# T4 — Transfos + filtres créatifs (SPEC UNIQUEMENT, aucune modif de code)

> **Statut : spec de cadrage, zéro code.** Tranche T4 du plan
> `docs/parity-gap.md` §T4 (5 items) : perspective/skew/distorsion +
> marionnette ; pack créatif (lueur, ombre portée, cristallisation,
> carte de normales, rétro, crayon, Perlin) ; flou d'objectif +
> distorsion fisheye-filtre + panoramas ; peinture sur modèle 3D +
> primitives + placement décor ; forme intelligente (redressement de
> tracé).
> Règles projet (AGENTS.md) : vocabulaire maison, FR/EN ressources,
> clean-room (aucune string/asset tiers), **JNI gelé — toute extension
> moteur est documentée en `ARCHITECTURE.md` avant le code**,
> `engine/include/openclip/` seul contrat public, `make test` vert,
> gate locale `emutest.sh`.
> Ordre de lecture : cette spec → `docs/parity-gap.md` §T4 →
> `ARCHITECTURE.md` (V+2/T1/T2, à étendre en §T4 avant code).

État de départ vérifié sur master : transfos sélection V+2
(`nSetSelectionMask` + `nSelectionBlit` modes `move|free|mesh`,
bilinéaire, snapshots tuiles pleines, 1 pas) ; registre de filtres M3
+ T1 (`unsharp`, `colorBalance`, `threshold`) + V+3 (`denoise`), règle
M3 (clés inconnues ignorées, manquantes = défauts, alpha jamais
touché, rgb ≤ alpha, `false` + intact sur erreur) ; tons M10
(`nApplyTone`) ; référence 3D M9 (overlay `nLoadGlbReference` +
`nSetRefView` + `nSetRefVisible`, caméra yaw/pitch/roll/dist/fov/
fisheye) + pose V+3 (`nRefJoints` rigide-par-nœud, `nSetRefPose`) ;
formes T1 (`nStampShape` rect/ellipse/polygone, 4×4 supersampling) ;
lissage V+3 (stabilize + RDP `simplify`) ; grille V+3 (`grid` 8/16/32,
`snapPaths`) ; GIF/WebM/APNG/séquences T3.

Conventions T4 (valent pour les 5 items) :
- **Contrat alpha M3** : tout filtre/transfo reste prémultiplié-safe
  (rgb ≤ alpha). Seules les écritures pixels (commits peinture,
  textures 3D §4b) touchent l'alpha **par design**.
