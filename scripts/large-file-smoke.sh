#!/usr/bin/env bash
# Streaming upload smoke test: runs the service with a small heap (-Xmx512m) against PostgreSQL, uploads a synthetic
# GeoJSON larger than the heap and checks that the job completes. The synthetic file contains a tiny valid problem
# plus hundreds of MB of padded features of an unsupported object_type (skipped by the streaming parser).
# Usage: scripts/large-file-smoke.sh [size_mb] [port]   (PostgreSQL must be reachable: see POSTGRES_* variables)
set -euo pipefail
SIZE_MB="${1:-700}"
PORT="${2:-8091}"
JAR="target/heatnet-router-1.0.0.jar"
[ -f "$JAR" ] || { echo "build first: mvn -B package -DskipTests"; exit 1; }
TMP="${TMPDIR:-/tmp}/heatnet-large-smoke"
mkdir -p "$TMP"
FILE="$TMP/large-${SIZE_MB}mb.geojson"

echo "Generating ${SIZE_MB} MB synthetic GeoJSON at $FILE ..."
python3 - "$FILE" "$SIZE_MB" <<'PY'
import sys
path, size_mb = sys.argv[1], int(sys.argv[2])
target = size_mb * 1024 * 1024
pad = "x" * 4000
with open(path, "w") as w:
    w.write('{"type":"FeatureCollection","features":[\n')
    w.write('{"type":"Feature","properties":{"id":1,"object_type":"heat_network","diameter":300},"geometry":{"type":"LineString","coordinates":[[37.6,55.75],[37.605,55.75]]}},\n')
    w.write('{"type":"Feature","properties":{"id":2,"object_type":"oks_connection_point","flow_tph":10},"geometry":{"type":"Point","coordinates":[37.6025,55.7515]}},\n')
    w.write('{"type":"Feature","properties":{"id":3,"object_type":"restriction","restriction_type":"oks"},"geometry":{"type":"Polygon","coordinates":[[[37.6023,55.7513],[37.6027,55.7513],[37.6027,55.7517],[37.6023,55.7517],[37.6023,55.7513]]]}}')
    n = 0; i = 0
    while n < target:
        f = ',\n{"type":"Feature","properties":{"id":"pad-%d","object_type":"unsupported_padding","description":"%s"},"geometry":{"type":"Point","coordinates":[37.61,55.76]}}' % (i, pad)
        w.write(f); n += len(f); i += 1
    w.write('\n]}\n')
print("features written:", i + 3)
PY
ls -lh "$FILE"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
echo "Starting the service with -Xmx512m on port $PORT (PostgreSQL ${POSTGRES_HOST:-localhost}:${POSTGRES_PORT:-5432}) using $JAVA ..."
"$JAVA" -Xmx512m -jar "$JAR" --server.port="$PORT" --heatnet.storage-dir="$TMP/jobs" --spring.servlet.multipart.location="$TMP/tmp" > "$TMP/app.log" 2>&1 &
APP_PID=$!
trap 'kill $APP_PID 2>/dev/null || true' EXIT
for i in $(seq 1 60); do curl -sf -o /dev/null "http://localhost:$PORT/actuator/health" && break; sleep 2; done
curl -sf -o /dev/null "http://localhost:$PORT/actuator/health" || { echo "service did not start, see $TMP/app.log"; exit 1; }

echo "Uploading ..."
START=$(date +%s)
JOB=$(curl -sf -F "file=@${FILE}" "http://localhost:$PORT/api/v1/jobs" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "job $JOB uploaded in $(( $(date +%s) - START )) s"
while true; do
  STATUS=$(curl -sf "http://localhost:$PORT/api/v1/jobs/$JOB" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["status"], "|", d.get("progress"), "|", d.get("featureCount"))')
  echo "  $STATUS"
  case "$STATUS" in DONE*|FAILED*) break;; esac
  sleep 3
done
case "$STATUS" in
  DONE*) echo "OK: ${SIZE_MB} MB input processed with a 512 MB heap (streaming upload + streaming parser)";;
  *) echo "FAILED, see $TMP/app.log"; exit 1;;
esac
