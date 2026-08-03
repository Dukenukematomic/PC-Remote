#!/usr/bin/env python3
"""
PC Remote - desktop server.

Listens on a TCP port for the Android "PC Remote" app and turns the messages it
receives into real mouse movement, clicks, scrolling and media-key presses.

The phone finds this PC by itself: a UDP responder answers discovery probes
broadcast on the LAN, so the app can list "PCs available" instead of asking
anyone to type an IP address.

Protocol: newline-delimited JSON, one object per line.

    {"t":"hello"}                      -> {"t":"welcome","host":"...","ver":1}
    {"t":"ping"}                       -> {"t":"pong"}
    {"t":"m","dx":12.5,"dy":-3.0}      relative mouse move
    {"t":"s","dx":0,"dy":-2}           scroll (dy>0 = up)
    {"t":"c","b":"left"}               click (left|right|middle)
    {"t":"dc","b":"left"}              double click
    {"t":"down","b":"left"}            press and hold (drag start)
    {"t":"up","b":"left"}              release (drag end)
    {"t":"k","k":"play"}               media key
                                       play|next|prev|volup|voldown|mute
    {"t":"type","s":"hello"}           type a string of text
    {"t":"key","k":"enter"}            named key
                                       enter|backspace|tab|esc|delete|space
                                       up|down|left|right|home|end|pgup|pgdn

Usage:
    python remote_server.py                 # small status window
    python remote_server.py --nogui         # console only
    python remote_server.py --port 7712
    python remote_server.py --name "Living room PC"
"""

import argparse
import json
import platform
import queue
import socket
import sys
import threading
import time

DEFAULT_PORT = 7712
DISCOVERY_PORT = 7713
DISCOVERY_MAGIC = "PCREMOTE-DISCOVER-1"
PROTOCOL_VERSION = 1

# One message should never be able to hold the keyboard hostage.
MAX_TYPE_CHARS = 512


# --------------------------------------------------------------------------
# Input backends
# --------------------------------------------------------------------------

class InputBackend:
    """Common interface the network layer talks to."""

    name = "none"

    def move(self, dx, dy):
        raise NotImplementedError

    def scroll(self, dx, dy):
        raise NotImplementedError

    def press(self, button):
        raise NotImplementedError

    def release(self, button):
        raise NotImplementedError

    def click(self, button, count=1):
        for _ in range(count):
            self.press(button)
            self.release(button)

    def media(self, key):
        raise NotImplementedError

    def type_text(self, text):
        raise NotImplementedError

    def key(self, name):
        raise NotImplementedError


class PynputBackend(InputBackend):
    """Cross-platform backend. Requires `pip install pynput`."""

    name = "pynput"

    def __init__(self):
        from pynput.mouse import Button, Controller as MouseController
        from pynput.keyboard import Controller as KeyController, Key

        self._mouse = MouseController()
        self._keyboard = KeyController()
        self._buttons = {
            "left": Button.left,
            "right": Button.right,
            "middle": Button.middle,
        }
        self._media = {
            "play": Key.media_play_pause,
            "next": Key.media_next,
            "prev": Key.media_previous,
            "volup": Key.media_volume_up,
            "voldown": Key.media_volume_down,
            "mute": Key.media_volume_mute,
        }
        self._named = {
            "enter": Key.enter,
            "backspace": Key.backspace,
            "tab": Key.tab,
            "esc": Key.esc,
            "delete": Key.delete,
            "space": Key.space,
            "up": Key.up,
            "down": Key.down,
            "left": Key.left,
            "right": Key.right,
            "home": Key.home,
            "end": Key.end,
            "pgup": Key.page_up,
            "pgdn": Key.page_down,
        }

    def move(self, dx, dy):
        self._mouse.move(int(round(dx)), int(round(dy)))

    def scroll(self, dx, dy):
        self._mouse.scroll(int(round(dx)), int(round(dy)))

    def press(self, button):
        self._mouse.press(self._buttons[button])

    def release(self, button):
        self._mouse.release(self._buttons[button])

    def media(self, key):
        k = self._media.get(key)
        if k is None:
            return
        self._keyboard.press(k)
        self._keyboard.release(k)

    def type_text(self, text):
        self._keyboard.type(text)

    def key(self, name):
        k = self._named.get(name)
        if k is None:
            return
        self._keyboard.press(k)
        self._keyboard.release(k)


