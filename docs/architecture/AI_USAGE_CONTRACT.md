# AI Usage and Provider Registry Contract

The Gateway is the source of truth for remote AI usage. Provider profiles are returned without API keys or credential references; clients receive only `credential_configured`, provider metadata, model names, capabilities, and optional prices per million tokens.

`GET /v1/ai/providers` returns the configured registry. `GET /v1/ai/usage?days=30` returns aggregates grouped by provider and model. Each aggregate contains requests, input tokens, output tokens, total tokens, estimated request count, average latency, and calculated USD cost.

The pipeline writes one JSONL event per successful provider response under `PUBLIKCLIP_HOME/ai_usage.jsonl`. If the provider returns usage metadata, the event uses `usage_source=actual`. If usage metadata is absent, the pipeline derives a bounded character-based estimate and sets `usage_source=estimated`. Estimated records are displayed separately and must not be interpreted as provider-billed totals.

No client sends provider API keys to the Android or Tauri UI. Provider credentials remain server-side in environment variables or the Gateway secret store.
