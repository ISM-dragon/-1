# PublikClip Engine

## المسؤولية

المحرك هو الطبقة الوحيدة التي تنسق pipeline الثقيلة. لا يعرف Android أو OAuth أو حسابات social. يستقبل source وoptions، ينفذ المراحل، يكتب checkpoints وartifacts، ويبلغ progress عبر contract ثابت.

## المراحل

| الترتيب | المرحلة | المخرج الأساسي |
|---|---|---|
| 1 | ingest | probe، normalized media، metadata وheatmap. |
| 2 | asr | transcript وword timestamps. |
| 3 | diarize | speaker segments عند توفر النموذج. |
| 4 | events | laughter، arousal، audio events. |
| 5 | candidates | نوافذ مرشحة وحدود clips. |
| 6 | score | subscores، confidence، ranking وplatform fit. |
| 7 | camera | face/speaker tracks وcrop path. |
| 8 | render | MP4 وcaptions وintegrity metadata. |

## Facade وlifecycle

العقد التشغيلي المطلوب هو `create_job`, `start_job`, `get_status`, `get_progress`, `cancel_job`, `resume_job`, `get_results`, و`render_clip`. Gateway لا يستورد عشرات الوحدات عشوائيًا؛ يطلق adapter/CLI في عملية منفصلة ويحفظ projection للحالة في SQLite [1] [2].

## Checkpoints

يجب أن يكون checkpoint atomicًا وقابلًا للقراءة بعد restart. لا يعاد تشغيل stage مكتملة إذا كانت مدخلاتها وversion وchecksum سليمة. إذا تلف checkpoint، يصنف الخطأ `CHECKPOINT_INVALID` ويبدأ من آخر stage آمنة أو يفشل برسالة recoverable واضحة.

## المخرجات

كل artifact قابل للتنزيل يجب أن يمر عبر containment وexistence وMP4 validation، وأن يحتوي على bytes وSHA-256. لا يعرض Gateway مسار filesystem الخام للعميل.

## المراجع

[1]: ../pipeline/publikclip_pipeline/engine/contracts.py "Engine public contracts"
[2]: ../pipeline/publikclip_pipeline/engine/pipeline.py "Pipeline orchestration"
[3]: ../gateway/main.py "Gateway subprocess, transitions, and artifact projection"
[4]: ../pipeline/tests/test_engine.py "Engine tests"

## References

المراجع محلية داخل المستودع.
