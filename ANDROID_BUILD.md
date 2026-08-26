# Android Build & Release Report

**النطاق:** Android BUILD & RELEASE فقط.
**المستودع:** `ISM-dragon/-1`
**الفرع المفحوص:** `main`
**Commit النهائي الذي تمت مراجعته وبُني منه الـAPK:** `0262537b8c45e0b4b864ca3f75cc875d1388b56e` (`fix(ci): install provider test dependencies`).
**Commit الأساس للفحص الأولي:** `e21f8911bcdc41734631897f9841a618dced3197`.
**تاريخ الفحص:** 2026-08-26
**المالك الافتراضي للتقرير:** Manus AI

## القرار التنفيذي

تم إنتاج **APK Release موقّع حقيقي** بنجاح من `origin/main` الأحدث. أثناء الفحص الأولي على commit الأساس ظهر خطأ توافق في `OpusBottomNav.kt`، ثم تبيّن قبل commit أن الإصلاح المطابق (`PrimaryItem` كامتداد لـ`RowScope`) موجود بالفعل في `origin/main` ضمن تغييرات جلسة Android الأخرى. لذلك لم يُعد هذا العمل تطبيق إصلاح UI ولم يغيّر خوارزميات المحرك أو scoring أو تصميم الواجهة.

ملف الإصدار النهائي هو `android/app/build/outputs/apk/release/app-release.apk`. حجمه **60,078,050 بايت**، وبصمة SHA-256 هي `67854dfc3e746f6cfd455261b2f371c426d01c5040b3f0adad89c302444d5e5e`.

> **النتيجة:** build وpackage وsigning وlint وABI والفحوص الساكنة ناجحة. اختبار install/launch على جهاز Android فعلي أو Emulator لم يكتمل لأن بيئة الاختبار لا تحتوي جهازاً جاهزاً، وEmulator بلا KVM بقي offline قبل تشغيل خدمات `package` و`activity`. لذلك لا يجوز اعتبار اختبار الجهاز وAPI end-to-end ناجحين في هذه الجلسة؛ اختبار API المنفصل للعقد الخلفية نجح عبر HTTP.

## ملخص النتائج

| الفحص | النتيجة | الدليل أو الملاحظة |
|---|---:|---|
| Git state قبل التعديل | ناجح | `main` متزامن مع `origin/main` عند commit الأساس، ولا توجد تغييرات مصدرية مسبقة في نطاق Android |
| Java/JDK | ناجح | OpenJDK 21.0.12 مع `javac` كامل |
| Gradle Wrapper | ناجح | Gradle 9.3.1 من `gradle-wrapper.properties` |
| Android Gradle Plugin | ناجح | AGP 9.1.1 من `libs.versions.toml` |
| Android SDK | ناجح | Platform 36.1 وBuild Tools 36.1.0 وPlatform Tools 37.0.1 |
| `clean` | ناجح | `./gradlew clean` ضمن البناء النهائي |
| Release compile/package | ناجح | `./gradlew clean :app:assembleRelease --no-daemon`؛ `BUILD SUCCESSFUL` |
| APK signing | ناجح | APK Signature Scheme v2؛ signer واحد؛ RSA 4096 |
| Manifest/permissions | ناجح مع ملاحظات موثقة | `apkanalyzer manifest` و`aapt2 dump permissions` |
| ABI | ناجح | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| min/target SDK | ناجح | min SDK 24، target SDK 36، compile SDK 36 |
| `lintRelease` | ناجح | `BUILD SUCCESSFUL`؛ 75 issue، منها 74 Warning و0 Error |
| Unit tests | ناجح | `:app:testDebugUnitTest`؛ اكتملت suite بنجاح بعد البناء من `origin/main` الأحدث |
| install APK | محجوب بيئياً | ADB ظهر offline أو بلا Android package service |
| launch APK | محجوب بيئياً | `activity` service غير متاح لأن boot لم يكتمل |
| API contract smoke test | ناجح منفصلاً | Gateway mock محلي: `/health`, `/v1/auth/session`, `/v1/processing/capabilities` أعادت HTTP 200؛ الاختبار ليس من داخل APK |

