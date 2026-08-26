# ISM / PublikClip — Contracts

**الحالة:** عقد مبدئي لمسار APK الشخصي.
**الإصدار:** `/v1`، مع `engine_contract_version=1`.
**قاعدة النطاق:** هذا ليس user-account API؛ bearer token وdevice binding هما حماية Gateway خاص، لا حسابات مستخدمين ولا multi-tenancy.

## 1. حدود العقد

العقد العام بين Android وGateway هو JSON للموارد، stream للوسائط، وHTTPS خارج LAN الخاص في debug. Android لا يعتمد على ملفات `checkpoint` أو أسماء Python classes. Gateway هو adapter الوحيد الذي يعرف storage paths وEngine invocation. Engine contract يبقى plain JSON-shaped records حتى يمكن استدعاؤه من Gateway أو CLI أو desktop shell [1] [2].

| الحد | العقد | المصدر الموثوق |
|---|---|---|
| Android → Gateway | `/v1` HTTP API، Bearer، upload/status/results/control | هذا الملف و`gateway/main.py` |
| Gateway → Engine | `ProcessingEngine` v1 أو JSONL compatibility أثناء الانتقال | `pipeline/publikclip_pipeline/engine/contracts.py` |
| Engine → Runtime | مراحل pipeline وcheckpoints وartifacts | `pipeline/publikclip_pipeline/jobs/queue.py` |
| Gateway → Android | نتائج آمنة وروابط media موقعة/محمية، دون secrets أو filesystem paths | Gateway response adapter |

## 2. المصادقة

كل route خاص يستخدم:

```http
Authorization: Bearer <gateway-session-token>
X-Device-ID: <stable-device-id>   # مستحسن في deployment الشخصي
X-Request-ID: <client-request-id>  # اختياري؛ ينشئ Gateway قيمة عند غيابه
```

لا يوجد endpoint لإنشاء user account أو billing. token يولده مالك Gateway ويُخزن في Android عبر secure storage؛ Gemini وprovider OAuth secrets تبقى في Gateway/AI runtime. يمكن إضافة `X-Device-ID` كقيد جهاز واحد، لكن لا يُحوّل ذلك إلى نظام مستخدمين. في production يجب أن يكون غياب `GATEWAY_TOKEN` configuration failure، لا anonymous access [3] [4].

## 3. Error envelope

يجب أن يكون الخطأ الخارجي موحدًا، حتى لو احتفظت endpoints القديمة بـHTTP status نفسه:

```json
{
  "error": {
    "code": "PIPELINE_UNAVAILABLE",
    "message": "The processing engine is not ready.",
    "request_id": "req_abc",
    "correlation_id": "cor_def",
    "retryable": true
  }
}
```

| HTTP | `code` أمثلة | `retryable` | معنى العميل |
|---:|---|---|---|
| 400/422 | `VALIDATION_ERROR`, `INVALID_SOURCE`, `INVALID_UPLOAD_RANGE` | false | أصلح payload أو المصدر |
| 401/403 | `UNAUTHORIZED`, `DEVICE_REQUIRED`, `DEVICE_NOT_ALLOWED` | false | لا تعاود بلا تغيير auth/config |
| 404 | `JOB_NOT_FOUND`, `MEDIA_NOT_FOUND` | false | المورد غير موجود أو انتهت صلاحيته |
| 409 | `OFFSET_MISMATCH`, `JOB_NOT_RESUMABLE`, `JOB_NOT_CANCELLABLE` | false | استخدم الحالة الحالية أو ابدأ resource صحيحًا |
| 413 | `UPLOAD_TOO_LARGE` | false | صغّر الملف أو غيّر policy |
| 429 | `WORKER_BUSY`, `RATE_LIMITED` | true | backoff واحترم retry-after |
| 500/502/503/504 | `PIPELINE_FAILED`, `PROVIDER_UNAVAILABLE`, `STORAGE_UNAVAILABLE` | حسب payload | اعرض retry/resume إن كان مسموحًا |

