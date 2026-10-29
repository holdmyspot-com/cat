# Request Execution: Gap Classification

## Design Goals

<!--
A failed evaluation could either trigger another development attempt or end the workflow, changing the user's result.
Choosing a persistence schema for an already-selected recovery does not change that result; an unrequested enhancement
is outside the work. Only unresolved, contract-compliant alternatives with materially different observable consequences
require the user's product-policy choice.
-->
- Escalate only unresolved product-policy choices, to the user or to the agent that delegated the work; resolve
	required implementation and internal-design gaps from the governing contract and acceptance evidence without
	transferring routine design work to the requester.

## Guidance

Before escalating a choice during authorized implementation, derive the required observable outcome and its acceptance
evidence from the governing contract, then classify the gap:

- An **implementation gap** exists when the contract already determines the observable behavior and the owning behavior
	is absent or incorrect. Implement and verify that behavior in the responsible component.
- An **internal-design gap** exists when the contract and acceptance evidence determine the observable behavior but an
	API, schema, component boundary, failure mechanism, or other internal design is missing. Select the smallest coherent
	design that preserves the contract, define its failure behavior and tests, and verify it without asking the
	requester to choose routine implementation mechanics.
- A **product-policy gap** exists only when at least two resolutions remain compatible with the governing contract and
	available evidence, but they differ in user-visible behavior, user authority, externally documented interface,
	product scope, statistical meaning, irreversible external state, or another material trade-off. State the concrete
	alternatives and consequences and ask the user to choose. When another agent delegated the work, report them to that
	agent instead of choosing.
- An **optional improvement** is not required to satisfy the governing contract. Keep it outside the authorized scope
	and do not let it block required work.

Do not classify a decision as product policy merely because the contract omits an internal mechanism, a reviewer prefers
another design, or the implementation needs a new internal artifact or command option. While a genuine product-policy
choice remains unresolved, pause only work whose behavior depends on it and continue independent authorized work.
