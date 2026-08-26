from pathlib import Path
from types import SimpleNamespace

import pytest
from unittest.mock import patch

from publikclip_pipeline.asr.stage import AsrStage
from publikclip_pipeline.jobs.queue import StageError


def test_missing_asr_runtime_is_classified_as_model_unavailable(tmp_path: Path):
    audio = tmp_path / "audio16k.wav"
    audio.write_bytes(b"placeholder")
    context = SimpleNamespace(
        prior={"ingest": {"audio_path": str(audio)}},
        job_dir=tmp_path,
        emit=lambda *_args, **_kwargs: None,
    )

    with patch("publikclip_pipeline.asr.stage._audio_is_nonempty", return_value=True):
        with pytest.raises(StageError) as error:
            AsrStage().run(context)

    assert error.value.code == "ASR_MODEL_UNAVAILABLE"
    assert "Speech model is unavailable" in error.value.safe_message
