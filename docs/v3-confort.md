# V+3 — Spécification confort pro

> **Statut : SPEC UNIQUEMENT, aucun code.** Tranche V+3 du backlog
> (`docs/post-v1-backlog.md` : §1, §2, §4, §5, §8, §9, §10, §11, §12,
> §13).
> Règles : vocabulaire maison, FR/EN ressources, clean-room (aucune
> string/asset tiers), JNI gelé — toute extension moteur est
> documentée en `ARCHITECTURE.md` **avant** le code (AGENTS.md).
> État de départ vérifié sur master : V+1 committée (sélection
> app-side session-only, calque texte `nSetLayerText` +
> `nBlitPngIntoLayer`, `mode:"alpha-erase"` dest-out) ; V+2
> committée `55b3e49` (BD/cases, GIF `nExportGif`, webtoon, multipage
> `.ocpages/`, transfo `nSelectionBlit`, guides, Bézier densifié
> app-side, `nSetLayerMeta`, `nVectorPaths`, `curves`, blends +4, PSD
> couches) ; compteurs réels : engine **103** tests, émulateur **125**
> `@Test`.
> Ordre de lecture : §0 périmètre → §1 couleur (§1+§2 backlog) →
> §2 tracé (§5) → §3 texte (§8) → §4 sélection canaux (§4) →
> §5 pose 3D (§10) → §6 sorties (§11+§13) → §7 productivité (§12) →
> §8 conditionnels IA (§9+§11) → §9 livraison → §10 risques.

---

## 0. Périmètre honnête : 11 items, pas 7

L'en-tête du backlog annonce « V+3 : 7 » mais **11 lignes portent le
tag (V+3)** — même écart que V+2 (annoncé 12, réel 16, cf
`docs/v2-bd-export.md` §0). Liste exacte vérifiée, regroupée en
8 chapitres :

| # | Item backlog | Coût | Chapitre |
|---|---|---|---|
| 1 | §1 Mélangeur de couleurs sur canvas (couteau/palette) | L | §1 |
| 2 | §2 Épreuvage / profils ICC, CMJN | M | §1 |
| 3 | §2 Roue harmonies (complémentaires/triades, guide) | S | §1 |
| 4 | §5 Accrochage / simplification de tracés | M | §2 |
| 5 | §8 Texte sur tracé / déformation texte | M | §3 |
| 6 | §4 Mémorisation de sélections (canaux nommés) | S | §4 |
| 7 | §10 Mannequin articulé posable (format ouvert) | L | §5 |
| 8 | §13 TIFF / PDF / SVG ouverts | M | §6 |
| 9 | §12 Raccourcis clavier PC / tablette edge-keyboard | S | §7 |
| 10 | §11 Débruitage local **conditionnel** | M | §8 |
| 11 | §9 Auto-colorisation locale **conditionnelle** | L | §8 |

**Tranches de la spec (réponses aux « ? » implicites) :**
- Mélangeur : **oui, V+3** mais réduit à un mode dab `mix`
  (relevé local + couteau), pas un simulateur de matière — la
  peinture épaisse/impasto est **hors V+3** (pas de heightfield).
- ICC/CMJN : **épreuvage sortie seule, sans blob ICC tiers** —
  conversion naïve maison + avertissement de gamut, jamais de
  profil embarqué sans licence loggée.
- TIFF/PDF/SVG : **import baseline d'abord** (leçon PSD M4), par
  format, export différé sauf SVG-réimport déjà couvert en V+2.
- IA (débruitage, colorisation) : **conditionnels stricts** — même
  blocage qu'en M8 (aucun poids mobile à licence claire confirmé).
  Sans poids permissifs, seul le débruitage classique from-scratch
  est livré (§8.1) ; le reste est **non-promis**.
- Mannequin : **squelette glTF + modèle libre** (licence loggée),
  jamais de pose/asset proprio (EXCLU backlog §10).

