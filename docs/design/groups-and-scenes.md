# Design: groups, one-tap scenes & customization

> Status: proposal v0.2. **This is the v1 focus.** Automations are parked until the mini-PC hub exists ([automations.md](automations.md)).
> Context: [architecture.md](architecture.md) · [iOS & sharing](ios-and-sharing.md) · [research](../research/tuya-platform.md)
> Devices in scope: **2× BARDI 12 W RGB+WW bulbs** now, plus a **4-socket smart power strip** (extension cord) later.

## 1. What's wrong with the BARDI app (and what we do instead)

| BARDI app | This app |
|---|---|
| Groups can only switch **work mode / power**. No colour or brightness for the whole group | Groups get **every** control their members support: power, brightness, white temperature, colour, effects |
| A power strip is one device with 4 switches buried inside it | Every socket (and the USB port) is its own **channel**, with its own name, icon, room and groups |
| Tap-to-Run gives barely any options | Scenes set **any field on any channel or group**, can leave fields unchanged, can toggle/cycle/restore, and can schedule an on-device "turn off after" timer |
| Fixed layout and icons | Custom names, icons, colours, room order, dashboard tiles, saved colour palettes |
| Must open the app | **Android:** Quick Settings tiles, widgets, launcher shortcuts · **iOS:** Control Center, widgets, Siri/Shortcuts, Action button |

## 2. Model

```
Device   = a physical thing on the network (1 Tuya id, 1 local_key, 1 IP)
 └─ Channel = something you can switch/control
             lamp  → 1 light channel
             strip → socket 1..4 + USB  (5 channels)
Room     = every channel lives in exactly one room (for layout)
Group    = any set of channels (a channel can be in many groups)
Scene    = a list of "make these targets look like this" steps, run with one tap
Palette  = a saved list of colours (used by groups and scenes)
```

Groups and scenes always point at **channels**, never at raw devices. That's what lets "Desk" = lamp 2 + strip socket 3 (monitor light) + strip USB (phone charger).

```yaml
# Example data (stored in the app's local DB, exportable as JSON/YAML)
channels:
  - { id: lamp-1,      device: bardi-bulb-a, kind: light,  name: Lampu Kamar, icon: bed,       room: bedroom }
  - { id: lamp-2,      device: bardi-bulb-b, kind: light,  name: Lampu Meja,  icon: desk_lamp, room: bedroom }
  - { id: strip-1,     device: strip,        kind: socket, name: Kipas,       icon: mode_fan,  room: bedroom, socket: 1 }
  - { id: strip-2,     device: strip,        kind: socket, name: Monitor,     icon: monitor,   room: bedroom, socket: 2 }
  - { id: strip-3,     device: strip,        kind: socket, name: Speaker,     icon: speaker,   room: bedroom, socket: 3 }
  - { id: strip-4,     device: strip,        kind: socket, name: Spare,       icon: power,     room: bedroom, socket: 4, hidden: true }
  - { id: strip-usb,   device: strip,        kind: socket, name: Charger,     icon: charger,   room: bedroom, socket: usb }

groups:
  - { id: lights, name: All lamps, icon: lightbulb, members: [lamp-1, lamp-2], brightness_mode: relative }
  - { id: desk,   name: Desk,      icon: desk,      members: [lamp-2, strip-2, strip-3] }
```

## 3. Groups: how controls behave

**Capabilities = union of the members.** Each control only goes to the members that support it, and the UI says so ("Colour → 2 of 3").

| Control | Behaviour |
|---|---|
| **Power tap** | If *any* member is on, turn all off. Otherwise turn all on. (Per group setting: `any_on → off` or `majority`) |
| **Brightness** | `absolute`: every lamp gets the same %. `relative`: keeps the ratio between lamps. If lamp A is at 80 % and B at 40 %, dragging the group to 50 % gives A 50 %, B 25 % |
| **White temperature** | same value to all lamps. Switches them to white mode if needed |
| **Colour** | `same`: one HSV for all. `palette`: spread a palette over the members (A gets colour 1, B colour 2, …) |
| **Effect** | same built-in or custom effect on every lamp (they run independently, so expect slight drift) |
| **Timer** | "Off in 30 min" sets each member's **on-device countdown**, so it fires even if the phone is off |

