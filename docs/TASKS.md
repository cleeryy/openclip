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

## V+1 — Fondations (sélection, texte, gomme alpha)
- [ ] Sélection rect/ellipse/lasso app-side (masque session-only, marching ants overlay, menu Tout/Inverser/+1px/Désélectionner, mix sous masque via duplicate→load→delete) — spec `docs/v1-selection-texte-gomme.md` §1
- [ ] Calque texte éditable (layout Canvas système, import pixels, fiche `x-openclip-*` ORA, `nSetLayerText`+`nBlitPngIntoLayer` documentés `ARCHITECTURE.md` d'abord) — spec §2
- [ ] Vraie gomme alpha `alpha-erase` dest-out moteur (alias legacy `eraser` conservé, migration prefs sans migration pixels, bandeau supprimé) — spec §3
- [ ] Gates : engine 80/80, émulateur 87 (86 spec + 1 capture), preuve `/tmp/opencode/openclip-v1.png` relue

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
