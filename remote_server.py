#!/usr/bin/env python3
"""
PC Remote - desktop server.

Listens on a TCP port for the Android "PC Remote" app and turns the messages it
receives into real mouse movement, clicks, scrolling and media-key presses.

It also serves this PC's screen as a stream of JPEG frames, so the phone can
watch what the pointer is doing instead of driving it blind. The app connects
to that stream and draws the frames itself.

The phone finds this PC by itself: a UDP responder answers discovery probes
broadcast on the LAN, so the app can list "PCs available" instead of asking
anyone to type an IP address.

Protocol: newline-delimited JSON, one object per line.

    {"t":"hello"}                      -> {"t":"welcome","host":"...","ver":1,
                                                          "screen":7714}
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

Screen frames, served over HTTP on a second port (7714 by default). The app is
the only thing meant to read these:

    GET /stream?w=960&fps=12&q=60      multipart/x-mixed-replace, never ends
    GET /frame?w=960&q=60              one still frame
    GET /info                          {"w":...,"h":...,"mime":...}

Usage:
    python remote_server.py                 # small status window
    python remote_server.py --nogui         # console only
    python remote_server.py --port 7712
    python remote_server.py --name "Living room PC"
    python remote_server.py --no-screen     # input only, no screen streaming
"""

import argparse
import http.server
import io
import json
import platform
import queue
import socket
import struct
import sys
import threading
import time
import urllib.parse
import zlib

DEFAULT_PORT = 7712
DISCOVERY_PORT = 7713
DEFAULT_SCREEN_PORT = 7714
DISCOVERY_MAGIC = "PCREMOTE-DISCOVER-1"
PROTOCOL_VERSION = 1

# One message should never be able to hold the keyboard hostage.
MAX_TYPE_CHARS = 512

# Screen streaming defaults. Every one of them can be overridden per request,
# so the phone picks what its network can keep up with.
SCREEN_WIDTH = 960
SCREEN_FPS = 12
SCREEN_QUALITY = 60
SCREEN_MAX_WIDTH = 1920
SCREEN_MAX_VIEWERS = 4


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
# Screen capture
# --------------------------------------------------------------------------

def enable_dpi_awareness():
    """Stop Windows from handing us a stretched, blurry copy of the screen.

    Without this a scaled display reports 1707x960 instead of its real
    2560x1440 and every captured frame is a blurred upscale of that. Must run
    before any window exists, so main() calls it first thing.
    """
    if platform.system() != "Windows":
        return
    import ctypes
    try:                                    # per-monitor v2, Windows 10+
        ctypes.windll.user32.SetProcessDpiAwarenessContext(
            ctypes.c_void_p(-4))
        return
    except Exception:                                         # noqa: BLE001
        pass
    try:                                    # Windows 8.1
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:                                         # noqa: BLE001
        try:                                # anything older
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:                                     # noqa: BLE001
            pass


def _pillow_image():
    """The PIL Image module, or None when Pillow is not installed."""
    try:
        from PIL import Image
    except ImportError:
        return None
    return Image


def _fit(sw, sh, max_width):
    """Target size for a screen of sw x sh, never scaling it up."""
    if sw <= max_width:
        return sw, sh
    return max_width, max(1, int(round(sh * max_width / float(sw))))


def _bgra_to_rgb(buf):
    """Repack what Windows hands us into what JPEG and PNG want.

    Both slice operations run inside CPython rather than a Python loop, which
    is the difference between a millisecond and half a second per frame.
    """
    b = bytearray(buf)
    del b[3::4]                             # BGRA -> BGR
    b[0::3], b[2::3] = b[2::3], b[0::3]     # BGR  -> RGB
    return bytes(b)


