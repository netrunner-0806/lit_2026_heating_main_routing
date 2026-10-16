#!/usr/bin/env python3
"""Real HTTP uploads through the repository Caddy config, in a fresh isolated Compose project.
prepare -> HTTP verification -> browser verification -> stop. Never touches production containers/volumes.
"""
import argparse, concurrent.futures, datetime, hashlib, http.client, json, secrets, subprocess, time, uuid, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'target/final-engineering/http'

def command(args):return subprocess.check_output(args,cwd=ROOT,text=True,stderr=subprocess.STDOUT).strip()
def load():
    s=json.loads((OUT/'environment.json').read_text());assert s['project'].startswith('lct-final-upload-');return s
def compose(s):return ['docker','compose','-p',s['project'],'-f',s['compose']]
def request(s,method,path,body=None,headers=None,expected=200,raw=False):
    c=http.client.HTTPConnection('localhost',s['port'],timeout=300)
    try:
        c.request(method,path,body=body,headers=headers or {});r=c.getresponse();data=r.read()
        assert r.status==expected,(method,path,r.status,data[:500])
        return data if raw else json.loads(data) if data else None
    finally:c.close()
def healthy(s):
    until=time.monotonic()+180
    while time.monotonic()<until:
        try:
            if request(s,'GET','/actuator/health')['status']=='UP':
                app=command(compose(s)+['ps','-q','app'])
                if app and command(['docker','inspect',app,'--format','{{.State.Health.Status}}'])=='healthy':return
        except (OSError,AssertionError,http.client.HTTPException,subprocess.CalledProcessError):pass
        time.sleep(1)
    raise RuntimeError('Proxy/app did not become healthy')
def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(65536),b''):h.update(block)
    return h.hexdigest()
def sample(s):
    app=command(compose(s)+['ps','-q','app'])
    processes=command(['docker','exec',app,'ps','-eo','pid,comm']).splitlines()
    pid=next(line.split()[0] for line in processes if line.split()[-1]=='java')
    status=command(['docker','exec',app,'cat','/proc/'+pid+'/status'])
    return {k:int(next(l.split()[1] for l in status.splitlines() if l.startswith(k+':')))*1024 for k in ['VmRSS','VmHWM']}

def native_memory(s,name):
    app=command(compose(s)+['ps','-q','app'])
    processes=command(['docker','exec',app,'ps','-eo','pid,comm']).splitlines()
    pid=next(line.split()[0] for line in processes if line.split()[-1]=='java')
    result={'rss':sample(s)}
    for operation in ['VM.native_memory','GC.heap_info']:
        args=['summary'] if operation=='VM.native_memory' else []
        cmd=['docker','run','--rm','--network','none','--pid','container:'+app,'--volumes-from',app,
             'lct-robustness-builder:baseline','timeout','20','jcmd',pid,operation]+args
        try:
            text=command(cmd);(OUT/(name+'-'+operation+'.txt')).write_text(text+'\n');result[operation]=text
        except subprocess.CalledProcessError as ex:result[operation]={'unavailable':ex.output}
    return result

def native_disk_full_probe():
    # Fill only a disposable 1 MiB tmpfs; never fill a workspace or persistent application volume.
    source=OUT/'DiskFullProbe.java'
    source.write_text('''import java.nio.*;import java.nio.channels.*;import java.nio.file.*;
public class DiskFullProbe { public static void main(String[] args) throws Exception {
try(FileChannel f=FileChannel.open(Path.of("/probe/data"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
ByteBuffer b=ByteBuffer.allocate(65536);for(int i=0;i<32;i++){b.clear();while(b.hasRemaining())f.write(b);}
throw new AssertionError("The 1 MiB tmpfs unexpectedly accepted 2 MiB");
} catch(java.io.IOException ex) {System.out.println(ex.getClass().getName()+": "+ex.getMessage());}
}}
''')
    result=command(['docker','run','--rm','--cpus','1','--memory','256m','--network','none',
                    '--mount','type=tmpfs,destination=/probe,tmpfs-size=1048576',
                    '-v',str(source)+':/DiskFullProbe.java:ro','lct-robustness-builder:baseline',
                    'java','-Xmx64m','/DiskFullProbe.java'])
    assert 'java.io.IOException: No space left on device' in result,result
    (OUT/'disk-full-native.log').write_text(result+'\n')
    return {'exception':result,'tmpfs_limit_bytes':1048576,'persistent_data_touched':False}

