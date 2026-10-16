#!/usr/bin/env python3
"""Build the unchanged Dockerfile and test an isolated disposable Compose project, never production volumes."""
import argparse, datetime, hashlib, json, subprocess, time, urllib.request, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out',type=Path,default=ROOT/'target/scale-acceptance/final')
    p.add_argument('--image',default='lct-scale:verified')
    p.add_argument('--port',type=int,default=18088)
    p.add_argument('--dockerfile',type=Path,default=ROOT/'Dockerfile')
    p.add_argument('--tested-build',default='final')
    a=p.parse_args();a.out=a.out.resolve();a.out.mkdir(parents=True,exist_ok=True)
    project='lct-scale-smoke-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S')
    def call(cmd):return subprocess.check_output(cmd,cwd=ROOT,text=True).strip()
    if call(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project]):raise RuntimeError('Project name already exists')
    with (a.out/'docker-build.log').open('w') as log:
        subprocess.run(['docker','build','-f',str(a.dockerfile),'-t',a.image,'.'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
    artifact_container=call(['docker','create','--name',project+'-artifact',a.image])
    jar=a.out/'app.jar'
    try:call(['docker','cp',artifact_container+':/app/app.jar',str(jar)])
    finally:call(['docker','rm',artifact_container])
    classes=hashlib.sha256()
    with zipfile.ZipFile(jar) as z:
        for name in sorted(n for n in z.namelist() if n.startswith('BOOT-INF/classes/') and n.endswith('.class')):
            classes.update(name.removeprefix('BOOT-INF/classes/').encode());classes.update(z.read(name))
    expected=(ROOT/'target/scale-builds'/a.tested_build/'sha256.txt').read_text().strip()
    assert classes.hexdigest()==expected,'Docker production classes differ from the tested frozen build'
    call(['docker','compose','config','--quiet'])
    cfg={'services':{'db':{'image':'postgres:16-alpine','environment':{'POSTGRES_DB':'heatnet','POSTGRES_USER':'heatnet','POSTGRES_PASSWORD':'heatnet'},
                         'volumes':['smoke-pg:/var/lib/postgresql/data'],
                         'healthcheck':{'test':['CMD-SHELL','pg_isready -U heatnet'],'interval':'2s','timeout':'5s','retries':30}},
          'app':{'image':a.image,'depends_on':{'db':{'condition':'service_healthy'}},'cpus':8,'mem_limit':'4g',
                 'environment':{'SPRING_DATASOURCE_URL':'jdbc:postgresql://db:5432/heatnet','SPRING_DATASOURCE_USERNAME':'heatnet',
                     'SPRING_DATASOURCE_PASSWORD':'heatnet','HEATNET_WORKERS':'1','JAVA_OPTS':'-Xms128m -Xmx2g -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError','HEATNET_TMP_DIR':'/app/data/tmp'},
                 'volumes':['smoke-data:/app/data'],'ports':[f'127.0.0.1:{a.port}:8080'],'restart':'unless-stopped',
                 'healthcheck':{'test':['CMD-SHELL','curl -fsS http://localhost:8080/actuator/health | grep -q UP'],
                               'interval':'5s','timeout':'5s','retries':12,'start_period':'30s'}}},'volumes':{'smoke-pg':{},'smoke-data':{}}}
    file=a.out/'docker-compose.smoke.json';file.write_text(json.dumps(cfg,indent=2)+'\n')
    compose=['docker','compose','-p',project,'-f',str(file)]
    base=f'http://127.0.0.1:{a.port}'
    def get(path,raw=False):
        with urllib.request.urlopen(base+path,timeout=20) as r:b=r.read()
        return b if raw else json.loads(b)
    def post(path,files):
        boundary='lct-scale-local-smoke';parts=[]
        for key,content in files.items():
            parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"; filename="{key}.geojson"\r\nContent-Type: application/geo+json\r\n\r\n').encode()+content+b'\r\n')
        body=b''.join(parts)+f'--{boundary}--\r\n'.encode()
        request=urllib.request.Request(base+path,data=body,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
        with urllib.request.urlopen(request,timeout=60) as r:return json.loads(r.read())
    rows=[]
    def job(scene,mode,content,expected='DONE'):
        ident=post('/api/v1/jobs?mode='+mode,{'file':content})['id'];deadline=time.monotonic()+300
        while time.monotonic()<deadline:
            j=get('/api/v1/jobs/'+ident)
            if j['status'] in ['DONE','FAILED']:break
            time.sleep(.5)
        assert j['status']==expected,j
        row={'scene':scene,'mode':mode,'id':ident,'status':j['status']}
        if expected=='DONE':
            result=get('/api/v1/jobs/'+ident+'/result.geojson',True)
            validation=post('/api/v1/validate',{'input':content,'result':result})
            assert validation['valid'] and validation['errors']==0 and validation['warnings']==0,validation
            row.update(validation=validation,output_sha256=hashlib.sha256(result).hexdigest())
            assert get('/api/v1/jobs/'+ident+'/input.geojson',True)==content
        else:
            assert 'COMPLEXITY_LIMIT' in json.dumps(j),j
            row['error']=j.get('error')
        rows.append(row);print(scene,mode,expected,flush=True)
        assert get('/actuator/health')['status']=='UP'
        return row
    result={'project':project,'production_volumes_touched':False,'image':a.image,'dockerfile_sha256':hashlib.sha256(a.dockerfile.read_bytes()).hexdigest(),
            'classes_sha256':classes.hexdigest(),'tested_build':a.tested_build,'tested_classes_identical':True}
    try:
        call(compose+['up','-d','--wait','--wait-timeout','120'])
        assert get('/actuator/health')['status']=='UP'
        original=next((ROOT/'ТЗ').glob('Датасет скоррект*.geojson')).read_bytes()
        row=job('original','PLANAR',original)
        assert row['output_sha256']==hashlib.sha256((ROOT/'examples/stress/baseline/planar.geojson').read_bytes()).hexdigest()
        job('narrow-corridor','DEPTH',(ROOT/'examples/stress/universal-narrow-corridor.geojson').read_bytes())
        # A 34 MiB single Feature exceeds this isolated 2 GiB JVM's 32 MiB per-feature budget.
        oversized=b'{"type":"FeatureCollection","features":[{"type":"Feature","unused":"'+b'x'*(34<<20)+b'"}]}'
        job('oversized-feature-controlled-failure','PLANAR',oversized,'FAILED')
        content=(ROOT/'examples/stress/routing-fallback.geojson').read_bytes()
        job('next-job-after-resource-failure','PLANAR',content)
        call(compose+['restart','app']);call(compose+['up','-d','--wait','--wait-timeout','120'])
        for row in rows:assert get('/api/v1/jobs/'+row['id'])['status']==row['status']
        job('new-job-after-restart','PLANAR',content)
        result.update(status='OK',health=get('/actuator/health'),jobs=rows,persisted_jobs_after_restart=len(rows)-1,
            compose_ps=call(compose+['ps','--format','json']),
            java_process=call(compose+['exec','-T','app','sh','-c','ps -ef | grep "[j]ava"']),
            image_id=call(['docker','image','inspect',a.image,'--format','{{.Id}}']))
        (a.out/'docker.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    finally:
        # Only this fresh namespaced test project is stopped. Named test volumes remain reviewable.
        call(compose+['down'])
    print('Docker build/run/validation/resource-failure/restart: OK',flush=True)

if __name__=='__main__':main()
