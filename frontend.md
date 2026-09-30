# Prompt Optimizer Product Contract

## Design Goals

- **G1 — Improve a prompt system:** produce a behaviorally stronger prompt-system candidate and leave every proposed
  content or structural change visible for ordinary Git review.
- **G2 — Let the user define compliance:** derive a complete, reviewable compliance contract from the prompt system,
  and do not optimize until the user confirms it.
- **G3 — Make a defensible prediction:** certify only when the evidence supports at least a 95% future pass rate under
  the frozen semantic evaluator with 95% confidence independently in every material use category.
- **G4 — Prevent benchmark leakage and invalid evidence:** separate development from formal certification, validate
  generated tests, preserve retry identity, and account for adaptive or correlated trials.
- **G5 — Report what was actually measured:** preserve results as point-in-time statistical observations, identify
  their material measurement conditions, and never present input fingerprints as a promise of reproducibility.
- **G6 — Preserve user control and durable progress:** allow interruption, resumption, revision, and cancellation
  without losing valid work or resetting a campaign’s statistical allowance. A new campaign has its own allowance.
- **G7 — Run through existing local agent infrastructure:** execute trials on the user's host through the current
  harness's existing runner skill in both installed harnesses, Codex and Claude, including prompt systems that use
  arbitrary host tools.

## Product Outcome

The user selects a prompt file or prompt system in a base Git worktree. A new campaign requires that base worktree to
be clean: if CAT finds staged, unstaged, or untracked changes, it refuses to fork or start trials and asks the user to
resolve those changes (for example, by committing, stashing, or removing them) before retrying. CAT then forks the
clean worktree into an optimization worktree. CAT derives the behavior that the prompt system is supposed to enforce,
presents the complete proposed compliance contract, and waits for the user to confirm or revise it. CAT develops
candidates in the optimization worktree and formally tests its frozen candidate in isolated trial
copies, then returns one of these outcomes:

- `certified`, with the applicable statistical claim, supporting trial records, and a separate acceptance decision;
- `not certified: below minimum` or `not certified: inconclusive`, with the evidence and available next actions;
- `not certified: plateau`, with the unresolved categories and development evidence;
- `certification unavailable: unsupported instruction source`, when known remote instruction text governs the agent;
- `certification unavailable: ineligible trial effect`, when a required trial path would write persistent state outside
  the trial copy or lacks confirmed read-only service credentials or a disposable fixture;
- `certification unavailable: unsupported output`, when a material requirement produces image, audio, or video output;
- `certification unavailable: unsupported test interaction`, when a material obligation needs a response-contingent
  follow-up that the confirmed test protocol cannot represent;
- `interrupted`, with resumable progress; or
- a precise failure that distinguishes infrastructure failure from a compliance failure.

During development, CAT keeps unselected candidate edits in isolated attempt worktrees. When a candidate has stronger
development evidence under the confirmed comparison rule, CAT applies its changes to the optimization worktree. CAT
leaves the base worktree untouched during development and formal certification. When a certified candidate satisfies
the campaign’s preselected acceptance policy, CAT delivers the selected in-project and explicitly authorized external
changes when the complete combined conflict check passes. The in-project result is a Git diff for the user to inspect;
external changes have a separate review record. The user accepts the Git changes by committing them, rejects them by
reverting them, or asks CAT for another revision. CAT does not commit on the user's behalf. This outcome serves G1,
G2, G3, and G6.

When an accepted candidate also changes an explicitly authorized external linked target, CAT delivers those changes
automatically with the in-project changes after a combined conflict check. It reports the external changes separately
from the base worktree's Git diff so the complete delivered candidate remains reviewable. Development and formal
certification never write the live external target.

CAT edits authorized prompt files under the confirmed scope while preserving the loader configuration that decides
which prompt files load for the campaign. A desired loader change is prepared before a new campaign starts; it cannot
be part of a candidate or accepted delivery in the current campaign.
If CAT observes a loader-configuration change during the campaign, it pauses before another call. Restoring the
recorded configuration permits resumption under the original evidence conditions; keeping the changed configuration
requires a new clean campaign. Earlier results remain point-in-time observations of the original configuration.

## Scope

This contract governs prompt optimization and prompt certification in both currently installed harnesses, Codex and
Claude. Every user receives the same optimizer behavior. Optimization runs locally against the user's configured
model-provider account.

Certifiable trial outputs in this version are text and observable file or tool effects. A trial may consume remote
service or MCP responses as task data and may use arbitrary installed host executables. Generated image, audio, and
video outputs are outside certification scope; a material requirement to produce one cannot be silently represented by
its text description or omitted from the claim. A material output or effect outside this scope makes certification of
the full prompt system unavailable until the confirmed contract can cover it.

A tested trial agent may delegate to subagents, including recursive child-of-child delegation and parallel subagents.
Native child Git worktrees may be used and their work may merge into the isolated trial. Child instructions, observed
text/file/tool effects, results, and merged work belong to that one assigned logical trial. The evaluator judges the
completed trial as one outcome under the frozen candidate; parallel children do not create additional samples. Trial
delegation does not grant candidate-generation agents any new edit authority.

Eligible writes stay in the isolated trial copy or a disposable external fixture. External services used by a trial require confirmed read-only
credentials or disposable fixtures before trial admission. Known writes to persistent host paths outside the trial
copy or persistent external service state are ineligible. CAT refuses a known persistent-write path before dispatch
and does not count its outcome. Arbitrary executables are not a containment guarantee: CAT records effects it can
observe and reports effects it cannot observe as limitations. A read-only prompt source remains eligible as an input;
an explicitly authorized later delivery of an accepted prompt edit follows the separate delivery rules below.
Child worktrees inherit these trial-effect and instruction-source limits. A merge into the trial copy is a trial
effect; a merge into the base, selected optimization worktree, or another persistent host path is ineligible.

The following concerns are outside this feature:

- licensing, entitlement, product billing, or usage analytics;
- provider spending limits or cost prediction;
- product releases, release catalogs, repository publication, or certification of build-time artifacts;
- adding model harnesses, operating-system support, or runner platforms;
- product feedback submission, customer-data policy, or telemetry;
- Docker or another packaged runtime profile; and
- implementation sequencing, branch management, work-executor orchestration, or progress ledgers as optimizer
  product behavior.

Existing runner, Git, and prompt-loading capabilities are dependencies of this feature, not functionality for the
optimizer to reimplement. This boundary serves G1 and G7.

## Terms

These terms are authoritative throughout this contract:

- **Prompt system:** the target prompt file together with the prompt files, rules, configuration, and loading
  relationships that participate in its behavior.
- **Compliance contract:** the user-confirmed requirements, material use categories, test definitions, applicability
  conditions, expected outcomes, evaluator, variation rules, and variation probabilities.
- **Compliance outcome:** the frozen evaluator's pass/fail label for the assigned trial under the confirmed rubric.
  A judge pass rate is not a measured rate of actual requirement compliance.
- **Test definition with approved variation rules:** a test whose semantic obligation and expected result are fixed,
  while explicitly listed incidental values may be sampled according to confirmed probabilities.
- **Material use category:** a distinct class of use for which averaging with other uses could conceal an important
  failure mode. A distinct requirement, applicability branch, or important interaction normally forms a category.
- **Candidate:** one exact prompt-system snapshot being developed or formally tested.
- **Base worktree:** the user’s original Git worktree, which receives an accepted candidate after a safe merge; an
  uncertified candidate requires a separate, explicit user choice when that choice is permitted.
- **Optimization worktree:** the fork of the base worktree in which CAT develops and certifies selected candidates.
- **Development evidence:** outcomes that may guide candidate creation. It is selection-biased and cannot certify the
  candidate selected from it.
- **Formal evidence:** outcomes from instances assigned to a frozen candidate under the formal procedure.
- **Campaign:** one explicitly started optimization effort, with its own acceptance policy and durable 5%
  false-certification allowance shared by all its formal attempts. Its starting harness is fixed for its lifetime;
  resuming an effort keeps its campaign identity and starting harness.
- **Optimization lineage:** the evidence history for one unchanged compliance contract and statistical claim within a
  campaign. A material contract revision starts a new lineage within the same campaign allowance.
- **Formal attempt:** one frozen candidate evaluated under one prespecified formal procedure and allocation. An
  authorized new test of the same snapshot is another attempt; a valid continuation or infrastructure retry is not.
- **Acceptance policy:** the choice, made before campaign trials, between accepting a certified minimum and additionally
  requiring demonstrated improvement over the original, together with the authorized actions for routine outcomes.
- **Test selection record:** the record proving which approved test definition and concrete variation were assigned to
  which frozen candidate before its outcome was observed.
- **Sampling allocation:** the policy for choosing which category receives the next development batch or formal trial.
  It may respond to earlier evidence without changing that category's confirmed input distribution or reporting weight.
