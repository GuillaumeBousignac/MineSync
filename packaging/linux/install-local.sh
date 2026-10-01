#!/usr/bin/env bash
# Installe l'image d'application Linux produite par jpackage pour l'utilisateur courant
# (sans droits administrateur) : menu des applications + icône MineSync.
#
#   mvn clean verify -Pjpackage
#   ./packaging/linux/install-local.sh            # installe
#   ./packaging/linux/install-local.sh --remove   # désinstalle (les données et mondes sont conservés)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/target/jpackage/output/MineSync"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/minesync/app"
DESKTOP="${XDG_DATA_HOME:-$HOME/.local/share}/applications/minesync.desktop"
ICON="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/512x512/apps/minesync.png"

refresh() {
    command -v update-desktop-database >/dev/null && update-desktop-database "$(dirname "$DESKTOP")" || true
    command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$(dirname "$(dirname "$(dirname "$ICON")")")" || true
}

if [[ "${1:-}" == "--remove" ]]; then
    rm -rf "$APP_DIR" "$DESKTOP" "$ICON"
    refresh
    echo "MineSync désinstallé (vos données dans ~/.local/share/minecraft-sync sont conservées)."
    exit 0
fi

[[ -x "$SRC/bin/MineSync" ]] || { echo "Image introuvable : lancez d'abord « mvn clean verify -Pjpackage »." >&2; exit 1; }

rm -rf "$APP_DIR"
mkdir -p "$(dirname "$APP_DIR")" "$(dirname "$DESKTOP")" "$(dirname "$ICON")"
cp -a "$SRC" "$APP_DIR"
install -m 644 "$ROOT/packaging/icons/linux/minesync.png" "$ICON"
sed "s#@APP_DIR@#$APP_DIR#" "$ROOT/packaging/linux/minesync.desktop" > "$DESKTOP"
chmod 644 "$DESKTOP"
refresh
echo "MineSync installé : $APP_DIR"
echo "Lancement : menu des applications, ou $APP_DIR/bin/MineSync"
