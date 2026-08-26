# Backend Handoff

## ملخص التسليم

تم تنفيذ **Private Backend** مستقل داخل `backend/` لخدمة شخصية على جهاز Android واحد. لا يحتوي النطاق على `users` أو billing أو subscriptions أو multi-tenancy، ولا ينشئ حسابات أو tenants أو سجلات اشتراك. مصدر الحقيقة المحلي هو SQLite للـjobs والـuploads وربط الجهاز، والملفات محصورة تحت storage root مضبوط.

تمت مراجعة `ENGINE_HANDOFF.md` الموجود في المستودع. التكامل الافتراضي الآن يمر عبر الواجهة العامة `publikclip_pipeline.engine.PipelineEngine` ويدعم عقد `ProcessingEngine` فقط. لا يستورد backend وحدات مراحل pipeline أو queue أو renderer مباشرة، ولا يحتاج Android إلى Python runtime. يبقى CLI adapter متاحًا فقط عندما يُضبط `PRIVATE_BACKEND_ENGINE_BIN` صراحةً كخيار legacy.

## الملفات والمسؤوليات

| الملف | المسؤولية |
|---|---|
| `backend/app.py` | FastAPI application، المصادقة، request IDs، validation، error envelope، routes، streaming upload، وتنزيل artifacts. |
| `backend/db.py` | SQLite durable store لجداول `device_binding`, `uploads`, و`jobs` فقط، مع transitions للإلغاء والاستئناف. |
| `backend/storage.py` | توليد IDs opaque، basename sanitization، حدود upload، وpath containment عند قراءة artifacts. |
| `backend/engine.py` | `Engine` boundary، `FacadePublikclipEngine` للواجهة العامة، وlegacy subprocess/unavailable adapters. |
| `backend/service.py` | worker lifecycle، استعادة الوظائف بعد restart، progress، cancel، resume، وتجميع نتائج وclips. |
| `backend/tests/test_api.py` | اختبارات integration لتدفق upload → job → polling → results → render → download والمصادقة. |
| `backend/tests/test_backend_contract.py` | اختبار تحويل public Engine Facade للأحداث والنتائج ومنع تسريب رسائل الاستثناء. |
| `backend/tests/test_persistence.py` | اختبارات SQLite للإلغاء، checkpoint availability، restart interruption، ورفض الحقول غير المعروفة. |
| `docs/API.md` | العقد التنفيذي الكامل الموجه إلى Android agent. |

## API المسلم

المسارات هي root paths كما هي أدناه؛ لا يضيف هذا التطبيق `/v1`:

| Method | Path | Success | الغرض |
|---|---|---:|---|
| `GET` | `/health` | `200` | readiness مختصر، لا يتطلب auth. |
| `POST` | `/uploads` | `201` | raw video upload، وليس multipart. |
| `POST` | `/jobs` | `202` أو `200` عند idempotency reuse | إنشاء أو إعادة استخدام job. |
| `GET` | `/jobs` | `200` | قائمة jobs وcursor. |
| `GET` | `/jobs/{id}` | `200` | الحالة الحالية والتقدم والأخطاء. |
| `POST` | `/jobs/{id}/cancel` | `200` | إلغاء durable وإشارة للworker. |
| `POST` | `/jobs/{id}/resume` | `200` | إعادة استخدام checkpoint مع نفس job identity. |
| `GET` | `/jobs/{id}/results` | `200` | النتائج بعد `COMPLETED`. |
| `GET` | `/jobs/{id}/clips` | `200` | clips وdownload URLs بعد الاكتمال. |
| `POST` | `/jobs/{id}/clips/{clip}/render` | `200` | إعادة render لمقطع موجود. |
| `GET` | `/jobs/{id}/clips/{clip}/download` | `200` | تنزيل MP4 محليًا بعد containment check. |

العقد التفصيلي، أمثلة JSON، جميع الحالات والأكواد موجود في [`docs/API.md`](docs/API.md). كل endpoint خاص يتطلب:

```http
Authorization: Bearer <PRIVATE_BACKEND_TOKEN>
X-Device-ID: <stable-device-id>
```

يرسل Android `X-Request-ID` اختياريًا، ويستلم `X-Request-ID` في كل response. لا تُرسل الأسرار في URL ولا تظهر في response أو logs.

## Job lifecycle

يُنشئ backend row durable في SQLite قبل إرسال الوظيفة إلى worker. الحالة الخارجية المبسطة هي `queued`, `running`, `completed`, `failed`, `cancelled`, أو `interrupted`، بينما `state` يعرض المرحلة canonical مثل `TRANSCRIBING` أو `RENDERING`. قيمة `progress` بين `0.0` و`1.0` وتأتي من Engine Facade أو من الحالة المحفوظة، ولا يصنع Android progress بديلًا.

عند restart تتحول الوظائف التي كانت `queued` أو `running` إلى `INTERRUPTED`. لا يُعلن `resume_available` إلا إذا كانت هوية المحرك `engine_job_id` موجودة. عند الإلغاء تُثبت الحالة `CANCELLED` قبل إشارة worker؛ وإذا كان worker ما يزال يتوقف، يعيد resume مؤقتًا `409 JOB_BUSY` بدل خلق سباق بين محاولتين. بعد التوقف يمكن لـAndroid طلب resume، مع بقاء `job_id` ثابتًا.

