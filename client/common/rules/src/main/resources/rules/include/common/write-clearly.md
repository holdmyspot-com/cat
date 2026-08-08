# Clear Writing

## Design Goals

- Let each intended reader understand the observable situation, make the required decision, and act correctly without
  translating project-specific shorthand; for a rule or prompt, the executing agent is the primary reader and a user
  must still be able to audit the same guidance.
- Keep a status update faithful to its evidence by distinguishing confirmation that the planned action still applies
  from evidence that requires a correction.
- Distinguish advisory guidance from an enforcement boundary when reporting how a change addresses a failure.

## Guidance

For a prompt file, write first for the agent that executes it. The agent must be able to identify exactly when
the guidance applies, what it must do, and what result lets it continue or stop. A user reviewing the same file must be
able to check that trigger, action, and result without translating project shorthand. Do not replace an executable
instruction with a friendly summary, and do not hide the instruction behind language that only an agent can interpret.

For every other reader-facing artifact, name the actor, input or state, action, result, and next decision that its
intended reader needs. Use common precise language unless a specialized term preserves a necessary distinction. Define
that term at its first use by saying what it represents, who uses it, and what result it affects. Do not define a term
by repeating it or replacing it with another project label. For example, say “a new folder containing the copied project
that the validator examines” instead of “an integration project.” Do not explain ordinary technical terms unless this
project gives them a specialized meaning.

When a source comment explains a local representation of an external actor, resource, or boundary, name both the
represented role and the concrete value or scope that models it. At a lifecycle transition, identify the actor, the
resource, the statement or scope that performs the transition, and the resulting effect. For example, explain that the
scope containing a child process's standard-error output closes that output and thereby delivers EOF to the parent
consumer; do not leave the reader to infer the process relationship from a pipe type.

Use a product or framework name only when it changes the explained behavior. When an ordinary parent and child process
would behave the same way, name those generic roles instead.

## Explain Decisions From Observable Facts

When the instruction depends on two inputs or states being different, name the concrete alternatives and the result the
reader can observe before naming an abstract category such as “type identity” or “semantic equivalence.” State the
decision for equal results, different results, and any explicit permission to treat the alternatives alike. An example
is unnecessary when those alternatives and the decision are already visible beside the instruction.

Apply the same standard to a new category, exception, boundary, or conclusion. Name the observable facts that place a
case inside the category, the evidence that confirms those facts, and the decision that follows. Do not let an example
or desired result define the category. Check one meaningful similar case and one case outside the category so the
wording states the shared condition instead of the example's label.

When defining a threshold, probability, range, or other decision setting, state the boundary and the decision directly.
For a selection threshold, write “Treat [observable condition] as [accepted or rejected decision].” For an error
probability, write “If [condition on the opposite side of the boundary] is true, allow at most [probability] chance of
[incorrect decision].” Avoid labels such as “desired rate,” “evidence for,” and “allowed risk” when the reader must
configure or interpret the setting. If an error bound covers a range, name the boundary value that is hardest to tell
apart from the accepted side, explain why, and compare a meaningfully farther value. For example, 90% compliance can
produce evidence similar to an accepted 95% rate, while 12% compliance produces many more failures and is much less
likely to be accepted by mistake. Do not add this comparison for a fixed option whose effect does not vary by value.

## Explain Causes and Evidence

When explaining a non-obvious order, cleanup, guard, workaround, handoff, or failed operation, give the shortest causal
sequence before naming its mechanism: the relevant starting state, the action, the artifact or value it affects, the
later action, and the safe or failed result. State how the later action obtains each needed value—for example through
an argument, durable artifact, child-process inheritance, or newly created value. Do not say an earlier action merely
“left” a value available. A direct action that has no non-obvious consequence does not need an invented failure story.

When an operation runs after work moves to another process, directory, or other state, name the information it needs and
how that information reaches the new location. If the new location does not receive it, say so and state the resulting
failure. Then either pass the required information at that boundary and verify the result, or say that the workflow
needs a capability that can provide and verify it. Do not call the change impossible only because the current
workflow lacks that capability.

When claiming that an operation changes a check, comparison, or later decision, name the artifact it reads or changes,
the property that can differ, how the check sees that property, and the decision that follows. State that proof before
introducing a hash, fingerprint, snapshot, cache, or similar mechanism. A content-and-path hash changes only when a file
is added, removed, renamed, or has different bytes; deleting and recreating the same files at the same paths with the
same bytes does not change it. To establish that an operation used unchanged inputs, compare the artifacts it reads, not
their output files merely because they share a directory. Output bytes are consequences of the inputs and can vary even
when no source changed. When the property is not yet known, say what could differ and name the before-and-after evidence
needed to find out. Do not present a possible mechanism as the established cause.

When reporting that a rule, review, or check prevents a failure, name the action it changes or rejects and the
enforcement boundary that makes that outcome occur. Advisory guidance can require or direct a reader to act, but it does
not by itself prove that the guidance was delivered or followed. Describe a wording or style rule as addressing its
specific documentation risk; do not claim that it prevents a separate routing, delivery, or application failure unless
a validation boundary rejects that failure.

When a check, observation, or result cannot prove a conclusion, name the observation, the conclusion it might suggest,
the inputs or cases it leaves unexamined, and the incorrect decision that could follow. Then give the replacement
procedure: what to inspect, what comparison or decision to make, and what evidence completes it. When reporting a
diagnosis, likewise name the producer, the artifact, the observed values, and the resulting behavior in causal order.
State only what those facts establish. If the unobserved step is still unknown, say that it remains to be located and
name the next check instead of assigning it to a component by guesswork.

When a term names a location by its workflow purpose, such as a test project, validation workspace, staging area, or
recreation directory, say at its first use whether it is new or existing, what it contains or excludes, who uses it
next, and what that use proves or produces. A named source file or output directory whose contents are already stated
beside it needs no second definition.

## Write Accurate Status Updates

After a completed check confirms the existing plan, report that confirmation and the next planned action. Do not
invent a defect or promise a correction that the evidence does not require. When new evidence requires a correction,
use the focused unexpected-result guidance instead.

## Review the Draft

Before finalizing, read the actual draft—not only this rule. For every internal label, compound relationship label, or
workflow term, either replace it with the artifact, relationship, and action it hides or define it where it first
appears. Confirm that the intended reader can paraphrase each sentence, identify the required action or decision, and
understand every necessary specialized term without opening another artifact. Rewrite any sentence that fails that
check.

When the draft repeats an exact identifier, path, command, input, or other machine-readable value that determines the
reported result or next action, copy it from the evidence and compare every repeated occurrence with that evidence
before finalizing. Do not paraphrase, shorten, or alter a decision-bearing value. If the reader does not need its exact
form to understand or take the next action, describe its role instead of repeating it.
