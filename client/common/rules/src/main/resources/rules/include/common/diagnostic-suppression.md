# Diagnostic Suppression

## Design Goals

- Resolve a warning, lint or static-analysis finding, or other diagnostic by correcting what it reports whenever an
  in-scope change can do so while preserving supported behavior, so suppression never hides a fixable problem.
- Keep any unavoidable suppression narrow, explained, and visible to a later reader.

## Guidance

Suppressing a diagnostic includes a suppression annotation or comment, an exclusion or relaxed threshold in a check's
configuration, a disabled check or feature, an ignored result, and output hidden from the user or a log. Treat each as
containment, not a correction.

Before suppressing, identify what the diagnostic reports and whether an in-scope change can correct it, such as
restructuring the code, giving a resource a clear owner, replacing a deprecated call, or fixing the configuration.
Attempt the earliest such correction that preserves the supported behavior, then verify at the affected boundary that
the diagnostic is gone and the capability that suppression would have removed still works. A diagnostic that misjudges
correct code still calls for a correction when a reasonable change makes the code clear to the check; a correction that
takes more code than the suppression is not a reason to suppress.

Decide each finding from its own cause. An existing suppression of the same check elsewhere in the project shows only
that someone contained that diagnostic there; it is neither a convention to follow nor evidence that this finding is a
false positive. Before calling a finding a false positive, establish the fact it disputes at the flagged code, such as
which object closes the reported resource and when.

Suppress only when no in-scope change can correct the cause, such as when it lies in generated, vendored, or
third-party code outside the authorized scope, or when the user explicitly chooses that trade-off. Then suppress at the
narrowest scope available, one statement or member and one named check, state the reason beside the suppression, and
keep the diagnostic observable elsewhere when it reports a real condition.

For example, a static analyzer warned that a test never closed a fake process the test created and handed to an object
that closes it. Moving the fake into a small test-owned class removed the warning while the test still verified the same
behavior; annotating the test method to suppress the check would have been containment. Likewise, do not disable runtime
performance data to hide a shared-storage warning when the process-launch boundary can give each runtime its own
storage.
