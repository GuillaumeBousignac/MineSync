#!/usr/bin/env python3
"""
Génère les icônes natives de MineSync à partir du logo source.

    python3 packaging/icons/generate-icons.py

Prérequis : Pillow (Arch : sudo pacman -S python-pillow ; ailleurs : pip install pillow).
Source    : packaging/icons/minesync-source.png (fond transparent, carré)
Produit   :
    packaging/icons/linux/minesync.png      512 px        (jpackage, Linux)
    packaging/icons/windows/minesync.ico    16 à 256 px   (jpackage, Windows)
    packaging/icons/macos/minesync.icns     16 à 1024 px  (jpackage, macOS)
    src/main/resources/com/minecraftsync/ui/logo.png   256 px (en-tête et icône de fenêtre)
"""
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SOURCE = HERE / "minesync-source.png"


def square(img: Image.Image, margin: float) -> Image.Image:
    """Recadre sur la partie visible puis centre dans un carré avec une marge (fraction du côté)."""
    alpha = img.getchannel("A").point(lambda v: 255 if v > 10 else 0)
    img = img.crop(alpha.getbbox())
    side = round(max(img.size) / (1 - 2 * margin))
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    return canvas


def main() -> None:
    src = Image.open(SOURCE).convert("RGBA")

    # Linux et Windows : carré presque bord à bord (convention de ces systèmes)
    tight = square(src, 0.02)
    # macOS : le carré arrondi occupe ~80 % de l'icône (grille d'icônes Apple)
    mac = square(src, 0.10)

    (HERE / "linux").mkdir(exist_ok=True)
    (HERE / "windows").mkdir(exist_ok=True)
    (HERE / "macos").mkdir(exist_ok=True)

    tight.resize((512, 512), Image.LANCZOS).save(HERE / "linux" / "minesync.png", optimize=True)

    tight.resize((256, 256), Image.LANCZOS).save(
        HERE / "windows" / "minesync.ico",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

    mac.resize((1024, 1024), Image.LANCZOS).save(HERE / "macos" / "minesync.icns")

    tight.resize((256, 256), Image.LANCZOS).save(
        ROOT / "src" / "main" / "resources" / "com" / "minecraftsync" / "ui" / "logo.png", optimize=True)

    print("Icônes générées dans", HERE)


if __name__ == "__main__":
    main()
