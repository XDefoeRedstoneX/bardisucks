# Phase 0: get your Bardi devices onto local control (Windows)

At the end you'll have each lamp's **id, IP, protocol version and `local_key`**, you'll have controlled the lamps from your PC with no cloud involved, and you'll have a **report (with no keys in it)** to send back so the app can be built against your real lamps.

Time: about 45 minutes for the two 12 W lamps. The power strip gets a short version when you buy it ([Step 7](#step-7-adding-the-power-strip-later)). Your sister's lamps: [Step 8](#step-8-your-sisters-lamps).

> ⚠️ **`secrets\devices.json` contains your lamps' keys.** It's git-ignored. Never commit it or send it to anyone. The report from Step 5 has no keys and is safe to share.
>
> ⚠️ **Once you have the keys, don't remove and re-add the lamps.** Every re-pair generates a new `local_key`.

Background on *why* each step exists: [research/tuya-platform.md](../research/tuya-platform.md).

---

## Step 0: Set up the Windows PC (10 min)

1. **Install Python.** In PowerShell:
   ```powershell
   winget install -e --id Python.Python.3.12
   ```
   (Or use the installer from python.org and tick **"Add python.exe to PATH"**.) Close and reopen PowerShell, then check that `py --version` prints 3.12.
2. **Get this repo:** on GitHub, **Code → Download ZIP**, then extract it (e.g. to `C:\bardisucks`). Or `git clone` it if you have Git.
3. **Open PowerShell in that folder:** in Explorer, right-click the folder → **Open in Terminal**.
4. **Install the script dependencies:**
   ```powershell
   py -m pip install -r tools\phase0\requirements.txt
   ```
5. **Set your Wi-Fi to "Private"** (otherwise Windows blocks the lamps' discovery broadcasts): *Settings → Network & internet → Wi-Fi → your network → Network profile type → **Private***.
   Later, when Windows asks whether Python may use the network, tick **Private networks** and click **Allow**.

## Step 1: Inventory (5 min)

For each lamp, in the BARDI app open the lamp → ✏️/⋯ → **Device Information** and write down the name, **IP address** and **MAC**.

In your router's admin page, **reserve a fixed IP (DHCP reservation)** for each MAC. Not strictly required, but it saves hassle later.

## Step 2: Quick check: can we skip re-pairing? (2 min, optional)

If the BARDI app has *Me → Settings → Account and Security → **User Code***, try Step 4 with that code and scan the QR with the **BARDI** app. If it works, skip Step 3. (It probably won't, since BARDI is a separate account system, but it's a quick check.)

## Step 3: Move the lamps from BARDI to Smart Life (10 min per lamp)

1. Install **Smart Life** on your phone. Create an account with region **Indonesia**. Don't use a "guest" account.
2. In the BARDI app: lamp → ✏️/⋯ → **Remove device**. If offered, pick **"Disconnect and wipe data"**.
3. Put the lamp in pairing mode: switch it off/on 3 times (on–off–on–off–on) until it **blinks fast**.
4. In Smart Life: **+ → Add Device**. Your phone must be on **2.4 GHz** Wi-Fi, and Smart Life needs Location + Nearby devices permissions.
5. Give it the name you want (the app will reuse it), e.g. "Lampu Kamar".

If you used Google Assistant / Alexa with BARDI, re-link them through Smart Life (optional).

## Step 4: Get the keys (5 min)

```powershell
py tools\phase0\get_keys.py
```

1. It asks for your **User Code**: in Smart Life, **Me → ⚙️ Settings → Account and Security → User Code**.
2. A QR code appears in the terminal and in your browser. In Smart Life: **+ (top right) → Scan**, scan it, and **confirm the login** (it may say "Home Assistant", because this uses Home Assistant's official Tuya login). The QR expires after about 2 minutes.
3. It lists your devices and saves everything to `secrets\devices.json`.

<details>
<summary>Fallback if that doesn't work: Tuya developer account + TinyTuya wizard</summary>

1. Sign up at [iot.tuya.com](https://iot.tuya.com) (skip "account type").
2. **Cloud → Create Cloud Project**: *Smart Home*, data center **Western America** (try **Singapore** if no devices show up).
3. Make sure the **IoT Core** and **Authorization** services are subscribed. Note the **Access ID / Secret**.
4. Project → **Devices → Link Tuya App Account → Add App Account**, and scan with Smart Life. Link it under **Devices**, *not* *Users*.
5. In the repo folder: `py -m tinytuya wizard` (region `us` or `sg`). It writes `devices.json`. Then use `--devices devices.json` with the probe in Step 5.

The IoT Core plan is a free trial that expires after about a month. You only need it once.
</details>

## Step 5: Test the lamps + run the checks (10 min)

**Close Smart Life on your phone first** (swipe it away), since a lamp accepts only one local connection at a time. Then:

```powershell
py tools\phase0\probe.py --effects
```

What it does:
1. **Scans** the Wi-Fi for your lamps (~20 s) and finds their IP and protocol version.
2. **Reads each lamp's full status locally** with its key. This proves local control works.
3. Runs the checks the app design depends on, asking you a couple of questions:
   - **Live colour preview:** the lamp sweeps through colours for ~8 s. Answer whether you saw it.
   - **On-device timer:** ~30 s. The lamp turns off and maybe back on by itself.
   - **Second connection:** what the lamp does if two apps connect at once (first lamp only).
   - **Effects** (because of `--effects`): pick a built-in scene/effect on the lamp in Smart Life, close Smart Life, type the effect's name, and repeat for each effect. Press Enter on an empty name to finish.
4. **Puts the lamp back** how it was.
5. Writes **`secrets\phase0-report.md`**: DP list, protocol version and check results. **No keys inside.**

Skip `--effects` if you're short on time. `--only "Kamar"` probes just one lamp.

## Step 6: Send me the report

Open `secrets\phase0-report.md` and paste its contents in the chat (or commit it as `docs/devices/bardi-bulb-12w.md`). That's everything needed to start building the lamp code. 🎉

## Step 7: Adding the power strip (later)

1. Buy a Tuya/Smart Life-compatible strip whose sockets are **individually switchable** (check the listing).
2. Pair it **straight into Smart Life**. No need to go through BARDI.
3. Run `py tools\phase0\get_keys.py` again. Fetching keys again is harmless (only *re-pairing* changes keys).
4. Run `py tools\phase0\probe.py --only "<strip name>"`. For a strip it only **reads** the status and doesn't switch any sockets.
5. Send me that report too.

## Step 8: Your sister's lamps

Same steps, with her lamps going into **her own Smart Life account** (Smart Life on her iPhone or tablet), and the PC part on your PC:

```powershell
py tools\phase0\get_keys.py --out secrets\devices-sister.json
```

```powershell
py tools\phase0\probe.py --devices secrets\devices-sister.json --out secrets\phase0-report-sister.md
```

For `get_keys.py`, use *her* User Code and have her scan the QR with her Smart Life. Check first that her tablet runs Android apps ([checklist](../design/household-and-platforms.md#2-huawei-tablet-checklist)).

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `py` is not recognized | Python isn't on PATH. Reopen PowerShell, or reinstall with "Add python.exe to PATH" |
| Smart Life says the QR/login is invalid | wrong User Code, or the QR expired (2 min). Run `get_keys.py` again |
| Probe: "NOT FOUND on LAN" | Wi-Fi profile is **Public** (set it to Private, Step 0.5), Python was blocked (*Windows Security → Firewall → Allow an app → Python → Private*), PC on a different network/guest Wi-Fi, or the router's AP/client isolation is on |
| Error "wrong local_key or protocol version" (914) | the lamp was re-paired after you fetched keys. Run `get_keys.py` again |
| Error "connection refused" (901) | Smart Life (or another script) holds the lamp's single connection. Close it and retry |
| Fallback wizard shows 0 devices | wrong data center, or app account linked under *Users* instead of *Devices* |
