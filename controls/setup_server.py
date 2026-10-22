"""Local download + one-use, TLS-pinned Mac-to-panel sign-in handoff.

Only the loopback form accepts credentials. Passwords live in memory for at most
two minutes and are delivered once over TLS to the requesting panel. No logs,
files, analytics, cloud proxying, or equipment commands.

0.18 adds a diagnostic timeline: the panel POSTs bounded event batches to
/timeline over the same pinned TLS; they are appended to a 0600 JSONL file and
served back on loopback only (GET /timeline?since=N). Batches are validated and
refused if any string looks like an address, MAC, email, token or long id.
"""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from collections import deque
from urllib.parse import urlsplit, parse_qs
import hmac
import json
import math
import os
import re
import secrets
import ssl
import threading
import time

ROOT = Path(__file__).resolve().parent
import household
HOUSEHOLD = household.load()
MAC = HOUSEHOLD['mac_host']
PANEL = HOUSEHOLD['panel_host']
FORM_ORIGIN = 'http://127.0.0.1:8776'

def sync_result(body):
    """Deliberately no arbitrary logs, tokens, shadow documents or account identifiers."""
    keys = {'build', 'stage', 'reportedOn', 'fresh'}
    if body.get('build') in {'0.10', '0.11', '0.12', '0.13', '0.14', '0.15', '0.16', '0.17', '0.18', '0.19', '0.20', '0.21', '0.22'}:
        keys |= {'messages', 'reported', 'lights', 'accepted', 'kind', 'view', 'attempt', 'command'}
        for key in ('messages', 'reported', 'lights', 'accepted', 'attempt'):
            if type(body.get(key)) is not int or not 0 <= body[key] <= 1000000:
                raise ValueError('Invalid sync result')
        if body.get('kind') not in {'none', 'desired-only', 'other', 'invalid', 'reported', 'light-fields', 'accepted-report'}:
            raise ValueError('Invalid sync result')
        if body.get('view') not in {'bear', 'original-watch', 'bear-overlay', 'original-visible'} or body.get('command') not in {'idle', 'sending', 'cloud-received', 'unconfirmed', 'confirmed', 'unchanged', 'failed'}:
            raise ValueError('Invalid sync result')
    if set(body) != keys or body['build'] not in {'0.7', '0.8', '0.9', '0.10', '0.11', '0.12', '0.13', '0.14', '0.15', '0.16', '0.17', '0.18', '0.19', '0.20', '0.21', '0.22'}:
        raise ValueError('Invalid sync result')
    stages = {'Not checked', 'Connecting status reader', 'Checking GET subscription',
              'Checking live-update subscription', 'Status subscriptions accepted',
              'Status reader disconnected', 'Status GET sent; waiting for reported lights',
              'Live subscription accepted; waiting for reported lights',
              'Live light report received', 'Device reports Chroma disconnected',
              'Device light report is old', 'Status GET failed', 'Status reader unavailable', 'Status refresh request failed'}
    stages |= {s + ' failed' for s in ('Not checked', 'Connecting status reader',
                                    'Checking GET subscription', 'Checking live-update subscription',
                                    'Status reader disconnected')}
    stage = body['stage']
    if not isinstance(stage,str):
        raise ValueError('Invalid sync result')
    stage = re.sub(r' \(MQTT -?[0-9]{1,5}\)$', '', stage)
    if stage not in stages or type(body['fresh']) is not bool:
        raise ValueError('Invalid sync result')
    if body['reportedOn'] is not None and type(body['reportedOn']) is not bool:
        raise ValueError('Invalid sync result')
    return dict(body, receivedAt=int(time.time()))


