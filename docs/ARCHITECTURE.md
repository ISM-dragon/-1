# ISM / PublikClip — Architecture Baseline

**الحالة:** وثيقة معمارية ناتجة عن تدقيق المستودع، وليست خطة إعادة كتابة.
**النطاق:** تطبيق Android شخصي لمستخدم واحد، وGateway خاص، ومحرك PublikClip مستقل.
**المبدأ الحاكم:** نعيد استخدام الوظائف الموجودة ونفصل المسؤوليات قبل إضافة أي implementation جديد.

## 1. الهدف والحدود

المنتج المستهدف ليس SaaS ولا خدمة متعددة المستخدمين. لا توجد في النطاق حسابات مستخدمين، billing، subscriptions، أو multi-tenancy. الهوية التشغيلية هي جهاز شخصي واحد يتصل بـGateway خاص يملك أسرار المزودين ويشغل المعالجة الثقيلة. تبقى وظائف النشر الاجتماعي والتحليلات موجودة في المستودع، لكنها اختيارية وليست شرطًا لمسار إنتاج APK الشخصي.

المستودع الحالي أكبر من هذا الهدف؛ فهو يحتوي عميل React/Tauri لسطح المكتب، وعميل Android native مستقل، ونسخة Android مولدة داخل Tauri، وGateway، وbackend بديل، وPython pipeline. لذلك فإن العمارة canonical المقترحة هنا لا تعني حذف المسارات الأخرى، بل تعني تحديد المسار الذي يجب أن يعتمد عليه APK الشخصي. الجرد الفعلي تؤكده بنية المشروع وملفات البناء [1] [2] [3].

## 2. الطوبولوجيا canonical

```text
┌──────────────────────────────────────────────┐
│ Android APK — personal client                │
│ import/camera, URI access, upload, polling,  │
│ offline WorkManager, preview/cache/export     │
└──────────────────────┬───────────────────────┘
                       │ HTTPS / private LAN in debug
                       │ Bearer session + device binding
                       ▼
┌──────────────────────────────────────────────┐
│ Private Gateway — single private control plane│
│ auth, upload sessions, SQLite state, workers, │
│ storage, idempotency, diagnostics, API        │
└──────────────────────┬───────────────────────┘
                       │ local subprocess boundary
                       ▼
┌──────────────────────────────────────────────┐
│ PublikClip Engine — Python public contract v1 │
│ job lifecycle, stage orchestration, progress, │
│ cancellation, resume, checkpoint/artifact     │
└──────────────────────┬───────────────────────┘
                       ▼
┌──────────────────────────────────────────────┐
│ AI / Media Runtime — private server           │
│ Python 3.12, WhisperX, Torch, diarization,    │
│ event models, face/ASD, FFmpeg/ffprobe,       │
│ model cache, optional Gemini/Ollama           │
└──────────────────────────────────────────────┘
```

هذا الرسم يحدد **حدودًا** لا أسماء مشاريع فقط: Android لا يستورد Python ولا يعرف أسماء stages أو ملفات checkpoints؛ Gateway لا يملك خوارزميات scoring أو camera؛ Engine لا يملك auth أو provider credentials؛ وAI/Media Runtime لا يرسل أسراره إلى العميل.

## 3. ملكية المكونات

| المكوّن | الموجود فعليًا | الملكية في العمارة canonical | ما يجب ألا يفعله |
|---|---|---|---|
| Android native (`android/`) | Jetpack Compose، Room، WorkManager، Media3، عميل Gateway، ومسار Kotlin محلي | التطبيق الشخصي: اختيار الملف/الكاميرا، تثبيت URI، الرفع، polling، offline/retry، cache، preview/export | تشغيل Python/uv/WhisperX/PyTorch أو حمل Gemini server key |
| Tauri/React (`app/`) | UI desktop، Tauri shell، استدعاء CLI محلي، ومكالمات Gateway/legacy social | مسار desktop مستقل للتطوير/المراجعة والتصدير؛ مصدر مرجعي لواجهات UX | أن يصبح اعتماد APK على Tauri-generated Android دون قرار صريح |
| Tauri generated Android (`app/src-tauri/gen/android`) | مشروع Android مولد، identifier `com.publikhq.publikclip`، وموارد Tauri | مسار منفصل يجب اعتباره غير canonical للـAPK الشخصي حتى توحيد القرار | مشاركة هوية أو lifecycle مع `android/` بلا migration موثق |
| Private Gateway (`gateway/`) | FastAPI، SQLite، worker queues، uploads، diagnostics، secret vault، processing bridge | **الـcontrol plane الوحيد** لمسار Android | نشر Mock كأنه provider حقيقي أو تشغيل endpoint عام بلا auth |
| Backend (`backend/`) | FastAPI آخر مع DB/storage/auth/device binding وJobManager | stack بديل/legacy للاختبار أو مادة دمج مستقبلية؛ ليس المسار الذي يستعمله Android حاليًا | تطوير API ثانٍ بالتوازي مع Gateway دون قرار دمج |
| PublikClip Engine (`pipeline/publikclip_pipeline/engine`) | `ProcessingEngine` وcontracts v1 و`PipelineEngine` | عقد المعالجة العام الذي يستدعيه Gateway والـCLI والاختبارات | معرفة UI أو social accounts أو OAuth |
| Python stages | ingest، ASR، diarization، events، candidates، scoring، camera، render | خوارزميات التحليل والتحرير والرندر على الخادم | الانتقال إلى Android native كنسخة صامتة أو متباينة بلا contract |
| AI/Media runtime | Python 3.12، runtime dependencies، model registry/cache، FFmpeg | تشغيل خاص على host/container دائم، مع disk وCPU/RAM كافيين | وضع نماذج أو مفاتيح server في APK |
| Social/OAuth/analytics | Gateway mock routes، provider registry، وInstagram loop محلي | optional server-side feature بعد استقرار processing path | أن تكون شرطًا لإنشاء clip شخصي |

