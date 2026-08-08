# Contract Retirement

## Design Goals

- Retire a supported contract completely while preserving every contract that remains supported.

## Guidance

Use this rule when removing, replacing, renaming, or ending compatibility for a public or workflow-facing API, command,
schema, configuration key, installer, plugin artifact, or documented procedure. Do not use it for an internal refactor
that has no supported consumer.

Before editing, classify every affected contract as **retain**, **retire**, or **internal**. Include its implementation,
entry points, callers, tests, documentation, configuration and build files, package contents, scripts, generated help,
and migration or recovery paths. A command name or source file alone is not the inventory.

Work backward from the required observable state: a supported consumer can use every retained contract, and no supported
consumer can invoke, discover, package, or follow a retired contract. Turn that state into an artifact-wide audit:

1. Search all affected source code, tests, documentation, configuration/build files, manifests, installers, scripts,
   packages, and generated help for each retired identifier and behavior.
2. Remove or replace every retired implementation, route, reference, fixture, and artifact. Do not merely hide a route
   from usage text or leave a compatibility branch that a consumer can still reach.
3. Recheck every retained item from the classification. A removal must not be inferred from naming, version number, or
   proximity; prove that each retained entry point still reaches its current behavior.

Verify retirement at both boundaries:

- For a retired entry point deliberately kept as a rejection boundary, a behavior test proves it rejects without
  performing work. Otherwise, prove that no supported consumer can invoke or discover it.
- A behavior or integration test invokes each affected retained entry point through its supported consumer boundary and
  proves it still succeeds.
- For a packaged or user-facing workflow, build the assembled artifact and run the relevant end-to-end path. Source-only
  tests do not prove packaging, documentation, or launcher retirement.

Before handoff, repeat the artifact-wide search. Treat every remaining reference as a decision: retain it only when it
names a current behavior or is necessary to test rejection; otherwise remove or replace it. Record the reason for each
intentional retained reference in the change review.
