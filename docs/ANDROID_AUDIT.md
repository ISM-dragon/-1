# ISM Android Audit

**تاريخ التدقيق:** 26 أغسطس 2026  
**المستودع:** `ISM-dragon/-1`  
**نقطة التدقيق الأساسية:** `e21f891` (`feat(android): refresh personal mobile studio and engine routing`)
**حالة ما بعد التدقيق:** أُضيف patch محدود في هذه الجلسة لإصلاح remote URL routing ونتائج metadata؛ لم تُنفذ إعادة بناء كبيرة.

## الخلاصة التنفيذية

المستودع يحتوي على **مسارين مختلفين لـ Android** يجب عدم معاملتهما كتطبيق واحد. المسار الأول هو Tauri-generated Android shell تحت `app/src-tauri/gen/android`: يستخدم React كواجهة، ويُنشئ APK، لكنه لا يشغّل Python أو `uv` أو FFmpeg المحلي على Android؛ عند التشغيل على Android يرفض Tauri المسار المحلي صراحة، بينما ترسل React المهمة إلى Gateway عبر HTTP. المسار الثاني هو تطبيق Kotlin/Jetpack Compose مستقل تحت `android/`: يملك `WorkManager` وRoom وMedia3 وطبقة معالجة محلية مبسطة. كان يقبل `content://` و`file://` فقط ويرفض YouTube وHTTPS قبل Gateway؛ وقد عولج هذا الحاجز الآن في patch محدود يسمح بتمرير HTTP/HTTPS إلى Gateway مع إبقاء المعالجة المحلية للملفات فقط. يبقى إثبات E2E وبناء APK release متطلبات منفصلة. [1] [2] [3] [13] [14]

المسار الأقصر للحصول على **APK Android يعمل end-to-end لمعالجة YouTube** هو اعتماد Tauri Android shell كعميل Gateway، وليس محاولة تشغيل pipeline Python داخل الهاتف. معظم الـ backend المطلوب موجود بالفعل: Gateway بـ FastAPI، SQLite للحالة، queue durable، رفع مصادر، تشغيل pipeline، progress، cancel/retry/resume، checkpoints، وخدمة MP4. المطلوب قبل وصفه بأنه production-ready هو بناء APK في بيئة Android صحيحة، نشر Gateway مع Python dependencies والنماذج وFFmpeg وGemini، ثم إضافة اختبار E2E حقيقي. أما النشر المباشر إلى Instagram فما زال development/mock-only في Gateway، في حين أن تكامل Instagram المحلي في Python يركز على OAuth/sync/link/analytics للـ Reels المنشورة يدويًا وليس على auto-publish. [4] [12] [18]

> **الحكم النهائي:** المعالجة الكاملة قابلة للنقل إلى backend بتعديل محدود نسبيًا، بينما parity محلي كامل على Android يتطلب إعادة تصميم كبيرة وليس مجرد packaging.

## 1. صورة المعمارية الحالية

```text
Tauri Desktop / React
  ├─ debug: uv --directory pipeline run publikclip
  ├─ packaged: bundled uv + pipeline source
  └─ Android: لا يشغّل runtime المحلي؛ React يستدعي Gateway HTTP

Tauri Android-generated APK
  └─ React UI + Tauri shell + Gateway remote processing

Native Android / Kotlin Compose APK
  ├─ WorkManager + Room
  ├─ remote: upload local URI → Gateway → poll → download MP4
  └─ local: Kotlin analysis + Gemini/STT + Media3 export

Gateway / FastAPI
  ├─ SQLite job state and transitions
  ├─ worker queue and restart recovery
  ├─ source upload/download
  ├─ Python pipeline subprocess
  ├─ server-side Gemini/provider routing
  └─ HTTP MP4 artifact serving

Python pipeline
  ├─ ingest / yt-dlp / FFmpeg probe
  ├─ WhisperX ASR + word alignment
  ├─ diarization / events / candidates
  ├─ LLM scoring and explainable rubric
  ├─ camera trajectories
  └─ FFmpeg render + captions + artifact validation
```

