# MoshGrid invite links (v1)

Adds a friend without meeting: A shares a link in any chat app, B taps it and sends a request, A
accepts. After that they are full friends, exactly as after a QR scan (location, Find, Help,
groups, photos). Nothing about either person is uploaded anywhere except the encrypted request
and answer, which travel as Nostr gift-wrapped DMs.

## The link

```
https://moshgrid.vercel.app/add#<base64url, no padding>
moshgrid://add#<same>
bytes = 01 key(noise) key(nostr) token(16) u64(expiresAtSec) str(name)
```

The code is in the `#fragment`, which browsers never send to the website. Anyone who sees the
link sees the name and two public keys, not a location. The `/add` page only offers to open the
app or download it. Links last 7 days; a link with less than a day left is replaced by a fresh one
when shared again. "Stop old links working" replaces the token at once.

Parsing is fail-closed: wrong version, short or trailing bytes, blank or over-long name, an
all-zero key or no expiry make it invalid.

## Payload

| Type | Value | Payload |
|------|-------|---------|
| `FRIEND_INVITE` | `0x48` | a request (kind 0) or an accept (kind 1) |

```
FRIEND_INVITE = 01 u8(kind) token(16) key(noise) key(nostr) str(name) u64(atMs)
```

Sent over Nostr only. Over Bluetooth it is dropped (requests come from people who are not friends
yet, and the mesh friend gate would refuse them anyway). Golden vectors:
`app/src/test/kotlin/com/bitchat/android/mosh/InviteTest.kt`.

## Rules (`InviteBook`)

- **Request** (B to A): kept only if its Nostr key is the one that signed the gift wrap, it carries
  A's current unexpired token, and it is not from A or from someone already a mutual friend. One
  per person; at most 20 waiting. A sees it on the Friends page, a home banner and a notification.
- **Accept** (A to B): counts only if it matches a request B sent with A's link: same token, same
  Noise and Nostr keys as the link, within 7 days. It echoes the token of the request, so it
  still works after A replaced the link.
- **Both sides** then record the other exactly as a QR scan does: favourite, verified
  fingerprint, Nostr key, "they added us". B shows as "waiting" from sending until the accept.
- Requests, sent requests and the token sit in the encrypted MoshGrid preferences; panic wipe
  clears them.

## Trust

Accepting a request makes them a full friend. The safety is that nobody is added without the
link owner's explicit Accept, links expire, and old links can be killed. A forwarded link can
reach a stranger; the request shows their chosen name only, so accept people you expect.

## Contacts

"Send to a contact" uses the system phone picker (no contacts permission; Android grants access to
the one number picked) and opens SMS or WhatsApp with the link. Contacts are never read in bulk,
stored or sent anywhere.
