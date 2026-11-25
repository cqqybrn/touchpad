"""TouchPad PC agent.

Receives controller state from the phone over UDP and feeds a virtual
Xbox 360 controller (ViGEmBus) plus a few keyboard keys (FFX booster F1-F5).

Packet (little-endian):
  magic b'TP', ver u8=1, type u8
  type 1 (state): seq u32, buttons u16 (XUSB bits), lx ly rx ry i16, lt rt u8, keys u8
  type 2 (ping):  seq u32, t u64   -> echoed back as type 3 (pong)
"""
import ctypes
import functools
import http.server
import os
import socket
import struct
import sys
import threading
import time

import vgamepad as vg

UDP_PORT = 39500
HTTP_PORT = 39501
FAILSAFE_S = 0.30
DIST_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "dist")

STATE_FMT = "<2sBBIHhhhhBBB"
STATE_LEN = struct.calcsize(STATE_FMT)
PING_FMT = "<2sBBIQ"
PING_LEN = struct.calcsize(PING_FMT)

# --- keyboard via SendInput (scan codes, works with DirectInput games) -------
KEY_SCANCODES = [0x3B, 0x3C, 0x3D, 0x3E, 0x3F]  # F1..F5 -> keys bits 0..4

user32 = ctypes.WinDLL("user32", use_last_error=True)
ULONG_PTR = ctypes.c_size_t


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", ctypes.c_ushort), ("wScan", ctypes.c_ushort),
                ("dwFlags", ctypes.c_ulong), ("time", ctypes.c_ulong),
                ("dwExtraInfo", ULONG_PTR)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", ctypes.c_long), ("dy", ctypes.c_long),
                ("mouseData", ctypes.c_ulong), ("dwFlags", ctypes.c_ulong),
                ("time", ctypes.c_ulong), ("dwExtraInfo", ULONG_PTR)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]


class INPUT(ctypes.Structure):
    _fields_ = [("type", ctypes.c_ulong), ("u", _INPUTUNION)]


KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_SCANCODE = 0x0008


def send_key(scan, down):
    inp = INPUT(type=1)
    inp.u.ki = KEYBDINPUT(0, scan, KEYEVENTF_SCANCODE | (0 if down else KEYEVENTF_KEYUP), 0, 0)
    user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT))


# --- controller state ---------------------------------------------------------
class Pad:
    def __init__(self):
        self.gp = vg.VX360Gamepad()
        self.keys = 0
        self.neutral()

    def apply(self, buttons, lx, ly, rx, ry, lt, rt, keys):
        r = self.gp.report
        r.wButtons = buttons
        r.sThumbLX, r.sThumbLY, r.sThumbRX, r.sThumbRY = lx, ly, rx, ry
        r.bLeftTrigger, r.bRightTrigger = lt, rt
        self.gp.update()
        changed = keys ^ self.keys
        for i, sc in enumerate(KEY_SCANCODES):
            if changed & (1 << i):
                send_key(sc, bool(keys & (1 << i)))
        self.keys = keys

    def neutral(self):
        self.apply(0, 0, 0, 0, 0, 0, 0, 0)


def seq_newer(a, b):
    return ((a - b) & 0xFFFFFFFF) < 0x80000000 and a != b


def local_ips():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ips)


def start_http():
    os.makedirs(DIST_DIR, exist_ok=True)
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *a):
            pass

    handler = functools.partial(Quiet, directory=DIST_DIR)
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()


def main():
    pad = Pad()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 16)
    sock.bind(("0.0.0.0", UDP_PORT))
    sock.settimeout(0.05)
    start_http()

    print("TouchPad 电脑端已启动")
    print(f"  手柄端口 UDP {UDP_PORT}    APK 下载 http://<电脑IP>:{HTTP_PORT}/")
    print("  本机 IP:", ", ".join(local_ips()) or "?")
    print("  Ctrl+C 退出\n")

    client = None
    last_seq = None
    last_rx = 0.0
    active = False
    count = 0
    stat_t = time.time()

    while True:
        try:
            data, addr = sock.recvfrom(256)
        except socket.timeout:
            data = None
        except ConnectionResetError:
            data = None
        now = time.time()

        if data and len(data) >= 4 and data[:2] == b"TP" and data[2] == 1:
            ptype = data[3]
            if ptype == 2 and len(data) >= PING_LEN:
                sock.sendto(b"TP\x01\x03" + data[4:PING_LEN], addr)
            elif ptype == 1 and len(data) >= STATE_LEN:
                _, _, _, seq, btn, lx, ly, rx, ry, lt, rt, keys = struct.unpack_from(STATE_FMT, data)
                if addr != client:
                    client, last_seq = addr, None
                    print(f"\n手机已连接: {addr[0]}:{addr[1]}")
                if last_seq is None or seq_newer(seq, last_seq):
                    last_seq = seq
                    pad.apply(btn, lx, ly, rx, ry, lt, rt, keys)
                    last_rx, active = now, True
                    count += 1

        if active and now - last_rx > FAILSAFE_S:
            pad.neutral()
            active = False
            last_seq = None
            print("\n信号中断，已松开所有按键")

        if now - stat_t >= 1.0:
            rate = count / (now - stat_t)
            sys.stdout.write(f"\r{'接收中' if active else '等待手机'}  {rate:5.0f} 包/秒   ")
            sys.stdout.flush()
            count, stat_t = 0, now


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