def _downscale_bgra(raw, sw, sh, max_width):
    """Shrink a BGRA buffer to at most max_width. Returns (w, h, bgra)."""
    if sw <= max_width:
        return sw, sh, raw

    Image = _pillow_image()
    if Image is not None:
        dw, dh = _fit(sw, sh, max_width)
        # The channels are mislabelled on purpose: resizing treats them
        # independently, so BGRA in means BGRA out.
        img = Image.frombuffer("RGBA", (sw, sh), raw, "raw", "RGBA", 0, 1)
        return dw, dh, img.resize((dw, dh), Image.BILINEAR).tobytes()

    # No Pillow. An integer step makes every output row one C-level slice,
    # which a per-pixel Python loop could not come close to.
    step = -(-sw // max_width)
    dw, dh = sw // step, sh // step
    px = memoryview(raw).cast("B").cast("I")    # one item per pixel
    rows = [px[y * step * sw:y * step * sw + dw * step:step].tobytes()
            for y in range(dh)]
    return dw, dh, b"".join(rows)


class ScreenBackend:
    """Common interface the frame server talks to."""

    name = "none"

    def size(self):
        """Native (width, height) of the area being captured."""
        raise NotImplementedError

    def grab(self, max_width):
        """Capture one frame. Returns (width, height, packed RGB bytes)."""
        raise NotImplementedError


class GdiScreenBackend(ScreenBackend):
    """Zero-dependency Windows capture, the same deal as WindowsBackend.

    The whole virtual desktop is copied, so a second monitor shows up too, and
    GDI does the downscale itself -- the pixels read back are already the size
    being sent. The mouse pointer is painted in by hand, because BitBlt never
    includes it and a pointer you cannot see defeats the purpose.
    """

    name = "gdi"

    SRCCOPY = 0x00CC0020
    CAPTUREBLT = 0x40000000
    HALFTONE = 4
    DIB_RGB_COLORS = 0
    BI_RGB = 0
    CURSOR_SHOWING = 0x0001
    DI_NORMAL = 0x0003

    SM_XVIRTUALSCREEN = 76
    SM_YVIRTUALSCREEN = 77
    SM_CXVIRTUALSCREEN = 78
    SM_CYVIRTUALSCREEN = 79

    def __init__(self):
        import ctypes
        from ctypes import wintypes

        self._ctypes = ctypes
        user = self._user32 = ctypes.windll.user32
        gdi = self._gdi32 = ctypes.windll.gdi32

        # Every handle goes through c_void_p. Left as the default c_int they
        # would be truncated to 32 bits on a 64-bit build and nothing would
        # work.
        H = ctypes.c_void_p
        INT = ctypes.c_int
        UINT = ctypes.c_uint
        DWORD = wintypes.DWORD

        class BITMAPINFOHEADER(ctypes.Structure):
            _fields_ = [("biSize", DWORD),
                        ("biWidth", wintypes.LONG),
                        ("biHeight", wintypes.LONG),
                        ("biPlanes", wintypes.WORD),
                        ("biBitCount", wintypes.WORD),
                        ("biCompression", DWORD),
                        ("biSizeImage", DWORD),
                        ("biXPelsPerMeter", wintypes.LONG),
                        ("biYPelsPerMeter", wintypes.LONG),
                        ("biClrUsed", DWORD),
                        ("biClrImportant", DWORD)]

        class BITMAPINFO(ctypes.Structure):
            _fields_ = [("bmiHeader", BITMAPINFOHEADER),
                        ("bmiColors", DWORD * 3)]

        class CURSORINFO(ctypes.Structure):
            _fields_ = [("cbSize", DWORD),
                        ("flags", DWORD),
                        ("hCursor", H),
                        ("ptScreenPos", wintypes.POINT)]

        class ICONINFO(ctypes.Structure):
            _fields_ = [("fIcon", wintypes.BOOL),
                        ("xHotspot", DWORD),
                        ("yHotspot", DWORD),
                        ("hbmMask", H),
                        ("hbmColor", H)]

        self._BITMAPINFO = BITMAPINFO
        self._BITMAPINFOHEADER = BITMAPINFOHEADER
        self._CURSORINFO = CURSORINFO
        self._ICONINFO = ICONINFO

        user.GetSystemMetrics.argtypes = [INT]
        user.GetSystemMetrics.restype = INT
        user.GetDC.argtypes = [H]
        user.GetDC.restype = H
        user.ReleaseDC.argtypes = [H, H]
        user.GetCursorInfo.argtypes = [ctypes.POINTER(CURSORINFO)]
        user.GetIconInfo.argtypes = [H, ctypes.POINTER(ICONINFO)]
        user.DrawIconEx.argtypes = [H, INT, INT, H, INT, INT, UINT, H, UINT]

        gdi.CreateCompatibleDC.argtypes = [H]
        gdi.CreateCompatibleDC.restype = H
        gdi.CreateCompatibleBitmap.argtypes = [H, INT, INT]
        gdi.CreateCompatibleBitmap.restype = H
        gdi.SelectObject.argtypes = [H, H]
        gdi.SelectObject.restype = H
        gdi.DeleteObject.argtypes = [H]
        gdi.DeleteDC.argtypes = [H]
        gdi.BitBlt.argtypes = [H, INT, INT, INT, INT, H, INT, INT, DWORD]
        gdi.StretchBlt.argtypes = [H, INT, INT, INT, INT,
                                   H, INT, INT, INT, INT, DWORD]
        gdi.SetStretchBltMode.argtypes = [H, INT]
        gdi.SetBrushOrgEx.argtypes = [H, INT, INT, H]
        gdi.GetDIBits.argtypes = [H, H, UINT, UINT, H,
                                  ctypes.POINTER(BITMAPINFO), UINT]
        gdi.GetDIBits.restype = INT

        # Drawing surfaces and read-back buffers live between frames. Both are
        # keyed by size, so a resolution change simply builds new ones.
        self._surfaces = {}
        self._buffers = {}

        # Prove the API works now rather than on the first frame.
        self.size()

    def _surface(self, screen_dc, width, height):
        """A memory DC with a bitmap of this size selected into it."""
        key = (width, height)
        found = self._surfaces.get(key)
        if found is not None:
            return found

        if len(self._surfaces) > 4:         # resolution has been fiddled with
            self.close()

        gdi = self._gdi32
        hdc = gdi.CreateCompatibleDC(screen_dc)
        bmp = gdi.CreateCompatibleBitmap(screen_dc, width, height)
        if not hdc or not bmp:
            raise OSError("could not create a %dx%d drawing surface"
                          % (width, height))
        previous = gdi.SelectObject(hdc, bmp)
        self._surfaces[key] = (hdc, bmp, previous)
        return self._surfaces[key]

    def _buffer(self, width, height):
        """Somewhere to read pixels back into, kept so 2 MB is not zeroed 12
        times a second."""
        key = (width, height)
        found = self._buffers.get(key)
        if found is None:
            found = (self._ctypes.c_char * (width * height * 4))()
            self._buffers[key] = found
        return found

    def close(self):
        gdi = self._gdi32
        for hdc, bmp, previous in self._surfaces.values():
            gdi.SelectObject(hdc, previous)
            gdi.DeleteObject(bmp)
            gdi.DeleteDC(hdc)
        self._surfaces = {}
        self._buffers = {}

    def _virtual_screen(self):
        g = self._user32.GetSystemMetrics
        return (g(self.SM_XVIRTUALSCREEN), g(self.SM_YVIRTUALSCREEN),
                max(1, g(self.SM_CXVIRTUALSCREEN)),
                max(1, g(self.SM_CYVIRTUALSCREEN)))

    def size(self):
        _x, _y, sw, sh = self._virtual_screen()
        return sw, sh

    def grab(self, max_width):
        ctypes = self._ctypes
        gdi, user = self._gdi32, self._user32
        x, y, sw, sh = self._virtual_screen()
        dw, dh = _fit(sw, sh, max_width)

        screen_dc = user.GetDC(None)
        if not screen_dc:
            raise OSError("GetDC failed")

        try:
            full_dc, full_bmp, full_prev = self._surface(screen_dc, sw, sh)
            gdi.BitBlt(full_dc, 0, 0, sw, sh, screen_dc, x, y,
                       self.SRCCOPY | self.CAPTUREBLT)
            self._draw_cursor(full_dc, x, y)

            if (dw, dh) == (sw, sh):
                out_dc, out_bmp, out_prev = full_dc, full_bmp, full_prev
            else:
                out_dc, out_bmp, out_prev = self._surface(screen_dc, dw, dh)
                gdi.SetStretchBltMode(out_dc, self.HALFTONE)
                gdi.SetBrushOrgEx(out_dc, 0, 0, None)
                gdi.StretchBlt(out_dc, 0, 0, dw, dh,
                               full_dc, 0, 0, sw, sh, self.SRCCOPY)

            info = self._BITMAPINFO()
            header = info.bmiHeader
            header.biSize = ctypes.sizeof(self._BITMAPINFOHEADER)
            header.biWidth = dw
            header.biHeight = -dh           # negative asks for top row first
            header.biPlanes = 1
            header.biBitCount = 32
            header.biCompression = self.BI_RGB

            buf = self._buffer(dw, dh)
            # GetDIBits refuses a bitmap that is still selected into a DC, so
            # it comes out for the read and goes straight back in.
            gdi.SelectObject(out_dc, out_prev)
            try:
                ok = gdi.GetDIBits(screen_dc, out_bmp, 0, dh,
                                   ctypes.cast(buf, ctypes.c_void_p),
                                   ctypes.byref(info), self.DIB_RGB_COLORS)
            finally:
                gdi.SelectObject(out_dc, out_bmp)
            if not ok:
                raise OSError("GetDIBits failed")
            return dw, dh, _bgra_to_rgb(buf)
        finally:
            user.ReleaseDC(None, screen_dc)

    def _draw_cursor(self, hdc, origin_x, origin_y):
        ctypes = self._ctypes
        info = self._CURSORINFO()
        info.cbSize = ctypes.sizeof(self._CURSORINFO)
        if not self._user32.GetCursorInfo(ctypes.byref(info)):
            return
        if not (info.flags & self.CURSOR_SHOWING) or not info.hCursor:
            return

        icon = self._ICONINFO()
        if not self._user32.GetIconInfo(info.hCursor, ctypes.byref(icon)):
            return
        try:
            self._user32.DrawIconEx(
                hdc,
                info.ptScreenPos.x - origin_x - icon.xHotspot,
                info.ptScreenPos.y - origin_y - icon.yHotspot,
                info.hCursor, 0, 0, 0, None, self.DI_NORMAL)
        finally:
            # GetIconInfo hands over two bitmaps that are ours to free.
            for bmp in (icon.hbmMask, icon.hbmColor):
                if bmp:
                    self._gdi32.DeleteObject(bmp)


class MssScreenBackend(ScreenBackend):
    """Cross-platform capture. Requires `pip install mss`."""

    name = "mss"

    def __init__(self):
        import mss

        self._mss = mss
        # An mss instance belongs to the thread that made it, and every viewer
        # gets its own thread.
        self._local = threading.local()
        self._grabber()

    def _grabber(self):
        sct = getattr(self._local, "sct", None)
        if sct is None:
            sct = self._mss.mss()
            self._local.sct = sct
        return sct

    def _all_monitors(self):
        return self._grabber().monitors[0]      # index 0 is every screen at once

    def size(self):
        mon = self._all_monitors()
        return mon["width"], mon["height"]

    def grab(self, max_width):
        shot = self._grabber().grab(self._all_monitors())
        sw, sh = shot.size
        dw, dh, raw = _downscale_bgra(shot.raw, sw, sh, max_width)
        return dw, dh, _bgra_to_rgb(raw)


def make_screen_backend(log):
    """Prefer the built-in Windows capture, which also draws the pointer."""
    problems = []

    if platform.system() == "Windows":
        try:
            return GdiScreenBackend()
        except Exception as exc:                              # noqa: BLE001
            problems.append("built-in: %s" % exc)

    try:
        return MssScreenBackend()
    except Exception as exc:                                  # noqa: BLE001
        problems.append("mss: %s" % exc)

    log("Screen view unavailable (%s)" % "; ".join(problems))
    log("  to enable it:  pip install mss")
    return None


# --------------------------------------------------------------------------
# Frame encoding
# --------------------------------------------------------------------------

def _png_chunk(tag, data):
    body = tag + data
    return (struct.pack(">I", len(data)) + body
            + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF))


