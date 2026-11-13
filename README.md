# bardisucks

An Android app (phones + tablets) to replace the BARDI Smart Home app, with groups and one-tap scenes that actually let you customize things.

BARDI devices are white-label **Tuya** devices. This app skips the BARDI/Tuya cloud and talks to the lamps and power strip **directly over home Wi-Fi**, using the Tuya LAN protocol.

```
v1 (now)    📱 Android app ──Tuya LAN──► 💡 💡 🔌
v2 (later)  📱 Android app ──► 🖥️ mini-PC hub (+ automations, remote access) ──► 💡 💡 🔌
```

**Devices:** 2× BARDI 12 W RGB+WW bulbs · a 4-socket smart power strip (planned) · your sister's own lamps, controlled from her Huawei tablet

## What it fixes

- **Groups** control colour, brightness, white temperature and effects, not just power/mode
- **One-tap scenes** set any field on any lamp, socket or group, can leave fields unchanged, and can toggle back, cycle through looks, or turn things off later with on-device timers
- **Each socket of the power strip** is its own thing with its own name, icon, room and groups
- **Customization:** icons, rooms, dashboard layout, colour palettes, custom bulb effects, themes
- **One-tap without opening the app:** Quick Settings tiles, home-screen widgets, launcher shortcuts, tablet dashboard mode
- **No Google services needed:** a plain APK that runs on Huawei devices too

## Status

📱 **App v0.2.** Lamps, groups (colour/brightness on a whole group) and **one-tap scenes** (normal, toggle, cycle, with turn-off timers), plus Quick Settings tiles and home-screen shortcuts. v0.1 is confirmed working on the real lamps; v0.2 is tested on a PC against simulated lamps. [How to install and use it](docs/guides/app.md). Next up: home-screen widgets and the power strip.

```
app/        Android app (Kotlin, Jetpack Compose): screens, key storage, app lifecycle
core/       Kotlin: lamp model, groups, live preview, home config, colour / effect encoders
tuya-lan/   Kotlin: Tuya LAN protocol 3.5 (handshake, AES-GCM frames, status/control/pushes)
cli/        Kotlin: the `bardi` test tool
tools/      Python: phase 0 scripts, fake lamp, test-vector generator
```

## Docs

| Doc | What's in it |
|---|---|
| [Research: how Bardi/Tuya devices work](docs/research/tuya-platform.md) | Tuya vs. OEM apps, data points for bulbs and power strips, effect formats, LAN protocol 3.1–3.5, getting local keys |
| [App guide](docs/guides/app.md) | install the APK, import your lamps, what v0.1 can do, troubleshooting |
| [BARDI 12 W bulb](docs/devices/bardi-bulb-12w-rgbww.md) | real DP map + verified behaviour (live preview, countdown flips, 2 connections) |
| [`bardi` CLI guide](docs/guides/cli.md) | build on Windows, control both lamps as a group from the command line |
| [Phase 0 guide](docs/guides/phase-0-onboarding.md) | Windows walkthrough: move lamps to Smart Life, `get_keys.py` (QR login → keys), `probe.py` (local control test + checks → key-free report) |
| [Design: architecture](docs/design/architecture.md) | Android app (Kotlin) talking directly to devices, module layout, profiles, connection handling, roadmap, mini-PC plan |
| [Design: groups, scenes & customization](docs/design/groups-and-scenes.md) | **the v1 focus:** channel/group model, group control rules, scene options, effects, customization, tiles/widgets, wireframes |
| [Design: sister's setup & platforms](docs/design/household-and-platforms.md) | her own lamps on the same Wi-Fi, Huawei tablet checklist, tablet layout/dashboard mode, why iOS is parked |
| [Design: automations](docs/design/automations.md) | ⏸ parked until the mini-PC hub exists |

## Roadmap

0. ✅ Onboarding: lamps off BARDI, keys pulled, local control verified
1. ✅ `tuya-lan`: Kotlin Tuya LAN protocol 3.5 + `bardi` CLI *(3.3/3.4 + discovery later, if the power strip needs them)*
2. ✅ App: full lamp control (phone + tablet layouts) *(v0.1, works on the real lamps)*
3. ✅ Groups with colour/brightness *(v0.1, works on the real lamps; rooms and palette colours later)*
4. Power strip (per-socket channels)
5. ✅ One-tap scenes: normal, toggle, cycle, timers, capture *(v0.2)*
6. ◐ One-tap surfaces: ✅ Quick Settings tiles, shortcuts *(v0.2)*; widgets, tablet dashboard later
7. Effects editor & polish
8. *Later:* mini-PC hub, automations, remote access (for both you and your sister)

## ⚠️ Secrets

Tuya `local_key`s give full control of a device. Keep them out of git. `.gitignore` already blocks the usual file names (`devices.json`, `tinytuya.json`, `tuya-raw.json`, `snapshot.json`, `/secrets/`, `/data/`).
