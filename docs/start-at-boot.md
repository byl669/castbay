# Starting with the TV, with no screen

The receiver is meant to be always on, like an Apple TV: switch the TV on and phones
find it already, without anyone opening CastBay. This note covers what upstream did,
what was changed here, and the parts Android decides for us.

## What upstream had

A `BootReceiver` listening for `BOOT_COMPLETED`, the two `QUICKBOOT_POWERON` actions
TV and phone vendors send instead of it, and the clock broadcasts a device that sleeps
rather than shuts down usually wakes on. It hands off to `ReceiverStarter`, which starts
`AirPlayService` as a foreground service. `WakeJobService` runs every ~15 minutes while
there is a network and starts the receiver again if the system stopped it.

None of it opened an activity, so a boot never put anything on screen.

## What was wrong

`ReceiverStarter.start()` returned early on every debug build:

```kotlin
if (BuildConfig.DEBUG) {
    Diagnostics.record("start", "$why: debug build, not started")
    return
}
```

That was there so a debug build installed beside the release app did not fight it for
ports 7000, 9958 and 19000. The effect was that a debug build — the one built here —
never started after a boot at all, whatever the settings said. The preference it should
have consulted had been removed too: `ReceiverSettingsStore.save()` ended with
`.remove("start_on_boot")`, and there was no switch on any settings page.

## What was changed

* `ReceiverSettings.startOnBoot`, persisted as `start_on_boot`. It defaults to
  `!BuildConfig.DEBUG`: release builds keep the intended always-on behaviour, a debug
  build starts off and can be turned on. Both can therefore be told what to do instead
  of one silently doing nothing.
* `ReceiverStarter` reads that setting instead of `BuildConfig.DEBUG`, and records
  `start at boot is off` in Diagnostics when it declines, so the reason is visible on
  the TV (About → Diagnostics) without adb.
* A **Start at Boot** switch on Settings → General, with a note saying what Android
  insists on: a service that keeps running has a notification.
* `startOnBoot` is left out of `needsRestartComparedTo()`, so toggling it does not
  restart a running receiver the way a connection setting does.
* `AirPlayService` returns `START_STICKY` on a normal start: if Android ends the process
  for memory, the receiver comes back by itself. A deliberate stop still returns
  `START_NOT_STICKY`, so quitting on purpose sticks.

Verified on a Sony BRAVIA 4K VH2 (Android 12): with the switch off, the boot broadcast
is recorded and the receiver stays down; with it on, `AirPlayService` comes up in the
foreground, ports 7000 / 9958 / 32500 listen, AirPlay registers on mDNS, and the TV's
own home screen stays the resumed activity.

## What Android will not let us do

* **The notification stays.** A foreground service must show one. It is on a low
  importance channel and says it is waiting, but it cannot be removed without giving up
  the foreground state, which is what keeps the receiver alive.
* **A stopped app hears no boot.** Android does not deliver `BOOT_COMPLETED` to an app
  that has never been opened since it was installed. Open CastBay once after installing
  and it will be there from then on.
* **Some TVs restrict auto-start.** Sony leaves it alone; plenty of others have an
  auto-start manager that blocks third-party apps by default. Nothing in the app can
  override that — it has to be allowed in the system settings.
* **A boot may arrive before the network.** If binding fails that early, the service
  stops itself and `WakeJobService` tries again within 15 minutes.

## Checking it without a reboot

`QUICKBOOT_POWERON` is not a protected broadcast, so the whole path can be exercised
over adb:

```sh
adb shell am force-stop com.weenas.castbay.debug
adb shell am broadcast -n com.weenas.castbay.debug/com.weenas.castbay.receiver.BootReceiver \
  -a android.intent.action.QUICKBOOT_POWERON
adb shell dumpsys activity services com.weenas.castbay.debug | grep isForeground
adb shell dumpsys activity activities | grep mResumedActivity   # should be the TV's, not ours
```

`BOOT_COMPLETED` itself is protected and cannot be sent this way; only a real reboot
tests that action.
