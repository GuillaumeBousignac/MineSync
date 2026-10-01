# Packaging de MineSync (Linux, Windows, macOS)

Le paquet natif est produit par **jpackage** (fourni avec le JDK), appelé par Maven via le profil `jpackage`.
Il embarque son propre environnement Java : l'utilisateur final n'a rien à installer.

**Règle importante : chaque paquet se construit sur son système.** jpackage ne sait pas produire un `.exe` depuis Linux, ni un `.dmg` depuis Windows. Le JAR autonome contient de plus les bibliothèques natives JavaFX du système qui compile.

## Organisation

```
packaging/
├── icons/
│   ├── minesync-source.png     logo d'origine (seule source à modifier)
│   ├── generate-icons.py       régénère toutes les icônes depuis la source
│   ├── linux/minesync.png      512 px      → Linux
│   ├── windows/minesync.ico    16 à 256 px → Windows
│   └── macos/minesync.icns     16 à 1024 px (Retina) → macOS
├── jpackage/
│   ├── common.args             nom, éditeur, description, classe principale
│   ├── linux.args              icône PNG
│   ├── linux-installer.args    options des paquets .deb / .rpm (facultatif)
│   ├── windows.args            icône ICO, menu Démarrer, raccourci, installation sans droits admin
│   └── macos.args              icône ICNS, identifiant du paquet
└── linux/
    ├── minesync.desktop        entrée de menu (modèle)
    └── install-local.sh        installe l'image Linux pour l'utilisateur courant
```

Le logo affiché dans l'application et utilisé comme icône de fenêtre se trouve dans
`src/main/resources/com/minecraftsync/ui/logo.png`.

Pour changer de logo : remplacer `packaging/icons/minesync-source.png` (PNG carré, fond transparent, 1024 px conseillé), puis lancer
`python3 packaging/icons/generate-icons.py` (Pillow requis : `sudo pacman -S python-pillow`).

## Commande commune

```bash
mvn clean verify -Pjpackage
```

