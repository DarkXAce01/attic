# Attic

Bring old printed photos into your phone. Point the camera at a print or a whole album page; Attic finds
every photo, flattens and straightens it, and saves a clean full-resolution copy.
Free forever, no ads, no internet permission. Nothing leaves your phone.

See [PLAN.md](PLAN.md) for the product plan and roadmap.

## Status: Phase 1, "Scan & keep"

**Scan**
- Live outlines around every print in view (several per album page). They lock and freeze once you hold
  still; the shutter ring fills, then it captures by itself and waits for the next page
- A live strip showing each print flattened, then the real saved crops right after a capture
- Detection that finds prints on striped, flowered and dark pages, under glare, with or without white
  borders, and tells a page full of prints from a photo with rectangles in it
- Prints are found by a small neural network trained for exactly this (see `ml/`), on the phone
- Full-resolution capture: the network takes a second, close look at each print in the full photo so
  no sliver of album page is left at the edges, its sides are fitted to the real edges (sub-pixel), then
  it's flattened. Soft (blurry) shots are flagged right away
- Glare-free mode for glossy prints and plastic sleeves: take the page once, then move the phone a
  little left and a little right when asked. Attic takes a shot each time you hold still, lines the
  shots up, and wherever one has a reflection it uses another shot where that spot is clear. The rest
  of the print comes from the first shot unchanged, so nothing gets soft. Fixing the crop later redoes
  the merge. In normal mode Attic says when it sees glare
- Scans go into the album you pick at the top of the scan screen, one capture per page

**Keep**
- Page details: after each page, "Details" by the shutter gives the whole page its when, who and where
  in one sheet, with the previous page's answers one tap away. Scanning never waits for it; pages
  without details say so in the album, so you can do them later
- Albums, page by page. Swipe left and right through an album's photos. Album dates that undated
  photos inherit
- Dates the way people say them: "summer 87", "about 1985", "early 90s", "14/7/1998", "'98 7 14"
- People (with suggestions from everyone you've tagged), place, caption
- Fix crop on the untouched original with a magnifying loupe, or snap to edges. Rotate
- Enhance, on by default: fading and colour casts undone, blacks back, local contrast and gentle
  sharpening that leaves flat areas and grain alone. No AI, nothing invented. Natural or Strong, or the
  scan exactly as taken; hold the photo to compare
- Every print also goes into `Pictures/Attic/<album>` as a high-quality JPEG with its real date,
  people, place and caption written into the EXIF, so Google Photos and Immich sort it correctly.
  Edits update the gallery copy in place
- Originals are never modified; all edits are redone from them

## Build and run on your phone

**Android Studio (the usual way):** File → New → Project from Version Control →
`https://github.com/DarkXAce01/attic`. After that, each update is: **Git → Pull**, let Gradle sync,
then **Run** with your phone connected.

**From WSL:**

```sh
source scripts/env.sh          # JDK, Android SDK and Gradle cache already on this machine
./gradlew testDebugUnitTest    # geometry, tracking, and the detector on synthetic album pages
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

The detector tests write overlays of what they found to `app/build/detector/`.

## Phase 0 test protocol

Scan 30 real prints from the family albums: glossy and matte, black and white and colour, curled, inside
plastic sleeves, one per shot and four or more per page, daylight and indoor light.
**Go bar:** at least 90% need no manual correction, crops are sharp enough to print at 2× size, and the
live outlines keep up at 15+ fps on a mid-range phone.

## License

[GPL-3.0](LICENSE). You're free to use, study, modify and share Attic, but modified versions must stay open
source under the same license.
