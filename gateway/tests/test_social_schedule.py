import asyncio
import os
import tempfile
import unittest
from pathlib import Path

from gateway import main


class SocialScheduleTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.previous_db = main.DB_PATH
        main.DB_PATH = Path(self.temp_dir.name) / "gateway.db"
        main.init_db()
        self._insert_account()

    def tearDown(self):
        main.DB_PATH = self.previous_db
        self.temp_dir.cleanup()

    def _insert_account(self):
        timestamp = main.now_iso()
        with main.closing(main.db()) as connection:
            connection.execute(
                "INSERT INTO accounts (id, platform, account_name, status, daily_limit, min_gap_seconds, created_at, updated_at) VALUES (?, ?, ?, 'connected', 10, 0, ?, ?)",
                ("acct-test", "youtube", "test-channel", timestamp, timestamp),
            )
            connection.commit()

    def _payload(self, post_id=None):
        return main.PostPayload(
            id=post_id,
            platform="youtube",
            account="test-channel",
            mediaUrl="https://www.youtube.com/watch?v=demo",
            title="A test clip",
            caption="A test caption",
            scheduledAt="2030-01-01T10:00:00+00:00",
            autoPublish=True,
            status="scheduled",
        )

    def test_same_payload_is_idempotent_even_with_different_client_ids(self):
        first = main.save_post(self._payload("client-one"))
        second = main.save_post(self._payload("client-two"))

        self.assertEqual(first["id"], "client-one")
        self.assertEqual(second["id"], "client-one")
        self.assertEqual(first["idempotency_key"], second["idempotency_key"])

        with main.closing(main.db()) as connection:
            count = connection.execute("SELECT COUNT(*) AS count FROM posts").fetchone()["count"]
        self.assertEqual(count, 1)

    def test_transient_provider_error_keeps_post_scheduled_for_retry(self):
        first = main.save_post(self._payload("retry-post"))
        previous_status = main.PROVIDER_MODE
        previous_mock_status = os.environ.get("MOCK_PROVIDER_STATUS")
        previous_retry_after = os.environ.get("MOCK_RETRY_AFTER")
        try:
            main.PROVIDER_MODE = "mock"
            os.environ["MOCK_PROVIDER_STATUS"] = "429"
            os.environ["MOCK_RETRY_AFTER"] = "5"
            result = asyncio.run(main.publish_job(first["id"]))
        finally:
            main.PROVIDER_MODE = previous_status
            if previous_mock_status is None:
                os.environ.pop("MOCK_PROVIDER_STATUS", None)
            else:
                os.environ["MOCK_PROVIDER_STATUS"] = previous_mock_status
            if previous_retry_after is None:
                os.environ.pop("MOCK_RETRY_AFTER", None)
            else:
                os.environ["MOCK_RETRY_AFTER"] = previous_retry_after

        self.assertIsNotNone(result)
        self.assertEqual(result["status"], "scheduled")
        self.assertEqual(result["attempts"], 1)
        self.assertIsNotNone(result["next_attempt_at"])
        self.assertIn("429", result["error"])


if __name__ == "__main__":
    unittest.main()
