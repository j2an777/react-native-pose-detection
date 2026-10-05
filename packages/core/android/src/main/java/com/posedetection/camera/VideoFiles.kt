package com.posedetection.camera

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.UUID

/** What a finished recording hands back to JavaScript. */
internal class RecordedVideo(
    val uri: String,
    val durationMs: Int,
    val size: Long,
    val hasAudio: Boolean,
) {
    val payload: Map<String, Any>
        get() =
            mapOf(
                "uri" to uri,
                "durationMs" to durationMs,
                "size" to size,
                "hasAudio" to hasAudio,
            )
}

/**
 * Where recordings land and how they are measured. The cache directory, like stills: this package
 * never asks for a storage permission, and nothing prunes these — the app owns the cleanup.
 */
internal object VideoFiles {
    private const val DIRECTORY = "pose-videos"

    fun create(context: Context): File {
        val directory = File(context.cacheDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("could not create the video directory")
        }
        return File(directory, "${UUID.randomUUID()}.mp4")
    }

    /**
     * Reads the written file back for its real duration rather than timing the recording in
     * Kotlin: the encoder decides where the last frame lands, and a stopwatch would be off by
     * whatever it took to flush.
     */
    fun describe(
        file: File,
        hasAudio: Boolean,
    ): RecordedVideo {
        val retriever = MediaMetadataRetriever()
        val durationMs =
            try {
                retriever.setDataSource(file.absolutePath)
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toIntOrNull() ?: 0
            } catch (error: Throwable) {
                // A readable file with an unreadable header is still a file the app can play.
                0
            } finally {
                runCatching { retriever.release() }
            }

        return RecordedVideo(
            uri = "file://${file.absolutePath}",
            durationMs = durationMs,
            size = file.length(),
            hasAudio = hasAudio,
        )
    }
}
