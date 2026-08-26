# Processing Engine Architecture

**الحالة:** Architecture contract v1
**النطاق:** Python processing pipeline
**الهدف:** فصل محرك معالجة الوسائط عن واجهة التطبيق والـUI مع إبقاء الخوارزميات الحالية، وملفات checkpoint، وواجهة CLI المتوافقة.

## 1. المبدأ المعماري

المحرك هو مالك دورة معالجة الوسائط ونتائجها المرحلية. أما الـUI أو application shell أو Gateway adapter فهي مستهلكون لعقد المحرك، ولا يستوردون وحدات المراحل أو persistence الداخلية كي يقرروا كيف تُنفَّذ المعالجة.

> **قاعدة الاعتماد:** المستهلك الخارجي يعتمد على `publikclip_pipeline.engine` فقط. تركيب المراحل، SQLite bookkeeping، أسماء ملفات checkpoint، واستدعاءات ffmpeg تبقى تفاصيل داخلية قابلة للتغيير خلف العقد.

الطبقة العامة موجودة في `pipeline/publikclip_pipeline/engine/`. يحتوي `contracts.py` على الأنواع والعقد الثابت، ويحتوي `pipeline.py` على `PipelineEngine`، وهو adapter orchestration يركّب المراحل الحالية ولا يعيد كتابة خوارزمياتها. تبقى خوارزميات `ingest` و`asr` و`diarize` و`events` و`candidates` و`scoring` و`camera` و`render` في مواقعها الأصلية.

## 2. public interface

يُنشأ المحرك محليًا من خلال:

```python
from publikclip_pipeline.engine import PipelineEngine

engine = PipelineEngine()
```

العقد المنطقي التالي هو السطح العام. تعاد records قابلة للتحويل إلى JSON، ولا يُفترض بالمستهلك معرفة `queue.Job` أو `StageContext` أو بنية ملفات pipeline.

| العملية | التوقيع المنطقي | النتيجة |
|---|---|---|
| إنشاء job | `create_job(source, settings=None, *, source_type=None)` | `JobRef` بهوية ثابتة |
| بدء job | `start_job(job_id, on_progress=None)` | `JobResults` عند الاكتمال |
| حالة job | `get_job_status(job_id)` | `JobStatus` durable projection |
| إلغاء job | `cancel_job(job_id)` | `JobStatus` بعد تثبيت طلب الإلغاء |
| استئناف job | `resume_job(job_id, settings=None, on_progress=None)` | `JobResults` بعد إعادة تشغيل المراحل الناقصة |
| نتائج job | `get_job_results(job_id)` | `JobResults` من checkpoints |
| قراءة clip | `get_clip(job_id, clip_index)` | `ClipResult` |
| إعادة render | `render_clip(job_id, clip_index, on_progress=None)` | artifact entry للمقطع |

تُصدَّر هذه الرموز من `publikclip_pipeline.engine`: `PipelineEngine`, `ProcessingEngine`, `JobRef`, `JobResults`, `JobStatus`, `ClipResult`, `ProgressEvent`, و`EngineError`. يحدد `ENGINE_CONTRACT_VERSION = 1` إصدار العقد، ويجب زيادة الإصدار فقط عند تغيير دلالة الحقول أو semantics العمليات، لا عند تغيير implementation داخلي.

### 2.1 Job input contract

يجب أن يكون `source` نصًا غير فارغ. يُستنتج `source_type` من بادئة HTTP عند عدم تمريره، وتُقبل القيم الحالية `file` و`url`. الإعدادات إما mapping JSON-serializable أو كائن `Settings` يملك `to_json()`. تُحفظ نسخة الإعدادات عند إنشاء job؛ لذلك لا يلتقط resume defaults جديدة بصورة صامتة.

مثال:

```python
job = engine.create_job(
    "https://example.test/video.mp4",
    {"processing_mode": "balanced", "caption_preset": "classic"},
    source_type="url",
)
```

`JobRef` لا يحتوي تفاصيل التخزين، ويضمن فقط `id` و`job_id` المتوافق ووقت الإنشاء والمصدر ونوعه وإصدار العقد.

## 3. فصل مراحل المعالجة

يستدعي `PipelineEngine` المراحل بترتيب واحد ثابت، بينما تظل كل خوارزمية مسؤولة عن عملها وبياناتها المرحلية. أسماء العقد العامة تُوحّد `diarize` إلى `diarization` و`score` إلى `scoring`، ولا تُسرّب هذه المطابقة إلى الـUI.

