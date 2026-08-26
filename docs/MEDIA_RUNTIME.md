# Media Runtime

**الحالة:** منفّذ على الفرع `agent/ai-media`  
**النطاق:** فحص وتشغيل FFmpeg وffprobe وعمليات الوسائط المشتركة، من دون تغيير عقود Backend أو Android UI أو خوارزميات scoring.

## الملخص

يوفر `MediaManager` نقطة دخول واحدة لعمليات `probe` و`validate` و`extract_audio` و`extract_frames` و`transcode` و`render` و`cleanup`، مع فحص مستقل لصلاحية FFmpeg وffprobe. كل عملية تستخدم subprocess محدودًا بمهلة، وتحوّل غياب binary أو فشله أو فساد المصدر إلى خطأ مصنف بدل crash عام.

> ملف الفيديو لا يصبح صالحًا لمسار التحليل لمجرد أن امتداده `.mp4`. يجب أن ينجح `ffprobe` ويظهر video stream صالح، ويُطلب audio stream فقط في العمليات التي تحتاجه.

## واجهة MediaManager

```python
from publikclip_pipeline.runtime.media_manager import MediaManager

media = MediaManager()
readiness = media.status()
probe = media.probe("source.mp4")
media.validate("source.mp4", require_audio=True)
media.extract_audio("source.mp4", "analysis.wav", sample_rate=16_000)
media.extract_frames("source.mp4", "frames", fps=2)
media.transcode("source.mp4", "normalized.mp4")
media.render("normalized.mp4", "final.mp4", extra_args=["-movflags", "+faststart"])
media.cleanup(["analysis.wav", "frames"])
```

| العملية | النتيجة أو الضمان |
|---|---|
| `check()` / `status()` | سجل readiness يحوي مساري FFmpeg وffprobe، الإصدار، دعم `subtitles`، profile العتاد، و`error_code` عند الفشل، من دون رفع استثناء للحالة العادية. |
| `probe(path)` | `MediaProbe` يتضمن المدة والأبعاد وFPS وcodec الفيديو والصوت ووجود audio وformat. يرفض الملف المفقود أو الفارغ أو الذي لا يحوي video stream. |
| `validate(path, require_audio=False)` | يعيد `valid=true` وprobe؛ ومع `require_audio=true` يرفض المصدر الذي لا يحوي audio. |
| `extract_audio(source, destination)` | يحول إلى mono PCM WAV بمعدل 16 kHz افتراضيًا، ويتحقق من أن الناتج أكبر من header. |
| `extract_frames(source, destination_dir)` | يستخرج frames عبر filter `fps` إلى ملفات مرقمة، ويتحقق من وجود output فعلي. |
| `transcode(source, destination)` | يعيد ترميز H.264/AAC افتراضيًا ثم يفحص output عبر ffprobe. |
| `render(source, destination, extra_args)` | نفس مسار الترميز مع نقطة مخصصة لخيارات الرندر الحالية مثل captions و`faststart`. لا يغيّر scoring أو camera decisions. |
| `cleanup(paths)` | يحذف artifacts ملفية أو مجلدات مؤقتة ويعيد عدد العناصر المحذوفة. |

## ترتيب اكتشاف FFmpeg

يحافظ manager على resolver المشروع الحالي: متغير `PUBLIKCLIP_FFMPEG`، binary المُدار تحت `PUBLIKCLIP_HOME/bin`، binary bundled الذي يحدده `PUBLIKCLIP_BUNDLED_FFMPEG`، نسخة Homebrew `ffmpeg-full` على macOS، ثم `PATH`. ويُبحث عن `ffprobe` بجوار FFmpeg المحلّي أو عبر `PATH`.[1]

لا يكفي العثور على executable؛ ينفذ `check()` كلًا من `ffmpeg -version` و`ffmpeg -filters` و`ffprobe -version`. يسجل `has_subtitles=false` إذا لم يكن filter `subtitles` موجودًا، لكن لا يحول ذلك بذاته إلى crash. مسار caption-capable download الموجود مسبقًا يظل مسؤولًا عن macOS وWindows فقط، بينما Linux يعتمد على FFmpeg النظامي أو إعداد خارجي.[1]

## الأخطاء المستقرة

