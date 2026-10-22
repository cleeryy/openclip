# Rebrand — spec (sans renommer le package)

> SPEC uniquement, aucune modification de code.
> Contrainte gelée : `applicationId` / `namespace` restent
> **`dev.openclip`** (mises à jour, données, SAF persisté, WebDAV :
> tout est keyé dessus — renommer casserait les installs).
> Seul le **visible** change : nom launcher, icône, accent/typo,
> strings, docs. Clean-room : nom, icône, palette et wording maison,
> rien repris d'un outil tiers.

## 1. Nom d'affichage

Recommandé : **Calque**.

- Pourquoi : 1 mot, terme du métier (le cœur de l'app : calques,
  masques, clipping), court en launcher, FR naturel et EN-safe
  (mot français distinctif, pas de collision évidente sur le Play
  Store au 2026-10 — à revérifier le jour J par une recherche store).
- Alternatives (ordre de repli) : **Esquisse** (doux, moins distinctif),
  **Planche** (BD-connoté, biaise vers V+2), **Atelier** (générique).
- Règle : nom ASCII-safe pour le launcher (`Calque`), accentué
  uniquement dans les phrases FR (`Calque — …` jamais nécessaire ;
  juste `Calque`).

## 2. Icône launcher (décrite en vectoriel, jamais copiée)

État actuel : `drawable/ic_logo.xml` — cercle bleu `#0F6FFF` + éclat
blanc, icône legacy (pas d'adaptive, pas de `mipmap-*`, pas de
monochrome). Sur launchers récents : tuile générique, pas de variante
thématique (cf. `audit-visuel.md` V18).

Nouvelle icône maison « trois feuillets » :

- `mipmap-anydpi-v26/ic_launcher.xml` (adaptive) :
  - `background` : `#141419` (encre, même ton que `surface-row`).
  - `foreground` (108dp, zone sûre 72dp) : trois rectangles arrondis
    (rayon 8dp) superposés en cascade, décalés de 10dp vers haut-droite :
    dos `#3A3A46`, milieu `#90B8FF` (accent), devant `#ECECF1`
    (papier) ; sur le feuillet papier, un point d'encre `#141419`
    (rond 10dp, bas-gauche) + un trait d'encre (barre 4dp, diagonale
    30°) — le geste du pinceau, en 2 formes géométriques.
  - `monochrome` (Android 13+) : mêmes trois feuillets en aplats du
    ton système (utiliser `android:tint` du monochrome : décrire les
    paths sans couleur dure, seule l'opacité distingue les feuillets
    100 % / 60 % / 30 %).
- Legacy : `drawable/ic_launcher_foreground.xml` + fond couleur pour
  pré-O (généré depuis le même vectoriel, pas un PNG committé).
- Interdits : pinceau réaliste, goutte d'eau générique, dégradés
  criards, tout motif proche d'une marque existante.

## 3. Palette et typographie maison

La palette slice 7 §4 reste (contrastes déjà ≥ 4.5:1) ; le rebrand ne
change que l'**accent de marque** et la **voix typographique** :

| Rôle | Avant | Après (maison) | Usage |
|---|---|---|---|
| `brand` | `#0F6FFF` (icône seule) | `#E0573A` cinabre | icône feuillet ? non — **réservé** : CTA premier (Export, Import SVG), pastille dirty `•`, pastille `i/N` |
| `accent` | `#90B8FF` partout | `#90B8FF` conservé | liens, save, reset view (lecture, pas action) |
| `select` | `#284678` | conservé | onglet actif, preset actif |
| `ok` / `err` | `#80E27E` / `#E08080` | conservés | statut seul |

