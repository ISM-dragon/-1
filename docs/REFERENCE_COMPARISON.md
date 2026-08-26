# مقارنة المشروع المرجعي مع publikclip

**الحالة:** مكتملة كمرحلة تدقيق أولي في 26 أغسطس 2026. هذه الوثيقة تحليل ومقارنة، وليست تفويضًا لنسخ المشروع المرجعي أو استبدال بنية المستودع.

## الخلاصة التنفيذية

المشروع المرفق `video_clipper-main` هو تطبيق Flask أحادي العملية يضم واجهة ويب، إدارة وظائف، تحليلًا نصيًا، Whisper المحلي، تقطيع FFmpeg، مولد ترجمات وصور مصغرة، وتنزيلًا عبر `yt-dlp`. أما المستودع الأساسي فيحتوي بالفعل على فصل أقوى بين عميل Android أصلي، وطبقة Gateway/Backend، ومحرك Python متعدد المراحل يضم checkpoints ونتائج قابلة للتدقيق. لذلك لا توجد مبررات لإعادة كتابة المحرك أو نسخ المشروع المرجعي بالكامل.

القرار العام هو **KEEP_CURRENT** للمحرك ومراحل AI/media وjob system، و**ADD_REFERENCE** فقط للأفكار القابلة لإعادة التنفيذ مستقلًا، مثل واجهة الاختيار البسيطة، أوضاع التقطيع المتسلسل، وتحسينات تجربة مراجعة المقطع. وبسبب عدم وجود ملف `LICENSE` مستقل في الأرشيف، مع وجود إعلان MIT داخل `pyproject.toml` فقط، لا يُنقل أي كود حرفيًا قبل الحصول على مصدر ترخيص واضح؛ ستقتصر هذه المرحلة على فهم السلوك وإعادة التنفيذ المستقل عند الضرورة [1] [2].

## مقارنة على مستوى المكونات

| المجال | المشروع الأساسي | المشروع المرجعي | القرار | سبب القرار |
|---|---|---|---|---|
| Android | Kotlin/Compose، Room، WorkManager، Media3، Photo Picker وعميل Gateway | لا يوجد عميل Android أصلي | KEEP_CURRENT | الأساس المطلوب للهاتف موجود في المستودع الأساسي؛ المرجع لا يضيف قيمة على lifecycle أو permissions. |
| API/backend | Gateway FastAPI بعقد v1، SQLite، auth، workers، uploads، retry/resume | Flask monolith مع routes وjob manager محلي | KEEP_CURRENT + IGNORE_REFERENCE | الفصل بين العميل والخادم مطلوب، بينما Flask المرجعي لا يحقق حدود Android/Backend/Engine. |
| رفع الوسائط | Gateway يملك upload one-shot وresumable sessions مع checksum | رفع Flask تقليدي وملفات محلية | IMPROVE_CURRENT | نضيف عميلًا متوافقًا مع عقد الرفع القابل للاستئناف، لا نعود إلى one-shot فقط. |
| ingest/probing | Pipeline ingest وFFmpeg/ffprobe على الخادم | FFmpeg محلي وعمليات تنزيل | KEEP_CURRENT | المعالجة الثقيلة لا ينبغي أن تدخل APK، وpipeline الحالي يحتوي على checkpoint وvalidation. |
| ASR | stages قائمة مع word timestamps ومواءمة/diarization عند توفر runtime | Whisper محلي دون حدود Android أو فصل model manager | KEEP_CURRENT | نقل Whisper desktop إلى Android يخالف شرط العزل؛ المرجع لا يحسن contract الحالي. |
| diarization/ASD/face | وحدات `diarize/` و`camera/` وvendor models | لا يوجد مكافئ متكامل | KEEP_CURRENT | التنفيذ الأساسي أعمق ويخدم auto-framing. |
| candidates/scoring | candidates، audio events، laughter، rubric، LLM providers، calibration | TF-IDF، hooks، quotes، emotion، LLM fallback | COMBINE CONCEPTS | يُدرس ترتيب hooks والأنماط فقط؛ يبقى scoring الحالي مصدر الحقيقة، مع fallback لا يحول فشل LLM إلى crash. |
| camera | active-speaker، face tracks، smoothing، zoom، transitions | vertical fit عام دون speaker director مكافئ | KEEP_CURRENT | الكاميرا الحالية متخصصة وموجود لها اختبارات. |
| captions | word timing، ASS، karaoke/emphasis، fonts وفصل نسبي عن render | SRT وburn-in ومولد subtitles | IMPROVE_CURRENT | نستفيد من بساطة قوالب المرجع، دون إعادة transcription أو ربط caption state بواجهة Android. |
| render | renderer/stage، artifact validation، FFmpeg capability | clipper/video editor ومؤثرات مباشرة | KEEP_CURRENT + ADD_REFERENCE | نحتفظ بالرندر الخادمي ونجعل خيارات edit قابلة للتمرير كـrender options. |
| library/results | Room ومشروع/مقطع وcache محلي في Android | JSON library ومجلدات clips_output | KEEP_CURRENT | Room وapp-private storage أقوى لإعادة التشغيل وAndroid process death. |
| sequential splitting | `candidates/windows.py` ومسار pipeline الحالي | `sequential_splitter.py` كتقسيم كامل متتابع | ADD_REFERENCE بعد قياس | فكرة مفيدة كـmode اختياري، لكنها لا تدخل production قبل regression tests وbenchmark. |
| downloader | `ingest/ytdlp.py` خلف Gateway | `media_management/downloader.py` | KEEP_CURRENT | تنزيل URL يجب أن يبقى في الخادم مع SSRF validation وعزل الشبكة. |
| social/publishing | وظائف اختيارية في Gateway | YouTube uploader وواجهات ويب | IGNORE_REFERENCE | النشر ليس شرطًا لمسار Android الشخصي الحالي. |
| pattern learning | calibration وpersonal taste وanalytics موجودة جزئيًا | `pattern_learning/trainer.py` | MANUAL_REVIEW | يحتاج تعريفًا واضحًا للبيانات والخصوصية ولا يسبق استقرار processing. |

