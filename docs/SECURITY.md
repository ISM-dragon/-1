# ISM Security QA Report

**الفرع:** `agent/qa`
**تاريخ المراجعة:** 2026-08-26
**الحكم:** **NOT READY FOR PUBLIC PRODUCTION**.

## النطاق والحكم الأمني

شملت المراجعة Gateway وPython Pipeline وAndroid client وحدود رفع وخدمة الوسائط، المصادقة الخاصة، SSRF، path traversal، subprocess/FFmpeg، الأسرار، الأخطاء والسجلات، ونقاط النهاية المكشوفة. لم تُستخدم بيانات اعتماد حقيقية ولم تُرسل ملفات أو أسرار إلى موفر خارجي. اختبارات provider وLLM تستخدم doubles أو تحققًا من العقد، ولذلك لا تُعد بديلًا عن اختبار نشر فعلي خلف HTTPS.

> القاعدة الأمنية الأساسية: **وجود إعداد أو مفتاح أو endpoint لا يساوي جاهزية تشغيلية**. يجب أن يفشل النشر العام عند غياب token أو HTTPS أو runtime حقيقي بدل إظهار نجاح وهمي.

## نموذج التهديد

التهديدات الأساسية هي عميل غير مصرح له يحاول قراءة أو تعديل jobs، مصدر فيديو remote يحاول استغلال SSRF أو DNS rebinding، اسم ملف يحاول الخروج من جذر التخزين، input يحاول الوصول إلى shell من خلال source أو captions أو mode، ملف وسائط فاسد أو كبير يستنزف التخزين، وموفر AI أو FFmpeg متعطل يعيد traceback أو سرًا في response/log. كما تشمل المخاطر تسريب Gateway token أو provider keys من Android أو JSON أو command line أو diagnostics.

| الأصل | نقطة الهجوم | الضابط | دليل الاختبار |
|---|---|---|---|
| Jobs ونتائج المعالجة | API الخاص | Bearer token وdevice/session policy | `test_qa_security_resilience.py` و`test_qa_api_e2e.py` |
| ملفات المصدر وMP4 | upload/media routes | size/checksum/ffprobe وpath containment | `test_media_lifecycle.py` وQA security tests |
| Gateway host | remote source URL | HTTP(S) فقط ورفض private/loopback/link-local/reserved/multicast وDNS-resolved private IP | `test_dns_rebinding_to_private_address_is_rejected` |
| Pipeline subprocess | source/options/FFmpeg | argument lists، لا `shell=True`، timeout bounded | `test_pipeline_arguments_are_structured_and_do_not_use_shell` |
| Provider credentials | diagnostics/child process/Android | server-side environment/vault، redaction، لا key في payload/URL/CLI | `test_gemini_gateway.py` وprovider registry tests |
| State durability | worker/restart | SQLite transitions وFAILED/INTERRUPTED state | lifecycle regression tests |

## نتائج الاختبار

| الفئة | النتيجة | التفسير |
|---|---:|---|
| Private authentication | PASS | token صحيح/خاطئ/مفقود، وrequired-without-configured يعيد 503 بدل السماح الصامت. |
| File validation | PASS | size، extension، checksum، ffprobe، existence، output minimum size. |
| Path traversal | PASS | source وprocessing media path لا يخرجان من root المصرح. |
| SSRF | PASS | hostname resolution يفحص العناوين الناتجة، بما فيها private DNS answer. |
| Command injection | PASS | أوامر Pipeline وFFmpeg قوائم arguments ولا تستخدم shell. |
| FFmpeg arguments/failure | PASS | timeout محدد وفشل probe مصنف؛ لا artifact نجاح وهمي. |
| Secrets | PASS على مستوى العقد | لا تُعاد المفاتيح في health/capabilities/diagnostics، وchild environment يعزل legacy key. |
| Logs/errors | PARTIAL | responses حتمية وآمنة في الاختبارات؛ لم يُختبر sink إنتاجي أو reverse proxy حقيقي. |
| Exposed endpoints | PASS مشروط | private routes محمية عند تفعيل `REQUIRE_GATEWAY_TOKEN=true`؛ التعرض العام وTLS يحتاجان deployment test. |

