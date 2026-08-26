# MANUS HANDOFF

## الحالة الحالية

تم تجهيز تطبيق Android أصلي مستقل داخل `android/` ليكون عميل release خفيفًا أمام private Processing Gateway. المسار canonical هو `ContractJobRepository` → `GatewayProcessingWorker` → `ApiContractClient`. أُنشئت هذه الوثيقة لتثبيت الحالة الحالية ومتطلبات المتابعة.

التطبيق لا يشغّل Python أو `uv` أو Node أو Rust أو FFmpeg داخله. `ProcessingEngine` أصبح remote-only، و`VideoProcessingWorker` ينفذ الرفع والاستطلاع والتنزيل عبر Gateway داخل `CoroutineWorker`، بينما تبقى Python pipeline ومفاتيح Gemini وFFmpeg في backend الخاص.

## Artifact

آخر نسخة مصدرية مستهدفة هي `0.11.0`، `versionCode=6`. لا يُعد APK صالحًا للتوزيع العام قبل بنائه بمفتاح المنتج واختبار الجهاز الحقيقي.

المسار الناتج:

```text
android/app/build/outputs/apk/release/app-release.apk
```

| الخاصية | القيمة |
|---|---|
| Package | `com.aistudio.opuspro.apk` |
| Version | `0.11.0` |
| Version code | `6` |
| Min SDK | `24` |
| Target/Compile SDK | `36` |
| APK size | `55,690,915` bytes |
| SHA-256 الحالي | `679389d8f0a9fb4edc4c6b94ddf11fea9b6d92c1a5cdd4586a4e98335259356d` |
| Signature | unsigned؛ يحتاج keystore المنتج قبل التوزيع |

مفتاح الاختبار ليس مفتاح النشر العام. يجب قبل النشر تمرير keystore مملوك للمنتج عبر `KEYSTORE_PATH` و`STORE_PASSWORD` و`KEY_PASSWORD`، وعدم تخزينه في Git أو تضمين أسراره في APK.

## قرارات الإصدار

يجب ضبط private Gateway بعنوان HTTPS ورمز وصول غير فارغ. في مسار معالجة الفيديو، يرسل Android الفيديو ورمز Gateway فقط؛ Gateway يدير Python pipeline وFFmpeg ومفاتيح Gemini. قد تستخدم بعض أدوات الذكاء الاختيارية مفتاحاً يضيفه المستخدم بنفسه، لكن لا يوجد مفتاح مضمّن في APK. التخزين العام غير مطلوب: Photo Picker يعيد `content://` أو URI محلياً، ثم ينسخ التطبيق المصدر إلى `filesDir/source_media` قبل جدولة العمل. النتائج البعيدة تُنزّل إلى `filesDir/gateway_exports/<jobId>`.

يستخدم التطبيق `WorkManager` مع `CoroutineWorker` ونوع خدمة `dataSync` للمهام الطويلة. حالة job محفوظة في Room، ويستخدم العمل الفريد network constraint وexponential backoff؛ كما يدعم الإلغاء وإعادة المحاولة والاستئناف عبر `remoteGatewayJobId`. يثبت `ContractJobRepository` URI المختار داخل `filesDir/source_media` قبل جدولة العمل. يستخدم `ApiContractClient` جلسة رفع resumable مع SHA-256 وoffset و`Content-Range`، ويستأنف العامل المهمة البعيدة الموجودة بدل إنشاء job جديد. `POST_NOTIFICATIONS` يُطلب وقت التشغيل على Android 13+، وتوجد قناة تقدم وقناة نتيجة. معالج `OpusApplication` يحفظ آخر crash في `filesDir/last_crash.txt` ثم يعيد تمرير الاستثناء إلى handler السابق.

## الملفات الرئيسية التي تغيرت

