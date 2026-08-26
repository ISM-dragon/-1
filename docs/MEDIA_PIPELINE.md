# Media Pipeline Reliability

## النطاق

هذا المستند يصف **نقل وتخزين ملفات الفيديو فقط** في Gateway. لا يغيّر المسار الجديد قواعد scoring أو مراحل AI أو عقود تشغيل الـpipeline؛ بل يضمن أن الملف لا يصبح مصدرًا للـprocessing إلا بعد اكتمال الكتابة، والتحقق من الحجم، ونجاح SHA-256، ونقله ذريًا إلى مكانه النهائي.

> القاعدة التشغيلية: لا يُكشف ملف `.part` للعملاء ولا يُمرَّر إلى الـpipeline. الملف يصبح مصدرًا صالحًا فقط بعد `complete` ونجاح integrity validation.

## دورة الحياة

يستخدم العميل مسارًا قابلًا للاستئناف من أربع خطوات. يبدأ العميل بجلسة upload تحمل الحجم النهائي وSHA-256 المحسوب محليًا، ثم يرسل body واحدًا أو أكثر مع offset متسلسل. بعد الانقطاع، يقرأ العميل الحالة ويعيد الإرسال من `offset` المعاد؛ ولا يُسمح بالكتابة في offset مختلف عن الموضع المثبت في SQLite.

| المرحلة | المسار | النتيجة |
|---|---|---|
| إنشاء الجلسة | `POST /v1/sources/uploads` | `id`, `expected_bytes`, `expected_sha256`, `offset=0`, `progress=0` |
| معرفة التقدم | `GET /v1/sources/uploads/{upload_id}` | الحالة الحالية والـoffset والـprogress دون كشف المسار الداخلي المؤقت |
| إرسال chunk | `PUT /v1/sources/uploads/{upload_id}` | يقبل `X-Upload-Offset` أو `Content-Range: bytes start-end/total` ويعيد offset جديدًا |
| إكمال وتثبيت | `POST /v1/sources/uploads/{upload_id}/complete` | يتحقق من الحجم وSHA-256، ثم ينفذ atomic rename إلى `source.mp4` وينشئ source job مكتملًا |
| قراءة المصدر النهائي | `GET /v1/sources/jobs/{job_id}/media/source.mp4` | يقدّم الملف النهائي فقط بعد اكتمال source job |

يمكن للعملاء الذين لا يحتاجون resume الاستمرار في استخدام `POST /v1/sources/upload`. هذا المسار legacy يستخدم نفس temp storage وSHA-256 وatomic finalize، لذلك لا يُنشئ ملفًا نهائيًا جزئيًا.

## التخزين المؤقت والآمن

تُكتب الأجزاء في `SOURCE_ROOT/.uploads/{upload_id}.part`، وتُضبط صلاحية مجلد `.uploads` ومجلد المصدر النهائي على `0700`. لا يُعاد أي مسار داخلي في استجابات API. عند الإكمال، يُنقل الملف إلى `SOURCE_ROOT/{upload_id}/source.mp4` باستخدام عملية ذرية، ثم تُسجل metadata في جدول `media_uploads` وتُنشأ سجلات `source_jobs` التي تستخدمها بقية المنظومة.

يُحفظ offset في SQLite بعد flush و`fsync`، ولا يُحدَّث إلا بعد أن تصبح bytes المكتوبة على القرص قابلة للاستئناف. يوجد lock داخل العملية لكل upload session لمنع طلبين متزامنين من الكتابة فوق نفس offset. وعند اكتشاف أن الملف الموجود على القرص أطول من offset المسجل، يُقص إلى offset الآمن قبل المتابعة؛ أما إذا كان أقصر، فيُرفض الطلب لأن الاستمرار قد ينتج ملفًا غير قابل للتحقق.

## Progress وresume/retry

يُحسب `progress` على أساس `received_bytes / expected_bytes`، بينما يمثل `offset` عدد bytes المثبتة. يجب أن يرسل العميل chunk يبدأ بالـoffset المعاد من الخادم. عند الانقطاع، لا تُعاد تهيئة الجلسة؛ يكفي استدعاء `GET` ثم إعادة `PUT` من نفس offset. إذا وصل chunk بمدى متعارض، يعاد `409`; وإذا تجاوز الحجم أو المدى المعلن، يعاد `413` أو `416` بحسب نوع الخطأ.

الـretry هنا خاص بنقل الوسائط: إعادة المحاولة آمنة لأن كل chunk idempotent عند نفس offset، والإكمال idempotent أيضًا؛ فاستدعاء `complete` بعد نجاح سابق يعيد نفس النتيجة. أما retry الخاص بـprocessing job فبقي كما هو ولم تُعدّل قواعده.

## Integrity وduplicate handling

لا يتم التثبيت قبل تحققين مستقلين: تطابق حجم الملف الفعلي مع `expected_bytes`، ثم تطابق SHA-256 المحسوب من الملف مع `expected_sha256`. عند الفشل، يُحذف temp file وتتحول الجلسة إلى `corrupt`، ولا يُنشأ `source_job` ولا URL صالح للمصدر.