TIMELINE_BUILDS = {'0.18', '0.19', '0.20', '0.21', '0.22'}
TIMELINE_MAX_BYTES = 131072
TIMELINE_MAX_EVENTS = 500
TIMELINE_MAX_FIELDS = 16
TIMELINE_MAX_TEXT = 160
TIMELINE_MAX_INT = 2 ** 53
TIMELINE_TYPE = re.compile(r'[a-z][a-z0-9_.]{0,31}')
TIMELINE_KEY = re.compile(r'[a-zA-Z][a-zA-Z0-9]{0,23}')
# The panel scrubs these already (Timeline.scrub); a batch that still carries one is refused whole.
TIMELINE_SECRETS = [re.compile(p) for p in (
    r'[^\s@"]+@[^\s@"]+',                                  # email
    r'(?i)\bsu-[^\s"\[]+',                                  # thing name
    r'(?i)[0-9a-f]{2}(?:[:-][0-9a-f]{2}){2,}',               # MAC (or most of one)
    r'\d{1,3}(?:\.\d{1,3}){3}',                              # IPv4
    r'(?i)[0-9a-f]{0,4}:[0-9a-f]{0,4}:[0-9a-f]{0,4}:',        # IPv6
    r'[A-Za-z0-9+/_=-]{24,}',                                 # tokens, keys, ids
    r'(?i)[0-9a-f]{12,}',                                     # long hex
    r'\d{7,}',                                                # long numbers
)]


def timeline_secret(text):
    return any(p.search(text) for p in TIMELINE_SECRETS)


def timeline_batch(body):
    """Validate one panel batch. Returns (run, lost, events); raises ValueError otherwise."""
    if not isinstance(body, dict) or set(body) != {'v', 'build', 'run', 'lost', 'events'}:
        raise ValueError('Invalid timeline batch')
    if body['v'] != 1 or type(body['v']) is not int or body['build'] not in TIMELINE_BUILDS:
        raise ValueError('Invalid timeline batch')
    for key in ('run', 'lost'):
        if type(body[key]) is not int or not 0 <= body[key] < TIMELINE_MAX_INT:
            raise ValueError('Invalid timeline batch')
    events = body['events']
    if not isinstance(events, list) or not 1 <= len(events) <= TIMELINE_MAX_EVENTS:
        raise ValueError('Invalid timeline batch')
    for event in events:
        if not isinstance(event, dict) or not {'s', 'm', 'w', 't'} <= set(event):
            raise ValueError('Invalid timeline event')
        for key in ('s', 'm', 'w'):
            if type(event[key]) is not int or not 0 <= event[key] < TIMELINE_MAX_INT:
                raise ValueError('Invalid timeline event')
        if event['s'] < 1 or not isinstance(event['t'], str) or not TIMELINE_TYPE.fullmatch(event['t']):
            raise ValueError('Invalid timeline event')
        fields = {k: v for k, v in event.items() if k not in ('s', 'm', 'w', 't')}
        if len(fields) > TIMELINE_MAX_FIELDS:
            raise ValueError('Invalid timeline event')
        for key, value in fields.items():
            if not TIMELINE_KEY.fullmatch(key):
                raise ValueError('Invalid timeline event')
            if value is None or type(value) is bool:
                continue
            if type(value) is int:
                if not -TIMELINE_MAX_INT < value < TIMELINE_MAX_INT:
                    raise ValueError('Invalid timeline event')
            elif type(value) is float:
                if not math.isfinite(value):
                    raise ValueError('Invalid timeline event')
            elif type(value) is str:
                if len(value) > TIMELINE_MAX_TEXT or timeline_secret(value):
                    raise ValueError('Invalid timeline event')
            else:
                raise ValueError('Invalid timeline event')
    return body['run'], body['lost'], events


