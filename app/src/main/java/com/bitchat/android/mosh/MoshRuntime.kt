package com.bitchat.android.mosh

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.service.MeshServiceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** App-wide MoshGrid wiring: friends, state, sending and receiving. Initialised once from the Application. */
object MoshRuntime {
    @Volatile private var appContext: Context? = null

    lateinit var friends: FriendRegistry
        private set
    lateinit var prefs: MoshPrefs
        private set
    lateinit var sender: MoshSender
        private set
    val store: FriendStateStore get() = FriendStateStore.shared

    private val _myLocation = MutableStateFlow<LocationUpdate?>(null)
    /** This phone's latest fix, for the Find screen and Help. Never stored or logged. */
    val myLocation: StateFlow<LocationUpdate?> = _myLocation.asStateFlow()

    /** Listeners for incoming payloads (Help notifications). */
    @Volatile var onReceived: ((Context, String, MoshPayload) -> Unit)? = null

    val isInitialised: Boolean get() = appContext != null

    @Synchronized
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        prefs = MoshPrefs.of(app)
        val identity = SecureIdentityStateManager(app)
        friends = DefaultFriendRegistry.fromApp { identity.getVerifiedFingerprints() }
        sender = MoshSender(
            clock = System::currentTimeMillis,
            sendMesh = { p, to -> prefs.bluetooth && MeshServiceHolder.getUnifiedOrCreate(app).sendMoshPayload(p, to) },
            sendNostr = { p, to ->
                val key = friends.friends().firstOrNull { it.peerID == to }?.noiseKeyHex ?: to
                NostrTransport.getInstance(app).sendMoshPayload(p, key)
            },
            internetAllowed = { nostrAllowed(prefs) },
            lastMeshSeenMs = { to ->
                MeshServiceHolder.getUnifiedOrCreate(app).getPeerInfo(to)?.takeIf { it.isConnected }?.lastSeen
            },
        )
        MoshInbox.friends = friends
        MoshInbox.sink = { from, p ->
            store.apply(from, p)
            HelpNotifier.onReceived(app, from, p)
            onReceived?.invoke(app, from, p)
        }
    }

    fun updateMyLocation(l: LocationUpdate) { _myLocation.value = l }

    /** Help to friends. Position comes from the latest fix. */
    val help: HelpRepeater by lazy {
        HelpRepeater(
            clock = System::currentTimeMillis,
            position = { myLocation.value?.let { Triple(it.lat, it.lon, it.accuracyM) } },
            send = { p -> sendToFriends(p) },
        )
    }

    private val _lastStop = MutableStateFlow<StopNotice?>(null)
    /** Set on start when MoshGrid was stopped or frozen; drives the "you were unreachable" banner. */
    val lastStop: StateFlow<StopNotice?> = _lastStop.asStateFlow()
    fun reportStop(n: StopNotice?) { _lastStop.value = n }

    /** Send to every friend; location skips friends the user paused. */
    fun sendToFriends(p: MoshPayload): Map<String, MoshRoute> {
        if (!isInitialised) return emptyMap()
        val paused = if (p is LocationUpdate) prefs.pausedFriends else emptySet()
        return friends.friends().filter { it.peerID !in paused }.associate { it.peerID to sender.send(p, it.peerID) }
    }

    /** Our own meeting point: apply locally, then tell friends. */
    fun setMeetingPoint(p: MeetingPoint) {
        store.setMeetingRecord(p)
        sendToFriends(p)
    }
}
