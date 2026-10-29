# Écart de parité — inventaire source vs code réel (DOC UNIQUEMENT)

> **Statut au 2026-10-29 (alpha `0.2.0-alpha`) : instantané v0, conservé
> comme point de départ.**
> Cet inventaire a été établi le 2026-10-21, **avant** les tranches de
> parité T1→T5. Depuis, **T1→T5 sont closes** (preuves, commits et runs
> dans `docs/TASKS.md`) : remplissage/dégradé/baguette/figures (T1),
> texte + vectoriel avancé (T2), keyframes/caméra/pistes/APNG (T3), warp/
> marionnette/filtres créatifs/décor 3D (T4), verrous/fills/`.grd`/`.abr`/
> macros/pages/EPUB/PDF (T5). Les tableaux §1→§14 ci-dessous décrivent donc
> l'état **avant** ces tranches ; le §« Ordre d'implémentation proposé » est
> **historique** (les 5 tranches y sont livrées, pas à reprendre).
> Base réelle au 2026-10-29 : **engine 237/237 tests**, **emu 248/248**
> (`@Test`, gate local KVM), JNI gelé documenté en `ARCHITECTURE.md`
> (M1→M10 + V+1→V+3 + T1→T5).
> **Écarts encore différés : suivis dans l'issue GitHub #4** — ne pas se
> fier aux compteurs de synthèse ci-dessous (23 % FAIT) pour l'état actuel.
> Méthode : chaque entrée de `docs/csp-functional-list.md` vérifiée contre le
> code (pas les docs marketing) : `engine/include/openclip/*.h` + shim
> `app/src/main/cpp/ExternalC.cpp` + `engine/tests/test_*.cpp` +
> `app/src/androidTest/.../EngineConformanceTest.kt` + `app/src/main`
> (Compose) + `ARCHITECTURE.md` (JNI) + specs `docs/v1/v2/v3-*.md` et
> `docs/t1..t5-*.md`.
> Vocabulaire maison, aucune string Celsys.
> Règle de sévérité : **FAIT = testé** (engine `make test` ou emu
> instrumenté nommé). Sinon PARTIEL ou ABSENT, même si le code semble proche.
> Base réelle **à la date de l'inventaire** : engine ~116 tests,
> emu **150 `@Test`** ; au 2026-10-29 : engine **237/237**, emu **248/248**
> (les deux chiffres sont rappelés ci-dessus).
> Comptage ci-dessous sur **260 sous-entrées détaillées** (la source en
> regroupe ~200 après fusion des variantes sur une ligne).

Légende : **FAIT** = livré + testé (preuve citée) · **PARTIEL** = marche
partiellement, écart précis cité · **ABSENT** = rien dans le code ·
**EXCLU** = refusé avec raison (jamais à implémenter, §8).

## 1. Panneaux d'interface (30)

| # | Statut | Preuve / écart |
|---|---|---|
| 1.1 Outils | FAIT | `ToolPalette.kt` + emu `slice7_toolRowNeverClipsWithoutScroll`, `touchTargets48dp*` |
| 1.2 Groupes d'outils | PARTIEL | `PresetGallery.kt` + import `.myb` M2 (emu `loadMybAcceptsValidRejectsMalformed`) ; **manque** : création/édition de sous-outil, import/export de sous-outil |
| 1.3 Réglages outil actif | FAIT | `PinnedBrushBar.kt` + `ToolPalette.kt` (taille/opacité/stabilize/curve/symétrie) ; emu `pinnedBarPersistsAndMirrors` |
| 1.4 Taille de brosse | FAIT | `PinnedBrushBar` + emu `cursorMatchesBrushDiameter` |
| 1.5 Paramètres détaillés | PARTIEL | sliders stabilize/curve/mix/grid/epsilon ; **manque** : jitter, double-pointe, tilt, paramétrage fin par outil |
| 1.6 Roue chromatique | FAIT | `ColorPanel.kt WheelTab` ; emu `wheelDrivesEngine` |
| 1.7 Curseurs couleur | FAIT | `ColorPanel.kt SlidersTab` HSV/RGB + `HexField` ; emu `slidersAndHexAgree` |
| 1.8 Nuanciers | FAIT | `SetsTab`, `color-sets.json` maison |
| 1.9 Mélangeur | FAIT | V+3 mode `mix` moteur (`brush.h mix/mixRadius`) ; emu `mixerSmearsStroke` ; engine `mixer_*` (3 tests) |
| 1.10 Historique couleurs | FAIT | `RecentsTab`, `color-recent.json` ; emu `recentsSurviveRestart`, `recentsFillOnStrokeOnly` |
| 1.11 Couleurs intermédiaires | ABSENT | rien (pas de palette de rapprochements) |
| 1.12 Calques | FAIT | `LayerPanel.kt` (visibilité/opacité/blends/rename/move/groupes) ; emu `layerDragReorderMovesOnce`, `fiftyLayersStayReadable` |
| 1.13 Recherche calques | FAIT | champ search `LayerPanel` + `blendSearchableApplies` (nom + blend cherchables) |
| 1.14 Propriétés calque actif | FAIT | `nLayerState` + miroir adjust ; emu `blendsExtraAccept`, `curvesChannelRoundTrip` |
| 1.15 Navigateur | FAIT | `NavigatorPanel` V+2 (downscale + tap-seek) ; emu `navigatorSeekRecenter` |
| 1.16 Cellulos | FAIT | `TimelineStrip.kt` vignettes ; emu `timelineThumbnailsMatchFrameCount`, `scrubChangesCurrentFrame` |
| 1.17 Chronologie | FAIT | `TimelineStrip` + fps + lecture ; emu `fpsClampAndPlaySteps`, `celHoldPlayback`, `celReorderThumbs` |
| 1.18 Banque d'éléments | ABSENT | tones procéduraux ≠ stockage d'objets réutilisables ; rien |
| 1.19 Vue secondaire | ABSENT | `ReferencePanel` = overlay wireframe, pas d'image de référence côte à côte |
| 1.20 Matériaux historique/tri | PARTIEL | `MaterialsRow.kt` + `nApplyTone` (emu `toneFillChangesCompositeAndUndoRestores`) ; **manque** : historique, tri v5.0, image-comme-matériau |
| 1.21 Enregistrement auto/type | ABSENT | rien |
| 1.22 Recherche matériaux | ABSENT | rien |
| 1.23 Historique opérations | PARTIEL | undo/redo multi-niveaux par trame moteur (`test_undo`, emu `shortcutUndoRedo`, `twoFingerTapUndoesStroke`) ; **manque** : panneau listant les opérations |
| 1.24 Panneau d'information | ABSENT | coordonnées/dimensions : probes de test seulement, pas de panneau |
| 1.25 Actions automatisées | ABSENT | pas de macros rejouables |
| 1.26 Aligner/répartir | ABSENT | rien |
| 1.27 Vue tous côtés | PARTIEL | symétrie miroir X/Y pendant le tracé (emu `rulerSymmetryMirrorsStrokes`) ; **manque** : aperçu symétrique multi-vues |
| 1.28 Accès rapide (smartphone) | EXCLU | plateforme : pas de panneau smartphone dédié (voir §8) |
| 1.29 Compositions de calques | ABSENT | pas de création/lecture de compositions |
| 1.30 Curseurs flottants | PARTIEL | `PinnedBrushBar` + `CommandBar` épinglables (emu `slice6_commandBarPinAndFullscreen`) ; **manque** : curseurs flottants libres |

