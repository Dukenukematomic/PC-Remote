PC REMOTE
=========

Turn your phone into a trackpad, keyboard and media remote for this PC,
and watch this PC's screen on the phone while you do it.


ON THE PC
---------
Double-click "Start PC Remote.bat" and leave the window open.

The first run pops up a Windows Firewall prompt. Tick "Private networks"
and click Allow. If you miss it the phone will not find the PC; to fix it,
run these three commands in an admin Command Prompt:

  netsh advfirewall firewall add rule name="PC Remote" dir=in action=allow protocol=TCP localport=7712
  netsh advfirewall firewall add rule name="PC Remote Discovery" dir=in action=allow protocol=UDP localport=7713
  netsh advfirewall firewall add rule name="PC Remote Screen" dir=in action=allow protocol=TCP localport=7714

Without that third rule everything works except the screen view, which
sits on "Connecting to the screen..." forever.

Needs Python 3 installed. No other setup.

Optional, for a smoother and much lighter screen view:

  pip install pillow

Without it the screen is sent as PNG instead of JPEG, which works but
uses several times the bandwidth.


ON THE PHONE
------------
1. Copy PCRemote.apk to the phone and open it. Android will ask you to
   allow installing from this source - that is expected.
2. Put the phone on the SAME Wi-Fi as this PC.
3. Open "PC Remote". This PC appears under "PCs available". Tap it.

It remembers your choice and reconnects by itself next time.


USING IT
--------
  One finger slide ............ move the pointer
  One finger tap .............. left click
  Two finger tap .............. right click
  Two finger slide ............ scroll
  Double tap, hold, slide ..... click and drag

Buttons for left/right click, previous, play/pause, next, and volume.
The gear icon adjusts pointer sensitivity and scroll speed.

Three icons sit at the top right. The gear is settings; the other two
are the screen and keyboard modes below. Each is a toggle - tap it once
to turn it on, again to turn it off.


KEYBOARD MODE
-------------
The keyboard icon switches the trackpad for a text box. Anything you
type there is typed on the PC as you go, including backspace and
autocorrect changes. There are also keys for Esc, Tab, Backspace, Enter
and the four arrows.

Click into the box you want to type into on the PC first. The server
types into whatever already has focus and never steals it, so nothing
jumps to the front while you are working.

Tap the icon again (or press back once) to return to the trackpad.


SCREEN MODE
-----------
The monitor icon puts this PC's live screen behind the trackpad. Every
gesture above still works - the trackpad just stops painting over the
picture, so the same swipe now moves a pointer you can watch.

If the PC has more than one monitor, a row of tabs appears above the
picture: "All screens" plus one per display, left to right. Tap a tab to
switch to that monitor. With a single monitor no tabs are shown.

The picture is scaled to your phone's width. The app asks for 60 frames a
second and the PC sends as many as it can manage.

What it actually manages depends on the monitor, because the cost is in
reading the screen and that scales with how many pixels the monitor has -
not with how small a picture is being sent:

  1920x1080 monitor .......... roughly 40-60 fps
  2560x1440 monitor .......... roughly 25-45 fps
  3840x2160 monitor .......... roughly 15-20 fps
  "All screens" on a multi-monitor PC ... under 10 fps

Pick a single monitor from the tabs rather than "All screens" if you want
it smooth. On a four-monitor PC "All screens" is capturing more than 20
million pixels per frame and cannot keep up, quite apart from being too
small to read on a phone.

Lowering the width does not speed it up much, since the expensive part is
reading the monitor rather than sending the picture.

To run the trackpad with no screen sharing at all:

  python remote_server.py --no-screen


IF NO PCs SHOW UP
-----------------
- The server window must be open on the PC.
- Phone and PC must be on the same network. Guest Wi-Fi and networks with
  "client isolation" block devices from seeing each other.
- Check the firewall rules above.
- Or tap "Enter an IP address instead" and type the PC's IP, which the
  server window shows.


NOTE
----
There is no password. Anyone on the same network who can reach port 7712
can move this PC's mouse and type on it, and anyone who can reach 7714
can watch the screen - which means reading whatever is on it without
touching a thing. Use it on a network you trust, close the server window
when you are done, and pass --no-screen if you only want the trackpad.
