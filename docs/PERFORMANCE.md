# خطة وأساس قياس الأداء

## المقاييس المطلوبة

يُقاس على fixture ثابت وعلى host محدد: total processing time، ASR time، diarization time، scoring time، render time، peak RAM، CPU، GPU/VRAM عند وجودها، disk usage، model load time، وعدد مرات reload.

## نقاط القياس

| النقطة | طريقة القياس | معيار المقارنة |
|---|---|---|
| ingest | timestamps في progress/checkpoint | أقل زمن مع validation محفوظ |
| ASR | stage duration وmodel load | لا reload لكل job |
| diarization/events | stage duration وRAM | لا تتجاوز limits المضبوطة |
| scoring | local/LLM timing وfallback count | failure لا يسقط job |
| render | per-clip وtotal render duration | artifact صالح وCFR مناسب |
| Android upload | bytes/second وretries | استعادة بدون فقد state |
| Android download | bytes/second وdisk free | لا ملف ناقص بدون error |

## baseline الحالي

لا توجد في بيئة التدقيق الحالية أدوات pytest أو Android SDK، لذلك لم يُسجل benchmark جديد. توجد سجلات سابقة في `evidence/` داخل المستودع، لكنها تُعامل كأدلة تاريخية مرتبطة ببيئتها ولا تُنسب إلى تشغيل هذا التدقيق. قبل النشر يجب إعادة القياس على host يملك Python runtime، FFmpeg/ffprobe، النماذج، وAndroid SDK.

## ضوابط optimization

لا تُنقل Whisper إلى Android لمجرد تقليل زمن الشبكة. لا تُفعل parallelism أو model caching جديد دون قياس peak RAM وdisk وcorrectness. كل تحسين يرفق قبل/بعد، fixture، commit، وقرار rollback.

## المراجع

[1]: ../pipeline/publikclip_pipeline/engine/pipeline.py "Pipeline stage execution"
[2]: ../pipeline/publikclip_pipeline/models/registry.py "Model loading and registry"
[3]: ../gateway/worker_queue.py "Worker resource and disk controls"
[4]: ../evidence/environment.md "Recorded environment evidence"
[5]: ../docs/TEST_MATRIX.md "QA matrix"

## References

المراجع محلية في المستودع، والنتائج المستقبلية يجب أن تحفظ كأدلة قابلة لإعادة الفحص.
