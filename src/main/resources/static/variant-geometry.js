/* Plan-view overlap only, in metres. No geometry or engineering properties are changed in the result. */
(function (root) {
  'use strict';
  const LIMIT = 2000000, TOL = 0.05, CELL = 40;
  function compare(features) {
    const segments = [], grid = new Map(); let work = 0;
    const first = features.find(f => f.geometry && ['LineString', 'MultiLineString'].includes(f.geometry.type));
    if (!first) return [];
    const origin = first.geometry.type === 'LineString' ? first.geometry.coordinates[0] : first.geometry.coordinates[0][0];
    const sx = 6378137 * Math.PI / 180 * Math.cos(origin[1] * Math.PI / 180), sy = 6378137 * Math.PI / 180;
    const point = c => [(c[0] - origin[0]) * sx, (c[1] - origin[1]) * sy];
    function cells(a, b, visit) {
      for (let x = Math.floor((Math.min(a[0], b[0]) - TOL) / CELL); x <= Math.floor((Math.max(a[0], b[0]) + TOL) / CELL); x++)
        for (let y = Math.floor((Math.min(a[1], b[1]) - TOL) / CELL); y <= Math.floor((Math.max(a[1], b[1]) + TOL) / CELL); y++) {
          if (++work > LIMIT) throw new Error('Слишком много геометрии для сравнения: показано обычное наложение.');
          visit(x + ':' + y);
        }
    }
    for (const f of features) {
      if (!f.geometry || !['LineString', 'MultiLineString'].includes(f.geometry.type)) continue;
      const lines = f.geometry.type === 'LineString' ? [f.geometry.coordinates] : f.geometry.coordinates;
      for (const line of lines) for (let i = 1; i < line.length; i++) {
        const a = point(line[i - 1]), b = point(line[i]);
        if (Math.hypot(b[0] - a[0], b[1] - a[1]) < 1e-9) continue;
        const s = { a, b, ca: line[i - 1], cb: line[i], f }; segments.push(s);
        cells(a, b, key => { if (!grid.has(key)) grid.set(key, []); grid.get(key).push(s); });
      }
    }
    const output = [], grouped = new Map();
    for (const s of segments) {
      const candidates = new Set(), intervals = [], dx = s.b[0] - s.a[0], dy = s.b[1] - s.a[1], len = Math.hypot(dx, dy);
      cells(s.a, s.b, key => { for (const other of grid.get(key) || []) {
        if (++work > LIMIT) throw new Error('Лимит сравнения геометрии: показано обычное наложение.'); candidates.add(other);
      }});
      for (const t of candidates) {
        if (String(s.f.properties.variant_id) === String(t.f.properties.variant_id)) continue;
        const tx = t.b[0] - t.a[0], ty = t.b[1] - t.a[1], tl = Math.hypot(tx, ty);
        if (Math.abs(dx * ty - dy * tx) / (len * tl) > 0.0001) continue;
        const dist = p => Math.abs(dx * (p[1] - s.a[1]) - dy * (p[0] - s.a[0])) / len;
        if (dist(t.a) > TOL || dist(t.b) > TOL) continue;
        const proj = p => ((p[0] - s.a[0]) * dx + (p[1] - s.a[1]) * dy) / (len * len);
        const u = proj(t.a), v = proj(t.b), lo = Math.max(0, Math.min(u, v)), hi = Math.min(1, Math.max(u, v));
        if ((hi - lo) * len > 1e-6) intervals.push([lo, hi]);
      }
      intervals.sort((a, b) => a[0] - b[0]); const merged = [];
      for (const pair of intervals) { const last = merged[merged.length - 1]; if (last && pair[0] <= last[1] + 1e-10) last[1] = Math.max(last[1], pair[1]); else merged.push(pair.slice()); }
      const at = t => t === 0 ? s.ca.slice() : t === 1 ? s.cb.slice() : s.ca.map((v, i) => v + t * (s.cb[i] - v));
      const add = (a, b, common) => {
        if (b - a <= 1e-10) return;
        if (!grouped.has(s.f)) grouped.set(s.f, new Map());
        const groups = grouped.get(s.f);
        if (!groups.has(common)) {
          const feature = { type: 'Feature', properties: s.f.properties, geometry: { type: 'MultiLineString', coordinates: [] }, common };
          groups.set(common, feature); output.push(feature);
        }
        groups.get(common).geometry.coordinates.push([at(a), at(b)]);
      };
      let cursor = 0; for (const [lo, hi] of merged) { add(cursor, lo, false); add(lo, hi, true); cursor = hi; } add(cursor, 1, false);
    }
    return output;
  }
  if (typeof module !== 'undefined') module.exports = { compare };
  else root.onmessage = e => { try { root.postMessage({ features: compare(e.data) }); } catch (error) { root.postMessage({ error: error.message }); } };
})(typeof self === 'undefined' ? globalThis : self);
