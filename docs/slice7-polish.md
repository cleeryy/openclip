# Slice 7 — Polish visuel studio (SPEC, sans code)

> SPEC UNIQUEMENT — aucune modification app/engine. Vocabulaire maison,
> aucun asset ni capture d'outil tiers. Clean-room : jamais de strings,
> icônes, noms de palettes ni layouts repris de Clip Studio Paint / Celsys
> (cf. `docs/csp-ux-research.md` § clean-room : décrire le fonctionnement
> avec nos mots, jamais copier l'apparence). JNI gelé (`ARCHITECTURE.md`) :
> tout ce qui suit est **app-side uniquement** (`MainActivity.kt`,
> `MenuBar.kt`, `DocTabs.kt`, `DocTitleBar.kt`, `CommandBar.kt`,
> `ToolPalette.kt`, `StudioChrome.kt`, `PinnedBrushBar.kt`,
> `TimelineStrip.kt`, dialogues, ressources `R.string`).
> Conventions : FR/EN via `R.string` uniquement, pas de littéraux FR
> en dur (leçon M5 : apostrophes FR cassent le build).

État vérifié (point de départ) : Slices 0-6 committées (shell, gestes,
couleur PRO, calques pro, timeline, feeling pinceau, multi-docs).
Preuves : `slice1.png` … `slice6.png` (+ checks) ; relire au minimum
`slice5-brushfeel.md` (curseur/HUD/gomme) et `slice6-multidoc.md`
(onglets, titre+save, command bar, workspaces). Référence goût :
`docs/csp-ux-research.md` (dark studio soigné, pas une app pour enfants ;
≤ 2 taps ; chaque panneau repliable, §R1).

Constat : le fonctionnel est complet, le visuel ne l'est pas. La Slice 7
ne rajoute **aucune feature** — elle fige le chrome, supprime les
doublons, rend le téléphone utilisable et pose la régression
« jamais de chrome > budget ».

## 1. Principe — soustraire, aligner, tronquer honnêtement

```
avant  = 7 rangées empilées + 3 sources de vérité (titre / onglets / statut)
après  = 1 statut unique + 1 menu qui tient + 1 titre qui ne répète pas
règle  = chaque rangée a un budget hauteur ; le canvas garde ≥ 2/3 (tablette)
```

- **Aucun JNI, aucun pixel moteur** : tout est Compose overlay
  (`MenuBar`, `DocTitleBar`, footer, chips, dialogues). `pullFrameFull`
  avant/après polish = byte-identique (garantie testable §7.1).
- **Une seule source par info** : le nom du doc vit dans les **onglets**,
  les dims dans le **titre**, le message dans le **statut** — jamais deux
  endroits en même temps (§2).
- **Téléphone = repli + compact, pas mini-tablette** : menu `☰` + save
  1-tap (§3), onglets scroll, command bar scroll, titre 12 car. sans zoom
  (déjà spec Slice 6 §9, ici rendu contraignant + testé).
- **Dark cohérent** : une palette fermée (§4), pas de gris inventé par
  rangée. Contrastes texte/fond ≥ 4.5:1 sur statuts et chips.

## 2. Audit visuel — défauts actés (à corriger par la spec)

Relevé sur les preuves (`/tmp/opencode/openclip-slice*.png`) et le code
(`MainActivity.kt`, `MenuBar.kt`, `DocTabs.kt`, `DocTitleBar.kt`,
`CommandBar.kt`). Chaque défaut porte un ID repris en §6 (portée) et §7
(tests).

### D1. Statut/footer duplique titre + dims (slice6, bloquant)

- Preuve slice6 : footer `WebDAV 2048×1536 Untitled 3 • Untitled 3 2048×1536`
  — l'info apparaît **deux fois** (dims + nom en gris, puis nom + dims en
  vert dans le message).
