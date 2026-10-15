# TASKS — suivi d'exécution (todo list)

Chaque tâche n'est « done » que si : code pushé sur master +
**CI verte** (tests moteur + build APK + check JNI) + validation
fonctionnelle décrite. Mettre à jour ce fichier à chaque livrable.

## M1 — Fondations
- [x] Squelette moteur C++ host-testable (tuiles, surface RGBA8, blend, brush, undo, ORA/PNG) — 19/19 tests locaux
- [x] App Compose skeleton (EngineHost JNI, EngineSurface, panneaux UI)
- [x] CI build/test/publish (engine.yml + android.yml, release APK sur tags)
- [ ] **CI verte** : corriger segfault suite de tests sur runner GitHub Actions
- [ ] **CI Android verte** : gradle assembleDebug + check symboles JNI passe sur runner (fix `ToolPalette` import → fait, voir runs suivants)
- [ ] Fonctionnel : APK installable qui dessine (test émulateur CI = gate "conforme")

## M2 — Pinceaux durcis
- [ ] Import presets MyPaint `.myb` réel + UI de presets (galerie)
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
- [ ] Groupes de calques + clipping + masques + alpha lock
- [ ] Calques d'ajustement (brightness/contrast, hue, gradient map)
- [ ] UX panneau calques : réordonner, modes, opacités (remplace le stub actuel)

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
- [ ] CI engine verte sur master
- [ ] CI android verte sur master (assembleDebug symboles check)
- [ ] Instrumented test émulateur (conforme = démarre + dessin + undo)
- [ ] Release pipeline tags v* → APKs sur GitHub Releases
