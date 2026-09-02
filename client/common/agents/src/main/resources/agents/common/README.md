# Shared agent bodies

These harness-neutral bodies define stakeholder reviews, plan construction, plan review, and mechanical plan execution.
Claude and Codex wrappers supply only harness metadata, a capability tier, and an include reference to the matching
body.

Stakeholder bodies review a declared change and report evidence-based findings or a clean approval. The plan-builder
body produces an implementation plan, while the plan-review body assesses a declared plan. These bodies are read-only.
The work-execute body may modify the workspace only after its read-only preflight establishes that the supplied plan
requires no implementation decisions.
