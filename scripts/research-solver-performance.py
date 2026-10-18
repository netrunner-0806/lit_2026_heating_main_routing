#!/usr/bin/env python3
"""Bounded, sequential experiments against frozen builds. Never touches application containers/volumes.

Compile and freeze with benchmark-scale.py --label NAME --snapshot first.
Outputs/JFR stay in target/research. A completed valid variant can coexist with a diagnosed
failed strategy (audit D2); diagnostic errors are retained verbatim, never treated as unreachable.
"""
import argparse, hashlib, json, subprocess, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--label',required=True)
    p.add_argument('--scene',default='original')
    p.add_argument('--mode',choices=['PLANAR','DEPTH','context'],default='PLANAR')
    p.add_argument('--repeat',type=int,default=4,help='warm runs after one warm-up; 0 means a single control')
    p.add_argument('--name',required=True)
    p.add_argument('--profile',action='store_true')
    p.add_argument('--parallelism',type=int)
    p.add_argument('--timeout',type=int,default=1200)
    a=p.parse_args()
    for value in [a.label,a.name]:
        if not value.replace('-','').replace('_','').isalnum():p.error('invalid label/name')
    scenes={'original':'ТЗ/Датасет скорректированный.geojson',
            'osm-water':'examples/stress/competition-osm-roads-water.geojson',
            **{str(n):f'target/scale-data/consumers-{n}.geojson' for n in [50,100,250,500]}}
    source=ROOT/scenes.get(a.scene,a.scene)
    source=source.resolve();source.relative_to(ROOT)
    build=ROOT/'target/scale-builds'/a.label
    out=ROOT/'target/research'/a.name;out.mkdir(parents=True,exist_ok=False)
    report=out/'runs.json';cid=out/'container.cid'
    cp=f'/work/{build.relative_to(ROOT)}/classes:/work/{build.relative_to(ROOT)}/test-classes:'+(build/'classpath.txt').read_text().strip()
    cmd=['docker','run','--rm','--pull=never','--cpus','8','--memory','4g','--cidfile',str(cid),
         '-v',f'{ROOT}:/work','-v','lct-robustness-m2:/root/.m2:ro','-w','/work','lct-robustness-builder:baseline',
         'java','-Xms128m','-Xmx2g','-XX:+UseG1GC','-XX:+ExitOnOutOfMemoryError']
    if a.parallelism is not None:cmd += [f'-Djava.util.concurrent.ForkJoinPool.common.parallelism={a.parallelism}']
    if a.profile:cmd += [f'-XX:StartFlightRecording=filename=/work/{out.relative_to(ROOT)}/profile.jfr,settings=profile,dumponexit=true']
    cmd += ['-cp',cp,'ru.lct.heatnet.stress.ScaleBenchmarkMain','/work/'+str(source.relative_to(ROOT)),a.mode,'/work/'+str(report.relative_to(ROOT)),str(a.repeat)]
    meta={'status':'RUNNING','label':a.label,'build_sha256':(build/'sha256.txt').read_text().strip(),
          'input':str(source.relative_to(ROOT)),'input_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),
          'mode':a.mode,'warm_repeats':a.repeat,'profile':a.profile,'parallelism':a.parallelism,
          'cpu_limit':8,'container_memory':'4g','heap':'2g','jvm':'Java 11; Xms128m; G1GC; ExitOnOutOfMemoryError'}
    meta['reproduce']=['python3','scripts/research-solver-performance.py','--label',a.label,
        '--scene',a.scene,'--mode',a.mode,'--repeat',str(a.repeat),'--name',a.name,'--timeout',str(a.timeout)]
    if a.profile:meta['reproduce'].append('--profile')
    if a.parallelism is not None:meta['reproduce']+=['--parallelism',str(a.parallelism)]
    metadata=out/'metadata.json';metadata.write_text(json.dumps(meta,indent=2)+'\n')
    start=time.monotonic();failure=None
    try:
        with (out/'run.log').open('w') as log:
            result=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=a.timeout)
        if result.returncode:raise RuntimeError('JVM exit '+str(result.returncode))
        rows=json.loads(report.read_text());assert len(rows)==a.repeat+1
        if a.mode!='context':
            assert all(r.get('valid') and not r['errors'] and not r['warnings'] and not r['engineering_oracle_issues'] for r in rows)
            assert len({r['output_sha256'] for r in rows})==1,'non-deterministic output'
            if a.scene=='original':
                expected=hashlib.sha256((ROOT/f'examples/stress/baseline/{a.mode.lower()}.geojson').read_bytes()).hexdigest()
                assert all(r['output_sha256']==expected for r in rows),'competition baseline changed'
        meta['status']='OK'
    except subprocess.TimeoutExpired as ex:
        failure=ex;meta['status']='TIMEOUT';meta['failure']=f'Experiment wall budget {a.timeout}s exceeded; not a solver infeasibility result'
    except Exception as ex:
        failure=ex;meta['status']='FAILED';meta['failure']=str(ex)
    finally:
        if cid.exists():subprocess.run(['docker','stop','--time','3',cid.read_text().strip()],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        meta['process_wall_s']=time.monotonic()-start;metadata.write_text(json.dumps(meta,indent=2)+'\n')
    print(json.dumps(meta),flush=True)
    if failure:raise failure

if __name__=='__main__':main()
