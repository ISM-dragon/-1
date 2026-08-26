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


## تحديث جلسة التدقيق والترحيل — 2026-08-26

تم فحص `autoclip-main.zip` كمرجع منفصل ومقارنته بالمشروع الأساسي على مستوى Android وGateway وEngine وAI/media وcaptions وcamera وjob lifecycle وlicenses. لم يتم نسخ المشروع المرجعي أو استبدال بنية PublikClip. أضيفت الوثائق التالية: `docs/REFERENCE_COMPARISON.md` و`docs/REFERENCE_MIGRATION_PLAN.md` و`docs/MIGRATION_DECISIONS.md` و`docs/THIRD_PARTY_LICENSES.md` و`docs/API.md` و`docs/ENGINE.md` و`docs/AI_RUNTIME.md` و`docs/MEDIA_RUNTIME.md` و`docs/ANDROID_UI.md` و`docs/TEST_MATRIX.md` و`docs/PERFORMANCE.md` و`docs/SECURITY.md`.

تم إصلاح فجوة recovery في المسار النشط: `ProcessingGatewayClient.process()` يقبل الآن `existingGatewayJobId`، و`VideoProcessingWorker` يمرر المعرف المحفوظ من Room. عند وجود job بعيد قابل للاستعادة، لا يعاد رفع المصدر أو إنشاء job مكرر؛ وتُطلب عملية resume للحالات `INTERRUPTED` أو الفشل القابل للاستعادة. إذا كان الإلغاء صريحًا ثم طلب المستخدم retry، يبدأ المسار job جديدًا بدل إعادة استخدام job ملغى. أضيف اختبار regression مصدره `android/app/src/test/java/com/example/ProcessingGatewayClientResumeTest.kt`، وتم التحقق من compile لمصادر التطبيق والاختبار.

نتائج التحقق في هذه الجلسة: `python3 scripts/check_identity.py` نجح، compileall نجح، Gateway `39 passed, 1 skipped`، backend `6 passed`، pipeline `117 passed`، وfrontend `npm ci && npm run build` نجح. كما نجح `:app:compileDebugKotlin` و`:app:compileDebugUnitTestKotlin` بعد تجهيز JDK 21 وAndroid API 36. تنفيذ Robolectric الكامل تجاوز مدة التنفيذ العملية في هذه البيئة ولم يُعلن نجاحه؛ لذلك لا تُغلق مصفوفة Android unit/lint/release إلا بسجل نهائي مكتمل. لا يزال اختبار الجهاز الحقيقي، private HTTPS Gateway، AI/model runtime، وrelease signing الخارجي مفتوحًا كما هو موثق في `docs/RELEASE_BLOCKERS.md`.


## نتائج البناء النهائية

بعد تجهيز JDK 21 وAndroid SDK API 36 محليًا، نجح `:app:lint` و`:app:assembleRelease` في `evidence/current_run/android_release_verify.log`. الناتج المحلي هو `android/app/build/outputs/apk/release/app-release-unsigned.apk` بحجم 55,690,915 bytes وبصمة SHA-256 هي `c327acb5fd98318e0f64bfa4f7227f613ef6a153b93f2e75ee048385aa5b8d1c`. يظل غير موقع لأن keystore release ليس موجودًا، ولذلك لا يُعامل كنسخة توزيع نهائية. نجح كذلك compile للتطبيق والاختبار، بينما بقي تنفيذ Robolectric الكامل غير معتمد بسبب تجاوز زمن البيئة؛ لا يوجد ادعاء بقبول الجهاز أو المسار E2E.
