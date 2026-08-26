# MANUS HANDOFF — ISM Android application

**المشروع:** ISM / PERSONAL ANDROID APK + PRIVATE BACKEND + PUBLIKCLIP ENGINE
**المستودع:** `ISM-dragon/-1`
**تاريخ التسليم:** 2026-08-26
**حالة التسليم:** **FINAL ACCEPTANCE BLOCKED — evidence and build artifacts committed for internal QA only**

## نطاق التسليم

تم تنفيذ تطبيق Android أصلي مخصص لـ ISM يعمل كعميل Gateway بعيد. Android مسؤول عن UI، file picker، اختيار الفيديو، الرفع، إنشاء الوظيفة، عرض الحالة والتقدم، معاينة النتائج، تصفح المقاطع، تنزيل النتائج، والتحرير الأساسي لبداية ونهاية المقطع. لا يحتوي التدفق الجديد على Python أو FFmpeg أو خط معالجة وسائط محلي.

> القاعدة المعمارية: Android هو stateful client فوق Gateway؛ Gateway هو المصدر authoritative لحالة الوظيفة والتقدم والنتائج، ولا تُنقل تفاصيل Python أو Pipeline الداخلية إلى الواجهة.

لم تُضف features أو تُعدّل المعمارية أو خوارزميات المعالجة في جلسة القبول هذه. التغييرات البرمجية الموجودة على `origin/main` دُمجت دون force push، وحافظت هذه الجلسة على توثيق القبول والأدلة.

## البنية المنفذة

| الطبقة | الملف/المكوّن | المسؤولية |
|---|---|---|
| نماذج العقد | `android/app/src/main/java/com/example/remote/model/RemoteProcessingModels.kt` | حالات Gateway canonical، أخطاء العقد، الوظائف، المقاطع، النتائج، وحالة UI |
| API client | `remote/data/GatewayApiClient.kt` | `/health`، session، capabilities، upload، create job، polling، cancel/retry/resume، media download |
| التخزين | `remote/data/RemoteProcessingStore.kt` | حفظ الوظيفة النشطة، remote job ID، idempotency key، النتائج، وإعدادات Gateway |
| orchestration | `remote/data/RemoteProcessingCoordinator.kt` | unique WorkManager، منع الوظائف المكررة، cancel/retry/resume، إعادة الجدولة |
| background execution | `remote/data/RemoteProcessingWorker.kt` | upload مرة واحدة، إنشاء idempotent job، polling، resume، download والتحقق من النتائج |
| presentation | `remote/ui/RemoteStudioViewModel.kt` | تنقل الشاشة وحالة UI وربط الإجراءات بالتخزين والمنسق |
| screens | `remote/ui/RemoteStudioScreens.kt` | Home، Import Video، Processing، Processing Error، Results، Clip Review، Settings |
| Gateway | `gateway/main.py` و`gateway/processing_service.py` | auth، SQLite state، media boundary، queue، retry/cancel/resume، provider secrets، artifact serving |
| Pipeline | `pipeline/publikclip_pipeline/` | ingest، ASR، diarization، events، candidates، scoring، camera، captions، FFmpeg rendering |

## نتائج البناء والتحقق

| الفحص | النتيجة | الدليل |
|---|---:|---|
| Android unit tests | 33 passed | `evidence/android_unit_test.log` |
| Final Android unit retest | 33 passed | `evidence/final_android_unit_retest.log` |
| Gateway tests | 40 passed, 1 skipped | `evidence/gateway_pytest.log` |
| PublikClip tests | 117 passed | `evidence/pipeline_pytest.log` |
| Root regression | 157 passed, 1 skipped | `evidence/root_pytest.log` |
| Final root regression retest | 157 passed, 1 skipped | `evidence/final_root_retest.log` |
| Targeted failure regression | 51 passed, 1 skipped | `evidence/targeted_failure_tests.log` |
| Identity | PASS: ISM / 0.10.1 / application ID / v1 | `evidence/identity.log` |
| Python compile | PASS | `evidence/py_compile.log` |
| Git whitespace | PASS | `evidence/git_diff_check.log` |
| Final release assembly | PASS | `evidence/final_android_release_build.log` |
| APK metadata/ZIP | PASS | `evidence/final_release_apk_badging.txt`, `evidence/final_release_apk_zip_test.txt` |
| APK signing | BLOCKED | `evidence/release_apk_signing_check.txt` — unsigned |

**APK الناتج:** `android/app/build/outputs/apk/release/app-release-unsigned.apk`
**SHA-256:** `f0ae2936f6dc10242c864460081151395fc9f621270d742639a67cb1159dc9ab`
**Application ID:** `com.aistudio.opuspro.apk`
**Version:** `0.10.1` / versionCode `5`

## Evidence تشغيلي فعلي

