# Performance Baseline وقياس الأداء

## المبدأ

لا يُستبدل stage أو model أو encoder لمجرد اختلاف المشروع المرجعي. يجب أولًا تشغيل dataset ثابت وتسجيل baseline قابل للمقارنة، ثم قياس التغيير نفسه على نفس البيئة ونفس الإعدادات.

## المقاييس

| المقياس | طريقة التسجيل | وحدة العرض |
|---|---|---|
| الزمن الكلي | من قبول job حتى artifact النهائي | ثانية ودقائق |
| ASR | زمن مرحلة transcription | ثانية |
| diarization | زمن clustering/speaker stage | ثانية |
| scoring | زمن LLM وfallback | ثانية |
| camera | زمن تحليل وإعادة التأطير | ثانية |
| render | زمن FFmpeg لكل clip | ثانية |
| CPU/RAM/VRAM | عينات أثناء job | متوسط وذروة |
| disk | قبل/بعد intermediate وoutputs | MB/GB |
| reliability | نسبة jobs المكتملة/المستأنفة/الفاشلة | % وعدد |

## معايير المقارنة

يُقبل optimization إذا حسّن الزمن أو الذاكرة دون خفض سلامة artifact أو جودة مقاطع مقبولة، أو إذا عالج فشلًا تشغيليًا واضحًا. عند فرق صغير، يبقى التنفيذ الحالي لتقليل migration risk. يجب حفظ logs والنسخة والإعدادات والعتاد مع كل benchmark.

## حدود البيئة

ASR وdiarization وvision وFFmpeg هي server-side heavy work. Android performance يقاس منفصلًا على upload، polling، استهلاك البطارية/الشبكة، استجابة UI، واستعادة worker بعد process death. لا تُخلط هذه الأرقام مع زمن pipeline الخادمي.
