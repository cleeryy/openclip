# V+2 — Spécification BD/manga + exports longs

> **Statut : SPEC UNIQUEMENT, aucun code.** Tranche V+2 du backlog
> (`docs/post-v1-backlog.md` : §3, §4, §5, §6, §7, §9, §11, §12, §13).
> Règles : vocabulaire maison, FR/EN ressources, clean-room (aucune
> string/asset tiers — rappel : `.cmc` et écriture `.clip` sont EXCLUS,
> alternatives maison ci-dessous), JNI gelé — toute extension moteur est
> documentée en `ARCHITECTURE.md` **avant** le code (AGENTS.md).
> État de départ vérifié sur master (`e578c2a`) : V+1 committée
> (sélection app-side session-only, calque texte `nSetLayerText` +
> `nBlitPngIntoLayer`, `mode:"alpha-erase"` dest-out) ; tones M10
> (`nApplyTone`, 6 motifs) ; vecteur M6 (`nAppendVectorPath`,
> `nVectorEraser`, `nUndoVector`) ; ORA/PSD M4 ; WebM M7b ; compteurs
> réels : engine **80** tests, émulateur **87** `@Test`.
> Ordre de lecture : §0 périmètre → §1 cases/bulles/vitesse → §2
> structure (lecture + multipage) → §3 exports (webtoon, GIF, SVG) →
> §4 édition support (transfo, collage, Bézier, guides) → §5 finition
> (blends, courbes, navigateur, autosave, PSD) → §6 livraison → §7 risques.

---

## 0. Périmètre honnête : 16 items, pas 12

L'en-tête du backlog annonce « V+2 : 12 » mais **16 lignes portent le
tag (V+2)** — liste exacte vérifiée, regroupée en 5 chapitres :

| # | Item backlog | Coût | Chapitre |
|---|---|---|---|
| 1 | §7 Cases / bulles / traits de vitesse (vectoriels) | M | §1 |
| 2 | §7 Sens de lecture + ordre des cases | S | §2 |
| 3 | §7 Export bande verticale (webtoon) | S | §3 |
| 4 | §7 Multipage projet maison (dossier ORA multiples) | M | §2 |
| 5 | §4 Déplacer / échelle / rotation / libre + maillage | M | §4 |
| 6 | §4 Copier / coller / couper inter-calques et inter-docs | S | §4 |
| 7 | §5 Édition points / poignées Bézier | M | §4 |
| 8 | §5 Export SVG des calques vectoriels | S | §3 |
| 9 | §6 Guides persistants + courbes/splines concentriques | M | §4 |
| 10 | §9 Export GIF / APNG | M | §3 |
| 11 | §9 Rythme par cel (hold), reorder cels, vrai compte onion moteur | M | §3 |
| 12 | §3 Modes de fusion supplémentaires | S | §5 |
| 13 | §11 Courbes de tons / balance par zone éditables par points | M | §5 |
| 14 | §12 Navigateur miniature | S | §5 |
| 15 | §12 Autosave périodique explicite | S | §5 |
| 16 | §13 Export couches PSD plein (layers/blends/masques) | M | §5 |

**Tranches de la spec (réponses aux « ? » de la mission) :**
- Trames : **aucun moteur neuf** — `nApplyTone` M10 suffit, la densité
  se règle app-side (masque, voir §1.1).
- GIF animé : **oui, V+2** (moteur from-scratch, §3.3). APNG :
  **différé V+2-bis** (chunks `acTL/fcTL/fdAT` + CRC from-scratch =
  second chantier, GIF couvre le besoin animé court).
- Vidéo : **déjà couverte** (WebM M7b FAIT). MP4/MPEG-4 : S app-side
  possible plus tard (MediaMuxer + AVC device), **hors V+2**.
- Multipage : **oui, V+2** maison (dossier ORA + sommaire JSON, §2.2) —
  jamais `.cmc` (EXCLU, format fermé).

**Principe transversal : app-side d'abord.** Chaque item tente 0 JNI
neuf (ponts existants : `nFrameCompositeInto`, `nLoadDocument`,
`nBlitPngIntoLayer`, `nSetLayerAdjustment`) ; le moteur n'est touché
que si indispensable, avec la proposition exacte à documenter en
`ARCHITECTURE.md` avant code.

---

## 1. Cases / bulles / traits de vitesse (vectoriels)

