# AI Runtime

**الحالة:** منفّذ على الفرع `agent/ai-media`  
**النطاق:** دورة حياة نماذج التحليل المحلية والكاشات الخارجية، من دون تعديل Android UI أو Backend أو خوارزميات scoring.

## الملخص التنفيذي

أصبح للمشروع سجل موحّد للنماذج وواجهة `ModelManager` تغطي `check` و`download` و`resume` و`verify` و`load` و`unload` و`delete` و`status`. يحتفظ المدير ببيانات الإصدار والحجم المتوقع وchecksum والمصدر والمسار المحلي والاحتياجات العتادية، ويميّز بوضوح بين النموذج الجاهز، والمفقود، والجزئي، والتالف، والمحمل في الذاكرة. النماذج التي تُدار بواسطة WhisperX أو SpeechBrain عبر Hugging Face لا تُعامل كتنزيلات ملفية وهمية؛ تظهر في الحالة ككاش خارجي، ولا تصبح جاهزة إلا عندما يكون المسار المحلي موجودًا وقابلًا للتحقق.

> لا تعلن البنية الجديدة أن وجود رابط التنزيل أو manifest يكفي لإثبات الجاهزية. الجاهزية تعني وجود artifact محلي قابل للتحقق، أو كاش خارجي مملوء وقابل للقراءة.

## جرد النماذج

| المكوّن | المفتاح | الإصدار المسجل | الحجم التقريبي | checksum | المصدر | المسار المحلي الافتراضي | الجهاز المقترح |
|---|---|---:|---:|---|---|---|---|
| ASR | `whisperx-asr/large-v3-turbo` | `large-v3-turbo` | نحو 1600 MB | غير مثبت حاليًا | [WhisperX/faster-whisper Hugging Face cache][1] | `PUBLIKCLIP_HOME/models/hf/whisper-large-v3-turbo` | CPU أو CUDA، RAM 8 GB كحد إرشادي، وVRAM 6 GB موصى بها |
| VAD | `silero-vad/silero-vad` | نسخة WhisperX المضمّنة | نحو 2 MB | غير مثبت حاليًا | [WhisperX/PyTorch cache][2] | `PUBLIKCLIP_HOME/models/hf/silero-vad` | CPU أو CUDA، RAM 2 GB |
| Word alignment | `whisperx-alignment/alignment-model` | language-dependent | نحو 500 MB | غير مثبت حاليًا | [WhisperX alignment loader][3] | `PUBLIKCLIP_HOME/models/hf/alignment` | CPU أو CUDA، RAM 4 GB |
| Diarization / speaker embedding | `campplus/campplus_cn_common.bin` | `campplus_cn_common` | نحو 28 MB | غير مثبت حاليًا | [funasr/campplus][4] | `PUBLIKCLIP_HOME/models/campplus/campplus_cn_common.bin` | CPU، RAM 2 GB |
| Audio tagging | `panns-cnn14-decisionlevelmax/Cnn14_DecisionLevelMax.pth` | `Cnn14 DecisionLevelMax mAP=0.385` | نحو 466 MB | غير مثبت حاليًا | [Zenodo checkpoint][5] | `PUBLIKCLIP_HOME/models/panns-cnn14-decisionlevelmax/Cnn14_DecisionLevelMax.pth` | CPU أو CUDA، RAM 4 GB، وVRAM 2 GB موصى بها |
| Laughter specialist | `laughter-jrgillick/best.pth.tar` | `jrgillick-resnet-with-augmentation` | نحو 10 MB | غير مثبت حاليًا | [jrgillick/laughter-detection][6] | `PUBLIKCLIP_HOME/models/laughter-jrgillick/best.pth.tar` | CPU، RAM 2 GB |
| Speech emotion / arousal | `speechbrain-ser-iemocap/speechbrain-emotion-recognition-wav2vec2-IEMOCAP` | repository revision | نحو 400 MB | غير مثبت حاليًا | [SpeechBrain IEMOCAP classifier][7] | `PUBLIKCLIP_HOME/models/ser` | CPU، RAM 4 GB |
| Face detection | `ultraface/ultraface-rfb-320.onnx` | `RFB-320 ONNX` | نحو 2 MB | غير مثبت حاليًا | [clip-forge UltraFace resource][8] | `PUBLIKCLIP_HOME/models/ultraface/ultraface-rfb-320.onnx` | CPU، RAM 1 GB |
| Active speaker frontend | `lr-asd/frontend.onnx` | `LR-ASD frontend ONNX` | نحو 3 MB | غير مثبت حاليًا | [clip-forge LR-ASD resource][9] | `PUBLIKCLIP_HOME/models/lr-asd/frontend.onnx` | CPU، RAM 1 GB |
| Active speaker backend | `lr-asd/backend.onnx` | `LR-ASD backend ONNX` | نحو 1 MB | غير مثبت حاليًا | [clip-forge LR-ASD resource][10] | `PUBLIKCLIP_HOME/models/lr-asd/backend.onnx` | CPU، RAM 1 GB |