- Règle d'usage : **cinabre = une seule action par écran**
  (l'action de sortie : Export). Jamais deux CTA cinabre côte à côte
  (ex. `Dither [Export]` : seul `Export` devient cinabre, `Dither`
  reste bouton secondaire).
- Typographie : bundler **une** fonte display OFL (recommandée :
  `Space Grotesk` 500/700 — géométrique studio, pas enfantine) pour
  `titleSmall`/`titleMedium` du chrome + nom du doc ; corps =
  système par défaut (pas de 2e fonte, pas de download). Titres FR :
  casse phrase, jamais de capitales forcées.
- `Theme.OpenClip` (interne) : renommage du style **non requis**
  (jamais visible) ; seul `android:label` change.

## 4. Strings impactées

| Clé / fichier | Avant | Après | Notes |
|---|---|---|---|
| `app_name` (`values` + `values-fr`) | `OpenClip` | `Calque` | seul changement de nom visible ; FR identique (pas d'accent au launcher) |
| `Theme.OpenClip` (`themes.xml`, manifest) | — | inchangé | interne, 0 impact visuel |
| `namespace` / `applicationId` (`build.gradle.kts`) | `dev.openclip` | **gelé** | ne pas toucher |
| Nom de fichier export WebM (`MainActivity.kt` l.1613, 2439 : `"openclip-…"`) | `openclip-<ts>.webm` | `calque-<ts>.webm` | nom proposé au SAF, visible ; ancien préfixe accepté à l'import (pas de migration) |
| `x-openclip-*` (méta ORA : `v1/v2/v3/confort`) | — | **gelés** | format de fichier, pas marque ; renommer casserait les docs existants |
| `x-openclip-text-path`, `x-openclip-pose` | — | **gelés** | idem |
| `commandbar.json`, `workspaces.json`, clés `workspace-v1` | — | inchangés | posture, pas marque |
| Commentaires `// OpenClip …` (une 60aine) | — | inchangés au rebrand | code, pas UI ; renommer = bruit de diff |
| Asset `openclip.apk` (`/opt/emu-ui`, Caddy) | — | renommable `calque.apk` | infra locale, hors repo ; faire le même jour pour éviter 2 noms |

Aucune autre string n'embarque la marque (vérifié : `strings.xml`
ne contient que `app_name`).

## 5. Docs et README à mettre à jour

| Fichier | Changement |
|---|---|
| `README.md` (titre + badges + captures) | titre `Calque (ex-OpenClip)`, 1 ligne « package `dev.openclip` inchangé pour les mises à jour » |
| `docs/ROADMAP.md` | titre + mention « marque Calque, moteur/docs inchangés » |
| `docs/audit-visuel.md` §V18 | cocher : traité par ce rebrand |
| `AGENTS.md` (conventions + AVD `openclip-emu/tablet`, `/opt/emu-ui/openclip.apk`) | noms AVD/APK : changer ou garder ? **garder** (infra, pas marque) — juste noter l'équivalence |
| `docs/*.md` (specs M1..M10, slices, V+) | **ne pas retoucher** (historique) — le rebrand ne réécrit pas l'histoire |
| Play listing / fastlane (si créé un jour) | nom `Calque`, icône adaptive, captures neuves (voir §6) |
| GitHub : nom du repo `cleeryy/openclip` | **garder** (URLs, CI, liens) ; la fiche repo mentionne `Calque` |

## 6. Plan de migration (1 commit dédié)

1. Commit unique `rebrand: Calque (label+icon, package gelé)` :
   `app_name` EN+FR, `mipmap-anydpi-v26` + foreground/monochrome,
   `themes.xml` parent inchangé, accent cinabre (1 token couleur +
   usages CTA), fonte display bundlée (1 fichier OFL + licence dans
   `assets/fonts/`), nom export `calque-`, `README.md` + `ROADMAP.md`.
   Rien d'autre (surtout pas les V1..V16 de l'audit : autre lane).
2. Vérifications : `bash scripts/prepush.sh` vert ; install `-r` par
   dessus l'alpha (données + docs + SAF conservés) ; lancement FR + EN.
3. Captures avant/après (même AVD, même doc, même heure) :
   launcher (tiroir d'apps), barre compacte, tiroir export —
   ` /tmp/opencode/calque-rebrand-avant.png / -apres.png`.
4. Seulement après : tag `v0.1.1-calque` ? Non — le workflow publish
   déclenche sur `v*` : choisir un tag version (`v0.2.0`) dont la note
   de release annonce `Calque`. Pas de tag fantaisie.

## 7. Volet « perfs max » (optimisations sûres, ordre + risques)

Ordre = gain / risque décroissant. Mesurer avant/après à chaque étape
(`dumpsys meminfo`, compteur `setBrush`, profiler Compose) ; une étape
qui ne gagne rien = revert.

| # | Optimisation | Fichier(s) | Gain attendu | Risque | Mesure |
|---|---|---|---|---|---|
| P1 | Throttle `setBrush` sliders : prévisualisation locale immédiate, push moteur ≤ 1/33ms + `onValueChangeFinished` final exact | `PinnedBrushBar.kt`, `ToolPalette.kt` | élevé (divise les appels moteur par ~10 pendant un drag) | faible (valeur finale garantie par le commit final) | compteur `setBrush`/drag 500ms ≤ 20, valeur finale byte-identique |
| P2 | LRU + `recycle()` vignettes timeline (24 entrées, motif `LayerPanel`) | `TimelineStrip.kt` l.100 | élevé (mémoire bornée sur longs clips) | faible (même motif déjà en prod calques) | cache ≤ 24, meminfo stable après suppressions |
| P3 | Puce `i/N` et pastille scrub : overlays déjà à coût zéro — ne rien changer, figer par test | `TimelineStrip.kt` l.345-375 | nul (évite une régression) | nul | T10 de l'audit |
| P4 | Scoper `status`/`refreshKey` (footer seul lit `status`, `key()` par zone) | `MainActivity.kt` | moyen (recompositions chrome) | moyen (état partagé subtil : `poke()` + tests hooks) | 0 recomposition dock sur `poke()` isolé |
| P5 | Vignettes calques : LRU 60 déjà OK ; ne pas passer en `RGB_565` | `LayerPanel.kt` | — | **refusé** : banding sur témoins couleur, gain mémoire marginal à 48px | ne pas faire |
| P6 | Timeline `LazyRow` déjà `userScrollEnabled=false` + programmatic : ne pas fenêtrer davantage | `TimelineStrip.kt` l.280 | — | **refusé tant que** : le worker thumbs est déjà borné (8) et débouncé (400ms) | ne pas faire |
| P7 | Release : R8/minify + `baseline-prof` sur le cold-start | `build.gradle.kts`, CI | moyen (taille APK 84MB → -15/25 %, démarrage) | moyen (ONNX Runtime + JNI : règles ProGuard à valider, `check_jni_symbols.sh` + boot émulateur après chaque règle) | taille APK, `reportFullyDrawn`, suite verte |

Ne pas faire (risque > gain) : pool de `Bitmap` partagé timeline/calques
(alityasing de recyclage, crashs `isRecycled` aléatoires) ; `Spine`/`RenderEffect`
sur le chrome (overdraw GPU pour 0 bénéfice) ; mise en cache du composite
pleine-résolution côté app (c'est le moteur qui possède les pixels, règle
slice 7 : `pullFrameFull` byte-identique).
