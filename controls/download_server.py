"""Static morning downloads only. No login, pairing, relay, POST, or equipment API."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
import household
HOUSEHOLD = household.load()
MAC, PANEL = HOUSEHOLD['mac_host'], HOUSEHOLD['panel_host']
FILES = {
    '/': ('download.html', 'text/html; charset=utf-8'),
    '/bear-steam-0.14.apk': ('bear-steam-0.14.apk', 'application/vnd.android.package-archive'),
    '/bear-steam-0.15.apk': ('bear-steam-0.15.apk', 'application/vnd.android.package-archive'),
    '/SHA256.txt': ('SHA256.txt', 'text/plain'),
    '/morning': ('MORNING.md', 'text/plain; charset=utf-8'),
    '/source.zip': ('bear-steam-source.zip', 'application/zip'),
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_): pass

    def do_GET(self):
        if self.client_address[0] not in {'127.0.0.1',MAC,PANEL} or self.headers.get('Host') not in {'127.0.0.1:8775',MAC+':8775'} or self.headers.get('Origin') is not None:
            self.send_error(403); return
        if self.path not in FILES:
            self.send_error(404); return
        name, mime = FILES[self.path]
        data = (ROOT/name).read_bytes()
        if name.endswith('.html'):
            data = household.page(data.decode(), HOUSEHOLD).encode()
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('X-Frame-Options', 'DENY')
        self.send_header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
        self.end_headers(); self.wfile.write(data)


if __name__ == '__main__':
    print(f'Static download only: http://{MAC}:8775/', flush=True)
    ThreadingHTTPServer(('0.0.0.0',8775),Handler).serve_forever()
