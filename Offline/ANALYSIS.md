# Intercomm APK analysis and MVP

## Observed artifact

The analyzed artifact is `/home/legend/Downloads/new/Intercomm.apk`, SHA-256
`a33df353944c427dbc7e8d89e3c03c2faf495d23fc3d0e2b28d9dfe4ae89c87a`.
REA/JADX identified package `com.androidintercom`, version `2.2.14` (104),
minimum Android API 26, target API 36, 4,635 classes, and 924 resources.

REA evidence: `ev_76232b5e73c36d0d7aa7e98d561abe1dc70a9e11f090d511db00399c26f9b255`.
The package was statically inspected; it was not executed and its signature was
not verified.

## Main features and workflow

The primary workflow is a local-network intercom:

1. Launch the main device screen.
2. Discover or add intercom peers over Wi-Fi, Bluetooth, nearby-device flows, or
   forwarding.
3. Select a peer/device group and use push-to-talk.
4. Capture microphone audio in a foreground service, packetize it, and send it
   to peers.
5. Play incoming audio and expose ongoing state through a persistent
   notification.

The app also contains settings for device identity, audio mode/quality, speaker
use, sound effects, broadcast behavior, auto-start, battery optimization, dark
mode, volume PTT, and voice detection. It includes PTT mapping/detection for
volume, media/play-pause, camera, and key-code buttons.

Static implementation markers show a foreground `IntercomService`, Wi-Fi
datagram communication with broadcast/multicast/unicast options, Bluetooth and
forwarding mechanisms, device models for Wi-Fi/Bluetooth/nearby/forwarded peers,
and event types for recording, sending audio, calls, permissions, and playback.
Bundled audio resources include start/stop talk, call, and beep sounds.

## Technology stack

Observed: Kotlin, AndroidX, lifecycle, Navigation, Room, WorkManager, Koin,
Kotpref, Material/AppCompat UI, Rx-style buses/disposables, protobuf-generated
intercom messages, Java UDP sockets, Bluetooth APIs, foreground microphone
service, and runtime permission handling.

## Rebuild decisions

This workspace implements a focused, independently authored Kotlin MVP rather
than copying the original implementation or assets:

- Jetpack Compose + Material 3 for the device list, PTT control, and settings.
- A foreground microphone service with a persistent notification.
- Independent UDP framing (`ICM2`, sender ID, sequence number, PCM payload)
  on port `45454`, with direct unicast fan-out and broadcast fallback.
- Audio capture at 16 kHz mono PCM and playback through `AudioTrack`.
- Clear extension points for real peer discovery, Bluetooth transport, device
  persistence, PTT hardware mapping, and audio effects.

The current rebuild intentionally does not claim wire compatibility with the
analyzed app. It was rebuilt around the same observed separation of concerns:

- `PeerDiscovery` owns identity announcements, per-interface IPv4 broadcast
  selection, peer timeout, and address tracking.
- `IntercomService` owns the persistent foreground lifecycle, audio capture,
  packet receive/playback, speaker routing, microphone state, and unicast
  fan-out.
- The room is always receiving and playing audio; microphone transmission is
  independently toggled on or off.
- The room limit is one local device plus five discovered peers (`6/6` total).
- Audio is captured and played as native 16 kHz mono 16-bit PCM. A simple
  voice-activity threshold suppresses prolonged silence, while Android echo
  cancellation, noise suppression, and automatic gain control are enabled
  when available.
- Audio uses direct unicast to all discovered peers, with a broadcast fallback
  during initial discovery. This avoids depending on hotspot broadcast delivery
  once peers are known and supports a 5-6 device room.
- Wi-Fi and multicast locks are held while the transport is active so Android
  does not suspend the local network path.

The APK builds as `app/build/outputs/apk/debug/app-debug.apk`.

## Current validation

- REA/JADX static analysis completed against the APK digest above.
- The rebuilt debug APK compiles with Java 17 and Gradle 8.4.
- The rebuilt APK installed and launched on the connected Samsung device
  (`SM-M136B`).
- `IntercomService` was confirmed running as a foreground service with the
  microphone service type.
- Samsung hotspot topology was retained: Samsung hosts the hotspot and does
  not join another Wi-Fi network; Xiaomi connects as a client.
- The rebuilt APK was installed and launched on both connected devices:
  Samsung `RZCT806HF7D` (`SM-M136B`) and Xiaomi `c9b1b692`
  (`2406ERN9CI`).
- Both devices displayed `2/6 devices in room`, confirming peer discovery.
- The Samsung microphone control was verified through both transitions:
  `MICROPHONE ON` → `MICROPHONE OFF` → `MICROPHONE ON`.
  Incoming room audio remains active while the microphone is off.
- No `IntercomService` errors or fatal exceptions were present in the recent
  logcat samples for either device.
- A six-device physical stress test and a conclusive human speech
  intelligibility test remain unavailable with only two devices connected.

## Audio output routing

- The app now exposes an audio-output control that switches between the phone
  speaker and Bluetooth earbuds/headsets.
- On Android 12 and newer, the service selects an available Bluetooth SCO or
  BLE headset communication device with `AudioManager.setCommunicationDevice`.
- On older Android versions, it falls back to Bluetooth SCO routing and keeps
  the speaker route available as a fallback.
- `BLUETOOTH_CONNECT` is requested at runtime when the user first selects
  Bluetooth output. Earbuds must already be paired and connected in Android
  system Bluetooth settings.
- The same communication route is used by the microphone capture and remote
  voice playback, so Bluetooth earbuds can provide both listening and
  microphone input when the headset exposes a communication profile.

## Permission-safe startup

- Android 14 requires both `FOREGROUND_SERVICE_MICROPHONE` and a granted
  `RECORD_AUDIO` permission before a microphone foreground service can enter
  the foreground.
- The app now requests microphone permission before starting the service,
  instead of starting it immediately during activity creation.
- The service checks `RECORD_AUDIO` before calling `startForeground()` and
  exits cleanly if permission has been revoked. This prevents the
  `SecurityException` seen on clean installs and devices with permission
  denied.
