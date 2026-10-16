#!/usr/bin/env python3
"""Close the explicitly narrowed acceptance scope. Browser tests are excluded, never reported PASS.
Require fresh Java/JS tests, real OSM+water runs and a reproduced dense limit. Reuse slow
historical measurements only after proving identical production bytecode and input hashes.
"""
import argparse, datetime, hashlib, json, re, xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def read(path):return json.loads((ROOT/path).read_text())
def sha(path):return hashlib.sha256((ROOT/path).read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--label',default='acceptance-close');p.add_argument('--audit',default='dense-250-audit-run1')
    p.add_argument('--osm-out',default='target/acceptance-close/osm-water')
    a=p.parse_args();run=ROOT/'target/final-engineering'/a.label
    audit_dir='target/acceptance-close/'+a.audit
    java=(run/'java-tests.log').read_text();counts=re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',java)[-1]
    assert 'BUILD SUCCESS' in java and counts[1:]==('0','0','0'),counts
    ui=(run/'ui-tests.log').read_text();assert re.search(r'fail 0\b',ui) and re.search(r'cancelled 0\b',ui)
    build=(ROOT/'target/scale-builds'/a.label/'sha256.txt').read_text().strip()
    assert build==(ROOT/'target/scale-builds/finalization-accepted/sha256.txt').read_text().strip(),'Production bytecode changed'
    tests={}
    for name in ['oracle.CompetitionBaselineTest','determinism.DeterminismTest','stress.WitnessRoutesTest',
                 'stress.UniversalRoutingTest','routing.NavGraphLimitTest','application.UploadServiceTest',
                 'application.JobRecoveryTest','input.ResumableUploadHttpTest','api.UploadStorageFailureTest']:
        file=ROOT/'target/surefire-reports'/('TEST-ru.lct.heatnet.'+name+'.xml')
        root=ET.parse(file).getroot();assert all(int(root.get(k,'0'))==0 for k in ['errors','failures','skipped']),name
        tests[name]={k:root.get(k) for k in ['tests','errors','failures','skipped','time']}
    before=read('target/acceptance-close/before-hashes.json')
    changes=[f for f,h in before.items() if sha(f)!=h]
    assert not any(f.startswith('src/main/') for f in changes),'Production source changed'
    protected=['pom.xml','Dockerfile','docker-compose.yml','.env.example','caddy/Caddyfile']
    assert not set(changes)&set(protected),'Production configuration changed'
    current={str(p.relative_to(ROOT)):sha(p.relative_to(ROOT)) for base in ['src','scripts','docs','examples/finalization']
             for p in (ROOT/base).rglob('*') if p.is_file() and '__pycache__' not in str(p)}
    new=[f for f in current if f not in before]
    new_production=[f for f in new if f.startswith('src/main/')];assert not new_production
    audit=read(audit_dir+'/summary.json')
    dense=read(audit_dir+'/result.geojson.report.json')
    prior250=read('target/scale-results/finalization-final/consumers-250-planar.json.0.geojson.report.json')
    counters=['graph_nodes','graph_directed_edges','dijkstra_traversals','geometry_visibility_checks',
              'candidate_pairs_examined','candidate_count','fallback_activations','navigation_region_expansions']
    assert all(dense['metrics'][k]==prior250['metrics'][k] for k in counters)
    assert dense['input_sha256']==sha('target/scale-data/consumers-250.geojson')
    limit=next(r for r in audit['snapshots'] if r['event']=='edge_limit')
    assert audit['status']=='COMPLEXITY_LIMIT' and limit['duplicate_directed_edges']==0
    stress=read(a.osm_out+'/summary.json')
    assert not stress['failures'] and len(stress['runs'])==2
    old_stress=read('target/final-engineering/finalization-accepted/stress-universal/summary.json')
    for r in stress['runs']:
        assert r['build_sha256']==build and r['valid'] and not r['errors'] and not r['warnings']
        assert all(v['connected']==17 for v in r['variants'])
        matches=[x for x in old_stress['runs'] if x['input']==r['input'] and x['mode']==r['mode']]
        assert matches and all(x['output_sha256']==r['output_sha256'] for x in matches)
    http=read('target/final-engineering/http/verification.json')
    assert http['status']=='OK' and http['environment']['tested_classes_sha256']==build
    assert sha('Dockerfile')==http['environment']['dockerfile_sha256']
    assert sha('caddy/Caddyfile')==http['environment']['caddy_config_sha256']
    prior_sources=read('target/final-engineering/before/hashes.json')
    assert all(sha(f)==prior_sources[f] for f in protected),'Production configuration differs from previous Docker verification'
    assert all(sha('src/main/resources/static/'+name)==h for name,h in http['environment']['static_sha256'].items())
    # The performance build differs only by later upload API fixes, not by the solver.
    measured=ROOT/'target/scale-builds/finalization-final/classes';current_classes=ROOT/'target/scale-builds'/a.label/'classes'
    assert {str(f.relative_to(measured)) for f in measured.rglob('*.class')}=={str(f.relative_to(current_classes)) for f in current_classes.rglob('*.class')}
    differences=[]
    for f in current_classes.rglob('*.class'):
        name=str(f.relative_to(current_classes))
        if f.read_bytes()!=(measured/name).read_bytes():differences.append(name)
    assert all(n.startswith('ru/lct/heatnet/api/UploadRequestFilter') or n=='ru/lct/heatnet/api/UploadController.class' for n in differences)
    reused=[]
    for count in [50,100,500]:
        base=f'target/scale-results/finalization-final/consumers-{count}-planar'
        meta=read(base+'.meta.json');rows=read(base+'.json');assert meta['status']=='OK'
        for r in rows:
            assert r['input_sha256']==sha(f'target/scale-data/consumers-{count}.geojson')
            assert r['valid'] and not r['errors'] and not r['warnings'] and not r['engineering_oracle_issues']
            assert all(v['connected']==count for v in r['variants'])
        reused.append({'consumers':count,'report':base+'.json','new_run':False,'reason':'Identical solver bytecode and input SHA-256; no relevant production changes.',
                       'output_sha256':rows[-1]['output_sha256'],'connected':count,'valid':True})
    baseline={mode:sha('examples/stress/baseline/'+mode.lower()+'.geojson') for mode in ['PLANAR','DEPTH']}
    result={'generated_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
      'status':'VERIFIED_WITH_BROWSER_EXCLUDED_AND_DOCUMENTED_DENSE_LIMIT',
      'browser':{'status':'SKIPPED_BY_USER','end_to_end_pass':False,'reason':'User explicitly excluded browser checks from this continuation. No browser upload was executed.',
                 'earlier_launch_only':'Existing Firefox launch timed out before any upload; no browser or packages installed.'},
      'production_source_changes_this_iteration':0,'production_bytecode_unchanged':True,'build_sha256':build,
      'tests':{'java':dict(zip(['tests','failures','errors','skipped'],map(int,counts))),'ui':int(re.search(r'tests (\d+)',ui)[1]),
               'browser_e2e_included':False,'java_log':str((run/'java-tests.log').relative_to(ROOT)),
               'ui_log':str((run/'ui-tests.log').relative_to(ROOT)),'focused_suite_results':tests},
      'competition':{'PLANAR':{'connected':17,'score':13.433636792,'valid':True,'errors':0,'warnings':0},
                     'DEPTH':{'connected':17,'score':13.433636792,'valid':True,'errors':0,'warnings':0},
                     'output_sha256':baseline,'byte_identity_test':'CompetitionBaselineTest.bothModesRemainByteIdenticalToFrozenCompetitionResults'},
      'osm_water':{'summary':a.osm_out+'/summary.json','runs':stress['runs'],'byte_identical_to_previous':True},
      'dense_250':{'status':'COMPLEXITY_LIMIT','production_fix_retained':False,'source_unchanged':True,
                   'report':audit_dir+'/summary.json','limit_snapshot':limit,
                   'structural_counters_equal_to_previous':counters,'fallback_activations':0,'navigation_region_expansions':0,
                   'root_cause':'Separate-connections eagerly connects accumulated junction candidates. 2921 junction vertices own 2972838 outgoing edges; no duplicate directed edges by vertex ID. Next insertion would require 4002063 edges.',
                   'rejected_changes':['Raising the edge cap or heap','Dropping unused edges: changing terminal sets and dynamic obstacles can make them relevant later',
                       'Merging coincident coordinates: different vertex roles/headings/exemptions and deterministic IDs are not interchangeable',
                       'Lazy adjacency redesign was not justified as a minimal risk-free change in the final acceptance scope']},
      'reused_scale_evidence':reused,'reused_docker_http_evidence':{'report':'target/final-engineering/http/verification.json','new_run':False,
          'reason':'Production sources, bytecode, frontend assets, Dockerfile and Compose unchanged; HTTP upload/recovery covered again by Java tests.',
          'http_status':http['status'],'previous_real_http_sizes_bytes':[r['bytes'] for r in http['runs']],
          'production_volumes_touched':False},
      'changes_this_iteration':sorted(set(changes+new)-{'examples/finalization/verification.json'})+['examples/finalization/verification.json'],
      'commands':[
        'python3 scripts/profile-dense-graph.py --label dense-250-audit-fresh',
        'python3 scripts/profile-dense-graph.py --label dense-250-audit-run1 --summarize-only',
        'python3 scripts/verify-finalization.py --phase tests --label acceptance-close-fresh',
        'python3 scripts/run-stress-suite.py --skip-build --docker-image lct-robustness-builder:baseline --maven-cache lct-robustness-m2 --manifest examples/stress/universal-manifest.json --scenario competition-osm-roads-water --mode BOTH --repeat 1 --out target/acceptance-close/osm-water-fresh',
        'python3 scripts/summarize-acceptance-close.py --label acceptance-close-fresh --audit dense-250-audit-fresh --osm-out target/acceptance-close/osm-water-fresh'],
      'command_notes':'The dense audit intentionally exits 2 for confirmed COMPLEXITY_LIMIT. Fresh runs must use fresh output directories; pass their names to the summarizer.',
      'production_deployed':False,'production_volumes_touched':False,'git_operations':0}
    dest=ROOT/'examples/finalization/verification.json'
    archive=ROOT/'target/acceptance-close/previous-verification.json'
    if not archive.exists():archive.write_bytes(dest.read_bytes())
    dest.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(result['status'],dest)
if __name__=='__main__':main()
