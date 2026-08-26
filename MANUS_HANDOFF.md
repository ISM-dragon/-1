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


## موجة التدقيق والمقارنة — 2026-08-26

تمت مراجعة المشروع الأساسي والمرجع المرفق على مستوى البنية، Android، Gateway، Engine، media، AI، scoring، camera، captions، الاختبارات، والتراخيص. لم يتم نسخ شجرة المرجع أو source code أو secrets. القرار التنفيذي هو إبقاء Native Android + Private Gateway + `PipelineEngine` كمسار canonical، مع تحسينات انتقائية لاحقة فقط بعد regression وbenchmark.

### الوثائق المضافة

- `docs/REFERENCE_COMPARISON.md`
- `docs/REFERENCE_MIGRATION_PLAN.md`
- `docs/MIGRATION_DECISIONS.md`
- `docs/THIRD_PARTY_LICENSES.md`
- `docs/API.md`
- `docs/ENGINE.md`
- `docs/AI_RUNTIME.md`
- `docs/MEDIA_RUNTIME.md`
- `docs/ANDROID_UI.md`
- `docs/TEST_MATRIX.md`
- `docs/PERFORMANCE.md`

### المخاطر المعروفة

المشروع يحتوي أكثر من مسار تشغيل، لكن Native Android/Gateway هو المسار المعتمد للإصدار. ما زال اختبار الجهاز الحقيقي أو emulator مستقرًا مطلوبًا للتحقق من install/launch/file-picker/foreground worker/process-death. كما أن تدقيق تراخيص dependencies النهائي يجب أن يُعاد عند تثبيت نسخ release النهائية.

### القرار التالي

الانتقال إلى تحسينات برمجية صغيرة منخفضة المخاطر، مع عدم استبدال المراحل الحالية أو نقل runtime الثقيل إلى Android. كل تغيير سيصاحبه اختبار regression ثم build كامل.


## موجة التنفيذ والتحقق — 2026-08-26

تم إصلاح فشل compilation كان موجودًا في HEAD: commit `02b91d7` حذف ثلاثة ملفات Android ما زالت `OpusRepository` تستوردها، فاستُعيدت من parent commit دون إعادة كتابة السلوك: `SpeechToTextService.kt` و`CaptionSidecarWriter.kt` و`LocalMediaAnalyzer.kt`. كما أضيف `MockWebServer` إلى version catalog لأن `RemoteGatewayApiContractTest` كان يُترجم ضمن source set ويحتاجه.

تم تحسين `ApiContractClient` كي يقرأ أخطاء Gateway عندما يكون `detail` كائنًا يحوي `code/message`، أو عندما توجد قائمة `errors`، مع الاحتفاظ بدعم الرسائل النصية القديمة و`request_id`. أضيف اختبار regression لهذا الشكل.

نتائج التحقق المسجلة:

| المجموعة | النتيجة |
|---|---|
| `python3 -m pytest -q` | `164 passed, 1 skipped` |
| Android `ApiContractClientTest` | نجح بعد إصلاح الملفات والاعتماد |
| Android `testDebugUnitTest` + `lint` + `assembleRelease` | نجحت في build كامل واحد قبل تنظيف build outputs لإعادة إنتاج artifact النهائي |
| `git diff --check` | نجح |

تحذيرات Gradle الحالية غير مانعة، وتشمل deprecated Compose icons وعبارة `Unable to strip` لبعض مكتبات native؛ لا تُعد فشلًا في هذا البناء. أُزيلت مجلدات build المحلية غير المتعقبة قبل إعادة البناء، وسيُعاد إنشاء APK النهائي ثم فحص SHA-256 وarchive وmanifest قبل commit.


## التحقق النهائي قبل الرفع — 2026-08-26

أُعيد تشغيل البناء من الشجرة الحالية بنجاح باستخدام JDK 21 وAndroid SDK 36. نجحت المهام `:app:testDebugUnitTest`, `:app:lint`, و`:app:assembleRelease`. لأن keystore الإنتاجي غير موجود في بيئة العمل، خرج release كملف unsigned، بينما أُنشئت نسخة Debug موقعة تلقائيًا للاختبار خارج Git:

| artifact | الحالة | SHA-256 |
|---|---|---|
| `publikclip-release-unsigned.apk` | Release unsigned، صالح للأرشيف وليس للتثبيت قبل التوقيع | `c1398a3ee0ee07a68fc5371675ce1bea06551dc24042cec51e8e55db392aa2c1` |
| `publikclip-debug.apk` | Debug signed، تحقق APK Signature Scheme v2 | `a78721c8efb9d7c1f3b1bc8b9ea0485698bfaa6e19c633356689b878c0abe877` |

أكد فحص Debug package الهوية `com.aistudio.opuspro.apk`، الإصدار `0.10.1`، وcompile/target SDK 36، كما لم يظهر في archive أي Python أو Whisper أو PyTorch أو FFmpeg أو Node أو Rust أو key placeholder. فحص ZIP نجح. لا يزال اختبار الجهاز الحقيقي أو emulator مستقرًا مطلوبًا لمسار install/launch/file-picker/process-death.

## حالة الرفع

التغييرات جاهزة للـcommit والرفع إلى `ISM-dragon/-1`. لا تُرفع build directories أو APKs أو keystores؛ تبقى artifacts خارج المستودع ويمكن إعادة إنتاجها بالأوامر الموثقة.