class TimelineStore:
    """Append-only 0600 JSONL of panel events plus a bounded in-memory tail for GET ?since=N."""
    ROTATE_BYTES = 20 * 1024 * 1024

    def __init__(self, path, keep=20000, clock=time.time):
        self.path = Path(path)
        self.clock = clock
        self.lock = threading.Lock()
        self.tail = deque(maxlen=keep)
        self.next = 1
        self.seen = {}
        if self.path.exists():
            with self.path.open() as lines:
                for line in lines:
                    try:
                        record = json.loads(line)
                    except ValueError:
                        continue
                    self.tail.append(record)
                    self.next = max(self.next, record.get('n', 0) + 1)
                    run = record.get('run')
                    self.seen[run] = max(self.seen.get(run, 0), record.get('s', 0))

    def append(self, body):
        run, lost, events = timeline_batch(body)
        with self.lock:
            records = []
            received = int(self.clock() * 1000)
            newest = self.seen.get(run, 0)
            for event in events:
                if event['s'] <= newest:
                    continue  # A resend after a lost reply.
                newest = event['s']
                records.append(event)
            self.seen[run] = newest
            if lost and records:
                # The panel's ring overwrote events before they could be sent.
                records.insert(0, {'t': 'timeline.lost', 'count': lost, 's': 0, 'm': records[0]['m'], 'w': records[0]['w']})
            for record in records:
                record.update(n=self.next, run=run, rx=received)
                self.next += 1
            if records:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                if self.path.exists() and self.path.stat().st_size > self.ROTATE_BYTES:
                    os.replace(self.path, self.path.with_name(self.path.name + '.1'))
                fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
                try:
                    os.fchmod(fd, 0o600)
                    os.write(fd, ''.join(json.dumps(r, separators=(',', ':')) + '\n' for r in records).encode())
                finally:
                    os.close(fd)
                self.tail.extend(records)
            return len(records)

    def since(self, after, limit=2000):
        with self.lock:
            events = [r for r in self.tail if r['n'] > after][:max(1, min(limit, 5000))]
            oldest = self.tail[0]['n'] if self.tail else self.next
            return {'events': events, 'next': events[-1]['n'] if events else max(after, self.next - 1), 'oldest': oldest}


class PairStore:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.items = {}
        self.lock = threading.Lock()

    def prune(self):
        now = self.clock()
        self.items = {k: v for k, v in self.items.items() if v['expires'] > now}

    def begin(self, address):
        with self.lock:
            self.prune()
            # One active setup per panel, bounded memory even on repeated requests.
            self.items = {k: v for k, v in self.items.items() if v['address'] != address}
            if len(self.items) >= 4:
                raise ValueError('Setup is busy')
            identity = secrets.token_hex(32)
            codes = {v['code'] for v in self.items.values()}
            code = str(secrets.randbelow(900000) + 100000)
            while code in codes:
                code = str(secrets.randbelow(900000) + 100000)
            self.items[identity] = dict(address=address, code=code,
                                       expires=self.clock() + 300, state='waiting')
            return dict(id=identity, code=code)

    def submit(self, code, email, password):
        if not isinstance(code, str) or not isinstance(email, str) or not isinstance(password, str):
            raise ValueError('Invalid setup')
        if not email.strip() or not password or len(email) > 254 or len(password) > 2048:
            raise ValueError('Enter your existing Mr. Steam login')
        with self.lock:
            self.prune()
            matches = [v for v in self.items.values() if hmac.compare_digest(v['code'], code)]
            if len(matches) != 1 or matches[0]['state'] != 'waiting':
                raise ValueError('Code expired or already used. Tap Sign in on the panel for a new code.')
            item = matches[0]
            item.update(credentials=dict(email=email.strip(), password=password), state='queued',
                        expires=min(item['expires'], self.clock() + 120))

    def poll(self, identity, address):
        with self.lock:
            self.prune()
            item = self.items.get(identity)
            if not item or item['address'] != address:
                raise ValueError('Setup expired')
            if item['state'] == 'queued':
                credentials = item.pop('credentials')
                item['state'] = 'delivered'
                return dict(state='credentials', **credentials)
            return dict(state=item['state'])

    def result(self, identity, address, state):
        if state not in ('connected', 'failed', 'canceled'):
            raise ValueError('Invalid result')
        with self.lock:
            self.prune()
            item = self.items.get(identity)
            if not item or item['address'] != address:
                raise ValueError('Setup expired')
            item.pop('credentials', None)
            item['state'] = state

    def state(self, code):
        with self.lock:
            self.prune()
            for item in self.items.values():
                if hmac.compare_digest(item['code'], code):
                    return item['state']
            return 'expired'


