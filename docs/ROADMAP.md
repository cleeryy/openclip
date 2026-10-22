# Roadmap — plan de parité (le plus large d'abord, tailles honnêtes)

> Marque : **Calque** (rebrand : nom launcher, icône, accent cinabre,
> fonte display — package `dev.openclip`, moteur et docs inchangés,
> voir `docs/rebrand.md`).

Goal: widest possible feature parity with a professional painting app
(reference scope: pro 2D drawing + animation tool, v5 extended edition —
see csp-analysis.md) via clean-room reimplementation. Each milestone ships
something usable.

> **Status 2026-10-20 : M1→M10 clos et livrés, phase STUDIO (slices 0→7)
> close, compat `.clip` lecture (a)+(b) close, écriture (c) écartée.**
> Ce fichier est la feuille de route tenue à jour (historique compact +
> phases suivantes). Le checklist exécutable avec preuves (tests, commits,
> runs CI) est `docs/TASKS.md` — voir son journal. Le tri fin parité est
> `docs/post-v1-backlog.md` (FAIT / FUTUR / EXCLU). Recherche format :
> `docs/clip-format-research.md`. Recherche UX : `docs/csp-ux-research.md`.
> Règle clean-room partout : vocabulaire maison, jamais de strings, assets,
> captures ni binaires tiers.

## 1. Historique M1→M10 (clos, résumé — détail dans TASKS.md)

| Jalon | Livré | Preuves |
|---|---|---|
| M1 — Fondations | moteur host-testable (tuiles, RGBA8, blends, brush pression, undo stroke, ORA/PNG) + shell Compose + CI | engine 19/19, emu 3/3 |
| M2 — Pinceaux durcis | accumulateur + stabilisation + courbe pression, presets `.myb` + galerie | engine 25/25, emu 4/4 |
| M3 — Filtres | 10 filtres + dialog + preview + queue undoable | engine 36/36, emu 6/6 |
| M4 — I/O fichiers | ORA round-trip (groupes, blends, miniature), PSD import/export baseline, PNG/JPEG + EXIF | engine 42/42, emu 8/8 |
| M5 — Calques complets | groupes + masques + écrêtage + alpha lock, panneau pro (reorder, blends, rename) | engine 48/48, emu 10/10 |
| M6 — Vectoriel + règles | calques vectoriels (resample, pression→largeur, gomme par intersection), symétrie/ellipse/segment | engine 55/55, emu 13/13 |
| M7 — Animation (+M7b) | timeline cels + onion + fps réel, undo par trame ; audio + export WebM (MediaCodec VP8) | emu 19/19 |
| M8 — IA + réglages | 5 calques de réglage composite-time (ORA round-trip) ; super-résolution ×3 embarquée (runtime portable + modèle fixe permissif) | engine 66/66, emu 20/20 |
| M9 — Référence 3D | visionneuse glTF wireframe (lib vendored permissive) + caméra yaw/pitch/roll/dist/fov + fisheye, perspective 3 points | engine 71/71, emu 21/21 |
| M10 — Écosystème sans telemetry | sync auto-hébergée (WebDAV basic, cleartext localhost seul) + tons procéduraux maison (6 motifs, undoable) | engine 75/75, emu 23/23 |

Différés documentés (pas des échecs) : débruitage local + colorisation
auto — aucun poids mobile à licence permissive confirmée (revoir si des
poids ouverts apparaissent).

## 2. Phase STUDIO — slices 0→7 (close)

Shell studio app-side uniquement (aucun JNI neuf), speccé slice par
slice (`docs/sliceN-*.md`), vocabulaire maison :

