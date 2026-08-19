# Known Behavior — ISM Social / Publikclip

## المنتج الحالي

النسخة الحالية تطبيق Desktop باسم Publikclip مبني على Tauri وReact. الواجهة تحتوي على onboarding وsource library وstudio وclip editor وreview وanalytics dashboard وsocial hub، وتستدعي gateway محليًا عبر API. لا توجد في هذه النسخة واجهة Android مكتملة داخل هذا المستودع؛ ملف `ANDROID.md` يصف اتجاهًا مستقبليًا ولا يثبت وجود تطبيق قابل للبناء.

## مسار معالجة الفيديو

المسار الفعلي الموجود في `pipeline/publikclip_pipeline/` هو محرك Python قابل للاختبار، ويضم وحدات لمعالجة الإدخال، والتعرف على الكلام، وdiarization، واكتشاف الأحداث، وتوليد المرشحين، والتسجيل، وتوجيه الكاميرا، وإنشاء captions، وخطة التعديل، والرندر عبر FFmpeg. اختبارات pipeline الحالية تؤكد السلوك البرمجي للوحدات، لكنها لا تعني أن كل نموذج خارجي أو كل ملف فيديو حقيقي متاح في كل بيئة.

## gateway الحالي

`gateway/main.py` ينشئ تطبيق FastAPI بعنوان `ISM Social Gateway`، ويستخدم SQLite محليًا، ويمكنه حماية الطلبات عبر `GATEWAY_TOKEN`. توجد مسارات health، وprocessing jobs، وsource jobs، وanalytics، وdashboard، وaccounts، وsocial capabilities، وOAuth mock، وschedule/publish. توجد مهام خلفية للمعالجة والنشر، مع حالات مثل `queued` و`running` و`completed` و`failed` بحسب المسار.

مصادر الفيديو العامة تُفحص عبر `validate_public_source`، والاختبارات الحالية تتوقع قبول HTTPS العام ورفض `localhost` وIP الخاص ومخططات الملفات. يجب الحفاظ على هذا السلوك الأمني أثناء أي refactor.

## النشر الاجتماعي

يحتفظ gateway بحسابات ومنشورات داخل SQLite، ويدعم سياسات daily limit وminimum gap وcooldown وidempotency key. وضع المزود الافتراضي هو `mock` ما لم تتم تهيئته بمتغيرات بيئة صريحة؛ يجب عدم تفسير وضع mock على أنه نشر حقيقي.

## حدود السلوك المثبت

لا يثبت baseline وجود authentication متعدد المستخدمين أو تخزين object storage أو queue موزعة أو WebSocket/SSE أو Android upload client. هذه عناصر تصميم مستقبلي يجب إضافتها تدريجيًا وبعقود واضحة، لا عبر الادعاء بأنها موجودة حاليًا.
