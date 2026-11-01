# Reviewer evidence contract

## Variables

- **manifest**: The invocation-local manifest path returned by prepare

## Scenario

I want to organize this branch by topic. Preparation returned `{{manifest}}`. Before any rewrite, tell the
source-neutral topic reviewer exactly which discovery evidence files to write, their JSON fields, and which bytes the
attestation hashes. Do not create outputs or rewrite history yet.

## Assertions

### consumed_sidecars

- **kind**: effect
- **description**: The reviewer writes the manifest's independent-candidate-universe, independent-action-result-matrix,
  independent-candidate-discovery-passes, and independent-review-attestation JSON sidecars. The response does not direct
  creation of candidate-universe, topic-candidate-universe, or planner-action-result-matrix sidecars.

### attested_bytes

- **kind**: effect
- **description**: The attestation binds the reviewer, captured base and tip, workflow revision, raw-diff handoff,
  unprimed review, and the SHA-256 of the exact independent universe and matrix bytes in their respective digest fields.

### terminal_pass

- **kind**: effect
- **description**: Discovery evidence has at least two consecutively numbered passes. The terminal pass names every
  final candidate exactly once and finds none.
