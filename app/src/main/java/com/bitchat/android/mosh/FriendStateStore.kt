package com.bitchat.android.mosh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What we currently know about our friends. Latest wins everywhere: updates can arrive late and out
 * of order (mesh relays, Nostr), so each one is compared by the time it was made, not received.
 */
/** What a received payload did to friends' Help; the notifier acts only on real changes. */
enum class HelpChange { NONE, NEW, UPDATED, CANCELLED }

/** When the payload was made, by the sender's clock. */
fun MoshPayload.madeAtMs(): Long = when (this) {
    is LocationUpdate -> takenAtMs
    is MeetingPoint -> setAtMs
    is HelpAlert -> raisedAtMs
    is HelpCancel -> cancelledAtMs
    is GroupRoster -> updatedAtMs
    is GroupMessage -> sentAtMs
    is GroupReceipt -> atMs
    is ProfilePhoto -> updatedAtMs
    is FriendInvite -> atMs
}

class FriendStateStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val helpPersistence: ReceivedHelpPersistence? = null,
    private val snapshotPersistence: FriendSnapshotPersistence? = null,
) {

    private val _locations = MutableStateFlow<Map<String, LocationUpdate>>(emptyMap())
    val locations: StateFlow<Map<String, LocationUpdate>> = _locations.asStateFlow()

    /** Newest meeting-point record, set or cleared. The visible meeting point is [meetingPoint]. */
    private var latestMeetingRecord: MeetingPoint? = null
    private var latestIsOwn = false
    /** Newest meeting-point record (set or cleared), for re-sending to friends. */
    val latestMeeting: MeetingPoint? @Synchronized get() = latestMeetingRecord
    private val _meetingPoint = MutableStateFlow<MeetingPoint?>(null)
    val meetingPoint: StateFlow<MeetingPoint?> = _meetingPoint.asStateFlow()

    private val _activeHelp = MutableStateFlow<Map<String, HelpAlert>>(emptyMap())
    val activeHelp: StateFlow<Map<String, HelpAlert>> = _activeHelp.asStateFlow()

    private val _lastHeardMs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastHeardMs: StateFlow<Map<String, Long>> = _lastHeardMs.asStateFlow()

    /**
     * Alert IDs already cancelled, with when we learned it. A cancel can overtake its alert (Nostr
     * versus mesh), and repeats can arrive late through relays; neither may bring a Help back.
     */
    private val cancelledHelp = HashMap<String, Long>()

    init {
        helpPersistence?.load()?.let { saved ->
            _activeHelp.value = saved.active
            cancelledHelp.putAll(saved.cancelled)
        }
        snapshotPersistence?.load()?.let { saved ->
            val now = clock()
            _locations.value = saved.locations.filterValues { now - it.takenAtMs <= FriendSnapshot.KEEP_MS }
            _lastHeardMs.value = saved.lastHeard.filterValues { now - it <= FriendSnapshot.KEEP_MS }
            saved.meeting?.takeIf { now - it.setAtMs <= FriendSnapshot.KEEP_MS && it.setAtMs <= now + MAX_FUTURE_MS }?.let { m ->
                latestMeetingRecord = m
                latestIsOwn = saved.meetingIsOwn
                _meetingPoint.value = if (m.cleared) null else m
            }
        }
    }

    /**
     * Apply a payload from a friend. Returns what it did to their Help, so a replayed, late or
     * cancelled alert never rings again.
     *
     * Payloads stamped more than [MAX_FUTURE_MS] ahead of our clock are dropped: a wrong clock (or a
     * hostile friend) must not pin a meeting point or location for ever. "Last heard" uses when the
     * payload was made, capped at now, so relays replaying old messages after a restart cannot make
     * a silent friend look recently heard.
     */
    @Synchronized
    fun apply(fromPeerID: String, p: MoshPayload): HelpChange {
        val now = clock()
        val madeAt = p.madeAtMs()
        if (madeAt > now + MAX_FUTURE_MS) return HelpChange.NONE
        val heard = minOf(now, madeAt)
        if (heard > (_lastHeardMs.value[fromPeerID] ?: Long.MIN_VALUE)) {
            _lastHeardMs.value = _lastHeardMs.value + (fromPeerID to heard)
        }
        var change = HelpChange.NONE
        when (p) {
            is LocationUpdate -> {
                val current = _locations.value[fromPeerID]
                if (current == null || p.takenAtMs > current.takenAtMs) {
                    _locations.value = _locations.value + (fromPeerID to p)
                }
                saveSnapshot()
            }
            is MeetingPoint -> setMeetingRecord(p, own = false)
            // Groups have their own store (MoshGroups); they never reach this one.
            is GroupRoster, is GroupMessage, is GroupReceipt, is ProfilePhoto, is FriendInvite -> return HelpChange.NONE
            is HelpAlert -> {
                saveSnapshot()
                if (p.alertId in cancelledHelp) return HelpChange.NONE
                val current = _activeHelp.value[fromPeerID]
                if (current == null || p.raisedAtMs >= current.raisedAtMs) {
                    // A repeat sent before the sender's GPS came back (for example right after a
                    // restart) must not wipe the last known position of the same alert.
                    val keep = current != null && current.alertId == p.alertId &&
                        HelpRepeater.isLocationUnknown(p) && !HelpRepeater.isLocationUnknown(current)
                    val stored = if (keep) p.copy(lat = current!!.lat, lon = current.lon, accuracyM = current.accuracyM) else p
                    _activeHelp.value = _activeHelp.value + (fromPeerID to stored)
                    saveHelp()
                    change = if (current?.alertId == p.alertId) HelpChange.UPDATED else HelpChange.NEW
                }
            }
            is HelpCancel -> {
                saveSnapshot()
                cancelledHelp[p.alertId] = now
                cancelledHelp.entries.removeAll { now - it.value > CANCEL_MEMORY_MS }
                if (_activeHelp.value[fromPeerID]?.alertId == p.alertId) {
                    _activeHelp.value = _activeHelp.value - fromPeerID
                    change = HelpChange.CANCELLED
                }
                saveHelp()
            }
        }
        return change
    }

    /**
     * Meeting points, latest wins. [own] marks one this phone set; only those are re-sent, so a
     * meeting point never travels beyond the friends of the person who set it.
     */
    @Synchronized
    fun setMeetingRecord(p: MeetingPoint, own: Boolean = true) {
        val latest = latestMeetingRecord
        if (latest != null && p.setAtMs <= latest.setAtMs) return
        latestMeetingRecord = p
        latestIsOwn = own
        _meetingPoint.value = if (p.cleared) null else p
        saveSnapshot()
    }


    /** The current meeting point if this phone set it (for re-sending), else null. */
    val ownLatestMeeting: MeetingPoint? @Synchronized get() = latestMeetingRecord?.takeIf { latestIsOwn }

    /** A removed friend: forget their location, Help and last-heard time. */
    @Synchronized
    fun forget(peerID: String) {
        _locations.value = _locations.value - peerID
        _activeHelp.value = _activeHelp.value - peerID
        _lastHeardMs.value = _lastHeardMs.value - peerID
        saveHelp(); saveSnapshot()
    }

    /** Panic wipe: forget everything about friends, including saved Help and cancel memory. */
    @Synchronized
    fun wipe() {
        _locations.value = emptyMap(); _activeHelp.value = emptyMap(); _lastHeardMs.value = emptyMap()
        latestMeetingRecord = null; latestIsOwn = false; _meetingPoint.value = null
        cancelledHelp.clear()
        saveHelp()
        saveSnapshot()
    }

    private fun saveSnapshot() {
        val p = snapshotPersistence ?: return
        // Never write back what is past its keep time: the 12-hour promise holds on disk too.
        val now = clock()
        _locations.value = _locations.value.filterValues { now - it.takenAtMs <= FriendSnapshot.KEEP_MS }
        _lastHeardMs.value = _lastHeardMs.value.filterValues { now - it <= FriendSnapshot.KEEP_MS }
        val meeting = latestMeetingRecord?.takeIf { now - it.setAtMs <= FriendSnapshot.KEEP_MS }
        p.save(FriendSnapshot(_locations.value, meeting, _lastHeardMs.value, meeting != null && latestIsOwn))
    }

    private fun saveHelp() { helpPersistence?.save(ReceivedHelp(_activeHelp.value, HashMap(cancelledHelp))) }

    /** Age of the friend's location by our clock. Never negative: phone clocks can differ. */
    fun locationAgeMs(peerID: String): Long? =
        _locations.value[peerID]?.let { (clock() - it.takenAtMs).coerceAtLeast(0) }

    /** True when the friend's location is more than [STALE_AFTER_MS] old. */
    fun isStale(peerID: String): Boolean {
        val age = locationAgeMs(peerID) ?: return true
        return age > STALE_AFTER_MS
    }

    /** True when nothing has been heard from the friend for more than [SILENT_AFTER_MS]. */
    fun isSilent(peerID: String): Boolean {
        val heard = _lastHeardMs.value[peerID] ?: return true
        return clock() - heard > SILENT_AFTER_MS
    }

    companion object {
        const val STALE_AFTER_MS = 600_000L
        const val SILENT_AFTER_MS = 900_000L
        /** Longer than relays replay (48 h, with gift-wrap times up to 22 h early), so a replayed alert stays cancelled. */
        const val CANCEL_MEMORY_MS = 72 * 3_600_000L
        /** Payloads stamped further ahead of our clock than this are dropped. */
        const val MAX_FUTURE_MS = 15 * 60_000L

        /** Set by [MoshRuntime.init] before first use, so friends' Help survives a restart. */
        @Volatile var sharedPersistence: ReceivedHelpPersistence? = null
        /** Set by [MoshRuntime.init] before first use, so friends' last locations survive a restart. */
        @Volatile var sharedSnapshot: FriendSnapshotPersistence? = null

        /** App-wide instance fed by [MoshInbox]. */
        val shared: FriendStateStore by lazy { FriendStateStore(helpPersistence = sharedPersistence, snapshotPersistence = sharedSnapshot) }
    }
}