- Cause code : `MainActivity.kt` `doActivate()` (l.629)
  `status.value = entry.name + " " + tab.w + "×" + tab.h` écrit le titre
  dans le **message éphémère** vert, qui s'ajoute au titre gris déjà
  affiché dans le footer (l.1815-1827 : dims + `doc.name + " •"`).
- Attendu : le switch d'onglet **ne poste aucun message** (le titre et
  les onglets montrent déjà le changement) OU poste un message
  **transverse** (`Espace %1$s`, `Enregistré`…) jamais `nom+dims`.
  Le vert statut est réservé aux **retours d'action** (save/export/undo
  tactile), pas à l'état courant.

### D2. Menu bar téléphone : « W i n d o w » vertical (bloquant)

- Preuves slice5/slice6 (1080×2400) : `MenuBar` (`Row` sans scroll,
  `MenuBar.kt` l.43-48, `padding 4dp`) déborde : `Window` s'écrase en
  colonne lettre-par-lettre, `Help` disparaît, aucun scroll possible.
- Tablette 1920×1200 (slice1) : tout tient (`File…Help` 1 ligne) — la
  régression est **téléphone uniquement**, donc le test doit exister dans
  les deux densités (§7.2).
- Attendu : §3 (menu compact `☰` sous seuil de largeur + rangée scrollable
  en dernier recours). Plus jamais de titre de menu wrappé : `maxLines=1`,
  `softWrap=false`, `overflow=Ellipsis` partout.

### D3. Chips rangée outils tronquées (« Filte… », slice6)

- Preuve slice6 : rangée `Brush | Eraser | Draw | 24px ━●━ (…` — le slider
  sort de l'écran, le chip suivant n'affiche que `(`. Même famille en
  tablette slice1 : `Filte` coupé à droite (rangée sans `horizontalScroll`).
- Cause : rangée outils sans scroll + sliders à largeur fixe trop gourmande
  sur 1080px. Slice 0 §C exigeait déjà le scroll horizontal — non appliqué
  à cette rangée.
- Attendu : toute rangée horizontale d'actions **scrolle** (`horizontalScroll`
  + `Arrangement.spacedBy(4dp)`) OU passe en compact (§3). Aucun chip
  coupé à mi-lettre sans scroll accessible (test §7.3 : scroll → chip
  atteignable et cliquable).

### D4. Titre doc vs onglets vs footer : 3 vérités

- `DocTabs` montre `• Untitled 3 ×`, `DocTitleBar` montre `• Untitled 3` +
  `2048×1536 @z%` + `Save`, le footer remontre `2048×1536` + `Untitled 3 •`.
  Trois widgets disent la même chose avec 3 styles (bleu sélection,
  blanc titre, gris footer).
- Attendu (répartition figée, §5) :
  - onglets = **nom + dirty + ×** (navigation) ;
  - titre = **nom + dims + zoom + save** (contexte, Slice 6 §3 conservé) ;
  - footer = **WebDAV + message statut seul** (plus de dims/nom).
  Le footer perd ses deux `Text` dims/nom (l.1815-1827) ; il garde WebDAV
  (bouton) + message + `weight(1f)`. Les tests §7.1 assertent l'absence.

### D5. Espacements/alignements incohérents

- `MenuBar` : `padding(horizontal=4dp)` sans hauteur fixe ; `DocTabs` :
  `padding 4/2dp` + `heightIn(min=48dp)` ; `DocTitleBar` : `padding 8/2dp`
  sans hauteur min, save 48dp ; `CommandBar` : `padding 4dp`, actions 48dp ;
  footer 34dp fixe + `spacedBy(8dp)`. Résultat visible : les rangées
  « respirent » différemment (titre tassé, onglets aérés, footer dense).
- Attendu : grille unique §4 (hauteurs 48/48/44/48/34, paddings 8dp
  horizontaux, `spacedBy(4dp)` intra-rangée / `8dp` inter-blocs,
  `Divider 1dp #2E2E38` entre chaque rangée). Divider manquant entre
  titre et command bar aujourd'hui → ajouter (même composant que les
  autres, pas de nouveau style).

