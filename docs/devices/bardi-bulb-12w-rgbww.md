# BARDI Light Bulb 12W RGBWW

> Source: phase 0 report, 2026-11-10 (tinytuya 1.20.0), two bulbs. No keys in this file.

| | |
|---|---|
| Tuya category | `dj` (light) |
| Product id | `omurhjizrsia38ec` (both bulbs) |
| LAN protocol | **3.5** (session-key handshake, AES-GCM `0x6699` frames) |
| Bulbs | "BARDI Light Bulb 12W RGBWW" `a3f6…55` @ 192.168.100.133 · "… 2" `a37c…sj` @ 192.168.100.134 |
| App profile | `tuya-light-rgbcw-v2` ([architecture §4.1](../design/architecture.md#41-profiles-capabilities--dps)) |

## Data points

| DP | Code | Type / range (cloud spec) | Local value format | Example |
|---:|---|---|---|---|
| 20 | `switch_led` | Boolean | `true`/`false` | `true` |
| 21 | `work_mode` | Enum `white`, `colour`, `scene`, `music` | string | `"colour"` |
| 22 | `bright_value_v2` | Integer 10–1000 | int | `1000` |
| 23 | `temp_value_v2` | Integer 0–1000 (0 = warm) | int | `1000` |
| 24 | `colour_data_v2` | Json h 0–360, s/v 0–1000 | **hex** `HHHHSSSSVVVV` | `001703e803e8` (h 23) |
| 25 | `scene_data_v2` | Json, scene_num 1–8, units static/jump/gradient | **hex** `SS` + 26-char units | `000e0d0000000000000000c80000` (the "night" preset) |
| 26 | `countdown_1` | Integer 0–86400 s | int | `0` |
| 28 | `control_data` | (send-only, not in status) | **hex** `M HHHH SSSS VVVV BBBB KKKK` | live preview |
| 34 | `do_not_disturb` | Boolean | `true`/`false` | `false` |

Not present: DP 27 music data in status, `rhythm_mode` / `sleep_mode` / `wakeup_mode` (no on-bulb schedules).

## Behaviour verified on the real bulbs

| Check | Result | Consequence for the app |
|---|---|---|
| Local control with the key | ✅ both bulbs | — |
| Live preview (DP 28) | ✅ smooth colour sweep, DP 24 unchanged | drag = DP 28 stream, release = DP 24 commit |
| Countdown (DP 26) | **flips** state: on→off *and* off→on | "off after X" only on a lamp that's on. The app clears DP 26 whenever it changes power, so a pending timer can't turn a lamp back on later |
| Second LAN connection | ✅ **both connections worked at the same time** | your phone + your sister's tablet (or app + hub) can be connected simultaneously. Polite mode is only a fallback |
| `do_not_disturb` (DP 34) | present | exposed as a lamp setting. On Tuya lights it keeps the lamp off when power returns after an outage |
