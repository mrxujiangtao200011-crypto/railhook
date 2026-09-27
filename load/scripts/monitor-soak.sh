#!/usr/bin/env bash
# Through `docker compose exec`: the unauthenticated management port is not published.
set -euo pipefail

OUT_FILE="${1:-soak-results.csv}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-60}"

DOCKER_COMPOSE="docker compose"
if ! docker compose version >/dev/null 2>&1; then
  DOCKER_COMPOSE="docker-compose"
fi

if [ ! -f "$OUT_FILE" ]; then
  echo "timestamp,api_hikari_active,api_hikari_pending,worker_hikari_active,worker_hikari_pending,api_jvm_used_mb,worker_jvm_used_mb,due_deliveries,oldest_due_age_s,redis_dbsize" > "$OUT_FILE"
fi

echo "Sampling every ${INTERVAL_SECONDS}s into $OUT_FILE — Ctrl-C to stop."

fetch_metric() {
  # $1 = service (api|worker), $2 = metric name
  $DOCKER_COMPOSE exec -T "$1" wget -q -O - "http://localhost:${3}/actuator/metrics/$2" 2>/dev/null \
    | grep -oE '"value":[0-9.]+' | head -1 | cut -d: -f2 || echo ""
}

while true; do
  ts=$(date -u +%Y-%m-%dT%H:%M:%SZ)

  api_active=$(fetch_metric api hikaricp.connections.active 8082)
  api_pending=$(fetch_metric api hikaricp.connections.pending 8082)
  worker_active=$(fetch_metric worker hikaricp.connections.active 8081)
  worker_pending=$(fetch_metric worker hikaricp.connections.pending 8081)

  api_jvm_bytes=$(fetch_metric api jvm.memory.used 8082)
  worker_jvm_bytes=$(fetch_metric worker jvm.memory.used 8081)
  api_jvm_mb=$(awk -v b="${api_jvm_bytes:-0}" 'BEGIN { printf "%.1f", b/1048576 }')
  worker_jvm_mb=$(awk -v b="${worker_jvm_bytes:-0}" 'BEGIN { printf "%.1f", b/1048576 }')

  due_row=$($DOCKER_COMPOSE exec -T postgres psql -U "${POSTGRES_USER:-webhook_user}" -d "${POSTGRES_DB:-webhook_platform}" -t -A -c \
    "SELECT count(*), COALESCE(EXTRACT(EPOCH FROM (now() - min(next_retry_at))), 0) FROM deliveries WHERE status IN ('PENDING', 'PROCESSING') AND next_retry_at <= now();" 2>/dev/null || echo "|")
  due_count=$(echo "$due_row" | cut -d'|' -f1)
  due_age=$(echo "$due_row" | cut -d'|' -f2)

  redis_dbsize=$($DOCKER_COMPOSE exec -T redis redis-cli -a "${REDIS_PASSWORD:-webhook_redis_pass}" --no-auth-warning DBSIZE 2>/dev/null | tr -d '\r')

  echo "${ts},${api_active},${api_pending},${worker_active},${worker_pending},${api_jvm_mb},${worker_jvm_mb},${due_count},${due_age},${redis_dbsize}" | tee -a "$OUT_FILE"

  sleep "$INTERVAL_SECONDS"
done