### D6. Quatre gris de fond qui se battent

- Relevé code : `#202026` (menu), `#1A1A20` (onglets/titre/command bar),
  `#1C1C22` (tiroir), `#1E1E24` (footer). Sur capture les coutures se
  voient (surtout titre/command bar vs onglets collés sans divider).
- Attendu : palette fermée §4 (3 surfaces max + 1 divider). Pas de nouveau
  gris sans passer par la palette.

### D7. Ellipsize FR non systématique

- Leçon Slice 0 §7 rappelée à chaque slice (`maxLines + ellipsis`) mais
  non appliquée partout : `MenuBar` titres sans `maxLines/overflow`
  (d'où D2), footer message sans `maxLines` (d'où la ligne verte qui
  pousse tout sur 2 lignes en FR longue : `Fermer sans enregistrer`,
  `Petit canvas : exports doux`).
- Attendu : tout `Text` de chrome = `maxLines=1` (+ `softWrap=false` en
  rangée) `overflow=Ellipsis`, sauf message statut (`maxLines=1`,
  ellipsis aussi) et bandeaux 2 lignes (`maxLines=2`, §5). Test §7.4 en
  locale FR avec les strings les plus longues.

### D8. États vides/erreurs inégaux

- Canvas vide (dernier onglet fermé) : CTA `newcanvas_empty_cta` existe
  (Slice 6 §2) mais sans spec visuelle (taille, centrage, monumentalité).
