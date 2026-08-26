# MANUS HANDOFF — ISM Android application

## نطاق التسليم

تم تنفيذ تطبيق Android أصلي مخصص لـ ISM يعمل كعميل Gateway بعيد. Android مسؤول عن **UI، file picker، اختيار الفيديو، الرفع، إنشاء الوظيفة، عرض الحالة والتقدم، معاينة النتائج، تصفح المقاطع، تنزيل النتائج، والتحرير الأساسي لبداية ونهاية المقطع**. لا يحتوي التدفق الجديد على Python أو FFmpeg أو خط معالجة وسائط محلي.

> القاعدة المعمارية: Android هو stateful client فوق Gateway؛ Gateway هو المصدر authoritative لحالة الوظيفة والتقدم والنتائج، ولا تُنقل تفاصيل Python أو Pipeline الداخلية إلى الواجهة.

## ملاحظة عن مصادر التسليم

لم توجد في النسخة المستنسخة ملفات باسم `MANUS_HANDOFF.md` أو `BACKEND.md`. لذلك استُخدمت وثائق العقد ومسؤوليات العميل الموجودة فعليًا: `docs/API-CONTRACT.md`، و`docs/CLIENT-RESPONSIBILITIES.md`، و`docs/android/ANDROID_PROCESSING_FLOW.md`، إضافة إلى تعريفات endpoints في `gateway/main.py`. هذا الملف هو وثيقة التسليم المحدثة المطلوبة للنسخة الحالية.

## البنية المنفذة

| الطبقة | الملف/المكوّن | المسؤولية |
|---|---|---|
| نماذج العقد | `android/app/src/main/java/com/example/remote/model/RemoteProcessingModels.kt` | حالات Gateway canonical، أخطاء العقد، الوظائف، المقاطع، النتائج، وحالة UI |
| API client | `remote/data/GatewayApiClient.kt` | `/health`، session، capabilities، upload، create job، polling، cancel/retry/resume، media download |
| التخزين | `remote/data/RemoteProcessingStore.kt` | حفظ الوظيفة النشطة، remote job ID، idempotency key، النتائج، وإعدادات Gateway |
| orchestration | `remote/data/RemoteProcessingCoordinator.kt` | unique WorkManager، منع الوظائف المكررة، cancel/retry/resume، إعادة الجدولة |
| background execution | `remote/data/RemoteProcessingWorker.kt` | upload مرة واحدة، إنشاء idempotent job، polling، resume، download والتحقق من النتائج |
| presentation | `remote/ui/RemoteStudioViewModel.kt` | تنقل الشاشة وحالة UI وربط الإجراءات بالتخزين والمنسق |
| screens | `remote/ui/RemoteStudioScreens.kt` | Home، Import Video، Processing، Processing Error، Results، Clip Review، Settings |

## الشاشات ومسارات الاستخدام

| الشاشة | السلوك |
|---|---|
| Home | فتح استيراد فيديو، عرض آخر وظيفة، الوصول إلى الإعدادات، وتمييز وجود وظيفة قيد التنفيذ |
| Import Video | اختيار `video/*` مع persistable URI permission، عرض اسم وحجم الملف، بدء المعالجة |
| Processing | عرض `state` و`progress` و`stage` و`message` التي يعيدها Gateway، مع زر cancel |
| Processing Error | عرض رسالة ورمز آمنين، وإظهار retry فقط عندما تكون الوظيفة recoverable |
| Results | عرض المقاطع الحقيقية التي أعادها Gateway بعد تنزيلها والتحقق من حجمها |
| Clip Review | معاينة محلية آمنة، ضبط start/end عبر sliders، وحفظ التعديل الأساسي محليًا |
| Settings | حفظ Gateway URL ورمز الجلسة، اختبار الاتصال، وعدم طلب أو عرض Gemini secret |

## حالات الاعتماد والاستعادة