الوضع الحالي في Gateway لا يطبق هذا envelope على كل `HTTPException`؛ لذلك هذا **target contract** وليس ادعاءً بأن كل endpoint يطابقه الآن. Backend البديل يملك exception handler أقرب إلى هذا الشكل [5]، ويجب نقل الفكرة إلى Gateway دون إنشاء API ثانٍ.

## 4. Upload contract

### 4.1 بدء جلسة upload

```http
POST /v1/sources/uploads
Content-Type: application/json
```

```json
{
  "filename": "podcast.mp4",
  "bytes": 734003200,
  "sha256": "<64 lowercase hex characters>"
}
```

الاستجابة:

```json
{
  "id": "upl_xxx",
  "status": "uploading",
  "offset": 0,
  "progress": 0.0,
  "expected_bytes": 734003200,
  "expected_sha256": "<sha256>",
  "chunk_bytes": 16777216,
  "expires_in_seconds": 86400
}
```

إذا وجد Gateway artifact أو session مكتملة بنفس `(bytes, sha256)` يعيد المورد مع `reused=true`. لا تُرسل أسماء الملفات غير الآمنة؛ يقبل Gateway video containers المسموحة فقط.

### 4.2 إرسال chunk واستئنافها

```http
PUT /v1/sources/uploads/{upload_id}
Content-Type: application/octet-stream
X-Upload-Offset: 0
```

أو:

```http
Content-Range: bytes 0-16777215/734003200
```

الاستجابة تعيد `offset` و`progress`. يجب أن يساوي بداية chunk الـoffset المثبت في Gateway؛ mismatch يعيد `409 OFFSET_MISMATCH`. بعد interruption يقرأ Android status ثم يعاود من offset، ولا يفترض أن الخادم يقبل bytes مكررة.

### 4.3 الإنهاء

```http
POST /v1/sources/uploads/{upload_id}/complete
```

```json
{
  "id": "upl_xxx",
  "status": "done",
  "source": "/v1/sources/jobs/upl_xxx/media/source.mp4",
  "integrity": {
    "algorithm": "sha256",
    "sha256": "<sha256>",
    "bytes": 734003200
  }
}
```

لا ينشئ Gateway processing job تلقائيًا؛ إنشاء job خطوة مستقلة idempotent. endpoint `/v1/sources/upload` one-shot يبقى compatibility مؤقتًا، لكن Android canonical يجب أن ينتقل إلى session contract حتى لا يفقد upload طويلًا عند انقطاع الشبكة [3] [6].

## 5. Processing job contract

### 5.1 الإنشاء

```http
POST /v1/processing/jobs
Content-Type: application/json
```

```json
{
  "source": "https://private-gateway.example/v1/sources/jobs/upl_xxx/media/source.mp4",
  "llm": "gemini",
  "captions": "classic",
  "mode": "balanced",
  "idempotency_key": "android-device-2026-08-26-upload-upl_xxx"
}
```

القيم المسموحة حاليًا لـ`llm` هي `gemini` و`ollama`، ولـ`mode` هي `fast`, `balanced`, `quality`, `maximum`. عند اختيار Gemini لا يرسل Android مفتاحًا؛ readiness/diagnostic في Gateway يقرر إن كان المسار قابلًا للبدء.

استجابة قبول جديدة:

```json
{
  "id": "proc_xxx",
  "job_id": "proc_xxx",
  "status": "queued",
  "state": "QUEUED",
  "correlation_id": "cor_xxx",
  "request_id": "req_xxx",
  "contract_version": 1
}
```

إعادة نفس `idempotency_key` تعيد job الأصلي مع `reused=true` ولا تنشئ تشغيلًا ثانيًا.

### 5.2 الحالة والـprogress

```http
GET /v1/processing/jobs/{job_id}
```

```json
{
  "id": "proc_xxx",
  "status": "running",
  "state": "DIARIZING",
  "stage": "diarize",
  "fraction": 0.42,
  "message": "Embedding speech windows…",
  "retry_count": 0,
  "recoverable": true,
  "cancel_requested": false,
  "correlation_id": "cor_xxx",
  "results": null,
  "transitions": []
}
```

الحالة canonical هي:

