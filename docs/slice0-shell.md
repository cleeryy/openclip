# Slice 0 — Shell studio cible (SPEC, sans code)

> SPEC UNIQUEMENT — aucune modification app/engine. Vocabulaire maison,
> aucun asset ni capture doutil tiers. Clean-room : jamais de strings,
> icones, noms de palettes ni layouts repris de Clip Studio Paint / Celsys
> (cf. `docs/csp-ux-research.md` § clean-room). JNI gelé (`ARCHITECTURE.md`).
> Conventions : FR/EN via `R.string` uniquement, pas de littéraux FR
> en code (leçon M5 : apostrophes FR en dur cassent le build).

État vérifié (point de départ) : `MainActivity.kt` (~661 l.) = toolbar haut
(`ToolPalette`) + row centrale canvas (`EngineSurface` via `AndroidView`,
`weight(1f)`) / dock droit 290dp repliable (onglets `Studio` /
`3D & Tones`, chips + bouton repli `⟩`) + tiroir timeline bas replié par
défaut (`showTimeline=false`, `heightIn(max=175dp)`) + barre statut 34dp
(toggle timeline, WebDAV, dimensions, statut, bouton rouvrir dock).
Panels réutilisables via pattern surface + modifier
(`LayerPanel`, `ColorPicker`, `PresetGallery`, `ReferencePanel`,
`MaterialsRow`, `TimelineStrip`, `ExportDialog`, `WebdavDialog`).

## 1. Structure cible — paysage tablette 1920×1200@240 (AVD `openclip-tablet`)

Ordre vertical strict, du haut vers le bas. Canvas dominant : ≥ 2/3 de la
largeur utile (R1).

```
┌──────────────────────────────────────────────────────────────┐
│ A. Barre menus type PC adaptée tablette        hauteur 48dp  │
│    Fichier | Édition | Affichage | Calque | Image | Aide     │
├──────────────────────────────────────────────────────────────┤
│ B. Rangée onglets docs (multi-docs-ready)      hauteur 36dp  │
│    [doc1 ╳] [doc2 ╳] [+]              titre+nom/résolution/zoom│
├──────────────────────────────────────────────────────────────┤
│ C. Rangée outils compacte                      hauteur 52dp  │
│    outil ▸ preset ▸ taille ━●━━ ▸ opacité ━●━━ │ undo redo   │
├──────────────────────────────────────┬───────────────────────┤
│                                      │ E. Dock droit 290dp   │
│ D. CANVAS dominant                   │ onglets :             │
│    EngineSurface, weight(1f),        │ Studio │ 3D&Tones │   │
│    ≥ 1280px sur 1920                 │ Avancé (M1→M10)       │
│    (≥ 2/3, outils ≤ 1/3, cf. R1)    │ repli 1 tap (⟩)       │
│                                      │ scroll vertical       │
├──────────────────────────────────────┴───────────────────────┤
│ F. Tiroir chronologie, replié par défaut, max 175dp          │
├──────────────────────────────────────────────────────────────┤
│ G. Barre statut 34dp : toggle tiroir │ WebDAV │ dims │ statut │
└──────────────────────────────────────────────────────────────┘
```

### A. Barre menus (nouveau, type PC adaptée tablette)

- Menus : Fichier (nouveau, ouvrir, enregistrer 1-tap, exporter…,
  sync WebDAV), Édition (annuler/rétablir, tout M2), Affichage (dock,
  tiroir, plein-canvas, zoom), Calque (M5), Image (M3/M8), Aide (à propos,
  licences).
- Tablette : cibles tactiles ≥ 48dp, menus déroulants larges, pas de
  survol requis. Chaque entrée = action existante ou dialogue existant
  (`ExportDialog`, `WebdavDialog`), rien de nouveau côté moteur.
- Doit afficher le doc courant : nom + dimensions + zoom (R7-a).

### B. Rangée onglets multi-docs-ready (nouveau, non destructif)

- Aujourd hui : un seul document / une seule session `EngineSurface`.
- Cible : rangée d onglets sous la barre menus, prête pour N sessions.
  Phase Slice 0 : 1 onglet réel + bouton `+` qui ouvre le dialogue
  nouveau canvas (largeur, hauteur, dpi, fond). Pas de N-sessions moteur
  dans cette slice (cf. backlog R7-e §5) — la rangée réserve l espace et
  le contrat visuel (onglet actif souligné, `╳` par onglet).
- Titre par onglet : `nom — L×H @dpi — zoom%`.

### C. Rangée outils compacte (complète la toolbar actuelle)

- Contenu : groupe outil actif ▸ sous-outil / preset (`PresetGallery`
  existant, version compacte horizontale) ▸ curseurs taille + opacité
  persistants (R2 — aujourd hui absents) ▸ undo / redo / save 1-tap.