إذا بدأ العميل جلسة جديدة بنفس `(expected_bytes, expected_sha256)`، يعيد Gateway الجلسة الجارية أو الملف المكتمل بدل تخزين نسخة ثانية. وتُعاد قيمة `reused=true` في هذه الحالة. هذا يحد من duplicate uploads على مستوى المحتوى، وليس فقط على مستوى اسم الملف.

## حدود الأحجام

الحد الافتراضي هو **2 GiB** عبر `ISM_MAX_UPLOAD_BYTES`، ويمكن خفضه أو تغييره في بيئة التشغيل. لا يعتمد المسار على تحميل الملف كاملًا في الذاكرة؛ body يُستهلك chunk-by-chunk ويُكتب مباشرة إلى disk. لذلك يجب ضبط مساحة التخزين الحرة بما يناسب حجم المصدر والنتائج المؤقتة، وتبقى حماية worker الحالية (`MIN_FREE_DISK_GB`) فعالة لمسار الـprocessing.

| السيناريو | التغطية العملية |
|---|---|
| `100MB` | تشغيل disk-backed فعلي في اختبار الحجم الكبير |
| `500MB` | تشغيل disk-backed فعلي في اختبار الحجم الكبير |
| `1GB+` | تشغيل `1025MB` فعليًا في اختبار الحجم الكبير |
| interrupted upload | chunk أول، قراءة offset، ثم resume من offset الصحيح |
| duplicate upload | إعادة init بنفس الحجم وSHA-256 وإعادة استخدام الجلسة/النتيجة |
| corrupted upload | إرسال bytes لا تطابق SHA-256 ورفض complete دون output نهائي |

## Output file handling

عند انتهاء الـpipeline، يفحص Gateway كل output قبل نشره عبر endpoint media. يجب أن يكون الملف موجودًا، غير فارغ، وبامتداد video مدعوم. تُضاف إلى كل output قيم `bytes` و`sha256` وكتلة `integrity`، بينما يبقى URL العام موجّهًا إلى endpoint المحمي الذي لا يقرأ إلا ملفات `.mp4` الموجودة داخل مجلد job النهائي. لا تُستخدم ملفات مؤقتة أو ملفات خارج مجلد الـjob كـoutputs.

## Automatic cleanup

ينفذ Gateway `cleanup_media_uploads()` عند startup. تُحذف جلسات `uploading` و`corrupt` و`failed` التي تجاوزت `ISM_MEDIA_UPLOAD_TTL_SECONDS` (الافتراضي 24 ساعة) مع ملفاتها المؤقتة. كما تُحذف orphan `.part` files القديمة من `.uploads`. لا يلمس cleanup جلسات `completed` ولا ملفات source النهائية ولا outputs الخاصة بالـprocessing.

## الاختبار العملي

يوجد الاختبار في [`gateway/tests/test_media_lifecycle.py`](../gateway/tests/test_media_lifecycle.py). للتحقق السريع شغّل:

```bash
python3 -m unittest gateway.tests.test_media_lifecycle -v
```

يشغّل الأمر السابق اختبار lifecycle صغيرًا ويترك اختبار الأحجام الثقيلة في وضع skip. للتحقق الفعلي من جميع الأحجام المطلوبة شغّل:

```bash
RUN_LARGE_MEDIA_TESTS=1 MEDIA_TEST_SIZES_MB=100,500,1025 \
  python3 -m unittest gateway.tests.test_media_lifecycle -v
```

يتطلب الاختبار الكبير مساحة قرابة 1GB على الأقل للملف الجاري، إضافة إلى مساحة مؤقتة مناسبة. في التحقق المنفذ لهذا التغيير نجحت تشغيلات `100MB` و`500MB` و`1025MB`، كما نجحت suite اختبارات Gateway القائمة؛ كان عدد اختبارات suite النهائي `29` قبل إضافة مسارات العقد الجديدة، مع اختبار lifecycle الكبير اختياريًا حسب متغير البيئة.

## الإعدادات

| المتغير | الافتراضي | الغرض |
|---|---:|---|
| `ISM_MAX_UPLOAD_BYTES` | `2147483648` | الحد الأقصى لحجم الفيديو المعلن والمستلم |
| `ISM_MEDIA_UPLOAD_CHUNK_BYTES` | `16777216` | حجم القراءة عند hashing وعتبة fsync أثناء الكتابة |
| `ISM_MEDIA_UPLOAD_TTL_SECONDS` | `86400` | مدة الاحتفاظ بجلسات الرفع غير المكتملة أو الفاشلة |
| `ISM_SOURCE_ROOT` | `gateway/sources` | جذر الملفات المؤقتة والنهائية للمصادر |

## مراجع الكود

[1]: ../gateway/main.py "Gateway media upload lifecycle and output integrity"
[2]: ../gateway/tests/test_media_lifecycle.py "Practical media lifecycle tests"
[3]: ../gateway/worker_queue.py "Worker resource and artifact validation"

المرجع الأساسي لتنفيذ upload lifecycle هو [`gateway/main.py`][1]، بينما يغطي الاختبار العملي [`gateway/tests/test_media_lifecycle.py`][2]، ويظل فحص artifacts الخاص بالـworker في [`gateway/worker_queue.py`][3].
