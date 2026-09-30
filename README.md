# Minecraft Sync — V1

Application de bureau (Java 25 + JavaFX) qui synchronise des mondes Minecraft entre plusieurs PC via **Google Drive**.
Une **session** = un monde précis (ex. `.minecraft/saves/Arzmania`) lié à un dossier Google Drive (ex. `Minecraft Sync/Arzmania`).

Principe d'usage : on joue sur le PC A, on quitte le monde, Minecraft Sync envoie automatiquement les changements ; sur le PC B, l'application récupère la nouvelle version avant qu'on relance le jeu.

---

## 1. Fonctionnalités de la V1

- Ajout de sessions depuis les mondes détectés (lanceur officiel, Prism Launcher, CurseForge, Modrinth App) ou via « Parcourir… » (MultiMC portable, ATLauncher, tout autre dossier).
- Détection de la version Minecraft et du caractère moddé (lecture de `level.dat`), modifiables à la main.
- Une carte par session : état, dernière synchronisation (date + appareil), activation/désactivation, synchronisation manuelle, paramètres, ouverture du dossier, retrait (le monde n'est **jamais** supprimé).
- Synchronisation **automatique** à la fermeture du monde (délai de calme réglable, 10 s par défaut), et vérification régulière des nouveautés (2 min par défaut).
- Synchronisation **différentielle et récursive** : tout le contenu du dossier du monde, sans liste de fichiers figée (mondes moddés compris) ; seuls les fichiers modifiés sont envoyés ou reçus.
- **Conflits** détectés (monde modifié sur deux PC) : choix explicite « version locale » / « version Google Drive » / « Annuler », jamais d'écrasement silencieux.
- **Sauvegarde** complète du monde local avant tout remplacement demandé par l'utilisateur ; anciennes versions Drive envoyées dans la corbeille de Google Drive (récupérables 30 jours).
- Robustesse réseau : nouvelles tentatives, aucune version partielle publiée, reprise automatique plus tard.

## 2. Prérequis

| Élément | Version |
|---|---|
| JDK | **25** (ex. Eclipse Temurin 25) |
| Maven | 3.9 ou plus |
| Compte Google | celui qui recevra les mondes |
| Systèmes | Windows 10/11, macOS, Linux (x64 ou ARM) |

Vérification : `java -version` et `mvn -v` doivent afficher Java 25.

## 3. Configuration Google Cloud (une seule fois)

L'application utilise **votre propre** accès à l'API Google Drive. Comptez 10 minutes.

1. Ouvrez la console Google Cloud (https://console.cloud.google.com) et créez un projet (ex. « Minecraft Sync »).
2. **Activez l'API** : *API et services › Bibliothèque* › « Google Drive API » › **Activer**.
3. **Écran de consentement** : *Menu › Google Auth Platform › Présentation (ou « Branding »)* › **Commencer**.
   - Nom de l'application : Minecraft Sync ; adresse e-mail d'assistance : la vôtre.
   - Audience : **Externe** (compte Gmail personnel).
   - Coordonnées : votre adresse ; acceptez les règles ; **Créer**.
4. **Accès aux données** (facultatif) : vous pouvez ajouter la portée `.../auth/drive.file`. C'est la seule demandée par l'application : elle ne voit **que les fichiers qu'elle a créés**, jamais le reste de votre Drive.
5. **Publication — important** : *Audience* › **Publier l'application** (statut « En production »).
   En statut « Test », Google fait expirer l'autorisation au bout de **7 jours** : il faudrait se reconnecter chaque semaine. La portée `drive.file` n'étant pas « sensible », la publication ne demande pas de validation par Google ; lors de la connexion, Google affichera seulement un avertissement « application non validée » : cliquez sur *Paramètres avancés › Accéder à Minecraft Sync*.
   Si vous préférez rester en « Test », ajoutez votre adresse dans *Audience › Utilisateurs test*.
6. **Identifiant OAuth** : *Google Auth Platform › Clients › Créer un client* › type **Application de bureau** › **Créer** › **Télécharger le JSON**.
7. Renommez ce fichier `credentials.json`. **Utilisez exactement le même fichier sur tous vos PC** : avec la portée `drive.file`, un autre identifiant ne verrait pas les dossiers créés par le premier.

Au premier clic sur « Connecter Google Drive », l'application demande ce fichier et le copie dans son dossier de données (voir § 10). Ne le partagez pas publiquement.

## 4. Compilation et lancement

```bash
cd minecraft-sync
mvn test                      # tests automatisés (voir § 9)
mvn javafx:run                # lancement direct
mvn package                   # produit target/minecraft-sync-1.0.0-all.jar
java -jar target/minecraft-sync-1.0.0-all.jar
```

Le JAR `-all` contient JavaFX **pour le système qui l'a compilé** : compilez-le sur chaque système (Windows, macOS, Linux), ou utilisez `mvn javafx:run` partout.

## 5. Première utilisation

### PC A (celui qui possède le monde)
1. Lancez l'application › **Connecter Google Drive** › choisissez `credentials.json` › gardez le nom de dossier racine proposé (« Minecraft Sync ») › autorisez l'accès dans le navigateur.
2. **+ Ajouter une session** › choisissez le monde › vérifiez nom, version, type › **Créer la session**.
3. La première synchronisation envoie tout le monde (quelques minutes pour un gros monde moddé).

### PC B (récupération)
1. Connectez Google Drive avec **le même compte, le même `credentials.json` et le même nom de dossier racine**.
2. Un avis signale les sessions disponibles : **☁ Sessions disponibles sur Google Drive** › choisissez la session, le dossier `saves` de destination (bonne instance, mêmes mods) et le nom du dossier › **Ajouter à ce PC**.
3. Le monde est téléchargé, vérifié (SHA-256) puis installé.

Ensuite, laissez l'application ouverte (fenêtre réduite) pendant que vous jouez : c'est elle qui détecte la fin de partie.

## 6. Fonctionnement

### Détection de l'utilisation d'un monde
Minecraft verrouille le fichier `session.lock` du monde ouvert. Toutes les 5 s, l'application tente brièvement de prendre ce verrou : si c'est impossible, le monde est « utilisé » et rien n'est synchronisé. Cette méthode fonctionne avec tous les lanceurs et détecte aussi le **retour au menu** (monde fermé même si le jeu tourne encore). La recherche du processus Java de Minecraft ne sert qu'à l'affichage (elle est peu fiable sous Windows).

Quand le monde se ferme : état « Minecraft fermé — synchronisation imminente », puis synchronisation après le délai de calme.

### Organisation sur Google Drive
```
Minecraft Sync/
└── Arzmania/
    ├── manifest.json   ← liste des fichiers (chemin, taille, SHA-256), révision, appareil, date
    ├── lock.json       ← présent pendant qu'un PC joue (renouvelé toutes les 5 min, ignoré après 15 min)
    └── files/          ← contenu ; chaque fichier porte son chemin relatif comme nom (ex. « region/r.0.0.mca »)
```
Le dossier `files/` n'est pas destiné à être parcouru à la main : pour récupérer un monde, passez par l'application.

### Décision à chaque synchronisation
L'application compare trois états : le monde local, le manifeste distant et la **référence** (dernier état synchronisé, stocké dans SQLite).

| Local | Distant | Action |
|---|---|---|
| inchangé | inchangé | rien |
| modifié | inchangé | envoi des seuls fichiers modifiés |
| inchangé | modifié | réception des seuls fichiers modifiés |
| modifié | modifié (différemment) | **conflit** : choix de l'utilisateur |

Le SHA-256 n'est recalculé que si la taille ou la date d'un fichier a changé.

### Règles de sûreté
- **Envoi** : les nouvelles versions sont envoyées comme nouveaux fichiers ; le manifeste n'est réécrit que si **tous** les envois ont réussi. Une coupure laisse la version précédente intacte et cohérente.
- **Réception** : téléchargement dans `<monde>/.minecraftsync-tmp/`, vérification SHA-256 de chaque fichier, puis remplacement avec **retour arrière** automatique en cas d'erreur.
- **Monde introuvable** (dossier supprimé, disque débranché) : aucune suppression n'est propagée ; restauration possible via *Paramètres › Restaurer depuis Google Drive*.
- **Manifeste distant illisible** : erreur, jamais d'écrasement.
- **Conflit** : « version Google Drive » crée d'abord une **copie complète** du monde local (3 conservées par session, réglable) ; « version locale » envoie la version du PC et l'ancienne part dans la corbeille Drive.
- Monde ouvert sur deux PC en même temps : avertissement, puis conflit à la fermeture.

## 7. Test sur deux PC (10 scénarios)

Préparez un monde de test (copie d'un vrai monde). Notez sur chaque PC l'heure et l'état affiché.

| # | Scénario | Résultat attendu |
|---|---|---|
| 1 | PC A : ajouter la session du monde | Carte créée, version et type détectés, dossier créé sur Drive |
| 2 | PC A : première synchronisation | « Synchronisation active », `manifest.json` et `files/` visibles sur Drive |
| 3 | PC B : ☁ Sessions disponibles › ajouter | Monde téléchargé dans le dossier `saves` choisi |
| 4 | PC B : lancer Minecraft | Le monde apparaît dans la liste et s'ouvre normalement |
| 5 | PC B : jouer (poser des blocs), revenir au menu | « Monde utilisé » pendant la partie, puis envoi automatique ~10 s après la fermeture ; seuls quelques fichiers envoyés (message de la carte) |
| 6 | PC A : attendre 2 min (ou « Synchroniser ») | Réception automatique ; « Dernière synchronisation : … (PC-B) » |
| 7 | PC A : lancer Minecraft | Les blocs posés sur PC B sont présents |
| 8 | PC A : jouer, fermer ; vérifier le message | Synchronisation différentielle (nombre de fichiers envoyés limité) |
| 9 | PC B : récupérer, jouer, fermer ; PC A : récupérer | Aller-retour complet sans perte |
| 10 | Débrancher le réseau des deux PC, jouer sur A **et** sur B, fermer, rebrancher | Le premier PC envoie ; le second affiche **Conflit détecté** › « Résoudre le conflit » › choisir une version ; si « version Google Drive » : copie de sauvegarde présente (*Plus › Paramètres › Ouvrir les sauvegardes*) |

Contrôles supplémentaires conseillés : couper le Wi-Fi pendant un envoi (état « Synchronisation échouée », puis nouvel essai automatique après 5 min, aucun fichier perdu) ; renommer temporairement le dossier du monde (erreur, rien supprimé sur Drive).

## 8. Simulation sur une seule machine

La propriété `minecraftsync.home` change le dossier de données : deux instances se comportent comme deux PC (identités, bases et jetons distincts).

```bash
mvn package
java -Dminecraftsync.home=/tmp/pcA -jar target/minecraft-sync-1.0.0-all.jar
java -Dminecraftsync.home=/tmp/pcB -jar target/minecraft-sync-1.0.0-all.jar
```
(Sous Windows : `-Dminecraftsync.home=C:\temp\pcA`.) Dans l'instance B, importez la session dans un **autre** dossier `saves` (bouton « Parcourir… »), puis donnez un autre nom d'appareil dans ⚙ Réglages.

## 9. Tests automatisés

`mvn test` exécute 15 tests. Un stockage distant simulé (`LocalFolderStorage`) remplace Google Drive et deux « PC » (bases et mondes distincts) sont simulés :

- première synchronisation et import sur un deuxième PC (fichiers de mods et dossiers vides compris) ;
- envoi différentiel (2 fichiers modifiés → 2 envois) et nettoyage des anciennes versions ;
- aller-retour A → B → A avec ajouts et suppressions ;
- conflit détecté sans écrasement, résolu côté Drive (avec sauvegarde vérifiée) ou côté local ;
- monde ouvert (verrou `session.lock`) jamais synchronisé ;
- panne réseau pendant l'envoi : manifeste inchangé, l'autre PC ne voit aucune version partielle, reprise réussie ;
- monde supprimé : aucune suppression distante, restauration possible ;
- verrou de présence d'un autre PC respecté ;
- manifeste corrompu jamais écrasé ;
- import au-dessus d'un monde différent = conflit ;
- lecture de `level.dat` (version, vanilla/moddé, fichier illisible) ;
- scénario automatique complet : ouverture du monde → partie → fermeture → envoi.

## 10. Emplacement des données de l'application

| Système | Dossier |
|---|---|
| Windows | `%APPDATA%\MinecraftSync` |
| macOS | `~/Library/Application Support/MinecraftSync` |
| Linux | `~/.local/share/minecraft-sync` |

Contenu : `minecraft-sync.db` (sessions, références, réglages), `credentials.json`, `tokens/` (jeton Google), `backups/` (sauvegardes des mondes).
« Déconnecter » efface le jeton local ; rien n'est supprimé sur Google Drive.

## 11. Limites connues de la V1

- **L'application doit être ouverte** pour synchroniser automatiquement (pas de service système, pas d'icône de zone de notification). Au lancement suivant, les changements en attente sont synchronisés.
- **Même `credentials.json` obligatoire sur tous les PC** (conséquence de la portée `drive.file`, choisie pour ne jamais accéder au reste du Drive). Pour la même raison, un dossier créé à la main dans Drive n'est pas visible par l'application : le dossier racine est créé par elle.
- Détection par verrou : la tentative de verrouillage dure quelques microsecondes toutes les 5 s ; une collision avec l'ouverture d'un monde par Minecraft est très improbable mais pas impossible (Minecraft afficherait alors une erreur ; il suffit de relancer le monde).
- La version et les mods ne sont **pas** vérifiés entre PC : ouvrir un monde moddé avec d'autres mods reste de la responsabilité du joueur.
- Pas de fusion de mondes, pas d'historique au-delà des sauvegardes locales et de la corbeille Drive (30 jours).
- Les fichiers de plus de quelques Go sont envoyés en une fois par l'envoi « reprenable » de Google ; une coupure oblige à renvoyer ce fichier.
- Les liens symboliques contenus dans un monde sont ignorés.

## 12. Architecture

```
com.minecraftsync
├── Main                     point d'entrée (lance JavaFX)
├── ui/                      interface JavaFX (aucune logique de synchronisation)
│   ├── MinecraftSyncApp, MainWindow, SessionView
│   ├── AddSessionDialog, ImportSessionDialog, ConflictDialog
│   └── SessionSettingsDialog, SettingsView, Ui
├── sync/                    cœur, indépendant de l'interface
│   ├── CloudStorage         interface du stockage distant (prévue pour d'autres services)
│   ├── SyncEngine           décisions, envoi, réception, retour arrière
│   ├── FileComparator       analyse récursive + SHA-256
│   ├── ConflictManager      description des conflits, sauvegardes
│   ├── Manifest, RemoteLock, SyncResult, ProgressListener
│   └── SyncService          sessions, file d'exécution, événements
├── google/                  GoogleAuth (OAuth 2.0), GoogleDriveService, DriveConnection
├── minecraft/               MinecraftDetector, MinecraftManager (surveillance),
│                            WorldDetector (lanceurs), WorldInfo, LevelDatReader (NBT)
├── database/                Database (SQLite)
├── model/                   Session, SyncState, FileEntry, DeviceIdentity, RemoteSessionInfo
└── util/                    AppPaths, Hashing, MiniJson, TimeFormat, Desktops
```

Pour ajouter OneDrive ou Dropbox plus tard : écrire une classe qui implémente `CloudStorage` ; le moteur n'a pas à changer.
