# Prompt Optimizer Implementation Plan

## Design Goals

- Implement every outcome and acceptance gate in [frontend.md](frontend.md), with each requirement assigned to a
  responsible component and a check of its observable behavior.
- Derive component boundaries, sequencing, and verification from the product contract, independently of the current
  optimizer implementation, and resolve integration assumptions before dependent implementation begins.
- Deliver a user-confirmed optimization workflow that develops and certifies in a forked optimization worktree, merges
  accepted certified changes into the base worktree when changed files do not overlap, and preserves statistical history and resumable work;
  declare feature completion only after the unrestricted build and the complete acceptance inventory pass.

## Authority and scope

This is an implementation plan for the **Prompt Optimizer Product Contract**, despite the filename. It does not propose
a browser application. [frontend.md](frontend.md) is the authoritative product source. Its definitions, G1–G7, scope,
statistical claim, failure behavior, and acceptance gates apply throughout this plan.

Current product source: the 2026-09-29 [frontend.md](frontend.md), SHA-256
`3c2a6ad510414bab4f96b9c394ca59fc8ebcb1de08f871708a32ccbf2a4bade3`. The user's instruction is to design the best
implementation for this contract without trying to reuse the existing optimizer. Existing optimizer classes, schemas,
tests, and package subdivisions impose no design constraints. Do not turn the assignments into an incremental repair
of that implementation. Preserve unrelated uncommitted work when executing the plan. The documents named
`frontend.outdated.md` and `frontend-implementation.outdated.md` are not implementation instructions.

This document assigns the implementation and its evidence gates. It does not claim that the product is implemented or
that its acceptance checks have run. Product and integration gates remain **unrun** until their recorded checks pass.

## Current implementation status

**Closure policy:** A local closure requires its production path, focused behavioral check, and applicable quality
check on the current inputs. Native and installed claims require evidence at those boundaries. A component closes only
after every assigned obligation and combined acceptance gate passes. Revalidate evidence when its determining inputs
change; preserve unaffected verified work. Historical receipts do not set current closure states.

| Component | Current closure state | Restored-tree audit and next evidence |
|---|---|---|
| W0 — integration capabilities | **Open.** | Shared adapter/source lifecycles, both retained readers, native answer agreement, and both adapters pass local focused/full checks. Both inspection launchers pass generated-artifact checks. Authentic host handoffs and installed native flows remain unverified. Complete P2, P4, P5, and P6 remain open for their assigned combined gates. |
| W1 — campaign execution | **Open; prior local closures reopened.** | Source and tests for the fork, inspection, observation, and recovery slices are present. Their current focused Maven `verify` subset passes; the real I1 and H9 checks, scheduling-bypass audit, and remaining component gates stay open. |
| W2 — contract preparation | **Open; prior local closures reopened.** | Current contract, evidence, policy, and category-assignment tests pass in the unrestricted reactor build, including the reduced source-backed review test and direct checkpoint policy tests. Complete derivation, H2, and the W2/W4 handoff remain open. |
| W3 — trials and evidence | **Open; prior local closures reopened.** | Current focused transport, admission, evaluator-request, sterile-workspace, and initial-trial dispatch tests pass. Other local slices still need their current checks. The last installed Codex evaluator probe failed input access, so it cannot establish H6 semantic judgment. |
| W4 — candidate worktrees and delivery | **Open; prior local closures reopened.** | Current selected-state replay, first-candidate admission, initial-round private-worktree, selected-worktree registration, and trial-dispatch checks pass. Other initial-candidate slices still need revalidation. Private authoring, external-link staging, native P2, promotion, and H10 delivery remain open. |
| W5 — development and certification | **Open; prior local closures reopened.** | Current sequential arithmetic, initial-round checks, and the stateless adaptive formal-category selector pass. Formal admission, durable category choices, formal assignments, and persisted formal outcome adoption are missing; current semantic-outcome commits accept development only. Complete rounds and certification remain open. |
| W6 — reporting | **Open.** | No component closure is recorded; the four outputs and combined applicability still need their consumer checks. |
| W7 — skill and distribution | **Open; prior local closures reopened.** | The 2026-09-29 Codex v1.1 cache refresh passed artifact verification and byte comparison with its verified build. The complete installed flow in both harnesses remains open. |
| W8 — acceptance inventory | **Open.** | Run every mapped acceptance case and the unrestricted reactor build on the final tree. |

This table is authoritative for component closure. Update each row only from its matching current evidence; local
builds and source presence do not pass W8's installed or complete acceptance gates.

### Verified local implementation and remaining work

The common inspection command and both native adapters are implemented. Shared source preparation and retained bridge
lifecycle own startup, cold reconciliation, cleanup, and precise terminal failure. Both retained readers validate native
identity, event archives, observed models, and bridge/native result agreement. Late finalizer failure does not erase a
valid completed native result; zero-turn settlement still requires its explicit owned proof. Claude does not claim
unobserved effective effort or unsupported tool/settings authority.

The Claude and Codex `optimizer-inspect` launchers resolve through the generated artifacts and reject missing host
identity through shared validation. Focused compile/quality checks, unrestricted Maven `verify`, and six artifact Bats
checks pass; the opt-in authenticated Codex smoke check is unrun. The Claude fixture emits explicit successful native
result dispositions. These are local availability and fixture checks, not installed optimizer acceptance.

The current W0 implementation frontier is the host-observation handoff:

1. Shared inspection-command recognition and quoting are implemented; retain the accepted producer below.
2. Implement Claude's current-tool model lookup and native hook consumer from that accepted snapshot. Match the exact
   parent session and tool identity; handle asynchronous incomplete records with a bounded waiting/failure contract.
   Do not substitute the configured model alias or invent effective effort.
3. Register and exercise authentic hooks in both installed harnesses. Verify invocation identity, matching interruption,
   restart, native disposition, adoption, and closure before passing their behavior gates.
4. Continue the combined P2/P4/P5/P6 checks with their named W3/W4/W5 producers. Availability does not pass those gates.

### Native evidence retained for the current frontier

| Evidence | Established observation | Remaining boundary |
|---|---|---|
| Isolated CAT Claude runner probe | Native PreToolUse provides session, prompt, tool-use and transcript-path identities, but no model/effort. The runner disables native session persistence, so its transcript path is absent. | It cannot supply the persisted host model needed by the ordinary host hook. A Bash failure in that probe is separate from metadata availability. |
| Normal persisted Claude probe | The transcript exists at PreToolUse, but an immediate read has no assistant message. | The first read alone cannot identify the current model. |
| Persisted Claude lag probe | On the ninth sample, after 0.084612 seconds, the transcript contains an assistant with matching sessionId, isSidechain=false, and the exact tool_use ID. Its model and UUID agree with the emitted native assistant event. The native result succeeds with empty stderr. | One print-mode observation gives no guaranteed latency or complete interactive-host acceptance. The observed model is gpt-5.6-luna despite the haiku launch alias; perTurnEffort is null. |
| Earlier installed inspection and runner observations | Native success, interruption, retained recovery, source loading, and limited child delegation have bounded historical observations. | Revalidate determining inputs and complete the assigned combined/native gates; no component closure follows from those receipts. |

The current probe artifacts are under `.cat/work/temp/retained/claude-host-observation-20260930`,
`.cat/work/temp/retained/claude-persisted-host-observation-20260930`, and
`.cat/work/temp/retained/claude-model-lag-observation-20260930`. The frontend acceptance audit consumes them and owns
cleanup after incorporating terminal receipts. Embedded execution paths preserve their captured values. Official
Claude hook documentation is at https://code.claude.com/docs/en/hooks#common-input-fields; selected model-change
notifications do not establish the actual model of a one-turn fallback.

### Unit-test runtime status

Aim for less than one second per unit test without sacrificing its claim, production functionality, or readability.
Preserve policy-boundary input counts using lightweight inputs; process only what the claim requires. Decompose at real
production responsibilities and reuse existing checks instead of cloning full campaign fixtures. Keep distinct source
authorization, indexed retry, cold recovery, and consumer wiring checks. Accept a longer measured runtime only after
unnecessary test work and straightforward production inefficiencies have been addressed.

| Current claim set | Retained change and evidence | Runtime status |
|---|---|---|
| Generation intent identity and lifecycle | Direct typed-intent equality tests replace the broad foreign-intent comparison. Real current-source and Git authority remain in the lifecycle test. | Direct checks take about 0.04 seconds; the broad scenario remains about 21 seconds. |
| Private-worktree preservation and generation | SelectedWorktreeReplayTest checks unowned-file preservation in both complete and missing-prompt checkouts. The redundant separate Git fixture was removed from the generation scenario. | Last paired total: 22.014 → 21.752 seconds, including direct identity cases; no stable speedup claimed. |
| Weak-followup decision index | Direct production index tests cover matching, missing and foreign references. Cold choice/draw retry, assignment-index repair, weakness and dispatch guards remain in the broad scenario. | Direct checks are below 0.41 seconds; broad remainder 10.879 seconds. All replacement cases total 12.040 versus 11.144 seconds, including added matching/foreign checks; this is no overall reduction. |
| Evaluator preparation and compliant/noncompliant counting | Counting variants skip only the repeated terminal completion-index deletion/rejection; the uncounted request/recovery scenario retains it. Counting, cold retry, outcome-index faults and adoption checks remain. | Paired total: 19.640 → 18.846 seconds. All six evaluator methods and the unrestricted reactor pass; the target is unmet. |
| Adaptive-choice lifecycle | Direct aggregate-policy selection is already covered in milliseconds. The 150 outcomes are synthetic indexed evidence, not 150 native evaluations. | Last focused broad run: 9.268 seconds. Further reduction needs a coherent source-authorized orchestration boundary; raw record persistence or multiple full fixtures would weaken claims or increase work. |

Other accepted reductions include one-operation evidence/choice validation reuse, mapper reuse, direct checkpoint and
batch-policy tests, smaller counted-source fixtures, and isolated argument/attempt/index assertions. Reuse is confined
to the same immutable reference and immediately validated operation; do not carry authority across reopening, locks,
external calls, native authoring, or possible source mutations. Repeated Git observations separated by publication,
replay or external mutation are not removable merely because their values happened to agree.

Before accepting another runtime change, retain the original claim-to-assertion mapping and compare the cumulative
setup, cleanup and method times of the same affected claim set. A shorter individual replacement or concurrent-suite
variation does not establish less total work. The remaining generation, weak-followup and adaptive-choice scenarios
stay in their required verification gates.

### Shared inspection-command producer

Status: accepted local implementation. OptimizerToolCommand owns both existing recognized launcher shapes and exact
POSIX quoting. CodexOptimizerPreToolHook delegates to it while retaining native identity validation and output behavior.
The helper's 13 direct tests and the Codex hook's four consumer tests pass; the measured focused methods are below
0.13 seconds. The unrestricted Maven verify returned exit 0. After declaration-order correction, the focused verify
passed again. PMD reports existing analyzer crashes in Campaign.java; complete quality coverage remains open and this
producer does not close W0 or W8. Accepted helper SHA-256:
`b5403f4fa604ef480d7fb5c07067a1159a12970219b0bd768408ad4311cb9af1`.

### Next implementation cluster: Claude current-tool host identity

Status: designed, not launched. Native model aliases are insufficient: the retained probe demonstrates that the actual
assistant model can differ. Read only the native transcript supplied by the current PreToolUse event and correlate its
explicit parent session and exact tool-use identity before binding the shared inspection command. An unrelated Bash
command needs neither host identity nor transcript access. A subagent or another session must never supply the model.
This is local selection over parsed native records using the existing strict Jackson mapper, not a new JSON codec.

Exclusive write allowlist: new HostToolModel.java and ClaudeOptimizerPreToolHook.java under claude/cli main's existing
io/github/cowwoc/cat/claude package; their two matching tests under the same test package. No registration, distribution,
installation, cache, campaign, or shared-helper edit belongs to this cluster. These are the next integration consumers
of the accepted OptimizerToolCommand snapshot. Parent owns later hook registration and authentic native acceptance.

Package-private final HostToolModel owns native-file model lookup. Its static findModel(BufferedReader records,
String session, String toolUse, boolean lastRecordComplete) returns Optional<String>, rejects null inputs and blank
identities, and leaves reader cleanup with its caller. Stream one record at a time with one-line lookahead. Use the
existing strict-duplicate-detection JsonMapper; parse before inspecting fields. Only assistant objects with matching
sessionId, explicit boolean isSidechain=false, and message.content containing a tool_use object with the exact id are
matches. Require a nonblank message.model on each match; reject differing models across matches. Ignore other sessions,
sidechains, missing/nonboolean sidechain markers and unrelated records. Malformed complete records fail with IOException;
a malformed final record may remain pending only when lastRecordComplete=false. A valid complete JSON value may match
without a trailing newline. Do not silently treat a missing sidechain marker as false.

HostToolModel.awaitModel(Path transcript, String session, String toolUse, Clock clock, Pause pause) returns the exact
model or throws IOException. Pause is a package-private functional interface, void await(Duration delay) throwing
InterruptedException. Validate all inputs before opening the file. Read UTF-8 with BufferedReader and inspect the final
file byte through a JDK seekable channel to establish newline completion; close both resources per attempt. A missing
file or absent matching record is pending, with an immediate first read and 100-millisecond pauses, for at most five
seconds according to the supplied Clock. Other IO/complete-record failures are immediate. Interrupted waiting restores
the interrupt flag and fails. The production hook supplies Clock.systemUTC() and a real timed pause; tests advance an
owned Clock in Pause and never sleep. This deadline is a failure boundary, not a claim that Claude always flushes in time.

Public final ClaudeOptimizerPreToolHook has main(String[] args), public static run(InputStream input,
OutputStream output), and a package-private run overload accepting HostToolModel.Lookup. Lookup is a package-private
functional interface with String read(Path transcript, String session, String toolUse) throwing IOException. The public
run supplies the production await operation. Accept only an object PreToolUse Bash event whose command the shared
helper recognizes; every other valid event produces no rewrite and never calls Lookup. Then require nonblank native
session_id, prompt_id, tool_use_id and transcript_path. Lookup supplies the model, and bindHostIdentity uses prompt_id
as the host turn. Preserve every tool_input member while changing command. Success emits hookSpecificOutput with
hookEventName=PreToolUse and updatedInput, without auto-approving permission. Identity/model lookup failure emits
permissionDecision=deny and an actionable permissionDecisionReason. Malformed event/input or output IO throws IOException;
main accepts no arguments, writes failure diagnostics to stderr and exits 2 on failure, as Claude documents for a
blocking PreToolUse hook. Do not print diagnostics to stdout or grant permissions.

Tests directly exercise matching parent records, unrelated sessions/tools, sidechain and omitted marker rejection,
conflicting and missing models, malformed complete and partial-final records, delayed appearance, missing-file timeout,
interruption, and null/blank boundaries. Hook tests inject Lookup to prove exact session/tool/path selection, prompt_id
binding, preservation of other input members, no lookup for unrelated commands, and denial on missing authority.
Use only the smallest in-process record/file fixtures needed for each claim; no native harness, runner or campaign.
Before acceptance run disabled-cache Maven verify with -pl claude/cli -am,
-Dtest=HostToolModelTest,ClaudeOptimizerPreToolHookTest and -Dsurefire.failIfNoSpecifiedTests=false, require exit 0,
record method XML timings and changed paths, then git diff --check. Parent reviews all four paths and runs the combined
reactor before accepting this consumer; installed/native gates remain separate.

## Confirmed scope decisions

The user kept the per-category 95% frozen-judge pass-rate claim at 95%
confidence under the confirmed benchmark and conditions, and full multi-file prompt-system support, including in-project content and ordinary-file create/rename/delete changes while keeping existing symlink entries, link destinations, and existing file modes fixed;
explicitly authorized external linked targets are limited to content edits of existing files. The user
requires CAT to fork the original base worktree only when it is clean at new-campaign start, conduct optimization and
certification in that fork, and merge accepted certified changes back only after certification and the campaign’s
acceptance decision. At a plateau, CAT leaves the base unchanged and retains the best optimization candidate for review;
it merges an uncertified candidate only through the separately labelled, permitted explicit user choice and labels it
`not certified`; that choice is not restricted to a plateau. A requested
or automatic merge proceeds without further intervention when files changed in the optimization worktree do not also
have base-worktree changes made after the fork; overlapping files require user direction. A nonclean starting base
worktree is rejected before forking or trials, with instructions to resolve its changes and retry.

The user chose detection of unowned changes to the optimization worktree at CAT's observable checkpoints. CAT stops
and asks for direction on a detected change; a transient edit reverted before observation may escape detection. The
contract does not require exclusive operating-system write control.

The user also chose CAT to propose the complete compliance contract for review, including requirements, categories,
tests, expected outcomes, and evaluator rules; the user confirms or revises it before optimization.
The user gives feedback rather than editing individual proposal fields. CAT regenerates and presents a complete
proposal after feedback, including requests that add, remove, or change any contract element, until the user confirms it.

The user retained the four outputs' candidate-versus-original comparison, including uncertainty and checks that the
underlying observations can validly be compared. CAT still reports current and historical certification status and
named material changes. There is no separate caller operation for comparing arbitrary saved results across candidates,
campaigns, or observation times.

The user kept conditional and later-loaded prompt files in the supported multi-file scope. Contract derivation,
development trials, and formal trials must follow their production loading behavior and preserve the stated
observation limits. The loader configuration that determines which prompt files load is fixed for the campaign and
cannot be edited by CAT as part of a candidate or delivery. Authorized prompt-file edits and ordinary in-project
prompt-file create/rename/delete remain supported. A desired loading-configuration change is prepared by the user
before a new campaign; an observed change during a campaign follows the material-change rules.

The user kept readable participating instruction files outside the forked project, including user-level rules, and
chose to allow optimization of prompt files and directories reached through symbolic links. CAT derives their
observable requirements and records link paths, resolved targets where observable, source identity, and loading
relationships. Linked targets inside the project may be editable under the confirmed authority. A resolved target
outside the project is editable only with explicit user authorization for that target and scope; otherwise it stays
read-only even if the link lies in an editable root. In this version, an authorized external file or directory link
allows content edits to existing resolved files only; external create/rename/delete, mode changes, and link retargets
are outside the edit authority. In-project ordinary prompt-file create/rename/delete remain supported, but symlink entries, their destinations, and existing file modes stay fixed. Authorized external-target changes remain isolated in candidate
and trial snapshots and cannot mutate the live external target during development or certification. If diagnosis
identifies a needed edit to an unauthorized external linked target, or authoring attempts one, CAT warns of the
affected requirement or attempted edit, link path, observable resolved target, consequence, and choices. It does not
promise exhaustive diagnosis; prompt restrictions and complete attempt-inventory validation reject observed forbidden operations or final deltas before candidate admission. The same
bounded warning applies when observed diagnosis needs, or authoring attempts, an external structural or link-retarget
operation through an already authorized link. It names the existing-file-content-only limit and choices to edit
existing content if sufficient, use another authorized in-project source, or stop/revise; reauthorizing that target
does not broaden its scope in this version. After
acceptance under the campaign policy or a permitted explicit uncertified override, CAT automatically delivers
explicitly authorized external-target changes with in-project changes under one combined conflict preflight, journal,
recovery path, and final snapshot check. A conflict or changed target stops unsafe writes and retains evidence for
user direction. The user limited certification to governing instructions from readable
files. A known remote/MCP response used as governing instructions makes certification unavailable;
ordinary remote task data remains allowed, even when its text contains commands. CAT distinguishes source roles from
available evidence, records observation limits, and cannot claim to detect hidden host behavior.

The user kept certification of multi-turn use categories with fixed, prewritten turn sequences. A representative or
formal logical trial assigns every turn and incidental template value before execution, runs in one fresh session,
and yields one semantic outcome for its category. A later user turn cannot be chosen or rewritten from an earlier
agent reply. Targeted adaptive diagnostics remain development-only and cannot enter representative or formal counts.
A material obligation requiring response-contingent follow-ups is a disclosed coverage gap that makes certification
of the full claim unavailable until the contract can cover it.

