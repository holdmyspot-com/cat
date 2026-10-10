# Shared capability

## Variables

| Name | Description |
|---|---|
| callers | The two importers that share the filter, such as `a command-line importer and a scheduled importer` |

## Scenario

Create an implementation plan for AI agents correcting a duplicate filter shared by {{callers}}. The accepted existing API contract requires suppressing a repeated stable activity ID while retaining two distinct IDs even when their payloads are identical. Both callers already supply the verified stable IDs; the filter currently compares payloads instead. The shared library has a runnable focused unit-test harness, and each caller has a runnable integration check. No interface, user workflow, deployment, or external provider contract changes are needed. Existing tests pass but omit the two-distinct-IDs/same-payload case. State the work assignments, edit-and-check order, and completion evidence. Remain read-only: do not implement, run tests, or delegate.

## Assertions

### observable_decisions

kind: effect

Uses the established ID contract and permits one shared-filter assignment; retains both callers' integration evidence. Schedules a focused behavioral failure for distinct IDs with equal payloads before the implementation correction, then passing focused and relevant broader checks. Keeps repeated-ID suppression covered. Does not require a journey document, duplicate capability implementations, or a new provider probe.

### planning_guidance_before_decisions

kind: effect

The retained events establish a successful read of `rules/include/common/implementation-planning.md` before the first
project inspection or decision about behavior coverage, assignments, dependencies, or checks. A failed lookup,
substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately from guidance
selection.

### test_first_and_project_verification

kind: effect

Before fixing production-edit order, the executor successfully reads `rules/include/common/tdd.md` and schedules
complete-project verification after focused and integration checks and before implementation handoff. It does not claim
planned checks ran, count a compilation failure as behavioral red evidence, or invent a project command. Guidance
selection and the scheduled acceptance checks are assessed separately.

### evidence_and_scope

kind: effect

Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept alternative decompositions
whose stated dependencies and checks satisfy the request; do not grade by literal phrases. Keep guidance selection or
delivery unverified when the retained events do not establish it.