الأحجام أعلاه تقديرية حيث كانت هذه هي metadata المتاحة في السجل السابق؛ أما `size_bytes` و`sha256` فيظهران كقيم `null` إلى أن تُثبت release artifacts رسميًا. لا يصف النظام نموذج Gemini أو Ollama كملف محلي؛ فهما مزودا scoring خارجيان ولهما lifecycle مختلف في عقد المزودين.[11]

## واجهة ModelManager

يُنشأ المدير عادةً من دون معاملات ليقرأ السجل الموحّد:

```python
from publikclip_pipeline.runtime.model_manager import ModelManager

manager = ModelManager()
state = manager.status()  # list[dict] لكل النماذج
state_one = manager.check("campplus/campplus_cn_common.bin")
```

تُرجع `check` و`status` سجلًا يتضمن `key` و`name` و`version` و`size_bytes` و`approx_mb` و`checksum` و`actual_checksum` و`checksum_pinned` و`source` و`local_path` و`hardware` و`state` و`available` و`loaded` و`resumable`. ويمكن استعمال المفتاح المختصر عندما يكون الاسم غير ملتبس، مثل `campplus`.

| العملية | السلوك |
|---|---|
| `check(key)` | قراءة حالة غير رافعة للاستثناء؛ مناسبة لصفحة readiness أو diagnostics. |
| `download(key, progress=...)` | تنزيل ملف مُدار مع كتابة إلى `.part` ثم تحقق ذري قبل الاستبدال. إذا وُجد artifact صالح يعاد مباشرة. |
| `resume(key, progress=...)` | استكمال `.part` عبر HTTP Range؛ وهو alias صريح لسياسة التنزيل القابلة للاستئناف. |
| `verify(key)` | فحص الوجود والحجم وSHA-256 عند توفره، ثم إعادة المسار أو رفع خطأ مصنف. |
| `load(key, loader=...)` | يتحقق أولًا ثم يستدعي loader اختياريًا ويحتفظ بالكائن في الذاكرة. من دون loader يعيد `Path` صالحًا. |
| `unload(key)` | يزيل الكائن من الذاكرة ويستدعي `close()` إذا كانت متاحة. |
| `delete(key)` | يفك التحميل ويحذف artifact وملف `.part`، أو مجلد الكاش الخارجي عند استعمال مفتاح مجلد. |
| `status(key=None)` | يعيد حالة عنصر واحد أو قائمة جميع العناصر. |

## الأخطاء المستقرة

يرفع المدير `ModelRuntimeError` مع `code` قابل للاستهلاك من الـpipeline والـGateway. كما يحافظ `registry.ensure` القديم على نفس النوع كي لا تتحول المسارات القائمة إلى أخطاء عامة.

| الكود | المعنى | الإجراء المقترح |
|---|---|---|
| `MODEL_MISSING` | المفتاح غير معروف أو artifact غير موجود أو ما زال جزئيًا. | اعرض حالة التنزيل أو شغّل `download`/`resume`. |
| `MODEL_CORRUPTED` | checksum أو الحجم غير صحيح، أو فشل loader في قراءة artifact. | احذف artifact وأعد التنزيل؛ لا تستخدم الوزن في inference. |
| `MODEL_DOWNLOAD_FAILED` | فشل HTTP أو النموذج يعتمد على كاش خارجي لم يُملأ. | افحص الشبكة/الكاش وأعد المحاولة مع إبقاء `.part` عند الإمكان. |

