# Android UI وUX

## المسار الأساسي

```text
Home → Import → Generate → Processing → Results → Clip Review → Edit → Render → Export
```

يجب أن تكون الواجهة مصممة للهاتف لا نسخة من Tauri desktop. شاشة Home تعرض آخر jobs وحالتها، وتعرض Import اختيار فيديو حقيقي عبر Photo Picker أو URI صالح. شاشة Processing تعرض stage/progress ورسالة آمنة وزري cancel/resume عند الحاجة. شاشة Results تعرض artifacts القابلة للتنزيل، بينما Clip Review/Edit تعرض metadata وإعدادات التحرير دون كشف مسارات الخادم.

## الحالة والاستعادة

Room هو مصدر الحالة المحلي لتجربة المستخدم، وGateway هو مصدر الحالة التشغيلية البعيدة. يحفظ التطبيق `remoteGatewayJobId` وidempotency key وartifact metadata، ويستخدم WorkManager بقيود الشبكة وbackoff. عند إغلاق التطبيق أثناء المعالجة، يعيد التطبيق ربط worker بالحالة المحفوظة بدل إنشاء job جديد.

## الأذونات والأمن

تُطلب الإشعارات على Android 13+ وقت الحاجة فقط، ولا تُطلب صلاحيات تخزين عامة إذا كان Photo Picker كافيًا. لا تُكتب tokens أو ملفات الفيديو أو stack traces في logcat. إعداد Gateway يجب أن يكون قابلًا للتغيير في debug، ولا تُضمّن أسرار الإنتاج في APK.

## حدود release

Native Android داخل `android/` هو المسار canonical. المسار المحلي القديم وTauri generated Android ليسا artifact release الأساسيين. يلزم اختبار install/launch/file-picker/foreground notification/process death على جهاز أو emulator مستقر قبل إعلان release production.
