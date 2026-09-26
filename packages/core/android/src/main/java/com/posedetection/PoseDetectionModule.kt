package com.posedetection

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.posedetection.camera.Facing
import com.posedetection.detector.StaticDetection
import com.posedetection.detector.StaticDetectionError
import com.posedetection.detector.StaticOptions
import com.posedetection.engine.FrameStreams
import com.posedetection.engine.OneEuroFilter
import com.posedetection.engine.parseData
import com.posedetection.engine.parseSelection
import com.posedetection.engine.parseTriggers
import com.posedetection.export.ExportCancelled
import com.posedetection.export.PoseExport
import com.posedetection.performance.Profile
import com.posedetection.performance.ThermalPolicy
import com.posedetection.view.OverlayConfig
import com.posedetection.view.PoseCameraView
import com.posedetection.view.parseOverlay
import expo.modules.interfaces.permissions.PermissionsResponse
import expo.modules.interfaces.permissions.PermissionsStatus
import expo.modules.kotlin.functions.Queues
import expo.modules.kotlin.jni.NativeArrayBuffer
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class PoseDetectionModule : Module() {
    private fun hasCameraPermission(): Boolean {
        val context = appContext.reactContext ?: return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun definition() =
        ModuleDefinition {
            Name("PoseDetection")

            Function("setLogLevel") { config: Any? -> applyLogLevel(config) }

            // The buffer is global because the level mask is. A view runs the flush, see PoseLog.
            Function("startLogStream") { PoseLog.startStream() }
            Function("stopLogStream") { PoseLog.stopStream() }

            Events("onVideoProgress", "onExportProgress")

            // Both handed to this package's own thread rather than run on Expo's, which every
            // module in the app shares: a video job there held all of them up while it ran.
            AsyncFunction(
                "detectOnImage",
            ) { uri: String, options: Map<String, Any?>?, promise: expo.modules.kotlin.Promise ->
                val context = appContext.reactContext
                if (context == null) {
                    promise.reject(ErrorCode.DETECTION_FAILED.name, "The module has no context.", null)
                    return@AsyncFunction
                }
                StaticDetection.executor.execute {
                    runCatching {
                        StaticDetection.detectImage(
                            context = context,
                            uri = uri,
                            options = StaticOptions.forImage(options),
                            angleJoints = angleJointsFrom(options),
                            selection = selectionFrom(options),
                        )
                    }.onSuccess { promise.resolve(NativeArrayBuffer.wrap(it)) }
                        .onFailure { rejectFileJob(promise, it) }
                }
            }

            AsyncFunction(
                "detectOnVideo",
            ) { uri: String, options: Map<String, Any?>?, taskId: Int, promise: expo.modules.kotlin.Promise ->
                val context = appContext.reactContext
                if (context == null) {
                    promise.reject(ErrorCode.DETECTION_FAILED.name, "The module has no context.", null)
                    return@AsyncFunction
                }
                StaticDetection.executor.execute {
                    runCatching {
                        StaticDetection.detectVideo(
                            context = context,
                            uri = uri,
                            options = StaticOptions.forVideo(options),
                            angleJoints = angleJointsFrom(options),
                            selection = selectionFrom(options),
                            taskId = taskId,
                            onProgress = { progress ->
                                sendEvent("onVideoProgress", mapOf("taskId" to taskId, "progress" to progress))
                            },
                        )
                    }.onSuccess { promise.resolve(NativeArrayBuffer.wrap(it)) }
                        .onFailure { rejectFileJob(promise, it) }
                }
            }

            Function("cancelDetectOnVideo") { taskId: Int -> StaticDetection.cancel(taskId) }

            // Handed to the export executor rather than run on Expo's, which is what keeps a long
            // export off any thread the camera cares about. See PoseExport for the other rules.
            AsyncFunction(
                "exportPose",
            ) { uri: String, options: Map<String, Any?>?, taskId: Int, promise: expo.modules.kotlin.Promise ->
                val context = appContext.reactContext
                if (context == null) {
                    promise.reject(ErrorCode.EXPORT_FAILED.name, "The module has no context.", null)
                    return@AsyncFunction
                }
                PoseExport.executor.execute {
                    runCatching {
                        PoseExport.run(context, uri, options, taskId) { progress ->
                            sendEvent("onExportProgress", mapOf("taskId" to taskId, "progress" to progress))
                        }
                    }.onSuccess { promise.resolve(it.payload()) }
                        .onFailure {
                            val cancelled = it is ExportCancelled
                            promise.reject(
                                if (cancelled) "EXPORT_CANCELLED" else "EXPORT_FAILED",
                                it.message ?: "the export failed",
                                null,
                            )
                        }
                }
            }

            Function("cancelExportPose") { taskId: Int -> PoseExport.cancel(taskId) }

            // Synchronous and on the JavaScript thread that calls them, the same shape as iOS, where
            // a view function would run on main. Each reads a view's frames through the id
            // `<PoseCamera>` gave it, and an id with no view behind it reads as an empty buffer.
            Function("drainFrames") { streamId: Int -> NativeArrayBuffer.wrap(FrameStreams.drain(streamId)) }
            Function("snapshotFrame") { streamId: Int -> NativeArrayBuffer.wrap(FrameStreams.snapshot(streamId)) }
            // An unknown or spent ticket is an empty buffer, which is the documented contract.
            Function("takeTriggerSnapshot") { streamId: Int, snapshotId: Int ->
                NativeArrayBuffer.wrap(FrameStreams.takeSnapshot(streamId, snapshotId))
            }
            Function("readLiveState") { streamId: Int -> FrameStreams.live(streamId) }

            AsyncFunction("getCameraPermission") { promise: expo.modules.kotlin.Promise ->
                val permissions = appContext.permissions
                if (permissions == null) {
                    // Readable without a manager, which is what makes this the useful fallback:
                    // an app can still find out where it stands, it just cannot ask from here.
                    // Without the manager only "granted or not" is knowable, and reporting a
                    // refusal as UNDETERMINED is the honest half of that: it says "ask to find out".
                    val status =
                        if (hasCameraPermission()) PermissionsStatus.GRANTED else PermissionsStatus.UNDETERMINED
                    promise.resolve(permissionResult(status, canAskAgain = true))
                    return@AsyncFunction
                }
                permissions.getPermissions(
                    { result -> promise.resolve(toPermissionResult(result)) },
                    Manifest.permission.CAMERA,
                )
            }

            AsyncFunction("requestCameraPermission") { promise: expo.modules.kotlin.Promise ->
                val permissions = appContext.permissions
                if (permissions == null) {
                    promise.reject(
                        "PERMISSIONS_UNAVAILABLE",
                        "No permissions manager is registered. Expo modules are not fully installed " +
                            "in this app, see the installation guide.",
                        null,
                    )
                    return@AsyncFunction
                }
                permissions.askForPermissions(
                    { result -> promise.resolve(toPermissionResult(result)) },
                    Manifest.permission.CAMERA,
                )
            }

            View(PoseCameraView::class) {
                Events(
                    "onReady",
                    "onError",
                    "onCameraChange",
                    "onFrames",
                    "onTrigger",
                    "onPerformanceChange",
                    "onLog",
                )

                Prop("facing") { view: PoseCameraView, value: String? ->
                    view.setFacing(value ?: "auto")
                }
                Prop("delegate") { view: PoseCameraView, value: String? ->
                    view.setDelegate(value ?: "auto")
                }
                Prop("active") { view: PoseCameraView, value: Boolean? ->
                    view.setActive(value ?: true)
                }
                Prop("detection") { view: PoseCameraView, value: Boolean? ->
                    view.setDetection(value ?: true)
                }
                Prop("maxPoses") { view: PoseCameraView, value: Int? ->
                    view.setMaxPoses(value ?: 1)
                }
                Prop("minConfidence") { view: PoseCameraView, value: Double? ->
                    view.setMinConfidence(value)
                }
                Prop("resolution") { view: PoseCameraView, value: String? ->
                    view.setResolution(value ?: "auto")
                }
                Prop("analysisResolution") { view: PoseCameraView, value: String? ->
                    view.setAnalysisResolution(value ?: "auto")
                }
                // `Any?` and a cast, like `overlay`: a star-projected Map has no registered type
                // converter, and that failure would only show up on a device.
                Prop("data") { view: PoseCameraView, value: Any? ->
                    view.setData(parseData(value as? Map<*, *>))
                }
                // Resolved by JavaScript, in ANGLE_JOINT_NAMES order. Re-deriving the set here
                // would be a second implementation of one rule, and a way for them to disagree.
                Prop("angleJoints") { view: PoseCameraView, value: List<String>? ->
                    view.setAngleJoints(value?.toTypedArray() ?: emptyArray())
                }
                Prop("selection") { view: PoseCameraView, value: List<String>? ->
                    view.setSelection(value?.let(::parseSelection))
                }
                Prop("profile") { view: PoseCameraView, value: String? ->
                    view.setProfile(Profile.from(value))
                }
                Prop("streamId") { view: PoseCameraView, value: Int? ->
                    view.setStreamId(value)
                }
                Prop("targetFps") { view: PoseCameraView, value: Int? ->
                    view.setTargetFps(value)
                }
                Prop("thermalPolicy") { view: PoseCameraView, value: String? ->
                    view.setThermalPolicy(ThermalPolicy.from(value))
                }
                Prop("smoothing") { view: PoseCameraView, value: Any? ->
                    when (value) {
                        true -> {
                            view.setSmoothing(true, OneEuroFilter.DEFAULT_MIN_CUTOFF, OneEuroFilter.DEFAULT_BETA)
                        }

                        // Absent is off. JavaScript resolves `'auto'` against `maxPoses` and always
                        // sends the answer, and one pose is already smoothed inside MediaPipe.
                        null, false -> {
                            view.setSmoothing(false, OneEuroFilter.DEFAULT_MIN_CUTOFF, OneEuroFilter.DEFAULT_BETA)
                        }

                        is Map<*, *> -> {
                            view.setSmoothing(
                                true,
                                (value["minCutoff"] as? Number)?.toFloat() ?: OneEuroFilter.DEFAULT_MIN_CUTOFF,
                                (value["beta"] as? Number)?.toFloat() ?: OneEuroFilter.DEFAULT_BETA,
                            )
                        }

                        else -> {
                            view.setSmoothing(false, OneEuroFilter.DEFAULT_MIN_CUTOFF, OneEuroFilter.DEFAULT_BETA)
                        }
                    }
                }
                Prop("logLevel") { _: PoseCameraView, value: Any? ->
                    // The level is global, and the prop is a convenience for setting it per camera.
                    applyLogLevel(value)
                }
                Prop("triggers") { view: PoseCameraView, value: Any? ->
                    view.setTriggers(parseTriggers(value as? List<*>))
                }
                Prop("overlay") { view: PoseCameraView, value: Any? ->
                    when (value) {
                        null, true -> view.setOverlay(true, OverlayConfig())
                        false -> view.setOverlay(false, OverlayConfig())
                        is Map<*, *> -> view.setOverlay(true, parseOverlay(value))
                        else -> view.setOverlay(true, OverlayConfig())
                    }
                }

                OnViewDidUpdateProps { view: PoseCameraView ->
                    view.onPropsUpdated()
                }

                OnViewDestroys { view: PoseCameraView ->
                    view.releaseEverything()
                }

                // Every one of these touches the capture session, which CameraX requires on the main
                // thread. That is also the serial queue all session state lives on, see CameraSource.
                AsyncFunction("switchCamera") { view: PoseCameraView, promise: expo.modules.kotlin.Promise ->
                    view.switchCamera(
                        onDone = { promise.resolve(null) },
                        onFailed = { message -> promise.reject("CAMERA_SWITCH_FAILED", message, null) },
                    )
                }.runOnQueue(Queues.MAIN)

                AsyncFunction(
                    "setFacing",
                ) { view: PoseCameraView, facing: String, promise: expo.modules.kotlin.Promise ->
                    val target = if (facing == "back") Facing.BACK else Facing.FRONT
                    view.setFacingInternal(
                        target = target,
                        onDone = { promise.resolve(null) },
                        onFailed = { message -> promise.reject("CAMERA_SWITCH_FAILED", message, null) },
                    )
                }.runOnQueue(Queues.MAIN)

                AsyncFunction("pause") { view: PoseCameraView -> view.pauseCamera() }.runOnQueue(Queues.MAIN)
                AsyncFunction("resume") { view: PoseCameraView -> view.resumeCamera() }.runOnQueue(Queues.MAIN)

                AsyncFunction("startDetection") { view: PoseCameraView ->
                    view.startDetection()
                }.runOnQueue(Queues.MAIN)

                AsyncFunction("stopDetection") { view: PoseCameraView ->
                    view.stopDetection()
                }.runOnQueue(Queues.MAIN)

                AsyncFunction("setOverlayEnabled") { view: PoseCameraView, enabled: Boolean ->
                    view.setOverlayEnabled(enabled)
                }.runOnQueue(Queues.MAIN)

                AsyncFunction("getState") { view: PoseCameraView ->
                    view.currentState()
                }.runOnQueue(Queues.MAIN)

                // On main, because the calibration it reads is main-thread state. Frames and the
                // live rate are read on the JavaScript thread instead, see FrameStreams.
                AsyncFunction("getProfile") { view: PoseCameraView ->
                    view.profileState()
                }.runOnQueue(Queues.MAIN)

                AsyncFunction("setProfile") { view: PoseCameraView, profile: String ->
                    view.applyProfile(Profile.from(profile))
                }.runOnQueue(Queues.MAIN)
            }
        }
}

/**
 * A file that could not be read rejects with its decode code, a missing model with
 * `MODEL_NOT_FOUND`, and anything that failed after the file was read with `DETECTION_FAILED`:
 * three different things for the app to tell its user, where one code used to cover all of them.
 */
private fun rejectFileJob(
    promise: expo.modules.kotlin.Promise,
    error: Throwable,
) {
    val code = (error as? StaticDetectionError)?.code ?: ErrorCode.DETECTION_FAILED
    promise.reject(code.name, error.message ?: "detection failed", null)
}

/** Resolved by JavaScript for the live path, and passed the same way here. */
internal fun angleJointsFrom(options: Map<String, Any?>?): Array<String> {
    val raw = options?.get("angleJoints") as? List<*> ?: return Skeleton.ANGLE_JOINT_NAMES
    return raw.mapNotNull { it as? String }.toTypedArray()
}

internal fun selectionFrom(options: Map<String, Any?>?): IntArray? {
    val raw = options?.get("select") as? List<*> ?: return null
    return parseSelection(raw.mapNotNull { it as? String })
}

internal fun applyLogLevel(config: Any?) {
    when (config) {
        is String -> {
            PoseLog.setLevel(LogLevel.from(config))
        }

        is Map<*, *> -> {
            PoseLog.setLevels(
                config.entries
                    .mapNotNull { (key, value) ->
                        val category = LogCategory.from(key as? String) ?: return@mapNotNull null
                        category to LogLevel.from(value as? String)
                    }.toMap(),
            )
        }

        else -> {
            PoseLog.setLevel(LogLevel.OFF)
        }
    }
}
