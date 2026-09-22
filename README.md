# Qlipbod — LAN Clipboard Sync

Private clipboard sharing between a Linux desktop and an Android phone over the local
network. Pair once with a PIN, then trust is pinned by device fingerprint — never by IP
or MAC — and text clips flow end to end. Designed in [`clipboard-sync-plan.md`](clipboard-sync-plan.md);
this repo is the implementation of **v1 (text-only sync)**.

## Status

| Phase | State |
| --- | --- |
| Toolchain & scaffold | ✅ Gradle 9.7.1 wrapper + Kotlin Multiplatform build |
| `sync-core` commonMain (JVM target) | ✅ implemented |
| `sync-core` jvmMain (identity, JSON stores, TCP transport) | ✅ implemented |
| Tests (`:sync-core:jvmTest`) | ✅ **49 / 49 green** (TDD red → green) |
| `android-app` / `linux-app` shells | ⬜ documented future modules, not yet materialized |

## Quick start

```bash
./gradlew :sync-core:jvmTest     # run the whole suite
./gradlew build                  # assemble + test
```

## Repository layout

```
clipboard-sync-plan.md      # authoritative design doc (trust, protocol, conflict, edge cases)
settings.gradle.kts         # includes :sync-core; future :android-app, :linux-app
sync-core/
  src/commonMain/           # KMP code shared by Linux and Android
  src/commonTest/           # multiplatform tests (the behavior contract)
  src/jvmMain/              # JVM-only implementations (crypto identity, JSON, TCP)
  src/jvmTest/              # JVM integration tests
```

### Module plan

- `sync-core` — all protocol, trust, history, pairing, and engine logic. Written once,
  runs identically on both platforms.
- `android-app` / `linux-app` — thin platform shells (clipboard hooks, QR/PIN UX, daemon),
  designed to slot in alongside `sync-core` without restructuring.

## What's implemented (v1)

| Area | Files | Notes |
| --- | --- | --- |
| **Crypto** | `crypto/` | Pure-Kotlin SHA-256 (FIPS 180-4) & HMAC-SHA256 so fingerprints/MACs are byte-identical across platforms; hex codec; CSPRNG-backed `randomHex`. Tested against FIPS/RFC 4231 vectors. |
| **Identity** | `identity/` | `DeviceId`, `LocalIdentity`; JVM `GeneratedIdentity` mints RSA-2048 self-signed certs via BouncyCastle, fingerprint = SHA-256 over the DER. |
| **Trust** | `trust/` | `TrustStore` keyed by fingerprint; add/remove/replace; persisted via `TrustStorage` (`JsonTrustStorage` on JVM). |
| **History** | `history/` | Bounded FIFO `ClipHistory`, capacity **50**, deduped by `(origin, sequence)`, conflict *loser* retained, persisted via `HistoryStorage`. |
| **Pairing** | `pairing/` | One-time PIN exchange HELLO → ACCEPT → CONFIRM; every message MAC'd over all fields with the PIN (MITM key injection fails the exchange); replay & tamper attacks tested. |
| **Protocol** | `protocol/` | `SyncEvent`, `FrameCodec` (4-byte big-endian length prefix, 1 MB cap, strict framing). |
| **Engine** | `engine/` | `SyncEngine`: monotonic per-device sequences (never wall-clock); sensitive clips never broadcast/stored; loop prevention via origin tagging + `fromNetwork` skip; LWW conflict resolution; untrusted peers rejected at the message layer. |
| **Transport** | `transport/` | `MessageChannel` interface; JVM `TcpMessageChannel` with length-prefixed frames over a socket. |

## Design decisions (from the plan)

- **Same language, both platforms** — Kotlin + Kotlin Multiplatform; `commonMain`/`jvmMain`
  layout means the Android target slots in later without restructuring.
- **Backend of trust is the fingerprint** — a peer is trusted because you paired with its
  key once and pinned the fingerprint. Network identity (IP/MAC) is never used as a trust
  signal, so roaming/spoofing IPs don't matter.
- **Pair-once with a PIN** — the PIN only proves both humans want this pairing; the
  fingerprint captured during the exchange is what persists. MAC over every field stops a
  third party injecting its own key mid-exchange.
- **Clips that fail to deliver are dropped** (no offline queue in v1) — a disconnected
  peer simply doesn't get that copy.
- **Sensitive clips are excluded from sync** by explicit callers (`sensitive = true`).
- **Conflict resolution is deterministic LWW** by total order key `(origin, sequence)`; the
  losing clip is still recorded so nothing is silently lost.

## Test surface (49 tests)

- `CryptoTest` — FIPS 180-4 + RFC 4231 known vectors, hex round-trips and rejection.
- `ClipHistoryTest` — FIFO bound, dedup, newest-first ordering, storage round trip.
- `TrustStoreTest` — trust lifecycle + malformed-fingerprint rejection.
- `PairingTest` — happy path, forged MAC, replayed HELLO, finished-session refusal.
- `FrameCodecTest` — framing edge cases (zero/negative/huge lengths, truncation, partial
  accumulation).
- `EngineTest` — monotonic sequences, sensitive clips, no-rebroadcast, echo/stale/untrusted
  handling, deterministic conflict convergence, bidirectional no-loop sync.
- `GeneratedIdentityTest` (JVM) — BouncyCastle cert generation, fingerprint derivation.
- `JsonStoresTest` (JVM) — persistence round trips on disk.
- `TcpMessageChannelTest` (JVM) — real sockets, frame integrity end to end.

## Next steps

1. Materialize `linux-app` (clipboard hook via `xclip`/Wayland, pairing UI, `TcpMessageChannel` server).
2. Materialize `android-app` (Android target in `sync-core`, clipboard service, PIN/QR screen).
3. Auto-discovery of peers on the LAN (plan §3) — out of scope for the core tests here.