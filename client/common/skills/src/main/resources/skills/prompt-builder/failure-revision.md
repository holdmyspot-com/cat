# Failure Revision

## Design Goals

- Revise the earliest controllable decision that can prevent an evidenced prompt failure, while retaining downstream
  guards only for distinct remaining risks.
- Keep conclusions bounded by recorded evidence and make incomplete authority, dependency, or execution conditions
  visible as blockers rather than successful diagnoses.
- Classify prompt failures by their observable boundary so delivery, application, external prerequisite, and
  deterministic-tool failures receive their owning repair path.

Preserve the failed input, rendered prompt or installed artifact, actions, outputs, and validator result. Separate what
those artifacts prove from unobserved reasoning.

Classify the evidence before selecting a repair. A delivery failure means the required prompt was absent or malformed at
the reader boundary. An application failure means it was present but its required action did not occur. An external
prerequisite failure means the action cannot complete because a dependency, permission, or authority is unavailable.
A deterministic-tool failure means a reproducible checker, packager, launcher, or other project tool violates its
documented contract. Repair the identified owner; do not rewrite prompt guidance to compensate for a deterministic
tool or external prerequisite failure.

For a failed organic lazy-route case, do not infer an application failure merely because the task result is wrong. Read
the retained native events first. When selection is missing or unclear, generate the routing-only diagnostic from that
case under `${CAT_WORK}/temp`; it stops after rule selection. When selection occurred but application is unclear,
generate the application-only direct diagnostic; it delivers the expected rules before the unchanged task. Neither
diagnostic can
establish a compliance rate. Use its answer to repair the router, delivery boundary, or selected prompt before creating
a fresh organic certification run.

Identify the earliest controllable delivery or decision that could have produced the required behavior. Change that
owner first. A later validator, retry, or recovery guard is additional protection only when it covers a distinct
remaining failure mode.

When the candidate ledger records the same active failure signature in two consecutive checks, do not make another
ordinary prompt revision yet. Complete the workflow-owned repeated-failure review using the candidate state, latest
retained check, and a new review bundle under `${CAT_WORK}/temp`. Ask Sol-high to review only that sealed bundle. Its
`review-result.json` must address every returned signature key, select `restructure` or `no_prompt_change` for each,
and list the obligations preserved by that decision. The workflow records the completed review before the next candidate
check. The review is diagnostic evidence: it does not promote a candidate or contribute to compliance. Do not invoke
the workflow's preparation or recording steps as CLI commands.

Before writing a repair from a failed trial, classify each proposed prompt sentence as one of: the general reader
decision, an exact value required by an authoritative ordinary contract, or a test fixture detail. Add the general
decision to the prompt and keep fixture details in the durable task, fixture, or assertion. Do not add a value-level
example merely because it occurred in the failed trial: compare a same-decision case using different values and a case
where the exact value changes the required decision. Keep the value out of the prompt in the first case; retain it only
in the second case, when ordinary readers need the value to apply an authoritative contract. This prevents a repair from
passing one fixture by teaching the prompt that fixture's answer instead of its reusable decision.

Keep a prompt repair within its prompt and deterministic-tool boundary. It may revise the authoritative prompt file,
its documented route, or the CLI tooling that assembles, selects, or verifies that route. Do not add, enable,
repurpose, or modify a harness hook or event handler to deliver prompt content as a repair for a prompt failure. If
the existing delivery boundary cannot reach the earliest observable trigger, record that unsupported boundary and
repair the prompt route or its corresponding CLI tooling; do not bypass the missing route by adding a hook. A hook
change is in scope only when the user explicitly requests a change to that hook's own documented observable contract,
not because it can make a prompt failure disappear.

Rerun the affected test after the revision. If the evidence is incomplete because a tool, dependency, or authority
is unavailable, report that condition and the exact evidence still required; do not turn an inconclusive observation
into a successful diagnosis.

A permission denial, rejected tool call, timeout before a result, or missing result means that requested operation did
not execute. Record its check as unrun, state the authority or condition needed to run it, and do not infer a pass from
the command text, a neighboring inspection, or the desired output. A completed different check may be reported only
under its own name and cannot replace the unrun operation.
