# Performance and Resource Baseline

## قاعدة القياس

لا تُقبل optimization كبيرة دون قياس قبل/بعد على نفس الفيديو ونفس إعدادات النموذج. يجب تسجيل الزمن الكلي وزمن ASR وdiarization وscoring وrender، إضافة إلى RAM وCPU وGPU ومساحة القرص وحجم artifacts.

## القياسات المطلوبة

| القياس | أين يقاس | ملاحظة |
|---|---|---|
| total processing time | Gateway job history | من create إلى artifact ready |
| ASR time | Engine stage telemetry | يتأثر بالنموذج والـdevice |
| diarization time | Engine stage telemetry | قد يكون CPU/RAM intensive |
| scoring time | scoring stage | يسجل LLM latency/fallback |
| render time | FFmpeg/render stage | يسجل preset والدقة وfps |
| RAM/CPU/GPU | server runtime | لا يُستنتج من Android UI |
| disk | upload/temp/output directories | يجب منع exhaustion مبكرًا |
| upload/download time | Android/Gateway logs | يميز network bottleneck عن engine |

## التشغيل المستهدف

المسار المرجعي هو single private Gateway instance مع durable SQLite bookkeeping وworker queue، لأن التوسع الأفقي ليس ضمن النطاق الحالي. يجب ألا يُفترض وجود GPU أو model cache دافئ. first-run model downloads وFFmpeg availability جزء من readiness وليس من benchmark الصامت.

## تحسينات ممنوعة بلا دليل

لا تُستبدل camera أو ASR أو diarization، ولا تُزاد parallelism، ولا تُنقل النماذج إلى الهاتف، ولا تُضاف dependency native ضخمة اعتمادًا على انطباع code review. أي تغيير يجب أن يحافظ على accuracy/correctness وcheckpoint compatibility، ويضيف regression evidence.

## Acceptance للقياس

لا توجد أرقام أداء عامة معتمدة حاليًا؛ الجهاز والـmodel/runtime غير مثبتين في هذه البيئة. المطلوب قبل release هو baseline محفوظ في `evidence/` لثلاث فئات على الأقل: normal، long، large، مع نتيجة clip/artifact وموارد التشغيل.
