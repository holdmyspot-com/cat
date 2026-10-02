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
  requirement ID it proves. The final gate counts these tags.
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
| WI-15 | In progress. Codex first, then Claude (user ruling, 2026-10-01). The Codex build of `6c90bad8d` is installed (`install-codex.sh`, exit 0; the installed `workflow-temp --help` shows the new output lines); install the Claude build only when Claude starts. Fixture pilot: a motto defined only in a handbook was not deficient (12/12 compliant); contradictory instructions ("close with the motto" beside "no slogans or sign-offs") were deficient (Claude 4/6, Codex 0/6 compliant); the author-style fix ("apart from the motto, no other slogans...") reached Codex 6/6 with correct answers. This contradiction fixture is the E1 deficient fixture. Fixtures `deficient`, `easy`, `link` and four `unsupported` variants are under `optimizer/e2e/` (README lists each one's use). E1 Codex, dirty-base refusal: passed through the installed skill (`start AGENTS.md` refused on the uncommitted `docs/handbook.md`; no campaign created). Evidence in `.cat/work/temp/e1-codex-15400782742439386847/receipts/`, kept until the WI-15 receipts are recorded. In that run, and in every later Codex run, the agent first ran `"${CAT_PLUGIN_ROOT}/client/bin/prompt-optimizer"` literally (exit 127, the variable is unset) and then found the installed path itself; `skill-env.md` is in the session's context but was not applied. The fix is a rule change, awaiting the user (asked 2026-10-01). E4 Codex: passed. Each of the four `unsupported` fixtures produced a proposal naming its outcome (unsupported instruction source R2–R3; unsupported output R3–R4; unsupported test interaction R4–R5; ineligible trial effect R3–R4); `confirm` recorded that outcome with `stop` as the only choice and dispatched no trial; `stop` cancelled each campaign; every base stayed clean and `~/acme-support` was never created. Evidence in `.cat/work/temp/e4-codex-11033726973909551249/*/receipts/` (events, final message, confirm and stop JSON, Codex 0.159.1). Minor findings, open: the summary's "Waiting for" line repeats "certification is unavailable: certification unavailable: …"; after `stop` the status is `awaiting-user` with only `resume`. E7 Codex, first run: `start` refused the link fixture with "missing target" although the external target existed, because the fork recorded external link targets only after the deriver named them (commit `97b9c226b` fixes it: creating or extending the fork records the existing outside targets of recorded links, transitively; full build 1201 tests, 0 failures; Codex build reinstalled). E7 Codex link report: passed on the rerun; the proposal lists `docs/handbook.md -> shared/handbook.md` (inside the project, outside prompt sources) and `docs/reply-rules.md` -> its external target (external, read only) and states the staging consequence. Evidence in `.cat/work/temp/e7-codex-4281424245095601688/receipts/`. Minor, accepted: on the intentionally contradictory fixture, the "Certifiable scope" line and a limitation disagree on whether certification is unavailable. E7's authorized external edit is still to run. E6 needs both harnesses and moves to the Claude phase. The trial model and effort question became trial profiles (user ruling, 2026-10-01): the certification-dependent scenarios (E1 certification, E2, E3, E5, E7 delivery, E8) wait for WI-16–WI-18, then the Codex build is reinstalled. The exit-127 cause was found: Codex tool shells define neither `CODEX_HOME` nor `CAT_PLUGIN_ROOT` (probe: only `CODEX_CI`, `CODEX_SESSION_ID`, `CODEX_THREAD_ID`, `CODEX_VERSION`, `HOME`), so `skill-env.md`'s derivation could not work; fixed by the plugin-root follow-up below. |
| Plugin root and harness home (WI-15 finding, R-I5) | Done (full build 1210 tests, 0 failures; bats 32/32). `ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE` is replaced in plugin-tree direct rules and their includes; project and user rules keep it unchanged, because the loader cannot tell a quotation from a use and rejecting would inject no rules. Lazily read `rules/include/**` files are not rendered; only `skill-env.md` uses the directive. Remaining duplicate derivations, out of scope: `CodexRunner.codexHome` and `ClaudeRunner.claudeConfigDirectory` fall back to a relative `.codex` or `.claude`; the Codex `claude-runner` skill lets a local `CAT_CLAUDE_MARKETPLACE_ROOT` override its derived path, which `.cat/rules/include/environment.md` forbids. Tests at or over 1 s: `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` 1.02 s (reason in its Javadoc). Verified after reinstalling Codex (commit `52ceefeec`): a Codex session asked to run `${CAT_PLUGIN_ROOT}/client/bin/workflow-temp root` ran the installed absolute path on its first command, exit 0, with no exit-127 call. Per the user's ruling (2026-10-01: Codex sets no custom environment variables; derive values in Java or state them through the environment rule), the session hooks render `<!-- cat:plugin-root -->` in plugin rules into the absolute plugin root, both `skill-env.md` files tell the agent to write that directory wherever a skill writes `${CAT_PLUGIN_ROOT}`, and `CodexGetHistory`/`ClaudeGetHistory` default to `HOME/.codex` and `HOME/.claude`. |
| P14 effort probe | Done 2026-10-01; P14 holds (`design.md` H27). First run inconclusive (H26: deficient 0/20 and fixed 20/20 at every effort). Rerun on an intermediate-compliance variant (the two rules in reverse order) at user request, 20 trials per effort on `gpt-6.1-sol`: low 5, medium 8, high 16, xhigh 19 compliant, all answers correct; the pre-stated 2×4 exact test rejects equal rates, p ≈ 2.2 × 10^-6. Effort stays in the profile set. A second rerun with a rule without a conflict (user request; `design.md` H28): `gpt-6.1-sol` was at the ceiling (40/40 pilot trials at low), so the run used `gpt-6-luna`: low 4, medium 6, high 13, xhigh 15 of 20, p ≈ 0.0006, mostly through whether the model read the handbook. A rerun against a partly optimized real rule (for example `write-clearly.md`, which needs the judge) waits for WI-17. Probe directories deleted after recording. |
| P15 single-cell development (weakest model, lowest effort) | Not started. Runs after the first real prompt file is optimized (§4). |
| WI-16 | Done 2026-10-01. Trial profiles and cells: contract types `Cell`, `ProfileBinding`, `ProfileCatalog`, `ProfileRule`, `TrialProfile`, `TrialProfiles`; `ProfileInventory` port with `ClaudeProfileInventory` (opus, fable and sonnet with low–max, haiku without effort, from a shipped resource) and `CodexProfileInventory` (listed models of `codex debug models`); session requests carry the cell's profile, judge and deriver use the role profile from the installed `optimize-xhigh` agent; campaign format 2 (format 1 refused); status JSON keys name cells (`cells`, `cell`). `DriverTest.cellsRunUnderTheirProfiles` is trace 15. Mutation checks on code written before its tests: every mutant killed except three equivalent mutants and two untested hops (CX14, the agent effort override, which existed before WI-16 and needs an installed-plugin fake; CX15, `CodexRunnerCli.serve` passing the parsed effort, whose neighbouring links are each tested). Integrated build: 1281 tests, 0 failures; no test at or over 1 second; bats 32/32. |
| WI-17 | Done 2026-10-01 (`8cfd6d829`). Eight check kinds with known-good and known-bad controls, CAT's core-task requirement on every test, no judge session after a failed check, and the check record in the author brief. The campaign and proposal file formats are version 3; version 2 is refused. Merging with WI-19 needed `TierFixtures` to leave the core-task requirement out of profile binding. Full build 1352 tests, bats 32. Open: `tool_used` and `exit_status` checks cannot be decided on real trials, because the Claude and Codex session results record no tool calls; until the adapters record them, such a check counts as an infrastructure failure. Follow-up: WI-17a. |
| WI-17a | Not started: the Claude and Codex adapters record each trial's tool calls and command exit statuses in the session result, so that `tool_used` and `exit_status` checks can be decided. Waits for the harness convention pass. |
| WI-18 | Not started. |
| WI-19 | Done 2026-10-01. `CellTier` derives the active set (bound cell, each model's lowest and highest effort in inventory order, any cell with an adopted failure other than a promotion run) from the contract and the persisted `failedCells`; `Scheduler` screens every cell with 3 trials of the incumbent in series 1, samples only active cells under the coverage floor with each model's lowest-effort cell first, and widens through a persisted `widened` latch once every active cell meets the readiness count conditions. Campaign format 3 (`failed_cells`, `development.widened`); format 2 refused. Status shows each category's active cells and the widened flag (`cellTier` in JSON). Trace 19 is `DriverTest.cellTiering`. Integrated build: 1297 tests, 0 failures; no test at or over 1 second in that run (the delegate's runs saw `GeneratorTest.samplesVariationsAtFrozenProbabilities` at 1.446 s and `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` at 1.059 s, both pre-existing); bats 32/32. |
| WI-20 | Not started. |

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
- `design.md` §2.7, §9, §14 G1 and D28: 3 concurrent author attempts while the working candidate is weak, best accepted attempt promoted; plan R-D12, WI-20 with §1.1, §6 and trace 20 (user ruling, 2026-10-01).
- `design.md` §3.3 rule 2, §6.6 and §12 P15: each model's lowest-effort active cell is sampled first in development; P15 asks whether developing only in the weakest model's lowest-effort cell suffices for stronger models and higher efforts, tested after a real prompt file is optimized; plan R-E19 and WI-19 updated, §4 P15 probe and §2 row added (user ruling, 2026-10-01).
- `design.md` §11 H28 and §12 P14 result: a rule without a conflict on `gpt-6-luna` also varies with effort (user request, 2026-10-01).
- `design.md` §11 H27 and §12 P14 result: the intermediate-compliance rerun shows reasoning effort changes compliance (user request, 2026-10-01).
- `design.md` §12: added P14 (does reasoning effort change compliance?); plan §4 adds its probe and §2 its row (user request, 2026-10-01).
- §2: added the plugin-root follow-up (R-I5). Codex tool shells define no `CODEX_HOME`, so the `skill-env.md` derivation always failed (found in WI-15).

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
| R-D1 | Readiness gate: at least 100 trials, at least 99%, latest two batches of 50 each at least 98%, no unresolved defect, baseline met. | §2.7 | WI-3 | U: `ReadinessGateTest` (each boundary on both sides) |
| R-D2 | Edit-or-continue: pause admissions, up to 3 reruns, edit if reproduced or more than 2 failures, otherwise continue with the window reset to 1. | §2.7 | WI-3, WI-9 | U: `EditOrContinueTest`; U: `DriverTest` (pause and resume) |
| R-D3 | Author attempts are validated, run twice on triggering instances and once on the regression set, and promoted by the §2.7 rule. Targeted runs never count. | §2.7 | WI-3, WI-9 | U: `PromotionTest`; U: `DriverTest` |
| R-D9 | Authors receive development instances and outcomes and the formal outcomes of ended attempts, never the instances of a certification attempt in progress. | §3.1 | WI-9 | U: `DriverTest` (author input during a formal attempt) |
| R-D4 | A new candidate starts new counts; earlier outcomes are retained for diagnosis. | §2.7 | WI-3 | U: `CampaignTransitionsTest` |
| R-D5 | Review checkpoint at 200 unresolved trials: stop the category, offer choices, record the next review point. | §2.7 | WI-3 | U: `ReviewCheckpointTest` |
| R-D6 | Rounds and plateau: a round gives each failing category a diagnosis and up to 2 author attempts. A plateau requires a complete round without promotion. Interruption, infrastructure failure or time never cause a plateau. | §2.7 | WI-3 | U: `PlateauTest` |
| R-D7 | Incumbent baseline arm: 50 per category, plus 50 conditional. A commit requires the baseline. The baseline is reused while measurement identities stay comparable; otherwise the incumbent is measured again as a new baseline with its own share. | §2.5 | WI-3 | U: `BaselineTest`, `AdmissionTest` |
| R-D8 | Development results are labelled `development estimate` and `not certified`. | §2.7, §2.9 | WI-12 | U: `StatusReportTest` |
| R-D10 | Author manifest: each failure's classification (routing, guidance, not guidance) and, for each changed hunk, the cited failing instances and its analogous and dissimilar cases. An attempt with an uncovered hunk or an unknown citation is rejected and retained; an attempt that edits nothing produces no candidate and its classifications reach the round's diagnosis and the review or plateau report. | §2.7, D25 | WI-18 | U: `AuthorManifestTest`, `DriverTest`, `StatusReportTest` |
| R-D11 | Minimality pass: delta debugging over the promoted attempt's hunks, each reduction judged by the promotion runs and rule, ending when no single hunk can be removed; its runs are targeted and never counted; the minimal attempt is promoted; the report names the removed hunks. | §2.7, D26 | WI-18 | U: `MinimalityTest` (removes an unneeded hunk, keeps a needed one, ends at single hunks), `DriverTest` |
| R-D12 | Concurrent attempts while weak: when any cell of a category the round addresses has a development lower bound below 80% or no development outcome, the round makes 3 independent attempts from the same brief; each runs the same promotion runs; the accepted attempt with the most passing promotion runs wins, ties to fewer changed lines, then the earlier attempt; only the winner is minimized and promoted; the others are retained; the status records each attempt's promotion results. | §2.7, D28 | WI-20 | U: `AttemptSelectionTest` (weak and not-weak rounds, winner, both tie-breaks, no accepted attempt), `DriverTest` trace 20, `StatusReportTest` |

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
| R-E3 | Allocation order: targeted work, development cell tier and coverage floor among active cells, Wilson lower bound, formal rule (the category furthest from certifying), baseline arm, with the stated tie-breaks. | §3.3 | WI-3 | U: `SchedulerTest` |
| R-E4 | Concurrency: risk ceiling, slow start, category limit from the passes still required to certify, campaign ceiling (default 8, range 1–16), baseline arm on the remaining ceiling. | §3.3 | WI-3 | U: `ConcurrencyPolicyTest` (no failures: 1 at n=10 and 2 at n=11, 3 at n=22 and 4 at n=23, 7 at n=46 and 8 at n=47; 10 at 1 of 100; reset after a failure) |
| R-E5 | Adoption in assignment order; post-decision outcomes never change a decision. | §3.4 | WI-3, WI-9 | U: `AdoptionTest`; U: `DriverTest` (completions out of order) |
| R-E6 | Throttling at 80% and 95% utilization, rejection backoff of 1, 2, 4 … up to 30 minutes, per-trial records, no evasion features. | §3.5 | WI-3, WI-8 | U: `ThrottleTest`; E: rate-limit state in the per-trial records of E1 per harness |
| R-E7 | Authentication warning for a subscription login, including the minimum session count. | §3.5 | WI-8, WI-10 | U: `ProposalTest`; E: E1 login classification per harness |
| R-E8 | Shared harness capacity is an injected per-harness instance, acquired by both runner CLIs and per optimizer session, with `tryAcquire` and an injected pause. | §3.6 | WI-5 | U: `HarnessCapacityTest`; E: E8 |
| R-E9 | Slices: status kinds, admission cutoff from the 90th-percentile duration or the time limit, 1-minute grace, cancellation of the remainder, `busy` for a second driver. | §3.7 | WI-9 | U: `SliceTest` |
| R-E10 | Interrupts: the Codex rollout watcher stops within 15 seconds; the Claude transcript backstop; Claude kill recovery. | §3.8 | WI-8, WI-9 | U: `RolloutInterruptSignalTest`, `TranscriptInterruptSignalTest`, `DriverTest`; E: E2 per harness |
| R-E11 | Reconciliation runs first in every `run`: adopts recorded outcomes, reruns started trials that have no outcome, checks the worktree. | §3.8 | WI-9 | U: `ReconciliationTest` |
| R-E12 | Minimum remaining trials by the §3.9 formulas for the development and formal phases; token statistics; no totals or time estimates. | §3.9 | WI-12 | U: `RemainingWorkTest` |
| R-E19 | Development cell tiering: a 3-trial screen of every cell; active cells (a bound cell, each model's lowest and highest effort, any cell with a failure); within the coverage floor, each model's lowest-effort active cell is sampled first; widening to every cell once every active cell meets the readiness count conditions; a failure in a newly sampled cell makes it active; a new candidate restarts in the active cells. | §3.3, §6.6, D27 | WI-19 | U: `SchedulerTest` (screen, active set, lowest effort first within the floor, widening, a new failure activating a cell, restart after an edit), `DriverTest` trace 19 |
| R-E18 | The status compares the working candidate with the incumbent on prompt size (bytes and words) and on median input tokens per trial in each cell where both have outcomes. | §3.9, D11 | WI-18 | U: `StatusReportTest` |
| R-E13 | Every nested role runs through the runners. Judge and deriver output is schema-validated, and invalid output is an infrastructure failure. The host agent never authors or judges. | §3.1 | WI-8, WI-10 | U: `JudgeVerdictTest`, `ProposalTest`; E: E1 runs trial, judge, author and deriver sessions per harness |
| R-E14 | Child subagents and child worktrees stay within one logical trial; reconciliation before judging; observation limits. | §3.2 | WI-8 | E: E5 child scenarios per harness (premise P7) |
| R-E15 | The Claude adapter classifies an interrupt it requested as an interruption (H10). | §11 | WI-8 | U: `ClaudeSessionsTest`; E: E2 in Claude |
| R-E16 | The observed model comes from harness events, never the alias. | §5 | WI-8 | U: event parsing tests per harness; E: E1 observed model per harness |
| R-E17 | A trial session starts under its cell's profile and the deriver, judge and author sessions under the role profile (the `optimize-xhigh` binding); each runner passes the model and effort to its harness; the observed profile is recorded per cell and decides comparability. | §3.1, §5, §6.6 | WI-16 | U: `ClaudeSessionsTest`, `CodexSessionsTest` (requested profile reaches the harness arguments), `MeasurementIdentityTest`; E: E1 per harness |

### 3.5 Workspace

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-W1 | Storage under `.cat/work/optimizer/<id>/`; loading reads only the snapshot; `info/exclude` added and reported when needed. | §4.1 | WI-4, WI-7 | U: `FileCampaignStoreTest`, `ForkTest` |
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
| R-M4 | Comparability by exact equality; missing data gives `INDETERMINATE` and an unavailable comparison; each differing condition is named. | §5 | WI-3 | U: `ComparabilityTest` |
| R-M5 | A run observed under conditions that differ from its trial's measurement identity yields no outcome, is kept as an observation, does not count toward the infrastructure-failure limit, and reruns the same instance after a restore. A new measurement identity during a certification attempt ends it `not certified: testing environment changed`; it never resumes, its allowance stays spent and the next attempt takes the next index. A restore lets the attempt continue. | §5, §7 | WI-3, WI-9 | U: `CampaignTransitionsTest`, `DriverTest` (trace 14) |
| R-C1 | The proposal lists every §6.1 item, without aggregate summaries. | §6.1 | WI-2, WI-10 | U: `ProposalTest`; E: E1 deriver per harness |
| R-C2 | Feedback regenerates a complete proposal; nothing runs before confirmation. | §6.1 | WI-9, WI-10 | U: `DriverTest`, `ProposalTest` (regeneration) |
| R-C3 | Categories and a balanced benchmark, frozen at confirmation; allocation never changes weights. | §6.2 | WI-2 | U: `DistributionTest` |
| R-C4 | Generator validation; separate durable seeded streams; targeted work marked before execution; formal draws after the freeze; repeated instances allowed. | §6.3 | WI-2 | U: `GeneratorTest`, `DrawStreamTest` |
| R-C5 | A frozen judge profile under the role profile; the judge decides only semantic requirements and sees deterministic results as evidence it cannot override. | §6.4, §3.1 | WI-2, WI-10, WI-17 | U: `JudgeVerdictTest`; E: E1 judge per harness |
| R-C8 | Every test checks the core-task requirement, which CAT adds with CAT as its source. | §6.1, D24 | WI-17 | U: `ProposalTest` |
| R-C9 | Deterministic checks: a closed set of kinds with parameters that may name variation values; each check is validated against its known-good and known-bad controls at confirmation and at load; a failing check is discarded and its requirement judged semantically, with the reason in the proposal. A trial passes when every check passes and the judge passes every semantic requirement; no judge session runs when a check failed or no semantic requirement remains; a failed check's record reaches the author. | §6.1, §6.4, D23 | WI-17 | U: `DeterministicCheckTest` (each kind, each control on both sides), `DriverTest`; E: E7 |
| R-C10 | Trial profiles: the inventory per harness with its source; bindings from Claude skill and subagent frontmatter and Codex agent TOML only, partial bindings fixing one dimension, `inherit` binding nothing; the profile set per category and the resulting cells; a user restriction narrows the set; the proposal lists inventory, bindings, cells and their minimum session count; confirmation freezes the set. | §6.6, §2.1, D22 | WI-16 | U: `ProfileSetTest`, `ProposalTest`, per-harness inventory and binding tests over recorded catalog output; E: E1 proposal per harness |
| R-C11 | Cells are the statistical unit: every §2 and §3 rule that names a category applies per cell, and `K` counts cells. | §2.1 | WI-16 | U: `SchedulerTest`, `ReadinessGateTest`, `CommitTest` (a two-profile category gives two cells with separate counts, gates and decisions) |
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
| R-P4 | Claim wording names the frozen judge, benchmark and conditions, and lists limitations. | §2.1, §5 | WI-12 | U: `StatusReportTest` |
| R-I1 | Skill wrappers in both harnesses share one body and follow the §8 loop. | §8 | WI-14 | E: both harnesses |
| R-I2 | Launcher commands and the JSON status object. | §8 | WI-13 | U: `CommandsTest` |
| R-I3 | The old skill is retired; `failure-regression-promotion.md` follows D13; `default-rules.md` routing and test resources are updated. | §8, §13 | WI-14 | U: rule tests; reference audit |
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
| P15 | After the first real prompt file is optimized (E1's file or `write-clearly.md`), develop a fresh campaign on it with the profile set restricted to one cell: the weakest model, chosen as the lowest incumbent-baseline pass rate at each model's lowest effort, at its lowest effort (the §6.6 user control), until readiness. Then freeze that candidate and run it, with no edits, in each benchmark cell: every other model's lowest and highest effort, and the weakest model's highest effort (the §6.6 active-set pattern; 15 cells with the Codex inventory). Score with the same contract checks and judge. Pre-stated test: for each benchmark cell, H0: its pass rate is below 95%, one-sided exact binomial, α = 0.05 split equally over the benchmark cells (with 15 cells, 112 passing trials and no failure reject H0 in a cell; about 1,700 trials, roughly 140M tokens at 85k per trial). Report the effort result (same model, higher effort) and the model result (stronger model) separately. A shortcut holds for that file only if H0 is rejected in every cell it covers and those cells show no failure kind absent in the development cell. Several files must agree before development policy changes (user requests, 2026-10-01). If the optimizer cannot run a frozen candidate without editing it, record that as a missing capability and run the trials through the harness with the same checks. | Development keeps each model's lowest and highest effort active (`design.md` §6.6) |

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
  `list` and their efforts; Claude reads a plugin resource listing documented aliases and efforts (P12). Each is
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
  revert one group, rerun the same promotion runs under the same rule, keep the reduction if it passes, otherwise
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
- **Closes:** R-E19 and the tier part of R-E3.
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

### WI-15 — Acceptance

- **End-to-end:** in both harnesses, run these scenarios through the installed skill:
  - **E1:** a dirty base is refused; a deficient fixture improves to `certified` and is delivered as a Git diff, with a
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
| WI-15 | WI-14. Its trial-based scenarios (E1 certification, E2, E3, E5, E7 delivery, E8) also need WI-16, WI-17, WI-18, WI-19 and WI-20. |

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
19. **Cell tiering:** after the screen, development samples only active cells; once they meet the readiness count
    conditions it widens, a failure in a newly sampled cell activates it, and the next candidate restarts in the
    enlarged active set.
20. **Concurrent attempts:** a weak working candidate's round starts 3 attempts; two pass the promotion rule and the one
    with more passing promotion runs is minimized and promoted, while the others are retained; once the candidate is no
    longer weak, the next round makes one attempt.

## 8. Test-suite rules

These rules restate `design.md` §10 as checks for reviewers:

- The only test that runs `CapCalibration` is `CapCalibrationTest`.
- The only tests that run real Git are `ForkTest`, `DeliveryTest` (final diff and no-commit check) and
  `InventoryScannerTest` (ignored files).
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

A mock-only run, a successful contract extraction, a candidate diff or a statistical unit test is intermediate
evidence. None of them proves the product outcome by itself.