| الملف | التغيير |
|---|---|
| `android/app/src/main/java/com/example/data/engine/ProcessingEngine.kt` | إزالة fallback المحلي وفرض Gateway HTTPS + token. |
| `android/app/src/main/java/com/example/data/worker/GatewayProcessingWorker.kt` | العامل canonical: استعادة remote job، polling، auto-resume للحالات interrupted/retry-wait، وتحديث Room/WorkManager. |
| `android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt` | مسار legacy remote-only محفوظ للتوافق ولم يعد المسار الذي تستخدمه `ContractApp`. |
| `android/app/src/main/java/com/example/data/repository/OpusRepository.kt` | تحويل `processNewVideo` إلى enqueue وانتظار terminal Room state بدلاً من تنفيذ محرك محلي. |
| `android/app/src/main/java/com/example/data/contract/ApiContractClient.kt` | إضافة resumable upload بالـSHA-256، جلسات upload قابلة لإعادة الاستخدام، وContent-Range مع error mapping. |
| `android/app/src/main/java/com/example/data/worker/ProcessingNotification.kt` | foreground notification وتحديث progress وقناة النتائج. |
| `android/app/src/main/java/com/example/MainActivity.kt` | طلب إذن الإشعارات على Android 13+. |
| `android/app/src/main/java/com/example/OpusApplication.kt` | crash handler يحفظ آخر stack trace. |
| `android/app/src/main/AndroidManifest.xml` | أقل صلاحيات صريحة، وإضافة foreground service type `dataSync`. |
| `android/app/build.gradle.kts` | إزالة Firebase/Google Services/Secrets plugin غير المستخدمة من APK. |
| `android/app/src/test/java/com/example/ProcessingEngineTest.kt` | اختبار Gateway-only ورفض المصدر/العنوان/token غير الصالح. |
| `android/app/src/test/java/com/example/ApiContractClientTest.kt` | اختبار upload ranges وSHA-256 وإكمال المصدر. |
| `docs/RELEASE.md` | تقرير الإصدار ومصفوفة التحقق وحدود اختبار الجهاز. |

## نتائج التحقق

نجح `:app:compileDebugKotlin` و`:app:lint` و`:app:assembleRelease` بعد توفير JDK 21 وAndroid SDK 36؛ الناتج الحالي unsigned. فشل التشغيل الكامل الأول بسبب غياب SDK، ثم فشل compilation واحد بسبب استدعاء SHA-256 غير صحيح وتم إصلاحه. محاولة `testDebugUnitTest` دخلت في انتظار تنزيل artifact خارجي من Robolectric ولم تُعتمد كاختبار ناجح؛ يجب إعادة تشغيل suite كاملة في CI أو بيئة ذات وصول مستقر إلى Maven قبل إصدار APK.

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


## جلسة التدقيق والتجهيز — 2026-08-26

تم فحص `supoclip-main.zip` باعتباره مرجعًا خارجيًا، وفحص بنية المستودع الحالي قبل أي نسخ. المرجع AGPL-3.0 ويحتوي على web stack وBackend متعدد الخدمات؛ لم تُنسخ منه ملفات source أو assets أو secrets أو build outputs. القرار المعتمد هو الاحتفاظ بمسار `android/` + `gateway/` + `pipeline/` كمسار APK canonical، مع استخدام أفكار UX وrubric فقط عبر إعادة تنفيذ مستقلة واختبارات regression.

تمت إضافة الوثائق المطلوبة التالية: `docs/API.md`, `docs/ENGINE.md`, `docs/AI_RUNTIME.md`, `docs/MEDIA_RUNTIME.md`, `docs/ANDROID_UI.md`, `docs/TEST_MATRIX.md`, `docs/PERFORMANCE.md`, `docs/SECURITY.md`, `docs/THIRD_PARTY_LICENSES.md`, `docs/REFERENCE_COMPARISON.md`, `docs/REFERENCE_MIGRATION_PLAN.md`, و`docs/MIGRATION_DECISIONS.md`. كما أضيف `scripts/verify.sh` لتشغيل Python regression وfrontend build، وتشغيل Android checks تلقائيًا عند توفر SDK.

## Evidence هذه الجلسة

