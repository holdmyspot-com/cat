# Implementation Planning

## Design Goals

- Let agents translate a design into implementation work whose required outcomes remain covered and whose connected
	execution paths are feasible, while ready assignments contain the decisions and evidence their executors need.
- Make decomposition, ordering, detail, ownership, and verification choices respond to the work's dependencies and
	uncertainty, with stated reasons that include coordination and rework without claiming an unmeasured speed advantage.
- Keep the executable plan current when evidence changes a design premise or task dependency, preserving valid work and
	exposing what must be decided before dependent implementation can begin.
- Cover consequential user-experience transitions and actor goals without requiring redundant documents or treating a
	coverage representation as a mandatory assignment boundary.

## Guidance

Use this rule when creating, reviewing, or revising an implementation plan for execution by AI agents. Complete these
entry reads in order before inspecting the project or making planning decisions; skip a read only when its full text
is already available:

1. If the plan includes work that changes observable behavior, read TDD first:

	```bash
	cat "$CAT_PLUGIN_ROOT/rules/include/common/tdd.md"
	```

	This applies when your own task is read-only: the trigger is the behavior the plan assigns. TDD owns when test-first
	is required, what establishes a behavioral red result, how unresolved contracts or unavailable test boundaries affect
	readiness, and verification before handoff.
2. Lazy load `../../common/intermediate-vs-terminal-goals.md`, `./backward-design.md`, and
	`./design-quality.md`. Those rules own terminal acceptance, design validation, and compatible
	implementation dependencies.

An agent-authored design receives the same checks as any other design; its author's confidence or approval is not
evidence that its premises are established.

Before translating a design into assignments, apply Design Quality's source-coverage check to the authoritative
product sources named by the task or design. A journey-only summary cannot narrow their outcomes, constraints, or
acceptance gates. If the design omits or conflicts with an in-scope obligation, correct its contract before assigning
dependent work; identify any genuinely unresolved decision and keep only that dependent work unready. Use a complete
task or product contract directly when no separate design is needed.

Start with the required outcomes, constraints, acceptance evidence, and dependencies derived under those rules. Preserve
that full coverage when changing the plan's work units or level of detail. A short ordered list is sufficient when the
work forms one chain; use explicit dependency relationships when several branches can proceed independently. Do not
require a separate graph artifact or scheduling tool merely to represent those relationships.

When a plan contains an unresolved premise that could change dependent work, or carries accepted work or decisions
across agents, retries, or revisions, lazy load `monotonic-progress.md` before fixing work boundaries, order, readiness,
or revision.

## Establish Behavior Coverage

For substantial user-facing work whose outcome depends on several stages, actors, channels, or interruptions, trace the
user journey through those transitions to the required result. Include relevant handoffs, recovery, and return visits
so that individually correct screens or actions do not conceal a missing experience. Use this coverage to identify
behavior that the design and plan must address.

Define that behavior through use cases or existing equivalent contracts: identify the actor's goal, preconditions,
successful outcome, and relevant alternative, failure, and recovery paths. Reuse authoritative scenarios or contracts
that already supply these decisions; separate journey or use-case documents are not required. Small local changes and
internal capabilities may need only their existing behavioral contract. Keep governing constraints visible even when
they are not captured by one journey or use case.

## Establish Executable Paths

Before selecting assignments, apply Design Quality's dependency and handoff checks to concrete paths from the contract's
allowed initial and recovery states through the required outcomes. For each consuming decision, trace its required
inputs back to an independently available input or an earlier producer, including the first invocation when no prior
result exists. Check both availability and validity: the producer's permitted behavior must establish the conditions
under which the consumer's decision is sound. Matching types or result schemas alone do not establish those conditions.

Use these traces to derive implementation prerequisites and the earliest runnable acceptance checks. Distinguish a
premise that can be checked now from a composed outcome that requires unfinished local components. For the latter,
identify the enabling implementation, place its integration check at the first runnable boundary, and keep only claims
depending on that result conditional. Do not make a component depend on a check that itself requires that component to
exist, or treat a scheduled check as evidence that its premise holds.

