# PUBLIKCLIP ENGINE Handoff

## نطاق التسليم

تم تحويل نقطة الاستدعاء الافتراضية من Backend إلى `PipelineFacadeEngine` الذي يستدعي `publikclip_pipeline.engine.PipelineEngine` العام. بقيت خوارزميات `ingest` وASR وdiarization وevents وcandidates وscoring وcamera وrender كما هي؛ التغيير محصور في facade، translation، lifecycle wiring، الاختبارات، والتوثيق.

لم تُعدّل Android أو UI. لم تُضف FastAPI أو OAuth أو provider logic إلى pipeline.

## Boundary المعتمد

```text
Backend JobManager
  → backend.engine.PipelineFacadeEngine
  → publikclip_pipeline.engine.PipelineEngine
  → existing pipeline stages
```

Backend لا يستورد queue أو stages أو renderer ولا يقرأ checkpoint files. المحرك العام يملك stage ordering وSQLite/checkpoints وresume وprogress persistence وrender. Backend يملك auth وHTTP job state وworker scheduling وmapping إلى states الخارجية.

`SubprocessPublikclipEngine` موجود للتوافق، ويُستخدم فقط عند ضبط `PRIVATE_BACKEND_ENGINE_BIN`. عند عدم ضبطه، يستخدم `create_app()` الـ`PipelineFacadeEngine` إذا كان checkout الـpipeline موجودًا.

## Public facade API

| العملية | الاستخدام |
|---|---|
| `create_job` | إنشاء job وحفظ settings snapshot |
| `start_job` | تشغيل المراحل من checkpoint الحالي |
| `get_status` | قراءة حالة durable |
| `get_progress` | قراءة آخر progress محفوظ |
| `cancel_job` | تثبيت cancellation marker |
| `resume_job` | استئناف نفس job ID وإعادة الناقص فقط |
| `get_results` | قراءة `JobResults` checkpoint-backed |
| `render_clip` | إعادة render لمقطع واحد وتحديث artifact |

الأسماء القديمة `get_job_status` و`status` و`progress` و`get_job_results` و`results` بقيت aliases. لم يتغير `ENGINE_CONTRACT_VERSION` لأن الإضافة aliases فقط ولا تغير semantics الحقول أو lifecycle.

## Backend translation

`PipelineFacadeEngine.run()` هو compatibility entry point لـ`JobManager` الحالي، لكنه ينفذ داخليًا `create_job` ثم `start_job` أو `resume_job`. يحول progress إلى `EngineEvent`، و`JobResults` إلى result payload فيه `clips` وmetadata آمنة، ويحوّل public `EngineError` إلى Backend `EngineError` مع الحفاظ على code وrecoverable.

`PipelineFacadeEngine.render_clip()` يعيد metadata مثل `clip` و`filename` و`bytes` و`download_ready` ولا يعيد path المطلق. مسارات artifacts الداخلية تظل تحت `PUBLIKCLIP_HOME/jobs/<engine_job_id>/clips` ويستخدمها Storage boundary للتنزيل.

## Runtime

يمرر Backend `config.storage_root` كـ`PUBLIKCLIP_HOME` للـEngine، لذلك تظهر artifacts في نفس storage boundary التي يستخدمها download route. يلزم تثبيت pipeline dependencies وFFmpeg والنماذج لتشغيل stages الحقيقية. إنشاء job وقراءة الحالة والاختبارات contract لا تتطلب تحميل ASR models.

## Verification performed

| الاختبار | النتيجة |
|---|---|
| `PYTHONPATH=pipeline python3 -m pytest -q pipeline/tests/test_engine.py` | **9 passed** |
| `PYTHONPATH=pipeline python3 -m pytest -q backend/tests/test_backend_engine.py backend/tests/test_api.py` | **11 passed, 1 warning** |

اختبار Backend الجديد يشغل HTTP lifecycle كاملًا عبر `PipelineFacadeEngine` المحقون بواجهة public double، ويتحقق من وصول artifact وتنزيله. هذه اختبارات integration للعقد والحدود وليست تشغيلًا للنماذج أو إنتاج MP4 حقيقي.

## الملفات الأساسية

- [`docs/ENGINE.md`](docs/ENGINE.md): دليل التشغيل والحدود.
- [`docs/CONTRACTS.md`](docs/CONTRACTS.md): عقد Engine وسبب aliases.
- [`pipeline/publikclip_pipeline/engine/contracts.py`](pipeline/publikclip_pipeline/engine/contracts.py): public records وProtocol.
- [`pipeline/publikclip_pipeline/engine/pipeline.py`](pipeline/publikclip_pipeline/engine/pipeline.py): facade orchestration.
- [`backend/engine.py`](backend/engine.py): adapter والـerror/event translation.
- [`backend/service.py`](backend/service.py): worker lifecycle دون قراءة pipeline internals.
