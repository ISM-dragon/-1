# MANUS HANDOFF

## الحالة الحالية

تم تجهيز تطبيق Android أصلي مستقل داخل `android/` ليكون عميل release خفيفاً أمام private Processing Gateway. لا يوجد `MANUS_HANDOFF.md` في نقطة البداية التي تم استلامها؛ لذلك أُنشئت هذه الوثيقة لتثبيت الحالة الحالية ومتطلبات المتابعة.

التطبيق لا يشغّل Python أو `uv` أو Node أو Rust أو FFmpeg داخله. `ProcessingEngine` أصبح remote-only، و`VideoProcessingWorker` ينفذ الرفع والاستطلاع والتنزيل عبر Gateway داخل `CoroutineWorker`، بينما تبقى Python pipeline ومفاتيح Gemini وFFmpeg في backend الخاص.

## Artifact

المسار الناتج:

```text
android/app/build/outputs/apk/release/app-release.apk
```

| الخاصية | القيمة |
|---|---|
| Package | `com.aistudio.opuspro.apk` |
| Version | `0.10.1` |
| Version code | `5` |
| Min SDK | `24` |
| Target/Compile SDK | `36` |
| APK size | `55,596,707` bytes |
| SHA-256 الحالي | `deceadddb138251acd6da62478f8b8913f7620c3f25140d2e3c108805c5faf5a` |
| Signature | APK Signature Scheme v2، بمفتاح اختبار خارج المستودع |

مفتاح الاختبار ليس مفتاح النشر العام. يجب قبل النشر تمرير keystore مملوك للمنتج عبر `KEYSTORE_PATH` و`STORE_PASSWORD` و`KEY_PASSWORD`، وعدم تخزينه في Git أو تضمين أسراره في APK.

## قرارات الإصدار

يجب ضبط private Gateway بعنوان HTTPS ورمز وصول غير فارغ. في مسار معالجة الفيديو، يرسل Android الفيديو ورمز Gateway فقط؛ Gateway يدير Python pipeline وFFmpeg ومفاتيح Gemini. قد تستخدم بعض أدوات الذكاء الاختيارية مفتاحاً يضيفه المستخدم بنفسه، لكن لا يوجد مفتاح مضمّن في APK. التخزين العام غير مطلوب: Photo Picker يعيد `content://` أو URI محلياً، ثم ينسخ التطبيق المصدر إلى `filesDir/source_media` قبل جدولة العمل. النتائج البعيدة تُنزّل إلى `filesDir/gateway_exports/<jobId>`.

يستخدم التطبيق `WorkManager` مع `CoroutineWorker` و`setForeground()` ونوع خدمة `dataSync` للمهام الطويلة. حالة job محفوظة في Room، ويستخدم العمل الفريد network constraint وexponential backoff؛ كما يدعم الإلغاء وإعادة المحاولة والاستئناف عبر `remoteGatewayJobId`. `POST_NOTIFICATIONS` يُطلب وقت التشغيل على Android 13+، وتوجد قناة تقدم وقناة نتيجة. معالج `OpusApplication` يحفظ آخر crash في `filesDir/last_crash.txt` ثم يعيد تمرير الاستثناء إلى handler السابق.

## الملفات الرئيسية التي تغيرت

| الملف | التغيير |
|---|---|
| `android/app/src/main/java/com/example/data/engine/ProcessingEngine.kt` | إزالة fallback المحلي وفرض Gateway HTTPS + token. |
| `android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt` | حذف استدعاء `ProductionVideoPipeline` المحلي، إضافة foreground progress، والإبقاء على remote processing. |
| `android/app/src/main/java/com/example/data/repository/OpusRepository.kt` | تحويل `processNewVideo` إلى enqueue وانتظار terminal Room state بدلاً من تنفيذ محرك محلي. |
| `android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt` | فرض HTTPS للـ Gateway. |
| `android/app/src/main/java/com/example/data/worker/ProcessingNotification.kt` | foreground notification وتحديث progress وقناة النتائج. |
| `android/app/src/main/java/com/example/MainActivity.kt` | طلب إذن الإشعارات على Android 13+. |
| `android/app/src/main/java/com/example/OpusApplication.kt` | crash handler يحفظ آخر stack trace. |
| `android/app/src/main/AndroidManifest.xml` | أقل صلاحيات صريحة، وإضافة foreground service type `dataSync`. |
| `android/app/build.gradle.kts` | إزالة Firebase/Google Services/Secrets plugin غير المستخدمة من APK. |
| `android/app/src/test/java/com/example/ProcessingEngineTest.kt` | اختبار Gateway-only ورفض المصدر/العنوان/token غير الصالح. |
| `docs/RELEASE.md` | تقرير الإصدار ومصفوفة التحقق وحدود اختبار الجهاز. |

## نتائج التحقق

