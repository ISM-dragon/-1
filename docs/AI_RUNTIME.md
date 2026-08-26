# AI Runtime

**المكان:** Private Gateway/AI host فقط.

لا يحتوي APK على Python أو WhisperX أو PyTorch أو provider keys أو model weights. يحتفظ الخادم الخاص بسجل المزودات، وmodel cache، وdiagnostics، ويعرض للعميل readiness وerrors آمنة فقط.

## نموذج السجل

| الحقل | الغرض |
|---|---|
| `name` | الاسم المنطقي للنموذج أو المزود. |
| `version` | تثبيت reproducible للنتائج. |
| `size_bytes` | التحقق من disk budget. |
| `sha256` | منع model corruption أو substitution. |
| `source` | مصدر التنزيل أو artifact registry. |
| `local_path` | مسار خاص بالخادم، ولا يخرج إلى العميل. |
| `installed` | هل الملفات كاملة ومتحقق منها؟ |
| `health` | `ready`, `missing`, `invalid`, أو `unavailable`. |

## الحدود التشغيلية

تبدأ الخدمة بفحص FFmpeg/ffprobe، مساحة التخزين، قابلية كتابة job directory، ووجود ASR/diarization models. عند اختيار Gemini أو Ollama يجب أن تعيد diagnostics سببًا قابلًا للفهم. لا يبدأ job جديد إذا كان provider المطلوب غير جاهز، ولا يحوّل هذا الوضع إلى 500 غامض أو crash.

يجب أن يكون model download قابلًا للاستئناف والتحقق والحذف الآمن. تحميل النموذج أو provider client يكون lazy ومخزنًا في cache لتجنب reload لكل job. تُقاس أزمنة ASR وdiarization وscoring واستهلاك RAM/VRAM قبل أي optimization.

## Scoring وfallback

يبقى scoring الحالي هو الأساس. يمكن للـLLM أن يضيف hook/value/shareability signals أو تفسيرًا، لكنه لا يحتكر صحة النتيجة. عند network/provider failure، يستخدم المحرك fallback rubric أو signal-based scoring عندما يكون متاحًا، ويسجل `provider_status`, `rubric_version`, و`confidence` في metadata. لا تُرسل transcript أو media إلى provider إلا وفق إعداد مالك Gateway.

### المراجع

[1]: ../gateway/provider_registry.py "Provider registry and health"
[2]: ../gateway/secret_vault.py "Gateway-owned secrets"
[3]: ../pipeline/publikclip_pipeline/insights/ "Scoring and calibration"
[4]: ../pipeline/publikclip_pipeline/asr/ "ASR runtime"
[5]: ../docs/FINAL_ACCEPTANCE.md "Observed readiness blockers"

## References

[1]: ../gateway/provider_registry.py "Provider registry and health"
[2]: ../gateway/secret_vault.py "Gateway-owned secrets"
[3]: ../pipeline/publikclip_pipeline/insights/ "Scoring and calibration"
[4]: ../pipeline/publikclip_pipeline/asr/ "ASR runtime"
[5]: FINAL_ACCEPTANCE.md "Observed readiness blockers"
