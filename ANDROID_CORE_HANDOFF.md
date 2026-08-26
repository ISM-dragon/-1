# Android Core Handoff

## النطاق

هذه التسليمة تنفذ طبقة **Android Core** لتطبيق ISM بوصف Android عميلًا للـPrivate Gateway. لا يشغّل التطبيق Python أو `uv` أو WhisperX أو PyTorch أو FFmpeg desktop، ولا يقرر حالة المعالجة محليًا. يبقى الـGateway مصدر الحقيقة للحالة والتقدم والنتائج والإلغاء والاستئناف، بينما تحفظ Android إسقاطًا محليًا durable للعرض والاستعادة. يتوافق ذلك مع [MASTER-ARCHITECTURE](docs/MASTER-ARCHITECTURE.md) و[API-CONTRACT](docs/API-CONTRACT.md) و[CLIENT-RESPONSIBILITIES](docs/CLIENT-RESPONSIBILITIES.md).

الملفات المطلوبة بالمسميات `docs/ARCHITECTURE.md` و`docs/CONTRACTS.md` و`docs/API.md` غير موجودة في checkout الحالي؛ لذلك استُخدمت الوثائق canonical المكافئة المذكورة أعلاه، وسُجلت نتيجة المراجعة في [ANDROID_CORE_REVIEW.md](docs/ANDROID_CORE_REVIEW.md).

## المكونات المسلّمة

| المكوّن | المسار | المسؤولية |
|---|---|---|
| `ApiClient` | `android/app/src/main/java/com/example/core/network/ApiClient.kt` | HTTP boundary موحد ومصادق عليه للرفع وإنشاء المهمة والقراءة والتحكم والنتائج والتنزيل. |
| `JobRepository` | `android/app/src/main/java/com/example/core/repository/JobRepository.kt` | persistence-first orchestration، Flow من Room، WorkManager، الاستعادة، وتحويل الحالة البعيدة إلى إسقاط محلي. |
| `MediaRepository` | `android/app/src/main/java/com/example/core/repository/MediaRepository.kt` | تثبيت `content://` و`file://`، رفع المصدر، تنزيل النتائج ذريًا، وتنظيف المصدر المُدار. |
| `JobState` | `android/app/src/main/java/com/example/core/model/JobState.kt` | vocabulary canonical للحالات مع دعم `queued/running/done/failed` القديم. |
| `ErrorState` | `android/app/src/main/java/com/example/core/model/ErrorState.kt` | نموذج أخطاء آمن ومستقر يميز authentication وcapability وnetwork وvalidation وterminal وoffline. |
| `PrivateBackendConfigStore` | `android/app/src/main/java/com/example/core/security/PrivateBackendConfigStore.kt` | حفظ عنوان الـGateway وsession token، مع تشفير token باستخدام Android Keystore وترحيل الإعداد القديم. |
| `IsmApplication` | `android/app/src/main/java/com/example/IsmApplication.kt` | إعادة جدولة كل مهمة غير طرفية عند إنشاء العملية. |

## خريطة العمليات

يطبق `ApiClient` المسارات التالية من عقد `/v1`:

| العملية | المسار أو السلوك |
|---|---|
| Upload | `POST /v1/sources/upload` عبر streaming `video/mp4` مع progress اختياري. |
| Create job | `POST /v1/processing/jobs` مع `source`, `llm`, `captions`, `mode`, و`idempotency_key`. |
| Get job | `GET /v1/processing/jobs/{id}`. |
| Get jobs | `getJobs(ids)` ينفذ قراءة authoritative منفصلة لكل ID؛ لا يفترض وجود list endpoint غير موثق. |
| Poll status | قراءة متكررة كل ثانيتين حتى `COMPLETED` أو `FAILED` أو `CANCELLED` أو `INTERRUPTED`، مع تمرير تحديثات الخادم فقط. |
| Cancel | `POST /v1/processing/jobs/{id}/cancel`. |
| Resume | `POST /v1/processing/jobs/{id}/resume`. |
| Retry | `POST /v1/processing/jobs/{id}/retry` متاح أيضًا للحالات القابلة للإعادة. |
| Results | استخراج `results.render.outputs` من مورد المهمة بعد اكتمالها. |
| Render request | `render()` اسم دلالي لـ`createJob()` لأن العقد الحالي يضم طلب render داخل إنشاء processing job ولا يعرّف endpoint render مستقلًا. |
| Download | تنزيل رابط `path` إلى ملف `.part` ثم rename ذري، مع حذف الملف الجزئي عند الفشل. |

يتم إرسال `Authorization: Bearer <session-token>` لكل طلب خاص عندما يكون token مضبوطًا. الأخطاء غير الناجحة تُحوّل إلى `ApiException(ErrorState)` وتقرأ فقط الحقول المستقرة من `error` أو `detail`، ولا تعرض body الخام أو headers أو filesystem paths.