### 1.1 Périmètre honnête

- **Cases** : rectangles + polygones quelconques (taps successifs,
  fermeture comme le lasso V+1). Rendu = **contours vectoriels**
  (`nAppendVectorPath` existant, pression→largeur M6) + fond blanc
  optionnel. **Pas de remplissage moteur en V+2** : l'intérieur est un
  masque polygonal app-side (même code que le masque de sélection V+1)
  ; le blanc de fond et les trames s'y appliquent par le pont mix-sous-
  masque (composite avant/après via `nFrameCompositeInto`, mix app-side,
  `nLoadDocument` dans une trame dupliquée — pattern V+1 §1.1/§1.5
  réutilisé tel quel, avec ses annonces : reset undo + aplatissement).
- **Trames dans les cases** : `nApplyTone` existant (6 motifs M10),
  contraint au masque polygonal par le même pont. La **densité** se
  règle app-side (seuil sur le masque : dithering ordonné du masque
  avant mix — pur calcul, aucun JNI). Aucun motif neuf, aucune
  extension `nApplyTone` en V+2.
- **Bulles** : ellipse / rectangle arrondi + **queue** (triangle dont
  la pointe se drag). Rendu = paths vectoriels fermés (contour) + texte
  V+1 par-dessus (calque texte lié, voir §1.4). Pas de déformation
  de contour (pétale, nuage) en V+2 — formes différées V+3 avec
  l'accrochage/simplification (§5 backlog).
- **Traits de vitesse** : **générateur app-side** (point de fuite +
  angle + longueur + densité + jitter seedé → N segments vectoriels
  via `nAppendVectorPath`, 1 undo par génération grâce à
  `nUndoVector` par tracé). Aucun JNI neuf : le générateur est du pur
  calcul trigonométrique.
- Hors périmètre : cases à bords perdus automatiques (le fond blanc
  + la gomme alpha V+1 couvrent le besoin), texte sur tracé (V+3),
  mannequin posable (V+3).

### 1.2 UX (outils, panneaux, gestes)

- **Outil Case** (rangée outils, à côté de Sélection) : modes
  rect / polygone, épaisseur du cadre (réutilise le slider taille),
  toggle « fond blanc ». Gestes : drag = rect (comme sélection V+1),
  taps = polygone (fermeture double-tap / seuil 24dp, mêmes constantes
  que lasso).
- **Outil Bulle** : modes ellipse / rect-arrondi, drag = cadre,
  second drag = poser la queue (base → pointe). Double-tap sur bulle
  = créer/éditer le calque texte lié (pont V+1 §2, badge `Tx`).
- **Panneau Vitesse** (repliable, pattern slice 3) : point de fuite
  (tap canvas quand armé, comme la pipette), angle, longueur, densité,
  seed, boutons Générer / Annuler (annuler = `nUndoVector` × N).
- Overlay : contours case/bulle en surbrillance pendant l'édition
  (même chemin overlay-only que fourmis/curseur, suit la matrice de
  vue). Couleur maison (pas de vocabulaire tiers).
- Strings : `panel_rect/poly`, `bubble_ellipse/rounded/tail`,
  `speed_burst`, `panel_order_hint` — FR + EN dès la spec.

### 1.3 Moteur vs app-side : 0 JNI en V+2 nominal

- Tout passe par `nAppendVectorPath` / `nUndoVector` / `nApplyTone` /
  ponts V+1 existants. Le `kind` de `VectorPath`
  (`stroke|eraser-stroke`) peut porter `"panel"`/`"bubble"` en
  convention app-side (chaîne libre, moteur indifférent).
- **Option documentée si les perfs l'exigent (V+2-bis, pas V+2)** :
  `kind:"fill"` = polygone fermé rempli au composite (évite le pont
  trame-dupliquée pour le fond blanc). À documenter en
  `ARCHITECTURE.md` avant code seulement si le mix app-side s'avère
  trop lent sur tablette — pas avant.

### 1.4 Persistance ORA : `x-openclip-panel*`

Attributs maison sur `<layer>` ( lecteurs tiers : pixels visibles,
attributs inconnus ignorés) :
- `x-openclip-panel` (`rect:x,y,w,h` ou `poly:x1,y1;…`),
  `x-openclip-panel-order` (entier, voir §2.1),
  `x-openclip-panel-bg` (`white|none`),
  `x-openclip-tone` (`hatch|…|none` + `x-openclip-tone-density`
  0..100, pour ré-appliquer sans le PNG),
