#!/usr/bin/env python3
"""Produce a compact, path-anonymised report from actual experiment artifacts (no invented successes)."""
import hashlib,json,re,statistics,subprocess,xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]

def main():
    area=ROOT/'target/research';series={}
    for file in sorted(area.glob('*/metadata.json')):
        meta=json.loads(file.read_text());rowfile=file.parent/'runs.json'
        if 'reproduce' not in meta:
            meta['reproduce']=['python3','scripts/research-solver-performance.py','--label',meta['label'],
                '--scene',meta['input'],'--mode',meta['mode'],'--repeat',str(meta['warm_repeats']),
                '--name',file.parent.name]
            if meta.get('profile'):meta['reproduce'].append('--profile')
        rows=json.loads(rowfile.read_text()) if rowfile.exists() else []
        selected=[r for r in rows if not r['warmup']] if meta['warm_repeats'] else rows
        summaries={}
        for metric in ['wall_ms','cpu_ms','peak_heap_bytes','peak_rss_bytes','gc_ms','live_thread_allocated_bytes']:
            values=[r[metric] for r in selected if metric in r]
            if values:summaries[metric]={'median':statistics.median(values),'min':min(values),'max':max(values)}
        series[file.parent.name]={'metadata':meta,'sampling':f"median of {len(selected)} warm samples" if meta['warm_repeats'] else 'single cold control',
            'measured_samples':len(selected),'summary':summaries,
            'runs':[{k:r[k] for k in ['features','restrictions','consumers','run','warmup','status','valid','errors','warnings','variants','metrics','diagnostics',
                'output_sha256','wall_ms','cpu_ms','peak_heap_bytes','peak_rss_bytes','gc_ms','live_thread_allocated_bytes','engineering_oracle_issues'] if k in r} for r in rows]}
    comparisons=[]
    for name,after in series.items():
        label=after['metadata']['label']
        prefix=label+'-'
        if label=='baseline' or after['metadata']['profile'] or not name.startswith(prefix):continue
        before=series.get('baseline-'+name[len(prefix):])
        if before is None:continue
        row={'experiment':prefix[:-1],'scene':after['metadata']['input'],'mode':after['metadata']['mode'],
             'same_input':before['metadata']['input_sha256']==after['metadata']['input_sha256'],
             'before_status':before['metadata']['status'],'after_status':after['metadata']['status']}
        for m in ['wall_ms','cpu_ms','peak_heap_bytes','peak_rss_bytes']:
            if before['metadata']['status']==after['metadata']['status']=='OK' and m in before['summary'] and m in after['summary']:
                b=before['summary'][m]['median'];a=after['summary'][m]['median']
                row[m]={'before':b,'after':a,'reduction_percent':(1-a/b)*100}
        if before['metadata']['status']==after['metadata']['status']=='OK':
            row['all_output_bytes_identical']=len({r['output_sha256'] for r in before['runs']+after['runs']})==1
            row['non_timing_counters_identical']=len({json.dumps({k:v for k,v in r['metrics'].items() if not k.endswith('_ms')},sort_keys=True)
                for r in before['runs']+after['runs']})==1
            assert row['same_input'] and row['all_output_bytes_identical'],row
            if label=='h3-block-final':assert row['non_timing_counters_identical'],row
        comparisons.append(row)
    profiles={}
    for p in area.glob('*/profile-summary.json'):
        d=json.loads(p.read_text());samples=d['execution_samples']
        profiles[p.parent.name]={'execution_samples':samples,'tlab_sampled_bytes':d['tlab_sampled_bytes'],
          'inclusive_application_cpu_percent':{k:100*v/samples for k,v in d['inclusive_cpu_samples'].items() if k.startswith('ru.lct.') and '$Lambda' not in k and 'lambda$' not in k},
          'top_inclusive_cpu_percent':{k:100*v/samples for k,v in list(d['inclusive_cpu_samples'].items())[:30]},
          'top_exclusive_cpu_percent_by_line':{k:100*v/samples for k,v in list(d.get('exclusive_cpu_samples_by_line',{}).items())[:30]},
          'threads':d['threads']}
    java={k:0 for k in ['tests','failures','errors','skipped']}
    for p in (ROOT/'target/surefire-reports').glob('TEST-*.xml'):
        x=ET.parse(p).getroot()
        for k in java:java[k]+=int(x.attrib[k])
    full=area/'full-java.log';ui=area/'ui.log'
    java['full_suite_completed']=full.exists() and 'BUILD SUCCESS' in full.read_text()
    ui_text=ui.read_text() if ui.exists() else ''
    ui_counts={name:int(match.group(1)) for name in ['tests','pass','fail','skipped','cancelled']
               for match in [re.search(r'\b'+name+r'\s+(\d+)',ui_text)] if match}
    isolation_file=ROOT/'target/isolation.json'
    isolation=json.loads(isolation_file.read_text()) if isolation_file.exists() else {
        'stable_sha':'c155a15a067be9c868330a8fd30aefbdcc4e72d3',
        'branch':subprocess.check_output(['git','branch','--show-current'],cwd=ROOT,text=True).strip()}
    environment=area/'environment.json'
    manifest=ROOT/'target/scale-data/consumer-manifest.json'
    changes=area/'build-differences.json'
    matrix=area/'matrix-h3-block-final.json'
    report={'stable_main_sha':isolation['stable_sha'],'branch':isolation['branch'],
      'implementation_head_at_report':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
      'conditions':{'jvm':'Eclipse Temurin 11.0.23, aarch64','cpus':8,'container_memory':'4g','Xms':'128m','Xmx':'2g',
        'note':'Sequential local Docker measurements. Application UI idle. Profiles are not used for timing comparisons. RSS is Java /proc VmRSS, not cgroup memory including page cache. Allocations are live-thread MXBean deltas; JFR TLAB bytes are sampling estimates.'},
      'environment':json.loads(environment.read_text()) if environment.exists() else {},
      'consumer_fixture_manifest':json.loads(manifest.read_text()) if manifest.exists() else {},
      'production_build_changed_files':json.loads(changes.read_text()) if changes.exists() else [],
      'candidate_matrix':json.loads(matrix.read_text()) if matrix.exists() else {'status':'NOT_RUN'},
      'series':series,'comparisons':comparisons,'profiles':profiles,'java':java,
      'ui':{'completed':bool(ui_counts.get('tests')) and ui_counts.get('fail')==0,**ui_counts},
      'baseline_sha256':{m:hashlib.sha256((ROOT/f'examples/stress/baseline/{m}.geojson').read_bytes()).hexdigest() for m in ['planar','depth']},
      'scope':'No VPS measurement, production runtime/volume change, main merge or push.'}
    dest=ROOT/'docs/solver-performance-results.json';dest.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print('Wrote',dest.relative_to(ROOT),'series',len(series),'comparisons',len(comparisons))

if __name__=='__main__':main()
