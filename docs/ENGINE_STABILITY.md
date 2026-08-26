# تقرير استقرار محرك الـPipeline

**الحالة:** production-reliability hardening مكتمل ضمن نطاق الاختبارات الممكنة محليًا.  
**التاريخ:** 2026-08-26  
**النطاق:** `ingest`، `ASR`، `diarize`، `events`، `candidates`، `score`، `camera`، `render`.

## الملخص التنفيذي

تم فحص عقود المراحل الثمانية، وآلية checkpoint/resume، وتحميل النماذج، واستخراج الصوت، وFFmpeg، وLLM scoring، وعمليات rendering. كانت suite الأساسية تمر بـ126 اختبارًا؛ وبعد إضافة اختبارات regression وإصلاحات reliability أصبحت تمر بـ143 اختبارًا. الإصلاحات تركزت على منع إعادة استخدام artifacts أو caches تالفة، تحويل timeout وفشل الأدوات الخارجية إلى أخطاء stage قابلة للتشخيص، تنظيف الملفات المؤقتة دائمًا، والتحقق من مخرجات ffprobe وtrajectory قبل اعتمادها.

لم يُشغّل end-to-end ثقيل باستخدام WhisperX أو نماذج diarization/vision الخارجية؛ بيئة الاختبار لا تحتوي WhisperX أو أوزان النماذج المطلوبة، وتنزيلها أثناء CI لن يكون اختبارًا حتميًا. لذلك يثبت هذا التقرير استقرار العقود ومسارات الفشل وrender smoke path، بينما يظل قياس الإنتاج الفعلي للنماذج على أجهزة النشر شرط قبول مستقل.

## نتيجة التحقق

| الفحص | النتيجة | الملاحظات |
|---|---:|---|
| `python3 -m pytest -q` | **143 passed** | 16.78 ثانية؛ 4 تحذيرات من Gateway/FastAPI وليست من pipeline |
| `python3 -m pytest -q -m 'not slow'` | **142 passed, 1 deselected** | 7.24 ثانية للاختبارات؛ 8.39 ثانية مع wrapper القياس؛ أقصى RSS ‏218,596 KB |
| `python3 -m pytest -q -m slow` | **1 passed** | render smoke؛ 12.95 ثانية للاختبار؛ 13.91 ثانية مع wrapper القياس؛ أقصى RSS ‏767,152 KB |
| `python3 -m compileall -q pipeline/publikclip_pipeline pipeline/tests` | **نجح** | لا توجد أخطاء syntax/bytecode compilation |
| `git diff --check` | **نجح** | لا توجد whitespace errors |
| FFmpeg probe | **متاح** | FFmpeg 6.1.1 في بيئة الاختبار |

تم قياس RSS على مستوى عملية الاختبار وأبنائها، ولذلك يعكس اختبار render أيضًا كلفة FFmpeg، ولا يمثل حد ذاكرة stage منفردة في الإنتاج.

## مصفوفة التغطية