يُحفظ `local_job_id` و`remote_job_id` و`idempotency_key` قبل وبعد كل حد شبكي مهم. يستخدم WorkManager `ExistingWorkPolicy.KEEP` باسم فريد لكل وظيفة، لذلك لا ينشئ التطبيق وظيفة ثانية إذا كان العمل مجدولًا أو قيد التنفيذ بالفعل. عند بدء التطبيق، تُقرأ الوظيفة المحفوظة؛ إذا كانت غير نهائية يُعاد enqueue للعمل نفسه ويستأنف polling.

| الحالة | استجابة Android |
|---|---|
| انقطاع الشبكة | Network constraint + exponential backoff، إبقاء الوظيفة في التخزين وعرض انتظار عودة الاتصال |
| إعادة تشغيل التطبيق | إعادة قراءة الوظيفة وإعادة enqueue باستخدام نفس local/remote IDs |
| job already running | عدم إعادة إنشاء الوظيفة؛ الاستعلام باستخدام remote job ID المحفوظ |
| اكتملت أثناء إغلاق التطبيق | قراءة `COMPLETED` عند التشغيل التالي، تنزيل `results.render.outputs`، وفتح Results |
| `INTERRUPTED` | استدعاء `/resume` ثم متابعة polling |
| `FAILED` | عرض الخطأ الموحّد، مع retry فقط عند `recoverable=true` |
| cancel | استدعاء `/cancel` ثم حفظ الحالة النهائية التي يعيدها Gateway وإيقاف unique work |
| نتائج فارغة | اعتبارها فشلًا آمنًا `NO_VALID_CLIPS` بدل فتح Review فارغ وكأنه نجاح |

## عقد API المستخدم

يرسل التطبيق `Authorization: Bearer <gateway-session-token>` عند الحاجة، ويستخدم فقط الحقول العامة التالية:

```text
GET  /health
GET  /v1/auth/session
GET  /v1/processing/capabilities
POST /v1/sources/upload
POST /v1/processing/jobs
GET  /v1/processing/jobs/{id}
POST /v1/processing/jobs/{id}/cancel
POST /v1/processing/jobs/{id}/retry
POST /v1/processing/jobs/{id}/resume
GET  /v1/processing/jobs/{id}/media/{filename}
```

طلب الإنشاء يرسل `source` و`llm=gemini` و`captions=classic` و`mode=balanced` و`idempotency_key`. واجهة Android لا تعرف كيف ينفذ Gateway المعالجة، ولا تعرض مسارات ملفات خاصة أو stack traces أو secrets.

## الاختبارات والتحقق

تمت إضافة `RemoteGatewayApiContractTest` باستخدام MockWebServer وRobolectric. يغطي الاختبار اختبار الاتصال عبر health/session/capabilities، Authorization header، upload raw video stream، create job، polling للحالة `RENDERING` مع progress، قراءة `COMPLETED` و`results.render.outputs`، وتنزيل MP4 حقيقي والتحقق من محتواه.

تم تشغيل الاختبارات التالية بنجاح بعد تجهيز JDK 21 وAndroid SDK في بيئة البناء:

```text
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests com.example.RemoteGatewayApiContractTest
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

نتج APK Debug فعلي في:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

اختبار الاتصال الحالي هو اختبار عقد محلي مع Mock Gateway ولا يتطلب أسرارًا أو تشغيل Python. لا يمكن اعتبار اتصال Gateway الإنتاجي مُتحققًا دون عنوان Gateway ورمز جلسة حقيقيين؛ يستطيع المستخدم تشغيل **اختبار الاتصال** من Settings بعد إدخالهما.

## تشغيل التطبيق

```bash
cd android
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

بعد فتح التطبيق، انتقل إلى **Settings**، أدخل عنوان Gateway بصيغة HTTPS في البيئات البعيدة أو عنوانًا محليًا عند التطوير، أدخل Gateway session token، ثم شغّل **اختبار الاتصال**. بعد نجاح capabilities يمكن اختيار الفيديو وبدء الوظيفة.