- **Undo** : écriture pixels = snapshots tuiles pleine couche
  (pattern filtre M3, 1 pas) ; fiches (pins marionnette non validées,
  scène 3D, réglages de filtre) = méta hors undo (comme holds/bulles/
  clés — glisser n'est pas un trait) ; validation d'une transfo =
  1 pas.
- **Fiches opaques** : le moteur stocke et rejoue, ne juge jamais le
  contenu (pattern M8/V+1). Règle M3 sur tout JSON.
- **Overlay-only** : poignées, grilles de déformation, prévisualisations
  live, trajectoires = `Canvas` Compose par-dessus `EngineSurface`,
  jamais dans les pixels moteur (même règle que fourmis/onion).
- **Coordonnées** : tout vit en pixels moteur (`screenToEngine()`,
  rotation-aware).
- **Rendu propre** : aides hors `nFrameCompositeInto`/exports **sauf**
  §4b (texture peinte = sortie, incluse partout).
- **Seuils tactiles** : cibles ≥ 48dp ; sliders + champs numériques ;
  gestes stylet-first.
- **Strings** : clés FR + EN dès la spec, vocabulaire maison
  (perspective, inclinaison, distorsion, marionnette, lueur, ombre
  portée, cristallisation, carte de normales, rétro, crayon, nuage,
  objectif, fisheye, panorama, décor, forme intelligente) — aucun
  terme tiers.
- **Budget JNI T4 : EXACTEMENT 3 symboles neufs**
  (`nPuppetWarp`, `nSetRefScene`, `nPaintRefMap`). Tout le reste =
  croissance JSON sur symboles existants ou registre `nApplyFilter`.
  Un générique `nT4Op(handle, json)` a été envisagé et REFUSÉ
  (même clause que T1 §8 / T2 / T3 : noms explicites, lisibilité de
  `check_jni_symbols.sh` et des tests par op).

Ordre de livraison : §2 → §3 → §1 → §5 → §4 (créatif d'abord : valeur
visible, 0 JNI, débloque les tests de registre ; 3D en dernier : L,
dépend d'un modèle libre).

---

## 1. Perspective / inclinaison / distorsion + marionnette

### 1.1 Périmètre
- **Livre** (comblement 6.14 PARTIEL + 6.16 ABSENT) : trois modes
  géométriques sur le contenu **sélectionné** du calque raster actif
  (même périmètre que `nSelectionBlit` V+2 : pixels masqués lus,
  effacés en place, rééchantillonnés, recomposés source-over),
  plus une déformation **marionnette** à épingles sur calque entier.
- Modes sélection (croissance JSON de `nSelectionBlit`, 0 JNI) :
  - `persp` : remapping quadrilatère — les 4 coins de la boîte du
    masque sont tirés vers 4 points libres (`quad:[[x,y]×4]`,
    ordre horaire depuis haut-gauche). Couvre le redressement de
    perspective documentaire et les effets de plan. Homographie
    inverse + bilinéaire, from-scratch.
  - `skew` (inclinaison) : cisaillement `{ax,ay}` en degrés
    (±60 clampés) autour du centre de la boîte du masque, bilinéaire.
    Le cas `ax=0|ay=0` = inclinaison simple.
  - `bulge` (distorsion) : gonflement/pincement radial `{amount}`
    -1..1 (négatif = pince) + `{radius}` 0.1..1 (fraction de la
    demi-diagonale de la boîte), centré boîte, bilinéaire.
    Fonction de chute lisse (cosinus) — pas de tourbillon ici
    (le tourbillon onduleux va au §3 registre, filtre tuilé).
  - `move|free|mesh` V+2 inchangés (non-régression verrouillée).
- Marionnette (1 symbole neuf) : épingles `{x,y}` + déplacements
  `{dx,dy}` sur le calque raster actif **entier** (pas de masque
  requis) ; déformation rigide-par-voisinage (moindres carrés
  mobiles, variante rigide, from-scratch, même esprit que
  `guides.h`/`anim.h` : pur, sans `Canvas`) avec `{radius}` px
  d'influence (défaut = 25 % de la diagonale) et chute lisse au-delà.
  Cas limites honnêtes : 0 épingle = intact byte-identique ; 1 seule
  épingle = translation locale ; épingles hors canvas ignorées.
- **Hors périmètre** : marionnette sur calque vectoriel (les paths
  gardent le chemin T2 `nReplaceVectorPath`), magnétisme de maillage,
  interpolation temporelle (renvoi T3 : une marionnette validée par
  cellulo + clés T3 = animation image-par-image, sans interpo auto).

### 1.2 UX
- `TransformPanel` : segmented `move|free|mesh|persp|skew|bulge|
  puppet` (48dp) ; poignées overlay : 4 coins (persp), 2 flèches de
  cisaillement (skew), 1 poignée radiale (bulge), épingles
  ajout/déplacement/suppression (puppet, tap = ajoute, appui long =
  supprime). Aperçu live = overlay (jamais pixels) jusqu'à
  **Valider** (1 pas) / **Annuler**.
- Puppet = onglet séparé (calque entier, pas de masque) avec densité
  de maillage visualisée en overlay (grille déformée, pas de pixels).
- Strings : `warp_persp/skew/bulge/puppet`, `warp_validate/cancel`,
  `warp_pin_add/delete`, `warp_no_selection` — FR + EN.

### 1.3 Moteur vs app-side
- **Moteur** : noyau pur `openclip/warp.h` (persp/skew/bulge, mêmes
  signatures que `transform.h` : `blitWarp(dst, src, mask, spec)`) +
  `openclip/puppet.h` (`puppetWarp(dst, src, pins, radius)`).
  Rééchantillonnage bilinéaire, prémultiplié-safe, byte-identique à
  paramètres neutres (quad = boîte, ax=ay=0, amount=0, 0 pin).
- **App-side** : poignées, overlay grille, sérialisation JSON,
  garde « pas de masque » (refus avant appel, pattern V+1).

### 1.4 API JNI proposée (à documenter en ARCHITECTURE.md §T4)
- 0 symbole pour persp/skew/bulge : `nSelectionBlit` JSON grandit
  (`mode:"persp"|"skew"|"bulge"`, `quad`, `ax/ay`, `amount/radius` ;
  règle M3 ; `false` + intact si pas de masque / JSON malformé /
  quad dégénéré (aire nulle, points confondus)).
- `nPuppetWarp(handle, index, json): Boolean` — `{pins:[{x,y,dx,dy}
  ...], radius?}` sur le calque raster `index` (0..64 épingles ;
  hors-bornes = erreur `false` + intact). Snapshots tuiles pleines,
  1 pas (pattern filtre).
- Soit **1 symbole neuf** pour cet item.

### 1.5 Undoabilité / persistance
- Validation = 1 pas (pixels) ; réglages/épingles pré-validation =
  méta hors undo ; `Annuler` = rien écrit. Persistance : transfos
  validées = pixels (rien à persister) ; épingles non validées =
  session-only (fermer le doc les jette, annoncé).

### 1.6 Tests
- Engine : quad identité = byte-identique ; quad dégénéré = false +
  intact ; skew 0/0 = identique ; skew ±60 clampé ; bulge 0 =
  identique ; bulge ±1 borné (pas de crash bord) ; puppet 0 pin =
  identique ; 1 pin = déplacement local + lointain intact ; 65 pins
  = false ; non-raster = false. App (emu) : chaque mode valide en
  1 undo ; annuler ne touche rien ; persp redressée visible au
  composite.

### 1.7 Dépendances JNI
- 1 symbole (`nPuppetWarp`) + croissance JSON `nSelectionBlit`.
  `check_jni_symbols.sh` + CI mis à jour. Aucune dépendance externe.

---

## 2. Pack créatif (7 filtres)

### 2.1 Périmètre
- **Livre** (complements 9.7 PARTIEL, 9.8/9.9/9.12/9.13/9.14/9.16
  ABSENTS) : 7 noms neufs routés par `nApplyFilter` existant,
  **0 JNI**, règle M3 stricte (clés inconnues ignorées, manquantes =
  défauts, alpha jamais touché, rgb ≤ alpha, `false` + intact sur
  params invalides). `canApply`/`filterNames` grandissent d'exactement
  ces 7 noms.
- Registre (tous from-scratch, tuilés, déterministes) :
  - `glow` (lueur) `{"radius":8 (1..64), "amount":0.6 (0..2),
    "tint":"#FFFFFF"}` — seuille les hautes lumières (luma > 0.7),
    floute (gaussienne séparable existante), teinte, écrante
    (screen) sur la source. amount 0 = identique.
  - `dropShadow` (ombre portée) `{"dx":8, "dy":8 (-64..64),
    "radius":6 (0..64), "color":"#000000", "opacity":0.5 (0..1)}` —
    silhouette alpha du calque décalée, floutée, teintée, composited
    **derrière** la source (seul filtre du pack qui lit l'alpha —
    en lecture seule, jamais écrite). radius 0 = ombre nette.
  - `crystallize` (cristallisation) `{"cell":16 (3..64)}` —
    pavage hexagonal (pas carré : signature visuelle propre) ;
    chaque cellule = couleur moyenne de son centre. Déterministe.
  - `normalMap` (carte de normales) `{"strength":1.0 (0..4)}` —
    hauteur = luma Rec.601, Sobel 3×3 → normale RGB
    (`(n*0.5+0.5)*255`, B vers le haut). Surface unie = bleu
    `#8080FF` uniforme (invariant testé).
  - `retro` (rétro) `{"warmth":0.5 (0..1), "grain":10 (0..64),
    "vignette":0.3 (0..1), "fade":0.2 (0..1)}` — balance chaude
    (leve les noirs, tasse les blancs), grain LCG graine fixe
    (même générateur que `addNoise`, reproductible), vignettage
    radial lisse, fondu des noirs. Tous 0 = identique.
  - `pencil` (crayon) `{"strength":0.7 (0..1)}` — luma → contours
    Sobel inversés × grain de papier procédural (valeur Perlin §2b
    à échelle fine, graine fixe), sortie quasi-monochrome chaude.
  - `perlinClouds` (nuage Perlin) `{"scale":6 (1..16 octaves-bases),
    "octaves":4 (1..6), "seed":0, "mode":"mix"|"overwrite"
    (défaut mix)}` — bruit de Perlin 2D from-scratch (gradient +
    quintique, table de permutation mélangée par LCG de `seed`),
    fBm multi-octaves, `mix` = écran doux à 50 % sur source,
    `overwrite` = remplissage pleine couche (même pattern que
    `nApplyTone` M10). Bruit-blanc `addNoise` ≠ Perlin (cohérent
    spatialement — c'est la différence annoncée).
- **Hors périmètre** : gonflement 9.15 (couvert §1 `bulge`),
  distorsions onduleuses (couvertes §3), « artistique » générique
  9.7 (le pack = sa définition fermée et testée).

### 2.2 UX
- `FilterDialog` existant : section « Créatif » (7 entrées, aperçu
  temps réel sur vignette 96px, pattern existant), sliders + champs
  numériques, `Valider` = 1 undo. `retro`/`pencil` proposés aussi en
  « Effets rapides » (pattern 9.24).
- Strings : `filter_glow/shadow/crystal/normal/retro/pencil/clouds`
  + suffixes `_radius/_amount/...` — FR + EN.

### 2.3 Moteur vs app-side : **moteur (registre uniquement)**
- Noyau `filters.cpp` (mêmes helpers : gaussienne séparable, LCG,
  Bayer-8) ; app-side = dialog existant + vignette preview.
  Aucun symbole, aucune fiche.

### 2.4 API JNI proposée
- Aucune. `nApplyFilter` route les 7 noms ; params ci-dessus
  documentés en `filters.h` (table Defaults, pattern M3/T1).

### 2.5 Undoabilité / persistance
- 1 pas par application (snapshots tuiles, pattern M3) ; réglages =
  session-only (pas de presets persistés en T4 — FUTUR) ; résultat =
  pixels.

### 2.6 Tests
- Engine (par filtre) : params neutres = byte-identique ; params
  invalides = false + intact ; invariants : glow relève les
  hautes lumières ; dropShadow ne touche pas la source non
  décalée ; crystal cellule 64 sur mire = aplats ; normalMap uni =
  `#8080FF` ; perlin même seed = byte-identique, seed ≠ = différent,
  overwrite ignore la source. App (emu) : les 7 noms appliquent +
  undo restaure (pattern `applyFilterKnownPosterizeModifiesComposite`).

### 2.7 Dépendances JNI
- Zéro. Pur registre moteur.

---

## 3. Flou d'objectif + fisheye-filtre + panoramas

### 3.1 Périmètre
- **Livre** (complements 9.4 ABSENT, 9.17 PARTIEL, 9.18 ABSENT) :
  3 noms neufs au registre `nApplyFilter`, **0 JNI**, règle M3.
- Registre :
  - `lensBlur` (flou d'objectif) `{"radius":8 (1..64),
    "highlights":0.5 (0..1)}` — disque49 (bokeh polygonal
    approximé : noyau disque seuillé, pas gaussien — c'est la
    différence annoncée vs `gaussianBlur`) ; `highlights` protège
    les hautes lumières (écrantage partiel, pastilles de bokeh
    simulées par gain local). radius 1 ≈ identique (disque 1px).
  - `fisheyeFilter` (distorsion fisheye-filtre) `{"amount":0.5
    (-1..1), "keepSize":1}` — remap radial `r' = r*(1+k*r²)`
    (même formule que la caméra M9, k = amount×0.001 mis à
    l'échelle pixels — documenté) ; négatif = pincushion ;
    `keepSize` = recadre au canvas (bords transparents comblés
    par clamp-to-edge, annoncé) ; amount 0 = identique. Distinct
    de la caméra M9 (aide overlay) et de la règle fisheye 2.27 :
    ici pixels du calque, undoable.
  - `panoramaRemap` (panoramas) `{"mode":"cyl|rect" (défaut cyl),
    "fov":120 (60..180)}` — `cyl` : projection cylindrique
    (redresse les bandes larges, bords étirés clampés) ;
    `rect` : inverse (re-projette en rectilinéaire). Pas
    d'assemblage multi-images en T4 (stitch = FUTUR : alignement
    auto hors périmètre) — la spec le dit honnêtement : ce filtre
    prépare/corrige les bandes panoramiques, il ne les coud pas.
- **Hors périmètre** : profondeur pilotée par carte (pas de canal
  Z), spin dédié 9.2 (toujours PARTIEL après T4 — noté au §8),
  stitch auto.

### 3.2 UX
- `FilterDialog` : section « Objectif » (3 entrées) ; `fisheyeFilter`
  + `panoramaRemap` avec overlay de grille d'aperçu (lignes droites
  témoins) ; `panoramaRemap` accessible aussi depuis le panneau
  page (bandes webtoon T3).
- Strings : `filter_lens/fisheye/panorama` + params — FR + EN.

### 3.3 Moteur vs app-side : **moteur (registre uniquement)**
- `lensBlur` = noyau disque from-scratch ; `fisheyeFilter`/
  `panoramaRemap` = remaps inverses + bilinéaire (même code de
  rééchantillonnage que `warp.h` §1 — pattern partagé documenté,
  pas de duplication silencieuse).

### 3.4 API JNI proposée
- Aucune. 3 noms au registre, table Defaults en `filters.h`.

### 3.5 Undoabilité / persistance
- 1 pas par application (pattern M3) ; résultat = pixels.

### 3.6 Tests
- Engine : neutres = identique ; invalides = false + intact ;
  lensBlur disque ≠ gaussien (au moins 1px diffère sur marche
  d'escalier) ; fisheye +0.5 bombe le centre (mire grille) ;
  cyl→rect→cyl ≈ identité à ±2 LSB (round-trip borné, honnête).
  App (emu) : applique + undo par filtre.

### 3.7 Dépendances JNI
- Zéro. Pur registre moteur.

---

## 4. Peinture sur modèle 3D + primitives + placement décor

### 4.1 Périmètre
- **Livre** (comblements 11.9/11.11/11.12/11.15 ABSENTS,
  11.10/11.13 PARTIELS) en **deux paliers**, overlay-first :
  - **Palier (a) — décor** : scène de références multi-instances.
    Chaque item = source (`chemin .glb` déjà importé OU primitive
    procédurale `cube|sphère|plan|cylindre` générée from-scratch
    en maillage triangle-arêtes, sans asset) + transform
    `{pos,rotY|rotXYZ,scale,visible}`. Rendu = wireframe overlay
    (même contrat M9 : `nCompositeInto` seul, jamais vignettes/
    exports). C'est le « choix mise en page/angles depuis une
    liste » (11.10) et le « placer objets dans décor » (11.12).
  - **Palier (b) — peinture** : les traits peignent une **texture**
    2D (1024², session) projetée sur l'item actif : modèles avec
    UV = peints via UV ; modèles sans UV (dont primitives) =
    projection planaire documentée (vue courante au moment du
    trait). La texture rend en overlay teinté sous le wireframe
    (toujours aide, jamais pixels du calque — le commit vers le
    calque reste un `nBlitPngIntoLayer` explicite, pattern V+1).
- **Hors périmètre** : rendu réaliste 11.21 (wireframe seul),
  matériaux 11.17, physique 11.27, rigging (renvoi V+3 pose),
  import 3D→lignes auto 11.23/11.24 (toujours manuel).

### 4.2 UX
- `ReferencePanel` grandit : liste d'items (ajouter .glb / primitive
  / supprimer / visibilité), sliders par item (pos/rot/scale),
  sélecteur d'item actif, mode Peinture (brosse peignant la texture,
  curseur 3D projeté en overlay), bouton **Reporter sur calque**
  (commit explicite, 1 undo, annoncé).
- Strings : `ref_scene_add/remove/primitive`, `ref_item_pose`,
  `ref_paint/commit_layer`, `ref_no_uv_planar` (bannière projection
  planaire) — FR + EN.

### 4.3 Moteur vs app-side
- **Moteur** : registre de scène (`openclip/refscene.h`, pur) +
  rasterisation wireframe multi-items (même pipeline M9) + map de
  texture par item (surface 1024² + drapeau UV/planar) ; palier (b) =
  projection trait→texel (inverse caméra + interpolation UV/planar).
- **App-side** : liste, sliders, import SAF (pattern M9), bannière
  planaire, commit via pont existant.

### 4.4 API JNI proposée (à documenter en ARCHITECTURE.md §T4)
- `nSetRefScene(handle, json): Boolean` — pose la scène
  `{items:[{src, pos:[x,y,z], rot:[x,y,z], scale, visible}...]}`
  (0..8 items, src = `file:<cache-path>` | `prim:cube|sphere|
  plane|cylinder` ; règle M3 ; faux + scène intacte si JSON
  malformé ou > 8 items). Remplace l'item unique M9 (migration =
  scène à 1 item, byte-identique).
