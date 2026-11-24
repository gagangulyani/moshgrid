package com.bitchat.android.mosh

import com.bitchat.android.model.NoisePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

/**
 * Field simulator: the real MoshGrid logic (MoshSender, MoshInbox, FriendStateStore, HelpRepeater)
 * on virtual phones joined by a scripted network. Bluetooth links, internet, delays, duplicates,
 * reordering, clock skew and app restarts are under test control. Radio physics, crowds, Doze and
 * battery are not modelled; those need physical phones. Coordinates are synthetic.
 */
class MoshFieldSimTest {

    // MARK: - world

    private class Net {
        var now = 0L
        private var seq = 0L
        private data class Ev(val at: Long, val seq: Long, val run: () -> Unit)
        private val queue = PriorityQueue<Ev>(compareBy<Ev>({ it.at }, { it.seq }))
        val phones = HashMap<String, Phone>()

        /** Real link state, and what each phone believes (it lags when someone walks away). */
        val linked = HashSet<Pair<String, String>>()
        val believedLinked = HashSet<Pair<String, String>>()
        /** Encrypted sessions. Lost when either app restarts; a send without one starts a handshake. */
        val sessions = HashSet<Pair<String, String>>()
        var handshakeMs = 2_000L
        var meshDelayMs = 500L
        var nostrDelayMs = 3_000L
        /** Relayed Nostr events wait here until the recipient is online. */
        private val relayInbox = HashMap<String, MutableList<Triple<String, NoisePayload, Long>>>()
        val log = mutableListOf<String>()

        fun at(t: Long, run: () -> Unit) { queue += Ev(t, seq++, run) }

        fun runUntil(t: Long) {
            while (true) {
                val e = queue.peek() ?: break
                if (e.at > t) break
                queue.poll(); now = e.at; e.run()
            }
            now = t
        }

        /** Advance in 1-minute steps, ticking every phone like the foreground service does. */
        fun advance(ms: Long) {
            val end = now + ms
            while (now < end) {
                val step = minOf(60_000L, end - now)
                phones.values.filter { it.alive }.forEach { it.tick() }
                runUntil(now + step)
                flushSenderQueues()
                flushRelays()
            }
        }

        fun link(a: String, b: String) {
            linked += a to b; linked += b to a; believedLinked += a to b; believedLinked += b to a
            sessions += a to b; sessions += b to a
        }
        fun dropSessions(id: String) { sessions.removeAll { it.first == id || it.second == id } }
        /** Walk away: the radio link is gone at once, the peer list notices only later. */
        fun walkAway(a: String, b: String) { linked -= a to b; linked -= b to a }
        fun peerTimeout(a: String, b: String) { believedLinked -= a to b; believedLinked -= b to a }
        fun unlink(a: String, b: String) { walkAway(a, b); peerTimeout(a, b) }

        /** Mesh: direct link, or one relay hop through a phone linked to both. */
        fun meshReach(from: String, to: String): Boolean =
            (from to to) in believedLinked || phones.keys.any { r -> r != from && r != to && (from to r) in believedLinked && (r to to) in linked }

        fun sendMesh(from: String, to: String, p: NoisePayload): Boolean {
            val src = phones.getValue(from)
            if (!src.bluetooth || !meshReach(from, to)) return false
            if ((from to to) !in sessions) {
                // Mirrors UnifiedMeshService.sendMoshPayload: no session, start a handshake, report not sent.
                at(now + handshakeMs) { if (meshReach(from, to)) { sessions += from to to; sessions += to to from } }
                return false
            }
            val direct = (from to to) in believedLinked
            at(now + meshDelayMs * if (direct) 1 else 2) {
                val ok = if (direct) (from to to) in linked
                else phones.keys.any { r -> r != from && r != to && (from to r) in linked && (r to to) in linked }
                val dst = phones.getValue(to)
                if (ok && dst.alive && dst.bluetooth) dst.receive(from, p) else log += "mesh lost $from->$to"
            }
            return true
        }

        /** Friends whose internet key the sender does not know yet: nothing can be sent to them. */
        val unknownNostrKey = HashSet<Pair<String, String>>()

        /** Like the app: false when the key is unknown; otherwise queued until the sender is online. */
        fun sendNostr(from: String, to: String, p: NoisePayload): Boolean {
            if ((from to to) in unknownNostrKey) return false
            val src = phones.getValue(from)
            if (!src.internet) { src.nostrQueue += to to p; log += "nostr queued $from->$to"; return true }
            relayInbox.getOrPut(to) { mutableListOf() } += Triple(from, p, now + nostrDelayMs)
            at(now + nostrDelayMs) { flushRelays() }
            return true
        }

