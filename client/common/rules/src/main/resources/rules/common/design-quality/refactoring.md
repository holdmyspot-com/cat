---
depends-on:
  - ../design-quality.md
---
# Refactoring

## Design Goals

- Complete each coherent refactoring boundary efficiently by batching its related edits for a shared source or target
  file without merging unrelated responsibilities.
- Reduce refactoring wall time through dependency-closed parallel work and controlled integration without weakening
  design review or verification.

## Guidance

Before editing a multi-responsibility refactoring boundary, create an extraction manifest that names its complete
dependency closure: members, fields, nested types, callers, invariants, failure behavior, collaborators, focused tests,
target owner, and coordinator composition points. Produce independent manifests in parallel when their responsibilities
do not share a coordinator; use an authoritative symbol or reference inventory so reviewers do not rediscover the same
closure independently.

Parallel implementers working on an extracted owner must use isolated worktrees and change only that owner's target files
and focused tests. They must not edit a shared coordinator source. Assign one integrator to each shared coordinator
source; independent coordinators may have independent integrators. The integrator applies the target changes, removes the
complete dependency closure from the coordinator, and wires the resulting owners in one controlled integration.

During a refactoring, inventory the related edits that share a source file or a target file and serve the same reviewed
responsibility boundary. Apply the largest coherent dependency-closed batch for each shared file, then run its focused
evidence; do not serialize small moves merely to shrink a diff or turn. A one-member extraction must record why no
related member can move in the same batch. Split a batch only when its edits belong to distinct responsibility boundaries,
require incompatible intermediate contracts, or cannot share focused verification. In that case, record the boundary and
give each batch its own evidence.

Run focused tests for independent target owners concurrently in their isolated worktrees. After integrating a compatible
wave, run one combined focused selection and the complete affected module suite; do not repeatedly run unchanged Maven
selections after individual member moves.