| الفحص | النتيجة |
|---|---:|
| `python3 -m pytest -q` | 164 passed، 1 skipped، 4 deprecation warnings |
| `scripts/verify.sh` | نجح؛ Python وfrontend مرّا، Android skipped بسبب غياب SDK في البيئة الحالية |
| `npm ci && npm run build` | نجح، 0 vulnerabilities في audit الخاص بـnpm |
| `bash -n scripts/verify.sh` | PASS |
| reference license inspection | AGPL-3.0؛ لا code copied |
| Git status | تغييرات محصورة في الوثائق و`scripts/verify.sh` |

## Known blockers غير البرمجية

لا يزال القبول النهائي مشروطًا بتوفير Android SDK/JDK كاملين لبيئة البناء، جهاز Android فعلي أو emulator مستقر لاختبار install/open/picker/process death/export، Gateway خاص عبر HTTPS مع token، مزود ASR/diarization/LLM جاهز، وrelease keystore. لا يُعلن release accepted قبل حفظ job IDs وstage outputs وartifact hashes وscreenshots/logcat لهذه المسارات. هذه القيود موثقة تفصيليًا في `docs/FINAL_ACCEPTANCE.md` و`docs/RELEASE_BLOCKERS.md`.


## ملحق جلسة البناء والدمج — 2026-08-26

بعد مزامنة main مع تحديثات remote المتزامنة، حُفظت وثائق التدقيق وملفات الإصدار الأحدث من remote، وأُبقي إصلاح `ApiContractClient` واختباره كإضافة مستقلة. الإصلاح يقرأ `detail` الكائني وقائمة `errors` و`request_id` من Gateway بدل تحويل الكائن إلى نص غير مفيد. كما بقيت ملفات Android الثلاثة المطلوبة للبناء واعتماد MockWebServer موجودة.

| الفحص | النتيجة |
|---|---:|
| `python3 -m pytest -q` | 164 passed، 1 skipped |
| `:app:testDebugUnitTest` | PASS |
| `:app:lint` | PASS مع تحذيرات deprecated غير مانعة |
| `:app:assembleRelease` | PASS؛ الناتج unsigned لغياب keystore الإنتاجي |
| `:app:assembleDebug` | PASS؛ APK Debug موقّع v2 متاح خارج Git |
| `unzip -t` وAPK badging | PASS؛ package `com.aistudio.opuspro.apk` وSDK 36 |

نسخة release الإنتاجية يجب توقيعها لاحقًا باستخدام keystore خاص عبر متغيرات `KEYSTORE_PATH`, `STORE_PASSWORD`, و`KEY_PASSWORD`. لا تزال اختبارات الجهاز الحقيقي أو emulator المستقر وGateway الخاص ومزودات runtime شروطًا لتجربة end-to-end، وليست مغطاة بالكامل داخل sandbox.

## ملحق التنفيذ المحلي — 2026-08-26

تمت إضافة `pipeline/publikclip_pipeline/runtime/` كحد مستقل لإدارة موارد المضيف والوسائط والنماذج. `MediaManager` يصنف أخطاء `MEDIA_INVALID`, `FFMPEG_MISSING`, `FFMPEG_FAILED`, `UNSUPPORTED_FORMAT`, و`INSUFFICIENT_DISK` ويغطي probe/validation/audio/frame/transcode/render/cleanup. `ModelManager` يسجل name/version/size/checksum/source/local path وحالة installed/loaded، ويدعم verify/download/resume/load/unload/delete. يعرض Gateway الحالة في `GET /v1/processing/capabilities` تحت `details.runtime`، ويعلن `runtime_ready=false` عندما تكون النماذج المطلوبة مفقودة.

تم تحديث عميل Android ليقرأ `runtime_ready` ويعرضه في إعدادات Gateway، مع اختبار عقدي لذلك. أضيف CI assembly لـ`app-release-unsigned.apk` دون أسرار أو keystore. لم تُنسخ أي ملفات أو assets أو secrets من `supoclip-main.zip`؛ المرجع AGPL-3.0 كما يثبت ملف `LICENSE` المرفق، وهو موثق في `docs/THIRD_PARTY_LICENSES.md`.

