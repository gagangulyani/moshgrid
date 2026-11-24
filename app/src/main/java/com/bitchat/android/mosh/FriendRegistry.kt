package com.bitchat.android.mosh

import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.noise.NoisePeerIdentity
import java.security.MessageDigest

enum class FriendStatus { NOT_FRIEND, WAITING_FOR_THEM, FRIEND }

data class Friend(val peerID: String, val noiseKeyHex: String, val displayName: String, val status: FriendStatus)

/**
 * Who counts as a MoshGrid friend.
 *
 * A friend is someone we favourited after an in-person QR verification of their Noise key.
 * Favourites alone are not trusted (upstream's favourites could be spoofed, Trail of Bits 2025).
 * Location, meeting points and Help are only sent to, and only accepted from, friends.
 * WAITING_FOR_THEM: we scanned them but they have not scanned us back yet.
 */
interface FriendRegistry {
    fun status(peerID: String): FriendStatus
    fun isFriend(peerID: String): Boolean = status(peerID) != FriendStatus.NOT_FRIEND
    fun friends(): List<Friend>
    fun displayName(peerID: String): String?
}

class DefaultFriendRegistry(
    private val relationships: () -> List<FavoriteRelationship>,
    private val fingerprintOfKey: (ByteArray) -> String,
    private val peerIdOfKey: (ByteArray) -> String?,
    private val verifiedFingerprints: () -> Set<String>,
) : FriendRegistry {

    override fun friends(): List<Friend> {
        val verified = verifiedFingerprints()
        return relationships().mapNotNull { rel ->
            if (!rel.isFavorite || fingerprintOfKey(rel.peerNoisePublicKey) !in verified) return@mapNotNull null
            val peerID = peerIdOfKey(rel.peerNoisePublicKey) ?: return@mapNotNull null
            Friend(
                peerID = peerID,
                noiseKeyHex = rel.peerNoisePublicKey.joinToString("") { "%02x".format(it) },
                // A nickname can be blank (verified before their name arrived); never show an empty name.
                displayName = rel.peerNickname.trim().ifEmpty { "Friend ${peerID.take(4)}" },
                status = if (rel.theyFavoritedUs) FriendStatus.FRIEND else FriendStatus.WAITING_FOR_THEM,
            )
        }
    }

    override fun status(peerID: String): FriendStatus =
        friends().firstOrNull { it.peerID == peerID }?.status ?: FriendStatus.NOT_FRIEND

    override fun displayName(peerID: String): String? = friends().firstOrNull { it.peerID == peerID }?.displayName

    companion object {
        /** Registry backed by the app's favourites store and verified fingerprints. */
        fun fromApp(verifiedFingerprints: () -> Set<String>): DefaultFriendRegistry = DefaultFriendRegistry(
            relationships = { FavoritesPersistenceService.shared.getOurFavorites() },
            fingerprintOfKey = { key -> MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) } },
            peerIdOfKey = { key -> NoisePeerIdentity.derivePeerID(key) },
            verifiedFingerprints = verifiedFingerprints,
        )
    }
}
