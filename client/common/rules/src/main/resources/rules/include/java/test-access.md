# Java Test Access

## Design Goals

- Let a Java test exercise a production-required non-public behavior directly when its public caller adds unrelated
  work, without widening the supported API or substituting different test-only behavior.

## Guidance

First check whether a focused production refactor can expose the behavior at its existing production-required
visibility. When Java visibility still prevents the test from calling that behavior directly, use a narrow, typed
SharedSecrets façade that delegates to the production operation. Keep the operation's visibility determined by its
production callers. Do not add a public test-only method, a mutable test seam, or a second implementation of the
behavior. Test the larger caller separately when its integration is part of the claim.
