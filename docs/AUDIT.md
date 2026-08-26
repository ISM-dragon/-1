# ISM / PublikClip — Repository Audit

**الدور:** Agent 01 — ARCHITECT + AUDIT
**المستودع:** `ISM-dragon/-1`
**نطاق الفحص:** Python pipeline، Engine، Gateway وbackend، Android، Tauri/React، FFmpeg، النماذج، ASR، diarization، scoring، camera، rendering، checkpoints، jobs، dependencies، packaging، CI، والأرشيف المرجعي المرفق `whisper.cpp-master.zip`.
**حكم التدقيق:** المستودع غني بالوظائف ويحتوي نواة قابلة لإعادة الاستخدام، والمسار العملي الصحيح هو Android remote إلى Gateway خاص يشغل Engine Python. عينة whisper.cpp مفيدة كمرجع JNI/benchmark محدود، لكنها لا تبرر نقل ASR المحلي أو نماذج GGML إلى APK، ولا يوجد بعد دليل جهاز حقيقي يثبت release end-to-end.

## الملخص التنفيذي

الموجود فعليًا هو ثلاثة مسارات متداخلة وليست منتجًا واحدًا متجانسًا: تطبيق desktop مبني بـReact/Tauri يشغل Python/uv محليًا، تطبيق Android native بـKotlin يملك مسارًا remote إلى Gateway، وطبقتا backend متوازيتان هما `gateway/` و`backend/`. الـPython pipeline نفسها تحتوي Engine contract v1 وترتيبًا واضحًا من ingest إلى render مع checkpoint/resume. أما whisper.cpp المرفق فيضيف عينة Java/JNI محلية للـASR فقط، لا job engine ولا video pipeline. هذه النواة هي أقوى جزء في المشروع ويجب أن تبقى على خادم خاص [1] [2] [3].

الجاهزية الفعلية لا تساوي إعلان production كاملًا. بعد تثبيت اعتماديات `gateway/` و`pipeline/` في بيئة التدقيق، نجحت suite الموحدة بنتيجة 164 اختبارًا ناجحًا واختبار واحد متخطى، لكن build/device E2E وGateway حقيقي ومزودات الإنتاج لم تُثبت في هذه الجلسة. لا تزال عينة whisper.cpp خارج APK، ولا توجد مقارنة أداء مبررة مع WhisperX. النشر الاجتماعي الحقيقي غير منفذ؛ mock OAuth وmock publisher هما development-only، وDocker/compose يقدمان أساس نشر لا إثبات تشغيل end-to-end. لذلك لا ينبغي إعلان المشروع “production ready” قبل smoke test حقيقي وتوقيع release ومراجعة device recovery.

## 1. ما الموجود فعلًا؟

| المسار | الموجود في الكود | درجة النضج الحالية |
|---|---|---|
| Python Engine | `PipelineEngine`، contracts v1، lifecycle، progress، cancel، resume، results، clip render | **قابل لإعادة الاستخدام**؛ يعتمد على runtime ثقيل وملفات job/checkpoints |
| Python pipeline | ingest/yt-dlp، FFmpeg probe/normalize، WhisperX ASR/alignment، CAM++ diarization، PANNs/laughter/DSP events، candidate windows، LLM/deterministic scoring، active-speaker/face camera، ASS captions، FFmpeg render | **الوظيفة الأساسية موجودة**، لكن first-run يحتاج Python 3.12 ونماذج وCPU/disk وشبكة |
| Gateway | FastAPI، bearer auth اختياري حسب env، SQLite، upload sessions، source downloads، worker queues، processing jobs، diagnostics، provider registry، secret vault، media serving | **أفضل backend للمسار المستهدف**، لكن يحتاج hardening وتوحيد error contract واختبار deployment |
| Backend البديل | FastAPI آخر، DB/storage، device binding، JobManager، adapter إلى CLI، results/download/render | **اختبار/stack بديل**؛ ليس ما يستعمله Android الحالي |
| Native Android | Compose، Room، WorkManager، Media3، local URI import، remote Gateway client، notifications | **عميل remote قابل للبناء نظريًا**؛ لا parity محلي مع Python ولا حاجة إلى whisper.cpp في المسار الأساسي |
| Tauri/React | واجهة Studio/Review/Social/Analytics/Providers/Source Library، Tauri commands، local checkpoints/secrets، remote Gateway calls | **Desktop path هو الأكثر تكاملًا**؛ Android Tauri generated مسار ثالث يجب عدم خلطه بالnative Android |
| Social/OAuth | provider registry وواجهات وحالات وسياسة نشر، mock account/OAuth/publisher، Instagram feedback loop محلي | **التصميم جزئي؛ live adapters غير منفذة** |
| Packaging/CI | npm build، Windows NSIS workflow، Android CI workflow، Dockerfile وcompose، resource staging لـTauri | **desktop CI موثق؛ Android build/device وdeployment الفعليان يحتاجان بيئة release كاملة** |

