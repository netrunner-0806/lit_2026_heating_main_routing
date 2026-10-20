const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../main/resources/static/app.js'),'utf8');
const flush=async()=>{for(let i=0;i<15;i++)await new Promise(setImmediate);};
function app(){
 const elements=new Map(),workers=[],timers=[],requests=[];let status={id:'job',status:'QUEUED',mode:'PLANAR'},mapCount=0, cameraChanges=0;const controls=[];
 function el(){const listeners={},children=[];let html='';const e={children,hidden:false,style:{},dataset:{},value:'default',files:[],classList:{toggle(){},add(){},remove(){}},listeners,addEventListener(t,f){assert(!listeners[t],'duplicate listener');listeners[t]=f;},appendChild(c){children.push(c);},querySelector(){return this.input||(this.input=el());},querySelectorAll(selector){return selector === 'input[data-variant]' ? this.checkboxes || [] : [];},setAttribute(k,v){this[k]=v;}};Object.defineProperty(e,'innerHTML',{get:()=>html,set:v=>{html=v;children.length=0;e.checkboxes=[...v.matchAll(/<input[^>]*data-variant="([^"]+)"[^>]*>/g)].map(m=>({dataset:{variant:m[1]},checked:m[0].includes(' checked')}));}});return e;}
 function layer(){const l={layers:[],addTo(){return l;},setView(){return l;},addLayer(x){l.layers.push(x);},clearLayers(){l.layers=[];},fitBounds(){},removeLayer(){},on(){},bindTooltip(){return l;},bindPopup(){return l;}};return l;}
 const c={console,URLSearchParams,location:{search:''},HeatnetUpload:class{},document:{getElementById(id){if(!elements.has(id))elements.set(id,el());return elements.get(id);},createElement:el,querySelector:()=>null},
 L:{map:()=>{mapCount++;const m=layer();m.fitBounds=()=>cameraChanges++;return m;},tileLayer:layer,layerGroup:layer,control:()=>{const l=layer();l.addTo=()=>{l.element=l.onAdd();return l;};controls.push(l);return l;},DomUtil:{create:el},DomEvent:{disableClickPropagation(){}},geoJSON(f,options){const l=layer();l.feature=f;l.options=options;return l;},circleMarker:layer,latLngBounds:()=>({isValid:()=>true,extend(){return this;}})},
 Worker:class{constructor(){workers.push(this);}postMessage(x){this.features=x;}terminate(){this.terminated=true;}},
 setTimeout(fn,ms){const t={fn,ms};timers.push(t);return t;},clearTimeout(t){if(t)t.cancelled=true;},
 async fetch(url){requests.push(url);return {ok:true,status:200,json:async()=>url==='/api/v1/jobs/job'?status:url.endsWith('/result')?{variants:[],mode:'PLANAR'}:url.endsWith('.geojson')?{features:[]}:[]};}};
 const expose=`globalThis.ui={groups,openJob,initViews,showVariant,setVariantVisible,renderVisibleVariants,renderActiveVariant,renderStages,drawOverlay,active:()=>currentVariant,setScene(n){resultData={mode:'PLANAR',variants:Array.from({length:n},(_,i)=>({variantId:'v'+(i+1),rank:i+1,score:i+1,connectedOksCount:2,calculatedCost:100+i,newNetworkLength:20+i,newChamberCount:1}))};resultGeo={features:resultData.variants.map(v=>({type:'Feature',properties:{variant_id:v.variantId,object_type:'heat_network',diameter:100},geometry:{type:'LineString',coordinates:[[37,55],[37.001,55]]}}))};jobMeta={connectionPointCount:2};inputGeo={features:[]};indexResultGeometry(resultGeo);initViews();renderActiveVariant();renderVisibleVariants();},addNodes(){for(const v of resultData.variants)for(const type of ['heat_chamber','technical_node'])resultGeo.features.push({type:'Feature',properties:{variant_id:v.variantId,object_type:type,id:v.variantId+'-'+type},geometry:{type:'Point',coordinates:[37,55]}});indexResultGeometry(resultGeo);},data:()=>resultData,view:()=>Array.from(visibleVariants),noInput:()=>{inputGeo=null;}};`;
 vm.runInNewContext(source.replace('  loadJobs();\n  const jobParam',expose+'\n  loadJobs();\n  const jobParam'),c);
 return {ui:c.ui,e:id=>elements.get(id),click:id=>elements.get(id).listeners.click(),tab:id=>elements.get('variantTabs').children.find(b=>b.dataset.variant===id),workers,timers,requests,setStatus:s=>status=s,mapCount:()=>mapCount,cameraChanges:()=>cameraChanges,legend:()=>controls[0].element.querySelector('.rows').children[0],toggle(id,on){const box=this.legend().querySelectorAll('input[data-variant]').find(b=>b.dataset.variant===id);assert(box,'existing checkbox');box.checked=on;this.legend().listeners.change({target:box});},controlCount:()=>controls.length};
}
function visibleIds(a) {
 return [...new Set(a.ui.groups.newNet.layers.flatMap(l=>l.feature.type==='FeatureCollection'?l.feature.features:[l.feature]).map(f=>f.properties.variant_id))];
}
function select(a, ids) {
 for(const box of a.legend().querySelectorAll('input[data-variant]')) if(box.checked!==ids.includes(box.dataset.variant)) a.toggle(box.dataset.variant,ids.includes(box.dataset.variant));
}
test('one/two/three variants have only available legend checkboxes and V1 initially selected',()=>{
 for(const n of [1,2,3]){
  const a=app();a.ui.setScene(n);
  const boxes=a.legend().querySelectorAll('input[data-variant]');
  assert.deepEqual(boxes.map(b=>b.dataset.variant),['v1','v2','v3'].slice(0,n));
  assert.deepEqual(boxes.map(b=>b.checked),Array.from({length:n},(_,i)=>i===0));
  assert.deepEqual(visibleIds(a),['v1']);assert.equal(a.e('overlayPanel').hidden,true);
  assert.equal(a.controlCount(),1);assert.equal(a.mapCount(),1);
 }
});
test('V1 → V1+V2 → V1+V2+V3 → V3 → V2 → V1 synchronizes legend, layers and KPIs',()=>{
 const a=app();a.ui.setScene(3);a.ui.noInput();
 const base=['existing','restrictions','chambers','points','source'];const saved={};
 base.forEach(k=>{a.ui.groups[k].addLayer({name:k});saved[k]=a.ui.groups[k].layers;});
 const requests=a.requests.length;
 for(const ids of [['v1'],['v1','v2'],['v1','v2','v3'],['v3'],['v2'],['v1']]){
  select(a,ids);const comparing=ids.length>1;
  assert.deepEqual(visibleIds(a),ids);
  assert.deepEqual(a.legend().querySelectorAll('input[data-variant]').filter(b=>b.checked).map(b=>b.dataset.variant),ids);
  assert.equal(a.e('overlayPanel').hidden,!comparing);assert.equal(a.e('summary').hidden,false);assert.equal(a.ui.active(),'v1');
  if(comparing){for(const id of ids)assert(a.e('overlayKpis').innerHTML.includes(id));assert.match(a.e('overlayKpis').innerHTML,/2\/2/);}
  else assert.equal(a.e('overlayKpis').innerHTML,'');
  for(const k of base)assert.strictEqual(a.ui.groups[k].layers,saved[k]);
 }
 assert.equal(a.requests.length,requests);assert.equal(a.cameraChanges(),0);assert.equal(a.mapCount(),1);
});
test('no selection clears only generated layers and shows a useful hint, then can be restored',()=>{
 const a=app();a.ui.setScene(3);a.ui.addNodes();select(a,[]);
 for(const k of ['newNet','newChambers','techNodes'])assert.equal(a.ui.groups[k].layers.length,0);
 assert.equal(a.e('variantHint').hidden,false);assert.match(a.e('variantHint').textContent,/Включите вариант в легенде/);
 assert.equal(a.e('overlayPanel').hidden,true);assert.equal(a.e('summary').hidden,false);assert.equal(a.ui.active(),'v1');
 select(a,['v2']);assert.deepEqual(visibleIds(a),['v2']);assert.equal(a.e('variantHint').hidden,true);
 a.ui.setScene(0);assert.equal(a.legend().hidden,true);assert.match(a.e('variantHint').textContent,/Нет вариантов/);
});
test('chambers and technical nodes follow the exact selected variants',()=>{
 const a=app();a.ui.setScene(3);a.ui.addNodes();
 for(const ids of [['v1','v2'],['v1','v2','v3'],['v3'],['v2'],['v1'],[]]){
  select(a,ids);
  assert.equal(a.ui.groups.newChambers.layers.length,ids.length);
  assert.equal(a.ui.groups.techNodes.layers.length,ids.length);
 }
});
test('rapid selection changes cancel stale workers without restoring removed routes',()=>{
 const a=app();a.ui.setScene(3);a.toggle('v2',true);const old=a.workers[0];assert(old);
 a.toggle('v1',false);assert(old.terminated);old.onmessage({data:{features:old.features}});
 assert.deepEqual(visibleIds(a),['v2']);
 a.toggle('v3',true);const w=a.workers.at(-1);
 assert.deepEqual([...new Set(w.features.map(f=>f.properties.variant_id))],['v2','v3']);
 w.onmessage({data:{error:'Лимит сравнения'}});assert.equal(a.e('overlayStatus').textContent,'Лимит сравнения');assert(w.terminated);
 assert.deepEqual(visibleIds(a),['v2','v3']);assert.doesNotMatch(a.e('overlayKpis').innerHTML,/>v1/);
 select(a,['v3']);assert.deepEqual(visibleIds(a),['v3']);
});
test('legend uses exact SVG strokes; single view is classic, overlay keeps variant colors and dashes',()=>{
 const a=app();a.ui.setScene(3);const expected=[['#2563eb',null,8],['#c65d08','12 8',6],['#7c3aed','3 7',4]];
 expected.forEach(([color,dash,weight],i)=>{
  select(a,['v'+(i+1)]);const style=a.ui.groups.newNet.layers[0].options.style;
  assert.equal(style.color,'#1d4ed8');assert.equal(style.dashArray,null);assert.equal(style.weight,3);
  assert(a.legend().innerHTML.includes(`stroke="${color}" stroke-width="${weight}"`));
  if(dash)assert(a.legend().innerHTML.includes(`stroke-dasharray="${dash}"`));
 });
 select(a,['v1','v2','v3']);const w=a.workers.at(-1);
 w.onmessage({data:{features:w.features.map(f=>({...f,common:true}))}});
 const layers=a.ui.groups.newNet.layers;assert.equal(layers.length,3);
 layers.forEach((l,i)=>{const style=l.options.style({common:true});assert.equal(style.color,expected[i][0]);assert.equal(style.dashArray,expected[i][1]);assert.equal(style.weight,expected[i][2]);assert(style.opacity>=0.5);assert(style.opacity<l.options.style({common:false}).opacity);});
 assert(w.terminated);
});
test('missing or reordered variants keep their own colors and initial V1 preference',()=>{
 const a=app();a.ui.setScene(3);a.ui.data().variants.reverse();a.ui.initViews();a.ui.renderVisibleVariants();
 assert.deepEqual(visibleIds(a),['v1']);assert.equal(a.ui.active(),'v1');assert.equal(a.ui.groups.newNet.layers[0].options.style.color,'#1d4ed8');
 a.ui.data().variants=a.ui.data().variants.filter(v=>v.variantId==='v3');a.ui.initViews();a.ui.renderVisibleVariants();
 assert.deepEqual(a.legend().querySelectorAll('input[data-variant]').map(b=>b.dataset.variant),['v3']);
 assert.deepEqual(visibleIds(a),['v3']);assert.equal(a.ui.active(),'v3');assert.equal(a.ui.groups.newNet.layers[0].options.style.color,'#1d4ed8');
});
test('MultiLineString and DEPTH properties remain intact when toggling routes',()=>{
 const a=app();a.ui.setScene(2);a.ui.data().mode='DEPTH';
 const f=a.ui.groups.newNet.layers[0].feature;f.geometry={type:'MultiLineString',coordinates:[[[37,55],[37.001,55]],[[37.001,55],[37.002,55]]]};f.properties.depth_start=3;f.properties.depth_end=4;
 a.toggle('v2',true);const w=a.workers.at(-1);
 assert.equal(w.features[0].geometry.type,'MultiLineString');assert.equal(w.features[0].properties.depth_end,4);
 a.toggle('v2',false);assert.strictEqual(a.ui.groups.newNet.layers[0].feature,f);
});
test('missing KPI stays dash and nonexistent variants cannot alter selection',()=>{
 const a=app();a.ui.setScene(2);delete a.ui.data().variants[1].calculatedCost;a.toggle('v2',true);assert.match(a.e('overlayKpis').innerHTML,/<td>—<\/td>/);
 a.ui.setVariantVisible('v3',true);assert.deepEqual(visibleIds(a),['v1','v2']);
});
test('restored controls are present once and obsolete overlay controls stay absent',()=>{
 const html=fs.readFileSync(path.join(__dirname,'../../main/resources/static/index.html'),'utf8');
 assert.doesNotMatch(html,/id="(?:compareVariants|singleVariant|emphasizeDifferences|overlayChoices)"/);
 for(const id of ['variantTabs','comparison','colorMode','depthLegend'])assert.equal(html.split(`id="${id}"`).length,2,id);
 const a=app();a.ui.setScene(3);assert.equal(a.e('layers').children.length,8);
 assert.doesNotMatch(source,/\b(?:activeView|overlayMode|selectedVariants)\b/);
 assert.equal(Object.keys(a.legend().listeners).length,1);
});
test('live progress shows concurrent strategies and factual counts without percentage',()=>{
 const a=app();a.ui.renderStages('Generating variants; mode=PARALLEL_2; completed=1; total=5; active=joint-nearest-first,separate-connections; formed=1; validated=0; recovery=false','RUNNING');
 const text=a.e('stages').innerHTML;assert.match(text,/PARALLEL_2/);assert.match(text,/1\/5/);assert.match(text,/Ближайшие сначала/);assert.match(text,/Раздельные подключения/);assert.doesNotMatch(text,/100%/);
 a.ui.renderStages('Writing validated result','RUNNING');assert.match(a.e('stages').innerHTML,/Формирование результата/);
});
test('queued and running poll, FAILED stops polling and new job clears comparison workers',async()=>{
 const a=app();await flush();await a.ui.openJob('job');await flush();assert.match(a.e('rsTitle').textContent,/очереди/);assert.equal(a.timers.length,1);
 a.setStatus({id:'job',status:'RUNNING',mode:'PLANAR',progress:'Generating variants; mode=PARALLEL_2; completed=1; total=5; active=separate-connections; formed=1; validated=0; recovery=false'});await a.timers.at(-1).fn();await flush();assert.equal(a.e('runStatus').dataset.kind,'busy');assert.equal(a.e('rsFill').style.width,'');
 a.setStatus({id:'job',status:'FAILED',error:'COMPLEXITY_LIMIT',diagnostics:[]});const count=a.timers.length;await a.timers.at(-1).fn();await flush();assert.equal(a.timers.length,count);assert.match(a.e('status').textContent,/COMPLEXITY_LIMIT/);
 a.ui.setScene(2);a.toggle('v2',true);const worker=a.workers.at(-1);await a.ui.openJob('job');await flush();assert(worker.terminated);assert.equal(a.ui.groups.newNet.layers.length,0);
});
test('DONE loads result then becomes complete without scheduling another poll',async()=>{
 const a=app();await flush();a.setStatus({id:'job',status:'DONE',mode:'PLANAR',variantCount:0});await a.ui.openJob('job');await flush();assert.equal(a.e('runStatus').dataset.kind,'ok');assert.equal(a.timers.length,0);
});
test('left tabs show exactly the chosen route; the legend only toggles route visibility',()=>{
 const a=app();a.ui.setScene(3);
 assert.deepEqual(a.e('variantTabs').children.map(b=>b.textContent),['v1 · место 1 · S 1,0000','v2 · место 2 · S 2,0000','v3 · место 3 · S 3,0000']);
 assert.equal(a.tab('v1')['aria-selected'],'true');
 a.tab('v2').listeners.click();assert.equal(a.ui.active(),'v2');assert.deepEqual(visibleIds(a),['v2']);
 assert.deepEqual(a.legend().querySelectorAll('input[data-variant]').filter(b=>b.checked).map(b=>b.dataset.variant),['v2']);
 select(a,['v1']);assert.equal(a.ui.active(),'v2');assert.equal(a.tab('v2')['aria-selected'],'true');assert.equal(a.e('summary').hidden,false);
 a.toggle('v3',true);assert.equal(a.ui.active(),'v2');
 select(a,[]);assert.equal(a.ui.active(),'v2');assert.equal(a.e('summary').hidden,false);assert.equal(a.ui.groups.newNet.layers.length,0);
 a.toggle('v1',true);a.toggle('v3',true);a.tab('v2').listeners.click();assert.deepEqual(visibleIds(a),['v2']);
 assert.deepEqual(a.legend().querySelectorAll('input[data-variant]').filter(b=>b.checked).map(b=>b.dataset.variant),['v2']);
 for(const id of ['v3','v1']){a.tab(id).listeners.click();assert.equal(a.ui.active(),id);assert.deepEqual(visibleIds(a),[id]);}
 a.ui.setVariantVisible('v9',true);a.ui.showVariant('v9');assert.equal(a.ui.active(),'v1');assert.deepEqual(visibleIds(a),['v1']);
});
test('special passages stay magenta in the single view and get a magenta underlay in the overlay',()=>{
 const a=app();a.ui.setScene(2);
 a.ui.groups.newNet.layers[0].feature.properties.laying_method='special';a.ui.groups.newNet.layers[0].feature.properties.diameter=500;
 a.ui.renderVisibleVariants();const style=a.ui.groups.newNet.layers[0].options.style;assert.equal(style.color,'#d000d0');assert.equal(style.weight,7);
 a.toggle('v2',true);const halo=a.ui.groups.newNet.layers[0];
 assert.equal(halo.feature.type,'FeatureCollection');assert.equal(halo.feature.features.length,1);assert.equal(halo.options.style(halo.feature.features[0]).color,'#d000d0');
 assert.deepEqual(visibleIds(a),['v1','v2']);
});
