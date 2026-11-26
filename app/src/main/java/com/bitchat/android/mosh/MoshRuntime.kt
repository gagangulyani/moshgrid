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
        // Help and friends' last locations are sensitive: encrypted at rest. Falls back to private
        // plain storage only if the Android Keystore is broken on this phone (Help must still work).
        val helpPrefs = secureHelpPrefs(app)
        FriendStateStore.sharedPersistence = PrefsReceivedHelp(helpPrefs)
        FriendStateStore.sharedSnapshot = PrefsFriendSnapshot(helpPrefs)
        helpOutbox = PrefsHelpOutbox(helpPrefs)
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
            internetOnline = { com.bitchat.android.nostr.NostrRelayManager.getInstance(app).isConnected.value },
        )
        MoshNetworkGate.apply(app, prefs)
        MoshInbox.friends = friends
        MoshGroups.init(app, helpPrefs)
        ProfilePhotos.init(helpPrefs)
        MoshInvites.init(helpPrefs)
        MoshGroups.transport = { p, peer, member, allowNet ->
            val encoded = MoshCodec.encode(p)
            val mesh = prefs.bluetooth && MeshServiceHolder.getUnifiedOrCreate(app).sendMoshPayload(encoded, peer)
            // Messages and rosters also go over the internet (a Bluetooth send has no delivery proof);
            // receipts only when Bluetooth could not take them, to keep relay traffic down.
            val wantNet = allowNet && nostrAllowed(prefs) && (p !is GroupReceipt || !mesh)
            val net = wantNet && NostrTransport.getInstance(app).sendMoshPayload(encoded, member.noiseKeyHex, member.nostrPubHex.ifEmpty { null })
            mesh to net
        }
        MoshInbox.sink = { from, p ->
            if (p is GroupRoster || p is GroupMessage || p is GroupReceipt) MoshGroups.onReceived(app, from, p)
            else if (p is ProfilePhoto) ProfilePhotos.onReceived(from, p)
            else {
                // The notifier acts on what the store decided: a replayed, late or cancelled alert never rings.
                val change = store.apply(from, p)
                HelpNotifier.onChange(app, from, change, store.activeHelp.value[from])
                onReceived?.invoke(app, from, p)
            }
        }
    }

    @Volatile private var helpOutbox: HelpOutboxPersistence? = null

    fun updateMyLocation(l: LocationUpdate) { _myLocation.value = l }

    /**
     * Friend's name for a mesh peer ID or a contact conversation ID ("contact_" + fingerprint).
     * MoshGrid announces no names in clear, so screens and notifications use this. Null if unknown.
     */
    fun friendNameFor(id: String): String? = friendFor(id)?.displayName?.takeIf { it.isNotBlank() }

    /** The friend behind a chat id: their peer id, or a contact conversation id carrying their fingerprint. */
    fun friendFor(id: String): Friend? {
        if (!isInitialised) return null
        val resolver = com.bitchat.android.services.ContactIdentityResolver
        val fingerprint = resolver.fingerprintFromContactConversationId(id)
        return friends.friends().firstOrNull { f ->
            f.peerID == id || (fingerprint != null &&
                resolver.bytesFromHex(f.noiseKeyHex)?.let { resolver.fingerprintHex(it) } == fingerprint)
        }
    }

    /** Help to friends. Position comes from the latest fix. */
    val help: HelpRepeater by lazy {
        HelpRepeater(
            clock = System::currentTimeMillis,
            position = { myLocation.value },
            send = { p -> sendToFriends(p) },
            persistence = helpOutbox,
            lateResend = quietResend,
        )
    }

    private val _lastStop = MutableStateFlow<StopNotice?>(null)
    /** Set on start when MoshGrid was stopped or frozen; drives the "you were unreachable" banner. */
    val lastStop: StateFlow<StopNotice?> = _lastStop.asStateFlow()
    fun reportStop(n: StopNotice?) { _lastStop.value = n }

    /** Send to every friend; location skips friends the user paused. */
    /**
     * Friends who scanned us back. Someone we added who has not (or who removed us) gets nothing:
     * they would drop it anyway, and our location should not go to them.
     */
    fun mutualFriends(): List<Friend> =
        if (!isInitialised) emptyList() else friends.friends().filter { it.status == FriendStatus.FRIEND }

    fun sendToFriends(p: MoshPayload): Map<String, MoshRoute> {
        if (!isInitialised) return emptyMap()
        val paused = if (p is LocationUpdate) prefs.pausedFriends else emptySet()
        return mutualFriends().filter { it.peerID !in paused }.associate { it.peerID to sender.send(p, it.peerID) }
    }

    private val _wipes = MutableStateFlow(0)
    /** Bumped by [wipe]; the UI restarts MoshGrid's first-run setup when it changes. */
    val wipes: StateFlow<Int> = _wipes.asStateFlow()

    /**
     * Panic wipe of everything MoshGrid keeps: own Help (after one last cancel), friends' Help and
     * locations, the meeting point, settings, socials links and people met, and diagnostics. Run before the identity is wiped.
     */
    fun wipe(context: Context) {
        val app = context.applicationContext
        if (isInitialised) {
            help.wipe()
            MoshGroups.wipe()
            ProfilePhotos.wipe()
            MoshInvites.wipe()
            OfflineMaps.wipe(app)
            HelpNotifier.cancel(app, store.activeHelp.value.keys)
            store.wipe()
            prefs.clear()
            MoshNetworkGate.apply(app, prefs)
        }
        _myLocation.value = null
        _lastStop.value = null
        MoshMonitor.wipe(app)
        MoshSocials.wipe(app)
        _wipes.value += 1
    }

    /**
     * Remove a friend: they stop getting our location, Help and meeting points, and what we hold
     * about them is forgotten. Adding them again needs a new QR scan or invite link.
     */
    fun removeFriend(context: Context, friend: Friend) {
        if (!isInitialised) return
        val key = com.bitchat.android.services.ContactIdentityResolver.bytesFromHex(friend.noiseKeyHex) ?: return
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(context.applicationContext)
        com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(key, friend.displayName, false)
        // Also forget the in-person verification: adding them again needs a new QR scan.
        runCatching {
            val identity = SecureIdentityStateManager(context.applicationContext)
            identity.setVerifiedFingerprint(identity.generateFingerprint(key), false)
        }.onFailure { MoshMonitor.failed("unfriend_failed", it) }
        // Tell them (Bluetooth if connected, else internet), so their list stops showing us as a friend.
        runCatching { com.bitchat.android.services.MessageRouter.tryGetInstance()?.sendFavoriteNotification(friend.peerID, false) }
            .onFailure { MoshMonitor.failed("unfriend_failed", it) }
        prefs.setPaused(friend.peerID, false)
        HelpNotifier.cancel(context.applicationContext, listOf(friend.peerID))
        store.forget(friend.peerID)
        ProfilePhotos.forget(friend.noiseKeyHex)
    }

    /** Re-sends the latest meeting point over Bluetooth so friends who were away still get it. */
    val meeting: MeetingResender by lazy {
        MeetingResender(clock = System::currentTimeMillis, latest = { store.ownLatestMeeting }, resend = quietResend)
    }

    private val quietResend: QuietResend by lazy {
        QuietResend(
            friendIds = { mutualFriends().map { it.peerID } },
            send = { p, to, meshOnly -> sender.send(p, to, meshOnly) },
        )
    }

    /** Our own meeting point: apply locally, then tell friends. */
    fun setMeetingPoint(p: MeetingPoint) {
        // Our change must win even if the current point's time came from a phone whose clock runs ahead.
        val after = store.latestMeeting?.setAtMs?.plus(1) ?: Long.MIN_VALUE
        val stamped = if (p.setAtMs < after) p.copy(setAtMs = after) else p
        store.setMeetingRecord(stamped, own = true)
        sendToFriends(stamped)
    }

    private fun secureHelpPrefs(app: Context): android.content.SharedPreferences = try {
        val key = androidx.security.crypto.MasterKey.Builder(app, androidx.security.crypto.MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            app, "moshgrid_secure", key,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also {
            // Earlier test builds kept this in plain storage: remove it.
            app.getSharedPreferences("moshgrid_help", Context.MODE_PRIVATE).edit().clear().commit()
        }
    } catch (e: Exception) {
        MoshMonitor.event(app, "secure_prefs_failed")
        app.getSharedPreferences("moshgrid_help", Context.MODE_PRIVATE)
    }
}
