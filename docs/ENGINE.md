# PUBLIKCLIP Engine

## الغرض

`PUBLIKCLIP Engine` هو الحد البرمجي العام بين Backend وخط معالجة الفيديو. يملك المحرك دورة حياة الـjob، وتركيب المراحل، وقراءة وكتابة checkpoints، وحالة SQLite الخاصة بخط المعالجة، وأحداث التقدم، وتجميع النتائج، ومسار إعادة render للمقطع. تبقى خوارزميات `ingest` و`ASR` و`diarization` و`events` و`candidates` و`scoring` و`camera` و`render` في وحداتها الحالية؛ لا يعيد هذا الحد تنفيذها.

> **قاعدة الاعتماد:** Backend يعتمد على `publikclip_pipeline.engine` أو على `PipelineFacadeEngine` الموجود في `backend/engine.py`. لا يجوز له استيراد `jobs.queue` أو وحدات المراحل أو renderer أو قراءة ملفات checkpoint مباشرة.

## البنية

```text
Backend JobManager
        │
        ▼
backend.engine.PipelineFacadeEngine
        │  public records/events/errors only
        ▼
publikclip_pipeline.engine.PipelineEngine
        │
        ▼
Pipeline stages + SQLite/checkpoints + artifacts
```

`PipelineFacadeEngine` هو adapter نقل داخل Backend. يستدعي `PipelineEngine` العام، ويحوّل `JobRef` و`JobResults` و`ProgressEvent` و`EngineError` إلى أنواع Backend الحالية. بقي `SubprocessPublikclipEngine` متاحًا كطبقة توافق اختيارية عندما يُضبط `PRIVATE_BACKEND_ENGINE_BIN`، لكنه ليس المسار الافتراضي.

## الواجهة العامة

تُصدّر الواجهة من `pipeline/publikclip_pipeline/engine/__init__.py`. الدوال التالية هي surface الرسمي القابل للاستدعاء من Backend؛ كل عملية lookup أو فشل معالجة يعبر الحد عبر `EngineError`.

| العملية | التوقيع | النتيجة |
|---|---|---|
| إنشاء job | `create_job(source, settings=None, *, source_type=None)` | `JobRef` بهوية ثابتة |
| بدء job | `start_job(job_id, on_progress=None)` | `JobResults` بعد اكتمال التشغيل |
| قراءة الحالة | `get_status(job_id)` | `JobStatus` durable |
| قراءة التقدم | `get_progress(job_id)` | mapping يحوي آخر progress محفوظ والمراحل |
| إلغاء job | `cancel_job(job_id)` | `JobStatus` بعد تثبيت طلب الإلغاء |
| استئناف job | `resume_job(job_id, settings=None, on_progress=None)` | `JobResults` لنفس الهوية |
| قراءة النتائج | `get_results(job_id)` | `JobResults` من checkpoints |
| إعادة render | `render_clip(job_id, clip_index, on_progress=None)` | artifact للمقطع |

تظل الأسماء `get_job_status` و`status` و`progress` و`get_job_results` و`results` aliases للتوافق مع CLI والاختبارات القديمة. لا يحتاج المستهلك الجديد إلى استخدامها.

## دورة الحياة

عند `create_job` يتحقق المحرك من أن المصدر غير فارغ ومن أن `source_type` هو `file` أو `url`، ثم يحفظ settings snapshot وينشئ هوية job ومجلدها. لا تُحمّل نماذج ASR أو الرؤية في هذه الخطوة.

عند `start_job` تُنفّذ المراحل بترتيب ثابت: `ingest`، ثم `ASR`، ثم `diarization`، ثم `events`، ثم `candidates`، ثم `scoring`، ثم `camera`، ثم `render`. بعد نجاح كل مرحلة يُكتب checkpoint ذريًا وتُسجل المرحلة كـ`done`. المرحلة التي تملك checkpoint صالحًا لا تُعاد عند resume.

| حالة Engine | المعنى | العمليات الأساسية المسموحة |
|---|---|---|
| `pending` | job منشأ ولم يبدأ | start، cancel، resume |
| `running` | مرحلة قيد التنفيذ | cancel |
| `done` | كل المراحل المطلوبة مكتملة | status، progress، results، render_clip |
| `failed` | فشل محفوظ مع رمز آمن | status، progress، results، resume |
| `cancelled` | إلغاء مثبت دائمًا | status، progress، resume |

لا يغير `resume_job` هوية job. يمسح علامة الإلغاء فقط، ثم يعيد تشغيل المراحل التي لا تملك checkpoint صالحًا أو artifact مطلوبًا. لا ينشئ Backend job داخليًا جديدًا ولا ينسخ checkpoints إلى مسار بديل.

