#!/usr/bin/env bash
# Validates a result GeoJSON against an input GeoJSON using the service's independent validator.
# Usage: scripts/validate-result.sh [base_url] [input.geojson] [result.geojson]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
INPUT="${2:?usage: scripts/validate-result.sh [base_url] <input.geojson> <result.geojson>}"
RESULT="${3:?usage: scripts/validate-result.sh [base_url] <input.geojson> <result.geojson>}"
curl -sf -F "input=@${INPUT}" -F "result=@${RESULT}" "$BASE/api/v1/validate" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("valid:", d["valid"], "errors:", d["errors"], "warnings:", d["warnings"])
print("checks:", d["checks"])
for i in d["issues"][:50]: print(" ", i["severity"], i["code"], i.get("variantId"), i.get("featureId"), "-", i["message"])
'
