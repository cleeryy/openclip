# Slice 2 — Couleur PRO (SPEC, sans code)

> SPEC UNIQUEMENT — aucune modification app/engine. Vocabulaire maison,
> aucun asset ni capture d'outil tiers. Clean-room : jamais de strings,
> icônes, noms de palettes ni layouts repris de Clip Studio Paint / Celsys
> (cf. `docs/csp-ux-research.md` § clean-room). JNI gelé (`ARCHITECTURE.md`) :
> tout ce qui suit est **app-side uniquement** (`ColorPicker.kt`,
> `MainActivity.kt`, `ToolPalette.kt`, ressources `R.string`, DataStore /
> fichier JSON maison). Composants 100 % originaux.
> Conventions : FR/EN via `R.string` uniquement, pas de littéraux FR
> en code (leçon M5 : apostrophes FR en dur cassent le build).

État vérifié (point de départ) : Slice 0 committé (MenuBar, `DocRegistry`
single-doc, `CollapsibleDrawer`, `slice0-shell.md`) + Slice 1 (matrice de vue
+ gestes dans `EngineSurface`, `slice1-gestures.md`).
Actuel couleur : `ColorPicker.kt` (~166 l.) = pastille + hex + 10 swatches
fixes (`QUICK_SWATCHES`) + 3 sliders RGB, pousse via
`onColorChanged → inkColor (MainActivity l.115)` → `ToolPalette`
(`initialColor` → `inkColor` local + `LaunchedEffect` → `pushBrush()` →
`surface.setBrush(json)`). Référence workflow : `docs/csp-ux-research.md`
§R3 (roue+triangle, sliders RGB/HSV, sets perso, récentes, pipette ;
jamais un seul picker). Spec shell : `docs/slice0-shell.md` §E (dock Studio).

## 1. Principe — quatre onglets + pipette, une seule source de vérité

Le panneau couleur du dock Studio devient un conteneur à **4 onglets**
(maison, noms `R.string` §6) + un **bouton pipette** persistant :

```
┌─────────────────────────────────┐
│ [Roue] [Curseurs] [Sets] [Récentes] │  chips 48dp min, scroll horizontal
├─────────────────────────────────┤
│  contenu onglet actif           │
├─────────────────────────────────┤
│ [pastille actuelle] [#RRGGBB] [💧 pipette : tap court / appui long] │
└─────────────────────────────────┘
```

- Onglets : `Roue HSV` (disque teinte/saturation + barre valeur, triangle
  maison — pas de copie visuelle tiers) / `Curseurs` (HSV + RGB + hex,
  remplace et étend l'actuel) / `Sets` (palettes sauvegardées, JSON maison
  persisté) / `Récentes` (historique session + persistant léger).
- Barre basse **toujours visible** quel que soit l'onglet : pastille
  actuelle (grand témoin), champ hex éditable, bouton pipette.
  Le bouton pipette ne vit PAS dans un onglet — il est accessible en 1 tap
  depuis n'importe quel onglet (workflow ≤ 2 taps, R2).
- **App-side uniquement** : aucun JNI nouveau. La pipette échantillonne le
  **bitmap composite affiché** (voir §5), pas le moteur. La roue, les
  sliders, les sets et les récentes ne produisent que des strings
  `#RRGGBB` — le moteur ne voit que `setBrush(json)` inchangé (M1/M2).

## 2. Chaîne de propagation simplifiée (hoister `BrushState`)

Problème actuel : la couleur vit à **trois endroits** (`MainActivity.inkColor`
→ `ToolPalette.inkColor` local + `LaunchedEffect` → `ColorPicker` états
RGB locaux). Ça marche mais chaque nouvel onglet (roue/sets/récentes)
devrait dupliquer la synchro.

Décision : **hoister un `BrushColor` unique dans `MainActivity`**
(ou un `ColorStore` `remember` équivalent) :

```
ColorStore (MainActivity, remember)
  inkHex: MutableState<String>   // "#RRGGBB", source de vérité
  |
  +---> ColorPanel(inkHex, onPick)     // nouveau conteneur 4 onglets (ex-ColorPicker)
  |       Roue / Curseurs / Sets / Récentes : tous appellent onPick(hex)
  +---> ToolPalette(inkHex)            // reçoit inkHex en param, pousse setBrush
  |       LaunchedEffect(inkHex) -> pushBrush() (existant, inchangé)
  +---> pipette -> onPick(sampledHex)  // même chemin, §5
  +---> stroke fini -> pushRecent(inkHex) // Récentes, §4
```

Règles :

