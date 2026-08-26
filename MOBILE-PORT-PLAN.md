# Publikclip: خطة النقل إلى تطبيق Android أصلي يعمل دون اتصال

**المشروع:** `publikclip`  
**الفرع:** `refactor/mobile-port`  
**تاريخ إعداد الخطة:** 26 أغسطس 2026  
**المؤلف:** Manus AI  
**النطاق الحالي:** الخطوة الأولى من النقل: تحليل المستودع، تنظيف البنية، وتثبيت التصميم التقني قبل البدء في دمج محركات الذكاء الاصطناعي الأصلية.

## 1. القرار التنفيذي

الهدف هو تحويل تطبيق `publikclip` من عميل سطح مكتب يعتمد على Python وGateway بعيد إلى تطبيق Android أصلي يستطيع تنفيذ **الاستيراد، التفريغ الصوتي، تحليل الصوت، اكتشاف الوجوه، اختيار المقاطع، والقص/التصيير** محليًا على الجهاز. سيبقى أي تقييم لغوي اختياري، مثل تقييم الفكاهة بواسطة LLM، خارج المسار الأساسي ومغلقًا افتراضيًا؛ ولن يكون وجود الشبكة شرطًا لإنشاء مقطع أو تصديره.

ستكون البنية المستهدفة تطبيقًا أحادي الوحدة في البداية، مبنيًا بـ Kotlin وJetpack Compose، مع حدود واضحة بين واجهة المستخدم، حالة المشروع، جدولة العمل، طبقة البيانات، ومحركات المعالجة الأصلية. هذا يقلل مخاطرة إعادة كتابة التطبيق دفعة واحدة، ويُبقي إمكانية فصل وحدات `core-media` و`core-ml` لاحقًا إذا أثبتت اختبارات البناء وحجم APK أن ذلك مفيد.

> **قاعدة التصميم:** لا تُعتبر المعالجة ناجحة إلا إذا أمكن تشغيلها من ملف محلي بعد قطع الشبكة، وإغلاق Activity، وإعادة فتح التطبيق، مع استعادة آخر checkpoint محفوظ.

## 2. نتيجة تحليل المستودع الحالي

يحتوي الجذر على أربعة مسارات رئيسية: `/pipeline` لمعالجة Python الثقيلة، و`/app` لواجهة Tauri/React المكتبية، و`/gateway` و`/backend` للخدمات الخلفية، و`/android` لتطبيق Jetpack Compose الأصلي. التطبيق Android قائم وقابل للبناء مبدئيًا، لكنه موصوف في `android/README.md` بأنه عميل **REMOTE_GATEWAY** فقط، ولا يضم مسار معالجة فيديو محليًا. لذلك فالمهمة ليست تحسين واجهة موجودة فحسب، بل استبدال عقد الاعتماد على Gateway بطبقة تنفيذ محلية مع الحفاظ على نماذج المشروع وتجربة الاستخدام قدر الإمكان.

الوحدة الحالية تستخدم Compose وRoom وWorkManager وMedia3 Transformer وMedia3 ExoPlayer وML Kit Face Detection وCoroutines وRetrofit/OkHttp. كما أن إعدادات الإصدار تستهدف `compileSdk 36.1` و`targetSdk 36` و`minSdk 24`، لكن `release.isMinifyEnabled` مضبوط حاليًا على `false`، وJava source/target مضبوط على 11. هذه نقاط انتقال مباشرة إلى متطلبات الإصدار النهائي، وليست تغييرات ينبغي خلطها مع أول commit للتنظيف.

