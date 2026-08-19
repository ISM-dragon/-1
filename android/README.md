# Opus Pro Android

هذا المجلد يحتوي على نسخة تطبيق Android الأصلية من Opus Pro المبنية باستخدام Kotlin وJetpack Compose وRoom وWorkManager وMedia3.

## البناء المحلي

من داخل هذا المجلد:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lint
./gradlew :app:assembleDebug
```

## العلاقة مع المستودع

المشروع الأصلي في جذر المستودع هو تطبيق سطح المكتب وطبقة Gateway وPython Pipeline. لا تُنسخ ملفات `app/src` إلى جذر مشروع Tauri؛ يبقى تطبيق Android هنا كعميل مستقل يستخدم عقد Gateway المشترك عند تفعيله.

## الخصوصية

المعالجة المحلية وقاعدة Room ومفاتيح Android تبقى داخل التطبيق. عند تفعيل Gateway، يرفع التطبيق الفيديو إلى العنوان الذي يحدده المستخدم عبر HTTPS أو localhost، ولا يرسل مفتاح Gemini المحلي إلى Gateway.

## CI

يُشغّل workflow `Embedded Android App` اختبارات الوحدة وAndroid Lint وبناء Debug APK عند تغيير هذا المجلد.