- `nPaintRefMap(handle, item, jsonDabs): Boolean` — timbres
  `{x,y,r,color,opacity}[]` (coords moteur projetées app-side) dans
  la texture de l'item (crée la map 1024² au premier trait ;
  planaire si pas d'UV). Méta-texture hors undo (comme un trait en
  cours) ; seul le commit calque est undoable.
- **2 symboles neufs** pour cet item (les seuls usages 3D de T4).

### 4.5 Undoabilité / persistance
- Scène = fiche session hors undo (comme pose V+3) ; texture =
  session hors undo ; commit calque = 1 pas. Persistance app-side :
  `decor.json` (items + transforms) + `tex_itemNN.png` (textures
  peintes) dans le dossier page `.ocpages/` (pattern `pose.json`
  V+3) ; sans eux = scène vide, jamais de page cassée.

### 4.6 Tests
- Engine : scène vide = composite inchangé ; primitive cube =
  arêtes projetées (> 0 px non blancs) ; item invisible = absent ;
  9 items = false ; paint sans UV = texels touchés + overlay
  teinté ; commit = pixels calque + 1 undo. App (emu) : liste
  ajoute/retire, sliders bougent la projection, peinture puis
  commit puis undo.

### 4.7 Dépendances JNI
- 2 symboles (`nSetRefScene`, `nPaintRefMap`). cgltf déjà vendu
  (aucun tiers neuf) ; primitives = from-scratch (pas de spec
  externe à logguer).

