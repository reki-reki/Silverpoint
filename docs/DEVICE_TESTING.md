# Device testing

A 30-minute walk through the app on a phone: the order to test in, and what "working" looks like. Use it before a release, or to report how Silverpoint behaves on your phone. Start at the top, because later steps depend on earlier ones.

**Install:** a release APK, or a debug build from the latest run in the repository's **Actions** tab (artifact `silverpoint-debug-apk`). Installing over an older version keeps your settings.

## 1. Diagnostics first (5 minutes)

The gear (top left) → **Diagnostics** → **Run diagnostics**, pointed at a well-lit scene. Then **Save JSON**.

- [ ] Verdict starts with `GO`, and the GPU self-test says **PASS** for all **four** cases: recipe, "every recipe control", neutral, black and white.
- [ ] Each case shows `gainMapLuminanceDiff` as a small number (not "not available").

If any self-test case fails, report it before anything else. It means the GPU and the reference disagree, and photos may look wrong.

## 2. Viewfinder (5 minutes)

- [ ] The viewfinder is **upright and not stretched**, with the recipe visible (pick Vivid Slide or Mono Yellow Filter: the difference is obvious). If it is sideways or stretched, turn off Settings → *Live recipe preview*, note it, and carry on.
- [ ] **Quick looks row** (under the lenses): tapping a starred look changes the preview within about half a second and its name shows briefly over the picture; long-press un-stars it. The circles show your scene through each look, moving smoothly (live, about 30 fps).
- [ ] **Look selector** (button right of the shutter): the grid shows your scene live through every look, smooth while you move the phone and while you scroll; the tabs (Starred, All, Core, Community, Film stocks, B&W) filter it; the star keeps a look in the quick row; tapping a look uses it and closes the sheet. Switching tabs never moves the sheet or the tabs (B&W with 2 looks is as tall as All). With the selector open, the viewfinder behind it should not stutter.
- [ ] **Live look previews off** (Settings → Viewfinder): the selector and quick row show a drawn landscape through each look instead of your scene.
- [ ] **Fit:** in Photo mode only the ± button and the lens pill sit on the picture; the quick looks row, PHOTO · PRO and the shutter row are below it. If anything overlaps differently, attach a screenshot of the camera screen.
- [ ] **Top bar:** tap Flash, Timer, Shape or the aids icon: the bar turns into its choices; picking one (or ✕) turns it back. Aids (grid, level, histogram, zebras, peaking) toggle and light orange; the histogram shows top left, zebras stripe bright areas, peaking outlines sharp edges in red.
- [ ] **Shapes:** 4:5 and 1:1 show a band of the picture with black above and below; 9:16 fills a taller viewfinder (sides cropped) with the controls fading over its bottom. Nothing of the picture shows through the top bar. Tap to focus still lands where you tap in each shape.
- [ ] **Haptics:** lens and mode changes tick; the shutter clicks; you feel a click and a lighter echo when a photo is taken and when tap-focus locks; an error knocks twice; Settings switches click. Settings → Haptics off silences them all. If nothing is felt with it on, note the phone's Settings → Sounds and vibration → Vibration intensity values.
- [ ] **Settings text is readable** (light on dark) and the large "Settings" title shrinks into the top bar as you scroll.
- [ ] With the level on, hold the phone sideways (landscape): the level line stays parallel to the real horizon and turns green when level.

## 3. Photos (10 minutes)

For each shot, open it from the thumbnail and check it in Samsung Gallery.

- [ ] **Daylight, main lens, Reportage:** looks like 0.1.2 did (same brightness and colours) plus a little grain.
- [ ] **Three other recipes** (for example Vivid Slide, Natural Portrait 400, Mono Yellow Filter): each looks like its description; no colour blotches or banding.
- [ ] **Each lens:** 0.6, 1, 3, 5. Switching takes about a second; photos are upright and sharp.
- [ ] **Tap to focus** on something close, then shoot: it is sharp, and the square turned green.
- [ ] **Indoor, low light** with merge on Auto: the badge says "merging 6 frames…", and Settings → Last photo gives the merge time. Compare noise with Settings → Multi-frame merge → Off, same scene.
- [ ] **Something moving** (a hand waving) in low light: no ghost copies.
- [ ] **Ultra HDR:** on the S25's screen, a photo with bright sky or lamps should look brighter in Samsung Gallery than the same photo with Settings → Ultra HDR off.
- [ ] Take three photos quickly: the shutter is ready again at once, and an orange ring with a count runs round the gallery thumbnail until they are saved.
- [ ] Take a photo and leave the app straight away: it still appears in the Gallery. Also try swiping Silverpoint away from Recents right after a merged shot: the photo still appears and the "Developing photos" notification goes away.
- [ ] **Flash:** set Flash On in a dim room and shoot: you see a pre-flash and then the flash; the photo is lit, single frame (no "merging" badge). Flash Auto in daylight does not fire; in the dark it does. Flash Off never fires.
- [ ] **Shapes in photos:** shoot 1:1, 4:5 and 9:16 (portrait), then 9:16 holding the phone sideways: the saved photos are square, 4:5, 9:16 and 16:9, showing the middle of the frame, and still look brighter on HDR screens (Ultra HDR). With "Also save the DNG" on, the DNG keeps the whole sensor.
- [ ] **First-launch path:** phone Settings → Apps → Silverpoint → Permissions → Camera → Don't allow. Open Silverpoint, tap *Allow camera*, then take a photo: it saves normally, and tap to focus works.

## 4. Pro mode (5 minutes)

Tap **PRO** above the shutter (or swipe the viewfinder to the left). Tap **PHOTO** (or swipe right) to go back. Pro's settings are round buttons (EV, ISO, SPEED, FOCUS, WB); tap one for its dial, tap again to put it away.

- [ ] **Dial motion:** tapping a setting swaps the lens pill for its dial in one smooth movement; tapping another setting swaps dials in place; tapping the same one again brings the lenses back. Nothing jumps or shows twice.
- [ ] **Dials:** dragging moves the scale under the centre mark; you feel a light tick per third of a stop, a firmer one on whole stops (labelled), a bump at the ends; a fling travels and settles on a stop. AUTO / RESET next to the value returns to automatic.

- [ ] ISO and shutter show live values while on auto.
- [ ] Set ISO 100 and 1/60: the preview gets darker or brighter accordingly, and the photo's details in Gallery (ⓘ) show ISO 100 and 1/60 s.
- [ ] Set only the shutter (1/1000) and shoot: the photo is about as bright as on auto (ISO raised to match).
- [ ] Focus dial: the preview's focus moves; with peaking on, the red edges follow.
- [ ] White balance 3000 K under daylight: the photo turns blue; switch the dial to TINT and set +30: it turns magenta.
- [ ] Timer at 2 (top bar → timer → 2 s): counts down with a tick each second, then shoots; pressing the shutter during the countdown cancels.

## Reporting results

Open a *Device report* issue (or a *Bug report* for a single problem) with:

- the Diagnostics JSON from step 1;
- screenshots or a screen recording of anything that looks wrong, and which step it was;
- Settings → Last photo for one single shot and one merged shot (develop and merge times);
- your phone model, One UI version and the app version (Settings shows it under the title).
