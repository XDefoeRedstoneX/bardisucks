# Design: architecture

> Status: proposal **v0.3**. **Android + iOS from one Kotlin Multiplatform codebase, no server yet.** Focus is customization, groups and one-tap scenes ([groups-and-scenes.md](groups-and-scenes.md)). iOS and two-person sharing: [ios-and-sharing.md](ios-and-sharing.md).
> Automations are parked until the mini-PC hub exists ([automations.md](automations.md)).
> Background: [research/tuya-platform.md](../research/tuya-platform.md).

## 1. Goals

1. **Replace the BARDI app** for 2× BARDI 12 W RGB+WW bulbs now, plus a 4-socket smart power strip later, on **Android (you) and iOS (your sister)**.
2. **Groups that actually work:** colour, brightness, white temperature and effects on a whole group, not just power and mode.
3. **One-tap scenes with real options:** any field on any lamp/socket/group, partial changes, toggle/restore, cycle, on-device off-timers.
4. **Customization:** names, icons, rooms, layout, palettes, themes, plus native one-tap surfaces (Android Quick Settings tiles/widgets/shortcuts, iOS Control Center/widgets/Siri).
5. **Local:** talks to devices over home Wi-Fi, works when the internet is down, responds well under a second.
6. **Ready for the hub later:** the device and scene logic is written once and reused by the future mini-PC server.

Non-goals for v1: automations/schedules (→ hub), remote control away from home (→ hub + Tailscale), real-time config sync between phones (→ hub), cameras, Zigbee/BLE.

## 2. Key decisions

### 2.1 v1 = the phone talks directly to the devices

Without automations, nothing has to run 24/7, so we don't need a server yet. Each phone's app opens the encrypted LAN connection to each lamp/strip itself.

The one Tuya constraint to respect is **one LAN connection per device**. The app holds connections only while it's on screen and releases them shortly after you leave. Widgets and tiles connect, send and disconnect (§6).

### 2.2 Native apps from one Kotlin Multiplatform codebase, not a web app

- **Browsers can't open raw TCP/UDP sockets**, so a web app/PWA can't talk to Tuya devices without a server in the middle. With no server in v1, it has to be native.
- **Tiles/controls, widgets and shortcuts** are a big part of "one tap", and they're native features on both platforms.
- **Kotlin Multiplatform (KMP)** compiles the same protocol + domain code for Android, iOS **and the JVM** (the future hub). **Compose Multiplatform** shares the screens too (stable on iOS since May 2025). So the protocol, groups, scenes and UI are written once, and only the tiles/widgets/controls are per-platform.

### 2.3 v2 (later) = mini-PC hub

When the mini PC exists, it takes over device connections and runs automations. The app gains a **hub mode** and talks to the hub instead of the devices.

```mermaid
flowchart LR
  subgraph V2["v2: later, with the mini PC"]
    direction LR
    A2["📱 Android / iOS app<br/>(hub mode)"]
    H["🖥️ Hub on mini PC<br/>same core + tuya-lan<br/>+ automations"]
    D2["💡💡🔌"]
    TS(("Tailscale"))
    A2 <-->|REST + WebSocket| H
    A2 -.->|away from home| TS
    TS -.-> H
    H <-->|Tuya LAN| D2
  end

  subgraph V1["v1: now"]
    direction LR
    A1["📱 Android / iOS app<br/>(UI, tiles, widgets)"]
    L1a["💡 Lamp 1"]
    L1b["💡 Lamp 2"]
    S1["🔌 Power strip<br/>(4 sockets + USB)"]
    A1 <-->|Tuya LAN, TCP 6668| L1a
    A1 <-->|Tuya LAN| L1b
    A1 <-->|Tuya LAN| S1
  end
```

