package com.bitchat.android.mosh

import com.bitchat.android.model.NoisePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Chaos simulator: the real MoshGrid logic (MoshSender, MoshInbox, FriendStateStore, HelpRepeater,
 * MeetingResender, saved state) on a crowd of virtual phones. Bluetooth is multi-hop through any
 * phone in range (strangers relay too, up to 7 hops) with loss that grows with crowd density;
 * people walk; mobile data, Bluetooth and the relays go up and down; apps are killed; clocks are
 * skewed; some friends' internet keys are unknown. Coordinates are synthetic (near 0.25, 0.5).
 *
 * Each scenario runs over several random seeds. Safety rules are checked throughout; delivery is
 * checked after a calm period at the end (everyone online), when every path is available again.
 */
class MoshChaosSimTest {

    private data class Cfg(
        val name: String,
        val strangers: Int = 60,
        val friends: Int = 6,
        val areaM: Double = 300.0,
        val btRangeM: Double = 30.0,
        val baseLoss: Double = 0.05,
        val internetUp: Double = 0.7,
        val bluetoothFlaps: Boolean = false,
        val relayFlaps: Boolean = false,
        val killsPerHour: Double = 0.0,
        val skewMaxMs: Long = 0,
        val unknownKeyFraction: Double = 0.0,
        val senderDies: Boolean = false,
        /** Two friend circles sharing one person (f2): X = f0,f1,f2 and Y = f2..f5. */
        val circles: Boolean = false,
        val seeds: Int = 6,
    )

    private class Ev(val at: Long, val seq: Long, val run: () -> Unit)

