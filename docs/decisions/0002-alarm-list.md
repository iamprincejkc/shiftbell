# 0002 - Any number of alarms, synced between devices

Date: 2026-11-03.
Status: accepted.

## Decision

Time in and time out stop being two fixed settings and become the first two entries of a list of alarms.
The owner can add more (a standup, a meeting, a one-off call) on either device, and the list is the same on all paired devices.

## Alarm

| Field | Meaning |
|---|---|
| `id` | `time-in` and `time-out` for the presets, 16 random hex characters for added ones |
| `role` | `TimeIn`, `TimeOut` or `Custom`; decides the wording and colour |
| `name` | What the alarm screen calls it ("Standup") |
| `enabled` | Off alarms stay in the list |
| `ringAt` | `HH:mm` |
| `deadline` | `HH:mm` or absent; after it the alarm says "late" |
| `days` | Weekdays it repeats on (`Monday`...) |
| `date` | `yyyy-MM-dd` for a one-off alarm; when set, `days` is ignored |
| `strict` | True: stopped by typing "done". False: one Dismiss button |
| `link` | Opened by the alarm's button (attendance page, meeting link), or empty |
| `armedAt` | When its time, days or date last changed; occurrences before it never ring |
| `updatedAt` | When anything about it last changed, on any device; the newest wins a sync |
| `deleted` | A tombstone, so a deletion reaches the other devices |

Settings written by 0.3 have no `alarms` list; loading them builds the two presets from the old `timeIn`, `timeOut`, `workDays`, `attendanceLink` and `requireTypedConfirmation`, with `updatedAt` at 2000-01-01 so any real edit on another device wins.
An empty list is a real choice (every alarm deleted) and is kept.

## State

A day's records are keyed by alarm id (`alarms: { "time-in": {...} }`); 0.3's `timeIn` / `timeOut` records load into `time-in` / `time-out`.
A day off still silences every alarm that day.

## Sync

- `ack` and `snooze` carry `alarm` (the id). For the presets they also carry the 0.3 `kind`, and an event with only a `kind` is read as that preset.
- New type `alarm` carries the whole definition in `def`. It is merged per id, newest `updatedAt` wins, ties keep what is there.
- A device sends an `alarm` event for every definition it changes, and all of them once after pairing or upgrading.
- `test` carries the alarm id, so the other device rings the same alarm.
