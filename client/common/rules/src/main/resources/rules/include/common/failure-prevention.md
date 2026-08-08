# Failure Analysis and Prevention

## Design Goals

- Classify a reported or observed failure from its complete available chronological record—including any recorded decision
  explanation—while separating that evidence from unavailable private reasoning or platform input; isolate its earliest
  controllable cause and correct it rather than masking a downstream symptom.
- Guard each reproducible, correctable failure with the smallest executable check of the claimed behavior, so the same
  defect cannot silently return.
- Derive prevention controls from the failure predicate, verify them against the reported failure and meaningful
  variants, and retain only the smallest control that provides the required coverage.
- Distinguish an unavailable prerequisite from a failure or unknown result while acquiring a known prerequisite, so a
  fallback cannot silently replace a required capability.
- Converge recurring or high-impact failure decisions through independently challengeable, evidence-backed acceptance
  conditions.
- Prevent a retry from concealing an incomplete or unexpectedly ended workflow, by requiring its cause and any
  controllable correction to be established before replacement work begins.
- Preserve supported behavior while correcting an unexpected warning, error, or diagnostic, so a controllable cause is
  fixed rather than hidden by suppression or feature removal.
- Diagnose a claimed instruction violation against the product the instruction governs, so an intermediate description
  is not mistaken for a prohibited action unless reader-visible language is itself the required product.
- Make progress in an iterative workflow monotonic: retain resolved acceptance conditions across rounds, so a later
  candidate cannot appear to improve while reintroducing a failure that an earlier candidate had removed.

## Guidance

Apply the foundational Backward Design guidance injected by the default rule to derive the earliest controllable
prevention.

Use the Backward Design and Iterative Generalization rule to derive the outcome, acceptance evidence, conditions, and
scope of a prevention. This rule adds the failure-specific decisions: identify why the governing process allowed the
failure, correct that cause, and prove that the correction works.

Before recommending a prevention, inventory every rule source that can govern the current context and follow its
routing, includes, overrides, and harness layers. Then classify the cause: a missing requirement, conflicting
requirements, ambiguous wording or routing, a rule missed through an incomplete source search, or failure to apply an
adequate existing requirement. Recommend the smallest correction for that cause. Add or strengthen a rule only when the
inventoried rules leave an observable requirement gap; repair the specific rule or route when they conflict or are
ambiguous; improve application or coverage procedures when the requirement was already adequate.

When diagnosing or improving a workflow from output produced over two or more rounds, first determine whether its
progress is monotonic. Give each observed unmet acceptance condition a stable signature made from the affected case,
observable boundary, and contradiction; compare the signatures and terminal outcomes of each later round with every
earlier candidate that established that condition. A different reviewer explanation does not make the returning
condition new. If a later round reintroduces an earlier signature, leaves a previously satisfied condition untested, or
cannot compare the rounds because an input, execution environment, or acceptance contract changed, report that
non-monotonic progress before recommending a local repair. Include a convergence correction that keeps the last
candidate satisfying all known conditions as the baseline, checks every known condition before replacing it, and
records why a condition first appeared, disappeared, or returned. A stochastic workflow may still produce variable
trials; its candidate promotion is monotonic only when replacement requires the same retained regression check under
comparable conditions. A one-shot operation with no earlier candidate or repeated outcome is different: do not invent a
progress comparison for it.

Before proposing a rule or workflow edit, make an attribution record: the failing observable behavior; the chronological
decisions that produced it; the earliest decision that diverged from the required behavior; the available evidence for
that decision; and every plausible existing authority that could govern it. For each proposed edit, state its
counterfactual action: the specific earlier decision it would change, the instruction that would require that different
action, and the observable evidence that would show the action occurred. Reject an edit when it cannot change the
earliest divergent decision, changes only a later symptom or output, or rests on an unverified explanation of that
decision. In that case, investigate the unverified decision or classify the existing authority as an application or
coverage failure instead of changing an unrelated rule.

Before concluding that a recorded actor gave no reason for a decision, inspect the complete available sequence around
that decision: the governing instruction, its messages or other reader-visible explanations before the action, the
action and its result, and any later explanation. A message may give an explicit but incorrect interpretation of the
contract; report that interpretation and compare it with the governing instruction. Do not call the decision unexplained
merely because the record lacks private reasoning, and do not treat an explicit explanation as proof that the actor
actually relied on it. When the record contains neither an explanation nor evidence that the available sequence was
examined, report the reason as unobserved and name the checked scope.

