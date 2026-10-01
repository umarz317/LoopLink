# Test checklist

Use the [installation guide](INSTALL.md). With the car parked, verify wired and wireless connection, picture, touch and music. Test disconnect/reconnect, then settings Apply/Cancel. Save a diagnostic report after reproducing an issue.

For channel memory, connect until authenticated CarPlay renders, disconnect and reconnect without changing the car's Wi-Fi association. Look for `remembered saved` followed by `remembered first`. Report absent events; creating a hotspot alone is insufficient.

Include head-unit model, DiLink/Android, iPhone/iOS, wired/wireless, app version and exact steps. Do not post credentials or unreviewed personal information. See [compatibility](COMPATIBILITY.md) for remaining limitations.

For a renderer comparison, open Settings → Advanced → Display and performance → Video rendering.
Compare Compatibility (TextureView, the existing default) with Efficient (SurfaceView), using the
same resolution, frame rate, connection and animated map/scrolling sequence. Check picture,
touch, settings overlays, resizing/rotation, and returning from Home or the reversing camera.
Diagnostics record the saved preference and the renderer used by the host. An emulator UI check
does not establish head-unit decoder compatibility or a measured performance improvement.
