# Roadmap — plan de parité (le plus large d'abord, tailles honnêtes)

> Marque : **Calque** (rebrand : nom launcher, icône, accent cinabre,
> fonte display — package `dev.openclip`, moteur et docs inchangés,
> voir `docs/rebrand.md`).

Goal: widest possible feature parity with a professional painting app
(reference scope: pro 2D drawing + animation tool, v5 extended edition —
see csp-analysis.md) via clean-room reimplementation. Each milestone ships
something usable.

> **Status 2026-10-29 (alpha `0.2.0-alpha`) : jalon produit COMPLET.**
> M1→M10 clos et livrés · phase STUDIO (slices 0→7) close · compat `.clip`
> lecture (a)+(b) close, écriture (c) écartée · V+1→V+3 closes · tranches
> parité **T1→T5 closes** · rebrand **Calque** livré · passe visuelle
> V1→V18 livrée · perf release **P7 (R8 + shrink ressources)** livrée.
> Gates vérifiés : **moteur 237/237 tests**, **émulateur 248/248** (local
> KVM, `scripts/emutest.sh`), JNI gelé conforme.
> Ce fichier est la feuille de route tenue à jour (historique compact +
> phases). Le checklist exécutable avec preuves (tests, commits, runs CI)
> est `docs/TASKS.md` — voir son journal. L'inventaire parité source vs
> code est `docs/parity-gap.md` (instantané v0 ; écarts restants suivis
> dans l'issue GitHub #4). Recherche format : `docs/clip-format-research.md`.
> Recherche UX : `docs/csp-ux-research.md`. Règle clean-room partout :
> vocabulaire maison, jamais de strings, assets, captures ni binaires tiers.

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

## 4. Tranches post-v1 livrées (V+1→V+3, T1→T5)

Chaque tranche a sa spec dédiée (`docs/vN-*.md`, `docs/tN-*.md`) et, pour
tout symbole moteur neuf, une extension JNI documentée dans
`ARCHITECTURE.md` **avant** le code. Gate local `emutest.sh` inchangé.
Coûts d'origine : **S** ≤ 1 session, **M** 2–4 sessions, **L** > 4 sessions
ou dépendance externe. Détail item par item (état d'origine) :
`docs/post-v1-backlog.md`.

| Tranche | Contenu livré | Preuves |
|---|---|---|
| V+1 — Fondations | sélection rect/ellipse/lasso + fourmis + transfo, calque texte éditable, gomme `alpha-erase` dest-out | engine 80/80, emu 87/87 |
| V+2 — BD / exports longs | cases/bulles/vitesse, ordre de lecture, webtoon, multipage `.ocpages/`, SVG, Bézier, copier/coller, navigateur, autosave, courbes, raccourcis, GIF, PSD multicouche | engine 103/103, emu 125/125 |
| V+3 — Confort pro | mélangeur, canaux nommés, accrochage/simplification, texte sur tracé, mannequin posable, TIFF, PDF 1ʳᵉ page, SVG `<path>`, débruitage local classique | gate absorbé par le run T1 (engine 148/148, emu 181) |
| T1 — Fondations dessin | seau scanline (tolérance + AA sous masque), baguette, dégradé, figures, feather/masque rapide, unsharp/balance/seuillage (4 symboles JNI) | engine 148/148, emu 181 (run détaché ; gate 183 couvert par T2 202/202) |
| T2 — Texte + vectoriel avancé | runs multi-polices + import fontes, inline ancré, vertical + césure, contour, retrace/connexion/largeur, trajectoires, PSD `TySh`, bulle dédiée (4 symboles JNI) | engine 170/170, emu 202/202 |
| T3 — Animation productive | keyframes calque + caméra, 4 pistes, feuille d'exposition, APNG, séquence PNG, stickers, audio seul (15 symboles JNI) | engine 193/193, emu 218/218 |
| T4 — Transfos + filtres créatifs | warp sélection, marionnette MLS, forme intelligente, +10 filtres, décor 3D peint (3 symboles JNI) | engine 217/217, emu 226 phone / 227 tablette |
| T5 — Échanges + pages + productivité | +10 blends (20 au total), verrous/flags, fills composite-time, edge/aquarelle, `.grd`/`.abr`, macros, aligner, historique/info, `project.json` v2, folio, vue double, repères mm, fanzine, EPUB, PDF, séquences (2 symboles JNI) | engine 237/237, emu 248/248 |

Écarts assumés et documentés (pas des régressions) : sélection app-side
(session-only), texte rasterisé au commit (fiche `x-openclip-*` en ORA),
lecture multipage en JSON app-side, aperçu mesh = cadre, dégradé `.clip`
compté par calque, débruitage/colorisation IA conditionnels à des poids
permissifs.

### V+1 — Fondations (7 items, livrée)
Sélection rect/ellipse/lasso + baguette (masque app-side, fourmis,
Tout/Inverser/+1 px/Désélectionner, mix sous masque) · calque texte
éditable (layout app-side, pixels commités, fiche `x-openclip-text`) ·
vraie gomme alpha `alpha-erase` dest-out (legacy `eraser` conservé) ·
verrou alpha / verrou pixels par calque · figures géométriques ·
inclinaison stylet · densité d'estampilles + interpolation inter-moves.

### V+2 — Cases / exports longs (12 items, livrée)
Déplacer / échelle / rotation / libre + maillage · copier/coller/couper
inter-calques et inter-docs · édition points/poignées Bézier · export SVG
des calques vectoriels · guides persistants · cases/bulles/traits de
vitesse · sens de lecture + ordre des cases · export bande verticale ·
multipage maison `.ocpages/` · export GIF (+APNG en T3) · rythme par cel +
reorder + onion moteur · export PSD multicouche.

### V+3 — Confort pro (7 items + 2 conditionnels, livrée)
Mélangeur couleurs sur canvas · épreuvage/profils (pipeline sortie seul,
aucun blob ICC tiers) · roue harmonies · canaux de sélection nommés ·
accrochage/simplification des tracés · texte sur tracé · mannequin
articulé posable · courbes de tons éditables · navigateur/autosave/
raccourcis clavier · débruitage local classique (médiane/bilatéral).
**Conditionnels non livrés** : débruitage IA + colorisation auto locale —
seulement si poids ouverts permissifs (même blocage qu'en M8).

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
- [x] V+1→V+3 livrées : emu 87 → 125, moteur 80 → 103 (V+3 couverte par le gate T1)
- [x] T1→T5 livrées : moteur 148 → **237/237**, émulateur 181 → **248/248** (phone KVM) ; tablette 247/248 (flake `clipMaskBadges`, vert en isolé)
- [x] Rebrand **Calque** livré (label + icône adaptative + accent cinabre + fonte OFL, package `dev.openclip` gelé)
- [x] Passe visuelle V1→V18 livrée (dock adaptatif, barres lisibles, footer propre, cibles 48 dp)
- [x] Perf release P7 : R8 + shrink ressources, règles keep JNI/ONNX, gate symboles mis à jour
- [x] Release `v0.1.0-alpha` publiée (prerelease, APK + sha256, téléchargement anonyme vérifié)
- [x] Tags `v0.1.0-alpha.2` / `v0.1.0-alpha.3` posés
- [ ] Tag `v0.2.0-alpha` (notes de release = `CHANGELOG.md`, taille APK et démarrage à mesurer)
- [ ] Validation manuelle tablette par le demandeur (cible 1920×1200@240)
- [ ] Écarts de parité restants : suivis dans l'issue **#4** (inventaire de départ : `docs/parity-gap.md`)

Not planned (rappel) : ads/telemetry, Play Billing, fingerprinting SDKs,
DRM de boutique fermée — GPL-3.0-or-later.

## 7. Alpha `0.2.0-alpha` — contenu et suivi

La release `0.2.0-alpha` (2026-10-29) est la première alpha consolidée de
la marque **Calque** : moteur M1→M10, shell STUDIO slices 0→7, lecture
`.clip` (a)+(b), tranches V+1→V+3, tranches T1→T5, rebrand, passe visuelle
V1→V18 et perf release P7. Le détail daté (commits, runs, preuves) est dans
`docs/TASKS.md` ; le journal utilisateur est `CHANGELOG.md`.

Suivi des travaux restants (issues GitHub, source de vérité) :

| Issue | Sujet | État |
|---|---|---|
| [#1](https://github.com/cleeryy/openclip/issues/1) | CI moteur rouge : `stack-use-after-scope` dans `savePsd` | ouvert |
| [#2](https://github.com/cleeryy/openclip/issues/2) | Split des APK release par ABI (taille de téléchargement) | ouvert |
| [#4](https://github.com/cleeryy/openclip/issues/4) | Écarts de parité différés (suite de `parity-gap.md`) | ouvert |
| [#5](https://github.com/cleeryy/openclip/issues/5) | Nom d'APK épinglé de l'UI émulateur après le rebrand | ouvert |

Hors issues : validation manuelle tablette par le demandeur, mesure
taille/démarrage de l'APK R8, et poids permissifs pour débruitage /
colorisation IA.
