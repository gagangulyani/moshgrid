package com.bitchat.android.mosh

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** What a socials code holds: a display name and links to public profiles. Nothing else. */
data class SocialsCard(val name: String, val links: List<String>)

/**
 * The socials code: `https://moshgrid.vercel.app/s#1.<name>.<link>…`, each part base64url (no padding)
 * of UTF-8. Any phone camera opens it as a web page; the part after # never reaches the server.
 * Decoding fails closed: anything outside the rules gives null. See docs/MOSH_SOCIALS.md.
 */
object SocialsCode {
    const val PREFIX = "https://moshgrid.vercel.app/s#"
    const val VERSION = "1"
    const val MAX_NAME = 40
    const val MAX_LINKS = 6
    const val MAX_LINK = 200
    /** Longest code text accepted: well above the longest valid code (about 1,900 characters). */
    const val MAX_CODE = 2200

    // https, a host with at least one dot, optional port and path. Printable ASCII only, no userinfo.
    private val LINK = Regex("^https://([A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}(?::[0-9]{1,5})?(?:[/?#][\\x21-\\x7E]*)?$")
    private val BAD_CHARS = Regex("[\"<>\\\\^`{|}]")

    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    /** A link the user typed, tidied, or null when it can't go in a code. "instagram.com/ana" gets https:// added. */
    fun cleanLink(raw: String): String? {
        val t = raw.trim()
        val s = if ("://" in t || t.isEmpty()) t else "https://$t"
        if (s.isEmpty() || s.length > MAX_LINK) return null
        if (!LINK.matches(s) || BAD_CHARS.containsMatchIn(s)) return null
        return s
    }

    fun cleanName(raw: String): String =
        raw.filterNot { Character.isISOControl(it) }.trim().take(MAX_NAME)

    /** The code text for [name] and [links]; links that can't go in a code are left out. Null if none are left. */
    fun encode(name: String, links: List<String>): String? {
        val ok = links.mapNotNull(::cleanLink).distinct().take(MAX_LINKS)
        if (ok.isEmpty()) return null
        return PREFIX + (listOf(VERSION) + (listOf(cleanName(name)) + ok).map { enc.encodeToString(it.toByteArray(Charsets.UTF_8)) })
            .joinToString(".")
    }

    /** Reads a scanned or opened code. Null unless every part follows the rules. */
    fun decode(text: String): SocialsCard? {
        val t = text.trim()
        if (!t.startsWith(PREFIX) || t.length > MAX_CODE) return null
        val parts = t.substring(PREFIX.length).split('.')
        if (parts.size < 3 || parts.size > 2 + MAX_LINKS || parts[0] != VERSION) return null
        val strings = parts.drop(1).map { part ->
            if (!part.matches(Regex("^[A-Za-z0-9_-]*$"))) return null
            utf8(runCatching { dec.decode(part) }.getOrNull() ?: return null) ?: return null
        }
        val name = strings[0]
        if (name.length > MAX_NAME || name.any { Character.isISOControl(it) } || name != name.trim()) return null
        val links = strings.drop(1)
        if (links.any { cleanLink(it) != it } || links.distinct().size != links.size) return null
        return SocialsCard(name, links)
    }

    private fun utf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()

    private val SITES = mapOf(
        "instagram.com" to "Instagram", "open.spotify.com" to "Spotify", "spotify.com" to "Spotify",
        "x.com" to "X", "twitter.com" to "X", "snapchat.com" to "Snapchat", "tiktok.com" to "TikTok",
        "youtube.com" to "YouTube", "youtu.be" to "YouTube", "soundcloud.com" to "SoundCloud",
        "linkedin.com" to "LinkedIn", "facebook.com" to "Facebook", "threads.net" to "Threads",
        "bsky.app" to "Bluesky", "music.apple.com" to "Apple Music", "github.com" to "GitHub",
        "t.me" to "Telegram", "linktr.ee" to "Linktree", "bandcamp.com" to "Bandcamp",
    )

    fun host(link: String): String =
        link.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringBefore(':').lowercase().removePrefix("www.").removePrefix("m.")

    /** "Instagram" for instagram.com links, otherwise the site's address. */
    fun label(link: String): String = SITES[host(link)] ?: host(link)

    /** The link without "https://" and "www.", as shown on screen. */
    fun shown(link: String): String = link.removePrefix("https://").removePrefix("www.").trimEnd('/')
}
