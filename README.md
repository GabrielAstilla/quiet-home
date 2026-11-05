# Quiet Home — a Liquid Glass launcher for Google TV

A native Android TV home screen for a TCL Google TV (works on any Android TV 8.0+ device).
Layout follows a console "library" style: top navigation, a big title for the selected app,
a background that follows your selection, and a horizontal carousel of pinned apps.
No ads, no recommendations, no content feeds, no account, no network access.

## What it looks like

```
 HOME  ALL APPS  SETTINGS                                   9:26 PM  ⌁
 ‾‾‾‾
 YouTube  v2.19
 Video app                               Last opened 2 hours ago
                                         Updated 3 days ago

 ┌──────┐ ╔════════╗ ┌──────┐ ┌──────┐ ┌───
 │ card │ ║selected║ │ card │ │ card │ │ …      ← row scrolls; next card peeks in
 └──────┘ ╚════════╝ └──────┘ └──────┘ └───
 (OK) OPEN   (HOLD OK) OPTIONS   (↑) MENU
```

## Updates and Downloader install

Settings › About › **Updates** shows the installed version, then *Check for Updates*, *Download Update* (with progress
and Cancel/Retry) and *Install Update* (Android's own installer asks you to confirm). An optional daily check is off by
default and only notifies. Updates come from the stable releases of the public repository
[GabrielAstilla/quiet-home](https://github.com/GabrielAstilla/quiet-home); no release is published yet. See
[RELEASING.md](RELEASING.md) for the release layout, signing, publishing, and registering a Downloader code that keeps
working for future versions.

## Navigation motion

HOME · ALL APPS · SETTINGS share **one glass pill**. Its two edges are separate springs: the leading edge is stiffer,
so the pill stretches slightly and settles as it slides, with minimal overshoot (damping 0.82). Colour, edge
highlight and shadow blend from "resting on the current page" to "remote focus". The labels never move; their
colour follows the pill. The current page keeps a faint marker while focus is elsewhere, and the pill glides back to it
when you leave the navigation. Page changes use a 260 ms fade with a 3 % slide in the direction of travel.
*Reduced motion* jumps the pill and only crossfades its look; *Off* is instant. Rapid presses retarget the springs from
where they are (verified: after a burst of 8 presses the pill had settled on the right tab within 250 ms).

**Typography note:** the app uses **TeX Gyre Heros**, a free Helvetica-style font, because real Helvetica can't be bundled
without a licence. If you have licensed Helvetica files, import them under Settings › Typography and they're used instead.

## Layout and card sizes

The Home screen is split from top to bottom: navigation and clock, the app title area, the carousel, and a **dedicated bottom area
for the remote guide**. The guide is its own layer pinned inside the TV-safe margin, so the carousel can never push it off screen.
The carousel gets the height that is left, and the card size is **capped to the largest that fits**, counting the focused card's
1.18× enlargement, its lift, ring and shadow. If your chosen card size is too big for the screen, Settings shows
"shown at N (max for this screen)".
On very cramped setups (high density, 135 % text and 5 % margins) the layout compacts gradually: the description goes first,
then the title shrinks, then the names under cards are hidden.
*Screen margins (overscan)* (None, 2 %, 3 %, 5 %) adds a safe border on every edge for TVs that crop the picture.

Checked on the emulator at 1280×720, 1920×1080 and higher densities, with card sizes 144, 208 and 304 dp,
names on and off, 135 % text and 5 % margins. The guide was fully on screen and the carousel ended above it in every case.
**4K wasn't truly tested**: the emulator's display is physically 1920×1080, so a 4K size override only rescales it.
Google TV renders 4K panels at the same 960×540 dp as 1080p, so the layout should match the 1080p results, but check it on the TV.

## Liquid Glass

Glass surfaces: the navigation and clock capsules, the settings panel, app option menus, confirmations,
the image picker, and "saved" chips. Each one draws, from bottom to top:

1. a **pre-blurred, low-resolution picture of what is behind it**, aligned to its position on screen.
   This is an efficient approximation of real-time blur: a 192 px wide copy of the background is made once per
   background change, and panels and menus use a 240 px blurred snapshot of the screen taken when they open.
   Drawing either costs one bitmap draw.
2. a **tint** (Glass tint, Glass opacity),
3. a **soft top reflection** and a **fine light edge** (brighter at the top),
4. a **soft shadow** and rounded clipping from the view outline.

App artwork and text are never blurred. The focused card gets a luminous white-to-accent ring, a faint outer glow,
a restrained reflection across the top of its artwork, a lift, and a soft shadow. All of these animate together.

**Lightweight effects** turns all of this into flat translucent surfaces: no sampling, no glow, no shadows,
and a stronger tint so text stays readable. The same translucent fallback is used when Glass blur is Off.

## Typography

Fonts, in order of preference:

| | Font | Where it comes from |
|---|---|---|
| 1 | **SF Pro** (Display for titles and the clock, Text for labels) | Your own licensed files. **Not included**: Apple's licence only covers mock-ups of software for Apple platforms, so it may not be licensed for Android use at all. |
| 2 | **Helvetica / Helvetica Neue** | Your own licensed files. **Not included**: it is a commercial Monotype font. |
| 3 | **TeX Gyre Heros** | **Built in.** A free Helvetica-style font under the GUST Font License (`app/src/main/assets/fonts/GUST-FONT-LICENSE.txt`). This is what you see by default. |
| 4 | Roboto | The TV's system font, used if the files above can't be read, or when *Apple-style fonts* is off. |

To add fonts you are licensed to use:
```sh
adb push SF-Pro-Display-Semibold.otf /sdcard/Android/data/com.zguy.tvlauncher/files/fonts/
adb push HelveticaNeue-Medium.ttf   /sdcard/Android/data/com.zguy.tvlauncher/files/fonts/
```
Then go to Settings › Appearance › Typography › Font › **Import fonts from folder**. Files are checked before use, so a broken
file is skipped instead of crashing. They are copied into private storage, so deleting the folder afterwards is fine.
Matching names: SF Pro files need `Display` or `Text` plus `Regular`/`Medium`/`Semibold`.
Helvetica files need `Roman`/`Regular`, `Medium` or `Bold` (Bold stands in for Semibold).

Weights used: Regular, Medium and Semibold. TeX Gyre Heros only has Regular and Bold, so Medium shows as Regular and Semibold as Bold.
A **Text size** setting (90–135 %) scales everything. Fallback line spacing is on, so taller fonts never clip.

## Clock & greeting

The greeting sits directly below the clock and uses the TV's local time:

| Time | Greeting |
|---|---|
| 5:00 AM – 11:59 AM | Good morning, Gabriel |
| 12:00 PM – 12:59 PM | Good noon, Gabriel |
| 1:00 PM – 5:59 PM | Good afternoon, Gabriel |
| 6:00 PM – 4:59 AM | Good evening, Gabriel |

It re-checks every minute while Home is visible (system time tick, no polling), on time or time-zone changes,
and when you come back to Home. Changes crossfade, or switch instantly with motion off.
Settings › Appearance › Clock & Greeting has a live preview, plus **Display name** (edited with the TV keyboard; blank means no name and no comma),
Show clock, Show greeting, Name in greeting, Clock format (12 h, 12 h with AM/PM, 24 h) and an optional **Glass backing**.

## Wallpapers

Settings › Appearance › Wallpaper:
- **Browse wallpapers…** opens a thumbnail gallery:
  - **Nature** and **Abstract**: 11 photos from Unsplash under the Unsplash License, bundled at 1920×1080 so they work
    offline. Photographer credits are under Settings › Wallpaper › *Wallpaper credits* and in [CREDITS.md](CREDITS.md).
  - **Quiet Home Originals**: Apple-*inspired* aerial (Coastline, Lagoon, Dunes, Canyon), ocean (Deep Sea), nature
    (Alpine Dusk, Forest Mist, Aurora) and abstract (Bloom, Silk) wallpapers. These are **drawn by this app's code**.
    They are not Apple images, and no Apple assets are bundled.
  - **Imported — your files**: images and videos you copy to the import folder. Use only files you have permission to use,
    including any Apple wallpapers or aerial videos.
- Choosing an image opens the **full-screen preview** (sample title and cards, Fill/Fit/Crop, blur, brightness, dimming, overlay).
  Nothing is saved until you choose **Save**.
- **Type**: Solid colour, Gradient (top/bottom colour, direction), Wallpaper image, or Video wallpaper.
- Per-app backgrounds, the *Change with selected app* toggle, the look settings and **Restore default wallpaper settings** are unchanged.

Importing from the remote: copy files to `/sdcard/Android/data/com.zguy.tvlauncher/files/wallpapers/`
(images: jpg/png/webp; videos: mp4/m4v/mov/mkv/webm/ts). They appear in the gallery automatically.
If a file was copied with restrictive permissions and shows as unreadable, run
`adb shell chmod 644 /sdcard/Android/data/com.zguy.tvlauncher/files/wallpapers/<file>`.

## Video wallpapers & screensaver

Every compatible video in the gallery offers **Preview full screen · Set as wallpaper · Set as screensaver · Use for both ·
Use a still frame as wallpaper**.

- **Compatibility** is checked against the TV's own decoders. Unsupported files are labelled *NOT SUPPORTED*, with the reason
  (e.g. "can't decode HEVC at 3840×2160"). Apple's aerials are usually 4K HEVC and some are HDR / Dolby Vision. If one won't play,
  use an H.264 or SDR HEVC 1080p version.
- **Playback**: silent and looped, on a hardware video surface behind the launcher, so navigation isn't slowed by video frames.
  A still frame shows immediately and crossfades to the video once the first frame renders.
  Brightness and dimming apply to video; blur applies to images and stills only.
- **Paused whenever Home isn't visible**: the decoder is released when another app opens and restarts when you return.
  *Animated (off = still frame)* and *Lightweight effects* both show the still frame instead.
- A missing or broken video falls back to its still frame, or to the colour or gradient if there is no still.

**Screensaver** (Settings › Screensaver): *Start after* (Off, 1–30 min), *Show* (current wallpaper with a very slow drift, or the
screensaver video), clock and greeting overlay, and *Preview screensaver*. It fades in. **Any remote key dismisses it,
and that key is swallowed**, so the card selected behind it never launches by accident. Volume keys still work.

**Google TV restrictions**: Google TV only offers its own Ambient Mode as the system screensaver, and third-party apps can't
add themselves to that list. So Quiet Home's screensaver is a **launcher-only idle mode**: it starts only while Quiet Home is on screen.
Quiet Home also includes an optional system screensaver ("Dream") which some firmware lets you select over ADB:
```sh
adb shell settings get secure screensaver_components          # note the current value first
adb shell settings put secure screensaver_components com.zguy.tvlauncher/.QuietDream
adb shell settings put secure screensaver_enabled 1
# undo: put back the value you noted, e.g.
adb shell settings put secure screensaver_components <previous value>
```
Google TV may ignore this setting. The system Dream is not interactive, so any key simply wakes the TV.

## Features

**Home**
- Hero area: selected app's name, version, short description, and details (last opened from here, last updated).
- Carousel of pinned apps; the selected card springs slightly larger with a light border and soft shadow.
- Background follows the selection: a per-app image you chose, or a blurred version of the app's artwork.
- Remote hints along the bottom; they change in move mode and on the top navigation.

**Remote**
- D-pad, OK, Back, long-press OK (or the Menu key). No touch, mouse or keyboard needed.
- Up from the carousel → top navigation. Down from the navigation → back to the carousel.
- Returning from an app or closing Settings restores the exact card or tab you left.
- Home pressed on Home jumps to the first card.
- Card options (hold OK, or the Menu key) open in a glass menu next to the card: Open · Move · Move to start ·
  Remove from Home · Change artwork… · Reset artwork · Set background… · Remove background · App info.
  Back closes the menu and focus returns to the card.
- OK gives a brief compress-and-release on the card. The app launches on release, without extra delay.
- Move mode: the card lifts, ← → moves it while neighbours slide into place, OK drops it, and a "Position saved" chip confirms.
- No pinned apps: a setup screen with an "Open All Apps" button and short instructions.

**All Apps** — every TV app, alphabetical; hold OK to add/remove from Home, change artwork or background.

**Settings** (slides in from the right; Home stays visible behind it and updates live)
- Background: type (solid colour / gradient / wallpaper image), colour, gradient top/bottom/direction,
  wallpaper image + "Adjust & preview", image position (Fill / Fit / Crop), and
  *Change with selected app*: Off (one background) · Custom images only · Custom + app artwork.
- Background look: brightness, dimming, blur (images), overlay colour, overlay opacity,
  restore default background, remove all per-app backgrounds.
- Glass: Visual effects (Full glass / Lightweight), glass blur (Off / Low / Medium / High), glass opacity, glass tint.
- Cards: size, spacing, corner radius, accent (focus glow), app names, TV banner artwork, remove custom artwork.
- Home screen: clock, network status, app description, app details, control hints.
- Motion: Animations (Full / Reduced / Off) and Animation speed (0.5× – 1.5×).
- All apps: columns, include non-TV apps, reset pinned apps.
- System: Home-button status, Android TV settings, reset everything, version.

**Background preview** — after choosing an image (for the wallpaper or one app) a full-screen preview shows it
behind a sample title and cards. Adjust position, blur, brightness, dimming and overlay with ← →, then **Save**.
Back or **Cancel** discards the image.

**Artwork** — TV banner if the app has one; otherwise a generated card (gradient tinted from the app icon,
icon and name). Any card can use your own image (cropped to 16:9).

## Motion

| Interaction | Animation (Full, 1×) | Interruptible |
|---|---|---|
| Card focus | spring scale + lift (damping 0.8–0.85), ring/glow/reflection fade 150 ms, shadow 180 ms | yes — springs retarget from the current value |
| OK press | compress to −5 % scale, spring back on release | yes |
| Carousel scroll | 260 ms decelerate, keeps selection ~1 card from the left, 1.5 from the right | yes — new press retargets |
| Title / details | fade-out 90 ms → swap → fade-in 180 ms with 6 dp rise | yes — latest selection wins |
| Background | crossfade 320 ms, starts ~60 ms after the title swaps | yes — stale loads are dropped |
| Settings panel | floating glass, 56 dp slide + fade, 320 ms in / 240 ms out, 38 % dim | — |
| App options menu | scale 0.92→1 + fade + 8 dp drift from the card, 260 ms in / 180 ms out, 22 % dim | — |
| Reorder | card lifts, neighbours move 220 ms, "Position saved" chip | — |

*Reduced* keeps only short fades (no scale, no slides, instant scroll). *Off* makes everything instant
and also disables window transitions. *Animation speed* scales every duration and spring.

## Performance

- Kotlin + plain Android Views. Dependencies: RecyclerView and DynamicAnimation (springs). No Compose / Leanback / AppCompat.
- Release APK ≈ 4.6 MB (11 bundled 1080p wallpapers ≈ 4 MB, font ≈ 270 KB). Permissions: `INTERNET` (only for
  update checks and downloads you start, or the optional daily check), `REQUEST_INSTALL_PACKAGES` (to hand an update
  to Android's installer), and `ACCESS_NETWORK_STATE` (status icon). No analytics, no account.
- The app list loads on a background thread as soon as the process starts. Cards are rendered once at the exact size
  and kept in a memory-bounded LRU cache.
- Blur is done once when an image loads, on a shrunken copy (box blur), so it costs nothing per frame.
  App-artwork backdrops are 160×90 pre-blurred bitmaps (~60 KB each).
- Background layers: base, one crossfading image pair, one combined dim/overlay tint, one readability gradient.
- Glass surfaces draw one small pre-blurred bitmap each. Nothing animates while idle, and springs settle and stop.
- No services, jobs or polling. The network callback is registered only while a screen is visible.

## Project layout

```
app/src/main/java/com/zguy/tvlauncher/
  LauncherApp.kt              process start: app list, package receiver, background image cache
  AppRepository.kt            app list, card artwork (custom / banner / generated), blurred backdrops, details, launch
  Prefs.kt                    every setting, option lists, defaults, resets, pinned order
  HomeActivity.kt             hero, carousel, per-app backgrounds, move mode, live restyle
  AllAppsActivity.kt          alphabetical grid
  SettingsActivity.kt         slide-in settings panel
  BackgroundEditorActivity.kt preview-before-save for wallpaper / per-app backgrounds
  PickImageActivity.kt        remote-friendly image picker (Pictures, import folder, file browser)
  BackdropView.kt             layered, crossfading background; publishes the blurred copy used by glass
  Glass.kt                    GlassView, GlassHub (shared blurred background), screen snapshot, focus-ring drawable
  Fonts.kt                    SF Pro › Helvetica › TeX Gyre Heros › Roboto selection, import, sizing
  Greeting.kt, ClockGreeting.kt  time-of-day greeting and clock binding (tested in app/src/test)
  Originals.kt                procedurally drawn Apple-inspired wallpaper collection
  WallpaperGalleryActivity.kt thumbnail gallery for Originals and imported images/videos
  Video.kt                    codec compatibility probe and still frames
  Screensaver.kt              launcher idle screensaver, idle timer, optional system Dream
  GlassMenu.kt                floating glass menus/confirmations and "saved" chips
  AppAdapter.kt               cards with spring focus, border fade, shadow
  TopBar.kt                   tabs, clock, network status
  SettingRows.kt              shared remote-friendly setting rows
  Images.kt                   import, crop, downscale, blur
  Ui.kt                       motion policy, styles, layout managers
```

## Build

Requires JDK 17 and the Android SDK (platform 35).

**Android Studio:** open this folder, let Gradle sync, then *Build → Build APK(s)*.

**Command line** (tools installed with Homebrew: `openjdk@17`, `android-commandlinetools`):
```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (R8, signed with debug key)
```
`local.properties` points at `/opt/homebrew/share/android-commandlinetools`. Prebuilt copies are in `dist/`.
Use the release build on the TV: it is smaller and faster.

## Install on the TCL Google TV (sideload)

### 1. Turn on developer options and debugging
1. **Settings → System → About** → select **Android TV OS build** 7 times until it says you are a developer.
2. **Settings → System → Developer options** → turn on **USB debugging**.
   On Google TV 12+ also turn on **Wireless debugging** if it is listed.
3. Find the TV's IP: **Settings → Network & Internet →** your network.

### 2. Install with ADB (from your Mac)
```sh
brew install android-platform-tools      # provides adb
adb connect 192.168.1.50:5555            # your TV's IP; accept the prompt on the TV
adb install -r app-release.apk           # or app-debug.apk
```
If your TV shows **Wireless debugging** with a pairing code, use
`adb pair IP:PAIR_PORT` with the code, then `adb connect IP:PORT` with the port shown on that screen.

### Alternative without a computer
Install **Downloader** (or **Send files to TV**) from the Play Store, transfer the APK,
and allow *Install unknown apps* for that app when asked.

After install, **Quiet Home** appears in the Google TV **Apps** list. It works fully as a normal app.

## Making it the Home screen

Declaring a Home intent (this app does) **does not** by itself replace the Google TV launcher.
Google TV firmware normally keeps its own launcher and does not show a Home app chooser.
Check what your TV supports, least invasive first. Settings → **Home button** in Quiet Home tells you the current state.

### Option A — Use it as an app (no changes to the TV)
Open it from Apps, or put it first in your Google TV favourites row. Nothing to undo.

### Option B — The system Home chooser (only if your firmware has one)
Some builds have **Settings → Apps → Default apps → Home app**. If you see it, pick Quiet Home.
Undo: pick *Google TV Home* there again. Most TCL Google TV builds do **not** have this.

### Option C — Remap the Home button (no system changes)
A key-remapping app such as **Button Mapper** can use an Accessibility Service to make the Home button open Quiet Home.
It needs the Accessibility permission, which lets that app see button presses.
Undo: turn the mapping off, or uninstall the remapping app.

### Option D — Set the preferred Home activity with ADB
```sh
adb shell cmd package set-home-activity com.zguy.tvlauncher/.HomeActivity
```
This is accepted on many builds but Google TV may still open its own launcher.
If Home still opens Google TV, the TV is ignoring it.
To undo, **first record the stock Home activity** (run this before Option D):
```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
# last line looks like  com.google.android.apps.tv.launcherx/<activity>
```
then set it back with `adb shell cmd package set-home-activity <that line>`.
Uninstalling Quiet Home also clears the preference.

### Option E — Disable the Google TV launcher with ADB (advanced)
**Read first:**
- This disables the stock home screen. You lose Google TV's home features (profiles, recommendations, the Google TV "For you" pages, and some TCL shortcuts that live there).
- Install Quiet Home and open it once **before** doing this. If no Home app works, the TV shows a blank screen on Home. You can still fix it over ADB.
- A system update may re-enable the stock launcher.
- Nothing is deleted. `disable-user` is fully reversible and needs no root.

```sh
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith   # fallback launcher that would appear instead
```
Press Home. The TV may ask which app to use; pick Quiet Home → *Always*.

### Restore the original Google TV home screen
```sh
adb connect 192.168.1.50:5555
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity <stock Home activity you recorded>   # only if you used Option D
adb reboot
```
Always restore **before** uninstalling Quiet Home if you used Option E.
Last resort: a factory reset (Settings → System → About → Reset) restores everything.

To check which package names your TV uses (they can differ by firmware):
```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell pm list packages | grep -i -E "launcher|tungsten|tcl"
```
If they differ from the names above, send me your exact TV model and Android TV OS version
(Settings → System → About) and I'll adjust the steps.

## Getting images onto the TV

Images for wallpapers, per-app backgrounds and artwork can come from:
- **From import folder** (works on every TV, no permission). From your Mac:
  `adb push photo.jpg /sdcard/Android/data/com.zguy.tvlauncher/files/wallpapers/`
  Supports .jpg, .jpeg, .png and .webp. Then choose **From import folder** in the picker.
- **From Pictures on this TV**: lists images in the TV's media library and asks for photo permission once.
  Images only appear there if an app (a file manager, *Send files to TV*, or a USB drive) has put them in a
  scanned folder such as Pictures or Download.
- **Browse files…**: only shown if the firmware includes a system file picker. Many Google TV builds don't,
  which is why the import folder exists.

If a saved wallpaper or per-app image goes missing, that screen falls back to the solid colour or gradient
instead of failing.

## Acceptance checks (Google TV emulator, Android 14)

| Check | Result |
|---|---|
| Pinned apps launch; order kept after force-stop and restart | ✅ |
| Remote navigation: carousel ↔ tabs, menus, settings, picker, preview editor | ✅ |
| Rapid input (bursts of 6–8 presses): lands on the right card, title matches, no delayed movement, no lost focus | ✅ |
| Custom wallpaper, background and glass settings persist across restarts | ✅ |
| Text readable over a dark busy wallpaper and a light wallpaper (overlay, readability gradient, text shadows) | ✅ |
| Focused / moving cards and their shadows not clipped (row padding sized from scale, lift and shadow) | ✅ |
| Reduced motion and Lightweight effects: work, no crashes | ✅ |
| Empty state when nothing is pinned | ✅ |
| Menus open next to the selected card and return focus on close | ✅ |

Bugs found and fixed during testing: image import always failed, the picker closed between steps,
menus appeared at the top of the screen, focus jumped to the tabs after a background restyle,
the focus ring was invisible on white banners, and the empty-state title stayed blank.

### Typography, wallpaper, video, screensaver and greeting (this update)

| Check | Result |
|---|---|
| Greeting follows local time: all 24 hours, the boundaries (4:59/5:00, 11:59/12:00, 12:59/13:00, 17:59/18:00), and no trailing comma when the name is blank or hidden | ✅ unit tests (`./gradlew testDebugUnitTest`) |
| "Good noon, Gabriel" shown below the clock at 12:4x on the emulator | ✅ |
| Typography renders: bundled TeX Gyre Heros (Helvetica-style) on Home, menus, settings, gallery, screensaver | ✅ |
| Layout doesn't clip with the larger clock + greeting (the title rises beside the clock) | ✅ |
| Wallpaper gallery: Originals and Imported sections, thumbnails, video probe, menu for videos | ✅ |
| Video wallpaper plays silently (frames change), is released when another app opens and restarts on return | ✅ (logged start/release) |
| Wallpaper and video settings persist across restarts | ✅ |
| Screensaver: starts after the idle time (started at exactly 60 s with *Start after: 1 min*) | ✅ |
| Screensaver shows the clock + greeting and dismisses on OK without launching the selected app | ✅ |
| SF Pro / Helvetica import from your own files | ⚠️ code path built but not exercised (no licensed files available) |

Bugs found and fixed: the screensaver closed itself on pointer hover; the gallery grid could take focus itself, leaving no tile selected;
the taller clock pushed the hint bar off screen.

## Needs checking on your TCL TV

- **Frame pacing** of springs, glass, crossfades and carousel scrolling on the TV's GPU. The emulator renders through
  the Mac and can't predict this. If anything stutters, try *Visual effects: Lightweight*,
  *Animations: Reduced*, *Blur: Off*, or *Change with selected app: Off*.
- **Cold start**: 0.4 s on the emulator in the final run (it varied 0.2–1.8 s as the emulator slowed down over
  the session). Measure on the TV.
- **Holding a direction**: the emulator can only send separate key events, not true held-key auto-repeat.
- **Long-press OK** timing and the **Menu** key on the TCL remote.
- Which **image sources** your firmware offers (Pictures library, file picker).
- The **Home button** options above, which depend on firmware.
- Video wallpapers with real Apple aerials: 4K HEVC and HDR / Dolby Vision decoding, loop smoothness (Android's player
  can leave a short gap at the loop point), and whether video plus glass stays smooth.
- The screensaver over a full idle period with your remote, and whether the remote's air-mouse wakes it.
- How SF Pro or Helvetica files you import look, if you have licensed copies.
- The optional system screensaver over ADB (Google TV may ignore it).