## التقدم والإلغاء

يستقبل callback التقدم كائن `ProgressEvent` يحتوي `job_id` و`stage` و`fraction` و`message`. تكون `fraction` بين `0.0` و`1.0` أو `-1.0` عندما تكون النسبة غير معروفة. تُحفظ آخر إشارة في SQLite كي يستطيع `get_progress` و`get_status` تقديم projection قابلة للاستعادة بعد restart.

يثبت `cancel_job` علامة الإلغاء قبل إرجاع الحالة. يتوقف runner عند أقرب boundary آمن بين المراحل، ويحفظ حالة `cancelled` ورمز `JOB_CANCELLED`. لا يعيد المحرك job إلى `running` تلقائيًا؛ يلزم استدعاء `resume_job` صريح.

## الأخطاء

كل الأخطاء التي تصل إلى المستهلك هي `EngineError`، ولا تُكشف raw traceback أو أسرار provider أو مسارات التنفيذ الداخلية.

| الرمز | المعنى | قابلية الاستئناف |
|---|---|---|
| `JOB_NOT_FOUND` | هوية غير موجودة | لا |
| `INVALID_SOURCE` | مصدر غير صالح | لا |
| `INVALID_JOB_SETTINGS` | settings غير قابلة للتسلسل أو تالفة | لا |
| `JOB_BUSY` | محاولة تشغيل job قيد التشغيل | نعم بعد انتهاء التشغيل الحالي |
| `JOB_CANCELLED` | إلغاء مقصود ومحفوظ | لا كإعادة تلقائية؛ نعم عبر resume الصريح |
| `JOB_NOT_CANCELLABLE` | محاولة إلغاء job مكتمل | لا |
| `CLIP_NOT_FOUND` | index غير صالح أو غير موجود | لا |
| `CLIP_RENDER_FAILED` | فشل إعادة render | نعم |
| `ENGINE_FAILED` | فشل غير مصنف عند الحد العام | نعم عندما تكون `recoverable=True` |

## مسؤولية Backend

يحافظ Backend على job HTTP الخاص به وعلى worker scheduling وauth وmapping إلى state vocabulary الخاصة به. عند استخدام `PipelineFacadeEngine`، يمرر source وoptions إلى `create_job`، ويحفظ `engine_job_id` الذي يعيده المحرك، ثم يستخدم callback لتحويل `ProgressEvent` إلى حالة Backend. النتائج تأتي من `JobResults`؛ لا يقرأ Backend `render.json` أو أي checkpoint بنفسه.

تُحفظ artifacts في `PUBLIKCLIP_HOME/jobs/<engine_job_id>/clips`. يعيد adapter للـBackend metadata آمنة مثل `clip` و`filename` و`bytes` و`download_ready`، بينما يبقى path المطلق داخل طبقة المحرك ولا يدخل schema HTTP.

## الاختبار والتشغيل

يمكن تشغيل اختبارات العقد والحدود من جذر المستودع بالأوامر التالية:

```bash
PYTHONPATH=pipeline python3 -m pytest -q pipeline/tests/test_engine.py
PYTHONPATH=pipeline python3 -m pytest -q backend/tests/test_backend_engine.py backend/tests/test_api.py
```

تغطي الاختبارات إنشاء وتشغيل job، progress events، checkpoint reuse، failure translation، cancel/resume، aliases الرسمية، ترجمة نتائج adapter، وتدفق HTTP مع artifact قابل للتنزيل. تشغيل pipeline الحقيقي يحتاج dependencies وmedia وFFmpeg والنماذج المتاحة؛ لا يُعد نجاح اختبارات العقد دليلًا على إنتاج MP4 حقيقي.

## الملفات المرجعية

| الملف | الدور |
|---|---|
| [`pipeline/publikclip_pipeline/engine/__init__.py`](../pipeline/publikclip_pipeline/engine/__init__.py) | exports العامة |
| [`pipeline/publikclip_pipeline/engine/contracts.py`](../pipeline/publikclip_pipeline/engine/contracts.py) | records وerrors وProtocol |
| [`pipeline/publikclip_pipeline/engine/pipeline.py`](../pipeline/publikclip_pipeline/engine/pipeline.py) | orchestration facade |
| [`backend/engine.py`](../backend/engine.py) | Backend adapter والـerror/event translation |
| [`backend/service.py`](../backend/service.py) | worker lifecycle وBackend state |
| [`pipeline/tests/test_engine.py`](../pipeline/tests/test_engine.py) | contract tests |
| [`backend/tests/test_backend_engine.py`](../backend/tests/test_backend_engine.py) | adapter tests |
