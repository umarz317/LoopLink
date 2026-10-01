<img src="asset/looplink-logo.svg" alt="LoopLink" width="320">

**CarPlay for Android head units.** LoopLink turns an Android head unit into a wired or wireless CarPlay receiver. App ID: `io.loopbreak.looplink`, so it installs alongside DiPlay instead of replacing it.

> **Fork notice.** LoopLink is a fork of [DiPlay](https://github.com/shihabal3amri/DiPlay) by shihabal3amri, which is itself based on [xcertplay](https://github.com/shilapi/xcertplay) by shilapi; its settings layout adapts [DiAuto](https://github.com/shihabal3amri/DiAuto). LoopLink removes DiPlay's BYD-specific features and rebrands the app. It is an independent project and is not affiliated with or endorsed by the DiPlay, xcertplay or DiAuto authors.

## What it does

- Wired USB and wireless CarPlay (Wi-Fi Direct or the car's existing hotspot) with local authentication.
- CarPlay size, resolution, frame rate, audio routing and optional iPhone location reporting.
- Standard Android media and voice keys control CarPlay playback and Siri.
- Local diagnostic reports, saved to `Downloads/LoopLink`; nothing is sent automatically.

Compared with DiPlay, LoopLink has **no** BYD features: no windshield HUD or instrument-cluster navigation, dashboard map, ADB battery, wheel-speed or parked-video reporting, and no BYD steering-wheel keycodes.

## Status

Not yet tested on a head unit or with an iPhone. The build passes its unit tests and lint, and the app UI was checked in an Android head-unit emulator, which cannot run a CarPlay session. Behaviour on real head units is inherited from DiPlay 0.2.8 but has not been revalidated after the BYD removal. This is **not an Apple-certified product**.

## Build

Requirements: JDK 25, Android SDK 37, NDK 28.2.13676358.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

A plain debug build has no accessory identity and cannot connect to an iPhone. A build that can authenticate needs an accessory identity supplied with `DIPLAY_AUTH_ASSETS_DIR` (`offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`); those files are never part of this repository, and `scripts/check_public_tree.py` fails if one is tracked.

## Documentation

The guides below are inherited from DiPlay and may still refer to it; they describe the same receiver.

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md)
- [Release notes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

## License

LoopLink is free software under the **GNU General Public License v3.0**; see [LICENSE](LICENSE). The settings screen adapts DiAuto and is marked AGPL-3.0-only; its license is in `docs/licenses/`. As a derivative of GPL-3.0 code, LoopLink must stay under the same terms: distributed copies and modifications must keep this license and make their source available.

Copyright © 2026 Loopbreak for LoopLink's changes; the original code remains copyright its respective authors. CarPlay and iPhone are trademarks of Apple Inc.; no Apple affiliation or endorsement is implied.
