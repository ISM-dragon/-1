# MANUS HANDOFF

**المشروع:** ISM  
**الحالة:** مكتمل على مستوى engine contract وmedia transfer/storage reliability، مع hardening إضافي لمراحل الـpipeline  
**Engine contract:** v1

## ملخص التنفيذ

أضيفت حزمة عامة في `pipeline/publikclip_pipeline/engine/` تحتوي على عقد ثابت وتنفيذ orchestration. أصبح `PipelineEngine` نقطة الاستدعاء العامة لإنشاء job وتشغيله وقراءة حالته وإلغائه واستئنافه وقراءة نتائجه وclip منفرد وإعادة render للمقطع.

أضيفت كذلك طبقة **media transfer/storage reliability** في Gateway فقط. تدعم الطبقة large uploads القابلة للاستئناف، progress offsets، checksum validation، temporary storage آمنًا، atomic finalize، duplicate detection، automatic cleanup، وoutput integrity metadata. لم تُغيّر خوارزميات scoring أو مراحل AI أو عقود الـprocessing.

أُضيف في هذا التغيير hardening مخصص للمراحل الثمانية نفسها، دون إضافة features: validation للمدخلات والـartifacts، recovery للـcheckpoint/cache الفاسد، معالجة timeout وFFmpeg/ffprobe errors، cleanup للملفات المؤقتة، وتحويل أخطاء LLM/provider إلى رسائل آمنة قابلة للتشخيص.

## الملفات الرئيسية

| الملف | الدور |
|---|---|
| `pipeline/publikclip_pipeline/engine/contracts.py` | عقود `ProcessingEngine` و`JobRef` و`JobStatus` و`JobResults` و`ClipResult` و`ProgressEvent` و`EngineError`. |
| `pipeline/publikclip_pipeline/engine/pipeline.py` | `PipelineEngine`، وهو adapter orchestration لا يملك algorithms المراحل. |
| `pipeline/publikclip_pipeline/jobs/queue.py` | checkpoint runner الحالي مع migration خفيف لحقول الإلغاء والخطأ والإعدادات، وحماية envelope parser. |
| `pipeline/publikclip_pipeline/cli.py` | compatibility shell؛ أوامر `run` و`resume` و`edit render-clip` تستدعي Engine API. |
| `gateway/main.py` | media upload sessions، offset persistence، checksum، cleanup، finalize، وoutput integrity. |
| `gateway/tests/test_media_lifecycle.py` | الاختبار العملي لدورة حياة media upload والـoutput. |
| `docs/ENGINE_ARCHITECTURE.md` | توثيق engine lifecycle والعقد والأخطاء والتقدم والإلغاء والاستئناف. |
| `docs/MEDIA_PIPELINE.md` | توثيق عقد media transfer/storage وسيناريوهات التشغيل والاختبار. |
| `docs/ENGINE_STABILITY.md` | تقرير فحص المراحل الثمانية، الإصلاحات، bottlenecks، والقيود التشغيلية. |
| `pipeline/tests/test_stability_regressions.py` | اختبارات regression لمسارات الفشل والـartifact/cache recovery. |

## Engine contract

```python
from publikclip_pipeline.engine import PipelineEngine

engine = PipelineEngine()
job = engine.create_job(source, settings=None, source_type=None)
status = engine.get_job_status(job.id)
result = engine.start_job(job.id, on_progress=callback)
clip = engine.get_clip(job.id, 0)
updated = engine.render_clip(job.id, 0, on_progress=callback)
```

الـcallback يستقبل `ProgressEvent` لا تفاصيل `StageContext`. الأخطاء التي تعبر الحد العام هي `EngineError` مع `code` و`safe_message` و`recoverable`؛ ولا تُعاد stack traces أو secrets.

## Media lifecycle contract

يبدأ resumable upload عبر `POST /v1/sources/uploads` مع `filename`, `bytes`, و`sha256`. يقرأ العميل التقدم عبر `GET /v1/sources/uploads/{upload_id}`، ويرسل chunks عبر `PUT` باستخدام `X-Upload-Offset` أو `Content-Range`. عند اكتمال الحجم يستدعي `POST /v1/sources/uploads/{upload_id}/complete`، حيث يتحقق Gateway من الحجم وSHA-256 ثم ينقل الملف ذريًا إلى `source.mp4` وينشئ source job.