- **Measurement identity:** the observable conditions needed to interpret whether a result is current or comparable,
  without claiming that those conditions are sufficient to replay an agent execution.

## Compliance Contract

### Contract derivation and confirmation

To serve G2, CAT must inspect the target prompt system using its production loading behavior and derive a proposed
contract from the requirements and rules embodied by that system. The proposal must list, without hiding summaries
behind aggregate counts:

- every extracted requirement and its source;
- every material use category and why separate treatment is necessary;
- every test definition and applicability condition;
- every multi-turn test's prewritten turn sequence and any material interaction it cannot cover;
- the expected compliant behavior and evaluator decision rule;
- every allowed variation dimension and its sampling probability;
- the development and formal category-allocation policies, coverage floor, and separation of diagnostic cases;
- the development-readiness rule, including its minimum evidence;
- the formal statistical procedure, finite cap and its planning rate/power, stopping and continuation rules, and assumptions;
- the immutable original snapshot, comparison metric and any aggregation weights, and comparison evidence procedure; and
- the campaign acceptance policy, including whether demonstrated improvement is required, automatic next actions,
  and whether separately authorized uncertified acceptance may be offered.

The agent proposes this material; the user does not have to derive routine test mechanics. The user may give feedback
requesting additions, removals, or changes to any requirement, category, test, expected outcome, evaluator rule,
variation rule, or probability. CAT regenerates the complete proposal from that feedback and presents it for another
review; the user does not need field-by-field editing controls. This cycle repeats until the user confirms one complete
proposal. Optimization must not begin before that confirmation.

Confirmation freezes the compliance contract for an optimization lineage. The user may revise it within the same
campaign: CAT presents a new complete proposal for confirmation, and a confirmed material change defines a different
claim and starts a new lineage. Trials under the earlier claim remain historical evidence and cannot certify the new
claim. Merely changing the candidate does not start a new lineage. Neither change resets the campaign’s formal-attempt
index or restores spent allowance. The acceptance policy is chosen before the first development or formal trial and
remains fixed for the campaign; an exceptional user decision is recorded as an explicit override, not a silent policy
change.

Once formal testing has begun, CAT starts a material contract revision only at a safe checkpoint: the current formal
attempt has ended or the user has explicitly stopped it for revision, and every in-flight native call, assigned trial,
candidate promotion, and delivery operation has a reconciled disposition. A request during an active attempt offers
continuing that attempt or stopping it for revision. To stop, CAT stops new assignments, requests cancellation of any
in-flight call, retains each valid outcome already observed, and records an unexecuted assignment without counting it as
a compliance outcome. The attempt then ends as `not certified: stopped for contract revision`; it cannot later resume
under either contract. If a call's disposition remains unknown, CAT preserves the pending request and defers the
revision until reconciliation establishes a safe checkpoint. A completed certification remains an immutable result for
its original claim. The revised proposal still requires confirmation before any trial under the new lineage, and the
campaign's spent formal-attempt allowance remains spent.

### Category formation

To serve G2 and G3, CAT must create a separate category whenever a combined score could allow numerous common or easy
cases to hide a materially different weak area. The user reviews and confirms the category boundaries. Certification
requires every confirmed material category to pass independently; performance in one category cannot compensate for
failure in another.

A category is a group of uses that needs its own compliance claim, not an individual test or a template value. For
example, a file-editing prompt system may need separate categories for an authorized edit, refusal to edit a protected
path, and safe recovery after interruption. Each category can contain several test definitions and many generated
instances. Changing a filename or another incidental template value usually creates another instance in its category,
not another category. The development trial counts and review checkpoint apply separately to each category of each
unchanged candidate; five categories reaching 100 trials each means 500 candidate executions, before any separate
original measurements or certification trials.

### Distribution proposal and confirmation

CAT automatically proposes the sampling distribution as part of contract derivation. The proposal identifies the
approved test definitions within each category, their selection probabilities, and the joint or conditional
probabilities for allowed input variations. Category certification remains separate; an overall usage mix cannot
replace a material category’s own evidence gate. The user reviews the complete proposal rather than constructing
probability tables manually.

Requirements and production loading reveal what behavior must be covered; they do not by themselves establish how
often each situation will occur in future use. The user will not supply representative usage records or examples.
CAT proposes an explicitly labelled balanced benchmark from the confirmed requirements and intended uses, starting
with equal weights across approved test definitions within each category and explicit, coverage-oriented variation
probabilities. These weights are testing assumptions, not estimated or statistically established usage frequencies.
The user may confirm or revise probabilities through feedback on the complete contract proposal. A materially
different rare use remains separately covered even without an example of its occurrence.

User confirmation freezes the complete within-category benchmark distribution and its explicit assumptions.
Certification applies to that confirmed benchmark; reports state that actual future usage frequencies are unknown,
explain the assumed-usage limitation, and identify uncovered or unrepresented situations. A material revision requires
a new complete confirmation and evidence lineage, while retaining the campaign allowance sequence. CAT cannot alter
weights after seeing candidate outcomes to make certification easier.

Category allocation is separate from those frozen distributions. CAT may direct more development batches or formal
trials to a category under the confirmed policies below. This changes testing effort, not the claimed frequency of that
category in future use. Reporting weights also remain separate from realized execution counts. Requiring every covered
category to pass protects the minimum against changes in the mixture between categories only while their internal
input distributions and measurement conditions remain applicable. It does not protect against unrepresented cases or
changes in the mixture within a category.

### Test generators

To serve G4, CAT must validate every generator before its output can count as representative development or formal
evidence. After the allocation policy selects a category, validation must establish that the generator:

- emits only instances permitted by the confirmed test definition;
- preserves the confirmed expected result;
- samples each variation according to its frozen probability;
- records the concrete generated instance;
- selects the test definition and variation within that category independently of candidate identity and observed
  outcomes, using the confirmed probabilities; and
- uses distinct, durable development and formal draw streams bound to the confirmed distribution.

Approved variation rules act as value templates: they vary declared incidental values while preserving the tested
obligation. They help broaden coverage but do not eliminate adaptive overfitting, evaluator bias, dependence, or a
mismatch between the confirmed distribution and future use. Retain representative fresh development batches and a
separate formal draw stream; never treat passing repeatedly reused development cases as fresh evidence. The formal
stream supplies new random assignments and executions after candidate freeze under the confirmed distribution, not
development outcomes relabelled as certification. Independent sampling can legitimately produce the same concrete
instance again; do not reject such a draw solely because it repeats a value and thereby change the distribution.
Representative development and formal evaluation may use the same approved definitions and distribution; their concrete
assignments, executions, and evidence records remain separate. Keep targeted development cases outside representative
rate counts. Targeted diagnosis may deliberately select a known failure or nearby variation; mark that purpose before
execution and never admit its outcome as a random representative or formal draw. Validate its expected behavior against
the confirmed obligation even though its selection is targeted. Targeted development diagnostics may choose later
turns in response to an earlier agent reply, but that adaptive sequence cannot enter representative or formal counts.
For representative and formal multi-turn evidence, each test definition fixes the turn sequence before execution;
approved incidental template values are drawn and assigned before the trial starts. Neither the generator nor an
operator may select or rewrite a later user turn based on an earlier agent reply. A material obligation that requires
such an unsupported response-contingent interaction remains a disclosed coverage gap and cannot be silently omitted
from the certification claim. A formal stream begins drawing for an attempt only
after its candidate and protocol are frozen. Resume
retains that attempt’s assignments and stream state, and an infrastructure retry reuses its existing logical trial.
A later edited candidate receives new formal assignments; prior formal outcomes may guide development but cannot
become the revised candidate’s certification observations. Separate stream identities alone do not prove the execution
and dependence assumptions required by the statistical method.

This separation protects the evaluation of the frozen candidate from direct tuning to that evaluation’s outcomes. It
does not prove that the test definitions cover every future use. Certification is conditional on the confirmed use
distribution and measurement assumptions; it must not be presented as elimination of overfitting or a guarantee for
unrepresented situations. Generator review separately maps requirements, applicability branches, and material
interactions to test definitions and varied instances, and records coverage gaps. Repeating incidental value changes
cannot stand in for a missing kind of behavior. Resolve a missing confirmed obligation before admitting its generator;
disclose situations outside the confirmed scope. New requirements or materially changed generator coverage require
contract revision and fresh evidence for the new lineage, with the same campaign allowance sequence.

A generator or evaluator validation failure is an infrastructure failure. It is not evidence that the candidate
complied or failed to comply.

### Evaluator authority

