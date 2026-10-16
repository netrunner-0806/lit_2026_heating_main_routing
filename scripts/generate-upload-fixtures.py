#!/usr/bin/env python3
"""Valid large GeoJSON for transport tests: original geometry plus bounded per-Feature text padding.
This tests bytes, not routing complexity. No source file is modified; no giant JSON object is built in RAM.
"""
import argparse, hashlib, json
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out',type=Path,default=ROOT/'target/upload-data')
    p.add_argument('--sizes',nargs='+',type=int,default=[100*1024**2,1024**3,int(2.8*1024**3)])
    a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    source=next((ROOT/'ТЗ').glob('Датасет скоррект*.geojson'))
    features=json.loads(source.read_text())['features']
    encoded=[json.dumps(f,ensure_ascii=False,separators=(',',':')).encode()[:-1]+b',"upload_padding":"' for f in features]
    prefix=b'{"type":"FeatureCollection","features":['; suffix=b']}\n'
    base=len(prefix)+len(suffix)+sum(len(f)+2 for f in encoded)+len(features)-1
    reports=[]; block=b'x'*65536
    for size in a.sizes:
        assert size>=base
        lengths=[(size-base)//len(features)+(i<(size-base)%len(features)) for i in range(len(features))]
        assert max(lengths)<31*1024**2,'Keep each Feature below the test JVM per-feature budget'
        path=a.out/f'valid-{size}.geojson'
        sha=hashlib.sha256()
        with path.open('wb') as out:
            def write(data):out.write(data);sha.update(data)
            write(prefix)
            for i,(feature,length) in enumerate(zip(encoded,lengths)):
                if i:write(b',')
                write(feature)
                while length:
                    n=min(length,len(block));write(block[:n]);length-=n
                write(b'"}')
            write(suffix)
        assert path.stat().st_size==size
        reports.append({'file':str(path.relative_to(ROOT)),'bytes':size,'sha256':sha.hexdigest(),'objects':len(features),
                        'provenance':'competition geometries unchanged; synthetic unused text padding, transport test only'})
        print(json.dumps(reports[-1]),flush=True)
    (a.out/'manifest.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2)+'\n')
if __name__=='__main__':main()
