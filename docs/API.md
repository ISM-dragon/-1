# عقد API الخاص بتطبيق Android

## النطاق

هذا العقد مخصص لتطبيق Android الشخصي. يستخدم Gateway نفسه كـprivate processing service، لكنه يعرّض مسارات `/jobs/*` مختصرة لا تكشف تفاصيل pipeline الداخلية. أما `/v1/processing/*` فتبقى عقدًا داخلية/legacy للتشغيل والاختبارات desktop.

## المصادقة

كل مسار عدا `GET /health` يحتاج `Authorization: Bearer <GATEWAY_TOKEN>`. يجب استخدام HTTPS خارج localhost في الاختبارات، وتخزين الرمز خارج المستودع. لا يضع APK مفتاح Gemini أو مفاتيح مزودي AI.

## الموارد

| الطريقة | المسار | الطلب | الاستجابة |
|---|---|---|---|
| GET | `/health` | بلا جسم | readiness مختصر للخدمة والمحرك. |
| POST | `/jobs` | multipart: `file`, `llm`, `captions`, `mode`, `idempotency_key`؛ أو `source_url` | job id وحالة أولية وprogress. |
| GET | `/jobs/{id}` | بلا جسم | `job_id`, `state`, `current_stage`, `progress`, `message`, `errors`, `recoverable`. |
| POST | `/jobs/{id}/cancel` | `{}` | الحالة بعد الإلغاء. |
| POST | `/jobs/{id}/resume` | `{}` | الحالة بعد جدولة الاستئناف. |
| GET | `/jobs/{id}/results` | بلا جسم | `clips`, `artifacts`, ونتائج المحرك بعد completion. |
| GET | `/jobs/{id}/clips/{clip}` | بلا جسم | تفاصيل المقطع والتحليل والتنقيح. |
| POST | `/jobs/{id}/clips/{clip}/render` | `{}` | artifact المحدث ورابط التنزيل. |

## دورة المهمة

الحالات الخارجية هي `QUEUED`, `PREPARING`, مراحل التنفيذ، `COMPLETED`, `FAILED`, `CANCELLED`, و`INTERRUPTED`. لا يعتمد العميل على اسم مرحلة Python لاتخاذ قرار terminal؛ يعتمد على `state`، ويحفظ stage للعرض فقط.

```text
POST /jobs
  → GET /jobs/{id}
  → إذا INTERRUPTED/RETRY_WAIT: POST /jobs/{id}/resume
  → عند COMPLETED: GET /jobs/{id}/results
  → GET أو POST render للمقطع عند الحاجة
```

## صيغة الخطأ

يرجع الخادم envelope ثابتًا قدر الإمكان:

```json
{
  "error": {
    "code": "RESULTS_NOT_READY",
    "message": "Results are available only after the job is completed.",
    "retryable": true,
    "request_id": "req_example"
  }
}
```

يُحافظ Android على `code` و`retryable` في السجل المحلي، ويعرض رسالة آمنة للمستخدم دون stack trace أو مسار ملف داخلي.

## idempotency وresume

يرسل Android مفتاحًا ثابتًا لكل job محلي. عند إعادة تشغيل التطبيق، يقرأ `remoteGatewayJobId` من Room ويستأنف polling بدل إعادة رفع الفيديو أو إنشاء job ثانٍ. إذا كانت المهمة `CANCELLED` نهائيًا، يمسح العميل المعرف البعيد عند اختيار retry المتعمد ويبدأ job جديدًا.

## المراجع

[1]: ../gateway/main.py "Android-facing private routes and canonical v1 routes"
[2]: ../android/app/src/main/java/com/example/data/remote/PrivateBackendClient.kt "Android private contract client"
[3]: ../android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Live worker integration"
[4]: ../gateway/tests/test_private_backend.py "Gateway private contract tests"

## References

المراجع ملفات محلية داخل المستودع، وهي مصدر تعريف العقد المنفذ حاليًا.