        /** A sender coming back online flushes what it queued while offline. */
        fun flushSenderQueues() {
            phones.values.filter { it.alive && it.internet && it.nostrQueue.isNotEmpty() }.forEach { src ->
                val q = src.nostrQueue.toList(); src.nostrQueue.clear()
                q.forEach { (to, p) -> sendNostr(src.id, to, p) }
            }
        }

        fun flushRelays() {
            for ((to, list) in relayInbox) {
                val dst = phones.getValue(to)
                if (!dst.alive || !dst.internet) continue
                val due = list.filter { it.third <= now }
                list.removeAll(due)
                due.forEach { dst.receive(it.first, it.second) }
            }
        }

        fun lastMeshSeen(from: String, to: String): Long? = if ((from to to) in believedLinked) now else null
    }

    private class MemOutbox : HelpOutboxPersistence {
        var saved: HelpOutbox? = null
        override fun load() = saved
        override fun save(o: HelpOutbox?) { saved = o }
    }

    private class MemReceived : ReceivedHelpPersistence {
        var saved: ReceivedHelp? = null
        override fun load() = saved
        override fun save(r: ReceivedHelp) { saved = r }
    }

    private class Phone(val id: String, val net: Net, var friendIds: Set<String>, val skewMs: Long = 0) {
        var alive = true
        var bluetooth = true
        var internet = true
        var fix: LocationUpdate? = null
        /** Internet sends waiting for a connection; in memory only, like the app's relay queue. */
        val nostrQueue = mutableListOf<Pair<String, NoisePayload>>()
        val paused = HashSet<String>()
        val outbox = MemOutbox()
        val received = MemReceived()
        val clock: () -> Long = { net.now + skewMs }
        lateinit var store: FriendStateStore; private set
        lateinit var sender: MoshSender; private set
        lateinit var help: HelpRepeater; private set
        val registry = object : FriendRegistry {
            override fun status(peerID: String) = if (peerID in friendIds) FriendStatus.FRIEND else FriendStatus.NOT_FRIEND
            override fun friends() = friendIds.map { Friend(it, it, it, FriendStatus.FRIEND) }
            override fun displayName(peerID: String) = peerID
        }

        init { start() }

        /** Process start: everything in memory is rebuilt, only persisted state survives. */
        fun start() {
            alive = true
            net.dropSessions(id)
            store = FriendStateStore(clock, received)
            sender = MoshSender(
                clock = clock,
                sendMesh = { p, to -> net.sendMesh(id, to, p) },
                sendNostr = { p, to -> net.sendNostr(id, to, p) },
                internetAllowed = { internet },
                lastMeshSeenMs = { to -> net.lastMeshSeen(id, to) },
            )
            help = HelpRepeater(clock, { fix }, { p -> sendToFriends(p) }, outbox)
        }

        fun kill() { alive = false; nostrQueue.clear() }

        fun sendToFriends(p: MoshPayload): Map<String, MoshRoute> {
            val skip = if (p is LocationUpdate) paused else emptySet()
            return friendIds.filter { it !in skip }.associateWith { sender.send(p, it) }
        }

        /** Times the notifier would ring (a NEW Help), per alert ID. */
        val rings = HashMap<String, Int>()

        fun receive(from: String, p: NoisePayload) {
            MoshInbox.receive(from, p, registry) { f, decoded ->
                if (store.apply(f, decoded) == HelpChange.NEW && decoded is HelpAlert) rings.merge(decoded.alertId, 1, Int::plus)
            }
        }

        fun tick() = help.tick()

        fun shareLocation(lat: Double, lon: Double) {
            fix = LocationUpdate(lat, lon, 8f, clock(), 80)
            sendToFriends(fix!!)
        }
    }

    private fun world(vararg ids: String, friendsAll: Boolean = true): Net {
        val net = Net()
        ids.forEach { id -> net.phones[id] = Phone(id, net, if (friendsAll) ids.toSet() - id else emptySet()) }
        return net
    }

    private fun Net.p(id: String) = phones.getValue(id)

    // MARK: - connectivity

