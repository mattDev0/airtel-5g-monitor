"""Generate the app icon for Android (vector + webp) and iOS (1024 png) from one geometry."""
import math, sys, os
from PIL import Image, ImageDraw

# Geometry in the 108x108 adaptive-icon viewport.
CX, CY = 54, 58
ARCS = [(10, "#FFFFFF"), (18, "#FFFFFF"), (26, "#67D4FF")]
ARC_W = 5.0
DOT_R = 4.5
PULSE = [(33, 77), (45, 77), (49, 70), (54, 85), (59, 73), (62, 77), (75, 77)]
PULSE_COLOR = "#67D4FF"
PULSE_W = 4.0
BG_TOP, BG_BOTTOM = "#11485F", "#061820"
A0, A1 = math.radians(225), math.radians(315)  # upward quarter, screen coords (y down)

def arc_pts(r):
    return (CX + r*math.cos(A0), CY + r*math.sin(A0)), (CX + r*math.cos(A1), CY + r*math.sin(A1))

def f(v): return f"{v:.2f}".rstrip("0").rstrip(".")

def vector_foreground(mono=False):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="108dp"', '    android:height="108dp"',
           '    android:viewportWidth="108"', '    android:viewportHeight="108">']
    for r, c in ARCS:
        (x0, y0), (x1, y1) = arc_pts(r)
        out += ['    <path', f'        android:pathData="M{f(x0)},{f(y0)} A{r},{r} 0 0,1 {f(x1)},{f(y1)}"',
                f'        android:strokeColor="{c}"', f'        android:strokeWidth="{f(ARC_W)}"',
                '        android:strokeLineCap="round" />']
    out += ['    <path', f'        android:fillColor="#FFFFFF"',
            f'        android:pathData="M{f(CX-DOT_R)},{CY} a{DOT_R},{DOT_R} 0 1,0 {f(2*DOT_R)},0 a{DOT_R},{DOT_R} 0 1,0 {f(-2*DOT_R)},0z" />']
    d = "M" + " L".join(f"{x},{y}" for x, y in PULSE)
    out += ['    <path', f'        android:pathData="{d}"', f'        android:strokeColor="{PULSE_COLOR}"',
            f'        android:strokeWidth="{f(PULSE_W)}"', '        android:strokeLineCap="round"',
            '        android:strokeLineJoin="round" />', '</vector>']
    return "\n".join(out) + "\n"

def vector_background():
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="108"
                android:endY="108"
                android:startColor="{BG_TOP}"
                android:endColor="{BG_BOTTOM}" />
        </aapt:attr>
    </path>
</vector>
'''

def hexrgb(h): return tuple(int(h[i:i+2], 16) for i in (1, 3, 5))

def render(size, viewport=(0, 0, 108, 108), shape="square"):
    """Render at 4x then downsample. viewport = region of the 108 canvas to show."""
    S = size * 4
    vx, vy, vw, vh = viewport
    k = S / vw
    P = lambda x, y: ((x - vx) * k, (y - vy) * k)
    # diagonal gradient background
    t, b = hexrgb(BG_TOP), hexrgb(BG_BOTTOM)
    grad = Image.new("RGB", (S, S))
    px = grad.load()
    for y in range(S):
        for x in range(0, S):
            u = (x + y) / (2 * S)
            px[x, y] = tuple(round(t[i] + (b[i]-t[i])*u) for i in range(3))
    img = grad
    d = ImageDraw.Draw(img)
    w = ARC_W * k
    for r, c in ARCS:
        R = r * k; cx, cy = P(CX, CY)
        d.arc([cx-R-w/2, cy-R-w/2, cx+R+w/2, cy+R+w/2], 225, 315, fill=c, width=round(w))
        for (x, y) in arc_pts(r):
            X, Y = P(x, y); d.ellipse([X-w/2, Y-w/2, X+w/2, Y+w/2], fill=c)
    cx, cy = P(CX, CY); R = DOT_R * k
    d.ellipse([cx-R, cy-R, cx+R, cy+R], fill="#FFFFFF")
    pw = PULSE_W * k
    pts = [P(x, y) for x, y in PULSE]
    d.line(pts, fill=PULSE_COLOR, width=round(pw), joint="curve")
    for X, Y in pts:
        d.ellipse([X-pw/2, Y-pw/2, X+pw/2, Y+pw/2], fill=PULSE_COLOR)
    img = img.resize((size, size), Image.LANCZOS)
    if shape == "circle":
        mask = Image.new("L", (size*4, size*4), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, size*4-1, size*4-1], fill=255)
        rgba = img.convert("RGBA"); rgba.putalpha(mask.resize((size, size), Image.LANCZOS)); return rgba
    return img

if __name__ == "__main__":
    repo = sys.argv[1]
    res = f"{repo}/app/src/main/res"
    open(f"{res}/drawable/ic_launcher_foreground.xml", "w").write(vector_foreground())
    open(f"{res}/drawable/ic_launcher_background.xml", "w").write(vector_background())
    # Legacy (API 24/25) bitmaps: the 72dp mask area of the 108 canvas.
    for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        vp = (18, 18, 72, 72)
        sq = render(px, vp)
        rounded = Image.new("L", (px*4, px*4), 0)
        ImageDraw.Draw(rounded).rounded_rectangle([0, 0, px*4-1, px*4-1], radius=px*4*0.2, fill=255)
        sq = sq.convert("RGBA"); sq.putalpha(rounded.resize((px, px), Image.LANCZOS))
        sq.save(f"{res}/mipmap-{name}/ic_launcher.webp", lossless=True)
        render(px, vp, "circle").save(f"{res}/mipmap-{name}/ic_launcher_round.webp", lossless=True)
    # iOS: full-bleed opaque 1024, iOS applies its own mask. Show the 80-unit centre.
    render(1024, (14, 14, 80, 80)).save(f"{repo}/iosApp/AirtelMonitor/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
