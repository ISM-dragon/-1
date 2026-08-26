# Private Backend API

**الإصدار:** `1`

**النطاق:** خدمة شخصية لجهاز Android واحد

**Base URL:** عنوان الخدمة الذي يضبطه المستخدم، من دون prefix إضافي مثل `/v1` في هذا الـbackend

> هذا العقد مخصص لتطبيق شخصي خاص. لا توجد فيه حسابات مستخدمين أو billing أو subscriptions أو multi-tenancy. لا يحتاج Android إلى Python أو pipeline runtime؛ يتعامل فقط مع HTTP وJSON وملفات MP4.

## 1. المصادقة وهوية الجهاز

باستثناء `GET /health`، يجب أن يرسل كل طلب:

```http
Authorization: Bearer <PRIVATE_BACKEND_TOKEN>
X-Device-ID: <stable-device-id>
X-Request-ID: <optional-client-correlation-id>
```

يضبط الخادم `PRIVATE_BACKEND_TOKEN` و`PRIVATE_BACKEND_DEVICE_ID` اختياريًا. عند غياب `PRIVATE_BACKEND_DEVICE_ID` يربط الخادم أول `X-Device-ID` ناجح ببصمة SHA-256 محفوظة في SQLite، ولا يحفظ القيمة الخام. أي جهاز آخر يحصل على `403 DEVICE_MISMATCH`. لا تُرسل قيمة token في query parameters ولا تُكتب في logs.

كل response يعيد `X-Request-ID`. عند عدم إرسال `X-Request-ID` ينشئ الخادم قيمة مثل `req_<opaque-value>`. يمكن استخدام هذه القيمة لربط طلب Android مع سجل الخادم.

## 2. صيغة الخطأ

جميع أخطاء HTTP المنظمة تستخدم envelope ثابتًا:

```json
{
  "error": {
    "code": "JOB_NOT_FOUND",
    "message": "Job not found",
    "request_id": "req_abc123",
    "retryable": false
  }
}
```

يجب أن يتعامل Android مع `code` لا مع نص `message`. رسائل الخادم آمنة للعرض لكنها ليست عقد ترجمة. أخطاء `401` و`403` هي أخطاء مصادقة أو جهاز، وأخطاء `409` هي حالات منطقية للوظيفة وليست أخطاء شبكة.

| HTTP | المعنى في Android |
|---:|---|
| `400` | المدخل أو مرجع الملف غير صالح؛ لا تعاود الإرسال دون تصحيح الطلب. |
| `401` | token أو `X-Device-ID` مفقود/غير صحيح. |
| `403` | الجهاز الحالي ليس الجهاز المرتبط بالخدمة. |
| `404` | المورد أو الوظيفة أو المقطع غير موجود. |
| `409` | انتقال الحالة غير مسموح حاليًا، مثل results قبل الاكتمال أو resume بلا checkpoint. |
| `413` | الملف تجاوز حد الرفع. |
| `422` | JSON أو source أو query parameters غير صالحة. |
| `429` | worker مشغول؛ استخدم backoff. |
| `500` | خطأ داخلي منظم؛ لا يعرض الخادم traceback. |
| `502/503/504` | المحرك أو الخدمة غير متاحة مؤقتًا؛ افحص `retryable`. |

## 3. رفع الفيديو

### `POST /uploads`

يقبل الخادم **raw request body** للفيديو، وليس multipart. أرسل `Content-Type` واسمًا اختياريًا في `X-Filename`.

```http
POST /uploads HTTP/1.1
Authorization: Bearer <token>
X-Device-ID: android-device-001
X-Filename: source.mp4
Content-Type: video/mp4
Content-Length: 1048576

<raw video bytes>
```

الاستجابة الناجحة هي `201 Created`:

```json
{
  "id": "upl_xxx",
  "filename": "source.mp4",
  "content_type": "video/mp4",
  "bytes": 1048576,
  "path": null,
  "source": "upload:upl_xxx",
  "created_at": "2026-08-26T12:00:00+00:00"
}
```

يجب على Android حفظ `id` أو `source` فقط. قيمة `path` دائمًا `null` في النقل حتى لا تنكشف مسارات filesystem. يختزل الخادم اسم الملف إلى basename ويحفظ الملف فعليًا باسم داخلي ثابت بامتداد فيديو مسموح، ويتحقق من الحجم أثناء streaming. الامتدادات المقبولة هي `.mp4`, `.mov`, `.m4v`, `.webm`, `.mkv`, و`.avi`؛ الامتداد غير المقبول يتحول إلى `.mp4` داخليًا.

الأكواد المتوقعة هي `EMPTY_UPLOAD` للطلب الفارغ، و`UPLOAD_TOO_LARGE` للملف الذي يتجاوز `PRIVATE_BACKEND_MAX_UPLOAD_BYTES`، و`INVALID_FILE` لمرجع ملف غير صالح.

## 4. إنشاء الوظيفة

### `POST /jobs`

