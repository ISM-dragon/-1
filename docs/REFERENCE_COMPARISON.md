# Reference comparison

**Date:** 2026-08-26
**Primary repository:** `ISM-dragon/-1`
**Reference:** supplied `supoclip-main` archive

## Executive conclusion

The reference project is a useful architectural reference but is not a replacement for the primary repository. The primary repository already contains the deeper video intelligence pipeline, explainable scoring, camera stage, captions, durable checkpointing, a FastAPI Gateway, and an Android client. The reference project adds useful patterns around service separation, media validation, model/runtime awareness, and a mobile-first workflow, but its backend dependency footprint is substantially heavier and includes multi-user, billing, database, cache, object storage, and MCP-oriented concerns explicitly excluded from this personal application.

> Decision: retain the primary pipeline and Gateway as the canonical processing plane; adapt selected ideas independently and do not copy the reference repository wholesale.

## Feature-level comparison

| Area | Primary repository | Reference archive | Decision |
|---|---|---|---|
| Android | Native Compose client with Room, WorkManager, Media3, notifications, and remote Gateway flow; also retains Tauri-generated Android shell | No equivalent native Android project found in the supplied archive | Keep primary Android implementation |
| Backend | FastAPI Gateway with SQLite job state, queue, auth, upload, cancel/retry/resume, artifact serving, and pipeline bridge | FastAPI backend with a broader service/data stack and heavier dependencies | Keep current; adapt boundary ideas only |
| Engine | Existing Python stages: ingest, ASR, diarization, events, candidates, scoring, camera, render | Backend-oriented processing services and Whisper/media integrations | Keep current stage graph and facade |
| Media | FFmpeg/ffprobe, normalized input, render validation, captions, artifact checks | Explicit media helpers and validation-oriented backend structure | Improve current with typed error/readiness contracts |
| AI | WhisperX/alignment, diarization, laughter/audio events, provider routing, explainable scoring, camera analysis | Whisper/OpenAI/AssemblyAI/MediaPipe-oriented alternatives | Keep current; no unbenchmarked replacement |
| Captions | Word timestamps, ASS styles, karaoke/prosodic emphasis, renderer-owned output | SRT-oriented media dependencies | Keep current canonical captions |
| Job lifecycle | Durable Gateway SQLite state, worker recovery, checkpoints, cancellation, retry/resume | Queue/cache-oriented dependencies including Redis/ARQ | Keep current for a private single-user service |
| Storage | Controlled local source/processing roots with path containment | PostgreSQL/S3-oriented dependencies | Ignore added cloud storage complexity |
| UI | Android workflow is being narrowed to Home → Import → Processing → Results → Review/Edit → Export | Next.js web frontend with broader product surfaces | Keep mobile-first Android; do not port web UI literally |
| Social/billing | Existing social surface is isolated and mock-first; billing is not central to Android flow | Includes broader product dependencies such as Stripe/auth | Ignore for this scope |
| Deployment | Private Gateway can run on a host/container with Python, FFmpeg, and models | Docker and service stack are available | Use the smallest viable private deployment |

## Non-functional comparison

The reference archive has a permissive MIT root license, but its dependency graph includes components with independent licenses that must be checked before reuse. The primary repository is AGPL-3.0-or-later. The safest path is independent reimplementation of ideas, with no copied source and no new dependency unless it solves a measured problem.

The reference backend assumes a service ecosystem that is unnecessary for a personal APK. Introducing PostgreSQL, Redis, S3, billing, or multi-user authentication would increase operational and security burden without improving the requested Android-to-private-engine path.

## Adopted ideas

The migration adopts the following ideas independently: explicit boundary ownership, runtime capability reporting, early media validation, stable user-safe error categories, and a mobile workflow that treats background processing and restoration as first-class behavior. These are documented in the migration plan and implemented without copying reference files.

## Rejected ideas

The migration rejects wholesale repository copying, replacement of WhisperX with another ASR stack without benchmark evidence, adoption of a larger cloud/database stack, multi-user/billing architecture, and UI parity with the reference web frontend.
