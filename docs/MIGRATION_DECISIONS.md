# سجل قرارات الترحيل

**المستودع:** `ISM-dragon/-1`

**المرجع:** `autoclip-main.zip`

**تاريخ القرار:** 2026-08-26

## منهج التقييم

تم تقييم البدائل وفق الأوزان المحددة للمشروع: توافق Android بنسبة 20%، الصحة 20%، الاستقرار 15%، الأداء 15%، قابلية الصيانة 10%، اكتمال الميزة 10%، كلفة الدمج 5%، وكلفة الاعتماديات 5%. هذه الدرجات توجه القرار ولا تستبدل benchmark أو اختبارًا فعليًا.

## سجل القرارات

| المجال | القرار | الأساس | الإجراء |
|---|---|---|---|
| Android client | `KEEP_CURRENT` | المرجع لا يملك Android client، والمسار الأساسي يفصل الهاتف عن Python | تثبيت Kotlin/Compose/Room/WorkManager وGateway client |
| Desktop frontend | `IGNORE_REFERENCE` | واجهة المرجع لا تعالج هدف APK الشخصي | عدم نسخ `frontend/` إلى `app/` أو `android/` |
| Gateway/backend boundary | `KEEP_CURRENT` | Gateway الحالي يملك auth/upload/queue/state/diagnostics | اعتباره canonical للمسار Android وتجميد backend البديل للميزات الجديدة |
| Engine facade | `KEEP_CURRENT` | يوجد adapter واضح مع JSONL وcontracts | إبقاء `backend/engine.py` وpipeline contracts كحد فاصل |
| Pipeline stages | `KEEP_CURRENT` | المشروع الأساسي يملك مراحل أوسع مع checkpoints | عدم حذف ingest/ASR/diarization/events/candidates/scoring/camera/render |
| Media helpers | `IMPROVE_CURRENT` | المرجع يملك اختبارات FFmpeg وpaths مفيدة | نقل حالات الاختبار والأخطاء كعقود مستقلة، لا نسخ renderer كامل |
| ASR/WhisperX | `KEEP_CURRENT` | runtime الأساسي متكامل server-side | لا native Whisper على Android قبل إثبات حاجة وbenchmark |
| Diarization | `KEEP_CURRENT` | CAM++ وتوزيع speaker timestamps جزء من المسار الحالي | لا استبدال implementation المرجعية دون بيانات دقة |
| Audio events | `KEEP_CURRENT` | وجود laughter/PANNs/DSP وإشارات arousal | الحفاظ على الأدلة متعددة الإشارات وعدم تبسيط scoring |
| Candidate boundaries | `COMBINE` | المرجع يحتوي اختبارات boundaries وdedupe قابلة للاستفادة | إضافة regression tests أو إعادة تنفيذ الفكرة داخل contracts الحالية |
| Scoring | `KEEP_CURRENT` | scoring الحالي يدعم LLM/fallback/confidence | عدم جعل فشل LLM crash إذا أمكن fallback، مع versioning |
| Camera/reframe | `MANUAL_REVIEW` | اختلاف البيانات والأداء كبير ولا يكفي code diff | benchmark مشترك قبل أي replacement |
| Captions | `IMPROVE_CURRENT` | المرجع يعرض حالات اختبار مفيدة، والأساسي يملك word timestamps | فصل caption state عن render logic وتحسين readability دون إعادة transcription |
| Model manager | `KEEP_CURRENT` | الأساسي يملك registry/cache/diagnostics أوسع | استكمال checksum/health/size metadata عند الحاجة |
| Job lifecycle | `COMBINE` | كلا المشروعين يملكان state/checkpoint ideas | توحيد transition/error contract مع بقاء Gateway authoritative |
| Android resume | `IMPROVE_CURRENT` | يجب استخدام `remoteGatewayJobId` بعد restart/retry | تغطية worker الحالي بالاختبار وعدم إنشاء remote job جديد بلا حاجة |
| Resumable upload | `MANUAL_REVIEW` | one-shot الحالي عامل لكن أقل تحملًا لانقطاع الشبكة | إضافة لاحقة بعد contract وbenchmark وليس ضمن نسخ المرجع |
| Secrets | `KEEP_CURRENT` | Gateway token وGemini secrets خارج APK | رفض أي secret من الأرشيف أو source tree |
| Docker | `COMBINE` | المرجع يملك compose أبسط، والأساسي يملك Gateway image | مقارنة التشغيل والحجم قبل أي تعديل deployment |
| CI/tests | `COMBINE` | لكل مشروع تغطية مختلفة | إضافة حالات مرجعية دون تكرار suites أو تشغيل code غير موثوق |
| Build artifacts | `IGNORE_REFERENCE` | لا حاجة لنسخ caches أو generated outputs | عدم إدخال `node_modules`, `.venv`, models, APKs أو DBs |

## قرارات الترخيص

لم يتم نسخ أي source file من المرجع إلى المشروع الأساسي. ملف المرجع `LICENSE` يعلن MIT مع copyright باسم مؤلفه، بينما المشروع الأساسي AGPL-3.0-or-later. دراسة الخوارزميات أو إعادة تنفيذ contract لا تعني نسخ الكود. أي دمج مستقبلي يتطلب حفظ notice وملء سجل `THIRD_PARTY_LICENSES.md` واختبار dependency compatibility.

## قرارات النطاق

المنتج المستهدف تطبيق شخصي واحد مع private backend. لذلك لا تُضاف multi-user accounts أو billing أو subscriptions أو public SaaS infrastructure. Social OAuth/publishing يبقى optional وfeature-gated ولا يدخل في acceptance لمسار clip generation الأساسي.

## العناصر المفتوحة

| العنصر | الحالة | دليل الإغلاق |
|---|---|---|
| جهاز Android حقيقي | مفتوح | APK مثبت، screenshots/logcat، ومسار E2E حقيقي |
| Release signing | مفتوح | keystore خارجي، `apksigner verify` ناجح، SHA-256 محفوظ |
| Gemini/AI runtime | مفتوح | diagnostics وASR/diarization/scoring/camera/render فعلية |
| Private HTTPS Gateway | مفتوح | endpoint قابل للوصول من الهاتف مع token وrestart/network evidence |
| Large media | مفتوح | تشغيل صريح بملف كبير ومساحة كافية |