تُكتب الأجزاء في `SOURCE_ROOT/.uploads/{upload_id}.part` بصلاحيات `0700` ولا يُكشف المسار المؤقت. يُثبت offset بعد flush و`fsync`. بعد interruption يمكن الاستئناف من offset المعاد؛ offset غير المتطابق يُرفض. جلسة أو artifact مكتمل بنفس `(bytes, sha256)` يُعاد استخدامه بدل إنشاء duplicate.

يُمرَّر source إلى الـpipeline فقط بعد التحقق من بقاء الحجم وSHA-256 مطابقين. outputs الخاصة بالـprocessing تُفحص قبل نشرها وتُعاد معها `bytes` و`sha256` و`integrity`. ينفذ startup cleanup إزالة جلسات `uploading/corrupt/failed` القديمة وملفات `.part` orphan دون المساس بالـoutputs النهائية.

## API media paths

| Method | Path | الاستخدام |
|---|---|---|
| `POST` | `/v1/sources/uploads` | إنشاء أو إعادة استخدام upload session |
| `GET` | `/v1/sources/uploads/{upload_id}` | قراءة الحالة والـoffset والـprogress |
| `PUT` | `/v1/sources/uploads/{upload_id}` | كتابة chunk قابلة للاستئناف |
| `POST` | `/v1/sources/uploads/{upload_id}/complete` | checksum validation وatomic finalize |
| `POST` | `/v1/sources/upload` | legacy one-shot path، ويستخدم نفس safety guarantees |

التوثيق التفصيلي للعقد والإعدادات وحدود الأحجام موجود في [`docs/MEDIA_PIPELINE.md`](docs/MEDIA_PIPELINE.md).

## Checkpoint وresume

بقيت envelope checkpoints الذرية كما هي، وبقيت قاعدة أن stage لا يُعد مكتملًا إلا مع checkpoint صالح وبقاء artifacts المطلوبة. أضيفت migration تلقائية وآمنة للمنازل المحلية القديمة إلى `jobs` لتوفير `error_code` و`message` و`cancel_requested`.

يحافظ `resume_job()` على هوية job نفسها، يمسح علامة الإلغاء صراحة، ثم يعيد تشغيل المراحل ذات checkpoint المفقود أو التالف أو stale فقط. أما checkpoint الصحيح فيُعاد استخدامه. لا يغيّر resume defaults الإعدادات إلا إذا مرر المستهلك settings جديدة.

ضمن hardening الحالي، يرفض checkpoint parser envelope غير القاموسي أو المنسوب إلى stage أخرى. كما تتحقق stages من side artifacts المهمة قبل cache reuse؛ trajectory وLLM cache وdiarization embeddings التالفة تؤدي إلى cache miss/rebuild أو خطأ آمن، لا إلى reuse غير موثوق.

## حدود التطبيق والـGateway

لا ينبغي لـUI أو Android استيراد `publikclip_pipeline` modules أو معرفة أسماء ملفات checkpoint أو classes المراحل. يعتمد shell على Gateway/API أو على JSONL compatibility output من CLI. ويظل Gateway مسؤولًا عن auth وremote durable state وmedia storage والـworker orchestration، بينما يظل Engine مسؤولًا عن processing lifecycle والنتائج المرحلية.

الـCLI يحتفظ بصيغة `--jsonl` القديمة: أحداث `job` ثم `progress` ثم `result`. لذلك لا يلزم تغيير launcher الحالي عند الانتقال التدريجي إلى Engine contract.

## التحقق المنفذ

نجح compile check ونجحت suite Gateway عبر:

```text
python3 -m unittest discover -s gateway/tests -p 'test_*.py' -v
```

