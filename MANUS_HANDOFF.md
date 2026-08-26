# MANUS HANDOFF

**المشروع:** ISM  
**التغيير:** فصل processing engine عن UI/application shell  
**الحالة:** مكتمل على مستوى العقد والتنفيذ والاختبارات الانتقائية  
**Engine contract:** v1

## ملخص التنفيذ

أضيفت حزمة عامة في `pipeline/publikclip_pipeline/engine/` تحتوي على عقد ثابت (`contracts.py`) وتنفيذ orchestration (`pipeline.py`). أصبح `PipelineEngine` هو نقطة الاستدعاء العامة لإنشاء job وتشغيله وقراءة حالته وإلغائه واستئنافه وقراءة نتائجه وclip منفرد وإعادة render للمقطع.

لم تُعد كتابة خوارزميات ingest أو ASR أو diarization أو events أو candidates أو scoring أو camera أو render. التنفيذ الجديد يركّب classes المراحل الحالية بالترتيب نفسه، ويحوّل مخرجاتها إلى records عامة قابلة للتحويل إلى JSON.

## الملفات الرئيسية

| الملف | الدور |
|---|---|
| `pipeline/publikclip_pipeline/engine/contracts.py` | `ProcessingEngine` protocol، `JobRef`، `JobStatus`، `JobResults`، `ClipResult`، `ProgressEvent`، و`EngineError`. |
| `pipeline/publikclip_pipeline/engine/pipeline.py` | `PipelineEngine`، وهو adapter orchestration لا يملك algorithms المراحل. |
| `pipeline/publikclip_pipeline/engine/__init__.py` | public exports الوحيدة التي ينبغي أن يعتمد عليها المستهلك الخارجي. |
| `pipeline/publikclip_pipeline/jobs/queue.py` | checkpoint runner الحالي مع migration خفيف لحقول الإلغاء والخطأ والإعدادات. |
| `pipeline/publikclip_pipeline/cli.py` | compatibility shell؛ أوامر `run` و`resume` و`edit render-clip` تستدعي Engine API. |
| `pipeline/tests/test_engine.py` | اختبارات العقد العامة للمحرك. |
| `docs/ENGINE_ARCHITECTURE.md` | التوثيق الكامل للـlifecycle والعقد والأخطاء والتقدم والإلغاء والاستئناف. |

## العقد العام

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

## Checkpoint وresume

بقيت envelope checkpoints الذرية كما هي، وبقيت قاعدة أن stage لا يُعد مكتملًا إلا مع checkpoint صالح وبقاء artifacts المطلوبة. أضيفت migration تلقائية وآمنة للمنازل المحلية القديمة إلى `jobs` لتوفير `error_code` و`message` و`cancel_requested`.

يحافظ `resume_job()` على هوية job نفسها، يمسح علامة الإلغاء صراحة، ثم يعيد تشغيل المراحل ذات checkpoint المفقود أو التالف أو stale فقط. أما checkpoint الصحيح فيُعاد استخدامه. لا يغيّر resume defaults الإعدادات إلا إذا مرر المستهلك settings جديدة.

## حدود التطبيق والـGateway

لا ينبغي لـUI أو Android استيراد `publikclip_pipeline` modules أو معرفة أسماء ملفات checkpoint أو classes المراحل. يعتمد shell على Gateway/API أو على JSONL compatibility output من CLI. ويظل Gateway مسؤولًا عن auth وremote durable state والـworker orchestration، بينما يظل Engine مسؤولًا عن processing lifecycle والنتائج المرحلية.

الـCLI يحتفظ بصيغة `--jsonl` القديمة: أحداث `job` ثم `progress` ثم `result`. لذلك لا يلزم تغيير launcher الحالي عند الانتقال التدريجي إلى Engine contract.

## التحقق المنفذ

نجح compile check:

```text
python3 -m compileall -q pipeline/publikclip_pipeline gateway
```

ونجحت اختبارات العقد وcheckpoint والـGateway ذات الصلة:

```text
24 passed, 4 warnings in 1.00s
```

وشملت الاختبارات التشغيل، progress events، إعادة استخدام checkpoint، failure handling إلى `EngineError`، resume مع تخطي stage مكتمل، الإلغاء الدائم ثم resume، قراءة clip، job lookup، وGateway processing contract.

كما تم تشغيل CLI فعليًا على مصدر ملف غير موجود للتحقق من failure handling وcompatibility JSONL. أعاد CLI `PIPELINE_STAGE_FAILED`، وحافظ resume على job ID وأعاد تشغيل ingest من دون crash.

شغّل أيضًا full pytest command، لكنه توقف أثناء collection لأن البيئة لم تكن تحتوي dependencies الصوتية للمشروع (`librosa` بعد تثبيت `scipy`). هذا عائق بيئي سابق عن اختبارات Engine؛ الاختبارات الانتقائية المذكورة أعلاه تمر. اختبار media حقيقي كامل يحتاج تثبيت dependencies المعرفة في `pipeline/pyproject.toml` وتوفير sample media وmodel/provider readiness.

## ملاحظات الدمج

تم الحفاظ على أسماء checkpoints والمسارات الحالية لتجنب migration للـartifacts. ما زالت أوامر `edit context` و`edit suggest-visuals` في CLI compatibility path القديم لأنها ليست ضمن public Engine contract المطلوب؛ أما render-clip فأصبح يمر عبر `PipelineEngine.render_clip()`.

الخطوة التالية الاختيارية هي إضافة adapter Gateway يستدعي `ProcessingEngine` مباشرة داخل worker process بدل parsing JSONL، مع إبقاء JSONL fallback خلال migration. لا يلزم ذلك لكي يستهلك backend العقد الحالي؛ لا يجوز أن ينتقل هذا adapter إلى UI.
