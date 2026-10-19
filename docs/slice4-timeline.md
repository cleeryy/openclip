# Slice 4 — Timeline pratique (SPEC, sans code)

> SPEC UNIQUEMENT — aucune modification app/engine. Vocabulaire maison,
> aucun asset ni capture d'outil tiers. Clean-room : jamais de strings,
> icônes, noms de palettes ni layouts repris de Clip Studio Paint / Celsys
> (cf. `docs/csp-ux-research.md` § clean-room). JNI gelé (`ARCHITECTURE.md`) :
> tout ce qui suit est **app-side uniquement** (`TimelineStrip.kt`,
> `StudioChrome.kt` (`CollapsibleDrawer`), `MainActivity.kt`, ressources
> `R.string`).
> Conventions : FR/EN via `R.string` uniquement, pas de littéraux FR
> en code (leçon M5 : apostrophes FR en dur cassent le build).

État vérifié (point de départ) : Slices 0-3 committées (shell, gestes,
couleur, calques). Référence workflow : `docs/csp-ux-research.md` §R5
(pistes, cels numérotés, onion skin avant/après teintées + réglage nb
d'images, fps ; tiroir caché en illustration pure ; lecture = timer app,
jamais boucle moteur).
Actuel : `TimelineStrip.kt` (~309 l.) = `LazyRow` de cels (thumbs 96×72dp
via `nFrameCompositeInto`, `clickable` → `setFrame` + `refresh`), rangée
add/duplicate/delete + play/pause, slider fps 1..60 (défaut 12, état
`remember` app-side), checkboxes onion prev/next + slider force
(`surface.setOnion`, fantômes sous le composite), rangée M7b audio/WebM
+ upscale IA (présentation, `MainActivity` propriétaire). Tiroir bas via
`CollapsibleDrawer` (`showTimeline=false`, `heightIn(max=175dp)`),
toggle `▸/▾` en barre statut. Spec shell : `docs/slice0-shell.md`.
Specs précédentes : `docs/slice1-gestures.md` (matrice de vue), slice 2
(couleur), slice 3 (calques).

Constat : la strip actuelle **fonctionne mais n'est pas pratique au doigt** —
thumbs petites (96dp), tap par cel sans scrub, onion binaire prev/next
sans nb d'images ni teintes visibles, fps/lecture éparpillés, tiroir
≤175dp vite saturé dès ~6 cels + 3 rangées de contrôles.

## 1. Principe — tiroir repliable, lecture timer, JNI gelé

```
timeline = tiroir (CollapsibleDrawer) + piste cels + barre transport
lecture  = Handler main-thread @ 1/fps -> setFrame (existant, inchangé)
onion    = overlay composite-only (nSetOnion existant, jamais pixels)
```

- **JNI gelé** : seuls symboles autorisés = ceux déjà utilisés par la
  strip (`addFrame` / `duplicateFrame` / `deleteFrame` / `setFrame` /
  `frameCount` / `nSetOnion` / `nFrameCompositeInto`). Aucun
  `nRenderCel`, `nMoveCel`, `nSetFps` : fps, ordre d'affichage, teintes
  onion restent **état app-side** (`remember`).
- **Lecture = timer app** (R5, déjà le cas) : le `Handler` existant est
  conservé tel quel (step `setFrame`, `needsComposite` côté render loop).
  Pas de boucle moteur, pas de thread nouveau, pas de `MediaPlayer`-like
  côté engine. Pause automatique : app background, tiroir replié, export
  en cours (`exporting=true` désactive déjà play).
- **Tiroir conservé** : `CollapsibleDrawer`, replié par défaut, max 175dp
  (Slice 0 §F inchangé). La Slice 4 **réorganise l'intérieur**, pas le
  contenant. Règle Slice 0 « 1 tap = disparu » et « plein-canvas = A+D+G »
  inchangées.
- **Overlay-only** : scrub, teintes onion, loupe fps ne touchent jamais
  les pixels moteur. `pullFrameFull` après scrub/onion-toggle retourne
  le composite **non teinté** de la trame courante (même garantie que la
  rotation de vue Slice 1).

## 2. Piste cels — scrub tactile + grosses vignettes

