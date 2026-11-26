# Socials codes

A socials code lets you give people you meet at a gig your public profile links. It is made for
strangers: it is not a friend code and it holds nothing that finds you or reaches you in MoshGrid.

## What it holds

- A display name, 0 to 40 characters (your MoshGrid name).
- 1 to 6 links, each one you added and ticked on the Share tab. When you type a link without
  `https://`, the app adds it; a code itself must always carry the full `https://` link.

Never in it: your MoshGrid ID or keys, your location, a way to message you, or anything about the phone.

## Format

```
https://moshgrid.vercel.app/s#1.<name>.<link 1>.<link 2>…
```

- `1` is the version. Anything else is rejected.
- Every other part is the UTF-8 text in base64url without padding (`A-Z a-z 0-9 - _`).
- The part after `#` is never sent to the website's server by a browser.

A code is read only if every rule below holds; otherwise it is refused as a whole (fail closed):

- It starts with exactly `https://moshgrid.vercel.app/s#` and is at most 2,200 characters.
- Version `1`, then a name, then 1 to 6 links, all valid base64url and valid UTF-8.
- The name has no control characters, no leading or trailing spaces, and is at most 40 characters.
- Each link is at most 200 characters, printable ASCII, starts with `https://`, has a host with at least
  one dot and a 2 to 63 letter ending, an optional port, no user name or password (`@` before the host),
  and none of `" < > \ ^ ` { | }`. The same link may not appear twice.

## Golden vectors

| Card | Code |
|---|---|
| Ana: `https://instagram.com/ana.moshes`, `https://open.spotify.com/user/ana` | `https://moshgrid.vercel.app/s#1.QW5h.aHR0cHM6Ly9pbnN0YWdyYW0uY29tL2FuYS5tb3NoZXM.aHR0cHM6Ly9vcGVuLnNwb3RpZnkuY29tL3VzZXIvYW5h` |
| `Jo 🎧`: `https://soundcloud.com/jo-on-stage?ref=gig` | `https://moshgrid.vercel.app/s#1.Sm8g8J-Opw.aHR0cHM6Ly9zb3VuZGNsb3VkLmNvbS9qby1vbi1zdGFnZT9yZWY9Z2ln` |

Tests: `app/src/test/kotlin/com/bitchat/android/mosh/SocialsCodeTest.kt`.

## Where it is read

- MoshGrid's Share tab, Scan: shows the card. The same scanner also takes friend codes (add-friend flow)
  and invite links.
- Any phone camera opens the web page `https://moshgrid.vercel.app/s`, which reads the fragment with the
  same rules in the browser and shows the links as text. It needs signal on that phone.
- On a phone with MoshGrid, the link opens the card in the app (App Link).

A card never opens a link by itself. Full addresses are shown and each link opens only when tapped, after
the rules are checked again.

## On the phone

- My links, which ones the code leaves out, and People I met are kept in the app's private storage only.
  Panic wipe clears them.
- The code shows at full screen brightness. The Quick Settings tile "My socials" shows it over the lock
  screen: the code only, with no editing and no way into the app without unlocking.