| Slice | Chantier | État |
|---|---|---|
| 0 — Shell | barre menus, registre docs, dock repliable, garde tactile | clos |
| 1 — Gestes | 2-tap undo / 3-tap redo, pinch/pan/rotate vue, stylet-first, edge-swipe | clos |
| 2 — Couleur PRO | roue HSV + curseurs + hex, sets sauvegardés (JSON maison) + récentes, pipette composite | clos |
| 3 — Calques pro | vignettes live, reorder tactile, blend cherchable, repères couleur, barre tailles épinglée | clos |
| 4 — Timeline | scrub tactile, grosses vignettes, garde suppression, onion visuel | clos |
| 5 — Feeling pinceau | curseur diamètre réel, graphe courbe pression, gomme dédiée (bandeau honnête : recouvre, ne retranche pas) | clos |
| 6 — Multi-docs | onglets (plafond 4) + save 1-tap + nouveau canvas, workspaces JSON maison, barre commandes épinglable | clos |
| 7 — Polish | chrome figé, doublons supprimés, téléphone utilisable, budget « jamais de chrome > limite » | clos |

Résultat : suite instrumentée **75/75 verte en émulateur local KVM**
(`scripts/emutest.sh`), moteur 75/75, CI Android + engine vertes.
Chaque panneau naît repliable ; cible tablette 1920×1200@240, téléphone
repli total par défaut.

## 3. Phase `.clip` — lecture (a)+(b) close, écriture (c) écartée

Politique héritée du précédent PSD (M4) : aperçu/composite d'abord,
refus honnête du reste, paliers testés. App-side sans JNI (pont ORA
existant). Zéro binaire tiers dans git, fixtures de test générées
in-code.

- **(a) Raster complet — CLOS.** Enveloppe conteneur + base embarquée
  (lecture seule) + tuiles décompressées → couches ORA multi-calques
  (noms/visibilité/opacité/blends). Fallback aperçu honnête si tuiles
  inconnues, refus explicite des hostiles. 2 tests instrumentés.
- **(b) Vectoriel/texte en dégradé visible — CLOS.** Géométrie validée +
  bornée → rasterisation maison ; compteur d'approximations par calque +
  bandeau « dégradé : N » (EN/FR) ; couches non interprétables sautées
  avec mention. 2 tests instrumentés (vecteur + texte, compteur > 0,
  hostile refusé).
- **(c) Écriture — DÉFINITIVEMENT ÉCARTÉE.** Aucune lib sérieuse ne la
  propose (éditeurs conservateurs seulement, jamais générateurs) ;
  risque de fichiers corrompus/refusés + surface juridique inutile.
  Écritures interop = ORA natif + PSD + PNG/JPEG/WebM. Compat mono-doc
  tiers = lecture seule.

## 4. Feuille de route post-v1 (reprise du backlog)

Chaque FUTUR = 1 spec slice dédiée + JNI documenté dans `ARCHITECTURE.md`
**avant** tout code moteur ; gate local `emutest.sh` inchangé.
Coûts : **S** ≤ 1 session, **M** 2–4 sessions, **L** > 4 sessions ou
dépendance externe. Détail item par item : `docs/post-v1-backlog.md`.

### V+1 — Fondations manquantes (7 items)
| Item | Coût | Dépendance |
|---|---|---|
| Sélection (rectangle/ellipse/lasso/baguette, fourmis overlay-only) | L | moteur + JNI-documenté-d'abord |
| Calque texte (mise en page app-side, raster éditable, méta `x-openclip-text` ; pas de moteur fonte natif) | M | moteur + app + JNI-documenté-d'abord |
| Vraie gomme alpha (`alpha-erase` dest-out ; auj. peinture claire documentée) | M | moteur + JNI-documenté-d'abord |
| Verrou alpha / verrou pixels (flag par calque) | S | moteur + JNI-documenté-d'abord |
| Formes géométriques (rectangle/ellipse/polygone, stamps undoables) | M | moteur + app |
| Inclinaison stylet (tilt → forme/angle) | M | moteur + app |
| Densité estampilles + interpolation inter-moves (reste M2) | S | moteur |