    @Test fun c1_friend_in_range_gets_location_over_bluetooth() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").shareLocation(0.001, 0.001)
        n.runUntil(1_000)
        assertNotNull(n.p("ben").store.locations.value["ana"])
    }

    @Test fun c2_friend_walks_away_internet_takes_over_and_locations_are_throttled() {
        val n = world("ana", "ben"); n.link("ana", "ben"); n.unlink("ana", "ben")
        n.runUntil(200_000) // past the 2-minute mesh grace
        assertEquals(MoshRoute.NOSTR, n.p("ana").sender.send(LocationUpdate(0.001, 0.001, 8f, n.now, 80), "ben"))
        assertEquals(MoshRoute.RATE_LIMITED, n.p("ana").sender.send(LocationUpdate(0.002, 0.001, 8f, n.now + 60_000, 80), "ben"))
        n.advance(10_000)
        assertNotNull(n.p("ben").store.locations.value["ana"])
    }

    @Test fun c3_no_bluetooth_no_internet_reports_none_for_every_friend() {
        val n = world("ana", "ben", "cy")
        n.p("ana").internet = false; n.p("ana").bluetooth = false
        n.p("ana").help.raise("")
        assertEquals(mapOf("ben" to MoshRoute.NONE, "cy" to MoshRoute.NONE), n.p("ana").help.lastRoutes)
    }

    @Test fun c4_bluetooth_switched_off_mid_session_falls_back_to_internet() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").bluetooth = false
        n.p("ana").help.raise("")
        n.advance(10_000)
        assertNotNull(n.p("ben").store.activeHelp.value["ana"])
    }

    @Test fun c5_help_reaches_friend_whose_peer_entry_is_stale() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.walkAway("ana", "ben") // ana still believes ben is connected
        n.p("ana").help.raise("")
        n.advance(10_000)
        assertNotNull("Help must not depend on a stale Bluetooth peer entry", n.p("ben").store.activeHelp.value["ana"])
    }

    @Test fun c6_friend_offline_gets_help_when_back_online() {
        val n = world("ana", "ben")
        n.p("ben").internet = false
        n.p("ana").help.raise("")
        n.advance(60_000)
        assertNull(n.p("ben").store.activeHelp.value["ana"])
        n.p("ben").internet = true
        n.advance(60_000)
        assertNotNull(n.p("ben").store.activeHelp.value["ana"])
    }

    @Test fun c7_friend_two_hops_away_gets_location_through_a_middle_phone() {
        val n = world("ana", "ben", "cy")
        n.p("ana").internet = false
        n.link("ana", "cy"); n.link("cy", "ben")
        n.p("ana").shareLocation(0.001, 0.001)   // no end-to-end session yet: starts the handshake via cy
        n.runUntil(5_000)
        n.p("ana").shareLocation(0.002, 0.001)
        n.runUntil(10_000)
        assertNotNull(n.p("ben").store.locations.value["ana"])
    }

    // MARK: - ordering and data

    @Test fun d1_old_location_arriving_late_is_ignored() {
        val n = world("ana", "ben")
        val newer = LocationUpdate(0.002, 0.002, 8f, 2_000, 80)
        val older = LocationUpdate(0.001, 0.001, 8f, 1_000, 80)
        n.p("ben").receive("ana", MoshCodec.encode(newer))
        n.p("ben").receive("ana", MoshCodec.encode(older))
        assertEquals(newer, n.p("ben").store.locations.value["ana"])
    }

    @Test fun d2_same_help_over_both_paths_is_one_alert() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").internet = true; n.p("ben").internet = true
        val id = n.p("ana").help.raise("")
        n.advance(10 * 60_000) // Bluetooth and internet copies plus several repeats
        assertEquals("rings", mapOf(id to 1), n.p("ben").rings)
    }

    @Test fun d3_cancel_before_alert_keeps_alert_away() {
        val n = world("ana", "ben")
        val alert = HelpAlert("h-1", 0.001, 0.001, 8f, "", 1_000)
        n.p("ben").receive("ana", MoshCodec.encode(HelpCancel("h-1", 2_000)))
        n.p("ben").receive("ana", MoshCodec.encode(alert))
        assertNull("a cancelled Help must not come back", n.p("ben").store.activeHelp.value["ana"])
    }

    @Test fun d3b_late_repeat_after_cancel_keeps_alert_away() {
        val n = world("ana", "ben")
        n.p("ben").receive("ana", MoshCodec.encode(HelpAlert("h-1", 0.001, 0.001, 8f, "", 1_000)))
        n.p("ben").receive("ana", MoshCodec.encode(HelpCancel("h-1", 2_000)))
        n.p("ben").receive("ana", MoshCodec.encode(HelpAlert("h-1", 0.001, 0.001, 8f, "", 1_500)))
        assertNull(n.p("ben").store.activeHelp.value["ana"])
    }

    @Test fun d4_receiver_clock_behind_sender_gives_no_negative_age() {
        val n = Net()
        n.phones["ana"] = Phone("ana", n, setOf("ben"), skewMs = 5 * 60_000)
        n.phones["ben"] = Phone("ben", n, setOf("ana"))
        n.link("ana", "ben")
        n.p("ana").shareLocation(0.001, 0.001)
        n.runUntil(1_000)
        assertFalse(n.p("ben").store.isStale("ana"))
        assertTrue(n.p("ben").store.locationAgeMs("ana")!! >= 0)
    }

    @Test fun d5_two_meeting_points_converge_on_the_latest() {
        val n = world("ana", "ben", "cy"); n.link("ana", "ben"); n.link("ben", "cy"); n.link("ana", "cy")
        val a = MeetingPoint("m-a", 0.001, 0.001, "gate", 10_000, "ana", false)
        val b = MeetingPoint("m-b", 0.002, 0.002, "bar", 10_500, "ben", false)
        n.p("ana").store.setMeetingRecord(a); n.p("ana").sendToFriends(a)
        n.p("ben").store.setMeetingRecord(b); n.p("ben").sendToFriends(b)
        n.runUntil(5_000)
        listOf("ana", "ben", "cy").forEach { assertEquals("m-b", n.p(it).store.meetingPoint.value?.pointId) }
    }

    @Test fun d6_stranger_payloads_are_dropped() {
        val n = world("ana", "ben", friendsAll = false); n.link("ana", "ben")
        n.p("ana").friendIds = setOf("ben") // one-way
        n.p("ana").help.raise("")
        n.advance(10_000)
        assertTrue(n.p("ben").store.activeHelp.value.isEmpty())
    }

    // MARK: - Help

    @Test fun h1_help_with_no_friends_reports_nobody() {
        val n = world("ana", friendsAll = false)
        n.p("ana").help.raise("")
        assertTrue(n.p("ana").help.lastRoutes.isEmpty())
    }

    @Test fun h2_old_fix_makes_help_position_vague() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").fix = LocationUpdate(0.001, 0.001, 8f, 0, 80)
        n.runUntil(20 * 60_000) // GPS off for 20 minutes
        n.p("ana").help.raise("")
        n.runUntil(n.now + 1_000)
        val h = n.p("ben").store.activeHelp.value.getValue("ana")
        assertTrue("20-min-old fix must not look exact: ${h.accuracyM}", h.accuracyM > 500f)
        assertFalse(HelpRepeater.isLocationUnknown(h))
    }

    @Test fun h4_sender_phone_dies_friends_keep_alert_with_its_age() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").help.raise("")
        n.runUntil(1_000)
        n.p("ana").kill()
        n.advance(30 * 60_000)
        val h = n.p("ben").store.activeHelp.value["ana"]
        assertNotNull("never auto-clear: a dead phone is the emergency", h)
        assertTrue(n.p("ben").clock() - h!!.raisedAtMs >= 29 * 60_000)
    }

    @Test fun h5_cancel_sent_offline_still_arrives_later() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").help.raise("")
        n.runUntil(1_000)
        n.unlink("ana", "ben"); n.p("ana").internet = false
        n.p("ana").help.cancel()
        n.advance(3 * 60_000)
        assertNotNull(n.p("ben").store.activeHelp.value["ana"])
        n.link("ana", "ben")
        n.advance(3 * 60_000)
        assertNull("cancel must be repeated until it can get through", n.p("ben").store.activeHelp.value["ana"])
    }

    @Test fun h6_sender_app_killed_help_resumes_after_restart() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        val id = n.p("ana").help.raise("")
        n.p("ana").kill()
        n.advance(5 * 60_000)
        n.p("ana").start()
        assertEquals(id, n.p("ana").help.activeAlertId)
        n.p("ana").fix = LocationUpdate(0.005, 0.005, 8f, n.p("ana").clock(), 80)
        n.advance(60_000)
        assertEquals(0.005, n.p("ben").store.activeHelp.value.getValue("ana").lat, 0.0)
    }

    @Test fun h6b_after_restart_help_retries_within_a_minute_once_bluetooth_is_back() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").internet = false
        n.p("ana").help.raise("")
        n.runUntil(1_000)
        val before = n.p("ben").store.activeHelp.value.getValue("ana").raisedAtMs
        n.p("ana").kill(); n.unlink("ana", "ben")
        n.p("ana").start()
        n.advance(30_000)            // first tick: Bluetooth session not back yet, nothing goes out
        n.link("ana", "ben")         // session re-established
        n.advance(70_000)            // well under the normal 2-minute repeat
        val after = n.p("ben").store.activeHelp.value.getValue("ana").raisedAtMs
        assertTrue("Help must retry quickly while it reached nobody over Bluetooth", after > before)
    }

    @Test fun s1_bluetooth_only_after_restart_locations_flow_again_without_any_chat() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").internet = false; n.p("ben").internet = false
        n.p("ana").kill(); n.p("ana").start()                    // session gone, link still up
        n.p("ana").shareLocation(0.001, 0.001)                   // starts the handshake, not delivered
        n.runUntil(n.now + 5_000)
        assertNull(n.p("ben").store.locations.value["ana"])
        n.p("ana").shareLocation(0.002, 0.001)                   // next send: session is back
        n.runUntil(n.now + 1_000)
        assertNotNull(n.p("ben").store.locations.value["ana"])
    }

    @Test fun h7_receiver_restart_keeps_friends_help() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").help.raise("")
        n.runUntil(1_000)
        n.p("ana").kill()
        n.p("ben").kill(); n.p("ben").start()
        assertNotNull(n.p("ben").store.activeHelp.value["ana"])
    }

    @Test fun h7b_receiver_restart_keeps_cancel_memory() {
        val n = world("ana", "ben")
        n.p("ben").receive("ana", MoshCodec.encode(HelpCancel("h-1", 2_000)))
        n.p("ben").kill(); n.p("ben").start()
        n.p("ben").receive("ana", MoshCodec.encode(HelpAlert("h-1", 0.001, 0.001, 8f, "", 1_000)))
        assertNull(n.p("ben").store.activeHelp.value["ana"])
    }

    // MARK: - friends

    @Test fun f1_paused_friend_gets_no_location_but_does_get_help() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        n.p("ana").paused += "ben"
        n.p("ana").shareLocation(0.001, 0.001)
        n.p("ana").help.raise("")
        n.runUntil(2_000)
        assertNull(n.p("ben").store.locations.value["ana"])
        assertNotNull(n.p("ben").store.activeHelp.value["ana"])
    }

    @Test fun f2_friend_who_reset_identity_is_a_stranger_until_rescanned() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        // ben reinstalls: new peer ID "ben2" that ana never verified
        n.phones["ben2"] = Phone("ben2", n, setOf("ana")); n.link("ana", "ben2")
        n.p("ben2").help.raise("")
        n.runUntil(2_000)
        assertNull(n.p("ana").store.activeHelp.value["ben2"])
    }

    // MARK: - crowds

    @Test fun i3_fifty_strangers_flooding_never_appear_and_a_friend_still_gets_through() {
        val n = world("ana", "ben"); n.link("ana", "ben")
        val strangers = (1..50).map { "s$it" }
        strangers.forEach { s -> n.phones[s] = Phone(s, n, setOf("ben")); n.link(s, "ben") }
        // Every stranger floods ben with locations, Help and meeting points for 10 minutes.
        repeat(10) { minute ->
            strangers.forEach { s ->
                n.p(s).shareLocation(0.001, 0.001)
                n.p(s).sendToFriends(MeetingPoint("m-$s-$minute", 0.002, 0.002, "", n.now, s, false))
            }
            if (minute == 0) strangers.forEach { n.p(it).help.raise("") }
            if (minute == 5) n.p("ana").help.raise("")
            n.advance(60_000)
        }
        val store = n.p("ben").store
        assertEquals(setOf("ana"), store.activeHelp.value.keys)
        assertTrue(store.locations.value.keys.none { it in strangers })
        assertTrue(store.meetingPoint.value?.pointId?.startsWith("m-s") != true)
    }

    @Test fun i4_twenty_friends_out_of_range_location_over_internet_stays_throttled() {
        val ids = listOf("ana") + (1..20).map { "f$it" }
        val n = world(*ids.toTypedArray()) // nobody linked: everyone far away, internet on
        n.runUntil(MoshSender.MESH_GRACE_MS + 1)
        var nostrSends = 0
        val start = n.now
        // Location every 2 minutes for an hour, as the app does while sharing.
        repeat(30) {
            nostrSends += n.p("ana").sendToFriends(LocationUpdate(0.001, 0.001, 8f, n.now, 80))
                .values.count { it == MoshRoute.NOSTR }
            n.advance(120_000)
        }
        assertEquals(60 * 60_000L, n.now - start)
        // At most one per friend per 5 minutes: 20 friends x 12 windows.
        assertTrue("sent $nostrSends", nostrSends <= 20 * 12)
        ids.drop(1).forEach { assertNotNull("no location at $it", n.p(it).store.locations.value["ana"]) }
        // Help is never throttled: every friend gets it at once.
        n.p("ana").help.raise("")
        n.runUntil(n.now + n.nostrDelayMs + 1)
        ids.drop(1).forEach { assertNotNull("no Help at $it", n.p(it).store.activeHelp.value["ana"]) }
    }
}