**Why BARDI groups fail at colour and our groups don't:** a lamp in white mode ignores a colour value unless the mode changes in the *same* command. For each member we build one multi-DP frame (`work_mode=colour` + `colour_data` + `switch_led=true`) and send all members **in parallel** over their own connections. They change within ~100 ms of each other.

**Smooth dragging:** while your finger is on the colour wheel or brightness slider, the app sends Tuya's real-time `control_data` DP (meant for "live preview while dragging"), throttled to ~5 updates/s per lamp. On release it sends the final `colour_data` / `bright_value` so the state sticks. *(Phase 0 check: confirm the Bardi bulb accepts DP 28.)*

**Mixed-state display:** the group tile shows a split swatch of member colours. The slider shows the range (e.g. "30–80 %") until you touch it.

## 4. One-tap scenes

A scene is a list of **steps**. Each step targets a channel or a group and lists **only the fields it wants to change**. Everything else stays as it is.

```yaml
id: movie
name: Movie
icon: movie
style: toggle            # normal | toggle | cycle  (see below)
steps:
  - target: lamp-1
    set: { power: on, color: { h: 250, s: 80, v: 15 } }
  - target: lamp-2
    set: { power: off }
  - target: strip-3        # speaker socket
    set: { power: on }
  - target: strip-1        # fan
    set: { power: on }
    then_off_after: 2h     # on-device countdown, runs without the phone
```

### Field options per target

