# مقارنة المشروع المرجعي

**الحالة:** تدقيق موثق قبل أي نسخ أو استبدال واسع.

**المصدر المرجعي:** الأرشيف المرفق `opensource-clipping-main.zip`، وهو مشروع **OpenSource Clipping** المنشور في [NaufalRizqullah/opensource-clipping][1]. يحتوي الأرشيف على خط معالجة Python محلي، وواجهة Web Studio مبنية بـHTML/JavaScript، وواجهة FastAPI خفيفة، وليس تطبيق Android أو بنية Backend/Engine منفصلة. ترخيصه المرصود في ملف `LICENSE` هو **MIT** مع إشعار copyright باسم Muhammad Naufal Rizqullah.

## الخلاصة التنفيذية

المشروع المرجعي غني بميزات clipping والرندر: transcription بكلمات زمنية، اختيار المقاطع عبر Gemini، auto-framing، split-screen وcamera-switch، karaoke subtitles، hook/teaser، B-roll، BGM، watermark، thumbnail، ورفع اختياري إلى YouTube/Facebook. لكنه يفترض Python محليًا وFFmpeg ونماذج GPU ومفاتيح مزودين، ويستخدم orchestrator متسلسلًا وملف JSON/ذاكرة محلية لإدارة الوظائف. لذلك فهو **مرجع ميزات وأفكار UX** وليس أساسًا مناسبًا لنسخه داخل APK أو لاستبدال حد Android → Gateway → Engine.

المشروع الهدف أقرب إلى المتطلب التشغيلي لأنه يملك تطبيق Android أصليًا، وGateway خاصًا، ومحرك PublikClip مرحليًا مع checkpoints، وحالة وظائف قابلة للاسترداد، وحدود أسرار واضحة. القرار المعتمد هو **COMBINE بصورة انتقائية**: الاحتفاظ بالمسار الحالي، والاستفادة من أفكار المرجع عبر إعادة تنفيذ مستقلة واختبارات regression، دون نسخ ملفات source أو assets أو build outputs.

> حجم المشروع المرجعي أو عدد خيارات CLI فيه لا يثبت تفوقه في التوافق مع Android أو الاستقرار أو قابلية الاستئناف. معيار الاختيار هو ملاءمة المسار المستهدف، وصحة التنفيذ، وكلفة الدمج، وقابلية القياس.

## مقارنة على مستوى المكوّنات

