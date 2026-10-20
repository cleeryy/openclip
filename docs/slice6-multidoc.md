# Slice 6 — Multi-docs à onglets + save 1-tap + workspaces (SPEC, sans code)

> SPEC UNIQUEMENT — aucune modification app/engine. Vocabulaire maison,
> aucun asset ni capture d'outil tiers. Clean-room : jamais de strings,
> icônes, noms de palettes ni layouts repris de Clip Studio Paint / Celsys
> (cf. `docs/csp-ux-research.md` § clean-room et §R7). JNI gelé
> (`ARCHITECTURE.md`) : tout ce qui suit est **app-side uniquement**
> (`MainActivity.kt`, `DocRegistry.kt`, `EngineSurface.kt` existant inchangé
> côté symboles, `MenuBar.kt`, `ToolPalette.kt`, nouveaux `DocTabs.kt`,
> `NewCanvasDialog.kt`, `CommandBar.kt`, `WorkspaceStore.kt`, ressources
> `R.string`).
> Conventions : FR/EN via `R.string` uniquement, pas de littéraux FR
> en dur (leçon M5 : apostrophes FR cassent le build).

État vérifié (point de départ) : Slices 0-5 committées (shell, gestes,
couleur, calques, timeline, feeling pinceau). Référence feeling :
`docs/csp-ux-research.md` §R7 (title bar nom/résolution/zoom, command bar
raccourcis perso, save 1-tap, workspaces nommés JSON maison jamais `.spws`,
dialogue nouveau illustration taille/résolution/papier) + §R1 (chrome
repliable, tout panneau naît repliable).
Actuel vérifié dans le code :

- `DocRegistry.kt` = `Map docId → DocEntry(name, dirty)`, 1 seul actif,
  `dispose()` explicite — **prêt pour N**, mais `MainActivity` n'ouvre
  qu'un seul `"doc-1"` et `open()` sur un 2e id ne fait que l'enregistrer
  (la surface continue d'afficher l'onglet actif).
- `EngineSurface.kt` = crée **1 handle + 1 thread** (`nInit`/`nDispose`,
  aucun état global shim) ; taille moteur figée en constantes
  `engineWidth = 2048`, `engineHeight = 1536` (le `nInit(w, h, dpi)`
  accepte pourtant w/h — la spec autorise de les paramétrer, JNI
  inchangé). Undo M7 = stacks moteur **par frame, par handle** : changer
  de doc = changer de handle, donc l'undo survit au switch **si et
  seulement si le handle inactif n'est pas `dispose()`**.
- `MainActivity.kt` = nom doc + dirty flag + label dims dans le **footer**
  (`"${w}×${h}"` + `doc.name + " •"`), SAF import/export
  (`OpenDocument` / `CreateDocument` + `saveTo`/`loadFrom` via file
  cache), `MenuBar` (Fichier / Édition / Calque / Sélection / Filtre /
  Affichage / Fenêtre / Aide), statut éphémère vert.
- RAM : chaque doc = `W×H×4B` par calque × frames (+ undo M7 : tuiles
  touchées par pas). Déjà lourd à 2048×1536 (12,6 Mo/couche pleine) —
  **plafond + garde obligatoires** (§5). Règle : seul l'onglet actif
  composite (`needsComposite` + `running=true`) ; les inactifs ont leur
  thread en pause (`running=false`, pas de blit) **mais gardent leur
  handle** (undo préservé) ; `dispose()` seulement à la fermeture
  d'onglet.

Constat : le shell sait gérer **un** document et **un** layout. La Slice 6
le rend **multi-documents** (onglets, 1-tap save, nouveau canvas) et
**multi-postures** (command bar épinglable, workspaces nommés) — sans
toucher au moteur.

## 1. Principe — N surfaces, 1 seule visible, 0 JNI

```
docs      = N DocEntry (DocRegistry, 1 actif) ↔ N EngineSurface (1 handle + 1 thread chacun)
visible   = 1 seule surface attachée (les autres GONE, thread en pause, handle vivant)
switch    = pause ancien (running=false) + resume nouveau (running=true) + needsComposite
close     = dispose() handle + thread, jamais de handle orphelin
persisté  = dirty flags, workspaces JSON, command bar config — jamais de pixels, jamais de .spws
```

