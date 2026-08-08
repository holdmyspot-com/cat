# Clear Writing: Unexpected-Result Updates

## Design Goals

- Let a reader whose next action changes because of new evidence understand the intended result, observed difference,
  practical effect on a named affected actor or operation, corrected behavior and its scope, verification, and any
  action that resumes after the correction before the update identifies how the difference was discovered.
- Preserve each contract-critical literal that identifies the affected input, state, operation, or result, so the
  reader can act on the same fact the evidence established.

## Guidance

Use this rule when new evidence requires an actor to correct, narrow, or otherwise change an action. The action may be
an earlier plan, an implemented behavior, or a requirement the actor would otherwise apply. Write one unheaded prose
paragraph of one or two natural sentences. “Concise” limits repetition, not the facts a reader needs to decide what
changes.

Copy a literal whose exact characters identify a decision-bearing input, state, operation, or result once in the first
statement that establishes the fact. This includes identifiers, paths, request values, configuration keys, and other
opaque tokens. Later use an unambiguous reference such as “that input” or “that path”; repeat the literal only when the
later statement could identify a different fact or the reader must use its exact characters. A descriptive value may be
paraphrased when its characters do not affect the decision.

Use this decision sequence, in order:

1. State the expected-versus-observed contrast. Name the normal or required result, then use “but” for the different
   observed behavior itself—not for the test, review, command, or other source that reported it. When a public
   contract or other authority establishes the normal result, name that authority and the constraint it establishes.
   For a reported acceptance, say that the observed boundary accepted the input; do not replace that observed result
   with a possibility such as “can accept” or “may accept.”
   Do not replace an exclusion with a positive guarantee, or infer an exclusion from a positive guarantee alone. When
   the normal result follows from an actor’s limited need, name that actor, its need, and the normal behavior together.
   For an action that is too broad, name its affected scope and the universal requirement it would impose.
2. State the practical consequence. For an invalid input or state, write the complete transfer in this order: “[actor]
   sends [that input or state] to [the caller-facing operation], which can [wrongly accept, receive, or act on it].”
   Do not replace that transfer with “can reach,” an unnamed boundary, “later processing,” “the system,” “gets
   through,” or the burden alone. If the receiving operation is also the observed validation boundary, name both roles.
   Correct: “The generator can send that input to the caller-facing operation, the validation boundary, which can
   accept it.” Incorrect: “The caller-facing operation sends the input to the validation boundary.” The first names
   the sender, receiving operation, and possible wrong action; the second reverses who sends the input. For an
   over-broad requirement, name the scope and specific requirement first, then the extra work or restriction and who
   bears it.
3. State the corrected behavior and the check that proves it. Name the actor, operation, input or state, and result
   before the check. A correction to an observed acceptance makes that observed boundary reject the input; it does not
   replace the boundary with the operation described in the consequence. Name it as “the validation boundary” in the
   correction; do not refer to it only as “that operation” or “the operation.” The check must run the corrected behavior
   with its selecting actor, input, or state and state the observed result. Do not promise only an edit, a rule, a code
   location, or a test status.

For a correction that narrows an earlier requirement, finish the sequence with paired condition-and-result clauses:
name the condition that retains the normal behavior and its result, then the condition receiving the exception and its
result, and say the check verifies both. Keep each actor and need the same as in the observed difference; “where
required,” “when needed,” or another unnamed exception is not a condition. A named subset establishes only that subset,
so state what remains available to a different actor or input. An unconditional correction needs no invented exception.

When the correction is a prerequisite for later work, name that work and say it resumes after the check passes. When
another actor must authorize the correction, name that actor and requested action instead of promising the change.
Mention a review, test, command, or other discovery source only in a separate closing sentence after the decision
sequence; it may confirm the observation but cannot replace it. A completed result that leaves the action unchanged
uses the foundational Clear Writing success-update guidance instead.

Before sending, reject a draft when its first clause is not the normal result, its “but” clause does not name the
observed difference, its consequence lacks the affected actor or operation, or its correction lacks the exact behavior,
check, required resumption, or either narrowing branch. Then compare the first occurrence of every decision-bearing
literal character-for-character with the task and verify that each later reference unambiguously identifies it.
