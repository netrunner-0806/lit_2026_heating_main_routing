#!/usr/bin/env python3
"""One-command regeneration + Java/UI + deterministic GeoJSON verification. No production services or Git.
Example: python3 scripts/verify-universal.py --docker-image maven:3.9.6-eclipse-temurin-11
Python dependencies: shapely, pyproj. Node is needed for the existing UI tests.
"""
import argparse, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path)
    p.add_argument('--docker-image')
    p.add_argument('--maven-cache',default='lct-stress-m2')
    p.add_argument('--offline',action='store_true',help='use already cached Maven dependencies')
    p.add_argument('--repeat',type=int,default=2)
    p.add_argument('--out',type=Path,default=ROOT/'target/universal-verification')
    a=p.parse_args()
    source=(a.source or next((ROOT/'ТЗ').glob('Датасет скоррект*.geojson'))).resolve()
    a.out=a.out.resolve();a.out.mkdir(parents=True,exist_ok=True)
    def run(cmd,log=None):
        print('RUN',cmd[0],cmd[1],flush=True)
        if log:
            with (a.out/log).open('w') as out:subprocess.run(cmd,cwd=ROOT,stdout=out,stderr=subprocess.STDOUT,check=True)
        else:subprocess.run(cmd,cwd=ROOT,check=True)
    # Legacy scenes are not overwritten: this regenerates the new separate manifest from the frozen OSM snapshot.
    run([sys.executable,'scripts/generate-universal-datasets.py','--source',str(source)])
    run([sys.executable,'scripts/analyze-road-conflicts.py'])
    mvn=['mvn','-B','-ntp']+(['-o'] if a.offline else [])
    mvn += ['-Dheatnet.corrected.input='+('/source.geojson' if a.docker_image else str(source)),
            '-Dheatnet.replay.seed=12345','test','dependency:build-classpath','-Dmdep.outputFile=target/stress-classpath.txt']
    if a.docker_image:
        mvn=['docker','run','--rm','--cpus','8','--memory','4g','-v',f'{ROOT}:/work','-v',f'{source}:/source.geojson:ro',
             '-v',f'{a.maven_cache}:/root/.m2','-w','/work',a.docker_image]+mvn
    run(mvn,'java.log');run(['node','--test','src/test/js/large-input-ui.test.cjs'],'ui.log')
    common=[sys.executable,'scripts/run-stress-suite.py','--skip-build','--repeat',str(a.repeat)]
    if a.docker_image:common+=['--docker-image',a.docker_image,'--maven-cache',a.maven_cache]
    run(common+['--manifest','examples/stress/universal-manifest.json','--source',str(source),'--out',str(a.out/'universal')])
    run(common+['--out',str(a.out/'legacy')])
    print('VERIFIED: Java/UI, original baseline, universal + legacy fixtures in PLANAR/DEPTH; reports:',a.out)

if __name__=='__main__':main()
