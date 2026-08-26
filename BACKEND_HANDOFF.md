# BACKEND_HANDOFF

## نطاق التسليم

تم تنفيذ **Private Backend** مستقل داخل `backend/`. الخدمة مخصصة لجهاز Android شخصي واحد، ولا تحتوي على user accounts أو billing أو subscriptions أو multi-tenancy. لم يتم تعديل `android/` أو `app/` أو `pipeline/` أو منطق scoring. كما لم يوجد ملف `ENGINE_HANDOFF.md` في المستودع عند الفحص؛ لذلك تم اعتماد واجهة adapter صريحة بدل إعادة بناء engine.

الملفات المضافة كلها ضمن نطاق backend، باستثناء هذا الملف المطلوب للتسليم:

| الملف | المسؤولية |
|---|---|
| `backend/app.py` | FastAPI app، auth، request IDs، error envelope، وكل المسارات المطلوبة. |
| `backend/db.py` | SQLite durable store لجداول الجهاز والرفع والjobs. |
| `backend/storage.py` | تخزين الفيديو والـartifacts والتحقق من المسارات ومنع path traversal. |
| `backend/engine.py` | Protocol وSubprocess adapter وUnavailable adapter لـpublikclip. |
| `backend/service.py` | worker lifecycle، progress، cancel، resume، وتجميع النتائج. |
| `backend/tests/test_api.py` | API وintegration tests باستخدام Fake Engine. |
| `backend/README.md` | التشغيل والتهيئة المختصرة. |

## API contract

كل المسارات أدناه، باستثناء `GET /health`، تتطلب في وضع الإنتاج:

```http
Authorization: Bearer <PRIVATE_BACKEND_TOKEN>
X-Device-ID: <stable-android-device-id>
```

يحاول backend ربط أول `X-Device-ID` ناجح ببصمة SHA-256 محفوظة محليًا. أي جهاز ثانٍ يواجه `DEVICE_MISMATCH`. لا يتم تخزين device ID الخام.

| Method | Path | Success | Contract |
|---|---|---:|---|
| `GET` | `/health` | `200` | `{ok, service, api_version, engine, auth_required}`. لا يتطلب auth ولا يكشف secrets. |
| `POST` | `/uploads` | `201` | Raw video body، مع `Content-Type` و`X-Filename` اختياري. يعيد `{id, filename, content_type, bytes, source}`. لا يعيد المسار الداخلي. |
| `POST` | `/jobs` | `202` | JSON يحتوي واحدًا فقط من `source` URL عام أو `upload_id`، و`options` اختياري، و`idempotency_key` اختياري أو `Idempotency-Key` header. |
| `GET` | `/jobs` | `200` | `{items, next_cursor}` مع `limit=1..100` و`status` و`before`. |
| `GET` | `/jobs/{id}` | `200` | job durable يتضمن `status`, `state`, `stage`, `progress`, `message`, `error_code`, `error_message`, `engine_job_id`, `resume_available`, `cancel_requested`, `options`, `results`, والتواريخ. |
| `POST` | `/jobs/{id}/cancel` | `200` | يجعل job `CANCELLED` durable ويرسل event إلغاء إلى worker الجاري. لا يمكن إلغاء job مكتمل. |
| `POST` | `/jobs/{id}/resume` | `200` | يعيد job إلى `QUEUED` فقط إذا كان `FAILED` أو `INTERRUPTED` أو `CANCELLED` وله engine checkpoint. |
| `GET` | `/jobs/{id}/results` | `200` | يعيد النتائج بعد `COMPLETED`. قبل ذلك يعيد `409 RESULTS_NOT_READY` مع `retryable=true`. |
| `GET` | `/jobs/{id}/clips` | `200` | `{job_id, items}`؛ كل item يحتوي رقم clip ورابط تنزيل backend. |
| `GET` | `/jobs/{id}/clips/{clip}/download` | `200` | MP4 فقط، بعد التحقق من job والاسم والمسار. |
| `POST` | `/jobs/{id}/clips/{clip}/render` | `200` | يشغّل `edit render-clip` عبر adapter ويعيد artifact الناتج ورابط تنزيله. يمكن إرسال `{options:{...}}` مستقبلًا؛ الخيارات الحالية محفوظة للتوافق ولا تغيّر scoring. |

