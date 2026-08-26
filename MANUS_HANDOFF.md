# MANUS HANDOFF — ISM Android application

**المشروع:** ISM / PublikClip  
**المستودع:** `ISM-dragon/-1`  
**الدور المنفذ:** Agent 01 — ARCHITECT + AUDIT  
**النطاق:** توثيق وتدقيق ودمج آمن فقط؛ لا إعادة كتابة للمشروع ولا حذف functionality.  
**Engine contract:** v1  
**القرار المعماري:** `Android APK → Private Gateway → PublikClip Engine → AI/Media Runtime`.

## القرار التنفيذي

المحرك الموجود في `pipeline/` هو نواة المعالجة المعتمدة. لا يجب تشغيل Python أو `uv` أو WhisperX أو PyTorch على Android. تطبيق Android الشخصي هو stateful client فوق Gateway: يختار الفيديو، يرفع المصدر، ينشئ الوظيفة، يعرض الحالة والتقدم، ينزّل النتائج، ويقدم المعاينة والتحرير الأساسي. Gateway هو المصدر authoritative لحالة الوظيفة والتقدم والنتائج، ولا تُنقل تفاصيل Python أو Pipeline الداخلية إلى الواجهة.

اختير `gateway/` ليكون الـprivate backend canonical لمسار Android لأن العميل الحالي يستهدفه ولأنه يضم upload lifecycle وprocessing state وworker supervision وdiagnostics وserver-side secret boundary. مجلد `backend/` ليس محذوفًا، لكنه stack بديل/legacy؛ لا ينبغي إضافة features جديدة إليه بالتوازي قبل قرار دمج صريح.

## البنية الحالية ذات الصلة

| الطبقة | الملف/المكوّن | المسؤولية الحالية |
|---|---|---|
| Engine | `pipeline/publikclip_pipeline/engine/` | `ProcessingEngine` و`PipelineEngine` وlifecycle وcheckpoint-backed results |
| Pipeline | `pipeline/publikclip_pipeline/` | ingest، ASR، diarization، events، candidates، scoring، camera، captions، render |
| Gateway | `gateway/` | auth، SQLite، upload/storage، workers، diagnostics، provider registry، secret boundary |
| Android contract | `android/app/src/main/java/com/example/data/contract/` و`remote/` | نماذج الحالة والعقد وGateway client للعميل الشخصي |
| Android orchestration | `RemoteProcessingCoordinator.kt`, `RemoteProcessingWorker.kt`, `GatewayProcessingWorker.kt` | unique WorkManager، upload، create/poll/cancel/retry/resume، download والتحقق |
| Android UI | `RemoteStudioScreens.kt`, `RemoteStudioViewModel.kt` وCompose screens | Home، import، processing، error، results، clip review، settings |
| Desktop | `app/src/` و`app/src-tauri/` | React/Tauri local CLI path وGateway adapter وreview/social UX |
| Backend البديل | `backend/` | FastAPI/DB/storage/JobManager مستقل؛ لا يستعمله Android canonical |

## Android flow

يستخدم التطبيق native Android ومسار Gateway بعيدًا. يدعم اختيار `video/*` مع persistable URI permission، حفظ الوظيفة في التخزين المحلي، منع duplicate work عبر `ExistingWorkPolicy.KEEP`، إعادة polling بعد restart، وإظهار `recoverable` قبل retry. لا ينشئ job ثانية إذا كانت `remote_job_id` و`idempotency_key` محفوظتين. عند إغلاق التطبيق أثناء المعالجة يعاود WorkManager التنفيذ من الحالة المحفوظة.

| الحالة | سلوك Android |
|---|---|
| انقطاع الشبكة | network constraint وexponential backoff، مع إبقاء الوظيفة محفوظة |
| إعادة تشغيل التطبيق | قراءة local/remote IDs وإعادة enqueue للعمل نفسه |
| job running | الاستعلام باستخدام `remote_job_id` دون إنشاء job جديدة |
| `INTERRUPTED` | استدعاء `/resume` ثم متابعة polling |
| `FAILED` | عرض error code/message، وإظهار retry فقط عند `recoverable=true` |
| cancel | استدعاء `/cancel` ثم حفظ الحالة النهائية وإيقاف unique work |
| outputs فارغة | فشل آمن `NO_VALID_CLIPS` بدل فتح Review فارغ |

## عقد Engine وMedia

```python
from publikclip_pipeline.engine import PipelineEngine

engine = PipelineEngine()
job = engine.create_job(source, settings=None, source_type=None)
status = engine.get_job_status(job.id)
result = engine.start_job(job.id, on_progress=callback)
clip = engine.get_clip(job.id, 0)
updated = engine.render_clip(job.id, 0, on_progress=callback)
```

الـcallback يستقبل `ProgressEvent` لا تفاصيل `StageContext`. الأخطاء العامة هي `EngineError` مع `code` و`safe_message` و`recoverable`؛ لا تعبر stack traces أو secrets الحد العام. يحتفظ CLI بصيغة JSONL التوافقية: `job` ثم `progress` ثم `result`.