## 2. ما يعمل فقط في development؟

وضع `PROVIDER_MODE=mock` هو development-only: مسارات `/v1/accounts/mock` و`/oauth/mock/complete` و`provider_publish` تنشئ حسابًا ورابطًا وهميين ولا تنشر على Instagram أو Facebook أو TikTok أو YouTube أو X. حتى وجود credentials للمنصات لا يكمل OAuth؛ المسارات الحية تعيد `501` لأن adapters والمراجعة والصلاحيات غير منفذة [4].

تشغيل Tauri المحلي يعتمد على `uv` ومجلد `pipeline/` من checkout الحالي، بينما الحزمة المكتبية تحاول نسخ pipeline ونسخة host من `uv` إلى resources. هذا مسار desktop، وليس Android runtime. كذلك Instagram feedback loop وOAuth المحلي في `pipeline/insights/instagram.py` وCLI/Tauri لا ينبغي اعتباره backend خاصًا لتطبيق Android.

المسار المحلي داخل `ProductionVideoPipeline.kt` يعمل بكود Kotlin مختلف: عند غياب Gateway يستخدم AI providers المحلية أو Gemini video analysis، وقد يؤجل clip selection/scoring إلى Gemini عندما لا توجد كلمات زمنية. هذا ليس تشغيلًا لـWhisperX/PyTorch ولا parity مع Python، ولذلك هو fallback/experimental لا المرجع الإنتاجي لمسار Android [5].

## 3. لماذا لا يعمل التطبيق الحالي على جهاز نظيف؟

المشكلة ليست سببًا واحدًا، بل سلسلة dependencies وقرارات هوية ومسارات متنافسة. على سطح المكتب، أول تشغيل يحتاج Node/Rust للبناء أو حزمة بنيت مسبقًا، ثم Python 3.12/uv، تنزيل عدة نماذج كبيرة، وFFmpeg قادرًا على captions. على Android، لا يمكن نسخ هذه الافتراضات إلى APK: لا يوجد Python/uv/WhisperX/PyTorch داخل التصميم، والـTauri shell نفسه يرفض local Python processing على Android [6].

على الهاتف، المسار remote يحتاج Gateway قابلًا للوصول من الشبكة، `PUBLIC_BASE_URL` صحيحًا، token صالحًا، storage دائمًا، FFmpeg، pipeline dependencies، model downloads، وGemini key server-side عند اختيار Gemini. القيم الافتراضية مثل `127.0.0.1` تصل إلى الخادم نفسه لا إلى الهاتف، و`REQUIRE_GATEWAY_TOKEN` في المثال المحلي false. كما أن Android client يرفع عبر endpoint one-shot قديم، رغم وجود contract أحدث للرفع المتقطع.

إضافة إلى ذلك، توجد هويتان Android native/generated: `android/` يستخدم `applicationId=com.aistudio.opuspro.apk` وnamespace `com.example`، بينما Tauri generated يستخدم `com.publikhq.publikclip`. هذا ليس فشل compile بحد ذاته، لكنه يجعل “APK الحالي” غير محدد ويمنع release contract واحدًا. signing release اختياري؛ إذا لم توجد keystore ومتغيرات كلمات المرور ينتج البناء غير موقع أو غير صالح لمسار توزيع production.

## 4. ما الذي يمكن تشغيله على Android؟

