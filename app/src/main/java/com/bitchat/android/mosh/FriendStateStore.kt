package com.bitchat.android.mosh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What we currently know about our friends. Latest wins everywhere: updates can arrive late and out
 * of order (mesh relays, Nostr), so each one is compared by the time it was made, not received.
 */
class FriendStateStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val helpPersistence: ReceivedHelpPersistence? = null,
) {

    private val _locations = MutableStateFlow<Map<String, LocationUpdate>>(emptyMap())
    val locations: StateFlow<Map<String, LocationUpdate>> = _locations.asStateFlow()

    /** Newest meeting-point record, set or cleared. The visible meeting point is [meetingPoint]. */
    private var latestMeetingRecord: MeetingPoint? = null
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
    }

    @Synchronized
    fun apply(fromPeerID: String, p: MoshPayload) {
        _lastHeardMs.value = _lastHeardMs.value + (fromPeerID to clock())
        when (p) {
            is LocationUpdate -> {
                val current = _locations.value[fromPeerID]
                if (current == null || p.takenAtMs > current.takenAtMs) {
                    _locations.value = _locations.value + (fromPeerID to p)
                }
            }
            is MeetingPoint -> setMeetingRecord(p)
            is HelpAlert -> {
                if (p.alertId in cancelledHelp) return
                val current = _activeHelp.value[fromPeerID]
                if (current == null || p.raisedAtMs >= current.raisedAtMs) {
                    _activeHelp.value = _activeHelp.value + (fromPeerID to p)
                    saveHelp()
                }
            }
            is HelpCancel -> {
                val now = clock()
                cancelledHelp[p.alertId] = now
                cancelledHelp.entries.removeAll { now - it.value > CANCEL_MEMORY_MS }
                if (_activeHelp.value[fromPeerID]?.alertId == p.alertId) {
                    _activeHelp.value = _activeHelp.value - fromPeerID
                }
                saveHelp()
            }
        }
    }

    /** Our own meeting-point changes go through the same latest-wins rule. */
    @Synchronized
    fun setMeetingRecord(p: MeetingPoint) {
        val latest = latestMeetingRecord
        if (latest != null && p.setAtMs <= latest.setAtMs) return
        latestMeetingRecord = p
        _meetingPoint.value = if (p.cleared) null else p
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
        const val CANCEL_MEMORY_MS = 24 * 3_600_000L

        /** Set by [MoshRuntime.init] before first use, so friends' Help survives a restart. */
        @Volatile var sharedPersistence: ReceivedHelpPersistence? = null

        /** App-wide instance fed by [MoshInbox]. */
        val shared: FriendStateStore by lazy { FriendStateStore(helpPersistence = sharedPersistence) }
    }
}
