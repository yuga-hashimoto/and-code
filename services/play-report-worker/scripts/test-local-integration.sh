#!/usr/bin/env bash
set -euo pipefail
workdir="$(mktemp -d)"
port="$(node -e 'const n=require("net").createServer();n.listen(0,"127.0.0.1",()=>{console.log(n.address().port);n.close()})')"
server_pid=''
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
  rm -rf "$workdir"
}
trap cleanup EXIT
config=wrangler.example.jsonc
./node_modules/.bin/wrangler d1 migrations apply andcode-ai-reports --local --persist-to "$workdir/state" --config "$config" > "$workdir/migrate.log"
./node_modules/.bin/wrangler dev --local --ip 127.0.0.1 --port "$port" --persist-to "$workdir/state" --config "$config" > "$workdir/server.log" 2>&1 &
server_pid=$!
for attempt in $(seq 1 60); do
  if curl -s -o /dev/null "http://127.0.0.1:$port/v1/reports"; then break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then cat "$workdir/server.log"; exit 1; fi
  sleep 1
done
status="$(curl -s -o "$workdir/response" -w '%{http_code}' -X POST "http://127.0.0.1:$port/v1/reports" -H 'Content-Type: application/json' -H 'CF-Connecting-IP: 203.0.113.1' --data '{"reason":"OTHER","excerpt":"synthetic local test"}')"
test "$status" = 201
status="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$port/v1/reports")"
test "$status" = 405
./node_modules/.bin/wrangler d1 execute andcode-ai-reports --local --persist-to "$workdir/state" --config "$config" --command "INSERT INTO reports (id, reason, excerpt, created_at) VALUES ('old-local-test', 'OTHER', 'old synthetic', 1)" > /dev/null
curl -fsS "http://127.0.0.1:$port/cdn-cgi/local/scheduled" > /dev/null
./node_modules/.bin/wrangler d1 execute andcode-ai-reports --local --persist-to "$workdir/state" --config "$config" --json --command "SELECT COUNT(*) AS old_count FROM reports WHERE id = 'old-local-test'" > "$workdir/result.json"
node -e 'const fs=require("fs");const r=JSON.parse(fs.readFileSync(process.argv[1]));if(r[0].results[0].old_count!==0)process.exit(1)' "$workdir/result.json"
echo 'Local Worker, D1 insertion, read denial and scheduled deletion passed.'
