# Android Build

**الحالة:** مسار البناء الرسمي لتطبيق Android Native Compose في `android/`.

> هذا التطبيق هو عميل Android فقط. لا يُضمّن APK Python أو `uv` أو `pip` أو Node أو Rust أو FFmpeg الخاص بسطح المكتب؛ المعالجة الثقيلة والـFFmpeg والنماذج تبقى على الـPrivate Gateway وفق الحدود المعمارية للمستودع.[1]

## نقطة البداية

نفّذ الأوامر من جذر المستودع بعد checkout نظيف:

```bash
git clone https://github.com/ISM-dragon/-1.git
cd -1
git checkout agent/build
cd android
```

يحتوي المستودع على Gradle Wrapper، ويجب استخدام `./gradlew` بدل تثبيت Gradle عالمي. يثبت wrapper الإصدار `9.3.1`، بينما يستخدم Android Gradle Plugin الإصدار المسجل في `android/gradle/libs.versions.toml`.[2]

## مصفوفة الأدوات

| المكوّن | الإصدار/القيمة | مصدر الحقيقة |
|---|---:|---|
| JDK | 17 | CI وشرط AGP؛ استخدم JDK كاملًا يتضمن `javac` |
| Gradle Wrapper | 9.3.1 | `android/gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.1.1 | `android/gradle/libs.versions.toml` |
| compile SDK | Android 36، minor API level 1 | `android/app/build.gradle.kts` |
| Build Tools | 36.0.0 | CI وعمليات التحقق |
| min SDK | 24 | `android/app/build.gradle.kts` |
| target SDK | 36 | `android/app/build.gradle.kts` |
| Application ID | `com.aistudio.opuspro.apk` | `android/app/build.gradle.kts` |
| ABI | Universal APK: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` | مخرجات `mergeReleaseNativeLibs` |

تثبيت SDK محليًا باستخدام command-line tools:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_SDK_ROOT="$HOME/android-sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

sdkmanager --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0"
yes | sdkmanager --sdk_root="$ANDROID_SDK_ROOT" --licenses
```

يكرر workflow نفسه تثبيت حزم SDK المطلوبة قبل البناء، حتى لا تعتمد النتيجة على Android Studio أو حالة جهاز المطور.[3]

## البناء والتحقق

```bash
./gradlew clean
./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1 --stacktrace
./gradlew :app:lint --no-daemon --max-workers=1 --stacktrace
./gradlew :app:assembleDebug --no-daemon --max-workers=1 --stacktrace
./gradlew :app:assembleRelease --no-daemon --max-workers=1 --stacktrace
```

مخرجات البناء هي:

```text
android/app/build/outputs/apk/debug/app-debug.apk
android/app/build/outputs/apk/release/app-release.apk
```

لا يضمن `assembleRelease` وحده وجود APK قابل للتثبيت إذا لم تُضبط مفاتيح release؛ في هذه الحالة تكون النتيجة المحلية `app-release-unsigned.apk`. عند ضبط `REQUIRE_RELEASE_SIGNING=true` يفشل البناء صراحة إذا كانت إعدادات التوقيع ناقصة، بدل إنتاج artifact يمكن الخلط بينه وبين حزمة توزيع.

للتحقق من الحزمة والتوقيع:

```bash
BUILD_TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
APK=app/build/outputs/apk/release/app-release.apk
"$BUILD_TOOLS/apksigner" verify --verbose "$APK"
"$BUILD_TOOLS/aapt2" dump badging "$APK" | grep "package: name='com.aistudio.opuspro.apk'"
"$BUILD_TOOLS/aapt2" dump badging "$APK" | grep "sdkVersion:'24'"
"$BUILD_TOOLS/aapt2" dump badging "$APK" | grep "targetSdkVersion:'36'"
unzip -l "$APK" | grep 'lib/'
```

الحزمة الحالية single/universal APK وليست split APK؛ لذلك لا يحتاج التثبيت اليدوي إلى اختيار ملف ABI منفصل. تتضمن المخرجات native libraries للأجهزة الحديثة ARM وللمحاكيات x86. هذا الاختيار يفضّل قابلية التثبيت على تقليل الحجم، ولا يغيّر منطق التطبيق.

## Release signing

المفاتيح لا تُحفظ في Git. يقرأ Gradle المتغيرات التالية:

| المتغير | المعنى |
|---|---|
| `KEYSTORE_PATH` | المسار إلى keystore release |
| `STORE_PASSWORD` | كلمة مرور keystore |
| `KEY_PASSWORD` | كلمة مرور alias |
| `REQUIRE_RELEASE_SIGNING=true` | يجعل التوقيع شرطًا إلزاميًا للبناء |

يستخدم الإعداد الحالي alias باسم `upload`. مثال بناء محلي بحزمة smoke مؤقتة:

```bash
keytool -genkeypair -v \
  -keystore "$PWD/ci-release.keystore" \
  -storepass android-ci-release \
  -keypass android-ci-release \
  -alias upload \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=ISM CI Release,O=ISM,C=US"

