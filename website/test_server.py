import json
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from server import create_server


class FakeModHandler(BaseHTTPRequestHandler):
    token = "test-token-that-is-longer-than-thirty-two-characters"
    received = []

    def log_message(self, *args):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"]))
        authorized = self.headers.get("Authorization") == "Bearer " + self.token
        payload = json.loads(body)
        self.received.append(payload)
        result = {"ok": authorized and payload.get("code") == "123456"}
        data = json.dumps(result).encode()
        self.send_response(200 if authorized else 401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class WebsiteTest(unittest.TestCase):
    def setUp(self):
        FakeModHandler.received = []
        self.mod = ThreadingHTTPServer(("127.0.0.1", 0), FakeModHandler)
        threading.Thread(target=self.mod.serve_forever, daemon=True).start()
        self.site = create_server("127.0.0.1", 0, Path(__file__).parent / "static",
                                  f"http://127.0.0.1:{self.mod.server_port}/reset", FakeModHandler.token)
        threading.Thread(target=self.site.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.site.server_port}"

    def tearDown(self):
        self.site.shutdown()
        self.site.server_close()
        self.mod.shutdown()
        self.mod.server_close()

    def get(self, path):
        with urllib.request.urlopen(self.base + path, timeout=5) as response:
            return response.status, dict(response.headers), response.read().decode()

    def post(self, username="Alice", code="123456", password="new-password", confirmation="new-password"):
        data = urllib.parse.urlencode({"username": username, "code": code, "password": password,
                                       "password_confirm": confirmation}).encode()
        request = urllib.request.Request(self.base + "/api/reset", data=data,
                                         headers={"Content-Type": "application/x-www-form-urlencoded"})
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, response.read().decode()
        except urllib.error.HTTPError as error:
            with error:
                return error.code, error.read().decode()

    def test_download_and_reset_pages(self):
        status, headers, index = self.get("/")
        self.assertEqual(status, 200)
        self.assertIn("dialog-auth-companion-1.5.0.jar", index)
        self.assertIn("Fabric Loader 0.17.3", index)
        self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
        _, _, reset = self.get("/reset")
        self.assertNotIn("{{STATUS}}", reset)
        self.assertIn('autocomplete="one-time-code"', reset)
        _, _, css = self.get("/style.css")
        self.assertNotIn("gradient", css.lower())

    def test_reset_calls_local_api_and_preserves_username(self):
        status, page = self.post()
        self.assertEqual(status, 200)
        self.assertIn("Пароль изменён", page)
        self.assertEqual(FakeModHandler.received[0]["password"], "new-password")
        status, page = self.post(code="654321")
        self.assertIn("Не удалось сбросить пароль", page)
        self.assertIn('value="Alice"', page)

    def test_password_confirmation_is_checked_before_api(self):
        status, page = self.post(confirmation="different-password")
        self.assertEqual(status, 400)
        self.assertIn("Пароли не совпадают", page)
        self.assertEqual(FakeModHandler.received, [])

    def test_rate_limit(self):
        for _ in range(5):
            status, _ = self.post(code="654321")
            self.assertEqual(status, 200)
        status, page = self.post(code="654321")
        self.assertEqual(status, 429)
        self.assertIn("Слишком много попыток", page)


if __name__ == "__main__":
    unittest.main()
