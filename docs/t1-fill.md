# T1 — Fondations dessin (SPEC UNIQUEMENT, aucune modif de code)

> **Statut : spec de cadrage, zéro code.** Tranche T1 du plan
> `docs/parity-gap.md` §T1 (6 items) : les outils de remplissage,
> dégradé, figures, baguette, adoucissement de sélection + 3 filtres.
> Règles projet (AGENTS.md) : vocabulaire maison, FR/EN ressources,
> clean-room (aucune string/asset tiers), **JNI gelé — toute extension
> moteur est documentée en `ARCHITECTURE.md` avant le code**,
> `engine/include/openclip/` seul contrat public, `make test` vert,
> gate locale `emutest.sh`.
> Ordre de lecture : cette spec → `docs/parity-gap.md` §T1 →
> `docs/v1-selection-texte-gomme.md` (masque §1, ponts) →
> `ARCHITECTURE.md` (V+1/V+2, à étendre en §T1 avant code).

État de départ vérifié sur master : brosse/tampon absents
(`grep floodFill/bucket` vide) ; `nApplyFilter` existe (M3, registre
`filters.h` : gaussianBlur/sharpen/motionBlur/radialBlur/posterize/
levels/chromaticAberration/addNoise/mosaic/gradientMap/denoise) ;
undo par trame M7 (`touchTile`, snapshots pleine couche pour
filtres) ; sélection = masque **app-side** V+1 (`EngineSurface.
selectionMask`, garde avant `nStrokeMove`, mix sous masque via
composite avant/après + `nLoadDocument` dans trame dupliquée) +
`nSetSelectionMask`/`nSelectionBlit` moteurs V+2 (transfo) ;
`nApplyTone` M10 (remplissage motif, alpha intact) ; `sharpen`
existe (≠ unsharp) ; `gradientMap` = filtre/ajustement, **pas**
d'outil dégradé.

Conventions T1 (valent pour les 6 items) :
- **Contrat alpha M3** : filtres/tons ne touchent jamais l'alpha,
  rgb clampé `[0,alpha]`. Seuls remplissage/dégradé/figures
  (peinture) et gomme dest-out modifient l'alpha **par design**.
- **Undo** : toute écriture pixels = snapshots tuiles pleine couche
  (pattern filtre M3 : `touchTile` tout, `endStroke` si succès,
  `cancelStroke` si échec), 1 pas d'undo par validation/geste.
  Per-frame M7 (changer de trame = autre pile).
- **Overlay-only** : prévisualisations (seau, dégradé, figure,
  baguette) = `Canvas` Compose par-dessus `EngineSurface`, jamais
  dans les pixels moteur (même règle que curseur/HUD/pipette).
- **Coordonnées** : masque et géométries en pixels moteur
  (`screenToEngine()`, rotation-aware) ; overlay fait l'inverse via
  la matrice de vue existante.
- **Seuils tactiles** : toute cible ≥ 48dp (`touchTargets48dp*`) ;
  sliders avec pas discret + champ numérique ; gestes stylet-first.
- **Strings** : clés FR + EN dès la spec, vocabulaire maison
  (seau, baguette, dégradé, figure, contour progressif, masque
  rapide) — aucun terme tiers.

---

## 1. Remplissage flood-fill (seau)

