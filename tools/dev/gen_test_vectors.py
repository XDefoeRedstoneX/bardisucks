"""Generate golden test vectors for the Kotlin tuya-lan module, using TinyTuya
(the reference implementation that works with the real BARDI lamps).

Usage:  py tools\\dev\\gen_test_vectors.py
Writes: tuya-lan/src/test/resources/tuya35-vectors.json
"""

from __future__ import annotations

import hmac
import json
from hashlib import sha256
from pathlib import Path

from tinytuya.core import header as H
from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core.message_helper import TuyaMessage, pack_message

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tuya-lan" / "src" / "test" / "resources" / "tuya35-vectors.json"

KEY = b"0123456789abcdef"
VERSION_HEADER = b"3.5" + H.PROTOCOL_3x_HEADER


def frame(key: bytes, iv: bytes, seq: int, cmd: int, payload: bytes, retcode: int | None) -> str:
    msg = TuyaMessage(seq, cmd, retcode, payload, 0, True, H.PREFIX_6699_VALUE, iv)
    return pack_message(msg, hmac_key=key).hex()


def main() -> None:
    iv = bytes(range(12))
    control_json = b'{"protocol":5,"t":1700000000,"data":{"dps":{"20":true,"21":"colour","24":"007803e803e8"}}}'
    frames = [
        # client -> device (no retcode)
        {"name": "client_dp_query", "seq": 1, "cmd": 0x10, "payload": b"{}", "retcode": None},
        {"name": "client_control", "seq": 2, "cmd": 0x0D, "payload": VERSION_HEADER + control_json, "retcode": None},
        {"name": "client_heartbeat", "seq": 3, "cmd": 0x09, "payload": b'{"gwId":"abc","devId":"abc"}', "retcode": None},
        # device -> client (with retcode)
        {"name": "device_status_push", "seq": 77, "cmd": 0x08,
         "payload": VERSION_HEADER + b'{"protocol":4,"t":1700000001,"data":{"dps":{"20":false,"26":0}}}', "retcode": 0},
        {"name": "device_query_reply", "seq": 78, "cmd": 0x10,
         "payload": b'{"dps":{"20":true,"22":500}}', "retcode": 0},
        {"name": "device_empty_ack", "seq": 79, "cmd": 0x0D, "payload": b"", "retcode": 0},
    ]
    for f in frames:
        f["frame"] = frame(KEY, iv, f["seq"], f["cmd"], f["payload"], f["retcode"])
        f["payload"] = f["payload"].hex()

    local_nonce = bytes.fromhex("000102030405060708090a0b0c0d0e0f")
    remote_nonce = bytes.fromhex("f0e1d2c3b4a5968778695a4b3c2d1e0f")
    xored = bytes(a ^ b for a, b in zip(local_nonce, remote_nonce, strict=True))
    session_key = AESCipher(KEY).encrypt(xored, use_base64=False, pad=False, iv=local_nonce[:12])[12:28]
    session = {
        "local_key": KEY.hex(),
        "local_nonce": local_nonce.hex(),
        "remote_nonce": remote_nonce.hex(),
        "device_proof": hmac.new(KEY, local_nonce, sha256).hexdigest(),   # in SESS_KEY_NEG_RESP
        "client_proof": hmac.new(KEY, remote_nonce, sha256).hexdigest(),  # in SESS_KEY_NEG_FINISH
        "session_key": session_key.hex(),
        # a frame encrypted with the session key, to prove both sides agree
        "session_frame": frame(session_key, iv, 5, 0x10, b"{}", None),
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"key": KEY.hex(), "iv": iv.hex(), "frames": frames, "session": session}, indent=2) + "\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
