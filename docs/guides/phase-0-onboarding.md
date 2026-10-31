# Phase 0: get your Bardi devices onto local control

You can do this today, before any app code exists. At the end you'll have each device's **id, IP, protocol version and `local_key`**, and you'll have toggled a lamp from a Python script with no cloud involved.

Do it for the **two 12 W lamps now**. The power strip gets a short version when you buy it ([Step 8](#step-8-adding-the-power-strip-later)). You need a laptop/PC for steps 4–7 (Windows or Linux is fine, no Mac needed). The Android app imports the result later.

**Your sister's lamps:** same steps, ideally into *her own* Smart Life account. She can use her iPhone or her tablet for the Smart Life parts, and you run the laptop parts. Or, as a shortcut, pair her lamps into a separate "home" in your Smart Life account so one key fetch covers both of you. See [household-and-platforms.md §1](../design/household-and-platforms.md#1-your-sisters-setup). Before anything else, check that her tablet can run Android apps ([§2](../design/household-and-platforms.md#2-huawei-tablet-checklist)).

> ⚠️ **Never commit `devices.json`, `tinytuya.json`, `tuya-raw.json`, `snapshot.json` or any file containing `local_key`.** The repo's `.gitignore` already blocks these names. Keep it that way.
>
> ⚠️ **Once you have the keys, don't remove and re-add devices.** Every re-pair generates a new `local_key`.

Background on *why* each step exists: [research/tuya-platform.md](../research/tuya-platform.md).

---

## Step 1: Inventory (5 min)

For each device, in the BARDI app open the device → ✏️/⋯ → **Device Information** and write down:
- name / room
- **Virtual ID** (= Tuya device id)
- **IP address** and **MAC**
- model (from the box or the app's product page)

In your router's admin page, **reserve a fixed IP (DHCP reservation)** for each MAC. Not strictly required, but it saves a lot of hassle later.

## Step 2: Quick check: can we skip re-pairing? (5 min, optional)

Try **Step 4, Option A** with the BARDI app. If the BARDI app has *Me → Settings → Account and Security → User Code* **and** its scanner accepts the QR login, you're done and can skip Step 3.
(It probably won't work, since OEM apps are separate account systems, but it only costs a minute to check.)

## Step 3: Move devices from BARDI to Smart Life (10 min per device)

1. Install **Smart Life** (Android/iOS). Create an account with region **Indonesia**. Don't use a "guest" account.
2. In the BARDI app: device → ✏️/⋯ → **Remove device**. If offered, pick **"Disconnect and wipe data"**.
3. Put the device into pairing mode:
   - **Bulb:** switch it off/on 3 times (on–off–on–off–on) until it blinks fast.
   - **Plug:** hold the button ~5 s until the LED blinks fast.
4. In Smart Life: **+ → Add Device**. Make sure your phone is on **2.4 GHz** Wi-Fi and Smart Life has Location + Local Network permissions.
5. Rename it in Smart Life to match your inventory, and check the new IP in Device Information. The **device id usually stays the same, but the key is new**.

If you use Google Assistant / Alexa with Bardi, you'll need to re-link via the Smart Life skill/action. That's optional, since the hub will cover it later.

## Step 4: Get the local keys

### Option A: QR login, no developer account (recommended)

Uses [`tuya-local-key`](https://github.com/vineetchoudhary/tuya-local-key), which wraps Tuya's official device-sharing SDK (the same login Home Assistant uses).

1. Smart Life → **Me → ⚙️ Settings → Account and Security → User Code**. Copy it.
2. On a computer with Python 3, follow the tool's README (it's `pip install` + run). Enter the User Code.
3. A QR code appears. In Smart Life: **+ → Scan**, then **Confirm login** (it may say "Home Assistant"). The QR expires in 1–2 min.
4. You get a list of devices with `id`, `local_key`, `ip`, `category`, `product_id` and the DP **spec**. Save the output **outside this repo** (or in `secrets/`, which is git-ignored).

### Option B: Tuya IoT developer account + TinyTuya wizard

Use this if A fails, or if you also want cloud API access.

1. Sign up at [iot.tuya.com](https://iot.tuya.com) (skip "account type").
2. **Cloud → Create Cloud Project**. Industry *Smart Home*, development method *Smart Home*, data center **Western America** (try **Singapore** if devices don't appear later).
3. Make sure the **IoT Core** and **Authorization** API services are subscribed. Note the **Access ID** and **Access Secret**.
4. Project → **Devices → Link Tuya App Account → Add App Account**. Scan the QR with Smart Life (**Me** tab → scan icon). Pick *Automatic* and *Read only status*.
   Link it under **Devices**, *not* under *Users*. Linking under Users returns 0 devices.
5. `pip install tinytuya` then `python -m tinytuya wizard`. Enter the Access ID/Secret and a device id, and pick region `us` (or `sg`).
6. It writes `devices.json` (names, ids, keys) plus `tuya-raw.json` (specs). All of these are git-ignored.

The IoT Core plan is a free **trial that expires after ~1 month**. You only need it for this step, and it can be extended later (*Extend trial period*) if you ever need to re-fetch keys.

## Step 5: Verify local control (10 min)

```bash
pip install tinytuya
```

```bash
python -m tinytuya scan
```

You should see each device with its **IP, id and protocol version** (3.3 / 3.4 / 3.5). Close the Smart Life app on your phone first, since a device accepts only one local connection at a time.

Then save this as `secrets/probe.py` (git-ignored) and fill in the values:

```python
import time
import tinytuya

lamp = tinytuya.BulbDevice(
    dev_id="bfxxxxxxxxxxxxxxxxxx",
    address="192.168.1.50",
    local_key="xxxxxxxxxxxxxxxx",
    version=3.3,           # from the scan
)

print("status:", lamp.status())   # dumps the raw DPs, e.g. {'20': True, '21': 'white', '22': 1000, ...}

lamp.turn_off(); time.sleep(1)
lamp.turn_on();  time.sleep(1)
lamp.set_brightness_percentage(30); time.sleep(1)
lamp.set_colour(255, 80, 0)        # orange
time.sleep(2)
lamp.set_white_percentage(80, 20)  # brightness 80 %, warm-ish
```

If the lamp toggles, phase 0 is done 🎉

## Step 6: Record each device's DP map (for building profiles)

For every device, copy the `status()` output and the spec section from Step 4 into `docs/devices/<model>.md`. **Remove the `local_key` first.** Example:

```markdown
# Bardi Smart Bulb RGBWW 12W
- category: dj · protocol: 3.3 · product_id: keyxxxx
- DPs: 20 switch_led bool · 21 work_mode enum[white,colour,scene,music] · 22 bright_value_v2 10–1000 · 23 temp_value_v2 0–1000 · 24 colour_data_v2 · 25 scene_data_v2 · 26 countdown_1 0–86400
```

Those files become the device profiles for the app (and later the hub).

## Step 7: Checks the app design depends on (15 min)

Run these with Smart Life **fully closed** (swipe it away), since it can hold the lamp's only LAN connection. Paste the results into `docs/devices/<model>.md`.

```python
import time, tinytuya
lamp = tinytuya.BulbDevice(dev_id="bf…", address="192.168.1.50", local_key="…", version=3.3)
lamp.set_socketPersistent(True)

# A) Full DP list: look for anything beyond 20–28 (e.g. sleep/wake/rhythm modes)
print(lamp.status())

# B) Live preview (DP 28): should sweep colours smoothly without changing DP 24
lamp.set_multiple_values({"20": True, "21": "colour"})
for h in range(0, 360, 10):
    lamp.set_value(28, f"0{h:04x}03e803e800000000", nowait=True)
    time.sleep(0.2)
print("DP 24 after sweep:", lamp.status()["dps"].get("24"))

# C) Countdown (DP 26): lamp is on, so does it turn off after 10 s?
lamp.set_value(26, 10); time.sleep(13)
print("after countdown:", lamp.status()["dps"].get("20"))
```

**D) Built-in effect strings:** in Smart Life pick each built-in scene/effect on a lamp, close Smart Life, then run `print(lamp.status()["dps"].get("25"))` and note the name → string. The app's effect list gets built from these.

**E) Protocol version:** from the `tinytuya scan` output (3.3 / 3.4 / 3.5).

**F) Second connection** (optional, only matters if two apps will ever share a lamp): run the script above in one terminal, keeping it open with `input()` at the end. In a second terminal, run `print(lamp.status())` with the same settings. Note what happens: the second one fails, **or** it works and the first one gets dropped.

## Step 8: Adding the power strip (later)

1. Buy a Tuya/Smart Life-compatible strip whose sockets are **individually switchable** (check the listing).
2. Pair it **straight into Smart Life**. No need to go through BARDI.
3. Re-run Step 4 to fetch its key. Fetching keys again is harmless, only *re-pairing* changes them, so the lamps' keys stay the same.
4. Test it:

```python
strip = tinytuya.OutletDevice(dev_id="…", address="192.168.1.60", local_key="…", version=3.3)
print(strip.status())          # expect DPs 1–4 (sockets), 5 or 7 (USB), 9–13 (countdowns), 38/40/41 (settings)
strip.turn_on(switch=3); time.sleep(1); strip.turn_off(switch=3)
```

5. Add `docs/devices/<strip-model>.md` with the DP list (no key!).

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Scan finds nothing | PC and devices on different subnets/VLANs, or AP/client isolation is on in the router, or a firewall is blocking UDP 6666/6667/7000 |
| `status()` returns error 914 / decrypt errors | wrong `local_key` (device re-paired after you fetched keys) or wrong `version` |
| Error 901 / connection refused | another client holds the single LAN connection (close Smart Life / any other script) |
| Wizard shows 0 devices | wrong data center, or app account linked under *Users* instead of *Devices* |
| Keys suddenly stopped working | someone removed/re-added the device. Re-fetch the keys (Step 4) |