| المجال العام | module الحالي | مسؤولية المحرك عند الحد الفاصل | checkpoint |
|---|---|---|---|
| ingest | `ingest.stage` | التحقق من المصدر، probe، واستخراج media/audio | `ingest.json` |
| ASR | `asr.stage` | transcript وتوقيت الكلمات | `asr.json` |
| diarization | `diarize.stage` | المتحدثون وspeaker turns | `diarize.json` |
| events | `events.stage` | timeline وaudio dynamics | `events.json` |
| candidates | `candidates.stage` | نوافذ المرشحين | `candidates.json` |
| scoring | `scoring.stage` | score واختيار clips | `score.json` |
| camera | `camera.stage` | trajectories وقرارات framing | `camera.json` |
| render | `render.stage` | إنتاج MP4 والتحقق من artifact | `render.json` |

لا ينقل هذا الفصل أي algorithm إلى engine package. الـEngine يملك orchestration فقط: إنشاء job، تمرير السياق، تخطي checkpoint صالح، تحويل الأحداث، وتجميع النتائج.

## 4. Engine lifecycle

يمر المحرك بالمراحل التالية:

1. **Create:** يتحقق من المصدر والإعدادات، وينشئ هوية job ومجلده ولقطة settings.
2. **Start:** يتحقق من أن job غير مكتمل وغير ملغى، ثم يشغّل المراحل بالترتيب.
3. **Checkpoint:** بعد نجاح كل stage تُكتب envelope ذرية، ثم تُسجل stage كـ`done`.
4. **Observe:** يرسل المحرك `ProgressEvent` ويعرض `JobStatus` من bookkeeping الدائم.
5. **Complete:** بعد نجاح آخر stage تُعاد `JobResults`، ويصبح status الداخلي `done`.
6. **Fail or cancel:** يحوّل المحرك الأخطاء إلى `EngineError` ثابت، ويحفظ الحالة بحيث يمكن للمستهلك عرض الإجراء المناسب.
7. **Resume:** يمسح فقط علامة الإلغاء، يعيد job نفسه، ويعيد تشغيل المراحل التي لا تملك checkpoint صالحًا أو فقدت artifact مطلوبًا.

`PipelineEngine` لا ينشئ thread أو process خلفيًا من تلقاء نفسه. إذا احتاج Gateway إلى worker، فهو يملك الجدولة والعزل ويستدعي engine من worker process أو compatibility CLI. هذا يبقي lifecycle المحرك حتميًا وقابلًا للاختبار.

## 5. Job lifecycle

حالات الـEngine المحلية الحالية هي `pending`, `running`, `done`, `failed`, و`cancelled`. هذه projection داخلية مبسطة، ويمكن للـGateway إسقاطها على vocabulary الأوسع الخاص به (`QUEUED`, `PREPARING`, `TRANSCRIBING`, `COMPLETED` وغيرها) دون أن يعرف تفاصيل stages.

| الحالة | معنى الحالة | العمليات المسموحة |
|---|---|---|
| `pending` | job منشأ ولم يبدأ | `start_job`, `cancel_job`, `resume_job` |
| `running` | stage قيد التنفيذ | `cancel_job`، ولا يبدأ مرة أخرى بالتوازي |
| `done` | كل checkpoints والـartifacts المطلوبة مكتملة | `get_job_status`, `get_job_results`, `get_clip`, `render_clip`؛ لا إلغاء |
| `failed` | stage فشل مع error آمن | `get_*`, `resume_job` |
| `cancelled` | طلب الإلغاء مثبت | `get_*`, ثم `resume_job` إذا أراد المستهلك ذلك |

الهوية لا تتغير عند resume. لا تُنشأ job جديدة ولا تُنسخ checkpoints إلى مسار بديل؛ وهذا يضمن أن الـGateway والـUI يحتفظان بالمعرف نفسه.

## 6. Checkpoint وresume

ملف كل stage عبارة عن envelope من الشكل التالي:

```json
{
  "stage": "ingest",
  "schema_version": 1,
  "created_at": 0.0,
  "data": {}
}
```

الـcheckpoint صالح فقط عندما يكون JSON قابلًا للقراءة، ويحمل `schema_version` المتوقع، ويكون `data` mapping، وتكون artifacts التي يتطلبها stage موجودة. الكتابة تتم إلى ملف مؤقت ثم rename ذري؛ لذلك لا تُعامل الكتابة غير المكتملة كنجاح.

عند `resume_job()` يُعاد استخدام كل checkpoint صالح. إذا غاب الملف أو تلف أو تغيّر schema version أو غاب artifact، يعاد تشغيل ذلك stage وما بعده باستخدام `prior` results التي يوفرها runner. لا تعيد طبقة Engine algorithms ولا تحاول إعادة بناء ملفات ناقصة يدويًا.

## 7. Progress events

يسلّم المستهلك callback اختياريًا يستقبل `ProgressEvent`:

```json
{
  "event": "progress",
  "job_id": "20260826-120000-a1b2c3",
  "stage": "scoring",
  "fraction": 0.5,
  "message": "..."
}
```

