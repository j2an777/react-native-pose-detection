package com.posedetection.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.posedetection.LogCategory
import com.posedetection.PoseLog

/** Where a calibration outlives the process. An interface so the logic can be tested without a Context. */
internal interface CalibrationStore {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )
}

/**
 * What this device's inference costs, in the order `guides/performance.md` describes it.
 *
 * 1. Measured: the median dispatch-to-result time over the last 60 frames that had a pose, first
 *    published after 15, so a session knows what its device costs within about half a second.
 * 2. Cached, so the second launch starts where the first one finished.
 *
 * Before either, the governor runs at the camera's rate. An unknown device is not a slow one, and
 * half a second at the camera's rate costs less than a start that looks slow to the person
 * watching. The memory and core probe that used to guess a rate now only names a tier until a
 * measurement replaces it.
 */
internal class Calibrator(
    private val store: CalibrationStore,
    /** Hardware model and OS version, the part of the cache key that is not the model file. */
    private val deviceKey: String,
    private val memoryGiB: () -> Float,
    private val cores: () -> Int,
) {
    enum class Phase {
        CALIBRATING,
        SETTLED,
        CACHED,
    }

    enum class Source {
        STATIC,
        MEASURED,
        CACHE,
    }

    var tier = DeviceTier.MEDIUM
        private set
    var phase = Phase.CALIBRATING
        private set
    var source = Source.STATIC
        private set

    /** The published median, or 0 before one exists. What the governor divides by. */
    var p50InferenceMs = 0f
        private set

    /** What the GPU check decided for this device and model last time, or null if it never ran here. */
    var gpuVerdict: Boolean? = null
        private set

    private val samples = FloatArray(WINDOW)
    private val scratch = FloatArray(WINDOW)
    private var sampleCount = 0
    private var cursor = 0
    private var sinceMedian = 0
    private var lastChangeMs = 0L
    private var modelFileName: String? = null

    /**
     * Loads what this device and model are known to cost. Runs on every session start and does
     * nothing when the model has not changed: a camera restart is not a new device, and throwing
     * the measurement away there sent the rate back to a guess every time a prop rebound the session.
     */
    fun start(modelFileName: String) {
        if (modelFileName == this.modelFileName) return
        this.modelFileName = modelFileName
        sampleCount = 0
        cursor = 0
        sinceMedian = 0
        lastChangeMs = 0L
        p50InferenceMs = 0f
        gpuVerdict = null

        val cached = readCache(modelFileName)
        if (cached != null) {
            gpuVerdict = cached.gpu
            if (cached.p50Ms > 0f) {
                tier = cached.tier
                p50InferenceMs = cached.p50Ms
                source = Source.CACHE
                phase = Phase.CACHED
                PoseLog.info(LogCategory.CALIBRATION) {
                    "starting from the cached ${tier.nameForJs()} tier, p50 ${p50InferenceMs}ms"
                }
                return
            }
        }

        tier = staticTier()
        source = Source.STATIC
        phase = Phase.CALIBRATING
        PoseLog.info(
            LogCategory.CALIBRATION,
        ) { "nothing measured yet, the device suggests the ${tier.nameForJs()} tier" }
    }

    /**
     * One frame's cost, dispatch to result. That span breathes with load: a rate the device cannot
     * hold shows up as queue wait long before it shows up as heat, which is what closes the loop.
     * Returns true when the published median or the tier moved, or when the measurement settled,
     * which the caller answers by re-running the governor and persisting.
     */
    fun record(
        inferenceMs: Float,
        nowMs: Long,
    ): Boolean {
        if (inferenceMs <= 0f || !inferenceMs.isFinite()) return false

        samples[cursor] = inferenceMs
        cursor = (cursor + 1) % WINDOW
        if (sampleCount < WINDOW) sampleCount += 1
        sinceMedian += 1

        // The first estimate lands at FIRST_ESTIMATE samples, then one every MEDIAN_STRIDE.
        if (sampleCount < FIRST_ESTIMATE || sinceMedian < MEDIAN_STRIDE) return false
        sinceMedian = 0
        val candidate = median()

        // Hysteresis: a rate that just moved is given time to show what it costs before it moves
        // again, or a device sitting between two answers oscillates between them forever. The
        // window itself is kept: inference cost does not become untrue because the rate changed.
        if (lastChangeMs != 0L && nowMs - lastChangeMs < COOLDOWN_MS) return false

        val nextTier = AutoTuner.tier(candidate)
        val moved =
            p50InferenceMs == 0f ||
                nextTier != tier ||
                kotlin.math.abs(implied(candidate) - implied(p50InferenceMs)) > DEADBAND_FPS

        if (!moved) {
            // Inside the deadband across a whole window with nowhere to move is what settled means.
            if (phase == Phase.SETTLED || sampleCount < WINDOW) return false
            phase = Phase.SETTLED
            source = Source.MEASURED
            PoseLog.info(LogCategory.CALIBRATION) { "settled at ${tier.nameForJs()}, p50 ${p50InferenceMs}ms" }
            return true
        }

        p50InferenceMs = candidate
        tier = nextTier
        source = Source.MEASURED
        phase = Phase.CALIBRATING
        lastChangeMs = nowMs
        PoseLog.info(LogCategory.CALIBRATION) { "p50 ${candidate}ms, ${tier.nameForJs()} tier" }
        return true
    }

    /** Only a settled, measured answer is worth persisting. A guess is not worth a second launch. */
    fun persist() {
        val model = modelFileName ?: return
        if (phase != Phase.SETTLED || source != Source.MEASURED) return
        write(model)
    }

    /** The GPU check is the slow half of building a landmarker, so its answer outlives the process. */
    fun recordGpuVerdict(usable: Boolean) {
        gpuVerdict = usable
        val model = modelFileName ?: return
        write(model)
    }

    private fun staticTier(): DeviceTier {
        val memory = memoryGiB()
        val cores = cores()
        return when {
            cores >= HIGH_CORES && memory >= HIGH_MEMORY_GIB -> DeviceTier.HIGH
            cores >= MEDIUM_CORES && memory >= MEDIUM_MEMORY_GIB -> DeviceTier.MEDIUM
            else -> DeviceTier.LOW
        }
    }

    private fun median(): Float {
        val count = minOf(sampleCount, WINDOW)
        System.arraycopy(samples, 0, scratch, 0, count)
        scratch.sort(0, count)
        return scratch[count / 2]
    }

    private fun write(model: String) {
        val gpu =
            when (gpuVerdict) {
                true -> "gpu"
                false -> "cpu"
                null -> ""
            }
        store.write(cacheKey(model), "${tier.name}|$p50InferenceMs|$gpu")
    }

    private data class Cached(
        val tier: DeviceTier,
        val p50Ms: Float,
        val gpu: Boolean?,
    )

    /** `TIER|p50|gpu`, where p50 is 0 when only the GPU check has run. */
    private fun readCache(model: String): Cached? {
        val stored = store.read(cacheKey(model)) ?: return null
        val parts = stored.split('|')
        if (parts.size != CACHE_FIELDS) return null
        val tier = DeviceTier.entries.firstOrNull { it.name == parts[0] } ?: return null
        val p50 = parts[1].toFloatOrNull()?.takeIf { it.isFinite() && it > 0f } ?: 0f
        val gpu =
            when (parts[2]) {
                "gpu" -> true
                "cpu" -> false
                else -> null
            }
        return Cached(tier, p50, gpu)
    }

    /**
     * Device, OS version, model and MediaPipe version. Any of them changing invalidates by producing
     * a different key rather than by anything having to notice and clear the old one. Versioned: the
     * first version cached a rate under a model that no longer exists.
     */
    private fun cacheKey(model: String): String = "v2|$deviceKey|$model|${MediaPipeVersion.PINNED}"

    companion object {
        /** Two seconds at 30 fps, which is long enough for a median to mean something. */
        const val WINDOW = 60

        /** Half a second at 30 fps: enough that one slow frame cannot decide it, soon enough to matter. */
        const val FIRST_ESTIMATE = 15

        /**
         * The median is a copy and a sort, so it is refreshed every quarter window rather than
         * every frame. Inference cost does not change in fifteen frames; recomputing inside that
         * span is work on the hot path for a number that comes out the same.
         */
        const val MEDIAN_STRIDE = 15

        const val COOLDOWN_MS = 3_000L

        /** Moves smaller than this, in frames per second at the default duty, are noise. */
        const val DEADBAND_FPS = 2

        /** Every rate past this is "faster than the camera", so differences above it mean nothing. */
        const val COMPARISON_CEILING_FPS = 60
        private const val COMPARISON_DUTY = 0.85f

        const val HIGH_CORES = 8
        const val MEDIUM_CORES = 6
        const val HIGH_MEMORY_GIB = 5.5f
        const val MEDIUM_MEMORY_GIB = 3.5f

        private const val CACHE_FIELDS = 3

        /** The rate a median implies at the default duty, capped where more stops meaning anything. */
        fun implied(p50Ms: Float): Int =
            minOf(RateGovernor.capacity(COMPARISON_DUTY, p50Ms) ?: COMPARISON_CEILING_FPS, COMPARISON_CEILING_FPS)
    }
}

