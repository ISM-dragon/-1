# Test matrix

| Scenario | Layer | Expected result | Evidence |
|---|---|---|---|
| Normal short video | Pipeline/Gateway/Android | Job reaches completed and returns validated MP4 manifest. | Requires configured runtime for full E2E. |
| Long or large video | Gateway/storage | Upload and processing remain bounded; disk errors are classified. | Gateway tests and host benchmark. |
| No audio | Media/ASR | Media is rejected or ASR fallback is explicit; no unhandled crash. | Pipeline regression tests. |
| Broken media | Gateway/media | `MEDIA_INVALID` or `UNSUPPORTED_FORMAT`. | Upload/media tests. |
| Multiple speakers | Pipeline | Diarization/camera stages preserve speaker metadata. | Pipeline tests and fixture. |
| Fast speech | Pipeline/ASR | Word timing remains valid or the job fails safely. | ASR tests. |
| Missing model | Runtime/Gateway | Capability is degraded; job returns `MODEL_MISSING`. | Runtime tests. |
| Missing FFmpeg | Runtime/Gateway | Capability is degraded; job returns `FFMPEG_MISSING`. | Gateway tests. |
| LLM unavailable | Scoring | Safe fallback or structured degraded score. | Provider/scoring tests. |
| Cancellation | Gateway/Android | State reaches `CANCELLED` and is not resurrected by restart. | Gateway state tests and Android worker tests. |
| Resume | Engine/Gateway/Android | Valid checkpoints are reused; job ID remains stable. | Queue/checkpoint tests. |
| Gateway restart | Gateway | Non-terminal non-cancelled jobs are requeued. | Restart recovery evidence. |
| Network interruption | Android/Gateway | WorkManager retries; no duplicate job from the idempotency key. | Client contract tests. |
| Android process death | Android | Room/local store restores job and worker reconciliation. | Device/instrumentation test required. |
| Rendering failure | Pipeline/Gateway | `FFMPEG_FAILED` or render error is persisted with recoverability. | Render tests. |
| Release assembly | Android/CI | APK assembles without secrets; signing is injected only when configured. | Gradle build evidence. |
| Secret scan | Repository | No keys, tokens, keystores, or generated media are committed. | Git/CI checks. |

A compilation-only result is not acceptance. Real-device E2E remains a release gate because the current sandbox may not provide a stable Android SDK/emulator or a configured model/FFmpeg host.
