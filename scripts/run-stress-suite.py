#!/usr/bin/env python3
"""Run generated fixtures in separate bounded JVMs; verify parsing, validation, witnesses and determinism.
Requires Maven/Java 11 locally, or --docker-image with a Maven/Java 11 image. No production services are used.
"""
import argparse, hashlib, json, os, subprocess, sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,help='optional unchanged corrected dataset for baseline comparison')
    p.add_argument('--generate',action='store_true')
    p.add_argument('--mode',choices=['PLANAR','DEPTH','BOTH'],default='BOTH')
    p.add_argument('--scenario',action='append',help='fixture filename without .geojson; repeatable')
    p.add_argument('--repeat',type=int,default=2)
    p.add_argument('--timeout',type=int,default=900,help='test-runner timeout per JVM, not a case/solver time limit')
    p.add_argument('--heap',default='2g')
    p.add_argument('--docker-image')
    p.add_argument('--maven-cache',default='lct-stress-m2')
    p.add_argument('--skip-build',action='store_true')
    p.add_argument('--manifest',type=Path,default=ROOT/'examples/stress/manifest.json')
    p.add_argument('--out',type=Path,default=ROOT/'target/stress-results')
    a=p.parse_args();a.out=a.out.resolve()
    if a.repeat<1 or a.timeout<1:p.error('positive repeat and timeout required')
    if a.generate:
        cmd=[sys.executable,str(ROOT/'scripts/generate-stress-datasets.py')]
        if a.source:cmd+=['--source',str(a.source.resolve())]
        subprocess.run(cmd,check=True,cwd=ROOT)
    fixtures=a.manifest.resolve().parent;manifest=json.loads(a.manifest.read_text())
    known={Path(s['file']).stem for s in manifest['scenarios']}
    unknown=set(a.scenario or [])-known
    if unknown:p.error('unknown scenarios: '+', '.join(sorted(unknown)))
    a.out.mkdir(parents=True,exist_ok=True)
    cpfile=ROOT/'target/stress-classpath.txt'
    def command(cmd, cidfile=None):
        if not a.docker_image:return cmd
        base=['docker','run','--rm','--cpus','8','--memory','4g','-v',f'{ROOT}:/work','-v',f'{a.maven_cache}:/root/.m2','-v',f'{a.out}:/results','-w','/work']
        if a.source:base+=['-v',f'{a.source.resolve()}:/source.geojson:ro']
        if cidfile:base+=['--cidfile',str(cidfile)]
        return base+[a.docker_image]+cmd
    if not a.skip_build:
        with (a.out/'build.log').open('w') as log:
            subprocess.run(command(['mvn','-B','-ntp','-Dtest=WitnessRoutesTest','test','dependency:build-classpath','-Dmdep.outputFile=target/stress-classpath.txt']),cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True)
    if not cpfile.exists():p.error('missing target/stress-classpath.txt; run without --skip-build')
    cp='target/classes:target/test-classes:'+cpfile.read_text().strip()
    scenes=[s for s in manifest['scenarios'] if not a.scenario or Path(s['file']).stem in a.scenario]
    if a.source and not a.scenario:
        if hashlib.sha256(a.source.read_bytes()).hexdigest()!=manifest['competition_sha256']:p.error('source hash differs from the frozen competition baseline')
        scenes.insert(0,{'file':'original.geojson','expected_connected':17,'original':True})
    modes=['PLANAR','DEPTH'] if a.mode=='BOTH' else [a.mode]
    rows=[];failures=[]
    for scene in scenes:
        input_path=a.source.resolve() if scene.get('original') else fixtures/scene['file']
        if scene.get('sha256') and hashlib.sha256(input_path.read_bytes()).hexdigest()!=scene['sha256']:
            p.error('fixture differs from its generated manifest: '+scene['file'])
        raw=json.loads(input_path.read_text());expected_features=len(raw['features'])
        expected_restrictions=sum(f['properties']['object_type']=='restriction' for f in raw['features'])
        for mode in modes:
            previous=None;previous_build=None
            for repeat in range(a.repeat):
                name=f"{Path(scene['file']).stem}-{mode.lower()}-{repeat+1}.geojson";output=a.out/name
                inp='/source.geojson' if scene.get('original') and a.docker_image else str(input_path)
                if a.docker_image and not scene.get('original'):inp='/work/'+str(input_path.relative_to(ROOT))
                out='/results/'+name if a.docker_image else str(output)
                cidfile=output.with_suffix('.cid') if a.docker_image else None
                if cidfile and cidfile.exists():p.error(f'stale container marker {cidfile}; inspect its container before repeating')
                cmd=command(['java',f'-Xmx{a.heap}','-XX:+ExitOnOutOfMemoryError','-cp',cp,'ru.lct.heatnet.stress.StressMain',inp,mode,out],cidfile)
                try:
                    with output.with_suffix('.log').open('w') as log:subprocess.run(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,timeout=a.timeout,check=True)
                    report=json.loads(Path(str(output)+'.report.json').read_text())
                except (subprocess.SubprocessError,FileNotFoundError) as ex:
                    if cidfile and cidfile.exists():
                        subprocess.run(['docker','stop','--time','5',cidfile.read_text().strip()],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
                    report={'input':scene['file'],'mode':mode,'status':'HARNESS_FAILURE','exception':str(ex)}
                finally:
                    if cidfile and cidfile.exists():cidfile.unlink()
                row={'run':repeat+1,**report};rows.append(row)
                errors=[]
                if report.get('status')!='DONE':errors.append('calculation did not finish DONE; inspect exact diagnostics')
                else:
                    if report.get('features')!=expected_features or report.get('restrictions')!=expected_restrictions:errors.append('input objects lost')
                    if not report.get('valid') or report.get('errors') or report.get('warnings'):errors.append('output validation failed')
                    if any(d['severity']=='ERROR' for d in report.get('diagnostics',[])):errors.append('solver/internal error')
                    for variant in report.get('variants',[]):
                        if 'expected_connected' in scene and variant['connected']!=scene['expected_connected']:errors.append('unexpected connected count')
                    if previous and previous!=report.get('output_sha256'):errors.append('non-deterministic output')
                    if previous_build and previous_build!=report.get('build_sha256'):errors.append('build changed between repetitions')
                    previous=report.get('output_sha256')
                    previous_build=report.get('build_sha256')
                    if scene.get('original'):
                        expected=hashlib.sha256((fixtures/'baseline'/f'{mode.lower()}.geojson').read_bytes()).hexdigest()
                        if previous!=expected:errors.append('competition baseline changed')
                if errors:failures.append({'file':name,'errors':errors})
                print(name,report.get('status'),'connected',[v['connected'] for v in report.get('variants',[])],'ms',report.get('wall_ms'),'errors',errors,flush=True)
                (a.out/'summary.json').write_text(json.dumps({'runs':rows,'failures':failures},ensure_ascii=False,indent=2)+'\n')
    return 1 if failures else 0

if __name__=='__main__':sys.exit(main())