### الحالات

القيم الأساسية هي `QUEUED`, `PREPARING`, `DOWNLOADING`, `INGESTING`, `TRANSCRIBING`, `DIARIZING`, `ANALYZING`, `CANDIDATES_READY`, `SCORING`, `EDITING`, `RENDERING`, `FINALIZING`, `COMPLETED`, `FAILED`, `CANCELLED`, و`INTERRUPTED`. حقل `status` يستخدم القيم الصغيرة `queued`, `running`, `completed`, `failed`, `cancelled`, و`interrupted` لتسهيل Android.

### الأخطاء

كل خطأ HTTP يعيد الشكل التالي ويضيف `X-Request-ID` إلى response:

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

أكواد مهمة للعميل هي `UNAUTHORIZED`, `DEVICE_REQUIRED`, `DEVICE_MISMATCH`, `UPLOAD_TOO_LARGE`, `EMPTY_UPLOAD`, `INVALID_SOURCE`, `UPLOAD_NOT_FOUND`, `JOB_NOT_FOUND`, `RESULTS_NOT_READY`, `CLIPS_NOT_READY`, `JOB_NOT_RESUMABLE`, `CHECKPOINT_NOT_FOUND`, `JOB_NOT_CANCELLABLE`, `ENGINE_UNAVAILABLE`, `ENGINE_START_FAILED`, `ENGINE_FAILED`, و`RENDER_FAILED`.

## Expected engine interface

الحد الفاصل الوحيد مع engine موجود في `backend/engine.py`:

```python
class Engine(Protocol):
    def available(self) -> tuple[bool, str]: ...

    def run(
        self,
        source: str,
        job_dir: Path,
        options: dict[str, Any],
        resume_engine_job_id: str | None,
        on_event: EventCallback,
        cancel_event: threading.Event,
    ) -> dict[str, Any]: ...

    def render_clip(
        self,
        engine_job_id: str,
        clip: int,
        job_dir: Path,
        on_event: EventCallback,
        cancel_event: threading.Event,
    ) -> dict[str, Any]: ...
```

الـSubprocess adapter الحالي يستدعي CLI الموجود في `pipeline/publikclip_pipeline/cli.py` بهذه الأوامر المنطقية:

```text
publikclip --jsonl run <source> [--llm ...] [--captions ...] [--mode ...]
publikclip --jsonl resume <engine_job_id> [options]
publikclip --jsonl edit render-clip <engine_job_id> <clip>
```

يجب أن يرسل CLI أسطر JSONL من النوع `job` و`progress` و`result`. الحد الأدنى للنتيجة النهائية هو `{ok: true, job_id: "..."}`؛ ويمكن أن تحتوي على `clips` أو نتائج إضافية. الـadapter لا يعيد تنفيذ ingest أو ASR أو diarization أو candidates أو scoring أو editing؛ يمرر الأحداث ويخزن manifest فقط. عند غياب pipeline يُستخدم `UnavailableEngine` ويظهر `ENGINE_UNAVAILABLE` بدل ادعاء أن engine جاهز.

## Android requirements

يُفضّل أن ينفذ Android flow الآتي:

| الخطوة | طلب |
|---|---|
| 1 | احفظ base URL وtoken وdevice ID في إعدادات private محلية آمنة، ولا تضع token في logs أو query parameters. |
| 2 | ارفع الفيديو raw إلى `/uploads`، واحتفظ بـ`id` فقط. أرسل `Content-Length` عندما يمكن ذلك لإظهار upload progress. |
| 3 | أنشئ job عبر `/jobs` مع `upload_id` و`Idempotency-Key` ثابت لكل محاولة منطقية. لا تنشئ job جديدًا عند إعادة إرسال response timeout. |
| 4 | استعلم عن `/jobs/{id}` كل 1–3 ثوانٍ مع backoff عند `429/503`. اعرض `progress` كنسبة بين 0 و1 و`stage` كنص غير قابل للترجمة المباشرة. |
| 5 | عند `COMPLETED` اطلب `/results` أو `/clips`، ثم نزّل MP4 من `download_url` مع نفس auth headers. |
| 6 | عند `FAILED` أو `INTERRUPTED` افحص `resume_available`، ثم اطلب `/resume` فقط عند توفر checkpoint. لا تعتبر أي job `FAILED` قابلًا للاستئناف تلقائيًا. |
| 7 | عند إلغاء المستخدم اطلب `/cancel`، ثم أوقف polling بعد تأكيد `CANCELLED` أو `COMPLETED`. |

