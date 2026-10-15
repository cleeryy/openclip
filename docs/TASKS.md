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
- [ ] Calques d'ajustement (brightness/contrast, hue, gradient map) — REPORTE M8 (impact faible/peinture)
- [x] UX panneau calques réel : visibilité/opacité/blends/rename/groupes/move/mask/clipped

## M6 — Vectoriel + règles
- [ ] Couches vectorielles (chemins, pression→largeur, gomme vectorielle)
- [ ] Règles : perspective 1/2/3 pts, symétrie, ellipse

## M7 — Animation
- [ ] Timeline UI, frames, onion skin, lecture fps réel
- [ ] Piste audio + export séquence/webm

## M8 — IA
- [ ] Super-résolution + débruitage via ONNX Runtime (poids libres)
- [ ] Auto-colorize assist (si poids ouverts disponibles)

## M9 — Référence 3D
- [ ] Visionneuse glTF (mannequins libres), panneau référence
- [ ] Outils distorsion/fisheye sur 3D importée

## M10 — Écosystème sans telemetry
- [ ] Sync auto-hébergée (WebDAV/Forgejo) plugin
- [ ] Matériaux libres : packaging + navigateur

## CI/CD — pérenne
- [x] CI engine verte sur master
- [x] CI android verte sur master (assembleDebug + symboles check réels)
- [ ] Instrumented test émulateur CI : build+connectés verts exigés sur master (gate `publish`)
- [ ] Release pipeline tags v* → APKs sur GitHub Releases

---
## Journal de progression
- 2026-10-15: M1 clos — CI engine + android vertes; gate émulateur (run 38721366608, 3/3).
- M2 lancé (2 lanes) : engine=stroke accumulator+stabilisation+pressure curve ; app=galerie MyPaint presets (SAF)+contrôles stabilize/curve+undo/redo état.
- 2026-10-15 (2): M2 clos — moteur stroke accumulator (stabilize/curve) 25/25; app galerie presets+nLoadMyb 4/4 émulateur.
- 2026-10-15 (3): M3 clos — 10 filtres + dialog + queue undoable, 36/36 engine, 6/6 emulator (run 38734875717).
- 2026-10-15 (4): M4 clos — 42/42 engine, 8/8 emulator; fix direct de 3 poisons (stb-in-function, loadPsd link, cancel string, apostrophe FR).
- 2026-10-15 (5): M5 clos — 10/10 emulator (run 38794030171); clipping semantique verrouillée par sonde locale (dbg6: stencil sparse OK). Leçon: 2 poisons CI (JDK pin repo, apostrophe FR) corrigés: prepush.sh gate = fidèle à la CI (42/42 + Kotlin + NDK locaux).
- 2026-10-15 (6): M6 clos — vector layers + rulers, 13/13 emulator (run 38815184126); engine 55/55 (a65b39f). CI emulator-resources fix (bound RAM + swiftshader_indirect) a supprimé les crashes system (run 38813808271 vert).