To serve G2 and G4, the confirmed contract must identify one frozen evaluator profile, rubric, and decision rule. Its
label is the semantic compliance outcome for the campaign. Candidate text and trial-generated content are evidence to
evaluate; they cannot modify the evaluator's rubric, result schema, or authority.
The evaluator judges open-ended text, file content, and tool effects against the confirmed semantic obligation; an
objective assertion or exit status alone does not establish compliance. Objective assertions and tool statuses may
inform the frozen evaluator; they do not substitute for its semantic judgment. The 95%/95% claim targets future pass
labels from this fixed judge under the confirmed benchmark and measurement conditions. It does not claim actual
requirement compliance or independent evaluator calibration.

## Optimization Workflow

### 1. Establish the target

To serve G1 and G5, CAT checks that the base Git worktree has no staged, unstaged, or untracked changes before a new
campaign forks it or starts trials. If it is not clean, CAT stops before either action and tells the user to resolve
the changes and retry. CAT records the clean base worktree's prompt-system paths, contents, and file modes, verifies
that the fork contains the same initial prompt system, and records the production loading relationships observed while
deriving the contract. A prompt system may contain one file or several related files. No manually assigned version
number is required; the tested snapshot is identified from its paths and contents.

The clean-start check applies to a new campaign before its fork. After the fork, base-worktree edits do not invalidate
the existing campaign; CAT handles them at delivery under the conflict and result-applicability rules below.

Readable participating instruction files loaded from outside the forked project, including user-level rules, remain in
scope. Prompt files and directories reached through symbolic links participate in discovery, contract derivation, and
optimization. CAT records each observed link path, resolved target where observable, loading relationship, and source
identity; a path observation alone does not prove which bytes the host consumed. A linked source is editable only when
its resolved target is within the confirmed editable authority. A resolved target outside the project requires the
user's explicit authorization as an editable target; otherwise it remains read-only, even if its link path is inside an
editable root. Authorization identifies the external target and its permitted scope, rather than granting write access
to other destinations reachable through it. In this version, authorization of an external linked file or directory
permits content edits to existing resolved files only. CAT cannot create, rename, or delete files in an external linked
directory, change their modes, or retarget a file or directory link as a substitute. Within the project, CAT may edit
existing prompt-file content and create, rename, or delete ordinary prompt files. It keeps existing symbolic-link
entries, their destinations, and existing file modes fixed; a newly created ordinary file uses the project's normal
creation mode. Loader configuration that decides which prompt files load is fixed for the campaign, including during
candidate authoring and trials. CAT cannot edit that configuration as a candidate change, even when it lies under an
otherwise editable root. If the desired behavior requires a loader-configuration change, the user prepares it before
starting a new campaign. A known material change to a source, link destination, or loading relationship follows the
material-change and historical-result rules below.

Certification supports governing instructions from readable files, whether they are inside or outside the forked
project. If a declared or observed remote service or MCP response supplies instructions that govern the agent, CAT
reports certification unavailable for that prompt system. It does not omit that source from the contract or treat it
as ordinary task data to make a certification claim. A remote response supplied for the agent to analyze as task data
remains allowed, even if the data contains imperative wording; its role, rather than its wording, determines this
boundary. Such task data stays within the confirmed test definition and semantic evaluation scope, including its
effect on text and file/tool behavior. CAT records available source-role evidence and observation limits; it does not claim to detect hidden
instructions that the host does not expose.
The same source-role rule applies to instructions a trial subagent loads. CAT includes child-visible readable governing
files in the proposed contract when observable and treats a known remote response governing a child as an unsupported
instruction source. Child task data remains eligible under the same observation limits.

### 2. Confirm the compliance contract

CAT performs the derivation and confirmation workflow above. Until confirmation, the only valid state is
`awaiting contract confirmation`; CAT must not run development or formal trials. Confirmation also records the
campaign’s acceptance policy once, before those trials. This gate serves G2.

### 3. Develop candidates

To serve G1, CAT may generate, diagnose, compare, and revise candidates using all prior development evidence in the
lineage. Development instances and their outcomes may be shown to candidate-generation agents.

Candidate-generation agents make proposed prompt-file changes within the confirmed editable roots of an isolated
attempt worktree based on the current selected candidate in the optimization worktree. CAT captures each attempt's
exact changes as a candidate snapshot and evaluates it before changing the selected optimization worktree. Trial
copies are separate from the selected optimization worktree and the base worktree.
For an authorized linked file or directory, the isolated attempt stages its resolved target within the confirmed
authority and retains the link-to-target relationship. The exact candidate snapshot includes authorized in-project
content edits and ordinary-file create, rename, and delete, plus only content edits to existing files in authorized
external targets, not just link paths. Development and formal trial copies stage those exact bytes
and relationships for production prompt loading. Neither authoring nor trials may mutate the base worktree or a live
external target during development or certification.

CAT promotes a distinct candidate only when its valid development evidence is stronger than the current selected
candidate under the confirmed comparison rule. Promotion applies the candidate's authorized content and ordinary-file structural changes to
the optimization worktree without changing the base worktree. CAT verifies the resulting prompt-system snapshot against
the measured candidate before that candidate becomes the selected development incumbent. Later development attempts
start from that incumbent. Unselected attempts never change the selected optimization worktree or the base worktree.
Promotion of an authorized external linked-target change updates only the retained selected candidate and its
isolated representation; it does not write the live external target.
CAT checks the optimization worktree against its recorded prompt-optimizer operations before further work and after
owned changes, including on restart. If it detects an unowned change, it stops scheduling work and asks the user how
to proceed. It preserves the worktree and evidence for that decision; it never silently incorporates the changed
content into a candidate or formal result. A transient edit that is reverted before CAT observes it may escape this
check; CAT does not claim to detect every write event.

The generation prompt forbids edits outside confirmed authority, changes to symlink entries or destinations, existing
file-mode changes, and loader-configuration edits. CAT uses the runner's existing workspace isolation and staged linked
targets. It does not add a custom filesystem or process sandbox to prevent every forbidden temporary edit. Before
candidate admission or any trial, CAT compares the complete no-follow attempt inventory with its starting snapshot,
including tracked, untracked, and ignored paths, symlink metadata, modes, loader configuration, and staged targets.
An observed forbidden operation or forbidden final delta rejects the whole attempt as an infrastructure failure;
it contributes neither a candidate nor a compliance outcome. CAT retains the isolated edits and operation evidence
for inspection and reconciles the native call and workspace before retry. The selected optimization worktree, base,
and live external targets remain unchanged. CAT does not claim to observe a transient forbidden edit reverted before
inspection when no operation event exposes it.

Focused native prompt tests report observed adherence counts and the tested operations, harness, model, and conditions.
These tests measure instruction compliance; they do not prove that forbidden mutations are impossible or establish the
campaign's separate frozen-judge certification claim. Git diff alone is insufficient evidence because it omits
untracked paths, external target content, and some permission changes.

When diagnosis identifies a requirement that would need an unauthorized external linked target to change, CAT warns
that the target remains read-only. The warning names the affected requirement, link path, resolved target where
observable, practical consequence, and available choices: explicitly authorize that target for a revised editable
scope, meet the requirement through another authorized source if possible, or stop/revise the campaign. This diagnosis
uses observed evidence and does not claim to identify every needed edit. When CAT observes an attempted write through an unauthorized link or a forbidden final delta, it rejects the attempt
and reports the attempted edit with the same source details and choices.

If diagnosis instead identifies a requirement needing a create, rename, delete, mode change, or link retarget through
an already authorized external link, CAT warns that this operation exceeds the existing-file-content-only scope.
The warning names the affected requirement or attempted operation, link path, resolved target where observable,
scope limit, practical consequence, and viable choices: edit existing file content if that can meet the requirement,
use another authorized in-project source if possible, or stop/revise the campaign. Authorizing the same external target
again cannot enable a structural edit in this version. CAT gives the same warning if an attempt tries the disallowed
operation, rejects the attempt, and admits neither a candidate nor a compliance outcome from it.
Diagnosis remains limited to observed evidence; CAT does not claim to identify every needed edit. Existing runner
isolation must keep live destinations outside authoring authority, and rejected attempts follow the reconciliation
and retry rule above.

CAT also rejects an observed attempt to create, delete, or retarget an in-project symlink, or to change an existing
in-project prompt file's mode. It reports the prohibited operation and keeps the attempt out of candidate and
compliance evidence. If diagnosis identifies one of these changes as necessary, CAT explains the scope limit and
directs the user to make that change before starting a new campaign or to pursue an allowed prompt edit.

Use the following accepted development-readiness defaults in the complete campaign proposal. The user may explicitly
revise them before confirming a campaign; CAT must not silently change them after observing results. All observations
used for these thresholds belong to the same candidate snapshot, category, and comparable measurement conditions.

