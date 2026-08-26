# MANUS HANDOFF — ISM Android / Backend

**المشروع:** ISM
**المستودع:** `ISM-dragon/-1`
**الحالة:** تم دمج إصلاحات Android/Gateway مع تحسينات engine وmedia lifecycle وpipeline stability؛ لم يبدأ rebuild كبير.

## ما تم تنفيذه

تم تصحيح عقد source في Native Android. يقبل `ProcessingEngine` الآن `content://` و`file://` للمعالجة المحلية، ويقبل `http://` و`https://` عندما يكون Gateway مضبوطًا. لم يعد `VideoProcessingWorker` يرفض URL قبل اختيار المسار، و`ProcessingGatewayClient` يرفع المصادر المحلية فقط ويرسل URL البعيد مباشرة إلى `/v1/processing/jobs`.

تم إصلاح نتائج Gateway بحيث تُستكمل metadata لكل output من score checkpoint، بما في ذلك `start` و`end` و`title` و`transcript` عندما لا تكون موجودة في render output. أضيفت regression coverage لعقد URL routing وmetadata وmedia upload path.

تم دمج `PipelineEngine` العام في `pipeline/publikclip_pipeline/engine/` بعقد ثابت للـ jobs والنتائج والأخطاء والتقدم، مع إبقاء CLI JSONL كطبقة توافق. كما تم دمج media lifecycle reliability في Gateway: resumable uploads، offset/checksum validation، atomic finalize، duplicate detection، cleanup، وoutput integrity metadata.

آخر تغييرات remote أضافت pipeline-stage stability hardening دون تغيير algorithms: validation للمدخلات والـartifacts، recovery للـcheckpoint/cache الفاسد، معالجة timeout وFFmpeg/ffprobe errors، cleanup للملفات المؤقتة، ورسائل آمنة لأخطاء LLM/provider.

## المعمارية المؤقتة

| المكوّن | المسؤولية |
|---|---|
| Android client | local URI أو remote URL، حفظ Gateway job ID، WorkManager، polling، تنزيل النتائج والكاش |
| Gateway | auth، SQLite state، media boundary، queue، retry/cancel/resume، provider secrets، artifact serving |
| Pipeline Engine | public lifecycle adapter فوق stage graph الحالي |
| Python pipeline | ingest، ASR، diarization، events، candidates، scoring، camera، captions، FFmpeg rendering |
| Social providers | تبقى منفصلة؛ live publishing غير جاهز خارج mock mode |

المسار الموصى به لمعالجة YouTube على Android هو تشغيل Python/FFmpeg والنماذج في Gateway، وليس داخل APK. Tauri Android لا يشغّل desktop runtime المحلي، وNative Android لا يملك parity كاملة مع WhisperX أو diarization أو active-speaker أو ASS/libass rendering.

## الاختبارات

| الفحص | النتيجة |
|---|---|
| `python3 -m pytest gateway backend -q` | **44 passed, 1 skipped** لاختبار large-media، مع تحذيرات deprecation |
| `cd pipeline && python3 -m pytest tests -q` | **97 passed**؛ suite الجذر الكاملة أعادت **154 passed, 1 skipped** |
| `python3 -m compileall -q gateway pipeline/publikclip_pipeline` | نجح |
| `python3 scripts/check_identity.py` | نجح |
| `git diff --check` | نجح قبل آخر merge |
| `cd android && ./gradlew :app:testDebugUnitTest --no-daemon` | متوقف قبل الاختبارات بسبب غياب Android SDK |

لم يُثبت بعد تشغيل E2E حقيقي من APK إلى Gateway إلى pipeline إلى MP4، ولم تُشغّل النماذج الثقيلة في هذه البيئة. Android unit tests ما زالت متوقفة بسبب غياب Android SDK.

## العوائق المتبقية

أهم عائق هو عدم وجود APK-to-Gateway E2E موثق. Android CI يبني debug APK فقط ولا يغطي release signing أو device/instrumentation tests. يجب اختيار Android surface رسمي واحد بين Tauri-generated APK وNative Compose APK قبل الاستثمار في parity أو publishing.

Gateway live social adapters وOAuth ما زالت غير منفذة خارج `PROVIDER_MODE=mock`. كما أن readiness probe يحتاج فصل structural readiness عن runtime ML/model readiness.

## الخطوة التالية

1. تثبيت Android SDK 36 وJava/Gradle المطلوبة وتشغيل unit tests وlint و`assembleDebug`.
2. تشغيل Gateway حقيقي مع Python 3.12 وFFmpeg/ffprobe وmodel cache وGemini server key.
3. اختبار Native remote URL وlocal upload مع cancel/retry/resume على جهاز أو emulator.
4. اختيار package/application ID الرسمي وإضافة release signing وE2E smoke test.
5. إبقاء Instagram publishing خارج نطاق الإصلاح الحالي حتى تُنفذ adapters وOAuth والإدارة الآمنة للأسرار.

التوثيق التفصيلي موجود في:

- [`docs/ANDROID_AUDIT.md`](docs/ANDROID_AUDIT.md)
- [`docs/ENGINE_ARCHITECTURE.md`](docs/ENGINE_ARCHITECTURE.md)
- [`docs/MEDIA_PIPELINE.md`](docs/MEDIA_PIPELINE.md)
- [`docs/ENGINE_STABILITY.md`](docs/ENGINE_STABILITY.md)
