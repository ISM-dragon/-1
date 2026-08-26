# MANUS HANDOFF — publikclip / Android private processing

## الحالة الحالية

اكتملت مراجعة المستودع الأساسي والأرشيف المرجعي، ولم يُنسخ أي ملف من المشروع المرجعي. المسار المعتمد هو تطبيق Android أصلي يتصل بـGateway خاص، بينما تبقى PublikClip Engine وPython وFFmpeg والنماذج على الخادم. تم توصيل العامل الفعلي الذي يطلقه `VideoUploadScreen` بعميل private-backend يستخدم عقد `/jobs/*` المختصر، مع الحفاظ على Room وWorkManager ونتائج Project/Clip.

## القرارات

| القرار | الحالة |
|---|---|
| عدم إعادة كتابة pipeline | مطبق؛ engine والمراحل الحالية باقية. |
| عدم تشغيل Python/FFmpeg/WhisperX داخل APK | مطبق؛ Android يرفع ويستطلع وينزل فقط. |
| استخدام private `/jobs/*` بدل internal `/v1/processing/*` لمسار Android | مطبق في العامل الرئيسي. |
| حفظ remote job ID وإعادة polling بعد restart | مطبق عبر `ProcessingJobEntity.remoteGatewayJobId`. |
| resume للمهمات interrupted/retry-wait | مطبق في `PrivateBackendClient`. |
| retry لمهمة cancelled | يبدأ job جديدًا بعد مسح المعرف البعيد. |
| نسخ كود المرجع | لم يحدث؛ ترخيص المرجع غير واضح رغم إعلان MIT النصي. |

## الملفات التي تغيرت أو أضيفت

| الملف | التغيير |
|---|---|
| `android/app/src/main/java/com/example/data/remote/PrivateBackendClient.kt` | عميل multipart للـprivate `/jobs`، polling، resume، cancel، parsing، وتنزيل artifact مع HTTPS/host validation. |
| `android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt` | العامل الرئيسي يستخدم `PrivateBackendClient` ويحافظ على Room notifications وimport. |
| `android/app/src/main/java/com/example/data/repository/OpusRepository.kt` | الاستيراد والإلغاء يستخدمان نوع وعميل private backend، وcancelled retry يمسح remote ID. |
| `android/app/src/main/java/com/example/data/db/ProcessingJobDao.kt` | إضافة `clearRemoteGatewayJobId`. |
| `android/app/src/test/java/com/example/PrivateBackendClientTest.kt` | اختبارات multipart/private job وresume من checkpoint. |
| `docs/REFERENCE_COMPARISON.md` | مقارنة code/feature-level وقرارات الأوزان. |
| `docs/REFERENCE_MIGRATION_PLAN.md` | خطة Wave 1–5 وبوابات الانتقال. |
| `docs/MIGRATION_DECISIONS.md` | سجل KEEP/ADD/IMPROVE/IGNORE/MANUAL_REVIEW. |
| `docs/THIRD_PARTY_LICENSES.md` | فحص provenance والترخيص وعدم النسخ. |
| `docs/API.md`, `docs/ENGINE.md`, `docs/AI_RUNTIME.md`, `docs/MEDIA_RUNTIME.md`, `docs/ANDROID_UI.md`, `docs/TEST_MATRIX.md`, `docs/PERFORMANCE.md` | عقود ومسؤوليات واختبارات وخطة قياس مطلوبة. |

## أدلة الاختبار

| الاختبار | النتيجة |
|---|---|
| `pytest -q backend/tests` | **6 passed**. |
| `pytest -q gateway/tests gateway/test_personal_taste.py gateway/test_processing_bridge.py gateway/test_provider_quality.py` | **47 passed, 1 skipped**؛ التخطي لاختبار وسائط كبير مشروط بـ`RUN_LARGE_MEDIA_TESTS=1`. |
| `PYTHONPATH=pipeline pytest -q pipeline/tests` | **117 passed** بعد تثبيت dependencies المعلنة. |
| Gradle Kotlin/Java compilation | نجح حتى `compileDebugUnitTestKotlin` و`compileDebugJavaWithJavac` ضمن تشغيل Android. |
| `PrivateBackendClientTest.interruptedExistingJobIsResumedWithoutUploadingAgain` | نجح مع `BUILD SUCCESSFUL`. |
| اختبار `processUsesPrivateJobsContractAndDownloadsResult` | لم يكتمل ضمن timeout في Robolectric؛ لا يُسجل كنجاح. |
| full `:app:testDebugUnitTest` | بدأ compilation، ثم أُوقف لحماية ذاكرة sandbox بعد توقف runner؛ لا يُسجل كنجاح كامل. |
| APK release/lint/device E2E | لم يُثبت في هذه الجولة. يلزم تشغيلها على بيئة Android مستقرة. |

## قيود معروفة

الاختبار الميداني على هاتف أو emulator مستقر لم يُنفذ هنا. كما أن upload multipart في اختبار Robolectric يحتاج تشخيصًا منفصلًا؛ compilation ناجح، لكن test runner توقف أثناء تنفيذ الاختبار. لا يوجد benchmark حقيقي لفيديو طويل أو نماذج AI في هذه البيئة، ولا ينبغي اعتبار وجود ملفات evidence التاريخية بديلًا عن إعادة القياس.

يتطلب الإنتاج ضبط `PUBLIC_BASE_URL` القابل للوصول من الهاتف، `REQUIRE_GATEWAY_TOKEN=true`، `GATEWAY_TOKEN` خارج Git، volume دائمًا للـSQLite/sources/processing/models، وHTTPS أو VPN. يجب بناء APK release وتوقيعه بمفتاح مملوك للمستخدم عبر متغيرات بيئية، دون حفظ keystore في المستودع.

## أوامر إعادة الإنتاج

```bash
cd android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew :app:compileDebugUnitTestKotlin
./gradlew :app:testDebugUnitTest --tests com.example.PrivateBackendClientTest.interruptedExistingJobIsResumedWithoutUploadingAgain
./gradlew :app:lint :app:assembleRelease
```

## المراجع

[1]: docs/REFERENCE_COMPARISON.md "Reference comparison"
[2]: docs/REFERENCE_MIGRATION_PLAN.md "Migration plan"
[3]: docs/API.md "Android private API contract"
[4]: android/app/src/main/java/com/example/data/remote/PrivateBackendClient.kt "Private backend client"
[5]: android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Live Android worker"
[6]: gateway/main.py "Canonical Gateway and private routes"
[7]: evidence/current_python_tests.log "Initial dependency-related test evidence"
[8]: evidence/backend_tests.log "Backend test evidence"
[9]: evidence/gateway_tests.log "Gateway test evidence"
[10]: evidence/pipeline_tests.log "Pipeline test evidence"

## References

المراجع ملفات محلية داخل المستودع. هذه الوثيقة تسجل ما ثبت وما لم يثبت، ولا تستخدم عبارة «كل شيء يعمل» دون دليل.
