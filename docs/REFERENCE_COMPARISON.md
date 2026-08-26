# مقارنة المشروع الأساسي بالمشروع المرجعي

## النطاق والمنهجية

أُجريت المقارنة بين مستودع `ISM-dragon/-1` وبين المرجع المرفق `clipper-main`. المرجع مشروع Python/FastAPI أحادي المستخدم يركز على رفع فيديو واحد، transcription، اختيار المقاطع، إعادة التأطير، captions، والرندر. المشروع الأساسي أوسع، ويضم مسار Android أصليًا، Gateway خاصًا، محركًا عامًا، pipeline متعددة المراحل، وواجهات سطح مكتب وميزات اجتماعية اختيارية.

المرجع استُخدم كمصدر أفكار وتصميمات قابلة للتقييم فقط. لم تُنسخ شجرة المستودع أو ملفات build أو الأسرار أو ملفات النماذج إلى المشروع الأساسي.

## المقارنة

| المجال | المشروع الأساسي | المشروع المرجعي | القرار | سبب القرار |
|---|---|---|---|---|
| Android | تطبيق Native مستقل يستخدم Compose وRoom وWorkManager وGateway client | لا يوجد عميل Android canonical | `KEEP_CURRENT` | المسار الحالي يحقق boundary صحيحًا ولا ينقل Python إلى الهاتف. |
| اختيار الفيديو | URI محلي ونسخ إلى storage خاص ثم رفع | drag-and-drop عبر واجهة ويب | `KEEP_CURRENT` | URI وPhoto Picker أنسب لـAndroid ولا تتطلب صلاحيات تخزين واسعة. |
| Gateway/API | FastAPI، SQLite، auth، رفع قابل للاستئناف، jobs، polling، cancel/resume | FastAPI محلي، job واحد، رفع مباشر | `KEEP_CURRENT` + `IMPROVE_CURRENT` | عقد Gateway الحالي أقوى للاستخدام عن بعد ويحتاج توثيقًا موحدًا فقط. |
| Job lifecycle | Queue وSQLite وcheckpoints وrestart recovery | حالة داخل الذاكرة وprogress محلي | `KEEP_CURRENT` | الاستمرارية بعد restart شرط أساسي للمسار المحمول. |
| Engine facade | `ProcessingEngine` و`PipelineEngine` بعقد JSON-shaped | دوال pipeline مباشرة | `KEEP_CURRENT` | الفصل الحالي يقلل coupling بين Gateway والمراحل. |
| ASR | مراحل ASR قابلة للتبديل، مع عزل runtime غير المتاح | faster-whisper | `KEEP_CURRENT` | لا يوجد سبب لنقل ASR إلى Android أو استبدال pipeline دون benchmark. |
| Scoring | candidates + score + fallbackات متعددة | Ollama/Qwen مع تنظيف JSON | `COMBINE` على مستوى guardrails | فكرة clamping وتنظيف مخرجات LLM مفيدة، لكن scoring الحالي لا يُستبدل بلا قياس. |
| Camera | مراحل camera وface/active-speaker ضمن pipeline | YuNet مع smoothing وlayouts | `IMPROVE_CURRENT` بعد benchmark | يمكن استعارة مبادئ smoothing وfallback، لا نسخ التنفيذ مباشرة. |
| Captions | word timestamps وrender pipeline | karaoke/boxed/bold وkeyword emphasis | `IMPROVE_CURRENT` | أنماط captions في المرجع مناسبة، بشرط فصل caption state عن render logic. |
| Media | FFmpeg/ffprobe والتحقق من artifacts داخل Gateway وpipeline | FFmpeg utility وcut spans وtranscoding | `COMBINE` | نحتفظ بحدود الخادم ونضيف تصنيفًا موحدًا للأخطاء عند الحاجة. |
| B-roll | optional server-side capabilities | Pexels optional مع no-op آمن | `IGNORE_REFERENCE` حاليًا | ليس جزءًا من مسار Android الأول، ويزيد network/licensing surface. |
| Brand kit | إعدادات موجودة عبر UI/config | `brand.json` محلي | `MANUAL_REVIEW` | يلزم قرار منتجي قبل تثبيت storage contract؛ لا يؤثر على core processing. |
| Desktop UI | React/Tauri قائم | Vanilla HTML بلا build | `KEEP_CURRENT` | اختلاف الهدف؛ لا تُنقل واجهة سطح المكتب إلى الهاتف. |
| Licensing | المستودع الأساسي AGPL-3.0 | المرجع MIT | `IGNORE_REFERENCE` للكود | لا نسخ source code؛ تُستفاد الأفكار ويُسجل المرجع فقط. |

## تقييم موزون للخيارات المؤثرة

القيم التالية تقديرية هندسية مبنية على قراءة الملفات والاختبارات الحالية، وليست benchmark أداءً ميدانيًا.

| الخيار | Android 20% | Correctness 20% | Stability 15% | Performance 15% | Maintainability 10% | Completeness 10% | Integration 5% | Dependency 5% | النتيجة / 100 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| إبقاء Gateway + Engine الحاليين | 95 | 90 | 88 | 82 | 88 | 84 | 92 | 90 | **88.9** |
| نقل المرجع كاملًا إلى Android | 35 | 55 | 42 | 38 | 30 | 65 | 25 | 20 | **40.5** |
| استبدال المحرك الحالي بمراحل المرجع | 60 | 68 | 55 | 65 | 52 | 72 | 45 | 55 | **60.4** |
| دمج guardrails وcaption ideas بشكل انتقائي | 82 | 84 | 80 | 78 | 78 | 86 | 72 | 75 | **80.8** |

## الخلاصة

المسار الأفضل هو **أفضل ما في المشروع الأساسي مع تحسينات انتقائية من المرجع**. يبقى Android عميلًا خفيفًا، ويبقى Gateway نقطة التحكم الوحيدة، ويبقى `PipelineEngine` واجهة المحرك العامة. تُؤجل إعادة تصميم camera/captions إلى تغييرات صغيرة قابلة للاختبار، ويُمنع نسخ كود المرجع ما لم تُراجع تراخيص الملفات والاعتماديات ويُضاف اختبار regression.

## مراجع الملفات الداخلية

- [`docs/ARCHITECTURE.md`](ARCHITECTURE.md)
- [`pipeline/publikclip_pipeline/engine/contracts.py`](../pipeline/publikclip_pipeline/engine/contracts.py)
- [`pipeline/publikclip_pipeline/engine/pipeline.py`](../pipeline/publikclip_pipeline/engine/pipeline.py)
- [`gateway/main.py`](../gateway/main.py)
- `reference_zip/clipper-main/ARCHITECTURE.md` في مساحة العمل المحلية، وهو غير منسوخ إلى المستودع.