### 1.1 Périmètre
- **Livre** : outil Seau — tap = remplit la zone **contiguë**
  (4-connexité) de la couleur d'encre sur le **calque actif**, avec
  **tolérance** (0 = pixel exact, 1 = tout) + **antialiasing**
  (lissage des bords à 1px, pas de marche d'escalier).
- Respecte le **sous-masque de sélection V+1** : hors masque =
  ignoré (même garde app-side que pinceau/gomme avant `nStrokeMove`)
  ; sans sélection = pleine couche.
- Options : `étendre 1–2px` (déborde sous le trait, anti-halo),
  `contigu only` (toujours vrai en T1 — le non-contigu = baguette
  globale §4, pas le seau), `fusionné` (échantillonne le composite
  visible mais écrit sur le calque actif — défaut NON, documenté).
- **Hors périmètre** : remplissage de motif/ton (M10 `nApplyTone`
  existe), remplissage de texte/vectoriel (pixels raster seuls),
  remplissage multi-calques en 1 pas (pont §V+1 si besoin, annoncé).

### 1.2 UX
- Outil Seau dans `ToolPalette` (icône seau maison) ; panneau
  repliable : tolérance slider 0–100 % (défaut 12 %), toggle
  antialiasing (défaut ON), étendre stepper 0/1/2px, aperçu
  survol (overlay teinté de la zone qui serait remplie, calcul
  throttlé 100 ms, annulable).
- Geste : tap = remplir ; drag = tolérance temporaire (vertical,
  comme pipette) ; 2-tap = annuler l'aperçu.
- Refus honnêtes avec hint : calque ajustement (`adjust != null`),
  calque invisible, calque vectoriel (proposer rasteriser, pas de
  silencieux).

### 1.3 Moteur vs app-side : **moteur** (scanline flood tuilé)
- Le flood-fill touche des millions de px : boucle C++ moteur,
  pas de pull/push bitmap plein à chaque tap.
- Nouveau header public **`engine/include/openclip/fill.h`**
  (testé par `engine/tests/test_fill.cpp`) :
  ```cpp
  struct FillParams { float tolerance = 0.12f; // 0..1
                      bool antialias = true;
                      int expandPx = 0; };      // 0..2
  // Remplit la région contiguë contenant (x,y) sur s avec ink
  // (straight rgb). seed = couleur du pixel (x,y) échantillonné
  // selon sampleMerged. mask (nullptr = sans sélection) : hors
  // masque = intouché. Région = |lum(px)-lum(seed)| <= tol*255
  // (luma Rec.601, même poids que gradientMap). Antialias = rampe
  // 1px sur la frontière (alpha partiel, prémultiplié-safe).
  // Retourne false + surface byte-identique si seed hors surface
  // ou params invalides.
  bool floodFill(Surface& s, int x, int y,
                 std::uint8_t r, std::uint8_t g, std::uint8_t b,
                 const FillParams& p, const std::uint8_t* mask,
                 int maskStride, std::string& error);
  ```
- Échantillonnage `fusionné` : le shim compose d'abord (pixels
  existants), passe le seed au noyau — le noyau reste pur
  (pas de composite dans `fill.h`).

### 1.4 API JNI proposée (à documenter en ARCHITECTURE.md §T1)
- `nFloodFill(handle, x, y, colorHex, json): Boolean` —
  json `{tolerance:0..1, antialias:bool, expandPx:0..2,
  sampleMerged:bool}` (clés inconnues ignorées, manquantes =
  défauts, règle M3). Undo = snapshots pleine couche (pattern
  filtre). `false` + couche intacte si seed hors canvas ou calque
  non-raster.
