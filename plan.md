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
  - **Unit:** runs in the default build under TestNG parallel methods. It includes file-system and real-Git tests that
    use test-owned temporary directories.
  - **Native:** TestNG group `native`, excluded by default and enabled with the Maven profile `native` and
    `-Dcat.native.harness=claude|codex`. It starts real nested sessions through the runners. Fixtures consumed by a
    runner live under the CAT work temp root, not the system temp directory.
  - **End-to-end:** scripted runs of the installed `prompt-optimizer` skill in each harness against fixture prompt
    systems. Each run records a receipt: commands, versions, outcome, and paths to retained evidence.
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
| WI-15 | Orchestrator, sending ESC for E2 and the native ESC tests as in P1–P3, with the user as the fallback. `optimize-low` may run the other native and end-to-end scenarios and collect their receipts. | Accepting the feature is a judgment against §9. ESC needs an interactive host, as in P1–P3. |

**When to delegate:**

- Delegate a work item once every predecessor in [§6](#6-dependency-order) is integrated. Each work item is one
  verification-closed unit, and its exit check is its acceptance evidence.
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

"Long-term" means the lifetime cost of the requirements that already exist, including their maintenance, not of
speculative ones. An option whose cost buys protection against no failure that a requirement names is rejected as
overkill.

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
| WI-7 | In progress: delegated to `optimize-medium` in `worktrees/v1.0-wi7` (branch `v1.0-wi7`, snapshot `5995438bf`), uncommitted. |
| WI-8 (adapters, signals, native contract tests) | In progress: delegated to `optimize-medium` in `worktrees/v1.0-wi8` (branch `v1.0-wi8`, snapshot `5995438bf`), uncommitted. |
| WI-12 | In progress: delegated to `optimize-low` in `worktrees/v1.0-wi12` (branch `v1.0-wi12`, snapshot `5995438bf`), uncommitted. |
| WI-3/WI-4 assignment record | In progress: delegated to `optimize-high` in `worktrees/v1.0-wi34f` (snapshot `d1393de1e`): adds §3.2's allocation decision, evidence revision, assignment time and infrastructure attempts to the trial and its stored record. |
| WI-11 | In progress: delegated to `optimize-high` in `worktrees/v1.0-wi11` (branch `v1.0-wi11`, snapshot `d1393de1e`), uncommitted. |
| WI-9, WI-10, WI-13 to WI-15 | Not started |

Changes made to `design.md` and this plan during implementation, apart from this section:

- R-S6: owner changed from WI-1 to WI-1, WI-3. Its proof `AdmissionTest` tests admission, which WI-3 builds.
- `design.md` §11: added H11–H13 (premises P1–P3).
- §1.1 assignments for WI-0 and WI-15: at the user's direction (2026-10-01), the orchestrator sends ESC through a terminal multiplexer, and the user presses ESC only if that fails.
- `design.md` §11: added H14–H23 (premises P4–P11). §12: added a result column. §3.1: judge and deriver output may be one JSON object alone in a Markdown code fence (H23). §3.2: a Claude turn includes its background children's completion; Codex trials cannot create child worktrees (H17, H18). §3.8: the Codex runner interrupts with `turn/interrupt`; reconciliation terminates the processes recorded in leftover run manifests (H19, H20). §5: loading observation names what each harness exposes (H21).
- §6 and §1.1: WI-8's runner fixes need only WI-0, run before the rest of WI-8, and go to `optimize-high`.
- `design.md` §3.3 and D8: the risk ceiling first permits 2, 4 and 8 trials at `n` = 11, 23 and 47 (D8 said 48); R-E4's proof now tests each threshold on both sides. D3: a committed 97% candidate certifies about 17%, matching §2.7 (it said 15%). WI-1 found both.
- §4: added the gate results. WI-8: added the runner fixes for H17 and H19 and their allowlist. WI-9: reconciliation terminates recorded processes. WI-10: loading split and the output extraction rule.
- `design.md` §5: a runner or CAT version change is a material harness change (H17). WI-3 asked whether these conditions are material; recorded as D21.

Update a row only from current evidence: its exit check passing on the current tree. Reopen a row when an input its
evidence depended on changes.

## 3. Requirement inventory

Each requirement points to the `design.md` section that defines it, its owning work item and its proof. A requirement
is closed only when every listed proof passes at its tier. Tier abbreviations: U = unit, N = native, E = end-to-end.

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
| R-E3 | Allocation order: targeted work, development coverage floor, Wilson lower bound, formal rule (the category furthest from certifying), baseline arm, with the stated tie-breaks. | §3.3 | WI-3 | U: `SchedulerTest` |
| R-E4 | Concurrency: risk ceiling, slow start, category limit from the passes still required to certify, campaign ceiling (default 8, range 1–16), baseline arm on the remaining ceiling. | §3.3 | WI-3 | U: `ConcurrencyPolicyTest` (no failures: 1 at n=10 and 2 at n=11, 3 at n=22 and 4 at n=23, 7 at n=46 and 8 at n=47; 10 at 1 of 100; reset after a failure) |
| R-E5 | Adoption in assignment order; post-decision outcomes never change a decision. | §3.4 | WI-3, WI-9 | U: `AdoptionTest`; U: `DriverTest` (completions out of order) |
| R-E6 | Throttling at 80% and 95% utilization, rejection backoff of 1, 2, 4 … up to 30 minutes, per-trial records, no evasion features. | §3.5 | WI-3, WI-8 | U: `ThrottleTest`; N: rate-limit event parsing per harness |
| R-E7 | Authentication warning for a subscription login, including the minimum session count. | §3.5 | WI-8, WI-10 | U: `ProposalTest`; N: classification per harness |
| R-E8 | Shared harness capacity is an injected per-harness instance, acquired by both runner CLIs and per optimizer session, with `tryAcquire` and an injected pause. | §3.6 | WI-5 | U: `HarnessCapacityTest`; N: two processes bounded |
| R-E9 | Slices: status kinds, admission cutoff from the 90th-percentile duration or the time limit, 1-minute grace, cancellation of the remainder, `busy` for a second driver. | §3.7 | WI-9 | U: `SliceTest` |
| R-E10 | Interrupts: the Codex rollout watcher stops within 15 seconds; the Claude transcript backstop; Claude kill recovery. | §3.8 | WI-8, WI-9 | U: `RolloutInterruptSignalTest`, `TranscriptInterruptSignalTest`, `DriverTest`; N: ESC per harness |
| R-E11 | Reconciliation runs first in every `run`: adopts recorded outcomes, reruns started trials that have no outcome, checks the worktree. | §3.8 | WI-9 | U: `ReconciliationTest` |
| R-E12 | Minimum remaining trials by the §3.9 formulas for the development and formal phases; token statistics; no totals or time estimates. | §3.9 | WI-12 | U: `RemainingWorkTest` |
| R-E13 | Every nested role runs through the runners. Judge and deriver output is schema-validated, and invalid output is an infrastructure failure. The host agent never authors or judges. | §3.1 | WI-8, WI-10 | U: `JudgeVerdictTest`, `ProposalTest`; N: one session per role per harness |
| R-E14 | Child subagents and child worktrees stay within one logical trial; reconciliation before judging; observation limits. | §3.2 | WI-8 | N: child scenarios per harness (premise P7) |
| R-E15 | The Claude adapter classifies an interrupt it requested as an interruption (H10). | §11 | WI-8 | U: `ClaudeSessionsTest`; N: Claude interrupt |
| R-E16 | The observed model comes from native events, never the alias. | §5 | WI-8 | U: event parsing tests per harness; N |

### 3.5 Workspace

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-W1 | Storage under `.cat/work/optimizer/<id>/`; loading reads only the snapshot; `info/exclude` added and reported when needed. | §4.1 | WI-4, WI-7 | U: `FileCampaignStoreTest`, `ForkTest` |
| R-W2 | Clean-base refusal before anything is created. The fork records paths, bytes, modes and links, copies ignored and external participants, and creates a detached optimization worktree. | §4.2 | WI-7 | U: `ForkTest` (real Git, once per operation) |
| R-W3 | Candidate identity is the hash of the overlay and the fork. | §4.3 | WI-6 | U: `OverlayTest` |
| R-W4 | Edit authority rules, including rejection of ambiguous dual-path edits, plus diagnosis warnings. | §4.3 | WI-6 | U: `AuthorityTest` |
| R-W5 | Staged source: tracked files, recorded participants, fixtures and overlay; links dereferenced; cycles and missing targets block confirmation; `.cat/work/` excluded; caches deleted when unused. | §4.4 | WI-7 | U: `StagedSourceTest`; N: runner accepts the staged source |
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
| R-C1 | The proposal lists every §6.1 item, without aggregate summaries. | §6.1 | WI-2, WI-10 | U: `ProposalTest`; N: deriver per harness |
| R-C2 | Feedback regenerates a complete proposal; nothing runs before confirmation. | §6.1 | WI-9, WI-10 | U: `DriverTest`, `ProposalTest` (regeneration) |
| R-C3 | Categories and a balanced benchmark, frozen at confirmation; allocation never changes weights. | §6.2 | WI-2 | U: `DistributionTest` |
| R-C4 | Generator validation; separate durable seeded streams; targeted work marked before execution; formal draws after the freeze; repeated instances allowed. | §6.3 | WI-2 | U: `GeneratorTest`, `DrawStreamTest` |
| R-C5 | A frozen judge profile; semantic judgment; assertions only as inputs. | §6.4 | WI-2, WI-10 | U: `JudgeVerdictTest`; N: judge per harness |
| R-C6 | The four scope conditions produce their `certification unavailable` outcomes, before or after a commit. | §6.5 | WI-3, WI-10 | U: `CampaignTransitionsTest`; E: unsupported cases |
| R-C7 | Loading is observed (premise P9) or derived from documented rules, with the limit recorded. | §6.1 | WI-10 | N: per harness |
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
| R-T1 | The testability contract T1–T7 holds. | §10 | WI-1 | U: `TestabilityAuditTest` (source scan of the optimizer and capacity code: no non-`final` static fields, no `Thread.sleep`, no reads of `HOME`, `user.home` or `System.getenv` outside `main` classes, only `final` fields in test classes); review audit at each work item's exit |
| R-T2 | Packages, ports and adapters follow §9: the harness-neutral core imports no harness package, and production sources import no test-support type. | §9 | WI-1 | U: `TestabilityAuditTest` (import scan) |

## 4. Premise gates (WI-0)

Run these before writing the code that depends on them. Use the existing runners and the probe method behind
`design.md` [§11](design.md#11-harness-evidence): a script that starts a plain, a `setsid` and a `nohup` child and
records heartbeats and signals, and that is driven through a real interactive host where ESC matters. Record each
result in `design.md` §11 as a new H-row with its date and versions. If a gate fails, apply its fallback from §12 and
update §3.7 or §3.8 as needed.

| Gate | Probe | Blocks |
|---|---|---|
| P1 | An interactive Codex host runs a command that tails its own rollout; press ESC; measure the time from ESC to `turn_aborted` being readable. | WI-8 Codex interrupt signal, WI-9 |
| P2, P3 | An interactive Claude host with the sandbox disabled runs the probe; press ESC; check child survival and the transcript interrupt record. | WI-8 Claude interrupt signal |
| P4, P5 | Read the runner event archives of one nested session per harness, searching for rate-limit utilization, reset times and usage. | WI-8 event parsing, WI-3 throttle inputs |
| P6 | Inspect each harness's credential sources in the runner's isolated home, for both a subscription login and an API key. | WI-8 classification |
| P7 | A nested trial delegates to a child, which delegates again, and two children use separate worktrees; the probe interrupts and restarts. | R-E14 |
| P8 | Kill a driver-like parent during a nested session and check for live native processes. | WI-9 reconciliation |
| P9 | Search the native events for prompt-source loading records in each harness. | WI-10 loading |
| P10 | A Codex host command runs past 10 minutes; record the timeout behaviour. | WI-13 slice default for Codex |
| P11 | Run 20 judge sessions per harness on fixed evidence; record the rate of schema-valid output. | WI-10 judge profile |

Exit check: every gate has a recorded result (pass, or the fallback applied) and `design.md` §11 and §12 are updated.

Results (2026-10-01; facts in `design.md` §11 H11–H23, results in §12):

- P1, P2, P3, P4, P5, P10: passed. P10 reached no limit in 662 seconds, so the over-limit behaviour is unobserved.
- P6: passed on the documented credential fields; only Claude subscription and Codex API-key logins were available.
- P7: partly passed. Completion and parallelism are observable once the runner fixes in WI-8 land; no Claude grandchild
  was observed. Fallback applied: Codex trials cannot create child worktrees (H18), recorded as a limit in §3.2.
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
- **Tests:** `CapCalibrationTest` checks the §2.4 table and is the only test
  that runs calibration. Every other test injects small handwritten `DecisionBoundaries`, or uses the `i = 1` table
  through a test fixture that builds it lazily once per JVM in an immutable holder. That holder is safe under parallel
  methods: it is computed once and never mutated.
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
    (the §2.8 actions), `KeepImprovement`, `Applicability` and `Comparability`;
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
  maximum, `tryAcquire` failing at the maximum, release, and two instances on the same directory sharing slots. Native:
  two runner processes with a maximum of 1 serialize.
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
  exclusion and fixtures. `LinkReportTest` covers the report. Native: the runner accepts a staged source built from a
  project with links.
- **Exit:** the focused tests pass.

### WI-8 — Session adapters and host signals (harness modules)

- **Contents:**
  - `ClaudeSessions` and `CodexSessions` implement `SessionPort` over `ClaudeRunner` and `CodexRunner`. They acquire one
    `HarnessCapacity` permit per session, pass evidence read-only to judges, collect transcripts and effects, and parse
    native events for the observed model, usage and rate limits. They classify outcomes as completed, interrupted at
    CAT's request (H10) or infrastructure failure.
  - Credential classification for each harness.
  - `RolloutInterruptSignal` (Codex) and `TranscriptInterruptSignal` (Claude) implement `HostInterruptSignal` from an
    injected path and clock.
  - Shared `SessionPortContract` tests run against the fake always, and against each real adapter in the native tier.
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
- **Tests:** event-parsing tests feed recorded native lines captured during WI-0 into the parser as in-memory input.
  Signal tests use a temporary rollout or transcript file that the test appends to under a manual clock. Native tests
  run the contract once per operation and harness.
- **Exit:** unit tests pass, and native contract tests pass in both harnesses.

### WI-9 — Driver (`optimizer.execution`)

- **`Driver.step`:** a deterministic loop body. It reads the state through `CampaignStore`, asks `Scheduler` for
  admissions, writes assignments, starts sessions through `SessionPort`, and consumes completion events from a queue.
  Then it writes outcomes, starts judges, adopts outcomes in order, applies `CampaignTransitions`, and checks
  `HostInterruptSignal` and the slice deadline.
- **`SliceRunner`:** the only class that blocks on real time. It waits on the event queue until the next deadline and
  is covered by one focused threaded test.
- **Also:** reconciliation (including terminating the recorded `ownerPid` and `nativePid` of runs left by an earlier
  driver, `design.md` §3.8 and H20), the campaign write lock (an injected path; a second driver gets `busy`), the slice cutoff
  and grace period, the pauses for measurement change and unowned change, the edit-or-continue pause, and the
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
  deriver output extraction rule of `design.md` §3.1 (a bare or singly fenced JSON object, H23); the authentication and link warnings in the proposal; and the judge profile freeze.
- **Closes:** R-C1, R-C2 (rendering), R-C5, R-C6 (detection in proposals), R-C7, R-E7 (warning), R-E13 (schemas).
- **Tests:** `ProposalTest` uses fixture deriver outputs, valid and invalid, to check every required item and the
  rejection of aggregate-only summaries. Native: one deriver, judge and author session per harness on a small fixture
  prompt system, recording the schema-valid rate from P11.
- **Exit:** unit tests and native sessions pass.

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

- **Skill:** replace the `skills/prompt-optimizer/first-use.md` body with the §8 loop, and update both `SKILL.md`
  wrappers, including their descriptions and allowed tools.
- **Rules:** apply D13 to `failure-regression-promotion.md` and `default-rules.md`, keeping the route's rule that a
  subagent reports a confirmed failure to the agent that delegated its work instead of correcting the guidance.
  Update the test resources that cite the old skill (`check-assumptions/existing-route-before-new-wrapper/scenario.md`,
  `limitations-and-workarounds/verification.md`) and `InvocationDirectoryTest`'s directory label if it no longer
  describes a real consumer. Follow `contract-retirement.md`.
- **Closes:** R-I1 (content), R-I3.
- **Tests:** existing rule tests pass. A reference audit (`grep -rn "prompt-optimizer"`) shows only references to the
  new skill.
- **Exit:** the audit is clean and the build passes.

### WI-15 — Acceptance

- **Native tier:** in both harnesses, run all `native` tests.
- **End-to-end:** in both harnesses, run these scenarios through the installed skill:
  - **E1:** a dirty base is refused; a deficient fixture improves to `certified` and is delivered as a Git diff, with a
    disjoint base edit surviving.
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
- **Fixtures:** small prompt systems under `client/common/cli/src/test/resources/optimizer/e2e/`, with a deliberately
  deficient rule, a rule that is easy to satisfy, a link fixture and an unsupported-case fixture. Lower the cost
  through the contract's category count, not by weakening the statistics: use 1–2 categories.
- **Closes:** every N and E proof in §3, and the G1–G10 gates of `design.md` §14.
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
| WI-15 | WI-14 |

Do WI-0 and WI-1 first when capacity is limited: they carry the highest risk of changing other work.

## 7. Handoffs and use-case traces

Each cross-component handoff is declared once here. Other sections refer to these declarations.

| Handoff | Producer → consumer | Ready when | Identity and authority | Failure and resumption | Proof |
|---|---|---|---|---|---|
| H-1 Confirmed contract | WI-10 → WI-3, WI-2 | The user's `confirm` on proposal revision `r` | Contract hash and lineage `ℓ`. Only the confirmation event freezes it. | A missing confirmation blocks every admission. A revision creates `ℓ + 1`. | `DriverTest` "nothing runs before confirmation" |
| H-2 Assignment | WI-3 `Scheduler` → WI-4 → WI-9 → WI-8 | The assignment record is durable | Campaign, purpose, snapshot, category, sequence and instance. The draw comes from the stream's persisted position. | Started without an outcome means rerun, same identity. | `ReconciliationTest` |
| H-3 Trial result → judge | WI-8 → WI-9 → WI-8 (judge) | Session completed and children reconciled | Logical trial identity. The judge receives read-only evidence. | Interruption or infrastructure failure means rerun the trial. An invalid judge result means rerun the judge with the same evidence. | `DriverTest`, `JudgeVerdictTest` |
| H-4 Outcome → adoption | WI-9 → WI-3 | Outcome durable, and every earlier sequence number adopted | Sequence order per category and purpose | A crash between write and adoption is adopted during reconciliation. | `AdoptionTest`, `ReconciliationTest` |
| H-5 Author delta → candidate | WI-7 → WI-6 → WI-3 → WI-7 | Delta captured and validated, promotion runs passed | Overlay hash. Authority comes from the contract. | A rejected attempt is retained with no candidate. The author reruns as a new attempt. | `InventoryDiffTest`, `PromotionTest`, `DriverTest` |
| H-6 Commit | WI-3 → WI-1 → WI-4 | Readiness and baseline met | Certification index `i`, `α_i`, `β_i`, frozen `UI(k)` and `LI(k)`, `DecisionBoundaries` and caps, all persisted before the first draw | Calibration failure leaves the attempt uncommitted and blocks admission. | `CommitTest`, `AdmissionTest` |
| H-7 Decision → delivery or user | WI-3 → WI-11 or WI-12 → skill | Attempt terminal and in-flight trials drained | Attempt and candidate identity. A keep-improvement choice names the qualifying attempt. | A pending choice, including the keep-improvement pause, survives restart. Delivery resumes from the journal. | `DecisionTableTest`, `KeepImprovementTest`, `DeliveryTest` |
| H-8 Status → skill → choice | WI-12 → WI-14 → WI-13 | `run` returned | Campaign identity and choice identifier. Only listed choices are accepted. | An unknown or stale choice is rejected with no change. | `CommandsTest`, E-scenarios |
| H-9 Capacity permit | WI-5 → WI-8 | `tryAcquire` succeeded | One permit per nested session | Process death releases the file lock. | `HarnessCapacityTest` |
| H-10 Host interrupt | WI-8 → WI-9 | An interrupt record later than the driver start | Host thread or session ID from the environment | Stop admissions, interrupt sessions, persist, exit within 15 seconds. | `RolloutInterruptSignalTest`, `DriverTest`, E2 |

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

## 8. Test-suite rules

These rules restate `design.md` §10 as checks for reviewers:

- The only test that runs `CapCalibration` is `CapCalibrationTest`.
- The only tests that run real Git are `ForkTest`, `DeliveryTest` (final diff and no-commit check) and
  `InventoryScannerTest` (ignored files).
- The only tests that start nested sessions are in the `native` group.
- No test class has a non-`final` field or mutable shared fixture. No test reads `HOME`, `user.home` or the current
  directory.
- No production class reachable from a unit test calls `Thread.sleep`.
- A unit test that takes longer than 2 seconds records its reason in its Javadoc. Without one, it is a review defect.

## 9. Final gates

The feature is complete only when every gate below passes on the final tree. Record each gate as passed, failed or
unrun; an unrun gate is unmet.

1. **Build:** `./mvnw verify` from `client/` passes.
2. **Traceability:**
   - Every requirement ID in §3 appears in at least one `// Requirement:` tag in a test that ran and passed in the tier
     its proof names. An agent checks this by extracting the IDs from this file and the tags from the sources and
     test reports.
   - No ID is untagged, and no tag names an unknown ID.
   - Every N and E proof has a receipt from WI-15.
3. **Native tier:** passes in both harnesses.
4. **End-to-end:** E1–E8 pass in both harnesses through the installed artifacts.
5. **Design acceptance:** each G1–G10 row of `design.md` §14 maps to passing evidence from gates 1–4.
6. **Testability audit:** the §8 rules hold, and the G9 check, unit tests run with `HOME` and `user.home` pointing at a
   nonexistent directory, passes.
7. **Documentation:** `design.md` §11 and §12 reflect the WI-0 results; decisions changed during implementation are
   recorded in §13; this plan's §2 shows every work item done.

A mock-only run, a successful contract extraction, a candidate diff or a statistical unit test is intermediate
evidence. None of them proves the product outcome by itself.
