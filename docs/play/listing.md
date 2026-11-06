# Google Play listing (draft)

**App name:** Attic: photo album scanner

**Short description (80 max):** see `fastlane/metadata/android/en-US/short_description.txt`

**Full description:**

Your family's photos are sitting in albums in a cupboard. Attic brings them into your phone, a whole page
at a time.

Point your phone at an album page. Attic finds every print on it, even five or six at once, straightens
them, and saves each one as its own photo, sharp and full size. Hold still and it takes the shot for you,
then waits for the next page.

Glossy prints and plastic sleeves catch the light? Turn on Glare-free: take the page, move the phone a
little left and right, and Attic keeps the clear part of each shot.

Then the stories. Give a whole page its date, names and place at once ("summer 87", "about 1985" and
"the 80s" all work). Attic puts every photo on a timeline, decade by decade.

Old prints fade and turn orange. Attic gently undoes that, without inventing anything; the photo exactly as
scanned is always one tap away.

Everything goes into your gallery too, with its date and names written into the file, so Google Photos and
other apps sort it properly. Back up to an SD card or a USB stick from Settings.

No internet, no account, no ads, no subscriptions. Attic has no internet permission at all: your family's
photos never leave your phone. Free and open source.

**Category:** Photography

**Tags:** photo scanner, album, old photos, digitize, family

**Data safety form:** no data collected, no data shared. (Attic has no internet permission.)

**Contact email:** portgasdjewel@gmail.com

**Privacy policy URL:** https://github.com/DarkXAce01/attic/blob/main/docs/play/privacy-policy.md

**Store text, screenshots, icon and feature graphic** are in `fastlane/metadata/android/en-US/` (the
layout F-Droid reads, and the one Play's tools upload from). Screenshots and the feature graphic are drawn
by `AppShots` with the Unsplash test photos:
`./gradlew :app:testDebugUnitTest --tests '*AppShots*' -Proborazzi.test.record=true`, then copy
`app/build/shots/app_store_*.png`, `app_feature.png` and `store_icon.png` across.

**Data safety form:** no data collected, no data shared. Camera, microphone and photos permissions are
used on the device only.
