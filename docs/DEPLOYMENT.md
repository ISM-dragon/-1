# Android Deployment

**المنتج:** ISM Native Android client  
**Application ID:** `com.aistudio.opuspro.apk`  
**المبدأ:** Android عميل للـPrivate Backend؛ لا يحمل Python أو `uv` أو `pip` أو Node أو Rust أو FFmpeg desktop.[1]

## مكوّنات النشر

| المكوّن | مكان التشغيل | المسؤولية |
|---|---|---|
| APK | جهاز Android API 24 أو أحدث | الاستيراد، إعداد Gateway، جدولة WorkManager، polling، cache وتنزيل النتائج |
| Private Gateway | Linux/VM/Docker قابل للوصول من الهاتف | auth، job state، queue، source ingest، API، retries، artifact serving |
| Python pipeline وFFmpeg والنماذج | خلف Gateway | التحليل، scoring، rendering والتحقق من MP4 |

الـGateway هو مصدر الحقيقة لحالة المهمة والـartifact. يجب ألا تُعامل عملية Android local fallback كبديل صامت لمعالجة الإنتاج؛ مسار المعالجة عن بعد هو المسار المطلوب عند تفعيل Private Backend.[1]

## متطلبات الـPrivate Backend

قبل تثبيت APK، يجب تشغيل Gateway مع Python dependencies وFFmpeg/ffprobe وmodel policy وdirectories قابلة للكتابة، وتحديد `PUBLIC_BASE_URL` بعنوان يمكن للهاتف الوصول إليه. يجب تفعيل auth في النشر الخاص، مثل `REQUIRE_GATEWAY_TOKEN=true`، وتوفير token قصير العمر أو token مخصص للاختبار عبر قناة آمنة. لا يوضع token أو Gemini API key داخل Git أو داخل APK.

يجب اختبار Gateway مستقلًا قبل ربط الهاتف:

```bash
curl --fail "$PUBLIC_BASE_URL/health"
curl --fail \
  -H "Authorization: Bearer $GATEWAY_TOKEN" \
  "$PUBLIC_BASE_URL/v1/processing/capabilities"
```

ينبغي أن يجيب health وcapabilities من نفس العنوان الذي سيُدخل في التطبيق، لا من `localhost` على جهاز الخادم. `localhost` على الهاتف يعني الهاتف نفسه، وليس الخادم.

## إعداد الاتصال من APK

افتح شاشة إعدادات Gateway في التطبيق وأدخل:

```text
Gateway URL: https://gateway.example.internal
Gateway token: <short-lived session token>
```

يطبّق العميل الشروط التالية قبل بدء المهمة:

| العنوان | Release | Debug |
|---|---|---|
| HTTPS | مسموح | مسموح |
| HTTP على localhost أو LAN الاختبار المدعومة | غير مسموح بسبب cleartext policy | مسموح للتطوير فقط |
| HTTP على عنوان عام | مرفوض | مرفوض من URL validator |
| عنوان فارغ أو بلا scheme | مرفوض | مرفوض |

يجب استخدام HTTPS خارج شبكة الاختبار. تسمح نسخة debug بالـcleartext لتجارب LAN فقط، بينما لا يفعّل manifest release هذا الاستثناء؛ هذا مقصود لتجنب إرسال bearer token والوسائط عبر HTTP غير مشفر.[2] [3]

## تثبيت APK

للتثبيت اليدوي على جهاز متصل ومصرح به:

```bash
adb devices
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.aistudio.opuspro.apk/com.example.MainActivity
```

إذا كانت الحزمة unsigned، سيفشل `adb install`. ابنِ release موقّعًا أولًا وفق `docs/ANDROID_BUILD.md`. لا تستخدم `adb install --bypass-low-target-sdk-block` أو أي تجاوز أمني كحل للنشر؛ target SDK في المشروع مضبوط على 36.

للتحقق من الإقلاع والسجلات:

```bash
adb shell pidof com.aistudio.opuspro.apk
adb logcat -c
adb shell am force-stop com.aistudio.opuspro.apk
adb shell monkey -p com.aistudio.opuspro.apk 1
adb logcat -d -v time | grep -i -E 'AndroidRuntime|com.aistudio.opuspro.apk|Gateway|WorkManager'
```

