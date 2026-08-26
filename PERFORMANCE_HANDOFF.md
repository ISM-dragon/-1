# PERFORMANCE HANDOFF — publikclip engine

**المالك:** Manus AI

**النطاق:** `pipeline/publikclip_pipeline` وharness القياس في `scripts/` فقط. لم يتم تعديل Android UI أو API أو gateway أو architecture العامة. بدأ العمل من فرع `main` بحالة Git نظيفة؛ لا توجد dependency على تغييرات من فروع أخرى في هذا التسليم.

## Executive summary

كان أكبر bottleneck قابل للتحسين بأمان في مسار T2 البصري هو `scoring/frames.py`: كانت كل صورة sampled تُنتج عبر عملية ffmpeg مستقلة وملف JPEG مؤقت مستقل، ما يكرر demux/decode startup ويزيد disk I/O. أصبح الاستخراج الآن عملية ffmpeg واحدة، تبث JPEGs إلى الذاكرة وتعيد نفس عدد الصور ونفس bytes الناتجة في benchmark. على ثلاث عينات، انخفض wall time بمعدل **71.5%** وانخفض CPU time بمعدل **43.5%**، مع صفر file-system output blocks في القياس الجديد لهذه المرحلة.

شملت التحسينات كذلك إزالة `audio32k.wav` المؤقت من PANNs؛ فالمسار الجديد يمرر PCM 32 kHz مباشرة إلى الذاكرة، مع تطابق byte-for-byte مع PCM المسار القديم. أضيف per-clip render cache موقّع يمنع إعادة تصيير clip سليم عند إعادة تشغيل render stage، مع بقاء `ffprobe` correctness check. كما أصبح sendcmd المؤقت يُحذف في `finally` حتى عند الفشل أو timeout، وصارت checkpoints تستخدم JSON compact encoding. لا تتوفر GPU/VRAM أو dependencies الثقيلة وmodel weights داخل بيئة القياس الحالية، لذلك لا أدّعي benchmark كاملًا للنماذج أو GPU؛ تم تحليل model lifecycle من الكود، وقياس مسارات FFmpeg والـrender وT2 وcheckpoint مباشرة.

## Environment and measurement contract

| البند | القيمة |
|---|---|
| النظام | Linux 6.1.102، x86_64 |
| CPU | 6 logical CPUs |
| RAM المضيفة عند القياس | 3.8 GiB إجمالي، 3.0 GiB available |
| GPU / VRAM | غير متاحة في sandbox؛ `nvidia-smi` غير موجود |
| FFmpeg | 6.1.1 |
| Python | 3.12.3 |
| Video fixtures | `1280x720/15s` منخفض الحركة، `1920x1080/15s` عالي الحركة، `1920x1080/30s` أطول |
| Sampling | 5 إطارات، width = 512، JPEG quality = 6، نفس `sample_times` |
| Render check | وجود video/audio، 1080x1920، duration ضمن tolerance 1.5s |

القياسات الخام قابلة لإعادة التشغيل عبر [`scripts/performance_benchmark.py`](scripts/performance_benchmark.py)، والمقارنة المحسوبة موجودة في [`benchmarks/performance_comparison.json`](benchmarks/performance_comparison.json). استخدم benchmark فيديوهات اختبار محلية متعددة حتى لا تدخل network latency أو API latency في مقارنة performance engine.[1] [2]

## Before / after results

القيم الموجبة في `delta` تعني زيادة، والقيم السالبة تعني تحسنًا أو انخفاضًا. `max RSS` هو أعلى RSS للعملية أو child process المرصودة؛ في render يهيمن ffmpeg encoder على القيمة، لذلك لا ينبغي تفسيره كـRAM Python وحدها.

