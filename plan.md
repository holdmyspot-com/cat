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
	- Core code goes in `client/common/cli` under `com.holdmyspot.cat.common.optimizer.<package>`, following
		`design.md` [§9](design.md#9-architecture).
	- Harness adapters and `main` classes go in `client/claude/cli` (`com.holdmyspot.cat.claude.optimizer`) and
		`client/codex/cli` (`com.holdmyspot.cat.codex.optimizer`).
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
		in each harness. Fixtures consumed by a runner live under the session's CAT_TEMP directory
		(`.cat/work/temp` until WI-83, then `.cat/work/<session-id>/temp`), not the system temp directory. Each run
		records a receipt: commands, versions, outcome, and paths to retained evidence.
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
| WI-10 | `optimize-xhigh` | The contract author, judge and prompt author prompts determine what the product measures. |
| WI-11 | `optimize-high` | Journaled writes that must resume without duplicating or overwriting a change. |
| WI-12 | `optimize-low` | Rendering from builder states. |
| WI-13 | `optimize-low` | Command parsing and wiring. |
| WI-14 | `optimize-high` | Rule and skill edits under CAT's prompt rules and contract retirement. |
| WI-16 | `optimize-xhigh` | Cells change every statistical rule's unit, and runner profile selection must reach both harnesses exactly. |
| WI-17 | `optimize-xhigh` | Check kinds, controls and the judge's narrower role decide what the product measures. |
| WI-18 | `optimize-xhigh` | Manifest validation, the minimality search and the prompt author's prompt decide which edits ship. |
| WI-19 | `optimize-xhigh` | Allocation policy that interacts with the commit gate, the coverage floor and edits. |
| WI-20 | `optimize-xhigh` | Selection among concurrent attempts decides which edit ships and how rounds spend prompt author sessions. |
| WI-21 | `optimize-high` | A proposal check, a rule edit under CAT's prompt rules, and a migration whose completeness an audit must prove. |
| WI-22 | `optimize-high` | A result-shape change through both adapters, the checks, the judge and the prompt author evidence, with recorded-event tests per harness. |
| WI-23 | `optimize-high` | Scenario discovery across module test resources, a rule edit, and moving 64 scenarios with their fixtures. |
| WI-24 | `optimize-high` | A new contract author finding through the schema, parser, proposal rendering and the confirm command. |
| WI-25 | `optimize-high` | New contract author and prompt author outputs, a pause state with choices, a revision from a choice, and two prompt-wording changes. |
| WI-26 | `optimize-xhigh` | The promotion and reduction rules and the prompt author's approach decide which edits ship and when a campaign plateaus. |
| WI-27 | `optimize-high` | Per-role session setup in both runners, rule delivery from the installed artifact, and the rule loader's path-trigger text. |
| WI-28 | `optimize-xhigh` | The judge's evidence decides verdicts; one renderer per harness must lose nothing the judge needs, and frozen judge prompts must keep working. |
| WI-29 | `optimize-xhigh` | Fail-fast discards outcomes and changes when rounds start, and must keep adoption order unbiased. |
| WI-30 | `optimize-high` | Contract validation and the prompt-author-manifest check; no concurrency. |
| WI-31 | `optimize-medium` | Slug parsing in the Codex inventory, and one port method with one pre-derivation check in the core. |
| WI-32 | `optimize-xhigh` | Replaces the allocation tier, the baseline targets and the confirmation flow; interacts with the commit gate, the coverage floor and certification. |
| WI-33 | `optimize-xhigh` | Changes what every trial, prompt author and judge session may see, and the campaign format; an error leaks held-out test cases silently. |
| WI-34 | `optimize-xhigh` | Adds a contract author session after failed attempts and changes the development distribution and commit-gate counts; an error leaks held-out values into development. |
| WI-38 | `optimize-xhigh` | A contract and proposal format change, a new attempt-ending phase with its own trials and pause, and stress profiles; an error tunes without a target again, spends unbounded trials, or leaks held-out content into the contract author's view. |
| WI-35 | `optimize-high` | One scheduler rule over the existing capability ranking; an error spends formal trials in stronger cells or stalls an attempt. |
| WI-39 | `optimize-medium` | One finder's directories and selection rule; an error leaves a killed driver's harness processes running or kills a live campaign's sessions. |
| WI-40 | `optimize-medium` | One more refused setting and one runner settings change; an error leaves held-out content readable or refuses a project for a setting that cannot expose it. |
| WI-41 | `optimize-high` | A measurement-condition change with a campaign format bump, a declared version per harness and a test that ties it to the generated session settings; an error repeats certifications needlessly or keeps one current after a change the model sees. |
| WI-42 | `optimize-high` | A measurement-condition change with a campaign format bump, a declared version per harness and a test that ties it to the judge profile and the rendered evidence; an error keeps a certification current after a judge change or misses one. |
| WI-43 | `optimize-high` | Deletion of campaign files at two endings, a new project file written by delivery, and status for a target without a campaign; an error deletes a campaign that can still resume or deliver, or reports a stale certification as current. |
| WI-44 | `optimize-medium` | Two report loops that iterate the scope's cells; an error undercounts the remaining commit-gate work or omits a development cell from the per-cell report. |
| WI-46 | `optimize-high` | Changes when development ends: a completed round without a promotion resumes sampling unless the failure limit is exceeded; an error ends development on a candidate that could still pass the gate, or loops without a plateau on one that cannot. |
| WI-47 | `optimize-medium`; its runner fix goes to `optimize-high` | Event parsing tested on recorded samples, and a runner change to which events reach the listener; an error records no model, or the thread default instead of the turn's effort. |
| WI-48 | `optimize-xhigh` | A trial-record schema change with a format bump, written at every trial session end by the driver; an error loses a session's record or records the throttle state of another session. |
| WI-49 | `optimize-high` | A campaign format change that records answered warnings, a merge of warnings into one pending decision, and two changes to the prompt author's prompt; an error asks the user the same question again in a loop, hides a new warning, or lists an external file as editable. |
| WI-50 | `optimize-medium` | A session-result change for the four role sessions and one message; an error retries a trial that should be judged on its behaviour, or hides the time limit behind a schema message. |
| WI-51 | `optimize-medium` | An authority change that resolves links through the fork inventory; an error lets a prompt author edit an external file, a loader-configuration file or an unrelated in-project file, or keeps rejecting a prompt-source link's target. |
| WI-52 | `optimize-medium` | A session-result change for timed-out trials in both harnesses; an error retries a trial whose model answered or ran a hook, or judges a turn the provider never answered as "no reply". |
| WI-53 | `optimize-xhigh` | A cross-process hold file with locking, refusal detection in both event readers, and driver changes to admission, retry counting and pausing; an error lets one process keep connecting while another is refused, counts a refusal toward the infrastructure limit, or retries a revoked account forever. |
| WI-55 | `optimize-high` | A prompt-author-input change that renders each failing trial's evidence section into the prompt author's prompt; an error gives a prompt author another trial's evidence or drops a failure's commands. |
| WI-56 | `optimize-high` | Removes an outcome, a pending decision and its stored form with a format bump, and changes when development ends; an error ends development early or loops past the review checkpoint. |
| WI-57 | `optimize-high` | Cross-process file locking whose failure is silent: an error lets processes exceed the shared bound while each one stays within it. |
| WI-58 | `optimize-high` | A change to the prompt author's prompt and input with a new manifest rule; an error lets an attempt leave a kind of failure uncorrected, or rejects every attempt. |
| WI-59 | `optimize-medium` | Extends an enum and the approach selection; an error repeats an approach for a category or gives a prompt author the wrong instruction. |
| WI-45 | `optimize-high` | Removes a user choice, a command option, a stored campaign key with a format bump and a delivery output, and narrows delivery to the project; an error lets a prompt author attempt or a delivery change an external file, or leaves a retired choice reachable. |
| WI-37 | `optimize-high` | A storage layout change with a campaign format bump, a second run root, and a refusal; an error loses campaigns, leaves held-out content readable, or denies a trial its own run. |
| WI-36 | `optimize-high` | Session settings in both runners, one of them through Codex's permission configuration; an error lets a session read held-out content or stops trials from reading their own files. |
| WI-60 | `optimize-high` | Parameterizes every statistical rule by the minimum pass rate; an error certifies below the confirmed rate or loses the exact decision at a boundary. |
| WI-61 | `optimize-high` | A new file format, its loader and a seeded generator whose output must never change; an error stages a workspace other than the scenario's, or changes a values file's fixtures in a later release. |
| WI-62 | `optimize-medium` | Replaces the split key; an error puts one answer in both parts. |
| WI-63 | `optimize-high` | Replaces check kinds and the judge's unit of decision; an error passes a trial that failed an assertion, or starts a judge after a failed check. |
| WI-64 | `optimize-xhigh` | Removes the category from the model, the scheduler, the store and the reports; an error mixes counts of different prompt tests. |
| WI-65 | `optimize-xhigh` | New e-processes, a pairing rule and an allocation rule; an error certifies a prompt that degrades a guard, or pairs a control trial with the wrong test case. |
| WI-66 | `optimize-high` | Trials before confirmation; an error counts a pilot trial or spends allowance before confirmation. |
| WI-67 | `optimize-high` | New delivery writes; an error overwrites a scenario the user edited or delivers nothing after a kept incumbent. |
| WI-68 | `optimize-high` | Prompt wording for three roles; an error leaves the contract author writing the old format. |
| WI-69 | `optimize-medium` | Mechanical conversion of recorded scenarios; an error changes what a scenario tests. |
| WI-81 | `optimize-high` | Configuration parsing, a build step and install-record lookups whose refusals must leave nothing behind, a schema and campaign format change, and runner installation in both harnesses; an error installs a stale or unlisted plugin, or lets a candidate's edit miss the plugin that trials load. |
| WI-82 | `optimize-xhigh` | Rewrites what the contract author writes and receives, the proposal format, two new commands and the held-out move; an error leaks held-out test cases through the draft directory or the tools, or leaves a session that times out with nothing. |
| WI-83 | `optimize-high` | Moves where every session and CAT command writes behind one component, changes text that loads into every session, and adds a sweep that deletes directories; an error deletes the files of a live or resumable session, or tells an agent a `CAT_TEMP` that its own commands do not use. |
| WI-84 | `optimize-high` | A storage move with a campaign format bump, a cross-session refusal and worktree removal in the sweep; an error loses a campaign, lets two sessions advance one target, or leaves a stale Git worktree entry. |
| WI-85 | `optimize-medium` | A new committed file that delivery appends to and `status` reads; an error drops an earlier record or reports another harness's record as current. |
| WI-86 | `optimize-medium` | Changes only how the proposal shows data that other code reads; an error drops an item that the proposal must list, or changes a stored derived-source path that staging resolves. |
| WI-87 | `optimize-high` | Renames a path that every session and CAT command writes, and text that loads into every session; an error tells an agent a `CAT_TEMP` that its commands do not use, or leaves a campaign where the commands no longer look. |
| WI-88 | `optimize-medium` | Deletes trial directories while a campaign or pilot runs; an error deletes a failed trial's evidence or a copy that a later trial still uses. |
| WI-89 | `optimize-high` | Changes which files every trial, guard pilot trial and control trial sees, replaces the fixture layout that every recorded prompt test, draft and delivery uses, and copies link targets into trials; an error drops a prompt file that loads in production, lets a trial read a prompt test, gives a trial a frozen or path-only copy of a linked file, or reuses a stored pilot that ran on the old workspace. |
| WI-90 | `optimize-medium` | Changes which files the contract author derives requirements from; an error lists a file that no trial loads, so a requirement measures prompts that trials never receive, or drops a trial plugin's file, so its requirements go untested. |
| WI-91 | `optimize-medium` | Changes what control trials remove at a symbolic link and what the proposal shows when the control source cannot be built; an error deletes or edits a link's target, which other paths or an external file share, or hides why no guard pilot ran. |
| WI-92 | `optimize-medium` | Changes which files the runner copies into a session's project; an error lets a session follow a link out of its project, or refuses every revision of a proposal whose prompt tests link a project file. |
| WI-93 | `optimize-medium` | Changes how the guard pilot answers a provider's rate limit or refusal; an error starts sessions during a hold, retries a refusal that waiting does not fix, or leaves a pilot that never finishes. |
| WI-94 | `optimize-low` | Changes what the contract author lists as the contract's limitations; an error drops a real coverage gap from the contract, or keeps one run's results in it. |
| WI-95 | `optimize-low` | Changes how the Claude runner writes each request line; an error makes Claude Code reject a request and end every nested Claude session. |
| WI-96 | `optimize-low` | Changes which files CAT derives as loading in Claude trials; an error leaves an imported instruction file uneditable, or lets the prompt author edit a file that trials do not load. |
| WI-97 | `optimize-low` | Changes when `start` refuses a Claude target; an error starts a campaign whose trials miss an imported file, or refuses an import that trials receive. |
| WI-98 | `optimize-medium` | Changes which files CAT derives as running in trials; an error leaves a hook's script out of trials, or lets the prompt author edit a file that no hook runs. |
| WI-99 | `optimize-low` | Changes what the proposal lists; an error hides a file that trials receive, or lists one that they do not. |
| WI-100 | `optimize-medium` | Changes when a prompt author's attempt contributes no candidate; an error measures a candidate whose trials miss a file it loads, or rejects a valid candidate. |
| WI-101 | `optimize-medium` | Changes when `start` refuses a campaign; an error starts a campaign whose trials lose a hook's context, or refuses a hook that works in trials. |
| WI-102 | `optimize-low` | Changes what the proposal lists as running only in the user's sessions; an error hides a hook, plugin or MCP server that trials do not run. |
| WI-103 | `optimize-low` | Changes which instruction files a nested Claude session loads; an error lets a trial load the user's project instructions or drops the trial's own. |
| WI-104 | `optimize-low` | Changes the `PROMPT_SOURCES` measurement value of Claude trials; an error drops a file-sourced skill, agent or plugin from the measurement or keeps a built-in one whose presence varies between sessions. |
| WI-105 | `optimize-low` | Changes which entries of a harness root pruning treats as runs; an error leaves a terminal run's runtime in place or makes pruning fail on an entry that is not a run. |
| WI-106 | `optimize-low` | Adds a bundled rule and its route; an error makes agents add parentheses that change an expression's grouping, or omit them where the user expects them. |
| WI-109 | `optimize-medium` | Changes which paths every nested session may write; an error lets trials write the user's Claude or CAT directories, or denies trials the dependency caches that their package installs and builds use. |
| WI-110 | `optimize-low` | Changes when promotion stops extending a passing streak; an error promotes an attempt on a streak that is still likely for the working candidate, or never stops adding runs. |
| WI-112 | `optimize-medium` | Changes how prompt-test discovery learns which links the project's Git index records; an error converts a working prompt test as a link checked out as a file, or misses one whose workspace link Git checked out as a file. |
| WI-113 | `optimize-medium` | Changes how every atomic file replacement and executable start waits; an error makes a write fail while another process reads the file, a start fail on a busy file, or a retry never stop. |
| WI-114 | `optimize-low` | Changes when the contract author's draft is refused; an error accepts a draft that no later command can read, or refuses one whose links every trial receives. |
| WI-115 | `optimize-low` | Changes what every command reads from a stored proposal and how a resumed campaign treats one that no longer validates; an error grants a campaign other prompt sources, or leaves a campaign that no command can end. |
| WI-116 | `optimize-low` | Changes which paths a Codex session is denied reading; an error lets a development session read held-out content, or stops every Codex session from starting. |
| WI-117 | `optimize-low` | Adds a bundled rule on the bounds of range parameters; an error routes it where no range is defined or teaches a convention that the language does not use. |
| WI-119 | `optimize-medium` | Changes which files a prompt author may create, where its brief tells it to put new guidance, what `start` reports without a prompt test configuration, and how every certification report names its environment and states that CAT neither records nor controls the host machine's programs and outside sources; an error lets a candidate write rules that trials never load, hides a plugin from the user's choice, claims a certification for plugins it never ran with, fails to load a campaign or history stored with the retired host tools and external sources conditions, or lets a user believe that CAT recorded the tools and sources that trials used. |
| WI-118 | `optimize-low` | Adds a bundled rule that names a condition whose meaning its expression does not state; an error routes it to every condition or names a condition in a way that skips a guard. |
| WI-120 | `optimize-high` | Adds Claude's `codex-runner` skill, changes Codex's `claude-runner` skill, and adds the download of the other harness's CAT release archive; an error runs a task without CAT for that harness, runs an archive whose checksum was not verified or of another version, leaves a partial copy that a later run uses, or makes a skill fail for a user who never installed the other harness's plugin. |
| WI-121 | `optimize-medium` | Adds the runners' `--session-transcripts` option and moves the optimizer's rollouts to it; an error leaves transcripts on disk by default, loses a kept run's transcripts, or breaks a campaign's records of what a trial's model received. |
| WI-122 | `optimize-high` | Changes what a session's commands may read and receive in both runners; an error lets a command read a login or start a harness that reaches the provider, or breaks a tool that trials use. |
| WI-123 | `optimize-high` | Adds requested sessions to both runners and the `request-session` launcher; an error gives a requested session more than its requester's permissions, puts a credential in reach of a command, escapes the shared capacity, or leaves a requested session running after its run ends. |
| WI-124 | `optimize-medium` | Changes every Markdown file of both plugin artifacts; an error drops guidance that a model needs, breaks a directive, or ships design goals or maintainer comments. |
| WI-108 | `optimize-medium` | Changes how every CAT launcher starts its JVM; an error makes launchers fail outside a sandbox too, or lets two JVMs in separate PID namespaces write the same perf-data file. |
| WI-107 | `optimize-medium` | Changes what every nested session may read and which host permissions a Claude session receives; an error lets trials and judges read the user's project again, denies a session its own run or evidence, or removes a grant that trials need. |
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
| WI-2 | Done (commit `7c38706aa`); full build passed on the integrated tree. Follow-ups: WI-10 injects the CAT-owned judge `model`, `prompt` and `result_schema` before validating contract author output, and renders the CAT-computed §6.1 items (procedure, link report, warnings, minimum session count) that the proposal schema does not hold; WI-3 or WI-4 hashes `ProposalSchema.toJson(Contract)` for H-1. The formal `DrawStream` starts closed and WI-3's commit opens it (R-F1). |
| WI-6 | Done (commit `5a5499b75`); full build passed on the integrated tree. `DeliveryPlan` treats any change to an overlay target on the base side as a conflict, including an identical change, per design §4.7. Its change list gives WI-11 each change's preimage and postimage for at-most-once resume. |
| WI-1 | Done (commit `269c29413`); full build passed on the integrated tree. `CapCalibration.calibrate` throws the checked `CapCalibrationException` when no cap up to 10,000 reaches 90% power; WI-3 blocks admission on it. `ErrorBudget` leaves certification-index assignment to WI-3 and WI-4. `TestabilityAuditTest` reads the source root from the surefire property `cat.client.directory` and scans `optimizer` packages in all three CLI modules plus `common/runner/HarnessCapacity*.java`; `Pause.java`, which holds the only `Thread.sleep`, is outside its scope. The test JAR of `client/common/cli` is published. |
| WI-3 | Done (commit `486beb8ba`); 218 focused tests and the full build passed on the integrated tree. `ContractIdentity` hashes `ProposalSchema.toJson(contract)` (H-1). Trial sequences are numbered per category, purpose and snapshot (`SequenceGroup`); adoption groups also include the attempt or series. Pending decisions queue in arrival order and the first is offered; an equal decision is not queued twice. Every measurement condition is material, including runner and CAT versions (D21). The throttle state is persisted in `Campaign`. Only `optimizer.port` is exported. For WI-8: the observed prompt-sources value must not depend on the candidate snapshot, or every candidate looks like a measurement change. For WI-12: `AssumptionCheckTest` already covers the policy side. |
| WI-8 runner fixes | Done (commit `013867fa9`); full build passed on the integrated tree; unit-tested with in-memory fakes shaped like the WI-0 evidence. Claude: after the root `result`, the turn waits while `background_tasks_changed` lists a task, then up to 30 seconds for a follow-up `init`, then for its `result`; the reply comes from root records only. Codex: `interrupt()` sends `turn/interrupt` (held until the turn ID is known), an interrupt CAT requested completes the turn, and the reply is the root thread's last `agentMessage`. A late interrupt is a no-op in the bridge and both processes. Open for the rest of WI-8: its native contract tests must exercise a Claude background child and a Codex mid-turn interrupt (the runner native tests cover only capacity); unobserved cases are a child finishing before the root `result` with a follow-up still to come, and SIGINT to Claude while it waits for children. The duplicated test `FakeNativeProcess` moves to the common test JAR when WI-8 adds that dependency. |
| WI-4 | Done (commit `1b124e324`); 38 store tests and the full build passed on the integrated tree. `FileCampaignStore` keeps `campaign.json` plus `trials/<sha256 of key>.json`, every file with `format_version: 1`; the conversion is hand-written (`CampaignJson`) because the campaign, statistics and contract packages are not opened to Jackson. A fault after each of the 20 write steps of one trial's lifecycle reloads with exactly one adopted outcome. `writeOutcome` ignores an identical rewrite and rejects a different one. Only `optimizer.port` is exported; the store and campaign packages stay internal. For WI-9: replace the snapshot that holds an assignment before its session starts, because `listUnadoptedOutcomes` reads records only for the snapshot's assigned and running trials. Gap found: the assignment record lacks §3.2's allocation decision with its evidence revision, the assignment time and per-attempt infrastructure records; a WI-3/WI-4 follow-up adds them. |
| WI-7 | Done (commit `e21810388`); 63 focused tests, the full build, and the native staged-source test in both harnesses passed. `Workspace` implements `WorkspacePort`. `Fork.create` refuses a dirty base, requires the repository top level, adds `/.cat/work/` to `info/exclude` only when `git check-ignore` reports it not ignored (new `Git.status` returns the exit status; any status other than 0 or 1 fails), and undoes its worktree and fork storage on failure. Dereferencing is shared by staging and `LinkReport`, so they cannot disagree. External-target edits live in a mirror at `<campaign>/worktree-external/`. The native test exercises the runners' shared `HarnessRunManager.copyProject`, not a runner process: runner-process acceptance of a staged source with links remains open for the native and end-to-end tiers (WI-15). Not covered: submodules (copied as directories). |
| WI-8 (adapters, signals, native contract tests) | Done (commit `39e7ed804`); full build passed on the integrated tree; native suites passed in both harnesses (Claude 10/10, Codex 9/9), including a Claude background child, an interrupt while Claude waits for a child, a Codex mid-turn interrupt and the host-login classification. Each module has a `SessionPort` adapter (`ClaudeSessions`, `CodexSessions`), native event parsing, `Credentials`, `PromptSources` and a host interrupt signal. The port gains `HostLogin hostLogin()` (SUBSCRIPTION, API_KEY, UNKNOWN; no secret) for WI-10's R-E7 warning, and `SessionResult.Completed` carries the observed prompt sources. Adapters take the host configuration directory as a setting, so the optimizer packages never read HOME. Runner fixes: both runners wait for the process to exit after its output ends, and the bridge stops a running turn when its input fails. Steps first written without a red phase were checked by mutation; the interrupt-at-turn-start request gained `interruptWaitsForTheTurnStart` because no test caught its removal. Decided: a session cut off at its time limit is `Completed` with partial replies (§3.2); Codex read-only evidence stays as H23 records; prompt author effects land in a run copy and the driver captures them through `WorkspacePort`. Open for the end-to-end tier: a Claude follow-up still to come after a child that finished before the root `result` is unobserved and would be attributed to the next turn. Shared test fakes (`FakeNativeProcess`, `ManualClock`, `ScriptedHarness`) are in `common.runner.fakes` of the common test JAR. The WI-0 probe directory was deleted with `workflow-temp delete`. |
| WI-12 | Done (commit `28e67b5df`); 43 focused tests and the full build passed on the integrated tree. The status is the sealed `ReportStatus`: `running`, `awaiting-user`, `busy` (set only by the driver) or the terminal outcome label; a finished campaign with no outcome reports its delivery label, else `interrupted`. `not certified` is a delivery label, not an outcome (§2.8). Development minimum remaining trials simulate all-pass trials against `CommitGate` (§3.9 corrected). `LinkReport` was not yet available, so the symbolic-link limitation is a boolean input (`ReportEvidence.symbolicLinks`) that WI-9 sets from WI-7's report. The report-side assumption test is `AssumptionCheckReportTest`. |
| WI-3/WI-4 assignment record | Done (commit `80b532b26`, plus `StatusReportTest` fix); full build passed on the integrated tree. Each trial carries `Allocation(rule, evidenceRevision, assignedAt)` (rule = the §3.3 allocation rule) and `InfrastructureAttempt(endedAt, failure, detail)` for every run without an outcome; the campaign's evidence revision counts adopted outcomes. `format_version` stays 1 (keys added only). |
| WI-11 | Done (commit `cf64d1ce0`); 12 `DeliveryTest` cases, 49 store tests and the full build passed on the integrated tree. `Delivery.deliver(campaignId, request, selected, working, promptSources)` returns the sealed `DeliveryResult` (`Delivered`, `Conflicted`, `ConcurrentEdit`, `UnownedChange`, `VerificationFailed`). The journal (`<campaign>/delivery.json`, `format_version: 1`) holds the full planned changes with content, so a resume re-applies the preflight plan without re-planning; a target already at its postimage is skipped. Only `certified`, `improved, not certified` and `not certified` deliver. The diff uses a temporary index in the Git directory, so the user's index is untouched. For WI-9: the delivered `Applicability` covers prompt content only; combine it with `Applicability.of` for measurement conditions. The delivery package is not exported. |
| WI-9 | Done (commit `0abcd21f0`); full build passed on the integrated tree. `DriverTest` has 27 tests, the 14 §7 traces among them, and runs in under 1 s; `ReconciliationTest` covers each write-ahead crash point; `SliceTest` covers cutoff, grace and the one threaded `SliceRunner` test. Prompt author edits are captured with `WorkspacePort.scanPromptAuthorEffects(copy, effects)` (design §9), and the PROMPT_AUTHOR session's source is the prepared authoring copy. `PROMPT_SOURCES` comes from `Completed.promptSources()`. `ProcessHandleTerminator` terminates a leftover run only when the recorded start instant matches; descendants already reparented are not found. Main code preceded most tests, so mutation checks were run: every mutation failed a test, and one that first survived (a judge's rate-limit rejection counted as a judge failure) gained `judgeRateLimit`. Accepted: the status has no delivery-output fields (WI-13 renders `SliceResult.delivery()`), and a crash between materializing a promotion and replacing the snapshot is reconciled. Seams for WI-10: `JudgeProtocol`, `PromptAuthorProtocol`. `optimizer.execution` is not exported; WI-13 decides. |
| Candidate overlay store (WI-4/WI-9 follow-up) | Done (commit `c93657464`); 15 overlay tests and the full build passed on the integrated tree. `CandidateOverlays` moved to `optimizer.port`, so the store does not depend on `execution`. `FileCandidateOverlays(campaignDirectory)` writes `<campaign>/overlays/<snapshot>.json` atomically (`format_version: 1`, the fork's identity, entries with Base64 content and mode); loading rebuilds the overlay from the persisted fork inventory and rejects a corrupt file, another fork, or an identity that differs from the requested snapshot. `FileCampaignStore` shares `AtomicFiles`. Verified: `Driver.submit` saves an overlay before any snapshot references it. For WI-13: save the incumbent `Overlay.of(fork inventory)` before the first `campaign.json` write. |
| WI-10 | Done (commit `7e93ca239`); the full build passed on the integrated tree (common/cli 875, claude 79, codex 78, distribution 23; no failures). `optimizer.proposal` derives and validates the proposal (Claude on `haiku` needs a two-turn contract author: inspect, then a tool-free turn that sends only the proposal); `optimizer.execution.prompt` renders the judge and prompt author prompts; the judge returns a `Judgment` (verdict and rationale). Judge rationales and each failure's diagnostic reruns persist with the campaign (`Failure`, `DiagnosticRerun`; required fields, no fallback) and reach the prompt author brief (§2.7, §3.1). An UNKNOWN login shows the full authentication warning (P6 fallback). Its native prompt tests were deleted under the 2026-10-01 test ruling; their assertions moved to E1's receipt (WI-15). |
| Claude follow-up attribution (WI-8 follow-up) | Done (commit `3b3b35b35`; full build passed with 1080 tests, 0 failures). Probes (Claude 2.1.286, haiku, six scenarios, `design.md` H24) reproduced the race: a child that finished during the root's last model call left an empty task list before the root `result`, and a follow-up `init`/`result` came within 0.1 seconds. Fix in `ClaudeHarnessProcess.TurnBoundary`, per `design.md` §3.2 and H25: the runner sets `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1`, and a turn ends at a `session_state_changed` `idle` while the latest `background_tasks_changed` lists no task; neither a root `result` nor a task-list change ends it alone. A Claude that emits no state events fails the turn non-retryably at its first `init`, naming `claude_code_version` (user ruling, 2026-10-01). The runner sets no turn time limit of its own (user ruling, 2026-10-01: the 30-second guard is removed); while a turn writes no bridge record for 30 seconds, `HarnessRunnerBridge` writes a `waiting` record (`runId`, seconds since the last record) and repeats it every 30 seconds, in both harnesses. A follow-up `result` (`origin.kind` `task-notification`) that arrives after its turn ended is relayed with the next turn's events, never taken as its reply, and reported in that turn's `standardError`. The fix also orders the bridge so an interrupt sent after `turn-completed` is a no-op (`HarnessRunnerBridgeTest.ignoresInterruptAfterTurnEnded` was intermittent). |
| WI-13 | Done (commit `9663297dd`; full build passed with 1139 tests, 0 failures; `prompt-optimizer.bats` 4/4 against both images; `prompt-optimizer status` printed the fixture campaign's status JSON from both jlink images). Launcher grammar: `start <target>`, `run|status|confirm|cancel <id>`, `revise <id> <feedback>`, `choose <id> <choice> [--attempt N \| --review-point N]`; statuses `refused`, `invalid-input`, `error` with exit codes 1, 2, 1. `optimizer.cli` is exported; execution, store and campaign stay internal. `confirm` records fixtures and derived sources through `Fork.addParticipants`, and a revision after confirmation that needs new participants is refused. `OptimizerCore` takes `Forks` and `CapCalibrator` seams, so its tests run no Git; `HarnessVersion` runs `<exe> --version` through an injected launcher. Loader configuration: Claude `.claude/settings.json`, `.claude/settings.local.json`, `.mcp.json`; Codex `.codex/config.toml`. |
| Harness terminology (user ruling, 2026-10-01) | Done (commit `8606bee5e`; full build passed with 1141 tests, 0 failures; the five bats files passed, 33 of 33). "Native" meaning the harness or its model process became "harness" in classes, members, messages and docs. Harness-specific classes take the harness's name (`ClaudeProcess`, `CodexProcess`); generic types keep "Harness" (`HarnessProcess`, `HarnessEvents`, `FakeHarnessCli`). `NativeProcessLiveness` became `ProcessHandleLiveness`, because `HarnessProcessLiveness` is the interface it implements. Shipped names changed with no migration: `native-event` → `harness-event`; manifest keys `nativePid`, `nativeStartedAt`, `nativeCommand` → `harnessPid`, `harnessStartedAt`, `harnessCommand`. Kept: native-code senses, the WI-14 native tier, shipped rule and skill prompts, and the recorded captures under `docs/changelog/1.0/captures`. The `artifact-runners.bats` fake `claude` now prints the `idle` state that the Claude turn boundary needs; the race fix had broken it, and that integration did not run bats. Every integration now runs the bats files. |
| Pouch scopes for the optimizer and bridge (WI-13 follow-up) | Done (commit `f58ee27e5`; full build passed with 1144 tests, 0 failures; the five bats files passed, 33 of 33). `Host` and the seam constructors are gone. `OptimizerCommandScope` is one scope per `prompt-optimizer` command: `MainOptimizerCommandScope` holds the shared production services (Git forks, cap calibration, system clock, version launcher, mapper, random), `ClaudeOptimizerCommandScope` and `CodexOptimizerCommandScope` add the sessions and host interrupt signal and are the only optimizer classes that read the environment, and `TestOptimizerCommandScope` supplies fakes. `HarnessRunnerScope` became abstract with `MainHarnessRunnerScope` and `runner.fakes.TestHarnessRunnerScope`, which own the bridge's clock and heartbeat ticker; the package-private `serve` overload is removed. `TestabilityAuditTest` allows environment reads in harness production scopes (`design.md` §10 T4). Tests at or over 1 s, none touched here: `EditorInventoryTest.reportsHeartbeatForSilentCommand` 2.55 s, `ExecutableFilesTest.retriesTextFileBusyStart` 2.12 s, `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` 1.83 s, `EditorPatchWorkflowTest.capturesPatchForEarlierFileVersion` 1.48 s. |
| Authority warnings (WI-6/WI-9/WI-12/WI-13 follow-up) | Done (commit `3c18e9870`; full build passed with 1186 tests, 0 failures; the five bats files passed, 33 of 33). The prompt author's final response is one JSON object `{"needed_edits":[{requirement, path, operation, target?}]}` (`optimizer/prompt-author/report.schema.json`, H23 extraction); a malformed report or an unknown requirement id is a prompt author infrastructure failure. CAT resolves each path from the fork and calls `Authority.diagnose`; any warning becomes a pending `AUTHORITY_WARNINGS` decision after the attempt's permitted edits are submitted. Choices: `choose <id> use_authorized_source`, stop, and the `revise` command; WI-45 removes the former `authorize_target` choice. Status JSON shows `authorityWarnings[]` and a summary line. Proof: `DriverTest.authorityWarningTrace` runs from the report through a restart to delivery. |
| `workflow-temp` output (user request, outside the WIs) | Done (commit `ebc375ded`; `InvocationDirectoryTest` 11/11, full build passed, the five bats files 33/33). `create` and `handoff` print the bare absolute path, `delete` prints nothing, `--help` states each command's output, and a usage error prints the help to standard error. `workflow-temporary-resources.md`, `failure-prevention.md` and both `skill-env.md` files state each command's output and name `.cat/work/temp` instead of the retired `.cat/temp`. WI-14 merged onto it cleanly; the new skill does not describe `workflow-temp`. |
| WI-14 | Done (integrated; full build 1193 tests, 0 failures; bats 32/32), except the session contract tests and the rest of the native-tier removal, which moved to WI-15. The user chose D13 over goal.md's "via `cat:prompt-optimizer`" wording and approved the rule diff (2026-10-01). Skill replaced with the §8 loop; D13 applied to both rules with the subagent reporting clause kept; new rule scenario `failure-regression-promotion/correct_without_campaign` (main-agent and subagent sides); old-skill citations updated; `StagedSourceNativeTest` became `StagedSourceRunnerCopyTest` (unit, both harnesses); `ClaudeRunnerCliNativeTest` and `CodexRunnerCliNativeTest` deleted (assertions covered by E8 and `HarnessCapacityTest`); the opt-in `prompt-evaluation` bats test removed, since that launcher and contract are retired; `TestabilityAuditTest` scans once per class (slowest 0.54 s). Reference audit: no `prompt-evaluation` hit; every `prompt-optimizer` hit names the new skill or launcher, or is a historical capture. Still present until WI-15's contract tests: `ClaudeSessionsNativeTest`, `CodexSessionsNativeTest`, the `native` profile and its properties (§9 gate 3 open). Pre-existing tests over 1 s: `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` 2.2 s, `EditorInventoryTest.reportsHeartbeatForSilentCommand` 1.6 s. |
| Runtime and interrupt-seam follow-up (WI-15 prerequisites) | Done (full build 1185 tests, 0 failures; bats 32/32; `FinalizationTest` closed in commit `0d68e24f3`, below). `ClaudeProcess` takes an `InterruptSender` (production sends SIGINT as before); `FakeHarnessCli(handler, afterInterrupt)` accepts an interrupt and replays the given lines (`ClaudeProcessTest.deliversInterruptThroughSender`, R-E15; its after-interrupt segment is synthetic until WI-15 recordings replace it). `EditorInventoryTest.reportsHeartbeatForSilentCommand` 1.4 s to 0.01 s. `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` (commit `0d68e24f3`): the replay classes remember reconstructions, path states and chains keyed on immutable object IDs, and read each commit range once, so the test starts 227 git processes instead of 471. It runs 0.77–0.87 s warm and 1.02 s in the full build (1196 reports, 9 of them stale; 0 failures), and its Javadoc records why it needs the real chain (`design.md` §10 T7). Other test at or over 1 s in that build: `ClaudeSessionsTest.givesUpOnARunnerThatIgnoresAnInterrupt` 1.30 s (varies 0.5–2.5 s between runs, like its Codex twin), left to WI-15's contract-test rewrite. Correction: WI-14's "1193 tests" counted 9 stale reports from removed modules; the true count was 1184. |
| WI-15 | In progress. Codex first again, then Claude (user ruling, 2026-10-20, after Codex's usage limits reset; it reverses the Claude-first ruling of 2026-10-19). The Claude E5 campaign `20261020T145958Z-bf57fc` is scoped `weakest_cell` (user ruling, 2026-10-20). The installed Codex build is that of `068985fd9` (installed 2026-10-07T22:34Z); the installed Claude build is that of `7d0860c1c` with the uncommitted rule and optimizer changes (installed 2026-10-10T18:33Z, user approval, to pick up the plugin testing rule). E8 on the build of `cf98b363f` (2026-10-06, `~/cat-client/worktrees/e8-codex4-{a,b}/receipts/`) failed: two campaigns at a ceiling of 16 held up to 16 permits each while the kernel listed at most 7 slot locks (WI-57). Install the Claude build only when Claude starts. Fixture pilot: a motto defined only in a handbook was not deficient (12/12 passed); contradictory instructions ("close with the motto" beside "no slogans or sign-offs") were deficient (Claude 4/6, Codex 0/6 passed); the prompt-author-style fix ("apart from the motto, no other slogans...") reached Codex 6/6 with correct answers. This contradiction fixture is the E1 deficient fixture. Fixtures `deficient`, `easy`, `link` and four `unsupported` variants are under `optimizer/e2e/` (README lists each one's use). E1 Codex, dirty-base refusal: passed through the installed skill (`start AGENTS.md` refused on the uncommitted `docs/handbook.md`; no campaign created). Evidence in `.cat/work/temp/e1-codex-15400782742439386847/receipts/`, kept until the WI-15 receipts are recorded. In that run, and in every later Codex run, the agent first ran `"${CAT_PLUGIN_ROOT}/client/bin/prompt-optimizer"` literally (exit 127, the variable is unset) and then found the installed path itself; `skill-env.md` is in the session's context but was not applied. Resolved by the skill launcher paths row below (skill-relative paths, user ruling). E4 Codex: passed. Each of the four `unsupported` fixtures produced a proposal naming its outcome (unsupported instruction source R2–R3; unsupported output R3–R4; unsupported test interaction R4–R5; ineligible trial effect R3–R4); `confirm` recorded that outcome with `stop` as the only choice and dispatched no trial; `stop` cancelled each campaign; every base stayed clean and `~/acme-support` was never created. Evidence in `.cat/work/temp/e4-codex-11033726973909551249/*/receipts/` (events, final message, confirm and stop JSON, Codex 0.159.1). Minor findings, resolved: the summary's "Waiting for" line repeated "certification is unavailable: certification unavailable: …" (fixed in `7ab90ec8e0`); after `stop` the status was `awaiting-user` with only `resume`, and a stopped campaign now reports `interrupted` with `resume` and any keep or accept choice, which `StatusReportTest` checks. E7 Codex, first run: `start` refused the link fixture with "missing target" although the external target existed, because the fork recorded external link targets only after the contract author named them (commit `97b9c226b` fixes it: creating or extending the fork records the existing outside targets of recorded links, transitively; full build 1201 tests, 0 failures; Codex build reinstalled). E7 Codex link report: passed on the rerun; the proposal lists `docs/handbook.md -> shared/handbook.md` (inside the project, outside prompt sources) and `docs/reply-rules.md` -> its external target (external, read only) and states the staging consequence. Evidence in `.cat/work/temp/e7-codex-4281424245095601688/receipts/`. Minor, accepted: on the intentionally contradictory fixture, the "Certifiable scope" line and a limitation disagree on whether certification is unavailable. E7 Codex delivery, 2026-10-02 (`~/cat-client/worktrees/e7-codex2/receipts/`): the prompt author's authority warning named the external `reply-rules.md`; `choose authorize_target` recorded it, and three later attempts edited it, but candidate 2 (an `AGENTS.md`-only edit made before the authorization) was certified (four cells, 61/61 to 164/168) and delivered with `externalChanges: []`, so the delivery of an external edit is still unobserved. `start docs/reply-rules.md` is refused because the link resolves outside the project (`~/cat-client/worktrees/e7-codex3/`, no campaign created); the user then ruled that external files are read-only (2026-10-05), so E7 reruns under its new definition after WI-45. E6 needs both harnesses and moves to the Claude phase. The trial model and effort question became trial profiles (user ruling, 2026-10-01): the certification-dependent scenarios (E1 certification, E2, E3, E5, E7 delivery, E8) wait for WI-16–WI-18, then the Codex build is reinstalled. The exit-127 cause was found: Codex tool shells define neither `CODEX_HOME` nor `CAT_PLUGIN_ROOT` (probe: only `CODEX_CI`, `CODEX_SESSION_ID`, `CODEX_THREAD_ID`, `CODEX_VERSION`, `HOME`), so `skill-env.md`'s derivation could not work; fixed by the plugin-root follow-up below. Codex session contract tests (2026-10-02): `CodexSessionsContractTest` runs six `SessionPortContract` checks against the real `CodexSessions` over `FakeHarnessCli` replaying complete, untrimmed codex-cli 0.159.1 streams from the E1 run (trial, judge, prompt author, two contract author turns) and the recorded child capture; paths and identifiers are neutralized. The interrupt checks replay the interrupted trial turn recorded in E2 (`interrupt-turn-1.jsonl`, complete, 36 lines); `CodexSessionsNativeTest` is deleted, and the `native` profile and its properties stay until the Claude recordings exist (full build 1702 tests, 0 failures; bats 32/32). E2 Codex: passed 2026-10-02. ESC in an interactive Codex host (`tmux -L catprobe`) mid-slice ended the in-flight trial turn `interrupted` 0.9 s later and the CAT run process exited 1.0 s after ESC, releasing its capacity slot; after resuming from the host, the interrupted trial gained one `INTERRUPTED` infrastructure attempt and exactly one outcome for the same test case, the earlier outcome was unchanged, and no certification attempt had started (attempt 0 throughout). Evidence in `~/cat-client/worktrees/e2-codex/receipts/`. E5 Codex: passed 2026-10-02 on the rerun. Trials used the host shell tool and child subagents, the judge's verdicts cite those events, and outcomes were recorded without a `MEASUREMENT_CHANGED` pause; Codex trials cannot create child worktrees, so that part is a Claude check. Evidence in `~/cat-client/worktrees/e5-codex2/receipts/`. E1 Codex on gpt-6-luna/low (user request): `not certified: plateau` after 6 trials, because the installed build predated WI-24 and the deficient fixture's conflict reached the trials; rerun after WI-25 is installed, in `~/cat-client/worktrees/`. E5 Codex, first run: paused `MEASUREMENT_CHANGED` on `PROMPT_SOURCES` after 3 trials, because the value listed hooks of later events (a subagent's start) that run only when a trial takes that action; fix in progress (only session-start sources count); rerun afterwards. Codex rerun on the build of `9c282057d` (installed 2026-10-04, `install-codex.sh` exit 0; full build 2126 tests, 0 failures, none at or over 1 s), in `~/cat-client/worktrees/e1-codex/receipts/`: the dirty-base refusal passed again (no campaign created); the proposal listed four conflicts between `motto-ending` and `no-slogans`, `question-only`, `no-taglines` and `no-other-boilerplate`; `confirm` was refused naming each one; after `revise` with the recommended resolution the proposal listed none, and `confirm weakest_cell` started development in four `gpt-6-luna/low` cells (at least 1944 sessions). After one trial, a trial's `account/rateLimits/updated` event reported the served codex-lb account's weekly window at `usedPercent` 100 (with credits, so the request succeeded; the other account had 91% left), and the throttle holds admissions until the reported reset, 2026-10-09T21:13:08Z. The user kept the hold and left codex-lb unchanged (2026-10-05), so the Codex trial scenarios resume after that reset. Usage was reset early (user, 2026-10-05), but the stored hold cannot learn of it, so E1 restarted in `~/cat-client/worktrees/e1-codex2` on the build of `99cb449f5`; before its first trial, codex-cli updated itself from 0.160.0+39 to 0.160.0+41 and the campaign paused `MEASUREMENT_CHANGED` on `HARNESS_VERSION`, as material conditions require; `new identity` continued it. E3 Codex: passed 2026-10-05 (`~/cat-client/worktrees/e3-codex/receipts/`). After the first prompt author attempt (candidate 1), the user's stop (`cancel`) left the base clean and offered `resume` and `accept without certification`; choosing the latter ended the campaign `not certified`, delivered the `AGENTS.md` edit as an uncommitted working-tree change labelled `not certified`, and kept the campaign's files. E1 Codex, 2026-10-05 (campaign `20261005T095251-e65173`, gpt-6-luna/low, `~/cat-client/worktrees/e1-codex2/receipts/` w39–w41): candidate 6 reached development counts of 20/21, 17/17 and 20/20, then `not certified: plateau`. Its one failure was an intermittent test case (the water-formula question, 2 of 3 passes, smoothed p = 0.6); each of the round's three attempts passed it 4 of 4 and every regression run, but 0.6^4 ≈ 0.13 is not below 0.10, so none was promoted and the round established a plateau before the candidate had 100 outcomes. One trial in that run first needed a `retry` after a burst of load-balancer `403` and `upstream_error` responses on both accounts (15:04–15:09 UTC). `stop` cancelled the campaign with nothing delivered and the fixture base clean, which shows E3's plateau-and-stop case in Codex; E3's uncertified acceptance is still to run. The user ruled that a round without a promotion keeps sampling unless a cell has more than 2 failures (WI-46). E1 Codex on that build, 2026-10-05 (campaign `20261005T182053Z-4f70a4`, gpt-6-luna/low, scoped workspace-write sandbox, `~/cat-client/worktrees/e1-codex3/receipts/`): after `revise` with the recommended resolution and `confirm weakest_cell`, 15 rounds and 56 prompt author attempts led to candidate 7 with development counts of 37/37, 56/57, 35/35 and 66/69, then `not certified: plateau` because `conversation_consistency` had 3 failures. Two are clean model failures (turn 1 skipped the handbook read and omitted or invented the motto); the third turn completed after four codex-lb HTTP 403 stream reconnects and replied "[Unable to read motto]" without reading the file. Attempts that passed every run (up to 23/23) were not promoted because each targeted an intermittent test case whose four-pass streak is still likely under the working candidate. E1 is not passed on Codex. Open product questions put to the user: whether a turn that completes after provider reconnects is an outcome or an infrastructure attempt, and whether E1 reruns on a more capable trial profile; the campaign waits at the plateau decision. | E1 Codex, third run (2026-10-05): stopped by user ruling. Rulings: a turn that completes after provider reconnects is a result (`design.md` §3.2); E1 stays on gpt-6-luna/low and reruns from the deficient fixture once WI-47 and WI-48 are installed, after E7 ends. Failure analysis of candidate 7: all 228 passing first replies read `docs/handbook.md` first; 4 of the 5 failures ran no command and answered from memory (the square-root-of-eighty-one question failed 3 of 29), and 1 mistyped the `read-file` path (exit 127) and answered without retrying. The stop was sent organically; the host first tried `confirm <campaign> stop` (refused), and on the second request ran `cancel`, which ended campaign `20261005T182053Z-4f70a4` `not certified: plateau` with the fixture base clean at `33cdcd9` (`~/cat-client/worktrees/e1-codex3/receipts/stop2-*`). E7 Codex, 2026-10-05 (campaign `20261005T215239Z-452cd2`, `~/cat-client/worktrees/e7-codex4/receipts/`): after `confirm weakest_cell`, the prompt author reported a needed edit to the external `reply-rules.md` and CAT raised an authority warning naming the requirement, `docs/reply-rules.md`, its external target and `external_target_read_only`, offering only `use_authorized_source`, stop and revise. After `use_authorized_source`, each later round reported the same warnings again (`answer_only_question`, `exact_motto_closing`), with up to three overlapping decisions pending: 17 prompt author attempts, 0 candidates. The user's stop ran `cancel`; the external file's sha256 stayed `476f03e1…7d13a` throughout and the base stayed clean at `171de2a`. E7 reruns after WI-49. The host also saw the `confirm` usage line spell the scopes with hyphens, which the CLI refuses; fixed in `1e2742198`. E7 Codex rerun, 2026-10-05 (campaign `20261005T225952Z-b214e7`, `~/cat-client/worktrees/e7-codex5/receipts/`): the first contract author hit its time limit (WI-50); `start` again produced the proposal. After `confirm weakest_cell`, CAT raised the external-file authority warning once; after `use_authorized_source` it was recorded as answered and never raised again (WI-49 exit). Three prompt authors' handbook edits were rejected as `outside_prompt_sources` and paused the campaign (WI-51). On the WI-51 build, attempts 6 and 9 edited `shared/handbook.md` through the link and reached promotion trials (WI-51 exit). Candidate 1 (round 2, attempt 4, `AGENTS.md` only, 3/4 promotion runs) then went through rounds 3–5 (attempts 10–18, at most 2/8 runs) without a stronger candidate. The campaign ended `not certified: plateau` because `multi_turn_consistency@gpt-6-luna/low` had 3 failures: turn 1 answered from memory without reading the handbook and made up a motto, a model failure. The external file's sha256 stayed `476f03e1…7d13a` and the base stayed clean at `c3e6e4f`. E7's delivery check waits for the user's choice between accepting without certification and stopping. E1 Codex, fresh run on the build with WI-47–WI-51, 2026-10-06 (campaign `20261006T014153Z-3062d6`, gpt-6-luna/low, `~/cat-client/worktrees/e1-codex4/receipts/`): `start` listed the two expected conflicts; after `revise` with resolution 1 and the profile restriction, `confirm weakest_cell` started development in three cells. A 2-minute burst of upstream HTTP 403s on both codex-lb accounts (02:05–02:07 UTC) failed three concurrent prompt author sessions, and the campaign paused as an infrastructure failure; `resume` continued it. After 15 rounds and 59 prompt author attempts, candidate 7 reached 196/199, 100/101 and 105/106, then `not certified: plateau` because `direct_factual_answers` had 3 failures. Of the 5 failures, 4 are model failures (one mistyped the `read-file` path and gave up, three answered without reading the handbook); in the fifth, codex-lb returned `server_is_overloaded`, Codex emitted nothing, and the trial was stopped at its 60-second limit with no reply. E1 is not passed on Codex. The user chose to stop E1 (`20261006T014153Z-3062d6`) and E7 (`20261005T225952Z-b214e7`) without certification (2026-10-07). Each host ran `cancel`, which the installed build refused because the campaign files are format version 22 (it reads only 24), so neither campaign records the cancellation; no process runs for either, nothing was delivered, and the bases stay clean at `cff19bf` and `c3e6e4f` (`~/cat-client/worktrees/e1-codex4/receipts/stop1-*`, `~/cat-client/worktrees/e7-codex5/receipts/stop1-*`). E7's delivery of an external edit is still unobserved. E8 Codex: passed 2026-10-06 on the installed build of `affff51af`, which includes WI-57 (codex-cli 0.160.0+41, `~/cat-client/worktrees/e8-codex5-{a,b}/receipts/`). Two campaigns, each started with a maximum concurrency of 16, ran at once from 15:33 to 18:48 UTC over the shared Codex capacity of 16; the kernel never listed more than 16 locked slots and listed a lock for every held permit (WI-57 exit). Campaign b certified its four cells and delivered; campaign a stopped at a decision checkpoint (one cell at 198/203, below the 99% development threshold), which E8 does not need answered. E1 Codex, fifth run, 2026-10-19 (campaign `20261019T174325Z-6df84d`, gpt-6-luna/low, the installed v1.0 release, codex-cli 0.160.0+41, `~/cat/worktrees/e1-codex5/receipts/`): `start` now requires the target's prompt test configuration, so every end-to-end fixture gained one that lists no trial plugin (`7b50c60e60`). The dirty-base refusal passed again (no campaign created). The proposal lists the two expected conflicts, `exact_motto_closing` with `no_boilerplate` and with `question_only`, each with a recommended resolution. Because the host session loads CAT's rules and the user's rules, and trials load neither, the proposal also holds 11 requirements and 6 prompt tests derived from those rules, and lists the rules as files that load in the user's sessions but not in trials, as `design.md` §6.1 specifies. The campaign waits for the user's `revise` feedback. E7 Codex, sixth run, 2026-10-19 (campaign `20261019T175709Z-b1ce3f`, same build, `~/cat/worktrees/e7-codex6/receipts/`): the link report passed again; it lists `docs/handbook.md -> shared/handbook.md` (inside the project, outside prompt sources) and `docs/reply-rules.md` -> its external target (external, read only) and states the staging consequence. The proposal lists the `exact_team_motto` and `question_only` conflict, both sourced from the external file, with three resolutions, and, as in E1, 17 requirements from CAT's rules and the user's rules that trials do not load. The external file's sha256 is `476f03e1…7d13a`, as before. The campaign waits for the user's `revise` feedback. After WI-90 (`7bbce9101f`, Codex plugin reinstalled from that build), the sixth E1 Codex run (campaign `20261019T185225Z-6ba4c4`, `~/cat/worktrees/e1-codex6/receipts/`) proposed 4 requirements, all from `AGENTS.md`, and one conflict, `exact_motto_closure` with `question_only`, with resolution 1 (the motto as the sole exception) recommended; the seventh E7 Codex run (campaign `20261019T185226Z-c05d87`, `~/cat/worktrees/e7-codex7/receipts/`) proposed 5 requirements, all from `AGENTS.md` and the external `docs/reply-rules.md`, reported both links correctly, listed two conflicts of `exact_motto_closing` with `no_boilerplate` and with `question_only`, and left the external file unchanged (sha256 `476f03e1…7d13a`). The CAT and user rules appear only in the informational list of files that load in the user's sessions but not in trials. Under the user's authorization (2026-10-19), revise feedback chose resolution 1 for every conflict (the exact motto is the only exception), and both revised proposals list no conflict; E7's control source now deletes the linked `docs/reply-rules.md` (WI-91), and E1's revision needed WI-92. Neither is confirmed: each guard pilot stops after 8 control trials of its first test, because the pilot runs up to 8 sessions at once, the load balancer sends every `gpt-6-luna` turn to one account, and that account answers `turn_rate_limit_exceeded` about 20 turns into a burst. A rerun of `start` keeps only a pilot whose trials all finished, so it restarts and stops at the same point (E1 twice, E7 three times). WI-93 makes the pilot wait out the hold and rerun, as `design.md` §3.5 already requires. On the WI-93 build (Codex plugin reinstalled) both pilots decided all 20 control trials of each test: E1's `factual_answer` 20/20 and `brief_explanation` 19/20, E7's `factual_answers` 20/20 and `concise_explanations` 19/20; both campaigns were confirmed with `weakest_cell` (user authorization). E1's contract still lists the stopped pilots as limitations, because the contract author's prompt does not say that pilot results are not limitations; fix after these campaigns. During development on 2026-10-19 the disk filled (a Bazel cache of another project): E7's commands failed with `No space left on device` at 18/18 and 17/17, and E1 paused on a trial without an outcome at 12/12 and 17/18. After space was freed, the Codex plugin was reinstalled from the build of `2bfde98c8c`, E1 was told to retry and E7 to continue. The Claude plugin was reinstalled from the build of `ab881c6ce1` (user request, 2026-10-19). E1 Claude, first run, 2026-10-19 (campaign `20261019T231053Z-b98fe3`, Claude Code 2.1.304, `~/cat/worktrees/e1-claude1/receipts/`): the dirty-base refusal passed (the launcher named `docs/handbook.md`; no campaign created). The first `start` failed in the contract author session (WI-95); on the build of `0cf58de40a` the proposal held 4 requirements from `AGENTS.md` through `CLAUDE.md`'s import, two prompt tests, and the `closes_with_motto` and `no_boilerplate` conflict with resolution 1 recommended; both guard pilots decided 20 trials in `haiku/default`, every guard at 20/20. Under the user's authorization, revise feedback chose resolution 1; the revised proposal lists no conflict, and the campaign was confirmed with `weakest_cell`. The first Claude E1 campaign was stopped on 2026-10-19 (user ruling): its 84 trial sessions received `CLAUDE.md` without the `AGENTS.md` that it imports, so it measured an empty prompt; a fresh Claude E1 run follows WI-97 to WI-102. E1 Claude, second run, 2026-10-20 (campaign `20261020T011909Z-c9f914`, Claude Code 2.1.304, plugin built from `2e3e9dfa85`, `~/cat/worktrees/e1-claude2/receipts/`): the dirty-base refusal passed; the proposal held the conflict with resolution 1 recommended and both guard pilots decided 20 trials in `haiku/default`; revise feedback chose resolution 1 and the campaign was confirmed with `weakest_cell` (user authorization). It paused after one trial (0 of 1) with the measurement conditions changed (`prompt_sources`), and two defects showed. First, the trial loaded the project's own `CLAUDE.md` from a directory above its run directory, read the project's `docs/handbook.md` and closed with its motto instead of the workspace's; WI-103 fixes this. Second, Claude Code 2.1.304 reports a different built-in skill list from one session to the next: of the run's 262 nested sessions, 130 list 17 built-in skills, 63 also list `deep-research` and `workflow-authoring`, and 69 list those and 13 `anthropic-skills:*` skills, with the same settings and for either model, so `prompt_sources` changes without any change to the prompt system; this needs the user's decision. E4 Claude: passed 2026-10-20 (Claude Code 2.1.304, the Claude plugin installed before WI-108, `~/cat/worktrees/e4-claude/*/receipts/`). Each of the four `unsupported` fixtures produced a proposal naming its outcome (unsupported instruction source and ineligible trial effect; unsupported output; unsupported test interaction; ineligible trial effect); `confirm weakest_cell` recorded it with `stop` as the only choice and dispatched no trial; `stop` ended each campaign with nothing delivered; every base stayed clean and `~/acme-support` was never created. Minor: with two unavailable conditions, the summary's `Outcome` names the last one recorded and `Waiting for` the first pending one. The guard pilots ran under Claude Code's `AGENTS.md` fallback (WI-111). E6: passed 2026-10-20 (codex-cli 0.160.0+41, Codex plugin from the build of `bebba77b8e`, `~/cat/worktrees/e6-claude/receipts/`). In the project of the stopped Claude E4 campaign `20261020T054250Z-4ab3bb`, a Codex session asked to resume it read `status`, and when asked to try anyway ran `prompt-optimizer choose 20261020T054250Z-4ab3bb resume`, which was refused (exit 1) because the campaign belongs to its Claude session, naming `claude --resume` for that session; every campaign file kept its sha256 and the base stayed clean. Claude session contract tests (2026-10-20, commit `86886a5bbd`): `ClaudeSessionsContractTest` runs the `SessionPortContract` checks except the running-turn interrupt, plus one session per role and the observed model, usage and rate-limit windows, against the real `ClaudeSessions` over `FakeHarnessCli` replaying complete, untrimmed Claude Code 2.1.304 streams from the second and third E1 Claude runs (a trial, a two-turn trial, judge, triage, prompt author and contract author); paths and identifiers are neutralized. `ClaudeSessionsNativeTest` keeps only the two interrupt checks until Claude E2 records an interrupted turn; then it is deleted with the `native` profile and its properties (full build 2985 tests, 0 failures, 80 s; `TestabilityAuditTest.onlyMainReadsEnvironment` took 1.14 s). Claude interrupt contract test and native-tier removal (2026-10-20, commits `97bb298579` and `5c3e5fae33`): a real interrupted Claude turn already existed, so it did not wait for E2. In the first E1 Claude run, CAT asked a held-out judge turn (Claude Code 2.1.304, `--model opus --effort high`) to interrupt while it was thinking after a tool call, and Claude answered with a `control_response` and ended the turn with an `error_during_execution` result. That 57-line turn is `interrupted-judge-turn-1.jsonl`, neutralized with the same substitutions, which reproduce the seven earlier recordings byte for byte. `RecordedClaude` holds back a turn's lines from that answer onward until the adapter writes an interrupt request; `ClaudeSessionsContractTest.interruptEndsARunningTurnAsInterrupted` (R-E15) runs the shared check over it and asserts exactly one interrupt request. `ClaudeSessionsNativeTest` is deleted. Its other check, an interrupt while a background child runs, has no recording; its live evidence is E2 in Claude (R-E15) and the E5 child scenarios (R-E14, premise P7). The `native` profile, `cat.test.groups`, `cat.test.excludedGroups`, `cat.native.harness` and the TestNG group wiring are removed. `cat.session.temp` stays: about 100 tests that start no harness keep their fixtures there, inside the session's temporary directory during a harness session. Gate 3 audit: no `native` group or profile remains, and the test-side `ProcessBuilder` calls start only shells, `git`, a Java fixture or packaged hook launchers, never a runner, harness or model. Full build 2991 tests, 0 failures, 9+1 skipped, 49 s; `DriverTest.attemptLoadsOnlyReceivedFiles[0]` took 1.03 s, the class's first-run class loading (1.6 s for that call and 0.03 s for the others when run alone; 0.85 s in the previous build). E5 Claude, 2026-10-20 (campaign `20261020T145958Z-bf57fc`, Claude Code 2.1.304, the plugin built from `3ac240d4c4`, `~/cat/worktrees/e5-claude/receipts/`): the project's `CLAUDE.md` imports an `AGENTS.md` that asks the agent to read `docs/facts.md` with a shell command and to start one subagent per topic, each in its own Git worktree. The proposal holds 5 requirements and three prompt tests (`two_topic_question`, `facts_question`, `general_question`), whose assertions check the Bash read, the `Agent` calls and each subagent's worktree; it lists no conflict and no unavailable condition. The guard pilots decided 20 control trials each in `haiku/default`; both `no_subagent_started` guards passed 20/20, and `general_question`'s `question_answered` guard was decided in no trial because `read-in-full` failed first in every control trial. The campaign waits for the user's certification scope. E1 Codex: passed 2026-10-21 (campaign `20261020T182700Z-666698`, gpt-6-luna/low, codex-cli 0.160.0+41, the plugin built from `112b263783`, `~/cat/worktrees/e1-codex8/receipts/`). Under the user's authorization, revise feedback chose resolution 1 for every conflict and `confirm weakest_cell` started development; the `confirm` turn failed on a codex-lb outage ("No available accounts ... all upstream accounts are unavailable"), and a later resume continued the campaign. After 4 rounds and 12 prompt author attempts, candidate 4 was certified at alpha 0.020 in both weakest cells, `direct_fact@gpt-6-luna/low` and `motto_requested_fact@gpt-6-luna/low`, each 147/148 with a lower bound of 0.950 and proven better than the incumbent (0/50 and 14/50); delivery wrote the revised `AGENTS.md` and the prompt tests under `.cat/tests/AGENTS/` to the working tree for Git review and removed the campaign's files from `.cat/sessions/`. The status summary now also shows a hold on the harness's shared capacity (commit `d7b4d490b8`): a campaign read "throttle no hold" while another campaign's hold stopped its trials. E7 Codex, ninth run, 2026-10-21 (campaign `20261020T182655Z-7b0150`, gpt-6-luna/low, codex-cli 0.160.0+41, the plugin built from `112b263783`, `~/cat/worktrees/e7-codex9/receipts/`): the link report lists `docs/handbook.md -> shared/handbook.md` (inside the project, outside prompt sources) and `docs/reply-rules.md` -> its external target (outside the project, external target read only). Under the user's authorization revise feedback chose resolution 1 and `confirm weakest_cell` started development (the `confirm` turn itself ended on a codex-lb outage and a resume continued it). The formal attempt paused with `keep improvement` offered, and the user chose it: delivery wrote the improved `AGENTS.md` to the working tree, outcome "improved, not certified" (`brief_factual_answers` 92/92, `brief_explanations` 257/262, frozen judge), and the external `reply-rules.md` stayed byte-identical (sha256 `476f03e1…7d13a` before and after). As the design requires for an uncertified outcome, the campaign's files stay under `.cat/sessions/`. No prompt author asked to edit the external file in this run, so every report carried an empty `authority_warnings` list; the read-only warning's live evidence remains the 2026-10-05 runs (`external_target_read_only`, offering only `use_authorized_source`, stop and revise) and `AuthorityWarningsTest`.
| Final gates (§9), interim check on `ec7dc5782` | 2026-10-05; each gate is rerun on the final tree. Gate 1: full build passed (2123 tests, 0 failures). Gate 2: every §3 ID has a test tag except R-I1, whose proof is the Claude-phase WI-15 receipt; no tag names an unknown ID (`DriverTest` named `R-G5`, now R-M1, R-M4 and R-E18). Gate 3: unmet; `ClaudeSessionsNativeTest` and the `native` Maven profile remain until the Claude recordings exist; `ProcessFixture` starts only a Java fixture process. Gate 6, G9 check: the full build passed with `HOME=/nonexistent-g9` and `-DargLine=-Duser.home=/nonexistent-g9` (Maven kept its repository through `MAVEN_USER_HOME` and `maven.repo.local`); every test-class report records `user.home=/nonexistent-g9`, and the directory was never created. Gates 4, 5, 7 and 8: unrun. |
| Final gates (§9), interim check on `7d94f99cb4` | 2026-10-17; each gate is rerun on the final tree. Gate 1: full build passed (2881 tests, 0 failures, 10 skipped, none at or over 1 s, 74 s). Gate 2: every §3 ID has a test tag except R-I1, whose proof is the Claude-phase WI-15 receipt; R-W13 was untagged until `addac0e182`; no tag names an unknown ID; the E receipts wait for WI-15. Gate 3: unmet, as on 2026-10-05: `ClaudeSessionsNativeTest` and the `native` Maven profile remain until the Claude recordings exist. Gate 6: the G9 check passed on `addac0e182` (277 test-class reports record `user.home=/nonexistent-g9`, and the directory was never created); `CleanupTest` and two `OptimizerCoreTest` cases ran real Git against §8 until `7d94f99cb4` moved the delivered-path diff behind `Forks`; no other test outside the §8 list constructs Git. Gate 7: unmet. `design.md` §11 and §12 hold every WI-0 result except P12 and P13, which stay open until WI-15's E1 runs a trial per Claude alias and Codex model; §13 records the user rulings through 2026-10-16 (D79), and the other rulings in this plan are process rulings or are written into the sections they change; §2 shows WI-15, WI-70 and WI-71 not done. Gates 4, 5 and 8: unrun. |
| Final gates (§9), interim check on `6599025514` | 2026-10-20; each gate is rerun on the final tree. Gate 1: full build passed (2991 tests, 0 failures, 10 skipped, none at or over 1 s; 46 s; the G9 clean build below took 75 s). Gate 2: every §3 ID has a test tag except R-I1, whose proof is the Claude-phase WI-15 receipt; no tag names an unknown ID; every tagged test class ran and passed (the two shared store and overlay contracts through their file and in-memory subclasses); the E receipts wait for WI-15. Gate 3: passed. `ClaudeSessionsNativeTest`, the `native` group and its Maven profile are gone, and test-side processes start only shells, Git, the Java process fixture or packaged hook launchers. Gate 6: passed. The clean build on `137651e280`, whose tree differs only in that `DriverTest` case, ran with `HOME` and `-DargLine=-Duser.home=/nonexistent-g9`, all 281 test-class reports record that `user.home`, and the directory was never created. A Git shim on `PATH` logged 487 real Git calls, all in fixture directories of the §8 tests (`ForkTest`, `DeliveryTest`, `InventoryScannerTest`, `GitTest` and the git-replay tests listed there); `TestabilityAuditTest` passed its field, sleep, environment and Git-construction checks; outside `CapCalibrationTest`, tests only construct `CapCalibration` values. `DriverTest.attemptLoadsOnlyReceivedFiles[0]` took 0.81 to 1.03 s across three builds (1.67 s run alone), because it ran the whole slice to check only that the attempt was validated; it now stops once the validated-attempt count rises (0.42 s alone). Gate 7: unmet; §2 shows WI-15, WI-70 and WI-71 not done. Gate 4: unrun. Gate 5: unrun; an interim map of `design.md` §14 onto §3 and the E scenarios found every unit claim of G1–G10 behind a §3 ID, and these end-to-end claims with no planned E evidence: in G1, keeping the incumbent instead of a regressing certified candidate removes the campaign, and an interrupted removal completes on the next command (`CleanupTest` only); in G7, a judged file-effect case, a prompt test's fixtures with a generated value in the trial workspace, a failed built-in tool deciding its trial with and without the judge, control trials paired with their formal trials' test cases (only when `p_min` is below 0.95, which no E fixture uses), and a trial with a read-only service credential and one with a disposable fixture both eligible (E4 covers only the refusals). Waiting for the user's choice between new E fixtures and accepting the unit proofs. Gate 8: unrun; an interim search of `client/`, `docs/` and `.cat/` found five test Javadocs and one test name that pointed at the design for their expected values, now stated directly (`752273c3d7`); no other reference to `design.md`, this plan, a section number or a WI, D or gate ID remains outside the `// Requirement:` tags. |
| Current-tree build, 2026-10-20 (`84ecec1d7f`) | Full build passed with the Git shim on `PATH` in 52 s (2403 + 244 + 299 + 56 tests, 0 failures, 10 skipped). Only `EditorTest.combinesSourcesInOneTopicOutput` took 1 s or more (1.24 s under the full build's load; 0.65 s run alone), so its Javadoc needs no runtime reason. New model-free evidence for the §14 G6 and G7 unit claims: `DriverTest.hostInterruptResumesAttempt`, `DriverTest.trialsReceiveTheirFixtures` and `RunnerSessionsTest.passesGeneratedFixtureToRunner` (`3ee7d2ebf7`). |
| Release v1.0 and installed plugins | 2026-10-05. The release run on `49e14f4e6` failed: the Linux uploads hit the Actions artifact storage quota (all 438 old artifacts, 47.5 GB, were deleted at 14:07 UTC; usage is recalculated every 6 to 12 hours), and on macOS two `read-file.bats` comparisons assumed GNU `stat` and `sha256sum` on the host, fixed in `005767630` and passing under a PATH with only BSD `stat` and no `sha256sum`. Full build on `005767630`: 2123 tests, 0 failures, none at or over 1 s. The Codex and Claude plugins were reinstalled from that build's linux-x64 archives; `plugin-info` reports version 1.0, linux-x64, for both. The tag is pushed again once the quota allows uploads. |
| Plugin root and harness home (WI-15 finding, R-I5) | Done (full build 1210 tests, 0 failures; bats 32/32). `ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE` is replaced in plugin-tree direct rules and their includes; project and user rules keep it unchanged, because the loader cannot tell a quotation from a use and rejecting would inject no rules. Lazily read `rules/include/**` files are not rendered; only `skill-env.md` uses the directive. Remaining duplicate derivations, out of scope: `CodexRunner.codexHome` and `ClaudeRunner.claudeConfigDirectory` fall back to a relative `.codex` or `.claude`; the Codex `claude-runner` skill lets a local `CAT_CLAUDE_MARKETPLACE_ROOT` override its derived path, which `.cat/rules/include/environment.md` forbids. Tests at or over 1 s: `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` 1.02 s (reason in its Javadoc). Verified after reinstalling Codex (commit `52ceefeec`): a Codex session asked to run `${CAT_PLUGIN_ROOT}/client/bin/workflow-temp root` ran the installed absolute path on its first command, exit 0, with no exit-127 call. Per the user's ruling (2026-10-01: Codex sets no custom environment variables; derive values in Java or state them through the environment rule), the session hooks render `<!-- cat:plugin-root -->` in plugin rules into the absolute plugin root, both `skill-env.md` files tell the agent to write that directory wherever a skill writes `${CAT_PLUGIN_ROOT}`, and `CodexGetHistory`/`ClaudeGetHistory` default to `HOME/.codex` and `HOME/.claude`. |
| P14 effort probe | Done 2026-10-01; P14 holds (`design.md` H27). First run inconclusive (H26: deficient 0/20 and fixed 20/20 at every effort). Rerun on an intermediate-pass-rate variant (the two rules in reverse order) at user request, 20 trials per effort on `gpt-6.1-sol`: low 5, medium 8, high 16, xhigh 19 passed, all answers correct; the pre-stated 2×4 exact test rejects equal rates, p ≈ 2.2 × 10^-6. Effort stays in the profile set. A second rerun with a rule without a conflict (user request; `design.md` H28): `gpt-6.1-sol` was at the ceiling (40/40 pilot trials at low), so the run used `gpt-6-luna`: low 4, medium 6, high 13, xhigh 15 of 20, p ≈ 0.0006, mostly through whether the model read the handbook. A rerun against a partly optimized real rule (for example `write-clearly.md`, which needs the judge) waits for WI-17. Probe directories deleted after recording. |
| P15 single-cell development (weakest model, lowest effort) | In progress on the Codex rule `read-file.md` (named `complete-file-reads.md` until 2026-10-07) through the probe harness, not through the optimizer (user ruling, 2026-10-03: a clean run on this rule). The earlier campaign on this rule does not count: its variants were tuned after Luna medium and Sol failures. Protocol, fixed before development: (1) The incumbent is the rule's text on v1.0 as delivered (without Design Goals); the tasks are the probe's long read, lazy load and search and, from p15-9 on, a no-file question, scored by `score.py`. (2) Models: `gpt-6-luna`, `gpt-6-astra` and `gpt-6.1-sol`; older models, `gpt-6-sol` included, are left out (user rulings, 2026-10-03). Efforts: `low` to `max`; `ultra` is `max` reasoning plus automatic task delegation and is not tested (user rulings, 2026-10-03). That is 15 cells. (3) Baseline: the incumbent at `low` on each model, 10 runs per task; the weakest model has the lowest pass rate (ties: more blocking failures, then `gpt-6-luna`). Result: `gpt-6-luna` 26/30 (4 long-read failures: two accepted a truncated read, one missed the line cut by the truncation, one re-read 4 lines), `gpt-6-astra` and `gpt-6.1-sol` 30/30. The development cell is `gpt-6-luna` at `low`. Before this result the scorer was fixed to credit a re-read whose numbering restarts (`tail -n +N \| cat -n`) or carries a composite prefix; it had counted such delivered text as missing, and rescoring 814 earlier runs changed no result. (4) Development uses only that cell, with the default tool output limit; every edit answers a failure seen in that cell during this campaign; a candidate first passes the commit gate there (`design.md` §2.7: at least 100 runs per task, at least 99% passing, the latest two batches of 50 at 98% or more; a 30-run screen is its first part, user ruling, 2026-10-04), then certifies by the optimizer's own rule (`design.md` §2.3 and §2.4; user ruling, 2026-10-04, replacing a 59/59 bar per task that ended an attempt at its first blocking failure): each cell and task is a category with betting e-values on its adopted outcomes, certified when `E↑ > 1/α_i` and clearly below when `E↓ > 1/α_i`; every failure, overreach included, counts, and no single failure ends the attempt, which ends not certified only when a category is clearly below or reaches its cap unresolved. Attempts under this rule are numbered from 1 (user ruling, 2026-10-04): attempt 1 has `α_1 = 2%`, needs at least 93 all-pass trials per category (148 with one failure) and caps each at 262. `cert.py` runs an attempt this way, with the cells opening in `design.md` §3.3 rule 4 order and a journal that lets it resume; screens still stop at the first blocking failure. (5) Benchmark: the frozen candidate, unedited, in the other 14 cells, in stages: the 2 other models at `low`, then the 3 models at `max`, then, only if those 5 pass, the 9 middle-effort cells (user rulings, 2026-10-03). Any failure refutes the shortcut for this file and ends the benchmark. 37 runs per task (148 per cell); every fault, overreach included, counts as a failure. A cell rejects H0 (pass rate below 95%, one-sided exact binomial, α = 0.05/14 for every cell from the start, so staging does not inflate the error) only with no failure. The effort result (`gpt-6-luna` above `low`) and the model result (the other models) are reported apart, with any failure kind not seen in development. Development so far: p15-1 (read the range from the line number before the truncation marker through the one after it) failed 30 of 80 screening runs: re-reads started below the cut, and two lazy runs ended their turn announcing a read (announce-and-stop); p15-2 replaces range arithmetic with the bundled `read-file` and forbids ending a turn with only an announcement. p15-2 failed 29 of 30 lazy runs, because the model set `max_output_tokens`; p15-3 gives the exact `exec` script and forbids that, and passed long reads 30/30, but lazy runs sometimes asked the user for the release with no call. p15-4 to p15-6 added opening lines against asking before reading; p15-6's "start every turn with an `exec` call that reads the files the request depends on" made counting runs read the whole file (2 overreaches in one cell). p15-7 sends search and count requests to `rg` in that opening line, but its "reads the files" made 2 of 57 runs `cat` the `read-file` script itself. p15-8 says that the call runs the script and never passes it to `cat`: its screen passed (long 30/30, search 30/30, lazy 64/65 with one self-recovering re-read), but 26 of 30 search answers miscounted (12 of 30 under p15-3), which the bar tallies apart (the model counts the matching lines that `rg -n` shows). p15-8 then certified on `gpt-6-luna` at `low`: 59/59 on each task, with no failure (44 of 59 search counts wrong). Benchmark stage 1 was stopped after 160 runs, all passing, because p15-8 tells the agent to start every turn with an `exec` call, which no task tested on a turn that needs no file (user ruling, 2026-10-03: stop, fix, redo). A fourth task, a general-knowledge question in the lazy-load workspace, now passes only with no tool call; a needless call is self-recovering. p15-8 made one in 9 of 10 such runs; the v1.0 rule and no rule made none in 20. p15-9 to p15-14 scoped or reordered the opening, and each made needless calls (`pwd`, an `rg` search for the question's term, or loading the files `AGENTS.md` names for customer replies) in 2 of 3 to 8 no-file runs. The zero-call ask that the opening targeted occurs without any rule (no rule: 2 of 30 lazy runs; v1.0 rule: 1 of 30); it stays a blocking failure of this rule (user ruling, 2026-10-03). p15-15 returns to p15-3 and adds a sentence against ending the turn by asking for what a workspace file may hold (alone: 0 needless calls in 20) and the `cat` clause; it passed 19 no-file runs but one lazy run batched three reads in one script and lost 38 lines to the cut. p15-16 adds that each script reads one file because several files' parts exceed one result: its screen passed 120 of 120 (18 of 30 search counts wrong). Its certification failed at lazy run 44 on a zero-call ask (user ruling, 2026-10-04: keep tuning in this rule). p15-17 to p15-23 tried sentences against asking, against claiming a file is missing, and on when instructed loads apply; none moved the zero-call rate: p15-23 failed 5 of 120 lazy runs that way, while p15-8's unconditional opening failed 0 of 124. Scoped openings (p15-24, p15-25) and loads scoped to the instructed work (p15-26) either made needless calls on the no-file task or still answered with no call. In 202 of 5320 read scripts the agent dropped the parentheses around `await`, printing `undefined`; p15-25 uses a two-statement script, `const result = await …; text(result.output);`, which keeps the exact-script approach (user ruling, 2026-10-04). The no-file task's "Do not edit any file." suffix drew the needless calls: p15-25 made them in 10 of 20 suffixed runs and 0 of 20 runs of the bare question, which is now the only no-file control (user ruling, 2026-10-04). p15-16 and p15-25 entered certification after a clean 30-run screen although development had shown the zero-call reply at about 1 in 40 lazy runs, and both certifications failed on it; certification now waits for the commit gate (user ruling, 2026-10-04). p15-28 keeps p15-25's opening and replaces its closing announcement line with a check before ending a turn that ran no command: a reply that asks for details, says it cannot see them, or only announces the next step is not sent, and the agent looks in the workspace instead. Its screen passed 120 of 120 (19 of 30 search counts wrong). It passed the commit gate: 100 runs per task, every one passing except plain run 54, a self-recovering `rg` search for the question's term before a correct answer (99/100; that batch of 50 at 98%). It then certified on `gpt-6-luna` at `low`: 59/59 on each task, with no failure (35 of 59 search counts wrong). Benchmark stage 1 passed: `gpt-6-astra` and `gpt-6.1-sol` at `low`, 148 of 148 runs each, with no failure and no wrong count. Stage 2 passed: the 3 models at `max`, 148 of 148 runs each (30 of 111 search counts wrong); one run that the full disk stopped before its session was written was rerun as the same run. Stage 3 passed: the 9 middle-effort cells, 148 of 148 runs each. Every one of the 14 benchmark cells rejects H0 with no failure, so the shortcut holds for this file, with no failure kind absent in the development cell. Wrong search counts, tallied apart, are a `gpt-6-luna` trait at every effort (22 to 35 of 37 per cell) and almost absent elsewhere (1 of 444 runs of `gpt-6-astra` and `gpt-6.1-sol`). For the file-reads bar, `gpt-6-luna` at `medium` and `gpt-6.1-sol` at `high` extended their 37 clean benchmark runs per task to readiness (100 per task, every one passing), 60 runs at the 5,000-token limit passed (20 per file task), and both cells then certified at 59/59 per task with no failure (51 of 118 search counts wrong). A pass supports the shortcut for this file only; several files must agree before development or certification policy changes. The four tasks are fixed test cases, so development and certification ran the same test cases; certification on held-out test cases (`design.md` §6.3, D43) was then run at the user's request (2026-10-04): `templates.py` makes each task a template (file path, release numbering and count, content seed, breaking change and its position, question wording; for the lazy task the guide and glossary names, desk, key rules and their positions; for the plain task 10 general-knowledge questions in a generated lazy-load workspace), assigns each test case to the development or held-out part by a hash under a salt frozen in `templates-salt.txt` before any held-out run, and builds 24 test cases per part per task; the original fixed tasks count as development test cases. The scorer was checked on one development test case per task. `ffh.py` reruns the bar's certification on held-out draws: 59 per task on `gpt-6-luna` at `low` and `medium` and `gpt-6.1-sol` at `high`, and 20 per file task at the 5,000-token limit, with the same failure rules. The original plain task ran in the probe root, because no `project-plain` exists; the held-out plain task runs in a lazy-load workspace. Result: p15-28 failed held-out certification. The first pass stopped on two correct plain answers that the answer patterns missed ("temporarily unable to handle" for 503, "couldn’t find" with a curly apostrophe for 404), both with no tool call; the patterns were widened and every run rescored before resuming. `gpt-6-luna` at `low` then made a needless `rg` search of the workspace before answering in 2 of 7 plain runs (the HTTPS port; what idempotent means), a second self-recovering failure in the cell, so the cell fails. The other 105 runs passed (lazy 21, long 23, search 28, plain 22 in the other cells; 6 search counts wrong). Development resumes on development test cases only, and the next certification uses a new salt and a larger question pool, because the orchestrator, who also tunes, saw the failing held-out test cases. `templates.py instances2` builds that set under `templates-salt2.txt`: the plain pool has 46 questions, the 10 seen so far stay in the development part, and the other 36 are split by question, so no held-out question is a development question; `ffh.py --test cases instances2 --part development` draws development test cases into `druns*`. On development plain test cases p15-28 made a needless call (mostly `pwd`) in 6 of 40 runs, and on development lazy test cases it passed 40 of 40. Wider general-knowledge wording cut the needless calls (p15-29: 0 of 40; p15-30 to p15-32: 1 to 2 per 40 to 70), but each such candidate also ended some lazy turns with no call (asking the customer for details or announcing a check), 1 to 5 per 40 to 57 runs, so p15-29, p15-30 and p15-34 failed readiness on lazy (p15-34 also had one long run answer from an `rg` search). Agents sometimes rewrote the script path by splicing in their working directory, because the probe's `bin/` path is a prefix of every run's workspace; from p15-34 on the script is at `/home/gili/cat-client/probe-plugin/bin/read-file`, outside the probe tree, as the plugin's `client/bin` is outside a project, and its continuation line says to run the next part before any other command or answer. p15-35 to p15-37 (wider exemption examples; the general-knowledge decision made from the request alone; p15-32 with the end-of-turn check moved last) also failed: plain 7 of 40, plain 7 of 40, and readiness stopped at a second plain needless call (with one lazy zero-call turn and one search overreach). p15-28 passed 100 of 100 development lazy runs. On development plain test cases, which run in a lazy-load workspace, the needless call also occurs without this rule: no rule 3 of 24 runs, the v1.0 rule (p15-0) 1 of 23; the earlier 0 of 20 came from the probe root, which has no `AGENTS.md`. A codex-lb outage (every request refused with `no_accounts` from 14:55 UTC on 2026-10-04) invalidated the rest of that measurement; the user restored routing on both accounts. The user ruled that any call on a plain question fails the run, whatever the no-rule rate (2026-10-04). p15-38 (p15-28 with a strong general-knowledge sentence in place of its weak exemption) passed 59 of 60 lazy and 37 of 40 plain screen runs; its plain calls read `AGENTS.md` or loaded the files that `AGENTS.md` names for customer replies. Saying that files named for some work are needed only when the request asks for that work, and that answering a question is not that work, stopped those loads (p15-40); the remaining plain failures were a bare `pwd`. p15-41 failed readiness on two `pwd` calls, both on the closure question, whose workspace names a glossary; that test case failed 11 of 20 runs under p15-41, against 1 of 20 with no rule and 0 of 20 with the v1.0 rule, so the rule's push to start with `exec` caused the call (one run even printed its answer with `printf`). p15-48 opens by deciding whether the request is about this project (it names a file, release, customer or other part of it, or asks for work that an instruction names files for) and runs no command otherwise; it passed the closure test case 30 of 30 and a screen of 60 plain and 60 lazy runs, but failed readiness on lazy: drafts written to the customer that asked for the customer's product or setup, with no call. Putting the project branch first again (p15-49) brought the closure failures back (5 of 20). p15-51 extends the end-of-turn check to a reply, or a draft in it, that asks anyone for information; it passed the hard plain and lazy test cases 56 of 56 but failed readiness on long, where a request to go through a whole changelog was answered from an `rg` search (4 of 72 long runs, against 0 of 70 under p15-41). p15-52 adds that a request to go through a file, or to judge what its text means, is not a search; it passed readiness on development test cases (plain, long and search 100 of 100, lazy 99 of 100 with one run that skipped the files `AGENTS.md` names; 61 wrong counts, tallied apart, as under every candidate). Its certification on the `instances2` held-out part failed after 663 passing runs: a luna5k lazy run asked the user for the customer's product or setup with no call (Luna low, Luna medium and Sol high had no blocking failure; one Luna medium search run read the whole file, a self-recovering overreach that extended that cell). Its failures were inspected, so the next certification uses `instances3` (salt `templates-salt3.txt`), whose plain pool adds 19 new questions and keeps every question the `instances2` development part used in development; luna5k joins Luna low as a development cell. p15-53 (a leading sentence forbidding questions before a call) brought back closure-question calls (3 of 16). On development lazy test cases under luna5k, p15-52 failed 2 of 80 runs with a zero-call ask, and p15-54 (a request about this project stays one even when it seems to lack details) 1 of 40. p15-55 replaces the decide-first opening with one sentence that gives both branches: a request that names a part of this project, or asks for work that an instruction names files for, starts with an `exec` call; any other request is answered with no command. It passed the closure test case 16 of 16 and Luna low lazy 40 of 40, but luna5k lazy failed 2 of 40 runs with no call, one of them announcing the check and stopping. p15-56 adds that the first call comes with no message before it: luna5k lazy 60 of 60, Luna low lazy 20 of 20, closure test case 15 of 16. It passed readiness on development test cases: Luna low 100 of 100 on each task, and luna5k 100 of 100 on long and search and 99 of 100 on lazy (one run made 6 calls but skipped about 40 lines of two files and read 12 lines twice; that batch of 50 at 98%); 79 of 200 search counts wrong, tallied apart. Its certification on the `instances3` held-out part failed, stopping after 687 runs: `gpt-6-luna` at `low` made a needless call on a plain question twice (one ran `pwd`, one read `AGENTS.md`), a second self-recovering failure in that cell; one `gpt-6-luna` at `medium` search run read the whole file, a self-recovering overreach that extended that cell. Lazy, long and the other cells had no failure (65 search counts wrong, tallied apart). Only the failing commands were inspected, not the failing questions, so the `instances3` held-out part stays unseen. As `design.md` §6.3 now prescribes after a failed attempt, `templates.py instances3x` adds 12 development-only plain questions on release and support subjects, each different from every earlier question, held-out ones included. p15-56 passed them 40 of 40 (no rule 20 of 20, the v1.0 rule 15 of 20), and 119 of 120 runs on the `instances3` development plain test cases; seven big-O answers that said "grows" were first scored as failures and pass once the answer pattern accepts it. The one failure loaded the files that `AGENTS.md` names for customer replies before answering what a mutex is; that test case then passed 24 of 24 under p15-56 (no rule 11 of 12, the v1.0 rule 12 of 12), so development reproduces no failure that the rule causes. With plain readiness restarted on the changed development distribution and passing (159 of 160), p15-56 is committed again, unchanged, on fresh draws from the unseen `instances3` held-out part. That recertification failed: `gpt-6-luna` at `low` made a second needless plain call at run 91 of its extended 94 (one loaded the file that `AGENTS.md` names, one ran `pwd`); `gpt-6-luna` at `medium` had one (a read of `AGENTS.md`) and was still extended. Lazy, long and search passed 59 of 59 in every cell and 20 of 20 at the 5,000-token limit, and `gpt-6.1-sol` at `high` passed plain 59 of 59 (52 search counts wrong). A correct stack answer ("the last item added is the first one removed") had first stopped it as `no answer`; the stack and queue answer patterns were widened and every run rescored before it resumed. Across both certifications `gpt-6-luna` at `low` made needless plain calls in 4 of 145 held-out runs (`pwd` twice, a read of `AGENTS.md`, a load of the file it names), against 2 of 300 development runs; the gap is not significant, but either rate is too high for the bar. p15-57 names `read-file` on `AGENTS.md` or on a file an instruction names among the commands a general-knowledge question never runs, and limits instructed loads to the work the request asks for. It did worse on the same 120 development draws (117 against p15-56's 119; two of its calls read `AGENTS.md`, which naming it seems to prime). p15-58 puts the general-knowledge branch first: lazy 100 of 100 and plain 120 of 120, but 2 of 32 on the closure and mutex test cases (`read-file .`, `read-file AGENTS.md`). p15-59 scopes the script's heading to requests about this project: closure 13 of 16. p15-60 adds one closing line, "A general-knowledge question gets its answer with no command, not even one to look around.": closure 79 of 80 and mutex 48 of 48, against p15-56's 61 of 80 and 47 of 48 measured at the same time (interleaved: 32 of 32 against 27 of 32). p15-60 passed the hardest luna5k lazy test cases 40 of 40 and readiness on development draws: `gpt-6-luna` at `low` 100 of 100 on lazy, long, search and plain (50 plain questions of the certification set and 50 added ones), luna5k 100 of 100 on lazy and long and 99 of 100 on search (one run read all 1,139 lines of a file to count releases instead of searching it). One long run lost its context in the harness and was replaced. Its certification on the held-out part, development cells first, stopped after 119 runs: a `gpt-6-luna` at `low` long run replied "I haven't read `notes/releases.md` yet" with no call, a blocking failure, although the rule already forbids sending a reply that says it lacks something (lazy 26, long 30, plain 20 and search 37 passed; 9 search counts wrong). p15-56, which lacks p15-60's closing line, had no such stop in 237 development long runs in that cell, against 1 of 125 for p15-60; the gap is not significant. p15-61 makes the closing line cover both cases: "A request about a file or another part of this project gets an `exec` call before any reply; a general-knowledge question gets its answer with no command, not even one to look around." It made 2 needless calls in 39 runs of the closure test case (`pwd`, a read of `README.md`). p15-62 kept p15-60's line and added "a question about a file gets no reply until you have read that file, never one saying that you have not read it": 36 of 40 on the closure test case interleaved with p15-60's 40 of 40, so any mention of files at the end primes calls. Following `design.md` §6.3 (D44), the long test then got development-only values from the failure's cell and failed requirement alone: 4 new paths and 4 new requests, none saying to read the whole file (`instances3l`, 24 test cases, 13 combinations). p15-60 failed 4 of 200 runs there in `gpt-6-luna` at `low`, each searching with `rg` for guessed words such as "breaking" or "callers" instead of reading the file; the answers were right but unsupported by a full read. p15-63 extends the rule's distinction: a request to judge what text means, "such as whether any entry forces a change on its readers, is not a search, because a search finds only the words you guess". Its long readiness runs take half their draws from the added values. p15-63 passed 199 of 200 on the added long values (p15-60: 196 of 200 on the same draws), the closure and mutex test cases 80 of 80, search 100 of 100 and the hardest lazy test cases 40 of 40, but failed readiness: luna5k search 98 of 100, both failures reading all 1,026 lines of `docs/history.md` to count the releases that mention the exporter (2 of 9 on that test case, against 0 of 9 for p15-60); every other task passed 99 or 100 of 100. p15-64 keeps the distinction and says why a count is still a search: it runs `rg` "for the word the request names, which finds every line with it", while judging what entries mean is not a search "because no word finds every such entry". p15-56 had passed the closure test case 15 of 16 earlier the same day, so its rate there moved over time; candidates are compared only on runs interleaved or made together. Certifications run all cells together until p15-56's spent about 500 runs in stronger cells, twice, before failing in the weakest; from p15-60 on they follow `design.md` §3.3 rule 4: the development cells (`gpt-6-luna` at `low` and luna5k) first, then `gpt-6-luna` at `medium` once both are certified, then `gpt-6.1-sol` at `high` (user ruling, 2026-10-04). `ffh.py --only <test case>` runs one test case repeatedly; each run directory is locked while it runs, after two plans with the same run numbers overwrote each other's runs. p15-60's stopped attempt continues as attempt 1 of the new rule, because the test stays valid when a stopped attempt continues with the same candidate and the same seeded draws: it keeps its 129 completed runs (114 scored at the stop, 15 that finished as it stopped), the one long failure included, and draws on from the `instances3` held-out part. Result: p15-60 is **certified** in all 15 categories (attempt 1, `α_1 = 2%`) after 1,729 runs: every category reached `E↑ > 50` with 93 to 170 adopted outcomes, and none was clearly below. It had 4 failures: the earlier zero-call long run (`gpt-6-luna` at `low`) and three search runs that read the whole 1,026- to 1,113-line file to count (`gpt-6-luna` at `low`, luna5k, `gpt-6-luna` at `medium`), each in a category that still certified at 147 to 149 passes. Wrong search counts, tallied apart: 64, 68 and 49 in the three `gpt-6-luna` search categories, 0 for `gpt-6.1-sol` at `high`. Per the user's ruling (2026-10-04), p15-60 is delivered to v1.0. Probe: `/home/gili/cat-client/worktrees/fullread-probe` (`c-p15-*`, `variants/p15-*`). |
| WI-16 | Done 2026-10-01. Trial profiles and cells: contract types `Cell`, `ProfileBinding`, `ProfileCatalog`, `ProfileRule`, `TrialProfile`, `TrialProfiles`; `ProfileInventory` port with `ClaudeProfileInventory` (opus, fable and sonnet with low–max, haiku without effort, from a shipped resource) and `CodexProfileInventory` (listed models of `codex debug models`); session requests carry the cell's profile, judge and contract author use the role profile from the installed `optimize-xhigh` agent; campaign format 2 (format 1 refused); status JSON keys name cells (`cells`, `cell`). `DriverTest.cellsRunUnderTheirProfiles` is trace 15. Mutation checks on code written before its tests: every mutant killed except three equivalent mutants and two untested hops (CX14, the agent effort override, which existed before WI-16 and needs an installed-plugin fake; CX15, `CodexRunnerCli.serve` passing the parsed effort, whose neighbouring links are each tested). Integrated build: 1281 tests, 0 failures; no test at or over 1 second; bats 32/32. |
| WI-17 | Done 2026-10-01 (`8cfd6d829`). Eight check kinds with known-good and known-bad controls, CAT's core-task requirement on every test, no judge session after a failed check, and the check record in the prompt author brief. The campaign and proposal file formats are version 3; version 2 is refused. Merging with WI-19 needed `TierFixtures` to leave the core-task requirement out of profile binding. Full build 1352 tests, bats 32. Open: `tool_used` and `exit_status` checks cannot be decided on real trials, because the Claude and Codex session results record no tool calls; until the adapters record them, such a check counts as an infrastructure failure. Follow-up: WI-17a. |
| WI-17a | Done 2026-10-01: the Claude and Codex adapters record each trial's tool calls and command exit statuses in the session result, from recorded harness output. Claude reports no status field (`design.md` H30); by user ruling its adapter reads the recorded result forms and leaves any other form without a status, which makes an `exit_status` check undecidable. A check whose control lacks what it observes is discarded to the judge. Codex MCP, sleep and image tool variants were not recorded. |
| WI-18 | Done 2026-10-01 (Codex xhigh). The prompt author returns an evidence manifest that is validated before its edit is tried; no-edit and rejected attempts keep their diagnoses; a passing attempt waits for a resumable minimality search that drops unneeded hunks through uncounted promotion runs, and removed hunks are reported; the status report compares working and incumbent snapshot sizes and observed input tokens, keeping missing usage distinct from zero. The campaign and trial format is version 4 and versions 1 to 3 are refused. Merged with WI-17a; on the merged tree the full build passed with 1434 tests, 0 failures, the five bats files passed 34 of 34, and no test took 1 s or more. |
| WI-19 | Done 2026-10-01. `CellTier` derives the active set (bound cell, each model's lowest and highest effort in inventory order, any cell with an adopted failure other than a promotion run) from the contract and the persisted `failedCells`; `Scheduler` screens every cell with 3 trials of the incumbent in series 1, samples only active cells under the coverage floor with each model's lowest-effort cell first, and widens through a persisted `widened` latch once every active cell meets the commit-gate count conditions. Campaign format 3 (`failed_cells`, `development.widened`); format 2 refused. Status shows each category's active cells and the widened flag (`cellTier` in JSON). Trace 19 is `DriverTest.cellTiering`. Integrated build: 1297 tests, 0 failures; no test at or over 1 second in that run (the delegate's runs saw `GeneratorTest.samplesVariationsAtFrozenProbabilities` at 1.446 s and `FinalizationTest.rejectsUnresolvedChallengesBeforeReplay` at 1.059 s, both pre-existing); bats 32/32. WI-32 replaces this tiering (user ruling, 2026-10-04). |
| WI-20 | Done 2026-10-02 (Codex xhigh; integrated as `42c763ca9`; full build 1458 tests, 0 failures; bats 32/32). `AttemptSelection` decides weakness (a cell of an addressed category whose development Wilson lower bound is below 80%, or that has no outcome) and the winner among accepted attempts (most passing promotion runs of the attempt itself, then fewer changed lines, then earlier launch). A weak round reserves 3 attempts per failing category and the driver starts them within capacity once the round's assigned development trials have settled, so every attempt gets the same brief; a round that is not weak keeps the sequential 2 attempts. Only the winner enters minimality; the others keep their overlays and evidence. Reservations are persisted, so a restart relaunches unfinished attempts without counting their runs. The status lists each attempt's passing and total runs, acceptance and winner (`prompt_author_attempts`). Campaign format 5; older formats refused. On review, the attempt's run counts excluded the working candidate's reference runs, which had been counted in selection and status (`AttemptSelectionTest.referenceRunsDoNotCountForAttempt` failed first); the three copies of that count became one. |
| Claude interrupt request (portability follow-up, user request 2026-10-01) | Done. `ClaudeProcess.interrupt()` writes a stream-json `control_request` with subtype `interrupt` to Claude's standard input instead of running `kill -INT`; the `InterruptSender` seam and `FakeHarnessCli`'s interrupt replay are removed (`design.md` §3.8, H29). `ClaudeProcessTest.interruptsThroughStandardInput` (R-E15): the in-memory Claude prints the recorded 2.1.287 post-interrupt lines only when it receives that request. The three native Claude interrupt tests passed against Claude 2.1.287 (3.2 s, 4.4 s, 5.2 s). |
| Test-double audit (user request 2026-10-01) | Done (`5079b82b0`, `82ee80ec1`; full build 1433 tests, 0 failures; bats 32/32). `FakeHarnessCli` and `ScriptedHarness` name the recording or documentation behind each scripted reaction (Claude Code 2.1.286/2.1.287, codex-cli 0.147.0/0.159.1, the JDK `Process` contract); unsourced reactions (Claude's post-interrupt sequence, failure after interrupt, permanent blocking, simulated writes) are removed. Of 45 test methods, 4 were removed and the rest kept, fixed or narrowed to the production decision; every fix was shown to fail against broken production. The app-server command test it removed is replaced by `CodexRunnerTest.startsAppServerForInstallation`. |
| Campaign lock failure masking (convention-pass follow-up) | Done (`e085ec1fa`; full build 1438 tests, 0 failures; bats 32/32). `CampaignLock` keeps the primary acquire or release failure and suppresses a close failure; `CampaignLockTest` injects both through `FaultingFileChannel` and failed first. `Driver.open` keeps the open failure when unlocking fails (`d83badc4d`; full build 1461 tests; `DriverTest.openKeepsFailureWhenUnlockFails` failed first). |
| R-W1 exclude notice (convention-pass follow-up) | Done (`138f66e87`; full build 1441 tests, 0 failures; bats 32/32). `ForkView.excludeAdded()` reaches the `start` command's message ("Added /.cat/work/ to the repository's local info/exclude."); `OptimizerCoreTest` covers added, already excluded, and added with a contract author failure, and failed first. |
| WI-21 | Done (`ea5a59fb9`; full build 1584 tests, 0 failures; bats 32/32). The approved `failure-regression-promotion.md` text is delivered unchanged. All 61 rule scenarios and 6 fixtures moved to `.cat/tests/client/common/rules/src/main/resources/rules/<rule>/<case>.md` (fixtures under `fixtures/`); the 16 `suite.json` manifests and the two retired `verification.md` notes were deleted. `RegressionScenarios` discovers project and user scenarios, the contract author receives their content and must cite each scenario of a named prompt source in `regression_scenarios`; `start` and `confirm` refuse an uncited proposal. Proposal format 4 and campaign format 7; older formats are refused, so the plugin is reinstalled only after the running E1 campaign ends, followed by a fresh derivation receipt on the final build. `RegressionScenariosTest` locates the repository through `cat.client.directory`. Open: the test uses `cat.work.temp`, like 11 other tests, which the native-tier removal must replace. |
| Skill proposal display (WI-15 finding) | Done (`c60e6d073`; full build 1568 tests, 0 failures; bats 32/32). In three E1 turns the Codex agent saved the proposal (74 KB, 25 KB, 15 KB) to `/tmp` and linked it instead of showing it. The user approved defining "show" in `first-use.md` as verbatim text in the reply; three regression scenarios are recorded under `.cat/tests/client/common/skills/src/main/resources/skills/prompt-optimizer/first-use/` (recorded, not tested). |
| WI-22 | Done 2026-10-02 (Codex high; full build 1609 tests, 0 failures, none at or over 1 s; bats 32/32). User ruling (`design.md` D29, H31): a turn's reply is every assistant message of the turn. `SessionResult.Completed` carries each turn's messages in order; the Codex adapter keeps every root `agentMessage` and the Claude adapter every root assistant text block, with a result-only final text added once. Checks join a turn's messages with newlines, so a suffix check still ends at the final response; check controls use the same nested shape. The judge, contract author and prompt author prompts state that every message is part of the reply, and prompt author failure records carry the messages. Structured output (contract author, judge, prompt author) is still parsed from the final message only. Campaign format 8; older formats are refused. The first Codex E1 campaign, which plateaued on Codex's progress note, was stopped as the test user with the base clean at the same HEAD. |
| Prompt author edit guidance (WI-15 finding, user ruling 2026-10-02) | Done (full build 1584 tests, 0 failures; bats 32/32). `design.md` §2.7, D29 and D30: prompt authors prefer editing or creating prompt files other than `AGENTS.md` and `CLAUDE.md`, editing those only when a correction cannot otherwise reach the sessions that need it; whether an interim message can be suppressed is not yet known, but its wording can be shaped, so a failure in one is `routing` or `guidance`. The user approved the `prompt-author.md` wording; regression scenarios are recorded under `client/common/cli/src/test/resources/prompt-author/prompt-author/` (recorded, not tested). |
| WI-23 | Done 2026-10-02 (full build 1611 tests, 0 failures; bats 32/32). User ruling: scenarios for guidance packaged as a module resource, such as CAT's plugin rules, skills and optimizer prompts, live in the module's `src/test/resources/<path>/<case>/scenario.md`; `.cat/tests/` holds project and user guidance scenarios. The 61 rule scenarios are back in `client/common/rules/src/test/resources/` with their fixtures at their earlier paths and fixed links; the `first-use.md` scenarios are in `client/common/skills/src/test/resources/prompt-optimizer/first-use/`. `RegressionScenarios` finds module scenarios through each module's test resources, and a `scenario.md` belongs only to the guidance its parent's parent names (`RegressionScenariosTest.readsModuleScenario` and `nestedGuidanceKeepsItsScenarios` failed first; the latter attributed a nested scenario to the enclosing guidance too). `everyScenarioNamesGuidance` and `moduleGuidanceScenariosStayInModule` audit the repository. Three git-replay tests took 1.5 to 1.7 s in this run (`FinalizationTest.publishAndVerifyGitReceipt`, `OutputGatesTest.draftOutputWorktreeLifecycle`, `retainedOutputWorktreeLifecycle`) and under 1 s in the run before it: open. |
| Proposal cell listing (E1 finding) | Done 2026-10-02 (full build 1612 tests, 0 failures, none at or over 1 s; bats 32/32). On the final build, Codex E1's first proposal was 84 KB: six unbound categories under the 44-profile inventory made 264 cells, each listed under trial profiles and again under the minimum session count, and the agent replied with one sentence instead of the proposal. The proposal now lists categories that share a profile set once, with their cell count and naming, and cells that share minimum trials once, naming every cell of a category together (`ProposalTest.groupsCellsThatShareProfilesAndCounts` failed first). The revised 16 KB proposal was shown verbatim. |
| Blank assistant messages (WI-22 follow-up) | Done 2026-10-02 (Codex medium; full build 1626 tests, 0 failures; bats 32/32). The Claude adapter skips blank root text blocks and the Codex adapter blank root `agentMessage` items, so a turn's reply holds only non-blank messages (`ClaudeProcessTest.skipsBlankRootTextBlocks`, `CodexProcessTest.skipsBlankRootAgentMessages`; both failed first). Four tests took 1.08–1.20 s (`DecisionBoundariesTest.lookupsAgreeWithBettingProcesses`, `OutputGatesTest.retainedOutputWorktreeLifecycle`, `TestabilityAuditTest.coreReferencesNoHarness`, `onlyMainReadsEnvironment`) at load average 25 from concurrent Codex sessions; each was under 1 s in the previous build. |
| Convention renames (convention-pass follow-up) | Done 2026-10-02 (Codex medium; full build 1612 tests, 0 failures, none at or over 1 s; bats 32/32). Campaign types no longer repeat their package: `Header`, `Registry`, `Transitions` and the test `Builder`; per-cell types and members say cell where they hold a cell (`CellCounts`, `CellDecision`). The two `ManualClock` doubles are one, in `common.fakes`. Codex had kept the old names in `toString` overrides with tests pinning them; both were removed, so diagnostics name the current record and its fields. |
| WI-24 | Done 2026-10-02 (Codex high; full build 1625 tests, 0 failures, none at or over 1 s; bats 32/32). User ruling (`design.md` D31). The contract author findings gain a required `conflicts` array (at least two distinct requirement ids that name contract requirements, and non-blank evidence); the proposal renders a `## Conflicts` section before the certifiable scope; `confirm` is refused while a conflict is listed, naming both blocking links and conflicts in one refusal (`ProposalTest`, `OptimizerCoreTest.conflictRefusalAndRevision`, `confirmListsEveryBlocker`; each failed first). Resolutions follow in WI-25. |
| WI-25 | Done 2026-10-02 (Codex xhigh; full build 1666 tests, 0 failures, none at or over 1 s; bats 32/32). User ruling (`design.md` D31, D32); the user approved the `first-use.md`, `contract-author.md` and `prompt-author.md` wording, including the campaign-edits paragraph and the step 4 manifest sentence. Each contract author conflict carries at least two resolutions, the recommended one and its reason, rendered as a numbered list; the skill asks the user to choose before confirming. The prompt author may classify a failure `conflict`, quoting text the confirmed prompt system already held (a quotation absent from the incumbent snapshot is a manifest violation); the report yields no candidate and pauses the campaign with one `resolution-<n>` choice per resolution plus `stop`, presented one conflict at a time, then one revision. The prompt author brief carries the campaign's edits since confirmation as a unified diff. Campaign format 9 (`ProposalTest`, `PromptAuthorManifestTest`, `PromptAuthorTest`, `ConflictPauseTest`, `TransitionsTest`, `CommandsTest`, `OptimizerCoreTest`, `StatusReportTest`, `FileCampaignStoreTest`; each failed first). |
| WI-26 | Done 2026-10-02 (Codex xhigh, three jobs; integrated full build 1760 tests, 0 failures, none at or over 1 s; bats 32/32). User rulings (`design.md` D30, D33, D34, D35, and a revert going back one promotion). An attempt fixes a triggering test case when every run there passes and the streak is unlikely for the working candidate (`p = (s+1)/(n+2)`, `p^k < 0.10`, compared exactly); runs start at 2 and extend to at most 4 as targeted, resumable promotion runs. Promotion needs at least one fix and no regression where the working candidate passes; a minimality reduction must keep every fix of the attempt it reduces. Promotion records the predecessor (snapshot, counts, test case outcomes, review points, regression verdicts, round); a promoted, unedited candidate whose development Wilson upper bound in an addressed cell falls below the predecessor's observed rate reverts before edit-or-continue, and the status shows the revert. A cell's attempts in one round carry the approaches in order (`PromptAuthorApproach`), derived from the round's reservations; `prompt-author.md` has the approved step 2 and a `Your approach` section. Stored format 11 (predecessor, reverts, test case outcomes). |
| WI-27 | Done 2026-10-02; the full build, the five distribution Bats files (32/32) and 1777 tests passed, with no test at 1 s or more. `Session` passes `--install-cat` only for trial sessions in both harnesses. `RoleRules` declares each role's rules once (prompt author: write-clearly, failure-prevention, contract-design, rule-organization, prompt-file-dependencies, prompt-file-references; contract author: contract-design; judge and trial: none) and fills the brief's `{{rules}}` section from the installed artifact through `ProjectRuleLoader.loadRule`, which renders the lazy-load paths as the artifact's absolute paths; a stored template without the placeholder gets the section appended; a missing rule file fails naming it. The path-scoped rule text names the absolute rule path and the absolute project root, so CAT's installed rule files never match. The user's and the project's rules do not reach the contract author, judge or prompt author (user ruling, `design.md` D37). Validation on a Codex campaign is pending. |
| WI-28 | Done 2026-10-02; the full build, the five distribution Bats files (32/32) and 1828 tests passed on the integrated tree, with no test at 1 s or more. Each harness maps its parsed events (`AppServerEvidence`, `StreamJsonEvents`) into the shared `SessionEvidence` model, which renders tool calls with input, exit status and complete output, child activity, hook status and response positions, and leaves out root reply text, session-start context, deltas and telemetry. `FileChanges` lists created, modified and deleted files against the staged source (unified diffs; a note naming the effects file for binary or oversized files). The driver keeps the staged source and the rendered evidence across judge retries. `judge.md` takes `{{evidence}}` and names the read-only effects directory; a frozen judge prompt with `{{effects}}` and `{{transcript}}` still renders with both paths. On three E1 trials the evidence is 160,386, 94,470 and 343 bytes against raw transcripts of 1,668,773, 1,560,042 and 72,981 bytes. User ruling (`design.md` D36, H32). Verdict agreement on recorded trials is pending. |
| WI-29 | Done 2026-10-04 (Codex xhigh; full build 1863 tests, 0 failures, none at or over 1 s). User request (`design.md` §2.6, §2.7, §3.1, D39). A triage session decides each failed development trial's impact: blocking, when the reply leaves the user without the turn's correct and complete result, or self-recovering, when it delivers that result at the cost of extra work; an invalid third output is blocking. A blocking failure discards the working candidate's unadopted development trials, interrupting those that run, ends any diagnosis and starts a round; a self-recovering failure keeps the edit-or-continue path. The impact reaches the prompt author brief. Campaign format 12. |
| WI-30 | Done 2026-10-04 (Codex high; full build 1879 tests, 0 failures, none at or over 1 s). User approval (`design.md` §2.7, §6.1, D40). Each requirement states its `trigger`, or null when it always applies, and each test lists the requirements whose trigger it leaves absent; the contract refuses a triggered requirement without such a test. A prompt author's dissimilar case is a contract test id, and the manifest check rejects any other. Campaign format 13 and proposal file format 5. |
| WI-31 | Done 2026-10-04. `CodexModelVersions` matches a slug `gpt-<major>[.<minor>][-<rest>]` and accepts a major of 6 or later; the Codex inventory keeps only such listed models and fails when none qualifies; `ProfileInventory.supportsModel` (Claude: every model) lets the core report each unsupported bound model before it starts the contract author session; the Codex role profile below version 6 fails. Integrated build: 1888 tests, 0 failures, none at or over 1 second. |
| WI-32 | Done 2026-10-04 (Claude `optimize-xhigh`; integrated as `66b650441`; full build 1909 tests, 0 failures, none at or over 1 s). Each harness ships `capability-ranking.json` (address and reading date); `CapabilityRanking` ranks by family, then version; revision feedback replaces it through the contract author finding `capability_ranking`, and an unranked inventory family blocks confirm. `CellTier` gives each category's weakest cell (from the latest versions for `weakest_cell`) and its development cells; the screen, active set, `widened` latch and `failed_cells` are gone. `confirm <campaign> <scope>` takes `weakest_cell`, `latest-versions` or `described` (cells from the contract author finding `certification_profiles`, patterns over the inventory, applied in every category; a described scope that leaves a category without a cell is refused). Campaign format 14 and proposal file format 6; every older campaign format is refused, so the compatibility reads of formats 9 to 13 and their tests are retired. The recorded launcher output `long-proposal.json` of the `first-use` skill scenario predates the certification-scope section; its assertions still hold, and it is rerecorded with the next live contract author session. User rulings (`design.md` §6.6, D27, D42, 2026-10-04): development and the commit gate only in each category's development cells (its weakest cell, plus any cell where certification failed); the user chooses the certification scope at confirmation from three choices (weakest cell only, latest model versions, or a scope the user describes; user ruling, 2026-10-04: no all-models choice); the weakest cell comes from a shipped capability ranking, with no trials. |
| WI-33 | Done 2026-10-04 (Claude `optimize-xhigh`; integrated as `4a7e9498b` and `eda6ae1c7`, the second withholding the salt and the held-out key values from the prompt author's contract; full build with WI-34: 1948 tests, 0 failures; one case at or over 1 s, `HarnessProcessTerminationTest.terminatesVisibleChildren` at 1.92 s, untouched by this work and run while 16 probe sessions loaded the machine). Prompt tests carry `key_dimensions`; the proposal carries a salt drawn at first derivation, kept through revision and frozen at confirmation; `Split.partOf` assigns each key-value combination to a part by SHA-256 of salt, test id and key labels; confirmation refuses a test with fewer than 2 combinations in either part; `Generator.draw` takes the part from the stream kind; formal failures reach development as `FailureSummary` (cell, test, failed requirements); campaign format 15, proposal format 7. User ruling (`design.md` §6.3, D43, 2026-10-04). |
| WI-34 | Done 2026-10-04 (Claude `optimize-xhigh`; integrated as `d2fd5025c`). An attempt that ends below minimum or inconclusive with formal failures blocks development scheduling and the commit gate until a contract author session (`values.md`, `values.schema.json`) returns at least 4 combinations per failed test from a development-only view; CAT rejects a value whose label or text equals any value of its dimension, ignoring case; added combinations share half of the test's development draws equally; adding values starts a new development series and clears counts; the report lists the values. The recommit loop is covered by `DriverTest.failedAttemptAddsDevelopmentValues`. User ruling (`design.md` §6.3, D44, 2026-10-04). |
| WI-36 | Done 2026-10-04 (Claude `optimize-high`; integrated as `118a2be8f`; full build 1978 tests, 0 failures, none at or over 1 s). Every role but the judge gets `--deny-read` for `campaign.json`, `proposal.json` and `trials/`, listed once by `FileCampaignStore.heldOutPaths`. Claude: `sandbox.filesystem.denyRead` plus a `permissions.deny` `Read(//<path>)` rule per path, because the sandbox does not cover file tools. Codex: `permissions.filesystem.deny_read` is honoured only in a requirements file, so the isolated `config.toml` gets a `cat-held-out` permission profile extending the runner's with `"<path>" = "deny"` entries, and the thread starts with `permissions:"cat-held-out"`; local `codex sandbox` probes confirmed the denial, staged sources still readable, and network kept. `start` refuses a harness whose sessions cannot deny reads. Gaps the probes found, left to WI-37: Codex on Linux reads a denied file again once it is replaced by rename, as the store writes the snapshot; formal trials' and judges' run directories are not denied; a host with the Claude sandbox off loses the denial for commands. No live session ran. |
| WI-37 | Done 2026-10-05 (Claude `optimize-high`; integrated as `0b973036c` on 2026-10-04). Live validation found WI-40; the Claude validation was repeated after WI-40 by WI-40's runner probe of 2026-10-05, which left the held-out directory and the run root unreadable. Full build 2003 tests, 0 failures; 10 tests at or over 1 s (up to 2.6 s), all in the Codex and Claude session and launcher tests, measured while 14 P15 probe sessions loaded the machine (load average 17 on 16 cores); the same tests were under 1 s in WI-36's build, so they are rechecked on an idle machine before WI-15. The campaign format is 16: the snapshot, the proposal and the trial records are in `<campaign>/held-out/`, and a snapshot outside it is refused. Formal and baseline trials and judges run under `<runRoot>-held-out`; every other session gets `--deny-read` for each campaign's held-out directory and that root, created before any session starts. Claude `start` refuses when managed or project settings set `sandbox.enabled` to false, `sandbox.allowUnsandboxedCommands` to true or a non-empty `sandbox.excludedCommands`, or are not a JSON object; the host's user settings are not checked, because the runner's isolated settings replace them. Codex has no refusal: its probes found no host setting that overrides the named profile's deny (`sandbox_mode` and `default_permissions` overrides, an untrusted project's `.codex/config.toml`, the `danger-*` parents), and in a `codex sandbox` probe a snapshot replaced by rename, a record added mid-session and a run directory created mid-session all stayed unreadable while the run root, the journal and the staged sources stayed readable. |
| WI-39 | Done 2026-10-04 (Claude `optimize-medium`; integrated as `6c313619d`; full build 2009 tests, 0 failures; 7 tests at or over 1 s, up to 2.6 s, a different set from WI-37's build, measured at load average 13 on 16 cores while P15 ran; rechecked idle before WI-15). Found while integrating WI-37: reconciliation looked for leftover runs in `<campaign>/runs`, where no run is written. `SessionPort.runRoots()` declares the run root and the held-out run root; `LeftoverRuns` searches both and returns the harness process of a run still marked `running` whose recorded owner, the driver process that hosts the runner bridge, has ended, using the runner's own liveness check. A run whose owner is not identified by pid and start time is not left over. |
| WI-40 | Done 2026-10-04 (Claude `optimize-medium`; integrated as `87a41b99f`; full build 2013 tests, 0 failures, none at or over 1 s at load average 4.7, which also clears the recheck of WI-37's and WI-39's slow tests). Live: the installed Claude `prompt-optimizer start` refused a project `.claude/settings.json` whose `allowRead` named the held-out directory, naming the file and the setting, and passed that check once the setting was removed. Runner probe with a host `allowRead` (2026-10-05, the Claude plugin installed from the verified build of `005767630`, Haiku 4.5): host user settings in a temporary configuration directory with copied credentials set `sandbox.filesystem.allowRead` to a held-out directory and a run root, and `claude-runner` ran with `--deny-read` for both. The isolated settings had no `allowRead`, `denyRead` for both and a `Read(//<path>/**)` deny rule for each. Asked only to print a file in each directory and list the run root, the session's Read tool was denied by permission settings for both files and its Bash listing showed the run root empty; it did not try a Bash read of the held-out directory. Neither file's token appeared in the bridge output or the run transcripts. An earlier run with the same request worded as a command made no tool call, because the model read the deny list in its sandbox configuration; a run whose prompt stated that a denial was expected is not counted. The copied credentials and the runner's own copies were deleted afterwards. The packaged launcher gives Java a private `/tmp`, so a project under `/tmp` is reported as not a real directory. Found by WI-37's validation (2026-10-04), through the packaged runners with `--deny-read` for a held-out directory and a run root, while a background job replaced the snapshot by rename and added a file mid-session. Codex (gpt-6-luna, low): one command execution printed the project file and `Permission denied` for the replaced snapshot, the added file, the directory listing and the run root. Claude (Haiku 4.5) with no project settings: the Bash tool found neither directory, and the Read tool was denied. With `permissions.defaultMode: bypassPermissions`, or with `allowRead` naming an ancestor of the held-out directory, a `../` path or a `~/` path, nothing was exposed. With project `.claude/settings.json` or `.claude/settings.local.json` `sandbox.filesystem.allowRead` naming the held-out directory, the run root or the snapshot, the Bash tool printed them: the replaced snapshot, the added file and the run root's file, or, for the snapshot entry, its content at session start. A `Read` allow rule did not override the `Read` deny. The runner copies the host user's `sandbox.filesystem` into the isolated settings, so a user `allowRead` reaches the session too. |
| WI-41 | Done 2026-10-04 (Claude `optimize-high`; integrated as `ee36d0666` with WI-42; full build 2085 tests, 0 failures; at or over 1 s at load about 5: `DriverTest.heldOutInstancesStayOutOfDevelopment` 2.4 s, `weakRoundSelectsBestThenRunsSequentially` 2.1 s, `weakRoundResumesOneWorkingReference` 2.0 s and `weakRoundRestartsTargetedRuns` 2.0 s, none of them changed by it). Runner and CAT versions are information only; `SessionPort.sessionEnvironmentVersion()` is 1 in each harness, guarded by `session-environment-versions.txt` in each harness module's test resources; each certification records its minimum pass rate and level, and the status names `MINIMUM_PASS_RATE` or `CERTIFICATION_LEVEL` when one makes it historical; campaign format 18. |
| WI-42 | Done 2026-10-04 (Claude `optimize-high`; integrated as `d7b5fb324`, build as WI-41). `SessionPort.judgeVersion()` is 1 in each harness and is the `JUDGE` condition, shown as "judge version"; `judge-versions.txt` in each harness module's test resources registers the hash of the judge profile and of the evidence section rendered for a fixed event file; the judge session's model is no longer recorded. |
| WI-43 | Done 2026-10-04 (Claude `optimize-high`; integrated as `2d1a7bc72`, `61b1d8001`, `559af86d7`; full build 2117 tests, 0 failures; at or over 1 s: `DriverTest.heldOutInstancesStayOutOfDevelopment` 1.16 s, not changed by it). User rulings (`design.md` §4.1, §4.7, §5, D51, G1, G5, 2026-10-04): a certified campaign removes its files from `.cat/work/` after delivery or a kept incumbent, and delivery writes a certification record into the project. `Cleanup` marks a removal, renames the campaign directory aside and finishes an interrupted removal on the next command for the target; a failed removal warns in later commands' `message`. The record is `.cat/certifications/<target without extension>.json` (`format_version` 1), the last journaled change of delivery. `status <target>` reports the resumable campaign, else the record, else the latest campaign; the record's report lists `Not checked: …` conditions, and from the other harness the harness-specific conditions are unchecked rather than changed. A new campaign's proposal shows the incumbent's record under "## Incumbent certification". |
| WI-44 | Done 2026-10-04 (Claude `optimize-medium`; integrated as `91570ec55`; full build 2052 tests, 0 failures; at or over 1 s: the unchanged `ExecutableFilesTest.runsConfiguredExecutable` 1.2 s, `retriesTextFileBusyStart` 1.2 s and `interruptedWaitStopsRetries` 1.2 s). `RemainingWork` adds a commit-gate entry for each development cell outside the scope; `CellReports`, which already listed every contract cell, now also lists stress cells after their base cells. |
| WI-45 | Done 2026-10-05 (commits `a20e6556f`, `eafda5451`). User ruling (`design.md` §4.3, §4.7, D52 and G2): external files are read-only members of the prompt family. `Authority` takes no authorized targets and returns `EXTERNAL_TARGET_READ_ONLY` for every operation on an external file, before the loader-configuration check; every warning offers `use_authorized_source`, stop and revise. `authorize_target`, `--target`, `Campaign.authorizedTargets` and `authorized_targets` are gone; `campaign.json` is at `format_version` 19 and a version-18 file is refused. `DeliveryPlan.requireProjectChanges` refuses an overlay with an external change before any journal or write, and delivery reports no external changes; delivery still scans the read-only participants the fork recorded, and `Driver.deliver` passes them as prompt sources so a change after the fork makes the result historical. Red-then-green: `CommandsTest.rejectsExternalTargetAuthorization`, `AuthorityTest.externalFilesAreReadOnly` and `externalWarningOffersNoEdit`, `DeliveryTest.externalChangeRefusedBeforeAnyWrite`, `FileCampaignStoreTest.refusesVersionEighteenCampaign`, `storesNoEditableExternalTargets`; `DeliveryTest.externalParticipantChangeIsHistorical` pins kept behavior. The `link` fixture README describes the new E7 check. The retired names remain only in rejection tests and in the verbatim Codex recording `author-turn-1.jsonl`, a dated capture that no supported consumer reads. Full build on the integrated tree 2122 tests, 0 failures; bats 44/44; at or over 1 s under load 22.7: `CodexSessionsContractTest` (1.31, 1.22 s), `BundledRuleAudienceTest.subagentGetsRoutingNotUserRules` 1.18 s, `TestabilityAuditTest.onlyMainReadsEnvironment` 1.14 s, none changed by WI-45. |
| WI-46 | Done 2026-10-05 (commits `6bc3b6e26`, `023cf2cc1`). User ruling (`design.md` §2.7, outcome table and D53): a round without a promotion keeps sampling the working candidate; a plateau requires that candidate to exceed the failure limit. `Plateau.isReached(round, counts)` holds only for a complete round while a working-candidate cell has more than `EditOrContinue.FAILURE_LIMIT` failures; the three plateau sites in `TrialAdoption` call `endUnproductiveRound`, which otherwise clears the round, keeps the working candidate and its retained attempts and diagnoses, and restarts admissions at a window of 1. The plateau status text names the limit. The gate's fourth condition follows the ended round: each working-candidate FAIL on a regression test case becomes settled and no longer blocks `CommitGate.isMet` until a later verdict on that test case replaces it; a promotion never carries it (`RegressionTestCase.settled`, `campaign.json` `format_version` 20, version 19 refused). Red-then-green: `PlateauTest` (sequential and weak rounds resume within the limit, plateau above it, a later failure uses edit-or-continue, a resumed candidate commits at the commit gate without its settled test case being drawn again), `RevertTest.restoresPredecessorAndResumesSampling`, `DriverTest.failedRoundResumesSampling`, `StatusReportTest.plateauNamesFailureLimit`, `CommitGateTest.settledRegressionDoesNotBlockGate`, `FileCampaignStoreTest` round trips and `refusesVersionNineteenCampaign`; `laterFailureOnSettledInstanceBlocksGate` and `promotionDoesNotCarrySettledState` were checked by mutation. Full build on the integrated tree 2136 tests, 0 failures, none at or over 1 s; bats 44/44. E1 restarts on this build. |
| WI-47 | Done 2026-10-05 (Claude `optimize-high` runner fix integrated as `69cfc88e1`, `optimize-medium` parser fix as `05686afd0`; the integration updated `CodexSessionsContractTest` to expect the recorded turn's `low` effort in `9decff8d8`; full build 2144 tests, 0 failures). Found in E1's receipt: all 1085 Codex trial records had an empty per-cell identity. `CodexProcess.sendTurn` now passes the start-up notifications, including `thread/started`, to its listener before the turn's own, and `AppServerEvents` takes the model and effort from the root thread's latest `thread/settings/updated`, because `thread/started` reports the thread's default effort. Red runs: the listener's first line was the `turn/start` response; the parser reported `medium` for a turn at `low`. |
| WI-48 | Done 2026-10-05 (Claude `optimize-xhigh`, integrated as `742bed9b6` and `9decff8d8`; full build 2144 tests, 0 failures; at or over 1 s at load 5.9 while Codex runs were active: `DriverTest.developmentCellsGrowOnFormalFailure` 1.48 s, `certify` 1.27 s, `failedAttemptReproducesBeforeDevelopment` 1.15 s, `failedRoundResumesSampling` 1.01 s). Each trial session end appends a `TrialSession` (start and end times, concurrency, throttle state, rate-limit windows, login class, token usage) in place of the token-usage list; format version 21. The login class is read once when the driver opens. The integration removed the end-after-start check, because a wall-clock step made the driver throw (`TrialSessionTest`). A rejection without windows records the throttle before its hold, which `trialEnded` applies afterwards. |
| WI-49 | Done 2026-10-05 (`99a51e094`, `07cb3a578`, `045709c2c`). Found by E7: after `use_authorized_source`, each round raised the same warnings again (17 prompt author attempts, 0 candidates; the external file's sha256 is unchanged, `476f03e1…7d13a`). The campaign records answered warnings (format version 22; version 21 is refused), a round's warnings merge into one pending decision, an answered warning is not raised again, each later prompt author is told the declined edits, and an external link is no longer listed as editable. Tests failed first: `TransitionsTest` (merge, answered warning raises nothing, a new warning lists only itself, stop and revise record nothing), `FileCampaignStoreTest` (reload, version 21 refused), `PromptAuthorTest` (declined edits, none, external and in-project links) and `DriverTest.promptAuthorIsGivenTheAnsweredWarnings`. Full build after integration: 2162 tests, 0 failures, none at or over 1 s. E7 rerun pending (WI-15). |
| WI-50 | Done 2026-10-05 (`1bc655ef9`). A contract author, judge, triage or prompt author session that reaches its time limit now fails with "The <role> session reached its <N>-minute time limit"; a trial still completes and is judged. `start` and `revise` report a contract author that did not complete by its reason, as interrupted, or as rate-limited. Tests failed first: `RunnerSessionsTest.failsAtTheTimeLimitUnlessTrial` (4 roles, `Completed` instead of `Failed`) and `OptimizerCoreTest.startReportsIncompleteContractAuthor` (4 rows, record text in the message). Full build after integration: 2170 tests, 0 failures; at or over 1 s under load 6.0 (an E7 contract author was running): `DistributionTest.drawsBalanceTestsWithinCategory` 2.36 s, `GeneratorTest.addedValuesTakeHalfOfDevelopment` 2.25 s and `drawsKeyValuesWithinThePart` 1.14 s, `ProposalSchemaTest.rejectsInvalidScalars` 1.06 s and `rejectsMissingScenarioCitations` 1.05 s. Found by the E7 rerun on 2026-10-05 (campaign `20261005T225952Z-b214e7`): the contract author's output turn was stopped at the 30-minute limit after streaming 18 KB in 698 s (the previous E7 contract author streamed 26 KB in 125 s); codex-lb shows the request cancelled after 698 s with its first token at 66 s. `start` reported a schema problem instead of the time limit. |
| WI-51 | Done 2026-10-05 (`f4fe2f46d`, `92eb13bc8`). `Authority` takes the fork inventory and treats the in-project target of each link at or below a prompt source, after following the chain, as a prompt file; external, loader-configuration, cycle and outside limits are unchanged. Tests failed first: `AuthorityTest` (link target, edit at the link and the target path, chain, directory link), `LinkReportTest.promptSourceLinkReportsNoLimit`, and `DriverTest.linkTargetEditBecomesCandidate`, which reproduced E7's pause ("The prompt author session failed 3 times ... OUTSIDE_PROMPT_SOURCES"). Full build after integration: 2180 tests, 0 failures; 15 tests took 1.1–1.8 s at load 18 from another project's Rust build, none of them new. Found by the E7 rerun on 2026-10-05 (campaign `20261005T225952Z-b214e7`): after `use_authorized_source` the answered warning was not raised again, but three prompt authors edited `docs/handbook.md`, which the prompt author's prompt listed as editable, and each attempt was rejected as `outside_prompt_sources` for its target `shared/handbook.md`; the campaign paused with "The prompt author session failed 3 times". The external file's sha256 is unchanged. E7 resumes after the Codex build is installed. |
| WI-52 | Done 2026-10-06 (`2bdc11b4b`, Claude `optimize-medium`). Each harness event reader records whether the model produced output in the turn in progress (reasoning, reply or reply delta, tool or command item, streamed record, or reported token usage) and which hooks are running. `Session` starts that record when it sends each turn and keeps its state at the moment the time limit passes, so the interrupt's own events do not count. A trial whose turn reached the limit with no output and no running hook fails with "The trial's turn <N> reached its <M>-minute time limit with no model output". Tests failed first: `RunnerSessionsTest.failsWhenTurnHasNoOutputAtTheLimit` (2 rows) and `failsNamingTheTurnThatHadNoOutput` (`Completed` instead of `Failed`), `AppServerEventsTest` (12 errors) and `StreamJsonEventsTest` (9 errors). Full build after integration: 2222 tests, 0 failures; at or over 1 s at load 18: the unchanged `BettingProcessTest.shortcutAgreesWithExactArithmetic` 1.06 s. |
| WI-53 | Done 2026-10-06 (commit `361e32bcf`). `HarnessCapacity` keeps one hold per harness in `hold.json` (`format_version` 1, `ends_at`, `backoffs`) under the harness lock directory, updated under `hold.lock`; `tryAcquire` and `acquire` wait while it lasts, so the optimizer and both runner CLIs honour it. A rejection takes the reset time when later; otherwise a rejection or a refusal after a hold ends extends it by 1, 2, 4, 8, 16 or 30 minutes; no update shortens it; a completed turn resets the sequence. Codex final `error` notifications with 403 or 429 under any `codexErrorInfo` key and Claude `api_error_status` 403 are read as refusals and rejections. `Session` records the hold, `SessionResult.Refused` and `RateLimited` carry it, and the `Driver` copies it into the campaign throttle; a refused trial reruns as `RATE_LIMITED`, refused judge, triage, values and prompt author sessions rerun without counting as failures, and a refusal after a 30-minute hold stops the slice as an infrastructure failure "The provider refused <harness> requests after a 30-minute hold". `campaign.json` stays at version 24. Red-first tests in `HarnessCapacityTest`, `AppServerEventsTest`, `StreamJsonEventsTest`, `ThrottleTest`, `DriverTest` and `OptimizerCoreTest`; the agent lost the red output to compaction. Full build on the integrated tree 2254 tests, 0 failures; at or over 1 s under load: `DistributionTest.drawsBalanceTestsWithinCategory` 2.19 s, `GeneratorTest` (1.84, 1.72 s), `TestabilityAuditTest.onlyMainReadsEnvironment` 1.02 s, none changed by WI-53. |
| WI-54 | Withdrawn 2026-10-06 (user ruling). Codex rejects retry limits for its built-in `openai` provider, and a provider under another id changes what Codex sends: its `version` header (the Codex version, `model-provider-info/src/lib.rs:388`), the behaviour keyed to the provider name "OpenAI", and the provider id in Codex's turn analytics. Codex keeps its default retries; the shared hold of WI-53 paces new sessions. |
| WI-55 | Done 2026-10-06 (commits `9df965f1c`, `b034e2129`). Each development failure persists its trial's transcript and effects paths (`TrialFiles` on `CampaignEvent.Complete`, `LogicalTrial` and `Failure`); `campaign.json` and trial records are at `format_version` 24 and version 23 is refused. At prompt author start the `Driver` stages the working snapshot and renders each failure's section through `SessionPort.renderEvidence(transcript, effects, source)`; the judge-version hashes are unchanged. `PromptAuthorPrompt` gives each failure an `evidence_section`, `null` when the transcript or effects are gone, and `prompt-author.md` explains that case. Red-then-green: `PromptAuthorTest.eachFailureCarriesItsTrialEvidence` and `failureWithoutRetainedEventsStatesIt`, `DriverTest.promptAuthorIsGivenTheFailedTrialSection`, `FileCampaignStoreTest.refusesVersionTwentyThreeCampaign` and `refusesVersionTwentyThreeTrialRecord`; `DriverTest.promptAuthorGetsNoUnretainedTrialSection` checked by mutation. Full build on the integrated tree 2229 tests, 0 failures; at or over 1 s under load: `TestabilityAuditTest` (2.14, 1.81, 1.72 s), `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.66 s, `EditorPatchWorkflowTest.capturesPatchForEarlierFileVersion` 1.06 s, none changed by WI-55. |
| WI-56 | Done 2026-10-06 (commit `5a368be06`). `Plateau`, `Outcome.PLATEAU` and `PendingDecision.PlateauReached` are gone; a complete round with no promotable attempt always ends with the working candidate kept, a window of 1 and its failing regression test cases settled. The review checkpoint offers `accept without certification` when the working candidate is not the incumbent. `campaign.json` is at `format_version` 23 and a version-22 file is refused. Red-then-green: `RoundEndTest` (rounds above the limit resume; a later failure starts a new round), `RevertTest.resumesRestoredPredecessorAboveLimit`, `DecisionTableTest.acceptanceWithoutCertificationIsExplicit`, `KeepImprovementTest`, `DriverTest.partialFixRevertsAndResumes` and `failingCandidateStopsAtReviewCheckpoint`, `StatusReportTest.reportsStoppedCheckpoint`, `FileCampaignStoreTest.refusesVersionTwentyTwoCampaign`. `grep -ri plateau client/` finds only that refusal fixture and an unrelated implementation-planning rules scenario. Full build on the integrated tree 2223 tests, 0 failures, none at or over 1 s. |
| WI-57 | Done 2026-10-06 (commit `dd51be9ee`). Found by the E8 rerun of 2026-10-06 (`~/cat-client/worktrees/e8-codex4-b/receipts/slot-lock-evidence.txt`). `ExclusiveFileLock` keeps a JVM-wide set of held lock files, keyed by the directory's real path and the file name, claimed before the file is opened and released only after its channel closes; `HarnessCapacity` slots, `CampaignLock` and `HarnessRunLock` use it (the audit found the same defect in both: `Cleanup` and `HarnessRunManager` lock attempts in the holder's JVM), and the hold monitor is keyed by real path. A `LockFileOpener` seam records opens. Red first: 7 tests failed on the old code (`HarnessCapacityTest` 4, `CampaignLockTest` 1, `HarnessRunLockTest` 1, plus the aliased hold lock). Full build 2261 tests, 0 failures; bats 44/44. Slow tests (≥1 s, none new): `TestabilityAuditTest` three at 2.4–2.8 s, `OutputGatesTest.retainedOutputWorktreeLifecycle` 2.3 s, three others at 1.0–1.1 s. Exit met 2026-10-06 by the E8 Codex rerun (`~/cat-client/worktrees/e8-codex5-a/receipts/capacity.log`): across 8902 samples of the deduplicating sampler, the kernel never listed more than 16 locked slot files, 1398 samples held all 16 (987 of them split between the two campaigns' processes), and open slot files exceeded kernel locks for at most 2 consecutive one-second samples, which are slot attempts rather than held permits. |
| WI-58 | Done 2026-10-07 (`68d530903`, Claude `optimize-high`). Found in P15's development of the Codex read rule: most remaining failures began with a mistyped or invented path, and no edit had targeted them because they looked like model limitations. The prompt author groups the failures into kinds by each trial's first departure from the contract and corrects every `routing` and `guidance` kind; `not_guidance` covers only a test or contract defect or an infrastructure fault. For a kind that an earlier hunk targeted and that failed again, the prompt author states the consequence of violating the instruction where the model decides instead of restating it. The prompt author input holds the earlier manifests under the confirmed contract, oldest first, with their results (`rejected`, `no_candidate`, `not_measured`, `not_promoted`, `promoted`). The manifest's `kinds` (name, failures, classification, hunk ids) replace the per-failure classification; hunks carry prompt-author-chosen ids. Validation rejects a failure outside every kind or in two kinds, a `routing` or `guidance` kind without a hunk, and a kind naming an unknown hunk; an attempt whose kinds are all `not_guidance` or `conflict` produces no candidate. Status JSON `prompt_author_diagnoses` entries carry `attempt` and `kinds` instead of `classifications`. `campaign.json` is at `format_version` 25 and version 24 is refused. Red first: `PromptAuthorManifestTest` (outside every kind, two kinds, guidance kind without a hunk, unknown hunk, all `not_guidance`), `DriverTest.notGuidanceEditProducesNoCandidate`, `FileCampaignStoreTest.refusesVersionTwentyFourCampaign`, `PromptAuthorTest.statesEarlierManifestsAndKindRule`. Full build after integration: 2275 tests, 0 failures, none at or over 1 s. A `not_guidance` or `conflict` kind that names hunks is accepted (the work item names three rejection rules). |
| WI-59 | Implemented 2026-10-07 (main agent) and committed with WI-60 to WI-69 in `79b88cd197` (2026-10-10T22:03Z). `PromptAuthorApproach` holds nine approaches; `Round.approach` continues a cell's list from `OptimizationEvidence.promptAuthorAttempts` and wraps. Red first: `RoundTest.approachesContinueAcrossRounds`, `OptimizationEvidenceTest.countsEarlierAttemptsPerCell`, `DriverTest.approachContinuesFromEarlierRound`. The ninth approach, giving the exact text, was added the same day at the user's request. Full build: 2273 tests, 0 failures; 4 tests outside this change at or over 1 s (`OutputGatesTest.retainedOutputWorktreeLifecycle` 2.35 s, `ReplayExecutionTest.replaysOrderedBatchesWithMatchingTag` 2.16 s, `OutputGatesTest.draftOutputWorktreeLifecycle` 2.14 s, `TopicReplayCompletionTest.preservesReceiptAcrossCleanupRetry` 1.06 s). |
| WI-38 | Done 2026-10-04 (Claude `optimize-xhigh`). Integrated `f7100f7db` (R-C18), `560cabcde` (R-C19 with the lower-effort stress setting) and `8e71d2abb` (Codex's 5000-token tool-output limit as the next stress setting, a derived development cell `<cell>/tool-output-5000` that persists, and the limit recorded in the observed identity; full build 2048 tests, 0 failures, none at or over 1 s); full build 2032 tests, 0 failures; at or over 1 s at load about 4: `DriverTest.heldOutInstancesStayOutOfDevelopment` 2.5 s and `DriverTest.developmentCellsGrowOnFormalFailure` 1.9 s, which now also run reproduction rounds, `DriverTest.certify` 1.0 s, and the unchanged `BettingProcessTest.shortcutAgreesWithExactArithmetic` 2.1 s, `CapCalibrationTest.alternativeBudgetCapsMatchReference` 1.6 s and `DecisionBoundariesTest.comparisonBoundariesAgreeWithComparisons` 1.4 s. Reproduction trials are targeted and stay out of representative counts. The 5000-token limit is the value P15 reproduced its failure under. The runner bridge's path to Codex has unit tests only. The reports' gap for development cells outside the scope is WI-44. |
| WI-35 | Done 2026-10-04 (Claude `optimize-high`; integrated as `159c329fe`; full build 1955 tests, 0 failures, none at or over 1 s). `CellTier.openCells` gives each contract category's development cells and each other scope cell whose development cells and lower cells are certified; an effort is higher when some model lists it later in the inventory and none earlier, and an unranked model or unordered effort is neither below nor above. `Scheduler.planFormal` takes from each contract category its open cell with the fewest adopted outcomes, then ranks the offered cells as before; baseline planning is unchanged. Two existing tests' fixtures certify the lower cells so the cells they test open. `design.md` §3.3 rule 4 now says "contract category", defined in §2.1, because §2 and §3 call one cell a category. User ruling (`design.md` §3.3 rule 4, D45, 2026-10-04). |
| Campaign ceiling option (WI-15 E8 finding) | Done 2026-10-02. The campaign ceiling was always 8, so two campaigns never reached the shared capacity of 16 and E8 could not observe the bound. `start <target> [--max-concurrency <1..16>]` sets it (default 8; 0, 17, a non-integer or a repeated option are refused before a campaign exists); the stored `ceiling` field is optional on read (absent means 8, format 11 unchanged); status shows it. Full build 1767 tests, 0 failures; bats 32/32. Pre-existing tests over 1 s: `TestabilityAuditTest` `productionReferencesNoTestSupport` 1.2 s, `coreReferencesNoHarness` 1.3 s, `onlyMainReadsEnvironment` 1.5 s. The skill's `start` step does not yet mention the option (a prompt change, awaiting the user). E8 reruns with two campaigns at 16. |
| Launcher help (WI-15 E8 finding) | Done 2026-10-02. Both E8 agents ran `prompt-optimizer --help` or `start --help` to find the ceiling option; the launcher answered "Unknown command" or took `--help` as the target, and the skill stopped. `help` alone, or `--help` or `-h` anywhere, now prints the usage as `{"status":"help"}` with exit 0 before the core is called (`CommandsTest.printsUsageOnRequest`). |
| Inline include mention (WI-15 E8 finding) | Done 2026-10-02. Both E8 campaigns failed every prompt author session with "Direct rule include must name a regular file inside its allowed source root": `prompt-file-dependencies.md` mentions `` `<!-- cat:include path -->` `` in prose, and the role-rule loader took the mention as a directive. A directive is now a line that consists of the directive alone (`ProjectRuleLoaderTest.keepsIncludeDirectiveMentionedInText`, red before the fix); every packaged directive already stood on its own line. Verify: build 0, Bats 32/32, 1834 tests, none ≥1 s. |
| Status comparability (WI-15 E1 and E7 finding) | Done 2026-10-02. Every campaign's status said the improvement over the incumbent and the median trial input tokens were unavailable, with the raw text `INDETERMINATE: [JUDGE, MODEL, REASONING, HOST_TOOLS, EXTERNAL_SOURCES]`: campaigns stored by earlier builds lack the judge, model and reasoning on both sides, and no runner observes host tools or external sources. Fix (`design.md` D38): comparability is per cell; a condition only one side records is `INDETERMINATE`, one neither records is unobserved and a limitation; reasons are sentences naming the condition and cell; the per-cell median trial input tokens no longer depend on comparability. No stored-format change. On a copy of the E1 campaign `20261002T214009-0da57b` the status now shows +78.6, +100.0 and +100.0 points and both medians per cell (working 55710/55434/55440, incumbent 82673/27615/27616); evidence in `~/cat-client/worktrees/v1.0-status/target/status-evidence/`. Full build 1770 tests, 0 failures; bats 32/32; no test over 1 s. |
| Round position, shared references and stopped status (WI-15 E1 finding) | Done 2026-10-02 (Codex xhigh; in the WI-26 build). The status showed the second round as "Round 4" (the round's first reserved attempt number); it now shows each round's position in text and JSON. A weak round's three attempts each ran their own working-candidate reference runs (27 targeted trials instead of 9); a reference run for a test case is now planned once per working candidate, serves every attempt, is excluded from the attempts' counts, and resumes once after a restart. A campaign stopped at a plateau reported `awaiting-user` and the pending plateau; it now reports its outcome (`not certified: plateau`), "The user stopped the campaign." and its choices. |
| Prompt sources at session start (WI-15 E5 finding) | Done 2026-10-02 (Codex high; full build 1676 tests, 0 failures, none at or over 1 s; bats 32/32). An E5 campaign paused `MEASUREMENT_CHANGED` on `PROMPT_SOURCES` because the value listed hooks of later events (Codex `subagentStart`) that run only when a trial takes that action. `design.md` §5 now limits the condition to sources loaded at session start; both readers keep only session-start hooks (`AppServerPromptSourcesTest`, `StreamJsonPromptSourcesTest`, each failed first; `DriverTest.certify` covers equal values not pausing). |
| Proposal text names the user's project (WI-15 E1 finding) | Done 2026-10-02 (Codex high; full build 1702 tests, 0 failures; bats 32/32). The E1 proposal's conflict evidence linked the contract author's staged copy (`.cat/work/optimizer-runs/.../project/AGENTS.md:6`) instead of the user's file. `ContractAuthorProtocol` now replaces the session's staged project root with the project root in every string of the contract author's output before validation and storage; paths to session artifacts outside the staged project stay unchanged (`ProposalTest`, 24 cases, 23 failed first). |
| Status token usage (WI-15 E1 finding, R-E12) | Done 2026-10-02 (Codex high; full build 1712 tests, 0 failures, none at or over 1 s; bats 32/32). `status` in its own process reported "The harness reported no token usage." for a campaign whose sessions all reported usage, because only one `run` slice's in-memory evidence held it and contract author usage was never collected. Every finished trial, judge, prompt author and contract author session's reported usage is now stored by role in the campaign's optimization evidence (format 10), and a trial record keeps its session's usage; the status computes its statistics from that stored evidence. Format 9 records load with no usage, and their `input_tokens` still feed the per-cell medians. |
| Development report cells (WI-15 E1 finding) | Done 2026-10-05 (Claude `optimize-low`; integrated as `2e776203d`; full build 2118 tests, 0 failures, none at or over 1 s). A `weakest_cell` campaign's development report listed all 80 inventory cells, each "not certified (not ready)", although 4 were in scope; it now lists the scope's cells and the development cells (`StatusReportTest.developmentReportsOnlyScopeCells`). |
| Outage retry (WI-15 E1 finding) | Done 2026-10-05 (Claude `optimize-medium`; integrated as `e4bcfd61c`; full build 2119 tests, 0 failures; `TestabilityAuditTest.onlyMainReadsEnvironment` 1.05 s, pre-existing). A provider outage made 3 logical trials of the E1 campaign each reach the infrastructure-failure limit, and one `retry` resumed only the first, so the user was asked again for each remaining trial. `retry` on a trial-failure pause now clears the count of every trial that a pending trial-failure decision names and resolves all of those decisions; other pending decisions keep their order (`TransitionsTest.retryResumesEveryTrialFailureOfAnOutage`, red before the fix). |
| User timezone for times (user request) | Done 2026-10-05 (Claude `optimize-medium`; integrated as `14618af50`, `94560c5c6` and `6eb4b3734`; full build 2123 tests, 0 failures, none at or over 1 s). Times written for the user are shown in the user's timezone with the zone, and every other time stays UTC unless the user asks for another zone. An always-on rule (`user-timezone.md`) states this for agents. The status summary shows the throttle hold in the system timezone while the report JSON keeps UTC (`StatusReportTest.summaryShowsHoldInUserTimeZone`, `CommandsTest.printsSummaryTimesInUserTimeZone`). Topic-replay backup branch names use UTC instead of the system timezone (`BackupTest.namesBackupBranchWithUtcTime`). Stored campaign and run files, campaign identifiers and session-history timestamps were already UTC. |
| Data-structure rule routing (user request) | Done 2026-10-05 (integrated as `810be256e`; full build 2119 tests, 0 failures, none at or over 1 s). The project rule `data.md` refers to this project instead of CAT, and the default project rules route to it instead of the Java rule. |
| Skill launcher paths (WI-15 finding, R-I5) | Done (`a1d70a4bd`; full build 1568 tests, 0 failures, none at or over 1 s; bats 32/32). Codex still ran `${CAT_PLUGIN_ROOT}/client/bin/prompt-optimizer` literally in 2 of 4 runs although the injected skill-environment rule named the plugin directory. The user chose skill-relative paths: every skill names `client/bin/<launcher>` in the plugin directory two levels above the skill's directory, and `BundledSkillLauncherTest` fails if a shipped skill writes the variable (it failed first on all 8 skills). After reinstalling, 6 of 6 Codex runs called the launcher by its absolute path first. |
| Real Git in tests (user ruling 2026-10-02: assume third-party tools work as documented) | Done (`18891bd45`, `f8f57259c`; full builds 1463 and 1461 tests, 0 failures; bats 32/32). The runner tests no longer use Git: `HarnessProjectRepository` and `CodexCuratedPluginCache` expose the commands and environment they give Git, and their tests assert those. `JlinkImageBuilderTest` asserts the launcher text instead of starting launchers and Maven wrappers; `GeneratorTest` checks `Generator.select` at each probability boundary instead of 40,000 draws. git-replay (`04ec22dfd`, one commit per the user's ruling; full build 1567 tests, 0 failures, none at or over 1 s; bats 32/32): its decisions are extracted and tested over captured facts, Git processes fall from 1,845 to 264, and nine integration tests keep real Git, one per Git operation, each under 1 s. §8 lists them and `GitTest`. |
| Fake contracts (doubles audit follow-up) | Done (`06515f1c8`, `7e8a2795d`; full builds 1514 and 1510 tests; bats 32/32). Shared contracts run `FakeWorkspacePort` against `Workspace` and `FakeHostInterruptSignal` against both harnesses' interrupt signals; the fakes were corrected where checks failed. `FakeJudgeProtocol` is deleted: the driver tests use the real `JudgePrompt` and script only the judge session's JSON judgment. |
| Convention clean-ups (convention-pass follow-up) | Done (`4a6723065`; full build 1463 tests; bats 32/32). `Harness.commandName()`, `FileCampaignStore.directory`, `CommitGate.meetsPassRateThreshold` and `EnumNames` each own a rule that was repeated. |
| R-M4 comparability wiring (gates audit) | Done (`5cda9e922`; full build 1530 tests, 0 failures; bats 32/32). `Comparability` had no production caller. A condition missing from both observations is now `INDETERMINATE`; the baseline, frozen comparisons and token evidence keep their measurement identity (campaign format 6); decisions, keep-improvement offers, token comparisons and the status go through `Comparability`. Each path's test failed first. Proof adds `BaselineTest`, `CommitTest`, `DecisionTableTest`, `KeepImprovementTest`, `OptimizationEvidenceTest`, `StatusReportTest`. |
| Complete file reads (Codex rule `read-file.md`, user-directed) | P15's candidate p15-28 met the bar below on the fixed tasks but failed certification on held-out test cases (see the P15 row). A candidate that certifies is delivered to v1.0 (user ruling, 2026-10-04: integrate it once it certifies); p15-60 certified and is delivered (`c54dd9796`, `a1434c7bf`; the rule equals p15-60's text except the script path, now `<!-- cat:plugin-root -->/client/bin/read-file`; full build 2015 tests, 0 failures, none at or over 1 s; bats 0 failures in 6 suites, `read-file.bats` 12/12; both harnesses reinstalled). Delivery found two defects in its `read-file`: the next-part command for a path that needs shell quoting loses its backslashes, because awk's `-v` reads escape sequences, and GNU-only `stat -c` and `sha256sum` fail on macOS. They are fixed in the script without a new certification, because the rule text is unchanged (`design.md` D48, user ruling, 2026-10-04); `read-file.bats` shows that the fixed script prints the same transcript as the certified one, kept as a fixture, for plain paths, continuation and repeated reads, and the same with BSD stand-ins for `stat` and `shasum`; macOS itself is first exercised by CI. The fix also bounds parts in bytes under a UTF-8 locale and reads the file from standard input, so a path containing `=` works. Prompt-only (no hooks). Bar: the `design.md` §2.7 commit gate in each development cell, then a certification attempt by the optimizer's rule (see the P15 row; user ruling, 2026-10-04) on Luna low, Luna medium and Sol high, and on Luna low at `tool_output_token_limit = 5000`; every failure counts, search overreach (receiving over 90% of a file to answer a search or count) included; a wrong count is tallied apart. The candidate bundles `read-file`, which prints a file in parts of at most 18,000 bytes, each ending with the command for the next part or `[end of <path>]`, and per Codex thread redirects a repeated read to the next unread part. Prompt-embedded scripts failed under the 5,000-token limit or on copy errors; a bundled `search-file` was dropped because agents used it instead of loading lazily named files. Hints in tool output that offer a way out of reading also made agents stop loading lazily named files. Variant seq44 (`read-file` only) passed its screen with the current `read-file` (276 runs, no failure) and passed certification on Luna low (177/177), but failed at the 5,000-token limit: on lazy run 20 the agent read only part of an unrequested file, then searched the glossary that `AGENTS.md` told it to load instead of reading it. Variant seq49 (every file an instruction names is read before any other file is searched or read) was stopped after 250 of its 440 screening runs, all passing, because the P15 campaign below restarts this rule from its v1.0 text; that campaign's candidate also needs this bar's cells before delivery. The probe harness and evidence are in `/home/gili/cat-client/worktrees/fullread-probe` (`ff.py`, `score.py`, `c-*.out`). Delivery, after the user approves a certified variant: bundle the scripts under the plugin's `client/bin` with bats tests, name them through `<!-- cat:plugin-root -->`, and update the rule and its scenarios. Redevelopment (user design, 2026-10-07): the rule teaches only a bundled `capture '<command>'`, which saves the command's output to a file and prints its first part; each part ends with an instruction that names the next command (`read-file` on the saved file) or says the output is complete, so the rule never names `read-file`. These screens score loss only: a run fails when output that the agent ran never reached it in full, and overreach and wrong counts are tallied apart. On luna5k lazy, 250 runs each, the quoted form lost output in 5 runs and the piped form (`<command> 2>&1 | capture`) in 21, mostly because the agent piped `read-file` into `capture` again, so the quoted form is used. Rewording the per-part instruction (no full rerun command; finish a capture even when its first parts seem to answer; nothing after the next command but a period) brought the losses to 4 of 550: luna5k lazy 4 of 250, long 0 of 100, search 0 of 100, `gpt-6-luna` at `low` plain 0 of 100. Two of the 4 read a rule under a wrongly substituted `${CAT_PLUGIN_ROOT}` and stopped, and two left a capture partway. The user ruled that tuning continues and that the plugin directory may be stated before it is used (2026-10-07). Writing the directory in place of the variable lost output in 1 of 550; the user chose instead to inject the harness `skill-env.md`, which states the directory, before every other rule (`105efbb41`, `ProjectRuleLoaderTest` 21/21). With that order the screen lost output in 4 of 550: lazy 1 of 250, long 0 of 100, search 0 of 100, plain 3 of 100 (two runs set `max_output_tokens` to 1,000 on the capture of the rules, and one never read them). A user-directed experiment (2026-10-07) replaced the full-read requirement with a correct answer, gave each part that stops early the notice that Claude Code's Read tool shows for a partial view, and dropped the instruction to read to the end. On the same 100 test cases per task, it answered correctly in 334 of 400 (lazy 72, long 99, search 63, plain 100), against 221 of 400 for the same rules without capture or any read guidance (9, 89, 23, 100) and 327 of 400 for the reorder screen (85, 100, 42, 100): stopping after the first page loses the style guide's sign-off on lazy, while counting from search output beats counting a whole file by eye. Claude's notice as cmw's per-part footer (cmx, 99 interleaved pairs per task): correct answers search 40 vs 42 (p 0.87), lazy 94 vs 88 (p 0.15); lost output 70 vs 1 and 70 vs 0 runs, nearly all rules-file lines, because the model stops after the first part; cmw's footer is kept. The cn line (2026-10-08 to 2026-10-11) replaces `read-file` with two scripts: `run-part` runs a command, saves its output and exit status, and hands them to `read-part`, which prints one part that fits Codex's 10,000-token exec budget and ends with a bracketed instruction naming the command that prints the next part, until `[End of …]`. A PostToolUse hook replaces that instruction, when Codex cut a part because the agent set `max_output_tokens`, with one that says to rerun the command without it. On luna5k, against no prompt with the same rules delivery (100 interleaved pairs per task), cnp read the whole output in 400 of 400 runs against lazy 8, long 29 and search 12 of 100, and answered correctly on lazy 78 vs 31, long 100 vs 93 and search 39 vs 33 (p 0.25). In `gpt-6.1-sol` at `low`, a Code Mode script sometimes printed the call's whole result object as JSON, whose escaping made a full part overflow the budget; `read-part` now sizes each part after JSON escaping (worst wrapped part 39,600 of 40,000 bytes). The probe gave the agent its rules in a file named by a SessionStart pointer, but CAT's Codex SessionStart hook delivers them inline as one developer message, with `read-file.md` as the section before Codex CLI Rendering. Readiness therefore reruns with the rules inline (cnq: cnp's guidance in that section plus the PostToolUse hook; cns: no prompt) in `gpt-6-luna` at `low` and `high` and `gpt-6.1-sol` at `low`, 100 pairs per task per cell (user ruling, 2026-10-11: readiness in the certification cells first). The readied candidate is cob, whose guidance is the section right after CAT Environment, so `ProjectRuleLoader` now injects `read-file.md` right after `cat-env.md` (user ruling, 2026-10-11). Answers are scored by a text check that recognizes the common correct replies and, when it fails, by a judge (`gpt-6.1-sol` at `high`) that decides the same statement; the check missed replies such as "session-store" for the session store (user ruling, 2026-10-11). Under that scoring cob passed readiness in all three cells: whole reads in every run of every cell and task, and no task answered significantly worse than no prompt (cns), the closest being `gpt-6-luna` at `high` search, 86 vs 93 of 100 pairs (p 0.09; 86.2% over all 400 cns runs). The user approved cob as certification attempt 1 (2026-10-11). Certification follows as prompt tests in the order `gpt-6-luna` at `low`, at `high`, then `gpt-6.1-sol` at `low`, each at `p_min` 0.95, with the readied candidate as attempt 1 (user rulings, 2026-10-08; the ruling named cnp, which cnq replaces). WI-70 ports the scripts and hook; WI-71 writes the prompt tests. |
| Optimizer terminology (user rulings, 2026-10-09) | Implemented 2026-10-09, uncommitted (main agent; full build 2276 tests, 0 failures; `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.60 s at load 4.0, pre-existing and not touched by the renames). A test definition is a prompt test, each drawn instance a test case, and its variation rules are variables (`PromptTest`, `DrawnTestCase`, `RegressionTestCase`, `TestCaseOutcomes`, `Variable`, `VariableValue`, `VariableChoice`); a trial profile's model and effort keep the name dimension. The deriver is the contract author and the author the prompt author: `SessionRole.CONTRACT_AUTHOR` and `PROMPT_AUTHOR`, the `PromptAuthor*` and `ContractAuthorProtocol` classes, the JSON keys `prompt_author`, `prompt_authors`, `prompt_author_attempts` and `prompt_author_diagnoses`, and the resources `/optimizer/prompt-author/` and `/optimizer/proposal/contract-author*`. Proposals are at `format_version` 11 and campaigns at 29; the judge version is 3 in both harnesses. Kept: the recorded Codex session transcripts and their README, whose names (`author-turn-1.jsonl`, `deriver-turn-*.jsonl`) belong to the recordings. The uncommitted WI-59 lines of this plan follow the new names too (user ruling, 2026-10-09). |
| WI-60 to WI-69 (prompt tests, user rulings 2026-10-10) | Committed together in `79b88cd197` (2026-10-10T22:03Z); `dd3f26d8c4` (2026-10-11) fixed `ToolScopeTest.defaultsWorkToProjectDirectory`, which failed in the builds below, and renamed it `defaultsToWorkingDirectory`. WI-60 implemented 2026-10-10 (full build 1886 + 183 + 188 + 31 tests, 0 failures; `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.44 s, not touched). The contract carries `minimum_pass_rate` (0.95 by default, 0.80–0.99, at most two decimals; the proposal schema requires it; campaigns at format 31). Writing `p_min` for the minimum pass rate, for the number of shortfalls `n_short` = 9, 3, 1, the below-minimum bet against the pass rate `p_min − n_short·(1 − p_min)` is `min(1, n_short·(1 − p_min)/p_min)`; a bet of 1 empties its term after the first pass, and the logarithmic shortcut skips that term. The planning rate and commit-gate pass rate are `1 − (1 − p_min)/5`, the batch limit `floor(20·(1 − p_min))`, the edit limit `floor(40·(1 − p_min))`, and the extended baseline applies below `p_min`; the limits use exact decimals because doubles floor `20·(1 − 0.80)` to 3. Applicability of a certification record takes the current `p_min`. The status report names `p_min` in each comparison (`minimum_pass_rate` in the JSON). Each behavior's test failed first. WI-61 implemented 2026-10-10 (full build 1930 + 183 + 188 + 31 tests, 0 failures; only `OutputGatesTest.retainedOutputWorktreeLifecycle` at 1.35 s, not touched): `Scenario.parse` refuses each malformed form at its line; `PromptTestFiles` reads `scenario.md`, `fixtures/` (symbolic links refused, strict UTF-8 decides text) and `values.json`, validating each committed test case; `ValuesFile` streams and refuses unknown fields and duplicate keys; `ValueGenerators.create` skips taken seeds; `log_lines` version 1 is pinned by a SHA-256 digest; `Fixtures.copy` checks every target before writing and removes what it wrote on a failure; `RegressionScenarios` marks each scenario current or earlier with the reason. WI-62 implemented 2026-10-10 (full build 1932 + 183 + 188 + 31 tests, 0 failures; `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.35 s and `TestabilityAuditTest.onlyMainReadsEnvironment` 1.01 s, neither touched): a test case's part is the low bit of the SHA-256 of the salt and its normalized expected outcome; committed test cases, and new ones sharing a committed outcome, are development; draws are uniform within the part (generator `contract-generator/3`); a test with fewer than 2 distinct outcomes in either part is refused, naming both counts; the contract author writes test cases (`values` and `expected_outcome`) and never `committed_test_cases`; after a failed attempt it writes at least 4 per failed test, and CAT refuses one whose normalized outcome equals a held-out one without naming the part, or whose values repeat an existing test case's ignoring case and spacing; campaigns at format 32. WI-63 implemented 2026-10-10 (full build 1933 + 184 + 189 + 31 tests, 0 failures; at least 1 s: `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.39 s, not touched, and `DriverTest` `heldOutTestCasesStayOutOfDevelopment` 1.50 s, `developmentCellsGrowOnFormalFailure` 1.50 s and `certify` 1.21 s, whose many simulated trials now each decide checks and render a judge prompt, with no single hot spot in a profile; their Javadoc records this): `tool:` lines parse into the built-in tools (hyphenated names, quoted arguments with `\"` and `\\` escapes, placeholders filled after splitting, optional `turn=<n>` for reply checks), each naming the behavior its failure shows; an unknown check or unsuitable arguments are refused at the scenario line; controls and discarded checks are gone, and the proposal refuses `checks` and `discarded_checks`; checks and the judge read one trial record; an `exit-status` check is refused in the proposal when the harness reports no exit status; CAT adds `expected_outcome` (a guard exactly when every declared assertion is one; declaring it is refused); a trial passes when every assertion passes; a failed check ends the trial without a judge session; the judge decides the other assertions and `expected_outcome`, returning a verdict, rationale and behavior for each; the core-task requirement is removed; judge version 4 in both harnesses, proposals at format 13, campaigns at format 33. The planned `JudgeVerdictTest` and `JudgeProtocolTest` cases live in `JudgePromptTest` and `JudgeTest`. WI-64 implemented 2026-10-10 (full build 1932 + 184 + 189 + 31 tests, 0 failures; at least 1 s: `DriverTest` `certify` 1.85 s, `developmentCellsGrowOnFormalFailure` 1.51 s and `heldOutTestCasesStayOutOfDevelopment` 1.48 s, whose Javadoc records the reason, and `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.52 s and `TestabilityAuditTest.onlyMainReadsEnvironment` 1.05 s, neither touched): `Category` is removed and each prompt test is its own claim; a cell is a prompt test under one profile, named by the test id when the test has one profile and `<test>@<model>/<effort>` otherwise, ordered by test, then by profile; the risk rank belongs to each prompt test; benchmark weights are each cell's held-out test-case weights; a draw consumes one stream value (generator `contract-generator/4`); a proposal with `categories`, a campaign at format 33 and a certification record at format 2 are refused; the proposal's Markdown lists each prompt test with its risk rank; judge and triage evidence no longer name a category; judge version 5 in both harnesses, proposals at format 14, campaigns at format 34, certification records at format 3. WI-65 implemented 2026-10-10 (full build 1959 + 184 + 189 + 31 tests with 1 error: `ToolScopeTest.defaultsWorkToProjectDirectory` resolves the CAT work root to `client/.cat/work`, which the session-start hook created at 2026-10-10T07:32Z, not to the worktree's `.cat/work`; the test is not part of WI-65 and every other test passed; at least 1 s: `DriverTest` `certify` 2.51 s, `heldOutTestCasesStayOutOfDevelopment` 2.50 s, `developmentCellsGrowOnFormalFailure` 2.31 s and `cellsRunUnderTheirProfiles` 1.74 s, which now also run control trials and whose Javadoc records the reason, and `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.38 s and `TestabilityAuditTest.onlyMainReadsEnvironment` 1.07 s, neither touched): `ControlComparison` computes `E_ctl↑` and `E_ctl↓` as exact rationals; a cell's comparisons start when its minimum-pass-rate part certifies, over every assertion of its prompt test, and stay in effect when later formal trials fail; the `j`-th control trial copies the test case of the cell's `j`-th candidate formal trial in assignment order, and adoption pairs them; extra formal trials run up to the cap to complete pairs; a cell certifies only when every comparison passes, and one comparison below control ends the attempt `below control`, with no failure groups but keep-improvement still offered; the prompt author's brief names each assertion below control with exact pass counts (`below_control`); the commit saves the control source, a fork of the incumbent overlay with every target prompt file emptied to zero bytes, and the proposal lists those paths; the certification record keeps each cell's per-assertion pairs (format 4); the status report gives the minimum-pass-rate part as `certified`, `clearly below` or `undecided` (also at the cap), each assertion's comparison as `passed`, `below control`, `undecided` or `not run` with the candidate's and the control's passes, and the cell's state at `α_i`; the minimum remaining trials add, per undecided cell, the fewest further pairs that pass its slowest undecided comparison if both trials of every pair pass (the pairs are shared, so the maximum over comparisons, not a sum), plus the formal trials those pairs lack, and have no minimum when a comparison cannot pass within the cap; campaigns at format 35. WI-66 implemented 2026-10-10 (full build 1972 + 184 + 189 + 31 tests with 1 error, the same `ToolScopeTest.defaultsWorkToProjectDirectory` as in WI-65; at least 1 s: `DriverTest` `certify` 2.74 s, `heldOutTestCasesStayOutOfDevelopment` 2.46 s, `developmentCellsGrowOnFormalFailure` 2.49 s and `cellsRunUnderTheirProfiles` 1.75 s, whose Javadoc records the reason, and `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.39 s and `TestabilityAuditTest` `onlyMainReadsEnvironment` 1.15 s and `coreReferencesNoHarness` 1.02 s, none touched): after a proposal validates and before CAT presents it, `GuardPilot` runs 20 targeted control trials of each prompt test in its weakest cell, on development test cases only, within the campaign's concurrency ceiling; a failed built-in tool decides the trial without a judge session, an invalid judge output is retried up to 3 times and then counts as a failed run, and a rate limit or refusal stops the pilot and records why; presenting a proposal records its trial fixtures and derived prompt sources into the fork, so a pilot's files match the formal control trials' files (user ruling, 2026-10-10: the fork persists the participants it recorded and the selected ones in `fork-participants`, written before any copy; before confirmation a later proposal leaves out files only earlier proposals needed, their copies stay in storage and are restored on re-selection, and the campaign's incumbent is rebound with its usage evidence kept; confirmation only checks that the fork holds them and refuses files that appeared after presentation; after confirmation the fork only grows: presenting a revision records the files it needs into a prospective fork that its pilot reads, confirming it moves the campaign onto that fork (`ExtendFork` rebases the incumbent and the working candidate, which keep their numbers and edits), and each earlier fork's selection is kept in `fork-versions/` so earlier candidates still load (user ruling, 2026-10-10, replacing the freeze of the same day); a left-out file's external link targets leave the fork with it unless a held link still reaches them, user ruling 2026-10-10); a stored pilot is reused when its test's definition, its weakest cell and the control source's files are unchanged, so a revision, before or after confirmation, reruns only changed prompt tests; pilots are stored in `held_out/pilot.json` (format 1); the proposal's `Guard pilot` section shows each assertion's pass rate and warns on a guard below `p_min`, and the revise brief carries the previous proposal's pilot results; pilot sessions enter no count and spend no allowance. The planned `DriverTest` cases live in `GuardPilotTest` and `OptimizerCoreTest`. The slow `DriverTest` cases were split on 2026-10-10 (user approval) so that each starts from the state its claim needs: `certify` starts in a running attempt with each cell one tied control pair short of certifying, and `commitControlsTheEmptiedIncumbent` checks the commit's emptied control source; `commitWaitsForEachProfileCell` and `cellsRunUnderTheirProfiles` split the two-profile trace at the commit; `developmentSamplesWeakestCells`, `developmentCellsGrowOnFormalFailure` and `commitWaitsForTheFailedCell` split the tiered trace; and `heldOutTestCasesStayOutOfDevelopment` starts in a running attempt whose last weaker cell is one control pair short. The obsolete variable, check-evidence and category sources that nothing used, with their tests and the unused `secrets` generator classes, were deleted on 2026-10-10 (user approval). Full build 1977 + 184 + 189 + 31 tests with 1 error, the same `ToolScopeTest.defaultsWorkToProjectDirectory`; at least 1 s at load 4.4: `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.35 s, untouched (an earlier build at load 5.9 also listed `CleanupTest.otherEndingsKeepFiles` 2.10 s and `DriverTest.authorityWarningTrace` 1.72 s). The fork's growth after confirmation was implemented 2026-10-10 (full build 1983 + 184 + 189 + 31 tests with 1 error, the same `ToolScopeTest.defaultsWorkToProjectDirectory`; at least 1 s: `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.38 s, untouched). WI-67 implemented 2026-10-10 (full build 2003 + 184 + 189 + 31 tests with 1 error, the same `ToolScopeTest.defaultsWorkToProjectDirectory`; at least 1 s: `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.32 s, untouched): a certified delivery and a kept incumbent write each confirmed prompt test's `values.json`, holding the confirmed contract's test cases and then the campaign's added ones without repeats, and its `scenario.md`, after the candidate's changes and in path order, through the same journal, preimage checks and verification; a prompt test's directory follows its prompt source (`.cat/rules/<p>.md` to `.cat/tests/<p>`, a module resource to that module's `src/test/resources/<p>`, any other file to `.cat/tests/<p>`); a created test's directory must not exist (a structural collision otherwise), an edited test rewrites the recorded scenario, keeping its preamble, and replaces one that no longer parses, and a recorded scenario that the user changed since the fork is a conflict that stops before any write; a file equal to the fork's copy is not rewritten; design goals that would not read back are left out; a proposal whose test cannot be written as `scenario.md` is refused, naming the test and the offending line; keeping the incumbent delivers the prompt tests alone and returns `Delivered` without a label, and cleanup follows only that verified delivery; `choose` now delivers when the choice ends the campaign with a delivery, which accepted choices never did before; staging for trials, the pilot and measurement leaves out the confirmed contract's prompt test directories; a campaign that stops writes none. Fixtures are not delivered yet because proposals carry none; WI-68 adds them. WI-68 implemented 2026-10-10 (full build 2021 + 184 + 189 + 31 tests with 1 error, the same `ToolScopeTest.defaultsWorkToProjectDirectory`; at least 1 s: `OptimizerCoreTest` `reviseRerunsChangedTestPilot` 1.91 s, `revisesPausedConflicts` 1.92 s, `revisionLeavesOutDroppedFixture` 1.37 s, `runExecutesOneSlice` 1.27 s, `removalIsReported` 1.24 s and `retainIncumbentDeliversPromptTests` 1.24 s, `DeliveryTest.promptTestFaultResumesOnce` 1.03 s and `OutputGatesTest.retainedOutputWorktreeLifecycle` 1.40 s, none recording a reason yet): proposals carry each prompt test's fixtures, which confirmation records, trials stage with the test case's values and delivery writes; generated values take seeds from the campaign salt; the runners accept `--fixtures`; a fixture path that collides or lies under `.git` is refused at confirmation, and a recorded fixture that an edited test leaves out is kept; the proposal shows each test's fixtures, its scenario's status and a diff against the recorded scenario; the contract author's request lists every recorded scenario with its format and the content of its text fixtures, so it can convert one in an earlier format; each value generator describes its call, words, dates and numbers, and the contract author and values prompts list them (`log_lines` numbers lie between 0 and 9000999); the prompt author receives each failure's failed assertions and behaviors, the ended attempts' failed assertions and below-control comparisons, and is told never to edit prompt test files; the triage prompt shows the expected outcome; the contract author, values, prompt author and triage prompts were rewritten for prompt tests, assertions and expected outcomes; `judge.md` is unchanged. Committed test cases in a values file are read with the scenario but not yet added to a new campaign's proposal. WI-69 implemented 2026-10-10 (full build 2022 + 184 + 189 + 31 tests; the only failure is `ToolScopeTest.defaultsWorkToProjectDirectory`, which fails without this work too): all 81 recorded scenarios are in the prompt-test format, and `RegressionScenariosTest.everyScenarioIsCurrent` loads every one without a refusal. Each scenario declares variables placed so that test cases differ in their expected outcome, marks each assertion `effect` or `guard`, and moves its context into the turn. The read-file scenarios have their own `fixtures/` copies, generated from one script, in place of the shared `project/` and `lazy-project/` directories; their search question counts releases for one of 12 components that occur 10 to 45 times. `failure-regression-promotion.md` records scenarios as prompt-test directories. Runner instructions that the format cannot express, such as the launcher answers of the `prompt-optimizer/first-use` scenarios and the scripted monitor of `state_aware_backoff`, are kept in `## Design Goals`. `design.md` §1–§6, §8–§10, §13 (D60–D66) and §14 record the design. |
| Prompt-test follow-ups (user requests, 2026-10-10) | Done (`79b88cd19`, `53ba28ea4`; full build 2023 + 184 + 189 + 31 tests with 1 error, the same `ToolScopeTest.defaultsWorkToProjectDirectory`; at least 1 s: `TestabilityAuditTest.onlyMainReadsEnvironment` 1.10 s). Built-in checks are built-in tools: `BuiltInTool`, `BuiltInToolResult`, `ToolLine`, `ToolRecords`, `ToolSupport` and `UndecidableToolException`, `Assertion.builtInTool()`, and the judge and triage evidence `tool_results`; Java identifiers say `builtInTool` and prose says built-in tool, because the agent's own tools already use the name tool. The judge, triage, prompt author and contract author prompts and the proposal schema follow, and the judge version is 6 in both harnesses. A new campaign's proposal now receives the committed test cases of each recorded scenario that a prompt test cites: the contract author's request lists them with the scenario, CAT adds them to the citing test, and a generated value's seed that a committed test case took is not drawn again (`ProposalTest.citingTestReceivesCommittedTestCases` failed first). `ProcessFixture` and `GateCommandFixture` start their child JVMs on the class path with tiered compilation stopped at C1 and the serial collector, which cut `ProcessHandleTerminatorTest.matchingProcessTreeIsTerminated` from 2.18 s to 0.17 s and `OutputGatesTest.retainedOutputWorktreeLifecycle` from 1.56 s to 0.41 s; a shared `JsonMapper` for citation identities trims the driver tests, and the Javadoc of `DriverTest.weakRoundSelectsBestThenRunsSequentially` and of `ReconciliationTest` `promotionRecordedBeforeWorktree` and `promotionMaterializedBeforeSnapshot`, which run a campaign through promotion and minimality, records why they can take over a second, as does `TestabilityAuditTest.onlyMainReadsEnvironment`, whose shared scan parses every optimizer source; parsing those files in parallel saved under 0.1 s and was not kept. The audit's mutable-static-field sample had become `private int test case;` in the earlier rename of instance to test case and is `private int instance;` again. The first release run of this work failed on every platform: `OptimizerCoreTest` `removalIsReported` and `retainIncumbentDeliversPromptTests` deliver, and delivery diffs against the project's `HEAD`, but the fixture's `.git` was an empty placeholder, so locally Git found the enclosing worktree and on CI no repository; both tests now commit the prompt in a real repository. On arm64, `BettingProcessTest.fullBetKeepsLogarithmFinite` read -2.2e-16 for the e-value's logarithm before any outcome, because weights of 1/3 have no exact double; it now allows rounding. A full build with the temporary directory outside any Git repository passed the same tests with the same 1 error. |
| Prompt-test follow-ups 2 (user requests, 2026-10-10) | Implemented 2026-10-10, uncommitted (full build 2027 + 184 + 189 + 31 tests with 1 error, the same `ToolScopeTest.defaultsWorkToProjectDirectory`; the same with the temporary directory outside any Git repository; at load 9 to 11, 16 tests took at least 1 s in each build, a different set each time and none of them new: the most frequent were `TestabilityAuditTest` scans, `OptimizerCoreTest` delivery paths, `DeliveryTest.promptTestFaultResumesOnce` and `CleanupTest.otherEndingsKeepFiles`). `design.md` records why an assertion's kind is declared rather than derived: the guard pilot warns on a guard below `p_min`, which a kind derived from the pilot's own rates could never do, and the kinds decide the kind of `expected_outcome`; control trials and the control comparison cover every assertion whatever its kind. A prompt test inherits the files of every `fixtures/` directory from its test root (`.cat/tests` or a module's `src/test/resources`) down to its own, an inner file replacing an outer one at the same path; staging leaves out the inherited directories, and delivery does not copy an inherited fixture that the test leaves unchanged into the test's own directory (`FixturesTest.inheritsAncestorFixtures`, `RegressionScenariosTest.readsInheritedFixtures`, `DeliveryTest.inheritedFixtureStaysShared` and the `PromptTestLocationsTest` exclusions failed first). The `prompt-optimizer/first-use` launcher answer moved from `fixtures/` to `launcher/`, so it is not copied into every first-use trial. The read-file scenarios keep their own fixture copies until the user chooses how to share them. `completion_scope_accounting` now has its plan, `docs/implementation-plan.md`, with one terminal global-wave row and the readiness work packages as the variables `verified_packages` and `open_packages`. The unused rule test files were removed (`ee146e08e`). |
| Key-value scenarios and fixture patterns (user request, 2026-10-10) | Implemented 2026-10-10 (full build 2034 + 184 + 189 + 31 tests, normal and with the temporary directory outside any Git repository; the only error in each was `ToolScopeTest.defaultsWorkToProjectDirectory`, which assumed the checkout root three levels up and failed once a nested `client/.cat` made `client` the nearest project; it is now `defaultsToWorkingDirectory`, which compares the default scope with an explicit one for the working directory, and both builds then ran 2034 + 184 + 189 + 31 tests with no failure; `ProposalSchemaTest.rejectsTestThatCannotBeWrittenAsScenario` now uses a turn that opens a code fence, because a turn with a `## Notes` line is now written as a `description` pair and reads back; at least 1 s: `DeliveryTest.promptTestFaultResumesOnce` 1.09 s, whose Javadoc now records why). `scenario.md` declares each variable as a `- **<name>**: <description>` pair and each assertion as `kind`, optional `tool` (one code span) and `description` pairs; a value continues on lines indented by two spaces; `## Scenario` and `### Turn <n>` hold prose or a `description` pair, and CAT refuses an undefined key, prose in a key-value section and a mixed section. The optional `## Fixtures` section lists `patterns` as code spans of the form `<directory>/fixtures/<glob>` (Java glob syntax), by default `fixtures/**`; a pattern must stay inside the test root, an explicit one must select a file, and a later pattern's file replaces an earlier one. The implicit inheritance of ancestor `fixtures/` directories is removed. Staging leaves out the shared directories that a cited scenario's patterns name; delivery keeps an unchanged shared file shared and writes a changed one into the test's own `fixtures/`, appending `fixtures/**` or an exact pattern. All 81 recorded scenarios were converted, with pair values wrapped at 120 characters, and the read-file scenarios read one pool, `client/codex/rules/src/test/resources/codex/read-file/fixtures/`, in place of their own copies; `general_knowledge_needs_no_command` declares `sign_off` and `search_needs_no_full_read` declares `change` for the pooled files' placeholders. The contract author prompt, the prompt author prompt and `failure-regression-promotion.md` describe the new format; the Markdown form of a key-value pair is a project rule (`.cat/rules/include/data.md`), not a plugin rule, so it does not govern end users' Markdown (user ruling, 2026-10-11). `design.md` §6.3 and D67 record the design. |
| WI-70 | Scripts and hook done (`92e3ebda3`, `18904fad8`, `890aed453`, `58be665f0`). The rule step changed in `0615af40f`: Codex SessionStart writes the session rules to `.cat/work/temp/codex-context/<session_id>.md` and injects only the read-file guidance with a pointer to read that file whole through `run-part`, because Codex truncates long additional context; `read-file.md` names `run-part` and `read-part`, and `client/bin/read-file` with its bats suite, fixture and CI steps is retired. The certified-wording step still waits for WI-71. |
| WI-71 | In progress. Prompt tests committed (`06d7f5075`); the general-knowledge test was removed (`a61c0120c`) because other bundled rules, such as Clear Writing, require reads before any reply, so a compliant agent failed it. Control trials omit the rule and its PostToolUse hook (`fcf108b5a`). The certification start of 2026-10-15 ended without a proposal: the contract author reached its 30-minute limit after re-typing the four recorded scenarios into the proposal and reading the distribution build to find each plugin file (`design.md` D77, D78). WI-81 to WI-85 are done. Attempt 5's certification start (2026-10-17, a fresh clone of `3d89d1d979`) produced a proposal, but all 200 guard-pilot trials were refused before a trial session: Codex lists CAT's `session_start`, `subagent_start` and `post_tool_use` hooks as `modified` in the host Codex home, whose approved hashes predate `9fe8738efb`'s `hooks.json` change, so the runner refuses them and Codex skipped them in the host session too (its session directory has no `rules.md`). The user had the current hook hashes approved and kept only the `complete_command_output` extra requirement (2026-10-17); `revise` passes that review and reruns the pilot once WI-86 is installed. The user chose the `weakest_cell` certification scope (2026-10-17). `revise` first stopped on a file name containing whitespace (fixed by `01eb12c64f`); its rerun produced a revised proposal, but `search_long_record_preserved` showed its first pilot, all 20 runs refused, because a stored pilot with failed runs was reused (fixed by `a9bdefed46`: such a pilot reruns). That proposal's guards below 95%: `search_needs_no_full_read`'s `component_named` 9 of 20 and `expected_outcome` 8 of 20, `file_update_preserved`'s `updated_file_contents` 3 of 20. `search_needs_no_full_read`'s pilot failed 11 of 20 control trials by refusing to read the trial's own `docs/lookup_N.md` as denied: the Codex runner denies each of the 511 other runs by name, and Codex shows the model about 212 of them and says the rest are omitted (a probe with 300 named denies refused 3 of 30 times, one run-root deny 0 of 30). The `file_update_preserved` fixture ends with an extra blank line, so `updated_file_contents` passed 3 of 20. The user ruled on 2026-10-17 that the run root is denied as one entry (`design.md` D80, H35); the Codex runner does so on Linux under session environment version 5 (`e993042379`; full build 2883 tests, none at least 1 s), which pauses the campaign and reruns every pilot. Both plugins were reinstalled from `cd230bc8e2` (WI-87) on 2026-10-17. The user approved the revise; it fixed `updated_file_contents` (20 of 20) but reused `search_needs_no_full_read`'s earlier pilot, because the stored pilot's key left out the session environment and judge versions (fixed by `4bdc1f0f6a`; full build 2897 tests, three `RunnerSessionsTest` cases 1.8 s under full-build load and below 0.5 s alone). That revise's `search_long_record_preserved` pilot passed `identifier_not_shortened` and `expected_outcome` only 2 of 20 times: the control agent dropped or added 16-character blocks of the 528-character identifier. Revise4 (2026-10-17) reran every pilot: `search_needs_no_full_read`'s `component_named` and `expected_outcome` 9 of 20, `search_long_record_preserved`'s two judged guards 6 of 20, every other guard at least 95%. Its control trials still refused their own project as denied: the run-root deny misleads agents without the read-file guidance (H35); hiding the other runs with a bubblewrap mount stopped it in a probe. Of `search_long_record_preserved`'s 14 failed pilot trials, 9 returned the identifier with too few or too many of its 32 repeated blocks, 3 found no record, and 2 refused their project as denied. In a probe of control trials without any deny, an identifier of 512 random hex digits came back exact 15 of 20 times and wrong 0 times, and the repeated one exact 3 of 21 times and wrong 12 times; the other trials (5 and 6) answered without a tool call that the file was unavailable, as 8 of 31 control trials of `search_needs_no_full_read` did. Open: the user's review of the guards, then a revise on the installed build, carrying the fixture fix. The Codex runner now denies the run root on every platform except a Linux session with evidence under it, and gives each session a developer note that the denial does not cover its project or home (`6818942507`, session environment version 6; full build 2902 tests, three `TestabilityAuditTest` cases 1.1 to 1.3 s), so every pilot reruns. Revise5 (2026-10-17, on that installed build) reran every pilot: `search_needs_no_full_read`'s `component_named` 12 of 20 and `expected_outcome` 9 of 20, `search_long_record_preserved`'s `identifier_not_shortened` and `expected_outcome` 6 of 20, every other guard at least 95%. Of the first test's 8 control trials that answered without reading, 2 cited the denied path and 6 said the file was not in their context, as control trials without any deny also do. At the user's request, revise6 (2026-10-17) shortened `search_long_record_preserved`'s archive identifiers to 10 characters, placed after 538 bytes of parcel metadata so the matching line still runs past the 500-byte search preview: its `identifier_not_shortened` and `expected_outcome` rose to 18 of 20; one failure searched with the wrong case and stopped, the other refused under the run-root deny. `search_needs_no_full_read` kept 12 and 9 of 20. At the user's request to raise these guards above 95%, revise7 phrased both tests' turns as "Look up …" (59 of 60 luna/low control trials named the component, against 53 of 60 for the question), and revise8 let the rubric accept capitalization, a leading article and punctuation in a component, tag or label answer, keeping opaque identifiers exact. Revise8 kept its earlier pilot because a stored pilot's identity left out the judge; `1d5ab0289b` adds the judge to it. Revise9 (2026-10-17, on that build) reran every pilot: `search_needs_no_full_read`'s guards 19 of 20, `search_long_record_preserved`'s answer guards 18 of 20, every other guard 100%; all three failures declined without a command. Control trials with the "Look up" turn named the component in 156 of 160 under the run-root deny with the developer note, 94 of 100 with each other run denied instead, and 94 of 100 with no deny, so these refusals are the control agent's own rate rather than the deny's, and two other note wordings did no better (95 and 94 of 100). The user declined certification for now and had the revised tests carried over from that campaign's draft (`7d50d0c45d`, in the `workspace/` layout). A new campaign (`20261018T084117Z-4dba83`, 2026-10-18, a fresh clone of `7d50d0c45d`, gpt-6-luna low and high and gpt-6.1-sol low, `weakest_cell`, the revise8 rubric) produced a proposal and guard pilot on gpt-6-luna/low: `search_needs_no_full_read`'s `component_named` 4 of 20, because the draft dropped its pattern check and the judge failed correct answers that ran `cat`; `search_long_record_preserved`'s `identifier_not_shortened` 18 of 20; `pipeline_operation_preserved` 19 of 20; the new extra guard `ordinary_response_guidance_loaded` 0 of 20 and the campaign's new `changed_action_response_route` test 5 of 20, both about the default rules' routing; every other guard 100%. The proposal again lists six extra requirements beyond `complete_command_output`. The user ruled (2026-10-18): the inline-answer test allows reads that the production rules require; keep only `complete_command_output` among the extra requirements and drop `changed_action_response_route`; restore the four answer pattern checks, with the judge as fallback, and judge an answer assertion on the reply alone; keep `weakest_cell`. The revise that applied them (2026-10-18) reran the affected pilots with no failed run: `search_needs_no_full_read`'s `component_named` 20 of 20, `search_long_record_preserved`'s `identifier_not_shortened` 19 of 20 (the failure said the file could not be accessed), every other guard 100% of its scored trials. The campaign's agent then ran `confirm … weakest_cell` and `run` without the user's approval, reading the relayed scope ruling as the skill's "confirm by choosing the cells"; the campaign is in development with 0 trials and a revision request. The user kept that confirmation (2026-10-18). The agent's later review request left a pending revision that differs only in its limitations text, which the user approved confirming with `weakest_cell`. Development reached candidate 10 over 35 rounds without a certified cell; its seventh campaign turn stopped on 2026-10-19 when the disk filled (`IOException: No space left on device`). The next turn paused on a changed measurement condition, the judge: version 9 re-indents the judge prompt with tabs and is otherwise identical. The user chose `new identity` (2026-10-19), which restarted development of candidate 10 from a new baseline. The user then moved this certification to the end of the plan and assumed that a whitespace-only change does not change compliance (2026-10-19); the turn was ended with candidate 10 at 1 outcome per test, and the campaign waits in its store. Open: the campaign's development and certification, after every other work item and gate that does not need them. |
| WI-72 | Done (`6470f0f78`). |
| WI-73 | Done (`d95e8c71e`). WI-81 replaces the contract author's plugin under test with the prompt test configuration (`design.md` D78). |
| WI-74 | Done (`088a4d0a6`). |
| WI-75 | Done (`1d170e9ae`). |
| WI-76 | Done (`a8b88937f`). |
| WI-77 | Done (`b33bc2a3b`). |
| WI-78, WI-79, WI-80 | Done (`7c3588e74`). |
| Current-tree build, 2026-10-16 | On `afa0b9e86`: the full build passed in 1:14 (2222 + 195 + 257 + 51 tests, 0 failures, 10 skipped); no test took 1 s or more. |
| WI-81 | Done (commit `a8e01990c`); the full build passed on the integrated tree (2758 tests, 0 failures). Runners take `installPlugins` with a `SetupCommands` callback; a trial passes the CAT plugin as `--artifact` with `--install-cat` and every other listed plugin as `--plugin`; role sessions pass neither. A failed build keeps no plugins and the target stays startable; a source fix committed to the base after the fork reaches the optimization worktree only after the campaign is cancelled and started again. `ProposalSchemaTest` proves unknown keys are refused with `trial_plugins`, because the exit check forbids the old key's name anywhere in `client`. The proposal lists files that load in production but not in trials. Open: the live part of the exit check (CAT builds itself on the fork commit and the proposal names the Codex rules mapping) runs in WI-71's next start. |
| WI-82 | Done (commits `26c0363751`–`582d37200e`); the full build passed on the integrated tree (2800 tests, 0 failures, 10 skipped; no test took 1 s or more). Before each contract author session CAT seeds a draft directory with the target's recorded prompt tests; the proposal file names each test's draft directory; `contract check` and `contract fixtures` run from the launcher with a fixed salt of 0, so their seeds reveal nothing of the split; the separate output turn and both output-format prompts are removed; the judge's input is unchanged, so the judge version stays 8. Open: the live part of the exit check (a proposal within the contract author's time limit) and whether the launcher runs inside each harness's sandbox are checked in WI-71's next start. |
| WI-83 | Done (commits `18322e516c`–`9fc61d1be1`). The full build passed in a session (2861 tests, 0 failures) and with both session variables unset (it used `client/target/work-temp`); no test took 1 s or more. Live checks, 2026-10-17, in a probe project: a Claude and a Codex session each had rules naming `.cat/work/<session-id>/temp`, and that directory and its marker existed; Claude ships no `run-part`, so the `run-part` checks ran in Codex only. Codex `run-part` saved into the session's `temp`; a subagent's `run-part` saved into the directory its injected rules name (its `agent_id`); `seq 1 20000`, read in four parts, left no `run-part-*` folder after the last part. The next SessionStart removed one Codex and one Claude directory whose marker was 25 hours old and whose transcript was gone, and kept the old ones whose transcript exists, a new one, one without a marker and `temp/`. The live check found that a subagent which Codex starts with its parent's conversation follows the parent's instruction to read the parent's rules file, which states the parent's `CAT_TEMP`; the subagent's rules now begin by telling it not to read that file (`9fc61d1be1`), and a rerun confirmed it no longer does. Later the subagent's rules moved to its own file, `.cat/work/<agent_id>/rules.md`, whose pointer first says that the parent's rules file belongs to the agent that started the subagent and must not be read, then repeats the primary agent's pointer (`ea3aeb7405`, `81af15808b`, `7d8f6e169e`); without `agent_id` the rules are inlined. Probe of forked subagents on gpt-6-luna/low, 2026-10-17: the primary pointer alone left 9 of 10 reading the parent's file, adding only "ignore the earlier instruction" 2 of 8, the committed wording 0 of 9. The full build passed (2881 tests, 0 failures, 72 s); no test took 1 s or more. |
| WI-84 | Done (commit `453dfacfeb`). The full build passed (2876 tests, 0 failures, 105 s); no test took 1 s or more. Every launcher command, `status` included, needs the host session variable and refuses without it. A campaign of another session is shown by `status` with its owning session; every other command for it, and `start` for its target, is refused with the command that resumes that session. Campaigns under the old `.cat/work/optimizer/` are neither read nor deleted. Live exit, 2026-10-17: WI-71's certification start in a Codex host created its campaign at `.cat/work/<thread-id>/optimizer/<campaign-id>/`, under the host thread's ID, and no `.cat/work/optimizer/` was created. |
| WI-85 | Done (commit `16feb4b9f1`); the full build passed on the integrated tree (2819 tests, 0 failures, 10 skipped). A certified delivery appends its record to `certifications.json` in the target's test directory, creating it when absent and never changing an earlier record; staging leaves it out; a record's harness is its recorded provider. `status` and a new campaign's proposal list the latest record of each harness with its applicability and the earlier records as history; `status` JSON replaces the top-level `attempt`, `scope` and `applicability` with `latest` and `earlier` (the skill shows only `summary`). A malformed history is refused at its JSON path. Open: WI-71's certified delivery appends its record. |
| WI-86 | Done (commit `22248dc5c0`); the full build passed in 81 s (2878 tests, 0 failures, 10 skipped; `TestabilityAuditTest.onlyMainReadsEnvironment` 1.03 s, not touched). The proposal names a trial plugin's files by plugin, shows an edited test's `values.json` as a note, generated values without seed or version, and scope cells as test × profile groups; stored derived-source paths are unchanged. Open: WI-71's revised proposal uses the format. |
| WI-87 | Done (`a684ea7a6c`, `ca04c4bbf7`, `f6e35de12d`). The user ruled on 2026-10-17 that SessionStart moves every existing session directory from `.cat/work/` to `.cat/sessions/`. Full build passed (2895 tests, 0 failures, none at 1 s or more); bats 33/33. The repository `.gitignore` keeps `.cat/work/` beside `.cat/sessions/`, because the move leaves `.cat/work/temp` in place. `design.md` records the move in §4.1 and D81. Live exit check, 2026-10-17, both plugins reinstalled from `cd230bc8e2`, in a scratch project seeded with a marked `.cat/work/<session-id>/` and `.cat/work/temp/keep.txt`: the Claude session read `.cat/sessions/<session-id>/rules.md` and the Codex session's rules name `.cat/sessions/<session-id>/temp`; each directory holds its marker (Claude's SessionEnd then deleted its rules file, as designed); the seeded directory moved to `.cat/sessions/`, and `.cat/work/temp/keep.txt` stayed. |
| WI-88 | Done. A recorded passing trial and a guard-pilot control trial that passes every assertion lose their effects and initial project; failed trials keep both (`design.md` D51, user ruling, 2026-10-17). Commit `90d3665183`; full build 2906 tests, 0 failures, 10 skipped, `TestabilityAuditTest.onlyMainReadsEnvironment` 1.07 s. The user also had WI-71's existing runs cleaned (2026-10-17): the 492 pilot trials that their judges passed, with every built-in tool passed or left to the judge, lost their project copies, freeing 111 GB; the 99 trials that a judge failed, the 427 that a built-in tool failed and the 200 refused runs keep theirs. |
| WI-89 | Done (user rulings, 2026-10-17): minimal trial workspaces; a prompt test's `workspace/` is the trial's root, its links that lead outside it reach trials as copies of their targets, and its other links stay links. f28029892c, 3db64f97b6, fd712bb3c2, 820553de6f, 2ad8bc43f7, 3236bf3e11, 6080715a42, d4d9c44fdc, 3eb5d8d83e, 4fe2d76299 and c0697fc325 implement staging, reading, refusals, delivery of links and the contract author's instructions; 402e734721 delivers a changed shared file as a copy; 684d23507a renames fixtures to workspace files and ignored files without compatibility; edffe7b9cb narrows the base source to `.cat/rules/` without the contract's ignored files; c7cd852415 retires the contract's ignored files without compatibility, and in-project dependents stay in the base source (user rulings, 2026-10-18); 869f4c54f6 renames the tests' leftover fixture names. The recorded prompt tests use `workspace/`. Full build 2026-10-18 at c7cd852415: 2927 tests, 0 failures, 10 skipped, no test 1 s or longer, 81 s; the renamed test classes pass at 869f4c54f6. WI-71's guard pilots reran in read-file campaign `20261018T084117Z-4dba83` (2026-10-18) on the `workspace/` layout with no failed run. |
| WI-90 | Done (user ruling, 2026-10-19): the contract author's request lists the files that load in trials, from the trial plugins that the prompt test configuration lists, not the files that load in the user's own sessions. Commit `7bbce9101f`; full build passed (2940 tests, 0 failures, 10 skipped; 4 min 13 s, of which tests took 71 s, 50 s in `client/common/cli`); no new test at 1 s or more. |
| WI-91 | Done: a dependent at a symbolic link removes the copy that trials see at the link's path and leaves the link's target unchanged; the proposal lists each reason why the control source cannot be built, and a test without a pilot gives that reason. Commit `27805aff58`; full build passed (2941 tests, 0 failures, 10 skipped; 4 min 34 s, of which tests took 72 s, 54 s in `client/common/cli`); the new tests take 0.02 s and 0.06 s. The build before it failed once in `BundledRuleAudienceTest.subagentGetsRoutingNotUserRules` (the bundled common rules were missing from the copied plugin; 3 of 3 isolated runs and the next full build passed), an intermittent failure not yet explained. |
| WI-92 | Done: the runner copies a link among a session's workspace files as a link and refuses, by name, one whose target is absolute or leaves the session's project, so a revision of a proposal whose prompt tests link a project file no longer fails. Commit `1222a545fe`; full build passed (2942 tests, 0 failures, 10 skipped; 4 min 32 s, of which tests took 87 s, 62 s in `client/common/cli`); the new test takes 0.09 s. 33 tests took 1 s or more, against 13 in the build before, none touched by this change, while another project's build loaded the machine. |
| WI-93 | Done: a guard pilot session that the provider rejects because of a rate limit, or refuses, runs again after the harness's shared hold, a trial with the same test case and a judge without spending an attempt; only a refusal after a 30-minute hold stops the pilot. Commit `0e11763633`; full build passed (2943 tests, 0 failures, 10 skipped; 109 s); the new tests take 0.17 s and 0.01 s. One test took 1 s or more (`TestabilityAuditTest.onlyMainReadsEnvironment`, 1.2 s), not touched by this change. |
| WI-94 | Done: the contract author's prompt says that `limitations` holds only gaps in what the contract requires or tests, and leaves out guard pilot results and other facts about one run. Commit `7d297c65d7`; full build passed (2943 tests, 0 failures, 10 skipped; 88 s). Four `OptimizerCoreTest` tests took 2.2 s to 2.4 s, none touched by this change. Exit: the Claude E1 run's revised proposal (2026-10-19, `~/cat/worktrees/e1-claude1/receipts/revise-final.txt`), made after both guard pilots finished, lists no pilot result as a limitation. |
| WI-95 | Done: the Claude runner writes each stream-json request with a JSON library, so a prompt that holds a tab or another control character is valid JSON. Commit `0cf58de40a`; full build passed (2944 tests, 0 failures, 10 skipped; 86 s; no test at 1 s or more). Exit: the Claude E1 run's `start` produced a proposal on that build (2026-10-19, `~/cat/worktrees/e1-claude1/receipts/start2-final.txt`). |
| WI-96 | Built, exit pending: the Claude adapter lists the files that a listed `CLAUDE.md` imports through `@path` as derived prompt sources, as Claude Code's documented loading rules do. Commit `d75e99cb01`; full build passed (2945 tests, 0 failures, 10 skipped; 92 s). Six `DeliveryTest` tests took 1.0 s to 2.2 s, none touched by this change. The exit needs a fresh Claude E1 run. |
| WI-97 | Done: `start` refuses a Claude prompt system that imports a missing file or a file outside the project, naming each import, before anything is created. An import is reported only when it is written as a file path, because Claude Code skips a mention such as `@types/node`. Commit `ae0e906113`; full build passed (2947 tests, 0 failures, 10 skipped; 96 s; no test took 1 s). |
| WI-98 | Done: in both harnesses, a project file that a project hook or MCP server runs is a derived prompt source, named with the hook's event or the server's name. Claude resolves `CLAUDE_PROJECT_DIR`; Codex reads `.codex/config.toml` through Jackson's TOML module, which its runtime now ships. Commit `4fb39b1b6f`; full build passed (2949 tests, 0 failures, 10 skipped; 86 s). `TestabilityAuditTest.onlyMainReadsEnvironment` took 1.004 s, untouched by this change. |
| WI-99 | Done: the proposal's prompt loading section lists every file that trials receive from the incumbent besides its prompt test's workspace files. The list is computed from the fork when the proposal is presented, so `OptimizerCoreTest.proposalListsTheFilesThatTrialsReceive` covers it rather than `ProposalTest`. Commit `5588aac475`; full build passed (2950 tests, 0 failures, 10 skipped; 81 s; no test took 1 s). |
| WI-100 | Done: after each prompt author attempt, CAT derives the files that load and run in trials from the attempt's copy; a project file outside the trial selection, or a missing or external import, makes the attempt fail as a prompt author failure that names each file. `DriverTest.attemptLoadsOnlyReceivedFiles` drives it through a new `FakeSessionPort.loading` seam. Commit `0fa539b932`; full build passed (2953 tests, 0 failures, 10 skipped; 81 s). Seven untouched tests took 1.0 to 1.4 s, led by `ProcessHandleTerminatorTest.matchingProcessTreeIsTerminated` (1.373 s) and `DriverTest.weakRoundSelectsBestThenRunsSequentially` (1.248 s). |
| WI-101 | Done: before the first contract author session, `start` runs each command hook of the prompt system and of the trial plugins that runs at session start (a matcher that selects `startup`) or prompt submission, without a model: once in a copy of the trial source with an empty harness home, and once in a copy of the fork with the user's home. Each adapter supplies the shell, the project, plugin and harness-home variables, and the event's input. A hook that succeeds only in the fork refuses the campaign with its exit status and the end of its error output; a hook whose output differs is listed in the prompt loading section with its first differing lines, and the proposal states the one-input limit. The result is stored in the campaign directory as `hooks.json`, so every presentation lists the same differences; a project without such hooks stages and copies nothing. Adapter coverage: `ClaudeSessionsTest.listsEarlyHooks`, `CodexSessionsTest.listsEarlyHooks`. Commit `72c915eeaa`; full build passed (2958 tests, 0 failures, 10 skipped; 85 s; no test took 1 s). |
| WI-102 | Done: both adapters list, with the files that load only in the user's sessions, each hook of the user's harness hooks file (Claude `settings.json` in the configuration directory, Codex `hooks.json` in its home), each plugin that the user's configuration enables and no trial plugin holds (Claude `enabledPlugins`, Codex `[plugins]` tables not disabled; a project-built trial plugin holds the plugin of its name without the marketplace), and each MCP server of the user's configuration (Claude `.claude.json` `mcpServers`, user scope only; Codex `[mcp_servers]` tables not disabled). The proposal shows them under the trial plugins. Coverage: `ClaudeSessionsTest.listsUserConfigurationThatTrialsLack`, `CodexSessionsTest.listsUserConfigurationThatTrialsLack`, `ProposalTest.listsTrialPlugins`; all three failed before the change. Commit `2e3e9dfa85`; full build passed (2960 tests, 0 failures, 10 skipped; 84 s; no test took 1 s). |
| WI-103 | Done: the Claude runner's isolated settings exclude, through `claudeMdExcludes`, the `CLAUDE.md`, `CLAUDE.local.md`, `.claude/CLAUDE.md` and `.claude/rules/**` of every directory above the run directory, so a trial no longer loads the user's project instructions; the session environment version is 4. Coverage: `ClaudeConnectionSettingsTest.excludesInstructionsAboveIsolationRoot`, which failed before the change; `ClaudeSessionEnvironmentVersionTest` hashes only the excludes at or below its root, since the directories above it differ between machines. Commit `416005eeaa`; full build passed (2961 tests, 0 failures, 10 skipped; 103 s; at or over 1 s: `TestabilityAuditTest.onlyMainReadsEnvironment` 1.20 s, untouched). Live (Claude Code 2.1.304, Haiku, the Claude plugin reinstalled from that build): a directory held all four files, and a session that the installed `claude-runner` started in a run root below it loaded only its own project's `CLAUDE.md`; a bare `claude` session there loaded all five. The Windows form of the patterns, with forward slashes, is untested. |
| WI-104 | Done (user decision, 2026-10-20): the Claude `PROMPT_SOURCES` value lists only plugins whose `init` path is not `builtin`, skills and agents in those plugins' namespaces, and skills, commands and agents that the session project's `.claude/skills`, `.claude/commands` or `.claude/agents` files declare by path or frontmatter `name`; built-in and account skills, agents and plugins are left out, and the harness version still identifies them. Coverage: `StreamJsonPromptSourcesTest.omitsBuiltInSources` and `StreamJsonPromptSourcesTest.listsProjectSkillsAndAgents`, which both failed before the change. Commit `74f6b41f7b`; full build passed (2963 tests, 0 failures, 10 skipped; 56 s while Codex campaigns ran; at or over 1 s, all untouched: four `RunnerSessionsTest` time-limit tests at 2.47–2.48 s, `DriverTest.attemptLoadsOnlyReceivedFiles` 2.13 s, `DriverTest.failedAttemptReproducesBeforeDevelopment` 1.18 s). |
| WI-105 | Done: `HarnessRunManager.pruneTerminalRuntimes` skips entries of the harness root whose names are not generated run identifiers. Every guard-pilot trial of the fresh Claude E1 campaign `20261020T023203Z-628bcb` failed with `harness and id must be generated runner identifiers`, because an empty `.claude` directory had appeared in the harness root `optimizer-runs/claude/claude`. Empty `.claude` directories also appeared in every directory above the trial runs up to `/home/gili/cat/worktrees`, which the previous E1 run (before WI-103) did not show. Cause: when a sandboxed Bash command changes directory from the working directory up to an ancestor, `bwrap` creates `.claude` in each directory on the way, reproduced under `strace` on a mirror of a live judge's settings; before WI-103 trials had no reason to leave their workspace. WI-107 keeps trials out of the project, which removes the reason. Coverage: `HarnessRunManagerTest.pruneSkipsEntriesThatAreNotRuns`, which failed with the campaign's error before the change. Commit `016cecf66f`; full build passed (2964 tests, 0 failures, 10 skipped; 58 s; none at or over 1 s). |
| WI-109 | Done (user request, 2026-10-20): a nested Claude session no longer receives the host's sandbox `allowWrite`, which granted the user's `~/.claude`, `~/.cat` and other host paths; it may write its own home and the user's `~/.npm` and `~/.m2`. A Codex session's writable roots and permission profile gain `~/.m2` beside `~/.npm`. Both harnesses keep the user's `MAVEN_OPTS`, which here names the Maven repository because Java takes `user.home` from the account (`/home/gili`) rather than from `HOME`. Claude session environment version 7, Codex 8. Live (Claude plugin reinstalled from that build): in the rerun Claude E1 guard pilot of campaign `20261020T023203Z-628bcb` (60 control trials, 2026-10-20T05:41Z onward), every file written under `~/.claude` and `~/.cat` belonged to a host session (its session records, environment, backups, shell snapshots and history) or to CAT's capacity slot locks, and each trial's reads and writes stayed in its run directory. No pilot trial used `~/.npm` or `~/.m2`, so the cache grants are shown only by the tests. |
| WI-112 | Done (2026-10-20, commit `e3e646d972`): `Forks.trackedLinks` lists the links that a project's Git index records (empty outside a work tree), `RegressionScenarios.read` receives them, and `InMemoryForks` supplies none. `TestabilityAuditTest.onlyForkAdapterConstructsGit` failed first on `RegressionScenarios.java:194`. `ForkTest.listsTrackedLinks` covers the lookup with real Git, `RegressionScenariosTest.refusesLinkCheckedOutAsFile` supplies the links, and `BackupTest.recordsAndDeletesBackupBranch` took over the UTC name check. Full build: 2987 tests, 0 failures, 54 s, none ≥1 s. A logging `git` first on the `PATH` recorded 487 calls (1121 before), all from §8 tests. |
| WI-113 | Done (2026-10-20, commit `673d89f9f8`): `FileReplacer` replaces `PortableFiles.replace`, and `ExecutableFiles` is an instance. Each waits between attempts through a `Pause` and measures its limit with a `Clock` that its creator supplies. Only the runner CLIs, the optimizer scopes, runner maintenance and the replay editor create the sleeping instances; tests use `Yielding` or a manual clock. A text-file-busy start now retries every 25 ms for up to 1 s instead of backing off three times. `TestabilityAuditTest.sourcesDoNotSleep` failed first on `ExecutableFiles.java:49`, `PortableFiles.java:156` and `PortableFilesTest.java:134`. `FileReplacerTest` and `ExecutableFilesTest` cover the retry, the limit, a failed pause and the platforms that fail at once. Full build: 2990 tests, 0 failures, 54 s, none ≥1 s; a logging `git` first on the `PATH` recorded 487 calls. |
| WI-114 | Done (2026-10-20, commit `051fcd3bfd`): authoring reads the stored form of the draft back against the user's project, and a prompt test's link that reaches a project link with an absolute or outside target is refused under the prompt test's link. `ProposalTest.refusesDraftThatStoredProposalRefuses` and `TestWorkspacesTest.refusesLinkToAbsoluteProjectLink` failed first. Full build: 2997 tests, 0 failures, 107 s; `TestabilityAuditTest.coreReferencesNoHarness` took 1.15 s because it was the first test to parse the audited sources, which `Scan`'s Javadoc records. The Claude E7 campaign `20261020T150959Z-28328a` holds a proposal stored before the fix and is cancelled; E7 restarts on the reinstalled plugin. |
| WI-115 | Done (2026-10-20, commit `82330d0e96`): commands take a campaign's prompt sources from the derived sources that the stored proposal file lists, without validating the proposal, so `cancel` and `status` work on a proposal that no longer validates. Resuming such an unconfirmed campaign derives a new proposal and lists the old one's problems; `confirm` and `revise` refuse it and point to `start`. `OptimizerCoreTest.cancelIgnoresInvalidProposal` and `resumeDerivesAgainForInvalidProposal` failed first. Full build: 2999 tests, 0 failures, none at or over 1 s, 52 s. Live check (2026-10-20, installed Claude plugin): the Claude E7 session cancelled campaign `20261020T150959Z-28328a`, whose proposal was stored before WI-114, with the project and the external file unchanged. |
| WI-116 | Done (2026-10-20, commit `9421591926`): the Codex runner omits a denied path that lies inside another denied directory, except the session log directory and login inside the session's home. Before the change every Codex session of a campaign failed to start: the development part denied both the host project and the held-out run root inside it, and Codex's Linux sandbox (codex-cli 0.160.0+41) failed with `bwrap: Can't mkdir …/optimizer-runs/codex-held_out: Read-only file system`, so the Codex E1 and E7 `start` turns of 2026-10-20 (`~/cat/worktrees/e1-codex7`, `e7-codex8`) delivered no proposal. A `codex sandbox` probe reproduced the failure with any denied directory inside another and showed that the outer denial already hides it. Codex session environment version 9. `CodexRunnerTest.skipsDeniedReadsInsideDeniedDirectory` and the updated `deniesProjectBesideEvidence` failed first. Full build passed (3004 tests, 0 failures; three tests over 1 s under build load, each under 1 s alone). |
| WI-117 | Done (user request, 2026-10-20, commit `112b263783`): bundled rule `rules/include/common/range-parameters.md`, routed from the default rules when an operation's parameters specify a range, makes a new or changed API follow the language's range convention, in Java half-open `[fromInclusive, toExclusive)` with zero-based indexes; an external contract's convention stays at its boundary. Scenario `range-parameters/range_follows_language`. The user then asked for language neutrality: the rule states the convention as the code's own language's, with Java (half-open, zero-based) and Lua (from 1, both bounds included) as contrasting examples, and the scenario adds a Lua request. At the user's request each bound's name also states whether it is included (Java `fromInclusive`/`toExclusive`, Lua `from_inclusive`/`to_inclusive`), so callers need not read the documentation, and the scenario's assertions require it. Full build passed; the rule and its route ship in both harness artifacts. Not yet screened on a model. |
| WI-118 | Done (user request, 2026-10-20): bundled rule `rules/include/common/named-conditions.md`, routed from the default rules when a condition combines several tests or tests a value whose meaning the expression does not state, assigns such a condition, or each unclear part, to a local boolean named for its meaning, even when the name is used once; a condition that already reads as its meaning stays inline, and a named condition keeps the guard that protects a later operand. At the user's request, a log, exception or user-facing error message that either branch emits shortly after the condition explains it instead of a named boolean, beginning with a brief statement of what the condition means. Scenario `named-conditions/conditions_named_for_meaning`. Not yet screened on a model. |
| WI-119 | In progress (R-D14, R-W16, R-M7, R-M8). Integrated: `3ba69f69ed` (`start` lists the installed plugins), `25c2ebc53c` (project rules for new guidance), `29dc769366` (prompt size counts only the target prompt file), `dc9f5dee05` (certified environment and plugin versions), `5a14a1ed60` (CAT identified by repository), `84d1a6b9a6` (`status` of a finished campaign shows the delivered environment), `069732f26b` (no model provider), `b6b6e92858` (host tools and outside sources recording, since reverted), `c6387aa712` (prompt-size test speed), `59b07b2002` (the host machine limitation that replaces the recording, R-M8, D95) and `e9bf3e36ac` (room for Windows paths in the long-output run-part test), and `status` stating the limitation once. User ruling (2026-10-22): the limitation keeps "Trials ran with", and `status` states it once. Open: the live exit check of the skill writing `prompt.json` through the installed launcher. User rulings (2026-10-21, `design.md` §2.7, §4.3, §4.4, §5, §8, D30, D92 and D93): CAT identified by its manifest's repository, project rules for new guidance when CAT is a trial plugin, `start` lists the installed plugins for the skill to write `prompt.json`, and every certification report shows the certified environment. User rulings (2026-10-22, `design.md` §4.4, §5, D92 and D93): only `https://github.com/holdmyspot-com/cat` is CAT's repository and the former `https://github.com/cowwoc/cat` is not; CAT's own version has two parts, such as `1.0`; `status` of a finished campaign that cleanup has not removed shows the certified environment from the delivered attempt's record; the status's prompt size counts only the target prompt file. User ruling (2026-10-22, `design.md` §5, D94), after `dc9f5dee05` recorded the trial model's provider: no model provider in the environment or the measurement identity, because Codex's `modelProvider` is only the name in `config.toml` and says nothing about who serves the model behind a proxy such as codex-lb, and Claude Code reports none; the `Model` line reads `Model: <model>, reasoning <level>`, and stored campaigns and histories that carry `model_provider` load without it. User ruling (2026-10-22, `design.md` §5, D95), superseding the same day's ruling to record the host tools and outside sources that trials used: CAT neither records nor controls the host machine's programs and outside sources (MCP servers, websites, web search); the `HOST_TOOLS` and `EXTERNAL_SOURCES` conditions are retired and dropped when a stored campaign or history is read, and a pending measurement change that names only them is dropped; the certified environment block and `status` state the limitation and recommend running the campaign, and the sessions that later use the prompt, in the same container image to limit changes to them. The questions on the recording's choices are moot. |
| WI-120 | In progress (R-I6). Integrated: `14665ac129` (Claude's `codex-runner` skill; `codex-artifact` and `claude-artifact` launchers that select the installed plugin of the same version, a bundle in `~/.cat/bundles`, or a verified download; `codex-runner` in the Codex plugin's `bin/`); full build passes; the built launchers downloaded, verified and reused both archives from a local release directory. Integrated: `c2507ee621` (each uninstaller removes `~/.cat/bundles`) and `61a51d4d06` (download time limits of 30 s to connect, 60 s for the response and 15 minutes for the file; other versions' bundles deleted after a download); full build passes; the built launcher deleted the other version's bundle and kept this version's. The Windows uninstaller test passed on the release workflow's Windows runner (run 39898874571, 2026-10-23). Integrated: `9b50fe0d56` (each check for a bundle deletes the bundles no run has used for more than three calendar months; a download or a run that uses a bundle restarts its age); full build passes. Open: the live run of each skill. User rulings (2026-10-22, `design.md` §8, D96): every harness's plugin has a runner skill for the other harness; a skill without the other harness's CAT artifact downloads that harness's release archive when the skill runs. |
| WI-121 | Done (2026-10-23, commits `0f83f6bd60`, `dde96e2321`, `41cb6dca72`, `b218e62f79`): both runners accept `--session-transcripts`, which requires `--keep-run`; the bridge writes a `session_transcript` record, the runner copies every native transcript into `artifacts/session-transcripts/` before pruning and the receipt lists them; Codex starts ephemeral threads without the option; optimizer trials pass it and Codex records read the new directory. Full build passes. A live trial must still confirm where Claude writes subagent transcripts. |
| WI-122 | Done (2026-10-23, commits `fecd992d13`, `7090217aa9`, `32307a817b`): `SessionCommandLimits` in the common runner package defines the run home, the credential variables of both harnesses and both harnesses' login and configuration files; Claude applies them through `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB=1` and denied reads, Codex through its shell environment policy and denied reads; one contract test runs against each runner; both runner skills tell callers that a session cannot start a harness and to pass `--plugin` to a new run. The scrub makes Claude Code ignore `acceptEdits`, so the isolated settings grant `Edit(./**)` (live probe, Claude Code 2.1.308: writes inside the project succeed, writes outside are denied); session environment versions Claude 11, Codex 10. A live trial in each harness must still confirm that a command cannot reach the provider. Known gap: on Windows Claude Code runs commands without its sandbox, so there the denied reads bind only Claude's file tools and a command can read whatever the user can; the environment scrub still holds. |
| WI-123 | In progress (R-I9). Done: the options, the requests and responses directories, environment and instructions in both runners, a private run root for each requested session created before the session starts, the 10-minute capacity bound (exit 75), the `request-session` launcher in both plugins, the broker (validation without links, input snapshots, count limit, five outcomes, transcript copies, stopping children) and the receipt list (`ee7e98e4a0` to `4f2d198a30`; build 93 s, no session environment change while requests are off). The launcher packs the request and its inputs into one tar archive inside the session's sandbox, and the runner opens only that archive through a native handle that follows no link and holds exactly one hard link, then unpacks it within entry, size and name bounds (`172e1da796`, D101; this replaces the `SecureDirectoryStream` snapshot that refused every request on Windows). The scopes now share one `JvmScope` whose `temporaryDirectory()` is the only source of the temporary root. The hosted Build of `131fb55a59` (run 40044546621) passed the archive checks on all five platforms after fixes for Linux arm64's no-follow flag (`ef8e1eec64`) and the macOS and Windows test fixtures. Claude live check passed (2026-10-24, Claude Code 2.1.308, `--session-requests 1`): the session requested a Claude session that read a file it wrote and answered from it; the receipt lists the request `completed` and the requester read the response. It first found three defects, now fixed: the run manifest parser overflowed the stack on the long harness command (`b42cf09664`), the session could not run the launcher named through a variable (`8c326949e3`: literal path in the instructions and a Bash grant for exactly that launcher) and could not read `responses` (`2c0708f86a`: a read grant for that directory only). The installed Codex plugin-creation check passed on 2026-10-24: a fresh isolated session created a plugin and requested a Codex child with an empty project and a prompt naming its skill; the child loaded the installed skill and returned its body-only phrase. The retained response reports completed, the child transcript records its successful skill-file read and exact response, and its receipt is copied to the requester. Evidence: orchestrator session 01a16de4-2163-75d0-9e45-481a1850fb3e, temp/wi123_plugin_check-u7pkCw/codex-valid-plugin/acceptance.json and the response/transcript/receipt paths it records. The first attempt used an invalid underscore-containing plugin name supplied by the parent prompt and was correctly refused; both attempts remain retained. Remaining: the Claude plugin-creation check. Its earlier receipt proves file passing and a completed requested session, but its prompt only creates notes.txt, so it does not prove the specified plugin case. |
| WI-124 | Done (R-I10): the plugin build and prompt-only repackaging remove design goals and non-directive comments from every Markdown file (`525546b5ea`; a scan of both built artifacts, Claude 186 and Codex 160 Markdown files with 89 lazily loaded rules each, found neither); role briefs and injected project rules lose them too, judge versions 10 (`c5f2e6c66b`); the plugin's rules on writing prompt files say the guidance must carry every goal (`97dcaa7161`); an audit of every rule, skill, agent and role brief against its design goals closed the gaps in 14 files' guidance, with goals and comments unchanged and no goal left unresolved (`72a6950802`; build 49 s). |
| Process termination lookup seam (2026-10-24) | Done: reviewed `terminator-unit` commits `cdf973a9dd` and `1c9166d4ba`, including all seven code-break receipts and their intended assertion failures; rebased onto `c69a2a7d19` as `1f24a4f2a1` and `bea97701c6` with equal range-diff patches, then fast-forwarded and pushed `v1.0`. Release build passed: 3180 tests, 0 failures/errors, 11 skipped, 115 s. Hosted Build for `c69a2a7d19` passed (run `40093472447`, all five platforms); hosted Build for `bea97701c6` passed on all five platforms (run `40094661311`). Review and build evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/integration-y5REYT/`. |
| Test-suite process boundaries (user ruling, 2026-10-24) | Done: `1ec6ad7341` shares the controlled process-handle fake, checks runner termination through parent/child/grandchild liveness and removes the unused real-process fixture and JDK-only executable check; `c965a458f7` checks build worktree/program resolution, failed exit status, the exact last 40 lines and recovery from a briefly busy starter without scripts, directories or retry/pause counts; `0b31d45ab0` checks child environment values and program lookup without shells or the host environment. Seven deliberate production defects failed the intended converted tests; originals restored. Full release build and G9 nonexistent `HOME`/`user.home` check passed on this tree: 3179 tests, 0 failures/errors, 11 skipped, 107 s; the home remained absent. At least 1 s: `SessionRequestCliTest.refusesUnsafeInputs[5]` 8.101 s, `[6]` 4.364 s; `SessionRequestBrokerTest.refusesHostileInputs[10]` 2.459 s, `[18]` 2.571 s, `[23]` 2.556 s. Receipts: current orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/integration-y5REYT/conversion-mutations.json` and `conversions-g9-build-result.json`. Additional conversion `2147ae89bb`: `HarnessProcessStarterTest` uses a fake process and verifies child PATH resolution, literal arguments, directory and environment isolation; all five deliberate defects caught. Full build: 3,207 tests, zero failures/errors, 11 skipped, 96 s; absent-home check passed. Evidence: `starter-mutations.json` and `starter-full-build-result.json` in the same receipt directory. Unit tests are the default; retain the smallest real boundary check when a fake would hide a required defect. Script, launcher, installer and Git boundary checks remain. |
| WI-15, Codex E5 takeover (2026-10-24) | In progress. Host `continue14` of thread `01a15ed8-fc71-72e1-9eb1-4fa77c43386e` resumed shell access, then campaign `20261021T142441Z-30f739` paused at candidate 37 on changed `judge` and `session_environment_version`. The user wants the existing identity/results retained despite the cache change; the existing restore/new-identity/stop choices cannot express that. No choice applied; the user approved keep identity for every measurement change except loader changes (WI-125). Resume authorization and evidence are in `/home/gili/cat/worktrees/e5-codex3/receipts/authorization.txt` and `continue14-events.jsonl`. |
| Scoped executable runner (user ruling, 2026-10-24) | Implemented as `b69aceb836`: `JvmScope.executableFiles()` supplies production retries with pauses and test runners that do not sleep; `OptimizerCore`, `RequestedRunner` and both harness runners obtain it from their scope. Existing focused optimizer/runner tests pass; two controlled checks prove service sharing/recovery and refusal after closure. Full build: 3,209 tests, zero failures/errors, 11 skipped, 96 s; absent-home check passed. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/integration-y5REYT/jvm-scope-full-build-result.json`. The user separately chose to retain refusal of symlinks anywhere in request input paths; macOS callers must provide resolved paths. |
| WI-126 | Installer repair committed and pushed as `361da15fee`. TDD reproduced checksum success followed by missing marketplace metadata; the fixed bootstrap, all 30 bootstrap checks, ShellCheck and the full 3,179-test build pass. Hosted Build `40098509359` passed. Release 40099789260 passed for 361da15fee; all ten archives and the ten-entry checksum manifest are published. The published Linux Codex archive passed its checksum and contains the fixed installer plus required metadata. Follow-up `d8887e449a`: an actual downloaded outer bootstrap exposed a separate two-URL shell wrapping defect in all four install/removal wrappers, plus the same adjacent-metadata bug in Claude. Five new regressions failed first; all 35 bootstrap checks, ShellCheck and the 3,209-test full build pass after the fixes. The public downloaded Codex outer bootstrap subsequently passed archive installation with controlled harness registration in an isolated home (orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/published-bootstrap-fixed-full-rme2yr92/receipt.json`); it changed no real harness or user configuration. Hosted Build `40105368986` for `e525b3ea4b` failed only because its new shared bootstrap test required successful Codex shell installation on Windows, where the installer correctly requires PowerShell. Corrected as `dfe2d5e331`: Windows must refuse with PowerShell directions and leave the marketplace absent; supported systems still install archive metadata. The previous test failed locally with controlled Git Bash platform input; the corrected check passes Linux, Git Bash and MSYS, and all 35 bootstrap checks pass with the parent output-routing context removed. The full 3,216-test build passes. Hosted Build `40109242080` for `c64f57c9b3` passed on all five platforms. The user force-pushed the replacement tag to `c64f57c9b3`; Release `40114796736` passed. All ten published archives match their ten-entry SHA-256 manifest, and their installers, marketplace metadata and standalone wrappers match that commit. Every Claude archive contains `bin/claude-runner`; every Codex archive contains its Claude-runner skill. Both public downloaded shell bootstraps installed metadata and registered the plugin, then removed and unregistered it, using isolated homes and stateful CLI registration fakes with real public HTTP downloads/checksums/scripts. This proves packaging and bootstrap behavior, not native CLI registration. Receipts: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/published-c64-release-6zx3qhzo/verification.json` and `temp/public-c64-install-remove-2qbw3bia/verification.json`. |
| WI-127 | Build-validation repair, committed on `jvm-options-c64` as `66e9059a45` and `311835d141`; replacement release at `c64f57c9b3` verified; integration build pending. Actual fork inspection found that the common module's native-access argument replaced caller `argLine` values, so the earlier check that a nonexistent home stayed absent did not establish that common tests used it. A retained behavioral checker failed against all 219 common reports before the configuration fix. The common test fork now combines caller options with the required native-access flag; an empty default keeps ordinary builds working. A test fixture then exposed an ancestor `.cat` directory and correctly reported itself inside a project; its isolated filesystem now supplies the outside-project condition it asserts. Focused checks pass both with and without caller options. Clean full build: 3,216 tests, zero failures/errors, 11 skipped, 114.265 s. All 297 reports record the requested outside-Git temporary directory and nonexistent `user.home`; `HOME` names the same absent directory, which was never created. Tests at or above one second: optimizer.test.TestabilityAuditTest.coreReferencesNoHarness 1.046 s; optimizer.execution.test.DriverTest.failedAttemptReproducesBeforeDevelopment 1.185 s; optimizer.execution.test.DriverTest.statusOmitsAbsentSessionUsage 1.01 s; optimizer.execution.test.DriverTest.weakRoundSelectsBestThenRunsSequentially 1.04 s; runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[10] 1.997 s; runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[18] 2.623 s; runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[23] 2.562 s; runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[5] 6.799 s; runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[6] 2.254 s. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/forked-jvm-options-m29d94cv/` (red reports/checker, default and focused checks, `isolated-fixture-full-build-result.json` and `isolated-fixture-runtime-property-check.json`). This proves the current G9 execution on that branch; the remaining §8 audit and other final gates remain unmet. |
| Final gates (§9), interim audit on `c64f57c9b3` | Gate 3: source inventory (374 test sources, 23 POMs) and a process trace found no real Codex/Claude/model launch and no native group/profile; retained processes exercise packaged boundaries or controlled shell, Git and Java fixtures. The traced run is process-audit evidence only: ptrace caused two 30-second request-input tests to time out, while the subsequent untraced focused run passed all nine checks. No timeout or security restriction was relaxed. Audit source hashes and complete execution evidence are retained in orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/java-harness-audit-obirnizd/`. Gate 2: class-level inventory has 136 requirements, 134 tagged, with R-I1 and R-I6 untagged and no unknown tags. Passing class reports do not establish exact tagged-method/data-provider execution; live E receipts are not reconciled. Gate 2 remains unmet. Current G9 execution is recorded under WI-127. Gates 4, 5, 7 and 8 remain unmet. |
| Final gates (§9), method execution audit on `4d96c5b607` | The platform Java parser maps all 2,095 requirement comments in 374 test-source files to methods and their observed Surefire cases; every mapped method's observed cases passed across 297 reports. The audit handles inherited contract methods, tags before annotations, multiple adjacent tags, string/text-block contents and UTF-16 source offsets; controlled fixtures also reject a tag before an unrelated field and malformed Java. `4d96c5b607` adds the missing R-I6 tags to 21 existing bundle checks; all 22 checks in that class passed again (19.741 s including Maven). R-I1 is the sole untagged requirement. These are execution and attribution checks: they do not prove that each assertion satisfies its requirement or that a case absent from every report was exercised. The 33 requirements naming E proof still need their live receipts reconciled, including R-I6's runner-skill checks. Gate 2 remains unmet. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/tagged-method-audit-xkl06G/method-traceability.json`, `release-bundle-tags-check.json`, parser/probe sources and reconciliation script. The receipt records source/report hashes; only the bundle test's tag comments changed after the preceding full build, and its fresh class report replaces that earlier report. |
| Final gates (§9), interim build and runtime audit on `f7d4420b18` | Gate 1 passed on `jvm-options-c64`: clean full build, 3,216 tests, zero failures/errors, 11 skipped, 119.004 s. G9 passed: all 297 reports record the requested outside-Git temporary directory and nonexistent home, which stayed absent. `10815ffd04` shortens the missing-usage fixture to one recorded trial before its manual clock expires the slice; all live and reloaded summary assertions remain. Its focused test time fell from 1.787 s to 0.433 s, and it took 0.015 s in the full suite. `f7d4420b18` documents the required source scan, reproduction transitions and archive-limit fixtures for four other observed slow methods. Tests at or above one second in this full build: claude.optimizer.test.ClaudeSessionsContractTest.runsOneSessionPerRole 1.37 s; common.optimizer.test.TestabilityAuditTest.coreReferencesNoHarness 1.048 s; common.optimizer.execution.test.DriverTest.failedAttemptReproducesBeforeDevelopment 1.002 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[10] 1.776 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[18] 1.388 s; common.optimizer.cli.test.OptimizerCoreTest.pilotLeavesOutIgnoredFiles 1.337 s; common.optimizer.cli.test.OptimizerCoreTest.presentingRecordsDerivedSources 1.28 s; common.optimizer.cli.test.OptimizerCoreTest.projectRulesLoadFromProject 1.241 s; common.optimizer.cli.test.OptimizerCoreTest.proposalListsEarlierCertifications 1.217 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[5] 6.341 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[6] 2.012 s. Five newly observed cases still need runtime review/Javadoc reasons: `ClaudeSessionsContractTest.runsOneSessionPerRole` and `OptimizerCoreTest.pilotLeavesOutIgnoredFiles`, `presentingRecordsDerivedSources`, `projectRulesLoadFromProject`, `proposalListsEarlierCertifications`; §8 and gate 6 therefore remain open. The refreshed method audit maps all 2,095 tags to passing observed cases across the fresh 297 reports; R-I1 remains untagged, live E receipts remain unreconciled, and gate 2 is unmet. Gate 3 passes for this source tree by the retained c64 process audit and review of every changed path: production Java is unchanged, and test/configuration changes add no real runner, harness or model launch and no native group/profile. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/forked-jvm-options-m29d94cv/slow-audit-final-full-build-result.json`, `slow-audit-final-runtime-property-check.json`, `temp/tagged-method-audit-xkl06G/` (baseline/shortened checks and refreshed source/report hashes), and `temp/java-harness-audit-obirnizd/branch-source-comparison.json`. Integration remains pending the user's c64 replacement release. |
| Final gates (§9), remaining runtime review on `d69b9f7dff` | Reviewed the five newly slow cases against their public requirements and existing focused collaborators. All five focused checks passed with scripted sessions or recorded replies; no real harness/model ran. The proposal checks require the public start/confirm/revise transitions, and the role adapter check needs one reply per role. Their Javadocs now explain those minimum inputs. `presentingRecordsDerivedSources` replaces fake selection-call history with equality of the public recorded inventory before and after confirmation, preserving paths, content hashes, modes and links; its focused check passed (one test, zero failures/errors, 1.161 s). Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/remaining-runtime-audit-e9n40w/` (focused receipts, retained original full reports and outcome-check log). This closes these five runtime explanations, not the broader §8 audit or gate 6. A fresh full build and method audit remain required after integration. |
| Final gates (§9), integrated build on `f6a60c6051` | Fast-forwarded `jvm-options-c64` into `v1.0` after verifying the replacement release. Clean full build passed: 3,216 tests, zero failures/errors, 11 skipped, 105.874 s. All 297 Surefire reports retain the requested outside-Git temporary directory and nonexistent home; the fresh home stayed absent. The method audit maps all 2,095 tags to passing observed cases; R-I1 and live E receipts still leave gate 2 unmet. Tests at or above one second: common.optimizer.execution.test.DriverTest.failedAttemptReproducesBeforeDevelopment 1.242 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[10] 1.733 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[18] 2.714 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[23] 2.287 s; common.optimizer.delivery.test.DeliveryTest.deliversPromptTestsFromTheDraft 1.166 s; common.optimizer.delivery.test.DeliveryTest.deliversLinksOfDraftTests 1.254 s; common.optimizer.delivery.test.DeliveryTest.deliversChangedSharedFilesAsCopies 1.312 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[5] 6.642 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[6] 2.548 s. The three newly slow DeliveryTest methods still require focused runtime review; the broader §8 audit and final gates remain open. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/forked-jvm-options-m29d94cv/integrated-release-full-build-result.json`, `integrated-release-runtime-property-check.json` and `temp/tagged-method-audit-xkl06G/integrated-method-traceability.json`. Hosted Build `40115674386` passed for `4b84f6ec87`. |
| Delivery file checks without Git (`a67604284d`) | The shared in-memory forks replace real Git for file/link delivery outcomes; four existing checks explicitly retain actual Git diffs or unchanged history/index. All 28 focused checks passed with unchanged outcome assertions. The three newly slow cases fell from 1.166/1.254/1.312 s to 0.159/0.171/0.202 s. Clean full build: 3,216 tests, zero failures/errors, 11 skipped, 194.465 s. All 297 reports use the requested temporary directory and absent home, which stayed absent. The timing breakdown reports per-module testcase durations and the largest class aggregates, with overlap and unavailable compilation/packaging wall-time attribution stated explicitly. The refreshed method audit maps all 2,095 tags to passing observed cases; R-I1 and E receipts still leave gate 2 unmet. Gate 3 is preserved by the source/process baseline and review of all 13 changed paths. Tests at or above one second: codex.optimizer.test.CodexSessionsContractTest.completesWithEvidenceOfEveryTurn 1.099 s; common.optimizer.test.TestabilityAuditTest.onlyForkAdapterConstructsGit 1.832 s; common.optimizer.test.TestabilityAuditTest.onlyMainReadsEnvironment 1.911 s; common.optimizer.test.TestabilityAuditTest.coreReferencesNoHarness 2.401 s; common.optimizer.execution.test.DriverTest.authorityWarningTrace 1.258 s; common.optimizer.execution.test.DriverTest.failedAttemptReproducesBeforeDevelopment 1.763 s; common.optimizer.execution.test.DriverTest.weakRoundSelectsBestThenRunsSequentially 1.716 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[10] 4.946 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[18] 3.023 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[23] 2.663 s; common.optimizer.campaign.test.TransitionsTest.certificationIndexIsPermanent 1.423 s; common.optimizer.store.test.FileCampaignStoreTest.trialSessionsSurviveRetries 1.189 s; common.optimizer.cli.test.OptimizerCoreTest.cancelIgnoresInvalidProposal 1.19 s; common.optimizer.cli.test.OptimizerCoreTest.buildsInOptimizationWorktree 1.437 s; common.optimizer.cli.test.OptimizerCoreTest.chooseAndCancelReachTheDriver 1.3 s; common.optimizer.cli.test.OptimizerCoreTest.choosesConflictsBeforeRevision 1.601 s; common.optimizer.cli.test.OptimizerCoreTest.confirmAcceptsLinkToProjectFileAtItsPath 1.617 s; common.optimizer.cli.test.OptimizerCoreTest.confirmDefaultsToWeakestCell 2.088 s; common.optimizer.cli.test.OptimizerCoreTest.confirmFreezesContract 2.168 s; common.optimizer.cli.test.OptimizerCoreTest.confirmListsEveryBlocker(boolean)[0] 1.797 s; common.optimizer.cli.test.OptimizerCoreTest.confirmRefusesLinkToPromptFile 1.804 s; common.optimizer.cli.test.OptimizerCoreTest.confirmRefusesProposalWithCategories 1.243 s; common.optimizer.cli.test.OptimizerCoreTest.confirmRefusesProposalWithOldJudgePrompt 1.466 s; common.optimizer.cli.test.OptimizerCoreTest.confirmListsEveryBlocker(boolean)[1] 1.54 s; common.optimizer.cli.test.OptimizerCoreTest.confirmRefusesProposalWithoutBehaviors 1.09 s; common.optimizer.cli.test.OptimizerCoreTest.confirmRefusesUncitedProposalFormat 1.228 s; common.optimizer.cli.test.OptimizerCoreTest.confirmRefusesUnrankedProposalFormat 1.0 s; common.optimizer.cli.test.OptimizerCoreTest.controlTrialsEmptyOnlyTarget 1.095 s; common.optimizer.cli.test.OptimizerCoreTest.conflictRefusalAndRevision 1.289 s; common.optimizer.cli.test.OptimizerCoreTest.contractAuthorListsFilesThatLoadInTrials 1.211 s; common.optimizer.cli.test.OptimizerCoreTest.confirmedRevisionRecordsNewDerivedSource 2.397 s; common.optimizer.cli.test.OptimizerCoreTest.failingRemovalWarnsOtherCommands 1.699 s; common.optimizer.cli.test.OptimizerCoreTest.pilotLeavesOutIgnoredFiles 1.017 s; common.optimizer.cli.test.OptimizerCoreTest.proposedRequirementChangeRevisesContract 1.244 s; common.optimizer.cli.test.OptimizerCoreTest.refusesUnlistedResolution 1.0 s; common.optimizer.cli.test.OptimizerCoreTest.removalIsReported 1.195 s; common.optimizer.cli.test.OptimizerCoreTest.resumeDerivesAgainForInvalidProposal 1.231 s; common.optimizer.cli.test.OptimizerCoreTest.retainIncumbentDeliversPromptTests 1.087 s; common.optimizer.cli.test.OptimizerCoreTest.runExecutesOneSlice 1.037 s; common.optimizer.cli.test.OptimizerCoreTest.runRecordsSoundTest 1.121 s; common.optimizer.cli.test.OptimizerCoreTest.revisionLeavesOutDroppedDerivedSource 1.483 s; common.optimizer.cli.test.OptimizerCoreTest.runRevisesTestWithDefect 1.084 s; common.optimizer.cli.test.OptimizerCoreTest.startRecordsLoaderFiles 1.129 s; common.optimizer.cli.test.OptimizerCoreTest.statusCombinesSliceUsage 1.429 s; common.release.test.ReleaseBundlesTest.httpErrorLeavesNoCopy 1.012 s; common.release.test.ReleaseBundlesTest.downloadsOverHttp 1.087 s; common.release.test.ReleaseBundlesTest.unfinishedBodyExpiresDownloadLimit 1.002 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[5] 13.177 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[6] 5.432 s. These additional slow cases remain in the broader runtime review; gate 6 remains open. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/remaining-runtime-audit-e9n40w/delivery-check/`, `temp/forked-jvm-options-m29d94cv/delivery-unit-final-*`, `temp/tagged-method-audit-xkl06G/delivery-method-traceability.json` and `temp/java-harness-audit-obirnizd/delivery-source-comparison.json`. |
| Empty Codex instruction sources (`fe211dc63b`) | TDD red/green and the existing precedence check pass. Clean full build: 3,217 tests, zero failures/errors, 11 skipped, 120.664 s. All 297 reports retain the required temporary directory and nonexistent home, which stayed absent. Maven reactor timing breakdown is retained (parallel durations overlap): common CLI 91 s, distribution 19.853 s, Codex CLI 9.248 s, Claude CLI 8.334 s; all module timings are in the receipt. The method audit maps all 2,095 tags to passing observed cases; R-I1 and E receipts remain unmet. The source comparison reviews all 15 changed paths, including the production file-size predicate, with no new runner/harness/model launch or native group/profile. Tests at or above one second: common.optimizer.execution.test.DriverTest.failedAttemptReproducesBeforeDevelopment 1.383 s; common.optimizer.execution.test.DriverTest.weakRoundSelectsBestThenRunsSequentially 1.055 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[10] 2.119 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[18] 1.43 s; common.git.replay.test.EditorInventoryTest.excludesAddedPathsFromOrigins 1.112 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[5] 6.691 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[6] 1.615 s. Broader runtime review remains open, including newly observed `EditorInventoryTest.excludesAddedPathsFromOrigins`. Hosted Build `40117172146` passed for this follow-up at `e7b78f1f71` (documentation after `fe211dc63b`). Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/remaining-runtime-audit-e9n40w/empty-instruction-check/`, `temp/forked-jvm-options-m29d94cv/empty-instructions-final-*`, `temp/tagged-method-audit-xkl06G/empty-instructions-method-traceability.json` and `temp/java-harness-audit-obirnizd/empty-instructions-source-comparison.json`. |
| Inherited Codex instruction discovery (`bf9de0e95e`) | Three expected TDD red cases, five focused green cases, standalone recreation and integrated public-operation checks pass. Clean full build: 3,220 tests, zero failures/errors, 11 skipped, 128.107 s; all 297 reports retain the required temporary directory and nonexistent home, which stayed absent. Parallel Maven module timings overlap: common CLI 95 s, distribution 21.903 s, Codex CLI 11.255 s, Claude CLI 10.057 s; every module duration is retained in the timing receipt. All 2,095 requirement comments map to passing observed method cases; R-I1 and end-to-end receipts remain unmet. The source comparison reviews all 15 changed paths against the no-model audit baseline; this follow-up adds bounded configuration parsing and filename/limit selection only, with no harness/model launch or native group/profile. Tests at or above one second: common.optimizer.execution.test.DriverTest.authorityWarningTrace 1.04 s; common.optimizer.execution.test.DriverTest.failedAttemptReproducesBeforeDevelopment 1.733 s; common.optimizer.execution.test.DriverTest.weakRoundSelectsBestThenRunsSequentially 1.425 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[10] 1.762 s; common.optimizer.cli.test.OptimizerCoreTest.choosesConflictsBeforeRevision 1.017 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[5] 6.715 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[6] 2.243 s. Broader runtime and fake-contract review remain open. The unchanged warning set includes seven existing PMD NonThreadSafeSingleton processing errors; this build does not establish that rule checked the affected methods. Hosted Build `40118625091` passed for this integration at `5235bae6ed`. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/instruction-loading-selection-8eIgXe/`, `temp/instruction-discovery-recreation-qltVMU/`, `temp/forked-jvm-options-m29d94cv/instruction-fallbacks-final-*`, `temp/tagged-method-audit-xkl06G/instruction-fallbacks-method-traceability.json` and `temp/java-harness-audit-obirnizd/instruction-fallbacks-source-comparison.json`. |
| Conflict-choice outcome checks (`e1eca138d7`) | The successful choice flow asserts its persisted remaining conflict instead of contract-author call count. The refusal-only check uses the existing confirmed-state Builder without replaying creation/confirmation; both refusal messages and the unchanged stored bytes remain asserted. Public revision/confirmation still use one coherent proposal/fork setup: the state builder alone does not create those artifacts. Three focused cases passed; a temporary stage profile measured fixture 0.135 s, creation 0.896 s, initial confirmation 0.089 s, pause preparation 0.012 s, first choice 0.070 s, final choice 0.124 s, final confirmation 0.078 s and cleanup 0.035 s. The profile is one instrumented run, not a speed benchmark; its initial Checkstyle failure executed no test, and both attempts restored their expected source postimage. The seeded refusal diagnostic passed at 0.332 s. Final clean full build: 3,220 tests, zero failures/errors, 11 skipped, 126.795 s; all 297 reports retain the requested temporary directory/nonexistent home, which stayed absent. In that full build: successful choices 0.579 s, refusal 0.041 s, paused revision 0.31 s. Parallel module timings overlap: common CLI 89 s, distribution 22.699 s, Codex CLI 13.779 s, Claude CLI 12.825 s; all modules are retained in the timing receipt. Final cases at or above one second: common.optimizer.test.TestabilityAuditTest.coreReferencesNoHarness 1.396 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[10] 2.355 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[18] 4.234 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[23] 1.388 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[5] 7.721 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[6] 3.336 s. The preceding unseeded full check also passed 3,220 tests in 116.878 s; its cases at or above one second were common.optimizer.execution.test.DriverTest.failedAttemptReproducesBeforeDevelopment 1.169 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[10] 1.588 s; common.runner.test.SessionRequestBrokerTest.refusesHostileInputs(java.lang.String)[18] 1.508 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[5] 6.113 s; common.runner.test.SessionRequestCliTest.refusesUnsafeInputs(java.lang.String)[6] 1.788 s. All 2,095 requirement comments map to passing observed cases; R-I1 and end-to-end receipts remain unmet. The source/process comparison confirms this follow-up changes one test source and planning only, adding no harness/model launch or native group/profile. Broader runtime/fake-contract review remains open. Hosted Build pending for the conflict-test follow-up. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/conflict-outcomes-kr6XqZ/`, `temp/forked-jvm-options-m29d94cv/conflict-outcomes-final-*`, `temp/forked-jvm-options-m29d94cv/conflict-refusal-unit-final-*`, `temp/tagged-method-audit-xkl06G/conflict-outcomes-method-traceability.json` and `temp/java-harness-audit-obirnizd/conflict-outcomes-source-comparison.json`. |
| Certification-index fixture (`fcd3e5e3d4`) | The index lifecycle test seeds development and baseline counts instead of replaying successful trials to reach the commit gate. Both commits and all revision, identity-change, cancellation, resume and new-campaign index assertions remain. Existing CommitGate tests separately check the thresholds. A retained single-run profile measured initial preparation 0.553 s, intervening transitions 0.049 s and second preparation 0.281 s; this is not a steady-state benchmark. The first temporary instrumentation was rejected for an unused final checkpoint assignment before tests; it was restored, corrected and rerun without suppressions. All 83 focused transition/gate cases pass; the index case took 0.062 s focused and 0.017 s in the full build. Clean full build: 3,220 tests, zero failures/errors, 11 skipped, 127.096 s; all 297 reports retain the requested temporary directory and nonexistent home, which stayed absent. Parallel module times overlap: common CLI 91 s, distribution 21.598 s, Codex CLI 14.386 s, Claude CLI 13.520 s; every module duration is retained. Seven cases reached one second: Driver.failedAttemptReproducesBeforeDevelopment 1.217 s, Driver.weakRoundSelectsBestThenRunsSequentially 1.010 s, Broker.refusesHostileInputs[10] 1.693 s/[18] 3.294 s/[23] 1.112 s, and RequestCli.refusesUnsafeInputs[5] 6.675 s/[6] 1.928 s. All 2,095 requirement comments map to passing cases; R-I1 and native receipts remain unmet. The source review retains the prior process audit and checks this test-only delta. Hosted Build for preceding planning commit `bad9ebbf98` passed (run `40120536256`); this new delta awaits its hosted Build. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/certification-index-OQjABP/`, `temp/forked-jvm-options-m29d94cv/certification-index-unit-final-*`, `temp/tagged-method-audit-xkl06G/certification-index-*` and `temp/java-harness-audit-obirnizd/certification-index-source-comparison.json`. |
| G3 statistical and report acceptance (`f2bf15d57d`, `f722e26554`) | Added 60 independently calculated exact-rational certification boundary pairs across minimum pass rates 0.80/0.90/0.97/0.99, indices 1–5 and 0–2 failures; both sides are asserted. A deliberate fixed-0.95 production defect failed the new check at its intended assertion; restoring the exact source passed. The initial attempt stopped before tests on the method-name length rule; the name was shortened without suppressions. Separately, a focused TDD check failed because the public claim omitted the held-out part; the claim now names it. The first green attempt caught a 121-character line before tests; wrapping it preserved the assertion. All 78 focused report/arithmetic cases passed. Clean full build: 3,221 tests, zero failures/errors, 11 skipped, 127.054 s; all 297 reports retain the requested temporary directory and nonexistent home, which stayed absent. Parallel module times overlap: common CLI 93 s, distribution 20.870 s, Codex CLI 13.484 s, Claude CLI 12.791 s; every module duration is retained. Ten cases reached one second: Driver.failedAttemptReproducesBeforeDevelopment 1.342 s, Driver.weakRoundSelectsBestThenRunsSequentially 1.100 s, Broker.refusesHostileInputs[10] 1.764 s/[18] 2.357 s, Core.refusesOtherSessionBeforeAnyChange 1.231 s, Core.refusedRevisionOffersRevise 1.434 s, Core.proposedRequirementChangeRevisesContract 1.657 s, Core.refusesRevisionThatChangesRequirements 1.048 s, RequestCli.refusesUnsafeInputs[5] 6.604 s/[6] 1.713 s. The new boundary case took 0.001 s and the report claim case 0.004 s. All 2,096 requirement comments map to passing cases; R-I1 and native receipts remain unmet. G3 maps ten inspected subclaims to 42 passing method references. Hosted Build `40122075505` passed for preceding planning commit `fd0f952a75`; the new changes await their hosted Build. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/statistical-boundaries-IF5b8z/`, `temp/held-out-claim-Fmojx6/` (including `g3-acceptance.json`), `temp/forked-jvm-options-m29d94cv/statistical-acceptance-unit-final-*`, `temp/tagged-method-audit-xkl06G/statistical-acceptance-*` and `temp/java-harness-audit-obirnizd/statistical-acceptance-source-comparison.json`. |
| Command fixture and runtime review (`4f3bb35e5f`) | Cancel/choice now uses a saved actual incumbent overlay and directly confirmed campaign; all returned/stored lifecycle and refusal assertions remain. One instrumented run spent 1.044 s in predecessor start/confirm, versus about 0.079 s in the commands checked; this is not a steady-state benchmark. An initial seed omitted the overlay and failed during driver reconciliation; its receipt is retained, and saving the actual captured inventory fixes the fixture. The focused corrected check passed in 0.476 s; the full-build case took 0.123 s. Four other public workflow checks now explain their required proposal/fork fixtures; a retry-guidance Javadoc claim is narrowed to its assertions. The four-case uninstrumented diagnostic passed, with cases above one second: refusedRevisionOffersRevise 1.485 s, refusesRevisionThatChangesRequirements 1.686 s, proposedRequirementChangeRevisesContract 1.953 s. Clean unrestricted build: 3,221 tests, zero failures/errors, 11 skipped, 113.336 s; all 297 runtime-property checks pass and nonexistent HOME remains absent. All 2,096 requirement comments map to passing observed methods; R-I1 and native receipts remain unmet. Parallel module times overlap: common CLI 83 s, distribution 21.346 s, Codex CLI 10.181 s, Claude CLI 8.905 s; all 23 module times are retained. Six full-build cases reached one second: Driver.failedAttemptReproducesBeforeDevelopment 1.086 s, Driver.weakRoundSelectsBestThenRunsSequentially 1.090 s, Broker.refusesHostileInputs[10] 1.572 s/[18] 1.019 s, RequestCli.refusesUnsafeInputs[5] 6.165 s/[6] 1.291 s. The retained process audit is rechecked against this single-test-source delta; no production behavior, runner/model launch or native group/profile changes. Hosted Build for preceding `82cd7e55d4` passed (run `40123112713`); this follow-up awaits its hosted Build. Broader runtime/shared-fake review and terminal gates remain open. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/command-runtime-95UfHP/`, `temp/forked-jvm-options-m29d94cv/command-runtime-unit-final-*`, `temp/tagged-method-audit-xkl06G/command-runtime-*` and `temp/java-harness-audit-obirnizd/command-runtime-source-comparison.json`. |
| Cross-JVM capacity and scheduling evidence (`5a719f4e31`) | Added the smallest process boundary check for a hold reaching an already-open pool in another JVM; no harness/model process or sleep. Earlier two-child check passed in 7.572 s; final one-child focused check passed in 4.919 s, with all 15 source-audit checks passing. Unrestricted absent-HOME/user.home clean verify: 3,222 tests, 0 failures/errors, 11 skipped, 413.324 s; all 297 runtime-property checks passed. The new boundary case took 2.286 s; its Javadoc states why a real process is required. Retained `temp/forked-jvm-options-m29d94cv/capacity-process-boundary-final-timing.md` lists all 23 overlapping reactor module durations and all 111 cases at least 1 s (Common CLI 05:14, Distribution 58.181 s, Codex CLI 39.520 s, Claude CLI 36.051 s). Runtime increased across many unchanged tests; no attribution is established from this run. `temp/tagged-method-audit-xkl06G/capacity-process-method-traceability.json`: 375 test sources, 297 reports, all 2,097 tagged methods passed, 136 known IDs, no unknown tags; R-I1 still lacks a passing tagged method. Baseline process audit plus reviewed source delta retained in `temp/java-harness-audit-obirnizd/capacity-process-source-comparison.json`. G8 assertion map now has 13 subclaims/73 method references, including actual cross-JVM persistence. Prior exact-commit hosted Build `40124907493` for `e2ecb6806a` passed; current source hosted result remains pending. |
| Shared fork contract and fake correction (`7b6e196317`) | Moved three existing real-adapter assertion bodies into `ForkViewContract` and ran the identical checks against `InMemoryForks`; all 18 original assertions remain, plus physical restoration of recorded bytes after the base file changes. Red: all three real checks passed, while the fake failed with `NoSuchFileException` for its newly selected worktree file. The fake now captures immutable scanned bytes and materializes selected files/removes deselected ones. Focused green: real 3/3, fake 3/3, source audit 15/15; full contract cases 0.045–0.271 s. Unrestricted absent-HOME/user.home clean verify: 3,225 tests, 0 failures/errors, 11 skipped, 327.575 s; all 298 runtime-property reports match. `temp/fork-view-contract-9u2njnte/` retains the preimages, write set, initial unused-import PMD failure (no behavioral test result), red/green XML, assertion preservation, full-build receipts, and `full-timing.md` with all 23 overlapping module durations and 66 cases at least 1 s (Common CLI 04:04, Distribution 51.661 s, Codex CLI 33.032 s, Claude CLI 28.861 s). `temp/tagged-method-audit-xkl06G/fork-view-contract-method-traceability.json`: 377 sources, 298 reports, all 2,097 tags passed, 136 known IDs, no unknown tags; R-I1 remains missing. `temp/java-harness-audit-obirnizd/fork-view-contract-source-comparison.json` verifies all 24 baseline delta paths; no new process boundary. G8 reconciled at 13 subclaims/73 method references. Prior exact-commit hosted Build `40126873667` for `26ca642b6d` passed after its rate-limit observation error was recovered; exact-commit hosted Build `40128284888` for documentation head `af23f8b03a` also passed. Other fake behaviors and runtime reasons remain to review; G9 stays unrun. |
| Fork lifecycle contract and command-scope correction (`a8e0a9801f`) | Renamed the shared helper to `ForksContract` and added identical physical removal, repeated removal, missing-worktree and opening-refusal checks for the real adapter and fake. The original real Git registry assertions remain; repeated removal adds a prune invocation in that existing boundary owner. Initial fake red: two presence/removal errors; after correcting those, opening a physically missing worktree still failed the contract. The fake now observes physical presence, deletes its worktree and refuses opening an absent one. The first unrestricted run exposed two fresh command scopes with independent empty fake stores; both fixtures now share their existing fake fork store while retaining all token outcome assertions. Focused green: 27 checks. Final unrestricted absent-HOME/user.home clean verify: 3,228 tests, 0 failures/errors, 11 skipped, 383.382 s; all 298 runtime-property checks pass and HOME stays absent. `temp/fork-lifecycle-contract-0zf6wu3_/` retains bounded preimages, red/green and failed/final full-build receipts, scope diagnosis, and both timing inventories; `final-full-timing.md` records all 23 overlapping reactor durations and 90 cases at least 1 s. `temp/tagged-method-audit-xkl06G/fork-lifecycle-method-traceability.json`: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, no unknown tags; R-I1 remains missing. `temp/java-harness-audit-obirnizd/fork-lifecycle-source-comparison.json` verifies all 25 baseline delta paths. G8 reconciled at 13 subclaims/73 method references. Exact-commit hosted Build `40130838292` for documentation head `31c178d34d` passed. The remaining fake creation/mode, collaborator-observation and runtime reviews remain open; G9 is unrun. |
| Captured plugin-base outcome assertions (`90174d41ba`) | Replaced three R-W12 collaborator input-list assertions and a build-log line count with actual copied-file outcomes. The build fixture checks the copied base against build output, changes the live output, and verifies revision preserves the saved base/digest and leaves the changed output untouched; a rebuild would overwrite that output. The installed-copy fixture compares copied files and verifies later installed-file changes leave the saved base intact. Invalid build output leaves no plugin-base artifact. Existing refusal, stale user-output, configured source and trial assertions remain. Production and fake implementations are unchanged; existing configured build-process checks remain. Focused: 18 checks passed. Unrestricted absent-HOME/user.home clean verify: 3,228 tests, 0 failures/errors, 11 skipped, 314.833 s; all 298 runtime-property checks pass and HOME stays absent. `temp/plugin-base-outcomes-FzDjig/` retains preimages, bounded scope, outcome rationale, focused/full receipts and `final-full-timing.md` with all 23 overlapping reactor durations and 70 cases at least 1 s. `temp/tagged-method-audit-xkl06G/plugin-base-outcomes-method-traceability.json`: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, no unknown tags; R-I1 remains missing. `temp/java-harness-audit-obirnizd/plugin-base-outcomes-source-comparison.json` reconciles all 25 baseline delta paths; no new process boundary. G8 reconciled at 13 subclaims/73 method references. Exact-commit hosted Build `40131808847` for documentation head `6087cf5615` passed; remaining fake creation/mode and runtime reviews keep G9 unrun. |
| Shared fork creation and permission checks (`38a31ebc9b`) | Shared two existing real-adapter creation/refusal bodies with the fake. All eleven original assertions remain, including creation-returned versus reopened inventory and the real-only symbolic-link assertion; four physical worktree byte/mode assertions were added. Initial Checkstyle and unused-import PMD runs stopped before tests. The behavioral red then passed all seven real checks and the 15 source-audit checks, but the fake recorded the executable with 0644 instead of 0755. The fake now captures actual portable modes. Both fixtures reuse the existing file writer and immutable permission constants. Focused green: 29 checks; unrestricted absent-HOME/user.home clean verify: 3,230 tests, 0 failures/errors, 11 skipped, 276.917 s; all 298 runtime-property checks pass and HOME stays absent. The fourteen full shared-contract cases took 0.005–0.251 s. `temp/fork-creation-contract-ZE3MHS/` retains preimages, bounded scope, style/red/green/full receipts, assertion-preservation review and `final-full-timing.md` with all 23 overlapping reactor durations and 43 cases at least 1 s. `temp/tagged-method-audit-xkl06G/fork-creation-method-traceability.json`: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, no unknown tags; R-I1 remains missing. `temp/java-harness-audit-obirnizd/fork-creation-source-comparison.json` verifies all 26 baseline delta paths; no additional Git command or fixture owner. G8 reconciled at 13 subclaims/73 references. Hosted Build [40133477895](https://github.com/holdmyspot-com/cat/actions/runs/40133477895) passed on documentation commit `64cf359589`. Refusal/response controls, remaining fake assumptions and runtime reviews keep G9 unrun. |
| Shared fork refusal and captured controls (`10f9ec421a`) | Four shared checks cover dirty-base recovery advice, creation-only exclude flags, unmanaged tracked links and empty-selection diffs. Existing real exclude-file and indexed-link assertions remain. Red: real 17 passed, fake refusal and Core refusal each failed on missing recovery advice; the fake now supplies it. Removed unused internal observer histories and described the fake diff as a controlled response. Focused green: 45 checks. Unrestricted clean verify: 3,236 tests, 0 failures/errors, 11 skipped, 115.792 s; all 298 runtime-property checks pass with HOME/user.home absent. All 29 real/fake shared executions took less than 1 s. `temp/fork-controls-contract-eii3Y2/` retains bounded scope, preimages, red/green/full receipts, internal-observer classification and `final-full-timing.md` listing all five cases at least 1 s plus 23 overlapping reactor durations. `temp/tagged-method-audit-xkl06G/fork-controls-method-traceability.json`: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, no unknown tags; R-I1 remains missing. `temp/java-harness-audit-obirnizd/fork-controls-source-comparison.json` reconciles all 26 cumulative delta paths. No production change, added Git command or real Git fixture owner. G8 remains 13 subclaims/73 references. Hosted Build [40134834746](https://github.com/holdmyspot-com/cat/actions/runs/40134834746) passed on documentation commit `bab44ac687`; remaining fake assumptions and runtime reviews keep G9 unrun. |
| Empty fork storage and shared postconditions (`ddbb4159b0`) | A fake fork with no captured files returned a view without creating its worktree. Red: the new empty-input case failed on absent worktree while all 17 real checks passed. Creation now makes the worktree after input validation. Generic existence, reopened-inventory and workspace-path/scan postconditions run in the existing real creation fixture and fake; the empty-inventory edge is additionally checked in the fake. Existing shared flows also check unknown-version absence and unchanged external bytes. Focused green: 46 checks. Unrestricted clean verify: 3,237 tests, 0 failures/errors, 11 skipped, 98.531 s; all 298 runtime-property checks pass and HOME/user.home stay absent. `temp/fork-storage-contract-wbbhuj/` retains preimages, bounded scope, expected red, green/full receipts, assertion-preservation review, existing runtime-reason review and `final-full-timing.md` listing all five cases at least 1 s and 23 overlapping reactor durations. `temp/tagged-method-audit-xkl06G/fork-storage-method-traceability.json`: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, no unknown tags; R-I1 remains missing. `temp/java-harness-audit-obirnizd/fork-storage-source-comparison.json` reconciles all 26 cumulative delta paths. No production change, added Git command or real Git fixture owner. G8 reconciled at 13 subclaims/73 references. Hosted Build [40135232410](https://github.com/holdmyspot-com/cat/actions/runs/40135232410) passed on documentation commit `56eafe8b72`; remaining fake/input/runtime reviews keep G9 unrun. |
| Captured bytes in fork workspaces (`5005b4e1d8`) | The shared creation check changes the live base after capture and inspects both saved reads and actual staged trial files. Red: all 17 real checks passed, but both fake outputs contained the later live edit. Each fake fork now supplies its captured file contents; supplied bytes take precedence in saved reads and staging, with captured regular-file modes retained. Existing controlled project fallback remains for other fixture owners. Every earlier assertion remains. Focused green: 81 checks, including independent real/fake workspace contracts; unrestricted clean verify: 3,237 tests, 0 failures/errors, 11 skipped, 95.546 s. All 298 runtime-property checks pass with HOME/user.home absent. `temp/fork-snapshot-bytes-OU4UKz/` retains preimages, scope, red/green/full receipts, the repaired fresh-report collector record, assertion review, `focused-summary.json` with all four focused source-audit cases at least 1 s and `final-full-timing.md` with all three full-build cases at least 1 s plus 23 overlapping reactor durations. Existing Javadoc explains those three full-build cases; the broader historical/runtime review remains open. `temp/tagged-method-audit-xkl06G/fork-snapshot-method-traceability.json`: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, no unknown tags; R-I1 remains missing. `temp/java-harness-audit-obirnizd/fork-snapshot-source-comparison.json` reconciles all 27 cumulative delta paths. No production change, new Git command or real Git fixture owner, external process, runner, harness or model. G8 reconciled at 13 subclaims/73 references. Hosted Build [40135748973](https://github.com/holdmyspot-com/cat/actions/runs/40135748973) passed on documentation commit `e7bd37d01c`; G9 remains unrun pending the remaining reviews. |
| Source-audit runtime reasons (`258dda94b9`) | Added Javadoc explaining complete three-module source/test-type inventory initialization and waits in three source-audit checks observed above 1 s. The fourth focused slow audit already explained that shared cost. Only Javadoc changed, verified by equality after stripping Javadoc. Unrestricted clean verify: 3,237 tests, 0 failures/errors, 11 skipped, 101.604 s; all 298 runtime-property checks pass with HOME/user.home absent. `temp/source-audit-runtime-5Uot9A/` retains the preimage, bounded scope, build receipt, `final-full-timing.md` with all four cases at least 1 s and 23 overlapping reactor durations, and `historical-runtime-reconciliation.json`: every one of the 111 historical cases passed in the current build and four remain at least 1 s. That timing reconciliation does not establish why the old executions were slow or that every fixture is minimal. `remaining-observer-review.txt` identifies further fake-history assertions for outcome-based review. `temp/tagged-method-audit-xkl06G/source-audit-runtime-method-traceability.json`: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, no unknown tags; R-I1 remains missing. `temp/java-harness-audit-obirnizd/source-audit-runtime-source-comparison.json` reconciles all 27 cumulative delta paths. G8 remains 13 subclaims/73 references. Hosted Build 40136174692 passed on documentation commit `149c2b1575`; G9 and the remaining final gates remain open. |
| Worktree outcomes without call histories (`43db080ec8`) | Delivery checks the kept candidate inventory and preserves an unowned edit. Promotion/restart and resume checks inspect the actual current worktree, including an after-reconciliation comparison. The port-fake materialization fixture changes its initial state before asserting the resulting inventory. Removed redundant history assertions and the now-unused internal materialized/copiedBases observation APIs and recording lists. All 29 focused checks passed both before and after that internal retirement. Unrestricted clean verify: 3,237 tests, 0 failures/errors, 11 skipped, 95.179 s; all 298 runtime-property checks pass with HOME/user.home absent. `temp/worktree-outcomes-Y07sqo/` retains bounded scope, preimages, assertion and internal-retirement reviews, focused/full receipts and `final-full-timing.md` with all four cases at least 1 s plus 23 overlapping reactor durations. Their existing runtime Javadoc reasons are unchanged and reviewed. `remaining-selection-observers.txt` identifies the separate actual-trial-source outcome work; its PromptTestLocations/StagedSource matches are business queries, not fake-history consumers. `temp/tagged-method-audit-xkl06G/worktree-outcomes-method-traceability.json`: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, no unknown tags; R-I1 remains missing. `temp/java-harness-audit-obirnizd/worktree-outcomes-source-comparison.json` reconciles all 29 cumulative delta paths. No production change, new Git command or owner, process, runner, harness or model. G8 reconciled at 13 subclaims/73 references. Hosted Build 40136695997 passed on documentation commit `7f0773b962`; G9 and other final gates remain open. |
| Trial and measurement source outcomes (`43c4a62e6e`) | Driver checks inspect actual trial sources and every retained staged measurement directory: prompt and mapped rule files are present, unrelated and held-out files absent, and mapped rule bytes retained. Pilot checks inspect actual request sources; its fixture selects the held-out path before excluding it, so exclusion changes the observed result. Existing profile, development/control and required trial outcome assertions remain. Removed unused internal selections/stagedWhole/excluded histories after 52 focused checks passed; all 52 passed again after retirement. Unrestricted absent-HOME/user.home clean verify: 3,237 tests, 0 failures/errors, 11 skipped, 239.398 s; all 298 runtime-property checks pass. `temp/trial-source-outcomes-okIfqA/` retains preimages, bounded scope, internal retirement, focused/full receipts, `final-full-timing.md` listing all eight cases at least 1 s and 23 overlapping reactor durations, and `runtime-javadoc-review.json`; two runtime reasons still need review. The seven PMD rule exceptions match the preceding build and add no affected rule/file pairs. Source-method reconciliation verifies 374 unchanged sources and reparses three: 377 sources, 298 reports, all 2,097 tags passing, 136 IDs, no unknown tags; R-I1 remains missing. `temp/tagged-method-audit-xkl06G/trial-source-outcomes-method-traceability.json` and `temp/java-harness-audit-obirnizd/trial-source-outcomes-source-comparison.json` retain the mapping and all 30 cumulative delta paths. No production source/behavior, new Git command or owner, process, runner, harness or model. G8 remains 13 subclaims/73 references; G9 and the other final gates remain open. Codex cache refreshed through the documented installer after the user directed assuming E5 stopped when absent from available terminals/processes; installer exit 0 and cache/runtime diff empty. The nested daemon sandbox boundary still requires host execution; continue16 is prepared and requested, not launched here. Hosted Build 40138053681 passed on documentation commit `183500ecb8` (295 s). |
| Captured authoring inventory outcome (`ffc30130d3`) | Replaced the literal fake authoring-directory layout assertion with the actual inventory supplied to the prompt author while its copy is live. The expected immutable inventory is read before registering the callback. Every prior failure/rerun/rationale/triage/promotion/count/window/worktree assertion remains. No production or fake behavior/API change. Focused: 45 checks passed, all individual cases below 1 s. Unrestricted absent-HOME/user.home clean verify covering this topic and the runtime Javadocs below: 3,237 tests, 0 failures/errors, 11 skipped, 140.418 s; all 298 runtime-property checks pass. `temp/authoring-source-outcome-GVbVk6/` retains preimages, bounded scope, initial compile correction, focused/full receipts, acceptance and `final-full-timing.md` listing all ten cases at least 1 s and 23 overlapping reactor durations. On final source `8e1845de4f`, source-method reconciliation verifies 374 unchanged test sources and reparses three: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, no unknown tags; R-I1 remains missing. `temp/tagged-method-audit-xkl06G/authoring-source-outcome-method-traceability.json` and `temp/java-harness-audit-obirnizd/authoring-source-outcome-source-comparison.json` retain that mapping and all 30 cumulative delta paths. No added process, Git command/owner, runner, harness/model, native group or profile. G8 remains 13 subclaims/73 references. Hosted Build 40138820743 passed on documentation commit 72bb98bcff in 395 s; the retained hosted-build-result.json records the exact SHA and terminal result. Remaining final gates stay open. |
| Plugin and archive runtime explanations (`8e1845de4f`) | Two method Javadocs explain the required stored start/revision/plugin-base interaction and actual entry-count, cumulative-size and oversized-archive byte-boundary I/O. The latter includes the secure private-copy refusal before archive decoding. Stripping Javadoc leaves both sources identical to their preimages; no fixture or assertion changed. `temp/plugin-archive-runtime-LRFTnq/` retains the separate scope, preimages and equality check. The same unrestricted 140.418 s build above covers both topics. `temp/authoring-source-outcome-GVbVk6/runtime-javadoc-review.json` inventories all ten current slow cases: eight reasons reviewed; the two Codex replay-method runtime explanations and the broader historical fixture/minimality audit remain open. G9 remains unrun. |
| Codex transcript outcomes (`f84c942bd3`) | Three replay transcript checks compare every stored line with the actual controlled input, retaining response identifiers without depending on IDs 3/4. Removed only the redundant fixture invocation-count assertion; completed role outcomes and distinct transcript/effects resources remain. No production behavior or emitted replay events changed. Two method Javadocs explain the required in-memory replay and persisted evidence cost. Focused: 23 checks passed. Unrestricted absent-HOME/user.home clean verify: 3,237 tests, 0 failures/errors, 11 skipped, 109.162 s; all 298 runtime-property checks pass. `temp/codex-transcript-outcomes-F7mEJy/` retains preimages, scope, focused/full XML, acceptance, unchanged PMD-processing-error comparison, and all six cases at least 1 s plus 23 overlapping module durations. All six current slow cases have reviewed method explanations. Source-method reconciliation verifies 375 unchanged sources and reparses two: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, R-I1 alone missing. The corresponding traceability and source-comparison receipts cover all 32 cumulative delta paths; no process, Git owner/command, runner, harness/model, native group or profile added. G8 remains 13 subclaims/73 references. Hosted Build `40140194102` passed on `4c10705527` (426 s); other final gates remain open. |
| Reporting acceptance (`718f13b4d5`) | Strengthened the certification-record status check with a controlled command-file edit; no command executes and every prior record/status/summary assertion remains. Focused: 16 checks passed. Unrestricted absent-HOME/user.home clean verify: 3,237 tests, zero failures/errors, 11 skipped, 111.675 s; all 298 runtime-property checks pass. `temp/reporting-acceptance-YvUeAu/` retains scope, preimages, focused/full XML, fifteen-clause G5 acceptance, unchanged PMD-processing comparison, all eight cases at least 1 s and 23 overlapping reactor durations. The two newly slow Driver checks were subsequently reviewed and documented in `dbb8113e89`; `runtime-followup.json` records the closure. Source reconciliation verifies 376 unchanged test sources and reparses one: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, R-I1 alone missing. Source comparison covers 32 cumulative paths; G8 remains thirteen subclaims/73 references. Hosted Build `40141276602` passed on `405e43ef95` (270 s); other final gates remain open. |
| Driver runtime explanations (`dbb8113e89`) | Two method Javadocs explain the existing seeded judge-model observations and the cap/choice/restart/delivery flow. Stripping Javadocs leaves executable source identical; no fixture or assertion changed. Unrestricted absent-HOME/user.home clean verify: 3,237 tests, zero failures/errors, 11 skipped, 105.363 s; all 298 runtime-property checks pass. `temp/driver-runtime-4e7jf9qk/` retains scope, preimage, equality check, full XML, unchanged PMD-processing comparison, all eight cases at least 1 s and 23 overlapping reactor durations. Every currently slow method has a reviewed runtime explanation. Source reconciliation verifies 376 unchanged sources and reparses one: 377 test sources, 298 reports, all 2,097 tags passed, 136 IDs, R-I1 alone missing; cumulative comparison remains 32 paths. G3 (ten clauses/42 references), G5 (fifteen/72) and G8 (thirteen/73) are reconciled to this source. G1 has 23 retained partial method reviews in `temp/delivery-acceptance-ned3nvr5/g1-partial-review.json`, with remaining clauses and outcome-policy review items explicit. Hosted Build `40141699173` passed on `b6d2fd43aa` (248 s); native and remaining final gates stay open. |
| Retained best offer (`893daf7373`) | Strengthened the existing direct-state ranking check: a third failed attempt is explicitly nonqualifying and leaves the same earlier best attempt on offer. Original ranking assertion remains; no production or fake API change. Focused: 16 checks passed. Unrestricted absent-HOME/user.home clean verify: 3,237 tests, zero failures/errors, 11 skipped, 104.569 s; all 298 runtime-property checks pass. The strengthened method is below 1 s in the full build. `temp/retained-offer-pkg3v7wk/` retains preimage, scope, focused/full XML, acceptance, unchanged PMD-processing comparison, all four cases at least 1 s and 23 overlapping reactor durations. Current slow methods have reviewed runtime explanations. Source audit verifies 376 unchanged sources and reparses one: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, R-I1 alone missing; cumulative source comparison covers 33 paths. G3/G5/G8 are reconciled. G1 now retains 51 partial method reviews and explicit unresolved fixture/outcome-policy work. Hosted Build 40142287762 passed on documentation commit 0046918ec8 in 370 s; remaining final gates stay open. |
| Coherent retained-incumbent regression (`b9259b5c53`) | The public choice starts from a saved changed candidate and an actual completed certified-proven-worse attempt. It asserts the decision and changed workspace before retaining the incumbent; every prior delivered prompt-test, unchanged target and cleanup assertion remains. Controlled evidence avoids replaying formal trials. Focused: 16 checks passed. Unrestricted absent-HOME/user.home clean verify: 3,237 tests, zero failures/errors, 11 skipped, 118.367 s; all 298 runtime-property checks pass. `temp/retained-incumbent-fixture-yggdq7mx/` retains scope, preimage, focused/full XML, unchanged PMD-processing findings, all fourteen slow cases and 23 overlapping reactor timings. Six Core runtime review items are closed by the separate documentation topic below. Hosted Build 40143472818 passed on a46e607e66 in 337 s. |
| Confirmation and revision runtime explanations (`43e9d193ad`) | Six method Javadocs explain the reviewed public start/confirmation/revision, staged source and actual link-refusal costs. Stripping Javadoc leaves executable source identical to the fixture commit. Unrestricted absent-HOME/user.home clean verify: 3,237 tests, zero failures/errors, 11 skipped, 96.891 s; all 298 runtime-property checks pass. `temp/confirmation-runtime-coi62fdg/` retains scope, preimage, equality proof, full XML, unchanged PMD-processing findings, all six slow cases and 23 overlapping reactor timings. Two newly slow GuardPilot methods are closed by the separate pilot-runtime topic below. Current audit verifies 376 unchanged sources and reparses one: 377 sources, 298 reports, all 2,097 tags passed, 136 IDs, R-I1 alone missing; cumulative source comparison covers 33 paths. G3/G5/G8 and all 51 partial G1 references are reconciled. Hosted Build 40143472818 passed on a46e607e66 in 337 s; required final gates remain open. |
| Control-pilot runtime explanations (`034e4dc796`) | Two method Javadocs explain the required 20-trial pilots, staged source and held-out-file outcomes, and failed safety pilot replacement while reusing formatting results. Stripping Javadoc leaves executable source identical. Unrestricted absent-HOME/user.home clean verify: 3,237 tests, zero failures/errors, 11 skipped, 100.075 s; all 298 runtime-property checks pass. `temp/pilot-runtime-xsff1wzl/` retains scope, preimage, equality proof, full XML, PMD comparison, all five slow cases and 23 overlapping reactor timings. The replay-editor runtime review is closed by the separate explanation below. Source audit: 376 unchanged plus one reparsed source, 298 reports, all 2,097 tags passed, 136 IDs, R-I1 alone missing; cumulative comparison covers 33 paths. G3/G5/G8 and 51 partial G1 references are reconciled. Hosted Build 40143774138 passed on 2fb60c90c1 in 337 s; remaining final gates stay open. |
| Replay declaration runtime (`ba754514d5`) | One method Javadoc explains file-backed discovery, scope, review and placement checks before declaring one output with two contributing sources. Executable source is identical after stripping Javadoc. Shared full-build proof is retained with the separate delivery-recovery topic below. |
| Durable delivery recovery outcomes (`f825e06e35`) | Replaced the internal write-hook sequence assertion with preserved physical modification times and persisted plan completion. Every create/edit/delete crash point, actual final file/mode, absent deleted file and single certification-history record remains checked. Focused: 16 checks passed. A deliberate rewrite of an already completed file failed at completedTime; exact production source was restored before unrestricted clean verify. Full build: 3,237 tests, zero failures/errors, 11 skipped, 125.875 s; all 298 runtime-property checks pass and HOME/user.home stays absent. temp/delivery-recovery-outcomes-85dfp83w retains both topic scopes/preimages, mutation receipts, full XML, all nine slow cases and all 23 overlapping reactor module durations. Three newly slow Git receipt/worktree checks retain actual boundary coverage; their runtime classification is reviewed. Source audit verifies 375 unchanged plus two reparsed sources, 298 reports, all 2,097 tags passed, 136 IDs and R-I1 alone missing; cumulative comparison covers 34 paths. G3/G5/G8 and all 51 partial G1 references are reconciled. Hosted Build pending; remaining final gates stay open. |
| WI-125 | Hosted Build `40101753979` passed for `79275818e7`. Implementation and local gates passed: 3,207 tests, zero failures or errors, 11 skipped; live E5 continuation remains pending (R-M1, R-M3, R-M4, R-M5, R-M6, R-M7, D102). User ruling, 2026-10-24: add an explicit `keep_identity` choice for all measurement changes except loader changes. Retain original conditions, evidence, baseline and certification state; record accepted observations, reject unaccepted changes and report the acceptance. This unblocks the user's requested continuation of Codex E5 with its existing identity/results. |
| WI-111 | In progress. User ruling (2026-10-20, `design.md` D90): `CLAUDE.md` is Claude Code's prompt file and `AGENTS.md` is Codex's. Done (`3ac240d4c4`; full build 2995 tests, 0 failures, none at or over 1 s, 46 s): a trial's session request says whether it runs on the control source, and the Claude runner sets `instructionFiles` to `claude-md` for such a trial (option `--instruction-files`); the version guard also hashes the control-trial settings, and the Claude session environment version is 9. The runner's option parsing has no unit test, as for its other Claude options, because that needs a harness process. The Claude plugin was reinstalled from that build (2026-10-20T14:49Z, `install-claude.sh` exit 0, runtime modules identical to the build's). Live check passed (2026-10-20, E1 Claude rerun of the guard pilot on that plugin, `~/cat/worktrees/e1-claude3/receipts/repilot2-*`): the motto guard passed in 0 of 60 control trials, against 59 of 60 before; a kept control trial had a 0-byte `CLAUDE.md` beside the 292-byte `AGENTS.md` that asks for the motto, and its reply had none. The contract was then confirmed with `weakest_cell` (user authorization); campaign `20261020T023203Z-628bcb`, created under session environment version 4, paused before its first trial with the measurement conditions changed (`session_environment_version`). User ruling (2026-10-24): retain the existing identity and accumulated results; authorization is recorded in `~/cat/worktrees/e1-claude3/receipts/authorization.txt`, and applying `keep_identity` in the owning session remains pending. User ruling (2026-10-20): trials other than control trials inherit the parent session's `instructionFiles` setting, and CAT offers no per-test setting. User ruling (2026-10-20): control trials keep their `claude-md` pin as the one exception. Settled (2026-10-24, `93b11f34fc`, test only): `start AGENTS.md` in Claude, in a project with no `CLAUDE.md` importing it, is refused (exit 1, status `refused`, no session started), because trials would not load the target; `start CLAUDE.md` in Codex without an `AGENTS.md` importing it is refused the same way. History: in the rerun Claude E1 guard pilot, 59 of 60 control trials closed with the motto although their `CLAUDE.md` was empty: Claude Code 2.1.304 ships the built-in plugin `cc-plugin-agents-md`, whose default `instructionFiles` mode, `claude-md-or-agents-md`, gives a project with no `CLAUDE.md` of its own its `AGENTS.md` files instead, and it treats an empty `CLAUDE.md` as none. A probe with every ancestor instruction file excluded, an empty `CLAUDE.md` and an `AGENTS.md` asking for a closing word gave that word in 2 of 2 replies, and in 0 of 2 with `pluginConfigs["cc-plugin-agents-md@builtin"].options.instructionFiles` set to `claude-md`. So a control trial of a `CLAUDE.md` that only imports `AGENTS.md` measures the incumbent's prompt, and every Claude guard pilot so far (E1, E4) ran that way. The trial settings also do not carry the user's own `instructionFiles` mode, and the ancestor excludes do not name `AGENTS.md`. Done regardless of the ruling (`4f16e07a5f`, full build 2978 tests, 0 failures, none at or over 1 s): trials exclude `AGENTS.md` and `.claude/AGENTS.md` above the run directory, keep the user's `instructionFiles` setting, and the loader configuration identity includes that setting when the user sets one; the Claude session environment version is 8. The Claude plugin was reinstalled from that build (2026-10-20, `install-claude.sh` exit 0, runtime modules identical to the build's). Follow-up approved by the user (2026-10-24), implemented as `86012e4ff7`: Codex trials inherit the parent's explicit `project_doc_fallback_filenames` and `project_doc_max_bytes` values from `config.toml`; absent settings remain absent, malformed TOML is refused, and the canonical values enter loader identity. Session environment version is 11. TDD covers absent/empty/zero values, multiline and quoted keys, top-level selection, malformed input and identity stability/change; an independent public-operation probe reproduced the same values before and after integration. Full build: 3,216 tests, zero failures/errors, 11 skipped, 101 s; nonexistent home stayed absent. Evidence: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/project-doc-selection-vztxpzuc`. Follow-up `fe211dc63b` skips zero-byte instruction files, matching [Codex discovery](https://learn.chatgpt.com/docs/agent-configuration/agents-md): an empty override falls back to nonempty `AGENTS.md`, and two empty files contribute no source. The new public `loadedSources` test failed on the wrong override selection before the fix, then passed with the existing precedence check. Follow-up `bf9de0e95e` derives sources from the inherited ordered fallback filenames and omits project instructions when the inherited integral byte limit is zero. Three public `loadedSources` regression tests failed for the intended missing behavior before the production edit, then all five focused discovery cases passed. Standalone final-source/dependency recreation and the integrated public-operation probe agree on all eight retained TOML inputs, each with built-in files present and absent. The full build passes 3,220 tests with zero failures/errors and 11 skipped. This changes discovery metadata only; native trial loading and session environment version 11 are unchanged. Live verification remains pending; WI-111 stays open. |
| WI-110 | Done (user ruling, 2026-10-20): a triggering test case's passing streak extends one run at a time until it is unlikely for the working candidate, with no 4-run limit, so an attempt can fix a test case that the working candidate passes most of the time. In the Codex E7 campaign, attempts 10 to 19 passed every promotion run and were all rejected, because the working candidate passed the triggering test cases 33 of 35 to 40 of 41 times and 4 passes could not be unlikely; those test cases now need 28 to 49 runs. Coverage: `PromotionTest.workingRecordDeterminesStreak`, whose 3-of-4 case failed before the change (rejected after 4 passes) and now promotes after 6. Commit `edd3c888ef`; full build passed (2973 tests, 0 failures, 10 skipped; 77 s; at or over 1 s, untouched: `OptimizerCoreTest.choosesConflictsBeforeRevision` 1.27 s). The Codex E7 campaign uses it only after a Codex reinstall. |
| WI-108 | Done (user ruling, 2026-10-20): every CAT launcher runs Java directly, without `bwrap`, on both harnesses, and its JVM options include `-XX:+PerfDisableSharedMem`, so a launcher runs inside a sandboxed session and two JVMs with the same PID write no shared perf-data file. Before the change, in a nested Claude session with the sandbox on, `bin/plugin-info` failed with `bwrap: No permissions to create new namespace`. Coverage: `JlinkImageBuilderTest.rewritesLauncherWithPrivatePerfData` and `rewritesBatchLauncher`, which failed before the change, `CodexImageLaunchersTest` and `jlink-launchers.bats`, whose `bwrap` now fails to create a namespace (Bats 34 of 34). Commit `bebba77b8e`; full build passed (2973 tests, 0 failures, 10 skipped; 88 s; at or over 1 s, untouched: `DriverTest.attemptLoadsOnlyReceivedFiles` 1.70 s, `TestabilityAuditTest.onlyMainReadsEnvironment` 1.10 s). Both plugins reinstalled; a nested Claude session with the sandbox on ran the installed `plugin-info`, which printed its three lines and exited 0. |
| WI-107 | Done (user request, 2026-10-20): every nested session is denied reads of the host project except its own run directory and the evidence it reads; in Claude the denied project is replaced by every entry under it except those and the directories leading to them, and in Codex likewise on Linux when read-only evidence lies inside it. A Claude session receives only the host's `deny` and `ask` permission rules, no longer its `allow` rules, `additionalDirectories` or `defaultMode`. Without the host's `allow` rules a trial lost its grant of the `Skill` tool, so CAT allows that tool itself. Session environment versions: Claude 6, Codex 7. Coverage: `ClaudeConnectionSettingsTest.writesIsolatedSettings`, `ClaudeRunnerTest.deniesProjectAroundRunRoot`, `CodexRunnerTest.deniesProjectBesideEvidence`, `ClaudeSessionsTest.deniesHostProjectReads`, each of which failed before the change. Commits `fb1a1dd484` and `9ad79579ad`; full build passed (2972 tests, 0 failures, 10 skipped; 56 s at load average 13.9 on 16 cores; at or over 1 s, all untouched: `RunnerSessionsTest.failsAtTheTimeLimitUnlessTrial[3]` 2.56 s, three `CommitGateTest` tests at 1.37–1.38 s and `DriverTest.attemptLoadsOnlyReceivedFiles[0]` 1.01 s; rerun at load 8.6, only the last stayed over 1 s, at 1.53 s). Live (Claude Code 2.1.304, Haiku, the Claude plugin reinstalled from that build): a session that the installed `claude-runner` started with `--install-cat` and `--deny-read` of a project holding its run root launched `cat:help`, and its read of a file elsewhere in that project was refused; before the `Skill` grant, the launch was refused. Not yet shown: a rerun guard pilot that reads nothing of the project and leaves no stray `.claude` directories. |
| WI-106 | Done (user request, 2026-10-20): the bundled rule `include/common/operator-precedence.md`, routed from the default rules, has agents parenthesize an expression where two different binary or ternary operators meet, keeping the language's grouping, except for conventions every reader knows; it reports an expression whose grouping looks unintended instead of regrouping it. Prompt tests `mixed_operators_grouped` and `conventional_precedence_kept` are committed and not yet run. Commit `888475617f`; full build passed (2963 tests, 0 failures, 10 skipped; 63 s; at or over 1 s only the untouched `DriverTest.attemptLoadsOnlyReceivedFiles` at 1.21 s; the rule is packaged in both runtimes). The rule names `&&`, `||` and `? :` by symbol instead of "logical and", "logical or" and "conditional operator", because Java calls `&&` and `||` conditional operators too (user approval, 2026-10-20). |

Changes made to `design.md` and this plan during implementation, apart from this section:

- 2026-10-24: `design.md` §10 T3 and this plan §8 record the user's unit-test default and the criterion for retaining
	an integration boundary check; product behavior is unchanged.
- R-S6: owner changed from WI-1 to WI-1, WI-3. Its proof `AdmissionTest` tests admission, which WI-3 builds.
- `design.md` §11: added H11–H13 (premises P1–P3).
- §1.1 assignments for WI-0 and WI-15: at the user's direction (2026-10-01), the orchestrator sends ESC through a terminal multiplexer, and the user presses ESC only if that fails.
- `design.md` §11: added H14–H23 (premises P4–P11). §12: added a result column. §3.1: judge and contract author output may be one JSON object alone in a Markdown code fence (H23). §3.2: a Claude turn includes its background children's completion; Codex trials cannot create child worktrees (H17, H18). §3.8: the Codex runner interrupts with `turn/interrupt`; reconciliation terminates the processes recorded in leftover run manifests (H19, H20). §5: loading observation names what each harness exposes (H21).
- §6 and §1.1: WI-8's runner fixes need only WI-0, run before the rest of WI-8, and go to `optimize-high`.
- §2: added a candidate overlay store follow-up (WI-4/WI-9). `design.md` §4.1 stores candidate overlays, but no work item built the production store that WI-9's `CandidateOverlays` needs.
- `design.md` §3.3 and D8: the risk ceiling first permits 2, 4 and 8 trials at `n` = 11, 23 and 47 (D8 said 48); R-E4's proof now tests each threshold on both sides. D3: a committed 97% candidate certifies about 17%, matching §2.7 (it said 15%). WI-1 found both.
- §4: added the gate results. WI-8: added the runner fixes for H17 and H19 and their allowlist. WI-9: reconciliation terminates recorded processes. WI-10: loading split and the output extraction rule.
- `design.md` §5: a runner or CAT version change was a material harness change (H17), recorded as D21 after WI-3 asked; D21 now makes a declared session environment version material instead (WI-41).
- `design.md` §10 (T3, T6) and §14 (G6, G7, G9), plan §1 test tiers, §3 proofs, WI-5, WI-7, WI-8, WI-10, WI-14, WI-15, §8 and §9 gates 2–3: Java tests are unit tests and never start a model; only prompt tests, run end to end, use one (user ruling, 2026-10-01). Native (N) proofs became end-to-end (E) proofs; WI-14 retires the native tier.
- `design.md` §3.2 and §11 H24: a Claude turn ends at a root reply only when the task list is empty and no root completion notice is unseen by a later root model call. Found by the follow-up attribution probes.
- `design.md` §3.2 and §11 H25: a Claude turn ends when Claude reports itself idle with an empty task list; this replaces the H24 stream rule. A Claude that does not report its state fails the turn at its first `init`. The runner has no turn time limit of its own and reports a waiting turn to its caller every 30 seconds (user rulings, 2026-10-01).
- `design.md` §4.1, §4.2 and §4.3: the unconfirmed proposal is stored with the campaign; confirmation records trial fixtures and derived prompt sources into the fork; an authority warning is an `awaiting-user` decision fed by the prompt author's report, and an authorized target is recorded in the campaign. §2: added the authority-warning follow-up. Found in the WI-13 review.
- §8: the unit-test runtime limit is 1 second, matching `design.md` §10 T7 (it said 2), and each integration lists slower tests.
- WI-14: the session-port contract keeps running against the real adapters, over recorded events, once the native session tests are deleted.
- `design.md` §4.1 and §4.2: the stored proposal is the latest one with its derived sources, and a revision after confirmation that needs new fork participants is refused (found in the WI-13 amendment).
- `design.md` throughout: "native" became "harness" where it meant the harness or its model process; §10 T3: fakes carry only contract-checked behaviour, recordings name their harness version, and end-to-end runs refresh them. Plan §2 harness terminology row, §8 fake rule, WI-14 and WI-15 recordings (user rulings, 2026-10-01).
- §2: added the pouch-scope follow-up for the optimizer and bridge wiring (found when the user asked about pouch scopes).
- `design.md` §10 T4 and R-T1: a harness's production scope may read the process environment, like `main`; test scopes supply their own values (found in the pouch-scope integration).
- WI-14 and WI-15: the session contract tests and the remaining native-tier removal moved to WI-15, because the existing recordings cannot satisfy every `SessionPortContract` check; WI-15 records untrimmed streams and an interrupted turn per harness. WI-15 runs Codex first and starts Claude only after Codex passes (user ruling, 2026-10-01).
- `design.md` §3.9: the development minimum counts the commit gate's batch condition. The old formula alone could undercount, contradicting "exact". WI-12 found it.
- `design.md` §1.2, §2.1, §3.1, §3.9, §5, §6.1, §6.4, new §6.6, §9, §12 (P12, P13), §13 (D11, D15, D22–D26) and §14 (G1, G2, G5, G7): trial profiles and cells as the statistical unit; deterministic checks with controls beside semantic judgment under a frozen role profile; a core-task requirement in every test; prompt author failure classification and a per-hunk citation manifest; a minimality pass; candidate-versus-incumbent prompt size and input tokens (user rulings, 2026-10-01). Plan: R-C5 rewritten; R-C8–R-C11, R-D10, R-D11, R-E17, R-E18 and R-I5 added; WI-16–WI-18 added with §1.1 assignments, §6 dependencies and `DriverTest` traces 15–18; WI-15's trial-based scenarios now wait for WI-16–WI-18.
- `design.md` §3.3 rule 2, §6.6 and D27: development cell tiering; plan R-E3 reworded, R-E19 and WI-19 added with §1.1, §6 and trace 19 (user ruling, 2026-10-01). `design.md` P14 and plan §4: the effort probe tests a pre-stated null hypothesis with the 2×4 exact test, and a non-rejection does not justify dropping efforts.
- `design.md` §1, §2.1, §2.7, §2.8, §6.4 and §6.5: the outcome table lists `not certified` and says which outcomes close
	the campaign and what follows each open one; "material category", "material requirement" and "material obligation"
	became the categories, requirements and obligations of the confirmed contract; a weak round makes 3 attempts for each
	failing category, as R-D6 counts attempts; `accept without certification` is offered at a stop, a plateau or
	unavailable certification when the working candidate is not the incumbent. Plan R-D12 says "for that category". All
	four follow the implementation (found in the convention pass).
- `design.md` §1.2, §3.1, §6.4, D29 and H31: a turn's reply is every assistant message of the turn; R-C12 and WI-22 added with §1.1, §2, §6 (user ruling, 2026-10-02, after the first Codex E1 campaign).
- `design.md` §2.7, D29 and D30: prompt authors prefer prompt files other than `AGENTS.md` and `CLAUDE.md`, and treat a
	failure in an interim message as a prompt defect, since its wording can be shaped even if it cannot be suppressed;
	R-D10 cites D30 (user ruling, 2026-10-02).
- `design.md` §8: a scenario for guidance packaged as a module resource lives in the module's `src/test/resources/`;
	R-I3 and WI-23 added with §1.1, §2, §6 (user ruling, 2026-10-02).
- `design.md` §8 states R-I5's obligation (locating CAT's files without undefined shell variables), which R-I5 already
	cited; §9 lists the `CandidateOverlays` port. Plan §7 declares H-13 (needed edits → authority warning) and H-14
	(candidate overlay), traced from the driver (found in the convention pass).
- `design.md` §2.7, §9, §14 G1 and D28: 3 concurrent prompt author attempts while the working candidate is weak, best accepted attempt promoted; plan R-D12, WI-20 with §1.1, §6 and trace 20 (user ruling, 2026-10-01).
- `design.md` §3.3 rule 2, §6.6 and §12 P15: each model's lowest-effort active cell is sampled first in development; P15 asks whether developing only in the weakest model's lowest-effort cell suffices for stronger models and higher efforts, tested after a real prompt file is optimized; plan R-E19 and WI-19 updated, §4 P15 probe and §2 row added (user ruling, 2026-10-01).
- `design.md` §11 H28 and §12 P14 result: a rule without a conflict on `gpt-6-luna` also varies with effort (user request, 2026-10-01).
- `design.md` §11 H27 and §12 P14 result: the intermediate-pass-rate rerun shows reasoning effort changes compliance (user request, 2026-10-01).
- `design.md` §12: added P14 (does reasoning effort change compliance?); plan §4 adds its probe and §2 its row (user request, 2026-10-01).
- §1 and §9 gate 8: the codebase never cites `design.md` or this plan, which are deleted after implementation; the
	requirement tags stay until the traceability gate passes, then a last commit removes them (user ruling, 2026-10-01).
- §2: added the plugin-root follow-up (R-I5). Codex tool shells define no `CODEX_HOME`, so the `skill-env.md` derivation always failed (found in WI-15).
- `design.md` §3.8: the Claude runner interrupts with a stream-json `control_request` (H29). §11: added H29.
- `design.md` §6.1, §8, D13 and G10; plan R-I3, §1.1, WI-21 and §6: a guidance correction records its regression scenarios under `.cat/tests/`, they are recorded, not tested, until a covering campaign, and CAT refuses a proposal that omits one. Nothing ran the old rule suites once the prompt-evaluation runner was retired (user rulings, 2026-10-02).

- `design.md` §2.7, §14 G1, D30, D33 and D34: an attempt that fixes at least one triggering test case without a
	regression is promoted, a reduction keeps every fix, the attempts of a round follow distinct approaches, and prompt
	authors correct an instruction in the file that states it; R-D3, R-D11 and R-D12 reworded, R-D14 and WI-26 added with
	§1.1, §2 and §6 (user ruling, 2026-10-02, after the E1 `gpt-6-luna`/low plateau).
- `design.md` §2.7, §14 G1 and D35: a test case counts as fixed only when the attempt's streak there is unlikely for
	the working candidate, with up to 4 runs, and a promoted candidate that falls clearly below its predecessor is
	reverted; R-D3 reworded, R-D15 added to WI-26 (user ruling, 2026-10-02).
- `design.md` §3.1, §11 H33 and D37: the contract author, judge and prompt author run without CAT's plugin and receive
	their role's rules in the brief; R-E20 and WI-27 added with §1.1, §2 and §6 (user ruling, 2026-10-02).
- `design.md` §3.1, §6.4, §11 H32 and D36: the judge receives a rendered evidence section instead of the trial's log;
	R-E21 and WI-28 added with §1.1, §2 and §6 (user ruling, 2026-10-02).
- `design.md` §6.6: the Codex inventory leaves out the `ultra` effort (`max` reasoning plus automatic task
	delegation), so each model's highest effort is the one below it; the cell counts dropped from 44 to 39.
	`CodexProfileInventory` skips `ultra`, proved by `CodexProfileInventoryTest` (user ruling, 2026-10-03).
- `design.md` §1, §1.2, §2.1, §2.5, §2.6, §2.7, §2.8, §3.3 rule 2, §3.9, §6.1, §6.3, §6.6, §8, §12 P15, §13 (D27,
	D42, D43) and §14 (G2, G3, G4, G8): development, the commit gate and prompt author rounds run only in each category's
	development cells (its weakest cell, plus any cell where certification failed); the weakest cell is the lowest effort
	of the least capable model in the scope by the vendors' published capability ranking, found with no trial; the user
	chooses the certification scope at confirmation from three choices (weakest cell only, every cell of each model
	family's latest version, or a scope the user describes), with no default; every prompt test varies and its test cases
	are split into a development part and a held-out part, and only held-out test cases certify or measure the baseline.
	Plan R-E3, R-E19, R-D1, R-D3, R-D7, R-D9, R-C1, R-C4, R-C11 and R-P4 reworded; R-C15, R-C16 and R-E22 added with
	WI-32, WI-33, §1.1, §2, §6 and traces 19 and 21; WI-19's tiering is replaced (user rulings, 2026-10-04).
- `design.md` §1.2, §6.1, §6.3 and §13 (D43): each prompt test marks the key dimensions that carry its content,
	and test cases split into parts by their key values, so a held-out test case never repeats a development test case's
	content in a new combination (user ruling, 2026-10-04). Plan R-C16 and WI-33 reworded.
- `design.md` §2.8, §3.1, §6.3, §13 (D44) and §14 G4: after an attempt ends with formal failures, a contract author
	session that sees only development values adds at least 4 development-only key-value combinations per failed test
	aimed at its failed requirements; they take half of that test's development draws and development counts restart.
	R-C17 and WI-34 added with §1.1, §2 and §6 (user ruling, 2026-10-04).
- `design.md` §3.3 rule 4, §13 (D45) and §14 G8: formal trials open a category's cells in order, development cells
	first, then each scope cell once every cell below it is certified. R-E23 and WI-35 added with §1.1, §2 and §6; the
	P15 protocol certifies its cells in the same order (user ruling, 2026-10-04, after two P15 certifications each spent about
	500 runs in stronger cells before failing in the weakest).
- `design.md` §6.3 "Held-out content on disk", §13 (D46) and §14 G4: every nested session except the judge runs with
	its harness sandbox denying reads of the snapshot, the proposal, the trial records and the report. R-E24 and WI-36
	added with §1.1, §2 and §6, replacing the §2 row "Held-out values on disk".
- `design.md` §4.1, §6.3 and D46 revised after WI-36's probes: held-out content lives in a held-out directory and a
	held-out run root, denied as whole directories to every session whose output reaches development; formal and
	baseline trials and judges are exempt; a host with the sandbox off is refused. WI-37 added with §1.1, §2 and §6.
- `design.md` §6.1, §6.3 ("Failure behavior", "Reproduction before tuning"), §2.8, §3.1, D47 and G4: formal failures
	reach development with their behavior and are reproduced in development, by rounds of replaceable values with
	stress settings, before any prompt author works on them; CAT pauses after 4 rounds without reproduction. R-C18, R-C19
	and WI-38 added with §1.1, §2 and §6 (user ruling, 2026-10-04).
- §4 P15 and the complete-file-reads bar: P15 certifies by the optimizer's rule (`design.md` §2.3, §2.4), not 59/59
	per task with a stop at the first blocking failure, and numbers its attempts under that rule from 1; p15-60's stopped
	attempt continues as attempt 1 (user rulings, 2026-10-04).
- WI-37 integrated; its planned `codex sandbox` test became manual evidence, because §8 forbids a test that starts a
	harness process; Claude validation adds `allowRead` and `bypassPermissions` checks. WI-39 added: reconciliation
	searched `<campaign>/runs` for leftover runs instead of the run roots.
- WI-40 added: WI-37's validation found that a Claude `sandbox.filesystem.allowRead` in managed, project or host user
	settings re-allows held-out reads.
- `design.md` §5, D48 and G5: a change to a command that an unchanged prompt tells the model to run keeps a
	certification current; a certification is repeated only when the model's behavior must change or the model changes
	(user ruling, 2026-10-04). No code follows from it: a command that the prompt ships runs by its path, which is not a
	host tool, and since D95 (2026-10-22) CAT records no host tools at all, so a changed command never makes a result
	historical. The complete-file-reads delivery fixes `read-file` under it.
- `design.md` §5, D21 and G5 generalized (user ruling, 2026-10-04): a certification is repeated only when the model's
	behavior or the desired behavior has changed. The runner and CAT versions are recorded for information; a declared
	session environment version is material in their place. WI-41 added with §1.1, §2 and §6.
- `design.md` §5, D49 and G5: certification parameters define the desired behavior; a certification stays current
	after they change only while its claim implies the new one (95% implies 94%, not 96%) (user ruling, 2026-10-04).
	Added to WI-41.
- `design.md` §5, §6.4, D50 and G5: a judge version that CAT declares per harness, and increments whenever a property
	that can change a verdict changes, identifies the judge; a new judge version makes a certification historical (user
	ruling, 2026-10-04). WI-42 added with §1.1, §2 and §6.
- `design.md` §4.1, §4.7, §5, D51, G1 and G5: a certified campaign removes its files from `.cat/work/` once its
	candidate is delivered or the user keeps the incumbent, and delivery writes a certification record of a few kilobytes
	into the project, from which `status` reports applicability (user rulings, 2026-10-04). WI-43 added with §1.1, §2 and
	§6.
- `design.md` §6.3: reproduction trials are targeted and stay out of representative development counts, as WI-38
	implemented. WI-44 added for the reports' gap that WI-38 found.
- `design.md` §5: a target's `status` reports its certification record whenever it has no resumable campaign, and from
	the other harness the harness-specific conditions are unchecked rather than changed, as WI-43 implemented. R-M6
	updated.
- `design.md` §4.3, §4.7, D52 and G2: every external file is a read-only member of the prompt family; no choice makes
	one editable and delivery writes no file outside the project (user ruling, 2026-10-05). WI-45 added with §1.1, §2
	and §6; E7, WI-6, WI-11 and H-13 updated.
- `design.md` §2.7, the outcome table and D53: a complete round without a promotable attempt keeps the working
	candidate and resumes its representative trials; `not certified: plateau` requires that candidate to have more than
	2 failures in a category (user ruling, 2026-10-05). WI-46 added with §1.1, §2 and §6.
- `design.md` §2.7 "Approaches" and D59: six approaches learned from hand tuning P15's rule join the list, and each
	round continues a category's approaches where its previous round stopped (user ruling, 2026-10-07). R-D14 reworded;
	WI-59 added with §1.1, §2 and §6.
- `design.md` §1.2, §2.7, §6.1, §6.3 and D43: "test definition" and "test template" became **prompt test**, and
	"instance" became **test case**, in the design, this plan and the code. A test case is a prompt test with one value
	for each variable, so a repeated draw is another trial of that test case, and a further failure of a test
	case awaiting its diagnostic reruns reproduces the defect (user ruling, 2026-10-09). R-D2 and R-C4 reworded. The
	campaign format becomes 27 and the proposal format 9, because the frozen judge prompt's placeholder is now
	`{{test_case}}`; R-C5 gains the test that refuses an older proposal. The judge prompt's wording changed with it, so
	the judge version becomes 2 in both harnesses (D50).
- `design.md` §1.2, §6.1, §6.3, D43 and D44: each variable of a prompt test states whether it is
	**outcome-changing**, meaning that two of its values need different expected outcomes, and the split uses the
	outcome-changing values. This replaces each prompt test's list of key dimensions and removes the 2026-10-04 default
	that treated every variable as key when none was listed; a prompt test that marks none is refused (user ruling,
	2026-10-09). R-C16 and R-C17 reworded, with WI-33 and WI-34. The proposal format becomes 10 and the campaign format
	28. The contract author, prompt author and failure-values prompts changed their wording with it.

- `design.md` §1–§6, §8–§10, §14 and D60–D66: prompt tests are directories holding `scenario.md` (variables, turns
	and assertions) and `fixtures/`; test cases carry values and an expected outcome, with filler from generators built
	into CAT; the split hashes the normalized expected outcome; each cell is one prompt test under one profile, certified
	at a per-campaign minimum pass rate `p_min` and against control trials per assertion; built-in `tool:` checks and an
	added expected-outcome assertion replace deterministic checks with controls and the core-task requirement; a guard
	pilot runs before confirmation; delivery writes the values file (user rulings, 2026-10-10). D23, D24, D43 and D44
	are revised or superseded. WI-60 to WI-69 added with §1.1, §2 and §6; R-S10, R-S11, R-F7, R-C20 to R-C23, R-W10 and
	R-W11 added; R-S2, R-S3, R-S6, R-D1, R-D5, R-D7, R-C1 to R-C5, R-C8 to R-C12, R-C16 to R-C18, R-E21 and R-I3
	reworded. The commit gate's 99% and 98% became `(1 − p_min)/5` and `2·(1 − p_min)/5`, generalizing the 99% ruling.
- `design.md` §4.2: CAT records a proposal's prompt tests, trial fixtures and derived prompt sources into the fork
	when it presents the proposal rather than at confirmation, so the guard pilot reads the files that trials will read.
	Before confirmation a later proposal leaves out files that only earlier proposals needed, keeping their copies;
	after confirmation the fork only grows, and confirming a revision that needs more files moves the campaign onto the
	larger fork (user ruling, 2026-10-10, replacing an earlier ruling that day that froze the fork at confirmation).
- `design.md` §2.1, §2.3, §2.4, §2.5, §2.7, §3.3, §6.6 and §13 (D35, D65, D73 revised; D74 to D76 added): at
	`p_min ≥ 0.95` the minimum pass rate implies the control and incumbent comparisons, so no paired trial runs; a
	promoted candidate is reverted only when its interval and its predecessor's do not overlap; the readiness gate is
	now the commit gate (user rulings, 2026-10-14). The §6.6 cost's "at least 93 pairs" was wrong: a guard that
	both trials of every pair pass needs 105. WI-78 to WI-80 and R-F9 added; R-D1, R-D15, R-F7, R-F8 and R-S11 reworded.
- `design.md` §1.2, §3.1, §4.1, §4.4, §5, §6.1, §6.3, §8, G7, D77 and D78: the contract author writes prompt tests as
	files in a draft directory that CAT seeds with the recorded ones, and the proposal keeps only what belongs to the
	contract; CAT derives the files that load, the hook registrations and the mappings before the session and gives the
	author `contract check` and `contract fixtures`; scenarios gain `## Expected Behavior`; the plugins that trials
	install come from a committed `prompt.json` in the target's test directory, and `start` builds each plugin the
	project builds on the fork commit (user rulings, 2026-10-15). WI-81 and WI-82 added with §1.1, §2, §6 and H-15, H-16;
	R-C26 and R-C27 added; R-W12, R-E20, R-W10 and R-C20 reworded; WI-71 needs both.
- `design.md` glossary, §4.1, §4.7, §5, §7, D17, D51 and D79: each host session has a work directory,
	`.cat/work/<session-id>/`, that one component creates with a marker and that a later session start removes once
	the harness no longer has the session's transcript; a campaign lives in the work directory of the session that
	starts it, and only that session runs it; certified deliveries append their records to `certifications.json` beside
	`prompt.json` (user request and rulings, 2026-10-16). WI-83, WI-84 and WI-85 added with §1, §1.1, §2, §6 and H-17;
	R-W13 added; R-W1, R-L1 and R-M6 reworded. WI-71 needs all three: the session rules that its trials load state
	`CAT_TEMP`, its campaign must start in the new storage, and its delivery writes the new history.
- `design.md` §3.9, D11 and G5 and plan R-E18 and WI-18: the status's prompt size is the size of the target prompt file
	alone, not of derived sources, project rules or the rest of the prompt system (user ruling, 2026-10-21).
- WI-86: the proposal names a trial plugin's files by plugin, leaves the generated `values.json` out of an edited test's
	difference, describes generated values without a seed or version, and groups certification scope cells by prompt
	test and profile (user request, 2026-10-17). WI-71's revised proposal needs it.
- WI-87: the session work directory becomes the CAT session directory, `.cat/sessions/<session-id>/`, in every path,
	identifier and text of the repository (user request, 2026-10-17).
- WI-88: a passing trial's two project copies are deleted once its verdict is recorded, and failed trials keep
	theirs (user ruling, 2026-10-17). R-W14 added; D51 reworded.
- WI-89: a trial's workspace holds the prompt system and its prompt test's `workspace/` directory, whose symbolic
	links that lead outside it reach the trial as copies of their targets and whose other links stay links, instead of
	the whole fork with `fixtures/` and glob `patterns` (user rulings, 2026-10-17). §4.4 and §6.3 reworded; D82 and
	R-W15 added; D67 partly superseded. A prompt test's fixtures are renamed its workspace files, and the trial
	fixtures the ignored files, without compatibility; the glossary's Fixture entry becomes Workspace file. The
	contract's ignored files are then retired without compatibility, because a trial receives a file outside the prompt
	system only through a link in `workspace/` (user ruling, 2026-10-18); D83 added.
- WI-83: the Codex PostToolUse hook deletes a `run-part` output once its last part arrives uncut and no cut part
	awaits a re-read, and only a file that `run-part` saved (user request, 2026-10-16).
- WI-70's delivery step: the session rules reach Codex through a rules file that the agent reads with `run-part`,
	and `client/bin/read-file` is already retired (`0615af40f`). WI-71: the general-knowledge prompt test is removed
	(`a61c0120c`).

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
| R-S2 | For the confirmed minimum pass rate `p_min`, certification e-value with the four bets at `r = p_min + (1 − p_min)·c` and their weights. Exact rational decision; equality never certifies. Floating-point shortcuts only outside a proven-safe margin. | §2.3 | WI-1, WI-60 | U: `BettingProcessTest` (boundary pairs `(S, F)` either side of `1/α_i` for `i = 1..5`, at 0.95 and at other `p_min`) |
| R-S3 | Below-minimum e-value: for the minimum pass rate `p_min` and the number of shortfalls `1 − p_min` below it, `n_short` = 9, 3, 1, the bet against the pass rate `p_min − n_short·(1 − p_min)` is `λ = min(1, n_short·(1 − p_min)/p_min)`; and the clearly-below decision. | §2.3 | WI-1, WI-60 | U: `BettingProcessTest` |
| R-S4 | Incumbent deficiency decision at `K/γ_b`. For `b = 1`, the fewest failures that prove deficiency are 10, 11, 11, 12 and 12 of 50, and 16, 16, 17, 17 and 17 of 100, for `K` = 1, 2, 3, 5 and 8. | §2.3, §2.5 | WI-1 | U: `BettingProcessTest` |
| R-S5 | Display bounds that invert both tests at any null rate, labelled with their level. | §2.3, §2.9 | WI-1 | U: `ConfidenceBoundsTest` |
| R-S6 | Cap calibration matching every row of the §2.4 table (`i` = 1–5, 10, 50 and 100) at the minimum pass rate `p_min = 0.95`, and the all-pass minimums 22, 45, 93, 157 and 474 at `p_min` = 0.80, 0.90, 0.95, 0.97 and 0.99. Calibration failure blocks admission. | §2.1, §2.4 | WI-1, WI-3, WI-60 | U: `CapCalibrationTest` (the only test that runs calibration); `AdmissionTest` |
| R-S7 | Comparison decisions: proven better when `E↑` at null `UI(k)` exceeds `K/β_i`, and proven worse when `E↓` at null `LI(k)` exceeds `K/β_i`. `UI` is rounded up and `LI` down to 4 decimal places. The displayed difference bound `LC − UI` is positive when certified and deficient. | §2.1, §2.3 | WI-1 | U: `ComparisonTest` (both sides of each threshold; rounding direction) |
| R-S8 | Wilson exploratory interval and one-sided Clopper–Pearson upper bound. | §2.7, §3.3 | WI-1 | U: `ExploratoryBoundsTest` |
| R-S9 | The certification index is assigned at commit and is permanent. Only a commit spends allowance. Edits, failed attempts, revisions, restarts and cancel-then-resume never reset the index; only a new campaign does. | §2.2, §7 | WI-3, WI-4 | U: `TransitionsTest`, `FileCampaignStoreTest` |
| R-S10 | The minimum pass rate `p_min` is proposed once per campaign, 0.95 by default, between 0.80 and 0.99 with at most two decimal places, confirmed with the contract, recorded with each certification; a change is a revision. | §2.1, §5 | WI-60 | U: `ProposalSchemaTest`, `CampaignTransitionsTest`, `CertificationRecordTest` |
| R-S11 | Control comparison per assertion: `E_ctl↑` and `E_ctl↓` over `Z = (X − Y + 1)/2` with `m = 0.475` and `f` = 0.25, 0.5, 0.75, 0.9, weights ¼, exact rationals, deciding at `1/α_i`; equality never decides. | §2.3 | WI-65 | U: `PairedComparisonTest` |

### 3.2 Development

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-D1 | Commit gate, in every development cell: at least 100 trials, for the minimum pass rate `p_min` a failure rate of at most `(1 − p_min)/5`, latest two batches of 50 each at most `2·(1 − p_min)/5`, no unresolved defect, baseline met. | §2.7, D76 | WI-3, WI-32, WI-60, WI-80 | U: `CommitGateTest` (each boundary on both sides, at 0.95 and at another `p_min`) |
| R-D2 | Edit-or-continue: pause admissions, up to 3 reruns, edit if reproduced (a rerun fails, or another adopted trial of the same test case in the same cell fails before the reruns end) or more than 2 failures, otherwise continue with the window reset to 1. | §2.7 | WI-3, WI-9 | U: `EditOrContinueTest` (including a repeated failure of a test case awaiting reruns); U: `DriverTest` (pause and resume) |
| R-D3 | Prompt author attempts are validated, run twice on triggering test cases and once on the regression set, and promoted by the §2.7 rule: the attempt fixes at least one triggering test case (every run passes and the streak is below 10% likely for the working candidate's smoothed pass rate there, with runs extended from 2 until the streak is unlikely) and fails no regression test case that the working candidate passes. The regression set holds development test cases that failed in representative development trials only. Targeted runs never count. | §2.7, D33, D35, D89 | WI-3, WI-9, WI-26, WI-33, WI-110 | U: `PromotionTest`; U: `DriverTest` |
| R-D9 | Prompt authors, triage and diagnosis receive development test cases and outcomes only; from a formal failure they receive its cell, prompt test and failed requirements, never a held-out test case, reply or judge rationale. | §3.1, §6.3 | WI-9, WI-33 | U: `DriverTest` (prompt author input during a formal attempt and after a failed one) |
| R-D4 | A new candidate starts new counts; earlier outcomes are retained for diagnosis. | §2.7 | WI-3 | U: `CampaignTransitionsTest` |
| R-D5 | Review checkpoint at 200 unresolved trials, or, for the minimum pass rate `p_min`, more than `200·(1 − p_min)/5` failures in a development cell: stop the prompt test, offer choices, record the next review point. | §2.7 | WI-3, WI-60, WI-64 | U: `ReviewCheckpointTest` |
| R-D6 | Rounds: a round gives each failing prompt test a diagnosis and up to 2 prompt author attempts. A complete round without promotion keeps the working candidate and resumes its representative trials; development has no plateau and ends only at the commit gate, a review checkpoint or a user stop. | §2.7 | WI-3, WI-46, WI-56 | U: `RoundEndTest`, `DriverTest` |
| R-D7 | Incumbent baseline trials of held-out test cases: 50 in every certification-scope cell, plus 50 in a scope cell below the minimum pass rate `p_min` not yet proven deficient. The incumbent's development trials never count toward it. A commit requires the baseline. The baseline is reused while measurement identities stay comparable; otherwise the incumbent is measured again as a new baseline with its own share. | §2.5 | WI-3, WI-32, WI-33, WI-60 | U: `BaselineTest`, `AdmissionTest`, `SchedulerTest` |
| R-D8 | Development results are labelled `development estimate` and `not certified`. | §2.7, §2.9 | WI-12 | U: `StatusReportTest` |
| R-D10 | Prompt author manifest: the failures grouped into kinds, each kind with its failing test cases, its classification (routing, guidance, test defect, not guidance, conflict) and, for routing and guidance, the hunks that address it, or for a test defect, the test design rule or expected outcome at fault; for each changed hunk, the cited failing test cases and its analogous and dissimilar cases. An attempt with an uncovered hunk, a failure outside every kind, a routing or guidance kind without a hunk, a test defect kind without its rule or outcome, or an unknown citation is rejected and retained; an attempt that edits nothing produces no candidate and its classifications reach the round's diagnosis and the review report. | §2.7, D25, D30, D58 | WI-18, WI-58 | U: `PromptAuthorManifestTest`, `DriverTest`, `StatusReportTest` |
| R-D11 | Minimality pass: delta debugging over the promoted attempt's hunks, each reduction run through the promotion runs and kept when it fixes every triggering test case that the attempt it reduces fixed and fails no regression test case that the working candidate passes, ending when no single hunk can be removed; its runs are targeted and never counted; the minimal attempt is promoted; the report names the removed hunks. | §2.7, D26 | WI-18 | U: `MinimalityTest` (removes an unneeded hunk, keeps a needed one, ends at single hunks), `DriverTest` |
| R-D12 | Concurrent attempts while weak: when any cell of a prompt test the round addresses has a development lower bound below 80% or no development outcome, the round makes 3 independent attempts for that prompt test from the same evidence; each runs the same promotion runs; the accepted attempt with the most passing promotion runs wins, ties to fewer changed lines, then the earlier attempt; only the winner is minimized and promoted; the others are retained; the status records each attempt's promotion results. | §2.7, D28 | WI-20 | U: `AttemptSelectionTest` (weak and not-weak rounds, winner, both tie-breaks, no accepted attempt), `DriverTest` trace 20, `StatusReportTest` |
| R-D13 | A prompt author conflict report whose quotations the confirmed snapshot holds pauses the campaign with each resolution and `stop`; a report quoting only campaign edits is rejected; a chosen resolution becomes a revision that needs confirmation and starts a new lineage. | §2.7, §7, D32 | WI-25 | U: `PromptAuthorManifestTest`, `DriverTest`, `OptimizerCoreTest` |
| R-D14 | A prompt test's attempts follow the nine §2.7 approaches in order, each round continuing where that prompt test's previous round stopped and wrapping after the last: correct the instruction where it is stated (`AGENTS.md` and `CLAUDE.md` included), rewrite rather than add, add the missing guidance, remove what the failing replies copied, explain the consequence where the decision is made, offer one form of each action, put text to be copied last on its line, define before use and keep definitions in agreement, give the exact text rather than how to build it. The prompt author corrects an instruction in the file that states it and a CAT-shipped target at its module source; when CAT is a trial plugin it puts other new guidance in a project rule, with `paths` frontmatter when the guidance applies only to some files; otherwise it adds new guidance to `AGENTS.md` or `CLAUDE.md` only when it cannot otherwise reach the sessions that need it. | §2.7, §4.3, D30, D34, D59 | WI-26, WI-59, WI-119 | U: `PromptAuthorTest`, `DriverTest` |
| R-D15 | A promoted candidate not yet edited is reverted to its predecessor when its development 95% Wilson upper bound in a cell the promoting round addressed falls below the predecessor's development 95% Wilson lower bound there; the check runs before edit-or-continue, the predecessor's counts return, and the round counts as producing no promotable attempt. | §2.7, D35, D75 | WI-26, WI-79 | U: `RevertTest`, `DriverTest` |
| R-D16 | Each failed representative development trial has an impact that a triage session decides before the outcome is recorded: blocking (the reply leaves the user without the turn's correct, complete result) or self-recovering (the reply still delivers that result, and the failure cost only extra work); an unclassifiable failure or a third invalid triage output is blocking. A blocking failure discards every unadopted development trial of the working candidate, interrupting those that run, ends any diagnosis in progress, and starts a round without diagnostic reruns; a self-recovering failure takes the R-D2 path. The impact reaches the prompt author brief and changes no count or claim. | §2.6, §2.7, §3.1, D39 | WI-29 | U: `EditOrContinueTest`, `TriagePromptTest`, `DriverTest` (fail-fast trace), `FileCampaignStoreTest` |
| R-D17 | The proposal states each requirement's trigger or that it always applies; CAT refuses a proposal in which a requirement with a trigger has no test where that trigger is absent. Each manifest hunk names its dissimilar case as a contract test; CAT rejects an attempt that names one the contract lacks. | §2.7, §6.1, D40 | WI-30 | U: `ProposalTest`, `PromptAuthorManifestTest` |
| R-D18 | In Codex, the inventory holds only listed models of version 6 or later, the number after `gpt-` in the slug, and leaves out a slug without that form; CAT refuses a proposal in which a Codex agent TOML binds a model below version 6, naming the file and the model, and a Codex role profile below version 6. | §6.1, §6.6, D41 | WI-31 | U: `CodexProfileInventoryTest`, `ClaudeProfileInventoryTest`, the core's derivation test |
| R-D19 | Test revision: each prompt test with a test defect kind gets a contract author session that rewrites it under the test design rules or reports it sound; CAT refuses a revision whose requirements differ from the confirmed ones, gives the revised test new test cases in both parts, reruns its guard pilot and starts a new lineage; a running attempt ends `not certified: stopped for test revision` with the index and spent allowance kept; a sound report returns the kind to the next round as routing or guidance; a report that no test within the rules can prove a requirement, or that a requirement must change, pauses for the user's choice; a review checkpoint first runs one test revision when none has examined the test since it last changed. | §2.7, §7, D70 | WI-75 | U: `DriverTest`, `CampaignTransitionsTest`, `StatusReportTest` |

### 3.3 Formal certification

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-F1 | A commit freezes the candidate, `α_i`, `β_i`, `UI(k)`, `LI(k)`, bets, cap and allocation policy before the first formal draw. The formal stream starts only after the freeze. | §2.4, §6.3 | WI-3 | U: `CommitTest` |
| R-F2 | Per-cell certified, clearly-below and cap decisions map to attempt outcomes and to the actions in §2.8. A frozen cap is never raised and an ended attempt never reopens. | §2.4, §2.8 | WI-3 | U: `DecisionTableTest` (every row) |
| R-F3 | Certified and not worse leads to automatic delivery. Worse needs an explicit trade-off. Uncertified acceptance is explicit and labelled. | §2.8 | WI-3, WI-11 | U: `DecisionTableTest`; E: E1, E3 |
| R-F4 | Formal outcomes never revise the same candidate. An edit needs a new attempt. | §2.6 | WI-3 | U: `CommitTest` |
| R-F5 | An unavailable condition discovered after a commit ends the attempt, keeps outcomes and allowance, and survives restart. | §6.5 | WI-3 | U: `CampaignTransitionsTest` |
| R-F6 | Keep improvement: one certification attempt under the current contract shows at least one prompt test proven better, none proven worse, and every other prompt test certified or with at least 100 adopted outcomes. A not-certified certification attempt that meets this pauses with `keep improvement`, `optimize further` and `stop`. Qualifying attempts stay available after `optimize further` and at a later review checkpoint, plateau or stop. CAT ranks qualifying attempts only within one measurement identity: the best has the highest 95% Wilson lower bound on its weakest prompt test, with ties going to more prompt tests proven better and then to the more recent attempt. It offers the best attempt of each identity as its own choice, the current identity first and each earlier one labelled historical with its differing conditions named, and never ranks attempts across identities. Keeping is always explicit, ends optimization, is labelled `improved, not certified` and delivers the chosen attempt's frozen snapshot. | §2.8, §2.10 | WI-3, WI-9, WI-11, WI-12 | U: `KeepImprovementTest` (each condition on both sides; the ranking and each tie-break; an earlier-contract attempt excluded; attempts under two measurement identities offered separately and never ranked together), `DecisionTableTest`, `DriverTest` (traces 12–14), `DeliveryTest`, `StatusReportTest` |
| R-F7 | Control trials: run on the control source, only when `p_min` is below 0.95 and only in a cell whose minimum-pass-rate part certified; the `j`-th pairs with the cell's `j`-th candidate formal trial in assignment order and copies its test case; extra formal trials run to complete pairs up to the cell's cap; a cell certifies only when every assertion's comparison passes; `E_ctl↓` above `1/α_i` ends the attempt `not certified: below control`. | §2.3, §2.8, §3.3, D74 | WI-65, WI-78 | U: `SchedulerTest`, `DecisionTableTest`, `DriverTest`, `StatusReportTest` |
| R-F8 | Incumbent trials: run the incumbent, only when `p_min` is below 0.95, only in a cell whose minimum-pass-rate part certified and only when the candidate is not the incumbent; the `j`-th pairs with the cell's `j`-th candidate formal trial in assignment order and copies its test case; a cell certifies only when every assertion's control and incumbent comparisons pass; `E_inc↓` above `1/α_i` ends the attempt `not certified: below incumbent`. | §2.1, §2.3, §2.8, §3.3, D73, D74 | WI-77, WI-78 | U: `PairedComparisonTest`, `SchedulerTest`, `DecisionTableTest`, `DriverTest`, `StatusReportTest`, `CertificationRecordTest` |
| R-F9 | Implied comparisons: when `p_min ≥ 0.95`, a cell certifies as soon as its minimum-pass-rate part certifies, no control or incumbent trial and no extra formal trial for pairs is scheduled or counted as remaining work, and the certification record and report say that the comparisons follow from the minimum pass rate. | §2.1, §2.3, §2.5, §3.3, D74 | WI-78 | U: `DecisionTableTest`, `SchedulerTest`, `RemainingWorkTest`, `StatusReportTest`, `CertificationRecordTest`, `DriverTest` |

### 3.4 Execution

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-E1 | Logical-trial lifecycle with write-ahead ordering: assignment persisted before the session starts, outcome before adoption. | §3.2, §3.8 | WI-4, WI-9 | U: `FileCampaignStoreTest` (fault after every step); U: `DriverTest` |
| R-E2 | Infrastructure failures rerun the same logical trial; 3 failures pause the campaign; rate-limit rejections are excluded; a timeout is behaviour, not a retry. | §3.2, §2.6 | WI-9 | U: `DriverTest` |
| R-E3 | Allocation order: targeted work, development cells and coverage floor among them, Wilson lower bound, formal rule (the prompt test furthest from certifying), baseline trials, with the stated tie-breaks. | §3.3 | WI-3, WI-32 | U: `SchedulerTest` |
| R-E4 | Concurrency: risk ceiling, slow start, prompt test limit from the passes still required to certify, campaign ceiling (default 8, range 1–16), baseline trials on the remaining ceiling. | §3.3 | WI-3 | U: `ConcurrencyPolicyTest` (no failures: 1 at n=10 and 2 at n=11, 3 at n=22 and 4 at n=23, 7 at n=46 and 8 at n=47; 10 at 1 of 100; reset after a failure) |
| R-E5 | Adoption in assignment order; post-decision outcomes never change a decision. | §3.4 | WI-3, WI-9 | U: `AdoptionTest`; U: `DriverTest` (completions out of order) |
| R-E6 | Throttling at 80% utilization, holds only on rejection, rejection backoff of 1, 2, 4 … up to 30 minutes, per-trial records, no evasion features. | §3.5 | WI-3, WI-8 | U: `ThrottleTest`; E: rate-limit state in the per-trial records of E1 per harness |
| R-E7 | Authentication warning for a subscription login, including the minimum session count. | §3.5 | WI-8, WI-10 | U: `ProposalTest`; E: E1 login classification per harness |
| R-E8 | Shared harness capacity is an injected per-harness instance, acquired by both runner CLIs and per optimizer session, with `tryAcquire` and an injected pause. | §3.6 | WI-5 | U: `HarnessCapacityTest`; E: E8 |
| R-E9 | Slices: status kinds, admission cutoff from the 90th-percentile duration or the time limit, 1-minute grace, cancellation of the remainder, `busy` for a second driver. | §3.7 | WI-9 | U: `SliceTest` |
| R-E10 | Interrupts: the Codex rollout watcher stops within 15 seconds; the Claude transcript backstop; Claude kill recovery. | §3.8 | WI-8, WI-9 | U: `RolloutInterruptSignalTest`, `TranscriptInterruptSignalTest`, `DriverTest`; E: E2 per harness |
| R-E11 | Reconciliation runs first in every `run`: adopts recorded outcomes, reruns started trials that have no outcome, checks the worktree, and terminates the recorded processes of runs that an earlier driver left running, under the run root and the held-out run root, never those of a live driver. | §3.8 | WI-9, WI-39 | U: `ReconciliationTest`, `LeftoverRunsTest` |
| R-E12 | Minimum remaining trials by the §3.9 formulas for the development and formal phases, the development minimum summing over every development cell, inside the certification scope or not; token statistics; no totals or time estimates. | §3.9 | WI-12, WI-44 | U: `RemainingWorkTest` |
| R-E19 | Development cells: representative development trials and prompt author rounds only in a prompt test's development cells (its weakest cell, plus every cell where a certification attempt of the lineage adopted a failure); the coverage floor among them. | §3.3, §6.6, D27 | WI-32 | U: `SchedulerTest` (only development cells sampled, the floor among them, a formal failure adding a cell), `DriverTest` trace 19 |
| R-E18 | The status compares the working candidate with the incumbent on the size (bytes and words) of the target prompt file alone, not of derived sources, project rules or the rest of the prompt system, and on median input tokens per trial in each cell where both have outcomes. | §3.9, D11, G5 | WI-18 | U: `StatusReportTest` |
| R-E13 | Every nested role runs through the runners. Judge and contract author output is schema-validated, and invalid output is an infrastructure failure. The host agent never prompt authors or judges. | §3.1 | WI-8, WI-10, WI-95 | U: `JudgeVerdictTest`, `ProposalTest`, `ClaudeStreamingProtocolTest`; E: E1 runs trial, judge, prompt author and contract author sessions per harness |
| R-E14 | Child subagents and child worktrees stay within one logical trial; reconciliation before judging; observation limits. | §3.2 | WI-8 | E: E5 child scenarios per harness (premise P7) |
| R-E15 | The Claude adapter classifies an interrupt it requested as an interruption (H10). | §11 | WI-8 | U: `ClaudeSessionsTest`; E: E2 in Claude |
| R-E16 | The observed model comes from harness events, never the alias. | §5 | WI-8 | U: event parsing tests per harness; E: E1 observed model per harness |
| R-E17 | A trial session starts under its cell's profile and the contract author, judge and prompt author sessions under the role profile (the `optimize-xhigh` binding); each runner passes the model and effort to its harness; the observed profile is recorded per cell and decides comparability. | §3.1, §5, §6.6 | WI-16 | U: `ClaudeSessionsTest`, `CodexSessionsTest` (requested profile reaches the harness arguments), `MeasurementIdentityTest`; E: E1 per harness |
| R-E20 | The contract author, judge and prompt author sessions run without CAT's plugin in both harnesses; trial sessions install exactly the trial plugins that the prompt test configuration lists, CAT only when it is listed (R-W12). The prompt author brief carries the complete text of its six role rules and the contract author brief the contract-design rule, read from the installed artifact with lazy-load paths naming its files; the judge brief carries none. | §3.1, D37, D78 | WI-27, WI-81 | U: runner session tests per harness, `PromptAuthorTest`, contract author prompt test |
| R-E21 | The trial record holds per turn the tool calls with input, exit status when reported and complete output, child activity and hook status, then the trial's file changes against the trial's initial project, without streaming fragments, telemetry or injected session-start text, from each harness's events; the built-in tools and the judge both read it. | §6.4, D36 | WI-28, WI-63, WI-74 | U: trial record tests on recorded events per harness, `JudgePromptTest` |
| R-E22 | Weakest cell: within the certification scope (for weakest cell only, within the latest versions), the lowest effort of the least capable model by CAT's shipped capability ranking (families ranked from the vendor's published model documentation, with its address and reading date; within a family, older versions below newer), with no trial; a prompt test with one cell uses it; the user may correct or extend the ranking in revision feedback; CAT refuses to confirm while an inventory model's family is unranked. | §6.6, D27 | WI-32 | U: `CapabilityRankingTest` (resource order for both harnesses, version order within a family, an unranked family), `CellTierTest` (weakest cell per scope, one cell, a corrected ranking) |
| R-E23 | Formal cell order: within a prompt test, formal trials go only to open cells, its development cells and each other scope cell whose development cells and lower cells (model ranked no higher and effort no higher, by the capability ranking) are all certified; among open cells, fewest formal outcomes first. | §3.3, D45 | WI-35 | U: `SchedulerTest` (no formal trial in a stronger cell while a development cell is undecided; incomparable cells open together; a cell opens once every cell below it is certified; a weakest-only scope is unaffected) |
| R-E24 | Held-out content unreadable: the snapshot, the proposal and the trial records live in the campaign's held-out directory, and formal and baseline trials and judges run under a held-out run root; development trials, targeted runs, triage, diagnosis, prompt authors and the contract author run with their harness sandbox denying reads of both directories for commands and file tools, in both harnesses; formal and baseline trials and judges deny nothing; a host with the sandbox off is refused. | §4.1, §6.3, D46 | WI-36, WI-37, WI-40 | U: `ClaudeSessionsTest`, `CodexSessionsTest` (the deny list per role), store layout and format tests, the refusal; V: one prompt author session per harness asked to print the snapshot by its absolute path gets no content, including after the snapshot is rewritten, and a development trial still passes |

### 3.5 Workspace

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-W1 | Storage under `.cat/sessions/<session-id>/optimizer/<id>/` of the session that starts the campaign, with both run roots under its `optimizer-runs/`; loading reads only the snapshot; `info/exclude` added and reported when needed. | §4.1, D17 | WI-4, WI-7, WI-84, WI-87 | U: `FileCampaignStoreTest`, `ForkTest`, `OptimizerCoreTest` (the notice) |
| R-W2 | Clean-base refusal before anything is created. The fork records paths, bytes, modes and links, copies ignored and external participants, and creates a detached optimization worktree. | §4.2 | WI-7 | U: `ForkTest` (real Git, once per operation) |
| R-W3 | Candidate identity is the hash of the overlay and the fork. | §4.3 | WI-6 | U: `OverlayTest` |
| R-W4 | Edit authority rules, including rejection of ambiguous dual-path edits, plus diagnosis warnings. | §4.3 | WI-6 | U: `AuthorityTest` |
| R-W5 | Staged source: tracked files, recorded participants, fixtures and overlay; links dereferenced; cycles and missing targets block confirmation; `.cat/work/` excluded; caches deleted when unused. | §4.4 | WI-7 | U: `StagedSourceTest`; the runner's project copy accepts the staged source in both harnesses |
| R-W6 | Symbolic-link report contents, and the limitation recorded on results. | §4.5 | WI-7, WI-12 | U: `LinkReportTest`, `StatusReportTest` |
| R-W7 | Inventory validation of prompt author attempts; unowned-change detection at every `run`, before promotion and before delivery. | §4.6 | WI-6, WI-9 | U: `InventoryDiffTest`, `DriverTest` |
| R-W8 | Delivery: verify the optimization worktree and materialize a selected candidate that is not the working candidate, preflight, journal, preimage checks, resume applying each change at most once, final verification, historical result after a base-only prompt edit. CAT never commits. | §1, §4.7 | WI-6, WI-11 | U: `DeliveryPlanTest`, `DeliveryTest` (fault between writes, concurrent edit, no commit created) |
| R-W9 | Cleanup: once a certified candidate's delivery completes and is verified, or the user keeps the incumbent instead of a regressing certified candidate and the prompt-test delivery that follows completes and is verified, CAT removes the optimization worktree, the campaign's runs in both run roots and then the campaign directory; an interrupted removal completes on the next command for the target; every other campaign keeps its files. | §4.1, §4.7 | WI-43 | U: `CleanupTest` (each ending, fault before each step); E: E1 |
| R-W10 | Control source: the incumbent's staged source with the target prompt file emptied to zero bytes, each dependent that the contract author lists removed (a file deleted, a hook registration unregistered, or an exact span deleted), and every other prompt file unchanged, the same for every candidate; the proposal lists the target and each dependent with the instruction it serves, and CAT refuses an entry that names the target, a path outside the prompt system and the project-built trial plugins, or a span that does not occur exactly once. | §4.4, §6.1, D71, D72, D78 | WI-65, WI-75, WI-76, WI-81, WI-91 | U: `StagedSourceTest`, `ControlSourceTest`, `ProposalTest`, `OptimizerCoreTest` |
| R-W11 | Delivery writes each confirmed prompt test's values file with every test case of the campaign, and the scenario and workspace of each created or edited prompt test, on a certified delivery and when the user keeps the incumbent; staging excludes the confirmed prompt test directories; a stopped campaign writes none. | §4.4, §4.7, D60 | WI-67 | U: `DeliveryTest`, `StagedSourceTest`, `DeliveryPlanTest`, `DriverTest`, `CleanupTest`, `OptimizerCoreTest`, `ScenarioTest`, `DeliveredTestTest`, `ProposalSchemaTest` |
| R-W12 | Trial plugins: `prompt.json` in the target's test directory lists, per harness, the plugins that trials install, each by name with an optional `build` (`command`, `output`); `start` refuses, before creating anything, a target without it or without a key for its harness, naming the file, and a listed plugin without `build` whose name matches a plugin manifest tracked in the project; `start` runs each build command in the optimization worktree and stops, with the exit status and the end of the output, on a failed command, a missing manifest or a different manifest name; a plugin the project does not build is copied from the user's single installed copy; each base is copied into campaign storage once; CAT maps prompt-system directories, or else single files, onto each project-built base by comparing files, lists a participating file that no mapping covers, warns on a mapped file that differs, and refuses a target that would not load in trials; each staged source, the control source included, has a copy of each project-built plugin with its mapped files written over the base; every trial installs exactly the listed plugins and reuses the user's approvals of their hooks; each plugin's name, base identity, build command and mappings are measurement conditions. | §3.1, §4.4, §5, §6.1, D69, D78 | WI-73, WI-81 | U: `PromptConfigurationTest`, `InstalledPluginsTest`, `PluginMappingTest`, `StagedSourceTest`, `ProposalTest`, `ProposalSchemaTest`, `MeasurementIdentityTest`, `OptimizerCoreTest`, `CodexRunnerTest`, `ClaudeRunnerTest`, `CodexSessionsTest`, `ClaudeSessionsTest`; E: WI-71's certification campaign |
| R-W13 | CAT session directories: one component, `SessionDirectory`, is the only code that names `.cat/sessions`, `.cat/work` or a session directory; it creates `.cat/sessions/<session-id>/` and its `temp/` (the session's `CAT_TEMP`) on first use and writes a marker naming the harness and its configuration directory, never overwriting one; the session ID is the hook input's `session_id` or the tool shell's `CLAUDE_CODE_SESSION_ID` or `CODEX_THREAD_ID`, in canonical UUID form; the injected rules name the directory that the agent's own commands resolve; SessionStart removes each other session directory whose marker is more than 24 hours old and whose transcript the marker's configuration directory no longer holds (a Codex transcript in `archived_sessions/` counts as held), never one without a marker; before that sweep, SessionStart moves each `.cat/work/<session-id>/` that holds a marker to `.cat/sessions/<session-id>/` with one rename, leaving it in place with a standard-error line when the new path exists or the rename fails; CAT neither writes nor deletes `.cat/work/temp`; the Codex PostToolUse hook deletes a `run-part-*` folder that `run-part` saved once its last part arrives uncut and no cut part awaits a re-read. | D79, D81 | WI-83, WI-87 | U: `SessionDirectoryTest`, `SessionSweepTest`, `SessionDirectoryAuditTest`, `ClaudeSessionStartHookTest`, `CodexSessionStartHookTest`, `CodexRunPartTest`, `HarnessRunnerOptionsTest`, `CodexRunnerCliTest`, `ToolScopeTest`, `CodexPostToolUseHookTest`; E: WI-83's and WI-87's exit checks in both harnesses |
| R-W14 | Passing trials' project copies: once a trial's passing verdict is recorded, the driver deletes its effects and its initial project; a guard-pilot control trial loses both once its judge passes every assertion; a failed trial, a failed run and a trial whose verdict is not recorded keep them; a failed deletion ends the pilot once its running sessions end. | §4.4, D51 | WI-88 | U: `DriverTest`, `GuardPilotTest` |
| R-W15 | Trial workspace: each candidate's base source holds only the prompt system (every file at or below an in-project prompt source, every instruction file and harness project directory file, the in-project loader configuration, `.cat/rules/`, every file that a plugin mapping covers and every in-project dependent), with the overlay applied and links dereferenced; a trial's workspace adds its prompt test's `workspace/` at the workspace root, with each link whose target is another entry of `workspace/` kept as a link and each other link replaced by a copy of its target, which CAT reads when it checks the proposal and records in the contract, with placeholders replaced in a target inside a test root; control and candidate trials of a test receive the same workspace files; no trial receives a prompt test directory, a shared file inside a test root or the prompt test configuration; plugin copies and prompt measurement use the base source; the contract author and test revision sessions keep the whole fork; CAT refuses an unknown scenario section, and a link that is absolute, missing, outside the project, cyclic, holds itself, reaches a prompt test directory, copies a prompt file, or that Git records where the checkout holds another kind of file; a draft resolves a link at the prompt test's project path, draft first; delivery replaces the link of a test whose shared file changed with a copy; a stored guard pilot applies only to the base selection it ran on. | §4.4, §6.3, D82 | WI-89 | U: `StagedSourceTest`, `TrialSelectionTest`, `PromptTestFilesTest`, `ScenarioTest`, `ProposalTest`, `ProposalSchemaTest`, `DeliveryTest`, `GuardPilotTest`, `DriverTest`; E: WI-71's guard pilots |
| R-W16 | Without a prompt test configuration, `start`'s refusal lists every plugin installed in the harness home with its version and every plugin manifest tracked in the project; the skill asks which ones the prompt's users have, writes `prompt.json` from the answer, shows it and runs `start` again. When CAT is a trial plugin, `.cat/rules/` is an in-project prompt source that a candidate may create rules in; a trial plugin is CAT when its manifest's `repository` matches CAT's after ignoring scheme and host case, a trailing `/` and a trailing `.git`, whatever its name or marketplace, and the campaign records each trial plugin's repository, a missing one meaning not CAT. Only `https://github.com/holdmyspot-com/cat` is CAT's repository; the former `https://github.com/cowwoc/cat`, which CAT 1.0.x and 1.1 manifests state, is not (user ruling, 2026-10-22). | §4.3, §4.4, §8, D30, D92, D93 | WI-119 | U: `OptimizerCoreTest`, authority test, `TrialPluginTest`, `PluginInfoTest`, `FileCampaignStoreTest`, `RunnerSessionsTest`; E: installed skill |

### 3.6 Measurement, contract and lifecycle

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-M1 | Measurement identity is recorded for every trial and result. | §5 | WI-3, WI-8, WI-104 | U: `MeasurementIdentityTest`, `StreamJsonPromptSourcesTest` |
| R-M2 | Each listed material change that its identity has not explicitly accepted makes a certification historical without deleting it; a runner or CAT version change with an unchanged session environment version does not; a parameter change makes it historical only when its claim does not imply the new one; an unaccepted judge version makes it historical. | §5 | WI-3, WI-41, WI-42, WI-125 | U: `ApplicabilityTest` (one case per change kind), the session environment tests of WI-41 |
| R-M3 | A `MEASUREMENT_CHANGED` pause before the next call, offering restore, new identity, keep identity and stop. Keep identity records accepted observed values and retains evidence and attempts; it is unavailable for loader changes (user ruling, 2026-10-24). A loader-configuration change offers only restoring it or starting a new campaign. | §5 | WI-9 | U: `DriverTest` |
| R-M4 | Comparability by exact equality or values explicitly accepted within the identity, campaign-wide and per cell; a condition only one side records gives `INDETERMINATE` and an unavailable comparison; a condition neither records is unobserved, does not prevent comparison and is a limitation; each differing or missing condition is named in words. | §5 | WI-3 | U: `ComparabilityTest`, `DecisionTableTest`, `KeepImprovementTest`, `OptimizationEvidenceTest`, `StatusReportTest` |
| R-M5 | A run observed under conditions that differ from its trial's measurement identity and have not been explicitly accepted yields no outcome, is kept as an observation, does not count toward the infrastructure-failure limit, and reruns the same test case after a restore. A new measurement identity during a certification attempt ends it `not certified: testing environment changed`; it never resumes, its allowance stays spent and the next attempt takes the next index. A restore or explicit keep identity lets the attempt continue; excluded verdicts still require a rerun. | §5, §7 | WI-3, WI-9 | U: `TransitionsTest`, `DriverTest` (trace 14) |
| R-M6 | A certified delivery appends its certification record to `certifications.json` beside `prompt.json` in the target's test directory, keeping every earlier record, and staging leaves the file out; each record holds the index, scope, per-cell counts and control pair counts, the minimum pass rate `p_min`, the control margin, `α_i` and every measurement condition, with prompt sources and contract by hash, and no trial results; `status` for a target without a resumable campaign and a new campaign's proposal report the latest record of each harness current or historical, naming each changed condition and, as unchecked, each condition only a session observes and, from the other harness, each harness-specific condition, and list earlier records as history. | §4.7, §5, D51 | WI-43, WI-85 | U: `CertificationRecordTest`, `CertificationHistoryTest`, `DeliveryTest`, `ApplicabilityTest`, `StagedSourceTest` |
| R-M7 | Every certification report (terminal outcome, delivery report, `status`) shows `Certified for the following environment:` with the harness and its version, the trial plugins with their manifest versions or `none`, then the model and reasoning per cell, with no provider (user ruling, 2026-10-22), the prompt files loaded, one `Harness settings` line naming the project's settings files that existed in trials (from the harness's fixed list), or `defaults` when there are none, followed by `with your connection settings and tool restrictions carried over`, the trial setup as the CAT release that introduced the session environment version, an unchecked one marked, and after the lines the host machine limitation of R-M8; a trial plugin's manifest version is a measurement condition. CAT's own version has two parts, such as `1.0`, in `Plugins` and `Trial setup`; a third-party plugin's version is the one its manifest states. `status` of a finished certified campaign that cleanup has not removed shows the same block, drawn from the delivered attempt's record in the target's certification history; without such a record it shows none (user rulings, 2026-10-22). | §5, D92, D94, D95 | WI-119 | U: report test, certification history test, `OptimizerCoreTest`; a stored campaign or history that carries the retired `model_provider` condition loads without it |
| R-M8 | CAT neither records nor controls the host machine's programs and outside sources, such as MCP servers, websites and web search: no measurement condition names them, so they never pause a campaign, prevent comparison or make a certification historical (user ruling, 2026-10-22). The certified environment block ends, after its lines, with the plain-words limitation that trials ran with this machine's programs and outside sources, which CAT neither records nor controls, and the recommendation to run the campaign, and the sessions that later use the prompt, in the same container image to limit changes to them; `status` states the same limitation among its fixed limitations, except when it shows the certified environment block, which already states it (user ruling, 2026-10-22: once only). A stored campaign or certification history that carries the retired `host_tools` or `external_sources` condition loads with it dropped, and a pending measurement change that names only retired conditions is dropped. | §5, D95 | WI-119 | U: `CertifiedEnvironmentTest`, `StatusReportTest`, `FileCampaignStoreTest`, `CertificationHistoryTest`, `CommandsTest`, `OptimizerCoreTest` |
| R-C1 | The proposal lists every §6.1 item, without aggregate summaries, including the minimum pass rate `p_min`, the requirement-to-assertion mapping, the capability ranking with its source, each certification scope's cells, weakest cells and minimum session count, each prompt test's scenario status and test cases with the distinct expected outcomes per part, the emptied control paths and the pilot results. | §6.1 | WI-2, WI-10, WI-32, WI-33, WI-62, WI-65, WI-66, WI-68 | U: `ProposalTest`; E: E1 contract author per harness |
| R-C2 | Feedback regenerates a complete proposal; nothing but the guard pilot runs before confirmation. | §6.1 | WI-9, WI-10, WI-66 | U: `DriverTest`, `ProposalTest` (regeneration) |
| R-C3 | Each prompt test is its own claim; within a part its test cases are drawn uniformly, frozen at confirmation; allocation never changes the draw. | §6.2 | WI-2, WI-64 | U: `DistributionTest` |
| R-C4 | Test case validation; separate durable seeded streams for development, formal and baseline draws, each confined to its part, and control trials that draw nothing; targeted work marked before execution; formal draws after the freeze; repeated draws of a test case within a part allowed, each another trial of that test case. | §6.3 | WI-2, WI-33, WI-62, WI-65 | U: `GeneratorTest` (`repeatedDrawsAreAllowed`), `DrawStreamTest` |
| R-C5 | A frozen judge profile under the role profile; the judge decides every assertion without a `tool:` line, including `expected_outcome`, and every assertion whose built-in tool failed under `on_tool_failure: judge`, per assertion with a rationale and a behavior, and sees built-in tool results as evidence; it cannot override one that decided its assertion. | §6.4, §3.1 | WI-2, WI-10, WI-17, WI-63 | U: `JudgeVerdictTest`, `OptimizerCoreTest` (a proposal frozen with an old judge version is refused); E: E1 judge per harness |
| R-C8 | Every prompt test has the `expected_outcome` assertion that CAT adds, a guard exactly when every other assertion is one; a scenario may not declare it. | §6.4, D62 | WI-63 | U: `ProposalTest`, `ScenarioTest` |
| R-C9 | Built-in tools: the closed `tool:` set of §6.4, with its argument syntax and placeholders; a check's verdict is final unless its `on_tool_failure` is `judge`, when a failure leaves the assertion to the judge; a trial passes when every assertion passes; no judge session runs after a failed check under `on_tool_failure: fail`; a failed check's record and behavior reach the prompt author; a check whose evidence the harness cannot supply is refused in the proposal. | §6.4, D63, D68 | WI-17, WI-63, WI-72 | U: `BuiltInToolTest` (each check on both sides), `DriverTest`; E: E7 |
| R-C10 | Trial profiles: the inventory per harness with its source; bindings from Claude skill and subagent frontmatter and Codex agent TOML only, partial bindings fixing one dimension, `inherit` binding nothing; the profile set per prompt test from the requirements mapped to its assertions, and the resulting cells; a user restriction narrows the set; the proposal lists inventory, bindings, cells and their minimum session count; confirmation freezes the set. | §6.6, §2.1, D22 | WI-16, WI-64 | U: `ProfileSetTest`, `ProposalTest`, per-harness inventory and binding tests over recorded catalog output; E: E1 proposal per harness |
| R-C11 | Cells are the statistical unit: one prompt test under one profile; every §2 and §3 rule applies per cell, and `K` counts the cells of the certification scope. | §2.1 | WI-16, WI-32, WI-64 | U: `SchedulerTest`, `CommitGateTest`, `CommitTest` (a prompt test with two profiles gives two cells with separate counts, gates and decisions) |
| R-C12 | A turn's reply is every assistant message of the turn; built-in tools of reply text, the trial record, the judge's evidence and the prompt author's failure records all use it. | §1.2, §6.4, D29 | WI-22, WI-63 | U: per-harness adapter tests over recorded events, `BuiltInToolTest`, `JudgeVerdictTest`, `DriverTest`; E: E1 per harness |
| R-C13 | A proposal lists every conflict between requirements with its evidence, and `confirm` is refused while one is listed. | §6.1, D31 | WI-24 | U: `ProposalTest`, `OptimizerCoreTest` |
| R-C14 | Each proposal conflict offers at least two resolutions and a recommended one with its reason; the agent asks the user to resolve every conflict before offering confirmation. | §6.1, §8, D31 | WI-25 | U: `ProposalTest`; E: E1 per harness |
| R-C15 | Certification scope: weakest cell only unless the user chooses latest model versions (every cell of each family's latest version) or a set of inventory cells mapped from the user's own description and passed to `revise`; `confirm` without a scope confirms the weakest cell only; CAT refuses a described cell outside the inventory; the choice is frozen with the contract, a change is a revision, and formal and baseline trials run in the scope's cells only. | §6.1, §6.6, D42 | WI-32 | U: `OptimizerCoreTest` (confirm without a scope confirms the weakest cell; each choice), `CommandsTest` (`confirm <campaign>` alone), `ProposalTest` (a described scope, a cell outside the inventory), `SchedulerTest` (formal cells per scope), `ContractStoreTest` (round trip) |
| R-C16 | Split: CAT draws a salt at the first validated proposal and keeps it; each test case's part is a salted SHA-256 of its normalized expected outcome, probability ½; committed test cases and new ones sharing a committed outcome are development; a prompt test with fewer than 2 distinct normalized outcomes in either part is refused. | §6.3, D61 | WI-33, WI-62 | U: `SplitTest`, `ProposalTest`, `ContractTest` |
| R-C17 | After an attempt ends with formal failures, a contract author session receives each failed prompt test with only its development test cases, and each failure's cell, failed assertions and behaviors, and writes at least 4 new test cases per failed test; CAT refuses one whose normalized expected outcome equals a held-out one; the added test cases are development only and take half of that test's development draws; development counts restart; the held-out part, the contract and the measurement identity are unchanged; the user's report lists them and delivery writes them. | §6.3, D44, D61 | WI-34, WI-62 | U: `DriverTest` (no held-out test case or salt in the input; a shared held-out outcome refused; draws; counts restart) |
| R-C18 | Failure behavior: each requirement lists the behaviors through which it can fail; each failed built-in tool names the behavior it detects; the judge names one listed behavior or `other` per failed assertion; a formal failure summary carries its cell, test, failed assertions and behaviors. | §6.1, §6.3, D47 | WI-38, WI-63 | U: proposal and contract tests, `JudgeProtocolTest`, `FailureSummary` tests |
| R-C19 | Reproduction before tuning: after a failed attempt, each failure group (cell, test, failed requirement, behavior) gets contract author values that join development only after 2 failures with its behavior in at most 100 reproduction trials per round; values that do not reproduce are replaced; a round may repeat under a stress setting, whose reproduction adds that stressed profile as a development cell; after 4 rounds CAT pauses with the group recorded as not reproduced. | §6.3, §2.8, D47 | WI-38 | U: `DriverTest` (reproduce, replace, stress, pause), `SchedulerTest` (reproduction trials before authoring), values-prompt tests (tried values and counts, no replies) |
| R-C20 | Scenario format: a prompt test directory with `scenario.md` (`## Variables` table, `## Scenario` turns, `## Expected Behavior` with `description` and `time_limit_seconds`, `## Assertions` with `kind:`, optional `tool:` and optional `on_tool_failure:`) and optional `workspace/`; placeholders only for declared names; `expected_outcome` reserved; a recorded scenario without `## Expected Behavior` stays readable, and a proposal's prompt test needs it; every malformed form refused with its location. | §6.3, D60, D68, D77 | WI-61, WI-68, WI-72, WI-82 | U: `ScenarioTest` |
| R-C21 | Test cases and generators: a value is text or a generator call that CAT gives a seed and version once, different for identical calls; `log_lines` version 1 expands deterministically to UTF-8 lines with timestamps in 2025; a generated value only in workspace-file contents; workspace files copied with placeholders replaced in names and UTF-8 contents, invalid names and collisions refused. | §4.4, §6.3, D64 | WI-61, WI-92 | U: `TestCaseTest`, `LogLinesTest`, `FixturesTest`, `HarnessRunManagerTest` |
| R-C22 | Values file: `values.json` with `format_version` 1 and `test_cases` holding `values` and `expected_outcome`; unknown fields refused; streamed on read. | §6.3 | WI-61 | U: `ValuesFileTest` |
| R-C23 | Guard pilot: after validation and before presentation, about 20 control trials per prompt test in its weakest cell on development test cases; the proposal shows each assertion's rate and warns on a guard below the minimum pass rate `p_min`; a revision reruns it only for changed prompt tests and its brief carries the latest results; pilot trials enter no count and spend no allowance. | §6.1, D66 | WI-66, WI-68, WI-93 | U: `GuardPilotTest`, `ProposalTest`, `OptimizerCoreTest` |
| R-C24 | Goal and requirements: `start` takes the user's goal; the contract author receives the target prompt file, the goal and only the target's recorded regression scenarios; with a goal, requirements state the outcomes it asks for and the operations it implies the prompt must not harm, and each other prompt obligation is an extra requirement that the user keeps or drops; each prompt test lists the requirements it proves and keeps the test design rules; CAT refuses a requirement that no prompt test proves and reports every validation problem at once, the scenario citations included when the proposal cannot be parsed. | G2, §6.1, §6.3, §8, D70 | WI-75 | U: `ProposalTest`, `CommandsTest`, `CampaignStoreContract`, `OptimizerCoreTest`; E: WI-71's certification start |
| R-C25 | Single target: `start` refuses a target that is not a regular file, naming it; the contract author and test revision prompts name the one target prompt file; the prompt author may still edit every in-project prompt file. | §1.2, §4.3, §8, D71 | WI-75 | U: `OptimizerCoreTest`, `ProposalTest` |
| R-C26 | Prompt tests as files: before each contract author session CAT creates a new draft directory in the session's run directory holding a copy, at its test path, of every recorded prompt test of the target (a revision starts from the latest proposal's draft, a test revision from the prompt test it revises); the request names the draft directory and the proposal file; the proposal file holds only the requirements, each prompt test's draft directory with its risk rank, proven and trigger-absent requirements, applicability, the requirement each assertion checks and uncovered interactions, and the minimum pass rate, rubric, decision rule, dependents, conflicts and limitations; a new test case in a values file has no seed, and validation gives each generated value its seed and version; when the session ends CAT moves the draft into the held-out directory with the proposal; a session that reaches its time limit leaves its files for diagnosis and stores no proposal; the presented proposal and delivery read the prompt tests from the draft. | §4.1, §4.7, §6.1, §6.3, D77 | WI-82 | U: `DraftDirectoryTest`, `ProposalSchemaTest`, `ProposalTest`, `TestRevisionTest`, `DeliveryTest`, `OptimizerCoreTest`; E: WI-71's certification start |
| R-C27 | Contract tools: the contract author's request lists the files that load in trials with their rules, the trial plugins with each project-built one's mappings, and every hook registration of the prompt system and of the trial plugins with its event, matcher and command; the launcher's `contract check` applies every session-end validation to the proposal file and the draft directory as they stand, prints every problem at once with its file and line or JSON path, and prints each prompt test's number of distinct expected outcomes without splitting them into parts; `contract workspace` writes one test case's workspace files into a named directory and prints each file's path, size in bytes, line count and comparison with each trial profile's tool-output limit; the skill does not use either command. | §6.1, §8, D77 | WI-82 | U: `ContractCheckTest`, `ContractFixturesTest`, `CommandsTest`, `ProposalTest`, `CodexSessionsTest`, `ClaudeSessionsTest` |
| R-C28 | Files that trials receive: the proposal lists every file of the incumbent's base source; the derived prompt sources hold the in-project files that hooks and MCP servers name by path; `start` refuses a missing or external import and a hook that fails only in a trial's workspace, and the proposal lists each hook whose output differs there; a candidate that loads or runs a project file its trials do not receive contributes no candidate; the proposal lists the hooks, plugins and MCP servers that run only in the user's sessions. | §4.4, §6.1, D86, D87, D88 | WI-97, WI-98, WI-99, WI-100, WI-101, WI-102, WI-103, WI-107, WI-109 | U: `ClaudeSessionsTest`, `CodexSessionsTest`, `ProposalTest`, `DriverTest`, `OptimizerCoreTest`, `ClaudeConnectionSettingsTest`; E: E1 per harness |
| R-C6 | The four scope conditions produce their `certification unavailable` outcomes, before or after a commit. | §6.5 | WI-3, WI-10 | U: `CampaignTransitionsTest`; E: unsupported cases |
| R-C7 | Loading is observed (premise P9) or derived from documented rules, with the limit recorded. | §6.1 | WI-10, WI-96 | E: E1 loading record per harness |
| R-L1 | One resumable campaign per root and target, across every CAT session directory; binding to the starting harness and the owning session; another session, in either harness, shows the campaign and its owning session and refuses every other command for it, and a second campaign for its target, naming the session to resume, without any state change. | §4.1, §7, D17 | WI-3, WI-13, WI-84 | U: `CampaignRegistryTest`, `OptimizerCoreTest`; E: cross-harness, cross-session |
| R-L2 | Revision creates a new lineage; the incumbent is measured again as a new baseline; allowance continues. | §7 | WI-3 | U: `CampaignTransitionsTest` |
| R-L3 | Revision during formal testing waits for a safe checkpoint; a stopped attempt never resumes. | §7 | WI-3, WI-9 | U: `CampaignTransitionsTest`, `DriverTest` |
| R-L4 | Cancel and resume semantics; a new campaign gets a fresh budget. | §7 | WI-3 | U: `CampaignTransitionsTest` |
| R-L5 | Every listed state distinction is persisted and survives reload. | §7 | WI-4 | U: `FileCampaignStoreTest` |

### 3.7 Reporting, interface and testability

| ID | Requirement | Design | Owner | Proof |
|---|---|---|---|---|
| R-P1 | Four outputs in both phases, with unavailable values explained, including the per-prompt-test comparison state at `β_i / K`. Allocation counts are shown apart from benchmark weights, and a pooled adaptive pass fraction is never presented as the future pass rate. | §2.9 | WI-12 | U: `StatusReportTest` |
| R-P2 | Descriptive pass rates by concurrency level; detected dependence gives `invalid statistical assumptions`. | §2.6 | WI-12, WI-3 | U: `AssumptionCheckTest` |
| R-P3 | The outcome set and its labels match §1 exactly, including `improved, not certified`. | §1 | WI-12 | U: `StatusReportTest` |
| R-P4 | Claim wording names the frozen judge, benchmark, held-out part and conditions, and lists limitations; a weakest-only claim names each prompt test's profile and says no other profile was certified. | §2.1, §5, §6.6 | WI-12, WI-32, WI-94 | U: `StatusReportTest` |
| R-I1 | Skill wrappers in both harnesses share one body and follow the §8 loop. | §8 | WI-14 | E: both harnesses |
| R-I2 | Launcher commands and the JSON status object. | §8 | WI-13 | U: `CommandsTest` |
| R-I3 | The old skill is retired; `failure-regression-promotion.md` follows D13; `default-rules.md` routing is updated. Recorded regression scenarios live in the location `design.md` §8 names for their guidance, as prompt test directories in the format of §6.3 (`.cat/tests/` for project and user guidance, the module's `src/test/resources/` for guidance packaged as a module resource), and CAT refuses a proposal that omits a scenario of one of its prompt sources. | §6.1, §8, §13 | WI-14, WI-21, WI-23, WI-61, WI-69 | U: `RegressionScenariosTest` (location audit), the proposal refusal test; reference audit |
| R-I4 | `prompt-optimizer` launchers are registered for both harnesses in `DistributionMain`. | §8 | WI-13 | U: distribution tests; E: installed launcher runs |
| R-I5 | Agents locate CAT's installed files without shell variables the harness does not define: the session hooks render the plugin root into the injected skill-environment rule, and Java derives the harness home from `CODEX_HOME` or `CLAUDE_CONFIG_DIR` when set, otherwise from `HOME`. | §8 | WI-15 finding | U: session-start hook tests, `CodexGetHistoryTest`, `ClaudeGetHistoryTest`; E: every E-scenario's events show no exit-127 launcher call |
| R-I6 | Codex's `claude-runner` and Claude's `codex-runner` skills run a task in the other harness with CAT for that harness installed, through that harness's runner launcher. Each uses the other harness's CAT artifact of the same version and platform: the installed plugin when its version matches, otherwise `${HOME}/.cat/bundles/cat-<harness>-v<version>-<platform>/`; without either it downloads that harness's release archive and `SHA256SUMS.txt`, verifies the checksum and extracts the archive there, and a failed download, checksum or extraction leaves no copy and reports the failure. A download waits at most 30 seconds to connect, 60 seconds for the response and 15 minutes for the whole file; after a download, the copies of every other CAT version in that directory are deleted, and uninstalling CAT for either harness removes the directory (user rulings, 2026-10-22: timeouts at the implementer's judgment; old downloads both deleted after a download and removed at uninstall). Each check for a copy deletes the copies that no run has used for more than three calendar months (user ruling, 2026-10-23: age counts from the last use). A plugin ships only its own harness's classes and files (user rulings, 2026-10-22). | §8, D96 | WI-120 | U: download and verification tests against a local release directory, distribution tests (each archive's launchers and skills); E: each runner skill without the other harness's plugin installed |
| R-I7 | A run leaves no harness transcript on disk unless its caller passes `--session-transcripts`, which requires `--keep-run`; with it, the runner reports each session's native transcript path in a `session_transcript` record when the session starts, copies every native transcript of the run, subagents' included, into `artifacts/session-transcripts/` at the end of the run and lists them in the receipt. The optimizer passes the option for its trials and reads what a trial's model received from those copies (user rulings, 2026-10-23: "1a, 2a"). | §8, D97 | WI-121 | U: runner option, command and receipt tests in both harnesses; optimizer session tests |
| R-I8 | In both harnesses and on every platform, a session's commands run with the run's isolated home as their home, receive no harness credential in their environment, and cannot read any harness's login or configuration, in the run's home or the user's, or another run; one harness-neutral definition sets these limits and one conformance test checks both runners against it (user ruling, 2026-10-23). | §8, D98 | WI-122 | U: shared conformance test run against each runner; E: a command in a session of each harness cannot start a harness that reaches the provider |
| R-I9 | A caller's `--session-requests <count>` lets a session request up to that many sessions through the `request-session` launcher, passing inputs and results only as file paths through the run's `requests` directory; the launcher packs the inputs into one archive inside the session's sandbox, and the runner opens only that archive. The runner starts each through the requested harness's runner with no more than the requester's permissions and none of its own requests, takes a capacity slot waiting at most 10 minutes, stops requested sessions when the run ends or its turn is interrupted, lists them in the receipt, and tells the session how to request one; the optimizer allows none (user ruling, 2026-10-23: option C, file references between sessions). | §8, D99, D101 | WI-123 | U: request validation (archive opened without links or extra hard links, unsafe entries refused), broker, capacity timeout, stop and receipt tests with fake harnesses; E: a Claude and a Codex session each test a plugin they wrote through a requested session |
| R-I10 | No model receives a `## Design Goals` section or an HTML comment other than a `cat:` directive, outside fenced code blocks and code spans, from any prompt file: not from either plugin artifact or a prompt-only repackaging (injected and lazily loaded rules, skill bodies and agent bodies), an optimizer role brief or an injected project rule; each prompt file's guidance achieves its design goals without them. | §8, D100 | WI-124 | U: `PluginArtifactBuilderTest`, `SourceOnlyContentTest`, `PromptTemplateTest`, `ProjectRuleLoaderTest`; R: a scan of both built artifacts and the guidance audit |
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
| P14 | Run the E1 deficient fixture (`optimizer/e2e/deficient`) through plain `codex exec` on one Codex model, `gpt-6.1-sol`, at efforts `low`, `medium`, `high` and `xhigh`, 20 trials each (80 trials, about 7M tokens, under 1% of a week). Score each reply against the fixture's motto requirement and its question. H0: the four efforts have the same pass rate. Test it with the 2×4 Fisher–Freeman–Halton exact test, two-sided, α = 0.05, fixed before the run (not lowest against highest observed, which picks the comparison after seeing the data). With 20 trials per effort only differences of roughly 40 percentage points or more are reliably detectable. If H0 is rejected, effort stays in the tests. A non-rejection is not evidence that the rates are equal; supporting fewer efforts would need an equivalence test with a stated margin and far more trials. If every effort is at 0% or 100%, the result is inconclusive (floor or ceiling), not "the same": rerun on the pilot's fixed variant before concluding (user request, 2026-10-01). | The profile-set default of `design.md` §6.6 |
| P15 | After the first real prompt file is optimized (E1's file or `write-clearly.md`), develop a fresh campaign on it with the profile set restricted to one cell: the weakest model, chosen as the lowest incumbent-baseline pass rate at each model's lowest effort, at its lowest effort (the §6.6 user control), until it passes the commit gate. Then freeze that candidate and run it, with no edits, in each benchmark cell: every other model's lowest and highest effort, and the weakest model's highest effort (the §6.6 active-set pattern; 15 cells with the Codex inventory). Score with the same contract checks and judge. Pre-stated test: for each benchmark cell, H0: its pass rate is below 95%, one-sided exact binomial, α = 0.05 split equally over the benchmark cells (with 15 cells, 112 passing trials and no failure reject H0 in a cell; about 1,700 trials, roughly 140M tokens at 85k per trial). Report the effort result (same model, higher effort) and the model result (stronger model) separately. A shortcut holds for that file only if H0 is rejected in every cell it covers and those cells show no failure kind absent in the development cell. Several files must agree before development policy changes (user requests, 2026-10-01). Development reaches the `design.md` §2.7 commit gate before the freeze, also when the trials run through a harness outside the optimizer. If the optimizer cannot run a frozen candidate without editing it, record that as a missing capability and run the trials through the harness with the same checks. | None: development samples only each category's weakest cell (`design.md` §6.6, user ruling, 2026-10-04); the result informs the certification scope the user chooses |

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
	- immutable `Contract`, `Category`, `PromptTest` (turn sequence, time limit, applicability, expected behaviour),
		`Variable`, `JudgeProfile`, `RiskRank` and `TrialFixtures`;
	- the proposal JSON schema and validation of contract author output;
	- `Generator`, which validates and draws test cases from a `DrawStream`;
	- `DrawStream`, a seeded `RandomGenerator` with a persisted seed and position, kept separately for development,
		formal and baseline draws.
- **Closes:** R-C3, R-C4, part of R-C1 (schema) and part of R-C5 (profile).
- **Tests:** `GeneratorTest` covers out-of-contract emission, a probability chi-square check with a fixed seed, and
	repeated draws of a test case. `DrawStreamTest` covers resume at the same position and independence from outcomes.
	`DistributionTest` covers balanced weights.
- **Exit:** the focused tests pass.

### WI-3 — Campaign model and policies (`optimizer.campaign`)

- **Contents:**
	- immutable `Campaign`, `Lineage`, `Candidate`, `CertificationAttempt`, `CategoryCounts (S, F, batches)`,
		`LogicalTrial` and `PendingDecision`;
	- `CampaignTransitions`, a pure function `(state, event) → state`, with events for confirmation, assignment,
		outcome, promotion, commit, decision, user choice (including keep improvement),
		revision, cancellation, measurement change and unowned change;
	- `CommitGate`, `EditOrContinue`, `Promotion`, `Plateau`, `ReviewCheckpoint`, `Baseline`, `DecisionTable`
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
		candidate, producing conflicts or an ordered change list of in-project changes.
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
	- `AuthoringCopies`: prepare the prompt author's source and capture its delta through `InventoryScanner`;
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

- **Prompts:** the contract author's prompt, the judge prompt and the prompt author's prompt. Each states its role's
	authority limits from `design.md` §3.1 and §4.3 and its output schema.
- **Driver handling:** proposal rendering, feedback regeneration and confirmation; loading observation or documented
	derivation (P9: hooks and Claude's skill inventory are observed, instruction files are derived, H21); the judge and
	contract author output extraction rule of `design.md` §3.1 (a bare or singly fenced JSON object, H23); the
	authentication and link warnings in the proposal; and the judge profile freeze.
- **Closes:** R-C1, R-C2 (rendering), R-C5, R-C6 (detection in proposals), R-C7, R-E7 (warning), R-E13 (schemas).
- **Tests:** `ProposalTest` uses fixture contract author outputs, valid and invalid, to check every required item and
	the rejection of aggregate-only summaries. E1 runs the contract author, judge and prompt author in both harnesses.
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
	- no commit created in the base worktree;
	- no file written outside the project.
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
	Update the test resources that cite the old skill (`check-assumptions/existing_route_before_new_wrapper/scenario.md`,
	`limitations-and-workarounds/verification.md`). Follow `contract-retirement.md`.
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
	`K` counts cells. Commit gate, baseline, commit, error budget, decisions, keep-improvement, the scheduler and the
	report are keyed by cell.
- **Sessions:** `SessionPort` takes the profile. `ClaudeSessions` passes `--model` and `--effort`; `CodexSessions`
	passes the model and reasoning effort to the bridge. The contract author, judge and prompt author run under the role
	profile (the `optimize-xhigh` binding per harness); `OptimizerCommandScope.JUDGE_MODEL = "harness-default"` is
	replaced. The observed profile is recorded per trial and compared per cell (R-E16, D21).
- **User control:** a revision may restrict the profile set; the proposal shows inventory, bindings, cells and the
	minimum session count.
- **Closes:** R-C10, R-C11, R-E17.
- **Tests:** `ProfileSetTest` (bound, partially bound and unbound files; `inherit`; a restriction), `ProposalTest`,
	`SchedulerTest`, `CommitGateTest`, `CommitTest`, `DecisionTableTest` and `StatusReportTest` with a two-cell
	category, both `*SessionsTest` classes for profile arguments, `MeasurementIdentityTest`. `DriverTest` trace 15.
- **Exit:** unit tests pass; the full build passes.

### WI-17 — Deterministic checks and the core-task requirement (user ruling, 2026-10-01)

- **Contract:** each requirement is deterministic or semantic. A deterministic check has a kind from a closed set,
	belongs to one test and one requirement, takes parameters that may name variable values, and carries a known-good
	and a known-bad control. CAT adds the core-task requirement (source CAT) to every test; it is semantic unless the
	test's expected result is mechanical.
- **Controls:** validated at confirmation and when the contract is loaded. A check that fails a control is discarded,
	its requirement becomes semantic, and the proposal says why.
- **Trial outcome:** checks run first on the trial evidence. A trial fails if any check fails; otherwise the judge
	decides the semantic requirements alone, under the role profile, and cannot override a check. No judge session runs
	when a check failed or no semantic requirement remains. A failed check's record reaches the prompt author brief.
- **Prompts:** the contract author's prompt marks each requirement and proposes checks with controls; the judge prompt
	receives only semantic requirements and the deterministic results as evidence.
- **Closes:** R-C5, R-C8, R-C9.
- **Tests:** `DeterministicCheckTest` (each kind; each control on both sides; variable values), `ProposalTest`
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

### WI-18 — Prompt author manifest, minimality pass and size report (user ruling, 2026-10-01)

- **Prompt author prompt:** classify each failure as routing, guidance or not guidance; make the smallest
	evidence-backed correction at the owning boundary, preferring to remove a contradiction over adding emphasis;
	generalize against analogous and dissimilar cases; emit the manifest.
- **Manifest:** extends the prompt author report (`optimizer/prompt-author/report.schema.json`) with each failure's
	classification and, per changed hunk, the cited failing test cases and the analogous and dissimilar cases. CAT rejects
	an attempt whose manifest omits a hunk or cites an unknown test case; the attempt is retained like an inventory
	violation. An attempt that edits nothing produces no candidate; its classifications go to the round's diagnosis and to
	the review-checkpoint or plateau report.
- **Minimality pass:** after the promotion runs pass, delta debugging over the attempt's hunks: start with two groups,
	revert one group, rerun the same promotion runs, keep the reduction if it passes the reduction rule (`design.md` §2.7), otherwise
	split finer down to single hunks. Its runs are targeted and never counted. The minimal attempt is promoted, and the
	report names the removed hunks.
- **Size report:** the status shows the size (bytes and words) of the working candidate's and the incumbent's target
	prompt file alone, with its staged bytes checked against the snapshot (user ruling, 2026-10-21: derived sources,
	project rules and the rest of the prompt system are not counted), and the median input tokens per trial in each cell
	where both have outcomes.
- **Closes:** R-D10, R-D11, R-E18.
- **Tests:** `PromptAuthorManifestTest`, `MinimalityTest`, `StatusReportTest`, `DriverTest` traces 17 and 18.
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
- **Proposal:** derivation finds the recorded scenarios of every prompt source, gives them to the contract author, and
	refuses a proposal in which no test cites one of them, naming each missing scenario (`design.md` §6.1).
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
	trial's test case, reply, evidence section, deterministic results and failure record (judge rationale or check
	record) and returns `blocking` or `self-recovering` with a rationale in a strict schema. It runs only for failed
	`DEVELOPMENT` trials; an invalid output reruns it, and a third invalid output records `blocking`.
- **Fail-fast:** adopting a blocking development failure discards the working candidate's unadopted development trials
	(the driver interrupts their sessions), clears any diagnosis and starts a round at once. A self-recovering failure
	keeps the edit-or-continue path.
- **Persistence:** the trial, the completion event and the retained failure carry the impact; the prompt author brief
	shows it. Campaign format 12.
- **Closes:** R-D16.
- **Tests:** `EditOrContinueTest` and adoption tests for both impacts, a blocking failure during a self-recovering
	diagnosis, and the discard of unadopted trials; `TriagePromptTest` for the request and the parser; `DriverTest`
	trace of a blocking failure that interrupts the running development trials and starts the prompt authors; store round
	trip and older-format reads.
- **Exit:** unit tests pass; the full build passes.

### WI-30 — Measured trigger-absent cases (user approval, 2026-10-03)

- **Contract:** the proposal schema gives each requirement a trigger, or marks that it always applies, and each test
	lists the triggers it leaves absent. Validation refuses a proposal in which a requirement with a trigger has no such
	test. The contract author brief asks for both.
- **Manifest:** each hunk's dissimilar case is a test identifier of the contract; manifest validation rejects an attempt
	that names one the contract lacks. The prompt author brief lists the contract's tests by identifier.
- **Closes:** R-D17.
- **Tests:** `ProposalTest` for a conditional requirement with and without a trigger-absent test, and one that always
	applies; `PromptAuthorManifestTest` for a known and an unknown dissimilar test.
- **Exit:** unit tests pass; the full build passes.

### WI-31 — Codex models of version 6 or later (user ruling, 2026-10-04)

- **Inventory:** `CodexProfileInventory` keeps a model listed with visibility `list` only when the number after `gpt-`
	in its slug, up to the next `-` or the end, is a version of 6 or later (`gpt-6-luna`, `gpt-6.1-sol`); it leaves out
	`gpt-5.5`, `gpt-5.6-sol` and any slug without that form. When no listed model qualifies, inventory fails with the
	existing diagnostic style, naming the requirement.
- **Bindings:** `ProfileInventory` gains `supportsModel(String model)`: `ClaudeProfileInventory` supports every model,
	and `CodexProfileInventory` supports a model of version 6 or later, by the same slug rule as the inventory.
	`binding(String)` is unchanged. After reading the staged source's bindings and before it starts the contract author
	session, the core checks each bound model; when any is unsupported, it starts no session and reports, as it reports a
	contract author failure, every such file with its model and that the user changes the binding and runs `start` again.
	A file that binds only its effort, or a supported model, is unaffected. Reading the Codex role profile fails, naming
	the installed `optimize-xhigh.toml` and its model, when that model is below version 6.
- **Closes:** R-D18.
- **Tests:** `CodexProfileInventoryTest` over recorded catalog output with `gpt-5.5`, `gpt-5.6-sol`, `gpt-6-luna`,
	`gpt-6.1-sol` and an unversioned slug, and `supportsModel` on each form; `ClaudeProfileInventoryTest`, that every
	model is supported; a core test in which a staged Codex agent TOML binds `gpt-5.6-luna`: no contract author session
	starts and the report names the file and the model, while a binding to `gpt-6-luna` or an effort-only binding derives.
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
	The commit gate and the §3.9 development minimum sum over development cells; prompt author rounds and the weak test
	use only them.
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
- **Tests:** `CapabilityRankingTest`, `CellTierTest`, `SchedulerTest`, `BaselineTest`, `CommitGateTest`,
	`ProposalTest`, `OptimizerCoreTest`, `StatusReportTest`, the contract and campaign store round trips, `DriverTest`
	trace 19.
- **Exit:** unit tests pass; the full build passes.

### WI-33 — Prompt tests and held-out parts (user ruling, 2026-10-04)

- **Split:** each variable of a prompt test states whether it is outcome-changing (user rulings, 2026-10-04
	and 2026-10-09). At confirmation CAT draws a random salt, frozen with the contract. In `optimizer.contract`, a test
	case's part is a hash of its prompt test and its outcome-changing values under the salt, each part with probability ½,
	so test cases that share outcome-changing values share a part. Variable values are finite lists, so CAT counts each
	prompt test's distinct outcome-changing combinations per part and refuses the proposal when either count is below 2,
	naming the prompt test and both counts, or when the prompt test marks no outcome-changing variable; the proposal
	lists the outcome-changing variables and the counts.
- **Draws:** `Generator.draw` takes the part from the stream kind: development draws development test cases, formal
	and baseline draws held-out test cases, each uniform over the prompt test's test cases in its part by the frozen
	probabilities restricted to that part. Targeted runs and promotion runs use development test cases only.
- **Evidence:** `TrialAdoption.adoptDevelopment` no longer adds the incumbent's development outcomes to the baseline;
	`adoptBaseline` and formal adoption no longer add failures to the regression set. A formal failure reaches
	development as its cell, prompt test and failed requirements: triage, diagnosis and prompt author briefs never receive
	a held-out test case, reply or judge rationale. The user's report may show held-out test cases.
- **ContractAuthor:** the contract author's prompt asks whether each variable is outcome-changing, so that each test
	yields at least 2 distinct outcome-changing combinations per part; any wording change to an optimizer prompt is shown
	to the user before delivery.
- **Format:** the contract stores the salt and whether each variable is outcome-changing; the campaign
	format becomes 15 and every older format is refused, as WI-32 refuses formats before 14.
- **Closes:** R-C16, R-D9, the part side of R-C4, R-C1, R-D3 and R-D7.
- **Tests:** `GeneratorTest`, `DrawStreamTest`, `ProposalTest`, `TrialAdoptionTest` (or the adoption cases in
	`DriverTest`), `DriverTest` trace 21, the prompt author brief tests, the contract store round trip.
- **Exit:** unit tests pass; the full build passes.

### WI-38 — Reproduction before tuning (user ruling, 2026-10-04)

- **Behavior:** the proposal lists, per requirement, the behaviors through which it can fail; each deterministic check
	names its behavior; the judge's verdict names one listed behavior or `other` for each failed requirement;
	`FailureSummary` carries it. Contract, proposal and campaign formats increase; older ones are refused.
- **Groups:** after a failed attempt, formal failures are grouped by cell, test, failed requirement and behavior.
- **Rounds:** per group, the values contract author writes at least 4 combinations aimed at the behavior; CAT runs up to
	100 reproduction trials of them in the group's cell, ending the round at 2 failures with that behavior. A reproduced
	group's values join development as WI-34's do. Otherwise the contract author gets the tried values and their counts,
	no replies, and its new values replace them. A failed round may repeat under a stress setting the harness offers (a
	lower effort of the same model; in Codex, a lower tool-output limit); a reproduction there adds that stressed profile
	as a development cell.
- **Pause:** after 4 rounds without reproduction, the group is recorded as not reproduced with its counts and CAT
	pauses; the user chooses more rounds, a new attempt with the unchanged candidate after the commit gate, or
	stopping. Prompt authors work on no group before it reproduces.
- **Closes:** R-C18, R-C19.
- **Tests:** the cases listed under R-C18 and R-C19.
- **Exit:** unit tests pass; the full build passes.

### WI-34 — Development values after a failed attempt (user ruling, 2026-10-04)

- **Trigger:** a certification attempt ends (`below minimum` or `inconclusive`) with at least one formal failure.
- **ContractAuthor:** one contract author session per ended attempt receives, for each prompt test with a formal
	failure, that prompt test with only the development values of its outcome-changing variables (as the prompt author's
	contract shows them, without the salt) and each failure's cell and failed requirements. It returns at least 4 new
	outcome-changing combinations per failed test that exercise those requirements, in a strict schema; the contract
	author's prompt change is shown to the user before delivery.
- **Validation:** CAT rejects a new outcome-changing value equal to any existing value of its variable, held-out values
	included, without telling the contract author which part holds the value, and validates the generator as for any test
	case; a rejected or failed derivation is an infrastructure failure.
- **Draws:** the added combinations belong to the development part only. Development draws of the test give them,
	together, half of its probability, and the confirmed development combinations the other half by their frozen
	probabilities. Formal and baseline draws are unchanged. A failure on an added test case is an ordinary development
	failure.
- **Counts:** the development distribution changed, so every candidate's development counts restart and the
	commit gate needs new trials.
- **State:** the added values are campaign state outside the confirmed contract; the contract identity and the
	measurement identity are unchanged; the user's report lists them.
- **Closes:** R-C17.
- **Tests:** `DriverTest` (ended attempt to contract author to development draws; restart of counts; no held-out value
	or salt in the contract author input; the WI-33 recommit loop no longer recommits an unchanged candidate without new
	development trials for the commit gate), `GeneratorTest`, the contract author brief tests, the campaign store round
	trip.
- **Exit:** unit tests pass; the full build passes.

### WI-35 — Formal cell order (user ruling, 2026-10-04)

- **Rule:** in the formal phase, `Scheduler` sends a category's formal trials only to its open cells: its development
	cells, and each other scope cell once every development cell and every scope cell below it are certified. A cell is
	below another when its model ranks no higher by `CapabilityRanking` and its effort is no higher, and the two differ.
	Among open cells, the one with the fewest formal outcomes goes first. The category limit applies to the open cells'
	remaining passes. Baseline trials are not ordered.
- **Closes:** R-E23.
- **Tests:** `SchedulerTest` cases listed under R-E23; a `DriverTest` attempt that fails in the weakest cell dispatches
	no formal trial elsewhere.
- **Exit:** unit tests pass; the full build passes.

### WI-36 — Held-out content unreadable (2026-10-04)

- **Rule:** every nested session except the judge runs with its harness sandbox denying reads of the campaign
	snapshot, the proposal and the trial records: Claude through `sandbox.filesystem.denyRead` and `Read` deny rules in
	the session's settings, Codex through deny entries in a permission profile that the thread names. A harness whose
	session cannot carry the list is refused before the campaign starts.
- **Closes:** R-E24.
- **Tests:** `ClaudeSessionsTest` and `CodexSessionsTest` cases listed under R-E24.
- **Validation:** one prompt author session per harness, asked to print the snapshot by its absolute path, gets no
	content; one development trial per harness still passes.
- **Exit:** unit tests pass; the full build passes; the validation holds in both harnesses.

### WI-39 — Leftover runs in the real run roots (2026-10-04)

- **Defect:** `OptimizerCore` and `DriverHarness` give `LeftoverRuns` `<campaign>/runs`, but runs are written under the
	harness run root `.cat/work/optimizer-runs/<harness>` and, since WI-37, `<harness>-held-out`. Reconciliation
	therefore never finds a killed driver's runs, and Codex harness processes that outlive the driver keep running (H20).
- **Fix:** reconciliation searches both run roots. The run roots hold every campaign's runs, so a run counts as left
	over only when it is still marked `running` and its recorded owner process (`ownerPid`, `ownerStartedAt`) is no
	longer alive; a run whose owner lives belongs to a running driver, of this or another campaign, and is left alone.
- **Closes:** R-E11 with WI-9.
- **Tests:** `LeftoverRunsTest`: a running run with a dead owner is found in each root; one with a live owner, a
	finished run and a manifest without start times are not. `ReconciliationTest` or `DriverTest`: reconciliation passes
	the session port's run roots, not a campaign directory.
- **Exit:** unit tests pass; the full build passes.

### WI-42 — Judge version (user ruling, 2026-10-04)

- **Change:** the judge measurement condition becomes a judge version, a constant per harness reached through the
	session port, in place of the judge model. CAT increments it in every release that changes the judge's role profile
	(model and effort), prompt, rubric, decision rule, result schema, or the evidence section rendered for the same harness
	events. A new judge version makes a certification historical. The campaign format increases and every older format
	is refused.
- **Guard:** each harness module keeps a registry file of its judge versions, each with the hash of the judge profile
	and of the evidence section rendered for fixed harness events. A test fails when the current hash is not registered
	under the current version, or when one version has two hashes, so a judge change needs a new version.
- **Closes:** R-M2 with WI-3 and WI-41.
- **Tests:** `ApplicabilityTest`: a new judge version makes a certification historical, and an unchanged one keeps it
	current; the registry test in each harness module; a change to each profile field or to the rendered section changes
	the hash.
- **Exit:** unit tests pass; the full build passes.

### WI-47 — Codex trials record the observed model and effort

- **Change:** `CodexProcess.sendTurn` passes every start-up notification it kept to the listener, in arrival order,
	before the turn's own notifications, so the bridge forwards them and the optimizer's parser sees `thread/started`.
	`AppServerEvents` also reads the root thread's `thread/settings/updated` notification: its `threadSettings.model`
	and `threadSettings.effort` replace the values from `thread/started`, and a later notification replaces an earlier
	one. A notification for another thread changes nothing.
- **Closes:** R-E16 for Codex.
- **Tests:** `CodexProcessTest`: the listener receives the start-up notifications before the turn's, and the turn
	archive is unchanged; `AppServerEventsTest`: a session whose `thread/started` reports `medium` and whose
	`thread/settings/updated` reports `low` reports `low`, and a child thread's settings change nothing.
- **Exit:** unit tests pass; the full build passes; the next Codex E1 trial records show the observed model and
	effort.

### WI-48 — Every trial session leaves a session record

- **Change:** each trial session end, whatever its result, appends one session record to the trial record: start
	and end times, concurrency, the campaign's throttle state after the session's rate-limit windows were applied, the
	windows the session reported, the login class and the reported token usage, if any. The login class is classified
	once when the driver starts. The session records replace the trial record's token-usage list, raising the format
	version by one.
- **Closes:** R-E6's per-trial records, and the login class that E1's receipt shows.
- **Tests:** `CampaignStoreContract`: a record with session records round-trips, and a file at the earlier version
	is refused with the start-a-new-campaign message; `DriverTest`: a completed trial and a rate-limited trial each
	append a session record with the expected values.
- **Exit:** unit tests pass; the full build passes; the next E1 trial records show every field.

### WI-51 — The in-project target of a prompt-source link is editable (user ruling, 2026-10-05)

- **Change:** `Authority` treats an in-project file as a prompt file when a symbolic link at or below a prompt source
	resolves to it, following link chains through the fork inventory. Such a target accepts content edits and
	ordinary-file creates and deletes, whether the attempt names the link path or the target path. External targets stay
	`external_target_read_only`, loader configuration stays `loader_configuration_fixed`, a cycle stays `link_cycle`,
	and an in-project file that no prompt-source link reaches stays `outside_prompt_sources`. The link report and the
	prompt author prompt follow from the same authority, so the proposal no longer reports such a link as outside the
	prompt sources.
- **Closes:** D54; found by the E7 rerun on 2026-10-05, where the prompt author's prompt listed `docs/handbook.md` as
	editable, three attempts edited it (`docs/handbook.md -> shared/handbook.md`), and CAT rejected each one as
	`outside_prompt_sources`, pausing the campaign after 3 prompt author failures.
- **Tests:** `AuthorityTest`: an edit at a prompt-source link whose target is in the project is authorized, at the
	link path and at the target path; through a chain of two in-project links; a link whose target is external is
	`external_target_read_only`; a prompt-source link to loader configuration is `loader_configuration_fixed`; an
	in-project file that only a non-prompt-source link reaches is `outside_prompt_sources`. `LinkReportTest`: the
	in-project link of a prompt source reports no limit. A `DriverTest` or authoring test: an attempt that edits the
	target through the link becomes a candidate.
- **Exit:** unit tests pass; the full build passes; the E7 rerun's handbook edits are accepted.

### WI-50 — A role session that reaches its time limit is an infrastructure failure

- **Change:** when a contract author, judge, triage or prompt author session reaches its time limit, the session's
	result is `SessionResult.Failed` with the reason "The <role> session reached its <N>-minute time limit", and each
	role's existing handling of a failed session applies. A trial session that reaches its time limit stays a completed
	session judged on its behaviour. `start` and `revise` report a contract author session that did not complete by its
	reason (failed), as interrupted, or as rate-limited until its reset time, never by the result's record text.
- **Closes:** R-E13 for a contract author session that reaches its time limit; found by the E7 rerun on 2026-10-05,
	where the provider streamed the contract author's proposal about 8 times slower than in the previous run, the
	30-minute limit stopped the output turn, and `start` reported "the final response is not exactly one JSON object".
- **Tests:** `SessionTest` (or the existing session adapter test): a contract author, judge, triage and prompt author
	session that reaches its time limit is `Failed` with the time-limit reason; a trial session that reaches it is
	`Completed`. `OptimizerCoreTest` (or the existing `start` test): a contract author session that fails reports its
	reason and the retry command.
- **Exit:** unit tests pass; the full build passes.

### WI-52 — A trial turn the provider never answered is an infrastructure attempt (user ruling, 2026-10-06)

- **Change:** each harness's event reader records, per turn, whether the model produced any output (a reasoning,
	reply or tool item, or reported token usage) and whether a hook is still running. When a trial session reaches its
	time limit in a turn with no model output and no running hook, the session's result is `SessionResult.Failed`
	with the reason "The trial's turn <N> reached its <M>-minute time limit with no model output", so the driver
	records an infrastructure attempt and reruns the same logical trial. A timed-out turn with any model output, or
	with a hook still running, stays a completed session judged on its behaviour.
- **Closes:** D55; found by the fresh E1 run on 2026-10-06, where a provider overload (`server_is_overloaded` in the
	load balancer's log at 05:01:26 UTC) left a Codex trial with no model output after its user message for 60 seconds, and
	the trial was judged "no reply" and counted toward the plateau.
- **Tests:** `SessionTest` (or the existing session adapter test): a trial that reaches its time limit with no model
	output after its user message is `Failed` with the reason; one with a reasoning item, a reply delta, a tool call or
	token usage is `Completed`; one whose hook started and never completed is `Completed`; a multi-turn trial whose
	turn 1 replied and whose turn 2 has no output is `Failed`. Event-reader tests for Codex and Claude: each output
	kind and each hook start and completion sets the per-turn state, using recorded event shapes.
- **Exit:** unit tests pass; the full build passes.

### WI-53 — A rejection or a refusal holds every CAT process of the harness (user rulings, 2026-10-06)

- **Change:**
	- **Refusal detection.** Each event reader recognizes a provider refusal, an HTTP 403 from the provider or its
		network edge. Codex: an `error` notification with `willRetry` false whose `codexErrorInfo` holds an
		`httpStatusCode` of 403 under any key (E8 recorded `httpConnectionFailed`; the retried ones carry
		`responseStreamDisconnected`). Claude: an error `result` with `api_error_status` 403, read where 429 is read
		today. A final Codex error with an `httpStatusCode` of 429 is a rate-limit rejection without a reset time: the
		load balancer answers a request it cannot admit under its account caps with a 429 `rate_limit_error`. `Session`
		reports a refused session as distinct from a rate-limited one, for every role.
	- **Shared hold.** `HarnessCapacity` keeps the hold in its harness directory: the time the hold ends and the next
		hold length. Writes are atomic and serialized by a lock file in the same directory, and a write never moves the
		end earlier. `acquire` waits and `tryAcquire` returns empty while the hold lasts, for the optimizer and both
		runner CLIs. An unlimited instance has no hold; a test-owned directory has its own.
	- **Hold rules.** A rate-limit rejection with a reset time holds until that time. A rejection without one, or a
		refusal, holds for the next length: 1 minute, doubling for each rejection that follows the end of a hold, up to
		30 minutes. A rejection that arrives while a hold lasts does not lengthen the sequence. A turn that completes
		resets the next length to 1 minute. The campaign `Throttle` takes its hold end and backoff from the shared hold
		rather than keeping its own sequence, and still drops `w` to 1; trial records keep recording the throttle state.
	- **Driver.** A rejected or refused session runs again after the hold: a trial as the same logical trial, any other
		role as the same request. Neither counts toward the 3-infrastructure-failure limit. A refusal that follows a
		30-minute hold pauses the campaign with "The provider refused <harness> requests after a 30-minute hold".
- **Closes:** D57 and the §3.5 rejection hold; found by E8 on 2026-10-06, where 403 bursts failed up
	to 451 of 474 requests a minute while each refused session reran within a minute.
- **Tests:** `AppServerEventsTest` and `StreamJsonEventsTest` on recorded shapes: a final 403 error is a refusal, a
	retried one (`willRetry` true) is not, a final Codex 429 and a Claude 429 are rate-limit rejections, a 500 stays an
	error. `HarnessCapacityTest`: a hold set by one instance blocks `tryAcquire` on a second instance over the same
	directory and ends at its time; a shorter hold never replaces a longer one; the lengths run 1, 2, 4, 8, 16, 30, 30
	minutes; a completed turn resets them; a rejection during a hold does not double. `DriverTest`: a refused trial reruns as the same logical trial
	after the hold and three refusals do not pause; a refusal after a 30-minute hold pauses with the message; a refused
	judge or prompt author request reruns. All with an injected clock and pause; no test sleeps.
- **Exit:** unit tests pass; the full build passes.

### WI-55 — Prompt authors receive each failing trial's evidence section (user ruling, 2026-10-06)

- **Change:** `PromptAuthorBrief` carries, for each failure it lists, that trial's evidence section, rendered by the
	same renderer the judge uses from the trial's retained harness events. `PromptAuthorPrompt` places each section with
	its failure. A failure whose events are no longer retained says so instead of omitting the section silently. Because a
	prompt author can start in a later slice than its failure, each development failure persists the paths of its trial's
	transcript and effects (carried through `CampaignEvent.Complete`, `LogicalTrial` and `Failure`). `campaign.json` and
	the trial records raise their `format_version` by one, and a file at the earlier version is refused with "start a new
	campaign". At prompt author start the `Driver` stages the working snapshot, which every development failure belongs
	to, and renders each failure's section from its persisted paths through the judge's renderer, which takes those paths
	instead of a `Completed`. A missing transcript or effects directory leaves that failure without a section.
- **Closes:** D56 and open item "prompt authors cannot see trial tool calls"; found by E1, where prompt authors could
	not tell a mistyped `read-file` path (exit 127) from a skipped read.
- **Tests:** `PromptAuthorPromptTest`: each failure's section appears with that failure and contains its tool call, exit
	status and output; two failures from different trials each get their own section; a failure without retained
	events states it. `DriverTest`: a prompt author started after a failed trial is given that trial's section.
- **Exit:** unit tests pass; the full build passes.

### WI-56 — Development has no plateau (user ruling, 2026-10-06)

- **Change:** remove `Outcome.PLATEAU`, `PendingDecision.PlateauReached` and `Plateau`. A complete round with no
	promotable attempt always takes the path that `TrialAdoption.endUnproductiveRound` takes today within the limit:
	keep the working candidate and its retained attempts, resume its representative trials, restart admissions at a
	window of 1. A later failure edits again under edit-or-continue. The review checkpoint is the bound; it offers
	`accept without certification` when the working candidate is not the incumbent. Status and report texts lose the
	plateau. `campaign.json` raises its `format_version`, and a file at the earlier version is refused with the
	start-a-new-campaign message, because a stored plateau pause has no meaning now.
- **Closes:** D53 and §2.7 "Rounds"; found by E1 and E7, which ended at a plateau after 15 and 5 rounds.
- **Tests:** `PlateauTest` becomes a test of the round's end: a complete round without a promotion above the failure
	limit resumes sampling, and the next failure starts a new round. `DriverTest`: a candidate that keeps failing
	reaches the review checkpoint and stops there, offering `accept without certification`. `FileCampaignStoreTest`:
	the earlier version is refused. `StatusReportTest` and `DecisionTableTest` lose the plateau rows.
- **Exit:** unit tests pass; the full build passes; `grep -ri plateau client/` finds only rejection-test fixtures.

### WI-57 — A capacity slot stays locked while its permit is open

- **Change:** on Linux, closing any channel on a file releases every POSIX lock that the process holds on that file.
	`HarnessCapacity.tryAcquire` opens each slot file in turn and closes the channel when the slot is taken; when another
	permit in the same JVM holds that slot, the close releases that permit's kernel lock while `FileLock.isValid()` stays
	true. The JVM's own lock table still keeps its sessions apart, so each process stays within the maximum, but other
	processes see the slots as free and the total across processes is unbounded. `tryAcquire` must never open or close a
	channel on a slot file that a permit in this JVM holds: one explicitly shared, process-owned `FileLocks`
	coordinator reserves held files before opening them and releases reservations only after their channels close.
	Directory aliases share reservation keys and hold-update monitors. Process scopes and runner entry points supply
	the same coordinator to capacity pools, campaign locks and run locks; coordination uses no mutable static registry. Every other `FileChannel` lock in `client/` (`CampaignLock`, `HarnessRunLock`, the hold
	lock and any other) is audited for the same open-and-close pattern and fixed the same way where a second channel on
	a locked file can be opened in the same JVM.
- **Closes:** R-E8 across processes; found by E8 on 2026-10-06, where campaign b's JVM held 16 open slot permits with
	12 live sessions while the kernel listed locks on 1 or 2 slot files, and two campaigns never showed more than 7
	locked slots at once.
- **Tests:** `HarnessCapacityTest`, through an injected slot-file opener: with a permit held on slot 0, a second
	instance over the same directory takes slot 1 without opening slot 0's file; after either permit closes, the other
	slot is still never opened by a third instance until its permit closes; at the maximum, `tryAcquire` opens no held
	slot file. Tests for each other lock the audit fixes. No test starts a process or sleeps.
- **Exit:** unit tests pass; the full build passes; during the E8 rerun, the kernel lists a lock for every open
	permit.

### WI-58 — Prompt authors address every kind of failure (user request, 2026-10-07)

- **Change:** the prompt author's prompt has the prompt author group the failures it receives into kinds, by the first
	action where each failing trial departs from the contract, and correct every routing or guidance kind. A kind that
	looks like a model limitation, such as a mistyped command or a skipped step, is routing or guidance; `not guidance`
	covers only a test or contract defect or an infrastructure fault. The prompt author input adds the lineage's earlier
	manifests with their promotion results. For a kind that an earlier hunk targeted and that failed again, the prompt has
	the attempt explain why the violated instruction must be followed, by stating the consequence of violating it where
	the model decides, instead of restating it. The manifest gains a list of kinds (name, failing test cases,
	classification, the hunks that address it), which replaces the per-failure classification. Manifest validation rejects
	a received failure outside every kind, a routing or guidance kind without a hunk, and a kind that names an unknown
	hunk. If a stored campaign file holds manifests, the change raises its format version as WI-56 did.
- **Closes:** D58 and §2.7 "Candidate generation and promotion" (steps 1, 3 and 5); R-D10.
- **Tests:** `PromptAuthorManifestTest`: a manifest with a failure outside every kind, a guidance kind without a hunk,
	or a kind naming an unknown hunk is rejected; a complete manifest is accepted; a manifest whose kinds are all `not
	guidance` produces no candidate. The test of the prompt author's prompt: the prompt holds the earlier manifests with
	their promotion results and states the every-kind rule. `DriverTest`: a rejected attempt is retained and contributes
	no candidate.
- **Exit:** unit tests pass; the full build passes.

### WI-59 — Approaches learned from hand tuning, rotated across rounds (user request, 2026-10-07)

- **Change:** `PromptAuthorApproach` gains six approaches after the first three: remove what the failing replies copied,
	explain the consequence where the decision is made, offer one form of each action, put text to be copied last on
	its line, define before use with definitions in agreement, and give the exact text rather than how to build it. A cell's attempts take the approaches in order, each
	round continuing where the cell's previous rounds stopped and wrapping after the last. The count of earlier attempts
	is the number of distinct attempt numbers below the round's first reservation for which CAT retained a manifest or a
	promotion result for that cell.
- **Closes:** D59 and §2.7 "Approaches"; R-D14.
- **Tests:** `RoundTest`: a round continues from the earlier count and wraps; `OptimizationEvidenceTest`: earlier
	attempts are counted per cell, once each, below the bound; `DriverTest`: a cell with two earlier attempts gives its
	next prompt author the third approach; `PromptAuthorTest`: each approach's instruction is rendered.
- **Exit:** unit tests pass; the full build passes.

### WI-49 — An answered authority warning stays answered

- **Change:** `Transitions` merges a `WarnAuthority` into a pending `AuthorityWarnings` decision, listing each distinct
	warning once. When the user chooses `use_authorized_source`, the campaign records the decision's warnings as
	answered (`answered_warnings` in `campaign.json`, raising its `format_version` by one), and a later
	`WarnAuthority` drops each answered warning; when none remains, no decision is raised and the attempt ends like any
	attempt without edits. The prompt author's prompt lists the answered warnings and tells the prompt author that the
	user declined those edits and that their requirements must be met through the editable prompt sources. The prompt
	author's prompt's editable prompt sources leave out each link whose resolved target is external; the link stays in the
	links list.
- **Closes:** R-W4 for repeated warnings; found by E7, where 17 prompt author attempts produced no candidate because
	each round reported the answered `answer_only_question` and `exact_motto_closing` warnings again.
- **Tests:** `TransitionsTest`: two warnings raised while a warning decision is pending give one decision listing each
	warning once; after `use_authorized_source`, an answered warning raises nothing and a different warning raises a
	decision; `CampaignStoreContract`: answered warnings round-trip, and a file at the earlier version is refused with
	the start-a-new-campaign message; `PromptAuthorPromptTest`: the prompt lists each answered warning, and a link to an
	external file is absent from the editable sources and present among the links.
- **Exit:** unit tests pass; the full build passes; the E7 rerun reaches an outcome without repeating an answered
	warning.

### WI-46 — Development continues after a round without a promotion (user ruling, 2026-10-05)

- **Change:** when a round completes without a promotable attempt, whether every attempt failed its promotion runs, no
	attempt was accepted, or a revert counted the round as unproductive, CAT checks the working candidate's development
	counts. If no category has more than `EditOrContinue.FAILURE_LIMIT` failures, the round ends with its attempts and
	diagnoses retained, the working candidate stays, and representative admissions resume with concurrency restarting at
	1; a later adopted failure goes through edit-or-continue as usual, and the commit gate commits the candidate once
	it holds. Otherwise the outcome is `not certified: plateau` as before. The status text for the plateau names the
	failure limit. Apply this at each place that now reports a plateau.
- **Closes:** R-D6.
- **Tests:** `TrialAdoptionTest` or the existing plateau tests: a sequential round, a weak round and a revert, each
	without a promotion, resume sampling while every category has at most 2 failures, and report a plateau when one
	has 3; a candidate that resumed sampling and then meets the commit gate is committed; `DriverTest`: a campaign
	whose only round fails continues admitting representative trials; `StatusReportTest`: the plateau text.
- **Exit:** unit tests pass; the full build passes.

### WI-45 — External files are read-only (user ruling, 2026-10-05)

- **Change:** an external file, reached through a link or read directly, is a read-only member of the prompt family.
	`Authority` takes no authorized targets and returns `EXTERNAL_TARGET_READ_ONLY` for every operation on an external
	file; its warnings offer `use_authorized_source`, stop and revise only. Remove the `authorize_target` choice, the
	`--target` option, `Campaign.authorizedTargets`, the authorized targets in the prompt author brief and prompt, and
	`authorized_targets` in `campaign.json`, raising its `format_version` by one. Delivery plans and writes in-project
	changes only: an overlay that changes an external file is refused before any write, and the delivery output has no
	external change record. Delivery still scans the external files the fork recorded, so a change made to one after
	the fork makes the result historical.
- **Closes:** R-W4 and R-W8 under the ruling.
- **Tests:** `AuthorityTest`: a content edit, create, delete, mode change and retarget of an external file each return
	`EXTERNAL_TARGET_READ_ONLY`, and the warning lists no choice that edits it; `CommandsTest`: `choose <id>
	authorize_target` and `--target` are rejected as unknown; `CampaignStoreContract`: a stored campaign round-trips
	without the key, and a file at the earlier version is refused with the start-a-new-campaign message; `DeliveryTest`:
	an overlay with an external change is refused before any write, and a change to a read-only external participant
	after the fork makes the result historical; `DriverTest.authorityWarningTrace` updated.
- **Exit:** unit tests pass; the full build passes; no supported consumer can reach `authorize_target`,
	`authorized_targets` or `external_changes`.

### WI-44 — Reports cover every development cell (2026-10-04)

- **Defect:** the commit gate covers every development cell, but `RemainingWork.development` iterates the
	certification scope's cells and `CellReports` the contract's cells. A development cell outside the scope, such as a
	failure cell under a weakest-only scope or a tool-output stress cell, is missing from the development minimum, and a
	stress cell from the per-cell report.
- **Fix:** each adds the development cells to the cells it iterates, each once, in contract order with a stress cell
	after its base cell.
- **Closes:** R-E12 with WI-12.
- **Tests:** `RemainingWorkTest`: a development cell outside the scope adds its commit-gate minimum; `StatusReportTest`
	or the cell report test: that cell appears once; a scope cell that is also a development cell is not counted twice.
- **Exit:** unit tests pass; the full build passes.

### WI-43 — Cleanup and certification record (user rulings, 2026-10-04)

- **Record:** when the delivered candidate is certified, the delivery change set writes the certification record to
	`<project>/.cat/certifications/<target path relative to the project, without its extension>.json`, replacing any
	earlier one, through the same preflight, journal and preimage checks as the prompt files. It holds the certification
	index, the scope, each category's counts, the threshold, `α_i` and every measurement condition, with the prompt
	sources and the contract by hash, and no trial results.
- **Cleanup:** after the delivery's final verification, or when the user keeps the incumbent instead of a regressing
	certified candidate, CAT removes the optimization worktree, deletes the campaign's runs from both run roots and
	deletes the campaign directory last. The campaign records the cleanup before it starts, so a fault leaves a campaign
	that the next command for the target finishes removing. Other campaigns keep their files.
- **Status:** `status` for a target without a campaign, and a new campaign's proposal, compare the record with the
	conditions CAT knows there and report current or historical with each changed condition; a condition only a session
	observes, such as the trial model, is reported as unchecked.
- **Closes:** R-W9, R-M6.
- **Tests:** `CleanupTest`: each ending removes everything, an uncertified or cancelled campaign keeps its files, and a
	fault before each step leaves a removal that the next command completes; `CertificationRecordTest`: the record's
	fields and size for a fixture campaign, and no trial result in it; `DeliveryTest`: the record is in the diff and
	replaces an earlier one; `ApplicabilityTest`: a record against a changed prompt hash, judge version and session
	environment version, and an unchecked trial model.
- **Exit:** unit tests pass; the full build passes.

### WI-41 — Session environment version (user ruling, 2026-10-04)

- **Change:** the runner and CAT versions become information that the identity records and the report shows, never a
	material or comparability condition. A new material condition, the session environment version, takes their place:
	a constant per harness, owned by that harness's module and reached through the session port, which CAT changes only
	in a release that changes what a session's model receives or how its actions take effect. The campaign format
	increases and every older format is refused.
- **Guard:** each harness module keeps a registry file of its session environment versions, each with the hash of the
	session settings the runner generates for a fixed input, with paths that differ per run replaced by placeholders: for
	Claude the isolated `settings.json`, for Codex the configuration and permission profile it writes. A test fails when
	the current settings' hash is not registered under the current version, or when one version has two hashes, so a
	settings change needs a new version.
- **Parameters:** each certification records its minimum pass rate and its `α_i`. Applicability compares
	them with the current values: the certification stays current while its minimum pass rate is at least the
	current one and its `α_i` is at most the current level for its attempt index; otherwise it is historical, and the
	report names the parameter.
- **Closes:** R-M2 with WI-3.
- **Tests:** `ApplicabilityTest`: a runner or CAT version change alone leaves a certification current, and a session
	environment change makes it historical; a recorded threshold above, equal to and below the current one, and an
	`α_i` below, equal to and above the current level, each give the implied result; `ComparabilityTest`: differing CAT versions do not prevent comparison;
	`DriverTest`: a CAT version change between trials does not pause; the registry test per harness; the format refusal.
- **Exit:** unit tests pass; the full build passes.

### WI-40 — Held-out reads re-allowed by `allowRead` (2026-10-04)

- **Defect:** in Claude, `sandbox.filesystem.allowRead` re-allows reads that `sandbox.filesystem.denyRead` denies, and
	settings files outrank the runner's isolated settings. An entry naming the held-out directory, the held-out run root
	or a file in them, in managed or project settings, exposes them to every command; the runner also copies the host
	user's `allowRead` into the isolated settings.
- **Fix:** `start` refuses when managed settings, `.claude/settings.json` or `.claude/settings.local.json` set a
	non-empty `sandbox.filesystem.allowRead`, as it refuses a non-empty `sandbox.excludedCommands`: entries are refused
	whatever they name, because which entries re-allow a denied path depends on Claude's path resolution, which CAT does
	not own. The runner's isolated settings drop `allowRead` whenever the session denies reads.
- **Closes:** R-E24 with WI-36 and WI-37.
- **Tests:** `ClaudeSessionsTest`: each of the three files with a non-empty `allowRead` is refused and names the
	setting; an empty array is not. The isolated-settings test: a host `allowRead` is dropped when reads are denied and
	kept when none are.
- **Validation:** the WI-37 Claude probe with project `allowRead` naming the held-out directory: `start` refuses, and
	the runner session with a host `allowRead` naming it gets no content.
- **Exit:** unit tests pass; the full build passes; the validation holds.

### WI-37 — Held-out directory and run root (2026-10-04)

- **Layout:** the snapshot, the proposal and the trial records move into the campaign's `held-out/` directory; the
	campaign format increases and every older format is refused. Formal and baseline trials and judges run under a
	held-out run root beside the runner's run root, created before any session starts.
- **Denial:** development trials, targeted runs, triage, diagnosis, prompt authors and the contract author deny reads of
	both directories as whole directories; formal and baseline trials and judges deny nothing.
- **Refusal:** `start` refuses when the host's settings turn off the sandbox that the denial needs.
- **Closes:** R-E24 with WI-36.
- **Tests:** store layout and format refusal; session arguments per role and harness; the refusal. The `codex sandbox`
	probe that a snapshot replaced by rename stays unreadable is manual evidence, not a test, because no test may start
	a harness process (§8).
- **Validation:** WI-36's, run after this item, plus, for Claude, that project settings with
	`sandbox.filesystem.allowRead` naming the held-out directory or `permissions.defaultMode: bypassPermissions` leave
	it unreadable; if either exposes it, `start` refuses that setting too.
- **Exit:** unit tests pass; the full build passes; the validation holds in both harnesses.

### WI-27 — Role sessions without the plugin, and the path trigger (user ruling, 2026-10-02)

- **Sessions:** the runner settings decide plugin installation per session role: trials install CAT; the contract
	author, judge and prompt author do not (`design.md` §3.1, D37), in both harnesses.
- **Role rules:** the prompt author and contract author briefs carry their rules' complete text from the installed
	artifact; the list is declared once.
- **Path trigger:** the rule loader's path-scoped rule text names the rule by its absolute installed path and limits
	the trigger to files inside the current project, so reading CAT's installed rule files never matches it.
- **Closes:** R-E20.
- **Tests:** session-argument tests per harness for each role; brief tests for the delivered rules; `ProjectRuleLoaderTest`
	for the path-trigger text.
- **Validation:** prompt author promotion and judge verdicts on a short Codex campaign compared with E1.
- **Exit:** unit tests pass; the full build passes.

### WI-26 — Partial promotion and attempt approaches (user ruling, 2026-10-02)

- **Promotion:** an attempt fixes a triggering test case when both of its runs there pass. It is promoted when it fixes
	at least one triggering test case and fails no regression test case that the working candidate passes; it is rejected
	as soon as no triggering test case can still be fixed or a regression is certain (`design.md` §2.7, D33). Winner
	selection among a weak round's accepted attempts is unchanged.
- **Streak rule:** a triggering test case counts as fixed only when every run passes and `p^k < 0.10`, where `k` is the
	run count and `p = (s + 1) / (n + 2)` from the working candidate's `s` passes in `n` outcomes on that test case (its
	representative failure, diagnostic reruns and any other outcome on the same test case). Runs start at 2 and are added
	one at a time while all pass and the streak is not yet unlikely (`design.md` §2.7, D35, D89).
- **Revert:** the §2.7 revert check and its effect on the round and the plateau (`design.md` §2.7, D35).
- **Minimality:** a reduction passes when it fixes every triggering test case that the attempt it reduces fixed and
	fails no regression test case that the working candidate passes.
- **Approaches:** each prompt author attempt of a category in a round receives the §2.7 approach for its position in
	that category's attempts; the prompt author brief and prompt carry it (`design.md` §2.7, D34). `prompt-author.md` step
	2 corrects an instruction in the file that states it, `AGENTS.md` and `CLAUDE.md` included (D30).
- **Closes:** R-D3 (rule change), R-D11 (reduction rule), R-D14, R-D15.
- **Tests:** `PromotionTest` (one of two triggering test cases fixed is promoted; none fixed is rejected early; a
	regression still rejects), `MinimalityTest` (a reduction that loses a fix fails; one that keeps every fix passes),
	`PromptAuthorTest` (each position's approach is rendered), `DriverTest` (a round's attempts receive distinct
	approaches; a partially fixing attempt becomes the working candidate).
- **Exit:** unit tests pass; the full build passes.

### WI-25 — Resolving conflicts with the user (user ruling, 2026-10-02)

- **Resolutions:** each contract author conflict gains at least two `resolutions` and the `recommended` one with its
	`reason`; the proposal's `Conflicts` section lists them with the recommended one marked (`design.md` §6.1, D31).
	`contract-author.md` takes the approved `### Conflicts` wording verbatim.
- **Agent flow:** `first-use.md` takes the approved step 2 and step 4 wording: the agent asks the user to resolve each
	conflict, naming the recommended resolution, never offers confirmation while one is listed, and passes the answers to
	`revise`.
- **Mid-campaign conflicts:** the prompt author manifest gains the `conflict` classification with the requirement ids,
	each quoted passage with its file, the resolutions and the recommended one. The prompt author brief carries the
	campaign's edits since confirmation so the prompt author can tell a conflict it inherited from one a campaign edit
	introduced. CAT rejects a `conflict` report whose quotation is absent from the snapshot confirmed with the contract. A
	valid report pauses the campaign: no new trials are scheduled, in-flight work finishes, and the choices are each
	resolution (recommended one marked, with its reason) and `stop`; `revise` with the user's own wording is also
	accepted. Choosing a resolution runs a revision with it as feedback and returns the revised proposal for confirmation;
	a running certification attempt then ends `not certified: stopped for contract revision`, and the confirmed revision
	starts a new lineage (`design.md` §2.7, §7, D32). `prompt-author.md` takes the approved step 1 and step 2 wording
	verbatim.
- **Closes:** R-C14, R-D13.
- **Tests:** resolution parsing and rendering; the prompt author conflict report accepted when its quotation is in the
	confirmed snapshot and rejected when only a campaign edit holds it; the pause and its choices; choosing a resolution
	yields a revision and, once confirmed, a new lineage; a running certification attempt ends
	`stopped for contract revision`.
- **Exit:** unit tests pass; the full build passes; E1 per harness shows the agent asking the user to resolve the
	motto conflict with a recommended resolution.

### WI-24 — Conflicting requirements block confirmation (user ruling, 2026-10-02)

- **Findings:** the contract author's findings gain a required `conflicts` list. Each entry names at least two contract
	requirements that no reply can satisfy together and the evidence: the file and the text that show the conflict. The
	contract author reports a conflict instead of settling which requirement prevails, and drops it from a revision once
	the user's feedback resolves it (`design.md` §6.1, D31).
- **Proposal:** a `Conflicts` section lists each conflict, or states that the contract author found none.
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
- **Discovery:** derivation finds module scenarios beside `.cat/tests/` ones, so the contract author receives them and a
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
- **Prompt author:** a failure record carries the same reply, so the prompt author sees the interim message that failed.
- **Stored format:** trial records and the campaign format that hold responses change format; older formats are
	refused, as before.
- **Closes:** R-C12.
- **Tests:** per harness, an adapter test over recorded events with a progress message before a tool call; a check
	test where a forbidden text appears only in an interim message and a suffix check still passes; a judge request test
	that shows every message; a prompt author failure-record test.
- **Exit:** unit tests pass; the full build passes; then E1 restarts on the final build.

### WI-60 — Minimum pass rate (user ruling, 2026-10-10)

- **Statistics:** every rule that fixed 95% takes the confirmed minimum pass rate `p_min`: `E↑` bets at
	`r = p_min + (1 − p_min)·c`; for the number of shortfalls `1 − p_min` below `p_min`, `n_short` = 9, 3, 1, `E↓`
	bets `λ = min(1, n_short·(1 − p_min)/p_min)` against the pass rate `p_min − n_short·(1 − p_min)`;
	cap calibration uses `r = 1 − (1 − p_min)/5`; and the commit gate, the review checkpoint and the baseline's extra
	50 trials scale with `1 − p_min` as `design.md` §2.1, §2.3–§2.5 and §2.7 state. Decisions stay exact rationals.
- **Contract:** the proposal holds `minimum_pass_rate`, between 0.80 and 0.99 with at most two decimal places, 0.95
	when the contract author proposes none other; CAT refuses another value. A changed `p_min` is a contract revision, and
	the certification record holds `p_min`.
- **Format:** proposal and campaign formats increase; older ones are refused.
- **Closes:** R-S10, and the `p_min` side of R-S2, R-S3, R-S6, R-D1, R-D5 and R-D7.
- **Tests:** `BettingProcessTest` (boundaries at `p_min` = 0.8, 0.9, 0.97 and 0.99, and the 0.95 fixtures unchanged),
	`CapCalibrationTest` (the all-pass minimums 22, 45, 93, 157 and 474 at `α = 2%`), `CommitGateTest`,
	`ReviewCheckpointTest`, `BaselineTest`, `ProposalSchemaTest` (range, decimals, default).
- **Exit:** unit tests pass; the full build passes.

### WI-61 — Scenario format and test cases (user rulings, 2026-10-10)

- **Loader:** `optimizer.contract` parses a prompt test directory: `scenario.md` with `## Variables` (exactly the
	columns `Name` and `Description`, single-line cells, `\|` escapes), `## Scenario` (one turn, or `### Turn <n>`
	subsections from 1), and `## Assertions` (`### <id>` subsections whose body starts with `kind: effect|guard` and an
	optional `tool:` line). It refuses a missing section, a duplicate or non-snake_case name or id, an unknown key line,
	a variable named `expected_outcome`, an assertion with that id, an undeclared placeholder and a prompt test with no
	variable. `{{name}}` is replaced only for declared names.
- **Test cases:** a test case holds one value per variable and an expected outcome. A value is text or a generator
	call `{"generator": "log_lines", "count": N}`; CAT adds `seed` and `version` when the test case is created, giving
	identical calls different seeds. A generated value is allowed only where a fixture's contents use it.
- **Generators:** `log_lines` version 1 expands `(seed, count)` with `java.util.Random`, built-in templates and a word
	list into UTF-8 lines ending in `\n`, with timestamps inside 2025. A generator name keeps every version; an unknown
	name or version is refused.
- **Fixtures:** `fixtures/` is copied to the trial workspace root with placeholders replaced in names and in UTF-8
	contents and other files copied unchanged; a file-name value must be one valid segment, and a collision with
	another fixture or the staged source is refused.
- **Values file:** `values.json` with `format_version` 1 and `test_cases`, each with `values` and
	`expected_outcome`; unknown fields are refused; it streams on read.
- **Regression scenarios:** `RegressionScenarios` loads directories in the new format from both locations.
- **Closes:** R-C20, R-C21, R-C22, and the loader side of R-I3.
- **Tests:** `ScenarioTest` (each section and each refusal), `TestCaseTest`, `LogLinesTest` (same bytes for a seed
	and version across runs, a fixed digest for one seed, timestamps within 2025, line count), `FixturesTest` (name and
	content substitution, binary file unchanged, invalid name, collision), `ValuesFileTest` (round trip, unknown field,
	format version), `RegressionScenariosTest`.
- **Exit:** unit tests pass; the full build passes.

### WI-62 — Split by expected outcome (user ruling, 2026-10-10)

- **Split:** a test case's part is the SHA-256 of the salt and its normalized expected outcome (Unicode NFC, trimmed,
	each whitespace run one space, lower case under `Locale.ROOT`), each part with probability ½. CAT draws the salt
	when it first validates a campaign's proposal and keeps it across revisions. A prompt test with fewer than 2 distinct
	normalized outcomes in either part is refused, naming the test and both counts. Test cases from a committed values
	file, and new ones sharing a committed outcome, belong to development.
- **Draws:** uniform over the prompt test's test cases within the part.
- **Added test cases:** after a failed attempt the contract author writes at least 4 per failed prompt test; CAT
	refuses one whose normalized outcome equals a held-out one, without saying which part holds it. This replaces the
	outcome-changing marks of WI-33 and the value checks of WI-34.
- **Closes:** R-C16, R-C17, and the part side of R-C4.
- **Tests:** `SplitTest` (stable under the salt, near ½ over many outcomes, normalization, committed test cases in
	development), `ProposalTest` (refusal at 1 outcome in a part), `DriverTest` (an added test case sharing a held-out
	outcome is refused; no held-out outcome or salt in the contract author input).
- **Exit:** unit tests pass; the full build passes.

### WI-63 — Built-in tools, trial record and the expected-outcome assertion (user rulings, 2026-10-10)

- **Built-in tools:** the check kinds become the built-in tools of `design.md` §6.4 with their hyphenated names, plus
	`file-absent`, `tool-unused`, `no-tool-call`, `read-in-full` and `read-at-most`. Arguments split on spaces with
	double quotes and `\"`/`\\` escapes, placeholders replaced after splitting, and an optional leading `turn=<n>` for
	built-in tools that examine replies. Known-good and known-bad controls and discarded checks are removed. Each failed
	built-in tool names its behavior.
- **Trial record:** the WI-28 evidence section becomes the trial record that both the built-in tools and the judge
	read; a built-in tool whose evidence the harness cannot supply is refused in the proposal.
- **Assertions:** a trial passes when every assertion passes. CAT adds `expected_outcome`, judged, a guard exactly
	when every other assertion is a guard. The judge returns a verdict, rationale and behavior per assertion it decides;
	a failed built-in tool skips the judge; the core-task requirement is removed.
- **Judge version:** increases in both harnesses, because the prompt, result schema and decision rule change.
- **Closes:** R-C5, R-C8, R-C9, R-C12, R-C18 (built-in tool behaviors), R-E21.
- **Tests:** `BuiltInToolTest` (each built-in tool on both sides, argument splitting and escapes, `turn=`),
	`TrialRecordTest` per harness over recorded events, `JudgeVerdictTest`, `JudgeProtocolTest`, `DriverTest` (no judge
	after a failed built-in tool), `ProposalTest` (the added assertion and its kind; an unsupported built-in tool
	refused).
- **Exit:** unit tests pass; the full build passes.

### WI-64 — Prompt tests replace categories (user ruling, 2026-10-10)

- **Model:** `Category` is removed. A cell is a prompt test under one profile; every count, gate, bet, cap, decision,
	allocation rule, round, approach list, review checkpoint, report and certification record that named a category
	names a prompt test or its cells. The profile set comes from the requirements that the proposal maps to the test's
	assertions; the mapping lives in the proposal.
- **Format:** proposal, campaign and certification-record formats increase; older ones are refused.
- **Closes:** R-C3, R-C10, R-C11, and the per-prompt-test side of R-D6, R-D12, R-D14, R-E3, R-E4, R-E19, R-E22,
	R-E23, R-F2, R-F6, R-M6, R-P1 and R-P4.
- **Tests:** the existing tests of those requirements, rewritten over prompt tests; `ProfileSetTest` (profile set from
	mapped requirements).
- **Exit:** unit tests pass; the full build passes.

### WI-65 — Control trials (user ruling, 2026-10-10)

- **Control source:** the incumbent's staged source with every target prompt file emptied to zero bytes; the proposal
	lists the emptied paths.
- **Statistics:** `E_ctl↑` and `E_ctl↓` per assertion over `Z = (X − Y + 1)/2` with `m = 0.475` and
	`f` = 0.25, 0.5, 0.75 and 0.9, as exact rationals, deciding at `1/α_i`.
- **Pairing:** the `j`-th control trial of a cell pairs with its `j`-th candidate formal trial in assignment order
	and runs that trial's test case; control trials start in a cell only after its minimum-pass-rate part certified
	(allocation rule 5); extra formal trials run when pairs need them; the pair cap is the cell's cap.
- **Outcomes:** a cell certifies when its minimum-pass-rate part and every control comparison pass; `E_ctl↓` above
	`1/α_i` ends the attempt `not certified: below control`; a comparison undecided at the cap leaves the attempt
	inconclusive. The report shows each assertion's pair counts and status.
- **Closes:** R-S11, R-F7, R-W10, and the control side of R-E3, R-E4 and R-F2.
- **Tests:** `ControlComparisonTest` (both decisions on fixed `Z` paths, exact boundaries), `SchedulerTest` (no control
	trial before the cell certifies; pairing order; extra formal trials), `DecisionTableTest` (below control),
	`StagedSourceTest` (emptied files), `StatusReportTest`.
- **Exit:** unit tests pass; the full build passes.

### WI-66 — Guard pilot (user ruling, 2026-10-10)

- **Pilot:** after a proposal validates and before CAT presents it, about 20 control trials per prompt test in its
	weakest cell, on development test cases only, decided by the proposed decision procedure. A revision reruns it only
	for prompt tests whose scenario or test cases changed. Pilot trials are targeted: they enter no count and spend no
	allowance.
- **Proposal:** shows each assertion's pilot pass rate and warns on a guard below the minimum pass rate `p_min`; it
	lists why each failed run ended without a decision; the revise brief carries the latest pilot results.
- **Closes:** R-C23, and the pilot side of R-C2.
- **Tests:** `GuardPilotTest` (control trials in the weakest cell, tallies, reuse, a rerun of changed tests only, a
	stop on a rate limit), `OptimizerCoreTest` (the pilot runs before presentation, adds to no count, and its brief
	reaches the revision), `ProposalTest` (rates and the warning).
- **Exit:** unit tests pass; the full build passes.

### WI-67 — Delivery of test files (user ruling, 2026-10-10)

- **Delivery:** a certified delivery and keeping the incumbent write each confirmed prompt test's `values.json`, with
	every test case of the campaign, and the `scenario.md` and `fixtures/` of every prompt test that the contract author
	created or edited, through the journaled steps of WI-11. Staging excludes the confirmed contract's prompt test
	directories. A campaign that stops without a delivery writes none of them.
- **Location:** a prompt test that the contract author created is written under the campaign target's test
	directory, which the regression-scenario location rules derive from the target, even when its guard assertions check
	behavior that other guidance governs (user ruling, 2026-10-10).
- **Closes:** R-W11, and the staging side of R-W5.
- **Tests:** `DeliveryTest` (both endings, a fault between writes, no write on stop), `StagedSourceTest` (test
	directories excluded).
- **Exit:** unit tests pass; the full build passes.

### WI-68 — Role prompts for prompt tests (user rulings, 2026-10-10)

- **Contract author:** proposes the minimum pass rate `p_min`, the requirement-to-assertion mapping, scenarios in the
	new format (converting a recorded scenario in an earlier format and showing it as an edit), fixtures, and test cases
	with values and expected outcomes; its instructions say that `tool:` names CAT's check, list the generators with their
	words and date range, and include the latest pilot results on revision.
- **Judge and prompt author:** the judge decides assertions; prompt authors receive failed assertions, behaviors and
	check results; neither edits a prompt test.
- **Wording:** every prompt wording change is shown to the user before delivery.
- **Closes:** the prompt side of R-C1, R-C5, R-C18, R-C20 and R-C23.
- **Tests:** the prompt rendering tests of each role.
- **Exit:** unit tests pass; the full build passes.

### WI-69 — Scenario conversion (user rulings, 2026-10-10)

- **read-file:** convert the Codex `read-file` scenarios to the new format with shared fixtures, drop the sentence
	"This is an application test.", and rewrite the search question so that each release has one matching line.
- **Other scenarios:** convert every other recorded scenario under `client/**/src/test/resources/` and `.cat/tests/`,
	and update `failure-regression-promotion.md` to record scenarios in the new format.
- **Closes:** the conversion side of R-I3.
- **Tests:** `RegressionScenariosTest` loads every recorded scenario without a refusal.
- **Exit:** unit tests pass; the full build passes.

### WI-70 — Paged command output for Codex (user request, 2026-10-11)

- **Programs:** ship `run-part` and `read-part` as Java launchers of the Codex image (user request, 2026-10-11: Java
	rather than bash). Like every jlink launcher (user requests, 2026-10-11), they share the caller's `/tmp`, so saved
	output outlives the call, with only the JVM's performance-data directory private, and they run Java in a UTF-8
	`LC_CTYPE` when the caller's is not UTF-8, so Java can name files that are not ASCII; `run-part` restores the caller's
	locale for the command. The plugin ships `bin/run-part` and `bin/read-part`, which start those launchers from the
	plugin's `client/bin` (user ruling, 2026-10-12), so agents run them from `${CAT_PLUGIN_ROOT}/bin`. `run-part` runs the
	command on standard input, saves its output and exit status in a new file under the nearest `.cat/work/temp` at or
	above the command's folder (user ruling, 2026-10-12: no trial writes outside the project), or under `${TMPDIR}` when
	there is none, and prints the first part; `read-part` prints a later part. Each part fits Codex's 10,000-token exec
	budget after JSON escaping and ends with a bracketed line that names the command printing the next part, or with
	`[End of …]` and the exit status.
- **Hook:** add a Codex PostToolUse hook that, when Codex cut a `run-part` or `read-part` result because the agent set
	`max_output_tokens`, replaces its last bracketed line with an instruction to rerun that command without the limit;
	every other result passes unchanged. Register and trust it beside the existing hooks.
- **Delivery:** the rules stay inline in the SessionStart context; no rules file or pointer is added.
- **Rule:** replace the text of `read-file.md` with the certified candidate's guidance only after WI-71 certifies it,
	showing the wording to the user first; then retire `client/bin/read-file`, its bats suite and fixtures, and its
	distribution entry.
- **Tests:** unit tests for both programs (part sizes after JSON escaping, quotes, control bytes and characters that
	are not ASCII, long lines, reassembly, exit status, usage errors, a missing or binary file, the caller's locale);
	unit tests for the hook (a cut part, an uncut part, another command); `DistributionMain` launches both; a bats test
	runs the plugin's `bin/run-part` and the next-part command it names.
	One bats test covers the template of every jlink launcher (user request, 2026-10-11: no bats test per program): a
	launcher run through Bubblewrap and one run directly start from a path with spaces in the C locale, the latter naming
	a file that is not ASCII.
- **Exit:** unit tests and bats pass; the full build passes.

### WI-71 — Read-file prompt tests (user rulings, 2026-10-08 and 2026-10-11)

- **Commit gate:** before certification, the candidate passes the commit gate in each certification cell (inline
	rules, 100 runs per task, reading at 99% or more and the latest two batches of 50 at 98% or more; answers no worse
	than no prompt).
- **Requirements (user rulings, 2026-10-13):** from the goal that the agent reads a file without truncation and that
	the prompt harms neither other operations, such as searching, nor lazy loading: a file the task needs reaches the
	agent whole; a long command output reaches the agent whole; a lazily loaded guide is loaded and reaches the agent
	whole; a search is not turned into a whole read; no command runs when none is needed; and each answer is no worse
	than without the prompt, on easy cases.
- **Prompt tests:** a whole long document, one lazily loaded guide read whole, a long command output, a question
	answered from general knowledge without reading, and a search whose guard test cases the agent passes almost always
	without the prompt. Every scenario is organic: it never tells the agent the expected outcome or that it is being
	tested. Each prompt test certifies its reading obligations as effects: the long document, the lazily loaded guide and
	the long command output reach the agent whole, and the general-knowledge question runs no command. Its answer is a
	guard: a text check that recognizes the common correct replies, with `on_tool_failure: judge`, on test cases easy
	enough that the agent answers correctly at least 95% of the time without the prompt, which the guard pilot confirms;
	the answer each test asks for therefore lies where a truncated read or a search still finds it (user ruling,
	2026-10-11). The scenarios no longer prescribe how the agent reads (through a named script, each line once) or check
	the reply's sign-off, which sits where a truncated read can miss it. Where a scenario does judge a drafted customer
	reply, a note to the requester after the draft, such as a statement that no files were edited or a source link, is not
	part of the reply (user ruling, 2026-10-11).
- **Certification:** `gpt-6-luna` at `low`, at `high`, then `gpt-6.1-sol` at `low`, each at `p_min` 0.95, with the
	readied candidate as attempt 1.
- **Exit:** certified in all three cells; WI-70's rule step then runs.

### WI-72 — Judge on tool failure (user rulings, 2026-10-11)

- **Scenario format:** an assertion may declare `on_tool_failure`, `fail` (the default) or `judge`; `judge` needs a
	`tool`. The proposal schema carries it as `on_tool_failure`, and `scenario.md` writes it only when it is `judge`.
- **Decision:** a built-in tool that passes still decides its assertion. A failure under `fail` ends the trial without a
	judge, as before; a failure under `judge` adds the assertion to the judge's list, with the tool's result as evidence
	rather than a verdict. The guard pilot decides the same way.
- **Prompts:** the judge prompt says which tool results are evidence only; the contract author prompt says when to give
	`judge`. The judge version increments in both harnesses.
- **Exit:** `ScenarioTest`, `ProposalSchemaTest`, `JudgePromptTest`, `DriverTest` and `GuardPilotTest` cover each side;
	the read-file lazy prompt test's answer assertion can use `judge`.

### WI-73 — Plugin under test (user ruling, 2026-10-11)

- **Contract:** the proposal schema carries an optional plugin under test: a plugin root (a project directory holding
	`.codex-plugin/plugin.json` or `.claude-plugin/plugin.json`), or a base directory with mappings from project source
	directories to plugin directories; and whether trials install CAT, with the contract author's evidence. The contract
	author prompt says how to find the plugin, its mappings and its dependency on CAT. For WI-71, the mapping is
	`client/codex/rules/src/main/resources/rules/codex/` onto the plugin's `rules/codex/`, and the base is the installed
	Codex CAT artifact; the common rules module is not a one-to-one copy, because its build moves the path-scoped rules.
- **Validation:** a mapping whose source file has no file at its plugin path in the base, or in-project prompt files in
	two plugins, is refused; a mapped file whose content differs from the base's is a warning; the proposal lists the plugin,
	the differing files, unapproved hooks and the CAT decision.
- **Staging:** CAT copies the base into campaign storage at confirmation; each staged source, and the control source,
	gets a plugin copy with its mapped files written over the base copy, so a candidate's edit and an emptied control
	file reach it.
- **Runners:** `CodexRunner` and `ClaudeRunner` install a given plugin directory into the trial home under the
	manifest's name, reuse the user's hook approvals for it, and install CAT only when asked; when the plugin under test
	is CAT, its copy is the installed CAT, including Codex's agent definitions. Role sessions still run without plugins
	and read their rules from the installed CAT artifact.
- **Identity:** the plugin root, or the base copy's identity and the mappings, and whether trials install CAT, are
	material conditions.
- **Closes:** R-W12.
- **Tests:** `ProposalSchemaTest` (both forms, an unknown key), `ProposalTest` (the refusals, the warning, the listed
	plugin), `StagedSourceTest` (a candidate edit and an emptied control file in the plugin copy, the base unchanged),
	`MeasurementIdentityTest` (each plugin condition material), `CodexRunnerTest` and `ClaudeRunnerTest` (the plugin
	copy installed, CAT installed only when asked, hook approvals of the plugin reused), `CodexSessionsTest` and
	`ClaudeSessionsTest` (trial settings carry the plugin copy, role sessions unchanged).
- **Exit:** unit tests pass; the full build passes; in WI-71's campaign, a control trial's installed `read-file.md` is
	empty and a candidate trial's equals the candidate's.

### WI-74 — Initial project (user ruling, 2026-10-12)

- **Problem:** the reading tools and the file changes compared a trial with its staged source, which holds no fixtures,
	so `read-in-full` of a fixture was undecidable and the judge saw every fixture as a file that the trial created.
- **Runners:** `HarnessRunManager.snapshotProject` copies the run's project, after the fixtures and before the Git
	baseline, to `initial-project` beside it; `CodexRunner` and `ClaudeRunner` call it for every run.
- **Evidence:** a completed session carries its initial project; the trial record's starting file text and
	`FileChanges` read it, and a retained failure stores it, so `campaign.json` moves to format version 38.
- **Closes:** R-E21's comparison with the workspace that the trial started with.
- **Tests:** `HarnessRunManagerTest` (the snapshot keeps the project and fixtures after the project changes),
	`TrialEvidenceTest` (a fixture's starting text is read, and fixtures are not file changes).
- **Exit:** unit tests pass; the full build passes; the read-file pilot decides `read-in-full` of a fixture.

### WI-75 — Requirements from the goal, and test revision (user rulings, 2026-10-13)

- **Problem:** the first read-file certification start gave the contract author neither the target nor the user's
	goal, and sent it all 94 recorded regression scenarios in a 608 KB prompt. Its proposal tested how the bundled
	scripts work rather than whether a file reaches the agent whole; its correction turn learned of the missing scenario
	citations only after its schema problem was fixed, so the second refusal ended the start; and its second start timed
	out. A test that failed for a reason in the test could only be classified *not guidance*, which waits for the user.
- **Start:** `start` takes an optional goal and stores it with the campaign; the skill passes the user's own words.
	`start` refuses a target that is not a regular file, naming it, because a campaign optimizes one prompt file.
- **Control source:** control trials and the guard pilot empty only the target prompt file; every other prompt file
	stays as the incumbent has it, so control trials measure the agent that the user would have without the target.
- **Contract author:** its prompt names the target prompt file, holds the goal and only the target's recorded
	regression scenarios, and states the requirement derivation of `design.md` §6.1 and the test design rules of §6.3. A
	requirement's source is the goal or the prompt file that states it; with a goal, a requirement whose source is a
	prompt file is an extra requirement, which the proposal marks and the user drops through `revise` feedback.
- **Validation:** CAT refuses a requirement that no prompt test proves, requires citations only of the target's
	recorded scenarios, and reports every problem at once, checking the citations even when the schema parse fails.
- **Failure analysis:** the prompt author's *test defect* classification and its manifest check; the test revision
	session, its validation, new test cases and guard pilot, the new lineage, the attempt outcome
	`not certified: stopped for test revision`, the return of a sound test's kind, the pause for an unprovable
	requirement or a requirement change, and the test revision before a review checkpoint stops a cell.
	`campaign.json` moves to format version 39.
- **Closes:** R-C24, R-C25, R-D19, the narrowed control source in R-W10 and the new classification in R-D10.
- **Tests:** `ProposalTest` (a requirement without a prompt test refused; only the target's scenarios sent and required;
	the goal and target in the prompt; schema and citation problems reported together; extra requirements marked; the
	control source lists only the target), `CommandsTest` and `OptimizerCoreTest` (`start --goal` stores the goal and
	passes it to the contract author; `start` refuses a directory; the guard pilot's control source empties only the
	target; `run` revises a test under a new lineage, records a sound test, pauses on a requirement change whose proposed
	change revises the contract, and refuses a revision that changes requirements or reuses a test case),
	`CampaignStoreContract` (the goal survives a reload), `PromptAuthorManifestTest` (a test defect kind without its rule
	rejected), `DriverTest` and `CampaignTransitionsTest` (a test revision's lineage, attempt outcome, sound report and
	pause), `StatusReportTest` (the revision's difference and reasons), `StagedSourceTest` (the control source empties
	only the target).
- **Exit:** unit tests pass; the full build passes; WI-71's certification start, given the user's goal, presents a
	proposal within its time limit whose requirements are WI-71's.

### WI-76 — Control source without the target's dependents (user ruling, 2026-10-13)

- **Problem:** the control source empties only the target, so a hook or route that exists only to serve the target,
	such as the Codex PostToolUse hook that repairs `read-part` output, still runs in control trials.
- **Proposal:** the contract author lists each dependent of the target: its path, the part it removes (a whole file, a
	hook registration, or an exact span) and the target instruction it serves; it lists nothing that still has a purpose
	without the target. CAT refuses an entry that names the target, a path outside the prompt system and the plugin under
	test, or a span that does not occur exactly once, and reports every problem at once. The proposal shows the list.
- **Control source:** the target emptied to zero bytes, each listed file deleted, each listed hook registration removed
	from the plugin copy's hook configuration, each listed span deleted; every other file unchanged. The guard pilot and
	the formal control trials use it. The control-source limitation names only references in files that stay.
- **Closes:** R-W10's dependents.
- **Tests:** `ProposalSchemaTest` (the dependents field round trip and an incomplete entry refused), `ContractTest`
	(ambiguous entries refused; plugin-root and built-plugin removals), `ProposalTest` (the list shown; a removal problem
	blocks confirmation), `ControlSourceTest` (each removal kind, a file that mentions the target unchanged, and each
	refusal), `DependentRemovalTest` (each removal kind on file content), `StagedSourceTest` (a built plugin's control copy
	without its dependents, and a missing dependent refused), `DriverTest` (the committed control snapshot, and control
	trials' plugin copies), `GuardPilotTest` (the pilot's control trials install the copy without dependents, and a
	changed list reruns the pilot).
- **Exit:** unit tests pass; the full build passes; WI-71's proposal lists the Codex PostToolUse hook as a dependent of
	the Codex read-file rule.

### WI-77 — Per-assertion comparison with the incumbent (user ruling, 2026-10-13)

- **Problem:** a candidate is compared with the incumbent only on whole trials, against frozen bounds from unpaired
	baseline trials, so it can certify while passing one assertion less often than the incumbent did.
- **Incumbent trials:** in a cell whose minimum-pass-rate part certified, and only when the candidate is not the
	incumbent, the `j`-th incumbent trial runs the incumbent on the test case of the cell's `j`-th candidate formal trial;
	extra formal trials run to complete pairs up to the cap. Incumbent trials count only toward the incumbent comparisons.
- **Comparison:** `E_inc↑` and `E_inc↓` are the control comparison's e-values over the incumbent pairs, with the same
	5-point margin and decided at `1/α_i`. A cell certifies only when every assertion's control and incumbent comparisons
	pass; `E_inc↓` above `1/α_i` ends the attempt `not certified: below incumbent`, which returns to development with
	the brief's `below_incumbent` entries; when one assertion is below control and another below incumbent at the same
	adoption, the attempt ends `below control`.
- **Reports:** the status report, the minimum remaining trials, the certification record and the campaign snapshot
	carry each cell's incumbent pairs beside its control pairs.
- **Closes:** R-F8, and the incumbent side of R-E3, R-E4 and R-F2.
- **Tests:** `SchedulerTest` (no incumbent trial before the cell certifies or when the candidate is the incumbent;
	pairing order; extra formal trials for incumbent pairs; the cell limit), `DecisionTableTest` (the incumbent
	comparison is the control comparison's statistic; below incumbent; below control first; certification waits for the
	incumbent; inconclusive at the cap), `CellTierTest` (a cell below incumbent returns to development), `DriverTest`
	(certification waits for the incumbent comparisons, whose trials stage the incumbent), `StatusReportTest`,
	`RemainingWorkTest` (incumbent pairs, sharing formal trials with control pairs), `CertificationRecordTest`,
	`EndedAttemptTest` and `PromptAuthorTest` (the brief's `below_incumbent` entries).
- **Exit:** unit tests pass; the full build passes.

### WI-78 — Comparisons implied by the minimum pass rate (user ruling, 2026-10-14)

- **Problem:** at `p_min ≥ 0.95` a true minimum-pass-rate claim already makes every control and incumbent comparison
	true, yet each certified cell still runs paired trials for them, and a guard that both trials pass almost always can
	leave the attempt inconclusive at the cap.
- **Condition:** one place decides whether a campaign compares assertions: only when `p_min` is below
	`1 − PairedComparison.MARGIN`, 0.95.
- **Certification:** when it does not, a cell certifies as soon as its minimum-pass-rate part certifies; no control or
	incumbent trial and no extra formal trial for pairs is scheduled, and the minimum remaining trials count none.
- **Reports:** the status report and the certification record say that the comparisons follow from the minimum pass
	rate instead of listing each assertion's comparison as `not run`.
- **Unchanged:** below 0.95, every comparison, decision and cost of WI-65 and WI-77.
- **Closes:** R-F9, and the `p_min` condition of R-F7 and R-F8.
- **Tests:** `DecisionTableTest` (at 0.95 a cell certifies on its minimum-pass-rate part with no pair; below 0.95 it
	still waits for the comparisons), `SchedulerTest` (no control, incumbent or extra formal trial at 0.95),
	`RemainingWorkTest`, `StatusReportTest`, `CertificationRecordTest`, `DriverTest` (an attempt at 0.95 certifies with
	no control or incumbent session).
- **Exit:** unit tests pass; the full build passes.

### WI-79 — Revert by non-overlapping intervals (user ruling, 2026-10-14)

- **Problem:** the revert check compares the promoted candidate's upper bound with its predecessor's observed rate,
	which carries the predecessor's own sampling error, so it withdraws a candidate as good as its predecessor 25–36% of
	the time.
- **Rule:** revert when the promoted candidate's development 95% Wilson upper bound in an addressed cell is below the
	predecessor's development 95% Wilson lower bound in that cell.
- **Storage:** each revert record holds the predecessor's lower bound in place of its observed rate; the campaign
	format version rises and the launcher fixture follows.
- **Closes:** R-D15.
- **Tests:** `RevertTest` (no revert while the intervals overlap, even below the predecessor's observed rate; revert
	once they do not), `FileCampaignStoreTest` round trip.
- **Exit:** unit tests pass; the full build passes.

### WI-80 — Commit gate (user ruling, 2026-10-14)

- **Problem:** "readiness gate" does not say what the gate permits, and "certification gate" would suggest that
	passing it certifies.
- **Rename:** the gate, its class, its remaining-work kind, its report labels and its status JSON key use "commit
	gate". The Git replay classes and the rules resources, which use "readiness" for another concept, are unchanged.
- **Closes:** R-D1's name.
- **Tests:** `CommitGateTest` (renamed), `RemainingWorkTest` and `StatusReportTest` (the new kind, labels and key).
- **Exit:** no optimizer source, test or fixture names the readiness gate; unit tests pass; the full build passes.

### WI-81 — Trial plugins from the prompt test configuration (user rulings, 2026-10-15)

- **Problem:** trials installed the plugin under test that the contract author proposed, and CAT only when the author
	cited evidence that the plugin depends on it, so a plugin that ships none of the target's files never reached trials.
	The author read the distribution build to find the mappings, and a built plugin's base was the user's installed
	copy, which on 2026-10-15 lacked a fix that the build output held (`design.md` §4.4, D78).
- **Configuration:** a target's prompt test configuration is `prompt.json` in its test directory (`design.md` §8): one
	key per harness, `claude` or `codex`, each holding `plugins`, a list of entries with `name` and an optional `build`
	holding `output` (a project directory relative to the project root) and an optional `command` (a non-empty argument
	list run from the project root). Unknown keys are refused with their JSON path. Staging leaves the configuration out,
	as it leaves out the prompt test directories.
- **Start:** before creating anything, `start` refuses a target whose test directory has no configuration, or whose
	configuration has no key for the harness running `start`, naming the file to write; and a listed plugin without
	`build` whose name matches a plugin manifest tracked in the project. After adding the optimization worktree, `start`
	runs each `build.command` there and stops, showing the exit status and the end of the output, when a command fails or
	an output lacks the harness's plugin manifest or names another plugin; the target is then startable again, as after a
	failed derivation. A plugin without `build` is found through the harness's install records (Claude Code's
	`installed_plugins.json`; Codex's `config.toml` and plugin cache), and `start` refuses unless exactly one installed
	copy matches. Each output or installed copy is copied into campaign storage as the plugin's base. A revision neither
	rebuilds nor recopies.
- **Mappings:** before the contract author starts, CAT maps each project directory of the prompt system onto each
	project-built base: to the base directory that holds a file at each of its files' relative paths, choosing the one
	with the most identical files, or else each file on its own to the base path that holds an identical copy. It lists a
	participating in-project prompt file that does not load from the project and that no mapping covers, warns on a
	mapped file whose content differs from the base's, refuses a campaign whose target would not load in trials, and
	lists every other prompt file that loads in production but not in trials. A file may map into several plugins.
- **Contract:** the proposal schema drops `plugin_under_test`, and the contract author prompt drops its plugin
	instructions; the campaign records the trial plugins, each with its name, base digest, base source (build command or
	installed copy) and mappings. Dependents may name a path in a project-built trial plugin and no longer in an
	installed one. The proposal shows the plugins as `design.md` §6.1 lists them. The proposal and campaign format
	versions increase, and the launcher fixture follows.
- **Staging and runners:** each staged source, the control source included, has a copy of each project-built trial
	plugin, its base with its mapped files written over it and, for the control source, its dependents removed.
	`CodexRunner` and `ClaudeRunner` install exactly the listed plugins into the trial home, a project-built plugin from
	its staged copy and any other from its base copy, and no other plugin: CAT runs in a trial only when the configuration
	lists it. Each plugin's hook approvals are reused, and an unapproved Codex hook is a proposal limitation. Role sessions
	are unchanged.
- **Identity:** each trial plugin's name and base identity, and the build command and mappings of each project-built
	one, are measurement conditions, replacing the plugin-under-test conditions.
- **Configurations:** add `client/codex/rules/src/test/resources/codex/read-file/prompt.json`, listing CAT under
	`codex` with the project's build command and the Codex distribution output, for WI-71. WI-15's fixtures each gain a
	configuration in WI-15.
- **Closes:** R-W12 as reworded, R-E20's trial side, and R-W10's narrowed dependent paths.
- **Tests:** `PromptConfigurationTest` (each form, each refusal with its JSON path), `CommandsTest` and
	`OptimizerCoreTest` (`start` refuses a missing configuration or harness key before creating anything, naming the file,
	and an unbuilt plugin that the project tracks; the build runs in the optimization worktree and never reads the base
	worktree's build output; a failed command, a missing manifest and another plugin's name stop `start` with the exit
	status and the end of the output and leave the target startable; a revision does not rebuild),
	`InstalledPluginsTest` in each harness (one copy, none, two), `PluginMappingTest` (a directory mapping, the most
	identical directory chosen, the single-file fallback, an uncovered file listed, a file in two plugins, a differing
	file warned, an unloadable target refused), `StagedSourceTest` (one copy per project-built plugin, the control copy
	without dependents, the configuration excluded), `ProposalSchemaTest` (`plugin_under_test` refused as unknown),
	`ProposalTest` (the plugins shown), `MeasurementIdentityTest` (each plugin condition material), `CodexRunnerTest` and
	`ClaudeRunnerTest` (exactly the listed plugins, two plugins, CAT only when listed, hook approvals reused),
	`CodexSessionsTest` and `ClaudeSessionsTest` (trial settings carry the plugins, role sessions unchanged). A test that
	runs a build command runs a fixture program, never Maven.
- **Exit:** unit tests pass; the full build passes; in WI-71's start, CAT builds itself on the fork commit and the
	proposal names that build output as CAT's base with the mapping of `client/codex/rules/src/main/resources/rules/codex/`.

### WI-82 — Prompt tests as files and contract tools (user rulings, 2026-10-15)

- **Problem:** the read-file certification start of 2026-10-15 left no proposal: the contract author reached its
	30-minute limit after re-typing the turns, variables and assertions of the four recorded scenarios, about 5,000
	characters, into the proposal JSON and validating nothing until the session ended (`design.md` §6.1, D77).
- **Scenario format:** `scenario.md` gains `## Expected Behavior` with `description` and `time_limit_seconds`, between
	`## Scenario` and `## Assertions`. A recorded scenario without it stays readable; CAT refuses a proposal whose prompt
	test lacks it. A prompt test's expected behavior and time limit come from its scenario, not from the proposal.
- **Draft directory:** before each contract author session, CAT creates a new draft directory in the session's run
	directory and copies into it, at its test path, the directory of every recorded prompt test of the target: scenario,
	`fixtures/` and values file. A revision's draft starts as a copy of the latest proposal's draft; a test revision's
	draft holds a copy of the prompt test it revises. The request names the draft directory and the proposal file, and
	asks the author to write each file once it settles it, in the turn that inspects the prompt system; the separate
	output turn is removed, and the correction turn edits the same files. When the session ends, CAT validates the
	proposal and the draft, and moves the draft into the held-out directory beside the proposal. A session that reaches
	its time limit stores no proposal and leaves its files in its run directory for diagnosis.
- **Proposal file:** holds only the requirements, each prompt test's directory in the draft with its risk rank, the
	requirements it proves, the requirements whose trigger it leaves absent, its applicability, the requirement each
	assertion checks and any interaction it cannot cover, and the minimum pass rate, the judge's rubric and decision
	rule, the dependents, the conflicts, the limitations and the ignored paths needed as trial fixtures. Test cases live in
	the values files; a new test case has no seed, and validation gives each generated value its seed and version and
	writes them into the draft's values file. The proposal format version increases.
- **Request:** besides the files that load in trials with their rules, the request lists the trial plugins with
	each project-built one's mappings (WI-81), every hook registration of the prompt system and of the trial plugins with
	its event, matcher and command, the draft directory, the proposal file and the absolute path of the launcher that
	provides the contract tools.
- **Contract tools:** the launcher's `contract check` applies every session-end validation to the proposal file and the
	draft directory as they stand, prints every problem at once with its file and line or its JSON path, prints each
	prompt test's number of distinct expected outcomes without splitting them into parts, and exits non-zero when it
	finds a problem. `contract fixtures` writes one test case's workspace files into a directory that the author names,
	the fixtures that its patterns select with its values substituted and its generator calls expanded, and prints each
	file's path, size in bytes, line count and comparison with the tool-output limit of each trial profile of the prompt
	test. Both run inside the contract author's sandbox, read nothing in the held-out directory and write only below the
	directory they are given; the skill does not use them.
- **Presentation and delivery:** the proposal shows each prompt test from the draft as recorded, created, or recorded
	and edited with a difference from the recorded files, with its test cases and the number of distinct expected outcomes
	in each part; delivery writes the confirmed draft's prompt tests.
- **Prompts:** the contract author prompt, its correction turn, the test revision prompt and `prompt-tests.md` describe
	writing prompt tests as files in the draft, the contract tools, and writing each file as it is settled. If the judge's
	rendered input changes, the judge version increases in both harnesses (D50).
- **Closes:** R-C26, R-C27, and R-C20's Expected Behavior section.
- **Tests:** `ScenarioTest` (the section read, a scenario without it read, a malformed section refused at its line),
	`ProposalSchemaTest` (the narrowed proposal; a restated prompt test refused as an unknown key), `DraftDirectoryTest`
	(seeding at test paths, a revision's copy, a test revision's copy, the move into the held-out directory, files kept
	after a time limit), `ProposalTest` (validation reads the draft; seeds written back; recorded, created and edited
	marked with differences; a missing recorded prompt test and a missing Expected Behavior refused; problems carry file
	and line or JSON path), `ContractCheckTest` (the same problems as session-end validation; counts without parts),
	`ContractFixturesTest` (files, sizes, line counts, each profile's limit; nothing written outside the named
	directory), `CommandsTest` (both commands listed and parsed), `TestRevisionTest`, `DeliveryTest` (prompt tests from the
	draft), `OptimizerCoreTest` (`start` seeds a draft and stores it in the held-out directory; a timed-out session stores
	nothing), `CodexSessionsTest` and `ClaudeSessionsTest` (the contract author session may run the launcher and write the
	draft and the proposal file).
- **Exit:** unit tests pass; the full build passes; WI-71's certification start, given the user's goal, presents a
	proposal within the contract author's time limit.

### WI-83 — Per-session work directories (user request and rulings, 2026-10-16)

- **Problem:** every session of a project writes into one shared `.cat/work/temp`. The SessionStart hooks put each
	session's rules in `claude-context/` or `codex-context/` there, agents create their temporary directories there, and
	`run-part`, the runner commands and the Git replay editor write there. Nothing ties a directory to the session that
	created it, so nothing can remove a session's files once the session is gone (`design.md` D79).
- **Layout:** each session gets `.cat/work/<session-id>/` for the files CAT writes for it, and
	`.cat/work/<session-id>/temp/`, which is the session's `CAT_TEMP`. The session's rules file moves to
	`.cat/work/<session-id>/rules.md`, and each Claude subagent's to `.cat/work/<session-id>/subagents/<agent-id>.md`.
	`<session-id>` is the harness's session ID in canonical UUID form; CAT refuses any other form, so a session directory
	never takes the name of another entry of `.cat/work`.
- **One owner:** a new `SessionWorkDirectory` in `com.holdmyspot.cat.common` is the only code that names `.cat/work`
	or builds a session directory's path. Hooks obtain it from `HookScope` with the hook input; commands obtain it from
	`ToolScope`, which reads the session variable. Its first use creates the directory and `temp/`, and writes the marker
	when none exists: it writes a temporary file in the directory and moves it into place atomically, so concurrent first
	uses, such as a hook and a command, leave one complete marker, and it never overwrites a marker. It provides the
	paths inside the directory (`temp/`, the rules files, and WI-84's campaign storage) and the sweep. The
	harness-specific parts, the session variable, the default configuration directory and the transcript lookup, come
	from a per-harness value that the harness's scopes supply. `WorkDirectoryAuditTest` fails when any other main source
	file spells `.cat/work`; code that only excludes the work root, such as `InventoryScanner`, `Fork`, `ForkView` and
	staging, takes it from a `SessionWorkDirectory` constant.
- **Marker:** `.cat/work/<session-id>/session.json` holds the harness, the harness's configuration directory as an
	absolute path (Claude: `CLAUDE_CONFIG_DIR`, else `$HOME/.claude`; Codex: `CODEX_HOME`, else `$HOME/.codex`) and the
	creation time in UTC.
- **Session ID:** hooks read the hook input's `session_id`. Commands read the variable that the harness sets in tool
	shells, `CLAUDE_CODE_SESSION_ID` or `CODEX_THREAD_ID`, as the optimizer's interrupt signals already do. A command that
	writes into `CAT_TEMP` (`run-part`, the runner commands, the Git replay editor) uses the session directory of the
	nearest CAT project. Without either variable, `run-part` keeps its fallback to `TMPDIR`, `TEMP` or the JVM's temporary
	directory; a runner command needs `--run-root`, and the Git replay editor refuses; each refusal names both variables.
- **Premise check, first:** in each harness, record the `session_id` of the SessionStart and SubagentStart hook inputs,
	the session variable in the tool shells of the main agent and of a subagent, the `session_id` after a resume, the
	configuration-directory variables that hooks and tool shells see, and whether the transcript exists when SessionStart
	runs. Claude subagents are expected to share the parent's session ID, and so its directory. The check decides which
	directory a Codex subagent's injected rules name: the one that the subagent's own commands resolve.
- **Sweep:** after the SessionStart hook obtains its own directory, on every source (startup, resume, clear, compact),
	it removes every other session directory whose marker is more than 24 hours old and whose transcript the marker's
	configuration directory no longer holds: for Claude, `projects/*/<session-id>.jsonl`; for Codex,
	`sessions/**/rollout-*-<session-id>.jsonl` or `archived_sessions/rollout-*-<session-id>.jsonl`, since an archived
	session can be unarchived. It handles markers of both harnesses. It never removes a directory without a marker. A
	failed removal is reported on standard error and does not fail the session start. SessionEnd still deletes only the
	session's rules files; a resume writes them again.
- **Handoffs:** an agent hands a file to another session by its absolute path. The receiving session reads it in place,
	or moves it into its own session directory to take over its cleanup.
- **Saved output (user request, 2026-10-16):** the Codex PostToolUse hook deletes a `run-part-*` folder once the agent
	has seen all of its output. It deletes when a `read-part` or `run-part` result that Codex did not cut ends with
	`[End of <path>]`, and only when `<path>` is `output.txt` in a `run-part-*` folder directly inside a `temp` directory
	that `SessionWorkDirectory` provides or that `run-part` falls back to, with `output.txt.exit-status` beside it, so a
	file that the agent named itself is never deleted. When the hook repairs a cut part, it records the part's position
	in the folder, and clears it when that part comes back uncut; it does not delete while a recorded position remains,
	so re-reading a cut part still works. A failed deletion is reported on standard error and leaves the result
	unchanged. A result whose command the hook cannot read, such as a Code Mode script's, and output whose last part the
	agent never reads stay until the sweep removes the session's directory. Parts are not deleted one by one: they are
	ranges of one file that `read-part` sizes at each call.
- **Rule text:** the injected `CAT_TEMP` statements (`cat-env.md` for Claude; `temporary-directory.md` for Codex on
	POSIX and Windows) name the session's directory, with the session ID filled in by the hook. Update
	`.cat/rules/include/workflow-temporary-resources.md`, the Codex hooks README, and the Javadoc of `HookScope`,
	`ToolScope` and `CodexRunPart`. These texts load into every trial of the read-file target, so WI-83 lands before
	WI-71's next certification start.
- **Unchanged:** CAT stops writing `.cat/work/temp`, and neither reads nor deletes what it holds; the user removes it.
- **Build:** `client/pom.xml`'s `cat.work.temp` becomes the `temp` directory of the session that runs the build, found
	from the same variables. A build outside a session, such as CI, uses a directory inside the repository that is not
	`.cat/work/temp` or a session directory and that `clean` removes; the implementer chooses it after confirming that
	the native runner tests accept it.
- **Closes:** R-W13.
- **Tests:** `SessionWorkDirectoryTest` (the directory and marker from a hook input and from each variable; a non-UUID
	ID refused; concurrent first uses leave one complete marker; an existing marker kept; the refusals without a
	variable), `SessionSweepTest` (removes a directory whose transcript is gone and whose marker is older than 24 hours,
	for each harness; keeps one whose transcript exists, an archived Codex one, one with a newer marker and one without a
	marker; a failed removal reported without failing the start), `WorkDirectoryAuditTest`, `ClaudeSessionStartHookTest`
	and `CodexSessionStartHookTest` (the directory, the marker, the rules file's path, the injected `CAT_TEMP`),
	`CodexRunPartTest` (output in the session's `temp`; the fallback without a variable), `HarnessRunnerOptionsTest` and
	`CodexRunnerCliTest` (the default run root under the session's `temp`; `--run-root` required without a variable),
	`ToolScopeTest`, `CodexPostToolUseHookTest` (an uncut last part deletes its `run-part-*` folder, from `run-part` and
	from `read-part`; kept: a cut last part, an uncut last part while a cut part's position is recorded until that part
	is read uncut, a file outside a `run-part-*` folder, one without `output.txt.exit-status`, and a result that is not a
	last part; a failed deletion reported with the result unchanged).
- **Exit:** unit tests pass; the full build passes in a session and with both variables unset; in each harness, a live
	session's injected rules name `.cat/work/<session-id>/temp`, that directory and its marker exist, and `run-part` saves
	its output there; a Codex subagent's `run-part` saves into the directory that its injected rules name; with one
	session's transcript moved away and its marker's time set back by more than 24 hours, the next SessionStart in the
	project removes that session's directory and keeps every other one; in a live Codex session, a `run-part` command
	whose output takes three parts leaves no `run-part-*` folder once the agent has read its last part.

### WI-84 — Campaigns in the session work directory (user ruling, 2026-10-16)

- **Problem:** the user wants each campaign run by only one session, with its files in that session's work directory
	(`design.md` §4.1, §7, D17).
- **Storage:** `FileCampaignStore` and the run roots obtain their directories from WI-83's `SessionWorkDirectory`:
	`.cat/work/<session-id>/optimizer/<campaign-id>/`, `.cat/work/<session-id>/optimizer-runs/<harness>` and its held-out
	run root. The held-out deny lists follow the new paths. The campaign format version increases; CAT does not read
	campaigns under the old `.cat/work/optimizer/` and does not delete them.
- **Ownership:** the commands find their own session's campaigns in its directory. For any other campaign, they list
	every session directory's `optimizer/` read-only: `status` shows the campaign and its owning session, and every other
	command for it, and `start` for its target while it is resumable, are refused, naming the session and the command
	that resumes it (`claude --resume <session-id>` or `codex resume <session-id>`), with no state change. The
	starting-harness binding stays.
- **Sweep:** before removing a session directory that holds a campaign, the sweep removes the campaign's optimization
	worktree through Git, as the campaign's own cleanup does (R-W9), so the repository keeps no stale worktree entry; a
	failure leaves that session directory in place and is reported on standard error.
- **Closes:** R-W1's and R-L1's new wording.
- **Tests:** `FileCampaignStoreTest` (the session's storage), `CampaignRegistryTest` and `OptimizerCoreTest` (a campaign
	started in one session: `status` from another shows its owner; `run`, a choice, `cancel` and `start` for its target
	from another session are refused, naming the session, with no state change), `SessionSweepTest` (a deleted session's
	campaign worktree removed through Git before the directory; a failed worktree removal keeps the directory),
	`CleanupTest`, `ClaudeSessionsTest` and `CodexSessionsTest` (the deny lists at the new paths), `ForkTest`.
- **Exit:** unit tests pass; the full build passes; WI-71's next certification start creates its campaign under its
	session's work directory.

### WI-85 — Certification history beside `prompt.json` (user ruling, 2026-10-16)

- **Problem:** a certified delivery replaces the target's single record under `.cat/certifications/`, so certifying the
	target in the other harness, or again later, loses the earlier record (`design.md` §4.7, §5, D51).
- **History file:** `certifications.json` in the target's test directory, beside `prompt.json`: a JSON object with a
	format version and a `records` array, oldest first. A certified delivery appends its record, creating the file when
	absent, and never changes an earlier record. CAT never writes `prompt.json`. Staging leaves the history out. The old
	location is dropped without migration: no certified delivery has written a record outside end-to-end fixture
	projects.
- **Reading:** `status` for a target without a resumable campaign, and a new campaign's proposal, report the latest
	record of each harness current or historical as before, and list the earlier records as history.
- **Closes:** R-M6's new wording.
- **Tests:** `CertificationHistoryTest` (append to an absent and an existing file; earlier records unchanged; latest per
	harness; a malformed file refused at its JSON path), `DeliveryTest` (the record appended in the change set),
	`ApplicabilityTest`, `StagedSourceTest` (the history left out), `CertificationRecordTest`.
- **Exit:** unit tests pass; the full build passes; WI-71's certified delivery appends its record to the read-file
	target's `certifications.json`.

### WI-86 — A smaller proposal (user request, 2026-10-17)

- **Problem:** WI-71's attempt-5 proposal is 236 KB. Most of it repeats data without adding items: the absolute
	campaign-storage path of the base plugin before each of about 70 prompt files (37 KB), each edited test's
	`values.json` shown as a difference while the test case list below shows the same values (26 KB), a seed and version
	in every generated value, and every cell identifier of each scope choice (15 KB for one choice).
- **Prompt loading:** a file of a trial plugin's base is named by the plugin and its path inside the plugin, for
	example `` trial plugin `cat`: `rules/common/file-links.md` ``. Only the rendering changes: the stored derived-source
	path stays absolute, because staging resolves it to tell project files from files outside the project.
- **Edited tests:** the difference leaves out `values.json` and says that its values are the test cases listed below.
	Differences of `scenario.md` and of fixtures stay.
- **Generated values:** shown as the generator and its parameters, for example `log_lines(count 420)`, without the
	seed or generator version.
- **Scope choices:** cells, weakest cells and minimum trials by cell are listed as groups of prompt tests and profiles
	(for example ``tests `a`, `b` × profiles `p1`, `p2` ``) that cover exactly the listed cells. A group never stands
	for cells it does not contain, so the listing stays complete rather than a count.
- **Tests:** `ProposalTest` (a base-plugin file named by plugin; the stored proposal keeps the absolute path; an edited
	test's difference without `values.json` and with the note; a generated value without seed; a scope choice whose
	cells form one group and one whose cells form several, with every cell recoverable from the groups).
- **Exit:** unit tests pass; the full build passes; WI-71's revised proposal uses the new format.

### WI-87 — The CAT session directory (user request, 2026-10-17)

- **Problem:** the user wants the directory that holds one host session's files named for that: `.cat/sessions/` in
	place of `.cat/work/`, and the term "CAT session directory" in place of "session work directory", "CAT work
	directory" and "work directory" for that concept.
- **Paths:** the owning component builds `.cat/sessions/<session-id>/`; every path inside it keeps its relative name
	(`temp/`, `session.json`, `rules.md`, `subagents/`, `optimizer/`, `optimizer-runs/`). The root that
	`InventoryScanner`, `Fork`, `ForkView` and staging exclude becomes `.cat/sessions/`, and so do the local exclude
	entry that `start` adds and the repository's `.gitignore` entry. CAT still neither writes nor deletes
	`.cat/work/temp`.
- **Term:** rename the concept throughout the repository: identifiers (`SessionWorkDirectory` becomes
	`SessionDirectory`, `WorkDirectoryAuditTest` becomes `SessionDirectoryAuditTest`, and likewise each other name of
	this concept), Javadoc, comments, messages, the injected session rules, plugin rules and skills, `docs/`,
	`.cat/rules/`, every mention in `design.md`, including its glossary, D17 and D79, and this plan's R-W1, R-W13 and
	R-L1. Other concepts keep their names, such as a process's working directory and a trial's project. Recorded harness
	output, such as test transcripts and changelog captures, and the §2 rows that report finished work keep the paths
	they recorded.
- **Existing directories (user ruling, 2026-10-17):** each SessionStart moves every `.cat/work/<session-id>/` that
	holds a marker to `.cat/sessions/<session-id>/`, whichever session wrote it, so campaigns, rules and temporary files
	keep their owner. It moves each directory with one atomic rename and leaves it in place when the new path exists or the
	rename fails, reporting that on standard error; the sweep then treats the new path like any other. A directory
	without a marker and `.cat/work/temp` stay where they are. The project rule that only plugin installation migrates
	plugin data cannot apply, because installation does not know the user's projects, so `design.md` records this
	exception. Cost: a session that started before the change and is still running loses its `CAT_TEMP` path, which
	its rules state, until it resumes.
- **Order:** land it before WI-71's revise. The change alters the `CAT_TEMP` path that every trial's rules state, and
	the revise reruns every pilot anyway (`design.md` D80).
- **Tests:** `SessionDirectoryTest` (the directory, `temp/` and marker under `.cat/sessions/`), `SessionSweepTest`,
	`SessionDirectoryAuditTest` (fails when another main source file spells `.cat/sessions` or `.cat/work`),
	`ClaudeSessionStartHookTest` and `CodexSessionStartHookTest` (the rules name `.cat/sessions/<session-id>/temp`), the
	exclusion tests of `InventoryScanner`, `Fork` and staging, `FileCampaignStoreTest`, and the move: at startup and at
	resume, every old directory with a marker moved with its campaigns, one whose new path exists left in both places
	unchanged, one without a marker and `.cat/work/temp` left in place.
- **Exit:** unit tests pass; the full build passes; a search of the repository finds the old term and `.cat/work/<`
	only in recorded output and §2 history; a live session in each harness gets rules that name
	`.cat/sessions/<session-id>/temp`, and that directory holds its marker.

### WI-88 — Delete passing trials' project copies (user ruling, 2026-10-17)

- **Problem:** each trial run keeps two full copies of the staged project, its effects and its initial project, until
	the campaign's files are removed. In the read-file campaign they took about 230 MB per trial and 276 GB over 1,218
	runs, yet only a failed trial's copies are read again: a prompt author's evidence section and a diagnosis render
	them.
- **Change:** once a trial's passing verdict is recorded, the driver deletes both copies; a guard-pilot control trial
	loses them once its judge passes every assertion. Failed trials, failed runs and trials whose verdict is not
	recorded keep them. The transcript and run record stay.
- **Tests:** a recorded passing development trial's copies are gone and a failed one's remain (`DriverTest`); a pilot
	keeps exactly the copies of its trials that failed an assertion (`GuardPilotTest`).
- **Exit:** unit tests pass; the full build passes.

### WI-89 — Minimal trial workspaces (user rulings, 2026-10-17)

- **Problem:** every trial copies the whole fork, about 104 MB per copy in the read-file campaign, although most tests'
	agents read only their fixtures. The other project files cost disk and time, and they are material that an agent can
	search or read although the test never chose it. Fixture `patterns` also need a rule for where each matched file
	lands.
- **Change:** each candidate's base source holds only the prompt system: its prompt sources, harness files, loader
	configuration, `.cat/rules/`, plugin mapping sources and in-project dependents, but no ignored file and no other file
	of `.cat/` (user ruling, 2026-10-17); an ignored file reaches a trial only through a link. A prompt test's `fixtures/` becomes `workspace/`, the root of each trial's workspace, and `## Fixtures` is retired: a shared or
	project file reaches a test through a symbolic link in its `workspace/`, which each trial receives as a copy of the
	link's target, read when the proposal is checked and recorded in the contract as a workspace file. A link to another
	entry of the same `workspace/` stays a link in each trial and in delivery. CAT refuses links that are absolute,
	missing, outside the project, cyclic, self-holding, into a prompt test directory or onto a prompt file, and links that
	Git records but the checkout holds as another kind of file. Drafts resolve links at the test's project path; delivery
	copies a changed shared file into the test that changed it. Plugin copies and prompt measurement use the base source;
	the contract author and test revisions keep the whole fork. The guard pilot's stored results record the base
	selection, so every pilot reruns once. The recorded prompt tests are converted, and the contract author's instructions
	describe `workspace/` and links. The fixtures terminology is renamed without compatibility (user rulings, 2026-10-17):
	a prompt test's files are its workspace files (JSON `workspace_files` and `recorded_workspace_files`), the contract's
	trial fixtures are its ignored files (`ignored_files.paths`), `contract fixtures` becomes `contract workspace`, and
	the runner's `--fixtures` becomes `--workspace`; a campaign stored before the rename no longer loads. The ignored
	files are then retired without compatibility (user ruling, 2026-10-18): the proposal's `ignored_files`, the
	contract's list, the proposal's "Ignored files" section, the contract author's instructions for them and
	confirmation's refusal of a missing ignored file are removed, because a link in `workspace/` reads its target from
	the draft or the live project, so the list reached no trial. In-project dependents stay in every trial's base source
	(user ruling, 2026-10-18).
- **Tests:** a staged source holds only the files that a selection names (`StagedSourceTest`); the base selection
	holds the prompt system and nothing else (`TrialSelectionTest`); a prompt test's `workspace/` files and links are
	read, placeholders apply only to test-root files, and each refused link and an unknown section are refused
	(`PromptTestFilesTest`, `ScenarioTest`); the proposal lists links and records them (`ProposalTest`,
	`ProposalSchemaTest`); a link inside `workspace/` stays a link in trials and delivery (`TestWorkspacesTest`,
	`DeliveryTest`, `DeliveryPlanTest`); a link onto a prompt file is refused (`OptimizerCoreTest`); control
	trials of a test receive its links and a stored pilot from another selection reruns (`GuardPilotTest`); delivery
	replaces a changed shared link with a copy (`DeliveryTest`); a proposal with `ignored_files` is refused
	(`ProposalSchemaTest`), and the fork records a proposal's derived sources that Git ignores, not other ignored files
	(`OptimizerCoreTest`).
- **Exit:** unit tests pass; the full build passes; WI-71's guard pilots rerun on the new workspaces.

### WI-90 — Requirements from what trials load (user ruling, 2026-10-19)

- **Problem:** the contract author's request listed the files that load in the user's own sessions of the harness,
	which include the rules of the CAT that the user installed and the user's own CAT rules. Trials load only the
	project's files and the trial plugins that the prompt test configuration lists, so the fifth E1 and sixth E7 Codex
	runs, which list no trial plugin, proposed 11 and 17 requirements from files that no trial receives.
- **Change:** the contract author's request lists the files that load in trials, derived from the campaign's trial
	plugins as the derived sources already are (`design.md` §6.1, D85). The list moves from the contract author
	protocol's construction into its request, beside the hook registrations, because only a request needs it. The
	contract author's instructions say that a path outside the project is a file of a trial plugin. The proposal still
	lists the files that load in the user's sessions but not in trials, as information.
- **Tests:** the contract author's request lists a trial plugin's file and leaves out a file that loads only in the
	user's sessions (`OptimizerCoreTest`); the request lists the files it is given with their rules (`ProposalTest`).
- **Exit:** unit tests pass; the full build passes; the E1 and E7 Codex proposals are derived again without the
	requirements from the user's own rules.

### WI-91 — Dependents at symbolic links

- **Problem:** the seventh E7 Codex run proposed removing `docs/reply-rules.md`, a link to the external rules file,
	and `docs/handbook.md`, a link inside the project. The control source refused both as "not a regular file of the
	project", although the only refusals that R-W10 names are the target, a path outside the prompt system and a span
	that does not occur exactly once. The refusal skipped the guard pilot and would refuse confirmation, yet the proposal
	said only "No pilot: CAT has not run it."
- **Change:** trials see a copy of a link's target at the link's path (`design.md` §4.4), so a dependent at a link
	removes that copy: a whole-file dependent deletes the link, and a span dependent leaves an ordinary file at the
	link's path without the span. The link's target is unchanged, so an external file, or a project file that other
	paths share, keeps its content. The overlay gains two operations that key an entry at a link itself; a prompt
	author's edits still apply to a link's target, and reloading or moving an overlay repeats each entry with the same
	operation. The proposal's "Control source" section lists each reason why the control source cannot be built, and a
	prompt test without a pilot names that reason.
- **Tests:** a whole-file dependent at an in-project link and a span dependent at a link to an external file are
	removed from the staged control source while both targets keep their content (`ControlSourceTest`); a refused
	control source is listed in the proposal and named in each pilot note (`ProposalTest`).
- **Exit:** unit tests pass; the full build passes; the E7 Codex proposal is revised and runs its guard pilot.

### WI-92 — Links in a revised draft

- **Problem:** the sixth E1 Codex run's contract author linked `docs/handbook.md` from each prompt test's
	`workspace/`, as `design.md` §6.3 asks of a test whose agent must find a project file. The proposal was valid, but
	its revision failed twice with "The contract author session did not complete: Workspace files contain a
	symbolic-link file": a revision copies the latest draft directory into the session's project, and the runner
	refused every link among the files it copies there. Every revision of a proposal whose prompt tests link a file
	therefore failed, in both harnesses.
- **Change:** the runner copies a link among a session's workspace files as a link with the same target and never
	follows it. It refuses a link whose target is absolute or lies outside the session's project, naming the link, so a
	session still cannot reach a file outside its project through one.
- **Tests:** a draft's link is copied as a link that reaches the project file it names, and a link that leaves the
	project is refused (`HarnessRunManagerTest`).
- **Exit:** unit tests pass; the full build passes; the E1 Codex proposal is revised.

### WI-93 — A guard pilot that waits out a hold

- **Problem:** the E1 and E7 Codex guard pilots stopped after 8 control trials of their first prompt test, five times
	in all. The pilot started no further session after a rate-limit rejection, and `start` keeps only a pilot whose
	trials all finished, so each rerun stopped at the same point. `design.md` §3.5 instead reruns a rejected session
	after the harness's shared hold, and pilot trials are targeted runs.
- **Change:** a pilot session that the provider rejects because of a rate limit, or refuses, runs again once the hold
	ends: a trial with the same test case, and a judge without spending one of its attempts. A refusal that follows a
	30-minute hold stops the pilot, which records why, as it pauses a campaign.
- **Tests:** rejected and refused trials and judges rerun and every pilot decides all its trials; a refusal after the
	longest hold stops the pilot (`GuardPilotTest`).
- **Exit:** unit tests pass; the full build passes; the E1 and E7 Codex pilots finish.

### WI-94 — Limitations without pilot results

- **Problem:** after the E1 and E7 Codex pilots finished, E1's revised contract still listed, as limitations, that its
	guard pilots had stopped on rate limits. The contract author copied the pilot status from its request, because its
	prompt does not say what a limitation is not; the copy stays in the frozen contract, and in every claim that lists
	its limitations, after a later pilot changes the results.
- **Change:** the contract author's prompt limits `limitations` to gaps in what the contract requires or tests, and
	leaves out guard pilot results and other facts about one run, which the proposal already shows.
- **Tests:** the full build; the next contract author revision in E1–E8 lists no pilot result as a limitation.
- **Exit:** the full build passes; a revised E-scenario proposal after a pilot lists no pilot result as a limitation.

### WI-95 — Claude requests with control characters

- **Problem:** in the first Claude E1 run, `start` failed twice before the contract author's first reply. Claude Code
	ended the session with `Error parsing streaming input line (type=user, 74130 chars): SyntaxError`. The Claude runner
	escaped only backslashes, quotes and line feeds in each request, so the tabs that indent the contract author's
	prompt reached Claude as raw control characters, which JSON does not allow in a string. Every nested Claude role
	whose prompt holds a tab fails the same way.
- **Change:** the runner builds each request line, a user turn or an interrupt, with the JSON library, which escapes
	every character that a JSON string cannot hold.
- **Tests:** `ClaudeStreamingProtocolTest.framesControlCharacters` frames a prompt with a tab, a carriage return and
	another control character and reads the line back as JSON with the same prompt; it failed before the change on the
	raw tab.
- **Exit:** the full build passes; the Claude E1 run's `start` produces a proposal.

### WI-96 — Claude imports as derived sources

- **Problem:** in the Claude E1 run, the target `CLAUDE.md` holds only `@AGENTS.md`, and every rule is in `AGENTS.md`.
	Trials load `AGENTS.md` through that import, but CAT did not list imported files among the derived prompt sources,
	so the prompt author's edit of `AGENTS.md` raised an `outside_prompt_sources` authority warning on both requirements
	it needed, and the campaign could only edit the one-line `CLAUDE.md`.
- **Change:** the Claude adapter lists, after each of `CLAUDE.md`, `.claude/CLAUDE.md` and `CLAUDE.local.md`, the
	project files that it imports through `@path`, and the files that those import, for at most four hops, as Claude
	Code documents. A relative path resolves against the importing file's directory, a backslash keeps a space in the
	path, and an import in a code span or fenced code block is not followed. Only an import that resolves to a file in
	the project is listed; a home-directory or other external import needs the user's approval in Claude Code and is
	not listed.
- **Tests:** `ClaudeSessionsTest.derivesImportedInstructions`, which failed before the change by listing only
	`CLAUDE.md`.
- **Exit:** the full build passes; a fresh Claude E1 run lists `AGENTS.md` as a derived source, and its prompt author
	edits `AGENTS.md` without an authority warning.

### WI-97 — Imports that trials cannot receive (user ruling, 2026-10-19)

- **Problem:** WI-96 lists only an import that resolves to a file in the project. An import whose file is missing, or
	lies outside the project such as `@~/notes.md`, loads in the user's sessions or nowhere, and its trials silently
	lack it.
- **Change:** the Claude adapter reports, with the files that load in trials, each import of a listed instruction file
	that names a missing file or a file outside the project, with the importing file. `start` refuses before creating
	anything while one is reported, naming each import and its file, so the user moves the file into the project or
	removes the import.
- **Tests:** `ClaudeSessionsTest` (a missing and an external import are reported, an in-project one is not);
	`OptimizerCoreTest` (`start` refuses and creates no campaign).
- **Exit:** the full build passes, and both tests failed before the change.

### WI-98 — Files that hooks and MCP servers run (user ruling, 2026-10-19)

- **Problem:** a hook or MCP server of the prompt system injects context from a script that no instruction file names,
	such as `$CLAUDE_PROJECT_DIR/scripts/inject.sh`. A script outside the harness's project directories is not in the
	base source, so the hook fails in trials, and the prompt author cannot edit the script.
- **Change:** both adapters derive, as prompt sources, each in-project regular file that a hook command of the project
	hook files or the trial plugins names by path, after the harness's project-directory variable is replaced, and each
	that an MCP server of the project's MCP configuration names in its command or arguments. A word that names no file
	in the project, such as a program on the search path, is not derived. Each derived file's rule names the hook's event
	or the MCP server.
- **Tests:** `ClaudeSessionsTest` and `CodexSessionsTest` (a script named through the project variable, a relative
	script and an MCP server's script are derived; a program name and a missing path are not).
- **Exit:** the full build passes, and both tests failed before the change.

### WI-99 — The files that trials receive (user ruling, 2026-10-19)

- **Problem:** the proposal lists the derived prompt sources but not every file that trials receive, so the user can
	confirm a contract without seeing that a file the prompt system needs is missing, as in the first Claude E1 run.
- **Change:** the proposal's prompt loading section lists every file of the incumbent's base source, which every trial
	receives besides its prompt test's workspace files.
- **Tests:** `ProposalTest` (the list holds the instruction files, a derived import and the project rules, and no
	other project file).
- **Exit:** the full build passes, and the test failed before the change.

### WI-100 — A candidate that loads a file its trials do not receive (user ruling, 2026-10-19)

- **Problem:** a prompt author can add an import of an existing project file, or a hook script reference, that the
	campaign's trial selection does not hold, and its trials then measure the candidate without that file.
- **Change:** after a prompt author attempt, CAT derives the files that load and run in trials from the attempt's
	copy. A file in the project that the trial selection does not hold, or a reference that `start` would refuse,
	makes the attempt contribute no candidate, and the prompt author failure names each file.
- **Tests:** `DriverTest` (an attempt that imports an unselected project file, and one that imports a missing file,
	contribute no candidate; an attempt that imports a file inside the prompt sources does).
- **Exit:** the full build passes, and the test failed before the change.

### WI-101 — Hooks checked before the contract author (user ruling, 2026-10-19)

- **Problem:** what a hook reads while it runs cannot be derived from its command. A session start hook that prints
	`docs/conventions.md` succeeds in the user's sessions and fails in trials, and nothing shows it before the
	campaign's measurements.
- **Change:** before the contract author starts, CAT runs each hook of the prompt system and of the trial plugins that
	runs at session start or prompt submission, without a model: once in the staged base source with a new, empty
	harness home, and once in a copy of the fork with the user's home, each with the input that the harness documents
	for the event and the same prompt. `start` refuses, naming the hook, its exit status and the end of its error
	output, when the hook succeeds in the fork and fails in the base source. The proposal lists each hook whose output
	differs between the two runs, with the first differing lines, and states that a hook that reads other files under
	another input can still fail in trials.
- **Tests:** `OptimizerCoreTest` (a hook that reads an unselected project file refuses `start`; a hook whose output
	holds the working directory is listed with its difference; a hook that only reads its plugin passes).
- **Exit:** the full build passes, and the test failed before the change.

### WI-102 — What runs only in the user's sessions (user ruling, 2026-10-19)

- **Problem:** the proposal lists the prompt files that load in the user's sessions but not in trials, but not the
	hooks, plugins and MCP servers of the user's harness home that trials do not install.
- **Change:** both adapters list, with the production-only files, each hook of the user's harness settings, each
	installed plugin that the prompt test configuration does not list, and each MCP server of the user's configuration;
	the proposal shows them under the trial plugins.
- **Tests:** `ClaudeSessionsTest`, `CodexSessionsTest` and `ProposalTest`.
- **Exit:** the full build passes, and the tests failed before the change.

### WI-103 — No instructions from above a run directory (WI-15 E1 finding, 2026-10-20)

- **Problem:** run directories lie inside the user's project, and Claude Code loads the instruction files of every
	directory above its working directory. A Claude E1 trial loaded the project's own `CLAUDE.md`, which imports the
	incumbent `AGENTS.md`, so trials of every candidate also received the incumbent, and the trial followed the
	project's handbook instead of its workspace's.
- **Change:** the Claude runner's isolated settings exclude, through `claudeMdExcludes`, the `CLAUDE.md`,
	`CLAUDE.local.md`, `.claude/CLAUDE.md` and `.claude/rules/**` of every directory above the run directory, the same
	boundary above which Git discovery already stops; the session environment version becomes 4.
- **Tests:** `ClaudeConnectionSettingsTest.excludesInstructionsAboveIsolationRoot`, `ClaudeSessionEnvironmentVersionTest`.
- **Exit:** the full build passes, the test failed before the change, and a nested session below a directory with
	each of the four files loads none of them.

### WI-104 — Prompt sources from files only (WI-15 E1 finding, 2026-10-20)

- **Problem:** with Claude Code 2.1.304 unchanged, nested sessions of one campaign listed different built-in skills:
	of 262, 130 listed 17, 63 also listed `deep-research` and `workflow-authoring`, and 69 also listed 13
	`anthropic-skills:*` skills. The account's synced skills and its dynamic workflows load in the background at
	startup, and a trial home starts with neither cached, so the `PROMPT_SOURCES` value differed between trials of one
	candidate and paused the campaign.
- **Change (user decision, 2026-10-20):** the Claude prompt sources list only the skills, agents and plugins that come
	from files: a plugin whose `init` path is not `builtin`, a skill or agent in such a plugin's namespace, and a skill,
	command or agent that a file below the session project's `.claude/skills`, `.claude/commands` or `.claude/agents`
	declares by path or frontmatter `name`. The harness version still identifies the built-in ones.
- **Tests:** `StreamJsonPromptSourcesTest.omitsBuiltInSources`, `StreamJsonPromptSourcesTest.listsProjectSkillsAndAgents`.
- **Exit:** the full build passes and both tests failed before the change.

### WI-105 — Pruning skips entries that are not runs (WI-15 E1 finding, 2026-10-20)

- **Problem:** an empty `.claude` directory appeared in a Claude harness root during a campaign, and pruning treated it
	as a run, so every later trial session failed and the guard pilot decided no trial.
- **Change:** pruning a harness root skips entries whose names are not generated run identifiers.
- **Tests:** `HarnessRunManagerTest.pruneSkipsEntriesThatAreNotRuns`.
- **Exit:** the full build passes and the test failed before the change.

### WI-106 — Operator precedence rule (user request, 2026-10-20)

- **Problem:** agents write conditions such as `count == 0 || reason != null && !isTerminal(reason)`, whose reader must
	recall which operator binds more tightly.
- **Change:** a bundled rule, routed from the default rules, has agents add parentheses that show the grouping the
	language applies where different operators meet, skip conventional precedence, and report rather than regroup an
	expression whose grouping looks unintended.
- **Tests:** prompt tests `mixed_operators_grouped` and `conventional_precedence_kept`.
- **Exit:** the full build passes with the rule packaged in both runtimes.

### WI-107 — Nested sessions stay out of the user's project (user request, 2026-10-20)

- **Problem:** in a Claude E1 campaign, 104 of 160 trial sessions used paths in the user's project around their run
	directory, and 63 read its copy of a document instead of their workspace's. The Claude runner also copied the host's
	permission grants into each session, so the host's additional directories let trials read and write every sibling
	worktree.
- **Change:** every nested session, in both harnesses, is denied reads of the host project except its own run
	directory and the evidence it reads; a Claude session receives only the host's deny and ask rules. The session
	environment versions change.
- **Tests:** `ClaudeConnectionSettingsTest.writesIsolatedSettings`, `ClaudeRunnerTest.deniesProjectAroundRunRoot`,
	`CodexRunnerTest.deniesProjectBesideEvidence`, `ClaudeSessionsTest.deniesHostProjectReads`, the session environment
	version tests.
- **Exit:** the full build passes, the tests failed before the change, and a rerun Claude E1 guard pilot shows no
	trial reading the project, no stray `.claude` directory and trials still running CAT's skills.

### WI-108 — CAT launchers run inside a sandbox (WI-15 E1 finding, 2026-10-20)

- **Problem:** every CAT launcher runs its JVM under `bwrap`, to give it a private perf-data directory, whenever `bwrap`
	is on the `PATH`. Inside a sandboxed Claude session `bwrap` cannot create a namespace, so the launcher exits with an
	error and a skill that runs it, such as `cat:help`, cannot finish.
- **Change:** every launcher runs Java directly, without `bwrap`, on both harnesses, and its JVM options include
	`-XX:+PerfDisableSharedMem`, which keeps the JVM's perf data inside the process, so two JVMs with the same PID write
	no shared file (user ruling, 2026-10-20). The user-owned launcher rule changes with it.
- **Tests:** the launcher template tests, with a `bwrap` that fails to create a namespace.
- **Exit:** the full build passes, the test failed before the change, and a nested Claude session with the sandbox on
	runs `plugin-info` through the installed plugin.

### WI-109 — Nested sessions write only their own state and the dependency caches (user request, 2026-10-20)

- **Problem:** the Claude runner copied the host's sandbox `allowWrite` into every nested session, so trials and
	judges could write the user's `~/.claude`, `~/.cat` and the host's other writable paths. Codex sessions could write
	the npm cache but not the Maven repository.
- **Change:** a nested session, in both harnesses, may write its own run and home and the user's `~/.npm` and `~/.m2`,
	and keeps the user's `MAVEN_OPTS`. The session environment versions change.
- **Tests:** `HarnessDependencyCacheTest`, `ClaudeConnectionSettingsTest.writesIsolatedSettings`,
	`ClaudeConnectionSettingsTest.copiesParentSandboxOnWindows`,
	`CodexConnectionConfigurationTest.configuresNestedDependencyCaches`, the session environment version tests.
- **Exit:** the full build passes, the tests failed before the change, and a rerun Claude E1 guard pilot shows no
	trial writing `~/.claude` or `~/.cat`.

### WI-110 — Passing streaks extend until unlikely (user ruling, 2026-10-20)

- **Problem:** promotion stopped a passing streak at 4 runs, so an attempt could fix only a test case that the working
	candidate passes less than about 56% of the time. The Codex E7 campaign's attempts passed every promotion run and were
	still rejected.
- **Change:** a passing streak extends one run at a time until it is unlikely for the working candidate, with no upper
	limit (`design.md` §2.7, D89).
- **Tests:** `PromotionTest.workingRecordDeterminesStreak` with a working candidate that passed 3 of 4 outcomes.
- **Exit:** the full build passes and the test failed before the change.

### WI-112 — Prompt-test discovery reads the Git index through `Forks` (testability audit finding, 2026-10-20)

- **Problem:** `RegressionScenarios.read` runs Git itself to find workspace links checked out as files, so every unit
	test that reads recorded prompt tests runs real Git: `OptimizerCoreTest` (594 calls in one build), `ProposalTest`,
	`DraftDirectoryTest` and `RegressionScenariosTest`, which also runs Git in the developer's own checkout. §8 allows
	real Git only in the tests it names. `BackupTest.namesBackupBranchWithUtcTime` also creates a repository, a second
	real-Git test of the backup operation.
- **Change:** `Forks` gains a lookup of the symbolic links that a project's Git index records, empty outside a work
	tree, and `RegressionScenarios.read` receives its result instead of running Git. `ForkTest` covers the lookup with
	real Git; every other test supplies the links. `BackupTest.recordsAndDeletesBackupBranch` takes over the UTC name
	check. `TestabilityAuditTest` fails when an optimizer source other than the fork adapter constructs `Git`.
- **Tests:** `ForkTest` (the index lookup), `RegressionScenariosTest` (a recorded link checked out as a file),
	`BackupTest.recordsAndDeletesBackupBranch`, `TestabilityAuditTest`.
- **Exit:** the full build passes, and a logging `git` first on the `PATH` records calls only from the tests that §8
	names.

### WI-113 — Retries wait through a pause (testability audit finding, 2026-10-20)

- **Problem:** `PortableFiles.replace` calls `Thread.sleep` between attempts on Windows, where unit tests such as
	`RunnerSessionsTest` reach it (`design.md` §10 T5), and `PortableFilesTest` holds the file open with
	`Thread.sleep`. `ExecutableFiles` sleeps before retrying a text-file-busy start, which every unit test that starts a
	process reaches.
- **Change:** `FileReplacer` replaces `PortableFiles.replace`, and `ExecutableFiles` becomes an instance. Each waits
	between attempts through a `Pause` and measures its limit with a `Clock`, both supplied by its creator. Only
	application entry points create the sleeping instances; unit tests pass yielding ones or their own.
	`TestabilityAuditTest`'s sleep check covers every main and test source of the three modules except `Pause.sleeping`.
- **Tests:** `FileReplacerTest` with a pause that ends the refusal, one that advances the clock past the limit, one
	that fails, and a platform other than Windows; `ExecutableFilesTest` counting pauses against a manual clock;
	`TestabilityAuditTest`.
- **Exit:** the full build passes, the tests failed before the change, and no test source sleeps.

### WI-114 — A draft that the stored proposal refuses (WI-15 E7 Claude finding, 2026-10-20)

- **Problem:** the contract author's draft is checked against the session's copy of the project, where a prompt file
	reached through a link is a regular file. Start, confirm, revise and the prompt-source listing read the stored
	proposal against the user's project. In Claude E7, both new prompt tests linked `docs/reply-rules.md`, a project
	link whose target is absolute, so authoring passed and `start` then failed on an invalid stored proposal, and the
	host agent offered to rewrite the user's link.
- **Change:** authoring also reads the stored form of the draft against the user's project and refuses the draft with
	every problem found, while its author can still fix it. A prompt test's link that reaches a project link with an
	absolute or outside target is refused under the prompt test's link, naming the project link and asking for another
	file.
- **Tests:** `ProposalTest.refusesDraftThatStoredProposalRefuses`, `TestWorkspacesTest.refusesLinkToAbsoluteProjectLink`.
- **Exit:** the full build passes, the tests failed before the change, and Claude E7 reaches confirmation.

### WI-115 — A stored proposal that no longer validates (WI-15 E7 Claude finding, 2026-10-20)

- **Problem:** every command validated the whole stored proposal again to learn its derived prompt sources. In Claude
	E7, the proposal stored before WI-114 did not validate, so `cancel` and `status` failed too, and `start` resumed
	into the same failure, which left no command that could end or continue the campaign. A project change after the
	proposal can cause the same state.
- **Change:** commands take the prompt sources from the derived sources that the stored proposal file lists, which
	validation never changes. Resuming an unconfirmed campaign whose proposal no longer validates derives a new proposal
	and tells the user why; `confirm` and `revise` refuse it and point to `start`.
- **Tests:** `OptimizerCoreTest.cancelIgnoresInvalidProposal`, `OptimizerCoreTest.resumeDerivesAgainForInvalidProposal`.
- **Exit:** the full build passes, the tests failed before the change, and the Claude E7 campaign stored before WI-114
	cancels through the installed skill.

### WI-119 — Project rules, trial plugin choice and the certified environment (user rulings, 2026-10-21)

- **Problem:** in Codex E5, whose prompt test configuration lists no trial plugin, candidate 7 had grown `AGENTS.md` from
	452 bytes to about 7,300, which every model call of every trial resent; no project rule could have loaded there. A
	user who has not written `prompt.json` gets only the file's name, and a certification never says which harness and
	plugins it holds for.
- **Change:**
	- a trial plugin is CAT when its manifest's `repository` is CAT's repository URL, compared after ignoring the letter
		case of the scheme and host, a trailing `/` and a trailing `.git`, whatever its name or marketplace (D93, user
		ruling 2026-10-21; only `https://github.com/holdmyspot-com/cat` is CAT's repository, and the former
		`https://github.com/cowwoc/cat` is not, user ruling 2026-10-22); `start` records each trial plugin's
		repository from its build output's or installed copy's manifest in the campaign, and a plugin recorded
		without one is not CAT; trial installation and rule loading use the same test on the plugin copy's manifest;
	- when CAT is a trial plugin of the campaign's harness, `.cat/rules/` is an in-project prompt source, so a candidate
		may create a rule there, and the prompt author's brief says where a correction goes: an instruction where it is
		stated, `AGENTS.md` and `CLAUDE.md` included; a CAT-shipped target at its module source; other new guidance in an
		existing or new project rule (`common`, `claude` or `codex`), with `paths` frontmatter when it applies only to
		some files; without CAT, the current preference for a file other than `AGENTS.md` and `CLAUDE.md`;
	- `start`'s refusal for a missing prompt test configuration lists every installed plugin of the harness home by name
		and version and every plugin manifest tracked in the project; the skill asks which ones the prompt's users have,
		asks for the build command and output of each project-built one, writes `prompt.json`, shows it and runs `start`
		again;
	- the measurement identity records each trial plugin's manifest version, and the terminal outcome, the delivery
		report and `status` show the `Certified for the following environment:` block in words that need none of CAT's
		terms: harness and version, plugins with versions, model, prompt files loaded, harness settings, trial
		setup (the CAT release that introduced the session environment version, which CAT records with each version),
		unchecked ones marked, then the host machine limitation; CAT's own version has two parts, such as `1.0`;
		`status` of a finished certified campaign that cleanup has not removed shows the block from the delivered
		attempt's record (user rulings, 2026-10-22).
	- CAT neither records nor controls the host machine's programs and outside sources (D95, user ruling 2026-10-22,
		superseding the same day's ruling to record them): `b6b6e92858`'s recording is reverted, the `HOST_TOOLS` and
		`EXTERNAL_SOURCES` conditions are removed and dropped when a stored campaign or history is read, together with a
		pending measurement change that names only them, and the certified environment block and `status` state the
		limitation and recommend a container image shared by the campaign and the sessions that later use the prompt.
- **Tests:** `TrialPluginTest` (`cat@x` from another repository is not CAT; a differently named plugin with CAT's
	repository is), `PluginInfoTest` (URL normalization, and CAT's repository constant against both plugin manifests),
	`FileCampaignStoreTest` (the repository round-trips, and a record without it is not CAT), `OptimizerCoreTest`
	(`start` records each trial plugin's repository; `status` of a finished campaign not yet removed shows the
	environment block), `RunnerSessionsTest` (trials install CAT by repository),
	`PromptAuthorTest` (the brief's placement rule with and without CAT as a trial plugin), an authority test
	that a candidate rule under `.cat/rules/` is accepted only when CAT is a trial plugin, `OptimizerCoreTest` (the
	refusal lists the installed plugins), `MarkdownTest` or the report's test (the environment block), and a
	certification history test that a plugin version change makes a record historical; for the host machine,
	`CertifiedEnvironmentTest` (no tools or outside sources lines; the block ends with the limitation),
	`StatusReportTest` (the fixed limitation in the limitations, summary and JSON), `FileCampaignStoreTest` (a campaign
	with `host_tools` and `external_sources` loads without them, and a pending measurement change that names only them
	is dropped) and `CertificationHistoryTest` (a record with them reads back without them).
- **Exit:** the full build passes, each test failed before its change, and the skill writes `prompt.json` from a user's
	answer through the installed launcher.


### WI-120 — Runner skills that download the other harness's artifact (user rulings, 2026-10-22)

- **Problem:** Codex's `claude-runner` skill runs `bin/claude-runner` from the installed Claude CAT plugin and fails
	when it is not installed, so a user who installed CAT only for Codex took the missing launcher for a broken release;
	Claude's plugin has no skill that runs a task in Codex.
- **Change:**
	- Claude's plugin gains a `codex-runner` skill that mirrors Codex's `claude-runner` skill and runs `bin/codex-runner`;
	- each runner skill resolves the other harness's CAT artifact of its own CAT version and platform: the installed
		plugin of that harness when its version matches, otherwise `${HOME}/.cat/bundles/cat-<harness>-v<version>-<platform>/`;
		without either, a launcher in the skill's own plugin downloads `cat-<harness>-v<version>-<platform>.tar.gz` and
		`SHA256SUMS.txt` from the release of that version, verifies the checksum, extracts into a temporary directory
		beside the destination and moves it into place, so a failure leaves no copy and is reported;
	- the download works on every release platform, Windows included, and its base URL can be overridden like the
		installer's `CAT_RELEASE_BASE_URL`, so tests serve a local release directory and never reach the network;
	- each plugin ships only its own harness's classes and files;
	- a download waits at most 30 seconds to connect, 60 seconds for the response and 15 minutes for the whole file;
		after a download, the copies of every other CAT version in the bundle directory are deleted, and each harness's
		uninstaller removes the directory;
	- each check for a copy deletes the copies, of any version, harness and platform, that no run has used for more
		than three calendar months; a download or a run that uses a copy restarts its age.
- **Exit:** unit tests of resolution, download, checksum failure, partial extraction and version mismatch against a
	local release directory; distribution tests of each archive's skills and launchers; the full build passes.
### WI-121 — Session transcripts on request (user rulings, 2026-10-23)

- **Problem:** Claude runs always pass `--no-session-persistence`, so a caller that tests what Claude records, such as a
	plugin's effect on the transcript, cannot get the transcript; Codex runs always keep rollouts, which callers rarely
	read.
- **Change:**
	- both runners accept `--session-transcripts`, which requires `--keep-run`, in the option parser and usage text;
	- without it, Claude keeps `--no-session-persistence` and Codex starts ephemeral threads and keeps no rollouts;
	- with it, Claude omits `--no-session-persistence` and Codex starts persistent threads; the bridge writes a
		`session_transcript` record with the native transcript path once the session starts; at the end of the run, the
		runner copies every native transcript, subagents' included, into `artifacts/session-transcripts/` before it
		prunes the run's home, and the receipt lists the copies;
	- the optimizer passes the option for its trials, and the records that read what a trial's model received read the
		new directory;
	- both runner skills describe the option.
- **Exit:** unit tests of option parsing, the Claude command, Codex thread parameters, the copy, the receipt and the
	optimizer's records in both harnesses; the full build passes.

### WI-122 — Identical trial limits in both harnesses (user ruling, 2026-10-23)

- **Problem:** a Codex session's commands keep the user's home, so a harness that a command starts reads the user's
	configuration, and a Claude session's commands receive the provider token; a harness started from a command reaches
	the provider in one harness and not in the other.
- **Change:**
	- one harness-neutral definition in the common runner package lists the limits of a session's commands: the run's
		home as `HOME` (and the Windows equivalents), no harness credential variable in their environment, and denied
		reads of every harness's login and configuration files in the run's home and the user's home and of every other
		run, leaving the rest of the user's harness directories readable because installed plugins link there;
	- both runners build their sandbox settings and environment from it: Claude sets its subprocess scrub, Codex sets
		its shell environment policy and the commands' home;
	- one conformance test runs against both runners' generated settings and environment, so a limit that one runner
		drops fails the build;
	- both runner skills tell callers that a session cannot start a harness itself, to test a plugin by passing its
		directory with `--plugin` and starting a new run for each change, and to use requested sessions when a session
		must start one.
- **Exit:** the conformance test passes for both runners; the full build passes; in each harness, a command in a
	session that starts the harness directly cannot reach the provider.

### WI-123 — Requested sessions (user ruling, 2026-10-23)

- **Problem:** a session cannot check its own work in a new session, such as a plugin it just wrote, because its
	commands may not start a harness.
- **Change:**
	- both runners accept `--session-requests <count>` (default `0`); with a positive count, the run's `requests`
		directory is the one directory outside the session's project and home that it may write, and the session's
		environment names the `request-session` launcher and the directory;
	- the `request-session` launcher, shipped in both harnesses' archives, takes the harness, a project directory, prompt
		files and optional plugins, model, effort and `--session-transcripts`; it writes the request atomically, waits for
		the response up to a bound and prints the response file's path, or the request id and how to wait again;
	- the runner validates each request (count, harness, paths readable by the session, model and effort) and starts
		the session through the requested harness's runner launcher, resolved as the runner skills resolve it, with the
		requester's run root and denied reads and no requests of its own; it copies the requested run's transcript,
		receipt and native transcripts into the run's `responses` directory, which the session may read but not write,
		and writes a response there that names the copies, never their content; the launcher packs the request and its
		inputs into one archive inside the session's sandbox, and the runner opens only that archive (no link, one hard
		link) and unpacks it into a directory of its own, refusing links, absolute and parent paths and oversized content
		(D101);
	- a requested session takes a slot of its harness's capacity, honouring the hold, and fails with `capacity` after
		waiting 10 minutes; ending the run or interrupting its turn stops requested sessions and answers pending requests
		with `stopped`;
	- the receipt lists each requested session; when requests are allowed, the session's instructions say how to make
		one; the optimizer allows none in its trials;
	- both runner skills describe the option.
- **Exit:** unit tests with fake harnesses of validation, the broker, the capacity timeout, stopping and the receipt;
	the full build passes; in each harness, a session writes a plugin and tests it through a requested session.

### WI-124 — Source-only content stays out of release artifacts (user ruling, 2026-10-23)

- **Problem:** the build copied prompt files unchanged, so lazily loaded rules, skill bodies and agent bodies shipped
	their design goals and maintainer comments, which reached models whenever an agent read them; only injected rules
	lost their design goals, at injection.
- **Change:** the plugin artifact builder removes the `## Design Goals` section and every HTML comment that is not a
	`cat:` directive from each Markdown file, outside fenced code blocks and code spans, before it expands includes;
	prompt-only repackaging, role-brief loading and project-rule injection do the same. The plugin's rules on writing
	prompt files say that the guidance alone must achieve every goal, and every prompt file's guidance is audited against
	its goals and fixed where it falls short.
- **Exit:** unit tests of the removal, the assembled artifact, the briefs and project rules; a scan of both built
	artifacts finds neither; the guidance audit covers every prompt file with design goals; the full build passes.

### WI-126 — Install Codex marketplace metadata from the verified release archive

- **Problem:** the POSIX bootstrap downloads the installer and shared library without the marketplace metadata that
	registration needs. The checksum succeeds, then the installer tries to copy a missing adjacent file.
- **Change:** resolve marketplace metadata from the verified artifact's `install` directory, matching the PowerShell
	installer. The shell regression runs the release bootstrap with only its actual downloaded installer dependencies.
- **Evidence:** commit `361da15fee`; the new regression first failed after checksum success with the missing-file
	diagnostic, then passed. All 30 bootstrap checks and ShellCheck pass. The full clean release build, using temporary
	storage outside Git and an absent user home, passed 3,179 tests with zero failures/errors and 11 skips in 100.901 s.
- **Hosted Build:** run `40098509359` succeeded for commit `361da15fee`.
- **Published:** the user completed the reserved delete/tag operation. Release run `40099789260` succeeded for `361da15fee`, publishing ten archives and a matching ten-entry `SHA256SUMS.txt`. The published Linux Codex archive passed its SHA-256 check; its installer matches the fixed source and it contains the required marketplace metadata. Verification receipt: orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/integration-y5REYT/release-bootstrap-fixed/verification.json`.

- **Downloaded-wrapper follow-up (`d8887e449a`):** the public Codex wrapper failed twice because a wrapped shell
	argument passed a directory URL and `bootstrap.sh` as separate curl URLs. All four install/removal wrappers had
	the same defect and could return success after curl failed. They now fetch one complete URL into a checked
	command substitution before running it. The downloaded Claude installer also takes marketplace metadata from
	the verified archive. Five new boundary regressions failed before the fixes; 35 bootstrap checks and ShellCheck
	pass. Full build: 3,209 tests, zero failures/errors, 11 skipped, 96 s; absent-home check passed. Evidence:
	orchestrator session `01a16de4-2163-75d0-9e45-481a1850fb3e`, `temp/integration-y5REYT/bootstrap-followup-evidence.json`.
	The earlier published archive verification remains valid for the initial Codex metadata fix; it did not prove the
	outer README bootstrap. Public outer-bootstrap verification and a replacement release remain pending.

### WI-125 — Keep measurement identity after an accepted change (user ruling, 2026-10-24)

- **Problem:** a plugin-cache update pauses an existing campaign, and none of its choices retains the accumulated
	results under the same measurement identity.
- **Change:** offer `keep_identity` for every measurement change except loader changes. Record accepted observations
	within the identity without clearing evidence, changing trial test cases or restarting certification attempts.
	Apply acceptance only to the values and cells observed; unknown changes still pause. Persist the acceptances
	with campaigns and certification history, and show them in status and certification reports.
- **Evidence:** `1b833feb8c`; TDD reproduced the unavailable choice, lost persisted acceptances, missing report disclosure, redundant pauses and overwritten inherited cell conditions before their fixes. Full clean build: 3,207 tests, zero failures/errors, 11 skipped in 98.227 s with an absent user home left absent. The documented installer installed the verified Codex artifact; the cache matches it.
- **Remaining:** the paused Codex E5 campaign must resume in its owning session and apply the user-approved choice; a call from the orchestrator was correctly refused without changing campaign state.
- **Exit:** unit tests prove retained state, accepted and unaccepted observations, per-cell scope, loader exclusion,
	store round trips and reporting; full build passes; the paused Codex E5 campaign resumes using the choice.

### WI-15 — Acceptance

- **End-to-end:** in both harnesses, run these scenarios through the installed skill:
	- **E1:** the deficient fixture's proposal lists its conflict, confirmation is refused
		until a resolution is chosen, and the fixture then improves to `certified` and is delivered as a Git diff, with a
		disjoint base edit surviving. Its receipt also shows, per harness: the contract author's proposal is schema-valid,
		has requirements and names the instruction file as a derived source; each judge verdict is schema-valid, carries a
		rationale and leaves the evidence unchanged; each prompt author attempt changes only prompt sources; and the
		per-trial records hold the observed model, the login class, rate-limit state and the loading record. These replace
		the retired native tests.
	- **E2:** ESC mid-slice, with Codex stopping within 15 seconds; the run's interrupted turn is saved as a recording.
		Unit tests replay that recording to show that resuming loses or duplicates no outcome and keeps the
		certification index.
	- **E3:** an explicit stop leaves the base unchanged; explicit uncertified acceptance is labelled
		`not certified` and delivered through the same choose-and-deliver path that keeping an improvement uses. Keeping
		an improvement is covered by `DriverTest` traces 12 and 13 and `DeliveryTest`, because a certification attempt
		ends uncertified only after hundreds of trials.
	- **E4:** unsupported cases: a remote governing instruction, an image-output requirement, a response-contingent
		obligation, and a persistent-write path.
	- **E5:** a trial uses a host tool, and one uses child subagents with child worktrees. One trial writes a file that
		its judge decides on; that trial and a text-output trial, each with its judge session, are saved as recordings
		that a unit test judges from.
	- **E6:** a cross-harness invocation is refused without any state change.
	- **E7:** a project with in-project and external links shows the link report; a prompt author's needed edit to an
		external file raises an authority warning that offers no way to edit it, and delivery leaves every external file
		unchanged.
	- **E8:** two concurrent CAT processes are bounded by the shared capacity.
- **Unit evidence instead of live runs (user ruling, 2026-10-20, D91):** these §14 claims are proven by Java tests,
	not by an E scenario: the dirty-base refusal (`ForkTest`, on a real repository); removing a campaign's files after
	the incumbent is kept and finishing an interrupted removal (driver and delivery tests); a prompt test's fixtures with
	a generated value (`TestWorkspaceTest`, `DriverTest.trialsReceiveTheirFixtures`,
	`RunnerSessionsTest.passesGeneratedFixtureToRunner`, `HarnessRunManagerTest.workspaceKeepsProjectFiles`); a failed
	built-in tool, with and without `on_tool_failure: judge` (`DriverTest.failedCheckRunsNoJudge`,
	`DriverTest.failedToolLeftToJudge`); paired control trials (`SchedulerTest.controlTrialsPairInAssignmentOrder`,
	`GuardPilotTest.runsControlTrialsInWeakestCell`, `DriverTest.certify`); resuming a certification attempt after a host
	interrupt (`DriverTest.hostInterruptResumesAttempt`, with the contract tests replaying each harness's recorded
	interrupted turn); and the eligible read-only-credential and disposable-fixture trials.
- **Recordings:** each run saves its harness event streams, untrimmed, with the harness version. They replace the
	contract tests' recordings, and the contract tests then pass on them (`design.md` §10 T3). Save at least one
	completed multi-turn session per role and an interrupted turn (from E2) per harness.
- **Contract tests (moved from WI-14):** once a harness's recordings exist, add `<Harness>SessionsContractTest`, which
	runs every `SessionPortContract` check against the real adapter over `FakeHarnessCli` replaying them. Delivering an
	interrupt to the fake needs a signal seam in the adapter, because `FakeHarnessCli.pid()` has no process to signal.
	Replace `codex/cli/src/test/resources/optimizer/codex-hooks.jsonl`, which names no harness version, with a
	recording that does. Then delete that harness's `*SessionsNativeTest`, and after both harnesses, remove the
	`native` profile, `cat.test.groups`, `cat.test.excludedGroups` and `cat.native.harness`. Keep `cat.session.temp`,
	the fixture directory of tests that start no harness.
- **Order:** run every step in Claude first; start Codex only after Claude passes all of E1–E8 (user ruling,
	2026-10-19, which reverses the Codex-first order of 2026-10-01). The Codex E1 and E7 campaigns that were in
	progress, `20261019T185225Z-6ba4c4` and `20261019T185226Z-c05d87`, paused when a runner change altered their session
	environment and were stopped at the user's ruling (2026-10-20); Codex E1 and E7 run again from the start.
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
| WI-34 | WI-33 |
| WI-35 | WI-34 |
| WI-36 | WI-34 |
| WI-37 | WI-36 |
| WI-38 | WI-34, WI-37 |
| WI-39 | WI-37 |
| WI-40 | WI-37 |
| WI-41 | WI-38 |
| WI-42 | WI-41 |
| WI-43 | WI-42 |
| WI-44 | WI-38 |
| WI-45 | WI-43 |
| WI-46 | WI-38 |
| WI-47 | WI-8 |
| WI-48 | WI-9 |
| WI-49 | WI-45 |
| WI-50 | WI-8 |
| WI-51 | WI-45 |
| WI-52 | WI-50 |
| WI-53 | WI-48 |
| WI-55 | WI-49 |
| WI-56 | WI-46 |
| WI-57 | WI-53 |
| WI-58 | WI-55 |
| WI-59 | WI-58 |
| WI-60 | WI-59 |
| WI-61 | WI-59 |
| WI-62 | WI-61 |
| WI-63 | WI-61 |
| WI-64 | WI-60, WI-62, WI-63 |
| WI-65 | WI-64 |
| WI-66 | WI-65 |
| WI-67 | WI-64 |
| WI-68 | WI-66, WI-67 |
| WI-69 | WI-61 |
| WI-70 | none for the scripts and hook; its rule step needs WI-71 |
| WI-71 | WI-69, WI-70, WI-72, WI-73, WI-75, WI-76, WI-77, WI-78, WI-79, WI-80, WI-81, WI-82, WI-83, WI-84; its revised proposal needs WI-86; its certified delivery also needs WI-85 |
| WI-72 | WI-63 |
| WI-73 | WI-65 |
| WI-74 | WI-28, WI-63 |
| WI-75 | WI-66, WI-74 |
| WI-76 | WI-73, WI-75 |
| WI-77 | WI-65 |
| WI-78 | WI-77 |
| WI-79 | WI-26 |
| WI-80 | WI-77 |
| WI-81 | WI-73, WI-75, WI-76 |
| WI-82 | WI-75, WI-81 |
| WI-83 | WI-70's scripts and hook |
| WI-84 | WI-82, WI-83 |
| WI-85 | WI-81 |
| WI-86 | WI-82 |
| WI-87 | WI-83, WI-84 |
| WI-88 | WI-71's pilot code (`GuardPilot`) |
| WI-89 | WI-88 |
| WI-90 | WI-81, WI-82 |
| WI-91 | WI-65, WI-81 |
| WI-92 | WI-61 |
| WI-93 | WI-66 |
| WI-94 | WI-66 |
| WI-95 | WI-8 |
| WI-96 | WI-10 |
| WI-97 | WI-96 |
| WI-98 | WI-10, WI-81 |
| WI-99 | WI-89 |
| WI-100 | WI-9, WI-89, WI-98 |
| WI-101 | WI-81, WI-89 |
| WI-102 | WI-81 |
| WI-103 | WI-5 |
| WI-104 | WI-5 |
| WI-105 | WI-5 |
| WI-107 | WI-5, WI-36 |
| WI-108 | WI-5 |
| WI-109 | WI-5 |
| WI-110 | WI-26 |
| WI-112 | WI-1, WI-7 |
| WI-113 | WI-1 |
| WI-114 | WI-82, WI-89 |
| WI-115 | WI-13 |
| WI-116 | WI-107 |
| WI-117 | — |
| WI-118 | — |
| WI-119 | WI-26, WI-81, WI-13 |
| WI-120 | WI-13 |
| WI-121 | WI-13 |
| WI-122 | WI-13 |
| WI-123 | WI-120, WI-121, WI-122 |
| WI-124 | WI-13 |
| WI-15 | WI-14. Its trial-based scenarios (E1 certification, E2, E3, E5, E7 delivery, E8) also need WI-16, WI-17, WI-18, WI-20, WI-32, WI-33, WI-34, WI-35, WI-36, WI-37, WI-38, WI-39, WI-40, WI-41, WI-42, WI-43, WI-44, WI-45, WI-46, WI-49, WI-50, WI-51, WI-52, WI-53, WI-55, WI-56, WI-57, WI-60 to WI-69, WI-90, WI-91, WI-92, WI-93, WI-95, WI-96, WI-97, WI-98, WI-99, WI-100, WI-101, WI-102, WI-103, WI-104, WI-105, WI-107, WI-108, WI-109, WI-110, WI-114, WI-115 and WI-116. |

Do WI-0 and WI-1 first when capacity is limited: they carry the highest risk of changing other work.

WI-71's development and certification, and WI-70's rule step that needs them, run last: after every other work item
and every §9 gate that does not need them (user ruling, 2026-10-19). Work that the certified wording could change, such
as WI-15's Codex scenarios, runs on the uncertified `read-file.md` meanwhile.

## 7. Handoffs and use-case traces

Each cross-component handoff is declared once here. Other sections refer to these declarations. Handoff IDs `H-n` are
distinct from the harness-evidence IDs `Hn` of `design.md` §11.

| Handoff | Producer → consumer | Ready when | Identity and authority | Failure and resumption | Proof |
|---|---|---|---|---|---|
| H-1 Confirmed contract | WI-10 → WI-3, WI-2 | The user's `confirm` on proposal revision `r` | Contract hash and lineage `ℓ`. Only the confirmation event freezes it. | A missing confirmation blocks every admission. A revision creates `ℓ + 1`. | `DriverTest` "nothing runs before confirmation" |
| H-2 Assignment | WI-3 `Scheduler` → WI-4 → WI-9 → WI-8 | The assignment record is durable | Campaign, purpose, snapshot, category, sequence and test case. The draw comes from the stream's persisted position. | Started without an outcome means rerun, same identity. | `ReconciliationTest` |
| H-3 Trial result → judge | WI-8 → WI-9 (WI-17 checks) → WI-8 (judge) | Session completed, children reconciled, every deterministic check passed and a semantic requirement remains. Otherwise CAT records the outcome without a judge session (`design.md` §6.4). | Logical trial identity. The judge receives read-only evidence and the deterministic results, which it cannot override. | Interruption or infrastructure failure means rerun the trial. An invalid judge result means rerun the judge with the same evidence. | `DriverTest` (trace 16), `DeterministicCheckTest`, `JudgeVerdictTest` |
| H-4 Outcome → adoption | WI-9 → WI-3 | Outcome durable, and every earlier sequence number adopted | Sequence order per category and purpose | A crash between write and adoption is adopted during reconciliation. | `AdoptionTest`, `ReconciliationTest` |
| H-5 Prompt author delta → candidate | WI-7 → WI-6, WI-18 → WI-3, WI-20 → WI-7 | Delta captured; inventory and manifest validated; promotion runs passed; in a weak round, the attempt won the selection; the minimality pass finished (`design.md` §2.7) | Overlay hash. Authority comes from the contract. | A rejected attempt is retained with no candidate. The prompt author reruns as a new attempt. | `InventoryDiffTest`, `PromptAuthorManifestTest`, `PromotionTest`, `AttemptSelectionTest`, `MinimalityTest`, `DriverTest` |
| H-6 Commit | WI-3 → WI-1 → WI-4 | Commit gate and baseline met | Certification index `i`, `α_i`, `β_i`, frozen `UI(k)` and `LI(k)`, `DecisionBoundaries` and caps, all persisted before the first draw | Calibration failure leaves the attempt uncommitted and blocks admission. | `CommitTest`, `AdmissionTest` |
| H-7 Decision → delivery or user | WI-3 → WI-11 or WI-12 → skill | Attempt terminal and in-flight trials drained | Attempt and candidate identity. A keep-improvement choice names the qualifying attempt. | A pending choice, including the keep-improvement pause, survives restart. Delivery resumes from the journal. | `DecisionTableTest`, `KeepImprovementTest`, `DeliveryTest` |
| H-8 Status → skill → choice | WI-12 → WI-14 → WI-13 | `run` returned | Campaign identity and choice identifier. Only listed choices are accepted. | An unknown or stale choice is rejected with no change. | `CommandsTest`, E-scenarios |
| H-9 Capacity permit | WI-5 → WI-8 | `tryAcquire` succeeded | One permit per nested session | Process death releases the file lock. | `HarnessCapacityTest` |
| H-10 Host interrupt | WI-8 → WI-9 | An interrupt record later than the driver start | Host thread or session ID from the environment | Stop admissions, interrupt sessions, persist, exit within 15 seconds. | `RolloutInterruptSignalTest`, `DriverTest`, E2 |
| H-11 Trial profile | WI-16 (`ProfileInventory`, profile sets) → WI-9 → WI-8 | Confirmation froze the profile set | Cell (category, profile). A trial session runs under its cell's profile; contract author, judge and prompt author sessions run under the role profile. The observed profile in the harness events decides comparability. | A run whose observed profile differs from its trial's measurement identity yields no outcome, and CAT pauses as `MEASUREMENT_CHANGED` (`design.md` §5). | `ProfileSetTest`, `ClaudeSessionsTest`, `CodexSessionsTest`, `MeasurementIdentityTest`, `DriverTest` (trace 15) |
| H-12 Failure evidence → prompt author | WI-9 → WI-10, WI-18 (prompt author prompt) | A development round starts its prompt author attempts (`design.md` §2.7) | The failing test cases' identities, which the manifest must cite, with their judge rationales or failed-check records and diagnostic reruns. Never a test case of a certification attempt in progress. | An attempt that cites an unknown test case is rejected and retained (H-5). | `DriverTest` (prompt author input during a formal attempt; traces 16 and 17), `PromptAuthorManifestTest` |
| H-13 Needed edits → authority warning | WI-10 (prompt author prompt) → WI-9 → WI-6 (`Authority`) → WI-3 → WI-12 | A prompt author session completed and its report lists the edits it needed, each naming a requirement of the confirmed contract | Each needed edit's requirement, path, operation and target, checked against the campaign's edit authority (`design.md` §4.3) | A malformed report, or one naming an unknown requirement, fails the attempt as an infrastructure failure with no candidate. Otherwise the attempt's candidate is submitted as usual. A warning is raised only while the campaign is active, confirmed and not revising its contract; it is an `awaiting-user` decision that survives restart. | `DriverTest`, `AuthorityWarningsTest`, `AuthorityTest`, `StatusReportTest` |
| H-14 Candidate overlay | WI-9 → WI-4 (`CandidateOverlays`) → WI-9, WI-11 | The overlay is saved before the event that records its snapshot | The snapshot, which is the overlay's identity. The campaign records only the snapshot. | Saving an overlay that is already saved does nothing. A recorded snapshot without a saved overlay, or an overlay file that is malformed, on another fork or for a different candidate, is a read failure that names the file; CAT never reconstructs an overlay. | `CandidateOverlaysContract` through `FileCandidateOverlaysTest` and `InMemoryOverlaysTest`, `DriverTest` |
| H-15 Draft directory | WI-82 (contract author session) → WI-6 (proposal validation) → held-out store → WI-67 (delivery) | The contract author session ended and validation of the proposal file and the draft passed | The draft's prompt tests at their test paths, each scenario with its Expected Behavior; values files with the seeds and versions that validation wrote; the proposal file naming each prompt test's directory | A refused proposal returns its problems, each with its file and line or JSON path, once to the same session, which edits the same files. A session that reaches its time limit stores nothing and leaves its files in its run directory. | `DraftDirectoryTest`, `ProposalTest`, `ContractCheckTest`, `DeliveryTest`, `OptimizerCoreTest` |
| H-16 Trial plugins | WI-81 (`start`) → WI-7 (staging) → WI-8 (runners) | Every listed plugin's base is copied into campaign storage and each project-built plugin's mappings are recorded, before the contract author starts | Each plugin's name and base digest, and each project-built one's build command and mappings; these are measurement conditions | A failed build, an output without the harness's manifest or with another plugin's name, or a listed plugin with no single installed copy stops `start`, showing what failed, and leaves the target startable again. | `PromptConfigurationTest`, `InstalledPluginsTest`, `PluginMappingTest`, `StagedSourceTest`, `CodexRunnerTest`, `ClaudeRunnerTest`, `MeasurementIdentityTest` |
| H-17 Session work directory | WI-83 (`SessionWorkDirectory`) → the agent (injected `CAT_TEMP`), CAT's commands (`run-part`, runner commands, Git replay editor) and WI-84 (campaign storage) | The first use in the session created `.cat/work/<session-id>/temp` and the marker | The harness's session ID: the hook input's `session_id` and the tool shell's `CLAUDE_CODE_SESSION_ID` or `CODEX_THREAD_ID` name the same directory; only `SessionWorkDirectory` builds its path | Any first use creates the directory and marker; without a session variable, `run-part` falls back to the system temporary directory, a runner command needs `--run-root` and the Git replay editor refuses. The sweep removes a session's directory only once its marker is more than 24 hours old and its transcript is gone, after removing its campaigns' worktrees through Git. | `SessionWorkDirectoryTest`, `SessionSweepTest`, `WorkDirectoryAuditTest`, `ClaudeSessionStartHookTest`, `CodexSessionStartHookTest`, `CodexRunPartTest`, `CodexRunnerCliTest`, `FileCampaignStoreTest` |

Use-case traces that `DriverTest` must cover, each as one test that starts from builder state:

1. **Certify:** confirmation, then development to the commit gate, commit, all-pass formal, certified, delivery
	requested.
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
	and CAT pauses with `MEASUREMENT_CHANGED`. Restoring reruns the same test case and the attempt continues. A second
	change followed by a new measurement identity ends the attempt `not certified: testing environment changed`,
	returns to development and starts a new incumbent baseline. A qualifying attempt from before the change is offered
	separately, labelled historical.
15. **Cells:** a category whose file is unbound runs under two profiles; each cell has its own counts, commit gate,
	commit and decision, and a trial runs under its cell's profile while the judge runs under the role profile.
16. **Deterministic failure:** a trial fails a deterministic check, no judge session runs, and the failed check's
	record reaches the prompt author brief.
17. **Manifest rejection:** a prompt author attempt with an uncited hunk is retained with no candidate, and the next
	attempt runs.
18. **Minimality:** a promoted attempt with one unneeded hunk is reduced to the needed hunks, the reduction's runs are
	not counted, and the report names the removed hunk.
19. **Development cells:** with the latest-versions scope, development samples only the weakest cell from the
	ranking, the candidate passes the commit gate there and is committed; a formal failure in another scope cell makes
	it a development cell, and the next commit needs the commit gate there too.
20. **Concurrent attempts:** a weak working candidate's round starts 3 attempts; two pass the promotion rule and the one
	with more passing promotion runs is minimized and promoted, while the others are retained; once the candidate is no
	longer weak, the next round makes one attempt.
21. **Held-out test cases:** development, targeted runs and prompt author briefs see only development test cases; formal
	and baseline trials draw only held-out ones; after a failed attempt the prompt author brief names the failure's
	cell, test and requirements but holds no held-out test case, reply or rationale.

## 8. Test-suite rules

These rules restate `design.md` §10 as checks for reviewers:

- Unit tests are the default (user ruling, 2026-10-24). Identify the product requirement; use controlled inputs
	and fakes when they prove it, and assert outcomes without internal-call, ordering or count assertions. Remove
	JDK/OS-only demonstrations. Retain the smallest integration check when replacing the dependency would hide a
	defect at the actual product boundary, including packaged scripts, launchers, installers and harness interaction.
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

### Interim design acceptance mapping

This map records reviewed evidence on the named source tree. Reconcile passed rows again on the final tree;
all other rows remain unrun, and final gate 5 remains unmet. The evidence paths below are relative to orchestrator
session `01a16de4-2163-75d0-9e45-481a1850fb3e`.

| Design row | State | Evidence and remaining work |
|---|---|---|
| G1 | Unrun | `temp/delivery-acceptance-ned3nvr5/g1-partial-review.json` retains 51 inspected current-source method references for cleanup, dirty-base refusal, file delivery, authoring, promotion, rollback and explicit choices. The earlier-best-offer check now includes a later failed attempt. The retained-incumbent fixture now includes the saved changed candidate and completed certified-proven-worse attempt. Crash recovery now checks physical mtime and durable plan outcomes instead of hook order. Complete the clause map and remaining outcome-policy/shared-fake review, including prompt-test crash recovery; reconcile certified delivery through both installed harnesses. |
| G2 | Unrun | Complete the proposal, guard-pilot, source, contract and fixture assertion map; retain WI-111 native loading verification. |
| G3 | Passed on `f825e06e35` | `temp/held-out-claim-Fmojx6/g3-acceptance.json` maps ten reviewed subclaims to 42 passing method references from the unrestricted 3,237-test build. It covers exact boundaries, cap fixtures, deficiency/improvement, frozen comparisons, other minimum pass rates, paired decisions, persistent budgets and claim wording. |
| G4 | Unrun | Map ordered adoption, retry, staging and test-case separation assertions; reconcile both native held-out access boundaries. |
| G5 | Passed locally on `f825e06e35` | `temp/delivery-recovery-outcomes-85dfp83w/g5-acceptance.json` maps fifteen inspected output, applicability, accepted-change, remaining-work and size subclaims to 72 passing method references. The public status test edits a command named by an unchanged prompt and retains current certification; file/memory store contracts retain accepted evidence, attempts and allowances after reopen. The native campaigns remain required under gate 4. Reconcile on the final source. |
| G6 | Unrun | Reconcile real ESC recordings for both harnesses, the Codex admission-stop limit, replay, persistence faults and cross-harness binding. |
| G7 | Unrun | Reconcile real runner roles, prompt tests, host tools, observed profiles and trial plugins; complete replay, workspace, delivery, eligibility and shared-capacity assertion maps. |
| G8 | Passed on `f825e06e35` | `temp/scheduling-acceptance-fgIIPg/g8-acceptance.json` maps thirteen inspected subclaims to 73 passing method references from the unrestricted 3,237-test build. It covers development cells, capability ranking, formal ordering, allocation, pairs, concurrency, throttling, refusal, volume warnings and persistent shared holds. One child JVM records a hold; an already-open parent pool refuses admission until controlled-clock expiry, then admits. This local design row does not close gate 4 or the user-deferred native E8 scenarios. |
| G9 | Unrun | Current unrestricted clean verify on `8ce2abae44`: 3,242 tests, zero failures/errors, 11 skipped, 105.198 s; all 299 runtime-property checks pass with nonexistent HOME/user.home. The explicit shared lock-coordination refactor (`12f54cef07`) is complete; hosted Build 40146996998 passed on documentation head `3fc3cd15a5`. Prompt-test restart outcome verification (`213a5f7f7d`) also passed hosted Build 40147837864 on `bbfc837e1f` in 292 s. Cleanup now calibrates its fake’s physical removal, registration removal and repeated removal through the same postconditions as the Git adapter; every original operation/assertion remains in order, including Git registration counts and opening refusal, and existing cleanup registration/bystander checks remain unchanged. No new Git process or model boundary. `temp/cleanup-forks-contract-itzxmfz0/` retains scope/preimages, exact extraction verification, 60 focused checks, full build, all six observed slow-case reasons and all 299 reports. Source reconciliation verifies 376 unchanged test sources and reparses two: 378 sources/299 reports, all 2,102 tags mapped to passing methods and the cumulative 82-path comparison retained. R-I1 still needs native evidence. Hosted Build is pending for this cleanup-test update; complete broader fixture, shared-fake, input and native reviews before passing this gate. |
| G10 | Unrun | Reconcile both final artifacts, shared skill body, launcher, procedure retirement, failure-regression rule and recorded-regression format/completeness checks. Published-release availability checks alone do not close this row. |