| المجال | الموجود حاليًا | الفجوة أمام النقل المحلي | الإجراء المقترح |
|---|---|---|---|
| الواجهة | Jetpack Compose وشاشات Home/Studio/Projects | لا توجد تجربة مكتملة لمعالجة محلية قابلة للاستئناف | الإبقاء على Compose وإعادة ربط الحالة بـ `JobRepository` محلي |
| البيانات | Room وطبقات نماذج/اختبارات موجودة | النموذج الحالي مرتبط بحالة Gateway و`remoteGatewayJobId` | إضافة مراحل محلية وcheckpoints ونسخ schema تدريجية |
| جدولة العمل | WorkManager و`CoroutineWorker` موجودان | العامل الحالي يستطلع Gateway بدل تشغيل مراحل الجهاز | تحويل العامل إلى state machine محلية مع إشعار foreground |
| الوسائط | Media3 Transformer/ExoPlayer موجودان | لا يوجد مسار مؤكد لتغليف captions و9:16 rendering محليًا | اعتماد Media3 أولًا، مع طبقة native بديلة عند الحاجة |
| الرؤية | ML Kit Face Detection موجود | استخدامه الحالي ليس بعدُ خوارزمية crop زمنية مستقرة | تحليل عينات، تنعيم المسار، safe margins، وfallback مركزي |
| ASR | لا يوجد Whisper.cpp أو نموذج محلي | الاعتماد السابق على WhisperX في Python | إضافة JNI/C++ مع نموذج Whisper.cpp مُكمّم |
| الصوت | لا يوجد TFLite/YAMNet في الوحدة | لا يوجد laughter/vocal-energy محلي | إضافة TFLite AudioClassifier وميزات طاقة خفيفة |
| الإصدار | R8 غير مفعّل | خطر تضخم APK وتسريب قواعد native/ML | تفعيل minification بعد تثبيت keep rules وقياس ABI/model sizes |
| الخدمات | Gateway وPython ما زالا في الجذر | الاعتماد الشبكي يخالف هدف التطبيق المستقل | إبقاؤهما للتوافق/الانتقال فقط، لا كشرط لتشغيل APK |

## 3. التنظيف المنفذ في هذه الخطوة

أُنشئ الفرع `refactor/mobile-port` انطلاقًا من حالة `main` النظيفة. ونُفذت فيه إزالة مجلد `evidence/`، وحذف ملفات `*_HANDOFF.md`، وحذف `FINAL-PRODUCTION-AUDIT.md`، وإزالة شجرة Tauri Android المولدة `app/src-tauri/gen/android` لتجنب تعارضها مع الوحدة الأصلية `/android`.

تم حفظ التنظيف في commit مستقل بالرسالة:

```text
chore(android): clean repository for native mobile port
```

لم تُحذف مجلدات `/pipeline` أو `/gateway` أو `/app` في هذه المرحلة؛ فهي مراجع سلوكية وعقود انتقالية لازمة لمقارنة نتائج المحرك المحلي، ولا ينبغي إزالتها قبل تثبيت parity tests للمخرجات.

## 4. البنية التقنية المستهدفة

### 4.1 طبقات التطبيق

ستُقسم الوحدة منطقيًا إلى الطبقات التالية، حتى لو بقيت في module واحد أثناء المرحلة الأولى:

| الطبقة | المسؤولية | ممنوع عليها |
|---|---|---|
| `ui` | شاشات Compose، مشغل الفيديو، محرر captions، إشعارات التقدم | استدعاء JNI أو Room مباشرة |
| `presentation` | ViewModels، أحداث المستخدم، `StateFlow`، تحويل النماذج إلى UI state | تنفيذ معالجة ثقيلة |
| `domain` | حالات المهمة، use cases، عقود المحركات، سياسة الاستئناف | معرفة تفاصيل Android UI أو HTTP |
| `data` | Room DAOs، مستودع المشاريع، SAF importer، ملفات checkpoint | اتخاذ قرارات العرض |
| `worker` | جدولة المراحل، foreground notification، cancellation، retry/backoff | تخزين حالة خارج Repository |
| `media` | قراءة metadata، عينات الإطارات، القص، الترجمة، التصدير | إدارة دورة حياة Activity |
| `ml` | Whisper JNI، TFLite/YAMNet، ML Kit، normalization | معرفة Gateway |
| `native` | C/C++ bindings وCMake وABI packaging | تضمين مفاتيح أو endpoint |

العقد المركزي سيكون `LocalProcessingEngine`. يعرّف كل محرك مدخلاته ومخرجاته وحالته، ويُبقي إمكانية الاختبار عبر fake implementations:

```kotlin
interface LocalProcessingEngine {
    suspend fun process(jobId: String): ProcessingResult
    suspend fun resume(jobId: String, checkpoint: Checkpoint): ProcessingResult
    suspend fun cancel(jobId: String)
}
```

التوقيع أعلاه تصوري؛ سيُثبت في التنفيذ بعد مراجعة نماذج Room الحالية وعدم كسر اختبارات العقود الموجودة.

