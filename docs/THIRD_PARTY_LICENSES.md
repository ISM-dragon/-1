# Third-party licenses and provenance

**Review date:** 2026-08-26

## Repository licenses

| Component | License/provenance | Integration decision |
|---|---|---|
| Primary repository `ISM-dragon/-1` | AGPL-3.0-or-later, as declared by the repository license files and existing documentation | Remains the governing project license. |
| Supplied `supoclip-main` archive | Root `LICENSE` declares MIT | Used for inspection and architectural comparison only; no source copied. |

## Reference archive review

The archive contains a root `LICENSE` but no separate file-level license notices were found in the supplied top-level, backend, frontend, or MCP inventory. That does not automatically relicense third-party dependencies. The reference lockfiles and manifests identify independent packages whose licenses must be checked from their upstream distributions before any direct reuse.

The following reference dependencies were observed during audit but were **not added** to the primary repository: FastAPI, uvicorn, openai-whisper, pydantic-ai, asyncpg, SQLAlchemy, Alembic, yt-dlp, AssemblyAI, MediaPipe, aiofiles, sse-starlette, ARQ, Redis, boto3, Next.js, Prisma, Better Auth, Stripe, Radix UI, MediaBunny, and related packages. Their presence in the archive is not provenance for the primary implementation.

## Primary runtime provenance

The primary pipeline already documents and packages its own dependencies in `pipeline/pyproject.toml`, `gateway/requirements.txt`, and Android Gradle version catalogs. Model downloads remain runtime data and are not committed into the repository. FFmpeg remains an external runtime dependency on the private processing host and is not bundled into the APK.

## Copying policy

No file or code fragment has been copied from the reference archive. The implementation uses independent code in the existing primary repository and records reference-derived ideas as architectural decisions. If future work imports a source component, the contributor must record the exact upstream URL, commit/version, license, notice requirements, files changed, and compatibility review in this document before merging.

## Secret and artifact policy

API keys, OAuth tokens, keystores, passwords, model weights, downloaded media, release artifacts, and generated build caches must not be committed. License notices included by existing dependencies must remain intact. A dependency may be introduced only after its license is compatible with project distribution and its operational cost is justified by a tested requirement.
