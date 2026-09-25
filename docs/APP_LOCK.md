# The in-app lock (Settings → Privacy & Browsing → App lock)

A PIN-and/or-biometric lock over the app itself, independent of the device's own
lock screen. It is aimed at the case every messaging app's "app lock" is aimed at:
the phone is already unlocked and handed to somebody else.

## What is stored

- `AppLock.save(secret)` — the password, as a PBKDF2 hash (120k rounds) plus the
  per-install salt, in its own file. `AppLock.isSet()` is the switch's truth, so
  the lock is on exactly when a password exists.
- `AppStore.appLockFlow()` — whether the lock is *armed* (mirrored in the UI).
- `AppStore.appLockBioFlow()` — offer the fingerprint as well as the keypad.
- `AppStore.appLockScreenOffFlow()` — lock when the screen turns off (default on).
- `AppStore.appLockLeaveFlow()` — lock when the app is left (default on).
- `AppStore.appLockDelayFlow()` — the grace period, in minutes, 0..60 (0 = Instant,
  the default). Applies to whichever triggers are on.

There is no recovery for a forgotten password beyond the confirm-guarded "turn the
lock off" escape that appears after a failed attempt (see `AppLockScreen`), which
is why the settings card says so before a password is set.

## Where the gate lives

`com.hikari.app.ui.AppLockGate`, wrapped around `MainActivity`'s content: while the
lock is on and the app has not been unlocked **this** composition — not the app,
not its dialogs — is what renders. `unlocked` lives in the composition (NOT in
saved state), so a fresh process always starts locked.

It is **deliberately not wrapped around `PlayerActivity`**. A video that is playing
when the screen turns off keeps playing (background audio is a real use of the
player), and the system's own lock screen stands between the phone and the player
anyway. The gate covers the app's browser/library/settings screens.

## How a lock trigger is detected

One `ProcessLifecycleOwner` observer, plus (since 0.10.39) a `BroadcastReceiver`
for `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`:

- On `ON_STOP`, `screenOff` is `screenWasOff.get() || power.isInteractive == false`.
- On `ACTION_SCREEN_OFF` the flag is set; on `ACTION_SCREEN_ON` it is cleared, and
  `ON_START` re-reads `isInteractive` so each departure is judged on its own.

**Why both signals.** `isInteractive` is the honest answer to "did the screen go
off or did the user leave?", but it is read from a lifecycle callback the platform
may dispatch either side of the display state settling. On some devices the read
happened before the state settled, returned `true`, and the "Lock when the screen
turns off" switch was never consulted — the app came back unlocked. That is the
reported *"lock when screen off is not working"*. `ACTION_SCREEN_OFF` IS the event,
so it cannot be raced; `isInteractive` remains as the catch for a broadcast that has
not been delivered yet. Both are cheap, and either one alone is enough to lock.

- Screen off → the **screen-off switch** decides; anything else (Home, Recents,
  another app, a lock key) → the **leave switch**.
- With the switch off, the departure does nothing at all — not even a grace period.
- With the grace period at 0 the state flips to locked immediately; otherwise the
  moment of departure is recorded and `ON_START` locks only if the grace has
  elapsed (`AppLockGate`'s `leftAt`).

## The cold-start hole (fixed in 0.10.39)

`enabled` used to be collected with `initial = false` — i.e. "the lock is off" —
so on every cold start the app composed and showed its content for a frame or two
before the stored value arrived and the lock card replaced it. The gate now holds
`enabledState: Boolean?`, draws a blank card until the store has answered, and only
then decides. A lock that flashes the screen it protects is not a lock.

## Tests that matter

1. Lock on, screen-off switch on, leave switch off, grace Instant:
   power off → power on → phone unlocked → the app must ask for the PIN.
2. Same with the screen-off switch OFF: power off/on must NOT lock, but pressing
   Home and returning must.
3. A video playing when the screen goes off must keep playing; returning to the
   app must leave the player on screen (see "Where the gate lives").
4. Cold start with the lock on: the app's own screens must never be visible before
   the unlock card (this is the flash the 0.10.39 change fixes).
