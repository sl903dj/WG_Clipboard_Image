#!/usr/bin/env python3
import hmac
import mimetypes
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOST = "10.66.0.2"  # Linux WireGuard IP
PORT = 8765
TOKEN_FILE = Path.home() / ".config/wg-clipboard/token"
MAX_TEXT_SIZE = 1024 * 1024
MAX_IMAGE_SIZE = 20 * 1024 * 1024
IMAGE_TYPES = ("image/png", "image/jpeg", "image/webp", "image/gif")

TOKEN = TOKEN_FILE.read_text().strip()


class ClipboardHandler(BaseHTTPRequestHandler):
    def authenticate(self):
        auth = self.headers.get("Authorization", "")
        return hmac.compare_digest(auth, "Bearer " + TOKEN)

    def reply(self, code, data=b"", content_type="text/plain; charset=utf-8"):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if data:
            self.wfile.write(data)

    def check_request(self, path):
        if self.path != path:
            self.reply(404, b"Not found")
            return False
        if not self.authenticate():
            self.reply(401, b"Unauthorized")
            return False
        return True

    def do_GET(self):
        if self.path == "/clipboard":
            if not self.check_request("/clipboard"):
                return
            try:
                result = subprocess.run(
                    ["xclip", "-selection", "clipboard", "-o"],
                    capture_output=True, timeout=3, check=True
                )
                self.reply(200, result.stdout, "text/plain; charset=utf-8")
            except Exception:
                self.reply(500, b"Clipboard read failed")
            return

        if self.path == "/clipboard/image":
            if not self.check_request("/clipboard/image"):
                return
            for mime in IMAGE_TYPES:
                try:
                    result = subprocess.run(
                        ["xclip", "-selection", "clipboard", "-o", "-t", mime],
                        capture_output=True, timeout=5
                    )
                    if result.returncode == 0 and result.stdout:
                        if len(result.stdout) > MAX_IMAGE_SIZE:
                            self.reply(413, b"Image too large")
                        else:
                            self.reply(200, result.stdout, mime)
                        return
                except subprocess.TimeoutExpired:
                    self.reply(504, b"Clipboard read timed out")
                    return
            self.reply(404, b"No supported image in clipboard")
            return

        self.reply(404, b"Not found")

    def do_POST(self):
        if self.path not in ("/clipboard", "/clipboard/image"):
            self.reply(404, b"Not found")
            return
        if not self.authenticate():
            self.reply(401, b"Unauthorized")
            return

        is_image = self.path == "/clipboard/image"
        limit = MAX_IMAGE_SIZE if is_image else MAX_TEXT_SIZE
        try:
            size = int(self.headers.get("Content-Length", "-1"))
            if size < 0 or size > limit:
                self.reply(413, b"Payload too large")
                return
            data = self.rfile.read(size)
            if len(data) != size:
                self.reply(400, b"Incomplete request body")
                return

            if is_image:
                mime = self.headers.get_content_type().lower()
                if mime not in IMAGE_TYPES:
                    self.reply(415, b"Unsupported image type")
                    return
                result = subprocess.run(
                    ["xclip", "-selection", "clipboard", "-t", mime, "-i"],
                    input=data, capture_output=True, timeout=10
                )
            else:
                result = subprocess.run(
                    ["xclip", "-selection", "clipboard", "-i"],
                    input=data, capture_output=True, timeout=5
                )
            if result.returncode != 0:
                self.reply(500, result.stderr or b"Clipboard write failed")
            else:
                self.reply(200, b"OK")
        except subprocess.TimeoutExpired:
            self.reply(504, b"Clipboard operation timed out")
        except Exception:
            self.reply(500, b"Clipboard write failed")

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), ClipboardHandler)
    print(f"Clipboard server listening on {HOST}:{PORT}")
    server.serve_forever()