```text
QUEUED → PREPARING → DOWNLOADING → INGESTING → TRANSCRIBING
→ DIARIZING → ANALYZING → CANDIDATES_READY → SCORING → EDITING
→ RENDERING → FINALIZING → COMPLETED
```

والحالات الطرفية/الاسترداد هي `FAILED`, `CANCELLED`, `RETRY_WAIT`, `INTERRUPTED`. يبقى `status` التوافقي `queued`, `running`, `done`, `failed` للعميل القديم. Android لا يخلق progress وهميًا؛ يعرض آخر حالة server مع offline/reconnecting واضح.

### 5.3 التحكم

```http
POST /v1/processing/jobs/{job_id}/cancel
POST /v1/processing/jobs/{job_id}/retry
POST /v1/processing/jobs/{job_id}/resume
```

`cancel` يثبت marker قبل محاولة terminate العملية. `resume` مسموح لـ`INTERRUPTED` أو failure قابل للاسترداد ويعيد استخدام Engine checkpoint؛ لا يعيد `CANCELLED` تلقائيًا. `retry` يحده `MAX_RETRY_COUNT`. كل انتقال مهم يجب أن يسجل `from_state`, `to_state`, `stage`, `fraction`, `message`, `error_code`, وtimestamp.

## 6. Results and artifacts

لا يعيد Gateway مسار filesystem محليًا للعميل. يستبدل `path` الداخلي برابط media محمي أو URL نسبي قابل للطلب بنفس Bearer:

```json
{
  "job_id": "proc_xxx",
  "results": {
    "render": {
      "captions_burned": true,
      "caption_preset": "classic",
      "outputs": [
        {
          "clip": 0,
          "filename": "clip_00.mp4",
          "media_url": "/v1/processing/jobs/proc_xxx/media/clip_00.mp4",
          "bytes": 18432000,
          "sha256": "<sha256>",
          "duration": 31.4,
          "integrity": "verified"
        }
      ]
    }
  }
}
```

قبل النشر في response يتحقق Gateway من وجود الملف، امتداد MP4، الحجم، وintegrity. Android ينزّل إلى app-private storage، يتحقق من non-zero bytes، ثم يستورد إلى Room/cache. مدة صلاحية URL، إن كانت signed، تُذكر صراحة؛ لا تُحفظ روابط public دائمة بلا حاجة.

## 7. Engine contract

المستهلكون البرمجيون يعتمدون على `ProcessingEngine` لا على stage modules. الدوال الحالية هي:

```python
job = engine.create_job(source, settings=None, source_type=None)
status = engine.get_job_status(job.id)
result = engine.start_job(job.id, on_progress=callback)
clip = engine.get_clip(job.id, 0)
updated = engine.render_clip(job.id, 0, on_progress=callback)
```

النماذج العامة هي `JobRef`, `JobStatus`, `JobResults`, `ClipResult`, `ProgressEvent`, و`EngineError`. `EngineError` يملك `code`, `safe_message`, و`recoverable`; لا تعبر stack traces أو secrets الحد العام [1] [2].

## 8. Compatibility وmigration rules

تبقى `/v1/sources/upload` وlegacy social routes مؤقتًا حتى ينتقل Android والواجهة، لكن لا تُستخدم في تصميم feature جديد. لا يقرأ Android `pipeline_job_id` أو checkpoint names. لا يرسل Gateway إلى Engine provider OAuth state. لا يضاف endpoint خاص بـuser account أو subscription؛ أي state جديد يجب أن يكون متعلقًا بجهاز/مصدر/job/artifact شخصي.

### المراجع

[1]: ../pipeline/publikclip_pipeline/engine/contracts.py "Public Engine contract v1"
[2]: ../pipeline/publikclip_pipeline/engine/pipeline.py "PipelineEngine implementation"
[3]: ../gateway/main.py "Gateway routes and current upload/processing behavior"
[4]: ../gateway/secret_vault.py "Gateway-owned secret storage"
[5]: ../backend/app.py "Alternative backend error envelope and auth behavior"
[6]: ../android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt "Current Android remote client"
