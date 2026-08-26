# AI runtime

All heavyweight AI execution is owned by the private processing host. The Android APK receives progress and validated results; it does not carry Python ML dependencies or provider secrets.

## Components

| Capability | Canonical runtime | Android responsibility |
|---|---|---|
| ASR and word alignment | WhisperX/VAD/alignment stages in the Python pipeline | Display transcript and caption preview. |
| Diarization | Python diarization stage | Display speaker-aware results when returned. |
| Audio events | Laughter, energy, arousal, and related event stages | No duplicate transcription or event inference. |
| Text/vision scoring | Gateway-configured provider router and pipeline scoring | Display score, confidence, provenance, and fallback state. |
| Face/camera signals | Python camera and visual analysis stages | Review crop/result; no silent local replacement. |

## Model metadata

Each managed model should have a name, version, size, checksum, source, local path, installed state, health, and last verification time. Downloads must be resumable and verified before use. A missing or corrupt model produces `MODEL_MISSING` or `MODEL_INVALID`; it must not be reported as a generic crash.

## Provider failure policy

A provider is selected by task capability, health, configuration, cost/latency policy, and explicit fallback order. A failed LLM request must either use a safe deterministic/local fallback or return a structured degraded result. A mock success response is not acceptable in production processing.

## Readiness

Gateway readiness distinguishes configuration from runtime verification. A configured provider key is not proof that the provider is reachable, and a present pipeline directory is not proof that every heavy import/model is healthy. Capability responses must expose the distinction so Android can show an actionable operator message.

## Privacy

Provider keys, model caches, and raw media stay on the private processing host. Logs redact authorization headers, credentials, and private filesystem paths. The Android app stores only the private Gateway session configuration required to use the service.
