# Media Runtime

## الحدود

FFmpeg وffprobe يعملان على الخادم الخاص فقط. Android يرفع bytes ويعرض preview وينزل artifact النهائي؛ ولا يحمل binary FFmpeg أو يعتمد على desktop media runtime.

## العمليات

يجب أن تغطي طبقة الوسائط probing، validation، audio extraction، frame extraction، CFR/transcoding، rendering، cleanup، والتحقق من artifact النهائي. كل مسار ملف يجب أن يبقى داخل job/source root المسموح، وكل MP4 نهائي يجب أن يمر بفحص readability قبل إعادته.

## تصنيف الأخطاء

| الكود | المعنى | قابلية الاسترداد |
|---|---|---:|
| `MEDIA_INVALID` | الملف غير مقروء أو بلا video stream صالح | لا غالبًا |
| `UNSUPPORTED_FORMAT` | الحاوية أو النوع غير مدعوم | لا |
| `FFMPEG_MISSING` / `FFMPEG_UNAVAILABLE` | binary غير موجود على الخادم | نعم بعد إصلاح البيئة |
| `FFMPEG_FAILED` | أمر FFmpeg فشل أثناء المعالجة | حسب السبب |
| `MODEL_MISSING` / `MODEL_INVALID` | نموذج مطلوب غير مثبت أو checksum غير صحيح | نعم بعد إصلاح النموذج |
| `INSUFFICIENT_DISK` | لا توجد مساحة كافية للوسائط المؤقتة أو النتائج | نعم بعد التنظيف/التوسعة |
| `MEDIA_CHECKSUM_MISMATCH` | bytes المرفوعة لا تطابق SHA-256 المتوقع | لا؛ أعد الرفع |
| `ARTIFACT_INVALID` | الناتج موجود لكنه غير صالح أو خارج الجذر المسموح | نعم بعد إعادة الرندر |

## cleanup

تُحذف partial uploads المنتهية صلاحيتها دون لمس source artifacts النهائية. لا يُحذف checkpoint ما لم يكن artifact المرتبط به غير صالحًا أو يطلب المستخدم تنظيف job. يجب أن تكون عمليات cleanup آمنة عند تكرارها.