يمكن لـAndroid تشغيل واجهة Compose، اختيار/import ملف محلي، تثبيت URI، تخزين job في Room، جدولة WorkManager، إرسال bytes إلى Gateway، polling للحالة، تنزيل MP4، استيراد النتائج، preview، وexport. ويمكنه أيضًا تنفيذ Kotlin local fallback الموجود، لكن هذا المسار لا يشغل محرك Python ولا يضمن نفس ASR/diarization/scoring/render الناتج.

لا يمكن ولا يجب أن يشغل Android: `uv`، Python، WhisperX، Torch، CAM++ Python، PANNs PyTorch، model registry الخاص بالـpipeline، أو FFmpeg server runtime كاعتماد مباشر. وجود Media3 وML Kit على الهاتف لا يساوي وجود pipeline parity؛ هذه أدوات local UX/export/face analysis محدودة وليست بديلًا عن runtime الخادم.

## 5. ما الذي يجب أن يعمل على Private Backend؟

| الوظيفة | مكانها المطلوب | السبب |
|---|---|---|
| Auth/session/device binding | Gateway | APK شخصي يحتاج حدًا أمنيًا دون user accounts أو multi-tenancy |
| Upload session/checksum/storage | Gateway | الهاتف لا يملك job storage authoritative، والرفع الطويل يحتاج resume |
| Job state/idempotency/retry/cancel/resume | Gateway + Engine | Gateway يملك الحالة الخارجية، Engine يملك checkpoint/artifact الداخلي |
| Python/uv runtime | Private server | غير مناسب لـAPK؛ يحتاج Python 3.12 وpackages أصلية |
| ASR/alignment/diarization/events/scoring/camera/render | Engine داخل worker server | CPU/RAM/disk وFFmpeg والنماذج الثقيلة |
| Gemini/Ollama/provider secrets | Gateway/AI runtime | لا تعبر إلى Android ولا تظهر في JSON/logs |
| Artifact authorization/download | Gateway | حماية ملفات source/output والتحقق من integrity |
| Social OAuth/publishing/analytics | Gateway، اختياري | يبقى خارج الحد الأدنى لمسار clip الشخصي ويُفعّل فقط بعد adapters الحقيقية |

الـGateway هو الخيار canonical لأن Android يستهدفه بالفعل، ولأنه يملك media lifecycle وprocessing state وdiagnostics وsecret boundary. `backend/` يعالج مشكلات مشابهة لكنه يكرر control plane ويستعمل API مختلفًا؛ إبقاء stackين نشطين سيضاعف drift.

## 6. ما الذي يجب إعادة استخدامه؟

تُعاد الاستفادة من Engine contract v1، `PipelineEngine`، queue/checkpoint runner، stages، vendor code المرخص، model registry، FFmpeg renderer، وJSONL CLI كـcompatibility shim. في Android يعاد استخدام WorkManager وRoom وURI handling وremote client وcache/import. وفي Gateway يعاد استخدام SQLite schema الحالية، upload checksum/atomic finalize، worker limits، diagnostics، secret vault، وprovider health model.

## 7. ما الذي يجب إعادة تصميمه؟

يجب أولًا توحيد backend على Gateway، لا إعادة كتابة pipeline. ثم يُعرّف adapter مباشر من Gateway إلى `ProcessingEngine` مع إبقاء JSONL fallback أثناء migration. يجب مواءمة Android مع resumable upload أو توثيق one-shot كعقد مقصود، وإضافة error envelope واحد، وإزالة hardcoded `llm=gemini` من عميل Android. يجب أيضًا اختيار APK واحد: native Android أو Tauri generated، ثم توحيد application identity وrelease signing.

لا يُحذف `ProductionVideoPipeline.kt` ولا social functionality في هذه المرحلة؛ لكن يجب وسمها بوضوح كـfallback/optional، ومنعها من تقديم نفسها كبديل production عن Python Engine. كما يجب ألا تُضاف dependencies جديدة إلا إذا أغلقت فجوة مثبتة في build أو runtime.

## 8. مشكلات P0/P1/P2

### P0 — مانعة للتشغيل الآمن أو للمسار المستهدف

