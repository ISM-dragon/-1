# مقارنة المشروع المرجعي مع ISM / PublikClip

**الحالة:** تدقيق موثق قبل أي نسخ أو استبدال واسع.

**المصدر المرجعي:** الأرشيف المرفق `supoclip-main.zip`، وهو مشروع SupoClip منشور تحت AGPL-3.0، ويحتوي على واجهة Next.js/React، وBackend FastAPI/ARQ، وMCP server، وخط معالجة يعتمد على مزودات خارجية. لم تُنسخ ملفات من الأرشيف إلى المستودع الهدف.

## الخلاصة التنفيذية

المشروع المرجعي قوي في تجربة الويب، وإدارة اختيار المقاطع، والتحرير الخفيف، والقوالب، والتكامل مع مزودات الذكاء الاصطناعي. أما المستودع الهدف فهو أقرب إلى المنتج المطلوب فعليًا لأنه يملك Android client، وPrivate Gateway، ومحرك PublikClip ذي checkpoints، واختبارات resilience، وعقودًا بين العميل والخادم. لذلك لا توجد مبررات تقنية لاستبدال بنية الهدف بنسخة المرجع. القرار المعتمد هو **COMBINE بصورة انتقائية**: الاحتفاظ بمسار Android/Gateway/Engine الحالي، واستخدام أفكار المرجع في UX والعقود القابلة للتوسع فقط بعد تحويلها إلى مكونات مستقلة واختبارها.

> لا يعتبر وجود implementation أكبر أو أحدث دليلًا كافيًا على صلاحيتها لمسار Android شخصي. معيار الاختيار هنا هو التوافق مع Android، والاستقرار، وكلفة الدمج، وقابلية القياس.

## مصفوفة القرار

| المجال | الموجود في الهدف | الموجود في المرجع | القرار | السبب والحدود |
|---|---|---|---|---|
| Android structure | تطبيق Kotlin مستقل داخل `android/` مع Compose وRoom وWorkManager | تطبيق ويب وموارد Tauri/iOS في الأرشيف | KEEP_CURRENT | الهدف الوحيد القابل لبناء APK أصلي مع دورة حياة Android واضحة. |
| File/video picker | وصول Android إلى URI محلي ثم نسخ آمن إلى `filesDir` | اختيار ملف عبر المتصفح | KEEP_CURRENT | `content://` وقيود Android لا يمكن تمثيلها باستدعاء متصفح عام. |
| Networking | عميل Gateway ورفع واستطلاع واستئناف | REST/WebSocket وARQ للويب | IMPROVE_CURRENT | يمكن استعارة فكرة progress stream، لكن العقد الحالي يجب أن يبقى المصدر الوحيد للـAPK. |
| Background processing | WorkManager وforeground notification وRoom state | ARQ/Redis worker | KEEP_CURRENT | WorkManager مناسب لاستمرار العميل؛ Redis ليس ضرورة لمالك واحد وGateway خاص. |
| Job persistence | SQLite/Room وcheckpoints وtransition history | DB/ARQ jobs | COMBINE | تبقى durable state في Gateway، ويُحسن Android عرضها بعد restart. |
| Upload | Gateway upload sessions وchecksum | رفع API يديره Backend | KEEP_CURRENT | session/resume وSHA-256 أهم لمسار فيديو كبير من one-shot upload. |
| ASR | Python/WhisperX في runtime خاص | AssemblyAI ومزودات متعددة | KEEP_CURRENT | لا يجوز نقل نموذج desktop إلى APK أو إعادة transcription دون سبب. |
| Diarization/events | مراحل مستقلة في Pipeline | مرحلة تحليل ضمن worker | KEEP_CURRENT | الفصل الحالي أفضل للـcheckpoints والاختبارات والتشخيص. |
| Candidate generation | candidates/scoring موجودان في Pipeline | LLM يختار 3–7 مقاطع مع virality rubric | IMPROVE_CURRENT | يمكن إضافة rubric versioned كمدخل scoring، دون حذف scoring الحالي أو جعل فشل LLM قاتلًا. |
| Camera/framing | `camera/` مع tracking/director/stage | face-centered crop | KEEP_CURRENT | الهدف يملك تركيبة أوسع؛ المرجع لا يثبت benchmark يتفوق عليه. |
| Captions | word timestamps وASS/render integration | قوالب وword-synced subtitles | IMPROVE_CURRENT | فصل caption state عن render logic، وإضافة presets فقط عبر contract. |
| Rendering/media | FFmpeg/ffprobe، validation، cleanup، artifacts | FFmpeg transitions/B-roll | COMBINE | تستمر media runtime الحالية؛ B-roll/transitions اختيارية ولا تدخل APK أو تزيد dependencies بلا قياس. |
| Preview/edit | Android results/cache ومسار تحرير مبدئي | محرر ويب trim/split/merge | ADD_REFERENCE | تُستخلص UX rules فقط؛ لا تُنقل واجهة الويب حرفيًا. |
| Provider management | Gateway provider registry وsecret vault | Gemini/OpenAI/Claude/Ollama في Backend | KEEP_CURRENT | الأسرار تبقى server-side، مع diagnostics واضحة وfallback آمن. |
| API/auth | Private token/device binding و`/v1` | user accounts/API keys وbilling routes | IGNORE_REFERENCE | خارج نطاق تطبيق شخصي، ويزيد المخاطر والسطح التشغيلي. |
| Social publishing | optional routes في الهدف والمرجع | Instagram/analytics/email integrations | IGNORE_REFERENCE | ليست شرطًا لإنشاء clip؛ تُبقى منفصلة حتى استقرار processing. |
| Deployment | Gateway خاص وDocker Compose volume | Docker Compose مع Redis/DB وخدمات متعددة | KEEP_CURRENT | تصميم أحادي المستخدم أبسط وأقرب للمتطلب؛ التوسع ليس هدف Wave الحالية. |
| MCP | غير أساسي للمسار Android | MCP server مستقل | IGNORE_REFERENCE | لا يضيف قيمة لمسار APK الشخصي الحالي. |
| Licensing | target license وvendor notices موجودة | AGPL-3.0 | MANUAL_REVIEW | لا نسخ للكود المرجعي قبل مراجعة التوافق؛ إعادة التنفيذ المستقل هو الافتراضي عند الغموض. |

