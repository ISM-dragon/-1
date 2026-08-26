# Implemented Personal Gateway Features

## Android → Gateway → Python → Gemini

The Android worker uses the configured Gateway URL and encrypted Gateway token from the existing Social Gateway settings. For a local video, Android computes its SHA-256, creates a session through `POST /v1/sources/uploads`, sends 4 MiB chunks with `X-Upload-Offset` and `Content-Range`, and completes the session after the Gateway verifies size, checksum, and media validity. A retry reuses an in-progress session with the same `(bytes, sha256)` when available. The Gateway then stores the file under its private source directory, starts `POST /v1/processing/jobs`, and returns progress through `GET /v1/processing/jobs/{id}`. The Python pipeline runs with server-side credentials and writes rendered clips. Android downloads the verified MP4 files and imports them into Room.

Gemini credentials are intentionally not sent by Android. They are resolved on the Gateway or pipeline host from server-side environment variables or its private secrets file.

## Provider Profiles

Provider profiles are stored in the server-side processing directory. A profile contains `id`, `type`, `base_url`, `credential_ref`, models, and declared capabilities. It does not contain an API key. The Gateway exposes authenticated management endpoints at `/v1/ai/providers`. The pipeline resolves credentials from `PUBLIKCLIP_SECRET_<REF>`, provider-specific environment variables, or the server-side `secrets.json`.

The router supports Gemini, Anthropic, OpenAI-compatible endpoints, OpenRouter-style endpoints, Together, Groq, Fireworks, Ollama, and local OpenAI-compatible servers. Capability filtering prevents a vision task from selecting a provider that declares `vision: false`. Provider failure falls through to the configured fallback model/provider.

## Processing Modes

`fast`, `balanced`, `quality`, and `maximum` are accepted by the Gateway and stored in the Python job settings. The mode changes candidate and finalist budgets while retaining hard limits. The current candidate ceilings are 20, 40, 70, and 100; finalist budgets are 6, 12, 20, and 32. The selected budget and mode are written into candidate and scoring artifacts for auditability.

## Personal Taste

The Gateway stores explicit preference events in `personal_taste.json`. Supported events are `LIKE`, `DISLIKE`, `SELECT`, `REJECT`, `EDIT`, `EXPORT`, and `PUBLISH`. Updates are incremental, bounded, confidence-aware, and include sample counts. Optional reason tags map to transparent dimensions such as hook, emotion, clarity, duration, and ending strength.

`POST /v1/personal/more-like-this` ranks supplied candidates using topic overlap, duration similarity, style similarity, verified score, and the personal adjustment. `POST /v1/personal/find-better` requires a configurable score delta, defaulting to five points, and explains the difference instead of returning a bare rank.

## Operational requirements

Set `GATEWAY_TOKEN`, `PUBLIC_BASE_URL`, `ISM_PIPELINE_DIR`, and the server-side provider credentials on the Gateway host. Use HTTPS for any deployment outside the local network. Configure the same Gateway URL and token in the Android Social Gateway settings. The Python runtime must have the pipeline dependencies and FFmpeg available.
