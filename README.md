# ASHIRU AIR TOUCH

Native Android app for camera-based hand gesture interaction.

## Implemented
- Kotlin Android app and CameraX front-camera preview.
- Runtime camera permission.
- MediaPipe Hand Landmarker configured to detect one hand.
- Index-finger tip cursor overlay in the camera preview.
- GitHub Actions debug APK build.

## Build
The workflow downloads the MediaPipe Hand Landmarker model into Android assets, then runs `gradle assembleDebug`. Open the repository's **Actions** tab and download the `ASHIRU-AIR-TOUCH-debug` artifact.

For a local build, download the model to `app/src/main/assets/hand_landmarker.task` from:
`https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task`

## Current limitation
The cursor currently moves inside the camera preview only. It does not yet control other apps or inject screen touches. Next milestone: pinch/open-palm/vertical-swipe gestures and an optional Accessibility Service that the user explicitly enables.