    private inner class World(val cfg: Cfg, seed: Int) {
        val rnd = Random(seed)
        var now = 0L
        private var seq = 0L
        private val q = PriorityQueue<Ev>(compareBy<Ev>({ it.at }, { it.seq }))
        fun at(t: Long, run: () -> Unit) { q += Ev(t, seq++, run) }

        val all = ArrayList<Node>()
        val friends = ArrayList<FriendNode>()
        var relayUp = true
        /** Relay storage: (from, to, payload, arrives-at). Delivered once per recipient. */
        val relay = ArrayList<RelayEvent>()
        val violations = ArrayList<String>()
        val nostrLocationSends = HashMap<Pair<String, String>, MutableList<Long>>()

        // Topology, rebuilt every step.
        private var adj: Map<Node, List<Node>> = emptyMap()
        /** Last time a Bluetooth path existed between two friends (the peer list lags by [PEER_TIMEOUT_MS]). */
        val lastPath = HashMap<Pair<String, String>, Long>()
        val sessions = HashSet<Pair<String, String>>()
        val unknownKey = HashSet<Pair<String, String>>()
        /** Who is friends with whom (mutual). Everyone by default. */
        val friendsOf = HashMap<String, Set<String>>()
        /** Receiver -> alert IDs whose position it has known (a later "unknown" would wipe the pin). */
        val knownPos = HashMap<String, MutableSet<String>>()
        /** Meeting points set during the run: (setter, record). */
        val meetingsSet = ArrayList<Pair<String, MeetingPoint>>()

        fun rebuild() {
            val up = all.filter { it.alive && it.bluetooth }
            val cell = cfg.btRangeM
            val grid = HashMap<Pair<Int, Int>, MutableList<Node>>()
            up.forEach { grid.getOrPut((it.x / cell).toInt() to (it.y / cell).toInt()) { mutableListOf() } += it }
            adj = up.associateWith { n ->
                val cx = (n.x / cell).toInt(); val cy = (n.y / cell).toInt()
                (-1..1).flatMap { dx -> (-1..1).flatMap { dy -> grid[(cx + dx) to (cy + dy)].orEmpty() } }
                    .filter { it !== n && hypot(it.x - n.x, it.y - n.y) <= cfg.btRangeM }
            }
            for (a in friends) for (b in friends) if (a !== b && path(a, b) != null) lastPath[a.id to b.id] = now
        }

        /** Shortest Bluetooth path, at most 7 hops, through any phone. */
        fun path(a: Node, b: Node): List<Node>? {
            if (a !in adj || b !in adj) return null
            val prev = HashMap<Node, Node?>(); prev[a] = null
            var frontier = listOf(a)
            repeat(7) {
                val next = ArrayList<Node>()
                for (n in frontier) for (m in adj[n].orEmpty()) if (m !in prev) {
                    prev[m] = n; if (m === b) {
                        val p = ArrayList<Node>(); var c: Node? = b
                        while (c != null) { p += c; c = prev[c] }
                        return p.reversed()
                    }
                    next += m
                }
                frontier = next
            }
            return null
        }

        fun believedLinked(from: String, to: String) = (lastPath[from to to] ?: Long.MIN_VALUE / 2) >= now - PEER_TIMEOUT_MS

        fun sendMesh(from: FriendNode, to: String, p: NoisePayload): Boolean {
            if (!from.bluetooth || !believedLinked(from.id, to)) return false
            if ((from.id to to) !in sessions) {
                // Like UnifiedMeshService.sendMoshPayload: start a handshake, report not sent.
                at(now + 2_000) {
                    val dst = friends.first { it.id == to }
                    if (from.alive && dst.alive && path(from, dst) != null && rnd.nextDouble() > 0.2) {
                        sessions += from.id to to; sessions += to to from.id
                    }
                }
                return false
            }
            val dst = friends.first { it.id == to }
            val route = path(from, dst)
            var delay = 0L
            val delivered = route != null && route.zipWithNext().all { (a, _) ->
                val density = adj[a]?.size ?: 0
                delay += 200L + rnd.nextLong(1_300) + density * 20L
                rnd.nextDouble() > cfg.baseLoss + minOf(0.35, density * 0.006)
            }
            if (delivered) at(now + delay) { if (dst.alive && dst.bluetooth) dst.receive(from.id, p) }
            return true
        }

        fun sendNostr(from: FriendNode, to: String, p: NoisePayload): Boolean {
            if ((from.id to to) in unknownKey) return false
            if (MoshCodec.decode(p) is LocationUpdate) nostrLocationSends.getOrPut(from.id to to) { mutableListOf() } += now
            if (from.online()) relay += RelayEvent(from.id, to, p, now + 1_000 + rnd.nextLong(29_000))
            else { from.queue += to to p; if (from.queue.size > 500) from.queue.removeAt(0) }
            return true
        }

        fun stepNetwork() {
            friends.filter { it.alive && it.online() && it.queue.isNotEmpty() }.forEach { f ->
                f.queue.forEach { (to, p) -> relay += RelayEvent(f.id, to, p, now + 1_000 + rnd.nextLong(29_000)) }
                f.queue.clear()
            }
            if (!relayUp) return
            relay.filter { !it.done && it.at <= now }.forEach { e ->
                val dst = friends.first { it.id == e.to }
                if (dst.alive && dst.online()) { e.done = true; dst.receive(e.from, e.p) }
            }
        }

        fun run(until: Long, step: Long = 5_000, chaos: Boolean) {
            while (now < until) {
                val t = now + step
                while (true) { val e = q.peek() ?: break; if (e.at > t) break; q.poll(); now = e.at; e.run() }
                now = t
                all.forEach { it.walk(step) }
                if (chaos) chaosStep(step)
                rebuild()
                friends.filter { it.alive }.forEach { it.onStep() }
                stepNetwork()
                checkSafety()
            }
        }

        /** Strangers in Bluetooth range push their own payloads at friends; all must be dropped. */
        fun strangersInject() {
            for (s in all) {
                if (s is FriendNode || !s.alive || rnd.nextDouble() > 0.01) continue
                val target = adj[s]?.firstOrNull { it is FriendNode } as? FriendNode ?: continue
                val (lat, lon) = origin(s.x, s.y)
                val p: MoshPayload = when (rnd.nextInt(4)) {
                    0 -> LocationUpdate(lat, lon, 5f, now, 50)
                    1 -> HelpAlert("x-${s.id}", lat, lon, 5f, "", now)
                    2 -> MeetingPoint("x-${s.id}", lat, lon, "", now, s.id, false)
                    else -> HelpCancel("x-${s.id}", now)
                }
                target.receive(s.id, MoshCodec.encode(p))
            }
        }

        /** A restarted app re-subscribes and the relay sends back the last 48 hours. */
        fun replayFor(id: String) {
            relay.filter { it.to == id && now - it.at <= 48 * 3_600_000L }.forEach { it.done = false }
        }

        fun chaosStep(step: Long) {
            val perStep = step / 60_000.0
            strangersInject()
            friends.forEach { f ->
                if (rnd.nextDouble() < 0.15 * perStep) f.internet = rnd.nextDouble() < cfg.internetUp
                if (cfg.bluetoothFlaps && rnd.nextDouble() < 0.05 * perStep) f.bluetooth = !f.bluetooth
                if (f.alive && !f.dead && rnd.nextDouble() < cfg.killsPerHour / 60.0 * perStep) {
                    f.kill(); val back = now + 60_000 + rnd.nextLong(240_000); at(back) { if (!f.dead && !f.alive) f.start() }
                }
            }
            if (cfg.relayFlaps && rnd.nextDouble() < 0.1 * perStep) relayUp = !relayUp
        }

        fun checkSafety() {
            for (f in friends) {
                if (!f.alive) continue
                val st = f.store
                val strangers = (st.locations.value.keys + st.activeHelp.value.keys + st.lastHeardMs.value.keys).filter { k -> friends.none { it.id == k } }
                if (strangers.isNotEmpty()) violations += "${cfg.name}: ${f.id} holds stranger data $strangers"
                val clockNow = f.clock()
                st.lastHeardMs.value.forEach { (k, t) -> if (t > clockNow) violations += "${f.id}: last heard $k in the future" }
                st.locations.value.forEach { (k, l) -> if (l.takenAtMs > clockNow + FriendStateStore.MAX_FUTURE_MS) violations += "${f.id}: future location from $k" }
                st.meetingPoint.value?.let { m ->
                    if (m.setBy != f.id && m.setBy !in friendsOf.getValue(f.id)) violations += "${cfg.name}: ${f.id} holds meeting point of non-friend ${m.setBy}"
                }
                for ((k, h) in st.activeHelp.value) {
                    val known = knownPos.getOrPut(f.id) { mutableSetOf() }
                    if (!HelpRepeater.isLocationUnknown(h)) known += h.alertId
                    else if (h.alertId in known) violations += "${f.id}: Help pin of $k lost its position"
                }
                for ((k, h) in st.activeHelp.value) if (h.alertId in cancelledIds) {
                    // A cancelled alert may show until its cancel arrives, but never after the receiver saw that cancel.
                    if (f.sawCancel.contains(h.alertId)) violations += "${f.id}: cancelled Help ${h.alertId} from $k came back"
                }
                for ((to, r) in f.help.lastRoutes) {
                    if ((r == MoshRoute.NOSTR || r == MoshRoute.QUEUED) && (f.id to to) in unknownKey)
                        violations += "${f.id}: route $r to $to without an internet key"
                }
            }
            for ((pair, times) in nostrLocationSends) {
                times.zipWithNext().forEach { (a, b) -> if (b - a < MoshSender.NOSTR_LOCATION_MIN_INTERVAL_MS - 1) violations += "throttle broken $pair" }
            }
        }

        val cancelledIds = HashSet<String>()

        fun origin(x: Double, y: Double): Pair<Double, Double> =
            (0.25 + y / 111_320.0) to (0.5 + x / (111_320.0 * cos(Math.toRadians(0.25))))
    }

