# Android UI ومسؤوليات العميل

## المسار الأساسي

يبدأ التطبيق من Home، ثم يفتح Import لاختيار فيديو عبر Photo Picker أو GetContent. بعد قراءة metadata وتثبيت URI في app-private storage، يطلق Generate مهمة WorkManager. شاشة Processing تعرض progress وstage ورسالة آمنة ويمكن إغلاق التطبيق خلالها. عند completion تظهر Results، ثم Clip Review وEdit وRender وExport.

## ما يفعله Android

| المسؤولية | التنفيذ |
|---|---|
| اختيار الفيديو | Photo Picker/GetContent وURI permissions. |
| metadata وpreview | MediaMetadataRetriever وpreview خفيف قبل الرفع. |
| background work | CoroutineWorker/WorkManager مع network constraint وforeground notification. |
| الحالة | Room `ProcessingJobEntity` مع remote job ID وprogress وstage. |
| الشبكة | private-backend client، Bearer token، HTTPS خارج localhost، retry/resume. |
| النتائج | تنزيل MP4 إلى `filesDir/gateway_exports` واستيرادها إلى Room Project/Clip. |
| التحرير | تمرير خيارات edit إلى endpoint render؛ لا يُنقل pipeline إلى الهاتف. |

## مبادئ UX

لا تُعرض مفاتيح server أو stack traces أو filesystem paths. تُعرض رسائل عربية مفهومة مع code داخلي عند الحاجة. لا يعتمد screen state على بقاء Activity؛ عند العودة يقرأ التطبيق Room ويعيد مراقبة المهمة. لا تُجعل social publishing أو dashboard شرطًا لإنشاء clip شخصي.

## ملاحظة بنيوية

يحتوي المستودع على مسار Compose رئيسي (`MainActivity` + `OpusRepository`) ومسار contract مستقل (`ContractApp`/`remote`). التعديل الحالي جعل العامل الفعلي في المسار الرئيسي يستخدم private-backend client؛ يبقى توحيد الواجهتين قرارًا لاحقًا حتى لا تحدث إعادة كتابة UI غير لازمة.

## المراجع

[1]: ../android/app/src/main/java/com/example/MainActivity.kt "Main Android entry point"
[2]: ../android/app/src/main/java/com/example/ui/screens/VideoUploadScreen.kt "Import and generate UX"
[3]: ../android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Background processing"
[4]: ../android/app/src/main/java/com/example/data/remote/PrivateBackendClient.kt "Private backend client"
[5]: ../android/app/src/main/java/com/example/data/repository/OpusRepository.kt "Room and project integration"

## References

المراجع محلية في المستودع.