## مقارنة تجربة Android المطلوبة

> المسار المعتمد هو: **Home → Import → Generate → Processing → Results → Review → Edit → Render → Export**.

المرجع يقدم dashboard ويب متكاملًا، لكنه ليس تصميمًا مناسبًا للنسخ إلى الهاتف حرفيًا. سيُعاد استخدام الفكرة فقط عندما تقلل عدد القرارات في شاشة الهاتف: اختيار فيديو واضح، حالة رفع منفصلة، progress قابل للاستعادة، ثم قائمة نتائج ومراجعة clip. أما القوائم التسويقية، factory، trend scout، وsocial publishing فتبقى خارج المسار الشخصي.

## تقييم موزون

استخدمت الأوزان المحددة في طلب التدقيق. الدرجات التالية تقدير هندسي للمقارنة، وليست benchmark أداءً حقيقيًا.

| المكوّن | Android 20% | Correctness 20% | Stability 15% | Performance 15% | Maintainability 10% | Completeness 10% | Integration cost 5% | Dependency cost 5% | الحكم |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Android native الأساسي | 10 | 9 | 8 | 8 | 8 | 8 | 8 | 8 | KEEP_CURRENT |
| Gateway/job system الأساسي | 8 | 9 | 8 | 8 | 8 | 9 | 7 | 7 | KEEP_CURRENT |
| Pipeline engine الأساسي | 4 | 9 | 8 | 8 | 8 | 9 | 8 | 7 | KEEP_CURRENT |
| Reference Flask web stack | 5 | 6 | 5 | 5 | 6 | 7 | 3 | 4 | IGNORE_REFERENCE |
| Reference NLP/LLM ideas | 2 | 6 | 5 | 7 | 7 | 5 | 6 | 6 | COMBINE CONCEPTS |
| Reference sequential splitter | 3 | 7 | 5 | 7 | 7 | 5 | 6 | 6 | ADD_REFERENCE بعد القياس |

## ما لم يُنسخ

لم يتم نسخ أي مجلد أو ملف من الأرشيف إلى المستودع الأساسي. ولم يتم إدخال dependencies المرجعية مثل Flask أو Whisper المحلي أو Groq أو Google SDK أو Edge TTS إلى المسار الأساسي؛ ذلك يحافظ على حدود الخادم ويمنع تضخم APK أو runtime غير الضروري.

## المراجع

[1]: ../reference/video_clipper-main/README.md "Reference project README and feature inventory"
[2]: ../reference/video_clipper-main/pyproject.toml "Reference project metadata and declared MIT text license"
[3]: ../pipeline/publikclip_pipeline/engine/contracts.py "Primary engine contract"
[4]: ../gateway/main.py "Primary Gateway API, jobs, uploads, and artifact handling"
[5]: ../android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Live Android processing worker"
[6]: ../android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt "Existing Android remote client"
[7]: ../docs/ARCHITECTURE.md "Canonical architecture decision"

## References

هذه المراجع داخل المستودع نفسه؛ لا يوجد مصدر خارجي مطلوب لإثبات المقارنة المعمارية، لأن المقارنة مبنية على الشيفرة والملفات المرفقة.
