# خطة الترحيل من المرجع إلى PublikClip

## المبدأ

لا توجد عملية نسخ كاملة من `clipper-main` إلى `ISM-dragon/-1`. كل تغيير يجب أن يمر عبر الفهم، ثم المقارنة، ثم القرار، ثم implementation مستقل أو adapter صغير، ثم اختبار regression. الأولوية لمسار Android الشخصي من اختيار الفيديو إلى التصدير.

## الموجة الأولى: التدقيق والتوثيق

تم تنفيذ هذه الموجة عبر جرد شجرة المشروع، مراجعة Android/Gateway/Engine/pipeline، قراءة بنية المرجع، والتحقق من تراخيص الطرفين. الناتج هو هذه الوثيقة ووثائق المقارنة والعقود والتراخيص.

## الموجة الثانية: تثبيت الحدود

يُثبت `ProcessingEngine` كواجهة عامة للمراحل، ويظل `Gateway` هو control plane الوحيد لمسار Android. لا يستورد Android أي Python module، ولا يستورد Gateway تفاصيل كثيرة من pipeline خارج adapter المحرك. تُحفظ checkpoints وartifacts كحقيقة تشغيلية، مع SQLite كدفتر حالة.

## الموجة الثالثة: تحسينات منخفضة المخاطر

1. توحيد وثائق API وEngine وMedia/AI runtime وAndroid UX.
2. تثبيت error envelope والأكواد القابلة للعرض في Android.
3. إضافة أو تحسين فحوص media قبل إنشاء job، مع إبقاء FFmpeg/ffprobe على الخادم.
4. الحفاظ على clamping وfallback في scoring عند غياب LLM.
5. فصل caption state عن render implementation في أي تغيير لاحق.

## الموجة الرابعة: تكامل Android

يستخدم Android Native مسار `Home → Import → Generate → Processing → Results → Review/Edit → Render → Export`. يعتمد التشغيل الطويل على WorkManager وRoom، ويعيد استخدام `remoteGatewayJobId` بعد إغلاق التطبيق أو انقطاع الشبكة. لا يُعلن local pipeline كمسار release canonical.

## الموجة الخامسة: التحقق والإصدار

يجب تشغيل unit tests للـPython وGateway وAndroid، ثم build debug/release وlint، ثم التحقق من APK archive وmanifest والتوقيع. اختبارات الجهاز الحقيقي أو emulator مستقر تظل مطلوبة لمسار install/launch/file-picker/process-death، ولا يكفي نجاح compilation.

## بوابات القرار

| البوابة | شرط المرور | الإجراء عند الفشل |
|---|---|---|
| License gate | الترخيص معروف ومتوافق، أو إعادة تنفيذ مستقلة | منع نسخ الكود وتسجيل السبب |
| Contract gate | لا تغيير breaking في `/jobs` أو `ProcessingEngine` | تحديث adapter والاختبارات قبل الدمج |
| Performance gate | benchmark يثبت تحسنًا أو عدم وجود تراجع | إبقاء implementation الحالية |
| Reliability gate | cancel/resume/restart وملف تالف لا تسبب crash غير مصنف | إضافة regression test وإيقاف الترحيل |
| Android gate | APK لا يحتوي runtime server أو secrets | إزالة dependency/secret وإعادة البناء |

## الأعمال المؤجلة

إعادة تصميم camera، إدخال B-roll، نقل Whisper إلى native Android، وتوسيع social publishing ليست ضمن التغيير الأول؛ تحتاج benchmark وقرارًا مستقلًا. كذلك لا تُضاف قاعدة بيانات مُدارة أو multi-user architecture في المرحلة الشخصية الحالية.
