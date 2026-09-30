# Railhook

Open-source webhook gateway. Send webhooks to your customers and receive them from Stripe,
GitHub and others, with retries, signatures and a record of every attempt.

![Deliveries](https://raw.githubusercontent.com/vadymkykalo/railhook/main/railhook-ui/public/screens/deliveries.webp)

## Images

Railhook runs as three images from one release. Use the same tag for all three.

| Image | What it runs |
|-------|--------------|
| [`railhook/railhook-api`](https://hub.docker.com/r/railhook/railhook-api) | REST API: takes events, stores them, owns the database migrations |
| [`railhook/railhook-worker`](https://hub.docker.com/r/railhook/railhook-worker) | Delivers events to endpoints, retries, forwards incoming webhooks |
| [`railhook/railhook-ui`](https://hub.docker.com/r/railhook/railhook-ui) | Dashboard and docs behind nginx |

They also need PostgreSQL 16 and Redis 7. The API has to start first: it runs the migrations the
worker waits for.

Tags: each release version and `latest`. Every image is built for
`linux/amd64` and `linux/arm64`, with an SBOM and provenance attached. The same images are on
`ghcr.io/vadymkykalo/railhook-*`.

## Run it

On a server with a domain, HTTPS through Let's Encrypt:

```bash
curl -fsSL https://railhook.io/install.sh | bash -s -- --domain hooks.example.com --email ops@example.com
```

It generates the secrets and starts the whole stack with Docker Compose. To pull from Docker Hub
instead of GHCR, set `DOCKER_REGISTRY=railhook/railhook` in `.env`.

- [Install with Docker](https://railhook.io/docs/self-hosting/install-docker/)
- [Kubernetes (Helm)](https://railhook.io/docs/self-hosting/kubernetes/)
- [Configuration reference](https://railhook.io/docs/self-hosting/configuration/)

## Links

- Website: https://railhook.io
- Source: https://github.com/vadymkykalo/railhook
- Changelog: https://github.com/vadymkykalo/railhook/blob/main/CHANGELOG.md
- License: MIT
