# Android UI — ISM Clip Workflow

**الحالة:** مكتمل على فرع `agent/android-ui`

**المنتج:** ISM Native Android

**المنصة:** Kotlin + Jetpack Compose + Room + WorkManager + Media3

## النطاق

هذه الواجهة موجهة للهاتف وتغطي المسار الشخصي الكامل لتحويل فيديو طويل إلى clips: اختيار الفيديو، تثبيت الملف، جدولة المعالجة، متابعة الحالة، مراجعة النتائج، تحرير المقطع، تصييره في الخلفية، ثم تصديره إلى MediaStore أو ورقة المشاركة. لا تنفذ الواجهة عمليات تحليل أو تصيير ثقيلة على خيط UI.

عند عدم وجود Gateway صالح، يستمر مسار الملفات المحلية عبر `ProcessingEngine` و`VideoProcessingWorker` و`ProductionVideoPipeline`. وعند ضبط Gateway، تبقى الواجهة عميلة لعقد المستودع الحالي؛ لا توجد طبقة API ثانية ولا تُنسخ أسرار مزودي الذكاء الاصطناعي إلى الواجهة.

## الشاشات ومسؤولياتها

| الشاشة | مسؤولية الواجهة | عقد المصدر المستخدم |
|---|---|---|
| Home | CTA للاستيراد، المشاريع الأخيرة، ومهمة معالجة مستمرة | `OpusRepository.allProjects`, `processingJobs` |
| Import | اختيار فيديو الهاتف، عرض حالة المصدر، ثم الجدولة | `DeviceGalleryVideoPicker`, `enqueueVideoProcessing()` |
| Processing | عرض progress وstage الحقيقيين، الإلغاء وإعادة المحاولة | `observeProcessingJob()`, `cancelVideoProcessing()`, `retryVideoProcessing()` |
| Results | قائمة clips للمشروع مع score والمدة والنص | `allClips`, `getClipsForProject()` |
| Clip Review | preview، score، confidence، duration، transcript، وتوصية المنصة | `Project`, `Clip`, Media3 preview |
| Editor | start/end، crop/framing، caption preset، position، style | `ClipEditState`, `ClipEditEngine`, `enqueueClipRender()` |
| Settings | إعداد Gateway واختبار الاتصال وشرح الخصوصية | `GatewayConfig`, `saveGatewayConfig()`, `testGatewayConnection()` |

ينتقل التطبيق تلقائيًا من `Processing` إلى `Results` فقط عندما تحفظ المهمة حالة نجاح ويكون `outputProjectId` صالحًا. لذلك لا تعرض الواجهة تقدّمًا وهميًا ولا تفترض اكتمالًا من انتهاء animation محلية.

## عقد التحرير والتصيير

يُجمع كل تغيير في المحرر داخل `ClipEditState` immutable. يحتوي العقد على حدود المقطع بالثواني، نسبة الأبعاد، موضع القص الأفقي، تفعيل الكابشن، preset الكابشن، موضعه، وأسلوبه.

> الواجهة ترسل `ClipEditState` إلى حد `ClipEditEngine`، ثم يمرره `OpusRepository` إلى `ClipRenderWorker`. التصيير نفسه يستدعي `Media3VideoProcessor` خارج Compose ويدعم clipping ونسبة الأبعاد والـ crop والكابشن الموجودة في المحرك.

| الحقل | التحقق | أثره في التصيير |
|---|---|---|
| `startTimeSec` / `endTimeSec` | البداية غير سالبة والنهاية بعد البداية | `MediaItem.ClippingConfiguration` |
| `aspectRatio` | `9:16`, `1:1`, `4:5`, أو `16:9` من واجهة المحرر | `ExportAspectRatio` و`Presentation` |
| `cropCenterX` | النطاق `-1..1` | تأثير `Crop` عند الحاجة |
| `captionsEnabled` | قيمة Boolean | تمرير cues أو قائمة فارغة |
| `captionPreset`, `captionPosition`, `captionStyle` | قيم UI موصوفة وممررة للعقد | محفوظة مع edit state للتوسعة؛ اختيار موضع/أسلوب الرسم التفصيلي يبقى ضمن قدرات renderer الحالية |

