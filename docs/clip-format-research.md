# Recherche format .clip — état de l'art, risques, plan (2026-10-19)

> RECHERCHE UNIQUEMENT, aucun code. Clean-room : aucune string/binaire/asset Celsys,
> observation d'interop légitime uniquement (fichiers produits par l'utilisateur,
> docs publiques, libs open-source). Licence cible : GPL-3.0-or-later.
> `.cmc` (gestion de pages projet) EXCLU du périmètre.

## 0. Précédent : import PSD M4 (architecture, en 5 lignes)

1. Parser C++17 from-scratch dans `engine/src/io/psd.cpp` + `engine/include/openclip/psd.h`
   (`loadPsd`/`savePsd`), aucune dépendance externe hormis STL.
2. Scope volontairement baseline : RGB 8-bit, 3–4 canaux, compression raw (0),
   **merged image data uniquement** — les Layer/Mask Records sont sautés, pas parsés.
3. Tout le reste (autres profondeurs, modes, RLE) est **refusé avec erreur explicite**,
   jamais deviné — politique "refus honnête" qui a fait ses preuves en CI.
4. Import = fond unique "Background" + prémultiplication alpha (surfaces engine
   prémultipliées) ; export = composite aplati, canaux planaires straight.
5. Leçon transposable au .clip : viser d'abord **l'aperçu/composite**, refuser le reste
   proprement, élargir par paliers testés (`engine/tests` + `scripts/emutest.sh`).

## 1. Ce qui est déjà connu en interne

- `docs/csp-analysis.md` : seul fait format pertinent = modules d'import/export
  PSD/PSB+PDF observés dans les noms de libs natives ; **rien sur .clip**
  (pas de REA ciblée sur le format, preuves `/tmp/opencode/rea/csp` absentes de la machine).
- Choix roadmap existant : "modèle `.clip` → OpenRaster", i.e. ORA est déjà le natif ;
  le .clip n'est envisagé que comme **import de compatibilité**, jamais comme natif.
- JNI figé (`ARCHITECTURE.md`) : toute exposition .clip passerait par un ajout
  explicite type `nLoadDocument(path)` étendu, sans toucher à l'ABI existante.

## 2. Structure publique connue du format .clip

Sources : SPEC.md de `Inochi2D/clip-d`, docs `clipfile` (docs.rs, v1.2.0, 2026-08),
viewer `opnd.cc`, thread Krita `libmarugarou`, README `dobrokot/clip_to_psd`.
Convergent sur :

- **Conteneur à chunks big-endian**, signature racine `CSFCHUNK` (8 octets + tailles/offsets).
  Chunks de premier niveau : `CHNKHead` (en-tête fichier), 0..n `CHNKExta`
  (pixels externes, zlib), `CHNKSQLi` (base SQLite embarquée), `CHNKFoot`.
- **Base SQLite embarquée** (domaine public, donc sans risque licence) avec tables
  observées : `Canvas`/`Project` (dimensions, résolution), `Layer` (nom, type,
  opacité, visibilité, blend `LayerComposite`), `CanvasPreview` (PNG de prévisualisation),
  `ExternalChunk` (index offsets des blobs), `Offscreen`/`Attribute` (métadonnées tuiles),
  `VectorObjectList`, tables textes/corrections/timeline/rulers/caméras.
- **Pixels raster** : tuiles/blocs zlib propriétaires Celsys (layouts 8-bit alpha/BGRA,
  gris ; haute profondeur et packings exotiques non documentés), référencés depuis SQLite.
- **Vectoriel** : strokes/points validés structurellement (coordonnées, contrôles Bezier,
  couleurs, radius, width/opacity factors) mais **rendu brosse non fidélisable** ;
  les "point factors" ne sont PAS prouvés être la pression tablette.
- **Texte** : strings UTF-8 lisibles, attributs (fonte/paragraphe/transfos) opaques.
- **Corrections** : 9 types observés décodés par `clipfile` (brightness/contrast, levels,
  tone curves, HSL, color balance, reverse gradient, posterize, threshold, gradient map).
