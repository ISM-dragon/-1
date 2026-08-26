# PublikClip Engine

## الغرض

المحرك هو boundary Python مستقل يستقبل مصدرًا وإعدادات ويعيد سجلات JSON-shaped. لا يعرف Android أو Gateway auth أو حسابات النشر. يملك فقط تركيب stages، حالة job، checkpoints، artifacts، progress، cancel، resume، وrender clip.

## الواجهة العامة

| العملية | الوظيفة |
|---|---|
| `create_job()` | إنشاء job وحفظ settings snapshot |
| `start_job()` | تشغيل المراحل بالترتيب مع progress callback |
| `get_job_status()` / `status()` | قراءة الحالة والتقدم والأخطاء |
| `progress()` | إسقاط تقدم موحد بين 0 و1 |
| `cancel_job()` | تسجيل طلب إلغاء قابل للرصد من stage runner |
| `resume_job()` | مسح cancel state وإعادة تشغيل المراحل غير المكتملة |
| `get_job_results()` / `results()` | قراءة checkpoints وartifacts |
| `get_clip()` | قراءة clip واحد من scoring وrender |
| `render_clip()` | إعادة رندر clip محدد دون إعادة المراحل البطيئة |

## ترتيب المراحل

```text
ingest → asr → diarization → events → candidates → scoring → camera → render
```

يُحوّل المحرك الأسماء الداخلية `diarize` و`score` إلى `diarization` و`scoring` في العقد العام. كل stage يكتب checkpoint ذريًا، ويُعد cache صالحًا فقط إذا تطابق `schema_version` وكانت artifacts موجودة وصالحة.

## الاسترداد

SQLite يسجل الحالة، والملفات على القرص هي مصدر الحقيقة للمخرجات. بعد restart، يقرأ runner checkpoints السليمة ويتجاوزها، ويعيد تشغيل المرحلة التي فقدت checkpoint أو artifact. عند الإلغاء لا تُعامل العملية كنجاح؛ تُحفظ `JOB_CANCELLED` ليستطيع Gateway عرضها واستئناف job إذا كان ذلك مناسبًا.

## الأخطاء

يحوّل المحرك استثناءات التنفيذ إلى `EngineError` آمن يحوي `code` و`safe_message` و`recoverable`. لا تُرسل stack traces أو مسارات الخادم إلى Android. يجب أن تعلن stages عن أخطاء قابلة للإجراء مثل `MEDIA_INVALID`, `MODEL_MISSING`, `FFMPEG_FAILED`, و`INSUFFICIENT_DISK` بدل إرجاع خطأ عام كلما أمكن.