- Masque : réutilise le masque moteur V+2 s'il est posé
  (`nSetSelectionMask`), sinon le garde app-side V+1 suffit
  (tap hors sélection refusé avant l'appel). Les deux chemins
  testés, pas de double source.

### 1.5 Undo / persistance
- Undo : 1 pas par tap (snapshots tuiles, `touchTile`).
- Persistance : **pixels commis, pas de fiche** (rouvrir = pixels,
  comme un trait). Rien en ORA.

### 1.6 Tests à ajouter
- Engine (`test_fill.cpp`, +6) : `fillExactRespectsBoundary`
  (plaque 2 couleurs, tol 0 = ne traverse pas) ;
  `fillToleranceCrossesNearColors` (tol haute traverse le proche) ;
  `fillAntialiasSoftensEdge` (frontière = alpha partiels, rgb ≤
  alpha) ; `fillRespectsMask` (hors masque byte-identique) ;
  `fillUndoRestores` (byte-identique) ; `fillBadSeedNoOp`
  (hors surface = false + intact).
- Instrumentés (+2) : `bucketFillChangesCompositeAndUndoRestores`
  (tap in-code via `nFloodFill`, composite change, undo restaure) ;
  `bucketFillMasked` (sélection moitié : hors-sélection
  byte-identique).

### 1.7 Dépendances JNI
- 1 symbole neuf (`nFloodFill`) + `ARCHITECTURE.md` §T1 avant code
  + `check_jni_symbols.sh` + CI. Aucune lib externe (file
  d'attente scanline maison, pas de récursion).

---

## 2. Dégradé linéaire / radial + libre (outil, pas filtre)

### 2.1 Périmètre
- **Livre** : outil Dégradé — drag définit la géométrie, commit à
  la relâche. 3 modes : **linéaire** (2 points), **radial**
  (centre + rayon), **libre** (N arrêts posés au tap, interpolation
  par pondération inverse-distance — pas un mesh Bézier, honnête).
- Arrêts : 2–8 couleurs maison (`color-sets.json` + historique),
  positions 0..1 réglables, interpolation RGB (pas de pigmentaire,
  documenté). Dithering léger ON par défaut (anti-banding 2 LSB).
- Sous-masque V+1 respecté (même garde) ; écrit sur calque actif
  (source-over, alpha selon arrêts opaques — dégradés alpha
  complexes = FUTUR, refusé honnêtement).
- **N'est PAS un filtre** : `gradientMap` (recolorie par luma)
  reste ce qu'il est ; ici on **peint** des pixels. Pas de calque
  dégradé vivant en T1 (pixels commis).

### 2.2 UX
- Outil Dégradé (`ToolPalette`) ; panneau : mode
  linéaire/radial/libre (segmenté), éditeur d'arrêts (liste
  couleur + position slider, +/−, 48dp), toggle dithering,
  aperçu live overlay (bandeau géométrie + rendu basse-rés,
  commit pleine-rés à la relâche).
- Gestes : drag = définir (linéaire/radial) ; mode libre = taps
  successifs posent les arrêts (max 8), double-tap = commit,
  2-tap = annuler. Annuler mid-drag = aucun pixel (preview
  overlay-only).
- Refus : calque ajustement/vectoriel/invisible (mêmes hints §1).

### 2.3 Moteur vs app-side : **moteur** (boucle pixels)
- Nouveau header **`engine/include/openclip/gradient.h`** :
  ```cpp
  struct GradientStop { std::uint8_t r,g,b; float pos; }; // pos 0..1
  struct GradientSpec {
      std::string kind; // "linear"|"radial"|"free"
      float x0,y0,x1,y1; // linear: p0->p1 ; radial: centre+rayon
                         // (x1,y1 = point circonférence)
      std::vector<GradientStop> stops; // 2..8, triés par pos
      // free: pts[i] = ancrage de stops[i] (même taille)
      std::vector<std::pair<float,float>> pts;
      bool dither = true;
  };
  // Peint le dégradé sur s (source-over, opaque). mask nullptr =
  // sans sélection. false + intact si spec invalide (<2 stops,
  // rayon nul, tailles incohérentes).
  bool paintGradient(Surface& s, const GradientSpec& spec,
                     const std::uint8_t* mask, int maskStride,
                     std::string& error);
  ```
- Interp : linéaire/radial = projection scalaire → rampe arrêts ;
  libre = poids `w_i = 1/(d_i²+ε)` normalisés (simple, borné,
  testable). Dithering = bruit LCG déterministe ±1 LSB (même
  graine à chaque appel, reproductible comme `addNoise`).

### 2.4 API JNI proposée
- `nPaintGradient(handle, json): Boolean` — json
  `{kind, x0,y0,x1,y1, stops:["#RRGGBB:pos",...],
  pts:[[x,y]...]?, dither:bool}` (même encodage stops que
  `gradientMap`, cohérence). 1 undo par commit. Masque : même
  règle §1.4 (moteur si posé, garde app-side sinon).

### 2.5 Undo / persistance
- Undo : 1 pas par commit (snapshots pleine couche).
- Persistance : pixels commis, pas de fiche. (Un calque dégradé
  vivant = T5, pas T1.)

### 2.6 Tests à ajouter
- Engine (+6) : `gradientLinearEndpoints` (p0/p1 = arrêts exacts) ;
  `gradientRadialCenterEdge` ; `gradientFreeWeights`
  (proche d'un arrêt ≈ sa couleur) ; `gradientDitherBounded`
  (|Δ| ≤ 2 LSB vs sans dither) ; `gradientRespectsMask` ;
  `gradientBadSpecNoOp`.
- Instrumentés (+2) : `gradientDragCommitsAndUndoRestores` ;
  `gradientMasked`.

### 2.7 Dépendances JNI
- 1 symbole (`nPaintGradient`) + §T1 avant code. 0 lib externe.

---

## 3. Figures géométriques (rect / ellipse / polygone)

### 3.1 Périmètre
- **Livre** : outil Figure — **timbres undoables** raster sur
  calque actif : rectangle (coins arrondis optionnels),
  ellipse (cercle avec verrou), polygone (3–12 côtés, rotation).
  Fond plein et/ou contour (épaisseur 1–64px), antialiasés.
- Pas de paths vectoriels éditables (M6 existe pour ça) : ici des
  **pixels commis en 1 pas**, comme un trait. Trames/cases BD
  (vectorielles V+2) inchangées.
- Sous-masque V+1 respecté (découpe app-side ou mask moteur,
  même règle).

### 3.2 UX
- Outil Figure ; panneau : forme (segmenté rect/ellipse/poly),
  côtés stepper 3–12, rotation slider, fond (couleur + ON/OFF),
  contour (couleur + ON/OFF + épaisseur), coins arrondis slider
  (rect seul), verrou carré/cercle (toggle + geste : 1 doigt
  contraint si l'autre touche l'écran, pattern existant ? sinon
  toggle seul — trancher en slice).
- Geste : drag = définir le cadre (départ → relâche), preview
  overlay-only (marching-ants + remplissage translucide) ;
  relâche = commit 1 undo. Échap/2-tap mid-drag = annuler.
- Cibles ≥ 48dp, sliders + champs numériques.

### 3.3 Moteur vs app-side : **moteur** (rastérisation AA)
- Nouveau header **`engine/include/openclip/shapes.h`** :
  ```cpp
  struct ShapeSpec {
      std::string kind; // "rect"|"ellipse"|"polygon"
      float x0,y0,x1,y1; // cadre (normalisé shim-side)
      int sides = 4;    // polygon 3..12
      float rotation = 0; // degrés, polygon seul
      float corner = 0; // rayon coins, rect seul
      bool fillOn = true; std::uint8_t fillR,fillG,fillB;
      bool strokeOn = false; float strokeW = 1;
      std::uint8_t strokeR,strokeG,strokeB;
  };
  // Rastérise AA (couverture 4x4, prémultiplié-safe) en
  // source-over. Contour centré sur le bord (moitié dedans).
  // false + intact si cadre dégénéré ou spec invalide.
  bool stampShape(Surface& s, const ShapeSpec& spec,
                  const std::uint8_t* mask, int maskStride,
                  std::string& error);
  ```
- Pourquoi moteur : AA + épaisseurs + polygones = boucle C++
  bornée et testable ; l'app ne fait que le cadre gestuel.

### 3.4 API JNI proposée
- `nStampShape(handle, json): Boolean` — json = `ShapeSpec`
  sérialisé (clés inconnues ignorées, défauts M3). 1 undo par
  commit.

### 3.5 Undo / persistance
- Undo : 1 pas par figure. Persistance : pixels commis.

### 3.6 Tests à ajouter
- Engine (+6) : `shapeRectFillsInterior` (intérieur plein,
  extérieur intact) ; `shapeEllipseAA` (frontière partielle) ;
  `shapePolygonSides` (hexagone ≈ 6 lobes — test par symétrie,
  pas pixel-par-pixel) ; `shapeStrokeWidth` (épaisseur ≈ demandée
  ±1px) ; `shapeRespectsMask` ; `shapeDegenerateNoOp`.
- Instrumentés (+1) : `shapeStampUndoable`
  (commit → composite change → undo restaure).

### 3.7 Dépendances JNI
- 1 symbole (`nStampShape`) + §T1 avant code. 0 lib externe.

---

## 4. Baguette magique (sélection contiguë, tolérance)

### 4.1 Périmètre
- **Livre** : outil Baguette — tap = **sélectionne** (pas peint)
  la région contiguë de couleur proche, avec **tolérance**
  0–100 %. Écrit dans le **masque de sélection** (pas dans les
  pixels). Cumul : tap = remplacer, Maj/SHIFT-style toggle
  (`+`/`−` dans le panneau : ajouter/soustraire/intersecter).
- Source d'échantillonnage : calque actif (défaut) ou composite
  fusionné (toggle, documenté) — même option que le seau §1.
- Différée depuis V+1 (« flood-fill tuilé + tolérance ») : c'est
  cette spec qui la livre, en réutilisant le noyau §1.
- **Hors périmètre** : sélection non-contiguë globale (même
  couleur partout — FUTUR), adoucissement auto (c'est §5),
  transformation du contenu (V+2 `nSelectionBlit` existe).

### 4.2 UX
- Outil Baguette (voisin du Seau) ; panneau : tolérance slider
  (défaut 15 %), source (calque/composite, segmenté), mode
  (remplacer/ajouter/soustraire, segmenté 48dp), toggle
  `adoucir 1px` (applique §5 en 1 tap).
- Geste : tap = sélectionner (fourmis immédiates, overlay §V+1) ;
  drag = tolérance temporaire. Menu Sélection inchangé
  (tout/inverser/+1px/désélectionner) + entrée `baguette_hint`.
- Vide (tap sur zone unie à tol 0 → 1px ?) : seuil minimal
  4×4px, en-deçà = hint « élargissez la tolérance » (pas de
  sélection invisible).

### 4.3 Moteur vs app-side : **moteur** (même noyau que §1)
- Réutilise `fill.h` en mode **masque-en-sortie** : nouveau
  header **`engine/include/openclip/select.h`** OU fonction
  sœur dans `fill.h` (trancher à l'écriture d'ARCHITECTURE.md —
  recommandé : `fill.h` + 1 fonction, pas de header neuf) :
  ```cpp
  // Région contiguë autour de (x,y) sur la SOURCE (calque ou
  // composite fourni par le shim) -> écrit 0/255 dans maskOut
  // (pleine résolution). combine : 0=remplacer,1=ajouter,
  // 2=soustraire. Même métrique luma+tol que floodFill §1.
  bool magicWand(const Surface& src, int x, int y, float tolerance,
                 bool sampleMerged /* doc shim-side */,
                 int combine, std::uint8_t* maskOut, int maskStride,
                 std::string& error);
  ```
- Perf : scanline tuilée comme §1 (pas de pull plein-rez côté app
  à chaque tap si source = calque actif — le shim passe le
  pointeur direct ; si source = composite, 1 pull + noyau).

### 4.4 API JNI proposée
- `nMagicWand(handle, x, y, json): Boolean` — json
  `{tolerance:0..1, source:"layer"|"merged", combine:"set"|"add"|
  "sub"}`. Écrit le masque **moteur** V+2 (session-global, effacé
  aux changements de trame/load — contrat V+2 conservé) ET miroir
  app-side pour les fourmis (même double-chemin que
  `nSetSelectionMask`, §V+2). `false` si seed hors canvas.
- Alternative 0-JNI (repli si perf app-side OK sur tablette) :
  pull composite + flood Kotlin + `nSetSelectionMask` existant.
  Trancher au prototype (mesure sur 2048px : si < 150 ms Kotlin,
  repli accepté — noter le choix en ARCHITECTURE.md).

### 4.5 Undo / persistance
- Sélection = **pas d'undo moteur** (métadonnée, comme V+1 :
  session-only, fermer = perdre, annoncé). Canaux nommés V+3
  inchangés (persistance PNG + booléens, FUTUR si besoin baguette
  → canal).
- Pixels : aucun (la baguette ne peint jamais).

### 4.6 Tests à ajouter
- Engine (+5) : `wandExactBoundary`, `wandToleranceExpands`,
  `wandAddSubtract` (combine), `wandBadSeedNoOp`,
  `wandMaskFullRes` (dimensions = canvas).
- Instrumentés (+2) : `wandSelectsAndAntsShow`
  (masque non-vide + probe overlay) ; `wandThenStrokeConstrained`
  (baguette → trait → hors-sélection intact — le test
  baguette+sélection bout-en-bout).

### 4.7 Dépendances JNI
- 1 symbole (`nMagicWand`) + §T1 avant code — OU 0 si repli
  app-side validé par mesure (§4.4). Noyau partagé avec §1
  (1 seule implémentation flood, 2 enveloppes).

---

## 5. Contour / flou de sélection + masque rapide

### 5.1 Périmètre
- **Livre** : (a) **contour progressif paramétrable**
  (ex-`+1px` fixe → rayon 0–32px), (b) **flou de sélection**
  (= contour progressif fort + nom honnête, même opérateur),
  (c) **masque rapide** : toggle session qui teinte la sélection
  en rouge translucide + permet de **peindre le masque au
  pinceau** (blanc = ajouter, noir = retirer), re-toggle = retour
  aux fourmis.
- Remplace `select_feather` (+1px) par `select_feather_radius`
  (slider) — migration string documentée, pas de code ici.
- **Hors périmètre** : flou tuilé moteur complexe (le rayon
  32px sur masque 1-bit = blur séparable banal, voir §5.3).

### 5.2 UX
- Menu Sélection : `Contour progressif…` ouvre un dialogue
  slider 0–32px + aperçu (overlay) + Valider/Annuler (1 undo
  app-side : snapshot du ByteArray masque).
- Masque rapide : bouton toggle dans le panneau Sélection
  (icône voile, ≥ 48dp) + raccourci clavier physique V+3
  (lettre libre, ex. `Q` — pas de conflit tablette) ; actif =
  teinte rouge 50 % hors-sélection + pinceau peint le masque
  (couleur forcée N/B selon mode, taille/opacité du pinceau
  courant réutilisées) ; hint « masque rapide : blanc ajoute,
  noir retire ».
- Fourmis ↔ voile : même masque, 2 rendus (pas de double
  source). Zoom/pan/rotate suivent (§V+1 §1.3, réutilisé).

### 5.3 Moteur vs app-side : **app-side** (recommandé)
- Masque = `ByteArray`/`Bitmap.ALPHA_8` app-side V+1 :
  contour/flou = **flou gaussien séparable** Kotlin sur le
  masque (rayon ≤ 32px, canvas ≤ 2048px = ~4 Mpx, < 200 ms en
  natif tab — mesuré au prototype, sinon passer en moteur).
- Peinture masque rapide = garde existante inversée (les
  estampilles vont au masque au lieu des pixels, pattern M5
  `maskTarget` — mais côté app : écrire dans le ByteArray via
  le même interpolateur de stamps, pas de JNI).
- **Si** la mesure dépasse 300 ms sur tablette cible, ALORS
  documenter en ARCHITECTURE.md (avant code) :
  `nFeatherSelection(handle, radius): Boolean` (blur du masque
  moteur V+2) — 1 symbole, même contrat undo/persistance.
  Choix tranché à la mesure, pas dans cette spec.

### 5.4 Undo / persistance
- Undo : pile app-side (snapshot masque avant chaque op,
  profondeur 20, session-only — pas d'undo moteur, pas de
  conflit M7).
- Persistance : **session-only** (contrat V+1 : fermer = perdre).
  Canaux nommés V+3 = chemin de sauvegarde explicite existant.

### 5.5 Tests à ajouter (instrumentés, 0 engine si app-side)
1. `selectionFeatherRadius` — rect net, rayon 8 : bord = rampe
   (ni 0 ni 255 sur la frontière), centre intact, extérieur
   intact.
2. `selectionFeatherZeroIdentity` — rayon 0 = byte-identique.
3. `quickMaskPaintEdits` — toggle rapide, trait blanc agrandit
   (masque ≥ avant), trait noir réduit, re-toggle = fourmis.
4. `quickMaskUndoRestores` — undo app-side = masque identique.
- Compte : +4 instrumentés.

### 5.6 Dépendances JNI
- **0 en nominal** (recommandé). 1 conditionnel (§5.3) si mesure
  KO. Pas de header moteur en nominal.

---

## 6. Unsharp + balance couleurs + seuillage (registre `nApplyFilter`)

### 6.1 Périmètre
- **Livre** : 3 filtres manquants de 6.8/6.9, tous dans le
  registre existant (0 symbole JNI) :
  - **unsharp** (vrai masque flou paramétrable : rayon + seuil —
    `sharpen` actuel = amount seul contre blur σ=1.0, pas
    paramétrable) ;
  - **balance couleurs** (3 plages ombres/moyens/clairs ×
    cyan-rouge/magenta-vert/jaune-bleu, modèle par plages, pas
    de roue pigmentaire) ;
  - **seuillage** (binaire N/B par luma + seuil, option
    dithering Bayer-8 comme le GIF V+2).
- Complètent 6.8 (brightness/contrast/hue/saturation/levels) et
  6.9 (curves/posterize/gradientMap) : après T1, plus aucun
  « corrections » ABSENT hors dégradé-inverse/accord (§T5).
- Sous-masque V+1 : via le pont existant (pleine couche + mix
  sous masque, §V+1 §1.1/§1.5 — aplatissement + reset undo
  annoncés, inchangé).

### 6.2 UX
- `FilterDialog` existant + 3 entrées (preview + file undoable,
  pattern `applyFilterKnownPosterizeModifiesComposite`) :
  - Unsharp : sliders rayon 0.5–10px (défaut 2), intensité
    0–200 % (défaut 60 %), seuil 0–32 (défaut 2, anti-bruit).
  - Balance : 3 sections repliables (ombres/moyens/clairs) × 3
    sliders −100..+100 (défaut 0) ; boutons `neutre` par section.
  - Seuillage : slider seuil 0–255 (défaut 128) + toggle
    dithering (défaut OFF) + aperçu N/B.
- Plages balance = poids luma (ombres = luma basse, etc.,
  recouvrement doux — formule documentée dans l'aide, pas de
  magie).

### 6.3 Moteur vs app-side : **moteur** (registre existant)
- Étendre `filters.h` (mêmes règles : clés inconnues ignorées,
  défauts documentés, alpha jamais touché, rgb ≤ alpha) :
  ```
  unsharp  {"radius":2.0, "amount":0.6, "threshold":2}
           out = c + amount*(c - gauss(c,radius)) si
           |c-blur| > threshold (par canal) ; radius>0, amount>=0
  colorBalance {"shadows":"0,0,0","midtones":"0,0,0",
                "highlights":"0,0,0"}  triplets cr,mv,jb −100..+100
           poids luma doux par plage, ajout par canal adverse
           (cr:+r/−g−b etc., clampé)
  threshold {"level":128, "dither":0}  luma Rec.601 >= level ?
           blanc : noir ; dither!=0 = Bayer-8 (même matrice que GIF)
  ```
- Balance : modèle « 3 plages × 3 axes » (pas de science des
  couleurs exotique) ; formules from-scratch, verrouillées par
  tests invariants (neutre = identité, extrêmes = clampés).

### 6.4 API JNI proposée : **AUCUNE** (0 symbole)
- `nApplyFilter` existant route les 3 noms (M3 pattern :
  `canApply` +1…3, `filterNames` +1…3). `ARCHITECTURE.md` :
  paragraphe T1 (pas de § symbole), `check_jni_symbols.sh`
  inchangé.

### 6.5 Undo / persistance
- Undo : pattern filtre (snapshots pleine couche, 1 pas).
- Persistance : pixels commis (filtres = destructifs annoncés,
  comme M3 ; le non-destructif = calques d'ajustement M8/V+2,
  inchangés).

### 6.6 Tests à ajouter
- Engine (+9) : unsharp — `unsharpIdentityAtZero`
  (amount 0 = byte-identique), `unsharpSharpensEdge`
  (contraste de marche ↑), `unsharpThresholdCalmsFlat`
  (plage unie + seuil haut = quasi-intacte) ; balance —
  `balanceNeutralIdentity` (0,0,0 ×3 = identique),
  `balanceShadowsOnlyDark` (clairs intacts ±1),
  `balanceClampNoOverflow` (rgb ≤ alpha partout) ; seuillage —
  `thresholdBinary` (que 0/255), `thresholdLevelOrders`
  (seuil 0 ≈ tout blanc, 255 ≈ tout noir),
  `thresholdDitherBounded` (moyenne locale ≈ seuil ±10 %).
- Instrumentés (+2) : `applyFilterKnownUnsharpModifiesComposite`
  (pattern posterize existant) ; `thresholdMasked`
  (pont sélection : hors-masque byte-identique).

### 6.7 Dépendances JNI
- **0**. Seul `filters.h`/`filters.cpp` + tests grandissent.
  Doc : 1 paragraphe ARCHITECTURE.md (registre, pas de symbole).

---

## 7. Ordre de livraison + gates

1. **Filtres §6 d'abord** (S) : 0 JNI, 0 risque contrat —
   débloque `FilterDialog` + pattern tests filtres.
2. **Seau §1 + baguette §4** (M+M, même noyau) : 1–2 symboles,
   `fill.h` (+`select.h` ou non) + §T1 avant code.
3. **Dégradé §2 + figures §3** (M+M) : 2 symboles, headers
   purs, previews overlay-only.
4. **Sélection §5 en dernier** (S) : 0 JNI nominal, 4 emu ;
   s'appuie sur baguette (§4) pour « adoucir puis peindre ».
5. Chaque item = 1 slice (pattern slices 0–6) + extension
   `ARCHITECTURE.md` **avant** code moteur + gate locale
   `emutest.sh` verte + `prepush.sh` avant push (AGENTS.md).

**Totaux visés fin T1** : engine +32 (≈116 → **148** :
6+6+6+5+0+9), émulateur +13 (≈150 → **163** :
2+2+1+2+4+2). Restent hors T1 : calque dégradé/remplissage
vivant, sélection globale non-contiguë, balance pigmentaire,
flou tuilé moteur (si mesure §5.3 KO → 1 symbole) — ni avancés
ni retirés.

## 8. Risques

- **Noyau flood partagé (§1+§4)** : 2 enveloppes, 1
  implémentation — diverger = 2 bugs. Mitigation : `fill.h`
  unique, tests miroirs (§1.6 ↔ §4.6), relecture noyau avant
  slice §4.
- **Stack overflow flood** : récursion interdite (file scanline
  + budget visites = W×H bits, OOM = false + intact, testé).
- **Perf dégradé libre** : pondération IDW en O(N·stops) par px
  (≤ 8 stops, borné) — tablette OK ; si > 500 ms sur 2048px,
  grille grossière + bilinéaire (noté en slice, pas ici).
- **Mix sous masque via `nLoadDocument`** (§6 + V+1 §1.1) :
  reset undo + aplatissement annoncés. Mitigation : trame
  dupliquée (`nDuplicateFrame` → mix → delete l'originale),
  pattern V+1/V+2 existant, jamais de variante silencieuse.
- **`sampleMerged` (§1/§4)** : 1 pull composite avant noyau —
  coût 1 trame, pas de boucle pull. Documenté dans le dialogue
  (source visible, jamais silencieuse).
- **Balance vs daltonisme** : sliders cr/mv/jb seuls =
  inaccessibles à certains. Mitigation : valeurs numériques +
  aperçu partagé (pas de reliance couleur-seule).
- **Aucun risque clean-room** : flood/IDW/Bayer/gauss/balance
  = algos publics from-scratch, stops `#RRGGBB:pos` maison,
  fontes/couleurs système, ORA inchangé (pixels commis).
- **Portée JNI** : max 4 symboles neufs
  (`nFloodFill`/`nMagicWand`/`nPaintGradient`/`nStampShape`,
  §5 à 0, §6 à 0) — si revue ARCHITECTURE.md préfère 1 symbole
  générique `nPaintOp(handle, json)` routant les 4 ops, les
  tests §1.6–§4.6 restent valides (seule l'enveloppe change).
