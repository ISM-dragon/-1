# Opus Pro Gateway Deployment

This package runs the existing FastAPI Gateway, FFmpeg, and the Python processing pipeline. It is intentionally separate from the web UI: the browser never receives the Gemini key or the Gateway token.

## Requirements

Use a persistent Linux host with Docker, at least 4 GB RAM, enough free disk for the largest source video plus its rendered clips, and an HTTPS reverse proxy. The process is resource-intensive; retain the default of one active processing job until measured capacity supports more.

## Setup

Copy `gateway/.env.production.example` to `gateway/.env.production`, generate a long random `GATEWAY_TOKEN`, add the server-side `GEMINI_API_KEY`, then set `PUBLIC_BASE_URL` and `CORS_ORIGINS` to the real HTTPS domains. Do not commit that file.

```bash
cd /srv/opus-pro
cp gateway/.env.production.example gateway/.env.production
chmod 600 gateway/.env.production
docker compose -f docker-compose.gateway.yml up -d --build
curl http://127.0.0.1:8787/health
```

Terminate TLS in a reverse proxy and forward the public HTTPS domain to `127.0.0.1:8787`. Keep port 8787 private at the firewall. After confirming `/health`, configure the same public HTTPS URL and token as `PROCESSING_GATEWAY_URL` and `PROCESSING_GATEWAY_TOKEN` in the web application secrets.

## Verification

```bash
curl -H "Authorization: Bearer $GATEWAY_TOKEN" https://gateway.example.com/v1/processing/capabilities
curl -H "Authorization: Bearer $GATEWAY_TOKEN" -X POST https://gateway.example.com/v1/diagnostics/pipeline
curl -H "Authorization: Bearer $GATEWAY_TOKEN" -X POST https://gateway.example.com/v1/diagnostics/gemini
```

Do not create an analysis job until diagnostics confirm that pipeline, FFmpeg, storage, and Gemini are ready. The systemd unit in `gateway/systemd/` makes the compose service restart after a host reboot.
