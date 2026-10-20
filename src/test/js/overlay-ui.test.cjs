const {test}=require('node:test');const assert=require('node:assert/strict');const vm=require('node:vm');const fs=require('node:fs');const path=require('node:path');
const source=fs.readFileSync(path.join(__dirname,'../../main/resources/static/app.js'),'utf8');
const flush=async()=>{for(let i=0;i<15;i++)await new Promise(setImmediate);};
function app(){
 const elements=new Map(),workers=[],timers=[],requests=[];let status={id:'job',status:'QUEUED',mode:'PLANAR'},mapCount=0;
 function el(){const listeners={},children=[];let html='';const e={children,hidden:false,style:{},dataset:{},value:'default',files:[],classList:{toggle(){},add(){},remove(){}},listeners,addEventListener(t,f){assert(!listeners[t],'duplicate listener');listeners[t]=f;},appendChild(c){children.push(c);},querySelector(){return this.input||(this.input=el());},querySelectorAll(){return [];},setAttribute(k,v){this[k]=v;}};Object.defineProperty(e,'innerHTML',{get:()=>html,set:v=>{html=v;children.length=0;}});return e;}
 function layer(){const l={layers:[],addTo(){return l;},setView(){return l;},addLayer(x){l.layers.push(x);},clearLayers(){l.layers=[];},fitBounds(){},removeLayer(){},on(){},bindTooltip(){return l;},bindPopup(){return l;}};return l;}
 const c={console,URLSearchParams,location:{search:''},HeatnetUpload:class{},document:{getElementById(id){if(!elements.has(id))elements.set(id,el());return elements.get(id);},createElement:el,querySelector:()=>null},
 L:{map:()=>{mapCount++;return layer();},tileLayer:layer,layerGroup:layer,control:layer,DomUtil:{create:el},DomEvent:{disableClickPropagation(){}},geoJSON(f,options){const l=layer();l.feature=f;l.options=options;return l;},circleMarker:layer,latLngBounds:()=>({isValid:()=>false})},
 Worker:class{constructor(){workers.push(this);}postMessage(x){this.features=x;}terminate(){this.terminated=true;}},
 setTimeout(fn,ms){const t={fn,ms};timers.push(t);return t;},clearTimeout(t){if(t)t.cancelled=true;},
 async fetch(url){requests.push(url);return {ok:true,status:200,json:async()=>url==='/api/v1/jobs/job'?status:url.endsWith('/result')?{variants:[],mode:'PLANAR'}:url.endsWith('.geojson')?{features:[]}:[]};}};
 const expose=`globalThis.ui={groups,openJob,showVariant,renderStages,drawOverlay,setScene(n){resultData={mode:'PLANAR',variants:Array.from({length:n},(_,i)=>({variantId:'v'+(i+1),rank:i+1,score:i+1,connectedOksCount:2,calculatedCost:100+i,newNetworkLength:20+i,newChamberCount:1}))};resultGeo={features:resultData.variants.map(v=>({type:'Feature',properties:{variant_id:v.variantId,object_type:'heat_network',diameter:100},geometry:{type:'LineString',coordinates:[[37,55],[37.001,55]]}}))};jobMeta={connectionPointCount:2};inputGeo={features:[]};indexResultGeometry(resultGeo);initOverlay();currentVariant='v1';showVariant('v1');},data:()=>resultData};`;
 vm.runInNewContext(source.replace('  loadJobs();\n  const jobParam',expose+'\n  loadJobs();\n  const jobParam'),c);
 return {ui:c.ui,e:id=>elements.get(id),click:id=>elements.get(id).listeners.click(),workers,timers,requests,setStatus:s=>status=s,mapCount:()=>mapCount};
}
test('one/two/three variants expose only available controls and real KPI values',()=>{
 for(const n of [1,2,3]){const a=app();a.ui.setScene(n);assert.equal(a.e('compareVariants').hidden,n<2);assert.equal(a.e('overlayChoices').children.length,n);a.click('compareVariants');assert.equal(a.ui.groups.newNet.layers.length,n);assert.match(a.e('overlayKpis').innerHTML,/2\/2/);assert.match(a.e('overlayKpis').innerHTML,/лучший S/);assert.equal(a.mapCount(),1);}
});
test('toggle layers, rapid selection, single view and reopening do not accumulate layers or fetch data',()=>{
 const a=app();a.ui.setScene(3);const requests=a.requests.length;a.click('compareVariants');const cb=a.e('overlayChoices').children[1].input;
 cb.listeners.change({target:{checked:false}});assert.equal(a.ui.groups.newNet.layers.length,2);
 cb.listeners.change({target:{checked:true}});assert.equal(a.ui.groups.newNet.layers.length,3);
 for(let i=0;i<5;i++){a.ui.showVariant('v2');assert.equal(a.ui.groups.newNet.layers.length,3);a.click('singleVariant');assert.equal(a.ui.groups.newNet.layers.length,1);a.click('compareVariants');}
 assert.equal(a.requests.length,requests);assert.equal(a.e('overlayChoices').children.length,3);
});
test('difference worker is terminated on changes and stale responses cannot restore old layers',()=>{
 const a=app();a.ui.setScene(3);a.click('compareVariants');a.e('emphasizeDifferences').checked=true;a.e('emphasizeDifferences').listeners.change();const old=a.workers[0];
 a.click('singleVariant');assert(old.terminated);old.onmessage({data:{features:old.features}});assert.equal(a.ui.groups.newNet.layers.length,1);
 a.click('compareVariants');const w=a.workers.at(-1);w.onmessage({data:{error:'Лимит сравнения'}});assert.equal(a.e('overlayStatus').textContent,'Лимит сравнения');assert.equal(a.ui.groups.newNet.layers.length,3);assert(w.terminated);
});
test('missing KPIs are dashes, not zero; zero selected variants is explicit',()=>{
 const a=app();a.ui.setScene(2);delete a.ui.data().variants[1].calculatedCost;a.click('compareVariants');assert.match(a.e('overlayKpis').innerHTML,/<td>—<\/td>/);
 for(const label of a.e('overlayChoices').children)label.input.listeners.change({target:{checked:false}});
 assert.equal(a.ui.groups.newNet.layers.length,0);assert.match(a.e('overlayStatus').textContent,/Выберите/);
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
 a.ui.setScene(2);a.click('compareVariants');a.e('emphasizeDifferences').checked=true;a.e('emphasizeDifferences').listeners.change();const worker=a.workers.at(-1);await a.ui.openJob('job');await flush();assert(worker.terminated);assert.equal(a.ui.groups.newNet.layers.length,0);
});
test('DONE loads result then becomes complete without scheduling another poll',async()=>{
 const a=app();await flush();a.setStatus({id:'job',status:'DONE',mode:'PLANAR',variantCount:0});await a.ui.openJob('job');await flush();assert.equal(a.e('runStatus').dataset.kind,'ok');assert.equal(a.timers.length,0);
});
