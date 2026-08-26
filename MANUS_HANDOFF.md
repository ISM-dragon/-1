# MANUS_HANDOFF — ISM Android Gateway Client

## الحالة

تم تنفيذ تطبيق Android أصلي حقيقي داخل `android/` باستخدام Kotlin وJetpack Compose. التطبيق موجه للاستخدام الشخصي، ويعتمد على **API contract فقط** عبر Gateway؛ لا يضمّن Python أو FFmpeg ولا يشغّل محرك المعالجة داخل Android.

> المراجع المتاحة في المستودع هي `docs/API-CONTRACT.md` و`docs/CLIENT-RESPONSIBILITIES.md`. لم توجد نسختان سابقتان من `MANUS_HANDOFF.md` أو `BACKEND.md` داخل مساحة العمل عند بدء التنفيذ، لذلك يوثق هذا الملف الوضع الحالي اعتمادًا على عقد API وطبقة Gateway الموجودة.

## المعمارية

| الطبقة | المسؤولية | الملفات الرئيسية |
|---|---|---|
| UI | عرض الشاشات، اختيار الفيديو، الحالة، التقدم، النتائج، المراجعة، الإعدادات | `android/app/src/main/java/com/example/ContractApp.kt` |
| Contract client | HTTP/JSON، Bearer auth، health، upload، job creation، polling، control، artifact download | `android/app/src/main/java/com/example/data/contract/ApiContractClient.kt` و`ProcessingContract.kt` |
| Durable orchestration | جدولة WorkManager، إعادة الاستعادة، منع التكرار، حفظ job IDs والحالة | `ContractJobRepository.kt` و`GatewayProcessingWorker.kt` |
| Persistence | Room للحالة المحلية وSharedPreferences المشفرة لإعدادات Gateway والـ artifact manifest | `ProcessingJobEntity` و`ProcessingJobDao` و`ContractJobRepository` |
| Backend | المصادقة، التحقق، queue، مراحل المعالجة، النتائج، retry/cancel/resume | Gateway API، وليس جزءًا من Android |

الواجهة لا تعرف أسماء مراحل Python أو ملفات pipeline الداخلية. تستخدم فقط `state`, `status`, `stage`, `fraction/progress`, `error_code`, `recoverable`, `artifacts`, `request_id`, و`correlation_id` كما يسمح بها العقد.

## الشاشات المنفذة

| الشاشة | السلوك |
|---|---|
| Home | بدء استيراد، عرض المهام النشطة، فتح المهام المكتملة أو الفاشلة |
| Import Video | `OpenDocument` لاختيار `video/*`، اسم المهمة، بدء آمن بعد الاختيار |
| Processing | عرض المرحلة والتقدم القادم من API، بقاء الحالة بعد إغلاق التطبيق، إلغاء وإعادة محاولة حالات الانتظار |
| Processing Error | عرض الرسالة الآمنة ورمز الخطأ/المرحلة، retry، resume من checkpoint، أو العودة للرئيسية |
| Results | عرض artifact manifest، مراجعة المقطع، وتنزيل ملفات MP4 عند الطلب |
| Clip Review | معاينة MP4 محليًا بعد التنزيل، قص بداية/نهاية للمعاينة، كتم الصوت، وحفظ إعدادات المعاينة محليًا |
| Settings | حفظ Gateway URL وsession token في Android Keystore، واختبار `/health` |

## دورة حياة المهمة

1. يختار المستخدم ملفًا محليًا، ويُحفظ job محلي في Room قبل أي طلب شبكة.
2. يضع WorkManager مهمة فريدة باسم `ism_gateway_processing_<localJobId>` مع `NetworkType.CONNECTED` و`KEEP`.
3. يرفع العامل الفيديو إلى `POST /v1/sources/upload`، ثم ينشئ job عبر `POST /v1/processing/jobs` مع `idempotency_key` ثابت مبني على job المحلي.
4. يحفظ `remoteGatewayJobId` فورًا، ثم يستعلم عن `GET /v1/processing/jobs/{id}` ويعرض النسبة الواردة من Gateway دون تصنيع تقدم محلي.
5. عند `COMPLETED` يحفظ manifest النتائج؛ لا تُنزّل الملفات إلا بطلب المستخدم من Results أو Clip Review.
6. عند `FAILED` تظهر شاشة خطأ، ويُسمح بـ retry إذا كانت المهمة قابلة للاستعادة، وبـ resume عندما يعيد العقد المهمة من checkpoint.
7. عند الإلغاء يُستدعى control route البعيد إن وُجد job ID، ثم يُلغى WorkManager وتُحفظ الحالة المحلية.
8. عند انقطاع الشبكة أو timeout يعيد WorkManager المحاولة ضمن حد محدود، وتظهر حالة `WAITING_FOR_NETWORK`. عند restart يعيد التطبيق جدولة المهام المحلية غير النهائية، بما في ذلك المهمة التي اكتملت أثناء غياب التطبيق.

## عقد API المستخدم

| العملية | المسار |
|---|---|
| الاتصال | `GET /health` |
| الرفع | `POST /v1/sources/upload` |
| إنشاء job | `POST /v1/processing/jobs` |
| polling | `GET /v1/processing/jobs/{id}` |
| الإلغاء | `POST /v1/processing/jobs/{id}/cancel` |
| retry | `POST /v1/processing/jobs/{id}/retry` |
| resume | `POST /v1/processing/jobs/{id}/resume` |
| تنزيل النتيجة | `GET` على رابط artifact الذي يعيده Gateway |

## الاختبارات والتحقق

| الاختبار | الأمر/النتيجة |
|---|---|
| Kotlin compilation | `./gradlew :app:compileDebugKotlin` — ناجح |
| API contract tests | `./gradlew :app:testDebugUnitTest --tests com.example.ApiContractClientTest --tests com.example.ProcessingEngineTest` — ناجح، 7 اختبارات |
| APK build | `./gradlew :app:assembleDebug` — ناجح |
| Lint | `./gradlew :app:lint` — ناجح مع تحذيرات deprecated موجودة في أجزاء قديمة غير مستخدمة من الغلاف الجديد |
| HTTP mock coverage | يغطي health، create job، polling/progress، cancel، artifact download، وJSON manifest parsing |
| Gateway E2E | السكربت الاختياري `scripts/test_android_gateway_contract.sh` يتطلب `GATEWAY_URL`, `GATEWAY_TOKEN`, و`VIDEO_FILE` ويختبر health/upload/create/poll/results ضد Gateway حقيقي |

تم إنشاء Debug APK في:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

## تشغيل E2E ضد Gateway حقيقي

```bash
GATEWAY_URL="https://gateway.example" \
GATEWAY_TOKEN="<session-token>" \
VIDEO_FILE="/absolute/path/video.mp4" \
./scripts/test_android_gateway_contract.sh
```

لا تُضع session tokens أو مفاتيح مزودي الذكاء الاصطناعي في المستودع أو داخل APK. يتطلب الاختبار الفعلي Gateway صالحًا وبيانات اعتماد يحددها مالك البيئة.

## ملاحظات التسليم

تظل ملفات المنتج القديم داخل المستودع لأسباب التوافق، لكن `MainActivity` لم يعد يربطها بالمسار التشغيلي. كما أصبح `VideoProcessingWorker` غلاف توافق يشير إلى `GatewayProcessingWorker` فقط، وأزيل قرار `LOCAL_PIPELINE` من `ProcessingEngine` حتى لا توجد نقطة تشغيل محلية لمحرك المعالجة داخل Android.
