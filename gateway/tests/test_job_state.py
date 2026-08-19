import sqlite3
import tempfile
import unittest
from pathlib import Path

from gateway import main
from gateway.job_state import canonical_state, legacy_status, validate_transition


class JobStateUnitTests(unittest.TestCase):
    def test_legacy_status_is_backward_compatible(self):
        self.assertEqual(legacy_status("COMPLETED"), "done")
        self.assertEqual(legacy_status("CANCELLED"), "cancelled")
        self.assertEqual(legacy_status("FAILED"), "failed")
        self.assertEqual(legacy_status("RENDERING"), "running")

    def test_terminal_state_rejects_accidental_transition(self):
        with self.assertRaises(ValueError):
            validate_transition("COMPLETED", "RENDERING")
        validate_transition("FAILED", "RETRY_WAIT")
        validate_transition("INTERRUPTED", "QUEUED")

    def test_gateway_persists_transition_history(self):
        with tempfile.TemporaryDirectory() as directory:
            original = main.DB_PATH
            main.DB_PATH = Path(directory) / "gateway.db"
            try:
                main.init_db()
                now = main.now_iso()
                with sqlite3.connect(main.DB_PATH) as connection:
                    connection.execute(
                        "INSERT INTO processing_jobs (id, source, status, state, created_at, updated_at) VALUES (?, ?, 'queued', 'QUEUED', ?, ?)",
                        ("proc_test", "https://example.com/video.mp4", now, now),
                    )
                    connection.commit()
                main.processing_transition("proc_test", "PREPARING", stage="prepare", fraction=0.1, message="started")
                main.processing_transition("proc_test", "CANCELLED", error_code="JOB_CANCELLED", recoverable=False, cancel_requested=True)
                with sqlite3.connect(main.DB_PATH) as connection:
                    row = connection.execute("SELECT state, status, retry_count, cancel_requested FROM processing_jobs WHERE id='proc_test'").fetchone()
                self.assertEqual(row, ("CANCELLED", "cancelled", 0, 1))
                self.assertEqual(len(main.processing_transition_history("proc_test")), 2)
            finally:
                main.DB_PATH = original


if __name__ == "__main__":
    unittest.main()