يجب تقديم **مصدر واحد فقط**: `upload_id` أو `source`. يقبل الخادم URL عام HTTP/HTTPS فقط عند استخدام `source`؛ تُرفض loopback وprivate وlink-local وreserved وmulticast وعناوين URL التي تتضمن userinfo.

```json
{
  "upload_id": "upl_xxx",
  "options": {
    "mode": "balanced",
    "llm": "gemini",
    "captions": "classic"
  },
  "idempotency_key": "android-installation-20260826-video-001"
}
```

أو:

```json
{
  "source": "https://public.example/video.mp4",
  "options": {},
  "idempotency_key": "android-installation-20260826-video-002"
}
```

يفضل إرسال `Idempotency-Key` كـHTTP header؛ إذا وُجد header فهو مقدم على قيمة JSON:

```http
Idempotency-Key: android-installation-20260826-video-001
```

الاستجابة الجديدة هي `202 Accepted`، والاستجابة المعادة بسبب idempotency هي `200 OK` مع `reused: true`:

```json
{
  "id": "job_xxx",
  "status": "queued",
  "state": "QUEUED",
  "stage": "queued",
  "progress": 0.0,
  "message": "Job accepted",
  "error_code": null,
  "error_message": null,
  "engine_job_id": null,
  "resume_available": false,
  "cancel_requested": false,
  "options": {"mode": "balanced"},
  "results": null,
  "source": "upload:upl_xxx",
  "source_upload_id": "upl_xxx",
  "created_at": "2026-08-26T12:00:00+00:00",
  "updated_at": "2026-08-26T12:00:00+00:00",
  "reused": false,
  "request_id": "req_abc123"
}
```

يجب أن يحفظ Android `id` قبل بدء polling. عند timeout بعد إرسال الطلب لا تنشئ job جديدًا؛ أعد الطلب بنفس `Idempotency-Key`.

الأكواد المهمة هي `INVALID_JOB_SOURCE`, `INVALID_SOURCE`, `UPLOAD_NOT_FOUND`, `VALIDATION_ERROR`, و`WORKER_BUSY`.

## 5. قراءة الوظائف والتقدم

### `GET /jobs`

Parameters اختيارية:

| Parameter | النوع | الافتراضي | الوصف |
|---|---|---:|---|
| `limit` | integer `1..100` | `50` | عدد الوظائف. |
| `status` | string | — | تصفية مثل `queued`, `running`, `completed`, `failed`, `cancelled`, `interrupted`. |
| `before` | ISO timestamp | — | cursor زمني للصفحة التالية. |

الاستجابة:

```json
{
  "items": [
    {
      "id": "job_xxx",
      "status": "running",
      "state": "TRANSCRIBING",
      "stage": "asr",
      "progress": 0.42,
      "message": "Processing",
      "error_code": null,
      "error_message": null,
      "engine_job_id": "engine_xxx",
      "resume_available": false,
      "cancel_requested": false,
      "options": {},
      "results": null,
      "created_at": "2026-08-26T12:00:00+00:00",
      "updated_at": "2026-08-26T12:01:00+00:00"
    }
  ],
  "next_cursor": null
}
```

### `GET /jobs/{id}`

يعيد نفس مورد الوظيفة. استخدم هذا المسار للـpolling كل `1–3` ثوانٍ، مع backoff عند `429` أو أخطاء مؤقتة. لا يصنع Android progress محليًا؛ اعرض قيمة `progress` التي يرسلها الخادم، وهي بين `0.0` و`1.0`.

## 6. حالات الوظيفة

حقل `state` هو الحالة canonical، وحقل `status` هو projection مبسط مناسب للعميل.

| `state` | `status` | معنى الحالة |
|---|---|---|
| `QUEUED` | `queued` | محفوظة وتنتظر worker. |
| `PREPARING` إلى `FINALIZING` | `running` | التنفيذ في المرحلة الموضحة في `stage`. |
| `COMPLETED` | `completed` | النتائج والـclips جاهزة. |
| `FAILED` | `failed` | فشل المحرك؛ افحص `error_code` و`resume_available`. |
| `CANCELLED` | `cancelled` | أُلغي الطلب بشكل دائم؛ ليس نجاحًا. |
| `INTERRUPTED` | `interrupted` | توقف backend أو worker؛ قد يكون resume متاحًا فقط مع checkpoint. |

القيم الممكنة لـ`state` هي `QUEUED`, `PREPARING`, `DOWNLOADING`, `INGESTING`, `TRANSCRIBING`, `DIARIZING`, `ANALYZING`, `CANDIDATES_READY`, `SCORING`, `EDITING`, `RENDERING`, `FINALIZING`, `COMPLETED`, `FAILED`, `CANCELLED`, و`INTERRUPTED`.

## 7. التحكم في دورة الحياة

### `POST /jobs/{id}/cancel`

يطلب إلغاءً durable قبل إرسال إشارة إلى worker أو المحرك. الاستجابة `200` تعيد الوظيفة بحالة `CANCELLED`. إذا كان للمحرك checkpoint محفوظ يظهر `resume_available: true`؛ وإلا يبقى `false`. لا يمكن إلغاء وظيفة مكتملة، ويعاد `409 JOB_NOT_CANCELLABLE`.

