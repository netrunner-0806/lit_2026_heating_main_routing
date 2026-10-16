#!/usr/bin/env python3
"""Summarise raw measurements without turning OOMs, invalid cluster networks or timeouts into successes."""
import argparse, datetime, hashlib, json, statistics
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def sha(file):
    h=hashlib.sha256()
    with file.open('rb') as f:
        for b in iter(lambda:f.read(1<<20),b''):h.update(b)
    return h.hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out',type=Path,default=ROOT/'examples/experiments/verification.json')
    p.add_argument('--acceptance',type=Path,default=ROOT/'target/scale-acceptance/final')
    p.add_argument('--before-hashes',type=Path)
    p.add_argument('--final-label',default='final')
    p.add_argument('--before-label',default='before')
    a=p.parse_args();a.out.parent.mkdir(parents=True,exist_ok=True)
    measurements=[]
    for meta_file in sorted((ROOT/'target/scale-results').glob('*/*.meta.json')):
        meta=json.loads(meta_file.read_text());file=ROOT/meta['report'];runs=json.loads(file.read_text()) if file.exists() else []
        phases=sorted({r.get('variant','default') for r in runs}) or ['default']
        for variant in phases:
            rr=[r for r in runs if r.get('variant','default')==variant]
            warm=[r for r in rr if not r.get('warmup')]
            row={k:v for k,v in meta.items() if k not in ['command','report']}
            row.update(label=meta_file.parent.name,variant=variant,raw_report=meta['report'],raw_metadata=str(meta_file.relative_to(ROOT)),completed_runs=len(rr),warm_runs=len(warm))
            for metric in ['wall_ms','parse_ms','restriction_prepare_ms','context_build_ms','retained_heap_after_parse','peak_heap_bytes','peak_rss_bytes']:
                values=[r[metric] for r in warm if metric in r]
                if values:row[metric]={'median':statistics.median(values),'min':min(values),'max':max(values),'samples':values}
            last=rr[-1] if rr else {}
            for key in ['objects','coordinates','features','restrictions','consumers','valid','errors','warnings','metrics','output_sha256','exception']:
                if key in last:row[key]=last[key]
            if 'variants' in last:
                row['connected']=[v['connected'] for v in last['variants']];row['scores']=[v['score'] for v in last['variants']]
                row['unconnected']=[v['unconnected'] for v in last['variants']]
                row['deterministic']=len(rr)>=2 and len({r.get('output_sha256') for r in rr})==1
            for key in ['connected','score','cost','length','chambers','issues','cluster_sizes']:
                if key in last:row[key]=last[key]
            row['run_statuses']=[r.get('status') for r in rr]
            if not warm and rr:row['cold_only']={k:v for k,v in last.items() if k not in ['diagnostics','variants','metrics']}
            measurements.append(row)
    acceptance={}
    for name in ['java-summary','ui-summary','docker','regeneration']:
        file=a.acceptance/(name+'.json')
        acceptance[name]=json.loads(file.read_text()) if file.exists() else {'status':'NOT_RUN'}
    scenes=[]
    previous_report=ROOT/'examples/stress/verification.json'
    previous_scenes={(s['file'],s['mode']):s for s in json.loads(previous_report.read_text()).get('scenarios',[])} if previous_report.exists() else {}
    for folder in ['universal','mixed']:
        file=a.acceptance/folder/'summary.json'
        if not file.exists():acceptance[folder]={'status':'NOT_RUN'};continue
        data=json.loads(file.read_text());acceptance[folder]={'runs':len(data['runs']),'failures':data['failures']}
        grouped={}
        for r in data['runs']:grouped.setdefault((r['input'],r['mode']),[]).append(r)
        expected_files=({s['file'] for s in json.loads((ROOT/'examples/stress/universal-manifest.json').read_text())['scenarios']}
                        if folder=='universal' else {'competition-dense.geojson','competition-mixed-restrictions.geojson'})
        expected={(file,mode) for file in expected_files for mode in ['PLANAR','DEPTH']}
        acceptance[folder]['coverage_complete']=set(grouped)==expected and all(len(rr)==2 for rr in grouped.values())
        for (file,mode),rr in grouped.items():
            last=rr[-1]
            previous=previous_scenes.get((file,mode),{})
            scenes.append({'file':file,'mode':mode,'runs':len(rr),'status':last['status'],
                'objects':last.get('features'),'restrictions':last.get('restrictions'),'consumers':last.get('consumers'),
                'connected':[v['connected'] for v in last.get('variants',[])],
                'scores':[v['score'] for v in last.get('variants',[])],
                'unconnected':[v.get('unconnected',{}) for v in last.get('variants',[])],
                'valid':last.get('valid'),'errors':last.get('errors'),'warnings':last.get('warnings'),
                'engineering_oracle_issues':last.get('engineering_oracle_issues'),
                'deterministic':len(rr)>=2 and len({r.get('output_sha256') for r in rr})==1,
                'output_sha256':last.get('output_sha256'),'metrics':last.get('metrics'),
                'previous_output_sha256':previous.get('output_sha256'),
                'same_output_as_previous_verification':last.get('output_sha256')==previous['output_sha256'] if previous.get('output_sha256') else None,
                'wall_ms_samples':[r.get('wall_ms') for r in rr],
                'max_sampled_heap_bytes':max(r.get('sampled_peak_heap_bytes',0) for r in rr)})
    files=[]
    if a.before_hashes:
        before=json.loads(a.before_hashes.read_text())
        candidates=set(before)
        for prefix in ['src','scripts','examples','docs']:
            candidates.update(str(f.relative_to(ROOT)) for f in (ROOT/prefix).rglob('*') if f.is_file() and '__pycache__' not in f.parts)
        candidates.add('README.md')
        for name in sorted(candidates):
            file=ROOT/name
            if file==a.out:continue
            current=sha(file) if file.is_file() else None
            if current!=before.get(name):files.append({'file':name,'before_sha256':before.get(name),'after_sha256':current})
        report_name=str(a.out.resolve().relative_to(ROOT))
        files.append({'file':report_name,'before_sha256':before.get(report_name),
                      'generated_report':True,'hash_omitted_to_avoid_self_reference':True})
        files.sort(key=lambda f:f['file'])
    source=next((ROOT/'ТЗ').glob('Датасет скоррект*.geojson'))
    result={
        'generated_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'scope':'Local experimental scalability validation; no server benchmark; no Git operations.',
        'environment':{'java':11,'heap':'2g','container_memory':'4g','docker_cpu_quota':8,
            'method':'One warm-up then four warm runs in the same JVM; separate frozen builds, sequential workloads. Cold-only/timeout runs are explicitly labelled.',
            'timing_scope':'PLANAR/DEPTH wall_ms is the complete CLI calculation, output writing and independent verification. It excludes HTTP upload, DB queueing and application startup.',
            'memory':'Linux /proc/self/status VmRSS and MemoryMXBean heap sampled every 10 ms; retained_heap_after_parse is whole JVM used heap after System.gc, including parser/runtime objects. Sampling is not an exact allocation bound.',
            'metrics_scope':'routing_intersection_calls counts instrumented robust overlays in routing obstacle spaces, not every JTS call in the process.'},
        'source_sha256':sha(source),
        'baseline_sha256':{mode:sha(ROOT/'examples/stress/baseline'/f'{mode}.geojson') for mode in ['planar','depth']},
        'hypotheses':[
            {'id':1,'decision':'KEEP compact XY storage and deferred preparation; REJECT dropping input geometries by a fixed ROI',
             'reason':'All geometries remain available to checks/validation. Plain JTS packed storage with a Coordinate[] soft cache increased retained heap and was replaced by an uncached packed sequence.'},
            {'id':2,'decision':'KEEP bounded navigation construction with adaptive expansion',
             'reason':'Navigation candidates are selected locally; the full obstacle index checks every route. Initial region includes all consumers, existing lines and chambers. Failed attachment expands coverage within explicit resource budgets.'},
            {'id':3,'decision':'NO production clustering',
             'reason':'Independent cluster networks fail combined validation. Reordering clusters with global topology can improve score on some synthetic scenes but adds work; measurements are retained, not promoted to a scalable replacement.'},
            {'id':4,'decision':'NO hydraulic production change',
             'reason':'Independent path-length witnesses A/B/C pass both the calculator and output validator; parallel branches are not summed and constant-flow diameter is preserved.'},
            {'id':5,'decision':'KEEP explicit per-feature and accumulated-model admission budgets',
             'reason':'File bytes and routing complexity are measured separately. Resource exhaustion raises COMPLEXITY_LIMIT, not geometric impossibility or silent filtering.'}
        ],
        'confirmed_experimental_defects':[
            {'case':'6 km water barrier and remote park; independently validated 6.35 km witness',
             'before':'Global search connected; initial local expansion stopped at 5624 > 4096 grid cells.',
             'fix':'Use the primary 100000-cell budget for region expansion, retain the 4096-cell budget for optional fine refinement.',
             'evidence':'target/scale-expansion-before.log'},
            {'case':'The same long detour raises DU to 900 by continuous length',
             'before':'Adaptive candidate used SMALL clearance: 1.70 m from water versus 2.23 m required. Its rejection hid the class-upgrade request.',
             'fix':'Keep rejecting the invalid candidate, forward affected consumer class floors to the existing rerouting loop, and validate the rerouted forest again.',
             'evidence':['target/scale-expansion-probe.log','target/scale-expansion-final.log']}
        ],
        'datasets':{name:json.loads((ROOT/'target/scale-data'/name).read_text()) for name in ['manifest.json','consumer-manifest.json','large-simple-manifest.json'] if (ROOT/'target/scale-data'/name).exists()},
        'measurements':measurements,'acceptance':acceptance,'scenarios':scenes,'changed_files':files,
        'remaining_limits':[
            'The whole retained input model and full obstacle index still reside in heap; this is not out-of-core geometry storage.',
            'A city-wide existing heat network or widely separated consumers also enlarge the initial navigation region; this case is not solved by discarding remote tie-ins.',
            'Adaptive expansion has finite vertex/edge/visibility/grid budgets. COMPLEXITY_LIMIT is not proof of unreachability.',
            'Admission estimates reserve memory but cannot guarantee that every JTS operation on adversarial geometry stays below heap. No promise of arbitrary 3 GB geometry complexity.',
            'City objects use real OSM shapes translated/scaled to synthetic positions. Their local consumers remain the original 17; this is not a million-consumer benchmark.',
            'The 500-consumer dense scene reached the existing 50000000 visibility-check budget; no cluster result is claimed for it.',
            'Region expansion is triggered by a failed attachment; it does not exhaust every possible cost-improving route outside a region that already has a valid connection.',
            'The large-simple file contains unused text deliberately; it demonstrates byte-size independence, not large geometric complexity.'
        ],
        'commands':{'acceptance':'python3 scripts/verify-scale.py --label rerun --generate',
            'generate':'python3 scripts/generate-scale-datasets.py',
            'large_simple':'python3 scripts/generate-scale-datasets.py --only-large-simple',
            'freeze_before_edit':'python3 scripts/benchmark-scale.py --label before --snapshot',
            'example_benchmark':'python3 scripts/benchmark-scale.py --label '+a.final_label+' --phase PLANAR --counts 1000000 --repeat 4',
            'clusters':'python3 scripts/benchmark-scale.py --label cluster --phase CLUSTER --input target/scale-data/consumers-50.geojson --repeat 4',
            'note':'Frozen build labels are immutable and reports are not overwritten. Use new labels for a new experiment.'},
        'git_operations':0}
    final_measurements=[m for m in measurements if m['label']==a.final_label and m['phase'] in ('PLANAR','DEPTH')]
    result['final_build_label']=a.final_label
    result['final_calculation_measurements_ok']=bool(final_measurements) and all(m['status']=='OK' for m in final_measurements)
    comparisons=[]
    for after in measurements:
        if after['label']!=a.final_label or after['phase'] not in ('parse','PLANAR','DEPTH'):continue
        before=next((m for m in measurements if m['label']==a.before_label and m['dataset']==after['dataset'] and m['phase']==after['phase']),None)
        comparisons.append({'dataset':after['dataset'],'phase':after['phase'],
            'objects':after.get('objects',after.get('features')),
            'connected':after.get('connected'),'scores':after.get('scores'),
            'before':{k:before.get(k) for k in ['status','wall_ms','parse_ms','retained_heap_after_parse','peak_heap_bytes','peak_rss_bytes']} if before else {'status':'NOT_MEASURED'},
            'after':{k:after.get(k) for k in ['status','wall_ms','parse_ms','retained_heap_after_parse','peak_heap_bytes','peak_rss_bytes']},
            'validation':{k:after.get(k) for k in ['valid','errors','warnings','deterministic','output_sha256']}})
    result['before_after']=comparisons
    originals=[m for m in final_measurements if Path(m['dataset']).stem=='original']
    result['original_baselines_preserved']=(len(originals)==2 and all(
        m['status']=='OK' and m.get('deterministic') and m.get('output_sha256')==result['baseline_sha256'][m['phase'].lower()]
        and m.get('connected') and all(n==17 for n in m['connected']) and m.get('errors')==0 and m.get('warnings')==0
        for m in originals))
    if a.before_hashes:
        protected_prefixes=('src/main/resources/', 'src/main/java/ru/lct/heatnet/api/',
                            'src/main/java/ru/lct/heatnet/cost/', 'src/main/java/ru/lct/heatnet/hydraulic/',
                            'src/main/java/ru/lct/heatnet/validation/', 'src/test/js/')
        protected_files={'pom.xml','Dockerfile','docker-compose.yml','.env.example','.gitignore'}
        protected_changes=[f['file'] for f in files if f['file'].startswith(protected_prefixes) or f['file'] in protected_files]
        result['protected_paths_unchanged']=not protected_changes
        result['protected_path_changes']=protected_changes
    result['over_budget_dataset']=json.loads((ROOT/'target/scale-over-budget/manifest.json').read_text()) if (ROOT/'target/scale-over-budget/manifest.json').exists() else None
    java=acceptance['java-summary'];ui=acceptance['ui-summary']
    result['acceptance_checks']={
        'java':java.get('tests',0)>0 and not any(java.get(k,0) for k in ['failures','errors','skipped']),
        'ui':ui.get('tests',0)>0 and not any(ui.get(k,0) for k in ['failures','fail','skipped']),
        'original_baselines':result['original_baselines_preserved'],
        'final_measurements':result['final_calculation_measurements_ok'],
        'universal':acceptance['universal'].get('coverage_complete',False) and not acceptance['universal'].get('failures'),
        'mixed':acceptance['mixed'].get('coverage_complete',False) and not acceptance['mixed'].get('failures'),
        'docker':acceptance['docker'].get('status')=='OK'}
    result['acceptance_complete']=all(result['acceptance_checks'].values())
    a.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(a.out)

if __name__=='__main__':main()