| Condition | Default trigger | Action |
|---|---|---|
| Clearly weak | A reproducible requirement failure, or an exploratory 95% Wilson interval whose upper endpoint is below 95%. | Diagnose and edit the candidate promptly. A reproducible defect does not need a minimum sample or a completed batch. |
| Consistently strong | Every material category has at least 100 representative development trials and at least 99% observed compliance; each of its latest two completed batches of 50 has at least 98% compliance; no unresolved repeatable defect or known regression remains. | Consider certification of the exact candidate using fresh formal trials. |
| Promising but uncertain | Neither weak nor ready, including an insufficient sample or uncertainty about reaching the minimum. | Keep the candidate unchanged and allocate further batches of 50 under the adaptive policy below, subject to the review checkpoint. |

Retain all representative observations for that candidate/category when computing its overall rate and interval; never
restart its counts or select only favourable batches. The two-batch stability check uses the latest two completed
batches, not a chosen pair. Interrupted or defect-stopped batches retain their outcomes and identities on resume.
Repeated executions of targeted diagnostic cases stay separate from representative rate estimates. Wilson intervals
are exploratory development summaries, not simultaneous guarantees across adaptive edits or repeated checks.

At 200 representative development trials in a category for an unchanged candidate, if the category remains unresolved,
review before scheduling more representative trials in that category. Show its evidence and offer further evidence,
targeted optimization, or stopping while preserving the candidate/results. An authorized extension specifies its next
review point before further sampling. This is a review checkpoint, not a hard trial cap or a plateau. A weak candidate
is edited promptly and a ready candidate can proceed without waiting to reach 200. Candidate edits start new
version-specific counts; earlier outcomes remain available for diagnosis and comparisons.

Fix identifiable defects early, spend representative trials on plausible candidates, and avoid requiring development
to prove certification before certification begins. Start a revision’s evaluation with the known failing cases and
relevant regressions; repair reproducible defects before spending a full representative batch on it. Representative
measurements then inform selection and readiness. Do not require the development interval’s lower endpoint to exceed
95% or consume formal allowance as an admission condition. This heuristic does not promise that certification will
pass or that it minimizes total executions.

#### Adaptive development allocation

Use the following default policy, recorded in the confirmed proposal. An eligible category has no pending evidence-review
choice or other admission stop. Readiness, a detected defect, interruption, and measurement-change decisions take
precedence over scheduling another batch; the allocation policy cannot extend a review checkpoint or force a ready
candidate to delay certification.

1. Start balanced: give each eligible category one representative batch of 50 before scheduling its second batch.
   Known failing examples and relevant regression checks run first; a reproducible defect may stop a batch early for
   diagnosis/editing. A new candidate begins its own coverage cycle and counts.
2. After initial coverage, alternate a coverage batch and an adaptive batch of 50. Coverage batches cycle evenly
   through eligible categories, including categories currently ready, to retain checks for regressions. Adaptive
   batches go to unresolved eligible categories. The equal shares are a default scheduling heuristic, not a claim
   about usage frequencies, an exact final sample ratio, or a proven minimum-cost allocation.
3. At a batch boundary, prioritize the unresolved category with the lowest exploratory 95% Wilson lower endpoint
   computed from all its current-candidate representative outcomes. This combines weak observed performance and
   uncertainty without rewarding raw failure counts. Break ties by fewer representative observations, then confirmed
   category order. Empty evidence receives first-coverage treatment. Coverage ties follow confirmed category order.
4. Hold the selected category for that batch unless a higher-priority stop applies. Update adaptive priorities at the
   next batch boundary; a single pass does not erase previous failures. As evidence of recovery accumulates, extra
   attention can move elsewhere while the coverage cycle remains. A reproducible defect or clearly weak result triggers
   diagnosis/editing immediately rather than repeated measurement of an already actionable weakness. When the cause is
   unknown, use bounded targeted diagnostic variations before editing under the existing development-round limit.
5. Persist the policy identity, candidate/lineage and measurement identity, evidence revision used, selected category,
   coverage/adaptive purpose, cycle position, and batch progress before the first draw. Resume an interrupted batch
   and retry its assigned trial without choosing a more favorable category or forgetting failures. An absent or stale
   selection blocks new dispatch until reconciled; previous valid outcomes remain retained.

The coverage floor applies while representative development evaluation continues; it never overrides a checkpoint,
defect, or other stop. Coverage observations also count toward the category's rate, readiness, and checkpoint, and can
make a previously ready category unresolved. If a category requires an evidence-review choice, sampling another one
does not satisfy or bypass that choice. If no unresolved eligible category remains, follow the readiness or pending
choice instead of filling an adaptive slot with unnecessary calls. Preserve historical evidence and known-failure
regression cases after an edit, but compute the new candidate's readiness and priorities from its own observations.
This policy reduces concentration on familiar failures without claiming to prevent all development overfitting.

#### Development results

Calculate and display the four outputs defined below throughout development, including actual category numerators and
denominators. Label the results `development estimate` and `not certified`; the 99% observed readiness target is not a
95%-confidence claim that true compliance is 99%.

CAT may make content changes and create, rename, or delete ordinary prompt files when those
changes improve the confirmed behavior within authorized in-project scope. It cannot create, delete, or retarget a
symbolic-link entry or change an existing file's mode during optimization. For an explicitly authorized external
file or directory reached through a link, only existing-file content edits are eligible; a proposed external
create/rename/delete, mode change, or link retarget is refused as an invalid generation attempt rather than admitted
as a candidate or compliance outcome. It applies selected candidates' changes within the optimization worktree and
merges certified changes that satisfy the acceptance policy into the base worktree for Git review under the existing
authorization for routine edits. This behavior serves G1.

### 4. Detect a development plateau

To serve G1 and G6 without pretending that improvement is guaranteed, CAT returns `not certified: plateau` only when
both conditions hold:

- every currently failing material category has received a bounded diagnosis and candidate-generation attempt; and
- one complete development round produced no distinct valid candidate with stronger development evidence.

The plateau report identifies the unresolved categories and evidence. CAT leaves the base worktree unchanged and retains
the optimization worktree and its best candidate for review. The user may revise the confirmed contract, explicitly
request a permitted merge of that uncertified candidate, provide another requested change, or stop. CAT must not predict a plateau
from elapsed time, token use, or a speculative cost model.

When a completed round produces a distinct stronger candidate that remains below development readiness, CAT starts
another development round under the confirmed policy without asking the user to request each round. It continues
until readiness, a valid plateau, an evidence-review choice, interruption, or another contract-defined stop.

### 5. Freeze and assign a formal candidate

To serve G3 and G4, CAT freezes the exact candidate after its successful promotion into the optimization worktree and
before drawing concrete formal instances. An initially ready candidate already captured from that worktree needs no
promotion. CAT verifies that the optimization worktree's prompt-system snapshot matches the candidate it freezes.
The user may inspect all confirmed test definitions and variation distributions, but candidate-generation agents must not see a concrete formal
instance before it is assigned to that candidate.

Each selection record contains at least:

- the confirmed test definition and category;
- the durable allocation decision and its prior-evidence revision, distinct from within-category draw probabilities;
- the approved variation values and probabilities used;
- the generator identity;
- the random draw identity or seed when the generator exposes one;
- the candidate identity and assignment time;
- the concrete generated instance;
- the complete assigned turn sequence and incidental template values before a multi-turn trial starts;
- the attempt and retry relationship; and
- the outcome and evaluator identity after evaluation.

The record establishes ordering and guards against cherry-picking. It does not claim that the model execution is
reproducible.

### 6. Run formal certification

Formal trials use fresh executions or freshly generated instances of the already-confirmed test definitions. They do
not introduce new requirements or tests. Each logical trial starts one fresh root harness session. Its agent may use
native recursive or parallel subagents and separate child Git worktrees; those children remain inside the same logical
trial assignment. When a confirmed test
requires multiple turns, its complete prewritten sequence and incidental values are assigned before execution; all
turns run in that session and produce one semantic outcome for its assigned category. A later user turn cannot be
chosen or rewritten in response to an earlier agent reply. If a known material obligation requires that unsupported
interaction, CAT reports the coverage gap and certification is unavailable for the full claim until the contract can
cover it; it cannot certify only the convenient subset.
Formal outcomes for a candidate may not be used to modify that same candidate and then continue counting as its
evidence.

A known unsupported governing-instruction source blocks formal-attempt allocation. If CAT discovers one after an
attempt was allocated, it stops further formal assignments, reconciles in-flight work, retains valid outcomes and the
spent allowance, and ends that attempt as `certification unavailable: unsupported instruction source`. That status is
not a statistical below-minimum, inconclusive, or certified result. Restart preserves the disposition. A later
file-backed contract requires the applicable contract-revision and fresh-attempt rules; earlier outcomes remain
historical.