The user kept the same optimizer certification workflow in both currently installed harnesses, Codex and Claude.
Each campaign stays bound to its starting harness. The other harness may show the existing campaign and its starting
harness, but cannot resume or advance it, allocate allowance, dispatch a call, or start a concurrent campaign for
the same root and target. It directs the user to continue or close that campaign in the starting harness, without
changing its state, evidence, history, or allowance.
The user confirmed that certification may invoke arbitrary installed host executables under the starting harness's
permissions. Trials may certify text and observable file/tool effects, including behavior that uses remote/MCP task
data; generated image, audio, and video outputs are excluded in this version. Open-ended text and effects retain a
frozen semantic evaluator, so objective assertions alone do not establish compliance. Eligible writes stay in the
trial copy or a disposable external fixture; known writes to persistent host paths outside the copy or to persistent external service state are
ineligible. Each external service used needs confirmed read-only credentials or a disposable fixture before trial
admission. CAT refuses known persistent-write paths and reports effects it cannot observe as limitations; arbitrary
host executables are not technically contained by this promise.
The tested trial agent may use native subagents recursively and in parallel, including separate child Git worktrees
whose work merges into its isolated trial copy. Child instructions, results, text/file/tool effects, and merged work
feed one logical trial judgment and count once. This does not expand candidate-generation agent authority. Native
child completion, merge, cancellation, and observation capabilities remain premise work, not passed gates.

The user confirmed that no representative usage records or examples will be supplied. CAT proposes an explicitly
labelled balanced benchmark for user confirmation or revision; it does not estimate frequencies from usage data.

The user kept automatic development across successive rounds. When a completed round promotes a stronger candidate
that is still below readiness, CAT starts the next round under the confirmed policy without another user request.
The user also chose to try every currently failing material category before declaring a development plateau. One
complete round must give each such category a bounded diagnosis and candidate-generation attempt; only a round with
no distinct valid candidate supported by stronger development evidence can establish plateau. Infrastructure failure
or an interrupted or incomplete round cannot establish it.

The user kept material contract revision within an existing campaign. CAT presents another complete proposal; once
confirmed, the revised claim starts a new statistical lineage and earlier trials remain historical evidence. The
campaign-wide formal-attempt allowance continues across those lineages. After formal testing begins, revision starts
only after the current attempt ends or the user explicitly stops it for revision, and all in-flight calls, assignments,
promotion, and delivery work have reconciled dispositions. A stopped attempt retains valid outcomes and its spent
allowance, cannot resume, and is labelled `not certified: stopped for contract revision`. An unknown native disposition
keeps the revision pending until reconciliation. This safe-checkpoint timing is a confirmed scope decision.

Keep licensing, entitlement, billing, analytics, cost prediction, releases, publication, product feedback submission, telemetry, Docker,
runtime packaging, new harnesses/platforms, and work-executor orchestration outside this feature. Build and installation
checks validate the existing distribution mechanism; they do not add a release product. Implementation tracking in this
document is a development aid, not optimizer product behavior.

Current statistical policy uses campaign-wide unlimited allowance splitting with explicit reset across campaigns,
four evidence outputs in both phases, and acceptance separate from certification under a policy confirmed before
trials. Defaults are minimum-only acceptance with the clear-regression safeguard, original measurement during
development with comparable evidence reuse, and the product contract's development readiness/checkpoint rules.
Anytime-valid evaluation uses a finite cap calibrated for at least 90% certification power at a 99% planning rate.
Adaptive development has a coverage floor, fixed within-category generators and adaptive formal category scheduling;
fresh formal evidence protects a candidate selected through development. The detailed policy and gates below govern
implementation; earlier fixed-sample arithmetic does not verify the selected sequential behavior.

## Optimizer design record

Maintain `client/common/cli/src/main/java/io/github/cowwoc/cat/common/optimizer/DESIGN.md` in the package root of the
created optimizer. W0 seeds it from the current product contract and this plan, explicitly recording the clean-base
campaign-start requirement, the separate treatment of base edits made after the fork, and editable linked files and
directories within confirmed authority, explicit external-target authorization, isolation, observable identities,
the existing-file-content-only external scope, in-project ordinary-file create/rename/delete with fixed symlink entries/destinations and existing file modes, unauthorized-write
warnings/admission rejection, and automatic conflict-checked combined delivery after acceptance. W0 records
the review-only patch alternative, rationale for the chosen behavior, affected handoffs, and acceptance evidence.
Each assignment owner updates
it before
closing a slice whose work selects or changes a design choice. Document every accepted optimizer design choice, including
product scope, component responsibilities, interface boundaries, durable identities and evidence, candidate and trial
isolation, statistical policy, failure and recovery behavior, and cross-component handoffs. For each choice, state the
decision, its governing contract source, rationale, consequential alternatives considered, affected components or
interfaces, and acceptance evidence. Identify superseded choices as history, never as concurrent implementation
instructions. A link to this plan alone does not document a choice.

`frontend.md` remains the product authority and this plan remains the execution and acceptance authority. Reconcile any
disagreement among them and `DESIGN.md` before dependent implementation proceeds. W8 audits the design record against
both authorities and the integrated optimizer; an omitted or stale choice leaves its owning assignment open.

## Architecture derived from the outcome

A certified result needs three independently established facts: the user approved the behavior being measured; the
selected development candidate was verified and frozen in the optimization worktree before formal assignment; and
valid formal evidence supports that exact frozen candidate. Only then may CAT merge the candidate's changes into the
base worktree, preserving base-only edits and requiring user direction for files changed on both sides. Durable
campaign execution connects those facts across interruption and restart.
Plateau, interruption, and precise failure are also valid product outcomes; each must retain its evidence and applicable
recovery path rather than imply that certification was achieved.

Use these responsibilities as the implementation boundaries. Names describe responsibilities rather than prescribe a
class per row. Keep common Java behavior under the shared CLI module, native integration in the current harness's CLI
module, and interaction instructions in the prompt-optimizer skill.

| Component | Owns | Does not own | First acceptance boundary |
|---|---|---|---|
| Campaign application | Complete lifecycle operations, legal state transitions, call admission, and sequencing producer results. | Native processes, semantic judgment, or statistical calculations. | Confirmation-to-first-development slice plus interruption. |
| Campaign repository | Atomic campaign revisions, immutable inputs/evidence, canonical target lookup, lineage identities, campaign allowance allocation, and restart recovery. | Choosing candidates or interpreting compliance. | Crash/restart before and after each publication boundary. |
| Contract preparation | Production source discovery, provenance, proposal validation, full presentation data, and user-confirmed contract revisions. | Certifying its own semantic extraction as complete without user review. | Fixture extraction, user revisions, and enforced confirmation. |
| Candidate workspace | Initial Git inventory, optimization-worktree fork, isolated generation attempts, edit authority, exact snapshots, promotion within the fork, and freeze. | Trial execution or automatic commit/revert. | Exact fork, prompt adherence, forbidden-delta rejection, outside-edit stop, and verified freeze. |
| Delivery merge | Policy-accepted certified transfer or explicitly authorized uncertified transfer across the base worktree and authorized external targets, combined conflict decisions, one durable journal, and final snapshot verification. | Candidate selection or automatic commit/revert. | Full preflight before any write, disjoint automatic delivery, conflict refusal, and recovery between destination writes under both acceptance authorities. |
| Trial execution | Validated sampling, durable assignment, isolated execution through the runner, evaluator authority, and exactly-once outcome adoption. | Candidate selection or a model's native protocol. | One assigned trial through native completion, evaluation, and recovery. |
| Optimization policy | H12 category allocation, development readiness, candidate comparison, complete plateau rounds, formal sample plans, campaign allowance use, category decisions, and acceptance policy. | Within-category generation, provider-cost prediction, or hidden changes to the user's claim. | First allocation through retained outcome; development-to-formal-to-development and certification paths. |
| Measurement and results | Observable measurement identity, change detection, original-comparison validity, immutable observations, applicability, and user reports. | Reconstructing a closed runtime or promising deterministic replay. | Material change pauses scheduling and makes a result historical. |

Keep storage and native execution as explicit boundaries because interruption, persistence, and two supported harness
integrations require independently testable behavior. Keep other domain operations direct; do not add a configurable
workflow engine, generic event bus, plugin registry, or an interface for every class.

The product explicitly requires the existing runner, Git, and prompt-loading capabilities. Those remain integration
dependencies. Their observable behavior must be established by P1–P4; their present implementation must not dictate the
optimizer's data model or workflow. Extend a responsible integration only when a required capability is missing.

### Replacement and removal

Remove every old implementation that the new design replaces. Removal is part of the owning assignment's completion,
not optional cleanup after the feature ships. Do not keep parallel implementations, unused abstractions, compatibility
adapters, fallback branches, or dormant entry points merely because they already exist.

W0 creates a replacement inventory organized by behavior. For each affected implementation and supported contract,
record whether it is retained, replaced, or retired; its owning assignment; its callers; and the evidence required for
cutover. Include source, tests, fixtures, public APIs, CLI commands, skill/rule routes, configuration, build dependencies,
module exports, manifests, launchers, generated help, installed artifacts, documentation, and recovery paths. This
inventory determines removal scope; it does not constrain the new architecture to the old implementation.

Each assignment that replaces behavior must:

1. Establish the new behavior's acceptance evidence and update every supported caller to its authoritative replacement.
2. Remove the displaced implementation and its obsolete tests, fixtures, wiring, dependencies, and documentation in the
   same integrated cutover. Preserve required behavioral coverage by expressing it against the new supported boundary;
   do not retain tests solely to keep obsolete code alive.
3. Verify affected retained entry points still work, and that supported consumers cannot invoke, discover, or install
   the retired behavior. Search remaining references and resolve each against the inventory.
4. Preserve durable user/campaign history required by the product. If persisted plugin data needs conversion, confine
   old-format handling to the installation migration boundary; do not retain the old runtime as a fallback. Verify the
   converted history and recovery path before deleting the displaced runtime. Unrelated user changes remain untouched.

W7 verifies the assembled and installed artifact contains the supported flow. W8 repeats the complete retirement audit
and rejects completion while any replaced implementation or obsolete consumer remains. Historical records and necessary
installation migrations may retain old identifiers with an explicit reason; they are not executable alternatives to
the current workflow.

## Design decisions and evidence prerequisites

### Component responsibilities

Keep deterministic mechanics in the shared Java CLI: file and Git operations, validation, state transitions,
assignment, sampling, evidence classification, statistics, comparison, formatting, and recovery. Keep semantic
extraction, category proposals, diagnosis, candidate authoring, and rubric-based evaluation with agent calls. User
confirmation is the authority that freezes the complete compliance contract.

Use existing runner implementations for every native model call. The optimizer produces typed requests and consumes
retained results; it does not launch or communicate with a model itself. Harness modules own native configuration,
prompt-loading adaptation, registration, cancellation, and write controls. Shared code receives harness-neutral data.

Derive domain records from the authoritative handoffs below. Introduce constrained domain types at caller boundaries for
finite choices such as evidence purpose, trial state, operation, outcome, session policy, and comparison status. Validate
raw CLI/JSON values once. Keep paths, requirement text, and other open-ended inputs open-ended. Test an accepted and an
excluded value at each changed public boundary.

Choose one implementation owner/integrator initially because state, authority, and recovery decisions are coupled.
Independent ready assignments below may advance without waiting for unrelated probes, but shared files have one writer.
This plan does not require additional agents or predict elapsed time.

### State, persistence, and execution model

Represent the campaign as a tagged state with the data required by that state:

- `awaiting contract confirmation` holds the full proposal revision and source snapshot.
- `developing` holds the confirmed contract, lineage, campaign acceptance policy, original baseline,
  optimization-worktree incumbent candidate, current round, and H12 allocation/batch progress; a
  pending internal promotion retains its previous incumbent until that worktree and campaign record agree.
- `formal testing` holds the frozen candidate, campaign formal-attempt index, sequential procedure, calibrated category caps, current bounds/counts, H12 pending category choice, assignment references, and any pending request to stop for contract revision.
- `awaiting outcome choice` holds the four outputs, policy decision, retained candidate/results, and applicable next actions.
- `delivering` holds the immutable certified or uncertified result, delivery authority, merge record, and base-worktree
  verification.
- `interrupted` holds the prior phase, outstanding call identity, and cancellation/reconciliation disposition.
- `MEASUREMENT_CHANGED` holds the prior phase, observed differences, and affected evidence.
- `UNOWNED_WORKTREE_CHANGE` holds the prior phase, observed optimization-worktree differences, and the user decision
  required before resumption.
- `closed` holds certification/acceptance, below-minimum, inconclusive, plateau, cancellation, or a precise failure disposition and its retained history.

Do not encode lifecycle as independent booleans that admit contradictory states. Infrastructure failures preserve a
recoverable phase and evidence where recovery is safe; a semantic compliance failure is an outcome within that phase,
not an infrastructure exception. Expose phase and result separately so a historical certification cannot look like a
new campaign's current state.

Use one on-disk repository per canonical project with a serialized project index and a single owner per active campaign.
Persist the campaign's starting harness with its immutable identity and indexed root/target reference. An invocation
from another harness validates the existing campaign through a read-only lookup, using retained immutable records to
resolve a stale index where possible, then displays its identity and starting harness. If that read-only evidence is
insufficient to identify the campaign, it reports the lookup failure and directs recovery to the starting harness.
The refused invocation must neither change the repository nor acquire campaign execution authority.
Choose immutable revision documents and content-addressed evidence blobs, published by one atomically replaced current
revision reference. A revision includes the predecessor, campaign state, campaign allowance index, lineage identity, durable call/assignment
references, and target aliases affected by the transition. Write and flush blobs/revision before publishing the reference;
validate platform durability at W1. The repository owns journal recovery and validates referenced content on load.
This supplies the required local transaction without a database service or an optimizer-specific distributed system.

The project index maps a canonical root/target to its campaign and maps a campaign/confirmed-claim pair to retained
lineage history. A new explicit campaign may use the same claim without adopting the earlier campaign’s allowance.
Restart, resumed cancellation, target relocation, or a contract revision within a campaign retains its allowance index.
Index updates and campaign revisions require a recoverable transaction: persist the intended old/new references, publish
the campaign revision, then reconcile the index before another owner proceeds. Keep history discoverable by immutable
campaign/lineage IDs; mutable target aliases are lookup aids. Do not allocate an allowance from an index that may be stale.
W1 must fault-test every publication boundary, including index publication, before consumers rely on it.

Keep a model call outside the storage lock. Persist its identity and dispatch intent first, release the lock, and let the
existing runner execute. Publish its retained result under that identity, then adopt it in one campaign revision. A
restart first reconciles that call with the runner before deciding whether a retry is safe. A runner that cannot supply
an authoritative disposition for an uncertain call leaves that call unresolved; it does not authorize replay.

Begin with sequential model calls within a campaign. This avoids candidate authoring overlapping formal drawing and
limits in-flight work during interruption; it is not a claim that all statistical dependence disappears. No concrete
formal instance is generated until authoring has ended and the candidate is frozen. Later concurrency is outside this
initial design and cannot be added without preserving assignment, cancellation, and statistical assumptions.

The application checks stop/measurement state immediately before dispatch under the same admission authority that
records the call. Give the runner's actual start boundary that call's revocable start authorization. Serialize acceptance
of that authorization with interruption: if interruption wins, the runner cannot start the pending call; if start wins,
the call is already registered as in flight and receives cancellation. Persist a stop even when the native handle is not
yet returned. Test interruption after dispatch intent but before native start, during handle publication, and after
completion but before adoption. A stop acknowledgement closes admission; terminal cleanup additionally requires the
outstanding call's known disposition. Do not mistake a persisted dispatch intent for proof that cancellation reached a
native process.

### Design checkpoint

| Concrete case | Analogous case | Boundary case | Resulting decision |
|---|---|---|---|
| Optimize one deficient prompt file. | Optimize a prompt split across rules, configuration, conditional loads, and linked files or directories. | Observe a linked external target that remains read-only without explicit authorization. | Snapshot the participating prompt system and record provenance; confirmed editable authority includes only explicitly authorized external targets. |
| A native call completes just before the optimizer crashes. | A campaign is cancelled and resumed, or its contract is materially revised. | The user explicitly starts a new campaign. | Recover logical work and the campaign allowance; a revised claim creates a new evidence lineage, while only a new campaign resets the allowance. |
| A formal candidate fails one category. | Common cases pass while a rare applicability branch fails. | Development trials guide a revision. | Require every formal category independently; development remains reusable for selection but cannot certify. |
| A failing category needs more development attention. | An uncertain formal category needs further evidence within its cap. | A diagnostician deliberately repeats a known failure or changes the input mix within a category. | Adapt category allocation using prior evidence while preserving the evaluated distribution within each category; targeted cases have a separate purpose and cannot enter representative/formal counts. |
| A trial edits and then loads its private prompt copy. | A lazy external source loads during a trial. | A later formal trial starts from changed candidate bytes. | Record observed paths and limitations without pretending they are byte receipts; changes to the later starting candidate require a new formal candidate. |

These comparisons favor connected behavior slices for confirmation, native execution, and recovery, with shared
components for state and statistical calculations. Splitting solely by screen, class, or source directory would defer
the handoffs that can invalidate the design.

### Premise gates

W0 owns the initial assessment of the following premises. Run each currently available part through the smallest
authentic installed boundary; record exact input, artifact/version identity, observed result, limitations, and
affected decisions. When a complete check needs an unfinished optimizer producer, W0 records the unresolved part and
its first runnable gate below. That producer's owner then runs the complete check before releasing its dependent
behavior. A mock, configuration, or successful launch cannot pass a behavior gate.

| Gate | Fact needed and distinguishing check | Dependent work and failure handling |
|---|---|---|
| P1 — Current-harness execution | Invoke the installed existing runner through the current harness's maintained callable route; retain a native result from production prompt loading and a harmless installed host tool using required host configuration. Check both supported harness adaptations. The later G7 organic optimizer check must prove that the skill uses this route. | W2–W5 native integration and G7. Repair missing registration/exposure in the existing runner integration; do not build an optimizer runner or silently substitute a different harness. |
| P2 — Generation authority | Through both installed runners, create an isolated attempt from the selected snapshot with staged linked targets. Deliver prompt restrictions and exercise allowed editor and shell/tool edits, including ordinary in-project create/rename/delete and existing-file content beneath authorized external links. Run focused forbidden-operation cases for external structural edits, symlink changes, existing-file modes, loader configuration, and unauthorized targets. Record observed prompt-adherence counts and conditions. Independently induce forbidden final deltas and verify complete no-follow inventory validation rejects the whole attempt before H4 or any trial, retaining the precise warning and evidence. Verify selected/base worktrees and live external targets remain unchanged. | W4. Use existing runner isolation and staged target copies; no custom filesystem or extra process sandbox is required. Prompt adherence is measured, not guaranteed. Git diff alone omits relevant state. An unobserved reverted temporary edit may escape detection; forbidden admitted state must not. If live-target isolation is unavailable, keep that linked path unready while independent work proceeds. |
| P3 — Runner cancellation and completion | Through the existing runner's callable boundary, establish native cancellation, retained completion, recoverable call identity, and authoritative worker disposition. Include completion racing cancellation. | Enables the W1 integration slice. This proves runner capabilities only; optimizer admission and user ESC behavior require I1 below. |
| P4 — Loading observations | Exercise startup, conditional, and lazy production loading through file and directory links, including an external target staged in isolation; compare exposed link paths, resolved targets, source content, and loading observations with consumer needs. Check which authentic observations distinguish a readable governing file, a remote response given instruction authority, and a remote response used as task data. Check observable external-service effect and authority signals, and preserve explicit limitations when the host supplies no role or effect event or only paths. | W2/W3/W4/W5/W6. Derivation needs captured readable source material and link/target identity; authoring and trials need proof that production loading sees the staged candidate rather than the live external target. Formal admission needs known source roles and confirmed read-only credentials or disposable fixtures for each external service; reports need honest observed identities and effect limits, not proof of consumed bytes or perfect containment. Missing runtime bytes alone are not a reason to reject the use case, while known remote governing instructions or persistent-write paths prevent certification. |
| P6 — Native trial subagents | In each installed harness, run a bounded authentic trial-agent probe with a child, recursive grandchild, parallel children, native separate child Git worktrees, and a merge into the isolated trial copy. Observe child identities, loaded instructions and source roles where exposed, completion and cancellation dispositions, file/tool effects, merge result, and recovery after parent interruption. Verify that the base and selected optimization worktree remain untouched; record missing host observations explicitly. | W1/W2/W3/W5/W6/W7 need a native child tree and retained completion/effect evidence before claiming one complete trial outcome, with the same external-effect and instruction-source limits as the root. A successful child launch or requested merge is an availability receipt only; it does not pass the behavior or recovery gate. |
| P5 — Statistical applicability | For the selected execution/evaluator profile, document within-category outcome assumptions and evidence concerning sessions, caching, throttling, evaluator memory, adaptive routing, and other known dependence. Review certification under H12 category choices from past evidence, with unchanged conditional within-category draws, retained stopping/cap rules, baseline reuse, and repeated looks/attempts. Review comparison and displayed interval guarantees separately. | W5 certification and inferential comparison admission. Fresh wording or sessions alone do not prove independence. If assumptions cannot be justified, preserve optimization and report formal certification unavailable. Never weaken the claim. |