المستودع نفسه يصف Gateway بأنه control plane، ويضع الحسابات والأسرار والحالة authoritative في الخادم، مع إبقاء المعالجة الثقيلة في pipeline. هذا هو الاتجاه الصحيح للـ Android، لكن وجود Native Android pipeline ثانٍ يخلق اختلافًا فعليًا في النتائج والـ checkpoints والتصدير. [22]

## 2. ما الذي يعمل حاليًا؟

| المنطقة | حالة التنفيذ الحالية | مستوى الثقة | الدليل الرئيسي |
|---|---|---:|---|
| Python pipeline | مراحل CLI كاملة من `run` و`resume` حتى render، مع checkpoints واختبارات pipeline ناجحة | مرتفع للكود والاختبارات، غير مثبت E2E على فيديو حقيقي في هذه الجلسة | [5] [8] [9] [10] |
| Gateway | FastAPI مع SQLite، workers، recovery، source upload/download، processing status، cancellation، retry، resume وخدمة artifacts | مرتفع | [4] [20] |
| React | typecheck وVite production build نجحا؛ الواجهة تميز Android وترسل remote jobs عبر HTTP | مرتفع | [3] [18] |
| Tauri Desktop | مسار debug يستدعي `uv` وpipeline؛ المسار packaged يستدعي `uv` مضمّنًا وpipeline staged | مرتفع للكود، packaging macOS/Windows لم يُنفذ محليًا | [2] [19] |
| Tauri Android | shell وGradle project مولدان وموجودان؛ المعالجة تكون remote فقط | مرتفع للكود، APK لم يُبنَ في هذه البيئة | [2] [21] |
| Native Android | Worker وRoom وWorkManager ومسار remote/local وMedia3 export موجودة؛ remote URL routing صُحح في patch الحالي | مرتفع للكود، build محلي متوقف بسبب غياب Android SDK | [13] [15] [16] |
| Checkpoints Python/Gateway | JSON atomic لكل stage، والتحقق من وجود artifacts، مع SQLite bookkeeping وrestart recovery في Gateway | مرتفع | [4] [9] |
| Rendering Python | FFmpeg يطبق crop trajectory، scale إلى 1080×1920، ASS subtitles، loudnorm، H.264/AAC، ويفحص الناتج | مرتفع | [6] [10] |
| Scoring Python | rubric explainable، T1 text، T2 vision عند Gemini، music brief، provenance وlocal-estimate عند Ollama | مرتفع للكود | [11] |
| Instagram local loop | OAuth ضد تطبيق Meta الخاص بالمستخدم، sync/media insights/link/calibration؛ النشر المحلي التلقائي مؤجل | مرتفع للكود والتوثيق | [12] |

اختبارات هذه الجلسة أعطت **35 اختبار Gateway ناجحًا** و**91 اختبار pipeline ناجحًا**. كما نجح `npm run typecheck` و`npm run build` في `app/`. هذه النتائج تثبت سلامة أجزاء مهمة من العقود والوحدات، لكنها لا تثبت تشغيل نموذج WhisperX أو Gemini أو معالجة فيديو كامل من APK إلى Gateway إلى MP4.

## 3. ما الذي يعمل فقط في development environment؟

أهم العناصر development-only هي تكاملات النشر الاجتماعي في Gateway. عند `PROVIDER_MODE=mock` يُنشئ النظام حسابات mock، وينشر post وهميًا، ويولد permalink محليًا. خارج mock mode ترجع adapters الحية `501` لأن live provider adapters غير مهيأة. OAuth Gateway نفسه يعيد mock URL في هذا الوضع، بينما المسار الحي يرجع `501` حتى عند وجود بعض بيانات الاعتماد. لذلك لا يجوز اعتبار Instagram auto-publish أو Facebook/TikTok/YouTube/X publishing جاهزًا للإنتاج. [4]

كذلك، Android CI يتحقق من `testDebugUnitTest` و`lint` و`assembleDebug` فقط، ويُنتج debug APK. لا يوجد في workflow بناء release موقّع، ولا instrumentation/device test، ولا Play-ready artifact. Native Android يملك signing اختياريًا فقط عند توفر keystore ومتغيرات البيئة؛ وإلا فالـ release غير موقّع. [17] [18]

