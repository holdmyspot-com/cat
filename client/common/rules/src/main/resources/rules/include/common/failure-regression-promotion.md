# Failure Regression Promotion

## Design Goals

- Turn each confirmed, reproducible user-owned prompt, rule, or workflow failure into the smallest evidence-backed
  correction at its owning decision boundary and regression coverage that detects its return.
- Begin that correction on the first qualifying failure whenever the current session exposes prompt optimization,
  without depending on an earlier optimizer invocation.
- Keep prompt and workflow regression coverage coherent by consolidating cases that make the same reader decision,
  rather than accumulating one standalone test per incident.

## Guidance

Use this rule only after Failure Prevention has produced its attribution record and the routing condition established
that prompt optimization is available from the session's enabled capabilities and the affected guidance is user-owned.
The confirmed first failure initiates this procedure: invoke `cat:prompt-optimizer` for the affected guidance before
resuming dependent work. Do not treat a prior optimizer invocation as a prerequisite. Preserve the reported observable
failure, governing instruction or contract, earliest divergent decision, available delivery evidence, and direct
regression case.

Classify the attribution before changing guidance:

- If an existing rule already directs the correct decision but was not loaded, delivered, or applied, repair that
  earliest routing or delivery boundary. Do not duplicate its requirement in a new rule.
- If no applicable rule directs the required decision, or its wording leaves a supported branch ambiguous, amend the
  owned authoritative rule or create a focused companion only when its trigger and reader decision differ.
- If the failure belongs to an implementation, external dependency, or unsupported observation rather than user-owned
  guidance, repair or report that owner without inventing a rule change.

For every correction to user-owned prompt, rule, or workflow guidance, add regression coverage before resuming
dependent work. Add the reported case to the existing parameterized prompt, routing, or workflow suite that owns the
same reader decision and observable trigger. Add a distinct test file only when no existing suite can express that
decision; record why the suite boundary differs. The regression must prove the governed guidance is present before the
action, the reported failure now takes the required path, a meaningful analogous case does too, and a dissimilar case
does not receive unrelated guidance.

When a user asks for a current percentage, ETA, or completion estimate, Long-Running Progress Reporting is the
authoritative owner. Its regression belongs with progress-claim routing: without an evidence-backed component inventory
the response reports uncertainty, while a complete inventory produces only the supported calculation. Do not create a
second progress-estimation rule.

Accept a promotion only after rerunning the reported regression, its analogous and dissimilar cases, and prompt or
rule validation for every changed source directory. Retain one test matrix organized by reader decision and trigger;
periodically merge duplicate rows that prove the same branch without losing an independently observable boundary.
