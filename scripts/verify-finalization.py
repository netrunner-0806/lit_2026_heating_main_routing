#!/usr/bin/env python3
"""Sequential, bounded final acceptance. No Git, installs, server access, or production Compose mutations.
Keep a frozen --before build for comparisons. Large 500-consumer full solves are correctness probes;
their graph construction is benchmarked repeatedly, smaller full solves use warm-up + four warm runs.
"""
import argparse, hashlib, json, shutil, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--label',default='finalization-final')
    p.add_argument('--before',default='finalization-profile')
    p.add_argument('--benchmark-label',help='reuse measured solver build after a proven API-only rebuild')
    p.add_argument('--graph-build',help='frozen build with graph SHA test harness; defaults to --label')
    p.add_argument('--control',default='finalization-accounting',help='test-only accounting control label')
    p.add_argument('--phase',choices=['tests','bench','ablation','stress','http','browser','stop','report','acceptance','all'],default='all')
    p.add_argument('--generate',action='store_true',help='generate dense fixtures; requires the existing Shapely/pyproj environment')
    a=p.parse_args();out=ROOT/'target/final-engineering'/a.label;out.mkdir(parents=True,exist_ok=True)
    measured=a.benchmark_label or a.label
    if not a.label.replace('-','').replace('_','').isalnum():p.error('invalid label')
    def run(cmd,log=None):
        print('RUN',*cmd,flush=True)
        if log:
            with (out/log).open('w') as file:subprocess.run(cmd,cwd=ROOT,stdout=file,stderr=subprocess.STDOUT,check=True)
        else:subprocess.run(cmd,cwd=ROOT,check=True)
    def bench(label,scene,phase,repeats=4,timeout=900,allow_complexity=False):
        meta=ROOT/'target/scale-results'/label/(scene.stem+'-'+phase.lower()+'.meta.json')
        def controlled_limit(row):
            assert allow_complexity and row['status']=='COMPLEXITY_LIMIT'
            assert row['build_sha256']==(ROOT/'target/scale-builds'/label/'sha256.txt').read_text().strip()
            details=json.loads((ROOT/(row['report']+'.0.geojson.report.json')).read_text())
            assert details['input_sha256']==hashlib.sha256(scene.read_bytes()).hexdigest(),'negative probe dataset changed'
            errors={d['code'] for d in details['diagnostics'] if d['severity']=='ERROR'}
            assert 'COMPLEXITY_LIMIT' in errors and errors<= {'COMPLEXITY_LIMIT','NO_VALID_VARIANT'},errors
            print('RECORDED NEGATIVE RESOURCE PROBE (not a successful solve)',meta.relative_to(ROOT),flush=True)
        if meta.exists():
            row=json.loads(meta.read_text())
            if allow_complexity and row['status']=='COMPLEXITY_LIMIT':controlled_limit(row);return
            if row['status']!='OK':raise RuntimeError('Existing unsuccessful measurement: '+str(meta))
            assert row['build_sha256']==(ROOT/'target/scale-builds'/label/'sha256.txt').read_text().strip()
            assert row['requested_warm_repeats']>=repeats
            h=hashlib.sha256()
            with scene.open('rb') as f:
                for block in iter(lambda:f.read(65536),b''):h.update(block)
            rows=json.loads((ROOT/row['report']).read_text())
            if phase in ['PLANAR','DEPTH']:assert all(r['input_sha256']==h.hexdigest() for r in rows),'dataset changed'
            print('KEEP VERIFIED',meta.relative_to(ROOT),flush=True);return
        try:
            run([sys.executable,'scripts/benchmark-scale.py','--label',label,'--phase',phase,'--input',str(scene),
                 '--repeat',str(repeats),'--timeout',str(timeout)])
        except subprocess.CalledProcessError:
            if not allow_complexity:raise
            controlled_limit(json.loads(meta.read_text()))
    if a.generate:
        run([sys.executable,'scripts/generate-scale-datasets.py','--only-consumers','--consumer-counts','17','50','100','250','500'])
        source=next((ROOT/'ТЗ').glob('Датасет скоррект*.geojson'))
        destination=ROOT/'target/scale-data/original.geojson'
        if destination.exists():assert source.read_bytes()==destination.read_bytes(),'competition input differs'
        else:shutil.copyfile(source,destination)
    if a.phase in ['tests','all']:
        run(['docker','run','--rm','--cpus','8','--memory','4g','-v',str(ROOT)+':/work','-v','lct-robustness-m2:/root/.m2',
             '-w','/work','lct-robustness-builder:baseline','mvn','-o','-B','-ntp',
             '-Dheatnet.corrected.input=/work/target/scale-data/original.geojson','-Dheatnet.replay.seed=12345','test',
             'dependency:build-classpath','-Dmdep.outputFile=target/stress-classpath.txt'],'java-tests.log')
        run(['node','--test']+[str(f.relative_to(ROOT)) for f in sorted((ROOT/'src/test/js').glob('*.test.cjs'))],'ui-tests.log')
        run([sys.executable,'scripts/benchmark-scale.py','--label',a.label,'--snapshot'])
    if a.phase in ['bench','all','acceptance']:
        # Before/after calculations run sequentially with identical quotas and heap. Never overlap measurements.
        for mode in ['PLANAR','DEPTH']:
            for label in [a.before,measured]:bench(label,ROOT/'target/scale-data/original.geojson',mode)
        for count in [50,100,250]:
            for label in [a.before,measured]:
                bench(label,ROOT/f'target/scale-data/consumers-{count}.geojson','PLANAR',4 if count<250 else 2,2400,allow_complexity=count==250)
        bench(measured,ROOT/'target/scale-data/consumers-500.geojson','context',4,300)
        bench(measured,ROOT/'target/scale-data/consumers-500.geojson','PLANAR',0,3600,allow_complexity=True)
        for scene in ['original','consumers-50','consumers-100','consumers-250']:
            for mode in (['planar','depth'] if scene=='original' else ['planar']):
                old=json.loads((ROOT/f'target/scale-results/{a.before}/{scene}-{mode}.json').read_text())
                new=json.loads((ROOT/f'target/scale-results/{measured}/{scene}-{mode}.json').read_text())
                if all(r['status']=='OK' for r in old+new):
                    assert {r['output_sha256'] for r in old}=={r['output_sha256'] for r in new},(scene,mode,'output changed')
    if a.phase in ['stress','all','acceptance']:
        run([sys.executable,'scripts/run-stress-suite.py','--skip-build','--docker-image','lct-robustness-builder:baseline',
             '--maven-cache','lct-robustness-m2','--manifest','examples/stress/universal-manifest.json','--mode','BOTH',
             '--repeat','2','--out',str(out/'stress-universal')],'stress-universal.log')
        run([sys.executable,'scripts/run-stress-suite.py','--skip-build','--docker-image','lct-robustness-builder:baseline',
             '--maven-cache','lct-robustness-m2','--scenario','competition-dense','--scenario','competition-mixed-restrictions',
             '--mode','BOTH','--repeat','2','--out',str(out/'stress-mixed')],'stress-mixed.log')
    if a.phase in ['ablation','all','acceptance']:
        graph=a.graph_build or a.label
        assert (ROOT/'target/scale-builds'/graph/'sha256.txt').read_text()==(ROOT/'target/scale-builds'/measured/'sha256.txt').read_text()
        if not (ROOT/'target/scale-builds'/a.control).exists():
            run([sys.executable,'scripts/prepare-visibility-ablation.py','--base',graph,'--label',a.control])
        bench(a.control,ROOT/'target/scale-data/consumers-500.geojson','context',4,900)
        bench(graph,ROOT/'target/scale-data/consumers-500.geojson','context',4,300)
    if a.phase in ['http','all','acceptance']:
        if not (ROOT/'target/upload-data/manifest.json').exists():run([sys.executable,'scripts/generate-upload-fixtures.py'])
        run([sys.executable,'scripts/verify-resumable-http.py','prepare','--tested-build',a.label])
        run([sys.executable,'scripts/verify-resumable-http.py','verify'])
    if a.phase in ['browser','all','acceptance']:
        run(['node','scripts/verify-resumable-browser.cjs'])
    if a.phase in ['stop','all','acceptance']:
        run([sys.executable,'scripts/verify-resumable-http.py','stop'])
    if a.phase in ['report','all','acceptance']:
        run([sys.executable,'scripts/summarize-finalization.py','--label',measured,'--runtime-label',a.label,'--before',a.before,
             '--graph-build',a.graph_build or a.label,'--control',a.control])
    print('PHASE COMPLETE',a.phase,flush=True)
if __name__=='__main__':main()
