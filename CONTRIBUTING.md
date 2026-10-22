# Contributing

Thanks for helping. Forks are welcome too: if you get this running on a different panel or model, tell us in an issue even if you never send code back.

## Ground rules

1. **Nothing sends a steam or light command the user did not tap.** No automatic Start, no retries except the existing bounded Stop resend, no command at page load or on wake. See [SAFETY.md](SAFETY.md).
2. **Stop always works.** Don't add any lock, guard or busy state that can block it.
3. **`python3 controls/verify.py` passes, and no suite's check count goes down.** Add checks for what you change. Behavior that only shows up on a device needs an emulator test in `controls/tests/overlay/`, using the fake equipment there.
4. **No identifiers in commits.** Never commit `household.json`, keys, certificates, timelines, logs, IP or MAC addresses, account names, or screenshots that show any of these. Use the made-up values from `household.example.json` in tests.
5. **No Mr. Steam code or files.** Don't commit the vendor's APK, decompiled code, resources or assets. Describe behavior you observed instead.
6. **Write for the panel's WebView.** Android 10's stock WebView is Chrome 74, so the page avoids `??`, `?.`, grid inside `<button>` and other newer features. A test enforces the syntax.
7. **Version bumps** change `android:versionName`/`versionCode` in `controls/AndroidManifest.xml`, `Timeline.BUILD`, `RemoteSignIn`'s build string and the test expectations together.

## Pull requests

- Keep each PR to one change and explain why it's needed.
- Include `verify.py` output, and emulator output or screenshots for anything visual.
- If you tested on a real panel, say which model and firmware, and whether the steam was on.

## License

By contributing you agree that your contribution is licensed under the GPL-3.0, like the rest of the project.
