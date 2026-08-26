# ISM QA Test Matrix

**الفرع:** `agent/qa`
**تاريخ التنفيذ:** 2026-08-26
**النطاق:** QA، الأمن، الأداء، Gateway، Python Pipeline، Android، والتكاملات المعلنة في وثائق المشروع.

## قرار الخروج

> **الحكم: NOT READY.** توجد اختبارات ناجحة كثيرة، لكن لا يجوز إعلان الجاهزية لأن اختبار Android الفعلي واختبار E2E الحقيقي من APK إلى Gateway إلى Pipeline إلى MP4 لم يُنفذا في هذه البيئة. كما بقيت قيود P0/P1 موثقة، منها غياب جهاز/محاكي Android، غياب Android SDK، وعدم توفر نماذج ASR وموفر LLM حقيقي. نجاح الاختبارات الحتمية باستخدام doubles يثبت العقد ولا يثبت جاهزية الإنتاج.

تعتمد هذه المصفوفة على اختبارات الكود الحالية والجديدة. كلمة **PASS** تعني أن الاختبار نفذ ونجح، و**BLOCKED** تعني أن الاختبار محدد وقابل للتشغيل لكنه يحتاج اعتمادًا أو جهازًا غير متاح، و**PARTIAL** تعني أن جزء العقد اختُبر دون المسار الإنتاجي الكامل.

## مصفوفة السيناريوهات

| المعرّف | المجال | السيناريو | الاختبار أو الدليل | النتيجة | ملاحظات القبول |
|---|---|---|---|---|---|
| UNIT-01 | Unit | parsing وvalidation للمدخلات | `pipeline/tests/test_qa_failure_modes.py`، اختبارات queue وrubric الحالية | PASS | تشمل invalid source وstable error codes. |
| UNIT-02 | Unit | checkpoint/cache فاسد | `pipeline/tests/test_stability_regressions.py` | PASS | يُعامل كـcache miss أو `StageError` آمن. |
| ENGINE-01 | Engine | فيديو صحيح ضمن عقد engine | `pipeline/tests/test_qa_failure_modes.py::test_valid_video_contract_completes_without_external_models` | PASS | عقد orchestration حتمي؛ ليس فيديو إنتاجيًا كاملًا. |
| ENGINE-02 | Engine | فيديو فاسد | `test_broken_video_is_a_stable_engine_error` | PASS | `MEDIA_INVALID` ولا يتحول إلى traceback عام. |
| ENGINE-03 | Engine | no audio | `test_no_audio_is_rejected_before_loading_the_model` و`test_asr_errors.py` | PASS | `ASR_AUDIO_INVALID` قبل تحميل النموذج. |
| ENGINE-04 | Engine | missing model/runtime | `test_missing_model_is_safe_and_retryable_after_valid_audio` | PASS | `ASR_MODEL_UNAVAILABLE`؛ توفر وزن حقيقي غير مثبت. |
| ENGINE-05 | Engine | FFmpeg failure | `test_ffmpeg_failure_does_not_create_a_successful_engine_result` واختبارات render regression | PASS | لا ينتج نجاحًا أو artifact وهميًا. |
| API-01 | API | health عام وsession خاص | `gateway/tests/test_qa_api_e2e.py` عبر ASGI | PASS | `/health` عام؛ `/v1/auth/session` يتطلب Bearer عند التفعيل. |
| API-02 | API | token مفقود أو غير صالح | `test_private_auth_fails_closed_when_required` | PASS | 401، و503 إذا كان الإعداد الإلزامي بلا token. |
| API-03 | API | protected capabilities وrequest ID | `test_protected_capabilities_never_echo_provider_secret` | PASS | request ID ثابت ولا تُعاد الأسرار أو المسارات الداخلية. |
| API-04 | API | malformed `Content-Length` و`Content-Range` | `test_malformed_content_length_is_http_error_not_500` و`test_malformed_content_range_offset_is_http_error_not_500` | PASS بعد الإصلاح | يعاد 400 بدل 500 غير مصنف. |
| API-05 | API | upload صحيح وفاسد | `gateway/tests/test_media_lifecycle.py` و`test_gateway_safety.py` | PASS | checksum وffprobe وatomic finalize؛ الفاسد 422. |
| API-06 | API | path traversal في media endpoints | `test_source_and_processing_media_paths_cannot_escape_authorized_root` | PASS | لا تُخدم ملفات خارج المجلد المصرح. |
| INT-01 | Integration | upload ثم complete ثم source mapping | `test_media_lifecycle.py` | PASS | يشمل interruption/resume وduplicate وchecksum cleanup. |
| INT-02 | Integration | job failure وerror persistence | `test_worker_failure_is_persisted_as_failed_and_not_requeued` | PASS بعد الإصلاح | الحالة durable تصبح `FAILED` مع `WORKER_FAILED`. |
| INT-03 | Integration | backend restart | `test_restart_history_records_actual_previous_state` | PASS بعد الإصلاح | `RENDERING → INTERRUPTED` يسجل الحالة السابقة الفعلية. |
| INT-04 | Integration | resume identity | `pipeline/tests/test_engine.py` واختبارات Gateway الحالية | PASS | تبقى هوية job وتُعاد checkpoints الصالحة. |
| INT-05 | Integration | cancel | `test_job_state.py` واختبارات queue/engine | PASS | الإلغاء durable ولا يعاد إحياؤه تلقائيًا. |
| INT-06 | Integration | retry | Gateway state tests و`FINAL_ACCEPTANCE.md` | PARTIAL | control-plane retry مثبت؛ retry مع نماذج/موفر حقيقي غير منفذ. |
| E2E-01 | E2E | APK → Gateway → Pipeline → MP4 | لا يوجد جهاز أو APK قابل للتشغيل في البيئة | BLOCKED P0 | شرط حاسم قبل الجاهزية. |
| E2E-02 | E2E | real short valid video | real pipeline needs WhisperX/weights/LLM | BLOCKED P0 | لا يجوز استبداله بـFake Engine في قبول الإنتاج. |
| E2E-03 | E2E | real long/large video | optional disk matrix + runtime benchmark | PARTIAL | sanity disk-backed بحجم 1 MiB نجح؛ مصفوفة 100/500/1025 MiB لم تُنفذ هنا. |
| E2E-04 | E2E | network interruption | Gateway/control-plane contract موجود | PARTIAL | سلوك WorkManager مع process recreation يحتاج Android device/emulator. |
| AND-01 | Android | routing للـHTTP/HTTPS وlocal URI | `android/.../ProcessingEngineTest.kt` و`QaProcessingResilienceTest.kt` | PASS (source-level) | يثبت القرار المنطقي فقط. |
| AND-02 | Android | restart/cancel/retry/network constraints | `PipelineWorkContractTest.kt` و`QaProcessingResilienceTest.kt` | PARTIAL | policy ثابتة؛ lifecycle الحقيقي محجوز لاختبار جهاز. |
| AND-03 | Android | unit test execution | `./gradlew :app:testDebugUnitTest --no-daemon` | BLOCKED P1 | `SDK location not found`. |
| AND-04 | Android | lint | `./gradlew :app:lint --no-daemon` | BLOCKED P1 | نفس غياب SDK. |
| AND-05 | Android | instrumentation/device restart | `androidTest` scaffold موجود | BLOCKED P0/P1 | لا يوجد ADB device أو emulator. |
| RES-01 | Resilience | insufficient storage | `gateway/tests/test_worker_queue.py` و`PersistentWorkerQueue.check_resources` | PASS | resource guard يرفض التشغيل عند free disk أقل من الحد. |
| RES-02 | Resilience | worker failure | QA regression test | PASS بعد الإصلاح | لا يبقى job queued مع status failed متضارب. |
| RES-03 | Resilience | backend restart | QA regression test | PASS بعد الإصلاح | transition history يعكس state الفعلية. |
| RES-04 | Resilience | Android restart | لا جهاز متصل | BLOCKED | يتطلب WorkManager/Room/device smoke. |
| RES-05 | Resilience | LLM unavailable | `test_asr_errors.py`، Gemini diagnostics، provider tests | PASS (contract) | real provider outage بعد الوصول إلى scoring غير مثبت. |
| RES-06 | Resilience | FFmpeg unavailable/failure | `test_stability_regressions.py` وGateway capability tests | PASS (contract) | binary production configuration غير متحقق على كل target. |

