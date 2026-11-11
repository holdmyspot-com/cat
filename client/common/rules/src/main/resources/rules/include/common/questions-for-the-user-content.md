# Request Execution: Questions for the User

## Design Goals

- Ask the user only for unresolved product decisions or material architecture/design decisions, and explain their
	alternatives, consequences, and a recommendation with each question.

## Guidance

Before asking the user a question, lazy load `request-execution/gap-classification.md` and apply its product-policy
and material architecture/design classification; that rule decides which decisions the user must settle. Present the
concrete alternatives, their consequences, and your recommendation. A question about missing information qualifies only
when that information is needed to settle such a decision.
