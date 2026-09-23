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
| Core tests (`:sync-core:jvmTest`) | ✅ **66 / 66 green** (TDD red → green) |
| `linux-app` daemon clipboard plane | ✅ `SyncDaemon` + `ClipboardAdapter` (`xclip`), 4 / 4 green (2 `xclip` tests environment-gated) |
| `linux-app` transport wiring | ✅ fingerprint-verified handshake — mutual cert exchange resolves the peer from the trust store; unpaired devices refused before any clip flows — 3 / 3 green |
| Pairing transport (TCP, PIN) | ✅ `PairingExchange` driver + framed `PairingTransport` (initiator/responder) pair over loopback TCP and seed the trust stores; full pair → trust → sync flow green — 3 new tests |
| Discovery (mDNS/DNS-SD `_clipsync._tcp`) | ✅ `ClipboardService` + `DiscoveryService` contract, JmDNS adapter (advertise/browse, TXT label + fingerprint), `classifyDiscovery` badge logic, and plan §5 `AutoConnect` — 5 classifier tests + gated mDNS round trip + 3 flow tests |
| `android-app` shell | ⬜ documented future module, not yet materialized |
| CLI + `systemd --user` packaging | ⬜ next slice (`linux-app` entry point, manual "add by IP" flag per plan §9) |

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
sync-core/                  # KMP module — protocol, trust, history, pairing, discovery, engine
  src/commonMain/           # code shared by Linux and Android
  src/commonTest/           # multiplatform tests (the behavior contract)
  src/jvmMain/              # JVM-only implementations (crypto identity, JSON, TCP, JmDNS mDNS)
  src/jvmTest/              # JVM integration tests
linux-app/                  # JVM application — daemon wiring, clipboard, transport
  src/main/kotlin/dev/qlipbod/app/linux/
    clipboard/              # ClipboardAdapter + XClipClipboard (X11 CLIPBOARD via xclip)
    daemon/                 # SyncDaemon: engine ⇄ clipboard ⇄ connections composition root
    discovery/              # AutoConnect: plan §5 auto-connect over the verified handshake
    transport/              # PeerConnection: dial/accept over sync-core TcpMessageChannel
  src/test/kotlin/          # daemon contract + loopback stream tests + env-gated xclip round trips
```

### Module plan

- `sync-core` — all protocol, trust, history, pairing, and engine logic. Written once,
  runs identically on both platforms.
- `linux-app` — the Linux daemon: clipboard hook, daemon wiring, TCP dial/accept with the
  fingerprint-verified handshake, PIN pairing over TCP, and mDNS discovery with plan §5
  auto-connect; next: packaging as a systemd user service.
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
| **Discovery** | `discovery/` | `ClipboardService` (service type `_clipsync._tcp`, TXT keys) + the `DiscoveryService` contract (advertise/browse/listen, restartable on network change); JVM `JmDnsDiscovery` advertises label/version/fingerprint in TXT and browses + dedupes appearances. `classifyDiscovery` badges a found device **paired** only when its advertised fingerprint resolves in the trust store — presence alone is never trust (§3-§5). `AutoConnect` (linux-app) then dials a paired device only through the fingerprint-verified handshake. |
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

## Test surface (66 core + 13 daemon)

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
- `DiscoveryClassifierTest` — plan §4/§5 badge logic: a device whose advertised
  fingerprint is in the trust store is **paired**; unknown, missing, or malformed
  fingerprints are **unpaired** (a hint only — the handshake stays the verdict).
- `JmDnsDiscoveryTest` (JVM, multicast-gated) — a real mDNS round trip: one discovery
  advertises `_clipsync._tcp`, another browses and resolves label, fingerprint, port, and
  protocol version from the TXT records; skips on hosts without multicast (like the
  `xclip` gate).

### Linux daemon (13 tests in `:linux-app:test`, 2 environment-gated)

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
- `DiscoverConnectFlowTest` — plan §5 wiring through a controllable discovery: a
  discovered **paired** device is dialed and the verified handshake syncs clips; an
  **unpaired** device is never dialed; a *lying advertisement* (a trusted fingerprint
  served by an untrusted certificate) is dialed once but refused by the handshake, then
  re-classified unpaired — no clip ever flows.
- `XClipClipboardTest` — real X11 CLIPBOARD round trips via `xclip`; skipped when xclip
  or an X server is unavailable (headless/environment-gated).

## Next steps

1. **CLI + packaging (plan §8, §9)** — a `linux-app` entry point (daemon `start`, `--pair <host> <pin>`, and the manual "add by IP" `--connect <host>` fallback so discovery is never a prerequisite), then a `systemd --user` service so the daemon survives reboots and restarts discovery on network changes.
2. **Encrypted data transport (plan §6)** — the handshake now *verifies* identity on the
   wire, but clip bytes are still plaintext. mTLS over the verified channel is deferred
   for v1 by choice; revisit with the verified session as the key-exchange backbone.
3. Materialize `android-app` (Android target in `sync-core`, clipboard service, PIN/QR screen).