P1–P4 and P6 are execution capabilities; P5 concerns the statistical interpretation of execution. They require separate evidence.
P6 has bounded direct, recursive, parallel, and child-worktree merge observations in both built harnesses. The
production Git/configuration path, child-visible source evidence, child terminal disposition after interruption,
and restart recovery remain open. A child launch or child-local file alone does not establish a merged trial outcome.
This planning task does not spend provider calls to pass them. No product-policy substitution is selected by this plan.

**First runnable combined premise checks:** W0 may close its assessment with an unresolved P2, P4, P5, or P6 only
when the exact missing observation and the enabling producer below are recorded. This does not pass that premise or
release the dependent behavior.

| Premise | Earlier producer and focused gate | First combined check and dependent release |
|---|---|---|
| P2 native authoring authority | W1 H1 supplies a verified clean fork; W2 H2 supplies confirmed editable targets; W4 H4 supplies an isolated attempt with staged linked targets and a native call bound to it. Focused H4 tests prove snapshots, complete authority validation, retry, and rejected-operation handling. | W4 runs P2 prompt-adherence, isolation, and forbidden-delta rejection checks through both installed adapters at the first H4 native call. No generated H4 candidate or W5 development round accepts an attempt before complete inventory validation passes. |
| P4 linked loading and source/effect observations | W1 H1 captures readable source and link identities; W2 H2 confirms source roles and external-target authority; W4 H4 stages an authorized external target without touching its live destination. W0's in-project linked-source receipts remain bounded evidence. | W2 checks derivation from the available production loading observations; W3 checks the full P4 staged external target and role/effect observations at the first H4-to-H5/H6 native trial. W5 formal admission and W6 reporting use only the observations established there, with explicit host limits. |
| P6 complete child trial and restart | W1 H3 retains native call identity and interruption; W2 H2 records child-visible instructions and authority; W4 H4 supplies the isolated trial source; W3 H5/H6 binds and reconciles the child tree to one trial. Focused tests reject root success with unfinished children, unknown child disposition, and duplicate or missing evidence across restart. | W3 repeats P6 through both installed runners at the first complete H3/H4/H5/H6 child trial, including interruption and restart. W5 may count one child-enabled logical trial only after its complete native effects and disposition are reconciled. W7 repeats the assembled installed route. |
| P5 statistical applicability | W2 H2 supplies the confirmed distribution and frozen evaluator; W3 H5/H6 supplies actual assignment, session, child-tree, evaluator, and outcome observations; W5 H11/H12 supplies the frozen test and adaptive category schedule. Focused statistical fixtures check calculations before native evidence exists. | W3/W5 review the selected method against that retained process before W5 admits formal certification. An unsupported assumption leaves certification unavailable; a local calculation test cannot pass P5. |

**I1 — First runnable optimizer interruption gate:** after P3, W1 implements durable call admission and start
authorization for a preconfirmation inspection attempt, together with the minimal W7 invocation/ESC adapter. Drive an
actual call through that adapter, interrupt at the dispatch/start/handle/adoption boundaries, and verify no later call
starts and that recovery adopts completed work once. This slice needs no generator, candidate author, or certification
engine. Pass I1 before accepting any W2–W5 native
call path. W7 later repeats it through the assembled installed skill. P3 does not depend on W1 or W7; I1 does.

### Development and statistical decisions

Choose **bounded anytime-valid Bernoulli confidence sequences** for formal evaluation. Freeze the procedure and
calibrate a finite cap before each attempt; evaluate after each valid logical trial. This preserves repeated-look
validity while bounding an inconclusive attempt. Use the product contract’s minimum-only acceptance policy and
per-campaign allowance unchanged. P5 must still establish that the actual sampling/evaluator process supports the
Bernoulli assumptions; a mathematical example does not pass that execution gate.

Use frontend.md’s accepted development-readiness defaults as the authoritative policy: representative batches of 50;
at least 100 trials and at least 99% observed compliance in every material category, with at least 98% in each of
that category’s latest two completed batches, and no unresolved repeatable defect or known regression before considering certification. The weak/uncertain/
ready decisions, exploratory 95% Wilson intervals, and unresolved 200-trial review checkpoint follow that same contract.
These are editable proposal defaults before campaign confirmation, not unresolved design choices or values to retune
from observed outcomes. Retain the existing one bounded diagnosis plus one generation attempt per failing category per
round. Report all four outputs with development labels and actual denominators.

Run targeted defect/regression checks before spending representative batches on a revision. A reproducible defect
allows diagnosis and another edit before completing a batch or reaching the readiness minimum. Representative evidence
collection targets plausible candidates under frontend.md's Adaptive development allocation policy; development need
not prove a formal lower bound. Coverage batches include ready categories while adaptive batches target unresolved ones.
Keep diagnostic executions separate from representative counts. Retain all current-version representative outcomes,
latest completed batch identities, and any partly completed batch on restart. Reaching 200 while unresolved requires
an evidence-review choice before more sampling in that category; an extension records the next checkpoint. A checkpoint
cannot declare a plateau, reset counts, or force a ready candidate to wait. These decisions belong to W5; W1 persists
batch/checkpoint identity, W3 produces purpose-labelled outcomes, and W6/W7 present evidence and any required choice.

W5 owns the category scheduler under that canonical product policy. Start with a 50-trial coverage batch per category,
then alternate coverage/adaptive batches. Track the coverage cursor and adaptive turn in H12, skip categories awaiting
review or another admission gate, and preserve partial batches. Rank adaptive categories by the exploratory Wilson
lower endpoint, then count and confirmed order; use all current-candidate representative evidence. Priorities change at
batch boundaries, while defects and existing stop decisions preempt batches. The coverage cycle, not raw failure
counts, prevents starvation. Do not add an independent frequency learner or infer usage probabilities from failures.
The scheduler chooses a category; W3 alone draws the within-category instance from its frozen probabilities. Diagnostic
requests carry a distinct purpose and cannot supply readiness counts. New candidates receive new counts, batches, and
allocation state while retaining earlier evidence and regression cases for diagnosis.

Formal scheduling follows frontend.md's Adaptive formal allocation policy using formal bounds only. Record each H12
choice before its draw, skip certified categories, and preserve the frozen cap/allowance. Under the selected procedure,
pausing a category while another is evaluated leaves its test process unchanged; selecting it from past outcomes
preserves the martingale argument only when its next outcome satisfies the same conditional assumptions. Establish
that fact for the actual W3/H12 process at P5. Do not treat adaptive within-category distributions, pooled adaptive
counts, or fixed per-definition quotas as supported by this argument. The per-category calibration fixtures remain
unchanged; earlier whole-attempt termination can prevent a category from using its full cap and does not create a
whole-candidate 90% power promise.

For internal development promotion, evaluate candidates against the same confirmed category definitions and comparison
evidence rule. The 100-trial readiness minimum governs formal admission, not every diagnosis, edit, or comparison.
This incumbent comparison is separate from final acceptance against the immutable original. Compare
their vectors of category shortfalls from readiness, sorted worst first, lexicographically; if tied, require improvement
in at least one named category and no regression in any other. Compare only candidates with valid representative
evidence under the confirmed development comparison protocol; show their actual counts and uncertainty. Incomplete
evidence cannot be presented as a completed readiness evaluation. A duplicate snapshot is not stronger evidence, and
absent required comparison evidence leaves promotion undecided. Explain
this policy in the proposal so it cannot change silently after outcomes arrive. Reusing development instances to compare
candidates is allowed and remains selection-biased development evidence.

The initial candidate comes from the captured user worktree, without a generation receipt. Measure it first under the
confirmed development protocol and retain those original observations for all later comparisons. Reuse them while
conditions remain comparable, with baseline uncertainty retained; do not rerun the original for every candidate or
force a clearly deficient original to complete the readiness minimum before diagnosis/authoring. If it already meets
readiness, it may proceed directly to formal testing; do not force a
cosmetic edit. Otherwise its evidence is the first authoring call's input. Later candidates come from reconciled
generation attempts. Candidate comparison and a first invocation therefore have an available starting point.

W2 automatically proposes the category test-selection and variation distributions using the product contract’s
Distribution proposal and confirmation rules. Requirements establish coverage, not future usage frequencies. Propose
equal test-definition weights within each category and explicit coverage-oriented variation probabilities, labelled
as balanced benchmark assumptions. Preserve required rare categories and let the user confirm or revise probabilities
in the complete proposal. User confirmation freezes the within-category distribution; the claim remains conditional
on the assumed benchmark, with actual future usage frequencies unknown.
Coverage validation maps requirements, branches, and material interactions to generators; it rejects an omitted
confirmed obligation and records out-of-scope limitations. Many fresh values from one template do not prove coverage
of an absent behavior. Adaptive allocation does not change those weights or remove this limitation.

For the chosen formal method, independently sample a test definition with replacement from the category’s confirmed
distribution for each logical trial, then sample that definition's approved joint variation distribution. Record any
conditional probabilities between variation dimensions; do not assume they are independent. Fixed quotas from test
definitions with different success probabilities are not identically distributed pooled Bernoulli trials, even when
their result schema is identical. Do not send those outcomes to this binomial procedure. If the user confirms a different
sampling protocol, establish its valid procedure before certification or report certification unavailable. Applicability
belongs in the confirmed generator; never drop an assigned instance after seeing its outcome to obtain a preferred mix.

For campaign-wide formal-attempt index `i`, spanning its lineages and candidates, use exact `alpha_i = 0.05 / (i * (i + 1))`. Each category uses that allowance; do not divide
it among categories. A lower bound equal to 0.95 fails. A result must include every confirmed category exactly once;
missing categories cannot disappear through a counts map. Display rounded values only after making the exact decision.

Use the following concrete sequential procedure, with target `t = 0.95`, confirmed planning rate `r = 0.99` and
planning power `w = 0.90` by default. The user may revise r and w before confirmation. The existing domain requires
`t < r <= 1` and `0 < w < 1`; retain that validation. Fix `c = (r - t) / (1 - t)` and `d = (r - t) / t` before draws.
For a possible true rate `m` in (0, 1), S successes, and F failures, define:

- `E_lower(m) = (1 - c + c / m)^S * (1 - c)^F`.
- `E_upper(m) = (1 - d)^S * (1 + d * m / (1 - m))^F`.

