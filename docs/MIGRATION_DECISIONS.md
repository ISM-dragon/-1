# سجل قرارات الترحيل

**الإصدار:** 1.0 — 26 أغسطس 2026

## قرارات حاكمة

| القرار | النطاق | الحكم | الدليل |
|---|---|---|---|
| KEEP_CURRENT | `pipeline/publikclip_pipeline/engine` والمراحل | تبقى مصدر الحقيقة للتحليل والرندر | وجود contract واختبارات checkpoints والمراحل. |
| KEEP_CURRENT | `gateway/` كـcontrol plane | يعتمد عليه مسار Android الحالي | يوفر auth، uploads، jobs، workers، diagnostics، artifacts. |
| IGNORE_REFERENCE | Flask monolith | لا يُنقل ولا يُشغّل داخل APK | لا يحقق boundary المطلوب ويضيف stack ثانٍ. |
| ADD_REFERENCE | sequential splitting كخيار مستقبلي | يُعاد تنفيذه داخل pipeline بعد benchmark | فكرة مفيدة، لكن لا يوجد دليل أداء على فيديوهات المشروع. |
| IMPROVE_CURRENT | Android upload | إضافة resume/checksum عند تثبيت contract | one-shot الحالي معرض لإعادة رفع ملفات كبيرة. |
| IMPROVE_CURRENT | error mapping | توحيد error codes وretry policy | ضروري لتمييز فشل الوسائط عن الشبكة والنماذج. |
| KEEP_CURRENT | WorkManager/Room | يبقى lifecycle المحلي | يلائم process death وnetwork constraints. |
| MANUAL_REVIEW | social publishing وpattern training | خارج P0/P1 | لا يسبق مسار clip generation الشخصي. |
| DO_NOT_REPLACE | scoring/camera/captions | لا استبدال لمجرد اختلاف المرجع | التنفيذ الأساسي أعمق ويملك models وtests. |

## التقييم الموزون

استخدمت الدرجات على مقياس من 1 إلى 10 مع الأوزان: Android compatibility 20%، correctness 20%، stability 15%، performance 15%، maintainability 10%، feature completeness 10%، integration cost 5%، dependency cost 5%. لا تمثل النتيجة benchmark ميدانيًا؛ هي قرار مخاطر migration.

| الخيار | النتيجة التقريبية | النتيجة التشغيلية |
|---|---:|---|
| Native Android + Gateway + current engine | 8.3/10 | المسار المعتمد. |
| Reference Flask + local Whisper | 5.0/10 | غير مناسب كمسار Android. |
| Copy reference scoring into pipeline | 5.9/10 | مخاطرة correctness وlicense؛ مرفوض. |
| Reimplement selected concepts independently | 7.1/10 | مسموح تدريجيًا بعد tests. |

## قرارات الحدود

لا يستورد Android أي Python أو uv أو pip أو desktop FFmpeg. لا يرسل Gateway مفاتيح Gemini إلى العميل. لا يستورد Gateway وحدات pipeline الداخلية عشوائيًا؛ يستخدم process/adapter boundary. لا يُسمح بتشغيل `PROVIDER_MODE=mock` كإعداد إنتاج، ولا الوصول البعيد دون token وHTTPS/VPN.

## قرارات البيانات

المصدر المحلي هو bytes مرفوعة، أما URL العام فهو source منفصل يتحقق من SSRF. checkpoints والـartifacts تعيش على الخادم، وحالة Android المحلية تحفظ remote job ID وprogress وdownloaded paths. لا يعاد transcription عندما تكون word timestamps صالحة.

## القرارات المؤجلة

قرار دمج `backend/` مع `gateway/` مؤجل؛ لا تُضاف features جديدة إلى الاثنين بالتوازي. قرار اعتماد `remote/*` UI بدل `OpusRepository` legacy UI مؤجل إلى ما بعد توحيد client contract، لأن وجود مسارين غير موصولين يزيد مخاطرة release. قرار دعم native Whisper أو Media3 processing محليًا مرفوض حاليًا ما لم يثبت benchmark أفضل وmemory profile مناسب.

## المراجع

[1]: ../docs/ARCHITECTURE.md "Current architecture and component ownership"
[2]: ../docs/REFERENCE_COMPARISON.md "Reference comparison"
[3]: ../docs/REFERENCE_MIGRATION_PLAN.md "Staged migration plan"
[4]: ../gateway/main.py "Gateway capabilities and routes"
[5]: ../android/app/src/main/java/com/example/data/worker/VideoProcessingWorker.kt "Current Android execution path"
[6]: ../pipeline/publikclip_pipeline/engine/contracts.py "Engine contract"

## References

جميع القرارات مبنية على الشيفرة والوثائق المحلية المشار إليها أعلاه.
