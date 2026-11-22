package com.bitchat.android.mosh.monitor

import java.io.File

/**
 * On-phone field log: event names and counts only, never locations, messages or IDs.
 * Lines are "<epoch ms> <name> <count>". Kept to [maxBytes], rotating into one ".1" file.
 * Shared by hand from settings ("Share diagnostics").
 */
class FieldLog(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    val file: File get() = File(dir, FILE_NAME)

    @Synchronized
    fun event(name: String, count: Int = 1): Boolean {
        if (!NAME.matches(name)) return false
        dir.mkdirs()
        val line = "${clock()} $name $count\n"
        if (file.exists() && file.length() + line.length > maxBytes) {
            val old = File(dir, "$FILE_NAME.1")
            old.delete()
            file.renameTo(old)
        }
        file.appendText(line)
        return true
    }

    companion object {
        const val FILE_NAME = "moshgrid-field.log"
        const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
        private val NAME = Regex("^[a-z_]+$")
    }
}