## عيوب أُعيد إنتاجها وأُصلحت

أعاد اختبار malformed upload إنتاج 500 غير مصنف عندما كانت قيمة `Content-Length` غير رقمية، وأُصلح parsing ليعيد 400. وأعاد اختبار resumable upload إنتاج `ValueError` من `X-Upload-Offset` غير الرقمي مع `Content-Range` صحيح، وأُصلح ليعيد 400. كما كشف اختبار worker failure تناقضًا بين `status=failed` و`state=QUEUED`؛ أصبح callback يثبت `FAILED` مع `WORKER_FAILED` و`recoverable=true`. وأخيرًا كشف restart test أن transition history تسجل `RUNNING` دائمًا بدل state السابقة؛ أصبحت تسجل القيمة الفعلية مثل `RENDERING`.

كل إصلاح مرتبط باختبار regression في `gateway/tests/test_qa_security_resilience.py`، ولم تُنفذ أي feature جديدة.

## الضوابط المطلوبة قبل الإنتاج

يجب تشغيل Gateway خلف HTTPS مع `REQUIRE_GATEWAY_TOKEN=true` وtoken عشوائي طويل، وإبقاء المنفذ الداخلي غير مكشوف، وضبط `CORS_ORIGINS` صراحة، وتخزين provider/OAuth secrets في vault أو environment آمن بصلاحيات مناسبة. يجب أيضًا تشغيل اختبارات endpoint عبر reverse proxy، واختبار redirect-following downloader إن أُضيف لاحقًا مع إعادة فحص كل redirect وDNS resolution.

يجب أن تبقى diagnostics محدودة بالـstatus والـcode والـlatency، وألا تسجل Authorization header أو provider payload أو filesystem path خاصًا أو traceback خامًا. يجب مراجعة logs في deployment فعليًا، لا الاكتفاء بمرور unit tests.

## حدود أمنية لم تُغلق في هذه الجلسة

لا يوجد APK/device E2E يثبت أن token لا يظهر في logs أو network traces أثناء دورة Android كاملة، ولا يوجد اختبار HTTPS/reverse-proxy فعلي. كما أن live OAuth/provider adapters خارج mock mode ليست جاهزة، ولا ينبغي اعتبار وجود `/v1/social/*` دليلًا على صلاحية النشر الحقيقي. لذلك تبقى هذه البنود **P0/P1 release blockers** وليست تحسينات اختيارية.

## أوامر الأمن

```bash
python3 -m pytest -q gateway/tests/test_qa_security_resilience.py
python3 -m pytest -q gateway/tests/test_qa_api_e2e.py
python3 -m pytest -q gateway/tests/test_gateway_safety.py gateway/tests/test_gemini_gateway.py gateway/tests/test_provider_registry.py
python3 -m compileall -q gateway pipeline/publikclip_pipeline

git ls-files | grep -Ei '(^|/)(\.env|.*secret.*|.*\.key$|.*\.pem$|.*credentials.*)' || true
```

الملفات `*.env.example` المتعقبة هي قوالب بلا أسرار؛ يجب أن تبقى ملفات runtime الحقيقية خارج Git وبصلاحيات مقيدة.

## مراجع داخل المستودع

[1]: ../SECURITY.md "Repository security boundary"
[2]: API-CONTRACT.md "API contract"
[3]: ../gateway/README.md "Gateway security and deployment"
[4]: ../gateway/processing_service.py "Pipeline environment and command boundary"
[5]: ../gateway/main.py "Gateway validation and media boundary"
[6]: ai/GEMINI_PROVIDER_AUDIT.md "Gemini provider audit"
