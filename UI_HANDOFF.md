# Android UI/UX Handoff — ISM Clips

## النطاق

هذا التسليم يخص **واجهة Android/Compose فقط** لتطبيق شخصي يحوّل الفيديوهات الطويلة إلى مقاطع قصيرة. لم يتم تعديل backend أو Python engine أو طبقات `data` و`domain`. بقيت استدعاءات repository وWorker والتصدير الحقيقي كما كانت، بينما أُعيد تنظيم العرض والتوجيه والنصوص ومسار الاستخدام.

> المسار الأساسي المستهدف: **فيديو → توليد → أفضل المقاطع → مراجعة → تحرير → تصدير**.

## خريطة الشاشات

| الشاشة | نقطة الدخول الحالية | الغرض | الإجراء الرئيسي |
|---|---|---|---|
| Home | `HomeScreen.kt` | بدء المشروع بسرعة وعرض آخر المشاريع الحقيقية | اختيار فيديو |
| Import | `VideoUploadScreen.kt` | اختيار فيديو محلي ومعاينة metadata والصورة المصغرة | توليد أفضل المقاطع |
| Processing | `VideoProcessingLoadingDialog.kt` | عرض المرحلة والتقدم الفعليين من المعالجة | الانتظار أو العودة لاحقاً |
| Results | `ClipStudioScreen.kt` | عرض أفضل المقاطع مرتبة حسب `viralityScore` مع معاينة حقيقية | اختيار مقطع |
| Clip Review | تبويب `المراجعة` داخل Results | مراجعة النتيجة والـ score وشرح الانتشار | حفظ المقطع أو فتح التحرير |
| Editor | تبويب `التحرير` داخل Results | ضبط camera framing ونسبة العرض | تحديث الإطار ثم التصدير |
| Settings | `SettingsScreen.kt` | إدارة اتصال المحرك والافتراضات المرئية | إدارة الاتصال |

## القرارات الأساسية

تمت إزالة البطاقات الإدارية والتجريبية من المسار الرئيسي، بما في ذلك شريط أدوات AI العام، النشر المباشر، المقارنة، لوحات الاستخدام، وشريط التنقل ذي الوجهات الكثيرة. بقيت الوجهات الثانوية في الكود القديم خارج شريط التنقل الأساسي لتقليل أثر التغيير على بقية المستودع، لكنها لم تعد جزءاً من تجربة المستخدم الأساسية.

تبدأ Home ببطاقة واحدة واضحة لاختيار الفيديو. بعد اختيار الملف، تعرض Import الصورة المصغرة والمدة والدقة والحجم والتحقق، ثم تستخدم افتراضات ISM الذكية افتراضياً. الإعدادات التفصيلية لا تظهر قبل التوليد حتى لا يتخذ المستخدم قرارات غير ضرورية.

تعرض Results المقاطع الحقيقية في شريط أفقي مرتب حسب النتيجة، ثم مشغل الفيديو الحقيقي إن كان `exportPath` صالحاً. تبويبا الترجمة والتأطير يقدمان التفاصيل بعد اختيار المقطع. يحتفظ زر التصدير بمسار `exportClipToFile` و`saveExportToMediaStore` الموجودين، مع رسائل عربية وإعداد تضمين الترجمة والجودة.

## الملفات المعدلة

| الملف | نوع التغيير |
|---|---|
| `android/app/src/main/java/com/example/MainActivity.kt` | تبسيط التوجيه إلى Home وResults وSettings وImport |
| `android/app/src/main/java/com/example/ui/screens/HomeScreen.kt` | إعادة بناء Home حول اختيار الفيديو والمشاريع الأخيرة |
| `android/app/src/main/java/com/example/ui/screens/VideoUploadScreen.kt` | تبسيط Import وإخفاء الخيارات الثانوية من المسار الأول |
| `android/app/src/main/java/com/example/ui/components/VideoProcessingLoadingDialog.kt` | تبسيط رسائل Processing وإزالة الإشارات إلى مزود محدد |
| `android/app/src/main/java/com/example/ui/screens/ClipStudioScreen.kt` | إعادة تسمية Results وإضافة تبويب Editor للتأطير وتقليل الإجراءات الثانوية |
| `android/app/src/main/java/com/example/ui/components/VideoSimPlayer.kt` | إزالة أسماء الأدوات الداخلية من عنوان المعاينة |
| `android/app/src/main/java/com/example/ui/components/OpusHeader.kt` | رأس منتج مختصر ومدخل إعدادات واضح |
| `android/app/src/main/java/com/example/ui/components/OpusBottomNav.kt` | ثلاث وجهات أساسية: الرئيسية، النتائج، الإعدادات |
| `android/app/src/main/java/com/example/ui/screens/SettingsScreen.kt` | شاشة Settings بسيطة مع إدارة الاتصال عند الطلب |
| `android/app/src/test/java/com/example/ResponsiveUiScreenshotTest.kt` | اختبارات Compose/Robolectric لثلاثة مقاسات Android |

## اختبار المقاسات

أُضيف اختبار snapshot/compose للقياسات التالية، مع التركيز على بقاء رأس التطبيق وشريط التنقل داخل مساحة العرض وعدم قص عناصر workflow الأساسية:

| الفئة | Qualifier |
|---|---|
| هاتف صغير | `w320dp-h568dp-xxhdpi` |
| هاتف حديث | `w393dp-h852dp-xxhdpi` |
| شاشة Android كبيرة | `w600dp-h960dp-xhdpi` |

نجح الفحص الثابت لمسار workflow ووجود test tags الخاصة بـ Home وImport وResults وEditor وSettings، كما نجح `git diff --check`. تعذّر تشغيل Gradle/Lint في بيئة التنفيذ لأن Android SDK غير مثبت ولا يوجد `android/local.properties`؛ لذلك يجب تشغيل الاختبار التالي في بيئة Android/CI تحتوي SDK:

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:lint
./gradlew :app:assembleDebug
```

## ملاحظات التكامل

لا توجد dependencies مطلوبة من backend أو Python engine لهذه التغييرات. اعتمدت الواجهة على callbacks الحالية: `onProjectCreated`, `onOpenProject`, `enqueueVideoProcessing`, `updateLayoutType`, `exportClipToFile`, و`saveExportToMediaStore`. أي تغيير مستقبلي في أسماء هذه callbacks أو نماذج `Project` و`Clip` يحتاج تحديثاً متزامناً في طبقة UI.

هذه التغييرات أُنجزت على فرع UI مستقل باسم `ui/android-clip-flow`. عند دمجها مع جلسات أخرى، يجب إبقاء تغييرات `android/app/src/main/java/com/example/data` و`android/app/src/main/java/com/example/domain` و`gateway` و`pipeline` خارج هذا الدمج اليدوي.

## مرجع المصدر

يعكس هذا المستند بنية المستودع الحالية ومسارات الملفات المذكورة أعلاه، وليس مواصفات backend جديدة. للمراجعة المباشرة: [مستودع ISM-dragon/-1](https://github.com/ISM-dragon/-1) [1].

## References

[1]: https://github.com/ISM-dragon/-1 — مستودع ISM-dragon/-1.
