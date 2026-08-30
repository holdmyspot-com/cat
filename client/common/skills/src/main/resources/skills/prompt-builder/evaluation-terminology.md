# Evaluation Terminology

## Design Goals

- Keep formal prompt-evaluation decisions, decision makers, and delivery forms distinct so workflow code, prompts,
  tests, and diagnostics assign work to the correct authority.

## Guidance

For formal prompt evaluation, use three distinct vocabularies:

- **Decision kind:** `deterministic` when a reproducible CLI check decides from declared inputs; `semantic` when
  retained evidence requires interpretation.
- **Decision maker:** the CLI for a deterministic decision; the model reviewer for a semantic decision.
- **Delivery or execution form:** a CLI or a prompt file.

Do not use `prompt` versus `CLI`, or `model` versus `CLI`, as decision-kind labels: a model can invoke a CLI and a
prompt file can direct either kind of work. In structured evaluator input, use `evaluation_kind` with only
`deterministic` or `semantic`, and derive the decision maker from that kind. Name the CLI artifact a **deterministic
check**, not an oracle, unless an external interface requires the latter term.