## 2. Outils (~33)

| # | Statut | Preuve / écart |
|---|---|---|
| 2.1 Zoom | FAIT | pinch ; emu `pinchZoomLeavesNoInk` |
| 2.2 Main/rotation/zoom | FAIT | emu `twoFingerDragPansWithoutInk`, `rotateGestureTiltsWithoutInk` |
| 2.3 Manipulation d'objets | PARTIEL | `nSelectionBlit` move (emu `transformMoveUndoable`), reorder calques ; **manque** : déplacer motif/trame/grille, table lumineuse-objet, édition chrono-objet |
| 2.4 Sélections lasso/rect/ellipse… | PARTIEL | rect/ellipse/lasso V+1 (5 emu : `selectionRectGestureConstrainsStroke`…) + inverser/+1px ; **manque** : pinceau de sélection, effacer/réduire sélection |
| 2.5 Lanceur de sélection | ABSENT | pas de menu contextuel de sélection |
| 2.6 Personnalisation lanceur | ABSENT | rien |
| 2.7 Baguette magique | ABSENT | explicitement différée V+1-bis (flood-fill tuilé non implémenté) |
| 2.8 Pipette | FAIT | emu `pipetteSamplesRightColor` (composite, loupe, hors-canvas raté) |
| 2.9 Plume/encre | PARTIEL | brosse générique + presets `.myb`, pas de moteur plume distinct ; dodge/burn via blends couverts partiellement |
| 2.10 Crayon | PARTIEL | idem (pas de mine distincte) |
| 2.11 Pinceau | FAIT | `stroke.h` accumulateur + stabilize + curve ; engine `test_stroke`/`test_brush`, emu `strokeAcrossP`, `curveChangesRendering` |
| 2.12 Aérographe | PARTIEL | opacité/pression/hardness couvrent l'usage, pas de moteur aérographe distinct |
| 2.13 Décoration | ABSENT | pas d'outil motifs (tones = remplissage, pas outil) |
| 2.14 Gomme | FAIT | `alpha-erase` dest-out moteur (5 engine `alphaErase*`) + gomme vectorielle M6 ; emu `eraserDestOutComposite`, `eraserBannerGone` |
| 2.15 Mélange | PARTIEL | couteau `mix` V+3 FAIT ; **manque** : estompe/flou/doigt/bords humides/texture en outils distincts |
| 2.16 Liquefier | ABSENT | mesh V+2 = sur sélection, pas de pinceau liquéfier |
| 2.17 Tampon duplication | ABSENT | rien |
| 2.18 Remplissage | ABSENT | aucun flood-fill (`grep floodFill/bucket` vide) ; `gradientMap` = filtre/ajustement, pas outil |
| 2.19 Dégradé | ABSENT | pas d'outil dégradé (gradientMap filtre/ajustement seulement) |
| 2.20 Dégradé libre | ABSENT | rien |
| 2.21 Figures | ABSENT | backlog V+1 resté ouvert : pas de rectangle/ellipse/polygone (seules cases BD via vecteur) |
| 2.22 Lignes de vitesse | FAIT | générateur V+2 (point de fuite + LCG) ; emu `speedGeneratesUndoable` |
| 2.23 Lignes de focus | PARTIEL | guides concentriques + speed radiales ; **manque** : denses/luminosité/explosion dédiées |
| 2.24 Cadres de case | PARTIEL | cases rect/poly + fond blanc (emu `panelRectStrokeClosed`) ; **manque** : stylo de cadre libre |
| 2.25 Division cadre/bord | ABSENT | rien |
| 2.26 Règles | PARTIEL | symétrie/ellipse/segment/perspective-3-pts/guides/concentriques/grid/snapPaths (emu `guidesSnapSteersStroke`, `rulerSnapKeysRoundTrip`) ; **manque** : courbe, figure, stylo de règle, guide-ligne |
| 2.27 Fisheye | PARTIEL | fisheye caméra référence moteur (`ref_fisheye_displaces_pixels`) ; **manque** : règle fisheye de tracé |
| 2.28 Règles spéciales | PARTIEL | concentriques + radiales ; **manque** : parallèles/multiples/cercles concentriques-outil |
| 2.29 Texte | FAIT | V+1 `nSetLayerText` + `nBlitPngIntoLayer` ; 4 emu (`textCommitPaintsPixels`…) |
| 2.30 Phylactères | FAIT | V+2 ellipse/rounded + queue + texte lié ; emu `bubbleTailAndTextLink` |
| 2.31 Flash/éclats | ABSENT | rien |
| 2.32 Correction de ligne | PARTIEL | édition points Bézier V+2 (3 emu) + RDP/snap V+3 ; **manque** : pincer/retracer/ajuster épaisseur dédiés |
| 2.33 Dépoussiérage | PARTIEL | `denoise` classique V+3 (emu `denoiseReducesNoise`) ; **manque** : nettoyage de scan (supprimer/combler/sélection poussière) |

