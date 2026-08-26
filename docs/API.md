# Private Gateway API

## Boundary

الـGateway هو الواجهة الوحيدة التي يستهلكها Android في المسار الإنتاجي. Android لا يستورد Python modules ولا يقرأ checkpoint files مباشرة. المصادقة Bearer token، والإنتاج يتطلب HTTPS.

## Endpoints الأساسية

| method | path | الوظيفة |
|---|---|---|
| `GET` | `/health` | فحص خدمة Gateway |
| `GET` | `/v1/capabilities` | التحقق من pipeline/FFmpeg/providers |
| `POST` | `/v1/sources/upload` | رفع source media |
| `POST` | `/v1/processing/jobs` | إنشاء job مع idempotency key |
| `GET` | `/v1/processing/jobs/{id}` | قراءة state/progress/stage/results |
| `POST` | `/v1/processing/jobs/{id}/cancel` | طلب الإلغاء |
| `POST` | `/v1/processing/jobs/{id}/retry` | إعادة محاولة recoverable job |
| `POST` | `/v1/processing/jobs/{id}/resume` | استئناف checkpoint job |
| `GET` | artifact URL | تنزيل MP4 بعد authorization |

## Job resource

يجب أن يوضح الرد `id` و`state` و`status` legacy عند الحاجة و`progress/fraction` و`stage` و`message` و`retry_count` و`error_code` و`recoverable`. الحالات الأساسية هي `QUEUED` و`RUNNING`/stages التشغيلية و`COMPLETED` و`FAILED` و`CANCELLED` و`INTERRUPTED`.

## Error envelope

العقد المستهدف هو:

```json
{
  "error": {
    "code": "MEDIA_INVALID",
    "message": "Readable explanation",
    "request_id": "request-id",
    "retryable": false
  }
}
```

يجب الحفاظ على HTTP status والتوافق مع `detail` القديم أثناء migration. لا تُعاد secrets أو stack traces إلى Android.

## Idempotency وresume

Android يحتفظ بـ`remoteGatewayJobId` في Room. بعد retry أو process death يجب استخدام المعرف نفسه عندما يكون recoverable بدل upload/create جديد. إذا أُلغي job عمدًا، يسمح retry الصريح ببدء job جديد وفق سياسة المنتج. Gateway يبقى authoritative للحالة والـcheckpoint.
