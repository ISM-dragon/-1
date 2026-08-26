# Private Backend لتطبيق Android

**الحالة:** جاهز للتجربة المحلية والربط مع Android
**النطاق:** مستخدم شخصي واحد، دون حسابات أو billing أو بنية multi-user
**التاريخ:** 2026-08-26

## الغرض والمعمارية

يوفر هذا الـ backend واجهة FastAPI خاصة تسمح لتطبيق Android برفع فيديو، إنشاء وظيفة معالجة، متابعة تقدّمها، إلغائها أو استئنافها، ثم قراءة المقاطع الناتجة وإعادة رندر مقطع محدد. التنفيذ لا يضيف خدمة ثانية؛ بل يضع مسارات Android المختصرة فوق Gateway الموجود أصلاً، ويعيد استخدام SQLite و`PersistentWorkerQueue` وcheckpoint files ومحرك `publikclip` نفسه.[1]

```text
Android
   ↓ HTTPS + Bearer token
Private API: /jobs/*
   ↓ SQLite job row + worker queue
publikclip engine: pipeline/publikclip_pipeline
   ↓ JSONL progress + atomic checkpoints
job/checkpoints
   ↓ validated artifacts
output clips
```

يمتلك الـ Gateway حالة الوظائف والـ authentication والتحقق من المدخلات، بينما يمتلك المحرك تحليل الوسائط وASR واختيار المقاطع والرندر. يحتفظ SQLite بسجل الوظيفة، وتبقى ملفات checkpoint الناتجة من المحرك مصدر الاستئناف الفعلي؛ وهذا يطابق قاعدة التصميم الموجودة في queue المحرك بأن الـ artifacts على القرص هي الحقيقة وأن الكتابة تتم ذرّياً.[2]

المسارات الجديدة `/jobs/*` هي واجهة خاصة ومبسطة لـ Android، أما `/v1/processing/*` فتبقى متاحة للتوافق مع العميل الحالي. كلاهما يقرأ ويعدّل نفس صفوف `processing_jobs` ونفس مجلدات `PUBLIKCLIP_HOME`، لذلك لا توجد حالة متوازية أو backend ثانٍ.

## التشغيل المحلي

من جذر المستودع، ثبّت اعتماديات الـ Gateway داخل بيئة افتراضية ثم شغّل Uvicorn. اعتماد `python-multipart` مطلوب لأن `POST /jobs` يستقبل `multipart/form-data`.

```bash
cd gateway
python3 -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt

export PROVIDER_MODE=mock
export GATEWAY_TOKEN='generate-a-long-random-token'
export REQUIRE_GATEWAY_TOKEN=true
export PUBLIC_BASE_URL='http://127.0.0.1:8787'
export ISM_PIPELINE_DIR="$PWD/../pipeline"
export ISM_MAX_UPLOAD_BYTES=$((2 * 1024 * 1024 * 1024))

python3 -m uvicorn main:app --host 127.0.0.1 --port 8787
```

لربط هاتف على شبكة محلية، استخدم عنوان LAN للخادم بدلاً من `127.0.0.1`، واجعل `PUBLIC_BASE_URL` مطابقاً للعنوان الذي يستطيع الهاتف الوصول إليه. في أي نشر خارج الشبكة المحلية يجب استخدام HTTPS أو شبكة VPN خاصة؛ لا يُرسل مفتاح Gemini إلى Android، بل يبقى في بيئة الخادم كما يوضح توثيق Gateway الحالي.[3]

## واجهة Android

جميع المسارات التالية محمية بـ `Authorization: Bearer <GATEWAY_TOKEN>` عندما يكون `REQUIRE_GATEWAY_TOKEN=true`. معرّف الوظيفة opaque ويُنشئه الخادم. القيم الزمنية ISO-8601 UTC، و`progress` قيمة بين `0.0` و`1.0` عندما يوفرها المحرك، وقد تكون `null` في بداية مرحلة لا تقدم نسبة قابلة للقياس.