| المسار | النتيجة |
|---|---|
| Gateway health/auth/capabilities | PASS محليًا؛ health وcapabilities نجحت، وroute خاص بلا token أعاد 401 |
| Valid video upload | PASS؛ `short.mp4` رُفع وأعيد source URL مع bytes وSHA-256 |
| Invalid video | PASS؛ `corrupted.mp4` رُفض HTTP 422 مع `MEDIA_INVALID` |
| Gemini create job | BLOCKED بأمان؛ 503 مع `GEMINI_NOT_CONFIGURED` |
| Real pipeline attempt | بدأ فعليًا ووصل إلى ingest/ASR، لكنه لم يصل إلى downstream artifact |
| Active cancel | PASS على Gateway؛ `CANCELLED` مع `JOB_CANCELLED` |
| Backend restart | PASS جزئيًا؛ job ID وtransition history بقيا durable بعد restart |
| Network loss | PASS جزئيًا؛ connection refusal أثناء التوقف ثم قراءة الوظيفة بعد عودة الخدمة |
| Retry | PASS جزئيًا؛ أعاد إدراج الوظيفة مع retry metadata، بينما ظل فشل pipeline الحقيقي قائمًا |
| Android install/open/picker/preview/edit/export | BLOCKED؛ لا جهاز حقيقي متصل وAPK unsigned |

الأدلة التفصيلية موجودة في `evidence/`، وأحكام القبول في `docs/FINAL_ACCEPTANCE.md`، وتصنيف الحواجز في `docs/RELEASE_BLOCKERS.md`.

## حالات الاعتماد والاستعادة

يُحفظ `local_job_id` و`remote_job_id` و`idempotency_key` قبل وبعد كل حد شبكي مهم. يستخدم WorkManager `ExistingWorkPolicy.KEEP` باسم فريد لكل وظيفة، لذلك لا ينشئ التطبيق وظيفة ثانية إذا كان العمل مجدولًا أو قيد التنفيذ بالفعل. عند بدء التطبيق، تُقرأ الوظيفة المحفوظة؛ إذا كانت غير نهائية يُعاد enqueue للعمل نفسه ويستأنف polling.

| الحالة | استجابة Android المتوقعة |
|---|---|
| انقطاع الشبكة | Network constraint + exponential backoff، إبقاء الوظيفة في التخزين وعرض انتظار عودة الاتصال |
| إعادة تشغيل التطبيق | إعادة قراءة الوظيفة وإعادة enqueue باستخدام نفس local/remote IDs |
| job already running | عدم إعادة إنشاء الوظيفة؛ الاستعلام باستخدام remote job ID المحفوظ |
| `INTERRUPTED` | استدعاء `/resume` ثم متابعة polling |
| `FAILED` | عرض الخطأ الموحّد، مع retry فقط عند `recoverable=true` |
| cancel | استدعاء `/cancel` ثم حفظ الحالة النهائية التي يعيدها Gateway وإيقاف unique work |
| نتائج فارغة | اعتبارها فشلًا آمنًا `NO_VALID_CLIPS` بدل فتح Review فارغ وكأنه نجاح |

## حواجز الإصدار

**P0:** لا يوجد جهاز Android حقيقي للاختبار، والمسار الكامل APK → Gateway → Pipeline → artifact → preview/edit/render-again/export غير مثبت.
**P1:** APK غير موقّع؛ Gemini غير مهيأ؛ اكتمال ASR/diarization/LLM production غير مثبت؛ private HTTPS/Docker deployment غير متاح في sandbox.
**P2:** large-media test skipped وdevice instrumentation غير مشغّل.
**P3:** deprecation warnings وصيانة namespace التاريخي موثقتان ولا تمنعان البناء.

هذه الحواجز ليست عيوبًا يجوز تجاوزها بإضافة mock success. لم يتم الادعاء بإغلاق P0/P1، ولم يُجرَ تغيير source code لإخفائها.

## كيفية إعادة التحقق

لإغلاق القبول، استخدم APK موقّعًا على جهاز Android حقيقي متصل مع USB debugging، واضبط Gateway خاصًا عبر HTTPS مع token، ثم نفّذ diagnostics قبل إنشاء job. يجب أن تكون pipeline وFFmpeg/storage وASR/diarization وLLM جاهزة. بعد ذلك أعد سيناريو `docs/FINAL_ACCEPTANCE.md` كاملًا، واجمع screenshots/logcat/job IDs/transition history وSHA-256 لكل artifact، ثم حدّث الحكم إلى PASS فقط بعد تحقق export وrender again من التطبيق نفسه.

## ملاحظة عن الوثائق المطلوبة

الملفات الحرفية `docs/ARCHITECTURE.md`, `docs/AUDIT.md`, `docs/API.md`, `docs/INTEGRATION_STATUS.md`, `docs/TEST_MATRIX.md`, `docs/PERFORMANCE.md`، و`docs/SECURITY.md` غير موجودة في repository الحالي. تمت مراجعة البدائل canonical الموجودة، ومنها `docs/MASTER-ARCHITECTURE.md`, `docs/API-CONTRACT.md`, `docs/FINAL-PRODUCTION-AUDIT.md`, `docs/ENGINE_STABILITY.md`, `docs/ANDROID_AUDIT.md`، و`docs/CLIENT-RESPONSIBILITIES.md`.

## ملفات التسليم

- `docs/FINAL_ACCEPTANCE.md`
- `docs/RELEASE_BLOCKERS.md`
- `evidence/` وسجلات الاختبارات والـ smoke tests
- `android/app/build/outputs/apk/release/app-release-unsigned.apk` للاختبار الداخلي فقط
