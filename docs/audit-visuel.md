# Audit visuel — défauts restants (hors D1-D8)

> SPEC uniquement, aucune modification de code.
> Relu : `/tmp/opencode/openclip-v1.png`, `openclip-v2.png`,
> `openclip-v3.png`, `openclip-v3-app.png`, `openclip-v3-dock.png`,
> `openclip-v3-chips*.png` (×5), `openclip-v3-vscroll.png`,
> `openclip-v3-drag*.png` (×2), `openclip-v3-tap*.png` (×3),
> `openclip-v3-raw.png` (launcher, hors app).
> Code : `MainActivity.kt`, `ToolPalette.kt`, `PinnedBrushBar.kt`,
> `LayerPanel.kt`, `StudioChrome.kt`, `DocTabs.kt`, `DocTitleBar.kt`,
> `MenuBar.kt`, `CommandBar.kt`, `TimelineStrip.kt`, `V3Panels.kt`.
> `docs/slice7-polish.md` D1-D8 : traités comme acquis, **non répétés**
> (sauf quand une preuve montre qu'un point D1-D8 n'est pas appliqué —
> signalé comme non-régression, pas comme nouveau défaut).

## Couverture et angles morts

- Toutes les preuves sont **téléphone portrait 1080×2400, locale EN**.
  Aucune capture tablette 1920×1200, aucun paysage, aucune locale FR,
  aucun dark-HDR/autre densité dans ce lot. Les défauts ci-dessous sont
  donc des constats téléphone/EN ; chaque fix proposé exige le pendant
  tablette + FR (tests § Tests).
- `openclip-v3-raw.png` = écran d'accueil Android (hors app) : une seule
  info utile, reprise en V18 (icône launcher invisible sur l'écran).

## Inventaire

### V1. Pastilles de la barre pinceau recouvrent leurs labels (bloquant)

- **Où / quand** : `PinnedBrushBar` bas de canvas, dock ouvert.
  Preuves `v3-app`, `v3-dock`, `v3-tap*`, `v3-vscroll` : on lit
  `10px ●Brus●` — les deux pastilles bleues des sliders écrasent le
  texte `Brush 100%` ; en `v1` : `● Brush 100% ● Stabilize 15%`.
- **Cause** : `PinnedBrushBar.kt` l.78-89 — `Row` en
  `widthIn(max=420dp).fillMaxWidth()` avec **6 enfants** dont 2 sliders
  en `weight(1f)` sans largeur minimale (`pinned-size`, `pinned-opaque`,
  l.102-147). Quand le dock latéral mange la largeur (voir V5), les
  `weight` s'écrasent vers 0 et les pastilles (≥ 20dp visuels + zone
  tactile 48dp) débordent sur les labels voisins à largeur fixe
  (40dp / 72dp).
- **Fix** : largeur minimale 96dp par slider + `horizontalScroll` de
  secours sur la rangée, OU bascule compacte (labels `%` seuls, pastilles
  seules) sous 320dp utiles. Jamais de chevauchement texte/contrôle.
- **Test** : `pinnedNoOverlap` — dock ouvert à 360dp : `boundsInRoot`
  de chaque pastille disjoints de chaque `Text` de la barre.

### V2. `POKE` vert dans le footer (bloquant, perçu comme bug)

- **Où / quand** : footer `WebDAV POKE` en vert `ok` sur `v1` et `v2`.
- **Cause** : `MainActivity.kt` l.960-963 — `poke()` écrit
  `status.value = "POKE"` comme simple déclencheur de recomposition
  (`refreshKey`). Appelé aux l.1014, 1373, 1378, 1382, 1419 : chaque
  mutation doc laisse le mot interne `POKE` affiché à l'utilisateur
  en couleur « succès ».
- **Fix** : `poke()` ne touche plus `status` (`refreshKey.value++`
  seul). Le vert reste réservé aux retours d'action (règle slice 7).
- **Test** : après activate/undo/save/import, `status-line` ne contient
  jamais `POKE` (assert négatif, EN + FR).

### V3. Nom ×3 et Save ×3 en mode compact (majeur)

- **Où / quand** : toutes les preuves compactes — barre `☰ Untitled 1 …
  Save` + rangée d'onglets (`Untitled 1 ×` + `+` **alors qu'un seul doc
  est ouvert**) + barre titre (`Untitled 1 … 2048×1536`) + `CommandBar`
  (`Save Undo Redo Export Full screen ⋯`). Le nom apparaît 3 fois,
  `Save` 3 fois (barre compacte, command bar, menu Fichier).
