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

📐 **Research & design.** No app code yet. Next steps: [Phase 0](docs/guides/phase-0-onboarding.md) (you, with the lamps), then the Kotlin protocol library.

## Docs

| Doc | What's in it |
|---|---|
| [Research: how Bardi/Tuya devices work](docs/research/tuya-platform.md) | Tuya vs. OEM apps, data points for bulbs and power strips, effect formats, LAN protocol 3.1–3.5, getting local keys |
| [Phase 0 guide](docs/guides/phase-0-onboarding.md) | move lamps to Smart Life, pull keys, toggle a lamp from Python, run the checks the design depends on |
| [Design: architecture](docs/design/architecture.md) | Android app (Kotlin) talking directly to devices, module layout, profiles, connection handling, roadmap, mini-PC plan |
| [Design: groups, scenes & customization](docs/design/groups-and-scenes.md) | **the v1 focus:** channel/group model, group control rules, scene options, effects, customization, tiles/widgets, wireframes |
| [Design: sister's setup & platforms](docs/design/household-and-platforms.md) | her own lamps on the same Wi-Fi, Huawei tablet checklist, tablet layout/dashboard mode, why iOS is parked |
| [Design: automations](docs/design/automations.md) | ⏸ parked until the mini-PC hub exists |

## Roadmap

0. Onboarding: lamps off BARDI, keys pulled, local control verified
1. `tuya-lan`: Kotlin Tuya LAN protocol + test CLI
2. App skeleton + full lamp control (phone + tablet layouts)
3. Rooms & groups
4. Power strip (per-socket channels)
5. One-tap scenes
6. One-tap surfaces (tiles, widgets, shortcuts, tablet dashboard)
7. Effects editor & polish
8. *Later:* mini-PC hub, automations, remote access (for both you and your sister)

## ⚠️ Secrets

Tuya `local_key`s give full control of a device. Keep them out of git. `.gitignore` already blocks the usual file names (`devices.json`, `tinytuya.json`, `tuya-raw.json`, `snapshot.json`, `/secrets/`, `/data/`).