/** The MediaPipe release build.gradle pins. `wireParity.test.ts` keeps the two in step. */
internal object MediaPipeVersion {
    const val PINNED = "0.10.35"
}

/** A calibration backed by this app's shared preferences, for this device. */
internal fun calibratorFor(context: Context): Calibrator {
    val preferences = context.getSharedPreferences("react-native-pose-detection", Context.MODE_PRIVATE)
    val store =
        object : CalibrationStore {
            override fun read(key: String): String? = preferences.getString(key, null)

            override fun write(
                key: String,
                value: String,
            ) {
                preferences.edit().putString(key, value).apply()
            }
        }
    return Calibrator(
        store = store,
        deviceKey = "${Build.MODEL}|${Build.VERSION.SDK_INT}",
        memoryGiB = { deviceMemoryGiB(context) },
        cores = { Runtime.getRuntime().availableProcessors() },
    )
}

/** What the device reports, which is a little under what it was sold as. */
internal fun deviceMemoryGiB(context: Context): Float {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0f
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return info.totalMem / BYTES_PER_GIB
}

internal const val BYTES_PER_GIB = 1_073_741_824f

/**
 * How Android's heat readings map onto the four states the governor acts on. Kept apart from
 * [ThermalMonitor] so the mapping is testable without a PowerManager.
 *
 * Android names more states than iOS, and shifts them one hotter: its LIGHT is iOS's `nominal`
 * range, and SEVERE is what `serious` means. Mapping SEVERE to critical once paused detection on
 * phones that sit there for minutes of ordinary camera use.
 */