## 3. Types de calques (13)

| # | Statut | Preuve / écart |
|---|---|---|
| 3.1 Pixel | FAIT | `canvas_layers_composite`, strokes emu |
| 3.2 Vectoriel | FAIT | M6 (7 engine `vector_*`) ; emu `vectorLayerAppendAndUndo`, `vectorEraserRemovesIntersectingPath` |
| 3.3 Calque 3D | PARTIEL | référence `.glb` overlay (emu `refLoadAndCompositeShowsEdges`) ; **manque** : calque 3D manipulable |
| 3.4 Matériau image | ABSENT | rien |
| 3.5 Dégradé | ABSENT | gradientMap = ajustement, pas calque |
| 3.6 Remplissage uni | ABSENT | rien |
| 3.7 Correction (8 familles) | PARTIEL | FAIT : brightness/contrast/hue/saturation/gradientMap/curves (engine `adjust_*` + `curves_*`, emu `curvesIdentityComposite`) ; **manque en ajustement** : posterize/levels (filtres seuls), dégradé inverse, balance couleurs, seuillage |
| 3.8 Correspondance dégradé | FAIT | `gradientMap` ajustement M8 (ORA round-trip) |
| 3.9 Bord de cadre | ABSENT | cases = paths vectoriels, pas calque dédié |
| 3.10 Sélection stockée | FAIT | canaux nommés V+3 persistés PNG + booléens ; 3 emu (`channelSaveReload`…) |
| 3.11 Phylactère + texte | PARTIEL | texte FAIT ; **manque** : calque phylactère dédié (bulles = vector + texte lié) |
| 3.12 Objet fichier | ABSENT | pas de référence externe liée |
| 3.13 Foil | ABSENT | rien |

## 4. Moteur de brosse (5)

| # | Statut | Preuve / écart |
|---|---|---|
| 4.1 Mélange réaliste | PARTIEL | `mix` relevé local V+3 ; **manque** : modèle pigmentaire |
| 4.2 Brosse double | ABSENT | rien |
| 4.3 Jitter teinte | ABSENT | stabilize/curve seuls, pas de variation aléatoire |
| 4.4 Multi-pointes | PARTIEL | 6 presets maison + parseur `.myb` ; **manque** : enregistrement plusieurs pointes |
| 4.5 Import `.abr` | ABSENT | seul `.myb` est parsé (`nLoadMyb`) ; rendu fidélité Photoshop non testé car absent |

## 5. Fenêtre et affichage (13)

| # | Statut | Preuve / écart |
|---|---|---|
| 5.1 Rotation/miroir | FAIT | gestures rotate + symétrie X/Y ; emu `rotateGestureTiltsWithoutInk`, `rulerSymmetryMirrorsStrokes` |
| 5.2 Zoom fluide | FAIT | pinch ; emu `pinchZoomLeavesNoInk` |
| 5.3 Taille d'impression | ABSENT | `dpi` param `nInit` mais pas de mode taille-réelle |
| 5.4 Règles/guides affichés | FAIT | guides persistants + toggle ; emu `guidesPersistReload` |
| 5.5 Grille / Lab | PARTIEL | grille 8/16/32 V+3 (emu `snapGridPersists`) ; **manque** : affichage Lab v5.1 |
| 5.6 Repères de coupe | ABSENT | rien |
| 5.7 Zones transparentes | PARTIEL | fond transparent au nouveau canvas (`NewCanvasDialog`) ; **manque** : damier de visualisation |
| 5.8 Espaces de travail | PARTIEL | workspaces JSON maison (emu `slice6_workspaceRoundTrip`) ; **manque** : espaces prédéfinis commutables |
| 5.9 Barre personnalisable | FAIT | `CommandBar` pin + `PinnedBrushBar` ; emu `slice6_commandBarPinAndFullscreen` |
| 5.10 Masquer chrome | FAIT | plein-écran (shortcut F + commandBar) |
| 5.11 CMJN | PARTIEL | épreuvage naïf overlay V+3 (emu `proofCmykOverlayOnly`, `proofGamutFlagsSaturated`) ; **manque** : vrai pipeline CMJN |
| 5.12 Thème interface | FAIT | palette sombre slice 7 (emu `slice7_*`) |
| 5.13 Pipette d'écran | PARTIEL | pipette composite FAIT ; **manque** : prélèvement hors-app (OS) |

