# ISM QA Performance Report

**الفرع:** `agent/qa`
**تاريخ القياس:** 2026-08-26
**الحكم:** **قياسات عقدية ومحلية ناجحة، لكن لا يوجد benchmark إنتاجي كامل للنماذج أو Android؛ المشروع NOT READY للإطلاق.**

## منهج القياس

استُخدمت أداة `scripts/measure_pytest.py` التي تشغّل الاختبار في child process وتقيس wall-clock time و`ru_maxrss` للعملية وأبنائها. القياس لا يغيّر المنتج ولا يثبت سقفًا عامًا لكل فيديو؛ خصوصًا أن render smoke يشمل كلفة FFmpeg، بينما ASR/diarization/LLM الثقيلة لم تُشغّل بسبب غياب runtime والأوزان والاعتمادات الخارجية.

> لا تُجرى أي performance optimization قبل وجود baseline قابل لإعادة الإنتاج. في هذه الجلسة جرى القياس أولًا، ثم أضيفت الاختبارات والإصلاحات الوظيفية، ولم تُجرَ تحسينات أداء.

## نتائج benchmark

| الحمل | الأمر | النتيجة | الزمن | أقصى RSS | الحالة |
|---|---|---:|---:|---:|---|
| Full Python suite | `python3 scripts/measure_pytest.py -q` | 173 passed، 1 skipped | 19.99 s | 767,308 KB | PASS |
| Non-slow suite | `python3 scripts/measure_pytest.py -q -m 'not slow'` | 172 passed، 1 skipped، 1 deselected | 9.46 s | 224,816 KB | PASS |
| Render smoke | `python3 scripts/measure_pytest.py -q -m slow` | 1 passed، 173 deselected | 14.52 s | 766,948 KB | PASS |
| Large disk-backed sanity | `RUN_LARGE_MEDIA_TESTS=1 MEDIA_TEST_SIZES_MB=1 ...` | 1 passed | نحو 1 s | غير مفصول | PASS |
| Large 100/500/1025 MiB matrix | documented opt-in command | لم تُنفذ | — | — | BLOCKED/DEFERRED |
| Real model load/transcribe | WhisperX + weights | لم تُنفذ | — | — | BLOCKED P0 |
| Real Gemini scoring | server credential/provider | لم تُنفذ | — | — | BLOCKED P0 |
| Android unit/lint | Gradle tasks | لم تبدأ بسبب غياب SDK | — | — | BLOCKED P1 |

القيمة الأعلى للـRSS في full suite مصدرها render smoke وFFmpeg، وليست حدًا مضمونًا لملف إنتاجي. وتشير قيمة non-slow إلى كلفة الاختبارات العقدية فقط، لا إلى كلفة المعالجة.

## بيئة القياس

| المورد | القيمة المرصودة | دلالة الاختبار |
|---|---:|---|
| Python | 3.12.3 | يطابق نطاق pipeline المعلن. |
| CPU | 6 logical CPUs | لا يوجد benchmark لتوازي jobs؛ الإعداد الافتراضي worker واحد. |
| RAM | 3.8 GiB total، نحو 2.9 GiB available وقت الفحص | لا يكفي لاستنتاج production ceiling للنماذج. |
| GPU | غير متاح | لا يمكن قياس GPU memory أو CUDA/Metal path. |
| FFmpeg | 6.1.1 | render/probe smoke متاحان محليًا. |
| Android SDK | غير متاح | Gradle unit/lint blocked. |
| Java compiler | `javac` غير متاح | قد يكون blocker إضافيًا بعد توفير SDK. |

## تغطية الموارد المطلوبة

### RAM

تغطي الاختبارات الحالية عدم اعتماد suite الخفيفة على نماذج ML، وتقيس RSS على مستوى process tree. ويظل الخطر الأكبر في ASR وdiarization وevents وcamera؛ إذ إن تحميل الصوت أو الإطارات أو الأوزان قد يرفع الذروة فوق ما قيس في smoke. لا يوجد في الكود الحالي memory budget أو hard admission control خاص بكل stage.

### CPU وGPU

يستخدم ASR المحلي مسار CPU بحسب العقد الحالية، ولم تتوفر GPU في بيئة القياس. لم يُقَس throughput، ولا أثر التوازي، ولا معدل استخدام CPU لكل مرحلة. لذلك لا يمكن اعتماد `MAX_ACTIVE_PROCESSING_JOBS` أعلى من الواحد بناءً على هذه النتائج.

### Model loading

