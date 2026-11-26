# MoshGrid profile photos (v1)

Each person can set one profile photo. It goes only to QR-verified mutual friends, inside the Noise
envelope like location and Help, so relays and nearby phones see only ciphertext. Group members who
are not friends never get it; they see initials.

## Payload

| Type | Value | Payload |
|------|-------|---------|
| `PROFILE_PHOTO` | `0x47` | my current photo, or "I removed it" |

```
PROFILE_PHOTO = 01 u64(updatedAtMs) u16(len) bytes(jpeg)
                len 0 = removed; otherwise 3..12288 bytes starting FF D8 FF (JPEG)
```

Same codec conventions as the other MoshGrid payloads (`MoshCodec`, see `MOSH_GROUPS.md`).
Decoding is fail-closed: wrong version, `updatedAtMs` of 0, a length above 12 KiB, bytes that do
not start like a JPEG, a short read or trailing bytes make it `null`. Golden vectors:
`app/src/test/kotlin/com/bitchat/android/mosh/ProfilePhotoTest.kt` (`photo_golden_bytes`).

## Rules (`ProfilePhotoBook`)

- **Who.** `MoshInbox` drops a photo from anyone who is not a QR-verified friend before decoding.
  The photo is filed under the friend's Noise key.
- **Which one.** A photo is kept only if its `updatedAtMs` is newer than the one held for that
  friend, is no more than 24 h ahead of this phone's clock, and decodes as a square image 16 to
  512 pixels a side. A removal is kept as a marker, so an older copy arriving late cannot bring the
  photo back.
- **Sending.** The app makes a 160 × 160 JPEG (centre crop, EXIF orientation applied, quality
  lowered until it is at most 10 KiB). Each mutual friend gets it once per change, and again every
  12 h in case a copy was lost; a friend added later gets it on the next minute tick. Routing is
  the normal `MoshSender` path: Bluetooth when there is an encrypted session, otherwise the
  internet. A change stamps `max(now, previous + 1)` so friends always take it even if the clock
  went back.
- **Storage.** Photos and the "sent" record sit in the encrypted MoshGrid preferences. Removing a
  friend deletes their photo; panic wipe deletes all of them.
- **Picking.** Android's photo picker: no storage permission, and only the chosen image is shared
  with the app.

## Not in v1

Group photos, photos for group members who are not friends, and full-size viewing.