class FrameEncoder:
    name = "none"
    mime = "application/octet-stream"

    def encode(self, width, height, rgb, quality):
        raise NotImplementedError


class JpegEncoder(FrameEncoder):
    """Pillow. Frames come out several times smaller than PNG, which is the
    whole game over Wi-Fi."""

    name = "jpeg"
    mime = "image/jpeg"

    def __init__(self):
        self._Image = _pillow_image()
        if self._Image is None:
            raise ImportError("Pillow is not installed")

    def encode(self, width, height, rgb, quality):
        image = self._Image.frombytes("RGB", (width, height), rgb)
        out = io.BytesIO()
        image.save(out, "JPEG", quality=quality)
        return out.getvalue()


class PngEncoder(FrameEncoder):
    """Standard library only, for a Python with nothing installed on it.

    Screens compress well enough for this to be usable, but frames still run
    several times the size of the JPEG equivalent.
    """

    name = "png"
    mime = "image/png"

    def encode(self, width, height, rgb, quality):
        stride = width * 3
        # PNG wants a filter byte in front of every scanline; 0 means "none".
        raw = b"".join(b"\x00" + rgb[i:i + stride]
                       for i in range(0, len(rgb), stride))
        return (b"\x89PNG\r\n\x1a\n"
                + _png_chunk(b"IHDR",
                             struct.pack(">IIBBBBB", width, height,
                                         8, 2, 0, 0, 0))
                + _png_chunk(b"IDAT", zlib.compress(raw, 1))
                + _png_chunk(b"IEND", b""))


