# PublikClip Engine

**العقد:** `engine_contract_version=1`.

**الملكية:** الـEngine مسؤول عن orchestration وخوارزميات clip generation، وليس عن auth أو UI أو provider secrets.

## الواجهة العامة

يعتمد Gateway وCLI والاختبارات على facade واحدة بدل استيراد stage modules مباشرة:

```python
job = engine.create_job(source, settings=None, source_type=None)
status = engine.get_job_status(job.id)
result = engine.start_job(job.id, on_progress=callback)
clip = engine.get_clip(job.id, 0)
updated = engine.render_clip(job.id, 0, on_progress=callback)
```

الأنواع العامة هي `JobRef`, `JobStatus`, `JobResults`, `ClipResult`, `ProgressEvent`, و`EngineError`. كل error يعيد `code`, `safe_message`, و`recoverable` دون stack trace أو secret.

## المراحل

```text
ingest → asr → diarize → events → candidates → score → camera → render
```

كل مرحلة تكتب envelope قابلًا للقراءة وتعلن progress. عند إعادة التشغيل يُعاد استخدام checkpoint صالح بدل إعادة الفيديو كاملًا. النتيجة النهائية لا تعتبر ناجحة إلا بعد وجود artifacts قابلة للقراءة والتحقق.

| الوظيفة | الضمان |
|---|---|
| `create_job` | إنشاء job directory وmetadata دون بدء معالجة غير idempotent. |
| `start_job` | تشغيل المراحل بالترتيب مع progress وcheckpoint. |
| `get_status` | قراءة الحالة الحالية من التخزين. |
| `cancel_job` | يثبت cancel request ثم يوقف التنفيذ بأمان. |
| `resume_job` | يمسح marker المؤقت ويكمل من checkpoint صالح إذا كانت الحالة قابلة للاسترداد. |
| `get_results` | يعيد نتائج آمنة وartifacts المكتملة فقط. |
| `render_clip` | يعيد render لمقطع محدد بعد التحقق من index والمدخلات. |

## قواعد الفشل

يفصل Engine بين `MEDIA_INVALID`, `FFMPEG_MISSING`, `FFMPEG_FAILED`, `MODEL_MISSING`, `MODEL_INVALID`, `INSUFFICIENT_DISK`, و`UNSUPPORTED_FORMAT`. فشل LLM اختياري لا يجب أن يحول الوظيفة إلى crash إذا كان fallback rubric أو provider بديلًا متاحًا. أما failure غير القابل للاسترداد فيثبت transition واضحًا ويترك artifacts التشخيصية.

### المراجع

[1]: ../pipeline/publikclip_pipeline/engine/contracts.py "Engine public types"
[2]: ../pipeline/publikclip_pipeline/engine/pipeline.py "Pipeline orchestration"
[3]: ../pipeline/publikclip_pipeline/jobs/queue.py "Checkpoint and job persistence"
[4]: ../gateway/processing_service.py "Gateway-to-engine bridge"
[5]: CONTRACTS.md "Cross-component contract"

## References

[1]: ../pipeline/publikclip_pipeline/engine/contracts.py "Engine public types"
[2]: ../pipeline/publikclip_pipeline/engine/pipeline.py "Pipeline orchestration"
[3]: ../pipeline/publikclip_pipeline/jobs/queue.py "Checkpoint and job persistence"
[4]: ../gateway/processing_service.py "Gateway-to-engine bridge"
[5]: CONTRACTS.md "Cross-component contract"
