from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

import gateway.main as gateway


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(gateway, "DB_PATH", tmp_path / "gateway.db")
    monkeypatch.setattr(gateway, "SOURCE_ROOT", tmp_path / "sources")
    monkeypatch.setattr(gateway, "PROCESSING_ROOT", tmp_path / "processing")
    gateway.init_db()
    with TestClient(gateway.app) as test_client:
        yield test_client
