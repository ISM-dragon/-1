import unittest

from gateway.main import app


class ApiContractTests(unittest.TestCase):
    def test_required_versioned_routes_exist(self):
        routes = {(route.path, method) for route in app.routes for method in getattr(route, "methods", set())}
        required = {
            ("/health", "GET"),
            ("/v1/auth/session", "GET"),
            ("/v1/processing/capabilities", "GET"),
            ("/v1/processing/jobs", "POST"),
            ("/v1/processing/jobs/{job_id}", "GET"),
            ("/v1/processing/jobs/{job_id}/cancel", "POST"),
            ("/v1/processing/jobs/{job_id}/retry", "POST"),
            ("/v1/processing/jobs/{job_id}/resume", "POST"),
            ("/v1/sources/upload", "POST"),
            ("/v1/sources/uploads", "POST"),
            ("/v1/sources/uploads/{upload_id}", "GET"),
            ("/v1/sources/uploads/{upload_id}", "PUT"),
            ("/v1/sources/uploads/{upload_id}/complete", "POST"),
            ("/v1/social/accounts", "GET"),
            ("/v1/social/{platform}/connect", "POST"),
            ("/v1/social/{platform}/callback", "GET"),
            ("/v1/social/{platform}/disconnect", "POST"),
            ("/v1/social/{platform}/status", "GET"),
            ("/v1/publishing/jobs", "POST"),
            ("/v1/publishing/jobs/{post_id}", "GET"),
            ("/v1/publishing/jobs/{post_id}/cancel", "POST"),
            ("/v1/publishing/jobs/{post_id}/retry", "POST"),
            ("/v1/analytics", "GET"),
        }
        self.assertTrue(required.issubset(routes), sorted(required - routes))


if __name__ == "__main__":
    unittest.main()