Repair each missing producer, incompatible assumption, or dependency cycle in the responsible design before marking
its assignment ready. Reuse established inputs and guarantees; do not add redundant producers or probes to an already
valid path. Recheck the affected paths after changing work boundaries or order. An outcome inventory, identifier map,
or list of components can support this check but cannot replace demonstrating their connected execution.

## Select the Work and Its Order

Choose the smallest coherent, verifiable portion of a use case or equivalent contract that preserves coupled decisions
and useful acceptance evidence. It must end in a result an agent can implement and verify with the context it receives.
A whole journey, whole use case, screen, or individual interaction is not automatically an assignment. Compare only
alternatives that could change a dependency, verification boundary, ownership boundary, or exposure to rework:

- Use a behavior slice when exercising its connected path can resolve a consequential integration or design question
	before further implementation depends on the answer.
- Use a component, shared capability, or coherent batch when its shared internals, compatibility constraints, or atomic
	transition need one consistent implementation and can be checked together. Such an assignment need not be directly
	user-visible. Keep the later consuming behavior and its integration check visible in the plan.
- Use a focused investigation when an unresolved premise changes which implementation is valid. Name the decision its
	result resolves and the implementation work that depends on it; an investigation result is not implementation
	completion.

These units can coexist in one plan. Distinguish required prerequisites from a preferred order. Place a decision's
evidence-producing work before implementation that relies on its answer, at the earliest boundary the governing design
checks permit. Keep independent ready work eligible while another branch awaits evidence. Do not assume that a named
component, source directory, or phase is an independently runnable assignment.

## Set Detail, Ownership, and Verification

Make each ready assignment complete for its selected executor: identify the intended result, relevant decisions and
constraints, available prerequisite inputs, ownership, and the check that accepts its output. Reference authoritative
contracts for settled details. An executor expected to exercise judgment may own stated design choices; a mechanical
executor must receive those choices resolved before handoff.

Keep later work in the outcome and dependency outline. Defer an implementation choice only when identified evidence
could change it; name the unresolved choice, the work or input that resolves it, its responsible decision maker, and
the condition for making the assignment ready. Detail stable later work upfront when doing so is useful. Before starting
deferred work, incorporate the evidence and complete its assignment. A rolling detail horizon does not permit missing
outcomes, hidden prerequisites, or an ambiguous ready handoff.

Choose ownership separately from decomposition. Account for shared decisions and writes, context reconstruction,
handoffs, review, and integration when deciding whether independent assignments justify more agents. Keep a small
cohesive task with one owner when splitting supplies no independent work or distinct verification benefit. When the plan
assigns work to subagents, lazy load `delegation.md` for assignment, isolation, readiness, and integration requirements;
do not substitute this planning rule for that execution contract.

When selecting verification work, lazy load `testing.md`.

Derive the plan's checks from the applicable rules' execution and handoff requirements. Name each required check and
schedule it before accepting the result or releasing the handoff it governs, even when the current task is only to plan
and the executor will run it later. A reference to a rule does not supply a missing plan step. Schedule focused checks
with the work they can accept, and the governing design's integration checks when the relevant components can first run
together. Batch checks for a coherent stable group when they still establish its required outcomes; neither frequent
checks nor a final broad check substitutes for missing boundary evidence.

Keep premise checks, behavioral red checks, and final acceptance distinct in the plan; passing a test based on an
assumed contract does not establish that contract.

State why the selected combination fits the observed dependencies, uncertainty, and available checks. Treat any
unmeasured elapsed-time advantage as a reasoned expectation. Do not invent durations, claim a universal optimum, or turn
that expectation into a forecast-based stopping or scheduling policy.

## Revise From Evidence

When a check or implementation result changes a premise, identify the affected design decision, work units, dependencies,
and acceptance evidence. Resolve the decision with its responsible owner, revise the affected plan, and reassess which
assignments are ready before releasing dependent work. Retain unaffected decisions and results only under their governing
validity conditions. Keep one current executable plan and the full required outcome coverage throughout revision.