- Hauteur fixe 52dp, scroll horizontal si dépassement. Visible même dock
  replié : le dessin ne perd jamais taille/opacité/undo (R2 + R6).
- Stabilisation / courbe (M2) restent dans le dock Studio ; la rangée
  n expose que les deux curseurs + undo/redo/save.

### D. Canvas dominant

- `EngineSurface` inchangé (surface + modifier). Garde tout l espace
  restant (`weight(1f)`), fond damier ou sombre neutre maison.
- Gestes stylet-first (R6, chantier n°1 du backlog, hors Slice 0 sauf
  réservation) : aucune zone morte tactile autour du canvas ; les
  panneaux repliés ne captent aucun événement.

### E. Dock droit 290dp repliable (existant, étendu)

- Largeur fixe 290dp, onglets : `Studio` (calques, couleur, presets),
  `3D&Tones` (référence, matériaux), **`Avancé`** (nouveau — voir §5 :
  M1→M10 sans suppression).
- En-tête : chips d onglets + bouton repli `⟩` (existant). Réouverture
  depuis la barre statut (`⟨ Calques`, existant).
- Contenu scroll vertical (`verticalScroll`, `Arrangement.spacedBy(8dp)`,
  `padding 8dp` — conserver).

### F. Tiroir chronologie (existant, inchangé)

- Replié par défaut (`showTimeline=false`), `max 175dp`, toggle `▸/▾`
  dans la barre statut. Lecture = timer app, jamais boucle moteur (R5).

### G. Barre statut 34dp (existante, enrichie)

- Gauche → droite : toggle tiroir │ WebDAV │ `L×H` réel (remplace le
  `2048×1536` codé en dur actuel) │ message statut │ poids │ bouton
  rouvrir dock si replié.
- Le message statut reste éphémère (save/export/import OK/KO existants).

## 2. Règles de repli (R1 — jamais de régression)

1. **1 tap = disparu.** Chaque panneau (dock, tiroir, rangées B/C)
   possède un toggle visible en permanence ; un seul tap le masque.
2. **Canvas ne perd jamais le focus.** Replier ne détruit pas la session
   (`EngineSurface` vivant, undo par trame conservé — stacks isolées M7).
   Rouvrir restaure l onglet et le scroll précédents.
3. **Ordre de repli automatique** (manque de place ou plein-canvas) :
   tiroir → dock → rangée outils → rangée onglets. La barre menus (A)
   et la barre statut (G) ne disparaissent jamais.
4. **Plein-canvas** (action Affichage) : ne garde que A + D + G.
   Chaque nouveau panneau futur naît repliable (backlog n°10).
5. **Aucun geste captif** : panneau replié = 0px, aucun overlay
   transparent au-dessus du canvas (rose tactile réservée au dessin).

## 3. Fallback téléphone 1080×2400 (portrait)

- **Tout replié par défaut** : dock fermé, tiroir fermé, rangée outils
  réduite aux essentiels (preset actif + taille + undo).
- Canvas pleine largeur ; dock = feuille bas (`bottom sheet` maison,
  pas de libellé copié) plutôt que colonne latérale.
- Barre menus (A) → menu compact `☰` + save 1-tap épinglé (R7).
- Rangée onglets (B) masquée tant qu un seul document ; réapparaît
  dès 2 docs.
- Cibles tactiles ≥ 48dp, pas de texte < 11sp.

## 4. Strings FR/EN proposées (noms `R.string`, sans littéraux)

Convention : clés existantes conservées ; nouvelles clés en snake_case,
accents FR uniquement en `values-fr` (jamais en dur). Placeholders
`%1$d` / `%1$.1f` comme l existant.

| Clé | EN (`values`) | FR (`values-fr`) | Usage |
|---|---|---|---|
| `menu_file` | File | Fichier | A |
| `menu_edit` | Edit | Édition | A |
| `menu_view` | View | Affichage | A |
| `menu_layer` | Layer | Calque | A |
| `menu_image` | Image | Image | A |
| `menu_help` | Help | Aide | A |
| `doc_new` | New canvas… | Nouveau… | B, dialogue px/dpi/fond |
| `doc_title` | %1$s — %2$d×%3$d @%4$d dpi — %5$d%% | %1$s — %2$d×%3$d @%4$d ppp — %5$d %% | B |
| `doc_close` | Close | Fermer | B (`╳`) |
| `tool_size` | Size %1$d | Taille %1$d | C |
| `tool_save_now` | Save | Enregistrer | C, 1-tap (R7) |
| `dock_studio` | Studio | Studio | E (remplace le littéral actuel) |
| `dock_ref_tones` | 3D and Tones | 3D et Teintes | E (remplace le littéral actuel) |
| `dock_advanced` | Advanced | Avancé | E, nouvel onglet §5 |
| `dock_collapse` | Collapse | Replier | E (`⟩`) |
| `dock_expand` | Show panels | Afficher panneaux | G (`⟨ …`) |
| `view_full_canvas` | Full canvas | Plein écran | A, règle n°4 |
| `timeline_toggle_show` | Show timeline | Afficher chrono | G (existant `timeline` conservé) |
| `status_dims` | %1$d×%2$d | %1$d×%2$d | G (remplace le dur) |
| `workspace_named` | Workspace %1$s | Espace %1$s | futur R7-c, réservé |
| `cmdbar_pin` | Pin action | Épingler | futur n°8, réservé |

