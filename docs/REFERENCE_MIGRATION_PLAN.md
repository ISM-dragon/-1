# خطة ترحيل PublikClip من المقارنة المرجعية

**الحالة:** خطة معتمدة للتنفيذ التدريجي، وليست تصريحًا بنسخ المشروع المرجعي.

## المبدأ

يُنفّذ الترحيل من خلال عقود واضحة واختبارات regression، مع إبقاء المسار العامل في المشروع الأساسي. لا يتم استبدال stage أو dependency أو backend لمجرد أن المرجع يملك implementation مختلفة. كل تغيير يجب أن يثبت فائدته في Android compatibility أو correctness أو stability أو performance.

## Wave 1 — Audit وArchitecture

| المخرج | الحالة | معيار القبول |
|---|---|---|
| مقارنة code/feature/license | مكتمل | `REFERENCE_COMPARISON.md` و`THIRD_PARTY_LICENSES.md` |
| تثبيت Gateway كـcanonical Android backend | مكتمل توثيقيًا | Android contract يشير إلى `/v1/*` وGateway job ID |
| تعريف Engine facade | موجود | `backend/engine.py` وpipeline engine contracts |
| تحديد ownership | مكتمل | Android، Gateway، Engine، Models، Media Runtime موثقة منفصلة |
| تحديد blockers | موجود | `docs/RELEASE_BLOCKERS.md` و`docs/FINAL-ACCEPTANCE.md` |

## Wave 2 — Engine وAI/Media وBackend foundations

يجب أولًا تثبيت lifecycle واحد للحالة، ثم مراجعة media validation وerror taxonomy. يحتفظ Engine بالمراحل الحالية: ingest، ASR، diarization، events، candidates، scoring، camera، render. تُضاف اختبارات للـresume من checkpoint، وتُمنع أخطاء LLM القابلة للتجاوز من إسقاط المهمة كاملة عندما يتوفر deterministic fallback.

لا تُنقل نماذج Whisper أو diarization إلى Android. Model Manager يبقى server-side ويعرض الاسم والإصدار والحجم والـchecksum والمصدر والحالة الصحية. FFmpeg يبقى server-side ويجب أن يصنّف `MEDIA_INVALID` و`FFMPEG_MISSING` و`FFMPEG_FAILED` و`MODEL_MISSING` و`MODEL_INVALID` و`INSUFFICIENT_DISK` و`UNSUPPORTED_FORMAT`.

## Wave 3 — Android core وUI وbuild

يحافظ Android على Compose وRoom وWorkManager وMedia3 وURI handling. المسار المطلوب هو Home → Import → Generate → Processing → Results → Clip Review → Edit → Render → Export. لا يُنقل Tauri desktop UI إلى Android حرفيًا، ولا يُضاف Python/uv/FFmpeg/Node/Rust إلى APK.

يبقى `GatewayProcessingWorker` مسؤولًا عن الرفع، إنشاء job، polling، حفظ remote job ID، retry، cancellation، واستعادة الحالة. يجب أن يفشل الإعداد غير الآمن closed: عنوان Gateway للإنتاج HTTPS ورمز Bearer غير فارغ. يثبت build في CI، بينما توقيع release يتطلب keystore خارج المستودع.

## Wave 4 — Integration

يُختبر تنفيذ Android مع Gateway خاص فعلي، وليس localhost داخل sandbox. يجب أن يتطابق job ID المحلي والبعيد، وأن تبقى artifacts قابلة للتنزيل بعد restart للـGateway أو التطبيق. تُراجع idempotency، network loss، retry، resume، authorization على artifacts، وcleanup للملفات المؤقتة.

## Wave 5 — E2E وQA وRelease

لا يغلق الإصدار إلا بعد تشغيل فيديو حقيقي من Android حتى MP4 نهائي ثم preview/edit/render-again/export، مع إثبات restart وnetwork interruption وfailure recovery. تُحفظ SHA-256 للـAPK والـartifacts، وسجل الجهاز وlogcat وtransition history. نجاح compilation أو unit tests وحده غير كافٍ.

## التغييرات المسموح بها في هذه الخطة

| التغيير | القرار | الشرط |
|---|---|---|
| إضافة docs/contracts واختبارات regression | مسموح | لا يغيّر السلوك القائم دون دليل |
| إصلاح resume باستخدام remote job ID | مسموح | اختبار Android unit/contract قبل الدمج |
| تحسين error envelope | مسموح | الحفاظ على HTTP status والتوافق القديم |
| إضافة resumable upload | لاحق | benchmark وانقطاع شبكي مثبت |
| استبدال camera أو ASR أو diarization | ممنوع حاليًا | benchmark وmanual review وlicense review |
| نسخ المشروع المرجعي كاملًا | ممنوع | لا يتوافق مع النطاق أو architecture |

## شروط التوقف أو طلب handoff

إذا تطلب التغيير تعديل subsystem غير مملوك للمهمة، يُكتب contract أو handoff بدل تعديل واسع. وإذا تعذر إثبات تشغيل model/runtime أو جهاز Android حقيقي، تُبقى الحالة `OPEN` ولا تُخفى تحت test doubles أو وثائق عامة.