## 6. Édition (~21)

| # | Statut | Preuve / écart |
|---|---|---|
| 6.1 Lissage | FAIT | stabilize + RDP V+3 ; emu `simplifyReducesPoints` |
| 6.2 Suppression trames (serveur) | EXCLU | IA-serveur interdit (§8) ; alternative partielle : `denoise` local |
| 6.3 Remplissage | ABSENT | cf 2.18 : aucun flood-fill |
| 6.4 Colorisation auto (serveur) | EXCLU | cloud interdit (§8) ; locale conditionnelle non livrée (pas de poids permissifs) |
| 6.5 Contour de sélection | ABSENT | rien (menu = tout/inverser/+1px/désélectionner) |
| 6.6 Flou de sélection | PARTIEL | adoucissement +1px (`select_feather`) ; **manque** : flou paramétrable |
| 6.7 Masque rapide | ABSENT | canaux V+3 = persistants, pas de quick-mask session |
| 6.8 Corrections (1) | FAIT | brightness/contrast/hue/saturation (ajustements) + levels (filtre) ; tests engine + emu |
| 6.9 Corrections (2) | PARTIEL | curves/posterize/levels/gradientMap FAIT ; **manque** : dégradé inverse, balance couleurs, seuillage |
| 6.10 Correspondance dégradé | FAIT | filtre + ajustement `gradientMap` |
| 6.11 Import `.grd` | ABSENT | rien |
| 6.12 Accord chromatique | ABSENT | rien |
| 6.13 Effets multi-calques | PARTIEL | filtres/tones sous masque via pont trame-dupliquée (emu `selectionFilterMasked`, `panelToneMasked`) avec aplatissement + reset undo annoncés |
| 6.14 Transfos | PARTIEL | move/free/mesh testés (4 engine `transform_*` + 2 emu) ; **manque** : perspective, skew, distorsion, miroirs dédiés |
| 6.15 Maillage | FAIT | `nSelectionBlit` mesh + emu `textMeshWarpUndoable` |
| 6.16 Marionnette | ABSENT | rien |
| 6.17 Mémoriser rotation | ABSENT | `resetView` seul, pas de mémorisation |
| 6.18 Résolution/taille canvas | PARTIEL | `NewCanvasDialog` dims + garde (emu) ; **manque** : redimensionnement du canvas existant |
| 6.19 Image-comme-matériau | ABSENT | cf 1.21 |
| 6.20 Couleur d'écran (OS) | ABSENT | plateforme ; rien |
| 6.21 Aide à l'ombrage | ABSENT | rien |

## 7. Propriétés de calque (~21)

| # | Statut | Preuve / écart |
|---|---|---|
| 7.1 Opacité | FAIT | `nSetLayerOpacity` + slider ; emu couches |
| 7.2 Dossiers + tri | FAIT | groupes M4/M5 + `doMove`/drag-reorder ; emu `layerDragReorderMovesOnce` |
| 7.3 Fusion | PARTIEL | aplatissement via ponts (`nLoadDocument` trame mixée, annoncé) ; **manque** : fusion explicite testée |
| 7.4 Effet de bord | ABSENT | rien |
| 7.5 Trames | FAIT | 6 tones + densité V+2 (emu `panelDensityThins`) ; engine `tone_*` (4 tests) |
| 7.6 Bord aquarelle | ABSENT | rien |
| 7.7 Couleur de calque | FAIT | `tagColor` app-side (slice 3, persistance slice 6) |
| 7.8 Modes de fusion | PARTIEL | 10 modes testés (`blend_*` + `blend_names_v2`) ; **manque** ≈20 modes source (assombrir/éclaircir, densité ±, soustraire, esquive, incrustation partielle, vive/linéaire/ponctuelle, maximal, ± couleur, diviser, traversant…) |
| 7.9 Textures | ABSENT | rien (tones ≠ texture de trait) |
| 7.10 Masques | FAIT | M5 (`mask_multiply_hides`, `mask_undo_restores`) ; emu `maskTargetStrokeHidesAndUndoRestores`, `clipMaskBadges` |
| 7.11 Lier règle au calque | ABSENT | guides au niveau document, pas par calque |
| 7.12 Calque de référence | ABSENT | pas de flag référence |
| 7.13 Écrêtage | FAIT | `clipped_respects_stencil` ; emu `clippedLayerRespectsStencil` |
| 7.14 Verrouiller | ABSENT | aucun flag lock (`grep lock` vide côté moteur) |
| 7.15 Verrou alpha | ABSENT | backlog V+1 non implémenté |
| 7.16 Brouillon | ABSENT | pas d'attribut draft/exclusion d'export |
| 7.17 Sélection d'après calque | ABSENT | pas de charge du masque depuis l'alpha du calque |
| 7.18 Pixel→vectoriel | ABSENT | rien |
| 7.19 Pixeliser | PARTIEL | rasterisation via `nBlitPngIntoLayer` (texte) ; **manque** : pixeliser explicite (vectoriel/texte→pixels) |
| 7.20 Transférer vers inférieur | ABSENT | pas de transfert explicite (couper/coller partiel seulement) |
| 7.21 Masquer brouillons | ABSENT | découle de 7.16 |

