# Post-v1 — backlog parité EX (DOC UNIQUEMENT, sans code)

> **Statut : document de cadrage.** Aucune modification app/engine.
> Vocabulaire maison, aucune string/asset/capture d'outil tiers.
> Référence de périmètre : outil pro de dessin 2D + animation (v5, édition
> étendue) — mirorrer le **fonctionnement**, jamais les ressources.
> Conventions : `FAIT` = livré + vérifié (réf M/slice entre parenthèses) ;
> `EN COURS` = spec et/ou code en chantier ; `FUTUR (Vn)` = proposition de
> tranche post-v1 avec coût **S** (≤ 1 session), **M** (2–4 sessions),
> **L** (> 4 sessions ou dépendance externe) + dépendance
> `moteur / app / JNI-documenté-d'abord` (rappel : tout nouveau symbole
> passe par `ARCHITECTURE.md` **avant** le code) ;
> `EXCLU` = refusé avec raison + alternative libre si elle existe.

Légende tranches proposées : **V+1** = fondations manquantes (sélection,
texte, gomme alpha) ; **V+2** = BD/cases/export long ; **V+3** = confort
pro (pose 3D, mélangeur, profils couleur).

---

## 1. Dessin / peinture

| Item (reformulé maison) | Statut | Détail / réf |
|---|---|---|
| Brosse tampon pression (taille + opacité) | FAIT (M1) | `stroke.h`, `dabFromPressure` |
| Lissage + stabilisation + courbe de pression | FAIT (M2, slice 5) | exponentiel `k=1-0.75·stabilize`, exposant `1+3·curve`, graphe 4 points app-side |
| Presets + import `.myb` + galerie | FAIT (M2) | lane app, 6 presets maison |
| Symétrie miroir X/Y pendant le tracé | FAIT (M6, slice 1 §7) | suit la matrice de vue automatiquement |
| Gomme outil dédié (taille/opacité propres, curseur pointillé) | FAIT (slice 5) | `tool` + `eraserRadius/Opaque` persistés, bandeau honnête |
| Vraie gomme alpha (retrancher, pas recouvrir) | FUTUR (V+1, M, moteur + JNI-documenté-d'abord) | `mode:"alpha-erase"` dest-out ; auj. peinture claire (limite documentée slice 5 §4) |
| Inclinaison stylet (tilt → forme/angle dab) | FUTUR (V+1, M, moteur + app) | champ JSON + `setBrush`, tests oblique |
| Mélangeur de couleurs sur canvas (couteau/palette) | FUTUR (V+3, L, moteur) | nouveau mode dab avec relevé local, à speccer |
| Densité estampilles + interpolation inter-moves | FUTUR (V+1, S, moteur) | cases TASKS M2 restées ouvertes |
| Partage de presets via sync auto-hébergée | FAIT (M10) | WebDAV push/pull, pas de boutique |

## 2. Couleurs

| Item | Statut | Détail / réf |
|---|---|---|
| Sélecteur de base + hex | FAIT (M1) | `ColorPicker` originel |
| Roue HSV + curseurs HSV/RGB + hex éditable | FAIT (slice 2) | `ColorPanel`, `normalizeHex`, source unique `inkHex` |
| Nuanciers sauvegardés (format maison) + récentes | FAIT (slice 2) | `color-sets.json` / `color-recent.json`, v1 |
| Pipette (tap arme, appui-long loupe, hors-canvas raté) | FAIT (slice 2) | sample composite via mapping inverse, overlay-only |
| Teintes procédurales (trames, voir §7) | FAIT (M10) | `nApplyTone`, 6 motifs |
| Épreuvage / profils ICC, CMJN | FUTUR (V+3, M, moteur + app) | pipeline sortie seul, jamais de blobs ICC tiers embarqués sans licence loggée |
| Roue harmonies (complémentaires/triades, guide) | FUTUR (V+3, S, app) | pur calcul, aucun JNI |

## 3. Calques

| Item | Statut | Détail / réf |
|---|---|---|
| Pile + visibilité + opacité + 6 blends + rename + move | FAIT (M5) | `nLayerState`, `LayerPanel` |
| Groupes repliables + mosaïque groupe | FAIT (M4/M5, slice 3) | solo-render, cache LRU |
| Masques + édition du masque | FAIT (M5, slice 3) | `maskTarget`, badge `◑` |
| Écrêtage au calque dessous | FAIT (M5, slice 3) | `clipped`, badge `↓`, stencil vérifié sonde dbg6 |
| Calques de réglage non destructifs (5 types) | FAIT (M8) | composite-time, ORA round-trip, badge `fx` |
| Vignettes live + reorder tactile + blend cherchable + recherche | FAIT (slice 3) | solo-render app-side, 0 JNI |
| Couleurs de repérage par calque | FAIT (slice 3, persistance slice 6) | champ `tagColor` app-side, workspaces JSON |
| Verrou alpha / verrou pixels | FUTUR (V+1, S, moteur + JNI-documenté-d'abord) | flag par calque dans `stampResolved` |
| Modes de fusion supplémentaires | FUTUR (V+2, S, moteur) | liste lue depuis point unique (slice 3 §3), 0 changement UI |

## 4. Sélections / transformations

| Item | Statut | Détail / réf |
|---|---|---|
| Sélection rectangle / ellipse / lasso / baguette + fourmis overlay | FUTUR (V+1, L, moteur + JNI-documenté-d'abord) | masque marching-ants overlay-only ; aucun JNI sélection n'existe |
| Déplacer / échelle / rotation / libre + maillage | FUTUR (V+2, M, moteur + JNI-documenté-d'abord) | rééchantillonnage tuiles, undo via `touchTile` |
| Copier / coller / couper inter-calques et inter-docs | FUTUR (V+2, S, app) | via `pullFrameFull` + collage, pas de JNI |
| Mémorisation de sélections (canaux nommés) | FUTUR (V+3, S, moteur + app) | persistance ORA étendue maison |

## 5. Vecteur

| Item | Statut | Détail / réf |
|---|---|---|
| Calques vectoriels (resample 8px, pression→largeur, undo par tracé) | FAIT (M6) | `LayerPanel` + toggles règles |
| Gomme vectorielle par intersection | FAIT (M6) | `nVectorEraser` |
| Édition points / poignées Bézier | FUTUR (V+2, M, moteur + JNI-documenté-d'abord) | handles overlay app, modèle paths moteur |
| Export SVG des calques vectoriels | FUTUR (V+2, S, app) | sérialisation paths, format ouvert |
| Accrochage / simplification de tracés | FUTUR (V+3, M, moteur) | Ramer-Douglas-Peucker + snap, speccer |

## 6. Formes / règles / perspective

| Item | Statut | Détail / réf |
|---|---|---|
| Règles symétrie / ellipse / segment | FAIT (M6) | toggles existants |
| Perspective 3 points (snap au segment) | FAIT (M9) | `nSetRulers` jusqu'à 3 points |
| Caméra distorsion / fisheye sur référence | FAIT (M9) | `k=fisheye*0.001`, overlay-only |
| Guides persistants + courbes/splines concentriques | FUTUR (V+2, M, moteur + app) | guides en JSON doc, snap moteur |
| Outils formes (rectangle/ellipse/polygone remplis) | FUTUR (V+1, M, moteur + app) | stamps géométriques undoables, pas de JNI neuf si via strokes |

## 7. BD / manga / webtoon + multipage (édition étendue)

| Item | Statut | Détail / réf |
|---|---|---|
| Trames procédurales 6 motifs | FAIT (M10) | `nApplyTone` hatch/crosshatch/dots/grid/checker/stripes, undoable |
| Cases / bulles / traits de vitesse (vectoriels) | FUTUR (V+2, M, moteur + app) | s'appuie sur vecteur M6 + tones M10 |
| Sens de lecture + ordre des cases | FUTUR (V+2, S, app) | métadonnées doc maison |
| Export bande verticale (webtoon) | FUTUR (V+2, S, app) | concat PNG haute, pas de JNI |
| Gestion multipage projet maison (dossier ORA multiples) | FUTUR (V+2, M, app) | sommaire JSON maison + onglets slice 6 |
| Format projet proprio multipage (`.cmc`) en lecture/écriture | EXCLU | format fermé Celsys, recherche l'écarte déjà (`clip-format-research.md` §0) ; alternative : multipage maison ci-dessus |
| Matériaux/boutique propriétaires | EXCLU | remplacés par tones procéduraux + sync WebDAV (M10) |

## 8. Texte

| Item | Statut | Détail / réf |
|---|---|---|
| Calque texte (fonte système, taille/graisse/alignement, rasterisé éditable) | FUTUR (V+1, M, moteur + app + JNI-documenté-d'abord) | piste recommandée : mise en page app-side (Canvas système), import pixels + ORA `x-openclip-text` (police+taille+chaîne) ; pas de moteur fonte natif (règle single-file) |
| Texte sur tracé / déformation texte | FUTUR (V+3, M, moteur + app) | après calque texte + maillage §4 |
| Polices embarquées propriétaires | EXCLU | fontes système + libres (licence loggée) uniquement |

## 9. Animation 2D

| Item | Statut | Détail / réf |
|---|---|---|
| Piste cels + vignettes + fps + lecture timer | FAIT (M7) | `TimelineStrip`, stacks undo par trame |
| Pelure d'oignon avant/après + force | FAIT (M7, slice 4) | toggle maître + steppers 0..4, bordures teintées overlay |
| Scrub tactile + grosses vignettes + garde suppression | FAIT (slice 4) | 128×96, confirm 3 s, jamais 0 cels |
| Piste audio + export WebM | FAIT (M7b) | `ClipAudio` + MediaCodec VP8/Muxer app-side |
| Upscale IA ×3 séquence | FAIT (M8) | ONNX ESPCN luma + chroma bicubique |
| Export GIF / APNG | FUTUR (V+2, M, moteur + app) | formats ouverts, pas de lib sans revue |
| Rythme par cel (hold), reorder cels, vrai compte onion moteur | FUTUR (V+2, M, moteur + JNI-documenté-d'abord) | `nSetOnionCount`, `nMoveCel` — explicitement hors slice 4 |
| Interpolation d'images / auto-colorisation en ligne | EXCLU (partiel) | service cloud Celsys interdit ; auto-colorize locale = FUTUR conditionnel (V+3, L) **si** poids ouverts permissifs (même condition que débruitage M8 différé) |

## 10. 3D / référence

| Item | Statut | Détail / réf |
|---|---|---|
| Visionneuse `.glb` wireframe + caméra yaw/pitch/roll/dist/fov + fisheye | FAIT (M9) | cgltf MIT vendored, overlay composite-only |
| Import référence SAF + panneaux | FAIT (M9) | `ReferencePanel` |
| Mannequin articulé posable (format ouvert) | FUTUR (V+3, L, moteur + app) | squelette glTF + handles, modèles libres (OSMIT & co., licence loggée) |
| Poses / assets propriétaires (`.c3dd/.cs3o/.pep`) | EXCLU | binaires Celsys hors git (règle `.gitignore`) ; alternative : mannequin libre ci-dessus |

## 11. Corrections / effets

| Item | Statut | Détail / réf |
|---|---|---|
| 10 filtres + dialog + preview + undo file | FAIT (M3) | blur/sharp/mosaic/noise/aberration/radial-motion/perlin/gradient-map/posterize/levels |
| Réglages 5 types composite-time | FAIT (M8) | brightness/contrast/hue/saturation/gradientMap |
| Super-résolution ×3 embarquée | FAIT (M8) | ONNX 1.31.0, modèle ESPCN 224 Apache-2.0 bundlé |
| Débruitage local | FUTUR (V+3, M) **conditionnel** | aucun poids mobile à licence claire confirmé (même blocage M8) ; revoir si poids permissifs |
| Courbes de tons / balance par zone éditables par points | FUTUR (V+2, M, moteur + JNI-documenté-d'abord) | LUT 16 floats (`curveLut` déjà identifié slice 5 §8) |
| Colorisation automatique en ligne / scanners en ligne | EXCLU | services cloud Celsys interdits (règle projet) ; alternative locale : voir auto-colorize conditionnelle §9 |

## 12. Interface / productivité

| Item | Statut | Détail / réf |
|---|---|---|
| Shell studio (menus, onglets, rangée outils, dock repliable, tiroir, statut) | FAIT (slice 0) | `MenuBar`, `DocRegistry` single-doc, `CollapsibleDrawer` |
| Gestes canvas (2-tap undo, 3-tap redo, pinch/pan/rotate vue, edge-swipe, stylus-first) | FAIT (slice 1) | matrice de vue app-side, overlays-only |
| Barre tailles épinglée + curseur diamètre réel + HUD pression | FAIT (slice 3/5) | `PinnedBrushBar`, anneau hover, `BrushPrefs` |
| Multi-docs onglets + save 1-tap + nouveau canvas + plafond 4 | EN COURS (slice 6) | spec + code en chantier (fichiers non commités : `DocTabs`, `NewCanvasDialog`, `CommandBar`, `DocTitleBar`) |
| Workspaces nommés JSON + barre commandes épinglable | EN COURS (slice 6) | `WorkspaceStore`, `CommandBarStore`, jamais `.spws` |
| Navigateur miniature (vue réduite + tap-seek) | FUTUR (V+2, S, app) | downscale composite existant, overlay-only (backlog n°9) |
| Autosave périodique explicite | FUTUR (V+2, S, app) | save 1-tap seul aujourd'hui ; opt-in + statut |
| Raccourcis clavier PC / tablette edge-keyboard | FUTUR (V+3, S, app) | après gestes ; clavier physique seul |
| Télémétrie / billing / push / crash-report tiers | EXCLU | "sans telemetry" (M10) ; statut local + WebDAV auto-hébergé |

## 13. Échanges / fichiers

| Item | Statut | Détail / réf |
|---|---|---|
| ORA round-trip complet (groupes, blends, ajustements, miniature) | FAIT (M4/M8) | natif du projet |
| PSD import baseline (fond aplati) + export aplati | FAIT (M4) | `loadPsd`/`savePsd` from-scratch, refus honnête |
| Export PNG/JPEG + EXIF + dialogue échelle | FAIT (M4/M8) | `ExportDialog`, SAF |
| Sync auto-hébergée WebDAV | FAIT (M10) | `WebdavClient`, cleartext localhost seul |
| Import format proprio mono-doc (`.clip`) **lecture seule, aperçu/composite** | EN COURS (recherche faite, impl slice 7) | `clip-format-research.md` : conteneur chunks + SQLite + tuiles zlib ; politique M4 (composite d'abord, refus honnête), jamais natif |
| Export couches PSD plein (layers/blends/masques) | FUTUR (V+2, M, moteur) | étendre `savePsd` au-delà de l'aplati |
| TIFF / PDF / SVG ouverts | FUTUR (V+3, M, moteur + app) | par format, baseline d'abord (leçon PSD) |
| Écriture `.clip` / `.cmc` | EXCLU | formats proprios fermés, pas de spec officielle ; alternative : ORA natif + PSD interchange |
| Services cloud éditeur (assets, sync, auth) | EXCLU | remplacés par WebDAV/Forgege (M10) |

---

## Comptes

- **FAIT : 39** (M1→M10 : 34 · slices 0→5 : 5 chantiers — shell, gestes, couleur, calques, timeline+feeling)
- **EN COURS : 3** (slice 6 : onglets/save/workspaces/command-bar ; import `.clip` lecture — recherche livrée, impl slice 7)
- **FUTUR : 26** (V+1 : 7 · V+2 : 12 · V+3 : 7 — coûts : 10×S, 11×M, 5×L)
- **EXCLU : 11** (cloud Celsys 4 : colorize/débruitage-en-ligne, assets/boutique, sync/auth, telemetry/billing · formats proprios écriture 3 : `.clip`, `.cmc`, poses/assets 3D · divers 4 : `.spws`, ICC/polices embarquées propriétaires, interpolation-cloud, `.cmc` déjà compté)

> Règle de découpe : chaque FUTUR = 1 spec slice dédiée (pattern slices
> 0–6) + JNI documenté dans `ARCHITECTURE.md` **avant** tout code moteur ;
> gate local `emutest.sh` inchangé.
