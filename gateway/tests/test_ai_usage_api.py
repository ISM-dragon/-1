from __future__ import annotations

import json


def test_provider_registry_preserves_pricing_and_hides_credentials(client, monkeypatch, tmp_path):
    import gateway.main as gateway
    monkeypatch.setattr(gateway, "PROCESSING_ROOT", tmp_path)
    response = client.post(
        "/v1/ai/providers",
        json={
            "id": "demo-provider",
            "name": "Demo Provider",
            "type": "openai_compatible",
            "credential_ref": "demo-secret",
            "default_model": "demo-model",
            "input_cost_per_million": 1.25,
            "output_cost_per_million": 5.0,
            "capabilities": {"chat": True, "structured_output": True},
        },
    )
    assert response.status_code == 200
    payload = response.json()["provider"]
    assert payload["credential_configured"] is True
    assert "demo-secret" not in json.dumps(payload)
    assert payload["input_cost_per_million"] == 1.25
    assert payload["output_cost_per_million"] == 5.0


def test_ai_usage_summary_marks_estimates_and_calculates_cost(client, monkeypatch, tmp_path):
    import gateway.main as gateway
    monkeypatch.setattr(gateway, "PROCESSING_ROOT", tmp_path)
    (tmp_path / "ai_usage.jsonl").write_text(
        "\n".join(
            [
                json.dumps({"provider": "gemini", "model": "flash", "input_tokens": 1000, "output_tokens": 500, "total_tokens": 1500, "usage_source": "actual", "latency_ms": 100, "input_cost_per_million": 1.0, "output_cost_per_million": 2.0, "timestamp": 4102444800}),
                json.dumps({"provider": "gemini", "model": "flash", "input_tokens": 2000, "output_tokens": 1000, "total_tokens": 3000, "usage_source": "estimated", "latency_ms": 300, "input_cost_per_million": 1.0, "output_cost_per_million": 2.0, "timestamp": 4102444800}),
            ]
        )
        + "\n"
    )
    response = client.get("/v1/ai/usage?days=30")
    assert response.status_code == 200
    aggregate = response.json()["aggregates"][0]
    assert aggregate["requests"] == 2
    assert aggregate["input_tokens"] == 3000
    assert aggregate["output_tokens"] == 1500
    assert aggregate["total_tokens"] == 4500
    assert aggregate["estimated_requests"] == 1
    assert aggregate["cost_usd"] == 0.006
    assert aggregate["average_latency_ms"] == 200.0