## 8. Vectoriel (8)

| # | Statut | Preuve / écart |
|---|---|---|
| 8.1 Pointe/taille/couleur | PARTIEL | pression→largeur moteur (`vector_pressure_maps_to_width`) ; **manque** : changement de pointe/taille/couleur par tracé |
| 8.2 Édition par points | FAIT | emu `bezierMovePoint` |
| 8.3 Déplacer/ajouter/supprimer | PARTIEL | move/insert/delete (emu `bezierInsertDelete`) ; **manque** : bascule des sommets |
| 8.4 Épaisseur/densité | PARTIEL | densification par poignées (emu `bezierHandleDensifies`) ; **manque** : réglage direct d'épaisseur (moyenne du profil à l'export SVG) |
| 8.5 Aimant/connecter | PARTIEL | `snapPaths` V+3 (engine `guides_path_vertex_snap`) = snap, pas aimant vectoriel ; **manque** : connexion de lignes |
| 8.6 Retracer | ABSENT | pas de re-tracé épaisseur/ligne |
| 8.7 Trajectoires visibles | PARTIEL | overlay d'édition (probes) ; **manque** : affichage permanent des trajectoires |
| 8.8 Copie SVG | FAIT | `SvgPanel` V+2 ; emu `svgExportValidXml`, `svgReimportApprox` |

## 9. Filtres (~25)

| # | Statut | Preuve / écart |
|---|---|---|
| 9.1 Flou/lissage/renforcement | FAIT | `gaussianBlur`, `sharpen` (engine `filters_gaussian_invariants`, `filters_sharpen_contrast`) |
| 9.2 Flou disque/radial v2.0 | PARTIEL | `radialBlur` ; **manque** : disque tournant (spin) dédié |
| 9.3 Flou radial/mouvement | FAIT | `radialBlur` + `motionBlur` (`filters_motion_radial_smooth`) |
| 9.4 Flou d'objectif | ABSENT | rien |
| 9.5 Netteté | FAIT | `sharpen` (répétable, pas de double niveau nommé) |
| 9.6 Masque flou | ABSENT | unsharp ≠ sharpen ; rien |
| 9.7 Anti-bruit/mosaïque/artistique | PARTIEL | `addNoise` + `mosaic` + `denoise` FAIT ; **manque** : « artistique » |
| 9.8 Crayon | ABSENT | rien |
| 9.9 Rétro | ABSENT | rien |
| 9.10 Aberration chromatique | FAIT | `filters_chromatic_shifts_rb_not_g` |
| 9.11 Bruit | FAIT | `addNoise` (`filters_noise_reproducible`) |
| 9.12 Carte de normales | ABSENT | rien |
| 9.13 Cristallisation | ABSENT | rien |
| 9.14 Lueur | ABSENT | rien |
| 9.15 Gonflement | ABSENT | rien |
| 9.16 Ombre portée | ABSENT | rien |
| 9.17 Distorsions (8) | PARTIEL | fisheye caméra-référence seul ; **manque** : pincement/zigzag/courbe/polaire/ondulation/vague/tourbillon en filtres |
| 9.18 Distorsion objectif/panoramas | ABSENT | rien |
| 9.19 Perlin | ABSENT | `addNoise` ≠ Perlin ; pas de motif nuageux |
| 9.20 Épaisseur de ligne | ABSENT | rien |
| 9.21 Dépoussiérage | PARTIEL | `denoise` filtre (cf 2.33) |
| 9.22 Filtres rapides / calque | FAIT | `FilterDialog` + preview + queue undoable (emu `applyFilterKnownPosterizeModifiesComposite`) |
| 9.23 Filtres rapides / sélection | PARTIEL | via pont mix (emu `selectionFilterMasked`), aplatissement annoncé |
| 9.24 Effets rapides / calque | FAIT | tones via `MaterialsRow` (emu `toneFillChangesCompositeAndUndoRestores`) |
| 9.25 Effets rapides / sélection | PARTIEL | tones sous masque polygonal (emu `panelToneMasked`), aplatissement annoncé |

## 10. Texte (11)

| # | Statut | Preuve / écart |
|---|---|---|
| 10.1 Outil texte | FAIT | V+1, 4 emu |
| 10.2 Transformation texte | PARTIEL | mesh sur texte validé (emu `textMeshWarpUndoable`) ; **manque** : échelle/rotation/inclinaison dédiées |
| 10.3 Interlignage | FAIT | slider leading `TextPanel` + fiche ORA |
| 10.4 Interlettrage/italique/contour | PARTIEL | tracking + B/I FAIT ; **manque** : contour de caractères |
| 10.5 Ajout de polices | PARTIEL | fontes système listées (`fontMenu`) ; **manque** : import depuis fichiers |
| 10.6 Mélange de polices | ABSENT | style global, pas de runs multi-polices |
| 10.7 Caractères externes | ABSENT | rien |
| 10.8 Furigana | ABSENT | rien |
| 10.9 Saisie inline | ABSENT | panneau séparé, pas de saisie inline canvas |
| 10.10 Export PSD texte | ABSENT | PSD = pixels (texte non préservé comme texte) |
| 10.11 Éditeur d'histoire | ABSENT | rien |

## 11. Aide à la création / 3D (~28)