نتيجة التحقق في هذه الجلسة: `123 passed` لاختبارات pipeline، و`48 passed, 1 skipped` لاختبارات Gateway، و`python3 -m compileall -q pipeline gateway` نجح، و`npm run typecheck` و`npm run build` نجحا بعد `npm ci`. تحقق runtime الفعلي من FFmpeg/ffprobe، واكتشف 6 نماذج معلنة مفقودة وأعاد `runtime_ready=false` كما هو متوقع. محاولة Android Gradle الحالية توقفت لأن Android SDK/`local.properties` غير موجودين في sandbox؛ لذلك لا أضيف ادعاء نجاح بناء Android جديد إلى هذه الجلسة.

## جلسة تدقيق whisper.cpp — 2026-08-26

تم فحص الأرشيف المرفق `whisper.cpp-master.zip` دون نسخ source أو binary أو model إلى المستودع. العينة `examples/whisper.android.java` هي Android Java/JNI ضيقة للـASR المحلي، تعتمد على ملفات المستودع الكامل وCMake/NDK، وتضع نموذج GGML وWAV داخل `assets`. لا تغطي video picker، upload، job lifecycle، checkpoints، diarization، events، candidates، scoring، camera، rendering، أو recovery بعد process death.

القرار هو **IGNORE_REFERENCE** لدمج JNI/CMake/GGML في APK في Wave الحالية، مع **ADD_REFERENCE** لفكرة benchmark/system-info و**IMPROVE_CURRENT** لحفظ timestamps ضمن العقود الحالية. يبقى المسار canonical: Android → Private Gateway → PublikClip Engine → AI/Media Runtime. أي ASR محلي مستقبلي يحتاج provider contract مستقلًا وقياس accuracy/latency/RAM/APK-size/battery على أجهزة حقيقية، ولا يغير remote path الافتراضي.

## Evidence هذه المراجعة

| الفحص | النتيجة |
|---|---|
| `python3 -m pytest -q` بعد تثبيت `gateway/requirements.txt` و`pipeline` | 164 passed، 1 skipped، 4 warnings |
| مراجعة `whisper.cpp/LICENSE` وnotices الفرعية | MIT root؛ notices إضافية داخل اختبارات مقتبسة من OpenAI Whisper |
| مراجعة Android sample README/JNI | local ASR demo فقط، وليس pipeline أو backend |
| Git status قبل التعديل | clean على `main`، متتبعًا `origin/main` |
| نطاق التغييرات في هذه الدفعة | وثائق المقارنة/التراخيص/التدقيق/الخطة والـhandoff، وresumable upload في عميل Android؛ لا production source من whisper.cpp |

## تنفيذ Wave 2/3 المحدود

تم تحديث `android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt` ليستخدم `POST /v1/sources/uploads`، يحسب SHA-256 للملف المحلي، يعيد استخدام جلسة بنفس `(bytes, sha256)` عند retry، يقرأ offset، ويرسل chunks بحجم 4 MiB مع `X-Upload-Offset` و`Content-Range`، ثم ينفذ `complete`. هذا يحافظ على Gateway كمسار المعالجة ولا يضيف whisper.cpp أو runtime native إلى APK.

## Pending work

لم يُثبت في هذه الجلسة Android Gradle build/device smoke أو Gateway production E2E بسبب غياب Android SDK/JDK release وبيئة Gateway خارجية. توحيد error envelope واختبار process death تبقيان ضمن موجات التنفيذ التالية؛ أما Android resumable upload فأُضيف في هذه الدفعة إلى `ProcessingGatewayClient` مع SHA-256 وsession dedupe وoffset/Content-Range، ويظل اختبار interruption على جهاز/Gateway فعلي مطلوبًا. لا يجوز إعلان release نهائي قبل توفير evidence لهذه المسارات.

راجع `docs/REFERENCE_COMPARISON.md` و`docs/REFERENCE_MIGRATION_PLAN.md` و`docs/THIRD_PARTY_LICENSES.md` و`docs/AUDIT.md` قبل بدء أي integration لاحق.

