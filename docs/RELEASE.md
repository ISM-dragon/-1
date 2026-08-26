# Android Release Notes

## النطاق والنتيجة

هذا التقرير يوثق baseline APK سابقًا موقّعًا وقابلًا للتثبيت لتطبيق ISM Android، ويعمل كعميل خفيف أمام private Processing Gateway. بعد هذه الدفعة أُضيف resumable upload إلى العميل الفعلي؛ لذلك يجب إعادة بناء artifact وتحديث بصمته قبل اعتبارها release نهائية. لا يحتوي تطبيق Android على محرك Python أو `uv` أو Node أو Rust، ولا ينفّذ معالجة الفيديو الثقيلة محلياً. تبقى مفاتيح مزودي الذكاء الاصطناعي ومحرك Python داخل backend الخاص.

الـ APK الموثق في baseline السابق هو `android/app/build/outputs/apk/release/app-release.apk`، بحجم **55,596,707 بايت**، ومعرّف الحزمة `com.aistudio.opuspro.apk`، والإصدار السابق `0.10.1`، و`versionCode=5`. هذه البصمة ليست بصمة الكود الحالي بعد تعديل resumable upload؛ يجب توليد بصمة جديدة بعد build ناجح:

```text
deceadddb138251acd6da62478f8b8913f7620c3f25140d2e3c108805c5faf5a
```

تم توقيع artifact بمفتاح اختبار مؤقت خارج Git باستخدام APK Signature Scheme v2. هذا يكفي للتثبيت المباشر على جهاز Android، لكنه **ليس مفتاح النشر الإنتاجي أو مفتاح Play App Signing**. قبل النشر العام يجب تمرير `KEYSTORE_PATH` و`STORE_PASSWORD` و`KEY_PASSWORD` الخاصة بمالك المنتج إلى Gradle، وعدم مشاركة المفتاح المؤقت أو كلمات مروره.

## بناء الإصدار

يُبنى التطبيق من مجلد `android` باستخدام JDK 21 وAndroid SDK 36 وGradle wrapper الموجود في المستودع. لا يحتاج المستخدم النهائي إلى أي من هذه الأدوات؛ هي متطلبات build فقط، وليست dependencies داخل APK.

```bash
cd android
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export KEYSTORE_PATH=/secure/path/ism-release-upload.jks
export STORE_PASSWORD='***'
export KEY_PASSWORD='***'
./gradlew :app:assembleRelease
```

لإخراج APK قابل للتثبيت محلياً يجب أن تكون `KEYSTORE_PATH` وبيانات التوقيع صالحة. لا تُحفظ هذه القيم في المستودع أو في سجل CI.

## مصفوفة التحقق

| المجال | النتيجة | الدليل أو الملاحظة |
|---|---|---|
| Release build | baseline سابق ناجح؛ إعادة البناء مطلوبة | `:app:assembleRelease` نجح سابقًا مع `compileSdk=36` و`targetSdk=36` و`minSdk=24`؛ لم يُعاد في هذه الجلسة بسبب غياب Android SDK. |
| Unit tests | baseline سابق ناجح؛ إعادة التشغيل مطلوبة | `:app:testDebugUnitTest` نجح سابقًا؛ لم يُعاد بعد تعديل upload بسبب غياب Android SDK. |
| Android Lint | baseline سابق ناجح؛ إعادة التشغيل مطلوبة | `:app:lint` نجح سابقًا؛ لم يُعاد بعد تعديل upload بسبب غياب Android SDK. |
| APK signature | ناجح | `apksigner verify` أكد v2 مع signer واحد. |
| Package metadata | baseline سابق | إعداد المصدر الحالي هو `com.aistudio.opuspro.apk`, version `0.11.0`, `versionCode=6`; يلزم build جديد لإثبات artifact. |
| Forbidden runtimes | ناجح | فحص أرشيف APK لم يجد مسارات أو مكتبات Python/`uv`/Node/Rust/Cargo/FFmpeg runtime. |
| Network access | ناجح static | `INTERNET` موجودة؛ عميل Processing Gateway يفرض HTTPS. `ACCESS_NETWORK_STATE` أضيفت من WorkManager merge. |
| File picker | ناجح static | `PickVisualMedia(VideoOnly)` مع fallback للنظام؛ يتم نسخ URI إلى `filesDir/source_media`. هذا يوافق Photo Picker الذي يمنح وصولاً للوسائط المختارة فقط [2]. |
| Storage access | ناجح static | لا توجد `READ_MEDIA_VIDEO` أو `READ_EXTERNAL_STORAGE` أو `WRITE_EXTERNAL_STORAGE` في التطبيق؛ لا يحتاج التطبيق تخزيناً عاماً. الملفات المؤقتة والنتائج البعيدة تُحفظ داخل مساحة التطبيق. |
| Notifications | ناجح static | `POST_NOTIFICATIONS` مُعلن ويُطلب على Android 13+؛ قناة تقدم foreground وقناة نتائج موجودتان. إذن Android 13 يؤثر على إشعارات FGS غير المعفاة [3]. |
| Background/foreground | ناجح static | `CoroutineWorker` يستدعي `setForeground()` مع `SystemForegroundService` ونوع `dataSync`، ويحدّث إشعار التقدم. WorkManager يدعم long-running workers بهذه الآلية [1]. |
| Long-running job | ناجح static | الرفع والاستطلاع يعملان على `Dispatchers.IO`؛ القيود تتطلب network؛ إعادة المحاولة exponential حتى محاولتين؛ `remoteGatewayJobId` محفوظ في Room؛ cancel/retry/resume متاحون. |
| App restart | ناجح static | حالة job والمشروع في Room، وWorkManager يعيد جدولة العمل الفريد بعد إغلاق Activity أو إعادة تشغيل العملية؛ لا يعتمد worker على ذاكرة Activity. |
| Crash handling | ناجح static | `OpusApplication` يحفظ آخر stack trace في `filesDir/last_crash.txt` ثم يمرر الخطأ إلى handler السابق بدلاً من ابتلاع crash. |
| Clean Android device | غير مكتمل بسبب البيئة | أُنشئ AVD Android 15 نظيف وحُوول تشغيله وتثبيت APK؛ emulator يعمل عبر TCG بدون acceleration ولم يبق مستقراً حتى `sys.boot_completed=1`. لذلك لم تُسجّل نتيجة install/launch device-level كنجاح زائف. |