def make_encoder(log):
    try:
        return JpegEncoder()
    except ImportError:
        log("Pillow missing - falling back to PNG frames (bigger, slower)")
        log("  for smoother video:  pip install pillow")
        return PngEncoder()


class ScreenSource:
    """One capture pipeline shared by every viewer.

    GDI device contexts cannot be driven from two threads at once, so grabs
    are serialised. With at most a handful of phones watching, a lock is
    simpler than running a producer thread and handing out its latest frame.
    """

    def __init__(self, backend, encoder):
        self.backend = backend
        self.encoder = encoder
        self._lock = threading.Lock()

    def frame(self, width, quality):
        with self._lock:
            w, h, rgb = self.backend.grab(width)
            return w, h, self.encoder.encode(w, h, rgb, quality)


def make_screen_source(log):
    backend = make_screen_backend(log)
    if backend is None:
        return None
    return ScreenSource(backend, make_encoder(log))


# --------------------------------------------------------------------------
# Screen frame server
# --------------------------------------------------------------------------

FRAME_BOUNDARY = "pcremoteframe"


class ViewerCount:
    """How many phones are watching, capped so one bad client cannot pin the
    CPU with a dozen streams."""

    def __init__(self, limit):
        self.limit = limit
        self._n = 0
        self._lock = threading.Lock()
        self.on_change = lambda n: None

    @property
    def count(self):
        with self._lock:
            return self._n

    def acquire(self):
        with self._lock:
            if self._n >= self.limit:
                return False
            self._n += 1
            n = self._n
        self.on_change(n)
        return True

    def release(self):
        with self._lock:
            self._n = max(0, self._n - 1)
            n = self._n
        self.on_change(n)


