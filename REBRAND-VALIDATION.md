# Rebrand validation — 2026.11.22.1101

- App label, package, launcher icon, source folders and links use APRSERJa.
- assembleDebug, assembleRelease, bundleRelease, test and lint succeeded. No unit-test sources; validation below uses instrumentation.
- S26 Android 16 emulator: queue persistence across process restart, recovery with message acknowledgment/incoming message, station decoding, offline routing and simulated GPS passed.
- Real APRS-IS receive-only login/feed smoke passed; no live transmission required.
- Signed release APK signature verified, clean installation and cold launch succeeded.
- The renamed Android package installs separately; existing installation data is retained. Enter credentials in the new app.
- Signing identity retained for release continuity; its historical certificate subject is unchanged.
