# Design: your sister's setup, Huawei tablet & platforms

> Status: proposal v0.4.
> Your sister has **her own lamps**, on the **same Wi-Fi**, and will use her **Huawei tablet**. iOS is parked (no Mac, Apple's developer fee).
> Context: [architecture.md](architecture.md) · [groups-and-scenes.md](groups-and-scenes.md) · [phase 0 guide](../guides/phase-0-onboarding.md)

## 1. Your sister's setup

**Two separate setups that happen to share a Wi-Fi network.**

```
Wi-Fi ─┬─ 💡💡 your lamps (+ strip)  ◄── your phone (only has your keys)
       └─ 💡   her lamps             ◄── her tablet  (only has her keys)
```

- **Each app only knows the devices it has keys for.** Her tablet never connects to your lamps and vice versa, so the one-connection-per-lamp limit never causes conflicts.
- **Discovery hears everyone's devices** on a shared Wi-Fi. Devices without a key are listed under *Other devices on this network* (hidden by default), so she won't see your lamps cluttering her app.
- **Getting her keys:**
  - **Recommended: her own Smart Life account.** She moves her lamps out of the BARDI app into *her* Smart Life account. She can use her iPhone for this, since Smart Life is on the App Store, or the tablet (Smart Life is reportedly on Huawei AppGallery). Then you run the key fetch on your laptop with *her* User Code, she scans the QR with her Smart Life, and you send her tablet the export.
  - **Shortcut: her lamps in your Smart Life account**, as a separate "home" in Smart Life. One key fetch covers everyone's lamps. Her tablet imports only hers (the import screen lets you pick devices). The downside is that her lamps then live in your account. If she wants Smart Life's remote control, you'd invite her as a member of that home.
- **Want to control each other's lamps someday?** *Share devices* exports selected devices (keys encrypted with a passphrase) to the other app. Polite mode then kicks in for shared lamps (release ~20 s after the last touch). This is optional and comes later. The mini-PC hub would make it seamless.
- **Later, with the hub:** one mini PC can serve both of you as two separate homes, so you each get automations and remote control.

## 2. Huawei tablet checklist

**First, check the OS:** *Settings → About tablet*.

| Shows | Runs our app? |
|---|---|
| **EMUI** or **HarmonyOS 2 / 3 / 4.x** | ✅ yes. These are Android-based and install APKs. Reports say global MatePads stay on 4.x for this reason |
| **HarmonyOS 5 / 6** ("HarmonyOS NEXT") | ❌ no. NEXT dropped Android app support (mostly China-market devices) |

What this means for the app design:

| Huawei reality | Design response |
|---|---|
| **No Google Play services** | the app uses **no** Google services (no Firebase/FCM, Play Integrity, Maps, ML Kit). It only talks to devices on the LAN, so nothing is lost |
| No Play Store | install the **APK directly** (allow "install unknown apps" for the file manager/browser once). Updates: send a new APK, or an optional in-app "check for update" from the GitHub release |
| Aggressive battery manager | doesn't matter in the foreground. If widgets/tiles act sluggish, set the app to *App launch → Manage manually*. **[test]** |
| Third-party Quick Settings tiles / home-screen widgets on HarmonyOS 4 | widgets should work like stock Android. Tiles in Huawei's Control Panel are **[test]**. Widgets and launcher shortcuts are the fallback |
| No Material You wallpaper colours | the app uses a chosen accent colour instead |
| **Big screen** | adaptive layout (below) |

## 3. Tablet layout & dashboard mode

Compose adaptive layouts, so one app works on both:

```
Phone (compact)                 Tablet (expanded, landscape)
┌───────────────┐               ┌──────────────┬──────────────────────────────┐
│ Scenes        │               │ Scenes       │  Lamp: Lampu Kamar           │
│ Favourites    │               │ ─────────    │   ◉ ON   [White|Colour|Effect]│
│ Rooms ▾       │   tap →       │ Bedroom      │        ╭──────────╮          │
│  …            │  full-screen  │  💡 Kamar ●  │        │  colour  │  ━━━●━ 60%│
│               │   detail      │  💡 Meja  ○  │        │  wheel   │  swatches │
└───────────────┘               │ Living  ▸    │        ╰──────────╯          │
                                └──────────────┴──────────────────────────────┘
```

- **List + detail side by side** in landscape, a bigger colour wheel, and scene buttons sized for tapping from a distance.
- **Dashboard mode** (tablet option): a full-screen grid of scenes and lamps, screen kept on while charging, dimmed clock. Turns the tablet into a wall/desk control panel. Fits since a tablet mostly stays at home anyway.

## 4. iOS (parked)

What we found, so it's ready if it ever comes back:
- **Technically fine:** iPhones can talk to Tuya lamps over the LAN. The plan would be moving `core` + `tuya-lan` to Kotlin Multiplatform and the screens to Compose Multiplatform (stable on iOS since May 2025), plus small native Swift parts (widgets, Control Center, Siri/Shortcuts).
- **The blockers are cost and tooling, not code:**
  - Building needs **Xcode on a Mac** (or cloud Mac builds).
  - Installing without a 7-day expiry needs the **Apple Developer Program, $99/yr** (TestFlight builds last 90 days and auto-update). The free route is re-installing every 7 days.
- **iOS quirks to remember:** the Local Network permission prompt, the need for a special Apple entitlement for UDP broadcast discovery (use known IPs / a TCP sweep instead), and widget/control actions running in an extension process (needs testing).
- **Why it's not ruled out:** `core` and `tuya-lan` are written without Android APIs, so the move to KMP later is mechanical.