### 4.2 دورة حياة المهمة والـ checkpoints

ستُحفظ كل مهمة في Room مع `jobId` ثابت، ومسار الملف المحلي، وhash/size، وإعدادات القص، وإصدار النموذج، ومرحلة التنفيذ، ونسبة التقدم، وآخر خطأ، ومعلومات التصدير. ستكون المراحل قابلة لإعادة التشغيل idempotently، بحيث لا يؤدي crash بعد التصيير إلى إعادة تشغيل ASR من الصفر.

```text
IMPORTED
  -> PROBING
  -> ASR
  -> AUDIO_FEATURES
  -> FRAME_ANALYSIS
  -> SEGMENT_RANKING
  -> RENDERING
  -> FINALIZING
  -> COMPLETED

أي مرحلة -> RETRYABLE_FAILURE / CANCELED
```

لكل مرحلة ملف checkpoint صغير أو سجل Room مناسب، مثل `asr.json`, `audio_features.bin`, `face_track.json`, و`render_manifest.json`. لا تُحفظ نتائج كبيرة داخل SQLite؛ تحفظ الملفات في مساحة التطبيق الخاصة، بينما يحتفظ Room بالمسارات والحالة والchecksum.

### 4.3 استيراد الملفات وSAF

سيستخدم التطبيق `ACTION_OPEN_DOCUMENT` أو Photo Picker بحسب تجربة النظام، ثم ينسخ المحتوى إلى `filesDir/source_media/{jobId}` قبل جدولة العمل. يتيح SAF للمستخدم اختيار الملفات من مزودي التخزين المختلفين، ويميز `ACTION_OPEN_DOCUMENT` عن `ACTION_GET_CONTENT` بحسب الحاجة إلى صلاحية مستمرة أو استيراد نسخة [3].

ستتحقق طبقة الاستيراد من MIME type، والحجم المتاح، وإمكانية القراءة، ومدة الفيديو، ثم تحفظ URI الأصلي وبيانات النسخة. إذا تعذر الوصول إلى المصدر لاحقًا، تستمر المهمة من النسخة المحلية بدل الاعتماد على grant مؤقت.

### 4.4 ASR محلي مع Whisper.cpp

الخيار الأساسي هو **Whisper.cpp عبر JNI/CMake** مع نموذج GGML/GGUF مُكمّم مناسب للجهاز. سيغلف JNI عمليات تحميل النموذج، وتمرير PCM mono 16 kHz، والإلغاء، وقياس التقدم، وإرجاع segments وtokens وtimestamps. يدعم مشروع Whisper.cpp تشغيلًا أصليًا على منصات متعددة ويقدم خيار word-level timestamps تجريبيًا؛ لذلك يجب تثبيت نسخة/commit وفحص دقة الكلمات قبل اعتمادها في محرر captions [7].

سيكون model pack متعدد اللغات هو الافتراضي الأولي لأن التطبيق لا ينبغي أن يقيّد اللغات من خلال اسم النموذج. ستُقاس ثلاثة ملفات نموذجية على أجهزة منخفضة ومتوسطة وعالية: زمن المعالجة، الذاكرة القصوى، والدقة على عينة مرجعية. لا يُعتمد أي نموذج لا يحقق ميزانية الذاكرة أو يجعل APK يتجاوز حد الإصدار.

لن يُستخدم MediaPipe Speech Recognizer كمسار إنتاج أول في هذه المرحلة؛ يمكن تقييمه لاحقًا كتجربة بديلة إذا أثبت جودة timestamps وحجمًا أقل. السبب هو أن Whisper.cpp يطابق احتياج word-level captions مباشرة، بينما يجب ألا يُربط المسار الأساسي بخدمة أو تنزيل نموذج أثناء التشغيل.

### 4.5 تحليل الصوت عبر TFLite وميزات حتمية

سيُستخدم TFLite لتصنيف الإشارات الصوتية. سيكون YAMNet baseline لتصنيف أحداث الصوت؛ فهو يتنبأ بفئات AudioSet عديدة، ويظهر في التوثيق الرسمي مع مثال Android لتصنيف الصوت [8] [9]. ستُحسب **vocal energy** بصورة حتمية من إطارات PCM بعد normalization، مع smoothing وعتبات قابلة للمعايرة، بدل جعل حجم الخط معتمدًا مباشرة على score خام.