A known material requirement for response-contingent follow-up turns likewise blocks formal-attempt allocation.
If discovered after allocation, CAT stops further assignments, reconciles in-flight work, retains valid outcomes and
the spent allowance, and reports `certification unavailable: unsupported test interaction`. It does not relabel a
targeted adaptive diagnostic as representative or formal evidence. Restart preserves that disposition.

A failed formal candidate may inform later development, but the revised candidate receives new formal instances and
the next allowance in the same campaign. This separation serves G3 and G4.
The formal allocation policy may select among unresolved categories from already retained outcomes, as specified
below; it cannot select favorable instances within a category or import development observations into formal counts.
CAT calculates the four outputs and applies the decision table below. A failure to certify alone does not prove that
the candidate is below the minimum. Retain the candidate, all results, and its allocated allowance when returning to
development, collecting more evidence, or stopping. Routine actions follow the preselected policy; unresolved choices
are presented concretely rather than automatically restarting optimization after every inconclusive result.
An attempt explicitly stopped for contract revision retains its four outputs as observations for the old claim and is
reported separately from a below-minimum or cap-inconclusive statistical decision.

### 7. Deliver the candidate and result

After certification and acceptance under the recorded policy or explicit user decision, CAT verifies that the
optimization worktree still contains the selected frozen candidate. Certification alone does not authorize delivery
when the acceptance requirement is unmet. CAT then compares the base worktree with its recorded clean fork state,
including any staged, unstaged, and untracked changes made after the fork and structural path changes. CAT compares
the complete change sets before writing to the base. If both worktrees changed the same file, or a rename, deletion,
or creation collides with a base change, CAT leaves the base untouched and asks the user
how to proceed. Otherwise it merges without user intervention and preserves all base-only edits. If a base file changes
after the conflict check but before its merge write, CAT stops without overwriting that edit and retains the partial
merge record for safe recovery or user direction. A merge interrupted before completion resumes from its retained
record without repeating or overwriting an edit. CAT verifies the exact combined result, then reports the Git diff, certification result, category
evidence, measurement identity, and limitations. If a base-only edit changed another participating prompt source, the
merged prompt system is a different snapshot: CAT still merges disjoint files, but reports the certification as
historical for the base worktree. Other known material base condition changes have the same reporting effect. The user
commits, reverts, or requests another revision. A behavioral edit after certification likewise makes the result
historical. This behavior serves G1, G5, and G6.

For authorized linked targets inside or outside the project, CAT binds delivery to the exact selected candidate and
confirmed target authority. External delivery writes only the accepted content of existing resolved files; it never
creates, renames, deletes, or retargets external paths. Before any base or external write, it verifies each link's resolved destination and
preflights the full combined in-project and external change sets against the fork-time source baseline and current
live targets. A same-file change on both sides, structural collision, changed link destination, or other target
identity mismatch stops the complete delivery before mutation and asks for user direction, preserving the candidate
and conflict evidence. Disjoint live changes survive. After a clear preflight, CAT journals one accepted delivery
across both destinations and applies the changes automatically. It validates each live preimage immediately before
writing; a concurrent edit stops further writes without overwriting it and retains any partial application in the
journal. Restart reconciles and resumes only the same authorized delivery, applying each change at most once and
never overwriting an intervening user edit. CAT verifies the final combined base and external snapshot, then reports
the Git diff, external change record, delivery status, and certification applicability. A partial delivery is never
reported as complete or as current certification for the combined live prompt system.

At a plateau or any other uncertified outcome, CAT does not merge automatically. If uncertified acceptance is
permitted and the user explicitly selects `accept without certification` for the retained candidate,
CAT verifies that the optimization worktree still contains that selected snapshot and applies the same complete
file-conflict check and recoverable merge procedure. The report labels the merged candidate `not certified`; merging
does not create or imply certification evidence.

## Statistical Certification Contract

### Claim

The user-facing claim is:

> Certification predicts the future pass rate under the frozen evaluator for executions starting from the certified
> prompt-system snapshot while the model, evaluator, benchmark, and other measurement conditions remain materially unchanged.

More precisely, CAT certifies only when every material use category has a valid one-sided lower confidence bound above
95% for the frozen judge's pass probability under its confirmed test distribution, under a procedure that keeps the
probability of ever falsely certifying that judge-pass claim within one campaign at or below 5%.
Minimum frozen-judge pass rate is the required certification claim. Demonstrated improvement is an optional acceptance requirement,
not a substitute for minimum certification. This claim serves G3; it is not a claim of deterministic future behavior
or a 95% probability assigned to a particular candidate after observing its result.

### Unlimited formal-attempt allowance

Formal attempt `i`, numbered from 1 across the campaign’s lineages and candidates, receives this
false-certification allowance:

`alpha_i = 0.05 / (i * (i + 1))`

The allowances sum to 0.05 across an unlimited number of formal attempts. The first three are 2.5%, about 0.833%, and about
0.417%. Later candidates remain testable but require stronger evidence.

Every category test in attempt `i` uses a valid level-`alpha_i` procedure, and the candidate certifies only if every
category passes. No division of `alpha_i` among categories is required: if any category's true compliance is at most
95%, certification requires that weak category’s level-`alpha_i` test to pass incorrectly. The campaign-wide sequence
then bounds the probability of ever falsely certifying by 5%, provided each attempt remains valid conditional on the
earlier history used to select it. This argument does not establish simultaneous coverage of every displayed interval
or a joint guarantee for certification and improvement claims.

Use an anytime-valid confidence-sequence procedure with a finite cap calibrated before each formal attempt. Its
bounds must remain valid across the supported repeated checks and stopping decisions under the retained trial process.
The accepted planning defaults are 99% true compliance and at least 90% probability of certification by the cap in
each category. These are sample-planning assumptions, not a finding that the candidate’s true rate is 99% or a promise
that all categories together will certify with 90% probability. CAT must not repeatedly inspect an ordinary
fixed-sample interval and treat it as anytime-valid evidence.

### Campaign boundaries and reset

An explicitly started new campaign receives a fresh 5% allowance and starts at attempt 1, including when the prompt
family, model, or other conditions are unchanged. Starting it records a new campaign identity and acceptance policy;
it does not overwrite or relabel the previous campaign’s evidence. The interface distinguishes `resume campaign`
from `start new campaign` and explains that the guarantee applies separately to each campaign.

Candidate edits, a failed certification, contract or measurement revisions, target renames, interruptions, process
restarts, and cancellation followed by resumption do not reset the current campaign’s sequence. CAT must not
silently start a new campaign to obtain more allowance. An explicit new campaign may follow a closed or cancelled one.
Repeated campaigns do not share a 5% lifetime guarantee: repeated resets and selection of a successful campaign can
increase the probability of at least one false certification across those campaigns. A successful certification also
does not automatically reset an effort that continues within the same campaign.

### Correlation and trial validity

To serve G4, fresh wording alone is not treated as proof of independent trials. The formal procedure must account for
the effects of shared sessions, provider caching, throttling, evaluator memory, adaptive routing, and other known
sources of dependence. CAT must use fresh harness sessions by default and a statistical method whose conditional
outcome assumptions match the execution process. When those assumptions cannot be justified, CAT reports that formal
certification is unavailable instead of weakening the claim.

### Adaptive formal allocation

Freeze the category-allocation policy with the formal procedure before the attempt's first draw. Initially give every
category one trial, unless the attempt stops earlier. Thereafter choose the unresolved category with the lowest current
formal lower bound, breaking ties by fewer valid formal observations and then confirmed category order. Reconsider
after each adopted valid outcome. This directs evidence to the weakest or most uncertain unresolved category without
editing the candidate or changing the category's test-selection/variation probabilities.

Choose and persist the category from earlier retained evidence before drawing its next instance. Its next draw must
remain a fresh draw from its frozen distribution conditional on that history. Validate the anytime-valid procedure
under this scheduling rule and the actual execution process before formal admission; adaptive scheduling alone is not
proof of validity. Keep each category's bounds and counts separate, stop sampling a category that certifies, and retain
the existing whole-attempt below-minimum and cap stops. Category scheduling consumes no additional attempt allowance
and cannot borrow another category's unused cap, reset counts, or change the reporting weights.

Restart recovers a pending category choice and its assigned instance before making another choice. A missing or stale
allocation record blocks dispatch until reconciled. Any material change to a category's internal test distribution
requires the contract-revision path and a valid procedure; the current method cannot pool such changed distributions.

### Retry identity

An infrastructure retry of the same assigned instance against the same frozen candidate remains the same logical
trial. It cannot create another opportunity to discard an unfavorable semantic outcome. A valid compliance outcome is
retained exactly once; a transport or infrastructure failure with no compliance outcome may be retried and does not
consume a new formal-attempt allowance.
Child completion, parallel work, and any child-worktree merge into the trial copy must be reconciled before final
semantic judgment. A retry cannot discard a completed child effect or count a child as a fresh trial. If child
disposition or merge effects cannot be determined, CAT records an infrastructure/observation limit and does not invent
a pass or failure label. Cancellation and restart reconcile the root and children under the same trial identity.

