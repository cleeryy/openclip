# CSP UX research — ce que le shell studio OpenClip doit mirorer

Public : quelqu'un qui n'a jamais ouvert Clip Studio Paint.
But : comprendre le **fonctionnement** à mirorer (workflow, layout, gestes),
sans jamais copier d'assets, strings ou binaires Celsys (voir § Clean-room).

État du shell actuel : `MainActivity.kt` = toolbar haut + canvas dominant +
dock droit repliable (onglets Studio / 3D & Tones) + tiroir timeline bas
replié par défaut + barre de statut fine. Ce document valide ces choix et
liste ce qui manque pour une parité workflow.

Sources primaires :
- Celsys — "Interface" (guide officiel, PC + renvoi Studio Mode tablette) :
  https://help.clip-studio.com/en-us/manual_en/060_pc/Interface.htm
- Celsys — "Basic touch gestures" (undo/redo 2-3 doigts, pan/zoom/rotate) :
  https://help.clip-studio.com/en-us/manual_en/750_gestures/Basic_touch_gestures.htm
- Celsys — "Preferences" (edge keyboard tablette) :
  https://help.clip-studio.com/en-us/manual_en/720_preferences/Preferences.htm
- Celsys — release notes 1.7.1 / 1.7.4 / 1.10.10 / 3.1.0 (palette dock,
  lock position/hauteur, layout tablette, Studio Mode par défaut) :
  https://www.clipstudio.net/en/dl/release_note/
- Celsys TIPS / Graphixly — Liz Staley, "Using Mobile Simple Mode"
  (Galaxy Tab S7, Simple vs Studio, calques + export) :
  https://graphixly.com/blogs/news/using-mobile-simple-mode
- Artiste — elenech, "How I use CSP on Galaxy Tab S7" (workspace 1/3 outils
  + 2/3 canvas, tool property, color sets, peu de calques) :
  https://medium.com/@elenech/how-i-use-my-clip-studio-paint-on-galaxy-tab-s7-workspace-brushes-color-palette-and-some-tips-59c72cd0ce1c
- Observation statique clean-room de l'apk 5.1.4 :
  `docs/csp-analysis.md` (evidence `ev_952bdf19…`, xapk sha
  `a2f6453d…c3931`) — architecture (1 NativeActivity, ~195 MB .so,
  ~45 plugins `*.cpm.so`, 108 états workspace `.spws`, 16k+ materials).

## 1. Les 7 réflexes pro à mirorer

### R1. Canvas dominant, chrome repliable (≤ 1/3 outils, ≥ 2/3 dessin)
Sur tablette 11" (Tab S7, 2560×1600) les artistes gardent ~1/3 d'écran pour
les outils (main gauche) et le reste pour le canvas (elenech). CSP le
formalise : palettes dockables/déplaçables, dock minimisable en onglets
d'icônes (release 1.7.1/1.7.4), verrouillage position/hauteur du dock
(1.10.10), layout tablette dédié (pref Interface, 1.7.1), Studio Mode
repositionné en 3.1.0.
**Shell : déjà conforme** (dock 290dp repliable, timeline repliée par
défaut). Garder : tout panneau doit pouvoir disparaître d'un tap, le
canvas ne perd jamais le focus.

### R2. Workflow ≤ 2 taps : outil → sub-outil → taille/opacité
Hiérarchie officielle : Tool palette (groupes) → Tool Group palette
(sub-outils) → Tool Slider (taille/opacité) → Tool Property (stabilisation,
etc.). L'artiste place la Tool Property — pas le simple slider — sous le
pinceau pour régler stabilisation sans quitter le geste.
**Shell : partiel.** PresetGallery + stabilisation existent, mais pas de
slider taille/opacité persistant en tête de dock ni de Tool Property
(stabilisation/courbe) visible pendant le dessin. À ajouter : une rangée
compacte taille+opacité épinglée en haut du canvas ou du dock.

