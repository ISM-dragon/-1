# ISM Provider Health Matrix

The Gateway distinguishes **configured** from **ready**. A credential reference being present does not prove that the provider accepts the credential, exposes the selected model, or supports the requested capability.

| State | Meaning | User action |
|---|---|---|
| `READY` | The provider responded successfully to the health probe and the configured capability path is available. | No action required. |
| `NOT_CONFIGURED` | No owner-supplied credential is available, or the provider is disabled. | Add the credential through the Gateway or enable the provider. |
| `NETWORK_ERROR` | The Gateway could not connect to the provider endpoint. | Check DNS, firewall, URL, and outbound access. |
| `AUTH_ERROR` | The provider rejected authentication. | Replace the owner-supplied credential; do not retry indefinitely. |
| `MODEL_ERROR` | The endpoint is reachable but the requested model/path is unavailable. | Choose an available model or correct the endpoint. |
| `CAPABILITY_ERROR` | The provider does not satisfy the task capability. | Select a provider with the required capability. |
| `RATE_LIMITED` | The provider returned a quota or rate-limit response. | Respect the provider's retry window and project quota. |
| `TIMEOUT` | The health probe exceeded its timeout. | Check the endpoint and network latency. |
| `UNKNOWN_ERROR` | The probe returned an unclassified failure. | Inspect the Gateway error without exposing credentials. |

Health records store state, reachability, authentication result, model availability, required capabilities, timestamp, latency, and a redacted error string. They do not store API-key values.

The web API is:

- `GET /v1/ai/providers`
- `GET /v1/ai/providers/{provider_id}`
- `POST /v1/ai/providers/{provider_id}/health`
- `GET /v1/ai/models`
- `GET /v1/diagnostics/workers`

All routes require the Gateway token when `GATEWAY_TOKEN` is configured.