| ID | المشكلة والدليل | الأثر | الإصلاح المطلوب قبل release |
|---|---|---|---|
| P0-1 | backend canonical غير محسوم؛ `gateway/` و`backend/` يقدمان API وstate models مختلفة [4] [7] | Android والوثائق قد يتجهان إلى عقدين، وفشل التشغيل لا يُشخّص بسهولة | تثبيت Gateway كـcanonical، تجميد backend البديل، ومنع features جديدة في stack الثاني |
| P0-2 | المسار Android end-to-end غير مثبت: لا APK artifact ولا اختبار جهاز/شبكة حقيقي | لا يوجد دليل أن المستخدم يستطيع import → process → download | بناء APK في CI، smoke test على جهاز/emulator، واختبار Gateway قابل للوصول |
| P0-3 | تشغيل Gemini remote مشروط بمفتاح server وruntime كامل، بينما readiness الحالي يفحص وجود ملفات أكثر من صلاحية model runtime | زر المعالجة قد يبدأ ثم يفشل بعد تنزيل/تحميل نماذج | readiness contract صريح: importability، FFmpeg captions، storage، model/provider probe |
| P0-4 | أي Gateway remote يجب أن يفرض token؛ المثال المحلي يسمح false | كشف خدمة خاصة دون auth إذا نُشرت env خاطئة | fail-closed في remote deployment، مع اختبار غياب/خطأ token |

### P1 — صحة، recovery، أو عقد تشغيلية

| ID | المشكلة والدليل | الأثر | الإصلاح المقترح |
|---|---|---|---|
| P1-1 | كان Android يستخدم `/v1/sources/upload` one-shot بينما العقد الحديث resumable `/v1/sources/uploads` | انقطاع رفع فيديو طويل كان يعيد العملية من الصفر | **أُصلح في هذه الدفعة:** client يحسب SHA-256، ينشئ session، يرسل chunks مع `X-Upload-Offset` و`Content-Range` ويستفيد من dedupe عند retry؛ يبقى اختبار interruption على جهاز/Gateway فعلي مطلوبًا. |
| P1-2 | `gateway/main.py` يحتوي تعريفات متكررة لمسارات AI providers في موضعين | route ambiguity واحتمال اختلاف behavior بين registry والcompatibility | إزالة التكرار بعد اختبار contract، دون حذف capability |
| P1-3 | وثيقة API تعد بخطأ `{error:{code,message,request_id,retryable}}`، بينما Gateway غالبًا يعيد FastAPI `detail` الخام | العميل لا يملك parsing ثابتًا ولا رسائل مستقرة | global exception handler/envelope في Gateway مع الحفاظ على HTTP codes |
| P1-4 | pipeline weights الثقيلة تُنزّل runtime، وعدة `ModelSpec` بلا sha256 مثبت | first-run طويل ومخاطر integrity/عدم reproducibility | pin hashes حيث يمكن، وإظهار manifest/version/size وتخزينها في readiness |
| P1-5 | queue Gateway داخل الذاكرة مع SQLite bookkeeping | آمن لمثيل واحد فقط؛ غير آمن للتوسع الأفقي أو claim متزامن | توثيق single-instance deployment وإضافة DB lease قبل أي replica |
| P1-6 | source/media validation ليست موحدة بين one-shot وresumable والـffprobe validation مؤجلة | bytes غير صالحة قد تستهلك التخزين وتفشل متأخرًا | فحص container/stream مبكرًا وتنظيف orphan artifacts |

### P2 — اكتمال وتوحيد قبل التحسينات الثانوية

| ID | المشكلة والدليل | الأثر | الإصلاح المقترح |
|---|---|---|---|
| P2-1 | full `pytest gateway` يفشل في root bridge test لأن `media_uploads` غير مهيأة، رغم نجاح `gateway/tests` | CI المحلي يعطي failure مضللًا | fixture أو `init_db()` واضح للاختبار، لا تغيير business behavior بلا حاجة |
| P2-2 | اختبار pipeline من بيئة نظيفة يحتاج scipy/librosa وغيرهما؛ الاختبار ينجح فقط بعد تثبيت dependencies | onboarding/contributor path غير deterministic | جعل command الموثق `uv sync && uv run pytest` هو gate، وتوضيح عدم كفاية system Python |
| P2-3 | native Android وTauri generated Android لهما identities ومسارات مختلفة | release ownership غير واضح | قرار artifact واحد وتوثيق migration مستقبلية |
| P2-4 | release signing مشروط بوجود keystore/env | يمكن إنتاج APK غير قابل للتوزيع | CI release signing منفصل، مع بقاء debug build للاختبار |
| P2-5 | Android UI يقبل remote URL في `App.tsx` بينما worker يستطيع local URI ثم remote upload | سلوك المنتج لا يطابق قدرة worker | اختيار عقد source واضح: URL-to-Gateway أو local-file resumable |
| P2-6 | Social live OAuth/publish غير منفذ وInstagram loop desktop-local | توسيع النطاق قد يشتت عن clip engine | إبقاؤه optional وموسومًا development/feature-gated |
| P2-7 | ملفات backup Android مفعلة، وtoken/Gateway config تحتاج مراجعة retention | خطر نسخ أسرار الجهاز إلى backup | سياسة backup صريحة لمفاتيح/شارات Gateway وعدم حفظ secrets في plain prefs |

