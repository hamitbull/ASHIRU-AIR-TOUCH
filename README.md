# ASHIRU AIR TOUCH

Native Android phone control with a camera-based Air Mouse and a laptop-style floating touchpad. Uses CameraX, MediaPipe Hand Landmarker, Android WindowManager overlays, and an explicitly enabled Android Accessibility Service.

## Modes

### Air Mouse
- The floating, fading cursor follows the midpoint between thumb and index fingertips over other apps.
- Pinch thumb and index together for one click.
- Move the hand upward/downward to scroll the foreground app.
- Open hand is an active scrolling pose, not a pause command.

### Floating Touchpad (v0.6.0)
- Start **FARA FLOATING TOUCHPAD** from the app, then tap the floating ⌖ bubble to open the touch surface over any foreground app.
- One-finger movement moves the on-screen pointer; a tap clicks; a quick second tap produces a second click for double-click behavior.
- Hold, move, and release to send a drag gesture.
- Two-finger vertical movement sends a scroll gesture.
- The touchpad can be hidden while its floating bubble remains available. Stop the service from its persistent notification.
- The pointer is a drawn overlay cursor and taps/drags/scrolls are dispatched through Android Accessibility. It is not a hardware mouse device, and some protected/system screens may block injected gestures.

## Permissions and first use

1. Allow **Display over other apps** for both floating modes.
2. Enable **ASHIRU AIR TOUCH** in Android Accessibility settings. This is required to send taps, drags, and scrolls to other apps.
3. For Air Mouse only, allow Camera permission and press **FARA AIR MOUSE**.
4. For Touchpad mode, press **FARA FLOATING TOUCHPAD**, then tap the floating ⌖ bubble to open the pad.
5. Test in another app. Keep the device awake and allow the service notification while using it.

## Build

The GitHub Actions workflow downloads the MediaPipe Hand Landmarker model into Android assets, then runs `gradle assembleDebug`. Open the repository's **Actions** tab and download the `ASHIRU-AIR-TOUCH-debug` artifact.

For a local build, download the model to `app/src/main/assets/hand_landmarker.task` from:

`https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`

## Important limitations

- Android Accessibility gesture dispatch simulates touch gestures. It does not create a native system mouse pointer or guarantee PC-style right-click/hover in every app.
- Protected screens, some games, banking apps, lock screens, and system UI may reject overlay input or accessibility gestures.
- Android battery management may stop background services. Camera tracking needs the front camera to remain available.
- The Air Mouse uses MediaPipe's pretrained hand-landmark model plus deterministic gesture filtering. It does not train itself or understand arbitrary intentions.
- Gesture accuracy depends on lighting, camera placement, and device performance.
- Accessibility permission is powerful. The service only sends the gestures implemented in this app; enable it only if you trust this build.

## Technology

- Kotlin, AndroidX, CameraX
- MediaPipe Tasks Vision Hand Landmarker in VIDEO mode
- Android foreground services and WindowManager overlays
- Android AccessibilityService gesture dispatch