يدعم Gateway resumable upload عبر `POST /v1/sources/uploads`، و`GET` للحالة، و`PUT` للـchunks، و`POST /complete` للتحقق من الحجم وSHA-256 والـatomic finalize. تُحفظ الأجزاء المؤقتة داخل storage خاص ولا يُكشف filesystem path. تُفحص outputs قبل إعادتها مع bytes وSHA-256 وintegrity metadata. يبقى `/v1/sources/upload` one-shot للتوافق، لكن العميل canonical يجب أن يستخدم session contract.

## API المستخدم

| Method | Path | الاستخدام |
|---|---|---|
| `GET` | `/health` | readiness عام محدود |
| `GET` | `/v1/auth/session` | session/device status دون user accounts |
| `GET` | `/v1/processing/capabilities` | pipeline/FFmpeg/storage/provider capabilities |
| `POST` | `/v1/sources/uploads` | بدء أو استئناف upload session |
| `GET` | `/v1/sources/uploads/{id}` | offset/progress |
| `PUT` | `/v1/sources/uploads/{id}` | chunk upload |
| `POST` | `/v1/sources/uploads/{id}/complete` | finalize/checksum |
| `POST` | `/v1/processing/jobs` | إنشاء job idempotent |
| `GET` | `/v1/processing/jobs/{id}` | state/progress/results |
| `POST` | `/v1/processing/jobs/{id}/cancel` | طلب الإلغاء |
| `POST` | `/v1/processing/jobs/{id}/retry` | retry محدود |
| `POST` | `/v1/processing/jobs/{id}/resume` | checkpoint resume |
| `GET` | `/v1/processing/jobs/{id}/media/{filename}` | تنزيل artifact محمي |

يستخدم العميل `Authorization: Bearer <gateway-session-token>`، ويفضل `X-Device-ID` و`X-Request-ID`. لا يوجد user account أو billing أو subscription. لا يرسل Android Gemini key؛ المفاتيح تبقى في Gateway/AI runtime.

## الاختبارات والتحقق

تم تسجيل baseline Agent 01 قبل دمج آخر remote changes: React/Vite build وPython `compileall` نجحا؛ `gateway/tests` نجحت بـ`29 passed, 1 skipped`؛ `backend/tests` نجحت بـ`6 passed`؛ و`pipeline/tests` نجحت بـ`97 passed` بعد تجهيز scipy/librosa في sandbox. full `pytest gateway` كان فيه فشل واحد في root bridge test بسبب قراءة `media_uploads` قبل `init_db()`.

في بيئة Agent 01 لم يثبت Android Gradle/APK لأن Java runtime كان موجودًا دون `javac`، ولم يُشغّل Docker أو device smoke. توجد في GitHub تغييرات لاحقة أضافت Android contract/remote classes واختبارات APK، لكن اختبار CI أو جهاز فعلي يبقى المرجع النهائي وليس ادعاءً مشتقًا من وجود الملفات فقط.

## ما يعمل وما لا يمثل production

`PROVIDER_MODE=mock` وmock OAuth/publisher development-only ولا ينشران إلى حسابات حقيقية. المسار المحلي في `ProductionVideoPipeline.kt` ليس parity مع Python WhisperX/PyTorch؛ يجب ألا يُعلن بديلًا عن Server Engine. كما أن Tauri generated Android مسار مستقل عن native `android/` ولا يدخل release حتى توحيد الهوية.

## الأولويات المتبقية

| الأولوية | المطلوب |
|---|---|
| P0 | تثبيت Gateway كـbackend واحد، إثبات APK import → process → render → download، وفرض auth/readiness في remote deployment |
| P1 | توحيد error envelope، إكمال Android resumable upload/idempotency، تثبيت model hashes، إزالة duplicate Gateway routes، وتوثيق single-instance worker |
| P2 | إصلاح test initialization، توحيد native/generated identity، ضمان release signing، مراجعة backup والمواءمة بين URL/local-file UI contract |

## حدود التغيير

لم تُحذف الوظائف، ولم تُنقل stages، ولم تُعدّل خوارزميات scoring أو diarization أو camera أو rendering. لا يجوز أن ينتقل Python أو model runtime إلى APK. أي تنفيذ لاحق يجب أن يحافظ على checkpoint names أو يقدم migration صريحًا، وأن يمر عبر `docs/ARCHITECTURE.md` و`docs/CONTRACTS.md`.

### المراجع

[1]: docs/ARCHITECTURE.md "Architecture baseline"
[2]: docs/AUDIT.md "Repository audit and test evidence"
[3]: docs/CONTRACTS.md "Preliminary API and Engine contracts"
[4]: docs/PARALLEL_WORK.md "Ownership and parallel work plan"
[5]: pipeline/publikclip_pipeline/engine/contracts.py "Engine contract v1"
[6]: gateway/main.py "Canonical Gateway implementation candidate"
[7]: android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Android routing and WorkManager"
