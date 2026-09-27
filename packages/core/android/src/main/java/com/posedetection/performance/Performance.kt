package com.posedetection.performance

import android.util.Size

internal enum class DeviceTier {
    LOW,
    MEDIUM,
    HIGH,
    ;

    fun nameForJs(): String = name.lowercase()
}

internal enum class Profile {
    AUTO,
    EFFICIENT,
    BALANCED,
    QUALITY,
    UNRESTRICTED,
    ;

    fun nameForJs(): String = name.lowercase()

    companion object {
        fun from(value: String?): Profile =
            when (value) {
                "efficient" -> EFFICIENT
                "balanced" -> BALANCED
                "quality" -> QUALITY
                "unrestricted" -> UNRESTRICTED
                else -> AUTO
            }
    }
}

internal enum class ThermalPolicy {
    ADAPTIVE,
    CRITICAL_ONLY,
    OFF,
    ;

    companion object {
        fun from(value: String?): ThermalPolicy =
            when (value) {
                "critical-only" -> CRITICAL_ONLY
                "off" -> OFF
                else -> ADAPTIVE
            }
    }
}

/** The OS states this package acts on, hotter last, so `ordinal` compares two readings. */
internal enum class ThermalState {
    NOMINAL,
    FAIR,
    SERIOUS,
    CRITICAL,
    ;

    fun nameForJs(): String = name.lowercase()
}

/** The public `LimitedBy`; each value is documented in `src/types/camera.ts`. */
internal enum class LimitedBy(
    val forJs: String,
) {
    CAMERA("camera"),
    DEVICE("device"),
    TARGET("target"),
    PROFILE("profile"),
    THERMAL("thermal"),
    LOW_POWER("lowPower"),
    IDLE("idle"),
    PAUSED("paused"),
}

/** With nobody in frame: [first] soon after they leave, [deep] once the phone is likely on a stand. */
internal data class IdleRates(
    val first: Int,
    val deep: Int,
) {
    /** The idle rate for this long without a pose, or null while a pose is recent. */
    fun rate(sinceLastPoseMs: Long): Int? =
        when {
            sinceLastPoseMs > DEEP_AFTER_MS -> deep
            sinceLastPoseMs > FIRST_AFTER_MS -> first
            else -> null
        }

    companion object {
        const val FIRST_AFTER_MS = 2_000L
        const val DEEP_AFTER_MS = 20_000L
    }
}

/** Duty is inference's share of time; under 1 it keeps MediaPipe's queue empty and the device cool. */
internal data class ProfileBudget(
    /** A ceiling below the camera's rate, or null for the camera's own. */
    val ceiling: Int?,
    val dutyNominal: Float,
    val dutyFair: Float,
    /** Null turns idle search off. */
    val idle: IdleRates?,
    /** False for the one profile that opts out of every heat response short of critical. */
    val heatBelowCritical: Boolean,
    val scaleAtFair: Boolean,
    /** Null follows the device's memory, otherwise a preset. */
    val preview: String?,
    val analysis: String,
)

internal object Budgets {
    private val AUTO =
        ProfileBudget(null, 0.85f, 0.70f, IdleRates(12, 5), true, false, null, "480p")
    private val QUALITY =
        ProfileBudget(null, 0.95f, 0.85f, IdleRates(15, 8), true, false, "1080p", "480p")
    private val BALANCED =
        ProfileBudget(24, 0.70f, 0.60f, IdleRates(12, 5), true, false, "720p", "480p")
    private val EFFICIENT =
        ProfileBudget(15, 0.50f, 0.40f, IdleRates(8, 3), true, true, "720p", "360p")
    private val UNRESTRICTED =
        ProfileBudget(null, 1.0f, 1.0f, null, false, false, "1080p", "480p")

    fun of(profile: Profile): ProfileBudget =
        when (profile) {
            Profile.AUTO -> AUTO
            Profile.QUALITY -> QUALITY
            Profile.BALANCED -> BALANCED
            Profile.EFFICIENT -> EFFICIENT
            Profile.UNRESTRICTED -> UNRESTRICTED
        }
}

/** Fixed for a session: nothing the governor learns may restart the camera. */
internal data class CameraGeometry(
    val preview: String,
    val analysis: String,
)

internal object GeometryResolver {
    /** Below 6: a phone sold as 6 GB reports a little under 6 GiB. */
    const val HIGH_MEMORY_GIB = 5.5f

    fun resolve(
        profile: Profile,
        requestedPreview: String,
        requestedAnalysis: String,
        memoryGiB: Float,
    ): CameraGeometry {
        val budget = Budgets.of(profile)
        val autoPreview = budget.preview ?: if (memoryGiB >= HIGH_MEMORY_GIB) "1080p" else "720p"
        return CameraGeometry(
            preview = if (requestedPreview == AUTO) autoPreview else requestedPreview,
            analysis = if (requestedAnalysis == AUTO) budget.analysis else requestedAnalysis,
        )
    }

    private const val AUTO = "auto"
}

internal data class RateRequest(
    val profile: Profile,
    val policy: ThermalPolicy,
    val thermal: ThermalState,
    val lowPower: Boolean,
    /** The camera's delivered rate, after it was pinned. */
    val cameraFps: Int,
    /** Median dispatch-to-result time, or 0 before anything was measured or cached. */
    val p50Ms: Float,
    val requestedFps: Int?,
)

internal data class RateDecision(
    val fps: Int,
    val limitedBy: LimitedBy,
) {
    val detectionPaused: Boolean get() = fps <= 0
}

