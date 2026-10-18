#!/usr/bin/env python3
"""Sequential, bounded comparison of two frozen builds. Never starts application services.

Exit 0: all planned cases completed, validated and byte-identical across builds.
Exit 2: at least one bounded large case timed out; no claim about its final result.
Any other failed/mismatched case fails immediately. Existing artifacts are never overwritten.
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCENES = {
    'original': 'ТЗ/Датасет скорректированный.geojson',
    'osm-water': 'examples/stress/competition-osm-roads-water.geojson',
    'mixed': 'examples/stress/universal-mixed.geojson',
    **{str(n): f'target/scale-data/consumers-{n}.geojson' for n in (50, 100, 250, 500)},
}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--before', default='baseline')
    p.add_argument('--after', default='h3-block-final')
    p.add_argument('--reuse-completed', action='store_true')
    p.add_argument('--large-timeout', type=int, default=300)
    a = p.parse_args()
    for label in (a.before, a.after):
        if not label.replace('-', '').replace('_', '').isalnum():
            p.error('invalid build label')
    if a.large_timeout <= 0:
        p.error('positive timeout required')
    plan = [(s, m, 4, '') for s, modes in [('original', ('PLANAR', 'DEPTH')),
            ('50', ('PLANAR',)), ('100', ('PLANAR',))] for m in modes]
    plan += [('250', 'PLANAR', 0, ''), ('250', 'PLANAR', 0, '-control2')]
    plan += [(s, m, 0, '') for s in ('osm-water', 'mixed') for m in ('PLANAR', 'DEPTH')]
    plan += [('500', 'PLANAR', 0, '')]
    records = []
    incomplete = False
    output = ROOT / 'target/research' / f'matrix-{a.after}.json'
    if output.exists():
        p.error('matrix report already exists; choose a fresh build label')
    for scene, mode, repeats, suffix in plan:
        pair = []
        for label in (a.before, a.after):
            name = f'{label}-{scene}-{mode.lower()}{suffix}'
            area = ROOT / 'target/research' / name
            metadata = area / 'metadata.json'
            expected = {
                'input_sha256': hashlib.sha256((ROOT / SCENES[scene]).read_bytes()).hexdigest(),
                'build_sha256': (ROOT / 'target/scale-builds' / label / 'sha256.txt').read_text().strip(),
                'mode': mode, 'warm_repeats': repeats, 'profile': False, 'parallelism': None,
                'cpu_limit': 8, 'container_memory': '4g', 'heap': '2g',
            }
            if metadata.exists():
                meta = json.loads(metadata.read_text())
                if not a.reuse_completed or meta.get('status') != 'OK':
                    raise RuntimeError(f'Existing incomplete/unapproved artifact: {name}')
                if any(meta.get(k) != v for k, v in expected.items()):
                    raise RuntimeError(f'Existing artifact conditions differ: {name}')
                print('REUSE', name, flush=True)
            else:
                print('START', name, flush=True)
                completed = subprocess.run([
                    'python3', 'scripts/research-solver-performance.py', '--label', label,
                    '--scene', SCENES[scene], '--mode', mode, '--repeat', str(repeats),
                    '--name', name, '--timeout', str(a.large_timeout if scene == '500' else 1200),
                ], cwd=ROOT)
                meta = json.loads(metadata.read_text())
                if completed.returncode and not (scene == '500' and meta['status'] == 'TIMEOUT'):
                    raise RuntimeError(f'Failed measurement: {name}')
            rows = json.loads((area / 'runs.json').read_text()) if (area / 'runs.json').exists() else []
            if meta['status'] == 'OK':
                assert len(rows) == repeats + 1
                assert all(r.get('valid') and r['errors'] == 0 and r['warnings'] == 0
                           and not r['engineering_oracle_issues'] for r in rows)
                assert len({r['output_sha256'] for r in rows}) == 1
                if scene == 'original':
                    golden = hashlib.sha256((ROOT / f'examples/stress/baseline/{mode.lower()}.geojson').read_bytes()).hexdigest()
                    assert all(r['output_sha256'] == golden for r in rows)
            else:
                incomplete = True
            pair.append((meta, rows))
            records.append({'name': name, 'status': meta['status']})
            output.write_text(json.dumps({'status': 'RUNNING', 'records': records}, indent=2) + '\n')
        if all(meta['status'] == 'OK' for meta, _ in pair):
            assert len({r['output_sha256'] for _, rows in pair for r in rows}) == 1, (scene, mode)
    output.write_text(json.dumps({'status': 'INCOMPLETE' if incomplete else 'OK', 'records': records}, indent=2) + '\n')
    return 2 if incomplete else 0


if __name__ == '__main__':
    raise SystemExit(main())
