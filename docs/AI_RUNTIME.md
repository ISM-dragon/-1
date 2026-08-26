# AI Runtime

## مسؤولية runtime

تشغيل ASR، diarization، event models، face/active-speaker analysis، وLLM scoring يتم على Private Backend. Android لا يحتوي Python أو WhisperX أو PyTorch أو نماذج server أو مفاتيح Gemini/Ollama.

## دورة النموذج

يجب على runtime معرفة اسم النموذج، النسخة، الحجم، checksum، المصدر، المسار المحلي، حالة التثبيت، والصحة. التحميل والاستئناف والتحقق والإلغاء والحذف عمليات خادمية، ولا تُنفذ أثناء APK build.

## fallback

إذا كان LLM اختياريًا غير متاح، لا يتحول job إلى crash إذا كان scoring المحلي أو heuristic fallback صالحًا. تُسجل capability diagnosis في Gateway ويُعاد error code واضح فقط عندما لا يوجد مسار بديل. لا يُعاد transcription لمجرد إعادة رندر clip.

## العزل والأداء

تُحمّل النماذج عند الحاجة وتُعاد الاستفادة منها بين jobs وفق حدود الذاكرة. يجب قياس زمن ASR وdiarization وscoring وRAM/VRAM قبل أي تغيير كبير. المخرجات الوسيطة تحفظ في job directory الخاص ولا تُكشف للعميل إلا عبر artifact آمن.
