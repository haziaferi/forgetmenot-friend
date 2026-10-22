#!/usr/bin/env python3
"""Draws the widget picker's preview images (res/drawable-*/widget_preview_*.png) at all five
densities, from the same numbers the widgets use: SmallFriendCard and LogTick in
SpotlightWidget.kt, TopThreeContent in TopThreeWidget.kt, and DEFAULT_WIDGET_THEME (Cyanotype
light, ThemeTokens.widget(ThemeTokens.DEFAULT)) in WidgetData.kt. Rerun it when the wording, sizes or default colours change.

The widgets render in the device's Roboto, which Windows does not ship; pass the folder that
holds Roboto-Regular.ttf and Roboto-Bold.ttf (adb pull /system/fonts/Roboto-*.ttf).

Newer Android (API 36 at least) ships only a variable Roboto-Regular.ttf, with no Bold file.
Make the two static files from it with fontTools (pip install fonttools):

    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer
    for wght, name in [(400, 'Regular'), (700, 'Bold')]:
        f = TTFont('RobotoVar.ttf')  # the pulled variable font, renamed
        loc = {a.axisTag: a.defaultValue for a in f['fvar'].axes}
        loc['wght'] = wght
        instancer.instantiateVariableFont(f, loc, inplace=True)
        f.save(f'Roboto-{name}.ttf')

    python tools/widget-previews.py FONT_DIR
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'android' / 'app' / 'src' / 'main' / 'res'
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
SS = 4  # drawn at 4x and reduced, for smooth corners and strokes

# DEFAULT_WIDGET_THEME: the card is tinted to --bg.
BG, TEXT, MUTED, ACCENT = '#E9F1F7', '#142F44', '#526A7C', '#0A5188'
RADIUS = 16
LINE = 1.172  # Roboto's ascent + descent, the height Android gives one line of text

fonts = Path(sys.argv[1]) if len(sys.argv) > 1 else None
if not fonts or not (fonts / 'Roboto-Regular.ttf').exists():
    sys.exit(__doc__)


def font(bold, sp, k):
    return ImageFont.truetype(str(fonts / ('Roboto-Bold.ttf' if bold else 'Roboto-Regular.ttf')), round(sp * k))


def card(w, h, k):
    im = Image.new('RGBA', (round(w * k), round(h * k)), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle([0, 0, im.width - 1, im.height - 1], radius=RADIUS * k, fill=BG)
    return im


def text(d, x, top, s, sp, bold, color, k, anchor='la'):
    # top is the line's top in dp; Android draws the baseline one ascent below it.
    f = font(bold, sp, k)
    d.text((x * k, (top + sp * 0.927) * k), s, font=f, fill=color, anchor=anchor.replace('a', 's'))


def tick(d, cx, cy, k):
    # The ✓ of LogTick (20 sp, accent), drawn as a stroke: Roboto has no glyph for it.
    pts = [(cx - 6.5, cy + 0.5), (cx - 2, cy + 5.5), (cx + 7, cy - 6)]
    d.line([(x * k, y * k) for x, y in pts], fill=ACCENT, width=round(2.6 * k), joint='curve')


def small(label, name, k):
    # SmallFriendCard, REGULAR: 10 dp padding, label (12 sp) over name (16 sp bold) beside the tick.
    w, h = 180, 72
    im = card(w, h, k)
    d = ImageDraw.Draw(im)
    block = 12 * LINE + 16 * LINE
    top = (h - block) / 2
    text(d, 10, top, label, 12, False, TEXT, k)
    text(d, 10, top + 12 * LINE, name, 16, True, ACCENT, k)
    tick(d, w - 10 - 24, top + 12 * LINE + 16 * LINE / 2, k)
    return im


def top_three(k):
    # TopThreeContent, REGULAR: 12 dp padding, a 12 sp bold header, rows 6 dp apart, codes on the right.
    w, h = 180, 110
    im = card(w, h, k)
    d = ImageDraw.Draw(im)
    text(d, 12, 12, 'Maybe say hi', 12, True, MUTED, k)
    y = 12 + 12 * LINE
    for name, code in (('Priya', '1m'), ('Sam', '22d'), ('Alex', '9d')):
        y += 6
        text(d, 12, y, name, 13, True, TEXT, k)
        text(d, w - 12, y + 0.5, code, 12, False, MUTED, k, anchor='ra')
        y += 13 * LINE
    return im


def save(im, name, density, scale):
    out = im.resize((round(im.width / SS), round(im.height / SS)), Image.LANCZOS)
    path = RES / ('drawable-' + density) / ('widget_preview_' + name + '.png')
    out.save(path, optimize=True)
    return path, out.size


for density, scale in DENSITIES.items():
    k = scale * SS
    for name, im in (('spotlight', small('Maybe say hi to', 'Priya', k)),
                     ('random', small('Thinking of', 'Sam', k)),
                     ('topthree', top_three(k))):
        path, size = save(im, name, density, scale)
        print(path.relative_to(ROOT), size)
