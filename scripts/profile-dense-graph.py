#!/usr/bin/env python3
"""Observe the unchanged solver using instrumented source COPIES, heap 2 GiB/container 4 GiB.
No production mutations. A controlled negative result exits 2 and is recorded explicitly.
"""
import argparse, hashlib, json, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def summarize(out,build,input_file):
    report=json.loads((out/'result.geojson.report.json').read_text())
    assert report['input_sha256']==sha(input_file),'Dataset changed'
    assert report['build_sha256']==(ROOT/'target/scale-builds'/build/'sha256.txt').read_text().strip()
    summary={'status':'DONE' if report['status']=='DONE' else 'COMPLEXITY_LIMIT',
      'instrumented_copies_only':True,'baseline_build':build,'input_sha256':report['input_sha256'],
      'diagnostics':report.get('diagnostics'),'solver_metrics':report.get('metrics'),
      'timing_note':'Observer overhead is included; not used to claim a speedup.',
      'scan_definition':'Edges enumerated from settled non-terminal Dijkstra vertices, before per-edge rejection filters; not chosen output edges.',
      'snapshots':[{k:v for k,v in json.loads(f.read_text()).items() if k not in ['vertices','additions_since_strategy_start']} for f in sorted((out/'graphs').glob('*.json'))]}
    if report['status']!='DONE':
        errors={d['code'] for d in report['diagnostics'] if d['severity']=='ERROR'}
        assert errors=={'COMPLEXITY_LIMIT','NO_VALID_VARIANT'},report
        assert any(s['event']=='edge_limit' for s in summary['snapshots'])
    else:
        assert report['valid'] and not report['errors'] and not report['warnings'] and not report['engineering_oracle_issues']
    (out/'summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
    print(summary['status'],'inspect',out/'summary.json')
    return 0 if report['status']=='DONE' else 2
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--build',default='finalization-accepted');p.add_argument('--label',default='dense-250-audit')
    p.add_argument('--input',type=Path,default=Path('target/scale-data/consumers-250.geojson'))
    p.add_argument('--summarize-only',action='store_true',help='Recover evidence after interruption without rerunning the solver')
    a=p.parse_args();assert a.label.replace('-','').isalnum()
    out=ROOT/'target/acceptance-close'/a.label
    if a.summarize_only:return summarize(out,a.build,a.input)
    out.mkdir(parents=True,exist_ok=False)
    build=ROOT/'target/scale-builds'/a.build
    sources=ROOT/'src/main/java/ru/lct/heatnet';before={str(f.relative_to(ROOT)):sha(f) for f in (ROOT/'src/main').rglob('*') if f.is_file()}
    hook='ru.lct.heatnet.stress.GraphAuditProbe'
    replacements={
      'routing/NavGraph.java':[
        ('        buildStaticEdges();','        buildStaticEdges();\n        '+hook+'.snapshot(this,"static",0);'),
        ('            directedEdges.addAndGet(-count);','            directedEdges.addAndGet(-count);\n            '+hook+'.limit(this,count);'),
        ('        active.set(v);\n        return v;','        active.set(v);\n        '+hook+'.added(this,v,js.size(),reverse.size());\n        return v;')],
      'routing/PathSearch.java':[
        ('        graph.countTraversal();','        graph.countTraversal();\n        '+hook+'.search(graph,terminals);'),
        ('            int[] nb = graph.neighbours(u);','            int[] nb = graph.neighbours(u);\n            '+hook+'.scanned(graph,u,nb.length);')],
      'solver/ForestBuilder.java':[
        ('            r.graph.truncate(r.baseSize);','            r.graph.truncate(r.baseSize);\n            '+hook+'.begin(r.graph,strategy.name());'),
        ('        vr.setTrace(trace);','        vr.setTrace(trace);\n        '+hook+'.end();')]
    }
    files=[]
    for name,edits in replacements.items():
        text=(sources/name).read_text()
        for old,new in edits:
            assert text.count(old)==1,(name,old);text=text.replace(old,new)
        f=out/'sources'/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(text);files.append(f)
    mount=['docker','run','--rm','--cpus','8','--memory','4g','-v',str(ROOT)+':/work','-v','lct-robustness-m2:/root/.m2:ro','-w','/work','lct-robustness-builder:baseline']
    wp=lambda f:'/work/'+str(f.relative_to(ROOT))
    cp=wp(build/'classes')+':'+wp(build/'test-classes')+':'+(build/'classpath.txt').read_text().strip()
    classes=out/'classes';classes.mkdir()
    subprocess.run(mount+['javac','--release','11','-cp',cp,'-d',wp(classes)]+[wp(f) for f in files]+['src/test/java/ru/lct/heatnet/stress/GraphAuditProbe.java'],check=True)
    cid=out/'container.cid'
    command=mount[:-1]+['--cidfile',str(cid),mount[-1]]+['java','-Xms128m','-Xmx2g','-XX:+UseG1GC','-XX:+ExitOnOutOfMemoryError','-Dheatnet.graphAudit='+wp(out/'graphs'),'-cp',wp(classes)+':'+cp,'ru.lct.heatnet.stress.StressMain',wp(a.input.resolve()),'PLANAR',wp(out/'result.geojson')]
    (out/'command.json').write_text(json.dumps(command,indent=2)+'\n')
    try:
        with (out/'run.log').open('w') as log:subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=900)
    finally:
        if cid.exists():subprocess.run(['docker','stop','--time','3',cid.read_text().strip()],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    assert before=={str(f.relative_to(ROOT)):sha(f) for f in (ROOT/'src/main').rglob('*') if f.is_file()},'Production sources changed'
    return summarize(out,a.build,a.input)
if __name__=='__main__':raise SystemExit(main())