- **JNI gelé** : seuls `nInit(w,h,dpi)`, `nDispose`, `nSetBrush`,
  `nStroke*`, `nCompositeInto`, `nFrameCompositeInto`,
  `nSaveDocument`/`nLoadDocument`, `nUndo`/`nRedo`, `pullFrameFull`
  existants. Aucun symbole nouveau. `EngineSurface` gagne un
  constructeur paramétré `(w, h)` (app-side, mêmes appels JNI) + des
  méthodes `pause()/resume()` qui pilotent `running`/`needsComposite`
  sans toucher au handle.
- **Un seul `pushBrush()`** (règle Slices 3/5) : le pinceau reste
  **global** (pas par doc — décision §8) ; au switch, le `BrushPrefs`
  hoisté repousse le JSON courant vers la surface nouvellement active
  (`surface.setBrush(prefs.currentJson())`, une fois, pas par frame).
- **Overlays composite-only** (règle Slice 5) : onglets, command bar,
  dialogue nouveau canvas, pastilles workspaces vivent en Compose
  **au-dessus** de la surface — jamais dans les pixels moteur.
  `pullFrameFull` avant/après switch = byte-identique par doc (garantie
  testable §7.1).
- **Clean-room workspaces** : format JSON maison (`workspace-v1`,
  §6), jamais de `.spws` lu/écrit, jamais de nom/layout repris de
  l'outil tiers. Le mot « workspace » seul est générique et autorisé ;
  aucun libellé tiers n'est copié.

## 2. Onglets multi-docs — N handles, 1 visible

- **UI** : nouveau `DocTabs.kt` — rangée d'onglets **sous la toolbar**,
  au-dessus du canvas (pas dans le footer : le footer reste statut).
  Chaque onglet = nom doc (tronqué 16 car. + ellipsis) + **pastille
  dirty `•`** + croix `×` (48dp min, pattern Slice 2 §5) + bouton `+`
  final (ouvre le dialogue §4). Scroll horizontal si > 3 onglets
  (téléphone). `testTag("doc-tab-$docId")`, `testTag("doc-tab-add")`,
  `testTag("doc-tab-close-$docId")`.
- **Cycle de vie par doc** (app-side, dans `MainActivity`) :
  - `openDocTab(docId, name, w, h)` : crée `EngineSurface(ctx, w, h)`
    + `start(dpi)` + câblage callbacks identique à l'actuel
    (undo/redo/pipette/stroke/view — factoriser le bloc `also { cv → }`
    actuel en `wireSurface(cv)`), `DocRegistry.open(docId, name)`,
    pousse le brush courant, `needsComposite=true`.
  - `activateDocTab(docId)` : ancien actif → `pause()` (thread en
    pause, **handle vivant**, `hoverActive=false`, file stroke
    **vide et fermée** — voir garde stroke ci-dessous) ; nouveau →
    `resume()` + `refreshKey++` + statut `nom + résolution + zoom`
    (rapproche title bar §R7, §3).
  - `closeDocTab(docId)` : si dirty → **dialogue confirmer**
    (Enregistrer / Fermer sans enregistrer / Annuler, §6 strings) ;
    sinon `surface.dispose()` (thread + `nDispose`) + `registry.close()`
    + active = dernier onglet (`lastOrNull`, comportement existant
    conservé). Dernier onglet fermé → **canvas vide honnête** (fond
    + CTA « Nouveau canvas », pas de surface fantôme ; `canvas.value =
    null`, tous les panneaux restent utilisables sauf dessin).
  - **Garde stroke pendant switch** : si un stroke est ouvert
    (`isDrawingNow()`) au moment du tap onglet, le switch est
    **différé** : l'onglet cible est pré-sélectionné visuellement mais
    l'échange n'a lieu qu'au `ACTION_UP` (ou `CANCEL`) — jamais de
    points d'un doc dans l'autre. Si le doigt ne se lève pas sous
    5 s, statut « finissez le trait pour changer de doc » + retour à
    l'onglet source. Test §7.2.
- **État par doc** (tout ce qui est moteur vit dans le handle, donc
  suit naturellement) : calques, frames, undo/redo M7, onion, teintes,
  réf glTF overlay, `currentFrameIdx`. **État vue par doc** : chaque
  surface a déjà sa matrice (`viewScaleMul/angle/off`) — le zoom/pan
  d'un doc ne fuit pas dans l'autre (non-régression à tester §7.1).
  **État app global** (pas par doc, décision §8) : pinceau, couleur,
  dock/timeline/command bar, workspaces.
