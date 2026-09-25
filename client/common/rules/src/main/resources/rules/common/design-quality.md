# Design Quality

## Design Goals

- Keep designs focused, extensible at real variation points, and verifiable against observable outcomes.
- Keep public contracts faithful to their domain constraints so callers cannot supply values the domain excludes.
<!-- A planned optimizer route assumed its native runner could enforce selected editable roots and report source loads;
     later real-runner probes changed those decisions after dependent interfaces had been planned. -->
- Let a multi-component plan establish decision-bearing execution capabilities at an authentic boundary before dependent
  design relies on them, and assign checks that need unfinished code to the first runnable integration gate.
<!-- In a feature-plan review, contract derivation needed source bytes that inspection had not retained. Another call
     handoff needed a claim before its producer supplied one. Defining each producer result and readiness point in one
     place exposes both timing gaps; the obligation inventory still covers requirements outside the traced use-cases.
     Later, an epoch installation needed a stop acknowledgment after cancellation cleared its active reference, while
     the resume record omitted it. A complete recovery trace must show how the later consumer retrieves that evidence.
     A candidate capture also needed a baseline held by a contract component scheduled after capture; tracing the
     capture input backward exposes the implementation dependency cycle before either component is assigned. -->
- Let multi-component designs establish one authoritative contract for each required handoff and demonstrate compatible
  success and recovery use-cases with boundary-specific evidence. Trace required consumer inputs to available producers
  without unresolved implementation-order cycles; selected use-cases are not exhaustive contract coverage.
<!-- The campaign-store component passed its focused and combined tests, but a closure review then found reachable
     lifecycle bypasses and missing durable fields. A green test selection had not covered its whole assigned contract. -->
- Let an implementation component be called complete only after its reachable production behavior and evidence cover
  every obligation assigned to that component, including failure and restart paths.
<!-- A whole-plan review was repeated after a known feasibility result and then again after a stale review-status row
     was corrected. A later review rejected the plan because historical bridge prose still competed with its current
     design. Consolidated inputs and one executable path avoid those reruns; unchanged scoped receipts remain useful. -->
- Let a multi-component plan receive review against one consolidated evidence snapshot, retaining review evidence for
  decisions whose reviewed inputs can be proved unchanged when later facts arrive, with one current executable design.

## Guidance

Use this rule as the canonical design standard for implementation and design review.

## Required Outcomes

A design must:

- Preserve codebase coherence: use established terminology, abstractions, module boundaries, and conventions unless a
  deliberate replacement improves them consistently.
- When a caller-facing parameter represents one of a known finite set of domain choices, expose a constrained domain
  type at that boundary. Use the language's idiomatic form, such as an enum, literal union, algebraic data type, or
  closed value object; do not declare that type and then accept a broad primitive such as a string or number instead.
  Preserve the constrained type through internal calls. At an untyped ingress such as a request, file, or command-line
  argument, validate the raw value once and convert it to the constrained type before calling the domain API. Do not
  apply this rule to genuinely open-ended text or numeric input. Test one accepted value and one excluded value at the
  public boundary.
- Reuse suitable existing project and JDK facilities before introducing new abstractions, dependencies, or
  reimplementations.
- Add an extension boundary only when a supported variation needs independently replaceable behavior; otherwise keep
  the behavior direct. Do not introduce a configurable abstraction merely for a hypothetical future variation.
- Do not require a caller or user to investigate, select, derive, or perform routine work that the system can safely
  determine or perform from available information. Narrow candidates and establish the applicable result before asking
  for input or failing; ask only for authority, preference, missing information, or action that the system cannot safely
  supply. Do not guess, broaden scope, or perform an irreversible action to avoid asking.
- Give every behavior one responsible component. Before duplicating or replacing behavior an integration is intended to
  provide, trace its path from the required outcome through the responsible component, registration or installation,
  configuration, invocation boundary, and observable result. Compare every link with a known-good path when one exists.
  Add an alternative only after identifying the failed link and establishing that the existing component cannot satisfy
  the requirement.
- Give each type, method, module, and configuration artifact one focused responsibility; split responsibilities whose
  combined complexity prevents local understanding or independent change.
- Centralize shared behavior and repeated domain values behind the narrowest reusable abstraction; do not duplicate
  logic, constants, validation, or policy.
- Keep data flow explicit and correct across methods and layers: preserve required context, ownership, lifecycle, error,
  and result information without reconstructing, losing, or silently changing it.
- Define externally observable behavior before implementation details, including normal results, failure behavior,
  lifecycle effects, and boundary conditions.
- Provide tests that establish each required observable outcome and meaningful failure path. When designing or reviewing
  those tests, first lazy load `../include/common/testing.md` for the canonical test-boundary, isolation, and artifact
  guidance.

