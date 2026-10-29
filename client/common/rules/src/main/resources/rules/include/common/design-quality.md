# Design Quality

## Design Goals

- Keep designs focused, extensible at real variation points, and verifiable against observable outcomes.
<!-- In a feature-plan review, contract derivation needed source bytes that inspection had not retained. Another call
     handoff needed a claim before its producer supplied one. Defining each producer result and readiness point in one
     place exposes both timing gaps; the obligation inventory still covers requirements outside the traced use-cases. -->
- Let multi-component designs establish one authoritative contract for each required handoff and demonstrate compatible
	use-cases without treating selected use-cases as exhaustive contract coverage.

## Guidance

Use this rule as the canonical design standard for implementation and design review.

## Required Outcomes

A design must:

- Preserve codebase coherence: use established terminology, abstractions, module boundaries, and conventions unless a
	deliberate replacement improves them consistently.
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
	those tests, first lazy load `testing.md`.

Before accepting a costly guarantee or its enforcement, distinguish the user-requested outcome from any stronger
promise introduced by the design. Trace the stronger promise to explicit user authority or a necessary consequence of
the retained contract; wording in an implementation plan alone does not establish that authority. Compare the least
costly approaches that satisfy the requested outcome using their implementation and verification cost, evidence about
failure likelihood, impact, detectability, and recovery. State when likelihood is unknown. Prefer the simplest approach
that satisfies the retained contract; do not silently strengthen it or weaken a confirmed guarantee. When a stronger
promise materially changes what the user receives, present its benefit and extra cost for a scope decision before
implementing its enforcement. Instructions, measured compliance, detection, and prevention establish different claims;
do not treat them as equivalent. Establish the actual recovery boundary rather than assuming that a diff or undo covers
every affected resource.

Before accepting a design or implementation plan with multiple components, map each required outcome and acceptance gate
to its responsible component and observable evidence. Give each required cross-component handoff one authoritative
declaration of its produced result, readiness point, consuming decision, identity and authority, lifecycle and failure
or resumption behavior, and observable proof. A shared API or schema may be that declaration; do not require a separate
table when it already supplies these decisions. Other component descriptions must refer to the same declaration rather
than define competing variants. Trace use-cases that collectively cover those handoffs, including success and
contract-relevant failure or resumption. At each handoff, verify that the producer can supply its result before the
consumer needs it and that both agree with the authoritative declaration. Resolve a mismatch in the responsible
component's design before handoff. A selected use-case does not establish complete obligation coverage, and separately
verified components do not establish compatible handoffs.

When designing or executing a multi-responsibility refactoring, lazy load
`design-quality/refactoring.md`.

## Shared Review

Implementers must apply the relevant requirements while designing and coding.

Design reviewers must assess the same requirements, identify missing outcomes, contradictions, or routing conflicts, and
distinguish one-off implementation defects from gaps in this rule or related canonical rules.

When a recurring review finding exposes a missing, ambiguous, or conflicting standard, update the applicable canonical
rule and apply the corrected standard where it is already in scope.