**Principe transversal : app-side d'abord.** Chaque item tente 0 JNI
neuf (ponts existants : `nSetBrush`, `nStrokeBegin/Move/End`,
`nFrameCompositeInto`, `nLoadDocument`, `nBlitPngIntoLayer`,
`nSetLayerMeta`, `nSetSelectionMask`, `nSelectionBlit`,
`nSetRulers`, `nAppendVectorPath`, `nVectorPaths`,
`nSetLayerAdjustment`, `nLoadGlbReference`) ; le moteur n'est touché
que si indispensable, avec la proposition exacte à documenter en
`ARCHITECTURE.md` avant code.

---

## 1. Couleur : mélangeur, épreuvage, harmonies

### 1.1 Mélangeur couteau/palette — L, moteur (1 clé JSON, 0 symbole)

- **Périmètre honnête** : un **mode dab** `mix`, pas un moteur de
  matière. Chaque estampille : (a) relève la couleur locale moyenne
  sous le dab (fenêtre rayon dab, pixels prémultipliés du calque
  actif), (b) mélange avec l'encre courante selon `mix:0..1`
  (`0` = brosse normale, `1` = couteau pur qui ne fait que pousser
  la peinture), (c) tamponne le résultat avec les mêmes
  taille/opacité/pression que la brosse. **Hors V+3** : impasto,
  volume, séchage, mélange multi-calques (le relevé est intra-calque
  actif seul, comme les filtres M3).
- **UX** : toggle Couteau dans la barre brosse (à côté de taille /
  opacité, pattern `PinnedBrushBar` slice 3/5) + slider `mix`
  0..100 %. Curseur diamètre réel inchangé. Presets : `mix` persiste
  dans le preset maison (même JSON que `radius`/`opaque`). Strings
  `mixer_knife`, `mixer_amount` FR/EN.
- **Moteur vs app-side** : moteur, **extension JSON sans symbole
  neuf** (pattern M2 : clés `stabilize`/`curve`). `nSetBrush` accepte
  `"mix":0..1` (défaut 0 = inchangé, tests existants verrouillés) +
  `"mixRadius":0.5..2.0` optionnel (facteur × rayon, défaut 1).
  Clés inconnues ignorées (règle M3). Undo = `touchTile` comme les
  traits. Sur cible masque armée : refus (même garde que
  brosse/gomme V+1) ; sur calque ajustement : refus ; sur calque
  vectoriel : refus (raster seul).
- **Persistance** : aucune (paramètre d'outil, comme `radius` —
  presets maison + `BrushPrefs`, pas ORA).
- **Tests à ajouter** (+3 engine / +1 emu) : engine — `mix:0` =
  byte-identique brosse, `mix:1` sur à-plat rouge pousse du rouge
  (pas d'encre neuve introduite : teinte résultante entre encre et
  relevé), hors bornes clampé ; emu — `mixerSmearsStroke`
  (trait sur dégradé : composite localement homogénéisé, undo
  restaure).
- **Dépendance JNI** : aucune extension `ARCHITECTURE.md` sauf les
  2 clés `mix`/`mixRadius` de `nSetBrush` (paragraphe, pas de
  symbole).

### 1.2 Épreuvage CMJN / sortie — M, app-side pur (0 JNI)

- **Périmètre honnête** : **épreuvage seul** (aperçu), jamais de
  conversion destructive. Conversion maison naïve
  sRGB→CMJN (`K=1-max(R,G,B)` etc., from-scratch, documentée comme
  approximative) + simulation papier (blanc cassé paramétrable) +
  **avertissement hors-gamut** (pixels non représentables teintés en
  overlay, comme les fourmis : composite-only). **Jamais de blob
  ICC tiers** sans licence loggée en `third_party/README.md`
  (interdit backlog §2). Pas de vrai CMJN 32-bit dans le moteur.
- **UX** : toggle « Épreuve sortie » dans le dialogue export
  (`ExportDialog` M4/M8) + sélecteur `sRGB simulé | CMJN naïf` +
  toggle gamut-warning. Overlay-only (même chemin que onion/pelure).
  Aide : « simulation indicative, pas contrat imprimeur ».
  Strings `proof_title`, `proof_cmyk`, `proof_gamut` FR/EN.
- **Moteur vs app-side** : **0 JNI** — pull `nCompositeInto` (ou
  `nFrameCompositeInto`), conversion + overlay app-side, jamais
  écrit dans les pixels (annulé à la fermeture du dialogue).