وتغطي suite Gateway الحالية `30` اختبارًا مع اختبار الأحجام الكبيرة اختياريًا في التشغيل العادي. نجح اختبار media lifecycle فعليًا لكل من `100MB` و`500MB` و`1025MB`، مع interruption/resume وSHA-256 validation وduplicate upload وcorrupted upload وautomatic cleanup وoutput integrity.

كما شملت اختبارات Engine التشغيل وprogress events وإعادة استخدام checkpoint وfailure handling إلى `EngineError` وresume مع تخطي stage مكتمل والإلغاء الدائم ثم resume وقراءة clip وjob lookup وGateway processing contract.

اختبارات الـpipeline بعد hardening:

| الأمر | النتيجة |
|---|---:|
| `python3 -m pytest -q` | **143 passed** خلال 16.78s |
| `python3 -m pytest -q -m 'not slow'` | **142 passed، 1 deselected** |
| `python3 -m pytest -q -m slow` | **1 passed**؛ render smoke |
| `python3 -m compileall -q pipeline/publikclip_pipeline pipeline/tests` | نجح |
| `git diff --check` | نجح |

لم يُشغّل end-to-end بالنماذج الثقيلة في هذه البيئة؛ لا توجد أوزان WhisperX وCAMPPlus/PANNs/vision المطلوبة، والتنزيل أثناء CI غير حتمي. أقصى RSS المقاس كان 218,596 KB للاختبارات السريعة و767,152 KB لاختبار render مع أبناء FFmpeg.

## حدود معروفة

الـmedia resume مدعوم داخل upload session نفسها، وليس عبر إعادة بناء bytes مفقودة من مصدر خارجي. إذا كان temp file أقصر من offset المسجل أو فُقد، تُرفض الجلسة ويجب بدء upload جديد. إذا كان أطول، يُقص إلى offset المثبت قبل المتابعة. لا تُزال outputs النهائية تلقائيًا بهذا التغيير؛ cleanup محصور عمدًا في upload temp sessions.

الـCLI يحتفظ حاليًا بمسار compatibility لبعض أوامر `edit context` و`edit suggest-visuals` لأنها ليست ضمن public Engine contract المطلوب. اختبار الأحجام الكبيرة يحتاج مساحة تخزين كافية، ويُفعّل عبر `RUN_LARGE_MEDIA_TESTS=1`.

استدعاءات WhisperX وinference المحلية لا تملك wall-clock timeout داخليًا؛ يجب أن يوفر worker supervisor حدودًا خارجية. كما أن diarization وevents وcamera لا تزال تحمل بيانات صوت/إطارات كبيرة في الذاكرة. specs الحالية لا تثبت sha256 لكل الأوزان. Linux يحتاج FFmpeg نظاميًا بمرشح subtitles/libass؛ والتفاصيل الكاملة في [`docs/ENGINE_STABILITY.md`](docs/ENGINE_STABILITY.md).

## حماية النطاق وملاحظات الدمج

تم الحفاظ على أسماء checkpoints والمسارات الحالية لتجنب migration للـartifacts. لم تُضف خوارزميات أو features جديدة؛ تعديلات pipeline محصورة في validation، recovery، error handling، cleanup، وregression tests.

الخطوة التالية الاختيارية هي إضافة adapter Gateway يستدعي `ProcessingEngine` مباشرة داخل worker process بدل parsing JSONL، مع إبقاء JSONL fallback خلال migration. لا يلزم ذلك لكي يستهلك backend العقد الحالي، ولا يجوز أن ينتقل هذا adapter إلى UI.

## طريقة إعادة التحقق

من جذر المستودع، شغّل:

```bash
python3 -m pytest -q
python3 -m pytest -q -m 'not slow'
python3 -m pytest -q -m slow
python3 -m compileall -q pipeline/publikclip_pipeline pipeline/tests
```

قبل أي release، شغّل benchmark end-to-end على كل target platform مع أوزان صحيحة وملفات media تمثل مصادر قصيرة وطويلة، وسجل زمن كل stage وpeak RSS ونجاح resume بعد قتل العملية. لا تعتبر suite الحالية بديلًا عن ذلك الاختبار التشغيلي.
