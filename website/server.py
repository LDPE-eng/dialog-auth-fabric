import argparse
import html
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class RateLimiter:
    def __init__(self, maximum=5, window_seconds=900):
        self.maximum = maximum
        self.window_seconds = window_seconds
        self.attempts = defaultdict(list)
        self.lock = threading.Lock()

    def allow(self, address, now=None):
        current = time.monotonic() if now is None else now
        with self.lock:
            recent = [timestamp for timestamp in self.attempts[address]
                      if current - timestamp < self.window_seconds]
            if len(recent) >= self.maximum:
                self.attempts[address] = recent
                return False
            recent.append(current)
            self.attempts[address] = recent
            return True


class Application:
    def __init__(self, static_dir, reset_api_url, token, limiter=None):
        self.static_dir = Path(static_dir).resolve()
        self.reset_api_url = reset_api_url
        self.token = token
        self.limiter = limiter or RateLimiter()
        self.index = (self.static_dir / "index.html").read_text()
        self.reset = (self.static_dir / "reset.html").read_text()
        self.css = (self.static_dir / "style.css").read_bytes()

    def reset_page(self, message="", success=False, username=""):
        status = ""
        if message:
            status = f'<p class="status {"success" if success else "error"}" role="status">{html.escape(message)}</p>'
        return self.reset.replace("{{STATUS}}", status).replace("{{USERNAME}}", html.escape(username))


class Handler(BaseHTTPRequestHandler):
    application = None

    def log_message(self, *args):
        pass

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/":
            self.respond(200, self.application.index, "text/html; charset=utf-8")
        elif path == "/reset":
            self.respond(200, self.application.reset_page(), "text/html; charset=utf-8")
        elif path == "/style.css":
            self.respond(200, self.application.css, "text/css; charset=utf-8", cache=True)
        elif path == "/health":
            self.respond(200, '{"ok":true}', "application/json; charset=utf-8")
        else:
            self.respond(404, "Страница не найдена", "text/plain; charset=utf-8")

    def do_POST(self):
        if urllib.parse.urlparse(self.path).path != "/api/reset":
            self.respond(404, "Страница не найдена", "text/plain; charset=utf-8")
            return
        if not self.application.limiter.allow(self.client_address[0]):
            self.reset_result(429, "Слишком много попыток. Повторите через 15 минут.", False, "")
            return
        origin = self.headers.get("Origin")
        if origin and urllib.parse.urlparse(origin).netloc != self.headers.get("Host", ""):
            self.reset_result(403, "Некорректный источник запроса.", False, "")
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 4096:
                self.reset_result(400, "Некорректный запрос.", False, "")
                return
            form = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8"), max_num_fields=8)
            username = form.get("username", [""])[0]
            code = form.get("code", [""])[0]
            password = form.get("password", [""])[0]
            confirmation = form.get("password_confirm", [""])[0]
            if not re.fullmatch(r"[A-Za-z0-9_]{1,16}", username) or not re.fullmatch(r"[0-9]{6}", code):
                self.reset_result(400, "Проверьте ник и одноразовый код.", False, username)
                return
            if password != confirmation:
                self.reset_result(400, "Пароли не совпадают.", False, username)
                return
            if not 8 <= len(password) <= 128:
                self.reset_result(400, "Пароль должен содержать от 8 до 128 символов.", False, username)
                return
        except (ValueError, UnicodeDecodeError):
            self.reset_result(400, "Некорректный запрос.", False, "")
            return
        payload = json.dumps({"username": username, "code": code, "password": password}).encode()
        request = urllib.request.Request(self.application.reset_api_url, data=payload, method="POST", headers={
            "Authorization": "Bearer " + self.application.token,
            "Content-Type": "application/json",
        })
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                result = json.load(response)
        except (urllib.error.URLError, TimeoutError, ValueError):
            self.reset_result(502, "Сервис сброса временно недоступен. Попробуйте позже.", False, username)
            return
        if result.get("ok") is True:
            self.reset_result(200, "Пароль изменён. Теперь можно войти в игру с новым паролем.", True, username)
        else:
            self.reset_result(200, "Не удалось сбросить пароль. Проверьте ник и код или выдайте новый код.", False, username)

    def reset_result(self, status, message, success, username):
        self.respond(status, self.application.reset_page(message, success, username), "text/html; charset=utf-8")

    def respond(self, status, body, content_type, cache=False):
        data = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy",
                         "default-src 'self'; style-src 'self'; script-src 'self'; "
                         "form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
        self.send_header("Cache-Control", "public, max-age=300" if cache else "no-store")
        self.end_headers()
        self.wfile.write(data)


def create_server(host, port, static_dir, reset_api_url, token, limiter=None):
    application = Application(static_dir, reset_api_url, token, limiter)
    handler = type("ConfiguredHandler", (Handler,), {"application": application})
    return ThreadingHTTPServer((host, port), handler)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8088)
    parser.add_argument("--static-dir", type=Path, required=True)
    parser.add_argument("--reset-api-url", default="http://127.0.0.1:25578/reset")
    args = parser.parse_args()
    token = os.environ.get("DIALOG_AUTH_RESET_TOKEN", "")
    if len(token) < 32:
        raise RuntimeError("DIALOG_AUTH_RESET_TOKEN must contain at least 32 characters")
    server = create_server(args.host, args.port, args.static_dir, args.reset_api_url, token)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
