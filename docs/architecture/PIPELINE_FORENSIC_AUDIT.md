# Pipeline Forensic Audit

## النطاق

تم تدقيق محرك `pipeline/publikclip_pipeline/`، وCLI، وSQLite-backed checkpoint queue، واختبارات pipeline في commit الأساس `d0fbaa5`.

## الرسم الفعلي

المسار المعلن داخل `cli.py` هو:

```text
source URL أو file
  → queue.create_job
  → ingest
  → asr
  → diarize
  → events
  → candidates
  → score
  → camera
  → render
  → checkpoints وJSONL result
```

كل مرحلة تُنفذ عبر `queue.Stage`، ويمكنها إصدار progress events، وتعيد JSON-serializable data. عند اكتمال المرحلة، تُحفظ نتيجة داخل `<job_dir>/<stage>.json` بغلاف يحتوي `stage` و`schema_version` و`created_at` و`data`.

## إدارة الحالة والاستئناف

`jobs/queue.py` يجعل ملفات artifacts وcheckpoints مصدر الحقيقة العملي. قاعدة SQLite تحفظ job status وstage runs، بينما الكتابة إلى checkpoint ذرية عبر ملف مؤقت ثم rename. عند resume، لا تُستخدم نتيجة checkpoint إذا كانت مفقودة أو غير قابلة للقراءة أو تحمل schema version مختلفة، كما يمكن للمرحلة رفض artifact المحفوظ عبر `artifacts_ok`.

الحالات الحالية داخل pipeline أبسط من حالات المنتج المقترحة: job هو `pending` أو `running` أو `done` أو `failed`، والمرحلة هي `running` أو `done` أو `failed`. لا توجد حاليًا state machine كاملة تشمل `queued` و`validating` و`rendering` و`cancelled` على مستوى pipeline.

## مكونات مثبتة بالاختبارات

اختبارات pipeline البالغ عددها 91 تغطي queue/checkpoints، ingest/events، scoring/rubric، candidate clustering، camera director، timeline edits، render contracts، وInstagram insights. نجاحها يثبت العقود الداخلية لهذه الوحدات في بيئة uv، وليس نجاح نماذج خارجية أو تنزيلات YouTube أو رندر كل ملف واقعي.

## المخاطر والفجوات

أهم فجوة تشغيلية هي أن gateway يستدعي CLI في worker مستقل ويحوّل بعض الأحداث إلى أعمدة `stage` و`fraction` فقط، لذلك لا توجد بعد عقود canonical مشتركة بين pipeline وmobile. كما أن pipeline تعتمد على FFmpeg ومكونات ML كبيرة، ولذلك لا ينبغي نقلها إلى Android؛ الهاتف يجب أن يرفع الملف أو المصدر، يراقب job، ويشاهد artifact النهائي.

هناك marker اختبار باسم `slow` غير مسجل في `pyproject.toml`؛ لا يكسر الاختبارات لكنه ينتج تحذيرًا. سيتم تسجيل marker رسميًا بدل إسكات التحذير.

## قرار التصميم

سيبقى pipeline الحالي هو محرك المعالجة. الإصلاح الآمن هو إضافة adapters وعقود API وjob translation حوله، مع الحفاظ على queue وcheckpoints، وليس إعادة كتابة مراحل ASR أو scoring أو render.