في desktop، Windows لديه workflow أقوى: `uv sync`، تنزيل FFmpeg caption-capable، suite pipeline، NSIS، تثبيت صامت، ثم launch لمدة 15 ثانية. لا يوجد workflow macOS مماثل في المستودع؛ وثيقة README تصف macOS build يدويًا، لكن هذا ليس تحقق CI مستمرًا. [1] [18]

يوجد أيضًا فرق بين **health/configured** و**runtime-ready**. `pipeline_checks()` يتحقق من وجود manifest ومجلد package، بينما بعض imports الثقيلة مؤجلة إلى worker. لذلك يمكن أن يظهر Gateway جاهزًا جزئيًا ثم يفشل عند استيراد dependency أو model فعليًا. يجب اعتبار readiness الحالية contract-level readiness لا proof of full runtime readiness. [4] [20]

## 4. ما الذي يعتمد على Python وuv؟

الـ Python pipeline يحدد Python `>=3.12,<3.13` ويستخدم `publikclip` كـ CLI entry point. dependencies تشمل `whisperx==3.8.6` و`onnxruntime` و`opencv-python-headless` و`scipy` و`librosa` و`scenedetect` و`SpeechBrain` ومكونات علمية أخرى. هذا ليس runtime مناسبًا لعملية Android العادية. [5]

في Tauri desktop، debug يستدعي `uv --directory ../../pipeline run publikclip`، بينما packaged build يضم `uv` وsource الخاص بالـ pipeline داخل resources؛ عند التشغيل الأول يستطيع `uv` إنشاء البيئة وتنزيل Python/dependencies. سكربت `prepare-resources.mjs` ينسخ binary `uv` من جهاز البناء، لذلك يجب أن يكون binary متوافقًا مع target platform والمعمارية. [2] [19]

في Gateway، `uv` مفضل عندما يكون موجودًا، لكن يوجد fallback إلى `python -m publikclip_pipeline.cli`. Dockerfile يثبت pipeline بـ `pip install ./pipeline` ويثبت FFmpeg ومكتبات النظام. لهذا يمكن نقل المعالجة إلى backend بدون نسخ runtime إلى الهاتف، بشرط تثبيت dependencies فعليًا في صورة أو host الخادم. [20] [7]

## 5. ما الذي يعتمد على FFmpeg؟

FFmpeg أساسي في Python لـ probe، استخراج/تطبيع الصوت، التعامل مع الفيديو، الرندر، loudness normalization، burn-in للـ ASS captions، والتحقق من وجود video/audio streams. Resolver يبحث بالترتيب في `PUBLIKCLIP_FFMPEG`، binary منزّل، binary bundled، Homebrew `ffmpeg-full`، ثم PATH، ولا يعتبر captions مدعومة إلا عند وجود filter باسم `subtitles`. [6] [10]

التنزيل التلقائي لـ caption-capable FFmpeg مبرمج لـ macOS وWindows فقط. Linux لا يحصل على provisioning تلقائي، بل يعتمد على FFmpeg النظامي أو إعداد خارجي. Android لا يستخدم هذا resolver أصلًا في Native path؛ يستخدم Media3 Transformer. عند استعمال Tauri Android، FFmpeg يكون على Gateway لا داخل APK. [6] [16]

Native Android Media3 يستطيع قص الفيديو وإخراج H.264/AAC وتطبيق crop وtext overlay، لكنه ليس equivalent لـ Python renderer: لا يوجد libass، ولا sendcmd trajectory كامل، ولا نفس loudnorm، ولا نفس ASS styles. captions فيه OverlayEffect بسيط، ويكتب WebVTT sidecar بعد التصدير. [15] [16]

## 6. ما الذي يعتمد على external models؟