`fraction` بين `0.0` و`1.0`، أو `-1.0` عندما تكون النسبة غير معروفة. المرحلة `render` ترسل تقدمًا على مستوى clips. الأحداث ملاحظات transient؛ أما الحقيقة القابلة للاستعادة فهي checkpoints و`JobStatus`. لذلك لا يجوز للـUI اختراع progress أو اعتبار غياب event دليلًا على الفشل.

## 8. Cancellation

`cancel_job()` يثبت `cancel_requested` في bookkeeping قبل إرجاع الحالة. إذا كان job pending ينتقل فورًا إلى `cancelled`. وإذا كان running، يرى runner العلامة عند boundary بين المراحل ويوقف التنفيذ قبل تشغيل stage التالية، ثم يحفظ `JOB_CANCELLED`. لا يمسح الإلغاء restart أو استدعاء آخر بالخطأ.

إلغاء عملية خارجية مثل ffmpeg أو worker process مسؤولية adapter أو Gateway الذي يملك العملية. بعد الإيقاف يجب أن يترك adapter job في حالة قابلة للملاحظة، ثم يستخدم `resume_job()` فقط عندما يطلب المستهلك ذلك. لا تُعاد حالة cancelled إلى running تلقائيًا.

## 9. Error model

كل خطأ يعبر الحد العام كـ`EngineError`:

| الحقل | الغرض |
|---|---|
| `code` | رمز ثابت للبرمجة والـlocalization، مثل `JOB_NOT_FOUND`, `INVALID_SOURCE`, `JOB_CANCELLED`, `CLIP_NOT_FOUND`, `CLIP_RENDER_FAILED` |
| `safe_message` | رسالة آمنة قابلة للعرض بعد localization |
| `recoverable` | هل يمكن عرض resume/retry للمستخدم |

لا تُعاد raw traceback أو credentials أو private implementation paths كجزء من العقد. أخطاء stage الحالية (`StageError`) تُحوّل في `PipelineEngine` إلى `EngineError` مع الاحتفاظ برمز الخطأ الآمن. الأخطاء غير المصنفة تصبح `ENGINE_FAILED`، وتبقى تفاصيل exception الأصلية في chain الداخلي فقط.

المستهلك يفرق بين ثلاثة أمور: خطأ إدخال غير قابل للإعادة (`INVALID_SOURCE`)، فشل معالجة قابل للاستئناف (`ENGINE_FAILED` أو خطأ stage recoverable)، وإلغاء مقصود (`JOB_CANCELLED`). أما `JOB_NOT_FOUND` و`CLIP_NOT_FOUND` فهما أخطاء lookup لا ينبغي إعادة المحاولة دون تغيير المعرف أو الفهرس.

## 10. Compatibility boundaries

أصبح `publikclip_pipeline.cli` compatibility shell: أوامر `run` و`resume` تستخدم `PipelineEngine`، وتبقى صيغة `--jsonl` الحالية (`job`, `progress`, `result`) حتى يستمر Tauri أو أي launcher قديم في العمل. أمر `edit render-clip` يستخدم `engine.render_clip()` أيضًا؛ وتبقى أوامر السياق والاقتراحات القديمة خلف CLI إلى أن تُنقل إلى عقد مستقلة.

لا يستورد التطبيق أو Android أي module من `pipeline/publikclip_pipeline`. التطبيق يتعامل مع Gateway/API أو CLI compatibility output فقط. ولا يحتاج Gateway إلى معرفة كيفية تنفيذ ASR أو diarization أو scoring؛ adapter النقل يترجم أحداث Engine إلى حالة Gateway الخاصة به، بينما يظل الـGateway مالك auth وdurable remote job state.

## 11. Testing contract

الاختبارات الجديدة في `pipeline/tests/test_engine.py` تستخدم stages صغيرة محقونة في `PipelineEngine`، ولذلك تتحقق من العقد دون تشغيل نماذج ASR أو ffmpeg. وهي تغطي إنشاء وتشغيل job، progress events، إعادة استخدام checkpoint، failure-to-`EngineError`، resume مع بقاء stage المكتمل دون إعادة تشغيل، الإلغاء الدائم ثم الاستئناف، قراءة clip، وخطأ job غير موجود.

تبقى اختبارات `pipeline/tests/test_queue.py` مصدر التحقق التفصيلي من atomic checkpoint وschema invalidation وartifact invalidation. تشغيل suite المحلي:

```bash
cd pipeline
PYTHONPATH=. pytest -q tests/test_queue.py tests/test_engine.py
```

يجب أن يمر هذا الاختبار قبل دمج أي تغيير في العقد. أما اختبار pipeline الحقيقي فيحتاج dependencies وmedia sample وffmpeg/model availability، وهو اختبار تكاملي منفصل عن contract tests.