Before classifying an agent or reviewer result as noncompliant, identify the product the governing instruction protects.
For an outcome-control instruction, name the protected state and the concrete actions that change it. Compare the event
order, changed artifacts, and resulting behavior with that boundary. Do not treat a provisional label, plan, or
explanation as a violation when the same record shows the action remained under evaluation. For an output-control
instruction—such as a required response, diagnostic, comment, label, or documentation form—the reader-visible language
is the product, so check that language directly. If one term can describe both an allowed intermediate state and a
forbidden final state, resolve the ambiguity before blaming the actor: define the states, reserve the final-state term
for the verified boundary, and test the action that changes state. For example, installing a parser to run a probe is
evaluation work; editing production code to call that parser before the probe completes changes normal production
behavior. A rule may intentionally make earlier wording a boundary only when its required reader-visible language is
the product; state that purpose and test it explicitly.

When work exposes an unexpected failure, discrepancy, or violated requirement, start this analysis without waiting for
the user to point it out. Pause only the dependent work: first state the action that was expected to succeed, the
observed result, why it prevents the intended outcome, and the next discriminating check. Inspect the available record
and classify whether the result is an expected negative test, a product behavior failure, an infrastructure failure, or
an error in the workflow that produced the result. For a controllable workflow error, repair its earliest owning rule
or tool and prove the repair before retrying, publishing, or continuing the dependent workflow. For a product behavior
failure, use the established result to select the appropriate product or prompt owner; do not silently turn it into a
workflow repair. An expected failing test that demonstrates its intended guard remains evidence, not an incident to
repair. This does not authorize unrelated changes: report or request direction when the needed owner is outside the
authorized scope.

Treat suppressing a warning, ignoring a result, disabling a check or feature, or hiding diagnostic output as containment,
not a correction. Before using containment, trace whether the underlying behavior has an in-scope, controllable owner
and attempt the earliest source correction that preserves the supported behavior. Verify that correction at the affected
boundary: it must prevent the unwanted result while the capability the containment would have removed still works. Use
containment only when the source cannot safely be corrected with available authority or the user explicitly chooses that
trade-off; record the limiting evidence and retain the observable diagnostic when doing so. For example, do not disable
runtime performance data merely to hide a shared-storage warning when the process-launch boundary can instead give each
runtime its own storage.

Treat a coarse error category, status code, timeout, or failure count as a symptom, not its cause. Before selecting a
mitigation, identify the failing component and operation, then inspect its retained diagnostic events, error stream,
and documented meaning. Separate each cause the coarse signal could represent, including causes specific to the
component, route, model, or service tier that handled the operation. Test or eliminate the applicable causes in causal
order. For example, concurrent HTTP 403 responses can reflect credentials, an account policy, a Codex load-balancer
constraint, or exhausted response capacity; do not reduce concurrency until the diagnostic identifies the capacity
constraint that the change would address. Likewise, an installation failure may be a registry outage, a package
conflict, or a permission denial; it does not by itself authorize changing the dependency choice. An explicit diagnostic
that names one cause may support the corresponding mitigation, while a task with no detailed diagnostic remains an
investigation rather than a configuration decision. Record the component and operation, the code, the detailed
diagnostic, the rejected hypotheses, and the evidence linking the selected mitigation to the confirmed cause.

When an agent or delegated reviewer fails to follow a rule, inspect the actual instructions delivered to that recipient
before changing the rule or prompt. Verify that the intended rule was loaded into that agent's context in its rendered
form and position, then identify any conflicting instruction, malformed include, wrong audience route, or competing
priority. Record each relevant instruction's recipient, role, order, occurrence count, and rendered text. Presence does
not prove application: later task-adjacent delivery can make an otherwise identical instruction more likely to guide the
response without overriding an earlier instruction. Isolate that effect by varying only delivery position in a controlled
reproduction. Repair the first faulty delivery or routing boundary; do not infer prompt noncompliance from the final
response alone.

When an agent's observable action or response violates an intended instruction and private reasoning or complete
platform input is unavailable, diagnose it as a black box. First define the failing observable behavior and the
acceptance check for the required behavior. Preserve a decision-debugging record containing the model, version,
settings, recipient, execution mode, exact task, every available governing instruction's rendered text or hash, role,
order, and occurrence count, relevant hook and tool events, exact raw output, deterministic validation result, and each
known unavailable input. Classify each diagnostic statement as an observation, an inference, or an unresolved unknown.

When a diagnosis relies on an observation whose producer can still advance it, treat the observation as provisional.
This includes a nonterminal workflow record such as `unclaimed`, `claimed`, `running`, or `publishing`, a partial
output, and a read before a background worker commits its result. Before reporting a failure, inspect the producer's
terminal record and liveness, then reread the observation after a causal boundary: the producer exits, the observation
changes, or its documented lease or deadline expires. Report the state as provisional when none of those boundaries can
be observed. A missing process or one nonterminal snapshot does not establish failure: the producer may have handed off
its result, be committing the next record, or be observed between transitions. An immutable completed artifact, a
terminal `failed` or `interrupted` record, or an expired deadline with no permitted successor establishes the outcome
only when its evidence identifies the affected work unit and reason.

