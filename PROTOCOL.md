# 声音接力 SoundRelay — Wire Protocol v1 (multi-PC + pairing)

> Product renamed 声桥 AirBridge → 声音接力 SoundRelay (v2.2). Wire-level
> identifiers deliberately RETAIN the legacy strings for compatibility with
> installed v2.1 endpoints: the `app` field value `"AirBridge"`, the BLE seed
> `"AirBridge-BLE-Service-v2"`, the mDNS type `_airbridge._udp`, and the
> canonical update URL. Do not "fix" them to the new brand.

Single UDP channel, port **47731**, used for presence beacons, discovery,
control handshake, keepalive and audio. The identical protocol runs over
WiFi LAN, USB tethering and `adb forward udp:47731 udp:47731` — no separate
code paths. One phone keeps **one session per PC**; there is no limit in the
protocol on how many PCs are connected at once.

## Packet header (16 bytes, big-endian)

| Offset | Size | Field |
|---|---|---|
| 0 | 2 | magic `0x5041` ("PA") |
| 2 | 1 | packet type (see below) |
| 3 | 1 | flags: bits 0-1 codec, bits 2-4 sample-rate code, bits 5-7 reserved |
| 4 | 4 | sequence number (per direction **per session**) |
| 8 | 8 | timestamp, microseconds (ping/pong echo it) |

Codec codes: `0 = PCM16`, `1 = Opus (reserved for v2, not used in v1)`.
Sample-rate codes: `0 = 48000`, `1 = 24000`, `2 = 16000`.

## Packet types

| Type | Name | Payload |
|---|---|---|
| 0 | AUDIO | PCM16 LE mono, 10 ms frame: 960 B @48k, 480 B @24k, 320 B @16k |
| 1 | DISCOVER | JSON `{app, kind:"discover", name, deviceId}` broadcast (phone → all) |
| 2 | DISCOVER_RESP | JSON `{app, kind, name, os, deviceId, tokenHash, appVersion, protoVersion}` |
| 3 | HELLO | JSON `{name, deviceId, rate, codec, appVersion, protoVersion, tokenHash?, pairCode?, pairQr?, pairTap?}` |
| 4 | HELLO_ACK | JSON `{deviceId, name, os, rate, codec, appVersion, protoVersion, token?, verifyCode?}` |
| 5 | PING | empty; header timestamp echoed back |
| 6 | PONG | empty; header timestamp = the PING's |
| 7 | RATE_REQ | flags carry the proposed new rate code |
| 8 | RATE_ACK | flags carry the accepted rate code |
| 9 | BYE | empty, courtesy close |
| 11 | BEACON | JSON `{app, kind:"beacon", name, os, deviceId, tokenHash, appVersion, protoVersion}` — PC broadcasts every 1 s |

## Identity & pairing

- Every endpoint has a persistent `deviceId` and every PC holds a secret
  `token`. `tokenHash = SHA-256(token)` (hex) is the only token-derived value
  ever put on the wire in beacons; the full token travels exactly once, in a
  HELLO_ACK over the LAN, at pairing time.
- HELLO authentication on the PC, first match wins:
  1. `deviceId` already paired **and** `tokenHash` matches → authenticated.
  2. `pairCode` equals the PC's current 6-digit pairing code (rotated every
     10 min) → **pair**: PC registers the phone, ACK carries `token` +
     `verifyCode`.
  3. `tokenHash` matches (QR flow — the phone scanned the PC's QR code and
     already holds the token) → **pair**, as above.
  4. Trust-on-first-use: PC has **no** paired phones and is within 120 s of
     startup → **pair**, as above.
  5. Otherwise the HELLO is ignored (no ACK).
- `verifyCode` = 6 digits derived from `SHA-256(token + ":" + phoneDeviceId)`;
  the PC displays it, the phone shows it on the verification-confirm screen,
  and the user only confirms when both screens agree. The phone stores the
  pairing in its registry **only after** that confirm.
- QR code content (PC → phone, out of band):
  `{"app":"AirBridge","v":1,"deviceId","name","os","ip","port","token"}`.
- Pairing-code lookup transport (v2.1, fixes C3): when the user enters a
  pairing code, the phone sends its DISCOVER probe not only to the LAN
  broadcast address but also by **unicast fan-out** to every IP it already
  knows — connected sessions, the presence table, and every paired PC's
  last-seen IP. A dropped broadcast (common on AP-isolated or busy Wi-Fi)
  no longer makes the code lookup silently find nothing.

## Presence & auto-activation

- The PC broadcasts BEACON every 1 s. The phone's foreground service listens
  continuously: a beacon from a **paired** PC whose per-device auto-connect
  switch is ON triggers HELLO + streaming with no user action (1–3 s).
