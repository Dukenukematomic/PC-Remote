# Source

Everything needed to rebuild the app and run the server from source. The
prebuilt `PCRemote.apk` in the folder above comes from exactly this tree.

```
server/     the desktop server (input, discovery, screen capture)
android/    the Android app, a plain Gradle project with no dependencies
```

## Server

```bash
python server/remote_server.py
```

Runs on a bare Python 3 install. Optional extras:

| Package | Buys you |
| --- | --- |
| `pillow` | JPEG screen frames instead of PNG — several times less bandwidth |
| `pynput` | Input on macOS and Linux (Windows has a built-in ctypes backend) |
| `mss` | Screen capture on macOS and Linux (Windows uses built-in GDI) |

## Android app

Open `android/` in Android Studio and press Run, or build from the command
line with a JDK 17 and the Android SDK (platform 34, build-tools 34.0.0):

```bash
gradle -p android assembleRelease
```

The APK lands in `android/app/build/outputs/apk/release/`. It is signed with
the standard Android debug key — fine for your own devices, not for
publishing.

No AndroidX or third-party libraries are used, so the build pulls only the
Android Gradle plugin itself.

## How the two halves talk

| Port | Protocol | Carries |
| --- | --- | --- |
| 7712 | TCP, newline-delimited JSON | pointer, clicks, scroll, media keys, typing |
| 7713 | UDP | discovery: the app broadcasts a probe, PCs reply with name and ports |
| 7714 | HTTP | screen frames as `multipart/x-mixed-replace` JPEG |

The full message list is in the docstring at the top of
[`server/remote_server.py`](server/remote_server.py).

Screen endpoints:

```
GET /stream?w=960&fps=12&q=60&mon=0    never-ending frame stream
GET /frame?w=960&q=60&mon=0            one still frame
GET /info                              size, encoder and viewer count
GET /monitors                          the displays that can be watched
```

`mon=0` is every screen at once; `1, 2, …` are individual displays, left to
right, which is what the app's monitor tabs page through.
