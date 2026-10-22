"""0.18 diagnostic timeline: server contract on an isolated loopback server, and the Mac CLI's math."""
import sys, unittest, json, tempfile, threading, http.client, os, stat, io, contextlib
from pathlib import Path
from http.server import ThreadingHTTPServer
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT.parent/'agent-tools/timeline'))
from setup_server import timeline_batch, TimelineStore, make_handler, PairStore, TIMELINE_MAX_BYTES
import bear_timeline


def event(s, t, m=None, **fields):
    return dict(s=s, m=1000 + s if m is None else m, w=1729500000000 + s, t=t, **fields)


def batch(events, run=1729500000000, lost=0):
    return dict(v=1, build='0.18', run=run, lost=lost, events=events)


class Server:
    def __init__(self, kind, store):
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), make_handler(kind, PairStore(), 'test-only', store))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start(); return self

    def __exit__(self, *_):
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=2)

    def request(self, method, path, body=None, headers=None, raw=None):
        connection = http.client.HTTPConnection('127.0.0.1', self.server.server_port, timeout=3)
        data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
        connection.request(method, path, data, {'Content-Type': 'application/json', **(headers or {})})
        response = connection.getresponse(); payload = response.read(); connection.close()
        return response.status, payload


class TimelineContractTest(unittest.TestCase):
    def test_valid_batch_and_java_sample(self):
        run, lost, events = timeline_batch(batch([event(1, 'ui.tap', control='temperature', delta=1, target=116, session=True),
                                                  event(2, 'bridge.read', ok=False, ms=2004, reason='timeout', error=None, roomF=82.5)]))
        self.assertEqual((lost, len(events)), (0, 2))
        sample = ROOT/'build/timeline-sample.json'  # Written by TimelineTest (Java) earlier in verify.py.
        if sample.exists():
            run, lost, events = timeline_batch(json.loads(sample.read_text()))
            self.assertGreaterEqual(len(events), 5)
            self.assertEqual({e['t'] for e in events} >= {'ui.tap', 'bridge.read', 'conn', 'ui.status', 'light.cmd'}, True)

    def test_malformed_batches_rejected(self):
        good = batch([event(1, 'conn', to=False, cause='poll-fail')])
        bad = [dict(good, extra=1), {k: v for k, v in good.items() if k != 'lost'}, dict(good, v=2), dict(good, build='0.17'),
               dict(good, run=-1), dict(good, run='1'), dict(good, lost=True), dict(good, events=[]), dict(good, events='x'),
               dict(good, events=[event(i + 1, 'x') for i in range(501)]), dict(good, events=[[1]]),
               dict(good, events=[{'s': 1, 'm': 1, 'w': 1}]), dict(good, events=[event(0, 'conn')]),
               dict(good, events=[event(1, 'Bad Type')]), dict(good, events=[event(1, 'conn', **{'bad-key': 1})]),
               dict(good, events=[event(1, 'conn', nested={'a': 1})]), dict(good, events=[event(1, 'conn', items=[1])]),
               dict(good, events=[event(1, 'conn', **{f'k{i}': i for i in range(17)})]),
               dict(good, events=[event(1, 'conn', text='x' * 161)]), dict(good, events=[event(1, 'conn', n=2 ** 60)]),
               dict(good, events=[event(1, 'conn', x=float('nan'))]), dict(good, events=[dict(event(1, 'conn'), m=True)]), []]
        for body in bad:
            with self.assertRaises(ValueError, msg=str(body)[:120]):
                timeline_batch(body)

    def test_identifiers_rejected(self):
        for text in ('connect to /192.168.1.104', 'SPA02:00:00:00:00:01', 'su-02:00:00:00:00:02-dev', 'owner@example.com',
                     'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9', 'fe80::1:2', 'acct 123456789012', '9f86d081884c7d659a2f'):
            with self.assertRaises(ValueError, msg=text):
                timeline_batch(batch([event(1, 'account.result', error=text)]))
        for text in ('Reconnecting…', '#ffaa00', 'Live subscription accepted; waiting for reported lights', '[ip] unreachable', 'Rx SPA[mac]'):
            timeline_batch(batch([event(1, 'ui.status', text=text)]))

    def test_store_appends_0600_dedups_and_marks_loss(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'timeline.jsonl'
            store = TimelineStore(path, clock=lambda: 1729500000.5)
            self.assertEqual(store.append(batch([event(1, 'a'), event(2, 'b')])), 2)
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
            self.assertEqual(store.append(batch([event(2, 'b'), event(3, 'c')])), 1, 'A resend stores only new events')
            self.assertEqual(store.append(batch([event(3, 'c')], lost=0)), 0)
            self.assertEqual(store.append(batch([event(10, 'd')], lost=6)), 2, 'Overwritten events leave a marker')
            self.assertEqual(store.append(batch([event(1, 'a')], run=99)), 1, 'A new run restarts its sequence')
            lines = [json.loads(l) for l in path.read_text().splitlines()]
            self.assertEqual([l['t'] for l in lines], ['a', 'b', 'c', 'timeline.lost', 'd', 'a'])
            self.assertEqual([l['n'] for l in lines], [1, 2, 3, 4, 5, 6])
            self.assertEqual(lines[3]['count'], 6)
            self.assertEqual(lines[0]['rx'], 1729500000500)
            page = store.since(3)
            self.assertEqual(([e['n'] for e in page['events']], page['next']), ([4, 5, 6], 6))
            self.assertEqual(store.since(6)['events'], [])
            reopened = TimelineStore(path)
            self.assertEqual((reopened.next, reopened.since(5)['events'][0]['t']), (7, 'a'))
            self.assertEqual(reopened.append(batch([event(10, 'd')])), 0, 'Dedup survives a server restart')

    def test_endpoints_on_isolated_loopback_servers(self):
        with tempfile.TemporaryDirectory() as folder:
            store = TimelineStore(Path(folder)/'t.jsonl')
            with Server('relay', store) as relay, Server('form', store) as form:
                good = batch([event(1, 'conn', to=False, cause='poll-fail'), event(2, 'conn', to=True, cause='poll-ok')])
                self.assertEqual(relay.request('POST', '/timeline', good)[0], 200)
                self.assertEqual(relay.request('POST', '/timeline', good, {'Origin': 'http://example.invalid'})[0], 403)
                self.assertEqual(relay.request('POST', '/timeline', dict(good, extra=1))[0], 400)
                self.assertEqual(relay.request('POST', '/timeline', raw=b'{not json')[0], 400)
                self.assertEqual(relay.request('POST', '/timeline', raw=b'NaN')[0], 400)
                self.assertEqual(relay.request('POST', '/timeline', good, {'Content-Type': 'text/plain'})[0], 400)
                big = b'{"pad":"' + b'x' * TIMELINE_MAX_BYTES + b'"}'
                self.assertEqual(relay.request('POST', '/timeline', raw=big)[0], 413)
                self.assertEqual(relay.request('GET', '/timeline')[0], 404, 'The LAN-facing relay never serves the timeline')
                self.assertEqual(form.request('POST', '/timeline', good)[0], 403, 'The loopback form never accepts panel batches')
                status, body = form.request('GET', '/timeline?since=0')
                page = json.loads(body)
                self.assertEqual((status, [e['cause'] for e in page['events']], page['next']), (200, ['poll-fail', 'poll-ok'], 2))
                self.assertEqual(json.loads(form.request('GET', '/timeline?since=2')[1])['events'], [])
                self.assertEqual(form.request('GET', '/timeline', headers={'Host': '192.168.1.223:8776'})[0], 403)
            with Server('relay', None) as legacy:
                self.assertEqual(legacy.request('POST', '/timeline', batch([event(1, 'x')]))[0], 404)


def run(events, run_id=1):
    return [dict(e, run=run_id, n=i + 1) for i, e in enumerate(events)]


class TimelineCliTest(unittest.TestCase):
    def test_percentiles(self):
        self.assertIsNone(bear_timeline.percentile([], 50))
        values = list(range(1, 101))
        self.assertEqual([bear_timeline.percentile(values, p) for p in (50, 90, 99, 100)], [50, 90, 99, 100])
        self.assertEqual(bear_timeline.percentile([300, 2000, 310], 50), 310)
        self.assertEqual(bear_timeline.spread([5])['p99'], 5)

    def test_reads_and_disconnects(self):
        events = run([
            event(1, 'bridge.read', m=0, ok=True, kind='full', ms=3700),
            event(2, 'bridge.read', m=2000, ok=True, kind='live', ms=300),
            event(3, 'bridge.read', m=4300, ok=False, kind='live', ms=2000, reason='timeout'),
            event(4, 'conn', m=4300, to=False, cause='poll-fail'),
            event(5, 'ui.connected', m=4301, to=False),
            event(6, 'bridge.read', m=6300, ok=False, kind='none', ms=0, reason='abandoned'),
            event(7, 'bridge.read', m=8600, ok=True, kind='live', ms=320),
            event(8, 'conn', m=8600, to=True, cause='poll-ok'),
            event(9, 'ui.connected', m=8601, to=True),
            event(10, 'bridge.read', m=12000, ok=False, kind='live', ms=2000, reason='incomplete'),
            event(11, 'conn', m=12000, to=False, cause='poll-fail'),
            event(12, 'conn', m=12001, to=False, cause='cmd-fail'),
            event(13, 'ui.status', m=12002, text='Reconnecting…'),
            event(14, 'bridge.locate.end', m=13000, ok=True)])
        s = bear_timeline.summarize(events)
        self.assertEqual((s['reads']['total'], s['reads']['ok'], s['reads']['abandoned']), (6, 3, 1))
        self.assertEqual(s['reads']['successRate'], 0.6, 'Abandoned reads are not bridge failures')
        self.assertEqual(s['reads']['reasons'], {'timeout': 1, 'abandoned': 1, 'incomplete': 1})
        self.assertEqual(s['reads']['byKind']['live'], {'n': 2, 'p50': 300, 'p90': 320, 'p99': 320, 'max': 320})
        self.assertEqual(s['disconnects']['count'], 2, 'A repeated false is one disconnect')
        self.assertEqual((s['disconnects']['recovered'], s['disconnects']['open'], s['disconnects']['totalMs']), (1, 1, 4300))
        self.assertEqual((s['uiDisconnects']['count'], s['uiDisconnects']['totalMs']), (1, 4300))
        self.assertEqual(s['uiStatus'], {'Reconnecting…': 1})
        self.assertEqual(s['locates'], {'total': 1, 'failed': 0})
        text = bear_timeline.render_summary(s)
        self.assertIn('3 ok / 5 (60.0%)', text)
        self.assertIn('lasted p50 4.30s', text)

    def test_tap_write_confirmed_latency(self):
        events = run([
            event(1, 'ui.tap', m=1000, control='temperature', delta=1, target=116, session=True),
            event(2, 'ui.tap', m=1200, control='temperature', delta=1, target=117, session=True),
            event(3, 'ui.tap', m=1300, control='time', delta=1, draft=21),  # idle draft tap: never sent
            event(4, 'ui.send', m=2000, control='temperature', value=117),
            event(5, 'cmd.submit', m=2001, action='temperature', value=117, accepted=True, task=1),
            event(6, 'cmd.start', m=2003, action='temperature', task=1),
            event(7, 'bridge.read', m=2400, ok=True, kind='live', ms=300),
            event(8, 'bridge.write', m=2500, action='temperature', value=117, receipt=True, ms=90),
            event(9, 'cmd.end', m=3100, action='temperature', task=1, ok=True),
            event(10, 'ui.tap', m=4000, control='steam', action='stop'),
            event(11, 'cmd.submit', m=4001, action='stop', value=0, accepted=True, task=2),
            event(12, 'cmd.start', m=4002, action='stop', task=2),
            event(13, 'bridge.write', m=4400, action='stop', value=0, receipt=False, ms=100),
            event(14, 'cmd.end', m=12400, action='stop', task=2, ok=False, error='Stop unconfirmed. Use Mr. Steam now.'),
            event(15, 'cmd.submit', m=13000, action='time', value=9, accepted=False, why='busy')])
        rows = bear_timeline.latencies(events)
        self.assertEqual(len(rows), 2)
        temp, stop = rows
        self.assertEqual((temp['tapToSubmit'], temp['tapToWrite'], temp['tapToConfirmed']), (1001, 1410, 2100),
                         'Latency runs from the FIRST tap of the burst; the write counts from when it was sent')
        self.assertEqual((stop['tapToWrite'], stop['tapToConfirmed'], stop['ok'], stop['tapToEnd']), (300, None, False, 8400))
        s = bear_timeline.summarize(events)
        self.assertEqual((s['commands']['temperature']['confirmed'], s['commands']['stop']['confirmed']), (1, 0))
        self.assertEqual(s['commands']['temperature']['tapToConfirmed']['p50'], 2100)

    def test_tail_lines_are_readable(self):
        events = run([event(1, 'conn', m=0, to=False, cause='poll-fail', lastOkAgoMs=4300),
                      event(2, 'bridge.read', m=2000, ok=True, kind='live', ms=303, asks=1, frags=2),
                      event(3, 'ui.tap', m=2100, control='temperature', delta=1, target=116, session=True)])
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'t.jsonl'
            path.write_text(''.join(json.dumps(e) + '\n' for e in events))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                bear_timeline.main(['tail', '--file', str(path)])
            lines = out.getvalue().splitlines()
            self.assertEqual(len(lines), 3)
            self.assertIn('DISCONNECTED (poll-fail, last ok 4.30s ago)', lines[0])
            self.assertIn('+2.000', lines[1]); self.assertIn('read   ok   live 303ms', lines[1])
            self.assertIn('TAP    temperature +1 → 116', lines[2])
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                bear_timeline.main(['summary', '--file', str(path), '--json'])
            self.assertEqual(json.loads(out.getvalue())['reads']['ok'], 1)


if __name__ == '__main__':
    unittest.main()
