# xcertplay core comparison — 2026-10-01

Compared LoopLink `c90ab15` with [xcertplay `17c92439413638dfd1d7f91d7e1c2e7358398762`](https://github.com/shilapi/xcertplay/tree/17c92439413638dfd1d7f91d7e1c2e7358398762), the current upstream snapshot inspected for this port. The comparison covers the shared receiver and its integration into LoopLink's existing media session.

## Ported changes

| Area | Result in LoopLink |
| --- | --- |
| Wired audio | Advertise PCM for wired low-latency audio; retain Opus for wireless sessions. The controller sets the transport flag when creating its wireless AirPlay configuration. |
| Microphone startup | Start after the SETUP response, without waiting for the first downlink audio packet. Honor a requested microphone port for any main-audio category; keep each category's independent stream identity and teardown. |
| Microphone capture | Capture 16 kHz mono PCM in communication mode, resample to the negotiated rate, expand PCM channels when needed, and bind the UDP socket to the phone's address family. Restore the previous audio mode after the last microphone closes. Gain remains at the existing 100% level. |
| Opus uplink | Use the negotiated capture rate while keeping the RTP clock at 48 kHz. Use Concentus 1.0.0 when Android's encoder is absent, fails, or stops producing timely output. Include its BSD license and dependency notice. |
| Video packets | Honor negotiated 1-, 2-, or 4-byte NAL lengths; reject damaged access units and negative packet lengths; validate and order HEVC VPS/SPS/PPS while retaining supported SEI. |
| Video recovery | Request a keyframe when frames arrive without codec configuration. Keep LoopLink's decoder fallback, reference-chain recovery, queue limits, and diagnostics. Include explicit empty parameters in the primary-screen keyframe command. |
| Wireless networking | Accept both address families on the AirPlay and iAP listeners. Bonjour continues advertising and probing the verified hotspot interface. Fall back to WPA2 when vendor Wi-Fi Direct frameworks omit the security type. |
| Bluetooth bootstrap | Try paired smartphone candidates in connected-first order. A phone explicitly selected in LoopLink remains the only candidate. Retain the named-iPhone fallback for frameworks that omit the Bluetooth class. |
| iAP2 lifetime | Bound identification/authentication/subscription bring-up; keep authenticated control sessions alive with bounded I/O polls, including wireless sessions without location reporting. Preserve location-request continuation from Bluetooth to Wi-Fi. |
| Now playing | Merge incremental title, artist, album, duration, playback position, and status updates. Reset metadata when the AirPlay session ends. Publish through LoopLink's existing Android media session and retain its steering-wheel commands and audio-focus behavior. |
| Artwork | Receive and acknowledge iAP2 session-12 artwork transfers. Bound the cache to four entries and 16 MiB, sample decoded artwork to at most 512 pixels per dimension, and reuse the decoded bitmap. Stop transfer workers during background controller teardown. |

## Preserved or already covered

- Authentication continues using `MfiTarget.LOCAL`, `offline-mfi/identity.pk8`, and `offline-mfi/certificate.p7b`. `LocalMfiAuthenticationClient`, `DiPlayBootstrap`, authentication preferences, and runtime-asset packaging are unchanged. The port introduces no hardware-chip requirement or certificate picker.
- Do not derive hotspot credentials from the accessory certificate: LoopLink's packaged certificate is shared across installations. Keep device-specific Wi-Fi identities and the existing configuration memory instead.
- LoopLink already has configurable music buffering, decoder fallback/recovery, audio-stream isolation, and video/audio/touch diagnostics. Retain these implementations instead of replacing them with upstream's buffer slider, media renderer, or graph overlays.
- Keep LoopLink's optional-tunnel behavior: missing type-130 control must not tear down a session that has rendered video. The upstream optional-handshake change is already covered by this local behavior.
- CH341 timing changes already match the inspected upstream implementation. No chip-driver or MFi-authenticator changes are needed.
- Preserve LoopLink branding, settings, package IDs, and pinned Android build tools. Concentus is the only new runtime dependency; the upstream Media3 service is not needed because LoopLink already owns its media session.

## Validation

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :automotive:lintDebug \
  :mobile:assembleDebug :automotive:assembleDebug
python3 scripts/check_public_tree.py
git diff --check
```

The tests cover microphone SETUP/teardown, negotiated rates and RTP timestamps, resampling across partial reads, audio-mode ownership, real software Opus and stalled-encoder fallback, HEVC/NAL validation, missing-configuration recovery, incremental metadata, artwork assembly, and control-session deadlines. Existing certificate-provider and LoopLink regressions also run.

Result: all 301 unit tests pass (245 shared, 56 common); both debug APKs build; mobile and automotive lint pass with zero errors and four warnings each. The public-tree check and `git diff --check` pass. Neither debug APK contains runtime identity assets.

These are source/CI debug builds. No authentication assets were supplied for this validation. No iPhone, head-unit, microphone hardware, or driving-session validation was performed. Physical follow-up should check wired and wireless Siri/calls, music plus navigation, metadata/artwork, reconnect, and a session lasting longer than five minutes.
