# Poids IA débruitage / colorisation — recherche de déblocage (#17, §8.2/§8.3)

Date : 2026-11-01. Portée : recherche seule (aucun code, aucun changement d'API).
Objet : §8.2 (débruitage IA) et §8.3 (auto-colorisation) sont CONDITIONNELS dans
`ARCHITECTURE.md` faute de « permissive <5 Mo ONNX weight ». Ce document tranche.

## 1. Politique de sélection (rappel)

- Licence permissive explicite : Apache-2.0, MIT, BSD, CC0 (pas de « research only »,
  pas de CC-BY-NC, pas de licence absente ou contradictoire entre miroirs).
- Poids < 5 Mo, inférence CPU mobile acceptable, aucune télémétrie, aucun service en ligne.
- Le gate qualité doit être explicite : un modèle qui passe licence+taille mais dont la
  qualité ne couvre pas l'usage visé est **rejeté** (pas « accepté par défaut »).

## 2. Méthode (reproductible)

1. Balayage de la Hub API Hugging Face (`/api/models?search=…&filter=onnx`, tri
   downloads, 294 dépôts couvrant denoise/colorize/manga/lineart) + tailles réelles via
   `/api/models/<id>/tree/main?recursive=true`.
2. Vérification de licence sur la carte du modèle **et** sur le dépôt d'origine
   (GitHub `/repos/<r>` : SPDX + fichier `LICENSE`).
3. Inspection binaire de chaque finaliste avec `onnx` 1.23.2 : `opset_import`, `ir_version`,
   formes d'entrée/sortie, nombre de paramètres, liste d'opérateurs, présence d'`external data`.
4. Exécution réelle avec `onnxruntime` **1.31.1** (même mineure que l'AAR 1.31.0 de l'app),
   CPU EP, 2 à 4 threads, x86_64 : latence, pic RSS (`VmHWM`), contraintes de dimensions.
5. Test qualité synthétique : image type « line art sur papier » 384×384 (traits noirs,
   dégradé doux) + bruit gaussien σ=25/255, PSNR entrée 22,81 dB.

Ces mesures x86_64 donnent un ordre de grandeur ; sur ARM64 mobile compter ~2–4× plus lent
en fp32, souvent plus rapide en int8 (dotprod).

## 3. Candidats débruitage

| Modèle (provenance) | Licence (vérifiée où) | Taille | Opset / IR | Entrée → sortie | Mesures | Verdict |
|---|---|---|---|---|---|---|
| `jere1882/document-image-denoising` — `residual_unet_bc16/model_fp32.onnx` (HF, auteur = repo GitHub `jere1882/DocDenoising`) | **MIT** — carte HF **et** fichier `LICENSE` du repo GitHub (SPDX `MIT`) | **1,85 Mo** (int8 : **0,50 Mo**) | 17 / 8 | `[batch,3,h,w]` f32 → idem, **h,w multiples de 8** | 368 ms @512² (fp32, 2 th) / 212 ms (int8) ; 1,76 s @1024² fp32, 0,70 s int8 (4 th) ; RSS +110 Mo par tuile 512² ; PSNR 22,81 → **32,62 dB** (fp32) / 32,59 (int8) | **RETENU** (seul candidat conforme, à cadrer « nettoyage de scan ») |
| `manasrpandya/bilateral-denoise-nbf` — `nbf.onnx` | MIT **uniquement** sur la carte HF ; dépôt GitHub (`NLP_CV_Lab`) **sans LICENSE** | 462 ko | 17 / 8 | `[n,1,h,w]` dynamique → `y` + `maps` (à jeter) | 1,34 s @512², 4,49 s @1 MP, pic RSS 424 Mo / 1,45 Go (942 nœuds pour 93 k params) ; PSNR → 29,19 dB | Rejeté : runtime inutilisable (coût/opérateur disproportionné), licence non corroborée |
| `jessolmstead/greycard-denoise` v19 | GPL-3.0 (carte ; compatible GPL-3.0 du projet mais hors politique) | 4,70 Mo fp32 / 2,36 Mo fp16 | 17 | `[1,4,h,w]` **Bayer RAW packé** | non testé | Rejeté : domaine d'entrée RAW/CFA, inapplicable à un bitmap RGBA |
| `Heliosoph/scunet-onnx` | Apache-2.0 (carte) ; upstream `cszn/SCUNet` | 3,62 Mo + **69 Mo** `.onnx.data` | — | — | — | Rejeté : taille réelle 69 Mo (external data) |
| `sheldonxxxx/RapidRAW-Nonlocal-Denoise` | MIT (tag) ; ré-upload tiers (`NOTICE.upstream`) | 82 Mo | — | — | — | Rejeté : taille + provenance de seconde main |
| `Logolabs/inkvec-denoiser-001` | Apache-2.0 (carte) | 76 Mo | — | — | — | Rejeté : taille |
| `SebRincon/nafnet-realestate` (`…colorft_3h_fp32.onnx`) | Apache-2.0 (carte) ; upstream NAFNet **MIT** | 2,23 Mo annoncés mais **artefact cassé** | 18 / 10 | `[N,3,h,w]` | Échec au chargement : `External data path validation failed for initializer: intro.weight` | Rejeté : export invalide + tâche = « enhancement » immobilier (577 paires, PSNR 21,69), pas du débruitage |
| `Tinigon/manga-colorizer-onnx` → `denoiser.onnx` | **unknown** (carte vide, aucun LICENSE) | 3,27 Mo | 17 / 8 | `[n,3,h,w]`, dims **paires** seulement | 1,37 s @512² ; PSNR → 28,64 dB ; échoue sur 333×500 | Rejeté : licence inconnue (bloquant) ; sinon taille/qualité acceptables |
| `cszn/KAIR` → `model_zoo/dncnn_25.pth` | **MIT** (fichier `LICENSE`, repo entier) | 2,12 Mo (.pth, **pas ONNX**) | — | gris, σ=25 fixe | non testé | Rejeté/différé : pas d'export ONNX fourni, monochrome, bruit gaussien fixe — il faudrait exporter nous-mêmes pour un gain inférieur au candidat retenu |

Rappel : le filtre **§8.1 « denoise » classique (median/bilateral 3×3) est déjà livré**
côté moteur (`engine/include/openclip/filters.h`). Le candidat retenu n'est donc pas un
« premier débruitage » mais un remplacement AI d'un outil existant — sur un domaine étroit.

## 4. Candidats colorisation

| Modèle (provenance) | Licence | Taille | Opset / IR | Entrée → sortie | Mesures | Verdict |
|---|---|---|---|---|---|---|
| `User-2468/mini-unet-colorizer` v3 — `colorizer.onnx` | Apache-2.0 (frontmatter ; **aucun fichier LICENSE**, entraînement revendiqué par l'auteur, cibles DDColor) | **15,30 Mo** | 17 / 8 | **fixe** `[1,1,256,256]` (L/50−1) → `[1,2,256,256]` (Lab ab), 3,98 M params | 46–52 ms / tuile 256² | Rejeté : **3× le budget** ; quantization dynamique int8 → 4,14 Mo mais `max|Δab|` = 18,6 et **705 ms** (plus lent) → pas de sauvetage par quantization |
| `piddnad/ddcolor_paper_tiny` (DDColor, Apache-2.0 confirmé HF **et** GitHub) | Apache-2.0 | 107–129 Mo (ONNX fp16), 210 Mo (PyTorch) | — | 512×512, Lab | — | Rejeté : taille |
| `manga_colorization_v2` (miroirs `Faridzar/…-fp16`, `ifritraen/…-fp32`, `Tinigon/colorizer.onnx`) | **contradictoire** (MIT sur un miroir, Apache-2.0 sur un autre, unknown sur le troisième) ; dépôt upstream introuvable | 58 Mo fp16 / 117 Mo fp32 | — | 512×512 | — | Rejeté : taille + provenance/licence incohérentes |
| `jantic/DeOldify` (export `xrds/deoldify`) | MIT (repo GitHub) | 61 Mo (quantized) | — | 256–512 | — | Rejeté : taille |
| `richzhang/colorization` (siggraph17) | BSD-2-Clause (repo GitHub) | ~130 Mo | — | 224×224 | — | Rejeté : taille |
| `pelixpng/pastvu-colorizer` | Apache-2.0 (carte) | 230 Mo | — | — | — | Rejeté : taille |

Aucun coloriseur ONNX ≤ 5 Mo n'existe publiquement avec licence permissive : le plus petit
candidat permissif fait 15,3 Mo (≈3,98 M paramètres), soit ~4× le plafond de 1,2 M paramètres
qu'imposerait un budget de 5 Mo en fp32. Le balayage exhaustif (294 dépôts, tous fichiers
`.onnx` ≤ 6 Mo listés) ne remonte qu'un seul fichier plus petit — `Tinigon/denoiser.onnx`,
licence inconnue.

## 5. Faisabilité d'intégration ORT Android

- **Opset** : tous les finalistes sont opset 17 / IR 8 (le nafnet rejeté est 18/10). ORT
  1.31 les charge ; aucun opérateur `com.microsoft` (contrib) requis.
- **Opérateurs du candidat retenu** : `Conv`, `ConvTranspose`, `MaxPool`, `Relu`, `Clip`,
  `Tanh`, `Sub`, `Concat`, `Constant` — tous dans le CPU EP Android. 43 nœuds, 0,482 M params.
- **Formes dynamiques** : `h`/`w` sont bien dynamiques, **mais** la chaîne
  `MaxPool(2×) … ConvTranspose(2×)` exige `h,w ≡ 0 (mod 8)` — vérifié : 512, 768, 1000, 1024
  passent ; 333×500 et 1000×1001 échouent (`Concat`: « dimensions must match 83 vs 82 »).
  Intégration = pad réflexif au multiple de 8 puis crop.
- **int8** : le fichier `model_int8.onnx` est un QDQ statique (`QuantizeLinear`/`DequantizeLinear`,
  per-channel, calibré sur 500 crops). ORT 1.31 l'exécute (y compris `QLinearConcat`) :
  2,5× plus rapide, écart qualité négligeable sur notre test (32,59 vs 32,62 dB).
- **Mémoire** : pic natif +110 Mo par tuile 512² (fp32), +57 Mo (int8) ; +290 Mo en une passe
  1024². Plafonner à des tuiles 512² avec 32 px de recouvrement.
- **Intégration app** : zéro JNI, zéro changement moteur — même patron que
  `app/src/main/java/dev/openclip/media/AiUpscaler.kt` (`createSession(byte[])` depuis
  `assets/models/`, `setIntraOpNumThreads(2)`, échec → chaîne de statut, jamais d'exception).
  Coût : un objet Kotlin, une action UI, une note « M11 additions » dans `ARCHITECTURE.md`.
- **APK** : +0,5 Mo (int8) à +1,9 Mo (fp32) — négligeable devant l'APK debug de 84 Mo.

## 6. Recommandation

**§8.2 débruitage : LIVRÉ** sous le nom « Scan cleanup (AI, documents) » (modèle docden bc16 fp32, 1,93 Mo, MIT, sha256 `93d7eac4…c8659`, facteur de mélange 0–100 %, tuiles 512²).

**§8.3 auto-colorisation : blocage CONFIRMÉ.** Aucun poids ≤ 5 Mo sous licence permissive
n'existe ; le plus petit candidat crédible est 3× au-dessus du budget, en entrée fixe 256²,
et la quantization le dégrade massivement tout en le ralentissant. Ce n'est pas un échec de
recherche : c'est une absence réelle d'offre. Revoir si un modèle ≤ 1,2 M params apparaît
(ou si le budget passe à 20 Mo, décision produit).

**§8.2 débruitage IA : déblocable, mais pas au bénéfice attendu.**
`jere1882/document-image-denoising` (MIT, 1,85 Mo fp32 / 0,50 Mo int8, opset 17) est le seul
candidat qui respecte licence + taille + runtime. Mais il est entraîné sur des **crops de
documents scannés** (dégradations JPEG/flou/bruit synthétiques, 50 k crops) : sur une
illustration ou une photo il risque de lisser les textures de pinceau et le grain voulu.
Comme le §8.1 classique (median/bilateral) est déjà livré, le gain marginal ne justifie pas
de rouvrir §8.2 en l'état.

Deux suites possibles, à trancher par le mainteneur :
1. **Statut quo** (recommandé) : laisser §8.2/§8.3 différés, mettre à jour la note
   d'`ARCHITECTURE.md` avec ces résultats (recherche faite, dates, critères) au lieu de
   « no permissive <5 Mo weight confirmed » sans preuve.
2. **Ouverture d'une tâche étroite « Nettoyage de scan »** (pas « débruitage IA ») :
   docden bc16 int8, 0,50 Mo, tuiles 512², pad mod-8, action explicite dans le menu ;
   gate de sortie = 20 tests instrumentés + comparaison visuelle sur scans réels.

## 7. Risques

| Risque | Détail | Mitigation |
|---|---|---|
| Qualité | Aucune métrique sur illustrations/photos pour docden ; PSNR document 29,22 dB, +9,8 dB sur notre synthétique seulement | Gate visuel sur 10 scans + 10 illustrations avant tout merge ; fallback = aucun changement |
| Domaine | Modèle « documents » : lissage des textures fines, du grain et des dégradés doux | Libellé UI explicite, appliqué sur calque dupliqué, annulable (undo par frame existe déjà) |
| Latence | 1,76 s @1024² sur x86 (4 th) → 3–7 s probables sur mobile | Tuiles 512², aperçu, barre de progression, annulation |
| Mémoire | +110 Mo natif par tuile ; OOM possible sur appareils 3 Go | Tuiles ≤ 512², une session réutilisée, pas de batch |
| Licence | Cartes HF parfois seules preuves (`nbf`, `mini-unet`) ; les cartes changent | Ne retenir que docden (LICENSE MIT dans le repo) ; épingler sha256 et vendoriser le fichier avec provenance dans `engine/third_party/README.md` |
| Données d'entraînement | Dataset Kaggle « document-crops-50k » : licence propre non vérifiée (poids MIT, données non redistribuées) | Acceptable (poids seuls redistribués) ; à mentionner dans la provenance |
| Taille APK | +0,5 à +1,9 Mo | Négligeable ; préférer int8 |
| Dérive | Aucun upstream maintenu (auteur unique, 0 star) ; pas de correctifs attendus | Poids figé, vendorisé, testé — pas de dépendance runtime |

## 8. Sources et empreintes

- docden : https://huggingface.co/jere1882/document-image-denoising ·
  https://github.com/jere1882/DocDenoising (MIT)
  - `residual_unet_bc16/model_fp32.onnx` 1 936 165 B ·
    `93d7eac43b27cbdcdfa6997a344695e885b85897e477820246bda43eac1c8659`
  - `residual_unet_bc16/model_int8.onnx` 529 367 B ·
    `6f1f7e2fc57e9393a671fe53f0843296886f67b706de1a400c25a2eb1940e0f1`
- nbf : https://huggingface.co/manasrpandya/bilateral-denoise-nbf (`nbf.onnx` 472 757 B ·
  `ddce79015e23fcac6058b0316decd091488367b1e982b9a9729a5cf12f9b4f10`)
- manga denoiser : https://huggingface.co/Tinigon/manga-colorizer-onnx (`denoiser.onnx`
  3 424 550 B · `bdfbcf0e34226e8acae575b26abc8e694c3b9be9c145bb7d12d967e5623e9d28`)
- mini colorizer : https://huggingface.co/User-2468/mini-unet-colorizer (`colorizer.onnx`
  16 039 257 B · `911fb777328fcfbeeb89cc25dca70092cf2de4686ede1edd44a8ccebc89ea341`)
- DDColor : https://huggingface.co/piddnad/ddcolor_paper_tiny ·
  https://github.com/piddnad/DDColor (Apache-2.0)
- DeOldify : https://github.com/jantic/DeOldify (MIT) ; siggraph17 :
  https://github.com/richzhang/colorization (BSD-2-Clause)
- KAIR : https://github.com/cszn/KAIR (MIT, `model_zoo/dncnn_25.pth` 2,12 Mo)
- NAFNet : https://github.com/megvii-research/NAFNet (MIT — le fine-tune « real estate »
  reste rejeté pour artefact cassé et tâche hors sujet)

Reproduction : `onnx.load(path, load_external_data=False)` pour opset/formes/params ;
`onnxruntime.InferenceSession(..., CPUExecutionProvider)` + `VmHWM` pour latence/mémoire ;
`onnxruntime.quantization.quantize_dynamic` pour le test int8 du coloriseur.