internal object ThermalMapping {
    /** Forecast headroom past these counts as the state named, before the status catches up. */
    const val HEADROOM_FAIR = 0.85f
    const val HEADROOM_SERIOUS = 0.95f

    fun fromStatus(status: Int): ThermalState =
        when {
            status >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalState.CRITICAL
            status >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.SERIOUS
            status >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.FAIR
            else -> ThermalState.NOMINAL
        }

    /** Null for NaN, which Android answers when asked too often: the last reading stands. */
    fun fromHeadroom(headroom: Float): ThermalState? =
        when {
            headroom.isNaN() -> null
            headroom >= HEADROOM_SERIOUS -> ThermalState.SERIOUS
            headroom >= HEADROOM_FAIR -> ThermalState.FAIR
            else -> ThermalState.NOMINAL
        }
}

/**
 * The OS thermal status, the forecast of where it is heading, and Battery Saver. Read on a timer on
 * the main thread, once a second, which is also the most often the forecast may be asked for.
 */
internal class ThermalMonitor(
    private val context: Context,
) {
    private val power: PowerManager? by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private var lastHeadroom = ThermalState.NOMINAL

    fun readThermal(): ThermalState {
        val power = power ?: return ThermalState.NOMINAL
        // No thermal API before Q. Reporting NOMINAL is honest: nothing was read.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.NOMINAL
        val status = ThermalMapping.fromStatus(power.currentThermalStatus)

        // The forecast lets the governor back off before the status says the device is throttling.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val headroom = runCatching { power.getThermalHeadroom(HEADROOM_FORECAST_SECONDS) }.getOrDefault(Float.NaN)
            ThermalMapping.fromHeadroom(headroom)?.let { lastHeadroom = it }
            if (lastHeadroom.ordinal > status.ordinal) return lastHeadroom
        }
        return status
    }

    /** Battery Saver is the person asking for less work, read apart from heat. */
    fun readLowPower(): Boolean = runCatching { power?.isPowerSaveMode ?: false }.getOrDefault(false)

    companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        private const val HEADROOM_FORECAST_SECONDS = 10
    }
}
