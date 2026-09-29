#!/usr/bin/env bash
# Runs the corrected competition dataset in DEPTH mode and saves the separate depth result.
# Usage: scripts/run-corrected-dataset-depth.sh [base_url] [input.geojson] [output.geojson]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
INPUT="${2:?usage: scripts/run-corrected-dataset-depth.sh [base_url] <input.geojson> [output.geojson] — the competition GeoJSON is provided separately}"
OUTPUT="${3:-examples/result-corrected-dataset-depth.geojson}"
JOB=$(curl -sf -F "file=@${INPUT}" "$BASE/api/v1/jobs?mode=DEPTH" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "DEPTH job $JOB"
while true; do
  STATUS=$(curl -sf "$BASE/api/v1/jobs/$JOB" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"] + " | " + str(d.get("progress")))')
  echo "  $STATUS"; case "$STATUS" in DONE*|FAILED*) break;; esac; sleep 2
done
curl -sf "$BASE/api/v1/jobs/$JOB/result" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("mode:", d["mode"], "elapsed ms:", d["elapsedMs"])
for v in d.get("variants") or []:
    print("  %s rank %d %-26s score %.4f cost %12.0f length %7.1f connected %d maxDepth %s extra %s crossings %s" % (v["variantId"], v["rank"], v["strategy"], v["score"], v["calculatedCost"], v["newNetworkLength"], v["connectedOksCount"], v.get("maxDepth"), v.get("depthExtraCost"), v.get("verticalCrossingCount")))
val=d["validation"]; print("validation: valid=%s errors=%s warnings=%s checks=%s" % (val["valid"], val["errors"], val["warnings"], sum(val["checks"].values())))
'
curl -sf -o "$OUTPUT" "$BASE/api/v1/jobs/$JOB/result.geojson"
echo "Result written to $OUTPUT"
