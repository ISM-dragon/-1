# Android Processing Audit

## الهدف

تطبيق Android في `app/src` لا يشغّل Python أو `uv` محلياً. المسار المدعوم هو Android إلى Personal Processing Gateway ثم إلى Pipeline Python الموجود في `pipeline/`، وبعدها تعود نتائج MP4 إلى التطبيق.

## مكونات المسار

| المكوّن | المسار | الدور |
|---|---|---|
| Studio | `app/src/components/Studio.tsx` | إدخال رابط المصدر وحفظ Gateway وتشغيل TEST SYSTEM وCUT IT. |
| App orchestration | `app/src/App.tsx` | فحص الرابط، اختبار health/capabilities/Gemini، إنشاء المهمة، استطلاع الحالة، وفتح Review. |
| API client | `app/src/api.ts` | HTTP requests إلى Gateway مع Bearer token وتحقق HTTPS/LAN Debug. |
| Gateway | `gateway/main.py` | المصادقة، فحوص البيئة، تشغيل Pipeline في worker، حفظ الحالة، وإتاحة الوسائط. |
| Pipeline adapter | `pipeline/publikclip_pipeline/cli.py` | الأمر `publikclip --jsonl run <source> --llm <mode> --captions <preset>`. |
| Gemini provider | `pipeline/publikclip_pipeline/scoring/llm.py` | يقرأ `PUBLIKCLIP_GEMINI_API_KEY` أو `PUBLIKCLIP_HOME/secrets.json` ويستدعي Google Gemini. |
| Render | `pipeline/publikclip_pipeline/render/stage.py` و`render/ffmpeg_bin.py` | إنشاء MP4 والتحقق من وجود المخرجات وقدرة FFmpeg على captions. |

## الإعداد الأمني

Android لا يرسل Gemini key إلى Gateway. يحفظ Gateway المفتاح في متغير البيئة `PUBLIKCLIP_GEMINI_API_KEY` أو في الملف المحلي غير المتعقب `gateway/secrets/gemini.key`، ثم يمرره إلى عملية Pipeline عبر بيئة subprocess. لا تعيد health أو capabilities أو diagnostic المفتاح أو قيمته.

يجب تشغيل Gateway مع `GATEWAY_TOKEN` طويل وعشوائي. في APK Debug يمكن استخدام عنوان LAN مثل `http://192.168.1.10:8787`، أما Gateway العام فيجب أن يستخدم HTTPS.

## نقاط الفشل

| الحالة | التشخيص الذي يظهر |
|---|---|
| Gateway لا يمكن الوصول إليه | `Gateway <status>` أو `GATEWAY_UNREACHABLE` |
| Bearer token غير صحيح | HTTP 401 / `AUTH_FAILED` |
| مجلد Pipeline أو `pyproject.toml` مفقود | `PIPELINE_UNAVAILABLE` |
| uv/Python غير متاح | `PIPELINE_UNAVAILABLE` أو نتيجة capabilities غير جاهزة |
| FFmpeg/ffprobe غير متاح | `FFMPEG_UNAVAILABLE` |
| Gemini key غير مضبوط | `GEMINI_NOT_CONFIGURED` |
| Gemini رفض المفتاح | `GEMINI_AUTH_FAILED` |
| Gemini model غير موجود | `GEMINI_MODEL_NOT_FOUND` |
| quota أو rate limit | `GEMINI_QUOTA_OR_RATE_LIMIT` |
| لا توجد مخرجات صالحة | `PROCESSING COMPLETED — NO VALID CLIPS FOUND` |
| Pipeline يفشل أثناء التنفيذ | حالة job `failed` مع stage/message/error محفوظة |

## المسارات التشخيصية

`GET /health` يعيد مؤشرات عامة فقط. و`GET /v1/processing/capabilities` يعيد readiness flags. و`POST /v1/diagnostics/pipeline` يفحص المسار والـruntime وFFmpeg والتخزين دون تنفيذ فيديو كامل. و`POST /v1/diagnostics/gemini` يرسل طلباً صغيراً إلى Gemini ولا يعيد المفتاح أو نص الاعتماد.

## قيد مهم

لا يمكن إثبات المعالجة الحقيقية من APK وحده إذا لم يكن Gateway متصلاً بخادم يملك اعتماديات WhisperX وFFmpeg ومفتاح Gemini صالحاً. TEST SYSTEM يميز هذه الحالة قبل إنشاء أي job، وCUT IT لا ينشئ المهمة إذا فشل أحد المتطلبات.