1. `ColorPanel` est **contrôlé** : il reçoit `inkHex: String` et n'a aucun
   état couleur persistant propre — chaque slider/roue écrit via
   `onPick(hex)` et relit `inkHex` (single source of truth). Les états
   locaux transitoires (doigt sur la roue) se rabattent sur `inkHex` au
   relâcher.
2. `ToolPalette.pushBrush()` inchangé (construit le JSON avec `inkColor`) ;
   la couleur n'est **jamais poussée deux fois** (pas de `setBrush` depuis
   `ColorPanel` — seul `ToolPalette` parle au moteur).
3. Normalisation : toute entrée (roue, sliders, hex tapé, pipette, set)
   passe par `normalizeHex()` (uppercase `#RRGGBB`, clamp, fallback
   `#141414` sur invalide — même garde que `parse()` actuel).
4. `Récentes` s'alimente **au stroke terminé** (`onStrokeChanged` existant
   dans `ToolPalette`, ou callback `MainActivity`), pas à chaque
   `onValueChange` du slider (évite 60 entrées par drag) — voir §4.
5. Undo/redo inchangés : la couleur n'entre pas dans la stack M7
   (stacks par trame = pixels, pas paramètres pinceau).

Hors portée : refonte `BrushState` (taille/opacité/stabilisation restent
dans `ToolPalette`) ; la Slice 2 ne hoiste que la **couleur**.

## 3. Contenu des onglets

### 3.1 Roue HSV (disque + valeur)

- Disque teinte/saturation (rendu Canvas maison : balayage conique
  approximé en N segments + dégradé radial blanc→transparent dessiné
  par-dessus — aucun asset) + curseur circulaire de sélection + **barre
  valeur (V) verticale ou horizontale** à côté/en dessous.
- Gestes : drag dans le disque = H+S, drag sur la barre = V ; au relâcher,
  `onPick(hex)`. Pendant le drag, la pastille basse suit en direct
  (feedback immédiat, moteur poussé au relâcher + throttle ~100 ms si
  suivi continu — même pattern que sliders actuels).
- Taille : disque ≥ 180dp de diamètre sur tablette (docked 290dp −
  padding), ≥ 160dp téléphone ; poignée de sélection ≥ 24dp visuel,
  **zone tactile ≥ 48dp** (le disque entier est tactile, le critère
  porte sur la barre V et les onglets).
- Accessibilité : le disque expose `contentDescription` (teinte % +
  saturation %) ; alternative clavier = onglet Curseurs (sliders natifs).

### 3.2 Curseurs (HSV + RGB + hex)

- Évolution directe de l'actuel : **6 sliders** (T, S, V + R, V, B — libellés
  `R.string`, valeurs numériques) + **champ hex éditable**
  (`#RRGGBB`, clavier hex, validation au Entrée + fallback silencieux).
