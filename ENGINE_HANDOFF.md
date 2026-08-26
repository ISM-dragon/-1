# PUBLIKCLIP ENGINE Handoff

## ملخص التسليم

أحدث remote يتضمن refactor مستقلًا لطبقة المحرك داخل `pipeline/publikclip_pipeline/engine/`. نقطة الاستدعاء العامة هي `PipelineEngine`، والعقد القابل للاستهلاك هو `ProcessingEngine`. هذا النطاق يحافظ على خوارزميات ASR وdiarization وevents وcandidates وscoring وcamera وrender الموجودة؛ المحرك مسؤول عن composition وlifecycle وcheckpoint access فقط.

لم تُعدّل هذه الجلسة Android UI أو Android project أو Gateway API أو التصميم المرئي. كما لم تُدمج تغييرات الجلسة الأخرى في commit جديد؛ تم العمل فوق `origin/main` الأحدث وإضافة progress aliases والتوثيق المطلوب فقط.

## عقد Backend

يُنشئ Backend instance من `PipelineEngine` داخل worker process أو worker thread، ثم يستخدم الدوال التالية. النتائج هي dataclasses immutable، وتتحول إلى JSON عبر `.to_dict()`؛ أحداث التقدم هي `ProgressEvent` وتتحول عبر `.to_dict()`.

| حاجة Backend | الاستدعاء | النتيجة |
|---|---|---|
| إنشاء job | `engine.create_job(source, settings, source_type=...)` | `JobRef` يحوي `id` و`job_id` وsource و`contract_version`. |
| بدء job | `engine.start_job(job_id, on_progress)` | `JobResults` بعد التشغيل أو بعد الاستفادة من checkpoints. الاستدعاء synchronous ويجب وضعه داخل worker. |
| الحالة | `engine.status(job_id)` أو `engine.get_job_status(job_id)` | `JobStatus` يحوي status وstage وprogress وmessage وحالات المراحل. |
| التقدم | `engine.progress(job_id)` | mapping يحوي `fraction` و`progress` وstage وmessage وstages؛ التقدم الأخير محفوظ في SQLite. |
| إلغاء | `engine.cancel_job(job_id)` | `JobStatus`؛ طلب الإلغاء دائم، وتلتقطه queue عند أقرب stage boundary آمن. |
| الاستئناف | `engine.resume_job(job_id, settings, on_progress)` | `JobResults` لنفس `job_id` مع إعادة استخدام checkpoints الصحيحة. |
| النتائج | `engine.results(job_id)` أو `engine.get_job_results(job_id)` | `JobResults` checkpoint-backed، ويمكن أن يكون جزئيًا قبل اكتمال job. |
| full render | `engine.render(job_id, on_progress=...)` | يشغّل المسار العادي checkpoint-aware ويعيد `JobResults`. |
| edited clip | `engine.render(job_id, clip_index, on_progress=...)` أو `engine.render_clip(...)` | mapping لنتيجة clip بعد استدعاء renderer الموجود وتحديث `render.json`. |

يتعين على Backend تحويل `JobRef.to_dict()` و`JobStatus.to_dict()` و`JobResults.to_dict()` إلى schema النقل الخاص به. لا ينبغي كشف tracebacks أو الاستثناءات الخام أو مسارات filesystem الداخلية في API العام.

## إعداد runtime

يجب أن يمرر Backend قيمة `PUBLIKCLIP_HOME` إلى processing root معزول، وأن يضمّن مجلد `pipeline/` في `PYTHONPATH` أو يثبت الحزمة من `pipeline/pyproject.toml`. لا يحتاج إنشاء job أو status أو قراءة checkpoints إلى تحميل نماذج ASR الثقيلة؛ أما التشغيل الفعلي فيحتاج dependencies المعلنة وffmpeg بحسب المراحل.

## حالات وأخطاء

يحافظ المحرك على stable error codes عبر `EngineError`. فشل مرحلة ما يُحفظ في queue ثم يُحوّل إلى خطأ آمن عند boundary المحرك. الخطأ القابل للاستئناف يملك `recoverable=True` حيث ينطبق، بينما `JOB_CANCELLED` ليس نجاحًا ولا ينبغي عرضه كـ done.

| الكود | المعنى |
|---|---|
| `JOB_NOT_FOUND` | job ID غير موجود. |
| `INVALID_SOURCE` أو `INVALID_JOB_SETTINGS` | input أو settings غير صالحين. |
| `JOB_BUSY` | محاولة تشغيل أو resume لـ job يعمل حاليًا. |
| `JOB_CANCELLED` | طلب إلغاء محفوظ أو إلغاء التقطته queue. |
| `JOB_NOT_CANCELLABLE` | محاولة إلغاء job مكتمل. |
| `CLIP_NOT_FOUND` | clip index غير صالح أو غير موجود في score checkpoint. |
| `CLIP_RENDER_FAILED` | فشل رندر clip. |
| `ENGINE_FAILED` | فشل تنفيذ غير مصنف عند boundary المحرك. |

## ما يحتاجه Android

لا يحتاج Android إلى Python runtime أو أسماء modules الداخلية. عليه حفظ `job_id` قبل بدء polling، وعرض status وprogress اللذين يعيدهما Backend، وعدم تصنيع progress محلي أو حذف checkpoints. يجب أن تكون cancellation وresume عمليتين على Backend مع بقاء job identity ثابتة.

## اعتماديات المسارات الأخرى

يبقى Gateway الحالي متوافقًا مع CLI `publikclip --jsonl`. الترحيل الاختياري إلى الاستدعاء المباشر يحتاج adapter داخل Gateway فقط لتحويل `ProgressEvent` وحالات `JobStatus` ونتائج `JobResults` إلى API schema الموجود، وربط cancellation المستمر بمسار Gateway. لا ينبغي تعديل pipeline لإضافة FastAPI أو OAuth أو client navigation.

## التحقق

توجد اختبارات العقد في [`pipeline/tests/test_engine.py`](pipeline/tests/test_engine.py)، وتبقى اختبارات queue في [`pipeline/tests/test_queue.py`](pipeline/tests/test_queue.py). التشغيل من جذر المستودع:

```bash
PYTHONPATH=pipeline pytest -q pipeline/tests/test_queue.py pipeline/tests/test_engine.py
PYTHONPATH=pipeline pytest -q pipeline/tests
```

التوثيق المعماري الموجود من جلسة refactor هو [`docs/ENGINE_ARCHITECTURE.md`](docs/ENGINE_ARCHITECTURE.md)، والواجهة العامة في [`pipeline/publikclip_pipeline/engine/__init__.py`](pipeline/publikclip_pipeline/engine/__init__.py).
