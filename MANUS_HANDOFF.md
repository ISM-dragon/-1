# MANUS Handoff

## الحالة الحالية

تم تثبيت تجربة Android UI حول مسار شخصي سريع وواضح:

> **Home → اختيار فيديو → Generate → Processing progress → أفضل المقاطع → Preview → اختيار clip → Edit → Export**

الـengine والـAPI خارج نطاق هذا التغيير. لم تُعدّل طبقات `data` أو `domain` أو `gateway` أو `pipeline`، ولم يتغير أي استدعاء لمعالجة الفيديو أو تحليل الذكاء الاصطناعي أو التصيير؛ التغيير محصور في طبقة العرض والتنقل داخل `android/app/src/main/java/com/example/ui`.

## ما تم تحسينه

| المنطقة | النتيجة |
|---|---|
| Home | شاشة بداية مختصرة تركز على إدخال رابط أو اختيار ملف محلي، مع زر Generate واضح ومشاريع أخيرة بسيطة. أزيلت بطاقات النشر التلقائي وملف صانع المحتوى ومؤشرات المزودين من المسار الرئيسي. |
| اختيار الفيديو | معاينة thumbnail فعلية، قراءة metadata، تحقق من الحجم والمدة والأبعاد، وإجراء Generate واحد واضح. |
| Processing | عرض التقدم الحقيقي من `ProcessingStep` و`ProcessingJobEntity` عبر المكوّن القائم، مع حالات loading وprocessing وcompleted وerror. |
| Studio | ترتيب النتائج حسب أفضل المقاطع، ثم score، ثم preview، ثم transcript، ثم تبويبا Captions وCrop، ثم Render & export. |
| Captions | الإبقاء على `AutoCaptionStudioCard` ومحددات الكابشن الحالية داخل تبويب مستقل بدلاً من دفنها بين أدوات ثانوية. |
| Crop | إضافة بطاقة مستقلة لاختيار `9:16 Full Screen` أو `Split Screen` أو `1:1 Square`، مع حفظ الاختيار عبر `repository.updateLayoutType`. |
| Export | نافذة تصدير صغيرة تعرض progress الحقيقي وتُظهر completed أو error بدلاً من الاكتفاء برسالة Toast. |
| Navigation | حصر شريط الهاتف السفلي في الرئيسية والاستوديو والمكتبة؛ مسارات dashboard وbenchmark وgateway والإعدادات ليست destinations أساسية. |
| Header | هوية مختصرة ومؤشر جاهزية إعداد الاتصال فقط، من دون credits أو تفاصيل مزودين داخل التجربة الأساسية. |

## الحالات المضافة أو الواضحة

| الحالة | موضع العرض |
|---|---|
| `loading` | قراءة metadata بعد اختيار الفيديو، مع مؤشر تحميل داخل بطاقة المعاينة. |
| `empty` | لا توجد مشاريع في Home، أو لا توجد مقاطع في Studio. |
| `error` | رابط غير صالح، metadata غير صالحة، فشل job، وفشل export؛ تظهر الرسالة داخل الواجهة. |
| `offline` | تنبيه واضح في Home وشاشة اختيار الفيديو عند فقدان اتصال الشبكة. |
| `processing` | بطاقة ومكوّن progress يعرضان المرحلة والنسبة الواردة من المحرك. |
| `completed` | انتقال إلى المشروع بعد نجاح job، ورسالة مكتملة داخل نافذة التصدير بعد حفظ الفيديو. |

## الملفات المعدّلة

- `android/app/src/main/java/com/example/ui/screens/HomeScreen.kt`
- `android/app/src/main/java/com/example/ui/screens/VideoUploadScreen.kt`
- `android/app/src/main/java/com/example/ui/screens/ClipStudioScreen.kt`
- `android/app/src/main/java/com/example/ui/components/OpusBottomNav.kt`
- `android/app/src/main/java/com/example/ui/components/OpusHeader.kt`

## التحقق

تم تشغيل `git diff --check` بنجاح، كما تم التحقق نصياً من أن الملفات المعدّلة لا تحتوي على تغييرات في طبقات `data` أو `domain` أو `gateway` أو `pipeline`، وأن عناصر dashboard/developer والنشر التلقائي أزيلت من الشاشات النشطة.

تعذّر إكمال `:app:testDebugUnitTest` و`:app:lint` في بيئة التنفيذ الحالية لأن Android SDK غير مثبت ولا يوجد `ANDROID_HOME` أو `android/local.properties` صالح. لم يُنشأ أي ملف SDK أو build artifact في commit.

## ملاحظات للمتابعة

المعاينة الحقيقية في `VideoSimPlayer` تعتمد على وجود `clip.exportPath` الناتج من engine؛ عند عدم توفر ملف MP4 تُعرض حالة فارغة صريحة بدلاً من فيديو تجريبي. لذلك لا توجد نتائج وهمية أو نسب تقدم مصطنعة في الواجهة.

### مراجع داخلية

[1]: android/README.md "Android client architecture and build commands"
[2]: docs/API-CONTRACT.md "Shared API contract"
[3]: android/app/src/main/java/com/example/data/repository/OpusRepository.kt "Repository processing contracts"
