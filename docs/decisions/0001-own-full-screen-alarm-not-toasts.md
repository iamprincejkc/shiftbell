# 0001 - Ring with our own full-screen window, not a Windows notification

Date: 2026-10-27
Status: accepted

## Context

The owner's time-in and time-out alarms "sometimes don't pop", and when they do, nothing makes them read that the alarm is about the attendance punch.
The Windows Clock app and most alarm apps deliver the alarm as a toast notification.
Toasts are hidden by Do Not Disturb, Focus, and full-screen apps (the owner games on this PC), they fall into Action Center after a few seconds, and they play through the notification sound path, which a muted PC silences.
They also need the PC awake at the exact minute.

## Decision

- Shiftbell draws its own top-most borderless window on every monitor and plays its own synthesised tone through the normal audio path.
- The window refuses every close except Confirm (type the phrase) and a limited Snooze.
- "Let me do it now" docks it to a small top-most bar for 3 minutes so the punch can actually be done in the browser, then it takes the screen back.
- Whether an alarm is due is recomputed every second from persisted state and the wall clock, so a missed alarm catches up after sleep, a restart or a clock change.
- A scheduled task restarts the app every 15 minutes and wakes the PC 2 minutes before each alarm; a Run key starts it at sign-in.

## Consequences

- The alarm works whatever the notification settings are, and cannot be read as "just another notification".
- Taking focus from a game is intended; the dock bar exists so that being forced does not stop the punch from being done.
- A PC that is shut down cannot be woken; the catch-up alarm on next start is the fallback, and the screen says "You're late".
- WPF plus WinForms, self-contained: a 78 MB exe in exchange for no runtime dependency.
