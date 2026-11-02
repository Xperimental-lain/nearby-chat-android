# Nearby Chat

An Android-first prototype for offline, direct messaging over nearby transports:

> **Prototype warning:** This project is experimental and has not been tested across a range of devices. Wi-Fi connections are not cryptographically authenticated and messages and files are not end-to-end encrypted. Do not use it for sensitive communication.

- Bluetooth Classic RFCOMM for short text messages between already paired phones.
- Local Wi-Fi for direct chat and media transfer between devices on the same router/access point, including networks without internet.

Routers and access points can extend a local network. Ethernet switches can connect that network's access points, but phones connect to the access points over Wi-Fi. If router client isolation blocks local discovery, this prototype can still message a directly paired Bluetooth peer; it does not relay through other phones.

## Implemented in this prototype

- One peer entry per device, merged across Wi-Fi and Bluetooth using a random session ID.
- Short text prefers Bluetooth, with automatic Wi-Fi fallback if that send fails. Larger text and attachments use Wi-Fi.
- Local Wi-Fi peer refresh and removal when a service disappears; Bluetooth can be retried after turning it on.
- Incoming Wi-Fi files require approval, are limited to 100 MB each and 200 MB total cache usage, and incomplete files are deleted.
- Unknown-size attachments are staged locally and sent if they fit the same per-file limit.
- Public and private group rooms with shareable invite codes. Group rooms are saved on the device; group message history is currently kept only while the app is open.
- Public group text is sent to all discovered Nearby Chat peers on the same local Wi-Fi network. Private group text is encrypted with AES-GCM using a shared key in the private invite.

## Current limits

- Connections are direct and one-hop; this is not yet a relaying mesh. Group messages use local Wi-Fi only and are sent to currently discovered peers; new members do not receive earlier messages.
- Bluetooth peers must already be paired in Android settings.
- Media currently works only over the same local Wi-Fi network; Wi-Fi Direct is not implemented.
- Direct Wi-Fi messages and files are not end-to-end encrypted. Private group message text is AES-GCM encrypted for people holding the invite key, but the group name and sender metadata remain visible on the local network, and the shared key does not verify which member sent a message. Anyone on the LAN can read public group messages. The Wi-Fi identity handshake is not cryptographic authentication. Use only for non-sensitive testing on a trusted network.
- Routers with client isolation can block local peer connections.

## Initial implementation constraints

- Android first; cross-platform protocol compatibility is a later milestone.
- No account, cloud server, or internet connection is required for local messaging.
- Wi-Fi Direct and local-LAN Wi-Fi are separate paths; local-LAN is implemented first.

## Development environment

Build with JDK 17 and Android SDK 35 using `./gradlew assembleDebug`.

To try it, install the debug APK on two Android phones. Grant Nearby devices permissions. For direct Wi-Fi, put both phones on the same router/AP, open Nearby Chat, wait for the peer entry, then send text or use Attach. For Bluetooth, pair the phones in Android settings first and open the app on both phones. When both paths are available, short direct messages use Bluetooth first and fall back to Wi-Fi if the Bluetooth send fails. Attachments always use Wi-Fi and the receiving phone asks before saving. Keep the app open during transfers.

For groups, open the Groups tab, create a public or private room, then copy its invite code to the other phone and use Join. Group messages require the phones to be discovered over the same local Wi-Fi network. Public group text is readable by nearby app peers; private group text is encrypted with the shared invite key. Group attachments, message history sync, Bluetooth group delivery, and relay through other phones are not implemented yet.

## License

Copyright (c) 2026 Xperimental-lain.

Unless stated otherwise, this project is licensed under the GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE).

The version publicly released under MIT remains available under that earlier license to anyone who received it; this change applies to the project going forward.