## إعدادات Android التي تم تدقيقها

### Gradle وSDK

| الإعداد | القيمة الفعلية |
|---|---|
| Root project | `android` |
| Module | `:app` |
| Namespace | `com.example` |
| Application ID | `com.aistudio.opuspro.apk` |
| Version code | `5` |
| Version name | `0.10.1` |
| `compileSdk` | 36 مع `minorApiLevel = 1` |
| `minSdk` | 24 |
| `targetSdk` | 36 |
| Java source/target | 11 |
| Kotlin | 2.3.20 بحسب version catalog |
| AGP | 9.1.1 |
| Gradle | 9.3.1 |
| Minification | disabled (`isMinifyEnabled = false`) |

تم تثبيت SDK في بيئة الفحص تحت `/home/ubuntu/android-sdk`، لكن هذا المسار ليس متطلباً من APK النهائي؛ هو متطلب build فقط. لا توجد أي إشارة إلى Python أو `uv` أو `pip` أو Node أو Rust أو FFmpeg desktop ضمن محتويات APK.

### إصلاح التوافق الذي تم التحقق منه

الإصلاح المطلوب للبناء كان موجوداً بالفعل في `origin/main` الأحدث، ولذلك لم يدخل أي تعديل مصدر Android في commit هذه المهمة. شكله في الشجرة النهائية هو:

```diff
 import androidx.compose.foundation.layout.Row
+import androidx.compose.foundation.layout.RowScope
 ...
-private fun PrimaryItem(
+private fun RowScope.PrimaryItem(
```

المسار: `android/app/src/main/java/com/example/ui/components/OpusBottomNav.kt`. هذا إصلاح توقيع compile-time فقط، ويحافظ على نفس العناصر والألوان والتخطيط. تم التحقق منه في الشجرة النهائية، ولم يُعدّل هذا العمل `engine` أو `scoring` أو pipeline algorithms أو ملفات UI أخرى.

### Manifest والصلاحيات

الـmain manifest يصرّح صراحةً بـ`INTERNET` و`POST_NOTIFICATIONS`، كما يصرّح بـ`WRITE_EXTERNAL_STORAGE` حتى API 28 فقط. وبعد دمج الاعتماديات، يظهر في APK أيضاً عدد من الصلاحيات اللازمة للمكتبات والخدمات، منها `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE`، و`READ_EXTERNAL_STORAGE` حتى API 28، إضافة إلى صلاحية Google Services وصلاحية dynamic receiver غير المصدّرة.

الـlauncher activity هي `.MainActivity` مع `android:exported="true"` وintent filter من نوع `MAIN`/`LAUNCHER`. لا توجد خدمة أو activity تطبيقية مصدّرة إضافية في manifest الرئيسي.

### Network security

لا يضع release manifest `android:usesCleartextTraffic="true"`، ولا يضع `android:networkSecurityConfig`. كما أن `src/debug/AndroidManifest.xml` وحده يفعّل cleartext traffic للتطوير. هذا يعني أن release يعتمد سياسة Android الآمنة الافتراضية، وهي مناسبة لـHTTPS وليست مناسبة لاتصال HTTP عادي إلى backend على الشبكة المحلية في Android API 28 وما بعده [1].

كود Android الخاص بالـGateway يقبل HTTPS، ويستثني localhost والعناوين المحلية الخاصة في مرحلة التحقق من العنوان. لكن هذا الاستثناء البرمجي لا يلغي سياسة Android للـcleartext. لذلك يجب أن يكون الـPrivate Backend المعروض للـrelease عبر **HTTPS وشهادة موثوقة**. إذا كان backend سيبقى HTTP على `192.168.x.x` أو `10.x.x.x`، فهذه تبعية Infra/Backend تحتاج سياسة network security مقيدة بمضيف محدد أو TLS termination؛ لم يتم تخفيف سياسة release العامة تلقائياً.

### Release signing

إعداد signing الحالي مشروط بوجود المتغيرات التالية وملف keystore صالح:

