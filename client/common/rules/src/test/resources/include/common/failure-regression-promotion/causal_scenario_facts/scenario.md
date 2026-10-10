# Causal scenario facts

## Variables

| Name | Description |
|---|---|
| ticket | The ticket number that tracks the job-adapter report, such as `BUG-219` |

## Scenario

Failure Prevention analysis has confirmed three reproducible failures in user-owned prompt or workflow guidance. An
agent is promoting them into reusable regression scenarios. For each, decide which incident details belong in the
scenario and what the scenario must check.

1. During work item `Q7`, an agent saw no dedicated skill file for a runner and proposed a new skill without checking
   whether the installed workflow could call its existing runner launcher. `Q7` identifies the planning row only.
2. In ticket `{{ticket}}`, an agent proposed a new job adapter because no class had that name. A maintained facade already
   exposes the needed submit and observe operations. The ticket number is used only to track the report.
3. A parser must accept header `X-Run-ID` and reject `x-run-id`; an agent wrote a test that changed the input header's
   case and therefore missed the required distinction. The exact header forms determine the behavior.

## Assertions

### reported_case

kind: effect

The runner regression keeps the missing-file observation, existing callable route, premature new-skill decision, and
the check of the installed route before a new boundary. The reusable scenario does not require the incidental `Q7`
label to make that decision.

### analogous_case

kind: effect

The facade regression keeps the missing-class observation, existing submit/observe operations, and the check of their
combined result before a new adapter. It does not make the tracking number part of the decision or acceptance.

### literal_input_counterexample

kind: effect

The parser regression preserves both exact header forms because changing their case changes the required outcome. It
does not apply the label-omission rule to a literal input whose spelling is the tested condition.

### guidance_selection

kind: effect

The retained events establish that the executor selects `rules/include/common/failure-regression-promotion.md` before
its first governed action.
