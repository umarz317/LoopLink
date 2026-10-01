# Unreleased — LoopLink browser receiver

- Fork DiPlay as LoopLink: a macOS CarPlay receiver that shows the iPhone's CarPlay screen in Chrome.
- Connect over USB with no Bluetooth or Wi-Fi setup: macOS usbmuxd opens the iPhone's CarPlay iAP2 channel and macOS's own USB networking carries AirPlay. Verified with an iPhone 17 on iOS 27.0.1: authentication, live video and tap input. Audio and microphone are untested.
- Simple full-window viewer with a LoopLink logo, compact controls and a settings panel.
- Remove the Android head-unit app and the wireless (Bluetooth) path; the protocol code it used now lives in `desktop/` with its tests.

The entries below are the Android history inherited from DiPlay.

# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# Unreleased — generic head units

- Settings → CarPlay authentication chooses the source: built-in identity, or an Apple MFi chip on a CH341 USB adapter or the head unit's I2C bus (upstream xcertplay's method). The chosen chip is no longer overridden by installed identity files, and chip mode needs no identity files. Verified in the emulator up to waiting for the chip; not yet tested with real hardware.
- Rename the app to CarLink with package `io.loopbreak.carlink` (installs separately from DiPlay) and a black-and-white launcher icon. The iPhone shows the car as CarLink; reports save to Downloads/CarLink. The debug build no longer adds `.hudtest`.
- Fix the crash right after VPN consent when connecting with USB: wired sessions no longer require a saved car-hotspot name (the default wireless mode). Reproduced and verified in the emulator.
- Guard the VPN consent screen on head units that lack it, and keep the last crash in the diagnostic report (`crash.log`).
- Home screen: a USB session is labelled USB CarPlay without hotspot hints, and no "car hotspot is off" warning appears before a hotspot is saved. Settings keep their scroll position after visiting system settings. Diagnostic reports say when a synthetic test identity is installed.
- Smoother, more responsive CarPlay on slow head units: touches are sent without Nagle delay; protocol hex traces are off unless `adb shell setprop log.tag.DiPlayTrace DEBUG`; video, audio, touch and wired-network threads run at display/audio priority; the video decoder reserves input buffers for one frame instead of 8 MB. Video stats now include decoder delay (`decode avg/max`). Not yet measured on a car.
- Add `scripts/emulator.sh`, a head-unit emulator for testing without the car; debuggable identity-free builds use a throwaway identity there.
- Remove BYD-specific features: windshield HUD and instrument-cluster navigation, the CarPlay dashboard map, optional ADB map pause and battery reporting, BYD steering-wheel keycodes and BYD settings screens.
- Stop the SOME/IP binding loop and cluster tickers that previously ran on every head unit.
- The car app in CarPlay uses a generic home icon (`asset/ic_car_home.svg`) and the label "Car" instead of the BYD logo and label. A new setting, Display and performance → Show car app in CarPlay, hides it.
- Navigation audio defaults to the media stream (3) instead of BYD's driver-speaker stream (14); the Audio routing grid still selects any stream.
- The voice key also accepts `KEYCODE_ASSIST`.
- On upgrade, delete data left by the removed features, including the ADB key that the car may have authorized. Revoke USB-debugging authorizations in the car's developer options if needed.
- Remove the BYDMate maneuver icons and their PolyForm Noncommercial notice, the Usage Access permission and the BYD package queries.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
