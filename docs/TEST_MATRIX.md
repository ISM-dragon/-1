# Test Matrix

## الاختبارات الآلية

| السيناريو | طبقة الاختبار | النتيجة المطلوبة |
|---|---|---|
| إنشاء job بإعدادات صالحة | Engine/Gateway | job جديد وحالة `QUEUED` |
| مصدر فارغ أو امتداد غير مدعوم | Gateway | error code آمن دون إنشاء job |
| ملف تالف أو بلا video stream | Media/Gateway | `MEDIA_INVALID` |
| SHA-256 غير صحيح | Upload | `MEDIA_CHECKSUM_MISMATCH` وتنظيف partial file |
| polling لحالة job | Android/Gateway | Room يعكس state/progress |
| cancel أثناء worker | Gateway/Engine | `CANCELLED` أو `cancel_requested` دون نجاح كاذب |
| resume بعد checkpoint | Engine/Gateway | إعادة تشغيل المراحل الناقصة فقط |
| restart للـGateway | Gateway | استعادة job من SQLite/checkpoint |
| غياب FFmpeg أو النموذج | Gateway/Media | error مصنف وقابل للإصلاح إذا أمكن |
| غياب LLM الاختياري | Scoring | fallback أو error آمن، لا crash غير مصنف |
| artifact خارج الجذر أو MP4 غير صالح | Gateway | `ARTIFACT_INVALID` وعدم كشف الملف |
| إعادة رندر clip | Engine/Gateway | تحديث artifact دون إعادة ASR |
| Android unit/lint/release build | Gradle | نجاح الاختبارات وبناء APK قابل للفحص |

## اختبارات الجهاز المطلوبة قبل النشر

يجب على جهاز Android أو emulator مستقر تثبيت APK وتشغيله، اختيار فيديو من Photo Picker، بدء رفع، إظهار foreground notification، إغلاق التطبيق أثناء polling، فتحه من جديد، استعادة job، تنزيل MP4 وتشغيل preview، ثم cancel/resume عند فشل الشبكة. هذه الاختبارات لا يثبتها نجاح unit tests وحده.

## قاعدة regression

كل bug يتبع التسلسل: إعادة إنتاج، إصلاح صغير داخل ملكية الملف، اختبار regression، ثم إعادة تشغيل المجموعة ذات الصلة. لا تُقبل عبارة “everything works” دون log أو artifact أو test result قابل للمراجعة.
