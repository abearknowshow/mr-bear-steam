#!/usr/bin/env python3
"""Read Bear's 0.18 diagnostic timeline on the Mac. Read-only: it never talks to the panel or bridge.

Usage:
  bear_timeline.py tail [--since N] [--follow] [--raw]      human-readable events (newest last)
  bear_timeline.py summary [--minutes M] [--since N] [--json]
Source: GET http://127.0.0.1:8776/timeline (setup_server, loopback only), or --file PATH for the JSONL.
"""
import argparse
import json
import math
import sys
import time
import urllib.request
from datetime import datetime

DEFAULT_URL = 'http://127.0.0.1:8776'


def fetch(url, since=0, limit=5000):
    with urllib.request.urlopen(f'{url}/timeline?since={int(since)}&limit={int(limit)}', timeout=5) as response:
        return json.load(response)


def load(args, since=0):
    if args.file:
        events = []
        with open(args.file) as lines:
            for line in lines:
                try:
                    record = json.loads(line)
                except ValueError:
                    continue
                if record.get('n', 0) > since:
                    events.append(record)
        return events
    events, cursor = [], since
    while True:
        page = fetch(args.url, cursor)
        events += page['events']
        if not page['events'] or page['next'] <= cursor:
            return events
        cursor = page['next']


def percentile(values, p):
    """Nearest-rank percentile; None for no data."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, math.ceil(p / 100 * len(ordered)))
    return ordered[min(rank, len(ordered)) - 1]


def spread(values):
    return {'n': len(values), 'p50': percentile(values, 50), 'p90': percentile(values, 90),
            'p99': percentile(values, 99), 'max': max(values) if values else None}


def outages(events, kind, value_key='to'):
    """Count true->false flips of a boolean event and how long each lasted (mono ms, per run)."""
    durations, open_since, down, last = [], {}, 0, {}
    for e in events:
        if e.get('t') != kind:
            continue
        run, up = e.get('run'), e.get(value_key)
        if up is False and last.get(run) is not False:
            down += 1
            open_since[run] = e['m']
        elif up is True and run in open_since:
            durations.append(e['m'] - open_since.pop(run))
        last[run] = up
    return {'count': down, 'recovered': len(durations), 'open': len(open_since),
            'totalMs': sum(durations), 'ms': spread(durations)}


CONTROL = {'start': 'steam', 'stop': 'steam', 'time': 'time', 'temperature': 'temperature'}


def latencies(events):
    """Tap -> native submit -> bridge write -> confirmed (cmd.end ok), per command task.

    A burst starts at the first accepted tap on a control since that control's previous submit;
    writes are attributed to the task running on the single steam worker (cmd.start..cmd.end)."""
    burst, tasks, running, done = {}, {}, {}, []
    for e in events:
        t, run = e.get('t'), e.get('run')
        if t == 'ui.tap' and not e.get('ignored') and e.get('control') in ('steam', 'time', 'temperature'):
            if e.get('control') != 'steam' and not e.get('session'):
                continue  # Idle taps edit a draft; nothing is sent.
            burst.setdefault((run, e['control']), e['m'])
        elif t == 'cmd.submit' and e.get('accepted'):
            control = CONTROL.get(e.get('action'))
            tap = burst.pop((run, control), None)
            tasks[(run, e.get('task'))] = {'action': e.get('action'), 'value': e.get('value'), 'tap': tap,
                                           'submit': e['m'], 'write': None, 'end': None, 'ok': None}
        elif t == 'cmd.start':
            running[run] = (run, e.get('task'))
        elif t == 'bridge.write' and run in running:
            task = tasks.get(running[run])
            if task and task['write'] is None and e.get('action') == task['action']:
                task['write'] = e['m'] - e.get('ms', 0)  # When the write went out, not when it returned.
        elif t == 'cmd.end':
            task = tasks.pop((run, e.get('task')), None)
            running.pop(run, None)
            if task:
                task.update(end=e['m'], ok=bool(e.get('ok')), error=e.get('error'))
                done.append(task)
    rows = []
    for task in done:
        start = task['tap'] if task['tap'] is not None else task['submit']
        rows.append({'action': task['action'], 'value': task['value'], 'ok': task['ok'], 'fromTap': task['tap'] is not None,
                     'tapToSubmit': task['submit'] - start,
                     'tapToWrite': None if task['write'] is None else task['write'] - start,
                     'tapToConfirmed': task['end'] - start if task['ok'] else None,
                     'tapToEnd': task['end'] - start, 'error': task.get('error')})
    return rows


def summarize(events):
    reads = [e for e in events if e.get('t') == 'bridge.read']
    ok = [e for e in reads if e.get('ok')]
    failed = [e for e in reads if not e.get('ok')]
    abandoned = [e for e in failed if e.get('reason') == 'abandoned']
    counted = len(reads) - len(abandoned)
    reasons = {}
    for e in failed:
        reasons[e.get('reason', '?')] = reasons.get(e.get('reason', '?'), 0) + 1
    by_kind = {}
    for e in ok:
        by_kind.setdefault(e.get('kind', '?'), []).append(e.get('ms', 0))
    rows = latencies(events)
    per_action = {}
    for row in rows:
        entry = per_action.setdefault(row['action'], {'n': 0, 'confirmed': 0, 'tapToWrite': [], 'tapToConfirmed': []})
        entry['n'] += 1
        entry['confirmed'] += 1 if row['ok'] else 0
        if row['tapToWrite'] is not None:
            entry['tapToWrite'].append(row['tapToWrite'])
        if row['tapToConfirmed'] is not None:
            entry['tapToConfirmed'].append(row['tapToConfirmed'])
    statuses = {}
    for e in events:
        if e.get('t') == 'ui.status':
            statuses[e.get('text')] = statuses.get(e.get('text'), 0) + 1
    return {
        'events': len(events),
        'runs': len({e.get('run') for e in events}),
        'lost': sum(e.get('count', 0) for e in events if e.get('t') == 'timeline.lost'),
        'reads': {'total': len(reads), 'ok': len(ok), 'failed': len(failed), 'abandoned': len(abandoned),
                  'successRate': None if counted == 0 else round(len(ok) / counted, 4), 'reasons': reasons,
                  'ms': spread([e.get('ms', 0) for e in ok]), 'byKind': {k: spread(v) for k, v in by_kind.items()}},
        'locates': {'total': sum(1 for e in events if e.get('t') == 'bridge.locate.end'),
                    'failed': sum(1 for e in events if e.get('t') == 'bridge.locate.end' and not e.get('ok'))},
        'disconnects': outages(events, 'conn'),
        'uiDisconnects': outages(events, 'ui.connected'),
        'uiStatus': statuses,
        'commands': {a: {'n': v['n'], 'confirmed': v['confirmed'], 'tapToWrite': spread(v['tapToWrite']),
                         'tapToConfirmed': spread(v['tapToConfirmed'])} for a, v in per_action.items()},
    }


def ms(value):
    return '—' if value is None else f'{value / 1000:.2f}s' if value >= 1000 else f'{value}ms'


def render_summary(s):
    r = s['reads']
    rate = '—' if r['successRate'] is None else f"{r['successRate'] * 100:.1f}%"
    out = [f"{s['events']} events, {s['runs']} run(s), {s['lost']} lost before upload",
           f"Bridge reads: {r['ok']} ok / {r['total'] - r['abandoned']} ({rate}); {r['abandoned']} abandoned for Stop",
           '  failures: ' + (', '.join(f'{k} {v}' for k, v in sorted(r['reasons'].items())) or 'none')]
    for kind, d in sorted(r['byKind'].items()):
        out.append(f"  {kind} ok: n={d['n']} p50 {ms(d['p50'])} p90 {ms(d['p90'])} p99 {ms(d['p99'])} max {ms(d['max'])}")
    out.append(f"Rediscoveries: {s['locates']['total']} ({s['locates']['failed']} failed)")
    for label, key in (('Disconnects (connected flag)', 'disconnects'), ('Disconnects shown to the page', 'uiDisconnects')):
        d = s[key]
        out.append(f"{label}: {d['count']} ({d['open']} still open); lasted p50 {ms(d['ms']['p50'])} p90 {ms(d['ms']['p90'])} "
                   f"max {ms(d['ms']['max'])}, total {ms(d['totalMs'])}")
    if s['uiStatus']:
        out.append('Status line shown: ' + ', '.join(f'"{k}" ×{v}' for k, v in sorted(s['uiStatus'].items())))
    for action, d in sorted(s['commands'].items()):
        out.append(f"{action}: {d['confirmed']}/{d['n']} confirmed; tap→write p50 {ms(d['tapToWrite']['p50'])} "
                   f"max {ms(d['tapToWrite']['max'])}; tap→confirmed p50 {ms(d['tapToConfirmed']['p50'])} "
                   f"p90 {ms(d['tapToConfirmed']['p90'])} max {ms(d['tapToConfirmed']['max'])}")
    return '\n'.join(out)


SKIP = {'s', 'm', 'w', 't', 'n', 'run', 'rx'}


def describe(e):
    t = e.get('t', '?')
    rest = ' '.join(f'{k}={json.dumps(v, ensure_ascii=False)}' for k, v in e.items() if k not in SKIP)
    if t == 'bridge.read':
        if e.get('ok'):
            return f"read   ok   {e.get('kind')} {ms(e.get('ms'))} (asks {e.get('asks')}, frags {e.get('frags')})"
        return f"read   FAIL {e.get('kind')} {ms(e.get('ms'))} {e.get('reason')} (asks {e.get('asks')}, frags {e.get('frags')}) {e.get('error', '')}"
    if t == 'bridge.write':
        return f"write  {e.get('action')}={e.get('value')} receipt={e.get('receipt')} sends={e.get('sends')} {ms(e.get('ms'))} {e.get('error', '')}".rstrip()
    if t == 'conn':
        return f"{'CONNECTED' if e.get('to') else 'DISCONNECTED'} ({e.get('cause')}, last ok {ms(e.get('lastOkAgoMs'))} ago)"
    if t == 'ui.connected':
        return f"PAGE connected={e.get('to')}: {e.get('why')}"
    if t == 'ui.status':
        return f"PAGE shows \"{e.get('text')}\""
    if t == 'ui.tap':
        if e.get('ignored'):
            return f"TAP    {e.get('control')} {e.get('delta', '')} ignored ({e.get('ignored')})"
        shown = e.get('target', e.get('draft', ''))
        delta = e.get('delta')
        return f"TAP    {e.get('control')} {'' if delta is None else f'{delta:+d} '}{'→ ' + str(shown) if shown != '' else rest}".rstrip()
    if t in ('cmd.submit', 'cmd.start', 'cmd.end'):
        return f"{t:<10} {rest}"
    return f'{t:<10} {rest}'.rstrip()


def line(e, previous):
    when = datetime.fromtimestamp(e['w'] / 1000).strftime('%H:%M:%S.%f')[:-3]
    gap = '' if previous is None or previous.get('run') != e.get('run') else f"+{(e['m'] - previous['m']) / 1000:.3f}"
    return f"{when} {gap:>8}  {describe(e)}"


def main(argv=None):
    parser = argparse.ArgumentParser(description='Bear 0.18 diagnostic timeline (read-only).')
    parser.add_argument('command', choices=('tail', 'summary'))
    parser.add_argument('--url', default=DEFAULT_URL)
    parser.add_argument('--file')
    parser.add_argument('--since', type=int, default=0, help='server event number to start after')
    parser.add_argument('--minutes', type=float, help='summary: only the last M minutes (panel wall clock)')
    parser.add_argument('--follow', action='store_true')
    parser.add_argument('--raw', action='store_true')
    parser.add_argument('--json', action='store_true')
    args = parser.parse_args(argv)
    events = load(args, args.since)
    if args.command == 'summary':
        if args.minutes:
            cutoff = time.time() * 1000 - args.minutes * 60000
            events = [e for e in events if e.get('w', 0) >= cutoff]
        s = summarize(events)
        print(json.dumps(s, indent=1) if args.json else render_summary(s))
        return 0
    previous = None
    while True:
        for e in events:
            print(json.dumps(e) if args.raw else line(e, previous), flush=True)
            previous = e
        if not args.follow:
            return 0
        cursor = events[-1]['n'] if events else args.since
        time.sleep(1)
        events = load(args, cursor)
        args.since = cursor


if __name__ == '__main__':
    sys.exit(main())
