# Third-Party Licenses

**الغرض:** سجل audit للتراخيص قبل أي دمج من المشروع المرجعي أو إضافة dependency.

## نتيجة مراجعة الأرشيفات المرجعية

### whisper.cpp-master.zip

يحتوي الأرشيف المرفق على `LICENSE` بترخيص MIT، مع copyright notice منسوب إلى The ggml authors. توجد أيضًا notices خاصة بأجزاء اختبارية مقتبسة من OpenAI Whisper داخل مجلدات tests، ولذلك لا يُفترض أن كل ملف في المستودع يملك notice واحدًا بمجرد قراءة الترخيص الجذري. عينة Android تعتمد على مصدر whisper.cpp وggml من المستودع الكامل، وعلى CMake/NDK، وتضمّن نموذجًا محليًا داخل assets بحسب README. لم تُنسخ أي ملفات أو نماذج أو binaries إلى المستودع الهدف، فلا يضاف dependency notice تشغيلي إلى APK في هذه الدفعة.

| المصدر | الترخيص المرصود | ما استُخدم من المصدر | القرار |
|---|---|---|---|
| whisper.cpp root | MIT | قراءة LICENSE وREADME والعينة فقط | لا نسخ؛ إذا أضيفت dependency مستقبلًا يجب حفظ MIT notice. |
| whisper.cpp `ggml/` | MIT-style notice كما يظهر في الأرشيف | مقارنة CMake/JNI/ASR فقط | لا نسخ؛ يلزم file-level audit قبل أي vendoring. |
| whisper.cpp test normalizers | OpenAI Whisper MIT notice | لم يُستخدم | لا نسخ ولا dependency. |
| whisper.cpp Android sample | MIT-covered source plus repository dependencies | fit check معماري فقط | IGNORE_REFERENCE للـAPK الحالي؛ لا نقل مباشر. |

### SupoClip reference

الأرشيف `supoclip-main.zip` يحتوي على ملف `LICENSE` نصه **GNU Affero General Public License v3.0**، مع copyright notice منسوب إلى Sami Hindi. لذلك فإن أي نسخ أو adaptation من كود SupoClip يحتاج مراجعة قانونية لتوافق AGPL-3.0 مع ترخيص المستودع الهدف، وحفظ notices المناسبة، وتحديد corresponding source عند التوزيع. في هذه الدفعة لم تُنسخ أي ملفات source أو binary أو asset من الأرشيف.

| المصدر | الترخيص المرصود | ما استُخدم من المصدر | القرار |
|---|---|---|---|
| SupoClip reference ZIP | AGPL-3.0 | مقارنة architecture/UX فقط | لا نسخ؛ الأفكار العامة يعاد تنفيذها مستقلًا. |
| whisper.cpp reference ZIP | MIT؛ مع notices فرعية داخل tests | مقارنة Android/JNI/ASR فقط | لا نسخ؛ أي استخدام لاحق يحتاج notice وfile-level dependency audit. |
| Target root | كما هو موثق في `LICENSE` | كود المستودع الحالي | يبقى notice الحالي دون تغيير. |
| Pipeline caption font | notice موجود داخل `pipeline/publikclip_pipeline/captions/fonts/OFL-Anton.txt` | font asset الحالي | يحتفظ notice المحلي وشروط OFL الخاصة به. |
| Vendor model/code | notices ومسارات vendor داخل `pipeline/publikclip_pipeline/vendor/` و`VENDORED-LICENSES.md` | الموجود مسبقًا في الهدف | لا تعديل للـnotice؛ أي تحديث يحتاج مراجعة مستقلة. |
| Android/Gradle/npm dependencies | تراخيص upstream غير منسوخة إلى source | dependencies build-time/runtime | يجب توليد/مراجعة dependency notice عند التوزيع النهائي. |

## قواعد الدمج

قبل إدخال dependency أو file من مشروع آخر يجب تحديد license على مستوى repository وfile، وفحص dependency transitive ذات الصلة، وتسجيل copyright/notice، والتحقق من compatibility. إذا كان الترخيص غير واضح أو غير متوافق، لا يُنسخ الكود؛ تُكتب implementation مستقلة مبنية على السلوك العام فقط. لا تُقبل build outputs أو secrets أو model artifacts من مشاريع مرجعية.

## فحص المستودع

تم التفريق بين source المتعقب وملفات build/cache. لا يُعتد بملفات `node_modules` أو Gradle caches كمرجع ترخيص للمشروع، ولا تُضاف إلى Git. يظل `VENDORED-LICENSES.md` وnotices المحلية مصدر المراجعة للمواد الموجودة بالفعل.

> هذا سجل هندسي وليس رأيًا قانونيًا. يلزم counsel review قبل توزيع APK أو Gateway مشتق إذا أُضيف كود AGPL أو dependency ذات copyleft متعارض.

## References

[1]: https://github.com/FujiwaraChoki/supoclip "Reference project represented by supplied archive"
[2]: https://github.com/FujiwaraChoki/supoclip "Reference project"
[3]: ../LICENSE "Target license"
[4]: ../VENDORED-LICENSES.md "Existing vendor notices"
[5]: ../pipeline/publikclip_pipeline/captions/fonts/OFL-Anton.txt "Font license notice"
[6]: ../../upload/whisper.cpp-master.zip "Supplied whisper.cpp archive; not committed"
[7]: https://github.com/ggerganov/whisper.cpp/blob/master/LICENSE "whisper.cpp MIT license"
