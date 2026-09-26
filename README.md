<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/brand/railhook-logo-dark.svg">
  <img src="docs/brand/railhook-logo.svg" alt="Railhook" height="48">
</picture>

**Open-source webhook gateway. Send webhooks to your customers and receive them from Stripe,
GitHub and others, with retries, signatures and a record of every attempt. Self-hosted or in
[Railhook Cloud](https://railhook.io/register).**

[![Latest release](https://img.shields.io/github/v/release/vadymkykalo/railhook?label=release)](https://github.com/vadymkykalo/railhook/releases/latest)
[![CI](https://github.com/vadymkykalo/railhook/actions/workflows/ci.yml/badge.svg)](https://github.com/vadymkykalo/railhook/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-000000.svg)](./LICENSE)
[![GHCR](https://img.shields.io/badge/GHCR-ghcr.io%2Fvadymkykalo%2Frailhook-000000?logo=docker&logoColor=white)](https://github.com/vadymkykalo?tab=packages&repo_name=railhook)

<a href="https://www.saashub.com/railhook?utm_source=badge&utm_campaign=badge&utm_content=railhook&badge_variant=color&badge_kind=approved"><img src="https://cdn-b.saashub.com/img/badges/approved-color.png?v=1" alt="Railhook on SaaSHub" height="40"></a>

[Website](https://railhook.io) · [Docs](https://railhook.io/docs/) ·
[API reference](https://railhook.io/docs/api-reference/) · [Railhook Cloud](https://railhook.io/register) · [Changelog](./CHANGELOG.md)

<img src="railhook-ui/public/screens/deliveries.webp" alt="Deliveries: every webhook sent, to which endpoint, and how each attempt went" width="100%">

<img src="railhook-ui/public/screens/attempts.webp" alt="One delivery's attempts: a timeout, a 502, then a 202 on the third try" width="49%">
<img src="railhook-ui/public/screens/incoming.webp" alt="Incoming webhooks from Stripe and GitHub, each signature verified before forwarding" width="49%">

</div>

## Install

With Docker and Compose (about 4 GiB of RAM):

```bash
curl -fsSL https://railhook.io/install.sh | bash
# on a server with a domain, with HTTPS:
curl -fsSL https://railhook.io/install.sh | bash -s -- --domain hooks.example.com --email ops@example.com
```

The installer checks the machine, generates the secrets into `.env`, pins the latest release,
starts the stack and adds a `./railhook` helper (`status`, `logs`, `upgrade`, `backup`, `doctor`).
To do the same by hand with `docker compose`, or to install on Kubernetes with the Helm chart, see
[Install with Docker](https://railhook.io/docs/self-hosting/install-docker/) and
[Kubernetes](https://railhook.io/docs/self-hosting/kubernetes/).

## Send an event

Create a project, an endpoint and an API key in the dashboard, then:

```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "X-API-Key: $RAILHOOK_API_KEY" \
  -H "Idempotency-Key: order-12345-completed" \
  -H "Content-Type: application/json" \
  -d '{"type":"order.completed","data":{"orderId":"ord_12345"}}'
```

Railhook signs it and delivers it to every endpoint subscribed to `order.completed`.

## What it does

- Sends each event to every endpoint subscribed to its type. The event is stored in the same
  transaction as the API call, then delivered through Kafka.
- Retries a failed delivery after 1m, 5m, 15m, 1h, 6h and 24h (7 attempts). What still fails
  goes to Failed Messages for bulk retry.
- Signs every request with HMAC-SHA256 in [Standard Webhooks](https://github.com/standard-webhooks/standard-webhooks)
  headers. A rotated secret keeps signing alongside the new one for 24 hours.
- Deduplicates on `Idempotency-Key`: a repeated key returns the first event instead of a new one.
- Optional per-endpoint ordering, rate limits and a circuit breaker.
- Customer portal: embed a page where your customers add endpoints, pick event types, and see and
  retry their deliveries.
- Replay: resend one delivery, or replay a time range of events as new deliveries.
- Incoming webhooks: one URL per source, signature verified (Stripe, GitHub, GitLab, Shopify,
  Slack, Twilio, Square, Adyen, SendGrid, HubSpot, or generic HMAC), raw request stored, then
  forwarded to your destinations with retries after 1m, 5m, 15m and 1h (5 attempts).
- Every attempt is recorded with its request, response and timing.
- SDKs for [Node](sdks/node), [Python](sdks/python) and [PHP](sdks/php), a [CLI](railhook-cli)
  that tunnels webhooks to `localhost`, and an MCP server at `/mcp`.
- Prometheus metrics, Grafana dashboards and alert rules in [`monitoring/`](monitoring), and a
  [Helm chart](deploy/helm/railhook).

## Docs

- [Documentation](https://railhook.io/docs/) and [API reference](https://railhook.io/docs/api-reference/)
- [Changelog](CHANGELOG.md), [Upgrading](UPGRADING.md)
- [Architecture](docs/ARCHITECTURE.md), [Operations](docs/OPERATIONS.md),
  [Contributing](CONTRIBUTING.md), [Security](SECURITY.md)

## License

[MIT](LICENSE). Self-hosted has every feature, with no licence key. Third-party notices:
[`NOTICE`](NOTICE), [`docs/licenses/`](docs/licenses/).