| Field | Values |
|---|---|
| `power` | on / off / toggle |
| `brightness` | % · or `+10` / `-10` (relative steps, great for widget buttons) |
| `white` | temperature 0–100 % (warm ↔ cool) |
| `color` | HSV · hex · a palette colour · `random` · `random_from: <palette>` |
| `effect` | built-in (night, read, rainbow, …) or one of your custom effects (§5) |
| `transition` | `instant` / `smooth` (uses the bulb's own gradient). Long fades (minutes) need the hub, so later |
| `then_off_after` | 1 min – 24 h, on-device countdown |

### Scene styles

- **normal**: tap applies.
- **toggle**: first tap applies and remembers what each target looked like before. The second tap **restores** that. (Perfect for "Movie", "Focus", "Guests".)
- **cycle**: each tap moves to the next look in a list, e.g. `Bright → Warm → Night light → Off`. Ideal for a single Quick Settings tile or widget button.

### Extras

- **Capture current:** pick channels and save how they look right now as a scene.
- **Preview** (long-press): shows a strip of the colours/states it'll apply.
- **Auto cover:** the scene card is tinted with the colours the scene sets.
- **Run from anywhere:** in-app, Quick Settings tile / Control Center, widget, launcher shortcut / quick action, Siri ("movie mode"), or a deep link (`bardisucks://scene/movie`) for NFC tags / Shortcuts / Tasker.

## 5. Effects (bulb-side animations)

Tuya bulbs can run an animation **on the bulb itself** from one DP (`scene_data`, DP 25 on these bulbs). Once it's sent, the phone can go away.

- **Built-in presets**: the 8 or so standard Tuya effects (night, read, meeting, leisure, soft, rainbow, shine, gorgeous …). We capture their exact strings from the bulb during phase 0.
- **Custom effect editor:** up to 8 colour steps, each `static` / `jump` / `gradient`, plus speed and per-step brightness/white. Encoded into the Tuya scene string (format in [research §2.3](../research/tuya-platform.md#23-light-effects--live-control-formats)) and saved like a palette, so you can apply it to a lamp, a group, or use it in a scene.
- **Later, maybe:** music mode (phone mic → live colours via DP 27/28), and on-device **wake-up / sleep fades** (`wakeup_mode`/`sleep_mode` DPs) if the Bardi firmware has them. Those run on the bulb at a set time, so you'd get "automation-like" behaviour without the mini PC.

## 6. Customization

| Area | Options |
|---|---|
| **Channels** | name, icon (Material Symbols set), room, hide, favourite, per-lamp **night floor** (minimum brightness the slider goes to), which controls to show (e.g. hide colour on a lamp you only use white) |
| **Rooms & groups** | name, icon, accent colour, order, collapse. Group brightness mode (absolute/relative), colour mode (same/palette) |
| **Home dashboard** | sections you choose and order: Favourites · Scenes · Rooms · Groups. Tile size small/large. Lamp tiles are **tinted with the lamp's real current colour** |
| **Colours** | saved swatches per lamp + shared palettes. "Palette from photo" (pick colours from an image) |
| **Theme** | Android: Material You (wallpaper colours). iOS: your accent colour. Both: dark / light / pure-black |
| **Language** | Indonesian + English strings |
| **Backup & sharing** | export/import as one file (keys encrypted with a passphrase). **Home config** (devices, groups, scenes, effects, palettes) is shareable with your sister. **Personal** stuff (dashboard, favourites, tiles/widgets, theme) stays per phone. The same file later imports into the hub |

## 7. One-tap surfaces (Android & iOS)

| Android | iOS | What it does |
|---|---|---|
| **Quick Settings tiles** (6 slots, "Bardi 1–6") | **Control Center controls** (+ Lock Screen, Action button), iOS 18+ | bind each to a channel, group, scene or cycle scene. Shows on/off state |
| **Glance widgets** | **Interactive WidgetKit widgets** (Home + Lock Screen), iOS 17+ | (a) scene grid. (b) one channel/group with on/off + brightness −/+. (c) whole room |
| **Launcher shortcuts** | **Quick actions** (long-press icon) | top 4 scenes |
| **Deep links** for Tasker / Routines / NFC | **App Intents** for Siri, Shortcuts, Spotlight, NFC & time-of-day automations | "Hey Siri, movie mode". Phone-based mini-automations until the hub exists |

All of these talk **directly to the devices over Wi-Fi**: connect, send, close (~0.2–0.8 s). Away from home they show "Not on home Wi-Fi". Remote control comes with the hub + Tailscale later. Until then the Smart Life app still works remotely through Tuya's cloud. Platform details: [ios-and-sharing.md §3](ios-and-sharing.md#3-one-tap-surfaces-android--ios).

## 8. Screens (wireframes)

```
┌ Home ─────────────────────────┐   ┌ Lamp: Lampu Kamar ───────────┐
│ Good evening        ⚙          │   │  ◉ ON                  ⋮     │
│ ┌Scenes───────────────────────┐│   │  [ White | Colour | Effect ] │
│ │[Movie][Night][Focus][All off]││   │        ╭──────────╮          │
│ └─────────────────────────────┘│   │       ╱  colour    ╲         │
│ Favourites                     │   │      │    wheel     │        │
│ ┌──────────┐ ┌──────────┐      │   │       ╲            ╱         │
│ │💡Kamar ●│ │💡Meja  ○ │      │   │        ╰──────────╯          │
│ │ 60% warm │ │  off     │      │   │  Brightness ━━━━━━●─── 60%   │
│ └──────────┘ └──────────┘      │   │  Swatches ● ● ● ● ● +        │
│ Bedroom                    ▾   │   │  ⏲ Off in…  · ☆ Save as scene│
│  All lamps  ●●  ━━━●━━  40–60% │   └──────────────────────────────┘
│  Kipas ● Monitor ○ Speaker ●   │
│  Charger ●                     │   ┌ Scene editor: Movie ─────────┐
└────────────────────────────────┘   │ Style: (•)toggle ( )normal   │
┌ Power strip ──────────────────┐   │ + Lampu Kamar  on · 🟣 15%    │
│  ALL ●   (tap = all on/off)    │   │ + Lampu Meja   off            │
│  1 Kipas     ●  ⏲ 2h left      │   │ + Speaker      on             │
│  2 Monitor   ○                 │   │ + Kipas        on · off in 2h │
│  3 Speaker   ●                 │   │ [+ Add target]   [Capture now]│
│  USB Charger ●                 │   │ [Test]                 [Save] │
│  Power-on after outage: memory │   └──────────────────────────────┘
│  Child lock ○  LED: relay      │
└────────────────────────────────┘
```

## 9. Build order (v1)

1. **Lamp control:** power, white/colour, brightness, temperature, colour wheel with live preview, swatches
2. **Rooms + groups:** union capabilities, relative brightness, palette colour, parallel fan-out
3. **Power strip:** per-socket channels, all-on/off, countdowns, power-on behaviour, child lock, LED mode
4. **Scenes:** normal/toggle/cycle, partial states, capture, on-device off-timers
5. **One-tap surfaces:** Android QS tiles, widgets, shortcuts, deep links → then the iOS equivalents (widgets, Siri/Shortcuts, Control Center)
6. **Effects:** built-in list, custom effect editor
7. **Polish & sharing:** themes, palette-from-photo, backup/export, Share home (you → sister), Indonesian strings