## 9. نتائج الاختبارات المنفذة

| الفحص | النتيجة | الملاحظة |
|---|---|---|
| `python3 scripts/check_identity.py` | **PASS** | product `ISM`، version `0.10.1`، API `v1`، Android id موجود |
| `python3 -m compileall -q pipeline/publikclip_pipeline backend gateway` | **PASS** | لم يثبت توفر runtime dependencies |
| `npm ci && npm run build` داخل `app/` | **PASS** | TypeScript وVite build نجحا |
| `python3 -m unittest discover -s gateway/tests -p 'test_*.py' -v` | **PASS** | suite المعزولة نجحت |
| `python3 -m pytest gateway/tests -q` | **29 passed, 1 skipped** | warnings بسبب FastAPI `on_event` deprecated؛ large-media اختياري |
| `python3 -m pytest backend/tests -q` | **6 passed, 1 warning** | يستخدم fake engine، وليس pipeline end-to-end |
| `python3 -m pytest -q` | **164 passed, 1 skipped** بعد تثبيت gateway/pipeline requirements في sandbox | أول تشغيل فشل collection بسبب نقص pytest، ثم python-multipart، scipy، وlibrosa؛ بعد استكمال المتطلبات نجحت suite الموحدة. |
| `bash scripts/verify.sh` | **NOT PROVEN بالكامل** | النسخة الأولى توقفت قبل الاختبارات بسبب نقص pytest؛ suite Python المنفصلة نجحت، وAndroid skipped بسبب غياب SDK. يلزم إعادة verify في بيئة CI كاملة. |
| Android Gradle checks | **NOT RUN في هذه الجلسة** | Android SDK غير مضبوط في sandbox؛ يلزم JDK 21 + SDK/NDK أو الاعتماد على CI. |
| Docker build | **NOT RUN** | docker غير متوفر في sandbox |
| APK/device smoke | **NOT RUN** | لا جهاز أو emulator متاح |

### المراجع

[1]: ../pipeline/pyproject.toml "Python 3.12 pipeline dependencies"
[2]: ../pipeline/publikclip_pipeline/engine/contracts.py "Engine contract v1"
[3]: ../pipeline/publikclip_pipeline/engine/pipeline.py "Engine orchestration and checkpoint-backed results"
[4]: ../gateway/main.py "Gateway implementation and live/mock route behavior"
[5]: ../android/app/src/main/java/com/example/domain/pipeline/ProductionVideoPipeline.kt "Kotlin Android local pipeline"
[6]: ../app/src-tauri/src/lib.rs "Tauri desktop sidecar and Android local-runtime guard"
[7]: ../backend/app.py "Separate private backend implementation"
[8]: ../android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt "Android Gateway client and legacy upload"
[9]: ../android/app/build.gradle.kts "Android SDK, signing, identity, and dependencies"
[10]: ../.github/workflows/windows.yml "Windows clean-machine desktop workflow"
[11]: ../.github/workflows/android-build.yml "Android CI workflow"
[12]: ../Dockerfile.gateway "Gateway container image"
[13]: ../../upload/whisper.cpp-master.zip "Supplied whisper.cpp reference archive"
[14]: https://github.com/ggerganov/whisper.cpp/blob/master/examples/whisper.android.java/README.md "whisper.cpp Android sample"
[15]: https://github.com/ggerganov/whisper.cpp/blob/master/LICENSE "whisper.cpp MIT license"
