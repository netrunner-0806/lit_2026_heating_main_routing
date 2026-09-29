#!/usr/bin/env bash
# Uploads the corrected competition dataset to a running service, waits for the result and downloads it.
# Usage: scripts/run-corrected-dataset.sh [base_url] [input.geojson] [output.geojson]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
INPUT="${2:?usage: scripts/run-corrected-dataset.sh [base_url] <input.geojson> [output.geojson] — the competition GeoJSON is provided separately}"
OUTPUT="${3:-examples/result-corrected-dataset.geojson}"

echo "Uploading $INPUT to $BASE ..."
JOB=$(curl -sf -F "file=@${INPUT}" "$BASE/api/v1/jobs" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "Job id: $JOB"
while true; do
  STATUS=$(curl -sf "$BASE/api/v1/jobs/$JOB" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"] + " | " + str(d.get("progress")))')
  echo "  $STATUS"
  case "$STATUS" in DONE*|FAILED*) break;; esac
  sleep 2
done
curl -sf "$BASE/api/v1/jobs/$JOB/result" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("status:", d["status"], "elapsed ms:", d["elapsedMs"], "error:", d.get("error"))
for v in d.get("variants") or []:
    print("  %s rank %d %-28s score %.4f cost %14.0f length %8.1f connected %d unconnected %s" % (v["variantId"], v["rank"], v["strategy"], v["score"], v["calculatedCost"], v["newNetworkLength"], v["connectedOksCount"], v["unconnectedOksIds"]))
val=d.get("validation") or {}
print("validation: valid=%s errors=%s warnings=%s" % (val.get("valid"), val.get("errors"), val.get("warnings")))
'
curl -sf -o "$OUTPUT" "$BASE/api/v1/jobs/$JOB/result.geojson"
echo "Result written to $OUTPUT"
