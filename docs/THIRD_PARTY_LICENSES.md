# Third-Party Licenses and Provenance

**Scope:** PublikClip/ISM Android client, private Gateway, Python Engine, and the attached `autoclip-main.zip` reference.

## Policy

لا تُدمج شفرة أو نماذج أو خطوط من مشروع مرجعي دون تحديد الترخيص وحقوق الإسناد وتوافق dependency. لا تُحفظ مفاتيح API أو ملفات نماذج runtime أو build artifacts في Git. عند عدم وضوح الترخيص، يكون القرار `IGNORE_REFERENCE` أو إعادة تنفيذ مستقلة بعد مراجعة قانونية.

## Reference archive

| المادة | المصدر | الترخيص الظاهر | حالة الدمج |
|---|---|---|---|
| تطبيق Autoclip Python/frontend/tests | `autoclip-main.zip`، ملف `LICENSE` | MIT، Copyright 2026 Jad Ghazi | لم تُنسخ ملفات إلى المستودع الأساسي |
| Docker/compose في المرجع | `autoclip-main/docker/` | يخضع لملف LICENSE العام ما لم يظهر notice مختلف | لم يُدمج؛ يحتاج مراجعة تشغيلية مستقلة |
| frontend dependencies | `autoclip-main/frontend/package.json` وlockfile | تراخيص upstream لكل dependency | لم تُنقل إلى مشروع Android أو app |
| Python dependencies | `autoclip-main/pyproject.toml` | تراخيص upstream لكل dependency | لم تُضاف بسبب اختلاف runtime والعقود |

## Existing project provenance

السجل التفصيلي للشفرة المقتبسة أو المكيّفة والنماذج والخطوط موجود في [`../VENDORED-LICENSES.md`](../VENDORED-LICENSES.md). وهو المرجع الأساسي لأي notice إضافي، ولا يُعاد نسخ المعلومات هنا على نحو قد يسبب drift.

| الفئة | أمثلة مسجلة | الملاحظة |
|---|---|---|
| Code adaptations | clip-forge، clippyme، openshorts، whisperX، 3D-Speaker، autoclip | موثقة مع upstream/license/path في `VENDORED-LICENSES.md` |
| Model weights | Whisper، wav2vec2، Silero، CAM++، PANNs، UltraFace، LR-ASD وغيرها | تُنزّل runtime ولا تُوزع من هذا المستودع وفق السجل الحالي |
| Fonts | Anton، Inter، Public Sans، Martian Mono، Archivo Black | SIL OFL 1.1 وفق السجل الموجود في pipeline |
| Android libraries | Compose، Room، WorkManager، Media3، ML Kit | يجب الرجوع إلى Gradle resolution وnotices عند توزيع APK |
| Python libraries | FastAPI، SQLite bindings، FFmpeg integrations، ML stack | لا يُفترض ترخيص موحّد؛ يجب الاحتفاظ بملفات lock وmetadata |

## Deliberately excluded material

لم تُنقل من المرجع واجهة `frontend/` كاملة، أو مجلد `autoclip/`، أو build caches، أو ملفات models، أو `.env`، أو مفاتيح، أو ملفات قاعدة بيانات، أو أي code بلا license واضح. كما لا تُستخدم أجزاء المرجع ذات شروط cloud/proprietary إن ظهرت كاعتماد منفصل.

## Compliance checklist قبل التوزيع

| الفحص | الحالة |
|---|---|
| وجود LICENSE للمشروع الأساسي | موجود: `LICENSE` — AGPL-3.0-or-later |
| provenance للشفرة المكيّفة | موجود: `VENDORED-LICENSES.md` |
| عدم نسخ المرجع كاملًا | متحقق في مرحلة التدقيق |
| فحص secrets و`.env` وkeys | يجب إعادة تشغيله في CI/release |
| Android dependency notices | مطلوب قبل توزيع APK النهائي |
| نماذج runtime وterms | مطلوب توثيقه عند تثبيت كل model version |
| التحقق من compatibilty بين AGPL وdependencies | مطلوب لأي dependency جديدة |

> هذا السجل توثيق هندسي وليس رأيًا قانونيًا. التوزيع العام أو إعادة الترخيص يحتاج مراجعة قانونية مناسبة، خصوصًا عند دمج AGPL مع مكونات خارجية أو نشر خادم معدل عبر الشبكة.
