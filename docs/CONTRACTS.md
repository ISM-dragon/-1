# PUBLIKCLIP Contracts

## Engine contract v1

العقد العام لمعالجة الوسائط موجود في `publikclip_pipeline.engine`. الإصدار الحالي هو `ENGINE_CONTRACT_VERSION = 1`. يعتمد Backend على هذا السطح فقط، ولا يعتمد على `jobs.queue` أو stage modules أو renderer.

| العملية | المدخلات | المخرج |
|---|---|---|
| `create_job` | `source` نص غير فارغ، settings JSON-serializable، و`source_type` اختياري | `JobRef` |
| `start_job` | `job_id` وcallback اختياري | `JobResults` |
| `get_status` | `job_id` | `JobStatus` |
| `get_progress` | `job_id` | progress mapping durable |
| `cancel_job` | `job_id` | `JobStatus` |
| `resume_job` | `job_id` وsettings اختياري وcallback اختياري | `JobResults` |
| `get_results` | `job_id` | `JobResults` |
| `render_clip` | `job_id` و`clip_index` وcallback اختياري | artifact mapping |

كل record عام يوفر `to_dict()` لتحويله إلى JSON. كل callback يستقبل `ProgressEvent`. كل فشل يعبر الحد عبر `EngineError` الذي يملك `code` و`safe_message` و`recoverable`.

## Records

`JobRef` يثبت هوية job ومصدره ونوعه ووقت إنشائه. `JobStatus` يعرض الحالة، المرحلة العامة، التقدم، الرسالة، الخطأ الآمن، قابلية الاستئناف، وعلامة الإلغاء. `JobResults` يقرأ البيانات المرحلية والـartifacts من checkpoints، ويمكن أن يكون جزئيًا قبل اكتمال job. `ClipResult` يعنون نتيجة clip مستقلًا.

تُوحّد أسماء المراحل عند الحد العام إلى `ingest` و`asr` و`diarization` و`events` و`candidates` و`scoring` و`camera` و`render`. أسماء التنفيذ القديمة `diarize` و`score` لا تظهر في `ProgressEvent` أو `JobStatus` أو `get_progress`.

## Lifecycle semantics

حالات المحرك المحلية هي `pending` و`running` و`done` و`failed` و`cancelled`. الإلغاء يكتب marker دائمًا قبل عودة `cancel_job`. الاستئناف يحافظ على job ID ويعيد استخدام checkpoints الصالحة، ويعيد فقط المراحل التي فقدت checkpoint أو artifact صالحًا.

التقدم transient عبر callback لكنه durable عبر SQLite؛ لذلك لا يُعد غياب callback دليل فشل. الـcheckpoint الناجح يتطلب envelope صالحة وschema version متوافقًا وartifacts مطلوبة موجودة.

## Error codes

| الرمز | الدلالة |
|---|---|
| `JOB_NOT_FOUND` | job غير موجود |
| `INVALID_SOURCE` | source غير صالح |
| `INVALID_JOB_SETTINGS` | settings غير قابلة للتسلسل أو تالفة |
| `JOB_BUSY` | job قيد التشغيل |
| `JOB_CANCELLED` | إلغاء محفوظ |
| `JOB_NOT_CANCELLABLE` | job مكتمل ولا يقبل الإلغاء |
| `CLIP_NOT_FOUND` | clip index غير موجود |
| `INVALID_CLIP_INDEX` | clip index سالب |
| `CLIP_RENDER_FAILED` | فشل إعادة render |
| `ENGINE_FAILED` | فشل داخلي غير مصنف |

## Change note

أضيفت في هذه النسخة aliases صريحة باسم `get_status` و`get_progress` و`get_results` إلى جانب الأسماء الموجودة `get_job_status` و`progress` و`get_job_results`. سبب التغيير هو مطابقة surface المطلوب للاستدعاء من Backend، مع إبقاء aliases القديمة حتى لا ينكسر CLI أو أي مستهلك سابق. لم يتغير معنى الحقول أو lifecycle semantics، لذلك بقي `ENGINE_CONTRACT_VERSION` مساويًا لـ`1`.

كما أضيف `PipelineFacadeEngine` في Backend ليحوّل هذا العقد العام إلى worker protocol الموجود. المسار الافتراضي لم يعد يحتاج subprocess أو JSONL؛ يظل `SubprocessPublikclipEngine` متاحًا فقط عندما يطلبه الإعداد صراحة.

## Boundary rule

```text
Backend → PipelineFacadeEngine → publikclip_pipeline.engine.PipelineEngine → Pipeline stages
```

يجب أن يبقى `PipelineEngine` مالكًا للـSQLite الداخلي، checkpoints، stage ordering، resume، progress persistence، وrender. ويجب أن يبقى Backend مالكًا لحالة HTTP وworker scheduling وauth وstorage policy. لا تنقل هذه المسؤوليات عبر الحدود ولا تكرر algorithms في adapter.
