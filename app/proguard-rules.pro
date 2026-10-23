# OpenClip release shrink rules (rebrand P7).
# Manifest components (MainActivity) are kept automatically by AGP.
# SPDX-License-Identifier: GPL-3.0-or-later

# JNI contract (ARCHITECTURE.md, scripts/check_jni_symbols.sh): the VM
# resolves EngineHost.n* by class+method name at runtime — never rename.
-keep class dev.openclip.engine.EngineHost { *; }
# Any other native entry point (safety net).
-keepclasseswithmembernames class * { native <methods>; }

# ONNX Runtime Android (M8 super-resolution): native-backed, ships its
# own consumer rules, but pin the API surface explicitly so an R8
# upgrade can never strip a model-load path.
-keep class ai.onnxruntime.** { *; }