| الطريقة والمسار | المدخلات | السلوك |
|---|---|---|
| `POST /jobs` | `multipart/form-data`؛ الحقل `file` للفيديو، والحقول الاختيارية `llm` و`captions` و`mode` و`idempotency_key` | يحفظ الفيديو تحت مجلد upload خاص، ينشئ صفاً دائماً في SQLite، ثم يضع الوظيفة في worker queue. يمكن استخدام `source_url` بدلاً من `file` لمصدر HTTPS عام؛ إذا أُرسلا معاً فالفيديو المرفوع هو المستخدم. |
| `GET /jobs/{job_id}` | لا شيء | يعيد حالة الوظيفة الحالية و`current_stage` و`progress` و`status` و`errors` وبيانات الاستئناف. |
| `POST /jobs/{job_id}/cancel` | لا شيء | يحفظ طلب الإلغاء أولاً، ثم ينهي عملية المحرك إن كانت تعمل. الإلغاء idempotent للوظيفة الملغاة، ولا يمكن إلغاء وظيفة مكتملة. |
| `POST /jobs/{job_id}/resume` | لا شيء | يعيد وظيفة `INTERRUPTED` أو `FAILED` أو `RETRY_WAIT` إلى queue ويستعمل `pipeline_job_id` وcheckpoint المتاحين. |
| `GET /jobs/{job_id}/results` | لا شيء | يعيد artifacts المقاطع بعد `COMPLETED` فقط. قبل ذلك يعيد `409 RESULTS_NOT_READY`. |
| `GET /jobs/{job_id}/clips/{clip_id}` | `clip_id` رقم غير سالب، ويدعم أيضاً صيغة `clip_01` | يعيد بيانات المقطع، الكلمات والأحداث والـ edit context، دون كشف مسارات النظام المحلية. |
| `POST /jobs/{job_id}/clips/{clip_id}/render` | لا شيء | يعيد رندر المقطع الفردي باستخدام edit/checkpoint الحالية، ثم يفحص artifact ويعيد URL قابل التنزيل. |

### إنشاء وظيفة ورفع فيديو

الرفع streaming وليس قراءة الملف كاملاً في الذاكرة. الامتدادات المقبولة هي `.mp4` و`.mov` و`.mkv` و`.webm`، كما يُقبل `video/*` عندما لا يرسل العميل اسماً بامتداد معروف. الحد الأقصى الافتراضي هو 2 GiB ويمكن تغييره عبر `ISM_MAX_UPLOAD_BYTES`.

```bash
curl -X POST 'http://127.0.0.1:8787/jobs' \
  -H "Authorization: Bearer $GATEWAY_TOKEN" \
  -F 'file=@./sample.mp4;type=video/mp4' \
  -F 'llm=ollama' \
  -F 'mode=balanced'
```

الاستجابة الأولية تكون بهذا الشكل التقريبي:

```json
{
  "job_id": "proc_…",
  "status": "queued",
  "state": "QUEUED",
  "current_stage": null,
  "progress": null,
  "message": "Job accepted",
  "errors": [],
  "recoverable": true,
  "cancel_requested": false,
  "retry_count": 0,
  "results_available": false
}
```

### polling والتقدم

يمكن لـ Android تنفيذ polling متدرج على `GET /jobs/{job_id}`، مع عرض `current_stage` و`progress` كما يرسلها الخادم، وعدم اختراع نسبة تقدّم محلية. الحالات canonical هي `QUEUED`, `PREPARING`, `DOWNLOADING`, `INGESTING`, `TRANSCRIBING`, `DIARIZING`, `ANALYZING`, `CANDIDATES_READY`, `SCORING`, `EDITING`, `RENDERING`, `FINALIZING`, `COMPLETED`, `FAILED`, `CANCELLED`, `RETRY_WAIT`, و`INTERRUPTED`.[4]

| الحقل | المعنى |
|---|---|
| `status` | قيمة مبسطة للتوافق: `queued`, `running`, `done`, `failed`, أو `cancelled`. |
| `state` | الحالة canonical الكاملة التي ينبغي استخدامها لاتخاذ قرارات UI. |
| `current_stage` | اسم مرحلة المحرك الحالية مثل `ingest` أو `asr` أو `render`. |
| `progress` | كسر عشري بين صفر وواحد، أو `null` عندما لا توجد نسبة موثوقة. |
| `errors` | مصفوفة أخطاء آمنة؛ كل عنصر يحتوي `code` و`message`، ولا يحتوي traceback أو أسراراً أو مسارات خاصة. |
| `results_available` | يصبح `true` عند اكتمال الوظيفة ووجود manifest نتائج. |

## الأمان والتحقق

يجب ضبط `REQUIRE_GATEWAY_TOKEN=true` في أي خدمة يمكن الوصول إليها من الهاتف عبر شبكة غير موثوقة. يقارن Gateway bearer token باستخدام مقارنة ثابتة زمنياً، ويجب عدم وضع الرمز أو مفتاح Gemini في Git أو URL أو JSON الخاص بالوظيفة. حدّد `CORS_ORIGINS` بدلاً من wildcard عند وجود عميل متصفح، واستخدم `PUBLIC_BASE_URL` ثابتاً ومطابقاً للعنوان الخارجي.