/** The rate model from `guides/performance.md`, in one place so no two callers order it differently. */
internal object RateGovernor {
    /** Below this a skeleton reads as broken. Heat and idle may go lower; device capacity may not. */
    const val FLOOR_FPS = 10
    const val LOW_POWER_CEILING = 24
    const val FAIR_SCALE = 0.75f
    const val SERIOUS_DUTY = 0.5f

    /** The rate keeping inference busy [duty] of the time. Null when unmeasured: unknown is not slow. */
    fun capacity(
        duty: Float,
        p50Ms: Float,
    ): Int? {
        if (p50Ms <= 0f || !p50Ms.isFinite()) return null
        return kotlin.math.floor(duty * 1_000f / p50Ms).toInt()
    }

    fun effectiveHeat(request: RateRequest): ThermalState {
        val critical = if (request.thermal == ThermalState.CRITICAL) ThermalState.CRITICAL else ThermalState.NOMINAL
        return when (request.policy) {
            ThermalPolicy.OFF -> ThermalState.NOMINAL
            ThermalPolicy.CRITICAL_ONLY -> critical
            ThermalPolicy.ADAPTIVE -> if (Budgets.of(request.profile).heatBelowCritical) request.thermal else critical
        }
    }

    fun decide(request: RateRequest): RateDecision {
        val heat = effectiveHeat(request)
        if (heat == ThermalState.CRITICAL) return RateDecision(0, LimitedBy.THERMAL)

        val camera = maxOf(1, request.cameraFps)
        val requested = request.requestedFps
        var decision =
            if (requested != null) explicit(requested, camera, request.p50Ms) else governed(request, camera, heat)

        if (heat == ThermalState.SERIOUS) {
            var halved = maxOf(1, camera / 2)
            capacity(SERIOUS_DUTY, request.p50Ms)?.let { halved = minOf(halved, maxOf(1, it)) }
            if (halved < decision.fps) decision = RateDecision(halved, LimitedBy.THERMAL)
        }

        // An explicit target or `unrestricted` has already decided; the OS throttles those itself.
        if (request.lowPower &&
            requested == null &&
            request.profile != Profile.UNRESTRICTED &&
            decision.fps > LOW_POWER_CEILING
        ) {
            decision = RateDecision(LOW_POWER_CEILING, LimitedBy.LOW_POWER)
        }
        return decision
    }

    /** Capped at device capacity: feeding MediaPipe faster only queues frames and adds latency. */
    private fun explicit(
        requested: Int,
        camera: Int,
        p50Ms: Float,
    ): RateDecision {
        var fps = maxOf(1, minOf(requested, camera))
        var reason = if (requested > camera) LimitedBy.CAMERA else LimitedBy.TARGET
        val capacity = capacity(1f, p50Ms)
        if (capacity != null && capacity < fps) {
            fps = maxOf(1, capacity)
            reason = LimitedBy.DEVICE
        }
        return RateDecision(fps, reason)
    }

    private fun governed(
        request: RateRequest,
        camera: Int,
        heat: ThermalState,
    ): RateDecision {
        val budget = Budgets.of(request.profile)
        val ceiling = minOf(camera, budget.ceiling ?: camera)
        var fps = ceiling
        var reason = if (ceiling < camera) LimitedBy.PROFILE else LimitedBy.CAMERA

        val nominal = capacity(budget.dutyNominal, request.p50Ms)
        if (nominal != null && nominal < fps) {
            fps = maxOf(nominal, minOf(FLOOR_FPS, ceiling))
            reason = LimitedBy.DEVICE
        }

        if (heat == ThermalState.FAIR) {
            val fair = capacity(budget.dutyFair, request.p50Ms)
            if (fair != null && fair < fps) {
                fps = maxOf(1, fair)
                reason = LimitedBy.THERMAL
            }
            if (budget.scaleAtFair) {
                fps = maxOf(1, (fps * FAIR_SCALE).toInt())
                reason = LimitedBy.THERMAL
            }
        }
        return RateDecision(fps, reason)
    }
}

/** Heat is adopted at once, cooling only after [COOL_DOWN_MS], at the warmest level seen meanwhile. */
internal class ThermalHysteresis {
    var state = ThermalState.NOMINAL
        private set

    private var coolerSinceMs = 0L
    private var coolerCandidate = ThermalState.NOMINAL

    /** True when [state] changed. */
    fun update(
        raw: ThermalState,
        nowMs: Long,
    ): Boolean {
        if (raw.ordinal >= state.ordinal) {
            coolerSinceMs = 0L
            if (raw.ordinal == state.ordinal) return false
            state = raw
            return true
        }

        if (coolerSinceMs == 0L) {
            coolerSinceMs = nowMs
            coolerCandidate = raw
            return false
        }
        if (raw.ordinal > coolerCandidate.ordinal) coolerCandidate = raw
        if (nowMs - coolerSinceMs < COOL_DOWN_MS) return false
        state = coolerCandidate
        coolerSinceMs = 0L
        return true
    }

    companion object {
        const val COOL_DOWN_MS = 30_000L
    }
}

/** The tier is only a label reported to the app; it drives nothing. */
internal object AutoTuner {
    const val HIGH_TIER_MAX_P50_MS = 22f
    const val MEDIUM_TIER_MAX_P50_MS = 45f

    fun tier(p50Ms: Float): DeviceTier =
        when {
            p50Ms <= HIGH_TIER_MAX_P50_MS -> DeviceTier.HIGH
            p50Ms <= MEDIUM_TIER_MAX_P50_MS -> DeviceTier.MEDIUM
            else -> DeviceTier.LOW
        }
}

internal fun Size.longestSide(): Int = maxOf(width, height)