Before fixing dependent interfaces in a multi-component plan, identify the assumptions about external execution
capabilities or evidence that could invalidate those interfaces. For each, name the needed consumer-visible fact and
the earliest authentic check that can distinguish it. Use an existing authoritative result or run a bounded real-boundary
probe when that boundary is available; a configuration, mock, or successful start proves only the claim it observes.
When the check needs unfinished implementation, put it at the first runnable integration gate and keep dependent claims
conditional until it passes. When the evidence comes from an independently owned host, process, or service, lazy load
`../include/common/design-feasibility.md` before accepting the dependent design.

Before accepting a design or implementation plan with multiple components, map each required outcome and acceptance gate
to its responsible component and observable evidence. Give each required cross-component handoff one authoritative
declaration of its produced result, readiness point, consuming decision, identity and authority, lifecycle and failure
or resumption behavior, and observable proof. A shared API or schema may be that declaration; do not require a separate
table when it already supplies these decisions. Other component descriptions must refer to the same declaration rather
than define competing variants. Trace use-cases that collectively cover those handoffs, including success and
contract-relevant failure or resumption. At each handoff, verify that the producer can supply its result before the
consumer needs it and that both agree with the authoritative declaration. Resolve a mismatch in the responsible
component's design before handoff. A selected use-case does not establish complete obligation coverage, and separately
verified components do not establish compatible handoffs. Where failure can occur between production and the consumer's
first observation, trace ownership and resumption across that interval and assign a check that induces failure there;
a check of a later observation cannot prove that earlier path.
Trace each input needed for a component's promised result backward through indirect references, derived records, and
other components until reaching a producer or independently available input. Check the resulting implementation
dependencies against the proposed cluster order. If a producer requires its consumer's cluster to finish first, leave
that behavior unassigned until the plan supplies a verified earlier producer, splits the clusters at a runnable
boundary, or changes the internal route without changing the required outcome. This check concerns implementation
prerequisites, not an intended runtime retry or resumption loop.
When permitted intervening transitions can change how a later consumer obtains required earlier evidence, trace the
contract-relevant sequence through the consumer's first use, including the combination of those transitions. Name the
durable identity and retrieval and validation path available to the consumer after that sequence, and show how each
reference-changing transition preserves or reconstructs that path. If the required evidence is no longer retrievable,
keep that handoff unresolved. Assign a fault-and-restart check spanning the
reference change and first use; separate checks of individual transitions do not prove their combined recovery path.

Before marking an implementation component complete, compare its assigned obligations and handoffs with the current
production entry points, durable data, failure and restart paths, and tests that exercise those boundaries. Inventory
reachable bypasses and absent fields or transitions even when the declared focused and combined checks pass. Record
which obligations the evidence proves; leave the component open for any unmet obligation without invalidating unrelated
verified work. A helper test or a green build does not establish a caller-facing result it never exercises.

Before launching a plan-review wave, identify the evidence each design decision depends on. Run all known, in-scope
checks that can resolve those decisions now, reconcile their results and known findings, and consolidate the
authoritative plan before reviewing the affected scopes. Review independent scopes concurrently when their determining
inputs are ready; do not wait for unrelated checks. Freeze each scope's determining inputs during its review. Batch
findings from the wave into one consolidation before re-review. If a review discovers a missing check, run it and
re-review only the scopes whose decisions its result changes; retain receipts for scopes with unchanged determining
inputs. A retained review receipt must identify its reviewed inputs and show a verifiable comparison of the inputs its
findings depended on; otherwise rerun that scope. Obtain a fresh whole-plan review of the consolidated snapshot when
the plan's acceptance contract requires one. Keep the current implementation decisions in one executable path; retain
prior reviews and superseded decisions only as clearly marked provenance, not as alternative instructions. Do not
delay plan review for evidence that only implementation or a later gate can produce. Implement ready, independent
clusters with focused checks. Release a dependent writer only from a source snapshot that contains its integrated
predecessor, compiles at their shared boundary, and has current predecessor-focused evidence. While that snapshot is
unavailable, advance only work whose tests can run independently; a test that cannot compile is not a behavioral red
result. Check the combined handoff at the first runnable integration gate before accepting the dependent behavior.

When designing or executing a multi-responsibility refactoring, lazy load
`design-quality/refactoring.md` for its dependency-closure, parallel-work, integration, and wave-verification procedure.

## Shared Review

Implementers must apply the relevant requirements while designing and coding.

Design reviewers must assess the same requirements, identify missing outcomes, contradictions, or routing conflicts, and
distinguish one-off implementation defects from gaps in this rule or related canonical rules.

When a recurring review finding exposes a missing, ambiguous, or conflicting standard, update the applicable canonical
rule and apply the corrected standard where it is already in scope.
