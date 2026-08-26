# Media Runtime Contract

## المسؤولية

Media Runtime يعمل على private backend فقط. وظيفته فحص المصدر، probing، استخراج الصوت والإطارات، normalization إلى CFR عند الحاجة، transcoding، rendering، وإزالة الملفات المؤقتة بعد نجاح أو فشل job.

## المسار

```text
source upload
  → validation/probe
  → audio extraction
  → frame extraction
  → ASR/analysis artifacts
  → candidate render
  → caption/camera composition
  → MP4 artifact
  → authorized download
```

يُعاد استخدام artifact الموجود في checkpoint صالح، ولا تُعاد معالجة الفيديو كاملًا بعد restart بلا سبب. يجب أن تكون الكتابة atomic وأن تبقى الملفات الجزئية خارج result index.

## تصنيف الأخطاء

| الكود | المعنى | retry |
|---|---|---|
| `MEDIA_INVALID` | الملف تالف أو لا يحتوي stream صالحًا | لا |
| `UNSUPPORTED_FORMAT` | الحاوية أو codec غير مدعوم | لا |
| `FFMPEG_MISSING` | binary أو capability المطلوبة غير متاحة | لا حتى إصلاح البيئة |
| `FFMPEG_FAILED` | فشل أمر FFmpeg أثناء التحويل أو التصيير | حسب السبب |
| `MODEL_MISSING` | model artifact غير مثبت | لا حتى التثبيت |
| `MODEL_INVALID` | checksum/metadata/تحميل النموذج غير صالح | لا حتى الإصلاح |
| `INSUFFICIENT_DISK` | لا توجد مساحة كافية للعمل أو النتيجة | لا حتى التنظيف/التوسعة |

## حدود Android

Android يختار URI ويرفع المصدر ويحفظ النتيجة محليًا للعرض أو التصدير. لا يعتمد APK على FFmpeg server binary أو Python desktop runtime. Media3 وواجهات preview/edit الخفيفة لا تُعامل كبديل عن server renderer.

## Cleanup وintegrity

ينبغي ربط كل source وartifact بـjob ID، منع path traversal، تنظيف orphan uploads بعد retention معلن، وفحص حجم الملف ووجوده قبل إرجاعه للعميل. تُحفظ SHA-256 للـrelease/artifact في evidence عندما يكون ذلك جزءًا من acceptance.
