# Research: how Bardi / Tuya devices actually work

> Status: research notes, October 2026. Each claim is tagged with how solid it is:
> **[verified]** = read from source code or official docs,
> **[community]** = repeated by several independent users and not officially documented,
> **[test]** = unknown for Bardi specifically, so check it on your own devices.

## TL;DR

- Bardi is a **white-label Tuya brand**. The lamps and plugs run stock Tuya Wi-Fi firmware, and the BARDI app is a Tuya **OEM app** built on Tuya's app platform. **[verified]**
- Every Tuya Wi-Fi device can be controlled **directly over your LAN** (TCP 6668, AES-encrypted with a per-device `local_key`). This is fast, works without internet, and has no quotas. It's the path this project should be built on. **[verified]**
- The only hard part is getting each device's **`local_key`** once. You have to go through Tuya's cloud for that, and OEM-app accounts like BARDI's are a separate account system that the usual key-extraction tools don't support. So the practical route is to **move the devices from the BARDI app to the Smart Life app**, pull the keys, and then talk to the devices locally. **[community, test]**
- Tuya's "Tap-to-Run" and "Automation" scenes are a cloud feature with a fixed menu of triggers, conditions and actions. Our own hub can run a much richer automation engine locally. **[verified]**

---

## 1. Who's who

