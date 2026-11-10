"""Phase 0, step 4: fetch the local keys of your Smart Life devices.

Logs in with a QR code that you scan in the Smart Life app (the same login
Home Assistant uses, via Tuya's device-sharing SDK), so no Tuya developer
account is needed. Writes everything to secrets/devices.json, which is
git-ignored. The file contains your local keys: never share or commit it.

Usage (Windows, from the repo folder):
    py tools\\phase0\\get_keys.py
    py tools\\phase0\\get_keys.py --out secrets\\devices-sister.json
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import webbrowser
from pathlib import Path

import qrcode
import qrcode.image.svg
from tuya_sharing import LoginControl, Manager

# Home Assistant's public app registration for Tuya's device-sharing login.
CLIENT_ID = "HA_3y9q4ak7g4ephrvke"
SCHEMA = "haauthorize"
QR_PREFIX = "tuyaSmart--qrLogin?token="

ROOT = Path(__file__).resolve().parents[2]
SECRETS = ROOT / "secrets"
POLL_SECONDS = 150


def parse_values(raw: str | None):
    """Spec ranges come back as JSON strings; keep the raw text if they don't parse."""
    if not raw:
        return None
    try:
        return json.loads(raw)
    except (TypeError, ValueError):
        return raw


def show_qr(content: str) -> Path:
    qr = qrcode.QRCode(border=2)
    qr.add_data(content)
    qr.make(fit=True)
    try:
        qr.print_ascii(invert=True)
    except Exception:
        pass  # terminal can't draw it; the browser copy below still works
    path = SECRETS / "login-qr.svg"
    qr.make_image(image_factory=qrcode.image.svg.SvgPathImage).save(str(path))
    webbrowser.open(path.as_uri())
    return path


def wait_for_login(login: LoginControl, token: str, user_code: str) -> dict:
    deadline = time.time() + POLL_SECONDS
    while time.time() < deadline:
        ok, info = login.login_result(token, CLIENT_ID, user_code)
        if ok:
            return info
        time.sleep(2)
    sys.exit("QR code expired before the login was confirmed. Run the script again.")


def export_device(device, home_name: str) -> dict:
    return {
        "id": device.id,
        "name": device.name,
        "home": home_name,
        "local_key": device.local_key,
        "category": device.category,
        "product_id": device.product_id,
        "product_name": device.product_name,
        "online": device.online,
        "support_local": device.support_local,
        "status": dict(device.status),
        # dp id -> Tuya code, e.g. {"20": "switch_led"}
        "dp_map": {str(dp): info.get("status_code") for dp, info in device.local_strategy.items()},
        "functions": {
            code: {"type": f.type, "values": parse_values(f.values)} for code, f in device.function.items()
        },
        "status_range": {
            code: {"type": r.type, "values": parse_values(r.values)} for code, r in device.status_range.items()
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Fetch Tuya local keys via Smart Life QR login.")
    parser.add_argument("--user-code", help="Smart Life: Me > Settings > Account and Security > User Code")
    parser.add_argument("--out", default=str(SECRETS / "devices.json"), help="output file (default: secrets/devices.json)")
    args = parser.parse_args()

    SECRETS.mkdir(exist_ok=True)
    user_code = args.user_code or input(
        "User Code (Smart Life > Me > Settings > Account and Security > User Code): "
    ).strip()

    login = LoginControl()
    response = login.qr_code(CLIENT_ID, SCHEMA, user_code)
    if not response.get("success"):
        sys.exit(f"Tuya refused the User Code: {response.get('msg') or response}")
    token = response["result"]["qrcode"]

    print("\nIn Smart Life: tap + (top right) > Scan, scan this QR code, then confirm the login.")
    print("The QR code also opened in your browser. It expires in about 2 minutes.\n")
    qr_path = show_qr(QR_PREFIX + token)
    try:
        info = wait_for_login(login, token, user_code)
    finally:
        qr_path.unlink(missing_ok=True)

    manager = Manager(CLIENT_ID, user_code, info["terminal_id"], info["endpoint"], info)
    devices = []
    for home in manager.home_repository.query_homes():
        for device in manager.device_repository.query_devices_by_home(home.id):
            devices.append(export_device(device, home.name))

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"devices": devices}, indent=2, ensure_ascii=False), encoding="utf-8")

    # End this login session again; we only needed it once.
    try:
        manager.user_repository.unload(info["terminal_id"])
    except Exception:
        pass

    print(f"\nFound {len(devices)} device(s):")
    for d in devices:
        key = "key OK" if d["local_key"] else "NO KEY (Bluetooth-only?)"
        print(f"  - {d['name']:<24} {d['category']:<4} {d['product_name'] or '':<28} home={d['home']}  {key}")
    print(f"\nSaved to {out}  (contains local keys: keep it private, it's git-ignored)")
    print("Next: py tools\\phase0\\probe.py")


if __name__ == "__main__":
    main()