- Versions CSP ≥ 1.5 partagent le même conteneur (dixit opnd.cc) ; pas de spec officielle
  Celsys — tout est reverse-engineering d'observation de fichiers de sortie.

## 3. Libs open-source existantes (ne pas copier aveuglément)

| Projet | Langage | Capacité | Licence | Usage envisageable |
|---|---|---|---|---|
| `clipfile` (+ `clipfile-svg`) v1.2.0 | Rust | Le plus complet : envelope, SQLite, raster 8-bit, vecteur (géométrie lossless), texte UTF-8, 9 corrections, animation/timelapse/2D-camera, **réécriture conservatrice validée** | **MIT** (vérifié lib.rs) → compatible GPL-3.0 | Référence doc + validation croisée ; portage manuel ciblé si besoin, pas de dépendance Rust dans l'engine C++ |
| `dobrokot/clip_to_psd` | Python (1 script, PIL optionnel) | Convertit .clip → PSD avec calques (visibilité, masks, blends, texte éditable, filtres HSL/levels/brightness-curves, outline, dégradés/solides) ; **vecteur/tones/frame-border non supportés** (rasterisés si pixels présents, absents sinon) ; anim/3D/rulers exclus | **Licence non constatée** (aucun champ SPDX visible le 2026-10-19) → **à clarifier avant toute réutilisation**, usage = lecture seule comme spec exécutable | Preuve d'existence du palier (a) ; PSD intermédiaire = voie d'import sans parser natif |
| `ctrlcctrlv/libmarugarou` (+ base `rasensuihei/cliputils` 2019 MIT) | C/Rust visé | Prototype : **extraction du CanvasPreview uniquement** ; auteur = clean-room strict (pas de licence CSP, pas de décompilation, fichiers de commission uniquement) | **Apache-2.0** (vérifié) → compatible GPL-3.0 | Précédent méthodologique propre ; ne jamais promettre l'écriture (l'auteur refuse, à raison) |
| `Inochi2D/clip-d` (+ SPEC.md) | D | Parser expérimental pour Inochi Creator, chunks + zlib documentés sommairement | Licence **à vérifier** sur le repo avant réutilisation | Spec d'appoint seulement (très incomplète) |
| `al3ks1s/clip-tools` | Python | API .clip/.cmc annoncée, **WIP vide** (README TODO) | Inconnue | Ignorer |
| Photopea | Web proprio | Ouvre .clip (seul outil grand public avec CSP) | **Propriétaire fermé** | Référence comportementale manuelle uniquement, jamais de code |
| `opnd.cc/clip-studio-file-viewer` | JS navigateur (100 % local) | Extrait preview PNG + dump SQLite brut | Outil, pas lib | Utile pour constituer un corpus de test (fichiers utilisateur) |

Dépendances transitives sûres : **SQLite (domaine public), zlib (permissive)** — déjà
dans l'écosystème (miniz-vendored-like possible ; `engine/third_party/README.md` à tenir).

## 4. Risques juridiques et techniques

- **Juridique (faible si discipline tenue)** : les formats de fichiers s'observent
  librement (interop, cf. position libmarugarou) ; le danger serait de décompiler
  `libClipStudioPaint.so`, de copier strings/structures Celsys, ou d'intégrer du code
  à licence incompatible/non clarifiée (`clip_to_psd`, `clip-d`). Règle : lire la doc,
  réécrire from-scratch en C++17, tracer les sources dans `engine/third_party/README.md`.
- **Technique (moyen-fort)** : format propriétaire non documenté, layouts tuiles
  partiellement inconnus (haute profondeur, packings exotiques), mapping des
  blend-modes Celsys → nôtres à valider un par un, vecteur non rendable fidèlement,
  fichiers potentiellement énormes (streaming + limites `kMaxSide`-style obligatoires,
  jamais de `new` sur taille lue sans borne — même politique que `psd.cpp`).
- **Charge** : `clipfile` = ~27 kSLoC pour une couverture encore incomplète → ne pas
  viser la parité, viser l'utile (a) puis le dégradé (b), jamais la fidélité totale.

## 5. Faisabilité par palier

