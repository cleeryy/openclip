# T3 — Animation productive (SPEC UNIQUEMENT, aucune modif de code)

> **Statut : spec de cadrage, zéro code.** Tranche T3 du plan
> `docs/parity-gap.md` §T3 (4 items) : images clés + interpolation +
> courbes ; mouvements caméra ; multi-pistes + étiquettes + feuille
> d'exposition ; séquences PNG + stickers APNG + export audio seul.
> Règles projet (AGENTS.md) : vocabulaire maison, FR/EN ressources,
> clean-room (aucune string/asset tiers), **JNI gelé — toute extension
> moteur est documentée en `ARCHITECTURE.md` avant le code**,
> `engine/include/openclip/` seul contrat public, `make test` vert,
> gate locale `emutest.sh`.
> Ordre de lecture : cette spec → `docs/parity-gap.md` §T3 →
> `ARCHITECTURE.md` (M7/M7b/V+2, à étendre en §T3 avant code).

État de départ vérifié sur master : trames = copies `Canvas` pleines
(M7 : `nAddFrame`/`nDuplicateFrame`/`nDeleteFrame`/`nSetFrame`/
`nFrameCount`/`nFrameCompositeInto`) ; rythme = holds V+2
(`nSetCelHold` 1..32, méta hors undo, GCE `round(hold*100/fps)`) +
réordonnancement (`nMoveCel`, LIFO exact contre les ops pixels) ;
pelure (`nSetOnionCount` 0..4/côté, teintes d'affichage app-side) ;
lecture = timer app-side (fps 1..60, défaut 12, pas de boucle moteur) ;
GIF animé moteur (`nExportGif`, median-cut 256 + LZW + Bayer-8
from-scratch, boucle 0/-1/N) ; WebM app-side (MediaCodec VP8 +
MediaMuxer, holds rejoués à l'export) ; audio = piste unique
session-only (`ClipAudio`, SAF → cache, jamais dans l'ORA, mux WebM).
Base réelle : engine **116** tests, émulateur **202** `@Test` (T2
committée).

Conventions T3 (valent pour les 4 items) :
- **Contrat alpha M3** : toute interpolation/peinture reste
  prémultipliée-safe (rgb ≤ alpha). Seules les écritures pixels
  (séquences, APNG, commits) touchent l'alpha **par design**.
- **Undo** : écriture pixels = snapshots tuiles pleine couche
  (pattern filtre M3, 1 pas) ; ops vectorielles = snapshot
  `VectorHistory` (pattern M6/T2, 1 pas) ; **fiches d'animation
  (clés, caméra, pistes, étiquettes) = méta hors undo** (comme les
  holds V+2 et la fiche bulle T2 — glisser une clé n'est pas un
  trait). Per-frame M7 (changer de trame = autre pile).
- **Fiches opaques** : le moteur stocke et rejoue les clés, ne juge
  jamais le contenu (pattern M8/V+1). Règle M3 : clés inconnues
  ignorées, manquantes = défauts documentés.
- **Overlay-only** : courbes, trajectoires de clés, pastilles
  caméra, feuille d'expo = `Canvas` Compose par-dessus
  `EngineSurface`, jamais dans les pixels moteur (même règle que
  fourmis/curseur/pipette/onion).
- **Coordonnées** : tout vit en pixels moteur (`screenToEngine()`,
  rotation-aware) ; overlay fait l'inverse via la matrice de vue.
- **Rendu propre** : aides (pelure, repère, trajectoires, feuille)
  hors `nFrameCompositeInto`/exports **sauf** caméra §2, qui est une
  sortie (appliquée partout, vignettes comprises).
- **Seuils tactiles** : cibles ≥ 48dp ; sliders + champs
  numériques ; gestes stylet-first.
- **Strings** : clés FR + EN dès la spec, vocabulaire maison
  (image clé, intervalle, courbe, caméra, piste, étiquette, feuille
  d'exposition, séquence, sticker) — aucun terme tiers.

---

## 1. Images clés + interpolation + courbes d'animation

### 1.1 Périmètre
- **Livre** (comblement 13.2 ABSENT + 13.3 ABSENT) : chaque calque
  (raster et vectoriel) porte une **piste de clés** : à un index de
  cellulo donné, une clé fige `{dx,dy,scale,angle,opacity}` ; entre
  deux clés, le moteur interpole à la lecture et au composite.
  **Courbes** : une aisance par segment (`linear|ease-in|ease-out|
  smooth|step`) + éditeur de courbe maison (Bézier 2 poignées,
  fonction pure Kotlin, 0 JNI).
- Propriétés interpolées (fermé, documenté) : translation px
  (`dx,dy`), échelle (`scale` 0.1..8, autour du centre du calque),
  rotation (`angle` degrés, autour du centre), opacité (`opacity`
  0..1, multiplie l'opacité du calque). **Hors périmètre** :
  interpolation de pixels (morphing), clés sur masques/ajustements/
  fiches texte, cinématique inverse (le mannequin V+3 reste
  pose-par-pose).
- Sémantique : une seule clé = tenue (`step` implicite, pas
  d'extrapolation) ; hors segment = clé la plus proche ; calque
  sans clé = rendu actuel byte-identique (verrou de non-régression).

### 1.2 UX
- `TimelineStrip` + `LayerPanel` : badge `◆` par calque cléé ;
  tap clé = saut de cellulo ; appui long = menu (ajouter ici,
  supprimer, copier vers…) ; panneau Courbe (segmenté 48dp :
  5 aisances + éditeur Bézier avec prévisualisation de la
  trajectoire en overlay pointillé).
- Aperçu : trajectoire du centre du calque dessinée en overlay
  (jamais pixels) ; lecture rejoue l'interpolation en direct.
- Strings : `anim_key_add/delete/goto`, `anim_ease_linear/
  in/out/smooth/step`, `anim_curve_edit`, `anim_no_keys` — FR + EN.

### 1.3 Moteur vs app-side : **moteur (noyau pur + composite)**
- Noyau `anim.h` from-scratch (même esprit que `cels.h` : pur,
  sans `Canvas`, testé en `make test`) :
  ```cpp
  struct LayerKeys { float dx=0, dy=0, scale=1, angle=0, opacity=1; };
  // Clé : {cel:int, keys:LayerKeys, ease:Ease} ; segments triés par cel.
  // sample(track, celFloat) -> LayerKeys interpolés (bilerp/slerp
  // courts sur angle, lerp sur le reste ; ease appliquée au t).
  ```
  Le composite applique l'échantillon au moment de peindre le
  calque (même point d'application que l'opacité/visibilité :
  translate/rotate/scale = rééchantillonnage bilinéaire de la
  surface du calque, opacité = facteur sur l'alpha de sortie).
- App-side : éditeur de courbe (Bézier → table 64 pas, fonction
  pure testée JVM), overlay trajectoire via `nLayerAnim` (lecture
  seule), transport existant (les holds V+2 rythment toujours la
  lecture : `celDelayCs` inchangé).

### 1.4 API JNI proposée (à documenter en ARCHITECTURE.md §T3)
- `nSetLayerAnim(handle, index, json|null): Boolean` — pose la
  piste du calque (`{keys:[{cel,dx,dy,scale,angle,opacity,
  ease}...]}` ; null/vide = efface, calque normal). Règle M3 ;
  `false` + intact si index mauvais ou json inparsable (clés
  hors-bornes = clampées, jamais d'erreur).
- `nLayerAnim(handle, index): String` — lecture seule
  (`{"keys":[...]}` ; `{}` si aucune ; chaîne vide si mauvais
  index — pattern `nVectorPaths` V+2).
- 2 symboles, `check_jni_symbols.sh` + CI mis à jour.

### 1.5 Undoabilité / persistance
- Undo : **méta hors undo** (comme holds/bulle — annoncée dans
  l'aide) ; les pixels interpolés ne sont jamais commis (rendu
  au composite-time, rien à annuler).
- Persistance : sidecar `anim.json` par document (voir §3.5 :
  clés + caméra + pistes + étiquettes voyagent ensemble) ;
  `nLayerState` gagne `"anim":N` (compte de clés, badge `◆`).
  ORA inchangé (pixels seuls, lecteurs tiers non impactés).

### 1.6 Tests à ajouter
- Engine (+6) : `animSampleMidpoint` (2 clés, cel médian =
  moitié ±ε) ; `animEaseStepHolds` (`step` = clé gauche pure) ;
  `animSingleKeyHolds` (1 clé = tenue partout) ;
  `animEmptyLayerIdentical` (sans clé = composite byte-identique) ;
  `animBadJsonNoOp` ; `animOpacityMultiplies` (clé opacity 0.5 ≈
  moitié d'alpha).
- Instrumentés (+4) : `keyAddMovesRendering` (clé posée →
  composite change, undo moteur vide) ; `keyDeleteRestores` ;
  `curveEaseChangesMidframe` (`smooth` ≠ `linear` au cel médian) ;
  `animSurvivesReload` (save/reload : compte de clés identique).

### 1.7 Dépendances JNI
- **2 symboles** (§1.4) + §T3 avant code + `anim.h`/`canvas.cpp`
  (application au composite) + tests. 0 lib externe.

---

## 2. Mouvements caméra (pan/zoom keyframés)

### 2.1 Périmètre
- **Livre** (comblement 13.1 ABSENT) : une **piste caméra
  session** (une par clip, pas par calque) : clés `{cel, x, y,
  zoom, angle?}` ; interpolation = même noyau §1 (lerp + aisance
  par segment). `zoom` 0.25..8 (défaut 1), `x,y` = centre regardé
  en pixels moteur, `angle` optionnel (défaut 0, même bornes que
  `nSetRefView` ±360).
- Sémantique de sortie : la caméra **s'applique partout**
  (`nCompositeInto` + `nFrameCompositeInto` + GIF §V+2 + WebM M7b
  + séquences/APNG §4) — c'est le cadre du film, pas une aide.
  Sans clé = vue actuelle byte-identique. Gestes pinch/pan/rotate
  existants déplacent la vue **manuelle** ; la caméra animée la
  remplace pendant la lecture/export (toggle `caméra animée
  ON/OFF`, défaut OFF — jamais de surprise au dessin).

### 2.2 UX
- Panneau Caméra (rideau `TimelineStrip`) : toggle ON/OFF,
  `+ clé ici`, steppers x/y/zoom, trajectoire du regard en
  overlay (ligne + pastilles par clé, suit la vue) ; pendant la
  lecture avec caméra ON, la vue suit les clés (les gestes
  coupent et repassent en manuel, annoncé).
- Strings : `camera_track_on/off`, `camera_key_add`,
  `camera_reset_view`, `camera_manual_cut` — FR + EN.

### 2.3 Moteur vs app-side : **moteur (piste session) + app**
- Moteur : piste stockée sur la session (pas sur un calque) ;
  `nCompositeInto`/`nFrameCompositeInto` cadrent le composite
  final (crop + bilinéaire + remplissage fond hors champ =
  couleur de fond du canvas, comme l'export WebM actuel).
- App-side : éditeur (mêmes widgets que §1, courbe partagée),
  overlay trajectoire, toggle ON/OFF (drapeau app-side : OFF =
  l'app demande le composite sans caméra).

### 2.4 API JNI proposée (à documenter en ARCHITECTURE.md §T3)
- `nSetCameraTrack(handle, json|null): Boolean` — `{keys:[{cel,
  x,y,zoom,angle?,ease}...]}` ; null/vide = efface.
- `nCameraTrack(handle): String` — lecture seule (`{"keys":[...]}`).
- `nSetCameraEnabled(handle, on): Boolean` — ON = cadré, OFF =
  vue manuelle (défaut OFF).
- 3 symboles. Rejet de la variante « caméra par calque » :
  une seule caméra-spectateur (revue T1 §8 : noms explicites).

### 2.5 Undoabilité / persistance
- Undo : **méta hors undo** (comme §1, annoncé).
- Persistance : même sidecar `anim.json` (§3.5) ; `nLayerState`
  inchangé (caméra = session, pas calque).

### 2.6 Tests à ajouter
- Engine (+5) : `cameraZoomCrops` (zoom 2 ≈ quart central
  agrandi ±1px) ; `cameraPanShifts` ; `cameraDisabledIdentical`
  (OFF = byte-identique) ; `cameraSingleKeyHolds` ;
  `cameraBadJsonNoOp`.
- Instrumentés (+3) : `cameraKeyFramesDiffer` (2 clés pan :
  vignettes 0 et N diffèrent, médian entre les deux) ;
  `cameraToggleRestores` (OFF = vue manuelle) ;
  `cameraSurvivesReload`.

### 2.7 Dépendances JNI
- **3 symboles** (§2.4) + §T3 avant code + `anim.h` (réutilisé)
  + cadrage composite. 0 lib externe.

---

## 3. Multi-pistes + étiquettes + feuille d'exposition

### 3.1 Périmètre
- **Livre** (comblement 13.7 PARTIEL + 13.12 ABSENT) en deux
  paliers :
  - (a) **Étiquettes + feuille d'exposition, 0 JNI** : marqueurs
    nommés sur la règle temps (`{cel, label, couleur}`) +
    **feuille d'exposition** = table lecture seule
    cellulo × piste dérivée des données live (holds, clés §1,
    caméra §2, audio) + export TXT/CSV via SAF.
  - (b) **Multi-pistes** : jusqu'à **4 pistes** parallèles ;
    chaque piste possède sa propre liste de cellulos (copies
    `Canvas` M7, même coût documenté) ; le composite = empilement
    source-over des cellulos courantes des pistes **visibles**
    (piste muette = sautée, comme un calque masqué). La piste 0
    est le clip M7 actuel (migration transparente : 1 piste =
    comportement byte-identique).
- **Hors périmètre** : pistes audio multiples (1 piste audio
  M7b conservée), linking/imbrication de pistes, time-remapping
  par piste (les holds restent par cellulo).

### 3.2 UX
- `TimelineStrip` : sélecteur de piste (segmenté `P1..P4`,
  48dp) + œil par piste + renommer (dialogue, 24 car. max) ;
  règle d'étiquettes au-dessus des vignettes (pastille couleur
  + tap = saut, appui long = éditer/supprimer) ; bouton
  `Feuille` = table plein-écran (lignes = cellulos avec holds
  `×N`, badges `◆`/`◉`, nom d'étiquette ; colonnes = pistes) +
  `Exporter CSV`.
- Opérations : `+ piste` (vide), `dupliquer la piste`
  (copie de la cellulo courante seule — pas tout le clip,
  annoncé), `supprimer` (garde : ≥ 1 piste, comme `cel-delete`).
- Strings : `track_add/duplicate/delete/rename/mute`,
  `label_add/edit/delete`, `exposure_sheet`, `exposure_export` —
  FR + EN.

### 3.3 Moteur vs app-side : **les deux**
- (a) app-side pur : étiquettes = `DocRegistry` session
  (+ sidecar §3.5) ; feuille = vue dérivée (aucun pixel neuf,
  aucun JNI).
- (b) moteur : pistes = N listes de trames dans la session ;
  les symboles trame M7/V+2 existants (`nAddFrame`,
  `nDuplicateFrame`, `nDeleteFrame`, `nSetFrame`, `nMoveCel`,
  `nSetCelHold`, `nFrameCompositeInto`, `nLoadDocument`…)
  **travaillent sur la piste active** (rétrocompatible : piste 0
  = sémantique actuelle) ; composite multi-pistes dans
  `nFrameCompositeInto`/`nCompositeInto`/exports.

### 3.4 API JNI proposée (à documenter en ARCHITECTURE.md §T3)
- `nTrackCount(handle): Int`, `nAddTrack(handle, name): Int`
  (0..3 existantes → index, 4 = refus), `nSetActiveTrack
  (handle, i): Boolean`, `nActiveTrack(handle): Int`,
  `nSetTrackVisible(handle, i, on): Boolean`,
  `nSetTrackName(handle, i, name): Boolean` (24 car. max,
  tronqué), `nDeleteTrack(handle, i): Boolean` (≥ 1 piste
  conservée, false sinon).
- Lecture : `nTrackState(handle): String` =
  `{"active":0,"tracks":[{"name", "frames":N, "visible":bool}]}`.
- 8 symboles (palier b seul ; palier a = 0 JNI et peut partir
  avant).

### 3.5 Undoabilité / persistance
- Undo : ops structurelles (add/dup/del piste, renommer,
  étiquettes) = **méta hors undo** (même règle que §1–§2) ;
  pixels par piste = piles M7 existantes (changer de piste =
  autre pile, comme changer de trame).
- Persistance : sidecar **`anim.json` v1** à côté du document
  (ORA multipage `.ocpages/` ou ORA simple) :
  `{"tracks":[{name,visible}],"labels":[{cel,txt,color}],
  "keys":{layerIdx:nSetLayerAnim-json},"camera":{...}}`
  (clés caméra §2 + clés calques §1 incluses — un seul fichier,
  une seule version). Absent/illisible = 1 piste + pas
  d'étiquettes (dégradé propre, jamais de clip cassé). Cap :
  64 Ko (au-delà = ignoré + bandeau, pattern `.clip` palier b).

### 3.6 Tests à ajouter
- Engine (+6) : `tracksDefaultOne` (session neuve = 1 piste) ;
  `trackAddCapFour` (5e = refus) ; `trackDeleteKeepsOne` ;
  `trackHiddenSkipped` (piste muette absente du composite) ;
  `trackFramesIsolated` (stroke piste 1 invisible piste 0) ;
  `trackFrameOpsScopeActive` (add sur piste active seule).
- Instrumentés (+5) : `trackAddSwitchStroke` ; `trackMuteHides`
  ; `labelJumpAndRoundTrip` (étiquette + reload identique) ;
  `exposureSheetShowsHolds` (probe `exposureForTest()` : holds
  + badges) ; `exposureCsvExports` (SAF cache : en-tête + N
  lignes).

### 3.7 Dépendances JNI
- **8 symboles** (palier b) + §T3 avant code + `document.h`
  (listes par piste) + sidecar app-side. 0 lib externe.
  Palier (a) livrable sans attendre (b).

---

## 4. Séquences PNG + stickers APNG (V+2-bis) + export audio seul

### 4.1 Périmètre
- **Livre** (comblement 13.10 PARTIEL + 13.11 PARTIEL) :
  (a) **séquence PNG** : un PNG pleine résolution par cellulo
  (holds **éclatés** : une image par tick de lecture, honnête
  avec le rythme — option `éclater les tenues ON/OFF`) livrée
  en **ZIP** (SAF `CreateDocument("application/zip")`,
  `frame_%04d.png`) ; (b) **APNG** (V+2-bis différé, livré ici) :
  encodeur from-scratch (`acTL`/`fcTL`/`fdAT`, délais =
  `celDelayCs` V+2, `zlib` via miniz **déjà vendored**,
  pas de lib neuve) ; (c) **stickers** : preset d'export
  (`512px côté max`, boucle infinie, fond **opaque auto** —
  stickers = pas d'alpha sorpresa, annoncé) en APNG ou WebM
  existant ; (d) **export audio seul** : copie du cache audio
  vers SAF (`audio/*`, nom + extension d'origine) — 0 moteur.
- **Hors périmètre** : export MP4 (brevets — WebM reste le
  film libre), pistes audio multiples, ré-encodage audio
  (copie byte-identique, jamais de transcodage silencieux).

### 4.2 UX
- Panneau Export (existant M7b/GIF V+2) + 3 boutons : `Séquence
  PNG (ZIP)`, `APNG`, `Sticker 512` (segmenté APNG/WebM) +
  `Audio seul` (visible seulement si piste attachée) ; chaque
  export = nommage `clip_0001.png…`, progression + `export_failed`
  honnête (jamais de crash si codec absent — règle M7b).
- Holds : toggle `éclater les tenues` (défaut ON pour ZIP,
  OFF sans objet pour APNG/GIF/WebM qui portent leurs délais).
- Strings : `export_pngseq`, `export_apng`, `export_sticker`,
  `export_audio_only`, `export_hold_expand`, `export_no_audio` —
  FR + EN.

### 4.3 Moteur vs app-side : **moteur (APNG + séquence) + app**
- Moteur : `apng.h` from-scratch (même pattern que `gif.h` :
  quantize réutilisé si RGBA, sinon RGB direct + `fdAT` par
  trame, `acTL` boucle 0/-1/N comme GIF) ; séquence = boucle
  composite interne (comme `nExportGif`, jamais de pull
  app-side par trame) écrivant dans un ZIP via miniz
  (déjà vendored, loggé `third_party/README.md`).
- App-side : SAF + progression + presets sticker (downscale
  512 + boucle infinie + fond opaque) + audio seul (copie
  `File`, 0 JNI) + holds éclatés (expansion `holds[i]` avant
  l'appel, ou param JSON).

### 4.4 API JNI proposée (à documenter en ARCHITECTURE.md §T3)
- `nExportPngSeq(handle, dirPath, json): Boolean` — json
  `{expandHolds:true, prefix:"frame_"}` (règle M3) ; écrit
  `prefix_%04d.png` pleine résolution (caméra §2 incluse,
  aides exclues).
- `nExportApng(handle, path, json): Boolean` — json
  `{loop, maxWidth, fps?}` (même enveloppe que `nExportGif` ;
  `fps` défaut 12, délais `celDelayCs`).
- 2 symboles. Audio seul = **0 JNI** (copie app-side).

### 4.5 Undoabilité / persistance
- Undo : exports = lecture seule (aucune entrée undo, comme
  GIF/WebM actuels).
- Persistance : aucune (les presets sticker vivent dans les
  réglages app-side session ; la piste audio reste session-only
  M7b — l'export audio seul ne la persiste pas).

### 4.6 Tests à ajouter
- Engine (+6) : `apngMagicAndActl` (signature PNG + `acTL` +
  `loop` retrouvé) ; `apngDelaysFollowHolds` (délais =
  `celDelayCs`) ; `apngSingleFrameValid` ; `pngSeqCountMatches`
  (N cellulos × holds éclatés = N fichiers) ;
  `pngSeqBadDirNoOp` ; `stickerPresetOpaque` (fond opaque
  forcé, alpha == 255 partout).
- Instrumentés (+4) : `pngSeqZipOpens` (ZIP + entrées PNG
  lisibles via JNI) ; `apngExportPlayable` (fichier non vide
  + `acTL` présent) ; `sticker512Fits` (côté max ≤ 512) ;
  `audioOnlyCopiesBytes` (export = byte-identique au cache).

### 4.7 Dépendances JNI
- **2 symboles** (§4.4) + §T3 avant code + `apng.h`/`pngseq`
  (miniz déjà vendored, 0 lib neuve) + tests.

---

## 5. Ordre de livraison + gates

1. **Étiquettes + feuille d'expo, palier (a) §3** d'abord (S,
   0 JNI) : valeur immédiate sur le clip M7/V+2 existant, aucun
   risque moteur ; pose le sidecar `anim.json` que §1–§2
   réutilisent.
2. **Images clés + courbes (§1)** (L, 2 symboles) : `anim.h` +
   §T3 avant code ; s'appuie sur le sidecar (a).
3. **Caméra (§2)** (M, 3 symboles) : réutilise le noyau §1
   (ne pas livrer §2 sans §1) ; toggle OFF par défaut.
4. **Multi-pistes, palier (b) §3** (M, 8 symboles) : consomme
   §1–§2 (clés par piste via sidecar) ; migration 1 piste =
   byte-identique verrouillée par test.
5. **Séquences + APNG + stickers + audio seul (§4)** en dernier
   (M, 2 symboles) : consomme §2 (caméra incluse) + holds V+2 ;
   APNG ferme le différé V+2-bis.
6. Chaque item = 1 slice (pattern slices 0–6) + extension
   `ARCHITECTURE.md` **avant** code moteur + gate locale
   `emutest.sh` verte + `prepush.sh` avant push (AGENTS.md).

**Totaux visés fin T3** : engine 116 → **≈137**
(+21 : §1: 6, §2: 5, §3: 6, §4: 6 ; §3a: 0) ;
émulateur 202 → **≈220** (+18 : §1: 4, §2: 3, §3: 5+2a?,
§4: 4 — le palier (a) ajoute 2 instrumentés : `labelJumpAnd
RoundTrip`, `exposureSheetShowsHolds`/`exposureCsvExports`
comptés en §3.6). Les comptes exacts sont arrêtés par slice ;
aucun item ne réduit un total existant.

**Extensions `ARCHITECTURE.md` à écrire avant code** :
`nSetLayerAnim`/`nLayerAnim` (§1) + `anim.h` (noyau pur) ;
`nSetCameraTrack`/`nCameraTrack`/`nSetCameraEnabled` (§2) +
sémantique « caméra = sortie » ; `nTrackCount`/`nAddTrack`/
`nSetActiveTrack`/`nActiveTrack`/`nSetTrackVisible`/
`nSetTrackName`/`nDeleteTrack`/`nTrackState` + sidecar
`anim.json` v1 + portée M7-sur-piste-active (§3) ;
`nExportPngSeq`/`nExportApng` + preset sticker + audio seul
0-JNI (§4). Le reste (courbes Bézier, overlays, feuille,
étiquettes palier a, presets, SAF/ZIP app-side) est 0 JNI par
construction.

## 6. Risques

- **Noyau d'interpolation (§1.3)** : lerp angle/scale autour du
  centre = choix honnête mais discutable (pivot libre = FUTUR).
  Mitigation : noyau pur + tests d'invariants ; pivot/ancrage
  documentés FUTUR, pas de promesse UI.
- **Caméra = sortie (§2)** : rompt la règle « aides hors
  exports » (pelure/repère). Mitigation : documenté comme
  sortie dès la spec ; toggle OFF par défaut ; test
  `cameraDisabledIdentical` verrouille le repli.
- **Multi-pistes × copies `Canvas` (§3)** : 4 pistes × N
  cellulos = coût mémoire M7 multiplié. Mitigation : même
  coût documenté qu'en M7 (petits canvas en tests) ; garde UI
  (refus au-delà de 4 pistes) ; `trackFramesIsolated` prouve
  l'isolation au lieu de la partager silencieusement.
- **Sidecar `anim.json` (§3.5)** : deux fichiers (ORA + sidecar)
  peuvent diverger (partage du seul ORA = animation perdue).
  Mitigation : absent = dégradé propre annoncé ; export ZIP
  Ultérieur (FUTUR, pas promis) ; cap 64 Ko + bandeau.
- **APNG from-scratch (§4)** : `fdAT`/`fcTL` mal séquencés =
  lecteurs stricts qui refusent. Mitigation : même pattern que
  `gif.h` (déjà livré et testé) ; miniz déjà vendored ;
  validation par relecture d'en-tête (`acTL` + N `fcTL`) en
  engine-test, pas de lecteur tiers requis.
- **ZIP séquence (§4)** : gros clips = centaines de PNG
  (stockage). Mitigation : `expandHolds` OFF possible ;
  progression + annulation ; refus honnête si espace
  insuffisant (pas de ZIP tronqué silencieux).
- **Portée JNI** : max 15 symboles neufs (2 + 3 + 8 + 2) —
  le plus gros budget depuis M7. Si la revue
  `ARCHITECTURE.md` préfère des enveloppes génériques
  (`nAnimOp(handle, json)`), les tests §1.6/§2.6/§3.6/§4.6
  restent valides (seule l'enveloppe change — même clause que
  T1 §8 et T2 §7).
- **Aucun risque clean-room** : lerp/aisances/APNG/ZIP/CSV =
  algos et formats publics from-scratch ; miniz déjà
  loggé ; audio = copie sans transcodage ; MP4 exclu
  (brevets) ; `.clip`-écriture toujours exclue.
