# Shiftbell sync protocol (v1)

The PC app and the phone app tell each other when an alarm is confirmed, snoozed, or the day is marked off.
Confirming on one stops the other; a snooze on one snoozes both; a day off on one silences both.
Times, work days and the link are not synced.

## Relay

`https://iamprincejkc.netlify.app/api/shiftbell/{channel}` on the owner's portfolio (repo `jkc-portfolio-2026`, `server/api/shiftbell/`).
Netlify, not the `iamprincejkc.pages.dev` copy: the relay's storage is Netlify Blobs, which only exists there.
The old custom domain `iamjkc.space` no longer resolves (checked 2026-10-31).
It stores opaque encrypted events in Netlify Blobs and cannot read them.

| Request | Does |
|---|---|
| `GET /api/shiftbell/{channel}?since={ms}` | `{ events: [{ id, data }], now }`: events written at or after `since` (server ms), oldest first, at most 200 |
| `POST /api/shiftbell/{channel}` body `{ "data": "..." }` | Appends one event, `201 { id }`. The first write claims the channel for its token |

Both need `Authorization: Bearer {token}`.
Events older than 30 days, and beyond 500 per channel, are dropped.

## Pairing

The PC makes a 32-byte random secret and shows it as a QR code and as text: `SB1-` + base64url (no padding) of the secret.
The phone scans or pastes it.
Everything else is derived from the secret with HKDF-SHA256, empty salt:

| Info string | Bytes | Used as |
|---|---|---|
| `shiftbell/v1/channel` | 16 | Channel id, lowercase hex |
| `shiftbell/v1/token` | 32 | Access token, base64url without padding |
| `shiftbell/v1/key` | 32 | AES-256-GCM key |

## Events

An event is UTF-8 JSON, encrypted with AES-256-GCM, a random 12-byte nonce, and associated data `shiftbell/v1/{channel}`.
`data` is base64url of nonce, then ciphertext, then the 16-byte tag.

```json
{"v":1,"type":"ack","kind":"TimeIn","date":"2026-10-31","at":"2026-10-31T07:46:05","from":"pc"}
```

| Field | Meaning |
|---|---|
| `type` | `ack` (typed "done"), `snooze`, `dayoff`, `test` or `testend` |
| `kind` | `TimeIn` or `TimeOut` (not for `dayoff`) |
| `date` | The work day, `yyyy-MM-dd` |
| `at` | When it happened, local wall-clock time, `yyyy-MM-ddTHH:mm:ss` |
| `until` | `snooze` only: when it rings again |
| `snoozes` | `snooze` only: snoozes used so far for that alarm |
| `off` | `dayoff` only: true for a day off, false to undo |
| `id` | `test` and `testend` only: which test alarm |
| `from` | `pc` or `phone`; a device ignores test events it sent itself |

Test alarms are never part of the state:

- `test`: the other device rings the same test, if the event is under 2 minutes old and that id has not rung there yet.
- `testend`: that test was stopped (confirmed or snoozed) on the sender; the other device stops it too.
- The PC reads every minute when idle, so a test pressed on the phone rings there within a minute.
- The phone only reads while the Shiftbell app is open (every 5 seconds), while it rings, and when its own alarm is due, so a test pressed on the PC rings there only if the app is open.

A second vector, a `test` event with id `0123456789abcdef` from the phone at 2026-10-31T13:30:00, is checked in both apps' tests too.

Unknown fields are ignored; an event that does not decrypt or parse is skipped.

## Merging

Applying an event twice, or events in any order, gives the same state:

- `ack`: keep the earliest acknowledgement; it clears any snooze.
- `snooze`: ignored once acknowledged; otherwise keep the latest `until` and the highest `snoozes`.
- `dayoff`: the change with the latest `at` wins (`DayOffChangedAt`).

## When devices talk

- A local confirm, snooze or day-off goes into a persisted outbox and is sent at once, then retried until the relay accepts it.
- Reads ask for `since` = the last server `now` minus 2 minutes, so a write that landed out of order is never skipped.
- PC: reads every minute, every 3 seconds while ringing, and right before an alarm starts ringing.
- Phone: reads when an alarm fires (before ringing), every 3 seconds while ringing, on boot and when the app opens.
- No network is never a reason not to ring: a read that fails or takes longer than 4 seconds lets the alarm ring.

## Test vectors

Secret bytes `01 02 ... 20`, nonce bytes `a0 a1 ... ab` (computed with Node's crypto, checked in both apps' tests):

| | |
|---|---|
| Code | `SB1-AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA` |
| Channel | `d572d7e8d50d215efff4140d0edf1166` |
| Token | `CHg7lE2AcanFZHsuv2ZWMbXINZJeL7O4Nx0mAzzmHO0` |
| Data for the JSON above | `oKGio6SlpqeoqaqrNIT6aKGm3XoALybGz2UAD2YHvGyPMAjZ8ga4cSN7ETChqskIlJZb-3Ies9nK-7Kep-hKijdeNyscZtLGxtTBqlKfzsNCEhQFasIqmDSZx9FxJyQVrSu154V8NZgLkFbkDHkdFtyvL1jCusZomvPb` |