ستُعامل `Laughter` كإشارة مرشحة من YAMNet، ثم تُدمج مع الطاقة، والتوقفات، ونتيجة الكلام ضمن `AudioFeatureVector`. إذا أظهرت مجموعة التقييم أن YAMNet غير كافٍ في الضحك المختلط بالكلام، سيُضاف نموذج TFLite صغير مخصص ومدرب على بيانات مرخصة، لا dependency Python داخل APK.

### 4.6 التحليل البصري والقص الذكي 9:16

سيُستخرج عدد محدود من الإطارات عبر Media3/MediaExtractor أو طبقة قراءة مناسبة، بدل تمرير الفيديو كاملًا إلى detector. سيستخدم ML Kit Face Detection بنموذج **bundled** عندما يكون التشغيل دون اتصال أولوية؛ فالتوثيق يوضح أن النموذج المضمّن متاح فورًا لكنه يزيد حجم التطبيق، بينما النموذج unbundled أصغر ويعتمد على تنزيل Play Services [4]. كما يحدد الدليل حد `minSdk` وهو 23، بينما المشروع الحالي على 24، لذلك لا يوجد تعارض مبدئي.

ستحوّل صناديق الوجوه إلى track زمني عبر interpolation وexponential smoothing، مع safe margins ومراعاة اتجاه الفيديو ونسبة العرض. عند غياب وجه موثوق، يستخدم المحرك motion/center crop ثابتًا بدل إنتاج قفزات. وسيُختبر الناتج على فيديو شخص واحد، شخصين، خروج المتحدث من الإطار، وإطارات مظلمة.

### 4.7 القص والتصيير

المشروع يضم Media3 Transformer بالفعل. سيكون Media3 Transformer هو المسار الأول للقص، التحويل، وإعادة الترميز حيث يغطي المخرجات المطلوبة [6]. أما **FFmpegKit القديم فلن يُضاف كـ Maven artifact**؛ فالمستودع الرسمي يذكر أنه retired ومؤرشف، ويشير إلى FFmpegKitNext كاستمرار source-only [5].

إذا احتاج burn-in captions أو codec/filter غير متاحين في Media3، فستكون البدائل مرتبة هكذا: أولًا إضافة effect/Transformer أصلي؛ ثانيًا بناء FFmpegKitNext من مصدر مثبت commit مع ABIs محددة وقائمة codecs مرخصة؛ ثالثًا استخدام طبقة C/C++ ضيقة مبنية من FFmpeg الرسمي إذا لم يحقق FFmpegKitNext متطلبات الإصدار. هذا القرار يحافظ على مطلب المستخدم الوظيفي دون الاعتماد على مكتبة متقاعدة لا تتلقى تحديثات.

كل export سيكتب إلى ملف مؤقت، ثم يجري fsync/rename ذريًا، ويُنشئ manifest يتضمن codec، resolution، duration، checksum، وإصدار pipeline. إذا فشل التصدير، يبقى المصدر وcheckpoints السابقة صالحين لإعادة المحاولة.

## 5. WorkManager، Android 14+، والطاقة

سيُنفذ العامل كـ `CoroutineWorker` طويل التشغيل. يوضح توثيق Android أن WorkManager يدعم العمال الذين يتجاوزون عشر دقائق عبر foreground service وإشعار قابل للتحديث، وأن `setForeground()` هو الواجهة المعلقة المناسبة لـ Kotlin [1]. كما يوجب Android 14 تحديد foreground-service type للعمال طويلي التشغيل؛ لذلك سيبقى `dataSync` مصرحًا به في manifest ويُطابق runtime `ForegroundInfo`.

سيستخدم التطبيق unique work لكل `jobId` مع `ExistingWorkPolicy.KEEP`، وقيد `NetworkType.NOT_REQUIRED` للمسار المحلي، و`requiresBatteryNotLow` افتراضيًا، وbackoff انتقائي للأخطاء المؤقتة. سيظهر زر إلغاء داخل الإشعار، ويُحفظ آخر checkpoint قبل كل مرحلة. ابتداءً من Android 16 قد تتأثر الأعمال الطويلة بحصة JobScheduler؛ يوثق Android هذا الاحتمال ويقترح أحيانًا foreground service مباشرًا أو user-initiated data transfer حسب نوع العمل [1]. لذلك سيكون هذا release gate لاختبار طويل على Android 16، مع إبقاء worker/foreground abstraction قابلة لاستبدال التنفيذ دون تغيير domain.

