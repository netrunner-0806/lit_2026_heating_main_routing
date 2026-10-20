const {test}=require('node:test');
const assert=require('node:assert/strict');
const {compare}=require('../../main/resources/static/variant-geometry.js');
const f=(id,coords,type='LineString')=>({type:'Feature',properties:{variant_id:id,object_type:'heat_network'},geometry:{type,coordinates:coords}});
const line=(id,a,b,y=55)=>f(id,[[37+a/10000,y],[37+b/10000,y]]);
test('reverse direction, extra vertices and separate features have the same coverage',()=>{
 const input=[line('v1',0,10),f('v2',[[37.001,55],[37.0005,55],[37,55]]),line('v3',0,3),line('v3',3,10)];
 const before=JSON.stringify(input),result=compare(input);assert(result.length>0);assert(result.every(x=>x.common));assert.equal(JSON.stringify(input),before);
});
test('partial overlap is split exactly, different tails stay visible',()=>{
 const result=compare([line('v1',0,10),line('v2',3,7)]).filter(x=>x.properties.variant_id==='v1');
 assert.deepEqual(result.map(x=>x.common),[false,true]);assert.equal(result[0].geometry.coordinates.length,2);assert.equal(result[1].geometry.coordinates[0][0][0],37.0003);
});
test('crossing and parallel separated pipes are not shared routes',()=>{
 const result=compare([line('v1',0,10),line('v2',0,10,55.00001),f('v3',[[37.0005,54.999],[37.0005,55.001]])]);
 assert(result.every(x=>!x.common));
});
test('MultiLineString and tiny coordinate noise are supported; DEPTH properties stay intact',()=>{
 const a=line('v1',0,10);a.properties.depth_start=3;
 const b=f('v2',[[[37,55.0000001],[37.0005,55.0000001]],[[37.0005,55.0000001],[37.001,55.0000001]]],'MultiLineString');
 const result=compare([a,b]);assert(result.every(x=>x.common));assert.equal(result[0].properties.depth_start,3);
});
test('single variant and repeated pipes within it never count as shared',()=>{assert(compare([line('v1',0,10),line('v1',0,10)]).every(x=>!x.common));});
test('empty input is safe',()=>assert.deepEqual(compare([]),[]));
test('pathological geometry reaches a controlled comparison limit',()=>assert.throws(()=>compare([f('v1',[[0,0],[90,70]])]),/много геометрии/));

test('densified input does not create one Leaflet feature per segment',()=>{
 const coords=Array.from({length:1001},(_,i)=>[37+i/100000,55]);
 const result=compare([f('v1',coords),f('v2',coords.slice().reverse())]);
 assert.equal(result.length,2);assert(result.every(x=>x.common&&x.geometry.coordinates.length===1000));
});
