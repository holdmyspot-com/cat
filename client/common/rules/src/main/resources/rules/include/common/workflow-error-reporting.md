# Workflow Error Reporting

## Design Goals

- Ensure a workflow's human or agent caller learns about incorrect or unavailable dependencies discovered while
  executing maintained instructions or workflows.
- Ensure a completion claim distinguishes successful work from degraded, bypassed, or unverifiable workflow checks, so a
  caller can make the next recovery decision with the actual evidence.
- Ensure a rejected generated or captured result reaches the end user with its retained evidence, earliest verified
  cause, unresolved uncertainty, and publication or recovery state.

## Guidance

When executing a maintained instruction, prompt, skill, rule, or workflow exposes a stale or broken reference, or
missing, unavailable, or incorrect behavior from an invoked CLI or other dependency, keep using the normal safe
workaround, recovery, or failure handling. Before returning control to the caller, explicitly report the problem in the
final summary, including the affected instruction dependency, its observed impact, the evidence checked, and whether it
was fixed, migrated, worked around, or remains unresolved. Report confirmed facts only; distinguish an observed failure
from an unverified suspected cause.

Treat each of these as reportable workflow problems even when a command eventually succeeds: an incompatible persisted
artifact or schema; discarded or restarted review evidence; a skipped, substituted, or weaker verification gate; and a
tool default that can change a required decision by omission. State whether the workaround preserved the required
outcome. If it did not, do not report completion; name the exact remaining decision or check and its recovery path.

When a validator, capture, review, or other workflow gate rejects generated output, report the failure to the end user
before returning control. Name the rejected output and gate; the retained execution artifact or event log; the exact
rule, dependency, or contract checked; the first verified causal action; every separate contributing condition; and
each unresolved input. State what publication, replacement, or existing result was preserved and what next action can
produce a conforming result. Do not collapse a validator's pattern match, an installation failure, a cache miss, or a
timeout into a root cause unless the retained evidence establishes that conclusion. A final success summary must list
every rejected required result and may claim only the subset whose required gates passed.

Before a final success summary, compare the required workflow states and quality gates with the evidence actually
recorded. Report every mismatch that could affect correctness, completeness, or confidence. A matching final tree, log
file, or successful no-op command is not evidence that the required semantic review or behavior check occurred.