### `POST /jobs/{id}/resume`

يعيد استخدام **نفس `job_id`** وcheckpoint المحرك، ولا ينشئ وظيفة جديدة. النجاح `200` يعيد الوظيفة في `QUEUED` بانتظار worker. لا يُقبل resume إلا إذا كانت الوظيفة `FAILED` أو `INTERRUPTED` أو `CANCELLED`، وكان `resume_available: true` و`engine_job_id` موجودًا.

الأكواد هي `JOB_BUSY` إذا كان worker السابق ما يزال يتوقف، و`JOB_NOT_RESUMABLE` إذا لم يوجد checkpoint صالح، و`CHECKPOINT_NOT_FOUND` إذا كانت هوية المحرك مفقودة.

## 8. النتائج والمقاطع

### `GET /jobs/{id}/results`

بعد `COMPLETED` يعيد:

```json
{
  "job_id": "job_xxx",
  "results": {
    "job_id": "job_xxx",
    "engine_job_id": "engine_xxx",
    "clips": [
      {
        "clip": 0,
        "filename": "clip_00.mp4",
        "bytes": 1234567,
        "download_ready": true,
        "title": "Example clip"
      }
    ]
  },
  "clips_url": "/jobs/job_xxx/clips"
}
```

قبل الاكتمال يعيد `409 RESULTS_NOT_READY` مع `retryable: true`. لا تعتبر `FAILED` أو `CANCELLED` نجاحًا ولا تطلب النتائج كأنها جاهزة.

### `GET /jobs/{id}/clips`

بعد `COMPLETED` يعيد:

```json
{
  "job_id": "job_xxx",
  "items": [
    {
      "clip": 0,
      "filename": "clip_00.mp4",
      "bytes": 1234567,
      "download_ready": true,
      "download_url": "/jobs/job_xxx/clips/0/download"
    }
  ]
}
```

قبل الاكتمال يعيد `409 CLIPS_NOT_READY` مع `retryable: true`. رقم `clip` integer يبدأ من الصفر.

### `POST /jobs/{id}/clips/{clip}/render`

يطلب إعادة render لمقطع موجود عبر Engine Facade. body اختياري:

```json
{"options": {"preset": "vertical"}}
```

الاستجابة `200`:

```json
{
  "job_id": "job_xxx",
  "clip": 0,
  "render": {
    "clip": 0,
    "filename": "clip_00.mp4",
    "bytes": 1234567,
    "download_ready": true
  },
  "download_url": "/jobs/job_xxx/clips/0/download"
}
```

الأكواد هي `JOB_NOT_RENDERABLE`, `CLIP_NOT_FOUND`, `CLIP_RENDER_FAILED`, و`RENDER_FAILED`. لا ينشئ backend ملفًا وهميًا عند غياب المحرك.

### `GET /jobs/{id}/clips/{clip}/download`

ينزل ملف MP4 بعد التحقق من الوظيفة ورقم المقطع واحتواء المسار داخل مجلد job المصرح به. يجب أن يرسل Android نفس headers الخاصة بالمصادقة. لا يحاول Android تركيب filesystem path من `filename`.

## 9. تدفق Android الموصى به

| الترتيب | الإجراء |
|---:|---|
| 1 | احفظ base URL وtoken وdevice ID محليًا بطريقة آمنة، ولا تسجل token. |
| 2 | أرسل الفيديو raw إلى `/uploads`، واحفظ `upload_id`. |
| 3 | أنشئ job عبر `/jobs` مع `Idempotency-Key` ثابت، واحفظ `job_id` فورًا. |
| 4 | استعلم عن `/jobs/{id}` كل 1–3 ثوانٍ واعرض `state`, `stage`, و`progress`. |
| 5 | عند `COMPLETED` اطلب `/results` أو `/clips`. |
| 6 | نزّل كل `download_url` باستخدام Bearer token و`X-Device-ID`. |
| 7 | عند `FAILED` أو `INTERRUPTED` اعرض resume فقط عندما يكون `resume_available == true`. |
| 8 | عند إلغاء المستخدم استدعِ `/cancel` وانتظر `CANCELLED` قبل إنهاء polling. |

## 10. ملاحظات تشغيلية

`GET /health` عام ويعيد readiness مختصرًا:

```json
{
  "ok": true,
  "service": "private-backend",
  "api_version": "1",
  "engine": {"available": true, "message": "publikclip public engine facade is available."},
  "auth_required": true
}
```

لا تعتمد Android على `engine.message` كعقد ثابت. الوصول عن بعد يجب أن يكون عبر HTTPS أو reverse proxy موثوق. يختبر bypass المحلي فقط عبر `PRIVATE_BACKEND_ALLOW_INSECURE_LOCAL=true` في بيئة اختبار محلية.

## المراجع الداخلية

[1]: ./MASTER-ARCHITECTURE.md "ISM Master Architecture"
[2]: ../ENGINE_HANDOFF.md "PUBLIKCLIP ENGINE Handoff"
[3]: ./API-CONTRACT.md "Existing ISM API Contract"
