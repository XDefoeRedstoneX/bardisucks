# The Android app (v0.2)

What it does:

- **Lamps:** on/off, brightness, white tone (warm ↔ cool), colour wheel, saved colours. The lamp follows your finger while you drag, and the lamps report back, so changes made elsewhere (Smart Life, the wall switch coming back on) show up.
- **Groups:** "All lamps" is built in; make your own with **New group**. A group has the same controls as a lamp, **including colour and brightness**, and every lamp changes at the same moment. Optional per group: *keep the difference between lamps* when dimming.
- **Scenes (new in 0.2):** one tap sets several lamps or groups at once. Each step can switch on or off, set brightness, a white tone or a colour, and start the lamp's own **turn-off timer** (it keeps counting even if your phone is off). Three styles:
  - **Normal:** a tap applies it.
  - **Toggle:** a tap applies it, the next tap puts everything back as it was (e.g. "Movie").
  - **Cycle:** each tap moves to the next look (e.g. Bright → Warm → Night → Off), great on a Quick Settings tile.
- **One tap without opening the app (new in 0.2):** six **Quick Settings tiles** (scene, or switch a lamp/group), scene **shortcuts** when you long-press the app icon, and **Add to home screen** for any scene.
- **Phone and tablet:** on a wide screen (tablet, landscape) the home screen stays on the left with the controls next to it.

Not in yet: home-screen widgets, bulb effects, the power strip, automatic lamp discovery. Control only works while the phone is on the home Wi-Fi.

## Install

1. Get `bardisucks.apk` onto the phone or tablet. Either the file sent in the chat, or from GitHub: **Actions → the latest "Build" run → Artifacts → bardisucks-apk** (a zip with the APK inside; needs a GitHub login).
2. Open the APK. Android asks once to allow installing from that app (file manager / browser): allow it, then **Install**. Play Protect may warn that it doesn't know the app: choose **Install anyway**.
3. Updating later is the same: install the newer APK over the old one. Lamps, names and groups are kept.

## Add your lamps

The app needs the `secrets\devices.json` file from your PC (made by `get_keys.py`, with the IP addresses that `probe.py` saved). It contains your lamps' keys, so treat it like a password.

1. Copy `devices.json` to the phone: USB cable, Quick Share / Nearby Share, or your own cloud drive.
2. In the app: **Import devices file** and pick it. The app stores the keys encrypted with a key that can't leave this phone.
3. **Delete the file from the phone** (Downloads etc.) afterwards.

Your sister does the same on her tablet with *her* file (`devices-sister.json`).

## Using it

- **Home:** tap a tile to open its controls, tap the round button to switch it. A group's button turns everything off if anything is on, otherwise everything on.
- **Controls:** *White* / *Colour* tabs switch the lamp's mode. Drag the brightness or white-tone bars, or the colour wheel. Tap a saved colour to use it, hold it to remove it, **+** saves the current colour.
- **Pencil (top right):** rename a lamp or fix its IP address; for a group: members, name, the keep-difference option, delete.
- **Settings (gear):** lamp list, import again.

## Scenes

- **First time:** tap **Add starter scenes** for six ready-made ones (Bright, Evening, Night light with a 30-minute timer, Movie as a toggle, All off, and a Cycle). Edit or delete them freely.
- **Run:** tap a scene. **Edit:** hold it.
- **Make one:** **New scene** → name, style, then one step per lamp or group. **Use how lamps look now** fills the steps from the lamps' current state, so the easiest way to make a scene is: set the lamps the way you like, then New scene → Use how lamps look now → Save. **Try it** shows a look on the lamps without saving.
- In a step, **Keep** leaves that part as the lamp has it (e.g. only change brightness, keep the colour).
- Toggle and cycle positions are remembered while the app runs; after a phone restart every scene starts from its first look.

## Quick Settings tiles and shortcuts

1. In the app: **Settings → Quick Settings tiles**, tap a tile, pick a scene (or a group/lamp to switch).
2. Add it to the pull-down panel: tap **Add** next to it (Android 13+), or open the panel, tap the pencil, and drag "Bardi 1" etc. in.
3. A tile shows the scene's name, and lights up while a toggle scene is on or a lamp/group is on. A cycle tile shows the next look.

For scenes, long-press the app icon to see shortcuts for the first four, or in a scene's editor tap **Add to home screen**.

The first tap after a while takes a second: the phone has to connect to the lamps first. Opening the panel already starts connecting, so usually it's quick.

## If something's off

| What you see | What it means / what to do |
|---|---|
| **Connecting…** that turns into **Offline** | The app can't reach the lamp: lamp has no power, phone isn't on the home Wi-Fi (mobile data doesn't work), or the lamp's IP address changed. For the last one: run `py tools\phase0\probe.py --no-tests` on the PC to see the new address, then pencil → IP address. Reserving the lamps' addresses in the router prevents this |
| **Import again to use** | The app's data was moved from another phone, where the keys were encrypted. Import the devices file again |
| A lamp reacts, then the app says it **didn't respond** | The lamp didn't confirm within 3 seconds, usually weak Wi-Fi at the lamp. The app reconnects by itself |
| The lamp was removed and re-added in Smart Life | Its key changed. Run `get_keys.py` again and import the new file |
| Tiles or shortcuts do nothing on the Huawei tablet | HarmonyOS may not support them. Tell me what happens; the app itself is unaffected |
| On **Android 17**, everything stays Offline | Tell me. The app deliberately targets Android 16 so the new local-network permission shouldn't apply, but that's untested |

## For development

- `./gradlew :app:assembleRelease` builds `app/build/outputs/apk/release/app-release.apk` (needs the Android SDK; without one the `:app` module is skipped so the CLI still builds).
- `./gradlew :app:testDebugUnitTest` runs the UI tests on the PC: the real screens against fake lamps, plus screenshots of every screen in `app/build/outputs/roborazzi/`.
- Every build is signed with the shared key in `app/signing/` so it can update any other build. See [its README](../../app/signing/README.md) for what that does and doesn't mean.