كل request يجب أن يرسل `X-Request-ID` اختياريًا لربط logs، وكل response قد يعيد request ID جديدًا. يجب على Android التعامل مع `401` بإعادة التهيئة المحلية، ومع `403 DEVICE_MISMATCH` كحالة backend مقيد بجهاز آخر، ومع `409` كحالة منطقية لا كفشل شبكة.

## Security and operational boundary

الـbackend لا يقبل URL محليًا أو عنوانًا خاصًا عند إنشاء job من `source`، ويمنع traversal عند قراءة upload أو clip. الملفات تحفظ محليًا تحت `PRIVATE_BACKEND_STORAGE`، والـDB تحت `PRIVATE_BACKEND_DB`. لا يتم عرض absolute paths في API. يجب تشغيل الوصول البعيد عبر HTTPS وtoken طويل عشوائي؛ `PRIVATE_BACKEND_ALLOW_INSECURE_LOCAL=true` للاختبارات المحلية فقط.

worker واحد متسلسل افتراضيًا، لكن ThreadPoolExecutor يسمح بتجميع jobs في الذاكرة مع durable rows. عند restart تتحول jobs الجارية إلى `INTERRUPTED` ويصبح resume متاحًا عندما يكون engine قد أصدر checkpoint ID. لا يستخدم backend أي Manus connector أو user account.

## Known issues and dependencies

أولًا، `ENGINE_HANDOFF.md` غير موجود في checkout الذي تم فحصه، لذلك يعتمد adapter على عقد JSONL المستنتجة من CLI الحالي. إذا تغير CLI، يجب تعديل `backend/engine.py` فقط وتحديث هذا الملف.

ثانيًا، لا يوجد اختبار end-to-end حقيقي مع فيديو وFFmpeg ومزود AI؛ الاختبارات الحالية تستخدم Fake Engine عمدًا حتى تكون deterministic ولا ترسل ملفات أو مفاتيح خارجية. يلزم اختبار نشر منفصل عند توفر runtime الخاص بـpipeline وFFmpeg ومفتاح مزود AI.

ثالثًا، رفع الفيديو مصمم كـraw body وليس multipart، لتجنب اعتماد parser إضافي ولتسهيل streaming وقياس الحجم. إذا تطلب Android multipart مستقبلًا، أضف endpoint متوافقًا داخل `backend/app.py` أو adapter رفع منفصل، ولا تغيّر contract الحالي.

رابعًا، `/jobs/{id}/clips/{clip}/render` يعتمد على engine لإعادة render. لا يخترع backend ملفًا عند غياب engine، ويرجع `RENDER_FAILED` أو `ENGINE_UNAVAILABLE`.

خامسًا، توجد خدمة `gateway/` قديمة/موازية بمسارات `/v1/...` في المستودع. لم يتم تعديلها احترامًا لقاعدة الملكية والتزامن. جلسة الدمج يجب أن تختار بوضوح هل Android سيستخدم API الجذر الجديد `/jobs` أم سيضيف proxy مقصودًا؛ لم تتم إضافة proxy لتجنب لمس نطاق جلسة أخرى.

## Verification

تم تشغيل:

```text
python3 -m compileall -q backend
pytest -q backend/tests
4 passed
```

التغيير المقصود في هذه الجلسة هو مجلد `backend/` وملف `BACKEND_HANDOFF.md` فقط. يجب مراجعة `git diff --check` قبل الدمج.
