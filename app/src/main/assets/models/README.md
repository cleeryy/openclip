# Bundled models

Every file here is a third-party artefact. It is vendored only when its
licence allows redistribution and the exact provenance is recorded.

## `docden_bc16_fp32.onnx` — scan cleanup (document denoise)

| Field | Value |
| --- | --- |
| Upstream | <https://huggingface.co/jere1882/document-image-denoising> (`residual_unet_bc16/model_fp32.onnx`) |
| Source code | <https://github.com/jere1882/DocDenoising> |
| Licence | MIT — `docden-LICENSE.txt`, also SPDX `MIT` on the GitHub repository |
| SHA-256 | `93d7eac43b27cbdcdfa6997a344695e885b85897e477820246bda43eac1c8659` |
| Size | 1 936 165 bytes |
| Opset / IR | 17 / 8 |
| Input | `noisy` `[batch, 3, h, w]` float32 in `[0,1]`, `h` and `w` multiples of 8 |
| Output | `denoised` same shape, same range |
| Training domain | **scanned documents** (not paintings) |
| Measured | 368 ms at 512² fp32 on x86 (2 threads); ~1.76 s at 1024² |

The UI labels the feature “Scan cleanup (AI, documents)” and offers a mix
factor, so artwork is never silently pushed through a document model.

## `super-resolution-10.onnx` — ESPCN ×3 (pre-existing)

Apache-2.0, <https://huggingface.co/onnxmodelzoo/super-resolution-10>.
See the M8 notes in `ARCHITECTURE.md`; the licence text ships next to the
file.
