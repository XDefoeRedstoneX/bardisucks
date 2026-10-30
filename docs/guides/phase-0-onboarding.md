# Phase 0: get your Bardi devices onto local control

You can do this today, before any app code exists. At the end you'll have each device's **id, IP, protocol version and `local_key`**, and you'll have toggled a lamp from a Python script with no cloud involved.

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

Those files become the device profiles for the hub in phase 1.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Scan finds nothing | PC and devices on different subnets/VLANs, or AP/client isolation is on in the router, or a firewall is blocking UDP 6666/6667/7000 |
| `status()` returns error 914 / decrypt errors | wrong `local_key` (device re-paired after you fetched keys) or wrong `version` |
| Error 901 / connection refused | another client holds the single LAN connection (close Smart Life / any other script) |
| Wizard shows 0 devices | wrong data center, or app account linked under *Users* instead of *Devices* |
| Keys suddenly stopped working | someone removed/re-added the device. Re-fetch the keys (Step 4) |
