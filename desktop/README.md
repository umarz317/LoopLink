<img src="../asset/looplink-logo.svg" alt="LoopLink" width="280">

# CarLink Desk

Experimental macOS CarPlay receiver with a localhost browser display. The iPhone connects over a USB cable; no CarPlay dongle is used. The AirPlay, iAP2 and accessory-authentication code under `src/main/kotlin/com/shilapi/xcertplay/` comes from DiPlay and xcertplay (see the [root README](../README.md)).

![LoopLink browser connection screen with the play-button logo](../asset/screenshot.jpg)

Current browser UI, captured without an iPhone connected.

## Run

Requires macOS, Xcode Command Line Tools, Java 17 or newer and libimobiledevice:

```sh
brew install libimobiledevice
scripts/desktop.sh
```

Open **http://127.0.0.1:8765** in Google Chrome. Keep the terminal running. Stop with Ctrl+C.

`scripts/desktop.sh build` builds without starting a receiver, `scripts/desktop.sh test` runs the desktop tests, and `scripts/desktop.sh devices` lists USB iPhones with their CarPlay network interface.

## Connect your iPhone

1. Connect the iPhone with a USB data cable. Unlock it and tap **Trust This Computer** if asked.
2. In the browser, click **Connect**. Accept any CarPlay prompt on the iPhone.
3. A working session shows **iPhone connected** with a green dot and live video. Click on the video to tap; use the top bar for Home, Back, media and Siri. For Siri and calls, enable the microphone in Settings before connecting and allow Chrome microphone access.

## How it works

- macOS puts the iPhone in its CarPlay USB configuration and brings up its CDC-NCM network interface (for example `en14`).
- `desktop/native/usb.c` uses macOS usbmuxd and the Mac's existing trust record (via libimobiledevice) to open `com.apple.carkit.service`, and relays that iAP2 byte stream over stdin/stdout. It also finds the iPhone's NCM interface through IOKit.
- The JVM runs DiPlay's wired iAP2 identification and accessory authentication over that stream, then tells the iPhone to open AirPlay to the Mac's IPv6 link-local address on that interface. The AirPlay listener uses a free port, so macOS AirPlay Receiver on port 7000 does not conflict.
- The browser decodes H.264/AAC/Opus with WebCodecs, plays LPCM with Web Audio, forwards touch and controls, and can capture microphone audio for the encrypted RTP uplink.

The UI and media servers bind only to `127.0.0.1` on ports 8765/8766. Set `CARLINK_DESKTOP_PORT` to change the UI port; the media port is the next port.

## Accessory identity

A real iPhone requires a trusted accessory identity. The launcher defaults to this repository's ignored `.private/runtime-assets/offline-mfi` directory. To use another provisioned directory:

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets scripts/desktop.sh
```

That directory must contain `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Keys are read only by the JVM receiver and are never served to the browser, copied into the application bundle, or added to Git. There is no synthetic-identity fallback for real-device connections.

Display settings, the persistent AirPlay identity and phone pairings are saved in ignored `.private/desktop/` with directory mode 0700 and file mode 0600. Diagnostic logs are held in memory and exclude payload dumps; **Save** in the log section exports them.

## Validation boundary

Validated on one Mac with an iPhone 17 on iOS 27.0.1 over USB: accessory authentication, live CarPlay video in Chrome, and Home and tap input. Audio playback, microphone/Siri and other iPhone models or macOS versions have not been validated. Wireless CarPlay is not supported. A desktop test cannot validate head-unit hardware, vehicle controls, automotive audio routing or on-car performance.

The protocol source retains its GPL-3.0 notices. See the repository [LICENSE](../LICENSE) and [third-party notices](../docs/THIRD_PARTY_NOTICES.md) when distributing modifications.
