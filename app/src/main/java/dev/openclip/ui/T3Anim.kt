// OpenClip T3 — animation productive : images cles, camera, pistes,
// etiquettes, feuille d'exposition, sidecar anim.json (app-side).
// Noyau d'echantillonnage = moteur (anim.h) ; ici : fiches Kotlin,
// courbes Bezier (fonction pure), sidecar v1, feuille derivee, CSV.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.openclip.ui

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.pow

/** T3 §1 : image cle d'un calque (memes bornes que le moteur). */
data class AnimKey(
    val cel: Int,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val angle: Float = 0f,
    val opacity: Float = 1f,
    val ease: String = "linear"
)

/** T3 §2 : image cle camera (centre regarde + zoom + angle). */
data class CameraKey(
    val cel: Int,
    val x: Float = 0f,
    val y: Float = 0f,
    val zoom: Float = 1f,
    val angle: Float = 0f,
    val ease: String = "linear"
)

/** T3 §3 : etiquette nommee sur la regle temps. */
data class AnimLabel(val cel: Int, val txt: String, val color: String = "#FFC845")

/** T3 §3 : etat d'une piste (miroir de nTrackState). */
data class TrackInfo(val name: String, val frames: Int, val visible: Boolean)

val AnimEases = listOf("linear", "ease-in", "ease-out", "smooth", "step")

/** T3 §1 : applique une aisance a t in [0,1] (miroir de applyEase). */
fun animEaseApply(ease: String, t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    return when (ease) {
        "step" -> 0f
        "ease-in" -> t * t
        "ease-out" -> 1f - (1f - t) * (1f - t)
        "smooth" -> t * t * (3f - 2f * t)
        else -> t
    }
}

/**
 * T3 §1 : editeur de courbe maison — Bezier cubique 2 poignees
 * (c1x,c1y,c2x,c2y in [0,1]) echantillonne en table 64 pas (fonction
 * pure, 0 JNI, testee). Sortie : facteur d'aisance par pas.
 */
fun bezierEaseTable(c1x: Float, c1y: Float, c2x: Float, c2y: Float, steps: Int = 64): FloatArray {
    fun bx(t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * c1x + 3f * u * t * t * c2x + t * t * t
    }
    fun by(t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * c1y + 3f * u * t * t * c2y + t * t * t
    }
    val out = FloatArray(steps + 1)
    for (i in 0..steps) {
        val target = i.toFloat() / steps
        // Inverse x(t) = target par dichotomie (monotone en x).
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (bx(mid) < target) lo = mid else hi = mid
        }
        out[i] = by((lo + hi) / 2f).coerceIn(0f, 1f)
    }
    return out
}

/** T3 §1 : echantillonne une piste de cles a un cellulo (miroir moteur, overlay). */
fun sampleAnimKeys(keys: List<AnimKey>, cel: Float): AnimKey? {
    if (keys.isEmpty()) return null
    val s = keys.sortedBy { it.cel }
    if (s.size == 1 || cel <= s.first().cel) return s.first().copy()
    if (cel >= s.last().cel) return s.last().copy()
    var i = 0
    while (i + 1 < s.size && s[i + 1].cel <= cel) i++
    val a = s[i]
    val b = s[i + 1]
    val span = (b.cel - a.cel).toFloat()
    val t = if (span <= 0f) 0f else (cel - a.cel) / span
    val e = animEaseApply(a.ease, t)
    fun angleLerp(from: Float, to: Float, k: Float): Float {
        var d = (to - from) % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return from + d * k
    }
    return AnimKey(
        cel = cel.toInt(),
        dx = a.dx + (b.dx - a.dx) * e,
        dy = a.dy + (b.dy - a.dy) * e,
        scale = a.scale + (b.scale - a.scale) * e,
        angle = angleLerp(a.angle, b.angle, e),
        opacity = a.opacity + (b.opacity - a.opacity) * e,
        ease = a.ease
    )
}

/** T3 : JSON d'une piste de cles calque (enveloppe nSetLayerAnim). */
fun animKeysToJson(keys: List<AnimKey>): String {
    val arr = JSONArray()
    for (k in keys.sortedBy { it.cel }) {
        arr.put(
            JSONObject()
                .put("cel", k.cel)
                .put("dx", k.dx.toDouble())
                .put("dy", k.dy.toDouble())
                .put("scale", k.scale.toDouble())
                .put("angle", k.angle.toDouble())
                .put("opacity", k.opacity.toDouble())
                .put("ease", k.ease)
        )
    }
    return JSONObject().put("keys", arr).toString()
}

fun animKeysFromJson(raw: String): List<AnimKey> {
    val out = mutableListOf<AnimKey>()
    try {
        val arr = JSONObject(raw).optJSONArray("keys") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                AnimKey(
                    cel = o.optInt("cel", 0).coerceAtLeast(0),
                    dx = o.optDouble("dx", 0.0).toFloat(),
                    dy = o.optDouble("dy", 0.0).toFloat(),
                    scale = o.optDouble("scale", 1.0).toFloat().coerceIn(0.1f, 8f),
                    angle = o.optDouble("angle", 0.0).toFloat(),
                    opacity = o.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
                    ease = o.optString("ease", "linear").takeIf { it in AnimEases } ?: "linear"
                )
            )
        }
    } catch (_: Exception) {
    }
    return out.sortedBy { it.cel }
}

