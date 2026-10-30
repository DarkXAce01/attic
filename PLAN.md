# Attic: Product & Build Plan

Name: **Attic**, where the old albums are found. Repo: github.com/DarkXAce01/attic (public).
Started 2026-10-30. Android first, Kotlin + Jetpack Compose. Free forever, no ads, nothing leaves the phone.

---

## 1. The vision

> Point your phone at an old album page. Attic finds every print, flattens it, removes the glare, reads the
> date stamped in the corner, and asks "who's this?". Then it rebuilds the album as a book you can hold and
> flip on your phone, with your mother's voice telling the story behind each photo.
> Later, it becomes the gallery for all your photos, old and new, on one timeline.

### Is it viable? Yes.
- **There's a real gap.** Photomyne is the most complete option but makes you pay to save files. Google
  PhotoScan is stale: one photo at a time, and it stamps the wrong dates. Microsoft Lens was removed in Feb 2026.
  The free croppers only crop. Nothing free covers **scan → remember → relive**.
- **Free forever is affordable.** Everything runs on the phone and there's no server, so each user costs about
  nothing. As with Sweepy, the app can skip the `INTERNET` permission entirely, which makes the privacy
  promise something you can prove.
- **The hard part is known.** Crop quality from a phone camera decides whether this works, so Phase 0 proves it
  before anything else gets built.
- **Main risk: scope.** The features below are a whole world. They ship in layers (§8), and every layer has to
  be a complete, lovable app on its own.

### Principles
1. **Originals are sacred.** The raw capture is never modified. Every crop, fix, and restoration is a recipe
   stacked on top that you can undo, compare, or remove.
2. **What you see is what you get.** The viewfinder shows the final crop before you press anything.
3. **Real dates, not scan dates.** A fuzzy date ("summer '87", "1990s") beats a precise wrong one, and it's
   written into the file so it survives leaving the app.
4. **Stories, not files.** Faces, voices, handwriting, and places are what make a photo matter, so the app
   captures them as first-class data.
5. **Calm UI, theatrical albums.** The app's own screens stay quiet so the photos carry the color. All the
   drama (paper, leather, page curls, light) lives inside the albums.

---

## 2. Features

Tags: **[MVP]** first release · **[v1]** public launch · **[v2]** after launch · **[Moon]** ambitious, later.

### A. Scan: digitizing that feels like magic