### V+2 — Cases / exports longs (12 items)
| Item | Coût | Dépendance |
|---|---|---|
| Déplacer / échelle / rotation / libre + maillage | M | moteur + JNI-documenté-d'abord |
| Copier / coller / couper inter-calques et inter-docs | S | app |
| Édition points / poignées Bézier | M | moteur + JNI-documenté-d'abord |
| Export SVG des calques vectoriels | S | app |
| Guides persistants + splines concentriques | M | moteur + app |
| Cases / bulles / traits de vitesse (sur vecteur M6 + tons M10) | M | moteur + app |
| Sens de lecture + ordre des cases (métadonnées maison) | S | app |
| Export bande verticale (concat PNG haute) | S | app |
| Multipage maison (dossier ORA multiples + sommaire JSON) | M | app |
| Export GIF / APNG (formats ouverts) | M | moteur + app |
| Rythme par cel + reorder cels + compte onion moteur | M | moteur + JNI-documenté-d'abord |
| Export PSD plein (couches/blends/masques, au-delà de l'aplati) | M | moteur |

### V+3 — Confort pro (7 items + 2 conditionnels)
| Item | Coût | Dépendance |
|---|---|---|
| Mélangeur couleurs sur canvas | L | moteur |
| Épreuvage / profils couleur (pipeline sortie seul, blobs sous licence loggée) | M | moteur + app |
| Roue harmonies (calcul pur) | S | app |
| Mémorisation sélections (canaux nommés, ORA étendu maison) | S | moteur + app |
| Accrochage / simplification tracés | M | moteur |
| Texte sur tracé / déformation texte (après texte + maillage) | M | moteur + app |
| Mannequin articulé posable (format ouvert, modèles libres licenciés) | L | moteur + app |
| Courbes de tons éditables par points (V+2, M, LUT identifiée) | M | moteur + JNI-documenté-d'abord |
| Navigateur miniature / autosave opt-in / raccourcis clavier (V+2–V+3, S chacun) | S | app |
| Débruitage local + colorisation auto locale — **conditionnels** : seulement si poids ouverts permissifs (même blocage qu'en M8) | M / L | externe |

## 5. EXCLU assumé (avec alternatives libres)

| Refusé | Raison | Alternative |
|---|---|---|
| Services cloud éditeur (assets, sync, auth, colorisation/débruitage en ligne, billing, telemetry, crash-report tiers) | règle projet « sans telemetry » | sync WebDAV auto-hébergée (M10), tons procéduraux (M10), SR embarquée (M8) |
| Écriture formats proprios mono-doc et multipage (`.clip`, `.cmc`) | pas de spec officielle, risque corruption + juridique (§3) | ORA natif + PSD interchange + multipage maison (V+2) |
| Poses / assets 3D proprios, matériaux / boutique proprios | binaires fermés hors git | mannequin libre V+3, tons procéduraux M10 |
| Workspaces `.spws`, polices / ICC embarqués proprios | ressources fermées | workspaces JSON maison (slice 6), fontes système + libres |
| Promesse de fidélité totale d'import (brosses vectorielles, attributs texte opaques, 3D, timelapse) | non fidélisable | dégradé visible compté (palier b) |

## 6. Jalons de validation

- [x] M1→M10 : CI engine + Android vertes sur master (runs tracés en TASKS.md)
- [x] STUDIO + `.clip` (a)+(b) : 75/75 émulateur local KVM + moteur 75/75
- [x] Release `v0.1.0-alpha` publiée (prerelease, APK + sha256, téléchargement anonyme vérifié)
- [x] Tags `v0.1.0-alpha.2` / `v0.1.0-alpha.3` posés
- [ ] Validation manuelle tablette par le demandeur (cible 1920×1200@240)
- [ ] Prochain tag alpha après retour tablette

Not planned (rappel) : ads/telemetry, Play Billing, fingerprinting SDKs,
DRM de boutique fermée — GPL-3.0-or-later.
