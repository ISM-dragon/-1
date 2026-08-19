# API v1 Contract

## المبدأ

تطبيق Android لا يشغّل WhisperX أو diarization أو FFmpeg الثقيل محليًا. يرسل المصدر إلى Gateway، ثم يراقب job ويعرض النتائج. كل استجابة completion يجب أن تعكس وجود artifacts فعلية؛ لا يتم إنشاء clip وهمي عند فشل worker.

## Health

```http
GET /api/v1/health
```

يعيد `ok` و`provider_mode` و`auth_configured` وعدد jobs النشطة. بقي `GET /health` متاحًا للتوافق.

## Projects

```http
GET   /api/v1/projects
POST  /api/v1/projects
GET   /api/v1/projects/{project_id}
PATCH /api/v1/projects/{project_id}
POST  /api/v1/projects/{project_id}/process
```

إنشاء المشروع يقبل `name` و`source` اختياريًا. بدء المعالجة يستخدم source المرسل أو source المحفوظ. المشروع ينتقل إلى `queued` ثم `processing`، وبعد انتهاء worker ينتقل إلى `completed` أو `failed` أو `cancelled`.

## Jobs

```http
GET  /api/v1/jobs/{job_id}
POST /api/v1/jobs/{job_id}/cancel
```

الإلغاء يرسل terminate إلى subprocess إن كان موجودًا، ثم يستخدم kill كخطة أخيرة بعد مهلة قصيرة. إذا لم يكن worker قد بدأ بعد، تُلغى المهمة من الحالة `queued`. لا يسمح endpoint بإلغاء job مكتملة أو فاشلة أو ملغاة، ويعيد `409` بدل إعلان نجاح غير حقيقي.

## Compatibility

تبقى مسارات العميل القديم كما هي:

```http
POST /v1/processing/jobs
GET  /v1/processing/jobs/{job_id}
POST /v1/sources/inspect
POST /v1/sources/download
GET  /v1/sources/jobs/{job_id}
```

إضافة `/api/v1` لا تحذف أو تغيّر عقد Desktop الحالي.

## Security

عند ضبط `GATEWAY_TOKEN` يجب إرسال `Authorization: Bearer <token>`. يجب استخدام HTTPS خارج localhost/LAN التطويرية، ولا توضع مفاتيح المزودين في APK أو bundle. وضع `PROVIDER_MODE=mock` يظل محاكاة محلية ولا يمثل نشرًا اجتماعيًا حقيقيًا.
