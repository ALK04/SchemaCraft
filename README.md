# SchemaCraft (Minecraft 26.3, Fabric)

Mini-Litematica côté client : sélection, schémas, hologramme, affichage couche par couche, printer et collage instantané.

## Installation (pas besoin de compiler)

1. **Installer Fabric** pour Minecraft **26.3** : télécharger l'installeur sur <https://fabricmc.net/use/installer/>,
   le lancer, choisir la version `26.3`, puis *Install*. Un profil « fabric-loader-26.3 » apparaît dans le launcher.
2. **Télécharger les deux mods** :
   - `schemacraft-1.7.0.jar` : onglet **Releases** de ce dépôt (à droite sur la page GitHub) → *Assets*.
   - **Fabric API** pour 26.3 : <https://modrinth.com/mod/fabric-api> (filtrer sur la version 26.3).
3. **Les copier dans le dossier `mods`** :
   - Launcher officiel : `Win + R` → taper `%appdata%\.minecraft` → ouvrir (ou créer) le dossier `mods`.
   - CurseForge / Modrinth / Prism : clic droit sur l'instance → *Ouvrir le dossier* → `mods`.
4. **Lancer le jeu** avec le profil Fabric 26.3. En jeu, appuyer sur `,` pour ouvrir le menu.

Pour utiliser des `.litematic` : les mettre dans le dossier `schematics` (ou `schemas`) à côté du dossier `mods`.
Pas besoin d'avoir Litematica installé.

## Contrôles

| Action | Contrôle |
|---|---|
| Position 1 | Bâton : clic droit sur un bloc |
| Position 2 | Bâton : shift + clic droit |
| Menu | `,` |
| Changer le mode de l'épée (Placer / Coller) | Épée en bois : shift + molette |
| Poser le schéma chargé (son coin min sur la face cliquée) | Épée en bois, mode Placer : clic droit |
| Coller le schéma dans le monde (créatif + op) | Épée en bois, mode Coller : clic droit |
| Annuler le dernier collage | Épée en bois : `Ctrl` + `Z` (AZERTY) |
| Tout afficher / couche par couche | `=` |
| Monter / descendre d'une couche | `PgUp` / `PgDn` |
| Ressources à avoir | `)` (AZERTY) |

Touches modifiables dans Options → Commandes → SchemaCraft.

## Menu (onglets)

- **Sélection** : coordonnées des 2 coins, taille, sauvegarde sous un nom.
- **Schémas** : liste des schémas, clic = charger :
  `<instance>/schemas/*.schema`, et les `.litematic` de `schemas/` et `schematics/`
  (dossier de Litematica, sous-dossiers compris). Pour un `.litematic`, toutes les régions sont
  fusionnées ; le contenu des coffres et les entités sont ignorés.
- **Placement** : coin du schéma, déplacement X/Y/Z (shift = 10 blocs), rotation ±90°, miroir X/Z (comme Litematica), mode d'affichage, couches, **Visible / Masqué** (masqué = printer en pause), retirer.
- **Ressources** : objets nécessaires (total, à poser, dans l'inventaire), filtre « à chercher ».
- **Édition** : modifier les blocs du schéma chargé, l'hologramme se met à jour aussitôt.
  - Une seule ligne **Remplacer [ … ] par [ … ]**, avec autocomplétion comme pour les commandes
    (↑/↓ ou molette pour choisir, Tab / Entrée / clic pour compléter, Échap pour fermer).
    Un aperçu indique ce qui va changer avant de valider.
  - **Un bloc** (clic dans la liste ou `oak_log`) → un bloc (`stone`), ou un mélange
    `stone 60, andesite 30, cobblestone 10` (positions tirées au hasard). `air` ou « Retirer le bloc » l'enlève.
  - **Un mot** (`oak`) → un autre mot (`spruce`) : change bûches, planches, escaliers, feuilles... en gardant
    l'orientation (les blocs qui n'existent pas, comme `dark_spruce_log`, sont ignorés et signalés).
  - Annuler (10 niveaux), puis « Enregistrer sous » un nouveau `.schema` dans `schemas/`.
- **Paramètres** : printer (on/off, délai, blocs par pose, portée, couche affichée seulement),
  collage (l'air du schéma efface ou non, annuler le dernier collage).
  Sauvegardés dans `config/schemacraft.properties`.

## Hologramme

Bloc manquant = fantôme semi-transparent, bloc faux = cadre rouge,
bloc sans modèle simple (coffre, liquide...) = petit cadre bleu.

## Printer

Pose automatiquement les blocs manquants à portée, du bas vers le haut, avec les blocs de l'inventaire
(créés en créatif). Comme litematica-printer, il simule le placement vanilla avec différentes faces,
hauteurs de clic et rotations pour obtenir le bon état (escaliers, dalles, bûches, fours...).

## Collage (mode Coller)

Épée en bois en main, **shift + molette** pour passer en mode **Coller** (affiché en haut à gauche).
Un clic droit pose alors tout le schéma d'un coup, exactement là où est l'hologramme, avec sa rotation
et son miroir (si l'hologramme n'est pas encore posé, il est d'abord posé sur la face cliquée).

- Uniquement en **créatif avec les droits de commande** (op niveau 2, ou triche activée en solo).
- **Solo** : écrit directement dans le monde, instantané, sans mises à jour de blocs.
- **Serveur** : envoie des `/setblock` et `/fill ... strict` (64 par tick) ; la règle
  `send_command_feedback` est coupée pendant le collage puis remise à `true`.
- Par défaut l'air du schéma **n'efface pas** le terrain (option dans Paramètres).
- **Ctrl + Z** (épée en main) annule le dernier collage, jusqu'à 10 collages. Le contenu des coffres
  remplacés n'est pas restauré.

## Build

- `./build-local.sh` (ou `./build-local.sh install` pour copier dans l'instance Jules) :
  compile avec le JDK 25 de CurseForge, sans Gradle (classpath dans `.buildlib/cp.txt`).
- `./gradlew build` : build Fabric Loom standard.
