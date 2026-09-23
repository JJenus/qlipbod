# Packaging qlipbod for Linux (plan §8)

The `linux-app` module is a JVM application (Gradle `application` plugin). The CLI:

```
qlipbod start [--port N] [--pairing-port N] [--poll-ms N] [--data-dir DIR] [--connect HOST[:PORT]]
qlipbod pair <HOST> [--pin PIN] [--pairing-port N] [--data-dir DIR]
qlipbod help
```

## Build a runnable distribution

```bash
./gradlew :linux-app:installDist
# → build/install/linux-app/bin/linux-app
```

Install it where the systemd unit expects it:

```bash
sudo mkdir -p /opt/qlipbod
sudo cp -r build/install/linux-app /opt/qlipbod/
```

## systemd --user service

The daemon should run per-user (it reads your X11 clipboard — never run it as root).

```bash
mkdir -p ~/.config/systemd/user
cp linux-app/packaging/qlipbod.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now qlipbod
systemctl --user status qlipbod
```

If you installed the distribution somewhere other than `/opt/qlipbod`, fix `ExecStart`
in `~/.config/systemd/user/qlipbod.service`.

### Restarting discovery on network changes

Discovery is presence-only and re-binds everything on a fresh start, so the simplest
correct behavior for a network change is to restart the service. With
NetworkManager, drop a dispatcher script at `/etc/NetworkManager/dispatcher.d/qlipbod`:

```bash
#!/bin/sh
if [ "$2" = "up" ] || [ "$2" = "down" ]; then
    systemctl --user restart qlipbod 2>/dev/null || true
fi
```

(make it executable and owned by root). Without NetworkManager, a
`systemctl --user restart qlipbod` at login or after switching networks does the same.

## Manual add-by-IP fallback (plan §9)

If mDNS is blocked (client/AP isolation on a hotspot or guest network), discovery will
show nothing even though TCP works. Pair and connect by explicit address instead:

```bash
qlipbod pair 192.168.1.20 --pin 2468
qlipbod start --connect 192.168.1.20
```

State (identity, trust store, history) lives in `~/.local/share/qlipbod` by default;
the identity — and therefore your fingerprint and pairings — survives restarts.

## What the os told us about mDNS

`qlipbod` uses pure-multicast DNS-SD (JmDNS), not Avahi. No system daemon is required;
only multicast must be enabled on the interface (it usually is on home Wi-Fi). If the
daemon starts on a host with no multicast it prints a warning and continues without
discovery (poll + accept + `--connect` still work).