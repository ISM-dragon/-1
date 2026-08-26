# API Contract — Android Private Gateway

## الحدود

هذا العقد مخصص لتطبيق Android الشخصي. كل endpoint محمي بآلية auth الخاصة بالـGateway، ولا يضع التطبيق مفاتيح مزودي الذكاء الاصطناعي. عنوان Gateway يجب أن يكون HTTPS في بيئة الإنتاج، بينما يسمح debug بعنوان خاص مضبوط صراحة.

## دورة الاستخدام

```text
POST /jobs (multipart video أو source_url)
  → GET /jobs/{job_id}
  → عند COMPLETED: GET /jobs/{job_id}/results
  → GET /jobs/{job_id}/clips/{clip_id}
  → POST /jobs/{job_id}/clips/{clip_id}/render
  → download_url / url
```

## العمليات

| الطريقة والمسار | الغرض | النجاح |
|---|---|---|
| `POST /jobs` | إنشاء job ورفع فيديو أو قبول `source_url` | كائن حالة يحوي `job_id` و`state` و`progress` |
| `GET /jobs/{job_id}` | polling واستعادة الحالة | الحالة الحالية، المرحلة، التقدم، الأخطاء |
| `POST /jobs/{job_id}/cancel` | طلب الإلغاء | `CANCELLED` أو `cancel_requested=true` |
| `POST /jobs/{job_id}/resume` | استئناف job قابل للاسترداد | job يعاد إلى queue |
| `GET /jobs/{job_id}/results` | قراءة نتائج job المكتمل | قائمة `clips` و`artifacts` |
| `GET /jobs/{job_id}/clips/{clip_id}` | تفاصيل clip وإعدادات التحرير | metadata آمنة بلا مسارات خادم |
| `POST /jobs/{job_id}/clips/{clip_id}/render` | إعادة رندر clip دون إعادة ASR | artifact صالح مع download URL |

## الحالات

الحالات التشغيلية الأساسية هي `QUEUED`, `RUNNING`/حالات المراحل، `CANCEL_REQUESTED`, `CANCELLED`, `FAILED`, و`COMPLETED`. قد يعيد Gateway حالات داخلية أكثر تفصيلًا مثل `TRANSCRIBING` أو `RENDERING`؛ يجب على Android عرضها كتقدم لا كعقد جديد.

## Error envelope

```json
{
  "errors": [
    {"code": "MEDIA_INVALID", "message": "Uploaded file is not a readable video."}
  ],
  "recoverable": false,
  "correlation_id": "cor_xxx"
}
```

الأكواد المهمة هي `VIDEO_REQUIRED`, `UNSUPPORTED_VIDEO`, `UPLOAD_TOO_LARGE`, `MEDIA_INVALID`, `MEDIA_CHECKSUM_MISMATCH`, `FFMPEG_UNAVAILABLE`, `PIPELINE_UNAVAILABLE`, `STORAGE_UNAVAILABLE`, `JOB_NOT_FOUND`, `RESULTS_NOT_READY`, `CLIP_NOT_FOUND`, `CLIP_RENDER_FAILED`, `JOB_CANCELLED`, و`WORKER_NOT_READY`.

## idempotency وresume

يرسل العميل `idempotency_key` ثابتًا لإعادة المحاولة الآمنة. يحتفظ Android بـ`remoteGatewayJobId` في Room، ويعيد polling أو resume بعد restart. لا يعيد العميل رفع أو معالجة المصدر إذا كان Gateway قد أعاد job أو artifact صالحًا.

## أمن البيانات

لا تُعاد مسارات filesystem الخام أو ملفات ASS أو مفاتيح المزودين للعميل. لا يقبل Gateway إلا امتدادات الفيديو المحددة، ويفحص الحجم وSHA-256 وقابلية القراءة قبل النشر. يجب ألا يسجل Android token أو محتوى الفيديو في logcat.