class ScreenHandler(http.server.BaseHTTPRequestHandler):
    """Serves frames. The subclass built in ScreenServer fills in the rest."""

    server_version = "PCRemote"
    sys_version = ""

    source = None
    viewers = None
    stop_event = None

    def log_message(self, fmt, *args):
        pass                        # the status window is for the user, not this

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        route = parsed.path.rstrip("/") or "/"
        try:
            if route == "/stream":
                self._stream(query)
            elif route == "/frame":
                self._still(query)
            elif route == "/info":
                self._info()
            else:
                self.send_error(404, "Not found")
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError,
                OSError):
            pass                    # the phone walked off mid-frame; normal

    @staticmethod
    def _number(query, key, default, low, high):
        try:
            return max(low, min(high, int(query[key][0])))
        except (KeyError, IndexError, ValueError):
            return default

    def _stream(self, query):
        width = self._number(query, "w", SCREEN_WIDTH, 160, SCREEN_MAX_WIDTH)
        fps = self._number(query, "fps", SCREEN_FPS, 1, 30)
        quality = self._number(query, "q", SCREEN_QUALITY, 10, 95)

        if not self.viewers.acquire():
            self.send_error(503, "Too many viewers")
            return

        try:
            self.send_response(200)
            self.send_header("Content-Type",
                             "multipart/x-mixed-replace; boundary=%s"
                             % FRAME_BOUNDARY)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()

            mime = self.source.encoder.mime.encode("ascii")
            interval = 1.0 / fps
            due = time.monotonic()

            while not self.stop_event.is_set():
                _w, _h, data = self.source.frame(width, quality)
                self.wfile.write(
                    b"--" + FRAME_BOUNDARY.encode("ascii") + b"\r\n"
                    b"Content-Type: " + mime + b"\r\n"
                    b"Content-Length: " + str(len(data)).encode("ascii")
                    + b"\r\n\r\n" + data + b"\r\n")

                due += interval
                idle = due - time.monotonic()
                if idle > 0:
                    time.sleep(idle)
                else:
                    # Capture is slower than the requested rate; send as fast
                    # as it manages rather than building up a backlog.
                    due = time.monotonic()
        finally:
            self.viewers.release()

    def _still(self, query):
        width = self._number(query, "w", SCREEN_WIDTH, 160, SCREEN_MAX_WIDTH)
        quality = self._number(query, "q", SCREEN_QUALITY, 10, 95)
        _w, _h, data = self.source.frame(width, quality)
        self._send(self.source.encoder.mime, data)

    def _info(self):
        width, height = self.source.backend.size()
        self._send("application/json", json.dumps({
            "w": width,
            "h": height,
            "mime": self.source.encoder.mime,
            "capture": self.source.backend.name,
            "viewers": self.viewers.count,
        }).encode("utf-8"))

    def _send(self, mime, data):
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(data)


