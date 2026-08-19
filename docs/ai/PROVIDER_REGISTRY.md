# ISM Provider Registry and Secret Vault

## Scope

The Gateway now owns provider discovery and secret boundaries. Built-in providers are registered as data at startup:

| ID | Type | Default endpoint | Credential reference |
|---|---|---|---|
| `gemini` | Gemini | Google Generative Language API | `GEMINI_API_KEY` |
| `openai` | OpenAI | OpenAI v1 API | `OPENAI_API_KEY` |
| `anthropic` | Anthropic | Anthropic v1 API | `ANTHROPIC_API_KEY` |
| `openrouter` | OpenAI-compatible | OpenRouter v1 API | `OPENROUTER_API_KEY` |
| `ollama` | Ollama | `http://127.0.0.1:11434` | none |

Custom OpenAI-compatible providers are stored in SQLite and can be created through `POST /v1/ai/providers` without source-code changes. The response includes provider metadata and health only; it never includes the submitted `api_key`.

## Secret boundary

Environment variables are preferred for the Gateway process. The optional file fallback is configured with `ISM_AI_SECRET_FILE`, defaults to `gateway/secrets/ai-vault.json`, is written atomically, and is forced to mode `0600`. The `gateway/secrets/` path and key files are excluded by `.gitignore`.

The Android client sends only the Gateway token during normal remote processing. Provider credentials stay on the processing server. The interface exposes secret names for diagnostics only; it never returns secret values.

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/v1/ai/providers` | List providers, models, health snapshots, and secret names only. |
| `POST` | `/v1/ai/providers` | Add an owner-supplied custom provider and optional model. |
| `PATCH` | `/v1/ai/providers/{id}` | Update metadata or replace an owner-supplied key. |
| `DELETE` | `/v1/ai/providers/{id}` | Remove a custom provider and its file-backed credential. |
| `POST` | `/v1/ai/providers/{id}/enable` | Enable a provider. |
| `POST` | `/v1/ai/providers/{id}/disable` | Disable a provider without deleting metadata. |
| `POST` | `/v1/ai/providers/{id}/health` | Run and persist a redacted health probe. |
| `GET` | `/v1/ai/models` | List registered model metadata. |

## Safety boundaries

The registry does not harvest credentials from GitHub, websites, chat logs, or third parties. It stores only credentials explicitly submitted by the owner or supplied to the Gateway process through its environment. Key rotation is for availability and owner-managed configuration; it is not a mechanism for bypassing provider quotas, account limits, or enforcement.

The provider router is intentionally not advertised as complete until provider adapters, fallback behavior, and quota semantics are implemented and tested end to end. The current slice provides the registry, secret boundary, health matrix, and a safe integration point for that later adapter layer.
