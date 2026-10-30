# Album

Bring old printed photos into your phone. Point the camera at a print or a whole album page; Album finds
every photo, flattens and straightens it, and saves a clean full-resolution copy.
Free forever, no ads, no internet permission. Nothing leaves your phone.

See [PLAN.md](PLAN.md) for the product plan and roadmap.

## Status: Phase 0 spike

Proving that phone-camera crops can be impeccable before building anything else.

- Live outlines around every print in view (several per album page)
- A live strip showing each print flattened, exactly as it'll be saved
- Auto-capture once everything in view is held still; it waits for the page to change before firing again
- Full-resolution capture: each print is re-detected on the full photo, then each edge is fitted to the
  real edge (sub-pixel), then flattened
- Originals kept untouched in app storage; each print saved as lossless PNG and copied to
  `Pictures/Album` so you can zoom in from any gallery app
- A prints screen with full-size zoom, size in megapixels, and a sharpness score

## Build

From WSL:

```sh
source scripts/env.sh          # JDK, Android SDK and Gradle cache already on this machine
./gradlew testDebugUnitTest    # geometry, tracking, and the detector on synthetic album pages
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the project folder in Android Studio and press Run with your phone connected.

The detector tests write overlays of what they found to `app/build/detector/`.

## Phase 0 test protocol

Scan 30 real prints from the family albums: glossy and matte, black and white and colour, curled, inside
plastic sleeves, one per shot and four or more per page, daylight and indoor light.
**Go bar:** at least 90% need no manual correction, crops are sharp enough to print at 2× size, and the
live outlines keep up at 15+ fps on a mid-range phone.
