# MoshGrid

Find your friends in the crowd, even when the phone network dies.

At a big gig, thousands of phones choke the network and your group scatters. MoshGrid lets the friends you came with see where each other are, message each other and call for help, phone to phone over Bluetooth when there is no signal, and over the internet whenever there is some.

**Download for Android:** https://moshgrid.vercel.app (Android 8 and newer, free, no account)

<table>
  <tr>
    <td><img src="website/img/help_hold.webp" alt="Ask for help: hold the red button for three seconds, with a note: red cap, near the sound desk" width="200"/></td>
    <td><img src="website/img/help_alert.webp" alt="A friend's phone after a Help alert: a dotted arrow and the distance counting down as they walk to you" width="200"/></td>
    <td><img src="website/img/compass.webp" alt="The Find compass: a sweep turns and friends are placed around you by direction and distance" width="200"/></td>
    <td><img src="website/img/group.webp" alt="A group chat: a message goes from sent to delivered to read, and a friend replies" width="200"/></td>
  </tr>
  <tr>
    <td>Hold Help for 3 seconds</td>
    <td>Friends get an arrow to you</td>
    <td>See everyone around you</td>
    <td>Group chats, no signal needed</td>
  </tr>
</table>

## What it does

- **Find.** A compass shows your friends around you by direction and distance, turning with your phone. Tap someone for a big arrow and how far away they are. A list and a map are one tap away. Festival venue maps ship inside the app, so they work with no data at all; elsewhere, with internet on, a street map comes from OpenFreeMap. Save the street map of any place beforehand (You, Offline maps) and it draws with no signal, even with MoshGrid's internet switched off. See [docs/MOSH_MAPS.md](docs/MOSH_MAPS.md).
- **Help.** Hold the Help button for three seconds and your location, plus a note like "red cap, near the sound desk", goes to every friend. It keeps resending until it gets through and tells you who it reached. Friends get an alarm-style alert, which can sound through Do Not Disturb if they allow it.
- **Meeting points.** Drop a pin and your group gets it. Every phone that has it keeps passing it on for a while, so friends who were out of range get it when they come back.
- **Friends-only chat and groups.** Message the people you scanned, nobody else, one to one or in groups of up to 12. Ticks show who has your message and who read it; tap a message to see each person.
- **Adding friends.** Scan each other's QR code in person, or send an invite link in any chat app (or pick one contact to text it to). Nobody is added until you accept their request, links expire after a week, and your contacts never leave the phone. Only friends can see your location or message you.
- **Share your socials.** The Share tab shows a code with links to your Instagram, Spotify or any profile you add, for people you meet at the gig. Any phone camera opens it as a web page, so they don't need MoshGrid. It holds only the links you tick, never your location or a way to message you. Scan other people's codes on the same tab and save them to People I met. The Quick Settings tile "My socials" shows your code even from the lock screen. See [docs/MOSH_SOCIALS.md](docs/MOSH_SOCIALS.md).
- **Profile photos.** Add a photo and your friends see it on the compass, in chats and in groups. It goes only to friends, encrypted like your messages.
- **Stays up to date.** MoshGrid checks moshgrid.vercel.app for new versions, verifies the download is signed with the MoshGrid key, and installs it; Android asks you to confirm when it needs to. To update straight away, tap Check for updates in You, About.
- **Network status.** See whether MoshGrid is reaching its relay, a backup relay, or only Bluetooth right now.

## How it gets messages through

Every MoshGrid phone passes messages on to the next one over Bluetooth, so a message can hop across the crowd to a friend out of direct range. When any phone has internet, messages also go through MoshGrid's own Nostr relay, end-to-end encrypted, so the relay cannot read them. Sharing your location and using the internet can each be turned off in settings.

Honest limits: Bluetooth reaches roughly across a large room between two phones, it works best when there are other MoshGrid phones in between, iPhone isn't supported yet, and MoshGrid is not an emergency service. In danger, find venue staff or call 112.

## MoshGrid and bitchat

MoshGrid is a fork of [bitchat-android](https://github.com/permissionlesstech/bitchat-android). We didn't build the mesh: the Bluetooth mesh, the Noise encryption and the Nostr transport all come from bitchat, and they are most of the code in this repository. bitchat's authors deserve the credit for that part.

The app built on top of it is a different product:

| | bitchat | MoshGrid |
|---|---|---|
| Who you talk to | Anyone nearby, often strangers, in public rooms and location channels | Only friends you scanned in person |
| What it's for | Open, anonymous chat | Finding your group at a gig and getting help |
| Main screen | Chat | Your friends, with a compass to find them one tap away |
| Getting help | Not a feature | Hold-to-send Help that retries until friends get it and says who did |
| Location | Coarse location channels shared with everyone in them | Your exact spot, shared only with your friends |

What MoshGrid adds: Find with offline venue and saved street maps, friend location sharing, Help with retries and delivery status, meeting points, friends by QR scan or invite link, profile photos, groups, self-updates, a new four-tab interface, and battery guidance for keeping the app running all show (the `mosh` and `ui/mosh` packages).

What MoshGrid hides or removes from bitchat: public mesh chat, location channels and the globe, voice notes, photo and file messages, Cashu payments and Tor. Phones still relay other MoshGrid phones' encrypted packets, so everyone in the crowd helps carry messages.

## Build

Requires JDK 21 and the Android SDK versions in `gradle/libs.versions.toml`.

```sh
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
```

Protocol specs for MoshGrid's own payloads: `docs/MOSH_GROUPS.md`, `docs/MOSH_PHOTOS.md`, `docs/MOSH_INVITES.md`.

Release builds are signed with a key that is not in this repository. Crash reporting is off unless you build with `-PsentryDsn=...`, and in the app it is on by default: the switch is shown during setup, nothing is sent before setup ends, and it can be turned off any time in You. See `AGENTS.md` for project conventions and `docs/` for protocol specs and the release guide.

## Verify a download

The website lists the SHA-256 of each APK and of the signing certificate. Compare them with:

```sh
shasum -a 256 MoshGrid-1.3.0.apk
apksigner verify --print-certs MoshGrid-1.3.0.apk
```

## Licence

MoshGrid is free software under the GNU General Public License v3, the same licence as bitchat-android. See `LICENSE.md` and `NOTICE`.
