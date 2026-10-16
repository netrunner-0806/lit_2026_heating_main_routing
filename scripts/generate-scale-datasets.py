#!/usr/bin/env python3
"""Streaming, deterministic city-scale fixtures derived from frozen OSM road shapes.

Generated objects are SYNTHETIC translations of OSM shapes, not real mapped locations.
Large files stay in target/scale-data, never replace the original competition input.
"""
import argparse, hashlib, json, math
from pathlib import Path
from pyproj import Transformer
from shapely.geometry import shape, mapping
from shapely.ops import transform

ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for b in iter(lambda: f.read(1024 * 1024), b''): h.update(b)
    return h.hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--counts', nargs='+', type=int, default=[10000, 100000, 500000, 1000000])
    p.add_argument('--out', type=Path, default=ROOT/'target/scale-data')
    p.add_argument('--source', type=Path)
    p.add_argument('--only-consumers',action='store_true')
    p.add_argument('--only-large-simple',action='store_true',help='same 144 geometries, 20 MiB unused text per Feature (~2.81 GiB total)')
    p.add_argument('--consumer-counts',nargs='+',type=int,default=[17,50,100,500])
    a = p.parse_args(); a.out.mkdir(parents=True, exist_ok=True)
    source = a.source or next((ROOT/'ТЗ').glob('Датасет скоррект*.geojson'))
    original = json.loads(source.read_text())['features']
    if a.only_large_simple:
        file=a.out/'large-simple.geojson';chunk='x'*(1<<20)
        with file.open('w') as out:
            out.write('{"type":"FeatureCollection","features":[')
            for i,f in enumerate(original):
                if i:out.write(',\n')
                text=json.dumps(f,ensure_ascii=False,separators=(',',':'))
                out.write(text[:-1]+',"unused_description":"')
                for _ in range(20):out.write(chunk)
                out.write('"}')
            out.write(']}\n')
        row={'file':file.name,'objects':len(original),'bytes':file.stat().st_size,'sha256':digest(file),
             'source_sha256':digest(source),'purpose':'Byte-size control: unchanged geometries, unused text; NOT a complex city geometry fixture.'}
        (a.out/'large-simple-manifest.json').write_text(json.dumps(row,indent=2)+'\n');print(json.dumps(row));return
    osm = json.loads((ROOT/'examples/stress/competition-osm-roads.geojson').read_text())['features'][len(original):]
    forward = Transformer.from_crs(4326, 32637, always_xy=True).transform
    inverse = Transformer.from_crs(32637, 4326, always_xy=True).transform
    templates = []
    for f in osm:
        g = transform(forward, shape(f['geometry']))
        if g.geom_type != 'Polygon' or not 12 <= len(g.exterior.coords) <= 80: continue
        cx,cy = g.centroid.x,g.centroid.y
        # Retain the road's meaningful turns; scale to fit a synthetic 60 m city block.
        factor = min(1., 60/max(g.bounds[2]-g.bounds[0], g.bounds[3]-g.bounds[1]))
        rings = [[((x-cx)*factor,(y-cy)*factor) for x,y in ring.coords]
                 for ring in [g.exterior,*g.interiors]]
        templates.append((str(f['properties']['id']),rings))
    assert templates
    manifest = {'source_sha256':digest(source), 'seed':20261016,
                'provenance':'Synthetic translations/scaling of frozen OSM road polygons; not real city positions.',
                'osm_source':'examples/stress/osm/provenance.json', 'scenarios':[]}
    for count in ([] if a.only_consumers else a.counts):
        assert count >= len(original)
        file = a.out/f'city-{count}.geojson'; coordinates=0
        with file.open('w') as out:
            out.write('{"type":"FeatureCollection","features":[')
            for i in range(count):
                if i: out.write(',\n')
                if i < len(original): f=original[i]
                else:
                    n=i-len(original); template,rings=templates[n%len(templates)]
                    # A reproducible 80 m grid starts >3 km east of the competition territory.
                    # It expands beyond every fixed local search radius as object count grows.
                    x0=419000+(n%1000)*80; y0=6170000+(n//1000)*80
                    wgs=[]
                    for ring in rings:
                        xs,ys=inverse([x0+x for x,y in ring],[y0+y for x,y in ring])
                        wgs.append([[round(x,11),round(y,11)] for x,y in zip(xs,ys)])
                        coordinates+=len(ring)
                    f={'type':'Feature','properties':{'id':f'city-{n}','object_type':'restriction',
                       'restriction_type':['road','park','water','prohibited_site'][n%4],
                       'synthetic':True,'template_osm_id':template},
                       'geometry':{'type':'Polygon','coordinates':wgs}}
                out.write(json.dumps(f,ensure_ascii=False,separators=(',',':')))
            out.write(']}\n')
        row={'file':file.name,'objects':count,'added_coordinates':coordinates,'bytes':file.stat().st_size,'sha256':digest(file)}
        manifest['scenarios'].append(row);print(json.dumps(row),flush=True)
        (a.out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    assert digest(source)==manifest['source_sha256']
    consumers=[]
    for count in a.consumer_counts:
        features=[]
        def feature(ident,kind,geometry,**props):
            features.append({'type':'Feature','properties':{'id':ident,'object_type':kind,**props},
                             'geometry':mapping(transform(inverse,geometry))})
        from shapely.geometry import Point,LineString,box
        ox,oy=414000,6173000
        feature('source','source',Point(ox,oy))
        feature('network','heat_network',LineString([(ox,oy-100),(ox,oy+400)]),diameter=300)
        for y in [0,150,300]:feature('chamber-'+str(y),'heat_chamber',Point(ox,oy+y))
        for i in range(count):
            x=ox+100+(i%25)*20;y=oy-50+(i//25)*20
            feature('cp-'+str(i),'oks_connection_point',Point(x,y),flow_tph=1)
            feature('oks-'+str(i),'restriction',box(x-2,y-2,x+2,y+2),restriction_type='oks')
        feature('water','restriction',box(ox+168,oy+20,ox+172,oy+120),restriction_type='water')
        feature('road','restriction',box(ox+230,oy-80,ox+236,oy+400),restriction_type='road')
        file=a.out/f'consumers-{count}.geojson'
        file.write_text(json.dumps({'type':'FeatureCollection','features':features},separators=(',',':'))+'\n')
        consumers.append({'file':file.name,'consumers':count,'objects':len(features),'sha256':digest(file)})
    (a.out/'consumer-manifest.json').write_text(json.dumps({'seed':20261016,'synthetic':True,'scenarios':consumers},indent=2)+'\n')

if __name__ == '__main__': main()
