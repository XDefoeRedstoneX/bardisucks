"""Phase 0, step 5: find your devices on the LAN, test local control and run
the checks the app design depends on. Writes a report WITHOUT keys to
secrets/phase0-report.md that you can share.

Close the Smart Life app first (swipe it away): a lamp only accepts one LAN
connection at a time.

Usage (Windows, from the repo folder):
    py tools\\phase0\\probe.py                 # all devices in secrets/devices.json
    py tools\\phase0\\probe.py --only "Lampu"  # devices whose name contains "Lampu"
    py tools\\phase0\\probe.py --effects       # also capture built-in effect strings
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import tinytuya
from tinytuya import scanner

ROOT = Path(__file__).resolve().parents[2]
SECRETS = ROOT / "secrets"
LIGHT_DPS = ("20", "21", "22", "23", "24")


def load_devices(path: Path) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    devices = data["devices"] if isinstance(data, dict) else data
    for d in devices:  # also accept TinyTuya wizard output ("key" instead of "local_key")
        d.setdefault("local_key", d.get("key", ""))
    return [d for d in devices if d.get("local_key")]


def mask(device_id: str) -> str:
    return device_id[:4] + "…" + device_id[-2:] if len(device_id) > 8 else "…"


def ask(prompt: str) -> str:
    return input(f"    {prompt} ").strip().lower()


def fresh_dps(d: tinytuya.Device) -> dict:
    """Reconnect and read status, so leftover replies/pushes on the socket can't skew the result."""
    d.close()
    result = d.status()
    return result.get("dps", {}) if isinstance(result, dict) else {}


def scan(devices: list[dict], seconds: int) -> dict:
    print(f"Scanning the network for up to {seconds} s (Windows may ask to allow Python: choose Private networks)...")
    known = [{"id": d["id"], "key": d["local_key"], "name": d["name"]} for d in devices]
    found = scanner.devices(
        verbose=False, scantime=seconds, poll=False, byID=True, show_timer=False,
        assume_yes=True, tuyadevices=known, wantids=[d["id"] for d in devices],
    )
    return {dev_id: info for dev_id, info in found.items() if info.get("ip")}


def connect(dev: dict, ip: str, version: str) -> tinytuya.Device:
    d = tinytuya.Device(dev["id"], ip, dev["local_key"], version=float(version))
    d.set_socketPersistent(True)
    return d


def is_error(result) -> bool:
    return not isinstance(result, dict) or "Error" in result or "dps" not in result


def describe_error(result) -> str:
    if isinstance(result, dict) and result.get("Err") == "914":
        return "wrong local_key or protocol version (was the device re-paired after fetching keys?)"
    if isinstance(result, dict) and result.get("Err") == "901":
        return "connection refused/failed (is Smart Life open on a phone? close it and retry)"
    return str(result)


def test_live_preview(d: tinytuya.Device, name: str) -> dict:
    """DP 28 (control_data): sweep hues without saving them."""
    input(f"    [live preview] Look at '{name}': it will sweep through colours for ~8 s. Press Enter to start...")
    d.set_multiple_values({"20": True, "21": "colour"})
    time.sleep(0.5)
    before = fresh_dps(d).get("24")
    for h in range(0, 360, 10):
        d.set_value(28, f"0{h:04x}03e803e800000000", nowait=True)
        time.sleep(0.2)
    time.sleep(0.5)
    after = fresh_dps(d).get("24")
    smooth = ask("Did the lamp sweep through the colours? [y/n]")
    return {"sweep_seen": smooth.startswith("y"), "dp24_changed": before != after}


def test_countdown(d: tinytuya.Device) -> dict:
    """DP 26: does the countdown only turn the lamp off, or flip its state?"""
    print("    [countdown] Testing the on-device timer (about 30 s, the lamp will turn off and maybe on again)...")
    d.set_value(20, True)
    time.sleep(1)
    d.set_value(26, 10)
    time.sleep(13)
    after_on = fresh_dps(d).get("20")
    d.set_value(20, False)
    time.sleep(1)
    d.set_value(26, 10)
    time.sleep(13)
    after_off = fresh_dps(d).get("20")
    if after_on is False and after_off is True:
        verdict = "flips state (on->off and off->on)"
    elif after_on is False:
        verdict = "turns off; does not turn an off lamp on"
    else:
        verdict = f"unclear (on-test ended {after_on}, off-test ended {after_off})"
    return {"verdict": verdict}


def test_second_connection(dev: dict, ip: str, version: str, first: tinytuya.Device) -> str:
    second = tinytuya.Device(dev["id"], ip, dev["local_key"], version=float(version), connection_timeout=3,
                             connection_retry_limit=1, connection_retry_delay=1)
    second.set_socketPersistent(True)
    retries = (first.socketRetryLimit, first.socketRetryDelay)
    first.socketRetryLimit, first.socketRetryDelay = 1, 1  # fail fast instead of retrying for ~25 s
    try:
        first.close()
        first.status()        # first connection is open...
        r2 = second.status()  # ...while a second one connects...
        r1 = first.status()   # ...and then the first one tries to talk again
    finally:
        second.close()
        first.socketRetryLimit, first.socketRetryDelay = retries
    ok1, ok2 = not is_error(r1), not is_error(r2)
    if ok1 and ok2:
        return "both connections worked at the same time"
    if ok1:
        return "second connection was refused, first kept working"
    if ok2:
        return "second connection took over, the first one lost the lamp"
    return "both failed while two connections were attempted"


