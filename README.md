# ASHIRU AIR TOUCH

Native Android hands-free phone control using the front camera, MediaPipe Hand Landmarker, CameraX, and an explicitly enabled Android Accessibility Service.

## Smart gesture controls

- **Live finger trail:** a floating, fading cursor follows the index fingertip over other apps.
- **Pinch to tap:** bring thumb and index finger together. The pinch must remain stable across multiple camera frames; the threshold is scaled to palm width to reduce sensitivity to camera distance and hand size.
- **Index-only scroll:** keep the index finger raised and the other three fingers folded, then move the fingertip up or down. The app ignores sideways drift and small movements.
- **Open palm pause:** an open hand pauses gesture actions.
- **Noise rejection:** smoothed coordinates, stable-frame checks, cooldowns, gesture release hysteresis, and a one-hand-only detector reduce accidental actions.
- **Background camera service:** a foreground notification indicates that camera tracking is active and provides a Stop action.

## Permissions and first use

1. Allow Camera permission.
2. Allow Display over other apps so the finger trail can be drawn above other apps.
3. Enable **ASHIRU AIR TOUCH** in Android Accessibility settings. This is required for tap and scroll gestures.
4. Return to the app and press **START AIR TOUCH**.
5. Open another app and test with your hand in front of the front camera, with good lighting.

## Build

The GitHub Actions workflow downloads the MediaPipe Hand Landmarker model into Android assets, then runs `gradle assembleDebug`. Open the repository's **Actions** tab and download the `ASHIRU-AIR-TOUCH-debug` artifact.

For a local build, download the model to `app/src/main/assets/hand_landmarker.task` from:

`https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`

## Important limitations

- The app uses MediaPipe's pretrained hand-landmark model plus deterministic gesture filtering. It does **not** train itself on personal behaviour or read the meaning of arbitrary gestures.
- Camera tracking needs the front camera to remain available. Android may stop camera access after force-stop, permission revocation, or some battery-saving actions.
- Lock-screen gestures are intentionally not included.
- Gesture accuracy depends on lighting, camera placement, hand visibility, and the device's camera performance.
- Accessibility permission is powerful. The service only dispatches the tap and swipe gestures implemented in this app; enable it only if you trust this build.

## Technology

- Kotlin, AndroidX, CameraX
- MediaPipe Tasks Vision Hand Landmarker in VIDEO mode
- Android foreground service and WindowManager overlay
- Android AccessibilityService gesture dispatch
