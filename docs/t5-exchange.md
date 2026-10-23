# T5 — Échanges + pages + productivité (SPEC UNIQUEMENT, aucune modif de code)

> **Statut : spec de cadrage, zéro code.** Dernière tranche du plan
> `docs/parity-gap.md` §T5 (7 items) : verrous calque + alpha +
> référence + brouillon ; effet de bord + aquarelle + calque
> dégradé/remplissage + foil ; 8–10 modes de fusion manquants ;
> import `.grd` + `.abr` (fidélité approximative documentée) + accord
> chromatique ; foliotation + infos œuvre + vue double + repères de
> coupe + fanzine ; EPUB + export PDF + séquences (avec T3) ; macros
> rejouables + aligner/répartir + panneau historique + panneau info.
> Règles projet (AGENTS.md) : vocabulaire maison, FR/EN ressources,
> clean-room (aucune string/asset/format tiers copié — `.abr`/`.grd`
> lus par parseurs maison documentés honnêtes, jamais recopiés),
> **JNI gelé — toute extension moteur est documentée en
> `ARCHITECTURE.md` avant le code**, `engine/include/openclip/` seul
> contrat public, `make test` vert, gate locale `emutest.sh`.
> Ordre de lecture : cette spec → `docs/parity-gap.md` §T5 →
> `ARCHITECTURE.md` (V+2/V+3/T1→T4, à étendre en §T5 avant code).

État de départ vérifié sur master : registres existants `nApplyFilter`
(M3 + T1 `unsharp`/`colorBalance`/`threshold` + V+3 `denoise` + T4
7 créatifs + 3 optiques), règle M3 (clés inconnues ignorées,
manquantes = défauts, alpha jamais touché, rgb ≤ alpha, `false` +
intact sur erreur) ; blends M5 + V+2 (14 noms : normal, multiply,
screen, overlay, add, soft-light + hard-light, difference, color,
luminosity + 4 de base) ; ajustements M8 + courbes V+2 (composite-time,
non-destructifs) ; calques M5 (visibilité/opacité/blend/groupes/
masques/écrêtage) ; ORA/PSD M4 + V+2 (`nPsdApproxCount`) ; `.ocpages/`
V+2 (pages ORA + `project.json` v1 + `thumbs/`, une page chargée à la
fois, lecture ltr/rtl) ; GIF/WebM/APNG/séquences PNG T3 ; fiches
opaques `nSetLayerMeta` V+2 / texte V+1 / bulles T2 ; tons M10
(`nApplyTone`) ; calque dégradé/remplissage **absent** (6.14/3.5/3.6),
foil **absent** (3.13), verrous **absents** (7.14/7.15/7.12/7.16),
EPUB **absent** (12.14), export PDF **absent** (12.15 PARTIEL : import
page 1 seul), macros/historique/info **absents** (1.25/7.23/7.24).

Conventions T5 (valent pour les 7 items) :
- **Contrat alpha M3** : tout filtre/fusion/rendu reste
  prémultiplié-safe (rgb ≤ alpha). Seules les écritures pixels
  documentées (commits peinture, textures) touchent l'alpha
  **par design**.
