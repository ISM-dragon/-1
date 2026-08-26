# Baseline — ISM Social / Publikclip

**تاريخ القياس:** 2026-08-19

## حالة المستودع

| العنصر | القيمة |
|---|---|
| المستودع | `https://github.com/ISM-dragon/-1` |
| الفرع الافتراضي | `main` |
| commit الأساس | `d0fbaa5` (`release v0.9.2 full audit fixes`) |
| الترخيص المعلن | AGPL-3.0-or-later؛ لا يجوز إعادة تسميته أو إزالته |
| نظام التشغيل | Ubuntu 24.04 |
| Python | 3.12.3 |
| Node.js | v22.13.0 |
| npm | 10.9.2 |
| uv | 0.12.1 |
| Rust/Cargo | غير متاحان في بيئة القياس الحالية |
| FFmpeg | 6.1.1 |

## بنية المشروع الحالية

المشروع ليس greenfield. يتكون من تطبيق Desktop مبني على Tauri/React داخل `app/`، وطبقة FastAPI أحادية الملف تقريبًا داخل `gateway/main.py`، ومحرك معالجة Python داخل `pipeline/publikclip_pipeline/`. يحتوي pipeline على مراحل ingest وASR وdiarization وevents وcandidates وscoring وcamera وcaptions وrender، إضافة إلى jobs وmodels وmusic وinsights.

## أوامر التحقق

| المكوّن | الأمر | النتيجة | الملاحظات |
|---|---|---|---|
| Desktop app | `cd app && npm ci` | PASS | تم تثبيت 76 حزمة، ولم يظهر audit vulnerability |
| Desktop app | `cd app && npm run build` | PASS | نجح TypeScript وVite وأنتج `app/dist/` |
| Gateway | `cd gateway && python3 -m pytest -q` | FAIL | pytest غير مثبت في Python النظام |
| Gateway | `uv run --with ... pytest -q gateway/tests` | FAIL | اختبار الاستيراد `from gateway.main` يفشل لأن `gateway` ليس حزمة Python قابلة للاستيراد من جذر الاختبار |
| Pipeline | `cd pipeline && python3 -m pytest -q` | FAIL | pytest غير مثبت في Python النظام |
| Pipeline | `cd pipeline && uv sync && uv run pytest -q` | PASS | 91 اختبارًا ناجحًا، مع تحذير واحد بسبب marker باسم `slow` غير مسجل |
| Tauri native build | `npx tauri build` | NOT RUN | Rust/Cargo غير متاحين في بيئة القياس الحالية |

## نتائج مهمة قبل التعديل

نجح بناء واجهة Desktop، كما نجحت اختبارات pipeline بعد إنشاء بيئة uv الخاصة بالمشروع. فشل gateway ليس دليلًا على فشل منطق الحماية نفسه؛ الفشل الحالي في مرحلة جمع الاختبارات بسبب بنية الاستيراد. ينبغي إصلاح قابلية تشغيل الاختبارات من جذر المستودع دون تغيير السلوك الخارجي للـgateway.

## CI الحالية

يحتوي المستودع على workflow باسم `.github/workflows/windows.yml` يشغّل `uv sync` واختبارات pipeline، ثم `npm ci` وبناء Tauri على Windows، وبعد ذلك يثبت NSIS installer ويشغّل التطبيق للتأكد من بقائه حيًا. لا يوجد في baseline الحالي workflow مستقل لبناء Android Native أو React Native.

## حدود baseline

تم إنشاء هذه الوثيقة بعد الفحص والتشغيل وقبل تعديل منطق التطبيق. تثبيت الحزم داخل بيئات `npm` و`uv` لا يُعد تعديلًا على source code، كما أن ملفات build/cache الناتجة لا ينبغي رفعها إلى Git.