### R3. Couleur PRO : roue + sets + récentes + pipette, jamais un seul picker
CSP a six palettes couleur (Wheel, Slider, Set, Approximate, Intermediate,
Historique) ; l'artiste empile roue/triangle + couleurs récentes + sets
perso (teintes peau par éclairage), bascule roue↔sliders (RGB/HSV/CMYK via
Window > Color Slider), échantillonne au picker et sauvegarde en sets.
**Shell : partiel.** ColorPicker actuel = un sélecteur simple. À ajouter :
onglets Roue / Curseurs (HSV+RGB hex) / Sets (sauvegardés) / Récentes +
bouton pipette (échantillonnage du composite) — tous en composants
originaux, aucune reprise visuelle Celsys.

### R4. Calques pro : liste compacte, opacité/blend/visibilité au doigt
Palette Layer officielle : ordre, vignettes, visibilité, opacité, modes,
dossiers, couleurs de palette reportées sur timeline (release 1.6.7) ;
en Simple Mode : bouton +, tap→menu (opacité/blend/visibilité).
L'artiste pro travaille avec *peu* de calques (esquisse + couleur puis
fusion) — la liste doit rester lisible à 3 comme à 50 calques.
**Shell : conforme** (LayerPanel : visibilité/opacité/blend/rename/groupes/
move/mask/clip, groupes repliables). Manque : vignettes par calque et
couleurs de repérage — envisager quand le moteur exposera des miniatures.

