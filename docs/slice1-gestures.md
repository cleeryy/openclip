# Slice 1 — Gestes canvas (SPEC, sans code)

> SPEC UNIQUEMENT — aucune modification app/engine. Vocabulaire maison,
> aucun asset ni capture d'outil tiers. Clean-room : jamais de strings,
> icônes, noms de palettes ni layouts repris de Clip Studio Paint / Celsys
> (cf. `docs/csp-ux-research.md` § clean-room). JNI gelé (`ARCHITECTURE.md`) :
> tout ce qui suit est **app-side uniquement** (`EngineSurface.kt`,
> `MainActivity.kt`, `ToolPalette.kt`, ressources `R.string`).
> Conventions : FR/EN via `R.string` uniquement, pas de littéraux FR
> en code (leçon M5 : apostrophes FR en dur cassent le build).

État vérifié (point de départ) : Slice 0 committé (`d5a2af5`) — `MenuBar`
(A), `DocRegistry` single-doc (B), `CollapsibleDrawer`, garde multi-touch
dans `EngineSurface.onTouchEvent` : **2+ pointeurs = navigation consommée,
aucun stroke**, rollback du stroke interrompu via `end + undo` côté render
loop (`navigating=true` drop + `nStrokeEnd` + `nUndo`). Spec shell :
`docs/slice0-shell.md`. Référence gestes : `docs/csp-ux-research.md` §R6
(2-doigts tap=undo, 3-doigts tap=redo, 2-doigts drag=pan, rotation 2
doigts=rotate vue, pinch=zoom ; séparation doigts-outils / stylet-dessin ;
edge-swipe ; palm rejection stylus-first).

## 1. Principe — matrice de vue, jamais pixels moteur

La Slice 1 ajoute une **matrice de vue purement app-side** :

```
view = { offsetX, offsetY (px écran), scale (×), angle (degrés) }
```

- Défauts : `offset=(0,0)`, `scale=fit` (valeur actuelle de
  `computePaperRect`), `angle=0`.
- Appliquée **uniquement** dans `computePaperRect` (le `RectF` destination
  devient `fit * scale + offset`, pivoté de `angle` autour du centre vue)
  et dans `drawBitmap` (même `dst` + rotation canvas `c.rotate(angle, cx,
  cy)` autour du centre de la vue, avant `drawRect` du cadre).
- **Jamais aux pixels moteur** : `engineWidth/engineHeight` (2048×1536),
  `mapToEngineX/Y`, `nStrokeBegin/Move/End`, `nCompositeInto`,
  `nFrameCompositeInto`, `pullFrameFull` restent inchangés. Le mapping
  tactile inverse la matrice (écran → papier → moteur) ; les règles,
  la symétrie et l'onion skin ne voient que des coordonnées moteur.
- Pas de persistance disque en Slice 1 (la vue est éphémère par session ;
  reset à `fit` sur rotation écran / nouveau doc). Workspaces nommés
  (backlog n°6) pourront la sauver plus tard en JSON maison — clé réservée,
  pas implémentée ici.