- JSON corrompus (`commandbar.json`, `workspaces.json`, `brush-defaults`) :
  fallback presets OK côté stores, mais **aucun retour statut**
  (l'utilisateur ne sait pas que ses réglages sont retombés par défaut).
- `takePersistableUriPermission` refusé (Slice 6 §10) : retombe sur dialogue
  à chaque save — correct, mais non annoncé (l'utilisateur croit à un bug).
- Attendu : §5 (CTA centré monumental + pastilles hint ; statut
  `prefs_reset_hint` / `save_no_persist_hint` 1 fois par session, jamais
  de dialogue bloquant).

## 3. Menu compact téléphone (☰ + save 1-tap)

Seuil : `compact = maxWidth < 600dp` (même bascule que Slice 6 §3/§9 ;
`WindowSizeClass` ou `BoxWithConstraints`, décision implémentation —
une seule bascule partagée par menu/titre/onglets, pas 3 seuils).

- **Compact (< 600dp)** : la `MenuBar` complète disparaît, remplacée par
  `CompactMenuBar` (nouveau composable, même fichier) :
  `☰` (48dp, `testTag("menu-compact")`, ouvre un `DropdownMenu` listant
  **tous** les menus existants par sections : Fichier / Édition / Calque /
  Sélection / Filtre / Affichage / Fenêtre / Aide — mêmes `StudioMenuItem`,
  aucun item nouveau) + titre doc tronqué 12 car. (`testTag("doc-title")`
  réutilisé) + **save 1-tap épinglé** (`testTag("doc-save")` réutilisé,
  même handler que `DocTitleBar`, R7).
  Le `DropdownMenu` est scrollable (`heightIn(max=70% écran)`,
  `verticalScroll`) ; chaque item ≥ 48dp ; section = `disabled` header
  non cliquable (style `labelSmall` gris, pas de string copiée).
- **Large (≥ 600dp)** : `MenuBar` existante conservée + durcissements :
  `Row` gagne `horizontalScroll` (filet si une locale déborde un jour),
  chaque titre `maxLines=1 softWrap=false overflow=Ellipsis`,
  `DropdownMenu` items `maxLines=1 overflow=Ellipsis`.
- **Jamais les deux** : une seule des deux barres est composée à la fois
  (pas de menu caché à 0px qui capte les taps — règle Slice 0 n°5).
- Plein écran : ni l'une ni l'autre (règle existante conservée, §5).

## 4. Cohérence dark (palette fermée, contrastes, grille)

Palette fermée (3 surfaces + texte + accents — les hex ci-dessous sont
les existants, **aucun gris nouveau**) :

| Rôle | Hex | Usage |
|---|---|---|
| `surface-menu` | `#202026` | menu bar (large + compact) |
| `surface-row` | `#1A1A20` | onglets, titre, command bar |
| `surface-status` | `#1E1E24` | footer statut 34dp |
| `surface-panel` | `#1C1C22` | tiroir timeline, feuilles dialogue |
| `divider` | `#2E2E38` | `HorizontalDivider 1dp` entre rangées |
| `ink-primary` | `#ECECF1` | titres, labels actions |
| `ink-secondary` | `#C4C4D0` | croix ×, pastilles, boutons secondaires |
| `ink-hint` | `#787888` | dims, hints, headers section |
| `accent` | `#90B8FF` | liens, reset view, save |
| `select` | `#284678` | onglet actif, preset actif |
| `ok` | `#80E27E` | message succès (sur `#1E1E24` : ratio ≈ 7:1) |
| `warn` | `#E0A050` | gomme, hints ambre (Slice 5 conservé) |
| `err` | `#E08080` | message échec (sur `#1E1E24` : ratio ≈ 4.6:1) |

- Contrastes : `ink-primary/secondary/hint` sur `surface-*` tous ≥ 4.5:1
  (vérifié par construction : le plus faible couple `hint #787888` sur
  `#1A1A20` ≈ 4.9:1) ; `err` réservé aux messages courts (jamais de paragraphe
  en `err`).
- Grille : hauteurs `menu 48dp / onglets 48dp min / titre 44dp min /
  command bar 48dp / footer 34dp` ; `padding horizontal 8dp` partout
  (le `4dp` menu/onglets/command existant monte à `8dp`) ;
  `spacedBy(4dp)` intra-rangée, divider `1dp` inter-rangées (ajouter entre
  titre↔command bar et command bar↔canvas, existants ailleurs conservés).
- Typo : `titleSmall` (titre doc), `labelMedium` (actions/chips),
  `labelSmall` (dims/hints/statut). Pas de `body*` dans le chrome.
  `letterSpacing` par défaut (pas de compactage maison).
- Touch targets : tout cliquable du chrome ≥ **48dp** (tolérance 0.5dp),
  y compris croix d'onglet, `+`, `⋯`, pastilles repli, items dropdown
  (règle Slices 2/5/6 étendue au menu compact + CTA vide).

## 5. Répartition figée + états soignés

Ordre vertical tablette (du haut vers le bas, `A` et `G` ne disparaissent
jamais — Slice 0 §1-§2) :

```
A. MenuBar 48dp (large) OU CompactMenuBar 48dp (compact)   [§3]
B. DocTabs 48dp min (scroll si > 3 onglets)                [nom + • + ×, §2-D4]
T. DocTitleBar 44dp min (nom + dims + zoom + save 1-tap)   [zoom masqué si compact]
C. CommandBar 48dp (scroll, pastille ⌄, édition appui long)[labels existants]
D. Canvas dominant (weight 1f, ≥ 2/3 tablette)
F. Tiroir timeline (replié défaut, max 175dp, inchangé)
G. Footer 34dp : [WebDAV] │ message statut (1 ligne, ellipsis)
```

- **Footer unique** : supprimer les deux `Text` dims/nom (MainActivity
  l.1815-1827) ; ne restent que bouton WebDAV + message statut
  (`maxLines=1 overflow=Ellipsis`, `testTag("status-line")`) + `weight(1f)`.
  `doActivate()` ne poste plus `nom+dims` (supprimer l.628-630 ou poster
  `""` — décision implémentation, le test §7.1 tranche).
- **Budget chrome** (régression, §7.6) : tablette 1920×1200@240
  (hauteur utile ≈ 900dp après barres système) : hauteurs fixes
  A+B+T+C+G = 48+48+44+48+34 = **222dp max** (tiroir replié) → canvas
  ≥ 600dp soit **≥ 2/3**. Téléphone 1080×2400 : tout replié par défaut
  (dock fermé, tiroir fermé, command bar repliée en pastille — Slice 0 §3
  rendu contraignant) ; menu compact 48dp + onglets 48dp (si ≥ 2 docs,
  sinon masqués) + titre 44dp + footer 34dp.
- **CTA canvas vide** : colonne centrée (canvas entier) : icône maison
  `＋` 64dp (texte, pas d'asset) + `newcanvas_empty_cta` (`titleMedium`) +
  hint `newcanvas_empty_hint` (`labelSmall hint`, 2 lignes max) + bouton
  `Nouveau canvas…` 48dp (`testTag` existant conservé). Fond = damier/fond
  canvas existant (pas de nouveau fond).
- **Hints non bloquants** (1 fois par session, statut seul, jamais de
  dialogue) : `prefs_reset_hint` (JSON corrompu → presets, stores
  `commandbar/workspaces/brush-defaults`) ; `save_no_persist_hint`
  (SAF sans persistance → dialogue à chaque save, annoncé une fois).
- **Erreur save/export** : message `err` + conserve le dirty (existant
  Slice 6 §3 conservé) ; succès : message `ok` 3 s puis effacement
  (comportement existant conservé, juste la couleur/1-ligne figées).

## 6. Portée Slice 7 (ce que cette spec autorise à coder ensuite)

1. **Footer unique** (D1+D4) : retirer dims/nom du footer, `doActivate`
   sans message `nom+dims`, `testTag("status-line")` + `maxLines=1` ellipsis.
2. **Menu compact** (D2) : `CompactMenuBar` (`☰` + titre 12 car. + save),
   seuil 600dp unique, `MenuBar` large + `horizontalScroll` + anti-wrap ;
   une seule barre composée à la fois.
3. **Rangées scrollables** (D3) : `horizontalScroll` sur rangée outils
   (et toute rangée d'actions qui en manque), chips `maxLines=1` ellipsis ;
   aucun chip coupé sans scroll.
4. **Palette + grille** (D5+D6) : migrer les 4 gris vers §4, paddings 8dp,
   dividers manquants, hauteurs figées ; aucun hex nouveau.
5. **Ellipsize FR** (D7) : audit `maxLines/overflow/softWrap` de tout `Text`
   du chrome + strings longues FR (§8) ; statut 1 ligne.
6. **États vides/erreurs** (D8) : CTA vide monumental, hints
   `prefs_reset_hint` / `save_no_persist_hint` 1×/session, couleurs ok/err
   figées.
7. **Strings §8** (EN+FR) + fiches Aide retouchées (menu compact, statut
   unique — pas de nouvelle fiche).
8. **Les 7 tests §7 verts** en `emutest.sh` local (tablette + téléphone).

**Décisions actées** (ne pas rouvrir sans nouvelle spec) : le footer ne
porte **plus jamais** dims/nom (message seul) ; le téléphone a un **menu
☰** (pas une rangée écrasée) ; la palette est **fermée** (aucun gris
nouveau) ; le chrome a un **budget chiffré** (§5) ; aucun dialogue
bloquant nouveau (hints statut seuls).

Hors portée (futur, spec dédiée) : vignettes d'onglet (attend
`nLayerCompositeInto`, Slice 3 §1) ; drag-and-drop inter-docs, split-screen
2 docs (1 seul composite, Slice 6 §8) ; autosave ; sync workspaces WebDAV ;
  thumbs timeline plus grandes ; clavier edge/tablette (R6) ; tout JNI.

## 7. Tests instrumentés à ajouter (gate local `emutest.sh`)

Fichier existant étendu : `EngineConformanceTest.kt` (émulateur KVM
local, jamais GitHub CI — règle AGENTS.md). Pattern établi
(`AndroidView` + `dispatchTouchEvent`, `pullFrameFull`, `Thread.sleep`).
Tablette = 1920×1200@240 (`openclip-tablet`), téléphone = 1080×2400
(`openclip-emu`). Chaque test passe **aux deux densités** sauf mention.

1. `statusHasNoDuplicateAfterSwitch` — ouvre B (`+` + dialogue défaut),
   stroke témoin sur A ; active B puis retour A ; assert : footer
   (`testTag("status-line")`) **ne contient ni** `doc.name` **ni** `×`
   (regex `\d+×\d+` absente) après 1 s (statut éphémère consommé) ;
   le titre (`doc-title`) contient le nom, les dims (`doc-dims`)
   contiennent `×` (séparation §5) ; `pullFrameFull` A byte-identique
   avant/après (polish overlay-only).
2. `menuFitsOneLineOrCompact` — tablette : tous les titres menus visibles
   en 1 ligne (`boundsInRoot` de chaque item sur la même bande Y à 4dp,
   `softWrap=false`, aucun titre wrappé multi-lignes) ; téléphone :
   `menu-compact` visible, `MenuBar` complète **absente**, tap `☰` →
   dropdown listant les 8 sections, chaque item ≥ 48dp, tap `Help` →
   dialogue aide (menu fonctionnel, pas décoratif) ; `doc-save` visible
   dans la barre compacte (save 1-tap téléphone).
3. `toolRowNeverClipsWithoutScroll` — téléphone : fling/scroll rangée outils
   jusqu'au bout ; assert : chaque chip (`Brush/Draw/Opacity/save…`) devient
   **entièrement visible** (`boundsInWindow` dans l'écran après scroll,
   plus de `(` coupé comme en D3) et cliquable (tap dernier chip → action
   postée) ; tablette : pas de scroll nécessaire (tout visible sans geste).
4. `frenchLongStringsEllipsize` — locale FR (relaunch `fr`), ouvre dialogue
   dirty + statut long (`Fermer sans enregistrer`, `Petit canvas : exports
   doux`, `Gomme = peinture claire (moteur)`) ; assert : aucun `Text` du
   chrome sur 2 lignes sauf bandeaux déclarés (`maxLines` + ellipsis :
   chaque nœud `maxLines==1`), aucune exception de layout, aucun titre
   menu wrappé (D2 non-régression en FR).
5. `emptyCanvasCtaAndPrefsHint` — ferme tous les onglets (confirme dirty) ;
   assert : CTA centré visible (`newcanvas_empty_cta` + bouton, targets
   ≥ 48dp), menus Fenêtre/Aide ouvrables (pas d'écran mort) ; écrase
   `commandbar.json` corrompu + `recreate()` → presets restaurés **et**
   statut `prefs_reset_hint` affiché 1 fois (pas de crash, pas de dialogue).
6. `chromeBudgetAndCollapse` — tablette tiroir replié : somme hauteurs
   A+B+T+C+G ≤ 230dp (marge sur 222dp §5, via `boundsInRoot`) ET canvas
   (`EngineSurface`) hauteur ≥ 2× la somme (≥ 2/3) ; plein écran (command
   bar) → dock+timeline+bar masqués, A+D+G seuls ; sortie → posture
   restaurée à l'identique (non-régression Slice 6 §7.7). Téléphone :
   défaut = dock fermé + tiroir fermé + command bar repliée (3 asserts).
7. `touchTargets48dpPolish` — toutes cibles chrome (menu items, `☰`,
   onglets + croix + `+`, save titre + save bar, `⋯`, pastilles repli,
   items dropdown, CTA vide, boutons dialogues) : `boundsInRoot` ≥ 48dp
   (tolérance 0.5dp) **aux deux densités**, FR + EN.

Exigences communes : reset (1 doc 2048×1536, pinceau 10px/100 %, onion
OFF, workspaces presets, command bar défaut, locale EN sauf test 4) en
teardown ; debounce persistance (`sleep` ≥ 700 ms) avant asserts fichier ;
seuils couleur Slice 2 (±1 LSB) ; jamais de builds lourds en parallèle de
l'émulateur (8 Go RAM).

## 8. Strings FR/EN proposées + téléphone

Convention Slices 0-6 : clés snake_case, accents FR en `values-fr` only,
placeholders `%1$s` jamais concaténés.

| Clé | EN (`values`) | FR (`values-fr`) | Usage |
|---|---|---|---|
| `menu_compact_open` | Open menu | Ouvrir le menu | §3 `☰` content-description |
| `menu_section_file` … | File… (existants réutilisés) | Fichier… | §3 headers dropdown (clés `menu_*` existantes) |
| `prefs_reset_hint` | Settings reset to defaults | Réglages rétablis par défaut | §5 JSON corrompu (1×/session) |
| `save_no_persist_hint` | Save asks each time (no persist) | Enregistrer redemande à chaque fois | §5 SAF sans persistance |
| `newcanvas_empty_hint` | Pick a size to start drawing | Choisissez un format pour dessiner | §5 CTA vide |
| `status_dismiss` | Clear | Effacer | §5 statut (pastille ×, accessibilité) |

Clés existantes conservées (aucune renommée) : `doc_save`, `save_ok`,
`save_failed`, `doc_untitled`, `docs_full_hint`, `finish_stroke_hint`,
`cmdbar_*`, `workspace_*`, `multidoc_help`, `webdav`, `timeline`,
`window_*`, `nav_mode_*`. FR longues déjà couvertes : `Fermer sans
enregistrer`, `Petit canvas : exports doux` (§7.4).

**Fallback téléphone** (< 600dp) : menu `☰` + save (§3) ; titre 12 car.
sans zoom ; onglets scroll, masqués si 1 doc ; command bar scroll
horizontale repliée par défaut ; dialogues pleine feuille scrollables ;
tout ≥ 48dp (§7.7).

## 9. Risques

- **Supprimer le message de switch casse un repère** : des utilisateurs
  lisent le vert comme confirmation (« je suis bien sur Untitled 3 »).
  Atténuation : l'onglet actif (bleu `select`) + le titre portent déjà
  l'info en permanence, mieux qu'un message éphémère ; le test §7.1 fige
  le nouveau contrat.
- **`☰` cache les menus aux habitués tablette** : le compact n'existe que
  sous 600dp (tablette inchangée) ; le dropdown reprend exactement les
  mêmes items/handlers (pas de divergence fonctionnelle à maintenir —
  §7.2 le prouve en tapant un item).
- **Scroll horizontal = action masquée** : une rangée scrollable peut
  cacher save/export aux novices. Atténuation : l'ordre par défaut met
  save/undo/redo en tête (visibles sans scroll sur 1080px) ; le plein
  écran et la command bar éditables restent 1-tap (§5).
- **Palette fermée vs futurs besoins** : interdire les gris nouveaux peut
  bloquer une future section (ex. miniature navigation n°9). Atténuation :
  la spec autorise `surface-panel` pour tout nouveau panneau ; un 5e gris
  exigera une spec (c'est le but : chaque gris se justifie).
- **Budget 222dp fragile** : une string FR + 2 lignes ou un divider oublié
  fait sauter le budget. Atténuation : test §7.6 avec marge (230dp) en
  EN **et** FR (test §7.4), gate local bloquant avant close.
- **i18n** : `Enregistrer redemande à chaque fois`, `Réglages rétablis par
  défaut` longs en FR — `maxLines=1` + ellipsis + scroll dropdown (§7.4) ;
  `%1$s` jamais concaténés (leçon M5).