/** T3 : JSON d'une piste camera (enveloppe nSetCameraTrack). */
fun cameraKeysToJson(keys: List<CameraKey>): String {
    val arr = JSONArray()
    for (k in keys.sortedBy { it.cel }) {
        arr.put(
            JSONObject()
                .put("cel", k.cel)
                .put("x", k.x.toDouble())
                .put("y", k.y.toDouble())
                .put("zoom", k.zoom.toDouble())
                .put("angle", k.angle.toDouble())
                .put("ease", k.ease)
        )
    }
    return JSONObject().put("keys", arr).toString()
}

fun cameraKeysFromJson(raw: String): List<CameraKey> {
    val out = mutableListOf<CameraKey>()
    try {
        val arr = JSONObject(raw).optJSONArray("keys") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                CameraKey(
                    cel = o.optInt("cel", 0).coerceAtLeast(0),
                    x = o.optDouble("x", 0.0).toFloat(),
                    y = o.optDouble("y", 0.0).toFloat(),
                    zoom = o.optDouble("zoom", 1.0).toFloat().coerceIn(0.25f, 8f),
                    angle = o.optDouble("angle", 0.0).toFloat(),
                    ease = o.optString("ease", "linear").takeIf { it in AnimEases } ?: "linear"
                )
            )
        }
    } catch (_: Exception) {
    }
    return out.sortedBy { it.cel }
}

fun trackStateFromJson(raw: String): Pair<Int, List<TrackInfo>> {
    try {
        val o = JSONObject(raw)
        val active = o.optInt("active", 0)
        val arr = o.optJSONArray("tracks") ?: return Pair(0, emptyList())
        val list = mutableListOf<TrackInfo>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            list.add(
                TrackInfo(
                    name = t.optString("name", ""),
                    frames = t.optInt("frames", 0),
                    visible = t.optBoolean("visible", true)
                )
            )
        }
        return Pair(active, list)
    } catch (_: Exception) {
        return Pair(0, emptyList())
    }
}

/**
 * T3 §3.5 : sidecar anim.json v1 (un seul fichier : cles + camera +
 * pistes + etiquettes). Absent/illisible = 1 piste + pas d'etiquettes
 * (degrade propre). Cap 64 Ko (au-dela = ignore + bandeau).
 */
object AnimSidecar {
    const val MAX_BYTES = 65536

    data class Doc(
        val tracks: List<TrackInfo> = listOf(TrackInfo("", 1, true)),
        val labels: List<AnimLabel> = emptyList(),
        val keys: Map<Int, List<AnimKey>> = emptyMap(),
        val camera: List<CameraKey> = emptyList()
    )

    fun toJson(doc: Doc): String {
        val o = JSONObject()
        o.put("v", 1)
        val ta = JSONArray()
        for (t in doc.tracks) {
            ta.put(
                JSONObject()
                    .put("name", t.name)
                    .put("frames", t.frames)
                    .put("visible", t.visible)
            )
        }
        o.put("tracks", ta)
        val la = JSONArray()
        for (l in doc.labels) {
            la.put(
                JSONObject().put("cel", l.cel).put("txt", l.txt).put("color", l.color)
            )
        }
        o.put("labels", la)
        val ka = JSONObject()
        for ((idx, keys) in doc.keys) ka.put(idx.toString(), JSONObject(animKeysToJson(keys)))
        o.put("keys", ka)
        o.put("camera", JSONObject(cameraKeysToJson(doc.camera)))
        return o.toString()
    }

    fun fromJson(raw: String): Doc {
        if (raw.toByteArray().size > MAX_BYTES) return Doc()
        try {
            val o = JSONObject(raw)
            val tracks = mutableListOf<TrackInfo>()
            val ta = o.optJSONArray("tracks")
            if (ta != null) {
                for (i in 0 until ta.length()) {
                    val t = ta.optJSONObject(i) ?: continue
                    tracks.add(
                        TrackInfo(
                            t.optString("name", "").take(24),
                            t.optInt("frames", 1).coerceAtLeast(1),
                            t.optBoolean("visible", true)
                        )
                    )
                }
            }
            val labels = mutableListOf<AnimLabel>()
            val la = o.optJSONArray("labels")
            if (la != null) {
                for (i in 0 until la.length()) {
                    val l = la.optJSONObject(i) ?: continue
                    labels.add(
                        AnimLabel(
                            l.optInt("cel", 0).coerceAtLeast(0),
                            l.optString("txt", "").take(24),
                            l.optString("color", "#FFC845")
                        )
                    )
                }
            }
            val keys = mutableMapOf<Int, List<AnimKey>>()
            val ka = o.optJSONObject("keys")
            if (ka != null) {
                for (k in ka.keys()) {
                    val idx = k.toIntOrNull() ?: continue
                    val inner = ka.optJSONObject(k) ?: continue
                    keys[idx] = animKeysFromJson(inner.toString())
                }
            }
            // Camera stored as {"keys":[...]} envelope.
            val camRaw = o.optJSONObject("camera")?.toString() ?: "{\"keys\":[]}"
            return Doc(
                tracks = tracks.ifEmpty { listOf(TrackInfo("", 1, true)) },
                labels = labels,
                keys = keys,
                camera = cameraKeysFromJson(camRaw)
            )
        } catch (_: Exception) {
            return Doc()
        }
    }

