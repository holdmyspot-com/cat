# Limitations and Workarounds

## Design Goals

- Evaluate the owning workflow before treating an absent condition or helper interface as an outcome boundary, then
  select the smallest evidence-supported feasible path without assuming unavailable authority and with reversal or
  removal where applicable.
- Distinguish an external blocker from unfinished, difficult, or high-volume authorized work, so a workflow does not
  stop while its next supported action remains available.

## Guidance

When a required condition appears absent, do not infer that it is permanently unavailable from its current absence.
Before describing a limitation, moving to a workaround, or asking the user to accept a degraded outcome, work backward
from the required observable outcome and classify the condition using available evidence:

1. It is currently absent but can be created, configured, provisioned, published, enabled, or integrated within the
   task's authorized scope.
2. It is currently absent and requires an external action, authority, dependency, or constraint that concretely blocks
   providing it within that scope.
3. It is genuinely unavailable for the requested outcome because objective evidence establishes an inherent or
   applicable external limitation.

For the first classification, include providing the condition as a candidate path; do not treat its absence as a reason
to exclude it. For the second and third classifications, identify the concrete blocking evidence and its effect on the
acceptance conditions. Do not label a condition impossible merely because the required artifact, configuration,
capability, integration, or prerequisite is not yet present.

An external blocker is a required authority, decision, system change, or unavailable input that the current actor cannot
obtain through its remaining authorized actions. Before reporting one, name the missing condition, the action that would
obtain it, and evidence that the action is unavailable. Do not call incomplete implementation, unreviewed batches, a
failed or unrun check, uncertainty, or the amount of remaining work a blocker; continue from the next recorded,
tool-supported action.

Do not treat the argument shape, output, or apparent narrowness of one helper, launcher, API, or CLI as the boundary of
the capability the user requested. First identify the workflow, skill, service, or other component responsible for the
observable outcome. Inspect its documented orchestration and supported paths, then distinguish the helper's mechanical
operation from the complete workflow. A helper can implement one step of a broader supported capability; only explicit
evidence that no broader path exists may establish the helper as the capability boundary.

Before declaring a capability unavailable or selecting a workaround, record the requested outcome, the component that
owns its workflow, each supported path, and the evidence for every excluded path. Compare those paths against the
outcome and required conditions. Apply this check equally to local launchers, APIs, plugin commands, build tools, and
external integrations.

Compare the feasible paths explicitly: providing the missing condition, using an existing supported path, and using a
workaround when one is necessary. Evaluate each against the observable outcome, acceptance evidence, prerequisites, user
authority, operational burden, reversibility, and concrete constraints. Select the smallest evidence-supported path that
achieves the requested outcome. Revisit excluded paths when new evidence changes their prerequisites or constraints.

Do not require speculative expansion, publication, provisioning, or integration outside the user's authority or the
task's stated scope. When a feasible path needs such authority, identify it as a decision or external dependency rather
than silently assuming permission or substituting a workaround. A workaround is justified only when the preferred path
is outside scope, cannot satisfy its prerequisites, or is concretely blocked; retain the evidence for that conclusion.
When a workaround remains necessary, design its user-facing flow to meet the requested outcome with the fewest necessary
steps and preserve an equivalent removal or reversal path where applicable.

Treat distinct ways to satisfy a condition as explicit options. Choose an option using the available evidence and
constraints. If its prerequisites cannot be satisfied, revisit the alternatives instead of forcing the selected path.
