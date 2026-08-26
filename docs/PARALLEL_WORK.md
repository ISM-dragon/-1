# ISM / PublikClip — Parallel Work Plan

**الغرض:** تقسيم العمل القادم بعد هذا التدقيق إلى مسارات قابلة للتوازي، من دون تحويل المشروع إلى إعادة كتابة أو السماح بتطوير backendين متنافسين.

## قاعدة العمل

العمل المتوازي مسموح عندما تكون حدود الملفات والعقد واضحة. لا يبدأ أي مسار implementation قبل تثبيت `docs/CONTRACTS.md` كمرجع، ولا يغير أي مسار Engine algorithms أو يحذف functionality في هذه المرحلة. كل تغيير يجب أن يملك اختبارًا وعقدًا وتفسيرًا لتأثير restart/resume.

## ownership canonical

| Owner | المكونات | المسؤولية |
|---|---|---|
| Architecture/Audit | `docs/`, root handoff، قرارات الحدود | تثبيت topology، توثيق المخاطر، منع scope drift، قبول interfaces |
| Engine owner | `pipeline/publikclip_pipeline/engine/`, `jobs/queue.py`، CLI adapter | الحفاظ على `ProcessingEngine` v1، checkpoint semantics، progress/error mapping، engine tests |
| Pipeline runtime owner | stages، model registry، FFmpeg، `pipeline/pyproject.toml` و`uv.lock` | reproducible Python 3.12 runtime، model manifest/checksum، media smoke، لا UI ولا auth |
| Gateway owner | `gateway/` وdeployment files | private auth، upload/storage، job state، worker supervision، adapter إلى Engine، error envelope |
| Android owner | `android/` | APK الشخصي، import/URI، WorkManager، resumable upload، polling، cache/download/export، لا Python |
| Desktop/UI owner | `app/src/` و`app/src-tauri/` | desktop review UX وGateway adapter، إبقاء local CLI compatibility، لا أسرار server |
| Release/CI owner | `.github/`, Gradle/Tauri packaging | JDK/SDK/toolchain، APK artifact، desktop artifact، clean smoke، signing خارج Git |
| Social optional owner | social/provider/analytics modules | live OAuth/publish فقط بعد processing baseline، mock واضح development-only |

## workstreams

### W0 — Contract freeze and repository guardrails

**Owner:** Architecture/Audit.
**الاعتماد:** لا شيء.
**النطاق:** تثبيت Gateway canonical لمسار Android، تثبيت Engine v1، تحديد APK artifact الواحد، ومراجعة أي PR ضد `ARCHITECTURE.md` و`CONTRACTS.md`.
**غير مسموح:** إضافة endpoint SaaS أو نقل pipeline إلى Android.
**المخرج:** قرار مكتوب، labels للـlegacy، ومصفوفة acceptance مشتركة.

### W1 — Engine adapter hardening

**Owner:** Engine owner.
**يمكن أن يبدأ بالتوازي مع W2 وW3 بعد W0.
**النطاق:** اختبار adapter مباشر Gateway → `ProcessingEngine`، مع إبقاء JSONL fallback؛ توحيد `EngineError` إلى public error codes؛ إضافة contract tests لـcreate/start/status/results/cancel/resume/render.
**ملفات حساسة:** `pipeline/publikclip_pipeline/engine/*`, `pipeline/publikclip_pipeline/jobs/queue.py`, `pipeline/publikclip_pipeline/cli.py`.
**لا يلمس:** Android UI أو social publishing.

### W2 — Server runtime and model readiness

**Owner:** Pipeline runtime owner.
**يمكن أن يبدأ بالتوازي مع W1 وW3.
**النطاق:** تشغيل Python 3.12 عبر `uv sync`، import check، FFmpeg/ffprobe/libass، مساحة disk، cache locations، model download/checksum، وfixture media smoke.
**ملفات حساسة:** `pipeline/pyproject.toml`, `pipeline/uv.lock`, `pipeline/publikclip_pipeline/models/*`, `pipeline/publikclip_pipeline/render/*`, Dockerfile.
**لا يلمس:** API routes أو Android payloads إلا عبر contract issue.

### W3 — Gateway correctness and private deployment

**Owner:** Gateway owner.
**يمكن أن يبدأ بالتوازي مع W1 وW2؛ يحتاج نتائج W2 قبل readiness النهائي.
**النطاق:** إزالة duplicate routes، global error envelope، production fail-closed auth، source/FFprobe validation، single-instance queue assertion، persistence/recovery tests، وتحويل processing adapter إلى Engine عند ثباته.
**ملفات حساسة:** `gateway/main.py`, `gateway/processing_service.py`, `gateway/worker_queue.py`, `Dockerfile.gateway`, `docker-compose.gateway.yml`, env examples.
**لا يضيف:** OAuth adapters أو user accounts كجزء من P0.

