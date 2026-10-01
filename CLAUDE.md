# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

LoopLink is an experimental macOS CarPlay receiver with a browser display ("CarLink Desk" in code). It is a GPL-3.0 fork of DiPlay (itself based on xcertplay). The Android head-unit app and the wireless/Bluetooth path have been removed; only the macOS USB receiver remains.

## Commands

Requirements: macOS, Xcode Command Line Tools, JDK 17+, `brew install libimobiledevice`.

```sh
scripts/desktop.sh            # build helper + receiver, run at http://127.0.0.1:8765
scripts/desktop.sh test       # same as ./gradlew -p desktop test
scripts/desktop.sh devices    # list USB iPhones and their CarPlay network interface
python3 scripts/check_public_tree.py   # CI: fails on tracked credentials

./gradlew -p desktop test --tests "com.shilapi.xcertplay.iap2.Iap2ProtocolTest"
```

Gradle always runs with `-p desktop`; there is no root Gradle project. CI is `.github/workflows/desktop.yml`.

## Credential / identity rules

- A real iPhone needs an accessory identity (`offline-mfi/identity.pk8`, `certificate.p7b`) supplied only via `DIPLAY_AUTH_ASSETS_DIR` (default `.private/runtime-assets`). It must never be committed or served to the browser.
- `scripts/check_public_tree.py` fails on any tracked `.pk8/.p7b/.pem/.key/.p12/.pfx/.jks/.keystore/.apk/.aab`, anything under `.private/`, or any embedded PEM private-key block. Tests generate synthetic identities (`SyntheticMfiIdentity`) — do not add key fixtures.

## Layout

- `desktop/native/usb.c` — `carlink-usb` helper (libimobiledevice + IOKit). `devices` prints JSON (UDID, name, trusted, NCM BSD interface, interface number); `connect UDID` opens `com.apple.carkit.service` and relays the iAP2 bytes over stdin/stdout (stderr = status lines).
- `desktop/src/main/kotlin/io/loopbreak/carlink/desktop/` — receiver: `Main.kt` (localhost HTTP API + static files), `Receiver.kt` (session), `HelperStream.kt` (helper process as a byte stream), `BrowserHub.kt` (WebSocket media/input), `LocalStore.kt` (settings, AirPlay identity, pairings in `.private/desktop/`).
- `desktop/src/main/kotlin/com/shilapi/xcertplay/` — protocol code inherited from upstream: `airplay/` (RTSP, pairing, streams, `/info`), `iap2/`, `mfi/`, `transport/Iap2*` control clients. `android/util/Log.kt` and `android/os/Process.kt` are JVM stand-ins so it compiles unchanged.
- `desktop/web/` — browser UI (vanilla JS, WebCodecs/Web Audio). Static files are whitelisted in `Main.kt`.

## Session flow

`Receiver.startUsb`: helper `devices` → bind an AirPlay `ServerSocket` on the NCM interface's IPv6 link-local address with an ephemeral port (macOS AirPlay Receiver may own 7000) → `Iap2Session.open` over the helper stream → `Iap2WiredControlClient.run` with `Iap2WiredCarPlayEndpoint` (address, port, public key) → the iPhone connects to AirPlay → video/audio go to the browser.

## Conventions

- Docs avoid overclaiming: state what was physically validated (iPhone model, iOS, macOS) versus untested. Keep that tone in README, CHANGELOG and PR text.
- Kotlin package `com.shilapi.xcertplay` is inherited; keep upstream source notices.
- Commit subjects are imperative sentences without prefixes.