| # | Statut | Preuve / écart |
|---|---|---|
| 11.1 Forme intelligente | ABSENT | rien |
| 11.2–11.6 Mannequin (proportions/poses/mains/perspectives/liste) | PARTIEL | mannequin générique V+3 : `nRefJoints` + `nSetRefPose` (engine `ref_pose_*`, emu `poseHandlesMove`, `poseRoundTrip`) ; **manque** : morphologie tête-corps, poses humaines naturelles, mains, perspectives manga, bibliothèque de poses |
| 11.7 Poses `.pep` | EXCLU | format proprio (§8) |
| 11.8 Morphologies | PARTIEL | `pose.json` persiste la pose (emu `poseRoundTrip`) ; **manque** : morphologies nommées |
| 11.9 Peindre modèles 3D | ABSENT | rien (overlay wireframe seul) |
| 11.10 Décors 3D (liste) | PARTIEL | import `.glb` + sliders caméra (`ReferencePanel`) ; **manque** : choix mise en page/angles depuis une liste |
| 11.11 Primitives 3D | ABSENT | import seul, pas de création |
| 11.12 Placer objets dans décor | ABSENT | rien |
| 11.13 Personnages 3D (import/poser) | PARTIEL | `nLoadGlbReference` wireframe (18 arêtes cube-fixture) ; **manque** : poser (rotations exceptées V+3) |
| 11.14 Expression/coiffure/vêtements | ABSENT | rien |
| 11.15 Peindre personnages 3D | ABSENT | rien |
| 11.16 Tête 3D | PARTIEL | mannequin générique seul, pas de tête dédiée |
| 11.17 Matériaux objets 3D | ABSENT | rien |
| 11.18 Main 3D | PARTIEL | générique seul, pas de main dédiée |
| 11.19 Scanner pose main | EXCLU | IA-serveur (§8) |
| 11.20 Scanner pose image | EXCLU | IA-serveur (§8) |
| 11.21 Import 3D réaliste | PARTIEL | wireframe seul, pas de rendu réaliste |
| 11.22 Mocap externe | EXCLU | plateforme + proprio (§8) |
| 11.23 Image 2D→lignes/tons | PARTIEL | import SVG subset (emu `svgImportPaths`, `svgImportIgnoresText`) ; **manque** : extraction auto lignes/tons |
| 11.24 Image 3D→lignes/tons | PARTIEL | wireframe traçable à la main (aide overlay) ; **manque** : conversion auto |
| 11.25 Accrochage 3D / copie | ABSENT | rien |
| 11.26 Réglages rendu 3D | PARTIEL | yaw/pitch/roll/dist/fov/fisheye sliders ; **manque** : tout rendu hors wireframe |
| 11.27 Physique 3D | ABSENT | rien |
| 11.28 Poses externes (service) | EXCLU | service en ligne (§8) |

## 12. Multi-pages (~15)

| # | Statut | Preuve / écart |
|---|---|---|
| 12.1 Gestionnaire de pages | FAIT | `PagesPanel` `.ocpages/` maison ; 3 emu (`multipageInsertDelete`…) |
| 12.2 Double/simple + repères | PARTIEL | une page chargée à la fois ; **manque** : vue double, alignement repères de coupe |
| 12.3 Reliure / page départ | PARTIEL | `reading` ltr/rtl (emu `readingOrderRoundTrip`) ; **manque** : sens de reliure, page de départ |
| 12.4 Foliotation | ABSENT | ordre tapé manuel, pas de numéros auto |
| 12.5 Infos œuvre | ABSENT | rien |
| 12.6 Tri noms de fichiers | ABSENT | ordre `project.json` manuel |
| 12.7 Partage équipe | PARTIEL | WebDAV push/pull = partage de fichiers, pas collaboratif temps réel |
| 12.8 Groupe de travail | EXCLU | plateforme (§8) |
| 12.9 Multi-export / lots | PARTIEL | webtoon concat + tranches (2 emu) ; **manque** : export par lots des compositions (+ 1.29 absent) |
| 12.10 Impression multi-pages | EXCLU | impression (§8) |
| 12.11 Fanzine | ABSENT | pas de repères coupe/reliure |
| 12.12 Webtoon | FAIT | `WebtoonPanel` export + tranches + ordre ; emu `webtoonConcatOrder`, `webtoonSliceHeight` |
| 12.13 Rogner/étendre webtoon | FAIT | tranches hauteur paramétrable (même emu) |
| 12.14 EPUB | ABSENT | rien |
| 12.15 PDF import/export | PARTIEL | import page 1 via `PdfRenderer` (emu `pdfImportFirstPage`) ; **manque** : export PDF, multi-pages |

## 13. Animation (14)

| # | Statut | Preuve / écart |
|---|---|---|
| 13.1 Caméra animée | ABSENT | pas de keyframes caméra (holds seuls) |
| 13.2 Images clés | ABSENT | pas d'interpolation (cels + holds) |
| 13.3 Courbes d'animation | ABSENT | rien (`CurveEditor` = courbe de pression, pas d'anim) |
| 13.4 Table lumineuse | FAIT | onion avant/après + force (emu `onionTintTouchesNoPixels`, `onionCountReal`) |
| 13.5 Chronologie (créer/éditer) | FAIT | cels add/dup/delete/set (emu `framesAddCountThumbnailDelete`, `frameSwitchIsolatesStrokes`) |
| 13.6 Pelure d'oignon | FAIT | cf 13.4 |
| 13.7 Pistes + étiquettes | PARTIEL | 1 piste cels + holds + piste audio ; **manque** : multi-pistes, étiquettes |
| 13.8 Modèles d'illustrations | ABSENT | rien |
| 13.9 Modèles d'animation | ABSENT | rien |
| 13.10 Exports (séquences/GIF/APNG/films) | PARTIEL | WebM (emu `webmExportProducesPlayableFile`) + GIF (`gifLoopAndDither`, `celHoldPlayback`) FAIT ; **manque** : séquences d'images, APNG (différé V+2-bis), stickers |
| 13.11 Audio import/export | PARTIEL | `ClipAudio` attach/play (emu `audioAttachPlayPause`) + mux WebM ; **manque** : export audio seul |
| 13.12 Cellulos / feuille expo | ABSENT | rien |
| 13.13 OpenToonz | EXCLU | tiers + plateforme (§8) |
| 13.14 Épuration audio | PARTIEL | scrub navigation tactile (emu `scrubChangesCurrentFrame`) ; **manque** : scrub audio proprement dit |

