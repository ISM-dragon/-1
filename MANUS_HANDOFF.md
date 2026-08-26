# MANUS_HANDOFF

**المشروع:** `publikclip` داخل المستودع `ISM-dragon/-1`  
**تاريخ التسليم:** 2026-08-26  
**نطاق هذه الجلسة:** متابعة انتقال عميل Android إلى APK شخصي يعتمد على Gateway خاص، دمج أحدث تغييرات `origin/main` حتى commit `d7d922e`، إصلاح أعطال CI والاختبارات، ومن دون إعادة كتابة الـPython pipeline أو حذفها.

## الخلاصة التنفيذية

المستودع لا يبدأ من تطبيق فارغ. توجد فيه ثلاثة حدود تشغيلية قائمة: تطبيق سطح مكتب React/Tauri، تطبيق Android أصلي مستقل بـKotlin وJetpack Compose، وGateway بـFastAPI ينسق حالات الوظائف، ورفع الوسائط القابل للاستئناف، وطابور المعالجة، ثم يشغّل Python pipeline عبر subprocess وبروتوكول JSONL. لذلك كان القرار الأقل مخاطرة هو اعتماد تطبيق Android الأصلي كنقطة بناء الـAPK، وإبقاء Gateway هو محرك المعالجة خارج الهاتف، مع الحفاظ على pipeline الحالية كما هي.

أصبح مسار المعالجة في Android صريحًا **Gateway-only**: لا تُقبل جدولة مهمة جديدة ما لم يتوفر عنوان Gateway صالح ورمز جلسة، ولا يعود `VideoProcessingWorker` إلى `ProductionVideoPipeline` المحلية. دُمجت كذلك أحدث تغييرات `origin/main` الخاصة بالمصادر البعيدة، ودورة حياة الوسائط القابلة للاستئناف، وعقد pipeline، وأصلحت أخطاء CI التي كانت تمنع التحقق من الفرع البعيد. بقي `ProductionVideoPipeline.kt` في شجرة المصدر ولم يُحذف؛ لكنه لم يعد route تشغيليًا للعامل. أضيف كذلك استئناف لمهمة Gateway المحفوظة، بحيث لا يعيد retry/resume رفع الفيديو أو إنشاء مهمة بعيدة جديدة، ويُستخدم معرف المهمة المحلي كمفتاح idempotency عند الإنشاء الأول.

## المعمارية الحالية قبل التعديل

| الحد | التنفيذ | المسؤولية | الملاحظة |
|---|---|---|---|
| Desktop Studio | `app/`، React + Tauri | واجهة سطح المكتب، وتشغيل Python محليًا عبر `uv` في desktop | يحتفظ بمساره المحلي ولم يُمسّ.
| Tauri Android shell | `app/src-tauri/` | غلاف Android للواجهة الويب | يحتوي guard صريحًا يمنع تشغيل Python/`uv` داخل Android.
| Native Android Studio | `android/`، Kotlin + Compose | اختيار الفيديو، WorkManager، Room، العرض والتنزيل المحلي | هذا هو العميل الأنسب للـAPK المستقل.
| Processing Gateway | `gateway/main.py` | API، bearer token، SQLite، queue، حالات الوظائف، رفع/تنزيل الوسائط | يملك حدود الأمن وحالة المهمة ولا يفترض وجود Python على الهاتف.
| Python pipeline | `pipeline/` | ingest، ASR، diarization، scoring، editing، rendering، artifacts | التنفيذ القائم يُستدعى من Gateway عبر JSONL، ويُعاد استخدامه كما هو.

التدفق السابق في Native Android كان هجينًا: `ProcessingEngine` يعيد `LOCAL_PIPELINE` عند غياب Gateway، ويعيد `REMOTE_GATEWAY` عند وجوده. بعد ذلك كان `VideoProcessingWorker` قادرًا على تشغيل `ProductionVideoPipeline` المحلية أو رفع المصدر إلى Gateway. أما Tauri Android فكان أصلًا يرفض تشغيل Python محليًا، وكانت واجهة React البعيدة أضيق من العميل الأصلي.

## المعمارية المستهدفة

```text
Android APK (Kotlin + Compose)
    ├── Uri/File picker
    ├── Room: local job/project/clip cache
    ├── WorkManager: network-aware background execution
    ├── ProcessingEngine: validates Gateway-only route
    └── ProcessingGatewayClient
            │ HTTPS + Authorization: Bearer gateway-session-token
            ▼
Private Processing Gateway (FastAPI)
    ├── authenticated API and durable SQLite job state
    ├── source upload boundary and media artifact validation
    ├── persistent worker queue, retry, cancel, resume
    └── subprocess JSONL bridge
            ▼
Existing Python pipeline + FFmpeg + WhisperX/AI models
    └── rendered MP4 clips and checkpoint artifacts
```