| Thing | What it is |
|---|---|
| **Tuya** | Chinese IoT platform. It supplies Wi-Fi modules, firmware, cloud and app templates to thousands of brands. |
| **Bardi** | Indonesian brand and Tuya partner. It sells Tuya-based bulbs, plugs, switches and cameras, and ships the **BARDI Smart Home** app (an OEM build of Tuya's app). See the [Tuya × Bardi announcement](https://tuya.com/news-details/tuya-smart-and-bardi-to-enable-indonesian-operator-xl-axiata-to-deliver-a-smart-home-and-lifestyle-concept-Kamxalj48xo2f). |
| **Smart Life / Tuya Smart** | Tuya's own first-party consumer apps. Unlike OEM apps, they can be linked to developer tooling. |
| **Tuya IoT Development Platform** (`iot.tuya.com`) | Developer portal. You create a "cloud project", link an app account, and get API access to that account's devices, including their `local_key`. |

**OEM app accounts are a separate account system.** Tuya's help center says OEM-app accounts are not shared with Tuya Smart / Smart Life, so the same login doesn't work across them, and only app accounts linked to a cloud project expose device info such as the Local Key ([Tuya Help Center](https://support.tuya.com/en/help/_detail/K93eolrqjoiu7), [also](https://support.tuya.com/en/help/_detail/Kf9vpx3t5qgpl)). Several users report that the working fix is to re-pair the device into Smart Life / Tuya Smart and link *that* account instead. **[community]**

A Samsung Community user in Indonesia reports pairing Bardi devices with either the BARDI app or Smart Life. That fits "stock Tuya firmware", but it's anecdotal. **[test]**

## 2. Device model: data points (DPs)

Every Tuya device exposes its state as a set of numbered **data points (DPs)**. Each DP has an id (int), a code (string, e.g. `switch_led`), a type (`bool`, `value`, `enum`, `string`, `raw`) and a range. Locally you read and write DPs by **number**. The cloud speaks **codes**. Which number maps to which code varies per product, so the hub needs a per-device mapping. **[verified]**

### 2.1 Lights: Tuya category `dj`

Most current RGB+CCT bulbs (likely including the Bardi RGBWW 9 W/12 W) use the **v2 layout**. These come from the TinyTuya README and Tuya's standard instruction set for lights ([Tuya `dj` instruction set](https://developer.tuya.com/cn/docs/iot/dj?id=K9i5ql3v98hn3)):

| DP | Code | Type | Range / format | Notes |
|---:|---|---|---|---|
| 20 | `switch_led` | bool | | on/off |
| 21 | `work_mode` | enum | `white` / `colour` / `scene` / `music` | **which of the DPs below are "live"** |
| 22 | `bright_value_v2` | value | 10–1000 | brightness in **white** mode only |
| 23 | `temp_value_v2` | value | 0–1000 | 0 = warmest, 1000 = coolest. Kelvin range depends on the bulb |
| 24 | `colour_data_v2` | string | 12 hex chars `HHHHSSSSVVVV`: H 0–360, S 0–1000, V 0–1000 | e.g. `000003e803e8` = full red. In **colour** mode brightness = V |
| 25 | `scene_data_v2` | string | encoded scene (gradients/jumps between colours) | the bulb's built-in effects |
| 26 | `countdown_1` | value | 0–86400 s | **on-device** timer. It keeps running if our hub is down |
| 27 | `music_data` | string | | music-sync mode, driven by the phone app |
| 28 | `control_data` | string | | "live" colour preview while dragging the picker (no save) |
| 30–34 | rhythm / sleep / wake / power-off memory / do-not-disturb | | | present on newer firmware, no range in docs |

Older v1 bulbs use DP 1–5 (switch, mode, brightness 25–255, temp 0–255, 14-char RGB+HSV hex colour). The hub's profile system has to handle both layouts. **[verified]**

**Gotcha:** brightness isn't one DP. In `white` mode it's DP 22, and in `colour` mode it's the V inside DP 24. Our normalized "brightness %" has to write to whichever one is active.

### 2.2 Plugs / outlets: Tuya category `cz`

From the TinyTuya README (v3.3+ devices):

| DP | Meaning | Range | Notes |
|---:|---|---|---|
| 1 (–7) | switch (per outlet; 7 is often USB) | bool | |
| 9 (–15) | countdown per outlet | 0–86400 s | on-device timer |
| 17 | energy added | | units/scale vary, often 0.001 kWh |
| 18 | current | mA | only on energy-monitoring models |
| 19 | power | W × 10 (scale 1) | **check the scale per device** |
| 20 | voltage | V × 10 (scale 1) | |
| 38 | power-on behaviour | `off` / `on` / `memory` | what happens after a power cut |
| 40 | indicator LED mode | `none` / `on` / `relay` / `pos` | |
| 41 | child lock | bool | |

Some plugs only refresh energy DPs (18–20) after an explicit `UPDATEDPS` request. TinyTuya recommends polling energy no faster than every 5–10 s. **[verified]**

### 2.2b Power strips (4 sockets + USB): Tuya category `pc`

This is the layout used by most 4-socket Tuya strips in the [tuya-local device library](https://github.com/make-all/tuya-local/tree/main/custom_components/tuya_local/devices) (`quad_powerstrip_usb.yaml`, `powerstrip_4outlet_usbs.yaml`, `brilliant_quad_powerstrip_usb.yaml`) **[verified for those models, test for yours]**:

| DP | Meaning | Notes |
|---:|---|---|
| 1, 2, 3, 4 | socket 1–4 on/off | bool, each socket switched separately |
| 5 | USB ports on/off | all USB ports usually switch together. A few models use DP 7 |
| 9, 10, 11, 12 | countdown socket 1–4 | seconds |
| 13 | countdown USB | seconds |
| 17–20 | energy (added kWh, mA, W ×10, V ×10) | only on energy-monitoring strips |
| 38 | power-on state | `on` / `off` / `memory` (some models use DP 7 with `"0"/"1"/"2"`) |
| 40 | indicator LED | `relay` (follows state) / `pos` (locator) / `none` |
| 41 | child lock | bool |

Tuya's cloud codes for these are `switch_1…4`, `switch_usb1`, `countdown_1…`, `relay_status` (`power_on`/`power_off`/`last`) ([Tuya standard instruction set: kg/cz/pc](https://developer.tuya.com/en/docs/iot/s?id=K9gf7o5prgf7s)).

**Buying note:** some cheap strips only switch *all* sockets together. Check that the listing says the sockets are individually controllable before you buy.

### 2.3 Light effects & live-control formats

All of these are hex strings on the wire **[verified]** (Tuya's [generic bulb template](https://developer.tuya.com/en/docs/iot/generic-light-bulb-template?id=Kag3g03a9vy81), checked against the real strings in tuya-local's `rgbcw_lightbulb.yaml`):

**`scene_data` (DP 25): on-bulb animations**
```
SS  + unit × 1..8
      unit = II TT MM HHHH SSSS VVVV BBBB KKKK      (26 hex chars)
SS   scene number            II   switch interval (0–100, speed)
TT   change/gradient time    MM   00 static · 01 jump · 02 gradient
HHHH hue 0–360               SSSS sat 0–1000      VVVV value 0–1000
BBBB white brightness 0–1000 KKKK white temperature 0–1000
```
Real presets seen on Tuya RGBCW bulbs (tuya-local):

| Name | String |
|---|---|
| night | `000c0d0000000000000000c80000` (white, brightness 200, warm) |
| reading | `010e0d0000840000000003e801f4` |
| working | `020e0d0000e80383000003e803e8` |
| leisure | `030e0d00001403e8000001f401f4` |
| soft | `04464602007803e803e800000000464602007803e8000a00000000` (green breathe, 2 units) |
| colorful / dazzling / gorgeous | `05…` / `06…` / `07…` multi-unit jump/gradient |

So a custom effect is just "pick a scene number, append up to 8 units". **[test]** Find the maximum number of units the Bardi bulb accepts.

**`control_data` (DP 28): live preview while dragging, not saved**
```
M HHHH SSSS VVVV BBBB KKKK     (21 hex chars)
M = 0 direct · 1 gradient
```

**`music_data` (DP 27):** same shape as `control_data`, streamed by the app in music mode.

**`colour_data` (DP 24):** `HHHHSSSSVVVV` (12 hex chars, H 0–360, S/V 0–1000). The cloud may call this `colour_data` or `colour_data_v2` depending on the product, but the local format is the same.

**Countdown (DP 26):** seconds, 0–86399 on the tuya-local generic bulb. **[test]** Check whether it turns the lamp *off* or *inverts* it when it ends. Most Tuya firmware flips the switch, so "off after 30 min" only makes sense on a lamp that's on.

**Optional on-bulb schedules** (newer firmware only, Base64 raw DPs): `rhythm_mode` (circadian colour schedule), `sleep_mode` (fade out at a time), `wakeup_mode` (sunrise fade at a time). These run **on the bulb**, so no server is needed, but the bulb needs correct time from Tuya's cloud. **[test]** Check whether the Bardi 12 W has them.

**Where to get the exact mapping:** the cloud returns each device's spec (`function` + `status_range`, with codes, types, ranges and **scale**) and a `local_strategy` that maps DP ids to codes. Pull it once during onboarding and generate the device profile from it instead of hand-guessing. **[verified]** ([tuya-device-sharing-sdk `device.py`](https://github.com/tuya/tuya-device-sharing-sdk))

## 3. The local (LAN) protocol

Reverse-engineered by the community and implemented by [TinyTuya](https://github.com/jasonacox/tinytuya) (Python, **v1.20.0** at time of writing), [tuya-local](https://github.com/make-all/tuya-local) (Home Assistant) and others. Details from TinyTuya's `PROTOCOL.md` **[verified]**:

| Version | Handshake | Crypto | Notes |
|---|---|---|---|
| 3.1 | none | AES-ECB on control messages only | status readable *without* the key |
| 3.2 | none | | rare, treated as a quirky 3.3 |
| 3.3 | none | AES-128-ECB + CRC32 | very common on 2019–2022 devices |
| 3.4 | 3-message session-key negotiation | AES-ECB + HMAC-SHA256 | new session key per TCP connection |
| 3.5 | session key | AES-GCM per frame (`0x6699` frames) | newest firmware |

**Ports**
- **TCP 6668**: the control connection (the hub connects *to* the device).
- **UDP 6666**: plaintext discovery broadcasts (v3.1).
- **UDP 6667**: encrypted discovery broadcasts (v3.3/3.4, some 3.5). Devices announce themselves every few seconds with id, IP and version.
- **UDP 7000**: v3.5 discovery. The client broadcasts a request and the device replies unicast.

**Connection behaviour**
- **Only one TCP connection per device at a time.** **[verified]** The hub has to be the single owner of each device's LAN connection. Phones running the Tuya app will then fall back to cloud control. That works, it's just slower.
- Hold the socket open (`set_socketPersistent(True)`), send a **heartbeat** about every 12 s, and the device **pushes status updates** (command `0x08`) whenever something changes, including a physical button press on a plug. That gives us real-time state without polling.
- On 3.4/3.5 any disconnect means a new handshake, so keep connections alive.
- Don't poll faster than ~1/s. Send several DP changes in one message (`set_multiple_values`) instead of many small ones.

**The `local_key`**
- 16-byte secret, generated when the device is **paired**. It **changes every time the device is removed and re-paired.** **[verified]** Once you have the keys, stop re-pairing devices.
- If you have the key, you have full control of the device. Treat it like a password.

## 4. Ways to get the `local_key`

| Route | Needs | Pros | Cons |
|---|---|---|---|
| **A. QR login via Tuya's device-sharing SDK** (e.g. [`tuya-local-key`](https://github.com/vineetchoudhary/tuya-local-key)) | Smart Life / Tuya Smart account, "User Code" from *Me → Settings → Account and Security* | no developer account, one-time, returns `local_key`, IP, category **and full DP specs** | uses Home Assistant's public app registration (`client_id HA_…`, schema `haauthorize`) **[verified]**. Only documented for Smart Life / Tuya Smart, not OEM apps **[test]** |
| **B. Tuya IoT platform + TinyTuya wizard** (`python -m tinytuya wizard`) | developer account, cloud project in the right data center, linked Smart Life account | well-trodden, also gives cloud API access | IoT Core "trial" expires after ~1 month and you renew it manually (up to 6 months via *Extend trial period*) **[verified]** ([TinyTuya #736](https://github.com/jasonacox/tinytuya/issues/736)). Data center has to match the account region |
| **C. Flash custom firmware** (see §6) | compatible chip, some risk | no keys, no Tuya at all | can brick devices, depends on the chip revision |

**Data center for Indonesia:** Tuya's region table maps Indonesia (+62) to **Western America** (`openapi.tuyaus.com`, TinyTuya region `us`) ([table](https://raw.githubusercontent.com/tuya/tuya-home-assistant/main/docs/regions_dataCenters.md)). Some newer accounts in Southeast Asia land on **Singapore** (`sg`). If route B shows zero devices, try the other one. Route A works this out for you. **[community]**

Home Assistant itself moved its official Tuya integration to this QR-login SDK in 2024.2 ([PR #109155](https://github.com/home-assistant/core/pull/109155)). That's why route A exists and doesn't need a developer account.

## 5. Tuya cloud vs. the Bardi app's scenes

Tuya has two scene types ([Tuya docs: Smart Scenes](https://developer.tuya.com/en/docs/app-development/scene-info?id=Kf5d4jdwx4k5l)):
- **Tap-to-Run**: a list of actions you run manually.
- **Automation**: conditions (time, weather/sunrise-sunset by *city*, device state, location) → actions.

Built-in limits of Tuya scenes:
- **They run in Tuya's cloud** for Wi-Fi devices. No internet means no automations, and every action takes a round trip through an overseas data center.
- A fixed menu of conditions and actions, no real `if/else`, variables or modes, and limited delays.
- The same device can't be both a condition and an action in one scene. **[verified]**
- No run history or trace, so you can't see *why* an automation didn't fire.
- An OEM app like BARDI can only expose a subset of what Tuya's platform supports.

Everything the custom app adds is on top of this. See [design/automations.md](../design/automations.md).

**Other cloud-based options we're *not* building on:**
- **Tuya Cloud OpenAPI as the main control path:** needs internet, adds latency, has quotas, and the trial needs renewing. It's only worth having as an optional fallback.
- **Tuya Smart Life App SDK** (build your own Tuya-branded app): free "Development Edition" for debugging only, and production use needs a paid commercial licence (one demo repo cites about $5k/yr, unverified) ([Tuya App SDK](https://www.tuya.com/platform/appdev/app-sdk)). Not worth it for a personal app.

## 6. Escape hatch: replace the firmware

Most current Tuya Wi-Fi bulbs and plugs use **Beken BK7231T/N** modules (WB2L, CB2L, CBLC9, …). Older ones are ESP8266.
- [tuya-cloudcutter](https://github.com/tuya-cloudcutter/tuya-cloudcutter) can flash [OpenBeken](https://github.com/openshwprojects/OpenBK7231T_App) **over the air** on supported firmware versions. After that the device speaks MQTT/HTTP, no Tuya at all. **[community]**
- Firmware updates close the exploit. Newer revisions (e.g. **BK7238**) aren't supported, and the remaining route is opening the device and flashing over serial. Using the wrong binary can brick it. **[community]** ([example teardown](https://www.elektroda.com/rtvforum/topic3927799.html))
- We don't know which chip Bardi bulbs use. **[test]**

For this project, firmware replacement is **optional**. The hub's driver layer should leave room for an "OpenBeken/MQTT" driver later, but the main plan works with stock firmware.

## 7. Open questions to check on your devices

1. Do the Bardi lamps pair with Smart Life without trouble? (Very likely yes.)
2. Can the BARDI app's scanner confirm a `tuya-local-key` QR login? (If it can, you can skip re-pairing. It probably can't.)
3. Which protocol version do the lamps report (`python -m tinytuya scan`)? 3.3, 3.4 and 3.5 all work, but it changes the code path.
4. The actual DP list and ranges for each lamp/plug (dump them during onboarding).
5. Do the lamps keep working locally if you block their internet access at the router? (Optional privacy hardening.)
6. Lamp effects/live control: does DP 28 (`control_data`) work for smooth dragging? What does DP 25 read back after you pick each effect in Smart Life (capture the preset strings)? How many effect units does the bulb accept?
7. Countdown (DP 26 on lamps, DP 9–13 on the strip): does it turn *off* or *flip* the state?
8. Does the 12 W bulb report the optional `sleep_mode` / `wakeup_mode` / `rhythm_mode` DPs?
9. Power strip (once bought): are the sockets individually switchable, and is USB on DP 5 or 7?

## Sources

- [Tuya × Bardi partnership announcement](https://tuya.com/news-details/tuya-smart-and-bardi-to-enable-indonesian-operator-xl-axiata-to-deliver-a-smart-home-and-lifestyle-concept-Kamxalj48xo2f)
- [TinyTuya](https://github.com/jasonacox/tinytuya): README, `PROTOCOL.md`, releases
- [TinyTuya issue #736: renewing the IoT Core trial](https://github.com/jasonacox/tinytuya/issues/736)
- [tuya-device-sharing-sdk](https://github.com/tuya/tuya-device-sharing-sdk) (MIT, v0.2.15)
- [tuya-local-key](https://github.com/vineetchoudhary/tuya-local-key)
- [make-all/tuya-local](https://github.com/make-all/tuya-local) (HA integration, device profile library)
- [Home Assistant core PR #109155: Tuya QR login](https://github.com/home-assistant/core/pull/109155)
- [Tuya IoT Platform configuration guide](https://github.com/tuya/tuya-home-assistant/wiki/Tuya-IoT-Platform-Configuration-Guide)
- [Tuya region → data center table](https://raw.githubusercontent.com/tuya/tuya-home-assistant/main/docs/regions_dataCenters.md)
- [Tuya standard instruction set: lights (`dj`)](https://developer.tuya.com/cn/docs/iot/dj?id=K9i5ql3v98hn3)
- [Tuya docs: Smart Scenes](https://developer.tuya.com/en/docs/app-development/scene-info?id=Kf5d4jdwx4k5l)
- [Tuya Help Center: OEM app accounts / local key](https://support.tuya.com/en/help/_detail/K93eolrqjoiu7)
- [Tuya App SDK](https://www.tuya.com/platform/appdev/app-sdk)
- [Tuya local protocol 3.3/3.4/3.5 explained (privatehomelab)](https://privatehomelab.com/tuya-local-protocol-version-3-3-3-4-3-5-explained/)
- [Elektroda: BK7231N bulb teardown + cloudcutter](https://www.elektroda.com/rtvforum/topic3927799.html)
