#!/usr/bin/env node
/* Native browser file input -> real Blob.slice/XHR -> unchanged Caddy reverse proxy -> production app. */
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process');
const {createHash}=require('node:crypto');
const ROOT=path.resolve(__dirname,'..'),OUT=path.join(ROOT,'target/final-engineering/http');
function loadPlaywright(){
  if(process.env.PLAYWRIGHT_MODULE)return require(process.env.PLAYWRIGHT_MODULE);
  for(const name of ['playwright','playwright-core']){
    let resolved;
    try{resolved=require.resolve(name);}catch(e){if(e.code==='MODULE_NOT_FOUND')continue;throw e;}
    return require(resolved);
  }
  throw new Error('Use an existing playwright/playwright-core installation or set PLAYWRIGHT_MODULE; this runner does not install browsers.');
}
const playwright=loadPlaywright();
const bundled={chromium:playwright.chromium.executablePath(),firefox:playwright.firefox.executablePath()};
function available(file){
  if(!file||!fs.existsSync(file))return false;
  // A cached macOS Chromium launcher without its frameworks is not a usable browser.
  return process.platform!=='darwin'||!file.includes('.app/Contents/MacOS/')||
    !/chrom(?:e|ium)/i.test(path.basename(file))||fs.existsSync(path.resolve(path.dirname(file),'../Frameworks'));
}
function onPath(names){
  for(const dir of (process.env.PATH||'').split(path.delimiter).filter(Boolean)){
    for(const name of names){const file=path.join(dir,name);if(available(file))return file;}
  }
}
const requestedEngine=process.env.BROWSER_ENGINE;
assert(!requestedEngine||['chromium','firefox'].includes(requestedEngine),'BROWSER_ENGINE must be chromium or firefox');
const candidates=[
  {engine:'chromium',file:bundled.chromium},{engine:'firefox',file:bundled.firefox},
  {engine:'firefox',file:process.platform==='darwin'?'/Applications/Firefox.app/Contents/MacOS/firefox':onPath(['firefox','firefox.exe'])},
  {engine:'chromium',file:process.platform==='darwin'?'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome':onPath(['chromium','chromium-browser','google-chrome','chrome.exe'])}
];
const selected=process.env.BROWSER_EXECUTABLE
  ?{engine:requestedEngine||(/firefox/i.test(process.env.BROWSER_EXECUTABLE)?'firefox':'chromium'),file:path.resolve(process.env.BROWSER_EXECUTABLE)}
  :candidates.find(c=>(!requestedEngine||c.engine===requestedEngine)&&available(c.file));
assert(selected&&available(selected.file),'No existing browser found; set BROWSER_ENGINE and BROWSER_EXECUTABLE. Nothing was installed.');
const engine=selected.engine,executablePath=selected.file;
// Stock Firefox uses BiDi; Playwright's bundled Firefox uses its own protocol.
const stockFirefox=engine==='firefox'&&path.resolve(executablePath)!==path.resolve(bundled.firefox);
const environment=JSON.parse(fs.readFileSync(path.join(OUT,'environment.json'))),base=`http://localhost:${environment.port}`;
const report={status:'RUNNING',browser:engine,through_caddy:true,
  memory_method:engine==='chromium'?'Browser process-tree RSS; Runtime.getHeapUsage usedSize plus backingStorageSize when supplied by Chromium'
    :'Browser process-tree RSS; JS/backing-store counters unavailable through Firefox BiDi',runs:[]};
