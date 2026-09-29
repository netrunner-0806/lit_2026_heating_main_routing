#!/usr/bin/env bash
# Reproducible depth-mode demo: runs the synthetic depth demo dataset in PLANAR and DEPTH modes, validates both,
# saves both results and prints the comparison.
# Usage: scripts/run-depth-demo.sh [base_url]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
INPUT="examples/depth-demo.geojson"

run_mode() {
  local MODE="$1"
  local JOB
  JOB=$(curl -sf -F "file=@${INPUT}" "$BASE/api/v1/jobs?mode=$MODE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  while true; do
    STATUS=$(curl -sf "$BASE/api/v1/jobs/$JOB" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"])')
    case "$STATUS" in DONE|FAILED) break;; esac
    sleep 2
  done
  echo "$JOB"
}

echo "PLANAR run ..."; PJOB=$(run_mode PLANAR); echo "  job $PJOB"
echo "DEPTH run ...";  DJOB=$(run_mode DEPTH);  echo "  job $DJOB"
curl -sf -o examples/depth-demo-planar-result.geojson "$BASE/api/v1/jobs/$PJOB/result.geojson"
curl -sf -o examples/depth-demo-depth-result.geojson "$BASE/api/v1/jobs/$DJOB/result.geojson"
echo "Validation (independent validator, both modes):"
for J in $PJOB $DJOB; do
  curl -sf "$BASE/api/v1/jobs/$J/result" | python3 -c '
import json,sys
d=json.load(sys.stdin); v=d["validation"]
print("  %s mode=%s valid=%s errors=%s warnings=%s checks=%s" % (d["jobId"][:8], d["mode"], v["valid"], v["errors"], v["warnings"], sum(v["checks"].values())))'
done
echo "Comparison of the best variants:"
python3 - "$BASE" "$PJOB" "$DJOB" <<'PY'
import json, sys, urllib.request
base, pj, dj = sys.argv[1:4]
def best(j):
    d = json.load(urllib.request.urlopen(f"{base}/api/v1/jobs/{j}/result"))
    return d["variants"][0], d
p, pd = best(pj); q, qd = best(dj)
print("  %-28s %14s %14s" % ("", "PLANAR best", "DEPTH best"))
for k, label in [("score","score"),("calculatedCost","calculated cost"),("newNetworkLength","length, m"),("newChamberCount","new chambers"),("specialCrossingCount","special crossings"),("existingChamberTieInCount","tie-ins"),("connectedOksCount","connected OKS")]:
    print("  %-28s %14s %14s" % (label, round(p[k],4) if isinstance(p[k], float) else p[k], round(q[k],4) if isinstance(q[k], float) else q[k]))
for k in ("maxDepth","averageDepth","depthExtraCost","verticalCrossingCount","aboveCrossingCount","belowCrossingCount","depthTransitionCount"):
    print("  %-28s %14s %14s" % (k, "-", q.get(k)))
print("  attachment points PLANAR:", p["attachmentPoints"])
print("  attachment points DEPTH: ", q["attachmentPoints"])
print("  delta cost: %+.0f, delta length: %+.1f m" % (q["calculatedCost"]-p["calculatedCost"], q["newNetworkLength"]-p["newNetworkLength"]))
PY
echo "Saved: examples/depth-demo-planar-result.geojson, examples/depth-demo-depth-result.geojson"