    fun save(file: File, doc: Doc): Boolean {
        return try {
            if (file.parentFile?.exists() == false) file.parentFile?.mkdirs()
            file.writeText(toJson(doc))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun load(file: File): Doc {
        return try {
            if (!file.exists()) return Doc()
            if (file.length() > MAX_BYTES) return Doc()
            fromJson(file.readText())
        } catch (_: Exception) {
            Doc()
        }
    }
}

/** T3 §3 palier a : une ligne de la feuille d'exposition. */
data class ExposureRow(
    val cel: Int,
    val hold: Int,
    val keyedLayers: List<Int>,
    val cameraKey: Boolean,
    val label: String?
)

/** T3 : feuille d'exposition derivee (vue lecture seule, 0 JNI). */
fun buildExposure(
    frameCount: Int,
    holds: Map<Int, Int>,
    keyCounts: Map<Int, Int>,
    cameraCels: Set<Int>,
    labels: List<AnimLabel>
): List<ExposureRow> {
    val labByCel = labels.associate { it.cel to it.txt }
    return (0 until frameCount).map { cel ->
        ExposureRow(
            cel = cel,
            hold = (holds[cel] ?: 1).coerceIn(1, 32),
            keyedLayers = keyCounts.filter { it.value > 0 }.keys.sorted(),
            cameraKey = cameraCels.contains(cel),
            label = labByCel[cel]
        )
    }
}

/** T3 : export CSV de la feuille (SAF, en-tete + N lignes). */
fun exposureCsv(rows: List<ExposureRow>, trackCount: Int = 1): String {
    val sb = StringBuilder()
    sb.append("cel,hold,keys,camera,label,tracks\n")
    for (r in rows) {
        val keys = r.keyedLayers.joinToString("+") { "L$it" }
        val lab = (r.label ?: "").replace(',', ';').replace('\n', ' ')
        sb.append("${r.cel},${r.hold},$keys,${if (r.cameraKey) 1 else 0},$lab,$trackCount\n")
    }
    return sb.toString()
}

/** T3 : collecte le doc d'animation live vers le sidecar (pistes =
 * miroir nTrackState, cles/camera relues moteur, etiquettes session). */
fun collectT3Doc(surface: EngineSurface, layerCount: Int): AnimSidecar.Doc {
    val (_, tracks) = trackStateFromJson(surface.trackState())
    val keys = mutableMapOf<Int, List<AnimKey>>()
    for (i in 0 until layerCount) {
        val k = animKeysFromJson(surface.layerAnim(i))
        if (k.isNotEmpty()) keys[i] = k
    }
    return AnimSidecar.Doc(
        tracks = tracks.ifEmpty { listOf(TrackInfo("", 1, true)) },
        labels = surface.t3Labels,
        keys = keys,
        camera = cameraKeysFromJson(surface.cameraTrack())
    )
}

/** T3 : repousse un sidecar vers le moteur (cles/camera/pistes
 * nom+visibilite, etiquettes session). Les comptes de cellulos ne
 * sont jamais restaures (pixels seuls = ORA). */
fun applyT3Doc(surface: EngineSurface, doc: AnimSidecar.Doc) {
    val want = doc.tracks
    // Assure assez de pistes (refus au-dela de 4, annonce par le compte).
    var n = surface.trackCount()
    var guard = 0
    while (n < want.size && guard++ < 4) {
        if (surface.addTrack("") < 0) break
        n = surface.trackCount()
    }
    for (i in want.indices) {
        if (i >= surface.trackCount()) break
        if (want[i].name.isNotEmpty()) surface.setTrackName(i, want[i].name)
        surface.setTrackVisible(i, want[i].visible)
    }
    surface.setActiveTrack(0)
    for ((idx, keys) in doc.keys) {
        if (keys.isEmpty()) surface.setLayerAnim(idx, null)
        else surface.setLayerAnim(idx, animKeysToJson(keys))
    }
    if (doc.camera.isEmpty()) surface.setCameraTrack(null)
    else surface.setCameraTrack(cameraKeysToJson(doc.camera))
    surface.t3Labels = doc.labels
}

/** T3 : sondes pour les tests instrumentes (jamais de pixels seuls). */
object T3TestHooks {
    /** Derniere feuille construite (apercu, pas de pixels). */
    var lastExposure: List<ExposureRow> = emptyList()

    /** Dernier CSV exporte (apercu). */
    var lastCsv: String = ""

    /** Derniere table Bezier 64 pas (courbe). */
    var lastBezierTable: FloatArray? = null

    /** Sonde spec §3.6 : expose holds + badges (vue derivee). */
    var exposureForTest: () -> List<ExposureRow> = { lastExposure }
}
