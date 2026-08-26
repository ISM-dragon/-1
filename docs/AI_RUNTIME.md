# AI Runtime Contract

## Boundary

كل المعالجة الثقيلة للذكاء الاصطناعي تبقى داخل private Gateway/Engine. تطبيق Android لا يحتوي Python أو WhisperX أو PyTorch أو CAM++ أو PANNs، ولا يحمل مفاتيح Gemini. الهاتف يرسل source وخيارات المعالجة ويتلقى حالة job وartifacts.

## Stages

| المرحلة | المسؤولية | المخرجات المتوقعة |
|---|---|---|
| ASR/alignment | transcription وword timestamps | transcript قابل لإعادة الاستخدام |
| Diarization | speaker segments وword-speaker mapping | speaker timeline |
| Events | laughter/audio/arousal/visual signals | event evidence |
| Candidates | windows وboundaries وdedupe | candidate clips |
| Scoring | LLM أو deterministic fallback وconfidence | ranked candidates |
| Camera | face/speaker/active-speaker framing | crop paths/director decisions |
| Render | captions وvideo output | MP4 artifacts |

## Model Manager

يجب أن يسجل النظام اسم النموذج، الإصدار، الحجم، المصدر، checksum، المسار المحلي، installed state، وhealth. يُفصل download/verify/load/unload/delete عن pipeline stages. فشل provider خارجي لا يتحول إلى crash إذا كان fallback deterministic ممكنًا، ويجب تسجيل error code وretryability.

## Readiness

لا تعني ملفات النموذج وحدها أن runtime جاهز. فحص الجاهزية المطلوب قبل job فعلي هو importability، مساحة القرص، توفر FFmpeg، صحة storage، صلاحية النموذج، ووصول provider المطلوب. نتائج diagnostics يجب أن تبقى آمنة ولا تكشف أسرارًا أو مفاتيح.

## القرارات

تم الإبقاء على WhisperX/diarization الحالية في الخادم بدل استبدالها بـ native Android Whisper من المشروع المرجعي. السبب هو الحفاظ على parity مع scoring والكاميرا والـcheckpoints. أي تغيير في النموذج يجب أن يكون versioned، benchmarked، وقابلًا للمقارنة.

## الفشل والاسترداد

| الخطأ | السلوك |
|---|---|
| `MODEL_MISSING` | إيقاف job برسالة قابلة للإجراء وعدم إنشاء artifact ناقص |
| `MODEL_INVALID` | رفض checksum أو health وإظهار diagnostics آمنة |
| LLM timeout/auth | deterministic fallback إن أمكن، وإلا `FAILED` مع retryability صحيحة |
| insufficient RAM/disk | إيقاف مبكر وتنظيف الملفات المؤقتة |
| restart | استعادة stage/checkpoint بدل transcription كاملة |
