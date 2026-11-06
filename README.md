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
- Hands-free: put the phone on a stand over the album and just turn pages. After a capture Attic waits
  until the page in view shows different photos (not just a different layout), then takes it. A hand
  passing over the page doesn't count
- Prints scanned twice are caught: right after the capture ("1 looks like one you already have") and on
  the photo, with a way to jump to the other copy. Works whichever way up the print was scanned
- Glare-free mode for glossy prints and plastic sleeves: take the page once, then move the phone a
  little left and a little right when asked. Attic takes a shot each time you hold still, lines the
  shots up, and wherever one has a reflection it uses another shot where that spot is clear. The rest
  of the print comes from the first shot unchanged, so nothing gets soft. Fixing the crop later redoes
  the merge. In normal mode Attic says when it sees glare
- Check, like a document scanner (on by default): each shot shows the outlines Attic found on the photo,
  and you can drag a corner or a whole side (with a magnifier under your finger), snap one to the
  edges, add one it missed or remove one, then save or retake. Glare-free pages are checked on the
  first shot and the changes carried over to the others. Switch it on the scan screen or in Settings
- The camera fills the screen with the album, the modes and what's happening floating over it; the
  album so far sits bottom left as a little pile of its last prints
- Scans go into the album you pick at the top of the scan screen, one capture per page
- From files: flatbed scans or photos of prints already on the phone, split and flattened the same way
- Negatives: film laid on a lightbox (or another phone showing Attic's Lightbox screen) is developed
  into the picture, the orange of colour film taken out. Fixing the crop develops it again
- Date stamps: the orange date many film cameras printed in the corner ('87 7 14) is read from every
  print, whichever way up it was scanned, and fills in the date when the photo has none. A stamp that
  reads two ways (3 5 '88) asks which day it is. Prints already in Attic are read in the background

**The attic** (design phase, first pass)
- Albums float as small islands in a dark attic, one beam of light and dust drifting through it. Each
  is a book: a cloth binding coloured from its own photos, the cover photo set into it, paper and print
  edges layered underneath like rock, a few of its photos drifting around it, a pencil-red ribbon
- Tap one and it comes up to you and falls open
- Inside, every page is rebuilt as it was: the photo of the page underneath, each print laid back in
  its exact spot and tilt, clean and flat. Pages turn over on their spine. Tap a print to open it
- Pencil red (Grease Pencil Studio's colour) is the one accent

**People**
- Faces are found on every photo on the phone (YuNet and SFace from the OpenCV model zoo), and shown
  under Who. Tap one to say who it is; once someone is named Attic suggests them on other photos
- People tab: the same unnamed face across several photos comes up as one group to name at once
  (tap any that don't belong), then everyone named, most photographed first
- A person's page: every photo of them, oldest first, with how old they were in each once you add
  when they were born ("1952" is enough)

**Remember**
- Stories: record a spoken memory on any photo; play it back from the photo. Long-press to delete
- Story sessions: "Tell its stories" on an album shows its photos one at a time, big, while the phone
  listens. Whatever is said while a photo is up becomes its story; photos nobody talked about get none.
  Made for sitting with a grandparent
- On an album page, a print with a story has a small mark: tap it to listen with the page open.
  Long-press a print to turn it over to its back
- Stories are in backups (and next to each photo in Photos/ as an m4a anyone can play)

**Share**
- Share a photo, or a whole album, through the share sheet: copies carry their date, people, place and
  caption. Attic sends nothing itself
- Save an album as one web page that opens in any browser without the internet: pages rebuilt as they
  were, every photo's details, its back, its stories to listen to. Or as an A4 photobook PDF to print
- Slideshow of an album or a person: each photo drifts slowly in toward its faces; a photo with a
  story stays up while the story plays. Cast the screen to a TV to watch together
- Family tree: on someone's page, add their parents, children, partner, brothers and sisters, and Attic
  draws the tree a generation to a row. Relatives without photos yet are drawn faintly
- On this day, over the timeline: photos from this day in other years, or this week, or this month, and
  when nothing is dated near today, one photo for the day
- Then and now: stand where an old photo was taken, line it up over the camera and take it again. Attic
  matches what hasn't changed to lay today's photo exactly over the old one; slide between the two
- Ask the family: one web page of the faces nobody has named and the photos with no date or place. A
  relative opens it in any browser, fills in what they know and sends back a small answers file; Attic
  reads it in without overwriting anything already filled in
- Phone photos on the timeline, if you want them: today's photos alongside the old scans, read where they
  are in the phone's gallery, never copied. On this day mixes them in too

**Keep**
- Timeline: every photo from every album by decade and year, oldest first. Rough dates sit after their
  decade's years; undated photos come last
- Backup to a folder you pick (SD card, USB stick, a synced folder): photos with their details for anyone,
  plus everything Attic needs to restore. Backing up again only adds what changed. Restore from it on a
  new phone. The albums screen says how many photos have no other copy, with the fix next to it
- Settings: the look new scans start with, saving to the gallery on or off, backup and restore
- Page details: after each page, "Details" by the shutter gives the whole page its when, who and where
  in one sheet, with the previous page's answers one tap away. Scanning never waits for it; pages
  without details say so in the album, so you can do them later
- Albums, page by page (see The attic). Swipe left and right through an album's photos. Album dates that undated
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
- Curled prints come out straight: when a print lifts off the page its sides photograph as bows, so
  each side's real edge is traced and the picture is mapped from the four curved sides onto a
  rectangle. Prints whose edges can't be seen clearly are left as before. A switch in Settings
- Borders: a print with a plain white, cream or black border (a Polaroid's too) says so, and one tap
  trims it to the picture, or puts it back. Writing on a border is noticed; Settings can trim plain
  borders of new scans automatically, never one with writing on it
- Dust, specks, hairs and small scratches are painted out of the Natural and Strong looks: only small or
  thin things that stand out well above how busy the picture is around them, never on a face. Original
  stays exactly as scanned. A switch in Settings
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

**Release builds.** Signing reads `keystore.properties` at the project root (never committed); without it,
release builds are unsigned, so anyone can build from source.

```sh
./gradlew bundleRelease                         # the Google Play bundle: app/build/outputs/bundle/release/
./gradlew assembleRelease -Pabi=arm64-v8a       # one signed APK to install by hand (about 31 MB)
```

Release builds are shrunk with R8. The release app is `io.github.darkxace01.attic`, separate from the
debug build (`….attic.debug`), so the two keep separate libraries. Play listing and privacy policy:
`docs/play/`.

## Phase 0 test protocol

Scan 30 real prints from the family albums: glossy and matte, black and white and colour, curled, inside
plastic sleeves, one per shot and four or more per page, daylight and indoor light.
**Go bar:** at least 90% need no manual correction, crops are sharp enough to print at 2× size, and the
live outlines keep up at 15+ fps on a mid-range phone.

## License

[GPL-3.0](LICENSE). You're free to use, study, modify and share Attic, but modified versions must stay open
source under the same license.
