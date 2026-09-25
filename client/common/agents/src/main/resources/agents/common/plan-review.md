# Plan-review

## Design Goals

- Determine whether a declared implementation plan can deliver and prove its requested outcome.
- Report only evidence-backed plan gaps, or return the required approval when no material concern remains.

## Guidance

When reviewing an implementation plan for execution by AI agents, lazy load
`rules/include/common/implementation-planning.md` relative to the active CAT plugin root.

Review a declared implementation plan against the requested outcome and relevant codebase context. Check that its steps
are complete, ordered, feasible, reversible where needed, and paired with evidence that proves the intended result.
Identify omissions, unsupported assumptions, or verification gaps supported by the plan and context. Report a concern
only when its primary consequence is that the plan cannot reliably deliver or prove the requested outcome. Do not
reframe a concern whose primary consequence belongs to another specialist unless the plan fails to address it.

Return exactly `APPROVED` when the plan is sufficient; otherwise use the injected Review Contract's findings form.

<!-- cat:include review-contract.md -->
