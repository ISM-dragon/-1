from __future__ import annotations

import json

from publikclip_pipeline.scoring.providers import ProviderProfile, _record_usage, _usage_counts


def test_usage_counts_preserves_actual_provider_tokens():
    usage = _usage_counts({"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}, "ignored", "ignored")
    assert usage == {"input_tokens": 12, "output_tokens": 8, "total_tokens": 20, "usage_source": "actual"}


def test_usage_counts_marks_estimates_when_provider_omits_usage():
    usage = _usage_counts({}, "a" * 40, "b" * 20)
    assert usage["usage_source"] == "estimated"
    assert usage["input_tokens"] == 10
    assert usage["output_tokens"] == 5
    assert usage["total_tokens"] == 15


def test_record_usage_writes_non_secret_jsonl(monkeypatch, tmp_path):
    import publikclip_pipeline.scoring.providers as providers

    monkeypatch.setattr(providers.config, "home_dir", lambda: tmp_path)
    profile = ProviderProfile(id="demo", name="Demo", type="openai_compatible", default_model="model", credential_ref="secret-ref", input_cost_per_million=1.0, output_cost_per_million=2.0)
    usage = _record_usage(profile, "model", 25, {"prompt_tokens": 100, "completion_tokens": 50}, "prompt", "output")
    assert usage["usage_source"] == "actual"
    line = (tmp_path / "ai_usage.jsonl").read_text().strip()
    payload = json.loads(line)
    assert payload["provider"] == "demo"
    assert payload["total_tokens"] == 150
    assert "secret-ref" not in line