Notes : `timeline`, `layers`, `webdav` existants conservés ; les deux
littéraux d onglets actuels (`"Studio"`, `"3D & Tones"` en dur dans
`MainActivity.kt` l.395/400) migrent vers `dock_studio` / `dock_ref_tones`.
`2048×1536` en dur (l.532) migre vers `status_dims`.

## 5. Découpage M1→M10 en onglet Avancé (sans suppression)

Rien n est retiré des onglets existants. L onglet **Avancé** ré-expose
chaque jalon M1→M10 comme section repliable, dans l ordre du backlog
`csp-ux-research.md` §3, en réutilisant les composants actuels :

| Section Avancé | Contenu (composants existants, déplacés par référence) | État |
|---|---|---|
| Pinceaux (M1/M2) | `PresetGallery` + stabilisation + courbe + import `.myb` | existant Studio |
| Filtres (M3) | liste 10 filtres + aperçu + appliquer/annuler | existant |
| Fichiers (M4) | ouvrir ORA/PNG/PSD + exporter ORA/PNG/PSD + statut | existant |
| Calques (M5) | `LayerPanel` complet (groupes, masques, clip, opacité, blend) | existant Studio |
| Vecteur + Règles (M6) | calque vectoriel + undo vectoriel + toggles règles | existant Studio |
| Animation (M7/M7b) | `TimelineStrip` (cels, onion, fps, audio, export WebM) | existant tiroir |
| IA (M8) | upscale ×3 + statut (denoise/colorize : mention différés, pas de faux bouton) | existant |
| Référence 3D (M9) | `ReferencePanel` (import `.glb`, yaw/pitch/roll/dist/fov/fisheye, toggle) | existant 3D&Tones |
| Teintes (M10) | `MaterialsRow` (6 tons procéduraux) | existant 3D&Tones |
| Sync (M10) | WebDAV push/pull (`WebdavDialog`) | existant statut |

Règles : sections repliables individuellement (1 tap) ; l onglet Avancé
ne duplique aucun composant — il pointe vers les mêmes `surface` +
`modifier` ; ordre = backlog §3 (gestes, couleur PRO, taille/opacité,
titre/save/nouveau, multi-docs, espaces nommés, vignettes, barre
commandes perso, miniature navigation, plein-canvas) pour les ajouts
futurs, chacun naissant repliable (règle n°4).

## 6. Portée Slice 0 (ce que cette spec autorise à coder ensuite)

1. Barre menus A + rangée onglets B (1 onglet + `+` → dialogue nouveau
   canvas px/dpi/fond) + rangée outils C (taille/opacité persistants +
   undo/redo/save 1-tap).
2. Onglet Avancé E (sections M1→M10 par référence, sans suppression).
3. Migration strings : onglets en dur → `R.string`, dims en dur →
   `status_dims`.
4. Fallback téléphone (§3).
5. Tests instrumentés : repli 1-tap de chaque panneau, canvas ≥ 2/3 en
   1920×1200, tout-replié par défaut en 1080×2400.

Hors portée : gestes R6, couleur PRO R3, multi-docs N-sessions,
espaces nommés, vignettes calques, barre commandes perso, miniature
navigation (backlog §3 n°1,2,5–9 — réservés, clés strings `workspace_named`
/ `cmdbar_pin` déjà réservées).

## 7. Risques visuels

- **Densité 1920×1200@240** : A(48) + B(36) + C(52) + G(34) = 170dp de
  chrome fixe ; reste ~740dp verticaux au canvas avec tiroir replié —
  acceptable, mais chaque dp ajouté à C doit être justifié (R2).
- **Rangée outils vs dock** : doublon preset/curseurs si le dock Studio
  garde les siens — prévoir masquage contextuel (dock ouvert = version
  complète, rangée = compacte) pour éviter la redondance visuelle.
- **Onglets 1-doc** : rangée B presque vide en Slice 0 (1 onglet + `+`) —
  risque d impression de vide ; atténué par le titre doc riche
  (nom/dims/zoom) aligné à droite.
- **Téléphone** : bottom-sheet + rangée compacte = deux patterns
  navigation distincts de la tablette ; tester sur AVD `openclip-emu`
  1080×2400 dès la première implémentation.
- **i18n** : libellés FR plus longs (ex. `Chronologie`, `Courbe de
  pression`) — prévoir ellipsization + `maxLines=1` sur chips et onglets.