| الوظيفة | التنفيذ Python | نموذج/مصدر خارجي | وضع Android الحالي |
|---|---|---|---|
| ASR والمحاذاة | WhisperX `large-v3-turbo` + Silero VAD + alignment | Hugging Face/WhisperX cache، نحو 1.6 GB مذكورة في progress | لا يوجد WhisperX محلي؛ إما نص مقدم، Gemini video، أو STT OpenAI/Groq اختياري |
| laughter | checkpoint `best.pth.tar` | GitHub | غير مضمّن في Android المحلي |
| audio tagging/arousal | PANNs CNN14 | Zenodo | Android يستخدم إشارات PCM خفيفة فقط |
| diarization/speaker embedding | SpeechBrain/CAMPPlus | Hugging Face checkpoint | لا يوجد diarization مكافئ محلي |
| face detection | UltraFace ONNX | GitHub/clip-forge resource | ML Kit face boxes فقط |
| active speaker | LR-ASD ONNX frontend/backend | GitHub/clip-forge resource | provider الحالي يعلن unsupported |
| text/vision scoring | Gemini أو Ollama أو provider router | Gemini API أو daemon محلي | Native Android يعتمد على Gemini/مزودات Android؛ Tauri Android يعتمد Gemini على Gateway |

سجل النماذج صريح في أن Whisper weights تدار عبر Hugging Face cache، وباقي النماذج تُنزّل عبر registry. كما أن ASR يحمّل النموذج والمحاذاة وقت التشغيل ويفرض وجود word timestamps. [7] [8]

## 7. jobs وcheckpoints والاسترداد

Python يستخدم SQLite bookkeeping وملفات `<job_dir>/<stage>.json` مغلفة بـ `schema_version`. الكتابة atomic عبر temporary file ثم rename، ولا يُستخدم checkpoint إلا إذا كان schema صحيحًا وartifacts المطلوبة ما زالت موجودة. `resume` يعيد فقط المراحل التي لا تملك checkpoint صالحًا. [9]

Gateway يضيف durable processing job state، transition history، correlation ID، worker heartbeat، cancellation persisted، وإعادة جدولة للوظائف غير النهائية عند startup. بعد نجاح pipeline يقرأ render checkpoint ويتحقق من artifact ثم يحوله إلى URL تحت `/v1/processing/jobs/{id}/media/{filename}`. هذا هو أقوى مسار جاهز حاليًا. [4]

Native Android يحفظ checkpoints خفيفة في Room. لكنه لا يملك استئنافًا محليًا حقيقيًا على مستوى artifact/stage؛ `resumeVideoProcessing()` يشترط وجود `remoteGatewayJobId`. لذلك resume المحلي يعيد Worker ولا يستكمل Python-like stage graph من ملفات checkpoints. [13] [15]

## 8. captions وscoring وrendering

في Python، مسار render يقرأ words المحاذاة، يطبق prosodic emphasis، يبني ASS، يضم الأحداث، ثم ينفذ FFmpeg مع trajectory من camera stage، scale رأسي 1080×1920، subtitles، loudnorm، H.264/AAC، و`faststart`. الناتج لا يُعلن إلا بعد probe للتحقق من وجود stream صوت وفيديو ومدة معقولة. [10]

في scoring، كل candidate يمر عبر text rubric، ثم finalists عبر visual pass عند Gemini. النتيجة تحمل subscores، adjustments، signals fired/missing، confidence، platform scores، وmusic brief. Ollama يتخطى T2 vision ويسجل النتيجة كـ `local-estimate`؛ Gemini يحتاج key واتصال خارجي. [11]

Native Android لا يعيد تنفيذ هذا graph. عند توفر word-timed transcript يستخدم detector وdeterministic score مبسطين؛ وإلا يؤجل اختيار المقاطع إلى Gemini. `processNewVideo()` يحفظ clips وviral metrics، ثم يصدّر كل clip عبر Media3 إذا كان المصدر URI محليًا. فشل بعض التصديرات قد ينتج `PARTIAL_FAILURE` بدل MP4 كامل لكل المقاطع. [15]

## 9. Instagram integration

يوجد تكامل Python محلي حقيقي نسبيًا مع Instagram Login ضد تطبيق Meta الخاص بالمستخدم: localhost callback، short-to-long-lived token exchange، refresh، media listing، thumbnails، insights، وربط Reel بمقطع ثم calibration من outcomes. التصميم الحالي يتتبع Reels المنشورة يدويًا لأن Meta يتطلب public media URL للنشر، بينما التطبيق المحلي يملك ملفات خاصة؛ لذلك auto-publish ليس مسارًا جاهزًا. [12]

