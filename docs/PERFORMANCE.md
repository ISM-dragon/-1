# Performance baseline

Optimization is evidence-driven. Before a large change, capture a representative short, long, and multi-speaker fixture on the private processing host.

## Measurements

| Metric | Measurement point |
|---|---|
| Total processing time | Job creation to validated final manifest. |
| ASR time | ASR stage start/end. |
| Diarization time | Diarization stage start/end. |
| Events/scoring time | Event and scoring stage start/end. |
| Camera time | Camera stage start/end. |
| Render time | FFmpeg invocation and final probe. |
| CPU/RAM/GPU | Host-level process/resource samples per stage. |
| Disk | Source, checkpoint, temporary, and output peaks. |
| Android upload/download | Bytes, elapsed time, retries, and battery/network behavior. |

## Operating constraints

The Android client must not load the desktop ML graph or model weights. Uploads are resumable and bounded. The Gateway uses a bounded worker queue so a personal host does not start unbounded model processes. Model caching avoids repeated downloads/loads where supported.

## Acceptance rule

An optimization is accepted only if it improves the measured target without reducing result correctness, reliability, or recoverability. Any change that increases peak memory or disk consumption must document the new bound and failure behavior.