## الصلاحيات الفعلية

الحد الأدنى الذي يطلبه التطبيق صراحةً هو التالي:

| الصلاحية | الغرض | هل هي runtime؟ |
|---|---|---|
| `INTERNET` | رفع الفيديو واستطلاع private Gateway وتنزيل النتائج | لا |
| `POST_NOTIFICATIONS` | إشعار تقدم المعالجة ونتيجتها | نعم على Android 13+ |
| `FOREGROUND_SERVICE` | تشغيل WorkManager long-running worker في foreground | لا |
| `FOREGROUND_SERVICE_DATA_SYNC` | توصيف خدمة رفع/تنزيل البيانات على Android 14+ | لا |

يتمتع WorkManager بإضافة صلاحيات تشغيله الداخلية مثل `WAKE_LOCK` و`RECEIVE_BOOT_COMPLETED` إلى manifest المدمج؛ لا يعني ذلك أن التطبيق يقرأ ملفات المستخدم أو يراقب الموقع. لا توجد صلاحيات كاميرا أو موقع أو قراءة مكتبة الوسائط العامة.

## Private Processing Gateway

يجب ضبط عنوان Gateway ورمز الوصول من شاشة إعدادات Gateway داخل التطبيق. يجب أن يكون العنوان HTTPS كاملاً، ويجب ألا يوضع Gemini API key داخل APK أو داخل إعدادات العميل. في مسار معالجة الفيديو، يرسل Android الفيديو ورمز Gateway فقط؛ Gateway هو الذي يدير Python pipeline وFFmpeg ومفاتيح مزودي الذكاء الاصطناعي. تبقى بعض أدوات الذكاء الاختيارية في التطبيق مرتبطة بمفاتيح يضيفها المستخدم بنفسه، لكنها ليست محرك المعالجة ولا مفاتيح مضمّنة في APK.

تدفق المعالجة هو: اختيار فيديو من Photo Picker، نسخ URI إلى مساحة التطبيق، إنشاء job في Room، جدولة `VideoProcessingWorker`، حساب SHA-256 وإنشاء جلسة upload عبر `/v1/sources/uploads`، إرسال chunks مع offset و`Content-Range` ثم إكمال الجلسة، إنشاء job بعيد، استطلاع الحالة، تنزيل MP4 إلى `filesDir/gateway_exports/<jobId>`، ثم استيراد المقاطع إلى Room. عند إغلاق التطبيق لا تُفقد المهمة المجدولة؛ وعند فشل شبكي تعيد WorkManager العملية، ويعيد Gateway session نفسها عبر `(bytes, sha256)` ليستأنف الرفع من offset المثبت، ثم يمكن استئناف job البعيد عندما يكون checkpoint محفوظاً في Gateway.

## التثبيت للمستخدم النهائي

يُنقل `app-release.apk` إلى الهاتف، ثم يُفتح من مدير الملفات ويُؤكّد التثبيت عبر Android Package Installer. قد يطلب Android تفعيل السماح بالتثبيت من هذا المصدر مرة واحدة. بعد التثبيت يفتح المستخدم التطبيق، يمنح إذن الإشعارات عند الطلب، يضبط HTTPS Gateway ورمز الوصول، ثم يستخدم زر اختبار الاتصال قبل إرسال أول فيديو.

## اختبار المحاكي وحدوده

تم تثبيت Android SDK وsystem image Android 15 وإنشاء AVD باسم `clean_api35`. تعذر إكمال اختبار الجهاز لأن المحاكي في بيئة التنفيذ يعمل عبر software TCG بلا تسريع، ظهر online مؤقتاً ثم خرج قبل اكتمال الإقلاع. بناءً على ذلك، نتائج `assembleRelease` و`apksigner` و`aapt2` وunit tests وlint مؤكدة، بينما install/launch/file-picker/restart/background على جهاز Android فعلي يجب إكمالها على هاتف أو emulator مزود بتسريع مستقر قبل اعتماد النشر العام.

## مراجع Android الرسمية

[1]: https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running "Support for long-running workers — Android Developers"

[2]: https://developer.android.com/training/data-storage/shared/photo-picker "Photo picker — Android Developers"

[3]: https://developer.android.com/develop/ui/compose/notifications/notification-permission "Notification runtime permission — Android Developers"
