# سجل تراخيص الطرف الثالث

## ملاحظة النطاق

هذا سجل هندسي أولي للمكونات الموجودة في المستودع ومسار البناء المقترح. لا يُعد رأيًا قانونيًا. لم يُنسخ كود أو asset أو secret من `clipper-main` إلى `ISM-dragon/-1`؛ لذلك لا توجد مطالبة بأن كود المرجع أصبح جزءًا من هذا المستودع.

## تراخيص المستودع والمراجع

| المكوّن | المصدر | الترخيص الظاهر | حالة الاستخدام |
|---|---|---|---|
| المشروع الأساسي | `LICENSE` في جذر المستودع | GNU AGPL v3 | الترخيص الحاكم للمشروع الأساسي كما هو موزع. |
| المشروع المرجعي | `reference_zip/clipper-main/LICENSE` | MIT | تمت قراءة الترخيص فقط؛ لم تُنسخ ملفات مصدر منه. |
| Android/Gradle plugins | `android/*` وملفات Gradle | تحددها ملفات Gradle وملاحظات كل dependency | يجب الاحتفاظ بملفات notices التي يطلبها build عند التوزيع. |
| Python dependencies | `backend/requirements.txt`, `gateway/requirements.txt`, `pipeline/pyproject.toml` | تراخيص كل حزمة upstream | لا تُعامل قائمة الحزم كترخيص واحد؛ يلزم توليد inventory عند release. |
| npm/Tauri dependencies | `app/package.json` وlockfile | تراخيص upstream لكل package | خارج APK Native canonical، لكنها تبقى جزءًا من شجرة المستودع. |

## مكونات runtime الحساسة

يستخدم المسار الخادمي FFmpeg/ffprobe، Python packages للـASR/diarization/vision، وربما مزود LLM اختياري. يجب تثبيت نسخها في بيئة النشر، الاحتفاظ بمعلومات المصدر، وعدم وضع نماذج أو مفاتيح مزودين داخل APK أو Git.

## قواعد الدمج من مشاريع خارجية

قبل نقل أي ملف أو snippet من مشروع آخر، يجب تحديد ترخيص المستودع، وفحص header أو file-level notice، وفحص dependencies المباشرة، ثم تسجيل المصدر والنسخة والملفات المنقولة. إذا كان الترخيص غير واضح أو متعارضًا مع AGPL، يُعاد تنفيذ الفكرة مستقلًا بدل نسخ الكود. عند وجوب الإبقاء على copyright أو notice، يوضع في الملف أو في هذا السجل كما يطلب الترخيص.

## ما تم أخذه من المرجع

| العنصر | هل نُسخ كود؟ | القرار |
|---|---:|---|
| فكرة pipeline stateless stages | لا | استخدمت للمقارنة المعمارية فقط. |
| clamping لمخرجات LLM | لا | فكرة مرشحة للتحسين الانتقائي داخل implementation الحالية. |
| smoothing وfallback في camera | لا | مراجعة يدوية وbenchmark قبل أي تغيير. |
| caption styles وkeyword emphasis | لا | تحسين مستقبلي داخل renderer الحالي مع regression. |
| Pexels B-roll | لا | خارج canonical Android path حاليًا. |

## متطلبات الإصدار

قبل نشر APK أو Gateway خارجيًا، يجب توليد قائمة dependencies نهائية مع إصداراتها وتراخيصها، وفحص artifacts وعدم تضمين مفاتيح أو ملفات نماذج خاصة، وإتاحة notices المطلوبة للمكونات التي تُوزع مع المنتج. يبقى هذا العمل release gate مستقلًا عن نجاح اختبارات الوحدة.
