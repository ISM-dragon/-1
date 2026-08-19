# Gateway Forensic Audit

## الوضع الحالي

`gateway/main.py` ملف FastAPI أحادي كبير نسبيًا، ويجمع configuration وSQLite schema وPydantic models وsecurity validation وworkers وroutes وHTML dashboard في وحدة واحدة. هذا مناسب للتجربة المحلية لكنه يصعب توسيعه إلى projects وjobs وstorage وauth وmobile contracts دون فصل تدريجي.

## المسارات الحالية

| المجال | المسارات الحالية | الملاحظات |
|---|---|---|
| Health | `GET /health`, `GET /` | health يعرض وضع المزود وحالة scheduler وعدد jobs النشطة |
| Source | `POST /v1/sources/inspect`, `POST /v1/sources/download`, `GET /v1/sources/jobs/{id}`, media route | يعتمد على yt-dlp وملفات محلية |
| Processing | `POST /v1/processing/jobs`, `GET /v1/processing/jobs/{id}`, media route | ينشئ worker عبر `asyncio.to_thread` ويستدعي pipeline |
| Analytics | `POST /v1/analytics/snapshots`, `GET /v1/analytics/summary` | تخزن snapshots في SQLite |
| Social accounts | accounts CRUD جزئي وسياسات النشر | mock account متاح فقط في `PROVIDER_MODE=mock` |
| Social publishing | capabilities، OAuth mock، schedule، publish، cancel | live adapters غير مكتملة؛ لا ينبغي اعتبار mock نشرًا حقيقيًا |

## الأمن المثبت

`validate_public_source` يقبل HTTP/HTTPS فقط ويرفض localhost وloopback وprivate/link-local/reserved/multicast addresses بعد DNS resolution. media routes تتحقق من أن المسار النهائي داخل base directory، وأن الملف موجود وامتداده MP4.

الـgateway يدعم `GATEWAY_TOKEN` عبر Bearer token، لكن غياب المتغير يعني anonymous access. هذا مناسب للتطوير المحلي فقط ويجب توثيقه بوضوح عند ربط تطبيق هاتف خارج الجهاز. قاعدة SQLite الحالية تخزن access/refresh tokens في أعمدة نصية؛ قبل الإنتاج يجب استخدام secret storage أو تشفير/فصل بيانات الاعتماد، وعدم تسجيلها في responses أو logs.

## workers والموثوقية

المعالجة والنشر يعملان داخل asyncio tasks وthreads محلية. عند إعادة تشغيل gateway تُحوّل jobs ذات الحالة queued/running إلى failed برسالة واضحة، ولا يوجد queue موزع أو retry durable خارج SQLite. هذه ليست fake success: المسارات تعيد job queued ثم تُحدّث الحالة الفعلية، لكن process قد يفقد المهمة عند توقف العملية.

## التوافق المطلوب

يستخدم Desktop العميل الحالي مسارات `/v1/processing/jobs` و`/v1/sources/jobs` ويعتمد حالات `queued|running|done|failed`. لذلك يجب إضافة `/api/v1` كطبقة توافقية دون حذف المسارات القديمة، وإضافة project/job aliases تدريجيًا دون تغيير response الحالي.

## الإصلاحات ذات الأولوية

أولوية P0 هي تثبيت اختبار gateway من جذر المستودع، تسجيل marker pipeline، إضافة project abstraction خفيفة مرتبطة بـprocessing job، وإتاحة health API versioned. أولوية P1 هي فصل schemas/services/routes إلى modules مع إبقاء `main.py` compatibility entry point. أولوية لاحقة هي queue durable وauth متعدد المستخدمين وobject storage؛ لا ينبغي ادعاء اكتمالها ضمن إصلاح أولي محلي.