- A PC is "online" when its beacon was heard within 3 s. Link death is
  declared after 5 s without packets; reconnect is automatic. Auto-connect
  failures use a fuse: 3 failed 10-second episodes pause auto-retry until
  the PC has been absent >10 s or the user taps the card.
- Unpaired PCs appear as "new devices"; pairing is §above.

## Audio behaviour

- **Fan-out:** each captured mic frame is sent to every connected session
  whose per-device mic-send switch (and the global mic switch) is on, with
  per-session sequence numbers.
- **Mixing:** for each 10 ms tick the phone pops one frame from every
  connected session whose speaker-receive switch is on, multiplies by that
  device's volume (mute = 0), sums, and hard-clamps to int16
  (clipping protection), then plays the mix.
- **Rate adaptation is global:** the shared capture pipeline runs at one
  rate; the worst connected link governs. Loss > 5 % or jitter > 15 ms on
  any session steps 48k → 24k → 16k (RATE_REQ to all sessions); ~30 s of
  clean audio on all sessions steps back up.
- **Jitter buffer per session:** target depth adapts 20–60 ms from that
  session's measured jitter (`clamp(2.5 × jitter, 20, 60)`); missing frames
  are concealed with silence and counted as loss.
- Keepalive: PING/PONG every 1 s per session; RTT is shown per device and
  the worst value drives the overview badge.

## Designed next layer: BLE presence (not in v1)

BLE is specified here as the *next* sensing layer, deliberately **not**
implemented in v1: PC-side BLE advertising is not portable across
Windows/Linux stacks, and phone-side background BLE wake is heavily
restricted. The designed flow: PC advertises the product BLE service UUID with
its deviceId + token-hash prefix; the phone, on BLE sighting of a paired
deviceId, wakes the LAN presence listener and fast-tracks HELLO once the PC
is also heard on WiFi/USB. v1's sensing stack is LAN beacon + pairing
registry + history-IP reconnect + QR/code pairing, which covers the
same-room scenarios BLE would, at zero extra hardware cost.

## Online update (v2 ADDITION — spec change flagged in work-v2/android-report.md)

- Canonical manifest address (default, editable in the app's Settings and
  persisted; the user may later move it to their own server):
  `https://raw.githubusercontent.com/Qiguiqiang/soundrelay/main/update.json`
- Manifest shape:
  `{"android": {"version": "2.1.0", "url": "<apk url>", "sha256": "<hex sha256 of the apk>", "notes": "更新说明"}}`
  (a matching `windows` object is used by the PC apps; the Android app
  reads only `android`.)
- Unified versioning: one release carries ONE version on both ends
  (this release: `2.0.0`, `protoVersion: 2`). HELLO carries
  `appVersion` + `protoVersion`; HELLO_ACK, BEACON and DISCOVER_RESP
  carry the same two fields back. If `protoVersion != 2` or the
  major.minor of `appVersion` differs, the phone flags the session
  `versionMismatch` and shows "版本不一致：电脑端为 vX，本机为
  v2.0.0，请先更新电脑端" with a jump to the update check — a version
  mismatch must never fail silently. The update manifest also carries a
  `windows` entry; the phone surfaces "电脑端最新版 vX" next to its own
  version so partner state is visible before pairing.
- Client behaviour (Android `UpdateChecker`): check on launch and from the
  检查更新 button; if `version` is newer than the app's `APP_VERSION`
  (semver-numeric compare), show `notes`, download the APK to cache,
  **verify SHA-256 and abort on mismatch**, then hand the file to the
  system installer via `REQUEST_INSTALL_PACKAGES` + a `content://` URI
  from the app's own minimal provider (no AndroidX FileProvider).
- The same canonical address string must appear verbatim in the Android
  source, the Windows (C#) source, and this document;
  `tools/check_protocol_consistency.py` fails on drift.

## Discovery constants (v2 ADDITION — flagged in work-v2/android-report.md)

- mDNS service type: `_airbridge._udp` (Android browses via `NsdManager`;
  PCs advertise the same type).
- BLE service UUID (proximity presence only; audio stays on WiFi):
  `9072f6d1-ec44-0c52-ad20-dc70f9bc5a38` — derived deterministically as
  the first 128 bits of SHA-256("AirBridge-BLE-Service-v2") formatted as
  a UUID. PC advertisements may carry the PC `deviceId` as UTF-8 service
  data under this UUID.
- HELLO may carry `pairTap: true` (one-tap pairing request from the
  nearby list); the PC decides whether to grant it (its own confirm /
  pairing window) — the verify-code confirm on the phone still applies.
