# مقارنة المشروع المرجعي مع PublikClip

**تاريخ التدقيق:** 2026-08-26

**المشروع الأساسي:** `ISM-dragon/-1` — PublikClip/ISM

**المشروع المرجعي:** `autoclip-main.zip` — Autoclip، ترخيص MIT وفق ملف `LICENSE` الموجود في الأرشيف

## الحكم التنفيذي

المشروع المرجعي يقدّم Python application متماسكة نسبيًا حول FastAPI وSQLite وpipeline موحّدة، بينما يحتوي المشروع الأساسي على نطاق أوسع: Android native، Gateway خاص، Python engine/pipeline، واجهة desktop، اختبارات تشغيلية، وأدلة قبول. لذلك لا توجد مبررات لنسخ المشروع المرجعي فوق المشروع الأساسي. القرار المعتمد هو **COMBINE انتقائي**: الحفاظ على Gateway وAndroid والـEngine الحاليين، والاستفادة من أفكار المرجع في تنظيم الاختبارات، فصل طبقات API، وضبط دورة media/pipeline حيث يمكن إثبات توافقها.

> القاعدة الحاكمة: المرجع مصدر للمقارنة والاختيار، وليس مصدرًا لاستبدال بنية PublikClip أو نسخ مستودع كامل.

## مصفوفة المقارنة على مستوى الميزة

| المجال | PublikClip الحالي | المرجع Autoclip | التقييم | القرار | سبب القرار |
|---|---|---|---|---|---|
| Android structure/lifecycle | تطبيق Kotlin/Compose داخل `android/` مع Room وWorkManager وواجهة Gateway | لا يوجد Android client في الأرشيف | أقوى في الأساسي | `KEEP_CURRENT` | لا يوجد بديل مرجعي قابل للاعتماد، والمسار الحالي يحقق فصل الهاتف عن Python |
| File/video picker | URI محلي مع Media3/طبقة تثبيت URI | واجهة frontend ولا يوجد Android picker | الأساسي أنسب للهاتف | `KEEP_CURRENT` | توافق Android و`content://` يتطلبان مكوّنًا أصليًا |
| Networking | `ApiContractClient` وGateway contract وBearer token وHTTPS | FastAPI client-side contract داخل تطبيق Python/frontend | متقارب | `KEEP_CURRENT` مع تحسينات contract | لا حاجة لإدخال HTTP stack مختلف إلى Android |
| Background processing | WorkManager/CoroutineWorker، Room state، retry، notification | queue/worker داخل Python | الأساسي أنسب للهاتف | `COMBINE` | تبقى WorkManager في الهاتف وتبقى queue في الخادم؛ يستفاد من وضوح مراحل المرجع |
| Job persistence | Gateway SQLite + Android Room + remote job ID | SQLite models/store وcheckpoint files | كلاهما صالح بنطاقه | `KEEP_CURRENT` | وجود طبقتين مقصود: حالة محلية للعرض وحالة authoritative على Gateway |
| Engine boundary | `backend/engine.py` وpipeline Engine/JSONL adapter | `autoclip/pipeline/runner.py` مع API داخلية | الأساسي أكثر ملاءمة للمسار المستهدف | `KEEP_CURRENT` | boundary الحالي يمنع Android من استيراد Python modules عشوائيًا |
| Stage pipeline | ingest، ASR، diarization، events، candidates، scoring، camera، render | ingest، transcribe، boundaries، highlights، reframe، captions، export | تقارب وظيفي | `KEEP_CURRENT` ثم `COMBINE` أفكارًا | لا تُستبدل stages الحالية دون benchmark وregression tests |
| Probing/transcoding/FFmpeg | media helpers وGateway validation وpipeline render | `pipeline/ffmpeg.py` و`export_render` واختبارات FFmpeg | المرجع أبسط وأوضح في بعض helpers | `IMPROVE_CURRENT` | يمكن نقل contract/error taxonomy بصورة مستقلة دون نسخ implementation |
| ASR/alignment | WhisperX/faster-whisper ومسار server-side | WhisperX/faster-whisper عبر providers | متقارب | `KEEP_CURRENT` | pipeline الأساسي يحتوي عقودًا ونماذج وتكاملًا أوسع |
| Diarization | CAM++/speaker pipeline وword-speaker assignment | diarization داخل pipeline المرجعي | الأساسي أوسع | `KEEP_CURRENT` | لا يوجد دليل أن المرجع أكثر دقة أو توافقًا مع النظام الحالي |
| Events/audio signals | laughter، PANNs، DSP، arousal وإشارات متعددة | event/highlight helpers أبسط | الأساسي أكثر اكتمالًا | `KEEP_CURRENT` | حذف scoring/evidence سيخفض الجودة ويخالف المتطلبات |
| Candidate/scoring | candidates + LLM/deterministic fallback + confidence | boundaries/highlights/ranking | متقارب مع اختلاف النماذج | `COMBINE` | الاستفادة من اختبار الحدود والـdedupe فقط، مع إبقاء scoring versioned الحالي |
| Camera/reframe | face/speaker/active-speaker، smoothing، director، render integration | reframe/cropping/smoothing داخل Python | كلاهما صالح | `MANUAL_REVIEW` | أي استبدال يحتاج benchmark على نفس الفيديوهات والـhardware |
| Captions | word timestamps وASS rendering وthemes | captions/export واختبارات مخصصة | متقارب | `IMPROVE_CURRENT` | فصل caption state عن render contract، دون إعادة transcription |
| Render/export | FFmpeg render، outputs، metadata cleanup، Android download | FFmpeg export/render tests | المرجع مفيد للاختبار | `COMBINE` | نقتبس حالات الاختبار ونبقي renderer الحالي |
| Model management | registry/cache/diagnostics في pipeline/Gateway | config/provider/model helpers | الأساسي أوسع | `KEEP_CURRENT` | نموذج manager مطلوب لحجم النماذج وchecksum/health |
| API | Gateway `/v1/*` + Android contract وerror model | FastAPI `/api` وschemas | الأساسي أقرب للهدف | `KEEP_CURRENT` | Android-facing API يجب ألا يرتبط بنماذج المرجع الداخلية |
| Cancellation/resume | durable state، checkpoints، retry، cancel/resume endpoints | job state/checkpoint helpers | متقارب | `COMBINE` | يُراجع transition validation واختبارات restart، ولا تُستبدل queue الحالية |
| Reliability | اختبارات Gateway للـrestart/network loss/cancel/failure | tests للـDB/FFmpeg/pipeline | كلاهما مفيد | `COMBINE` | نضيف حالات مرجعية إلى matrix الأساسية عند الحاجة |
| Security/secrets | Gateway token، secret vault، no secrets in APK، HTTPS | config/env داخل Python | الأساسي أقوى للمسار الشخصي | `KEEP_CURRENT` | لا يجوز نسخ secrets أو config assumptions من المرجع |
| Release | Android Gradle/CI وDocker/Gateway workflows | Docker/compose وCI Python/frontend | متكاملان في مجالات مختلفة | `COMBINE` | نستفيد من docker layout عند الحاجة فقط بعد مراجعة الترخيص والتشغيل |