| المجال | الموجود في الهدف | الموجود في المرجع الفعلي | القرار | السبب والحدود |
|---|---|---|---|---|
| Android structure | Kotlin/Compose داخل `android/` مع Room وWorkManager | لا يوجد Android native؛ Web Studio وموارد ويب | KEEP_CURRENT | الهدف الوحيد القابل لبناء APK أصلي بدورة حياة Android واضحة. |
| File/video picker | URI من Photo Picker ثم نسخ إلى `filesDir` | ملفات محلية أو روابط يقرأها Python | KEEP_CURRENT | يعالج صلاحيات Android واستمرار URI في الخلفية. |
| Networking | Gateway API خاص، bearer token، upload session وpolling | FastAPI Web Studio مع `POST /api/jobs` وSSE polling | IMPROVE_CURRENT | تُستفاد فكرة progress، لكن عقد `/v1` الحالي يبقى canonical للـAPK. |
| Background processing | WorkManager وforeground notification وRoom | Thread pool/semaphore ومهمة asyncio محلية | KEEP_CURRENT | WorkManager وRoom ملائمان لاستمرار العميل بعد إغلاق Activity أو إعادة التشغيل. |
| Job persistence | SQLite في Gateway وRoom في Android وcheckpoint stages | JSON snapshot وذاكرة process-local في Web API | KEEP_CURRENT | المرجع لا يقدم durable queue أو restart reconciliation مكافئًا. |
| Upload | Resumable session، offset، SHA-256، atomic finalize | رفع/ملفات محلية ضمن Web API | KEEP_CURRENT | رفع الفيديو الطويل يحتاج استئنافًا ولا ينبغي ربطه بمسار local filesystem. |
| ASR | WhisperX/ASR ضمن Pipeline server-side | Faster-Whisper وبدائل/YouTube subtitles | KEEP_CURRENT | لا ينقل runtime الثقيل إلى APK ولا يعيد transcription دون سبب. |
| Diarization/events | مراحل مستقلة وcheckpoints | Pyannote اختياري وbest-effort داخل orchestrator | KEEP_CURRENT | الفصل الحالي أفضل للاستئناف والتشخيص. |
| Candidate generation | candidates ثم scoring مع signals متعددة | Gemini يختار highlights وmetadata | IMPROVE_CURRENT | يمكن إضافة rubric المرجع كإشارة versioned اختيارية، دون حذف scoring الحالي. |
| Camera/framing | `camera/` مع tracking/director/stage وactive speaker | MediaPipe/YOLO وsplit-screen/camera-switch | KEEP_CURRENT + MANUAL_REVIEW | لا استبدال دون benchmark؛ تُعاد تنفيذ التحسينات المفيدة خلف feature flag. |
| Captions | word timestamps وASS/render integration | karaoke ASS وfont presets وkinetic typography | IMPROVE_CURRENT | تفصل caption state عن render وتُضاف presets قابلة للقياس. |
| Rendering/media | FFmpeg/ffprobe، validation، artifacts، cleanup | hooks، transitions، B-roll، BGM، watermark، thumbnail | COMBINE | تستمر media runtime الحالية؛ الميزات الإضافية اختيارية ولا تدخل APK. |
| Preview/edit | Android results/review/edit/export | Web Studio لتكوين jobs ومعاينة النتائج | ADD_REFERENCE | تُستخلص قواعد flow والتحرير الخفيف فقط؛ لا تُنقل واجهة الويب حرفيًا. |
| Provider management | Gateway registry وsecret vault وdiagnostics | مفاتيح env مباشرة لـGemini/Pexels/HF/NVIDIA | KEEP_CURRENT | الأسرار تبقى server-side وتظهر حالات آمنة فقط. |
| API/auth | private token وdevice boundary | API key لـWeb Studio/tunnel | KEEP_CURRENT | لا حاجة لـmulti-user أو billing أو حسابات عامة. |
| Social publishing | optional routes وmock adapters | YouTube/Facebook uploaders | IGNORE_REFERENCE | خارج الحد الأدنى لمعالجة clip، ويبقى feature منفصلًا. |
| Deployment | Gateway خاص وsingle-instance storage | Colab/Kaggle أو Python محلي مع FFmpeg | KEEP_CURRENT | يتطابق مع Android شخصي ويتيح مصدر أسرار واحدًا. |
| Licensing | مستودع الهدف AGPL-3.0-or-later وnotices محلية | المرجع MIT | MANUAL_REVIEW | MIT يسمح بإعادة الاستخدام مع حفظ notice، لكن لا حاجة للنسخ؛ الأفكار العامة يعاد تنفيذها. |

## التقييم الوزني

استُخدمت الأوزان المطلوبة في التكليف. الدرجات التالية تقدير هندسي للمفاضلة بين **المسار الحالي** و**نقل بنية المرجع**، وليست benchmark أداءً.

| معيار القرار | الوزن | المسار الحالي | نقل بنية المرجع |
|---|---:|---:|---:|
| توافق Android | 20% | 5/5 | 1/5 |
| Correctness | 20% | 4/5 | 3/5 |
| Stability | 15% | 4/5 | 2/5 |
| Performance | 15% | 4/5 | 3/5 |
| Maintainability | 10% | 4/5 | 2/5 |
| Feature completeness | 10% | 4/5 | 5/5 |
| Integration cost | 5% | 4/5 | 1/5 |
| Dependency cost | 5% | 4/5 | 2/5 |
| **المحصلة التقريبية** | **100%** | **4.15/5** | **2.45/5** |

## القرارات الناتجة

تم اعتماد `KEEP_CURRENT` للمسار Android → Gateway → Engine → AI/Media Runtime. وتم اعتماد `IMPROVE_CURRENT` للرفع القابل للاستئناف، وerror envelope، وcaptions، وscoring، وUX. وتم اعتماد `ADD_REFERENCE` لأفكار المحرر والقوالب، و`IGNORE_REFERENCE` للحسابات وbilling وMCP والبنية السحابية العامة. لم تُنقل dependencies أو أسرار أو artifacts build من الأرشيف.

## References

[1]: https://github.com/NaufalRizqullah/opensource-clipping "OpenSource Clipping reference project"
[2]: ../LICENSE "Target AGPL-3.0-or-later license"
[3]: ../pipeline/publikclip_pipeline/engine/contracts.py "PublikClip Engine contract v1"
[4]: ../gateway/main.py "Private Gateway implementation"
[5]: ../android/app/build.gradle.kts "Native Android build configuration"
[6]: REFERENCE_MIGRATION_PLAN.md "Reference migration plan"

---

**ملاحظة:** لم تُنسخ أي أجزاء من OpenSource Clipping إلى المستودع الهدف؛ التعديلات الحالية هي إعادة تنفيذ مستقلة لعقود الرفع والأخطاء، مع توثيق الأفكار المرجعية فقط.
