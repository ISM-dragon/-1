# قرارات الترحيل والدمج

## الحالة

هذه الوثيقة تسجل قرارات موجة التدقيق الأولى. القرار لا يعني أن كل feature مكتملة في الإنتاج؛ بل يحدد المسار المعتمد وما يحتاج إلى benchmark أو مراجعة لاحقة.

| المكوّن | القرار | النطاق الحالي | الدليل/الاختبار | المتابعة |
|---|---|---|---|---|
| Android runtime boundary | `KEEP_CURRENT` | Android لا يشغل Python أو FFmpeg أو Node أو Rust | اختبارات بنية Android وفحص APK السابق | اختبار install/launch على جهاز مستقر |
| Gateway API | `KEEP_CURRENT` | `/jobs` و`/jobs/{id}` وresults/cancel/resume/render | `gateway/tests` وعقد Android | توثيق نسخة API وerror envelope |
| Resumable upload | `KEEP_CURRENT` | chunk offsets وSHA-256 وffprobe validation | اختبارات media lifecycle | اختبار انقطاع الشبكة الحقيقي |
| Engine facade | `KEEP_CURRENT` | `PipelineEngine` و`ProcessingEngine` وcheckpoints | `pipeline/tests/test_engine.py` | منع استيرادات داخلية جديدة في Gateway |
| Job persistence | `KEEP_CURRENT` | SQLite + worker queue + pipeline checkpoints | restart/recovery evidence | إضافة lease إذا أصبح هناك أكثر من worker |
| Media errors | `IMPROVE_CURRENT` | تصنيف أخطاء upload/probe/artifact على حدود Gateway | اختبارات validation الحالية | توحيد الأكواد مع pipeline عند تنفيذ التغيير التالي |
| Scoring guardrails | `COMBINE` | الاحتفاظ بالscoring الحالي وإضافة أفكار cleaning فقط عند الحاجة | اختبارات rubric/stability | benchmark جودة clips قبل أي تبديل |
| Camera tracking | `MANUAL_REVIEW` | لا استبدال implementation الحالية | لا يوجد benchmark مرجعي قابل للمقارنة | قياس jitter/render time على dataset ثابت |
| Captions | `IMPROVE_CURRENT` | الاحتفاظ بword timestamps وتطوير styles تدريجيًا | اختبارات render/caption | فصل state عن renderer قبل feature جديدة |
| B-roll | `IGNORE_REFERENCE` | خارج canonical Android path | غير مطلوب لمسار النجاح الأول | قرار مستقل بعد استقرار core path |
| Reference source copy | `IGNORE_REFERENCE` | لا توجد ملفات مصدر من المرجع في المشروع | مراجعة diff وlicense | أي اقتباس مستقبلي חייב license review |
| Release artifact | `KEEP_CURRENT` | Native Android release APK هو artifact الأساسي | Gradle/lint/APK checks | استخدام keystore إنتاجي خاص قبل التوزيع |

## ملكية الملفات

| النطاق | الملفات الرئيسية | قاعدة التعديل |
|---|---|---|
| Android | `android/` | تعديلات lifecycle/UI/network/background فقط |
| Gateway | `gateway/` | API/auth/storage/worker boundary فقط |
| Engine | `pipeline/publikclip_pipeline/engine/` | العقد والتركيب دون UI أو auth |
| Stages | `pipeline/publikclip_pipeline/{asr,camera,...}` | تغيير algorithm مع regression وbenchmark |
| Documentation | `docs/` و`MANUS_HANDOFF.md` | تحديث بعد كل موجة رئيسية |

## القرار التنفيذي

التغيير الحالي يجب أن يركز على توثيق الحدود واختبار البناء، وليس على إعادة كتابة المحرك. هذا يقلل migration risk ويحافظ على السلوك الموجود، مع إبقاء نقاط التحسين في media/captions/scoring قابلة للتنفيذ لاحقًا دون كسر عقد Android.