| Phase Maven | Action |
|---|---|
| `package` | JAR autonome `target/minecraft-sync-1.0.0-all.jar` (comme d'habitude) |
| `pre-integration-test` | copie du JAR dans `target/jpackage/input` |
| `verify` | jpackage → `target/jpackage/output` |

Le système est détecté automatiquement (profils `os-linux`, `os-windows`, `os-macos`) :

| Système | Type par défaut | Icône utilisée |
|---|---|---|
| Linux | `app-image` (dossier autonome) | `icons/linux/minesync.png` |
| Windows | `exe` (installateur) | `icons/windows/minesync.ico` |
| macOS | `dmg` (image disque contenant `MineSync.app`) | `icons/macos/minesync.icns` |

Changer de type : `-Djpackage.type=…` (ex. `app-image`, `deb`, `rpm`, `msi`, `pkg`).
La version du paquet (`jpackage.app.version`, dans le `pom.xml`) doit rester purement numérique (ex. `1.0.0`).

`mvn package` seul ne lance pas jpackage : le développement courant n'est pas ralenti.

---

## 1. Linux depuis Arch

### Prérequis
```bash
sudo pacman -S --needed jdk-openjdk maven
java -version      # doit afficher 25 ou plus ; sinon : sudo archlinux-java set <version>
```
JavaFX vient de Maven : le paquet Arch `java-openjfx` n'est pas nécessaire.

### Générer l'image d'application (recommandé sur Arch)
```bash
mvn clean verify -Pjpackage
target/jpackage/output/MineSync/bin/MineSync      # essai direct
./packaging/linux/install-local.sh                # menu des applications + icône
./packaging/linux/install-local.sh --remove       # désinstallation (données et mondes conservés)
```
Pour distribuer cette image à d'autres utilisateurs Linux :
```bash
tar -C target/jpackage/output -czf MineSync-1.0.0-linux-x64.tar.gz MineSync
```

### Paquets .deb / .rpm (facultatif, pour Debian/Ubuntu ou Fedora)
```bash
sudo pacman -S --needed dpkg fakeroot    # pour .deb
sudo pacman -S --needed rpm-tools        # pour .rpm
mvn clean verify -Pjpackage -Djpackage.type=deb -Djpackage.extra.args=@packaging/jpackage/linux-installer.args
```
Avant diffusion, remplacer l'adresse `contact@example.org` dans `linux-installer.args`.
jpackage ne produit pas de paquet pacman : sur Arch, l'image et `install-local.sh` sont la voie simple. Un `PKGBUILD` pourra venir plus tard.

---

## 2. Windows (.exe)

À faire **sur un PC Windows 10/11** :

1. Installer un JDK 25 (ex. Eclipse Temurin 25, en cochant « Set JAVA_HOME ») et Maven 3.9.
2. Installer **WiX Toolset**, requis par jpackage pour `.exe` et `.msi`.
   - Le plus simple : WiX **3.14**, puis ajouter son dossier `bin` au `PATH`.
   - WiX 4 et 5 sont aussi reconnus par jpackage depuis le JDK 24.
3. Ouvrir un nouveau terminal (PowerShell) et vérifier : `java -version`, `mvn -v`, `candle -?` (WiX 3) ou `wix --version` (WiX 4/5).
4. Dans le dossier du projet :
   ```powershell
   mvn clean verify -Pjpackage
   ```
5. Résultat : `target\jpackage\output\MineSync-1.0.0.exe`.

Ce que fait l'installateur :
- installation pour l'utilisateur courant, sans droits administrateur ;
- choix du dossier d'installation ;
- entrée dans le menu Démarrer (groupe MineSync) et raccourci sur le Bureau, avec l'icône ICO.

L'identifiant `--win-upgrade-uuid` de `windows.args` ne doit **jamais changer** : c'est lui qui permet à une version suivante de remplacer proprement la précédente.

Sans signature de code, Windows SmartScreen affichera « Windows a protégé votre ordinateur » au premier lancement (*Informations complémentaires › Exécuter quand même*).

---

## 3. macOS (.app / .dmg)

À faire **sur un Mac** :

1. Installer un JDK 25 **de la même architecture que le Mac visé** (Apple Silicon : aarch64 ; Intel : x64), puis Maven : `brew install --cask temurin@25` et `brew install maven` (ou installation manuelle).
2. Dans le dossier du projet :
   ```bash
   mvn clean verify -Pjpackage                          # → target/jpackage/output/MineSync-1.0.0.dmg
   mvn clean verify -Pjpackage -Djpackage.type=app-image  # → target/jpackage/output/MineSync.app seul
   ```
3. L'icône ICNS est utilisée pour le Dock, le Finder et le `.dmg`.

À savoir :
- Un paquet construit sur Apple Silicon ne fonctionne pas sur un Mac Intel, et inversement : il faut le construire sur chaque architecture visée.
- Sans signature ni notarisation Apple (compte développeur payant), macOS bloque le premier lancement. Il faut faire clic droit sur l'application › *Ouvrir*, ou passer par *Réglages Système › Confidentialité et sécurité › Ouvrir quand même*.
- La signature pourra être ajoutée plus tard dans `macos.args` (`--mac-sign`, `--mac-signing-key-user-name`).

---

## 4. Où placer les icônes

| Fichier | Rôle | Utilisé par |
|---|---|---|
| `packaging/icons/minesync-source.png` | logo d'origine | `generate-icons.py` |
| `packaging/icons/linux/minesync.png` | icône du lanceur et de l'entrée de menu | `linux.args`, `install-local.sh` |
| `packaging/icons/windows/minesync.ico` | icône du `.exe`, du menu Démarrer et du raccourci | `windows.args` |
| `packaging/icons/macos/minesync.icns` | icône de `MineSync.app` et du `.dmg` | `macos.args` |
| `src/main/resources/com/minecraftsync/ui/logo.png` | logo de l'en-tête et icône de la fenêtre / barre des tâches | application |

Les chemins sont relatifs à la racine du projet : Maven lance jpackage depuis ce dossier.

## Points à surveiller plus tard

- **Taille** : environ 180 Mo une fois décompressé, car tout le JDK est embarqué. Elle pourra être réduite avec `--add-modules` (liste des modules réellement utilisés), après des tests sur les trois systèmes.
- **Nom** : la fenêtre s'intitule encore « Minecraft Sync » ; le paquet s'appelle « MineSync ». À harmoniser si tu le souhaites.
- **Données** : elles restent dans le dossier de données habituel (voir README, § 10) ; désinstaller l'application ne les supprime pas.