### W4 — Android remote client

**Owner:** Android owner.
**الاعتماد:** W0 ونسخة أولى من W3 contract.
**النطاق:** اختيار source semantics، resumable upload، idempotency persistence، polling/reconnect، cancel/resume، error envelope، secure token/device ID، artifact checksum/cache، وWorkManager behavior.
**ملفات حساسة:** `VideoProcessingWorker.kt`, `ProcessingGatewayClient.kt`, Gateway settings/repository.
**قرار مطلوب:** native `android/` هو APK canonical؛ Tauri generated Android لا يدخل release حتى قرار مختلف.

### W5 — APK packaging and device smoke

**Owner:** Release/CI owner.
**الاعتماد:** W4 وقرار identity.
**النطاق:** JDK 17 toolchain في CI، debug APK artifact، emulator/device smoke، network route إلى private Gateway، release signing secrets، backup policy، وmanifest permissions.
**ملفات حساسة:** `android/app/build.gradle.kts`, `AndroidManifest.xml`, `.github/workflows/android-build.yml`.
**لا يغير:** processing algorithm.

### W6 — Desktop/Tauri compatibility

**Owner:** Desktop/UI owner.
**يمكن أن يبدأ بعد W0 بالتوازي.
**النطاق:** إبقاء desktop local pipeline path، اختبار resource staging، توحيد Gateway API adapter تدريجيًا، ووسم local Instagram loop.
**ملفات حساسة:** `app/src/api.ts`, `app/src/App.tsx`, `app/src-tauri/src/lib.rs`, `app/scripts/prepare-resources.mjs`.
**لا يفرض:** Tauri architecture على native Android.

### W7 — Social optional surface

**Owner:** Social optional owner.
**الاعتماد:** W0، ويجب ألا يحجب W1–W5.
**النطاق:** adapters رسمية منفصلة، OAuth state/nonce، token vault، publish idempotency، capability restrictions، analytics provenance.
**القاعدة:** mock routes تستمر للاختبار فقط وتبقى `development_only=true`; لا تُعرض كجاهزية إنتاج.

## ترتيب التنفيذ المقترح

| الدفعة | الأعمال | شرط الانتقال |
|---|---|---|
| Gate A | W0 + إصلاح test harness في P2 | عقد واحد وAPK واحد وGateway canonical |
| Gate B | W1 + W2 + جزء readiness من W3 | Engine smoke server-side وmodel/FFmpeg readiness حقيقي |
| Gate C | بقية W3 + W4 | upload/status/results يعملان بعقد موحد مع auth/error envelope |
| Gate D | W5 + fixture end-to-end | APK يثبت ويتصل ويستعيد job وينزّل MP4 |
| Gate E | W6 ثم W7 عند الحاجة | لا regression في desktop؛ social لا يغير processing baseline |

## مناطق التعارض الممنوع

| التعارض | القرار |
|---|---|
| W1 وW3 يغيران معنى `JobStatus` أو `EngineError` بالتوازي | يملك W0 schema؛ أي تعديل يمر عبر contract review |
| W3 وW4 يغيران upload payload في الوقت نفسه | Gateway يثبت request/response أولًا، Android يستهلكه بعدها |
| W4 وW5 يغيران applicationId/namespace معًا | Release owner يملك identity migration؛ لا تغييرات صامتة |
| أي مسار يضيف dependency ثقيلة | يرفق benchmark/سبب/بديل وقرار Architecture قبل الدمج |
| Social work يغير auth/storage المستخدمة في processing | ممنوع؛ social يستعمل حدود Gateway نفسها ولا يكسرها |
| native Android وTauri generated Android ينشران artifactين | ممنوع حتى توحيد product identity وsupport policy |

## Definition of Done المشترك

لا يعتبر أي workstream مكتملًا بوجود كود فقط. يجب أن يثبت contract، validation/error behavior، persistence impact، restart/resume behavior، اختبارًا آليًا أو smoke مناسبًا، وتوثيقًا قصيرًا. إذا تعذر اختبار device/provider خارجي، يسجل السبب كـ`NOT RUN` ولا يستبدل بمحاكاة نجاح.

### المراجع

[1]: ./ARCHITECTURE.md "Canonical topology and ownership"
[2]: ./AUDIT.md "Prioritized repository audit"
[3]: ./CONTRACTS.md "Android/Gateway/Engine contracts"
[4]: ../.github/workflows/android-build.yml "Current Android CI workflow"
[5]: ../app/src-tauri/src/lib.rs "Desktop/Tauri runtime boundary"
