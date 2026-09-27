package com.posedetection

import android.os.SystemClock
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

/** Format inside the lambda: a disabled call site then builds nothing. See docs/logging.md. */
internal object PoseLog {
    private const val TAG = "PoseDetection"
    private const val BITS_PER_CATEGORY = 3
    private const val CATEGORY_MASK = 0x7

    // 3 bits of level per category: base raised by every camera's prop, written under levelLock.
    private val mask = AtomicInteger(0)

    private val levelLock = Any()

    /** What setLogLevel() asked for; guarded by levelLock. */
    private var base = 0

    private val raises = IdentityHashMap<Any, Int>()

    /** Drop-oldest, so a listener that stalls costs a fixed size. */
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

    /** The one camera that flushes, or cameras would split the entries; null: the module flushes. */
    private var owner: Any? = null

    const val FLUSH_MS = 250L

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

    fun claimStream(candidate: Any) {
        synchronized(ring) { if (owner == null) owner = candidate }
    }

    fun releaseStream(candidate: Any) {
        synchronized(ring) { if (owner === candidate) owner = null }
    }

    /** A camera passes itself and claims the flush if free; the module passes null and needs it free. */
    fun takeBatch(flusher: Any?): List<Map<String, Any?>>? {
        if (!streaming) return null
        synchronized(ring) {
            if (flusher == null && owner != null) return null
            if (flusher != null) {
                if (owner == null) owner = flusher
                if (owner !== flusher) return null
            }
            if (count == 0) return null

            val batch = ArrayList<Map<String, Any?>>(count + 1)
            val start = (head - count + CAPACITY) % CAPACITY
            // A warn entry, not a field, so a listener that reads only entries still sees the loss.
            if (dropped > 0) {
                batch.add(
                    mapOf(
                        "level" to "warn",
                        "category" to "engine",
                        "message" to "$dropped log entries were dropped before this batch",
                        "timestamp" to entryTimestamps[start].toDouble(),
                        "data" to mapOf("droppedCount" to dropped),
                    ),
                )
            }
            for (index in 0 until count) {
                val slot = (start + index) % CAPACITY
                batch.add(
                    mapOf(
                        "level" to (entryLevels[slot]?.name?.lowercase() ?: "info"),
                        "category" to (entryCategories[slot]?.name?.lowercase() ?: "engine"),
                        "message" to (entryMessages[slot] ?: ""),
                        "timestamp" to entryTimestamps[slot].toDouble(),
                    ),
                )
                entryMessages[slot] = null
            }

            head = 0
            count = 0
            dropped = 0
            return batch
        }
    }

    /** Apart from [emit] so a test can reach it without Logcat. */
    fun record(
        level: LogLevel,
        category: LogCategory,
        message: String,
        timestampMs: Long,
    ) {
        synchronized(ring) {
            if (!streaming) return
            entryLevels[head] = level
            entryCategories[head] = category
            entryMessages[head] = message
            entryTimestamps[head] = timestampMs

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

    /** A camera's logLevel prop, on top of setLogLevel(); null withdraws it rather than turning logs off. */
    fun raise(
        owner: Any,
        raised: Int?,
    ) {
        synchronized(levelLock) {
            if (raised == null) raises.remove(owner) else raises[owner] = raised
            mask.set(combined())
        }
    }

    fun levelMask(config: Any?): Int? =
        when (config) {
            is String -> packed(LogLevel.from(config))
            is Map<*, *> -> merged(0, levelsFrom(config))
            else -> null
        }

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

    // Public only because the inline functions above call it.
    fun emit(
        level: LogLevel,
        category: LogCategory,
        message: String,
    ) {
        if (streaming) record(level, category, message, SystemClock.elapsedRealtime())

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
