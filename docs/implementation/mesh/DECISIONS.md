# RiderVoice Offline Mesh — Engineering Decisions Log

Format: `WP-NN | situation | decision | reason`

| Work Package | Situation | Decision | Reason |
|---|---|---|---|
| WP-25 | Baseline compilation error `OfflineRoomScreen.kt:151: Unresolved reference: setPttHeld` | Replaced `voxBridge?.setPttHeld(held)` with existing method `voxBridge?.setPttPressed(held)` | `MeshVoxBridge` already defines `setPttPressed(Boolean)`; avoiding duplicate aliases per prompt instruction. |
| Owner Q1 | Offline secret for online rides | Fail-closed: bind `NullOfflineSecretProvider` returning `null`. Delete `fromOnlineRoom` and `BOOTSTRAP_KEY`. No secret derived from online ride metadata. UI displays "Offline fallback unavailable: no shared secret". | Mandated owner decision Q1. Prevents broken room-name secret derivation without breaking backend freeze. |
| Owner Q2 | Single port vs dual-port design | Standardize on single UDP port 45454. Remove `DISCOVERY_PORT` alias. Append amendment to plan file. | Mandated owner decision Q2. Prevents artificial port separation in single-socket link architecture. |
| Owner Q3 | HELLO packet boundedGraphUpdates & transportMetadata | Do not add unimplemented fields to HELLO codec. Document as "Reserved - not implemented". | Mandated owner decision Q3. Preserves wire protocol stability. |
| Owner Q4 | Activity configChanges attribute | Do not add `android:configChanges` to `MainActivity`. Handle screen rotation via `OfflineSessionHolder` singleton and argument-free route. | Mandated owner decision Q4. Prevents altering activity lifecycle for the rest of the application. |
| Owner Q5 | Wire nonce formatting & legacy fallback | Retain current wire format and legacy-nonce fallback. Document 24-bit source ID prefix truncation and single-key forgeability in threat model. | Mandated owner decision Q5. Prevents wire-breaking protocol modifications. |
| Owner Q6 | HTTP connectivity probe target | Keep `Constants.BACKEND_URL + "/health"`; minimum 5s throttle; probe on `Dispatchers.IO` only when connectivity is degraded. | Mandated owner decision Q6. Uses existing frozen backend health endpoint. |
| Owner Q7 | Mic combination semantics | Capture runs while `audioEnabled && (micToggleOn \|\| pttHeld \|\| (voxEnabled && armed))`. PTT release never closes a toggle- or VOX-opened mic. Helmet key = toggle event. | Mandated owner decision Q7. Accurately reflects motorcycle helmet and hands-free rider ergonomics. |
| Owner Q8 | AudioRecord warm capture vs delay | Keep capture warm only while `micToggleOn \|\| voxEnabled`. Accept first-syllable delay for PTT-only use. | Mandated owner decision Q8. Minimizes battery drain and eliminates continuous Android microphone privacy indicator. |
| Owner Q9 | Stage 4 continuity client stubs | Retain client stubs in repository. Label as "NOT IMPLEMENTED - client stubs only, no backend, unreferenced in production". | Mandated owner decision Q9. Retains interface stubs for future backend work without claiming functionality. |
| Owner Q10 | Frozen files & permissions invariant | Exact 0 diff against commit a37ef09. Zero backend changes. Zero Android permissions added. | Mandated owner decision Q10 & Rule R1. Preserves operational stability of production online ride infrastructure. |
| WP-28 | LiveKit quiet guard and enableAudio return type | 400ms guard window before claiming OFFLINE_MESH owner; enableAudio returns EnableAudioResult sealed interface | Avoids throwing exceptions across coordinator boundaries; ensures LiveKit mic is completely quiet before mesh starts AudioRecord |