لن تُستخدم صلاحية التخزين العامة. وستُراجع `POST_NOTIFICATIONS` وforeground-service permissions وmanifest merger على أجهزة API 34+، مع التأكد من أن عدم منح الإشعارات لا يفسد المعالجة نفسها.

## 6. واجهة captions ومحررها

سيعتمد مشغل الفيديو على Media3 ExoPlayer داخل Compose، مع overlay زمني من `CaptionTimeline`. كل كلمة ستكون ذات `startMs`, `endMs`, `text`, و`prosodyScore`. أثناء التشغيل، تُحدد الكلمة النشطة بالـ playback position، وتُطبق karaoke highlight مع انتقالات قصيرة لا تؤثر على دقة القراءة.

سيُحول `prosodyScore` إلى مجال مستقر عبر percentile clipping وعمليات easing؛ الكلمات الأعلى طاقة تزيد قليلًا في الحجم/السطوع ضمن حدود إمكانية القراءة، لا بصورة تقفز أو تسبب layout shift. وسيتيح Caption Editor تعديل النص، تقسيم/دمج الكلمات، ضبط timestamps، وتغيير style، ثم حفظ نسخة تحريرية منفصلة عن نتيجة ASR الخام حتى يمكن إعادة تشغيل ASR دون فقد تعديلات المستخدم.

## 7. خطة التنفيذ المرحلية

| المرحلة | الناتج القابل للمراجعة | معيار الخروج |
|---|---|---|
| 0. التنظيف | فرع النقل وحذف artifacts المتعارضة | clean status وcommit مستقل |
| 1. عقود محلية | domain models، Room schema، checkpoint repository | اختبارات state transitions وmigration |
| 2. ingestion | SAF/Photo Picker إلى app-private storage | ملف كبير يعاد فتحه بعد إغلاق Activity |
| 3. native audio | PCM extraction، Whisper JNI، timestamps | golden transcript مع قياس RAM/زمن |
| 4. sound scoring | TFLite/YAMNet وvocal energy | features deterministic وقابلة لإعادة الحساب |
| 5. face tracking | ML Kit bundled، smoothing، crop path | فيديوهات reference بلا jitter خطير |
| 6. render | Media3 Transformer ثم native fallback عند الحاجة | MP4 قابل للتشغيل وmanifest صحيح |
| 7. worker | WorkManager foreground، cancel، retry، resume | إغلاق التطبيق/قفل الشاشة لا يفقد المهمة |
| 8. UI | player، karaoke، prosodic styling، editor | اختبارات Compose ولقطات مرجعية |
| 9. hardening | R8، ABI splits، licensing، privacy، release | APK أقل من 100MB وoffline smoke pass |

يجب أن يكون كل commit قابلًا للفحص، وبصيغة conventional commits، مثل `feat(android): add local job state machine` و`feat(android): integrate whisper.cpp JNI` و`fix(android): resume render from checkpoint`.

## 8. الاختبارات ومعايير القبول

ستُبنى مصفوفة اختبار من خمس طبقات. أولًا، اختبارات وحدات لعقود Room، checkpoint transitions، timestamp merge، prosody normalization، وcrop smoothing. ثانيًا، اختبارات native golden على ملفات صوت قصيرة مع قياس الدقة والذاكرة. ثالثًا، اختبارات integration لمسار SAF إلى export مع ملفات ذات URI من Downloads وGoogle Drive وUSB إن توفرت. رابعًا، اختبارات lifecycle تشمل process death، reboot، screen lock، cancel، retry، وlow battery. خامسًا، اختبارات release تشمل R8، `bundletool`/APK inspection، native ABIs، manifest، وPlay pre-launch checks.

