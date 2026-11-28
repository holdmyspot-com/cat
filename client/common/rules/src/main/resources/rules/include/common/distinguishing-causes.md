# Distinguishing Causes

## Design Goals

- Reach each verdict of a test, a check, or a cause analysis only on evidence that rules out the other causes of the
	same observation, so that a broken setup or a wrong diagnosis is not reported as an expected outcome.

## Guidance

When designing a test, a check, or a cause analysis, list every result its observation could have and every cause that
could produce each result. Make the test or the diagnosis tell those causes apart: reach each verdict, whether pass,
fail, skip, or a named cause, only on evidence that rules out the others. Report a cause that cannot be told apart from
another as unknown, with what would separate them, not as the likeliest one.

Two consequences follow. Code or a test that chooses a branch, outcome, or report by naming a cause, such as a skip,
fallback, retry, "unsupported" path, degraded mode, or error message that states a reason, confirms the specific cause
it names, not a symptom that other causes share. A step that the choice depends on and that did not take effect is a
failure, not the named case.

For example, a test makes a file unreadable and skips when it can still read the file, because the process runs with
elevated privileges. The read can also succeed because the permission change did nothing, or because the file system
ignores permissions. The test reads the file's permissions back: when they still grant read access, its setup failed
and it fails; it skips only when they grant none, and then names the privilege as the cause. In a diagnosis, a request
that failed can reflect a bad credential, a DNS error, or an outage. Saying that the server is down needs evidence that
excludes the others, such as the name resolving and the connection being refused or timing out while other hosts
respond; when none separates these causes, report the cause as unknown.