### R5. Timeline tiroir : cels, onion skin, fps — cachée quand inutile
Timeline officielle : pistes, cels numérotés, vignettes de calques (1.6.7),
onion skin (images avant/après teintées, réglage nb d'images, "Animation >
Show animation cels > Onion skin settings"), lecture au fps choisi.
En illustration pure le tiroir n'existe pas à l'écran.
**Shell : conforme** (TimelineStrip 96px, fps 12, onion prev/next,
tiroir ≤175dp replié par défaut). Garder : lecture = timer app, jamais de
boucle moteur (déjà le cas).

### R6. Gestes stylet-first, pas de menus pour undo/pan/zoom
Gestes officiels : 2 doigts tap = undo, 3 doigts tap = redo, 2 doigts
drag = pan, rotation 2 doigts = rotate canvas, pinch = zoom ; séparation
doigts-outils / stylet-dessin ("Use different tools with fingers and pen") ;
edge keyboard (swipe depuis les bords) sur tablette.
**Shell : à faire.** Aucun de ces gestes n'est câblé sur EngineSurface
aujourd'hui. Priorité : 2-tap undo / 3-tap redo (custom undo EngineHost),
pinch zoom + 2-doigts pan/rotate *de la vue* (matrice d'affichage, jamais
des pixels moteur), séparation doigt-navigue / stylet-dessine, edge-swipe
= clavier/raccourcis. Palm rejection via stylus-first (ACTION_DOWN
stylet > doigt).

### R7. Multi-docs à onglets + barre de commandes perso + save/export 1 tap
Title bar officielle : nom/résolution/zoom du canvas édité ; Command bar :
raccourcis perso (save…) ; Simple Mode : bouton save 1 tap en haut,
export via … → Export Image → format → Share/Save. Workspaces nommés et
partageables (fichiers `.spws`, Assets) ; nouvel-iiustration : dialogue
taille/résolution/papier.
**Shell : partiel.** Un seul document/sessions, pas d'onglets ; pas de
command bar custom ; export via SAF + statut (déjà 1-tap save implicite
manquant). À ajouter par étapes : (a) titre doc + save 1-tap dans la
toolbar, (b) onglets multi-docs (sessions moteur multiples), (c) workspaces
nommés persistés (équivalent fonctionnel des .spws, format maison JSON),
(d) dialogue nouveau canvas (px + dpi + fond, suggestion auto ≥2000px
comme chez l'artiste).

## 2. Ce qu'on NE copie PAS (périmètre clean-room)

Interdit (AGENTS.md + ARCHITECTURE.md, rappel contraignant) :
- **Binaires/extraits** : `.xapk/.apk`, `libClipStudioPaint.so`,
  plugins `*.cpm.so`, `PreinstallFiles.apk`, poids ML Caffe/ailia/TFLite
  S-Pen — hors git, jamais disséqués au-delà de l'inventaire statique.
- **Assets/contenus** : presets `.bfps`, configs `.todb/.tomd`,
  workspaces `.spws` (108 états), 16k+ materials, mannequins `.c3dd/.cs3o`,
  poses `.pep`, color sets, gradations, ICC, fonts, Closure Library
  (`assets/HTML.zip`) — remplacés par formats ouverts (MyPaint `.myb`,
  OpenRaster, tones procéduraux `nApplyTone`, futur mannequin libre).
- **Strings/UI** : aucun libellé, icône, nom de palette/outil, texte d'aide
  ou layout pixel repris de CSP. Nos strings EN/FR sont rédigées
  (`R.string.*` maison). Décrire un comportement ("tiroir timeline
  repliable") ≠ copier son apparence.
- **Formats proprio** : `.clip/.c3dd/.cs3o/.pep/.bfps` ni lus ni écrits ;
  ORA natif, PSD interchange, PNG/JPEG/WebM export, glTF référence.
- **Services** : Play billing, FCM, Asset Packs, Analytics, Crashlytics,
  AppsFlyer, ML Kit — remplacés par sync auto-hébergée (WebDAV) "sans
  telemetry" (M10).

Règle de formulation dans ce repo : "mirorer le fonctionnement" = décrire
le workflow en mots à nous (ex. "dock repliable en onglets d'icônes"),
jamais nommer/citer des ressources Celsys ni joindre de captures CSP.

## 3. Implications concrètes pour le shell (backlog ordonné)

| # | Chantier | État | Prochain pas (sans toucher engine/JNI) |
|---|---|---|---|
| 1 | Gestes canvas (R6) | absent | 2-tap undo / 3-tap redo, pinch+pan+rotate vue, stylet-first ; tests instrumentés gestes |
| 2 | Couleur PRO (R3) | basique | onglets Roue/Sliders/Sets/Récentes + pipette composite ; sets persistés en JSON maison |
| 3 | Taille/opacité épinglées (R2) | absent | rangée compacte persistante + Tool Property (stabilisation/courbe déjà moteur M2) |
| 4 | Titre + save 1-tap + nouveau canvas (R7) | partiel | nom/résolution/zoom en toolbar, dialogue nouveau doc (px/dpi/fond) |
| 5 | Multi-docs onglets (R7) | absent | N sessions EngineSurface, switch sans perdre undo (stacks par frame déjà isolées M7) |
| 6 | Workspaces nommés (R7) | absent | JSON maison (dock ouvert/onglet/timeline/gestes), jamais de `.spws` |
| 7 | Vignettes calques + couleurs repères (R4) | absent | attend miniatures moteur ; couleurs = champ app-side pur |
| 8 | Command bar custom (R7) | absent | rangée d'actions épinglables (save/undo/export/plein écran) |
| 9 | Navigateur miniature (officiel §9) | absent | vue réduite du composite + tap-seeking ; zoom/rotate/flip via mêmes gestes R6 |
| 10 | Plein-canvas tablette (R1) | OK | ne jamais régresser : chaque nouveau panneau naît repliable |

Contraintes transverses : tablette 1920×1200@240 = cible (AVD
`openclip-tablet`) ; téléphone 1080×2400 = repli total par défaut ;
aucun asset embarqué nouveau sans licence permissive loggée
(`engine/third_party/README.md` côté moteur, équivalent app-side à créer) ;
FR/EN pour chaque string ajoutée (leçon M5 : apostrophes FR cassent le
build — passer par ressources, jamais de littéraux).
