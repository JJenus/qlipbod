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
| Core tests (`:sync-core:jvmTest`) | ✅ **60 / 60 green** (TDD red → green) |
| `linux-app` daemon clipboard plane | ✅ `SyncDaemon` + `ClipboardAdapter` (`xclip`), 4 / 4 green (2 `xclip` tests environment-gated) |
| `linux-app` transport wiring | ✅ fingerprint-verified handshake — mutual cert exchange resolves the peer from the trust store; unpaired devices refused before any clip flows — 3 / 3 green |
| Pairing transport (TCP, PIN) | ✅ `PairingExchange` driver + framed `PairingTransport` (initiator/responder) pair over loopback TCP and seed the trust stores; full pair → trust → sync flow green — 3 new tests |
| `android-app` shell | ⬜ documented future module, not yet materialized |
| Discovery (mDNS/Avahi), CLI/`systemd` packaging | ⬜ next slices |

## Quick start

```bash
./gradlew :sync-core:jvmTest     # run the core suite
./gradlew :linux-app:test        # run the daemon suite
./gradlew build                  # assemble + test everything
```

## Repository layout

```
clipboard-sync-plan.md      # authoritative design doc (trust, protocol, conflict, edge cases)
settings.gradle.kts         # includes :sync-core, :linux-app; future :android-app
sync-core/                  # KMP module — protocol, trust, history, pairing, engine
  src/commonMain/           # code shared by Linux and Android
  src/commonTest/           # multiplatform tests (the behavior contract)
  src/jvmMain/              # JVM-only implementations (crypto identity, JSON, TCP)
  src/jvmTest/              # JVM integration tests
linux-app/                  # JVM application — daemon wiring, clipboard, transport
  src/main/kotlin/dev/qlipbod/app/linux/
    clipboard/              # ClipboardAdapter + XClipClipboard (X11 CLIPBOARD via xclip)
    daemon/                 # SyncDaemon: engine ⇄ clipboard ⇄ connections composition root
    transport/              # PeerConnection: dial/accept over sync-core TcpMessageChannel
  src/test/kotlin/          # daemon contract + loopback stream tests + env-gated xclip round trips
```

### Module plan

- `sync-core` — all protocol, trust, history, pairing, and engine logic. Written once,
  runs identically on both platforms.
- `linux-app` — the Linux daemon: clipboard hook, daemon wiring, TCP dial/accept with the
  fingerprint-verified handshake, PIN pairing over TCP; next: mDNS/discovery and packaging
  as a systemd user service.
- `android-app` — thin platform shell (clipboard service, PIN/QR UX), designed to slot
  in alongside `sync-core` without restructuring.

## What's implemented (v1)

| Area | Files | Notes |
| --- | --- | --- |
| **Crypto** | `crypto/` | Pure-Kotlin SHA-256 (FIPS 180-4) & HMAC-SHA256 so fingerprints/MACs are byte-identical across platforms; hex codec; CSPRNG-backed `randomHex`. Tested against FIPS/RFC 4231 vectors. |
| **Identity** | `identity/` | `DeviceId`, `LocalIdentity`; JVM `GeneratedIdentity` mints RSA-2048 self-signed certs via BouncyCastle, fingerprint = SHA-256 over the DER. |
| **Trust** | `trust/` | `TrustStore` keyed by fingerprint; add/remove/replace; persisted via `TrustStorage` (`JsonTrustStorage` on JVM). |
| **History** | `history/` | Bounded FIFO `ClipHistory`, capacity **50**, deduped by `(origin, sequence)`, conflict *loser* retained, persisted via `HistoryStorage`. |
| **Pairing** | `pairing/` | One-time PIN exchange HELLO → ACCEPT → CONFIRM; every message MAC'd over all fields with the PIN (MITM key injection fails the exchange); replay & tamper attacks tested. JVM `PairingTransport` runs the exchange over the same length-prefixed framing as the data channel (short-lived connection, initiator/responder + `PairingFailedException`). |
| **Protocol** | `protocol/` | `SyncEvent`, `FrameCodec` (4-byte big-endian length prefix, 1 MB cap, strict framing, raw-byte framing for handshakes), plus the mutual certificate handshake: `HandshakeHello`, fingerprint resolution against the trust store, typed refusal of unpaired/malformed peers. A shared JVM `readFrameBody` (with `StreamFrameException`) serves both the handshake and the pairing exchange so one framing definition lives everywhere. |
| **Engine** | `engine/` | `SyncEngine`: monotonic per-device sequences (never wall-clock); sensitive clips never broadcast/stored; loop prevention via origin tagging + `fromNetwork` skip; LWW conflict resolution; untrusted peers rejected at the message layer. |
| **Transport** | `transport/` | `MessageChannel` interface; JVM `TcpMessageChannel` with length-prefixed frames over a socket (incl. `attach` for post-handshake sockets); JVM `readHelloFrame` glue for the handshake. |

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

