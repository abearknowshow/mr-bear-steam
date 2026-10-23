# How it works

The iSteamX wall panel is an Android 10 tablet (PX30) whose home screen is the Mr. Steam app (`com.yizhong`). Mr. Bear Steam is a second app that draws its own full-screen dashboard on top of it. Mr. Steam keeps running underneath, so its timers, lights connection and safety behavior are never replaced.

```
 Wall panel (Android 10)                                Home network / cloud
 ┌────────────────────────────────────────┐
 │ Mr. Bear Steam                         │
 │  DashboardService ── WebView page      │  UDP 10022   ┌───────────────────┐
 │   (Java)            (index.html,       │─────────────▶│ Steamlinx bridge  │──▶ steam generator
 │                      live.js)          │              └───────────────────┘
 │  CloudLights (Cognito + AWS IoT MQTT)  │─────────────▶ Mr. Steam cloud ──▶ light ring
 │  DashboardAccessService (window host)  │
 ├────────────────────────────────────────┤  HTTPS (pinned) ┌──────────────────┐
 │ Mr. Steam (original app, HOME)         │◀───────────────▶│ Your Mac         │
 └────────────────────────────────────────┘  sign-in, logs  │ setup_server.py  │
                                                            └──────────────────┘
```

## The parts

- **`Protocol`, `UdpTransport`, `BridgeTransport`, `BridgeLocator`, `ControlEngine`** talk to the Steamlinx bridge. Only five commands exist: Start, Stop, time, temperature and lights. They carry identity checks, bounds and advancing sequence numbers, and every write is followed by a readback.
- **`CloudLights`, `ShadowRead`, `LightPayload`, `LightReport`** sign in to the same Mr. Steam account and change only the lighting state of the one panel that matches `household.json`.
- **`DashboardService` + `assets/index.html` + `assets/live.js`** draw the dashboard. The page talks to Java through a narrow bridge of nine methods. Nothing in the page reaches the network.
- **`DashboardAccessService` + `OverlayWindows`** host the window and open it on wake and after a restart.
- **`PanelPolicy` + `PanelSettings`** hold the rules (guard, keep-screen-on, idle sleep, open on wake) and the few stored settings (accent, open on wake, last light color).
- **`RemoteSignIn` + `setup_server.py`** do a one-time sign-in from your Mac, so nobody types a password on a wet wall panel.
- **`Timeline`** keeps a scrubbed ring of 2,000 diagnostic events on the panel. They upload over pinned TLS to your Mac, where `agent-tools/timeline/bear_timeline.py` reads them.

## Problems we hit, and what got around them

| Problem on the real panel | Cause | What we did |
|---|---|---|
| "Feature not available" when asking for the normal overlay permission | The firmware disables `SYSTEM_ALERT_WINDOW` | Host the window from an accessibility service that asks for **no** capabilities and **no** events. It cannot read the screen or tap anything. |
| No ADB over Wi-Fi, no reachable USB port | Firmware | A separate owner-started Remote Support app that sends screenshots and coordinate taps through a relay on the Mac. A tap needs a fresh frame. |
| Stop failed in 0.1 | Every command reused sequence number 1 | Advancing sequence numbers. Only Stop may be resent. |
| About 1 in 5 bridge requests silently dropped (60 of 75) | The bridge drops requests from a **new** client id | Keep one client id per transport: 75 of 75 in a read-only test. In daily use about 1 read in 25 still misses; see [#8](https://github.com/abearknowshow/mr-bear-steam/issues/8). |
| After two misses, a 1.8 s network search that always found the same bridge | Rediscovery on every reset | Greet the last bridge first (about 20 ms) and search only if it stays silent; retry a missed read after 0.25 s; show "Reconnecting…" after 10 s instead of 6 (0.24). On the panel, "Reconnecting…" time fell from about 4–7% to about 2%. |
| Reads came back with another reader's data | The bridge mixes up concurrent readers | Never run a second reader while Bear is open. |
| Full status read took 3.7 to 4 s | Reading the whole register map | Read the 42-byte live block instead: about 300 ms. |
| The bridge's address changed (DHCP) | Home network | Discover the bridge by its identity on your subnet. Writes never trigger discovery. |
| Light state went stale after 2 idle minutes | The panel publishes light state only when it changes | Ask for a status-only refresh, at most once every 30 s. |
| Cloud status reads returned HTTP 403 | Account permissions | Use the live MQTT update stream instead. |
| Setting a light to the state it already had never confirmed | No change means no report | Show "unchanged" instead of waiting. |
| Light color reset to Amber after every wake | The light reports only on/off, never its color | Remember the last chosen color on the panel (0.22). |
| Screen never went to sleep with Bear open | The panel's own screen timeout never fires; Mr. Steam's idle screen only counts touches on its own app | After 5 minutes without a touch, and only with the steam confirmed off by a fresh reading, Bear turns the screen off itself through its accessibility service (0.23). Mr. Steam stops steam when the screen goes off, so Bear never does this while steam may be running. |
| Chrome kept closing | Mr. Steam presses Back once a second while Chrome is in front | Bear never needs Chrome. See ROADMAP for in-app updates. |
| Page blank on the test emulator | Android 10's stock WebView is Chrome 74 (no `??`, no grid inside buttons) | Write the page for Chrome 74. A test fails the build if newer syntax slips in. |

## Measured speed and reliability

All numbers come from the panel's own diagnostic timeline or the Android 10 emulator. "Confirmed" means the bridge reported the new state back.

| Measure | Before | After |
|---|---|---|
| Start: tap to confirmed (median, real panel) | 9.17 s (0.19) | 3.67 s (0.20) |
| Start confirmed in the emulator | 4 of 8 (0.19) | 8 of 8 (0.20) |
| Stop: tap to confirmed (median, emulator) | 2.9 s (0.19) | 0.47 s (0.20) |
| Stop: tap to command sent (median, real panel) | | 8 ms |
| Live status read | 3.7 to 4 s (full read) | about 300 ms (0.17) |
| Bridge requests answered | 60 of 75 (new id each time) | 75 of 75 (one id) |
| Screen wake to dashboard on screen (real panel) | opened by hand | 0.12 to 0.29 s (0.21) |

We never measured the original Mr. Steam app's own speed side by side, so these compare Bear with earlier Bear.

## Testing without hardware

`python3 controls/verify.py` runs about 800 checks: the protocol against recorded bridge replies, a fake bridge on loopback (including dropped packets and lost Stop receipts), the light reader against a fake AWS SDK, the page in jsdom, and the rules for wake, the guard and the screen. It never contacts a real device.

The emulator tests in `controls/tests/overlay/` build the real dashboard classes with fake steam, a fake cloud and a fake Mr. Steam app. Neither APK has the INTERNET permission. They run on an Android 10, 1024×600 emulator and cover wake, restart, the guard, return to Mr. Steam, and the remembered color.