| المتغير | الاستخدام |
|---|---|
| `KEYSTORE_PATH` | مسار keystore |
| `STORE_PASSWORD` | كلمة مرور keystore |
| `KEY_PASSWORD` | كلمة مرور المفتاح |
| key alias | ثابت في Gradle: `upload` |

إذا لم تتوفر هذه القيم، ينتج Gradle `app-release-unsigned.apk` بدلاً من APK قابل للتوزيع. في هذا الفحص استُخدم keystore محلي مؤقت خارج المستودع، ولم تُحفظ كلمة مروره أو ملفه في Git. APK النهائي تم التحقق منه بواسطة `apksigner`:

| الخاصية | القيمة |
|---|---|
| Signature scheme | v2: true؛ v1/v3/v3.1/v4: false |
| Number of signers | 1 |
| Certificate DN | `CN=Local Release, OU=Android, O=ISM, L=Local, ST=Local, C=US` |
| Certificate SHA-256 | `b2c7c9c2db3a60a07b696340e1d185d56b304270ed0d0b5f39bf2497b73a0ecb` |
| Key algorithm/size | RSA / 4096 bit |

للتوزيع الإنتاجي، يجب استبدال الشهادة المحلية المؤقتة بشهادة release المعتمدة من مالك التطبيق، وتمرير الأسرار من CI أو secret manager دون commit.

## APK package وABI وruntime independence

نتيجة `aapt2 dump badging` للـAPK النهائي:

```text
package: name='com.aistudio.opuspro.apk' versionCode='5' versionName='0.10.1'
compileSdkVersion='36'
targetSdkVersion:'36'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
```

الـAPK الواحد يحتوي على أربع ABI، ولذلك يمكن اختباره على أجهزة ARM64 وARM32 وعلى x86/x86_64 Emulator. حجم APK الكبير نسبياً متوقع بسبب Compose وFirebase وMedia3 وML Kit؛ ولم تظهر داخله ملفات أو مكتبات تشير إلى Python أو uv أو pip أو Node أو Rust أو `ffmpeg`/`ffprobe` desktop.

هذا لا يعني أن الـBackend لا يحتاج هذه الأدوات. استجابة capabilities من Gateway تذكر Python وuv وFFmpeg وFFprobe كمتطلبات backend-side. هذه المتطلبات تبقى على الخادم الخاص ولا تُشحن داخل Android client، وهو الفصل المطلوب في هذا الإصدار.

## أوامر البناء وإعادة الإنتاج

بعد توفير JDK وAndroid SDK، يمكن إعادة البناء من مجلد `android` كما يلي:

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export KEYSTORE_PATH=/secure/path/release-upload.jks
export STORE_PASSWORD='provided-by-ci-secret'
export KEY_PASSWORD='provided-by-ci-secret'

./gradlew clean :app:assembleRelease --no-daemon

$ANDROID_SDK_ROOT/build-tools/36.1.0/apksigner verify --verbose \
  app/build/outputs/apk/release/app-release.apk
```

للفحص الساكن:

```bash
$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/apkanalyzer manifest permissions \
  app/build/outputs/apk/release/app-release.apk

$ANDROID_SDK_ROOT/build-tools/36.1.0/aapt2 dump badging \
  app/build/outputs/apk/release/app-release.apk
```

## نتائج الاختبارات

### Build وlint

البناء النهائي من الصفر نجح بهذه المهمة:

```text
./gradlew clean :app:assembleRelease --no-daemon
BUILD SUCCESSFUL
```

كما نجح:

```text
./gradlew :app:lintRelease --no-daemon
BUILD SUCCESSFUL
```

تقرير lint يحتوي 75 issue، منها 74 Warning و0 Error. التحذيرات الموجودة تتضمن تحديثات dependency/AGP مقترحة وتحذيرات locale وdeprecated APIs، لكنها لم تمنع release packaging. لم يتم تعديلها لأنها خارج نطاق BUILD & RELEASE الفوري ولأن بعضها يقع في منطق أو واجهة ليست مملوكة لهذه المهمة.

### Unit tests

تم تشغيل `:app:testDebugUnitTest` على `origin/main` الأحدث، واكتملت suite بنجاح. كان الفحص الأولي على commit الأساس الأقدم قد أظهر فشلين في `ProcessingEngineTest`، لكن تحديثات الجلسات الأخرى على `origin/main` تضمنت إصلاحات routing ذات الصلة. هذه فحوص سلوك routing/engine وليست فحوص packaging، ولم يتم تعديل engine algorithms أو scoring في هذا العمل.

### install وlaunch

تم بناء APK موقّع ومحاولة تثبيته عبر:

```bash
adb -s emulator-5554 install -r \
  android/app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 shell am start -W \
  -n com.aistudio.opuspro.apk/.MainActivity
