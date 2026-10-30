# TASKS — suivi d'exécution (todo list)

Chaque tâche n'est « done » que si : code pushé sur master +
**CI verte** (tests moteur + build APK + check JNI) + validation
fonctionnelle décrite. Mettre à jour ce fichier à chaque livrable.

## M1 — Fondations
- [x] Squelette moteur C++ host-testable (tuiles, surface RGBA8, blend, brush, undo, ORA/PNG) — 19/19 tests locaux
- [x] App Compose skeleton (EngineHost JNI, EngineSurface, panneaux UI)
- [x] CI build/test/publish (engine.yml + android.yml, release APK sur tags)
- [x] CI engine verte sur master (run 38717447431 ; UB saveOra corrigée)
- [x] CI Android verte sur master (run 38717535678 ; NDK + script check corrigés, APK réel en artefact)
- [x] Fonctionnel : APK installable qui dessine — GATE ATTEINT (run 38721366608, émulateur KVM : 3/3 tests passés, 0 failed ; stroke modifie le composite, undo restaure). Publish sur tag v* sera prouvé au release M2.
  - [x] Instrumented tests émulateur (3 tests : launch UI + handle/layers + stroke/undo) — app/src/androidTest
  - [x] Job `emulator` CI (reactivecircus android-emulator-runner, API 34 x86_64, master/tags pushes) gate `publish` via `needs: [build, emulator]`

