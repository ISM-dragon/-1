# PublikClip Engine

## Facade

يجب أن يتعامل Gateway مع Engine facade بدل استيراد pipeline internals عشوائيًا. العمليات المطلوبة هي `create_job`، `start_job`، `get_status`، `get_progress`، `cancel_job`، `resume_job`، `get_results`، و`render_clip`. التطبيق الأساسي يحتوي adapter وJSONL compatibility path في `backend/engine.py`، بينما تبقى الخوارزميات داخل `pipeline/`.

## Stages

```text
ingest → asr → diarize → events → candidates → score → camera → render
```

كل stage يسجل state وprogress وartifacts وerrors. لا تعتمد Android على أسماء ملفات داخلية؛ يعتمد فقط على API resource.

## Checkpoint/resume

يُحفظ checkpoint بعد كل مرحلة قابلة لإعادة الاستخدام مع version وinput identity وartifact metadata. عند restart أو retry، يتحقق Engine من صلاحية checkpoint قبل المتابعة. إذا كان checkpoint غير صالح، يُعاد بناء المرحلة المطلوبة فقط بدل إعادة الفيديو كاملًا بلا تحقق.

## Cancellation

الإلغاء يبدأ من Android/Gateway ويصل إلى worker/process. يجب أن تكون النتيجة transition واضحة إلى `CANCELLED`، وألا تُفهرس ملفات جزئية كـresults. إذا كان process لا يستجيب، يستخدم Gateway timeout/termination آمنًا مع cleanup.

## Compatibility

لا تُحذف `ProductionVideoPipeline` أو adapters القديمة دون إثبات أن جميع callers انتقلوا إلى facade. المسار المعتمد للـAPK هو Gateway remote؛ أي local Kotlin pipeline يبقى fallback/experimental وموسومًا بذلك.
