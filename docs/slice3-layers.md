# Slice 3 — Calques pro (SPEC, sans code)

> SPEC UNIQUEMENT — aucune modification app/engine. Vocabulaire maison,
> aucun asset ni capture d'outil tiers. Clean-room : jamais de strings,
> icônes, noms de palettes ni layouts repris de Clip Studio Paint / Celsys
> (cf. `docs/csp-ux-research.md` § clean-room). JNI gelé (`ARCHITECTURE.md`) :
> tout ce qui suit est **app-side uniquement** (`LayerPanel.kt`,
> `ToolPalette.kt`, `MainActivity.kt`, ressources `R.string`, JSON maison).
> Composants 100 % originaux.
> Conventions : FR/EN via `R.string` uniquement, pas de littéraux FR
> en code (leçon M5 : apostrophes FR en dur cassent le build).

État vérifié (point de départ) : Slices 0-2 committées (shell, gestes,
couleur PRO). Référence workflow : `docs/csp-ux-research.md` §R4 (liste
compacte, opacité/blend/visibilité au doigt, lisible à 3 comme à 50
calques) + §R2 (rangée compacte taille+opacité épinglée + Tool Property
stabilisation visible pendant le dessin).
Actuel calques : `LayerPanel.kt` (~522 l.) = snapshot `nLayerState`
parsé en `LayerInfo` (index/nom/visibilité/opacité/blend/groupe/
masque/clip/enfants), groupes repliables, `LayerRow` par calque
(sélection, visibilité texte `o`/`-`, slider opacité, dropdown blend
6 modes `BLEND_NAMES`, boutons `Aa ^ v M+ M C +ch`), cluster
ajustement M8 (5 types + Clear + slider montant). Reorder = boutons
haut/bas (pas de DnD). Pas de vignettes.
Actuel pinceau : `ToolPalette.kt` = `BrushState`
(couleur/isBrush/radius/opaque/stabilize/curve M2) → `setBrush(json)` ;
sliders taille (`1..128px`) + opacité inline **non persistants**
(`remember`, reset au restart), stabilisation/courbe cachés derrière
`showAdvanced` (chip `⚙`), symétrie X/Y.
Référence presets : `PresetGallery.kt` (grille 3 colonnes, sélection
bleue, import SAF).

