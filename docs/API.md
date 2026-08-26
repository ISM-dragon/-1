# API Reference — Private Processing Gateway

**الإصدار:** `/v1`.

**الجمهور:** Android personal client وعميل smoke/E2E، وليس API عامًا متعدد المستخدمين.

## الحدود والمصادقة

يستخدم العميل `Authorization: Bearer <gateway-token>` مع `X-Request-ID` اختياريًا و`X-Device-ID` عند تفعيل device binding. لا تُرسل مفاتيح Gemini أو AssemblyAI أو أي provider إلى Android. يجب تشغيل Gateway خلف HTTPS أو شبكة خاصة؛ قيمة `localhost` مخصصة للاختبار المحلي فقط.

| المجموعة | المسارات الأساسية | الغرض |
|---|---|---|
| Health | `GET /health`, `GET /ready`, `GET /v1/diagnostics` | فحص الخدمة والـruntime والمزودات. |
| Upload | `POST /v1/sources/uploads`, `PUT /v1/sources/uploads/{id}`, `POST /v1/sources/uploads/{id}/complete` | رفع resumable مع offset وSHA-256. |
| Processing | `POST /v1/processing/jobs`, `GET /v1/processing/jobs/{id}` | إنشاء job idempotently وقراءة الحالة. |
| Controls | `POST /v1/processing/jobs/{id}/cancel`, `/retry`, `/resume` | إلغاء، إعادة محاولة، أو استئناف checkpoint صالح. |
| Results | `GET /v1/processing/jobs/{id}/results`, `GET /v1/processing/jobs/{id}/media/{filename}` | نتائج آمنة وتنزيل artifacts بعد التحقق. |

## إنشاء job

يرسل العميل مصدرًا مكتملًا وخيارات المعالجة و`idempotency_key`. تعيد الخدمة job id وحالة `QUEUED` أو نتيجة reused عند تكرار المفتاح. القيم الحالية لـ`llm` هي `gemini` و`ollama`، والقيم الحالية لـ`mode` هي `fast`, `balanced`, `quality`, و`maximum`.

```json
{
  "source": "upl_01H...",
  "llm": "gemini",
  "mode": "balanced",
  "captions": "classic",
  "idempotency_key": "android-device-01-upl-01H..."
}
```

## الحالة والنتائج

الحالة server-side durable، بينما يعرض Android snapshot محليًا عند انقطاع الشبكة. الحالة التوافقية هي `queued`, `running`, `done`, `failed`، مع حالات تفصيلية مثل `CANCELLED`, `INTERRUPTED`, و`RETRY_WAIT`. كل response يجب أن يضم `request_id` و`correlation_id` عندما تكون الخدمة جاهزة لذلك.

```json
{
  "job_id": "proc_01H...",
  "status": "running",
  "state": "DIARIZING",
  "stage": "diarize",
  "fraction": 0.42,
  "message": "Processing audio",
  "retry_count": 0,
  "recoverable": true,
  "results": null
}
```

لا يخرج filesystem path داخلي في response النهائي. يعيد Gateway `media_url` محميًا أو مسارًا نسبيًا يُطلب بنفس Bearer، ويتحقق من وجود الملف، ونوعه، وحجمه، وSHA-256 قبل إتاحته.

## Error envelope

```json
{
  "error": {
    "code": "PIPELINE_UNAVAILABLE",
    "message": "The processing engine is not ready.",
    "request_id": "req_01H...",
    "correlation_id": "cor_01H...",
    "retryable": true
  }
}
```

| HTTP | أمثلة code | سلوك Android |
|---:|---|---|
| 400/422 | `VALIDATION_ERROR`, `MEDIA_INVALID`, `UNSUPPORTED_FORMAT` | عرض رسالة قابلة للإصلاح دون retry تلقائي. |
| 401/403 | `UNAUTHORIZED`, `DEVICE_REQUIRED` | إيقاف الطلب وطلب تصحيح إعداد Gateway. |
| 404 | `JOB_NOT_FOUND`, `MEDIA_NOT_FOUND` | تحديث Room وحذف المرجع غير الصالح. |
| 409 | `OFFSET_MISMATCH`, `JOB_BUSY`, `JOB_NOT_RESUMABLE` | قراءة الحالة الحالية ثم متابعة المسار المناسب. |
| 413 | `UPLOAD_TOO_LARGE` | إظهار policy الحد الأقصى. |
| 429 | `WORKER_BUSY`, `RATE_LIMITED` | احترام backoff و`Retry-After`. |
| 500–504 | `PIPELINE_FAILED`, `PROVIDER_UNAVAILABLE`, `STORAGE_UNAVAILABLE` | retry/resume فقط عندما يعلن `retryable=true`. |

### المراجع

[1]: CONTRACTS.md "Detailed contract shapes"
[2]: ../gateway/main.py "Gateway routes"
[3]: ../gateway/job_state.py "Durable job state"
[4]: ../android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt "Android HTTP client"
[5]: ../docs/FINAL_ACCEPTANCE.md "Acceptance evidence"

## References

[1]: CONTRACTS.md "Detailed contract shapes"
[2]: ../gateway/main.py "Gateway routes"
[3]: ../gateway/job_state.py "Durable job state"
[4]: ../android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt "Android HTTP client"
[5]: FINAL_ACCEPTANCE.md "Acceptance evidence"
