# TEST HANDOFF

## نطاق التسليم

هذا التسليم مملوك لنطاق **TESTING** فقط. يغطي الخطة والاختبارات الآلية التي يمكن تشغيلها محلياً عبر **ENGINE** و**BACKEND** و**ANDROID** و**END-TO-END**. لم تتم إعادة كتابة أي functionality في المنتج، ولم تُعدّل ملفات Android أو gateway أو pipeline الموجودة؛ أُضيفت اختبارات مستقلة فقط، إضافة إلى هذا المستند.

> معيار هذه الجلسة: لا يُسجّل bug إلا بعد إعادة إنتاجه من خلال اختبار حتمي، ولا يُصلح كود المنتج إلا إذا ثبت أن السبب في functionality وليس في بيئة التشغيل أو في الاختبار نفسه.

## الحالة التنفيذية

| المجال | الاختبارات المنفذة | النتيجة | الملاحظات |
|---|---:|---|---|
| ENGINE | اختبارات engine الحالية مع اختبارات resilience الجديدة | ناجح | checkpoints، الفشل، الاستئناف، الإلغاء، وعقود الوسائط مغطاة |
| BACKEND | اختبارات gateway الحالية مع اختبارات resilience الجديدة | ناجح | API state machine، صحة التبعيات، worker، LLM، FFmpeg، والاستعادة مغطاة |
| ANDROID | `./gradlew test --no-daemon` | محجوب بيئياً | Gradle يعمل، لكن Android SDK غير موجود في sandbox؛ لا يُعد ذلك فشل functionality |
| END-TO-END | إنشاء job ثم polling محلياً عبر عقد gateway مع worker/provider mocks | ناجح | لا يستدعي LLM أو model weights أو خدمة خارجية حقيقية |
| Regression suite | `python3 -m pytest -q` | **147 passed, 5 warnings** | التحذيرات تشمل deprecated FastAPI lifecycle ووسم `slow` غير مسجل |

تم تحديث clone إلى أحدث `origin/main` عبر fast-forward قبل التشغيل النهائي. آخر commit upstream المرئي أثناء الجلسة هو `258fd3a`; لذلك لا ينبغي افتراض أن هذا handoff يحتوي تغييرات جلسات أخرى لم تصل إلى `origin/main` بعد.

## الاستراتيجية حسب الطبقة

### ENGINE

يُختبر الـ Engine على مستوى العقود الحتمية قبل إدخال تكلفة النماذج أو الشبكة. تبدأ الدورة بقبول فيديو صالح، ثم التحقق من probe، ثم رفض الحالات التي لا تحتوي video stream أو التي يعجز `ffprobe` عن قراءتها. يُختبر الفيديو الصامت باعتباره فيديو صالحاً مع `has_audio=false`، ولا يُسمح للاختبار باختلاق مسار صوت.

تُختبر إدارة الوظائف بمرحلة ناجحة تُحفظ في checkpoint، ومرحلة فاشلة قابلة للاستئناف، وتأكيد أن الاستئناف يتجاوز المرحلة المكتملة ولا يعيد تشغيلها. كما يُختبر إلغاء job عبر marker دائم، ورفض العمليات غير الصالحة على jobs المكتملة. توجد اختبارات Engine upstream في [`pipeline/tests/test_engine.py`](pipeline/tests/test_engine.py)، واختبارات الوسائط والعزل في [`pipeline/tests/test_engine_resilience_contract.py`](pipeline/tests/test_engine_resilience_contract.py).

### BACKEND

تُختبر واجهات gateway بعقود الحالة canonical/legacy، مع التحقق من أن missing model وغياب LLM وغياب FFmpeg تؤدي إلى error code أو HTTP status ثابت وقابل للعرض للمستخدم، لا إلى exception داخلي غير مصنف. يُختبر worker من حيث deduplication، فشل handler، resource guard، وإيقاف queue.

تُختبر دورة job التالية: `QUEUED` ثم فشل قابل للاسترداد، `resume` مرة واحدة، رفض `resume` المكرر، و`CANCELLED` كحالة نهائية idempotent. كما تُختبر استعادة backend: job كان قيد التنفيذ عند startup يتحول إلى `INTERRUPTED` مع `recoverable=true` ورسالة checkpoint-resume. المصدر المرجعي لسلوك endpoint هو [`gateway/main.py`](gateway/main.py)، والاختبارات الجديدة في [`gateway/tests/test_resilience_contract.py`](gateway/tests/test_resilience_contract.py).

### ANDROID