- Conséquence testable : à `angle≠0` ou `scale≠fit`, un stroke dessine
  toujours au bon endroit moteur (le mapping inverse compense), et
  `pullFrameFull` retourne un composite **non pivoté** (la rotation est
  overlay d'affichage, comme le mannequin M9).

Limites : `scale` clampé `[0.25×fit … 8×fit]`, `angle` normalisé
`(-180°, +180°]`, `offset` clampé pour que le papier reste attrapable
(au moins 1/4 de la feuille visible). Reset 1-tap (double-tap 1 doigt ?
non — voir §2 : bouton + raccourci, pas de conflit avec le dessin).

## 2. Mapping gestuel exact

Priorité de décision dans `onTouchEvent`, évaluée **avant** tout stroke :

| Entrée | Condition | Action |
|---|---|---|
| Stylet (`TOOL_TYPE_STYLUS`, `ACTION_DOWN`) | toujours, même si un doigt est déjà posé | **dessin prioritaire** (palm rejection stylus-first, §5) ; le doigt en cours est ignoré (pas de pan) |
| 1 pointeur doigt/souris | mode dessin (défaut) | **dessin** (comportement actuel inchangé) |
| 1 pointeur doigt | mode navigation (toggle §3 actif) | pan 1-doigt (déplacement vue), aucun stroke |
| 2 doigts tap (< 250 ms, < 24dp de bougé) | — | **undo** (= `es.undo()`, même chemin que le bouton C) |
| 3 doigts tap (< 250 ms) | — | **redo** (= `es.redo()`) |
| 2 doigts drag (bougé ≥ 24dp, Δangle < 8°) | — | **pan** : `offset += centroidDelta` |
| Pinch (Δdistance, Δangle < 8°) | — | **zoom** : `scale *= d1/d0` autour du centroïde (pivot sous les doigts fixe à l'écran) |
| Rotation 2 doigts (Δangle ≥ 8°) | — | **rotate vue** : `angle += Δangle` autour du centroïde ; pinch+rotate combinés autorisés (appliquer scale puis angle dans le même frame) |
| Transition 1→2 pointeurs mid-stroke | — | **déjà gérée Slice 0** : `navigating=true`, stroke interrompu `end+undo`, aucun reste d'encre ; la Slice 1 ajoute seulement le pan/zoom/rotate réel pendant la phase à 2 doigts (au lieu de juste consommer) |
| Edge-swipe (bord gauche/droit, ≤ 32dp du bord, swipe ≥ 64dp vers l'intérieur) | panneau adjacent replié | rouvre le panneau (dock / rangée outils) ; **jamais** de stroke, même en mode dessin |
| Edge-swipe bord haut/bas | — | ignoré (réservé système + tiroir timeline a son toggle) |

Notes :

- Tap = `ACTION_DOWN` + `ACTION_UP` rapides **sans** `ACTION_MOVE`
  au-delà du seuil ; le tap 2/3 doigts ne doit ni commencer de stroke ni
  appeler `undo/redo` deux fois (debounce 400 ms entre deux taps système).
- Le tap 2-doigts **après** un stroke (doigts posés une fois le stylet
  levé) annule le stroke : c'est le test phare (§7).
- Souris : bouton gauche = dessin, molette = zoom autour du curseur,
  drag molette/milieu = pan. Pas de rotate souris en Slice 1.
- Aucun geste ne crée de couche, ne change d'outil, n'ouvre de dialogue :
  gestes = vue + undo/redo uniquement.

### Pourquoi « 1 doigt = dessin par défaut » ?

Recherche §R6 : séparation doigts-outils / stylet-dessin existe chez la
référence, mais exiger le stylet exclurait le test au doigt sur émulateur
(KVM, pas de stylet) et les téléphones sans stylet. Décision : **1 doigt
dessine par défaut** (comportement actuel préservé, zéro régression) ;
un **toggle navigation** (§3) inverse le 1-doigt en pan pour les utilisateurs
stylet-first. Le 2-doigts reste navigation dans les deux modes.

## 3. Mode navigation vs dessin

- Toggle dans la rangée outils C (Slice 0) + entrée menu Affichage :
  icône maison `main` (pan) vs `pinceau` (dessin). Défaut = dessin.
- Effet : seul le **1-doigt** change de sens (dessin ↔ pan). Tout le reste
  (§2) est identique dans les deux modes ; le stylet dessine **toujours**,
  même en mode navigation (stylus-first, §5).
- Feedback : curseur/tooltip du toggle + statut (`status_nav_draw` /
  `status_nav_move`, §6) ; le canvas ne change pas de fond (le damier/sombre
  Slice 0 reste).
- Le mode survit au repli des panneaux et au switch d'onglet (état
  `remember` dans `MainActivity`, pas par doc en Slice 1 — multi-docs
  N-sessions = backlog n°5, hors portée).

Hors portée : pression/tilt configurables, stabilisation gestuelle,
raccourcis clavier PC, double-tap stylet latéral.

## 4. Edge-swipe

- Zones : bandes 32dp le long des bords gauche (rangée outils / dock en
  portrait) et droit (dock 290dp). Le swipe part **du bord vers
  l'intérieur** (≥ 64dp) et rouvre le panneau replié correspondant
  (règle Slice 0 « 1 tap = disparu », ici le swipe = le tap inverse).
- Arbitrage : l'edge-swipe est testé **avant** le stroke 1-doigt ; un
  `ACTION_DOWN` dans la bande arme un détecteur, annulé dès que le doigt
  sort du pattern (bougé vertical dominant, ou 2e pointeur → navigation
  normale). Aucun stroke ne démarre depuis la bande tant que le détecteur
  est armé.
- Téléphone (bottom sheet) : edge bas → rouvre la feuille ; bords
  gauche/droit restent dessin (pas de dock latéral en portrait).
- Pas de clavier/edge-keyboard en Slice 1 (mentionné en R6, renvoyé au
  backlog barre de commandes n°8).

## 5. Palm rejection (stylus-first) + cas limites

- **Stylus-first** : dès qu'un `TOOL_TYPE_STYLUS` touche l'écran, tous les
  contacts doigt actifs sont ignorés jusqu'au `ACTION_UP` stylet ; aucun
  pan/zoom ne démarre d'une paume posée avant le stylet. Implémentation :
  suivi du `toolType` par `pointerId`, pas seulement `pointerCount`
  (la garde Slice 0 `pointerCount >= 2` reste le filet de sécurité).
- **Paume seule** (gros contact, `pressure` saturée / `size` > seuil,
  `TOOL_TYPE_PALM` quand dispo) : traitée comme navigation (consommée,
  aucun stroke), jamais d'encre.
- **Transition 1→2 pointeurs mid-stroke** : inchangée Slice 0 (rollback
  `end+undo`) + la vue suit désormais les doigts (§2). Retour 2→1 : le
  doigt restant **ne reprend pas** le stroke annulé ; un nouveau stroke
  exige un `ACTION_DOWN` frais (évite les traits parasites au lever d'un
  doigt du pinch).
- **Multi-touch > 3 pointeurs** : navigation consommée, aucun undo/redo
  (seuls les taps exactement-2 / exactement-3 déclenchent).
- **Doigts sur UI** : les gestes ne s'évaluent que dans `EngineSurface` ;
  un 2e doigt posé sur un panneau ne compte pas (pas de tracking
  inter-vues). Panneaux repliés = 0px (règle Slice 0 n°5), donc aucune zone
  morte tactile autour du canvas.
- **Performance** : calculs vue en `Float` côté UI thread uniquement ;
  `renderLoop` lit une copie volatile ; aucun verrou moteur ajouté
  (`engineLock` inchangé, pas de JNI nouveau).

## 6. Feedback visuel + strings

Pendant la navigation, overlay **composite-only** (jamais dans les pixels) :

- Loupe discrète en coin canvas : `zoom %` (ex. `100 %`) + `angle °`
  (ex. `+15°`, masqué si `|angle| < 0.5°`) + pastille mode (pinceau/main).
  Disparaît 800 ms après le dernier événement navigation.
- Double-tap 1-doigt : **rien** (réservé au dessin, pas de zoom-toggle —
  évite le conflit avec les doubles-traits rapides).
- Bouton reset vue (cadrage `fit`, angle 0) : dans le menu Affichage +
  chip dans la loupe pendant qu'elle est visible.
- Message statut éphémère existant réutilisé pour undo/redo tactiles
  (mêmes strings que les boutons, pas de doublon).

Strings maison (`R.string`, EN `values` / FR `values-fr`, placeholders
comme l'existant, accents FR uniquement en `values-fr`) :

| Clé | EN | FR | Usage |
|---|---|---|---|
| `nav_mode_draw` | Draw | Dessin | toggle C, 1-doigt = dessin |
| `nav_mode_move` | Navigate | Naviguer | toggle C, 1-doigt = pan |
| `view_reset` | Reset view | Réinitialiser vue | Affichage + chip loupe |
| `view_zoom_fit` | %1$d%% | %1$d %% | loupe zoom |
| `view_angle` | %1$d° | %1$d° | loupe angle |
| `status_nav_draw` | Draw mode | Mode dessin | statut |
| `status_nav_move` | Navigate mode | Mode navigation | statut |
| `undo_touch` | Undo (two-finger tap) | Annuler (tap 2 doigts) | aide gestes |
| `redo_touch` | Redo (three-finger tap) | Rétablir (tap 3 doigts) | aide gestes |
| `gestures_help` | Canvas gestures… | Gestes canvas… | Aide, ouvre la fiche §2 |

Aide : une fiche « Gestes canvas » (dialogue maison, texte seul, mêmes
mots que §2) accessible depuis le menu Aide — pas de schéma copié, pas de
capture tiers.

## 7. Interaction règles / symétrie / moteur

- `setRulers` (shim JSON `symmetryX/symmetryY`, toggles `LayerPanel`
  l.275/287 + `ToolPalette` l.89) : coordonnées **moteur**, donc la vue
  **suit automatiquement** — aucun changement : la ligne de symétrie reste
  collée au bon pixel quel que soit zoom/rotate (le mapping inverse
  compense). À documenter dans la fiche Aide (1 phrase).
- Règles M6 (perspective 3-points, guides) : overlay d'affichage comme la
  loupe — pivotées avec `angle` côté canvas, valeurs moteur intactes.
- Onion skin / cels / filtres / tones / 3D : inchangés (composites moteur,
  la vue ne fait que cadrer le bitmap final).
- Undo tactile = `es.undo()` / redo = `es.redo()` : mêmes stacks par trame
  M7, `onStrokeChanged` notifié pareil, `DocRegistry.markActiveDirty`
  inchangé.

## 8. Tests instrumentés à ajouter (gate local `emutest.sh`)

Fichier existant étendu : `EngineConformanceTest.kt`
(émulateur KVM local, jamais GitHub CI — règle AGENTS.md). Chaque test
suit le pattern établi (surface trouvée via `findEngineSurface`,
`MotionEvent.obtain` + `dispatchTouchEvent`, `Thread.sleep(350)`,
`pullFrameFull` + scan pixels, `es.undo()`).

1. `pinchZoomLeavesNoInk` — stroke 1-doigt annulé par arrivée 2e doigt
   (déjà couvert en rollback Slice 0) **+** écartement pinch (2 DOIGTS,
   `ACTION_MOVE` à 2 pointeurs via `MotionEvent.obtain` multi-pointer) ;
   assert : `pullFrameFull` tout blanc (0 pixel non-blanc) ET `es.undo()`
   == `false` (rien à annuler — le rollback a déjà nettoyé).
2. `twoFingerTapUndoesStroke` — trace un stroke 1-doigt (pattern
   `canvasSurfaceHasVisibleSizeAndTouchDrawsStroke`), attend encre
   (> 20 px non-blancs), simule tap 2-doigts (DOWN×2 + UP, < 250 ms,
   bougé < 24dp) ; assert : pixels redevenus blancs ET statut undo.
3. `threeFingerTapRedoesStroke` — après (2), tap 3-doigts ; assert : encre
   de retour (> 20 px) — symétrique du redo bouton.
4. `twoFingerDragPansWithoutInk` — drag 2-doigts ≥ 60dp ; assert : pas
   d'encre (frame blanche) ET `offset != (0,0)` (exposé via getter
   app-side `viewOffset()` — pas de JNI) ET un stroke ultérieur dessine
   toujours au centre moteur (mapping inverse sain).
5. `rotateGestureTiltsWithoutInk` — rotation 2-doigts ≥ 15° ; assert : pas
   d'encre ET `viewAngle()` ≈ ±15° à 2° près ; `view_reset` remet angle 0.
6. `oneFingerDrawsInBothModes` (paramétré dessin/navigation) — en mode
   navigation, 1-doigt = pan (pas d'encre, offset bougé) MAIS stylet
   simulé (`TOOL_TYPE_STYLUS`) dessine quand même (encre présente) ;
   en mode dessin, 1-doigt dessine (non-régression du test Slice 0).
7. `edgeSwipeReopensDockWithoutInk` (tablette 1920×1200) — dock replié,
   swipe depuis le bord droit ; assert : dock visible ET frame blanche.

Exigences communes : seuils temps/distance (§2) en constantes nommées
`companion` testées aux bornes (249/251 ms, 23/25dp) au moins pour le tap ;
debounce 400 ms vérifié (double tap rapide = 1 seul undo) ; chaque test
laisse la vue reset (fit/angle 0) pour ne pas polluer le suivant.

## 9. Portée Slice 1 (ce que cette spec autorise à coder ensuite)

1. État vue `{offset, scale, angle}` + getters `viewOffset/viewAngle/
   viewScale` + `resetView()` (app-side, `EngineSurface`).
2. `computePaperRect`/`drawBitmap`/`mapToEngineX/Y` conscients de la vue
   (§1) + loupe overlay + chip reset.
3. Machine gestes §2 (stylus-first, taps 2/3 doigts, pinch/pan/rotate,
   edge-swipe §4, toggle dessin/navigation §3).
4. Strings §6 (EN+FR), fiche Aide gestes, entrée Affichage reset vue.
5. Les 7 tests §8 verts en `emutest.sh` local (tablette 1920×1200 ;
   smoke 1080×2400 : au moins 1-4 verts, edge bas = bottom sheet).

Hors portée : persistance vue / workspaces (n°6), couleur PRO (n°2),
multi-docs N-sessions (n°5), vignettes (n°7), command bar (n°8),
miniature navigation (n°9), clavier edge, molette rotate, tilt/stabilisation
gestuelle — tous renvoyés au backlog `csp-ux-research.md` §3.

## 10. Risques

- **Faux undo au lever de pinch** : le retour 2→1 doigts ressemble à un
  tap si les seuils sont mal ordonnés — d'où la règle « pas de reprise de
  stroke sur doigt restant » (§5) + debounce ; à surveiller sur écran
  capacitif bruité (émulateur = tactile parfait, le tactile réel bave).
- **Conflit edge-swipe / dessin bord-à-bord** : les artistes dessinent
  jusqu'au bord ; bande 32dp trop large = strokes avalés. Atténuation :
  détecteur armé→annulé (§4) + seuil 64dp ; si les tests montrent des
  strokes perdus, réduire à 24dp ou exiger le départ hors-papier.
- **Loupe vs composite perf** : `c.rotate` + overlay texte à chaque frame
  pendant le geste sur tablette swiftshader — rester sous 16 ms ; sinon
  ne redessiner la loupe qu'à 10 Hz (le bitmap suit chaque frame).
- **Téléphone** : bottom-sheet + edge bas = pattern distinct de la
  tablette ; le test 7 doit exister dans les deux variantes (bord droit
  vs bord bas) ou le swipe téléphone restera non couvert.
- **i18n** : `Réinitialiser vue`, `Rétablir (tap 3 doigts)` longs en FR —
  ellipsization + `maxLines=1` sur chips/loupe (leçon Slice 0 §7).
