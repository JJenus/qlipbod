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
| Core tests (`:sync-core:jvmTest`) | ✅ **76 / 76 green** (TDD red → green) |
| `linux-app` daemon clipboard plane | ✅ `SyncDaemon` + `ClipboardAdapter` (`xclip`), 4 / 4 green (2 `xclip` tests environment-gated) |
| `linux-app` transport wiring | ✅ fingerprint-verified handshake — mutual cert exchange resolves the peer from the trust store; unpaired devices refused before any clip flows — 3 / 3 green |
| Pairing transport (TCP, PIN) | ✅ `PairingExchange` driver + framed `PairingTransport` (initiator/responder) pair over loopback TCP and seed the trust stores; full pair → trust → sync flow green — 3 new tests |
| Discovery (mDNS/DNS-SD `_clipsync._tcp`) | ✅ `ClipboardService` + `DiscoveryService` contract, JmDNS adapter (advertise/browse, TXT label + fingerprint), `classifyDiscovery` badge logic, and plan §5 `AutoConnect` — 5 classifier tests + gated mDNS round trip + 3 flow tests |
| CLI + `systemd --user` packaging | ✅ `qlipbod start|pair|help` (plan §8) through `DaemonRuntime` with `--connect` manual add-by-IP (§9); persistent identity/trust/history in the data dir; systemd `--user` unit + NetworkManager restart hook docs — 24 new tests |
| `android-app` shell | ⬜ documented future module, not yet materialized |

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
linux-app/                  # JVM application — daemon wiring, clipboard, transport, CLI
  src/main/kotlin/dev/qlipbod/app/linux/
    clipboard/              # ClipboardAdapter + XClipClipboard (X11 CLIPBOARD via xclip)
    cli/                    # qlipbod CLI: parser, QlipbodCli handlers, QlipbodState, Main
    daemon/                 # SyncDaemon + DaemonRuntime: engine ⇄ clipboard ⇄ connections
    discovery/              # AutoConnect: plan §5 auto-connect over the verified handshake
    transport/              # PeerConnection: dial/accept over sync-core TcpMessageChannel
  src/test/kotlin/          # daemon contract + loopback stream tests + env-gated xclip round trips
  packaging/                # systemd --user unit + install/network-change README
```

### Module plan

- `sync-core` — all protocol, trust, history, pairing, and engine logic. Written once,
  runs identically on both platforms.
- `linux-app` — the Linux daemon: clipboard hook, daemon wiring, TCP dial/accept with the
  fingerprint-verified handshake, PIN pairing over TCP, and mDNS discovery with plan §5
  auto-connect; the `qlipbod` CLI (`start`/`pair`/`help`) is packaged as a `systemd --user`
  service with a NetworkManager dispatcher hook for network changes.
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

## Test surface (76 core + 37 daemon)

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
- `FileIdentityStorageTest` (JVM) — the persistent identity: a generated identity round-trips
  through the data dir byte-identically, an existing one is reloaded unchanged, and the
  directory is created on first use — the fingerprint-survives-restart guarantee.
- `SystemMonotonicClockTest` (JVM) — the production clock is strictly monotonic across a
  sequence (150 → 156 → 164…) and never regresses.
- `QlipbodDefaultsTest` — the shared wire defaults (sync 4343 / pairing 4344) are in sync
  with the service type and used by both the CLI and the future Android app.
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

### Linux daemon (37 tests in `:linux-app:test`, 2 environment-gated)

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
- `DaemonRuntimeTest` — the long-running composition: a manual dial through the poll +
  accept loops syncs both directions; a discovered paired device is auto-dialed; the
  `--connect` manual add-by-IP dial connects at startup; `close()` is graceful,
  idempotent, and leaves nothing accepting.
- `CliParserTest` — the pure argument grammar: defaults match the wire ports, every
  option parses, `--connect HOST` defaults to the sync port, and bad ports/values/options
  and flags missing values become `Error`s.
- `QlipbodCliTest` — the command surface end to end: a matching PIN pins the confirmed
  fingerprint in the on-disk trust store and reports the peer; a mismatched PIN fails
  and leaves the store untouched; identity and trust persist across boots; and the full
  product journey — `pair` via the CLI, then `start` via the same data dir, then a
  discovered device auto-connects and clips flow.
- `XClipClipboardTest` — real X11 CLIPBOARD round trips via `xclip`; skipped when xclip
  or an X server is unavailable (headless/environment-gated).

## Next steps

1. **Materialize `android-app`** — add the Android target to `sync-core`, bind
   `NsdManager` discovery (same `_clipsync._tcp` service type and
   `DiscoveryService` contract), a clipboard service, and the PIN pairing screen.
   The CLI pairing flow already proves the wire protocol the app will speak.
2. **Encrypted data transport (plan §6)** — the handshake now *verifies* identity on the
   wire, but clip bytes are still plaintext. mTLS over the verified channel is deferred
   for v1 by choice; revisit with the verified session as the key-exchange backbone.
3. Re-base the engine's `SystemMonotonicClock` across restarts from persisted history, so
   sequences stay strictly increasing even across reboots of one device.