الهاتف لا يحتاج Python أو `uv` أو WhisperX أو PyTorch أو FFmpeg. الهاتف يرفع ملف `content://` أو `file://` إلى Gateway، يستطلع حالة المهمة، ينزّل ملفات MP4 التي تحققت البوابة من وجودها، ثم يستوردها إلى Room. يحتفظ Gateway بمفتاح Gemini وأي أسرار مزودين على الخادم، ولا تُرسل هذه الأسرار في APK أو JSON المهمة.

هذا ليس نظام SaaS متعدد المستخدمين. الرمز الموجود في هذا التصميم هو **رمز خدمة خاص بين APK وGateway** وليس تسجيل دخول مستخدمين أو بنية multi-user. يظل Gateway خاصًا، ويجب استخدام HTTPS خارج شبكة LAN موثوقة، مع `REQUIRE_GATEWAY_TOKEN=true` في النشر الشخصي.

## الملفات المهمة

| الملف | الدور الحالي |
|---|---|
| `android/app/src/main/java/com/example/data/engine/ProcessingEngine.kt` | حد القرار؛ يتحقق من المصدر المحلي ومن عنوان Gateway ورمزه، ويعيد route بعيدًا فقط.
| `android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt` | WorkManager executor؛ يرفع/يستطلع/ينزّل ويستورد النتيجة، من دون تشغيل المحرك المحلي.
| `android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt` | عقد HTTP للرفع، إنشاء الوظيفة، polling، cancel/retry/resume، وتنزيل artifacts.
| `android/app/src/main/java/com/example/data/repository/OpusRepository.kt` | جدولة WorkManager، حفظ Room، استيراد نتائج Gateway، والتحكم في دورة حياة الوظائف.
| `gateway/main.py` | API والـworker bridge؛ يشغّل أمر pipeline ويحوّل JSONL إلى حالات Gateway.
| `gateway/processing_service.py` | إعداد بيئة subprocess وبناء أمر pipeline وعزل الأسرار.
| `gateway/worker_queue.py` و`gateway/job_state.py` | صف العمل وحالات الوظائف والتحولات والاسترداد.
| `pipeline/publikclip_pipeline/cli.py` | CLI وبروتوكول JSONL الذي يجب الحفاظ عليه.
| `app/src-tauri/src/lib.rs` | مسار سطح المكتب المحلي وguard الخاص بـTauri Android؛ لم يُستخدم كمحرك APK الأصلي.
| `android/app/build.gradle.kts` و`android/app/src/main/AndroidManifest.xml` | هوية وبناء وصلاحيات تطبيق Android.
| `docs/API-CONTRACT.md` و`gateway/README.md` | عقد API ومتطلبات تشغيل Gateway.

## المشاكل التي كشفها الفحص

أولًا، المشروع يضم مسارين Android مختلفين: Tauri Android وNative Android. Tauri Android يمنع Python محليًا عمدًا، بينما Native Android كان يحتوي fallback محليًا مستقلًا عن Python. هذا كان يترك المعمارية الهجينة غير متطابقة مع الهدف المعلن `Android APK = UI/client`.

ثانيًا، كان غياب Gateway يؤدي إلى اختيار `LOCAL_PIPELINE` بدل فشل مبكر. وبعد دمج upstream، تم الحفاظ على دعم مصادر HTTP/HTTPS التي يقرأها Gateway، إلى جانب ملفات `content://` و`file://` التي يرفعها العميل.

ثالثًا، كانت النتيجة العملية هي إمكانية إنشاء WorkManager job ينجح فقط إذا كانت الاعتماديات المحلية متاحة، وهو عكس قرار الاعتماد على محرك خاص خارجي. عولج ذلك الآن في `ProcessingEngine` وداخل `OpusRepository.enqueueVideoProcessing`.

رابعًا، كانت إعادة المحاولة أو الاستئناف في العامل تعيد تنفيذ `process(...)` مع رفع الملف وإنشاء job جديد؛ وكان Gateway يدعم idempotency في عقده، لكن العميل لم يثبت المفتاح على دورة المهمة المحلية، كما أن مسار repository لم يكن يستدعي control endpoint البعيد قبل إعادة الجدولة. عولج ذلك باستخدام `jobId` المحلي كمفتاح idempotency، وتمرير `remoteGatewayJobId` الموجود لاستئناف polling دون إعادة الرفع، واستدعاء `/retry` أو `/resume` على Gateway قبل إعادة WorkManager.

