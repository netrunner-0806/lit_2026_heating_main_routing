/* Demo UI: upload -> run (2D or depth mode) -> poll -> map, variants, profile, inspector, comparison, explainability. */
(function () {
  const $ = (id) => document.getElementById(id);
  const fmtMoney = (v) => (v == null ? '—' : Math.round(v).toLocaleString('ru-RU') + ' руб.');
  const fmtNum = (v, d = 1) => (v == null ? '—' : Number(v).toLocaleString('ru-RU', { maximumFractionDigits: d, minimumFractionDigits: d }));
  const SCORE_DECIMALS = 4;
  const fmtScore = (v) => fmtNum(v, SCORE_DECIMALS);
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  // ---- map (Leaflet, OpenStreetMap Standard basemap with visible attribution) ----
  const OSM_TILES = { url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors', maxZoom: 19 };
  const map = L.map('map', { preferCanvas: true, attributionControl: true }).setView([55.75, 37.62], 13);
  const osm = L.tileLayer(OSM_TILES.url, { maxZoom: OSM_TILES.maxZoom, attribution: OSM_TILES.attribution }).addTo(map);
  $('basemap').addEventListener('change', (e) => (e.target.checked ? osm.addTo(map) : map.removeLayer(osm)));

  const RESTRICTION_COLORS = { oks: '#8d99ae', water: '#4ea8de', railway: '#b5651d', road: '#e9c46a', tram_tracks: '#c77dff', gas_pipeline: '#f4a261', power_cable: '#e63946', park: '#52b788', social_area: '#90be6d', prohibited_site: '#6c757d' };
  const layerDefs = [
    { key: 'restrictions', name: 'Ограничения (restriction)', color: '#8d99ae' },
    { key: 'existing', name: 'Существующая сеть', color: '#d62828' },
    { key: 'chambers', name: 'Существующие камеры', color: '#000' },
    { key: 'points', name: 'Точки подключения ОКС', color: '#2a9d8f' },
    { key: 'source', name: 'Источник', color: '#f77f00' },
    { key: 'newNet', name: 'Новая сеть (base / special)', color: '#1d4ed8' },
    { key: 'newChambers', name: 'Новые камеры', color: '#facc15' },
    { key: 'techNodes', name: 'Технические узлы', color: '#7c3aed' },
  ];
  const groups = {};
  layerDefs.forEach((d) => {
    groups[d.key] = L.layerGroup().addTo(map);
    const label = document.createElement('label');
    label.innerHTML = `<input type="checkbox" checked data-layer="${d.key}"> <span class="sw" style="background:${d.color}"></span>${d.name}`;
    label.querySelector('input').addEventListener('change', (e) => (e.target.checked ? groups[d.key].addTo(map) : map.removeLayer(groups[d.key])));
    $('layers').appendChild(label);
  });
  const legend = L.control({ position: 'bottomright' });
  legend.onAdd = () => {
    const div = L.DomUtil.create('div', 'legend');
    const rows = [
      ['existing', '<span class="sw" style="border-color:#d62828"></span>Existing heat network'],
      ['newNet', '<span class="sw" style="border-color:#1d4ed8"></span>Generated heat network'],
      ['newNet', '<span class="sw" style="border-color:#d000d0"></span>Special passages', 'special'],
      ['points', '<span class="dot" style="background:#2a9d8f;border-color:#1b4332"></span>OKS connection points'],
      ['chambers', '<span class="dot" style="background:#fff;border-color:#000"></span>Existing chambers'],
      ['newChambers', '<span class="dot" style="background:#facc15;border-color:#7a5c00"></span>New chambers'],
      ['techNodes', '<span class="dot" style="background:#7c3aed;border-color:#4c1d95;width:8px;height:8px"></span>Technical nodes'],
      ['restrictions', '<span class="box" style="background:#8d99ae"></span>Restrictions (oks / water / railway / road…)'],
    ];
    div.innerHTML = '<h4>Legend</h4>' + rows.map((r) => r[2] === 'special' ? `<label>${r[1]}</label>` : `<label><input type="checkbox" checked data-legend="${r[0]}"> ${r[1]}</label>`).join('');
    L.DomEvent.disableClickPropagation(div);
    div.querySelectorAll('input[data-legend]').forEach((cb) => cb.addEventListener('change', (e) => {
      const k = e.target.dataset.legend;
      e.target.checked ? groups[k].addTo(map) : map.removeLayer(groups[k]);
      const side = document.querySelector(`#layers input[data-layer="${k}"]`);
      if (side) side.checked = e.target.checked;
    }));
    return div;
  };
  legend.addTo(map);

  // ---- state ----
  let mode = 'PLANAR';
  let currentJob = null, jobMeta = null, resultData = null, resultGeo = null, inputGeo = null, currentVariant = null, pollTimer = null;
  let reference = null, explainData = null, otherMode = null;
  const STAGES = ['Parsing input', 'Building routing graph', 'Generating variants · flows · DU · depth profiles · cost', 'Validating', 'Done'];

  $('modeSwitch').querySelectorAll('button').forEach((b) => b.addEventListener('click', () => {
    mode = b.dataset.mode;
    $('modeSwitch').querySelectorAll('button').forEach((x) => x.classList.toggle('active', x === b));
  }));

  function setStatus(text, isError) { const s = $('status'); s.textContent = text; s.className = 'status' + (isError ? ' error' : ''); }

  function renderStages(progress, status) {
    const box = $('stages');
    if (!progress) { box.hidden = true; return; }
    let cur = 0;
    const p = progress.toLowerCase();
    if (p.startsWith('parsing')) cur = 0; else if (p.startsWith('building')) cur = 1; else if (p.startsWith('generating')) cur = 2; else if (p.startsWith('validating')) cur = 3; else if (p.startsWith('done')) cur = 4;
    const depth = (jobMeta && jobMeta.mode === 'DEPTH') || mode === 'DEPTH';
    box.innerHTML = STAGES.map((s, i) => {
      if (i === 2 && !depth) s = 'Generating variants · flows · DU · cost';
      const cls = i < cur || status === 'DONE' ? 'done' : i === cur ? 'current' : 'pending';
      return `<div class="stage ${cls}">${s}${i === cur && status !== 'DONE' ? ' — ' + esc(progress.replace(/^[^:]*:\s*/, '')) : ''}</div>`;
    }).join('');
    box.hidden = false;
  }

  $('run').addEventListener('click', async () => {
    const f = $('file').files[0];
    if (!f) { setStatus('Выберите файл GeoJSON', true); return; }
    const fd = new FormData();
    fd.append('file', f);
    fd.append('unknownRestrictionPolicy', $('policy').value);
    fd.append('maxVariants', $('maxVariants').value);
    $('run').disabled = true;
    setStatus('Загрузка ' + f.name + ' (' + Math.round(f.size / 1024) + ' КБ), режим ' + (mode === 'DEPTH' ? 'с учётом глубины' : '2D') + '…');
    try {
      const r = await fetch('/api/v1/jobs?mode=' + mode, { method: 'POST', body: fd });
      const j = await r.json();
      if (!r.ok) throw new Error(j.message || r.statusText);
      openJob(j.id);
    } catch (e) { setStatus('Ошибка: ' + e.message, true); $('run').disabled = false; }
  });

  $('rerunOther').addEventListener('click', async () => {
    if (!currentJob || !jobMeta) return;
    const other = jobMeta.mode === 'DEPTH' ? 'PLANAR' : 'DEPTH';
    $('run').disabled = true;
    const r = await fetch('/api/v1/jobs/' + currentJob + '/rerun?mode=' + other, { method: 'POST' });
    const j = await r.json();
    if (!r.ok) { setStatus('Ошибка: ' + (j.message || r.statusText), true); $('run').disabled = false; return; }
    openJob(j.id);
  });

  async function openJob(id) {
    currentJob = id;
    clearTimeout(pollTimer);
    ['resultCard', 'profileCard', 'explainCard', 'modeCompareCard', 'featureCard', 'techCard'].forEach((id) => { $(id).hidden = true; if ($(id).tagName === 'DETAILS') $(id).open = false; });
    poll();
  }

  async function poll() {
    const r = await fetch('/api/v1/jobs/' + currentJob);
    const j = await r.json();
    jobMeta = j;
    if (j.mode && j.mode !== mode) { // the switch reflects the mode of the job being shown
      mode = j.mode;
      $('modeSwitch').querySelectorAll('button').forEach((x) => x.classList.toggle('active', x.dataset.mode === mode));
    }
    renderStages(j.progress, j.status);
    if (j.status === 'QUEUED' || j.status === 'RUNNING') {
      setStatus(`Задание ${j.id.slice(0, 8)}… ${j.status} · режим ${j.mode === 'DEPTH' ? 'с учётом глубины' : '2D'}`);
      pollTimer = setTimeout(poll, 1500);
      return;
    }
    $('run').disabled = false;
    $('stages').hidden = true; // progress is shown only while the calculation runs
    renderDiagnostics(j);
    if (j.status === 'FAILED') {
      setStatus('Ошибка расчёта: ' + (j.error || '') + '\n' + (j.diagnostics || []).filter((d) => d.severity !== 'INFO').map((d) => `${d.severity} ${d.code}: ${d.text}`).join('\n'), true);
      loadJobs();
      return;
    }
    setStatus(`Готово · ${j.variantCount} вариант(а) · режим ${j.mode === 'DEPTH' ? 'с учётом глубины' : '2D'}`);
    await loadResult();
    loadJobs();
  }

  function renderDiagnostics(j) {
    const s = j.inputStats;
    if (!s) { $('diagCard').hidden = true; return; }
    const types = Object.entries(s.restriction_types || {}).map(([k, v]) => `${k} ${v}`).join(', ') || '—';
    const rows = [['Features', s.features], ['Existing networks', s.existing_networks], ['Existing chambers', s.existing_chambers], ['OKS points', s.oks_points],
      ['Restrictions', s.restrictions], ['Restriction types', types], ['Invalid geometries fixed', s.invalid_geometries_fixed],
      ['Unknown restriction types', `${s.unknown_restrictions} ${s.unknown_restrictions ? '[ ' + (j.unknownRestrictionPolicy === 'FORBIDDEN' ? 'forbidden' : 'ignored') + ' ]' : ''}`],
      ['Custom rules applied', s.custom_rule_restrictions], ['CRS', s.crs]];
    const crs = String(s.crs || '').replace('EPSG:', '').replace(' -> ', ' → ').replace('EPSG:', '');
    $('diagLine').textContent = `${s.features} объектов · ${s.oks_points} ОКС · ${s.restrictions} ограничений · CRS ${crs}`;
    $('diagnostics').innerHTML = '<table>' + rows.map(([k, v]) => `<tr><td>${k}</td><td>${esc(v)}</td></tr>`).join('') + '</table>';
    const warns = (j.diagnostics || []).filter((d) => d.severity === 'WARNING');
    if (warns.length) $('diagnostics').innerHTML += `<details><summary>Предупреждения: ${warns.length}</summary>${warns.slice(0, 20).map((d) => `<div class="notes">${esc(d.code)}: ${esc(d.text)}</div>`).join('')}</details>`;
    $('diagCard').hidden = false;
  }

  async function loadReference() {
    if (reference) return reference;
    reference = await fetch('/api/v1/reference').then((r) => r.json()).catch(() => null);
    return reference;
  }
  function heightOf(du) {
    const row = reference && reference.diameters ? reference.diameters.find((d) => d.du === du) : null;
    return row ? row.heightM : 0.3;
  }

  async function loadResult() {
    const [res, geo, inp] = await Promise.all([
      fetch('/api/v1/jobs/' + currentJob + '/result').then((r) => r.json()),
      fetch('/api/v1/jobs/' + currentJob + '/result.geojson').then((r) => r.json()),
      fetch('/api/v1/jobs/' + currentJob + '/input.geojson').then((r) => r.json()).catch(() => null),
    ]);
    await loadReference();
    resultData = res; resultGeo = geo; inputGeo = inp; explainData = null; otherMode = null;
    const depth = res.mode === 'DEPTH';
    $('modeTag').textContent = depth ? 'режим с учётом глубины' : '2D';
    $('download').href = '/api/v1/jobs/' + currentJob + '/result.geojson';
    $('downloadReport').href = '/api/v1/jobs/' + currentJob + '/report.json';
    $('downloadCsv').href = '/api/v1/jobs/' + currentJob + '/report.csv';
    $('rerunOther').textContent = depth ? 'Рассчитать в 2D' : 'Рассчитать с учётом глубины';
    $('colorModeRow').hidden = !depth;
    if (!depth) $('colorMode').value = 'default';
    $('techJob').innerHTML = `Задание <span class="mono">${esc(currentJob)}</span> · ${esc(jobMeta && jobMeta.originalFilename || '')} · режим ${depth ? 'с учётом глубины' : '2D'}`;
    $('techCard').hidden = false;
    $('resultCard').hidden = false;
    renderBadge(res.validation);
    renderPerf(res);
    const tabs = $('variantTabs');
    tabs.innerHTML = '';
    (res.variants || []).forEach((v) => {
      const b = document.createElement('button');
      b.textContent = `${v.variantId} · rank ${v.rank} · S=${fmtScore(v.score)}`;
      b.addEventListener('click', () => showVariant(v.variantId));
      tabs.appendChild(b);
    });
    drawInput();
    if (res.variants && res.variants.length) showVariant(res.variants[0].variantId);
    loadModeComparison();
    loadExplain();
  }

  function renderPerf(res) {
    const m = res.metrics || {};
    const secs = ((m.total_time_ms || res.elapsedMs || 0) / 1000).toFixed(1);
    const details = Object.entries(m).filter(([k]) => k !== 'mode').map(([k, v]) => `${k}: ${v}`).join(' · ');
    $('perf').textContent = `Время расчёта: ${secs.replace('.', ',')} с`;
    $('techPerf').innerHTML = `Метрики расчёта: ${esc(details)}`;
  }

  function renderBadge(val) {
    const b = $('badge');
    if (!val) { b.hidden = true; return; }
    const checks = Object.values(val.checks || {}).reduce((a, x) => a + x, 0);
    const errors = val.errors || 0, warnings = val.warnings || 0;
    let cls, title;
    if (errors > 0) { cls = 'invalid'; title = 'INVALID'; }
    else if (warnings > 0) { cls = 'warn'; title = 'VALID WITH WARNINGS'; }
    else { cls = 'valid'; title = 'VALID'; }
    b.className = 'badge ' + cls;
    b.innerHTML = `<span class="big">${title}</span><span class="small">${checks.toLocaleString('ru-RU')} checks · ${errors} errors${warnings ? ' · ' + warnings + ' warnings' : ''}</span>`;
    b.hidden = false;
  }

  function drawInput() {
    ['restrictions', 'existing', 'chambers', 'points', 'source'].forEach((k) => groups[k].clearLayers());
    if (!inputGeo) return;
    const bounds = [];
    inputGeo.features.forEach((f) => {
      const p = f.properties || {}; const t = p.object_type; let layer;
      if (t === 'restriction') {
        const c = RESTRICTION_COLORS[p.restriction_type] || '#ff69b4';
        const isLine = f.geometry && /Line/.test(f.geometry.type);
        layer = L.geoJSON(f, { style: isLine ? { color: c, weight: 3, dashArray: '6 4' } : { color: c, weight: 1, fillColor: c, fillOpacity: 0.35 } });
        layer.bindPopup(`<b>restriction</b> ${p.restriction_type}<br>id: ${p.id}${p.address ? '<br>' + esc(p.address) : ''}`);
        groups.restrictions.addLayer(layer);
      } else if (t === 'heat_network') {
        layer = L.geoJSON(f, { style: { color: '#d62828', weight: 4, opacity: 0.85 } });
        layer.bindPopup(`<b>Существующая сеть</b> id ${p.id}<br>ДУ ${p.diameter}`);
        groups.existing.addLayer(layer);
      } else if (t === 'heat_chamber') {
        const [x, y] = f.geometry.coordinates;
        layer = L.circleMarker([y, x], { radius: 6, color: '#000', fillColor: '#fff', fillOpacity: 1, weight: 2 }).bindPopup(`<b>Камера</b> id ${p.id}`);
        groups.chambers.addLayer(layer);
      } else if (t === 'oks_connection_point') {
        const [x, y] = f.geometry.coordinates;
        layer = L.circleMarker([y, x], { radius: 7, color: '#1b4332', fillColor: '#2a9d8f', fillOpacity: 1, weight: 2 }).bindPopup(`<b>Точка подключения ОКС</b> id ${p.id}<br>расход ${p.flow_tph} т/ч`);
        groups.points.addLayer(layer);
      } else if (t === 'source') {
        const [x, y] = f.geometry.coordinates;
        layer = L.circleMarker([y, x], { radius: 9, color: '#9c2a00', fillColor: '#f77f00', fillOpacity: 1, weight: 2 }).bindPopup(`<b>Источник</b> ${esc(p.name || '')} id ${p.id}`);
        groups.source.addLayer(layer);
      }
      if (layer && layer.getBounds) { const b = layer.getBounds(); if (b.isValid()) bounds.push(b); }
      else if (layer && layer.getLatLng) bounds.push(L.latLngBounds(layer.getLatLng(), layer.getLatLng()));
    });
    if (bounds.length) map.fitBounds(bounds.reduce((a, b) => a.extend(b)), { padding: [20, 20] });
  }

  function duWeight(du) { return du <= 100 ? 3 : du <= 200 ? 4 : du <= 400 ? 5 : 7; }
  function depthColor(d) {
    if (d == null) return '#1d4ed8';
    if (d < 2.95) { const t = Math.max(0, Math.min(1, (2.95 - d) / 2.25)); return `hsl(${170 - 20 * t}, 60%, ${45 - 10 * t}%)`; }
    if (d > 3.05) { const t = Math.max(0, Math.min(1, (d - 3.05) / 2.0)); return `hsl(${20 - 20 * t}, 75%, ${45 - 10 * t}%)`; }
    return '#1d4ed8';
  }
  $('colorMode').addEventListener('change', () => { if (currentVariant) showVariant(currentVariant); });

  function variantFeatures(vid) { return resultGeo.features.filter((f) => String(f.properties.variant_id) === String(vid)); }

  function showVariant(vid) {
    currentVariant = vid;
    [...$('variantTabs').children].forEach((b) => b.classList.toggle('active', b.textContent.startsWith(vid + ' ')));
    ['newNet', 'newChambers', 'techNodes'].forEach((k) => groups[k].clearLayers());
    const feats = variantFeatures(vid);
    const byDepth = $('colorMode').value === 'depth' && resultData.mode === 'DEPTH';
    $('depthLegend').hidden = !byDepth;
    if (byDepth) $('depthLegend').innerHTML = '<div>Глубина верха габарита новой сети</div><div class="bar"></div><div class="ticks"><span>0.7 m (мельче)</span><span>3.0 m</span><span>4.0+ m (глубже)</span></div>';
    feats.forEach((f) => {
      const p = f.properties;
      if (p.object_type === 'heat_network') {
        const special = p.laying_method === 'special';
        const mid = p.depth_start != null ? (p.depth_start + p.depth_end) / 2 : null;
        const color = byDepth ? depthColor(mid) : (special ? '#d000d0' : '#1d4ed8');
        const layer = L.geoJSON(f, { style: { color, weight: duWeight(p.diameter) + (special && byDepth ? 2 : 0), opacity: 0.95, dashArray: special && byDepth ? '8 4' : null } });
        layer.on('click', () => showFeature(p));
        const depthTxt = p.depth_start != null ? ` · h ${fmtNum(p.depth_start, 2)}→${fmtNum(p.depth_end, 2)} м · Kгл ${p.k_depth}` : '';
        layer.bindTooltip(`ДУ ${p.diameter} · ${fmtNum(p.flow_tph, 2)} т/ч · ${fmtNum(p.length, 1)} м${special ? ' · спецпроход K=' + p.k_spec : ''}${depthTxt}`, { sticky: true });
        groups.newNet.addLayer(layer);
      } else if (p.object_type === 'heat_chamber') {
        const [x, y] = f.geometry.coordinates;
        const m = L.circleMarker([y, x], { radius: 7, color: '#7a5c00', fillColor: '#facc15', fillOpacity: 1, weight: 2 });
        m.on('click', () => showFeature(p));
        m.bindTooltip(`Новая камера ${p.id}: ДУ ${p.diameter}, ${fmtMoney(p.cost)}`);
        groups.newChambers.addLayer(m);
      } else if (p.object_type === 'technical_node') {
        const [x, y] = f.geometry.coordinates;
        const m = L.circleMarker([y, x], { radius: 4, color: '#4c1d95', fillColor: '#7c3aed', fillOpacity: 1, weight: 1 });
        m.on('click', () => showFeature(p));
        m.bindTooltip(`Технический узел ${p.id}`);
        groups.techNodes.addLayer(m);
      }
    });
    const s = feats.find((f) => f.properties.object_type === 'variant_summary');
    const v = (resultData.variants || []).find((x) => x.variantId === vid) || {};
    renderSummary(s ? s.properties : {}, v);
    renderComparison(vid);
    renderValidation(vid);
    renderProfilePanel(vid);
    renderExplain(vid);
  }

  function renderSummary(s, v) {
    const kv = (k, val, wide) => `<div class="kv${wide ? ' wide' : ''}"><div class="k">${k}</div><div class="v">${val}</div></div>`;
    const total = (inputGeo ? inputGeo.features : []).filter((f) => f.properties.object_type === 'oks_connection_point').length;
    const connected = v.connectedOksCount ?? '—';
    $('summary').innerHTML =
      kv('Score', fmtScore(s.score)) + kv('Итоговая стоимость', fmtMoney(s.calculated_cost)) +
      kv('Длина новой сети', fmtNum(s.new_network_length, 1) + ' м') + kv('Подключено ОКС', total ? `${connected} из ${total}` : `${connected}`) +
      kv('Новые камеры', `${v.newChamberCount ?? '—'} · ${fmtMoney(s.chamber_construction_cost)}`) + kv('Врезки в сущ. камеры', `${s.existing_chamber_tie_in_count} · ${fmtMoney(s.existing_chamber_tie_in_cost)}`) +
      kv('Штраф', fmtMoney(s.unconnected_penalty)) + kv('Стратегия', v.strategy || s.strategy || '—');
    const ds = $('depthSummary');
    if (s.mode === 'depth') {
      ds.hidden = false;
      ds.innerHTML = kv('Макс. глубина', fmtNum(s.max_depth, 2) + ' м') + kv('Стоимость заглубления', fmtMoney(s.depth_extra_cost)) +
        kv('Пересечения по вертикали', `${s.vertical_crossing_count} · над ${s.above_crossing_count} · под ${s.below_crossing_count}`) + kv('Переходов глубины', s.depth_transition_count);
    } else ds.hidden = true;
    const u = s.unconnected_oks_ids || [];
    $('unconnected').innerHTML = u.length ? `<span class="bad">Не подключены (маршрут не найден): ${u.join(', ')}</span>` : '<span class="ok">Все точки подключения подключены</span>';
    const notes = (v.notes || []).map(esc).join('<br>');
    const attach = (v.attachmentPoints || []).map(esc).join('; ');
    $('techJob').innerHTML = $('techJob').innerHTML.split('<br>')[0] + (attach ? `<br>Точки присоединения: ${attach}` : '') + (notes ? `<br>${notes}` : '');
  }

  function renderComparison(vid) {
    const vs = resultData.variants || [];
    if (vs.length < 2) { $('comparison').innerHTML = ''; return; }
    const rows = vs.map((v) => `<tr class="${v.variantId === vid ? 'active' : ''}"><td>${v.variantId} · ${v.strategy}</td><td>${v.rank}</td><td>${fmtScore(v.score)}</td>` +
      `<td>${fmtMoney(v.calculatedCost)}</td><td>${fmtNum(v.newNetworkLength, 1)}</td><td>${v.newChamberCount}</td><td>${v.existingChamberTieInCount}</td>` +
      `<td>${v.connectedOksCount}</td><td>${fmtNum(v.sharedNetworkLength, 1)}</td><td>${v.rootCount}</td>${v.mode === 'depth' ? `<td>${fmtNum(v.maxDepth, 2)}</td>` : ''}</tr>`).join('');
    let html = `<table><tr><th>variant</th><th>rank</th><th>score</th><th>cost</th><th>length, м</th><th>new ch.</th><th>tie-ins</th><th>OKS</th><th>shared, м</th><th>roots</th>${vs[0].mode === 'depth' ? '<th>max h</th>' : ''}</tr>${rows}</table>`;
    const cur = vs.find((v) => v.variantId === vid);
    if (cur && cur.diffVsBest) html += `<div class="diff">${esc(cur.diffVsBest.text)}</div>`;
    else if (cur) html += `<div class="diff">${vid} is the reference (rank 1) for the comparison</div>`;
    $('comparison').innerHTML = html;
  }

  const GROUPS = [['Output schema', (k) => k === 'schema' || k === 'references'], ['Geometry & topology', (k) => ['forest', 'degrees', 'turns', 'intersections'].includes(k)],
    ['Hydraulics', (k) => k === 'hydraulics'], ['Restrictions', (k) => k.startsWith('restriction:')], ['Depth', (k) => k.startsWith('depth:')], ['Cost', (k) => k === 'cost']];
  function renderValidation(vid) {
    const val = resultData.validation;
    if (!val) { $('validation').innerHTML = ''; return; }
    const issues = (val.issues || []).filter((i) => !i.variantId || i.variantId === vid);
    const errors = issues.filter((i) => i.severity === 'ERROR'), warnings = issues.filter((i) => i.severity === 'WARNING');
    const total = Object.values(val.checks || {}).reduce((a, b) => a + b, 0);
    let html = errors.length ? `<span class="bad">Валидатор: ${errors.length} ошибок</span>` : `<span class="ok">Валидатор: ошибок нет</span>`;
    html += ` · проверок: ${total}` + (warnings.length ? `, предупреждений ${warnings.length}` : '');
    const grouped = GROUPS.map(([name, test]) => [name, Object.entries(val.checks || {}).filter(([k]) => test(k)).reduce((a, [, v]) => a + v, 0)]).filter(([, n]) => n > 0);
    html += `<details><summary>по группам проверок</summary><div class="groups">${grouped.map(([n, c]) => `<span>${n}</span><span>${c}</span>`).join('')}</div></details>`;
    if (issues.length) html += '<ul>' + issues.slice(0, 50).map((i) => `<li><b>${i.severity}</b> ${esc(i.code)}: ${esc(i.message)}</li>`).join('') + '</ul>';
    $('validation').innerHTML = html;
  }

  // ---- feature details + crossing inspector ----
  function showFeature(p) {
    $('featureCard').hidden = false;
    const rows = Object.entries(p).filter(([k]) => k !== 'variant_id' && k !== 'crossings').map(([k, v]) => {
      let val = v;
      if (k === 'cost') val = fmtMoney(v);
      else if (Array.isArray(v)) val = v.join(', ') || '—';
      else if (v == null) val = 'null';
      return `<tr><td>${k}</td><td>${esc(val)}</td></tr>`;
    }).join('');
    $('feature').innerHTML = `<table>${rows}</table>`;
    const ins = $('inspector');
    if (p.object_type === 'heat_network' && Array.isArray(p.crossings) && p.crossings.length) {
      ins.innerHTML = p.crossings.map((c) => {
        const utility = c.utility_top != null;
        const ok = utility ? c.actual_clearance >= c.required_clearance - 1e-6 : c.actual_depth >= c.required_min_depth - 1e-6;
        return `<div class="cross"><div><b>${esc(c.type)}</b> · ${esc(c.object)}</div>
          <div>Способ: <b>${esc(c.method)}</b></div>
          ${utility ? `<div>Existing utility: top ${fmtNum(c.utility_top, 2)} m · bottom ${fmtNum(c.utility_bottom, 2)} m</div>` : `<div>Required cover: ≥ ${fmtNum(c.required_min_depth, 2)} m</div>`}
          <div>New heat network: top ${fmtNum(c.new_top, 2)} m · bottom ${fmtNum(c.new_bottom, 2)} m (H ${fmtNum(c.new_height, 3)} m)</div>
          ${utility ? `<div>Required clearance: ${fmtNum(c.required_clearance, 2)} m · Actual: ${fmtNum(c.actual_clearance, 2)} m</div>` : `<div>Actual depth: ${fmtNum(c.actual_depth, 2)} m</div>`}
          <div>Status: <span class="st ${ok ? 'ok' : 'bad'}">${ok ? 'VALID' : 'VIOLATION'}</span> · Kspec ${c.k_spec} · Kdepth ${p.k_depth} · ${fmtMoney(p.cost)}</div></div>`;
      }).join('');
    } else if (p.object_type === 'heat_network' && p.depth_start != null) {
      ins.innerHTML = `<div class="cross">Глубина ${fmtNum(p.depth_start, 2)} → ${fmtNum(p.depth_end, 2)} м · уклон ${p.slope} · Kгл ${p.k_depth}${p.laying_method === 'special' ? ' · Kспец ' + p.k_spec : ''}</div>`;
    } else ins.innerHTML = '';
  }

  // ---- longitudinal profile ----
  function nodeIndex(feats) {
    const nodes = {};
    const existingChambers = new Set((inputGeo ? inputGeo.features : []).filter((f) => f.properties.object_type === 'heat_chamber').map((f) => String(f.properties.id)));
    feats.forEach((f) => { if (f.properties.object_type === 'heat_chamber' || f.properties.object_type === 'technical_node') nodes[String(f.properties.id)] = f.properties; });
    return { nodes, isRoot: (id) => existingChambers.has(String(id)) || (nodes[String(id)] && nodes[String(id)].chamber_kind === 'tie_in_on_existing_network') };
  }
  function pathFromOks(vid, oksId) {
    const feats = variantFeatures(vid).filter((f) => f.properties.object_type === 'heat_network');
    const idx = nodeIndex(variantFeatures(vid));
    const byNode = {};
    feats.forEach((f) => { [f.properties.start_node_id, f.properties.end_node_id].forEach((n) => (byNode[String(n)] = byNode[String(n)] || []).push(f)); });
    const path = []; let node = String(oksId); const used = new Set(); let guard = 0;
    while (guard++ < 500) {
      const next = (byNode[node] || []).find((f) => !used.has(f.properties.id));
      if (!next) break;
      used.add(next.properties.id);
      const fwd = String(next.properties.start_node_id) === node;
      const other = fwd ? next.properties.end_node_id : next.properties.start_node_id;
      path.push({ f: next, fwd, from: node, to: String(other) });
      node = String(other);
      if (idx.isRoot(node)) break;
    }
    return { path, idx };
  }
  function renderProfilePanel(vid) {
    if (!resultData || resultData.mode !== 'DEPTH') { $('profileCard').hidden = true; return; }
    const oks = (inputGeo ? inputGeo.features : []).filter((f) => f.properties.object_type === 'oks_connection_point').map((f) => f.properties.id);
    const feats = variantFeatures(vid).filter((f) => f.properties.object_type === 'heat_network');
    const connected = oks.filter((id) => feats.some((f) => String(f.properties.start_node_id) === String(id) || String(f.properties.end_node_id) === String(id)));
    if (!connected.length) { $('profileCard').hidden = true; return; }
    const sel = $('profileOks');
    sel.innerHTML = connected.map((id) => `<option value="${esc(id)}">ОКС ${esc(id)}</option>`).join('');
    sel.onchange = () => drawProfile(vid, sel.value);
    $('profileCard').hidden = false;
    drawProfile(vid, connected[0]);
  }
  function drawProfile(vid, oksId) {
    const { path, idx } = pathFromOks(vid, oksId);
    const depth = resultData.mode === 'DEPTH';
    let s = 0; const segs = [];
    path.forEach((e) => {
      const p = e.f.properties;
      const d0 = p.depth_start != null ? (e.fwd ? p.depth_start : p.depth_end) : 3.0, d1 = p.depth_start != null ? (e.fwd ? p.depth_end : p.depth_start) : 3.0;
      segs.push({ s0: s, s1: s + p.length, d0, d1, p, fromNode: e.from, toNode: e.to });
      s += p.length;
    });
    const total = s || 1;
    let maxD = 3.5;
    segs.forEach((g) => { maxD = Math.max(maxD, g.d0 + heightOf(g.p.diameter), g.d1 + heightOf(g.p.diameter)); (g.p.crossings || []).forEach((c) => { if (c.utility_bottom != null) maxD = Math.max(maxD, c.utility_bottom); }); });
    const W = 380, H = 260, L = 42, R = 10, T = 14, B = 26;
    const X = (d) => L + (d / total) * (W - L - R), Y = (h) => T + (h / (maxD + 0.5)) * (H - T - B);
    let svg = `<svg viewBox="0 0 ${W} ${H}" xmlns="http://www.w3.org/2000/svg">`;
    for (let h = 0; h <= maxD + 0.5; h += 1) svg += `<line x1="${L}" y1="${Y(h)}" x2="${W - R}" y2="${Y(h)}" stroke="#eef1f4"/><text x="${L - 4}" y="${Y(h) + 4}" font-size="9" text-anchor="end" fill="#6b7785">${h} м</text>`;
    svg += `<line x1="${L}" y1="${Y(0)}" x2="${W - R}" y2="${Y(0)}" stroke="#333" stroke-width="1.5"/><text x="${L + 2}" y="${Y(0) - 3}" font-size="9" fill="#333">поверхность 0</text>`;
    svg += `<line x1="${L}" y1="${Y(3)}" x2="${W - R}" y2="${Y(3)}" stroke="#94a3b8" stroke-dasharray="4 3"/><text x="${W - R - 2}" y="${Y(3) - 3}" font-size="9" text-anchor="end" fill="#64748b">обычная глубина 3,0 м</text>`;
    // crossings: utilities and road/tram zones
    segs.forEach((g) => (g.p.crossings || []).forEach((c) => {
      if (c.utility_top != null) svg += `<rect x="${X(g.s0)}" y="${Y(c.utility_top)}" width="${Math.max(2, X(g.s1) - X(g.s0))}" height="${Math.max(2, Y(c.utility_bottom) - Y(c.utility_top))}" fill="${RESTRICTION_COLORS[c.type] || '#999'}" opacity="0.8"><title>${esc(c.object)} ${c.utility_top}–${c.utility_bottom} м</title></rect>`;
      else svg += `<rect x="${X(g.s0)}" y="${Y(0)}" width="${Math.max(2, X(g.s1) - X(g.s0))}" height="${Y(c.required_min_depth) - Y(0)}" fill="${RESTRICTION_COLORS[c.type] || '#e9c46a'}" opacity="0.5"><title>${esc(c.object)}: cover ≥ ${c.required_min_depth} м</title></rect>`;
    }));
    // network top (and bottom)
    const top = segs.map((g) => `${X(g.s0)},${Y(g.d0)} ${X(g.s1)},${Y(g.d1)}`).join(' ');
    const bottom = segs.map((g) => `${X(g.s0)},${Y(g.d0 + heightOf(g.p.diameter))} ${X(g.s1)},${Y(g.d1 + heightOf(g.p.diameter))}`).join(' ');
    svg += `<polyline points="${bottom}" fill="none" stroke="#93c5fd" stroke-width="1.5"/><polyline points="${top}" fill="none" stroke="#1d4ed8" stroke-width="2.5"/>`;
    segs.forEach((g) => { if (Math.abs(g.d1 - g.d0) > 1e-6) svg += `<line x1="${X(g.s0)}" y1="${Y(g.d0)}" x2="${X(g.s1)}" y2="${Y(g.d1)}" stroke="#f59e0b" stroke-width="3"><title>уклон ${g.p.slope}</title></line>`; });
    // nodes
    segs.forEach((g, i) => {
      const n = idx.nodes[g.toNode];
      const isRoot = idx.isRoot(g.toNode);
      if (n && n.object_type === 'heat_chamber' || isRoot) svg += `<circle cx="${X(g.s1)}" cy="${Y(g.d1)}" r="4" fill="#facc15" stroke="#7a5c00"><title>камера ${esc(g.toNode)}</title></circle>`;
      else if (n && n.object_type === 'technical_node') svg += `<polygon points="${X(g.s1)},${Y(g.d1) - 5} ${X(g.s1) - 4},${Y(g.d1) + 3} ${X(g.s1) + 4},${Y(g.d1) + 3}" fill="#7c3aed"><title>технический узел ${esc(g.toNode)}</title></polygon>`;
      if (i === 0) svg += `<circle cx="${X(g.s0)}" cy="${Y(g.d0)}" r="4" fill="#2a9d8f" stroke="#1b4332"><title>ОКС ${esc(oksId)}</title></circle>`;
    });
    for (let d = 0; d <= total; d += Math.max(10, Math.round(total / 8 / 10) * 10)) svg += `<text x="${X(d)}" y="${H - 8}" font-size="9" text-anchor="middle" fill="#6b7785">${Math.round(d)}</text>`;
    svg += `<text x="${(L + W - R) / 2}" y="${H - 0}" font-size="9" text-anchor="middle" fill="#6b7785">расстояние вдоль трассы, м</text>`;
    svg += `<rect id="profileHit" x="${L}" y="${T}" width="${W - L - R}" height="${H - T - B}" fill="transparent"/></svg>`;
    $('profile').innerHTML = svg;
    const hit = $('profile').querySelector('#profileHit');
    hit.addEventListener('mousemove', (ev) => {
      const rect = hit.getBoundingClientRect();
      const d = ((ev.clientX - rect.left) / rect.width) * total;
      const g = segs.find((x) => d >= x.s0 && d <= x.s1) || segs[segs.length - 1];
      if (!g) return;
      const t = (d - g.s0) / Math.max(1e-9, g.s1 - g.s0);
      const h = g.d0 + (g.d1 - g.d0) * t;
      const cr = (g.p.crossings || []).map((c) => `${c.type} ${c.method}${c.actual_clearance != null ? ' · clearance ' + fmtNum(c.actual_clearance, 2) + ' м' : ''}`).join('; ');
      $('profileHover').textContent = `${Math.round(d)} м · глубина ${fmtNum(h, 2)} м · ДУ ${g.p.diameter} · ${g.p.laying_method}${cr ? ' · ' + cr : ''} · Kгл ${g.p.k_depth}${depth ? '' : ' (2D: глубина не рассчитывалась)'}`;
    });
  }

  // ---- planar vs depth comparison ----
  async function loadModeComparison() {
    $('modeCompareCard').hidden = true;
    if (!jobMeta) return;
    const other = jobMeta.mode === 'DEPTH' ? 'PLANAR' : 'DEPTH';
    const jobs = await fetch('/api/v1/jobs').then((r) => r.json()).catch(() => []);
    const cand = jobs.find((j) => j.status === 'DONE' && j.mode === other && j.originalFilename === jobMeta.originalFilename && j.inputSize === jobMeta.inputSize && j.variantCount > 0);
    if (!cand) return;
    const [res, geo] = await Promise.all([fetch('/api/v1/jobs/' + cand.id + '/result').then((r) => r.json()), fetch('/api/v1/jobs/' + cand.id + '/result.geojson').then((r) => r.json())]);
    otherMode = { job: cand, res, geo };
    const mine = resultData.variants[0], theirs = res.variants[0];
    const planar = jobMeta.mode === 'DEPTH' ? theirs : mine, depthV = jobMeta.mode === 'DEPTH' ? mine : theirs;
    const planarGeo = jobMeta.mode === 'DEPTH' ? geo : resultGeo, depthGeo = jobMeta.mode === 'DEPTH' ? resultGeo : geo;
    const crossingsByType = (g, vid) => { const m = {}; g.features.filter((f) => String(f.properties.variant_id) === vid && f.properties.object_type === 'heat_network' && f.properties.laying_method === 'special').forEach((f) => (f.properties.crossed_restrictions || []).forEach((c) => { const t = String(c).split('#')[0]; m[t] = (m[t] || 0) + 1; })); return m; };
    const pc = crossingsByType(planarGeo, planar.variantId), dc = crossingsByType(depthGeo, depthV.variantId);
    const row = (k, a, b) => `<tr><td>${k}</td><td>${a}</td><td>${b}</td></tr>`;
    let html = `<table><tr><th></th><th>PLANAR best</th><th>DEPTH best</th></tr>` +
      row('Cost', fmtMoney(planar.calculatedCost), fmtMoney(depthV.calculatedCost)) + row('Length', fmtNum(planar.newNetworkLength, 1) + ' м', fmtNum(depthV.newNetworkLength, 1) + ' м') +
      row('Score', fmtScore(planar.score), fmtScore(depthV.score)) + row('New chambers', planar.newChamberCount, depthV.newChamberCount) +
      row('Special crossings', planar.specialCrossingCount, depthV.specialCrossingCount) + row('Tie-ins', planar.existingChamberTieInCount, depthV.existingChamberTieInCount) +
      row('Max depth', '—', fmtNum(depthV.maxDepth, 2) + ' м') + row('Depth extra cost', '—', fmtMoney(depthV.depthExtraCost)) +
      row('Δ cost', '', fmtMoney(depthV.calculatedCost - planar.calculatedCost)) + row('Δ length', '', fmtNum(depthV.newNetworkLength - planar.newNetworkLength, 1) + ' м') + '</table>';
    const diffs = [];
    const pa = new Set(planar.attachmentPoints || []), da = new Set(depthV.attachmentPoints || []);
    const changed = [...da].filter((x) => !pa.has(x)).length;
    if (changed) diffs.push(`Depth-aware variant uses ${changed} other attachment point${changed > 1 ? 's' : ''}`);
    Object.keys({ ...pc, ...dc }).forEach((t) => { const d = (dc[t] || 0) - (pc[t] || 0); if (d < 0) diffs.push(`Route avoids ${-d} ${t} crossing${-d > 1 ? 's' : ''}`); if (d > 0) diffs.push(`Route adds ${d} ${t} crossing${d > 1 ? 's' : ''}`); });
    if (depthV.belowCrossingCount) diffs.push(`${depthV.belowCrossingCount} crossing${depthV.belowCrossingCount > 1 ? 's' : ''} pass BELOW the utility, ${depthV.aboveCrossingCount || 0} ABOVE`);
    if (!diffs.length) diffs.push('Same horizontal route; only the vertical profile differs');
    html += `<div class="diff">${diffs.map(esc).join('<br>')}</div><div class="notes">Ранжирование режимов раздельное; это сравнение лучших вариантов, а не общий rank.</div>`;
    $('modeCompare').innerHTML = html;
    const dCost = depthV.calculatedCost - planar.calculatedCost, dLen = depthV.newNetworkLength - planar.newNetworkLength;
    $('modeCompareLine').textContent = `Δ стоимости ${dCost >= 0 ? '+' : '−'}${fmtMoney(Math.abs(dCost))} · Δ длины ${dLen >= 0 ? '+' : '−'}${fmtNum(Math.abs(dLen), 1)} м`;
    $('modeCompareCard').hidden = false;
  }

  // ---- explainability ----
  async function loadExplain() {
    explainData = await fetch('/api/v1/jobs/' + currentJob + '/explain').then((r) => r.json()).catch(() => null);
    if (currentVariant) renderExplain(currentVariant);
  }
  function renderExplain(vid) {
    if (!explainData || !explainData.decisions) { $('explainCard').hidden = true; return; }
    const v = explainData.decisions.find((d) => d.variantId === vid);
    if (!v) { $('explainCard').hidden = true; return; }
    const decs = v.decisions || [];
    renderExplainSummary(vid, decs);
    const counts = {};
    decs.forEach((d) => (counts[d.type] = (counts[d.type] || 0) + 1));
    const order = ['VARIANT_RANKED', 'TIE_IN_SELECTED', 'ROUTE_SELECTED', 'CHAMBER_CREATED', 'ROUTE_REJECTED', 'DEPTH_BELOW_SELECTED', 'DEPTH_ABOVE_SELECTED', 'DEPTH_UNDER_SELECTED', 'DIAMETER_SELECTED'];
    let html = `<div class="notes">${Object.entries(counts).map(([k, n]) => `${k}: ${n}`).join(' · ')}</div>`;
    order.forEach((type) => {
      const items = decs.filter((d) => d.type === type);
      if (!items.length) return;
      html += `<details ${type === 'DIAMETER_SELECTED' ? '' : 'open'}><summary>${type} (${items.length})</summary>` + items.slice(0, 40).map((d) => {
        const inputs = Object.entries(d.inputs || {}).map(([k, x]) => `${k} = ${x}`).join(', ');
        const alts = (d.alternatives || []).map((a) => `<div class="r">↳ ${esc(a)}</div>`).join('');
        return `<div class="dec"><span class="t">${esc(d.objectId)}</span> → <b>${esc(d.decision)}</b><div class="r">${esc(d.reason)}</div>${inputs ? `<div class="r">${esc(inputs)}</div>` : ''}${alts}</div>`;
      }).join('') + '</details>';
    });
    $('explain').innerHTML = html;
    $('explainCard').hidden = false;
  }

  /** Short human-readable account of the variant's decisions (the raw event log stays in the technical details). */
  function renderExplainSummary(vid, decs) {
    const ru = (t) => String(t)
      .replace(/^new chamber on line (.+)$/, 'новая камера на существующей линии $1')
      .replace(/^existing chamber (.+)$/, 'существующая камера $1')
      .replace(/^existing junction chamber$/, 'существующая развилочная камера новой сети')
      .replace(/^new junction chamber on link#(\d+)$/, 'новая развилка на построенном участке');
    const lines = [];
    const ranked = decs.find((d) => d.type === 'VARIANT_RANKED');
    if (ranked) {
      const i = ranked.inputs || {};
      lines.push(`<b>Вариант ${esc(vid)}</b>: место ${esc(String(ranked.decision).replace(/^.*rank\s*/, ''))} по score ${fmtScore(i.score)}, стратегия ${esc(i.strategy || '—')}${i.candidates_total ? ` (из ${i.candidates_total} построенных вариантов)` : ''}.`);
    }
    // the final attachment of every point (the improvement pass may re-attach a point: keep the last decision)
    const last = new Map();
    decs.filter((d) => d.type === 'TIE_IN_SELECTED' || d.type === 'ROUTE_SELECTED').forEach((d) => last.set(String(d.objectId), d));
    last.forEach((d) => {
      const i = d.inputs || {};
      const how = d.type === 'TIE_IN_SELECTED' ? `врезка: ${ru(d.decision)}` : `подключение к новой сети (${ru(d.decision)})`;
      const extra = [i.route_length_m != null ? `маршрут ${fmtNum(i.route_length_m, 1)} м` : null, i.special_passages ? `спецпроходов ${i.special_passages}` : null,
        i.boundary_point === 'next feasible' ? 'выход через следующую допустимую точку границы' : null].filter(Boolean).join(', ');
      lines.push(`ОКС <b>${esc(d.objectId)}</b> → ${how}${extra ? ` — ${extra}` : ''}.`);
    });
    if (last.size) lines.push(`Для каждой точки выбран кандидат присоединения с минимальным приростом score среди оценённых (существующие камеры, новые камеры на линии, развилки на построенной сети).`);
    decs.filter((d) => d.type === 'ROUTE_REJECTED' && d.decision === 'unconnected').forEach((d) => lines.push(`ОКС <b>${esc(d.objectId)}</b> не подключён: ${esc(d.reason)}.`));
    const vs = ((resultData && resultData.variants) || []).find((x) => x.variantId === vid) || {};
    const chambers = vs.newChamberCount != null ? vs.newChamberCount : decs.filter((d) => d.type === 'CHAMBER_CREATED').length;
    if (chambers) lines.push(`Новых камер: ${chambers} — развилка допускается только в камере; врезка в существующую камеру делается, если она есть в 10 м и имеет свободное примыкание.`);
    const dus = decs.filter((d) => d.type === 'DIAMETER_SELECTED');
    if (dus.length) {
      const byFlow = dus.filter((d) => /^minimum DU/.test(d.reason || '')).length;
      const mono = dus.filter((d) => /must not decrease/.test(d.reason || '')).length;
      const len = dus.filter((d) => /maximum continuous length/.test(d.reason || '')).length;
      lines.push(`Диаметры: ${byFlow} участков — минимальный ДУ по расходу${mono ? `, ${mono} — увеличен, чтобы ДУ не уменьшался к сети` : ''}${len ? `, ${len} — увеличен из-за предельной длины` : ''}.`);
    }
    const above = decs.filter((d) => d.type === 'DEPTH_ABOVE_SELECTED').length, below = decs.filter((d) => d.type === 'DEPTH_BELOW_SELECTED').length, under = decs.filter((d) => d.type === 'DEPTH_UNDER_SELECTED').length;
    if (above + below + under) lines.push(`Пересечения по глубине: над коммуникацией — ${above}, под коммуникацией — ${below}, под дорогой/трамваем — ${under}; на каждом выбрана глубина плато, ближайшая к 3,0 м, при которой помещаются уклоны ≤ 0,10.`);
    $('explainSummary').innerHTML = lines.map((l) => `<div class="why">${l}</div>`).join('') || '<div class="notes">нет решений для этого варианта</div>';
    const rejected = decs.filter((d) => d.type === 'ROUTE_REJECTED' && d.decision === 'unconnected').length;
    $('explainLine').textContent = `${last.size} присоединений · ${chambers} новых камер${rejected ? ` · не подключено: ${rejected}` : ''}`;
  }

  async function loadJobs() {
    try {
      const jobs = await fetch('/api/v1/jobs').then((r) => r.json());
      $('jobs').innerHTML = jobs.slice(0, 10).map((j) => `<div class="job" data-id="${j.id}"><div>${esc(j.originalFilename || '—')} · ${j.mode === 'DEPTH' ? 'с учётом глубины' : '2D'} · ${j.status === 'DONE' ? `вариантов ${j.variantCount}` : j.status}</div><div class="id">${new Date(j.createdAt).toLocaleString('ru-RU')}</div></div>`).join('') || '<span class="muted">пока нет</span>';
      $('jobs').querySelectorAll('.job').forEach((el) => el.addEventListener('click', () => openJob(el.dataset.id)));
    } catch (e) { /* ignore */ }
  }
  loadJobs();
  const jobParam = new URLSearchParams(location.search).get('job');
  if (jobParam) openJob(jobParam);
})();
