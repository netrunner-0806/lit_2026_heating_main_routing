#!/usr/bin/env python3
"""Run frozen Java builds in isolated, bounded containers; preserve failed/OOM/timeout measurements.

Example: --label before --snapshot (after Maven test-compile), then --label before --phase parse.
No production services, volumes, configuration or Git operations are used.
"""
import argparse, hashlib, json, shutil, subprocess, time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--label',required=True)
    p.add_argument('--snapshot',action='store_true')
    p.add_argument('--phase',choices=['parse','prepare','graph','context','PLANAR','DEPTH','CLUSTER'],default='parse')
    p.add_argument('--harness',help='use this frozen test harness with the selected production build')
    p.add_argument('--counts',nargs='+',type=int,default=[10000,100000,500000,1000000])
    p.add_argument('--input',type=Path)
    p.add_argument('--repeat',type=int,default=4)
    p.add_argument('--heap',default='2g')
    p.add_argument('--timeout',type=int,default=900)
    p.add_argument('--image',default='lct-robustness-builder:baseline')
    p.add_argument('--maven-cache',default='lct-robustness-m2')
    a=p.parse_args()
    if not a.label.replace('-','').replace('_','').isalnum():p.error('label must be alphanumeric')
    build=ROOT/'target/scale-builds'/a.label
    if a.snapshot:
        if build.exists():p.error('frozen build already exists; use a different label')
        build.mkdir(parents=True)
        for part in ['classes','test-classes']:shutil.copytree(ROOT/'target'/part,build/part)
        shutil.copy2(ROOT/'target/stress-classpath.txt',build/'classpath.txt')
        h=hashlib.sha256()
        for f in sorted((build/'classes').rglob('*.class')):h.update(str(f.relative_to(build/'classes')).encode());h.update(f.read_bytes())
        (build/'sha256.txt').write_text(h.hexdigest());print('FROZEN',a.label,h.hexdigest());return
    assert build.exists(),'snapshot the build first'
    out=ROOT/'target/scale-results'/a.label;out.mkdir(parents=True,exist_ok=True)
    files=[a.input.resolve()] if a.input else [ROOT/f'target/scale-data/city-{n}.geojson' for n in a.counts]
    harness=ROOT/'target/scale-builds'/(a.harness or a.label)
    cp=f'/work/{build.relative_to(ROOT)}/classes:/work/{harness.relative_to(ROOT)}/test-classes:'+(build/'classpath.txt').read_text().strip()
    invocation=[]
    for file in files:
        assert file.is_file(),file
        name=file.stem+'-'+a.phase.lower(); report=out/(name+'.json');cid=out/(name+'.cid')
        meta_file=out/(name+'.meta.json')
        if report.exists() or cid.exists() or meta_file.exists():p.error('existing report, metadata or container marker: '+name)
        args=['ru.lct.heatnet.stress.ScaleBenchmarkMain','/work/'+str(file.relative_to(ROOT)),a.phase,'/work/'+str(report.relative_to(ROOT)),str(a.repeat)]
        if a.phase=='CLUSTER':args=['ru.lct.heatnet.stress.ClusterExperimentMain','/work/'+str(file.relative_to(ROOT)),'/work/'+str(report.relative_to(ROOT)),str(a.repeat)]
        cmd=['docker','run','--rm','--cpus','8','--memory','4g','--cidfile',str(cid),
             '-v',f'{ROOT}:/work','-v',f'{a.maven_cache}:/root/.m2:ro','-w','/work',a.image,
             'java','-Xms128m','-Xmx'+a.heap,'-XX:+UseG1GC','-XX:+ExitOnOutOfMemoryError',
             '-cp',cp]+args
        start=time.monotonic();failure=None;rc=None
        # Preserve the exact command even if the controlling session is interrupted. RUNNING is not success.
        meta={'dataset':str(file.relative_to(ROOT)),'phase':a.phase,'build_sha256':(build/'sha256.txt').read_text(),
              'status':'RUNNING','exit_code':None,'process_wall_s':None,
              'heap':a.heap,'container_memory':'4g','cpu_quota':8,'requested_warm_repeats':a.repeat,
              'test_harness':a.harness or a.label,'report':str(report.relative_to(ROOT)),'command':cmd}
        meta_file.write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
        try:
            with (out/(name+'.log')).open('w') as log:
                proc=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,timeout=a.timeout)
                rc=proc.returncode
        except subprocess.TimeoutExpired:failure='TIMEOUT'
        finally:
            if cid.exists():
                # Only the exact test container created by this invocation can be stopped.
                subprocess.run(['docker','stop','--time','3',cid.read_text().strip()],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                cid.unlink()
        text=(out/(name+'.log')).read_text()
        if failure is None and rc not in (0,None):
            if 'OutOfMemoryError' in text:failure='OOM'
            elif 'EXPERIMENT_REJECTED' in text:failure='EXPERIMENT_REJECTED'
            elif 'COMPLEXITY_LIMIT' in text:failure='COMPLEXITY_LIMIT'
            else:failure='PROCESS_FAILURE'
        if failure is None:
            try:
                rows=json.loads(report.read_text())
                expected=(a.repeat+1)*(3 if a.phase=='CLUSTER' else 1)
                if len(rows)!=expected:failure='INCOMPLETE_MEASUREMENT'
                if a.phase in ('PLANAR','DEPTH'):
                    if any(not r.get('valid') or r.get('errors') or r.get('warnings') or r.get('engineering_oracle_issues') for r in rows):failure='VALIDATION_FAILURE'
                    if any(d.get('severity')=='ERROR' for r in rows for d in r.get('diagnostics',[])):failure='SOLVER_DIAGNOSTIC_ERROR'
                    if len({r.get('output_sha256') for r in rows})!=1:failure='NONDETERMINISTIC_OUTPUT'
                    if file.stem=='original' or file.stem.startswith('city-') or file.stem=='large-simple':
                        expected_hash=hashlib.sha256((ROOT/'examples/stress/baseline'/f'{a.phase.lower()}.geojson').read_bytes()).hexdigest()
                        if any(r.get('output_sha256')!=expected_hash for r in rows):failure='BASELINE_CHANGED'
            except (ValueError,OSError,KeyError):failure='MISSING_OR_INVALID_REPORT'
        meta={'dataset':str(file.relative_to(ROOT)),'phase':a.phase,'build_sha256':(build/'sha256.txt').read_text(),
              'status':failure or 'OK','exit_code':rc,'process_wall_s':time.monotonic()-start,
              'heap':a.heap,'container_memory':'4g','cpu_quota':8,'requested_warm_repeats':a.repeat,'test_harness':a.harness or a.label,
              'report':str(report.relative_to(ROOT)),'command':cmd}
        meta_file.write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
        invocation.append(meta)
        print(json.dumps({k:v for k,v in meta.items() if k!='command'},ensure_ascii=False),flush=True)
        # A failed experiment is evidence, never a successful benchmark. Continue independent datasets,
        # but return a failing exit status if any run failed.
    return 1 if any(m['status']!='OK' for m in invocation) else 0

if __name__=='__main__':raise SystemExit(main())
