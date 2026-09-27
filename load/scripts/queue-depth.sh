#!/usr/bin/env bash
set -euo pipefail

DOCKER_COMPOSE="docker compose"
if ! docker compose version >/dev/null 2>&1; then
  DOCKER_COMPOSE="docker-compose"
fi

$DOCKER_COMPOSE exec -T postgres psql -U "${POSTGRES_USER:-webhook_user}" -d "${POSTGRES_DB:-webhook_platform}" -c \
  "SELECT status, count(*), min(next_retry_at) AS oldest_due, now() - min(next_retry_at) AS oldest_due_age FROM deliveries WHERE status IN ('PENDING', 'PROCESSING') AND next_retry_at <= now() GROUP BY status ORDER BY status;"