## 3. Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | **Kotlin Multiplatform** | one codebase → Android, iOS, JVM (hub later) |
| UI | **Compose Multiplatform + Material 3** | shared screens on both phones, easy custom colour wheel/sliders. Material You colours on Android |
| Android one-tap | `TileService` (6 slots), **Jetpack Glance** widgets, launcher shortcuts | native Android |
| iOS one-tap | **WidgetKit** interactive widgets, **Control Center controls**, **App Intents** (Siri/Shortcuts), quick actions | native Swift, small. See [ios-and-sharing.md §3](ios-and-sharing.md#3-one-tap-surfaces-android--ios) |
| Async | kotlinx.coroutines | parallel group fan-out, per-device actors |
| Storage | **Room (KMP)** + DataStore (KMP) | channels, groups, scenes, palettes, settings. Same schema on both |
| Secrets | `local_key`s in **Android Keystore** / **iOS Keychain** (`expect/actual`) | keys never leave the phone in plaintext |
| Serialization | kotlinx.serialization | profiles, import/export, sharing, backup |
| Sockets | `ktor-network` (JVM + iOS), with Apple's Network framework as the iOS fallback | raw TCP for the Tuya protocol |
| Crypto | `expect/actual`: `javax.crypto` on Android/JVM. On iOS `cryptography-kotlin` or CommonCrypto (AES-ECB) + CryptoKit (AES-GCM) | AES-ECB, AES-GCM, HMAC-SHA256, CRC32 |
| Reference / test oracle | **TinyTuya** (Python, on a laptop) | used to verify our Kotlin protocol port against real devices |

**Android:** min SDK 26 (Android 8), target latest. Permissions: `INTERNET`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (to hear discovery broadcasts), plus the local-network permission on Android versions that require it.
**iOS:** min iOS 17 (Control Center controls need 18). Local Network permission (`NSLocalNetworkUsageDescription`). No broadcast discovery (see [ios-and-sharing.md §4](ios-and-sharing.md#4-ios-specific-constraints)).

### Repo layout (once code starts)

```
shared/
  tuya-lan/      KMP: Tuya LAN protocol 3.1–3.5, discovery, DeviceConnection (commonMain + jvm/ios bits)
  core/          KMP: channels, profiles, groups, scenes, effects codec, colour math, import/export
  ui/            Compose Multiplatform: all app screens
androidApp/      Android entry point + Quick Settings tiles + Glance widgets + shortcuts
iosApp/          Xcode project: app entry hosting the shared UI + WidgetKit + Control Center + App Intents (Swift)
cli/             JVM command-line tool: test tuya-lan against real devices from a laptop
hub/  (later)    Ktor server on the mini PC, reusing shared/core + shared/tuya-lan; automations live here
docs/
```

## 4. Domain model

Full details in [groups-and-scenes.md §2](groups-and-scenes.md#2-model). In short:

- **Device:** Tuya id, IP, protocol version, product id, category, encrypted `local_key`, profile.
- **Channel:** a controllable endpoint on a device (lamp = 1 light channel, strip = 4 sockets + USB). Has name, icon, room, flags.
- **Room / Group / Scene / Palette / Effect:** all point at channels.

### 4.1 Profiles: capabilities ↔ DPs

The UI and scene engine never touch raw DP numbers. A profile maps capabilities to DPs, and ships as JSON inside `core`:

```yaml
id: tuya-light-rgbcw-v2           # BARDI 12 W RGB+WW (verify in phase 0)
match: { category: dj, has_dps: [20, 21, 22, 23, 24] }
channels:
  - kind: light
    power:      { dp: 20 }
    mode:       { dp: 21, map: { white: white, colour: color, scene: effect, music: music } }
    brightness: { white: { dp: 22, range: [10, 1000] }, color: { dp: 24, field: v, range: [10, 1000] } }
    white_temp: { dp: 23, range: [0, 1000], kelvin_hint: [2700, 6500] }
    color:      { dp: 24, codec: hsv12 }            # HHHHSSSSVVVV
    effect:     { dp: 25, codec: tuya_scene }       # see research §2.3
    live:       { dp: 28, codec: control21 }        # drag preview
    countdown:  { dp: 26, unit: s, max: 86399 }
```

```yaml
id: tuya-strip-4-usb
match: { category: pc, has_dps: [1, 2, 3, 4, 5] }
channels:
  - { kind: socket, key: "1",   power: { dp: 1 }, countdown: { dp: 9 } }
  - { kind: socket, key: "2",   power: { dp: 2 }, countdown: { dp: 10 } }
  - { kind: socket, key: "3",   power: { dp: 3 }, countdown: { dp: 11 } }
  - { kind: socket, key: "4",   power: { dp: 4 }, countdown: { dp: 12 } }
  - { kind: socket, key: "usb", power: { dp: 5 }, countdown: { dp: 13 } }
device_settings:
  power_on_state: { dp: 38, values: [on, off, memory] }
  indicator:      { dp: 40, values: [relay, pos, none] }
  child_lock:     { dp: 41 }
energy:   # only on energy-monitoring strips
  { kwh: 17, ma: 18, w: { dp: 19, scale: 1 }, v: { dp: 20, scale: 1 } }
```

When keys are imported, the importer reads the DP spec that the key tool exported, picks a matching profile, or **generates one** and lets you fix it in a "Device details / DPs" screen.

### 4.2 State

For each channel the app keeps the **reported** state (what the device last pushed) and **pending** commands. A command updates the UI immediately (optimistic). The device's status push confirms it. With no confirmation in ~3 s, the app retries once and then shows the tile as "didn't respond".

## 5. Tuya LAN layer (`tuya-lan` module)

A Kotlin port of what TinyTuya does, built in this order:
1. **Frame codec:** `0x55AA` frames (3.1–3.4) and `0x6699` frames (3.5). Sequence numbers, CRC32 / HMAC-SHA256 / GCM tags.
2. **Crypto + session:** AES-128-ECB payloads, and for 3.4/3.5 the 3-message session-key negotiation (cmd `0x03`/`0x04`/`0x05`).
3. **Commands:** `CONTROL` (`0x07`) / `CONTROL_NEW` (`0x0d`), `DP_QUERY` (`0x0a`) / `DP_QUERY_NEW` (`0x10`), `STATUS` push (`0x08`), `HEART_BEAT` (`0x09`), `UPDATEDPS` (`0x12`).
4. **Discovery:** listen on UDP 6666/6667 and probe UDP 7000 (3.5) to learn IP and version (Android/JVM). On iOS, use known IPs plus a TCP 6668 subnet sweep instead (no broadcast entitlement needed).

We only build the protocol versions the Bardi devices actually report (phase 0 scan) first. The rest come later.

**Testing:**
- **Golden tests:** TinyTuya on a laptop produces frames for a known key/payload/nonce. Our encoder has to produce identical bytes, and our decoder has to parse TinyTuya's.
- **Live tests:** the `cli` tool toggles and reads the real lamps from a laptop before any phone UI exists. The same tests later run on an iPhone (iOS spike).

## 6. Connections on the phone

```
ConnectionManager (singleton, shared code, one per app/extension process)
 ├─ DeviceActor per device (coroutine + command channel)
 │    connect → handshake → persistent socket
 │    receive loop: status pushes → normalize via profile → StateStore → UI/widgets
 │    heartbeat every ~12 s
 │    commands: coalesce (last value per DP wins), merge into one multi-DP frame,
 │              ≥150 ms between frames, live-preview (DP 28) throttled to ~5/s
 ├─ Discovery while app is in foreground (Android: UDP + MulticastLock · iOS: TCP sweep when a device goes missing)
 └─ Lifecycle:
      app visible           → connect all devices (warm, so groups fire in sync)
      polite mode (2 people)→ also release a device ~20 s after the last touch
      app hidden            → close all (Android after ~30 s; iOS suspends the app right away)
      tile/widget/control   → connect only the needed devices, send, read back, close
```

- **Group fan-out:** one coroutine per member device, all sent at once (`awaitAll`). With warm connections, members change within ~100 ms of each other.
- **Mode-safe commands:** setting colour on a lamp in white mode sends `switch_led + work_mode=colour + colour_data` in **one frame**, which is exactly what BARDI groups don't do.
- **Not home / unreachable:** if the devices don't answer, tiles and widgets show "Not on home Wi-Fi" and offer to open Smart Life (cloud) instead.

## 7. Getting keys onto the phone

The one-time key fetch happens on a laptop ([phase 0 guide](../guides/phase-0-onboarding.md)). Then:
- **Import file:** the app accepts `tuya-local-key` output or TinyTuya's `devices.json` (+ `tuya-raw.json` for DP specs). Send it to the phone (Android: Quick Share / USB / Drive · iOS: AirDrop / Files), then go to **Settings → Import devices**. The app encrypts the keys and you delete the file.
- **Share home:** one phone exports its home config (keys encrypted with a passphrase) and the other imports it. See [ios-and-sharing.md §2](ios-and-sharing.md#2-is-she-in-your-house-or-her-own).
- **Manual add:** id + key + IP + version form (IP/version can be auto-filled from discovery).
- **Later:** an in-app QR login (same flow as `tuya-local-key`), needing a second screen to show the QR. Not needed for v1.

## 8. Security

- `local_key`s are encrypted with a non-exportable Android Keystore key (Android) or stored in the Keychain, device-only, shared with the app's own extensions (iOS). They're excluded from cloud backups, never logged, and shown only after a fingerprint/Face ID/PIN check.
- **Backup/export** is one file. Keys are included only if you set a passphrase. The same file imports into the hub later.
- The repo `.gitignore` blocks key files (`devices.json`, `tinytuya.json`, `tuya-raw.json`, `snapshot.json`, `/secrets/`).
- **Optional later:** put the IoT devices on their own Wi-Fi/VLAN and block their internet access once local control is proven.

## 9. Reliability

| Situation | Behaviour |
|---|---|
| Internet down | everything works (LAN only) |
| Phone off / away | lamps and strip still work by hand and via Smart Life (cloud). On-device countdowns still fire |
| Smart Life open on another phone at the same time | that phone uses cloud control, which works fine. If it grabbed the LAN slot, our app retries |
| Two phones running this app (you + sister) | works: each releases connections when backgrounded or idle (polite mode). Tapping the same lamp at the same instant means one retries. Truly simultaneous use + synced scenes is what the hub solves |
| Device IP changes | discovery finds it again. Still set DHCP reservations |
| Device re-paired in Smart Life | key changed, so the app shows **Re-import key** |
| Power cut | the strip follows its power-on setting (DP 38: on/off/memory), and bulbs follow their own power-on behaviour |

## 10. Roadmap

| Phase | Deliverable | Done when |
|---|---|---|
| **0. Onboarding** | lamps moved to Smart Life, keys pulled, local control verified with TinyTuya, DP dumps + checks from [research §7](../research/tuya-platform.md#7-open-questions-to-check-on-your-devices) | a Python probe toggles both lamps |
| **1. `tuya-lan` + CLI** | Kotlin protocol for the version(s) your lamps use, golden tests, CLI | `cli toggle lamp-1` works from a laptop |
| **2. App skeleton + lamp control** | import keys, device list, full lamp screen with live colour preview | daily lamp control without BARDI |
| **3. Rooms & groups** | union capabilities, relative brightness, palette colour, in-sync fan-out | colour + brightness on "All lamps" works |
| **4. Power strip** | per-socket channels, all on/off, countdowns, power-on state, child lock, LED | each socket named and grouped |
| **5. One-tap scenes** | normal/toggle/cycle, partial states, capture current, on-device off-timers | your 3–5 favourite looks are one tap |
| **6. Android surfaces** | 6 QS tile slots, Glance widgets, launcher shortcuts, deep links | lamps controllable without opening the app |
| **7. Effects & polish** | built-in effects, custom effect editor, palette-from-photo, themes, backup, Indonesian strings | |
| **iOS track** (can start after phase 1) | iOS spike → app shell with shared screens → widgets, Siri/Shortcuts, Control Center → TestFlight ([details](ios-and-sharing.md#6-order-of-work-fits-into-the-roadmap)) | your sister installs it from TestFlight |
| **Sharing** (with phase 5) | split home vs. personal config, Share home export/import, polite mode | both phones run the same scenes |
| **Later: hub** | mini PC + `hub/` (Ktor) + hub mode in app, automations ([parked spec](automations.md)), Tailscale remote, live config sync between phones | |

## 11. Planning the mini PC (later, short version)

The hub is tiny: a few hundred MB RAM, almost no CPU.
- **Hardware:** any low-power x86 mini PC (Intel N100-class) or a Raspberry Pi 4/5. Something around 6–10 W idle is ideal since it runs 24/7.
- **Network:** wired Ethernet to the router, on the **same subnet** as the lamps/strip (UDP discovery doesn't cross subnets).
- **Software:** Debian/Ubuntu Server + Docker + Tailscale. The hub is one container. The same box can host other stuff (Pi-hole, media, backups).
- When you get there, we'll write a separate hub design doc and un-park the automation spec.

## 12. Alternatives considered

| Option | Verdict |
|---|---|
| PWA / web app | can't open raw sockets, so it needs a server. Fits v2 as an extra client, not v1 |
| Flutter + Dart `tinytuya` port | also covers Android + iOS, and there's a young Dart port that claims 3.1–3.5 support. But tiles/widgets/controls still need native code on both platforms, and the hub couldn't share the code as easily. KMP + Compose Multiplatform gets the same reach and keeps the hub reuse |
| Separate native apps (Kotlin + Swift) | best "native feel", but double the UI work. Not worth it for a personal app |
| Embed Python TinyTuya in Android (Chaquopy) | works, but adds ~20 MB+ and a second runtime. A Kotlin port is cleaner and reusable on the hub |
| Tuya Smart Life App SDK | commercial licence, cloud-based |
| Home Assistant + Tuya Local | great for automations, but you said customization/UI is the main pain, and HA's UI isn't tailored for this. Still a fine choice for the mini PC era if you'd rather not build the hub |

## 13. Still open

- **Your sister: same house or her own?** Decides sharing vs. a separate setup ([ios-and-sharing.md §2](ios-and-sharing.md#2-is-she-in-your-house-or-her-own)).
- **iOS distribution:** Apple Developer Program ($99/yr, recommended) vs. free 7-day sideloading. And is there a Mac (own/borrowed) or do we build in the cloud?

- **Power strip model:** pick one that's Tuya/Smart Life-compatible with **individually switchable** sockets (+ switchable USB). Energy monitoring is a bonus. Pair it straight into Smart Life, not BARDI.
- **Protocol version** of the 12 W bulbs (phase 0 scan) decides which part of `tuya-lan` gets built first.
- The phase 0 checks in [research §7](../research/tuya-platform.md#7-open-questions-to-check-on-your-devices) (live preview DP, countdown behaviour, effect strings, sleep/wake DPs).