| Stage | التحقق من input | missing artifact | corrupted checkpoint/cache | timeout/error | memory | logging | reproducibility |
|---|---|---|---|---|---|---|---|
| ingest | source path/type، وجود audio، probe | ملفات media/audio الفارغة أو غير الموجودة تعيد التشغيل | artifacts الفارغة لا تُعتبر fresh | FFmpeg probe/extraction وyt-dlp errors قابلة للتشخيص | stderr محدود نسبيًا، مع بقاء decode نفسه خارج اختبار النموذج | progress أثناء metadata/download/probe/extraction | source hash للملفات وsettings snapshot |
| ASR | وجود ingest وWAV غير الفارغ | missing أو header-only audio يرفع `ASR_AUDIO_INVALID` | يعتمد على checkpoint العام؛ model cache الخارجي خارج سيطرة stage | فشل import/model/transcribe/align يتحول إلى `StageError`؛ تحرير model في `finally` | حذف model و`gc.collect()` قبل alignment وبعده | loading/transcribing/aligning progress | `large-v3-turbo` و`int8` وCPU ثابتة وcache تحت `PUBLIKCLIP_HOME` |
| diarize | ingest+asr+audio | audio المفقود يوقف المرحلة | `diar_embeddings.npy` الفاسد أو ذي الشكل الخاطئ يُحذف ويُعاد حسابه | model/embed/cluster errors تسجلها طبقة runner | ما زال الصوت الكامل والembeddings في الذاكرة | progress للتحميل والembedding والcache | cache مرتبط بعدد النوافذ، وclustering deterministic على نفس المدخلات |
| events | media/audio/ASR outputs | missing أو empty analysis audio يرفع `INPUT_INVALID` | side `curves.json` يُعاد إنتاجه عند إعادة تشغيل stage | FFmpeg extraction timeout وOSError وnon-zero return code لها رسائل ثابتة | `librosa.load` يحمل الصوت كاملًا؛ wav32 المؤقت يُحذف في `finally` | progress لكل channel وbenchmarks في checkpoint | sample rates وgrid ثابتة، وsource arousal مسجل |
| candidates | ingest+diarize+events وحقول curves الأساسية | curves المفقود أو sidecars المفقودة يمنع cache reuse | JSON الفاسد يرفع `ARTIFACT_INVALID` | scene detection يتدهور إلى قناة فارغة عمدًا، أما curves errors فتوقف stage | curves/interest arrays في الذاكرة؛ budget محدد | progress للكشف والمنحنى والنوافذ | budget مربوط بـprocessing mode وduration |
| score | prior outputs، curves، scenes، candidates | missing/invalid curves أو scenes خطأ قابل للتشخيص | LLM cache الفاسد يُحذف ويُعامل كـmiss، والكتابة ذرية | Gemini/Ollama/provider errors وtimeouts لها error codes؛ frame timeout يُتخطى كدليل بصري اختياري | frame bytes وLLM context؛ لا يوجد model-wide memory budget | progress لكل candidate/finalist | cache key يشمل backend/model/prompt/schema/images، وconstants version مسجلة |
| camera | prior outputs، probe، curves، models | trajectories المفقودة أو الفارغة تمنع cache reuse | trajectory JSON الفاسد أو بلا frames يُعامل كـcache miss | أخطاء decode/model ما زالت تعتمد على wrappers الداخلية | raw frames/tracks قد تكون كبيرة، خصوصًا مع مصادر طويلة | progress لكل clip | camera settings snapshot داخل checkpoint |
| render | media/probe/curves/trajectory/clip metadata | trajectory المفقود يرفع `ARTIFACT_MISSING`؛ output الفارغ لا يُعتمد | curves/trajectory/outputs JSON الفاسدة ترفض قبل render أو في resume | FFmpeg timeout/OSError/non-zero، وffprobe timeout/invalid JSON لها paths آمنة؛ cmd file يُحذف دائمًا | rendering يستهلك ذاكرة FFmpeg؛ لا يوجد buffer كامل للفيديو في Python | progress لكل clip وcaption capability | encoder fallback، metadata scrub، وsettings/caption preset ضمن contract |

## الأعطال المثبتة وإصلاحها

