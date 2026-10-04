# Prompt Optimizer Implementation Plan

This plan turns `design.md` into code and acceptance evidence. It is written for the AI agents that implement it.
`design.md` is the product and technical authority; this plan is the execution and acceptance authority. If they
disagree, fix `design.md` first, then this plan, before dependent work continues.

## Design Goals

- **Complete coverage:** Every obligation in `design.md` has a requirement ID in
  [the inventory](#3-requirement-inventory) and at least one named test. Each listed owner closes that requirement in
  its work item, and no work item closes a requirement it does not own. The final gate ([§9](#9-final-gates)) rejects
  the feature while any requirement lacks passing evidence at its required tier.
- **Risk first:** Premises that could change a design decision are probed before dependent code is written. Decisions
  that are hard to change, such as statistics, persistence and adoption order, are built and proven before features
  that depend on them.
- **Executable acceptance:** Each work item states its deliverables, the requirement IDs it closes, the tests that prove
  them and an exit check an agent can run.
- **Concurrent, cheap tests:** Every work item preserves `design.md` [§10](design.md#10-testability-contract). A change
  that makes a test depend on shared state, real time or replaying earlier work is rejected in review.

## 1. Working rules for implementers

- **Placement:**
  - Core code goes in `client/common/cli` under `io.github.cowwoc.cat.common.optimizer.<package>`, following
    `design.md` [§9](design.md#9-architecture).
  - Harness adapters and `main` classes go in `client/claude/cli` (`io.github.cowwoc.cat.claude.optimizer`) and
    `client/codex/cli` (`io.github.cowwoc.cat.codex.optimizer`).
  - Test builders and fakes go in `client/common/cli` test sources, published through that module's test JAR.
- **Reuse:** `Git` (`common.git`), the runner API (`common.runner`), the project's Jackson 3 `JsonMapper`
  conventions, `requirements-java` validation and `TemporaryDirectories`. Do not reimplement a runner, a Git client or a
  JSON codec.
- **Rules:** Follow the CAT rules the session routes for Java, Javadoc, testing, error handling and TDD. Subagents
  receive the same routing. Write a failing test before each behaviour change.
- **Requirement tags:** Every test method that proves a requirement carries a comment `// Requirement: R-…` naming each
  requirement ID it proves. The final gate counts these tags, and gate 8 then removes them.
- **No references to this plan or `design.md`:** both files are deleted once the implementation is complete (user
  ruling, 2026-10-01). Code, tests, resources and prompts never cite either file, its section numbers or its
  identifiers (H, D, G, P, T, E, WI, handoff and trace numbers); they state the rule, number or reason itself. The
  requirement tags are the only exception until gate 8.
- **Test tiers:**
  - **Unit:** every Java test. It runs in the default build under TestNG parallel methods, and includes file-system and
    real-Git tests that use test-owned temporary directories. `design.md` [§10](design.md#10-testability-contract) T3
    governs it: no Java test starts a runner, a harness process or a model (user ruling, 2026-10-01).
  - **End-to-end:** scripted runs of the installed `prompt-optimizer` skill in each harness against fixture prompt
    systems. These are the only checks that start nested sessions or a model, and they exercise every port operation
    in each harness. Fixtures consumed by a runner live under the directory that `workflow-temp root` prints
    (`.cat/work/temp`), not the system temp directory. Each run records a receipt: commands, versions, outcome, and
    paths to retained evidence.
- **Build check:** `./mvnw verify` from `client/` is the unrestricted build. A work item is not done until it passes,
  along with the work item's focused tests.

### 1.1 Models and delegation

One orchestrating agent executes this plan. It delegates work items to subagents, integrates their results and keeps
every decision that needs judgment.

**Orchestrator:** Opus 5.5 at high effort in Claude Code, or `gpt-6.1-sol` at high effort in Codex. It never delegates:

- integrating each delegated result into the shared tree, one at a time;
- reviewing each result against its work item's exit check, [§8](#8-test-suite-rules) and `design.md`
  [§10](design.md#10-testability-contract);
- every change to `design.md` or this plan, including premise-gate fallbacks;
- user decisions and the interactive premise gates;
- the final gates in [§9](#9-final-gates).

**Subagent tiers:** Each tier is a CAT agent. The tier names rank relative capability; they are not effort values. A
subagent's model and reasoning effort come from its agent definition, not from the call that starts it.

| Agent | Claude `model`, `effort` | Codex `model`, `model_reasoning_effort` | Use |
|---|---|---|---|
| `optimize-xhigh` | `opus`, `high` | `gpt-6.1-sol`, `high` | Correctness depends on subtle reasoning: statistics, interacting policies, concurrency, prompts. |
| `optimize-high` | `opus`, `medium` | `gpt-6.1-sol`, `medium` | Logic or I/O whose failure and recovery paths must be exact. |
| `optimize-medium` | `sonnet`, `high` | `gpt-6-luna`, `high` | Well-specified code against existing APIs. |
| `optimize-low` | `sonnet`, `medium` | `gpt-6-luna`, `medium` | Mechanical code: wiring, rendering, parsing. |

**Setup, before WI-0:** the orchestrator adds the tier agents beside CAT's other agents, in the same layout:

- one harness-neutral body, `client/common/agents/src/main/resources/agents/common/optimize.md`, stating the
  task-contract obligations and the decision rule below. CAT's rules reach the subagent through its start hook, so the
  body does not repeat them. Update the directory's `README.md`, which says every shared body is read-only.
- Claude wrappers `client/claude/plugin/src/main/resources/agents/claude/optimize-<tier>.md`, with the model and
  effort above, the tools `Read, Edit, Write, Bash, Grep, Glob` and an include of the shared body.
- Codex wrappers `client/codex/plugin/src/main/resources/agents/codex/optimize-<tier>.toml`, named
  `cat-optimize-<tier>`, with the model and effort above, `sandbox_mode = "workspace-write"` and the same include.

It then runs `./mvnw verify`, installs the built plugin through its documented lifecycle, and starts a new session to
execute the plan. The agents ship in the plugin like CAT's reviewer agents. Before the first delegation, it verifies
each agent by starting it once with a trivial task:

- the harness offers it: `cat:claude:optimize-<tier>` in Claude Code, or `cat-optimize-<tier>` in Codex;
- the model recorded in its transcript matches its definition. Effort may not be observable; record whether it is;
- its injected context includes CAT's Default Rule Routing and Request Execution rules, and its body's decision rule.

A mismatch is fixed in the agent definition before any delegation.

**Assignments:**

| Work item | Executor | Reason |
|---|---|---|
| WI-0 | Orchestrator. `optimize-medium` writes the probe scripts. For P1–P3 the orchestrator runs each interactive host in a terminal multiplexer and sends it the ESC key; the user presses ESC only if that fails. | P1–P3 need ESC in an interactive host, and choosing a fallback changes the design. |
| WI-1 | `optimize-xhigh` | Exact rational decisions, a proven-safe floating-point margin and calibration fixtures. |
| WI-2 | `optimize-medium` | A schema and seeded draw streams with complete specifications. |
| WI-3 | `optimize-xhigh` | Interacting policies, the decision table and the scheduler. |
| WI-4 | `optimize-high` | Crash consistency proven by fault injection. |
| WI-5 | `optimize-medium` | A focused API replacement with a known retirement procedure. |
| WI-6 | `optimize-high` | Edit authority and three-way delivery conflicts. |
| WI-7 | `optimize-medium` | Git and file operations through existing APIs. |
| WI-8 | `optimize-medium`; its runner fixes go to `optimize-high` | Adapters over the existing runners, with event parsing tested on recorded samples. The runner fixes change turn-boundary and interrupt paths whose failure handling must be exact. |
| WI-9 | `optimize-xhigh` | Adoption order, concurrency, reconciliation and interrupts. |
| WI-10 | `optimize-xhigh` | The deriver, judge and author prompts determine what the product measures. |
| WI-11 | `optimize-high` | Journaled writes that must resume without duplicating or overwriting a change. |
| WI-12 | `optimize-low` | Rendering from builder states. |
| WI-13 | `optimize-low` | Command parsing and wiring. |
| WI-14 | `optimize-high` | Rule and skill edits under CAT's prompt rules and contract retirement. |
| WI-16 | `optimize-xhigh` | Cells change every statistical rule's unit, and runner profile selection must reach both harnesses exactly. |
| WI-17 | `optimize-xhigh` | Check kinds, controls and the judge's narrower role decide what the product measures. |
| WI-18 | `optimize-xhigh` | Manifest validation, the minimality search and the author prompt decide which edits ship. |
| WI-19 | `optimize-xhigh` | Allocation policy that interacts with readiness, the coverage floor and edits. |
| WI-20 | `optimize-xhigh` | Selection among concurrent attempts decides which edit ships and how rounds spend author sessions. |
| WI-21 | `optimize-high` | A proposal check, a rule edit under CAT's prompt rules, and a migration whose completeness an audit must prove. |
| WI-22 | `optimize-high` | A result-shape change through both adapters, the checks, the judge and the author evidence, with recorded-event tests per harness. |
| WI-23 | `optimize-high` | Scenario discovery across module test resources, a rule edit, and moving 64 scenarios with their fixtures. |
| WI-24 | `optimize-high` | A new deriver finding through the schema, parser, proposal rendering and the confirm command. |
| WI-25 | `optimize-high` | New deriver and author outputs, a pause state with choices, a revision from a choice, and two prompt-wording changes. |
| WI-26 | `optimize-xhigh` | The promotion and reduction rules and the author's approach decide which edits ship and when a campaign plateaus. |
| WI-27 | `optimize-high` | Per-role session setup in both runners, rule delivery from the installed artifact, and the rule loader's path-trigger text. |
| WI-28 | `optimize-xhigh` | The judge's evidence decides verdicts; one renderer per harness must lose nothing the judge needs, and frozen judge prompts must keep working. |
| WI-29 | `optimize-xhigh` | Fail-fast discards outcomes and changes when rounds start, and must keep adoption order unbiased. |
| WI-30 | `optimize-high` | Contract validation and the author-manifest check; no concurrency. |
| WI-31 | `optimize-medium` | Slug parsing in the Codex inventory, and one port method with one pre-derivation check in the core. |
| WI-32 | `optimize-xhigh` | Replaces the allocation tier, the baseline targets and the confirmation flow; interacts with readiness, the coverage floor and certification. |
| WI-33 | `optimize-xhigh` | Changes what every trial, author and judge session may see, and the campaign format; an error leaks held-out instances silently. |
| WI-15 | Orchestrator, sending ESC for E2 as in P1–P3, with the user as the fallback. `optimize-low` may run the other end-to-end scenarios and collect their receipts. | Accepting the feature is a judgment against §9. ESC needs an interactive host, as in P1–P3. |

**When to delegate:**

- Delegate a work item once every predecessor in [§6](#6-dependency-order) is integrated. Each work item can be
  verified on its own, and its exit check is its acceptance evidence.
- Run independent work items concurrently, each in its own isolated worktree created from the same integrated snapshot
  (`isolation: "worktree"` in Claude Code). At the start these are WI-0's probe scripts, WI-1, WI-2, WI-5 and WI-6.
- Run deterministic checks as scripts, not agents: requirement-tag traceability, the reference audit, and link and
  anchor checks.

**Task contract:** each delegation states:

- the work item's text from [§5](#5-work-items) and the `design.md` sections it cites;
- its requirement IDs and their rows from [§3](#3-requirement-inventory);
- a write allowlist: the packages and paths the work item names, and their tests;
- its worktree and the snapshot it starts from;
- the verification commands: the focused tests and `./mvnw verify` from `client/`;
- the exit check as its acceptance evidence.

The subagent never changes `design.md`, this plan or a path outside its allowlist, and never integrates its own
result. When it finds a contradiction, a decision it may not make, or a failure outside its allowlist, it stops and
reports it.

**Decision rule:** a decision that `design.md` and this plan leave open is classified before anyone acts on it.

- **Already determined:** the documents fix the observable behaviour. Implement it.
- **Internal:** an API, type, data layout, helper structure or test arrangement that changes nothing a user, a
  statistical claim or a documented interface can observe. The subagent decides. It picks the simplest design that
  meets every requirement already in `design.md` without later rework, adds no extension point or configuration for a
  variation no requirement names, and reports the choice and its reason with its result.
- **External:** the choice changes what the user sees, a statistical claim or its error budget, a documented interface,
  persisted data, or delivery to the base worktree. The subagent stops and reports it with the alternatives it found.
  The orchestrator decides when the documents' goals and decision records settle it, asks the user when they don't,
  and records a lasting choice in `design.md` §13.

Judge an option's long-term cost over the lifetime of the requirements that already exist, including their
maintenance, not over speculative ones. Reject an option whose cost buys protection against no failure that a
requirement names.

**Acceptance and escalation:**

- The orchestrator rejects changes outside the allowlist, integrates the result, reruns the exit check on the integrated
  tree and reviews it.
- A reported design gap returns to the orchestrator. It fixes `design.md` first, then this plan, then relaunches the
  work item.
- After two failed attempts at the same exit check, the work item moves up one tier (low, medium, high, xhigh). After
  two failures at `optimize-xhigh`, the orchestrator takes it over.

## 2. Status

| Work item | State |
|---|---|
| Setup (§1.1) | Done (commit `bd37b5b3d`). Verified 2026-10-01 with one trivial task per agent. Claude offers `cat:claude:optimize-<tier>`; transcripts record `claude-opus-5-5` (xhigh, high) and `claude-sonnet-5-5` (medium, low), with effort observable and matching. Codex offers `cat-optimize-<tier>` (rollout `agent_role`); rollouts record `gpt-6.1-sol` high/medium and `gpt-6-luna` high/medium. Every agent's injected context contains Default Rule Routing, Request Execution and the body's decision rule; in Claude the rules arrive through the SubagentStart context file. |
| WI-0 | Done 2026-10-01. Every gate has a result (§4); `design.md` §11 H11–H23 and §12 record them. ESC was sent through `tmux -L catprobe`. Evidence is in `.cat/work/temp/optimizer-probes-13495928275341475570` (one directory per probe and harness, each with `summary.md`). It stays until WI-8 has copied the recorded native lines it needs into test resources, then WI-8's integration deletes it with `workflow-temp delete`. |
| WI-5 | Done (commit `b3abb8863`): `HarnessCapacity` replaces `HarnessConcurrencyLimit` (no references remain); both runner CLIs acquire it; `native` Maven profile added. Full build passed on the integrated tree; the native two-process test passed in both harnesses. The native test fails rather than skips when a real session holds the single slot. |
| WI-2 | Done (commit `7c38706aa`); full build passed on the integrated tree. Follow-ups: WI-10 injects the CAT-owned judge `model`, `prompt` and `result_schema` before validating deriver output, and renders the CAT-computed §6.1 items (procedure, link report, warnings, minimum session count) that the proposal schema does not hold; WI-3 or WI-4 hashes `ProposalSchema.toJson(Contract)` for H-1. The formal `DrawStream` starts closed and WI-3's commit opens it (R-F1). |
| WI-6 | Done (commit `5a5499b75`); full build passed on the integrated tree. `DeliveryPlan` treats any change to an overlay target on the base side as a conflict, including an identical change, per design §4.7. Its change list gives WI-11 each change's preimage and postimage for at-most-once resume. |
| WI-1 | Done (commit `269c29413`); full build passed on the integrated tree. `CapCalibration.calibrate` throws the checked `CapCalibrationException` when no cap up to 10,000 reaches 90% power; WI-3 blocks admission on it. `ErrorBudget` leaves certification-index assignment to WI-3 and WI-4. `TestabilityAuditTest` reads the source root from the surefire property `cat.client.directory` and scans `optimizer` packages in all three CLI modules plus `common/runner/HarnessCapacity*.java`; `Pause.java`, which holds the only `Thread.sleep`, is outside its scope. The test JAR of `client/common/cli` is published. |
| WI-3 | Done (commit `486beb8ba`); 218 focused tests and the full build passed on the integrated tree. `ContractIdentity` hashes `ProposalSchema.toJson(contract)` (H-1). Trial sequences are numbered per category, purpose and snapshot (`SequenceGroup`); adoption groups also include the attempt or series. Pending decisions queue in arrival order and the first is offered; an equal decision is not queued twice. Every measurement condition is material, including runner and CAT versions (D21). The throttle state is persisted in `Campaign`. Only `optimizer.port` is exported. For WI-8: the observed prompt-sources value must not depend on the candidate snapshot, or every candidate looks like a measurement change. For WI-12: `AssumptionCheckTest` already covers the policy side. |
| WI-8 runner fixes | Done (commit `013867fa9`); full build passed on the integrated tree; unit-tested with in-memory fakes shaped like the WI-0 evidence. Claude: after the root `result`, the turn waits while `background_tasks_changed` lists a task, then up to 30 seconds for a follow-up `init`, then for its `result`; the reply comes from root records only. Codex: `interrupt()` sends `turn/interrupt` (held until the turn ID is known), an interrupt CAT requested completes the turn, and the reply is the root thread's last `agentMessage`. A late interrupt is a no-op in the bridge and both processes. Open for the rest of WI-8: its native contract tests must exercise a Claude background child and a Codex mid-turn interrupt (the runner native tests cover only capacity); unobserved cases are a child finishing before the root `result` with a follow-up still to come, and SIGINT to Claude while it waits for children. The duplicated test `FakeNativeProcess` moves to the common test JAR when WI-8 adds that dependency. |
| WI-4 | Done (commit `1b124e324`); 38 store tests and the full build passed on the integrated tree. `FileCampaignStore` keeps `campaign.json` plus `trials/<sha256 of key>.json`, every file with `format_version: 1`; the conversion is hand-written (`CampaignJson`) because the campaign, statistics and contract packages are not opened to Jackson. A fault after each of the 20 write steps of one trial's lifecycle reloads with exactly one adopted outcome. `writeOutcome` ignores an identical rewrite and rejects a different one. Only `optimizer.port` is exported; the store and campaign packages stay internal. For WI-9: replace the snapshot that holds an assignment before its session starts, because `listUnadoptedOutcomes` reads records only for the snapshot's assigned and running trials. Gap found: the assignment record lacks §3.2's allocation decision with its evidence revision, the assignment time and per-attempt infrastructure records; a WI-3/WI-4 follow-up adds them. |
| WI-7 | Done (commit `e21810388`); 63 focused tests, the full build, and the native staged-source test in both harnesses passed. `Workspace` implements `WorkspacePort`. `Fork.create` refuses a dirty base, requires the repository top level, adds `/.cat/work/` to `info/exclude` only when `git check-ignore` reports it not ignored (new `Git.status` returns the exit status; any status other than 0 or 1 fails), and undoes its worktree and fork storage on failure. Dereferencing is shared by staging and `LinkReport`, so they cannot disagree. External-target edits live in a mirror at `<campaign>/worktree-external/`. The native test exercises the runners' shared `HarnessRunManager.copyProject`, not a runner process: runner-process acceptance of a staged source with links remains open for the native and end-to-end tiers (WI-15). Not covered: submodules (copied as directories). |
| WI-8 (adapters, signals, native contract tests) | Done (commit `39e7ed804`); full build passed on the integrated tree; native suites passed in both harnesses (Claude 10/10, Codex 9/9), including a Claude background child, an interrupt while Claude waits for a child, a Codex mid-turn interrupt and the host-login classification. Each module has a `SessionPort` adapter (`ClaudeSessions`, `CodexSessions`), native event parsing, `Credentials`, `PromptSources` and a host interrupt signal. The port gains `HostLogin hostLogin()` (SUBSCRIPTION, API_KEY, UNKNOWN; no secret) for WI-10's R-E7 warning, and `SessionResult.Completed` carries the observed prompt sources. Adapters take the host configuration directory as a setting, so the optimizer packages never read HOME. Runner fixes: both runners wait for the process to exit after its output ends, and the bridge stops a running turn when its input fails. Steps first written without a red phase were checked by mutation; the interrupt-at-turn-start request gained `interruptWaitsForTheTurnStart` because no test caught its removal. Decided: a session cut off at its time limit is `Completed` with partial replies (§3.2); Codex read-only evidence stays as H23 records; author effects land in a run copy and the driver captures them through `WorkspacePort`. Open for the end-to-end tier: a Claude follow-up still to come after a child that finished before the root `result` is unobserved and would be attributed to the next turn. Shared test fakes (`FakeNativeProcess`, `ManualClock`, `ScriptedHarness`) are in `common.runner.fakes` of the common test JAR. The WI-0 probe directory was deleted with `workflow-temp delete`. |
| WI-12 | Done (commit `28e67b5df`); 43 focused tests and the full build passed on the integrated tree. The status is the sealed `ReportStatus`: `running`, `awaiting-user`, `busy` (set only by the driver) or the terminal outcome label; a finished campaign with no outcome reports its delivery label, else `interrupted`. `not certified` is a delivery label, not an outcome (§2.8). Development minimum remaining trials simulate all-pass trials against `ReadinessGate` (§3.9 corrected). `LinkReport` was not yet available, so the symbolic-link limitation is a boolean input (`ReportEvidence.symbolicLinks`) that WI-9 sets from WI-7's report. The report-side assumption test is `AssumptionCheckReportTest`. |
| WI-3/WI-4 assignment record | Done (commit `80b532b26`, plus `StatusReportTest` fix); full build passed on the integrated tree. Each trial carries `Allocation(rule, evidenceRevision, assignedAt)` (rule = the §3.3 allocation rule) and `InfrastructureAttempt(endedAt, failure, detail)` for every run without an outcome; the campaign's evidence revision counts adopted outcomes. `format_version` stays 1 (keys added only). |
| WI-11 | Done (commit `cf64d1ce0`); 12 `DeliveryTest` cases, 49 store tests and the full build passed on the integrated tree. `Delivery.deliver(campaignId, request, selected, working, promptSources)` returns the sealed `DeliveryResult` (`Delivered`, `Conflicted`, `ConcurrentEdit`, `UnownedChange`, `VerificationFailed`). The journal (`<campaign>/delivery.json`, `format_version: 1`) holds the full planned changes with content, so a resume re-applies the preflight plan without re-planning; a target already at its postimage is skipped. Only `certified`, `improved, not certified` and `not certified` deliver. The diff uses a temporary index in the Git directory, so the user's index is untouched. For WI-9: the delivered `Applicability` covers prompt content only; combine it with `Applicability.of` for measurement conditions. The delivery package is not exported. |
| WI-9 | Done (commit `0abcd21f0`); full build passed on the integrated tree. `DriverTest` has 27 tests, the 14 §7 traces among them, and runs in under 1 s; `ReconciliationTest` covers each write-ahead crash point; `SliceTest` covers cutoff, grace and the one threaded `SliceRunner` test. Author edits are captured with `WorkspacePort.scanAuthorEffects(copy, effects)` (design §9), and the AUTHOR session's source is the prepared authoring copy. `PROMPT_SOURCES` comes from `Completed.promptSources()`. `ProcessHandleTerminator` terminates a leftover run only when the recorded start instant matches; descendants already reparented are not found. Main code preceded most tests, so mutation checks were run: every mutation failed a test, and one that first survived (a judge's rate-limit rejection counted as a judge failure) gained `judgeRateLimit`. Accepted: the status has no delivery-output fields (WI-13 renders `SliceResult.delivery()`), and a crash between materializing a promotion and replacing the snapshot is reconciled. Seams for WI-10: `JudgeProtocol`, `AuthorProtocol`. `optimizer.execution` is not exported; WI-13 decides. |
| Candidate overlay store (WI-4/WI-9 follow-up) | Done (commit `c93657464`); 15 overlay tests and the full build passed on the integrated tree. `CandidateOverlays` moved to `optimizer.port`, so the store does not depend on `execution`. `FileCandidateOverlays(campaignDirectory)` writes `<campaign>/overlays/<snapshot>.json` atomically (`format_version: 1`, the fork's identity, entries with Base64 content and mode); loading rebuilds the overlay from the persisted fork inventory and rejects a corrupt file, another fork, or an identity that differs from the requested snapshot. `FileCampaignStore` shares `AtomicFiles`. Verified: `Driver.submit` saves an overlay before any snapshot references it. For WI-13: save the incumbent `Overlay.of(fork inventory)` before the first `campaign.json` write. |
| WI-10 | Done (commit `7e93ca239`); the full build passed on the integrated tree (common/cli 875, claude 79, codex 78, distribution 23; no failures). `optimizer.proposal` derives and validates the proposal (Claude on `haiku` needs a two-turn deriver: inspect, then a tool-free turn that sends only the proposal); `optimizer.execution.prompt` renders the judge and author prompts; the judge returns a `Judgment` (verdict and rationale). Judge rationales and each failure's diagnostic reruns persist with the campaign (`Failure`, `DiagnosticRerun`; required fields, no fallback) and reach the author brief (§2.7, §3.1). An UNKNOWN login shows the full authentication warning (P6 fallback). Its native prompt tests were deleted under the 2026-10-01 test ruling; their assertions moved to E1's receipt (WI-15). |
| Claude follow-up attribution (WI-8 follow-up) | Done (commit `3b3b35b35`; full build passed with 1080 tests, 0 failures). Probes (Claude 2.1.286, haiku, six scenarios, `design.md` H24) reproduced the race: a child that finished during the root's last model call left an empty task list before the root `result`, and a follow-up `init`/`result` came within 0.1 seconds. Fix in `ClaudeHarnessProcess.TurnBoundary`, per `design.md` §3.2 and H25: the runner sets `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1`, and a turn ends at a `session_state_changed` `idle` while the latest `background_tasks_changed` lists no task; neither a root `result` nor a task-list change ends it alone. A Claude that emits no state events fails the turn non-retryably at its first `init`, naming `claude_code_version` (user ruling, 2026-10-01). The runner sets no turn time limit of its own (user ruling, 2026-10-01: the 30-second guard is removed); while a turn writes no bridge record for 30 seconds, `HarnessRunnerBridge` writes a `waiting` record (`runId`, seconds since the last record) and repeats it every 30 seconds, in both harnesses. A follow-up `result` (`origin.kind` `task-notification`) that arrives after its turn ended is relayed with the next turn's events, never taken as its reply, and reported in that turn's `standardError`. The fix also orders the bridge so an interrupt sent after `turn-completed` is a no-op (`HarnessRunnerBridgeTest.ignoresInterruptAfterTurnEnded` was intermittent). |
| WI-13 | Done (commit `9663297dd`; full build passed with 1139 tests, 0 failures; `prompt-optimizer.bats` 4/4 against both images; `prompt-optimizer status` printed the fixture campaign's status JSON from both jlink images). Launcher grammar: `start <target>`, `run|status|confirm|cancel <id>`, `revise <id> <feedback>`, `choose <id> <choice> [--attempt N \| --review-point N]`; statuses `refused`, `invalid-input`, `error` with exit codes 1, 2, 1. `optimizer.cli` is exported; execution, store and campaign stay internal. `confirm` records fixtures and derived sources through `Fork.addParticipants`, and a revision after confirmation that needs new participants is refused. `OptimizerCore` takes `Forks` and `CapCalibrator` seams, so its tests run no Git; `HarnessVersion` runs `<exe> --version` through an injected launcher. Loader configuration: Claude `.claude/settings.json`, `.claude/settings.local.json`, `.mcp.json`; Codex `.codex/config.toml`. |
| Harness terminology (user ruling, 2026-10-01) | Done (commit `8606bee5e`; full build passed with 1141 tests, 0 failures; the five bats files passed, 33 of 33). "Native" meaning the harness or its model process became "harness" in classes, members, messages and docs. Harness-specific classes take the harness's name (`ClaudeProcess`, `CodexProcess`); generic types keep "Harness" (`HarnessProcess`, `HarnessEvents`, `FakeHarnessCli`). `NativeProcessLiveness` became `ProcessHandleLiveness`, because `HarnessProcessLiveness` is the interface it implements. Shipped names changed with no migration: `native-event` → `harness-event`; manifest keys `nativePid`, `nativeStartedAt`, `nativeCommand` → `harnessPid`, `harnessStartedAt`, `harnessCommand`. Kept: native-code senses, the WI-14 native tier, shipped rule and skill prompts, and the recorded captures under `docs/changelog/1.0/captures`. The `artifact-runners.bats` fake `claude` now prints the `idle` state that the Claude turn boundary needs; the race fix had broken it, and that integration did not run bats. Every integration now runs the bats files. |
| Pouch scopes for the optimizer and bridge (WI-13 follow-up) | Done (commit `f58ee27e5`; full build passed with 1144 tests, 0 failures; the five bats files passed, 33 of 33). `Host` and the seam constructors are gone. `OptimizerCommandScope` is one scope per `prompt-optimizer` command: `MainOptimizerCommandScope` holds the shared production services (Git forks, cap calibration, system clock, version launcher, mapper, random), `ClaudeOptimizerCommandScope` and `CodexOptimizerCommandScope` add the sessions and host interrupt signal and are the only optimizer classes that read the environment, and `TestOptimizerCommandScope` supplies fakes. `HarnessRunnerScope` became abstract with `MainHarnessRunnerScope` and `runner.fakes.TestHarnessRunnerScope`, which own the bridge's clock and heartbeat ticker; the package-private `serve` overload is removed. `TestabilityAuditTest` allows environment reads in harness production scopes (`design.md` §10 T4). Tests at or over 1 s, none touched here: `EditorInventoryTest.reportsHeartbeatForSilentCommand` 2.55 s, `ExecutableFilesTest.retriesTextFileBusyStart` 2.12 s, `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` 1.83 s, `EditorPatchWorkflowTest.capturesPatchForEarlierFileVersion` 1.48 s. |
| Authority warnings (WI-6/WI-9/WI-12/WI-13 follow-up) | Done (commit `3c18e9870`; full build passed with 1186 tests, 0 failures; the five bats files passed, 33 of 33). The author's final response is one JSON object `{"needed_edits":[{requirement, path, operation, target?}]}` (`optimizer/author/report.schema.json`, H23 extraction); a malformed report or an unknown requirement id is an author infrastructure failure. CAT resolves each path from the fork and calls `Authority.diagnose`; any warning becomes a pending `AUTHORITY_WARNINGS` decision after the attempt's permitted edits are submitted. Choices: `choose <id> authorize-target --target <path>` (only when the limit allows it; removes only that target's warnings), `choose <id> use-authorized-source`, stop, and the `revise` command. Grammar: `choose <id> <choice> [--attempt N \| --review-point N \| --target <path>]`. `campaign.json` gains required `authorized_targets` (sorted absolute paths); `format_version` stays 1 (keys added only). Status JSON shows `authorityWarnings[]` and a summary line. Delivery treats authorized targets as prompt sources. Proof: `DriverTest.authorityWarningTrace` runs from the report through a restart and authorization to delivery. |
| `workflow-temp` output (user request, outside the WIs) | Done (commit `ebc375ded`; `InvocationDirectoryTest` 11/11, full build passed, the five bats files 33/33). `create` and `handoff` print the bare absolute path, `delete` prints nothing, `--help` states each command's output, and a usage error prints the help to standard error. `workflow-temporary-resources.md`, `failure-prevention.md` and both `skill-env.md` files state each command's output and name `.cat/work/temp` instead of the retired `.cat/temp`. WI-14 merged onto it cleanly; the new skill does not describe `workflow-temp`. |
| WI-14 | Done (integrated; full build 1193 tests, 0 failures; bats 32/32), except the session contract tests and the rest of the native-tier removal, which moved to WI-15. The user chose D13 over goal.md's "via `cat:prompt-optimizer`" wording and approved the rule diff (2026-10-01). Skill replaced with the §8 loop; D13 applied to both rules with the subagent reporting clause kept; new rule scenario `failure-regression-promotion/correct-without-campaign` (main-agent and subagent sides); old-skill citations updated; `StagedSourceNativeTest` became `StagedSourceRunnerCopyTest` (unit, both harnesses); `ClaudeRunnerCliNativeTest` and `CodexRunnerCliNativeTest` deleted (assertions covered by E8 and `HarnessCapacityTest`); the opt-in `prompt-evaluation` bats test removed, since that launcher and contract are retired; `TestabilityAuditTest` scans once per class (slowest 0.54 s). Reference audit: no `prompt-evaluation` hit; every `prompt-optimizer` hit names the new skill or launcher, or is a historical capture. Still present until WI-15's contract tests: `ClaudeSessionsNativeTest`, `CodexSessionsNativeTest`, the `native` profile and its properties (§9 gate 3 open). Pre-existing tests over 1 s: `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` 2.2 s, `EditorInventoryTest.reportsHeartbeatForSilentCommand` 1.6 s. |
| Runtime and interrupt-seam follow-up (WI-15 prerequisites) | Done (full build 1185 tests, 0 failures; bats 32/32; `FinalizationTest` closed in commit `0d68e24f3`, below). `ClaudeProcess` takes an `InterruptSender` (production sends SIGINT as before); `FakeHarnessCli(handler, afterInterrupt)` accepts an interrupt and replays the given lines (`ClaudeProcessTest.deliversInterruptThroughSender`, R-E15; its after-interrupt segment is synthetic until WI-15 recordings replace it). `EditorInventoryTest.reportsHeartbeatForSilentCommand` 1.4 s to 0.01 s. `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` (commit `0d68e24f3`): the replay classes remember reconstructions, path states and chains keyed on immutable object IDs, and read each commit range once, so the test starts 227 git processes instead of 471. It runs 0.77–0.87 s warm and 1.02 s in the full build (1196 reports, 9 of them stale; 0 failures), and its Javadoc records why it needs the real chain (`design.md` §10 T7). Other test at or over 1 s in that build: `ClaudeSessionsTest.givesUpOnARunnerThatIgnoresAnInterrupt` 1.30 s (varies 0.5–2.5 s between runs, like its Codex twin), left to WI-15's contract-test rewrite. Correction: WI-14's "1193 tests" counted 9 stale reports from removed modules; the true count was 1184. |
| WI-15 | In progress. Codex first, then Claude (user ruling, 2026-10-01). The Codex build of `6c90bad8d` is installed (`install-codex.sh`, exit 0; the installed `workflow-temp --help` shows the new output lines); install the Claude build only when Claude starts. Fixture pilot: a motto defined only in a handbook was not deficient (12/12 compliant); contradictory instructions ("close with the motto" beside "no slogans or sign-offs") were deficient (Claude 4/6, Codex 0/6 compliant); the author-style fix ("apart from the motto, no other slogans...") reached Codex 6/6 with correct answers. This contradiction fixture is the E1 deficient fixture. Fixtures `deficient`, `easy`, `link` and four `unsupported` variants are under `optimizer/e2e/` (README lists each one's use). E1 Codex, dirty-base refusal: passed through the installed skill (`start AGENTS.md` refused on the uncommitted `docs/handbook.md`; no campaign created). Evidence in `.cat/work/temp/e1-codex-15400782742439386847/receipts/`, kept until the WI-15 receipts are recorded. In that run, and in every later Codex run, the agent first ran `"${CAT_PLUGIN_ROOT}/client/bin/prompt-optimizer"` literally (exit 127, the variable is unset) and then found the installed path itself; `skill-env.md` is in the session's context but was not applied. The fix is a rule change, awaiting the user (asked 2026-10-01). E4 Codex: passed. Each of the four `unsupported` fixtures produced a proposal naming its outcome (unsupported instruction source R2–R3; unsupported output R3–R4; unsupported test interaction R4–R5; ineligible trial effect R3–R4); `confirm` recorded that outcome with `stop` as the only choice and dispatched no trial; `stop` cancelled each campaign; every base stayed clean and `~/acme-support` was never created. Evidence in `.cat/work/temp/e4-codex-11033726973909551249/*/receipts/` (events, final message, confirm and stop JSON, Codex 0.159.1). Minor findings, open: the summary's "Waiting for" line repeats "certification is unavailable: certification unavailable: …"; after `stop` the status is `awaiting-user` with only `resume`. E7 Codex, first run: `start` refused the link fixture with "missing target" although the external target existed, because the fork recorded external link targets only after the deriver named them (commit `97b9c226b` fixes it: creating or extending the fork records the existing outside targets of recorded links, transitively; full build 1201 tests, 0 failures; Codex build reinstalled). E7 Codex link report: passed on the rerun; the proposal lists `docs/handbook.md -> shared/handbook.md` (inside the project, outside prompt sources) and `docs/reply-rules.md` -> its external target (external, read only) and states the staging consequence. Evidence in `.cat/work/temp/e7-codex-4281424245095601688/receipts/`. Minor, accepted: on the intentionally contradictory fixture, the "Certifiable scope" line and a limitation disagree on whether certification is unavailable. E7 Codex delivery, 2026-10-02 (`~/cat-client/worktrees/e7-codex2/receipts/`): the author's authority warning named the external `reply-rules.md`; `choose authorize-target` recorded it, and three later attempts edited it, but candidate 2 (an `AGENTS.md`-only edit made before the authorization) was certified (four cells, 61/61 to 164/168) and delivered with `externalChanges: []`, so the delivery of an external edit is still unobserved. `start docs/reply-rules.md` is refused because the link resolves outside the project (`~/cat-client/worktrees/e7-codex3/`, no campaign created); how to reach that delivery awaits the user. E6 needs both harnesses and moves to the Claude phase. The trial model and effort question became trial profiles (user ruling, 2026-10-01): the certification-dependent scenarios (E1 certification, E2, E3, E5, E7 delivery, E8) wait for WI-16–WI-18, then the Codex build is reinstalled. The exit-127 cause was found: Codex tool shells define neither `CODEX_HOME` nor `CAT_PLUGIN_ROOT` (probe: only `CODEX_CI`, `CODEX_SESSION_ID`, `CODEX_THREAD_ID`, `CODEX_VERSION`, `HOME`), so `skill-env.md`'s derivation could not work; fixed by the plugin-root follow-up below. Codex session contract tests (2026-10-02): `CodexSessionsContractTest` runs six `SessionPortContract` checks against the real `CodexSessions` over `FakeHarnessCli` replaying complete, untrimmed codex-cli 0.159.1 streams from the E1 run (trial, judge, author, two deriver turns) and the recorded child capture; paths and identifiers are neutralized. The interrupt checks replay the interrupted trial turn recorded in E2 (`interrupt-turn-1.jsonl`, complete, 36 lines); `CodexSessionsNativeTest` is deleted, and the `native` profile and its properties stay until the Claude recordings exist (full build 1702 tests, 0 failures; bats 32/32). E2 Codex: passed 2026-10-02. ESC in an interactive Codex host (`tmux -L catprobe`) mid-slice ended the in-flight trial turn `interrupted` 0.9 s later and the CAT run process exited 1.0 s after ESC, releasing its capacity slot; after resuming from the host, the interrupted trial gained one `INTERRUPTED` infrastructure attempt and exactly one outcome for the same instance, the earlier outcome was unchanged, and no certification attempt had started (attempt 0 throughout). Evidence in `~/cat-client/worktrees/e2-codex/receipts/`. E5 Codex: passed 2026-10-02 on the rerun. Trials used the host shell tool and child subagents, the judge's verdicts cite those events, and outcomes were recorded without a `MEASUREMENT_CHANGED` pause; Codex trials cannot create child worktrees, so that part is a Claude check. Evidence in `~/cat-client/worktrees/e5-codex2/receipts/`. E1 Codex on gpt-6-luna/low (user request): `not certified: plateau` after 6 trials, because the installed build predated WI-24 and the deficient fixture's conflict reached the trials; rerun after WI-25 is installed, in `~/cat-client/worktrees/`. E5 Codex, first run: paused `MEASUREMENT_CHANGED` on `PROMPT_SOURCES` after 3 trials, because the value listed hooks of later events (a subagent's start) that run only when a trial takes that action; fix in progress (only session-start sources count); rerun afterwards. |
| Plugin root and harness home (WI-15 finding, R-I5) | Done (full build 1210 tests, 0 failures; bats 32/32). `ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE` is replaced in plugin-tree direct rules and their includes; project and user rules keep it unchanged, because the loader cannot tell a quotation from a use and rejecting would inject no rules. Lazily read `rules/include/**` files are not rendered; only `skill-env.md` uses the directive. Remaining duplicate derivations, out of scope: `CodexRunner.codexHome` and `ClaudeRunner.claudeConfigDirectory` fall back to a relative `.codex` or `.claude`; the Codex `claude-runner` skill lets a local `CAT_CLAUDE_MARKETPLACE_ROOT` override its derived path, which `.cat/rules/include/environment.md` forbids. Tests at or over 1 s: `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` 1.02 s (reason in its Javadoc). Verified after reinstalling Codex (commit `52ceefeec`): a Codex session asked to run `${CAT_PLUGIN_ROOT}/client/bin/workflow-temp root` ran the installed absolute path on its first command, exit 0, with no exit-127 call. Per the user's ruling (2026-10-01: Codex sets no custom environment variables; derive values in Java or state them through the environment rule), the session hooks render `<!-- cat:plugin-root -->` in plugin rules into the absolute plugin root, both `skill-env.md` files tell the agent to write that directory wherever a skill writes `${CAT_PLUGIN_ROOT}`, and `CodexGetHistory`/`ClaudeGetHistory` default to `HOME/.codex` and `HOME/.claude`. |
| P14 effort probe | Done 2026-10-01; P14 holds (`design.md` H27). First run inconclusive (H26: deficient 0/20 and fixed 20/20 at every effort). Rerun on an intermediate-compliance variant (the two rules in reverse order) at user request, 20 trials per effort on `gpt-6.1-sol`: low 5, medium 8, high 16, xhigh 19 compliant, all answers correct; the pre-stated 2×4 exact test rejects equal rates, p ≈ 2.2 × 10^-6. Effort stays in the profile set. A second rerun with a rule without a conflict (user request; `design.md` H28): `gpt-6.1-sol` was at the ceiling (40/40 pilot trials at low), so the run used `gpt-6-luna`: low 4, medium 6, high 13, xhigh 15 of 20, p ≈ 0.0006, mostly through whether the model read the handbook. A rerun against a partly optimized real rule (for example `write-clearly.md`, which needs the judge) waits for WI-17. Probe directories deleted after recording. |
| P15 single-cell development (weakest model, lowest effort) | In progress on the Codex rule `complete-file-reads.md` through the probe harness, not through the optimizer (user ruling, 2026-10-03: a clean run on this rule). The earlier campaign on this rule does not count: its variants were tuned after Luna medium and Sol failures. Protocol, fixed before development: (1) The incumbent is the rule's text on v1.0 as delivered (without Design Goals); the tasks are the probe's long read, lazy load and search and, from p15-9 on, a no-file question, scored by `score.py`. (2) Models: `gpt-6-luna`, `gpt-6-astra` and `gpt-6.1-sol`; older models, `gpt-6-sol` included, are left out (user rulings, 2026-10-03). Efforts: `low` to `max`; `ultra` is `max` reasoning plus automatic task delegation and is not tested (user rulings, 2026-10-03). That is 15 cells. (3) Baseline: the incumbent at `low` on each model, 10 runs per task; the weakest model has the lowest pass rate (ties: more blocking failures, then `gpt-6-luna`). Result: `gpt-6-luna` 26/30 (4 long-read failures: two accepted a truncated read, one missed the line cut by the truncation, one re-read 4 lines), `gpt-6-astra` and `gpt-6.1-sol` 30/30. The development cell is `gpt-6-luna` at `low`. Before this result the scorer was fixed to credit a re-read whose numbering restarts (`tail -n +N \| cat -n`) or carries a composite prefix; it had counted such delivered text as missing, and rescoring 814 earlier runs changed no result. (4) Development uses only that cell, with the default tool output limit; every edit answers a failure seen in that cell during this campaign; a candidate first passes the readiness gate there (`design.md` §2.7: at least 100 runs per task, at least 99% passing, the latest two batches of 50 at 98% or more; a 30-run screen is its first part, user ruling, 2026-10-04), then certifies there at 59/59 per task, a self-recovering failure (an overreach, or a re-read that still delivers every line) extending its task to 94; screens and certifications stop at the first blocking failure (user ruling, 2026-10-03). (5) Benchmark: the frozen candidate, unedited, in the other 14 cells, in stages: the 2 other models at `low`, then the 3 models at `max`, then, only if those 5 pass, the 9 middle-effort cells (user rulings, 2026-10-03). Any failure refutes the shortcut for this file and ends the benchmark. 37 runs per task (148 per cell); every fault, overreach included, counts as a failure. A cell rejects H0 (pass rate below 95%, one-sided exact binomial, α = 0.05/14 for every cell from the start, so staging does not inflate the error) only with no failure. The effort result (`gpt-6-luna` above `low`) and the model result (the other models) are reported apart, with any failure kind not seen in development. Development so far: p15-1 (read the range from the line number before the truncation marker through the one after it) failed 30 of 80 screening runs: re-reads started below the cut, and two lazy runs ended their turn announcing a read (announce-and-stop); p15-2 replaces range arithmetic with the bundled `read-file` and forbids ending a turn with only an announcement. p15-2 failed 29 of 30 lazy runs, because the model set `max_output_tokens`; p15-3 gives the exact `exec` script and forbids that, and passed long reads 30/30, but lazy runs sometimes asked the user for the release with no call. p15-4 to p15-6 added opening lines against asking before reading; p15-6's "start every turn with an `exec` call that reads the files the request depends on" made counting runs read the whole file (2 overreaches in one cell). p15-7 sends search and count requests to `rg` in that opening line, but its "reads the files" made 2 of 57 runs `cat` the `read-file` script itself. p15-8 says that the call runs the script and never passes it to `cat`: its screen passed (long 30/30, search 30/30, lazy 64/65 with one self-recovering re-read), but 26 of 30 search answers miscounted (12 of 30 under p15-3), which the bar tallies apart (the model counts the matching lines that `rg -n` shows). p15-8 then certified on `gpt-6-luna` at `low`: 59/59 on each task, with no failure (44 of 59 search counts wrong). Benchmark stage 1 was stopped after 160 runs, all passing, because p15-8 tells the agent to start every turn with an `exec` call, which no task tested on a turn that needs no file (user ruling, 2026-10-03: stop, fix, redo). A fourth task, a general-knowledge question in the lazy-load workspace, now passes only with no tool call; a needless call is self-recovering. p15-8 made one in 9 of 10 such runs; the v1.0 rule and no rule made none in 20. p15-9 to p15-14 scoped or reordered the opening, and each made needless calls (`pwd`, an `rg` search for the question's term, or loading the files `AGENTS.md` names for customer replies) in 2 of 3 to 8 no-file runs. The zero-call ask that the opening targeted occurs without any rule (no rule: 2 of 30 lazy runs; v1.0 rule: 1 of 30); it stays a blocking failure of this rule (user ruling, 2026-10-03). p15-15 returns to p15-3 and adds a sentence against ending the turn by asking for what a workspace file may hold (alone: 0 needless calls in 20) and the `cat` clause; it passed 19 no-file runs but one lazy run batched three reads in one script and lost 38 lines to the cut. p15-16 adds that each script reads one file because several files' parts exceed one result: its screen passed 120 of 120 (18 of 30 search counts wrong). Its certification failed at lazy run 44 on a zero-call ask (user ruling, 2026-10-04: keep tuning in this rule). p15-17 to p15-23 tried sentences against asking, against claiming a file is missing, and on when instructed loads apply; none moved the zero-call rate: p15-23 failed 5 of 120 lazy runs that way, while p15-8's unconditional opening failed 0 of 124. Scoped openings (p15-24, p15-25) and loads scoped to the instructed work (p15-26) either made needless calls on the no-file task or still answered with no call. In 202 of 5320 read scripts the agent dropped the parentheses around `await`, printing `undefined`; p15-25 uses a two-statement script, `const result = await …; text(result.output);`, which keeps the exact-script approach (user ruling, 2026-10-04). The no-file task's "Do not edit any file." suffix drew the needless calls: p15-25 made them in 10 of 20 suffixed runs and 0 of 20 runs of the bare question, which is now the only no-file control (user ruling, 2026-10-04). p15-16 and p15-25 entered certification after a clean 30-run screen although development had shown the zero-call reply at about 1 in 40 lazy runs, and both certifications failed on it; certification now waits for the readiness gate (user ruling, 2026-10-04). p15-28 keeps p15-25's opening and replaces its closing announcement line with a check before ending a turn that ran no command: a reply that asks for details, says it cannot see them, or only announces the next step is not sent, and the agent looks in the workspace instead. Its screen passed 120 of 120 (19 of 30 search counts wrong). It passed the readiness gate: 100 runs per task, every one passing except plain run 54, a self-recovering `rg` search for the question's term before a correct answer (99/100; that batch of 50 at 98%). It then certified on `gpt-6-luna` at `low`: 59/59 on each task, with no failure (35 of 59 search counts wrong). Benchmark stage 1 passed: `gpt-6-astra` and `gpt-6.1-sol` at `low`, 148 of 148 runs each, with no failure and no wrong count. Stage 2 passed: the 3 models at `max`, 148 of 148 runs each (30 of 111 search counts wrong); one run that the full disk stopped before its session was written was rerun as the same run. Stage 3 passed: the 9 middle-effort cells, 148 of 148 runs each. Every one of the 14 benchmark cells rejects H0 with no failure, so the shortcut holds for this file, with no failure kind absent in the development cell. Wrong search counts, tallied apart, are a `gpt-6-luna` trait at every effort (22 to 35 of 37 per cell) and almost absent elsewhere (1 of 444 runs of `gpt-6-astra` and `gpt-6.1-sol`). For the file-reads bar, `gpt-6-luna` at `medium` and `gpt-6.1-sol` at `high` extended their 37 clean benchmark runs per task to readiness (100 per task, every one passing), 60 runs at the 5,000-token limit passed (20 per file task), and both cells then certified at 59/59 per task with no failure (51 of 118 search counts wrong). A pass supports the shortcut for this file only; several files must agree before development or certification policy changes. The four tasks are fixed instances, so development and certification ran the same instances; certification on held-out template instances (`design.md` §6.3, D43) was then run at the user's request (2026-10-04): `templates.py` makes each task a template (file path, release numbering and count, content seed, breaking change and its position, question wording; for the lazy task the guide and glossary names, desk, key rules and their positions; for the plain task 10 general-knowledge questions in a generated lazy-load workspace), assigns each instance to the development or held-out part by a hash under a salt frozen in `templates-salt.txt` before any held-out run, and builds 24 instances per part per task; the original fixed tasks count as development instances. The scorer was checked on one development instance per task. `ffh.py` reruns the bar's certification on held-out draws: 59 per task on `gpt-6-luna` at `low` and `medium` and `gpt-6.1-sol` at `high`, and 20 per file task at the 5,000-token limit, with the same failure rules. The original plain task ran in the probe root, because no `project-plain` exists; the held-out plain task runs in a lazy-load workspace. Probe: `/home/gili/cat-client/worktrees/fullread-probe` (`c-p15-*`, `variants/p15-*`). |
| WI-16 | Done 2026-10-01. Trial profiles and cells: contract types `Cell`, `ProfileBinding`, `ProfileCatalog`, `ProfileRule`, `TrialProfile`, `TrialProfiles`; `ProfileInventory` port with `ClaudeProfileInventory` (opus, fable and sonnet with low–max, haiku without effort, from a shipped resource) and `CodexProfileInventory` (listed models of `codex debug models`); session requests carry the cell's profile, judge and deriver use the role profile from the installed `optimize-xhigh` agent; campaign format 2 (format 1 refused); status JSON keys name cells (`cells`, `cell`). `DriverTest.cellsRunUnderTheirProfiles` is trace 15. Mutation checks on code written before its tests: every mutant killed except three equivalent mutants and two untested hops (CX14, the agent effort override, which existed before WI-16 and needs an installed-plugin fake; CX15, `CodexRunnerCli.serve` passing the parsed effort, whose neighbouring links are each tested). Integrated build: 1281 tests, 0 failures; no test at or over 1 second; bats 32/32. |
| WI-17 | Done 2026-10-01 (`8cfd6d829`). Eight check kinds with known-good and known-bad controls, CAT's core-task requirement on every test, no judge session after a failed check, and the check record in the author brief. The campaign and proposal file formats are version 3; version 2 is refused. Merging with WI-19 needed `TierFixtures` to leave the core-task requirement out of profile binding. Full build 1352 tests, bats 32. Open: `tool_used` and `exit_status` checks cannot be decided on real trials, because the Claude and Codex session results record no tool calls; until the adapters record them, such a check counts as an infrastructure failure. Follow-up: WI-17a. |
| WI-17a | Done 2026-10-01: the Claude and Codex adapters record each trial's tool calls and command exit statuses in the session result, from recorded harness output. Claude reports no status field (`design.md` H30); by user ruling its adapter reads the recorded result forms and leaves any other form without a status, which makes an `exit_status` check undecidable. A check whose control lacks what it observes is discarded to the judge. Codex MCP, sleep and image tool variants were not recorded. |
| WI-18 | Done 2026-10-01 (Codex xhigh). The author returns an evidence manifest that is validated before its edit is tried; no-edit and rejected attempts keep their diagnoses; a passing attempt waits for a resumable minimality search that drops unneeded hunks through uncounted promotion runs, and removed hunks are reported; the status report compares working and incumbent snapshot sizes and observed input tokens, keeping missing usage distinct from zero. The campaign and trial format is version 4 and versions 1 to 3 are refused. Merged with WI-17a; on the merged tree the full build passed with 1434 tests, 0 failures, the five bats files passed 34 of 34, and no test took 1 s or more. |
| WI-19 | Done 2026-10-01. `CellTier` derives the active set (bound cell, each model's lowest and highest effort in inventory order, any cell with an adopted failure other than a promotion run) from the contract and the persisted `failedCells`; `Scheduler` screens every cell with 3 trials of the incumbent in series 1, samples only active cells under the coverage floor with each model's lowest-effort cell first, and widens through a persisted `widened` latch once every active cell meets the readiness count conditions. Campaign format 3 (`failed_cells`, `development.widened`); format 2 refused. Status shows each category's active cells and the widened flag (`cellTier` in JSON). Trace 19 is `DriverTest.cellTiering`. Integrated build: 1297 tests, 0 failures; no test at or over 1 second in that run (the delegate's runs saw `GeneratorTest.samplesVariationsAtFrozenProbabilities` at 1.446 s and `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` at 1.059 s, both pre-existing); bats 32/32. WI-32 replaces this tiering (user ruling, 2026-10-04). |
| WI-20 | Done 2026-10-02 (Codex xhigh; integrated as `42c763ca9`; full build 1458 tests, 0 failures; bats 32/32). `AttemptSelection` decides weakness (a cell of an addressed category whose development Wilson lower bound is below 80%, or that has no outcome) and the winner among accepted attempts (most passing promotion runs of the attempt itself, then fewer changed lines, then earlier launch). A weak round reserves 3 attempts per failing category and the driver starts them within capacity once the round's assigned development trials have settled, so every attempt gets the same brief; a round that is not weak keeps the sequential 2 attempts. Only the winner enters minimality; the others keep their overlays and evidence. Reservations are persisted, so a restart relaunches unfinished attempts without counting their runs. The status lists each attempt's passing and total runs, acceptance and winner (`author_attempts`). Campaign format 5; older formats refused. On review, the attempt's run counts excluded the working candidate's reference runs, which had been counted in selection and status (`AttemptSelectionTest.referenceRunsDoNotCountForAttempt` failed first); the three copies of that count became one. |
| Claude interrupt request (portability follow-up, user request 2026-10-01) | Done. `ClaudeProcess.interrupt()` writes a stream-json `control_request` with subtype `interrupt` to Claude's standard input instead of running `kill -INT`; the `InterruptSender` seam and `FakeHarnessCli`'s interrupt replay are removed (`design.md` §3.8, H29). `ClaudeProcessTest.interruptsThroughStandardInput` (R-E15): the in-memory Claude prints the recorded 2.1.287 post-interrupt lines only when it receives that request. The three native Claude interrupt tests passed against Claude 2.1.287 (3.2 s, 4.4 s, 5.2 s). |
| Test-double audit (user request 2026-10-01) | Done (`5079b82b0`, `82ee80ec1`; full build 1433 tests, 0 failures; bats 32/32). `FakeHarnessCli` and `ScriptedHarness` name the recording or documentation behind each scripted reaction (Claude Code 2.1.286/2.1.287, codex-cli 0.147.0/0.159.1, the JDK `Process` contract); unsourced reactions (Claude's post-interrupt sequence, failure after interrupt, permanent blocking, simulated writes) are removed. Of 45 test methods, 4 were removed and the rest kept, fixed or narrowed to the production decision; every fix was shown to fail against broken production. The app-server command test it removed is replaced by `CodexRunnerTest.startsAppServerForInstallation`. |
| Campaign lock failure masking (convention-pass follow-up) | Done (`e085ec1fa`; full build 1438 tests, 0 failures; bats 32/32). `CampaignLock` keeps the primary acquire or release failure and suppresses a close failure; `CampaignLockTest` injects both through `FaultingFileChannel` and failed first. `Driver.open` keeps the open failure when unlocking fails (`d83badc4d`; full build 1461 tests; `DriverTest.openKeepsFailureWhenUnlockFails` failed first). |
| R-W1 exclude notice (convention-pass follow-up) | Done (`138f66e87`; full build 1441 tests, 0 failures; bats 32/32). `ForkView.excludeAdded()` reaches the `start` command's message ("Added /.cat/work/ to the repository's local info/exclude."); `OptimizerCoreTest` covers added, already excluded, and added with a deriver failure, and failed first. |
| WI-21 | Done (`ea5a59fb9`; full build 1584 tests, 0 failures; bats 32/32). The approved `failure-regression-promotion.md` text is delivered unchanged. All 61 rule scenarios and 6 fixtures moved to `.cat/tests/client/common/rules/src/main/resources/rules/<rule>/<case>.md` (fixtures under `fixtures/`); the 16 `suite.json` manifests and the two retired `verification.md` notes were deleted. `RegressionScenarios` discovers project and user scenarios, the deriver receives their content and must cite each scenario of a named prompt source in `regression_scenarios`; `start` and `confirm` refuse an uncited proposal. Proposal format 4 and campaign format 7; older formats are refused, so the plugin is reinstalled only after the running E1 campaign ends, followed by a fresh derivation receipt on the final build. `RegressionScenariosTest` locates the repository through `cat.client.directory`. Open: the test uses `cat.work.temp`, like 11 other tests, which the native-tier removal must replace. |
| Skill proposal display (WI-15 finding) | Done (`c60e6d073`; full build 1568 tests, 0 failures; bats 32/32). In three E1 turns the Codex agent saved the proposal (74 KB, 25 KB, 15 KB) to `/tmp` and linked it instead of showing it. The user approved defining "show" in `first-use.md` as verbatim text in the reply; three regression scenarios are recorded under `.cat/tests/client/common/skills/src/main/resources/skills/prompt-optimizer/first-use/` (recorded, not tested). |
| WI-22 | Done 2026-10-02 (Codex high; full build 1609 tests, 0 failures, none at or over 1 s; bats 32/32). User ruling (`design.md` D29, H31): a turn's reply is every assistant message of the turn. `SessionResult.Completed` carries each turn's messages in order; the Codex adapter keeps every root `agentMessage` and the Claude adapter every root assistant text block, with a result-only final text added once. Checks join a turn's messages with newlines, so a suffix check still ends at the final response; check controls use the same nested shape. The judge, deriver and author prompts state that every message is part of the reply, and author failure records carry the messages. Structured output (deriver, judge, author) is still parsed from the final message only. Campaign format 8; older formats are refused. The first Codex E1 campaign, which plateaued on Codex's progress note, was stopped as the test user with the base clean at the same HEAD. |
| Author edit guidance (WI-15 finding, user ruling 2026-10-02) | Done (full build 1584 tests, 0 failures; bats 32/32). `design.md` §2.7, D29 and D30: authors prefer editing or creating prompt files other than `AGENTS.md` and `CLAUDE.md`, editing those only when a correction cannot otherwise reach the sessions that need it; whether an interim message can be suppressed is not yet known, but its wording can be shaped, so a failure in one is `routing` or `guidance`. The user approved the `author.md` wording; regression scenarios are recorded under `client/common/cli/src/test/resources/author/author/` (recorded, not tested). |
| WI-23 | Done 2026-10-02 (full build 1611 tests, 0 failures; bats 32/32). User ruling: scenarios for guidance packaged as a module resource, such as CAT's plugin rules, skills and optimizer prompts, live in the module's `src/test/resources/<path>/<case>/scenario.md`; `.cat/tests/` holds project and user guidance scenarios. The 61 rule scenarios are back in `client/common/rules/src/test/resources/` with their fixtures at their earlier paths and fixed links; the `first-use.md` scenarios are in `client/common/skills/src/test/resources/prompt-optimizer/first-use/`. `RegressionScenarios` finds module scenarios through each module's test resources, and a `scenario.md` belongs only to the guidance its parent's parent names (`RegressionScenariosTest.readsModuleScenario` and `nestedGuidanceKeepsItsScenarios` failed first; the latter attributed a nested scenario to the enclosing guidance too). `everyScenarioNamesGuidance` and `moduleGuidanceScenariosStayInModule` audit the repository. Three git-replay tests took 1.5 to 1.7 s in this run (`FinalizationTest.publishAndVerifyGitReceipt`, `OutputGatesTest.draftOutputWorktreeLifecycle`, `retainedOutputWorktreeLifecycle`) and under 1 s in the run before it: open. |
| Proposal cell listing (E1 finding) | Done 2026-10-02 (full build 1612 tests, 0 failures, none at or over 1 s; bats 32/32). On the final build, Codex E1's first proposal was 84 KB: six unbound categories under the 44-profile inventory made 264 cells, each listed under trial profiles and again under the minimum session count, and the agent replied with one sentence instead of the proposal. The proposal now lists categories that share a profile set once, with their cell count and naming, and cells that share minimum trials once, naming every cell of a category together (`ProposalTest.groupsCellsThatShareProfilesAndCounts` failed first). The revised 16 KB proposal was shown verbatim. |
| Blank assistant messages (WI-22 follow-up) | Done 2026-10-02 (Codex medium; full build 1626 tests, 0 failures; bats 32/32). The Claude adapter skips blank root text blocks and the Codex adapter blank root `agentMessage` items, so a turn's reply holds only non-blank messages (`ClaudeProcessTest.skipsBlankRootTextBlocks`, `CodexProcessTest.skipsBlankRootAgentMessages`; both failed first). Four tests took 1.08–1.20 s (`DecisionBoundariesTest.lookupsAgreeWithBettingProcesses`, `OutputGatesTest.retainedOutputWorktreeLifecycle`, `TestabilityAuditTest.coreReferencesNoHarness`, `onlyMainReadsEnvironment`) at load average 25 from concurrent Codex sessions; each was under 1 s in the previous build. |
| Convention renames (convention-pass follow-up) | Done 2026-10-02 (Codex medium; full build 1612 tests, 0 failures, none at or over 1 s; bats 32/32). Campaign types no longer repeat their package: `Header`, `Registry`, `Transitions` and the test `Builder`; per-cell types and members say cell where they hold a cell (`CellCounts`, `CellDecision`). The two `ManualClock` doubles are one, in `common.fakes`. Codex had kept the old names in `toString` overrides with tests pinning them; both were removed, so diagnostics name the current record and its fields. |
| WI-24 | Done 2026-10-02 (Codex high; full build 1625 tests, 0 failures, none at or over 1 s; bats 32/32). User ruling (`design.md` D31). The deriver findings gain a required `conflicts` array (at least two distinct requirement ids that name contract requirements, and non-blank evidence); the proposal renders a `## Conflicts` section before the certifiable scope; `confirm` is refused while a conflict is listed, naming both blocking links and conflicts in one refusal (`ProposalTest`, `OptimizerCoreTest.conflictRefusalAndRevision`, `confirmListsEveryBlocker`; each failed first). Resolutions follow in WI-25. |
| WI-25 | Done 2026-10-02 (Codex xhigh; full build 1666 tests, 0 failures, none at or over 1 s; bats 32/32). User ruling (`design.md` D31, D32); the user approved the `first-use.md`, `deriver.md` and `author.md` wording, including the campaign-edits paragraph and the step 4 manifest sentence. Each deriver conflict carries at least two resolutions, the recommended one and its reason, rendered as a numbered list; the skill asks the user to choose before confirming. The author may classify a failure `conflict`, quoting text the confirmed prompt system already held (a quotation absent from the incumbent snapshot is a manifest violation); the report yields no candidate and pauses the campaign with one `resolution-<n>` choice per resolution plus `stop`, presented one conflict at a time, then one revision. The author brief carries the campaign's edits since confirmation as a unified diff. Campaign format 9 (`ProposalTest`, `AuthorManifestTest`, `AuthorTest`, `ConflictPauseTest`, `TransitionsTest`, `CommandsTest`, `OptimizerCoreTest`, `StatusReportTest`, `FileCampaignStoreTest`; each failed first). |
| WI-26 | Done 2026-10-02 (Codex xhigh, three jobs; integrated full build 1760 tests, 0 failures, none at or over 1 s; bats 32/32). User rulings (`design.md` D30, D33, D34, D35, and a revert going back one promotion). An attempt fixes a triggering instance when every run there passes and the streak is unlikely for the working candidate (`p = (s+1)/(n+2)`, `p^k < 0.10`, compared exactly); runs start at 2 and extend to at most 4 as targeted, resumable promotion runs. Promotion needs at least one fix and no regression where the working candidate passes; a minimality reduction must keep every fix of the attempt it reduces. Promotion records the predecessor (snapshot, counts, instance outcomes, review points, regression verdicts, round); a promoted, unedited candidate whose development Wilson upper bound in an addressed cell falls below the predecessor's observed rate reverts before edit-or-continue, and the status shows the revert. A cell's attempts in one round carry the approaches in order (`AuthorApproach`), derived from the round's reservations; `author.md` has the approved step 2 and a `Your approach` section. Stored format 11 (predecessor, reverts, instance outcomes); formats 9 and 10 load and never revert. |
| WI-27 | Done 2026-10-02; the full build, the five distribution Bats files (32/32) and 1777 tests passed, with no test at 1 s or more. `Session` passes `--install-cat` only for trial sessions in both harnesses. `RoleRules` declares each role's rules once (author: write-clearly, failure-prevention, contract-design, rule-organization, prompt-file-dependencies, prompt-file-references; deriver: contract-design; judge and trial: none) and fills the brief's `{{rules}}` section from the installed artifact through `ProjectRuleLoader.loadRule`, which renders the lazy-load paths as the artifact's absolute paths; a stored template without the placeholder gets the section appended; a missing rule file fails naming it. The path-scoped rule text names the absolute rule path and the absolute project root, so CAT's installed rule files never match. The user's and the project's rules do not reach the deriver, judge or author (user ruling, `design.md` D37). Validation on a Codex campaign is pending. |
| WI-28 | Done 2026-10-02; the full build, the five distribution Bats files (32/32) and 1828 tests passed on the integrated tree, with no test at 1 s or more. Each harness maps its parsed events (`AppServerEvidence`, `StreamJsonEvents`) into the shared `SessionEvidence` model, which renders tool calls with input, exit status and complete output, child activity, hook status and response positions, and leaves out root reply text, session-start context, deltas and telemetry. `FileChanges` lists created, modified and deleted files against the staged source (unified diffs; a note naming the effects file for binary or oversized files). The driver keeps the staged source and the rendered evidence across judge retries. `judge.md` takes `{{evidence}}` and names the read-only effects directory; a frozen judge prompt with `{{effects}}` and `{{transcript}}` still renders with both paths. On three E1 trials the evidence is 160,386, 94,470 and 343 bytes against raw transcripts of 1,668,773, 1,560,042 and 72,981 bytes. User ruling (`design.md` D36, H32). Verdict agreement on recorded trials is pending. |
| WI-29 | Done 2026-10-04 (Codex xhigh; full build 1863 tests, 0 failures, none at or over 1 s). User request (`design.md` §2.6, §2.7, §3.1, D39). A triage session decides each failed development trial's impact: blocking, when the reply leaves the user without the turn's correct and complete result, or self-recovering, when it delivers that result at the cost of extra work; an invalid third output is blocking. A blocking failure discards the working candidate's unadopted development trials, interrupting those that run, ends any diagnosis and starts a round; a self-recovering failure keeps the edit-or-continue path. The impact reaches the author brief. Campaign format 12; formats 9 to 11 load without impacts. |
| WI-30 | Done 2026-10-04 (Codex high; full build 1879 tests, 0 failures, none at or over 1 s). User approval (`design.md` §2.7, §6.1, D40). Each requirement states its `trigger`, or null when it always applies, and each test lists the requirements whose trigger it leaves absent; the contract refuses a triggered requirement without such a test. An author's dissimilar case is a contract test id, and the manifest check rejects any other. Campaign format 13 and proposal file format 5; earlier formats load with every requirement always applying. |
| WI-31 | Done 2026-10-04. `CodexModelVersions` matches a slug `gpt-<major>[.<minor>][-<rest>]` and accepts a major of 6 or later; the Codex inventory keeps only such listed models and fails when none qualifies; `ProfileInventory.supportsModel` (Claude: every model) lets the core report each unsupported bound model before it starts the deriver session; the Codex role profile below version 6 fails. Integrated build: 1888 tests, 0 failures, none at or over 1 second. |
| WI-32 | Not started. User rulings (`design.md` §6.6, D27, D42, 2026-10-04): development and readiness only in each category's development cells (its weakest cell, plus any cell where certification failed); the user chooses the certification scope at confirmation from three choices (weakest cell only, latest model versions, or a scope the user describes; user ruling, 2026-10-04: no all-models choice); the weakest cell comes from a shipped capability ranking, with no trials. |
| WI-33 | Not started. User ruling (`design.md` §6.3, D43, 2026-10-04): every test is a template, its instances are split into a development and a held-out part at confirmation, and only held-out instances certify. |
| Campaign ceiling option (WI-15 E8 finding) | Done 2026-10-02. The campaign ceiling was always 8, so two campaigns never reached the shared capacity of 16 and E8 could not observe the bound. `start <target> [--max-concurrency <1..16>]` sets it (default 8; 0, 17, a non-integer or a repeated option are refused before a campaign exists); the stored `ceiling` field is optional on read (absent means 8, format 11 unchanged); status shows it. Full build 1767 tests, 0 failures; bats 32/32. Pre-existing tests over 1 s: `TestabilityAuditTest` `productionReferencesNoTestSupport` 1.2 s, `coreReferencesNoHarness` 1.3 s, `onlyMainReadsEnvironment` 1.5 s. The skill's `start` step does not yet mention the option (a prompt change, awaiting the user). E8 reruns with two campaigns at 16. |
| Launcher help (WI-15 E8 finding) | Done 2026-10-02. Both E8 agents ran `prompt-optimizer --help` or `start --help` to find the ceiling option; the launcher answered "Unknown command" or took `--help` as the target, and the skill stopped. `help` alone, or `--help` or `-h` anywhere, now prints the usage as `{"status":"help"}` with exit 0 before the core is called (`CommandsTest.printsUsageOnRequest`). |
| Inline include mention (WI-15 E8 finding) | Done 2026-10-02. Both E8 campaigns failed every author session with "Direct rule include must name a regular file inside its allowed source root": `prompt-file-dependencies.md` mentions `` `<!-- cat:include path -->` `` in prose, and the role-rule loader took the mention as a directive. A directive is now a line that consists of the directive alone (`ProjectRuleLoaderTest.keepsIncludeDirectiveMentionedInText`, red before the fix); every packaged directive already stood on its own line. Verify: build 0, Bats 32/32, 1834 tests, none ≥1 s. |
| Status comparability (WI-15 E1 and E7 finding) | Done 2026-10-02. Every campaign's status said the improvement over the incumbent and the median trial input tokens were unavailable, with the raw text `INDETERMINATE: [JUDGE, MODEL, REASONING, HOST_TOOLS, EXTERNAL_SOURCES]`: campaigns stored by earlier builds lack the judge, model and reasoning on both sides, and no runner observes host tools or external sources. Fix (`design.md` D38): comparability is per cell; a condition only one side records is `INDETERMINATE`, one neither records is unobserved and a limitation; reasons are sentences naming the condition and cell; the per-cell median trial input tokens no longer depend on comparability. No stored-format change. On a copy of the E1 campaign `20261002T214009-0da57b` the status now shows +78.6, +100.0 and +100.0 points and both medians per cell (working 55710/55434/55440, incumbent 82673/27615/27616); evidence in `~/cat-client/worktrees/v1.0-status/target/status-evidence/`. Full build 1770 tests, 0 failures; bats 32/32; no test over 1 s. |
| Round position, shared references and stopped status (WI-15 E1 finding) | Done 2026-10-02 (Codex xhigh; in the WI-26 build). The status showed the second round as "Round 4" (the round's first reserved attempt number); it now shows each round's position in text and JSON. A weak round's three attempts each ran their own working-candidate reference runs (27 targeted trials instead of 9); a reference run for an instance is now planned once per working candidate, serves every attempt, is excluded from the attempts' counts, and resumes once after a restart. A campaign stopped at a plateau reported `awaiting-user` and the pending plateau; it now reports its outcome (`not certified: plateau`), "The user stopped the campaign." and its choices. |
| Prompt sources at session start (WI-15 E5 finding) | Done 2026-10-02 (Codex high; full build 1676 tests, 0 failures, none at or over 1 s; bats 32/32). An E5 campaign paused `MEASUREMENT_CHANGED` on `PROMPT_SOURCES` because the value listed hooks of later events (Codex `subagentStart`) that run only when a trial takes that action. `design.md` §5 now limits the condition to sources loaded at session start; both readers keep only session-start hooks (`AppServerPromptSourcesTest`, `StreamJsonPromptSourcesTest`, each failed first; `DriverTest.certify` covers equal values not pausing). |
| Proposal text names the user's project (WI-15 E1 finding) | Done 2026-10-02 (Codex high; full build 1702 tests, 0 failures; bats 32/32). The E1 proposal's conflict evidence linked the deriver's staged copy (`.cat/work/optimizer-runs/.../project/AGENTS.md:6`) instead of the user's file. `DeriverProtocol` now replaces the session's staged project root with the project root in every string of the deriver's output before validation and storage; paths to session artifacts outside the staged project stay unchanged (`ProposalTest`, 24 cases, 23 failed first). |
| Status token usage (WI-15 E1 finding, R-E12) | Done 2026-10-02 (Codex high; full build 1712 tests, 0 failures, none at or over 1 s; bats 32/32). `status` in its own process reported "The harness reported no token usage." for a campaign whose sessions all reported usage, because only one `run` slice's in-memory evidence held it and deriver usage was never collected. Every finished trial, judge, author and deriver session's reported usage is now stored by role in the campaign's optimization evidence (format 10), and a trial record keeps its session's usage; the status computes its statistics from that stored evidence. Format 9 records load with no usage, and their `input_tokens` still feed the per-cell medians. |
| Skill launcher paths (WI-15 finding, R-I5) | Done (`a1d70a4bd`; full build 1568 tests, 0 failures, none at or over 1 s; bats 32/32). Codex still ran `${CAT_PLUGIN_ROOT}/client/bin/prompt-optimizer` literally in 2 of 4 runs although the injected skill-environment rule named the plugin directory. The user chose skill-relative paths: every skill names `client/bin/<launcher>` in the plugin directory two levels above the skill's directory, and `BundledSkillLauncherTest` fails if a shipped skill writes the variable (it failed first on all 8 skills). After reinstalling, 6 of 6 Codex runs called the launcher by its absolute path first. |
| Real Git in tests (user ruling 2026-10-02: assume third-party tools work as documented) | Done (`18891bd45`, `f8f57259c`; full builds 1463 and 1461 tests, 0 failures; bats 32/32). The runner tests no longer use Git: `HarnessProjectRepository` and `CodexCuratedPluginCache` expose the commands and environment they give Git, and their tests assert those. `JlinkImageBuilderTest` asserts the launcher text instead of starting launchers and Maven wrappers; `GeneratorTest` checks `Generator.select` at each probability boundary instead of 40,000 draws. git-replay (`04ec22dfd`, one commit per the user's ruling; full build 1567 tests, 0 failures, none at or over 1 s; bats 32/32): its decisions are extracted and tested over captured facts, Git processes fall from 1,845 to 264, and nine integration tests keep real Git, one per Git operation, each under 1 s. §8 lists them and `GitTest`. |
| Fake contracts (doubles audit follow-up) | Done (`06515f1c8`, `7e8a2795d`; full builds 1514 and 1510 tests; bats 32/32). Shared contracts run `FakeWorkspacePort` against `Workspace` and `FakeHostInterruptSignal` against both harnesses' interrupt signals; the fakes were corrected where checks failed. `FakeJudgeProtocol` is deleted: the driver tests use the real `JudgePrompt` and script only the judge session's JSON judgment. |
| Convention clean-ups (convention-pass follow-up) | Done (`4a6723065`; full build 1463 tests; bats 32/32). `Harness.commandName()`, `FileCampaignStore.directory`, `ReadinessGate.meetsPassRateThreshold` and `EnumNames` each own a rule that was repeated. |
| R-M4 comparability wiring (gates audit) | Done (`5cda9e922`; full build 1530 tests, 0 failures; bats 32/32). `Comparability` had no production caller. A condition missing from both observations is now `INDETERMINATE`; the baseline, frozen comparisons and token evidence keep their measurement identity (campaign format 6); decisions, keep-improvement offers, token comparisons and the status go through `Comparability`. Each path's test failed first. Proof adds `BaselineTest`, `CommitTest`, `DecisionTableTest`, `KeepImprovementTest`, `OptimizationEvidenceTest`, `StatusReportTest`. |
| Complete file reads (Codex rule `complete-file-reads.md`, user-directed) | P15's candidate p15-28 meets the bar below in every cell (see the P15 row); nothing is delivered to v1.0 until the user approves it. Prompt-only (no hooks). Bar (user rulings, 2026-10-03): the `design.md` §2.7 readiness gate in each cell, then 59/59 per task (long read, lazy load, search) on Luna low, Luna medium and Sol high, plus 60 runs at `tool_output_token_limit = 5000`; a run without the call and an announce-and-stop need tuning; a failure the agent recovers from by itself, including search overreach (receiving over 90% of a file to answer a search or count), extends that cell to 94 runs; a wrong count is tallied apart. The candidate bundles `read-file`, which prints a file in parts of at most 18,000 bytes, each ending with the command for the next part or `[end of <path>]`, and per Codex thread redirects a repeated read to the next unread part. Prompt-embedded scripts failed under the 5,000-token limit or on copy errors; a bundled `search-file` was dropped because agents used it instead of loading lazily named files. Hints in tool output that offer a way out of reading also made agents stop loading lazily named files. Variant seq44 (`read-file` only) passed its screen with the current `read-file` (276 runs, no failure) and passed certification on Luna low (177/177), but failed at the 5,000-token limit: on lazy run 20 the agent read only part of an unrequested file, then searched the glossary that `AGENTS.md` told it to load instead of reading it. Variant seq49 (every file an instruction names is read before any other file is searched or read) was stopped after 250 of its 440 screening runs, all passing, because the P15 campaign below restarts this rule from its v1.0 text; that campaign's candidate also needs this bar's cells before delivery. The probe harness and evidence are in `/home/gili/cat-client/worktrees/fullread-probe` (`ff.py`, `score.py`, `c-*.out`). Delivery, after the user approves a certified variant: bundle the scripts under the plugin's `client/bin` with bats tests, name them through `<!-- cat:plugin-root -->`, and update the rule and its scenarios. |

Changes made to `design.md` and this plan during implementation, apart from this section:

- R-S6: owner changed from WI-1 to WI-1, WI-3. Its proof `AdmissionTest` tests admission, which WI-3 builds.
- `design.md` §11: added H11–H13 (premises P1–P3).
- §1.1 assignments for WI-0 and WI-15: at the user's direction (2026-10-01), the orchestrator sends ESC through a terminal multiplexer, and the user presses ESC only if that fails.
- `design.md` §11: added H14–H23 (premises P4–P11). §12: added a result column. §3.1: judge and deriver output may be one JSON object alone in a Markdown code fence (H23). §3.2: a Claude turn includes its background children's completion; Codex trials cannot create child worktrees (H17, H18). §3.8: the Codex runner interrupts with `turn/interrupt`; reconciliation terminates the processes recorded in leftover run manifests (H19, H20). §5: loading observation names what each harness exposes (H21).
- §6 and §1.1: WI-8's runner fixes need only WI-0, run before the rest of WI-8, and go to `optimize-high`.
- §2: added a candidate overlay store follow-up (WI-4/WI-9). `design.md` §4.1 stores candidate overlays, but no work item built the production store that WI-9's `CandidateOverlays` needs.
- `design.md` §3.3 and D8: the risk ceiling first permits 2, 4 and 8 trials at `n` = 11, 23 and 47 (D8 said 48); R-E4's proof now tests each threshold on both sides. D3: a committed 97% candidate certifies about 17%, matching §2.7 (it said 15%). WI-1 found both.
- §4: added the gate results. WI-8: added the runner fixes for H17 and H19 and their allowlist. WI-9: reconciliation terminates recorded processes. WI-10: loading split and the output extraction rule.
- `design.md` §5: a runner or CAT version change is a material harness change (H17). WI-3 asked whether these conditions are material; recorded as D21.
- `design.md` §10 (T3, T6) and §14 (G6, G7, G9), plan §1 test tiers, §3 proofs, WI-5, WI-7, WI-8, WI-10, WI-14, WI-15, §8 and §9 gates 2–3: Java tests are unit tests and never start a model; only prompt tests, run end to end, use one (user ruling, 2026-10-01). Native (N) proofs became end-to-end (E) proofs; WI-14 retires the native tier.
- `design.md` §3.2 and §11 H24: a Claude turn ends at a root reply only when the task list is empty and no root completion notice is unseen by a later root model call. Found by the follow-up attribution probes.
- `design.md` §3.2 and §11 H25: a Claude turn ends when Claude reports itself idle with an empty task list; this replaces the H24 stream rule. A Claude that does not report its state fails the turn at its first `init`. The runner has no turn time limit of its own and reports a waiting turn to its caller every 30 seconds (user rulings, 2026-10-01).
- `design.md` §4.1, §4.2 and §4.3: the unconfirmed proposal is stored with the campaign; confirmation records trial fixtures and derived prompt sources into the fork; an authority warning is an `awaiting-user` decision fed by the author's report, and an authorized target is recorded in the campaign. §2: added the authority-warning follow-up. Found in the WI-13 review.
- §8: the unit-test runtime limit is 1 second, matching `design.md` §10 T7 (it said 2), and each integration lists slower tests.
- WI-14: the session-port contract keeps running against the real adapters, over recorded events, once the native session tests are deleted.
- `design.md` §4.1 and §4.2: the stored proposal is the latest one with its derived sources, and a revision after confirmation that needs new fork participants is refused (found in the WI-13 amendment).
- `design.md` throughout: "native" became "harness" where it meant the harness or its model process; §10 T3: fakes carry only contract-checked behaviour, recordings name their harness version, and end-to-end runs refresh them. Plan §2 harness terminology row, §8 fake rule, WI-14 and WI-15 recordings (user rulings, 2026-10-01).
- §2: added the pouch-scope follow-up for the optimizer and bridge wiring (found when the user asked about pouch scopes).
- `design.md` §10 T4 and R-T1: a harness's production scope may read the process environment, like `main`; test scopes supply their own values (found in the pouch-scope integration).
- WI-14 and WI-15: the session contract tests and the remaining native-tier removal moved to WI-15, because the existing recordings cannot satisfy every `SessionPortContract` check; WI-15 records untrimmed streams and an interrupted turn per harness. WI-15 runs Codex first and starts Claude only after Codex passes (user ruling, 2026-10-01).
- `design.md` §3.9: the development minimum counts the readiness gate's batch condition. The old formula alone could undercount, contradicting "exact". WI-12 found it.
- `design.md` §1.2, §2.1, §3.1, §3.9, §5, §6.1, §6.4, new §6.6, §9, §12 (P12, P13), §13 (D11, D15, D22–D26) and §14 (G1, G2, G5, G7): trial profiles and cells as the statistical unit; deterministic checks with controls beside semantic judgment under a frozen role profile; a core-task requirement in every test; author failure classification and a per-hunk citation manifest; a minimality pass; candidate-versus-incumbent prompt size and input tokens (user rulings, 2026-10-01). Plan: R-C5 rewritten; R-C8–R-C11, R-D10, R-D11, R-E17, R-E18 and R-I5 added; WI-16–WI-18 added with §1.1 assignments, §6 dependencies and `DriverTest` traces 15–18; WI-15's trial-based scenarios now wait for WI-16–WI-18.
- `design.md` §3.3 rule 2, §6.6 and D27: development cell tiering; plan R-E3 reworded, R-E19 and WI-19 added with §1.1, §6 and trace 19 (user ruling, 2026-10-01). `design.md` P14 and plan §4: the effort probe tests a pre-stated null hypothesis with the 2×4 exact test, and a non-rejection does not justify dropping efforts.
- `design.md` §1, §2.1, §2.7, §2.8, §6.4 and §6.5: the outcome table lists `not certified` and says which outcomes close
  the campaign and what follows each open one; "material category", "material requirement" and "material obligation"
  became the categories, requirements and obligations of the confirmed contract; a weak round makes 3 attempts for each
  failing category, as R-D6 counts attempts; `accept without certification` is offered at a stop, a plateau or
  unavailable certification when the working candidate is not the incumbent. Plan R-D12 says "for that category". All
  four follow the implementation (found in the convention pass).
- `design.md` §1.2, §3.1, §6.4, D29 and H31: a turn's reply is every assistant message of the turn; R-C12 and WI-22 added with §1.1, §2, §6 (user ruling, 2026-10-02, after the first Codex E1 campaign).
- `design.md` §2.7, D29 and D30: authors prefer prompt files other than `AGENTS.md` and `CLAUDE.md`, and treat a failure
  in an interim message as a prompt defect, since its wording can be shaped even if it cannot be suppressed; R-D10 cites D30 (user ruling, 2026-10-02).
- `design.md` §8: a scenario for guidance packaged as a module resource lives in the module's `src/test/resources/`;
  R-I3 and WI-23 added with §1.1, §2, §6 (user ruling, 2026-10-02).
- `design.md` §8 states R-I5's obligation (locating CAT's files without undefined shell variables), which R-I5 already
  cited; §9 lists the `CandidateOverlays` port. Plan §7 declares H-13 (needed edits → authority warning) and H-14
  (candidate overlay), traced from the driver (found in the convention pass).
- `design.md` §2.7, §9, §14 G1 and D28: 3 concurrent author attempts while the working candidate is weak, best accepted attempt promoted; plan R-D12, WI-20 with §1.1, §6 and trace 20 (user ruling, 2026-10-01).
- `design.md` §3.3 rule 2, §6.6 and §12 P15: each model's lowest-effort active cell is sampled first in development; P15 asks whether developing only in the weakest model's lowest-effort cell suffices for stronger models and higher efforts, tested after a real prompt file is optimized; plan R-E19 and WI-19 updated, §4 P15 probe and §2 row added (user ruling, 2026-10-01).
- `design.md` §11 H28 and §12 P14 result: a rule without a conflict on `gpt-6-luna` also varies with effort (user request, 2026-10-01).
- `design.md` §11 H27 and §12 P14 result: the intermediate-compliance rerun shows reasoning effort changes compliance (user request, 2026-10-01).
- `design.md` §12: added P14 (does reasoning effort change compliance?); plan §4 adds its probe and §2 its row (user request, 2026-10-01).
- §1 and §9 gate 8: the codebase never cites `design.md` or this plan, which are deleted after implementation; the
  requirement tags stay until the traceability gate passes, then a last commit removes them (user ruling, 2026-10-01).
- §2: added the plugin-root follow-up (R-I5). Codex tool shells define no `CODEX_HOME`, so the `skill-env.md` derivation always failed (found in WI-15).
- `design.md` §3.8: the Claude runner interrupts with a stream-json `control_request` (H29). §11: added H29.
- `design.md` §6.1, §8, D13 and G10; plan R-I3, §1.1, WI-21 and §6: a guidance correction records its regression scenarios under `.cat/tests/`, they are recorded, not tested, until a covering campaign, and CAT refuses a proposal that omits one. Nothing ran the old rule suites once the prompt-evaluation runner was retired (user rulings, 2026-10-02).

- `design.md` §2.7, §14 G1, D30, D33 and D34: an attempt that fixes at least one triggering instance without a
  regression is promoted, a reduction keeps every fix, the attempts of a round follow distinct approaches, and authors
  correct an instruction in the file that states it; R-D3, R-D11 and R-D12 reworded, R-D14 and WI-26 added with §1.1,
  §2 and §6 (user ruling, 2026-10-02, after the E1 `gpt-6-luna`/low plateau).
- `design.md` §2.7, §14 G1 and D35: an instance counts as fixed only when the attempt's streak there is unlikely for
  the working candidate, with up to 4 runs, and a promoted candidate that falls clearly below its predecessor is
  reverted; R-D3 reworded, R-D15 added to WI-26 (user ruling, 2026-10-02).
- `design.md` §3.1, §11 H33 and D37: the deriver, judge and author run without CAT's plugin and receive their role's
  rules in the brief; R-E20 and WI-27 added with §1.1, §2 and §6 (user ruling, 2026-10-02).
- `design.md` §3.1, §6.4, §11 H32 and D36: the judge receives a rendered evidence section instead of the trial's log;
  R-E21 and WI-28 added with §1.1, §2 and §6 (user ruling, 2026-10-02).
- `design.md` §6.6: the Codex inventory leaves out the `ultra` effort (`max` reasoning plus automatic task
  delegation), so each model's highest effort is the one below it; the cell counts dropped from 44 to 39.
  `CodexProfileInventory` skips `ultra`, proved by `CodexProfileInventoryTest` (user ruling, 2026-10-03).
- `design.md` §1, §1.2, §2.1, §2.5, §2.6, §2.7, §2.8, §3.3 rule 2, §3.9, §6.1, §6.3, §6.6, §8, §12 P15, §13 (D27,
  D42, D43) and §14 (G2, G3, G4, G8): development, readiness and author rounds run only in each category's development
  cells (its weakest cell, plus any cell where certification failed); the weakest cell is the lowest effort of the
  least capable model in the scope by the vendors' published capability ranking, found with no trial; the user chooses
  the certification scope at confirmation from three choices (weakest cell only, every cell of each model family's
  latest version, or a scope the user describes), with no default; every test is a
  template whose instances are split into a development part and a held-out part, and only held-out instances certify
  or measure the baseline.
  Plan R-E3, R-E19, R-D1, R-D3, R-D7, R-D9, R-C1, R-C4, R-C11 and R-P4 reworded; R-C15, R-C16 and R-E22 added with
  WI-32, WI-33, §1.1, §2, §6 and traces 19 and 21; WI-19's tiering is replaced (user rulings, 2026-10-04).

Update a row only from current evidence: its exit check passing on the current tree. Reopen a row when an input its
evidence depended on changes.

## 3. Requirement inventory

Each requirement points to the `design.md` section that defines it, its owning work item and its proof. Section
numbers in this inventory refer to `design.md`. A requirement is closed only when every listed proof passes at its
tier. Tier abbreviations: U = unit, E = end-to-end.

### 3.1 Statistics

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-S1 | Joint budget: certification `α_i = 0.04/(i(i+1))` per certification index; improvement `β_i = 0.005/(i(i+1))` per certification index, divided by `K`; incumbent `γ_b = 0.005/(b(b+1))` per incumbent baseline, divided by `K`; a new lineage or measurement identity starts the next baseline. Development candidates that are never committed spend nothing. | §2.2 | WI-1 | U: `ErrorBudgetTest` |
| R-S2 | Certification e-value with the four bets and weights. Exact rational decision; equality never certifies. Floating-point shortcuts only outside a proven-safe margin. | §2.3 | WI-1 | U: `BettingProcessTest` (boundary pairs `(S, F)` either side of `1/α_i` for `i = 1..5`) |
| R-S3 | Below-minimum e-value and the clearly-below decision. | §2.3 | WI-1 | U: `BettingProcessTest` |
| R-S4 | Incumbent deficiency decision at `K/γ_b`. For `b = 1`, the fewest failures that prove deficiency are 10, 11, 11, 12 and 12 of 50, and 16, 16, 17, 17 and 17 of 100, for `K` = 1, 2, 3, 5 and 8. | §2.3, §2.5 | WI-1 | U: `BettingProcessTest` |
| R-S5 | Display bounds that invert both tests at any null `t`, labelled with their level. | §2.3, §2.9 | WI-1 | U: `ConfidenceBoundsTest` |
| R-S6 | Cap calibration matching every row of the §2.4 table (`i` = 1–5, 10, 50 and 100). Calibration failure blocks admission. | §2.4 | WI-1, WI-3 | U: `CapCalibrationTest` (the only test that runs calibration); `AdmissionTest` |
| R-S7 | Comparison decisions: proven better when `E↑` at null `UI(k)` exceeds `K/β_i`, and proven worse when `E↓` at null `LI(k)` exceeds `K/β_i`. `UI` is rounded up and `LI` down to 4 decimal places. The displayed difference bound `LC − UI` is positive when certified and deficient. | §2.1, §2.3 | WI-1 | U: `ComparisonTest` (both sides of each threshold; rounding direction) |
| R-S8 | Wilson exploratory interval and one-sided Clopper–Pearson upper bound. | §2.7, §3.3 | WI-1 | U: `ExploratoryBoundsTest` |
| R-S9 | The certification index is assigned at commit and is permanent. Only a commit spends allowance. Edits, failed attempts, revisions, restarts and cancel-then-resume never reset the index; only a new campaign does. | §2.2, §7 | WI-3, WI-4 | U: `CampaignTransitionsTest`, `FileCampaignStoreTest` |

### 3.2 Development

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-D1 | Readiness gate, in every development cell: at least 100 trials, at least 99%, latest two batches of 50 each at least 98%, no unresolved defect, baseline met. | §2.7 | WI-3, WI-32 | U: `ReadinessGateTest` (each boundary on both sides) |
| R-D2 | Edit-or-continue: pause admissions, up to 3 reruns, edit if reproduced or more than 2 failures, otherwise continue with the window reset to 1. | §2.7 | WI-3, WI-9 | U: `EditOrContinueTest`; U: `DriverTest` (pause and resume) |
| R-D3 | Author attempts are validated, run twice on triggering instances and once on the regression set, and promoted by the §2.7 rule: the attempt fixes at least one triggering instance (every run passes and the streak is below 10% likely for the working candidate's smoothed pass rate there, with runs extended from 2 up to 4) and fails no regression instance that the working candidate passes. The regression set holds development instances that failed in representative development trials only. Targeted runs never count. | §2.7, D33, D35 | WI-3, WI-9, WI-26, WI-33 | U: `PromotionTest`; U: `DriverTest` |
| R-D9 | Authors, triage and diagnosis receive development instances and outcomes only; from a formal failure they receive its cell, test definition and failed requirements, never a held-out instance, reply or judge rationale. | §3.1, §6.3 | WI-9, WI-33 | U: `DriverTest` (author input during a formal attempt and after a failed one) |
| R-D4 | A new candidate starts new counts; earlier outcomes are retained for diagnosis. | §2.7 | WI-3 | U: `CampaignTransitionsTest` |
| R-D5 | Review checkpoint at 200 unresolved trials: stop the category, offer choices, record the next review point. | §2.7 | WI-3 | U: `ReviewCheckpointTest` |
| R-D6 | Rounds and plateau: a round gives each failing category a diagnosis and up to 2 author attempts. A plateau requires a complete round without promotion. Interruption, infrastructure failure or time never cause a plateau. | §2.7 | WI-3 | U: `PlateauTest` |
| R-D7 | Incumbent baseline arm of held-out instances: 50 in every certification-scope cell, plus 50 in a scope cell below 95% not yet proven deficient. The incumbent's development trials never count toward it. A commit requires the baseline. The baseline is reused while measurement identities stay comparable; otherwise the incumbent is measured again as a new baseline with its own share. | §2.5 | WI-3, WI-32, WI-33 | U: `BaselineTest`, `AdmissionTest`, `SchedulerTest` |
| R-D8 | Development results are labelled `development estimate` and `not certified`. | §2.7, §2.9 | WI-12 | U: `StatusReportTest` |
| R-D10 | Author manifest: each failure's classification (routing, guidance, not guidance) and, for each changed hunk, the cited failing instances and its analogous and dissimilar cases. An attempt with an uncovered hunk or an unknown citation is rejected and retained; an attempt that edits nothing produces no candidate and its classifications reach the round's diagnosis and the review or plateau report. | §2.7, D25, D30 | WI-18 | U: `AuthorManifestTest`, `DriverTest`, `StatusReportTest` |
| R-D11 | Minimality pass: delta debugging over the promoted attempt's hunks, each reduction run through the promotion runs and kept when it fixes every triggering instance that the attempt it reduces fixed and fails no regression instance that the working candidate passes, ending when no single hunk can be removed; its runs are targeted and never counted; the minimal attempt is promoted; the report names the removed hunks. | §2.7, D26 | WI-18 | U: `MinimalityTest` (removes an unneeded hunk, keeps a needed one, ends at single hunks), `DriverTest` |
| R-D12 | Concurrent attempts while weak: when any cell of a category the round addresses has a development lower bound below 80% or no development outcome, the round makes 3 independent attempts for that category from the same evidence; each runs the same promotion runs; the accepted attempt with the most passing promotion runs wins, ties to fewer changed lines, then the earlier attempt; only the winner is minimized and promoted; the others are retained; the status records each attempt's promotion results. | §2.7, D28 | WI-20 | U: `AttemptSelectionTest` (weak and not-weak rounds, winner, both tie-breaks, no accepted attempt), `DriverTest` trace 20, `StatusReportTest` |
| R-D13 | An author conflict report whose quotations the confirmed snapshot holds pauses the campaign with each resolution and `stop`; a report quoting only campaign edits is rejected; a chosen resolution becomes a revision that needs confirmation and starts a new lineage. | §2.7, §7, D32 | WI-25 | U: `AuthorManifestTest`, `DriverTest`, `OptimizerCoreTest` |
| R-D14 | The attempts a category receives in one round follow the §2.7 approaches in order: correct the instruction where it is stated (`AGENTS.md` and `CLAUDE.md` included), rewrite rather than add, add the missing guidance; a sequential round uses the first two. The author corrects an instruction in the file that states it and adds new guidance to `AGENTS.md` or `CLAUDE.md` only when it cannot otherwise reach the sessions that need it. | §2.7, D30, D34 | WI-26 | U: `AuthorTest`, `DriverTest` |
| R-D15 | A promoted candidate not yet edited is reverted to its predecessor when its development 95% Wilson upper bound in a cell the promoting round addressed falls below the predecessor's observed pass rate there; the check runs before edit-or-continue, the predecessor's counts return, and the round counts as producing no promotable attempt. | §2.7, D35 | WI-26 | U: `PromotionTest` or a revert policy test, `DriverTest` |
| R-D16 | Each failed representative development trial has an impact that a triage session decides before the outcome is recorded: blocking (the reply leaves the user without the turn's correct, complete result) or self-recovering (the reply still delivers that result, and the failure cost only extra work); an unclassifiable failure or a third invalid triage output is blocking. A blocking failure discards every unadopted development trial of the working candidate, interrupting those that run, ends any diagnosis in progress, and starts a round without diagnostic reruns; a self-recovering failure takes the R-D2 path. The impact reaches the author brief and changes no count or claim. | §2.6, §2.7, §3.1, D39 | WI-29 | U: `EditOrContinueTest`, `TriagePromptTest`, `DriverTest` (fail-fast trace), `FileCampaignStoreTest` |
| R-D17 | The proposal states each requirement's trigger or that it always applies; CAT refuses a proposal in which a requirement with a trigger has no test where that trigger is absent. Each manifest hunk names its dissimilar case as a contract test; CAT rejects an attempt that names one the contract lacks. | §2.7, §6.1, D40 | WI-30 | U: `ProposalTest`, `AuthorManifestTest` |
| R-D18 | In Codex, the inventory holds only listed models of version 6 or later, the number after `gpt-` in the slug, and leaves out a slug without that form; CAT refuses a proposal in which a Codex agent TOML binds a model below version 6, naming the file and the model, and a Codex role profile below version 6. | §6.1, §6.6, D41 | WI-31 | U: `CodexProfileInventoryTest`, `ClaudeProfileInventoryTest`, the core's derivation test |

### 3.3 Formal certification

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-F1 | A commit freezes the candidate, `α_i`, `β_i`, `UI(k)`, `LI(k)`, bets, cap and allocation policy before the first formal draw. The formal stream starts only after the freeze. | §2.4, §6.3 | WI-3 | U: `CommitTest` |
| R-F2 | Per-category certified, clearly-below and cap decisions map to attempt outcomes and to the actions in §2.8. A frozen cap is never raised and an ended attempt never reopens. | §2.4, §2.8 | WI-3 | U: `DecisionTableTest` (every row) |
| R-F3 | Certified and not worse leads to automatic delivery. Worse needs an explicit trade-off. Uncertified acceptance is explicit and labelled. | §2.8 | WI-3, WI-11 | U: `DecisionTableTest`; E: E1, E3 |
| R-F4 | Formal outcomes never revise the same candidate. An edit needs a new attempt. | §2.6 | WI-3 | U: `CommitTest` |
| R-F5 | An unavailable condition discovered after a commit ends the attempt, keeps outcomes and allowance, and survives restart. | §6.5 | WI-3 | U: `CampaignTransitionsTest` |
| R-F6 | Keep improvement: one certification attempt under the current contract shows at least one category proven better, none proven worse, and every other category certified or with at least 100 adopted outcomes. A not-certified certification attempt that meets this pauses with `keep improvement`, `optimize further` and `stop`. Qualifying attempts stay available after `optimize further` and at a later review checkpoint, plateau or stop. CAT ranks qualifying attempts only within one measurement identity: the best has the highest 95% Wilson lower bound on its weakest category, with ties going to more categories proven better and then to the more recent attempt. It offers the best attempt of each identity as its own choice, the current identity first and each earlier one labelled historical with its differing conditions named, and never ranks attempts across identities. Keeping is always explicit, ends optimization, is labelled `improved, not certified` and delivers the chosen attempt's frozen snapshot. | §2.8, §2.10 | WI-3, WI-9, WI-11, WI-12 | U: `KeepImprovementTest` (each condition on both sides; the ranking and each tie-break; an earlier-contract attempt excluded; attempts under two measurement identities offered separately and never ranked together), `DecisionTableTest`, `DriverTest` (traces 12–14), `DeliveryTest`, `StatusReportTest` |

### 3.4 Execution

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-E1 | Logical-trial lifecycle with write-ahead ordering: assignment persisted before the session starts, outcome before adoption. | §3.2, §3.8 | WI-4, WI-9 | U: `FileCampaignStoreTest` (fault after every step); U: `DriverTest` |
| R-E2 | Infrastructure failures rerun the same logical trial; 3 failures pause the campaign; rate-limit rejections are excluded; a timeout is behaviour, not a retry. | §3.2, §2.6 | WI-9 | U: `DriverTest` |
| R-E3 | Allocation order: targeted work, development cells and coverage floor among them, Wilson lower bound, formal rule (the category furthest from certifying), baseline arm, with the stated tie-breaks. | §3.3 | WI-3, WI-32 | U: `SchedulerTest` |
| R-E4 | Concurrency: risk ceiling, slow start, category limit from the passes still required to certify, campaign ceiling (default 8, range 1–16), baseline arm on the remaining ceiling. | §3.3 | WI-3 | U: `ConcurrencyPolicyTest` (no failures: 1 at n=10 and 2 at n=11, 3 at n=22 and 4 at n=23, 7 at n=46 and 8 at n=47; 10 at 1 of 100; reset after a failure) |
| R-E5 | Adoption in assignment order; post-decision outcomes never change a decision. | §3.4 | WI-3, WI-9 | U: `AdoptionTest`; U: `DriverTest` (completions out of order) |
| R-E6 | Throttling at 80% and 95% utilization, rejection backoff of 1, 2, 4 … up to 30 minutes, per-trial records, no evasion features. | §3.5 | WI-3, WI-8 | U: `ThrottleTest`; E: rate-limit state in the per-trial records of E1 per harness |
| R-E7 | Authentication warning for a subscription login, including the minimum session count. | §3.5 | WI-8, WI-10 | U: `ProposalTest`; E: E1 login classification per harness |
| R-E8 | Shared harness capacity is an injected per-harness instance, acquired by both runner CLIs and per optimizer session, with `tryAcquire` and an injected pause. | §3.6 | WI-5 | U: `HarnessCapacityTest`; E: E8 |
| R-E9 | Slices: status kinds, admission cutoff from the 90th-percentile duration or the time limit, 1-minute grace, cancellation of the remainder, `busy` for a second driver. | §3.7 | WI-9 | U: `SliceTest` |
| R-E10 | Interrupts: the Codex rollout watcher stops within 15 seconds; the Claude transcript backstop; Claude kill recovery. | §3.8 | WI-8, WI-9 | U: `RolloutInterruptSignalTest`, `TranscriptInterruptSignalTest`, `DriverTest`; E: E2 per harness |
| R-E11 | Reconciliation runs first in every `run`: adopts recorded outcomes, reruns started trials that have no outcome, checks the worktree. | §3.8 | WI-9 | U: `ReconciliationTest` |
| R-E12 | Minimum remaining trials by the §3.9 formulas for the development and formal phases; token statistics; no totals or time estimates. | §3.9 | WI-12 | U: `RemainingWorkTest` |
| R-E19 | Development cells: representative development trials and author rounds only in a category's development cells (its weakest cell, plus every cell where a certification attempt of the lineage adopted a failure); the coverage floor among them. | §3.3, §6.6, D27 | WI-32 | U: `SchedulerTest` (only development cells sampled, the floor among them, a formal failure adding a cell), `DriverTest` trace 19 |
| R-E18 | The status compares the working candidate with the incumbent on prompt size (bytes and words) and on median input tokens per trial in each cell where both have outcomes. | §3.9, D11 | WI-18 | U: `StatusReportTest` |
| R-E13 | Every nested role runs through the runners. Judge and deriver output is schema-validated, and invalid output is an infrastructure failure. The host agent never authors or judges. | §3.1 | WI-8, WI-10 | U: `JudgeVerdictTest`, `ProposalTest`; E: E1 runs trial, judge, author and deriver sessions per harness |
| R-E14 | Child subagents and child worktrees stay within one logical trial; reconciliation before judging; observation limits. | §3.2 | WI-8 | E: E5 child scenarios per harness (premise P7) |
| R-E15 | The Claude adapter classifies an interrupt it requested as an interruption (H10). | §11 | WI-8 | U: `ClaudeSessionsTest`; E: E2 in Claude |
| R-E16 | The observed model comes from harness events, never the alias. | §5 | WI-8 | U: event parsing tests per harness; E: E1 observed model per harness |
| R-E17 | A trial session starts under its cell's profile and the deriver, judge and author sessions under the role profile (the `optimize-xhigh` binding); each runner passes the model and effort to its harness; the observed profile is recorded per cell and decides comparability. | §3.1, §5, §6.6 | WI-16 | U: `ClaudeSessionsTest`, `CodexSessionsTest` (requested profile reaches the harness arguments), `MeasurementIdentityTest`; E: E1 per harness |
| R-E20 | The deriver, judge and author sessions run without CAT's plugin in both harnesses; trial sessions keep it. The author brief carries the complete text of its six role rules and the deriver brief the contract-design rule, read from the installed artifact with lazy-load paths naming its files; the judge brief carries none. | §3.1, D37 | WI-27 | U: runner session tests per harness, `AuthorTest`, deriver prompt test |
| R-E21 | The judge receives a rendered evidence section per turn (tool calls with input, exit status when reported and complete output; child activity; hook status) and the trial's file changes against the staged source, without streaming fragments, telemetry or injected session-start text, from each harness's events; a frozen judge prompt with the earlier placeholders still renders. | §6.4, D36 | WI-28 | U: evidence renderer tests on recorded events per harness, `JudgePromptTest` |
| R-E22 | Weakest cell: within the certification scope (for weakest cell only, within the latest versions), the lowest effort of the least capable model by CAT's shipped capability ranking (families ranked from the vendor's published model documentation, with its address and reading date; within a family, older versions below newer), with no trial; a category with one cell uses it; the user may correct or extend the ranking in revision feedback; CAT refuses to confirm while an inventory model's family is unranked. | §6.6, D27 | WI-32 | U: `CapabilityRankingTest` (resource order for both harnesses, version order within a family, an unranked family), `CellTierTest` (weakest cell per scope, one cell, a corrected ranking) |

### 3.5 Workspace

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-W1 | Storage under `.cat/work/optimizer/<id>/`; loading reads only the snapshot; `info/exclude` added and reported when needed. | §4.1 | WI-4, WI-7 | U: `FileCampaignStoreTest`, `ForkTest`, `OptimizerCoreTest` (the notice) |
| R-W2 | Clean-base refusal before anything is created. The fork records paths, bytes, modes and links, copies ignored and external participants, and creates a detached optimization worktree. | §4.2 | WI-7 | U: `ForkTest` (real Git, once per operation) |
| R-W3 | Candidate identity is the hash of the overlay and the fork. | §4.3 | WI-6 | U: `OverlayTest` |
| R-W4 | Edit authority rules, including rejection of ambiguous dual-path edits, plus diagnosis warnings. | §4.3 | WI-6 | U: `AuthorityTest` |
| R-W5 | Staged source: tracked files, recorded participants, fixtures and overlay; links dereferenced; cycles and missing targets block confirmation; `.cat/work/` excluded; caches deleted when unused. | §4.4 | WI-7 | U: `StagedSourceTest`; the runner's project copy accepts the staged source in both harnesses |
| R-W6 | Symbolic-link report contents, and the limitation recorded on results. | §4.5 | WI-7, WI-12 | U: `LinkReportTest`, `StatusReportTest` |
| R-W7 | Inventory validation of author attempts; unowned-change detection at every `run`, before promotion and before delivery. | §4.6 | WI-6, WI-9 | U: `InventoryDiffTest`, `DriverTest` |
| R-W8 | Delivery: verify the optimization worktree and materialize a selected candidate that is not the working candidate, preflight, journal, preimage checks, resume applying each change at most once, final verification, historical result after a base-only prompt edit. CAT never commits. | §1, §4.7 | WI-6, WI-11 | U: `DeliveryPlanTest`, `DeliveryTest` (fault between writes, concurrent edit, no commit created) |

### 3.6 Measurement, contract and lifecycle

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-M1 | Measurement identity is recorded for every trial and result. | §5 | WI-3, WI-8 | U: `MeasurementIdentityTest` |
| R-M2 | Each listed material change makes a certification historical without deleting it. | §5 | WI-3 | U: `ApplicabilityTest` (one case per change kind) |
| R-M3 | A `MEASUREMENT_CHANGED` pause before the next call, with three ways to continue. A loader-configuration change offers only restoring it or starting a new campaign. | §5 | WI-9 | U: `DriverTest` |
| R-M4 | Comparability by exact equality, campaign-wide and per cell; a condition only one side records gives `INDETERMINATE` and an unavailable comparison; a condition neither records is unobserved, does not prevent comparison and is a limitation; each differing or missing condition is named in words. | §5 | WI-3 | U: `ComparabilityTest`, `DecisionTableTest`, `KeepImprovementTest`, `OptimizationEvidenceTest`, `StatusReportTest` |
| R-M5 | A run observed under conditions that differ from its trial's measurement identity yields no outcome, is kept as an observation, does not count toward the infrastructure-failure limit, and reruns the same instance after a restore. A new measurement identity during a certification attempt ends it `not certified: testing environment changed`; it never resumes, its allowance stays spent and the next attempt takes the next index. A restore lets the attempt continue. | §5, §7 | WI-3, WI-9 | U: `CampaignTransitionsTest`, `DriverTest` (trace 14) |
| R-C1 | The proposal lists every §6.1 item, without aggregate summaries, including the capability ranking with its source, each certification scope's cells, weakest cells and minimum session count, and each test definition's instance count per part. | §6.1 | WI-2, WI-10, WI-32, WI-33 | U: `ProposalTest`; E: E1 deriver per harness |
| R-C2 | Feedback regenerates a complete proposal; nothing runs before confirmation. | §6.1 | WI-9, WI-10 | U: `DriverTest`, `ProposalTest` (regeneration) |
| R-C3 | Categories and a balanced benchmark, frozen at confirmation; allocation never changes weights. | §6.2 | WI-2 | U: `DistributionTest` |
| R-C4 | Generator validation; separate durable seeded streams for development, formal and baseline draws, each confined to its part; targeted work marked before execution; formal draws after the freeze; repeated instances within a part allowed. | §6.3 | WI-2, WI-33 | U: `GeneratorTest`, `DrawStreamTest` |
| R-C5 | A frozen judge profile under the role profile; the judge decides only semantic requirements and sees deterministic results as evidence it cannot override. | §6.4, §3.1 | WI-2, WI-10, WI-17 | U: `JudgeVerdictTest`; E: E1 judge per harness |
| R-C8 | Every test checks the core-task requirement, which CAT adds with CAT as its source. | §6.1, D24 | WI-17 | U: `ProposalTest` |
| R-C9 | Deterministic checks: a closed set of kinds with parameters that may name variation values; each check is validated against its known-good and known-bad controls at confirmation and at load; a failing check is discarded and its requirement judged semantically, with the reason in the proposal. A trial passes when every check passes and the judge passes every semantic requirement; no judge session runs when a check failed or no semantic requirement remains; a failed check's record reaches the author. | §6.1, §6.4, D23 | WI-17 | U: `DeterministicCheckTest` (each kind, each control on both sides), `DriverTest`; E: E7 |
| R-C10 | Trial profiles: the inventory per harness with its source; bindings from Claude skill and subagent frontmatter and Codex agent TOML only, partial bindings fixing one dimension, `inherit` binding nothing; the profile set per category and the resulting cells; a user restriction narrows the set; the proposal lists inventory, bindings, cells and their minimum session count; confirmation freezes the set. | §6.6, §2.1, D22 | WI-16 | U: `ProfileSetTest`, `ProposalTest`, per-harness inventory and binding tests over recorded catalog output; E: E1 proposal per harness |
| R-C11 | Cells are the statistical unit: every §2 and §3 rule that names a category applies per cell, and `K` counts the cells of the certification scope. | §2.1 | WI-16, WI-32 | U: `SchedulerTest`, `ReadinessGateTest`, `CommitTest` (a two-profile category gives two cells with separate counts, gates and decisions) |
| R-C12 | A turn's reply is every assistant message of the turn; deterministic checks of reply text, the judge's evidence and the author's failure records all use it. | §1.2, §6.4, D29 | WI-22 | U: per-harness adapter tests over recorded events, `DeterministicCheckTest`, `JudgeVerdictTest`, `DriverTest`; E: E1 per harness |
| R-C13 | A proposal lists every conflict between requirements with its evidence, and `confirm` is refused while one is listed. | §6.1, D31 | WI-24 | U: `ProposalTest`, `OptimizerCoreTest` |
| R-C14 | Each proposal conflict offers at least two resolutions and a recommended one with its reason; the agent asks the user to resolve every conflict before offering confirmation. | §6.1, §8, D31 | WI-25 | U: `ProposalTest`; E: E1 per harness |
| R-C15 | Certification scope: CAT refuses to confirm until the user chooses one of weakest cell only or latest model versions (every cell of each family's latest version), or a set of inventory cells mapped from the user's own description and passed to `revise`; there is no default; CAT refuses a described cell outside the inventory; the choice is frozen with the contract, a change is a revision, and formal and baseline trials run in the scope's cells only. | §6.1, §6.6, D42 | WI-32 | U: `OptimizerCoreTest` (confirm without a scope and with each choice), `ProposalTest` (a described scope, a cell outside the inventory), `SchedulerTest` (formal cells per scope), `ContractStoreTest` (round trip) |
| R-C16 | Test templates: at confirmation CAT freezes a random salt and assigns each instance to the development or held-out part by a salted hash with probability ½; it refuses a proposal with a test definition of fewer than 2 distinct instances in either part. | §6.1, §6.3, D43 | WI-33 | U: `GeneratorTest` (split stable under the salt, near ½ over many instances, each part drawn only by its streams), `ProposalTest` (refusal at 1 instance in a part) |
| R-C6 | The four scope conditions produce their `certification unavailable` outcomes, before or after a commit. | §6.5 | WI-3, WI-10 | U: `CampaignTransitionsTest`; E: unsupported cases |
| R-C7 | Loading is observed (premise P9) or derived from documented rules, with the limit recorded. | §6.1 | WI-10 | E: E1 loading record per harness |
| R-L1 | One resumable campaign per root and target; binding to the starting harness; refusal from the other harness without any state change. | §7 | WI-3, WI-13 | U: `CampaignRegistryTest`; E: cross-harness |
| R-L2 | Revision creates a new lineage; the incumbent is measured again as a new baseline; allowance continues. | §7 | WI-3 | U: `CampaignTransitionsTest` |
| R-L3 | Revision during formal testing waits for a safe checkpoint; a stopped attempt never resumes. | §7 | WI-3, WI-9 | U: `CampaignTransitionsTest`, `DriverTest` |
| R-L4 | Cancel and resume semantics; a new campaign gets a fresh budget. | §7 | WI-3 | U: `CampaignTransitionsTest` |
| R-L5 | Every listed state distinction is persisted and survives reload. | §7 | WI-4 | U: `FileCampaignStoreTest` |

### 3.7 Reporting, interface and testability

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-P1 | Four outputs in both phases, with unavailable values explained, including the per-category comparison state at `β_i / K`. Allocation counts are shown apart from benchmark weights, and a pooled adaptive pass fraction is never presented as the future pass rate. | §2.9 | WI-12 | U: `StatusReportTest` |
| R-P2 | Descriptive pass rates by concurrency level; detected dependence gives `invalid statistical assumptions`. | §2.6 | WI-12, WI-3 | U: `AssumptionCheckTest` |
| R-P3 | The outcome set and its labels match §1 exactly, including `improved, not certified`. | §1 | WI-12 | U: `StatusReportTest` |
| R-P4 | Claim wording names the frozen judge, benchmark, held-out part and conditions, and lists limitations; a weakest-only claim names each category's profile and says no other profile was certified. | §2.1, §5, §6.6 | WI-12, WI-32 | U: `StatusReportTest` |
| R-I1 | Skill wrappers in both harnesses share one body and follow the §8 loop. | §8 | WI-14 | E: both harnesses |
| R-I2 | Launcher commands and the JSON status object. | §8 | WI-13 | U: `CommandsTest` |
| R-I3 | The old skill is retired; `failure-regression-promotion.md` follows D13; `default-rules.md` routing is updated. Recorded regression scenarios live in the location `design.md` §8 names for their guidance (`.cat/tests/` for project and user guidance, the module's `src/test/resources/` for guidance packaged as a module resource), and CAT refuses a proposal that omits a scenario of one of its prompt sources. | §6.1, §8, §13 | WI-14, WI-21, WI-23 | U: `RegressionScenariosTest` (location audit), the proposal refusal test; reference audit |
| R-I4 | `prompt-optimizer` launchers are registered for both harnesses in `DistributionMain`. | §8 | WI-13 | U: distribution tests; E: installed launcher runs |
| R-I5 | Agents locate CAT's installed files without shell variables the harness does not define: the session hooks render the plugin root into the injected skill-environment rule, and Java derives the harness home from `CODEX_HOME` or `CLAUDE_CONFIG_DIR` when set, otherwise from `HOME`. | §8 | WI-15 finding | U: session-start hook tests, `CodexGetHistoryTest`, `ClaudeGetHistoryTest`; E: every E-scenario's events show no exit-127 launcher call |
| R-T1 | The testability contract T1–T7 holds. | §10 | WI-1 | U: `TestabilityAuditTest` (source scan of the optimizer and capacity code: no non-`final` static fields, no `Thread.sleep`, no reads of `HOME`, `user.home` or `System.getenv` outside `main` classes and harness production scopes, only `final` fields in test classes); review audit at each work item's exit |
| R-T2 | Packages, ports and adapters follow §9: the harness-neutral core imports no harness package, and production sources import no test-support type. | §9 | WI-1 | U: `TestabilityAuditTest` (import scan) |

## 4. Premise gates (WI-0)

Run these before writing the code that depends on them. Use the existing runners and the probe method behind
`design.md` [§11](design.md#11-harness-evidence): a script that starts a plain, a `setsid` and a `nohup` child and
records heartbeats and signals, and that is driven through a real interactive host where ESC matters. Record each
result in `design.md` §11 as a new H-row with its date and versions. If a gate fails, apply its fallback from
`design.md` §12 and update `design.md` §3.7 or §3.8 as needed.

| Gate | Probe | Blocks |
|---|---|---|
| P1 | An interactive Codex host runs a command that tails its own rollout; press ESC; measure the time from ESC to `turn_aborted` being readable. | WI-8 Codex interrupt signal, WI-9 |
| P2, P3 | An interactive Claude host with the sandbox disabled runs the probe; press ESC; check child survival and the transcript interrupt record. | WI-8 Claude interrupt signal |
| P4, P5 | Read the runner event archives of one nested session per harness, searching for rate-limit utilization, reset times and usage. | WI-8 event parsing, WI-3 throttle inputs |
| P6 | Inspect each harness's credential sources in the runner's isolated home, for both a subscription login and an API key. | WI-8 classification |
| P7 | A nested trial delegates to a child, which delegates again, and two children use separate worktrees; the probe interrupts and restarts. | R-E14 |
| P8 | Kill a driver-like parent during a nested session and check for live harness processes. | WI-9 reconciliation |
| P9 | Search the harness events for prompt-source loading records in each harness. | WI-10 loading |
| P10 | A Codex host command runs past 10 minutes; record the timeout behaviour. | WI-13 slice default for Codex |
| P11 | Run 20 judge sessions per harness on fixed evidence; record the rate of schema-valid output. | WI-10 judge profile |
| P14 | Run the E1 deficient fixture (`optimizer/e2e/deficient`) through plain `codex exec` on one Codex model, `gpt-6.1-sol`, at efforts `low`, `medium`, `high` and `xhigh`, 20 trials each (80 trials, about 7M tokens, under 1% of a week). Score each reply against the fixture's motto requirement and its question. H0: the four efforts have the same compliance rate. Test it with the 2×4 Fisher–Freeman–Halton exact test, two-sided, α = 0.05, fixed before the run (not lowest against highest observed, which picks the comparison after seeing the data). With 20 trials per effort only differences of roughly 40 percentage points or more are reliably detectable. If H0 is rejected, effort stays in the tests. A non-rejection is not evidence that the rates are equal; supporting fewer efforts would need an equivalence test with a stated margin and far more trials. If every effort is at 0% or 100%, the result is inconclusive (floor or ceiling), not "the same": rerun on the pilot's fixed variant before concluding (user request, 2026-10-01). | The profile-set default of `design.md` §6.6 |
| P15 | After the first real prompt file is optimized (E1's file or `write-clearly.md`), develop a fresh campaign on it with the profile set restricted to one cell: the weakest model, chosen as the lowest incumbent-baseline pass rate at each model's lowest effort, at its lowest effort (the §6.6 user control), until readiness. Then freeze that candidate and run it, with no edits, in each benchmark cell: every other model's lowest and highest effort, and the weakest model's highest effort (the §6.6 active-set pattern; 15 cells with the Codex inventory). Score with the same contract checks and judge. Pre-stated test: for each benchmark cell, H0: its pass rate is below 95%, one-sided exact binomial, α = 0.05 split equally over the benchmark cells (with 15 cells, 112 passing trials and no failure reject H0 in a cell; about 1,700 trials, roughly 140M tokens at 85k per trial). Report the effort result (same model, higher effort) and the model result (stronger model) separately. A shortcut holds for that file only if H0 is rejected in every cell it covers and those cells show no failure kind absent in the development cell. Several files must agree before development policy changes (user requests, 2026-10-01). Development reaches the `design.md` §2.7 readiness gate before the freeze, also when the trials run through a harness outside the optimizer. If the optimizer cannot run a frozen candidate without editing it, record that as a missing capability and run the trials through the harness with the same checks. | None: development samples only each category's weakest cell (`design.md` §6.6, user ruling, 2026-10-04); the result informs the certification scope the user chooses |

Exit check: every gate has a recorded result (pass, or the fallback applied) and `design.md` §11 and §12 are updated.

Results (2026-10-01; facts in `design.md` §11 H11–H23, results in `design.md` §12):

- P1, P2, P3, P4, P5, P10: passed. P10 reached no limit in 662 seconds, so the over-limit behaviour is unobserved.
- P6: passed on the documented credential fields; only Claude subscription and Codex API-key logins were available.
- P7: partly passed. Completion and parallelism are observable once the runner fixes in WI-8 land; no Claude grandchild
  was observed. Fallback applied: Codex trials cannot create child worktrees (H18), recorded as a limit in
  `design.md` §3.2.
- P8: passed for Claude; failed for Codex. Fallback applied: reconciliation terminates recorded processes (WI-9).
- P9: failed for instruction files. Fallback applied: documented derivation (WI-10).
- P11: passed with the extraction rule; Claude fenced every judge response, Codex returned bare JSON.

Cleanup: the probes create their directories with `workflow-temp create` and delete them once their facts are
recorded.

## 5. Work items

Each work item lists its scope, the requirements it closes, its tests and its exit check. "Exit" always also includes
the unrestricted build and a review against the testability contract.

### WI-1 — Statistics core (`optimizer.statistics`)

The riskiest pure code, built first.

- **`BettingProcess`:** an immutable value for one side (`ABOVE` or `BELOW`) with its bets and weights. It provides
  exact `exceeds(S, F, threshold)` using `BigInteger` rationals, with a log-domain shortcut whose safety margin is
  proven in its Javadoc, and `logValue(S, F)` for display.
- **`DecisionBoundaries`:** precomputes, for each `F` up to the cap, the minimum certifying `S` and the maximum
  clearly-below `S` at `α_i`, and for each category the minimum proven-better `S` and the maximum proven-worse `S` at
  `K/β_i` against that category's frozen `UI(k)` and `LI(k)`. It is computed once per attempt at commit and persisted
  with the attempt, so later decisions are lookups.
- **`CapCalibration`:** the absorbing-state calculation over `(S, F)`. Certification caps use a 99% planning rate and
  90% power. It is pure, and its result is persisted.
- **Other contents:** `ErrorBudget`, `ConfidenceBounds` (inversion by bisection), `ExploratoryBounds` (Wilson,
  Clopper–Pearson) and `Comparison` (difference bounds).
- **Build:** configure `client/common/cli` to publish its test JAR, which later work items' test builders and fakes
  use.
- **Closes:** R-S1–R-S8, R-T1 and R-T2. `TestabilityAuditTest` is created here and scans every optimizer package
  as later work items add them; every work item's exit runs it.
- **Tests:** `CapCalibrationTest` checks the `design.md` §2.4 table and is the only test that runs calibration. Every
  other test injects small handwritten `DecisionBoundaries`, or uses the `i = 1` table through a test fixture that
  builds it lazily once per JVM in an immutable holder. That holder is safe under parallel methods: it is computed once
  and never mutated.
- **Exit:** the focused tests pass. Each test's Javadoc tabulates the `design.md` §2.4, §2.5 and §13 numbers that it
  checks. An independent script computed them during design, with exact rational decision
  boundaries and a double-precision probability sum. A disagreement is a defect in one of the two: investigate it, and
  correct `design.md` first if the design value is wrong.

### WI-2 — Contract model and generators (`optimizer.contract`)

- **Contents:**
  - immutable `Contract`, `Category`, `TestDefinition` (turn sequence, time limit, applicability, expected behaviour),
    `VariationRule`, `JudgeProfile`, `RiskRank` and `TrialFixtures`;
  - the proposal JSON schema and validation of deriver output;
  - `Generator`, which validates and draws instances from a `DrawStream`;
  - `DrawStream`, a seeded `RandomGenerator` with a persisted seed and position, kept separately for development,
    formal and baseline draws.
- **Closes:** R-C3, R-C4, part of R-C1 (schema) and part of R-C5 (profile).
- **Tests:** `GeneratorTest` covers out-of-contract emission, a probability chi-square check with a fixed seed, and
  repeated instances. `DrawStreamTest` covers resume at the same position and independence from outcomes.
  `DistributionTest` covers balanced weights.
- **Exit:** the focused tests pass.

### WI-3 — Campaign model and policies (`optimizer.campaign`)

- **Contents:**
  - immutable `Campaign`, `Lineage`, `Candidate`, `CertificationAttempt`, `CategoryCounts (S, F, batches)`,
    `LogicalTrial` and `PendingDecision`;
  - `CampaignTransitions`, a pure function `(state, event) → state`, with events for confirmation, assignment,
    outcome, promotion, commit, decision, user choice (including keep improvement),
    revision, cancellation, measurement change and unowned change;
  - `ReadinessGate`, `EditOrContinue`, `Promotion`, `Plateau`, `ReviewCheckpoint`, `Baseline`, `DecisionTable`
    (the `design.md` §2.8 actions), `KeepImprovement`, `Applicability` and `Comparability`;
  - `Scheduler`, a pure admission plan: given the state, the clock reading, the throttle state and free capacity, it
    returns the next admissions, each with a category, purpose and snapshot;
  - `ConcurrencyPolicy`, `Throttle` and the dependence check (`AssumptionCheck`);
  - `CampaignRegistry`, which decides campaign uniqueness and harness binding over a list of campaign headers;
  - the port interfaces `SessionPort`, `WorkspacePort` and `HostInterruptSignal` (`design.md` §9), with their fakes in
    test sources. WI-7 and WI-8 implement them, and WI-9 drives the fakes.
- **Test support** (test sources): `CampaignBuilder`, so a test can write, for example,
  `certificationAttempt(i).category("k", S, F)`.
- **Closes:** R-S9, R-D1–R-D7, R-F1, R-F2, R-F4, R-F5, the policy side of R-F3 and R-F6, R-E3–R-E6, R-M1, R-M2,
  R-M4, the policy side of R-M5, R-C6, R-L1–R-L4 and part of R-P2.
- **Tests:** the U proofs named in §3. Each covers both sides of every boundary, with builders only and no I/O.
- **Exit:** the focused tests pass in under one second each.

### WI-4 — Campaign store (`optimizer.store`)

- **Contents:** the `CampaignStore` port, with operations for load snapshot, write assignment, write outcome, replace
  snapshot, list unadopted outcomes and list campaign headers, and `FileCampaignStore`. The file store writes
  atomically (temporary file, `fsync`, rename) and exposes an injected `WriteStep` hook for fault injection.
- **Contents in test sources:** `InMemoryCampaignStore`, and the abstract `CampaignStoreContract` that both
  implementations extend.
- **Closes:** R-E1 (persistence), R-W1 (layout and snapshot-only loading), R-L5, and the persistence side of R-S9.
- **Tests:** `FileCampaignStoreTest` covers a fault after every write step, a reload after each fault, and the
  invariant that no outcome is duplicated or lost. It also checks that loading reads only `campaign.json`, by
  replacing the trial records with unreadable files and still loading.
- **Exit:** the focused tests pass.

### WI-5 — Shared harness capacity (`common.runner`)

- **Contents:** replace the static `HarnessConcurrencyLimit` with an instance type `HarnessCapacity`:
  - `of(Path lockDirectory, int maximum, Pause pause)`, `unlimited()`, `acquire()`, `tryAcquire()` and
    `Permit.close()`;
  - `main` in `ClaudeRunnerCli` and `CodexRunnerCli` resolves `$HOME/.cat/locks/harness-capacity/<harness>` and
    passes the instance in;
  - `CodexRunnerCli` starts acquiring capacity;
  - `--max-concurrency` keeps its range of 1–16.
  Follow `contract-retirement.md` for the removed static API.
- **Closes:** R-E8.
- **Tests:** `HarnessCapacityTest` uses a test-owned directory and a manual pause. It covers acquisition up to the
  maximum, `tryAcquire` failing at the maximum, release, and two instances on the same directory sharing slots. E8
  shows two CAT processes bounded by the shared capacity.
- **Exit:** the focused and runner tests pass; `grep` finds no `HarnessConcurrencyLimit` references.

### WI-6 — Inventory, authority and delivery planning (`optimizer.inventory`)

- **Pure contents:**
  - `FileInventory` (path, kind, bytes hash, mode, link target), `Overlay` and `Authority`;
  - `InventoryDiff`, which classifies forbidden operations;
  - `DeliveryPlan`, a three-way comparison of the fork baseline, the current base and external state with the
    candidate, producing conflicts or an ordered change list.
- **I/O contents:** `InventoryScanner`, which scans without following links. This is its only I/O.
- **Closes:** R-W3, R-W4, R-W7 (diff), R-W8 (plan).
- **Tests:** `AuthorityTest`, `InventoryDiffTest`, `OverlayTest` and `DeliveryPlanTest`, all in memory.
  `InventoryScannerTest` uses one temporary tree containing links, modes and ignored files.
- **Exit:** the focused tests pass.

### WI-7 — Workspace I/O (`optimizer.workspace`)

- **Contents:**
  - `Fork`: clean check, recording, participant copies, the `info/exclude` check, and the detached worktree, all
    through `Git`;
  - `StagedSources`: build and delete staged sources, dereference links, report cycles and missing targets, exclude
    `.cat/work/`;
  - `AuthoringCopies`: prepare the author's source and capture its delta through `InventoryScanner`;
  - `LinkReport`.
  Together they implement WI-3's `WorkspacePort`.
- **Closes:** R-W1 (exclude), R-W2, R-W5, R-W6.
- **Tests:** `ForkTest` runs each Git operation once in a test-owned repository: a dirty-base refusal for each change
  kind, the exclude added only when needed, and the worktree at `HEAD`. `StagedSourceTest` covers dereferencing,
  exclusion and fixtures. `LinkReportTest` covers the report. A unit test shows that the runner's project copy
  accepts a staged source built from a project with links, in both harnesses.
- **Exit:** the focused tests pass.

### WI-8 — Session adapters and host signals (harness modules)

- **Contents:**
  - `ClaudeSessions` and `CodexSessions` implement `SessionPort` over `ClaudeRunner` and `CodexRunner`. They acquire one
    `HarnessCapacity` permit per session, pass evidence read-only to judges, collect transcripts and effects, and parse
    harness events for the observed model, usage and rate limits. They classify outcomes as completed, interrupted at
    CAT's request (H10) or infrastructure failure.
  - Credential classification for each harness.
  - `RolloutInterruptSignal` (Codex) and `TranscriptInterruptSignal` (Claude) implement `HostInterruptSignal` from an
    injected path and clock.
  - Shared `SessionPortContract` tests run against the fake and against each adapter fed recorded harness events.
  - Runner fixes the adapters depend on (`design.md` §11 H17, H19):
    - the Claude runner ends a turn only when the root reply has arrived and `background_tasks_changed` lists no task,
      relaying `task_notification` records and the follow-up reply in the same turn;
    - the Codex runner implements mid-turn interruption with `turn/interrupt`, and fills `assistant_response` from the
      last completed `agentMessage` item;
    - both runners treat an interrupt that arrives after the turn ended as a no-op instead of failing.
- **Closes:** R-E6 (parsing), R-E7 (classification), R-E10 (signals), R-E13 (transport), R-E14, R-E15, R-E16, and part
  of R-M1.
- **Allowlist note:** the runner fixes above write the runner sources under `client/claude/cli`, `client/codex/cli` and
  `client/common/cli`.
- **Tests:** event-parsing tests feed recorded harness event lines captured during WI-0 into the parser as in-memory
  input.
  Signal tests use a temporary rollout or transcript file that the test appends to under a manual clock. WI-15's
  end-to-end runs exercise each operation in both harnesses.
- **Exit:** unit tests pass.

### WI-9 — Driver (`optimizer.execution`)

- **`Driver.step`:** a deterministic loop body. It reads the state through `CampaignStore`, asks `Scheduler` for
  admissions, writes assignments, starts sessions through `SessionPort`, and consumes completion events from a queue.
  Then it writes outcomes, starts judges, adopts outcomes in order, applies `CampaignTransitions`, and checks
  `HostInterruptSignal` and the slice deadline.
- **`SliceRunner`:** the only class that blocks on real time. It waits on the event queue until the next deadline and
  is covered by one focused threaded test.
- **Also:** reconciliation (including terminating the recorded `ownerPid` and `harnessPid` of runs left by an earlier
  driver, `design.md` §3.8 and H20), the campaign write lock (an injected path; a second driver gets `busy`), the slice
  cutoff and grace period, the pauses for measurement change and unowned change, the edit-or-continue pause, and the
  safe-checkpoint handling for revision.
- **Closes:** R-D2, R-D3 (driver side), R-D9, the driver side of R-F6, R-E1, R-E2, R-E5, R-E9, R-E10, R-E11, R-M3,
  the driver side of R-M5, R-C2, R-L3 and R-W7 (checkpoints).
- **Tests:**
  - `DriverTest` uses `InMemoryCampaignStore`, a fake `SessionPort` whose completions the test releases in any order,
    a fake `WorkspacePort` and a manual clock. Its scenarios are listed in [§7](#7-handoffs-and-use-case-traces).
  - `ReconciliationTest` starts from builder states that represent each crash point.
  - `SliceTest` covers cutoff and grace using the manual clock.
  - No test sleeps.
- **Exit:** the focused tests pass, and the `DriverTest` suite stays under 10 seconds in total.

### WI-10 — Contract derivation, judging and authoring prompts

- **Prompts:** the deriver prompt, the judge prompt and the author prompt. Each states its role's authority limits
  from `design.md` §3.1 and §4.3 and its output schema.
- **Driver handling:** proposal rendering, feedback regeneration and confirmation; loading observation or documented
  derivation (P9: hooks and Claude's skill inventory are observed, instruction files are derived, H21); the judge and
  deriver output extraction rule of `design.md` §3.1 (a bare or singly fenced JSON object, H23); the authentication
  and link warnings in the proposal; and the judge profile freeze.
- **Closes:** R-C1, R-C2 (rendering), R-C5, R-C6 (detection in proposals), R-C7, R-E7 (warning), R-E13 (schemas).
- **Tests:** `ProposalTest` uses fixture deriver outputs, valid and invalid, to check every required item and the
  rejection of aggregate-only summaries. E1 runs the deriver, judge and author in both harnesses.
- **Exit:** unit tests pass.

### WI-11 — Delivery executor (`optimizer.delivery`)

- **Contents:** executes a `DeliveryPlan` with a journal through `CampaignStore`, validating each preimage and
  resuming each change at most once. Reports the result.
- **Closes:** R-W8 (execution), and the delivery side of R-F3 and R-F6.
- **Tests:** `DeliveryTest` uses a temporary base and an external directory and covers:
  - a disjoint base edit surviving;
  - a same-file conflict stopping before any write;
  - an injected fault between writes followed by resume;
  - a concurrent edit detected before its write;
  - a historical result after a base-only prompt edit;
  - a kept improvement older than the working candidate, materialized in the optimization worktree and then delivered;
  - no commit created in the base worktree.
  It uses real Git only for the final diff report and the no-commit check.
- **Exit:** the focused tests pass.

### WI-12 — Reporting (`optimizer.report`)

- **Contents:** `StatusReport`, the JSON status plus the human summary. It includes the four outputs, labels, outcome
  names, claim wording, limitations, minimum remaining trials, token statistics, the descriptive check by concurrency
  level, and the keep-improvement choice with its evidence and `UI(k)` values.
- **Closes:** R-D8, R-E12, R-P1–R-P4, the report side of R-F6, and part of R-W6.
- **Tests:** `StatusReportTest`, `RemainingWorkTest` (hand-computed cases in both phases) and `AssumptionCheckTest`,
  all built from builder states.
- **Exit:** the focused tests pass.

### WI-13 — Commands, wiring and distribution

- **Contents:**
  - `optimizer.cli.Commands` parses `start`, `run`, `status`, `confirm`, `revise`, `choose` and `cancel` and calls the
    core;
  - `ClaudePromptOptimizer.main` and `CodexPromptOptimizer.main` resolve `HOME`, the host session environment
    variables, the capacity, the clock and the store, then wire the adapters;
  - `prompt-optimizer` launchers for both harnesses in `DistributionMain`;
  - the slice defaults chosen from P10.
- **Closes:** R-I2, R-I4, and part of R-L1 (cross-harness refusal at the command boundary).
- **Tests:** `CommandsTest` calls the parse-and-dispatch operation in process with a fake core and covers each
  command, the output schema, and invalid input rejected once at ingress. Distribution tests confirm the launchers
  exist by running them.
- **Exit:** the unit tests pass, and the built artifacts' launchers print the status JSON for `status` on a fixture
  campaign.

### WI-14 — Skill replacement and rule updates

- **Skill:** replace the `skills/prompt-optimizer/first-use.md` body with the `design.md` §8 loop, and update both
  `SKILL.md` wrappers, including their descriptions and allowed tools.
- **Rules:** apply D13 to `failure-regression-promotion.md` and `default-rules.md`, keeping the route's rule that a
  subagent reports a confirmed failure to the agent that delegated its work instead of correcting the guidance.
  Update the test resources that cite the old skill (`check-assumptions/existing-route-before-new-wrapper/scenario.md`,
  `limitations-and-workarounds/verification.md`) and `InvocationDirectoryTest`'s directory label if it no longer
  describes a real consumer. Follow `contract-retirement.md`.
- **Native tier retirement:** delete the Java tests that start a runner, a harness process or a model
  (`ClaudeRunnerCliNativeTest`, `ClaudeSessionsNativeTest`, `CodexRunnerCliNativeTest`, `CodexSessionsNativeTest`);
  make `StagedSourceNativeTest` a unit test over both harnesses; remove the `native` Maven profile, the
  `cat.test.groups`/`cat.test.excludedGroups` native defaults and `cat.native.harness`. Keep the recorded-event
  parsing tests. Before deleting the session tests, run `SessionPortContract` against the real `ClaudeSessions` and
  `CodexSessions` over `FakeHarnessCli` replaying recorded events (`design.md` §10 T3), so the fake stays
  checked against the real adapters. Each recording names the harness version that produced it. List each deleted
  assertion with the WI-15 scenario that covers it. Follow `contract-retirement.md`.
  The existing recordings cannot satisfy every contract check (they are trimmed, and none records an interrupted
  turn), so the session contract tests, the deletion of `ClaudeSessionsNativeTest` and `CodexSessionsNativeTest` and
  the removal of the `native` profile and its properties move to WI-15's **Contract tests** step.
- **Closes:** R-I1 (content), R-I3.
- **Tests:** existing rule tests pass. A reference audit (`grep -rn "prompt-optimizer"`) shows only references to the
  new skill.
- **Exit:** the audit is clean, no Java test starts a runner, a harness process or a model, and the build passes.

### WI-16 — Trial profiles and cells (user ruling, 2026-10-01)

- **Contract:** `optimizer.contract` gains the profile inventory with its source, bindings (file, model, effort, each
  optional) and each category's profile set; cells are (category, profile). `ProposalSchema` and the contract hash
  include them.
- **Inventory:** a `ProfileInventory` port. Codex reads `codex debug models` and keeps the models with visibility
  `list` and their efforts except `ultra`; Claude reads a plugin resource listing documented aliases and efforts (P12). Each is
  tested over recorded output.
- **Bindings:** read only from Claude skill and subagent frontmatter (`model`, `effort`) and Codex agent TOML
  (`model`, `model_reasoning_effort`). `inherit` binds nothing; a partial binding fixes one dimension. The
  core-task requirement is excluded from the profile-set computation.
- **Statistics:** every rule in `optimizer.campaign` and `optimizer.statistics` that is per category becomes per cell;
  `K` counts cells. Readiness, baseline, commit, error budget, decisions, keep-improvement, the scheduler and the
  report are keyed by cell.
- **Sessions:** `SessionPort` takes the profile. `ClaudeSessions` passes `--model` and `--effort`; `CodexSessions`
  passes the model and reasoning effort to the bridge. The deriver, judge and author run under the role profile
  (the `optimize-xhigh` binding per harness); `OptimizerCommandScope.JUDGE_MODEL = "harness-default"` is replaced.
  The observed profile is recorded per trial and compared per cell (R-E16, D21).
- **User control:** a revision may restrict the profile set; the proposal shows inventory, bindings, cells and the
  minimum session count.
- **Closes:** R-C10, R-C11, R-E17.
- **Tests:** `ProfileSetTest` (bound, partially bound and unbound files; `inherit`; a restriction), `ProposalTest`,
  `SchedulerTest`, `ReadinessGateTest`, `CommitTest`, `DecisionTableTest` and `StatusReportTest` with a two-cell
  category, both `*SessionsTest` classes for profile arguments, `MeasurementIdentityTest`. `DriverTest` trace 15.
- **Exit:** unit tests pass; the full build passes.

### WI-17 — Deterministic checks and the core-task requirement (user ruling, 2026-10-01)

- **Contract:** each requirement is deterministic or semantic. A deterministic check has a kind from a closed set,
  belongs to one test and one requirement, takes parameters that may name variation values, and carries a known-good
  and a known-bad control. CAT adds the core-task requirement (source CAT) to every test; it is semantic unless the
  test's expected result is mechanical.
- **Controls:** validated at confirmation and when the contract is loaded. A check that fails a control is discarded,
  its requirement becomes semantic, and the proposal says why.
- **Trial outcome:** checks run first on the trial evidence. A trial fails if any check fails; otherwise the judge
  decides the semantic requirements alone, under the role profile, and cannot override a check. No judge session runs
  when a check failed or no semantic requirement remains. A failed check's record reaches the author brief.
- **Prompts:** the deriver prompt marks each requirement and proposes checks with controls; the judge prompt receives
  only semantic requirements and the deterministic results as evidence.
- **Closes:** R-C5, R-C8, R-C9.
- **Tests:** `DeterministicCheckTest` (each kind; each control on both sides; variation values), `ProposalTest`
  (core-task requirement added; a check with a failing control discarded), `JudgeVerdictTest`, `DriverTest` trace 16.
- **Exit:** unit tests pass; the full build passes.

### WI-17a — Tool calls in session results (follow-up to WI-17, 2026-10-01)

- **Contract:** a completed session result lists the trial's tool calls in the order the harness made them: each call's
  tool name, its command when the tool runs one, and the command's exit status when the harness reports one. A field the
  harness does not report stays absent. Claude reports a status only in the form of its result (`design.md` H30), so
  its adapter reads that recorded form and leaves the status absent for any other. Trial evidence passes the list to the `tool_used`
  and `exit_status` checks, which then decide on real trials; a check stays undecidable only when the harness reports
  nothing it needs.
- **Adapters:** the Claude adapter reads tool calls from stream-json events and the Codex adapter from app-server
  events. Each adapter's parsing tests use output recorded from the real harness, and the recording's source and version
  are documented.
- **Tests:** each adapter's event tests (a tool call with and without an exit status; a failed command; for Claude, an
  exit code of 1 that Claude interprets as success, a background command and a denied command), a `TrialEvidence`
  test, `DriverTest`, where a trial's `tool_used` and `exit_status` checks are decided, and `DeterministicCheckTest` cases
  where a missing status makes the check undecidable, in a trial and in a check's control.
- **Exit:** unit tests pass; the full build passes.

### WI-18 — Author manifest, minimality pass and size report (user ruling, 2026-10-01)

- **Author prompt:** classify each failure as routing, guidance or not guidance; make the smallest evidence-backed
  correction at the owning boundary, preferring to remove a contradiction over adding emphasis; generalize against
  analogous and dissimilar cases; emit the manifest.
- **Manifest:** extends the author report (`optimizer/author/report.schema.json`) with each failure's classification
  and, per changed hunk, the cited failing instances and the analogous and dissimilar cases. CAT rejects an attempt
  whose manifest omits a hunk or cites an unknown instance; the attempt is retained like an inventory violation. An
  attempt that edits nothing produces no candidate; its classifications go to the round's diagnosis and to the
  review-checkpoint or plateau report.
- **Minimality pass:** after the promotion runs pass, delta debugging over the attempt's hunks: start with two groups,
  revert one group, rerun the same promotion runs, keep the reduction if it passes the reduction rule (`design.md` §2.7), otherwise
  split finer down to single hunks. Its runs are targeted and never counted. The minimal attempt is promoted, and the
  report names the removed hunks.
- **Size report:** the status shows the working candidate's and the incumbent's prompt size (bytes and words) and the
  median input tokens per trial in each cell where both have outcomes.
- **Closes:** R-D10, R-D11, R-E18.
- **Tests:** `AuthorManifestTest`, `MinimalityTest`, `StatusReportTest`, `DriverTest` traces 17 and 18.
- **Exit:** unit tests pass; the full build passes.

### WI-19 — Development cell tiering (user ruling, 2026-10-01)

- **Scheduler:** the `design.md` §3.3 rule 2 cell tier and the `design.md` §6.6 screen, active set, lowest-effort
  priority and widening, in `optimizer.campaign`. The active set is persisted with the campaign and survives restart.
- **Report:** the status shows each category's active cells and whether development has widened.
- **Replaced:** WI-32 replaces this tiering (user ruling, 2026-10-04).
- **Tests:** `SchedulerTest`, `StatusReportTest`, `DriverTest` trace 19.
- **Exit:** unit tests pass; the full build passes.

### WI-20 — Concurrent attempts while the candidate is weak (user ruling, 2026-10-01)

- **Policy:** the `design.md` §2.7 weak test, the 3 concurrent attempts, winner selection and its tie-breaks, in
  `optimizer.campaign`. The driver starts the attempts concurrently within the shared harness capacity and runs each
  attempt's promotion runs; only the winner enters the minimality pass. A round that is not weak keeps one attempt at a
  time.
- **Report:** the status lists each attempt of a round with its promotion results and whether it won.
- **Closes:** R-D12.
- **Tests:** `AttemptSelectionTest`, `StatusReportTest`, `DriverTest` trace 20.
- **Exit:** unit tests pass; the full build passes.

### WI-21 — Regression scenarios (user rulings, 2026-10-02)

- **Location:** a scenario for `<root>/.cat/rules/<path>.md` (`<root>` the project or `${HOME}`) lives in
  `<root>/.cat/tests/<path>/`; one for other guidance in the project lives in
  `<project>/.cat/tests/<path relative to the project, without .md>/` (`design.md` §8). One Markdown file per scenario
  holds the prewritten turns and the expected behaviour.
- **Proposal:** derivation finds the recorded scenarios of every prompt source, gives them to the deriver, and refuses
  a proposal in which no test cites one of them, naming each missing scenario (`design.md` §6.1).
- **Rule:** deliver the approved `failure-regression-promotion.md` text.
- **Migration:** move every scenario under `client/common/rules/src/test/resources/` to the location above, with the
  fixtures it uses; retire the 16 `suite.json` files and any note that describes running the retired runner. Follow
  `contract-retirement.md`.
- **Closes:** R-I3 (with WI-14).
- **Tests:** a proposal refusal test, a test that a proposal citing every scenario is accepted, and
  `RegressionScenariosTest`, which fails when a scenario lies outside `.cat/tests/` or names no existing guidance file.
- **Exit:** unit tests pass; the full build passes; a reference search finds no `suite.json` and no scenario left in the
  rules module.

### WI-28 — Judge evidence section (user ruling, 2026-10-02)

- **Renderer:** one evidence renderer per harness turns the trial's recorded events (Codex app-server events, Claude
  stream-json) into the `design.md` §6.4 evidence section; file changes come from the effects copy against the staged
  source. The judge prompt gains an `{evidence}` placeholder; the judge reads no log or transcript path. A frozen judge
  prompt that still names `{effects}` and `{transcript}` renders with the values those placeholders had.
- **Closes:** R-E21.
- **Tests:** renderer tests on recorded events of both harnesses (tool call with output and exit status, child activity,
  hook status, no streaming fragments or injected rules); `JudgePromptTest` for the new and the frozen template.
- **Validation:** the new judge reaches the recorded verdict on recorded E1 trials; judge input tokens are compared with
  the H32 measurement.
- **Exit:** unit tests pass; the full build passes.

### WI-29 — Failure impact and fail-fast development (user ruling, 2026-10-03)

- **Triage:** a new role session, run under the role profile without CAT's plugin, receives a failed development
  trial's instance, reply, evidence section, deterministic results and failure record (judge rationale or check
  record) and returns `blocking` or `self-recovering` with a rationale in a strict schema. It runs only for failed
  `DEVELOPMENT` trials; an invalid output reruns it, and a third invalid output records `blocking`.
- **Fail-fast:** adopting a blocking development failure discards the working candidate's unadopted development trials
  (the driver interrupts their sessions), clears any diagnosis and starts a round at once. A self-recovering failure
  keeps the edit-or-continue path.
- **Persistence:** the trial, the completion event and the retained failure carry the impact; the author brief shows
  it. Campaign format 12; formats 9 to 11 stay readable, with no impact on their failures.
- **Closes:** R-D16.
- **Tests:** `EditOrContinueTest` and adoption tests for both impacts, a blocking failure during a self-recovering
  diagnosis, and the discard of unadopted trials; `TriagePromptTest` for the request and the parser; `DriverTest`
  trace of a blocking failure that interrupts the running development trials and starts the authors; store round
  trip and older-format reads.
- **Exit:** unit tests pass; the full build passes.

### WI-30 — Measured trigger-absent cases (user approval, 2026-10-03)

- **Contract:** the proposal schema gives each requirement a trigger, or marks that it always applies, and each test
  lists the triggers it leaves absent. Validation refuses a proposal in which a requirement with a trigger has no such
  test. The deriver brief asks for both.
- **Manifest:** each hunk's dissimilar case is a test identifier of the contract; manifest validation rejects an attempt
  that names one the contract lacks. The author brief lists the contract's tests by identifier.
- **Closes:** R-D17.
- **Tests:** `ProposalTest` for a conditional requirement with and without a trigger-absent test, and one that always
  applies; `AuthorManifestTest` for a known and an unknown dissimilar test.
- **Exit:** unit tests pass; the full build passes.

### WI-31 — Codex models of version 6 or later (user ruling, 2026-10-04)

- **Inventory:** `CodexProfileInventory` keeps a model listed with visibility `list` only when the number after `gpt-`
  in its slug, up to the next `-` or the end, is a version of 6 or later (`gpt-6-luna`, `gpt-6.1-sol`); it leaves out
  `gpt-5.5`, `gpt-5.6-sol` and any slug without that form. When no listed model qualifies, inventory fails with the
  existing diagnostic style, naming the requirement.
- **Bindings:** `ProfileInventory` gains `supportsModel(String model)`: `ClaudeProfileInventory` supports every model,
  and `CodexProfileInventory` supports a model of version 6 or later, by the same slug rule as the inventory.
  `binding(String)` is unchanged. After reading the staged source's bindings and before it starts the deriver session,
  the core checks each bound model; when any is unsupported, it starts no session and reports, as it reports a deriver
  failure, every such file with its model and that the user changes the binding and runs `start` again. A file that
  binds only its effort, or a supported model, is unaffected. Reading the Codex role profile fails, naming the
  installed `optimize-xhigh.toml` and its model, when that model is below version 6.
- **Closes:** R-D18.
- **Tests:** `CodexProfileInventoryTest` over recorded catalog output with `gpt-5.5`, `gpt-5.6-sol`, `gpt-6-luna`,
  `gpt-6.1-sol` and an unversioned slug, and `supportsModel` on each form; `ClaudeProfileInventoryTest`, that every
  model is supported; a core test in which a staged Codex agent TOML binds `gpt-5.6-luna`: no deriver session starts
  and the report names the file and the model, while a binding to `gpt-6-luna` or an effort-only binding derives.
- **Exit:** unit tests pass; the full build passes.

### WI-32 — Development cells and certification scope (user rulings, 2026-10-04)

- **Ranking:** each harness module ships a capability ranking resource: Codex `astra` above `sol` above `luna`
  (developers.openai.com/api/docs/models, read 2026-10-04), Claude `fable` above `opus` above `sonnet` above `haiku`
  (platform.claude.com/docs/en/models/overview, read 2026-10-04), each with its address and reading date. A model's
  family is its name without its version; within a family, an older version ranks below a newer one. Revision feedback
  may correct or extend the ranking, which the contract then holds. An inventory model whose family is unranked is
  named in the proposal, and CAT refuses to confirm until the user ranks it.
- **Weakest cell:** in `optimizer.campaign`, `CellTier` gives way to the `design.md` §6.6 development cells. A
  category's weakest cell is, among its cells in the certification scope, the lowest effort of the least capable model;
  a category with one cell uses it. No trial runs to find it. The 3-trial screen, the active set, the lowest-effort
  priority and the `widened` latch are removed.
- **Development cells:** the weakest cell, plus each cell where a certification attempt of the lineage adopted a
  failure. A development trial's failure no longer adds a cell. `Scheduler` rule 2 assigns only development cells,
  under the coverage floor among them.
  The readiness gate and the §3.9 development minimum sum over development cells; author rounds and the weak test use
  only them.
- **Scope:** the contract holds the certification scope: `WEAKEST_CELL`, `LATEST_VERSIONS`, or a set
  of inventory cells that revision feedback supplies from the user's own description (refused when a cell is outside
  the inventory). `confirm` takes the scope as a required argument; the command, `Core.confirm` and
  `OptimizerCore.confirm` refuse a confirmation without one, naming every choice. `LATEST_VERSIONS` keeps each family's
  highest listed version. Formal trials, the formal rule and `K` cover the scope's cells only. Baseline targets: 50 in
  every scope cell, plus 50 in a scope cell below 95% not yet proven deficient.
- **Proposal and report:** the proposal shows the capability ranking with its source, and for each scope its cells,
  each category's weakest cell and the minimum session count; the confirmation skill presents the three choices with
  the `design.md` §6.6 tradeoffs, asks the user, and for `other` maps the user's words to cells through `revise` and
  shows the result for confirmation. The status shows
  each category's weakest cell (or that it is not yet fixed) and development cells; a weakest-only claim names the
  profile and says no other profile was certified.
- **Format:** the campaign format version increases; the previous format is refused, as format 2 was.
- **Closes:** R-E19, R-E22, R-C15, the development-cell part of R-D1, R-E3 and R-D7, the scope part of R-C1 and R-C11,
  and the scope wording of R-P4.
- **Tests:** `CapabilityRankingTest`, `CellTierTest`, `SchedulerTest`, `BaselineTest`, `ReadinessGateTest`,
  `ProposalTest`, `OptimizerCoreTest`, `StatusReportTest`, the contract and campaign store round trips, `DriverTest`
  trace 19.
- **Exit:** unit tests pass; the full build passes.

### WI-33 — Test templates and held-out parts (user ruling, 2026-10-04)

- **Split:** at confirmation CAT draws a random salt, frozen with the contract. In `optimizer.contract`, an instance's
  part is a hash of its test definition and variation values under the salt, each part with probability ½. Variation
  values are finite lists, so CAT counts each definition's distinct instances per part and refuses the proposal when
  either count is below 2, naming the definition and both counts; the proposal lists the counts.
- **Draws:** `Generator.draw` takes the part from the stream kind: development draws development instances, formal
  and baseline draws held-out instances, each uniform over the definition's instances in its part by the frozen
  probabilities restricted to that part. Targeted runs and promotion runs use development instances only.
- **Evidence:** `TrialAdoption.adoptDevelopment` no longer adds the incumbent's development outcomes to the baseline;
  `adoptBaseline` and formal adoption no longer add failures to the regression set. A formal failure reaches
  development as its cell, test definition and failed requirements: triage, diagnosis and author briefs never receive
  a held-out instance, reply or judge rationale. The user's report may show held-out instances.
- **Deriver:** the deriver prompt asks for variation dimensions on every test, so that each yields at least 2
  distinct instances per part; any wording change to an optimizer prompt is shown to the user before delivery.
- **Format:** the contract stores the salt; the campaign format version increases with WI-32's.
- **Closes:** R-C16, R-D9, the part side of R-C4, R-C1, R-D3 and R-D7.
- **Tests:** `GeneratorTest`, `DrawStreamTest`, `ProposalTest`, `TrialAdoptionTest` (or the adoption cases in
  `DriverTest`), `DriverTest` trace 21, the author brief tests, the contract store round trip.
- **Exit:** unit tests pass; the full build passes.

### WI-27 — Role sessions without the plugin, and the path trigger (user ruling, 2026-10-02)

- **Sessions:** the runner settings decide plugin installation per session role: trials install CAT; the deriver, judge
  and author do not (`design.md` §3.1, D37), in both harnesses.
- **Role rules:** the author and deriver briefs carry their rules' complete text from the installed artifact; the list
  is declared once.
- **Path trigger:** the rule loader's path-scoped rule text names the rule by its absolute installed path and limits
  the trigger to files inside the current project, so reading CAT's installed rule files never matches it.
- **Closes:** R-E20.
- **Tests:** session-argument tests per harness for each role; brief tests for the delivered rules; `ProjectRuleLoaderTest`
  for the path-trigger text.
- **Validation:** author promotion and judge verdicts on a short Codex campaign compared with E1.
- **Exit:** unit tests pass; the full build passes.

### WI-26 — Partial promotion and attempt approaches (user ruling, 2026-10-02)

- **Promotion:** an attempt fixes a triggering instance when both of its runs there pass. It is promoted when it fixes
  at least one triggering instance and fails no regression instance that the working candidate passes; it is rejected
  as soon as no triggering instance can still be fixed or a regression is certain (`design.md` §2.7, D33). Winner
  selection among a weak round's accepted attempts is unchanged.
- **Streak rule:** a triggering instance counts as fixed only when every run passes and `p^k < 0.10`, where `k` is the
  run count and `p = (s + 1) / (n + 2)` from the working candidate's `s` passes in `n` outcomes on that instance (its
  representative failure, diagnostic reruns and any other outcome on the same instance). Runs start at 2 and are added
  one at a time, up to 4, while all pass and the streak is not yet unlikely (`design.md` §2.7, D35).
- **Revert:** the §2.7 revert check and its effect on the round and the plateau (`design.md` §2.7, D35).
- **Minimality:** a reduction passes when it fixes every triggering instance that the attempt it reduces fixed and fails
  no regression instance that the working candidate passes.
- **Approaches:** each author attempt of a category in a round receives the §2.7 approach for its position in that
  category's attempts; the author brief and prompt carry it (`design.md` §2.7, D34). `author.md` step 2 corrects an
  instruction in the file that states it, `AGENTS.md` and `CLAUDE.md` included (D30).
- **Closes:** R-D3 (rule change), R-D11 (reduction rule), R-D14, R-D15.
- **Tests:** `PromotionTest` (one of two triggering instances fixed is promoted; none fixed is rejected early; a
  regression still rejects), `MinimalityTest` (a reduction that loses a fix fails; one that keeps every fix passes),
  `AuthorTest` (each position's approach is rendered), `DriverTest` (a round's attempts receive distinct
  approaches; a partially fixing attempt becomes the working candidate).
- **Exit:** unit tests pass; the full build passes.

### WI-25 — Resolving conflicts with the user (user ruling, 2026-10-02)

- **Resolutions:** each deriver conflict gains at least two `resolutions` and the `recommended` one with its `reason`;
  the proposal's `Conflicts` section lists them with the recommended one marked (`design.md` §6.1, D31). `deriver.md`
  takes the approved `### Conflicts` wording verbatim.
- **Agent flow:** `first-use.md` takes the approved step 2 and step 4 wording: the agent asks the user to resolve each
  conflict, naming the recommended resolution, never offers confirmation while one is listed, and passes the answers to
  `revise`.
- **Mid-campaign conflicts:** the author manifest gains the `conflict` classification with the requirement ids, each
  quoted passage with its file, the resolutions and the recommended one. The author brief carries the campaign's edits
  since confirmation so the author can tell a conflict it inherited from one a campaign edit introduced. CAT rejects a
  `conflict` report whose quotation is absent from the snapshot confirmed with the contract. A valid report pauses the
  campaign: no new trials are scheduled, in-flight work finishes, and the choices are each resolution (recommended one
  marked, with its reason) and `stop`; `revise` with the user's own wording is also accepted. Choosing a resolution
  runs a revision with it as feedback and returns the revised proposal for confirmation; a running certification
  attempt then ends `not certified: stopped for contract revision`, and the confirmed revision starts a new lineage
  (`design.md` §2.7, §7, D32). `author.md` takes the approved step 1 and step 2 wording verbatim.
- **Closes:** R-C14, R-D13.
- **Tests:** resolution parsing and rendering; the author conflict report accepted when its quotation is in the
  confirmed snapshot and rejected when only a campaign edit holds it; the pause and its choices; choosing a resolution
  yields a revision and, once confirmed, a new lineage; a running certification attempt ends
  `stopped for contract revision`.
- **Exit:** unit tests pass; the full build passes; E1 per harness shows the agent asking the user to resolve the
  motto conflict with a recommended resolution.

### WI-24 — Conflicting requirements block confirmation (user ruling, 2026-10-02)

- **Findings:** the deriver's findings gain a required `conflicts` list. Each entry names at least two contract
  requirements that no reply can satisfy together and the evidence: the file and the text that show the conflict. The
  deriver reports a conflict instead of settling which requirement prevails, and drops it from a revision once the
  user's feedback resolves it (`design.md` §6.1, D31).
- **Proposal:** a `Conflicts` section lists each conflict, or states that the deriver found none.
- **Confirmation:** `confirm` is refused while the stored proposal lists a conflict, and the refusal names each conflict
  and says that feedback must resolve it. Nothing is recorded on refusal.
- **Closes:** R-C13.
- **Tests:** the findings parser accepts a valid conflict and rejects one that names fewer than two requirements or an
  unknown requirement; the proposal renders the section in both states; `confirm` is refused with a conflict and
  succeeds after a revision without one.
- **Exit:** unit tests pass; the full build passes.

### WI-23 — Module scenario location (user ruling, 2026-10-02)

- **Location:** a scenario for guidance packaged as a module resource, `<module>/src/main/resources/<top>/<path>.md`,
  lives in `<module>/src/test/resources/<path>/<case>/scenario.md`; `.cat/tests/` keeps the scenarios of project rules,
  user rules and other project guidance (`design.md` §8).
- **Discovery:** derivation finds module scenarios beside `.cat/tests/` ones, so the deriver receives them and a
  proposal that omits one of a named prompt source is refused. A `scenario.md` belongs to the guidance of its parent's
  parent directory only, so a nested guidance's scenarios are not attributed to an enclosing one.
- **Migration:** move the 61 rule scenarios back to `client/common/rules/src/test/resources/<path>/<case>/scenario.md`
  with their fixtures at their earlier paths, keeping the current scenario text and fixing each fixture link; move the
  `first-use.md` scenarios to `client/common/skills/src/test/resources/prompt-optimizer/first-use/<case>/scenario.md`.
  `suite.json` and `verification.md` stay retired.
- **Closes:** R-I3 (with WI-14, WI-21).
- **Tests:** discovery of a module scenario and its citation refusal; a nested guidance's scenario stays with it;
  `RegressionScenariosTest` fails when a module guidance's scenario lies in `.cat/tests/`, or a scenario directory in
  either location names no existing guidance.
- **Exit:** unit tests pass; the full build passes; no scenario of module guidance remains under `.cat/tests/`.

### WI-22 — Replies include interim messages (user ruling, 2026-10-02)

- **Result:** `SessionResult.Completed` carries each turn's reply as its assistant messages in order, ending with the
  final response (`design.md` §1.2 Reply, D29). The Codex adapter takes every `agent_message` of the turn; the Claude
  adapter takes every assistant text block of the turn, including those before a tool call and those of a follow-up
  that belongs to the turn.
- **Checks:** a deterministic check of reply text reads the whole reply: the turn's messages joined in order, so a
  suffix check still ends at the final response and a forbidden-text check sees an interim message (`design.md` §6.4).
  Control validation uses the same shape.
- **Judge:** the `responses` evidence gives each turn's messages in order, and the judge prompt says that every one
  of them is part of the reply.
- **Author:** a failure record carries the same reply, so the author sees the interim message that failed.
- **Stored format:** trial records and the campaign format that hold responses change format; older formats are
  refused, as before.
- **Closes:** R-C12.
- **Tests:** per harness, an adapter test over recorded events with a progress message before a tool call; a check
  test where a forbidden text appears only in an interim message and a suffix check still passes; a judge request test
  that shows every message; an author failure-record test.
- **Exit:** unit tests pass; the full build passes; then E1 restarts on the final build.

### WI-15 — Acceptance

- **End-to-end:** in both harnesses, run these scenarios through the installed skill:
  - **E1:** a dirty base is refused; the deficient fixture's proposal lists its conflict, confirmation is refused
    until a resolution is chosen, and the fixture then improves to `certified` and is delivered as a Git diff, with a
    disjoint base edit surviving. Its receipt also shows, per harness: the deriver's proposal is schema-valid, has
    requirements and names the instruction file as a derived source; each judge verdict is schema-valid, carries a
    rationale and leaves the evidence unchanged; each author attempt changes only prompt sources; and the per-trial
    records hold the observed model, the login class, rate-limit state and the loading record. These replace the
    retired native tests.
  - **E2:** ESC mid-slice, then resume with no lost or duplicated outcomes, the certification index kept, and Codex
    stopping within 15 seconds.
  - **E3:** a plateau or explicit stop leaves the base unchanged; explicit uncertified acceptance is labelled
    `not certified` and delivered through the same choose-and-deliver path that keeping an improvement uses. Keeping
    an improvement is covered by `DriverTest` traces 12 and 13 and `DeliveryTest`, because a certification attempt
    ends uncertified only after hundreds of trials.
  - **E4:** unsupported cases: a remote governing instruction, an image-output requirement, a response-contingent
    obligation, and a persistent-write path.
  - **E5:** a trial uses a host tool, and one uses child subagents with child worktrees.
  - **E6:** a cross-harness invocation is refused without any state change.
  - **E7:** a project with in-project and external links shows the link report, and an authorized external edit is
    delivered.
  - **E8:** two concurrent CAT processes are bounded by the shared capacity.
- **Recordings:** each run saves its harness event streams, untrimmed, with the harness version. They replace the
  contract tests' recordings, and the contract tests then pass on them (`design.md` §10 T3). Save at least one
  completed multi-turn session per role and an interrupted turn (from E2) per harness.
- **Contract tests (moved from WI-14):** once a harness's recordings exist, add `<Harness>SessionsContractTest`, which
  runs every `SessionPortContract` check against the real adapter over `FakeHarnessCli` replaying them. Delivering an
  interrupt to the fake needs a signal seam in the adapter, because `FakeHarnessCli.pid()` has no process to signal.
  Replace `codex/cli/src/test/resources/optimizer/codex-hooks.jsonl`, which names no harness version, with a
  recording that does. Then delete that harness's `*SessionsNativeTest`, and after both harnesses, remove the
  `native` profile, `cat.test.groups`, `cat.test.excludedGroups`, `cat.native.harness` and `cat.work.temp`.
- **Order:** run every step in Codex first; start Claude only after Codex passes all of E1–E8 (user ruling,
  2026-10-01).
- **Fixtures:** small prompt systems under `client/common/cli/src/test/resources/optimizer/e2e/`, with a deliberately
  deficient rule, a rule that is easy to satisfy, a link fixture and an unsupported-case fixture. Lower the cost
  through the contract's category count, not by weakening the statistics: use 1–2 categories.
- **Closes:** every E proof in §3, and the G1–G10 gates of `design.md` §14.
- **Exit:** see [§9](#9-final-gates).

## 6. Dependency order

A work item starts once every work item it needs is integrated. A premise gate listed in [§4](#4-premise-gates-wi-0)
blocks only the part of a work item that it names.

| Work item | Needs |
|---|---|
| WI-0, WI-1, WI-2, WI-5, WI-6 | Nothing |
| WI-3 | WI-1, WI-2. Its `Throttle` also needs P4 and P5. |
| WI-4 | WI-3 |
| WI-7 | WI-3, WI-6 |
| WI-8 | WI-0, WI-3, WI-5. Its runner fixes need only WI-0 and are delegated and integrated on their own first. |
| WI-11 | WI-4, WI-6 |
| WI-9 | WI-3, WI-4, WI-11. It drives fakes and does not wait for WI-7 or WI-8. |
| WI-12 | WI-3 |
| WI-10 | WI-0 (P9, P11), WI-2, WI-9 |
| WI-13 | WI-0 (P10), WI-5, WI-7, WI-8, WI-9, WI-10, WI-11, WI-12 |
| WI-14 | WI-13 |
| WI-16 | WI-14 |
| WI-17 | WI-16 |
| WI-18 | WI-17 |
| WI-19 | WI-16 |
| WI-20 | WI-18 |
| WI-21 | WI-14, WI-10 |
| WI-22 | WI-8, WI-17 |
| WI-23 | WI-21 |
| WI-24 | WI-22 |
| WI-25 | WI-24 |
| WI-26 | WI-18, WI-20, WI-25 |
| WI-27 | WI-26 |
| WI-28 | WI-22, WI-26 |
| WI-29 | WI-28 |
| WI-30 | WI-29 |
| WI-31 | WI-30 |
| WI-32 | WI-31 |
| WI-33 | WI-32 |
| WI-15 | WI-14. Its trial-based scenarios (E1 certification, E2, E3, E5, E7 delivery, E8) also need WI-16, WI-17, WI-18, WI-20, WI-32 and WI-33. |

Do WI-0 and WI-1 first when capacity is limited: they carry the highest risk of changing other work.

## 7. Handoffs and use-case traces

Each cross-component handoff is declared once here. Other sections refer to these declarations. Handoff IDs `H-n` are
distinct from the harness-evidence IDs `Hn` of `design.md` §11.

| Handoff | Producer → consumer | Ready when | Identity and authority | Failure and resumption | Proof |
|---|---|---|---|---|---|
| H-1 Confirmed contract | WI-10 → WI-3, WI-2 | The user's `confirm` on proposal revision `r` | Contract hash and lineage `ℓ`. Only the confirmation event freezes it. | A missing confirmation blocks every admission. A revision creates `ℓ + 1`. | `DriverTest` "nothing runs before confirmation" |
| H-2 Assignment | WI-3 `Scheduler` → WI-4 → WI-9 → WI-8 | The assignment record is durable | Campaign, purpose, snapshot, category, sequence and instance. The draw comes from the stream's persisted position. | Started without an outcome means rerun, same identity. | `ReconciliationTest` |
| H-3 Trial result → judge | WI-8 → WI-9 (WI-17 checks) → WI-8 (judge) | Session completed, children reconciled, every deterministic check passed and a semantic requirement remains. Otherwise CAT records the outcome without a judge session (`design.md` §6.4). | Logical trial identity. The judge receives read-only evidence and the deterministic results, which it cannot override. | Interruption or infrastructure failure means rerun the trial. An invalid judge result means rerun the judge with the same evidence. | `DriverTest` (trace 16), `DeterministicCheckTest`, `JudgeVerdictTest` |
| H-4 Outcome → adoption | WI-9 → WI-3 | Outcome durable, and every earlier sequence number adopted | Sequence order per category and purpose | A crash between write and adoption is adopted during reconciliation. | `AdoptionTest`, `ReconciliationTest` |
| H-5 Author delta → candidate | WI-7 → WI-6, WI-18 → WI-3, WI-20 → WI-7 | Delta captured; inventory and manifest validated; promotion runs passed; in a weak round, the attempt won the selection; the minimality pass finished (`design.md` §2.7) | Overlay hash. Authority comes from the contract. | A rejected attempt is retained with no candidate. The author reruns as a new attempt. | `InventoryDiffTest`, `AuthorManifestTest`, `PromotionTest`, `AttemptSelectionTest`, `MinimalityTest`, `DriverTest` |
| H-6 Commit | WI-3 → WI-1 → WI-4 | Readiness and baseline met | Certification index `i`, `α_i`, `β_i`, frozen `UI(k)` and `LI(k)`, `DecisionBoundaries` and caps, all persisted before the first draw | Calibration failure leaves the attempt uncommitted and blocks admission. | `CommitTest`, `AdmissionTest` |
| H-7 Decision → delivery or user | WI-3 → WI-11 or WI-12 → skill | Attempt terminal and in-flight trials drained | Attempt and candidate identity. A keep-improvement choice names the qualifying attempt. | A pending choice, including the keep-improvement pause, survives restart. Delivery resumes from the journal. | `DecisionTableTest`, `KeepImprovementTest`, `DeliveryTest` |
| H-8 Status → skill → choice | WI-12 → WI-14 → WI-13 | `run` returned | Campaign identity and choice identifier. Only listed choices are accepted. | An unknown or stale choice is rejected with no change. | `CommandsTest`, E-scenarios |
| H-9 Capacity permit | WI-5 → WI-8 | `tryAcquire` succeeded | One permit per nested session | Process death releases the file lock. | `HarnessCapacityTest` |
| H-10 Host interrupt | WI-8 → WI-9 | An interrupt record later than the driver start | Host thread or session ID from the environment | Stop admissions, interrupt sessions, persist, exit within 15 seconds. | `RolloutInterruptSignalTest`, `DriverTest`, E2 |
| H-11 Trial profile | WI-16 (`ProfileInventory`, profile sets) → WI-9 → WI-8 | Confirmation froze the profile set | Cell (category, profile). A trial session runs under its cell's profile; deriver, judge and author sessions run under the role profile. The observed profile in the harness events decides comparability. | A run whose observed profile differs from its trial's measurement identity yields no outcome, and CAT pauses as `MEASUREMENT_CHANGED` (`design.md` §5). | `ProfileSetTest`, `ClaudeSessionsTest`, `CodexSessionsTest`, `MeasurementIdentityTest`, `DriverTest` (trace 15) |
| H-12 Failure evidence → author | WI-9 → WI-10, WI-18 (author prompt) | A development round starts its author attempts (`design.md` §2.7) | The failing instances' identities, which the manifest must cite, with their judge rationales or failed-check records and diagnostic reruns. Never an instance of a certification attempt in progress. | An attempt that cites an unknown instance is rejected and retained (H-5). | `DriverTest` (author input during a formal attempt; traces 16 and 17), `AuthorManifestTest` |
| H-13 Needed edits → authority warning | WI-10 (author prompt) → WI-9 → WI-6 (`Authority`) → WI-3 → WI-12 | An author session completed and its report lists the edits it needed, each naming a requirement of the confirmed contract | Each needed edit's requirement, path, operation and target, checked against the campaign's edit authority and the targets the user authorized (`design.md` §4.3) | A malformed report, or one naming an unknown requirement, fails the attempt as an infrastructure failure with no candidate. Otherwise the attempt's candidate is submitted as usual. A warning is raised only while the campaign is active, confirmed and not revising its contract; it is an `awaiting-user` decision that survives restart, and authorizing one target leaves the other warnings pending. | `DriverTest`, `AuthorityWarningsTest`, `AuthorityTest`, `StatusReportTest` |
| H-14 Candidate overlay | WI-9 → WI-4 (`CandidateOverlays`) → WI-9, WI-11 | The overlay is saved before the event that records its snapshot | The snapshot, which is the overlay's identity. The campaign records only the snapshot. | Saving an overlay that is already saved does nothing. A recorded snapshot without a saved overlay, or an overlay file that is malformed, on another fork or for a different candidate, is a read failure that names the file; CAT never reconstructs an overlay. | `CandidateOverlaysContract` through `FileCandidateOverlaysTest` and `InMemoryOverlaysTest`, `DriverTest` |

Use-case traces that `DriverTest` must cover, each as one test that starts from builder state:

1. **Certify:** confirmation, then development to readiness, commit, all-pass formal, certified, delivery requested.
2. **Edit after a failure:** a failure, a reproduced rerun, an edit, promotion runs, a new working candidate with
   counts at zero and the window back to 1.
3. **Continue after a failure:** a failure, 3 passing reruns, continue with the window back to 1 and doubling after
   that.
4. **Out-of-order completion:** in formal, trial 3 completes before trial 2 and the decision waits for trial 2.
   Post-decision outcomes are recorded but don't change the decision.
5. **Crash:** a crash after the outcome write but before adoption, then reconciliation adopts it once.
6. **Interrupt:** a host interrupt mid-slice stops admissions, interrupts sessions and persists state.
7. **Inconclusive:** a certification attempt reaches its cap, then returns to development.
8. **Rate limit:** a rate-limit rejection drops the window to 1, holds admissions until reset, then reruns the same
   logical trial.
9. **Unowned change:** an unowned worktree change pauses until the user decides.
10. **Revision during formal:** a revision requested mid-attempt waits for drain; the stopped attempt never resumes.
11. **Busy:** a second driver receives `busy`.
12. **Keep improvement:** a certification attempt reaches its cap with one category proven better and the others
    certified or at 100 or more outcomes. CAT pauses with `keep improvement`, `optimize further` and `stop`; after a
    restart the choice is still pending; `keep improvement` requests delivery of that attempt's snapshot, labelled
    `improved, not certified`.
13. **Best candidate so far:** an attempt qualifies, the user chooses `optimize further`, a later candidate's
    attempt also qualifies with a lower weakest-category bound, and a third attempt fails. At the stop, CAT offers
    `keep improvement` for the first candidate, and keeping it materializes that candidate before delivery.
14. **Measurement change during formal:** mid-attempt, a run reports a different model. Its record holds no outcome
    and CAT pauses with `MEASUREMENT_CHANGED`. Restoring reruns the same instance and the attempt continues. A second
    change followed by a new measurement identity ends the attempt `not certified: testing environment changed`,
    returns to development and starts a new incumbent baseline. A qualifying attempt from before the change is offered
    separately, labelled historical.
15. **Cells:** a category whose file is unbound runs under two profiles; each cell has its own counts, readiness,
    commit and decision, and a trial runs under its cell's profile while the judge runs under the role profile.
16. **Deterministic failure:** a trial fails a deterministic check, no judge session runs, and the failed check's
    record reaches the author brief.
17. **Manifest rejection:** an author attempt with an uncited hunk is retained with no candidate, and the next attempt
    runs.
18. **Minimality:** a promoted attempt with one unneeded hunk is reduced to the needed hunks, the reduction's runs are
    not counted, and the report names the removed hunk.
19. **Development cells:** with the latest-versions scope, development samples only the weakest cell from the
    ranking, the candidate passes readiness there and is committed; a formal failure in another scope cell makes it a
    development cell, and the next commit needs readiness there too.
20. **Concurrent attempts:** a weak working candidate's round starts 3 attempts; two pass the promotion rule and the one
    with more passing promotion runs is minimized and promoted, while the others are retained; once the candidate is no
    longer weak, the next round makes one attempt.
21. **Held-out instances:** development, targeted runs and author briefs see only development instances; formal and
    baseline trials draw only held-out ones; after a failed attempt the author brief names the failure's cell, test
    and requirements but holds no held-out instance, reply or rationale.

## 8. Test-suite rules

These rules restate `design.md` §10 as checks for reviewers:

- The only test that runs `CapCalibration` is `CapCalibrationTest`.
- The only tests that run real Git are `ForkTest`, `DeliveryTest` (final diff and no-commit check),
  `InventoryScannerTest` (ignored files) and `GitTest` (the process adapter's exit status and argument passing). Outside
  the optimizer, the git-replay package keeps one real-Git test per Git operation, each under 1 second:
  `BackupTest.recordsAndDeletesBackupBranch`, `HistoryTest.inventoriesSourcePathStates`, three
  `EditorPatchWorkflowTest` patch-adaptation and draft-tree tests, `ReplayExecutionTest.replaysOrderedBatchesWithMatchingTag`,
  `FinalizationTest.publishAndVerifyGitReceipt` and the two `OutputGatesTest` worktree-lifecycle tests. Every other test
  asserts the commands CAT builds or its decisions from captured Git output (user ruling, 2026-10-02: assume Git works
  as documented).
- No Java test starts a runner, a harness process or a model. Only the WI-15 end-to-end runs start nested sessions.
- No test class has a non-`final` field or mutable shared fixture. No test reads `HOME`, `user.home` or the current
  directory.
- No production class reachable from a unit test calls `Thread.sleep`.
- A fake has only behaviour its shared contract checks, and that contract also runs against the real adapter
  (`design.md` §10 T3). Reviewers check this; there is no coverage tool (user ruling, 2026-10-01).
- A unit test that takes 1 second or longer records its reason in its Javadoc (`design.md` §10 T7). Without one, it is a
  review defect. Every integration lists the tests at or over 1 second from the surefire reports.

## 9. Final gates

The feature is complete only when every gate below passes on the final tree. Record each gate as passed, failed or
unrun; an unrun gate is unmet.

1. **Build:** `./mvnw verify` from `client/` passes.
2. **Traceability:**
   - Every requirement ID in §3 appears in at least one `// Requirement:` tag in a test that ran and passed in the tier
     its proof names. An agent checks this by extracting the IDs from this file and the tags from the sources and
     test reports.
   - No ID is untagged, and no tag names an unknown ID.
   - Every E proof has a receipt from WI-15.
3. **No model in Java tests:** an audit finds no Java test that starts a runner, a harness process or a model, and no
   `native` test group or Maven profile.
4. **End-to-end:** E1–E8 pass in both harnesses through the installed artifacts.
5. **Design acceptance:** each G1–G10 row of `design.md` §14 maps to passing evidence from gates 1–4.
6. **Testability audit:** the §8 rules hold, and the G9 check, unit tests run with `HOME` and `user.home` pointing at a
   nonexistent directory, passes.
7. **Documentation:** `design.md` §11 and §12 reflect the WI-0 results; decisions changed during implementation are
   recorded in §13; this plan's §2 shows every work item done.
8. **Standalone codebase:** after gates 1–7 pass, a last commit removes every `// Requirement:` tag, and a search of
   `client/`, `docs/` and `.cat/` finds no reference to `design.md`, this plan, or their section numbers and
   identifiers (§1).

A mock-only run, a successful contract extraction, a candidate diff or a statistical unit test is intermediate
evidence. None of them proves the product outcome by itself.