| الفيديو | المسار | wall قبل → بعد | CPU قبل → بعد | max RSS قبل → بعد | disk/output قبل → بعد | correctness |
|---|---|---:|---:|---:|---:|---|
| low-motion 720p/15s | camera decode probe | 0.4723 → 0.4534s (-4.0%) | 1.9324 → 1.8564s (-3.9%) | 86.20 → 84.68 MB (-1.8%) | 200 → 0 blocks | لا ينطبق؛ probe |
| low-motion 720p/15s | T2 frame extraction | 0.6067 → 0.1669s (-72.5%) | 1.7715 → 1.0829s (-38.9%) | 88.43 → 90.03 MB (+1.8%) | 88 → 0 blocks | 5 frames؛ bytes JPEG متطابقة |
| low-motion 720p/15s | render | 2.4527 → 2.3547s (-4.0%) | 8.0704 → 7.8947s (-2.2%) | 713.98 → 714.18 MB | output 350,104 → 350,104 B | `ok=true`, 1080x1920، 6s |
| low-motion 720p/15s | checkpoint write | 0.0077 → 0.0042s (-45.5%) | 0.8123 → 0.8088s | 34.94 → 34.98 MB | 21,761 → 15,242 B (-30.0%) | JSON schema ثابت |
| high-motion 1080p/15s | T2 frame extraction | 0.8805 → 0.2785s (-68.4%) | 2.9939 → 1.6310s (-45.5%) | 111.40 → 113.55 MB (+1.9%) | 128 → 0 blocks | 5 frames؛ bytes JPEG متطابقة |
| high-motion 1080p/15s | render | 2.8665 → 2.8929s (+0.9%) | 12.1190 → 12.2204s (+0.8%) | 740.50 → 740.82 MB | output 4,005,964 → 4,005,964 B | `ok=true`, 1080x1920، 6s |
| high-motion 1080p/15s | checkpoint write | 0.0059 → 0.0046s (-22.0%) | 0.8174 → 0.8189s | 34.89 → 34.91 MB | 21,761 → 15,242 B (-30.0%) | JSON schema ثابت |
| long 1080p/30s | T2 frame extraction | 0.8796 → 0.2324s (-73.6%) | 2.3582 → 1.2681s (-46.2%) | 109.91 → 111.58 MB (+1.5%) | 88 → 0 blocks | 5 frames؛ bytes JPEG متطابقة |
| long 1080p/30s | render | 1.8710 → 1.9315s (+3.2%) | 5.8151 → 5.9187s (+1.8%) | 738.50 → 738.50 MB | output 118,763 → 118,763 B | `ok=true`, 1080x1920، 6s |
| long 1080p/30s | checkpoint write | 0.0051 → 0.0042s (-17.6%) | 0.8055 → 0.8137s | 34.93 → 34.88 MB | 21,761 → 15,242 B (-30.0%) | JSON schema ثابت |

النتيجة العملية الأهم هي أن T2 أصبح يمرر **5/5 frames** في كل عينة، وأن bytes JPEG المسجلة في baseline وafter متطابقة. كما أن ملفات render النهائية متطابقة في الحجم ونجحت sanity checks الثلاثة. تذبذب render المباشر بين التشغيلين ضمن ضوضاء benchmark الطبيعية؛ المكسب الأكبر من render cache يظهر عند إعادة تشغيل stage مع مخرجات سليمة، وليس عند أول render cold run. الأرقام التفصيلية محفوظة في [`performance_baseline_head.json`](benchmarks/performance_baseline_head.json) و[`performance_after.json`](benchmarks/performance_after.json).[3] [4]

## Findings by subsystem

### CPU and FFmpeg

`scoring/frames.py` كان يطلق ffmpeg مرة لكل timestamp، أي خمس عمليات على الحد الأدنى للعينة وقد تصل إلى 12 عملية لكل finalist. كل عملية كانت تعيد فتح المصدر، seek، decode، encode JPEG، ثم write/read/unlink. التحسين يستخدم seek واحدًا إلى أول timestamp، وfilter `select`، و`image2pipe`، ثم يفصل JPEG markers في Python. لم يتغير `sample_times` أو scale أو quality. النتيجة المقاسة هي خفض wall time بين 68.4% و73.6% وخفض CPU بين 38.9% و46.2%.

يبقى في camera path bottleneck واضح لم يتغير لتفادي أي تغيير في preprocessing accuracy: `camera/asd.py` ينفذ detection pass وcrop pass ثم `extract_mfcc` كـثلاثة decodes مستقلة لنفس clip. كذلك يكرر `AsdModel.score_track` backend inference عبر نوافذ 1–6 ثوانٍ فوق embeddings نفسها. هذا مرشح التحسين التالي، لكنه يحتاج benchmark مع UltraFace وLR-ASD weights مثبتة وparity test للـtrajectory، ولذلك لم يتم دمجه في هذا commit.

### RAM وVRAM

بيئة القياس لا تحتوي GPU أو VRAM، لذلك لا توجد نتيجة GPU utilization أو VRAM peak يمكن نسبتها إلى engine. في مسارات decode وT2 بقي max RSS قريبًا من 84–114 MB، وارتفع بعد streaming frames بنحو 1.5–1.9% فقط بسبب buffer JPEG في الذاكرة. في render بلغ max RSS نحو 714–741 MB، وهو encoder/FFmpeg dominated، ولم يتغير عمليًا. هذا يحافظ على الدقة ولا يفرض downscale إضافيًا قبل render.

في events، PANNs ما زال يستخدم نفس 32 kHz PCM، لكن المسار الجديد لا ينشئ WAV مؤقتًا، ثم يحرر `pmodel` و`y32k` مباشرة بعد inference. يظل `y16k` حيًا لأنه مطلوب لاحقًا للـDSP وSER؛ ذلك مقصود وليس memory leak. لا توجد مؤشرات على model reload لكل clip: CameraStage ينشئ UltraFace وLR-ASD مرة واحدة قبل loop المقاطع، وEventsStage ينشئ PANNs مرة واحدة، وASR يحرر ASR model قبل تحميل aligner.

### Model loading and checkpoints