تتكون بوابة Android المطلوبة من ثلاثة مستويات. المستوى الأول unit tests لمحرك routing، صلاحية source URI، gateway URL، ونموذج حالات job. المستوى الثاني اختبار WorkManager وRoom للتأكد من بقاء job وcheckpoint بعد process death أو إعادة تشغيل التطبيق، مع عدم تحويل job الملغى إلى running تلقائياً. المستوى الثالث اختبار device/emulator لانقطاع الشبكة أثناء upload أو polling، ثم عودة الاتصال وإعادة المزامنة دون إنشاء job مكرر.

الاختبار الآلي الموجود في sandbox لم يصل إلى تنفيذ Android بسبب غياب SDK: Gradle فشل عند تحديد `sdk.dir` أو `ANDROID_HOME`. يلزم تشغيل الأمر نفسه على runner يحوي Android SDK، ثم إضافة connected tests على emulator/device لتغطية **Android restart** وnetwork interruption فعلياً. هذا dependency بيئي وليس bug مثبتاً في Android functionality.

### END-TO-END

يبدأ الاختبار من source URL موقّع داخل gateway، ينشئ processing job، يتحقق من response shape (`id`, `status`, `state`, `correlation_id`)، ثم يجري polling ويؤكد وجود transition history. تُستخدم fake worker وprovider mocks لعزل LLM وFFmpeg وmodel downloads، بينما تُختبر قاعدة البيانات والحالة والحدود بين API وqueue فعلياً.

في بيئة release يجب إضافة مسار E2E حقيقي بملف MP4 صغير صالح، وملف صامت، وملف كبير قريب من حد الرفع، ثم تشغيل gateway وpipeline وFFmpeg الحقيقيين. ويجب تكرار المسار مع إيقاف backend أثناء job، قتل Android process، وفصل الشبكة ثم إعادة الاتصال.

## مصفوفة السيناريوهات المطلوبة

| السيناريو | مستوى الاختبار | الاختبار/الدليل | النتيجة الحالية |
|---|---|---|---|
| valid video | ENGINE | `test_valid_video_probe_reports_video_and_audio` | PASS |
| invalid video | ENGINE | `test_invalid_video_without_video_stream_is_rejected` | PASS |
| large video | ENGINE/BACKEND | `test_large_video_job_keeps_checkpoint_contract` و`test_large_and_invalid_artifacts_are_rejected_or_accepted_by_size_and_container` | PASS؛ استخدم sparse file بحجم 64 MiB لتجنب استهلاك مساحة غير لازم |
| no audio | ENGINE | `test_no_audio_video_is_identified_without_fabricating_audio` | PASS |
| broken media | ENGINE | `test_broken_media_from_ffprobe_is_reported_as_ffmpeg_error` | PASS |
| missing model | ENGINE/BACKEND | `test_missing_model_is_explicitly_not_present` | PASS؛ لم يحدث download حقيقي |
| LLM unavailable | BACKEND/ENGINE | `test_llm_unavailable_has_stable_error_code` | PASS؛ `GEMINI_NOT_CONFIGURED` |
| FFmpeg unavailable | BACKEND/ENGINE | `test_ffmpeg_unavailable_returns_503_before_job_creation` | PASS؛ `503` و`FFMPEG_UNAVAILABLE`، ولا يُنشأ job |
| job failure | ENGINE/BACKEND | `test_failure_is_converted_to_stable_engine_error_and_resume_skips_checkpoint` واختبارات state الحالية | PASS |
| job resume | ENGINE/BACKEND | `test_checkpoint_is_reused_by_resume` و`test_job_failure_can_be_resumed_once_and_duplicate_resume_is_rejected` | PASS |
| job cancellation | ENGINE/BACKEND | `test_cancel_and_resume_preserve_job_identity` و`test_job_cancellation_persists_terminal_state` | PASS |
| backend restart | BACKEND | `test_backend_restart_marks_inflight_job_interrupted` | PASS |
| Android restart | ANDROID | يتطلب `connectedAndroidTest` على emulator/device | BLOCKED حتى توفير SDK وdevice |
| network interruption | BACKEND/ENGINE | `test_network_interruption_is_classified_without_leaking_provider_error` | PASS؛ `GEMINI_NETWORK_ERROR` |

## بيانات الاختبار والعزل

