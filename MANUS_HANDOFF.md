# MANUS HANDOFF — ISM Android / Backend

**المشروع:** ISM
**المستودع:** `ISM-dragon/-1`
**الحالة:** تم دمج تغييرات remote الحديثة مع patch Android/Gateway محدود؛ لم يبدأ rebuild كبير.

## ما تم دمجه وتنفيذه

تم تصحيح عقد source في Native Android. يقبل `ProcessingEngine` الآن `content://` و`file://` للمعالجة المحلية، ويقبل `http://` و`https://` عندما يكون Gateway مضبوطًا. كما أن `VideoProcessingWorker` لم يعد يرفض URL قبل اختيار المسار، و`ProcessingGatewayClient` يرفع المصادر المحلية فقط ويرسل URL البعيد مباشرة إلى `/v1/processing/jobs`.

تم إصلاح نتائج Gateway بحيث تُستكمل metadata الخاصة بكل output من score checkpoint، بما في ذلك `start` و`end` و`title` و`transcript` عندما لا تكون موجودة في render output. أضيفت regression tests لهذا العقد ولسلوك remote URL routing.

تم دمج طبقة `PipelineEngine` العامة في `pipeline/publikclip_pipeline/engine/`، مع عقود lifecycle للـ jobs والنتائج والأخطاء والتقدم، مع إبقاء CLI JSONL كطبقة توافق. كما تم دمج تحسينات media lifecycle في Gateway، بما يشمل resumable uploads وoffset/checksum validation وatomic finalize وcleanup وoutput integrity metadata.

## المعمارية المعتمدة مؤقتًا

| المكوّن | المسؤولية |
|---|---|
| Android client | إدخال local URI أو remote URL، حفظ Gateway job ID، WorkManager، polling، تنزيل النتائج والكاش |
| Gateway | auth، SQLite state، resumable media boundary، queue، retry/cancel/resume، provider secrets، artifact serving |
| Pipeline Engine | public lifecycle adapter فوق stage graph الحالي دون نقل algorithms إلى Android |
| Python pipeline | ingest، ASR، diarization، events، candidates، scoring، camera، captions، FFmpeg rendering |
| Social providers | تبقى منفصلة؛ live publishing غير جاهز خارج mock mode |

المسار الموصى به لمعالجة YouTube على Android هو تشغيل Python/FFmpeg والنماذج في Gateway، وليس داخل APK. Tauri Android لا يشغّل desktop runtime المحلي، وNative Android لا يملك parity كاملة مع WhisperX أو diarization أو active-speaker أو ASS/libass rendering.

## الاختبارات

| الفحص | النتيجة |
|---|---|
| `python3 -m pytest gateway -q` | **38 passed, 1 skipped** لاختبار الأحجام الكبيرة، مع تحذيرات FastAPI deprecation |
| `cd pipeline && python3 -m pytest tests -q` | **97 passed** |
| `python3 -m compileall -q gateway pipeline/publikclip_pipeline` | نجح |
| `python3 scripts/check_identity.py` | نجح |
| `git diff --check` | نجح قبل الدمج |
| `cd android && ./gradlew :app:testDebugUnitTest --no-daemon` | متوقف قبل الاختبارات بسبب غياب Android SDK |

بعد الدمج أُعيد تشغيل Gateway وpipeline suites بنجاح. المتبقي هو تشغيل Android tests في بيئة تحتوي Android SDK 36.

## العوائق المتبقية

أهم عائق متبقٍ هو عدم وجود تشغيل E2E موثق من APK إلى Gateway إلى job حقيقي إلى MP4 قابل للتشغيل. كذلك ما زال Android CI يبني debug APK فقط، ولا يغطي release signing أو device/instrumentation tests.

Gateway live social adapters وOAuth ما زالت غير منفذة خارج `PROVIDER_MODE=mock`. كما أن readiness probe يحتاج لاحقًا إلى فصل structural readiness عن runtime ML/model readiness.

يجب اختيار Android surface رسمي واحد بين Tauri-generated APK وNative Compose APK قبل الاستثمار في parity أو publishing. الهوية الحالية مختلفة بينهما، كما تختلف عقود source وrendering.

## الخطوة التالية

1. تثبيت Android SDK 36 وJava/Gradle المطلوبة وتشغيل unit tests وlint و`assembleDebug`.
2. تشغيل Gateway حقيقي مع Python 3.12 وFFmpeg/ffprobe وmodel cache وGemini server key.
3. اختبار Native remote URL وlocal upload مع cancel/retry/resume.
4. اختيار package/application ID الرسمي وإضافة release signing وE2E smoke test.
5. إبقاء Instagram publishing خارج نطاق الإصلاح الحالي حتى تُنفذ adapters وOAuth والإدارة الآمنة للأسرار.

الوثيقة التفصيلية موجودة في [`docs/ANDROID_AUDIT.md`](docs/ANDROID_AUDIT.md).