- `x-openclip-bubble` (`ellipse|rounded`, `tail:bx,by,tx,ty`),
  `x-openclip-bubble-text` (index du calque texte lié),
- `x-openclip-speed` (`x,y,angle,len,count,seed` — régénérable).
Si `stack.xml` est écrit côté moteur (point V+1 §2.4 à lever), ces
fiches suivent le même canal opaque que `nSetLayerText` (pattern M8).

### 1.5 Tests à ajouter

- Engine : **aucun** (0 JNI).
- Instrumentés (+5) :
  1. `panelRectStrokeClosed` — rect in-code : composite montre le
     cadre (pixels non-fond sur le périmètre), intérieur intact.
  2. `panelToneMasked` — tone dots sous masque polygonal : hors-
     polygone byte-identique.
  3. `bubbleTailAndTextLink` — bulle + texte lié : ORA round-trip,
     fiches relues identiques.
  4. `speedGeneratesUndoable` — génération 24 traits : composite
     non-vide, undo × 24 = byte-identique.
  5. `panelOverlayFollowsView` — overlay édition, zoom ×2 + rotation :
     reste sur le polygone (probe `panelOverlayForTest()`).

---

## 2. Structure : sens de lecture + multipage maison

### 2.1 Sens de lecture + ordre des cases — S, app-side pur

