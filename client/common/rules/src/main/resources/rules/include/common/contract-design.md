# Contract Design and Preservation

## Design Goals

- Ensure each substantive, independently readable prompt file declares goals that remain complete, accurate, and
  executable for its supported use cases and durable outcomes, translating claims of complete, explicit, or mechanically
  auditable coverage into evidence and validation that creation, regeneration, or revision can enforce.
- Make every Design Goal concrete enough to derive a direct passing and failing acceptance case without inventing its
  actor, trigger, promised result, proof, or an independently selectable scope category, while stating the durable
  invariant rather than an example-specific inventory of inputs or mechanisms.
- Ensure a Design Goal governing a choice among alternatives names the evidence-based selection outcome without
  predetermining a branch that only the reported example selected.
- Ensure an artifact's Design Goals form one coherent contract: identify and repair duplicate, overlapping,
  contradictory, and prerequisite-dependent goals before relying on them to derive guidance or verification.
- Place each new or changed normative prompt requirement in the authoritative prompt file for its reader decision and
  trigger, creating a focused companion only when no existing owner covers that decision.
- Express guidance compactly without hiding an independently actionable obligation, its condition, its evidence, or
  rationale the intended reader needs to apply it.
- Apply iterative generalization to compaction only when evidence proves a shared invariant preserves every existing
  reader decision; retain uncertain wording and report the possible improvement instead.

## Guidance

Apply the foundational Backward Design rule injected by the default rule, including its terminal-outcome derivation and
iterative generalization.

Before choosing a normative edit or its owner, record a design checkpoint in the task evidence: the reported case, one
meaningful analogous case, one relevant dissimilar case, the reader decision required in each case, and the invariant
that survives the comparison. Use that checkpoint to identify the authoritative prompt file and the smallest wording
change. Do not claim to have applied iterative generalization when this record is absent, or when it merely names cases
without showing the decision each one requires. Revisit the checkpoint after drafting: the proposed wording must direct
the original and analogous cases without imposing its action on the dissimilar case.

Before adding or changing normative guidance, inventory its plausible prompt-file owners. Compare each candidate's
reader, observable trigger, required decision, Design Goals, evidence boundary, and the durable concept that makes the
guidance necessary. Select the narrowest owner that remains correct for every current and reasonably foreseeable
consumer of that concept, not the skill, wrapper, workflow phase, or failure example that happened to expose it. Amend
an existing owner only when those factors are the same; otherwise create and route a focused companion. For example, a
rule about a build plan's declared read inputs belongs with build-plan provenance even when a statistical evaluation
first uses that plan; a rule about one harness's native environment remains with that harness. Record the owner
comparison and rejected candidates in the design checkpoint. For a rule-file organization decision, also apply the more
specific Rule Organization guidance.

For a substantive prompt file, place a concise `## Design Goals` section immediately after its title and any
frontmatter. Use one bullet for each durable, observable outcome it exists to achieve. Follow the list with `##
Guidance` when the remaining content is unheaded, or let the next named `##` section begin the remaining content. A file
that only routes to another prompt or supplies minimal metadata without independent guidance is not substantive and does
not need goal boilerplate. `## Design Goals` is the sole summary of why a substantive prompt exists; keep only distinct
invocation criteria, constraints, procedures, examples, or verification in the remaining sections.

Before creating or accepting a prompt file as a companion, classify it separately from its parent. A companion that a
reader can load directly and that supplies an independent procedure, constraint, or decision is substantive even when a
parent prompt links to it; give that companion its own `## Design Goals` and map its guidance to them. A wrapper that
only supplies harness metadata or routes to another file remains non-substantive. Verify this distinction by checking
the assembled artifact: every independently readable substantive file has `## Design Goals`, while each exempt wrapper
contains no independent guidance. Do not infer a companion's goals from its parent or treat the parent's section as
coverage for a separately readable file.

Before accepting a Design Goal, derive one direct passing case and one direct failing case from its wording. The goal
must identify the affected actor or artifact, the input, decision, or state that makes it apply, and the promised
observable result. Its wording must also identify the evidence that proves the result, or route the reader to the
guidance that defines that evidence. Reject and rewrite a goal when a reviewer must invent any of those facts to decide
whether it passed. Keep implementation mechanisms and one-off examples out of the goal unless they are part of the
caller-visible result.