Constat JNI (vérifié) : le moteur n'expose **aucun rendu par calque**.
Seuls existent `nCompositeInto` (composite courant) et
`nFrameCompositeInto(handle, index, …)` (composite par **trame**
d'animation, réutilisé par `TimelineStrip` + `drainThumbQueue` à 96px).
Aucun `nLayerCompositeInto` / `nRenderLayer`. Donc les vignettes par
calque ne peuvent pas être un simple appel — voir §1 (stratégie
app-side sans JNI, JNI futur seulement documenté).

## 1. Vignettes live — stratégie « solo-render » app-side (aucun JNI)

Principe : réutiliser les symboles existants pour isoler chaque calque,
**sans nouveau JNI** :

```
refreshThumbs(layers):
  snapshot visibilités actuelles (nLayerState, déjà lu)
  pour chaque calque visible à l'écran (seulement les lignes affichées) :
    file d'attente debounce 400 ms après dernier stroke / layerOp
    worker fond (jamais UI thread) :
      1. masque tous les calques sauf i (setLayerVisibility × N, existant)
      2. nCompositeInto -> pixels -> downscale 48px (crop carré centré)
      3. restaure les visibilités snapshot
      4. needsComposite = true -> recompose canvas (rendu existant)
    cache { layerIndex -> Bitmap 48px + génération }
```

Règles :

1. **Debounce strict** : aucun solo-render pendant un stroke (flag
   `isDrawing` existant côté `EngineSurface`) ; la file part 400 ms
   après `onStrokeChanged` / `refresh()` / scroll idle. Ne jamais
   solo-render pendant un drag de reorder (§2) — vignettes gelées,
   rafraîchies au drop.
2. **Fenêtre visible seule** : avec `LazyColumn`, seuls les index
   visibles demandent une vignette (les autres gardent le placeholder).
   À 50 calques, jamais plus de ~8 composites par passe.
3. **Cache + génération** : clé `(layerIndex, strokeGen, opacity,
   blend, visible)` — tout `layerOp`/stroke incrémente `strokeGen` et
   invalide ; LRU ≤ 60 entrées (48×48 ARGB ≈ 9 Ko/unité, négligeable).
   Calque invisible → pas de render, placeholder « œil barré ».
   Groupe → vignette = mosaïque 2×2 des 4 premiers enfants (même
   pipeline, enfants solo-render) ou icône dossier si replié vide.
4. **Atomicité visuelle** : pendant le solo, le canvas affiché ne doit
   pas flasher — le worker tourne sur `engineLock` (même verrou que
   `drainThumbQueue`) et le `renderLoop` saute une frame
   (`needsComposite` re-armé après restauration ; au pire 1 frame
   ≈ 16 ms de retard, pas de flash car le bitmap affiché n'est
   remplacé qu'au prochain composite complet).
5. **Fallbacks** : échec composite → placeholder damier (maison, même
   damier que fond canvas) ; calque vide (0 px peints) → damier aussi
   (distingué du calque masqué par l'indicateur §4, pas par la
   vignette). Timeout worker 2 s → abandon silencieux + retry au
   prochain idle.
6. **Coût assumé** : O(visibles) composites par refresh (~8 × 5 ms en
   KVM local, mesuré sur `drainThumbQueue`). Sur appareil lent,
   dégrader : vignettes seulement du calque actif + voisins ±2
   (flag `REDUCED_THUMBS`, décision implémentation).

**JNI futur (documenté, PAS cette slice)** : si le solo-render déçoit
(flicker ou lenteur à 50 calques), le bon JNI serait
`nLayerCompositeInto(handle, layerIndex, pixels, w, h)` — composite
d'un seul calque sans toucher aux visibilités, exposé dans
`ARCHITECTURE.md` d'abord, puis câblé dans `drainThumbQueue`. La
présente spec n'en a pas besoin : le cache + fenêtre visible tiennent
sans lui. Ne jamais bricoler un JNI « vite fait » sans passer par
`ARCHITECTURE.md` (règle gel).

Repère visuel : vignette **48×48dp** carrée, coins 6dp, bordure
1dp (sélection = bordure accent 2dp, même langage que
`PresetGallery`), damier maison en fond (transparence lisible),
`contentDescription` = nom du calque + « vignette ».

## 2. Ligne calque — anatomie cible (≥ 48dp, 1 main)

Chaque ligne passe en `LazyColumn` (remplace le `Column.forEach`
actuel — indispensable à 50 calques) et suit cette anatomie fixe,
hauteur **≥ 56dp** (zone tactile totale ≥ 48dp garantie) :

```
┌──────────────────────────────────────────────┐
│ [48px] Nom………… [C][M] [👁] │  rang 1 : vignette + nom + indicateurs + visibilité
│ [48px] opacité ─────○── 78%  [blend ▾]      │  rang 2 : slider + % + blend cherchable
└──────────────────────────────────────────────┘
  sélection = fond secondaryContainer (existant, conservé)
  calque actif = bordure gauche accent 3dp en plus
```

- **Rang 1 (56dp)** : vignette 48dp à gauche (tap = sélection ;
  **double-tap = renommer**, remplace le bouton `Aa`) ; nom `weight(1f)`
  (tap = sélection, ellipsis 1 ligne) ; indicateurs clip/masque §4 ;
  **cible visibilité 48×48dp** (icône œil maison, pas les lettres
  `o`/`-` actuelles — trop petites, illisibles au doigt).
- **Rang 2 (48dp)** : slider opacité pleine largeur restante
  (existant conservé, `onValueChangeFinished` → `setLayerOpacity`) +
  label `%` fixe 44dp (ne saute plus pendant le drag : largeur fixe,
  valeur monospacée) ; chip blend (§3) à droite, tap = ouvre le
  sélecteur cherchable.
- **Actions secondaires** (renommer, masque, clip, enfant, supprimer)
  déménagent dans un **menu appui long** sur la ligne (bottom-sheet
  maison ou `DropdownMenu` — décision implémentation) : la ligne au
  repos ne montre que sélection/visibilité/opacité/blend. Fini les
  6 `TextButton` (`Aa ^ v M+ C +ch`) qui doublent la hauteur et
  exigent une loupe.
- **Groupes** : chevron `▸/▾` 48dp à gauche de la vignette (remplace
  `+`/`-` texte) ; enfants indentés 16dp + ligne verticale guide ;
  replié = 1 ligne (vignette mosaïque + nom + compteur `N`).

Drag-reorder tactile :

- Poignée `≡` 48×48dp à l'extrême droite du rang 1 (seule zone de
  drag — le reste de la ligne reste tap/scroll, pas de conflit avec
  le scroll vertical de la liste).
- `Modifier.draggable` vertical (ou `reorderable` maison si la
  dépendance n'est pas voulue — **aucune nouvelle lib sans revue** ;
  `draggable` du framework suffit) : la ligne suit le doigt (offset Y,
  élévation + ombre), les autres se décalent (gap animé 120 ms) ;
  au drop → **un seul** `moveLayer(from, to)` (pas N appels : calculer
  l'index cible final, le moteur ne sait déplacer qu'en absolu).
- Pendant le drag : scroll auto aux bords (hot-zone 64dp haut/bas,
  pas de 8 px/s — 1 ligne/300 ms), vignettes gelées (§1), aperçu
  fantôme semi-transparent de la ligne déplacée.
- Annulation : drag hors liste ou retour à la position = aucun appel
  moteur. Accessibilité : les boutons haut/bas actuels (`^`/`v`)
  **restent** dans le menu appui long (alternative non-tactile +
  tests).
- Groupes : drop sur un groupe replié = `appendChild` ; drop entre
  deux lignes = reorder au même niveau (décision au seuil 50 % de
  la hauteur de ligne, indicateur visuel : ligne bleue inter vs
  contour bleu groupe).

## 3. Blend cherchable (dropdown filtrable)

Remplace le `DropdownMenu` 6 items actuel (qui ne scale pas si le
moteur ajoute des modes — M8 a déjà 5 types d'ajustement) :

- Chip compacte (texte = nom court sans préfixe `svg:`, ex. `multiply`,
  + chevron `▾`, hauteur 48dp, `contentDescription` = « mode de fusion
  : X »).
- Tap chip → dialogue **plein dock** (pas un popup 6 lignes) :
  champ recherche en haut (filtre substring insensible à la casse,
  clavier ouvert par défaut sur tablette, fermé sur téléphone),
  liste `LazyColumn` des modes (nom + **micro-aperçu** : pastille
  24dp montrant le blend appliqué à un dégradé maison fixe —
  pure app-side, `BlendMode` Compose sur deux couleurs témoins,
  jamais les pixels moteur), mode actif coché.
- Tap mode → **prévisualisation immédiate** (`setLayerBlend` direct,
  moteur = vérité, comme aujourd'hui) ; tap hors dialogue / back =
  garde le dernier choisi (pas de cancel-restore : le moteur a déjà
  appliqué, undo = re-choisir l'ancien — documenté dans le hint).
- Ajout futur d'un mode moteur = 0 changement UI (liste lue depuis
  `BLEND_NAMES` étendu — garder la liste en **un seul endroit**,
  exposée test-only).
- Noms affichés : forme courte maison (`normal`, `multiply`, …),
  jamais `svg:…` face utilisateur (le préfixe reste dans le JSON
  moteur — mapping `display ↔ svg:` en un point).

## 4. Indicateurs clip / masque / ajustement (badges, pas texte)

Remplace les marqueurs texte actuels (`[M]`, `[C]`, `M+`, `x `) par
3 badges 24dp visuels (zone tactile 48dp via le menu appui long,
  pas de tap direct) dans le rang 1, après le nom :

| Badge | Signification | Visuel maison |
|---|---|---|
| `↓` flèche entrante | `clipped = true` (écrêté au calque dessous) | pastille bleue + flèche bas |
| `◐` demi-cercle | `hasMask = true`, édition normale | pastille grise demi-pleine |
| `◑` + contour accent | `maskTarget = index` (édition du masque en cours) | pastille accent pleine + anneau |
| `fx` | ajustement M8 présent (`adjust != null`) | pastille violette `fx` (2 lettres, pas un mot tiers) |

- Tap badge = va au réglage (clip → menu appui long ; masque →
  `maskTarget(i, true)` existant ; `fx` → scrolle le cluster
  ajustement M8 sous la liste, existant conservé).
- Couleurs de repérage par calque (§R4 « couleurs de palette ») :
  **app-side pur**, champ `tagColor: Int?` dans `LayerInfo` (jamais
  moteur), 6 pastilles maison dans le menu appui long, reportées en
  pastille 8dp sur la vignette + (futur) timeline 1.6.7-style.
  Persisté dans le JSON maison workspaces (n°6 du backlog), pas en
  Slice 3 — la spec prévoit le champ, l'implémentation le câble
  quand les workspaces arrivent.

## 5. Rangée taille/opacité épinglée + Tool Property (R2)

Problème actuel : taille/opacité vivent dans `ToolPalette` (top
toolbar, `remember` non persisté) et stabilisation/courbe derrière
le chip `⚙`. En plein dessin (canvas focus, dock replié), aucun
réglage n'est visible — R2 exige taille+opacité **sous le pinceau**.

Décision : **barre flottante compacte épinglée bas-canvas**
(overlay composite-only, comme la loupe Slice 1/2 — jamais dans les
pixels moteur) :

```
┌────────────────────────────────────────┐
│ 12px ━━━━━○──── 34% │  [stabilisation 15%] │ …│  rangée épinglée, repliable « – »
└────────────────────────────────────────┘
```

- Contenu : slider taille (`1..128px`, même `valueRange` que
  `ToolPalette`), slider opacité pinceau (`opaque`, pas l'opacité
  calque — les deux coexistent : label `Pinceau %` vs `Calque %`
  dans la ligne §2, confusion interdite), chip stabilisation
  (`stabilize %`, tap = cycle 0/15/50 % ou ouvre mini-slider —
  décision implémentation, M2 `stabilize`/`curve` déjà moteur).
  Courbe (`curve`) reste dans `showAdvanced` (rare) — la rangée
  n'affiche que les 3 réglages du geste courant.
- **Persistance** : `radius/opaque/stabilize` migrent de `remember`
  vers `DataStore`/`JSON maison` (`brush-defaults.json`, même
  pattern que `color-sets.json` Slice 2 : debounce 500 ms + `onPause`,
  fallback `10px/100 %/15 %` si absent/corrompu). `ToolPalette` et la
  rangée lisent/écrivent le **même store hoisté** (`BrushPrefs` dans
  `MainActivity`, calqué sur `ColorStore` Slice 2 §2 — un seul
  `pushBrush()`, jamais deux `setBrush` concurrents).
- Placement : bas du canvas, au-dessus de la timeline repliée
  (offset 8dp), largeur ≤ 420dp centrée, fond semi-opaque 92 % +
  ombre (lisible sur canvas clair/sombre), **repliable d'un tap**
  (`–` → pastille `+` 48dp, règle Slice 0 n°4 : chaque panneau naît
  repliable, le canvas ne perd jamais le focus).
- Téléphone : même rangée, pleine largeur moins 16dp, sliders
  48dp ; ne chevauche jamais la bottom sheet (z-order : sheet >
  rangée ; rangée masquée quand sheet ouverte).
- Tool Property complète (stabilisation + courbe + symétrie X/Y
  `showAdvanced` actuel) **reste** dans `ToolPalette` — la rangée
  est un miroir des 3 urgents, pas un remplacement. Les deux
  écrivent le même `BrushPrefs`, pas de divergence possible
  (contrôlé, même pattern que `ColorPanel` Slice 2 §2.1).

## 6. Lisibilité 3 → 50 calques + tablette/téléphone

- **3 calques** : liste aérée (lignes 64dp, vignettes 48dp, rangs
  respirants) ; bouton `+` grand (48dp, 1 tap — parité Simple Mode
  R4 : `+` puis menu opacité/blend/visibilité = notre rang 2 déjà
  visible, 0 tap de plus).
- **50 calques** : `LazyColumn` + lignes compactes auto (`56dp`
  sous 20 calques — seuil fixe, pas d'animation de collapse),
  groupes repliables repliés par défaut au-delà de 20 lignes,
  scroll rapide (scrollbar maison + lettre/compteur `12/50` en
  overlay pendant le fling), recherche par nom (même champ que
  blend §3 ? Non — champ dédié en tête de liste, filtre substring,
  survit au reorder).
- **Mode compact auto** : `> 20 calques → rang 2 (slider+blend)
  replié sauf calque actif` (tap ligne = déplie son rang 2). Le
  calque actif garde toujours ses réglages visibles (R4 « au
  doigt ») ; les autres = rang 1 seul (56dp → ~2× plus de lignes
  visibles). Seuil et comportement exposés test-only.
- **Tablette 1920×1200@240 (cible)** : dock 290dp, lignes 64dp,
  vignettes 48dp, rangée épinglée 420dp. **Téléphone 1080×2400** :
  dock = bottom sheet (Slice 0 §3), lignes 56dp min, blend via
  dialogue plein écran, rangée épinglée pleine largeur.
- **Cibles ≥ 48dp** : visibilité, poignée drag, chevron groupe,
  chip blend, slider (zone), bouton `+`, badges (via menu) —
  pastilles décoratives seules exemptées (vignette = sélection,
  son tap = 48dp OK).

## 7. Strings FR/EN proposées (noms `R.string`, sans littéraux)

Convention Slices 0-2 : clés snake_case, accents FR uniquement en
`values-fr`. `layers/ok/cancel` existants réutilisés.

| Clé | EN (`values`) | FR (`values-fr`) | Usage |
|---|---|---|---|
| `layer_search` | Search layers | Chercher | champ filtre nom |
| `layer_blend_search` | Search mode | Chercher | champ filtre blend §3 |
| `layer_reorder_hint` | Drag ≡ to reorder | Glisser ≡ | hint drag |
| `layer_rename_hint2` | Double-tap to rename | Double-toucher : renommer | hint (remplace `Aa`) |
| `layer_longpress` | Hold for more | Maintenir : plus | hint menu |
| `layer_opacity_brush` | Brush %1$d%% | Pinceau %1$d %% | rangée épinglée (vs calque) |
| `layer_opacity_layer` | Layer %1$d%% | Calque %1$d %% | rang 2 (vs pinceau) |
| `layer_clipped_on` | Clipped to below | Écrêté au-dessous | badge + menu |
| `layer_mask_editing` | Editing mask | Masque en cours | badge `◑` |
| `layer_fx_present` | Adjustment on | Réglage actif | badge `fx` |
| `layer_tag_color` | Tag | Repère | menu couleur repère §4 |
| `layer_thumb_empty` | Empty | Vide | `contentDescription` damier |
| `layer_pinned_hide` | Hide bar | Masquer | repli rangée §5 |
| `layer_pinned_show` | Sizes | Tailles | pastille `+` repliée |
| `layer_compact_auto` | Compacted %1$d | Compacté %1$d | statut mode compact §6 |

Notes : `layer_add_group/add_vector/children/visible/hidden/rename`
existants conservés ; symboles `≡ ▸ ▾ ◐ ◑ ↓ fx` = glyphes courants,
pas d'icônes tierces ; `%` FR via ressources.

## 8. Tests instrumentés à ajouter (gate local `emutest.sh`)

Fichier étendu : `EngineConformanceTest.kt` (émulateur KVM local,
jamais GitHub CI — règle AGENTS.md). Pattern établi
(`findEngineSurface`, `pullFrameFull`, `layerOp`, `Thread.sleep`).
Jamais d'assert sur état Compose seul quand un pixel moteur peut
prouver.

1. `layerThumbsSoloRender` (vignettes sans JNI) — 2 calques, stroke
   rouge sur calque 0 puis bleu sur calque 1 ; attend le debounce
   (`sleep` 600 ms) ; assert : vignette calque 0 à dominante R et
   calque 1 à dominante B (lit le cache test-only ou capture les
   `Image` affichées — décision implémentation ; tolérance : canal
   dominant > autres +40 sur ≥ 30 % des px 48×48) ; puis masque le
   calque 1 (`setLayerVisibility`) → sa vignette devient damier/
   placeholder, pas bleue (prouve l'invalidation).
2. `layerDragReorderMovesOnce` (DnD = 1 seul `moveLayer`) — 3 calques
   nommés A/B/C (ordre moteur C/B/A, sommet en premier), drag
   programmé (`MotionEvent` long-press poignée + move + up, ou appel
   du handler drop test-only) déplaçant A au sommet ; assert :
   `nLayerState` = ordre attendu **et** compteur d'appels moteur = 1
   (spy test-only sur `moveLayer` ou relecture d'état + pas d'appels
   multiples — au minimum : état final correct + undo M7 cohérent).
   Puis : smoke `^`/`v` du menu (ordre correct, alternative non-tactile).
3. `blendSearchableApplies` (blend cherchable) — ouvre le sélecteur
   depuis la chip, tape `mult` dans la recherche ; assert : seul
   `multiply` visible ; tap → `nLayerState` blend == `svg:multiply`
   **et** composite change (`pullFrameFull` diffère d'avant sur un
   canvas à 2 calques superposés — prouve l'application moteur, pas
   juste le label). Back/re-tap : mode conservé (pas de restore).
4. `pinnedBarPersistsAndMirrors` (rangée épinglée) — règle taille 24px
   + opacité 50 % + stabilisation via la rangée, `ActivityScenario
   .recreate()`, `sleep` 700 ms (debounce) ; assert : rangée affiche
   24px/50 % après restart (store `brush-defaults.json` présent) ;
   stroke → pixels couvrent une surface cohérente avec 24px
   (spot-check : stroke 24px opaque 50 % plus large/clair que stroke
   10px/100 % témoin — comparaison relative, pas absolue) ;
   `ToolPalette` sliders suivent (même `BrushPrefs`, pas de
   divergence : lit `radius`/`opaque` exposés test-only des deux côtés).
5. `touchTargets48dpLayers` — parcourt les nœuds (visibilité,
   poignée ≡, chevron, chip blend, slider zone, `+`, rangée épinglée)
   : `boundsInRoot.{width,height} ≥ 48dp` (tolérance 0.5dp) en
   1920×1200 **et** 1080×2400. Échoue si un visuel 24–32dp n'a pas
   son padding tactile.
6. `fiftyLayersStayReadable` (lisibilité extrême) — crée 50 calques
   (`addLayerNamed` × 50 via `layerOp`), scrolle en bas ; assert :
   mode compact auto actif (lignes non-actives = rang 1 seul,
   hauteur ≈ 56dp), compteur `50/50` affiché, recherche `Layer 4`
   filtre (seules les lignes matchées visibles, tap = sélection +
   déplie rang 2) ; teardown : supprime les 47 surnuméraires
   (ou recrée le doc — décision implémentation, jamais de doc
   à 50 calques committé en fixture).
7. `clipMaskBadges` — `setLayerClipped(i, true)` + `addLayerMask(j)`
   + `maskTarget(j, true)` via UI ou `layerOp` ; assert : badges
   `↓` / `◑` visibles avec bons `contentDescription`
   (`layer_clipped_on` / `layer_mask_editing`), tap badge masque →
   `maskTarget` reflété dans `nLayerState` ; calque avec ajustement
   M8 (`setLayerAdjustment`) → badge `fx` visible.

Exigences communes : chaque test reset (3 calques témoins, opacité 1,
blend normal, `BrushPrefs` 10px/100 %/15 %) en teardown ; seuils
couleur ±1 LSB + dominante +40 ; debounce attendu (`sleep` ≥ 600 ms)
avant asserts vignettes/persistance ; tests 1–4,6–7 sur tablette
1920×1200, smoke 1080×2400 : au moins reorder + 48dp verts.

## 9. Portée Slice 3 (ce que cette spec autorise à coder ensuite)

1. `LazyColumn` lignes 2 rangs (§2) + menu appui long (remplace les
   `TextButton` ; `^`/`v`, `Aa`, `M+`, `C`, `+ch` y survivent comme
   alternatives), chevron groupes 48dp, compteur `N/50`.
2. Vignettes solo-render app-side + cache LRU + debounce (§1),
   placeholders damier, mosaïque groupes. **Aucun JNI.**
3. Blend cherchable + micro-aperçus Compose (§3), mapping
   `display ↔ svg:` en un point.
4. Badges clip/masque/`fx` + champ `tagColor` app-side (§4 ;
   persistance workspaces renvoyée au n°6 du backlog).
5. Rangée taille/opacité/stabilisation épinglée + `BrushPrefs`
   persisté + miroir `ToolPalette` (§5), repliable, composite-only.
6. Mode compact auto + recherche nom + strings §7 (EN+FR).
7. Les 7 tests §8 verts en `emutest.sh` local (tablette ; smoke
   téléphone).

Hors portée : `nLayerCompositeInto` (documenté §1, JNI futur hors
slice), couleurs repères persistées (attendent workspaces n°6),
navigateur miniature (n°9), multi-docs onglets (n°5), command bar
(n°8), alpha pinceau par canal (opaque existe, pas de slider A),
suppression calque avec confirmation système (menu inline, même
pattern que sets Slice 2).

## 10. Risques

- **Solo-render = N jeux de visibilité** : entre masquer et restaurer,
  un stroke ou un undo concurrent corrompt l'état (calque resté
  masqué). Atténuation : tout sous `engineLock` + `strokeGen` vérifié
  avant restauration (si `strokeGen` a bougé pendant le worker :
  jeter le résultat, restaurer, retry au prochain idle) ; test 1
  couvre l'invalidation, jamais de solo-render pendant `isDrawing`.
- **Flash canvas pendant le solo** : le `renderLoop` peut composer
  entre le masquage et la restauration. Atténuation : flag
  `suspendRender` pendant la fenêtre (pattern `needsComposite`
  existant — le loop saute, reprend après) ; au pire 1 frame sautée,
  documentée. Si un flash est observé en revue : repli = vignettes
  calque actif ±2 seuls (`REDUCED_THUMBS`).
- **50 composites = lenteur ressentie** : même fenêtrés, 8 solos +
  downscales sur appareil lent peuvent dépasser 500 ms.
  Atténuation : cache agressif (seuls les calques sales re-rendent),
  downscale direct à 48px (pas de full Bitmap intermédiaire si
  possible — décision implémentation), mode compact §6 qui réduit
  les lignes à-thumbées.
- **Drag vs scroll** : le `draggable` vertical sur la poignée entre
  en conflit avec le scroll de la `LazyColumn` si la hitbox déborde.
  Atténuation : drag **seulement** sur la poignée 48dp (le reste =
  scroll/tap), `awaitLongPress` avant de capturer (le scroll reste
  prioritaire au tap court) ; test 2 + test manuel tablette exigés.
- **`moveLayer` absolu vs drop relatif** : calculer `to` faux =
  calque à la mauvaise place sans erreur moteur. Atténuation : un
  seul calcul `from → to` à partir des index affichés (ordre moteur
  = ordre inverse d'affichage — piège connu, commenter) + assert
  `nLayerState` dans le test 2.
- **Double `setBrush`** : rangée + `ToolPalette` écrivant en même
  temps = JSON écrasé. Atténuation : `BrushPrefs` hoisté unique
  (§5, pattern `ColorStore` Slice 2), un seul `pushBrush()` —
  interdiction de `setBrush` depuis la rangée autrement que via le
  store.
- **i18n** : `Écrêté au-dessous`, `Échantillonné`-style longs en FR —
  `maxLines=1` + ellipsis (leçon Slice 0 §7) ; `%%` FR via
  ressources, jamais en dur.