## M2 — Pinceaux durcis
- [x] Engine stroke accumulator+stabilisation+pressure curve (stroke.h/stroke.cpp, 5 tests, 25/25 verts)
- [x] Import presets MyPaint `.myb` réel + UI de presets (galerie) — lane app (run 38725283977 : build+emulator verts, 4/4 tests ; nLoadMyb shim + PresetGallery + 6 presets clean-room)
- [ ] Stabilisation/lissage d'entrée (facteur 1–2 trames), courbe de pression éditable, tilt
- [ ] Qualité : interpolation inter-moves correcte (aujourd'hui chaque move = estampilles isolées), densité d'estampilles

## M3 — Filtres (~10 premiers + framework)
- [ ] Framework filtre (dialog + preview pipeline, plugin PvP en C++)
- [ ] Gaussian blur, sharp + unsharp, mosaic, add noise, chromatic aberration, radial/motion blur, perlin noise layer, gradient map, posterize/levels

## M4 — I/O fichiers
- [ ] ORA round-trip complet (groupes, toutes les fusion/modes, thumb)
- [ ] PSD import (calques/blends/masques) + PSD export basique
- [ ] Export PNG/JPEG avec EXIF, dialog d'export (échelle)

## M5 — Couches complètes
- [x] Moteur groupes/masques (Layer.mask+hasMask/clipped, compositeInto mask×alpha + stencil previous-layer, history mask tiles) — 48/48 tests locaux (6 nouveaux test_layers.cpp)
- [x] Shim+Kotlin 10 symboles M5 + nLayerState snapshot panel (ARCHITECTURE.md §M5, check_jni_symbols PASS)
- [x] LayerPanel réel (visibilité/opacité slider/blend dropdown/rename/add group/move up-down/mask+mask-target/clipped, groupes repliables, ops render-thread) — strings ajoutées
- [x] 2 tests instrumentés M5 (maskTarget stroke hide + undo restore ; clipped stencil + unclip parity) — CI engine verte run 38769787419 ; APK build vert (run 38762936820 build success) ; emulator 10/10 en attente run vert (cancellations par pushes successifs, dernier run 38774585877 build success)
- [x] Calques d'ajustement (brightness/contrast, hue, gradient map) — fait en M8 (non destructifs, composite-time, ORA round-trip ; + saturation)
- [x] UX panneau calques réel : visibilité/opacité/blends/rename/groupes/move/mask/clipped

## M6 — Vectoriel + règles (clos: app 13/13 run 38815184126, engine 55/55 commit a65b39f)
- [x] Couches vectorielles (chemins resamplés 8px, pression→largeur via PointCurve, gomme vectorielle par intersection, undo path-level)
- [x] Règles M6: symétrie X/Y, ellipse (projection), segment/perspective à 1 segment (3-point perspective → M9)

## M7 — Animation (clos: 17/17 émulateur local KVM, CI android verte run 38906341149, engine local 55/55)
- [x] Timeline UI, frames, onion skin, lecture fps réel (frames=full Canvas copies, undo stacks par frame — fix frameSwitchIsolatesStrokes commit 129e660, onion prev/next, TimelineStrip 96px + fps slider)
- [x] Piste audio + export séquence/webm (M7b clos : ClipAudio MediaPlayer + ExportWebm MediaCodec VP8/MediaMuxer app-side, SAF audio→cache ; 19/19 local KVM, CI android run 38983403837)

## M8 — IA (clos: 20/20 émulateur local, engine 66/66, CI android 38987098898 + engine 38987098900, commit d9c11b7)
- [x] Super-résolution ×3 via ONNX Runtime (onnxruntime-android 1.31.0 MIT, modèle ESPCN 224 fixe Apache-2.0 bundlé ; luma SR + chroma bicubique, retour PNG→nLoadDocument, bouton IA ×3 dans la timeline) + calques d'ajustement (brightness/contrast/hue/saturation/gradientMap, engine composite-time, ORA attrs)
- [x] Débruitage : DIFFÉRÉ (aucun modèle mobile à licence permissive confirmée) ; auto-colorize : DIFFÉRÉ (pas de poids ouverts) — notes honnêtes dans ARCHITECTURE.md, revoir en M8b

## M9 — Référence 3D (clos: 21/21 émulateur local, engine 71/71, CI android 38989149455 + engine 38989149351, commit 406ab88)
- [x] Visionneuse glTF (cgltf MIT vendored, .glb wireframe projeté caméra yaw/pitch/roll/dist/fov + fisheye, overlay au-dessus du frame dans nCompositeInto seulement, panneau référence import SAF + sliders)
- [x] Outils distorsion/fisheye (lentille fisheye k=fisheye*0.001 sur la caméra de référence) + perspective 3 points (nSetRulers jusqu'à 3 points, snap au segment le plus proche)

## M10 — Écosystème sans telemetry (clos: 23/23 émulateur local, engine 75/75, CI android 39134827315 + engine 39134827313, commit c5f1d3d)
- [x] Sync auto-hébergée (WebDAV/Forgejo) plugin (WebdavClient HTTP basic auth push/pull, dialog export/import SAF, cleartext limité à 127.0.0.1/localhost via network_security_config)
- [x] Matériaux libres : packaging + navigateur (tones procéduraux clean-room : hatch/crosshatch/dots/grid/checker/stripes via nApplyTone, remplissage de calque undoable comme un filtre)

## CI/CD — pérenne
- [x] CI engine verte sur master
- [x] CI android verte sur master (assembleDebug + symboles check réels)
- [ ] Instrumented test émulateur CI : build+connectés verts exigés sur master (gate `publish`)
- [x] Release pipeline tags v* → APKs sur GitHub Releases (v0.1.0-alpha publié le 2026-10-18 : asset `openclip-v*.apk` debug-signed + `.sha256`, prerelease auto sur tags à tiret, téléchargement anonyme vérifié)

## V+1 — Fondations (sélection, texte, gomme alpha) — CLOS (engine 80/80, emu 87/87, preuve `/tmp/opencode/openclip-v1.png`)
- [x] Sélection rect/ellipse/lasso app-side (masque session-only, marching ants overlay, menu Tout/Inverser/+1px/Désélectionner, mix sous masque via duplicate→load→delete) — spec `docs/v1-selection-texte-gomme.md` §1
- [x] Calque texte éditable (layout Canvas système, import pixels, fiche `x-openclip-*` ORA, `nSetLayerText`+`nBlitPngIntoLayer` documentés `ARCHITECTURE.md` d'abord) — spec §2
- [x] Vraie gomme alpha `alpha-erase` dest-out moteur (alias legacy `eraser` conservé, migration prefs sans migration pixels, bandeau supprimé) — spec §3
- [x] Gates : engine 80/80, émulateur 87 (86 spec + 1 capture), preuve `/tmp/opencode/openclip-v1.png` relue

---
## Journal de progression
- 2026-10-15: M1 clos — CI engine + android vertes; gate émulateur (run 38721366608, 3/3).
- M2 lancé (2 lanes) : engine=stroke accumulator+stabilisation+pressure curve ; app=galerie MyPaint presets (SAF)+contrôles stabilize/curve+undo/redo état.
- 2026-10-15 (2): M2 clos — moteur stroke accumulator (stabilize/curve) 25/25; app galerie presets+nLoadMyb 4/4 émulateur.
- 2026-10-15 (3): M3 clos — 10 filtres + dialog + queue undoable, 36/36 engine, 6/6 emulator (run 38734875717).
- 2026-10-15 (4): M4 clos — 42/42 engine, 8/8 emulator; fix direct de 3 poisons (stb-in-function, loadPsd link, cancel string, apostrophe FR).
- 2026-10-15 (5): M5 clos — 10/10 emulator (run 38794030171); clipping semantique verrouillée par sonde locale (dbg6: stencil sparse OK). Leçon: 2 poisons CI (JDK pin repo, apostrophe FR) corrigés: prepush.sh gate = fidèle à la CI (42/42 + Kotlin + NDK locaux).
- 2026-10-15 (6): M6 clos — vector layers + rulers, 13/13 emulator (run 38815184126); engine 55/55 (a65b39f). CI emulator-resources fix (bound RAM + swiftshader_indirect) a supprimé les crashes system (run 38813808271 vert).
- 2026-10-16: M6 clos — vector layers + rulers, 13/13 emulator (run 38815184126). Fix CI stabilité émulateur (RAM bornée + swiftshader_indirect, run 38813808271 vert).
- 2026-10-16 (2): Emulateur = gate LOCAL desormais (`scripts/emutest.sh`, suite instrumentee) ; job emulateur retire de GitHub CI (trop lent: timeout 1h). CI GitHub = engine tests + build APK debug/androidTest + JNI symbols, avec concurrency auto-cancel. M7: run local en cours de validation (17 tests).
- 2026-10-16 (3): M7 clos — undo per-frame (commit 129e660), 17/17 emulateur local KVM (boot 117s, suite 42s) ; CI android verte run 38906341149 ; engine 55/55 local via prepush. Reste M7b (audio + webm).
- 2026-10-17: M8 clos — ajustements (engine, ORA round-trip) + AI x3 (ONNX Runtime, modèle ESPCN 224 fixe) ; 20/20 local KVM + CI android/engine verts.
- 2026-10-17 (2): M7b clos — ClipAudio + ExportWebm (MediaCodec VP8/MediaMuxer, app-side) ; 19/19 local, CI android 38983403837.
- 2026-10-17 (3): M9 clos — glTF cgltf vendored + fisheye + perspective 3 points ; 21/21 local, engine 71/71, CI 38989149455/38989149351 (commit 406ab88).
- 2026-10-18: M10 clos (DERNIER JALON) — tones procéduraux clean-room (nApplyTone) + sync WebDAV app-side ; 23/23 local, engine 75/75, CI android 39134827315 + engine 39134827313 (commit c5f1d3d). App OpenClip M1→M10 complète.
- 2026-10-20: .clip palier (a) clos (import lecture raster, app-side sans JNI) — DocClip.kt (enveloppe CSFCHUNK + SQLite plateforme lecture seule + tuiles zlib RGBA8 → ORA multi-couches via DocOra.writeLayersOra, fallback CanvasPreview honnête, refus explicite des hostiles) + câblage Fichier > Import (.clip → ORA → nLoadDocument existant, strings clip_failed/clip_preview_only EN/FR) + 2 tests instrumentés (fixtures générées in-code, zéro binaire Celsys) ; engine 75/75 + emutest local OK + preuve /tmp/opencode/openclip-clip-a.png. Palier (b) dégradé et (c) écriture : non faits (recherche §5 : b dégradé explicite, c refusée).
- 2026-10-18 (2): première release ALPHA v0.1.0-alpha publiée (prerelease GitHub) — APK debug-signed arm64+x86_64 84 MB + sha256, téléchargement anonyme vérifié (run tag 39150123145) ; workflow publish durci (action-gh-release v3, asset versionné, prerelease sur tags à tiret). Denoise/auto-colorize différés faute de poids à licence claire.
- 2026-10-20 (2): .clip palier (b) clos (import dégradé vectoriel/texte, app-side sans JNI) — DocClip étendu (tables VectorObjectList/texte tolérantes, géométrie validée bornée, rasterisation maison Canvas/Paint, compteur d’approximations par calque + bandeau « dégradé : N » EN/FR, couches non interprétables sautées avec mention) + 2 tests instrumentés (fixture in-code 1 vecteur + 1 texte, composite + compteur > 0, hostile refusé) ; engine 75/75 + emutest local OK (75 tests) + preuve /tmp/opencode/openclip-clip-b.png. Palier (c) écriture : refusée (recherche §5).
- 2026-10-20 (3): .clip palier (c) écriture DÉFINITIVEMENT ÉCARTÉE (décision) — aucune lib sérieuse ne l'implémente, risque de corruption silencieuse + surface juridique inutile ; ORA/PSD restent les écritures interop. Compat .clip = lecture seule (a)+(b). Chantier UX/.clip clos : slices 0→7 + (a)+(b) verts, validation manuelle tablette + tag alpha.3 restants côté demandeur.
- 2026-10-20 (4): V+1 code COMPLET non validé émulateur (infra hôte bloquante, voir § BLOQUANT plus bas) — sélection rect/ellipse/lasso app-side + marching ants + menu 4 actions + mix sous masque, texte (StaticLayout→PNG→nBlitPngIntoLayer, fiche x-openclip-* ORA, nSetLayerText), gomme alpha-erase dest-out moteur (legacy eraser conservé, paintMaskDab descendu moteur byte-identique). prepush OK (engine 80/80, JNI, Kotlin, NDK). Suite 87 tests écrite (75+12) : 1er run partiel 87 lancés / 76 passés / 11 échecs tous V+1 (10× openFreshDoc timeout 5 s trop court sous swiftshader froid + 1× assertion outdated mode:eraser). Fixes appliquées (poll 25 s type awaitDocReady, assertion mode:alpha-erase, bandeau supprimé du test) + APKs rebuildés à jour. BLOQUANT : l'émulateur ne cold-boote plus (segfault QEMU systématique, KVM prouvé sain via micro-test KVM_RUN/HLT, AVD recréée, TCG+KVM+2 AVDs+2 modes GPU échouent pareil ; snapshots détruits par mon wipe — à signaler). Verdict OK (87) + screenshot /tmp/opencode/openclip-v1.png : EN ATTENTE d'un émulateur fonctionnel (relancer scripts/emutest.sh --keep).
- 2026-10-20 (5): V+1 CLOS — OK (87 tests) émulateur local, engine 80/80, prepush vert, preuve /tmp/opencode/openclip-v1.png relue (fourmis + « Aa » + ligne d'encre interrompue par le sillon d'effacement dest-out). Causes racines des 11 échecs initiaux : (1) helpers traversal fragiles (findActiveEngineSurface → surface pausée ; accès direct es + gate d'identité étatique) — pas de régression prod (boot-doc OK, dump hiérarchie sain, verts en isolé) ; (2) strokes de test dans la bande edge 32dp (= edge-swipe, pas d'encre — limite slice-1 pré-existante) → géométrie hors-band, gate sans pont d'interpolation (voulu) ; (3) fiche texte survivant à l'undo pixels (design moteur : History = tuiles, pas champs Layer) ; (4) assertion outdated mode:eraser+bannière (contrat V+1). Infra : cold-boot segfault systématique contourné en recréant l'AVD (état snapshot corrompu par wipe ; KVM hôte prouvé sain via micro-test KVM_RUN/HLT) ; load 30 (MCPs + workers orphelins d'autres sessions, zombies next-server non touchés) — suite verte après stabilisation.

## V+2 — BD/manga + exports longs (spec docs/v2-bd-export.md, 16 items)
- [x] Moteur : blends hard-light/difference/color/luminosity (HSL maison), courbes `curves` (LUT 256, par canal, ORA `adjust-curve`), transfo `transform.h` (move/free/mesh, bilinéaire), guides `guides.h` (snap 2px), GIF `gif.h` (median-cut 256 + LZW from-scratch, validé PIL), PSD multicouche (records + table 6 + base `Merged approximations` + compteur), cels `cels.h` (holds 1..32, moveCursor, onionRange, celDelayCs), méta générique `Layer.meta` + `adjustCurve` (ORA round-trip)
- [x] Shim : 8 JNI (`nMoveCel` undo LIFO exact, `nSetCelHold`, `nSetOnionCount`, `nSetSelectionMask` luma, `nSelectionBlit`, `nExportGif`, `nSetLayerMeta` validée, `nPsdApproxCount`) + 1 getter `nVectorPaths` (décision §3.3/§4.3, documentée) ; `ARCHITECTURE.md` étendu AVANT code (7 extensions + getter)
- [x] App : onglet BD (cases rect/poly + fond blanc + tons/densité Bayer + bulles ellipse/rounded + queue + vitesse LCG + lecture LTR/RTL), transfo (arm/apply/cancel), copier/coller/couper (presse-papier session, refus mismatch), Bézier (lire/insérer/supprimer/densifier Catmull-Rom), guides (V/H/C, persistance, merge symétrie), GIF (loop/maxW/dither/fps), webtoon (concat ordre + tranches), SVG (1 path/trait), multipage `.ocpages/` (insert/delete/reorder/garde 0-pages, thumbs), navigateur (tap-seek), autosave opt-in (1/5/15 min, jamais mid-stroke), courbes (4 pts × canal), holds/reorder/onion UI timeline, blends+curves LayerPanel, statut approx PSD
- [x] Gates : engine 103/103 (80+23), prepush OK (JNI/Kotlin/NDK), émulateur 125 (87+38) — voir ligne du jour ; preuve `/tmp/opencode/openclip-v2.png` relue
- [x] Makefile : suivi de dépendances headers (`-MMD -MP`) — plus d'objets périmés après édition d'un header
- [ ] Écarts assumés (rapport) : replace Bézier = 1 undo-vecteur vide le calque (re-commit restaure) ; lecture = JSON app-side (pas d'attr ORA racine, 0 JNI) ; numérotation à la création (pas de tap-ordre) ; aperçu mesh = cadre ; nSetOnionCount écrase via wrapper ordonné (setOnion d'abord)

- 2026-10-21 (1): V+2 CLOSE — OK (125 tests) émulateur local (87+38), engine 103/103, prepush vert, preuve /tmp/opencode/openclip-v2.png relue (onglet Comic : GIF Once/Loop/N + maxWidth + dither, Webtoon gutter/tranches, toggle visible). Run 1 : 16 échecs (resample ~8px, knownBlendName, record move pré-move, GCE offset, cut=transparent, deleteAt ×2, scroll imbriqué, dock ouvert en cascade, onion sur frame opaque, ajustement sur calque seul, resample Bézier, pli + ANR). Run 2 : 8 (2 déjà fixés + 6 cascade dock). Cause racine unique : 3e chip → header 290dp déborde → toggle hors-champ → clics silencieux → dock bloqué ouvert (diagnostiqué screenshot + V2DIAG dockOpen). Fix : chips en Row scrollable, toggle épinglé + scroll-to avant clics + finally-safe. Run 3 : 124/125 (layerDragReorderMovesOnce, flake gesture sous charge, vert en isolé). Run 4 : OK (125 tests).
- 2026-10-20 (6): V+2 implémentée (16 items, tranches §6 respectées 1→6). Causes racines des échecs du run 1 (16) : resample moteur ~8px vs comptes exacts (asserts directionnels), `knownBlendName` sans les 4 modes, record move pré-move au lieu de post-move (cur suit la cel), GCE parsé au mauvais offset, cut = transparent pas blanc, deleteAt appelé 2×, scroll vertical imbriqué (crash composition), dock laissé ouvert (contamination UI en cascade), onion sur frame opaque, ajustement sur calque de base seul, resample Bézier vs "undo restaure", tag-2 sous le pli (performScrollTo). Run 2 : 8 échecs (2 déjà fixés en source + 6 cascade dock à confirmer).
- 2026-10-22 (2): T2 texte + vectoriel avancé CLOS phone (spec docs/t2-text-vector.md, ordre §4→§3→§1§2→§5) — moteur §4 (style par tracé tip/size/couleur/sommets + sibling ORA data/vectorNNN.json cap 256Ko + vectorStored) + §3 (nReplaceVectorPath/nConnectVectorPaths/nSetVectorWidths) + §1 (fiche runs/outline/fontfile, REPRO repli sans embarquement) + §2 (dir/hyphen, layout maison, césure FR/EN) + §5 (TySh subset PSD + nSetLayerBubble composite-time) ; 4 symboles JNI documentés §T2 AVANT code ; engine 170/170 (22 tests), prepush vert ; app (maison layout, FontRegistry+SAF, inline ancré, outils vecteur+trajectoires, bulle dédiée, SVG couleurs, PSD ON/OFF, 38 strings FR/EN) + 19 tests emu. Gate : OK (202 tests) phone KVM (2 runs INCONCLUSIFS initiaux = segfault QEMU hôte sous swap 7Go/8Go ; fix : daemons Gradle stoppés ; 4 échecs au run vert : vertical translate, scroll imbriqué ×2, compat méta-bulle — tous fixés, repli méta legacy conservé) ; preuve /tmp/opencode/openclip-t2.png relue (éditeur inline ancré + Cancel/Settings/Set) ; gate tablette OK (202 tests, AVD openclip-tablet 1920x1200). (spec docs/t2-text-vector.md, ordre §4→§3→§1§2→§5) — moteur §4 (style par tracé tip/size/couleur/sommets + sibling ORA data/vectorNNN.json cap 256Ko + vectorStored) + §3 (nReplaceVectorPath/nConnectVectorPaths/nSetVectorWidths) + §1 (fiche runs/outline/fontfile, REPRO repli sans embarquement) + §2 (dir/hyphen, layout maison, césure FR/EN) + §5 (TySh subset PSD + nSetLayerBubble composite-time) ; 4 symboles JNI documentés §T2 AVANT code ; engine 170/170 (22 tests), prepush vert ; app (maison layout, FontRegistry+SAF, inline ancré, outils vecteur+trajectoires, bulle dédiée, SVG couleurs, PSD ON/OFF, 38 strings FR/EN) + 19 tests emu (183 au total). Gate emu : 2 runs INCONCLUSIFS (segfault QEMU hôte, swap 7Go/8Go — daemons Gradle stoppés, relance en cours).
- 2026-10-22: T1 fondations dessin CLOSE par adoption (travail orphelin vérifié + 1 fix compile) — seau/baguette (fill.h scanline + nFloodFill/nMagicWand), dégradé (gradient.h + nPaintGradient), figures (shapes.h + nStampShape), feather/masque rapide app-side, unsharp/balance/seuillage (registre nApplyFilter) ; 4 symboles JNI documentés en ARCHITECTURE.md §T1 AVANT code ; engine 148/148, prepush vert, emu phone OK (181 tests, run détaché), preuve /tmp/opencode/openclip-t1.png relue. Gate 183/183 final + tablette reportés (box partagée saturée).
- 2026-10-23: T3 animation productive CLOSE — keyframes {dx,dy,scale,angle,opacity} (anim.h + nSetLayerAnim/nLayerAnim), caméra sortie keyframée, 4 pistes (labels/feuille + pistes parallèles, sidecar anim.json v1), APNG from-scratch + séquence PNG ZIP + stickers + audio seul (MP4 exclu) ; bug produit trouvé et fixé (nAddTrack blanches opaques → pistes neuves transparentes, ARCHITECTURE.md §T3) ; engine 193/193, prepush vert, emu OK (218 tests) phone, preuve /tmp/opencode/openclip-t3.png relue (dialogue Tracks + rangée Keys/Camera/Tracks/Sheet).
- 2026-10-23: T5 échanges + pages + productivité CLOS (DERNIÈRE tranche, spec docs/t5-exchange.md, ordre §3→§1→§2→§4→§7→§5→§6) — §3 10 blends (darken/lighten/linear-burn/linear-dodge jumeau documenté de add/linear-light/vivid-light/pin-light/subtract/divide/exclusion, neutres verrouillés, PSD dark/lite/lbrn/lddg/vLit/lLit/pLit/fsub/fdiv + exclusion→norm) + §1 verrous (nSetLayerFlags, garde centrale shim, alphaLock noyaux dab/flood/gradient/shape, refus blit+dropShadow/edgeBorder-outside sur alphaLock, R/D exclus des exports via compositeCell forExport, ORA x-openclip-locked/alphalock/reference/draft, PSD fusionnés+comptés) + §2 fills (fill_layer.h solid/dégradé/foil Perlin graine fixe, composite-time, nSetLayerFill + bake via blit-path, ORA x-openclip-fill*, PSD base+compté) + filtres edgeBorder (chamfer-34 + AA 1px, outside étend l'alpha par design) et watercolor (Perlin T4 graine fixe, tout-zéro = identique) + §4 parseurs grd/abr maison (sous-ensembles documentés, refus + message, fixtures in-code) + accord Reinhard→3 fiches curves + gradients.json + §7 macros (journal whitelist + macros.json v1 + rejouer avec garde, tracés exclus annoncés) + aligner (masque-plein + nSelectionBlit, 1 pas/calque) + historique session + info 1Hz + §5 project.json v2 tolérant v1 (work/folio/crop/startPage), folio à l'export (romain inclus), vue double lecture seule, repères mm→dpi + opt-in, fanzine imposé multiple-de-4 + §6 EPUB (mimetype STORED premier verrouillé par test, OPF spine+rtl, XHTML strict) + PDF PdfDocument + séquences multipage pNN_frame_MMMM + stickers ≤512px ; EXACTEMENT 2 symboles JNI (nSetLayerFlags, nSetLayerFill) documentés en ARCHITECTURE.md §T5 AVANT code, générique nT5Op refusé ; engine 237/237, prepush vert, emu phone OK (248 tests) + tablette 247/248 (clipMaskBadges flake connu, vert en isolé), preuve /tmp/opencode/openclip-t5.png relue (menu Exchange + toggles Lock/Alpha lock/Reference/Draft + Hide drafts). Leçon : rangées +48dp → scroll externe layer-list requis dans 4 tests UI (clics silencieux sous la barre nav sinon) ; seed lum noir == rect noir (alphaLock) ; préfixe frame_ + _%04d = double underscore.
- 2026-10-23: T4 transfos + filtres créatifs CLOS (spec docs/t4-transform.md, ordre §2→§3→§1→§5→§4) — registre +10 filtres (glow, dropShadow [alpha composite derrière par design], crystallize hex, normalMap, retro, pencil, perlinClouds ; lensBlur disque, fisheyeFilter, panoramaRemap cyl/rect round-trip ±2LSB) + warp sélection (warp.h persp/skew/bulge, croissance JSON nSelectionBlit, coutures verrouillées) + marionnette (puppet.h MLS rigide, calque entier) + forme intelligente app-side (SmartShape.kt RDP/moments, fantôme + valider 1 undo / garder rejoué identique, toggle PinnedBrushBar défaut OFF) + décor 3D (refscene.h : primitives procédurales, peinture texture 1024² picking écran, overlay teinté sous wireframe, commit wire:0/flat:1 via pont flatten, persistance decor.json par replay de dabs) ; EXACTEMENT 3 symboles JNI (nPuppetWarp, nSetRefScene, nPaintRefMap) documentés en ARCHITECTURE.md §T4 AVANT code, générique nT4Op refusé ; engine 217/217 (30 tests), prepush vert, emu phone OK (226 tests) + tablette OK (227 avec test capture), preuve /tmp/opencode/openclip-t4.png relue (panneau Warp : Perspective/Tilt/Distort/Puppet + garde sans sélection). Reruns verts après 5 échecs initiaux (resample acc=target, scanner quad imbriqué, buildPrimitive qui wipait visible, double conversion coords, quad neutre depuis bbox mesurée) + 1 flake clipMaskBadges (vert en isolé).

## V+3 — Confort pro (clos, gate absorbé par le run T1 : engine 148/148, emu 181)
- [x] Mélangeur couleurs sur canvas (mode `mix` moteur, rayon de mélange, preuve emu `mixerSmearsStroke`) — spec `docs/v3-confort.md`
- [x] Épreuvage / profils couleur (pipeline sortie seul, aucun blob ICC tiers) + roue harmonies
- [x] Canaux de sélection nommés (persistance maison) + accrochage / simplification des tracés
- [x] Texte sur tracé (via les ponts texte + vecteur existants) + mannequin articulé posable (glTF libre, pose)
- [x] TIFF import, PDF première page, SVG `<path>` subset, débruitage local classique
- [x] Gate : suite complète au run T1 (engine 148/148, emu 181), commit b87a4b7

## T1 — Fondations dessin (clos : engine 148/148, emu 181 run détaché ; gate 183 couvert par T2, commit c1b059e)
- [x] Seau scanline (tolérance + AA sous masque) + baguette magique — `fill.h`, JNI `nFloodFill` / `nMagicWand`
- [x] Dégradé linéaire/radial/libre — `gradient.h`, JNI `nPaintGradient`
- [x] Figures géométriques undoables (rect/ellipse/polygone) — `shapes.h`, JNI `nStampShape`
- [x] Feather / masque rapide app-side + unsharp / balance couleurs / seuillage (`nApplyFilter`)
- [x] 4 symboles JNI documentés `ARCHITECTURE.md` §T1 AVANT code ; preuve `/tmp/opencode/openclip-t1.png`

## T2 — Texte + vectoriel avancé (clos : engine 170/170, emu 202/202 phone, commit ac4fbe3)
- [x] Runs multi-polices + import fontes libres (SAF), contour, fiche `x-openclip-*` (sans embarquement de fonte)
- [x] Saisie inline ancrée, vertical, césure FR/EN, layout maison
- [x] Retrace / connexion / largeur par tracé + trajectoires permanentes (sibling ORA `data/vectorNNN.json`, cap 256 Ko)
- [x] Export PSD `TySh` subset + calque bulle dédié (composite-time)
- [x] 4 symboles JNI documentés §T2 AVANT code ; gate tablette OK (202) ; preuve `/tmp/opencode/openclip-t2.png`

## T3 — Animation productive (clos : engine 193/193, emu 218/218, commit 232854e)
- [x] Keyframes calque {dx,dy,scale,angle,opacity} + caméra de sortie keyframée — `anim.h`, JNI `nSetLayerAnim` / `nLayerAnim`
- [x] 4 pistes parallèles + étiquettes + feuille d'exposition (sidecar `anim.json` v1)
- [x] APNG from-scratch + séquence PNG ZIP + stickers ≤ 512 px + audio seul (MP4 exclu)
- [x] Fix produit : `nAddTrack` créait des pistes blanches opaques → pistes neuves transparentes (documenté §T3)
- [x] 15 symboles JNI documentés `ARCHITECTURE.md` §T3 AVANT code (2 keys + 3 caméra + 8 pistes + 2 exports), générique `nAnimOp` refusé
- [x] Preuve `/tmp/opencode/openclip-t3.png` (dialogue Tracks + rangée Keys/Camera/Tracks/Sheet)

## T4 — Transfos + filtres créatifs (clos : engine 217/217, emu 226 phone / 227 tablette, commit ea19d60)
- [x] Warp sélection (perspective / skew / bulge) — `warp.h`, croissance JSON via `nSelectionBlit`, coutures verrouillées
- [x] Marionnette MLS rigide sur calque entier — `puppet.h`, JNI `nPuppetWarp`
- [x] Forme intelligente app-side (RDP + moments, fantôme, 1 undo au valider) — `SmartShape.kt`
- [x] +10 filtres (glow, drop shadow, crystallize, normal map, retro, pencil, Perlin clouds, lens blur, fisheye, panorama remap)
- [x] Décor 3D : primitives procédurales + peinture texture 1024² — `refscene.h`, JNI `nSetRefScene` / `nPaintRefMap`, persistance `decor.json`
- [x] 3 symboles JNI documentés §T4 AVANT code ; preuve `/tmp/opencode/openclip-t4.png`

## T5 — Échanges + pages + productivité (clos : engine 237/237, emu 248/248 phone, commit f667b39)
- [x] +10 modes de fusion (20 au total, neutres verrouillés, mapping PSD documenté)
- [x] Verrous/flags calque (lock, alpha lock, reference, draft ; R/D exclus des exports) — `nSetLayerFlags`
- [x] Fills composite-time (solid / dégradé / foil) + edge border + aquarelle — `fill_layer.h`, `nSetLayerFill`
- [x] Parseurs `.grd` / `.abr` maison (sous-ensembles documentés, refus + message, fixtures in-code) + accord Reinhard → curves
- [x] Macros rejouables (whitelist + `macros.json` v1), aligner, historique session, panneau info 1 Hz
- [x] Pages : `project.json` v2 tolérant v1 (work/folio/crop/startPage), folio à l'export, vue double lecture seule, repères mm, fanzine multiple de 4
- [x] Exports : EPUB (mimetype STORED premier verrouillé par test), PDF, séquences multipage `pNN_frame_MMMM`, stickers ≤ 512 px
- [x] Exactement 2 symboles JNI documentés §T5 AVANT code ; tablette 247/248 (`clipMaskBadges` flake connu, vert en isolé) ; preuve `/tmp/opencode/openclip-t5.png`

## REBRAND — Calque (clos, commit 932c883)
- [x] Nom launcher EN+FR **Calque**, icône adaptative trois feuillets + monochrome, fond encre `#141419`
- [x] Accent cinabre `#E0573A` réservé au CTA premier (Export, Import/Export SVG) et aux pastilles dirty/i-N
- [x] Fonte display Space Grotesk (OFL, licence dans `app/src/main/assets/fonts/`) ; exports `calque-<ts>.*`
- [x] Package `dev.openclip`, clés `x-openclip-*`, repo et `Theme.OpenClip` **gelés** — install `-r` par-dessus l'alpha, données/SAF conservés
- [x] Spec `docs/rebrand.md` (décision, migration, captures avant/après hors git)

## VISUEL — Passe V1→V18 (close, commit 25ff4ab, audit `docs/audit-visuel.md`)
- [x] V1–V4 lisibilité : pastilles de la barre pinceau, `POKE` du footer, nom/save ×3 en mode compact, rangée d'outils coupée
- [x] V5–V9 dock et tiroirs : dock adaptatif (canvas non réduit à une bandelette), ligne calque sans double bordure, wraps cohérents, tête de dock, contenu sous header/rail
- [x] V10–V12 affordances : undo/redo réellement actifs, rail Timeline lisible, overlays/pose sans modèle
- [x] V13–V14 perfs perçues : cache vignettes timeline borné LRU 24 + recycle, `setBrush` throttlé (~33 ms + push final)
- [x] V15 relevé (`status`/`refreshKey`) : aucune régression constatée, mesure de recomposition non instrumentée
- [x] V16 paysage téléphone (chrome replié), V17 contrastes (aucune violation avérée), V18 icône launcher (traité par le rebrand)
- [x] Gate : suite instrumentée associée dans les runs T1→T5 (engine 148 → 237, emu 181 → 248)

## PERF — P7 release (close, commit 25ceab6)
- [x] R8 minification + shrink ressources sur `release` (`app/build.gradle.kts`, `app/proguard-rules.pro`)
- [x] Règles keep JNI (`EngineHost`, natives) + ONNX Runtime ; validation par `check_jni_symbols.sh` + boot émulateur + suite complète
- [x] `check_jni_symbols.sh` : capture `nm` avant `grep` (plus de SIGPIPE sous `pipefail`)
- [ ] Mesures post-R8 (taille APK, `reportFullyDrawn`) à consigner au tag `v0.2.0-alpha`
- [x] P1 (throttle ~33 ms + push final, V14) et P2 (LRU 24 avec `recycle()`, V13) livrés ; P5/P6 refusés (risque > gain) ; P4 (scope `status`/`refreshKey`, V15) non mesuré — cf. `docs/rebrand.md` §7

## Journal — 2026-10-29
- Alpha `0.2.0-alpha` : documentation consolidée (README produit, `CHANGELOG.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, templates GitHub, ROADMAP/TASKS/parity-gap à jour) — refs issue #3. Gates rappelés : engine 237/237, emu 248/248 local.

## Journal — 2026-10-30
- Issue #29 tranche A2 (blends + traversant) : 8 modes moteur (color burn `1-min(1,(1-d)/s)`, color dodge `min(1,d/(1-s))`, add glow `d+2s-sd`, max jumeau documenté de lighten avec alias `lighten-2`, darker/lighter color par lightness HSL, hue/sat sur la base HSL existante), traversant réel (composition directe sur l'accumulateur parent, groupes imbriqués, masque/clip du groupe, enfants isolés bufferisés), ORA `isolation` + préservation exacte d'un composite-op inconnu (`x-openclip-blend`), PSD `idiv`/`div `/`lite`/`dkCl`/`lCCl`/`hue `/`sat `/`colr`/`lum `/`smud` + add-glow→norm compté, UI pastille traversant/isolé + picker localisé FR/EN ; **0 nouveau symbole JNI** (`nLayerState` gagne `"pass"`, `nSetLayerFlags` accepte `passThrough`) ; engine 291/291, prepush vert.
