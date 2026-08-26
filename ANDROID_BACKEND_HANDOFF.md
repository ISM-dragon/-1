# Android Backend Handoff

هذا الملف يخص تبعية الاختبار خارج نطاق Android BUILD & RELEASE، ولا يطلب أي تعديل في خوارزميات المحرك أو scoring أو واجهة Android.

## المطلوب من Backend/Infra

يجب توفير عنوان **HTTPS** قابل للوصول من جهاز Android أو Emulator، مع شهادة موثوقة وtoken اختبار محدود الصلاحية. عميل Android يستخدم عقد Gateway التالية:

| الغرض | Endpoint |
|---|---|
| Health | `GET /health` |
| Session/auth | `GET /v1/auth/session` |
| Capabilities | `GET /v1/processing/capabilities` |
| Processing | مسارات `/v1/processing/...` التي يستدعيها `ProcessingGatewayClient` |

يجب تمرير التوكن في `Authorization: Bearer <token>`. لا يوضع التوكن أو مفتاح الشهادة الخاص داخل Git أو APK.

## شرط الشبكة

Release لا يفعّل cleartext traffic. لذلك يجب استخدام HTTPS. إذا كان الخادم على شبكة خاصة بعنوان LAN مثل `192.168.x.x` أو `10.x.x.x`، فالعنوان الخاص وحده لا يكفي؛ يلزم TLS termination وشهادة يثق بها الجهاز، أو سياسة network security مقيدة بمضيف محدد تعتمدها جهة Android بعد مراجعة أمنية.

## اختبار القبول المقترح

بعد توفير endpoint والتوكن وعلى جهاز Android متصل:

```bash
adb install -r app-release.apk
adb shell am start -W -n com.aistudio.opuspro.apk/.MainActivity
adb logcat -c
adb shell am force-stop com.aistudio.opuspro.apk
adb shell am start -W -n com.aistudio.opuspro.apk/.MainActivity
adb logcat -d -v threadtime | grep -E 'ProcessingGatewayClient|SocialGatewayClient|HTTP|FATAL EXCEPTION'
```

يُعد الاختبار ناجحاً عندما يفتح `MainActivity` دون crash، ويقبل التطبيق عنوان HTTPS، وتعود endpoints الثلاثة بحالة 2xx من الجهاز نفسه، ثم تظهر حالة Gateway connected داخل التطبيق. يلزم تسجيل hostname والمنفذ ووقت الاختبار ونسخة APK، دون تسجيل التوكن أو أي بيانات شخصية.