Cible : piste utilisable au doigt sur tablette 1920×1200@240, lisible
dès 3 cels comme à 30.

- **Taille cible** : vignettes **128×96dp** (au lieu de 96×72), `spacedBy(8dp)`,
  numéro de cel **sous** la vignette (`labelSmall`, `[i]` si courant —
  existant conservé). Hauteur piste ≈ 96 + 18 (label) + padding ≈ 120dp :
  tient dans les 175dp avec la barre transport (§5) en mode compact.
- **Fenêtre visible** : `LazyRow` conservée, mais **auto-scroll au cel
  courant** pendant lecture/scrub (`listState.animateScrollToItem(cur)`,
  sans animation pendant play — `scrollToItem` direct à 12+ fps pour
  rester sous 16 ms/frame). Pastille `i/N` (« 3/12 ») en tête de piste
  quand `count > fenêtre visible`.
- **Scrub tactile** : drag horizontal **sur la piste** = seek.
  - Détecteur : `draggable`/`detectHorizontalDragGestures` sur le conteneur
    piste (pas sur chaque vignette) ; seuil 16dp puis `cur = indexFor(x)`
    (position → index le plus proche, clampé `[0, count)`) ; `setFrame`
    **au relâcher seulement** + preview du candidat pendant le drag
    (surlignage, pas de `setFrame` continu — évite N `needsComposite`
    par seconde et le spam undo).
  - Pendant le drag : bandeau candidat (`cel i`, thumb agrandie 1.25× en
    overlay, composite-only) ; au `ACTION_UP` : `setFrame(candidat)` +
    `refresh()` (pattern existant).
  - Tap simple sur vignette = comportement actuel inchangé (non-régression).
  - Conflit vertical : drag vertical ≥ 24dp dominant = scroll vertical du
    tiroir parent (s'il existe), pas de seek ; la piste ne consomme que
    l'horizontal dominant.
- **Sélection vs courant** : un seul concept en Slice 4 — le cel affiché
  (`cur`) est la sélection (comme aujourd'hui). Pas de multi-sélection,
  pas de drag-reorder (renvoyé §8).
- **Cibles tactiles** : vignette 128×96 = cible naturelle ≥ 48dp ; les
  petits boutons texte actuels (`frame_add`…) migrent en barre transport
  §5 avec `height ≥ 48dp` (`TextButton` + `Modifier.heightIn(min=48dp)`).

## 3. Onion skin visualisée — teintes + nb images + toggle

Existant : `onionPrev/onionNext: Boolean` + `onionStrength: Float` →
`surface.setOnion(p, n, strength)`. La Slice 4 rend ça **visible et réglable**,
sans toucher au moteur (les teintes sont une interprétation app-side du
fantôme existant ; si `setOnion` ne teinte pas aujourd'hui, la spec
**documente** les teintes comme convention d'affichage, appliquée côté
strip/thumbs — jamais comme filtre pixels).

- **Toggle maître** : un switch/checkbox `Onion` (défaut OFF conservé —
  comportement actuel : les deux checkboxes décochées) qui arme/coupe
  l'ensemble sans perdre les réglages (ré-applique `setOnion` au toggle).
- **Teintes avant/après** : pastilles couleur fixes, vocabulaire maison :
  - avant = **bleu-vert** (`#40C8B8`, alpha = force),
  - après = **rose-orange** (`#E08050`, alpha = force),
  - jamais rouge/vert purs (lisibilité daltonisme de base).
  - Visualisation : bordure teintée 2dp sur les vignettes fantômes dans
    la piste (cels `cur-k … cur-1` en bleu-vert, `cur+1 … cur+k` en
    rose-orange) + légende une ligne (« ● avant ● après », `labelSmall`).
  - Le canvas lui-même reste affiché par le moteur (`nSetOnion`) ; la
    strip ne **filtre aucun pixel** — les teintes strip sont des bordures,
    pas des recomposites.
- **Réglage nb d'images** : deux steppers `− / +` (boutons 48dp) :
  `onionBefore: Int` (0..4, défaut 1 = `onionPrev` actuel),
  `onionAfter: Int` (0..4, défaut 1 = `onionNext` actuel).
  Mapping JNI gelé : `setOnion(p = before>0, n = after>0, strength)` —
  le **nombre** d'images est une indication d'affichage strip (quelles
  vignettes portent la bordure teintée) + documentation pour le futur
  `nSetOnionCount` (hors portée, §8) ; le moteur continue en tout-ou-rien.
  Ne jamais prétendre au moteur plus que `(p, n, strength)`.
- **Force** : slider existant conservé (0..100 %, défaut 30 %), appliqué
  au relâcher (`onValueChangeFinished`, déjà le cas).
- **Garantie testable** : ON/OFF ou changement de teinte/compte ne modifie
  **aucun pixel** du composite courant (`pullFrameFull` identique avant/après
  à ±0 px — le fantôme onion vit sous le composite moteur, la strip ne fait
  que des bordures).

## 4. Cels — ajout / suppression / duplication (inchangés moteur, sécurisés UI)

Symboles existants conservés (`addFrame`, `duplicateFrame(cur)`,
`deleteFrame(victim)`, `setFrame`, `refresh()`). La Slice 4 ajoute la
**sécurité tactile**, pas de nouveaux symboles :

- **Ajout** : `+ Cel` insère après le courant et **sélectionne le nouveau**
  (`cur = frameCount()-1`, déjà le cas) + auto-scroll dessus.
- **Duplication** : `⧉` duplique le courant, sélectionne la copie.
- **Suppression** : `🗑`/texte `Suppr.` avec **garde-fou** :
  - si `count == 1` : bouton désactivé (jamais 0 cels — `coerceAtLeast(1)`
    existant) ;
  - sinon confirmation **1-tap annulable** : le bouton devient
    `Confirmer ?` 3 s (pas de dialogue modal qui casse le flow tactile),
    puis `deleteFrame(victim)`, `cur = max(0, cur-1)`, `refresh()`.
  - statut éphémère existant réutilisé (« cel supprimé », mêmes strings).
- Tous les boutons : `height ≥ 48dp`, labels via `R.string` (§6).

Hors portée : reorder/drag cel, renommage cels, dossier de cels,
exposition/rythme par cel (hold 2s/3s), copier-coller inter-docs —
renvoyés §8.

## 5. Transport — lecture + fps (timer app, layout compact)

- **Barre transport unique** (une seule `Row`, remplace les 3 rangées
  actuelles boutons/fps/onion) :
  ```
  [⏮][▶/⏸][⏭]  fps − 12 +  │  Onion (toggle)  │  + ⧉ 🗑
  ```
  (glyphes : texte maison `R.string`, pas d'icônes copiées ; `⏮/⏭` =
  cel précédent/suivant = `setFrame((cur±1+count)%count)`).
- **Lecture** : `Handler` existant inchangé (step à `1000/fps`, clamp
  1..60). Ajouts UI seulement : `⏮/⏭` step manuel (pause d'abord si
  `playing`), compteur `i/N` + `fps` affichés pendant play, pause auto
  au repli du tiroir (le `DisposableEffect(playing…)` existant est déjà
  coupé à la disparition — à **vérifier**, pas à réécrire).
- **fps** : stepper `− / +` (1..60, défaut 12) **+** slider existant
  conservé en second rideau (section repliable « Réglages » : fps slider,
  onion force, onion comptes — pour tenir dans 175dp). Le stepper couvre
  le tactile rapide, le slider la précision.
- **Hauteur budget** (tient dans 175dp) :
  piste ~120dp + transport 48dp = 168dp ≤ 175dp ✓ ;
  section « Réglages » (fps slider + onion comptes + force) = contenu
  **scrollable interne** du tiroir ouvert (le `CollapsibleDrawer` accepte
  déjà un `content()` arbitraire ; scroll vertical interne si dépassement,
  jamais d'extension du max).

## 6. Strings FR/EN proposées + téléphone

Convention Slice 0/1 : clés snake_case, accents FR en `values-fr` only.

| Clé | EN (`values`) | FR (`values-fr`) | Usage |
|---|---|---|---|
| `cel_add` | Add cel | Ajouter cel | §4 (remplace `frame_add` ou alias) |
| `cel_duplicate` | Duplicate | Dupliquer | §4 |
| `cel_delete` | Delete | Supprimer | §4 |
| `cel_delete_confirm` | Tap again to confirm | Toucher pour confirmer | §4 garde-fou |
| `cel_count` | %1$d/%2$d | %1$d/%2$d | §2 pastille i/N |
| `transport_prev` | Previous cel | Cel précédent | §5 ⏮ |
| `transport_play` | Play | Lire | (existant `timeline_play` conservé si présent) |
| `transport_pause` | Pause | Pause | (existant `timeline_pause` conservé) |
| `transport_next` | Next cel | Cel suivant | §5 ⏭ |
| `fps_step_down` | Slower | Moins vite | §5 stepper − |
| `fps_step_up` | Faster | Plus vite | §5 stepper + |
| `onion_master` | Onion skin | Pelure d'oignon | §3 toggle maître |
| `onion_before` | Before | Avant | §3 pastille + stepper |
| `onion_after` | After | Après | §3 pastille + stepper |
| `onion_count_before` | %1$d before | %1$d avant | §3 compte |
| `onion_count_after` | %1$d after | %1$d après | §3 compte |
| `timeline_settings` | Timeline settings | Réglages chrono | §5 second rideau |

Clés existantes conservées : `timeline`, `timeline_fps`,
`timeline_play/pause`, `frame_add/duplicate/delete`, `onion`,
`onion_prev/next`, `onion_strength`, `audio_*`, `export_webm`,
`ai_upscale`.

**Fallback téléphone 1080×2400 (portrait)** — tout replié par défaut
(Slice 0 §3, inchangé) :

- Tiroir fermé par défaut ; ouvert = **piste seule + transport** (la
  section Réglages naît repliée) ; vignettes 128×96 conservées (scroll
  horizontal, 2–3 visibles — scrub d'autant plus justifié qu'on ne voit
  pas tout).
- Transport : `⏮ ▶ ⏭ + ⧉` sur une ligne, `🗑`/fps-stepper en ligne 2 si
  place, sinon dans Réglages ; cibles ≥ 48dp, texte ≥ 11sp (règle Slice 0).
- Aucun overlay : tiroir replié = 0px au-dessus du canvas (règle n°5).

## 7. Tests instrumentés à ajouter (gate local `emutest.sh`)

Fichier existant étendu : `EngineConformanceTest.kt` (émulateur KVM
local, jamais GitHub CI — règle AGENTS.md). Pattern établi (surface via
`findEngineSurface`, `MotionEvent`/`dispatchTouchEvent` ou Espresso sur
la strip Compose, `pullFrameFull` + scan pixels).

1. `scrubChangesCurrentFrame` — 3 cels (add ×2, encre distincte sur cel
   1 : stroke pattern `canvasSurfaceHasVisibleSizeAndTouchDrawsStroke`),
   drag horizontal sur la piste (DOWN piste + MOVE ≥ 64dp + UP) ;
   assert : `cur` a changé (`frameCount` ≥ 3 ET cel affiché ≠ départ,
   vérifié par pixels : le composite après scrub == composite du cel
   cible lu avant via `setFrame` direct) ET aucun cel créé/supprimé
   (`frameCount` constant).
2. `onionTintTouchesNoPixels` — 2 cels encrés différemment, ON onion
   (toggle maître + before=1/after=1 + force 50 %) ;
   assert : `pullFrameFull` **identique** avant/après (0 px de différence
   hors fantôme — tolérance : comparer le composite `setFrame(cur)` avec
   onion ON puis OFF, byte-identique) ET bordures teintées présentes
   dans la hiérarchie Compose (assert UI : nodes avec content-description
   `onion_before/after`, pas de scan pixels).
3. `onionCountStepperBounds` — steppers before/after : 0..4 clampés
   (tenter + au-delà de 4, − sous 0) ; assert : état `remember` clampé ET
   `setOnion` appelé avec `(before>0, after>0)` (mapping §3, pas de crash
   moteur aux bornes).
4. `celDeleteGuardSingleFrame` — 1 cel : bouton delete désactivé ;
   2 cels : 1er tap arme `cel_delete_confirm`, 2e tap < 3 s supprime
   (`frameCount` −1, `cur` replié) ; timeout 3 s sans 2e tap = désarmé
   (pas de suppression) ; après suppression du dernier-1 : retour à 1 cel,
   delete re-désactivé.
5. `fpsClampAndPlaySteps` — fps stepper aux bornes (1..60, tenter 0/61) ;
   play 2 s à 4 fps sur 3 cels ; assert : `cur` a avancé (≥ 4 steps
   modulo count, `playTick`-like observable) ET pause auto au repli du
   tiroir (toggle drawer → `playing == false` au reopen).
6. `drawerKeepsMaxHeightPhoneFolded` (smoke 1080×2400) — tiroir replié par
   défaut, ouvert = hauteur ≤ 175dp, piste scrollable + transport ≥ 48dp
   par bouton ; repli = 1 tap, canvas pleine largeur.

Exigences communes : chaque test reset (onion OFF, fps 12, `cur=0`,
tiroir replié) pour ne pas polluer le suivant ; seuils drag (16dp arm,
64dp seek) en constantes nommées testées aux bornes ; debounce play
vérifié (double play rapide = 1 seul timer).

## 8. Portée Slice 4 (ce que cette spec autorise à coder ensuite)

1. Piste §2 : vignettes 128×96 + pastille i/N + auto-scroll + scrub
   tactile (preview candidat + `setFrame` au relâcher).
2. Onion §3 : toggle maître + pastilles teintées + steppers 0..4 +
   mapping `(before>0, after>0)` vers `setOnion` existant + légende.
3. Cels §4 : boutons 48dp + garde-fou suppression (confirm 3 s, jamais
   0 cels).
4. Transport §5 : barre unique ⏮/▶/⏭ + stepper fps + section Réglages
   repliable (slider fps + onion comptes + force), pause auto au repli
   **vérifiée**.
5. Strings §6 (EN+FR), téléphone replié par défaut.
6. Les 6 tests §7 verts en `emutest.sh` local (tablette 1920×1200 ;
   smoke 1080×2400 : au moins le n°6 + n°1 verts).

Hors portée (futur, JNI si besoin documenté dans `ARCHITECTURE.md`
d'abord) : reorder cels par drag, renommage cels, hold/exposition par
cel, `nSetOnionCount` (vrai nb d'images moteur), `nSetFps`, copier-coller
inter-docs, courbe fps (ease), audio scrub-sync (M7b : audio reste
présentation), miniature navigation (backlog n°9).

## 9. Risques

- **Budget 175dp** : 120 (piste) + 48 (transport) = 168dp — reste 7dp
  de marge ; la moindre rangée ajoutée casse le max. Atténuation :
  section Réglages en scroll interne, jamais d'extension ; si les tests
  montrent un overflow sur @240, réduire vignettes à 112×84 avant de
  toucher au max (le max est contrat Slice 0).
- **Scrub vs tap** : le seuil 16dp doit séparer nettement tap (sélection)
  et drag (seek) — tactile réel bruité (cf. leçon gestes Slice 1 §10).
  Si faux seeks : monter à 24dp (seuil gestes R6) ou exiger MOVE dominant
  horizontal ×2 vs vertical.
- **Perf play + auto-scroll** : `scrollToItem` à chaque step + `refresh()`
  (re-pull N thumbs `nFrameCompositeInto`) à 12+ fps sur swiftshader —
  risque > 16 ms. Atténuation : pendant play, ne re-pull que le thumb
  du `cur` (cache existant `thumbs[i]`, déjà en place) ; full `refresh()`
  seulement au pause/scrub-relâcher/cel-op.
- **`setOnion` tout-ou-rien** : la spec vend un « nb d'images » qui n'est
  (côté moteur) qu'un booléen — risque de sur-promesse UX. Atténuation :
  §3 impose le mapping honnête + légende « affichage » ; le vrai compte
  moteur est explicitement hors portée (§8).
- **i18n** : `Pelure d'oignon`, `Toucher pour confirmer` longs en FR —
  `maxLines=1` + ellipsization sur transport et légende (leçon Slice 0 §7).
