# خطة ترحيل الأفكار المرجعية

**الحالة:** معتمدة للتنفيذ التدريجي، وليست تصريحًا بنسخ أي مشروع مرجعي. أضيف إلى نطاق التدقيق في 2026-08-26 أرشيف `whisper.cpp-master.zip` كمرجع Android/JNI/ASR فقط.

## المبدأ

المسار المستهدف هو Android APK شخصي يتصل بـPrivate Gateway. ستُرحّل الأفكار التي تخدم هذا المسار فقط، وبحدود عقود واضحة. كل تغيير cross-component يبدأ بتثبيت contract واختبار regression، ثم يُدمج على دفعة صغيرة يمكن التراجع عنها. لا يُسمح بإعادة بناء المشروع من الصفر، ولا بإدخال user accounts أو billing أو Redis أو مزودات جديدة لمجرد مطابقة المرجع.

## الموجات

| الموجة | النطاق | مخرجاتها | بوابة الانتقال |
|---|---|---|---|
| Wave 1 — Audit | مقارنة الكود والميزات والتراخيص | هذه المقارنة، قرارات الدمج، وثائق architecture/contracts | لا production replacement قبل اكتمال الوثائق. |
| Wave 2 — Engine/Runtime | تثبيت Engine facade، lifecycle، media errors، model diagnostics | عقود v1، checkpoints، failure envelope، اختبارات restart/cancel/resume | جميع اختبارات Python الحالية تمر، مع evidence لاختبار failure paths. |
| Wave 3 — Android Core | upload sessions، Room state، WorkManager، notifications، secure config | APK client لا يعرف Python ولا الأسرار | unit/lint/build، ثم device test على جهاز فعلي. |
| Wave 4 — Integration | ربط APK بـGateway خاص، تنزيل النتائج، preview/edit/export | E2E من URI إلى artifact | job ID، stage evidence، hashes، وعدم وجود mock في المسار الأساسي. |
| Wave 5 — Release QA | توقيع، device matrix، network loss، process death، restart/recovery | release APK وrelease evidence | لا تغلق blockers دون دليل تشغيل فعلي. |

## ترتيب التغييرات

أولًا، يجب تثبيت `/v1` وعقد `ProcessingEngine` بحيث تظل مراحل `ingest → asr → diarize → events → candidates → score → camera → render` خلف facade واحدة. ثانيًا، تم تحسين upload/session في العميل canonical دون تغيير one-shot compatibility route. ثالثًا، يجب استكمال Android UX على شكل Home → Import → Generate → Processing → Results → Review → Edit → Render → Export، مع تخزين حالة job في Room واستخدام WorkManager للاستمرار.

بعد ذلك فقط تُضاف أفكار المرجع ذات القيمة المحدودة: presets للـcaptions، بيانات rubric واضحة للـscoring، وعمليات trim/split/merge في شاشة التحرير، ومبدأ تسجيل benchmark/system-info المستقل. هذه الإضافات يجب أن تعمل فوق artifacts والعقود الحالية؛ لا يجوز أن تجعل Android يعيد transcription أو يشغل FFmpeg محليًا. وبالنسبة إلى whisper.cpp، لا يُنقل JNI/CMake أو نموذج GGML إلى APK في هذه الموجة؛ أي مسار ASR محلي مستقبلي يحتاج عقد `LocalAsrProvider` منفصلًا، إدارة model/checksum، قياسًا على أجهزة فعلية، ومقارنة accuracy/latency/RAM مع WhisperX قبل تفعيله. أما B-roll، النشر الاجتماعي، MCP، الحسابات، billing، والـmulti-user فخارج هذه الخطة.

## ضوابط الترحيل

| الخطر | الضابط |
|---|---|
| كسر checkpoints القديمة | قراءة legacy envelopes، وكتابة versioned envelopes، واختبار resume من كل stage. |
| اختلاف نتائج scoring | إطلاق rubric versioned وتسجيله في job metadata، ثم مقارنة النتائج قبل تغيير default. |
| تضخم APK | فحص APK archive يمنع Python/uv/Node/Rust/FFmpeg وWhisper/GGML models والنماذج والأسرار. |
| فقد upload عند انقطاع الشبكة | offset + SHA-256 + idempotency key، واختبار interruption/resume. |
| تسرب الأسرار | provider keys في Gateway فقط، وAndroid لا يستقبل إلا capability/result آمنًا. |
| التباس بين Android native وTauri Android | artifact واحد وapplication ID واحد للمسار الشخصي؛ Tauri يبقى مسارًا منفصلًا. |
| نسخ ترخيص غير واضح | إعادة التنفيذ المستقل عند أي غموض، وإضافة سجل إلى `THIRD_PARTY_LICENSES.md` قبل الدمج؛ whisper.cpp MIT-style لا يعني نسخ source بلا حفظ notice. |

## بوابة whisper.cpp المستقبلية

لا تُفتح تذكرة دمج native ASR إلا إذا توفرت عينة صوت وفيديو ممثلة، baseline موثق لـWhisperX، ومقارنة قابلة لإعادة الإنتاج لزمن ASR، الذاكرة، الدقة، حجم APK، واستهلاك البطارية. يجب أن يكون التشغيل opt-in ولا يغير المسار remote الافتراضي، وأن تعالج العملية النماذج القابلة للتنزيل والتحقق والحذف دون وضعها داخل Git.

## معايير التراجع

يُلغى التغيير إذا أدى إلى فشل في build أو regression suite، أو زاد زمن المعالجة/الذاكرة دون evidence، أو غيّر output السابق دون versioning، أو احتاج dependency غير متوافقة مع Android/ترخيص المشروع، أو جعل failure في LLM أو FFmpeg يسبب crash بدل error قابل للفحص.

## References

[1]: REFERENCE_COMPARISON.md "Comparison and decisions"
[2]: ARCHITECTURE.md "Canonical topology and ownership"
[3]: CONTRACTS.md "Public API and engine contracts"
[4]: FINAL_ACCEPTANCE.md "Evidence-based acceptance matrix"
[5]: REFERENCE_COMPARISON.md "SupoClip and whisper.cpp comparison"