## مقارنة الاعتماديات والترخيص

المرجع يستخدم MIT للتطبيق نفسه، بينما المشروع الأساسي AGPL-3.0-or-later ويحتوي بالفعل على سجل provenance للشفرة المقتبسة والنماذج والخطوط في `VENDORED-LICENSES.md`. لم تُنقل أي ملفات أو dependency من الأرشيف إلى source tree في مرحلة التدقيق. أي اقتباس لاحق يجب أن يكون **إعادة تنفيذ مستقلة** أو patch صغيرًا مع حفظ الإسناد وإضافة الاختبارات.

الاختلاف في الترخيص لا يمنع دراسة الأفكار، لكنه يمنع افتراض أن كل ملف يمكن دمجه بلا تحليل. ملفات المرجع التي تعتمد على أسماء مؤلفين أو notices يجب ألا تُخلط مع ملفات المشروع الأساسي إلا بعد تحديد attribution المطلوب.

## خلاصات قابلة للتنفيذ

المرجع مفيد في اختبار FFmpeg، حدود المقاطع، paths، captions، وdatabase lifecycle. أما Android، private Gateway، secrets boundary، WorkManager، release signing، وremote artifact flow فتبقى مسؤولية المشروع الأساسي. لا يوصى بنسخ `frontend/` أو `docker/` أو `autoclip/` كاملًا إلى `-1`.

## القرار النهائي

| القرار | النطاق |
|---|---|
| `KEEP_CURRENT` | Android native، Gateway canonical، Python Engine boundary، scoring/audio signals، secrets policy، release identity |
| `IMPROVE_CURRENT` | error envelope، model readiness، media error taxonomy، docs، regression matrix، remote resume coverage |
| `COMBINE` | حالات اختبار FFmpeg/DB/pipeline، أفكار boundaries والـdedupe، تنظيم provider/model metadata |
| `MANUAL_REVIEW` | camera/reframe، أي تغيير في ASR/diarization، أي dependency native جديدة |
| `IGNORE_REFERENCE` | نسخ frontend أو المستودع كاملًا، account/SaaS assumptions، أي code بلا license واضح، build artifacts وsecrets |

## مراجع الملفات

- المشروع الأساسي: `docs/AUDIT.md`, `docs/ARCHITECTURE.md`, `docs/CONTRACTS.md`, `docs/API-CONTRACT.md`, `gateway/`, `pipeline/`, `android/`.
- المرجع: `autoclip-main/README.md`, `autoclip-main/LICENSE`, `autoclip-main/pyproject.toml`, `autoclip-main/tests/`, `autoclip-main/autoclip/`.
- سجل provenance الأساسي: [`../VENDORED-LICENSES.md`](../VENDORED-LICENSES.md).
