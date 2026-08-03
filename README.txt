PC REMOTE
=========

Turn your phone into a trackpad and media remote for this PC, and watch
this PC's screen on the phone while you do it.


ON THE PC
---------
Double-click "Start PC Remote.bat" and leave the window open.

The first run pops up a Windows Firewall prompt. Tick "Private networks"
and click Allow. If you miss it the phone will not find the PC; to fix it,
run these two commands in an admin Command Prompt:

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


SEEING THE SCREEN
-----------------
The monitor icon at the top right puts this PC's live screen behind the
trackpad. Every gesture above still works - the trackpad just stops
painting over the picture, so the same swipe now moves a pointer you can
watch. Tap the icon again to go back to the plain trackpad.

The picture is scaled to your phone's width and sent at about 12 frames
a second. It is meant for glancing at what the pointer is doing and for
pausing something from the sofa, not for video. Every monitor is
captured, so a two-screen PC arrives as one wide picture.

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
can move this PC's mouse, and anyone who can reach 7714 can watch the
screen - which means reading whatever is on it without touching a thing.
Use it on a network you trust, close the server window when you are
done, and pass --no-screen if you only want the trackpad.
