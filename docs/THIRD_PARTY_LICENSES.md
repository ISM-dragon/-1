# تراخيص الأطراف الثالثة

**الغرض:** سجل التدقيق قبل أي دمج من مشروع مرجعي أو إضافة dependency.

## نتيجة مراجعة الأرشيف المرجعي

الأرشيف المرفق `opensource-clipping-main.zip` هو مشروع **OpenSource Clipping**. يحتوي ملف `LICENSE` على ترخيص **MIT** وإشعار copyright باسم Muhammad Naufal Rizqullah. لم تُنسخ ملفات source أو binary أو asset أو build output من الأرشيف إلى المستودع الهدف؛ استُخدمت ملاحظاته على مستوى السلوك والميزات لتوجيه المقارنة وإعادة التنفيذ المستقلة.

ترخيص MIT يسمح بالنسخ والتعديل وإعادة التوزيع بشرط الاحتفاظ بإشعار copyright ونص الترخيص، مع بقاء ضمانات المشروع كما هي. هذا السجل ليس رأيًا قانونيًا، ولذلك يجب إعادة فحص الترخيص وملفات dependencies إذا أُدخل أي كود فعليًا في إصدار لاحق.

| المصدر | الترخيص المرصود | ما استُخدم من المصدر | القرار |
|---|---|---|---|
| OpenSource Clipping reference archive | MIT؛ `opensource-clipping-main/LICENSE` | مقارنة architecture/UX وميزات clipping فقط | لا نسخ مباشر؛ إعادة التنفيذ المستقلة هي المعتمدة. |
| Target root | AGPL-3.0-or-later؛ `LICENSE` | كود المستودع الحالي | يبقى notice الحالي دون تغيير. |
| Pipeline caption font | OFL-1.1؛ `pipeline/publikclip_pipeline/captions/fonts/OFL-Anton.txt` | font asset الموجود مسبقًا | يحتفظ notice المحلي وشروط OFL. |
| Vendor model/code | notices ومسارات vendor داخل `pipeline/` و`VENDORED-LICENSES.md` | الموجود مسبقًا في الهدف | لا تعديل للـnotice؛ أي تحديث يحتاج مراجعة مستقلة. |
| Android/Gradle/npm dependencies | تراخيص upstream في ملفات lock/cache أو metadata | dependencies build-time/runtime | يجب توليد ومراجعة dependency notice عند توزيع APK أو Gateway. |

## قواعد الدمج

قبل إدخال dependency أو file من مشروع آخر يجب تحديد license على مستوى repository وfile، وفحص dependencies العابرة ذات العلاقة، وتسجيل copyright/notice، والتحقق من التوافق مع طريقة توزيع المستودع. إذا كان الترخيص غير واضح أو غير متوافق، لا يُنسخ الكود؛ تُكتب implementation مستقلة مبنية على السلوك العام فقط. لا تُقبل build outputs أو secrets أو model artifacts من مشاريع مرجعية.

لا يحتوي هذا التغيير على مادة من الأرشيف المرجعي تتطلب notice إضافيًا. إذا تغير ذلك، يجب إضافة notice صريح ونسخة من نص الترخيص في موضع مناسب قبل الدمج.

## فحص المستودع

تم التفريق بين source المتعقب وملفات build/cache. لا يُعتد بملفات `node_modules` أو Gradle caches كمرجع ترخيص للمشروع، ولا تُضاف إلى Git. يظل `VENDORED-LICENSES.md` وnotices المحلية مصدر المراجعة للمواد الموجودة بالفعل.

> هذا سجل هندسي وليس رأيًا قانونيًا. يلزم counsel review قبل توزيع APK أو Gateway إذا أضيفت مادة ذات شروط خاصة أو تغيرت طريقة التجميع والتوزيع.

## References

[1]: https://github.com/NaufalRizqullah/opensource-clipping "OpenSource Clipping reference project"
[2]: ../../upload/opensource-clipping-main.zip "Supplied reference archive; not committed"
[3]: ../LICENSE "Target AGPL-3.0-or-later license"
[4]: ../VENDORED-LICENSES.md "Existing vendor notices"
[5]: ../pipeline/publikclip_pipeline/captions/fonts/OFL-Anton.txt "Font license notice"
