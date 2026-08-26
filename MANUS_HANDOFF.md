# MANUS HANDOFF

## الحالة عند التسليم

آخر تحقق شامل في **2026-08-26** على فرع `main`. نتيجة القبول الصارم هي **FAIL** لأن Android APK لم يُبنَ بنجاح ولم يُشغّل على جهاز أو Emulator، ولأن Pipeline الحقيقي توقف عند ASR بسبب عدم توفر WhisperX/PyTorch والنماذج المطلوبة. تقرير التفاصيل الكامل موجود في [`docs/FINAL_ACCEPTANCE.md`](docs/FINAL_ACCEPTANCE.md).

> لا تُعلن النسخة جاهزة للإنتاج أو ناجحة في اختبار End-to-End إلا بعد إعادة تشغيل المسار من Android APK فعليًا مع نماذج Pipeline ومزود LLM متاحين.

## معمارية المسار

المسار المقصود هو: Android media picker → Gateway upload → create processing job → ingest → ASR → diarization → events → candidates → scoring → camera → render → results → clip preview → download/export. Android لا يشغّل Python داخل APK؛ يرسل الفيديو إلى Gateway ويحفظ job ID ونتائج التنزيل محليًا عبر Room وWorkManager.

| المكوّن | الموقع | المسؤولية |
|---|---|---|
| Android | `android/` | اختيار واستيراد الوسائط، جدولة WorkManager، حفظ job ID، polling، preview وexport. |
| Gateway | `gateway/main.py` | upload، job lifecycle، auth، state transitions، retry/cancel/resume، results وmedia serving. |
| Pipeline | `pipeline/publikclip_pipeline/` | ingest، ASR، diarization، events، candidates، scoring، camera، render وcheckpoints. |
| Acceptance report | `docs/FINAL_ACCEPTANCE.md` | PASS/FAIL لكل مرحلة وكل failure scenario. |

## تشغيل Gateway المحلي

من جذر المستودع:

```bash
export GATEWAY_TOKEN='ضع-رمزًا-محليًا-طويلًا'
export REQUIRE_GATEWAY_TOKEN=true
export PUBLIC_BASE_URL='http://127.0.0.1:8787'
export ISM_PIPELINE_DIR="$PWD/pipeline"
export ISM_PROCESSING_ROOT="$PWD/gateway/processing"
python3 -m uvicorn gateway.main:app --host 127.0.0.1 --port 8787
```

يجب التحقق قبل بدء المهمة من `/health` و`/v1/processing/capabilities` و`/v1/diagnostics/pipeline`. عند اختيار Gemini يجب أن يكون `/v1/diagnostics/gemini` في حالة `ready`. لا يوضع مفتاح Gemini داخل Android أو job JSON أو URL.

## متطلبات Android

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:lint
./gradlew :app:assembleDebug
```

في هذا التحقق تم تثبيت JDK وAndroid SDK محليًا، لكن `assembleDebug` انتهى باختفاء Gradle daemon تحت ضغط ذاكرة sandbox. لم يتوفر `adb` بجهاز، ولم يوجد AVD. لذلك لم يُنفذ Android UI، Media Picker، app restart، clip preview، أو export من APK.

## نتائج التشغيل المنفذة

تم إنشاء fixture كلام حقيقي قصير مدته 8 ثوانٍ وطويل مدته 120 ثانية، ونجح upload الحقيقي لكليهما بعد إضافة ffprobe validation. Pipeline الحقيقي أنشأ ingest checkpoint والصوت التحليلي، ثم توقف عند ASR لأن runtime/model غير متاح. مسار API المنفصل باستخدام test double عبر جميع أسماء المراحل نجح للفيديو القصير والطويل، وفُحص artifact الناتج بـFFprobe، لكنه ليس دليلًا على نجاح Pipeline الإنتاج الحقيقي.

اختُبرت network interruption وbackend restart وresume وcancel وretry وjob failure وrender failure عبر Gateway lifecycle. أُعيد تشغيل Gateway بعد الانقطاع، وسُجلت حالة `INTERRUPTED` ثم اكتملت مهمة recovery في contract test. أُعيدت مهمة failure عبر `RETRY_WAIT → QUEUED` مع زيادة `retry_count`. لا يُسمح باستخدام هذه النتائج لإعلان نجاح Android E2E.

## الإصلاحات التي يجب الحفاظ عليها

أُصلح `cancel` على job في حالة `FAILED` ليعيد HTTP 409 مع توجيه إلى retry/resume بدل HTTP 500. أُضيف ffprobe validation قبل إنشاء source job، مع رفض corrupted media بـ`MEDIA_INVALID` وتنظيف المجلد. أُضيف تصنيف `ASR_MODEL_UNAVAILABLE` عند غياب WhisperX/PyTorch. كما أُصلح سياق `RowScope` في `OpusBottomNav` ليتوافق مع Material3 `NavigationBarItem`.

اختبارات regression الحالية تشمل جميع اختبارات Gateway وعددها 37 اختبارًا ناجحًا، واختبارات التحكم والوسائط وASR وعددها 15 اختبارًا ناجحًا، واختبارات Pipeline المعزولة غير البطيئة التي نجحت عند تشغيل كل ملف في عملية مستقلة. اختبار Android build وrender smoke الكامل بقيا FAIL في البيئة الحالية ويجب عدم تحويلهما إلى PASS دون إعادة تشغيل فعلي.

## بوابة القبول التالية

يجب أولًا توفير Android SDK/JDK وemulator أو هاتف، ثم بناء APK، وتثبيت نماذج ASR وdiarization، وتشغيل Ollama model أو Gemini credential اختباري server-side، وتشغيل فيديو قصير وطويل عبر APK نفسه. بعد ذلك يجب تنفيذ كل حالات failure matrix في [`docs/FINAL_ACCEPTANCE.md`](docs/FINAL_ACCEPTANCE.md) مع حفظ evidence، ثم تحديث هذا الملف ورفع القرار إلى PASS فقط إذا اكتمل المسار حتى preview وdownload/export من التطبيق.

## مراجع التشغيل

- [`docs/API-CONTRACT.md`](docs/API-CONTRACT.md)
- [`docs/CLIENT-RESPONSIBILITIES.md`](docs/CLIENT-RESPONSIBILITIES.md)
- [`docs/MASTER-ARCHITECTURE.md`](docs/MASTER-ARCHITECTURE.md)
- [`gateway/README.md`](gateway/README.md)
- [`android/README.md`](android/README.md)
- [`docs/FINAL_ACCEPTANCE.md`](docs/FINAL_ACCEPTANCE.md)