في المقابل، Gateway social surface عام لكنه mock-first. `/v1/social/*` يعرض capabilities، accounts، schedule، publish، OAuth، لكنه يرجع live-adapter-not-implemented خارج mock mode. واجهة React قادرة على استدعاء Gateway، لكنها لا تحول ذلك إلى تكامل Instagram production. كما أن Tauri React يستدعي `ig_tool` المحلي، وهذا المسار desktop/Python وليس Native Android. [2] [3] [4]

## 10. ما الذي يستحيل تشغيله مباشرة على Android في التصميم الحالي؟

| capability | الحكم |
|---|---|
| تشغيل Python/uv bundled pipeline داخل Tauri Android | غير مدعوم صراحة؛ `pipeline_invocation()` يعيد خطأ على `target_os=android` لأن binary desktop/host ليس Android ARM runtime. [2] |
| تشغيل WhisperX/SpeechBrain/ONNX desktop graph من APK بدون native port | غير متاح مباشرة؛ لا توجد حزمة Android-native مكافئة لهذا graph ولا إدارة model footprint داخل التطبيق. [5] [7] [8] |
| استخدام Python FFmpeg resolver والتنزيل التلقائي داخل Android | غير متاح في المسار الحالي؛ provisioning محصور macOS/Windows. [6] |
| active-speaker smart camera محلي في Native Android | غير متاح؛ provider المضمّن يعيد `supported=false` ويستخدم static aspect-ratio fallback. [14] |
| معالجة YouTube URL عبر Native Android Worker الحالي | غير ممكن حاليًا؛ Worker وProcessingEngine يرفضان أي scheme غير `content` أو `file` قبل remote routing. [13] [14] |
| Instagram live publish من Gateway الحالي | غير ممكن خارج mock mode؛ live adapters ترجع `501`. [4] |
| APK production موقّع من CI الحالي | غير متحقق؛ CI يبني debug فقط، وrelease signing اختياري. [17] [18] |

هذا لا يعني أن Android لا يستطيع تقنيًا تنفيذ هذه الوظائف، بل يعني أنها **ليست جزءًا من التنفيذ الحالي**. نقلها إلى native Android يتطلب نماذج Android/ONNX مهيأة، إدارة ذاكرة وطاقة، downloader، renderer، وقاعدة توافق جديدة.

## 11. ما الذي يمكن نقله إلى backend بدون تعديل كبير؟

يمكن نقل أو إبقاء العناصر التالية في Gateway backend تقريبًا كما هي: Python pipeline بأكمله، `uv`/Python environment، FFmpeg وffprobe، model caches، Gemini/provider secrets، SQLite job state، worker queue، source download/upload، checkpoints، artifact validation، progress mapping، cancellation، retry/resume، وmedia serving. توجد بالفعل حدود برمجية لهذه العملية في `gateway/main.py` و`processing_service.py` وDockerfile. [4] [20] [7]

يمكن كذلك إبقاء React/Tauri كواجهة عميلة مع تعديل محدود: إرسال source URL إلى `/v1/processing/jobs`، polling للحالة، عرض stage progress، واستلام artifact URLs. هذا المسار مطبق أصلًا في `App.tsx` و`api.ts` لمسار Android Tauri. [3]

أما native Android فالأجزاء القابلة لإعادة الاستخدام دون تغيير كبير هي WorkManager، Room، notification، retry policy، وdownload/import إلى project library. ما يحتاج تعديلًا صغيرًا هو جعل remote source يقبل URL مباشرًا، وإضافة upload-only branch للمصادر المحلية. لا ينبغي نقل Python code إلى `android/app/src`.

## 12. ما الذي يجب إعادة تصميمه؟

أولًا، يجب حسم **عميل Android واحد**. حاليًا Tauri-generated APK وNative Compose APK يقدمان هويتين ومسارين مختلفين (`com.publikhq.publikclip` مقابل `com.aistudio.opuspro.apk`) ويختلفان في source contract وrendering وcheckpoint semantics. استمرار الاثنين دون contract موحد سيؤدي إلى سلوك غير متوقع ودعم مزدوج.