## Engine Facade boundary

الحد الوحيد بين application shell والمحرك هو `backend.engine.Engine`. المحول الافتراضي يستدعي:

```python
from publikclip_pipeline.engine import PipelineEngine
```

ثم يستخدم فقط `create_job`, `start_job`, `get_job_status`, `progress`, `cancel_job`, `resume_job`, `results`, و`render_clip` من العقد العام. يحول `ProgressEvent` إلى `EngineEvent` ويحّول `JobResults` و`ClipResult` إلى JSON-shaped payloads. الاستثناءات تتحول إلى `EngineError` بأكواد ثابتة مثل `JOB_NOT_FOUND`, `JOB_BUSY`, `JOB_CANCELLED`, `CLIP_NOT_FOUND`, `CLIP_RENDER_FAILED`, و`ENGINE_FAILED`.

يستخدم backend `PRIVATE_BACKEND_STORAGE` كـ`PUBLIKCLIP_HOME`، لذلك توجد checkpoints المحرك تحت storage root نفسه ولكن في مجلدات job opaque. لا تُعرض absolute paths في API. لا ينبغي تعديل `pipeline/` لتلبية احتياج HTTP؛ أي تغيير في عقد المحرك يجب أن يمر من واجهة `pipeline/publikclip_pipeline/engine/__init__.py` ثم adapter.

## Safe file handling

رفع الفيديو يتم streaming مع حد `PRIVATE_BACKEND_MAX_UPLOAD_BYTES`. اسم العميل يختزل إلى basename ويُحفظ الملف داخليًا باسم `source.<safe-extension>`. مراجع clip لا تُستخدم مباشرة كمسارات؛ الخادم يحلها ثم يتأكد أن الملف موجود، MP4، وداخل مجلد job المصرح به. URL المصدر العام يخضع للتحقق من scheme وuserinfo وDNS addresses، وتُرفض loopback وprivate وlink-local وreserved وmulticast.

## Error and logging contract

كل خطأ منظم له الشكل:

```json
{
  "error": {
    "code": "JOB_NOT_FOUND",
    "message": "Job not found",
    "request_id": "req_...",
    "retryable": false
  }
}
```

لا يعرض الخادم tracebacks أو مسارات filesystem أو authorization headers. رسائل engine تُقص وتُنظف من path وtoken-like values قبل التخزين أو الإرسال. logging يسجل method/path/status/request ID، ويستخدم `job_id` وerror code في سجلات دورة الوظيفة من دون source URLs أو tokens.

## تشغيل محلي

من جذر المستودع:

```bash
export PRIVATE_BACKEND_TOKEN='random-long-private-token'
export PRIVATE_BACKEND_DEVICE_ID='android-device-identifier'
uvicorn backend.app:app --host 0.0.0.0 --port 8788
```

يمكن ضبط `PRIVATE_BACKEND_ROOT`, `PRIVATE_BACKEND_DB`, `PRIVATE_BACKEND_STORAGE`, `PRIVATE_BACKEND_MAX_UPLOAD_BYTES`, و`PRIVATE_BACKEND_PIPELINE_DIR`. يضبط `PRIVATE_BACKEND_ENGINE_BIN` فقط عند الحاجة إلى CLI legacy. الوصول من Android خارج localhost يجب أن يكون عبر HTTPS أو reverse proxy موثوق. `PRIVATE_BACKEND_ALLOW_INSECURE_LOCAL=true` للاختبارات المحلية فقط.

## Verification

تم التحقق بالأوامر التالية:

```bash
python3 -m compileall -q backend
git diff --check
PYTHONPATH=. pytest -q backend/tests
PYTHONPATH=pipeline pytest -q pipeline/tests/test_engine.py pipeline/tests/test_queue.py
```

نتيجة الاختبارات الحالية قبل commit: **12 backend tests passed** و**15 pipeline engine/queue tests passed**. ظهرت warning توافق من `starlette.testclient` حول httpx، لكنها لا تفشل الاختبارات ولا تخص عقد التطبيق.

## حدود التكامل مع Android

الـAndroid agent مسؤول عن حفظ `job_id` قبل polling، استخدام نفس idempotency key عند timeout، إرسال token وdevice ID في كل طلب خاص، والتعامل مع `409` كحالة منطقية. يجب إيقاف polling بعد تأكيد `CANCELLED` أو `COMPLETED`. عند `FAILED` أو `INTERRUPTED` لا يظهر زر resume إلا إذا كان `resume_available == true`.

يوجد في المستودع Gateway أقدم بعقد `/v1/...` وعميل Android حالي مرتبط به. هذا التسليم لا يضيف proxy ولا يغير `android/` أو `gateway/`. قبل ربط Android بهذا backend يجب أن يختار الدمج صراحةً أحد المسارين: اعتماد root API الموثق هنا وتحديث عميل Android، أو إضافة proxy مستقل متوافق؛ لا تخلط الصيغتين في العميل نفسه.

## مراجع داخلية

[1]: docs/MASTER-ARCHITECTURE.md "ISM Master Architecture"
[2]: ENGINE_HANDOFF.md "PUBLIKCLIP ENGINE Handoff"
[3]: docs/API-CONTRACT.md "Existing ISM API Contract"
[4]: docs/API.md "Private Backend API"
