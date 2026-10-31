# Design: iOS & sharing with a second person

> Status: proposal v0.3. Added because your sister (iPhone) wants in.
> Context: [architecture.md](architecture.md) · [groups-and-scenes.md](groups-and-scenes.md)

## 1. Short answer

**Yes, iOS works.** iPhones can talk to Tuya devices over the LAN like Android can. The plan changes from "Android app in Kotlin" to **one Kotlin Multiplatform codebase** that builds both apps:

| Part | Shared? |
|---|---|
| Tuya LAN protocol (`tuya-lan`) | ✅ 100 % shared (plus small platform bits for sockets/crypto) |
| Devices, groups, scenes, effects, colour math (`core`) | ✅ 100 % shared |
| Screens (Home, Lamp, Group, Scene editor, Strip, Settings) | ✅ shared via **Compose Multiplatform** (stable on iOS since 1.8.0, May 2025) |
| One-tap surfaces (tiles/controls, widgets, shortcuts, Siri) | ❌ native per platform (small) |
| Installing the app | ❌ very different (§5). **This is the real cost of iOS** |

So most of the work is done once. What iOS really adds is **distribution** (Apple account, Mac/CI) and a few iOS-native pieces.

## 2. Is she in your house or her own?

This decides how sharing works. The app supports both:

### A. Same house, same lamps

- **Shared "home" vs. personal stuff:** the app splits its data into
  - **Home config:** devices + keys, channels, rooms, groups, scenes, effects, palettes. Shared.
  - **Personal:** dashboard layout, favourites, tile/widget/control bindings, theme. Each person's own.
- **Sharing in v1 (no server):** *Settings → Share home* makes an export file (or QR on screen for small homes), with keys encrypted by a passphrase. She imports it via AirDrop/Files. If you add a scene later, share again. The import merges by id and shows what changed. It's a bit manual, but fine for 2 people.
- **The one-connection rule:** each lamp accepts one LAN connection at a time. Both apps release connections when backgrounded, and with two people the app also releases a lamp **~20 s after the last touch** ("polite mode") and reconnects on the next tap (~0.2–0.5 s). If both of you hit the same lamp at the same instant, one app gets "busy" and retries. **[test]** Phase 0 Step 7 checks what the bulb does with a second connection.
- **This is the strongest reason for the mini PC:** with a hub, it holds the only connection, both phones talk to the hub, scenes sync automatically, and both of you get remote control.
- **Cloud fallback for her:** you can also add her as a member of your home in Smart Life, so she has remote control through Tuya's cloud until the hub exists.

### B. Her own place, her own lamps

- She gets her **own** Smart Life account and does her own [phase 0](../guides/phase-0-onboarding.md). You'd run the key fetch for her on your laptop (it's a QR scan with her phone).
- Nothing is shared. Her app has her home only.
- (Later, the app could hold several homes. Not needed now.)

## 3. One-tap surfaces: Android ↔ iOS

| Android | iOS equivalent | Min iOS |
|---|---|---|
| Quick Settings tiles (6 slots) | **Control Center controls** (also usable on the **Lock Screen** and the **Action button**) | 18 |
| Glance home-screen widgets | **Interactive WidgetKit widgets** (Home Screen + Lock Screen) | 17 |
| Launcher shortcuts | Home Screen **quick actions** (long-press icon) | — |
| Deep links (`bardisucks://…`) | same URL scheme | — |
| Tasker / NFC via deep link | **App Intents → Shortcuts, Siri, Spotlight**. "Hey Siri, movie mode." Shortcuts automations on **NFC tag**, **time of day**, or **arriving home** | 17 |

The iOS row has a nice bonus: **Shortcuts personal automations** can run a scene at a set time or when she gets home, **without the hub**. The phone has to be on home Wi-Fi at that moment, so it's not as reliable as the hub's automations, but it's a free "mini automation" until then. On Android, Tasker or Routines can do the same through deep links.

Planned min version: **iOS 17** (iPhone XS/XR or newer). Control Center controls light up on iOS 18+.

## 4. iOS-specific constraints

| Constraint | Effect on the design |
|---|---|
| **Local Network permission** (iOS 14+) | The app asks once ("Allow bardisucks to find devices on your local network"). If she taps *Don't allow*, nothing works, so onboarding explains it first and links to Settings. Needs `NSLocalNetworkUsageDescription` |
| **UDP broadcast/multicast needs a special entitlement** (`com.apple.developer.networking.multicast`) that you request from Apple | We **don't depend on discovery on iOS**: device IPs come from the import file + DHCP reservations. If a device moves, iOS does a quick **TCP 6668 sweep** of the subnet (plain unicast, no entitlement) to find it again. Request the entitlement later if it's ever needed |
| App gets **suspended** right after leaving the screen | connections close when backgrounded (same as the Android plan, just faster) |
| Widgets/controls run their action **in the widget extension process** | the extension does connect → send → close itself, using the shared `tuya-lan` code and the shared Keychain. **[spike]** confirm an extension can reach the LAN under the app's Local Network permission (test from a TestFlight build, since Xcode-launched apps get the permission auto-granted) |
| Keys | stored in the **iOS Keychain** in a shared access group so the app and its widget/control extensions can read them |
| Material You (wallpaper colours) | Android-only. On iOS the app follows light/dark mode with your chosen accent colour |

## 5. Getting the app onto her iPhone

Android is easy: install the APK. iOS has no free, permanent way to install an app outside the App Store (outside the EU):

| Option | Cost | Lasts | For her | Verdict |
|---|---|---|---|---|
| Xcode + free Apple ID | free | **7 days**, then re-install from a Mac. Max 3 apps | annoying | dev/testing only |
| SideStore / AltStore + free Apple ID | free | 7 days, refreshes itself (with some setup) | fiddly, can break | possible if you want free |
| **Apple Developer Program + TestFlight** | **$99/yr** | each build 90 days, auto-updates via the TestFlight app | install TestFlight, tap invite | **recommended** |
| Developer Program + Ad Hoc | $99/yr | 1 year per build | needs her iPhone's UDID registered | alternative |
| App Store (unlisted) | $99/yr | permanent | normal install | overkill, adds App Review |

With TestFlight, she can be an **internal tester** (added to your App Store Connect team, up to 100 people, no review), so new builds show up for her right away. You just need to upload a fresh build at least every 90 days, which a CI job can do automatically.

### Do you need a Mac?

Building an iOS app needs **Xcode, which only runs on macOS**:
- **No Mac:** build and upload to TestFlight from cloud Macs (GitHub Actions macOS runners, Xcode Cloud, or Codemagic) with fastlane. The shared UI is developed and tested on Android, and iOS gets the same screens. It works, but debugging iOS-only problems (widgets, controls, permissions) without a Mac is slow.
- **Borrowed/occasional Mac:** enough for the iOS-specific parts (§3) and the first signing setup.
- **Own Mac:** easiest. Even an older Apple Silicon Mac is fine.

## 6. Order of work (fits into the roadmap)

1. Build everything Android-first as planned, but structured as **KMP** from day one (costs almost nothing extra now).
2. **iOS spike** (after phase 1): `tuya-lan` running on an iPhone. Sockets via `ktor-network` (published for iOS) or Apple's Network framework, and crypto via `cryptography-kotlin` or CommonCrypto/CryptoKit. Goal: toggle a lamp from an iPhone test app.
3. iOS app shell hosting the shared Compose screens + Local Network onboarding + Keychain.
4. iOS surfaces: interactive widgets → App Intents/Siri/Shortcuts → Control Center controls.
5. TestFlight pipeline, then send your sister the invite.
