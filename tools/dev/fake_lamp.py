"""A fake Tuya protocol-3.5 RGBCW lamp, for testing the app/CLI without real lamps.

Speaks the same LAN protocol as the BARDI 12 W bulbs (session-key handshake,
AES-GCM 0x6699 frames), with the DP layout from the phase 0 report. Like the
real bulbs it accepts several connections at once, pushes status changes to
every connected client, and its countdown (DP 26) flips the power state.

Usage:
    py tools\\dev\\fake_lamp.py                       # listens on 127.0.0.1:6668
    py tools\\dev\\fake_lamp.py --host 0.0.0.0 --port 6668
Device id / local key default to the values below (also used by the tests).
"""

from __future__ import annotations

import argparse
import hmac
import json
import os
import socket
import threading
import time
from hashlib import sha256

from tinytuya.core import header as H
from tinytuya.core.crypto_helper import AESCipher
from tinytuya.core.message_helper import TuyaMessage, pack_message, parse_header, unpack_message

DEV_ID = "fake0lamp0000000035a"
LOCAL_KEY = b"0123456789abcdef"
VERSION_HEADER = b"3.5" + H.PROTOCOL_3x_HEADER

SESS_KEY_NEG_START, SESS_KEY_NEG_RESP, SESS_KEY_NEG_FINISH = 3, 4, 5
CONTROL, STATUS, HEART_BEAT, DP_QUERY = 7, 8, 9, 0x0A
CONTROL_NEW, DP_QUERY_NEW, UPDATEDPS = 0x0D, 0x10, 0x12


class Lamp:
    def __init__(self, verbose: bool):
        self.dps = {"20": True, "21": "white", "22": 1000, "23": 1000, "24": "000003e803e8",
                    "25": "000e0d0000000000000000c80000", "26": 0, "34": False}
        self.clients: list[Client] = []
        self.lock = threading.Lock()
        self.seq = 0
        self.verbose = verbose

    def next_seq(self) -> int:
        with self.lock:
            self.seq += 1
            return self.seq

    def apply(self, changes: dict) -> dict:
        changed = {}
        for dp, value in changes.items():
            if dp in ("27", "28"):  # music / live preview: shown, never stored
                continue
            self.dps[dp] = value
            changed[dp] = value
            if dp == "26" and value:
                threading.Thread(target=self._countdown, args=(int(value),), daemon=True).start()
        return changed

    def _countdown(self, seconds: int) -> None:
        time.sleep(seconds)
        self.dps["20"] = not self.dps["20"]
        self.dps["26"] = 0
        self.broadcast({"20": self.dps["20"], "26": 0})

    def broadcast(self, changed: dict) -> None:
        for c in list(self.clients):
            c.push(changed)


class Client:
    def __init__(self, lamp: Lamp, conn: socket.socket, addr):
        self.lamp, self.conn, self.addr = lamp, conn, addr
        self.key = LOCAL_KEY  # becomes the session key after the handshake
        self.ready = False
        self.local_nonce = self.remote_nonce = b""
        self.send_lock = threading.Lock()

    def send(self, cmd: int, payload: bytes) -> None:
        msg = TuyaMessage(self.lamp.next_seq(), cmd, 0, payload, 0, True, H.PREFIX_6699_VALUE, True)
        with self.send_lock:
            self.conn.sendall(pack_message(msg, hmac_key=self.key))

    def push(self, changed: dict) -> None:
        if not self.ready or not changed:
            return
        body = {"protocol": 4, "t": int(time.time()), "data": {"dps": changed}}
        try:
            self.send(STATUS, VERSION_HEADER + json.dumps(body, separators=(",", ":")).encode())
        except OSError:
            pass

    def handle(self, msg) -> None:
        payload = msg.payload
        if payload.startswith(VERSION_HEADER):
            payload = payload[len(VERSION_HEADER):]
        if msg.cmd == SESS_KEY_NEG_START:
            self.local_nonce, self.remote_nonce = payload[:16], os.urandom(16)
            proof = hmac.new(LOCAL_KEY, self.local_nonce, sha256).digest()
            self.send(SESS_KEY_NEG_RESP, self.remote_nonce + proof)
        elif msg.cmd == SESS_KEY_NEG_FINISH:
            if payload[:32] != hmac.new(LOCAL_KEY, self.remote_nonce, sha256).digest():
                raise ConnectionError("handshake HMAC mismatch")
            xored = bytes(a ^ b for a, b in zip(self.local_nonce, self.remote_nonce, strict=True))
            self.key = AESCipher(LOCAL_KEY).encrypt(xored, use_base64=False, pad=False, iv=self.local_nonce[:12])[12:28]
            self.ready = True
        elif msg.cmd in (DP_QUERY, DP_QUERY_NEW):
            body = {"protocol": 4, "t": int(time.time()), "data": {"dps": self.lamp.dps}}
            self.send(msg.cmd, json.dumps(body, separators=(",", ":")).encode())
        elif msg.cmd in (CONTROL, CONTROL_NEW):
            request = json.loads(payload)
            dps = request.get("data", {}).get("dps") or request.get("dps", {})
            self.lamp.verbose and print(f"[{self.addr[0]}] set {dps}")
            self.send(msg.cmd, b"")  # empty ack
            self.lamp.broadcast(self.lamp.apply(dps))
        elif msg.cmd == HEART_BEAT:
            self.send(HEART_BEAT, b"")
        elif msg.cmd == UPDATEDPS:
            self.send(UPDATEDPS, b"")
        else:
            self.send(msg.cmd, b"")

    def run(self) -> None:
        buf = b""
        try:
            while True:
                chunk = self.conn.recv(4096)
                if not chunk:
                    return
                buf += chunk
                while len(buf) >= 18:
                    hdr = parse_header(buf)
                    if len(buf) < hdr.total_length:
                        break
                    frame, buf = buf[:hdr.total_length], buf[hdr.total_length:]
                    msg = unpack_message(frame, hmac_key=self.key, no_retcode=True)
                    if not msg.crc_good:
                        raise ConnectionError("GCM tag check failed (wrong key?)")
                    self.handle(msg)
        except (OSError, ConnectionError, ValueError) as exc:
            self.lamp.verbose and print(f"[{self.addr[0]}] closed: {exc}")
        finally:
            self.conn.close()
            if self in self.lamp.clients:
                self.lamp.clients.remove(self)


def serve(host: str, port: int, verbose: bool = True) -> Lamp:
    lamp = Lamp(verbose)
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port))
    srv.listen(5)

    def accept_loop():
        while True:
            conn, addr = srv.accept()
            client = Client(lamp, conn, addr)
            lamp.clients.append(client)
            threading.Thread(target=client.run, daemon=True).start()

    threading.Thread(target=accept_loop, daemon=True).start()
    return lamp


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Fake Tuya 3.5 lamp")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=6668)
    args = ap.parse_args()
    serve(args.host, args.port)
    print(f"Fake lamp on {args.host}:{args.port}  id={DEV_ID}  key={LOCAL_KEY.decode()}  (Ctrl+C to stop)")
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