| الكود | متى يظهر | ما الذي يراه المستهلك |
|---|---|---|
| `FFMPEG_MISSING` | executable غير موجود أو ffprobe غير قابل للبدء. | تثبيت FFmpeg/ffprobe أو تصحيح override؛ لا تبدأ عملية media. |
| `FFMPEG_INVALID` | timeout أو binary فاشل أو غير صالح أثناء readiness، أو فشل تشغيل لا يرتبط بمصدر media محدد. | فشل runtime قابل للتشخيص مع إعادة المحاولة بعد إصلاح البيئة. |
| `MEDIA_INVALID` | مصدر مفقود/فارغ/مكسور، metadata غير صالحة، لا يوجد video stream، لا يوجد audio عند طلبه، أو output فارغ. | ارفض المصدر أو artifact؛ لا تمرر ملفًا غير متحقق منه للمراحل التالية. |

عند وجود source محدد وفشل FFmpeg في قراءته، يصنف manager الخطأ كـ`MEDIA_INVALID` لأن الإجراء المطلوب هو رفض المصدر. أما فشل binary العام في `check()` فيصنف `FFMPEG_MISSING` أو `FFMPEG_INVALID`. هذه التفرقة تمنع إخفاء عطل المضيف داخل رسالة media عامة.

## سياسة العمليات

تتحقق عمليات القراءة أولًا من وجود الملف وحجمه، ثم من جاهزية FFmpeg، ثم من metadata. تكتب عمليات الاستخراج والترميز داخل parent directory موجود أو تنشئه، وتفحص الناتج بعد انتهاء العملية. subprocess لا يستخدم shell، وتوجد timeout لكل نوع: probe قصير، audio/frame extraction حتى ساعة، والترميز/الرندر حتى ست ساعات للحالات الطويلة.

فيديو صالح بلا audio مقبول في `probe` و`validate` الافتراضي؛ وهذا يتيح تشخيصه بوضوح. لكنه يرفض `extract_audio` و`validate(require_audio=True)` برمز `MEDIA_INVALID`. فيديو مكسور لا يُصلح تلقائيًا ولا يُرسل إلى stage التالية. الفيديو الكبير يعالج عبر FFmpeg إلى ملفات على القرص، ولا يُحمّل كاملًا في ذاكرة Python.

## التكامل مع المسار القائم

يبقى `ingest/normalize.py` و`render/ffmpeg_bin.py` متوافقين مع الاستدعاءات القائمة. استُخدم `MediaManager` كطبقة runtime موحدة جديدة، بينما لم تُعد كتابة renderer أو قواعد camera/scoring. في حال استخدام manager داخل stage أو worker، يجب تمرير `MediaRuntimeError.code` إلى job error؛ وقد أضيفت حماية stage runner للحفاظ على الأكواد `FFMPEG_MISSING` و`FFMPEG_INVALID` و`MEDIA_INVALID` بدل تحويلها تلقائيًا إلى `ENGINE_FAILED`.

يتطابق ذلك مع عقد المشروع الذي يفصل بين ingest/ASR/diarization/render ويفرض structured errors في واجهة Gateway.[2] كما يظل رفع المصدر atomic ومسار `.part` غير مكشوف للعملاء وفق مواصفة media upload الحالية.[3]

## التشغيل والاختبار

تشغيل اختبارات runtime:

```bash
PYTHONPATH=pipeline pytest -q pipeline/tests/test_ai_media_runtime.py
```

يغطي الاختبار فيديو صالحًا مع audio، probe وvalidate وextract audio وframes وtranscode وcleanup، وفيديو مكسور، وفيديو بلا audio، وفيديو أكبر وأعلى دقة، وbinary FFmpeg مفقودًا أو فاشلًا. وتشمل اختبارات ModelManager missing/corrupt وload/unload/delete. الاختبارات لا تتطلب تنزيل checkpoints حقيقية، لذلك لا تدّعي نجاح inference أو E2E مع مزود خارجي.

## مراجع

[1]: ../pipeline/publikclip_pipeline/render/ffmpeg_bin.py "Existing FFmpeg resolver and caption capability detection"
[2]: MASTER-ARCHITECTURE.md "ISM canonical pipeline boundaries and structured failure lifecycle"
[3]: MEDIA_PIPELINE.md "ISM atomic media upload, integrity, and cleanup contract"