- L'actuel (`R/G/B` + hex lecture seule + 10 swatches fixes) devient :
  sliders HSV en premier (choix pro : T/S/V d'abord), RGB ensuite,
  hex éditable en dernier. Les 10 `QUICK_SWATCHES` **déménagent** dans
  l'onglet Sets comme set intégré « Base » (voir §3.3) — pas de doublon.
- Chaque slider : label + valeur (existant `red %1$d` conservé, + 3 clés
  HSV §6), `height ≥ 48dp` zone tactile (le `Slider` Material fait 24dp
  visuel — envelopper dans `Row` avec `clickable`/`padding` pour atteindre
  48dp, sans changer le visuel).
- Conversion HSV↔RGB : `androidx.compose.ui.graphics.Color`
  (`Color.hsv()` / `copy(alpha=1f)`), pure app-side, aucune dépendance.

### 3.3 Sets sauvegardés (JSON maison persisté)

- Liste de sets nommés (maison, ex. « Base », « Peau », utilisateur+) ;
  chaque set = grille de pastilles + nom + actions (renommer, supprimer,
  ajouter la couleur actuelle `+`).
- Set intégré **« Base »** : les 10 hex actuels, non supprimable
  (réinitialisable). Nouveaux sets : créés vides via bouton `+`
  (nom auto `Set N`, renommable), remplis par `+ couleur actuelle`.
- **Format maison** (jamais de format tiers) — fichier
  `filesDir/color-sets.json`, exemple :
  ```json
  {"version":1,"sets":[{"name":"Base","colors":["#141414",…]},
  {"name":"Peau","colors":["#F5D6B8","#E8A87C"]}],"active":"Base"}
  ```
- Persistance : écriture **debouncée** (500 ms après modif) + au
  `onPause` ; lecture au démarrage (fallback « Base » si absent/corrompu —
  jamais de crash sur JSON invalide). Pas de DataStore/proto : un fichier
  JSON + `kotlinx.serialization` ou parse manuel (dépendance déjà dispo ?
  sinon parse manuel ~30 lignes — décision d'implémentation, pas de
  nouvelle lib sans revue).
- Tap pastille = `onPick(hex)` (+ pousse en Récentes, §4).
  **Appui long pastille = active la pipette-recolor ? Non** — appui long
  sur pastille = menu (remplacer par couleur actuelle / supprimer).
  La pipette a son bouton dédié (§5) ; pas de geste caché sur les sets.
- Capacité : ≤ 12 sets, ≤ 48 couleurs/set (au-delà : refus silencieux +
  statut éphémère existant). Suppression set = confirmation inline
  (pas de dialogue système).

### 3.4 Récentes

- Grille horizontale (1 rangée, scroll) des **12 dernières couleurs
  effectivement dessinées** (ordre anti-chronologique, dédupliquées).
- Alimentation : **au stroke terminé uniquement** (§2.4) — un drag de
  slider qui n'a jamais touché le canvas ne pollue pas l'historique.
- Persistance légère : `color-recent.json` (même dossier, même debounce) —
  survit au restart (test §7.2). Bouton « effacer » (corbeille maison,
  confirmation inline).
- Tap = `onPick(hex)`. Vide initial = pastille fantôme + hint
  (`R.string.color_recent_empty`).

## 4. Pipette — comportement exact

Contrainte JNI : **aucun nouveau JNI**. La pipette lit le pixel du
**bitmap composite affiché** (celui que `EngineSurface` dessine :
dernier `pullFrameFull` / frame du `renderLoop`), converti écran→moteur
via le mapping inverse Slice 1 (donc correcte même zoomé/pivoté).

| Geste | Effet |
|---|---|
| Tap bouton pipette | **arme** la pipette (mode prise : le prochain tap canvas échantillonne au lieu de dessiner ; curseur pastille + statut `pipette_armed`) ; re-tap bouton = désarme |
| Tap canvas (armée) | **échantillonne** : pixel moteur `(x,y)` → `onPick(#RRGGBB)` → désarme auto → statut `pipette_picked` + pastille mise à jour. Hors papier (fond damier) = désarme sans changer la couleur + statut `pipette_missed` |
| **Appui long bouton pipette (≥ 400 ms)** | **échantillonnage immédiat sans armer** : si un doigt/stylet est déjà sur le canvas ? Non — variante simple : appui long = arme en mode **loupe continue** : tant que le bouton reste (ou le mode reste armé), chaque déplacement sur le canvas met à jour la pastille en direct ; relâcher fige + `onPick` final. (Si trop complexe à câbler : fallback = même effet que tap, documenté dans l'implémentation.) |
| Échap pendant navigation (Slice 1) | la pipette suit la **matrice de vue** (mapping inverse) ; loupe/overlay 3D M9 (mannequin) **exclus** du sample (composite moteur seul, comme `pullFrameFull`) |
| Stylet vs doigt | les deux échantillonnent (pas de stylus-first ici : en mode pipette armée, même le stylet échantillonne — 1 seul tap, pas de dessin parasite) |

Feedback **loupe** : pendant le mode armé / drag-loupe, pastille agrandie
flottante près du doigt (offset 64dp haut-droite, jamais sous le doigt)
avec anneau de la couleur samplée + hex en direct ; disparaît au désarmement.
Overlay **composite-only** (même règle que loupe Slice 1 : jamais dans les
pixels moteur, `c.rotate` non appliqué au sample — le sample précède la
rotation d'affichage).

Cas limites : canvas vide (tout blanc) = sample `#FFFFFF` valide ;
calque masqué / invisible = composite seul (ce que l'œil voit) ;
multitouch pendant pipette = 1er pointeur seul compte, les autres ignorés ;
quitter le canvas sans relâcher (drag-loupe) = fige la dernière valide.

## 5. Layouts — tablette 1920×1200 / téléphone 1080×2400

- **Tablette (dock 290dp)** : `ColorPanel` dans l'onglet Studio existant
  (sous `LayerPanel` ou au-dessus selon Slice 0-E — décision
  d'implémentation : couleur **au-dessus** des calques, R2 « sous le
  pinceau »). Hauteur : roue ~260dp, curseurs ~300dp scroll interne si
  besoin, sets/récentes grilles. Le dock scrolle déjà (`verticalScroll`
  Slice 0) — le panneau ne casse pas le scroll.
- **Téléphone (bottom sheet maison, Slice 0 §3)** : mêmes 4 onglets en
  feuille bas, disque ≥ 160dp, sliders 48dp ; la barre basse
  (pastille+hex+pipette) reste épinglée en haut de la feuille.
- **Cibles tactiles ≥ 48dp** : chips d'onglets, bouton pipette, bouton `+`
  set, pastilles de sets/récentes (visuel 32–40dp + padding → 48dp),
  sliders (zone 48dp). Pastille actuelle témoin : 48dp.
- États repliés : le panneau naît **repliable** (règle Slice 0 n°4) ;
  replier ne perd ni `inkHex` ni sets (états hoistés, §2).

## 6. Strings FR/EN proposées (noms `R.string`, sans littéraux)

Convention Slice 0 §4 : clés snake_case, accents FR uniquement en
`values-fr`, placeholders comme l'existant. Clés `red/green/blue`
existantes conservées.

| Clé | EN (`values`) | FR (`values-fr`) | Usage |
|---|---|---|---|
| `color_wheel` | Wheel | Roue | onglet 1 |
| `color_sliders` | Sliders | Curseurs | onglet 2 |
| `color_sets` | Sets | Nuanciers | onglet 3 |
| `color_recent` | Recent | Récentes | onglet 4 |
| `color_hue` | Hue %1$d° | Teinte %1$d° | slider H |
| `color_sat` | Sat %1$d%% | Sat. %1$d %% | slider S |
| `color_val` | Value %1$d%% | Val. %1$d %% | slider V |
| `color_hex` | #%1$s | #%1$s | champ hex (hint identique) |
| `color_hex_invalid` | Invalid code — kept %1$s | Code invalide — %1$s conservé | fallback hex |
| `color_set_base` | Base | Base | set intégré |
| `color_set_new` | New set | Nouveau | bouton + set |
| `color_set_add` | Add current | Ajouter actuelle | + pastille |
| `color_set_rename` | Rename… | Renommer… | menu appui long |
| `color_set_delete` | Delete | Supprimer | menu / confirmation |
| `color_recent_empty` | Draw to fill recents | Dessinez pour remplir | grille vide |
| `color_recent_clear` | Clear | Effacer | bouton corbeille |
| `pipette` | Picker | Pipette | bouton (1 tap = armer) |
| `pipette_armed` | Tap canvas to sample | Touchez le canvas | statut armé |
| `pipette_picked` | Sampled %1$s | Échantillonné %1$s | statut succès |
| `pipette_missed` | Outside canvas | Hors canvas | statut raté |
| `pipette_hold` | Hold for loupe | Maintenir : loupe | hint appui long |

Notes : `red/green/blue`, `cancel/ok`, statut éphémère existants
réutilisés ; aucun libellé repris d'outil tiers (mots génériques :
roue, curseurs, nuancier, pipette — vocabulaire courant, pas de
marque).

## 7. Tests instrumentés à ajouter (gate local `emutest.sh`)

Fichier existant étendu : `EngineConformanceTest.kt` (émulateur KVM
local, jamais GitHub CI — règle AGENTS.md). Pattern établi
(`findEngineSurface`, `MotionEvent.obtain`, `pullFrameFull` + scan,
`Thread.sleep`). Couleur = pixels moteur : tester via strokes réels
ou `pullFrameFull` après `setBrush`+stroke, jamais via état Compose seul.

1. `wheelDrivesEngine` (roue→moteur) — fixe la couleur via l'onglet Roue
   (clic disque programmé ou appel `onPick` — décision implémentation ;
   au minimum : sélectionne pastille rouge du set Base → stroke 1-doigt
   → `pullFrameFull` : pixels non-blancs à dominante R (R > G+40 &&
   R > B+40 sur ≥ 10 px). Prouve la chaîne §2 bout-en-bout.
2. `recentsSurviveRestart` (sets persistés après restart) — crée un set
   « Test » + 2 couleurs via UI (ou API `ColorStore` exposée test-only),
   tue et relance l'activité (`ActivityScenario.recreate()`), assert :
   set « Test » présent avec les 2 hex, `active` inchangé ; JSON
   `color-sets.json` présent et parse sans crash après corruption
   volontaire (écrit `{{{` → relance → fallback Base, test robustesse).
3. `pipetteSamplesRightColor` (pipette = bonne couleur) — trace un stroke
   rouge large (setBrush rouge + stroke couvrant le centre, > 200 px),
   arme la pipette (tap bouton), tap centre canvas ; assert : `inkHex`
   (exposé via getter test-only ou pastille `contentDescription`)
   == couleur du stroke à ±1 LSB/canal (tolérance anti-dithering ;
   comparer via `pullFrameFull.getPixel` centre vs `inkHex` parsé).
   Puis : tap hors papier (coin fond) avec pipette armée → couleur
   inchangée + statut `pipette_missed`.
4. `slidersAndHexAgree` — onglet Curseurs : pousse R=255 G=0 B=0 via
   sliders (ou `onPick("#FF0000")`), assert pastille+hex affichent
   `#FF0000` ; tape `#00FF00` dans le champ hex → sliders R/G/B et T/S/V
   suivent (relit positions slider ou `inkHex`) ; hex invalide
   (`#ZZZZZZ`) → couleur inchangée + statut `color_hex_invalid`.
5. `touchTargets48dp` — parcourt les nœuds (chips onglets, pipette, `+`,
   pastilles, sliders) : `boundsInRoot.height ≥ 48dp` (tolérance 0.5dp)
   en 1920×1200 **et** 1080×2400 (bottom sheet). Échoue si un visuel
   32dp n'a pas son padding tactile.
6. `recentsFillOnStrokeOnly` — vide les récentes, drag sliders sans
   dessiner → récentes toujours vides ; trace 2 strokes (rouge puis
   bleu) → récentes = [bleu, rouge] dédupliquées ; 3e stroke rouge →
   rouge remonte en tête sans doublon.

Exigences communes : chaque test reset `inkHex` à `#141414` + set actif
« Base » en teardown ; seuils couleur ±1 LSB ; debounce persistance
attendu (`sleep` 700 ms avant assert fichier) ; tests 1–4 sur tablette
1920×1200, smoke 1080×2400 : au moins pipette + 48dp verts.

## 8. Portée Slice 2 (ce que cette spec autorise à coder ensuite)

1. `ColorPanel` (conteneur 4 onglets + barre basse pastille/hex/pipette,
   contrôlé par `inkHex`, §2) remplaçant l'usage direct de `ColorPicker`
   dans le dock ; `ColorPicker` actuel conservé ou refactoré en onglet
   Curseurs (décision implémentation — pas de suppression sèche avant
   que les tests §7 soient verts).
2. Roue HSV maison (Canvas, §3.1) + Curseurs HSV+RGB+hex éditable (§3.2).
3. Sets JSON maison + persistance debouncée (§3.3) + Récentes session +
   persistées (§3.4).
4. Pipette app-side (arme/tap/appui-long-loupe, sample composite via
   mapping inverse Slice 1, §4) + loupe overlay composite-only.
5. Hoist couleur `ColorStore/inkHex` (§2) + strings §6 (EN+FR).
6. Les 6 tests §7 verts en `emutest.sh` local (tablette ; smoke téléphone).

Hors portée : couleur par calque / teinte moteur (filtres M3 et tons M10
restent les seuls traitements pixels), alpha pinceau (opaque existe déjà,
pas de slider A en Slice 2), workspaces nommés (n°6), miniature
navigation (n°9), persistance vue Slice 1 — tous renvoyés au backlog
`csp-ux-research.md` §3.

## 9. Risques

- **Disque HSV maison décevant** : un dégradé conique approximé peut
  faire « jouet » face aux attentes pro. Atténuation : N ≥ 72 segments +
  sur-échantillonnage radial ; si le rendu déçoit en revue visuelle,
  basculer sur anneau teinte + carré S/V (même contrat `onPick`, tests
  inchangés).
- **Sample vs affichage** : la pipette lit le composite **moteur**, la
  loupe montre le bitmap **affiché** (zoom/rotate overlays, damier,
  mannequin M9) — un sample au bord du papier ou sur damier peut
  surprendre. Atténuation : règle « hors papier = raté » (§4) + tolérance
  ±1 LSB documentée dans le test 3.
- **JSON corrompu / migration** : `version:1` dès maintenant ; tout parse
  échoue → fallback Base silencieux (jamais de crash au boot — le test 2
  l'exige). Ne jamais écrire pendant un stroke (debounce + `onPause`).
- **Triple synchro HSV↔RGB↔hex** : boucles infinies `onValueChange →
  onPick → recompose → slider` si mal câblé. Atténuation : sliders
  écrivent, relisent `inkHex` (contrôlé, §2.1) ; arrondis canal à l'entier
  le plus proche, un seul `onPick` par frame.
- **48dp partout** : sliders Material 24dp + grilles denses = échec test 5
  facile. Prévoir dès la maquette : padding tactile invisible (visuel
  32dp + `padding` → 48dp hit), vérifié par le test lui-même.
- **i18n** : `Récentes`, `Échantillonné`, `Réinitialiser…` longs en FR —
  `maxLines=1` + ellipsis sur chips/statuts (leçon Slice 0 §7) ; `%` FR
  avec espace insécable via ressources, jamais en dur.