- **Périmètre** : métadonnée document `reading` (`ltr` | `rtl`,
  défaut `ltr`) + `panel-order` (liste d'index, éditée par
  tap-ordre sur les cases : mode « numéroter », taps successifs
  assignent 1..N, overlay chiffres). Utilisée par : l'export webtoon
  (§3.1, concat dans l'ordre), le sommaire multipage, la navigation
  page-suivante. Aucun effet pixels, aucun JNI.
- **UX** : toggle LTR/RTL dans le panneau Case ; mode numéroter
  (chiffres overlay-only, annulation = Échap). Strings
  `reading_ltr/rtl`, `panel_numbering` FR/EN.
- **ORA** : `x-openclip-reading` sur la racine du `stack.xml`,
  `x-openclip-panel-order` (cf §1.4).
- **Tests** (+1 emu) : `readingOrderRoundTrip` — 3 cases numérotées
  2-3-1, save/reload : ordre relu identique.

### 2.2 Multipage maison — M, app-side pur

- **Périmètre** : un **dossier projet maison** (nom libre, suffixe
  `.ocpages/`) contenant `pages/` (un ORA par page : `001.ora`,
  `002.ora`…), `project.json` (sommaire : `{version:1,
  reading:"ltr", pages:[{file, title, width, height, thumb}]}`) et
  `thumbs/` (PNG 256px générés app-side via `nFrameCompositeInto`
  downscalé — même chemin que vignettes timeline M7). Onglets slice 6
  réutilisés (une page = un doc). **Jamais `.cmc`** (EXCLU).
- **UX** : panneau Pages (liste + vignettes + renommer / insérer /
  supprimer avec garde « jamais 0 pages », même pattern que cels M7 ;
  glisser-déposer = reorder, l'ordre étant `project.json`). Page
  suivante/précédente suit `reading`. Scope mémoire : une seule page
  chargée moteur à la fois (8 Go box, règle slice 6 conservée).
- **ORA** : inchangé par page (ORA standard, lecteurs tiers ouvrent
  chaque page isolément — dégradé propre documenté dans l'aide).
  `project.json` est le seul format maison (documenté dans ce §,
  versionné `version:1`, champs inconnus ignorés à la lecture).
- **Tests** (+3 emu) :
  1. `multipageInsertDelete` — 3 pages, insert + delete : sommaire
     cohérent, jamais 0 pages (garde).
  2. `multipageReorderPersists` — reorder + reload dossier : ordre
     relu identique.
  3. `multipageThumbRefresh` — trait page 2 → vignette régénérée
     (hash change), autres intactes.

---

## 3. Exports longs + animation

### 3.1 Bande verticale webtoon — S, app-side pur

- **Périmètre** : export **page courante ou pages cochées** (ordre
  `panel-order`/`project.json`, sens `reading` pour l'ordre des pages)
  en **bande verticale unique** : composites pleine résolution tirés
  par `nFrameCompositeInto`… non — par `nCompositeInto` page par page
  (page chargée → composite → page suivante), concaténés verticalement
  avec gouttière paramétrable (0–64px, couleur fond), puis **découpage
  en tranches** de hauteur max paramétrable (défaut 8192px, plafond
  des plateformes de lecture) avec chevauchement 0. Fichiers
  `webtoon-p001.png`… via SAF (pont cache M4). Aucun JNI.
- **Limite honnête** : mémoire = 1 tranche à la fois (pas de bitmap
  géant) ; largeur = largeur page (pas de rescale, le rescale reste
  le dialogue `ExportDialog` M4/M8). Mention dans l'aide.
- **Tests** (+2 emu) : `webtoonConcatOrder` — 2 pages, ordre RTL :
  pixels page 2 au-dessus ; `webtoonSliceHeight` — 3 pages 2048px +
  plafond 4096 : 2 tranches, hauteurs exactes, jonction sans couture
  (ligne de jointure byte-identique au composite).

### 3.2 GIF animé — M, moteur from-scratch (APNG différé)

- **Périmètre V+2 : GIF89a uniquement.** Boucle (`NETSCAPE2.0`,
  `loop: once|infinite|N`), délai par cel (réutilise les holds §3.4),
  largeur max (downscale app-side avant encodage si > 1024, annoncé).
  **APNG différé V+2-bis** : chunks `acTL/fcTL/fdAT` + CRC
  from-scratch = second chantier de même taille ; GIF couvre le
  besoin « animé court partageable ». Palette : quantize moteur
  (médiane-cut 256, from-scratch, pas de lib).
- **Moteur (à documenter en `ARCHITECTURE.md` avant code)** :
  `nExportGif(handle, path, json): Boolean` — json `{loop, maxWidth,
  dither:bool}` ; frames tirées en interne par la boucle composite
  existante (pas de pull app-side trame par trame) ; LZW from-scratch
  (brevets expirés 2003–2004, aucun risque) ; `false` + couche
  intacte sur échec (règle M3/M4). Aucune dépendance vendored
  (gif.h non retenu : from-scratch = moins de revue licence, codecs
  maison déjà pratiqués en M3/M4).
- **App** : dialogue export (boucle, délai hérité timeline, largeur
  max), SAF `image/gif`, progression par cel (callback existant ou
  polling, à trancher en slice).
- **Tests** (+4 engine / +2 emu) : engine — header `GIF89a`,
  dimensions, boucle infinie, 2 frames distinctes décodables
  (round-trip par le lecteur de test from-scratch), échec chemin
  invalide = false ; emu — `gifExportPlaysTwoFrames` (fichier non
  vide + trailer `0x3B`), `gifHoldRespected` (délai du hold §3.4
  retrouvé dans le bloc GCE).

### 3.3 Export SVG des calques vectoriels — S, app-side pur

- **Périmètre** : sérialisation des `VectorPath` d'un calque
  (introspection via `nLayerState` + exposition pts existante — si les
  pts ne sont pas exposés, lecture documentée via le même canal que
  `nLayerState`, 0 JNI neuf ou 1 getter documenté, à lever avant
  code) en `<path d="M…L…" stroke-width="…">` (largeur = moyenne du
  profil, honnêtement notée : le profil pression→largeur variable est
  approximé, pas de `variable-width` propriétaire). `stroke` = couleur
  du calque, fond transparent. SAF `image/svg+xml`.
- **Tests** (+2 emu) : `svgExportValidXml` (parse + 1 path par tracé),
  `svgReimportLosesNothing Visible` — réimport visuel : composite du
  SVG rastérisé ≈ original à epsilon (pas byte-identique, documenté).

### 3.4 Rythme par cel + reorder + vrai compte onion — M moteur

- **Périmètre** : les 3 symboles explicitement **hors slice 4**,
  repris ici : `nMoveCel(handle, from, to)` (reorder, undoable),
  hold par cel (`nSetCelHold(handle, index, frames)` — le fps reste
  clip-level M7, le hold multiplie la durée d'exposition), vrai
  compte onion moteur (`nSetOnionCount(handle, before, after)` —
  remplace les steppers overlay-only slice 4 par le tirage réel des
  N voisins dans `nCompositeInto`).
- **À documenter en `ARCHITECTURE.md` avant code** : les 3 symboles,
  sémantique undo (move = une entrée, hold = méta hors undo comme
  `nSetOnion`, onion-count = rendu seul), bornes (hold 1..32, onion
  0..4 même bornes que les steppers actuels).
- **App** : colonne hold par vignette timeline, drag-reorder
  (même pattern tactile que reorder calques slice 3), steppers onion
  branchés sur le symbole (au lieu de l'overlay).
- **Tests** (+3 engine / +3 emu) : engine — move permute, hold hors
  bornes rejeté, onion-count 0 = composite sans voisins ;
  emu — `celHoldPlayback` (timeline joue le hold : durée × N),
  `celReorderThumbs` (vignettes suivent), `onionCountReal` (voisin
  rang 2 visible seulement si count ≥ 2 — le test qui tue l'overlay).

---

## 4. Édition support (sélection, Bézier, guides)

### 4.1 Transfo de sélection — M, le JNI réservé par V+1 §1.6

- **Périmètre** : déplacer / échelle / rotation / libre (bilinéaire)
  + **maillage** (grille 3×3 handles, interpolation bilinéaire par
  cellule) du contenu **sous masque V+1**, sur calque actif.
  Rééchantillonnage tuilé moteur, undo via `touchTile` (comme les
  traits). Aperçu = overlay app-side (matrice transfo appliquée au
  composite pull, jamais aux pixels avant validation — même règle que
  texte V+1 §2.3).
- **Moteur (réserve V+1 §1.6 reprise telle quelle, à documenter
  avant code)** : `nSelectionBlit(handle, json)` — json
  `{dx,dy,scale,angle,mode:"move|free|mesh", mesh:[…]}` appliqué au
  contenu du masque actif ; le masque devient **persistant moteur**
  à ce moment-là (transfert documenté : upload du ByteArray app-side
  vers moteur, un seul symbole d'upload inclus dans la proposition
  `ARCHITECTURE.md`, pas avant).
- **Tests** (+4 engine / +3 emu) : engine — move translate exact,
  scale ×2 bilinéaire (pas de trous), rotate 90° coins, mesh
  identité = byte-identique ; emu — aperçu suit le doigt, validation
  undoable, annulation = byte-identique.

### 4.2 Copier / coller / couper — S, app-side pur

- **Périmètre** : sous sélection V+1 : copier = `pullFrameFull`-style
  (composite du calque actif sous masque vers presse-papier
  session-only, même pont que §1.1) ; coller = nouveau calque via
  `nAddLayer` + `nBlitPngIntoLayer` (V+1) ; couper = copier +
  effacer (gomme alpha V+1 sous masque). **Inter-docs** : le
  presse-papier vit dans `DocRegistry` (comme les masques V+1 §1.3),
  dimensions canvas vérifiées au collage (mismatch = refus honnête +
  hint, pas de rescale silencieux).
- **Tests** (+2 emu) : `copyPasteRoundTrip` (collé = source sous
  masque, nouveau calque), `cutClearsUnderMask` (source effacée,
  hors-masque intact).

### 4.3 Édition points / poignées Bézier — M apparent, 0 JNI

- **Périmètre honnête** : les paths M6 sont **polylignes à profil**
  (pas de Bézier stockées — `parseVectorPathJson` prend des pts).
  V+2 ne change pas le modèle : l'édition = déplacer/supprimer/
  insérer des points + **poignées simulées app-side** (une poignée =
  subdivision : drag de la poignée insère N points interpolés le long
  d'une courbe Catmull-Rom calculée app-side — pur calcul, rendu
  identique car le moteur rasterize la polyligne densifiée).
  Replace = `nUndoVector` + `nAppendVectorPath` (1 undo, pattern
  texte V+1 §2.1 « ré-éditer = remplacer »).
- **UX** : mode Édition vecteur (tap path = montrer les points,
  drag = déplacer, double-tap segment = insérer, appui-long point =
  supprimer, poignées = sliders de tension par point). Overlay
  handles (même chemin que §1.2).
- **Moteur : aucun** (modèle inchangé, documenté comme tel en
  `ARCHITECTURE.md` : « V+2 Bézier = densification app-side »).
- **Tests** (+3 emu) : `bezierMovePoint` (déplacé → composite change
  localement, undo restaure), `bezierInsertDelete` (compte de points
  via probe), `bezierHandleDensifies` (poignée → plus de points,
  courbe lisse à epsilon).

### 4.4 Guides persistants + splines — M partagé

- **Périmètre** : guides **persistants par document** (verticaux /
  horizontaux / concentriques : cercles + segments radiaux pour les
  trames de fond type « focus lines ») stockés en **JSON doc
  app-side** (pas ORA — overlay de travail, comme la sélection V+1
  session-only… non : persistants → fichier `guides.json` à côté du
  doc, ou workspace slice 6 ; à trancher en slice, les deux sont
  app-side). Snap **moteur** : les segments tracés s'aimantent
  (même shim que règles M6, avant estampillage).
- **Moteur (à documenter avant code)** : extension `nSetRulers`
  (clés `guides:[{x}|{y}|{cx,cy,r}]`, inconnues ignorées — règle M3),
  **0 symbole neuf**.
- **Tests** (+2 engine / +2 emu) : engine — snap vertical à 2px,
  cercle guide : trait libre projeté ; emu — persistance reload,
  toggle visibilité overlay-only.

---

## 5. Finition (couleur, sortie, productivité)

### 5.1 Modes de fusion supplémentaires — S moteur, 0 UI

- **Périmètre** : étendre le noyau `blend` (ex. `hard-light`,
  `difference`, `color`, `luminosity` — liste fermée en slice, 3–4
  modes, from-scratch comme M1/M5). **Aucun changement UI** : la
  liste est lue depuis le point unique (slice 3 §3), les noms FR/EN
  suivent la table existante.
- **Tests** (+3 engine / +1 emu) : invariants connus par mode
  (difference identité à soi = noir, color conserve luma…),
  `nSetLayerBlend` accepte/refuse, composite bout-en-bout.

### 5.2 Courbes de tons / balance par zone — M, 0 symbole neuf

- **Périmètre** : nouveau **type d'ajustement** `curves` dans le
  canal `nSetLayerAdjustment` existant (pattern M8 : json étendu,
  clés inconnues ignorées) : `{"type":"curves",
  "points":[[in,out]×N], "channel":"rgb|r|g|b"}` → LUT 256
  interpolée (`curveLut`, déjà identifié slice 5 §8). Composite-time,
  non destructif, ORA round-trip comme M8. Balance par zone =
  curves + masque M5 (combinaison existante, pas de chantier).
- **UX** : éditeur de courbe (graphe 4 points comme stabilisation
  M2 slice 5 — pattern connu), par canal, preview composite.
- **À documenter en `ARCHITECTURE.md`** : le type `curves`
  (sémantique LUT, interpolation linéaire, bornes clampées) —
  extension JSON, pas de symbole.
- **Tests** (+3 engine / +2 emu) : identité = byte-identique,
  inversion, par-canal isole le canal, round-trip ORA.

### 5.3 Navigateur miniature — S, app-side pur

- **Périmètre** : downscale du composite existant
  (`nCompositeInto` vers petit buffer, même chemin que vignettes
  timeline), overlay-only, **tap-seek** (tap = recentrer la vue,
  via la matrice inverse existante). Aucun JNI.
- **Tests** (+1 emu) : `navigatorSeek recenters` (tap coin →
  offset de vue suit).

### 5.4 Autosave périodique explicite — S, app-side pur

- **Périmètre** : **opt-in** (défaut OFF, règle « sans telemetry » :
  tout reste local), intervalle 1/5/15 min, statut local
  (« enregistré 14:02 », même pattern que statut M10). Écrit le doc
  courant (ORA) + `project.json` si multipage. Jamais pendant un
  trait actif (attente fin de stroke, comme l'audio M7b ne coupe
  jamais un rendu).
- **Tests** (+2 emu) : `autosaveWritesOnTick` (fichier mtime avance),
  `autosaveSkipsMidStroke` (pas d'écriture entre begin/end).

### 5.5 Export couches PSD plein — M moteur, 0 symbole neuf

- **Périmètre** : étendre `savePsd` au-delà de l'aplati M4 :
  couches RGB8 + visibilité + opacité + blends mappés sur les modes
  PSD standards (`norm`, `mul `, `scrn`, `over`, `sAdd`, `sSoft` —
  table explicite en slice, leçon PSD : baseline d'abord, refus
  honnête pour le reste : masques et groupes = aplatis avec compteur
  visible, comme le dégradé `.clip` palier b). Format `psd` de
  `nSaveDocument` inchangé en signature.
- **Tests** (+3 engine / +1 emu) : round-trip couches (noms +
  pixels), blend inconnu → `norm` + compteur, groupe = aplati
  annoncé ; emu : fichier ouvrable (header `8BPS` + couches > 1).

---

## 6. Ordre de livraison + gates

1. **Rythme cel + onion (§3.4)** d'abord : petit moteur isolé,
   débloque la lecture des holds pour le GIF.
2. **Transfo sélection (§4.1) + copier/coller (§4.2)** : la sélection
   V+1 devient manipulable (réserve V+1 §1.6 consommée).
3. **Cases/bulles/vitesse (§1) + lecture/multipage (§2)** : le cœur
   BD, 0 moteur, s'appuie sur §4.2 (collage) et tones M10.
4. **Guides (§4.4) + Bézier (§4.3)** : précision du tracé (snap
   moteur documenté, édition 0 JNI).
5. **GIF (§3.2) + webtoon (§3.1) + SVG (§3.3)** : les sorties longues
   (GIF utilise les holds du (1), webtoon l'ordre du §2).
6. **Finition (§5)** : blends, curves, navigateur, autosave, PSD —
   indépendants, parallélisables.
7. Chaque chantier = 1 spec slice dédiée (pattern slices 0–6) +
   extension `ARCHITECTURE.md` **avant** code moteur + gate locale
   `emutest.sh` verte + `prepush.sh` avant push (AGENTS.md inchangé).

**Totaux visés fin V+2** : engine 80 → **≈104** (+24 : GIF 4, transfo
4, guides 2, blends 3, curves 3, PSD 3, cel/onion 3, cases-fill
optionnel 2) ; émulateur 87 → **≈123** (+36). Les comptes exacts sont
arrêtés par slice ; aucun item ne réduit un total existant.

**Extensions `ARCHITECTURE.md` à écrire avant code (rappel § par §)** :
`nSelectionBlit` + upload masque (§4.1), `nMoveCel` / `nSetCelHold` /
`nSetOnionCount` (§3.4), `nExportGif` (§3.2), `nSetRulers` clés
`guides` (§4.4), type `curves` de `nSetLayerAdjustment` (§5.2),
sémantique couches `savePsd` (§5.5), « V+2 Bézier = densification
app-side, 0 JNI » (§4.3). Le reste (§1, §2, §3.1, §3.3, §4.2, §5.1
partiel, §5.3, §5.4) est 0 JNI par construction.

## 7. Risques

- **Pont trame-dupliquée (§1.1, repris de V+1 §5)** : le mix cases /
  tones reset l'undo et aplatit (contrat M4). Mitigation : cases et
  tones sur calque dédié « décor » (recommandé dans l'aide), ou
  `kind:"fill"` V+2-bis (§1.3) si les retours tablette l'exigent.
- **GIF from-scratch** : LZW + quantize maison = risque de fichier
  lourd ou lent sur gros canvas. Mitigation : `maxWidth` 1024 par
  défaut + tests de taille plancher/plafond ; APNG reste l'issue
  V+2-bis si la qualité 256 couleurs est jugée insuffisante.
- **APNG différé** : dire non proprement — le dialogue n'offre que
  GIF + WebM (existants), pas d'entrée leurre (règle V+1 §1.1).
- **Multipage vs ORA standard** : lecteurs tiers voient des pages
  isolées sans sommaire. Mitigation : `project.json` versionné +
  aide « exporter la page en PNG pour partage » (pont M4 existant).
- **`stack.xml` côté moteur ?** : même point ouvert que V+1 §2.4/§5 —
  si le writer est moteur, les fiches §1.4 suivent le canal opaque
  `nSetLayerText`-style (1 symbole générique `nSetLayerMeta` possible
  au lieu de N symboles — à trancher à l'écriture d'`ARCHITECTURE.md`).
- **Snap guides + stabilisation M2** : deux transfos d'entrée dans le
  shim (lissage puis snap — ordre à figer : lisser PUIS snapper,
  testé §4.4).
- **Aucun risque clean-room** : GIF89a/LZW brevets expirés, quantize
  et LUT from-scratch, SVG/ORA/PNG ouverts, multipage maison,
  `.cmc`/`.clip`-écriture/boutique toujours exclus.