def prepare(port,tested_build):
    OUT.mkdir(parents=True,exist_ok=True)
    if (OUT/'environment.json').exists():
        previous=load()
        if command(compose(previous)+['ps','--all','-q']):
            raise RuntimeError('An isolated test environment still exists; finish or stop it explicitly')
        archive=OUT.with_name('http-archive-'+previous['project'])
        assert not archive.exists(),'Previous evidence archive already exists'
        OUT.rename(archive);OUT.mkdir()
    project='lct-final-upload-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S')
    with (OUT/'build.log').open('w') as log:
        subprocess.run(['docker','build','-t','lct-finalization:verified','.'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
    cfg={'services':{
        'db':{'image':'postgres:16-alpine','environment':{'POSTGRES_DB':'heatnet','POSTGRES_USER':'heatnet','POSTGRES_PASSWORD':'local-test-only'},
              'volumes':['test-pg:/var/lib/postgresql/data'],'healthcheck':{'test':['CMD-SHELL','pg_isready -U heatnet'],'interval':'2s','timeout':'5s','retries':30}},
        'app':{'image':'lct-finalization:verified','depends_on':{'db':{'condition':'service_healthy'}},'cpus':8,'mem_limit':'4g',
               'environment':{'SPRING_DATASOURCE_URL':'jdbc:postgresql://db:5432/heatnet','SPRING_DATASOURCE_USERNAME':'heatnet',
                    'SPRING_DATASOURCE_PASSWORD':'local-test-only','HEATNET_WORKERS':'1','HEATNET_TMP_DIR':'/app/data/tmp',
                    'JAVA_OPTS':'-Xms128m -Xmx2g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics'},
               'volumes':['test-data:/app/data'],'restart':'unless-stopped',
               'healthcheck':{'test':['CMD-SHELL','curl -fsS http://localhost:8080/actuator/health | grep -q UP'],'interval':'5s','timeout':'5s','retries':20,'start_period':'30s'}},
        'caddy':{'image':'caddy:2.11.4-alpine','depends_on':{'app':{'condition':'service_healthy'}},
                 'environment':{'DOMAIN':'http://localhost','ACME_EMAIL':'admin@example.com'},'ports':[f'127.0.0.1:{port}:80'],
                 'volumes':[str(ROOT/'caddy/Caddyfile')+':/etc/caddy/Caddyfile:ro','test-caddy-data:/data','test-caddy-config:/config']}
        },'volumes':{k:{} for k in ['test-pg','test-data','test-caddy-data','test-caddy-config']}}
    path=OUT/'compose.json';path.write_text(json.dumps(cfg,indent=2)+'\n')
    s={'project':project,'compose':str(path),'port':port,'production_volumes_touched':False,
       'caddy_config_sha256':sha(ROOT/'caddy/Caddyfile'),'dockerfile_sha256':sha(ROOT/'Dockerfile')}
    s['native_disk_full_probe']=native_disk_full_probe()
    memory,cpus,architecture=command(['docker','info','--format','{{.MemTotal}} {{.NCPU}} {{.Architecture}}']).split()
    s['docker_resources']={'memory_bytes':int(memory),'logical_cpus':int(cpus),'architecture':architecture,
                           'app_cpu_quota':8,'app_memory_limit_bytes':4*1024**3,'app_max_heap_bytes':2*1024**3}
    artifact=command(['docker','create','--name',project+'-artifact','lct-finalization:verified'])
    try:command(['docker','cp',artifact+':/app/app.jar',str(OUT/'app.jar')])
    finally:command(['docker','rm',artifact])
    classes=hashlib.sha256()
    with zipfile.ZipFile(OUT/'app.jar') as jar:
        for name in sorted(n for n in jar.namelist() if n.startswith('BOOT-INF/classes/') and n.endswith('.class')):
            classes.update(name.removeprefix('BOOT-INF/classes/').encode());classes.update(jar.read(name))
        s['static_sha256']={}
        for name in ['app.js','upload.js','index.html','style.css']:
            digest=hashlib.sha256(jar.read('BOOT-INF/classes/static/'+name)).hexdigest()
            assert digest==sha(ROOT/'src/main/resources/static'/name),'Stale Docker UI: '+name
            s['static_sha256'][name]=digest
    assert classes.hexdigest()==(ROOT/'target/scale-builds'/tested_build/'sha256.txt').read_text().strip(),'Docker classes differ from tested build'
    s['tested_classes_sha256']=classes.hexdigest()
    (OUT/'environment.json').write_text(json.dumps(s,indent=2)+'\n')
    command(['docker','compose','config','--quiet']);command(compose(s)+['up','-d','--wait','--wait-timeout','180']);healthy(s)
    s['java_process']=command(compose(s)+['exec','-T','app','sh','-c','ps -ef | grep "[j]ava"'])
    s['health']='UP';s['image_id']=command(['docker','image','inspect','lct-finalization:verified','--format','{{.Id}}'])
    s['startup_memory']=native_memory(s,'startup')
    (OUT/'environment.json').write_text(json.dumps(s,indent=2)+'\n');print('PREPARED',project,flush=True)

def upload(s,path,restart=False,lost_response=False,expected_status='DONE',mode='PLANAR'):
    size=path.stat().st_size;chunk=16*1024**2;ident=str(uuid.uuid4());token=secrets.token_hex(32)
    base='/api/v1/uploads/'+ident;headers={'X-Upload-Token':token};expected_sha=sha(path)
    meta={'uploadId':ident,'filename':path.name,'totalSize':size,'chunkSize':chunk,'fingerprint':expected_sha,
          'mode':mode,'maxVariants':3,'unknownRestrictionPolicy':'IGNORE'}
    start=time.monotonic();request(s,'POST','/api/v1/uploads',json.dumps(meta).encode(),{**headers,'Content-Type':'application/json'},201)
    request(s,'GET',base,None,{},403)
    transferred=0;part_count=(size+chunk-1)//chunk;restarted=False;interrupted_part=None
    before_restart_jobs=request(s,'GET','/api/v1/jobs')
    with path.open('rb') as f:
        for index in range(part_count):
            data=f.read(chunk);digest=hashlib.sha256(data).hexdigest();h={**headers,'Content-Type':'application/octet-stream','X-Part-SHA256':digest}
            if restart and not restarted and index>=int(part_count*.35):
                # Real half-written HTTP body, socket closed before declared Content-Length; no part acknowledgement.
                c=http.client.HTTPConnection('localhost',s['port'],timeout=20)
                c.putrequest('PUT',base+'/parts/'+str(index))
                for k,v in h.items():c.putheader(k,v)
                c.putheader('Content-Length',str(len(data)));c.endheaders();c.send(data[:len(data)//2]);c.close()
                interrupted_part=index;transferred+=len(data)//2
                command(compose(s)+['restart','app']);healthy(s);restarted=True
                status=request(s,'GET',base,None,headers)
                assert {p['index'] for p in status['parts']}==set(range(index)),status
                for j in before_restart_jobs:
                    assert request(s,'GET','/api/v1/jobs/'+j['id'])['status']==j['status']
            if lost_response and index==0:
                # Send the entire request, observe persistence over a separate connection, then discard the response.
                c=http.client.HTTPConnection('localhost',s['port'],timeout=30)
                c.request('PUT',base+'/parts/'+str(index),data,h);transferred+=len(data)
                until=time.monotonic()+30
                while request(s,'GET',base,None,headers)['receivedBytes']<len(data):
                    assert time.monotonic()<until;time.sleep(.05)
                c.close()
            request(s,'PUT',base+'/parts/'+str(index),data,h);transferred+=len(data)
            assert request(s,'GET',base,None,headers)['receivedBytes']==min(size,(index+1)*chunk)
    ready=request(s,'GET',base,None,headers);assert ready['receivedBytes']==size
    assert ready['jobId'] is None,'Incomplete/unfinalized input was exposed as a job'
    assert {j['id'] for j in request(s,'GET','/api/v1/jobs')}=={j['id'] for j in before_restart_jobs}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(lambda _:request(s,'POST',base+'/complete',None,headers,202),range(2)))
    assert results[0]['jobId']==results[1]['jobId'];assert results[0]['sha256']==expected_sha
    job=results[0]['jobId'];upload_s=time.monotonic()-start
    actual=command(compose(s)+['exec','-T','app','sha256sum','/app/data/jobs/'+job+'/input.geojson']).split()[0]
    assert actual==expected_sha
    command(compose(s)+['exec','-T','app','test','!','-e','/app/data/jobs/.uploads/'+ident+'/data'])
    assert len([j for j in request(s,'GET','/api/v1/jobs') if j['id']==job])==1
    row={'file':str(path.relative_to(ROOT)),'mode':mode,'bytes':size,'input_sha256':expected_sha,'stored_sha256':actual,'job_id':job,
         'upload_wall_s':upload_s,'transmitted_bytes':transferred,'restart_during_upload':restarted,
         'interrupted_part':interrupted_part,'duplicate_complete_same_job':True,'through_caddy':True,'upload_status':'OK'}
    # Calculation is measured separately; upload success is never presented as calculation success.
    deadline=time.monotonic()+300
    while time.monotonic()<deadline:
        j=request(s,'GET','/api/v1/jobs/'+job)
        if j['status'] in ('DONE','FAILED'):break
        time.sleep(.5)
    row['calculation_status']=j['status'];row['calculation_elapsed_ms']=j['elapsedMs']
    assert j['status']==expected_status,j
    if expected_status=='FAILED':
        assert 'COMPLEXITY_LIMIT' in json.dumps(j['diagnostics']),j
        assert command(compose(s)+['exec','-T','app','sha256sum','/app/data/jobs/'+job+'/input.geojson']).split()[0]==expected_sha
        row.update(error=j['error'],diagnostics=j['diagnostics'],failed_input_retained=True,memory=sample(s))
        healthy(s);print('HTTP VERIFIED controlled resource failure; input retained',flush=True);return row
    result=request(s,'GET','/api/v1/jobs/'+job+'/result');assert result['validation']['valid'],result
    assert not result['validation']['errors'] and not result['validation']['warnings'],result
    output=request(s,'GET','/api/v1/jobs/'+job+'/result.geojson',raw=True)
    row.update(output_sha256=hashlib.sha256(output).hexdigest(),validation=result['validation'],variants=result['variants'],memory=sample(s))
    if path.name.startswith('valid-') or path.name=='original.geojson':
        assert row['output_sha256']==sha(ROOT/'examples/stress/baseline'/f'{mode.lower()}.geojson')
    healthy(s);print('HTTP VERIFIED',path.name,size,round(upload_s,2),'s; calculation',j['status'],flush=True);return row

def interrupted_calculations(s):
    """Actual old multipart submissions, interrupted while RUNNING/QUEUED, with retained inputs after restart."""
    content=(ROOT/'target/scale-data/original.geojson').read_bytes()
    boundary='heatnet-smoke-'+uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="restart.geojson"\r\n'
          'Content-Type: application/geo+json\r\n\r\n').encode()+content+f'\r\n--{boundary}--\r\n'.encode()
    headers={'Content-Type':'multipart/form-data; boundary='+boundary}
    jobs=[request(s,'POST','/api/v1/jobs',body,headers,202) for _ in range(2)]
    deadline=time.monotonic()+10
    while True:
        statuses=[request(s,'GET','/api/v1/jobs/'+j['id'])['status'] for j in jobs]
        if statuses==['RUNNING','QUEUED']:break
        assert time.monotonic()<deadline,statuses
        time.sleep(.1)
    # SIGKILL only this disposable app, then let its existing unless-stopped policy restart it.
    app=command(compose(s)+['ps','-q','app'])
    before=int(command(['docker','inspect',app,'--format','{{.RestartCount}}']))
    processes=command(['docker','exec',app,'ps','-eo','pid,comm']).splitlines()
    pid=next(line.split()[0] for line in processes if line.split()[-1]=='java')
    killed=subprocess.run(['docker','exec',app,'kill','-KILL',pid],capture_output=True,text=True)
    assert killed.returncode in (0,137),killed.stderr
    deadline=time.monotonic()+60
    while int(command(['docker','inspect',app,'--format','{{.RestartCount}}']))<=before:
        assert time.monotonic()<deadline,'App did not restart automatically';time.sleep(.5)
    healthy(s)
    rows=[]
    for j in jobs:
        after=request(s,'GET','/api/v1/jobs/'+j['id']);assert after['status']=='FAILED',after
        assert 'restart' in after['error'].lower(),after
        actual=command(compose(s)+['exec','-T','app','sha256sum','/app/data/jobs/'+j['id']+'/input.geojson']).split()[0]
        assert actual==hashlib.sha256(content).hexdigest()
        rows.append({'job_id':j['id'],'status':after['status'],'input_sha256':actual,'reason':after['error']})
    return {'legacy_multipart_compatible':True,'states_before':statuses,'jobs_after':rows,
            'automatic_restart_count':int(command(['docker','inspect',app,'--format','{{.RestartCount}}']))-before,
            'restart_policy':command(['docker','inspect',app,'--format','{{.HostConfig.RestartPolicy.Name}}'])}

def cancel_session(s):
    ident=str(uuid.uuid4());token=secrets.token_hex(32);base='/api/v1/uploads/'+ident
    headers={'X-Upload-Token':token};content=b'{"type":"FeatureCollection","features":[]}'
    meta={'uploadId':ident,'filename':'cancel.geojson','totalSize':len(content),'fingerprint':hashlib.sha256(content).hexdigest()}
    request(s,'POST','/api/v1/uploads',json.dumps(meta).encode(),{**headers,'Content-Type':'application/json'},201)
    request(s,'PUT',base+'/parts/0',content,{**headers,'Content-Type':'application/octet-stream','X-Part-SHA256':hashlib.sha256(content).hexdigest()})
    request(s,'DELETE',base,None,headers,204);request(s,'GET',base,None,headers,410)
    command(compose(s)+['exec','-T','app','test','!','-e','/app/data/jobs/.uploads/'+ident+'/data'])
    return {'status':'CANCELLED','temporary_data_removed':True}

def verify():
    s=load();rows=[];report={'status':'RUNNING','environment':s,'runs':rows}
    def save(): (OUT/'verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    save()
    try:
        large_metadata=json.dumps({'padding':'x'*9000}).encode()
        for path in ['/api/v1/uploads','/api/v1/uploads/','/api/v1/uploads;probe=1','/api/v1/uploads/'+str(uuid.uuid4())+'/complete']:
            request(s,'POST',path,large_metadata,{'Content-Type':'application/json'},413)
        report['metadata_path_variants_rejected']=True;save()
        files=[ROOT/'examples/stress/routing-fallback.geojson',ROOT/'target/scale-data/original.geojson',ROOT/'examples/stress/competition-osm-roads.geojson']
        files += [ROOT/x['file'] for x in json.loads((ROOT/'target/upload-data/manifest.json').read_text())]
        for path in files:
            rows.append(upload(s,path,restart=path.stat().st_size==1024**3,lost_response=path.stat().st_size<100000));save()
        report['memory_after_large_uploads']=native_memory(s,'after-large-uploads');save()
        oversize={'uploadId':str(uuid.uuid4()),'filename':'too-large.geojson','totalSize':3*1024**3+1,'fingerprint':'ab'*32}
        overtoken=secrets.token_hex(32)
        request(s,'POST','/api/v1/uploads',json.dumps(oversize).encode(),{'X-Upload-Token':overtoken,'Content-Type':'application/json'},413)
        request(s,'GET','/api/v1/uploads/'+oversize['uploadId'],None,{'X-Upload-Token':overtoken},404)
        report['cancel']=cancel_session(s);save()
        report['crash_recovery']=interrupted_calculations(s);save()
        # Valid JSON and source geometry, but one Feature intentionally exceeds the measured 2 GiB JVM budget.
        original=json.loads((ROOT/'target/scale-data/original.geojson').read_text())
        feature=original['features'][0];feature['properties']['upload_padding']='x'*(34*1024**2)
        too_complex=OUT/'oversized-feature.geojson';too_complex.write_text(json.dumps(original,separators=(',',':')))
        rows.append(upload(s,too_complex,expected_status='FAILED'));save()
        rows.append(upload(s,ROOT/'examples/stress/routing-fallback.geojson'));save()
        rows.append(upload(s,ROOT/'target/scale-data/original.geojson',mode='DEPTH'));save()
        report.update(status='OK',oversize_rejected_before_transfer=True,health='UP',production_volumes_touched=False,
                      memory_after_http=native_memory(s,'after-http'),compose_ps=command(compose(s)+['ps','--format','json']));save()
    except Exception as ex:
        report.update(status='FAILED',exception=str(ex));save();raise

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','verify','stop']);p.add_argument('--port',type=int,default=18089)
    p.add_argument('--tested-build',default='finalization-final')
    a=p.parse_args()
    if a.action=='prepare':prepare(a.port,a.tested_build)
    elif a.action=='verify':verify()
    else:
        s=load();(OUT/'memory-after-browser.json').write_text(json.dumps(native_memory(s,'after-browser'),indent=2)+'\n')
        command(compose(s)+['stop','app'])
        (OUT/'app.log').write_text(command(compose(s)+['logs','--no-color','app']))
        command(compose(s)+['down']) # Preserve only our named test volumes for inspection, never use -v.
        print('Stopped isolated project',s['project'])
if __name__=='__main__':main()