## التقييم الوزني

استُخدمت الأوزان المطلوبة في التكليف. الدرجات التالية تقدير هندسي للمفاضلة بين **المسار الحالي** و**نقل بنية المرجع**، وليست benchmark أداءً.

| معيار القرار | الوزن | المسار الحالي | نقل بنية المرجع |
|---|---:|---:|---:|
| توافق Android | 20% | 5/5 | 2/5 |
| Correctness | 20% | 4/5 | 3/5 |
| Stability | 15% | 4/5 | 3/5 |
| Performance | 15% | 4/5 | 3/5 |
| Maintainability | 10% | 4/5 | 3/5 |
| Feature completeness | 10% | 4/5 | 4/5 |
| Integration cost | 5% | 4/5 | 1/5 |
| Dependency cost | 5% | 4/5 | 2/5 |
| **المحصلة التقريبية** | **100%** | **4.15/5** | **2.70/5** |

## نتيجة التدقيق

تم اعتماد `KEEP_CURRENT` للمسار Android → Gateway → Engine → AI/Media Runtime. وتم اعتماد `IMPROVE_CURRENT` للـcaptions وscoring وUX، و`ADD_REFERENCE` لأفكار المحرر والقوالب، و`IGNORE_REFERENCE` للحسابات وbilling وMCP وsocial integrations في هذه المرحلة. لم تُنقل dependencies أو أسرار أو artifacts build من الأرشيف.

## References

[1]: https://github.com/FujiwaraChoki/supoclip "SupoClip public reference project"
[2]: ARCHITECTURE.md "Target architecture baseline"
[3]: CONTRACTS.md "Android/Gateway/Engine contracts"
[4]: ../gateway/main.py "Private Gateway implementation"
[5]: ../pipeline/publikclip_pipeline/engine/contracts.py "PublikClip Engine contract"
[6]: ../android/app/build.gradle.kts "Native Android build configuration"
[7]: ../VENDORED-LICENSES.md "Target repository vendor notices"

---

**ملاحظة:** لم تُنسخ أي أجزاء من SupoClip إلى المستودع الهدف أثناء هذا التدقيق.