Revising the frozen candidate's behavioral prompt-system content for a later formal trial creates a new formal candidate
and consumes the next campaign allowance. Modifications confined to a trial's isolated project are part of that trial's
behavior; they do not revise the frozen candidate or change the trial's assignment. Restarting the process,
interrupting the campaign, or retrying unchanged work does not reset the campaign’s allowance sequence. These
rules serve G3, G4, and G6.

### Finite cap and continuing evaluation

Before any formal draw, freeze the procedure, campaign allowance, planning rate/power, and per-category trial cap.
Choose the smallest cap at which the selected procedure’s probability of certification reaches the confirmed planning
power under the planning rate, accounting for its early success and below-minimum stopping rules. Report that cap and
the maximum total candidate trials across categories before execution. Calibration and its assumptions must be valid;
an unavailable calibration is an admission failure, not permission to run without a cap.

After each valid logical trial, update that category’s anytime-valid bounds. Stop sampling a category once it certifies.
A category shown clearly below the minimum ends the candidate’s attempt under the decision table. If an unresolved
category reaches its cap, end the attempt as `not certified: inconclusive (trial cap reached)`, stop scheduling its
remaining candidate trials, and retain the original by default. Preserve the candidate, counts, bounds, and results.
The user may subsequently choose further optimization or an explicitly authorized new attempt. The cap does not prove
that true compliance is below 95%, and it cannot justify accepting an uncertified candidate automatically.

Within the retained cap, additional evaluation of the unchanged candidate continues the same attempt, retains its
observations, and consumes no new allocation. Resume retains the cap, counts, bounds, and assignments; infrastructure
retries retain the logical trial. The cap counts valid logical candidate trials; infrastructure failures follow their
separate recovery/stop rules and are not fabricated statistical outcomes.

Do not increase a frozen cap after observing evidence or automatically reopen an attempt that reached it. A later
authorized formal attempt uses fresh draws and the next campaign allowance, even for the same snapshot. Editing the
candidate likewise requires a new attempt. Prior outcomes remain attached to their original attempt and cannot be
silently pooled into the new certification evidence.

### Evidence-cost reference

For the selected sequential procedure with the accepted planning defaults, the first three per-category caps are
250, 317, and 334 trials. These are maximum valid trial counts; successful categories can stop earlier. The cap is
recalculated from each attempt’s allowance before its draws, rather than reusing the first attempt’s cap forever.

For comparison only, a fixed-sample exact binomial test has these best-case zero-failure paths:

| Formal-attempt allowance | Minimum all-pass result per category |
|---:|---:|
| 2.5% | 72/72 |
| 0.833% | 94/94 |
| 0.417% | 107/107 |

Those fixed-sample figures are arithmetic references, not stopping thresholds for the selected sequential method.
A higher cap does not mean that every evaluation uses more trials; the average depends on true compliance and the
stopping rule. Neither the cap nor development readiness converts the 99% planning rate into an observed formal claim.

## Evidence Display and Acceptance

### Four outputs in both phases

Use the same valid trial observations to calculate these outputs; four outputs do not require four independent trial
sets. Comparison additionally needs valid original-snapshot observations. Show unavailable values and their reasons
instead of inventing an estimate. Routine baseline measurements follow the predeclared comparison schedule under the
campaign’s existing authorization; they do not require repeated approval.

| Output | Required presentation |
|---|---|
| Estimated frozen-judge pass rate | Candidate passes/trials and estimated rate under the confirmed evaluator for each material category. An optional aggregate uses only predeclared use weights and cannot replace category gates; the estimate does not measure actual requirement compliance. |
| Estimated improvement over the original | Candidate rate minus the recorded original’s rate, in percentage points, with the original identity, sample counts, and interval for the difference. Show per-category results; any acceptance metric or aggregation is fixed beforehand. |
| Comparison with the minimum acceptable rate | The 95% threshold, the relevant bounds, and `below minimum`, `inconclusive`, or `certified` in formal evaluation. Development shows its readiness assessment and always says `not certified`. |
| Error bars | Method, confidence level or exploratory status, sample size, and interval endpoints for rates and differences. Keep the formal one-sided certification bound visibly distinct from a descriptive two-sided interval. |

Show the allocation policy and actual category counts separately from the benchmark and any aggregation weights.
Compute comparisons under the same confirmed category definitions and reporting weights for the original and candidate;
different realized sampling shares cannot redefine improvement. Do not present the pooled adaptive pass fraction as
the future frozen-judge pass rate. Error bars quantify uncertainty under the stated measurement procedure; they do not cover unknown
usage frequencies, missing scenarios, or generator representativeness. More trials cannot remove those coverage gaps.

Development estimates and intervals describe evidence used adaptively for candidate selection. Label them exploratory;
they do not provide the campaign’s certification guarantee or spend its formal allowance. Reused cases and targeted
regression tests must be identified. Fresh representative development batches improve the usefulness of the estimate,
but selection among repeatedly assessed candidates can still bias the displayed result.

Formal intervals and decisions follow the selected anytime-valid procedure, so the displayed formal bounds support
its repeated checks and early decisions within the frozen cap. Show each bound’s confidence level and distinguish the
minimum-certification guarantee from the coverage of a displayed two-sided interval. Additional observations generally improve precision; do not promise that every new
observation strictly narrows an interval, or narrow error bars merely by recounting reused observations.

By default, measure the original during the initial representative development evaluation and retain those
observations for comparison throughout the campaign. Reuse comparable original observations instead of rerunning the
original for every candidate. Diagnosis and early fixes need not wait for the original to complete the 100-trial
readiness sample; retain the actual available count and its uncertainty. The default minimum-only acceptance policy
does not require additional original executions during certification merely to populate a formal improvement claim.
Continue showing the available development comparison with its exploratory label, even alongside formal candidate
certification; certification does not upgrade that comparison’s evidence. Additional original measurements are needed
when the preselected valid comparison procedure calls for them, such as for improvement-required acceptance.

The original is the immutable prompt-system snapshot recorded at the campaign’s fork, not the latest development
incumbent or a subsequently edited base worktree. Reuse its observations only where the comparison procedure permits
and measurement conditions remain comparable. Reuse does not create more original observations or remove their
uncertainty. New matched tasks require original executions when the chosen paired procedure needs them. The proposal
specifies when baseline measurements run and whether a development-only estimate or formal improvement evidence is
needed. A comparison trial identifies its arm as original or candidate and uses that arm’s frozen snapshot in an
isolated trial copy. Evaluating the original must not replace the selected optimization-worktree candidate. Formal
comparison cannot assume that a development-tuned or reused baseline estimate is fresh unbiased data.

An optional demonstrated-improvement requirement needs its own prespecified valid comparison procedure, including
repeated-candidate and repeated-look error control and a stated guarantee. The minimum-certification allowance does
not automatically cover that separate claim. State each guarantee separately; a promised joint guarantee needs
corresponding joint error accounting. Do not infer improvement from overlapping/nonoverlapping individual rate
intervals: use the interval for the candidate-minus-original difference.

### Decision table

Apply the table to valid formal evidence under the campaign’s policy. `Clearly below` means at least one material
category’s applicable upper bound is below 95%; `certified` requires every formal lower bound to exceed 95%; otherwise
the minimum is inconclusive. Equality does not certify. Infrastructure failure or unsupported statistical assumptions
produce an unavailable result and recovery choices, not a fabricated compliance classification.

For the predeclared comparison metric, `clearly improved` means the applicable difference interval is wholly above
the required improvement margin, zero unless a positive practical margin was chosen beforehand. `Clearly worse` means
it is wholly below zero. Otherwise the improvement requirement is inconclusive or unmet, including a positive result
that does not clear a chosen practical margin; follow the inconclusive-comparison row and show the actual bounds.
Absent or incomparable evidence is unavailable. The original’s own certification status is not a substitute for this comparison.

| Candidate’s evidence | Comparison with original | Recommended action |
|---|---|---|
| Clearly below the minimum | Any | Return to optimization, or stop and retain the original. |
| Inconclusive about the minimum | Any | Within the cap, offer continued evaluation of the unchanged candidate, further optimization, or stopping. At the cap, end the attempt without certification and retain the original; subsequent testing needs an explicitly authorized new attempt. |
| Certified | Clearly improved | Accept under the user’s existing authorization; further optimization can be a later effort. |
| Certified | Clearly worse | Retain the original by default; accepting a known regression requires an explicit trade-off. |
| Certified | Comparison inconclusive or unavailable | Apply the acceptance policy chosen beforehand: accept based on the certified minimum, or require more comparison evidence. |