const save=()=>fs.writeFileSync(path.join(OUT,'browser.json'),JSON.stringify(report,null,2)+'\n');
function browserPid(profile){
  const rows=execFileSync('ps',['-axo','pid=,ppid=,command='],{encoding:'utf8'}).split('\n');
  const row=rows.find(r=>(r.includes('--user-data-dir='+profile)||r.includes('--profile '+profile))&&!r.includes('--type=')&&!r.includes('-contentproc'));
  assert(row,'Owned browser process was not found');return Number(row.trim().split(/\s+/)[0]);
}
function rss(pid){
  try{
    const rows=execFileSync('ps',['-axo','pid=,ppid=,rss='],{encoding:'utf8'}).trim().split('\n').map(l=>l.trim().split(/\s+/).map(Number));
    const family=new Set([pid]);let changed=true;
    while(changed){changed=false;for(const [p,parent]of rows)if(family.has(parent)&&!family.has(p)){family.add(p);changed=true;}}
    return rows.filter(r=>family.has(r[0])).reduce((n,r)=>n+r[2]*1024,0);
  }catch{return null;}
}
(async()=>{
  save();let activeContext;
  try{
    const manifest=JSON.parse(fs.readFileSync(path.join(ROOT,'target/upload-data/manifest.json')));
    const files=[{file:'target/scale-data/original.geojson'},manifest[0],manifest.at(-1)];
    for(let n=0;n<files.length;n++){
      const fixture=files[n],file=path.join(ROOT,fixture.file),size=fs.statSync(file).size;
      const profile=path.join(OUT,'browser-profiles',String(n));
      assert(!fs.existsSync(profile),'Browser profile already exists; preserve prior evidence and start a fresh HTTP test environment');
      let context,page,cdp,pid;
      const requests=[],errors=[];
      let peakRss=0,peakJs=0,reloaded=false,abortOnce=n===1,gate=n===1,acknowledged=new Set(),pausedDisplay=null;
      const timer=setInterval(async()=>{
        if(pid)peakRss=Math.max(peakRss,rss(pid)||0);
        try{const m=await cdp?.send('Runtime.getHeapUsage');if(m)peakJs=Math.max(peakJs,m.usedSize+(m.backingStorageSize||0));}catch{}
      },500);
      const launch=async()=>{
        context=await playwright[engine].launchPersistentContext(profile,{executablePath,headless:true,...(stockFirefox?{channel:'moz-firefox'}:{})});activeContext=context;
        report.browser_version=context.browser().version();
        pid=browserPid(profile);page=context.pages()[0]||await context.newPage();
        cdp=engine==='chromium'?await context.newCDPSession(page):null;
        page.on('pageerror',e=>errors.push(e.message));
        page.on('request',r=>{if(r.url().includes('/api/'))requests.push({url:new URL(r.url()).pathname,method:r.method(),afterReload:reloaded});});
        await context.route('**/tile.openstreetmap.org/**',r=>r.abort());
        await context.route('**/api/v1/uploads/*/parts/*',async route=>{
          const i=Number(new URL(route.request().url()).pathname.split('/').at(-1));
          if(abortOnce){abortOnce=false;return route.abort('internetdisconnected');}
          if(gate&&i>=2)return route.abort('internetdisconnected');
          return route.continue();
        });
      };
      const start=Date.now();
      try{
        await launch();
        await page.goto(base,{waitUntil:'domcontentloaded'});
        await page.locator('#file').setInputFiles(file);await page.locator('#run').click();
        if(gate){
          // Two confirmed 16 MiB parts (~32% of 100 MiB), then restart the browser process with its own persisted profile.
          await page.waitForFunction(async()=>{
            const s=JSON.parse(localStorage.getItem('heatnet-upload-v1')||'null');if(!s)return false;
            const r=await fetch('/api/v1/uploads/'+s.request.uploadId,{headers:{'X-Upload-Token':s.token}});
            return r.ok&&(await r.json()).receivedBytes>=32*1024**2;
          },null,{timeout:120000,polling:500});
          await page.locator('#pauseUpload').click();await page.waitForFunction(()=>!document.getElementById('run').disabled);
          const before=await page.evaluate(async()=>{
            const s=JSON.parse(localStorage.getItem('heatnet-upload-v1'));
            return (await fetch('/api/v1/uploads/'+s.request.uploadId,{headers:{'X-Upload-Token':s.token}})).json();
          });
          acknowledged=new Set(before.parts.map(p=>p.index));assert(acknowledged.size>=2);
          pausedDisplay=await page.locator('#uploadStatus').innerText();
          gate=false;reloaded=true;await context.close();pid=null;cdp=null;
          await launch();await page.goto(base,{waitUntil:'domcontentloaded'});
          assert.match(await page.locator('#uploadStatus').innerText(),/Выберите тот же файл/);
          await page.locator('#file').setInputFiles(file);await page.locator('#run').click();
        }
        await page.waitForFunction(()=>document.getElementById('status').textContent.startsWith('Готово'),null,{timeout:600000,polling:500});
        // The UI sets DONE before its asynchronous result fetch finishes. Wait for that fetch,
        // rather than making the acceptance test depend on Playwright event delivery timing.
        await page.waitForFunction(()=>!document.getElementById('resultCard').hidden,null,{timeout:30000});
        const visible=await page.locator('#uploadStatus').innerText();assert.match(visible,/100/);
        const jobPath=requests.filter(r=>/\/jobs\/[0-9a-f-]+\/result$/.test(r.url)).at(-1).url;
        const result=await (await context.request.get(base+jobPath)).json();
        assert.equal(result.status,'DONE');assert.equal(result.validation.valid,true);
        assert.equal(result.validation.errors,0);assert.equal(result.validation.warnings,0);
        const output=await (await context.request.get(base+jobPath+'.geojson')).body();
        const outputSha=createHash('sha256').update(output).digest('hex');
        assert.equal(outputSha,createHash('sha256').update(fs.readFileSync(path.join(ROOT,'examples/stress/baseline/planar.geojson'))).digest('hex'));
        if(size>10*1024**2)assert(!requests.some(r=>/\/input(?:-preview)?\.geojson$/.test(r.url)));
        if(reloaded)for(const r of requests.filter(r=>r.afterReload&&r.method==='PUT'))assert(!acknowledged.has(Number(r.url.split('/').at(-1))),'confirmed part was resent');
        assert.deepEqual(errors,[]);
        const row={file:fixture.file,bytes:size,status:'OK',job_id:result.jobId,wall_s:(Date.now()-start)/1000,
          actual_browser_process_restart:reloaded,confirmed_parts_not_resent:[...acknowledged],
          network_error_injected:n===1,input_download_requested:requests.some(r=>/\/input(?:-preview)?\.geojson$/.test(r.url)),
          peak_browser_tree_rss_bytes:peakRss,peak_page_js_and_backing_storage_bytes:engine==='chromium'?peakJs:null,
          validation:result.validation,variants:result.variants,output_sha256:outputSha,upload_display:visible,paused_display:pausedDisplay,requests};
        report.runs.push(row);save();console.log('BROWSER VERIFIED',fixture.file,size);
        if(n===1)await page.screenshot({path:path.join(OUT,'upload-browser.png'),fullPage:true});
      }finally{clearInterval(timer);if(context)await context.close();activeContext=null;}
    }
    report.status='OK';save();
  }catch(e){report.status='FAILED';report.error=e.stack;save();throw e;}
  finally{if(activeContext)await activeContext.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
