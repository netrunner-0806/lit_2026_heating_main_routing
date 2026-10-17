const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '../../..');
const workflow = fs.readFileSync(path.join(root, '.github/workflows/ci-cd.yml'), 'utf8');

test('publication never deploys on push or without an explicit manual production request', () => {
  const gate = workflow.match(/^  deploy:\n[\s\S]*?^    if: (.+)$/m)[1];
  const evaluate = (overrides = {}) => {
    const values = {
      'github.event_name': 'workflow_dispatch', 'github.ref': 'refs/heads/main',
      'inputs.deploy': true, 'needs.verify.outputs.relevant': 'true',
      'needs.build-and-publish.outputs.latest': 'true', ...overrides,
    };
    let expression = gate;
    for (const [key, value] of Object.entries(values)) expression = expression.split(key).join(JSON.stringify(value ?? null));
    return vm.runInNewContext(expression, {}, { timeout: 100 });
  };
  assert.equal(evaluate(), true, 'explicit verified main deployment remains available');
  assert.equal(evaluate({ 'github.event_name': 'push' }), false);
  assert.equal(evaluate({ 'inputs.deploy': false }), false);
  assert.equal(evaluate({ 'inputs.deploy': undefined }), false);
  assert.equal(evaluate({ 'github.ref': 'refs/heads/feature' }), false);
  assert.equal(evaluate({ 'needs.build-and-publish.outputs.latest': 'false' }), false);
  assert.match(workflow, /deploy:\n\s+description:[^\n]+\n\s+type: boolean\n\s+required: false\n\s+default: false/);
});

test('production compose consumes the image published by the primary repository', () => {
  const compose = fs.readFileSync(path.join(root, 'compose.prod.yaml'), 'utf8');
  assert.match(compose, /image: ghcr\.io\/netrunner-0806\/lit_2026_heating_main_routing:\$\{HEATNET_IMAGE_TAG:-latest\}/);
  assert(!compose.includes('lct_finale_private'), 'must not deploy the upstream registry image');
  assert.match(workflow, /REPO: \$\{\{ github\.repository \}\}/);
  assert.match(workflow, /image=\$\{REGISTRY\}\/\$\{REPO,,\}/);
});

test('deployment refuses a different existing Compose project before changing volumes', () => {
  const step = workflow.split('      - name: Pull and update the stack\n')[1].split('      - name: Wait for healthy containers\n')[0];
  const remote = step.split("<<'REMOTE' | tee \"$RUNNER_TEMP/pull.log\"\n")[1].split('          ref=$(compose config --images app')[0];
  assert(remote.includes('for container in heatnet-db heatnet-app'));
  // Execute the actual preflight shell with a fake Docker CLI; no daemon, server or volumes are touched.
  const fake = `docker() {
    if [ "$1" = inspect ]; then printf '%s\\n' "$EXISTING_PROJECT";
    elif [ "$1" = compose ]; then
      case "$*" in *--quiet*) return 0;; *config*) echo 'name: heatnet';; *) return 99;; esac
    else return 99; fi
  }
  `;
  for (const [existing, expected] of [['heatnet', 0], ['', 0], ['original-project', 1]]) {
    const result = spawnSync('bash', ['-c', fake + remote], { encoding: 'utf8',
      env: { ...process.env, DEPLOY_PATH: root, EXISTING_PROJECT: existing } });
    assert.equal(result.status, expected, result.stderr + result.stdout);
    if (expected) assert.match(result.stdout, /volumes were not changed/);
  }
});