## Test surface (60 core + 10 daemon)

- `CryptoTest` — FIPS 180-4 + RFC 4231 known vectors, hex round-trips and rejection.
- `ClipHistoryTest` — FIFO bound, dedup, newest-first ordering, storage round trip.
- `TrustStoreTest` — trust lifecycle + malformed-fingerprint rejection.
- `PairingTest` — happy path, forged MAC, replayed HELLO, finished-session refusal.
- `PairingExchangeTest` (JVM) — the `PairingSession.drive` conversation over in-memory
  queues: matching PINs confirm both sides with the counterpart's real identity, the
  confirmed fingerprint drops straight into the handshake resolver (and a stranger's does
  not), and a mismatched PIN fails both sides.
- `PairingTransportTest` (JVM) — real loopback TCP pairing: matching PIN returns the
  confirmed peer on both sides; mismatch throws `PairingFailedException` on both sides.
- `FrameCodecTest` — framing edge cases (zero/negative/huge lengths, truncation, partial
  accumulation, raw-byte framing, oversized rejection).
- `HandshakeTest` — cert round trip through a Hello frame, fingerprint resolution to the
  exact trusted peer, unpaired-key and malformed-hello refusal.
- `EngineTest` — monotonic sequences, sensitive clips, no-rebroadcast, echo/stale/untrusted
  handling, deterministic conflict convergence, bidirectional no-loop sync.
- `GeneratedIdentityTest` (JVM) — BouncyCastle cert generation, fingerprint derivation.
- `JsonStoresTest` (JVM) — persistence round trips on disk.
- `TcpMessageChannelTest` (JVM) — real sockets, frame integrity end to end, `attach` on a
  pre-connected socket.

### Linux daemon (10 tests in `:linux-app:test`, 2 environment-gated)

- `SyncDaemonTest` — poll surfaces a user copy exactly once; boot clipboard is never
  re-synced; network-applied clips are written to the clipboard and their OS echo is
  suppressed (no rebroadcast); clipboard read failures degrade to "no change".
- `SyncDaemonStreamTest` — real loopback TCP with the mutual certificate handshake:
  copies sync both ways with exactly one broadcast per device (no loops); an unpaired
  device is refused *at the handshake* before any clip can apply; a peer that closes
  mid-handshake surfaces a typed handshake error, not a crash.
- `SyncDaemonPairFlowTest` — the product promise end to end: pair two devices over TCP
  with a shared PIN, persist each side's confirmed fingerprint into its trust store, then
  the ordinary verified data handshake connects them and clips flow both ways with no
  loops.
- `XClipClipboardTest` — real X11 CLIPBOARD round trips via `xclip`; skipped when xclip
  or an X server is unavailable (headless/environment-gated).

## Next steps

1. **Discovery (mDNS/DNS-SD) — the last plan-phase-1 piece** — advertise and browse
   `_clipsync._tcp` (Avahi/mDNS) so paired devices find each other without typing IPs,
   with a manual "add by IP" fallback (pairing and manual connect already work end to
   end: pair once over TCP, trust the confirmed fingerprint, dial the address, and the
   handshake resolves the peer — plan §9).
2. **Encrypted data transport (plan §6)** — the handshake now *verifies* identity on the
   wire, but clip bytes are still plaintext. Next: wrap the verified channel in TLS
   (mTLS per plan §6) so only the two paired devices can read what flows.
3. **CLI + packaging** — `linux-app` entry point, systemd `--user` service (plan §8).
4. Materialize `android-app` (Android target in `sync-core`, clipboard service, PIN/QR screen).