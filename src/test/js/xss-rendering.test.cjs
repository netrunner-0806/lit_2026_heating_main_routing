const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
const flush = async () => { for (let i = 0; i < 15; i++) await new Promise(setImmediate); };

function app() {
  const elements = new Map(), listeners = new Map(), popups=[], tooltips=[];
  let prefix = null, uploadProgress; const tiles = [];
  function element(id) {
    const classes = new Set();
    const el = { id, innerHTML: '', children: [], hidden: false, value: 'default', dataset: {}, tagName: 'DIV', style: {}, files: [],
      classList: { toggle(c, on) { (on === undefined ? !classes.has(c) : on) ? classes.add(c) : classes.delete(c); }, add: (c) => classes.add(c), remove: (c) => classes.delete(c), contains: (c) => classes.has(c) },
      addEventListener(type, fn) { listeners.set(id + ':' + type, fn); },
      querySelector: () => element(), querySelectorAll: () => [],
      appendChild(child) { this.children.push(child); } };
    return el;
  }
  const layer = () => { const l = { addTo: () => l, setView: () => l, fitBounds() {}, removeLayer() {}, clearLayers() {}, addLayer() {}, on() {}, bindPopup: (html) => { popups.push(html); return l; }, bindTooltip: (html) => {tooltips.push(html);return l;} }; return l; };
  const context = {
    HeatnetUpload: class { constructor(onProgress) { this.saved = null; uploadProgress = onProgress; } },
    console, URLSearchParams, location: { search: '' },
    document: { getElementById(id) { if (!elements.has(id)) elements.set(id, element(id)); return elements.get(id); },
      createElement: () => element(), querySelector: () => null },
    L: { map: () => { const m = layer(); m.attributionControl = { setPrefix(p) { prefix = p; } }; return m; }, tileLayer: (url, options) => { const l = layer(); tiles.push({ url, options, added: false }); l.addTo = () => { tiles[tiles.length - 1].added = true; return l; }; return l; }, geoJSON: () => layer(), circleMarker: () => layer(), latLngBounds: () => ({ isValid: () => false }), layerGroup: () => layer(), control: () => layer(),
      DomUtil: { create: () => element() }, DomEvent: { disableClickPropagation() {} } },
    setTimeout() { return {}; }, clearTimeout() {},
    async fetch() { return { ok: true, status: 200, json: async () => [] }; },
  };
  vm.runInNewContext(source.replace('  loadJobs();\n  const jobParam', `  globalThis.audit = {showVariant, renderBadge, showFeature, renderSummary, renderExplainSummary, renderComparison, renderValidation, loadJobs, drawInput, setResult: (r) => {resultData=r;}, setInput: (r) => {inputGeo=r;}, setGeo: (r) => {resultGeo=r;indexResultGeometry(r);}};\n  loadJobs();\n  const jobParam`), context, { filename: 'app.js' });
  return { audit: context.audit, popups, tooltips, elements, listeners, tiles, prefix: () => prefix, uploadProgress: (p) => uploadProgress(p) };
}

const payload = '<img src=x onerror=alert(1)>';
const escaped = '&lt;img src=x onerror=alert(1)&gt;';
function safe(html) { assert(!html.includes(payload), html); assert(html.includes(escaped), html); }

test('feature keys and inspector values are text, including depth metadata', () => {
  const ui=app(); ui.audit.showFeature({object_type:'heat_network', [payload]:payload,
    depth_start:3, depth_end:3, slope:payload, k_depth:payload, laying_method:'special', k_spec:payload});
  safe(ui.elements.get('feature').innerHTML); safe(ui.elements.get('inspector').innerHTML);
});
test('human-readable decisions escape input line IDs retained in attachment descriptions', () => {
  const ui=app(); ui.audit.renderExplainSummary('v1', [{type:'TIE_IN_SELECTED',objectId:payload,
    decision:'new chamber on line '+payload,inputs:{},reason:payload}]);
  safe(ui.elements.get('explainSummary').innerHTML);
});
test('summary, variants and validation never interpolate raw API text', () => {
  const ui=app(); ui.audit.renderSummary({unconnected_oks_ids:[payload]}, {strategy:payload,notes:[payload]});
  safe(ui.elements.get('summary').innerHTML); safe(ui.elements.get('unconnected').innerHTML);
  ui.audit.setResult({variants:[{variantId:payload,strategy:payload}, {variantId:'v2'}],validation:{issues:[{severity:payload,code:payload,message:payload}]}});
  ui.audit.renderComparison(payload); safe(ui.elements.get('comparison').innerHTML);
  ui.audit.renderValidation(payload); safe(ui.elements.get('validation').innerHTML);
});
test('GeoJSON popup IDs, names and properties stay escaped', () => {
  const ui=app(); ui.audit.setInput({features:['source','heat_chamber','oks_connection_point','heat_network','restriction'].map(object_type=>({
    properties:{object_type,id:payload,name:payload,restriction_type:payload,flow_tph:payload,diameter:payload,address:payload},
    geometry:{type:object_type==='heat_network'?'LineString':object_type==='restriction'?'Polygon':'Point',coordinates:[37,55]}}))});
  ui.audit.drawInput(); assert.equal(ui.popups.length,5); ui.popups.forEach(safe);
});

test('result map tooltips and validation badge escape API text', () => {
  const ui=app(); ui.audit.setInput({features:[]}); ui.audit.setResult({mode:'PLANAR',variants:[],validation:{issues:[]}});
  ui.audit.setGeo({features:['heat_network','heat_chamber','technical_node'].map(object_type=>({
    properties:{object_type,variant_id:'v1',id:payload,diameter:payload,laying_method:'special',k_spec:payload},
    geometry:{type:object_type==='heat_network'?'LineString':'Point',coordinates:[37,55]}}))});
  ui.audit.showVariant('v1');assert.equal(ui.tooltips.length,3);ui.tooltips.forEach(safe);
  ui.audit.renderBadge({errors:payload,warnings:payload,checks:{geometry:payload}});safe(ui.elements.get('badge').innerHTML);
});
