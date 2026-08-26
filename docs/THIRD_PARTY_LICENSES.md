# سجل التراخيص والمصادر الخارجية

**نطاق المراجعة:** المستودع الأساسي والأرشيف المرفق `video_clipper-main` قبل أي نقل للكود.

## النتيجة

المستودع الأساسي يعلن **AGPL-3.0-or-later** في `LICENSE` وREADME، ويحتوي على سجل provenance منفصل في `VENDORED-LICENSES.md`. الأرشيف المرجعي لا يحتوي على ملف `LICENSE`, `COPYING`, أو `NOTICE` مستقل في الجذر أو المستوى القريب، لكن `pyproject.toml` يعلن حقلًا نصيًا يقول MIT. هذا الإعلان وحده لا يوفر نص MIT ولا يكفي لتحديد copyright notices أو حالة الملفات المضمنة؛ لذلك صُنّف كـ **LICENSE_UNCLEAR**، ولم يُنسخ منه أي كود.

| المصدر | ما تم فحصه | الحالة | القرار |
|---|---|---|---|
| publikclip الأساسي | `LICENSE`, `README.md`, `VENDORED-LICENSES.md` | AGPL-3.0-or-later مع سجل مكونات | KEEP_CURRENT؛ الحفاظ على notices والالتزامات. |
| المشروع المرجعي | `pyproject.toml`, README، tree كامل | إعلان MIT نصي بلا ملف ترخيص مستقل | لا نسخ حرفي؛ إعادة تنفيذ مستقل فقط بعد مراجعة قانونية مناسبة. |
| Python dependencies المرجعية | `openai-whisper`, Flask، yt-dlp، SDKs، Edge TTS وغيرها في `pyproject.toml` | لا تم تدقيق تراخيص كل نسخة transitive من الأرشيف | لا تُضاف إلى APK أو runtime الأساسي لمجرد وجودها في المرجع. |
| Android dependencies الحالية | `android/gradle/libs.versions.toml` وGradle build | dependencies خارجية يستخدمها التطبيق | تبقى كما هي؛ يلزم فحص dependency license عند كل release. |
| Fonts داخل pipeline | `pipeline/.../captions/fonts/OFL-*.txt` | notices موجودة محليًا | KEEP_CURRENT؛ عدم حذف notices. |

## سجل ما أُخذ من المرجع

لم يُؤخذ أي ملف أو مقطع كود أو asset من المشروع المرجعي إلى `ISM-dragon/-1`. ما استُخدم هو ملاحظات تصميمية عامة فقط: فصل مراحل واجهة المستخدم، فكرة sequential splitting، قوالب captions، وfallback scoring. هذه الأفكار أعيد تقييمها معماريًا ولم تُعتبر نقلًا لمصدر محمي.

## قواعد الدمج المستقبلية

قبل إدخال أي component من مصدر خارجي يجب حفظ رابط commit أو archive، اسم صاحب copyright، نص الترخيص، الملفات المتأثرة، compatibility مع AGPL-3.0، dependencies المنقولة، وموضع عرض notice للمستخدم. إذا بقي الترخيص غير واضح، يكون القرار `IGNORE_REFERENCE` أو `MANUAL_REVIEW`، ولا يُستخدم النسخ واللصق كحل مؤقت.

هذه الوثيقة ليست رأيًا قانونيًا. أي توزيع تجاري أو نشر عام لـAPK أو خدمة الشبكة يجب أن يمر بمراجعة قانونية للتراخيص وحقوق نماذج الذكاء الاصطناعي وFFmpeg ومصادر الوسائط.

## المراجع

[1]: ../LICENSE "Primary repository AGPL license"
[2]: ../VENDORED-LICENSES.md "Primary repository third-party provenance"
[3]: ../reference/video_clipper-main/pyproject.toml "Reference declared metadata and text license"
[4]: ../reference/video_clipper-main/README.md "Reference project documentation"
[5]: ../android/gradle/libs.versions.toml "Android dependency catalog"

## References

تم بناء السجل من الملفات المحلية المذكورة أعلاه؛ لا يُفهم الإعلان النصي في `pyproject.toml` على أنه بديل عن ملف ترخيص كامل.