| بوابة قبول | الشرط |
|---|---|
| Offline | بعد استيراد الملف، ينجح المسار الأساسي دون شبكة أو Gateway |
| Resumable | بعد قتل العملية، تُستعاد المهمة من آخر مرحلة مكتملة ولا يُعاد نسخ المصدر بلا داعٍ |
| Correctness | timestamps وcaptions وduration وorientation تطابق golden fixtures ضمن tolerance موثق |
| Battery | لا يبدأ العمل في الخلفية مع بطارية منخفضة إلا بقرار مستخدم صريح، ويعرض إشعارًا مستمرًا |
| Size | APK/AAB وmodel/native assets تحت ميزانية Play مع target أقل من 100MB كما طلب المشروع |
| Privacy | لا تُرسل الوسائط أو transcripts إلى الشبكة في المسار الأساسي، ولا توجد secrets داخل APK |
| Release | `isMinifyEnabled = true`، keep rules مبررة، mapping محفوظ خارج المستودع، وsigned build ناجح |

## 9. المخاطر وقرارات الحسم

**خطر حجم النماذج.** نموذج ASR وTFLite وnative ABIs قد تدفع الحزمة فوق الميزانية. سيُعالج ذلك بتكميم Whisper، ABI splits، استبعاد architectures غير المطلوبة، وقياس bundle/APK في CI. لا يُقبل تنزيل النموذج وقت التشغيل كحل افتراضي إذا كان سيكسر شرط التشغيل الأول دون اتصال.

**خطر timestamps.** word-level timestamps في Whisper.cpp موصوفة بأنها تجريبية [7]. لن تُعرض karaoke captions بدقة كلمة على أنها جاهزة قبل اختبارها على الكلام السريع، التداخل، واللغات المستهدفة؛ وسيكون fallback segment-level واضحًا في الـ UI.

**خطر FFmpegKit.** FFmpegKit القديم متقاعد [5]. لهذا السبب لا ينبغي نسخ dependency قديمة من مستندات سابقة. سيُثبت قرار Media3 أو FFmpegKitNext بعد spike صغير يقارن trimming، subtitles، codecs، حجم native libraries، ورخص FFmpeg/filters.

**خطر سياسة Android 14/16.** foreground service types وحصص JobScheduler قد تغيّر سلوك المهام الطويلة [1]. سيُختبر ذلك على API 34 و35 و36، ويُحفظ تصميم worker خلف interface تسمح بمسار foreground مباشر إذا أثبت الاختبار الحاجة.

**خطر اختلاف مخرجات Python.** لن تتم إزالة Python pipeline حتى تتوافر fixtures ثابتة للمقارنة. ستُقارن النتائج على مستوى النص، الزمن، ميزات الصوت، face path، وترتيب المقاطع مع tolerance؛ وليس عبر مساواة byte-for-byte للفيديو المعاد ترميزه.

## 10. الخطوة التنفيذية التالية

الخطوة التالية بعد هذه الخطة هي إضافة نماذج Room المحلية و`LocalProcessingEngine` خلف feature flag، مع إبقاء Gateway adapter موجودًا لتشغيل اختبارات parity. بعد تثبيت state machine وingestion، يُدمج Whisper.cpp في spike مع نموذج صغير ويُقاس على جهاز/محاكي مستقر. لا ينبغي بدء إعادة بناء الواجهة أو إضافة dependencies native كبيرة قبل أن تنجح هذه البوابات الثلاث: **إدخال ملف محلي، ASR محلي قابل للاستئناف، وexport محلي بسيط**.

## المراجع

[1]: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running "Android Developers: Support for long-running workers"
[2]: https://developer.android.com/training/data-storage/room "Android Developers: Save data in a local database using Room"
[3]: https://developer.android.com/guide/topics/providers/document-provider "Android Developers: Open files using the Storage Access Framework"
[4]: https://developers.google.com/ml-kit/vision/face-detection/android "Google Developers: Detect faces with ML Kit on Android"
[5]: https://github.com/arthenica/ffmpeg-kit "arthenica/ffmpeg-kit: archived and retired; FFmpegKitNext continuation"
[6]: https://developer.android.com/media/media3/transformer "Android Developers: Media3 Transformer"
[7]: https://github.com/ggml-org/whisper.cpp "ggml-org/whisper.cpp"
[8]: https://www.tensorflow.org/hub/tutorials/yamnet "TensorFlow Hub: YAMNet tutorial"
[9]: https://developers.google.com/codelabs/tflite-audio-classification-basic-android "Google Developers: TFLite audio classification on Android"

