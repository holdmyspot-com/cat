---
depends-on:
  - ../common/backward-design.md
---
# Class Design

## Design Goals

- Make each component's responsibility, lifecycle, ownership, safety, and concurrency guarantees explicit, and keep its
  API and collaboration boundaries compatible with them.
- Make components replaceable and testable only where a supported variation or external integration needs a boundary,
  without exposing unsafe convenience paths, test-only production responsibilities, or trivial indirection.
- Keep a collaborating family of implementation-only top-level types discoverable through one focused package, without
  leaking their enclosing component's name into every local type.

## Guidance

When designing or changing a component, API, collaborator, variation point, integration boundary, construction path, or
test-support boundary, first identify the guarantees each participant needs: caller-visible results and failures,
ownership and lifecycle, safety, concurrency, and the supported variations. Evaluate only the conditions that can
prevent those guarantees: one component owns unrelated outcomes; a supported variant requires changing stable policy;
an implementation weakens a contract; a consumer receives operations it does not need; or policy depends directly on
integration mechanics. Restore a failed guarantee with the smallest boundary or contract that makes it verifiable. Do
not add an abstraction when no independent variation, external integration, or testable boundary needs one.

When supported variants differ in behavior, put that behavior behind a narrow contract and add an implementation rather
than repeatedly extending a central conditional. Every implementation must preserve its contract's caller-visible
preconditions, results, state guarantees, and failure behavior. Give each consumer only the operations it uses, and keep
high-level policy independent of an integration's concrete mechanics. Select the concrete integration at the composition
boundary.

Place a third-party tool, service, process, or other external interaction behind a focused access boundary. That
boundary owns the external request, environment, lifecycle, output, and external failures; application policy and
interpretation remain outside it. Apply the same boundary to test fixtures unless external behavior itself is the
subject under test.

Keep a helper out of production when only tests use it. Make shared test support explicitly available to the tests that
need it, and move it into production only after identifying an actual production consumer. State a component's intended
concurrency guarantee in caller-visible terms, then choose state-management mechanisms that preserve it; do not add
concurrency machinery merely as speculative protection.

When a component needs several related top-level implementation types, place that family in a package named for their
shared domain and keep the entry point and its non-public collaborators together there when their dependencies permit.
Use the package to establish that context: an implementation-only type should state its local responsibility, such as
`PublicCommands`, rather than repeat its enclosing component's name. Retain a component prefix only when callers
must distinguish equally plausible types across package boundaries. Do not create a package for a single incidental
helper or move a component by widening package-private dependencies merely to satisfy this organization; first extract
or relocate the dependent family through a reviewed boundary.

Apply the same naming and package decision to test-source collaborators. A test that proves one component's public or
internal boundary belongs with that component family and uses its local responsibility as the class name, such as
`PublicCommandsTest`; it must not repeat the production component merely because the test source has a broader package.
Whenever creating or moving a top-level implementation or test type, compare its simple name with its package context
before compiling: remove a component prefix that the package already establishes, or record the cross-package ambiguity
that requires retaining it.

Expose only the operations intended callers need. Do not add a wrapper, override, helper, or command that merely passes
the same inputs to another operation; add one only when it changes behavior, visibility, contract, or composition. Do
not introduce a builder merely to reduce mandatory argument count; use one when optional values, independent defaults,
staged required-value checks, or named configuration materially improve the API.

When designing or changing an externally callable API, derive the caller-visible guarantees first. For each offered
input, output, option, overload, or operation, check whether it lets callers violate a required guarantee merely by
choosing the convenient option. Omit that option when it does; do not present an unsafe representation as a convenience
alternative.

Do not add or retain an overload, convenience method, adapter, default, or conversion that accepts or returns a
representation known to violate a primary guarantee. A safe core behind that entry point is still unsafe: callers can
choose the weaker path and lose the guarantee before the core can restore it. Reject the entry point instead of
converting it internally. A convenience is allowed only when it preserves every guarantee of the primary contract.

For example, an API whose monetary contract requires exact decimal values must not offer binary floating-point input or
output merely to simplify callers; use an exact decimal or domain money representation throughout that API. Likewise, an
API that must protect a secret must not accept a plaintext string merely because it is easy to pass. These are unsafe
entry points even when their implementation immediately converts the supplied value.

When an external boundary unavoidably supplies a less-safe representation, isolate its acceptance at that boundary,
validate and convert it immediately, and do not propagate it as an ordinary API choice.
