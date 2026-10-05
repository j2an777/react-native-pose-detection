package com.posedetection.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.posedetection.LogCategory
import com.posedetection.PoseLog
import java.io.File

/**
 * Recording, kept apart from the session wiring it rides on.
 *
 * Recording swaps the stills use case out rather than adding a fourth: preview, analysis, capture
 * and video together is more than almost any phone will bind. A phone's own camera app splits photo
 * and video modes for the same reason. What never gets dropped is the analysis — a recording with
 * no detection is not what this package is for, so a camera that will not bind the three is told to
 * the caller instead of being quietly handed a blind session.
 */
internal class RecordingSession(
    val file: File,
    val hasAudio: Boolean,
)

internal class RecordingInProgress : IllegalStateException("a recording is already running")

internal class NotRecording : IllegalStateException("no recording is running")

internal class MicrophoneDenied :
    IllegalStateException("audio was asked for but the microphone permission is not granted")

/** True when the app holds RECORD_AUDIO right now. */
internal fun hasMicrophonePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * `withAudioEnabled` is guarded by the check above rather than by the annotation, which the linter
 * cannot see through.
 */
@SuppressLint("MissingPermission")
internal fun CameraSource.beginRecording(
    context: Context,
    audio: Boolean,
    onStopped: (Result<RecordedVideo>) -> Unit,
) {
    if (activeRecording != null) throw RecordingInProgress()
    if (audio && !hasMicrophonePermission(context)) throw MicrophoneDenied()

    // Rebinds into the video combination. Throws RecordingUnavailable when this camera will not
    // take preview, analysis and video at once, and the old session is restored by the caller.
    bindForRecording(true)

    val capture = videoCaptureOrNull() ?: throw RecordingUnavailable()
    val file = VideoFiles.create(context)
    val session = RecordingSession(file, audio)

    val pending =
        capture.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .let { if (audio) it.withAudioEnabled() else it }

    activeRecording =
        pending.start(ContextCompat.getMainExecutor(context)) { event ->
            if (event !is VideoRecordEvent.Finalize) return@start
            activeRecording = null
            // Back to stills whatever happened: leaving the session in video mode would make the
            // next takePhoto fail for a reason the caller never asked for.
            runCatching { bindForRecording(false) }
                .onFailure { PoseLog.warn(LogCategory.CAMERA) { "rebinding after a recording failed: ${it.message}" } }

            if (event.hasError()) {
                // The file is written up to the error on some codes, but a partial clip reported as
                // a success is worse than none, so it is removed.
                runCatching { session.file.delete() }
                onStopped(Result.failure(IllegalStateException("the recording failed with code ${event.error}")))
                return@start
            }
            onStopped(runCatching { VideoFiles.describe(session.file, session.hasAudio) })
        }

    PoseLog.info(LogCategory.CAMERA) { "recording started, audio=${if (audio) "on" else "off"}" }
}

/** The finalize callback is what settles the promise; this only asks for it. */
internal fun CameraSource.endRecording() {
    val recording = activeRecording ?: throw NotRecording()
    recording.stop()
}
