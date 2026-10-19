# Slice 5 — Feeling pinceau (SPEC, sans code)

> SPEC UNIQUEMENT — aucune modification app/engine. Vocabulaire maison,
> aucun asset ni capture d'outil tiers. Clean-room : jamais de strings,
> icônes, noms de palettes ni layouts repris de Clip Studio Paint / Celsys
> (cf. `docs/csp-ux-research.md` § clean-room). JNI gelé (`ARCHITECTURE.md`) :
> tout ce qui suit est **app-side uniquement** (`EngineSurface.kt`,
> `MainActivity.kt`, `ToolPalette.kt`, `BrushPrefs.kt`, `PinnedBrushBar.kt`,
> ressources `R.string`).
> Conventions : FR/EN via `R.string` uniquement, pas de littéraux FR
> en code (leçon M5 : apostrophes FR en dur cassent le build).

État vérifié (point de départ) : Slices 0-4 committées (shell, gestes,
couleur, calques, timeline). Référence feeling : `docs/csp-ux-research.md`
§R2 (rangée taille+opacité épinglée, Tool Property stabilisation visible
pendant le dessin) + §R6 (séparation stylet-dessine / doigts-outils, palm
rejection stylus-first).
Actuel pinceau (vérifié dans le code) :

- `ToolPalette.kt` = `BrushState(color, isBrush, radius, opaque, stabilize,
  curve)` → JSON `{"radius","hardness","opaque","spacing":0.15,"color",
  "stabilize","curve","mode":"brush"|"eraser"}` poussé via
  `surface.setBrush(json)` (`pushBrush()`). Taille `1..128px` + opacité
  inline, stabilisation/courbe derrière `showAdvanced` (chip `⚙`),
  symétrie X/Y.
- `BrushPrefs.kt` (Slice 3) = store hoisté unique (`radius/opaque/stabilize`
  persistés `brush-defaults.json`, debounce 500 ms + `flush` onPause,
  fallback `10px/100 %/15 %`). **`curve` n'est PAS persistée** : champ
  live `toolCurve` via `bindTool(color, eraser, curve)`, valeur locale
  `var curve` dans `ToolPalette` (reset au restart). Second-class citizen.
- `PinnedBrushBar.kt` = miroir bas-canvas (taille + opacité + cycle
  stabilisation 0/15/50 %), repliable pastille `+` 48dp, composite-only.
- `EngineSurface.kt` = render-loop + stroke queue : `pressureOf(raw)` =
  `(raw<=0 ? 0.5 : raw*2).coerceIn(0.05,1)` (souris sans pression → 0.5),
  historique `MotionEvent` drainé, `nStrokeBegin/Move/End` sur render
  thread, `needsComposite=true` **à chaque point** (rendu incrémental
  existant, §4). Stylet dessine toujours (stylus-first Slice 1) ; loupe
  navigation overlay composite-only (zoom % + angle, 800 ms).
- Moteur M2 (vérifié `ExternalC.cpp` + `stroke.cpp`) : `stabilize` 0..1
  (lissage exponentiel `k = 1-0.75*stabilize`), **`curve` 0..1 →
  exposant `1+3*curve`** (`PointCurve::fromJsonCurve`, linéaire..lourd),
  taille dab `radius*(0.25+0.75*pr)`, opacité `opaque*pr`.
- **Gomme actuelle = hack couleur** (vérifié `stampResolved`) : `mode:
  "eraser"` peint du **blanc opaque** (`kBg=255`) via `dabFromPressure`
  sur la surface du calque actif — pas une efface alpha. Sur calque
  transparent ou multi-calques, ça **recouvre** au lieu de révéler
  (blanc opaque masque les calques dessous). Le vecteur a une vraie
  gomme (`nVectorEraser` supprime les paths intersectés) ; le raster non.
  ARCHITECTURE.md M2 l'avoue : « erases toward canvas background, not
  layer alpha, until M5 adds alpha-erase » — **M5 n'a pas livré
  l'alpha-erase** (M5 = visibilité/opacité/masques/clip, pas de dest-out).

