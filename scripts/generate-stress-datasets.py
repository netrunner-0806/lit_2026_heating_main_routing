#!/usr/bin/env python3
"""Deterministic synthetic stress inputs, EPSG:4326. Requires pyproj and shapely.
No network request, no edits to the competition source; source SHA is recorded.
"""
import argparse, hashlib, json, math, random
import pyproj, shapely
from pathlib import Path
from pyproj import Transformer
from shapely.geometry import Polygon, MultiPolygon, LineString, Point, mapping, shape
from shapely.ops import transform

ROOT = Path(__file__).resolve().parents[1]
TO_M = Transformer.from_crs(4326, 32637, always_xy=True).transform
TO_W = Transformer.from_crs(32637, 4326, always_xy=True).transform

def encode(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False)+'\n').encode()

def rounded(value):
    if isinstance(value, float): return round(value, 11)
    if isinstance(value, (tuple, list)): return [rounded(v) for v in value]
    if isinstance(value, dict): return {k:rounded(v) for k,v in value.items()}
    return value

def feature(id_, object_type, geom, **props):
    assert geom.is_valid and not geom.is_empty, (id_, geom.wkt[:150])
    return {'type':'Feature','properties':{'id':id_, 'object_type':object_type,**props},
            'geometry':rounded(mapping(transform(TO_W, geom)))}

