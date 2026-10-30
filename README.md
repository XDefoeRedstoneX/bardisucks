# bardisucks

A local-first replacement for the BARDI Smart Home app, with real scenes and automations.

BARDI devices are white-label **Tuya** devices. This project skips the BARDI/Tuya cloud and talks to the lamps and outlets **directly over the home network**, and runs its own automation engine on a small always-on hub. A phone-friendly web app (PWA) is the remote control.

```
📱 PWA  ──REST/WebSocket──►  🖥️ Hub (Python, on your LAN)  ──Tuya LAN protocol──►  💡💡🔌
                              ├─ device registry + profiles
                              ├─ automation engine + scheduler
                              └─ SQLite
```

## Status

📐 **Research & design.** No app code yet. Start with Phase 0 below, which you can do right now.

## Docs

| Doc | What's in it |
|---|---|
| [Research: how Bardi/Tuya devices work](docs/research/tuya-platform.md) | Tuya vs. OEM apps, data points, LAN protocol 3.1–3.5, how to get local keys, why Tuya scenes are limited, firmware-flashing escape hatch |
| [Phase 0 guide: get devices onto local control](docs/guides/phase-0-onboarding.md) | step-by-step: move devices to Smart Life, pull keys, toggle a lamp from Python |
| [Design: architecture](docs/design/architecture.md) | hub + PWA, tech stack, device model & profiles, driver layer, API, screens, security, roadmap |
| [Design: scenes & automation engine](docs/design/automations.md) | triggers/conditions/actions schema, run modes, modes & snapshots, traces, cookbook |

## Roadmap (short)

0. Onboarding: devices off BARDI, keys pulled, local control verified
1. Hub core: device registry, Tuya LAN driver, API
2. PWA v1: replaces the BARDI app for daily use
3. Scenes
4. Automation engine
5. Smart outlets + energy monitoring
6. Remote access (Tailscale), phone shortcuts, notifications

## ⚠️ Secrets

Tuya `local_key`s give full control of a device. Keep them out of git. `.gitignore` already blocks the usual file names (`devices.json`, `tinytuya.json`, `tuya-raw.json`, `snapshot.json`, `secrets/`, `data/`).
