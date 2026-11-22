import json
import tempfile
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
        self.temporary = tempfile.TemporaryDirectory()
        manifest_path = Path(self.temporary.name) / "manifest.json"
        manifest_path.write_text(json.dumps({"formatVersion": 2, "serverId": "gang", "mods": [{
            "id": "dialogauth-companion", "version": "9.9.9-test", "url": "mods/custom-companion.jar",
            "file": "custom-companion.jar", "size": 100, "sha256": "b" * 64, "required": True}, {
            "id": "trmt", "version": "0.5.1", "file": "trmt-0.5.1.jar", "size": 123,
            "sha256": "a" * 64, "required": True, "homepage": "https://example.com/mod",
            "sources": "https://example.com/source"}]}))
        manifest_path.with_name("modpack-public.key").write_bytes(b"test-public-key")
        self.site = create_server("127.0.0.1", 0, Path(__file__).parent / "static",
                                  f"http://127.0.0.1:{self.mod.server_port}/reset", FakeModHandler.token,
                                  manifest_path=manifest_path)
        threading.Thread(target=self.site.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.site.server_port}"

    def tearDown(self):
        self.site.shutdown()
        self.site.server_close()
        self.mod.shutdown()
        self.mod.server_close()
        self.temporary.cleanup()

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
        self.assertIn("/downloads/client/mods/custom-companion.jar", index)
        self.assertIn("Dialog Auth 9.9.9-test", index)
        self.assertNotIn("{{COMPANION_URL}}", index)
        self.assertIn("Fabric Loader 0.20.1", index)
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

    def test_manual_install_report_contains_versions_hashes_and_project_links(self):
        _, _, index = self.get("/")
        self.assertIn("Ручная установка и проверка", index)
        self.assertIn("PojavLauncher", index)
        self.assertIn("trmt 0.5.1", index)
        self.assertIn("a" * 64, index)
        self.assertIn("https://example.com/source", index)
        self.assertIn("Fingerprint ключа подписи", index)
        self.assertNotIn("{{MANUAL_REPORT}}", index)

    def test_missing_manifest_does_not_break_download_or_reset(self):
        self.site.RequestHandlerClass.application.manifest_path = Path(self.temporary.name) / "missing.json"
        status, _, index = self.get("/")
        self.assertEqual(status, 200)
        self.assertIn("Список модов временно недоступен", index)
        status, _, reset = self.get("/reset")
        self.assertEqual(status, 200)
        self.assertIn("Сброс пароля", reset)


if __name__ == "__main__":
    unittest.main()
