package com.bitchat.android.mosh

/**
 * Per-maker steps so the phone does not kill MoshGrid in a pocket. Phone makers common in India
 * (Xiaomi, Oppo, Vivo, Realme, OnePlus) stop background apps aggressively.
 */
object BatteryGuide {
    private val generic = listOf(
        "Settings > Battery: set MoshGrid to Unrestricted (or turn off battery optimisation).",
        "Keep MoshGrid's notification on; it is what keeps the mesh running.",
    )

    fun stepsFor(manufacturer: String): List<String> = when (manufacturer.lowercase().trim()) {
        "xiaomi", "redmi", "poco" -> listOf(
            "Settings > Apps > Manage apps > MoshGrid > Autostart: on.",
            "Same screen > Battery saver: No restrictions.",
            "Open recent apps, long-press MoshGrid and tap the lock icon.",
        )
        "oppo", "realme" -> listOf(
            "Settings > Battery > More settings: turn off Optimise battery use for MoshGrid.",
            "Settings > Apps > MoshGrid > Battery usage: allow background activity and Auto launch.",
            "Lock MoshGrid in recent apps (pull the card down).",
        )
        "vivo", "iqoo" -> listOf(
            "Settings > Battery > Background power consumption: allow MoshGrid.",
            "i Manager > App manager > Autostart: on for MoshGrid.",
            "Lock MoshGrid in recent apps.",
        )
        "oneplus" -> listOf(
            "Settings > Battery > Battery optimisation > MoshGrid: Don't optimise.",
            "Settings > Apps > MoshGrid > Battery: allow background activity.",
            "Lock MoshGrid in recent apps.",
        )
        "samsung" -> listOf(
            "Settings > Battery > Background usage limits: remove MoshGrid from Sleeping and Deep sleeping apps.",
            "Settings > Apps > MoshGrid > Battery: Unrestricted.",
        )
        else -> generic
    }
}