## 14. Divers (~23)

| # | Statut | Preuve / écart |
|---|---|---|
| 14.1 Fichiers appli tierce | ABSENT | pas d'import ibisPaint (`.clip` lecture seule couverte : 4 emu `clipImport*`) |
| 14.2 Scanner | ABSENT | plateforme ; rien |
| 14.3 Caméra | ABSENT | rien (pas de capture photo in-app) |
| 14.4 Impression | EXCLU | pas d'impression (§8) |
| 14.5 Plateforme de matériaux | EXCLU | cloud éditeur (§8) ; alternative : WebDAV auto-hébergé FAIT |
| 14.6 Macros | ABSENT | cf 1.25 |
| 14.7 Compagnon | ABSENT | rien |
| 14.8 Raccourcis sans fil v2 | EXCLU | périphérique proprio + Bluetooth/OS (§8) |
| 14.9 Raccourcis v1 | EXCLU | idem ; alternative : clavier physique V+3 (emu `shortcutUndoRedo`, `shortcutToolSwitch`) |
| 14.10 Contrôleur physique | ABSENT | plateforme ; rien |
| 14.11 Capteur de pose | EXCLU | externe + plateforme (§8) |
| 14.12 Tactile | FAIT | 2-tap/3-tap, pinch/pan/rotate, edge-swipe, stylus-first ; emu gestes slice 1 |
| 14.13 Modèles calques/cadrage | PARTIEL | `NewCanvasDialog` (dims + fond) ; **manque** : modèles de cadrage |
| 14.14 Réglages via compte | EXCLU | compte en ligne (§8) ; alternative : workspaces JSON locaux FAIT |
| 14.15 Pression du stylet | PARTIEL | courbe de pression éditable (emu `curveChangesRendering`) ; **manque** : calibrage/ajustement OS |
| 14.16 Molette externe | ABSENT | Windows-only ; rien |
| 14.17 Sidecar | ABSENT | macOS-only ; rien |
| 14.18 Récupération canvas | FAIT | autosave opt-in local (emu `autosaveWritesOnTick`, `autosaveSkipsMidStroke`) ; pas de cloud |
| 14.19 Feuille expo externe | EXCLU | Windows+iPad (§8) |
| 14.20 Timelapse | ABSENT | pas d'enregistrement vidéo du processus |
| 14.21 Profils colorimétriques | PARTIEL | épreuvage naïf + gamut (V+3) ; **manque** : réglages de profil (pas de blob ICC sans licence) |
| 14.22 Sauvegarde à chaque opération | FAIT | autosave périodique opt-in 1/5/15 (même emu) |
| 14.23 Journal via compte | EXCLU | telemetry/compte (§8) |

## Synthèse chiffrée

| Section | FAIT | PARTIEL | ABSENT | EXCLU |
|---|---|---|---|---|
| 1 Panneaux (30) | 14 | 6 | 9 | 1 |
| 2 Outils (33) | 8 | 13 | 12 | 0 |
| 3 Calques (13) | 4 | 3 | 6 | 0 |
| 4 Brosses (5) | 0 | 2 | 3 | 0 |
| 5 Affichage (13) | 6 | 5 | 2 | 0 |
| 6 Édition (21) | 3 | 6 | 10 | 2 |
| 7 Props calque (21) | 6 | 3 | 12 | 0 |
| 8 Vectoriel (8) | 2 | 5 | 1 | 0 |
| 9 Filtres (25) | 7 | 6 | 12 | 0 |
| 10 Texte (11) | 2 | 3 | 6 | 0 |
| 11 3D/aide (28) | 0 | 14 | 9 | 5 |
| 12 Multi-pages (15) | 3 | 5 | 5 | 2 |
| 13 Animation (14) | 3 | 4 | 6 | 1 |
| 14 Divers (23) | 3 | 3 | 9 | 8 |
| **Total (260)** | **61** | **78** | **102** | **19** |

Taux : **23 % FAIT testé**, 30 % PARTIEL (base d'extension), 39 % ABSENT,
7 % EXCLU. Points forts : calques/masques/écrêtage, vectoriel de base,
animation cel + exports, tons, texte de base, sélection app-side, transfos
sélection, multipage maison, webtoon/GIF/WebM. Zones blanches : remplissage
et dégradés-outils (zéro), brosse double/jitter/`.abr`, verrous calque,
filtres créatifs (9.8/9.9/9.12–9.16), keyframes/caméra, puppet warp,
perspective/skew, texte avancé, 3D productive (peinture/primitives).

> **Note 2026-10-29 :** ces compteurs datent d'avant T1→T5. Plusieurs
> ABSENT/PARTIEL listés ci-dessus sont **depuis livrés et testés**
> (remplissage/dégradé/baguette T1, texte runs + vectoriel avancé T2,
> keyframes/caméra/pistes T3, warp/puppet/filtres créatifs/décor 3D T4,
> verrous/blends/fills/`.grd`/`.abr`/macros/EPUB/PDF T5). Le suivi des
> écarts **restants** est l'issue GitHub #4.

## Ordre d'implémentation proposé — historique (T1→T5 livrées, pas de code)

Coûts : **S** ≤ 1 session · **M** 2–4 · **L** > 4 ou dépendance externe.
Dépendances : moteur / app / JNI-documenté-d'abord (règle projet : tout
symbole neuf passe par `ARCHITECTURE.md` avant le code).

