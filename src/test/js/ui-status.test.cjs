const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
const flush = async () => { for (let i = 0; i < 15; i++) await new Promise(setImmediate); };

function app() {
  const elements = new Map(), listeners = new Map();
  let prefix = null, uploadProgress; const tiles = [];
  function element(id) {
    const classes = new Set();
    const el = { id, children: [], hidden: false, value: 'default', dataset: {}, tagName: 'DIV', style: {}, files: [],
      classList: { toggle(c, on) { (on === undefined ? !classes.has(c) : on) ? classes.add(c) : classes.delete(c); }, add: (c) => classes.add(c), remove: (c) => classes.delete(c), contains: (c) => classes.has(c) },
      addEventListener(type, fn) { listeners.set(id + ':' + type, fn); },
      querySelector: () => element(), querySelectorAll: () => [],
      appendChild(child) { this.children.push(child); } };
    return el;
  }
  const layer = () => { const l = { addTo: () => l, setView: () => l, fitBounds() {}, removeLayer() {}, clearLayers() {}, addLayer() {}, on() {}, bindPopup: () => l, bindTooltip: () => l }; return l; };
  const context = {
    HeatnetUpload: class { constructor(onProgress) { this.saved = null; uploadProgress = onProgress; } },
    console, URLSearchParams, location: { search: '' },
    document: { getElementById(id) { if (!elements.has(id)) elements.set(id, element(id)); return elements.get(id); },
      createElement: () => element(), querySelector: () => null },
    L: { map: () => { const m = layer(); m.attributionControl = { setPrefix(p) { prefix = p; } }; return m; }, tileLayer: (url, options) => { const l = layer(); tiles.push({ url, options, added: false }); l.addTo = () => { tiles[tiles.length - 1].added = true; return l; }; return l; }, layerGroup: () => layer(), control: () => layer(),
      DomUtil: { create: () => element() }, DomEvent: { disableClickPropagation() {} } },
    setTimeout() { return {}; }, clearTimeout() {},
    async fetch() { return { ok: true, status: 200, json: async () => [] }; },
  };
  vm.runInNewContext(source, context, { filename: 'app.js' });
  return { elements, listeners, tiles, prefix: () => prefix, uploadProgress: (p) => uploadProgress(p) };
}

test('attribution prefix (Leaflet link and its flag image) is disabled via the Leaflet API', async () => {
  const ui = app(); await flush();
  assert.strictEqual(ui.prefix(), false); // L.Control.Attribution#setPrefix(false) removes the prefix entirely
});

test('OpenStreetMap copyright attribution of the basemap is kept', async () => {
  const ui = app(); await flush();
  const osm = ui.tiles.find((t) => /tile\.openstreetmap\.org/.test(t.url));
  assert(osm && osm.added, 'OSM tile layer must be on the map');
  assert.match(osm.options.attribution, /&copy;\s*<a href="https:\/\/www\.openstreetmap\.org\/copyright"[^>]*>OpenStreetMap<\/a>\s*contributors/);
  assert.doesNotMatch(osm.options.attribution, /Leaflet/);
});

test('run without a file shows an error state instead of a fake progress', async () => {
  const ui = app(); await flush();
  await ui.listeners.get('run:click')();
  assert.equal(ui.elements.get('runStatus').hidden, false);
  assert.equal(ui.elements.get('runStatus').dataset.kind, 'error');
  assert.equal(ui.elements.get('rsBar').hidden, true);
  assert.equal(ui.elements.get('fileBox').dataset.state, 'error');
  assert.equal(ui.elements.get('run').disabled, undefined); // the button was never locked
});

test('resumable upload events retain byte progress, pause and completion in the new status panel', async () => {
  const ui = app(); await flush();
  ui.uploadProgress({ state: 'UPLOADING', sentBytes: 16 * 1024 ** 2, totalBytes: 64 * 1024 ** 2 });
  assert.equal(ui.elements.get('runStatus').dataset.kind, 'progress');
  assert.equal(ui.elements.get('rsFill').style.width, '25%');
  assert.match(ui.elements.get('rsValue').textContent, /16.*МиБ из 64.*МиБ/);
  ui.uploadProgress({ state: 'PAUSED', sentBytes: 16 * 1024 ** 2, totalBytes: 64 * 1024 ** 2 });
  assert.equal(ui.elements.get('runStatus').dataset.kind, 'paused');
  assert.equal(ui.elements.get('run').disabled, false);
  assert.equal(ui.elements.get('run').textContent, 'Продолжить загрузку');
  ui.uploadProgress({ state: 'COMPLETE', sentBytes: 64 * 1024 ** 2, totalBytes: 64 * 1024 ** 2 });
  assert.equal(ui.elements.get('uploadPanel').hidden, true);
  assert.equal(ui.elements.get('runStatus').dataset.kind, 'busy', '100% upload is not a completed calculation');
  assert.equal(ui.elements.get('rsTitle').textContent, 'Файл принят');
});
