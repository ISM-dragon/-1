# Publikclip engine

The Python engine is the canonical heavy-processing plane. Its public facade is exposed from `pipeline/publikclip_pipeline/engine`, so Gateway code does not need to import individual stage implementations arbitrarily.

## Stage graph

```text
ingest → asr → diarization → events → candidates → scoring → camera → render
```

Each stage receives the prior stage's durable artifacts and settings, emits normalized progress, and writes an atomic checkpoint. A valid checkpoint is reused only when its schema and required artifacts still validate.

## Public operations

| Operation | Purpose |
|---|---|
| `create_job` | Validate source and settings and return stable job identity. |
| `start_job` | Run the stage graph, emitting progress and preserving checkpoints. |
| `get_status` / `get_progress` | Return durable status projections. |
| `cancel_job` | Persist cancellation and stop execution at the next safe boundary. |
| `resume_job` | Reuse valid checkpoints and continue incomplete work. |
| `get_results` | Return checkpoint-backed results and artifact metadata. |
| `render_clip` | Render or re-render one selected clip through the canonical renderer. |

## Error boundary

Implementation exceptions are converted at the facade boundary into `EngineError` with a stable code, safe message, and recoverability flag. Raw traces remain in operator logs only. Provider failures may produce a degraded, explicitly marked score when a safe fallback exists; otherwise the job fails with a structured error.

## Non-goals

The engine does not own Android navigation, Gateway authentication, provider OAuth, billing, or social account state. It does not assume that a client remains connected while a job runs.