```

لكن Android Emulator لم يكتمل إقلاعه في بيئة الفحص. لا يوجد `/dev/kvm`، ومحاولتا API 36 وATD API 35 بقيتا offline قبل جاهزية system services، وأعادت ADB:

```text
cmd: Can't find service: package
cmd: Can't find service: activity
```

هذه نتيجة **محجوبة بيئياً** وليست دليلاً على فشل APK. يلزم تنفيذ install/launch مرة أخرى على جهاز Android أو Emulator مزود بـKVM/مسرّع فعلي قبل إعلان هذا الجزء passed.

### API connection

تم تشغيل Gateway من نفس المستودع بوضع mock مستقل، ثم اختبار العقد التي يستخدمها Android client:

| Endpoint | النتيجة |
|---|---:|
| `GET /health` | HTTP 200 |
| `GET /v1/auth/session` | HTTP 200 |
| `GET /v1/processing/capabilities` | HTTP 200 |

هذا يثبت أن عقد HTTP الخلفية قابلة للوصول وأن responses الأساسية متاحة. لكنه **ليس اختباراً end-to-end من داخل APK** لأن Emulator لم يصبح online، كما أنه ليس اتصالاً بخادم production/private فعلي. يلزم تمرير عنوان HTTPS الحقيقي وtoken الصحيح في اختبار الجهاز النهائي؛ لا يوجد في المستودع أو جلسة الفحص عنوان production خاص قابل للاستخدام بأمان.

## Handoff مطلوب للـPrivate Backend/Infra

التبعية الوحيدة خارج ملكية Android BUILD & RELEASE هي توفير endpoint خاص قابل للوصول من الجهاز. يجب أن يوفّر فريق Backend/Infra عنوان HTTPS النهائي، الشهادة الموثوقة، وtoken اختبار محدود الصلاحية. لا ينبغي وضع token أو certificate private key داخل Git أو داخل APK.

يوجد ملف handoff مستقل باسم `ANDROID_BACKEND_HANDOFF.md` يختصر هذه المتطلبات ولا يغيّر أي كود backend.

## الملفات والـcommit

لم يغيّر commit هذه المهمة أي ملف source؛ إصلاح compile-time كان موجوداً في `origin/main` قبل commitنا. أُضيف هذا التقرير وملف handoff فقط. لم يتم commit ملفات build أو keystore أو أسرار. APK النهائي مرفق خارج Git كartifact؛ عدم commit binary بحجم 58MB يحافظ على المستودع خفيفاً ولا يغيّر سياسة Git الحالية.

## المراجع

[1]: https://developer.android.com/privacy-and-security/security-config "Android Developers: Network security configuration and cleartext traffic"
[2]: https://developer.android.com/build/building-cmdline "Android Developers: Build your app from the command line"
[3]: https://developer.android.com/studio/publish/app-signing "Android Developers: Sign your app"
[4]: https://developer.android.com/guide/topics/manifest/uses-sdk-element "Android Developers: <uses-sdk> manifest element"
[5]: https://github.com/ISM-dragon/-1/blob/0262537b8c45e0b4b864ca3f75cc875d1388b56e/android/app/build.gradle.kts "Repository: Android app Gradle configuration at audited commit"
[6]: https://github.com/ISM-dragon/-1/blob/0262537b8c45e0b4b864ca3f75cc875d1388b56e/android/app/src/main/AndroidManifest.xml "Repository: Android main manifest at audited commit"
[7]: https://github.com/ISM-dragon/-1/blob/0262537b8c45e0b4b864ca3f75cc875d1388b56e/android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt "Repository: Android private Gateway client"
