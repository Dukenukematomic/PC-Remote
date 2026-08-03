"""Draws the PC Remote icon used by the desktop shortcut.

Run once; writes pcremote.ico next to this script. Kept in the repo so the
shortcut icon can be regenerated without any binary asset in version control.
"""

import os

from PIL import Image, ImageDraw

BG = (14, 15, 20, 255)
BODY = (108, 123, 255, 255)
DARK = (14, 15, 20, 255)

SIZES = [16, 24, 32, 48, 64, 128, 256]


def draw(size):
    # Draw large, then downsample, so the small sizes stay clean.
    s = 256
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=56, fill=BG)

    # remote body
    d.rounded_rectangle([78, 34, 178, 222], radius=22, fill=BODY)

    # trackpad
    d.rounded_rectangle([92, 50, 164, 122], radius=8, fill=DARK)

    # four buttons
    for cx, cy in ((110, 148), (146, 148), (110, 190), (146, 190)):
        d.ellipse([cx - 12, cy - 12, cx + 12, cy + 12], fill=DARK)

    return img.resize((size, size), Image.LANCZOS)


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "pcremote.ico")
    frames = [draw(n) for n in SIZES]
    frames[-1].save(out, format="ICO",
                    sizes=[(n, n) for n in SIZES],
                    append_images=frames[:-1])
    print("wrote", out)


if __name__ == "__main__":
    main()