class WindowsBackend(InputBackend):
    """Zero-dependency Windows backend built on SendInput via ctypes."""

    name = "win32"

    # mouse event flags
    MOVE = 0x0001
    LEFTDOWN, LEFTUP = 0x0002, 0x0004
    RIGHTDOWN, RIGHTUP = 0x0008, 0x0010
    MIDDLEDOWN, MIDDLEUP = 0x0020, 0x0040
    WHEEL, HWHEEL = 0x0800, 0x1000
    WHEEL_DELTA = 120

    KEYEVENTF_EXTENDEDKEY = 0x0001
    KEYEVENTF_KEYUP = 0x0002
    KEYEVENTF_UNICODE = 0x0004

    VK = {
        "play": 0xB3,      # VK_MEDIA_PLAY_PAUSE
        "next": 0xB0,      # VK_MEDIA_NEXT_TRACK
        "prev": 0xB1,      # VK_MEDIA_PREV_TRACK
        "volup": 0xAF,     # VK_VOLUME_UP
        "voldown": 0xAE,   # VK_VOLUME_DOWN
        "mute": 0xAD,      # VK_VOLUME_MUTE
    }

    NAMED_VK = {
        "backspace": 0x08,
        "tab": 0x09,
        "enter": 0x0D,
        "esc": 0x1B,
        "space": 0x20,
        "pgup": 0x21,
        "pgdn": 0x22,
        "end": 0x23,
        "home": 0x24,
        "left": 0x25,
        "up": 0x26,
        "right": 0x27,
        "down": 0x28,
        "delete": 0x2E,
    }

    # Keys that live on the grey navigation cluster need the extended flag or
    # applications read them as their numpad twins.
    EXTENDED = {"pgup", "pgdn", "end", "home", "left", "up", "right", "down",
                "delete"}

    def __init__(self):
        import ctypes
        from ctypes import wintypes

        self._ctypes = ctypes
        ULONG_PTR = ctypes.POINTER(wintypes.ULONG)

        class MOUSEINPUT(ctypes.Structure):
            _fields_ = [("dx", wintypes.LONG),
                        ("dy", wintypes.LONG),
                        ("mouseData", wintypes.DWORD),
                        ("dwFlags", wintypes.DWORD),
                        ("time", wintypes.DWORD),
                        ("dwExtraInfo", ULONG_PTR)]

        class KEYBDINPUT(ctypes.Structure):
            _fields_ = [("wVk", wintypes.WORD),
                        ("wScan", wintypes.WORD),
                        ("dwFlags", wintypes.DWORD),
                        ("time", wintypes.DWORD),
                        ("dwExtraInfo", ULONG_PTR)]

        class _INPUTunion(ctypes.Union):
            _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT)]

        class INPUT(ctypes.Structure):
            _anonymous_ = ("u",)
            _fields_ = [("type", wintypes.DWORD), ("u", _INPUTunion)]

        self._INPUT = INPUT
        self._MOUSEINPUT = MOUSEINPUT
        self._KEYBDINPUT = KEYBDINPUT
        self._user32 = ctypes.windll.user32
        self._user32.SendInput.argtypes = (wintypes.UINT,
                                           ctypes.POINTER(INPUT),
                                           ctypes.c_int)
        self._user32.SendInput.restype = wintypes.UINT
        # Fractional pixels left over from the last move, carried forward so
        # slow finger drags still travel.
        self._rx = 0.0
        self._ry = 0.0

    def _send_mouse(self, dx=0, dy=0, data=0, flags=0):
        mi = self._MOUSEINPUT(dx, dy, data, flags, 0, None)
        inp = self._INPUT(0)  # INPUT_MOUSE
        inp.mi = mi
        self._user32.SendInput(1, self._ctypes.byref(inp),
                               self._ctypes.sizeof(inp))

    def _send_key(self, vk, up=False, extended=False):
        flags = self.KEYEVENTF_KEYUP if up else 0
        if extended:
            flags |= self.KEYEVENTF_EXTENDEDKEY
        ki = self._KEYBDINPUT(vk, 0, flags, 0, None)
        inp = self._INPUT(1)  # INPUT_KEYBOARD
        inp.ki = ki
        self._user32.SendInput(1, self._ctypes.byref(inp),
                               self._ctypes.sizeof(inp))

    def _send_unicode(self, code_unit, up=False):
        """Types a UTF-16 code unit directly, bypassing the keyboard layout."""
        flags = self.KEYEVENTF_UNICODE
        if up:
            flags |= self.KEYEVENTF_KEYUP
        ki = self._KEYBDINPUT(0, code_unit, flags, 0, None)
        inp = self._INPUT(1)
        inp.ki = ki
        self._user32.SendInput(1, self._ctypes.byref(inp),
                               self._ctypes.sizeof(inp))

    def move(self, dx, dy):
        self._rx += dx
        self._ry += dy
        ix, iy = int(self._rx), int(self._ry)
        self._rx -= ix
        self._ry -= iy
        if ix or iy:
            self._send_mouse(dx=ix, dy=iy, flags=self.MOVE)

    def scroll(self, dx, dy):
        if dy:
            self._send_mouse(data=int(round(dy)) * self.WHEEL_DELTA,
                             flags=self.WHEEL)
        if dx:
            self._send_mouse(data=int(round(dx)) * self.WHEEL_DELTA,
                             flags=self.HWHEEL)

    def press(self, button):
        flags = {"left": self.LEFTDOWN,
                 "right": self.RIGHTDOWN,
                 "middle": self.MIDDLEDOWN}.get(button)
        if flags:
            self._send_mouse(flags=flags)

    def release(self, button):
        flags = {"left": self.LEFTUP,
                 "right": self.RIGHTUP,
                 "middle": self.MIDDLEUP}.get(button)
        if flags:
            self._send_mouse(flags=flags)

    def media(self, key):
        vk = self.VK.get(key)
        if vk is None:
            return
        self._send_key(vk, up=False)
        self._send_key(vk, up=True)

    def type_text(self, text):
        # UTF-16 so characters outside the BMP go out as their surrogate pair.
        data = text.encode("utf-16-le")
        for i in range(0, len(data), 2):
            unit = data[i] | (data[i + 1] << 8)
            self._send_unicode(unit, up=False)
            self._send_unicode(unit, up=True)

    def key(self, name):
        vk = self.NAMED_VK.get(name)
        if vk is None:
            return
        extended = name in self.EXTENDED
        self._send_key(vk, up=False, extended=extended)
        self._send_key(vk, up=True, extended=extended)


