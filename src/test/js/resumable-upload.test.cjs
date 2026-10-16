const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createHash, webcrypto } = require('node:crypto');
const { HeatnetUpload } = require('../../main/resources/static/upload.js');
const OPTIONS = { mode:'PLANAR',maxVariants:3,unknownRestrictionPolicy:'IGNORE',chunkSize:8*1024**2 };
const hash = b => createHash('sha256').update(b).digest('hex');
function file(bytes, name='test.geojson') {
  const b = new Blob([bytes]); b.name=name; b.lastModified=123;
  b.arrayBuffer=()=>{throw new Error('Whole file must not be materialized');}; return b;
}
function fixture() {
  const storage=new Map(), parts=new Map(), sent=[], progress=[], events=[];
  let request, concurrent=0, peak=0, completions=0;
  const state={ lostAfterWrite:false, offline:false, failStatus:0, sessionStatus:0, cancelCount:0, puts:0, completed:false, dropComplete:false };
  const view=()=>({...request,partCount:Math.ceil(request.totalSize/request.chunkSize),state:state.completed?'COMPLETED':'UPLOADING',jobId:state.completed?'one-job':null,
    parts:[...parts].map(([index,sha256])=>({index,sha256}))});
  const env={ crypto:webcrypto, AbortController, localStorage:{ getItem:k=>storage.get(k),setItem:(k,v)=>storage.set(k,v),removeItem:k=>storage.delete(k) },
    setTimeout:(fn,ms)=>{const t=setTimeout(fn,ms>=120000?ms:0);t.unref();return t;},clearTimeout,
    async fetch(url,options) {
      events.push([options.method,url]);
      if(state.offline) throw new TypeError('offline');
      if(state.sessionStatus) return {ok:false,status:state.sessionStatus,json:async()=>({message:'Upload expired'})};
      if(options.method==='POST' && url.endsWith('/uploads'))request=JSON.parse(options.body);
      if(options.method==='DELETE'){state.cancelCount++;return {ok:true,status:204};}
      let body=view();
      if(url.endsWith('/complete')) {
        assert.equal(parts.size,body.partCount);completions++;state.completed=true;body={...body,state:'COMPLETED',jobId:'one-job'};
        if(state.dropComplete)throw new TypeError('lost complete response');
      }
      return {ok:true,status:200,json:async()=>body};
    },
    XMLHttpRequest:class {
      constructor(){this.upload={};this.headers={};this.aborted=false;}
      open(method,url){this.url=url;this.index=Number(url.split('/').at(-1));}
      setRequestHeader(k,v){this.headers[k]=v;}
      abort(){this.aborted=true;if(this.onabort)this.onabort();}
      send(blob){
        concurrent++;peak=Math.max(peak,concurrent);sent.push(this.index);state.puts++;
        assert(blob.size<=OPTIONS.chunkSize);
        blob.arrayBuffer().then(bytes=>{
          concurrent--;
          if(this.aborted)return;
          this.upload.onprogress({loaded:blob.size});
          if(state.offline){this.onerror();return;}
          if(state.failStatus){this.status=state.failStatus;this.response={message:'busy'};this.onload();return;}
          assert.equal(hash(Buffer.from(bytes)),this.headers['X-Part-SHA256']);
          parts.set(this.index,this.headers['X-Part-SHA256']);
          if(state.lostAfterWrite){state.lostAfterWrite=false;this.onerror();return;}
          this.status=200;this.response=view();this.onload();
        });
      }
    }
  };
  // Keep backoff timers referenced in tests; timeout timers are cancelled by the client.
  env.setTimeout=(fn,ms)=>setTimeout(fn,ms>=120000?ms:0);
  return {env,parts,sent,state,progress,events,storage,client:()=>new HeatnetUpload(p=>progress.push(p),env),peak:()=>peak,completions:()=>completions};
}