def make_handler(kind, store, csrf, timeline=None):
    class Handler(BaseHTTPRequestHandler):
        server_version = 'BearSteamSetup'

        def log_message(self, *_):
            pass

        def reply(self, status, data, mime='application/json'):
            if not isinstance(data, bytes):
                data = json.dumps(data).encode()
            self.send_response(status)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(data)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('X-Frame-Options', 'DENY')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            self.end_headers()
            self.wfile.write(data)

        def allowed(self, post=False):
            address = self.client_address[0]
            port = self.server.server_address[1]
            hosts = {f'127.0.0.1:{port}'} if kind == 'form' else {f'{MAC}:{port}', f'127.0.0.1:{port}'}
            addresses = {'127.0.0.1'} if kind == 'form' else {MAC, PANEL, '127.0.0.1'}
            if address not in addresses or self.headers.get('Host') not in hosts:
                self.reply(403, {'error': 'Unavailable'}); return False
            origin = self.headers.get('Origin')
            if kind == 'form':
                if (post and origin != FORM_ORIGIN) or (origin is not None and origin != FORM_ORIGIN):
                    self.reply(403, {'error': 'Unavailable'}); return False
                if post and not hmac.compare_digest(self.headers.get('X-Setup-Token', ''), csrf):
                    self.reply(403, {'error': 'Unavailable'}); return False
            elif origin is not None:
                self.reply(403, {'error': 'Unavailable'}); return False
            return True

        def read_json(self, limit=8192):
            if self.headers.get('Content-Type') != 'application/json':
                raise ValueError('Expected JSON')
            length = int(self.headers.get('Content-Length', '0'))
            if not 1 <= length <= limit:
                raise ValueError('Invalid body')
            value = json.loads(self.rfile.read(length))
            if not isinstance(value, dict):
                raise ValueError('Invalid body')
            return value

        def do_GET(self):
            if not self.allowed(): return
            try:
                if kind == 'form' and self.path == '/':
                    page = (ROOT/'setup.html').read_text().replace('__SETUP_TOKEN__', csrf)
                    return self.reply(200, page.encode(), 'text/html; charset=utf-8')
                if kind == 'form' and self.path == '/setup.js':
                    return self.reply(200, (ROOT/'setup.js').read_bytes(), 'application/javascript')
                if kind == 'form' and self.path.startswith('/state/'):
                    return self.reply(200, {'state': store.state(self.path[7:])})
                if kind == 'relay' and self.path.startswith('/pair/'):
                    return self.reply(200, store.poll(self.path[6:], self.client_address[0]))
                if kind == 'form' and timeline is not None and urlsplit(self.path).path == '/timeline':
                    query = parse_qs(urlsplit(self.path).query)
                    after = int(query.get('since', ['0'])[0])
                    limit = int(query.get('limit', ['2000'])[0])
                    return self.reply(200, timeline.since(after, limit))
                if kind == 'form' and self.path == '/sync-result':
                    report = ROOT/'build/sync-result.json'
                    return self.reply(200, json.loads(report.read_text()) if report.exists() else {'state':'waiting'})
                if kind == 'download':
                    files = {'/': ('download.html', 'text/html; charset=utf-8'),
                             '/bear-steam-0.14.apk': ('bear-steam-0.14.apk', 'application/vnd.android.package-archive'),
                             '/bear-steam-0.15.apk': ('bear-steam-0.15.apk', 'application/vnd.android.package-archive'),
                             '/bear-steam-0.16.apk': ('bear-steam-0.16.apk', 'application/vnd.android.package-archive'),
                             '/bear-steam-0.17.apk': ('bear-steam-0.17.apk', 'application/vnd.android.package-archive'),
                             '/bear-steam-0.18.apk': ('bear-steam-0.18.apk', 'application/vnd.android.package-archive'),
                             '/bear-steam-0.19.apk': ('bear-steam-0.19.apk', 'application/vnd.android.package-archive'),
                             '/bear-steam-0.20.apk': ('bear-steam-0.20.apk', 'application/vnd.android.package-archive'),
                             '/bear-steam-0.21.apk': ('bear-steam-0.21.apk', 'application/vnd.android.package-archive'),
                             '/bear-steam-0.22.apk': ('bear-steam-0.22.apk', 'application/vnd.android.package-archive'),
                             '/SHA256.txt': ('SHA256.txt', 'text/plain'),
                             '/morning': ('MORNING.md', 'text/plain; charset=utf-8'),
                             '/source.zip': ('bear-steam-source.zip', 'application/zip')}
                    if self.path in files:
                        name, mime = files[self.path]
                        data = (ROOT/name).read_bytes()
                        if name.endswith('.html'):
                            data = household.page(data.decode(), HOUSEHOLD).encode()
                        return self.reply(200, data, mime)
                self.reply(404, {'error': 'Not found'})
            except (ValueError, OSError):
                self.reply(410, {'error': 'Setup expired or unavailable'})

        def do_POST(self):
            if not self.allowed(post=True): return
            try:
                if kind == 'relay' and timeline is not None and self.path == '/timeline':
                    length = int(self.headers.get('Content-Length', '0'))
                    if length > TIMELINE_MAX_BYTES:
                        if length <= 8 * TIMELINE_MAX_BYTES:
                            self.rfile.read(length)  # Bounded drain so the sender sees the status, not a reset.
                        self.close_connection = True
                        return self.reply(413, {'error': 'Too large'})
                    stored = timeline.append(self.read_json(TIMELINE_MAX_BYTES))
                    return self.reply(200, {'ok': True, 'stored': stored})
                body = self.read_json()
                if kind == 'relay' and self.path == '/sync-result':
                    clean = sync_result(body)
                    (ROOT/'build').mkdir(exist_ok=True)
                    report = ROOT/'build/sync-result.json'
                    report.write_text(json.dumps(clean));report.chmod(0o600)
                    return self.reply(200, {'ok': True})
                if kind == 'relay' and self.path == '/pair' and body == {}:
                    return self.reply(201, store.begin(self.client_address[0]))
                if kind == 'relay' and self.path.startswith('/result/') and set(body) == {'state'}:
                    store.result(self.path[8:], self.client_address[0], body['state'])
                    return self.reply(200, {'ok': True})
                if kind == 'form' and self.path == '/credentials' and set(body) == {'code','email','password'}:
                    store.submit(body['code'], body['email'], body['password'])
                    return self.reply(202, {'ok': True})
                self.reply(404, {'error': 'Not found'})
            except (ValueError, TypeError, KeyError):
                self.reply(400, {'error': 'Check your code and login. If the code expired, tap Sign in on the panel for a new code.'})
    return Handler


def main():
    store = PairStore(); csrf = secrets.token_hex(32)
    timeline = TimelineStore(os.environ.get('BEAR_TIMELINE') or ROOT/'build/timeline.jsonl')
    download = ThreadingHTTPServer(('0.0.0.0', 8775), make_handler('download', store, csrf))
    form = ThreadingHTTPServer(('127.0.0.1', 8776), make_handler('form', store, csrf, timeline))
    relay = ThreadingHTTPServer(('0.0.0.0', 8777), make_handler('relay', store, csrf, timeline))
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(ROOT/'assets/setup-server.crt', ROOT/'private/setup-server.key')
    relay.socket = context.wrap_socket(relay.socket, server_side=True)
    for server in (download, form, relay):
        server.daemon_threads = True
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print(f'Download: http://{MAC}:8775/ | Mac sign-in: http://127.0.0.1:8776/', flush=True)
    try:
        while True:
            time.sleep(10)
            with store.lock: store.prune()
    finally:
        for server in (download, form, relay): server.shutdown(); server.server_close()


if __name__ == '__main__': main()
