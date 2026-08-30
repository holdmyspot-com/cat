{{CONTEXT}}

# Evaluation data

```json
{{EVALUATION_DATA}}
```

Update the existing `verdicts.json` in the current workspace; do not create a verdict file in a copied `trial-*`
directory or any parent or sibling directory. The CLI has already created its complete JSON shape: preserve every
top-level trial/case identifier and every nested assertion identifier exactly as written. Do not add, remove, rename, or
move any key. Fill each blank assertion `status` with `passed` or `failed` and its `evidence` with a non-blank concrete
source, event, command result, or produced value. Fill each item `status` with `passed` only when all of its assertion
statuses are `passed`; otherwise use `failed`. Do not use one generic explanation as evidence for several assertions.
Cite the same observable evidence in the final response.

The evaluation data contains only assertions that require semantic review. For every item, read its compact
`executor_response` first and verify the listed SHA-256 value before deciding a reader-facing wording assertion. That
file contains the completed task response; the full `executor_output` remains provenance for routing or protocol facts.
The CLI has already decided and retained its deterministic checks. Do not add, reinterpret, or restate those checks:
review only the supplied assertions independently. Cite `executor_response` for each wording verdict.

Use only the current workspace and the files named in the evaluation data. Do not search for prior verdicts, other
runs, caches, or work directories: they are not evidence for this review and can contain retired assertion names or
outcomes. If a named file is unavailable, report that exact unavailable path instead of looking for a substitute.

{{SEMANTIC_REVIEW_CONTRACT}}
