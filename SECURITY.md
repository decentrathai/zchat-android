# Security Policy

ZChat is a privacy tool. If it fails, the consequence falls on the person using it,
so we would rather hear about a problem early and awkwardly than late and politely.

## Reporting a vulnerability

Email **btcpresent@gmail.com** with `SECURITY` in the subject line.

Please include enough detail to reproduce: affected version (Settings shows the
version and build), the transport mode involved (Shielded, Tunnel, or Open), and the
steps you took. A proof of concept helps but is not required to report.

We aim to acknowledge within 72 hours.

**Do not open a public issue for a vulnerability.** Use email first.

## Disclosure

We follow coordinated disclosure. We ask for 90 days before public disclosure, and
we will credit you in the release notes and in any published advisory unless you
prefer otherwise. If a fix is going to take longer than 90 days we will say so and
agree a date with you rather than let the clock run out quietly.

## Scope

In scope: the Android client in this repository, the ZMSG protocol, the encryption
and ratchet layers, the NOSTR transport, group key handling, the calling stack, and
the backend and relay we operate (`api.zsend.xyz`, `relay.zsend.xyz`).

Out of scope: the Zcash protocol itself and the Zcash Android SDK (report those to
Electric Coin Company), third-party NOSTR relays we do not run, and issues that
require a physically compromised or already-rooted device.

## Known open items

We publish what we know is wrong rather than wait until it is fixed:

- First contact is trust-on-first-use. Safety numbers and key-change alerts are
  implemented; verify your contacts out of band.
- The ratchet retains a persistent root, so it does not provide per-message forward
  secrecy. A full double ratchet is designed and queued.
- Tor support is off by default and its coverage across our network paths is
  incomplete.
- Relay, TURN, STUN, and media endpoints are hardcoded. Only the lightwalletd
  server is user-configurable today.

## Verifying what you install

Every release is signed with the same key. Check it before installing:

```
apksigner verify -v --print-certs ZChat.apk
```

Certificate SHA-256, permanent:

```
F1:7A:F1:28:23:CA:20:8B:63:2E:29:81:38:B7:89:13:74:F6:65:17:C8:9D:BF:BE:12:FC:3A:C3:65:01:C8:06
```
