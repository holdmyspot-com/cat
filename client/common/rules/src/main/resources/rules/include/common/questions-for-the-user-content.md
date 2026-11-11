# Request Execution: Questions for the User

## Design Goals

- Ask the user only for unresolved product decisions, material architecture/design decisions, or a request referent
	whose materially different readings the context does not settle, and explain their alternatives, consequences, and a
	recommendation with each question.
- Do not substitute the agent's plausible interpretation for a user request whose materially different meanings would
	require different work or produce different results.
- When the user asks whether you have questions, settle every unresolved question eligible under Request Execution
	before dependent work, so the work does not rest on a guess that the user could have settled.

## Guidance

Before asking the user a question, lazy load `request-execution/gap-classification.md` and apply its product-policy
and material architecture/design classification; that rule decides which decisions the user must settle. Present the
concrete alternatives, their consequences, and your recommendation. A question about missing information qualifies only
when that information is needed to settle such a decision.

A referent clarification, as the next paragraph describes, is an eligible question alongside the decisions that that
classification selects. When another agent delegated the work, report the alternatives to that agent instead of asking
the user.

Before researching, changing, or scheduling work, resolve a request's referent from the request and authoritative
conversation context. If two or more reasonable readings select materially different targets, scope, result, or next
action and that context does not choose one, ask a short clarification that names those alternatives and the decision
they change. Do not turn one plausible reading into a search query or a rule change merely because it is the most
recently mentioned term. Continue without asking only when every reasonable reading leads to the same safe, reversible
action and report which reading that action covers. For example, a request to update “the workflow” may refer to the
workflow being discussed or to the rule that governs it; ask which one when those changes would affect different
readers. A request that explicitly names a file and change does not need this clarification.

When the user asks whether you have any questions, apply Request Execution's question eligibility and ask every
unresolved eligible question. An invitation to ask questions does not transfer implementation decisions to the user.
Give your recommendation with each. When the user answers, check whether
the answers leave or raise another eligible question, and ask it in the same reply, without waiting for the user to ask
again. Repeat until no eligible question remains, then say so and proceed. Do not ask a question that the request, the
conversation or a reversible default already answers; state the default you chose instead.