    private class RelayEvent(val from: String, val to: String, val p: NoisePayload, val at: Long) { var done = false }

    private open inner class Node(val w: World, val id: String) {
        var x = w.rnd.nextDouble() * w.cfg.areaM
        var y = w.rnd.nextDouble() * w.cfg.areaM
        private var tx = x; private var ty = y
        var speed = 0.3 + w.rnd.nextDouble()
        var alive = true
        var bluetooth = true
        fun walk(stepMs: Long) {
            if (!alive) return
            val d = hypot(tx - x, ty - y)
            if (d < 2) { tx = w.rnd.nextDouble() * w.cfg.areaM; ty = w.rnd.nextDouble() * w.cfg.areaM; return }
            val s = minOf(d, speed * stepMs / 1000.0)
            x += (tx - x) / d * s; y += (ty - y) / d * s
        }
        fun goTo(nx: Double, ny: Double) { tx = nx; ty = ny }
    }

    private inner class FriendNode(w: World, id: String, val skew: Long) : Node(w, id) {
        var internet = w.rnd.nextDouble() < w.cfg.internetUp
        var dead = false
        val queue = ArrayList<Pair<String, NoisePayload>>()
        val sawCancel = HashSet<String>()
        private val outbox = object : HelpOutboxPersistence {
            var s: HelpOutbox? = null; override fun load() = s; override fun save(o: HelpOutbox?) { s = o }
        }
        private val received = object : ReceivedHelpPersistence {
            var s: ReceivedHelp? = null; override fun load() = s; override fun save(r: ReceivedHelp) { s = r }
        }
        private val snapshot = object : FriendSnapshotPersistence {
            var s: FriendSnapshot? = null; override fun load() = s; override fun save(s: FriendSnapshot) { this.s = s }
        }
        val clock: () -> Long = { w.now + skew }
        lateinit var store: FriendStateStore
        lateinit var sender: MoshSender
        lateinit var help: HelpRepeater
        lateinit var meeting: MeetingResender
        private var lastShared: LocationUpdate? = null
        private var nextTick = w.rnd.nextLong(60_000)
        private var nextGps = 0L

        fun online() = internet && w.relayUp

        fun friendIds() = w.friendsOf.getValue(id).toList()

        /** Like MoshRuntime.setMeetingPoint: our point always wins over the current one. */
        fun setOwnMeeting(m: MeetingPoint) {
            val after = store.latestMeeting?.setAtMs?.plus(1) ?: Long.MIN_VALUE
            val stamped = if (m.setAtMs < after) m.copy(setAtMs = after) else m
            store.setMeetingRecord(stamped, own = true); sendToFriends(stamped)
            w.meetingsSet += id to stamped
        }

        fun fix() = w.origin(x, y).let { (lat, lon) -> LocationUpdate(lat, lon, 8f, clock(), 70) }

        fun start() {
            val restart = ::store.isInitialized
            alive = true; dead = false
            // A new process has no GPS fix yet (a cold fix takes 20-90 s) and the first tick comes
            // first, so a resumed Help goes out without a position.
            lastShared = null
            nextGps = w.now + 20_000 + w.rnd.nextLong(70_000)
            nextTick = w.now
            w.sessions.removeAll { it.first == id || it.second == id }
            if (restart) w.replayFor(id)
            store = FriendStateStore(clock, received, snapshot)
            sender = MoshSender(
                clock = clock,
                sendMesh = { p, to -> w.sendMesh(this, to, p) },
                sendNostr = { p, to -> w.sendNostr(this, to, p) },
                internetAllowed = { true },
                lastMeshSeenMs = { to -> w.lastPath[id to to]?.takeIf { w.believedLinked(id, to) }?.plus(skew) },
                internetOnline = { online() },
            )
            val quiet = QuietResend({ friendIds() }) { p, to, meshOnly -> sender.send(p, to, meshOnly) }
            help = HelpRepeater(clock, { lastShared }, { p -> sendToFriends(p) }, outbox, quiet)
            meeting = MeetingResender(clock, { store.ownLatestMeeting }, quiet)
        }

        fun kill() {
            alive = false; queue.clear(); w.sessions.removeAll { it.first == id || it.second == id }
            // The throttle lives in memory: a new app run may send one location sooner.
            w.nostrLocationSends.keys.filter { it.first == id }.forEach { w.nostrLocationSends.remove(it) }
        }

        fun sendToFriends(p: MoshPayload): Map<String, MoshRoute> = friendIds().associateWith { sender.send(p, it) }

        fun receive(from: String, p: NoisePayload) {
            if (!alive) return
            val registry = object : FriendRegistry {
                override fun status(peerID: String) = if (peerID in w.friendsOf.getValue(id)) FriendStatus.FRIEND else FriendStatus.NOT_FRIEND
                override fun friends() = emptyList<Friend>()
                override fun displayName(peerID: String): String? = peerID
            }
            MoshInbox.receive(from, p, registry) { f, decoded ->
                if (decoded is HelpCancel) sawCancel += decoded.alertId
                val change = store.apply(f, decoded)
                // What the notifier would do: a NEW change rings. It must never ring for a cancelled alert.
                if (change == HelpChange.NEW && decoded is HelpAlert && decoded.alertId in sawCancel)
                    w.violations += "${w.cfg.name}: $id rang for cancelled Help ${decoded.alertId}"
            }
        }

        fun onStep() {
            if (w.now >= nextGps) {
                nextGps = w.now + LocationSharer.UPDATE_INTERVAL_MS
                val f = fix()
                if (shouldSend(lastShared, f, clock())) { lastShared = f; sendToFriends(f) } else lastShared = lastShared ?: f
            }
            if (w.now >= nextTick) { nextTick = w.now + 60_000; help.tick(); meeting.tick() }
        }

        init { start() }
    }