---

## 5. Forme intelligente (redressement de tracé)

### 5.1 Périmètre
- **Livre** (comblement 11.1 ABSENT) : à la fin d'un trait (stylusé
  ou doigt), le moteur de reconnaissance **app-side** classe la
  forme (`segment|ellipse|cercle|rect|triangle`, seuil de confiance
  0.7, sinon trait gardé tel quel — jamais de remplacement
  surprise) et propose le remplacement en overlay fantôme ;
  **l'utilisateur valide** (tap) ou continue (le fantôme s'efface
  au trait suivant). Zéro automatisme destructeur.
- Reconnaissance : fonction pure Kotlin from-scratch (fermeture
  du tracé, circularité isopérimétrique, RDP + comptage de côtés
  pour triangle/rect, ajustement ellipse par moments d'ordre 2 —
  algorithmes classiques documentés, aucune lib).
- Remplacement : segment/ellipse/cercle → `nAppendVectorPath` T2
  (reste éditable !) ; rect/triangle → `nStampShape` T1 (pixels,
  1 pas). Cercle parfait = ellipse contrainte (snap astreinte,
  annoncé).

### 5.2 UX
- Toggle `Forme intelligente` dans `PinnedBrushBar` (défaut OFF —
  les puristes ne sont jamais surpris) ; fantôme pointillé +
  pastille `Valider` (48dp) ; slider Tolérance (0.5..0.95).
  Mode auto-Remplace en option (défaut OFF, annoncé).