## تغطية الأمن

| الفئة | الحالة | الدليل |
|---|---|---|
| Private authentication | PASS | configured token، wrong token، missing token، fail-closed mode. |
| File validation | PASS | extension، size، checksum، ffprobe، non-empty output. |
| SSRF/DNS rebinding | PASS | private/loopback/link-local/reserved/multicast وDNS-resolved private address. |
| Path traversal | PASS | source وprocessing media paths مع containment check. |
| Command injection | PASS | command construction list-based، `shell` غير مستخدم، source لا يُمرر كسلسلة shell. |
| FFmpeg arguments | PASS | list arguments، timeout bounded، no shell execution. |
| Secrets | PASS | child environment mapping وredaction tests، provider vault tests. |
| Logs/errors | PARTIAL | API responses لا تكشف secrets؛ full production log sink غير متاح في sandbox. |
| Exposed endpoints | PASS (contract) | health public، private routes protected عند `REQUIRE_GATEWAY_TOKEN=true`؛ HTTPS deployment غير منفذ هنا. |

## قواعد إعادة الإنتاج والإصلاح

تمت إعادة إنتاج أربعة عيوب على الفرع: قيمة `X-Upload-Offset` غير الرقمية كانت ترفع `ValueError`، و`Content-Length` غير الرقمي كان يؤدي إلى 500، وworker callback كان يترك `state=QUEUED` مع `status=failed`، وrestart history كان يسجل `RUNNING` بدل state السابقة. عولجت هذه العيوب وأضيفت لها اختبارات regression مستقلة.

## أوامر التحقق

```bash
python3 -m pytest -q
python3 -m pytest -q -m 'not slow'
python3 -m pytest -q -m slow
RUN_LARGE_MEDIA_TESTS=1 MEDIA_TEST_SIZES_MB=1 \
  python3 -m pytest -q gateway/tests/test_media_lifecycle.py \
  -k large_size_matrix_is_available_for_real_storage_runs
cd android && ./gradlew :app:testDebugUnitTest --no-daemon
cd android && ./gradlew :app:lint --no-daemon
```

## مراجع المستودع

[1]: API-CONTRACT.md "ISM API Contract"
[2]: ENGINE_ARCHITECTURE.md "Processing Engine Architecture"
[3]: FINAL_ACCEPTANCE.md "Final acceptance baseline"
[4]: ../gateway/README.md "Gateway README"
[5]: ../android/README.md "Android README"
[6]: ENGINE_STABILITY.md "Pipeline stability report"