خامسًا، أظهر أول build فعلي بعد توفير SDK وJDK خطأ ترجمة موجودًا في `OpusBottomNav.kt`: `NavigationBarItem` هو امتداد لـ`RowScope` في نسخة Material3 المستخدمة، بينما كان helper خارج هذا الـscope. أُصلح ذلك بتحويل `PrimaryItem` إلى `RowScope.PrimaryItem` دون تغيير السلوك المرئي.

سادسًا، أظهر Quality Gate على GitHub أن Workflow الاختبارات لم يثبت `gateway/requirements.txt`، ففشل collection أولًا بسبب `ModuleNotFoundError: fastapi` ثم كشف التشغيل التالي غياب `httpx` الذي يستورده `ProviderRouter`. كما كان اختبار `uploaded_source_path` لا يهيئ جدول `media_uploads` ولا يسجل upload مكتملًا بعد إضافة التحقق من الحجم وSHA-256. وأظهر التشغيل التالي أن اختبار الوسائط الفاسدة يعتمد ضمنيًا على وجود FFprobe في runner؛ عُزل ذلك الاختبار باستخدام mock يعيد probe فاشلًا، مع إبقاء اختبار جاهزية FFprobe منفصلًا. بعد تجاوز هذه النقاط وصل CI إلى اختبار render وكشف غياب `ffmpeg` من runner؛ أضيف تثبيت FFmpeg صراحةً إلى Workflow. عولجت المشاكل في Workflow والاختبارات نفسها، من دون تخفيف فحوص الأمان.

## القرارات التقنية

| القرار | السبب |
|---|---|
| اعتماد `android/` كتطبيق APK الأصلي | هو تطبيق Android مستقل بالفعل ويملك Compose وRoom وWorkManager وتدفق رفع/تنزيل مكتملًا؛ لا حاجة لنسخ `app/src` أو إعادة بناء UI من الصفر.
| جعل Android Gateway-only | يمنع الاعتماد الخفي على Python/AI/FFmpeg محليًا ويطابق حدود العميل/المحرك المطلوبة.
| إبقاء `ProductionVideoPipeline.kt` دون حذف | يحافظ على functionality موجودة ويجعل العزل قابلًا للعكس، مع منع العامل الحالي من اختياره.
| الإبقاء على Room وWorkManager | يوفران cache محليًا واستمرارًا في الخلفية وإعادة محاولة وإشعارات دون إعادة تصميم.
| استخدام token خاص بدل user authentication | يحقق خصوصية Gateway الشخصي من دون إدخال multi-user architecture أو حسابات مستخدمين.
| استخدام `jobId` المحلي كمفتاح idempotency | يمنع إنشاء Gateway jobs مكررة عند إعادة التنفيذ لنفس المهمة المحلية.
| عدم إرسال Gemini key إلى الهاتف | الأسرار تبقى على Gateway، وهو الحد الأمني المناسب للمحرك الخاص.
| تثبيت متطلبات Gateway في Quality Gate | يجعل CI يختبر البيئة المعلنة فعليًا بدل الاعتماد على packages موجودة صدفة على runner.
| اختبار upload المكتمل بقاعدة مؤقتة | يحافظ على فحص الحجم وSHA-256 وstatus بدل تحويل الاختبار إلى stub غير ممثل للإنتاج.

## التعديلات التي تمت

| الملف | التعديل |
|---|---|
| `android/app/src/main/java/com/example/data/engine/ProcessingEngine.kt` | إزالة route المحلي من enum؛ اشتراط Gateway URL وtoken؛ استخدام `java.net.URI` القابل للاختبار في JVM؛ الإبقاء على المصدر المحلي كمدخل للرفع فقط.
| `android/app/src/main/java/com/example/data/repository/OpusRepository.kt` | إضافة فحص `ProcessingEngine` قبل نسخ المصدر أو إنشاء Room/WorkManager job.
| `android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt` | عزل وإزالة فرع `ProductionVideoPipeline` من العامل؛ تمرير `remoteGatewayJobId` و`jobId` إلى عميل Gateway؛ الإبقاء على دورة cancel/retry/error الحالية.
| `android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt` | دعم `existingGatewayJobId` و`idempotencyKey`؛ إعادة الرفع والإنشاء فقط عند عدم وجود remote job محفوظ.
| `android/app/src/main/java/com/example/data/repository/OpusRepository.kt` | استدعاء Gateway `/retry` و`/resume` فعليًا قبل إعادة جدولة المهمة المحلية.
| `android/app/src/test/java/com/example/ProcessingEngineTest.kt` | استبدال اختبار fallback المحلي باختبارات فشل مبكر للعنوان والرمز، مع تغطية route البعيد والمصدر والعنوان غير الصالح.
| `android/app/src/main/java/com/example/ui/components/OpusBottomNav.kt` | إصلاح receiver الخاص بـ`NavigationBarItem` حتى تترجم واجهة Android.
| `android/README.md` و`ANDROID.md` | توثيق تدفق APK الشخصي Gateway-only، ومكان Python/FFmpeg/WhisperX خارج الهاتف.
| `.github/workflows/quality-gate.yml` | تثبيت `gateway/requirements.txt` و`httpx` قبل اختبارات Python، وتثبيت FFmpeg قبل اختبار render.
| `gateway/test_processing_bridge.py` | تهيئة SQLite المؤقتة وإضافة upload مكتمل مطابق للحجم وSHA-256.
| `gateway/tests/test_gateway_safety.py` | جعل اختبار الوسائط الفاسدة deterministic عبر mock لـFFprobe مع الحفاظ على عقد 422.
| `MANUS_HANDOFF.md` | هذه الوثيقة.