    private class Outcome(val helpLatencyMs: Map<String, Long?>, val violations: List<String>)

    private fun simulate(cfg: Cfg, seed: Int): Outcome {
        val w = World(cfg, seed)
        repeat(cfg.friends) { i ->
            val skew = if (cfg.skewMaxMs == 0L) 0L else w.rnd.nextLong(-cfg.skewMaxMs, cfg.skewMaxMs)
            val f = FriendNode(w, "f$i", skew); w.friends += f; w.all += f
        }
        repeat(cfg.strangers) { w.all += Node(w, "s$it") }
        val ids = w.friends.map { it.id }
        if (cfg.circles) {
            val x = setOf("f0", "f1", "f2"); val y = setOf("f2", "f3", "f4", "f5")
            ids.forEach { id -> w.friendsOf[id] = ((if (id in x) x else emptySet()) + (if (id in y) y else emptySet())) - id }
        } else ids.forEach { id -> w.friendsOf[id] = ids.toSet() - id }
        for (a in w.friends) for (b in w.friends) if (a !== b && w.rnd.nextDouble() < cfg.unknownKeyFraction) w.unknownKey += a.id to b.id

        val f0 = w.friends[0]; val f1 = w.friends[1]; val f2 = w.friends[2]; val f3 = w.friends[3]
        var help0: String? = null; var help2: String? = null; var cancelled2 = false
        val firstSeen = HashMap<String, Long>()
        var raisedAt = 0L
        w.at(5 * 60_000L) { if (f0.alive) { help0 = f0.help.raise("red cap"); raisedAt = w.now } }
        w.at(8 * 60_000L) {
            if (f1.alive) f1.setOwnMeeting(MeetingPoint("m-1", 0.2501, 0.5001, "gate", f1.clock(), f1.id, false))
        }
        w.at(12 * 60_000L) { if (f2.alive) help2 = f2.help.raise("") }
        w.at(20 * 60_000L) { if (f2.alive && help2 != null) { w.cancelledIds += help2!!; f2.help.cancel(); cancelled2 = true } }
        w.at(25 * 60_000L) {
            if (f3.alive) f3.setOwnMeeting(MeetingPoint("m-2", 0.2502, 0.5002, "bar", f3.clock(), f3.id, false))
        }
        if (cfg.senderDies) w.at(7 * 60_000L) { f0.kill(); f0.dead = true }

        // Chaos for 35 minutes, watching when each friend first sees f0's Help.
        while (w.now < 35 * 60_000L) {
            w.run(w.now + 5_000, chaos = true)
            val id = help0 ?: continue
            w.friends.filter { it !== f0 && it.alive && it.store.activeHelp.value[f0.id]?.alertId == id }
                .forEach { firstSeen.putIfAbsent(it.id, w.now - raisedAt) }
        }

        // Calm: everyone back (except a dead phone), all networks up, friends walk towards each other.
        w.friends.forEach { f ->
            if (!f.dead && !f.alive) f.start()
            f.internet = true; f.bluetooth = true
            f.goTo(cfg.areaM / 2 + w.rnd.nextDouble() * 20, cfg.areaM / 2 + w.rnd.nextDouble() * 20)
        }
        w.relayUp = true
        // Internet keys are learned once friends meet over Bluetooth (favourite exchange).
        w.unknownKey.clear()
        w.run(w.now + 15 * 60_000L, chaos = false)

        val v = ArrayList(w.violations)
        for (f in w.friends) {
            if (!f.alive) continue
            val friendOfF0 = f.id in w.friendsOf.getValue(f0.id)
            if (friendOfF0 && help0 != null && f0.alive && f.store.activeHelp.value[f0.id]?.alertId != help0)
                v += "${cfg.name}/$seed: ${f.id} never got f0's Help"
            if (friendOfF0 && cfg.senderDies && help0 != null && f.id in firstSeen && f.store.activeHelp.value[f0.id]?.alertId != help0)
                v += "${cfg.name}/$seed: ${f.id} lost f0's Help after f0 died"
            if (f.id in w.friendsOf.getValue(f2.id) && cancelled2 && f.store.activeHelp.value[f2.id] != null)
                v += "${cfg.name}/$seed: ${f.id} still shows f2's cancelled Help"
            // Each phone ends on the newest point set by itself or one of its friends, never anyone else's.
            val expected = w.meetingsSet.filter { (setter, _) -> setter == f.id || setter in w.friendsOf.getValue(f.id) }
                .maxByOrNull { it.second.setAtMs }?.second
            val mp = f.store.meetingPoint.value?.pointId
            if (expected != null && mp != expected.pointId) v += "${cfg.name}/$seed: ${f.id} meeting point is $mp, not ${expected.pointId}"
        }
        val lat = w.friends.filter { it.id in w.friendsOf.getValue(f0.id) }.associate { it.id to firstSeen[it.id] }
        return Outcome(lat, v)
    }

