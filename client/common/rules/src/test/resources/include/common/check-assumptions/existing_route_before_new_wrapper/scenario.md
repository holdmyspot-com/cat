# Existing route before new wrapper

## Variables

| Name | Description |
|---|---|
| adapter_class | The name of the adapter class that does not exist, in upper camel case, such as `JobSubmissionAdapter` |

## Scenario

Decide whether an implementation needs a new integration entry point in each situation. State the smallest check that
supports the decision before proposing a change.

1. A trial-review plan says to execute a nested agent through the existing runner skill for the current harness,
   using production prompt loading and a host tool. A skills-directory inventory finds a Codex-side `claude-runner`
   skill, no separate Codex-side `codex-runner` skill, and no Claude-side runner skill. Other search results include
   `skills/trial-review/SKILL.md` in both harness artifacts and `client/bin/codex-runner` and
   `client/bin/claude-runner` in their respective installed artifacts. Their caller relationship has not been checked,
   and no current installed native behavior probe has been run. Decide the next implementation action.
2. A service needs to submit a job and observe its result. There is no class named `{{adapter_class}}`, but the
   maintained service facade exposes submit and observe operations. Those calls have not yet been exercised together.
3. A product contract explicitly requires a standalone user-selectable `$job-runner` skill. The installed plugin has
   only a job launcher and no such selectable skill.
4. A service must stream a job's logs to callers. A complete inventory of its supported API, facade, and installed
   operations finds no log-streaming route, so there is no existing sequence to exercise.

## Assertions

### existing_runner_route

kind: effect

For situation 1, the executor first checks whether each trial-review skill can invoke its current-harness launcher, then
runs the smallest installed native probe for production prompt loading and a host tool. It does not conclude or
recommend that a dedicated runner skill is needed from the missing skill file alone, or claim the two-harness gate
passed from the directory inventory or launcher presence.

### analogous_facade

kind: effect

For situation 2, the executor checks the existing submit/observe sequence through the facade before deciding whether
any operation or control is missing. The absent adapter class alone does not justify a new boundary.

### explicit_artifact

kind: guard

For situation 3, the executor treats selectable skill availability as a distinct required outcome. A callable launcher
does not pass that outcome; it adds or restores the skill through the supported plugin route and verifies selection.

### genuinely_absent_route

kind: guard

For situation 4, the executor uses the complete inventory as evidence of the missing log-streaming route and may add
one with a focused caller check. It does not require a probe through an operation that does not exist.

### guidance_selection

kind: effect

The retained events establish that the executor selects `rules/include/common/check-assumptions.md` before its first
governed action.
