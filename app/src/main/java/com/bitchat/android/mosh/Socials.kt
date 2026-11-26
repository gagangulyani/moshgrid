package com.bitchat.android.mosh

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Someone whose socials code I saved, and when. */
data class MetPerson(val card: SocialsCard, val atMs: Long)

/** Where the socials book is kept. */
interface SocialsPersistence {
    fun load(): Triple<List<String>, Set<String>, List<MetPerson>>
    fun save(links: List<String>, hidden: Set<String>, met: List<MetPerson>)
}

/**
 * My links (and which of them my code leaves out), and the people whose codes I saved.
 * Only links that [SocialsCode.cleanLink] accepts are kept. Nothing here is ever sent anywhere:
 * the code is shown on screen and read by someone else's camera. See docs/MOSH_SOCIALS.md.
 */
class SocialsBook(private val persistence: SocialsPersistence? = null) {
    private val _links = MutableStateFlow<List<String>>(emptyList())
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    private val _met = MutableStateFlow<List<MetPerson>>(emptyList())
    val links: StateFlow<List<String>> = _links.asStateFlow()
    /** Links I keep but left out of my code. */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()
    /** Newest first. */
    val met: StateFlow<List<MetPerson>> = _met.asStateFlow()

    init {
        persistence?.load()?.let { (l, h, m) ->
            _links.value = l.mapNotNull(SocialsCode::cleanLink).distinct().take(SocialsCode.MAX_LINKS)
            _hidden.value = h.intersect(_links.value.toSet())
            _met.value = m.sortedByDescending { it.atMs }.take(MAX_MET)
        }
    }

    /** Replaces my links with the ones that can go in a code. Returns what was kept. */
    @Synchronized fun setLinks(raw: List<String>): List<String> {
        val ok = raw.mapNotNull(SocialsCode::cleanLink).distinct().take(SocialsCode.MAX_LINKS)
        _links.value = ok
        _hidden.value = _hidden.value.intersect(ok.toSet())
        save(); return ok
    }

    @Synchronized fun setShown(link: String, shown: Boolean) {
        if (link !in _links.value) return
        _hidden.value = if (shown) _hidden.value - link else _hidden.value + link
        save()
    }

    /** Links that go in my code right now. */
    fun shared(): List<String> = _links.value.filter { it !in _hidden.value }

    /** Saves someone I met. The same card again moves to the top instead of repeating. */
    @Synchronized fun remember(card: SocialsCard, nowMs: Long) {
        _met.value = (listOf(MetPerson(card, nowMs)) + _met.value.filter { it.card != card }).take(MAX_MET)
        save()
    }

    @Synchronized fun forget(person: MetPerson) {
        _met.value = _met.value - person
        save()
    }

    fun isSaved(card: SocialsCard): Boolean = _met.value.any { it.card == card }

    @Synchronized fun wipe() {
        _links.value = emptyList(); _hidden.value = emptySet(); _met.value = emptyList()
        save()
    }

    private fun save() = persistence?.save(_links.value, _hidden.value, _met.value)

    companion object { const val MAX_MET = 200 }
}

/** The app's socials book, kept in its own preferences file. Also carries "open the Share tab" requests. */
object MoshSocials {
    private const val FILE = "moshgrid_socials"
    /** Intent action for the app icon shortcut that opens the Share tab. */
    const val ACTION_SHOW = "com.moshgrid.action.SHOW_SOCIALS"
    @Volatile private var book: SocialsBook? = null

    fun of(context: Context): SocialsBook = book ?: synchronized(this) {
        book ?: SocialsBook(PrefsPersistence(context.applicationContext)).also { book = it }
    }

    /** A card opened from a link (https://moshgrid.vercel.app/s#…), waiting to be shown. */
    private val _opened = MutableStateFlow<SocialsCard?>(null)
    val opened: StateFlow<SocialsCard?> = _opened.asStateFlow()
    fun open(text: String): Boolean { val c = SocialsCode.decode(text) ?: return false; _opened.value = c; return true }
    fun closeOpened() { _opened.value = null }

    /** Bumped when something outside the app (shortcut, link) asks for the Share tab. */
    private val _shareRequests = MutableStateFlow(0L)
    val shareRequests: StateFlow<Long> = _shareRequests.asStateFlow()
    fun requestShare() { _shareRequests.value = System.nanoTime() }

    fun wipe(context: Context) {
        of(context).wipe()
        _opened.value = null
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class PrefsPersistence(context: Context) : SocialsPersistence {
        private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        override fun load(): Triple<List<String>, Set<String>, List<MetPerson>> {
            val links = prefs.getString("links", "")!!.split('\n').filter { it.isNotEmpty() }
            val hidden = prefs.getString("hidden", "")!!.split('\n').filter { it.isNotEmpty() }.toSet()
            // Each person is "<time> <their code>"; a line that doesn't read back is dropped.
            val met = prefs.getString("met", "")!!.split('\n').mapNotNull { line ->
                val at = line.substringBefore(' ').toLongOrNull() ?: return@mapNotNull null
                SocialsCode.decode(line.substringAfter(' '))?.let { MetPerson(it, at) }
            }
            return Triple(links, hidden, met)
        }
        override fun save(links: List<String>, hidden: Set<String>, met: List<MetPerson>) {
            prefs.edit().putString("links", links.joinToString("\n")).putString("hidden", hidden.joinToString("\n"))
                .putString("met", met.mapNotNull { p -> SocialsCode.encode(p.card.name, p.card.links)?.let { "${p.atMs} $it" } }
                    .joinToString("\n")).apply()
        }
    }
}
