#!/usr/bin/env python3
"""Build a TEST-ONLY control with identical accounting but without forbidden-interior certificates.
This distinguishes real geometry work reduction from moving the visibility-budget counter.
Only a copied frozen build is changed; production source and target/classes are never edited.
"""
import argparse, hashlib, json, shutil, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--base',default='finalization-harness');p.add_argument('--label',default='finalization-accounting')
    a=p.parse_args();base=ROOT/'target/scale-builds'/a.base;dest=ROOT/'target/scale-builds'/a.label
    assert a.label.replace('-','').isalnum() and not dest.exists()
    java=ROOT/'src/main/java/ru/lct/heatnet/routing/NavGraph.java'
    cls=Path('ru/lct/heatnet/routing/NavGraph.class')
    assert (base/'classes'/cls).read_bytes()==(ROOT/'target/classes'/cls).read_bytes(),'Compile the current source before creating control'
    shutil.copytree(base,dest)
    source=java.read_text()
    for expression in ['space.definitelyBlocked(ci, cj, Exemptions.NONE)','space.definitelyBlocked(c, cj, merged)']:
        assert source.count(expression)==1;source=source.replace(expression,'false /* test-only control */')
    test_source=dest/'ablation-source/NavGraph.java';test_source.parent.mkdir();test_source.write_text(source)
    cp='/work/'+str((dest/'classes').relative_to(ROOT))+':'+(base/'classpath.txt').read_text().strip()
    command=['docker','run','--rm','--cpus','8','--memory','1g','-v',str(ROOT)+':/work','-v','lct-robustness-m2:/root/.m2:ro',
             '-w','/work','lct-robustness-builder:baseline','javac','-source','11','-target','11','-encoding','UTF-8','-cp',cp,
             '-d','/work/'+str((dest/'classes').relative_to(ROOT)),'/work/'+str(test_source.relative_to(ROOT))]
    subprocess.run(command,check=True)
    sha=hashlib.sha256()
    for f in sorted((dest/'classes').rglob('*.class')):sha.update(str(f.relative_to(dest/'classes')).encode());sha.update(f.read_bytes())
    (dest/'sha256.txt').write_text(sha.hexdigest())
    (dest/'ablation.json').write_text(json.dumps({'base':a.base,'base_sha256':(base/'sha256.txt').read_text(),
        'control_sha256':sha.hexdigest(),'difference':'forbidden-interior certificates disabled; same 50M full-check accounting, radii, ordering, weights and all graph limits',
        'command':command},indent=2)+'\n');print('CONTROL FROZEN',a.label,sha.hexdigest())
if __name__=='__main__':main()