The accepted default is **certified minimum sufficient**: accept a certified candidate under the campaign’s existing
authorization, including when comparison with the original is inconclusive or unavailable. Retain the original by
default when there is a clear regression; accepting that regression requires an explicit trade-off. The full proposal
records this policy before trials without requiring a separate repeated acceptance-policy question.

The user may instead explicitly select **certified minimum plus demonstrated improvement required** before the campaign,
when that additional evidence cost is justified. Record authorized automatic next actions with the chosen policy. Both
policies retain the original by default when the comparison shows a clear regression. Certification status and
acceptance/delivery status are separate: a certified candidate can be retained without being accepted, and an uncertified candidate never acquires a certified
label through acceptance.

Routine outcomes execute under the recorded authorization. If a choice remains, offer concrete applicable actions:
`collect more evidence`, `optimize further`, or `stop and keep the original`, explaining which snapshot and allowance
are affected. Preserve the candidate and its results for all choices. When permitted, expose `accept without
certification` as a separate explicit choice, never the default or an unlabelled variant of acceptance. A known
regression likewise requires an explicit recorded trade-off. All accepted deliveries use the same conflict detection,
worktree ownership, and recoverable merge rules.

## Measurement Identity and Result Applicability

### Point-in-time evidence

To serve G5, CAT treats every result as a point-in-time statistical observation. It records enough observable identity
to explain what was measured and to detect known material changes, including:

- the exact candidate prompt-system snapshot;
- the confirmed compliance contract and generator identities;
- the model and exposed reasoning or sampling configuration;
- the campaign's loader configuration and observed prompt-loading relationships;
- the evaluator profile, rubric, and decision rule;
- the harness and runner identity;
- the identities and available version information of directly invoked host tools;
- prompt-source paths observed loading during trials, including conditionally and lazily loaded sources;
- symbolic-link paths, resolved targets where observable, confirmed edit authority, and staged linked-source snapshot
  identities, with limits on what the host actually consumed; and
- external or MCP source identities and captured material responses when available.

CAT does not parse native binaries to construct dependency closures, copy a closed runtime filesystem, or claim that a
content hash recreates an external service, host configuration, or nondeterministic agent execution. Missing or
unobservable conditions are reported as limitations.

### Current and historical results

A certification is **current** only while CAT knows of no material change to the certified candidate or its measurement
conditions. CAT conservatively makes it historical when it observes any of these changes:

- behavioral prompt-system content;
- a requirement, category, test definition, variation rule, or variation probability;
- evaluator model, prompt, rubric, or decision rule;
- runner model or reasoning profile; or
- a known provider, harness, source, or host-tool change capable of affecting outcomes.

CAT cannot prove that a provider made no hidden behavioral change. It reports current applicability based on observable
conditions, names any observed material change that makes the result historical, and retains that limitation with the
result.

For the four outputs' candidate-versus-original comparison, the observations are **comparable** only when they share
the required measurement identity: the same compliance contract and use distributions, evaluator semantics, model
profile, and materially equivalent execution conditions. Candidate identity and observation time may differ because
the comparison measures the candidate against the campaign's immutable original. CAT names known material differences
rather than reducing the check to one opaque hash. It checks required recorded conditions by exact equality, except
candidate identity and observation time. Missing required data makes comparability `INDETERMINATE` and the original
comparison unavailable; missing data cannot be treated as equal. This check belongs to the four outputs and acceptance
decision, not to a separate operation for comparing arbitrary saved results.

Historical results are immutable observations. A stale result is not deleted or rewritten; it simply cannot be
presented as certification of the current prompt system. These rules serve G5.

### Source changes during a run

Prompt loading follows production behavior, including conditional and lazy loading. CAT records the prompt-source paths
it observes the host loading during each trial. A trial's outcome measures behavior starting from its assigned frozen
candidate snapshot under the confirmed test protocol, including changes made within the trial's isolated project. A
load-path observation does not establish which bytes the host loaded, and CAT must not present a starting-snapshot
fingerprint as a receipt for bytes consumed during the trial.
For linked sources, CAT also verifies the staged resolved-target snapshot before assignment and records observed link
and target identities. A changed link destination or target content is a material condition; CAT does not silently
pool outcomes across that change. This does not turn path observations into byte-consumption proof.

If CAT detects an unowned optimization-worktree edit, the stop-and-ask rule above applies before any new-candidate
decision. Otherwise, if the candidate's behavioral content changes for a later formal trial, the new-candidate rule applies. If another
known material measurement condition changes between trials, CAT must not silently combine the conditions into one
result; it pauses or starts the appropriate new measurement identity and reports the change. This protects G4 and G5
without requiring continuous filesystem monitoring or an artificial closed prompt profile. When CAT detects a material
change to the candidate or an external measurement condition during a run, it pauses as `MEASUREMENT_CHANGED` before
scheduling another model call. Resumption requires restoring the observed conditions, starting a new measurement
identity, or cancelling.

## Host-Based Execution

To serve G7, the optimizer delegates nested agent invocations through the existing runner skill for the current
harness. It does not implement its own Codex, Claude, process, or platform runner.

Trials use the user's host environment and may invoke arbitrary installed command-line tools, language runtimes, and
configuration needed by the prompt under test. The optimizer neither requires a Docker image nor packages an immutable
infrastructure runtime. It fingerprints directly invoked executables or records their exposed versions when practical,
but does not attempt to discover all transitive binary or configuration dependencies.

External and nondeterministic task data, including remote tool responses, is allowed. CAT records its observable
identity and material response when possible and narrows the result's applicability when it cannot. Its existence is
not a reason to claim deterministic replay or to reject an otherwise valid prompt-optimization use case. Remotely
supplied governing instructions follow the certification boundary above. These rules serve G5 and G7.

Before a trial uses an external service, CAT confirms that its credential is read-only or its target is a disposable
fixture. It rejects known calls that would write persistent service state. It also rejects known writes to persistent
host paths outside the trial copy, including those reachable through a host executable. A known ineligible path is an
admission failure, not a semantic trial result. CAT does not promise to intercept every effect of arbitrary host
executables or services; it records observed effects and discloses unobservable ones in the result's limitations.

## Interruption, Resumption, and Cancellation

To serve G6, pressing ESC or otherwise interrupting the active workflow stops scheduling new model calls promptly and
requests cancellation of in-flight work through the runner's supported mechanism. Completed valid trial outcomes,
candidate snapshots, the confirmed contract and acceptance policy, formal assignments, and the campaign allowance
index remain durable.

On the next invocation in the campaign's starting harness, CAT offers to resume or cancel the campaign. Resumption
continues from the retained state and does not repeat completed valid work. Cancellation closes the active campaign
and schedules no further calls, but it does not erase its history or reset its allowance if that campaign is later
resumed. Starting an explicitly new campaign follows the separate reset rule above, even when it uses the same
confirmed claim. At most one resumable campaign may exist for each canonical project root and target path.

A campaign stays bound to the harness in which it began. An invocation from the other installed harness shows the
existing campaign and its starting harness, then directs the user to continue or close it there. That invocation
cannot resume or advance the campaign, allocate its formal allowance, dispatch a model call, or start a concurrent
campaign for the same canonical root and target. The refusal changes no campaign state, evidence, history, or allowance.
After the campaign is closed in its starting harness, a separately authorized new campaign follows the ordinary
new-campaign rule.

Campaign state must distinguish at least:

- confirmed versus unconfirmed contracts;
- development versus formal evidence;
- assigned, completed, invalid, and retryable trials;
- infrastructure failures versus compliance outcomes;
- campaign, candidate, lineage, and formal-attempt identities and the campaign allowance index;
- the campaign's starting harness, retained across restart and target relocation;
- the chosen acceptance policy, four displayed outputs, acceptance decision, and any explicit override;
- a detected unowned optimization-worktree change that awaits user direction; and
- current versus historical certifications.

## Acceptance Evidence

The feature is complete only when the following observable gates pass. Unit evidence may establish local calculations,
but it cannot substitute for the named integration boundary.