`models/registry.py` يحتفظ بالأوزان تحت `PUBLIKCLIP_HOME/models`، ويعيد path مباشرة إذا كان الملف موجودًا؛ أما stage runner فيتجاوز stage كاملًا عند وجود checkpoint سليم وartifacts سليمة. لذلك model downloads لا تتكرر بعد أول نجاح، ولا تُعاد inference للمراحل المكتملة. التحسين المضاف إلى queue هو compact JSON serialization؛ بقي envelope وschema version وatomic temp-plus-replace كما هي، لذا لم يتغير عقد resume. على payload ممثل من 250 clips انخفض checkpoint من 21,761 إلى 15,242 bytes، أي 30.0% أقل.

### Disk I/O and temporary files

لم يعد T2 ينشئ `t2frames/frame_XX.jpg`؛ توقيع `tmp_dir` بقي كما هو للحفاظ على internal call contract، لكنه لا يُستخدم. في PANNs لم يعد `audio32k.wav` يُكتب إلى job directory أو يُحذف في النهاية؛ PCM يخرج من ffmpeg مباشرة إلى stdout. تحقق مستقل على عينة high-motion أعطى `pcm_cmp=0` و961,196 bytes في المسارين، ما يعني أن عينات PCM متطابقة byte-for-byte.

يُحذف `sendcmd` في renderer داخل `finally`. هذا لا يقلل cold render كثيرًا لأن الملف صغير مقارنة بالencode، لكنه يمنع orphan temp files عند exception أو timeout. وتمت إضافة manifest لكل clip مع signature تشمل renderer version وmedia stat وtrajectory وASS والإعدادات. إذا كان mp4 موجودًا، والmanifest يطابق، و`ffprobe` ينجح، يُعاد استخدام clip بدل إعادة تصييره؛ وإذا فشل أي شرط يعاد render كما كان.

## Implemented changes

| الملف | التغيير | أثر الدقة |
|---|---|---|
| `pipeline/publikclip_pipeline/scoring/frames.py` | one-process FFmpeg `select` + JPEG pipe؛ لا temp JPEGs | نفس sample list، scale، quality؛ JPEG bytes متطابقة في fixtures |
| `pipeline/publikclip_pipeline/events/stage.py` | direct 32 kHz `s16le` PCM إلى الذاكرة؛ `del y32k` بعد PANNs | PCM متطابق مع WAV path؛ نفس sample rate/channel/format |
| `pipeline/publikclip_pipeline/render/stage.py` | signed per-clip manifest مع verification قبل reuse | لا reuse إلا عند تطابق المدخلات ونجاح ffprobe |
| `pipeline/publikclip_pipeline/render/renderer.py` | `finally` cleanup لـsendcmd، cache version marker | filter/codec/CRF/bitrate/output resolution لم تتغير |
| `pipeline/publikclip_pipeline/jobs/queue.py` | compact checkpoint JSON | schema وatomic write وreader contract ثابتة |
| `scripts/performance_benchmark.py` | reproducible 3-fixture benchmark | أداة قياس فقط، لا تغير runtime API |
| `scripts/performance_compare.py` | before/after comparison JSON | أداة تحليل فقط |

## Validation performed

تم تشغيل `python3 -m compileall -q pipeline/publikclip_pipeline scripts` بنجاح، كما نجحت مجموعة اختبارات `pipeline/tests` كاملة: **91 passed**. نجح PCM byte comparison، ونجح render sanity على العينات الثلاث مع video/audio و1080x1920 و6s. لم يتم تشغيل full model inference لأن `onnxruntime` و`torch` وmodel weights غير مثبتة في sandbox؛ `librosa` متاحة الآن للاختبارات. لذلك يجب تنفيذ acceptance pass على جهاز التطوير الذي يحتوي weights قبل دمج أي camera-path optimization لاحق.

## Open bottlenecks and next handoff

الأولوية التالية هي instrumented benchmark كامل لكل stage على جهاز مزود بالنماذج، مع فصل cold model-load time عن warm inference time، وقياس RSS لكل child process وGPU/VRAM إن وُجدت. بعد ذلك يمكن اختبار دمج camera detection/crop decode أو تمرير audio16k المشترك إلى MFCC مع إثبات parity. لا أوصي بتغيير `ASD_FPS` أو backend windows أو دقة UltraFace/LR-ASD كحل سريع؛ تلك تغييرات accuracy وليست performance-safe.

لا توجد تغييرات مطلوبة من Android UI أو API، ولا توجد dependency على فروع جلسات أخرى. أي تحسين للـcamera model preprocessing ينبغي أن يأتي في handoff مستقل مع golden trajectory fixtures بدل تعديل subsystem آخر في هذا الفرع.

## References

[1]: benchmarks/performance_comparison.json "Normalized before/after benchmark comparison"

[2]: scripts/performance_benchmark.py "Reproducible performance benchmark harness"

[3]: benchmarks/performance_baseline_head.json "Baseline measured from clean HEAD worktree"

[4]: benchmarks/performance_after.json "After measured after performance changes"
