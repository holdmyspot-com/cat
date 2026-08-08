# Safe Work Delegation

## Design Goals

- Allocate workflow work to the least costly authority that can verify or decide it: deterministic work to authoritative
  automation, bounded review to independently checkable delegation, and high-judgment decisions to the authority able to
  evaluate them.

## Guidance

When designing, updating, or executing a prompt-driven workflow, first classify every operation as deterministic,
bounded-review, or high-judgment work.

Move deterministic operations into authoritative automation, normally a CLI. The automation owns authoritative-state
capture, derived paths and arguments, validation, generated artifacts, recovery evidence, execution, and
machine-readable diagnostics. Do not leave an agent or human to reproduce those operations.

Delegate bounded-review work to a cheaper model only when its acceptance check is materially cheaper, narrower, or more
reliable than independently performing the delegated work. Give it explicit inputs, a bounded deliverable, and
acceptance evidence. Treat its result as a candidate until the specified deterministic check or higher-authority review
accepts it.

When a delegated response must use a specific form, state that form in the task sent to the recipient. Name the
recipient's injected response contract and required form when one exists; otherwise specify every required response
component. Do not rely on a parent-only requirement or generic handoff language.

Keep high-judgment work with the higher-level model: semantic design, intent, topic boundaries, earliest complete
placement, conflict resolution, security-sensitive decisions, and any result whose verification would require redoing
the same analysis. Do not delegate merely to create an appearance of independent review.

When a cheaper agent is available and delegation satisfies these conditions, use it; otherwise continue directly.
