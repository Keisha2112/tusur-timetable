"""Генератор иконок приложения из docs/icon-source.png.

Исходник — квадрат с фиолетовым фоном, белой шапочкой и надписью «ТУСУР».
Надпись в иконку не попадает: на 48 dp она нечитаема, а маска Android 8+
обрезала бы её. Из изображения извлекается только шапочка (по альфа-каналу),
фон берётся сплошным цветом.

Запуск: python scripts/make_icons.py
"""

from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "docs" / "icon-source.png"
RES = ROOT / "app" / "src" / "main" / "res"

BG = (60, 55, 141)  # #3C378D, цвет фона исходника

LEGACY = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# Доля ширины холста, которую занимает шапочка.
CAP_LEGACY = 0.64
CAP_ROUND = 0.56
CAP_ADAPTIVE = 0.55

ADAPTIVE_SIZE = 432  # 108 dp при xxxhdpi


def luminance(r, g, b):
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def extract_cap(src: Image.Image) -> Image.Image:
    """Возвращает белую шапочку с прозрачным фоном, обрезанную по контуру."""
    img = src.convert("RGBA")
    w, h = img.size
    px = img.load()
    bg_lum = luminance(*BG)

    alpha = Image.new("L", (w, h))
    ap = alpha.load()
    for y in range(h):
        for x in range(w):
            r, g, b, _ = px[x, y]
            a = (luminance(r, g, b) - bg_lum) / (255.0 - bg_lum)
            a = 0.0 if a < 0.06 else min(1.0, a)
            ap[x, y] = round(a * 255)

    # Строки с содержимым: шапочка и надпись разделены промежутком.
    rows = [y for y in range(h) if any(ap[x, y] > 60 for x in range(w))]
    bands, start = [], rows[0]
    for prev, cur in zip(rows, rows[1:]):
        if cur - prev > 6:
            bands.append((start, prev))
            start = cur
    bands.append((start, rows[-1]))
    top, bottom = max(bands, key=lambda b: b[1] - b[0])

    cols = [x for x in range(w) if any(ap[x, y] > 60 for y in range(top, bottom + 1))]
    left, right = cols[0], cols[-1]

    pad = 2
    box = (max(0, left - pad), max(0, top - pad), min(w, right + 1 + pad), min(h, bottom + 1 + pad))
    mask = alpha.crop(box)
    cap = Image.new("RGBA", mask.size, (255, 255, 255, 255))
    cap.putalpha(mask)
    return cap


def compose(size: int, cap: Image.Image, ratio: float, shape: str, supersample: int = 8, dy: float = -0.02) -> Image.Image:
    s = size * supersample
    canvas = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)

    if shape == "round":
        draw.ellipse((0, 0, s - 1, s - 1), fill=BG + (255,))
    elif shape == "rounded":
        draw.rounded_rectangle((0, 0, s - 1, s - 1), radius=round(0.22 * s), fill=BG + (255,))

    width = round(ratio * s)
    height = round(width * cap.height / cap.width)
    scaled = cap.resize((width, height), Image.LANCZOS)
    # Оптический центр: кисточка утяжеляет низ, поэтому чуть поднимаем.
    top = (s - height) // 2 + round(dy * s)
    canvas.alpha_composite(scaled, ((s - width) // 2, top))
    return canvas.resize((size, size), Image.LANCZOS)


def main():
    src = Image.open(SRC)
    cap = extract_cap(src)

    for folder, size in LEGACY.items():
        out = RES / folder
        out.mkdir(parents=True, exist_ok=True)
        compose(size, cap, CAP_LEGACY, "rounded").save(out / "ic_launcher.png")
        compose(size, cap, CAP_ROUND, "round").save(out / "ic_launcher_round.png")

    (RES / "drawable-xxxhdpi").mkdir(parents=True, exist_ok=True)
    foreground = compose(ADAPTIVE_SIZE, cap, CAP_ADAPTIVE, "none")
    foreground.save(RES / "drawable-xxxhdpi" / "ic_launcher_foreground.png")

    mono = Image.new("RGBA", foreground.size, (255, 255, 255, 0))
    mono.putalpha(foreground.getchannel("A"))
    mono.save(RES / "drawable-xxxhdpi" / "ic_launcher_monochrome.png")

    print("cap size:", cap.size)
    print("done")


if __name__ == "__main__":
    main()
