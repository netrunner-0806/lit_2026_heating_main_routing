const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
const flush = async () => { for (let i = 0; i < 15; i++) await new Promise(setImmediate); };

function app({ inputSize, failStatus = false, failResult = false, previewStatus = 200 } = {}) {
  const elements = new Map(), requests = [], timers = [], drawn = [];
  function element() {
    return { children: [], hidden: false, value: 'default', dataset: {}, tagName: 'DIV',
      classList: { toggle() {}, add() {}, remove() {} }, addEventListener() {},
      querySelector: () => element(), querySelectorAll: () => [],
      appendChild(child) { this.children.push(child); } };
  }
  function layer(feature) {
    if (feature) drawn.push(feature);
    const l = { addTo: () => l, setView: () => l, fitBounds() {}, removeLayer() {}, clearLayers() {},
      addLayer() {}, on() {}, bindPopup: () => l, bindTooltip: () => l,
      getBounds: () => bounds(), getLatLng: () => [55.75, 37.6] };
    return l;
  }
  function bounds() { const b = { isValid: () => true, extend: () => b }; return b; }
  const inputFeature = { type: 'Feature', properties: { id: 1, object_type: 'heat_network', diameter: 100 },
    geometry: { type: 'LineString', coordinates: [[37.6, 55.75], [37.61, 55.76]] } };
  const status = { id: 'job', status: 'DONE', mode: 'PLANAR', variantCount: 0, inputSize, connectionPointCount: 1 };
  const context = {
    console, URLSearchParams, location: { search: '?job=job' },
    document: { getElementById(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); },
      createElement: element, querySelector: () => null },
    L: { map: () => layer(), tileLayer: () => layer(), layerGroup: () => layer(), control: () => layer(),
      DomUtil: { create: element }, DomEvent: { disableClickPropagation() {} },
      geoJSON: layer, circleMarker: () => layer(), latLngBounds: bounds },
    setTimeout(fn, ms) { const timer = { fn, ms }; timers.push(timer); return timer; },
    clearTimeout(timer) { if (timer) timer.cancelled = true; },
    async fetch(url) {
      requests.push(url);
      if (url === '/api/v1/jobs/job' && failStatus) { failStatus = false; throw new TypeError('offline'); }
      if (url.endsWith('/result.geojson') && failResult) {
        failResult = false; return { ok: false, status: 503, json: async () => ({ message: 'unavailable' }) };
      }
      let body = {};
      if (url === '/api/v1/jobs') body = [];
      else if (url === '/api/v1/jobs/job') body = status;
      else if (url.endsWith('/result')) body = { mode: 'PLANAR', variants: [], metrics: {} };
      else if (url.endsWith('/result.geojson')) body = { type: 'FeatureCollection', features: [] };
      else if (url.endsWith('/input-preview.geojson')) body = { type: 'FeatureCollection', features: [inputFeature] };
      else if (url.endsWith('/input.geojson')) throw new Error('Full input endpoint must not be requested');
      return { ok: true, status: url.endsWith('/input-preview.geojson') ? previewStatus : 200, json: async () => {
        assert.notEqual(url.endsWith('/input-preview.geojson') && previewStatus, 204, '204 must not be parsed as JSON');
        return body;
      } };
    },
  };
  vm.runInNewContext(source, context, { filename: 'app.js' });
  return { requests, timers, elements, drawn };
}

test('3 GiB input is never downloaded or parsed for the map', async () => {
  const ui = app({ inputSize: 3 * 1024 ** 3 }); await flush();
  assert(ui.requests.some(u => u.endsWith('/result.geojson')));
  assert(!ui.requests.some(u => /\/input(?:-preview)?\.geojson$/.test(u)));
  assert.equal(ui.timers.length, 0);
  assert.match(ui.elements.get('status').textContent, /Большой исходный файл/);
});

test('small input uses bounded preview and still draws existing layers', async () => {
  const ui = app({ inputSize: 1024 }); await flush();
  assert(ui.requests.some(u => u.endsWith('/input-preview.geojson')));
  assert(!ui.requests.some(u => u.endsWith('/input.geojson')));
  assert(ui.drawn.some(f => f.properties.object_type === 'heat_network'));
  assert.equal(ui.timers.length, 0);
});

test('unknown input size also avoids downloading input', async () => {
  const ui = app(); await flush();
  assert(!ui.requests.some(u => /\/input(?:-preview)?\.geojson$/.test(u)));
});

test('status polling retries after a network error', async () => {
  const ui = app({ inputSize: 3 * 1024 ** 3, failStatus: true }); await flush();
  assert.equal(ui.timers.length, 1);
  assert(ui.timers[0].ms >= 2000);
  await ui.timers[0].fn(); await flush();
  assert.equal(ui.requests.filter(u => u === '/api/v1/jobs/job').length, 2);
  assert(ui.requests.some(u => u.endsWith('/result.geojson')));
});

test('result HTTP error retries and recovers without downloading giant input', async () => {
  const ui = app({ inputSize: 3 * 1024 ** 3, failResult: true }); await flush();
  assert.equal(ui.timers.length, 1);
  await ui.timers[0].fn(); await flush();
  assert.equal(ui.requests.filter(u => u.endsWith('/result.geojson')).length, 2);
  assert(!ui.requests.some(u => /\/input(?:-preview)?\.geojson$/.test(u)));
});

test('server may decline preview with 204 without breaking result display', async () => {
  const ui = app({ inputSize: 1024, previewStatus: 204 }); await flush();
  assert.equal(ui.timers.length, 0);
  assert.equal(ui.elements.get('resultCard').hidden, false);
});