لم تُعدّل Python pipeline أو Gateway API في هذه الجلسة لأن الحد الفاصل القائم، بعد دمج تحسينات `origin/main`، كان كافيًا لإعادة الاستخدام. أُصلح اختبار Gateway وWorkflow التحقق فقط. لم تُحذف الوظائف الاجتماعية أو التحليلية أو إعدادات المزودين، لأنها ليست blocker لمسار معالجة الفيديو الشخصي.

## التعديلات التي لم تتم

لم يتم بعد تغيير `applicationId` أو `namespace` من القيم الحالية (`com.aistudio.opuspro.apk` و`com.example`). هذا يحتاج قرار هوية نهائيًا ومراجعة migration، ولا ينبغي تنفيذه عشوائيًا لأنه قد يؤثر على التثبيت والتوقيع والتوافق.

لم يتم إنشاء release signing key أو توقيع production APK. الموجود حاليًا هو مسار debug، وملف signing يعتمد على متغيرات بيئية وkeystore غير متعقب. كما أن assembleDebug الكامل لم يصل إلى إنشاء artifact بسبب ضغط الذاكرة، رغم نجاح الترجمة واختبار الوحدة المستهدف في الجولة السابقة.

لم يتم نشر Gateway أو ربطه بجهاز دائم أو GPU أو بيئة تملك dependencies الفعلية. لم يتم تنفيذ معالجة MP4 حقيقية من الهاتف، لأن ذلك يتطلب عنوان Gateway قابلًا للوصول، token، FFmpeg، Python/pipeline dependencies، اعتماد Gemini صالحًا عند استخدام Gemini، ومصدر فيديو اختباريًا يملكه المستخدم.

لم يتم حذف الاعتماديات المحلية الخاصة بمسار Android القديم من `build.gradle.kts`، لأن ذلك سيكون تقليصًا أوسع من المطلوب وقد يكسر شاشات أو وظائف أخرى. العزل الحالي تشغيلي، والخطوة التالية يمكن أن تكون dependency audit ثم إزالة آمنة مع اختبارات.

لم يتم بناء Tauri APK؛ الهدف المعتمد لهذه الجلسة هو Native Android الموجود في `android/`. يبقى Tauri desktop محفوظًا لمسار سطح المكتب، وTauri Android يحتاج قرارًا منفصلًا إذا أريد أن يصبح هو العميل الوحيد.

## الاختبارات والتحقق

| الفحص | النتيجة |
|---|---|
| `git diff --check` | نجح.
| `./gradlew :app:lint` | لم يكتمل محليًا بسبب ضغط ذاكرة مرتفع في sandbox؛ لا توجد نتيجة lint نهائية محلية.
| `./gradlew :app:assembleDebug` | لم يكتمل محليًا بسبب ضغط الذاكرة عند مراحل dex؛ لا يوجد APK محلي نهائي من هذه الجلسة.
| `python3 -m pytest gateway -q` | نجح: `40 passed, 1 skipped` مع 4 تحذيرات deprecation من FastAPI.
| `python3 -m pytest -q` | نجح: `157 passed, 1 skipped` مع 4 تحذيرات deprecation من FastAPI.
| `python3 -m compileall -q gateway pipeline/publikclip_pipeline` | نجح.
| اختبار Android المستهدف بعد rebase | نجح: `:app:testDebugUnitTest --tests com.example.ProcessingEngineTest`؛ ترجمة Kotlin وKSP وJava و6 حالات ProcessingEngine مرّت.
| `./gradlew :app:testDebugUnitTest` الكامل | بقي عالقًا محليًا في Test Executor ولم يُعتمد كنجاح؛ يلزم تأكيده عبر CI.
| `git diff --check` | نجح بعد كل التعديلات الحالية.
| Quality Gate على GitHub قبل الإصلاح | فشل بسبب FastAPI ثم `httpx` ثم اعتماد اختبار safety على FFprobe ثم غياب FFmpeg لاختبار render؛ تم إصلاح Workflow والاختبارات، ويجب تأكيد التشغيل الجديد بعد هذا commit.

