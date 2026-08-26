# AI Runtime

## الحدود

تعمل نماذج ASR وdiarization وaudio events وface/active-speaker detection على private processing host. لا يحمل APK Python أو PyTorch أو WhisperX أو server-side provider keys. Android يرسل options ويستقبل metadata وartifacts فقط.

## Model Manager

لكل نموذج يجب تسجيل الاسم، الإصدار، الحجم، checksum، المصدر، local path، installed state، وhealth. العمليات المطلوبة هي `check`, `download`, `resume`, `verify`, `load`, `unload`, و`delete`. model cache يعيش خارج مساحة Android.

## المزودات وfallback

يستخدم scoring الحالي مزودات configurable ويدعم Gemini/Ollama وفق إعدادات الخادم. فشل LLM لا يجب أن يسقط الوظيفة إذا كانت قواعد scoring المحلية قادرة على إنتاج نتيجة؛ يسجل النظام `LLM_UNAVAILABLE` أو `LLM_INVALID_RESPONSE` ويستمر بدرجة confidence مخفضة. لا تُرسل ملفات الفيديو كاملة إلى مزود خارجي ضمن المسار المحلي إلا بقرار صريح.

## التوافق مع المرجع

المشروع المرجعي يعتمد Whisper المحلي وLLM fallback من Groq/Gemini/NVIDIA. هذه أفكار تشغيلية فقط؛ لم تُنقل dependencies أو SDKs إلى APK. يحتفظ publikclip بمساره الحالي لأنه يضم diarization وevents وcamera وscoring قابلًا للتدقيق.

## المراجع

[1]: ../pipeline/publikclip_pipeline/models/registry.py "Model registry"
[2]: ../pipeline/publikclip_pipeline/models/specs.py "Model specifications"
[3]: ../pipeline/publikclip_pipeline/scoring/llm.py "LLM scoring integration"
[4]: ../pipeline/publikclip_pipeline/scoring/stage.py "Scoring stage"
[5]: ../reference/video_clipper-main/pyproject.toml "Reference AI dependencies"

## References

المراجع ملفات محلية في المستودع.
