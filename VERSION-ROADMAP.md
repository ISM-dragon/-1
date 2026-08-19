# ISM Version Roadmap v0.6–v0.9

هذه الخارطة تحول التطبيق من مشغل Pipeline تجريبي إلى منصة خاصة لإدارة المصادر، معالجة الفيديو، النشر الرسمي، والتحليلات. كل إصدار قابل للبناء منفرداً، ولا يعتمد على تدوير IP أو تجاوز أنظمة الحماية.

## v0.6 — Source Library and Account Foundation

يضيف الإصدار مدير مصادر داخل التطبيق لقبول رابط فيديو مفرد أو رابط قناة أو قائمة تشغيل، ومعاينة العناصر قبل التنزيل، وخيار تنزيل جميع العناصر أو عدد محدد مع حد تأكيد واضح. تُحفظ الملفات في مكتبة محلية أو في Gateway عند استخدام Android. كما يضيف نماذج الحسابات الموحدة، حالة الاتصال، انتهاء OAuth، ومخزن إعدادات لا يضع الأسرار داخل GitHub.

## v0.7 — Automation and Official Publishing

يضيف الإصدار طوابير معالجة قابلة للاستئناف، منشورات مجدولة، موافقة اختيارية قبل النشر، سياسة idempotency، retry/backoff، حدود يومية وفواصل لكل حساب، وحالات provider-specific. تُبنى موصلات Instagram/Meta وTikTok وYouTube وX عبر OAuth وواجهاتها الرسمية، مع تمييز Direct Post وDraft عندما تفرض المنصة ذلك. يشمل الإصدار أيضاً webhooks أو polling منخفض التواتر لتحديث حالة الرفع.

## v0.8 — Account Dashboard and Analytics

يضيف لوحة متعددة الحسابات تعرض حالة الاتصال، صلاحيات OAuth، آخر مزامنة، المنشورات اليومية، الفشل والتأخير، ومؤشرات المشاهَدات والإعجابات والتعليقات ووقت المشاهدة عندما تسمح المنصة بذلك. يعرض كل رقم مصدره وتاريخ تحديثه وما إذا كان عاماً أو خاصاً، ولا يحول البيانات غير المتاحة إلى أصفار وهمية. يدعم YouTube Analytics، Instagram Insights للحسابات الاحترافية، ومؤشرات X العامة/الخاصة ضمن نوافذ الوصول الرسمية؛ ويضع TikTok في وضع capabilities واضح إلى حين منح الصلاحية المناسبة.

## v0.9 — Reliability, Security, and Mobile Readiness

يضيف اختبارات تعاقدية لكل Provider، تشفيراً أو مدير أسرار للخادم، تدوير رموز OAuth فقط عند الحاجة، سجل تدقيق، حذفاً آمناً، حد حجم الملف، فحص MIME، حماية SSRF لعناوين الوسائط، تنظيف الملفات المؤقتة، مراقبة صحة العمال، وواجهة هاتف لا تقص الحقول. يشمل الإصدار signed release/AAB عند توفر keystore الإنتاجي، ونسخة Debug قابلة للاختبار، وتعليمات نشر Gateway.

## ممنوعات تصميمية

لا يتضمن المشروع تدوير عناوين IP أو proxies لتجاوز الحظر أو إخفاء أتمتة الحسابات. بدلاً من ذلك يستخدم عزل الحسابات، حدود النشر الرسمية، التهدئة بعد أخطاء 401/403/429، موافقة المستخدم، وعدم تكرار المنشورات، وإيقافاً آمناً عند انتهاء OAuth أو ظهور تحذير من موفر الخدمة.

## المصادر الرسمية المستخدمة

- Meta Instagram Content Publishing: https://developers.facebook.com/documentation/instagram-platform/content-publishing
- Meta Instagram Insights: https://developers.facebook.com/documentation/instagram-platform/insights
- TikTok Content Posting API: https://developers.tiktok.com/products/content-posting-api/
- TikTok Display API: https://developers.tiktok.com/doc/display-api-overview
- YouTube upload: https://developers.google.com/youtube/v3/guides/uploading_a_video
- YouTube Analytics: https://developers.google.com/youtube/analytics
- X media upload: https://docs.x.com/x-api/media/introduction
- X metrics: https://docs.x.com/x-api/fundamentals/metrics
- yt-dlp: https://github.com/yt-dlp/yt-dlp