ثانيًا، يجب جعل Gateway API contract canonical للـ Android: source type واضح (`remote_url` أو `upload`)، manifest موحد يضم `start`, `end`, `title`, `transcript`, `score`, و`media_url`، وحالة job واحدة مع `state`, `stage`, `fraction`, `recoverable`, `retry_count`, و`checkpoint_available`. حاليًا Gateway render outputs لا تضمن كل metadata التي يتوقعها Native client؛ لذلك قد يضع Native client start/end افتراضيين إلى صفر عند غيابهما، حتى لو كان ملف MP4 صحيحًا.

ثالثًا، يجب فصل capability readiness إلى طبقتين: `dependencies_ready` و`runtime_probe_passed`. وجود pyproject لا يكفي لإعلان pipeline جاهزًا؛ يجب probe يحمّل imports الأساسية، يتأكد من model cache أو download policy، ويجرب FFmpeg caption path دون كشف الأسرار.

رابعًا، يجب إعادة تصميم social publishing كـ provider adapters حقيقية، vault server-side، OAuth state/nonce، upload hosting public، idempotency، وprovider-specific retry. لا ينبغي توسيع UI قبل إنهاء هذه الحدود.

## 13. Critical blockers مرتبة

| الأولوية | blocker | الأثر |
|---:|---|---|
| P0 | Native Android كان يرفض YouTube/HTTPS بينما وثائق Android وReact تصف remote URL flow | **أُصلح في patch الحالي**؛ يلزم E2E للتحقق من المسار الكامل |
| P0 | لا يوجد E2E proof من Android APK إلى Gateway إلى MP4 حقيقي | لا يمكن إعلان Android production-ready |
| P0 | Python/WhisperX/نماذج/FFmpeg ليست داخل Android؛ Tauri يمنع spawn المحلي | يجعل local parity مستحيلًا دون إعادة تصميم كبيرة |
| P1 | وجود تطبيقَي Android بعقود وهوية وruntime مختلفين | يضاعف سطح الاختبار ويخلق تضاربًا في المنتج المستهدف |
| P1 | Gateway live social adapters غير منفذة، والنشر الحالي mock-only | Instagram/النشر ليس production capability |
| P1 | CI يبني debug APK فقط ولا يبني release موقّعًا أو يجري device tests | لا توجد حزمة توزيع موثوقة |
| P1 | Gateway health لا يثبت وجود كل ML runtime dependencies | احتمال false-ready ثم فشل worker عند أول job |
| P2 | Native Android captions/rendering ليست equivalent لـ ASS/libass/camera pipeline | اختلاف النتائج بين Android وdesktop/backend |
| P2 | Native remote result mapping لم يكن يستفيد من start/end/title/transcript الموجودة في pipeline | **أُصلح في Gateway patch الحالي**؛ يلزم E2E للتحقق من التوافق |
| P2 | لا يوجد macOS CI مماثل لـ Windows | ادعاء macOS يعتمد على build يدوي لا تحقق مستمر |

## 14. أقل تغيير ممكن للحصول على Android APK يعمل end-to-end

### الخيار الموصى به: Tauri Android + Gateway remote

هذا هو أقل تغيير لأن React/Tauri لديه بالفعل شرط Android، تخزين Gateway URL/token، capability checks، `processingStart`، polling، واسترجاع النتائج. مسار التنفيذ الأدنى هو:

1. اعتماد `app/src-tauri/gen/android` كـ APK Android الرسمي مؤقتًا، وعدم محاولة تشغيل Python أو FFmpeg داخله.
2. تجهيز Gateway على Linux/VM/Docker مع Python 3.12، تثبيت pipeline dependencies، FFmpeg/ffprobe، writable processing/source roots، وserver-side `GEMINI_API_KEY`.
3. ضبط `PUBLIC_BASE_URL` القابل للوصول من الهاتف، `REQUIRE_GATEWAY_TOKEN=true`، وHTTPS أو LAN debug URL مسموح به.
4. بناء debug APK في بيئة تحتوي Android SDK 36 وNDK/Java المطلوبين، ثم اختبار YouTube URL حقيقي قصير، انتظار job completion، تنزيل MP4، وفتح clip في review.
5. إضافة smoke E2E آلي أو شبه آلي يغطي: health، capabilities، Gemini diagnostic، start، polling، completed result، media GET، وfailure/retry.
6. قبل التوزيع، إضافة release signing ونسخة Android identity متفق عليها؛ لا يلزم نقل pipeline إلى Kotlin لهذا الخيار.