When a maintained workflow, tool session, or worker ends without the terminal result it promised, stop before retrying
it. First establish what was expected to finish, what actually stopped or remained incomplete, and why that prevents
the workflow from proving its result. Inspect the retained record, process or job liveness, exit status or signal,
standard output and error, and the caller or host session that owned the operation. Then classify the result as a
recorded terminal failure, an incomplete record whose owner may still finish, or an unexpected termination. Do not
treat a missing process plus a `RUNNING` record as a harmless stale result: it means the required terminal transition is
unproven.

For an unexpected termination, identify the earliest owner that could have preserved a terminal failure result—for
example, the launcher that lost the exit status, the coordinator that did not record interruption, or the caller that
released its session. If the owner and correction are reproducible, add the smallest check that fails when the promised
terminal result is absent, repair that owner, and verify the check before starting a replacement run. If the retained
evidence cannot distinguish the cause, preserve it, state the unresolved alternatives and the next discriminating
check, and do not use the incomplete run as outcome evidence. Restart only after the prior run is classified and its
controllable cause is repaired or explicitly shown to be outside the workflow's authority. A normally recorded terminal
test failure is different: use its established result to revise the tested behavior instead of treating it as an
infrastructure incident.

Before replacing an interrupted evaluation, determine whether any input, worker result, or externally visible action
was already recorded or exposed. Treat that evidence as part of the interrupted evaluation rather than as permission to
start a fresh population. Repair the earliest lifecycle owner, then resume the retained identity with only its
unrecorded work. Start a replacement identity only after the prior evaluation reaches a terminal supersession record
that names the changed determining input and why resumption cannot answer the same question.

Do not ask for or rely on private chain-of-thought, think-aloud narration, or the agent's explanation of its own
decision. Such narration may be unavailable or change the behavior under investigation, and is not evidence of the
cause. A retrieval probe, forced choice, canary, accessed-file trace, or required output marker establishes only the
observable behavior it elicits.

When an output selects a fallback after identifying a required external facility, reconstruct the decision in source
order. Record the rendered requirement that orders the primary and fallback choices; the event that selected a suitable
facility; every discovery, cache, registry, installation, permission, and timeout result; the first output action that
uses the fallback; and the validator's independent result. Classify a missing local copy, a failed acquisition, and an
unknown acquisition result separately from evidence that no suitable facility exists. Do not infer ecosystem absence
from any of the first three. Compare the executed artifact's rule hash with source before ruling out stale routing, and
compare the captured tool events before claiming tools or discovery were unavailable. The earliest verified cause is
the first observable fallback choice that the recorded requirement does not permit; name any conflicting later rule
text separately instead of attributing an unobserved reason to the agent.

For this kind of failure, make the recovery test reproducible: hold the task, fixture, model, settings, rendered rule
hashes, and acquisition condition fixed, then vary only one suspected cause. Test a reconciled primary/fallback rule
with acquisition blocked, and test the unchanged rule with the selected dependency made available. A valid blocked case
reports the dependency-acquisition failure without producing the forbidden fallback; a valid available case uses the
selected facility. Treat a downstream validator as evidence of the output predicate only: verify that its accepted
names, imports, and patterns cover the generated form before using its failure category as an explanation.

Reproduce the failure, then vary one controllable factor at a time while keeping the fixture, task outcome, model,
settings, and other delivered instructions fixed. Test instruction presence, wording or reference, recipient, role,
order, occurrence, and direct versus delegated delivery when relevant. For variable output, define the acceptance
threshold before testing and repeat the control and candidate under the same conditions. A difference supports a
conclusion about the changed factor only; it does not reveal private reasoning or prove the contents or effect of an
unobserved platform instruction.

Repair the earliest verified controllable input or delivery boundary. Verify the reported failure, a meaningful
analogous case, and a relevant dissimilar case. If no controlled variant isolates the cause, report the visibility limit
and next discriminating test instead of assigning the cause to a hidden prompt, model prior, or intermediate component.

When diagnosing a delegated response with the wrong required form, first lazy load `./delegation.md`.

