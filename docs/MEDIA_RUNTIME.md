# Media Runtime

## المسؤولية

تجري probing وvalidation وaudio extraction وframe extraction وCFR/transcoding وrender وcleanup على الخادم الخاص. Android يثبت URI ويعرض preview خفيفًا، لكنه لا يعتمد على desktop FFmpeg أو Python runtime.

## دورة الوسائط

```text
source bytes/URL
 → validation + ffprobe
 → normalized working media
 → audio/frames
 → analysis artifacts
 → candidate/render outputs
 → MP4 validation + checksum
 → authenticated download
 → cleanup حسب retention policy
```

## تصنيف الأخطاء

| الرمز | المعنى | قابلية إعادة المحاولة |
|---|---|---|
| `MEDIA_INVALID` | الملف تالف أو لا يمكن قراءته | لا، إلا بعد اختيار ملف آخر |
| `UNSUPPORTED_FORMAT` | container/codec غير مدعوم | لا، بعد transcoding أو ملف آخر |
| `FFMPEG_MISSING` | binary غير متاح على الخادم | نعم بعد إصلاح البيئة |
| `FFMPEG_FAILED` | فشل أمر FFmpeg | يعتمد على الرسالة والـartifact |
| `MODEL_MISSING` | النموذج لم يُثبت | نعم بعد التنزيل |
| `MODEL_INVALID` | checksum أو loading غير صالح | نعم بعد إعادة التحقق |
| `INSUFFICIENT_DISK` | المساحة أقل من الحد | نعم بعد التنظيف/التوسعة |
| `CLIP_FILE_NOT_FOUND` | artifact غير موجود | نعم إذا كان checkpoint سليمًا |

## الأمن والتنظيف

كل مسار artifact يُحل داخل job root قبل تقديمه. تُحذف الملفات المؤقتة عند الفشل، وتُحفظ outputs وcheckpoints فقط وفق retention policy. يجب أن يفرض endpoint download المصادقة وmedia type المتوقع وألا يكشف مسارات filesystem.

## المراجع

[1]: ../pipeline/publikclip_pipeline/ingest/normalize.py "Media normalization"
[2]: ../pipeline/publikclip_pipeline/render/ffmpeg_bin.py "FFmpeg discovery"
[3]: ../pipeline/publikclip_pipeline/render/renderer.py "Rendering"
[4]: ../gateway/worker_queue.py "Artifact validation and disk safety"
[5]: ../gateway/main.py "Upload and media endpoints"

## References

المراجع محلية داخل المستودع.