class ScreenServer(threading.Thread):
    """Hands the phone a never-ending stream of screen frames."""

    def __init__(self, port, source, log=print):
        super().__init__(daemon=True)
        self.port = port
        self.source = source
        self.log = log
        self.viewers = ViewerCount(SCREEN_MAX_VIEWERS)
        self._stop = threading.Event()
        self._httpd = None

    def run(self):
        handler = type("PCRemoteScreenHandler", (ScreenHandler,), {
            "source": self.source,
            "viewers": self.viewers,
            "stop_event": self._stop,
        })
        try:
            self._httpd = http.server.ThreadingHTTPServer(
                ("0.0.0.0", self.port), handler)
        except OSError as exc:
            self.log("Screen view disabled: TCP %d unavailable (%s)"
                     % (self.port, exc))
            return

        width, height = self.source.backend.size()
        self.log("Screen view on TCP %d  (%dx%d, %s, %s)"
                 % (self.port, width, height,
                    self.source.backend.name, self.source.encoder.name))
        try:
            self._httpd.serve_forever(poll_interval=0.3)
        except OSError:
            pass

    def stop(self):
        self._stop.set()
        if self._httpd:
            # shutdown() blocks until the serving loop notices, so it must not
            # run on the thread doing the serving.
            threading.Thread(target=self._httpd.shutdown, daemon=True).start()


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

    def __init__(self, name, service_port, log=print, screen_port=0):
        super().__init__(daemon=True)
        self.name_ = name
        self.service_port = service_port
        self.screen_port = screen_port
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
        # "screen" is 0 when there is nothing to watch. Older builds of the app
        # ignore the extra key, so the version number stays where it is.
        reply = json.dumps({
            "t": "pcremote",
            "name": self.name_,
            "port": self.service_port,
            "ver": PROTOCOL_VERSION,
            "screen": self.screen_port,
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
    def __init__(self, port=DEFAULT_PORT, log=print, name=None,
                 screen_port=DEFAULT_SCREEN_PORT):
        self.port = port
        self.log = log
        self.display_name = name or socket.gethostname()
        self._discovery = None
        self.backend = make_backend(log)
        self._sock = None
        self._stop = threading.Event()
        self.clients = 0
        self.on_clients_changed = lambda n: None

        # Screen capture is a bonus, not a requirement: if it cannot start,
        # the trackpad carries on working and the phone is told there is
        # nothing to watch.
        self.screen = None
        self.screen_port = 0
        if screen_port:
            source = make_screen_source(log)
            if source is not None:
                self.screen = ScreenServer(screen_port, source, log)
                self.screen_port = screen_port

    def serve_forever(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("0.0.0.0", self.port))
        self._sock.listen(4)
        self.log("Listening on 0.0.0.0:%d as \"%s\"" % (self.port,
                                                        self.display_name))
        for ip in local_ip_addresses():
            self.log("  address: %s" % ip)

        if self.screen:
            self.screen.start()

        self._discovery = DiscoveryResponder(self.display_name, self.port,
                                             self.log, self.screen_port)
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
        if self.screen:
            self.screen.stop()
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
                        "ver": PROTOCOL_VERSION,
                        "screen": self.screen_port}
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

def run_gui(port, name, screen_port):
    import tkinter as tk
    from tkinter import scrolledtext

    root = tk.Tk()
    root.title("PC Remote - server")
    root.geometry("470x400")
    root.minsize(420, 340)

    tk.Label(root, text="This PC shows up in the phone app as",
             font=("Segoe UI", 10), fg="#666").pack(pady=(14, 2))
    tk.Label(root, text=name, font=("Segoe UI Semibold", 18)).pack()
    tk.Label(root, text="%s  ·  port %d" % (local_ip_addresses()[0], port),
             font=("Consolas", 10), fg="#888").pack(pady=(2, 10))

    status = tk.Label(root, text="Starting...", font=("Segoe UI", 10),
                      fg="#666")
    status.pack(pady=(0, 2))

    watching = tk.Label(root, text="", font=("Segoe UI", 9), fg="#888")
    watching.pack(pady=(0, 8))

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

    server = RemoteServer(port=port, log=log, name=name,
                          screen_port=screen_port)

    def clients_changed(n):
        root.after(0, lambda: status.configure(
            text=("%d phone%s connected" % (n, "" if n == 1 else "s"))
            if n else "Waiting for a phone to connect",
            fg="#1a7f37" if n else "#666"))

    def viewers_changed(n):
        root.after(0, lambda: watching.configure(
            text="%d watching this screen" % n if n else "",
            fg="#1a7f37"))

    server.on_clients_changed = clients_changed
    if server.screen:
        server.screen.viewers.on_change = viewers_changed
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
    ap.add_argument("--screen-port", type=int, default=DEFAULT_SCREEN_PORT,
                    help="port the screen is streamed on (default %d)"
                         % DEFAULT_SCREEN_PORT)
    ap.add_argument("--no-screen", action="store_true",
                    help="do not stream the screen at all")
    args = ap.parse_args()

    # Has to happen before anything opens a window.
    enable_dpi_awareness()

    screen_port = 0 if args.no_screen else args.screen_port

    if args.nogui:
        server = RemoteServer(port=args.port, name=args.name,
                              screen_port=screen_port)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            server.stop()
            print("\nBye.")
        return

    try:
        run_gui(args.port, args.name, screen_port)
    except ImportError:
        print("tkinter not available, falling back to --nogui")
        RemoteServer(port=args.port, name=args.name,
                     screen_port=screen_port).serve_forever()


if __name__ == "__main__":
    sys.exit(main())