## الافتراضات

يفترض هذا التسليم أن المستخدم يريد APK Native Android شخصيًا وليس إعادة استخدام Tauri Android كغلاف. ويفترض أن Gateway سيعمل على جهاز خاص أو خادم خاص يملك موارد المعالجة، وأن تخزين الفيديو مؤقت ويمكن رفعه إلى ذلك الجهاز. ويفترض أن رمز Gateway ليس user login بل secret خدمة واحد، وأن عدم وجود authentication متعدد المستخدمين قرار مقصود.

يفترض أيضًا أن عقد Gateway الحالي `/v1/sources/upload` و`/v1/processing/jobs` و`GET /v1/processing/jobs/{id}` وmedia download هو العقد الذي سيبقى ثابتًا في الجلسة التالية. إذا تغيرت أسماء الحقول أو حالات الوظائف في Gateway فيجب تحديث العميل واختبار العقد معًا.

## Blockers الحالية

الـblocker الأول تشغيلي: لا يوجد Gateway منشور وقابل للوصول من الجهاز، لذلك لم تُنفذ رحلة `upload → process → download → import` الحقيقية. يلزم تجهيز `GATEWAY_TOKEN` و`REQUIRE_GATEWAY_TOKEN=true` و`PUBLIC_BASE_URL` و`ISM_PROCESSING_ROOT` و`ISM_PIPELINE_DIR` وFFmpeg واعتماديات pipeline ومفتاح Gemini على الخادم فقط.

الـblocker الثاني متعلق بالـrelease: لا توجد هوية package نهائية أو signing configuration شخصية مؤكدة. لا ينبغي توزيع APK release قبل حسم ذلك.

الـblocker الثالث متعلق بالتحقق المحلي: اختبار Android الكامل بقي عالقًا في Test Executor، وLint وassembleDebug لم يُستكملا في sandbox بسبب الموارد؛ لذلك يجب اعتماد CI لتأكيد البناء الكامل. اختبارات Python الكاملة أصبحت تمر، مع بقاء اختبار الوسائط الكبير skipped عمدًا.

## Next steps للجلسة التالية

1. رفع الإصلاحات الحالية إلى GitHub وانتظار Quality Gate وEmbedded Android App، ثم معالجة أي failure جديد من logs بدل تجاوزه.
2. تجهيز Gateway على جهاز خاص أو خدمة دائمة، وضبط البيئة من دون وضع أي secret في Git أو APK، ثم تمرير اختبار `/health` و`/v1/processing/capabilities` وdiagnostic pipeline/Gemini.
3. تشغيل Native Android على جهاز أو emulator، اختبار اختيار فيديو محلي، الرفع، polling، تنزيل MP4، الاستيراد إلى Room، cancel، retry، وresume على نفس `remoteGatewayJobId`.
4. إكمال `assembleDebug` و`lint` في CI أو بيئة ذات ذاكرة كافية، وحفظ APK الناتج كartifact؛ ثم تشغيل اختبار instrumentation الأساسي على جهاز فعلي إن توفر.
5. تثبيت عقد idempotency والاستئناف باختبارات عميل/خادم، خصوصًا حالات انقطاع الشبكة بعد الرفع وقبل حفظ remote job.
6. تنفيذ dependency audit لمسار Android القديم: إزالة ما لا يلزم فقط بعد إثبات أن `ProductionVideoPipeline` غير مستورد في المسار المنتج وبعد مرور tests/build.
7. حسم package identity وrelease signing واسم التطبيق النهائي، ثم إنشاء مسار release APK منفصل عن debug.
8. بعد نجاح رحلة MP4 الحقيقية، تحديث هذه الوثيقة بحالة التشغيل الفعلية، artifact path، نتائج الاختبارات، وأي أخطاء متبقية.

## References داخل المستودع

- `docs/MASTER-ARCHITECTURE.md`
- `docs/API-CONTRACT.md`
- `docs/android/ANDROID_PROCESSING_AUDIT.md`
- `gateway/README.md`
- `ANDROID.md`
- `android/README.md`
