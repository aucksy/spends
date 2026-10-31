"""Turn the raw store screenshots (play/screenshots, rendered by StoreScreenshotsTest) into Play listing
images: brand background, a headline and subline, and the screen inside a phone frame.

    python scripts/store/frame_screenshots.py

Writes play/store/NN-name.png at 1080 x 1920 (9:16, inside Play's 2:1 limit). Fonts are the app's own
(Manrope), so the captions match the app. The order below is the order Play shows them in.
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "play" / "screenshots"
OUT = ROOT / "play" / "store"
FONTS = ROOT / "app" / "src" / "main" / "res" / "font"

W, H = 1080, 1920
TOP, SIDE = 110, 70
TEAL_TOP, TEAL_BOTTOM = (19, 128, 116), (10, 88, 80)
SUB_COLOUR = (206, 236, 230)

# (raw file, headline, subline). Words checked against the app: nothing here is promised that is not live.
SHOTS = [
    ("04-review-and-add", "Bank SMS in. Entry ready.", "Nothing is added until you tap Add."),
    ("01-home", "Know what's left till payday", "Your balance follows your salary cycle."),
    ("02-analytics", "See where your money goes", "Every rupee, sorted by category."),
    ("03-cards", "Card bills, all in one place", "Bill date, due date and spend per card."),
    ("05-category-food", "Spot when you overspend", "This month next to your usual month."),
    ("06-recurring", "Rent and EMIs add themselves", "Set them once. Spends adds them monthly."),
    ("07-sms-capture", "Your data stays on your phone", "No account. No ads. SMS stays on your phone."),
]


def font(name, size):
    return ImageFont.truetype(str(FONTS / f"manrope_{name}.ttf"), size)


def balanced(draw, text, fnt, width):
    """One line if it fits, else the two-line split with the most even lines (no lone last word)."""
    if draw.textlength(text, font=fnt) <= width:
        return [text]
    words = text.split()
    splits = [(" ".join(words[:i]), " ".join(words[i:])) for i in range(1, len(words))]
    best = min(splits, key=lambda ab: max(draw.textlength(ab[0], font=fnt), draw.textlength(ab[1], font=fnt)))
    return list(best) if max(draw.textlength(t, font=fnt) for t in best) <= width else wrap(draw, text, fnt, width)


def wrap(draw, text, fnt, width):
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if draw.textlength(trial, font=fnt) <= width or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    return lines + [line]


def background():
    bg = Image.new("RGB", (W, H))
    px = bg.load()
    for y in range(H):
        t = y / (H - 1)
        c = tuple(round(a + (b - a) * t) for a, b in zip(TEAL_TOP, TEAL_BOTTOM))
        for x in range(W):
            px[x, y] = c
    return bg.convert("RGBA")


def phone(screen, screen_w):
    bezel, radius = 18, 64
    scale = screen_w / screen.width
    shot = screen.convert("RGB").resize((screen_w, round(screen.height * scale)), Image.LANCZOS)
    pw, ph = shot.width + 2 * bezel, shot.height + 2 * bezel
    body = Image.new("RGBA", (pw, ph), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle((0, 0, pw - 1, ph - 1), radius=radius, fill=(22, 26, 29, 255))
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, shot.width - 1, shot.height - 1), radius=radius - bezel, fill=255)
    body.paste(shot, (bezel, bezel), mask)
    return body


def frame(raw, headline, subline):
    img = background()
    d = ImageDraw.Draw(img)
    head_font, sub_font = font("extrabold", 80), font("medium", 40)
    y = TOP
    for line in balanced(d, headline, head_font, W - 2 * SIDE):
        d.text((W // 2, y), line, font=head_font, fill="white", anchor="ma")
        y += 94
    y += 16
    for line in balanced(d, subline, sub_font, W - 2 * SIDE):
        d.text((W // 2, y), line, font=sub_font, fill=SUB_COLOUR, anchor="ma")
        y += 56
    top = y + 44
    screen = Image.open(raw)
    # Biggest phone that fits between the caption and a small bottom margin.
    screen_w = min(800, round((H - top - 36 - 36) * screen.width / screen.height))
    body = phone(screen, screen_w)
    x = (W - body.width) // 2
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((x, top + 24, x + body.width, top + 24 + body.height), radius=64, fill=(0, 0, 0, 110))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(36)))
    img.alpha_composite(body, (x, top))
    return img.convert("RGB")


def main():
    OUT.mkdir(exist_ok=True)
    for old in OUT.glob("*.png"):
        old.unlink()
    for i, (name, headline, subline) in enumerate(SHOTS, 1):
        out = OUT / f"{i:02d}-{name.split('-', 1)[1]}.png"
        frame(RAW / f"{name}.png", headline, subline).save(out, optimize=True)
        print(out.relative_to(ROOT))


if __name__ == "__main__":
    main()
