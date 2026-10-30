# Attic: notes for Claude

- Product plan and roadmap: `PLAN.md`. Current phase is in `README.md`.
- Kotlin + Jetpack Compose, Android only, `minSdk 29`. Build with `source scripts/env.sh` first (the JDK,
  SDK and Gradle cache live in `~/.cache/sweepy-build`).
- `~/projects/swipe` (Sweepy) is the user's other app. Read it for patterns, **never edit it**.
- The user installs builds on their phone as we go, and every change gets committed to git.
- Vision code (`vision/`) keeps geometry in plain Kotlin so it can be unit tested. OpenCV-backed code is
  tested against synthetic scenes with the desktop OpenCV build (`DetectorTest`).
- UI follows taste-skill rules adapted for Compose (PLAN.md §4): true neutrals, one accent (`Palette.Accent`,
  only for "ready" and the main action), sentence-case labels, no mono micro-labels, no 01/02 numbering, no
  hairline/ruler decoration, no gradients or glass. Photos carry the colour.
- No INTERNET permission, ever.