لم تُحمّل WhisperX أو SpeechBrain/CAMPPlus أو PANNs أو نماذج vision، ولم تُقَس download/cache warm-up أو cold-start. اختبارات `ASR_MODEL_UNAVAILABLE` وprovider health تثبت التصنيف الآمن، لا زمن أو صحة نموذج حقيقي.

### Total processing time وrender time

الـfull suite استغرقت 19.99 ثانية، واختبار render smoke 14.52 ثانية مع child RSS قريب من 767 MB. هذه أرقام اختبار fixture صغير وليست SLA. يجب قياس مصدر قصير ومتوسط وطويل حقيقي مع cache cold/warm، وتسجيل زمن كل stage، وعدد المرشحين، وLLM latency، وزمن render، وزمن التنزيل والرفع.

### Disk usage

يكتب resumable upload إلى disk chunk-by-chunk بدل تحميل الملف كاملًا إلى RAM، وتوجد resource guard عبر `MIN_FREE_DISK_GB`. نجح sanity disk-backed بحجم 1 MiB. لم تُنفذ 100/500/1025 MiB في هذه الجلسة؛ لا يجوز الادعاء بسلامة سقف 2 GiB أو مساحة النتائج قبل تشغيلها على host النشر مع قياس peak temporary usage.

## Benchmark protocol قبل الاعتماد

يجب على بيئة النشر تشغيل البروتوكول التالي مع فيديوهات يملك المستخدم حق معالجتها، ومن دون طباعة أي token:

```bash
python3 scripts/measure_pytest.py -q
python3 scripts/measure_pytest.py -q -m 'not slow'
python3 scripts/measure_pytest.py -q -m slow
RUN_LARGE_MEDIA_TESTS=1 MEDIA_TEST_SIZES_MB=100,500,1025 \
  python3 -m pytest -q gateway/tests/test_media_lifecycle.py \
  -k large_size_matrix_is_available_for_real_storage_runs
```

ولكل تشغيل حقيقي يجب تسجيل `source_duration_seconds`, `source_bytes`, `output_bytes`, `stage_seconds`, `total_seconds`, `render_seconds`, `peak_rss_mb`, `free_disk_before_mb`, `free_disk_after_mb`, `model_cache_state`, و`provider_latency_ms`. يجب تكرار كل حالة cold وwarm مرتين على الأقل، ورفض benchmark إذا كان القياس يتضمن swap أو throttling غير موثق.

## بوابات اعتماد مقترحة وليست نتائج حالية

لا تُعامل القيم التالية كـPASS حتى تُقاس على جهاز الإنتاج. يجب تحديد ميزانية RAM لكل profile، وحدًا أعلى للمدة والحجم، وحدًا أقصى للزمن الكلي، وحدًا منفصلًا للرندر، وحدًا لاستدعاءات LLM، وحدًا أدنى للمساحة الحرة. كما يجب تحديد سلوك واضح عند تجاوز أي ميزانية: رفض قبل البدء أو failure قابل للاستئناف، لا قتل صامت أو نجاح جزئي غير معلن.

| بوابة | القياس المطلوب | نتيجة هذه الجلسة |
|---|---|---|
| Memory | peak RSS لكل profile وstage | غير متاح للنماذج؛ smoke فقط PASS |
| CPU | utilization وthroughput لكل stage | غير مقاس |
| GPU | device memory وutilization إن توفر | غير متاح |
| Cold model load | أول تشغيل مع cache فارغ | غير منفذ |
| Warm model load | تشغيل لاحق مع cache | غير منفذ |
| Total processing | end-to-end real MP4 | P0 blocked |
| Render | real artifact وffprobe validation | smoke PASS، production blocked |
| Disk | peak temp/source/output footprint | 1 MiB sanity PASS؛ matrix الكبيرة deferred |

## قيود وأولوية الإصدار

غياب benchmark حقيقي للنماذج وAndroid ليس سببًا لتخفيض الاختبارات أو زيادة التوازي. هو **P0/P1 release blocker** لأن النظام قد يكون صحيح العقد لكنه غير قابل للتشغيل ضمن RAM/disk/time المتاحة. يجب أولًا توفير host مطابق للإنتاج، Android SDK/device، نموذج cache وسياسة credentials، ثم إعادة تشغيل protocol وحفظ النتائج مع commit أو artifact مستقل.

## مراجع داخل المستودع

[1]: ../scripts/measure_pytest.py "Reproducible pytest resource measurement"
[2]: ENGINE_STABILITY.md "Pipeline stage stability and known performance limits"
[3]: ../gateway/worker_queue.py "Worker resource guard and artifact validation"
[4]: MEDIA_PIPELINE.md "Disk-backed media lifecycle"
[5]: ../gateway/DEPLOYMENT.md "Gateway deployment resource requirements"
