import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx

from gateway import main


class GatewayApiE2ETests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name)
        self.patches = patch.multiple(
            main,
            DB_PATH=root / "gateway.db",
            PROCESSING_ROOT=root / "processing",
            SOURCE_ROOT=root / "sources",
            PUBLIC_BASE_URL="https://gateway.example.test",
            GATEWAY_TOKEN="qa-api-token",
            REQUIRE_GATEWAY_TOKEN=True,
            read_server_gemini_key=lambda: None,
        )
        self.patches.start()
        main.init_db()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="https://gateway.example.test")

    async def asyncTearDown(self):
        await self.client.aclose()
        self.patches.stop()
        self.directory.cleanup()

    async def test_public_health_and_private_session_contract(self):
        health = await self.client.get("/health", headers={"X-Request-ID": "qa-health-1"})
        self.assertEqual(health.status_code, 200)
        self.assertEqual(health.headers["x-request-id"], "qa-health-1")
        health_body = health.json()
        self.assertIn(health_body["status"], {"ok", "degraded"})
        self.assertNotIn("token", json_text(health_body).lower())

        unauthorized = await self.client.get("/v1/auth/session")
        self.assertEqual(unauthorized.status_code, 401)
        authorized = await self.client.get("/v1/auth/session", headers={"Authorization": "Bearer qa-api-token"})
        self.assertEqual(authorized.status_code, 200)
        self.assertEqual(authorized.json()["product"], "ISM")
        self.assertEqual(authorized.json()["api_version"], "v1")

    async def test_protected_capabilities_never_echo_provider_secret(self):
        response = await self.client.get(
            "/v1/processing/capabilities",
            headers={"Authorization": "Bearer qa-api-token", "X-Request-ID": "qa-capabilities"},
        )
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(response.headers["x-request-id"], "qa-capabilities")
        self.assertNotIn("qa-api-token", json_text(body))
        self.assertNotIn(str(self.directory.name), json_text(body))


def json_text(value) -> str:
    import json

    return json.dumps(value, sort_keys=True)


if __name__ == "__main__":
    unittest.main()
