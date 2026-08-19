# Android Processing Flow

```text
Studio.tsx
  └─ onRun(source, llm, captions)
       └─ App.tsx:startRun()
            ├─ loadProcessingGatewayConfig()
            ├─ api.gatewayHealth()
            ├─ api.processingCapabilities()
            ├─ api.geminiDiagnostic() عندما يكون llm=gemini
            ├─ api.processingStart()
            │    └─ POST /v1/processing/jobs
            └─ api.processingStatus() كل 1.5 ثانية
                 ├─ status=queued/running → تحديث stage/fraction/message
                 ├─ status=failed → عرض error الحقيقي
                 └─ status=done → results.render.outputs ثم Review
```

## Gateway worker flow

`gateway/main.py:start_processing` يتحقق من URL العام، يمنع المهمة المكررة، يطبق حد التزامن، ويحفظ صفاً في `processing_jobs`. ثم يشغّل `run_processing_job` في thread منفصل. هذا العامل يستدعي `pipeline_command()` ويضبط `PUBLIKCLIP_HOME` و`PYTHONPATH` و`PUBLIKCLIP_GEMINI_API_KEY` server-side، ثم يشغّل:

```text
uv run --project pipeline publikclip --jsonl run SOURCE --llm gemini --captions classic
```

كل JSONL event من stdout يحول إلى تحديث في `processing_jobs`. عند `event=result` يقرأ Gateway checkpoint files من مجلد المهمة، يحول كل مسار MP4 إلى `/v1/processing/jobs/{id}/media/{filename}`، ولا يعيد المسار المحلي الخام إلى Android.

## Pipeline stage order

الترتيب الفعلي في `pipeline/publikclip_pipeline/cli.py` هو:

```text
ingest → asr → diarize → events → candidates → score → camera → render
```

يقرأ scoring من `pipeline/publikclip_pipeline/scoring/llm.py`. وضع Gemini يقرأ `PUBLIKCLIP_GEMINI_API_KEY` أولاً، ثم `PUBLIKCLIP_HOME/secrets.json` كخيار بديل. لا يحتاج Android إلى معرفة هذه التفاصيل السرية.

## نتيجة حقيقية

عند نجاح المهمة يجب أن يحتوي `status.results.render.outputs` على ملفات MP4 فعلية. إذا كان `done` بلا outputs، يحول App الحالة إلى `PROCESSING COMPLETED — NO VALID CLIPS FOUND` بدلاً من فتح Review فارغ وكأنه نجاح.