- Strings : `shape_smart_toggle/confirm/tolerance/auto`,
  `shape_kept_stroke` (confiance basse) — FR + EN.

### 5.3 Moteur vs app-side : **app-side (0 JNI nominal)**
- Reconnaissance + fantôme 100 % Kotlin (fonction pure, tests JVM) ;
  commits via symboles T1/T2 existants. **Conditionnel** (pattern
  T1 §5) : si la mesure prototype sur tablette cible (> 300 ms pour
  2048 pts) l'exige, un `nFitShape` moteur est documenté en
  ARCHITECTURE.md **avant** code — chemin nominal = pas de symbole.

### 5.4 API JNI proposée
- Aucune (chemin nominal). Réutilise `nStampShape` (T1) +
  `nAppendVectorPath`/`nReplaceVectorPath` (M6/T2).

### 5.5 Undoabilité / persistance
- Validation = 1 pas (pixels T1 ou path T2) ; fantôme = overlay
  hors undo ; toggle/tolérance = prefs app-side persistées
  (workspaces JSON V+3). Rien dans l'ORA (formes validées = paths
  vectoriels T2 ou pixels, déjà couverts).

### 5.6 Tests
- JVM : carré tordu → `rect` ≥ 0.7 ; gribouillis → rejet ; cercle →
  `cercle` + axes ±10 % ; 2048 pts < 300 ms (borne prototype).
  Emu : fantôme affiché, valider = 1 undo, continuer = trait
  original intact.