نجحت `:app:testDebugUnitTest` و`:app:lint` و`:app:assembleRelease`. أكد `apksigner` توقيع v2، وأكد `aapt2` package والـ SDK والصلاحيات. فحص أرشيف APK لم يجد Python أو `uv` أو Node أو Rust أو Cargo أو FFmpeg runtime، ولم يجد Gemini API key أو placeholder key مضمّناً في DEX.

تم إنشاء AVD نظيف Android 15 باسم `clean_api35` ومحاولة تثبيت وتشغيل الـ APK. المحاكي يعمل في بيئة TCG بلا acceleration؛ ظهر online مؤقتاً ثم خرج قبل اكتمال `sys.boot_completed=1`. لذلك لم يتم اعتماد install/launch/restart/file-picker/background على جهاز حقيقي كاختبار ناجح، ويجب إكمالها على هاتف Android أو emulator مستقر مزود بتسريع قبل النشر العام.

## أوامر إعادة الإنتاج

```bash
cd android
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export KEYSTORE_PATH=/secure/path/release.jks
export STORE_PASSWORD='***'
export KEY_PASSWORD='***'
./gradlew :app:testDebugUnitTest :app:lint :app:assembleRelease
```

## مراجع التسليم

التفاصيل التشغيلية، مصفوفة الصلاحيات، بصمة APK، وقيود اختبار الجهاز موجودة في [`docs/RELEASE.md`](docs/RELEASE.md). يجب أن يكون أي backend مستخدم في الإنتاج خاصاً ومحمياً بـ HTTPS وGateway token، ويجب عدم نقل محرك Python أو أسراره إلى تطبيق Android.

## تحديث 2026-08-26

أُنجز تدقيق المشروع المرجعي `supoclip-main` دون نسخ source code، وسُجلت المقارنة والقرارات وخطة الهجرة في `docs/REFERENCE_COMPARISON.md` و`docs/REFERENCE_MIGRATION_PLAN.md` و`docs/MIGRATION_DECISIONS.md` و`docs/THIRD_PARTY_LICENSES.md`. أضيفت كذلك وثائق API وEngine وAI runtime وMedia runtime وAndroid UI وTest Matrix وPerformance وSecurity.

أضيفت طبقة `pipeline/publikclip_pipeline/runtime/` المستقلة: `HardwareInfo` لاكتشاف موارد المضيف، و`MediaManager` لفحص FFmpeg/ffprobe وتنفيذ probe/validation/audio/frame/transcode/render/cleanup بأخطاء مصنفة، و`ModelManager` لتتبع الإصدار والحجم وchecksum والمصدر والمسار وحالة التثبيت والتنزيل القابل للاستئناف والتحميل والتفريغ والحذف. عُرضت هذه الجاهزية في `GET /v1/processing/capabilities` تحت `details.runtime` مع الحفاظ على الحقول القديمة.

أضيفت اختبارات regression للوسائط والنماذج وعقد Gateway. نتائج التحقق الحالية: `123 passed` لاختبارات pipeline و`48 passed, 1 skipped` لاختبارات Gateway، و`npm run typecheck` و`npm run build` نجحا. فحص compileall نجح. لم يُنفذ Android Gradle build محليًا لأن Android SDK غير موجود في بيئة التنفيذ؛ CI أصبح يبني debug وunsigned release APK ويرفعهما دون أي مفاتيح سرية.

## الملفات الجديدة/المعدلة في هذه المرحلة

| الملف | التغيير |
|---|---|
| `pipeline/publikclip_pipeline/runtime/*` | Runtime managers مستقلون للعتاد والوسائط والنماذج. |
| `pipeline/publikclip_pipeline/models/registry.py` | Metadata اختيارية متوافقة لخدمة lifecycle. |
| `pipeline/tests/test_ai_media_runtime.py` | اختبارات regression للـruntime. |
| `gateway/main.py` | إضافة runtime readiness إلى capabilities. |
| `gateway/tests/test_web_processing_contract.py` | اختبار contract لحقول readiness. |
| `.github/workflows/android-build.yml` | إضافة unsigned release assembly وartifact. |
| `docs/*` | وثائق المقارنة، الحدود، التشغيل، الأمان، الأداء، والاختبارات. |
| `MANUS_HANDOFF.md` | هذا التحديث. |

## المتبقي قبل النشر الشخصي

يلزم تشغيل Gateway فعليًا مع Python dependencies والنماذج وFFmpeg، ثم تنفيذ E2E حقيقي من Android إلى upload/job/poll/render/download على جهاز أو محاكي مستقر. يلزم كذلك keystore مملوك للمستخدم إذا كان APK سيُوزع خارج بيئة التطوير. لا توجد ادعاءات بأن social publishing أو Android-local ML أصبحا production-ready ضمن هذا التغيير.
