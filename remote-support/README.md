# Bear Remote Support (optional)

A separate Android app (`local.steam.support`) that lets someone at a computer see the wall panel and tap it. It exists because the iSteamX panel has no working ADB over Wi-Fi and no reachable USB port, which made testing on the real panel impossible. You do not need it to use Mr. Bear Steam.

## How a session works

1. The owner opens Remote Support on the panel and taps **Start support**. Android asks to share the screen; the owner taps **Start now**.
2. The panel shows a pairing code. The operator approves that code on the Mac (`python3 support_cli.py approve CODE`).
3. The panel sends screen frames to `relay.py` on the Mac over pinned TLS. The operator saves a frame (`support_cli.py screenshot`) and taps by coordinates (`support_cli.py tap X Y`). Each tap must name the frame it was aimed at, so a stale screen cannot be tapped.

## Limits built in

- **Owner-started only.** No startup receiver, no unattended reconnect, no saved session.
- **Session end.** A session ends after at most 2 hours. It also ends on the panel's Stop chip or notification, on screen lock, if the service is turned off, or after 15 s without reaching the relay.
- **Allowed apps.** It can see and act on Mr. Steam, Bear, Chrome and Android Settings, and nothing else. Accessibility pages and installer or permission screens are blocked, and Force stop, Uninstall and Clear storage are refused. Chrome can open only the configured install pages.
- **Password screens.** A frame is sent blank when a password field may be visible.
- **No equipment access.** Remote Support has no steam or light API; it can only tap what is on screen.

## Build and run

Uses the same `household.json`, certificate and signing key as the main app (see [docs/SETUP.md](../docs/SETUP.md)).

```sh
python3 remote-support/build.py                      # bear-remote-support-<version>.apk
python3 remote-support/relay.py                      # relay on :8778, install page on :8780
python3 remote-support/support_cli.py status         # shows a pending pairing code
python3 remote-support/support_cli.py approve CODE   # only for a session the owner just started
python3 remote-support/support_cli.py screenshot     # then: tap X Y | long_press X Y | swipe ... | back | home | stop
```

Tests: `cd remote-support && python3 -m unittest discover -s tests`, plus `tests/build_fixture.py` and `tests/run_emulator.py` / `tests/run_loopback.py` on an isolated Android 10 emulator (no INTERNET permission in the offline build).
