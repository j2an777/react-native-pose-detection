package com.posedetection

import android.util.Log
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

internal enum class LogLevel(
    val rank: Int,
) {
    OFF(0),
    ERROR(1),
    WARN(2),
    INFO(3),
    DEBUG(4),
    TRACE(5),
    ;

    companion object {
        fun from(name: String?): LogLevel = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF
    }
}

internal enum class LogCategory {
    CAMERA,
    DETECTOR,
    ENGINE,
    TRIGGERS,
    CALIBRATION,
    OVERLAY,
    ;

    companion object {
        fun from(name: String?): LogCategory? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * A disabled call site costs one atomic read and an integer compare: the lambda is inlined and
 * never invoked, so nothing is built or allocated. Formatting outside the lambda turns that into
 * a per-frame cost at 30 fps. See docs/logging.md.
 *
 * Entries always go to Logcat, so native-only debugging works with no JavaScript listener
 * attached. They are additionally buffered for JavaScript while a listener is.
 */
internal object PoseLog {
    private const val TAG = "PoseDetection"
    private const val BITS_PER_CATEGORY = 3
    private const val CATEGORY_MASK = 0x7

    // 3 bits of level per category, packed into one int. One atomic read per call site. It is
    // `base` raised by every camera's `logLevel` prop, and written only under `levelLock`.
    private val mask = AtomicInteger(0)

    private val levelLock = Any()

    /** What `setLogLevel()` asked for. Guarded by `levelLock`. */
    private var base = 0

    /** Each camera's `logLevel` prop, raising the level for as long as that camera exists. */
    private val raises = IdentityHashMap<Any, Int>()

    /** Bounded and drop-oldest, like the frame buffer: a listener that stalls costs a fixed size. */
    private const val CAPACITY = 256
    private val entryLevels = arrayOfNulls<LogLevel>(CAPACITY)
    private val entryCategories = arrayOfNulls<LogCategory>(CAPACITY)
    private val entryMessages = arrayOfNulls<String>(CAPACITY)
    private val entryTimestamps = LongArray(CAPACITY)

    private val ring = Any()
    private var head = 0
    private var count = 0
    private var dropped = 0

    @Volatile
    private var streaming = false

    /**
     * One view flushes, whoever attached first. Without this every camera on screen would drain
     * the same buffer and each would receive an arbitrary share of the entries.
     */
    private var owner: Any? = null

    fun startStream() {
        synchronized(ring) {
            streaming = true
            head = 0
            count = 0
            dropped = 0
        }
    }

    fun stopStream() {
        synchronized(ring) {
            streaming = false
            count = 0
            dropped = 0
        }
    }

    val isStreaming: Boolean
        get() = streaming

    fun claimStream(candidate: Any): Boolean =
        synchronized(ring) {
            if (owner == null) owner = candidate
            owner === candidate
        }

    fun releaseStream(candidate: Any) {
        synchronized(ring) { if (owner === candidate) owner = null }
    }

    /**
     * Everything buffered since the last call, oldest first, plus how many were dropped. The maps
     * are built here rather than at the call site: a disabled channel must not build anything.
     */
    fun drain(into: MutableList<Map<String, Any?>>): Int {
        synchronized(ring) {
            val start = (head - count + CAPACITY) % CAPACITY
            for (index in 0 until count) {
                val slot = (start + index) % CAPACITY
                into.add(
                    mapOf(
                        "level" to (entryLevels[slot]?.name?.lowercase() ?: "info"),
                        "category" to (entryCategories[slot]?.name?.lowercase() ?: "engine"),
                        "message" to (entryMessages[slot] ?: ""),
                        "timestamp" to entryTimestamps[slot].toDouble(),
                    ),
                )
                entryMessages[slot] = null
            }

            val droppedCount = dropped
            head = 0
            count = 0
            dropped = 0
            return droppedCount
        }
    }

    private fun record(
        level: LogLevel,
        category: LogCategory,
        message: String,
    ) {
        synchronized(ring) {
            if (!streaming) return
            entryLevels[head] = level
            entryCategories[head] = category
            entryMessages[head] = message
            entryTimestamps[head] = android.os.SystemClock.elapsedRealtime()

            head = (head + 1) % CAPACITY
            if (count == CAPACITY) dropped += 1 else count += 1
        }
    }

    fun setLevel(level: LogLevel) {
        synchronized(levelLock) {
            base = packed(level)
            mask.set(combined())
        }
    }

    fun setLevels(levels: Map<LogCategory, LogLevel>) {
        synchronized(levelLock) {
            base = merged(base, levels)
            mask.set(combined())
        }
    }

    /**
     * A camera's `logLevel` prop: raises the level on top of `setLogLevel()` while the camera
     * exists, and gives it back when the prop goes or the camera does. `null` withdraws the raise
     * and leaves the global level alone rather than turning it off, which is what iOS needs because
     * Expo hands it every prop on a view's first update, set or not.
     */
    fun raise(
        owner: Any,
        raised: Int?,
    ) {
        synchronized(levelLock) {
            if (raised == null) raises.remove(owner) else raises[owner] = raised
            mask.set(combined())
        }
    }

    /** A level config as JavaScript sends it, a level or a map of categories to levels, as a mask. */
    fun levelMask(config: Any?): Int? =
        when (config) {
            is String -> packed(LogLevel.from(config))
            is Map<*, *> -> merged(0, levelsFrom(config))
            else -> null
        }

    /** A map of category names to level names; a name this version does not know is skipped. */
    fun levelsFrom(config: Map<*, *>): Map<LogCategory, LogLevel> =
        config.entries
            .mapNotNull { (key, value) ->
                val category = LogCategory.from(key as? String) ?: return@mapNotNull null
                category to LogLevel.from(value as? String)
            }.toMap()

    private fun packed(level: LogLevel): Int {
        var bits = 0
        for (category in LogCategory.entries) {
            bits = bits or (level.rank shl (category.ordinal * BITS_PER_CATEGORY))
        }
        return bits
    }

    private fun merged(
        start: Int,
        levels: Map<LogCategory, LogLevel>,
    ): Int {
        var bits = start
        for ((category, level) in levels) {
            val shift = category.ordinal * BITS_PER_CATEGORY
            bits = (bits and (CATEGORY_MASK shl shift).inv()) or (level.rank shl shift)
        }
        return bits
    }

    /** `base` with each category taken up to the highest level any camera raised it to. */
    private fun combined(): Int {
        var bits = base
        for (raised in raises.values) {
            for (category in LogCategory.entries) {
                val shift = category.ordinal * BITS_PER_CATEGORY
                val level = (raised shr shift) and CATEGORY_MASK
                if (level > ((bits shr shift) and CATEGORY_MASK)) {
                    bits = (bits and (CATEGORY_MASK shl shift).inv()) or (level shl shift)
                }
            }
        }
        return bits
    }

    fun isEnabled(
        level: LogLevel,
        category: LogCategory,
    ): Boolean {
        val shift = category.ordinal * BITS_PER_CATEGORY
        return ((mask.get() shr shift) and CATEGORY_MASK) >= level.rank
    }

    inline fun log(
        level: LogLevel,
        category: LogCategory,
        message: () -> String,
    ) {
        if (!isEnabled(level, category)) return
        emit(level, category, message())
    }

    inline fun error(
        category: LogCategory,
        message: () -> String,
    ) = log(LogLevel.ERROR, category, message)

    inline fun warn(
        category: LogCategory,
        message: () -> String,
    ) = log(LogLevel.WARN, category, message)

    inline fun info(
        category: LogCategory,
        message: () -> String,
    ) = log(LogLevel.INFO, category, message)

    inline fun debug(
        category: LogCategory,
        message: () -> String,
    ) = log(LogLevel.DEBUG, category, message)

    inline fun trace(
        category: LogCategory,
        message: () -> String,
    ) = log(LogLevel.TRACE, category, message)

    // Public because the inline functions above are, not because anything else should call it.
    fun emit(
        level: LogLevel,
        category: LogCategory,
        message: String,
    ) {
        record(level, category, message)

        val line = "[${category.name.lowercase()}] $message"
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, line)
            LogLevel.WARN -> Log.w(TAG, line)
            LogLevel.INFO -> Log.i(TAG, line)
            LogLevel.DEBUG -> Log.d(TAG, line)
            LogLevel.TRACE -> Log.v(TAG, line)
            LogLevel.OFF -> Unit
        }
    }
}
