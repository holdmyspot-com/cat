## CLI Output Contracts

## Design Goals

- Provide deterministic, stable CLI and hook output contracts using only conventions documented by the target harness.

## Guidance

Treat machine-readable CLI and hook output as a stable contract. Write only the documented payload to standard output;
write diagnostics to standard error. Keep field names, line endings, ordering, and error behavior deterministic. Test
the contract through the public Java entry point or the external launcher boundary, as appropriate.

Before defining harness hook output or exit behavior, verify the current harness reference and keep the relevant
boundary test tied to that documented contract.
