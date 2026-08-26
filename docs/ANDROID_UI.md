# Android UI and Client Responsibilities

## الهدف

Android هو عميل شخصي خفيف أمام private Gateway. لا ينقل desktop UI إلى الهاتف ولا يعرّض تفاصيل Python الداخلية للمستخدم.

## الرحلة الأساسية

| الشاشة/الحالة | مسؤولية العميل |
|---|---|
| Home | عرض المشاريع والمهام الأخيرة وحالة Gateway |
| Import | اختيار فيديو عبر Photo Picker أو URI محلي والتحقق من قابلية القراءة |
| Generate | اختيار caption theme وprocessing mode ثم إنشاء job محلي |
| Processing | عرض stage/progress/notification مع بقاء التطبيق قابلًا للإغلاق |
| Results | عرض المقاطع التي أعادها Gateway وحفظ artifact paths محليًا |
| Clip Review | preview ومعلومات الزمن والدرجة |
| Edit | تعديلات خفيفة لا تتطلب نقل Engine إلى الهاتف |
| Render | طلب render أو استخدام artifact الناتج وفق contract |
| Export | حفظ أو مشاركة ملف MP4 بعد نجاح integrity check |

## Lifecycle

يُحفظ `ProcessingJobEntity` في Room ويُحفظ `remoteGatewayJobId` فور إنشاء job. WorkManager يعيد المحاولة وفق network constraint وbackoff، ويستعيد المهمة بعد process death. الإلغاء يطلب cancel من Gateway ثم يلغي العمل المحلي، بينما retry/resume لا ينشئ job بعيدًا جديدًا عندما يكون job ID السابق قابلًا للاستعادة.

## الصلاحيات والأسرار

يُطلب `POST_NOTIFICATIONS` عند الحاجة على Android 13+. لا تُطلب صلاحيات تخزين عامة عندما يكفي URI/Photo Picker. رمز Gateway يُحفظ عبر طبقة secure key الحالية، ولا يُضمّن في APK أو logs. عنوان الإنتاج يجب أن يكون HTTPS؛ loopback HTTP مسموح للاختبارات المحلية فقط.

## حدود القبول

نجاح unit tests لا يثبت تجربة الجهاز. يجب اختبار import وbackground notification وclose/reopen وpreview/edit/export على جهاز Android أو emulator مستقر متصل بـGateway خاص. عدم توفر هذا الجهاز يبقي release blocker مفتوحًا.
