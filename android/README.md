# تطبيق ISM Android

هذا المجلد يحتوي على تطبيق ISM للموبايل، وهو عميل Android أصلي مبني باستخدام Kotlin وJetpack Compose وRoom وWorkManager وMedia3.

## تجربة الاستخدام

واجهة التطبيق موجهة للاستخدام الشخصي السريع: الشاشة الرئيسية تبدأ بمصدر فيديو أو ملف من Photo Picker، والاستوديو يعرض نتائج القص، والمكتبة تحفظ المشاريع، بينما يجمع «مركز التحكم» التحليلات والمقارنة وإدارة Gateway دون ازدحام شريط التنقل.

## محرك المعالجة

يستخدم التطبيق طبقة `ProcessingEngine` بمسار واحد فقط هو `REMOTE_GATEWAY`. لا توجد معالجة فيديو محلية ولا fallback محلي. بعد اختيار الملف ينسخ التطبيق URI إلى مساحة خاصة، ويحفظ job في Room، ثم يجدول `VideoProcessingWorker` عبر WorkManager. العامل يستخدم عميل private backend بعقد `/jobs/*` multipart، يرفع الفيديو، ينشئ job بعيداً بمفتاح idempotency، يستطلع الحالة، يستأنف checkpoint عند الحاجة، ينزّل MP4 الناتج، ويحفظ المشروع والمقاطع محلياً. تفاصيل العقد في [`../docs/API.md`](../docs/API.md).

يملك Gateway محرك Python وFFmpeg ومفاتيح Gemini ومفاتيح المزودين. لا يحتوي APK على Python أو `uv` أو Node أو Rust أو desktop FFmpeg runtime، ولا يضمّن Android أي Gemini secret؛ وقد تستخدم بعض أدوات الذكاء الاختيارية مفتاحاً يضيفه المستخدم بنفسه. لا تُقبل عناوين private backend إلا عبر HTTPS مع token غير فارغ، ويُسمح بـHTTP فقط لـlocalhost في الاختبارات المحلية.

## البناء والتحقق

من داخل هذا المجلد، يتطلب البناء JDK 21 وAndroid SDK 36 فقط على جهاز التطوير:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lint
./gradlew :app:assembleRelease
```

لإخراج APK قابل للتثبيت يجب تمرير `KEYSTORE_PATH` و`STORE_PASSWORD` و`KEY_PASSWORD` إلى Gradle. الناتج هو `app/build/outputs/apk/release/app-release.apk`. لا تُحفظ مفاتيح التوقيع في المستودع. يقوم CI كذلك ببناء `app-release-unsigned.apk` ورفعه كأثر غير موقّع؛ أما التوزيع الفعلي فيتطلب مفاتيح خارج المستودع.

## الصلاحيات والتخزين

يطلب التطبيق `INTERNET` للشبكة، و`POST_NOTIFICATIONS` على Android 13+ لعرض حالة المهمة، و`FOREGROUND_SERVICE` و`FOREGROUND_SERVICE_DATA_SYNC` للمهام الطويلة. WorkManager يضيف صلاحياته الداخلية اللازمة لتشغيل واستعادة العمل. لا توجد صلاحيات قراءة أو كتابة تخزين عام؛ Photo Picker و`GetContent` يقدمان URI للملف، ثم يُنسخ إلى `filesDir/source_media` كي لا تعتمد المهمة الخلفية على صلاحية مؤقتة. النتائج تحفظ في `filesDir/gateway_exports`.

## الخلفية وإعادة التشغيل والأعطال

المهام الطويلة تعمل في `CoroutineWorker` على `Dispatchers.IO` مع إشعار foreground وتحديث progress، وقيد network، وexponential backoff. حالة المهمة و`remoteGatewayJobId` محفوظان في Room؛ لذلك لا تعتمد المعالجة على بقاء Activity مفتوحة. يدعم التطبيق الإلغاء وإعادة المحاولة والاستئناف من checkpoint البعيد. يحفظ `OpusApplication` آخر stack trace في `filesDir/last_crash.txt` ثم يمرر crash إلى النظام بدلاً من ابتلاعه.

## حدود الاختبار

نجحت اختبارات الوحدة وAndroid Lint وrelease assembly وفحوص توقيع APK وmanifest. تم إنشاء AVD Android 15 نظيف ومحاولة تثبيت وتشغيل APK، لكن بيئة TCG بلا acceleration لم تُبقِ المحاكي مستقراً حتى اكتمال الإقلاع؛ يجب إكمال install/launch/restart/file-picker/background على جهاز فعلي أو emulator مستقر قبل النشر العام.

راجع [`../docs/RELEASE.md`](../docs/RELEASE.md) و[`../MANUS_HANDOFF.md`](../MANUS_HANDOFF.md) لمصفوفة الإصدار الكاملة.

## العلاقة مع المستودع

المشروع الأصلي في الجذر هو تطبيق سطح المكتب وطبقة Gateway وPython Pipeline. يبقى تطبيق Android هنا كعميل مستقل يستخدم عقد Gateway المشترك، وتبقى المعالجة الثقيلة عن بُعد على Gateway بدل تضمين Python أو FFmpeg أو النماذج داخل APK.

## الترخيص

المشروع مرخّص وفق AGPL-3.0-or-later. راجع `../LICENSE` و`../docs/THIRD_PARTY_LICENSES.md`.