- **Undo** : écriture pixels = snapshots tuiles pleine couche
  (pattern filtre M3, 1 pas) ; fiches (flags, fills, clés, macros,
  labels) = méta hors undo (comme holds/bulles/pins — glisser ou
  cocher n'est pas un trait) ; validation d'une op = 1 pas.
- **Fiches opaques** : le moteur stocke et rejoue, ne juge jamais le
  contenu (pattern M8/V+1). Règle M3 sur tout JSON.
- **Overlay-only** : repères de coupe, vue double, badges verrous,
  historique, loupe info = `Canvas` Compose par-dessus
  `EngineSurface`, jamais dans les pixels moteur.
- **Coordonnées** : tout vit en pixels moteur (`screenToEngine()`,
  rotation-aware).
- **Rendu propre** : aides (badges, repères, double-vue) hors
  `nFrameCompositeInto`/exports **sauf** repères de coupe et
  foliotation quand l'utilisateur les inclut explicitement (§5).
- **Seuils tactiles** : cibles ≥ 48dp ; sliders + champs numériques.
- **Strings** : clés FR + EN dès la spec, vocabulaire maison
  (verrou, alpha, référence, brouillon, bord, aquarelle, dégradé,
  remplissage, foil, accord, foliotation, œuvre, coupe, fanzine,
  macro, aligner, répartir, historique, info) — aucun terme tiers.
- **Budget JNI T5 : EXACTEMENT 2 symboles neufs**
  (`nSetLayerFlags`, `nSetLayerFill`). Tout le reste = croissance
  JSON sur symboles existants, croissance registre `nApplyFilter` /
  `blend.h`, ou app-side pur.
  Un générique `nT5Op(handle, json)` a été envisagé et REFUSÉ
  (même clause que T1 §8 / T2 / T3 / T4 : noms explicites,
  lisibilité de `check_jni_symbols.sh` et des tests par op).

Ordre de livraison : §3 → §1 → §2 → §4 → §7 → §5 → §6 (registre
d'abord : 0 JNI, valeur visible ; échanges binaires ensuite ;
productivité puis pages/exports en dernier : app-side, dépendent
de T3 et des §1–§4).

---

## 1. Verrous calque + verrou alpha + calque référence + brouillon

### 1.1 Périmètre
- **Livre** (comblement 7.14 + 7.15 + 7.12 + 7.16/7.21, tous ABSENT) :
  quatre flags par calque top-level, posés par **1 symbole neuf**,
  appliqués moteur + gardes app-side :
  - `locked` : toute écriture refusée (`false` + couche intacte) —
    strokes, `nFloodFill`, `nPaintGradient`, `nStampShape`,
    `nSelectionBlit`, `nBlitPngIntoLayer`, `nPuppetWarp`, filtres,
    tones. La sélection/masque sur un calque verrouillé reste
    lisible (overlay) mais non applicable.
  - `alphaLock` (verrou alpha) : les commits peinture ne touchent
    que les pixels déjà couverts (alpha > 0) — encre conservée où
    le calque est transparent. Équivalent documenté du « verrou
    transparence » maison.
  - `reference` (calque de référence) : visible dans l'éditeur,
    **exclu de tous les exports** (GIF/WebM/APNG/séquences PNG/
    EPUB/PDF, `nCompositeInto` d'export — même statut que
    l'onion M7 : aide, jamais sortie). Échantillonnage pipette :
    inclus (c'est son usage : relever une couleur dessus).
  - `draft` (brouillon) : comme `reference` (exclu des exports) +
    badge `D` + exclu du comptage « calques » du panneau info ;
    `Masquer brouillons` (7.21) = bascule d'affichage session-only
    app-side (ne touche pas les flags).
- **Hors périmètre** : verrou partiel (position seule / peinture
  seule), verrou de groupe hérité (le flag vit par calque ; un
  groupe verrouillé se propage à l'affichage app-side mais chaque
  couche garde son flag), verrouillage par mot de passe.

### 1.2 UX
- `LayerPanel` : 4 icônes toggle par ligne (cadenas, damier-alpha,
  punaise-référence, crayonné-brouillon), 48dp, état dans
  `nLayerState` ; tentative d'édition sur calque verrouillé =
  anneau barré + hint `layer_locked` (même garde que couches
  d'ajustement V+1) ; badges `🔒`/`α`/`R`/`D` sur la ligne.
- `draft_hide` toggle global (7.21) dans le menu du panneau.
- Strings : `layer_lock/alpha/reference/draft`,
  `layer_locked_hint`, `draft_hide/show` — FR + EN.

### 1.3 Moteur vs app-side
- **Moteur** : flags stockés sur `Layer` (`bool locked, alphaLock,
  reference, draft`, défaut false) ; garde centrale dans le shim
  avant chaque op d'écriture (1 point de contrôle, pas N `if`
  dispersés) ; `alphaLock` appliqué dans le stamp (couverture =
  alpha dst > 0, encre source-over inchangée ailleurs) ;
  `reference`/`draft` filtrés dans les chemins d'export
  (même branche que l'exclusion onion/overlay).
- **App-side** : toggles + gardes pré-appel (anneau barré) +
  `draft_hide` + badges. Aucune logique de pixels.

### 1.4 API proposée
- `nSetLayerFlags(handle, index, json): Boolean` — `{locked?,
  alphaLock?, reference?, draft?}` (booléens, M3 rule : clés
  inconnues ignorées, manquantes = inchangées). `false` + intact
  sur mauvais index ou JSON illisible. Méta hors undo.
- `nLayerState` gagne `"flags":{locked,alphaLock,reference,draft}`
  par calque top-level.
- ORA : attrs `x-openclip-locked|alphalock|reference|draft="1"`
  sur `<layer>` ( lecteurs tiers : ignorés → pixels seuls,
  même dégradation propre que M8).

### 1.5 Undoabilité
- Flags = méta hors undo (comme holds/bulles/clés T3). Les écritures
  refusées ne créent aucune entrée.

### 1.6 Persistance
- ORA round trip (§1.4) ; PSD : calques `reference`/`draft` fusionnés
  dans le record `Merged approximations` + comptés
  (`nPsdApproxCount`, règle V+2 §5.5) ; `.ocpages/` inchangé
  (flags dans chaque ORA de page).

### 1.7 Tests
- Engine : écriture sur `locked` = `false` + couche byte-identique
  (chaque op : stroke, fill, gradient, shape, blit, filter, tone) ;
  `alphaLock` : pixels transparents intacts, pixels couverts
  peints ; `reference`/`draft` absents du composite d'export,
  présents dans `nCompositeInto` éditeur ; round trip ORA des 4 flags.
- Emu : toggle verrou → stroke refusé + hint ; badge `D` ;
  `draft_hide` masque/affiche ; export sans les couches R/D.

### 1.8 Dépendances JNI
- **1 symbole neuf : `nSetLayerFlags`** (compté dans le budget §T5).
  Rien d'autre.

---

## 2. Effet de bord + bord aquarelle + calque dégradé/remplissage + foil

### 2.1 Périmètre
- **Livre** (comblement 7.4 + 7.6 ABSENT, 3.5/3.6 ABSENT, 3.13
  ABSENT) en deux moitiés :
  - (a) Filtres (0 JNI, registre `nApplyFilter`, règle M3) :
    - `edgeBorder` (effet de bord) `{"width":4 (1..32),
      "color":"#RRGGBB", "mode":"inside"|"outside"|"center"}`
      — liseré opaque le long du contour alpha du calque
      (distance-champ naïf from-scratch, réponse binaire, AA 1px
      par couverture). `outside` étend la surface (snapshots
      pleines).
    - `watercolor` (bord aquarelle) `{"darken":0.4 (0..1),
      "rough":6 (0..16), "bloom":0.3 (0..1)}` — assombrit et
      sature les bords de taches (pixels semi-couverts) avec un
      grain procédural (même Perlin §T4 `perlinClouds`, graine
      fixe) : bords irréguliers plus denses, centres lavés.
      Tous zéros = byte-identique.
  - (b) Calques de remplissage (1 symbole neuf) : calque non-pixels
    dont le contenu est **rendu à l'heure du composite**
    (pattern ajustements M8 : ne peint rien, s'applique au passage
    de la marche) — `kind` :
    - `solid` (remplissage uni) `{color}` — aplat opaque.
    - `gradient` (dégradé) `{kind:linear|radial, x0,y0,x1,y1,
      stops:[...2..8]}` — même encodage que `gradientMap` M3 /
      `nPaintGradient` T1, mais **vivant** (re-rendu à chaque
      composite, rééditable).
    - `foil` `{hue:0..360, scale:4 (1..16), shine:0.5 (0..1)}` —
      feuille métallisée procédurale from-scratch : rampe
      irisée (teinte tournante sur le dégradé) × grain Perlin
      fixe × reflets (bandes diagonales claires). Documenté
      comme évocation, pas simulation physique.
    - Masque/écrêtage/opacité/visibilité s'appliquent exactement
      comme aux calques normaux (pattern M8) ; les outils pixels
      sur un calque fill sont refusés (garde app-side, comme
      ajustements V+1).
- **Hors périmètre** : dégradé alpha multi-stops (même FUTURE que
  T1 §8 : stops opaques seuls), texture de trait (7.9 reste
  ABSENT), foil importé depuis assets tiers.

### 2.2 UX
- `FillPanel` : segmented `solid|gradient|foil` + éditeur de stops
  (ajout/suppression/déplacement, 2..8) + sliders `hue/scale/
  shine` + prévisualisation live (overlay composite, jamais pixels)
  jusqu'à **Valider** (1 pas : pose/édition de fiche) / **Annuler**.
- Filtres `edgeBorder`/`watercolor` dans `FilterDialog` (même rang
  que les T4) avec preview + queue undoable.
- Strings : `fill_solid/gradient/foil`, `fill_stops/hue/scale/
  shine`, `filter_edgeBorder/watercolor` — FR + EN.

### 2.3 Moteur vs app-side
- **Moteur** : noyau pur `openclip/fill_layer.h` (rendu
  composite-time : `renderFill(dst, layer, fill, w, h)`) + 2 noms
  au registre filtres (`edgeBorder`, `watercolor`, même contrat
  que T4 §2).
- **App-side** : éditeurs, stops, preview, gardes de refus.

### 2.4 API proposée
- `nSetLayerFill(handle, index, json|null): Boolean` — fiche fill
  sur calque top-level (null/efface = calque normal à nouveau ;
  la surface pixels existante est gardée mais ignorée tant que la
  fiche vit, restaurée à l'effacement — documenté). JSON
  `{kind:"solid"|"gradient"|"foil", color?, kind2?...}` (M3 rule).
  `false` + intact sur mauvais index / JSON illisible / stops < 2.
  Méta hors undo.
- `nLayerState` gagne `"fill":{...}|null` (badge `F`).
- ORA : attrs `x-openclip-fill|fill-stops|fill-params` (même
  dégradation propre) ; PSD : non-éligible → base + compté.

### 2.5 Undoabilité
- Filtres (a) : 1 pas (snapshots tuiles). Fiche fill (b) : méta
  hors undo (comme ajustements M8 — déplacer un stop n'est pas
  un trait) ; **pixeliser** explicite (7.19 reste PARTIEL→
  complété ici pour les fills : `Rasterize` = bake composite du
  fill dans la surface via le chemin `nBlitPngIntoLayer`, 1 pas).

### 2.6 Persistance
- ORA round trip (§2.4) ; `.ocpages/` : fills dans chaque ORA ;
  PSD : base + compté (§2.4).

### 2.7 Tests
- Engine : chaque `kind` rend un composite non-vide déterministe ;
  stops triés par le moteur ; stops < 2 = `false` + intact ;
  masque/écrêtage/opacité appliqués (même tests que M8) ;
  `edgeBorder` 3 modes (inside ne déborde pas, outside étend) ;
  `watercolor` tout-zéro = byte-identique ; round trip ORA.
- Emu : éditeur stops round trip ; pixeliser undoable ;
  `filterNames` contient les 2 noms.

### 2.8 Dépendances JNI
- **1 symbole neuf : `nSetLayerFill`** (budget §T5 : 2/2 avec §1).
  Filtres = 0 JNI (registre).

---

## 3. 8–10 modes de fusion manquants

### 3.1 Périmètre
- **Livre** (comblement 7.8 PARTIEL : 14 noms → 24) : **10 noms
  neufs** au noyau `blend.h`, from-scratch, sans symbole (pattern
  V+2 : la `LayerPanel`-liste est la seule source) :
  `darken` (assombrir), `lighten` (éclaircir), `linear-burn`
  (densité linéaire –), `linear-dodge` (déjà couvert par `add` ?
  non : `add` clampé simple vs dodge avec écrêtage — documenté
  jumeau, testé distinct), `vivid-light` (vive), `linear-light`
  (linéaire), `pin-light` (ponctuelle), `subtract` (soustraire),
  `divide` (diviser), `exclusion` (incrustation partielle).
  Formules maison verrouillées par invariants (commutativités,
  neutres, noir/blanc témoins) ; prémultiplié-safe (rgb ≤ alpha,
  alpha = source-over des alphas comme les 14 existants).
- Noms ORA/PSD : `svg:` quand le nom existe dans la nomenclature
  ouverte (`darken`, `lighten`, `color-burn`… — table explicite),
  sinon nom maison (`vivid-light`, `pin-light`, `subtract`,
  `divide`, `exclusion`) avec dégradation Normal chez les lecteurs
  tiers (même règle M5/V+2). PSD : table étendue, le reste → `norm`
  + compté (règle V+2 §5.5 inchangée).
- **Hors périmètre** : `traversant` (pass-through de groupe —
  FUTURE : demande la refonte du composite de groupes M5),
  jumeaux redondants (`dodge`/`burn` classiques quand `add` et
  `linear-burn` les couvrent — documenté, pas d'alias
  silencieux).

### 3.2 UX
- Aucun changement UI structurel : la liste des blends du
  `LayerPanel` passe à 24 noms (recherche existante 1.3 FAIT les
  couvre) ; pastille d'aperçu conservée.
- Strings : 10 clés `blend_darken/lighten/…` — FR + EN.

### 3.3 Moteur vs app-side
- **Moteur 100 %** (`blend.h` + table de noms). App-side = liste.

### 3.4 API proposée
- Aucune : `nSetLayerBlend` et `nLayerState` inchangés en
  signature (noms neufs routés par le registre, pattern V+2 §5.5).

### 3.5 Undoabilité
- Blend = méta couche (hors undo, comme aujourd'hui — changer de
  blend n'est pas un trait).

### 3.6 Persistance
- ORA round trip (noms §3.1) ; groupes/masques inchangés.

### 3.7 Tests
- Engine : 10 invariants (ex. `difference` existe déjà : `subtract`
  clampé à 0 vs signé — testé distinct ; `divide` par blanc =
  identité ; `darken`/`lighten` commutatives ; `exclusion` à 50 %
  gris = gris conservé ; neutres documentés par nom).
- Emu : `blendsExtraAccept` étendu (les 24 noms acceptés, look
  non-blanc vérifié sur 2 témoins).

### 3.8 Dépendances JNI
- **0 symbole** (registre seul, pattern V+2).

---

## 4. Import `.grd` + `.abr` + accord chromatique

### 4.1 Périmètre
- **Livre** (comblement 6.11 + 4.5 ABSENT, 6.12 ABSENT) avec
  honnêteté annoncée :
  - `.grd` (dégradés) : parseur maison from-scratch du conteneur
    ouvert documenté (stops couleur/position) → converti en stops
    maison (`#RRGGBB:pos`, 2..8, triés). Tout record non-couleur
    (bruit, transparence complexe) = **refusé avec message**
    (pas de dégradé silencieux approché) ; alpha-stops refusés
    (même FUTURE que T1 §8).
  - `.abr` (pointes) : parseur maison from-scratch (enveloppe +
    bitmaps 8-bit) → pointe tamponnée dans le moteur de brosse
    (taille/dureté/opacité, pas de dynamique propriétaire :
    inclinaison/rotation importées = **ignorées + annoncées**).
    Fidélité documentée « approximative » dans le dialogue
    d'import (jamais de promesse pixel).
  - Accord chromatique (6.12) : transfert statistique
    source→cible (moyenne/écart-type par canal, pattern Reinhard
    simplifié, from-scratch) produisant une LUT appliquée via le
    canal **ajustement `curves` existant** (V+2) — 0 moteur neuf :
    l'accord devient une fiche `curves` rééditable, pas une
    opération magique.
- **Hors périmètre** : écriture `.abr`/`.grd` (formats proprios —
  même refus que `.clip`, §8) ; colorisation auto (reste EXCLU
  cloud, §8) ; profils ICC (14.21 : pas de blob sans licence).

### 4.2 UX
- `ImportPanel` : SAF OpenDocument (`.grd`/`.abr`) → cache-file →
  parse maison → prévisualisation (rampe pour grd, tampon test
  pour abr) → **Importer** (grd → bibliothèque de dégradés
  app-side `gradients.json` ; abr → preset brosse maison).
  Dialogue d'honnêteté : `import_approx` (liste ce qui est ignoré).
- Accord : `ColorMatchDialog` — source = calque/zone mostré
  (pipette zone ou calque entier), cible = calque actif → preview
  → **Appliquer** (pose une fiche `curves`, rééditable).
- Strings : `import_grd/abr/approx/refused`, `match_source/target/
  apply` — FR + EN.

### 4.3 Moteur vs app-side
- **App-side 100 %** : parseurs Kotlin from-scratch (`GrdParser`,
  `AbrParser`, aucune lib) ; stats + LUT Kotlin pures ;
  application via symboles existants (`nSetLayerAdjustment`
  `curves`, `nSetBrush`). Aucun byte-code tiers, aucune spec
  propriétaire recopiée (structure lue à l'octet, documentée
  comme interop).
- **Moteur : rien** (les stops/pointes/LUT sont des données
  maison une fois converties).

### 4.4 API proposée
- Aucune (canaux existants). `gradients.json` v1 app-side
  (même pattern que `color-sets.json` 1.8 FAIT).

### 4.5 Undoabilité
- Import = ajout bibliothèque (undo app-side : suppression) ;
  accord = fiche `curves` (méta hors undo, rééditable/effaçable).

### 4.6 Persistance
- Dégradés importés : `gradients.json` local (comme nuances) ;
  accord : fiche `curves` → ORA round trip existant V+2 ;
  pointes : presets brosse maison (même stockage que §M2).

### 4.7 Tests
- Engine : aucun (0 moteur).
- Emu : fixtures `.grd`/`.abr` minimales **générées in-code**
  (jamais de binaires tiers) : parse OK + stops/pointe attendus ;
  fichier tronqué = refus + message ; accord sur 2 aplats =
  fiche `curves` posée + composite rapproché (distance couleur
  diminuée, pas égalité) ; mention `approx` affichée.

### 4.8 Dépendances JNI
- **0 symbole.**

---

## 5. Foliotation + infos œuvre + vue double + repères de coupe + fanzine

### 5.1 Périmètre
- **Livre** (comblement 12.4 + 12.5 ABSENT, 12.2 PARTIEL, 12.11
  ABSENT), **app-side 100 %** (0 JNI — `.ocpages/` + overlays) :
  - Foliotation : numéros auto par page (`{n}` / `{n}/{total}` /
    roman option), position (4 coins + centre-bas), police système,
    rendus **à l'export** (jamais dans les pixels ORA).
  - Infos œuvre : fiche `{titre, auteur, année, résumé, langue,
    sens}` dans `project.json` v2 (migration v1 : champs absents =
    vides, jamais cassé) → page de garde EPUB/PDF + métadonnées.
  - Vue double : affichage côte à côte (page N + N+1 selon sens
    de lecture ltr/rtl V+2) en lecture seule (zoom/pan synchrones,
    édition = retour page simple — annoncé).
  - Repères de coupe : overlay fond perdu + traits de coupe +
    zone de sécurité (marges paramétrables mm, conversion via dpi
    `nInit` 5.3 PARTIEL) ; inclus à l'export **sur opt-in**
    (`crop_marks` coché = rendus, décoché = absents).
  - Fanzine (cahier) : imposition A5-sur-A4 / A6-sur-A5 (ordre des
    pages + rotation 180° des verso, schéma affiché avant export ;
    multiple de 4 exigé — bourrage de pages blanches proposé,
    jamais silencieux).
- **Hors périmètre** : sens de reliure physique autre que
  ltr/rtl existant (12.3 PARTIEL : page de départ ajoutée ici —
  `startPage:left|right`), export par lots des compositions
  (dépend 1.29 ABSENT, FUTURE).

### 5.2 UX
- `PagesPanel` étendu : drag-reorder existant + numéros auto
  affichés + fiche œuvre (`WorkDialog`) + toggle vue double +
  réglages coupe (marges mm, fond perdu) + assistant fanzine
  (schéma pages → **Exporter**).
- Strings : `pages_folio/work/double/crop/fanzine/impose` — FR+EN.

### 5.3 Moteur vs app-side
- **App-side 100 %** : `project.json` v2 + overlays Compose +
  calculs d'imposition Kotlin purs. Le moteur ne connaît ni pages
  ni repères (seule la page chargée existe pour lui, règle V+2).

### 5.4 API proposée
- Aucune JNI. `project.json` v2 : `{..., "work":{...},
  "folio":{...}, "crop":{...}, "startPage":...}` (v1 lu sans eux).

### 5.5 Undoabilité
- Réorganisation pages = op app-side undoable (pile session,
  depth 20, pattern §7 masque rapide T1) ; fiche œuvre/folio =
  méta hors undo.

### 5.6 Persistance
- `project.json` v2 + ORA par page inchangés ; `thumbs/` régénérés.

### 5.7 Tests
- Emu : folio `{n}/{total}` sur export webtoon (2 pages → numéros
  distincts) ; `project.json` v1 migré sans perte ; vue double
  affiche 2 vignettes ; fanzine 8 pages → ordre imposé vérifié
  (table de correspondance) ; repères opt-in/out.
- Engine : aucun.

### 5.8 Dépendances JNI
- **0 symbole.**

---

## 6. EPUB + export PDF + séquences (avec T3)

### 6.1 Périmètre
- **Livre** (comblement 12.14 ABSENT, 12.15 PARTIEL→FAIT côté
  export, 13.10 PARTIEL : séquences avec T3) — **app-side 100 %** :
  - EPUB : ZIP from-scratch via `java.util.zip` (même pattern que
    le ZIP T3 §4 : pas de lib) — `mimetype` **non-compressé en
    premier** (contrainte du format, verrouillée par test),
    `container.xml` + OPF (métadonnées §5 + spine pages dans
    l'ordre + sens `page-progression-direction` depuis `reading`)
    + XHTML par page (image pleine page + folio) + images
    (composites PNG via `nCompositeInto`, repères/folio selon
    opt-ins §5). Validation : décompression + XML bien-formé.
  - Export PDF : `android.graphics.pdf.PdfDocument` (API
    plateforme, pas de lib) — 1 page PDF par page `.ocpages/`,
    taille depuis dims canvas + dpi, folio/repères selon opt-ins ;
    multi-pages natif (la limite « page 1 » de l'import reste :
    import = page 1, export = tout).
  - Séquences (avec T3) : réemploi `nExportPngSeq` + ZIP app-side
    au niveau **projet multipage** (préfixe `pNN_frame_MMMM.png`,
    holds éclatés, caméra T3 incluse) ; stickers APNG multipage =
    1 APNG par page (matte + ≤512px, preset T3 §4).
- **Hors périmètre** : EPUB3 fixed-layout audio/vidéo (images
  seules, annoncé) ; PDF vectoriel (raster par page, annoncé) ;
  import EPUB/PDF multipage (import PDF reste page 1).

### 6.2 UX
- `ExportPanel` : segmented `webtoon|GIF|WebM|APNG|séquence|
  EPUB|PDF|fanzine` + opt-ins `folio/crop_marks` + presets
  (`écran`/`impression` : maxWidth/dpi) + progression + annulation
  (même pattern que ZIP T3) ; SAF CreateDocument
  (`application/epub+zip`, `application/pdf`, `application/zip`).
- Strings : `export_epub/pdf/sequence/sticker_pages` — FR + EN.

### 6.3 Moteur vs app-side
- **App-side 100 %** : composites tirés (`nCompositeInto` /
  `nFrameCompositeInto`, caméra T3 déjà appliquée moteur) ;
  assemblage Kotlin. Aucun parseur/format tiers.

### 6.4 API proposée
- Aucune (symboles T3 + V+2 existants). Constantes documentées :
  ordre `mimetype`-premier, OPF v2 minimal, XHTML par page.

### 6.5 Undoabilité
- Export = lecture seule, jamais d'undo.

### 6.6 Persistance
- Aucune (sorties SAF) ; presets d'export en préférences app-side.

### 6.7 Tests
- Emu : EPUB 3 pages → ZIP valide (`mimetype` premier + non-
  compressé, OPF spine = 3, XHTML bien-formés) ; PDF 3 pages →
  3 pages + dimensions ≈ canvas/dpi ; séquence multipage →
  `p01_frame_0001.png`… présents, holds éclatés ; APNG sticker
  ≤ 512px + matte blanc.
- Engine : aucun (0 moteur).

### 6.8 Dépendances JNI
- **0 symbole** (T3 + pulls existants).

---

## 7. Macros rejouables + aligner/répartir + panneau historique + panneau info

### 7.1 Périmètre
- **Livre** (comblement 1.25 + 14.6 ABSENT, 1.26 ABSENT, 1.23
  PARTIEL, 1.24 ABSENT), **app-side 100 %** (0 JNI) :
  - Macros : enregistrement = journal des appels JNI mutateurs
    réussis + paramètres (niveau shim, pas d'enregistrement
    d'écran) ; rejouer = ré-émettre dans l'ordre sur le calque/
    document courant (ratés = arrêt + rapport ligne-par-ligne,
    jamais silencieux) ; bibliothèque `macros.json` v1 (nommer/
    renommer/supprimer, pas d'édition pas-à-pas — FUTURE) ;
    recrutement exclu : ops de navigation (zoom/pan/-frame-switch)
    non rejoués (filtrés à l'enregistrement, annoncé).
  - Aligner/répartir : sur les calques top-level sélectionnés
    (multi-sélection nouvelle du `LayerPanel`, session-only) —
    6 alignements (gauche/centre-h/droite/haut/centre-v/bas) +
    3 répartitions (h/v + grille) ; exécution via le chemin
    existant **masque-plein + `nSelectionBlit` move**
    (masque blanc pleine page posé, blit translaté, 1 pas par
    calque — 0 JNI neuf, même pattern que V+1 §1.5) ;
    référence = canvas ou premier sélectionné (toggle).
  - Panneau historique (1.23) : liste session-only des pas undo
    moteur (le shim publie chaque `endStroke`/op validée : nom +
    heure + miniature optionnelle) + tap-pour-revenir (dépile
    jusqu'au pas, pattern exact-LIFO T3 respecté : un pas
    postérieur se défait d'abord — annoncé) ; pas de branchement
    (linéaire, comme le moteur).
  - Panneau info (1.24) : curseur (x,y moteur), taille canvas,
    dpi, taille pointe, couche active + flags/badges, zoom,
    mémoire approx (tuiles allouées, compteur session) —
    lecture seule, 1 Hz max (jamais de polling par trait).
- **Hors périmètre** : macros conditionnelles/boucles (liste
  linéaire seule) ; alignement de paths vectoriels internes
  (calques seuls) ; script externe (pas d'éval — sécurité).

### 7.2 UX
- `MacroPanel` (● enregistrer / ■ stop / ▶ rejouer, liste
  `macros.json`, rapport d'erreurs) ; `AlignPanel` (9 boutons
  48dp + toggle référence) ; `HistoryPanel` (liste + tap-revenir
  + `history_depth` compteur) ; `InfoPanel` (grille lecture seule
  + loupe pipette existante réutilisée pour x,y).
- Strings : `macro_record/stop/replay/library/error`,
  `align_left/center/right/top/middle/bottom/distribute_h/v/
  grid/ref`, `history_jump`, `info_cursor/canvas/dpi/brush/
  layer/zoom/memory` — FR + EN.

### 7.3 Moteur vs app-side
- **App-side 100 %** : journal shim, sélections, panneaux. Le
  moteur ne connaît ni macros ni historique-listé (son undo reste
  la pile d'exécution, pattern T3 pending-moves respecté).

### 7.4 API proposée
- Aucune JNI. `macros.json` v1 : `[{name, ops:[{op, params}...]}]`
  (op = nom de symbole existant + JSON validé au rejouer par le
  même parseur qu'à l'enregistrement — op inconnue = arrêt +
  rapport).

### 7.5 Undoabilité
- Rejouer = N pas (1 par op rejouée, défaisables un par un) ;
  aligner = 1 pas par calque déplacé ; historique = vue (tap-
  revenir = N undos normaux) ; info = lecture seule.

### 7.6 Persistance
- `macros.json` local (comme workspaces V+3) ; historique =
  session-only (document fermé = vidé, comme la sélection V+1) ;
  multi-sélection = session-only.

### 7.7 Tests
- Emu : macro 3 ops (filtre + tone + blend) rejouée → composite
  égal à l'original (byte-identique sur canvas témoin) ;
  macro avec op devenue invalide → arrêt + rapport ;
  aligner 3 calques (gauche + répartir-h) → positions attendues ;
  historique liste N pas + tap-revenir restaure le composite
  (hash) ; info affiche x,y/dims/dpi cohérents.
- Engine : aucun (0 moteur ; le chemin masque-plein+blit est
  déjà testé V+2/T4).

### 7.8 Dépendances JNI
- **0 symbole.**

---

## 8. Budget JNI T5 + revue des alternatives

| § | Symboles neufs | Registre/JSON | App-side |
|---|---|---|---|
| 1 flags | `nSetLayerFlags` (1) | `nLayerState`+ORA | toggles/gardes |
| 2 fills | `nSetLayerFill` (1) | `edgeBorder`+`watercolor`, `nLayerState`+ORA | éditeurs |
| 3 blends | 0 | 10 noms `blend.h` | liste |
| 4 grd/abr/accord | 0 | existants (`curves`) | parseurs+dialogues |
| 5 pages | 0 | — | `project.json` v2+overlays |
| 6 EPUB/PDF/séq | 0 | T3 existants | assembleurs |
| 7 macros/align/hist/info | 0 | existants (masque-plein+blit) | journal+panneaux |
| **Total** | **EXACTEMENT 2** | — | — |

- Alternatives envisagées et REFUSÉES (même clause T1 §8/T2/T3/T4) :
  - générique `nT5Op(handle, json)` : illisible pour
    `check_jni_symbols.sh` et les tests par op ;
  - verrous app-side seuls (sans garde moteur) : contournables par
    tout chemin pixels (macros §7 rejoueraient sur un calque
    verrouillé) — la garde centrale shim est exigée ;
  - fills comme pixels T1 (`nStampShape` géant) : non-rééditables,
    contredit l'usage dégradé/foil ;
  - `.abr` via lib tiers : licence + binaire — parseur maison seul ;
  - EPUB via lib : `java.util.zip` suffit (mimetype-premier
    verrouillé par test) ;
  - PDF via moteur (rendu + xref from-scratch) : `PdfDocument`
    plateforme, moins de code, moins de risques ;
  - aligner via `nOffsetLayer` neuf : masque-plein + `nSelectionBlit`
    existant couvre le besoin — 0 symbole.
- Risques : (a) `.abr` : versions d'enveloppe inconnues → refus +
  message (jamais de pointe corrompue) ; (b) EPUB : lecteurs stricts
  sur l'ordre `mimetype` → test verrou ; (c) rejouer macros
  inter-pages : ops indexées par calque → garde (même count sinon
  arrêt + rapport) ; (d) `reference`/`draft` oubliés dans un chemin
  d'export neuf → test par export (chaque export §6 + T3 a son cas
  R/D) ; (e) `project.json` v2 : lecteur v1-tolérant (champs absents
  = défauts, jamais cassé).

## 9. Portée finale

T5 close le plan parity-gap : avec T1→T4, toutes les tranches ont
leur spec (0 code dans les 5 docs, code en tranches ultérieures
dans l'ordre §3→§1→§2→§4→§7→§5→§6). Restent EXCLU (§8, 19 items,
jamais) et FUTURE documentés (pass-through, dégradés alpha,
macros conditionnelles, stitch panoramique T4, embedding fontes
T2) — chaque FUTURE renvoie à sa spec d'origine, aucun travail
fantôme.
