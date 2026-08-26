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

### VideoClipper-main المرفق

يحتوي `VideoClipper-main/LICENSE` على **MIT License** مع `Copyright (c) 2026 Kacper`. فُحصت الملفات `ai_utils.py`, `video_utils.py`, و`app.py` على مستوى المكوّنات، ولم يُنسخ أي منها إلى production. استُخدمت أفكار عامة فقط في المقارنة: audio-energy fallback، face EMA/interpolation، crop presets، وcaption styling. لا توجد إشعارات تشغيلية جديدة مطلوبة ما دام الدمج إعادة تنفيذ مستقلة.

| المصدر | الترخيص المرصود | ما استُخدم | القرار |
|---|---|---|---|
| `VideoClipper-main/app.py` | MIT ضمن repository | مقارنة Streamlit UX فقط | لا نسخ؛ IGNORE_REFERENCE للـAPK/Gateway |
| `VideoClipper-main/ai_utils.py` | MIT ضمن repository | مقارنة ASR/Gemini/fallback فقط | لا نسخ؛ provider secrets تبقى server-side |
| `VideoClipper-main/video_utils.py` | MIT ضمن repository | مقارنة crop/caption/RMS فقط | لا نسخ؛ إعادة تنفيذ مستقلة عند الحاجة |
| `VideoClipper-main/LICENSE` | MIT، copyright Kacper | license audit | يُحفظ النص إذا حدث نسخ فعلي مستقبلًا |

[8]: ../../references/VideoClipper-main/LICENSE "Attached VideoClipper MIT license"
[9]: ../../references/VideoClipper-main/README.md "Attached VideoClipper README"
[10]: ../../references/VideoClipper-main/ai_utils.py "Attached VideoClipper AI helpers"
[11]: ../../references/VideoClipper-main/video_utils.py "Attached VideoClipper media helpers"

## OpenSource Clipping archive — 2026-08-26

تم فحص `opensource-clipping-main.zip` المرفق في هذه الجلسة. ملف `LICENSE` يعلن **MIT License** مع copyright باسم Muhammad Naufal Rizqullah. فُحصت بنية `clipping/` و`web/api/` وملفات التشغيل على مستوى المقارنة، ولم تُنسخ ملفات source أو assets أو models أو binaries أو secrets إلى المستودع الهدف. لذلك لا يضاف notice تشغيلي جديد إلى APK أو Gateway في هذه الدفعة.

| المادة المرجعية | الترخيص | الاستخدام الحالي | القرار |
|---|---|---|---|
| `opensource-clipping-main/clipping/` | MIT ضمن repository | مقارنة pipeline وrender features | لا نسخ؛ إعادة التنفيذ المستقلة فقط. |
| `opensource-clipping-main/web/api/` | MIT ضمن repository | مقارنة Web API وjob UX | لا نسخ؛ عقد `/v1` الحالي يبقى canonical. |
| `opensource-clipping-main/LICENSE` | MIT | تدقيق ترخيص | يُحفظ النص إذا حدث نسخ فعلي مستقبلًا. |

[14]: https://github.com/NaufalRizqullah/opensource-clipping "OpenSource Clipping upstream project"
[15]: ../../upload/opensource-clipping-main/LICENSE "Attached archive license; not committed"

## OpenSource Clipping archive — 2026-08-26

تم فحص `opensource-clipping-main.zip` المرفق في هذه الجلسة. ملف `LICENSE` يعلن **MIT License** مع copyright باسم Muhammad Naufal Rizqullah. فُحصت بنية `clipping/` و`web/api/` وملفات التشغيل على مستوى المقارنة، ولم تُنسخ ملفات source أو assets أو models أو binaries أو secrets إلى المستودع الهدف. لذلك لا يضاف notice تشغيلي جديد إلى APK أو Gateway في هذه الدفعة.

| المادة المرجعية | الترخيص | الاستخدام الحالي | القرار |
|---|---|---|---|
| `opensource-clipping-main/clipping/` | MIT ضمن repository | مقارنة pipeline وrender features | لا نسخ؛ إعادة التنفيذ المستقلة فقط. |
| `opensource-clipping-main/web/api/` | MIT ضمن repository | مقارنة Web API وjob UX | لا نسخ؛ عقد `/v1` الحالي يبقى canonical. |
| `opensource-clipping-main/LICENSE` | MIT | تدقيق ترخيص | يُحفظ النص إذا حدث نسخ فعلي مستقبلًا. |

[14]: https://github.com/NaufalRizqullah/opensource-clipping "OpenSource Clipping upstream project"
[15]: ../../upload/opensource-clipping-main/LICENSE "Attached archive license; not committed"
