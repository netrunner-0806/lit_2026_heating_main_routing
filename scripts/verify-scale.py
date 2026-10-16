#!/usr/bin/env python3
"""Reproduce scalability acceptance on the current source, without touching production or Git.

Requires the existing Java 11/Maven Docker image/cache, Python shapely+pyproj and Node.
Frozen --before-label is optional: it refers to a build captured BEFORE editing source with benchmark-scale.py.
Negative historical experiments remain in their own reports; an acceptance failure always exits non-zero.
"""
import argparse, json, re, subprocess, sys, xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--label',required=True,help='new, unused frozen build label')
    p.add_argument('--before-label')
    p.add_argument('--image',default='lct-robustness-builder:baseline')
    p.add_argument('--cache',default='lct-robustness-m2')
    p.add_argument('--counts',nargs='+',type=int,default=[10000,100000,500000,1000000])
    p.add_argument('--repeat',type=int,default=4)
    p.add_argument('--generate',action='store_true')
    p.add_argument('--skip-docker-smoke',action='store_true')
    a=p.parse_args()
    out=ROOT/'target/scale-acceptance'/a.label
    if out.exists():p.error('acceptance directory already exists; choose a new label')
    out.mkdir(parents=True);commands=[]
    def run(cmd,name):
        print('RUN',name,flush=True);row={'step':name,'command':cmd};commands.append(row)
        with (out/(name+'.log')).open('w') as log:
            result=subprocess.run(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
        row['exit_code']=result.returncode
        (out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
        result.check_returncode()
    py=sys.executable
    if a.generate:
        run([py,'scripts/generate-scale-datasets.py','--counts',*[str(n) for n in a.counts]],'generate-city')
        run([py,'scripts/generate-scale-datasets.py','--only-large-simple'],'generate-large-simple')
    source=next((ROOT/'ТЗ').glob('Датасет скоррект*.geojson'))
    original=ROOT/'target/scale-data/original.geojson';original.parent.mkdir(parents=True,exist_ok=True)
    original.write_bytes(source.read_bytes())
    run(['docker','run','--rm','--cpus','8','--memory','4g','-v',f'{ROOT}:/work',
         '-v',f'{a.cache}:/root/.m2','-w','/work',a.image,'mvn','-o','-B','-ntp',
         '-Dheatnet.corrected.input=/work/target/scale-data/original.geojson','-Dheatnet.replay.seed=12345',
         'test','dependency:build-classpath','-Dmdep.outputFile=target/stress-classpath.txt'],'java')
    totals=dict(tests=0,failures=0,errors=0,skipped=0)
    for f in (ROOT/'target/surefire-reports').glob('TEST-*.xml'):
        xml=ET.parse(f).getroot()
        for key in totals:totals[key]+=int(xml.get(key,'0'))
    if totals['failures'] or totals['errors'] or totals['skipped']:raise RuntimeError(totals)
    (out/'java-summary.json').write_text(json.dumps(totals,indent=2)+'\n')
    run(['node','--test','src/test/js/large-input-ui.test.cjs'],'ui')
    tap=(out/'ui.log').read_text()
    ui={key:int(re.search(r'^# '+key+r' (\d+)$',tap,re.M).group(1)) for key in ['tests','pass','fail','skipped']}
    (out/'ui-summary.json').write_text(json.dumps(ui,indent=2)+'\n')
    run([py,'scripts/benchmark-scale.py','--label',a.label,'--snapshot'],'freeze')
    def bench(label,phase,file=None,counts=None):
        cmd=[py,'scripts/benchmark-scale.py','--label',label,'--phase',phase,'--repeat',str(a.repeat),'--image',a.image,'--maven-cache',a.cache]
        if file:cmd+=['--input',str(file)]
        if counts:cmd+=['--counts',*[str(n) for n in counts]]
        run(cmd,label+'-'+phase.lower()+'-'+(Path(file).stem if file else 'city'))
    for label in ([a.before_label] if a.before_label else [])+[a.label]:
        bench(label,'parse',counts=a.counts)
        bench(label,'parse',ROOT/'target/scale-data/large-simple.geojson')
        for mode in ['PLANAR','DEPTH']:bench(label,mode,original)
    for mode in ['PLANAR','DEPTH']:
        bench(a.label,mode,counts=a.counts)
        bench(a.label,mode,ROOT/'target/scale-data/large-simple.geojson')
    common=[py,'scripts/run-stress-suite.py','--skip-build','--docker-image',a.image,'--maven-cache',a.cache,
            '--repeat','2','--mode','BOTH']
    run(common+['--manifest','examples/stress/universal-manifest.json','--out',str(out/'universal')],'universal')
    run(common+['--scenario','competition-dense','--scenario','competition-mixed-restrictions',
                '--out',str(out/'mixed')],'mixed')
    if not a.skip_docker_smoke:run([py,'scripts/smoke-scale-docker.py','--out',str(out),'--tested-build',a.label],'docker-smoke')
    run([py,'scripts/summarize-scale.py','--acceptance',str(out),'--final-label',a.label,
         '--before-label',a.before_label or 'before',
         '--out',str(out/'verification.json')],'summarize')
    print('PASS: Java/UI, frozen benchmarks, byte-identical competition/city outputs, independent validation and deterministic stress results.',flush=True)
    print('Evidence:',out,flush=True)

if __name__=='__main__':main()