    private fun runAll(cfg: Cfg): List<Outcome> {
        val outs = (1..cfg.seeds).map { simulate(cfg, it) }
        val lat = outs.flatMap { it.helpLatencyMs.values }
        val got = lat.filterNotNull().sorted()
        val p = { q: Double -> got.getOrNull(((got.size - 1) * q).toInt())?.let { "${it / 1000}s" } ?: "-" }
        println("${cfg.name}: Help seen during chaos by ${got.size}/${lat.size} friend-runs; median ${p(0.5)}, p90 ${p(0.9)}, max ${p(1.0)}")
        return outs
    }

    private fun check(cfg: Cfg) {
        val v = runAll(cfg).flatMap { it.violations }
        assertTrue(v.distinct().take(10).joinToString("\n"), v.isEmpty())
    }

    @Test fun x1_dense_crowd_multi_hop() = check(Cfg("crowd", strangers = 200, baseLoss = 0.1))
    @Test fun x2_mobile_data_jammed_bluetooth_only() = check(Cfg("jammed", internetUp = 0.0))
    @Test fun x3_bluetooth_off_everywhere_internet_only() = check(Cfg("internet-only", strangers = 0, btRangeM = 0.1))
    @Test fun x4_everything_flapping() = check(Cfg("flapping", internetUp = 0.5, bluetoothFlaps = true, relayFlaps = true))
    @Test fun x5_friends_split_far_apart() = check(Cfg("split", strangers = 40, areaM = 1_500.0, internetUp = 0.4))
    @Test fun x6_apps_killed_often() = check(Cfg("kills", killsPerHour = 4.0, internetUp = 0.5))
    @Test fun x7_clocks_off_by_10_minutes() = check(Cfg("skew", skewMaxMs = 10 * 60_000L, internetUp = 0.5))
    @Test fun x8_internet_keys_unknown_until_friends_meet() = check(Cfg("no-keys", unknownKeyFraction = 0.6, internetUp = 0.8))
    @Test fun x9_sender_phone_dies_after_raising_help() = check(Cfg("sender-dies", senderDies = true))

