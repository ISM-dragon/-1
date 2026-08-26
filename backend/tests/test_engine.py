from __future__ import annotations

import threading
from pathlib import Path

from backend.engine import EngineEvent, SubprocessPublikclipEngine


def test_jsonl_event_mapping(tmp_path: Path):
    events: list[EngineEvent] = []
    adapter = SubprocessPublikclipEngine(tmp_path)
    assert adapter._emit_line('{"event":"job","job_id":"eng-1"}', events.append) is None
    assert adapter._emit_line('{"event":"progress","stage":"render","fraction":1.4,"message":"done"}', events.append) is None
    result = adapter._emit_line('{"event":"result","ok":true,"job_id":"eng-1"}', events.append)
    assert result == {"event": "result", "ok": True, "job_id": "eng-1"}
    assert [event.kind for event in events] == ["job", "progress", "result"]
    assert events[0].engine_job_id == "eng-1"
    assert events[1].progress == 1.0


def test_unavailable_pipeline_is_reported(tmp_path: Path):
    adapter = SubprocessPublikclipEngine(tmp_path)
    available, message = adapter.available()
    assert available is False
    assert "not available" in message
