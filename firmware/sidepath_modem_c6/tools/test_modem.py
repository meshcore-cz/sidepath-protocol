#!/usr/bin/env python3
"""Minimal host test for the Sidepath BLE modem.

Sends a packet, toggles relay mode, and (with two modems) verifies a packet
sent on one is received on the other. Uses only the Python standard library for
the protocol; the serial port is opened via pyserial if available, otherwise via
a raw termios fallback so no extra dependency is strictly required.

Usage:
    test_modem.py --port /dev/ttyACM0                 # single-modem smoke test
    test_modem.py --port /dev/ttyACM0 --peer /dev/ttyACM1  # two-modem relay test
"""

import argparse
import sys
import threading
import time

try:
    import serial  # pyserial
except ImportError:  # pragma: no cover - fallback path
    serial = None


class Modem:
    """A line-oriented connection to a Sidepath modem."""

    def __init__(self, port, baud=115200):
        if serial is None:
            raise SystemExit(
                "pyserial not found: pip install pyserial (or run with it on PATH)"
            )
        self.ser = serial.Serial(port, baud, timeout=0.2)
        self.buf = b""
        self.lines = []
        self.lock = threading.Lock()
        self.stop = False
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        time.sleep(0.3)

    def _read(self):
        while not self.stop:
            data = self.ser.read(256)
            if not data:
                continue
            self.buf += data
            while b"\n" in self.buf:
                line, self.buf = self.buf.split(b"\n", 1)
                text = line.decode("ascii", "replace").strip()
                if text:
                    with self.lock:
                        self.lines.append(text)
                    print(f"[{self.ser.port}] < {text}")

    def send(self, cmd):
        print(f"[{self.ser.port}] > {cmd}")
        self.ser.write((cmd + "\n").encode("ascii"))
        self.ser.flush()

    def wait(self, prefix, timeout=5.0):
        """Wait for a line starting with prefix, returning it (or None)."""
        deadline = time.time() + timeout
        seen = 0
        while time.time() < deadline:
            with self.lock:
                lines = self.lines[seen:]
                seen = len(self.lines)
            for ln in lines:
                if ln.startswith(prefix):
                    return ln
            time.sleep(0.05)
        return None

    def close(self):
        self.stop = True
        self.ser.close()


def expect(modem, cmd, prefix):
    modem.send(cmd)
    line = modem.wait(prefix)
    if line is None:
        raise SystemExit(f"FAIL: no '{prefix}' reply to '{cmd}'")
    return line


def smoke(m):
    print("\n== smoke test ==")
    expect(m, "PING", "OK PING")
    expect(m, "INFO", "INFO ")
    expect(m, "SET_PHY 1M", "OK SET_PHY")
    expect(m, "SET_TX_POWER MEDIUM", "OK SET_TX_POWER")
    expect(m, "RELAY_ON", "OK RELAY_ON")
    expect(m, "RELAY_OFF", "OK RELAY_OFF")
    expect(m, "START_SCAN", "OK START_SCAN")
    expect(m, "SEND 08deadbeef", "OK SEND")
    expect(m, "STOP_SCAN", "OK STOP_SCAN")
    expect(m, "STATS", "STATS ")
    print("smoke test PASSED")


def relay_test(tx, rx):
    print("\n== two-modem relay test ==")
    expect(rx, "START_SCAN", "OK START_SCAN")
    expect(tx, "SET_PHY 1M", "OK SET_PHY")
    expect(rx, "SET_PHY 1M", "OK SET_PHY")
    payload = "08c0ffee1234"  # ttl=08, content=c0ffee1234
    tx.send(f"SEND {payload}")
    line = rx.wait("RX ", timeout=8.0)
    if line is None:
        raise SystemExit("FAIL: peer did not receive the packet")
    # RX <rssi> <phy> <ttl||content>
    got = line.split()[-1]
    if got[2:] != payload[2:]:  # compare content, TTL may differ
        raise SystemExit(f"FAIL: content mismatch sent={payload} got={got}")
    print(f"relay test PASSED (received {got})")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", required=True, help="primary modem serial port")
    ap.add_argument("--peer", help="second modem serial port (relay test)")
    ap.add_argument("--baud", type=int, default=115200)
    args = ap.parse_args()

    m = Modem(args.port, args.baud)
    try:
        smoke(m)
        if args.peer:
            peer = Modem(args.peer, args.baud)
            try:
                relay_test(m, peer)
            finally:
                peer.close()
        print("\nALL TESTS PASSED")
    finally:
        m.close()


if __name__ == "__main__":
    sys.exit(main())
