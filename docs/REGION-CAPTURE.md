# Two-Hand Pinch Region Capture

> **Status: UNTESTED on hardware.** The app compiles and the unit tests pass
> (`RegionMathTest`, 11 tests), but nothing here has run on a Galaxy XR yet. The
> Android XR emulator has no real hand tracking or world camera, so the feature
> can only be verified on a headset.

The engineer pinches with both hands; the two pinch points are opposite corners of a
rectangle in their view. Releasing locks the frame in the room. **Capture** takes a real
photo with the world-facing camera, crops it to the frame and sends it to the brain as an
ordinary photo (`POST /api/captures`, unchanged).

## How it works

| Step | Implementation | API |
|---|---|---|
| Hand tracking | System hand tracking, enabled only while framing | ARCore for Jetpack XR `Hand.left/right(session).state` (`HAND_TRACKING` permission) |
| Pinch | Thumb tip ↔ index tip: closed under 2 cm, open over 3.5 cm (hysteresis) | `HandJointType.THUMB_TIP`, `INDEX_TIP` |
| Frame | Both pinch points projected along the line of sight onto one plane facing the head, at their mean depth; head roll removed so the frame stays level; anchored in the world | `ArDevice.state.devicePose` |
| Viewfinder | Transparent `PanelEntity` in activity space (Tundra hairline + corner marks, size in cm), with a Capture / Cancel bar under it | SceneCore `PanelEntity` |
| Photo | Existing `DeviceCamera.takePhoto()`. On Android XR the CameraX back camera is the main world-facing camera, so this is the real environment, not a render | CameraX |
| Crop | Frame corners projected into the photo with the head pose at the shutter and the camera intrinsics; handles sensor orientation, EXIF rotation and JPEG aspect crop | Camera2 `LENS_INTRINSIC_CALIBRATION` |
| Brain | Cropped JPEG goes through `vm.onPhotoTaken()` → `LocalBrain.ingest()` | unchanged |

Code: `capture/RegionMath.kt` (geometry, unit-tested), `capture/RegionCapture.kt`
(tracking and phases), `capture/RegionCrop.kt`, `ui/capture/RegionViewfinder.kt`, plus
`DeviceCamera.intrinsics()`.

### Known limitations

- **Camera-to-head offset is unknown.** The platform publishes no extrinsics, so the camera
  is assumed to sit at the head pose and the crop is widened by 8% on each side. Expect
  some offset, larger at close range.
- **No intrinsics → full photo.** If the camera reports no calibration, focal length over
  sensor size is used (`calibrated=false`). If neither is available, or the frame is out
  of the camera's view, the **full photo** is sent and the island says so. It is never
  passed off as a crop.
- **Full Space only.** The menu entry is hidden in Home Space.

## Test on a Galaxy XR

### 1. Set up

```bash
# Terminal 1: the brain on the laptop (keeps `adb reverse tcp:8787` up)
cd server && npm install && npm start        # dashboard: http://localhost:8787

# Terminal 2: headset on USB, developer mode + USB debugging on
./gradlew installDebug
# or without the server: ./gradlew installDebug -Ptundra.demo=true

# Terminal 3: logs
adb logcat -s TundraRegion TundraCamera
```

### 2. Walk through

| # | Do | Expect |
|---|---|---|
| 1 | Launch the app, allow camera + microphone | Full Space, passthrough, the island in front |
| 2 | Expand the island → **Camera** → **Frame a region** | First time: hand tracking permission prompt. Then the status line reads "Pinch with both hands to frame" |
| 3 | Pinch with both hands and pull them apart | A frame appears between the pinches with cobalt corner marks, follows the hands live, shows its size in cm |
| 4 | Release both hands | Frame stays put, corners turn white, Capture / Cancel bar appears under it |
| 5 | Turn your head, take a step | Frame stays fixed in the room |
| 6 | Pinch with both hands again | Frame is redrawn |
| 7 | Tap **Capture** | Frame flashes; island shows `photo N` uploading, then analysis |
| 8 | Start again and use **Cancel** (bar, or the Camera slot that became Cancel) | Frame disappears, island returns to normal |

### 3. Check the crop (the important part)

- Logcat should show e.g. `Region 812x604 of 1920x1080 calibrated=true`.
- Open the new photo on the dashboard: it should contain only what was inside the frame.
- Compare against the full photo kept on the device:

  ```bash
  adb shell run-as com.tundra.tundraxr ls files/captures
  adb shell run-as com.tundra.tundraxr cat files/captures/photo_<ts>.jpg > full.jpg
  adb shell run-as com.tundra.tundraxr cat files/captures/photo_<ts>_region.jpg > region.jpg
  ```

- **Alignment test:** frame a business card or sticker edge to edge, at ~30 cm and ~1 m.
  Note which way and how far the crop is off. A consistent offset means a fixed
  camera-to-head correction needs adding to `RegionMath.crop`.

### 4. Report back

| Symptom | Meaning |
|---|---|
| "No camera calibration · sent full photo" | Device reports no intrinsics; send the `TundraCamera` log |
| "Frame out of camera view · sent full photo" | Frame was outside the camera's field of view |
| "Hand tracking unavailable on this device" / "Hand tracking permission needed" | `session.configure` refused; send the `TundraRegion` log |
| Frame hard to see | Note the background (bright/dark) for an outline tweak |

Unit tests: `./gradlew :app:testDebugUnitTest`.