## 4. lifecycle التشغيلي

يبدأ Android بقراءة ملف محلي من `content://` أو `file://`، ثم يرسل bytes إلى Gateway. بعد اكتمال الرفع والتحقق من الحجم وSHA-256، ينشئ Gateway processing job idempotently. يحتفظ Gateway بالحالة والـcorrelation ID، ويرسل المهمة إلى worker واحد افتراضيًا، ويشغل CLI/Engine في عملية Python منفصلة. يقرأ progress JSONL، ويحفظ `pipeline_job_id`، ويعيد استخدام checkpoint عند resume. عند اكتمال `render.json` ووجود ملفات MP4 سليمة، يعرض Gateway نتائج مسموحًا بها ويقوم Android بالتنزيل والاستيراد إلى Room [4] [5] [6].

```text
local URI
  → Android upload session
  → Gateway source artifact + checksum
  → processing job (idempotency key)
  → worker subprocess
  → Engine stages/checkpoints
  → validated render outputs
  → authenticated download
  → Android Room + app-private cache
```

الـURL الخارجي، مثل YouTube، يظل capability إضافية: إما أن يرسل Android رابطًا إلى Gateway ليتولى `yt-dlp`، أو يستخدم العميل upload لملف محلي. لا ينبغي أن يخلط العقد بين هذين المصدرين؛ `source_url` ليس bytes مرفوعة، و`content://` لا يستطيع الخادم قراءته مباشرة.

## 5. إعادة الاستخدام مقابل إعادة التصميم

| القرار | المكونات المعنية | الحكم |
|---|---|---|
| إعادة استخدام | `engine/contracts.py`, `engine/pipeline.py`, `jobs/queue.py` | نواة صحيحة لفصل المحرك؛ تثبيت contract v1 وإضافة adapters فقط |
| إعادة استخدام | stages وvendor models و`render/ffmpeg_bin.py` | تبقى على private runtime؛ لا تنقل إلى APK |
| إعادة استخدام مع adapter | `gateway/main.py`, `processing_service.py`, `worker_queue.py` | اجعل Gateway هو API الوحيد، ثم استبدل parsing JSONL تدريجيًا بـEngine adapter مباشر دون كسر fallback |
| إعادة استخدام | Android `WorkManager`, Room, `ProcessingGatewayClient` | أساس جيد لمسار remote؛ يجب مواءمته مع resumable upload وcontract error envelope |
| إعادة تصميم تدريجي | Android `ProductionVideoPipeline` | لا يُحذف الآن، لكن لا يُعلن parity مع Python؛ يفضل جعله fallback تجريبيًا أو إيقاف routing المحلي في production APK |
| إعادة تصميم | وجود `backend/` بجانب `gateway/` | لا تُضاف features جديدة إلى stackين؛ يحدد القرار التالي Gateway canonical ثم يُجمّد backend أو يُدمج لاحقًا |
| إعادة تصميم صغيرة | legacy social routes وmock publisher | تبقى للتوافق، لكن تُوسم development-only وتُفصل عن مسار processing الشخصي |
| إعادة تصميم release | native Android مقابل Tauri generated Android | اختيار artifact واحد وapplication identity واحدة قبل النشر؛ لا migration تلقائي |

## 6. نشر الـPrivate Backend

النشر الموصى به هو Gateway واحد خلف شبكة خاصة أو reverse proxy/VPN، مع volume دائم لـSQLite، sources، processing، model cache، وoutputs. `docker-compose.gateway.yml` يربط المنفذ محليًا على `127.0.0.1`، وتحتاج البيئة الفعلية إلى reverse proxy أو VPN للوصول من الهاتف. يجب ضبط `PUBLIC_BASE_URL` على عنوان قابل للوصول من Android، لا القيمة الافتراضية localhost، وتفعيل `REQUIRE_GATEWAY_TOKEN=true` و`GATEWAY_TOKEN` طويل ومخزن خارج Git [7] [8].

لا يحتاج هذا التصميم إلى قاعدة بيانات مُدارة أو multi-tenancy؛ SQLite مناسب لمالك واحد ومثيل worker واحد في المرحلة الحالية. لكنه ليس تصميمًا للتوسع الأفقي؛ queue الحالية داخل الذاكرة، ولذلك يمنع تشغيل أكثر من Gateway worker owner للمجلد نفسه قبل إضافة DB lease.

## 7. تعريف الجاهزية

يعد المسار جاهزًا للتجربة الشخصية عندما ينجح: health/readiness، pipeline importability، FFmpeg/ffprobe، storage write، Gemini diagnostic عند اختيار Gemini، upload مع checksum، job completion، تنزيل MP4، وإعادة تشغيل/resume. نجاح build وحده لا يثبت نجاح هذه السلسلة. كما أن نجاح Android unit tests لا يثبت وجود APK قابل للتثبيت أو وصول الهاتف إلى Gateway.

### المراجع

[1]: ../README.md "Repository README and current desktop/pipeline status"
[2]: ../android/app/build.gradle.kts "Native Android build configuration"
[3]: ../app/src-tauri/tauri.conf.json "Tauri product and resource configuration"
[4]: ../pipeline/publikclip_pipeline/engine/contracts.py "Engine public contract v1"
[5]: ../pipeline/publikclip_pipeline/engine/pipeline.py "PipelineEngine orchestration"
[6]: ../android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Android local/remote routing and background execution"
[7]: ../gateway/main.py "Gateway runtime, auth, jobs, storage, and workers"
[8]: ../docker-compose.gateway.yml "Private Gateway compose deployment"