    @Test fun x11_two_friend_circles_sharing_one_person() = check(Cfg("circles", circles = true, internetUp = 0.6))
    @Test fun x12_restarts_with_relay_replay() = check(Cfg("replay", killsPerHour = 6.0, internetUp = 0.9, relayFlaps = true))

    @Test fun x10_everything_at_once() =
        check(Cfg("worst", strangers = 150, areaM = 800.0, baseLoss = 0.15, internetUp = 0.4, bluetoothFlaps = true,
            relayFlaps = true, killsPerHour = 2.0, skewMaxMs = 5 * 60_000L, unknownKeyFraction = 0.3, seeds = 8))

    @Test fun x0_good_conditions_help_arrives_within_two_minutes() {
        val outs = runAll(Cfg("good", strangers = 20, internetUp = 1.0, seeds = 4))
        outs.forEach { o -> assertTrue(o.violations.joinToString("\n"), o.violations.isEmpty()) }
        val lat = outs.flatMap { it.helpLatencyMs.values }
        assertTrue("some friend never got Help in good conditions: $lat", lat.all { it != null })
        assertTrue("slow Help in good conditions: $lat", lat.filterNotNull().all { it <= 120_000 })
    }

    private companion object { const val PEER_TIMEOUT_MS = 30_000L }
}
