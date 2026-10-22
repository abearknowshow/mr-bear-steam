# Safety

A steam shower is a heater and a steam generator in a closed room. This project sends commands to one. Treat it that way.

## What the software guarantees

- **Stop is never blocked.** Not by the lock, not by the 1.5 s wake guard, not while another command is in flight. Stop pre-empts any pending change.
- **No automatic Start.** Nothing retries a Start, and nothing starts steam without a tap. Opening on wake or after a restart sends no commands.
- **Only Stop is resent.** When its receipt is missing, Stop is sent at most three times within 1.2 s. Every other command is sent once.
- **A receipt is not proof.** "Confirmed" means the bridge reported the new state back. A cloud acknowledgement or a UDP receipt alone is never shown as success.
- **Device limits win.** Time and temperature are kept inside the limits the panel itself reports, and the UI's ranges sit inside those.
- **The original Mr. Steam app stays installed and in charge underneath.** Its own controls, timers and safety behavior are untouched. Mr. Steam ↗ in Settings brings it back at any time.

## What you must do

- **Install and test with the steam off.** Watch the first session on a new install or a new version from start to finish.
- **Do not leave steam running unattended** because of anything this app shows. Screens can be wrong; the room is the truth.
- **Never force-stop, clear or uninstall the Mr. Steam app.** The lights and the original controls depend on it.
- **Keep the original recovery path.** Know how to power-cycle your panel's hub, and keep a copy of the Mr. Steam installer you started with.
- **Installer, permission and accessibility screens are yours to tap.** No script in this repository taps them for you.
- **Remote Support is optional and owner-started only.** It needs the owner to press Start support on the panel. Sessions end after 2 hours, on screen lock or on Stop.

## No warranty

This is a hobby project shared under the GPL-3.0, which comes with no warranty (sections 15 and 16). It may void your equipment's warranty or support. You are responsible for what it does in your home.