| نوع البيانات | طريقة الإنشاء | الغرض |
|---|---|---|
| Probe صالح | JSON ثابت يحاكي video stream وaudio stream | قياس metadata دون binary أو download |
| فيديو صامت | JSON ثابت يحوي video stream فقط | التحقق من `has_audio=false` |
| فيديو غير صالح | audio-only probe | رفض input بلا video stream |
| وسائط تالفة | `FfmpegError` يحاكي فشل ffprobe | التأكد من تصنيف corruption |
| فيديو كبير | sparse file بحجم 64 MiB | اختبار الحجم وcheckpoint دون تخزين payload حقيقي |
| model مفقود | `PUBLIKCLIP_HOME` معزول وملف model غير موجود | منع silent fallback أو download غير مقصود |
| LLM/network | HTTP mocks و`httpx.ConnectError` | تثبيت error codes وعدم كشف provider exception |
| job failure/restart | SQLite مؤقتة وtransitions حتمية | اختبار durability دون DB الإنتاجية |

## أوامر التشغيل

من جذر المستودع:

```bash
python3 -m pytest -q
```

لتشغيل الاختبارات الجديدة فقط:

```bash
python3 -m pytest -q \
  pipeline/tests/test_engine_resilience_contract.py \
  gateway/tests/test_resilience_contract.py
```

لتشغيل Android على runner يحوي SDK:

```bash
cd android
./gradlew test --no-daemon
./gradlew connectedAndroidTest --no-daemon
```

يجب توفير `ANDROID_HOME` أو `android/local.properties` قبل الأمر الأخير، مع emulator/device لاختبارات process restart وnetwork toggling. لا تُشغّل connected tests على sandbox الحالي قبل توفير هذه المتطلبات.

## bugs والإصلاحات

لم تُثبت جلسة الاختبار أي bug في **product functionality**. لذلك لم يُعدّل أي ملف production، ولم تُنشأ إصلاحات وهمية أو تغييرات احتياطية بلا reproduction. لا يوجد bug يحتاج إلى سلسلة `reproduce → fix → regression test` في هذا التسليم؛ اختبارات regression الموجودة تثبت العقود الحالية كما هي.

واجهت الجلسة في البداية مشكلة جمع اختبارية بحتة لأن ملفي اختبار جديدين كان لهما الاسم نفسه في مجلدين مختلفين. أُعيدت تسمية ملف pipeline إلى `test_engine_resilience_contract.py`، ثم أصبحت المجموعة الجديدة ناجحة (`15 passed`) وأصبحت المجموعة الكاملة بعد مزامنة upstream ناجحة (`147 passed`). هذا إصلاح لاسم test module وليس إصلاحاً في functionality، ولذلك لا يُسجّل كـ product bug.

## التحذيرات والحدود المفتوحة

الـ regression suite ناجحة مع خمسة warnings: تحذيران lifecycle قديمان في FastAPI، وتحذيران تابعان لهما، وتحذير `pytest.mark.slow` غير مسجل. هذه التحذيرات لا تكسر الاختبارات، لكنها مرشحة لتنظيف مستقل في جلسة تملك quality tooling أو gateway configuration؛ لم تُعدّل هنا لأن الطلب محصور في TESTING ولأنها ليست failures مثبتة.

اختبارات LLM وFFmpeg والنماذج الجديدة contract-level ومُعزولة. وهي تثبت state وerror handling، لكنها لا تثبت توافق إصدار provider أو صحة model weights أو قدرة FFmpeg على subtitle rendering في كل منصة. يلزم release gate حقيقي منفصل لهذه الوظائف.

## اعتماديات وتسليم بين الجلسات

لا تعتمد ملفات الاختبار الجديدة على تعديل من جلسة أخرى. ومع ذلك، تعتمد تغطية Engine على التغييرات upstream الموجودة في `258fd3a`، وخصوصاً [`pipeline/publikclip_pipeline/engine`](pipeline/publikclip_pipeline/engine) و[`pipeline/tests/test_engine.py`](pipeline/tests/test_engine.py). كما أن تغطية Android restart لا يمكن إكمالها حتى توفر جلسة Android/CI Android SDK وemulator أو device.

عند الدمج، يجب التأكد من أن commit هذه الجلسة لا يُستخدم كبديل عن commits جلسات Android أو backend الأخرى، وأن أي تعارض في الاختبارات يُحل بالحفاظ على اختبارات كل جلسة وعدم overwrite لملفات غير مملوكة.

## المراجع داخل المستودع

[1]: pipeline/publikclip_pipeline/engine/pipeline.py "Pipeline Engine lifecycle"
[2]: pipeline/publikclip_pipeline/jobs/queue.py "Durable job queue and checkpoints"
[3]: gateway/main.py "Gateway processing API and startup recovery"
[4]: android/app/src/main/java/com/example/domain/pipeline/PipelineWork.kt "Android WorkManager pipeline contract"
[5]: .github/workflows/quality-gate.yml "Repository quality gate workflow"
