# Android UI وClient Responsibilities

**الهدف:** APK شخصي خفيف أمام Gateway خاص.

## المسار الرئيسي

```text
Home → Import → Generate → Processing → Results
     → Clip Review → Edit → Render → Export
```

تختار شاشة Import فيديو من Photo Picker أو URI مدعوم، وتنسخه إلى app-private storage قبل جدولة الرفع. شاشة Generate تجمع خيارات آمنة مثل mode وcaption preset؛ لا تعرض provider secrets. شاشة Processing تعرض state وstage وfraction من Gateway مع حالة offline/reconnecting، وتسمح بالإلغاء عندما يكون job قابلًا لذلك. Results وClip Review تعرضان metadata والـpreview، ثم تحفظ شاشة Edit تغييرات trim/caption/framing كطلب render جديد أو update مقيد. Export ينزل artifact ويتحقق من bytes قبل عرضه عبر Android media/share APIs.

| مسؤولية Android | ما يجب ألا يفعله |
|---|---|
| اختيار الفيديو والوصول إلى URI | تمرير `content://` إلى الخادم باعتباره filesystem path. |
| نسخ المصدر والتحقق من الحجم | الاحتفاظ بمصدر أو نتيجة في public storage بلا حاجة. |
| upload/poll/control عبر Gateway | استيراد Python internal modules أو تشغيل FFmpeg server binary. |
| Room job state وWorkManager | افتراض أن Activity أو process سيبقى حيًا أثناء المعالجة. |
| foreground notification وطلب `POST_NOTIFICATIONS` | إنشاء progress وهمي أو إعلان completion قبل server state. |
| preview/cache/export | عرض path داخلي أو URL غير محمي. |

## الاستمرار والـprocess death

يحتفظ Room بـ`remoteGatewayJobId` وupload offset وstate وlast error. يستخدم WorkManager unique work، وnetwork constraint، وbackoff. عند إغلاق التطبيق يعاد إنشاء observer من Room، ثم يقرأ الحالة من Gateway؛ لا تبدأ مهمة ثانية إذا بقي `idempotency_key` نفسه. عند فشل الشبكة يحتفظ العميل بآخر snapshot ويعرض reconnecting، ثم يستأنف upload أو polling من offset/status.

## الهوية والصلاحيات

الـapplication ID الحالي هو `com.aistudio.opuspro.apk`. يُطلب أقل قدر من الصلاحيات، مع foreground service type `dataSync` للمهام الطويلة و`POST_NOTIFICATIONS` على Android 13+. يُخزّن Gateway token في secure storage خارج Room plain text. لا يُستخدم Tauri-generated Android runtime كبديل صامت للمشروع native.

### المراجع

[1]: ../android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Background work"
[2]: ../android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt "Gateway client"
[3]: ../android/app/src/main/java/com/example/data/repository/OpusRepository.kt "Room/repository flow"
[4]: ../android/app/src/main/AndroidManifest.xml "Permissions and service declaration"
[5]: ../android/app/src/main/java/com/example/MainActivity.kt "Android entry point"

## References

[1]: ../android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Background work"
[2]: ../android/app/src/main/java/com/example/data/remote/ProcessingGatewayClient.kt "Gateway client"
[3]: ../android/app/src/main/java/com/example/data/repository/OpusRepository.kt "Room/repository flow"
[4]: ../android/app/src/main/AndroidManifest.xml "Permissions and service declaration"
[5]: ../android/app/src/main/java/com/example/MainActivity.kt "Android entry point"
