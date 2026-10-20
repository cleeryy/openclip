# V+1 — Spécification fondations manquantes (sélection, calque texte, vraie gomme alpha)

> **Statut : SPEC UNIQUEMENT, aucun code.** Tranche V+1 du backlog
> (`docs/post-v1-backlog.md` : §1 gomme alpha, §3 verrou cité en
> dépendance, §4 sélection, §8 calque texte).
> Règles : vocabulaire maison, FR/EN ressources, clean-room (aucune
> string/asset tiers), JNI gelé — toute extension moteur est documentée
> en `ARCHITECTURE.md` **avant** le code (AGENTS.md).
> Ordre de lecture : §1 sélection → §2 texte → §3 gomme (la gomme
> s'appuie sur le masque de sélection §1.6 pour le mode « effacer dans
> la sélection »).

État de départ vérifié sur master : menu Sélection présent dans
`MainActivity.kt` avec 2 entrées désactivées (`select_all`,
`deselect`, `enabled = false`, « pas d'API sélection moteur ») ;
gomme raster = peinture claire (bandeau honnête `eraser_covers_hint`,
`BrushPrefs.kt` : `TODO(alpha-erase)`, `mode:"eraser"` = peinture vers
le fond) ; vraie suppression uniquement sur calques vectoriels
(`nVectorEraser`) ; ORA app-side minimaliste (`DocOra.kt` :
`writeLayersOra`, aucun attribut `x-openclip-*` aujourd'hui).

---

## 1. Sélection (rect / ellipse / lasso, baguette différée)

### 1.1 Périmètre honnête

- **V+1 livre : rectangle, ellipse, lasso polygonal** (tap-tap-tap +
  double-tap pour fermer, ou drag continu). Baguette magique
  (sélection par couleur contiguë) : **différée en V+1-bis / V+2** —
  coût flood-fill tuilé + tolérance + antialiasing, à speccer à part.
  Le menu ne propose jamais la baguette tant qu'elle n'existe pas
  (pas d'entrée désavouée au-delà de la tranche).
- **La sélection est un masque binaire 1 bit pleine résolution**
  (même dimensions que le canvas), stocké **app-side uniquement**
  en V+1 (un `Bitmap.ALPHA_8` ou `ByteArray` dans le `DocRegistry`
  par document, jamais dans le moteur). Aucun symbole JNI de
  sélection n'est créé en V+1.
- **Effet : la sélection contraint ce qui existe déjà**, elle ne
  crée aucune primitive moteur :
  - tracé pinceau/gomme : les estampilles hors masque sont ignorées ;
  - `nApplyFilter` / `nApplyTone` : appliqués pleine couche puis
    **fusionnés sous masque app-side** (pull composite avant/après
    via `nFrameCompositeInto`, mix sous masque, re-import par
    `nLoadDocument` sur une copie — même pont que l'export WebM M7b,
    aucun JNI neuf) ;
  - remplissage / effacement : idem.
- **Hors périmètre V+1** (renvoyés aux FUTUR existants) :
  déplacer/échelle/rotation du contenu sélectionné (§4 backlog,
  rééchantillonnage tuilé + `touchTile` undo), copier/coller
  inter-calques (pont `pullFrameFull` + collage, S app-side),
  mémorisation canaux nommés (V+3), baguette (voir ci-dessus).

### 1.2 UX (gestes, panneaux)

- **Outil Sélection dans la rangée d'outils** (`ToolPalette`), à côté
  de pinceau/gomme : 3 modes (rect / ellipse / lasso), sélecteur
  segmenté dans un petit panneau repliable (pattern `LayerPanel` :
  né replié, cible tablette + téléphone repli total).
- **Gestes canvas** (suit `slice1-gestures.md`) :
  - rect/ellipse : drag = définir le cadre (départ → relâche) ;
    1 doigt en mode navigation existant inchangé ; le mode Sélection
    arme la forme au lieu d'encrer (comme la pipette armée : aucun
    trait parasite, `pipetteArmed` comme modèle).
  - lasso : drag continu ou taps successifs, fermeture au double-tap
    ou au retour près du point de départ (seuil 24dp, même constante
    que `TAP_MAX_DP`) ; auto-fermeture droite sinon.
  - 2-tap pendant une sélection active = annuler le tracé en cours
    (pas undo moteur) ; Échap / tap hors actions = garder la
    sélection (pas de perte accidentelle).
- **Menu Sélection** (remplace les 2 entrées désactivées) :
  Tout sélectionner (rect pleine page, app-side pur → activable sans
  moteur) / Désélectionner / Inverser / Contour progressif +1px
  (adoucit les bords, app-side) — les 4 sans JNI. Flottant/transformer
  restent absents (pas de leurre).
- **Marching ants overlay app-side** : `Canvas` Compose par-dessus
  `EngineSurface`, `DashPathEffect` animé par phase (timer 10 Hz
  comme le HUD slice 5), suit la matrice de vue (même `screenToEngine`
  inverse, rotation-aware). Ne touche jamais aux pixels moteur
  (même règle que curseur/HUD/pipette : overlay-only, composite-only).
  Couleur : blanc + ombre noire (lisible sur tout fond), pas de
  vocabulaire tiers.
- **Strings** : `selection_rect/ellipse/lasso`, `select_all`,
  `deselect`, `select_invert`, `select_feather`, `selection_active_hint`
  — FR + EN dès la spec (règle projet).

### 1.3 Matrice / vue

- Le masque vit en **coordonnées moteur** (2048×1536 nominal,
  `engineWidth/Height` réels) ; le tracé gestuel passe par
  `screenToEngine()` existant (déjà rotation-aware, §slice 1).
- Le overlay fourmis fait l'inverse (masque → écran) via la même
  matrice (`fitScale × viewScaleMul`, rotation, offset) : réutiliser
  le chemin du `CursorRing`, pas de seconde matrice.
- Zoom/pan/rotate en sélection active : le masque ne bouge pas
  (ancré canvas), seul l'overlay suit la vue. `resetView()` n'efface
  pas la sélection.
- Multi-docs : un masque par `docId` dans le registre (comme les
  préférences pinceau), `pause()`/`resume()` le conservent ;
  `dispose()` le libère (même cycle que `liveHandles` slice 6).

### 1.4 Persistance ORA

- **V+1 : la sélection n'est PAS persistée** (session-only, comme
  l'audio M7b). Documenté dans le dialogue : fermer le doc = perdre
  la sélection (refus honnête, pas de silo propriétaire).
- **V+3 (canaux nommés, backlog §4)** : étendre `stack.xml` maison
  avec `<layer>`-sœur `<selection name src>` + PNG 8 bits ; préfixe
  `x-openclip-selection` réservé dès V+1 pour ne pas casser la
  compatibilité ORA (lecteurs tiers ignorent l'inconnu).

### 1.5 Tests à ajouter

- Engine : **aucun** (0 JNI neuf en V+1 — gate `make test` inchangée).
- Instrumentés app-side (émulateur local, gate `emutest.sh`) :
  1. `selectionRectConstrainsStroke` — rect moitié gauche, trait
     pleine largeur, composite : droite inchangée (pixels avant =
     après à epsilon fond).
  2. `selectionLassoCloseAndInvert` — lasso triangulaire in-code,
     inverser, effacer : intérieur intact, extérieur effacé.
  3. `selectionOverlayFollowsView` — overlay affiché, zoom ×2 +
     rotation 15° : `cursorRingForTest`-style probe (nouveau
     `selectionOverlayForTest()`) reste sur le masque.
  4. `selectionSurvivesTabSwitch` — pause/resume multi-docs : masque
     identique (hash du ByteArray).
  5. `selectionFilterMasked` — filtre mosaïque sous sélection :
     hors-sélection byte-identique.
- Compte : +5 tests (75 → 80 visés).

### 1.6 Extension moteur/JNI requise : AUCUNE en V+1

- Choix délibéré : tout est app-side (masque + overlay + mix sous
  masque via `nFrameCompositeInto`/`nLoadDocument` existants).
- **Si** V+2 veut la sélection transformante (déplacer/échelle), ALORS
  documenter d'abord en `ARCHITECTURE.md` (avant code) :
  `nSelectionBlit(handle, json)` — json `{dx,dy,scale,angle,mode}`
  appliqué au contenu du masque actif persistant moteur, undoable via
  `touchTile` ; le masque deviendrait moteur à ce moment-là
  (transfert documenté, pas avant).

---

## 2. Calque texte éditable

### 2.1 Périmètre honnête

- **Piste retenue (backlog §8)** : mise en page **app-side**
  (Canvas système), import pixels + méta ORA
  `x-openclip-text` (police + taille + chaîne). **Pas de moteur fonte
  natif** (règle single-file : aucune lib fonte C++ vendored,
  aucun HarfBuzz/Freetype dans `engine/third_party`).
- **Modèle** : un calque texte = un calque raster normal moteur
  (peint par import bitmap) + une fiche texte app-side
  (`TextSpec` : chaîne multiligne, nom de fonte système, taille px,
  graisse, italique, alignement, interligne, espacement lettres,
  couleur, cadre x/y/w). **Ré-éditer = re-rendre + remplacer les
  pixels** (rasterisé éditable, pas de glyphes persistants moteur).
- Inclus V+1 : saisie multiligne, taille, graisse/italique système,
  alignement G/C/D, interligne 0.8–2.0, espacement lettres,
  couleur (source `inkHex`), cadre repositionnable (drag du bloc),
  annuler/rétablir par remplacement (snapshot tuile comme filtre M3).
- Hors périmètre : texte sur tracé / déformation (V+3, après maillage
  §4), polices embarquées propriétaires (**EXCLU**, backlog §8 —
  fontes système + libres à licence loggée uniquement), césure
  typographique fine, bidirectionnel complexe (Canvas système seul).

### 2.2 UX (gestes, panneaux)

- **Création** : outil Texte (rangée outils) → tap canvas = point
  d'ancrage + ouverture du panneau édition (champ multiligne +
  contrôles) ; drag = cadre de largeur fixe (retour ligne auto).
- **Panneau Texte** (repliable, pattern slice 3) : champ texte,
  sélecteur fonte système (liste `Typeface` disponibles, jamais de
  nom tiers), taille slider 8–256px, B/I toggles, alignement 3
  boutons, interligne stepper, espacement slider, couleur (réutilise
  `ColorPanel`), boutons Valider / Annuler.
- **Ré-édition** : double-tap sur un calque texte = rouvre le panneau
  avec la fiche (pixels remplacés à la validation, pas de doublon).
  Badge calque `Tx` (même famille que `◑ ↓ fx`).
- **Rendu** : `android.graphics.Paint` + `StaticLayout` (multiligne,
  alignement, spacing natifs), fond transparent, import via le pont
  bitmap→moteur existant (même chemin que collage `pullFrameFull`
  inversé / `nLoadDocument` sur copie — aucun JNI neuf).
- **Limite honnête affichée** : petite taille (< 12px sur canvas 2048)
  = rasterisation nette à 100 % mais floue au zoom (pas de vecteur
  texte en V+1) ; mention dans l'aide (`BrushHelp`-style,
  `text_help_body` FR/EN).

### 2.3 Matrice / vue

- Le cadre texte est en coordonnées moteur ; le drag du bloc passe
  par `screenToEngine()` ; l'aperçu live pendant l'édition est un
  overlay Compose (comme les fourmis §1.2), le commit écrit les
  pixels via le pont existant.
- Rotation de vue : le texte commité tourne avec le canvas (pixels
  normaux) ; l'aperçu overlay suit la matrice comme §1.3.

### 2.4 Persistance ORA : `x-openclip-text`

- `stack.xml` : attributs maison sur le `<layer>` texte (lecteurs
  tiers : PNG visible + attributs inconnus ignorés = dégradé propre) :
  `x-openclip-text` (chaîne, échappée XML), `x-openclip-font`
  (nom système, ex. `sans-serif`), `x-openclip-size` (px moteur),
  `x-openclip-style` (`bold|italic|bold-italic|regular`),
  `x-openclip-align` (`left|center|right`),
  `x-openclip-leading` (facteur), `x-openclip-tracking` (px),
  `x-openclip-color` (`#RRGGBB`), `x-openclip-box` (`x,y,w`).
- Rechargement : si la fonte système manque (autre appareil),
  fallback `sans-serif` + bandeau « fonte substituée » (même pattern
  que le dégradé `.clip` palier b : compteur visible, jamais silencieux).
- `DocOra.writeLayersOra` : étendre `OraLayer` avec `textSpec: String?`
  (null = raster normal) ; `nLoadDocument`/`nSaveDocument` moteur
  inchangés (le XML est écrit côté app — vérifier quel côté écrit
  `stack.xml` au moment de l'impl : si c'est le moteur, ALORS
  l'extension `nSaveDocument` passe par `ARCHITECTURE.md` d'abord,
  voir §2.6).

### 2.5 Tests à ajouter

- Engine : **aucun** si `stack.xml` est écrit app-side ; sinon voir §2.6.
- Instrumentés :
  1. `textCommitPaintsPixels` — fiche in-code (« Aa Ég » + emoji
     manquant → tofu, pas de crash), commit, composite non-vide
     dans le cadre.
  2. `textReeditReplaces` — Valider « A » puis ré-éditer « B » :
     pixels A absents (diff > seuil dans l'ancien cadre), undo
     restaure A.
  3. `textOraRoundTrip` — save ORA + reload : chaîne/fonte/taille
     identiques (`x-openclip-*` relus), pixels byte-identiques.
  4. `textFontFallback` — fonte inconnue → `sans-serif`, bandeau
     affiché, pas de crash.
- Compte : +4 tests (80 → 84 visés avec §1).

### 2.6 Dépendance moteur/JNI : AUCUNE si ORA app-side, documentée sinon

- **Cas nominal : 0 JNI.** Mise en page Canvas système + ponts
  existants (`nLoadDocument` copie / collage bitmap).
- **Point à lever avant code** : qui écrit `stack.xml` ? Si le
  moteur (via miniz), alors l'extension minimale documentée
  **d'abord** en `ARCHITECTURE.md` :
  `nSetLayerText(handle, index, json|null)` — stocke la fiche
  (opaque au moteur, round-trip ORA comme les ajustements M8 :
  `<layer>` attrs, chargement d'un type inconnu = fiche ignorée,
  pixels gardés). Sémantique copiée sur `nSetLayerAdjustment`
  (M8) : null efface, inconnu ignoré, valeurs par défaut codées.
- Dans les deux cas : pas de HarfBuzz/Freetype côté moteur (règle
  single-file maintenue).

---

## 3. Vraie gomme alpha (`alpha-erase`, dest-out)

### 3.1 Périmètre honnête

- **Aujourd'hui** : `mode:"eraser"` peint vers le fond clair
  (documenté slice 5 §4, bandeau `eraser_covers_hint`, `BrushPrefs`
  `TODO(alpha-erase)`). Sur fond blanc c'est invisible ; sur
  multi-calques ça recouvre au lieu de retrancher.
- **V+1 livre** : nouveau mode brosse `alpha-erase` = **dest-out
  moteur** (l'estampille réduit l'alpha du calque actif au lieu de
  peindre), pression → quantité effacée, taille/opacité propres
  déjà persistées (`eraserRadius/Opaque`) réutilisées sans migration.
- **Interaction avec les masques M5** : la gomme écrit dans la
  cible courante (`maskTarget`) — sur masque, effacer = peindre du
  noir (masquer), pas toucher l'alpha pixels (règle existante
  conservée, testée).
- **Interaction avec la sélection (§1)** : gomme hors sélection =
  ignorée (même garde que le pinceau) ; mode « effacer dans la
  sélection » sans JNI supplémentaire (le mix sous masque §1.1 couvre
  déjà le cas filtre/ton ; la gomme dest-out est nativement
  masquable par le garde app-side avant `nStrokeMove`).
- Hors périmètre : verrou alpha / verrou pixels (item V+1 séparé,
  flag par calque dans `stampResolved` — à speccer à part, dépend de
  ce §), gomme vectorielle (FAIT M6, inchangée).

### 3.2 UX (gestes, panneaux)

- **Aucun changement gestuel** : même outil Gomme, même curseur
  pointillé (`ringEraserPaint`), même HUD. Seul le **bandeau honnête
  disparaît** (remplacé par un toast unique post-migration, voir §3.4).
- `PinnedBrushBar` / `ToolPalette` : inchangés (rayon/opacité déjà
  séparés). L'aide `brush_help_body` est réécrite : « la gomme
  retranche l'alpha sur raster comme sur vectoriel ».
- **Garde** : gomme sur calque de réglage (`adjust != null`) =
  refusée avec hint (un calque fx ne porte pas de pixels, comme
  aujourd'hui pour les filtres) ; gomme sur calque invisible =
  anneau barré existant (`barred`, `cursorRingForTest`).

### 3.3 Moteur : extension documentée d'abord

- Sémantique : `mode:"alpha-erase"` dans le descripteur `setBrush`
  (même canal JSON que `stabilize/curve/mode` M2 — **pas de nouveau
  symbole JNI si le shim peut router** ; sinon 1 symbole documenté).
  L'estampille applique dest-out sur le calque actif :
  `a' = a·(1 − k·dab)`, rgb re-prémultiplié et clampé `[0,alpha]`
  (contrat M3 conservé : alpha modifié **par design ici**, rgb suit).
- Undo : même chemin `touchTile` que les traits (pas de chantier undo).
- **À documenter en `ARCHITECTURE.md` avant code** (proposition à
  valider, pas du code) :
  - soit §M11 : clé `mode` étendue (`"brush"|"eraser"|"alpha-erase"`),
    `eraser` historique = conservé alias peinture claire (compat
    presets `.myb` + anciens docs) ;
  - soit `nSetEraserMode(handle, on: Boolean)` si le shim préfère un
    symbole explicite (contrat `check_jni_symbols.sh` + CI mis à jour).
- Le choix symbole-vs-JSON est tranché à l'écriture d'`ARCHITECTURE.md`,
  pas dans cette spec (les deux respectent « JNI-documenté-d'abord »).

### 3.4 Migration (remplace le hack blanc)

- **Aucune migration de pixels** : les anciens traits « gomme »
  restent ce qu'ils sont (peinture claire committée, undoable comme
  tout trait — l'historique ne ment pas).
- **Migration prefs** : `BrushPrefs` (`tool:"eraser"`) = inchangé ;
  seul le moteur change d'interprétation (peinture claire →
  dest-out). Un **dialogue unique** à la première ouverture
  post-tranche (« la gomme retranche désormais », FR/EN,
  `eraser_alpha_note`, ne plus montrer) + entrée d'aide.
- **Compat presets `.myb`** : aucun preset maison n'utilise
  `mode:"eraser"` comme peinture voulue (galerie slice 2 = 6 presets
  pinceau) ; le `.myb` importé avec `eraser` suit la nouvelle
  sémantique (noté dans l'aide import).

### 3.5 Tests à ajouter

- Engine (`make test`, headerless assert) :
  1. `alphaEraseReducesAlpha` — plaque opaque, estampille
     `alpha-erase` centre : alpha centre < avant, bords intacts,
     rgb ≤ alpha partout.
  2. `alphaErasePressureScales` — pression 1.0 efface plus que 0.2
     (monotone sur le pixel central).
  3. `alphaEraseUndoRestores` — undo = byte-identique (via `touchTile`).
  4. `alphaEraseLegacyModeKept` — `mode:"eraser"` peint toujours clair
     (compat, verrouille l'alias).
  5. `alphaEraseOnMaskPaintsBlack` — cible masque : alpha pixels
     intact, masque assombri.
- Instrumentés :
  6. `eraserDestOutComposite` — 2 calques (rouge sous, bleu dessus),
     gomme sur bleu : composite montre le rouge (le test qui tue le
     hack blanc : avant il montrait du clair).
  7. `eraserBannerGone` — le bandeau `eraser-banner` n'apparaît plus.
- Compte : +5 engine (75 → 80) + 2 instrumentés (84 → 86 visés).

### 3.6 Dépendances

- Moteur : tacheté dest-out dans `brush.h` (`stampResolved`) + JSON
  `mode` étendu — **documenté en `ARCHITECTURE.md` avant code**,
  0 ou 1 symbole JNI selon §3.3.
- App : `BrushPrefs.currentJson()` émet `"mode":"alpha-erase"` quand
  `isEraser` (1 ligne + alias legacy) ; aide + strings FR/EN ;
  suppression du bandeau conditionnée au support moteur (sonde :
  si le symbole/JSON est refusé → garder le bandeau, dégradé propre).

---

## 4. Ordre de livraison + gates

1. **Gomme alpha d'abord** (§3) : débloque la crédibilité de l'outil
   existant ; moteur + JNI-documenté-d'abord + 5 engine / 2 emu.
2. **Sélection ensuite** (§1) : 0 moteur, 5 emu ; s'appuie sur la
   gomme dest-out pour « effacer dans la sélection ».
3. **Texte en dernier** (§2) : 0 moteur (si ORA app-side), 4 emu ;
   réutilise le pont bitmap et le badge `Tx`.
4. Chaque fondation = 1 spec slice dédiée (pattern slices 0–6) +
   extension `ARCHITECTURE.md` **avant** code moteur + gate locale
   `emutest.sh` verte + `prepush.sh` avant push (règles AGENTS.md
   inchangées).

**Totaux visés fin V+1 (3 fondations)** : engine 75 → **80**,
émulateur 75 → **86**. Restent hors V+1 : baguette, transfo de
sélection, copier/coller, texte sur tracé, verrous alpha/pixels,
formes géométriques, tilt — items backlog inchangés, ni avancés ni
retirés par cette spec.

## 5. Risques

- **Mix sous masque (§1.1) via `nLoadDocument`** : remplace la trame
  courante (reset undo M7 documenté dans le shim). Mitigation :
  la passe sélection opère sur une **copie de trame**
  (`nDuplicateFrame` → mix → `nDeleteFrame` l'originale) ou exige un
  point undo explicite ; à trancher dans la spec slice (pas ici).
- **`stack.xml` côté moteur ? (§2.4)** : si oui, 1 symbole JNI de plus
  que prévu. Mitigation : lever le point avant code (lecture du
  writer ORA moteur vs `DocOra` app).
- **Dest-out + prémultiplié (§3.3)** : rgb doit suivre alpha
  (halos sinon). Mitigation : tests invariants rgb ≤ alpha (déjà le
  contrat M3, réutilisé).
- **Fonte substituée (§2.4)** : texte rechargé ailleurs ≠ pixel.
  Mitigation : bandeau + fiche conservée (ré-édition possible).
- **Aucun risque clean-room** : aucun format/asset tiers, fontes
  système uniquement, ORA étendu par attributs maison ignorables.