def capture_effects(d: tinytuya.Device) -> list[tuple[str, str]]:
    print("    [effects] In Smart Life, pick a built-in scene/effect on this lamp, then CLOSE Smart Life.")
    effects = []
    while True:
        name = input("    Effect name (or Enter to finish): ").strip()
        if not name:
            return effects
        value = fresh_dps(d).get("25")  # reconnect: Smart Life may have used the lamp meanwhile
        print(f"      DP 25 = {value}")
        effects.append((name, value))


def spec_for(code: str | None, dev: dict) -> str:
    if not code:
        return ""
    spec = dev.get("functions", {}).get(code) or dev.get("status_range", {}).get(code)
    if not spec:
        return ""
    values = spec.get("values")
    return f"{spec.get('type', '')} {json.dumps(values, ensure_ascii=False) if values else ''}".strip()


def report_device(dev: dict, net: dict | None, dps: dict | None, checks: dict) -> str:
    lines = [f"## {dev['name']}", ""]
    product = f"{dev.get('product_name', '?')} (`{dev.get('product_id', '?')}`)"
    lines.append(f"- category: `{dev.get('category', '?')}` · product: {product}")
    lines.append(f"- device id: `{mask(dev['id'])}`")
    if net:
        lines.append(f"- LAN: {net['ip']} · protocol **{net.get('version', '?')}**")
    for k, v in checks.items():
        lines.append(f"- {k}: {v}")
    if dps:
        dp_map = dev.get("dp_map", {})
        lines += ["", "| DP | code | value | spec |", "|---:|---|---|---|"]
        for dp in sorted(dps, key=lambda x: int(x) if str(x).isdigit() else 999):
            code = dp_map.get(str(dp))
            lines.append(f"| {dp} | {code or ''} | `{dps[dp]}` | {spec_for(code, dev)} |")
    lines.append("")
    return "\n".join(lines)


def probe(dev: dict, net: dict | None, args, done_second_conn: list) -> str:
    print(f"\n== {dev['name']} ({dev.get('category', '?')})")
    if not net:
        print("   not found on the network")
        return report_device(dev, None, None, {"result": "NOT FOUND on LAN (same Wi-Fi? firewall? device offline?)"})

    version = str(net.get("version") or "3.3")
    print(f"   found at {net['ip']}, protocol {version}")
    d = connect(dev, net["ip"], version)
    result = d.status()
    if is_error(result):
        print(f"   ERROR: {describe_error(result)}")
        return report_device(dev, net, None, {"result": f"ERROR: {describe_error(result)}"})

    dps = result["dps"]
    checks = {"local control": "OK (status read with local key)"}
    is_light = dev.get("category") == "dj" or all(k in dps for k in LIGHT_DPS)
    if is_light and not args.no_tests:
        original = {k: dps[k] for k in LIGHT_DPS if k in dps}
        try:
            lp = test_live_preview(d, dev["name"])
            checks["live preview (DP 28)"] = (
                f"sweep {'seen' if lp['sweep_seen'] else 'NOT seen'}; DP 24 {'changed' if lp['dp24_changed'] else 'unchanged'}"
            )
            checks["countdown (DP 26)"] = test_countdown(d)["verdict"]
            if not done_second_conn:
                checks["second LAN connection"] = test_second_connection(dev, net["ip"], version, d)
                done_second_conn.append(True)
            if args.effects:
                for name, value in capture_effects(d):
                    checks[f"effect '{name}' (DP 25)"] = f"`{value}`"
        finally:
            d.set_multiple_values(original)  # put the lamp back how it was
            print("   restored the lamp's previous state")
    d.close()
    return report_device(dev, net, dps, checks)


def main() -> None:
    parser = argparse.ArgumentParser(description="Probe Tuya devices locally and write a key-free report.")
    parser.add_argument("--devices", default=str(SECRETS / "devices.json"),
                        help="output of get_keys.py (or TinyTuya devices.json)")
    parser.add_argument("--only", help="only devices whose name contains this text")
    parser.add_argument("--effects", action="store_true", help="also capture built-in effect strings (interactive)")
    parser.add_argument("--no-tests", action="store_true", help="only read status, skip the lamp tests")
    parser.add_argument("--scan-seconds", type=int, default=20)
    parser.add_argument("--out", default=str(SECRETS / "phase0-report.md"))
    args = parser.parse_args()

    path = Path(args.devices)
    if not path.exists():
        sys.exit(f"{path} not found. Run get_keys.py first.")
    devices = load_devices(path)
    if args.only:
        devices = [d for d in devices if args.only.lower() in d["name"].lower()]
    if not devices:
        sys.exit("No devices with a local key to probe.")

    found = scan(devices, args.scan_seconds)
    sections, done_second_conn = [], []
    for dev in devices:
        try:
            sections.append(probe(dev, found.get(dev["id"]), args, done_second_conn))
        except KeyboardInterrupt:
            print("\nStopped.")
            break
        except Exception as exc:  # keep going with the other devices
            print(f"   ERROR: {exc}")
            sections.append(report_device(dev, found.get(dev["id"]), None, {"result": f"ERROR: {exc}"}))

    header = [
        "# Phase 0 report", "",
        f"_Generated {time.strftime('%Y-%m-%d %H:%M')} · tinytuya {tinytuya.__version__} · contains no local keys_", "",
    ]
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(header) + "\n" + "\n\n".join(sections), encoding="utf-8")
    print(f"\nReport written to {out} (no keys inside, safe to share).")


if __name__ == "__main__":
    main()
