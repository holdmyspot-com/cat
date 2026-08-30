# Review Handoffs

## Design Goals

- Give an independent prompt reviewer only the evidence needed to test a stated acceptance condition without leaking a
  preferred conclusion.
- Use Sol-high only to resolve an evidence-defined uncertainty, disagreement, or high-impact challenge, not as an
  unrecorded replacement for the normal evaluation reviewer.

Before delegating review, choose the exact readable artifacts, allowed output destination, and question to answer. Use
`prompt-evaluation review-bundle <question.md> <retained-bundle-directory-under-cat-work-temp>
<result-path-relative-to-bundle> <evidence>...` to copy only those selected artifacts into the reviewer bundle. Do not
allow repository discovery, edits outside the returned result path, or access to prior findings unless they are evidence
under review. Reject a handoff that supplies the desired result, proposed wording, or unrelated project material.

Ask the reviewer to try to falsify the acceptance condition by naming a missing prerequisite, bypass, boundary case, or
counterexample. Treat a preference or unsupported alternative as non-finding; resolve only findings with observable
evidence.

For a workflow-owned repeated-failure review, use Sol-high and write the conclusion only to the returned
`review-result.json` path. For every returned signature key, include a finding with that key, either `restructure` or
`no_prompt_change`, and the prompt obligations that remain required. Return the result to the workflow owner for
recording. This records that the bounded review completed without adding a trial, a promotion, or a compliance result.

Use the normal independent reviewer for ordinary formal evaluation. For every assertion, require a status and concrete
evidence. When an assertion names normal input/output behavior, run the smallest deterministic probe that calls the
changed production boundary and give its observed result to the reviewer. Escalate a sealed, bounded bundle to Sol-high
when the normal verdict conflicts with that probe, omits an assertion's evidence, leaves a material contradiction, or
reviews a recurring or high-impact failure. Preserve both verdicts and name the specific conflicting assertion,
observation, and resulting decision. Do not escalate merely to obtain a preferred verdict, and do not replace all normal
reviews with Sol-high: that would hide which cases require additional judgment and make the ordinary evaluation series
needlessly expensive.
