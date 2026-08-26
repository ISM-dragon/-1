# Security Boundary

## Threat model

المشروع تطبيق شخصي أمام private processing service، وليس SaaS عامًا. الخطر الأساسي هو كشف source media أو Gateway token أو provider secrets، أو السماح بتنزيل artifact لا يخص job، أو تشغيل Gateway بلا مصادقة.

## Controls

| المجال | الضابط |
|---|---|
| Android secrets | حفظ token عبر secure key manager وعدم تضمينه في APK أو logs |
| Transport | HTTPS لكل Gateway غير loopback؛ HTTP المحلي للاختبارات فقط |
| Gateway auth | Bearer token إلزامي في deployment الخاص، مع fail-closed configuration |
| Provider secrets | Gemini وأي مفاتيح AI تبقى server-side ولا تعبر إلى Android |
| Artifact access | authorization على job/artifact، منع path traversal، وفحص file existence/size |
| Uploads | فحص media مبكر، حدود حجم/مساحة، وcleanup للملفات الجزئية واليتيمة |
| Logs | عدم طباعة tokens أو prompts الحساسة أو stack traces للمستخدم |
| Backup | مراجعة Android backup حتى لا تُنسخ secrets أو media الخاصة بلا سياسة |
| Dependencies | تثبيت versions وفحص license/provenance قبل إضافة dependency |

## Release

توقيع release keystore خارج المستودع عبر متغيرات البيئة. لا تُرفع ملفات `.env` أو keystore أو models أو قواعد بيانات evidence تحتوي بيانات حقيقية. يجب إعادة فحص APK بحثًا عن secrets قبل التوزيع.

## Scope exclusions

لا توجد multi-user accounts أو billing أو social OAuth live ضمن الحد الأدنى. إضافة هذه المكونات توسّع threat model وتتطلب مراجعة مستقلة.

> هذا توثيق هندسي لتقليل المخاطر وليس اعتمادًا أمنيًا أو قانونيًا.
