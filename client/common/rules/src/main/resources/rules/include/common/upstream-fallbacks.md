# Upstream Download Fallbacks

## Design Goals

<!-- A hosted build failed on every job that refreshed its package index while the provider's servers were unreachable,
     and the agent only waited for the provider to recover. -->
- A build or job step that downloads from a single upstream server, such as a package index, an artifact repository or
	a release host, still completes while that server is unreachable, by falling back, only after the primary fails, to
	an equivalent source whose content passes the checks that the primary's content passes.
- When an unreachable upstream server broke a run, the agent adds that fallback to the failing step instead of only
	waiting for the provider to recover, and no change disables or relaxes a signature, checksum or other integrity check.

<!-- A fallback passed its stubbed tests but failed on the real runner, whose package sources pointed to a server-list
     file that the stubs never modelled. -->
- A fallback is relied on only after it has run once in the environment where its step runs, with the primary forced
	to fail or during a real outage of it, so that a defect that only that environment shows is found before an outage.

## Guidance

This rule applies when you write or change a build or job step that downloads dependencies, packages or tools from one
upstream server, and when a job failed because such a server could not be reached: a name that does not resolve, a
refused or timed-out connection, or an error response during the provider's outage. It does not apply to a step that
already falls back to a verified equivalent source, or to a failure with another cause.

Keep the step's download and add a fallback to it; do not drop or replace the dependency, even when the job's tools
could be supplied another way. The fallback is an equivalent source: a mirror or archive that serves the same packages
and that the same signature keys verify, such as the official archive that the distribution itself publishes, or a
release download pinned to a version with a checksum that the step compares. The project need not name that source: take
the distribution and release from the job's log and workflow, not from your local machine. The fallback runs only when
the primary fails, so that a healthy primary behaves as before. Add the fallback in the same change even when you cannot
run the step's environment yourself; do not leave the step unchanged because the fallback is unverified, since a later
run verifies it, as described below.

A source is equivalent only when its content passes the integrity checks that the primary's content passes, and the step
applies those checks itself when it runs: the signing keys that the environment already trusts verify the distribution's
official archive, and the step's own comparison verifies a pinned download, so you need not fetch the candidate to
confirm it. Never disable or relax a check to make a fallback work, such as turning off signature verification, trusting
unsigned packages or skipping a checksum. When a candidate's content cannot pass those checks, it is not a fallback:
choose another or report that none exists.

When an outage of such a server broke a run, add the fallback to the failing step instead of only waiting for the
provider to recover or rerunning until it does. Waiting for recovery may continue as a parallel track; it does not
replace the fallback.

A fallback counts as verified only after it has run once in the real environment where the step runs, such as the same
runner image or hosted worker, with the primary forced to fail, or during a real outage of the primary. Stubs and a
local machine do not count: they do not model that environment's own source configuration, and the next ordinary run
does not verify it. Until that run, your final reply to the user says that the fallback is unverified and names that
run, whether or not the user asked about verification. Force the primary to fail on that machine only, without affecting
anything else: make its host names unresolvable or point them at an unreachable address, such as through the hosts file,
or point the step at an invalid primary through its configuration. Make this a one-off verification run, not a permanent
step of every build, so that routine builds do not depend on the fallback source. A disposable worker needs nothing
restored.