Each is a nonnegative test martingale at Bernoulli mean m. The lower process is a supermartingale when the conditional
success probability is at most m; the upper process is one when it is at least m. Thus threshold `1 / alpha_i`
controls each directional crossing probability by alpha_i over all permitted looks. Invert the lower process for the
lower bound L and the upper process for U. Use the roots at that threshold, with L = 0 when there is no lower root and
U = 1 when there is no upper root. Handle r = 1 by the probability limits: a failure makes the lower process zero;
all-pass data retains its defined limit. Empty data gives [0, 1]. Use current-count bounds, without post-hoc choice of
bet fractions or evidence windows. The standard test-martingale/anytime-valid argument is described in
[Ramdas et al.](https://arxiv.org/abs/2210.01948).

Each directional bound has anytime coverage at least `1 - alpha_i`; the displayed two-sided interval has coverage at
least `1 - 2 * alpha_i` for this category/attempt. Show those levels explicitly. The campaign’s 5% guarantee concerns
false minimum certification, not simultaneous coverage of every displayed interval or false below-minimum decisions
across all categories. The lower-bound intersection across categories preserves the product’s certification claim.

For the default r and t, certification tests
`E_lower(0.95) = (0.99 / 0.95)^S * (0.01 / 0.05)^F > 1 / alpha_i`.
The below-minimum decision tests
`E_upper(0.95) = (0.91 / 0.95)^S * (0.09 / 0.05)^F > 1 / alpha_i`.
Equality does not pass either strict boundary. Use exact rational comparisons for these decisions; log arithmetic or
numerical root presentation must not round a boundary into a different result. Bounds are reported from the same
procedure; ordinary fixed-sample intervals cannot replace them during repeated checks.

Calibrate each category’s cap before its first draw with an absorbing-state Bernoulli recursion at planning rate r.
For each live success/failure count, propagate the next outcome probabilities; absorb mass that crosses the lower
boundary as certified and mass that crosses the upper boundary as below minimum. Select the smallest finite N where
cumulative certified probability is at least w. Retain the procedure identity, exact inputs, cap and achieved planning
power in the attempt plan. Account for both early outcomes in calibration. Reject an unavailable or invalid
calibration before formal dispatch; never silently fall back to an uncapped procedure or a guessed cap.

For default inputs, the first three attempt caps are 250, 317, and 334 trials per category, with planning powers about
92.11%, 92.37%, and 90.48%. Discreteness can overshoot 90%. Independent integer-arithmetic enumeration verifies these
fixtures; production needs its own focused boundary/calibration tests. These are per-category planning probabilities,
not a guarantee for all categories together or evidence of the candidate’s true rate. The former fixed-sample
173/170, 234/230, and 284/279 fixtures remain historical/comparison arithmetic only; the product’s 72/94/107 all-pass
figures likewise do not govern sequential stopping.

Stop a category at L > t and retain its passing receipt. U < t ends the candidate’s formal attempt as below minimum.
An unresolved category reaching N ends the attempt as `not certified: inconclusive (trial cap reached)` and stops
further candidate trials. A certificate requires a passing receipt for every confirmed category; faster categories
cannot compensate for an unresolved one. Within N, continue the unchanged candidate with the same observations and
allocation. Restart preserves N, counts, bounds and assignments. An infrastructure retry retains its logical trial
and does not manufacture a compliance observation. Caps count valid logical trials; infrastructure recovery remains
a separate lifecycle path.

Do not increase N after outcomes or automatically reopen an attempt that reached it. At the cap, keep the original
and preserve the candidate/results. Further optimization or an explicitly authorized new test is a subsequent user
choice; a new formal attempt uses fresh evidence and the next campaign allocation even for the same snapshot.
Apply the product decision table and acceptance policy to early certification/below-minimum results. The accepted
default is minimum-only acceptance with the clear-regression safeguard; demonstrated improvement is an explicit
optional requirement selected before the campaign. A permitted uncertified acceptance remains a separate explicit
choice. New campaigns reset the allocation; resumption, in-campaign contract revision, and measurement changes do not.

W5 owns the procedure and calibration and publishes H11’s frozen attempt plan before W3 can assign formal instances.
W1 persists that plan, category stopping receipts and cap disposition; W6/W7 report limits, achieved planning power,
actual counts and available next actions. Replace the prior fixed-sample runtime path at W5’s cutover while preserving
historical results under their original procedure identities. P5 must review the selected procedure and actual trial
assumptions before certification admission; that integration gate remains unrun.

W5 owns valid rate/difference intervals and comparison decisions; W6 owns their presentation in both phases. Retain
category estimates and allocation counts separately; a pooled adaptive pass fraction is not a usage estimate. Use the
same predeclared aggregation weights for candidate/original comparisons, never their realized sampling shares.
Intervals do not measure uncertainty about missing scenarios or unknown usage frequencies. The
comparison metric, any weights or practical margin, original-snapshot observation schedule/reuse rules, and separate
comparison error guarantee must be fixed in the proposal. Required baseline executions come through W3 before W5 can
claim improvement. With the default minimum-only policy, show the reused original’s development comparison as
exploratory during certification and do not require extra formal original trials solely to populate superiority
statistics. Improvement-required acceptance still waits for its valid formal comparison evidence. A development
estimate cannot silently become formal comparative evidence. The minimum-certification
allowance alone does not establish simultaneous interval coverage or a joint improvement guarantee. P5 must accept the
comparison procedure and its repeated-look/attempt accounting before inferential comparison or improvement-required
acceptance is enabled; this integration gate is unrun. Unavailable comparison is explicit and follows the selected
minimum-only or improvement-required policy. Campaign confirmation may explicitly revise the accepted defaults;
it does not make them unresolved.

## Authoritative handoffs and recovery

The following declarations define the optimizer's data contracts. Producer and consumer descriptions elsewhere refer
to these IDs. Persist referenced inputs before publishing their references. A consumer validates identity, authority,
and predecessor state before changing campaign state.

| ID | Produced result, owner, and readiness | Consumer, identity, and authority | Failure and recovery evidence |
|---|---|---|---|
| H1 | W1 rejects a new campaign if the base Git worktree has staged, unstaged, or untracked changes, before forking or trials, and directs the user to resolve them and retry. W1 then forks the clean base Git worktree with its path, content, and mode state; W1/W2 retain the verified optimization-worktree snapshot, observed loading relationships, participating file/directory link paths and resolved targets where observable, source content and modes, and full proposal. Ready after clean-start admission, fork verification, source capture, and proposal validation. | Contract presentation, W4 candidate capture, isolated external-target staging, and later delivery use the same fork and source references. The baseline is independent of any later candidate or certification result. | Crash during fork or before proposal publication; restart verifies the same fork and linked-source baseline against retained evidence or rejects it. Never reconstruct source bytes from a later edited target. Changes made in the base after the fork belong to H10; material linked-target changes trigger H9. |
| H2 | W2 publishes the user-confirmed contract, editable authority for ordinary-file content/create/rename/delete and linked-target content within confirmed in-project roots, with existing symlink entries/destinations and file modes fixed; explicitly authorized external resolved targets permit existing-file content edits only. It also publishes remaining read-only sources, evaluator/generator profiles, text and file/tool effect obligations, excluded media output obligations, each used external service's confirmed read-only credential or disposable fixture, confirmed balanced benchmark probabilities and explicit assumed-usage limitations, complete prewritten multi-turn definitions and coverage limits, development/formal allocation policies, readiness rule, formal procedure, comparison definition/procedure, acceptance policy, and confirmed source baseline. Ready only after the complete proposal revision is confirmed and persisted; a post-formal revision also requires H11's terminal or explicit revision-stop receipt and reconciled H3/H5/H6/H8/H10 work. | Campaign activation, generation, and trial staging use the exact confirmed ID, target scope, service-effect eligibility, and baseline. A link path alone cannot authorize a different resolved target. The same campaign/claim resolves the retained lineage; a material revision creates a new one without resetting the campaign allowance. | Crash between stop request, native reconciliation, H11 terminalization, confirmation persistence, and campaign activation recovers the pending request and original attempt once. Unknown native disposition defers revision; no trial starts under the new claim before confirmation and no duplicate lineage or allowance arises. |
| H3 | W1 reserves and persists a call identity, purpose, campaign/lineage, candidate or proposal reference, model profile, context, revocable start authorization, and cancellation ownership before dispatch. Admission validates the invoking harness against the campaign's retained starting harness. W0/native runner retains completion evidence. | Runner start consumes the current authorization only in the campaign's starting harness; W2/W3/W4 adopt completion by stable call identity, even if the active campaign reference changes. | Interruption before native start revokes authorization; an already-started call is cancelled. Crash before first optimizer observation recovers retained completion once, without blind retry. A different harness cannot create or consume the call authorization. |
| H4 | W4 creates an immutable candidate from either H1/H2's confirmed initial snapshot or a reconciled isolated generation attempt's actual changes and edit-authority evidence. Its exact snapshot includes authorized in-project ordinary-file content/create/rename/delete, existing-file content edits in explicitly authorized external targets, and unchanged symlink entries, destinations, and existing file modes; disallowed symlink operations or mode changes cannot enter a candidate. It records formal freeze only after H8 verifies selection in the optimization worktree and isolated representation of external targets; the initial baseline needs no authoring. | W3 stages the exact candidate in isolated trials, including linked targets. W5's incumbent transition requires H8; formal draws require a freeze matching the selected in-project and isolated external snapshots. | A rejected generation attempt, including a write through an unauthorized external link, admits no candidate or compliance outcome and changes neither selected worktree, base, nor live external target; earlier allowed attempt edits remain isolated. Verify that a clean first invocation measures its baseline without authoring prerequisites. |
| H5 | W3 persists a selection record after the immutable candidate snapshot and before execution: H12 category-decision reference, category/test, generator, approved values/probabilities, exposed draw identity, concrete instance (including every prewritten turn and assigned incidental value for representative/formal evidence), assignment time, candidate, comparison arm/pair identity when applicable, and attempt/retry relationship. Formal candidate selection requires H8 promotion (or the initial H4 baseline), freeze, and H11’s persisted procedure/allowance/cap before any draw. Original-arm comparison selection binds H1/H2’s immutable original and the confirmed comparison plan; it does not promote the original into the optimization worktree. | Trial execution and evaluation consume the same logical trial ID, frozen evaluator, and assignment; neither an agent reply nor a retry may select or rewrite a later representative/formal user turn. Representative-development, targeted-diagnostic, and formal purposes are immutable; development trials may measure an unpromoted candidate. A pending revision stop bars new formal assignments. | Crash after assignment but before launch; restart reuses the complete representative/formal turn sequence if the attempt is still active, or records it as unexecuted during a revision stop. Crash after result but before adoption; adopt once rather than redraw. Missing/stale H12 or mismatched purpose blocks a new draw; no category reselection can replace an existing assignment. |
| H6 | W3 retains raw trial and per-turn evidence, semantic outcome or precise infrastructure disposition, evaluator identity, runtime observations, and retry linkage. | W5 counts a valid semantic outcome once under its assigned category/candidate and complete H5 sequence; infrastructure failures without an outcome remain non-semantic. | Retry transport failure on the same assignment and sequence. Preserve any already-valid semantic outcome even if later reporting fails. Invalid generators/evaluators or response-contingent representative/formal turns contribute no compliance outcome. |
| H7 | W5 persists the four outputs and their phase/uncertainty labels, category estimates/intervals, actual allocation counts and policy identity, fixed reporting weights, original-comparison evidence, and measurement identity; formal results additionally bind H11’s frozen sequential plan, the decision or revision-stop disposition, campaign allowance index, and category stop receipts. It records the policy decision or pending choice when applicable. W6 publishes the immutable result and its applicability assessment. | Development/interim reporting needs the measured H4/H6 evidence, not promotion or delivery. Selected-candidate reporting also uses H8 where applicable; only delivery reports require H10. A revision-stopped or unsupported-interaction formal result remains an observation under its old claim, never a below-minimum, cap-inconclusive, or certified result. All reports retain explicit measurement fields and generator/coverage limitations. | Crash after decision or revision stop and before reporting recovers that candidate/result without recertification or another allowance. Known material changes prevent a current claim. |
| H8 | W4 journals promotion of a selected stronger development candidate into the optimization worktree and retained isolated external-target representation, with prior selected snapshot, linked-source relationships, candidate contents, path operations, and owned write evidence. Ready only after exact combined snapshot verification. The initial baseline needs no promotion. | W5 adopts the new development incumbent only from this receipt and may freeze it for formal draws; W6 checks that both the optimization worktree and isolated external representation still match the frozen candidate before delivery. | Crash during create/rename/delete or before incumbent adoption recovers the same promotion idempotently without writing the base or live external target. An observed unowned optimization-worktree or isolated-target change stops scheduling and prevents promotion until user direction; a partially applied promotion never makes the new candidate authoritative. |
| H9 | W1's measurement observer captures available conditions before a call and receives W3/W4's new observations after completion. It publishes changed/unchanged/unknown fields and the pause decision before another call can be admitted. | All model-call paths consume the current observation revision, including evaluation after a trial. W6 consumes the same records for result applicability and candidate-versus-original comparison. An H8-authorized promotion starts the selected development candidate's new identity; an unowned edit in the optimization worktree is a stop condition. | Change between trial completion and evaluation pauses before evaluation; unknown fields retain limitations. The observer exists before W2–W5, not only with final reporting. |
| H10 | W4 retains the exact selected in-project and isolated external-target candidate, H7 acceptance authority or permitted explicit uncertified override, H1 clean fork/source baseline, current base and external target observations, complete combined change sets, link identities, and one durable journal across both destinations. W6 first verifies that exact combined candidate. | Before any write, W4 rejects external changes other than content edits to existing resolved files and rejects in-project symlink-entry/destination or existing-file mode changes, resolves every linked target, and preflights all in-project and external changes against baseline and current live paths. A same-file conflict, structural collision, changed destination, or other target-identity mismatch stops the complete delivery for user direction with retained evidence. Disjoint live edits survive. A clear preflight authorizes automatic application to both destinations. Before each write, validate the live preimage and link identity. W6 reports delivery and current applicability only after verifying the final combined snapshot; an explicit uncertified transfer remains `not certified`. | The journal survives a fault before, between, or after destination writes. Recovery reconciles already-applied edits and resumes the same authorized delivery idempotently, without overwriting a concurrent user edit or recertifying. A changed preimage stops further writes, retains partial-application evidence, and requires user direction. A partial transfer is never reported delivered or current for the combined system. |
| H11 | W5 computes the category caps and procedure from H2’s confirmed statistical inputs; W1 atomically reserves the campaign attempt index and persists that frozen plan, including the formal allocation policy, after H4/H8 snapshot verification, P5 admission, and starting-harness validation. Ready before any formal draw. | W3/H5 and W5/H12 use the same attempt ID, category set, procedure, policy, cap and allowance for each assignment; W5 updates decisions and H7 reports those references. A later trial cannot supply or revise its own predeclared limit. An explicit revision stop is a terminal attempt disposition retaining the spent allocation and valid outcomes. | Crash before/after allocation or revision stop recovers the same plan/index and terminal receipt without a duplicate spend. Missing, stale or altered plan/cap blocks formal dispatch. A different harness cannot allocate the attempt index. Resume retains stop receipts; a terminal cap or revision stop prohibits additional assignments to that attempt. |
| H12 | W5 chooses the next category/purpose from H2, H4 and retained H6 evidence; W1 durably publishes the decision before W3 draws. Bind campaign/lineage, candidate/arm, measurement revision, policy and source-evidence revision, eligibility/stop state, category, purpose, and development batch/cycle position or formal H11 attempt. The first development choice uses H2/H4 and empty evidence; it requires no earlier H5/H6. Diagnostic choices identify their bounded diagnosis/regression request; comparison-arm choices bind H2's comparison schedule. | W3 validates the current decision before H5, draws only within the chosen category's frozen representative/formal distribution, and attaches the candidate afterward. W5 consumes H6 once to advance batch progress or formal scheduling. Only targeted-diagnostic purpose permits deliberate case selection; it cannot contribute representative/formal counts. A revision-stop request forbids a further formal choice until the attempt is terminalized. | Crash after decision publication but before H5 reuses that pending choice. Crash after H6 publication but before batch advancement adopts the outcome once before another choice. Missing index/reference or stale candidate, lineage, measurement, or stop state blocks dispatch and requires reconciliation; never reset the cycle or discard retained failures. A revision stop preserves or explicitly cancels a pending unexecuted assignment and never discards a valid result. An authorized candidate change starts new scheduling state and preserves historical decisions. |

**Linked-source handoff across H1/H2/H4/H5/H8/H9/H10:** H2 binds any external edit authorization to the resolved
target recorded by H1 and the user's explicitly authorized scope. H4/H8 preserve the exact linked-target content and relationships without
writing a live external target. W3 materializes and validates that exact snapshot through the linked paths in each
isolated H5 trial; a mere path fingerprint is insufficient to prove staging. H9 treats observed target-content,
link-destination, and loading-relationship changes as material before another call. H10 verifies the live link and
resolved target and preflights the full combined in-project/external change set before any write. H10 journals one
accepted delivery across both destinations, including a restart between destination writes; W6 verifies the final
combined result before any delivered/current claim.

**Loader-configuration handoff across H1/H2/H4/H5/H8/H9/H10:** H1 retains the clean fork's configuration that decides
which prompt files load; H2 identifies it as fixed, outside editable candidate authority. H4/H8 and H5 carry that
same configuration into isolated candidate and trial snapshots while authorized ordinary prompt-file edits remain
possible. W4 forbids configuration edits in the prompt and rejects an observed operation or final delta before candidate admission and rejects any such delta at H8/H10. H9 compares
known configuration and loading-relationship changes before another call; a changed live configuration makes earlier
results historical and prevents silently reusing them for the changed system. W1 pauses before another call; restoring
the recorded configuration permits resumption under the original conditions, while retaining the changed
configuration requires a new clean campaign whose fork captures it.

**Trial-subagent handoff across P6/H2/H3/H5/H6/H7/H9/H11:** P6 establishes authentic native child-tree behavior in
both harnesses before W3 relies on it. H2 includes known child-visible governing sources, test obligations, and
effect authority. H3 binds recursive and parallel children and their separate worktrees to one root call; H5 binds
that call to one assigned candidate and logical trial. H6 retains observed child instructions, completion, text/file/tool
effects, and any merge into the isolated trial copy, with explicit gaps where the host supplies no observation. W3
reconciles every known child and merge disposition before publishing a valid judge outcome. H9 observes material
source or effect changes without treating trial-private child edits as candidate revisions. W5/H11 counts H6 once,
and H7/W6 reports that one outcome and its observation limits. Cancellation, retry, and restart retain the same H5
identity; an unresolved child cannot be silently abandoned or replaced with a new draw. Known remote governing
instructions or persistent writes by a child follow the same admission/terminalization rules as the root.

**Source-role gate across H1/H2/H5/H6/H7/H11/H12:** W2 records whether each known participating source is a
readable governing-instruction file (including one reached through a linked file or directory), remotely supplied governing instructions, or ordinary task data, with the
evidence and observation limits for that classification. H2 cannot silently omit a known governing source. W5 checks
that record before H11 allocation; a known remote-instruction source makes certification unavailable without spending
an attempt or publishing a formal H12/H5 choice. W3 adds later source-role observations to H6. If they reveal remote
governing instructions after H11 allocation, W5 bars new formal choices, reconciles in-flight H3/H5/H6 work, preserves
valid outcomes and the spent H11 index, and publishes a terminal H7 result of `certification unavailable: unsupported
instruction source`. W6 reports that disposition without a statistical conclusion; restart recovers the same H11/H7
records. Ordinary remote task data remains admissible and retains its H6/H7 source observations. A host's unobservable
source role is a reported limitation, not evidence that no such instructions exist.

Implement H12's W1 persistence boundary and W5 first-choice producer from H2/H4 before integrating W3's first campaign
draw. W3's sampler can still be built against test-owned inputs. Its first H6 enables W5's evidence-dependent selector,
not the bootstrap selector. The first combined gate traces H2/H4 -> H12 -> H5 -> H6 -> W5's next choice with a process
restart after each publication boundary. This order avoids requiring a trial outcome before the scheduler can assign
the first trial. Formal integration additionally requires H11 and P5; diagnostics and original-arm comparisons retain
their own declared purposes and cannot bypass that gate.

W1 owns durable campaign lookup by canonical project root and target path, with at most one resumable campaign per key.
Retain stable campaign, lineage, call, and trial identities independently of the mutable active locator. If target rename
changes that locator, preserve a validated mapping to the same campaign and lineage. Cancellation closes scheduling;
it does not erase campaign/claim history. Resuming retains the campaign allowance even when the claim or target
reference changed. Only an explicit new-campaign operation creates a fresh sequence; it records the separate guarantee
and preserves earlier history. New campaign creation and resumption must remain distinct on lookup and recovery.
Bind each campaign to the harness that began it before any mutable campaign operation. Resolve the canonical root and
target before admission in either harness, including after target rename and process restart. An invocation from the
other harness returns the existing campaign's identity and starting harness read-only, directs continuation or closure
there, and refuses resumption, cancellation, new campaign creation for that root/target, allowance allocation, and
call dispatch. Test concurrent invocations from both harnesses against the same key; only the starting harness may
hold or recover execution authority, and refusal preserves every retained byte and counter.

Exercise the combined recovery sequence: an authorized native generation call renames the target in its isolated
attempt worktree and completes; W3 measures the candidate, W5 selects it, and W4 begins H8 selection into the
optimization worktree. Interruption occurs after the optimization target rename but before incumbent adoption or
publication of its new target alias; cancellation closes scheduling; the process restarts. Repository recovery
reconciles H8's journal and explicit target mapping before resolving a new invocation's target lookup. After a later
certification, H10 begins the base-worktree merge and a second fault occurs after a base target rename but before its
alias publication. Recovery validates both journals and aliases before reporting delivery. Retain the original
campaign/lineage, trial outcomes, and allowance through both transitions. Do not start a promotion after interruption
or infer target identity from filename similarity. Separate rename and interruption tests do not prove this combined
path.

Store optimizer-created uncommitted campaign evidence under the project CAT temporary root resolved by the owning scope,
using its established allocation/handoff mechanism. Distinguish short-lived trial workspaces from retained campaign and
lineage evidence. Historical result lookup and resumption remain consumers after cancellation or an invocation exits;
do not run generic scratch cleanup over those records. Record cleanup ownership and references. Reclaim private scratch
only after workers terminate and its last consumer finishes; retain source snapshots and outcomes needed by history.

## Implementation assignments

Each assignment begins unclaimed. The implementation owner updates this document with its state, input snapshot,
verification commands/results, and unresolved obligations. Use unclaimed, claimed, verified, invalidated, or reassigned
for work ownership; use passed, failed, unrun, or user-excluded for acceptance gates. These are implementation records.
The owner also records the slice's accepted design choices in the optimizer package's `DESIGN.md` before closing it.

For each behavior change, establish a focused behavioral red test before changing production behavior. A compile or
fixture failure is not that red result. If a runnable boundary first needs a behavior-preserving seam, verify existing
behavior, add the seam, then demonstrate the behavioral failure. After implementation, run the focused check and
owned-path quality checks before a slice handoff. Run unrestricted project verification at the declared integration
and terminal build gates. Record exact commands and terminal outcomes. Do not count a helper test as proof
of a campaign or installed-runner result.

After the worktree restoration, treat every dated closure claim in these assignments as historical until its matching
current gate appears in the closure-audit section above. That section is the current status record; a source file or
test name in an older paragraph does not close its slice.

### W0 — Establish current integration capabilities

**Owner:** existing runner integrations and integration evidence. **Ready now:** inspection and bounded premise checks.

1. Trace the required existing runner, Git, and prompt-loading integration paths: registration, configuration, invocation,
   observable result, cancellation, and write authority. Inspect callers only to plan a safe integration cutover, not to
   select the optimizer architecture. Create the replacement inventory above and assign each removal to its replacement's
   owner. Record unrelated uncommitted work before editing.
2. Run the currently available P1–P4 and P6 observations for both supported harnesses using test-owned projects and
   evidence. Record the missing producers and first runnable combined checks above for any remaining P2/P4/P6 part;
   do not require those producers' completed behavior to close W0's premise assessment. P5 remains a W3/W5 formal
   admission gate against the actual evaluator and execution process. Do not infer absence of a capability from one
   helper; trace its owning integration.
3. Repair supported runner gaps in their owning modules. Expose current-harness invocation through the maintained runner
   integration if registration is missing. Do not duplicate model launch or protocol code in the optimizer.
4. Consolidate findings into this plan before fixing dependent interfaces. Close W0 as a premise assessment when its
   bounded receipts, explicit unresolved parts, responsible producers, and first combined checks are recorded. Keep
   P2/P4/P5/P6 open where their complete behavior has not run; leave only the behavior depending on those parts
   unready. W1 local persistence and statistical fixtures can proceed independently.
5. Seed the optimizer package's `DESIGN.md` with the current accepted choices in the product contract and this plan,
   including editable linked files and directories within confirmed authority, explicit external-target authorization,
   external existing-file content edits only with no create/rename/delete, mode change, or link retarget, plus retained
   in-project ordinary-file create/rename/delete with fixed symlink entries/destinations and existing file modes. Include isolated candidate/trial staging, observable link/target identity and material-change behavior, unauthorized-write
   warnings and admission rejection, plus accepted automatic conflict-checked delivery across the base and authorized
   external targets. Record the review-only patch alternative, rationale, cross-component effects, and acceptance
   evidence for this choice; do not present the alternative as current behavior.

**Accept:** retained authentic receipts for each supported capability, or an explicit unresolved capability with its
affected work. A missing external capability does not mark unrelated implementation verified or blocked. The initial
`DESIGN.md` reconciles with the current product contract and plan, and distinguishes accepted from pending choices.

**Replacement inventory:** `HEAD` has no `common/optimizer` Java implementation or optimizer CLI to
retire; those sources in this worktree are new, untracked feature work. The existing prompt-optimizer skill is the
overlapping supported behavior. The inventory is a cutover assignment, not proof that W7/W8 has finished it.

| Existing behavior and contract | Disposition and owner | Supported callers and cutover evidence |
|---|---|---|
| Codex and Claude `skills/prompt-optimizer/SKILL.md` wrappers plus shared `skills/prompt-optimizer/first-use.md`: observed-failure diagnosis, prompt repair, retest, and evidence handoff. | Replace the overlapping optimization procedure in W7 with the complete confirmed-contract lifecycle; preserve the diagnostic request's authorized scope. Retire superseded instructions only with the integrated skill. | Direct named skill invocation and rule-triggered `cat:prompt-optimizer` in `failure-regression-promotion.md`; W7 must test natural selection, diagnostic no-auto-confirm behavior, confirmation, resume/cancel, and both harness wrappers. W8 checks no conflicting old procedure remains installed. |
| Existing `codex-runner`/`claude-runner` launchers, native protocol adapters, retained-run APIs, and the Codex-side `claude-runner` skill. | Retain and extend in W0/W1/W3/W4; do not replace model launch with an optimizer runner. | Existing runner skill and new optimizer adapter/CLI; P1–P4, I1, and G7 need installed/native evidence. P2's isolated-generation worktree authority is currently unverified. |
| Existing `workflow-temp` invocation evidence and CAT installation/assembly routes. | Retain for skill-local evidence and installation in W7. W1 campaign durability uses its own repository; temp evidence cannot substitute for durable campaign records. | Existing skill first-use and plugin lifecycle; verify handoff cleanup ownership, fresh installed skill selection, and installed artifact contents in W7/W8. |
| Existing shared Git/project-copy and replay facilities. | Retain their supported uses. W1 owns the verified base-to-optimization fork; W4 owns isolated attempts, exact snapshots, internal promotion, and authorized file-level delivery. A runner copy cannot substitute for the selected optimization worktree or an authoring attempt. | Runner clients and W1/W4 Git operations; P2, H1/H4/H8/H10, A01–A03, A24, and A34 show fork, native edit authority, and restart-safe delivery while unrelated Git callers still pass. |

W7 must inspect source tests, fixtures, help, manifests, module exports, launchers, documentation, and installed packages
for each changed entry point. W8 repeats the reachability/reference audit and records any intentional historical
identifier or migration. No current source inspection can pass that final installed-boundary audit.

**P2 execution boundary:** The existing runners copy a supplied project
into their retained run directory before native launch. That establishes an isolated trial copy, but it cannot prove
that native authoring changed W4's separately created Git worktree or that its confirmed editable roots were enforced.
W4 must create the private worktree from the selected incumbent, retain its exact base and allowed roots, and pass that
worktree to a runner mode that executes there without another copy. The generation prompt forbids edits outside
confirmed authority, symlink and existing-mode changes, and loader-configuration edits. Existing isolation and staged
external targets keep live destinations outside authoring authority. A disposable native probe records allowed editor
and shell edits, measured prompt adherence, and no edit to selected/base worktrees or live external targets. Focused
injected forbidden deltas must reject the whole attempt before candidate or trial admission. A copy-only result or
Git diff alone cannot prove the complete inventory and private-worktree handoff. If the native boundary stops before a
write, retain that environmental result separately and leave P2 open.

**Native environment boundary:** The outer Codex daemon socket is intentionally inaccessible to nested
processes. Preserve that boundary. Use a supported independent execution path; a socket startup failure proves no
allowed or forbidden edit. P2 remains open until native private-worktree edits and admission validation run.

### W1 — Implement durable campaign execution

**Owner:** campaign application/repository, the candidate workspace's baseline-capture slice, measurement observation,
and the minimal invocation/ESC adapter in the appropriate modules.
**Requires:** source contract; native cancellation integration requires P3. Local persistence is independently ready.

1. Implement the state model and repository publication protocol above. Add complete callable lifecycle operations
   rather than public commands for internal record manipulation. Connect every transition to its actual producer.
2. Implement H1 baseline ownership and H3 dispatch/adoption primitives before dependent work. At new-campaign
   admission, inspect the base Git inventory and reject staged, unstaged, or untracked changes before fork or trials;
   tell the user to resolve the changes and retry. Fork the clean base worktree, verify the fork, and persist its
   identity and source blobs. Do this before W2 derivation; do not depend on W4 candidate generation or a
   later confirmed-contract consumer. A restart reuses or reconciles the same fork rather than silently making another.
3. Atomically register the canonical root/target campaign, retain the campaign/claim lineage index and campaign
   formal-attempt ledger, and serialize
   conflicting owners. Use a single campaign owner and operating-system lock rather than introducing distributed leases.
   The repository is the sole owner of publication and recovery.
4. Make stop and measurement-pause checks part of every model-call admission, including extraction, diagnosis,
   generation, trial execution, and evaluation. Record interruption before allowing another dispatch. Retain in-flight
   identity and known terminal disposition. Compare the optimization worktree with its last verified owned revision
   before each next action and on restart; reconcile an in-flight owned write before classifying an observed difference.
   A detected unowned change enters `UNOWNED_WORKTREE_CHANGE`, stops scheduling, and awaits user direction rather than
   silently becoming a candidate or `MEASUREMENT_CHANGED` recovery. Cancellation and ESC must not be modeled as
   semantic trial failures.
   Bind the trial agent's recursively delegated native child tree to the parent H3 call and H5 logical trial. Stop
   further child launches on interruption, request native cancellation of running children, and reconcile each known
   child disposition and child-worktree merge before H6 adoption or retry. A pending or unknown child disposition
   cannot be silently turned into a completed trial or a fresh assignment.
5. Define the measurement fields needed by H3/H6/H7 now: candidate/contract, generator/evaluator, model/configuration,
   runner/harness, observed sources/tools/external responses, times, and explicit limitations. Missing and equal are
   different states. Later result formatting consumes these records without inventing missing identity.
   Implement H9's observation/change decision here: compare available known conditions before dispatch, consume new
   native observations after every call, and pause before the next call on a detected material change. No continuous
   filesystem monitor is required. Record missing observations as limitations, not equal values or invented changes.
6. Implement atomic publication and restart recovery for confirmed state, acceptance policy/choice, call completion,
   trial outcome, H12 allocation/batch state, campaign allowance, and locator changes. Implement H12's persistence
   operation before W5's bootstrap selector and W3's first campaign assignment. Distinguish explicit new-campaign creation from resumption;
   only the former creates a fresh allowance sequence. A post-formal contract revision must consume a retained terminal
   or explicit stop receipt for the current attempt and reject unresolved native calls, trial assignments, promotion,
   or delivery. Preserve a pending revision request across restart until reconciliation reaches that checkpoint. Treat
   damaged or inconsistent evidence as a precise failure before scheduling.
7. Publish the first indexed preconfirmation inspection owner, preparation cycle, and attempt from H1's verified
   optimization-worktree source capture.
   This supplies an actual producer for I1 without requiring W2's semantic derivation or a candidate trial. With P3
   available, implement and pass I1 using the minimal real invocation adapter. Later W7 work extends that adapter with
   the complete skill flow; it is not the producer of W1's initial interruption evidence.

**Accept:** a nonclean base worktree is rejected before a new-campaign fork or trials with a clear recovery action;
a real Git-worktree fork preserves the clean source paths, contents, and modes; restart reuses the exact verified fork
even if the base has changed since the fork; duplicate campaign admission, stale ownership, preconfirmation refusal,
record-publication crashes,
call adoption, cancellation, and retained allowance identity pass direct production-operation tests. Pass I1 and H9's
between-call change check. Audit all scheduling paths for bypasses before releasing W2–W5 native integration.

**H9 dependency correction:** W2's confirmed-contract transition produces the claim and measurement epoch required by
the confirmed-campaign admission path. W2's local proposal and confirmation producer can use W1's retained H1/H3
inputs and does not consume H9's later admission decision. Build that W2 producer as a gate-enabling slice, then return
to W1's H9 publication and before-call pause gate before releasing any W2–W5 native model-call consumer. This order
breaks the implementation prerequisite cycle while preserving W1's acceptance requirement that H9 pass before its
dependent native work proceeds. The initial W2 producer is now integrated; W1 can use its retained measurement epoch
for the confirmed-campaign admission slice. I1 remains a separate unmet live-host gate.

**W1 preconfirmation observation assignment:** Before the first inspection claims a call, publish a complete
known-or-limited condition observation bound to its indexed preparation cycle, inspection owner, and logical call.
The observation records the selected model profile and captured starting source snapshot as known evidence without
claiming which bytes the host later loaded; conditions not yet observable remain explicit limitations. Index the
observation under the campaign's closed preconfirmation namespace. The scheduler must refuse an inspection claim or
pre-start when that exact observation index is missing, foreign, or stale; an equal publication retry repairs a
missing index. Verify direct claim refusal, successful coordinator dispatch through the production observer,
restart/index repair, and no confirmed measurement epoch before confirmation. Later preconfirmation calls must compare
their own observations with retained prior call conditions and pause on a known material change; the first-inspection
gate alone does not close H9.

**W1 paused-campaign cancellation assignment:** From a confirmed campaign paused by a known measurement change with no
unresolved native slot, a caller-authorized cancellation must close the campaign, retain its epoch, change evidence,
lineage and history, and release the canonical project/target key through the locator's durable key transition. Reject
foreign, stale, or conflicting producer evidence before the key changes. Verify the production cancellation operation,
equal retry, cold restart after state commit and before key-index repair, no further scheduling, and preserved evidence.
This is a separate cancellation slice; resumption of a cancelled campaign and cancellation with in-flight native work
remain open until their own focused gates.

**W1 after-call restoration assignment:** When an adopted confirmed call's after-call observation caused a known
measurement pause, restoring the original known conditions before a distinct next logical call must retain the
original epoch, clear only that pause, publish the next call's indexed before-call observation, and admit the next
call. The completed earlier call and its after-call change evidence remain durable. Verify this through the production
observer and scheduler after a cold store reopen; a same-call restoration request must be refused.

### W2 — Derive, present, revise, and confirm the contract

**Owner:** contract preparation and its source-discovery/presentation operations in the shared CLI module.
**Requires:** W1 H1/H3 and P1; W0's bounded P4 readable-source observations for native derivation. The staged-external
part of P4 follows W2 H2 and W4 staging at W3's first combined trial; it is not a prerequisite for producing H2.

1. Inspect the selected target through production prompt-loading behavior. Capture readable sources and observed loading
   relationships, including conditional branches. Preserve external/read-only sources separately from editable roots;
   linked files and directories contribute requirements, with link path, resolved target, and loading relationship
   recorded where observable. Classify resolved in-project targets under confirmed editable authority and external
   targets as read-only unless the user explicitly authorizes the resolved target and permitted scope. A link path
   within an editable root cannot by itself authorize an external target or a changed destination. Present external
   authorization as content edits to existing resolved files only, whether reached through a file or directory link;
   do not include external create/rename/delete, mode changes, or link retarget in the editable proposal. Preserve
   confirmed in-project ordinary-file create/rename/delete while excluding symlink-entry/destination and existing-file mode changes.
   Identify the loader configuration that decides which prompt files load, record its fork-time identity and observed
   relationships, and exclude its content and structure from editable authority for this campaign even if it is under
   an otherwise editable root. If diagnosis needs a different loading rule, direct preparation before a new campaign.
   Distinguish known remote responses used as governing instructions from ordinary remote task data, retain the role
   evidence and host observation limits, and flag the former as unsupported for certification. Do not replace
   production loading with a repository-wide text scan or silently omit a known governing source.
   Include child-visible instruction files and source roles that production loading exposes during recursive trial
   delegation in the proposed requirements and coverage. A known remote response governing a child follows the same
   unsupported-instruction-source rule; missing child observations remain explicit limits.
2. Produce the complete proposal: extracted requirements and sources; category boundaries and reasons; all test definitions
   and applicability conditions; every multi-turn test's complete prewritten sequence and any material interaction it
   cannot cover; expected outcomes; evaluator profile/rubric/decision rule; variation rules/probabilities;
   development minimum/comparison rule; and formal procedure/assumptions. For multiple tests within a category, define
   their independent test-selection distribution and approved joint variation distribution explicitly, as required by
   the selected binomial procedure. Propose the product's balanced benchmark: equal test-definition weights within each
   category and explicit coverage-oriented variation probabilities. Label the probabilities as assumptions and retain
   the assumed-usage limitation in H2. Let the user confirm or revise probabilities in the complete proposal; keep
   development/formal allocation policies separately from frozen within-category and reporting weights. Keep rare
   material uses covered without observed examples. Do not treat prompt text as evidence of real-world frequencies. Map every retained requirement, applicability branch, and material interaction to
   its category, test, and evaluator clause. Propose separate categories wherever pooling can conceal a materially
   different weak area; the user confirms or revises those boundaries. Flag a material obligation whose compliant
   behavior requires a response-contingent follow-up; a fixed-turn test cannot silently stand in for its full coverage.
3. Present all items for review without aggregate-only summaries. Accept user feedback requesting additions, removals,
   or changes to any requirement, category, test, expectation, evaluator rule, variation rule, or probability; CAT
   regenerates and presents a complete new proposal instead of exposing field-by-field edit operations. Retain the
   feedback and predecessor proposal so restart resumes the same preparation cycle. Validate coverage, references,
   distributions, and complete fields; user-requested obligations retain user provenance. Derive routine mechanics
   and concrete proposals for the user. A known remote governing-instruction source is disclosed as a certification
   boundary, never presented as a complete file-backed contract eligible for formal testing. Likewise, disclose a
   material response-contingent obligation as unsupported by representative/formal testing, not as an omitted case.
   If derivation identifies a requirement needing an external create, rename, delete, mode change, or link retarget
   through an already authorized target, present its path, blocked operation, content-only scope limit, consequence,
   and viable choices without suggesting that reauthorizing that target enables the edit. Do not claim exhaustive
   diagnosis or silently omit the requirement.
4. Keep the campaign `awaiting contract confirmation` until the exact complete revision is confirmed. Discovery calls are
   preparation, never development/formal evidence. Publish H2 and activate once. Material revisions start a different
   lineage; candidate edits alone do not. Both retain the campaign allowance. H2 binds the acceptance policy before
   trials, including optional improvement, baseline-comparison rules, and authorized routine outcomes. After formal
   testing begins, admit revision preparation only from a terminal or explicitly stopped attempt with all native and
   worktree operations reconciled. A pending stop for revision cannot activate H2 while its native disposition is
   unknown; confirmation never repurposes old assignments or outcomes.
5. Validate generator/evaluator specifications before evidence admission. Semantic preservation and rubric suitability
   require reviewed fixtures and an explicit validation result; a schema check alone cannot prove them. Check behavior
   coverage as well as incidental value variation: an omitted confirmed obligation prevents admission, while situations
   outside scope remain visible limitations. Validate that each representative/formal multi-turn definition fixes its
   turn sequence before execution and permits only declared incidental value draws before the trial. Generator breadth
   and fresh draws are distinct acceptance obligations.

**Accept:** G2 fixture extraction enumerates every known requirement/source; feedback targeting each contract element
produces a complete revised proposal and, only after confirmation, changes subsequent assignments. Refused
preconfirmation development/formal calls have no side effects. Include read-only/external sources, remote governing-
instruction versus ordinary remote-data roles, user-requested requirements, missing source material, invalid
probabilities, balanced benchmark assumptions and user revisions, rare-use coverage without observed examples,
frozen within-category weights despite adaptive allocation, and feedback/H2 crash/restart.
Include linked files and directories in-project and externally, observable link and resolved-target identity,
explicit authorization of an external resolved target with existing-file-content-only scope, preserved in-project
ordinary-file create/rename/delete scope with fixed symlink entries/destinations and existing file modes, and refusal
to infer external authority from the link path or from a proposed retarget.
Include a generator with many incidental variations that still omits a required behavior, and a proposal that confuses
adaptive category allocation with outcome-dependent changes to its benchmark probabilities. Include fixed-turn
coverage falsely claimed for a response-contingent obligation and prewritten sequence/template validation.

**Next W2/W4 linked-source boundary:** The selected-source proposal is an authority suggestion, not loading proof.
Build the H2 source graph from the *adopted* inspection result, its indexed native completion, the H1 source capture,
and the retained Git inventory. Represent the selected logical path and resolved content path separately. For a regular
`target.md`, both are `target.md`; for `target.md -> actual.md`, the logical path is `target.md`, the resolved content
path is `actual.md`, the link text is `actual.md`, and the bytes/hash come from the H1 snapshot entry for `actual.md`.
Carry each native path observation, its loading relationship when reported, and `PROMPT_SOURCE` coverage as observation
evidence. With zero path events and LIMITED coverage, label the selected path as a requested/retained source, not a
host-confirmed load, and do not manufacture a `PromptSourceReceipt` or call its hash consumed bytes. Preserve a
receipt-backed route only for an actual boundary that reports consumed bytes. The contract review must show the source
path, resolved target, retained-byte provenance, coverage limit, and proposed editable target before confirmation.

At confirmation, verify the source graph and proposed root against the same adopted result and H1 inventory; reject a
different link destination, resolved target, source hash, or completion reference. Store known regular content under
its resolved path in the source baseline while keeping the link entry/destination in the captured inventory and
reviewed contract. A resolved in-project file may be editable only within its confirmed root. W4 selects the resolved
file's retained bytes and checks the selected logical path through that link relationship; it must not require a
regular snapshot entry at the link path or turn the link into a regular file. An external-target link is a separate
case requiring isolated target staging and explicit content-only authority; an ordinary remote task datum has no file
link relationship and must not enter this path. The first combined check runs an adopted in-project linked inspection
through complete proposal review, H2 confirmation, restart, and W4 initial candidate capture, asserting the link
entry/destination, resolved original bytes, LIMITED host coverage, and resolved-only edit authority. Compare the same
flow with a regular target and reject an external link without its distinct staging/authorization path. A publication
fault between adoption and H2's first read must either recover the same indexed evidence or reject it before
confirmation. Existing receipt fixtures can test their receipt-specific boundary, but cannot pass this combined gate.

### W3 — Execute assigned trials and preserve trustworthy evidence

**Owner:** trial execution and generator/evaluator operations in the shared CLI module; runner-result adaptation stays
with native runner owners. **Requires:** W1/W2, W4's initial candidate slice, and W5's bootstrap H12 selector for campaign
trials; P1/P3 and I1 for native execution. The full P4/P6 checks follow W3's first runnable staged-source and
child-enabled H5/H6 trial; neither is a prerequisite for implementing the H5/H6 producer. Do not accept those
trial outcomes for formal certification until the applicable combined checks pass.
Sampler/evaluator mechanics can be implemented independently with test-owned inputs. Formal dispatch additionally needs
W4's formal-freeze result.

1. Validate generators against the confirmed definitions, permitted values, invariant expected outcome, and frozen
   probabilities. Keep development and formal draw streams distinct and durable, bound to the H2 distribution.
   The representative/formal sampler receives H12's chosen category, approved definitions and draw state, never
   candidate content/identity or outcomes as within-category selection inputs. Validate H12 before drawing and attach
   candidate identity afterward. Targeted diagnosis uses a separate purpose and is excluded from representative/formal
   admission; it may adapt later turns to earlier replies without contributing to representative or formal counts.
   Representative/formal generation assigns the entire prewritten turn sequence and incidental template values before
   execution and rejects a later turn selected or rewritten from an agent reply. Formal draws start only after freeze; development outcomes cannot
   acquire formal purpose. Resume retains assigned instances and draw state; retries preserve the logical trial.
   A revised candidate gets fresh formal assignments, without discarding valid repeated values from independent draws.
2. Persist H5 after an immutable candidate snapshot and H12 decision and before each development launch. A generated candidate may be
   measured while it remains isolated, but the assignment must resolve its indexed H4 attempt, confirmed contract,
   lineage, and measurement epoch; it cannot accept an arbitrary candidate ID. Persist formal H5 only after verified
   H8 promotion (or the initial H4 baseline), candidate freeze, and H11’s durable plan. Reject a missing or changed
   cap/plan and any attempt already closed at its cap. Comparison trials follow H5’s separate immutable
   original-arm binding without changing the incumbent; paired assignments retain their shared instance identity.
   Reject a new formal assignment after a revision stop request; reconcile any existing assignment before H11 becomes
   terminal. Retain the concrete instance and exposed draw identity; durable assignment
   precedes launch. For representative/formal multi-turn trials, persist every assigned turn and template value in H5
   before the first turn; retry and restart must reuse that exact sequence. Candidate authors cannot read unassigned formal draws or the active candidate's reserved formal
   instances. Approved definitions and distributions remain visible to the user.
3. Execute through the existing runner in a fresh isolated project containing the assigned starting candidate and the
   confirmed protocol's inputs. Stage the exact authorized linked file/directory targets, including external target
   bytes, in isolation and verify that production loading through their links sees the assigned candidate without
   reading a live target in place of staged content or writing to it. Bind observed link/target identities and staging
   evidence to H5/H6; reject an incomplete or changed staged snapshot before semantic outcome admission.
   Use one fresh root native session per logical trial; execute every turn of a confirmed
   prewritten multi-turn test within it. Do not derive a representative/formal follow-up turn from the agent's reply.
   Permit that agent to use native recursive and parallel subagents with separate child Git worktrees. Bind every
   child identity and completion to the root H5 assignment, reconcile parallel work and child-worktree merges into
   the isolated trial copy, and prevent child effects from changing the frozen candidate or other persistent roots.
   Retain per-turn outputs, the assigned sequence, and one semantic outcome under the same assignment;
   a restart or infrastructure retry cannot replace a turn, redraw values, or count one turn as another logical trial. Trial-private edits never mutate
   the frozen candidate.
4. Collect raw outputs, known host-tool identities/configuration observations, loading paths, external/MCP source identity
   and available material responses. Record available evidence about whether a remote response supplied governing
   instructions or task data; report a known remote governing source to the H11 admission/terminalization gate. Keep
   native observation limitations explicit. Permit arbitrary installed host tools
   supported by the existing runner; do not add a packaged filesystem or binary dependency parser. Before dispatch,
   verify that every external service used has confirmed read-only credentials or a disposable fixture and refuse any
   known persistent write outside the trial copy, including host paths and service state. Retain the eligibility
   decision and effect observations with H5/H6; an unknown or unobservable effect is reported as a limitation, not a
   containment claim. Remote task responses remain trial data whose effect on text and file/tool behavior is evaluated.
   Collect available child instruction-source, output, file/tool-effect, and merge observations as part of the root
   H6 evidence. Apply the same source-role and external-effect limits to each child. A child launched successfully
   without a known completion and merged-effect disposition does not establish a valid trial outcome.
5. Evaluate open-ended text and file/tool effects with the frozen evaluator profile/rubric/schema. An objective
   assertion, process exit status, or tool invocation alone is insufficient to establish semantic compliance. Treat
   candidate text and generated output as untrusted evaluation data, not evaluator instructions. Validate evaluator
   results and retain evidence needed to diagnose them. Count only the frozen judge's validated labels for the
   95%/95% claim; do not present those labels as independently calibrated actual compliance.
6. Implement H6 classification and retry. Infrastructure failure without a semantic outcome may retry the same assignment;
   it cannot redraw. A valid outcome is durable and counted once, including a failure. Malformed generator/evaluator
   output is infrastructure failure, not compliance evidence. Reconcile uncertain native completion before another call.
   Reconcile the entire child tree, including concurrent completion and merge races, on cancellation and restart;
   count only the one validated root-trial outcome, and do not discard a retained child effect on retry.

**Accept:** generator support/probability fixtures, fixed multi-turn sequence/template validation and semantic validation cases, evaluator authority/injection cases,
development measurement of an indexed unpromoted H4 candidate, refusal of an unpromoted formal candidate,
development/formal isolation, assignment-order tests, duplicate/out-of-order completions, retry races, fresh/multi-turn
session behavior, private edits, and a real installed-runner trial. Inject failure between native completion and H6 adoption.
Include authorized linked-file and linked-directory candidates with an explicitly authorized external target;
prove trial loading uses exact staged candidate content and leaves the base and live external target unchanged.
Reject a representative/formal follow-up chosen from an earlier agent reply while allowing an explicitly targeted
adaptive diagnostic without counting it. Restart and retry retain the exact assigned turns, values, and single outcome.
Also reject a missing/stale H12 reference, recover its chosen category after a crash before drawing, preserve a draw
across retry, and prove that outcome-dependent category scheduling never changes within-category probabilities.

**W3 assignment target:** From the registered current confirmed claim and W4's adopted initial candidate, load
the exact confirmed contract, draw an initial development instance, and durably bind the candidate, category, concrete
instance, active measurement epoch, and draw identity before native dispatch. The create-once assignment key and
logical trial identity must include that epoch so a later new measurement cannot silently recover a trial from the
earlier identity. The focused gate must recover the same assignment after restart,
reject a changed candidate or contract, and show that no trial starts before confirmation. The first measured initial
trial and W5's category-level development decision remain subsequent integration gates.

**W3 later development-candidate gate:** After W4 publishes a reconciled isolated attempt as an H4 candidate, W3
assigns and evaluates development instances for that exact candidate without changing the campaign's promoted
incumbent. The focused gate must reject an unindexed or changed attempt, retain assignment and outcome across restart,
and show that a formal draw still refuses the candidate before H8 promotion and freeze. W5 consumes these results
to decide whether promotion is warranted; the first runnable combined check measures one new candidate and promotes
it only after the confirmed stronger-evidence rule passes.

### W4 — Manage candidate worktrees and deliver accepted changes

**Owner:** candidate workspace, delivery merge, and existing runner edit-authority integration.
**Requires:** W1 H1/H3 and W2 H2 for native authoring. W4 stages the isolated H4 attempt, then runs full P2 at its
first native authoring call before accepting a generated candidate. The initial candidate needs no authoring. Later promotion also
requires W3's candidate measurement and W5's stronger-evidence selection. H10 delivery requires either W5's
certification plus policy acceptance (or explicit regression trade-off), or a permitted explicit uncertified-acceptance
request, followed by W6’s exact-snapshot verification.

1. Use H1's verified optimization worktree and retained isolated external-target snapshot as the selected incumbent
   location, then create a separate Git worktree and isolated target staging for one generation attempt. Bind its
   base snapshot, confirmed editable authority, link/target identities, campaign, lineage, attempt, and
   retained native call before authoring. Materialize the selected incumbent and any CAT-owned staged, unstaged, and
   untracked content in that attempt worktree, including authorized linked file/directory target changes; verify
   paths, bytes, modes, link resolution, and absence of writes to the base or live external target before native start. Invoke the
   existing runner
   there without another project copy. Preserve the base worktree throughout development and formal certification.
2. Put the confirmed edit restrictions in the generation prompt and use existing runner workspace isolation with
   staged linked targets. Compare the complete no-follow inventory against the starting snapshot before candidate
   admission or any trial, including tracked, untracked, and ignored paths, symlink metadata, file modes, loader
   configuration, and staged target content. An observed prohibited operation or forbidden delta rejects the whole
   attempt and admits no candidate or compliance outcome. Earlier allowed attempt edits remain
   isolated for inspection; no attempted edit reaches the selected optimization worktree or base worktree. Reconcile
   Git and native call state before retry; report a precise infrastructure failure if a safe retry state cannot be
   established. Compare optimization-worktree changes with CAT's recorded operations before each new action and after
   owned writes. A detected unowned change stops scheduling and requires user direction; transient edits reverted
   before observation are outside this detection guarantee.
   Forbid edits to the loader configuration in the prompt and reject an observed operation or delta, even if its path
   lies under an authorized prompt-file root. Do not admit that attempt as a candidate or trial. Preserve authorized
   ordinary prompt-file content and structural edits under the separate confirmed scope.
   Permit linked file/directory edits only when their resolved targets fall under confirmed authority. A linked
   external target needs explicit authorization for that target and scope; the link path alone grants none. Restrict
   authorized external edits to content of existing resolved files, whether reached through a file or directory link.
   Forbid external create, rename, delete, mode change, or link retarget and any in-project symlink
   create/delete/retarget or existing-file mode change in the prompt; reject an observed operation or delta. Do not
   silently treat a retarget as an authorized content edit. If observed diagnosis needs
   such an operation, or authoring attempts it through an already authorized external link, report the affected
   requirement or attempted operation, link path, resolved target where observable, existing-file-content-only limit,
   consequence, and viable choices: edit existing content if sufficient, use another authorized in-project source if
   possible, or stop/revise. Reauthorizing that external target cannot permit the operation in this version. If
   diagnosis identifies a needed change to an unauthorized external linked target, report the affected requirement,
   link path, resolved target where observable, read-only consequence, and choices to authorize that scope, meet it
   elsewhere, or stop/revise. Do not claim to detect every needed edit. If native authoring tries an unauthorized
   write through the link, report the attempted edit and the same details; preserve existing isolation, isolated earlier
   allowed edits, and the invalid-attempt disposition. Report an in-project symlink-entry/destination or existing-file
   mode attempt with its blocked operation and scope limit; a diagnosis that needs one directs the user to prepare it
   before a new campaign or pursue an allowed edit.
   Measure prompt adherence through focused native cases in both harnesses and report counts, operations, model, and
   conditions. No custom filesystem, FUSE binding, or additional process-confinement layer is required. Instruction
   compliance is measured behavior, not prevention of every write; an unobserved reverted temporary edit can escape
   detection. Existing isolation of the selected/base worktrees and live external targets is still required.
3. Implement H4's initial-candidate path from the confirmed baseline first, so W3 can measure it before any authoring.
   Admit later candidates only from reconciled isolated generation evidence. Store exact prompt-system snapshots
   independently of either mutable worktree or external target. Include authorized in-project ordinary-file create/rename/delete and existing-file content changes in external linked targets in snapshot and distinctness checks. Reject any symlink-entry/destination or existing-file mode change, as well as external structural changes, before candidate admission. Distinctness is based on paths/content, not an agent's claim or a
   manually assigned version. W3 measures an unpromoted candidate before W5 compares it with the incumbent.
4. Implement H8 promotion into the optimization worktree after W5 selects a stronger candidate. Journal the selected
   candidate, selected-worktree and isolated external-target state, owned path operations, and expected result before applying
   in-project ordinary-file content/create/rename/delete and external existing-file content changes, with symlink entries/destinations and existing file modes unchanged. Do not promote by writing the live external target. Verify the exact measured combined snapshot and publish the promotion receipt before
   W5 advances the incumbent. A crash at any application/publication boundary recovers the same promotion without
   duplicate edits. Do not commit, reset, or silently discard worktree changes.
5. Bind formal freeze only to the verified promoted candidate, or to the initial captured candidate when no
   promotion was needed. Before formal assignment, recheck that the optimization worktree and isolated external-target
   representation match the frozen candidate. W6 rechecks both after certification before delivery.
6. Implement H10 after certification and policy acceptance, or a permitted explicit request to accept without
   certification. Certification alone cannot bypass an unmet comparison requirement or clear-regression decision.
   Bind one delivery journal to that authority, candidate identity, in-project and external change sets, and observed
   link destinations. Compare the full change sets from H1's clean fork/source baseline to the selected candidate and
   to the current base and live external targets, including post-fork staged, unstaged, untracked, and structural edits,
   contents, and modes. Reject an external candidate change that creates, renames, deletes, changes mode, or retargets
   a link, and any in-project candidate change to a symlink entry/destination or existing file mode; H10 may deliver only edits to the content of existing resolved external files. Resolve each link and preflight all destination identities, conflicts, and preimages before
   any base or external write. A same-file overlap, structural collision, or changed destination stops the complete
   delivery and asks for user direction while retaining evidence. Otherwise journal and automatically apply the
   accepted changes to both destinations, preserving disjoint live edits and the base's staging state. Before each
   write, validate its live preimage and link destination against H10; a new edit stops further writes without
   overwrite, retaining partial-application evidence. Recover an interruption before, between, or after destination
   writes from the same journal, applying no change twice. Verify the final combined snapshot before reporting
   delivered/current status. A base-only or external-only change to another participating prompt source makes a
   certification historical for that combined live system even when H10 safely merges disjoint changes. Every
   uncertified delivery remains labeled `not certified` regardless of the transferred content.

**Accept:** P2 prompt-adherence measurements, existing isolation, allowed edits, and complete forbidden-delta rejection before candidate admission, including symlink-entry/destination and existing-file mode changes; unchanged base files until
certification except for user edits made after the fork; content/structural diffs; post-fork staged, unstaged, untracked,
symlink, and unowned-edit fixtures; stronger
candidate selection followed by exact optimization-worktree promotion and freeze; restart during promotion; certified
disjoint-file merge with base-only edits preserved; same-file and structural collision refusal; and restart before,
during, and after the delivery merge. An uncertified outcome leaves the base unchanged until a permitted explicit request; that
request uses the same path-conflict and recovery checks and never creates a certified label. A rejected generation
attempt cannot reach candidate admission or promotion. An
unowned optimization-worktree edit stops scheduling until user direction.
For linked files and directories, verify authorized in-project and explicitly authorized external edits enter exact
candidate snapshots while base/live external targets remain unchanged. For an unauthorized external linked target,
verify bounded diagnosis warnings, prompt restrictions, complete inventory validation, and rejected observed operations or final deltas, retaining prior allowed attempt edits
without a candidate or compliance outcome. Authorized external file and directory links permit existing-file content
edits only; an observed external create, rename, delete, mode change, or link retarget operation or final delta rejects the attempt before H4/H10.
An observed need or attempted out-of-scope operation through an authorized link gives the path/operation/scope warning
and viable choices, without suggesting reauthorization of the same target or claiming complete diagnosis.
H10 acceptance preflights the full combined change set before any write;
a conflict or changed target leaves both destinations untouched and needs user direction. An accepted disjoint
delivery applies to both destinations, and a fault between writes recovers idempotently without overwriting an
intervening user edit. W6 verifies the final combined snapshot before reporting completion or current applicability.

**Generation edit-authority decision — acceptance unrun:** The requested outcome is that only
allowed edits enter candidates, trials, promotion, and delivery, while the selected/base worktrees and live external
targets remain isolated. Preventing every temporary in-attempt symlink or mode mutation adds a stronger guarantee with
no established need under this outcome. A custom filesystem and process-confinement implementation would add native
dependencies, platform adapters, lifecycle handling, and verification. The simpler selected design uses prompt
restrictions, focused compliance measurements, existing runner isolation, staged external targets, and complete
no-follow validation before admission. Failure likelihood is unknown until measured. A rejected attempt costs retained
inspection and reconciled retry; recovery covers its disposable state, not arbitrary live external writes. Existing
live-target isolation therefore remains mandatory. No FUSE dependency or additional confinement layer is selected.
The earlier native-binding availability probe is not evidence for the revised P2 behavior gate. W4 remains open:
installed prompt adherence, complete candidate capture/admission, external staging, and promotion/delivery acceptance
still require their own evidence.

**Private-generation cutover:** Finish the complete preparation, native authority, capture, failure/retry
and recovery path in the private attempt worktree. Retire the active-worktree LiveGenerationCheckpoint/capture/restore
family and its selectable generation route only through the verified cutover. Promotion and delivery keep their
separate journal. Shared HarnessRunWorkspace and both optimizer adapters supply local binding paths; installed
private authoring, isolated external-target staging and candidate admission remain unverified.

### W5 — Run development, plateau detection, and formal certification

**Owner:** optimization policy, statistical calculations, and allowance integration.
**Requires:** W1/W2 and W4's admitted initial candidate for the bootstrap H12 allocation; W3's first H6 for subsequent
evidence-dependent development selection; W3/W4 generation integration before formal admission. W5 can implement
statistical calculations and calibration independently, but P5 must pass against the actual H2/H5/H6/H11/H12 process
before any formal certification claim is admitted.

1. Publish the first H12 choice from H2/H4 and empty evidence before requesting W3's first draw. Obtain initial
   evidence for H4’s original candidate before diagnosis or authoring; a reproducible defect permits
   early diagnosis without waiting for the readiness sample. Retain its representative measurements as the reusable
   original baseline. Run development under the confirmed readiness/comparison rules and defect-first sequencing.
   Retain category numerators/denominators and all prior
   development evidence. Candidate authors may use development evidence and failed prior formal candidates' evidence,
   but never relabel it as the new candidate’s formal evidence. Track batch completion and category counts for the
   exact candidate. Produce H12 decisions under the product's balanced-start, alternating coverage/adaptive policy;
   coverage also includes ready categories and adaptive batches target unresolved ones. Use current-candidate rates
   and uncertainty at batch boundaries, with defect/stop decisions taking precedence. At an unresolved 200-trial checkpoint, retain
   a pending review choice before further sampling, and preserve it on restart. Never reset an unchanged candidate’s
   counts or require a development certification bound before formal admission.
2. Define a durable development round over the currently failing categories. Perform a bounded diagnosis and isolated
   candidate-generation attempt for each. Persist the attempt limit and stronger-evidence comparison rule in the
   confirmed proposal, and retain the round's category set and each category's attempt disposition, so restart resumes
   unfinished categories without reinterpreting or resetting the round. Measure an unpromoted candidate through W3,
   compare it with the incumbent, and request H8 promotion into the optimization worktree only for a distinct candidate
   with stronger valid evidence. Keep the previous incumbent authoritative until promotion and exact snapshot
   verification pass. When the outcome policy returns to development, include categories that failed certification
   even if their earlier development estimates meet readiness. Do not automatically loop an unchanged failed snapshot
   into another formal test; an authorized evidence-collection choice follows the within-cap continuation/new-attempt rule above.
   Infrastructure failures leave the round incomplete.
3. Return `not certified: plateau` only after every failing category received those attempts and the complete round
   produced no distinct valid candidate with stronger development evidence. Report unresolved categories and evidence.
   Do not use token usage, elapsed time, or predicted benefit as a plateau rule. Keep a round's incumbent and required
   categories fixed; if a stronger candidate is promoted and adopted but remains below readiness, begin the next round
   automatically from its current category evidence without another user request.
   A plateau report exposes contract revision, another requested change, and stop as distinct user choices, preserving
   the candidate/results and separately labelling any permitted explicit uncertified-acceptance choice.
4. Once readiness passes, require H8's verified optimization-worktree promotion receipt for a revised candidate or
   H4's captured initial candidate. Recheck the selected optimization-worktree snapshot, freeze that exact candidate,
   reject a known remote governing-instruction source, excluded image/audio/video output requirement, known persistent
   write path, missing external-service read-only credential or disposable fixture, or material requirement for
   unsupported response-contingent follow-ups before allocating H11, then publish H11 by allocating its campaign
   formal-attempt index once and persisting the calibrated sequential plan/caps. Let W3
   assign fresh formal instances. An
   unowned worktree change or incomplete promotion cannot spend an
   allowance or create a formal assignment. Keep development counters physically/logically separate from formal counters.
5. Use H12's formal category-selection policy and update valid category bounds for frozen-judge pass labels after each logical trial; stop categories
   that certify. Reuse pending choices across restart, keeping within-category draws unchanged. End the attempt when a
   category is clearly below minimum or reaches its cap unresolved; certify only when every confirmed category has a
   passing receipt. Reject missing/extra categories and invalid/duplicate outcomes. Count a recursive or parallel
   child tree only through its root H5/H6 trial; require reconciled child completion and merge effects before admitting
   its judge label. Neither a child count nor a child-worktree merge creates another sample or a new candidate. Reject
   an outcome with unresolved required child effects rather than inventing a pass. Bind H7 to the retained procedure,
   cap/calibration, stop receipts, allowance, candidate, contract, evaluator, and measurement conditions. On an explicit
   revision stop, stop further H12 choices, reconcile in-flight calls and assignments through H3/H5/H6, retain valid
   semantic outcomes, mark unexecuted assignments without inventing outcomes, and terminalize H11 once. Report its
   status as `not certified: stopped for contract revision`. A retry or restart cannot reopen that attempt or restore
   its allocation. If a remote governing-instruction source is discovered after H11 allocation, bar new formal choices,
   reconcile in-flight calls/assignments, retain valid outcomes and spent allowance, and terminalize once as
   `certification unavailable: unsupported instruction source`; never convert it to a statistical result.
   Apply that same stop, reconciliation, evidence, allowance, and restart rule if a material response-contingent
   obligation is discovered after H11 allocation; report `certification unavailable: unsupported test interaction`.
   Apply the same post-allocation reconciliation and spent-allowance rule to a newly discovered required media output
   or ineligible persistent effect, reporting `certification unavailable: unsupported output` or
   `certification unavailable: ineligible trial effect`, respectively. A known case before H11 allocation refuses
   without spending allowance or dispatching a formal trial.
6. Produce the four outputs and apply the canonical product decision table, keeping minimum certification and acceptance
   separate. Default to minimum-only acceptance with the clear-regression safeguard. Execute authorized routine actions; retain a pending concrete choice when the policy does not decide it.
   Preserve the candidate, results, and spent allowance. Infrastructure retries retain attempt allowance and logical
   trial. Cancellation/resumption, restart, and in-campaign contract revision retain campaign numbering; explicit new
   campaigns reset it. H7 supplies the acceptance authority consumed by H10.
7. Calculate rate and difference intervals under the approved procedure. Measure the immutable original through W3
   when required by that procedure; do not count reused baseline observations again or pool candidate versions.
   Validate comparison conditions, phase labels, repeated-look/attempt accounting, and unavailable-result handling.

**Accept:** exact sequential bounds/calibration fixtures, empty/threshold/every-category cases, permitted early stopping
and within-cap continuation, terminal cap/refused extension and restart, development
readiness thresholds/batch stability, early-defect repair, unresolved-checkpoint/resume behavior, reused original
evidence, unpromoted-candidate comparison, incumbent adoption only after H8, complete/incomplete plateau rounds,
duplicate candidates, every decision-table row and both acceptance policies, valid continuation/new attempts, and
full restart/cancellation/revision/new-campaign allowance tests. Include revision requested during a live formal call,
unknown disposition, explicit stop after a valid outcome, unexecuted assignment, attempt terminalization, and H2
confirmation only after the safe checkpoint. Invoke
campaign operations as well as local arithmetic tests.
Pass the H12 bootstrap and fault/restart chain before accepting adaptive scheduling: balanced initial coverage,
alternating coverage/adaptive batches, rate/uncertainty priority and tie breaks, reduced extra attention after recovery,
ready-category coverage, checkpoint exclusion, early defect repair, and no pooled old-candidate/diagnostic counts.
For formal scheduling, inject retained outcomes to change the next category while preserving its generator, cap and
allowance; include stopped-category exclusion and whole-attempt terminal stops. P5 separately establishes statistical
applicability; a scheduler fixture alone cannot establish the error guarantee.

**Review-checkpoint boundary:**
When an authorized extension reaches its next review point, retain a new request linked to the preceding response
before another draw. The request and response retrieval path must survive restart and preserve the earlier review
chain. Check a threshold reached partway through a preassigned batch at assignment, claim, and start admission, and
ensure an equal retry of counted work remains available. The existing review response grants further evidence only
while the category's total remains below its stated point; it does not authorize sampling at or beyond that point.

### W6 — Report results, applicability, original comparison, and delivery status

**Owner:** measurement/result operations and final report composition.
**Requires:** W1 measurement data, W3 observations, and W5 H7 estimates/decisions. Selected-candidate checks also use
W4 H8 where applicable; W4 H10 is required only for merged-result reporting, never for development/interim reports.

1. Publish the four outputs from H7 during both development and certification, preserving exploratory versus formal
   labels, baseline sample uncertainty, explicit unavailable fields, and certification versus acceptance status.
   Name estimated rates, original comparisons, and the 95%/95% claim as frozen-judge pass rates under the confirmed
   benchmark and measurement conditions; do not present them as actual requirement compliance or calibrated truth.
   Show allocation policy/counts separately from fixed benchmark/reporting weights; use comparable per-category
   original/candidate evidence. A pooled adaptive pass fraction is not future compliance. Explain that error bars do
   not cover missing scenarios or unknown usage frequencies, and fresh draws do not establish generator breadth.
   Label an attempt stopped for contract revision separately from statistical below-minimum and cap-inconclusive
   decisions, and label a known remote governing-instruction source as certification unavailable, distinguishing a
   pre-allocation refusal from a spent attempt stopped after discovery. Retain its source-role evidence, valid outcomes,
   and any spent allowance. Report a material response-contingent obligation as a coverage gap and certification
   unavailable under the fixed-turn protocol, likewise distinguishing pre-allocation refusal from later discovery.
   Do not display targeted adaptive diagnostics as representative or formal compliance rates. Ordinary remote task
   data remains admissible and within semantic evaluation scope, with its observation limits. Report excluded
   image/audio/video outputs as `certification unavailable: unsupported output` and refused known persistent-write
   paths or missing external-service authority as `certification unavailable: ineligible trial effect`, rather than
   statistical failures; report unobservable effects as limitations. Include observed child delegation depth, parallel
   participation, instruction roles, worktree merges, and effects in the trial evidence view. Report missing child or
   merge observations as limits to the judgment and measurement identity; never display child runs as additional
   formal samples. A diagnosis that identifies a needed edit to an unauthorized
   external linked target, or an attempted edit through its link, reports the affected requirement or edit, link path,
   resolved target where observable, read-only consequence, and available user choices. For an already authorized
   external link, an observed need or attempted create, rename, delete, mode change, or link retarget reports the
   blocked operation or requirement, paths, existing-file-content-only limit, consequence, and choices to edit
   existing content if sufficient, use another authorized in-project source, or stop/revise. Reauthorization of that
   same target is not offered as a route to structural editing. An in-project symlink create, delete, or retarget attempt
   or existing-file mode-change attempt reports the blocked operation and fixed-entry or fixed-mode scope; a diagnosed
   need directs the user to prepare that change before a new campaign or pursue an allowed edit. Neither diagnosis
   claims complete detection; a rejected attempt is neither a candidate nor a compliance outcome. Reports distinguish accepted
   authorized external-target changes retained in a candidate from H10's verified delivery to the live target.
   Publish immutable
   point-in-time results with
   category evidence, complete measurement identity, explicit limitations,
   and the applicable prediction. Do not imply deterministic replay or equate snapshot identity with consumed bytes.
2. For the four outputs' candidate-versus-original comparison, check required material conditions field by field
   using exact recorded equality, excluding candidate identity and observation time as specified by the contract.
   Name known differences; missing required data yields `INDETERMINATE` comparability and an unavailable original
   comparison, never equality. Keep this check within result calculation and presentation, with no general operation
   for comparing arbitrary saved results. Current applicability is a distinct decision.
3. Use W1's H9 observer at result use/resumption and throughout execution. A detected change pauses scheduling as
   `MEASUREMENT_CHANGED` before another model call. Offer restoration, a new measurement identity, or cancellation.
   A changed behavioral candidate uses the new-formal-candidate rule; a materially changed contract uses a new lineage.
   Never pool outcomes across changed measurement conditions.
   A new measurement identity alone does not reset the campaign’s allowance sequence. Distinguish snapshot
   identity from formal-attempt identity: a fresh formal attempt under changed conditions reserves the next campaign
   allowance even if its starting snapshot is unchanged. Restoration resumes the original attempt only when its retained
   evidence remains valid under the original conditions. Keep any affected semantic outcomes in history with their
   measurement identity; do not silently relabel them as failed transport calls or draw replacements to improve a score.
4. Preserve the historical result and name each observed material change that makes it historical. Hidden provider
   changes remain an explicit limitation.
   Report the fixed loader configuration as a measurement condition. A diagnosed need to change which prompt files
   load directs preparation before a new campaign; an attempted configuration edit rejects candidate admission when observed or present in the final delta,
   not a trial outcome.
   Runtime source-path events cannot retroactively change the claimed starting snapshot into a byte-consumption receipt.
   Compare the fixed loader configuration and observed link paths, resolved destinations, target contents, and loading
   relationships where available;
   a material difference pauses before another call and makes the prior result historical for the changed system.
5. Before H10, re-read H8 (or H4 for an unchanged initial candidate) and verify that the optimization worktree and
   isolated external-target representation still equal the frozen certified candidate or
   the retained selected uncertified candidate named by a permitted explicit user request. Validate H7’s acceptance
   authority; a certified label alone is insufficient. If an unowned edit is detected, stop for
   user direction and retain the earlier result only for its recorded snapshot.
   After H10, validate its combined base/external delivery journal and exact final snapshot before reporting the
   current Git diff, external change record, delivered status, or current certification applicability. A preflight
   conflict leaves both destinations untouched and reports the choice needed from the user. A conflict detected
   during application retains its partial journal, stops further writes, and cannot be reported complete. A disjoint
   live edit to another participating prompt source or another material difference makes a certification
   historical for the combined system even if H10 succeeds. An explicitly requested uncertified delivery remains labeled
   `not certified`. The user commits, reverts, or requests revision; CAT performs none of those choices
   automatically.
   If the candidate includes authorized external target edits, verify each live link/target observation and show the
   exact transferred changes. Report delivered/current only after the full combined snapshot matches H10; a base
   merge alone cannot establish that claim.

**Accept:** each material-condition mutation, missing original-comparison data, unknown provider state, changed sources during a
trial, before-next-call pause, restoration/new-identity/cancel recovery, unowned optimization-worktree change,
policy-accepted certified and explicitly requested uncertified merges, same-file conflict, and immutable historical results. An end-to-end revision makes
the previous certification historical while retaining its evidence.
Include observable file/directory-link identity and material target/loading changes, exact authorized external
candidate snapshots, complete/partial delivery status, and final combined applicability. Verify that identified need
and rejected observed operation or final delta for an unauthorized external target or an out-of-scope operation through an authorized link
produce the precise path/operation/scope warning with viable choices and no false candidate/compliance result.
Include unequal original/candidate category sample counts with the same per-category rates: changing allocation alone
must not change a fixed-weight improvement estimate. Preserve diagnostic labels and coverage limitations in both phases.

### W7 — Integrate the skill, complete CLI operations, and installed distribution

**Owner:** optimizer-facing API/CLI composition, maintained prompt-optimizer skill resources, harness registration,
distribution wiring, and user documentation. **Requires:** W2–W6 integration; P1–P4, P6, and I1 passed for the claimed harness.
The minimal invocation/ESC adapter is built and checked in W1; this assignment completes its user-facing workflow.

1. Expose complete caller operations for proposal/feedback/reproposal/confirmation, execution/resumption, interruption/cancellation,
   unowned-worktree-change and delivery-conflict decisions, pending outcome choices, separately labelled uncertified
   acceptance, a safe-checkpoint contract-revision request with continuation or explicit formal-attempt stop,
   explicit new-campaign versus resume operations, and status/result with the four outputs and current/historical
   applicability. The CLI handles paths,
   identity, recovery, and structured results; prompts do not reconstruct those mechanics or publish internal steps.
2. Make the skill present the full proposal, gather feedback for a complete re-proposal, obtain explicit confirmation,
   consume structured lifecycle results, and
   request only user authority/preferences. On an observed unowned optimization-worktree change or combined delivery conflict,
   present the affected paths and retained evidence and ask how to proceed before another dependent action. On next
   invocation in the starting harness, discover the canonical target's resumable campaign and offer resume/cancel.
   In the other harness, show the campaign and its starting harness and direct the user there to continue or close it;
   expose no state-changing operation for that campaign or root/target. Do not ask the user to locate
   its records or derive candidate/test mechanics. When a revision is requested during formal testing, show the
   current attempt and its retained evidence, offer continuation or an explicit stop for revision, and keep the
   revision pending until the native and worktree checkpoint is safe. Present a known remote governing-instruction
   source as unsupported for certification, without reclassifying it as task data or spending a formal attempt when
   detected before allocation. Present a material response-contingent obligation as unsupported by the fixed-turn
   certification protocol, with the uncovered obligation and the retained evidence; do not silently certify a subset
   or spend a formal attempt when known before allocation.
   State in the installed user flow that certification concerns future pass labels under the frozen judge and confirmed
   benchmark; objective assertions may inform that judge but cannot replace its semantic decision. Show excluded
   image/audio/video outputs and known persistent-write paths with their exact unsupported-output or ineligible-effect
   dispositions, and display unobservable
   effect limitations without claiming arbitrary executable containment.
   Present the read-only warning for an unauthorized external linked target when diagnosis identifies a needed edit or
   an authoring attempt tries one, naming the affected requirement or attempted edit, observable link/target paths,
   consequence, and available user choices. Also present the content-only scope warning for an already authorized
   external link when diagnosis identifies a needed structural change or an attempt tries it, naming the blocked
   operation, paths, consequence, and viable alternatives without offering reauthorization of the same target as a fix.
   Neither warning promises that diagnosis finds every needed edit. Show an in-project symlink create, delete, or retarget
   attempt or existing-file mode-change attempt as rejected when observed or present in the final delta, with the prohibited operation and scope limit; explain the
   prepare-before-new-campaign or allowed-edit choices when diagnosis identifies such a need.
   Show an observed loader-configuration edit or final delta as a rejected attempt and direct a desired loading-rule change to preparation
   before a new campaign, while leaving authorized ordinary prompt-file edits available. Show explicitly
   authorized external edits in candidate review. After acceptance, present the single automatic conflict-checked
   delivery across the base and external targets, any conflict requiring user direction, and partial recovery status.
   Show final delivery and applicability only from W6's verified combined H10 result.
3. Inventory and reconcile callers of the existing observation/diagnosis skill, including rule-triggered use. Preserve
   their authorized scope: diagnostic entry must not silently confirm a contract or begin certification. Update callers,
   routing descriptions, shared body, harness wrappers, help, and documentation together. Remove superseded instructions
   from the supported flow rather than maintaining two conflicting optimizer procedures.
4. Keep shared skill behavior harness-neutral and native details in their wrappers. Follow existing assembly/install
   mechanisms. If persisted plugin-data migration is needed, the installer owns it; runtime uses the current contract.
5. Exercise the installed skill naturally from an optimization request, not only by explicitly loading its body. Verify
   complete proposal, feedback and re-proposal, confirmation, progress labels, ESC, resume/cancel, unowned-edit stop, base merge conflict,
   recursive and parallel trial-agent subagents, native child worktrees and merge into the isolated trial, one outcome
   per assignment, child cancellation/restart reconciliation, and child source/effect limitations,
   precise failures, the accepted default and optional acceptance policy, all decision-table rows, development
   checkpoint choices, formal-attempt revision stop/restart, explicit uncertified delivery, cross-harness discovery and
   refusal without side effects, and final reviewable diff.

**Accept:** public-boundary tests reject internal-only operations before side effects; skill selection and application
have separate evidence; both supported harness routes reach the same feasible product behavior. Installed tests prove
native execution, loading, host-tool use, write control, and lifecycle behavior rather than inspecting package contents.

### W8 — Close the complete acceptance inventory

**Owner:** integration and acceptance evidence. **Requires:** W0–W7 integrated and focused checks passed.

Run the inventory below against one recorded source/distribution snapshot. Reconcile all known findings before final
acceptance. Audit reachable production entry points, direct service paths, durable records, failure/restart paths, and
user documentation against their assigned obligations even if focused checks pass. Record absent fields, bypasses, or
unexercised outcomes and keep affected assignments open. Recheck only evidence whose determining inputs changed.
Audit the optimizer package's `DESIGN.md` against the accepted product choices, this plan's handoffs and acceptance
gates, and the integrated behavior. Return missing or stale choices to their owning assignment before final acceptance.

Close the replacement inventory: prove every displaced implementation and obsolete caller, test, dependency, route,
and packaged artifact has been removed or replaced. Verify retained supported behavior and required historical data
through their current consumer boundaries. Leftover old implementations are an unmet completion condition.

Finish with the unrestricted project build and installed organic acceptance. Do not replace an unrun required gate
with a mock, source inspection, a successful launch, a candidate diff, or a previous build of different inputs.

## Dependency and release order

| Assignment | Required predecessors | Independent eligible work |
|---|---|---|
| W0 | None | Probe capabilities separately; consolidate only the decisions their evidence affects. |
| W1 | Product contract; P3 for native integration, then W1's own I1 gate | Local persistence, base-to-optimization fork and baseline capture, observation, and ownership while probes run; implement the minimal invocation adapter before I1. |
| W2 | W1 H1 verified fork and H3 for local proposal/confirmation; W1 H9 and I1 for native call consumers; P1 and bounded P4 readable-source observations for native derivation | Local proposal/confirmation supplies the epoch input W1 H9 needs; its controlled-fixture checks can run before native derivation passes. Full staged-external P4 follows H2 and W4 staging at the first W3 combined trial. |
| W3 | W1/W2, W4 initial snapshot and W5 bootstrap H12; P1/P3 and I1 for native trials | Generator/evaluator mechanics are independent; first campaign trial waits for H4 and the empty-evidence H12 producer. W3 performs full P4/P6 when staged-source and child-enabled H5/H6 paths first run; formal trials also wait for freeze/H11/P5. |
| W4 | W1 H1/H3 and W2 H2 for isolated staging and native authoring; W3 initial measurement and W5 round selection for generated candidates; W5 stronger-evidence selection for H8; W5 certification plus acceptance authority or a permitted explicit uncertified request, then W6 snapshot check for H10 | Initial candidate admission requires no authoring. W4 first stages an H4 attempt, then runs full P2 before accepting native authoring; W3 runs staged-source P4 at the first combined trial. H8 is checked before incumbent adoption or formal freeze. Combined in-project/external H10 delivery follows policy acceptance or a permitted explicit uncertified request and uses one preflight and journal. |
| W5 | W1 H12 persistence, W2 H2 and W4 H4 for bootstrap allocation; W3 H6 for later development decisions; W3/W4 generation and H8 promotion for revised formal candidates | Bootstrap allocation needs no trial result. Later development selection produces W4 generation input without waiting for W4 native authoring. Local statistical fixtures and cap calibration are independent; P5 against the actual H2/H5/H6/H11/H12 process gates formal admission. |
| W6 | W1/W3/W4 H8/W5 for candidate preflight; W4 H10 for merged-result reporting | Original-comparison validity and exact-snapshot preflight can be checked before H10; final report waits for its merge receipt. |
| W7 | W2–W6 and supported native gates, including I1 | Complete the minimal invocation adapter already verified in W1; add full skill interaction and packaging. |
| W8 | All required assignments and gates | Acceptance fixtures are prepared with their owning assignment. |

Release dependent implementation only from a snapshot containing its integrated predecessor, a compiling shared
boundary, and current predecessor-focused evidence. Tests that cannot compile against a missing predecessor are not
behavioral red tests. At the first runnable handoff, run the combined producer/consumer check before accepting the
consumer. W1 produces the verified fork and initial source storage; W2 produces confirmed baseline authority; W4
consumes them. W5 first publishes H12 from H2/H4 and empty evidence; W3 then measures that unpromoted candidate before
W5 selects or reprioritizes it. W4 then produces H8's verified
optimization-worktree promotion before W5 adopts or formally freezes it. When H7 supplies certified acceptance
authority or a permitted explicit uncertified request, W6 verifies the exact selected snapshot; W4 then produces H10's
combined base/external delivery, and W6 reports it after verifying the final snapshot. W3 produces measurement observations before W6 consumes them. Neither route
depends on a later report or candidate to create its own prerequisite.

## Requirement coverage and acceptance inventory

The named cases below are required acceptance scenarios, not claims that test classes already exist. Their owners add
failure-capable automated tests where practical and retain native execution evidence where that boundary is required.
All rows are initially **unrun**; none are excluded. Each G gate passes only when all its mapped cases pass.

| Case | Product obligation and observable acceptance | Owner / boundary |
|---|---|---|
| A01 | G1/G7: a naturally invoked installed optimizer forks a clean base worktree, improves a deficient prompt in the optimization worktree, certifies its exact selected snapshot, then merges content and create/rename/delete changes into the base as a reviewable Git diff. No automatic commit/revert; user revision remains reachable. | W1/W4/W5/W6/W7; organic installed workflow. |
| A02 | G1/G5/G6: a new campaign rejects staged, unstaged, or untracked base changes before forking or trials and directs the user to resolve them and retry; a clean fork is verified. After the fork, unselected attempts and uncertified work leave the base unchanged absent a permitted explicit uncertified-acceptance request; base-only staged, unstaged, untracked, and structural edits survive delivery when disjoint from optimization changes, including after a restart. A same-file edit on both sides or structural collision stops for user direction without overwrite. A disjoint base-only edit to another prompt source merges but makes certification historical for the combined base snapshot. CAT-owned edits in candidate-generation worktrees remain supported. | W1/W4/W5/W6; real Git worktrees and delivery merge. |
| A03 | G1: native editor and shell calls receive the generation edit restrictions. Allowed ordinary in-project and linked-target content edits enter exact isolated snapshots. Focused prompt tests record adherence counts and conditions for forbidden structural, symlink, mode, loader, and unauthorized-target operations. Injected forbidden deltas and observed forbidden operations reject the whole attempt before any candidate or compliance outcome. Complete no-follow validation covers untracked and ignored files, symlink metadata, modes, and staged external content; Git diff alone is insufficient. Earlier edits remain isolated and retry requires reconciliation. Warnings name the operation, link and observable target, scope, consequence, and viable alternatives. No attempt or trial write reaches the selected/base worktree or live external target. Unobserved reverted temporary edits are outside the detection claim. | W0/W4/W6/W7; prompt adherence, existing isolation, admission validation, and report. |
| A04 | G2: extraction lists every requirement and source in a fixture with multiple files, conditional/lazy loading, a readable user-level instruction file outside the forked project, linked file and directory sources, and material applicability interactions. Link path and resolved target are recorded where observable. In-project targets use confirmed editable authority for ordinary-file content/create/rename/delete, with symlink entries/destinations and existing file modes fixed; external resolved targets are read-only until explicitly authorized for existing-file content edits only, with no create/rename/delete, mode change, or link retarget. Each category's reason and full test/evaluator/variation proposal is visible. A diagnosis that identifies a needed unauthorized external linked-target edit or a barred structural edit through an already authorized link warns of the affected requirement, paths, scope limit, consequence, and viable choices without promising complete detection or suggesting reauthorization of the same target for structural work. | W2/W4/W6/W7; production derivation, authorization, diagnosis, and presentation. |
| A05 | G2: development/formal operations before confirmation perform no trials; feedback requesting additions, removals, category changes, expected-outcome changes, evaluator changes, and probability changes yields a complete new proposal. Only its confirmed version affects subsequent trials. | W1/W2/W3/W7; public feedback, proposal, confirmation, and assignment operations. |
| A06 | G2/G6: the same campaign/claim resumes its lineage; material contract revision creates another claim while retaining campaign allowance; candidate-only revision does not reset lineage. After formal testing begins, revision requires a terminal or explicitly revision-stopped attempt and reconciled work. Crash during H2 activates once. | W1/W2/W5; durable restart. |
| A07 | G3: every category must have a lower bound strictly above 0.95; missing/extra categories, equal boundary, and a category without a passing sequential receipt cannot certify. Strong categories cannot compensate for a weak category. | W5; exact statistics and campaign decision. |
| A08 | G3: allowances follow `0.05/(i*(i+1))`; verify the first three illustrative all-pass sizes and boundary failures. Process restart, resumed cancellation, locator changes, and in-campaign contract revision cannot reset the campaign formal-attempt sequence; an explicit new campaign resets to 1 even for the same claim and reports the separate guarantee. | W1/W5; exact fixtures and durable lifecycle. |
| A09 | G4: exact sequential bound/cap fixtures satisfy the confirmed significance and power rule; the plan is recorded before draws; early decisions and repeated checks are valid, and caps cannot be extended after outcomes; dependence assumptions match the selected procedure. Unsupported assumptions produce certification unavailable. | W0/W5; statistical review, fixtures, admission. |
| A10 | G4: invalid generator support, expectation changes, wrong within-category probabilities, or candidate/outcome-dependent selection within a category cannot enter representative/formal evidence. H12 may adapt category allocation; targeted diagnostics are separate. Development/formal streams are distinct and concrete instances retained. | W2/W3/W5; generator validation, allocation and sampler. |
| A11 | G4: verified selection of a revised candidate in the optimization worktree and formal freeze precede assignment and execution; authoring cannot see reserved formal instances; prior development evidence cannot become formal evidence; new candidates get fresh assignments. | W3/W4/W5; complete assignment path. |
| A12 | G4: trial-private edits and later lazy loads do not revise the assigned candidate; a representative/formal multi-turn trial has a complete prewritten sequence and incidental values assigned before its first turn, then stays in one fresh session, one logical trial, one assignment, and one semantic outcome across completion, retry, and restart. A later user turn is never chosen or rewritten from an earlier agent reply. | W3; installed runner and retained assignment. |
| A13 | G4: evaluator rubric/schema/profile stay frozen despite instructions embedded in candidate/output; validation failures are infrastructure failures. A valid semantic failure cannot be discarded through retry or a lost reporting response. | W3; evaluator boundary and result adoption. |
| A14 | G4/G6: transport failure without a semantic result retries the identical assignment; duplicate completions count once; crash between native completion and adoption recovers retained evidence without blind replay. | W1/W3; H3/H5/H6 fault-and-restart tests. |
| A15 | G1/G3: development and certification show all four outputs; development is exploratory and never certified. Original reuse retains its uncertainty; differences use their own intervals; unavailable comparison remains explicit. Outcome decisions retain evidence and allowance. | W5/W7; complete workflow. |
| A16 | G1/G6: plateau requires a bounded attempt for every failing category and a whole round without a stronger distinct valid candidate; interrupted/incomplete/failed infrastructure rounds and elapsed time cannot trigger plateau. Restart retains the round's category set and completed attempts, then resumes unfinished categories. A stronger distinct candidate below readiness starts another round automatically without another user request, while a ready candidate advances to formal admission. | W5; development workflow and restart. |
| A17 | G5: changing each required material condition makes certification historical without altering its original observation; status names the observed material changes and records limitations for unavailable source/tool/provider conditions. | W6; result use/resume. |
| A18 | G5: the four outputs' candidate-versus-original comparison names material differences, permits candidate identity and observation time to differ, and reports `INDETERMINATE` comparability and an unavailable comparison when required recorded conditions are missing; required conditions use exact recorded equality. Current/historical applicability remains separately reported, without a general saved-result comparison operation. | W5/W6/W7; original-comparison result and presentation. |
| A19 | G5: source-path observations and starting snapshots are labeled accurately; runtime bytes are not claimed from paths, hidden provider stability is not promised, and external/MCP observations are retained when available. | W3/W6/W7; actual collection and rendered result. |
| A20 | G4/G5: detecting a material candidate/condition change pauses as `MEASUREMENT_CHANGED` before another call; restoration, new identity, and cancellation follow their distinct rules without pooling evidence or resetting the campaign’s allowance sequence. | W1/W5/W6; scheduling and resume boundary. |
| A21 | G6: ESC stops scheduling promptly and requests native cancellation; completed outcomes, assignments, contract, candidates, and allowance survive; resume skips completed work. Test interruption racing completion. | W0/W1/W3/W7; live invocation and restart. |
| A22 | G6: canonical root/target admits at most one resumable campaign; next invocation in its starting harness offers resume/cancel; cancellation causes no subsequent call and retains history for the same claim. | W1/W7; invocation and concurrent admission. |
| A23 | G6: combined completion-before-adoption, interruption, target-reference change during isolated authoring and optimization-worktree promotion, cancellation, certification, base merge, and restart retains validated call, stop, assignment, lineage, and target evidence at first use. | W1/W4/W6/W7; combined recovery trace defined above. |
| A24 | G1/G5/G6: crash during internal promotion recovers the same selected candidate without duplicate edits; crash before, during, or after an authorized base merge retains the result and delivery authority and reconciles the exact journal without recertifying, relabeling an uncertified candidate, or overwriting base-only edits. | W4/W5/W6; H7/H8/H10 restart. |
| A25 | G7: real nested agent uses the installed current-harness runner, production loading, and a host tool/configuration; both supported harness integrations have boundary evidence. No Docker, immutable runtime, or binary closure is required. | W0/W3/W7; organic native integration. |
| A26 | Scope/architecture: all users receive the same optimizer behavior; public flow contains no entitlement, billing, spending forecast, release, telemetry, or executor-ledger requirement. Existing diagnostic callers cannot bypass confirmation. | W7/W8; caller inventory and supported entry tests. |
| A27 | Terminal build: unrestricted reactor verification passes on the complete final working tree with no skipped modules, tests, or linters. | W8; supported build command below. |
| A28 | Replacement removal: every implementation displaced by this design, together with obsolete callers, tests, fixtures, wiring, dependencies, and documentation, is removed. Retained entry points and migrated history work through the current installed flow; retired behavior is neither reachable nor distributed. | W0 inventory; W1–W7 cutovers; W8 final source/reference and installed-boundary audit. |
| A29 | G1/G4: a first invocation with no earlier revisions captures an immutable initial candidate before evaluation. An initially ready candidate reaches formal evaluation without a cosmetic edit; an initial failure supplies authoring evidence. | W1/W2/W4/W3/W5; production bootstrap through first decision. |
| A30 | G4: a category containing test definitions with different success probabilities draws definitions independently with replacement from the frozen distribution. Fixed per-definition quotas cannot enter the IID binomial procedure. | W3/W5; sampler through statistical admission. |
| A31 | G6: the minimal live invocation reaches I1 after its W1 producers exist; stopping races authorization, native handle creation, and result adoption without authorizing a later call. | W1; admission and real invocation adapter. |
| A32 | G1/G3: a failed formal category enters the next development round even when development readiness already passes. The same failed candidate does not automatically re-enter formal testing without a stronger distinct revision. | W5; formal failure through development and next admission. |
| A33 | G1/G6: an unowned optimization-worktree edit detected before a next action, after an owned write, or on restart stops scheduling, preserves the worktree/evidence, and requires user direction; no changed content enters candidate or formal evidence silently. The detection claim excludes transient edits reverted before observation. | W1/W4/W5/W7; actual worktree observation and public resume boundary. |
| A34 | G1: a plateau, interruption, cancellation, or infrastructure failure does not automatically transfer optimizer changes to the base worktree; the optimization worktree and evidence remain available. A permitted explicit uncertified-acceptance request merges the selected candidate through H10's conflict and restart rules and reports `not certified`. | W1/W4/W5/W6/W7; terminal, user request, and restart paths. |
| A35 | G1/G3/G6: exercise every product decision-table row, both preselected acceptance policies, clear-regression override, uncertified choice permission/label, and preservation of candidates/results. Certification cannot bypass acceptance or authorize a default regression. Restart preserves a pending choice. | W1/W2/W5/W6/W7; policy through H7/H10. |
| A36 | G3/G4: an unchanged candidate can continue within its frozen cap under the same allocation. Restart preserves counts and caps. At an unresolved cap the attempt ends without certification and retains the original; an explicitly authorized later test uses fresh draws and the next allocation. No cap extension or automatic retest can bypass that terminal decision. | W1/W3/W5/W6; evaluation and recovery. |
| A37 | G1/G3/G5: formal improvement-required acceptance waits for valid original measurements, a predeclared comparison metric/procedure, and repeated-attempt/look error accounting. Minimum-only certification does not imply a joint comparison guarantee. Reused development baseline data cannot be treated as fresh formal evidence. | W2/W3/W5/W6; comparison producer and decision. |
| A38 | Maintainer design record: `client/common/cli/src/main/java/io/github/cowwoc/cat/common/optimizer/DESIGN.md` documents every accepted optimizer design choice, including clean campaign start, post-fork base changes, editable linked files/directories under confirmed authority, explicitly authorized external existing-file content edits only, preserved in-project ordinary-file create/rename/delete with fixed symlink entries/destinations and existing file modes, exact isolated candidate/trial snapshots, observable identity/limits, material-change handling, bounded unauthorized-write warnings, prompt restrictions, measured adherence, complete no-follow admission validation, and rejection of observed out-of-scope operations or final deltas, and automatic combined conflict-checked delivery after acceptance. For that delivery choice it records the review-only patch alternative, rationale, one-journal recovery, affected interfaces, and acceptance evidence. It records each choice's contract source, rationale, consequential alternatives, affected components or interfaces, and acceptance evidence. Superseded choices cannot appear as current instructions. | W0 seeds; W1–W7 update their choices at slice closure; W8 reconciles against the product contract, plan, and integrated behavior. |
| A39 | G1/G4/G6: a reproducible defect triggers diagnosis/edit before a full representative batch; further batches follow the product's coverage/adaptive policy, with extra attention to unresolved categories. Formal readiness needs every category’s 100/99% and latest-two-batches/98% criteria and no unresolved repeatable defect/regression, without a development formal lower-bound gate. Old-version/diagnostic results cannot count toward readiness. | W1/W3/W5/W6; development selection and formal admission. |
| A40 | G1/G6: an unresolved category at 200 representative trials enters review before further sampling; choose further evidence with a recorded next checkpoint, optimization, or stopping. Restart preserves counts, batches, candidate/results, and the pending choice. Ready/weak outcomes act earlier and a checkpoint cannot declare a plateau. | W1/W5/W6/W7; review and resumption. |
| A41 | G1/G3/G5: the default accepts a certified minimum without requiring formal superiority trials unless there is a clear regression. Original observations are measured during development, reused with uncertainty, and remain exploratory alongside certification. An explicitly selected improvement requirement invokes its valid comparison procedure. | W2/W3/W5/W6/W7; original measurement through acceptance. |
| A42 | G2/G4/G5: CAT proposes the product's explicitly labelled balanced benchmark with equal test-definition weights within each category and coverage-oriented variation probabilities. The user confirms or revises probabilities in the complete proposal; H2 preserves explicit assumptions, assumed-usage limitations, allocation policies, and confirmation. Rare requirements stay covered; outcome-dependent changes to frozen within-category or reporting weights are rejected while category allocation may adapt. Claims identify their benchmark scope and unknown actual usage frequencies. | W2/W6/W7; derivation, confirmation, and report. |
| A43 | G4/G6: representative development and formal evaluation may share definitions but retain separate assignments/executions. Formal draws follow candidate/protocol freeze; restart preserves draw/assignment identity; retries reuse the logical trial; edits receive fresh formal assignments. Naturally repeated values remain valid and old development/formal outcomes cannot be relabelled as new certification evidence. | W1/W2/W3/W5; generator through formal admission and recovery. |
| A44 | G3/G4/G6: calibrated caps 250/317/334 meet the default per-category planning power, with strict anytime lower/upper boundaries and both early stops included. Prove permitted checks/continuation, freeze-before-draw, category success retention, immediate candidate termination on below-minimum or unresolved cap, no post-outcome cap increase, and restart without extra samples/allocation. Display directional/two-sided confidence levels without a joint-coverage claim. | W1/W3/W5/W6/W7; calibration through scheduling and report. |
| A45 | G1/G4: balanced initial development batches precede alternating coverage/adaptive batches. Extra allocation uses current-candidate rates/uncertainty with the specified ties; a raw failure count or a single pass cannot override that rule. Ready categories retain coverage; improvement reduces extra attention; actionable defects preempt batches. Checkpoints and readiness stop unnecessary calls. | W5 policy; W1/W3 combined allocation-to-outcome path. |
| A46 | G3/G4: formal category allocation changes after retained outcomes while each next within-category draw keeps its frozen conditional distribution. Certified categories stop; below-minimum/cap stops end the attempt. Category scheduling preserves counts, caps and allowance, and cannot change generators or borrow caps. | W3/W5 scheduling and P5 statistical applicability; acceptance remains unrun until both pass. |
| A47 | G4/G6: the first H12 needs H2/H4 and no earlier outcome. Restart after H12 publication but before H5 reuses its choice; restart after H6 but before batch advancement adopts once. Missing/stale indexes, changed measurement/lineage, retries and candidate revisions preserve valid evidence and reject unauthorized new dispatch. | W1/W3/W5; H12 first-use and combined publication/restart checks. |
| A48 | G1/G4/G5: unequal allocation alone cannot change a fixed-weight original/candidate comparison or turn a pooled adaptive pass fraction into future compliance. Reports separate counts, diagnostic purpose, benchmark/reporting weights, uncertainty, and unknown-usage/coverage limitations. | W5/W6; calculation and rendered outputs in both phases. |
| A49 | G2/G4: a generator with many fresh template values but a missing confirmed behavior fails coverage admission. Targeted failures remain useful development evidence without entering readiness/formal counts. Fresh post-freeze formal assignments and campaign error control remain required after adaptive development; no report claims elimination of overfitting or coverage of omitted uses. | W2/W3/W5/W6; coverage validation, evidence admission and report. |
| A50 | G2/G3/G6: requesting a material revision during a live formal attempt offers continuation or an explicit stop. A stop prevents new assignments, reconciles native calls, retains every valid outcome, records unexecuted assignments without semantic outcomes, terminalizes the old attempt once as `not certified: stopped for contract revision`, and preserves its spent allowance. Unknown native disposition defers revision across restart. Only after H8/H10 and call/trial work are reconciled may H2 activate a new lineage; old certification and trial evidence remain attached to the old claim. | W1/W2/W3/W5/W6/W7; public choice through H3/H5/H6/H11/H2 fault-and-restart boundary. |
| A51 | G2/G3/G4/G5/G6/G7: a readable user-level instruction file contributes requirements and remains read-only; a declared or observed remote/MCP response used as governing instructions is reported as unsupported for certification and cannot be silently omitted or relabelled task data. Known pre-allocation cases spend no allowance and dispatch no formal trial. Discovery after allocation stops new assignments, reconciles native calls, retains valid outcomes and the spent index, and survives restart as `certification unavailable: unsupported instruction source`, never as a statistical decision. An ordinary remote response used as task data remains admissible with source observations and limits, including when its text contains commands; unobservable host roles remain an explicit detection limit. | W0/W1/W2/W3/W5/W6/W7; source-role derivation, formal admission, native trial, report, and fault-and-restart boundary. |
| A52 | G6/G7: Codex and Claude expose the same installed optimizer certification workflow through their own native runners. A campaign started in either harness remains bound to that harness across restart and target relocation. The other harness's invocation discovers and displays its identity and starting harness, directs the user there, and refuses resumption, advancement, cancellation, concurrent new campaign creation for the same root/target, formal allowance allocation, and native call dispatch without changing campaign state, evidence, history, or allowance. Concurrent cross-harness invocations and a stale-index read-only lookup leave execution authority with the starting harness; an unresolvable index reports failure without mutation. | W0/W1/W7; both installed entry points, canonical lookup, native admission, concurrent invocation, and restart. |
| A53 | G2/G3/G4/G5: a fixed multi-turn definition binds its full turn sequence and incidental template values before representative/formal execution; an attempted response-dependent follow-up is rejected for those evidence purposes, while an explicitly targeted adaptive diagnostic is permitted and excluded from their counts. A material response-contingent obligation cannot be omitted or represented as fully covered by fixed turns: W2 exposes the gap, W5 refuses H11 before allocation, and W6/W7 report certification unavailable. Discovery after allocation retains valid evidence and spent allowance, stops new assignments, and recovers the same nonstatistical disposition after restart. | W2/W3/W5/W6/W7; contract coverage, H5/H6 native multi-turn trial, admission, report, and fault-and-restart boundary. |
| A54 | G1/G4/G5/G6: authorized linked file and directory changes are captured in exact candidate snapshots and staged through production loading for development and formal trials without changing the base or live external target. For explicitly authorized external file and directory links, only content edits to existing resolved files enter a candidate and delivery; create, rename, delete, mode change, and link retarget observed operations or final deltas reject the attempt, while in-project ordinary-file create/rename/delete remain supported. In-project symlink create/delete/retarget and observed existing-file mode-change operations or final deltas reject the attempt before admission, are reported with their scope limits, and contribute no candidate or compliance evidence. Observed need or attempted forbidden edits through an authorized external link produce the precise path/operation/content-only-scope warning and viable alternatives, excluding reauthorization of the same target. H9 detects material link/target changes. Accepted delivery preflights all base and external change sets and link identities before any write; a conflict or changed destination leaves both untouched and requires user direction. A clear preflight automatically applies disjoint changes to both destinations under one journal. Faults before, between, and after destination writes recover idempotently without overwriting intervening user edits, preserving partial evidence. W6 reports delivered/current only after verifying the final combined snapshot; an uncertified override remains `not certified`. | W0/W1/W2/W3/W4/W6/W7; P2/P4 native staging and H4/H5/H8/H9/H10 combined delivery/restart boundary. |
| A55 | G2/G3/G4/G5: an authentic remote/MCP response used as task data remains in the test input and semantic judgment, including an imperative-looking response; an observed governing-instruction role remains unsupported. Source-role evidence and missing host observations appear in the result. A text response and an open-ended file/tool effect are judged under the frozen rubric, while an objective assertion alone cannot certify compliance. The 95%/95% statistics and report concern that judge's future pass labels under the confirmed benchmark, with no actual-compliance or calibration claim. | W0/W2/W3/W5/W6; P4 role observation, H2/H5/H6 evaluator boundary, H11/H7 claim, and rendered result. |
| A56 | G2/G4: image, audio, or video output requirements are identified as outside this version's certifiable outputs and cannot be omitted or replaced by text assertions to certify the full claim. Text and observable file/tool effects remain eligible. | W2/W3/W5/W6/W7; proposal coverage, H11 admission, and user-visible disposition. |
| A57 | G4/G5/G7: authentic trials may invoke arbitrary host executables when each used external service has confirmed read-only credentials or a disposable fixture. A known persistent write to host paths outside the trial copy or persistent service state, or missing service authority, is refused before dispatch without a semantic outcome; a pre-allocation refusal spends no formal allowance, while discovery after allocation preserves its spent index and valid earlier outcomes. A read-only service and a disposable fixture run through the native boundary; effects not observable by CAT are reported as limitations, with no claim of complete executable containment. | W0/W2/W3/W5/W6/W7; P4 effect observations, H2/H5/H6 admission and result, installed native trials, and report. |
| A58 | G1/G2/G5: in a real conditional/lazy-loading prompt system, H1/H2 identify the loader configuration at the clean fork and H4/H5/H8 preserve it while authorized ordinary in-project prompt-file content and create/rename/delete changes remain eligible. An attempted loader-configuration edit is forbidden by the generation prompt and, when observed or present in the final delta, rejects the attempt before candidate or trial admission, including when the path lies under an editable root; H10 refuses any such delivery delta. Diagnosis directs a needed loading-rule change to preparation before a new campaign. An observed in-campaign configuration or loading-relationship change triggers H9's pause and historical-result path rather than silently pooling evidence; restoring the recorded configuration can resume, while keeping the change requires a new clean campaign. | W1/W2/W3/W4/W6/W7; H1/H2 fixed authority, native authoring/trial/loading boundary, H8/H9/H10 admission and delivery, and result presentation. |
| A59 | G2/G3/G4/G5/G6/G7: authentic installed-runner trials in both harnesses let the tested agent delegate to a child, a recursive grandchild, and parallel children in separate native Git worktrees, then merge child work into the isolated trial copy. P6 proves native completion/effect/merge observations, not merely child launch. H2 includes child-visible instruction sources; H3/H5 bind the whole tree to one assigned frozen candidate and logical trial; H6 retains child text/file/tool effects and final merged state for one frozen-judge outcome; H11/H7 count one sample. Child trial effects never change the frozen candidate, base, or selected optimization worktree; known remote governing child instructions and persistent child writes follow existing ineligibility paths. Cancellation, retry, crash, and restart reconcile concurrent child completion and merge races without duplicate counts or discarded valid effects. Missing native child/merge evidence remains an explicit limit and cannot be invented as an outcome. Candidate-generation agents gain no new authority from this trial scope. | W0/W1/W2/W3/W5/W6/W7; P6, H2/H3/H5/H6/H7/H9/H11, native installed execution, evaluator, report, and fault-and-restart boundary. |

### Test execution and completion evidence

Use TestNG for direct Java behavior with test-owned inputs and injected collaborators. Use Bats for actual shell,
launcher, installation, or bootstrap behavior. Reserve native agent runs for claims about native execution, loading,
write controls, cancellation, and organic user flow. A source-string/package-shape assertion cannot prove those claims.

Each test's name, exercised operation, and assertion must describe the same result. Use independent worktrees and fresh
sessions where required. Allocate runner-consumed fixtures beneath the resolved CAT temporary root, retain their native
results for acceptance, and clean private scratch after workers terminate and consumers finish. In-process tests may
use framework-owned temporary directories. Tests must not mutate the developer's active prompts or installed account
configuration as fixtures.

For focused TestNG work, run the narrowest supported Maven selection for the changed module and named test, including
required reactor predecessors. Record the actual command after resolving its runnable scope. Do not invent a success
receipt for a test class that does not exist yet. The final mandatory command, from the repository root, is:

```bash
client/mvnw -q -Dmaven.build.cache.enabled=false -f client/pom.xml verify
```

Follow [contributing.md](docs/contributing.md) for existing artifact installation and external boundary tests. Complete
the unrestricted build before handoff; separately run the installed organic scenarios and retain their terminal results.
Provider authentication or an unavailable supported host boundary leaves that named native gate unrun, not passed by
mock evidence. Continue independently verifiable work and state the exact external prerequisite needed to finish.

W8 records, for every case, the tested source/distribution identity, exact invocation, evidence location, terminal
outcome, and remaining limitation. Mark G1–G7 and the unrestricted build separately. Reconcile this inventory against
the current [frontend.md](frontend.md) immediately before declaring completion. The feature is complete only when all
required cases and the unrestricted build pass; writing this plan, merging components, producing a diff, or passing
statistical fixtures is intermediate product evidence.