### إذا كان الهدف Native Compose APK بدل Tauri

تم تنفيذ التعديل الأدنى لعقد Native Compose في هذه الجلسة: `ProcessingEngine` و`VideoProcessingWorker` يقبلان HTTP/HTTPS عند وجود Gateway، و`ProcessingGatewayClient` يرسل URL مباشرة بدل محاولة فتحه كـ local URI، مع إبقاء `content://`/`file://` في upload branch. كما أُصلح Gateway ليعيد start/end/title/transcript من score إلى Android. المتبقي هو بناء Android وتشغيل E2E حقيقي؛ وهذا ليس إعادة بناء للـ pipeline.

### ما لا يدخل في أقل تغيير

لا يدخل في هذا المسار تشغيل WhisperX أو diarization أو active-speaker محليًا، ولا تنفيذ Instagram live publish، ولا توحيد كامل للـ Native local pipeline. هذه مشاريع لاحقة مستقلة وليست prerequisites لـ APK remote-processing يعمل.

## 15. Architecture المقترحة

```text
Official Android client (choose one)
  ├─ imports local file or accepts public URL
  ├─ stores only short-lived Gateway session/token
  ├─ WorkManager persists gateway_job_id
  ├─ polls canonical state contract
  └─ downloads validated MP4 manifest

Gateway control plane
  ├─ auth/session + capability probes
  ├─ source boundary: URL ingest or private upload
  ├─ SQLite durable jobs/transitions/idempotency
  ├─ persistent worker + bounded retry/cancel/recovery
  ├─ Python pipeline executor
  ├─ model cache + server Gemini/provider vault
  ├─ FFmpeg/ffprobe render and artifact validation
  ├─ signed/authorized media URLs
  └─ future provider adapters for real publishing

Python processing plane
  ├─ ingest / ASR / diarization / events
  ├─ candidate and explainable scoring
  ├─ camera trajectory
  ├─ ASS caption synthesis
  └─ verified 9:16 MP4 artifacts
```

القاعدة الأساسية هي أن **Gateway هو مصدر الحقيقة للـ job والـ artifact**، وAndroid عميل lifecycle وcache فقط. أي local Android processing يجب أن يُعامل كميزة degraded/optional لا كبديل صامت للـ canonical Python pipeline.

## 16. خطة التنفيذ المرحلية

| المرحلة | النتيجة المطلوبة | معيار الخروج |
|---:|---|---|
| 0. قرار المنتج | اختيار Tauri APK أو Native Compose APK كمسار رسمي | اسم package واحد ووثيقة contract واحدة |
| 1. Backend readiness | صورة Gateway قابلة للتشغيل مع pipeline/FFmpeg/models وGemini probe | job حقيقي من curl يعيد MP4 صالحًا |
| 2. Android remote smoke | URL أو upload، start، poll، cancel، retry، resume، download | اختبار على جهاز/محاكي مع network interruptions |
| 3. Contract hardening | manifest metadata موحد، auth، artifact URL policy، runtime readiness | لا default metadata صامت ولا false-ready |
| 4. Release packaging | release APK موقّع، version/application ID، crash/log policy | artifact قابل للتثبيت خارج CI debug |
| 5. Feature parity review | مقارنة captions/camera/scoring بين Android والbackend | تفاوت موثق ومقبول، لا ادعاء parity غير مثبت |
| 6. Social production | Meta/Instagram adapter حقيقي أو إبقاء manual-link واضحًا | OAuth review، public media hosting، publish/insights E2E |

## 17. الاختبارات التي شُغّلت في هذه الجلسة