| العطل | الإصلاح | regression coverage |
|---|---|---|
| `read_checkpoint` كان ينهار إذا كان JSON array أو يقبل envelope من stage أخرى | رفض envelope غير القاموسي والتحقق من `stage` و`schema_version`؛ يعاد `cache miss` | `test_checkpoint_with_json_array_is_a_cache_miss` |
| media/audio الفارغ في ingest كان يمكن أن يُعتبر artifact صالحًا عند resume | التحقق من regular non-empty files وإعادة استخراج/تنزيل artifact الفارغ | `test_ingest_empty_artifacts_are_not_fresh` |
| فشل normalize بسبب timeout أو غياب executable كان يخرج exception خامًا | تحويله إلى `FfmpegError` قابل للتحويل إلى رسالة stage | `test_normalize_timeout_is_ffmpeg_error` |
| WAV المتوسط الخاص بـPANNs كان يبقى بعد فشل القراءة أو model inference | وضع cleanup داخل `finally`، مع حذف output الجزئي عند extraction failure | مغطى عبر extraction failure contract |
| timeout في audio extraction كان يرفع `TimeoutExpired` خامًا | `StageError` مع code `FFMPEG_TIMEOUT` وتنظيف destination | `test_events_audio_extraction_timeout_is_stage_error` |
| `diar_embeddings.npy` التالف كان يسقط diarization بدل إعادة الحساب | helper آمن باستخدام `allow_pickle=False`، shape validation، وحذف الكاش الفاسد | `test_diarize_corrupt_embeddings_are_recomputed` |
| fallback DSP arousal أسقط remainder bin فأنتج curve أقصر من المتوقع | تجميع آخر bin الجزئي بدل truncation | `test_arousal_dsp_preserves_remainder_bin` |
| curves JSON الفاسد في candidates كان يرفع `JSONDecodeError` خامًا | validation و`StageError(ARTIFACT_INVALID)` | `test_candidates_corrupt_curves_are_a_stage_error` |
| أخطاء provider-router لم تكن موحدة، وقد تُتخطى candidate بصمت | تطبيع setup/runtime failures إلى `LlmError` codes ثابتة؛ score يحولها إلى `StageError` | `test_routed_provider_failure_is_normalized` |
| LLM cache الفاسد كان يوقف scoring، والكتابة غير ذرية | حذف cache غير الصالح عند القراءة والكتابة عبر temp+replace | `test_corrupt_llm_cache_is_ignored_and_removed` |
| frame extraction timeout كان يوقف score رغم أن T2 دليل اختياري | إسقاط frame الفاشل، cleanup دائم، والاستمرار بالصور المتاحة | `test_extract_frames_timeout_is_degraded` |
| فشل videotoolbox probe كان يرفع timeout خامًا | fallback إلى software encoder عند timeout/OSError | مغطى ضمن render timeout contract |
| render cmd file كان يبقى بعد timeout، وffprobe invalid JSON كان ينهار | `finally` لتنظيف cmd file وsafe result من `verify_output` | `test_render_cmd_is_cleaned_on_timeout` و`test_verify_output_handles_ffprobe_failure` |
| trajectory التالفة أو المفقودة كانت تُتخطى بصمت في camera/render | validation JSON وframes، وstage error واضح أو cache miss في resume | `test_camera_corrupt_trajectory_invalidates_checkpoint` و`test_render_stage_rejects_corrupt_trajectory` |

## الاختبارات التي أُضيفت

أُضيف الملف [`pipeline/tests/test_stability_regressions.py`](../pipeline/tests/test_stability_regressions.py)، ويغطي حالات الفشل التي لا تحتاج نماذج أو شبكة. كما سُجل marker `slow` في [`pytest.ini`](../pytest.ini) حتى تكون اختبارات render الثقيلة قابلة للفصل بوضوح. أُضيفت أداة القياس [`scripts/measure_pytest.py`](../scripts/measure_pytest.py) لقياس الزمن وRSS بشكل قابل لإعادة التشغيل.

## الاختناقات الأداءية

أكبر تكلفة متوقعة في ASR هي تحميل `large-v3-turbo` ثم transcription وword alignment؛ المرحلة تحرر نموذج ASR قبل تحميل alignment لتقليل peak RSS، لكن لا يوجد wall-clock budget داخل استدعاءات WhisperX نفسها [1]. Diarization وevents يحملان الصوت الكامل عبر `librosa`، ثم يشغلان embedding/PANNs/SER على مصفوفات في الذاكرة؛ هذا هو المسار الأكثر تعرضًا لضغط الذاكرة مع مصادر طويلة [2] [3].

في camera، يقرأ المسار الإطارات الخام في مرحلتي detection وcrop، بينما render ينفذ decode/encode وfilters وloudnorm وsubtitles في FFmpeg. قياس render smoke المحلي بلغ أقصى RSS للعملية والأبناء **767,152 KB**؛ لا يجوز تفسيره كحد ثابت لكل فيديو أو كل جهاز [4] [5]. أما score فينفذ عددًا من استدعاءات LLM لكل candidate/finalist، واستخراج frames هو تكلفة FFmpeg إضافية؛ cache key والكتابة الذرية يقللان إعادة الإنفاق بعد النجاح أو crash [6] [7].

## Known issues

1. **لا يوجد timeout شامل داخل عمليات WhisperX أو inference المحلية.** توجد timeouts حول أدوات FFmpeg/HTTP، لكن `whisperx.load_model` و`transcribe` و`align` استدعاءات داخلية طويلة؛ يجب أن تُعامل كـoperational risk في worker supervision بدل افتراض أن stage تستطيع إيقافها ذاتيًا [8].

2. **التحميل الكامل للصوت في الذاكرة.** `librosa.load` في diarization/events، ومصفوفات الإطارات في camera، ليست streaming pipelines. مصادر الساعات الطويلة قد تتجاوز حدود الذاكرة العملية، لذلك يلزم benchmark على hardware الإنتاج قبل اعتماد مدة مصدر قصوى [2] [3] [9].

