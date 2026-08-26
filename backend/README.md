# Private publikclip Backend

هذا المجلد يحتوي خدمة **Private Backend** شخصية لجهاز Android واحد. لا توجد حسابات مستخدمين أو billing أو subscriptions أو multi-tenancy. الخدمة تحفظ jobs والملفات محليًا، وتستدعي `publikclip` عبر adapter معزول في `backend/engine.py`.

## التشغيل

من جذر المستودع:

```bash
export PRIVATE_BACKEND_TOKEN='ضع-رمزًا-عشوائيًا-طويلًا'
export PRIVATE_BACKEND_DEVICE_ID='android-device-identifier'
uvicorn backend.app:app --host 0.0.0.0 --port 8788
```

يمكن تغيير مكان قاعدة البيانات والملفات بواسطة `PRIVATE_BACKEND_DB` و`PRIVATE_BACKEND_STORAGE`. الحد الافتراضي لرفع الفيديو هو 2 GiB، ويمكن تغييره عبر `PRIVATE_BACKEND_MAX_UPLOAD_BYTES`.

يجب تشغيل الخدمة عبر HTTPS أو خلف reverse proxy موثوق عند الوصول من Android عبر شبكة غير محلية. لا تستخدم `PRIVATE_BACKEND_ALLOW_INSECURE_LOCAL=true` إلا للاختبارات المحلية؛ هذا الخيار يسمح لعميل localhost بتجاوز Bearer token ولا يصلح للإنتاج.

## واجهة API المختصرة

جميع المسارات التالية، باستثناء `/health`، تتطلب `Authorization: Bearer ...` و`X-Device-ID: ...` في وضع التشغيل العادي. رفع الفيديو يتم كـ raw request body مع `Content-Type: video/mp4` و`X-Filename` اختياريًا.

| الطريقة | المسار | الغرض |
|---|---|---|
| `GET` | `/health` | liveness ووجود adapter فقط، ولا يكشف أسرارًا. |
| `POST` | `/uploads` | حفظ فيديو محليًا وإرجاع `upload_id`. |
| `POST` | `/jobs` | إنشاء job من `upload_id` أو من URL عام؛ يعيد `202`. |
| `GET` | `/jobs` | قائمة jobs مع `limit` و`status` وcursor زمني اختياري. |
| `GET` | `/jobs/{id}` | الحالة، المرحلة، progress، الخطأ، والـresume metadata. |
| `POST` | `/jobs/{id}/cancel` | إلغاء durable وإرسال إشارة إلى worker أو subprocess. |
| `POST` | `/jobs/{id}/resume` | إعادة تشغيل job فاشل/متوقف إذا توفر engine checkpoint. |
| `GET` | `/jobs/{id}/results` | استرجاع النتائج بعد `completed`. |
| `GET` | `/jobs/{id}/clips` | قائمة المقاطع مع روابط التنزيل. |
| `GET` | `/jobs/{id}/clips/{clip}/download` | تنزيل ملف MP4 بعد اكتمال job. |
| `POST` | `/jobs/{id}/clips/{clip}/render` | طلب إعادة render عبر engine adapter. |

صيغة الخطأ ثابتة، مثلًا:

```json
{
  "error": {
    "code": "RESULTS_NOT_READY",
    "message": "Results are not ready",
    "request_id": "req_...",
    "retryable": true
  }
}
```

يقبل `POST /jobs` JSONًا من الشكل التالي، مع تقديم واحد فقط من `source` و`upload_id`:

```json
{
  "upload_id": "upl_...",
  "options": {"llm": "gemini", "captions": "classic", "mode": "balanced"},
  "idempotency_key": "android-installation-job-001"
}
```

## الاختبارات

```bash
pytest -q backend/tests
```

الاختبارات تستخدم Fake Engine ولا تتطلب مفاتيح AI أو تنزيل فيديو خارجي. اختبارات integration الحقيقية مع pipeline تعتمد على تثبيت runtime الخاص بالمحرك ووجود FFmpeg وتهيئة مفاتيح مزود AI خارج هذا المجلد.