- **(a) Raster complet (import lecture)** : **FAISABLE, risque modéré.** Chemin propre :
  envelope `CSFCHUNK` → `CHNKSQLi` (SQLite lecture seule) → arbre `Layer` → tuiles zlib
  8-bit → couches OpenClip (noms/visibilité/opacité/blends connus, masks). Fallback
  pragmatique : `CanvasPreview` PNG si tuiles inconnues. Référence exécutable :
  `clip_to_psd` prouve que le raster+blends sort ; `clipfile[raster]` donne les layouts validés.
- **(b) Vectoriel/texte en dégradé visible** : **FAISABLE EN DÉGRADÉ EXPLICITE.**
  Vecteur → rasteriser nos propres paths (comme M6) depuis géométrie validée (pas de
  rendu brosse Celsys) ; texte → string UTF-8 posée en calque raster/texte simple
  (fontes système, attributs ignorés, watermark "approximatif"). À refuser proprement :
  rulers/3D/corrections non mappées (ou aplaties via preview). `clipfile-svg` montre
  l'ampleur des approximations à comptabiliser honnêtement.
- **(c) Écriture .clip** : **NON RECOMMANDÉE (même si (a) solide).** Aucune lib sérieuse
  ne l'offre (`clipfile write` = éditeur conservateur de structure existante, pas un
  générateur ; libmarugarou la refuse par principe). Risque : produire des fichiers que
  CSP refuse ou corrompt, + exposition juridique inutile. Exporter vers ORA/PSD/PNG
  suffit comme "écriture interop" — position à tenir.

## 6. Plan recommandé (si le chantier est ouvert)

1. **Corpus** : collecter des .clip de test *dessinés par nous dans CSP d'évaluation
   ou fournis par l'utilisateur* (petits : 20×10 puis 200×150, formes primaires pures —
   méthode Grum999) + noter version CSP source. Jamais de binaire Celsys dans git.
2. **P0 lecture envelope** : `CSFCHUNK`/`CHNKHead`/`CHNKSQLi`/`CHNKFoot` + dump SQLite
   (lecture seule, bornes strictes) → test engine : dimensions + arbre de calques listés.
3. **P1 preview** : `CanvasPreview` → PNG → `nLoadDocument`-compatible (1 calque fond).
   Valeur immédiate, risque ~nul, même sans décoder les tuiles.
4. **P2 raster par calque** : tuiles zlib 8-bit connues → couches (nom/visibilité/opacité/
   blends validés un par un, masks) ; layouts inconnus = erreur explicite + fallback preview.
5. **P3 dégradés (b)** : vecteur→paths rasterisés maison, texte→UTF-8 simple, corrections→
   aplaties ou ignorées avec rapport honnête par calque (compteur d'approximations).
6. **Portes** : chaque palier = tests engine + `scripts/emutest.sh` + doc ARCHITECTURE.md
   (ajout JNI explicite) ; jamais de dépendance Rust/Python dans l'engine — parser C++17
   from-scratch inspiré des specs, pas copié des implémentations.
7. **Alternative P2-bis** : si le natif bloque, convertisseur externe optionnel
   `.clip → .psd` (style `clip_to_psd`, licence à clarifier) comme import assisté,
   jamais dans le chemin natif.

## 7. Ce qu'on NE fera PAS

- Pas de `.cmc` (projets multi-pages) — exclu du périmètre.
- Pas d'écriture de .clip (palier (c) refusé, §5).
- Pas de décompilation/désassemblage de `libClipStudioPaint.so`, pas de copie de
  strings, constantes, assets ou binaires Celsys ; pas d'extrait XAPK dans git.
- Pas de promesse de fidélité (rendu brosse vecteur, attributs texte, 3D, timelapse,
  animation) — dégradé visible assumé et documenté.
- Pas d'intégration de code `clip_to_psd`/`clip-d`/`clip-tools` sans licence clarifiée ;
  pas de dépendance Rust (clipfile) dans l'engine C++ — référence seulement.
- Pas de parsing sans bornes (tailles/offsets/zlib) : tout input .clip est hostile
  jusqu'à preuve du contraire (même politique que `psd.cpp` : refus explicite).