لا تُحاول الواجهة تنفيذ render متزامن. `enqueueClipRender()` يضع القيم في `WorkRequest`، ويستعيد `EditorWorkflowScreen` حالة `WorkInfo` عند إعادة التركيب أو الدوران. عند النجاح يحدّث المستودع `Clip` بحدود التصيير الجديدة ونسبة الأبعاد ومسار الملف الناتج.

## حالات التجربة

| الحالة | السلوك المتوقع |
|---|---|
| Empty | Home وResults يعرضان رسالة واضحة وCTA للاستيراد بدل مساحة فارغة |
| Loading | استعادة مهمة غير متاحة لحظيًا تعرض spinner ولا تنشئ نتائج مزيفة |
| Processing | progress وstage من Room/Worker، مع زر إلغاء، وإعادة محاولة عند الفشل |
| Error | رسالة آمنة للمستخدم مع إبقاء زر retry عندما تسمح حالة المهمة |
| Small screen | كل المحتوى داخل `LazyColumn`، والخيارات الأفقية قابلة للتمرير |
| Large phone | البطاقات تتمدد بعرض الهاتف دون تخطيط desktop ثابت |
| Rotation | `MainActivity` يعلن `configChanges` للدوران وحجم الشاشة حتى لا يفقد اختيار الفيديو وحالة workflow أثناء الدوران |
| Export | قبل التصيير يظهر تنبيه للمستخدم؛ بعد وجود `exportPath` تُحفظ النسخة إلى MediaStore وتُفتح ورقة المشاركة |

## الاختبار والتنفيذ

تمت إضافة `ClipEditStateTest` للتحقق من الحفاظ على قيم التحرير ورفض نطاق زمني غير صالح وموضع crop خارج الحدود. تم تشغيل ترجمة Kotlin بنجاح عبر `:app:compileDebugKotlin`، كما نجح الاختبار المستهدف عبر `:app:testDebugUnitTest --tests com.example.ClipEditStateTest`.

مجموعة `testDebugUnitTest` الكاملة بدأت تنفيذ اختبارات Robolectric الموجودة في المشروع لكنها لم تُكمل ضمن جلسة التحقق؛ لذلك تُعد نتيجة الاختبار المستهدف هي الإثبات الآلي الجديد، بينما تبقى مجموعة Robolectric القديمة بحاجة إلى تشغيل مستقل في CI أو بيئة أطول مهلة. لم يُنفذ اختبار instrumentation بصري على جهاز فعلي داخل بيئة العمل، ويجب التحقق اليدوي من preview وMediaStore وrotation على جهاز Android أو emulator قبل الإصدار.

## الملفات الأساسية

| الملف | الغرض |
|---|---|
| `ui/screens/ClipWorkflowScreen.kt` | غلاف التنقل والشاشات السبع ومكوّنات الحالات |
| `domain/model/ClipEditState.kt` | عقد state immutable للتحرير |
| `domain/editor/ClipEditEngine.kt` | حد المحرك بين UI والتنفيذ |
| `data/worker/ClipRenderWorker.kt` | التصيير الثقيل في الخلفية |
| `data/repository/OpusRepository.kt` | adapter للمستودع الحالي وجدولة التصيير وتحديث Clip |
| `MainActivity.kt` | تهيئة Activity والثيم والمستودع |
| `ClipEditStateTest.kt` | اختبارات حدود عقد التحرير |

## قيود معروفة

حقل `confidence` غير موجود في عقد `Clip` أو `ViralScoreMetricEntity` الحالية، لذلك تعرض شاشة Clip Review القيمة `غير متاحة` مع توضيح يمنع اشتقاق نسبة غير موثقة من score. كما أن Media3 renderer الحالي يستقبل caption cues ولا يملك بعد معاملات رسم مستقلة لموضع ونمط الكابشن؛ يمرر Editor هذه القيم داخل edit state دون اختلاق دعم engine غير موجود.