يتحقق الرفع من امتداد أو نوع الفيديو، ويكتب chunks محدودة الحجم، ويزيل المجلد الجزئي عند تجاوز الحد أو حدوث فشل. أسماء الملفات تُخفض إلى basename، ومسار كل upload وartifact يُحلّ عبر `Path.resolve()` ثم يُفحص containment داخل مجلد الوظيفة. لا تُعرض ملفات خارج المجلد المصرح به، ولا تُعاد المسارات المحلية في تفاصيل المقاطع أو النتائج. هذه الحدود تكمل قواعد SSRF وmedia artifact الموجودة في Gateway بدلاً من تجاوزها.[5]

الـ API لا يفتح accounts أو billing أو public authentication أو multi-user authorization. هذا مقصود لأن الخدمة شخصية. إذا أصبح الخادم مشتركاً بين مستخدمين، فهذه الواجهة غير كافية وحدها، ويجب إضافة عزل هوية وصلاحيات ومخزن أسرار وسياسة retention قبل النشر.

الأخطاء التشغيلية تعاد برسالة ثابتة وcode قابل للمعالجة من Android، مثل `VIDEO_REQUIRED`, `UPLOAD_TOO_LARGE`, `UNSUPPORTED_VIDEO`, `JOB_NOT_FOUND`, `RESULTS_NOT_READY`, `CLIP_NOT_READY`, و`CLIP_RENDER_FAILED`. لا تعاد تفاصيل FFmpeg أو traceback للعميل.

## persistence والاستعادة

عند قبول الوظيفة، تُحفظ metadata في `processing_jobs` قبل submit إلى worker. يكتب Gateway progress transitions في SQLite، ويحفظ worker `pipeline_job_id` عندما يصدره المحرك. عند إعادة تشغيل Gateway، تُعاد الوظائف غير النهائية إلى حالة قابلة للاستعادة، ويُعاد استخدام checkpoint المحرك بدلاً من فقدان العمل السابق. وظيفة الإلغاء المحفوظة لا تُعاد إلى التنفيذ تلقائياً.

الـ worker محدود افتراضياً بمهمة معالجة واحدة عبر `MAX_ACTIVE_PROCESSING_JOBS=1`، مع حارس للمساحة الحرة عبر `MIN_FREE_DISK_GB`. لذلك لا يحتاج هذا التصميم الشخصي إلى Redis أو Kafka؛ SQLite والملفات الذرية يكفيان لهذا النطاق.[1]

## الاختبارات

اختبارات العقد والأمان والرفع موجودة في `gateway/tests/test_private_backend.py`، وتختبر التسجيل، bearer token عبر HTTP، multipart upload، حد الحجم، تنظيف الملفات الجزئية، shape التقدم والأخطاء، رفض النتائج قبل اكتمالها، ورفض path escape.

```bash
cd /path/to/repository
PYTHONPATH=. pytest -q gateway/tests/test_private_backend.py
PYTHONPATH=. pytest -q gateway/tests/test_private_backend.py gateway/tests/test_web_processing_contract.py gateway/tests/test_job_state.py gateway/test_processing_bridge.py
```

يوجد أيضاً اختبار E2E قابل للإعادة في `scripts/e2e_private_backend.sh`. ينشئ MP4 حقيقياً عبر FFmpeg مع video وaudio، يشغّل Uvicorn، يرفع الملف عبر `POST /jobs`، يستطلع الحالة، ويتحقق من وصول الوظيفة إلى حالة نهائية مع progress وerrors محفوظة. في بيئة التنفيذ الحالية وصل المحرك فعلياً إلى مرحلة ASR ثم انتهى بخطأ آمن `PIPELINE_STAGE_FAILED` لأن الملف الاصطناعي يحتوي نغمة لا كلاماً؛ لذلك لم يُدّعَ نجاح رندر كامل لمقطع. يتطلب اختبار النجاح الكامل فيديو كلامياً واعتماديات ASR/LLM والنماذج اللازمة.

```bash
cd /path/to/repository
scripts/e2e_private_backend.sh
```

## مراجع المستودع

[1]: ../docs/MASTER-ARCHITECTURE.md "الأساس المعماري canonical للمستودع"

[2]: ../pipeline/publikclip_pipeline/jobs/queue.py "استمرارية الوظائف وcheckpoint في محرك publikclip"

[3]: ../gateway/processing_service.py "حد Gateway الفاصل مع عملية المحرك والأسرار server-side"

[4]: ../gateway/job_state.py "آلة حالات وظائف المعالجة"

[5]: ../gateway/worker_queue.py "فحص artifacts وحارس موارد worker"