- **Dirty** : `markActiveDirty(true)` existe (stroke/undo/filtre) —
  étendre aux mêmes déclencheurs sur **chaque** surface (le callback
  `onStrokeChanged` est câblé par surface, donc `markDirty(docId)`
  avec le bon id, pas toujours l'actif). Save 1-tap (§3) et export
  SAF existant effacent le dirty **du seul doc sauvegardé**.

## 3. Titre doc + save 1-tap dans la toolbar

Le footer actuel montre déjà `dims + nom + •`. La Slice 6 le **remonte**
au niveau title bar §R7 sans le dupliquer :

- **Toolbar** (dans `ToolPalette` ou rangée dédiée `DocTitleBar.kt`,
  décision implémentation) : `« nom — W×H @z% »` (`z` = `viewZoomPercent()`,
  réarmé par `onViewChanged` existant) + bouton **save 1-tap**
  (`testTag("doc-save")`, icône maison disquette-générique, 48dp).
  Comportement save : si le doc a déjà un chemin SAF persistant
  (export/save précédent via `CreateDocument` → `takePersistableUriPermission`),
  **écrase sans dialogue** (le vrai 1-tap §R7) ; sinon ouvre le
  `CreateDocument` existant (format ORA par défaut, choix ORA/PSD/PNG
  conservé). Succès → `markDirty(false)` + statut `R.string.save_ok` ;
  échec → `R.string.save_failed` (pas de toast silencieux).
- **Footer conservé** (dims + nom + statut) : pas de duplication
  visuelle — le footer garde dims+statut, la toolbar porte
  nom+zoom+save. Sur téléphone (< 600dp) : titre tronqué 12 car.,
  zoom masqué (dims seules, leçon Slice 0 §7).
- **Dirty omniprésent** : `•` dans l'onglet (§2) **et** après le nom
  toolbar. Jamais de save implicite qui efface sans retour : chaque
  save poste le statut.

## 4. Dialogue nouveau canvas — px/dpi/fond, suggestion ≥ 2000px

`NewCanvasDialog.kt` (remplace l'ouverture aveugle `doc-1`) :

- **Champs** : largeur px (défaut **2048**), hauteur px (défaut
  **1536**), résolution dpi (défaut **300**, choix 72/150/300/600 —
  transmise à `nInit(w,h,dpi)`, aucun autre effet moteur),
  **fond** (3 chips : Blanc / Transparent / Teinte Ink — la teinte
  reprend `inkHex` courant ; fond appliqué comme **remplissage du
  calque 1** après `nInit`, via le chemin stroke/fill existant ou
  `nLoadDocument` d'un ORA minimal généré app-side — décision
  implémentation, aucun JNI), nom (défaut `Sans titre N`, incrément
  local, jamais de collision avec les onglets ouverts).
- **Suggestion ≥ 2000px** (§R7, pratique artiste) : si
  `max(w,h) < 2000`, hint non-bloquant
  (`R.string.newcanvas_small_hint`, « petit canvas : l'export
  manquera de détail ») + preset 1-tap `2048×1536` et `3000×2000`.
  Pas de validation bloquante (workflow tactile).
- **Gardes** : w/h clampés `[256, 4096]` (au-delà = refus poli +
  statut, protège la RAM §5) ; w/h non-numériques = boutons `+`/`-`
  steppers 128px + champ numérique (clavier tablette edge §R6) ;
  **annuler ne crée rien** (pas de handle orphelin).
- **Presets orientation** : Paysage / Portrait (swap w/h 1-tap) —
  tablette 1920×1200 par défaut paysage, téléphone portrait.
- Au `+` des onglets **et** au premier lancement (plus de `doc-1`
  auto) : le dialogue s'ouvre ; l'onglet n'est créé qu'à la
  confirmation (pas de surface avant validation).

## 5. Plafond mémoire + garde (obligatoire, pas optionnelle)

Chaque doc ≈ `W×H×4B` par calque plein × frames × (undo M7 : tuiles
modifiées par pas — borné mais non nul). 2048×1536 = 12,6 Mo/couche
pleine ; 4 calques × 8 frames ≈ 400 Mo + undo → **l'onglet 5 peut
tuer l'app** sur tablette 3 Go.

- **Plafond** : `MAX_OPEN_DOCS = 4` (constante `DocRegistry`,
  testable). Au 5e `+` : dialogue **plafond**
  (`R.string.docs_full_hint`) proposant fermer/enregistrer un doc —
  jamais de 5e handle silencieux. Le test §7.4 assert le refus.
- **Garde par doc** : au dialogue §4, estimation
  `W×H×4×(calques=1)×1,5 (marge undo)` affichée en Mo sous les
  champs (`« ~19 Mo »` pour 2048×1536) ; si > 256 Mo/doc : hint
  ambre non-bloquant. Pas de calcul savant — une multiplication
  suffit, c'est une garde pas un profiler.
- **Inactifs sobres** : `pause()` = `running=false` (thread rendue,
  pas de `nCompositeInto`, pas de blit) + `hoverActive=false` +
  files vidées ; **handle conservé** (undo M7 par frame intact —
  c'est le point central de la spec, §7.1 le prouve). `dispose()`
  **uniquement** à `closeDocTab`. Fuite interdite : chaque `start()`
  a son `dispose()` sur le même objet (assert test-only
  `openHandles == surfaces vivantes`, §7.4).
- **Jamais de builds lourds en parallèle de l'émulateur** (règle
  AGENTS.md, 8 Go RAM) — rappel opérationnel, pas de code.

## 6. Command bar épinglable + workspaces nommés JSON maison

### 6a. Command bar (R7 : raccourcis perso, Simple Mode save 1-tap)

- `CommandBar.kt` : rangée **épinglable** sous les onglets (au-dessus
  du canvas), 48dp par action, masquable d'un tap (pastille `⌄`,
  règle §R1 : tout panneau naît repliable). Actions épinglables :
  **save** (§3), **undo**, **redo**, **export** (ouvre `ExportDialog`
  existant), **plein écran** (masque dock+timeline+command bar elle-même,
  2e tap ou bouton flottant `✕` pour sortir — statut annonce l'état).
- **Config persistée** (`commandbar.json`, `version:1`, clés
  optionnelles, fallback = les 5 dans l'ordre ci-dessus) : ordre +
  visibilité par action. Édition : appui long sur la bar → mode
  édition (chips à bascule, drag simple = monter/descendre via
  flèches `↑↓`, pas de drag tactile — accessibilité + tests).
  `pushBrush`-style : une seule source (`CommandBarStore`), un seul
  writer.
- Undo/redo de la bar appellent les mêmes `surface.undo()/redo()` +
  `markDirty` + `refreshKey` que le menu Édition (pas de 2e chemin).

### 6b. Workspaces nommés (R7 : équivalent fonctionnel `.spws`, format maison)

- `WorkspaceStore.kt` : `workspaces.json` (`version:1`) =
  `{ nom: { dockOpen, dockTab, timelineOpen, navMode, commandBarVisible,
  commandBarOrder[] } }`. **Jamais de `.spws`** lu/écrit/cité dans le
  code (clean-room §R7). Noms libres FR/EN (ressources pour les
  2 presets : `Dessin`/`Drawing`, `Animation`/`Animation`).
- **UI** : sélecteur dans le menu Fenêtre (`StudioMenu` existant) +
  dialogue maison (nommer / écraser / supprimer / renommer). Appliquer
  = pose les 6 champs d'un coup + `needsComposite` + statut
  `workspace_applied`. Presets usine : `Dessin` (dock ouvert onglet 0,
  timeline fermée, command bar visible) / `Animation` (timeline ouverte,
  dock onglet 0, command bar visible). Dernier workspace appliqué
  persisté (`lastWorkspace`) et restauré au lancement.
- **Gardes** : nom vide/doublon = refus poli (statut, pas de crash) ;
  JSON corrompu = presets usine (même pattern que `BrushPrefs`,
  Slice 3) ; suppression du workspace courant = retombe sur `Dessin`
  + statut. **Les workspaces ne stockent jamais** : docs ouverts,
  pixels, pinceau, couleur (posture, pas contenu — documenté dans
  l'aide).

## 7. Tests instrumentés à ajouter (gate local `emutest.sh`)

Fichier existant étendu : `EngineConformanceTest.kt` (émulateur KVM
local, jamais GitHub CI — règle AGENTS.md). Pattern établi
(`AndroidView` + `dispatchTouchEvent`, `pullFrameFull` + scan pixels,
`Thread.sleep`). Nouveaux tests **Slice 6** (tablette 1920×1200 ;
smoke téléphone : 1 + 7) :

1. `twoDocsSwitchKeepsPixelsAndUndo` — ouvre doc A (défaut), stroke
   témoin (encre > 0 px via `pullFrameFull(0)`) ; ouvre doc B (`+` +
   dialogue défaut) ; stroke différent sur B ; **retour A** :
   assert composite A **byte-identique** à avant-switch (pas de fuite
   inter-docs) ET `undo()` sur A **vrai** (composite change, encre
   diminue — prouve les stacks M7 par handle intactes après pause/
   resume) ; redo restaure. Vue : `zoomAt` sur A avant switch, assert
   `viewScale()` inchangé au retour ET B à 100 % (matrices par
   surface, pas de fuite).
2. `switchDuringStrokeDefers` — `ACTION_DOWN + MOVE×2` sur A **sans
   UP**, tap onglet B (via `performClick` sur `doc-tab-B`) ; assert :
   B **pas encore actif** (`activeDocId == A`) ; `ACTION_UP` → switch
   effectif (`activeDocId == B`) ; composite B **vierge** (0 px d'encre
   du stroke A — pas de point égaré). Timeout 5 s : passé ce délai
   sans UP, statut `finish_stroke_hint` + retour visuel onglet A.
3. `saveOneTapClearsDirtyOnlyThere` — stroke sur A (dirty `•`
   visible, `testTag` pastille) ; tap `doc-save` (chemin SAF mocké —
   si `CreateDocument` non simulable, injecter un path cache
   test-only équivalent au flux `saveTo` existant) ; assert :
   `dirty(A) == false`, `dirty(B untouched) == false`, statut
   `save_ok` ; stroke sur B → `dirty(B) == true`, `dirty(A)` reste
   false (dirty par doc, pas global).
4. `fourDocsCeilingAndCleanDispose` — ouvre B, C, D (4 onglets) ;
   5e `+` → assert dialogue `docs_full_hint` **et aucun 5e handle**
   (`isReady()` faux pour le 5e, `registry.size == 4`) ; ferme B
   (non-dirty → pas de dialogue) ; assert `registry.size == 3`,
   actif = dernier (`lastOrNull`), **handle B `dispose()`**
   (test-only : compteur handles vivantes `== 3`, pas d'orphelin) ;
   rouvre E → OK (le plafond s'est libéré). Ferme doc dirty →
   dialogue confirmer (3 boutons, annuler = onglet intact).
5. `newCanvasDialogDimsAndGuard` — ouvre dialogue, pose `3000×2000`
   fond transparent ; confirme ; assert `canvasWidth/Height ==
   3000/2000` (surface paramétrée, JNI inchangé) + calque 1
   transparent (scan : pixel central alpha 0) ; dialogue `100×100` →
   hint `newcanvas_small_hint` visible, confirmation quand même
   possible ; `5000×5000` → refus poli (pas d'onglet créé,
   `size` inchangé) ; estimation Mo affichée (`~… Mo`, nœud
   `testTag("newcanvas-estimate")`).
6. `workspaceRoundTrip` — applique preset `Animation` (timeline
   ouverte assert `timeline-drawer` affiché) ; modifie (dock fermé,
   navMode on) ; sauve sous `« Test »` ; applique `Dessin` ;
   ré-applique `« Test »` ; assert : dock fermé + navMode on +
   timeline ouverte (les 6 champs, pas seulement 1) ;
   `recreate()` + sleep 700 ms → dernier workspace restauré ;
   JSON corrompu injecté (fichier écrasé test-only) → presets usine,
   pas de crash. Suppression `« Test »` → retombe `Dessin`.
7. `commandBarPinAndFullscreen` — masque `export` (mode édition appui
   long, bascule) ; `recreate()` → export toujours masqué, ordre
   conservé ; tap `plein écran` → dock + timeline + bar masqués
   (assert `dock-toggle` absent/gone, canvas seul) ; 2e tap (bouton
   flottant) → retour exact (dock/tab/timeline restaurés — le plein
   écran **ne touche pas** au workspace courant, juste un masquage
   overlay). Touch targets : onglets + croix + save + actions bar ≥
   48dp (tolérance 0,5dp) en 1920×1200 **et** 1080×2400.
8. `closeLastDocShowsEmptyCanvas` — ferme tous les onglets (confirme
   les dirty) ; assert : CTA `newcanvas_empty_cta` visible,
   `canvas.value == null`, menus Fenêtre/Aide toujours ouvrables
   (pas d'écran mort) ; `+` recrée un doc dessinable (stroke →
   encre).

Exigences communes : reset (`1 doc 2048×1536`, pinceau 10px/100 %,
onion OFF, workspaces presets, command bar défaut) en teardown ;
debounce persistance (`sleep` ≥ 700 ms) avant asserts fichier ;
seuils couleur §7 Slice 2 (±1 LSB, dominante +40) ; jamais de builds
lourds en parallèle de l'émulateur (8 Go RAM).

## 8. Portée Slice 6 (ce que cette spec autorise à coder ensuite)

1. `DocTabs.kt` + cycle `open/activate/close` (§2) + garde
   stroke-en-cours différé + dialogue confirmer-dirty + canvas vide
   + CTA.
2. `DocTitleBar` (nom + `W×H @z%` + save 1-tap `doc-save`, §3) avec
   `takePersistableUriPermission` + dirty par doc (étendre
   `markDirty(docId)` aux callbacks de chaque surface).
3. `NewCanvasDialog.kt` (§4 : px/dpi/fond/nom, presets, hint
   ≥2000px, clamp 256..4096, estimation Mo) + `EngineSurface(ctx,w,h)`
   paramétré (mêmes symboles JNI) + fond initial (remplissage calque
   1, aucun JNI).
4. `pause()/resume()` sur `EngineSurface` + `wireSurface()` factorisé
   + repush brush au switch (§1-§2) + `MAX_OPEN_DOCS = 4` + dialogue
   plafond (§5).
5. `CommandBar.kt` + `CommandBarStore` (`commandbar.json`, ordre +
   visibilité, mode édition appui long, plein écran overlay, §6a).
6. `WorkspaceStore.kt` (`workspaces.json` maison, presets
   Dessin/Animation, sélecteur menu Fenêtre + dialogue, §6b).
7. Strings §9 (EN + FR), fiche Aide « multi-docs + workspaces »,
   téléphone replié (titre 12 car., zoom masqué, onglets scroll).
8. Les 8 tests §7 verts en `emutest.sh` local (tablette ; smoke
   téléphone 1+7).

**Décisions actées** (ne pas rouvrir sans nouvelle spec) : pinceau et
couleur **globaux** (pas par doc — un illustrateur garde sa main
d'un canvas à l'autre ; le per-doc multiplierait les `setBrush`
concurrents, leçon Slice 3 §5) ; workspaces = **posture** (jamais de
docs/pixels/pinceau dedans) ; inactifs = **thread en pause, handle
vivant** (undo M7 intact), `dispose()` seulement à la fermeture ;
plafond **4 docs** (RAM tablette 3 Go, §5).

Hors portée (futur, spec dédiée) : onglets détachables / split-screen
2 docs visibles (1 seul composite aujourd'hui — le double blit
doublerait la RAM active) ; drag-and-drop calque inter-docs (copie
par `pullFrameFull` + collage, pas de JNI) ; autosave périodique
(save 1-tap explicite seul, pas de surprise tactile) ; sync
workspaces via WebDAV (local seul) ; miniature d'onglet (attend
`nLayerCompositeInto`, Slice 3 §1 — texte seul en attendant).

## 9. Strings FR/EN proposées + téléphone

Convention Slices 0-5 : clés snake_case, accents FR en `values-fr`
only, jamais de littéraux (leçon M5).

| Clé | EN (`values`) | FR (`values-fr`) | Usage |
|---|---|---|---|
| `doc_untitled` | Untitled %1$d | Sans titre %1$d | §4 nom défaut (existant `doc_untitled` à étendre) |
| `doc_save` | Save | Enregistrer | §3 bouton 1-tap |
| `save_ok` | Saved | Enregistré | §3 statut succès |
| `save_failed` | Save failed | Échec enregistrement | §3 statut échec |
| `doc_close_save` | Save | Enregistrer | §2 dialogue dirty (bouton) |
| `doc_close_discard` | Close without saving | Fermer sans enregistrer | §2 dialogue dirty (bouton) |
| `doc_close_cancel` | Cancel | Annuler | §2 dialogue dirty (bouton) |
| `doc_close_dirty` | Save changes to %1$s? | Enregistrer %1$s ? | §2 dialogue dirty (titre) |
| `docs_full_hint` | 4 docs max — close one first | 4 docs max — fermez-en un | §5 plafond |
| `finish_stroke_hint` | Lift the pen to switch docs | Levez le stylet pour changer | §2 garde stroke |
| `newcanvas_title` | New canvas | Nouveau canvas | §4 titre dialogue |
| `newcanvas_small_hint` | Small canvas: soft exports | Petit canvas : exports doux | §4 hint <2000px |
| `newcanvas_too_big` | Too big (max 4096) | Trop grand (max 4096) | §4 clamp |
| `newcanvas_bg_white` | White | Blanc | §4 fond |
| `newcanvas_bg_transparent` | Transparent | Transparent | §4 fond |
| `newcanvas_bg_ink` | Ink tint | Teinte encre | §4 fond |
| `newcanvas_empty_cta` | New canvas… | Nouveau canvas… | §2 canvas vide |
| `cmdbar_edit` | Arrange actions… | Ranger les actions… | §6a mode édition |
| `cmdbar_fullscreen` | Full screen | Plein écran | §6a action |
| `cmdbar_exit_fullscreen` | Exit | Quitter | §6a sortie |
| `workspace_draw` | Drawing | Dessin | §6b preset |
| `workspace_anim` | Animation | Animation | §6b preset |
| `workspace_applied` | Workspace %1$s | Espace %1$s | §6b statut |
| `workspace_save_title` | Save workspace as… | Nommer l'espace… | §6b dialogue |
| `workspace_deleted` | Workspace deleted | Espace supprimé | §6b statut |
| `multidoc_help` | Tabs & spaces… | Onglets et espaces… | Aide, fiche §2-§6 |

Clés existantes conservées : `doc_untitled`, `import_doc`,
`export_title`, `undo`, `redo`, `timeline`, `window_dock`,
`window_studio`, `window_tones`, `nav_mode_*`.

**Fallback téléphone 1080×2400 (portrait)** : onglets scroll
horizontal, titre 12 car. + ellipsis, zoom `%` masqué (dims seules),
dialogue nouveau canvas pleine feuille scrollable, command bar scroll
horizontal (pas de wrap), workspaces via menu Fenêtre seul (pas de
sélecteur persistant en toolbar). Touch targets §7.7 couverts.

## 10. Risques

- **Handle orphelin = fuite native** : chaque `openDocTab` sans
  `closeDocTab` symétrique laisse un moteur + thread vivants (RAM
  native invisible au GC). Atténuation : `DisposableEffect` par
  surface (pas un seul global comme aujourd'hui) + `onDestroy`
  dispose-tout + test compteur handles §7.4 ; si la revue trouve un
  chemin sans dispose (rotation, `recreate()`), repli = un seul doc
  (désactiver le `+` derrière un flag, la spec reste valide).
- **Pause ≠ libère la RAM** : `running=false` stoppe le CPU/blit
  mais les pixels + undo restent (c'est voulu pour l'undo M7).
  4 docs lourds peuvent quand même dépasser la RAM — d'où le
  plafond 4 + garde 256 Mo/doc (§5). Si l'OOM survit : repli
  documenté = plafond 2 (constante seule, pas de redesign).
- **SAF 1-tap fragile** : `takePersistableUriPermission` refusé par
  certains providers → le 1-tap retombe sur `CreateDocument` à
  chaque fois (pas d'erreur, juste un dialogue — le test §7.3
  injecte le path cache pour ne pas dépendre du provider).
- **Switch différé frustrant** : l'utilisateur tape B en plein
  trait et rien ne se passe 5 s. Atténuation : pré-sélection
  visuelle immédiate + statut `finish_stroke_hint` (§2) ; jamais de
  perte de trait (le stroke finit sur A, c'est garanti §7.2).
- **Workspaces vs plein écran** : deux masquages qui se chevauchent
  (workspace fermant le dock + plein écran masquant tout).
  Atténuation : le plein écran est **overlay pur** (ne touche pas au
  workspace courant ni à son JSON — §7.7 le prouve) ; sortir du
  plein écran restaure la posture workspace à l'identique.
- **i18n** : `Fermer sans enregistrer`, `Petit canvas : exports
  doux` longs en FR — `maxLines=1..2` + ellipsis (leçon Slice 0 §7) ;
  `%1$s`/`%1$d` jamais concaténés en dur (leçon M5).