def rect(x,y,w,h): return Polygon([(x,y),(x+w,y),(x+w,y+h),(x,y+h)])

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path)
    parser.add_argument('--out',type=Path,default=ROOT/'examples/stress')
    parser.add_argument('--seed',type=int,default=20261016)
    parser.add_argument('--density',type=float,default=1)
    parser.add_argument('--restrictions',type=int,default=40,help='number of road ribbons at density=1')
    args=parser.parse_args()
    if args.source is None:
        candidates=list((ROOT.parent/'lct-no-yan-materials/ТЗ').glob('Датасет скорректир*.geojson'))
        if not candidates: parser.error('--source must name the corrected competition dataset')
        args.source=candidates[0]
    if args.density<=0 or args.restrictions<1:parser.error('positive density and restrictions required')
    raw=args.source.read_bytes();base=json.loads(raw)
    assert any(f['properties']['object_type']=='oks_connection_point' and 'flow_tph' in f['properties'] for f in base['features']), 'Use the corrected dataset (flow_tph on connection points)'
    args.out.mkdir(parents=True,exist_ok=True)
    rng=random.Random(args.seed)
    geoms=[transform(TO_M,shape(f['geometry'])) for f in base['features']]
    x0=min(g.bounds[0] for g in geoms);y0=min(g.bounds[1] for g in geoms)
    x1=max(g.bounds[2] for g in geoms);y1=max(g.bounds[3] for g in geoms)
    w=x1-x0;h=y1-y0
    manifest={'seed':args.seed,'density':args.density,'restrictions':args.restrictions,
      'competition_sha256':hashlib.sha256(raw).hexdigest(),'crs':'EPSG:4326 / CRS84; generation EPSG:32637',
      'road_source':'SYNTHETIC. Overpass request on 2026-10-16 returned HTTP 406. Not the organizers dataset.',
      'generator_versions':{'shapely':shapely.__version__,'pyproj':pyproj.__version__,'PROJ':pyproj.proj_version_str},
      'road_width':'Assumed nominal full width 4–7 m, passing bays add up to 2.6 m; not measured.',
      'scenarios':[]}
    def save(name, additions, description, initial=None, expected=None, witness=None):
        fs=list(base['features'] if initial is None else initial)+additions
        ids=[str(f['properties']['id']) for f in fs];assert len(ids)==len(set(ids)),name
        for f in fs:assert shape(f['geometry']).is_valid, (name,f['properties']['id'])
        doc={'type':'FeatureCollection','features':fs};b=encode(doc);(args.out/name).write_bytes(b)
        entry={'file':name,'description':description,'features':len(fs),'added_features':len(additions),
               'added_layer_bytes':len(encode({'type':'FeatureCollection','features':additions})),
               'bytes':len(b),'sha256':hashlib.sha256(b).hexdigest(),
               'restrictions':sum(f['properties']['object_type']=='restriction' for f in fs)}
        if expected is not None:entry['expected_connected']=expected
        if witness is not None:
            path=name.replace('.geojson','.witness.json');(args.out/path).write_bytes(encode(witness));entry['witness']=path
        manifest['scenarios'].append(entry)
    def restriction(id_,kind,g):return feature('stress-'+id_,'restriction',g,restriction_type=kind)
    # Every road vertex changes the curve or bay width; no repeated coordinates or size padding.
    roads=[];n=max(1,round(args.restrictions*args.density));samples=240
    for i in range(n):
        horizontal=i%2==0;length=w*.84 if horizontal else h*.84
        cross=(h if horizontal else w)*(.07+.86*(i//2+.5)/math.ceil(n/2))
        phase=rng.uniform(0,2*math.pi);amplitude=rng.uniform(3,13);full_width=rng.uniform(4,7)
        left=[];right=[]
        for j in range(samples+1):
            t=j/samples;along=length*t
            offset=amplitude*math.sin(t*math.pi*4+phase)
            bay=1.3*max(0,math.sin(t*math.pi*14+phase))**4
            center=(x0+w*.08+along,y0+cross+offset) if horizontal else (x0+cross+offset,y0+h*.08+along)
            half=full_width/2+bay
            left.append((center[0],center[1]+half) if horizontal else (center[0]+half,center[1]))
            right.append((center[0],center[1]-half) if horizontal else (center[0]-half,center[1]))
        roads.append(restriction('road-'+str(i),'road',Polygon(left+right[::-1])))
    save('competition-with-roads.geojson',roads,'Synthetic intra-block road ribbons, crossings and overlaps; nominal ~600 KB layer.')
    waters=[restriction('water-1','water',rect(x0+w*.47,y0+h*.38,70,125)),
      restriction('water-2','water',MultiPolygon([Point(x0+w*.28,y0+h*.63).buffer(24,resolution=20),Point(x0+w*.32,y0+h*.65).buffer(18,resolution=20)])),
      restriction('water-3','water',Polygon([(x0+w*.65,y0+h*.65),(x0+w*.73,y0+h*.65),(x0+w*.73,y0+h*.69),(x0+w*.68,y0+h*.69),(x0+w*.68,y0+h*.76),(x0+w*.65,y0+h*.76)]))]
    save('competition-with-water.geojson',waters,'Polygon/MultiPolygon and concave ponds with alternative bypasses.')
    kinds=['road','tram_tracks','railway','water','gas_pipeline','power_cable','oks','park','social_area','prohibited_site']
    mixed=[]
    for i,k in enumerate(kinds):
        x=x0+w*(.18+.14*(i%5));y=y0+h*(.3+.3*(i//5))
        g=LineString([(x,y),(x+65,y+15)]) if k in ('gas_pipeline','power_cable') else rect(x,y,45,5 if k in ('road','tram_tracks','railway') else 22)
        mixed.append(restriction('mixed-'+str(i),k,g))
    save('competition-mixed-restrictions.geojson',mixed,'All ten supported restriction types with their original crossing semantics.')
    dense=[]
    for i in range(max(1,round(120*args.density))):
        x=x0+w*(.08+.84*rng.random());y=y0+h*(.08+.84*rng.random())
        g=Point(x,y).buffer(rng.uniform(3,10),resolution=5+i%7)
        dense.append(restriction('dense-'+str(i),['park','water','social_area'][i%3],g))
    # A 3 m gap is geometrically passable for a small pair (check distances, not file size).
    dense += [restriction('corridor-left','water',rect(x0+w*.52,y0+h*.2,20,100)),restriction('corridor-right','water',rect(x0+w*.52+23,y0+h*.2,20,100))]
    save('competition-dense.geojson',dense,'Dense curved obstacles, near neighbours and a narrow corridor.')
    # Small metric scenes are independent of the competition topology; paths stored explicitly as witnesses.
    ox,oy=400000.,6170000.
    def local(g):return transform(lambda x,y,z=None:(x+ox,y+oy),g)
    def rf(id_,k,g):return restriction(id_,k,local(g))
    def basic(cp,net=(-10,0,10,0),chamber=False):
        fs=[feature('source','source',local(Point(net[0],net[1]))),
            feature('network','heat_network',local(LineString([(net[0],net[1]),(net[2],net[3])])),diameter=300),
            feature('consumer','oks_connection_point',local(Point(*cp)),flow_tph=4)]
        if chamber:fs.append(feature('chamber','heat_chamber',local(Point(0,0))))
        return fs
    def witness(points):return {'consumer':'consumer','network':'network','chamber':None,'coordinates':rounded([TO_W(ox+x,oy+y) for x,y in points]),'expected_connected':1}
    save('routing-fallback.geojson',[], 'Open space beyond terminal radius; no obstacles near consumer.',basic((0,1100)),1,witness([(0,1100),(0,0)]))
    save('routing-fallback-detour.geojson',[rf('detour','water',rect(-100,400,200,200))], 'Long detour with no graph vertices around remote consumer.',basic((0,1800)),1,witness([(0,1800),(-150,1800),(-150,250),(0,0)]))
    # Final exit at the far side is valid; nearest 40 samples all face the blocking water.
    save('routing-fallback-oks.geojson',[rf('own','oks',rect(-150,60,300,100)),rf('blocked-exit','water',rect(-250,42,500,14))],
      'Nearest OKS exits blocked, far boundary has a validated exit.',basic((0,65)),1,witness([(0,65),(0,185),(280,185),(280,0),(10,0)]))
    road_ring=Polygon([(-30,60),(30,60),(30,120),(-30,120)],holes=[[(-12,78),(-12,102),(12,102),(12,78)]])
    save('routing-fallback-road-exit.geojson',[rf('own-road-exit','oks',rect(-10,80,20,20)),rf('road-ring','road',road_ring)],
      'The first free point is inside a road; extend the same straight approach beyond the special section, not a turn inside road.',
      basic((0,90)),1,witness([(0,90),(0,0)]))
    ring=Polygon([(-80,-80),(80,-80),(80,80),(-80,80)],holes=[[(-20,-20),(-20,20),(20,20),(20,-20)]])
    save('routing-impossible.geojson',[rf('water-ring','water',ring)],'Consumer enclosed by water ring; no legal crossing.',basic((0,0),(150,0,170,0)),0)
    save('routing-impossible-exit.geojson',[rf('own','oks',rect(-10,80,20,20)),rf('enclosure','railway',Polygon([(-20,70),(20,70),(20,110),(-20,110)],holes=[[(-12,78),(-12,102),(12,102),(12,78)]]))],
      'Own OKS enclosed by forbidden railway; exact geometric impossibility.',basic((0,90)),0)
    edge=[rf('road-inside','road',rect(-25,-5,50,10)),rf('tram','tram_tracks',rect(-25,50,50,6)),
      rf('multi','water',MultiPolygon([rect(70,20,10,20),rect(85,20,10,20)])),
      rf('hole','park',Polygon([(100,70),(130,70),(130,100),(100,100)],holes=[[(105,75),(105,95),(125,95),(125,75)]])),
      rf('concave','social_area',Polygon([(-100,50),(-60,50),(-60,60),(-90,60),(-90,90),(-100,90)])),
      rf('touch1','prohibited_site',rect(60,110,10,10)),rf('touch2','prohibited_site',rect(70,110,10,10)),
      rf('near','park',rect(80.00000001,110,10,10)),
      rf('short','gas_pipeline',LineString([(-25,80),(-24.999999,80),(25,80)])),
      rf('parallel','power_cable',LineString([(-25,80.8),(25,80.800001)])),
      rf('vertices','water',Point(-130,0).buffer(20,resolution=180)),rf('own-corner','oks',rect(-10,120,20,20))]
    # Exactly 1 m from each of two sides: either admissible equal-distance boundary exit may be chosen.
    wit=witness([(9,121),(0,0)]);wit['chamber']='chamber'
    save('geometry-edge-cases.geojson',edge,'Holes, MultiPolygon, touching and almost touching rings, tiny segments, complex boundaries, camera in road, OKS corner.',basic((9,121),chamber=True),1,wit)
    (args.out/'manifest.json').write_bytes(encode(manifest))
    for e in manifest['scenarios']:print(e['file'],e['bytes'],'bytes;',e['added_features'],'added;',e['added_layer_bytes'],'layer bytes')
    assert hashlib.sha256(args.source.read_bytes()).hexdigest()==manifest['competition_sha256']
if __name__=='__main__':main()