### T1 — Fondations dessin (blosson critique, 6 items)
| Item | Coût | Dépendance |
|---|---|---|
| Remplissage flood-fill (tolérance + antialiasing, sous-masque V+1) | M | moteur + JNI-d'abord |
| Dégradé linéaire/radial + libre (outil, pas filtre) | M | moteur + JNI-d'abord |
| Figures géométriques (rect/ellipse/polygone, stamps undoables) | M | moteur + app |
| Baguette magique (flood-fill contigu, tolérance) | M | moteur + JNI-d'abord |
| Contour/flou de sélection paramétrable + masque rapide | S | app (+ moteur si flou tuilé) |
| Unsharp mask + balance couleurs + seuillage (filtres, complètent 6.8/6.9) | S | moteur |

### T2 — Texte + vectoriel avancé (5 items)
| Item | Coût | Dépendance |
|---|---|---|
| Contour de caractères + multi-polices (runs) + import `.ttf/.otf` libres | M | app |
| Saisie inline + vertical + césure simple | M | app |
| Retracer/ajuster épaisseur + connecter lignes + trajectoires permanentes | M | moteur + JNI-d'abord |
| Pointe/taille/couleur par tracé + bascule sommets | S | moteur + JNI-d'abord |
| Export PSD texte (fiche) + calque phylactère dédié | S | moteur + app |

### T3 — Animation productive (4 items)
| Item | Coût | Dépendance |
|---|---|---|
| Images clés + interpolation + courbes d'animation | L | moteur + JNI-d'abord |
| Mouvements caméra (pan/zoom keyframés) | M | moteur + JNI-d'abord |
| Multi-pistes + étiquettes + feuille d'exposition | M | app (+ moteur) |
| Séquences PNG + stickers APNG (V+2-bis) + export audio seul | M | moteur + app |

### T4 — Transfos + filtres créatifs (5 items)
| Item | Coût | Dépendance |
|---|---|---|
| Perspective/skew/distorsion + puppet warp | L | moteur + JNI-d'abord |
| Pack créatif : lueur, ombre portée, cristallisation, carte normales, rétro, crayon, Perlin | M | moteur |
| Flou d'objectif + distorsion fisheye-filtre + panoramas | M | moteur |
| Peinture sur modèle 3D + primitives + placement décor | L | moteur + app + asset libre |
| Forme intelligente (redressement de tracé) | M | app (+ moteur) |

### T5 — Échanges + pages + productivité (7 items)
| Item | Coût | Dépendance |
|---|---|---|
| Verrous calque + verrou alpha + calque référence + draft | S | moteur + JNI-d'abord |
| Effet de bord + bord aquarelle + calque dégradé/remplissage + foil | M | moteur |
| 8–10 modes de fusion manquants (assombrir/éclaircir, soustraire, vives…) | S | moteur |
| Import `.grd` + `.abr` (fidélité documentée approximative) + accord chromatique | M | moteur + app |
| Foliotation + infos œuvre + vue double + repères de coupe + fanzine | M | app |
| EPUB + export PDF + séquences (avec T3) | M | app (+ moteur) |
| Macros rejouables + aligner/répartir + panneau historique + panneau info | M | app |

## 8. EXCLU (jamais à implémenter, 19 items)

| Refusé | Raison | Alternative livrée / prévue |
|---|---|---|
| 1.28 accès rapide smartphone, 14.8/14.9 tablettes de raccourcis, 14.10 contrôleur physique | périphériques proprios / plateformes | clavier physique V+3 FAIT |
| 6.2 suppression trames serveur, 6.4 colorisation serveur, 11.19/11.20 scanners de pose, 11.28 service de poses, 13.13 OpenToonz | cloud/serveur éditeur, règle « sans telemetry » | `denoise` local (PARTIEL), colorisation locale conditionnelle (poids permissifs requis) |
| 11.7 `.pep`, 12.8 travail de groupe proprio, 14.5 plateforme de matériaux, 14.14/14.23 compte en ligne | auth/billing/assets/boutique Celsys | WebDAV auto-hébergé + tones procéduraux (M10) FAIT |
| 14.4/12.10 impression, 14.2 scanner, 14.3 caméra desktop, 14.16 molette, 14.17 Sidecar, 14.19 feuille expo externe, 14.11 capteur pose, 11.22 mocap | plateforme OS ou hardware externe | — (hors périmètre Android) |
| Écriture formats proprios (`.clip`, `.cmc`), cf ROADMAP §3/§5 | pas de spec officielle, risque corruption + juridique | lecture seule `.clip` (a)+(b) FAIT, ORA natif + PSD interchange, multipage `.ocpages/` FAIT |
*Note : 14.20 timelapse n'est pas un EXCLU — FUTUR app-side bon marché
(MediaCodec M7b réutilisable), classé ABSENT au §14.*