- **Persistance** : aucune (réglage de dialogue session-only ;
  le choix papier/gamut peut rejoindre `WorkspaceStore` slice 6,
  à trancher en slice).
- **Tests à ajouter** (+2 emu, 0 engine) : `proofCmykOverlayOnly`
  (toggle on/off = byte-identique après fermeture),
  `proofGamutFlagsSaturated` (à-plat 100 % cyan : warning non-vide ;
  gris neutre : vide).
- **Dépendance JNI** : aucune.

### 1.3 Roue harmonies — S, app-side pur (0 JNI)

- **Périmètre** : pur calcul sur la teinte courante : modes
  complémentaire / analogue / triade / carré, affichés en **guide
  overlay** sur la roue HSV existante (`ColorPanel` slice 2) +
  tap-pour-adopter (écrit `inkHex`, source unique inchangée).
  Aucun effet pixels, aucun JNI.
- **UX** : segment « Harmonies » dans `ColorPanel`, 4 modes,
  pastilles cliquables, hint `harmony_hint`. Strings
  `harmony_*` FR/EN.
- **Persistance** : mode choisi en `WorkspaceStore` (préférence UI,
  pas document).
- **Tests** (+1 emu) : `harmonyComplementAdopts` (teinte 0° →
  complément 180° adopté dans `inkHex`).
- **Dépendance JNI** : aucune.

---

## 2. Tracé : accrochage + simplification — M, 0 symbole neuf

- **Périmètre honnête** : deux moitiés indépendantes.
  - (a) **Simplification** : Ramer-Douglas-Peucker **app-side pur**
    (from-scratch ~20 lignes, epsilon = 0.5–2 px réglable) appliqué
    à la polyligne **avant** `nAppendVectorPath` (vecteur) ou avant
    estampillage (raster : sous-échantillonne les points du stroke).
    Modèle paths M6 inchangé (polylignes à profil, rappel V+2 §4.3).
  - (b) **Accrochage** : snap des points d'entrée aux guides
    persistants V+2 + grille optionnelle (pas 8/16/32 px) + sommets
    des paths voisins (tolérance 4 px). Réutilise le shim
    `snapGuides` (tolérance 2 px V+2) étendu par clés JSON.
- **UX** : toggles dans la rangée règles M6 (icônes
  aimant + courbe-lissée) + slider epsilon + pas de grille.
  Preview : point aimanté montré (overlay, comme guides V+2).
  Strings `snap_grid`, `simplify_epsilon` FR/EN.
- **Moteur vs app-side** : **0 symbole neuf**. (a) est app-side
  pur. (b) = extension JSON de `nSetRulers` existant : clés
  `"snapTol":px`, `"grid":0|8|16|32`, `"snapPaths":bool`
  (inconnues ignorées, règle M3 ; absentes = comportement V+2).
  Ordre pipeline figé (rappel V+2 §7) : lisser PUIS snapper.
- **Persistance** : réglages outil en `BrushPrefs`/`WorkspaceStore`
  (pas ORA) ; les guides restent V+2 (`guides.json` / workspace).
- **Tests à ajouter** (+2 engine / +2 emu) : engine — snap grille
  16 px à tolérance, `snapTol:0` désactive ; emu —
  `simplifyReducesPoints` (polyligne 100 pts → < 40, courbe à
  epsilon visuel), `snapGridPersists` (reload : réglages relus).
- **Dépendance JNI** : paragraphe `ARCHITECTURE.md` (clés
  `snapTol`/`grid`/`snapPaths` de `nSetRulers`), pas de symbole.

---

## 3. Texte sur tracé / déformation — M, 0 JNI neuf