When a failure involves generated, packaged, cached, installed, or otherwise derived behavior, identify the exact
artifact executed at the relevant boundary and compare its relevant content or hash with the source under review.
Rebuild, reinstall, or recapture before drawing conclusions when they differ. When that rebuild occurs inside a process
that already loaded the old artifact, do not use that process's later output as evidence for the rebuilt behavior: it
continues to execute the code it loaded at startup. Start a new consumer from the rebuilt artifact, retain the rebuilt
artifact identity with that consumer's result, and use only that result to decide whether the rebuild corrected the
failure. This applies equally to a CLI that rebuilds itself, a server that reloads a package, and a worker that updates
its own plugin; a source timestamp or successful build alone does not establish which version produced a later result.

When a required instruction, security control, access boundary, or other governing constraint appears to prevent
progress, keep it in force while tracing its authoritative delivery path and each boundary it crosses. Repair the first
verified broken boundary with the narrowest authority that restores the required behavior. Do not remove or weaken the
constraint, or add a duplicate source, injected copy, fallback, or compatibility path, unless evidence proves the
authoritative path cannot satisfy the requirement and the user explicitly authorizes the changed or additional contract.
When temporarily repeating an authoritative instruction to diagnose delivery, mark that copy diagnostic-only. Do not
retain it as production guidance; use a passing result to identify the missing delivery boundary, then restore one
authoritative source through that boundary.

When creating, regenerating, or revising an artifact preserves existing content but omits coverage that should follow
from a declared goal or contract, classify the cause as a derivation-coverage failure, not as proof that the goal,
contract, or prior content was necessarily wrong. Correct the creation or revision procedure so it derives and checks
coverage from each declared outcome independently of reported failures or existing examples, then verify the omitted
outcome and an analogous case.

Prevent a mistake at its earliest controllable cause. Do not substitute a downstream detector, reviewer, checklist,
warning, retry, or compensating workaround for an omitted required design or execution step. First change the governing
procedure so it explicitly performs the missed step at the decision where the failure originates, then verify that this
source correction prevents the reported failure and its meaningful variants. Add a downstream guard only when evidence
shows it reinforces the source correction without replacing it; explain the independent failure mode it covers. If the
governing procedure already requires the missed step, treat the failure as an application-compliance problem and revise
the application procedure or its coverage—not the surrounding symptom.

Before designing that correction, derive the failure predicate and generalize it iteratively. Start with the smallest
observable failure, compare the next meaningful similar and dissimilar cases, and retain only the invariant that
explains all included cases without imposing unrelated obligations. Design and evaluate prevention controls against the
stabilized problem, not against the original example or its surface details. Recheck that the chosen control prevents
the reported failure and the included variants.

When the failure has a reproducible observable boundary, write or revise the smallest behavior-level regression check
before changing the correction when practical. The check must fail for the reported behavior and pass only when the
claimed outcome occurs. If the correction already exists, promptly demonstrate the same distinction against a controlled
reproduction or the known defective revision. Exercise the operation that produces the claimed outcome; do not replace
that check with assertions about source text, generated files, or third-party behavior. When no executable boundary is
available, record the specific limitation, the closest available evidence, and why it cannot provide the regression
check. Keep the check with the correction and run it before broader verification.

## Adversarial Review and Evidence-Led Convergence

For a recurring or high-impact failure in a workflow, rule, security boundary, or irreversible operation, use an
independent adversarial reviewer before selecting a solution. Give the reviewer only the goal, acceptance evidence,
failure history, governing artifacts, candidate solutions, and their stated evidence—not the proposing agent's private
reasoning. Ask it to falsify each candidate by identifying bypasses, unchecked assumptions, boundary cases, and failures
that rely on the same behavior that previously failed.

When a finite evidence ledger is necessary, create it in the decision or review artifact produced by this workflow and
name where the next required reader retrieves it. When a later run or another actor must use the ledger, lazy load
`./monotonic-progress.md`, then write and commit it as a named repository artifact with a stated retrieval step;
otherwise treat it as a current-run artifact and require the workflow to recreate it. Each item must name one acceptance
condition, the candidate or implementation that covers it, objective evidence, and a status: open, resolved, or
explicitly out of scope. A reviewer finding may reopen or add work only when it supplies evidence of an unguarded
acceptance condition or a relevant counterexample; do not reopen work for preference, speculative alternatives, or
already-rejected scope.

Revise only to resolve ledger items. Each revision must close at least one open item or replace it with a strictly more
specific evidence-backed item; deduplicate equivalent findings and do not reintroduce resolved items without new
evidence. Re-review only changed candidates and unresolved findings.

Reach a fixed point when every ledger item is resolved or explicitly out of scope, the adversarial reviewer finds no new
evidence-backed gap, and the reported failure remains a passing regression check. Select the smallest candidate that
reaches this fixed point and provides observable coverage for every acceptance condition.
