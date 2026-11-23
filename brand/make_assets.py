"""Writes the Chlorine logo files from logo_gen.py: brand/logo.svg (wordmark), brand/logo.json
(path data for the pages' inline <symbol>), favicon.svg ("Cl" mark on a dark tile),
apple-touch-icon.png and og.png at the site root. Adapted from mouse33/brand/make_assets.py.
Run: python -I brand/make_assets.py"""
import os, sys, json
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw, ImageFont
from shapely.geometry import MultiPolygon
from logo_gen import build, to_path

HERE = os.path.dirname(os.path.abspath(__file__)); SITE = os.path.join(HERE, "..")
ACCENT, ACCENT_L, INK, PAPER = "#7ab8ff", "#1766D1", "#111111", "#FFFFFF"
word, (ww, wh) = build()
mark, (mw, mh) = build(mark_only=True)
D_WORD, D_MARK = to_path(word), to_path(mark)
pad = 0.6

def svg_word(fill=ACCENT):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{-pad} {-pad} {ww+2*pad:.2f} {wh+2*pad:.2f}">'
            f'<title>Chlorine</title><path fill="{fill}" d="{D_WORD}"/></svg>\n')

def svg_icon():
    s = 21 / max(mw, mh); ox, oy = (32 - mw*s)/2, (32 - mh*s)/2
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="{INK}"/>'
            f'<path fill="{ACCENT}" transform="translate({ox:.2f} {oy:.2f}) scale({s:.4f})" d="{D_MARK}"/></svg>\n')

def raster(g, scale, offset, img, color, hole):
    d = ImageDraw.Draw(img)
    for p in (g.geoms if isinstance(g, MultiPolygon) else [g]):
        d.polygon([(offset[0] + x*scale, offset[1] + y*scale) for x, y in p.exterior.coords], fill=color)
        for ring in p.interiors:
            d.polygon([(offset[0] + x*scale, offset[1] + y*scale) for x, y in ring.coords], fill=hole)

def touch_icon(n=180, ss=4):
    N = n*ss; img = Image.new("RGB", (N, N), INK)
    s = N*0.66 / max(mw, mh)
    raster(mark, s, ((N - mw*s)/2, (N - mh*s)/2), img, ACCENT, INK)
    return img.resize((n, n), Image.LANCZOS)

def font(names, size):
    for n in names:
        for d in ("C:/Windows/Fonts", "/usr/share/fonts/truetype/dejavu", "/Library/Fonts"):
            p = os.path.join(d, n)
            if os.path.exists(p): return ImageFont.truetype(p, size)
    return ImageFont.load_default()

def og(ss=2):
    W, H = 1200*ss, 630*ss; img = Image.new("RGB", (W, H), INK)
    s = 900*ss / ww
    raster(word, s, ((W - ww*s)/2 - 20*ss, 200*ss), img, ACCENT, INK)
    d = ImageDraw.Draw(img)
    mono = font(["consola.ttf", "DejaVuSansMono.ttf"], 26*ss)
    d.text((W/2, 470*ss), "FABRIC PERFORMANCE MOD · MINECRAFT 26.2 AND 26.3", font=mono, fill="#a19d90", anchor="mm")
    return img.resize((1200, 630), Image.LANCZOS)

if __name__ == "__main__":
    open(os.path.join(HERE, "logo.svg"), "w").write(svg_word())
    open(os.path.join(HERE, "logo-light.svg"), "w").write(svg_word(ACCENT_L))
    open(os.path.join(SITE, "favicon.svg"), "w").write(svg_icon())
    touch_icon().save(os.path.join(SITE, "apple-touch-icon.png"))
    og().save(os.path.join(SITE, "og.png"), optimize=True)
    json.dump({"word": D_WORD, "w": round(ww+2*pad, 2), "h": round(wh+2*pad, 2), "pad": pad, "mark": D_MARK},
              open(os.path.join(HERE, "logo.json"), "w"))
    parts = len(getattr(word, "geoms", [word]))
    print("word", round(ww, 2), round(wh, 2), len(D_WORD), "chars,", parts, "parts (letters + the i's dot); mark",
          round(mw, 2), round(mh, 2), len(getattr(mark, "geoms", [mark])), "part")
