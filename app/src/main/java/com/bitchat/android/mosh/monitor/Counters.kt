package com.bitchat.android.mosh.monitor

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-memory event counters for busy paths (packets, peers, relay failures). Flushed to the field
 * log once an hour instead of writing to disk per packet. Names only; never IDs or content.
 */
class Counters {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val seenKeys = ConcurrentHashMap.newKeySet<String>()

    fun inc(name: String) { counts.getOrPut(name) { AtomicInteger() }.incrementAndGet() }

    /** Count [name] once per [key] (e.g. once per message), however often the caller sees it. */
    fun once(name: String, key: String): Boolean {
        if (!seenKeys.add("$name:$key")) return false
        inc(name)
        return true
    }

    fun snapshot(): Map<String, Int> = counts.mapValues { it.value.get() }

    @Synchronized
    fun flushTo(log: FieldLog) {
        for ((name, n) in counts) {
            val v = n.getAndSet(0)
            if (v > 0) log.event(name, v)
        }
        if (seenKeys.size > 10_000) seenKeys.clear()
    }

    companion object {
        val shared = Counters()
    }
}