### 5.7 Dépendances JNI
- Zéro (nominal). Le conditionnel `nFitShape` reste documenté-ici-
  d'abord s'il devient nécessaire.

---

## Récapitulatif JNI + risques

| Item | Symboles neufs | Registre/JSON |
|---|---|---|
| §1 transfos + marionnette | `nPuppetWarp` (1) | `nSelectionBlit` +3 modes |
| §2 pack créatif (7) | 0 | `nApplyFilter` +7 noms |
| §3 objectif/fisheye/panorama (3) | 0 | `nApplyFilter` +3 noms |
| §4 décor + peinture 3D | `nSetRefScene`, `nPaintRefMap` (2) | — |
| §5 forme intelligente | 0 (nominal) | réutilise T1/T2 |
| **Total T4** | **EXACTEMENT 3** | +10 noms de filtres, +3 modes blit |

**Décisions** : (1) persp/skew/bulge en croissance `nSelectionBlit`
plutôt qu'un symbole transfo générique — le périmètre sélection du
V+2 est conservé, 0 symbole ; (2) marionnette calque-entier séparée
(le masque V+2 ne porte pas d'épingles) ; (3) pas de stitch panorama
— filtre seul, honnête ; (4) peinture 3D = texture overlay + commit
explicite, jamais d'écriture directe dans les pixels du calque ;
(5) forme intelligente app-side avec validation utilisateur, défaut
OFF ; (6) budget 3 symboles, pas de générique (clause T1/T2/T3).

**Risques** : (a) homographie persp sur tuiles 0x100 — coutures aux
bords de tuiles si l'interpolation ignore le voisinage : le noyau
doit échantillonner la surface source, pas la tuile (test couture
dédié) ; (b) puppet MLS sur grand calque = O(pins × pixels) : cap
64 épingles + rayon d'influence + mesure prototype tablette, sinon
dégradation (baisse de résolution de la grille) documentée, pas
silencieuse ; (c) `normalMap` sur contenu prémultiplié — lire le
straight (déprémultiplié), sinon les bords semi-transparents
faussent le Sobel ; (d) `dropShadow` est le seul filtre lisant
l'alpha — revue M3 explicite (lecture seule verrouillée par test) ;
(e) primitives sans UV + peinture planaire = surprise si la vue
change après peinture : bannière `ref_no_uv_planar` + la texture
reste valide (projection figée au trait, documenté) ; (f) 9.2 spin
reste PARTIEL après T4 — assumé, noté FUTUR si demandé.
