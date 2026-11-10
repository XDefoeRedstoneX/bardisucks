# The `bardi` command-line tool (phase 1)

`bardi` drives your lamps through the app's own Kotlin protocol library (`tuya-lan`), the code the Android app will use. It's the first real test of that library on your lamps, and it already does what the BARDI app can't: **colour and brightness on a group**.

Commands run on every matching lamp **at the same time**, so `all` (or any shared part of a name) acts as a group.

## Setup on Windows (one time, ~10 min)

1. **Install Java** (needed to build/run Kotlin code). In PowerShell:
   ```powershell
   winget install -e --id Microsoft.OpenJDK.21
   ```
   Then close and reopen PowerShell.
2. **Get the latest repo** (Download ZIP again, or `git pull`), and open PowerShell in the repo folder.
3. **Build the tool.** The first run downloads Gradle and libraries, so it takes a few minutes:
   ```powershell
   .\gradlew.bat :cli:installDist
   ```
4. **Save the lamps' LAN IPs** into `secrets\devices.json` (your earlier probe run predates this feature). Close Smart Life first:
   ```powershell
   py tools\phase0\probe.py --no-tests
   ```
5. Make a short alias for this PowerShell window:
   ```powershell
   Set-Alias bardi .\cli\build\install\bardi\bin\bardi.bat
   ```

## Try it

```powershell
bardi list
```

```powershell
bardi status all
```

```powershell
bardi color all 200
```
Both lamps go blue at once. (Hue 0–360: 0 red, 30 orange, 60 yellow, 120 green, 200 blue, 280 purple.)

```powershell
bardi color all 30 80 40
```
Orange at 80 % saturation and 40 % brightness.

```powershell
bardi brightness all 25
```
This keeps each lamp's current colour or white mode.

```powershell
bardi white all 70 20
```
White at 70 % brightness and 20 % "warmth" (0 = warmest).

```powershell
bardi toggle all
```
If either lamp is on, both go off. Otherwise both go on.

```powershell
bardi sweep all
```
A rainbow preview on both lamps (DP 28), then they go back to how they were.

```powershell
bardi effect all 04464602007803e803e800000000464602007803e8000a00000000
```
A built-in style "breathing green" effect, running on the bulbs themselves.

```powershell
bardi watch all
```
Live updates. Flip a lamp with Smart Life or its switch and the change shows up. Ctrl+C to stop.

`all` can be any part of a name: `bardi off "RGBWW 2"` only targets "BARDI Light Bulb 12W RGBWW 2". Rename the lamps in Smart Life and run `get_keys.py` again to get nicer names (your saved IPs are kept).

Smart Life can stay open, since your bulbs accept two connections at once.

## What to send back

Run these and paste the output (no keys are printed):
```powershell
bardi status all
```

```powershell
bardi color all 200
```

```powershell
bardi sweep all
```
Also tell me whether both lamps changed together and whether the sweep looked smooth.

## Without real lamps

A fake protocol-3.5 lamp is included for testing:
```powershell
py tools\dev\fake_lamp.py
```
In a second window:
```powershell
bardi --id fake0lamp0000000035a --key 0123456789abcdef --ip 127.0.0.1 status x
```

## For development

- `.\gradlew.bat build` compiles everything and runs the tests (frame encoding is checked byte-for-byte against TinyTuya's, in `tuya-lan/src/test/resources/tuya35-vectors.json`).
- `py tools\dev\gen_test_vectors.py` regenerates those vectors.

## Troubleshooting

| Message | Fix |
|---|---|
| `No LAN IP for …` | run step 4, or add `--ip 192.168.100.133` for a single lamp |
| `No handshake reply … wrong local key` | the lamp was re-paired (run `get_keys.py` again) or its IP changed (run step 4 again) |
| `Can't connect to …` | lamp is off at the wall, or the PC is on a different network |
| `'java' is not recognized` / `JAVA_HOME` | reopen PowerShell after installing Java |
| `Protocol 3.3 isn't supported yet` | only 3.5 is implemented so far (your bulbs are 3.5). The power strip may need 3.3/3.4 later |
