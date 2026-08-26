# MANUS HANDOFF — ISM Android application

**المشروع:** ISM / PERSONAL ANDROID APK + PRIVATE BACKEND + PUBLIKCLIP ENGINE
**المستودع:** `ISM-dragon/-1`
**تاريخ التسليم:** 2026-08-26
**الحالة:** **FINAL ACCEPTANCE BLOCKED — evidence and build artifacts committed for internal QA only**

## القرار المعماري

المسار canonical هو: **Android APK → Private Gateway → PublikClip Engine → AI/Media Runtime**. تطبيق Android هو stateful client فوق Gateway؛ لا يشغّل Python أو `uv` أو WhisperX أو PyTorch أو FFmpeg. Gateway هو المصدر authoritative لحالة الوظيفة والتقدم والنتائج، ويحوي auth وSQLite وupload/storage وworker supervision وdiagnostics وserver-side secret boundary.

تم اختيار `gateway/` كـprivate backend canonical لمسار Android. مجلد `backend/` مستقل/legacy ولا ينبغي إضافة features جديدة إليه بالتوازي دون قرار دمج صريح. لم تُضف features أو تُعدّل المعمارية أو خوارزميات المعالجة في جلسة القبول هذه.

## البنية المنفذة

| الطبقة | الملف/المكوّن | المسؤولية |
|---|---|---|
| Engine contract | `pipeline/publikclip_pipeline/engine/` | `ProcessingEngine` و`PipelineEngine` وlifecycle وcheckpoint-backed results |
| Pipeline | `pipeline/publikclip_pipeline/` | ingest، ASR، diarization، events، candidates، scoring، camera، captions، render |
| Gateway | `gateway/` | auth، SQLite، upload/storage، workers، diagnostics، provider registry، secret boundary |
| Android contract | `android/app/src/main/java/com/example/data/contract/` و`remote/` | نماذج الحالة والعقد وGateway client للعميل الشخصي |
| Android orchestration | `RemoteProcessingCoordinator.kt`, `RemoteProcessingWorker.kt`, `GatewayProcessingWorker.kt` | unique WorkManager، upload، create/poll/cancel/retry/resume، download والتحقق |
| Android UI | `RemoteStudioScreens.kt`, `RemoteStudioViewModel.kt` وCompose screens | Home، import، processing، error، results، clip review، settings |
| Desktop | `app/src/` و`app/src-tauri/` | React/Tauri local CLI path وGateway adapter وreview/social UX |

يستخدم Android اختيار `video/*` مع persistable URI permission، حفظ الوظيفة محليًا، منع duplicate work عبر `ExistingWorkPolicy.KEEP`، وإعادة polling بعد restart. يدعم Gateway resumable upload عبر `/v1/sources/uploads` إضافة إلى one-shot `/v1/sources/upload` للتوافق، ويفحص outputs مع bytes وSHA-256 وintegrity metadata.

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

يُحفظ `local_job_id` و`remote_job_id` و`idempotency_key` قبل وبعد كل حد شبكي مهم. عند إغلاق التطبيق أثناء المعالجة يعاود WorkManager التنفيذ من الحالة المحفوظة، وعند `INTERRUPTED` يستدعي `/resume`. عند `FAILED` يظهر retry فقط عندما تكون `recoverable=true`. لا يرسل Android Gemini key؛ المفاتيح تبقى في Gateway/AI runtime.

## حواجز الإصدار

**P0:** لا يوجد جهاز Android حقيقي للاختبار، والمسار الكامل APK → Gateway → Pipeline → artifact → preview/edit/render-again/export غير مثبت.
**P1:** APK غير موقّع؛ Gemini غير مهيأ؛ اكتمال ASR/diarization/LLM production غير مثبت؛ private HTTPS/Docker deployment غير متاح في sandbox.
**P2:** large-media test skipped وdevice instrumentation غير مشغّل.
**P3:** deprecation warnings وصيانة namespace التاريخي موثقتان ولا تمنعان البناء.

هذه الحواجز ليست عيوبًا يجوز تجاوزها بإضافة mock success. لم يتم الادعاء بإغلاق P0/P1، ولم يُجرَ تغيير source code لإخفائها.

## كيفية إعادة التحقق

لإغلاق القبول، استخدم APK موقّعًا على جهاز Android حقيقي متصل مع USB debugging، واضبط Gateway خاصًا عبر HTTPS مع token، ثم نفّذ diagnostics قبل إنشاء job. يجب أن تكون pipeline وFFmpeg/storage وASR/diarization وLLM جاهزة. بعد ذلك أعد سيناريو `docs/FINAL_ACCEPTANCE.md` كاملًا، واجمع screenshots/logcat/job IDs/transition history وSHA-256 لكل artifact، ثم حدّث الحكم إلى PASS فقط بعد تحقق export وrender again من التطبيق نفسه.

## ملاحظة عن الوثائق

النسخة الأحدث من المستودع تحتوي الآن وثائق baseline المطلوبة مثل `docs/ARCHITECTURE.md` و`docs/AUDIT.md` و`docs/CONTRACTS.md` و`docs/PARALLEL_WORK.md`، إضافة إلى وثائق التشغيل والقبول `docs/FINAL_ACCEPTANCE.md` و`docs/RELEASE_BLOCKERS.md`. تم الحفاظ على هذه الملفات عند الدمج مع main البعيد.

## ملفات التسليم

- `docs/ARCHITECTURE.md`
- `docs/AUDIT.md`
- `docs/CONTRACTS.md`
- `docs/FINAL_ACCEPTANCE.md`
- `docs/RELEASE_BLOCKERS.md`
- `evidence/` وسجلات الاختبارات والـ smoke tests
- `android/app/build/outputs/apk/release/app-release-unsigned.apk` للاختبار الداخلي فقط
