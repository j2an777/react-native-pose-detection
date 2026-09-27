package com.posedetection.export

import android.content.Context
import android.net.Uri
import com.posedetection.detector.StillConfidence
import com.posedetection.view.OverlayConfig
import com.posedetection.view.parseOverlay
import java.io.File

/** Defaults as documented in guides/files.md. */
internal class ExportOptions(
    val overlay: OverlayConfig,
    val drawOverlay: Boolean,
    val maxPoses: Int,
    val minConfidence: Float,
    /** Between samples the last pose is held, as the live overlay holds one between inferences. */
    val sampleFps: Int,
    /** Long edge of the output, or 0 for the source's own size. */
    val maxSize: Int,
    val directory: File,
    val fileName: String,
    /** Still images only. */
    val quality: Int,
) {
    companion object {
        private const val DEFAULT_SAMPLE_FPS = 10
        private const val MIN_MAX_SIZE = 120

        fun parse(
            context: Context,
            raw: Map<*, *>?,
            sourceName: String,
        ): ExportOptions {
            val overlayRaw = raw?.get("overlay")
            val maxPoses = count(raw?.get("maxPoses"), 1, 5)
            return ExportOptions(
                overlay = (overlayRaw as? Map<*, *>)?.let { parseOverlay(it) } ?: OverlayConfig(),
                drawOverlay = overlayRaw as? Boolean ?: true,
                maxPoses = maxPoses,
                minConfidence =
                    ((raw?.get("minConfidence") as? Number)?.toFloat() ?: StillConfidence.forMaxPoses(maxPoses))
                        .coerceIn(0.1f, 1f),
                sampleFps = count(raw?.get("fps"), DEFAULT_SAMPLE_FPS, 60),
                maxSize = maxSize(raw?.get("maxSize")),
                directory = directory(context, raw?.get("directory") as? String),
                fileName = fileName(raw?.get("fileName") as? String, sourceName),
                quality =
                    ((raw?.get("quality") as? Number)?.toFloat() ?: 0.9f)
                        .coerceIn(0.1f, 1f)
                        .let { (it * 100).toInt() },
            )
        }

        /** Cache by default: an export is derived data, and filesDir would keep copies nobody asked for. */
        private fun directory(
            context: Context,
            raw: String?,
        ): File {
            val base =
                when (raw) {
                    null, "cache" -> {
                        context.cacheDir
                    }

                    "documents" -> {
                        context.filesDir
                    }

                    else -> {
                        val parsed = Uri.parse(raw)
                        File(if (parsed.scheme == "file") parsed.path ?: raw else raw)
                    }
                }
            base.mkdirs()
            sweepStaging(base)
            return base
        }

        /** Exports run serially on one executor, so nothing swept here belongs to a running one. */
        private fun sweepStaging(base: File) {
            base
                .listFiles { file ->
                    file.name.endsWith("${PoseExport.STAGING_SUFFIX}.mp4") ||
                        file.name.endsWith("${PoseExport.STAGING_SUFFIX}.jpg")
                }?.forEach { it.delete() }
        }

        /** Sanitized: a slash in the name would write outside the chosen directory. */
        private fun fileName(
            raw: String?,
            sourceName: String,
        ): String {
            val candidate = raw?.takeIf { it.isNotEmpty() } ?: "$sourceName-pose"
            val cleaned =
                candidate
                    .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' || it == '.' }
                    .trim()
            return cleaned.ifEmpty { "pose-export" }
        }

        private fun maxSize(value: Any?): Int {
            val size = (value as? Number)?.toInt() ?: return ExportCanvas.DEFAULT_MAX_SIZE
            return if (size <= 0) 0 else size.coerceAtLeast(MIN_MAX_SIZE)
        }

        private fun count(
            value: Any?,
            fallback: Int,
            limit: Int,
        ): Int = (value as? Number)?.toInt()?.coerceIn(1, limit) ?: fallback
    }
}

internal class ExportSummary(
    val file: File,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val frameCount: Int,
    val posesFound: Int,
) {
    fun payload(): Map<String, Any> =
        mapOf(
            "uri" to Uri.fromFile(file).toString(),
            "width" to width,
            "height" to height,
            "durationMs" to durationMs,
            "frameCount" to frameCount,
            "posesFound" to posesFound,
        )
}

internal class ExportError(
    message: String,
) : Exception(message)

internal class ExportCancelled : Exception("the export was cancelled")
