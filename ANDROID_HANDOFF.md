# ANDROID_HANDOFF

## النطاق والملكية

هذا التسليم يخص **Android client فقط**. التطبيق مسؤول عن اختيار الفيديو، واجهة الرفع، إنشاء ومتابعة مهمة Gateway، حفظ job ID وحالة المهمة، عرض النتائج، مراجعة المقاطع، إعدادات الاتصال، والتصدير عبر system document picker. لا يحتوي APK على Python أو `uv` أو WhisperX أو PyTorch، ولا يغير scoring أو diarization أو تنفيذ الـ backend.

لم يوجد ملف `BACKEND_HANDOFF.md` في المستودع عند بدء العمل. اعتمد التنفيذ على `docs/API-CONTRACT.md` و`docs/android/ANDROID_PROCESSING_FLOW.md` وعلى عميل Gateway الموجود مسبقًا في Android.

## ما تم تنفيذه

| المنطقة | التنفيذ |
|---|---|
| Home | واجهة ISM، زر استيراد، المهام النشطة والحديثة، واستئناف المهمة بعد إعادة التشغيل. |
| Import Video | `OpenDocument` لانتقاء `video/*`، حفظ صلاحية URI القابلة للاستمرار، بيانات العنوان والمدة والسياق والمنصة ونمط الترجمة ووضع المعالجة. |
| Upload / Create Job | استدعاء `OpusRepository.enqueueVideoProcessing`، الذي يثبت نسخة المصدر ويضع `VideoProcessingWorker` في WorkManager مع قيد اتصال الشبكة. |
| Processing | عرض النسبة والمرحلة والحالة الواردة من Room/Worker، مع الإلغاء وإعادة المحاولة والانتقال إلى النتائج بعد النجاح. |
| Restart survival | يبقى job ID وبيانات المهمة في Room، ويحفظ العميل آخر job ID في SharedPreferences، ويعيد WorkManager متابعة المهمة بعد إعادة إنشاء العملية. |
| Results | قراءة المشروع والمقاطع من Room، عرض score والنطاق الزمني والنص، ورفض فتح مراجعة فارغة عند عدم وجود مقاطع صالحة. |
| Clip Review | معاينة MP4 المنزّل عبر `VideoView`، عرض النص وشرح hook والدرجة، ثم التصدير إلى اختيار المستخدم. |
| Settings | حفظ Gateway Base URL وBearer token في إعدادات Android الحالية؛ token يُعرض كحقل كلمة مرور ولا تُخزّن مفاتيح مزود المحرك داخل العميل. |
| Export | نسخ ملف MP4 المحلي الذي نزّله Worker إلى URI يختاره المستخدم عبر `CreateDocument`; لا يكشف التطبيق مسارًا محليًا خامًا للمستخدم ولا ينفذ render محليًا. |

## API assumptions

العميل الحالي يتوقع أن يكون Gateway هو مصدر الحقيقة الوحيد لحالة المهمة والتقدم والنتائج. قيمة `progress` و`stage` و`message` المعروضة في شاشة Processing تأتي من استجابة Gateway ومن حالة Worker المستديمة، ولا يتم توليد تقدم اصطناعي في الواجهة.

| الوظيفة | الافتراض الحالي |
|---|---|
| Authentication | يرسل العميل `Authorization: Bearer <token>` عند وجود token. يمكن أن يكون token فارغًا في بيئة Gateway لا تتطلب مصادقة. |
| Source upload | `POST /v1/sources/upload` مع `Content-Type: video/mp4` يعيد JSON يحوي `source` عامًا وآمنًا للاستخدام في إنشاء المهمة. |
| Create job | `POST /v1/processing/jobs` مع `source`, `llm`, `captions`, `mode`, و`idempotency_key` يعيد `id`. |
| Polling | `GET /v1/processing/jobs/{id}` يعيد حالة المهمة، ويفضل `state` أو `status`، و`fraction` بين 0 و1، و`stage` و`message` عند توفرها. |
| Completion | تعتبر النتيجة صالحة فقط عندما تحتوي `results.render.outputs` على عناصر MP4 ذات `path` يبدأ بـ `http`. `done` أو `completed` بلا outputs ليست حالة نتائج ناجحة للمراجعة. |
| Media | كل output يعيد رابط Gateway آمنًا إلى `/v1/processing/jobs/{id}/media/{filename:path}`؛ لا يجوز إعادة مسار ملفات Pipeline المحلي إلى Android. |
| Controls | الإلغاء وإعادة المحاولة والاستئناف تستخدم `POST /v1/processing/jobs/{id}/cancel`, `/retry`, و`/resume`. |
| Download | Worker ينزّل كل output عبر HTTP إلى مجلد التطبيق الداخلي قبل استيراده إلى Room. |

## Backend endpoints المطلوبة أو المؤكدة

المسارات التالية هي الحد الأدنى الذي يحتاجه Android client الحالي. المسارات الستة الأولى موجودة في عقد Gateway الحالي؛ لا يلزم تعديل backend لتشغيل دورة الرفع والمعالجة والنتائج الحالية.