State each goal as the outcome a reader, caller, or later workflow can rely on, including the condition that permits or
prevents that outcome. Do not make a relationship among internal records, hashes, receipts, caches, or other mechanisms
the goal itself: that wording tells the reader how an implementation might work without saying what it guarantees. For
example, say that a prior result may be reused only while its determining inputs are unchanged, not that a receipt must
represent those inputs. Put the mechanism and its evidence in the guidance. Apply this distinction to every internal
representation, not only stored records.

When a goal addresses state that an earlier action might have established, name the later action's required input and
the result it must produce instead of stating only that it must not rely on the earlier action. A new shell, restarted
process, or independently invoked workflow step cannot use a local value from an earlier invocation unless a documented
boundary deliberately supplies that value. Require the later action to derive or receive its needed input at that
boundary, then state the observable result. This does not prohibit a caller from supplying an explicit documented
parameter whose lifetime covers the later action.

Before a later workflow transition changes durable state based on an earlier verification, revalidate the inputs that
made that verification valid. If any determining input changed, reject the later transition before its side effect and
run the documented prerequisite that establishes a current verification. Do not treat a stale earlier result as
authority to alter the input population, publish a later result, or begin an irreversible dependent action.

When a draft goal names examples of inputs, artifact kinds, mechanisms, or other categories that all receive the same
decision, compare the reported case with a meaningful case that has a different example category and a dissimilar case
where that category changes the decision. State the invariant shared by the first two cases in the goal, and move the
example inventory to guidance. Retain a category in the goal only when omitting it would leave the reader unable to make
a different required decision. For example, a build result may be reused only when every determining input is unchanged
or revalidated; the build-plan guidance, not the goal, identifies source, resources, configuration, and generated
inputs as ways to find those inputs.

When a Design Goal governs selection, rejection, fallback, or replacement among alternatives, derive an acceptance case
where each plausible branch is selected and one where the evidence remains unresolved. The goal must name the required
evidence and resulting decision for each case: select a candidate that satisfies the contract, reject one with a
contract-specific conflict, and preserve the unresolved state as a block or investigation. Rewrite a goal that says
only to avoid, reject, retain, or replace one branch unless its wording also makes the evidence and permissible
alternative outcomes explicit. A stated preference may prioritize an outcome after evidence supports it, but it must not
treat the reported case's branch as the outcome to prove.

For each Design Goal, list the scope categories that the mapped guidance asks a reader to select, search, accept, or
reject. Read the title and Design Goals without the guidance, and test whether a reader can still derive each category's
required decision. Name a category in the relevant goal when omitting it could make the reader skip that decision; a
general summary such as “all sources” is sufficient only when it preserves every category's decision. Do not promote a
mechanism, protocol, or one-off example into a goal merely because guidance uses it.

When creating, regenerating, materially revising, reviewing, or auditing a substantive prompt, treat its `## Design
Goals` and existing content as coequal constraints. Before editing or concluding the review, map every normative
statement in the existing content to the goal, a necessary subgoal, or an explicit retained constraint. Investigate each
unmapped statement; preserve it, clarify it, or remove it only with evidence that it is obsolete, conflicting, or
outside the prompt's scope. Never silently discard an unmapped normative statement while regenerating a prompt. For a
restore and reapply task, first restore and map every original obligation before editing.

When updating an existing rule, reassess its `## Design Goals` before writing guidance. Compare the observed case and
the derived invariant with every current goal: add a goal when the rule must now achieve a durable outcome it does not
name, update a goal when its wording no longer describes that outcome, and remove a goal only when evidence shows its
outcome is obsolete or outside the rule's responsibility. Do not add a goal for an example, implementation detail, or
one-time repair. Then map the revised guidance to the retained, added, or updated goals before finalizing it.

Before deriving or revising guidance from Design Goals, audit the goals as one contract. Compare every pair and classify
the relationship as distinct, overlapping, duplicate, contradictory, or prerequisite-dependent. For an overlap, state
the distinct reader decision each goal protects or merge the goals. For a contradiction, identify the affected outcome,
choose the supported constraint using evidence, and revise or remove the conflicting goal. For a prerequisite
dependency, record the required order and verify that the later goal does not promise an outcome before its condition
exists. Do not treat similar wording, a shared example, or a successful downstream check as evidence that goals are
compatible. Preserve the audit with the rule's design checkpoint.