export KEYSTORE_PATH="$PWD/ci-release.keystore"
export STORE_PASSWORD=android-ci-release
export KEY_PASSWORD=android-ci-release
export REQUIRE_RELEASE_SIGNING=true
./gradlew :app:assembleRelease --no-daemon --max-workers=1
```

هذا المفتاح مناسب للتحقق المؤقت والتثبيت على جهاز اختبار فقط. يجب أن يستخدم التوزيع الحقيقي keystore إنتاج محفوظًا في secret manager أو GitHub Actions secrets، مع عدم تغيير alias أو المفتاح بين إصدارات التطبيق المنشورة. توقيع APK شرط Android للتثبيت والتحديث.[4]

## Manifest والصلاحيات

يعلن `android/app/src/main/AndroidManifest.xml` عن `INTERNET` للاتصال بالـGateway و`POST_NOTIFICATIONS` للإشعارات و`WRITE_EXTERNAL_STORAGE` حتى API 28 فقط للتوافق مع الأجهزة القديمة. النشاط الرئيسي exported مع launcher intent، ولا توجد صلاحيات provider أو أسرار اجتماعية داخل APK.[5]

نسخة debug فقط تفعّل `usesCleartextTraffic=true` لتسهيل Gateway محلي على شبكة الاختبار. لا يفعّل manifest release هذا الاستثناء؛ لذلك يجب أن يكون عنوان Gateway في release عبر HTTPS. لا تُستخدم مفاتيح Gemini أو مفاتيح مزودي النشر كإعدادات APK؛ الـGateway يحتفظ بأسرار الخادم.[1]

## إعادة الإنتاج

لنتيجة قابلة للمقارنة، استخدم checkout نظيفًا، JDK 17، SDK packages أعلاه، Gradle Wrapper، وبيئة signing معلنة. لا تعتمد على Python أو `uv` أو `pip` أو Node أو Rust أو FFmpeg desktop أو Android Studio لتنفيذ APK. يقوم CI بتشغيل الاختبارات وlint ثم يبني Debug وRelease، ويتحقق من `apksigner` وapplication ID وmin/target SDK قبل رفع artifacts.[3]

## ملاحظات التحقق

في بيئة العمل الحالية نجح `assembleRelease` بعد تثبيت JDK 17 وAndroid SDK، وأنتجت الحزمة universal native ABIs. كان فشل Robolectric الأول ناتجًا عن checksum mismatch في cache المحلي لـ`android-all-instrumented`; بعد حذف cache وإعادة تنزيل dependency نجحت اختبارات الوحدة. يلزم جهاز Android فعلي أو emulator متصل للتحقق من install/launch وE2E، وهو ليس جزءًا من بيئة البناء وحدها.

## References

[1]: ../docs/MASTER-ARCHITECTURE.md "ISM canonical architecture"
[2]: ../android/gradle/wrapper/gradle-wrapper.properties "Gradle Wrapper configuration"
[3]: ../.github/workflows/android-build.yml "Reproducible Android CI workflow"
[4]: https://developer.android.com/studio/publish/app-signing "Sign your app | Android Developers"
[5]: ../android/app/src/main/AndroidManifest.xml "Android application manifest"
