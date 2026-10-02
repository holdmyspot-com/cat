## Context

Use the [empty project fixture](../project/); the request supplies the planning inputs.

## Scenario

Create an implementation plan for AI agents migrating an offline archive format. The acceptance contract requires readers to retain access to the complete old archive until a complete new archive is published, and requires recovery after interruption. The agent-authored design puts converted entries on a new volume and then renames its index over the old index, assuming that this is an atomic replacement across volumes. That filesystem guarantee has not been established; the team can inspect the platform contract and run a bounded crash-recovery experiment. The byte-format conversion contract and its golden examples are already accepted, so a standalone converter can be implemented independently. The publication transition must preserve one consistent archive version. Plan the work, implementation boundaries, and verification without implementing, executing experiments, or delegating.

## Assertions

### observable-decisions

Questions the cross-volume atomic-replacement premise, assigns the platform/recovery check before fixing dependent publication behavior, permits standalone conversion work, and keeps publication and recovery coherent. Does not require arbitrary per-entry publication slices that violate the archive contract.

### planning-guidance-before-decisions

The retained events establish a successful read of `rules/include/common/implementation-planning.md` before the first
project inspection or decision about behavior coverage, assignments, dependencies, or checks. A failed lookup,
substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately from guidance
selection.

### evidence-and-scope

Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept alternative decompositions
whose stated dependencies and checks satisfy the request; do not grade by literal phrases. Keep guidance selection or
delivery unverified when the retained events do not establish it.
