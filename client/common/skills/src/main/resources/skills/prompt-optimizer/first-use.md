# Prompt Optimizer

## Design Goals

- Turn an observed workflow failure into a verified, reusable improvement instead of an untested suggestion.
- Preserve the independence of the observed run while making every finding and prevention goal auditable.
- Stop only when a fresh retest supplies failure-capable evidence that the identified mistakes no longer occur.
- Route every prompt-artifact change to `prompt-builder`, which owns prompt design, organization, revision, validation,
  assembly, and certification.
- Prefer an upstream behavior-producing correction at the earliest mistaken decision over a later rejection, retry, or
  compensating guard; retain a downstream guard only for a distinct remaining failure mode.
- Detect and prevent completion claims that lack a passed or explicitly excluded record for every required terminal
  gate, even when related intermediate checks succeeded.
- Prevent a topic review from treating internal implementation artifacts as a user or developer feature. A topic counts
  only when its claimed behavior is reachable through a supported command, hook, installed-plugin selection, build,
  release, or other documented developer interface. For an agent or skill whose claim is availability, a host-native
  named availability or selection check is sufficient; executing a model task is required only when the claim concerns
  the task's generated behavior.

Use this skill when the user asks to observe an agent or workflow, identify its mistakes without steering it, derive
prevention goals, route the correction to its authoritative owner, and retest the result. Use it for a bounded,
repeatable process improvement; do not use it for a one-off implementation task that has no observed failure pattern.

## Loop

1. State the scope, the terminal observable outcome, acceptance evidence, every required terminal gate, and the
   non-interference boundary. Record each gate as passed, failed, unrun, or explicitly excluded; a related check does
   not substitute for an unrun or failed gate. Use the selected harness's `workflow-temp create prompt-optimizer`
   launcher
   to create the fresh invocation-local evidence directory, and use its returned `directory` value; do not create or
   select that directory in the prompt or place it in the repository, `.cat`, an installation, or a cache. At the
   terminal boundary, invoke `workflow-temp handoff <directory> <retained-directory-under-cat-work-temp>` when a
   later
   workflow needs its uncommitted evidence; otherwise invoke `workflow-temp delete <directory>`. Record the later
   workflow as the cleanup owner, then delete the retained evidence after that workflow reaches its terminal result.
2. Delegate or run one representative workflow execution. Before every material action, require a short narration of the
   action, prerequisites, execution context, inputs, output channel, created artifacts, and expected evidence. Observe
   it without correction. Record both the intended action and the actual result.
3. After the run completes, classify every mistake separately: violated requirement, triggering decision, observable
   consequence, and evidence. Treat reporting completion while a required terminal gate is failed or unrun as its own
   mistake, even if related checks passed. Include process mistakes such as leaked reviewer context, wrong working
   directory, incorrect output assumptions, invalid validation order, unsafe artifact creation, and fail-open checks.
4. Work backward from the desired final evidence. For each mistake, write a prevention goal in this form: “The workflow
   is complete only when [observable condition], proven by [failure-capable evidence].” Decide whether the goal belongs
   in the workflow, a validator, a test, or a project rule. Do not add repository guidance that merely restates general
   engineering knowledge. Identify the earliest decision that should have produced the missing behavior. Make that
   decision produce the required evidence or action up front; use a later rejection only when it protects a distinct
   failure mode that the upstream correction cannot prevent. When the mistake is a composite candidate that hides
   separately selectable actions, the prevention evidence must include a complete-looking mixed-family input that the
   validator rejects; prose requiring a narrower topic is not sufficient. When the mistake is a topic made only of
   internal classes, tests, or packaging helpers, require the candidate to name the supported interface that reaches the
   claimed result. Reject it as a standalone feature when no such interface invokes it. For an agent or skill that
   claims only availability, accept a host-native named availability or selection check; do not require a live model
   task unless the candidate claims the model-generated result.
5. Send each correction to its authoritative owner. When it changes a prompt artifact—including a skill, rule, or agent
   instruction—apply `prompt-builder`; do not author, organize, validate, assemble, or certify the prompt here. Hand it
   only the retained observed-run evidence, failure classification, earliest mistaken decision, prevention goals,
   affected artifact scope, and required terminal gates. Hand off the retained evidence with `prompt-builder` as its
   recorded later consumer and cleanup owner. `prompt-builder` returns the revised artifact, its Design-Goal and
   ownership audit, deterministic-check results, assembled/build evidence, formal-certification result, and any blocker.
   Preserve existing safety constraints. For a delegated review, use an allowlisted handoff: exact permitted inputs and
   destinations, no expected result, count, names, plan, or prior-artifact discovery. Require behavior-first discovery
   before source provenance, and require validations to fail closed.
6. Record the authoritative owner's returned evidence and any blocker. Do not repeat `prompt-builder`'s authoring,
   validation, assembly, or certification procedure. Run only the optimizer's fresh independent retest and terminal-gate
   audit. For a retest that may outlast one tool result, start its synchronous command in the available PTY-capable
   interface, retain the returned session identifier, and poll that same session until its terminal result is available.
7. Retest with fresh inputs and an independent reviewer or executor. Do not pass the earlier conclusion, suspected bug,
   intended fix, or expected outcome. The retest must exercise every previously failed decision, preserve its trace, and
   produce a terminal-gate audit showing that every required gate passed or was explicitly excluded. If it finds a new
   mistake, return to step 3; do not declare success because earlier mistakes were fixed.
8. Finish only when every observed mistake has either a passing retest or an explicit external blocker. Before reporting
   a blocker, lazy load and apply `rules/include/common/limitations-and-workarounds.md`. Report the evidence location,
   prevention goals, changed artifacts, verification commands, retest result, and any retained limitation. Do not
   rewrite history, delete evidence, or make external changes unless separately authorized.

## Review Checklist

- Was every material action narrated before execution, not retroactively?
- Did the observer avoid steering the run until it completed?
- Does the observed run and retest record every required terminal gate, with no failed or unrun gate treated as
  completion?
- Did the independent reviewer receive only an allowlisted, source-neutral handoff?
- Does each prevention goal name a concrete completion condition and evidence?
- Does every candidate claim a result reachable through a supported interface rather than the existence of internal
  classes, tests, or packaging helpers? For availability-only agents and skills, does its evidence use a host-native
  availability or selection check instead of treating source inspection as proof?
- For every revised prompt artifact, does the `prompt-builder` handoff and return record show its Design-Goal audit,
  ownership decision, deterministic checks, assembly/build validation, and formal certification?
- Does the correction cause the desired behavior at the earliest mistaken decision rather than merely rejecting it
  later?
- Did the retest run in the recorded working context and propagate validation failures?
- Did the final retest cover both the original failure and new failure modes introduced by the fix?