| الأمر | النتيجة |
|---|---|
| `python3 -m pytest gateway -q` | **36 passed**, مع 4 deprecation warnings من FastAPI `on_event` |
| `cd pipeline && python3 -m pytest tests -q` | **91 passed** |
| `cd app && npm run typecheck` | **نجح** |
| `cd app && npm run build` | **نجح**؛ Vite أنتج bundle production |
| `cd android && ./gradlew :app:testDebugUnitTest --no-daemon` | **لم يبدأ الاختبار**؛ Gradle نجح في تنزيل نفسه لكنه توقف لأن Android SDK غير موجود (`SDK location not found`) |
| Tauri desktop build كامل | لم يُنفذ؛ لم يتوفر سبب كافٍ لتجاوز build المعماري، كما أن التدقيق لا يهدف إلى إعادة البناء |
| Android APK build | لم يُنفذ بنجاح؛ بيئة التدقيق لا تحتوي SDK، وCI repository الحالي يثبت فقط debug path |

أثناء الفحص أزيلت مخرجات build/cache غير المتعقبة. ملفات الإصلاح المتعقبة في هذه الجلسة هي كود Android، عقد نتائج Gateway، واختبارات regression، إضافة إلى تحديث هذا التقرير و`MANUS_HANDOFF.md`.

## References

[1]: https://github.com/ISM-dragon/-1/blob/e21f891/README.md "Repository README"
[2]: https://github.com/ISM-dragon/-1/blob/e21f891/app/src-tauri/src/lib.rs "Tauri Rust runtime bridge"
[3]: https://github.com/ISM-dragon/-1/blob/e21f891/app/src/App.tsx "React application entrypoint"
[4]: https://github.com/ISM-dragon/-1/blob/e21f891/gateway/main.py "FastAPI Gateway and processing control plane"
[5]: https://github.com/ISM-dragon/-1/blob/e21f891/pipeline/pyproject.toml "Python pipeline manifest"
[6]: https://github.com/ISM-dragon/-1/blob/e21f891/pipeline/publikclip_pipeline/render/ffmpeg_bin.py "FFmpeg resolver and capability provisioning"
[7]: https://github.com/ISM-dragon/-1/blob/e21f891/pipeline/publikclip_pipeline/models/specs.py "External model registry"
[8]: https://github.com/ISM-dragon/-1/blob/e21f891/pipeline/publikclip_pipeline/asr/stage.py "WhisperX ASR and alignment stage"
[9]: https://github.com/ISM-dragon/-1/blob/e21f891/pipeline/publikclip_pipeline/jobs/queue.py "Python jobs and checkpoint store"
[10]: https://github.com/ISM-dragon/-1/blob/e21f891/pipeline/publikclip_pipeline/render/stage.py "Python render stage"
[11]: https://github.com/ISM-dragon/-1/blob/e21f891/pipeline/publikclip_pipeline/scoring/stage.py "Scoring stage"
[12]: https://github.com/ISM-dragon/-1/blob/e21f891/pipeline/publikclip_pipeline/insights/instagram.py "Instagram feedback loop"
[13]: https://github.com/ISM-dragon/-1/blob/e21f891/android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Native Android WorkManager worker"
[14]: https://github.com/ISM-dragon/-1/blob/e21f891/android/app/src/main/java/com/example/data/engine/ProcessingEngine.kt "Native Android route selection"
[15]: https://github.com/ISM-dragon/-1/blob/e21f891/android/app/src/main/java/com/example/domain/pipeline/ProductionVideoPipeline.kt "Native Android local pipeline"
[16]: https://github.com/ISM-dragon/-1/blob/e21f891/android/app/src/main/java/com/example/data/video/Media3VideoProcessor.kt "Native Android Media3 renderer"
[17]: https://github.com/ISM-dragon/-1/blob/e21f891/android/app/build.gradle.kts "Native Android build and signing configuration"
[18]: https://github.com/ISM-dragon/-1/blob/e21f891/.github/workflows/android-build.yml "Android CI workflow"
[19]: https://github.com/ISM-dragon/-1/blob/e21f891/app/scripts/prepare-resources.mjs "Tauri resource staging script"
[20]: https://github.com/ISM-dragon/-1/blob/e21f891/gateway/processing_service.py "Gateway pipeline execution helper"
[21]: https://github.com/ISM-dragon/-1/blob/e21f891/app/src-tauri/gen/android/app/build.gradle.kts "Tauri-generated Android Gradle project"
[22]: https://github.com/ISM-dragon/-1/blob/e21f891/docs/MASTER-ARCHITECTURE.md "Canonical architecture baseline"

---

**Author:** Manus AI
