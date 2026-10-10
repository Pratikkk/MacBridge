# Android UI performance

Measured on an authorized physical Android phone with the debug build, Home open,
and no interaction on 2026-10-10. This is an idle sample, not a scrolling or
startup benchmark. CPU counters have 10 ms resolution on this device.

## Baseline

The initial 20-second observation added no rendered frames. A repeatable sample
with `tools/profile-android-idle.py` measured 20.95 seconds, **0 new frames,
0 new janky frames, 0 ms main-thread CPU, and 50 ms aggregate process CPU**.
There was no evidence of continuous idle redraws or an idle main-thread spin.
The cumulative renderer statistics included four slow frames (38–700 ms),
but the idle interval added none. These cumulative frames cannot attribute a
startup bottleneck or measure gesture performance; those require a separate
interactive trace. Debug/JIT overhead also limits comparison with release builds.

After installing the validated update and manually reopening Home, the same
sampler measured **20.41 seconds, 0 new frames, 0 new janky frames, 10 ms
main-thread CPU, and 40 ms aggregate process CPU**. Both samples were already
quiet; the small CPU difference is within counter granularity and is not evidence
of a speedup. The benefit established by regression checks is bounded Home data
and stopping unobserved/lifecycle-inactive query work. The Android build and all
120 tests passed, and the installed APK checksum matched the built APK.

## Changes based on code inspection

- UI collection uses `collectAsStateWithLifecycle`, stopping below STARTED and
  receiving fresh state on return. Connection/transfer engines retain their own
  application lifetime.
- UI Room streams use `WhileSubscribed(5_000)` rather than eager application-wide
  collection. The grace period covers quick navigation; unused history queries
  stop afterward. Stored permissions are still checked directly by the engine.
- Home's SQL query materializes current transfers and the latest row in each
  direction. Previously Home loaded, sorted, and filtered the complete history
  on each progress update. Full history and restart recovery remain available.
  SQL still scans the existing table; this bounds UI allocations/work rather
  than promising constant database lookup cost.
- Security subscribes to diagnostic logs only in the Logs section. Filtering,
  formatting setup, and public identity display values are remembered rather
  than repeated on unrelated recompositions.

## Loop audit

The transport blocks on socket reads, suspends between reconnect attempts and
10-second heartbeats, and does network work off the main thread. Transfer
watchdogs block on channels when no transfer is active/paused. Their 250 ms
checks while transfers exist enforce expiry, disconnect, and permission changes;
they are suspended IO work, not an idle spin. File copy/hash loops are bounded
IO work. The Mac controller's pipe readers block on reads and suppress unchanged
published fields; no Mac performance claim is based on the Android measurements.

## Reproduce

Leave Home open, then run (substitute the authorized device serial):

```sh
python3 tools/profile-android-idle.py --serial SERIAL --seconds 20
```

Use `--adb /path/to/adb` if adb is not on PATH. The command reads frame and CPU
counters; it does not open, tap, reset, or clear the app. It rejects process
changes and missing windows. Leave the app foregrounded throughout the sample;
an attached window alone cannot prove uninterrupted foreground activity.

`UiPerformanceTest` checks a 500-row history with simultaneous/paused transfers,
completion/cancellation and intact history, query stop/restart after observers
leave, idle Home recomposition, background collection cancellation, and fresh
progress on resume. Existing transfer/protocol tests cover affected behavior
including disconnect, resume, invalid inputs, permissions, identities and shutdown.
