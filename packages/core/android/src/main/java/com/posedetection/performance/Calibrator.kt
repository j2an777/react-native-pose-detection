package com.posedetection.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.posedetection.LogCategory
import com.posedetection.PoseLog

/** An interface so the calibrator is testable without a Context. */
internal interface CalibrationStore {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )
}

/** This device's measured inference cost, cached per device and model. See `guides/performance.md`. */
internal class Calibrator(
    private val store: CalibrationStore,
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

    /** The published median, or 0 before one exists. */
    var p50InferenceMs = 0f
        private set

    /** The GPU check's latest verdict for this device and model, or null if it never ran. */
    var gpuVerdict: Boolean? = null
        private set

    private val samples = FloatArray(WINDOW)
    private val scratch = FloatArray(WINDOW)
    private var sampleCount = 0
    private var cursor = 0
    private var sinceMedian = 0
    private var lastChangeMs = 0L
    private var modelFileName: String? = null

    /** A no-op for the same model: a camera restart is not a new device, so the measurement stands. */
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
     * Dispatch to result, so a rate the device cannot hold shows here as queue wait before heat.
     * True when the median or tier moved or the measurement settled: re-run the governor, persist.
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

        if (sampleCount < FIRST_ESTIMATE || sinceMedian < MEDIAN_STRIDE) return false
        sinceMedian = 0
        val candidate = median()

        // Cooldown, or a device between two answers oscillates. The samples stay valid across it.
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

    /** Only a settled measurement: a guess must not seed the next launch. */
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

    /** Keeps a stored verdict this calibrator never saw: a file job may have recorded it. */
    private fun write(model: String) {
        val verdict = gpuVerdict ?: readCache(model)?.gpu
        store.write(cacheKey(model), "${tier.name}|$p50InferenceMs|${verdictName(verdict)}")
    }

    fun cachedGpu(model: String): Boolean? = readCache(model)?.gpu

    /** A file job's verdict, stored without touching this calibrator or the camera's measurement. */
    fun storeGpu(
        usable: Boolean,
        model: String,
    ) {
        val cached = readCache(model)
        val tier = cached?.tier ?: DeviceTier.MEDIUM
        store.write(cacheKey(model), "${tier.name}|${cached?.p50Ms ?: 0f}|${verdictName(usable)}")
    }

    private fun verdictName(verdict: Boolean?): String =
        when (verdict) {
            true -> "gpu"
            false -> "cpu"
            null -> ""
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

    /** Any part changing makes a new key, so nothing has to clear the old one. `v2` retired v1's rates. */
    private fun cacheKey(model: String): String = "v2|$deviceKey|$model|${MediaPipeVersion.PINNED}"

    companion object {
        /** Two seconds at 30 fps, which is long enough for a median to mean something. */
        const val WINDOW = 60

        /** Half a second at 30 fps: one slow frame cannot decide it, yet it lands soon. */
        const val FIRST_ESTIMATE = 15

        /** A median is a copy and a sort, so it refreshes every quarter window, not every frame. */
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

        fun implied(p50Ms: Float): Int =
            minOf(RateGovernor.capacity(COMPARISON_DUTY, p50Ms) ?: COMPARISON_CEILING_FPS, COMPARISON_CEILING_FPS)
    }
}

/** The MediaPipe release build.gradle pins. `wireParity.test.ts` keeps the two in step. */
internal object MediaPipeVersion {
    const val PINNED = "0.10.35"
}

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

internal fun deviceMemoryGiB(context: Context): Float {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0f
    val info = ActivityManager.MemoryInfo()
    manager.getMemoryInfo(info)
    return info.totalMem / BYTES_PER_GIB
}

internal const val BYTES_PER_GIB = 1_073_741_824f

/** Android runs one state hotter than iOS: LIGHT is still `nominal`, and SEVERE only `serious`. */
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

/** Read once a second on main, which is also the most often Android allows the headroom forecast. */
internal class ThermalMonitor(
    private val context: Context,
) {
    private val power: PowerManager? by lazy { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    private var lastHeadroom = ThermalState.NOMINAL

    fun readThermal(): ThermalState {
        val power = power ?: return ThermalState.NOMINAL
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalState.NOMINAL
        val status = ThermalMapping.fromStatus(power.currentThermalStatus)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val headroom = runCatching { power.getThermalHeadroom(HEADROOM_FORECAST_SECONDS) }.getOrDefault(Float.NaN)
            ThermalMapping.fromHeadroom(headroom)?.let { lastHeadroom = it }
            if (lastHeadroom.ordinal > status.ordinal) return lastHeadroom
        }
        return status
    }

    fun readLowPower(): Boolean = runCatching { power?.isPowerSaveMode ?: false }.getOrDefault(false)

    companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        private const val HEADROOM_FORECAST_SECONDS = 10
    }
}
