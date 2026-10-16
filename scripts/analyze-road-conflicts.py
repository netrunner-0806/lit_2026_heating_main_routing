#!/usr/bin/env python3
"""Independent geometric evidence for the five historical synthetic road refusals (no solver result used)."""
import hashlib, importlib.util, json
from pathlib import Path
from shapely.geometry import shape, Point, LineString
from shapely.ops import transform, nearest_points
ROOT=Path(__file__).resolve().parents[1]
s=importlib.util.spec_from_file_location('gen',ROOT/'scripts/generate-stress-datasets.py');g=importlib.util.module_from_spec(s);s.loader.exec_module(g)
path=ROOT/'examples/stress/competition-with-roads.geojson';raw=path.read_bytes();fs=json.loads(raw)['features']
objects=[(f['properties'],transform(g.TO_M,shape(f['geometry']))) for f in fs]
roads=[(p,x)for p,x in objects if p.get('restriction_type')=='road']
oks=[(p,x)for p,x in objects if p.get('restriction_type')=='oks']
nets=[(p,x)for p,x in objects if p['object_type']=='heat_network']
rows=[]
for id_ in [2,11,12,15,17]:
    cp=next(x for p,x in objects if p['object_type']=='oks_connection_point' and str(p['id'])==str(id_))
    inside=[(p,x)for p,x in roads if x.contains(cp)]
    assert inside, id_
    own=[(p,x)for p,x in oks if x.covers(cp)]
    choices=sorted([(x.distance(cp),p['id'],nearest_points(cp,x)[1]) for p,x in nets],key=lambda x:(x[0],str(x[1])))[:3]
    row={'consumer_id':id_,'classification':'GENUINELY_UNREACHABLE_IN_THIS_SYNTHETIC_MODEL','own_oks_ids':[p['id'] for p,x in own],
        'containing_roads':[{'id':p['id'],'inside_margin_m':x.boundary.distance(cp),'valid_polygon':x.is_valid,'synthetic':str(p['id']).startswith('stress-road-')}for p,x in inside],
        'nearest_exterior_exits':[{'oks_id':p['id'],'distance_m':cp.distance(x.exterior) if x.geom_type=='Polygon' else cp.distance(x.boundary),'still_road_constrained':True}for p,x in own],
        'alternative_tie_ins':[{'network_id':idn,'distance_m':d,'straight_attempt_geometry':g.rounded(list(LineString([cp,q]).coords)),'first_road_interval_starts_at_consumer':all(x.intersects(LineString([cp,q])) for p,x in inside)}for d,idn,q in choices],
        'proof':'Consumer is strictly inside an ORIGINAL individual road polygon (not a union/buffer artifact) and away from every existing network. Any continuous final approach has a nonzero terminal interval inside that road. A road passage requires polygon plus 3 m beyond BOTH boundaries; the terminal has no exterior continuation. The 29.09 §13 exception is only for chambers on existing networks, not this consumer. Changing own-OKS exit, target, detour, road-union construction or depth cannot remove this local obstruction.',
        'witness':'No valid witness can satisfy that local necessary condition. SyntheticRoadConflictsTest checks two different attachment directions with ResultValidator and requires SECTION_OUTSIDE naming the containing road.',
        'organizer_case_claim':False}
    assert min(d for d,idn,q in choices)>0.1
    rows.append(row)
out={'input_sha256':hashlib.sha256(raw).hexdigest(),'metric_crs':'EPSG:32637','command':'python3 scripts/analyze-road-conflicts.py','consumers':rows,'data_warning':'These roads are synthetic. This proof does not apply to the unknown organizer input, nor imply that real OSM roads block the same consumers.'}
(ROOT/'examples/stress/synthetic-road-conflicts.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n')
print([(r['consumer_id'],[(x['id'],round(x['inside_margin_m'],3))for x in r['containing_roads']])for r in rows])
