# Grease Pencil — the signature in every app

Each app can look completely different. These few things stay the same, so anyone who uses two of
your apps can tell they come from the same person.

## The four rules

1. **The loop is in every app icon.** Draw it round the icon's main object, or as a small loop in a
   corner. It's the hand-drawn red circle that overshoots where it started: never a perfect circle.
2. **The loop draws itself in once per app,** at the moment that matters most: a task finished,
   something saved, a thank-you. Only one such moment per app, so it stays special.
3. **Every Settings screen ends with the promise line:** the small loop, the app name and version,
   then that app's honest promises, e.g. `SWEEPY 1.0.0 · NO INTERNET · NO ADS · NO ACCOUNT`.
   Only promise what's true for that app.
4. **The pencil red is the accent:** `#D4432A` on light backgrounds, `#E5553A` on dark ones.
   Other colours can change from app to app; this one appears somewhere in every app.

## The voice

How the apps talk is part of the signature too:

- Plain words, short sentences. Say what happens: "Nothing goes until you say so."
- Warm and a little playful, never pushy: "Boarding passes, memes, that one recipe."
- No exclamation-mark hype, no guilt-trips, no fake urgency, no "Oops!".
- No numbered sections ("01", "02") or other filler decoration.

## What's in this folder

| File | What it's for |
|---|---|
| `GreasePencil.kt` | Copy into any Compose app. The loop, its draw-in animation, `Modifier.circled()` and `PromiseLine`. Needs only Compose UI and foundation. |
| `GreasePencilStudio.kt` | The studio logo (`StudioLockup`, `StudioMonogram`) for the end of Settings. Needs `GreasePencil.kt` and the fonts below. |
| `fonts/` | Instrument Serif (regular and italic) and Geist Mono, which the logo is set in, with their licences (SIL OFL: free to bundle in apps; keep the licence files with the app). |
| `studio/` | The studio logo as PNG files: Play developer page icon and header, and the full logo, each in light and dark. |
| `grease_pencil_loop.xml` | The loop as an Android vector drawable, for icons and splash screens. |
| `grease_pencil_loop.svg` | The same loop for Figma, Inkscape or any design tool. |

## Using the code

```kotlin
// The once-per-app moment: the loop draws itself in.
GreasePencilLoop(Modifier.size(120.dp))

// Circle a word or a picture (drawn behind it, never through the letters).
Text("found", Modifier.circled())

// The end of Settings.
PromiseLine(listOf("No internet", "No ads", "No account"), version = "1.0.0", appName = "Sweepy")
```

`GreasePencilLoop(key = something)` replays the animation whenever `something` changes.

## The end of every Settings screen

```kotlin
StudioLockup(ink = textColor, fonts = studioFonts)            // see the top of GreasePencilStudio.kt
Spacer(Modifier.height(14.dp))
PromiseLine(listOf("No internet", "No ads"), version, appName = "App name",
    color = mutedTextColor, showLoop = false,
    style = TextStyle(fontFamily = studioFonts.mono, fontSize = 10.sp, letterSpacing = 0.8.sp))
```

The logo keeps the studio's red in every app and every theme; only its text follows the app's colours.

## Starting a new app with Claude

Copy this whole folder into the new project (for example into `docs/grease-pencil/`), then paste:

> This app is by Grease Pencil Studio. Read `docs/grease-pencil/README.md` and follow its four rules
> and its voice guide. Add `GreasePencil.kt` and `GreasePencilStudio.kt` to the app (change the package
> to the app's), copy the three fonts from `fonts/` into `res/font/` and their licence files into the
> app's licences, and end the Settings screen with `StudioLockup` and a `PromiseLine` of this app's
> true promises. Use the pencil red as the app's accent. Put the loop in the app icon. Pick one moment
> in the app where the loop draws itself in. Everything else about the look can be the app's own.