- **Périmètre honnête** : deux livraisons, après calque texte V+1
  + maillage V+2 (dépendances explicites).
  - (a) **Texte sur tracé** : mise en page **app-side** (platform
    Canvas `drawTextOnPath`, police système — pas de moteur fonte
    natif, règle V+1 §8) le long d'un path vectoriel désigné
    (tap path quand l'outil texte est actif). Commit pixels via
    `nBlitPngIntoLayer` existant (1 undo, pattern V+1 §2.1
    « ré-éditer = remplacer »).
  - (b) **Déformation** : réutilise `nSelectionBlit` mode `mesh`
    V+2 — le texte validé (raster) se déforme comme tout contenu
    de calque (grille 3×3). **Aucune déformation vectorielle du
    glyph** en V+3 (pas de contour fonte moteur).
- **UX** : outil texte + mode « suivre le tracé » (tap path =
  lier, drag offset le long du path, slider hauteur). Overlay
  chemin + poignées offset. Badge `Tx` existant + suffixe `⌒`
  quand lié à un path. Strings `text_on_path`, `text_offset`.
- **Moteur vs app-side** : **0 JNI** (composition de ponts V+1/V+2
  existants).
- **Persistance ORA** : fiche texte V+1 étendue via le canal
  générique `nSetLayerMeta` (V+2) : `x-openclip-text-path` (index
  du path / calque vectoriel source), `x-openclip-text-offset`
  (0..1). Lecteurs tiers : pixels visibles, attributs ignorés
  (pattern M8/V+1).
- **Tests à ajouter** (+3 emu, 0 engine) : `textOnPathCommits`
  (commit le long d'un arc : composite non-vide, undo restaure),
  `textOnPathRoundTrip` (fiches relues identiques),
  `textMeshWarpUndoable` (mesh sur texte validé : change puis
  restaure).
- **Dépendance JNI** : aucune (paragraphe « V+3 texte = ponts
  existants » en `ARCHITECTURE.md` si besoin de lever l'ambiguïté,
  comme V+2 §4.3).

---

## 4. Canaux de sélection nommés — S, app-side pur (0 JNI)

- **Périmètre honnête** : la sélection V+1 reste un masque
  session-only ; V+3 ajoute des **canaux nommés persistés** :
  liste par document (`Sélection 1…N`, renommables), activer =
  recharger dans le masque session, Encounter = écraser depuis le
  masque courant. Opérations booléennes à l'activation : remplacer
  / ajouter / soustraire / intersecter (calcul app-side sur le
  buffer 1-bit, pur CPU).
- **UX** : panneau Canaux (pattern repliable slice 3) : liste +
  renommer / supprimer (garde : jamais de crash à 0 canaux, le
  masque session reste utilisable), 4 boutons de mode à
  l'activation. Strings `channel_save/load/delete`, `channel_op_*`.
- **Moteur vs app-side** : **0 JNI**. Stockage = PNG 1-bit
  (via `nFrameCompositeInto` ? non — le masque est app-side :
  écriture PNG directe par la plateforme) dans le dossier doc
  (`channels/<nom>.png` à côté du ORA, ou `project.json`-régé
  pour le multipage `.ocpages/`) + re-upload via
  `nSetSelectionMask` V+2 quand le moteur en a besoin (transfo).
  Le masque session garde sa sémantique V+1 (jamais persisté
  lui-même, annoncé).
- **Persistance** : `channels.json` (v1 : `[{name, file}]`,
  champs inconnus ignorés) + PNG ; multipage : par page (même
  dossier que la page). Validés par round-trip.
- **Tests à ajouter** (+3 emu, 0 engine) : `channelSaveReload`
  (masque → canal → clear → reload = identique),
  `channelBooleanOps` (ajout/soustraction/intersection sur fixtures
  2 rects), `channelRenameDelete` (garde 0 canaux + masque intact).
- **Dépendance JNI** : aucune.

---

## 5. Mannequin articulé posable — L, moteur + app (2 symboles)

Le seul chantier V+3 qui demande des **symboles neufs**, avec un
gating asset strict.

- **Périmètre honnête** : squelette articulé minimal (hanches,
  torse, tête, bras ×2 segments, jambes ×2 segments ≈ 10 os,
  rotations Euler par os) + handles overlay + pose par drag.
  Le mannequin est un **glTF libre** (ex. modèle CC0, licence
  loggée en `engine/third_party/README.md` + `assets/` — aucun
  asset proprio, rappel EXCLU §10). Rendu = **wireframe overlay**
  (même contrat que M9 : `nCompositeInto` seul, jamais export).
  **Hors V+3** : skinning texturé, cinématique inverse, multi-
  personnages, animation du mannequin.
- **UX** : panneau Pose (liste d'os, sliders par axe quand un os
  est sélectionné, drag direct des handles canvas, presets
  debout/assis/course, reset). Handles = overlay-only, suivent la
  matrice de vue. Strings `pose_*` FR/EN.
- **Moteur (à documenter en `ARCHITECTURE.md` avant code)** :
  - `nRefJoints(handle): String` — introspection seule : JSON
    `{joints:[{name, parent, pos:[x,y,z]}]}` (objet vide si pas de
    modèle). Pattern `nVectorPaths` V+2 (lecture seule, pas de
    pixels).
  - `nSetRefPose(handle, json): Boolean` — `{bone:deg…}`
    (rotations Euler degrés, clés inconnues ignorées, règle M3 ;
    hors bornes ±180 clampé, pas d'échec). Overlay-only, jamais
    dans undo (comme `nSetRefView` M9).
  - Le skinning est CPU from-scratch (linéaire, 10 os — pas de
    lib), wireframe existant M9 réutilisé.
- **Persistance** : pose en `nSetLayerMeta` ? Non — pas un calque :
  JSON doc app-side (`pose.json` à côté du doc / workspace,
  `x-openclip-pose` exclu) + `project.json` multipage si besoin.
  Modèle : chemin cache + hash (re-résolu au chargement, refus
  honnête si absent).
- **Tests à ajouter** (+3 engine / +3 emu) : engine — joints du
  cube-fixture (0 os, objet vide), pose hors bornes clampée,
  pose inconnue ignorée ; emu — `poseHandlesMove` (drag handle :
  overlay suit), `poseRoundTrip` (save/reload identique),
  `poseNeverExports` (composite export sans wireframe).
- **Gate asset** : aucun code pose avant qu'un modèle libre
  (CC0/CC-BY, poids < 2 Mo) soit choisi + licence loggée. Sans
  modèle : symboles + overlay validés sur le cube-fixture M9 seul
  (dégradé documenté).

---

## 6. Formats ouverts : TIFF / PDF / SVG — M, par format baseline

Leçon PSD M4 appliquée : **baseline d'abord, refus honnête**, 0
promesse au-delà. Chaque format = 1 palier indépendant.

### 6.1 TIFF import baseline — moteur, 0 symbole neuf

- **Périmètre** : TIFF non-compressé + LZW, RGB/RGBA8, strips,
  1 page (page 1 seule, compteur « page 1/N » honnête).
  Refus : JPEG-dans-TIFF, CMYK, 16-bit, tuiles (message, pas
  crash). From-scratch (pas de libtiff — règle single-file ;
  tags lus : `ImageWidth/Length`, `BitsPerSample`,
  `Compression`, `StripOffsets/ByteCounts`).
- **Moteur** : extension de `nLoadDocument` (détection `.tif/.tiff`,
  signature inchangée — paragraphe `ARCHITECTURE.md`).
  `nSaveDocument(…, "tiff")` ? **Différé** (export = PNG, pont
  existant).
- **Tests** (+3 engine / +1 emu) : header II/MM, RGB8 strips
  round-trip, LZW décodé, CMYK rejeté = false + session intacte ;
  emu : fichier ouvrable via SAF.

### 6.2 PDF import première page — app-side pur

- **Périmètre** : **page 1 seule** (compteur honnête), rasterisée
  via la plateforme (`PdfRenderer`, pas de moteur) à 150 dpi puis
  `nLoadDocument` (PNG cache) ou `nBlitPngIntoLayer`. Export PDF ?
  **Différé** (partage = PNG/WebM existants).
- **Tests** (+1 emu) : fixture PDF 1 page générée in-code :
  import non-vide + dimensions.
- **JNI** : aucun.

### 6.3 SVG import vectoriel — app-side pur

- **Périmètre** : subset `<path>` (M/L/C/Q/Z absolus+relatifs,
  `stroke-width`, `stroke`) → `nAppendVectorPath` (densification
  V+2 §4.3 pour C/Q). Refus honnête : texte, dégradés, filters,
  `<image>` (ignorés + compteur visible). Export SVG = déjà V+2
  §3.3 (round-trip approximatif documenté).
- **Tests** (+2 emu) : `svgImportPaths` (2 paths : 2 tracés,
  largeurs), `svgImportIgnoresText` (compteur + pixels texte
  absents).
- **JNI** : aucun (lecture `nVectorPaths` existante pour vérif).

---

## 7. Productivité : raccourcis clavier — S, app-side pur

- **Périmètre** : **clavier physique seul** (Chromebook / tablette
  + edge-keyboard — pas de clavier logiciel) : annuler/rétablir,
  brosse/gomme/couteau, taille +/-, pipette (Alt-clic déjà ?),
  calque haut/bas, frame suiv./préc., lecture/pause, plein-écran,
  aide raccourcis (`?`). Table fixe FR/EN, non-rebindable en V+3
  (rebind = différé, pas de chantier settings).
- **UX** : dialogue `?` (liste cherchable, pattern blend slice 3),
  hints dans les menus existants (`MenuBar` slice 0). Conflits :
  elaborado contre les gestes (priorité clavier quand visible,
  documenté).
- **Persistance** : aucune (table en dur versionnée).
- **Tests** (+2 emu) : `shortcutUndoRedo` (Ctrl+Z / Ctrl+Y :
  piles suivent), `shortcutToolSwitch` (B/E/M : outil actif suit,
  probe `activeToolForTest()`).
- **JNI** : aucun.

---

## 8. Conditionnels IA : seulement si poids permissifs

Même blocage qu'en M8 (débruitage + colorisation différés : aucun
poids mobile à licence claire). **Règle d'entrée** : aucun code
§8.2/§8.3 avant qu'un poids (ONNX, < 5 Mo, Apache-2.0/MIT/CC0,
origine loggée) soit validé comme le modèle ESPCN M8. La revue de
licence est le gate, pas la technique.

### 8.1 Débruitage classique from-scratch — M, 0 symbole neuf (NON conditionnel)

- **Périmètre** : filtre `denoise` dans le registre `nApplyFilter`
  existant (params `{"strength":0..1, "mode":"median|bilateral"}` —
  médian 3×3 + bilatéral naïf from-scratch, M3 pattern). Undoable,
  contrat alpha M3. C'est le **socle livrable sans IA**.
- **Tests** (+2 engine / +1 emu) : bruit impulsionnel réduit
  (variance avant > après), identité à `strength:0` byte-identique ;
  emu : dialogue + preview (pattern M3).
- **JNI** : paragraphe (nom + params du registre), pas de symbole.

### 8.2 Débruitage IA local — M conditionnel (app-side)

- **Périmètre SI poids** : même flot que SR M8 (ONNX Runtime
  1.31.0 existant, luma + chroma, PNG → `nLoadDocument`) avec un
  modèle débruitage mobile permissif. Sinon : **non-livré**,
  §8.1 seul + note honnête (comme M8).
- **Tests** (+1 emu, si livré) : `denoiseAiReducesNoise` (fixture
  bruitée : variance après < avant, alpha intact).
- **JNI** : aucun.

### 8.3 Colorisation automatique locale — L conditionnelle (app-side)

- **Périmètre SI poids** : calque ligne (luma) + hints couleur
  (taps) → modèle mobile permissif → calque couleur proposé
  (jamais appliqué direct : preview + accepter/refuser, undoable
  via `nAddLayer` + `nBlitPngIntoLayer`). Service en ligne :
  **EXCLU** (backlog §9/§11, règle projet sans-cloud).
- **Tests** (+1 emu, si livrée) : `colorizePreviewLayer` (calque
  proposé non-vide, original intact).
- **JNI** : aucun.

---

## 9. Ordre de livraison + gates

1. **Harmonies + raccourcis (§1.3, §7)** d'abord : app-side purs,
   0 risque, réchauffent les ponts UI (`ColorPanel`, `MenuBar`).
2. **Canaux nommés (§4)** : la sélection V+1 devient persistée
   (débloque les usages BD V+2 : masques cases réutilisables).
3. **Simplification/accrochage (§2) + texte-sur-tracé (§3)** :
   précision + lettrage, 0 JNI (densification + ponts existants).
4. **Mélangeur (§1.1)** : le seul moteur non-conditionnel de la
   tranche (clés `mix`, isolé, testé engine-first).
5. **Épreuvage (§1.2) + TIFF/PDF/SVG (§6)** : sorties baseline,
   indépendantes, parallélisables.
6. **Mannequin (§5)** : en dernier (gate asset + 2 symboles —
   le plus gros risque JNI de la tranche).
7. **Conditionnels IA (§8.2/§8.3)** : seulement si le gate poids
   passe ; §8.1 (classique) livré avec le lot (5) sans attendre.
8. Chaque chantier = 1 spec slice dédiée (pattern slices 0–6) +
   extension `ARCHITECTURE.md` **avant** code moteur + gate locale
   `emutest.sh` verte + `prepush.sh` avant push (AGENTS.md inchangé).

**Totaux visés fin V+3** : engine 103 → **≈113** (+10 : mélangeur
3, guides-clés 2, TIFF 3, denoise-classique 2 ; mannequin +3 et
conditionnels exclus du compte nominal) ; émulateur 125 →
**≈150** (+25 : §1 4, §2 2, §3 3, §4 3, §5 3, §6 4, §7 2, §8 4 —
débruitage classique 1 + conditionnels 2 inclus seulement si
livrés). Les comptes exacts sont arrêtés par slice ; aucun item
ne réduit un total existant.

**Extensions `ARCHITECTURE.md` à écrire avant code (rappel § par §)** :
clés `mix`/`mixRadius` de `nSetBrush` (§1.1), clés
`snapTol`/`grid`/`snapPaths` de `nSetRulers` (§2), « V+3 texte =
ponts existants, 0 JNI » (§3), `nRefJoints` + `nSetRefPose` + gate
asset (§5), TIFF dans `nLoadDocument` + registre `denoise`
(§6.1/§8.1). Le reste (§1.2, §1.3, §4, §6.2, §6.3, §7, §8.2, §8.3)
est 0 JNI par construction.

## 10. Risques

- **Mélangeur vs perfs** : relevé local par dab = lecture voisinage
  à chaque estampille (coût rayon²). Mitigation : `mixRadius`
  plafonné ×2 + tests plancher/plafond tablette ; dégradé honnête
  (relevé sous-échantillonné) si trop lent — à trancher en slice
  avec mesure, pas avant.
- **Épreuvage naïf pris pour contrat** : la conversion maison
  n'est pas un ICC. Mitigation : wording aide + badge « indicatif »
  dans le dialogue (règle V+1 : pas d'entrée leurre, pas de
  promesse).
- **Mannequin sans modèle** : symboles sans asset = coquille vide.
  Mitigation : gate asset (§5) — sans modèle libre validé, seuls
  les symboles testés sur cube-fixture partent (overlay), la
  pose complète est reportée V+3-bis.
- **Pose proprio déguisée** : importer `.c3dd/.cs3o/.pep` « pour
  dépanner » contournerait l'EXCLU §10. Mitigation : refus honnête
  + aide « modèle libre », comme `.clip`-écriture en V+2.
- **TIFF scope-creep** : le format a 70+ tags. Mitigation : baseline
  fermée (§6.1) + table des refus en slice, comme PSD M4.
- **IA fantôme** : promettre §8.2/§8.3 sans poids = dette.
  Mitigation : conditionnels **non-comptés** dans les totaux
  nominaux + note M8-style tant que le gate licence ne passe pas.
- **Clavier vs gestes** : conflits focus (stylus-first slice 1).
  Mitigation : priorité clavier documentée + dialogue `?` comme
  source unique (pas de rebind V+3).
- **Aucun risque clean-room** : RDP/Catmull-Rom/bilatéral/médian/
  CMJN-naïf from-scratch, glTF ouvert, ONNX existant, clavier
  plateforme, `.cmc`/`.clip`-écriture/boutique/cloud toujours exclus.