def make_backend(log):
    """Prefer pynput; fall back to the built-in Windows backend."""
    try:
        backend = PynputBackend()
        log("Input backend: pynput")
        return backend
    except Exception as exc:                                  # noqa: BLE001
        if platform.system() == "Windows":
            log("pynput unavailable (%s) - using built-in Windows backend"
                % exc.__class__.__name__)
            return WindowsBackend()
        raise SystemExit(
            "No usable input backend.\n"
            "Install pynput first:  pip install pynput\n"
            "(original error: %s)" % exc
        )


# --------------------------------------------------------------------------
# Networking
# --------------------------------------------------------------------------

def local_ip_addresses():
    """Best-effort list of LAN addresses the phone could reach us on."""
    ips = []

    # The address used to reach the outside world is almost always the right one.
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("8.8.8.8", 80))
            ips.append(s.getsockname()[0])
        finally:
            s.close()
    except OSError:
        pass

    try:
        for info in socket.getaddrinfo(socket.gethostname(), None,
                                       socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass

    return ips or ["127.0.0.1"]


class DiscoveryResponder(threading.Thread):
    """Answers the phone's broadcast "who's out there?" probes.

    The phone sends DISCOVERY_MAGIC to the broadcast address on
    DISCOVERY_PORT; we reply straight back to whichever ephemeral port it
    used, so the app never has to receive a broadcast itself (which some
    Android builds throttle without a multicast lock).
    """

    def __init__(self, name, service_port, log=print):
        super().__init__(daemon=True)
        self.name_ = name
        self.service_port = service_port
        self.log = log
        self._sock = None
        self._stop = threading.Event()

    def run(self):
        try:
            self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self._sock.bind(("0.0.0.0", DISCOVERY_PORT))
        except OSError as exc:
            self.log("Discovery disabled: UDP %d unavailable (%s)"
                     % (DISCOVERY_PORT, exc))
            return

        self.log("Discovery active on UDP %d" % DISCOVERY_PORT)
        reply = json.dumps({
            "t": "pcremote",
            "name": self.name_,
            "port": self.service_port,
            "ver": PROTOCOL_VERSION,
        }).encode("utf-8")

        while not self._stop.is_set():
            try:
                data, addr = self._sock.recvfrom(512)
            except OSError:
                break
            if DISCOVERY_MAGIC.encode("utf-8") not in data:
                continue
            try:
                self._sock.sendto(reply, addr)
            except OSError:
                pass

    def stop(self):
        self._stop.set()
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass


class RemoteServer:
    def __init__(self, port=DEFAULT_PORT, log=print, name=None):
        self.port = port
        self.log = log
        self.display_name = name or socket.gethostname()
        self._discovery = None
        self.backend = make_backend(log)
        self._sock = None
        self._stop = threading.Event()
        self.clients = 0
        self.on_clients_changed = lambda n: None

    def serve_forever(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("0.0.0.0", self.port))
        self._sock.listen(4)
        self.log("Listening on 0.0.0.0:%d as \"%s\"" % (self.port,
                                                        self.display_name))
        for ip in local_ip_addresses():
            self.log("  address: %s" % ip)

        self._discovery = DiscoveryResponder(self.display_name, self.port,
                                             self.log)
        self._discovery.start()

        while not self._stop.is_set():
            try:
                conn, addr = self._sock.accept()
            except OSError:
                break
            threading.Thread(target=self._handle, args=(conn, addr),
                             daemon=True).start()

    def stop(self):
        self._stop.set()
        if self._discovery:
            self._discovery.stop()
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass

    # -- per-connection ----------------------------------------------------

    def _handle(self, conn, addr):
        self.clients += 1
        self.on_clients_changed(self.clients)
        self.log("Connected: %s:%d" % addr)
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        # Idle phones get dropped so a stale connection cannot linger forever.
        conn.settimeout(30.0)
        buf = b""
        try:
            while not self._stop.is_set():
                chunk = conn.recv(4096)
                if not chunk:
                    break
                buf += chunk
                if len(buf) > 1 << 16:          # runaway garbage, drop it
                    buf = b""
                    continue
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    reply = self._dispatch(line)
                    if reply is not None:
                        conn.sendall((json.dumps(reply) + "\n").encode())
        except (OSError, socket.timeout):
            pass
        finally:
            try:
                conn.close()
            except OSError:
                pass
            # A phone that vanishes mid-drag must not leave a button stuck down.
            for b in ("left", "right", "middle"):
                try:
                    self.backend.release(b)
                except Exception:                             # noqa: BLE001
                    pass
            self.clients -= 1
            self.on_clients_changed(self.clients)
            self.log("Disconnected: %s:%d" % addr)

    def _dispatch(self, line):
        line = line.strip()
        if not line:
            return None
        try:
            msg = json.loads(line.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return None
        if not isinstance(msg, dict):
            return None

        t = msg.get("t")
        try:
            if t == "m":
                self.backend.move(float(msg.get("dx", 0)),
                                  float(msg.get("dy", 0)))
            elif t == "s":
                self.backend.scroll(float(msg.get("dx", 0)),
                                    float(msg.get("dy", 0)))
            elif t == "c":
                self.backend.click(self._button(msg))
            elif t == "dc":
                self.backend.click(self._button(msg), count=2)
            elif t == "down":
                self.backend.press(self._button(msg))
            elif t == "up":
                self.backend.release(self._button(msg))
            elif t == "k":
                self.backend.media(str(msg.get("k", "")))
            elif t == "type":
                text = msg.get("s", "")
                if isinstance(text, str) and text:
                    self.backend.type_text(text[:MAX_TYPE_CHARS])
            elif t == "key":
                self.backend.key(str(msg.get("k", "")))
            elif t == "ping":
                return {"t": "pong"}
            elif t == "hello":
                return {"t": "welcome",
                        "host": self.display_name,
                        "ver": PROTOCOL_VERSION}
        except Exception as exc:                              # noqa: BLE001
            self.log("Input error: %s" % exc)
        return None

    @staticmethod
    def _button(msg):
        b = str(msg.get("b", "left"))
        return b if b in ("left", "right", "middle") else "left"


# --------------------------------------------------------------------------
# GUI
# --------------------------------------------------------------------------

def run_gui(port, name):
    import tkinter as tk
    from tkinter import scrolledtext

    root = tk.Tk()
    root.title("PC Remote - server")
    root.geometry("470x380")
    root.minsize(420, 320)

    tk.Label(root, text="This PC shows up in the phone app as",
             font=("Segoe UI", 10), fg="#666").pack(pady=(14, 2))
    tk.Label(root, text=name, font=("Segoe UI Semibold", 18)).pack()
    tk.Label(root, text="%s  ·  port %d" % (local_ip_addresses()[0], port),
             font=("Consolas", 10), fg="#888").pack(pady=(2, 10))

    status = tk.Label(root, text="Starting...", font=("Segoe UI", 10),
                      fg="#666")
    status.pack(pady=(0, 8))

    log_box = scrolledtext.ScrolledText(root, height=10, font=("Consolas", 9),
                                        state="disabled", wrap="word")
    log_box.pack(fill="both", expand=True, padx=10, pady=(0, 10))

    msgs = queue.Queue()

    def log(text):
        msgs.put("[%s] %s" % (time.strftime("%H:%M:%S"), text))

    def drain():
        while True:
            try:
                line = msgs.get_nowait()
            except queue.Empty:
                break
            log_box.configure(state="normal")
            log_box.insert("end", line + "\n")
            log_box.see("end")
            log_box.configure(state="disabled")
        root.after(120, drain)

    server = RemoteServer(port=port, log=log, name=name)

    def clients_changed(n):
        root.after(0, lambda: status.configure(
            text=("%d phone%s connected" % (n, "" if n == 1 else "s"))
            if n else "Waiting for a phone to connect",
            fg="#1a7f37" if n else "#666"))

    server.on_clients_changed = clients_changed
    status.configure(text="Waiting for a phone to connect")

    threading.Thread(target=server.serve_forever, daemon=True).start()

    def on_close():
        server.stop()
        root.destroy()

    root.protocol("WM_DELETE_WINDOW", on_close)
    root.after(120, drain)
    root.mainloop()


# --------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description="PC Remote server")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT,
                    help="TCP port to listen on (default %d)" % DEFAULT_PORT)
    ap.add_argument("--nogui", action="store_true",
                    help="run headless in the console")
    ap.add_argument("--name", default=socket.gethostname(),
                    help="name shown in the phone app (default: hostname)")
    args = ap.parse_args()

    if args.nogui:
        server = RemoteServer(port=args.port, name=args.name)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            server.stop()
            print("\nBye.")
        return

    try:
        run_gui(args.port, args.name)
    except ImportError:
        print("tkinter not available, falling back to --nogui")
        RemoteServer(port=args.port, name=args.name).serve_forever()


if __name__ == "__main__":
    sys.exit(main())
