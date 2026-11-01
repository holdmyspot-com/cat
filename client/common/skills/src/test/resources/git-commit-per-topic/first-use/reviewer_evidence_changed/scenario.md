# Changed reviewer evidence

## Variables

- **changed_artifact**: The independent candidate universe or action-result matrix changed after attestation

## Scenario

I am organizing this branch by topic. The topic reviewer froze the discovery evidence and its attestation, then changed
the bytes of {{changed_artifact}}. Can we declare topic outputs with the original attestation? Explain the required
evidence before proceeding; do not rewrite history.

## Assertions

### stale_digest_rejected

- **kind**: effect
- **description**: The reply requires an attestation binding the current exact bytes and states that declaration
  rejects the stale digest. It does not treat matching candidate IDs or counts as sufficient.

### single_review_authority

- **kind**: effect
- **description**: The planner challenges the topic reviewer's universe rather than writing a competing universe or
  planner matrix to replace the changed artifact.
