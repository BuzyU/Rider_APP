# RiderVoice Offline Mesh — Nearby Connections Link Design

## 1. Overview & Strategy
`NearbyMeshLink : MeshLink` provides peer-to-peer ad-hoc radio framing using Google Play Services Nearby Connections API.
- **Topology:** `Strategy.P2P_CLUSTER` (multi-to-multi mesh).
- **Service ID:** Deterministically derived from non-secret `sessionId`: `"rvmesh-" + session.sessionId.toString().take(8)`.
- **Role:** Every node advertises and discovers simultaneously on start.

## 2. Generalized MeshEndpoint
Generalize `MeshEndpoint` so transport-specific identifiers are transparent:
```kotlin
data class MeshEndpoint(
    val endpointId: String,
    val host: String = "",
    val port: Int = 0
)
```
- **UDP Link:** `endpointId = "$host:$port"`, `host = ip`, `port = 45454`.
- **Nearby Link:** `endpointId = nearbyEndpointId`, `host = ""`, `port = 0`.
`MeshLink.send(endpoint, payload)` routes using `endpoint.endpointId`.

## 3. Frame Transmission & Delivery
- **No Broadcast Medium:** Nearby Connections lacks layer-2 subnet broadcast.
- **Unicast Loops:** `ForwardingEngine.originateHello()` and `sendVoice()` iterate `link.endpoints()`, dispatching datagrams via `Payload.fromBytes(payload)` to each connected `endpointId`.
- **Payload Limits:** Voice packets (747B) and HELLO beacons fit well within Nearby Connections byte payload limits (< 32 KB).

## 4. Permission Matrix by Android API Level
- **Android 13+ (API 33+):** `android.permission.NEARBY_WIFI_DEVICES` (with `usesPermissionFlags="neverForLocation"`), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`.
- **Android 12 (API 31–32):** `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`.
- **Android 11 and lower (API <= 30):** `ACCESS_FINE_LOCATION`, `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`.

## 5. Availability Gate & Fail-Closed Policy
- Google Play Services availability is checked via `GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)`.
- If Play Services is missing, outdated, or required radio permissions are revoked, settings UI displays:
  `"OFFLINE BACKUP UNAVAILABLE ON THIS DEVICE"` and toggle is forced disabled.
- **Strict Fail-Closed (No Silent Fallback):** The system must **never** silently fall back to UDP when Nearby fails or is unavailable. Silent fallback creates a false expectation of connectivity when riders are not connected to an identical Wi-Fi access point.

## 6. Connection & Authentication Model
- Nearby Connections connection lifecycle uses `requestConnection` and `acceptConnection`.
- Because riders wear motorcycle gloves and helmets while riding, interactive manual 6-digit PIN confirmation on-screen is rejected as hazardous.
- Connections are auto-accepted at the link layer, treating the link purely as an untrusted bit pipe.
- **Wire Security:** Outer HMAC-SHA256 with session `authKey` (derived from Crockford Base32 pairing code) authenticates every frame in `ForwardingEngine` step 6. Unauthenticated packets are discarded before processing.

## 7. Battery & Latency Risks
- **Battery Drain:** High RF duty cycle (simultaneous BLE scanning + Wi-Fi Direct / Local SoftAP negotiation) drains ~14–18% battery per hour; aggressive duty-cycle sleep is necessary.
- **Latency & RF Contention:** Coexistence of 2.4 GHz Wi-Fi Direct with Bluetooth SCO helmet headsets induces acoustic jitter; frame buffers must absorb up to 60 ms transfer jitter.

## 8. Interface Changes Required
1. `MeshEndpoint`: add `endpointId: String`.
2. `MeshLink`: interface signature remains `send(MeshEndpoint, ByteArray): Boolean`.
3. `OfflineMeshVoiceTransport`: link factory dynamically supplies `NearbyMeshLink` if enabled, or `UdpMeshLink`.

## 9. Test Plan
- Unit tests with `FakeNearbyConnectionsClient` validating 6-device cluster discovery, connection acceptance, and multi-hop forwarding.
- Reconnection stress test simulating convoy drop-outs and re-merging.
- Gate verification: devices without Play Services correctly show unavailable banner and fail closed.

