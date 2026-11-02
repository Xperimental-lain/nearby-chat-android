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

## Current limits

- Connections are direct and one-hop; this is not yet a relaying mesh.
- Bluetooth peers must already be paired in Android settings.
- Media currently works only over the same local Wi-Fi network; Wi-Fi Direct is not implemented.
- The Wi-Fi identity handshake is not cryptographic authentication, and Wi-Fi messages/files are not end-to-end encrypted. The file prompt limits unsolicited transfers but cannot verify who sent one. Use only for non-sensitive testing on a trusted network.
- Routers with client isolation can block local peer connections.

## Initial implementation constraints

- Android first; cross-platform protocol compatibility is a later milestone.
- No account, cloud server, or internet connection is required for local messaging.
- Wi-Fi Direct and local-LAN Wi-Fi are separate paths; local-LAN is implemented first.

## Development environment

Build with JDK 17 and Android SDK 35 using `./gradlew assembleDebug`.

To try it, install the debug APK on two Android phones. Grant Nearby devices permissions. For Wi-Fi, put both phones on the same router/AP, open Nearby Chat, wait for the single peer entry, then send text or use Attach. For Bluetooth, pair the phones in Android settings first and open the app on both phones. When both paths are available, short text uses Bluetooth first and falls back to Wi-Fi if the Bluetooth send fails. Attachments always use Wi-Fi and the receiving phone asks before saving. Keep the app open during transfers.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).
