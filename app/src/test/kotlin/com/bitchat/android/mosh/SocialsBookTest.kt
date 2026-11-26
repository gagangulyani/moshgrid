package com.bitchat.android.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialsBookTest {
    private class Mem : SocialsPersistence {
        var links = listOf<String>(); var hidden = setOf<String>(); var met = listOf<MetPerson>()
        override fun load() = Triple(links, hidden, met)
        override fun save(links: List<String>, hidden: Set<String>, met: List<MetPerson>) { this.links = links; this.hidden = hidden; this.met = met }
    }
    private val ig = "https://instagram.com/ana"
    private val sp = "https://open.spotify.com/user/ana"

    @Test fun keeps_only_usable_links_and_persists() {
        val mem = Mem(); val b = SocialsBook(mem)
        assertEquals(listOf(ig, sp), b.setLinks(listOf(" $ig ", "", "http://x.com/a", sp, ig)))
        assertEquals(listOf(ig, sp), mem.links)
        assertEquals(listOf(ig, sp), SocialsBook(mem).links.value)
    }

    @Test fun ticking_off_a_link_leaves_it_out_of_the_code() {
        val b = SocialsBook(Mem()); b.setLinks(listOf(ig, sp))
        b.setShown(sp, false)
        assertEquals(listOf(ig), b.shared())
        b.setShown(sp, true)
        assertEquals(listOf(ig, sp), b.shared())
    }

    @Test fun removing_a_link_also_forgets_it_was_hidden() {
        val b = SocialsBook(Mem()); b.setLinks(listOf(ig, sp)); b.setShown(sp, false)
        b.setLinks(listOf(ig)); b.setLinks(listOf(ig, sp))
        assertEquals(listOf(ig, sp), b.shared())
    }

    @Test fun people_met_newest_first_no_repeats_and_forget() {
        val mem = Mem(); val b = SocialsBook(mem)
        val jo = SocialsCard("Jo", listOf(ig)); val sam = SocialsCard("Sam", listOf(sp))
        b.remember(jo, 1); b.remember(sam, 2); b.remember(jo, 3)
        assertEquals(listOf("Jo", "Sam"), b.met.value.map { it.card.name })
        assertTrue(b.isSaved(sam))
        b.forget(b.met.value.last())
        assertFalse(b.isSaved(sam))
        assertEquals(listOf("Jo"), SocialsBook(mem).met.value.map { it.card.name })
    }

    @Test fun people_met_is_capped() {
        val b = SocialsBook(Mem())
        repeat(SocialsBook.MAX_MET + 5) { b.remember(SocialsCard("P$it", listOf(ig)), it.toLong()) }
        assertEquals(SocialsBook.MAX_MET, b.met.value.size)
        assertEquals("P${SocialsBook.MAX_MET + 4}", b.met.value.first().card.name)
    }

    @Test fun wipe_clears_everything_and_saves_it() {
        val mem = Mem(); val b = SocialsBook(mem)
        b.setLinks(listOf(ig)); b.setShown(ig, false); b.remember(SocialsCard("Jo", listOf(sp)), 1)
        b.wipe()
        assertTrue(b.links.value.isEmpty() && b.hidden.value.isEmpty() && b.met.value.isEmpty())
        assertTrue(mem.links.isEmpty() && mem.hidden.isEmpty() && mem.met.isEmpty())
    }
}
