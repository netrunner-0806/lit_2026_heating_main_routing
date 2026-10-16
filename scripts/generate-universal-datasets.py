#!/usr/bin/env python3
"""Offline, deterministic OSM/synthetic geometry regressions. Requires pyproj and shapely.
Uses the checked-in, attributed OSM snapshot; never modifies the competition input.
"""
import argparse, gzip, hashlib, importlib.util, json, re, xml.etree.ElementTree as ET
from pathlib import Path
from shapely.geometry import Point, LineString, Polygon, MultiPolygon, shape, box
from shapely.ops import transform, polygonize_full, unary_union

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('stressgen', ROOT/'scripts/generate-stress-datasets.py')
g = importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
DRIVABLE = {'residential','service','living_street','unclassified','tertiary','secondary','primary','trunk','motorway'}
WIDTH = {'residential':6.,'service':4.,'living_street':4.,'unclassified':6.,'tertiary':7.,'secondary':9.,'primary':12.,'trunk':12.,'motorway':12.}

def relation_polygon(path, relation_id):
    doc = ET.fromstring(gzip.decompress(path.read_bytes()))
    nodes = {x.attrib['id']:(float(x.attrib['lon']),float(x.attrib['lat'])) for x in doc.findall('node')}
    ways = {x.attrib['id']:LineString([nodes[n.attrib['ref']] for n in x.findall('nd')]) for x in doc.findall('way')}
    rel = next(x for x in doc.findall('relation') if x.attrib['id']==str(relation_id))
    assert all(m.attrib.get('role') in ('outer','inner') for m in rel.findall('member') if m.attrib['type']=='way'), f'Unsupported polygon member role in {relation_id}'
    rings = {}
    for role in ('outer','inner'):
        parts = [ways[m.attrib['ref']] for m in rel.findall('member') if m.attrib['type']=='way' and m.attrib.get('role','outer')==role]
        if parts:
            polygons,cuts,dangles,invalid=polygonize_full(parts)
            assert cuts.is_empty and dangles.is_empty and invalid.is_empty, f'Incomplete polygon linework in {relation_id}/{role}'
            rings[role]=unary_union(polygons)
        else:rings[role]=Polygon()
        if parts and rings[role].is_empty: raise ValueError(f'Unclosed OSM relation {relation_id}: {role}')
    result = rings['outer'].difference(rings['inner'])
    assert result.is_valid and not result.is_empty, relation_id
    return transform(g.TO_M,result), {t.attrib['k']:t.attrib['v'] for t in rel.findall('tag')}

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--source',type=Path);ap.add_argument('--out',type=Path,default=ROOT/'examples/stress')
    a=ap.parse_args()
    if a.source is None:a.source=next((ROOT/'ТЗ').glob('Датасет скоррект*.geojson'))
    raw=a.source.read_bytes();base=json.loads(raw);fs=base['features'];a.out.mkdir(parents=True,exist_ok=True)
    b=unary_union([shape(f['geometry']) for f in fs]).bounds
    # Retain objects in a documented 100 m apron too; this is a finite test-layer extent, NOT a routing boundary.
    extent=transform(g.TO_M,box(*b)).buffer(100,join_style=2)
    osm=ROOT/'examples/stress/osm';provenance=json.loads((osm/'provenance.json').read_text())
    for filename,digest in provenance['files_sha256'].items():assert hashlib.sha256((osm/filename).read_bytes()).hexdigest()==digest
    snapshot=json.loads((osm/'roads.overpass.json').read_text())
    roads=[];waters=[];excluded=[]
    for e in snapshot['elements']:
        t=e.get('tags',{});rid=e['id'];category=t.get('area:highway',t.get('highway',''));cls=category.removesuffix('_link')
        if e['type']=='relation':
            poly,t=relation_polygon(osm/f'relation-{rid}.osm.gz',rid)
            if t.get('natural')=='water':kind='water'
            elif t.get('area:highway','').removesuffix('_link') in DRIVABLE:kind='road'
            else:excluded.append({'id':rid,'reason':'non-motor area'});continue
            poly=poly.intersection(extent)
            if poly.is_empty:continue
            f=g.feature(f'osm-relation-{rid}', 'restriction',poly,restriction_type=kind,osm_type='relation',osm_id=rid,osm_tags=t,width_source='OSM mapped area',provenance='osm/provenance.json',clipped_to_bbox_apron_m=100)
            (roads if kind=='road' else waters).append(f);continue
        coords=[(p['lon'],p['lat']) for p in e.get('geometry',[])]
        if t.get('natural')=='water':
            assert coords[0]==coords[-1],rid
            poly=transform(g.TO_M,Polygon(coords)).intersection(extent)
            if not poly.is_empty:waters.append(g.feature(f'osm-way-{rid}','restriction',poly,restriction_type='water',osm_id=rid,osm_type='way',osm_tags=t,provenance='osm/provenance.json'))
            continue
        if cls not in DRIVABLE:excluded.append({'id':rid,'class':category,'reason':'not a motor road'});continue
        is_area=t.get('area')=='yes' or 'area:highway' in t
        if is_area:
            assert coords[0]==coords[-1],rid
            poly=transform(g.TO_M,Polygon(coords));width=None;source='OSM mapped area'
        else:
            width_text=t.get('width','');match=re.fullmatch(r'\s*(\d+(?:\.\d+)?)\s*(?:m)?\s*',width_text)
            width=float(match.group(1)) if match and 0<float(match.group(1))<=100 else WIDTH[cls]
            source='OSM width tag' if match and 0<float(match.group(1))<=100 else 'assumed class width, not measured'
            poly=transform(g.TO_M,LineString(coords)).buffer(width/2,quad_segs=4,cap_style=2,join_style=2)
        assert poly.is_valid,rid
        poly=poly.intersection(extent)
        if poly.is_empty:continue
        roads.append(g.feature(f'osm-way-{rid}','restriction',poly,restriction_type='road',osm_id=rid,osm_type='way',osm_class=category,osm_tags=t,width_m=width,width_source=source,provenance='osm/provenance.json',clipped_to_bbox_apron_m=100))
    manifest={'seed':20261016,'competition_sha256':hashlib.sha256(raw).hexdigest(),'bbox_wgs84':list(b),'generator':'scripts/generate-universal-datasets.py','generator_versions':{'shapely':g.shapely.__version__,'pyproj':g.pyproj.__version__,'PROJ':g.pyproj.proj_version_str},'crs':'WGS84 input/output; EPSG:32637 operations','osm':provenance,'assumed_full_width_m':WIDTH,'excluded_osm':excluded,'scenarios':[]}
    def save(name,features,description,expected=None,witness=None,proof=None):
        for f in features:assert shape(f['geometry']).is_valid,(name,f['properties']['id'])
        ids=[str(f['properties']['id']) for f in features];assert len(ids)==len(set(ids))
        data=g.encode({'type':'FeatureCollection','features':features});(a.out/(name+'.geojson')).write_bytes(data)
        row={'file':name+'.geojson','features':len(features),'restrictions':sum(f['properties']['object_type']=='restriction' for f in features),'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'description':description}
        if expected is not None:row['expected_connected']=expected
        if witness:
            row['witness']=name+'.witness.json';(a.out/row['witness']).write_bytes(g.encode(witness))
        if proof:row['impossibility_proof']=proof
        manifest['scenarios'].append(row)
    save('competition-osm-roads',fs+roads,'Real OSM centerlines with attributed width approximation; mapped road areas retained.')
    metric=[transform(g.TO_M,shape(f['geometry'])) for f in fs];bounds=unary_union(metric).bounds;x,y=bounds[0],bounds[1]
    extra=g.feature('universal-water-extra','restriction',g.rect(x+650,y+500,40,65),restriction_type='water',provenance='synthetic supplemental pond')
    save('competition-osm-roads-water',fs+roads+waters+[extra],'Same real roads, all queried real water polygons + one labelled synthetic pond.')
    # Geometry conflicts are evidence, not permission to remove a road. Width uncertainty remains explicit.
    conflicts=[]
    for road in roads:
        rp=road['properties'];rg=transform(g.TO_M,shape(road['geometry']))
        for f,geom in zip(fs,metric):
            p=f['properties'];kind=p['object_type']
            if not (kind=='oks_connection_point' or p.get('restriction_type')=='oks'):continue
            if not rg.intersects(geom):continue
            conflicts.append({'road_id':rp['id'],'object_id':p['id'],'object_type':kind,'width_source':rp['width_source'],'intersection_area_m2':rg.intersection(geom).area,'point_inside':kind=='oks_connection_point' and rg.contains(geom),'distance_to_boundary_m':rg.boundary.distance(geom),'assessment':'Model overlap retained. Real footprint cannot be confirmed from approximate width; no geodetic truth claimed.' if rp['width_source'].startswith('assumed') else 'Mapped OSM overlap retained; independent source alignment still requires survey.'})
    (a.out/'osm-conflicts.json').write_text(json.dumps({'road_count':len(roads),'water_count':len(waters),'overlaps':conflicts},ensure_ascii=False,indent=2)+'\n')
    ox,oy=400000.,6170000.
    def local(s):return transform(lambda x,y,z=None:(x+ox,y+oy),s)
    def rf(id_,kind,s):return g.feature('universal-'+id_,'restriction',local(s),restriction_type=kind)
    def basic(cp=(0,100),net=(-10,0,10,0),chamber=False):
        out=[g.feature('source','source',local(Point(net[0],net[1]))),g.feature('network','heat_network',local(LineString([(net[0],net[1]),(net[2],net[3])])),diameter=300),g.feature('consumer','oks_connection_point',local(Point(cp)),flow_tph=4)]
        if chamber:out.append(g.feature('chamber','heat_chamber',local(Point(0,0))))
        return out
    def witness(cs,chamber=False):return {'consumer':'consumer','network':'network','chamber':'chamber' if chamber else None,'coordinates':g.rounded([g.TO_W(ox+x,oy+y)for x,y in cs]),'expected_connected':1}
    def scene(name,restrictions,desc,cs,cp=(0,100),net=(-10,0,10,0),chamber=False):save(name,basic(cp,net,chamber)+restrictions,desc,1,witness(cs,chamber))
    scene('universal-water-detours',[rf('water-multi','water',MultiPolygon([g.rect(-35,30,70,12),g.rect(-15,60,80,12)]))],'Two staggered forbidden water bodies; left detour.',[(0,100),(-50,100),(-50,20),(-20,20),(0,0)])
    ring=Polygon([(-70,-70),(70,-70),(70,70),(-70,70)],holes=[[(-20,-20),(-20,20),(20,20),(20,-20)]])
    save('universal-water-impossible',basic((0,0),(100,0,120,0))+[rf('water-ring','water',ring)],'Water separates the consumer hole from every network point.',0,proof='Jordan separation: consumer in bounded hole; entire existing network outside outer ring. Any continuous path intersects closed water annulus; forbidden in both modes.')
    scene('universal-adjacent-roads',[rf('road-a','road',g.rect(-30,35,60,5)),rf('road-b','road',g.rect(-30,40,60,6))],'Touching road polygons, overlapping 3 m extents, one straight crossing.',[(0,100),(0,0)])
    scene('universal-intersecting-roads',[rf('road-diagonal','road',Polygon([(-35,15),(35,85),(39,81),(-31,11)])),rf('tram-horizontal','tram_tracks',g.rect(-40,45,80,7))],'Road/tram overlap: geometry of both, K=max on overlap.',[(0,100),(0,0)])
    for kind in ['road','tram_tracks']:
        for chamber in [False,True]:scene('universal-'+kind+'-inside-'+('existing' if chamber else 'new'),[rf('inside',kind,g.rect(-30,-5,60,10))],'Camera inside polygon: straight passage ends at camera, +3 m only outside.',[(0,100),(0,0)],chamber=chamber)
    own=MultiPolygon([g.rect(-10,90,20,20),g.rect(30,85,8,15)])
    scene('universal-complex-oks',[rf('own','oks',own),rf('park-hole','park',Polygon([(-60,30),(-20,30),(-20,70),(-60,70)],holes=[[(-50,40),(-50,60),(-30,60),(-30,40)]]))],'MultiPolygon own building; no transit through other component; polygon hole.',[(9,91),(9,0)],cp=(9,91))
    scene('universal-mixed',[rf('road','road',g.rect(-30,70,60,5)),rf('gas','gas_pipeline',LineString([(-30,50),(30,50)])),rf('cable','power_cable',LineString([(-30,50.8),(30,50.8)])),rf('water','water',g.rect(15,20,20,20)),rf('rail','railway',g.rect(-50,20,4,50)),rf('park','park',g.rect(50,20,15,50)),rf('social','social_area',g.rect(-70,10,10,20)),rf('prohibited','prohibited_site',g.rect(70,10,10,20))],'All crossing semantics retained with neighbouring forbidden objects.',[(0,100),(0,0)])
    scene('universal-long-detour',[rf('long-wall','water',g.rect(-650,180,1300,30))],'Direct path blocked; witness goes beyond the complete object envelope with a justified margin.',[(0,400),(-680,400),(-680,70),(-100,70),(0,0)],cp=(0,400))
    # Corridor remains > 2*(1 + 0.685) m, but the inflated navigation union closes it.
    free=unary_union([g.rect(-12,70,24,30),g.rect(-1.725,20,3.45,55),g.rect(-1.725,18.275,75,3.45),g.rect(65,5,25,30)])
    maze=box(-25,-10,105,115).difference(free)
    scene('universal-narrow-corridor',[rf('maze','water',maze)],'L-shaped 3.45 m legal corridor within a closed water polygon; inflated navigation zones must not erase it.',[(0,85),(0,20),(77,20)],cp=(0,85),net=(77,10,77,30))
    scene('universal-boundary',[rf('micro-road','road',Polygon([(-25,40),(25,40),(25,40.000001),(25,46),(-25,46)])),rf('close-tram','tram_tracks',g.rect(-25,46.000001,50,5)),rf('near-water','water',g.rect(20,60,10,20)),rf('touch-water','water',g.rect(30,60,10,20))],'Tiny boundary edge, near-coincident special boundaries, touching water polygons.',[(0,100),(0,0)])
    (a.out/'universal-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'scenarios':len(manifest['scenarios']),'roads':len(roads),'water':len(waters),'overlaps':len(conflicts),'bbox':b}))

if __name__=='__main__':main()
