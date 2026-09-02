# Idempotency

## Design Goals

- Ensure that retrying an externally durable operation after an unknown result does not create a second logical
  operation or duplicate external effect.

## Guidance

Before sending an externally durable operation that can be retried after an unknown outcome, define its logical
operation identity. Persist that identity with the exact request or request fingerprint that it identifies before the
first send. Every retry sends the same identity and request. The receiving boundary returns the original result for
that identity and rejects a different request that claims it.

Create a new identity only when a caller explicitly starts a new logical operation. The identity may be an idempotency
key, resource identifier, or another documented replay key. A new random value for each transport attempt creates a
new operation and is not idempotent retry behavior.

Verify one unknown-outcome retry reaches the receiving boundary with the original identity and request, and one
explicitly new operation reaches it with a different identity. The test must also prove that a changed request claiming
the retained identity is rejected.
