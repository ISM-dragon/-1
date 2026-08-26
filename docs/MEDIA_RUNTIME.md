# Media Runtime

**المكان:** Private backend وPublikClip Engine.

## دورة الوسائط

تبدأ كل وظيفة بـprobe وvalidation قبل تشغيل AI. يُحفظ المصدر في job-private directory، وتُستخرج نسخة audio عند الحاجة، وتُستخدم frame extraction وCFR/transcoding حسب متطلبات stages. بعد render يتحقق النظام من وجود MP4 غير صفري، وقابلية القراءة، والحجم، والـchecksum، ثم ينشر artifact عبر Gateway المحمي. تُحذف الملفات المؤقتة فقط بعد نجاح النشر أو وفق retention policy تشخيصية.

| العملية | شرط النجاح |
|---|---|
| Probe | وجود container/streams وduration قابلة للقراءة. |
| Validate | صيغة وحجم وcodec ضمن policy، مع رفض الملف الفاسد قبل pipeline. |
| Audio extraction | ملف audio قابل للقراءة ومعدل عينة مناسب للـASR. |
| Frame extraction | timestamps منتظمة لاستخدام face/speaker analysis. |
| CFR/transcode | مصدر متوافق مع render دون drift أو variable-rate مفاجئ. |
| Render | خروج clip يطابق 9:16 وإعدادات captions/camera المطلوبة. |
| Cleanup | إزالة temp files دون حذف checkpoints أو outputs المنشورة. |

## الأخطاء المستقرة

| code | المعنى | قابلية retry |
|---|---|---:|
| `MEDIA_INVALID` | الملف غير قابل للفحص أو فاسد | لا |
| `FFMPEG_MISSING` | الأدوات غير مثبتة أو غير قابلة للتشغيل | بعد إصلاح الخادم فقط |
| `FFMPEG_FAILED` | فشل أمر FFmpeg بعد validation | حسب السبب |
| `MODEL_MISSING` | نموذج مطلوب غير موجود | بعد التثبيت |
| `MODEL_INVALID` | checksum أو metadata غير صالح | بعد إعادة التنزيل |
| `INSUFFICIENT_DISK` | المساحة غير كافية للمصدر/intermediates/output | بعد التنظيف أو زيادة المساحة |
| `UNSUPPORTED_FORMAT` | container/codec خارج policy | لا |

لا يضمّن Gateway command lines كاملة أو مسارات host أو secrets في response للعميل. تُحفظ التفاصيل محليًا في logs مرتبطة بـ`correlation_id`.

### المراجع

[1]: ../pipeline/publikclip_pipeline/media/ "Media pipeline modules"
[2]: ../pipeline/publikclip_pipeline/render/ "Rendering and FFmpeg resolution"
[3]: ../gateway/main.py "Upload validation and media delivery"
[4]: ../gateway/processing_service.py "Processing bridge"
[5]: ../evidence/gateway_smoke.json "Observed invalid-media behavior"

## References

[1]: ../pipeline/publikclip_pipeline/media/ "Media pipeline modules"
[2]: ../pipeline/publikclip_pipeline/render/ "Rendering and FFmpeg resolution"
[3]: ../gateway/main.py "Upload validation and media delivery"
[4]: ../gateway/processing_service.py "Processing bridge"
[5]: ../evidence/gateway_smoke.json "Observed invalid-media behavior"
