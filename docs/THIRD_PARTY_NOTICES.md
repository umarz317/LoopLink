# Credits and license notices

## Origin

LoopLink is a modified version of [DiPlay by shihabal3amri](https://github.com/shihabal3amri/DiPlay), which is itself a modified version of [xcertplay by shilapi](https://github.com/shilapi/xcertplay). Both are licensed under GNU GPL version 3; the full text is in `LICENSE`. The original xcertplay README is retained in `docs/UPSTREAM-README.md`.

The AirPlay, iAP2 and accessory-authentication code under `desktop/src/main/kotlin/com/shilapi/xcertplay/` comes from those projects, with existing source comments and attribution preserved. Upstream credits [LIVI](https://github.com/f-io/LIVI) and [Showcase](https://github.com/amineross/showcase) for protocol research.

LoopLink removes the Android receiver and adds a macOS receiver with a browser display (`desktop/`). Those changes are also GPL-3.0.

The DiPlay Android home/settings UI and download website adapted [DiAuto](https://github.com/shihabal3amri/DiAuto) under AGPL-3.0. That code is not part of LoopLink; it remains available in the upstream DiPlay history.

## Runtime dependencies

- Kotlin standard library — JetBrains; Apache License 2.0.
- Bouncy Castle 1.79 — The Legion of the Bouncy Castle Inc.; Bouncy Castle license (MIT-style).
- Jackson Databind 2.18 — FasterXML; Apache License 2.0.
- Java-WebSocket 1.6.0 — Nathan Rajlich and contributors; MIT license.
- SLF4J — QOS.ch; MIT license.
- libimobiledevice, libusbmuxd, libplist — libimobiledevice project; GNU LGPL version 2.1 or later. Not bundled: the USB helper links the copies installed on the user's Mac (for example with Homebrew).

License files for bundled dependencies are in `docs/licenses/dependencies/`.

## Trademarks

CarPlay, iPhone and AirPlay are trademarks of Apple Inc. LoopLink is not an Apple-certified product, and no Apple affiliation or endorsement is implied.

## Experimental authentication data

Connecting to a real iPhone requires an accessory identity that is never part of this repository. DiPlay's preview APK bundled a certificate/key pair recovered from public Carlinkit C2Air firmware; such data are not Apple-issued credentials for LoopLink and are not relicensed as project source code. LoopLink reads a locally supplied identity from `DIPLAY_AUTH_ASSETS_DIR` and never commits or serves it; its continued acceptance by iOS is unresolved.
