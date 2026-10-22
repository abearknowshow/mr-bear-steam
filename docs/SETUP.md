# Setup

This builds Mr. Bear Steam for **your** panel. Plan an hour the first time. Keep the steam off while installing and testing. Read [SAFETY.md](../SAFETY.md) first.

## 1. Tools

- JDK 17 or 21 (`JAVA_HOME`). The default is Homebrew's `openjdk@21`.
- Android SDK with build-tools 36.0.0 and platform android-36 (`ANDROID_HOME`; override with `ANDROID_BUILD_TOOLS` and `ANDROID_PLATFORM`).
- Python 3.10+, Node.js 18+, `openssl`.

```sh
python3 controls/fetch_dependencies.py   # pinned AWS SDK, Paho, Conscrypt, Gson from Maven Central, checksum-verified
npm install                              # jsdom, for the page tests
python3 controls/verify.py               # about 800 checks, no hardware, uses household.example.json
```

## 2. household.json

Copy the example and fill in your values. The file is git-ignored; never commit it.

```sh
cp household.example.json household.json && chmod 600 household.json
```

| Field | What it is | How to find it |
|---|---|---|
| `mac_host` | The computer that runs `setup_server.py` (sign-in handoff, install page, diagnostics) | Its IP address on your home network. Give it a fixed address in your router. |
| `panel_host` | The wall panel | Android Settings → Network on the panel, or your router's client list |
| `lan_prefix` | Your home subnet, first three parts plus a dot | For example `192.168.1.` if your addresses are `192.168.1.x`. Bear looks for the bridge only inside it. |
| `bridge_id` | The Steamlinx bridge's identity, `SPA` followed by its MAC address | The bridge answers a UDP broadcast of `<HELLO>1</HELLO>` on port 10022 with `<HELLO>SPA…\|name</HELLO>`. Your router may also list the bridge's MAC. |
| `light_thing` | The panel's device name in the Mr. Steam cloud, `su-<MAC>-dev` | Bear lists your account's devices at sign-in and uses only the one matching this value. If it does not match, the error message shows the last two bytes Bear expected. |
| `vendor_cloud` | Mr. Steam's cloud settings (region, Cognito user pool, app client, identity pool, IoT endpoint, devices URL) | These are the same for every iSteamX account. They are published in [pedantique/mrsteam-homeassistant](https://github.com/pedantique/mrsteam-homeassistant) (`const.py`). |

A setup helper that finds `bridge_id` and `light_thing` for you is on the [roadmap](../ROADMAP.md).

## 3. Certificate

The panel apps trust only your Mac, through a certificate built into them.

```sh
python3 controls/make_certificate.py
```

This writes `controls/private/setup-server.key` and the certificate into both apps' `assets/`. All of these are git-ignored. If you replace the certificate later, rebuild and reinstall both apps.

## 4. Signing key

Android installs an update only when it is signed with the same key. Make one key and keep it safe.

```sh
keytool -genkeypair -keystore ~/bear-steam.p12 -storetype PKCS12 -alias bear-steam -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Bear Steam"
printf '%s' 'your-keystore-password' > ~/bear-steam.pass && chmod 600 ~/bear-steam.pass
export STEAM_SIGNING_KEYSTORE=~/bear-steam.p12 STEAM_SIGNING_PASSWORD_FILE=~/bear-steam.pass
```

## 5. Build and serve

```sh
python3 controls/build.py         # writes controls/bear-steam-<version>.apk
python3 controls/setup_server.py  # install page on http://<mac_host>:8775/, sign-in on http://127.0.0.1:8776/
```

## 6. Install on the panel

1. Turn the steam off.
2. On the panel, get to Chrome. Mr. Steam sends Back while Chrome is in front: open Android Settings from Mr. Steam's Wi-Fi screen, find Chrome under Apps & notifications, and fully stop Chrome before opening it.
3. Open `http://<mac_host>:8775/`, download the APK and install it. The installer screens are yours to tap.
4. Open Bear Steam and follow its setup: turn on its **Accessibility** service. The service has no screen-reading or gesture abilities. It only hosts the dashboard window.
5. Sign in once: Bear shows a code, and you open `http://127.0.0.1:8776/` on your Mac and use your existing Mr. Steam account. The password never touches the panel.

Then, still with the steam off: sleep and wake the screen a few times, restart the panel once, try Mr. Steam ↗ and the Bear Steam tab, and only then run a short steam session while you watch it.

## Optional

- **Diagnostics:** `python3 agent-tools/timeline/bear_timeline.py summary` reads the scrubbed timeline the panel sends to `setup_server.py` (set `BEAR_TIMELINE` to choose the file).
- **Remote Support:** see [remote-support/README.md](../remote-support/README.md). It is for testing on a panel without ADB, and only the owner can start a session.
- **Emulator tests:** create an Android 10 (API 29), 1024×600, 160 dpi emulator on port 5580, then `python3 controls/tests/overlay/build_fixture.py` and the `*_emulator_test.py` scripts.