## الاستمرارية ودورة الحياة

تُكتب صفوف المهام في Room قبل جدولة WorkManager. يتضمن `ProcessingJobEntity` الآن `idempotencyKey` و`remoteSource` و`errorCode` و`errorRetryable` و`lastRequestId`، مع ترحيل schema من 5 إلى 6. يحفظ العامل `remoteGatewayJobId` فور إنشاء المهمة البعيدة، ولذلك لا يعتمد استئناف polling على ذاكرة العملية.

يستخدم `JobRepository` `NetworkType.CONNECTED` وunique work باسم المهمة مع `KEEP`. عند إعادة إنشاء process، يستدعي `IsmApplication` `recoverPendingJobs()` ويعيد جدولة الصفوف غير الطرفية. إذا كان للصف remote job ID، يعيد العامل قراءة الحالة من الـGateway، ويطلب `resume` عند `INTERRUPTED` أو الحالة الفاشلة القابلة للاسترداد، ثم يواصل polling. إذا انقطع الاتصال، يعيد WorkManager المحاولة وفق تصنيف `IOException` و`ApiException`؛ لا يُعرض اكتمال مصطنع ولا يُحذف المصدر قبل اكتمال التنزيل وحفظ النتائج.

الإلغاء يحافظ على الدلالة الدائمة: يرسل العامل أو المستودع cancel إلى الـGateway ثم يكتب `CANCELLED` محليًا. الحالة الطرفية لا تُعاد إلى `RUNNING` بعد restart. ويظل backend هو المرجع عند اختلاف الإسقاط المحلي، بما في ذلك بعد إعادة تشغيل الـGateway.

## الأمان

يُحفظ session token داخل `PrivateBackendConfigStore` في `SharedPreferences` بعد تشفيره عبر `SecureKeyManager` وAndroid Keystore. تتم إزالة مفاتيح plaintext القديمة بعد الترحيل. عنوان الـbackend يقبل HTTPS في الإنتاج، ويسمح HTTP فقط للمضيفات المحلية والشبكات الخاصة المدعومة للتطوير أو LAN. تُرفض بيانات اعتماد URL.

عند تنزيل artifact، تُقبل الروابط النسبية من الـGateway أو الروابط المطلقة التي تطابق مضيف الـbackend المضبوط؛ يمنع ذلك إرسال bearer token إلى مضيف خارجي. لا تُحمل أي provider secrets أو refresh tokens إلى Android.

## التحقق

| الأمر | النتيجة |
|---|---|
| `./gradlew :app:testDebugUnitTest --tests com.example.core.AndroidCoreContractTest --no-daemon` | نجح؛ اختبارات mapping للحالات والأخطاء وHTTP contract. |
| `./gradlew :app:testDebugUnitTest --no-daemon` | نجح؛ مجموعة Android unit tests كاملة. |
| `./gradlew :app:lint :app:assembleRelease --no-daemon` | نجح؛ lint وrelease build. |

الاختبارات الجديدة موجودة في `android/app/src/test/java/com/example/core/AndroidCoreContractTest.kt`. وهي تتحقق من canonical/legacy state mapping، تصنيف أخطاء capability، authorization، مسار create/get، parsing النتائج، وتحويل مسار الوسائط النسبي إلى مضيف الـGateway.

## حدود مقصودة

لا يحتوي هذا التغيير على visual UI أو شاشة إعداد جديدة؛ الواجهة الحالية تستمر في استخدام `OpusRepository`، الذي وُصل بدوره إلى `PrivateBackendConfigStore`. كما لم يُنشأ `GET /v1/processing/jobs` لأن العقد الحالي لا يعلنه؛ `getJobs(ids)` يحافظ على مصدر الحقيقة باستخدام endpoint الفردي. وأخيرًا، يبقى `ProcessingGatewayClient` القديم موجودًا للتوافق مع نماذج النتائج والسطوح القديمة، بينما يستخدم عامل المعالجة مسار `ApiClient` الجديد.

## ملاحظات التشغيل

يحتاج release deployment إلى `KEYSTORE_PATH` و`STORE_PASSWORD` و`KEY_PASSWORD` في CI، ولا تُلتزم أي أسرار في المستودع. يجب ضبط Gateway بعنوان HTTPS صالح وsession token قصير العمر قبل تشغيل المعالجة البعيدة. يجب أن يعرض أي UI لاحق `ErrorState.kind`, `code`, `message`, و`retryable` بدل نصوص الاستثناء الخام.

## المراجع

[1]: docs/MASTER-ARCHITECTURE.md "ISM canonical architecture baseline"
[2]: docs/API-CONTRACT.md "ISM Gateway API contract"
[3]: docs/CLIENT-RESPONSIBILITIES.md "ISM client responsibilities"