- **Cause** : D4 a vidé le footer, mais la combinaison compacte garde
  les 3 sources (`CompactMenuBar` titre + `DocTabs` + `DocTitleBar`,
  `MainActivity.kt` l.1788-1873). La règle slice 7 §5 « onglets masqués
  si 1 doc » n'est pas appliquée : `DocTabs` compose toujours la rangée
  + `+`.
- **Fix** : en compact + 1 seul doc, masquer `DocTabs` (garder un `+`
  discret dans la barre titre) ; en compact + ≥ 2 docs, la barre
  compacte ne montre que `☰` + `Save` (pas de titre, les onglets
  portent le nom). Un seul `Save` visible par état.
- **Test** : `compactSingleSource` — 1 doc compact : `doc-tabs`
  absent, un seul nœud `doc-save` ; 2 docs : titre compact absent,
  onglets présents.

### V4. Rangée d'outils coupée `10|` sans affordance (majeur)

- **Où / quand** : **toutes** les preuves — dernier chip de la rangée
  `Brush Eraser Draw Selection Text` coupé mi-lettres (`10|`).
- **Cause** : la rangée a bien un `horizontalScroll` (famille D3), mais
  aucun indice visuel (fade de bord, pastille `…`, compteur) : un
  utilisateur ne devine pas le scroll, et le chip coupé ressemble à un
  bug. Même famille : header du dock `Side dock [Studio] (` — le chip
  suivant n'est qu'une parenthèse (preuves `vscroll`, `drag`, `tap`).
- **Fix** : fade de bord 12dp + `contentPadding(end=12dp)` sur chaque
  rangée scrollable du chrome (outils, header dock, rangées V2/V3) ;
  jamais de lettre coupée sans fade.
- **Test** : `scrollAffordance` — si contenu > viewport, fade présent
  (assert tag) ; scroll → dernier chip entièrement visible et cliquable.

### V5. Dock 290dp fixe : canvas réduit à une bandelette (bloquant téléphone)

- **Où / quand** : toutes les preuves dock ouvert — le canvas n'est
  plus qu'une languette blanche à gauche (~1/4 de la largeur), le dock
  occupe le reste. Dessiner est impossible dock ouvert sur téléphone.
- **Cause** : `MainActivity.kt` l.2150-2155 — `CollapsibleDrawer`
  `width(if (dockOpen) 290dp else 56dp)` côte à côte avec le canvas
  (`Row` + `weight(1f)`, l.1877-1881), sans breakpoint. 290dp sur
  ~360dp utiles = plus de place pour dessiner.
- **Fix** : sous 600dp, le dock n'est plus latéral : feuille
  recouvrante (pleine largeur, 70 % hauteur max, fond semi-opaque,
  tap-dehors = fermer) ou tiroir bas. Le côte-à-côte reste réservé
  aux largeurs ≥ 600dp.
- **Test** : `phoneDockIsOverlay` — dock ouvert à 360dp : canvas ≥ 50 %
  de la largeur, OU dock en overlay (assert `boundsInWindow` canvas
  pleine largeur derrière le voile).

### V6. Ligne calque : double bordure + dosage tronqué (majeur)

- **Où / quand** : carte `Layer 1` sur `v3-dock`, `chips`, `vscroll`,
  `tap*` — cadre bleu épais **+** bordure bleue de la vignette (double
  contour), et la zone dosage affiche `Layer` au lieu d'un pourcentage
  (`Slider … Layer normal ▾`).
- **Cause** : `LayerPanel.kt` l.597-609 — fond `secondaryContainer` +
  `border(3dp, primary)` sur toute la carte **alors que** la vignette
  porte déjà `border(2dp, primary)` (l.893-897) ; label dosage
  `layer_opacity_layer` en `width(44dp)` monospace (l.759-767) : trop
  étroit, le nombre est poussé hors champ.
- **Fix** : sélection = fond seul OU bordure seule (pas les deux) ;
  dosage `width(56-64dp)` + `maxLines=1 ellipsis`, de préférence format
  court `%d %%` seul (le mot `Layer` n'apprend rien, le slider est
  juste à côté).
