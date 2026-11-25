# MoshGrid groups (v1)

Small private group chats (up to 12 people) that work over the Bluetooth mesh and, when there is
signal, over Nostr. There is no group server: every message is sent as its own Noise-encrypted copy
to each member, so relays and nearby phones only ever see ciphertext.

## Payloads

Three MoshGrid payload types travel inside the Noise envelope, next to location, meeting point and
Help (`NoisePayloadType`, 0x40+ range):

| Type | Value | Payload |
|------|-------|---------|
| `GROUP_ROSTER` | `0x44` | who is in a group |
| `GROUP_MESSAGE` | `0x45` | a message, or "I left" |
| `GROUP_RECEIPT` | `0x46` | delivered or read, for one message |

All share the MoshGrid codec conventions (`MoshCodec`): first byte is version `1`; integers are
big-endian; `str` is a u8 length plus UTF-8 bytes; `key` is 32 raw bytes. Decoding is fail-closed:
unknown version, unknown kind, out-of-range lengths, trailing bytes or any rule below make the
payload `null`, and it is dropped.

```
GROUP_ROSTER   = 01 str(groupId) str(name) key(creator) u64(updatedAtMs)
                 u8(count) count × [ key(noise) optKey(nostr) str(name) ]
                 optKey = 00 | 20 key        (Nostr key absent or present)
GROUP_MESSAGE  = 01 str(groupId) str(messageId) u8(kind) u16(len) bytes(text) u64(sentAtMs)
                 kind 0 = text (1..500 bytes, not blank), kind 1 = left (empty text)
GROUP_RECEIPT  = 01 str(groupId) str(messageId) u8(read: 0 delivered, 1 read) u64(atMs)
```

Limits: ids 1..64 bytes, group and member names up to 64 bytes (the app writes at most 32),
2..12 members, member Noise keys unique, the creator must be a member. Golden vectors are in
`app/src/test/kotlin/com/bitchat/android/mosh/MoshGroupsTest.kt` (`receipt_golden_bytes`,
`roster_golden_bytes`).

## Trust rules (`GroupStore`)

- **Roster.** Accepted only if the sender is the group's creator, the creator is a QR-verified
  friend of mine, I am in the member list, and `updatedAtMs` is newer than the roster I hold. The
  creator of a group never changes. A roster without me closes the group on my phone (read-only).
- **Who I trust in a group.** Members who are not my friends are trusted because my friend, the
  creator, vouched for their keys. The app labels them "Not your friend · added by <creator>".
- **Messages and receipts.** Accepted only from current members of a group I am in. On the mesh the
  sender is the Noise-authenticated peer ID, derived from the member's Noise key. Over Nostr the
  sender is matched by the Nostr key in the roster (friends by their favourite record first).
- **Early messages.** A message for a group I do not know yet waits (at most 50) until that group's
  roster arrives, then is checked against it; messages from non-members are dropped then.
- **Repeats.** A message id from the same sender is stored once; a repeat is acknowledged again.
- Location, meeting points and Help stay friend-only. Group membership never grants them.

## Delivery and receipts (`MoshGroups`)

- Sending fans out one copy per member: Bluetooth if a session is up (a handshake is started if
  not), plus one internet copy. Rosters go both ways too. Receipts go over the internet only when
  Bluetooth could not take them, to keep relay traffic down.
- Undelivered copies are retried over Bluetooth every minute for 12 hours; the creator resends the
  roster with each retry, so late joiners can read the message.
- The receiver answers every accepted message with a *delivered* receipt to its author, and with a
  *read* receipt when the group's chat is on screen.
- Per-member state only moves forward: waiting → sent → delivered → read. The bubble shows one
  tick when sent to anyone, two grey ticks when delivered to everyone, two cyan ticks when read by
  everyone. Tapping your own message lists each member with their state and time.

## Membership changes

- Only the creator can rename, add or remove people. Removed people get the new roster, so their
  chat closes.
- Anyone can leave: they send a `left` message to the others and their chat closes. Others stop
  sending to them.
- If the creator leaves, the group carries on with the members it has, but nobody can change it.

## Storage

Groups live in MoshGrid's encrypted preferences (the same file as Help and friends' last
locations), at most 500 messages per group, and are erased by the panic wipe.

## Testing

`MoshGroupsTest` covers the codec and every rule above. Groups touch messaging and packets, so a
release needs a Mesh Lab run on two physical phones (see `docs/release-gate-runbook.md`): make a
group, send both ways, check delivered and read ticks with Bluetooth only, then with internet only.