test('large file uses bounded slices, two transfers maximum, then one complete',async()=>{
  const f=fixture(), b=file(new Uint8Array(OPTIONS.chunkSize*3+123));
  assert.equal(await f.client().start(b,OPTIONS),'one-job');
  assert.equal(f.sent.length,4);assert(f.peak()<=2);assert.equal(f.completions(),1);
  assert.equal(f.progress.at(-1).confirmedBytes,b.size);assert.equal(f.progress.at(-1).state,'COMPLETE');
  assert.equal(f.storage.size,0);
});
test('lost response retries the same bounded part without restarting upload',async()=>{
  const f=fixture();f.state.lostAfterWrite=true;
  await f.client().start(file(new Uint8Array(100)),OPTIONS);
  assert.deepEqual(f.sent,[0,0]);assert.equal(f.parts.size,1);assert.equal(f.completions(),1);
});
test('permanent 503 pauses after bounded retries and keeps resumable identity',async()=>{
  const f=fixture();f.state.failStatus=503;const c=f.client();
  await assert.rejects(c.start(file(new Uint8Array(100)),OPTIONS),/busy/);
  assert.equal(f.sent.length,5);assert.equal(f.completions(),0);assert(c.saved);assert.equal(f.progress.at(-1).state,'PAUSED');
});
test('HTTP 422 does not retry forever',async()=>{
  const f=fixture();f.state.failStatus=422;
  await assert.rejects(f.client().start(file(new Uint8Array(100)),OPTIONS));assert.equal(f.sent.length,1);
});
test('browser reload resumes only missing chunks and verifies already acknowledged content',async()=>{
  const f=fixture(), b=file(new Uint8Array(OPTIONS.chunkSize*3+100));let stopped=false;
  const first=new HeatnetUpload(p=>{if(p.confirmedBytes>=OPTIONS.chunkSize && !stopped){stopped=true;first.pause();}},f.env);
  await assert.rejects(first.start(b,OPTIONS));
  const acknowledged=new Set(f.parts.keys()), prior=f.sent.length;assert(acknowledged.size>0);
  assert.equal(await f.client().start(b,OPTIONS),'one-job');
  assert(f.sent.slice(prior).every(i=>!acknowledged.has(i)));
});
test('same name and size but changed file content cannot resume',async()=>{
  const f=fixture();f.state.failStatus=503;
  await assert.rejects(f.client().start(file(new Uint8Array(100)),OPTIONS));
  f.state.failStatus=0;const count=f.sent.length;
  await assert.rejects(f.client().start(file(new Uint8Array(100).fill(42)),OPTIONS),/другой файл/);
  assert.equal(f.sent.length,count);assert.equal(f.completions(),0);
});
test('oversize file rejected before hashing or HTTP; cancel removes only its own session',async()=>{
  const f=fixture();await assert.rejects(f.client().start({size:3*1024**3+1},OPTIONS),/3 ГиБ/);assert.equal(f.events.length,0);
  f.state.failStatus=503;const c=f.client();await assert.rejects(c.start(file(new Uint8Array(10)),OPTIONS));
  await c.cancel();assert.equal(f.state.cancelCount,1);assert.equal(f.storage.size,0);
});
test('resume validates entire confirmed chunks, not just filename or sampled fingerprint',async()=>{
  const f=fixture(),bytes=new Uint8Array(4*1024**2),b=file(bytes);
  const first=new HeatnetUpload(p=>{if(p.confirmedBytes===b.size)first.pause();},f.env);
  await assert.rejects(first.start(b,OPTIONS));assert.equal(f.parts.size,1);
  bytes[200000]=42;const changed=file(bytes);const prior=f.sent.length;
  await assert.rejects(f.client().start(changed,OPTIONS),/подтверждённая часть отличается/);
  assert.equal(f.sent.length,prior);assert.equal(f.completions(),0);
});
test('lost complete response resumes the same job with 100% upload and no retransmission',async()=>{
  const f=fixture(),b=file(new Uint8Array(100));f.state.dropComplete=true;
  await assert.rejects(f.client().start(b,OPTIONS),/lost complete response/);
  assert(f.state.completed);const puts=f.sent.length,completeCalls=f.completions();
  f.state.dropComplete=false;
  assert.equal(await f.client().start(b,OPTIONS),'one-job');
  assert.equal(f.sent.length,puts);assert.equal(f.completions(),completeCalls);
  assert.equal(f.progress.at(-1).confirmedBytes,b.size);assert.equal(f.progress.at(-1).sentBytes,b.size);
  assert.equal(f.storage.size,0);
});
test('expired session does not claim that removed chunks are still saved; cancellation enables a fresh upload',async()=>{
  const f=fixture(),b=file(new Uint8Array(100));f.state.failStatus=503;
  await assert.rejects(f.client().start(b,OPTIONS));
  f.state.sessionStatus=410;const resumed=f.client();
  await assert.rejects(resumed.start(b,OPTIONS),/Upload expired/);
  assert.doesNotMatch(f.progress.at(-1).message,/Подтверждённые части сохранены/);
  assert.match(f.progress.at(-1).message,/начните новую/);
  await resumed.cancel();assert.equal(f.storage.size,0);
  f.state.sessionStatus=0;f.state.failStatus=0;
  assert.equal(await f.client().start(b,OPTIONS),'one-job');
});
