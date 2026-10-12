# FocusBoard Sync

A tiny Android app that sends your phone's screen time to [FocusBoard](https://github.com/Abel1712/focusboard) on your laptop.

## Install
1. Open the **Releases** page of this repository on your phone.
2. Tap **FocusBoardSync.apk** in the newest release and install it (allow "Install unknown apps" for your browser when asked).
3. Open the app, tap **Allow usage access**, and switch on **FocusBoard Sync**.

## Sync
1. On the laptop, double-click **Sync phone** (or press **Ctrl+Alt+P**). A QR code appears.
2. In the app, tap **Scan QR code to sync** and scan it.

## Privacy
- The app reads per-app screen time (the same data Digital Wellbeing uses) for the last 7 days.
- It only sends it to the laptop address inside the QR code, and only to local network addresses
  (`10.x`, `172.16-31.x`, `192.168.x`). The data is encrypted (AES-256 + HMAC) with a one-time key
  from the QR code, so only that laptop session can read it.
- Nothing is sent to the internet. No accounts, analytics or ads.

## Build
GitHub Actions builds the APK on every push to `main` (`.github/workflows/build.yml`) and attaches it to a release.

The release signing key is not in this repository. The workflow reads it from two repository secrets,
`KEYSTORE_BASE64` (the keystore file, base64-encoded) and `SIGNING_PASSWORD`. In a fork without those secrets the
APK is signed with a debug key and no release is published.

## License
[MIT](LICENSE)
