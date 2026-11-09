# SchemaCraft (Minecraft 26.3, Fabric)

Mini-Litematica côté client : sélection, schémas, hologramme, affichage couche par couche et printer.

## Contrôles

| Action | Contrôle |
|---|---|
| Position 1 | Bâton : clic droit sur un bloc |
| Position 2 | Bâton : shift + clic droit |
| Menu | `,` |
| Poser le schéma chargé (son coin min sur la face cliquée) | Épée en bois : clic droit |
| Tout afficher / couche par couche | `=` |
| Monter / descendre d'une couche | `PgUp` / `PgDn` |

Touches modifiables dans Options → Commandes → SchemaCraft.

## Menu (onglets)

- **Sélection** : coordonnées des 2 coins, taille, sauvegarde sous un nom.
- **Schémas** : liste des schémas (`<instance>/schemas/*.schema`), clic = charger.
- **Placement** : coin du schéma, déplacement X/Y/Z (shift = 10 blocs), mode d'affichage, couches, retirer.
- **Paramètres** : printer (on/off, délai, blocs par pose, portée, couche affichée seulement).
  Sauvegardés dans `config/schemacraft.properties`.

## Hologramme

Bloc manquant = fantôme semi-transparent, bloc faux = cadre rouge,
bloc sans modèle simple (coffre, liquide...) = petit cadre bleu.

## Printer

Pose automatiquement les blocs manquants à portée, du bas vers le haut, avec les blocs de l'inventaire
(créés en créatif). Comme litematica-printer, il simule le placement vanilla avec différentes faces,
hauteurs de clic et rotations pour obtenir le bon état (escaliers, dalles, bûches, fours...).

## Build

- `./build-local.sh` (ou `./build-local.sh install` pour copier dans l'instance Jules) :
  compile avec le JDK 25 de CurseForge, sans Gradle (classpath dans `.buildlib/cp.txt`).
- `./gradlew build` : build Fabric Loom standard.
