# خطة ترحيل ومقارنة المشروع المرجعي

**الهدف:** الوصول إلى APK Android شخصي يعمل عبر private processing service، مع إبقاء PublikClip Engine ومراحل AI/media على الخادم.

## مبدأ التنفيذ

تُنفذ الخطة على موجات، ولا تبدأ موجة لاحقة إذا كانت الواجهة السابقة غير مستقرة. المشروع المرجعي يستخدم لفهم أفكار محددة فقط؛ أي component يُعاد تنفيذه مستقلًا داخل بنية publikclip بعد تعريف contract واختبار regression. لا يُسمح بنسخ كود من الأرشيف في ظل غياب ملف ترخيص مستقل واضح.

## الموجات

| الموجة | النطاق | المخرجات | بوابة الانتقال |
|---|---|---|---|
| Wave 1 | Audit + architecture + comparison | هذه الوثيقة، المقارنة، القرارات، licenses، architecture | اكتمال الجرد وعدم وجود قرار نسخ غير موثق |
| Wave 2 | Engine + AI/media + backend foundations | Engine facade، lifecycle، checkpoint، media errors، provider fallback | unit tests وcontract tests خضراء، وCLI قابل للتشغيل على host مجهز |
| Wave 3 | Android core/UI/build | عميل remote، URI handling، WorkManager، notifications، results/export | Android unit tests، lint، release assembly |
| Wave 4 | Integration | Android ↔ Gateway ↔ Engine، upload/retry/resume/cancel، artifact download | gateway contract + smoke test على خدمة محلية |
| Wave 5 | E2E + QA + release | اختبار فيديو حقيقي، restart/network loss، APK signed externally | وجود evidence؛ لا يكفي compile |

## الأولويات المحددة

### أولوية P0: تثبيت boundary والعميل الفعلي

المسار الذي يطلقه `VideoUploadScreen` يستخدم `OpusRepository.enqueueVideoProcessing` ثم `VideoProcessingWorker`. لذلك لا يكفي وجود `remote/*` كمسار غير مستخدم. يجب أن يستعمل العامل الفعلي عقد Gateway أو private backend المعتمد، وأن تكون مفاتيح المصادقة وDevice ID خارج الكود المصدر.

### أولوية P0: توافق العقد

يجب أن يدعم العميل المسارات التي يوفرها الخادم المختار فقط. Gateway الحالي يوفر `/v1/sources/upload` و`/v1/processing/jobs/*`، بينما backend البديل يوفر `/uploads` و`/jobs/*`. لا يجوز خلط المسارين في عميل واحد دون adapter صريح. القرار المرحلي هو إبقاء Gateway control plane canonical لأن Android الحالي، الوثائق، والاختبارات تستخدم عقده؛ ويُعامل `backend/` كـalternative stack حتى يثبت دمجه في قرار مستقل.

### أولوية P1: upload resumability

عميل Android الحالي يرفع one-shot، رغم أن Gateway يوفر جلسات resumable مع offset وSHA-256. يُضاف adapter resumable بعد تثبيت contract، مع fallback one-shot فقط للخوادم التي تعلن capability صراحة. هذا يمنع إعادة رفع ملف كبير كاملًا بعد network interruption.

### أولوية P1: state restoration

يجب حفظ local job وremote job ID وprogress وstage وartifact paths بطريقة durable، ثم إعادة enqueue بعد process death. `Room` يبقى مصدر الحالة لمسار التطبيق الرئيسي، مع عدم إنشاء store متوازٍ غير مستخدم إلا إذا أُعيد توصيله بالواجهة.

### أولوية P1: error taxonomy

تُوحّد أخطاء media وmodel وnetwork في أكواد قابلة للعرض وإعادة المحاولة: `MEDIA_INVALID`, `FFMPEG_MISSING`, `FFMPEG_FAILED`, `MODEL_MISSING`, `MODEL_INVALID`, `INSUFFICIENT_DISK`, `UNSUPPORTED_FORMAT`, `AUTH_REQUIRED`, `NETWORK_UNAVAILABLE`, و`JOB_NOT_RESUMABLE`.

### أولوية P2: تحسينات المرجع

تُعاد دراسة sequential splitting، قوالب captions الأبسط، وواجهة clip review بعد استقرار المسار الأساسي. أي إضافة يجب أن تأتي مع benchmark أو regression test، وألا تُدخل Flask/Whisper desktop أو SDKs غير لازمة إلى APK.

## معايير القبول

لا تُعلن المهمة مكتملة إلا بعد إثبات: اختيار فيديو حقيقي، رفعه، إنشاء job، polling، تنفيذ stages، إرجاع clip metadata، تنزيل MP4، preview/edit/render/export، ثم إغلاق Android وإعادة فتحه واستعادة job، مع اجتياز cancel/retry/resume/network interruption وfailure handling. نتائج كل اختبار تحفظ في `evidence/` وتذكر في `MANUS_HANDOFF.md`.

## المراجع

[1]: ../docs/ARCHITECTURE.md "Canonical architecture"
[2]: ../gateway/main.py "Gateway v1 routes and persistent workers"
[3]: ../backend/app.py "Alternative private backend API"
[4]: ../android/app/src/main/java/com/example/ui/screens/VideoUploadScreen.kt "Main Android import and enqueue flow"
[5]: ../android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Main Android worker"
[6]: ../reference/video_clipper-main/README.md "Reference feature inventory"

## References

المراجع أعلاه ملفات محلية في نفس المستودع وتمثل source of truth للقرارات المرحلية.
