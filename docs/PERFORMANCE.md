# Performance Baseline

**الحالة:** القياس جزء من release evidence؛ لا توجد أرقام production جديدة في هذه الجلسة لأن نماذج المعالجة وجهاز Android الفعلي غير متاحين.

## المقاييس المطلوبة

| المكوّن | المقياس | طريقة القياس | معيار المقارنة |
|---|---|---|---|
| End-to-end | total processing time | timestamps من create إلى artifact verified | حسب مدة/دقة المصدر وmode. |
| ASR | ASR wall time وRTF | stage timestamps ومدة الفيديو | baseline لكل model/version. |
| Diarization | stage wall time وRAM peak | process metrics | مقارنة قبل/بعد model cache. |
| Scoring | زمن signals وLLM | provider latency وfallback count | لا تضحى correctness لخفض latency. |
| Camera | تحليل frames وcrop smoothing | stage timing وعدد frames | ثبات framing وغياب jitter. |
| Render | render time وoutput size | FFmpeg logs وartifact metadata | bitrate/resolution/preset ثابت. |
| Server | CPU/RAM/VRAM/disk | host metrics لكل job | منع OOM وdisk exhaustion. |
| Android | upload throughput وbattery وcache size | WorkManager/device logs | لا ينهار job عند process death أو network loss. |

## قواعد benchmark

يُستخدم source ثابت ومجموعة إعدادات ثابتة، ويُسجل commit وmodel versions وFFmpeg version وhardware. تُقارن النتيجة مع baseline لا مع انطباع بصري. أي optimization يجب أن تثبت فائدتها في الزمن أو الذاكرة أو الاستقرار، مع إبقاء output correctness وcaption timing وcamera framing ضمن regression checks.

### المراجع

[1]: ../evidence/environment.md "Environment evidence"
[2]: ../docs/FINAL_ACCEPTANCE.md "Current acceptance limitations"
[3]: ../pipeline/publikclip_pipeline/ "Pipeline runtime"
[4]: ../gateway/processing_service.py "Stage execution bridge"

## References

[1]: ../evidence/environment.md "Environment evidence"
[2]: FINAL_ACCEPTANCE.md "Current acceptance limitations"
[3]: ../pipeline/publikclip_pipeline/ "Pipeline runtime"
[4]: ../gateway/processing_service.py "Stage execution bridge"
