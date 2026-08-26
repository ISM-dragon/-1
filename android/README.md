# ISM Android client

## تجربة الاستخدام

هذا تطبيق Android أصلي مخصص للاستخدام الشخصي في ISM. يبدأ التدفق من **Home** ثم **Import Video** لاختيار فيديو من file picker، وبعدها تُرفع النسخة إلى Gateway وتُتابع الوظيفة من **Processing**. عند النجاح تظهر **Results** ثم **Clip Review** للتحكم الأساسي في بداية ونهاية المقطع، بينما تحفظ **Settings** عنوان Gateway ورمز الجلسة بطريقة آمنة.

## حدود المسؤولية

Android مسؤول عن الواجهة، اختيار الفيديو، الرفع، إنشاء الوظيفة، عرض التقدم، تنزيل النتائج، المراجعة، والتحكم الأساسي بالمقطع. لا يحتوي التطبيق على Python أو FFmpeg أو أي خط معالجة وسائط محلي، ولا تعتمد الواجهة على أسماء وحدات Pipeline الداخلية. المصدر الوحيد للحالة والتقدم والنتائج هو عقد Gateway العام تحت `/v1`.

## دورة الوظيفة

يستخدم التطبيق `RemoteProcessingCoordinator` و`RemoteProcessingWorker` مع WorkManager. يُحفظ `local_job_id` و`remote_job_id` و`idempotency_key` والنتائج في تخزين محلي دائم، ويُستخدم unique work لمنع إنشاء وظيفة ثانية عند إعادة فتح التطبيق. عند إغلاق التطبيق أو انقطاع الشبكة يستعيد العامل الوظيفة، ويستعلم عن الحالة الحالية، ويعيد المحاولة بمهلة تزايدية عند الأخطاء القابلة لإعادة المحاولة. إذا أعاد Gateway حالة `INTERRUPTED` يُطلب الاستئناف عبر العقد، وإذا كانت الوظيفة مكتملة أثناء إغلاق التطبيق تُستعاد النتائج عند التشغيل التالي.

تدعم الواجهة حالات المهمة الجارية، المهمة الموجودة مسبقًا، اكتمال المهمة، الفشل، إعادة المحاولة، الاستئناف، والإلغاء. لا تُعرض نسبة تقدم مصطنعة؛ النسبة المعروضة مشتقة من `progress` أو `fraction` التي يعيدها Gateway.

## عقد API المستخدم

| العملية | العقد المستخدم |
|---|---|
| اختبار الاتصال | `GET /health`, `GET /v1/auth/session`, `GET /v1/processing/capabilities` |
| رفع الفيديو | `POST /v1/sources/upload` بصيغة stream/multipart-compatible raw video |
| إنشاء الوظيفة | `POST /v1/processing/jobs` مع `source`, `llm`, `captions`, `mode`, `idempotency_key` |
| الاستعلام | `GET /v1/processing/jobs/{id}` |
| التحكم | `POST /cancel`, `POST /retry`, `POST /resume` |
| النتائج | `results.render.outputs` أو `artifacts` ثم تنزيل روابط الوسائط العامة |

تُقرأ حالات `state` canonical، مع دعم الحقول التوافقية `status`. تُحوّل أخطاء العقد إلى رسالة آمنة ورمز خطأ قابل للعرض دون كشف stack trace أو المسارات الخاصة أو Authorization header.

## البناء والاختبار

من داخل هذا المجلد:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :app:testDebugUnitTest
./gradlew :app:lint
./gradlew :app:assembleDebug
```

اختبار `RemoteGatewayApiContractTest` يستخدم Mock Gateway ويغطي الاتصال، Authorization، رفع الفيديو، إنشاء الوظيفة، قراءة حالة التقدم، قراءة النتائج، وتنزيل MP4. لا يحتاج الاختبار إلى تشغيل Python أو Gateway فعلي.

## العلاقة مع المستودع

المشروع الأصلي في جذر المستودع هو تطبيق سطح المكتب وطبقة Gateway وPython Pipeline. تطبيق Android هنا عميل مستقل، ويشارك العقد العام فقط. وثيقة العقد المرجعية هي `docs/API-CONTRACT.md`، كما تُحافظ `MANUS_HANDOFF.md` على تفاصيل التسليم الحالية.

## الخصوصية

رمز جلسة Gateway يُحفظ مشفرًا عبر Android Keystore. لا يرسل التطبيق مفتاح Gemini ولا أي سر خاص بالمزود إلى الخادم؛ يرسل فقط طلب API وفق العقد. المعالجة الفعلية للوسائط تقع على Gateway المهيأ من المستخدم.