After repairing a goal relationship, repeat the goal-to-guidance mapping, acceptance checks, and meaningful
counterexample and analogous-case tests for every affected goal. Do not finalize while a goal is unclassified, a
contradiction lacks a resolution, an overlap lacks a distinct decision or merge, or revised guidance lacks mapped
acceptance evidence.

When creating, regenerating, or revising an artifact with declared goals or contracts, derive required coverage from
each one even when no existing content or reported failure mentions it. For every goal or contract, state its observable
outcome, test it against a meaningful counterexample and analogous case, and add the smallest implementation, procedure,
guidance, test, or other artifact content that makes the outcome achievable. Preservation mapping proves that known
obligations survived; it does not prove that the artifact covers every declared outcome. Before finalizing, verify that
every goal or contract maps to retained or new coverage and at least one concrete acceptance check.

Do not treat a goal as covered merely because a guidance passage uses related words or addresses a neighboring example.
For each broad goal, derive the specific reader decision in the reported case, then test whether the mapped guidance
names the state that makes that decision necessary, the action to take, and the observable result. Repeat with a
meaningful analogous case; retain or add coverage when it exposes a distinct decision. A dissimilar case that needs no
such decision confirms the boundary. For example, a goal to expose hidden causal relationships is not covered by a
comment that merely names a mechanism: a reader deciding why an ordering matters needs the concrete failure, ordered
action, and safe resulting state. Record these decision-level checks with the goal-to-guidance mapping.

When a goal, contract, or guidance promises that coverage is complete, explicit, exhaustive, or mechanically auditable,
identify the atomic item that must be covered, the record that names its decision and evidence, the owner that writes
the record, the validation boundary that reads it, and the error that rejects an omitted, duplicated, or unassigned
item. A summary claim, a source-level classification, or a successful later operation is not that record. Test the
validator with the smallest incomplete input that would otherwise reach the promised boundary, then verify that a
complete input proceeds.

When a goal, contract, or guidance names a principle, pattern, standard, or acronym, do not assume its name tells the
reader how to act. Enumerate its independently applicable parts. For each part, derive an observable trigger that tells
the reader when to evaluate it, the guarantee it protects, the smallest response when that guarantee is at risk, and
evidence that the response restored it. Test those parts with a case that needs each one and a meaningful case that does
not. A compact combined instruction is sufficient only when it still lets the intended reader identify and apply every
part. Otherwise define the parts or narrow the stated scope; never claim that guidance applies a complete named
principle when it supplies decisions for only a subset.

Base every recommendation on its derived outcome, acceptance evidence, and required conditions; do not generalize from
one observed failure or propose an ad-hoc remedy. State the concise, externally useful rationale in those terms without
revealing private reasoning.

When generating or revising guidance, remove wording only after verifying that it carries no independent reader
decision, condition, action, outcome, evidence, or necessary rationale. During every prompt-file regeneration, material
revision, review, or audit, perform a separate compaction pass after preservation mapping. Use Iterative Generalization:
compare neighboring and semantically related passages by reader, trigger, action, condition, evidence, and rationale;
merge them only when one shared invariant preserves every mapped decision. When the cases or evidence leave that
conclusion uncertain, retain the wording and report the compaction candidate rather than merging or removing it. For
each overlap, record the merge or the distinct actionable difference that requires both. Do not conclude that no
compaction is possible without completing this comparison. When a task requires restoring and then reapplying guidance,
treat restoration and compaction as separate gates: first restore and map every original obligation, then perform the
compaction pass. Keep each independently actionable obligation visible; use short sentences, parallel bullets, or an
ordered list when that makes its actor, action, and condition clear. Prefer the small amount of structure needed for
readability over a shorter sentence with ambiguous qualifiers or an undefined summary label. Before finalizing, map the
preservation mapping and the merge-or-distinct decision for each overlap, then re-read the compressed text as the
intended reader. Restore wording or structure if that reader cannot identify what to do, when to do it, and what
demonstrates completion.

Before removing or relocating guidance as duplicate, compare its trigger, reader, decision, and routing with the
proposed destination. Remove it only when that destination is authoritative and available for every use of the removed
guidance; otherwise retain a concise statement or an explicit route. Similar wording alone does not establish
duplication.
