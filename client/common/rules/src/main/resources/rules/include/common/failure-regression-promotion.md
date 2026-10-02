# Failure Regression Promotion

## Design Goals

- Turn each confirmed, reproducible user-owned prompt, rule, or workflow failure into the smallest evidence-backed
  correction at its owning decision boundary and regression scenarios that a campaign can test for its return.
- Make that correction on the first qualifying failure without halting the user's task for a certification campaign,
  and leave certifying the corrected guidance to a campaign that the user chooses to start.
- Keep regression scenarios coherent by consolidating cases that make the same reader decision, rather than
  accumulating one standalone scenario per incident.

## Guidance

Use this rule only after Failure Prevention has produced its attribution record and the routing condition established
that the affected guidance is user-owned. The confirmed first failure initiates this procedure: make the correction and
record its regression scenarios yourself before resuming dependent work. Do not invoke `cat:prompt-optimizer` for the
failure, because a campaign needs a clean worktree, a confirmed contract and many trials, and would halt the user's
task. Preserve the reported observable failure, governing instruction or contract, earliest divergent decision,
available delivery evidence, and direct regression case.

Classify the attribution before changing guidance:

- If an existing rule already directs the correct decision but was not loaded, delivered, or applied, repair that
  earliest routing or delivery boundary. Do not duplicate its requirement in a new rule.
- If no applicable rule directs the required decision, or its wording leaves a supported branch ambiguous, amend the
  owned authoritative rule or create a focused companion only when its trigger and reader decision differ.
- If the failure belongs to an implementation, external dependency, or unsupported observation rather than user-owned
  guidance, repair or report the responsible component or dependency without inventing a rule change.

For every correction to user-owned prompt, rule, or workflow guidance, record regression scenarios before resuming
dependent work: the reported case, a meaningful analogous case, and a dissimilar case that must not receive unrelated
guidance. Each scenario is one Markdown file holding the prewritten turns and the expected behaviour. Store it in the
corrected guidance's scenario directory: for `<root>/.cat/rules/<path>.md`, where `<root>` is the project or `${HOME}`,
use `<root>/.cat/tests/<path>/`; for other guidance in the project, such as CAT's plugin rule sources, use
`<project>/.cat/tests/<path relative to the project, without .md>/`. Extend an existing scenario that already makes the
same reader decision under the same observable trigger instead of adding one; add a new scenario only when none can
express that decision.

When a user asks for a current percentage, ETA, or completion estimate, Long-Running Progress Reporting is the
governing rule. Its regression belongs with progress-claim routing: without an evidence-backed component inventory
the response reports uncertainty, while a complete inventory produces only the supported calculation. Do not create a
second progress-estimation rule.

Accept a correction only after prompt or rule validation passes for every changed source directory. Nothing runs a
regression scenario outside a campaign. Periodically merge scenarios that prove the same branch without losing an
independently observable boundary.

A correction made this way is recorded, not tested: its scenarios are tested only when the user starts a
`cat:prompt-optimizer` campaign whose prompt system includes the corrected guidance. When you report the correction, say
so; you may suggest such a campaign, and start one only at the user's request.
