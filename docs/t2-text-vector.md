# T2 — Texte + vectoriel avancé (SPEC UNIQUEMENT, aucune modif de code)

> **Statut : spec de cadrage, zéro code.** Tranche T2 du plan
> `docs/parity-gap.md` §T2 (5 items) : contour + multi-polices + import
> de fontes, saisie inline + vertical + césure, retracer/connecter/
> trajectoires, style par tracé + sommets, PSD-texte + calque
> phylactère dédié.
> Règles projet (AGENTS.md) : vocabulaire maison, FR/EN ressources,
> clean-room (aucune string/asset tiers), **JNI gelé — toute extension
> moteur est documentée en `ARCHITECTURE.md` avant le code**,
> `engine/include/openclip/` seul contrat public, `make test` vert,
> gate locale `emutest.sh`.
> Ordre de lecture : cette spec → `docs/parity-gap.md` §T2 →
> `docs/v1-selection-texte-gomme.md` §2 (fiche texte V+1) →
> `docs/v2-bd-export.md` §1 (cases/bulles) + §3.3 (SVG) + §4.3 (Bézier)
> → `ARCHITECTURE.md` (V+1/V+2/V+3 + §T1, à étendre en §T2 avant code).

État de départ vérifié sur master : fiche texte opaque V+1
(`nSetLayerText` + `nBlitPngIntoLayer`, layout Canvas système, commit
pixels, attrs ORA `x-openclip-text|font|size|style|align|leading|
tracking|color|box`) ; vecteur M6 (`nAppendVectorPath`,
`nVectorEraser`, `nUndoVector`, `nVectorPaths` getter V+2) avec
rasterisation **noire opaque par calque** (`rasterizeVectorPaths(...,
0,0,0)` — per-path color explicitement « M7+ work », c'est la présente
tranche qui la livre) ; édition Bézier V+2 = densification Catmull-Rom
app-side ; cases/bulles V+2 = convention `kind` app-side
(`"panel"`/`"bubble"`) + texte lié par index ; PSD V+2 = couches
éligibles + `nPsdApproxCount` (texte/bulle = aplatis + comptés
aujourd'hui).
Base réelle : engine **116** tests, émulateur **150** `@Test`.

Conventions T2 (valent pour les 5 items) :
- **Contrat alpha M3** : toute peinture reste prémultipliée-safe
  (rgb ≤ alpha). Seuls texte-commits et remplissage bulle (peinture)
  modifient l'alpha **par design**.
- **Undo** : écriture pixels = snapshots tuiles pleine couche
  (pattern filtre M3, 1 pas) ; ops vectorielles = snapshot
  `VectorHistory` pleine couche vectorielle (pattern M6, 1 pas) ;
  per-frame M7 (changer de trame = autre pile).
- **Fiches opaques** : le moteur ne parse jamais le contenu texte/
  police/bulle (pattern M8/V+1 : stocke, round-trip, ignore
  l'inconnu). Règle M3 : clés inconnues ignorées, manquantes =
  défauts documentés.
- **Overlay-only** : inline, trajectoires, poignées, queue de bulle
  = `Canvas` Compose par-dessus `EngineSurface`, jamais dans les
  pixels moteur (même règle que fourmis/curseur/pipette).
- **Coordonnées** : tout vit en pixels moteur (`screenToEngine()`,
  rotation-aware) ; overlay fait l'inverse via la matrice de vue.
- **Seuils tactiles** : cibles ≥ 48dp ; sliders + champs numériques ;
  gestes stylet-first.
- **Strings** : clés FR + EN dès la spec, vocabulaire maison
  (contour, chasse, œil, queue, pointe, sommet) — aucun terme tiers.

---

## 1. Contour + multi-polices (runs) + import `.ttf/.otf` libres

### 1.1 Périmètre
- **Livre** : (a) **contour de caractères** (trait autour du glyphe,
  0–8px + couleur, toggle) ; (b) **runs multi-polices** : la fiche
  texte porte des plages stylées `[{start,len,font,style,size,color}]`
  au lieu d'un style global ; (c) **import de fontes** `.ttf`/`.otf`
  depuis fichiers (SAF → bibliothèque maison) — **libres uniquement**
  (l'utilisateur confirme la licence libre à l'import ; origine
  loggée dans le registre, même esprit que `third_party/README.md`).
- Mise en page **toujours app-side** (pas de HarfBuzz/Freetype moteur —
  règle single-file maintenue) : le layout devient un **moteur de
  lignes maison** (mesure par run via `Paint.getTextWidths`,
  découpe par mots, `StaticLayout` remplacé — contrôle total des
  runs, fonction pure testable).
- **Hors périmètre** : ligatures complexes / bidirectionnel
  (Canvas système seul, honnête), chasse variable par fonte,
  graisse synthétique au-delà du B/I système.

### 1.2 UX
- `TextPanel` : toggle contour (épaisseur 0–8px + couleur, défaut
  OFF) ; **éditeur de runs** : sélection d'une plage dans le champ
  → barre « appliquer à la sélection » (fonte, B/I, taille,
  couleur) ; liste des fontes = système + bibliothèque importée
  (badge `maison` sur les importées).
- Import : bouton `+` → SAF `font/*` → copie `fonts/` cache +
  `Typeface.createFromFile` ; refus honnêtes : > 5 Mo, ni ttf ni
  otf, fichier illisible (hint, pas de crash).
- Rendu contour : `Paint.Style.STROKE` sous la passe remplissage
  (même commit `nBlitPngIntoLayer`, 1 undo) ; aperçu live overlay.
- Strings : `text_outline`, `text_run_apply`, `font_import`,
  `font_substituted`, `font_too_large` — FR + EN.

### 1.3 Moteur vs app-side : **app-side + fiche étendue, 0 JNI**
- `nSetLayerText` JSON grandit (pattern `curves` V+2, mêmes
  symboles) : `outline:{w:0..8, color:"#RRGGBB"}` (défaut OFF),
  `runs:[{start,len,font,style,size,color}]` (vide/absent = style
  global actuel, rétrocompatible byte-identique), `fontfile` (nom
  du fichier embarqué, voir §1.5).
- Moteur : `writeTextAttrs`/`tagAttr` étendus
  (`x-openclip-text-runs`, `-outline`, `-fontfile`) + **embarquement
  fonte** : le writer ORA ajoute `data/fonts/<hash>.ttf` (bytes
  fournis par l'app via le canal existant — à trancher à l'écriture
  d'`ARCHITECTURE.md` : soit l'app écrit le zip, soit le moteur
  accepte les bytes ; dans les deux cas **0 symbole neuf** si le
  canal existant suffit, sinon 1 documenté avant code).
- Fallback : fonte absente au reload → `sans-serif` + bandeau
  `font_substituted` (pattern V+1 §2.4 conservé) ; pixels toujours
  gardés.

### 1.4 API JNI proposée : **AUCUNE** (nominal)
- Extension JSON opaque de `nSetLayerText` (règle M3). Si
  l'embarquement fonte exige un canal bytes neuf, 1 symbole
  documenté en §T2 avant code (repli : fonte non embarquée =
  pixels seuls + bandeau, honnête).

### 1.5 Undo / persistance
- Undo : commit = 1 pas (snapshots pleine couche, pattern V+1).
- ORA : attrs `x-openclip-text-runs|outline|fontfile` + bytes
  `data/fonts/` (lecteurs tiers : PNG visible, attrs/fichiers
  inconnus ignorés = dégradé propre). `nLayerState` `"text"`
  grandit des mêmes clés (badge `Tx` + ré-édition inchangés).
- PSD : voir §5 (la fiche §1 est la source du `TySh`).

### 1.6 Tests à ajouter
- Engine (+4) : `textRunsRoundTrip` (fiche runs → ORA → reload
  identique) ; `textRunsMalformedIgnored` (runs hors-bornes =
  style global, pixels intacts) ; `textFontfileMissingFallback`
  (fichier absent = calque normal, pixels gardés) ;
  `textOutlineOpaque` (contour = donnée opaque, composite
  inchangé côté moteur).
- Instrumentés (+4) : `textOutlineCommitsStroke` (commit avec
  contour : périmètre du glyphe ≠ fond) ; `textRunsCommitMixed`
  (2 runs, 2 fontes : plus de pixels qu'1 run — non-trivialité) ;
  `fontImportRoundTrip` (ttf in-code copié : fiche + reload) ;
  `fontFallbackBanner` (fonte inconnue → `sans-serif` + bandeau).

### 1.7 Dépendances JNI
- **0 en nominal.** Seuls `canvas.h` (fiche), `ora.cpp` (attrs +
  embarquement) + tests grandissent.

---

## 2. Saisie inline + vertical + césure simple

### 2.1 Périmètre
- **Livre** : (a) **saisie inline** : le champ d'édition vit
  **sur le canvas** (ancré au cadre texte, suit la vue), pas dans
  un panneau séparé ; (b) **texte vertical** (mode `dir:"v"` :
  colonnes haut→bas, droite→gauche, style manga ; latin = pivoté
  90°, honnête tategaki simplifié) ; (c) **césure simple** FR/EN :
  fonction pure `hyphenate(mot, langue)` rule-based from-scratch
  (coupe entre consonne et consonne+voyelle, jamais après la 1re
  ni avant les 2 dernières lettres, mots ≤ 5 lettres intouchés,
  tiret affiché) — pas de dictionnaire, documenté approximatif.
- **Hors périmètre** : IME complexe CJK, justification
  inter-caractères fine, césure par dictionnaire (FUTUR si besoin).

### 2.2 UX
- Double-tap / outil Texte sur calque texte = **champ inline**
  (Compose `BasicTextField` en overlay, position = cadre texte →
  écran via la matrice de vue, suit pinch/pan/rotate en direct) ;
  Valider = commit (1 undo), Échap/2-tap hors champ = annuler
  (aucun pixel), le panneau `TextPanel` reste pour les réglages
  (runs §1, contour, vertical, césure).
- Vertical : toggle `dir h|v` dans `TextPanel` (segmenté 48dp) ;
  overlay inline pivote en colonnes ; commit via le même pont.
- Césure : toggle par calque (`hyphen:true|false`, défaut OFF —
  jamais de surprise typographique) ; mots coupés = tiret
  + retour ligne dans le layout §1.3.
- Strings : `text_inline_hint`, `text_dir_h/v`, `text_hyphen` —
  FR + EN.

### 2.3 Moteur vs app-side : **app-side pur, 0 JNI**
- Layout vertical + `hyphenate()` = pur Kotlin (fonction pure,
  tests JVM/instrumentés) ; fiche `nSetLayerText` grandit de
  `dir` + `hyphen` (clés opaques, règle M3) ; commit =
  `nBlitPngIntoLayer` existant.

### 2.4 API JNI proposée : **AUCUNE.**

### 2.5 Undo / persistance
- Undo : commit/validation = 1 pas (pattern V+1) ; frappe inline =
  hors undo (overlay, comme la prévisualisation dégradé T1).
- ORA : attrs `x-openclip-text-dir|hyphen` (même dégradé propre) ;
  `nLayerState` suit.

### 2.6 Tests à ajouter (instrumentés + unit, 0 engine)
1. `hyphenateFrRules` — « papillon » → coupes {pa-pil-lon},
   « rue » intact, « a » intact.
2. `hyphenateEnRules` — « butter » → {but-ter}, mots ≤ 5 intacts.
3. `inlineCommitIsOneUndo` — frappe inline + Valider : composite
   change, 1 undo restaure.
4. `verticalTextStacksColumns` — `dir:"v"` : hauteur > largeur du
   bloc (colonnes, pas ligne).

### 2.7 Dépendances JNI
- **0.** Fiche JSON + layout Kotlin + overlay.

---

## 3. Retracer / ajuster épaisseur + connecter lignes + trajectoires permanentes

### 3.1 Périmètre
- **Livre** (comblement 8.6 PARTIEL + 8.5 + 8.7) : (a) **retracer** :
  redessiner à main levée près d'un tracé existant remplace sa
  géométrie (style conservé) ; (b) **ajuster épaisseur** : geste
  le long d'un tracé = gonfle/dégonfle localement le profil de
  largeurs (falloff gaussien autour du doigt) ; (c) **connecter** :
  fusion de 2 tracés dont les extrémités se touchent (tolérance)
  en 1 tracé rééchantillonné ; (d) **trajectoires permanentes** :
  toggle d'affichage des lignes centrales + points de **tous** les
  tracés du calque (pas seulement en édition).
- Seuil honnête : retracer/connecter sur tracés du **même calque**
  vectoriel uniquement ; `eraser-stroke` exclus (pas de retracer
  de gomme).

### 3.2 UX
- Mode Édition vecteur (existant V+2 §4.3) + 3 sous-outils
  (segmenté 48dp) : `retracer` (drag près d'un tracé = highlight
  < 12px puis remplacement au relâcher), `épaisseur` (drag le
  long = curseur local ×/÷, molette verticale = intensité),
  `connecter` (tap 2 extrémités ou drag de l'une vers l'autre ;
  snap magnétique < 12px).
- Trajectoires : toggle œil dans le panneau vecteur (overlay
  lignes fines + points, couleur maison, suit la vue) ; par
  défaut OFF (canvas propre).
- Strings : `vector_retrace/thicken/connect`,
  `vector_trajectories`, `vector_connect_too_far` — FR + EN.

### 3.3 Moteur vs app-side : **moteur (indexé) + overlay**
- Nouveau : ops **indexées** sur `vectorData` (pattern M6 +
  `VectorHistory`, snapshot avant chaque op, 1 pas) :
  ```cpp
  // Remplace la géométrie du tracé id (style/tip/couleur conservés),
  // rééchantillonnage ~8px comme parseVectorPathJson. false + intact
  // si id hors-bornes ou json malformé.
  bool replaceVectorPath(Canvas&, VectorHistory&, std::size_t li,
                         std::size_t id, const std::string& pathJson,
                         float curveExp, std::string& error);
  // Fusionne a+b si dist(extrémité,extrémité) <= tol (défaut 12px,
  // param json) : concatène dans le sens qui minimise la couture,
  // rééchantillonne, supprime b. false + intact si trop éloignés.
  bool connectVectorPaths(Canvas&, VectorHistory&, std::size_t li,
                         std::size_t a, std::size_t b, float tol,
                         std::string& error);
  // Remplace le profil de largeurs du tracé id (même taille que pts,
  // valeurs > 0, clampées 0.5..128px). Le geste (falloff) est calculé
  // app-side : le moteur pose, ne dessine pas de geste.
  bool setVectorWidths(Canvas&, VectorHistory&, std::size_t li,
                       std::size_t id, const std::vector<float>& widths,
                       std::string& error);
  ```
  (`vector.h`, testés par `test_vector.cpp` ; `id` = positionnel
  dans `vectorData`, documenté comme tel.)
- Trajectoires = overlay app-side via `nVectorPaths` existant
  (0 JNI) ; retracer-highlight = même lookup (plus proche < 12px).

### 3.4 API JNI proposée (à documenter en ARCHITECTURE.md §T2)
- `nReplaceVectorPath(handle, index, id, pathJson): Boolean`
- `nConnectVectorPaths(handle, index, a, b, tolJson?): Boolean`
  (json `{tol:12}` optionnel, règle M3 — ou param float, trancher
  à l'écriture d'`ARCHITECTURE.md`)
- `nSetVectorWidths(handle, index, id, widthsJson): Boolean`
  (`widths:[...]`, taille doit égaler pts, sinon false + intact)
- 3 symboles, `check_jni_symbols.sh` + CI mis à jour.

### 3.5 Undo / persistance
- Undo : 1 pas par op (snapshot `VectorHistory`, pattern M6).
- Persistance : voir §4.5 (sibling JSON — les ops §3 modifient
  des tracés qui y survivent).

### 3.6 Tests à ajouter
- Engine (+7) : `replaceKeepsStyle` (géométrie change, tip/
  couleur conservés) ; `replaceBadIdNoOp` ; `connectJoinsEnds`
  (a+b proches = 1 tracé, longueur ≈ somme) ;
  `connectTooFarNoOp` (false + 2 tracés intacts) ;
  `widthsRescale` (profil posé = rendu épaissi au composite) ;
  `widthsBadSizeNoOp` ; `vectorOpUndoRestores`
  (chaque op + undo = byte-identique).
- Instrumentés (+4) : `retraceReplacesAndUndo`
  (replace via JNI → composite change → undo restaure) ;
  `connectMergesTwoPaths` ; `thickenWidensRendering` ;
  `trajectoriesOverlayFollowsView` (toggle + zoom/rotate : probe
  `vectorOverlayForTest()` sur les tracés).

### 3.7 Dépendances JNI
- **3 symboles** + §T2 avant code + `vector.h`/`vector.cpp`.
  0 lib externe (rééchantillonnage = code M6 existant).

---

## 4. Pointe / taille / couleur par tracé + bascule sommets

### 4.1 Périmètre
- **Livre** (comblement 8.1 PARTIEL + 8.3 + 8.4) : chaque tracé porte
  son style : **pointe** (`tip:"round|flat"`, défaut `round`),
  **taille** (facteur 0.25–4× appliqué au profil pression, défaut
  1), **couleur** (`#RRGGBB` par tracé, défaut encre de session —
  met fin au « noir opaque par calque » M6) ; **bascule sommets** :
  flag par point `corner` (dur = polyline franche, lisse =
  Catmull-Rom à la densification, défaut lisse).
- `eraser-stroke` : tip/taille s'appliquent (largeur d'effacement),
  couleur ignorée (documenté).

### 4.2 UX
- Panneau vecteur : sélecteur pointe (2 icônes 48dp), slider
  taille (défaut 1×), couleur (réutilise `ColorPanel`) =
  **style du prochain tracé** + « appliquer à la sélection »
  (tracés highlightés) via `nSetVectorStyle`… non — via
  `nReplaceVectorPath`-style : le style se pose à l'append
  (`pathJson` étendu) et se ré-applique par remplacement
  (§3, les ops indexées conservent le style sauf ordre
  contraire — json `style:{...}?` optionnel sur replace).
- Sommets : en mode Édition, tap point = bascule dur/lisse
  (icône change : carré vs rond) ; poignées existantes V+2
  respectent `corner` (un point dur coupe la Catmull-Rom —
  densification par segments entre durs).
- Export SVG V+2 suit : `stroke-linecap` (round/butt),
  `stroke` = couleur du tracé (fini l'« approximé largeur
  moyenne » seul — la couleur devient exacte).
- Strings : `vector_tip_round/flat`, `vector_size`,
  `vector_vertex_sharp/smooth` — FR + EN.

### 4.3 Moteur vs app-side : **moteur (fiche tracé), 0 symbole neuf**
- `VectorPath` grandit : `tip`, `sizeMul`, `r,g,b`,
  `corners: vector<char>` (même taille que `pts`) :
  ```cpp
  struct VectorPath {
      std::vector<Vec2> pts;
      std::vector<float> widthProfile; // × sizeMul au raster
      std::string kind;                // + conventions panel/bubble
      std::string tip = "round";       // "round" | "flat"
      float sizeMul = 1.0f;            // 0.25..4
      std::uint8_t r = 0, g = 0, b = 0; // couleur du tracé
      std::vector<char> corners;       // 1 = sommet dur
  };
  ```
- `parseVectorPathJson` lit les clés neuves (règle M3 :
  inconnues ignorées, `tip` inconnu = `round`, taille hors
  bornes = 1, couleur inparsable = encre session) ;
  `rasterizeVectorPaths` perd ses params `r,g,b` (couleur par
  tracé ; signature interne — **pas de JNI impacté**) ;
  `flat` = stamps carrés (couverture AA existante).
- `nVectorPaths` getter V+2 rend les champs neufs
  (`tip/size/color/corners`) — lecture seule étendue, pas de
  symbole neuf.

### 4.4 API JNI proposée : **AUCUNE**
- `nAppendVectorPath` JSON étendu (pattern M3) ;
  `nReplaceVectorPath` §3 accepte `style:{...}?` optionnel.

### 4.5 Undo / persistance — **le sibling JSON vectoriel**
- Constat honnête : aujourd'hui un calque vectoriel s'écrit en
  ORA comme PNG de `l.surface` (**vide** — les tracés sont
  perdus au reload, seul le composite les a vus). T2 ferme ce
  trou : chaque calque vectoriel écrit `data/vectorNNN.json`
  (`{paths:[{pts,widths,tip,size,color,corners,kind}]}`) +
  attr `x-openclip-vector="data/vectorNNN.json"`, **en plus**
  du PNG (lecteurs tiers : PNG + attr inconnu ignoré).
  Fichier absent/illisible au reload = PNG seul (dégradé
  propre, jamais de calque cassé). Cap : 256 Ko par calque
  (au-delà = PNG seul + compteur visible, comme `.clip`
  palier b).
- `nLayerState` : inchangé (les styles se relisent via
  `nVectorPaths`).

### 4.6 Tests à ajouter
- Engine (+6) : `vectorColorPerPath` (2 tracés rouge/bleu :
  pixels des deux teintes au composite) ;
  `vectorTipFlatSquares` (extrémités plates ≈ coins, pas de
  demi-disques) ; `vectorSizeMulScales` (2× ≈ largeur double
  ±1px) ; `vectorCornersRoundTrip` (fiche → sibling → reload
  identique) ; `vectorSiblingMissingFallsBack`
  (json absent = PNG seul) ; `vectorEraserIgnoresColor`
  (gomme = dest-out quelle que soit la couleur posée).
- Instrumentés (+4) : `vectorStyleAppendRoundTrip`
  (append stylé → `nVectorPaths` relit tip/taille/couleur) ;
  `vectorCornerTogglesSmooth` (bascule → densification change
  le composite localement) ; `vectorOraKeepsPaths`
  (save/reload : compte de tracés identique — **le test qui
  tue la perte actuelle**) ; `svgUsesPathColor`
  (export SVG : `stroke` = couleur du tracé).

### 4.7 Dépendances JNI
- **0.** `vector.h`/`vector.cpp` + `ora.cpp` (sibling) +
  `canvas.cpp` (signature interne raster) + tests.

---

## 5. Export PSD texte (fiche) + calque phylactère dédié

### 5.1 Périmètre
- **Livre** (comblement 10.10 ABSENT + 3.11 PARTIEL) : (a) **PSD
  texte** : `savePsd` écrit un enregistrement texte éditable
  (`TypeToolObjectSetting` subset honnête : nom de fonte,
  taille, couleur, cadre, chaîne UTF-16 ; runs > 1 = aplatis
  sur le style de base + **compté** dans `nPsdApproxCount`)
  **en plus** des pixels (lecteurs sans support texte : voient
  les pixels, comme aujourd'hui) ; (b) **calque phylactère
  dédié** : un calque porteur d'une fiche bulle
  (`shape: ellipse|rounded`, cadre, queue, fond, contour,
  index du calque texte lié) **rendu au composite-time**
  (fond + contour AA, pas des pixels commis) — fini la
  convention `kind` fragile V+2.
- Tables PSD : texte = `TySh`, pas de mapping blend neuf ;
  bulle = non-éligible (aplati + compté, règle V+2 §5.5).

### 5.2 UX
- Export PSD : case à cocher `inclure le texte éditable`
  (défaut ON ; OFF = pixels seuls, comme M4) + compteur
  d'approximations visible (réutilise le canal
  `nPsdApproxCount` : runs aplatis + bulles + groupes…).
- Calque bulle : outil Bulle V+2 → crée désormais un **calque
  bulle** (badge `○`, panneau : forme, fond, contour 1–32px,
  queue drag canvas, `lier un texte` = sélecteur de calque
  texte) ; déplacer la bulle = drag du calque (pas de pixels
  à réécrire) ; double-tap texte lié = inline §2.
- Strings : `psd_text_include`, `psd_approx_count`,
  `bubble_layer`, `bubble_link_text` — FR + EN.

### 5.3 Moteur vs app-side : **moteur (fiches + composite)**
- `Layer` grandit d'une fiche bulle opaque (pattern M8/V+1) ;
  rasterisation au composite via le noyau T1 `stampShape`
  (ellipse/rect-arrondi + queue triangulaire, fond + contour)
  — si T1 non livré, noyau from-scratch documenté en §T2
  (pas de duplication silencieuse : trancher à l'écriture
  d'`ARCHITECTURE.md`).
- PSD : `savePsd` écrit `TySh` minimal par calque texte
  éligible (raster non-masqué/non-écrêté, comme les couches
  V+2) ; sinon pixels + compteur.

### 5.4 API JNI proposée (à documenter en ARCHITECTURE.md §T2)
- `nSetLayerBubble(handle, index, json|null): Boolean` — json
  `{shape:"ellipse|rounded", frame:[x,y,w,h], tail:[bx,by,tx,ty]?,
  fill:"#FFFFFF", stroke:"#000000", strokeW:4, textLayer:-1}`
  (règle M3 ; null/vide = efface, redevient calque normal).
  1 symbole. ORA : attrs `x-openclip-bubble|frame|tail|fill|
  stroke|text` (même dégradé propre) ; `nLayerState` gagne
  `"bubble"` (badge `○`, panneau).
- PSD-texte : **0 symbole** (`nSaveDocument` inchangé,
  `nPsdApproxCount` existant compte les aplatissements).

### 5.5 Undo / persistance
- Undo : fiche bulle = méta (hors undo, comme `nSetLayerMeta`
  et les holds V+2 — glisser la queue n'est pas un trait) ;
  pixels du texte = 1 pas (pattern V+1).
- ORA : attrs bulle + fiche texte §1 (round-trip total :
  bulle + texte lié + runs + fonte embarquée).
- PSD : `8BPS` + couches + `TySh` (émulateur : Photoshop non
  requis — header + tag `TySh` + pixels vérifiés).

### 5.6 Tests à ajouter
- Engine (+5) : `psdTypeToolPresent` (calque texte → tag
  `TySh` + chaîne UTF-16 retrouvée) ; `psdRunsFlattenCounted`
  (runs > 1 → approx +1, style de base écrit) ;
  `bubbleCompositePaints` (calque bulle : fond blanc + contour
  au composite, dessous intact hors cadre) ;
  `bubbleNullClears` (null = calque normal) ;
  `bubbleOraRoundTrip` (fiche + texte lié re-identiques).
- Instrumentés (+3) : `psdTextLayerOpens`
  (`8BPS` + `TySh` + couches > 1 via JNI) ;
  `bubbleLayerDragMoves` (fiche frame changée → composite
  suit, 0 pixel commis — undo moteur vide) ;
  `bubbleTextLinkRoundTrip` (bulle + texte lié : ORA reload,
  index relu identique).

### 5.7 Dépendances JNI
- **1 symbole** (`nSetLayerBubble`) + §T2 avant code ;
  `psd.cpp` (TySh) + `canvas.h`/`ora.cpp` (fiche bulle).

---

## 6. Ordre de livraison + gates

1. **Style par tracé + sommets (§4)** d'abord (S, 0 JNI) :
   ferme le trou de persistance vectorielle (sibling JSON —
   le test `vectorOraKeepsPaths` verrouille la non-perte) et
   donne à §3 des tracés stylés à manipuler.
2. **Retracer/connecter/épaisseur (§3)** (M, 3 symboles) :
   `vector.h` + §T2 avant code ; s'appuie sur §4 (styles
   conservés au replace).
3. **Texte runs/contour/fontes (§1) + inline/vertical/césure
   (§2)** (M+M, 0 JNI) : layout maison + fiches étendues ;
   §2 réutilise le moteur de lignes §1 (ne pas livrer §2
   sans §1).
4. **PSD-texte + bulle (§5)** en dernier (M, 1 symbole) :
   consomme les fiches §1 (runs → TySh) ; bulle rendue via
   noyau T1 (trancher la dépendance en §T2).
5. Chaque item = 1 slice (pattern slices 0–6) + extension
   `ARCHITECTURE.md` **avant** code moteur + gate locale
   `emutest.sh` verte + `prepush.sh` avant push (AGENTS.md).

**Totaux visés fin T2** : engine 116 → **≈138**
(+22 : §1: 4, §2: 0, §3: 7, §4: 6, §5: 5) ;
émulateur 150 → **≈169** (+19 : §1: 4, §2: 4, §3: 4,
§4: 4, §5: 3). Les comptes exacts sont arrêtés par slice ;
aucun item ne réduit un total existant.

**Extensions `ARCHITECTURE.md` à écrire avant code** :
`nReplaceVectorPath` / `nConnectVectorPaths` /
`nSetVectorWidths` (§3), `nSetLayerBubble` + sémantique
`TySh` (§5), extension JSON opaque `nSetLayerText`
(runs/outline/fontfile/dir/hyphen, §1–§2), extension
`pathJson` + getter `nVectorPaths` + sibling ORA
`data/vectorNNN.json` (§4), canal bytes fonte ORA **ou**
repli documenté (§1.3). Le reste (§2 layout/césure/inline,
trajectoires overlay, SVG couleurs) est 0 JNI par
construction.

## 7. Risques

- **Layout lignes maison (§1.3)** : mesurer par run +
  découpe par mots = risque de divergences vs `StaticLayout`
  (casse, espacements). Mitigation : fonction pure + tests
  de non-trivialité emu ; bidi/CJK refusés honnêtement.
- **Embarquement fonte ORA (§1.3)** : writer moteur vs
  app — même point ouvert que V+1 §2.4. Mitigation : trancher
  à l'écriture d'`ARCHITECTURE.md` ; repli sans embarquement
  (pixels + bandeau) toujours valide.
- **`TySh` subset (§5)** : Photoshop tolère un `TySh` minimal
  mais l'éditabilité parfaite (kerning, warps) n'est pas
  promise — documenté dans le dialogue (« texte de base »).
  Mitigation : pixels toujours écrits à côté (jamais de
  calque texte sans pixels).
- **IDs positionnels §3** : `id` = index dans `vectorData` —
  invalide après undo/erase concurrents. Mitigation : l'app
  relit `nVectorPaths` avant chaque op (pas de cache d'id
  entre gestes) ; `false` + intact si périmé (testé).
- **Sibling JSON 256 Ko (§4.5)** : très grands calques
  vectoriels > cap = PNG seul + compteur. Mitigation :
  compteur visible (pattern `.clip` palier b), pas de
  silencieux.
- **Bulle + undo (§5.5)** : fiche hors undo = drag non
  annulable. Mitigation : annoncé dans l'aide (comme les
  holds V+2) ; les pixels texte restent undoables.
- **Aucun risque clean-room** : layout/césure/TySh-subset/
  IDW existant = algos publics from-scratch ; fontes =
  fichiers utilisateur (licence confirmée à l'import, origine
  loggée) ; ORA/PSD/SVG ouverts ; `.clip`-écriture toujours
  exclue.
- **Portée JNI** : max 4 symboles neufs
  (`nReplaceVectorPath`/`nConnectVectorPaths`/
  `nSetVectorWidths`, `nSetLayerBubble` ; §1/§2/§4 à 0) —
  si revue `ARCHITECTURE.md` préfère 1 symbole générique
  `nVectorOp(handle, json)` routant les 3 ops §3, les tests
  §3.6 restent valides (seule l'enveloppe change — même
  clause que T1 §8).
