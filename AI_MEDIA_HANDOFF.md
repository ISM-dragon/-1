# AI + Media Runtime Handoff

**Branch:** `agent/ai-media`  
**Owner:** Agent 03 — AI + MEDIA RUNTIME  
**Status:** Implementation complete; commit is created after final verification.

## Baseline documents

الملفات المطلوبة حرفيًا `docs/ARCHITECTURE.md` و`docs/AUDIT.md` و`docs/CONTRACTS.md` غير موجودة في `main` وقت إنشاء الفرع. تمت مراجعة البدائل canonical الموجودة: `docs/MASTER-ARCHITECTURE.md` و`docs/FINAL-PRODUCTION-AUDIT.md` و`docs/API-CONTRACT.md` و`docs/MEDIA_PIPELINE.md`، مع إبقاء هذا الفرق ظاهرًا في التسليم.

## Delivered

أضيفت طبقة runtime مستقلة داخل `pipeline/publikclip_pipeline/runtime/`. يوفر `ModelManager` دورة حياة موحدة للنماذج: `check` و`download` و`resume` و`verify` و`load` و`unload` و`delete` و`status`. كما يوفّر `MediaManager` واجهة FFmpeg موحدة لـ`probe` و`validate` و`extract_audio` و`extract_frames` و`transcode` و`render` و`cleanup`.

تم توسيع `ModelSpec` وإعادة بناء السجل ليشمل نماذج ASR والمحاذاة وVAD وdiarization وaudio events وSER وface وactive-speaker. لكل سجل version وapproximate size وchecksum field وsource وlocal path وhardware requirements. النماذج التي يديرها WhisperX أو SpeechBrain عبر upstream cache معلّمة `managed=false` كي لا يظهر وجود URL كأنه تنزيل جاهز.

أضيفت قراءة موارد CPU وGPU وRAM وVRAM وdisk مع profiles `cpu-small` و`cpu-standard` و`gpu-standard` و`gpu-large`. فشل GPU discovery لا يفشل runtime؛ يتحول إلى CPU fallback ظاهر في الحالة.

## Stable errors

تظهر أخطاء النماذج بصيغ `MODEL_MISSING` و`MODEL_CORRUPTED` و`MODEL_DOWNLOAD_FAILED`. وتظهر أخطاء media بصيغ `FFMPEG_MISSING` و`FFMPEG_INVALID` و`MEDIA_INVALID`. كما يحافظ stage runner على هذه الأكواد في job state بدل تحويلها إلى `ENGINE_FAILED` عند مرور استثناء مصنف.

## Files

| الملف | الغرض |
|---|---|
| `pipeline/publikclip_pipeline/runtime/model_manager.py` | ModelManager وModelStatus وModelRuntimeError. |
| `pipeline/publikclip_pipeline/runtime/media_manager.py` | MediaManager وMediaProbe وMediaRuntimeError. |
| `pipeline/publikclip_pipeline/runtime/hardware.py` | قياس الموارد وprofiles. |
| `pipeline/publikclip_pipeline/models/registry.py` | ModelSpec الموسع وbackward-compatible ensure مع stable model errors. |
| `pipeline/publikclip_pipeline/models/specs.py` | سجل النماذج الكامل. |
| `pipeline/publikclip_pipeline/jobs/queue.py` | حفظ أكواد AI/media المعروفة في job failures. |
| `pipeline/tests/test_ai_media_runtime.py` | اختبارات runtime المطلوبة. |
| `docs/AI_RUNTIME.md` | جرد النماذج وعقد ModelManager. |
| `docs/MEDIA_RUNTIME.md` | عقد MediaManager وسلوك FFmpeg. |

## Verification

تم تشغيل:

```bash
PYTHONPATH=pipeline python3 -m py_compile \
  pipeline/publikclip_pipeline/runtime/*.py \
  pipeline/publikclip_pipeline/models/*.py \
  pipeline/publikclip_pipeline/jobs/queue.py

PYTHONPATH=pipeline pytest -q pipeline/tests/test_ai_media_runtime.py
PYTHONPATH=pipeline pytest -q \
  pipeline/tests/test_ai_media_runtime.py \
  pipeline/tests/test_stability_regressions.py \
  pipeline/tests/test_engine.py
```

النتيجة: **9 اختبارات runtime ناجحة**، و**33 اختبارًا مشتركًا ناجحًا**. تغطي الاختبارات فيديو صالحًا، فيديو مكسورًا، فيديو بلا audio، فيديو أكبر وأعلى دقة، missing/failing FFmpeg، missing/corrupt model، load/unload/delete، وresume عبر HTTP Range مع checksum.

لا تتضمن هذه الاختبارات تنزيل checkpoints حقيقية أو inference كاملًا أو Android E2E؛ لذلك لا يوجد ادعاء زائف بجاهزية provider أو APK. لم تُعدّل ملفات Android UI، ولم تُعدّل Backend، ولم تُعد كتابة scoring algorithms.

## Follow-up integration

ينبغي لأي stage جديدة تحتاج lifecycle صريحًا أن تستخدم `ModelManager` بدل تنزيلات خاصة بها. وعند استخدام upstream loader لـWhisperX أو SpeechBrain، يجب ملء الكاش أولًا ثم استدعاء `verify`/`check` قبل inference. أما renderer الحالي فيبقى متوافقًا؛ ويمكن تمرير خياراته إلى `MediaManager.render` دون نقل قرارات camera أو scoring إلى manager.

## Commit

سيظهر commit النهائي في الفرع الحالي بعد اجتياز full regression suite وفحص `git diff --check`.