| Feature | Tag | Notes |
|---|---|---|
| **Live multi-print detection** | MVP | Outlines appear around every print in view, even 4–6 on one album page. |
| **Live result strip** | MVP | Flattened, color-corrected thumbnails of each detected print update live at the bottom of the viewfinder. This is "the camera shows exactly how it'll turn out". |
| **Auto-capture** | MVP | Fires when the phone is steady, every edge is locked, and the frame is sharp. No shutter tap. |
| **Sharpness & quality check** | MVP | Each crop gets a score. Blurry or too-low-resolution crops get a calm "retake this one" before you move on. |
| **Manual corner fix** | MVP | Drag the corners with a magnifier loupe. Meant to be rare. |
| **Album-page mode** | MVP | One shot of a page splits into N prints *and* saves the page layout, which feeds Rebuilt Albums (D). |
| **Hands-free page turning** | v1 | Phone on a stand or a stack of books above the album. The app notices when you turn the page and scans the new one. You just turn pages. Turns a 3-hour job into a 30-minute one. |
| **Glare-free sweep** | v1 | Glossy prints and plastic sleeves reflect light. Sweep the phone slowly across and the app merges the frames, keeping only glare-free pixels. Live hotspot warnings in the viewfinder ("tilt a little"). |
| **Curl & bend correction** | v1 | Old prints curl. The app straightens slightly bent prints and pages, not just flat rectangles. |
| **Flip-side capture** | v1 | After a scan, "flip it over?". The back (handwriting, studio stamps, dates) is attached to the print. |
| **Date-stamp reader** | v1 | Reads the orange date printed in the corner by 90s cameras ('98 7 14) and the photo lab's date on the back. |
| **Polaroid & border-aware cropping** | v1 | Keeps white borders and handwritten Polaroid captions as part of the print, with an option to crop to the image only. |
| **Film negatives & slides mode** | v2 | Lay negatives on a laptop or tablet showing a white screen (the app has a "lightbox" screen for a second phone). Attic inverts the negative and removes the orange film base color. Rescues photos that were never printed. |
| **Flatbed import** | v2 | Bring in scanner images (TIFF/PNG); they're split and flattened the same way. |
| **Session progress** | MVP | "Nani's red album · page 14 · 61 prints". Resume any time. Makes a 1,000-photo job feel finishable. |

### B. Remember: the stories behind the prints

| Feature | Tag | Notes |
|---|---|---|
| **Quick-tag sheet** | MVP | After each page: when (exact / month / year / decade / "around"), who, where, which album. One thumb, minimal typing, remembers your last answers. |
| **Fuzzy dates** | MVP | "About 1987" sits in the timeline as a soft band, not a fake Jan 1. Written to EXIF/XMP. |
| **On-device face grouping** | v1 | Tag Grandpa once and he's suggested in 200 photos. |
| **Ages, worked out** | v1 | Add a birth year for a person and every photo shows "Dad, about 23". Unknown dates can be *estimated* from tagged ages ("Mom looks about 8 here, so about 1972"). |
| **Family tree** | v2 | Link people (parent, sibling, spouse). Browse by family branch; see who's missing photos. |
| **Voice stories** | v1 | Hold any photo and record a memory. Audio is attached forever and plays from the album page. |
| **Story sessions** | v1 | "Interview mode" for grandparents: the app shows photos one by one, full screen, records them talking, and splits the audio per photo automatically. Feels like a podcast with your family. |
| **Handwriting & captions** | v1 | Text read from backs and album margins becomes searchable captions. |
| **Era hint** | v2 | When there's no date, the app suggests a decade from print size, border style, paper and color fade. Always shown as a suggestion, never stored as fact. |
| **Places & then-and-now** | v2 | Pin a photo to a place. Stand in the same spot today and the camera overlays the old photo so you can line it up; Attic makes a before/after slider. |
| **Family help pack** | v2 | No server needed. Export a "help me tag" pack over WhatsApp or Nearby Share. A relative tags faces and dates on their phone and sends it back; Attic merges the answers. |
| **Duplicate finder** | v1 | Catches the same print scanned twice, or one that's already in your phone gallery. |

### C. Revive: restoration, honestly

All optional, never automatic. Always shown with an original/restored slider, and labeled "restored" everywhere.

| Feature | Tag | Notes |
|---|---|---|
| Fade & color cast fix | MVP | Classic image processing, instant, on-device. Most 80s/90s prints have gone orange or magenta. |
| Dust & scratch removal | v1 | Detects and fills specks and fine cracks. |
| Upscaling | v2 | Real-ESRGAN family, for small prints you want to enlarge. |
| Face restoration | v2 | GFPGAN (Apache-2.0). **Not CodeFormer**: its license is non-commercial. |
| Colorize black & white | Moon | Clearly marked as an interpretation. Never replaces the original. |

### D. Albums: the world inside the app

| Feature | Tag | Notes |
|---|---|---|
| **Rebuilt albums** | v1 | From album-page scans, recreate the *physical album your parents made*: same layout, same order, their original page as the background. Tap a print and it lifts off the page at full quality. |
| **3D book** | v1 | Real page curl with paper bend, shadow and light falling across the page. Pages have weight; flick fast and several turn at once. |
| **Tilt light** | v1 | Glossy prints catch a soft reflection that moves as you tilt the phone (gyroscope). Matte pages don't. Small effect, huge sense of realness. |
| **Album themes as materials** | v1 → v2 | Start with **one theme done perfectly**, then add: *Leather-bound* (gold-stamped spine), *Cloth & corners* (photo corner mounts), *Spiral scrapbook* (tape, notes, ticket stubs), *Shoebox* (a loose pile you rummage through with physics, pick a print up, flip it over to read the back), *Slide tray* (a projector-style carousel, click-clack between slides), *Contact sheet* (film strips on a lightbox, with a loupe). |
| **Flip a print** | v1 | In any album, flip a photo over to see its scanned back and handwriting. |
| **Voice on the page** | v1 | A small mark shows a print has a story; tap to hear it while the page stays open. |
| **Auto-layout** | v1 | Pick photos and get pages arranged by their proportions and importance, with no faces cropped. Fully editable. |
| **Smart albums** | v2 | Built automatically: "Mom through the years", "Our house in Delhi", "Diwali, every year", "1990s". |
| **Decade timeline** | v1 | A scrubber through 70 years of the family, with each person's age shown as you scroll. |
| **Slideshow & TV** | v2 | Ken Burns-style slideshow with voice stories playing over it. Cast to the TV for the family. |
| **Shareable album file** | v2 | Export an album as one offline web page (opens in any browser) or a print-ready PDF photobook. Share by WhatsApp, Drive or a USB stick, no account needed. |

### E. Gallery: your everyday photos too

| Feature | Tag | Notes |
|---|---|---|
| Camera roll on the same timeline | v2 | Indexes MediaStore, no copies. Old scans and today's photos together. |
| On-device smart search | v2 | "beach", "birthday cake", "grandma in red". Small image–text model, fully offline. |
| On this day | v2 | "38 years ago today" mixing scans and phone photos. Plus a home-screen widget. |
| People across both | v2 | The face groups from scans also find the same people in modern photos. Grandpa at 30 and at 80. |
| Default gallery app | Moon | Can replace the stock gallery: viewer, editor, share, trash. |

### F. Keep: never lose it

| Feature | Tag | Notes |
|---|---|---|
| Backup health | MVP | "These 812 photos exist in only 1 place". The goal is 3 copies, shown simply. |
| Full-quality export | MVP | Originals + edits as PNG/max-quality JPEG with all metadata (dates, people, captions) embedded in standard EXIF/XMP fields, so it survives without the app. |
| Export to Immich / Google Photos / a drive | v1 | Through Android's share sheet and storage picker, so the app still needs no internet permission. |
| Legacy package | v2 | One folder for a USB stick: every photo, every voice story, and an offline viewer that opens in any browser. For relatives without the app, and for the future. |

---

## 3. Navigation & key flows

**Bottom bar, four tabs:** **Scan · Albums · Timeline · People.** Search sits at the top of Timeline;
backup, export and settings are behind a single menu.

**First-run flow (under 60 seconds to the first wow):**
1. One screen: "Bring your old albums to life. Nothing leaves your phone." Camera permission.
2. Straight into the viewfinder with a one-line hint. Point at any print.
3. The first print lifts off the screen into a fresh album, flattened and clean. That's the hook.

**Scanning flow:** viewfinder → auto-capture → quick-tag sheet (skippable) → next page. Tagging never blocks
scanning; untagged prints wait in an "Answer later" pile.

---

## 4. Design direction (taste-skill rules, adapted for Compose)

taste-skill (github.com/Leonxlnx/taste-skill) is written for web pages, but its rules don't depend on a
framework. We apply them to Compose, and **Roborazzi screenshots play the part of its browser check**: render
every screen at phone size, critique against the rules, fix the top three problems, and repeat for up to three
rounds.

**The idea that drives the look:** *the app is the lightbox; the albums are the objects.* App screens are a
quiet, neutral surface for viewing photos. Paper, leather, light and texture belong only to the albums and
prints themselves.

**Rules for the app's own screens:**
- True neutrals (not cream), in light and dark. Colour comes from the photos; one accent, used only on the
  primary action and on data.
- One typeface with tabular figures for the UI. A second face only if it has one clear job (e.g. album titles).
- Sentence-case labels at readable sizes. **No** tiny tracked-uppercase or monospace micro-labels, no
  `01 / 02` section numbering, no rulers or tick-mark decoration, no hairline grids.
- No gradients, glows, or glass. Opaque surfaces one tone step apart. Shadows only on things that float.
- Each screen answers one question first, and its biggest element answers it. (Albums: "what do I want to
  open?" → the albums themselves, big, as objects. Scan: "is it seeing my photos?" → the viewfinder.)
- Real photos in every screenshot and store graphic (CC0 sample prints in the test fixtures), never gradient
  placeholders.
- **One signature motion:** a scanned print lifting off the camera view and settling into its album. Everything
  else is quick and quiet. No fade-up on every element, no bounce easing.

**Avoid the "warm editorial" look that AI tools now default to:** cream background with a terracotta/rust
accent, an oversized italic serif headline, mono labels everywhere. That warmth belongs *inside* the albums,
not on the app's own screens.

---

## 5. Tech stack

**Native Kotlin + Jetpack Compose, Android first**, the same toolchain as Sweepy (`~/projects/swipe`).
WSL already has the Android SDK, Gradle and signing set up. Sweepy is a reference to read and copy patterns
from; this project never edits it.

| Need | Choice | Why |
|---|---|---|
| UI | Kotlin, Jetpack Compose, Material 3 (heavily restyled) | Proven in Sweepy |
| Camera | CameraX: `Preview` + `ImageAnalysis` + high-resolution `ImageCapture` | Smooth preview while detection runs on frames |
| Print detection | OpenCV (Android SDK) for edges and perspective; a small LiteRT segmentation model for messy album pages | OpenCV is great for one clean print; album pages with patterned backgrounds and plastic sleeves need ML |
| Glare-free sweep | OpenCV frame alignment + per-pixel minimum/median merge | Same idea as PhotoScan, done live and guided |
| Text (dates, handwriting) | ML Kit text recognition (`play` build) / Tesseract (`foss` build) | Offline; open-source build for F-Droid |
| Faces | ML Kit face detection + a small face-embedding model (LiteRT) | Grouping fully on-device |
| Search | Small CLIP-style image–text model (LiteRT) | v2 |
| Restoration | LiteRT / ONNX Runtime with permissively licensed models | Watch every model's license |
| Page curl & tilt light | Compose `graphicsLayer` + AGSL `RuntimeShader` (Android 13+), flat page-slide fallback for older phones | GPU page curl, no game engine |
| Shoebox physics | A small custom 2D physics step in Compose (few bodies) | Avoids pulling in a game engine |
| Audio | Media3 (record and play voice stories) | Same library as Sweepy uses for video |
| Database | Room | Same as Sweepy |
| Images | Coil 3 + a custom tile decoder for zooming into large masters | Masters can be 20+ MP |
| Metadata | `androidx.exifinterface` + XMP writer | Dates/people/captions survive export |
| UI tests | Roborazzi screenshots + Robolectric | The taste check loop |
| Builds | `play` and `foss` flavors | F-Droid needs the all-open-source build |

**Big models vs. no internet:** ML models can add a lot of APK size. Use **Play Asset Delivery**
(on-demand asset packs downloaded by the Play Store, so the app still needs no internet permission) for the
heavy restoration models. The `foss` build ships them in a separate optional APK or leaves them out.

**minSdk:** 29 or 30 (to be decided in Phase 0, based on CameraX/AGSL needs and your family's phones). AGSL effects need Android 13+.

---

## 6. Data model (first draft)

```
print            -- one physical photo
  id, created_at
  original_uri         (raw capture, never modified)
  recipe_json          (crop quad, rotation, color fixes, restorations, in order)
  rendered_uri         (cached output of the recipe at full resolution)
  back_uri             (flip-side scan, nullable)
  date_from, date_to, date_label   (fuzzy dates: "about 1987" = 1986-01-01..1988-12-31)
  date_source          (EXACT | DATE_STAMP | BACK_TEXT | AGE_ESTIMATE | ERA_GUESS | USER)
  place_id, caption, back_text
  quality_score, phash

page_scan        -- one capture of an album page, used for Rebuilt Albums
  id, album_id, page_number, original_uri, layout_json (each print's quad on the page)

album            -- physical or digital
  id, title, kind (REBUILT | DIGITAL | SMART), theme, cover_print_id, created_at

album_slot       -- a print placed on a page of an album
  album_id, page, print_id, x, y, w, h, rotation, z

person           -- with optional family links and birth year
  id, name, birth_year, birth_year_fuzzy
person_link      -- parent/child/spouse/sibling
face             -- detected face, embedding, assigned person
  id, print_id, box, embedding, person_id, confirmed

story            -- voice memory
  id, print_id, audio_uri, duration, recorded_at, speaker_person_id, transcript
```

---

## 7. Phase 0: prove the hard part (2–3 weeks)

A throwaway app with just a viewfinder. Find out whether phone-camera crops can be *impeccable*.

1. CameraX preview + OpenCV quad detection on analysis frames; draw the outlines live.
2. Capture at full resolution, map each quad onto the full-res frame, warp it flat, and save as PNG.
3. A live result strip of flattened thumbnails.
4. Test set: **30 real prints from your family albums.** Glossy and matte, B&W and color, curled, inside
   plastic sleeves, 1 per shot and 4+ per page, daylight and indoor light.
5. Compare each crop against a careful manual crop.

**Go bar:** at least 90% of prints need no manual correction, crops are sharp enough to print at 2× size, and
detection runs at 15+ fps on a mid-range phone.
**If OpenCV alone misses it on album pages:** add the segmentation model in Phase 1 rather than giving up.

---

## 8. Roadmap

| Phase | Length (part-time, with Claude) | What ships | Done when |
|---|---|---|---|
| **0 · Spike** | 2–3 wks | Viewfinder, live detection, flat full-res crops | The go bar in §7 is hit |
| **1 · MVP "Scan & keep"** | 6–8 wks | Multi-print scan, live strip, auto-capture, quality check, quick-tag sheet, fuzzy dates, simple library (by album / year), color fix, backup health, export with metadata | You've digitized one complete family album with it and would use it for the rest |
| **2 · v1 "Relive"** (public launch) | 8–10 wks | Rebuilt albums, 3D book with one perfect theme, tilt light, flip a print, voice stories + story sessions, hands-free page turning, glare-free sweep, date-stamp reader, faces & ages, decade timeline | Family members who aren't you open it *for fun* |
| **3 · v2 "Everything"** | ongoing | More album themes (Shoebox, Slide tray…), gallery mode, smart search, On this day + widget, family tree, help packs, then-and-now, shareable album files, TV, negatives mode, upscaling & face restore | |
| **Moon** | — | Default gallery app, colorization, iOS | |

**Launch:** Google Play + F-Droid, "free forever, no ads, no internet permission" as the headline. Optional
donations that unlock nothing.

---

## 9. Open decisions
1. ~~Name~~: decided, **Attic**.
2. ~~Open source~~: decided, public from day one (GPL-3.0, like Sweepy).
3. **minSdk.** Depends on the oldest phone in your family that should run it.
4. **First album theme.** Rebuilt (your parents' real pages) is a given. Of the materials, which one do you
   want perfect first: Leather-bound, Cloth & corners, Scrapbook, or Shoebox?
5. **When to start Phase 0.** It needs the 30 test prints photographed or ready, and a phone connected over adb.