Constat : le pinceau **peint juste mais ne se sent pas** — pas de curseur
de taille (on dessine à l'aveugle en hover), courbe pression = slider
abstrait 0..100 % sans visualisation, gomme = peinture blanche déguisée
(dangereuse sur transparent), aucun retour taille/pression pendant le
geste. La Slice 5 rend le geste **lisible avant, pendant, après** — sans
toucher au moteur.

## 1. Principe — overlays composite-only, un seul `pushBrush()`

```
feeling = curseur (avant) + feedback (pendant) + courbe (réglage) + gomme (outil)
moteur  = setBrush(json) inchangé, nStroke* inchangé, nCompositeInto inchangé
```

- **JNI gelé** : aucun symbole nouveau. Seuls `setBrush`,
  `nStrokeBegin/Move/End`, `nCompositeInto`, `nFrameCompositeInto`,
  `pullFrameFull`, `isDrawingNow()` sont utilisés. La courbe éditable
  mappe vers le champ **`curve`** existant (exposant `1+3*curve`,
  vérifié `stroke.cpp:27`) — pas de nouveau champ JSON, pas de JNI.
- **Un seul `pushBrush()`** : règle Slice 3 §5 étendue — `ToolPalette`,
  `PinnedBrushBar` et le nouvel éditeur courbe écrivent via le
  `BrushPrefs` hoisté unique, jamais de `setBrush` direct concurrent
  (interdiction répétée : double `setBrush` = JSON écrasé).
- **Overlays composite-only** : curseur anneau (§2), loupe pression
  (§5), pastille taille/pression (§5) vivent dans le `renderLoop`
  canvas (`lockCanvas`/`drawCircle`/`drawText`) ou en Compose au-dessus
  de la surface — **jamais dans les pixels moteur**. `pullFrameFull`
  après curseur/feedback seul = frame inchangée (garantie testable,
  comme la rotation Slice 1 et les teintes onion Slice 4).
- **Persistance** : `curve` rejoint `radius/opaque/stabilize` dans
  `brush-defaults.json` (`version` implicite : clés optionnelles,
  fallback `0` si absente/corrompue — même garde que Slice 3). La
  courbe par points (§3) se sérialise en 4 floats (les ordonnées),
  pas en bitmap.

## 2. Curseur taille réelle — anneau au diamètre du brush

Cible : en hover stylet (ou doigt posé sans bouger), un anneau montre
**exactement** le diamètre de pose du dab à pression courante.

- **Rendu** : cercle `Paint.Style.STROKE`, `strokeWidth` 2px écran,
  couleur blanc 90 % + ombre portée noire 50 % (lisible sur canvas
  clair ET sombre, comme la loupe Slice 1), tracé dans le `renderLoop`
  après `drawBitmap` (même passe que la loupe, avant `unlockCanvasAndPost`).
  Centre = position curseur écran, rayon écran = `dabRadiusMoteur *
  échelleVue` où `échelleVue = fitScale * viewScaleMul` (matrice Slice 1
  inversée dans l'autre sens : moteur → écran). Pression hover sans
  contact = `ppressure` stabilisée courante si stroke ouvert, sinon
  **0.6 nominale** ( Convention : l'anneau au repos montre le dab à
  pression moyenne, pas le max — évite de promettre un gros trait).
- **Sources de position** :
  - Stylet hover : `onHoverEvent` (`ACTION_HOVER_MOVE`, `TOOL_TYPE_STYLUS`)
    — actuellement **non géré** dans `EngineSurface` (vérifié : seul
    `onTouchEvent` + `onGenericMotionEvent` molette existent). La spec
    ajoute `hoverX/hoverY/hoverActive` volatiles + `invalidate` via
    `needsComposite=true` (même pattern que `markNavChanged`).
  - Doigt : pas de hover capacitif — l'anneau suit le `ACTION_DOWN/MOVE`
    en cours (centre = doigt, rayon = dab à la pression live du point).
  - Souris : `onHoverEvent` souris aussi (molette = zoom existant, le
    curseur suit le pointeur).
- **Gomme** : anneau **pointillé** + teinte ambre (`#E0A050`) vs plein
  blanc pour le pinceau — distinction immédiate sans lire un label
  (§6 strings `cursor_brush/cursor_eraser` en content-description).
  Mode navigation (`navigateMode`) : anneau **masqué** (on pane, on ne
  peint pas — même logique que `strokePointerId=-1`) ; pipette armée :
  anneau remplacé par la pastille pipette Slice 2 (pas deux curseurs).
- **Masques** : hors papier (`!isOnPaper`) = anneau grisé à 30 % (sur
  le damier fond, on ne peint pas) ; pendant `suspendRender` (solo
  Slice 3) ou `navigating` (2 doigts) = masqué (pas de curseur
  mensonger pendant un geste vue) ; calque masqué/invisible actif =
  anneau barré (diagonale, « ce stroke ne marquera pas »).
- **Perf** : 1 cercle + 1 ombre par frame hover — négligeable (< 0.5 ms).
  `needsComposite` déjà ré-armé à chaque frame hover : sur swiftshader
  tablette, throttle hover à 30 Hz max (ne re-armer que si `> 33 ms`
  depuis la dernière frame curseur).

## 3. Courbe de pression — graphe éditable par points

Le slider `curve` 0..100 % actuel est abstrait (personne ne sait ce que
« 63 % » peint). La Slice 5 le remplace par un **graphe tactile**
`pression entrée (x) → pression sortie (y)`, 4 points éditables.

- **Modèle** : 4 points `(x_i fixé, y_i réglable)` : x = 0 / 0.33 / 0.66 / 1,
  y ∈ [0,1] chacun. Rendu : polyline lissée (Catmull-Rom → ~32
  échantillons) affichée en overlay Compose (Canvas maison, 200×140dp,
  fond grille 3×3 + diagonale pointillée « linéaire » témoin).
  **Mapping vers le moteur** : la courbe par points est
  **approximée** par le champ `curve` existant au `pushBrush()` —
  moindres carrés sur l'exposant : choisir `curve` ∈ [0,1] qui minimise
  `Σ (t^(1+3*curve) − user(t))²` sur 16 échantillons (calcul app-side
  ~µs, table 17 entrées pré-calculables). Le graphe exact reste
  **documentation visuelle app-side** ; le moteur reçoit le meilleur
  `curve` scalaire (honnêteté : le test §7 assert le scalaire, pas une
  courbe parfaite — le moteur ne sait faire que `pow`, vérifié
  `stroke.h:17-18`).
  - Presets 1-tap : `Linéaire` (y=x, curve=0), `Doux` (concave, curve≈0.3),
    `Ferme` (convexe, curve≈0.7) — chips 48dp sous le graphe.
  - Garde : y clampés [0,1], monotonie **non exigée** (l'utilisateur peut
    faire une cloche — l'approximation `pow` la lissera, documenté dans
    le hint ; pas de validation bloquante).
- **UI** : remplace le slider `curve` dans `showAdvanced` (`ToolPalette`)
  — le slider reste en fallback accessibilité (même valeur `curve`
  approximée, synchro bidirectionnelle : drag point → recalcule `curve`
  → slider suit ; drag slider → reset points sur la courbe `pow`).
  Drag point = touch vertical sur la poignée 48dp (visuel 24dp +
  padding, pattern Slice 2 §5). `onValueChangeFinished` → `pushBrush()`
  (pas de `setBrush` par frame de drag — même throttle que sliders).
- **Persistance** : `BrushPrefs` gagne `curvePoints: FloatArray(4)`
  (défaut linéaire `[0, 1/3, 2/3, 1]` en y) + `curve` scalaire dérivé ;
  fichier `brush-defaults.json` : clés `curve` + `curvePts` optionnelles
  (absentes = fallback linéaire, jamais de crash — garde Slice 3).
- **Hors portée assumée** : vraie courbe par points côté moteur
  (table LUT transmise en JSON) = **futur documenté** (`curveLut` 16
  floats, `ARCHITECTURE.md` d'abord, JNI/shim ensuite). La Slice 5 ne
  promet que l'approximation scalaire — le graphe est un meilleur
  **contrôle** du même champ, pas un nouveau moteur.

## 4. Vraie gomme — outil dédié, pas hack couleur

État : `mode:"eraser"` = peinture blanche opaque (dangereux : sur calque
transparent, effacer = **créer** du blanc opaque ; sur pile multi-calques,
ça **enterre** les calques dessous au lieu de révéler).

Décision Slice 5 (app-side, JNI gelé) — **outil gomme honnête en 2 temps** :

1. **Temps 1 (cette spec, app-side)** : la gomme devient un **outil
   dédié** avec état propre, pas un mode du pinceau :
   - `BrushPrefs` gagne `tool: PINCEAU | GOMME` (persisté, clé `tool`) ;
     `ToolPalette` chips existantes conservées (déjà `brush/eraser`,
     non-régression) mais la gomme a désormais **sa taille et son
     opacité propres** (mémoire séparée : `eraserRadius/eraserOpaque`,
     persistées `eRadius/eOpaque` — changer d'outil ne casse plus le
     réglage du pinceau, leçon presets Slice 3).
   - Garde anti-dégât : si le calque actif est **transparent** (vignette
     damier / `soloRender` vide) OU si la pile a > 1 calque visible, un
     **bandeau honnête** s'affiche au-dessus de la PinnedBar : « gomme =
     peinture claire (moteur actuel) » (`R.string.eraser_covers_hint`,
     §6) + bouton `En savoir plus` vers la fiche Aide (§6). Pas de
     blocage (workflow tactile), mais **jamais de blanc silencieux**.
   - Curseur §2 pointillé ambre + chip gomme ambre dans la barre
     (vs blanc pinceau) — l'outil se voit avant de toucher.
   - Sur **calque vectoriel** : la gomme appelle déjà le vrai
     `vectorEraser` (suppression de paths, vérifié) — y router
     explicitement quand `isVector` (comportement existant conservé,
     juste documenté + testé §7.4).
2. **Temps 2 (futur, PAS cette slice)** : vrai alpha-erase moteur
   (`dest-out` : `a -= m`, rgb re-clampés `[0,a]`, contrat M3 alpha) —
   documenté ici, implémenté après `ARCHITECTURE.md` :
   `nSetBrush` accepterait `"mode":"alpha-eraser"` (ou bit dédié),
   `stampResolved` brancherait vers `eraseDab` au lieu de `dabFromPressure`
   blanc, undo via `touchTile` existant (même chemin que `paintStamp`).
   La spec **interdit** de bricoler un JNI vite fait sans passer par
   `ARCHITECTURE.md` (règle gel, rappel Slice 3 §1).

## 5. Latence + feedback — ce qui existe, ce qu'on ajoute

**Existant (ne pas réécrire)** : queue UI → render thread (`QueuedPoint`
+ historique `MotionEvent`), `needsComposite=true` à chaque point
drainé, `nCompositeInto` + blit par frame. Le stroke est déjà
**incrémental** : l'encre apparaît au rythme du render-loop (≈ frame
suivante du point). `pressureOf` coerce `0.05..1`, souris → 0.5.

**Ajouts Slice 5 (feedback seul, zéro JNI)** :

- **Pastille taille/pression live** : pendant un stroke (et 800 ms après
  le `UP`, même TTL que la loupe Slice 1), chip flottante haut-droit
  canvas (offset 8dp, fond 80 % — même langage que la loupe) :
  `« 24px · 72 % »` (diamètre dab courant = `radius*(0.25+0.75*pr)` à
  la pression live + pression %) en `labelSmall` monospace. Disparaît
  avec la même règle que la loupe (`isLoupeVisible` étendu ou flag
  jumeau `isBrushHudVisible`, décision implémentation — même TTL).
- **Throttle HUD** : la pastille se met à jour au plus à 10 Hz (le
  stroke peut pousser 120 pts/s ; le texte à 120 Hz = jank Compose).
  Le canvas bitmap suit chaque frame (inchangé), seul le texte est
  throttlé.
- **Latence mesurable** : exposer test-only `lastStrokeLatencyMs`
  (temps `ACTION_DOWN` → premier `needsComposite=false` consommé) pour
  le test §7.5 — pas d'UI, juste le crochet de mesure.

## 6. Strings FR/EN proposées + téléphone

Convention Slices 0-4 : clés snake_case, accents FR en `values-fr` only.

| Clé | EN (`values`) | FR (`values-fr`) | Usage |
|---|---|---|---|
| `cursor_brush` | Brush %1$dpx | Pinceau %1$d px | §2 content-description anneau |
| `cursor_eraser` | Eraser %1$dpx | Gomme %1$d px | §2 anneau pointillé ambre |
| `cursor_offpaper` | Outside canvas | Hors canvas | §2 anneau grisé |
| `cursor_blocked` | Layer hidden | Calque masqué | §2 anneau barré |
| `curve_title` | Pressure curve | Courbe pression | §3 titre graphe |
| `curve_linear` | Soft | Doux | §3 preset concave |
| `curve_firm` | Firm | Ferme | §3 preset convexe |
| `curve_linear_reset` | Linear | Linéaire | §3 preset y=x |
| `curve_hint` | Shape the stroke response | Façonnez la réponse | §3 hint + note lissage |
| `eraser_covers_hint` | Eraser paints light (engine) | Gomme = peinture claire (moteur) | §4 bandeau honnête |
| `eraser_learn_more` | Learn more | En savoir plus | §4 fiche Aide |
| `brush_hud` | %1$dpx · %2$d%% | %1$d px · %2$d %% | §5 pastille live |
| `brush_help` | Brush feeling… | Feeling pinceau… | Aide, fiche §2-§5 |

Clés existantes conservées : `brush`, `eraser`, `stabilize`, `curve`,
`opacity`, `layer_opacity_brush`, `nav_mode_*`, `pipette*`.

**Fallback téléphone 1080×2400 (portrait)** : graphe courbe 160dp large
(pleine largeur feuille), presets chips scroll horizontal ; anneau
curseur = doigt seul (pas de hover) + pastille HUD réduite (`24px`
seul, pression masquée si < 360dp de large) ; bandeau gomme 2 lignes
max + `maxLines/ellipsis` (leçon Slice 0 §7).

## 7. Tests instrumentés à ajouter (gate local `emutest.sh`)

Fichier existant étendu : `EngineConformanceTest.kt` (émulateur KVM
local, jamais GitHub CI — règle AGENTS.md). Pattern établi
(`findEngineSurface`, `MotionEvent.obtain` + `dispatchTouchEvent`,
`pullFrameFull` + scan pixels, `Thread.sleep`).

1. `cursorMatchesBrushDiameter` — `setBrush` 24px via `BrushPrefs`
   (courbe linéaire, pression nominale 0.6 → dab `24*(0.25+0.75*0.6) =
   16.8px` moteur) ; hover stylet simulé (`ACTION_HOVER_MOVE`,
   `TOOL_TYPE_STYLUS`) au centre ; assert : anneau affiché (nœud ou
   flag test-only `cursorRing() != null`) ET rayon écran ≈ `16.8 *
   fitScale * viewScaleMul` à ±2px (prouve moteur→écran via matrice
   Slice 1) ; doigt levé = anneau masqué ; `pullFrameFull` identique
   avant/après hover (0 px — overlay-only).
2. `curveChangesRendering` — stroke témoin à pression simulée 0.5
   (courbe linéaire, `curve=0`) → mesure couverture (px non-blancs) ;
   passe `curve=1` (exposant 4 : `0.5^4=0.0625` — dab quasi invisible,
   vérifié `test_stroke.cpp:22`) → même stroke → assert : couverture
   **nettement plus faible** (ratio < 0.5) ; puis graphe 4 points
   « ferme » → `pushBrush()` → assert : champ `curve` transmis ≈ 0.7
   à ±0.15 (approximation §3, lue via spy test-only sur le JSON
   `setBrush` ou `BrushPrefs.curve`) ; persistance : `recreate()` +
   `sleep` 700 ms → points restaurés.
3. `eraserSparesOtherLayers` — 2 calques : rouge en bas, bleu en haut
   (actif) ; stroke gomme sur le bleu ; assert **honnête au moteur
   actuel** : composite changé (pas tout blanc, pas tout bleu) ET
   calque bas **pixel-identique** (`soloRenderLayer(0)` avant/après
   byte-identique — la gomme ne touche que le calque actif, même en
   mode peinture-blanche) ; le test **documente** la limite (blanc
   opaque, pas de transparence révélée) et porte un `TODO(alpha-erase)`
   lié au Temps 2 §4 — il doit **échouer volontairement** si un jour
   le blanc recouvre le bas (garde anti-régression du hack).
4. `eraserToolSeparation` — règle pinceau 24px/opacité 100 % puis
   gomme 48px/50 % ; switch pinceau→gomme→pinceau ; assert :
   `BrushPrefs.radius/eraserRadius` inchangés chacun (pas d'écrasement
   croisé), `currentJson()` porte `mode:"brush"` puis `"eraser"`,
   curseur ambre pointillé en mode gomme (flag test-only) ; bandeau
   `eraser_covers_hint` visible sur pile 2 calques, absent sur calque
   unique opaque.
5. `strokeStaysIncrementalAndHudLives` — `ACTION_DOWN + MOVE×5` lents
   (100 ms d'intervalle) ; assert : composite intermédiaire (après 3e
   MOVE, avant UP) contient déjà de l'encre (> 0 px non-blancs —
   prouve l'incrémental existant, non-régression) ET pastille HUD
   visible avec `24px · …` (nœud `contentDescription = brush_hud`) ;
   1 s après UP : HUD disparu ; `lastStrokeLatencyMs` (test-only)
   < 500 ms sur KVM (garde grossière, pas de benchmark).
6. `touchTargets48dpBrush` — poignées courbe (4×), presets, chips
   gomme/pinceau, pastille repli PinnedBar : `boundsInRoot ≥ 48dp`
   (tolérance 0.5dp) en 1920×1200 **et** 1080×2400.

Exigences communes : reset (`tool=pinceau`, 10px/100 %/15 %/courbe
linéaire, onion OFF, `cur=0`) en teardown ; seuils couleur §7 Slice 2
(±1 LSB, dominante +40) ; debounce persistance (`sleep` ≥ 700 ms) avant
asserts fichier ; tests 1-5 tablette, smoke téléphone : au moins 1 + 6.

## 8. Portée Slice 5 (ce que cette spec autorise à coder ensuite)

1. `onHoverEvent` stylet/souris + `hoverX/hoverY/hoverActive` + anneau
   §2 (rayon moteur→écran, pointillé ambre gomme, masques hors-papier /
   navigation / solo-render / pipette).
2. Graphe courbe 4 points + presets + approximation `curve` (§3) +
   persistance `curve/curvePts` dans `BrushPrefs` + slider fallback
   synchro.
3. Outil gomme dédié : `tool` + `eraserRadius/eraserOpaque` persistés,
   bandeau honnête §4, routage vectoriel explicite. **Aucun JNI.**
4. Pastille HUD taille/pression (10 Hz, TTL 800 ms, §5) + crochet
   `lastStrokeLatencyMs` test-only.
5. Strings §6 (EN+FR), fiche Aide feeling, téléphone replié.
6. Les 6 tests §7 verts en `emutest.sh` local (tablette ; smoke
   téléphone 1+6).

Hors portée (futur, `ARCHITECTURE.md` d'abord) : `curveLut` 16 floats
(vraie courbe moteur), `mode:"alpha-erase"` + `eraseDab` dest-out
(Temps 2 §4), `nLayerCompositeInto` (Slice 3 §1), pression tilt,
stabilisation gestuelle, workspaces (n°6), miniature (n°9).

## 9. Risques

- **Approximation courbe décevante** : une cloche utilisateur → `pow`
  monotone : le rendu ne suit pas le dessin du graphe. Atténuation :
  hint honnête (§3) + presets qui eux mappent exactement ; si la revue
  juge l'écart inacceptable : repli = slider `curve` seul (le graphe
  devient visualisation lecture-seule). Le futur `curveLut` lève
  définitivement le risque.
- **Gomme blanche = dégâts silencieux** : malgré le bandeau, un
  utilisateur peut « effacer » un transparent en blanc opaque sans
  undo. Atténuation : bandeau §4 + statut éphémère au premier usage
  par session (« gomme = peinture claire ») + undo M7 inchangé
  (1 tap répare) ; le vrai alpha-erase est le seul fix structurel.
- **Hover inexistant sur émulateur** : `ACTION_HOVER_MOVE` au doigt
  n'existe pas — le test 1 doit simuler `TOOL_TYPE_STYLUS`, sinon
  l'anneau téléphone/doigt ne se couvre que via le stroke en cours.
  Si l'injection hover échoue en test : fallback = exposer
  `hoverForTest(x,y)` test-only (décision implémentation, documentée).
- **Anneau vs perf** : `needsComposite` à 30 Hz hover + blit 2048×1536
  sur swiftshader — risque > 16 ms/frame. Atténuation : throttle
  33 ms (§2) + anneau seul sans re-pull (le bitmap composite est
  réutilisé, seul l'overlay est redessiné — le `needsComposite=false`
  suffit si le bitmap est frais ; sinon skip hover-frame).
- **Double `setBrush`** : graphe + sliders + PinnedBar écrivant dans la
  même seconde = JSON écrasé (leçon Slice 3 §10). Atténuation : tout
  passe par `BrushPrefs` + un seul `pushBrush()` par frame (debounce
  drag, `onValueChangeFinished` pour la courbe).
- **i18n** : `Façonnez la réponse`, `Gomme = peinture claire (moteur)`
  longs en FR — `maxLines=1..2` + ellipsis (leçon Slice 0 §7).