عند مرور استثناء ذي code معروف عبر `run_stages` يُحفظ code نفسه في سجل job بدل `ENGINE_FAILED`. لا يتم تسجيل الأسرار أو مسارات النماذج للعميل بوصفها اعتمادًا على هذا السلوك؛ واجهة الإنتاج ينبغي أن تختار الرسالة الآمنة المناسبة لعقد API.[12]

## موارد الجهاز والprofiles

توفر `inspect_resources()` قياسًا منخفض الاعتماد لـCPU وRAM وGPU/VRAM عبر `nvidia-smi` عند توفره، ومساحة القرص الحرة والكاملة عبر `shutil.disk_usage`. فشل أداة GPU لا يفشل التطبيق؛ يسجل النظام `gpu_available=false` ويستمر على CPU.

| profile | شروط اختيار تقريبية | الجهاز | سياسة التحميل |
|---|---|---|---|
| `cpu-small` | CPU محدود أو RAM غير كافية لمسار أكبر | CPU | نموذج واحد في الذاكرة، ASR `int8`. |
| `cpu-standard` | 8 threads وRAM 16 GB تقريبًا من دون GPU | CPU | نموذج واحد، إبقاء ASR وalignment متعاقبين لتقليل peak RSS. |
| `gpu-standard` | CUDA ظاهرة وVRAM لا تقل تقريبًا عن 4 GB | CUDA | نموذج واحد، ASR `float16` موصى به عند توافق runtime. |
| `gpu-large` | CUDA وVRAM تقارب 10 GB أو أكثر وRAM 16 GB | CUDA | حتى نموذجين وفق سياسة المضيف، مع إبقاء التحميل المرحلي هو الافتراضي. |

هذه profiles توصيات تشغيلية وليست ضمانًا لتوافق كل إصدار CUDA أو checkpoint. يظل ASR الحالي في stage يستخدم CPU و`int8` صراحة، بينما يصف manager الموارد دون فرض تغيير على stage أو scoring.[13]

## سياسة التحقق والتكامل

ملفات النماذج الملفية تُحفظ أولًا في `filename.part`، ويستمر التنزيل من حجم الملف الجزئي إذا دعم الخادم HTTP Range. إذا تجاهل الخادم Range يعاد البدء من الصفر. لا ينتقل الملف إلى الاسم النهائي قبل نجاح فحص الحجم المتوقع وchecksum المثبت، وعند غياب checksum يظل `checksum_pinned=false` في الحالة.

نماذج WhisperX وSilero وalignment وSER مميزة بـ`managed=false` لأنها تُملأ عبر upstream cache loader؛ وهذا يمنع تنزيل URL غير كافٍ أو إعلان readiness زائفًا. المطلوب عند دمج loader فعلي هو استدعاء `check` بعد اكتمال الكاش ثم `verify` قبل inference.

## مراجع

[1]: https://huggingface.co/Systran/faster-whisper-large-v3-turbo "Faster-Whisper large-v3-turbo model"
[2]: https://github.com/snakers4/silero-vad "Silero VAD"
[3]: https://github.com/m-bain/whisperX "WhisperX alignment loader"
[4]: https://huggingface.co/funasr/campplus "FunASR CAM++ checkpoint"
[5]: https://zenodo.org/record/3987831 "PANNs checkpoint on Zenodo"
[6]: https://github.com/jrgillick/laughter-detection "JRGillick laughter detection"
[7]: https://huggingface.co/speechbrain/emotion-recognition-wav2vec2-IEMOCAP "SpeechBrain emotion recognition"
[8]: https://github.com/JeremySNR/clip-forge/blob/main/resources/models/ultraface-rfb-320.onnx "UltraFace ONNX resource"
[9]: https://github.com/JeremySNR/clip-forge/blob/main/resources/models/lr-asd-frontend.onnx "LR-ASD frontend resource"
[10]: https://github.com/JeremySNR/clip-forge/blob/main/resources/models/lr-asd-backend.onnx "LR-ASD backend resource"
[11]: MASTER-ARCHITECTURE.md "ISM canonical architecture and AI routing lifecycle"
[12]: API-CONTRACT.md "ISM API error and readiness contract"
[13]: ../pipeline/publikclip_pipeline/asr/stage.py "Current WhisperX stage resource policy"
