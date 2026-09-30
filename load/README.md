# Load and soak tests (k6)

Scenarios that drive `POST /api/v1/events` against a running stack and deliver to a mock receiver
(`load/receiver`) that can be made slow, down or failing.

| Script | What it does |
|---|---|
| `ingest.js` | Sustained ingestion at `TARGET_RPS` |
| `fanout.js` | One event to `FANOUT_N` endpoints |
| `failure-recovery.js` | Endpoint goes slow, then down, then recovers, under traffic |
| `ordering.js` | Ordered deliveries while a retry backlog builds; fails on any out-of-order arrival |
| `soak.js` | Hours of moderate ingestion, for leaks |

Each script's `setup()` registers its own user, project and API key. Env vars are in
`lib/config.js` and at the top of each script.

## Setup

1. Install k6 (https://k6.io/docs/get-started/installation/).
2. Add `WEBHOOK_ALLOW_PRIVATE_IPS=true` to `.env` before `make up`: the receiver has a private
   Docker address, which SSRF protection blocks. Never set this in production; the API refuses to
   start with it when `APP_ENV=production`.
3. Start the stack and the receiver:

   ```bash
   make up && make wait-healthy
   docker compose -f docker-compose.yml -f load/docker-compose.load.yml \
     --profile embedded-db up -d load-receiver
   curl http://localhost:9000/_control/health   # {"ok":true}
   ```

## Running a scenario

```bash
k6 run -e TARGET_RPS=200 -e DURATION=5m load/ingest.js
k6 run -e FANOUT_N=100 -e EVENTS_TO_SEND=10 load/fanout.js
k6 run -e PHASE_HEALTHY_SECONDS=60 -e PHASE_DOWN_SECONDS=120 load/failure-recovery.js
k6 run -e BURST_SIZE=50 -e RETRY_WAIT_SECONDS=150 load/ordering.js
node --test load/receiver/server.test.js
```

### Reading results

- Ingestion: k6 `http_reqs` rate against `TARGET_RPS`, and whether `ingest_errors` or 429s climb.
- End-to-end latency: `GET http://localhost:9000/_control/summary` returns `latencyMsP50` and
  `latencyMsP99`, measured from `data.sentAtMs`. It includes the time a delivery waits to be claimed and worker time.
- Delivery backlog (due but not yet finished): `watch -n5 ./load/scripts/queue-depth.sh`.
- Ordering: `ordering.js` exits non-zero if sequences arrive out of order or fewer than
  `BURST_SIZE` arrive. Give `RETRY_WAIT_SECONDS` room for the first retry at maximum jitter plus
  one retry poll.

## Soak run

```bash
k6 run -e DURATION=4h -e TARGET_RPS=10 load/soak.js &
./load/scripts/monitor-soak.sh soak-results.csv   # samples every 60s (INTERVAL_SECONDS)
```

Look for:

- Connection leak: `*_hikari_active` rising with flat load, or
  `docker compose logs api worker | grep -i "connection leak"`.
- Memory: `*_jvm_used_mb` with a rising floor after GC.
- Redis: `redis_dbsize` that keeps growing after traffic drops. All expected keys have TTLs.

## Measured numbers

Measured on 2026-09-30 against 3.2.0 with `make up`: api, worker, Postgres 16, Redis, UI and the
receiver on one laptop (Intel i5-11320H, 8 threads, 15 GB RAM, Docker 20.10), k6 0.54 on the same
machine. Default pool sizes. The per-project guards were raised so they would not be the limit:
`EVENT_INGESTION_RATE_LIMIT_PER_SECOND=2000`, `WEBHOOK_PROJECT_RATE_LIMIT_PER_SECOND=2000`,
`WEBHOOK_MAX_CONCURRENT_PER_TENANT=200`, `ENTITLEMENT_DEFAULT_RATE_LIMIT=2000`.
`WEBHOOK_MAX_CONCURRENT_PER_ENDPOINT` stayed at 5. Delivery times are `succeeded_at - created_at`
from the database.

| Scenario | Result |
|---|---|
| `ingest.js`, 100 events/s for 2 min, one endpoint | 12,000 accepted, 0 errors, API p99 27 ms. Delivered as fast as ingested: p50 23 ms, p99 2.9 s, backlog empty 1 s after the run |
| `ingest.js`, 300 events/s for 1 min, one endpoint | 18,000 accepted, 0 errors, API p99 30 ms. One endpoint drains at 211 deliveries/s (5 concurrent requests), so a backlog builds above that |
| `fanout.js`, 200 events × 50 endpoints | 10,000 deliveries in 12.6 s, about 790/s, all on the first attempt |
| `ordering.js`, 20 ordered deliveries, the first forced into a retry | 0 out of order; the other 19 waited for the retry (about 87 s, the first rung) |
| `failure-recovery.js`, endpoint healthy, slow, down 2 min, back | 2,701 events, 2,701 delivered exactly once, 0 duplicates, backlog empty 5 min after recovery |

The single-endpoint ceiling is the per-endpoint concurrency limit, not the queue: raising
`WEBHOOK_MAX_CONCURRENT_PER_ENDPOINT` raises it, at the cost of more parallel requests to one
receiver.
