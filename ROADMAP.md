# Roadmap

Ideas that are ready to pick up. Each one should come with tests, and with an emulator check for anything that opens another screen. Comment on the matching issue before you start, so two people don't build the same thing.

## Check for updates inside Bear (help wanted)

**The problem.** Installing a new version today takes about eight steps:
1. Open Mr. Steam's system settings, then its Wi-Fi screen, which opens Android's Wi-Fi settings.
2. Search for "Apps & notifications" and find Chrome.
3. Fully stop Chrome. Otherwise Mr. Steam's once-a-second Back press closes it.
4. Open the install page, install, then start Bear and Remote Support again.

**The idea.** Add "Check for updates" to Settings:
- Bear asks the configured update source (the Mac's install page, or later GitHub Releases) whether a newer version exists.
- It downloads the APK and opens Android's normal package installer.
- The owner still taps through the installer.
- Android accepts only an update signed with the same key.

It needs the `REQUEST_INSTALL_PACKAGES` permission, a `FileProvider`, and the owner allowing "Install unknown apps" for Bear once. The update source should be a setting, not a hard-coded address.

## Settings shortcuts (good first issue)

One-tap rows in Settings for:
- Remote Support (`local.steam.support`): an ordinary launch.
- Android Wi-Fi, Bluetooth and Accessibility settings: standard `Settings.ACTION_*` intents. The Accessibility row helps when an update turns Bear's service off.

**Known limits.**
- The panel has no Back or Home buttons, so every shortcut must keep the Bear Steam tab on screen as the way back. Prove that on the emulator for each screen.
- A shortcut into Mr. Steam's own settings page isn't possible. Mr. Steam is one activity with no deep links, and Mr. Steam ↗ already brings it back where it was.
- A plain Chrome shortcut doesn't help, because of the Back behavior described above.

## Setup helper (good first issue)

A script that fills in `household.json` by:
- finding the Steamlinx bridge (the UDP `<HELLO>` broadcast on port 10022);
- listing the cloud device names for the signed-in account.

Today these are found by hand (docs/SETUP.md).

## Bluetooth now playing

Show what the paired iPad or phone is playing, and let you pause or skip from the panel.

## Run the tests on every pull request

`controls/verify.py` needs only a JDK, Python, Node and the fetched dependencies, so it can run in GitHub Actions.

## Other panels and models

Everything has been tried on one iSteamX panel (Android 10, PX30) with one Steamlinx bridge. Reports from other models, firmware versions or regions are very welcome, working or not.

## Small cleanups

- The launcher label still reads "Bear Steam (Test)". It should read "Mr. Bear Steam".
- The install page (`controls/download.html`) lists the owner's version history. Make it generic.
