# Security

## Reporting a vulnerability

Email **gagangulyanig@gmail.com** with "MoshGrid security" in the subject. Please don't open a public GitHub issue for anything that could put users at risk (for example a way to read someone's messages or location, to add yourself as someone's friend without their accept, or to get a phone to install a fake update).

Include the app version (You, About), what you did, and what happened. You'll get a reply within 7 days. Fixes ship as an app update, which installed apps pick up on their own.

## Scope

- The MoshGrid Android app (this repository, `mosh` and `ui/mosh` packages, and the parts of bitchat-android it uses).
- moshgrid.vercel.app (downloads, the update file `version.json`, invite and socials pages).
- MoshGrid's relay (`wss://relay.gagangulyani.com`) and error server (`errors.gagangulyani.com`).

Issues in upstream bitchat-android code that MoshGrid shares are welcome too; we'll pass them upstream.

## How MoshGrid protects users

- Messages, locations, Help alerts and photos are end-to-end encrypted to each friend (Noise over Bluetooth, NIP-17 gift wraps over Nostr). Relays can't read them.
- Friends are only added in person by QR code or by an invite the other person must accept.
- Updates are installed only if the APK's SHA-256 matches `version.json` and it is signed with the MoshGrid release key.
- Reports carry no location, messages, names, friends or phone ID. The error server keeps the sending address with its last part removed, for 30 days. See https://moshgrid.vercel.app/privacy.
