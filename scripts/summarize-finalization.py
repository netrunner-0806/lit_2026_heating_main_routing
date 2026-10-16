#!/usr/bin/env python3
"""Build the final evidence manifest from completed measurements; fail on missing/failed acceptance.
No test result is inferred from an upload, a timeout, or a partially completed solver strategy.
"""
import argparse, datetime, hashlib, json, re, statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def read(p):return json.loads(p.read_text())
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(65536),b''):h.update(b)
    return h.hexdigest()
def relative(p):return str(p.relative_to(ROOT))
def measurement(label,scene,mode):
    base=ROOT/'target/scale-results'/label/(scene+'-'+mode.lower())
    meta=read(Path(str(base)+'.meta.json'));rows=read(Path(str(base)+'.json'))
    selected=[r for r in rows if not r['warmup']] or rows
    result={k:meta[k] for k in ['status','build_sha256','heap','container_memory','cpu_quota','requested_warm_repeats','report']}
    result['sampling']='warm median' if any(not r['warmup'] for r in rows) else 'single cold probe'
    for key in ['wall_ms','cpu_ms','peak_heap_bytes','peak_rss_bytes','context_build_ms']:
        values=[r[key] for r in selected if key in r]
        if values:result['median_'+key]=statistics.median(values);result['max_'+key]=max(values)
    last=rows[-1]
    if meta['status']!='OK':
        detail=Path(str(base)+'.json.0.geojson.report.json')
        if detail.exists():last={**read(detail),**last}
    result['objects']=last.get('features',last.get('objects'))
    result['consumers']=last.get('consumers')
    result['metrics']=last.get('metrics',{k:v for k,v in last.items() if k not in ['run','warmup','status','wall_ms','cpu_ms','peak_heap_bytes','peak_rss_bytes']})
    result['variants']=[{k:v for k,v in r.items() if k!='notes'} for r in last.get('variants',[])]
    result['output_sha256']=sorted({r['output_sha256'] for r in rows if 'output_sha256' in r})
    result['graph_sha256']=sorted({r['graph_sha256'] for r in rows if 'graph_sha256' in r})
    result['validation']={k:last.get(k) for k in ['valid','errors','warnings','engineering_oracle_issues']}
    if meta['status']!='OK':
        result['failure']=last.get('exception');result['diagnostics']=last.get('diagnostics')
        # A failure inside ClassResources construction is not published in graphStats().
        # Preserve the counters emitted at the throw site; zero published metrics are not zero work.
        partial=[]
        for diagnostic in last.get('diagnostics',[]):
            match=re.search(r'nodes=(\d+), dynamic insertions=(\d+), geometry checks=(\d+)',diagnostic.get('text',''))
            if match:partial.append(dict(zip(['nodes','dynamic_insertions','geometry_checks'],map(int,match.groups()))))
        if partial:
            result['failure_site_metrics']=partial
            result['metrics_scope']='Published contexts only; failure_site_metrics includes the context that failed during construction.'
    return result
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--label',default='finalization-final');p.add_argument('--before',default='finalization-profile')
    p.add_argument('--graph-build',default='finalization-harness');p.add_argument('--control',default='finalization-accounting')
    p.add_argument('--runtime-label',help='final API-only rebuild, if distinct from the measured solver build')
    p.add_argument('--allow-incomplete-browser',action='store_true',help='Write an explicitly INCOMPLETE report; never mark missing browser evidence accepted')
    p.add_argument('--out',type=Path,default=ROOT/'examples/finalization/verification.json');a=p.parse_args()
    runtime=a.runtime_label or a.label
    source=next((ROOT/'ТЗ').glob('Датасет скоррект*.geojson'))
    source_sha=sha(source)
    assert source_sha==sha(ROOT/'target/scale-data/original.geojson'),'Competition source changed'
    run=ROOT/'target/final-engineering'/runtime;build=(ROOT/'target/scale-builds'/a.label/'sha256.txt').read_text().strip()
    runtime_build=(ROOT/'target/scale-builds'/runtime/'sha256.txt').read_text().strip()
    runtime_differences=[]
    measured_classes=ROOT/'target/scale-builds'/a.label/'classes'
    runtime_classes=ROOT/'target/scale-builds'/runtime/'classes'
    assert {str(f.relative_to(measured_classes)) for f in measured_classes.rglob('*.class')}=={str(f.relative_to(runtime_classes)) for f in runtime_classes.rglob('*.class')}
    for f in runtime_classes.rglob('*.class'):
        name=str(f.relative_to(runtime_classes))
        if f.read_bytes()!=(measured_classes/name).read_bytes():runtime_differences.append(name)
    assert all(n.startswith('ru/lct/heatnet/api/UploadRequestFilter') or n=='ru/lct/heatnet/api/UploadController.class'
               for n in runtime_differences),'Solver changed after measurements'
    java=(run/'java-tests.log').read_text();counts=re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)',java)[-1]
    assert 'BUILD SUCCESS' in java and counts[1:]==('0','0','0'),counts
    ui=(run/'ui-tests.log').read_text();assert re.search(r'fail 0\b',ui) and re.search(r'cancelled 0\b',ui)
    tests={'java':dict(zip(['tests','failures','errors','skipped'],map(int,counts))),'ui':int(re.search(r'tests (\d+)',ui)[1]),
           'java_log':relative(run/'java-tests.log'),'ui_log':relative(run/'ui-tests.log')}
    pairs=[]
    for scene,mode in [('original','PLANAR'),('original','DEPTH')]+[(f'consumers-{n}','PLANAR') for n in [50,100,250]]:
        old=measurement(a.before,scene,mode);new=measurement(a.label,scene,mode)
        assert new['status']=='OK' or (scene=='consumers-250' and new['status']=='COMPLEXITY_LIMIT'),(scene,mode,new['status'])
        assert new['build_sha256']==build
        if old['status']=='OK':assert old['output_sha256']==new['output_sha256'],(scene,mode,'output changed')
        else:assert old['status']=='COMPLEXITY_LIMIT',(scene,mode,old['status'])
        pairs.append({'dataset':scene,'mode':mode,'before':old,'after':new,
                      'wall_speedup_percent':100*(1-new['median_wall_ms']/old['median_wall_ms']) if old['status']==new['status']=='OK' else None})
    for pair in pairs[:2]:
        assert pair['after']['output_sha256']==[sha(ROOT/'examples/stress/baseline'/f"{pair['mode'].lower()}.geojson")]
        assert pair['after']['variants'][0]['connected']==17
    old500=measurement(a.before,'consumers-500','PLANAR');assert old500['status']=='COMPLEXITY_LIMIT'
    new500=measurement(a.label,'consumers-500','PLANAR');assert new500['status'] in ['OK','COMPLEXITY_LIMIT']
    if new500['status']=='OK':assert all(v['connected']==500 for v in new500['variants'])
    for row in [pairs[-1]['after'],new500]:
        if row['status']=='COMPLEXITY_LIMIT':
            errors={d['code'] for d in row['diagnostics'] if d['severity']=='ERROR'}
            assert 'COMPLEXITY_LIMIT' in errors and errors<={'COMPLEXITY_LIMIT','NO_VALID_VARIANT'}
    graph_control=measurement(a.control,'consumers-500','context')
    graph_after=measurement(a.graph_build,'consumers-500','context')
    assert graph_control['status']==graph_after['status']=='OK'
    assert len(graph_after['graph_sha256'])==1 and graph_control['graph_sha256']==graph_after['graph_sha256'],'graph differs'
    stress=[]
    for name in ['stress-universal','stress-mixed']:
        s=read(run/name/'summary.json');assert not s['failures'],s['failures']
        for r in s['runs']:
            assert r['build_sha256']==runtime_build and r['valid'] and not r['errors'] and not r['warnings']
            stress.append({k:r[k] for k in ['input','mode','run','features','restrictions','valid','errors','warnings','output_sha256','wall_ms','variants']})
    assert len(stress)==68,len(stress)
    http=read(ROOT/'target/final-engineering/http/verification.json')
    browser_path=ROOT/'target/final-engineering/http/browser.json'
    browser=read(browser_path) if browser_path.exists() else {
        'status':'NOT_VERIFIED','runs':[],
        'reason':'Chromium launch failed before opening the page (incomplete local installation). Firefox fallback was prepared but not run before the user requested the report.',
        'failed_attempt':'target/final-engineering/http/browser-failed-incomplete-chromium.json'}
    assert http['status']=='OK'
    browser_accepted=browser['status']=='OK' and any(r['bytes']>=int(2.8*1024**3) and r['status']=='OK' for r in browser['runs'])
    assert browser_accepted or a.allow_incomplete_browser,'Browser acceptance is incomplete'
    assert http['environment']['tested_classes_sha256']==runtime_build
    assert http['metadata_path_variants_rejected']
    assert any(r['bytes']>=int(2.8*1024**3) and r['upload_status']=='OK' for r in http['runs'])
    assert http['crash_recovery']['states_before']==['RUNNING','QUEUED'] and http['crash_recovery']['automatic_restart_count']>=1
    before=read(ROOT/'target/final-engineering/before/hashes.json')
    missing=[name for name in before if not (ROOT/name).is_file()]
    assert not missing,('Existing files removed',missing)
    current={relative(f):sha(f) for root in ['src','scripts','caddy'] for f in (ROOT/root).rglob('*') if f.is_file() and '__pycache__' not in str(f)}
    for name in ['README.md','Dockerfile','docker-compose.yml','pom.xml','.env.example','.gitignore','.dockerignore']:
        if (ROOT/name).exists():current[name]=sha(ROOT/name)
    changes=[{'file':f,'sha256':h,'change':'modified' if f in before else 'new'} for f,h in sorted(current.items()) if before.get(f)!=h]
    protected=['Dockerfile','docker-compose.yml','.env.example','.gitignore','.dockerignore','pom.xml','src/main/resources/application.yml','caddy/Caddyfile','src/main/resources/static/style.css']
    assert all(current[f]==before[f] for f in protected),'protected configuration changed'
    engineering=[f for f in before if f.startswith('src/main/java/ru/lct/heatnet/')
                 and (any('/'+package+'/' in f for package in ['config','hydraulic','cost','depth','validation','geo','output'])
                      or f.endswith('/SolverConfig.java') or f.endswith('/SpecialPassage.java'))]
    assert all(current[f]==before[f] for f in engineering),'Engineering rules, parser or output changed'
    memory_path=ROOT/'target/final-engineering/http/memory-after-browser.json'
    output={'generated_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
      'status':'ACCEPTED_WITH_DOCUMENTED_LIMITS' if browser_accepted else 'INCOMPLETE_BROWSER_VERIFICATION',
      'environment':{'scope':'local Docker VM; same host and sequential runs','cpu_quota':8,'jvm_heap':'2 GiB','container_memory':'4 GiB',
                     'server_77s_comparison':'not measured; no server access'},
      'build_classes_sha256':runtime_build,'benchmark_classes_sha256':build,'runtime_diff_from_benchmarks':sorted(runtime_differences),
      'solver_bytecode_identical_between_benchmark_and_runtime':True,
      'source_file':relative(source),'source_sha256':source_sha,'tests':tests,'benchmarks':pairs,
      'dense_500':{'before':old500,'after':new500,'control_without_certificates':graph_control,'with_certificates':graph_after,
                  'graph_comparison':{
                    'byte_identical':True,
                    'wall_reduction_percent':100*(1-graph_after['median_wall_ms']/graph_control['median_wall_ms']),
                    'cpu_reduction_percent':100*(1-graph_after['median_cpu_ms']/graph_control['median_cpu_ms']),
                    'full_geometry_check_reduction_percent':100*(1-graph_after['metrics']['geometry_visibility_checks']/graph_control['metrics']['geometry_visibility_checks']),
                    'intersection_reduction_percent':100*(1-graph_after['metrics']['routing_intersection_calls']/graph_control['metrics']['routing_intersection_calls'])},
                  'prior_900s_probe':'target/scale-results/finalization-interior/consumers-500-planar.meta.json (TIMEOUT, not acceptance)'},
      'stress':stress,'http':http,'browser':{k:v for k,v in browser.items() if k!='runs'},
      'browser_runs':[{k:v for k,v in r.items() if k!='requests'} for r in browser['runs']],
      'memory_at_teardown':read(memory_path) if memory_path.exists() else None,
      'browser_acceptance_complete':browser_accepted,
      'changed_files':changes,'deleted_files':missing,'documentation':['README.md','docs/resumable-upload.md','examples/finalization/README.md'],
      'protected_configuration_unchanged':protected,'production_volumes_touched':False,'server_deployed':False,'git_operations':0,
      'engineering_parser_output_sources_unchanged':engineering,
      'remaining_limits':[
        'Upload max 3 GiB is a transport limit, not a guarantee for arbitrary geometry complexity. Large transport fixtures preserve the 144-object scene and add unused text.',
        'Graph search remains heuristic and bounded; resource exhaustion is COMPLEXITY_LIMIT, not proof of geometric impossibility.',
        'The dense 250-consumer fixture still reaches the unchanged 4M directed-edge limit in separate-connections. The 500-consumer full solve is a single cold probe; repeated measurements cover its exact navigation graph.',
        'Upload journal assumes one app process, a persistent Linux filesystem with hard links, atomic rename and directory fsync. Completed/cancelled metadata tombstones are retained.',
        'No independent consumer clustering, strategy parallelism, global cache, candidate reduction, cost or engineering-rule changes.',
        '16 GiB production sizing must reserve native JVM, PostgreSQL and OS memory; local 2 GiB heap measurements do not certify worst-case native memory at Xmx13g.'
      ],
      'commands':[
        'python3 scripts/verify-finalization.py --phase tests --label <fresh-label>',
        'python3 scripts/verify-finalization.py --phase bench --label <fresh-label> --before finalization-profile',
        'python3 scripts/verify-finalization.py --phase stress --label <fresh-label>',
        'python3 scripts/generate-upload-fixtures.py',
        'python3 scripts/verify-finalization.py --phase http --label <fresh-label>',
        'python3 scripts/verify-finalization.py --phase browser --label <fresh-label>',
        'python3 scripts/verify-finalization.py --phase stop --label <fresh-label>',
        'python3 scripts/prepare-visibility-ablation.py',
        'python3 scripts/benchmark-scale.py --label finalization-accounting --phase context --input target/scale-data/consumers-500.geojson --repeat 4 --timeout 900',
        'python3 scripts/benchmark-scale.py --label finalization-harness --phase context --input target/scale-data/consumers-500.geojson --repeat 4 --timeout 300',
        f'python3 scripts/summarize-finalization.py --label {a.label} --runtime-label {runtime} --before {a.before} --graph-build {a.graph_build} --control {a.control}',
        f'python3 scripts/verify-finalization.py --phase acceptance --label {runtime} --benchmark-label {a.label} --before {a.before} --graph-build {a.graph_build} --control {a.control}'
      ]}
    a.out.parent.mkdir(parents=True,exist_ok=True);a.out.write_text(json.dumps(output,ensure_ascii=False,indent=2)+'\n');print(relative(a.out))
if __name__=='__main__':main()
