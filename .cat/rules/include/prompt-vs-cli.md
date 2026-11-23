# Prompt-versus-CLI Boundary

## Design Goals

- Put each operation at the prompt and CLI boundary with the owner that can perform it reliably.
- Let callers start complete CLI operations without exposing partial workflow mechanics.
- Let callers find the right CLI operation from a compact index, and inspect its inputs only when they select it.

## Guidance

First lazy load the `## Guidance` section of the shared plugin rule from the checkout at
`${CAT_PROJECT_ROOT}/client/common/rules/src/main/resources/rules/include/common/prompt-vs-cli.md`. This applies the
guidance from the version of CAT being developed. In CAT, put operations with deterministic, non-brittle contracts
in Java CLI code under `client/**`. Shell and prompt instructions dispatch those operations through the CLI.

A prompt may invoke a public CLI operation. A CLI may construct a typed request for a runner and consume its retained
result, but it must not start, configure, or communicate with a model. Only a focused Runner implementation may own a
native model process, its environment, protocol, output, and external failures. Keep model-specific process mechanics
out of CLI workflow, policy, persistence, and validation code.

When a prompt, CLI, hook, or launcher depends on environment state, first lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/environment.md`. The owning harness boundary must determine which native values
exist in its process; do not make a prompt or tool reuse a hook-only variable in an agent shell or another process
context.

Prefer one CLI command for a complete deterministic workflow. When a model decision, user interaction, or original
content must occur between deterministic operations, call the CLI again at that boundary.

A public CLI entry point exposes only complete caller-facing operations: a workflow lifecycle action, or a bounded
creation, audit, comparison, or diagnosis that accepts all of its caller inputs and returns its documented result. Do
not publish individual composition steps—such as claiming work, making a workspace, launching a worker, recording an
intermediate result, or deciding a partial outcome—as separately invocable commands. Those steps exist only to complete
another operation, so that operation performs and validates them in order. Make the top-level help a compact, grouped
index of public command names and short purposes; show a command's accepted inputs and observable result only when the
caller asks for `help <command>` or `command --help`. If an internal step needs direct code coverage, call an internal
operation from the owning package or module, not the public CLI parser.
Test that the public entry point rejects an internal step before it creates files, starts a worker, or changes a record.

Before leaving or returning an operation in a prompt file, build an exhaustive operation ledger covering its inputs,
outputs, state transitions, validation, formatting, Git or filesystem work, subprocess construction, failure checks,
and follow-up diagnosis. Classify every ledger item as CLI-owned or prompt-owned. Apply the shared rule and CAT's
Java CLI requirement above, and record the reason for every prompt-owned operation. Reinspect the rendered prompt
after either conversion and check every remaining prompt operation against its recorded reason. The CLI accepts the
model's reviewed decisions as structured data. It must not infer topics, intent, names, or equivalence from prose,
paths, regular expressions, or patch similarity.

For each extracted workflow, make the CLI own the complete mechanical transaction: capture authoritative state, derive
safe values, validate preconditions and postconditions, preserve recovery evidence, execute deterministic steps in
order, and return machine-readable results. Do not leave callers to copy hashes, calculate paths, manage temporary
state, choose routine command arguments, compare expected values, or manually perform safe follow-up checks.

Treat a CLI's documented machine-readable result and actionable failure diagnostic as the canonical source for its
mechanical facts, derived values, validation results, recovery state, and next steps. A prompt may state when to invoke
the CLI and how a later decision consumes a returned key, but must not restate those details or create a parallel
procedure. When a prompt needs a deterministic fact that the CLI does not return, extend the CLI's structured result
instead of copying or deriving the fact in the prompt.

Before adding or updating a prompt that invokes a CLI, compare every mechanical claim in the rendered prompt with the
CLI's inputs, structured success result, and failure diagnostics. Remove a prompt claim when the CLI already supplies
it; add a CLI result field when the claim is necessary for a later decision. Verify the final prompt contains only its
decision boundary, required user authority, and references to the canonical CLI keys—never duplicated values, validation
logic, recovery instructions, or generated command details.

Give each CLI result a contract that its caller can consume without text scraping: use `key=value` lines for scalar
values read by an LLM, and compact JSON for nested, repeated, multiline, or non-LLM-consumed values.