## هذه الجولة — Android resumable upload والتحقق

بعد دمج تغييرات العمل المتوازي، أضيف إلى `GatewayApiClient` مسار resumable فعلي يستخدم `POST /v1/sources/uploads`، SHA-256، `Content-Range`، `X-Upload-Offset`، chunk sizing، واستعلام الحالة عند انقطاع الشبكة. إعادة تشغيل WorkManager بنفس المصدر تعيد استخدام جلسة Gateway الجزئية لأن الخادم يطابق `sha256 + bytes`. أضيفت اختبارات contract للـinitialization، ranges، completion، واستئناف offset، وأصبحت قيمة `llm` جزءًا من `GatewayConfig` بدل hardcoding داخل العميل. أزيل Foojay resolver غير الضروري من `android/settings.gradle.kts` حتى يستخدم البناء JDK المحلي دون plugin خارجي.

نتائج التحقق الأخيرة: `python3 -m compileall -q pipeline/publikclip_pipeline backend gateway` نجح؛ pipeline tests `117 passed`؛ Gateway tests `39 passed, 1 skipped`؛ backend tests `6 passed`؛ وAndroid `:app:compileDebugKotlin :app:assembleDebug` نجح باستخدام JDK 21 وAndroid API 36. نجح subset من Android unit tests. full Android unit-test task وصل إلى runtime لكنه فشل في `RemoteGatewayApiContractTest` بسبب TLS أثناء تنزيل artifact Robolectric عبر `MavenArtifactFetcher`، وليس بسبب compiler error؛ يلزم CI أو بيئة Maven مستقرة لإثبات اختبار HTTP الكامل. لم يُعتمد device/emulator smoke.

الـreference الإضافي في هذه الجولة هو `VideoClipper-main` المرفق بترخيص MIT؛ تمت إضافة تفاصيله إلى `docs/REFERENCE_COMPARISON.md` و`docs/THIRD_PARTY_LICENSES.md`، ولم تُنسخ منه ملفات إلى production.


## الإغلاق بعد دمج التحديثات المتزامنة — 2026-08-26

تم دمج `origin/main` دون force-push أو إسقاط تغييرات المتعاونين. التصميم النهائي يحتفظ بـ`ProcessingGatewayClient` كممر Android، ويجمع بين الرفع المتقطع SHA-256/offset وبين حفظ `remoteGatewayJobId` لإعادة polling وresume بدل إنشاء مهمة مكررة. كما أصبح retry للمهمة الملغاة يمسح المعرف البعيد ويبدأ معالجة جديدة، بينما تبقى المهمة interrupted قابلة للاستئناف. كما أضيفت أدلة runtime الخاصة بإدارة hardware/media/models واختبارات resume العقدية.

| الفحص الأخير | النتيجة |
|---|---|
| Python regression بعد الدمج | 172 passed، 1 skipped، 5 warnings |
| Android post-merge `:app:compileDebugKotlin` | PASS |
| Android post-merge `:app:assembleDebug` | PASS |
| Android `:app:lint` و`:app:assembleRelease` | PASS في بيئة JDK 21 وAndroid SDK API 36 |
| Android resume contract test | PASS في التشغيل المحدد |
| Android full Robolectric suite | لا يُعتمد كاختبار كامل؛ بعض التشغيلات توقفت تحت ضغط الذاكرة/اعتماد Maven، بينما compilation وsubset resume نجحا |
| Frontend production build | PASS وفق evidence الدمج المتزامن |

ظهر تحذير packaging معروف بأن مكتبات native محددة لم تُجرَ لها strip فتم تضمينها كما هي؛ لم يمنع ذلك نجاح البناء. لا يوجد APK أو build cache متعمد داخل Git. يبقى اختبار upload الحقيقي، device smoke، وGateway production E2E مطلوبًا قبل إصدار عام، كما يلزم release keystore وprovider/model readiness. لا توجد أسرار أو keystores في هذه الدفعة. تحفظ أدلة الدمج في `evidence/current_run/merged_verify.log` و`evidence/current_run/merged_android_verify.log`.
