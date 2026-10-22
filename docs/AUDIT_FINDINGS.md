# RiderVoice Audit Findings Tracker

Branch: `main` (consolidated on top of `origin/main` at `65e718c`). Status is updated as work lands.
Legend: FIXED = verified by automated test/build; PARTIAL = part done; TODO = not started.
RUNTIME items cannot be verified without a device and are listed in the final report.

## Verification baseline & CI status
- Backend: `npm test` in `backend/` -> 56 passing across 10 test suites (baseline was 37).
- Android: `gradlew :app:testDebugUnitTest` and `:app:assembleDebug` pass.
- Web app: `npm run build` passes with Next.js webpack production build.
- GitHub Actions workflow (`.github/workflows/build.yml`): All 3 jobs verified locally.

## Phase 0 / Phase 1 (backend room identity, lifecycle, membership)
| Area | Status | Notes |
|---|---|---|
| Version header + legacy-client gate (426) | FIXED | `authMiddleware.js`, `AppModule.kt` sends `X-App-Version` |
| Room lifecycle (`RoomStatus`, `endedAt`, ENDED -> 410) | FIXED | Prisma migration `20261023_phase1_room_lifecycle_membership`, drift test green |
| Single resolver / token service / membership service | FIXED | `roomResolver.js`, `tokenService.js`, `membershipService.js` |
| REMOVED rider blocked; token has no `roomCreate` | FIXED | `room_lifecycle.test.js` |
| Join codes, share-link maxUses/revoke | PARTIAL | Implemented in routes; dedicated tests still TODO |
| Invite uniqueness (`roomId, inviteeId`) + de-dup | FIXED | In migration |
| `clientRideId` idempotency (rides) | FIXED | Unique index in migration |

## Phase 2 (client session state)
| Finding | Status | Notes |
|---|---|---|
| A5 JWT expiry/room validation | FIXED | `JwtRoomClaimDecoder.validate`, 5 new unit tests; `RoomViewModel.joinRoom` no longer reuses stale cached tokens |
| H3 "404 = ended" polling | PARTIAL | `LobbyViewModel` maps 410/403/404; `RoomViewModel` 10s poll and UI consumption of `terminal` TODO |
| `ActiveRideRepository` replacing `RideSession` | TODO | 11 files reference `RideSession` |
| A7 `LiveKitManager.connect` semantics | TODO | |
| Navigation / deep links (C2-C9, H7) | TODO | |

## Phases 3-12
TODO: audio routing, VOX, battery, diagnostics client, push/SOS, backend hardening,
CI/security, cleanup/docs, verification scripts.

## NOT REPRODUCED
None recorded yet.
