# Failure and Workaround Rule Routing

## Design Goals

- Route each decision about handling, analyzing, preventing, suppressing, or working around a failure or limitation to
	its focused rule before the reader acts on that failure.

## Guidance

When adding, catching, translating, or reporting a failure, lazy load `../error-handling.md`.

When considering a workaround for an external defect, lazy load `../external-workarounds.md`.

When analyzing a reported or newly observed unexpected failure, discovering that an earlier action violated the
user-authorized scope, diagnosing why an agent ignored or misapplied an instruction, or designing a prevention, and
before invoking an operation that can modify multiple artifacts or whose actual write set is unproven, lazy load
`../failure-prevention.md`.

When the Failure Prevention analysis confirms a reproducible failure involving prompt, rule, or workflow guidance and
the affected guidance is user-owned, lazy load `../failure-regression-promotion.md` before resuming dependent work. The
first qualifying failure is the trigger. User-owned guidance is under the current project's `.cat/rules/` tree or the
current user's `${HOME}/.cat/rules/` tree, or is other guidance that a project rule declares user-owned. Do not load
this rule for installed/cache, third-party, or unknown-owner guidance. A subagent does not load this rule or edit that
guidance: it reports the confirmed failure, the affected guidance and the attribution evidence to the agent that
delegated its work, then continues only work that does not depend on the correction.

When a compiler, linter, or static-analysis check reports a warning or finding, lazy load `../diagnostic-suppression.md`
before choosing how to resolve it, including before searching the project for how it handled the same finding. Also lazy
load it before suppressing any other diagnostic, or before disabling, excluding, or relaxing a check.

When assessing an apparently absent condition, a helper's apparent limitation, or a proposed workaround, lazy load
`../limitations-and-workarounds.md`.

When executing a maintained instruction, prompt, skill, rule, or workflow exposes an incorrect or unavailable
dependency, lazy load `../workflow-error-reporting.md` before handoff.