- **Test** : screenshot carte sélectionnée → un seul contour ; texte
  dosage matche `\d+ %`.

### V7. Wraps incohérents : `Satura tion`, `Gamut warning` (majeur, FR pire)

- **Où / quand** : rangée d'ajustements (`Brightness Contrast Hue
  Satura/tion` sur 2 lignes, toutes preuves dock) ; `Output proof`
  (`[Proof] [Naive CMYK] [Gamut / warning]` — chip 2 lignes plus haut
  que ses voisins, preuve `v3`).
- **Cause** : labels de `TextButton`/`FilterChip` sans `maxLines=1`
  + `ellipsis` (rangée `ADJUST_TYPES`, `LayerPanel.kt` l.478-503 ;
  chips `ProofPanel`, `V3Panels.kt` l.581-599). En FR (`Luminosité`,
  `Saturation`, `Avertissement`) ce sera pire.
- **Fix** : `maxLines=1 softWrap=false ellipsis` sur **tous** les labels
  de chips/boutons du dock + `horizontalScroll` sur chaque rangée qui
  en manque (`HarmonyPanel` modes l.93, `PosePanel` joints l.353,
  `ChannelsPanel` ops l.197, `ProofPanel` l.581, `ImportPanel` l.679).
- **Test** : `dockRowsSingleLine` en FR — aucune rangée de chips du dock
  > 48dp de haut ; aucun chip coupé sans scroll (avec V4).

### V8. Tête de dock : chip suivant réduit à `(` (mineur)

- **Où / quand** : `Side dock [Studio] ( … ⟩` (`vscroll`, `drag*`,
  `tap*`) — le bord du chip suivant dépasse comme une parenthèse
  orpheline.
- **Cause** : `headerExtra` scrollable (`MainActivity.kt` l.2166-2174)
  sans `contentPadding` ni fade : le viewport coupe le chip en plein
  milieu.
- **Fix** : `contentPadding(end=12dp)` + fade (avec V4) ; à défaut,
  pastille `+n` au lieu d'un chip coupé.
- **Test** : couvert par `scrollAffordance` (V4) appliqué au header.

### V9. Contenu coupé sous le header et sur le rail Timeline (mineur)

- **Où / quand** : `Import .glb` décapité sous le header du dock
  (`drag`, `vscroll`) ; `One slice per file` mangé par le rail
  `Timeline` en bas (`v2`).
- **Cause** : `dock-scroll` (`MainActivity.kt` l.2221-2226) et boîte
  timeline (l.2413-2421) en `verticalScroll` **sans** `contentPadding`
  vertical : le premier/dernier item colle la bordure et passe
  dessous au scroll.
- **Fix** : `contentPadding(top=8dp, bottom=8dp)` équivalent
  (`Spacer`/`padding` déjà partiel : ajouter 8dp bas manquant) des
  deux côtés.
- **Test** : scroll haut → premier item entièrement visible ;
  scroll bas → dernier item entièrement visible (`boundsInWindow`).

### V10. Undo/Redo toujours « actifs », effet nul (mineur)

- **Où / quand** : `CommandBar` — `Save Undo Redo Export Full screen`
  affichés en blanc identique sur doc neuf ; un tap `Undo` sans
  historique ne fait rien et ne dit rien (boutons `TextButton` sans
  état `enabled`, `CommandBar.kt` l.204-220).
- **Cause** : la barre ne connaît pas `canUndo/canRedo` (vivant dans
  `ToolPalette`, l.82-83) : pas d'état désactivé possible.
- **Fix** : griser (`contentColor` hint + alpha) quand la pile est vide
  (hoister `canUndo/canRedo` ou exposer un état), + hint statut au tap
  inopérant. Pas de bouton mort silencieux.
- **Test** : doc neuf → `cmdbar-undo` en couleur hint ; tap → hint
  statut affiché.

### V11. Rail `Timeline ▸` : on dirait un contrôle désactivé (mineur)

- **Où / quand** : rail replié `Timeline ▸` gris hint sur toutes les
  preuves ; la pastille `i/N` n'apparaît qu'au-delà de 3 cels
  (`TimelineStrip.kt` l.349).
- **Cause** : glyphe + label en `ink-hint` (`StudioChrome.kt` l.91-96),
  même ton que le texte réellement inerte ; seuil `count > 3` arbitraire.
- **Fix** : glyphe en accent (`#90B8FF`) quand le tiroir est fermé
  (appel à l'action), pastille `i/N` dès 2 cels.
- **Test** : contraste glyphe ≥ 4.5:1 (mesuré, pas à l'œil) ; pastille
  visible à 2 cels.

### V12. `Show overlay` et presets de pose sans modèle (mineur)

- **Où / quand** : `ReferencePanel` (`drag`, `tap*`) — case `Show
  overlay` petite visuellement ; `Stand Sit Run` + sliders `Yaw…Fisheye`
  présents alors que `No posable model — load a libre glTF mannequin.`
  (`v3` onglet Comfort).
- **Cause** : état vide en texte nu, actions armées quand même (les
  presets écrivent une pose sans squelette ; la case ne change rien de
  visible sans modèle).
- **Fix** : sans modèle : presets et sliders désactivés (hint), CTA
  `Import .glb` mis en avant ; case à cocher ≥ 48dp de zone tactile
  (à confirmer au test, Material 3 l'assure normalement).
- **Test** : `poseEmptyState` — sans modèle : presets `disabled`,
  `v3-pose-refresh` visible ; avec modèle : presets actifs.

### V13. Vignettes timeline : cache non borné, jamais recyclées (majeur, perfs)

- **Où / quand** : invisible à l'œil sur les preuves, mais structurel —
  `TimelineStrip.kt` l.100 : `thumbs = remember { mutableStateMapOf() }`
  sans limite ni `recycle()` (contrairement au LRU 60 de `LayerPanel`,
  l.203-209). Chaque frame garde son `Bitmap` pleine largeur.
- **Cause** : pas de LRU, pas de purge à la suppression de frame.
- **Fix** : LRU 24 + `recycle()` à l'éviction/suppression, même motif
  que les calques ; garder le 96px (déjà petit). Voir volet perfs de
  `docs/rebrand.md` pour l'ordre.
- **Test** : clip 50 frames → taille du cache bornée (hook
  `thumbSnapshot`-like à ajouter) ; `dumpsys meminfo` stable après
  suppressions.

### V14. `setBrush` à chaque tick de slider (majeur, perfs)

- **Où / quand** : tout drag de `PinnedBrushBar` (`onValueChange`,
  l.111-116, 140-146, 153-160) ou `ToolPalette` (l.267-301) appelle
  `surface.setBrush()` **par événement**, soit des dizaines d'appels
  moteur par seconde pendant un drag.
- **Cause** : pas de throttle/debounce ni de séparation
  prévisualisation/validation.
- **Fix** : prévisualisation locale immédiate, `setBrush` au plus
  toutes les ~33ms + `onValueChangeFinished` systématique (voir
  `docs/rebrand.md`, gain le plus élevé, risque le plus faible).
- **Test** : compteur `setBrush` pendant un drag 500ms : ≤ 20 appels,
  valeur finale exacte.

### V15. `status`/`refreshKey` au sommet recomposent tout (mineur, perfs, à mesurer)

- **Où / quand** : `OpenClipScreen` — `status`, `refreshKey`,
  `strokeGen` hoistés haut (`MainActivity.kt` l.255-300) ; chaque
  `poke()` (V2) recompose menus, onglets, dock scrollable et timeline.
  Le canvas (`AndroidView`) ne redessine pas, mais tout le chrome
  Compose se réévalue.
- **Cause** : état lu par le footer mais détenu par l'écran entier.
- **Fix** : scoper (`status` lu uniquement dans le composable footer ;
  `refreshKey` découpé par zone) + `key()`/`remember` sur les gros
  blocs. Mesurer d'abord (profiler Compose : recomposition counts),
  pas d'optimisation aveugle.
- **Test** : compteur de recompositions du dock sur `poke()` isolé :
  0 recomposition du contenu du dock (hook ou metrics du compilateur
  Compose).

### V16. Paysage téléphone : chrome ×8 rangées, canvas ~0 (bloquant, non prouvé mais certain)

- **Où / quand** : non capturé (aucune preuve paysage), mais arithmétique :
  8 rangées (~48dp × 7 + 34dp ≈ 370dp) sur ~360dp de hauteur utile en
  paysage 2400×1080 → canvas négatif. `configChanges` gère la rotation
  (`AndroidManifest.xml` l.18) sans layout alternatif.
- **Cause** : colonne verticale unique, aucun mode paysage.
- **Fix** : en paysage < 600dp de haut : passer le chrome en latéral
  (dock droit existant + barre d'actions verticale fine) ou plein écran
  avec overlays ; jamais 8 rangées empilées.
- **Test** : `landscapeUsable` — rotation paysage : canvas ≥ 40 % de la
  hauteur, toutes les actions restent atteignables (menu `☰`, save,
  undo, export).

### V17. Contrastes : un seul point à mesurer, pas de violation avérée

- Vérifié par construction (règle slice 7 §4) : `hint #787888` sur
  `#1A1A20` ≈ 4.9:1 ; `ok`/`err` conformes. Les `⋯`/`⌄`/`Timeline ▸`
  en hint sont donc **lisibles mais mous** (V11), pas illisibles.
- **Reste à mesurer** : hint des `OutlinedTextField` (`Search layers`)
  enscheme Material 3 dark — si `onSurfaceVariant` du thème < 4.5:1,
  forcer la couleur du placeholder. Test : mesure instrumentée du ratio
  sur `layer-search` (lire les pixels, pas le token).
- **Dark suintements** : aucun fond clair intrusif dans l'app sur ce lot
  (le blanc de `v3-raw` = écran d'accueil Android, hors app).

### V18. Icône launcher invisible sur l'écran d'accueil (mineur, vers rebrand)

- **Où** : `v3-raw` — l'écran d'accueil ne montre aucune icône
  OpenClip ; `app/src/main/res/drawable/ic_logo.xml` = cercle bleu
  `#0F6FFF` + éclat blanc, `android:icon` legacy (pas d'`adaptive-icon`,
  pas de `mipmap-*`, pas de monochrome Android 13+).
- **Effet** : tuile générique, confusable, pas de variante thématique.
  Traité par `docs/rebrand.md` (nouvelle icône adaptive).

## Top 5 (ordre d'attaque)

1. **V5 — dock 290dp côte à côte sur téléphone** : rend le dessin
   impossible dock ouvert ; conditionne V1 (largeur du bandeau pinceau).
2. **V1 — pastilles sur labels du bandeau pinceau** : réglages
   illisibles, le cœur du geste.
3. **V2 — `POKE` vert** : 3 lignes à changer, effet « app cassée »
   immédiat sur chaque capture.
4. **V3 — nom ×3 / Save ×3** : la confusion permanente, fin de D4.
5. **V4+V7 — scrolls sans affordance et wraps** : la famille « ça a l'air
   buggé » (chips coupés, `Satura tion`, `Gamut warning` 2 lignes).

## Tests visuels à ajouter (récap, gate `emutest.sh` local)

| # | Test | Densités | Couvre |
|---|---|---|---|
| T1 | `pinnedNoOverlap` | téléphone dock ouvert | V1 |
| T2 | `statusNeverPoke` | téléphone + tablette | V2 |
| T3 | `compactSingleSource` | téléphone 1 doc / ≥ 2 docs | V3 |
| T4 | `scrollAffordance` | téléphone + tablette | V4, V8 |
| T5 | `phoneDockIsOverlay` | téléphone | V5 |
| T6 | `layerCardSingleBorderAndPct` | téléphone + tablette | V6 |
| T7 | `dockRowsSingleLine` (FR) | téléphone + tablette, locale FR | V7 |
| T8 | `scrollEdgePadding` | téléphone | V9 |
| T9 | `undoDisabledState` | téléphone | V10 |
| T10 | `timelineRailContrastAndCount` | téléphone | V11 |
| T11 | `poseEmptyState` | téléphone | V12 |
| T12 | `thumbCacheBounded` | téléphone | V13 |
| T13 | `brushPushThrottled` | téléphone | V14 |
| T14 | `dockNoRecomposeOnPoke` | téléphone (profiler) | V15 |
| T15 | `landscapeUsable` | téléphone paysage | V16 |
| T16 | `textfieldHintContrast` | téléphone + tablette | V17 |
| T17 | `frenchLongStringsEllipsize` (étendre slice 7 §7.4 au dock V2/V3) | téléphone FR | V7 + FR |

Conventions communes : reset 1 doc 2048×1536, locale EN sauf T7/T17,
theme dark, teardown avec `sleep` persistance comme slice 7 §7.