| Method | Endpoint | الحالة | ملاحظة Android |
|---|---|---|---|
| `POST` | `/v1/sources/upload` | مطلوب ومستخدم | رفع المصدر مع progress محلي مبني على bytes المكتوبة. |
| `POST` | `/v1/processing/jobs` | موجود ومستخدم | إنشاء job idempotent. |
| `GET` | `/v1/processing/jobs/{id}` | موجود ومستخدم | polling كل ثانيتين داخل Worker، والتطبيق يعرض Room state. |
| `GET` | `/v1/processing/jobs/{id}/media/{filename}` | موجود ومستخدم | تنزيل مخرجات MP4. |
| `POST` | `/v1/processing/jobs/{id}/cancel` | موجود ومستخدم | يؤكد الإلغاء قبل وضع المهمة المحلية في `CANCELLED`. |
| `POST` | `/v1/processing/jobs/{id}/retry` | موجود ومستخدم | إعادة جدولة المهمة عند الفشل القابل للإعادة. |
| `POST` | `/v1/processing/jobs/{id}/resume` | موجود ومستخدم عند توفر remote job | استئناف checkpoint بعيد. |
| `POST` | `/v1/processing/jobs/{id}/render` | **غير موجود في العقد الحالي** | مطلوب فقط إذا أصبح render عملية منفصلة بعد إنشاء المهمة؛ لا يعتمد Android عليه حاليًا، لأن Gateway يعيد outputs بعد render ضمن دورة المعالجة. |

## Engine-related UI requirements

ينبغي أن يظل المستخدم على شاشة Processing أثناء `queued` و`running` و`interrupted`، وأن يرى المرحلة ورسالة الخطأ الآمنة وإجراءً واضحًا مثل الإلغاء أو إعادة المحاولة. لا يجوز عرض “اكتمل” قبل أن يثبت Gateway وجود `results.render.outputs`، ولا يجوز عرض Review فارغة كأنها نجاح.

يجب ألا تعرض الواجهة تفاصيل Python أو مسارات الخادم المحلية أو مفاتيح Gemini أو Authorization headers. أي خطأ يجب أن يوضح ما حدث، والسبب الآمن، والخطوة التالية الممكنة. المصدر المحلي يجب أن يبقى قابلًا للقراءة من Worker عبر URI مستديم، وتبقى كل المعالجة الثقيلة داخل Gateway/engine.

## Known issues and dependencies

أولاً، لا يوجد endpoint مستقل لـ`render` في عقد Gateway الحالي. زر المراجعة/التصدير يعمل على MP4 الذي أعاده Gateway ونزّله Worker، ويعرض ذلك صراحة في الواجهة. إذا قرر backend فصل render عن `POST /v1/processing/jobs`، يجب إضافة endpoint موثق من الطرف الآخر ثم تحديث `ProcessingGatewayClient` وطبقة الحالة في Android؛ لم أعدل backend وفق قاعدة الملكية.

ثانيًا، نتيجة Gateway تُحوّل إلى مشروع ومقاطع Room في Worker، لذلك لا تعرض شاشة Results إلا البيانات التي نجح Worker في تنزيلها وحفظها. فقدان الاتصال بعد إنشاء المهمة يعادله Worker بإعادة المحاولة وفق backoff، بينما الإيقاف الدائم يظهر كخطأ قابل لإعادة المحاولة أو فشل نهائي بحسب نوع الاستثناء.

ثالثًا، `VideoView` مناسب للمعاينة المحلية البسيطة، لكنه ليس محررًا زمنيًا متقدمًا. أي timeline editing أو caption burn-in تفاعلي يجب أن يبقى خارج هذا التسليم أو يُبنى فوق Media3 في مهمة Android لاحقة.

رابعًا، التطبيق يحتاج إلى `Base URL` صحيح وإلى Gateway متاح عبر HTTPS خارج الشبكة المحلية. عند عدم ضبط Gateway، Worker يفشل برسالة صريحة بدل محاولة تشغيل pipeline محليًا.

خامسًا، توجد تحذيرات deprecation قديمة في ملفات UI أخرى موجودة في المشروع، لكنها لا تمنع release build. لا تتعلق هذه التحذيرات بعقد backend أو بتشغيل المحرك.

## Build verification

تم اختبار البناء بالأمر التالي بعد توفير Android SDK وJDK في بيئة الاختبار:

```bash
cd android
./gradlew assembleRelease --no-configuration-cache
```

النتيجة: `BUILD SUCCESSFUL`. الناتج هو `android/app/build/outputs/apk/release/app-release-unsigned.apk`، ويُعاد توليده عند الحاجة لأن مجلدات build المحلية غير داخلة في commit.

## Dependencies on other sessions

لا توجد تعديلات مطلوبة على Python engine أو scoring أو diarization أو Gateway في هذا التسليم. الاعتماد الوحيد على الجلسات الأخرى هو أن يظل عقد Gateway أعلاه متوافقًا، وأن يعيد Gateway مخرجات render آمنة كما هو موضح. إذا تغير العقد، يجب تحديث `ProcessingGatewayClient` و`ANDROID_HANDOFF.md` في جلسة Android فقط، مع ترك backend للجلسة المالكة له.

## References

[1]: docs/API-CONTRACT.md — عقد API العام للمستودع.
[2]: docs/CLIENT-RESPONSIBILITIES.md — مسؤوليات Native Android وحدود العميل.
[3]: docs/android/ANDROID_PROCESSING_FLOW.md — تدفق Android وقاعدة التحقق من مخرجات render.
[4]: gateway/README.md — تشغيل Gateway وعلاقته بعميل Android.
