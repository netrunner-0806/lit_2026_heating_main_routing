const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '../../..');
const readWorkflow = (name) => fs.readFileSync(path.join(root, '.github/workflows', name), 'utf8');
// verify.yml is the entry point (triggers, deploy gate); the other two stages are reusable workflows it calls.
const verifyWorkflow = readWorkflow('verify.yml');
const publishWorkflow = readWorkflow('build-and-publish.yml');
const deployWorkflow = readWorkflow('deploy.yml');

test('deployment runs for a verified main commit that moved latest, on push or an explicit manual request', () => {
  const gate = verifyWorkflow.match(/^  deploy:\n[\s\S]*?^    if: (.+)$/m)[1];
  const evaluate = (overrides = {}) => {
    const values = {
      'github.event_name': 'push', 'github.ref': 'refs/heads/main',
      'inputs.deploy': undefined, 'needs.verify.outputs.relevant': 'true',
      'needs.build-and-publish.outputs.latest': 'true', ...overrides,
    };
    let expression = gate;
    for (const [key, value] of Object.entries(values)) expression = expression.split(key).join(JSON.stringify(value ?? null));
    return vm.runInNewContext(expression, {}, { timeout: 100 });
  };
  assert.equal(evaluate(), true, 'a verified push to main deploys automatically');
  assert.equal(evaluate({ 'github.event_name': 'workflow_dispatch', 'inputs.deploy': true }), true, 'manual run with deploy');
  assert.equal(evaluate({ 'github.event_name': 'workflow_dispatch', 'inputs.deploy': false }), false, 'manual run without deploy');
  assert.equal(evaluate({ 'github.event_name': 'workflow_dispatch' }), false);
  assert.equal(evaluate({ 'github.event_name': 'pull_request' }), false);
  assert.equal(evaluate({ 'github.ref': 'refs/heads/feature' }), false);
  assert.equal(evaluate({ 'github.event_name': 'workflow_dispatch', 'inputs.deploy': true, 'github.ref': 'refs/heads/feature' }), false);
  assert.equal(evaluate({ 'needs.verify.outputs.relevant': 'false' }), false);
  assert.equal(evaluate({ 'needs.build-and-publish.outputs.latest': 'false' }), false, 'an older run never deploys');
  assert.match(verifyWorkflow, /^  push:\n    branches: \[main\]\n  workflow_dispatch:\n/m);
  assert.match(verifyWorkflow, /deploy:\n\s+description:[^\n]+\n\s+type: boolean\n\s+required: false\n\s+default: true/);
});

test('production compose consumes the heatnet image published by this repository', () => {
  const compose = fs.readFileSync(path.join(root, 'compose.prod.yaml'), 'utf8');
  assert.match(compose, /image: ghcr\.io\/netrunner-0806\/heatnet:\$\{HEATNET_IMAGE_TAG:-latest\}/);
  assert(!compose.includes('lct_finale_private'), 'must not deploy the upstream registry image');
  assert(!compose.includes('lit_2026_heating_main_routing:'), 'must not deploy the previous package name');
  assert.match(publishWorkflow, /^  IMAGE_NAME: heatnet$/m);
  assert.match(publishWorkflow, /OWNER: \$\{\{ github\.repository_owner \}\}/);
  assert.match(publishWorkflow, /image=\$\{REGISTRY\}\/\$\{OWNER,,\}\/\$\{IMAGE_NAME\}/);
});

test('deployment refuses a different existing Compose project before changing volumes', () => {
  const step = deployWorkflow.split('      - name: Pull and update the stack\n')[1].split('      - name: Wait for healthy containers\n')[0];
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