## Smoke test على جهاز Android

نفّذ الخطوات بالترتيب، وسجّل النتيجة في تقرير الإصدار:

1. ثبّت APK release الموقّع على جهاز API 24+، ثم افتح `com.aistudio.opuspro.apk`.
2. أدخل عنوان HTTPS للـPrivate Gateway وtoken صالحًا، واضغط اختبار الاتصال.
3. تحقق من health/capabilities ومن ظهور حالة الاتصال دون crash.
4. أرسل رابط فيديو HTTP/HTTPS صالحًا أو اختر ملفًا محليًا؛ يمر الرابط البعيد إلى Gateway، بينما يُرفع الملف المحلي عبر source upload.
5. تحقق من حفظ Gateway job ID قبل polling، ثم راقب الحالات server-reported حتى completion أو failure.
6. عند completion، تحقق من تنزيل MP4 غير فارغ وفتحه من مكتبة التطبيق. عند failure، تحقق من رسالة آمنة وإمكان retry دون تسريب token أو مسار الخادم.
7. أعد تشغيل التطبيق أثناء polling وتحقق من أن WorkManager يعاود lifecycle من job state المحفوظ، لا من progress وهمي.

هذا smoke test يثبت تكامل العميل والخادم، لكنه لا يثبت جاهزية مزودي النشر الاجتماعي؛ تلك الواجهات في Gateway قد تبقى mock/development-only وفق تقرير التدقيق.[1]

## Release signing والتوزيع

التوقيع الإنتاجي يجب أن يتم في CI أو secret manager. يضبط build pipeline `KEYSTORE_PATH` و`STORE_PASSWORD` و`KEY_PASSWORD`، ويفعّل `REQUIRE_RELEASE_SIGNING=true`. لا ترفع keystore أو كلمات المرور إلى المستودع. مفتاح CI المؤقت الموجود في workflow يصلح فقط لـartifact اختبار قصير العمر، وليس لتحديث تطبيق منشور.

قبل التوزيع، تحقق من:

```bash
BUILD_TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
APK=android/app/build/outputs/apk/release/app-release.apk
"$BUILD_TOOLS/apksigner" verify --verbose "$APK"
"$BUILD_TOOLS/aapt2" dump badging "$APK" | grep "package: name='com.aistudio.opuspro.apk'"
```

يجب الاحتفاظ بالمفتاح الإنتاجي نفسه لكل تحديث لنفس application ID. إذا تغير application ID أو signing identity، فسيُعامل الإصدار كتطبيق مختلف أو كتحديث غير قابل للتثبيت فوق النسخة السابقة.[4]

## CI artifact

workflow `.github/workflows/android-build.yml` يبني على Ubuntu باستخدام JDK 17، يثبت Android SDK packages المطلوبة، يشغّل unit tests وlint، يبني Debug وsigned Release، ثم يتحقق من signature وapplication ID وSDK bounds ويرفع APKs كـartifacts. هذا لا يستبدل اختبار جهاز حقيقي؛ يجب إضافة device runner أو تنفيذ smoke test يدوي قبل إعلان الإصدار production-ready.

## حدود التحقق في هذه المهمة

تم التحقق محليًا من clean Gradle path وunit tests بعد إصلاح cache dependency الفاسد، ومن `assembleRelease` وAPK packaging ووجود ABIs الأربعة في universal APK. لم يكن هناك جهاز Android فعلي أو emulator متصل في بيئة التنفيذ؛ لذلك لا يجوز اعتبار install وlaunch وbackend E2E مثبتة حتى تُنفذ أوامر `adb` أعلاه على جهاز يمكنه الوصول إلى الـPrivate Backend.

## References

[1]: ../docs/MASTER-ARCHITECTURE.md "ISM canonical architecture and Android boundary"
[2]: ../android/app/src/main/AndroidManifest.xml "Release manifest and permissions"
[3]: https://developer.android.com/privacy-and-security/security-config "Network security configuration | Android Developers"
[4]: https://developer.android.com/studio/publish/app-signing "Sign your app | Android Developers"