3. **سلامة الأوزان غير المثبتة بالكامل.** registry يتحقق من `sha256` عندما يكون pinned ويرفض الملفات الفارغة، لكن specs الحالية تحتوي entries بلا hashes؛ الملف غير الفارغ قد يظل غير موثق integrity حتى يصل hash release رسمي [10] [11].

4. **تدهور متعمد في بعض المسارات.** scene detection failures تتحول إلى channel فارغة، وT2 frames الفاشلة لا توقف scoring، وSER يعود إلى DSP proxy. هذا يحافظ على إتمام العمل لكنه يجب أن يظهر في provenance ويؤخذ في تقييم الجودة؛ stage الحالية تسجل `arousal_source` و`signals_missing` حيث ينطبق ذلك [3] [6].

5. **التحذيرات المتبقية من خارج pipeline.** suite تعرض تحذيرات `FastAPI on_event` من Gateway. لم تُعدّل لأنها خارج نطاق الطلب ولا تؤثر في stages الثمانية.

## Platform limitations

| البيئة | القيد |
|---|---|
| Python | الحزمة تعلن Python `>=3.12,<3.13`؛ يجب تثبيت هذا النطاق في CI/runtime [12]. |
| ASR | التنفيذ المحلي مضبوط على CPU لأن مسار ctranslate2 الحالي لا يستخدم MPS داخل stage؛ هذا يجعل benchmark الجهاز الفعلي مهمًا [8]. |
| FFmpeg/Linux | `ffmpeg_bin.ensure_capable` لا ينزّل static caption-capable build على Linux؛ يلزم FFmpeg النظامي بمرشح subtitles/libass عند الحاجة [4]. |
| FFmpeg/macOS/Windows | توجد مسارات اكتشاف وتنزيل static build، لكن توفر الشبكة والصلاحيات ومسار binary يظل شرطًا تشغيليًا [4]. |
| Hardware encoding | Videotoolbox اختياري؛ عند عدم توفره يستخدم render `libx264`. اختلاف encoder/hardware قد يغير زمن التنفيذ، لا contract المخرجات الأساسي [5]. |
| LLM | Gemini يحتاج secret صالحًا وOllama يحتاج daemon/model محليًا؛ provider-router يعتمد profiles وcredentials خارج المستودع [6] [13]. |
| Models | أوزان WhisperX، CAMPPlus، PANNs، UltraFace، وLR-ASD خارج Git؛ لا يصح اعتبار suite الخفيفة إثباتًا لصحة كل وزن أو parity على كل منصة [8] [10] [11]. |

## Definition of done لهذا hardening

يُعتبر التغيير الحالي مكتملًا ضمن نطاقه عندما يمر `python3 -m pytest -q`، ويظل render smoke ناجحًا، وتكون checkpoint/cache/artifact failures قابلة للاسترداد أو ذات رسالة stage واضحة، ولا توجد تغييرات في product features. الإطلاق الفعلي ما زال مشروطًا بتشغيل benchmark end-to-end على كل target platform مع الأوزان المثبتة، وتحديد سقف مدة/ذاكرة للمصادر الطويلة.

## المراجع

[1]: ../pipeline/publikclip_pipeline/asr/stage.py "ASR stage"
[2]: ../pipeline/publikclip_pipeline/diarize/stage.py "Diarization stage"
[3]: ../pipeline/publikclip_pipeline/events/stage.py "Events stage"
[4]: ../pipeline/publikclip_pipeline/render/ffmpeg_bin.py "FFmpeg binary resolution"
[5]: ../pipeline/publikclip_pipeline/render/renderer.py "FFmpeg renderer"
[6]: ../pipeline/publikclip_pipeline/scoring/stage.py "Scoring stage"
[7]: ../pipeline/publikclip_pipeline/scoring/llm.py "LLM clients and cache"
[8]: ../pipeline/pyproject.toml "Pipeline dependencies and Python version"
[9]: ../pipeline/publikclip_pipeline/camera/asd.py "Camera ASD decode path"
[10]: ../pipeline/publikclip_pipeline/models/registry.py "Model registry and checksum handling"
[11]: ../pipeline/publikclip_pipeline/models/specs.py "Registered model specifications"
[12]: ../pipeline/pyproject.toml "Python compatibility declaration"
[13]: ../pipeline/publikclip_pipeline/scoring/providers.py "Provider router"
