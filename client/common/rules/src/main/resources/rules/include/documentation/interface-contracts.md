---
depends-on:
  - ../documentation.md
  - ./conditions.md
---

# Interface Contracts

## Design Goals

- Let a reader unfamiliar with the project understand the observable contract of a type, method, function, script,
	command, or configuration entry, including its inputs, results, state changes, and failures, without reading its
	implementation.
- Let a caller learn from a callable's documentation every result it can receive, what each means and when it occurs,
	without reading the implementation.
- Let a caller find the failures that its own invalid input causes before the failures that valid input can still meet.

## Guidance

Document every externally or internally callable artifact — type, method, function, script, command, or
configuration entry — so a reader unfamiliar with this project can understand its observable contract without reading
its implementation.
State only the applicable responsibility; each input's semantic role, required form, and effect; output or returned
result; consequential state changes; failure conditions and their observable result; and the next action or decision
when it is not obvious. Describe values by their domain meaning rather than their position, variable name, container
type, or implementation role. Keep method-level behavior separate from one input's description. When asked only to
document existing code, do not add a constructor, method, field, or other executable declaration merely to create
another documentation target.

Describe each result a caller can receive that has a different meaning, such as an empty, null, sentinel, or boundary
value besides the ordinary result, with the condition that produces it. For a boolean result, the condition that makes
it `true` is enough.

Document every deterministic, externally observable failure that a caller can trigger through documented inputs or
required state. When documentation lists the exceptions or errors that a callable raises, list every entry for invalid
input from the caller, such as a missing, malformed, or out-of-range argument, before every entry for a failure that
valid input can still meet, such as a closed resource or a failed read. Use plural wording when one predicate governs
two or more inputs, and singular wording only when it governs one input or each input has a distinct predicate.

When an input, selected option, or operation determines a fixed value, threshold, range, rate, unit, ordering, policy,
or timing that changes an observable result, failure, or state, state it exactly and explain its relationship to the
affected behavior. Do not replace an exact caller-visible condition with a generic label.

Describe a callable's meaningful input-to-output behavior, not incidental implementation mechanics. Omit fallback order,
data sources, delegation, algorithms, and other replaceable details unless a reader needs them to choose an input,
predict an output or failure, understand a security, lifecycle, or concurrency guarantee, or recover from a failure.
Keep mechanism-specific rationale in implementation comments when maintainers need it but callers do not.

Distinguish inputs that share a representation by their semantic roles, such as source and destination, input and
output, or accumulator and lookup. For mutable input, state what its contents represent, required incoming state,
whether the callable reads or changes it, its state after success, and any caller-visible partial change on failure.
Keep each input description limited to that input's value contract, including a semantic constraint such as a
non-blank identifier; describe input interaction, state changes, and failure effects in the callable's description.
