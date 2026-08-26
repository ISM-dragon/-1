هذا المجلد يحتوي على تطبيق ISM للموبايل، وهو عميل Android أصلي مبني باستخدام Kotlin وJetpack Compose وRoom وWorkManager وMedia3.

## تجربة الاستخدام

واجهة التطبيق موجهة للاستخدام الشخصي السريع: الشاشة الرئيسية تبدأ بمصدر فيديو أو ملف محلي، والاستوديو يعرض نتائج القص، والمكتبة تحفظ المشاريع، بينما يجمع «مركز التحكم» التحليلات والمقارنة وإدارة مزودي الذكاء الاصطناعي دون ازدحام شريط التنقل.

## محرك المعالجة

يستخدم التطبيق طبقة `ProcessingEngine` لاختيار مسار التنفيذ قبل جدولة العمل. إذا لم يُضبط Gateway صالح، تُستخدم `ProductionVideoPipeline` المحلية. وإذا وُجد عنوان HTTP أو HTTPS صالح، تُرسل المهمة إلى `ProcessingGatewayClient` عبر Gateway. في المسارين، يحفظ `VideoProcessingWorker` الحالة والتقدم في Room، ويدعم الإلغاء وإعادة المحاولة والاستئناف عند توفر checkpoint بعيد.

لا توجد نتائج وهمية أو نسب تقدم مصطنعة: الواجهة تعرض الحالة التي يرسلها المحرك الفعلي، وتُرفض مصادر الفيديو أو عناوين Gateway غير الصالحة قبل بدء المهمة.

## البناء المحلي

يتطلب البناء JDK 17 وAndroid SDK 36 وBuild Tools 36.0.0. من داخل هذا المجلد:

```bash
./gradlew clean
./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1
./gradlew :app:lint --no-daemon --max-workers=1
./gradlew :app:assembleDebug --no-daemon --max-workers=1
```

لبناء release قابل للتثبيت، يجب توفير keystore عبر `KEYSTORE_PATH` و`STORE_PASSWORD` و`KEY_PASSWORD`، ثم تشغيل:

```bash
export REQUIRE_RELEASE_SIGNING=true
./gradlew :app:assembleRelease --no-daemon --max-workers=1
```

يوجد المسار الكامل، بما في ذلك تثبيت SDK والتحقق من APK وABI وnetwork والتوزيع، في [`docs/ANDROID_BUILD.md`](../docs/ANDROID_BUILD.md) و[`docs/DEPLOYMENT.md`](../docs/DEPLOYMENT.md).

## العلاقة مع المستودع

المشروع الأصلي في جذر المستودع هو تطبيق سطح المكتب وطبقة Gateway وPython Pipeline. لا تُنسخ ملفات `app/src` إلى جذر مشروع Tauri؛ يبقى تطبيق Android هنا كعميل مستقل يستخدم عقد Gateway المشترك عند تفعيله.

## الخصوصية

المعالجة المحلية وقاعدة Room ومفاتيح Android تبقى داخل التطبيق. عند تفعيل Gateway، يرفع التطبيق الفيديو إلى العنوان الذي يحدده المستخدم عبر HTTPS أو localhost، ولا يرسل مفتاح Gemini المحلي إلى Gateway.

## CI

يُشغّل workflow `Embedded Android App` اختبارات الوحدة وAndroid Lint وبناء Debug APK عند تغيير هذا المجلد.