| Goal | Required evidence |
|---|---|
| G1 | An end-to-end scenario rejects a new campaign from a base worktree with staged, unstaged, or untracked changes before forking or trials, explains how to clean it and retry, then forks a clean base worktree, improves a deliberately deficient prompt system in the optimization worktree, leaves unselected attempts and uncertified changes out of the base worktree by default, and merges an accepted certified candidate’s content and structural changes into the base worktree as a reviewable Git diff. A plateau or other uncertified outcome leaves the base unchanged unless the user explicitly chooses a permitted uncertified merge, which remains labeled `not certified`. Certification alone cannot bypass the acceptance policy or clear-regression decision. Disjoint base edits made after the fork survive without user intervention; same-file changes stop for user direction. Authorized linked file and directory edits remain isolated during development and certification; external links permit existing-file content edits only, while in-project ordinary prompt-file create, rename, and delete remain supported. Existing symlink entries, their destinations, and existing file modes stay fixed. Accepted in-project and explicitly authorized external content edits receive one automatic, conflict-checked delivery after full combined preflight. An unauthorized external-link write, disallowed structural edit through an authorized external link, in-project symlink-entry change, or existing-file mode change when observed as an operation or final delta rejects the whole attempt before candidate or trial admission; the reported reason identifies the prohibited operation and scope. Commit, revert, and revision remain user choices. |
| G2 | Contract extraction lists every fixture requirement and source, including readable prompt files and directories reached through symbolic links, with observable link and resolved-target identities and loading relationships. It distinguishes authorized editable targets from external targets that remain read-only without explicit authorization, records the existing-file-content-only scope for authorized external links, and distinguishes readable file instructions from remotely supplied governing instructions and ordinary remote task data. It rejects external structural edits and link retargets while keeping in-project symlink entries, their destinations, and existing file modes fixed. It reports unsupported certification without silently omitting a known instruction source or material response-contingent obligation. Optimization refuses to start before confirmation; feedback requesting additions, removals, category changes, and variation-probability changes produces a new complete proposal, and only its confirmed version governs subsequent trials. Diagnosis that identifies a needed edit to an unauthorized external linked target or a barred structural edit through an authorized one warns of the affected requirement, path, scope limit, consequence, and viable choices without claiming complete detection. A post-formal material revision waits for a terminal or explicitly stopped attempt and reconciled outstanding work. |
| G3 | Statistical fixtures prove certification requires every category's frozen-judge pass-rate lower bound to exceed 95%, and the user-facing claim names the confirmed benchmark and conditions without asserting actual requirement compliance or evaluator calibration. Attempt allowances follow the unlimited campaign sequence; restarts, resumed cancellations, and in-campaign contract revisions cannot reset it; an explicit new campaign resets it while retaining history and reporting the per-campaign scope. |
| G4 | Tests prove formal instances are assigned only after verified optimization-worktree promotion and candidate freeze, trial-private changes do not revise the assigned candidate, generators reject out-of-contract output, prewritten multi-turn sequences and incidental values are assigned before execution and cannot change in response to agent replies, targeted adaptive diagnostics cannot enter representative/formal counts, development evidence cannot enter formal evidence, semantic outcomes cannot be discarded through retries, the selected statistical method remains valid under its supported stopping rule, continuation/new-attempt choices cannot launder prior outcomes into a new test, and calibrated caps terminate inconclusive attempts without relaxing certification. |
| G5 | A clean fork baseline is retained; a disjoint base edit to another participating prompt source made after the fork merges but makes the combined result historical. Changing each recorded material condition makes the result historical while preserving it; combined base/external delivery must be fully verified before a result is reported delivered or current; candidate-versus-original comparison reports named material differences and is unavailable when required comparability data is missing; a starting-snapshot fingerprint is not presented as a receipt for bytes consumed during a trial; an unobservable provider change remains an explicit limitation rather than a reproducibility claim. |
| G6 | Interrupt-and-resume tests stop new calls, retain completed work and formal assignments, recover candidate promotion and the journaled combined base/external delivery without duplicate or conflicting application, including interruption between destination writes and a concurrent live edit; resume without duplication even when the base acquired changes after the fork, and preserve the campaign allowance and acceptance policy. An explicitly stopped formal attempt cannot resume after a contract revision request; an unknown native disposition delays that revision. An observed outside edit in the optimization worktree stops scheduling and requires user direction; cancellation schedules no further calls. Cross-harness invocation discovers the existing campaign and its starting harness but refuses to resume, advance, dispatch, allocate allowance, or start a concurrent campaign without changing its state; restart retains the same harness binding. |
| G7 | Organic end-to-end tests in both installed harnesses invoke a real nested agent through each harness's existing runner, exercise production prompt loading, and permit a prompt test to use a host tool without Docker, runtime packaging, or binary dependency parsing. |

The G2/G3/G4/G7 acceptance inventory additionally requires a text-output case and a file/tool-effect case whose open-ended
behavior is judged by the frozen semantic rubric; an objective assertion alone cannot pass either case. An
image/audio/video output obligation is rejected from this version's full certification claim. A remote/MCP response
used as task data remains an evaluated input, while the same channel observed supplying governing instructions follows
the unsupported-source result. A real host-tool trial with a read-only service credential and one with a disposable
external fixture must be eligible; missing service authority and known writes to persistent host paths outside the
trial copy or persistent service state must be refused before dispatch, without counting a semantic outcome. Reports
must identify effect observation limits and make no containment promise for arbitrary executables. The statistical
fixtures and user-facing report must identify the 95%/95% target as future frozen-judge pass rate under the confirmed
benchmark and conditions, without claiming actual compliance or evaluator calibration.

The G2/G4/G5/G6/G7 acceptance inventory requires authentic trials in both installed harnesses where the tested agent
uses a child, a child delegates again, and parallel children work in separate native Git worktrees before their work
merges into the isolated trial. Child-visible instructions, text, file/tool effects, and merged work inform one frozen
judge outcome and one statistical count under the assigned candidate. A child-worktree edit may change the trial copy
but never the frozen candidate, base, or selected optimization worktree. Exercise child failure, cancellation, retry,
and restart while preserving the one-trial identity and without silently dropping an effect or counting a child as a
new sample. Test a known child remote-governing source and a known persistent external write against the existing
source-role and effect eligibility limits. Record unavailable child or merge observations as limitations and do not
claim a native capability merely because a child was requested or started.

The G1/G2/G5 acceptance inventory also requires a campaign with conditional and later-loaded prompt files whose
loader configuration is recorded at the clean fork and remains fixed through candidate authoring, trial staging,
formal evaluation, and accepted delivery. An authorized ordinary in-project prompt-file edit and create/rename/delete
remain eligible; an attempted loader-configuration edit is refused without candidate or trial admission. A diagnosed
need for a different loading rule directs the user to prepare that change before a new campaign. An observed change
to the loading configuration during the campaign triggers the material-change and historical-result behavior.

Linked-source acceptance additionally verifies exact candidate snapshots and trial staging for authorized file and
directory links, including an explicitly authorized external target, without mutation of the base or live external
target before delivery. It verifies that external file links and existing files beneath external directory links allow
content edits but refuse create, rename, delete, mode change, and link retarget attempts without admitting a candidate
or compliance outcome. Observed need and attempted prohibited edits produce the precise scope warning, including
viable choices other than reauthorizing an already authorized external target; in-project ordinary-file create, rename, and delete remain supported. Attempts to create, delete, or retarget an in-project symlink or change an existing prompt file's mode are forbidden by the generation prompt and rejected when observed or present in the final delta before candidate or trial admission. It checks link/target changes as material
conditions. Accepted delivery preflights the full
combined change set before any write, applies disjoint in-project and external changes automatically, refuses
conflicts or changed destinations, and survives a fault between destination writes without duplicate application or
overwriting a concurrent user edit. Only a verified combined result is reported as delivered or currently certified.

Additional evidence-display and decision gates serve G1, G3, G4, G5, and G6: both phases expose all four outputs with
valid phase labels and explicit unavailable values; original comparison retains baseline uncertainty; development
reuse cannot create formal evidence; each decision-table row, preselected policy, clear regression, unavailable
comparison, valid continuation, new attempt, and explicit uncertified override reaches the correct action. Restart
preserves pending choices and delivery authority without repeating tests or automatically accepting a retained result.
Readiness checks cover an early reproducible defect, weak/uncertain/ready boundary cases, the 100-trial and latest-two-
batch rules, selective evidence collection in unresolved categories, and the unresolved 200-trial review. They prove
that edits cannot inherit old readiness counts, retries cannot duplicate outcomes, and a review cannot falsely declare
a plateau. Default-policy checks show original measurements reused without false precision, exploratory comparison
labels surviving certification, and acceptance on minimum certification without mandatory formal superiority trials.
Distribution checks cover the automatically proposed balanced benchmark, its explicit assumed-usage limitation,
user confirmation or revision of probabilities, rare-use coverage, and frozen within-category weights before sampling. Formal-draw
checks prove that development outcomes cannot be relabelled, formal draws follow freeze, retries/resume preserve
assignments, and a revised candidate cannot inherit earlier certification observations.

The terminal acceptance gate is the unrestricted project build plus the complete prompt-optimizer acceptance inventory
above. A mock-only run, a successful contract extraction, a candidate diff, or a statistical unit test is intermediate
evidence and cannot by itself establish the product outcome.
