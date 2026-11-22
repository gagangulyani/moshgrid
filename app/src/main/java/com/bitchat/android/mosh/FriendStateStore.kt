package com.bitchat.android.mosh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What we currently know about our friends. Latest wins everywhere: updates can arrive late and out
 * of order (mesh relays, Nostr), so each one is compared by the time it was made, not received.
 */
class FriendStateStore(private val clock: () -> Long = System::currentTimeMillis) {

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
                val current = _activeHelp.value[fromPeerID]
                if (current == null || p.raisedAtMs >= current.raisedAtMs) {
                    _activeHelp.value = _activeHelp.value + (fromPeerID to p)
                }
            }
            is HelpCancel -> {
                if (_activeHelp.value[fromPeerID]?.alertId == p.alertId) {
                    _activeHelp.value = _activeHelp.value - fromPeerID
                }
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

    /** True when the friend's location is more than [STALE_AFTER_MS] old. */
    fun isStale(peerID: String): Boolean {
        val l = _locations.value[peerID] ?: return true
        return clock() - l.takenAtMs > STALE_AFTER_MS
    }

    /** True when nothing has been heard from the friend for more than [SILENT_AFTER_MS]. */
    fun isSilent(peerID: String): Boolean {
        val heard = _lastHeardMs.value[peerID] ?: return true
        return clock() - heard > SILENT_AFTER_MS
    }

    companion object {
        const val STALE_AFTER_MS = 600_000L
        const val SILENT_AFTER_MS = 900_000L

        /** App-wide instance fed by [MoshInbox]. */
        val shared: FriendStateStore by lazy { FriendStateStore() }
    }
}
