# Security Boundary

## نموذج التهديد

المنتج شخصي، لكن الفيديوهات والـtranscripts حساسة. التهديدات الأساسية هي تسريب provider keys، كشف media URLs، anonymous Gateway access، path traversal، رفع ملفات فاسدة أو ضخمة، وترك artifacts في public storage. التصميم يقلل ذلك بفصل Android عن runtime الخاص، وبحصر auth والتخزين والمزودات في Gateway.

| الأصل | الحماية المطلوبة |
|---|---|
| Gateway token | لا يضمّن في source أو APK؛ يُخزن في secure storage ويُمرر عبر HTTPS. |
| Provider keys | `gateway/secret_vault.py` أو secret manager على الخادم فقط. |
| Source media | job-private storage، checksum، policy للحجم والصيغة، وretention واضح. |
| Outputs | media route محمي بـBearer ولا يعيد filesystem path داخليًا. |
| Job state | SQLite volume دائم مع correlation/request IDs ومنع تعديل state من العميل. |
| Upload | offset مثبت، SHA-256، filename normalization، ورفض invalid range. |
| Logs | لا transcripts أو tokens أو command secrets في response/log public. |
| Deployment | شبكة خاصة أو reverse proxy/VPN، HTTPS، `REQUIRE_GATEWAY_TOKEN=true`، وvolume دائم. |

## قواعد Android

لا يحتوي APK على Python أو uv أو pip أو Rust أو Node أو FFmpeg binaries أو model weights أو Gemini key. لا تُضاف صلاحيات واسعة بلا مبرر. يجب طلب `POST_NOTIFICATIONS` عند الحاجة فقط، ونسخ URI إلى `filesDir` بدل منح الخادم وصولًا مباشرًا إلى content provider. يجب مسح الملفات المؤقتة بعد النجاح أو وفق retention policy.

## قواعد النشر الشخصي

SQLite وworker واحد مناسبان لمستخدم واحد، لكنهما لا يقدمان horizontal scaling أو multi-tenant isolation. لا يجوز فتح Gateway على الإنترنت العام بلا HTTPS وtoken وreverse proxy مناسب. الأسرار والقيم الإنتاجية توضع في environment/secret manager خارج Git؛ ملفات `.env.example` placeholders فقط.

### المراجع

[1]: ../gateway/secret_vault.py "Gateway secret boundary"
[2]: ../gateway/main.py "Auth, upload, and media routes"
[3]: ../android/app/src/main/AndroidManifest.xml "Android permissions"
[4]: ../.env.example "Example configuration without secrets"
[5]: ../docker-compose.gateway.yml "Private deployment shape"

## References

[1]: ../gateway/secret_vault.py "Gateway secret boundary"
[2]: ../gateway/main.py "Auth, upload, and media routes"
[3]: ../android/app/src/main/AndroidManifest.xml "Android permissions"
[4]: ../.env.example "Example configuration without secrets"
[5]: ../docker-compose.gateway.yml "Private deployment shape"
