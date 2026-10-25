# Prompt Optimizer Design

This is the product and technical design of CAT's prompt optimizer. It is written for the AI agents that implement and
maintain the feature; a user should still be able to audit every promise in it. `plan.md` is the implementation plan
that turns this design into code and acceptance evidence. [§1.2](#12-terms) defines the project terms that the goals and
later sections use.

## Design Goals

- **G1 — Improve and deliver for review:** Given a prompt system in a clean Git worktree, produce a stronger candidate.
	Merge it into that worktree as uncommitted changes only after it satisfies G3. For every other outcome, leave the
	worktree unchanged unless the user explicitly chooses one of two alternatives. They can keep a candidate proven better
	than the incumbent under the conditions of [§2.10](#210-keeping-a-proven-improvement), labelled
	`improved, not certified`, or accept an uncertified candidate, labelled `not certified`.
- **G2 — Let the user define compliance:** Derive a complete compliance contract proposal from the user's goal and the
	prompt system. The proposal covers requirements, prompt tests and their test cases, trial profiles, the judge rubric
	and the minimum pass rate. Each requirement states an outcome that the goal asks for, or an obligation of the prompt
	system that the user keeps when no goal requirement covers it, and each prompt test proves requirements with the least
	work that can still fail them, without telling the agent that it is tested. Run no trial until the user confirms it.
	Certify the weakest cell only unless the user, after seeing what each choice costs and claims, chooses every cell
	of each model family's latest version or a scope the user describes. The one exception is the guard pilot, which
	runs control trials of development test cases before confirmation so that the proposal can show how often each guard
	assertion passes without the prompt.
- **G3 — Certify a joint claim:** Report a candidate `certified` only when both claims hold, with at most a 5%
	probability that any certification, deficiency or improvement claim in the campaign is false:
	- **(a)** for every prompt test, under each trial profile of the certification scope, the candidate's future pass
		rate on the test's held-out test cases under the frozen decision procedure exceeds the campaign's minimum pass
		rate, and on each of the test's assertions the candidate's pass rate is no more than 5 percentage points below that
		of the same agent with the target prompt removed;
	- **(b)** for every prompt test whose incumbent is proven to pass less often than the minimum pass rate, the
		candidate is proven better than the incumbent.
	A prompt test on which the candidate is proven worse than the incumbent blocks default delivery.
- **G4 — Keep evidence valid:** Development outcomes never count as certification evidence. Every prompt test varies,
	and its test cases are split by expected outcome, before any trial, into a development part and a held-out part. Formal outcomes come only
	from fresh draws of held-out test cases, assigned after the candidate is frozen, and no session that shapes a
	candidate ever sees a held-out test case. A retry cannot discard an outcome. The order in which parallel trials
	finish cannot change a decision.
- **G5 — Report what was measured:** Show the four outputs, the measurement identity, current or historical
	applicability and limitations. Report the deterministic minimum number of trials still required, and never present
	a forecast as a bound.
- **G6 — Preserve user control and progress:** An interruption or crash loses at most the trials in flight, which
	rerun under the same logical-trial identity. Resuming continues without repeating completed work and never resets
	the campaign's error budget.
- **G7 — Use existing infrastructure in both harnesses:** Start every nested session (trial, judge, prompt author,
	contract author) through the existing Claude or Codex runner. Trials may use the user's host tools and need
	no Docker. Total nested sessions per harness stay within one cap shared by all CAT processes.
- **G8 — Minimize work and wall time at a responsible volume:** Develop each prompt test under its weakest trial profile
	only, and test the riskiest prompt tests first. In a certification attempt, do the cheapest work first: decide what
	CAT's built-in tools can decide before starting a judge, and run control trials only for cells that already
	certified. Keep parallelism low while failures are likely and raise it as passes accumulate. Stay within conservative session limits, slow down
	when the provider reports rate-limit pressure, and never use techniques that disguise automated use.
- **G9 — Keep tests concurrent and cheap:** Every unit test runs under TestNG's parallel-methods mode without shared
	locks, files or static state. A test reaches any campaign state without executing the expensive operations that
	would precede it in production. Each expensive operation is verified once.
- **G10 — Replace the existing skill:** `prompt-optimizer` invokes this feature in both harnesses. The old
	observe-diagnose-retest skill and every reference to it are retired.

[Acceptance evidence](#14-acceptance-evidence) maps each goal to the evidence that proves it.

## 1. Product outcome

The user names a prompt file or prompt system in a Git worktree. CAT:

1. refuses to start unless that worktree has no staged, unstaged or untracked changes;
2. records the prompt system and forks the worktree into an optimization worktree;
3. proposes a compliance contract, plus warnings about symbolic links and expected session volume, runs the guard pilot
	([§6.1](#61-derivation-and-confirmation)), and waits for the user to confirm or revise the contract, certifying the
	weakest cell only unless the user chooses another certification scope;
4. measures the incumbent and develops candidates in each prompt test's weakest cell, testing the riskiest prompt tests
	first;
5. commits a candidate to formal certification once it passes the commit gate;
6. certifies it under the joint claim or reports why it could not. If an uncertified attempt meets the
	keep-improvement conditions of [§2.10](#210-keeping-a-proven-improvement), CAT pauses and asks the user whether to
	keep the best proven candidate so far and stop optimizing, continue optimizing, or stop without delivering; and
7. merges the selected candidate into the user's worktree for Git review. That is either a certified candidate proven
	worse than the incumbent on no prompt test, or a candidate the user explicitly chose to keep. The delivery also
	writes each prompt test's test cases beside its scenario ([§4.7](#47-delivery)).

The user commits, reverts or asks for another revision. CAT never commits on the user's behalf.

CAT reports each of these outcomes with its evidence and available next actions. A campaign closes with `certified`,
`improved, not certified` or `not certified`; every other outcome leaves it open:

| Outcome | Meaning |
|---|---|
| `certified` | G3 holds; delivery follows the decision table in [§2.8](#28-decision-table). |
| `improved, not certified` | The user kept a candidate proven better than the incumbent on at least one prompt test, proven worse on none, and checked for regression on every other prompt test ([§2.10](#210-keeping-a-proven-improvement)). |
| `not certified` | The user accepted an uncertified candidate ([§2.8](#28-decision-table)). |
| `not certified: below minimum` | A cell is proven below the minimum pass rate in the current certification attempt. |
| `not certified: below control` | On some assertion of a cell, the candidate is proven to pass more than 5 percentage points less often than the agent with the target prompt removed ([§2.3](#23-sequential-tests)). |
| `not certified: below incumbent` | On some assertion of a cell, the candidate is proven to pass more than 5 percentage points less often than the incumbent ([§2.3](#23-sequential-tests)). |
| `not certified: inconclusive` | A cell reached its trial cap with its minimum-pass-rate test, a control comparison or an incumbent comparison undecided. |
| `not certified: stopped for contract revision` | The user stopped a certification attempt to revise the contract. |
| `not certified: testing environment changed` | The user started a new measurement identity during a certification attempt ([§5](#5-measurement-identity-and-applicability)). |
| `not certified: stopped for test revision` | A test revision changed a prompt test during a certification attempt ([§2.7](#27-development-commit-gate-and-edit-or-continue)). |
| `certification unavailable: unsupported instruction source` | A remote or MCP response supplies governing instructions. |
| `certification unavailable: ineligible trial effect` | A required trial path writes persistent state outside the trial copy, or lacks read-only credentials or a disposable fixture. |
| `certification unavailable: unsupported output` | A requirement produces image, audio or video output. |
| `certification unavailable: unsupported test interaction` | An obligation needs a follow-up turn chosen from the agent's reply. |
| `certification unavailable: invalid statistical assumptions` | A detected dependence between trials invalidates the formal procedure ([§2.6](#26-validity-conditions-and-assumption-review)). |
| `interrupted` | Work stopped and can resume. |
| infrastructure failure | A precise failure that is not a pass or fail outcome. |

What follows an open outcome depends on the outcome:

- `not certified: below minimum`, `not certified: below control`, `not certified: below incumbent` and
	`not certified: inconclusive` end one certification attempt and return to development automatically, unless the
	attempt's evidence offers `keep improvement` ([§2.8](#28-decision-table)).
- `not certified: stopped for contract revision`, `not certified: stopped for test revision` and
	`not certified: testing environment changed` end the running certification attempt.
- Each `certification unavailable` outcome pauses for the user's choice ([§2.8](#28-decision-table)).
- `interrupted` and an infrastructure failure pause until the user resumes.

### 1.1 Scope

The feature covers prompt optimization and certification in both installed harnesses, Claude and Codex, with the same
behaviour for every user. It runs locally against the user's configured provider account. The existing runner, Git and
prompt-loading capabilities are dependencies to reuse, not functionality to reimplement.

Out of scope: licensing, billing, cost prediction, usage analytics, telemetry, product releases, new harnesses or
platforms, Docker or another packaged runtime, and product feedback submission.

### 1.2 Terms

| Term | Meaning |
|---|---|
| Target prompt file | The one prompt file that a campaign optimizes and certifies, which the host agent passes to `start`. `start` refuses a directory ([§8](#8-skill-and-command-interface)). |
| Prompt system | The target prompt file together with the files, rules, configuration and loading relationships that shape its behaviour. |
| Compliance contract | The confirmed requirements, prompt tests with their test cases, trial profiles, judge and minimum pass rate. |
| Goal | What the user wants the target prompt to achieve, in the user's own words, which the host agent passes to `start` ([§6.1](#61-derivation-and-confirmation)). |
| Requirement | An observable outcome that the prompt system must produce, or an operation that it must not harm, with its source: the user's goal or the prompt file that states it. A requirement that only a prompt file states is an *extra requirement* when a goal was given, and the user keeps or drops it. Every requirement has a prompt test that proves it, and every prompt test proves at least one requirement ([§6.1](#61-derivation-and-confirmation)). |
| Test revision | A contract author session that rewrites a prompt test whose failures come from the test rather than the prompt, keeping the requirements that the test proves ([§2.7](#27-development-commit-gate-and-edit-or-continue)). |
| Trial profile | One model and reasoning-effort combination that a trial session runs under ([§6.6](#66-trial-profiles)). |
| Pass rate | The fraction of a candidate's trials of one prompt test that pass. A trial passes when every assertion of its prompt test passes. It measures compliance only as far as the prompt tests cover the requirements ([§2.1](#21-claims)). |
| Minimum pass rate | The pass probability that claim (a) requires every certified cell to exceed. The contract author proposes it once per campaign, 0.95 unless the user's feedback asks for another value, and the user confirms it with the contract. It lies between 0.80 and 0.99 ([§2.1](#21-claims)). |
| Cell | One prompt test under one trial profile. Every certified cell needs its own claim, because averaging profiles could hide a weak one. |
| Weakest cell | The cell of a prompt test, within the certification scope, of the least capable model by advertised capability at its lowest effort. No trial runs to find it ([§6.6](#66-trial-profiles)). |
| Development cell | A cell where development samples and edits candidates: the prompt test's weakest cell, plus every cell where a certification attempt adopted a failure ([§6.6](#66-trial-profiles)). |
| Certification scope | The cells that certification tests and the claim covers: the weakest cell only, unless the user chooses at confirmation every cell of each model family's latest version or a scope the user describes ([§6.6](#66-trial-profiles)). |
| Prompt test | A test in the contract, written as a `scenario.md` file in its own directory: its variables, its turns and its assertions. Its test cases supply the variables' values ([§6.3](#63-prompt-tests-and-test-cases)). |
| Assertion | One statement about a trial that a prompt test checks, under its own heading in the scenario's `## Assertions` section. It is a guard or an effect assertion, and a built-in tool or the judge decides it ([§6.3](#63-prompt-tests-and-test-cases), [§6.4](#64-judge)). |
| Guard assertion | An assertion that an agent passes almost always even without the target prompt, such as answering a question from the one line that a search finds. It protects an operation that the prompt must not harm. |
| Effect assertion | An assertion that the target prompt exists to make true, such as reading a long file whole. Any assertion that is not a guard is an effect assertion. |
| Variable | A named input of a prompt test, declared in its `## Variables` section and written `{{name}}` in its turns, assertions and workspace files ([§6.3](#63-prompt-tests-and-test-cases)). |
| Test case | A prompt test with one value for each of its variables, together with its expected outcome. The contract author writes the test cases. Every trial of the same values is a trial of the same test case ([§6.3](#63-prompt-tests-and-test-cases)). |
| Expected outcome | The result that a compliant reply gives for a test case, such as the date that the turn asks for. CAT splits test cases into parts by it, and adds to every prompt test an assertion that the reply's result matches it ([§6.3](#63-prompt-tests-and-test-cases)). "Expected outcome" never means a trial's pass or fail result. |
| Held-out part | The test cases of each prompt test that only formal trials, control trials of an attempt and the incumbent's baseline trials draw. No session that shapes a candidate sees them ([§6.3](#63-prompt-tests-and-test-cases)). |
| Workspace file | A file in a prompt test's `workspace/` directory, or the copy of the file or directory that a symbolic link there reaches. CAT copies it into each trial's workspace at its path below `workspace/`, replacing each variable in its name, and in the content of a file from a test root, with the test case's value ([§6.3](#63-prompt-tests-and-test-cases)). |
| Value generator | A function built into CAT that expands a seed and parameters, such as a line count, into the text of a workspace file, giving the same bytes on every machine ([§6.3](#63-prompt-tests-and-test-cases)). |
| Values file | The committed `values.json` beside a scenario, which holds its test cases' values and expected outcomes. Delivery writes it at the end of a campaign ([§4.7](#47-delivery)). In a draft directory it also holds the contract author's new test cases. |
| Draft directory | A new directory that CAT creates for each contract author session, holding a copy of every recorded prompt test of the target. The session edits those copies and creates its new prompt tests there, and delivery writes the confirmed ones ([§6.1](#61-derivation-and-confirmation)). |
| Contract tools | The commands that CAT gives a contract author session for work that needs no judgment, such as validating its draft or measuring a test case's workspace files ([§6.1](#61-derivation-and-confirmation)). |
| Reply | Every assistant message the trial agent sends during one turn, in order: its interim messages, such as a progress note before a tool call, and its final response (D29). |
| Built-in tool | A tool that ships with CAT and decides one assertion from the trial record without a model. An assertion names it in its `tool` key, and its `on_tool_failure` key says whether a failure of the tool fails the trial or leaves the assertion to the judge ([§6.4](#64-judge)). |
| Trial record | The normalized record of one trial that CAT builds from the harness events and the trial's file changes. Built-in tools read it, and the judge receives it as its evidence section ([§6.4](#64-judge)). |
| Decision procedure | The built-in tools plus the frozen judge, which together give each assertion of a trial its pass label ([§2.1](#21-claims), [§6.4](#64-judge)). |
| Control trial | A trial of the incumbent with the target prompt file emptied, its dependents removed and the rest of the prompt system unchanged, which measures each assertion without the prompt ([§2.3](#23-sequential-tests), [§4.4](#44-trial-staging)). |
| Incumbent trial | A trial of the incumbent, paired with a candidate formal trial and running its test case, which measures each assertion with the prompt system that the candidate would replace ([§2.3](#23-sequential-tests)). Baseline trials also run the incumbent, but they draw their own test cases and measure the whole trial. |
| Guard pilot | The control trials of development test cases that CAT runs before confirmation, so the proposal shows how often each assertion passes without the prompt ([§6.1](#61-derivation-and-confirmation)). |
| Incumbent | The prompt system snapshot recorded at the fork, before any optimization. It never changes, and every candidate is compared with it. |
| Candidate | One exact snapshot: the incumbent plus an overlay of edits. The incumbent is the first candidate, with no edits. |
| Working candidate | The candidate that development currently samples and improves. It starts as the incumbent, and each promotion replaces it. The optimization worktree holds it. |
| Prompt author attempt | One prompt author session's edits. It becomes a candidate only after validation and promotion. |
| Promotion | Making a validated candidate the working candidate. It never consumes allowance. |
| Development | The phase that samples and edits the working candidate before a commit ([§2.7](#27-development-commit-gate-and-edit-or-continue)). Development outcomes never count as certification evidence. |
| Failure impact | Whether a failed development trial is *blocking*, leaving the user without the turn's correct and complete result, or *self-recovering*, delivering that result anyway at the cost of extra work. A blocking failure makes development edit at once ([§2.7](#27-development-commit-gate-and-edit-or-continue)). |
| Commit gate | The development conditions that a candidate must meet in every development cell before it can be committed ([§2.7](#27-development-commit-gate-and-edit-or-continue)). |
| Allowance | A certification attempt's shares `α_i` and `β_i` of the campaign's error budget ([§2.2](#22-joint-error-budget)). |
| Commit | Freezing the working candidate into a certification attempt, which permanently consumes that attempt's allowance ([§2.2](#22-joint-error-budget)). |
| Certification attempt | One frozen candidate, committed after the commit gate, tested on fresh formal trials under one frozen procedure and cap. Its trials test the joint claim and measure improvement over the incumbent. Its certification index `i` numbers it within the campaign. |
| Formal trial | A trial of a certification attempt, drawn from the held-out part after the commit ([§6.3](#63-prompt-tests-and-test-cases)). |
| Logical trial | One assigned test case for one snapshot, judged once. Retries keep its identity. |
| Adoption | Counting a recorded outcome into its cell's passes `S` and failures `F`, strictly in assignment order ([§3.4](#34-adoption-order-and-draining)). |
| Representative trial | A trial drawn from the confirmed distribution and counted in rates. Targeted reruns, promotion runs and regression runs are not representative. |
| Targeted run | A rerun, promotion run or regression run of a chosen test case. It is marked before execution and never enters representative or formal counts ([§6.3](#63-prompt-tests-and-test-cases)). |
| Baseline trials | The incumbent's representative trials of held-out test cases in the certification scope, used for the deficiency claim and comparisons. |
| Four outputs | The estimated pass rate, the improvement over the incumbent, the comparison with the minimum pass rate and the error bars, shown in both phases ([§2.9](#29-four-outputs)). |
| Measurement identity | The recorded conditions that a result was measured under. A result is current while CAT knows of no material change to them, and historical after one ([§5](#5-measurement-identity-and-applicability)). |
| Campaign | One explicitly started effort, with its own 5% joint budget, a fixed starting harness and the one host session that runs it ([§4.1](#41-storage)). |
| Lineage | The evidence history under one confirmed contract within a campaign. |
| Base worktree | The user's Git worktree. Apart from the CAT session directories in its Git-ignored `.cat/sessions/` directory, which hold campaign storage ([§4.1](#41-storage)), only delivery writes to it. |
| CAT session directory | `.cat/sessions/<session-id>/` in the project, owned by one host session: its `temp/` is the session's `CAT_TEMP`, and it holds the campaigns that the session runs ([§4.1](#41-storage), D79, D81). |
| Optimization worktree | CAT's fork of the base, holding the working candidate. |
| Staged source | A symbolic-link-free directory for one snapshot, which the runner copies for each trial. |
| Prompt test configuration | The committed `prompt.json` in the target's test directory, which lists for each harness the trial plugins, and how the project builds each one that it builds ([§4.4](#44-trial-staging)). |
| Certification history | The committed `certifications.json` beside `prompt.json` in the target's test directory, holding the certification record of each certified delivery of the target ([§5](#5-measurement-identity-and-applicability)). |
| Trial plugin | A harness plugin that the prompt test configuration lists, which every trial installs. A plugin that the project builds is installed from a build of the fork commit, with the snapshot's prompt files in place of the build's copies ([§4.4](#44-trial-staging)). |
| Runner | CAT's existing component that runs one nested Claude or Codex session in a fresh isolated run directory (`ClaudeRunner`, `CodexRunner`). |
| Driver | The Java process that each `prompt-optimizer run` command starts. It makes every deterministic decision ([§3.1](#31-nested-sessions), [§3.7](#37-process-model)). |
| Slice | One time-bounded run of the driver inside a foreground command. |

## 2. Statistical design

### 2.1 Claims

All claims concern the pass labels of one frozen decision procedure, meaning CAT's built-in tools plus the frozen
judge ([§6.4](#64-judge)), under a prompt test's confirmed test cases restricted to its held-out part
([§6.3](#63-prompt-tests-and-test-cases)). They are not claims about actual requirement compliance, judge calibration
or unrepresented situations.

The statistical unit is the cell: one prompt test under one trial profile ([§6.6](#66-trial-profiles)). `K` counts the
cells of the certification scope. A prompt test whose prompt files bind one profile has one cell. Every other prompt
test has one cell per profile in the confirmed profile set. A prompt test's formal and baseline trial counts are
multiplied by the number of its cells in the certification scope; when the user certifies only the weakest cell, `K` is
the number of prompt tests. A trial passes when every assertion of its prompt test passes, so a weak assertion cannot
hide behind strong ones. `p_min` is the campaign's minimum pass rate.

- **Certification claim (a):** For every cell `k` of the certification scope, both parts hold:
	- **minimum pass rate:** the candidate's pass probability `pC(k)` exceeds `p_min`;
	- **control comparison:** for every assertion `a` of the prompt test, `pC(k, a) > pN(k, a) − 0.05`. Here `pC(k, a)`
		is the probability that a candidate trial passes assertion `a`, and `pN(k, a)` is the probability that a control
		trial, which runs without the target prompt, passes it. The comparison is per assertion, not per trial: without the
		prompt, an effect assertion such as reading a long file whole usually fails, so the control's whole-trial rate is
		low and a candidate would beat it even if the prompt harmed a guard such as searching. The 5-point margin is fixed:
		proving that the candidate is no worse at all would need unbounded trials when both pass almost always.
	- **incumbent comparison:** for every assertion `a` of the prompt test, `pC(k, a) > pI(k, a) − 0.05`, where
		`pI(k, a)` is the probability that an incumbent trial passes assertion `a`. The whole-trial comparisons with the
		incumbent below cannot show that a candidate traded one assertion for another; this part can. It holds trivially,
		and CAT runs no incumbent trial, when the candidate is the incumbent.
	- **implied comparisons:** when `p_min ≥ 0.95`, the minimum-pass-rate part implies both comparisons, so CAT runs no
		control or incumbent trial and a cell certifies on its minimum-pass-rate part alone (user ruling, 2026-10-14). A
		trial passes only when every assertion passes, and no reference passes an assertion more than always, so
		`pC(k, a) ≥ pC(k) > p_min ≥ 0.95 ≥ pN(k, a) − 0.05`, and likewise for `pI(k, a)`. The certification record says
		that the comparisons follow from the minimum pass rate. Below `p_min = 0.95` the comparisons run as described.
- **Deficiency claim:** The incumbent's pass probability `pI(k)` is below `p_min`. CAT makes this claim only for cells
	where its baseline evidence proves it.
- **Improvement claim (b):** In every cell with a deficiency claim, `pC(k) − pI(k) > 0`.
- **Proven improvement:** In cell `k`, `pC(k) > pI(k)` is proven when one certification attempt's evidence puts the
	candidate's lower confidence bound, at the attempt's per-cell improvement level `β_i / K`, above `UI(k)`, whether or
	not (a) holds. `UI(k)` is the incumbent's upper confidence bound at its baseline's per-cell level `γ_b / K`
	([§2.2](#22-joint-error-budget)). This claim supports the keep-improvement option
	([§2.10](#210-keeping-a-proven-improvement)).
- **Worse finding:** `pC(k) − pI(k) < 0` is proven at the same per-cell level. This finding is not a guarantee; it
	blocks default delivery.

If (a) and the deficiency claim both hold for a cell, then, with `p_min` the minimum pass rate, `pC(k) > p_min > pI(k)`,
so (b) follows without additional trials: the certification test's lower bound `LC(k)` at `α_i` exceeds `p_min` and
`UI(k)` is below `p_min`, so the displayed difference bound `LC(k) − UI(k)` is positive. (b) therefore costs only the
incumbent's baseline measurement, which the four outputs need anyway.

**Minimum pass rate.** The contract author proposes the minimum pass rate `p_min` once per campaign, 0.95 unless the
user's feedback asks for another value, and the user confirms it with the contract
([§6.1](#61-derivation-and-confirmation)). CAT refuses a value outside 0.80 to 0.99 or with more than two decimal
places. Below 0.80, a claim this weak rarely justifies a certification, and the below-minimum test's bets lose power
([§2.3](#23-sequential-tests)); above 0.99, the first attempt needs at least 474 all-pass trials per cell, against 93 at
0.95. A different `p_min` is a revision of the contract ([§7](#7-campaign-lifecycle)). Every threshold that this section
and [§2.7](#27-development-commit-gate-and-edit-or-continue) state for 0.95 scales with `1 − p_min`, and each states its
value at 0.95.

### 2.2 Joint error budget

The campaign's total probability of making any false certification, deficiency or improvement claim is at most 0.05.
The budget is split three ways. Each part is spent by a series `x / (n·(n + 1))`, which sums to `x` over an unlimited
number of terms. A campaign can therefore commit any number of attempts, for any number of candidates, within the same
5%. Each later attempt receives a smaller share and needs more trials ([§2.4](#24-caps)).

- **Certification budget, 0.04:** Certification attempt `i`, numbered from 1 across the whole campaign, receives
	`α_i = 0.04 / (i·(i + 1))`: 2%, 0.667%, 0.333%, and so on.
- **Improvement budget, 0.005:** Certification attempt `i` also receives `β_i = 0.005 / (i·(i + 1))` for the
	improvement claims made from its trials. It is split equally across the attempt's `K` cells, so each cell's
	proven-better decision uses `β_i / K`.
- **Incumbent budget, 0.005:** Incumbent baseline `b`, numbered from 1 across the whole campaign, receives
	`γ_b = 0.005 / (b·(b + 1))`. A campaign starts a new baseline whenever it measures the incumbent again: for a new
	contract lineage or a new measurement identity ([§2.5](#25-incumbent-baseline)). The share is split equally across
	the contract's `K` cells, so each cell's incumbent bounds `UI(k)` and `LI(k)` use `γ_b / K`. Both the deficiency
	claim and the improvement claim rely on `UI(k)`.

An attempt receives its certification index when it is committed and keeps it permanently. Only a commit to a
certification attempt spends allowance. Development trials, promotions and candidates that are never committed consume
none, so development can try any number of candidates.

Claim (a) needs no division of `α_i` among cells or among its parts. It is a conjunction: it is false only if some
cell's true rate is at most the minimum pass rate `p_min`, or some assertion's control or incumbent comparison is false,
and a false
certification then requires that one part's level-`α_i` test to pass incorrectly. Per-cell claims are different. "Proven
better in cells B and D" is false if either part is false, and each cell's test can fail independently, so the
improvement budget and the incumbent budget are divided by `K`. A union bound across attempts, baselines and the three
budgets gives a joint error of at most 0.05. This guarantee requires every attempt to remain valid given the history
that selected it ([§2.6](#26-validity-conditions-and-assumption-review)).

Candidate upper bounds, incumbent lower bounds and the below-control test serve only the worse finding or the end of
an attempt, which can block delivery but never support a claim, so they consume no budget.

The budget is per campaign. An explicit new campaign receives a fresh budget, and the interface says so. Candidate
edits, failed attempts, contract revisions, restarts and cancel-then-resume never reset it.

### 2.3 Sequential tests

Each cell in a certification attempt uses anytime-valid betting tests on the outcomes adopted in assignment order
([§3.4](#34-adoption-order-and-draining)). Let `S` be the number of adopted passes and `F` the number of adopted
failures, and `p_min` the campaign's minimum pass rate.

**Certification test (null: the pass probability is at most the minimum pass rate `p_min`).** The e-value is

```
E↑(S, F) = Σ_m w_m · (1 − c_m + c_m / p_min)^S · (1 − c_m)^F
```

with bets and weights `(c, w) = (0.4, 0.1), (0.6, 0.2), (0.8, 0.5), (1.0, 0.2)`. Each bet `c` is the growth-optimal bet
against the alternative pass rate `r = p_min + (1 − p_min)·c`, which at `p_min = 0.95` is 97%, 98%, 99% and 100%. The
weights favour `p_min + 0.8·(1 − p_min)`, 99% at 0.95. The term with `c = 1.0` vanishes after the first failure, because
`0^0 = 1` and `0^F = 0` for `F ≥ 1`.

**Below-minimum test (null: the pass probability is at least the minimum pass rate `p_min`).** The test bets against
three alternative pass rates below `p_min`. Let `n_short` be the number of shortfalls `1 − p_min` that an alternative
lies below `p_min`, for `n_short = 9, 3, 1`. The alternative is then `p_min − n_short·(1 − p_min)`, and the
growth-optimal bet against it is `λ = min(1, n_short·(1 − p_min) / p_min)`. The e-value weighs the three bets equally:

```
E↓(S, F) = Σ_{n_short = 9, 3, 1} (1/3) · (1 − λ)^S · (1 + λ · p_min / (1 − p_min))^F
```

At `p_min = 0.95` the alternatives are 50%, 80% and 90%, and `λ = 9/19, 3/19, 1/19`. Below `p_min = 0.90` the bet for
`n_short = 9` reaches 1 and stops growing.

**Control comparison (per assertion; null: `pC(k, a) ≤ pN(k, a) − 0.05`).** Control trials run only when `p_min` is
below 0.95 ([§2.1](#21-claims)), and in a cell only after its minimum-pass-rate test certified it
([§3.3](#33-allocation-and-concurrency)). The `j`-th control trial of a cell
pairs with the cell's `j`-th candidate formal trial in assignment order and runs that trial's test case. For assertion
`a`, let `X_j` and `Y_j` be 1 when the candidate trial and the control trial of pair `j` pass `a`, and 0 otherwise, and
let `Z_j = (X_j − Y_j + 1) / 2`, which is 0, ½ or 1. Under the null, the mean of `Z_j` is at most `m = 0.475`. The
e-values are

```
E_ctl↑ = Σ_m (1/4) · Π_j (1 + (f_m / m) · (Z_j − m))
E_ctl↓ = Σ_m (1/4) · Π_j (1 + (f_m / (1 − m)) · (m − Z_j))
```

with `f = 0.25, 0.5, 0.75, 0.9`. `E_ctl↑` tests the null above, and `E_ctl↓` tests the opposite null, that `pC(k, a)` is
at least `pN(k, a) − 0.05`. Each factor is positive, and both e-values depend only on how many pairs gave each value of
`Z`. When both trials pass, as they nearly always do on a guard assertion, `Z = ½` and `E_ctl↑` grows slowly; a pair
that only the control passes shrinks it, and a pair that only the candidate passes grows it fastest.

**Incumbent comparison (per assertion; null: `pC(k, a) ≤ pI(k, a) − 0.05`).** Incumbent trials run the incumbent, the
snapshot recorded at the fork, and follow every rule of control trials: they run in a cell only after its
minimum-pass-rate test certified it, the `j`-th pairs with the cell's `j`-th candidate formal trial in assignment order
and runs that trial's test case, and `E_inc↑` and `E_inc↓` are `E_ctl↑` and `E_ctl↓` computed over the incumbent pairs.
The two comparisons share the candidate formal trials but not their pairs: pair `j` of each comparison holds the same
candidate trial and its own control or incumbent trial. When the candidate is the incumbent, the cell has no incumbent
comparison.

Decisions:

- A cell's minimum-pass-rate part **certifies** in certification attempt `i` when `E↑ > 1/α_i`.
- A cell is **clearly below** in certification attempt `i` when `E↓ > 1/α_i`.
- An assertion's control comparison **passes** in certification attempt `i` when its `E_ctl↑ > 1/α_i`, and its
	incumbent comparison passes when its `E_inc↑ > 1/α_i`. A cell **certifies** when its minimum-pass-rate part certified
	and every assertion's control and incumbent comparisons passed, or, when `p_min ≥ 0.95`, as soon as its
	minimum-pass-rate part certifies, because that part implies both comparisons ([§2.1](#21-claims)).
- An assertion is **below control** in certification attempt `i` when its `E_ctl↓ > 1/α_i`. This ends the attempt
	`not certified: below control`.
- An assertion is **below incumbent** in certification attempt `i` when its `E_inc↓ > 1/α_i`. This ends the attempt
	`not certified: below incumbent`. When one assertion is below control and another below incumbent at the same
	adoption, the attempt ends `not certified: below control`.
- An incumbent cell is **proven deficient** when `E↓ > K/γ_b`, using its baseline's per-cell level `γ_b / K` from
	[§2.2](#22-joint-error-budget).
- A candidate cell is **proven better** in certification attempt `i` when `E↑`, with the null rate `UI(k)` in place of
	`p_min`, exceeds `K/β_i`. `UI(k)` is rounded up to 4 decimal places and frozen when the attempt is committed. The
	incumbent's baseline is complete by then.
- A candidate cell is **proven worse** in certification attempt `i` when `E↓`, with the null rate `LI(k)` in place of
	`p_min`, exceeds `K/β_i`. `LI(k)` is the incumbent's lower bound at `γ_b / K`, rounded down to 4 decimal places and
	frozen at commit. Using the proven-better level makes "proven better" and "proven worse" equally strong statements.
- Equality never decides. Every decision compares exact rationals. An implementation may decide from floating-point
	logarithms only when the margin exceeds a tolerance proven safe, and must use exact arithmetic otherwise.
- Because `E↑` and `E↓` depend only on `(S, F)`, and each control e-value only on its counts of the three values of
	`Z`, a cell's statistical state is fully described by those counts.

Confidence bounds invert `E↑` and `E↓` over the null rate at the stated level. A displayed bound is a display value
and may use floating-point arithmetic; a decision against a frozen null rate uses the exact rules above.

### 2.4 Caps

Before a certification attempt's first draw, CAT freezes `α_i`, `β_i`, `UI(k)` and `LI(k)`, the bets and weights, the
allocation policy, and a per-cell cap. The cap is the smallest number of adopted outcomes at which the probability of
certifying the minimum-pass-rate part reaches 90% for a cell whose true pass rate lies one fifth of the way from 1 down
to the minimum pass rate `p_min`, which is `1 − (1 − p_min)/5`, 99% at `p_min = 0.95`, counting the attempts that stop
early because the cell certifies or is clearly below. CAT computes it with an exact absorbing-state calculation over
`(S, F)`. Calibration that fails or is unavailable blocks admission; CAT never runs an attempt without a cap. The same
cap bounds each control and incumbent comparison's number of pairs.

At `p_min = 0.95`:

| Certification attempt `i` | `α_i` | Minimum all-pass trials per cell | Cap per cell | P(certify within 600) if true rate 98% |
|---:|---:|---:|---:|---:|
| 1 | 2.000% | 93 | 262 | 0.854 |
| 2 | 0.667% | 117 | 328 | 0.759 |
| 3 | 0.333% | 132 | 384 | 0.677 |
| 4 | 0.200% | 143 | 398 | 0.601 |
| 5 | 0.133% | 152 | 409 | 0.568 |
| 10 | 0.0364% | 179 | 481 | 0.433 |
| 50 | 0.00157% | 244 | 638 | 0.183 |
| 100 | 0.000396% | 271 | 712 | 0.108 |

The cost grows slowly with the attempt count: a candidate that passes every trial needs 93 trials per cell in attempt
1 and 271 in attempt 100. These values are regression fixtures for the calibration code. When a cell reaches its cap
with its minimum-pass-rate part, a control comparison or an incumbent comparison undecided, the attempt ends
`not certified: inconclusive`. CAT never raises a frozen cap or reopens an attempt.

A control or incumbent comparison can need more pairs than the cell needed formal trials. CAT then assigns more
candidate formal trials to the cell, each paired with a control trial and an incumbent trial, up to the cap. Those
trials count in `(S, F)` like any other, but cannot undo a certified minimum-pass-rate part, because an anytime-valid
test that has crossed its threshold has decided. A guard assertion sets this cost. In a simulation of attempt 1, when
the candidate and the control each pass a guard 99% of the time, the comparison passed within the cap of 262 pairs 93%
of the time, after a median of 123 pairs; at 98% each, 74% of the time. Even a candidate and a reference that both pass
every trial need 105 pairs, more than the 93 formal trials of the minimum-pass-rate part. An effect assertion that the
control passes 10% of the time passed after a median of 8 pairs. At `p_min ≥ 0.95` no comparison runs, so none of this
cost arises ([§2.1](#21-claims)).

### 2.5 Incumbent baseline

The incumbent is the immutable prompt-system snapshot recorded at the fork. Its baseline is a separate set of
representative trials that draw held-out test cases, like formal trials ([§6.3](#63-prompt-tests-and-test-cases)), so
the incumbent's bounds and the candidate's claims describe the same test cases. These baseline trials run on spare
capacity ([§3.3](#33-allocation-and-concurrency)) until it reaches these targets:

- 50 representative trials in every cell of the certification scope ([§6.6](#66-trial-profiles));
- 50 more trials in a cell of the certification scope whose observed incumbent rate is below the minimum pass rate
	`p_min` but not yet proven deficient, ending at 100.

The incumbent is also the first working candidate. Its development trials draw development test cases and never count
toward the baseline. Control trials are not baseline trials: they run the incumbent with its target prompt file
emptied ([§4.4](#44-trial-staging)), only within a certification attempt and only when `p_min` is below 0.95
([§2.1](#21-claims)). Incumbent trials are not baseline trials either: they run the test cases of the candidate formal
trials they pair with, under the same conditions, and count only toward the incumbent comparisons.

A commit requires these targets to be met. The baseline is reused across candidates while measurement identities stay
comparable ([§5](#5-measurement-identity-and-applicability)). Otherwise the incumbent is measured again as a new
baseline with its own share of the incumbent budget ([§2.2](#22-joint-error-budget)). A new baseline never reuses an
earlier baseline's share, because both could then make a false claim.

At `p_min = 0.95`, for 1 to 8 cells in the first baseline, 50 trials prove deficiency at 10–12 failures (80% observed or
lower), and 100 trials prove it at 16–17 failures (84% observed or lower). An incumbent cell near 90% therefore usually
remains "deficiency unproven". In that case (b) is not required for that cell, and the report shows the descriptive
difference interval instead.

### 2.6 Validity conditions and assumption review

Ville's inequality makes each test valid under every stopping rule and every adaptive choice of the next cell. It
holds provided that, for each cell, every trial's pass probability given all earlier adopted outcomes and
scheduling decisions is at most the null value. The design meets or monitors each threat to that condition:

| Threat | Why it matters | Design response |
|---|---|---|
| Faster trials finishing first | If failures take longer, deciding on completion order biases toward passes. | Adopt outcomes strictly in assignment order ([§3.4](#34-adoption-order-and-draining)). |
| Adaptive cell choice and concurrency | Scheduling depends on past outcomes. | The cell is chosen and persisted before the draw, and the draw within the cell is independent of outcomes. Concurrency changes timing only. Both are allowed by the supermartingale argument. |
| Shared state between concurrent trials | Trials that read each other's files, judge results or assignments are no longer conditionally independent. | Each trial runs in a fresh session with its own isolated project copy and harness home. Trial staging excludes all optimizer state ([§4.4](#44-trial-staging)). |
| Judge memory | A judge that sees earlier trials links outcomes. | One fresh judge session per trial, and the judge receives only that trial's evidence. |
| Provider prompt caching | Shared prefixes are cached across sessions. | Caching changes latency and cost, not sampling given the prompt. This is an assumption, recorded as a limitation. |
| Throttling and load under parallelism | Load could change model behaviour, or turn errors into failures. | Provider errors and rate-limit rejections are infrastructure failures that rerun the same logical trial. Every trial records its concurrency level and throttle state. |
| Parallel conditions differing from real use | Real use is usually one session at a time. | The test stays valid under the weaker null "every trial's conditional rate is at most the minimum pass rate", even when conditions vary. The only risk is that parallel conditions are better than serial ones. The report runs a descriptive comparison of pass rates by concurrency level, and the claim names the measured conditions. |
| Discarding unfavourable outcomes | A retry could hide a failure. | A valid pass or fail outcome is recorded once. Only an attempt with no outcome reruns, as the same logical trial with the same test case. A trial that exceeds its declared time limit is judged on its behaviour, not retried, unless the provider produced no output in the turn that reached it ([§3.2](#32-logical-trials)). A logical trial with 3 infrastructure failures, not counting rate-limit rejections or provider refusals, pauses the campaign; it is never skipped. A blocking development failure discards every unadopted development trial of the working candidate, finished or not, so the discard depends only on adopted outcomes ([§2.7](#27-development-commit-gate-and-edit-or-continue)). |
| Formal outcomes tuning the same candidate | Editing a candidate in response to its formal results. | A formal outcome may inform development, but any edit creates a new candidate and needs a new attempt. |
| Certifying on the test cases that shaped the candidate | A candidate tuned on the test cases that then certify it is measured on its own training data, so the claim overstates how it does on cases it was not tuned for. | CAT splits every prompt test's test cases by expected outcome before any trial. Development, prompt authors and diagnosis see only development test cases; formal trials and baseline trials draw only held-out test cases, and a formal failure reaches development without its test case ([§6.3](#63-prompt-tests-and-test-cases)). |
| Control trials chosen by the candidate's outcomes | A control or incumbent comparison that paired its trials with chosen candidate trials, or stopped on the candidate's outcomes, could favour the candidate. | Each control or incumbent trial runs the test case of the candidate formal trial it pairs with, pairs follow assignment order, and the comparison decides only from its own pairs, up to the frozen cap ([§2.3](#23-sequential-tests)). Running the comparison only in certified cells cannot raise the false-certification probability, because a false certification still needs a false part to pass. |
| Generated workspace-file text deciding the outcome | Filler that happened to contain the answer would make two answers correct. | Only values that the contract author writes decide a test case's expected outcome, and the contract author's instructions list each value generator's words and date range ([§6.3](#63-prompt-tests-and-test-cases)). |

If CAT detects dependence it cannot rule out, the result is `certification unavailable: invalid statistical
assumptions`, not a weaker claim. One example is an assignment that read another trial's records.

### 2.7 Development: commit gate and edit-or-continue

All development numbers apply to one candidate snapshot, one cell and comparable measurement conditions. An edit
creates a new candidate with new counts. Earlier outcomes remain available for diagnosis.

**Commit gate (commit condition).** Every development cell ([§6.6](#66-trial-profiles)) needs all of the
following, where `p_min` is the minimum pass rate:

- at least 100 representative development trials;
- an observed failure rate of at most `(1 − p_min)/5`, which at `p_min = 0.95` is at least 99% observed passes;
- latest two completed batches of 50 each with a failure rate of at most `2·(1 − p_min)/5`, 98% or more passes at 0.95;
- no unresolved reproducible defect or known regression. A defect is unresolved while its diagnosis, round or prompt
	author attempts are in progress; a round that ends without a promotable attempt leaves the working candidate's later
	trials to decide.

The first three are the gate's **count conditions**. The incumbent's baseline targets must also be met
([§2.5](#25-incumbent-baseline)). The gate is a cost control, not evidence. It keeps weak candidates from spending
allowance on a commit they will probably fail. At `p_min = 0.95`, within the first attempt's cap of 262 trials, a
candidate certifies with probability 0.90 if its true rate is 99%, 0.49 if it is 98%, and only 0.17 if it is 97%.

The gate precedes every certification of a candidate, including one in an experiment that runs trials outside CAT,
such as a premise test ([§12](#12-premise-gates)). No short screen replaces it: a candidate passes 30 trials in a row
with probability 0.74 if its true rate is 99% and 0.21 if it is 95%, so a clean screen cannot tell them apart. A
candidate whose development shows a failure rate above the gate's limit, `(1 − p_min)/5` for the minimum pass rate
`p_min`, 1% at 0.95, returns to development, whatever its
latest screen showed (user ruling, 2026-10-04).

**Failure impact.** Each failed representative development trial has an impact, which a triage session decides before
CAT records the outcome (user ruling, 2026-10-03):

- *blocking*: the reply leaves the user without the turn's correct and complete result unless the user acts. The agent
	ended the turn without doing the work, asked for something it could have found or done itself, answered wrongly or
	incompletely, or left an effect that must be undone.
- *self-recovering*: the trial broke a requirement along the way, but the reply still delivers the turn's correct and
	complete result and leaves nothing to undo, so the failure cost only extra work, such as reading more than needed, a
	repeated read or a needless call, or a mistake the agent corrected within the turn.

A failure that the triage cannot place, or whose triage fails 3 times, is blocking. The impact steers development only:
both kinds are failures in every count, estimate and claim, and the decision procedure ([§6.4](#64-judge)) does not
change. A blocking failure in a certification attempt does not end the attempt (user ruling, 2026-10-03).

**Edit-or-continue.** An adopted blocking failure **fails fast** (user ruling, 2026-10-03). CAT discards the working
candidate's development trials that have no adopted outcome, interrupting those that run, and edits at once without
diagnostic reruns, so the round's prompt authors do not wait for those trials. A self-recovering failure's reruns still
in progress end too. The discard depends only on adopted outcomes, never on a discarded trial's own outcome, so adoption
order stays unbiased ([§2.6](#26-validity-conditions-and-assumption-review)). After an adopted self-recovering failure,
CAT:

1. pauses new representative admissions for that candidate; in-flight trials finish;
2. reruns the failing test case up to 3 times as a targeted diagnostic, outside the representative counts;
3. **edits** if the defect is reproducible, meaning a rerun fails or another adopted trial of the same test case in
	the same cell fails before the reruns end, or if the candidate can no longer meet the gate by the 200-trial review
	checkpoint, which means, for the minimum pass rate `p_min`, more than `200·(1 − p_min)/5` failures, 2 at 0.95, in any
	development cell;
4. otherwise **continues** sampling, with concurrency restarting at 1 ([§3.3](#33-allocation-and-concurrency)).

The failure's diagnosis and impact are retained either way.

**Candidate generation and promotion.** A prompt author session edits a copy of the working candidate inside the
confirmed authority ([§4.3](#43-candidates-and-edit-authority)). It receives the failing test cases, each failing
trial's evidence section ([§6.4](#64-judge)) with its tool calls, their exit statuses and outputs, the judge rationales
and built-in tool results, the diagnoses and the contract (D56). It also receives the manifests of the lineage's
earlier attempts with their promotion results, so it can tell which kinds of failure an earlier correction already
targeted (D58). The prompt author works as follows:

1. **Group and classify the failures** before editing. Failures whose evidence first departs from the contract at the
	same action, such as the same mistyped path, the same skipped part of a file or the same answer given without a
	required read, form one *kind*. The prompt author names each kind, lists its failing test cases and classifies it:
	- *routing*: an existing instruction was not loaded or not applied, so the prompt author fixes how the prompt system
		reaches or triggers it;
	- *guidance*: an instruction is missing, ambiguous or contradicts another, so the prompt author amends it;
	- *test defect*: the prompt test breaks a test design rule of [§6.3](#63-prompt-tests-and-test-cases), such as
		needing more work than its requirements need or telling the agent that it is tested, or its expected outcome is
		wrong. The prompt author names the rule or the outcome at fault and makes no prompt edit for it, and a test
		revision corrects the test (below);
	- *not guidance*: an infrastructure fault, which neither a prompt edit nor a test revision can correct, so the prompt
		author makes no edit for it;
	- *conflict*: two requirements of the contract cannot both be satisfied, and the prompt system confirmed with the
		contract already held the text that causes it. The prompt author quotes that text, offers at least two resolutions
		and recommends one, and makes no edit for it. CAT checks each quotation against the confirmed snapshot and rejects
		the report if one is absent, because a conflict that a campaign edit introduced is a *guidance* failure that the
		prompt author resolves (D32).

	A kind that looks like a model limitation, such as a mistyped command, an action that nothing in the prompt
	asked for, or a step that the model skipped although the prompt states it, is *routing* or *guidance*, never *not
	guidance*: what the model sees where it makes that decision can still make the failure less likely (D58). It is a
	*test defect* only when the step lies outside the least work that the test's requirements need.

	An interim message belongs to the reply (D29). Whether a prompt can stop a harness from posting one is not yet
	known, but the prompt can shape its wording, so a failure in an interim message is *routing* or *guidance*, and the
	prompt author corrects the prompt so that every message of the reply complies.
2. **Make the smallest correction** that the evidence supports, in the prompt file that owns the decision (D30):
	- an instruction that a prompt file already states is corrected in that file, `AGENTS.md` and `CLAUDE.md` included;
	- a target that ships with CAT, such as CAT's own rules, skills and optimizer prompts, is corrected at its source
		location in its module, which mappings carry into trials ([§4.4](#44-trial-staging));
	- new guidance for any other target goes into a project rule when CAT is a trial plugin of the campaign's harness,
		meaning a trial plugin whose manifest states CAT's repository ([§4.4](#44-trial-staging)):
		an existing rule under `.cat/rules/` on the same topic first, otherwise a new rule in `.cat/rules/common/`, or in
		`.cat/rules/claude/` or `.cat/rules/codex/` when it depends on that harness. A rule whose guidance applies only
		while the agent works on some files has `paths` frontmatter that matches them, so it loads only for that work;
	- without CAT as a trial plugin, no project rule loads in trials or for the prompt's users, so new guidance goes into
		a prompt file other than `AGENTS.md` or `CLAUDE.md`, which load into every session, and into one of those only
		when it cannot otherwise reach the sessions that need it, such as the reference that loads a new file.

	Removing a contradiction or an ambiguity comes before adding
	emphasis. An edit must not contradict another instruction of the prompt system; the prompt author resolves any
	contradiction that its own edit or an earlier candidate's edit introduced. Capitals, "important" markers and repeated
	instructions are not corrections.
3. **Address every kind.** Each attempt corrects every *routing* and *guidance* kind it receives. No kind waits for a
	later round because it is rare, looks random or looks like a model limitation (D58). When the earlier manifests
	show a correction that already targeted a kind, and the kind failed again, the attempt does not restate or
	strengthen that instruction: it explains why the instruction must be followed, by stating, where the model makes
	the decision, the consequence that violating it has for the result.
4. **Generalize deliberately.** For each change, name one analogous case that must take the same path and one
	dissimilar case that must not receive the new guidance, and word the change for the shared invariant. The
	dissimilar case is a contract test, so the trials measure it: an edit that broadens a requirement's trigger passes
	every test where the trigger is present and fails only where it is absent (D40).
5. **Cite evidence.** The attempt carries a manifest:
	- every changed hunk lists the failing test cases that motivate it, together with its analogous case and the contract
		test that is its dissimilar case;
	- every kind lists its failing test cases, its classification and, when it is *routing* or *guidance*, the hunks that
		address it, or when it is a *test defect*, the test design rule or expected outcome at fault.

CAT rejects an attempt whose manifest omits a changed hunk, leaves a received failure out of every kind, leaves a
*routing* or *guidance* kind without a hunk or a *test defect* kind without the rule or outcome at fault, cites a test
case that is not among the failures it received, or names a dissimilar case that is not a test of the contract. A
rejected attempt contributes no candidate. Its evidence is retained, as for an inventory violation
([§4.6](#46-inventory-validation)). An attempt that classifies every kind as *test defect* or *not guidance* makes no
edit and produces no candidate. Its classifications appear in the round's diagnosis and in the report at a review
checkpoint.

**Test revision (user rulings, 2026-10-13).** For each prompt test with a *test defect* kind, CAT starts a test
revision: a contract author session that receives the confirmed contract, the user's goal, the prompt test, the kind
with its failing test cases and their evidence, and the prompt author's reasons. The session rewrites the prompt test's
directory in a draft directory that holds a copy of it, with the contract tools
([§6.1](#61-derivation-and-confirmation)), so that it keeps every test design rule of
[§6.3](#63-prompt-tests-and-test-cases) and proves the same requirements, or reports that the test is sound, with its
reasons. The user confirmed the requirements, and choosing how to test them is the contract author's work, so a revision
that keeps them needs no confirmation. CAT validates the revised contract as it validates a proposal
([§6.1](#61-derivation-and-confirmation)), refuses one whose requirements differ from the confirmed ones, gives the
revised prompt test new test cases in both parts, and reruns the guard pilot for it. The revised contract starts a new
lineage ([§7](#7-campaign-lifecycle)); a certification attempt that is running ends `not certified: stopped for test
revision`, and the certification index and the spent allowance carry on. The progress summary and the report show each
revision as a difference from the earlier scenario, with its reasons. A report that the test is sound returns the kind
to the next round, which classifies it as *routing* or *guidance*.

The workflow tunes the prompt system by itself and asks the user only when it cannot make progress. CAT pauses the
campaign for the user's choice, as for a conflict, when a test revision reports that no prompt test within the test
design rules can prove a requirement, or that correcting the failure needs a requirement to change. The choices are the
session's proposed requirement change, another change that the user describes, and stop; a chosen change becomes
revision feedback that needs confirmation like any other.

A *conflict* report pauses the campaign for the user's choice: CAT schedules no new trials, and in-flight work
finishes. The choices are each offered resolution, with the recommended one marked, another resolution that the user
describes, and stop. A resolution becomes revision feedback; the revised proposal needs confirmation like any other,
and a confirmed revision starts a new lineage ([§7](#7-campaign-lifecycle)). A certification attempt that is running
when the user chooses a resolution ends `not certified: stopped for contract revision`.

CAT validates the attempt's file inventory, then runs the attempt on the triggering test cases twice each and on the
lineage's regression set once each. The regression set is every development test case that failed in a representative
development trial of any earlier candidate. These **promotion runs** are targeted and never counted. An attempt
**fixes** a triggering test case when every one of its runs there passes and so long a streak would be unlikely for the
working candidate (user ruling, 2026-10-02). The working candidate's record on the test case is its representative
failure there, its diagnostic reruns and any other outcome it has on the same test case; with `s` passes in `n` such
outcomes, its smoothed pass rate is `p = (s + 1) / (n + 2)`. A streak of `k` passes is unlikely when `p^k < 0.10`. Each
triggering test case starts with 2 runs; while every run so far passes and the streak is not yet unlikely, CAT adds one
run, with no upper limit (user ruling, 2026-10-20). Since `p` is below 1, a streak that keeps passing always becomes
unlikely; a failing run means the test case is not fixed. For example, a working candidate that passed 0 of 4 outcomes
on a test case (`p` ≈ 0.17) needs 2 passes, one that passed 1 of 4 (`p` ≈ 0.33) needs 3, one that passed 2 of 4 (`p` =
0.5) needs 4, one that passed 3 of 4 (`p` ≈ 0.67) needs 6, and one that passed 39 of 41 (`p` ≈ 0.93) needs 32. CAT **promotes** the attempt to working candidate when it
fixes at least one triggering test case and fails no regression test case that the current working candidate passes
(user ruling, 2026-10-02). An attempt that fixes some triggering test cases but not others is therefore promoted, and
the test cases it leaves failing return through the next round. Promotion makes the attempt the next working candidate
only; certification still requires the commit gate.

**Revert.** A promoted candidate that does clearly worse than its predecessor is withdrawn (user ruling, 2026-10-02).
While a promoted candidate has not been edited, CAT checks each cell that the promoting round addressed after every
adopted representative outcome there. When the candidate's development 95% Wilson upper bound in that cell falls below
the predecessor's development 95% Wilson lower bound in the same cell, so that the two intervals do not overlap, CAT
reverts (user ruling, 2026-10-14): the predecessor becomes the working candidate again with its retained counts, the
withdrawn candidate's outcomes stay available for diagnosis, and the round that promoted it counts as having produced no
promotable attempt. The check runs before edit-or-continue, so a revert replaces an edit. Comparing with the
predecessor's observed pass rate instead withdrew a candidate as good as its predecessor 25–36% of the time, because
that rate carries the predecessor's own sampling error; with non-overlapping intervals and a predecessor of 50 trials,
2–6% of the time. A candidate at 70% against a predecessor at 90% is then withdrawn within 50 trials 58% of the time
rather than 99%. A predecessor with no representative outcome in the cell gives no basis and is never reverted to there.
A revert goes back one promotion: the restored predecessor is not checked against its own predecessor, because its check
ended when it was edited (user ruling, 2026-10-02). The working candidate's result on a test case is its latest outcome
there. When it has none, the promotion runs also run the working candidate on that test case once.

**Several attempts while the candidate is weak.** While the working candidate is weak, a round's prompt author attempts
run independently and at the same time (user ruling, 2026-10-01). The candidate is weak when, in any development cell of
a prompt test the round addresses, its development estimate (the exploratory 95% Wilson lower bound) is below 80%, or it
has no development outcome there. A weak round makes 3 attempts for each failing prompt test; otherwise it makes each
prompt test's attempts one at a time. Each attempt receives the same evidence and runs through the same promotion runs.
Among the attempts that the promotion rule accepts, the winner has the most passing promotion runs; a tie goes to the
attempt that changes fewer lines, then to the earlier attempt. Only the winner goes through the minimality pass and can
become the working candidate; the others are retained with their evidence. The status records each attempt's promotion
results, so the benefit of several attempts can be measured. All of these runs are targeted and never counted.

**Approaches.** The attempts a prompt test receives in one round each follow a different approach, so that they do not
repeat one search (user ruling, 2026-10-02). A prompt test's attempts take the approaches below in order. Each round
continues the list where that prompt test's previous round stopped, and the list wraps to its start after the last
approach (user ruling, 2026-10-07):

1. Correct the instruction where it is stated: edit the file that states the instruction the failing replies violated
	or misapplied, including `AGENTS.md` or `CLAUDE.md`.
2. Rewrite rather than add: reword, merge or remove existing instructions, adding new text only where no existing
	instruction covers the failing case.
3. Add the missing guidance where the failing sessions make the decision, in an existing or new file.
4. Remove what the failing replies copied: when they reproduce an example, a sample command or an action that a
	prohibition names, delete that text or make it general rather than adding an instruction against it.
5. Explain the consequence where the decision is made: beside the instruction the failing replies broke, state what the
	agent loses or breaks when it does not follow it, rather than repeating the instruction or adding emphasis.
6. Offer one form of each action: where the instructions show several ways to perform the action that the failing
	replies got wrong, keep the form that the passing replies follow and remove the others.
7. Put text to be copied last on its line: when the failing replies miscopy a command, path or value, place it at the
	end of its line with nothing after it.
8. Define before use and keep definitions in agreement: state each term, path or placeholder that the failing replies
	misread before its first use, and make every statement of it agree rather than removing the ones that already
	agree.
9. Give the exact text rather than how to build it: when the failing replies assemble a command, path or value
	wrongly, state the finished command, path or value instead of the parts or steps that produce it.

CAT counts a prompt test's earlier attempts from the manifests and promotion results it retains, so an attempt that left
neither does not advance the list.

**Minimality pass.** Before a promoted attempt becomes the working candidate, CAT removes the parts of it that the
evidence does not need. It divides the attempt's changed hunks into groups and reduces them by delta debugging:

1. Start with two groups.
2. For each group, revert that group's hunks to the working candidate's text.
3. Run the reduced attempt through the same promotion runs, extended as above. It passes when it fixes every
	triggering test case that the attempt it reduces fixed and fails no regression test case that the current working
	candidate passes.
4. When a reduced attempt passes, it replaces the attempt and the reduction continues from it. Otherwise, split the
	groups more finely, down to single hunks.

The pass ends when no single remaining hunk can be removed. These runs are targeted diagnostics: they never enter
representative or formal counts and never consume allowance. Removing a hunk can only drop text the prompt author added
or changed, so the result stays inside the attempt's authority. The minimal attempt is the one CAT promotes. A promoted
working candidate then receives representative trials from zero.

For `h` hunks, the pass needs at most a number of reduction runs that grows with the square of `h`, and usually far
fewer. Each reduction run repeats the promotion runs. Because the promotion rule samples each triggering test case at
most 4 times, a needed hunk can be removed by chance. The representative trials that follow catch that, as they catch
any other weak candidate.

**Review checkpoint.** At 200 representative trials in a development cell for an unchanged candidate with that cell
still unresolved, CAT stops scheduling that cell. When no test revision has examined the cell's prompt test since the
test last changed, CAT first starts one with the cell's failure kinds; if it revises the test, the cell continues under
the new lineage instead of stopping. Otherwise CAT shows the evidence and offers more evidence, targeted optimization,
or stopping. An authorized extension names its next review point.

**Rounds.** In a development round, every currently failing prompt test receives a bounded diagnosis and up to 2 prompt
author attempts made one at a time, or 3 concurrent attempts while the working candidate is weak. A round that promotes
a stronger candidate still short of the commit gate starts the next round automatically. A complete round that produced
no promotable attempt keeps the working candidate and resumes its representative trials; a later failure makes it edit
again under edit-or-continue, so each new round has new failure evidence. Development ends only when the candidate
passes the commit gate and is committed, when a cell reaches its review checkpoint, or when the user stops (user
rulings, 2026-10-05 and 2026-10-06). Failing to prove an attempt better never ends it, and neither do elapsed time or
tokens.

Development estimates use exploratory 95% Wilson intervals. They are labelled `development estimate` and
`not certified`, and they are never presented as simultaneous guarantees.

### 2.8 Decision table

Formal decisions, per certification attempt:

| Formal evidence | Comparison with incumbent | Action |
|---|---|---|
| A cell is clearly below | Any | End the attempt `not certified: below minimum`, retain all evidence, and return to development automatically with each formal failure's cell, prompt test, failed assertions and behavior as diagnostic input, after reproducing each failure group in development ([§6.3](#63-prompt-tests-and-test-cases)). |
| An assertion is below control | Any | End the attempt `not certified: below control` and return to development automatically with the cell, the assertion and its candidate and control pass rates. |
| An assertion is below incumbent | Any | End the attempt `not certified: below incumbent` and return to development automatically with the cell, the assertion and its candidate and incumbent pass rates. |
| A cell reached its cap undecided | Any | End the attempt `not certified: inconclusive` and return to development automatically, after reproducing each failure group in development ([§6.3](#63-prompt-tests-and-test-cases)). |
| Every cell certified | No cell proven worse | `certified`. Every deficient cell is improved by construction. Deliver automatically ([§4.7](#47-delivery)). |
| Every cell certified | Some cell proven worse | `certified`, but retain the incumbent by default. Delivery requires the user to explicitly accept the regression trade-off. |

When a certification attempt ends `not certified` and its evidence meets the keep-improvement conditions, CAT pauses
instead of returning to development automatically. [§2.10](#210-keeping-a-proven-improvement) states that pause and
every later offer of `keep improvement`.

The user can stop at any point. When the user stops, at a review checkpoint, or when certification is unavailable,
CAT offers `accept without certification` if the working candidate is not the incumbent. `keep improvement` and
`accept without certification` are separate explicit choices and never the default. CAT labels the merged result
`improved, not certified` or `not certified` respectively. Certification status and delivery status are always
reported separately.

### 2.9 Four outputs

Both phases display these outputs from the same observations, with unavailable values shown along with their reasons:

| Output | Presentation |
|---|---|
| Estimated pass rate | Candidate passes/trials and rate per cell, under the frozen decision procedure, and each assertion's rate. |
| Improvement over the incumbent | Candidate minus incumbent, in percentage points per cell, with the incumbent's identity, counts, deficiency status and the difference interval. A certification attempt also shows `proven better`, `proven worse` or `undecided` at `β_i / K`. |
| Comparison with the minimum pass rate | Certification attempt: for each cell, each part of claim (a) with its rate, bound and status: the minimum-pass-rate part `certified`, `clearly below` or `undecided`, and each assertion's control comparison `passed`, `below control`, `undecided`, `not run` or, when `p_min ≥ 0.95`, `implied by the minimum pass rate`, with the candidate's and the control's rates, and its incumbent comparison `passed`, `below incumbent`, `undecided`, `not run` or `implied by the minimum pass rate`, with the candidate's and the incumbent's rates; then the cell's `certified`, `clearly below`, `below control`, `below incumbent` or `inconclusive`, with `α_i`. Development phase: the commit-gate assessment and always `not certified`. |
| Error bars | Method, level or exploratory status, sample size and endpoints. The one-sided certification bound is kept visibly distinct from descriptive two-sided intervals. |

Allocation counts are shown apart from the benchmark weights. A pooled adaptive pass fraction is never presented as
the future pass rate. Error bars do not cover unknown usage frequencies or missing scenarios.

### 2.10 Keeping a proven improvement

When reaching the minimum pass rate proves too costly, the user can keep a candidate that is proven better than the
incumbent and stop optimizing. The result is labelled `improved, not certified`.

**Certification evidence only.** Only a certification attempt's trials can prove an improvement. Its trials answer two
questions at once: whether each cell exceeds the minimum pass rate, and whether it beats the incumbent. A candidate's
improvement is therefore known only after it passes the commit gate and is committed. Development results cannot be
reused for it: the candidate was selected because its development results looked best, so testing those same results
afterwards overstates it. The claim uses only the adopted outcomes of one certification attempt.

**Conditions.** One certification attempt under the current contract must show all of the following:

- at least one cell is proven better ([§2.3](#23-sequential-tests));
- no cell is proven worse;
- every other cell was certified or has at least 100 adopted outcomes in that attempt.

The last condition is the regression check: a cell is kept only after enough formal trials to reveal a large
regression. A cell that the attempt left with only a few outcomes, because another cell ended the attempt early, has
not had that check. Attempts under an earlier contract are historical ([§7](#7-campaign-lifecycle))
and do not qualify.

**Best candidate so far.** CAT retains every qualifying attempt, so a proven candidate stays available after the user
chooses `optimize further`, in case later candidates fail. CAT ranks qualifying attempts only against attempts under the
same measurement identity ([§5](#5-measurement-identity-and-applicability)). Within one identity, the best attempt is
the one whose weakest cell has the highest 95% Wilson lower bound on its formal pass rate. Ties go to more cells proven
better, then to the more recent attempt. The ranking is descriptive and does not prove the offered
candidate better than the other qualifying ones. Every qualifying candidate's own improvement claim remains valid,
because the joint budget covers the claims of all attempts at once ([§2.2](#22-joint-error-budget)).

**Earlier measurement identities.** An attempt under an earlier measurement identity of the current contract still
qualifies, but its claim describes the conditions it was measured under, so its result is historical. Results under
different identities are not comparable, so CAT never ranks them against each other or calls one better. CAT offers
the best attempt of each identity as its own choice: the current identity first, then each earlier one, labelled
historical and naming each condition that differs from the current identity.

**When CAT offers it.** When a certification attempt ends without certification and its evidence meets the conditions,
CAT pauses instead of returning to development ([§2.8](#28-decision-table)). It offers `keep improvement`,
`optimize further` or `stop`, so the user decides whether more optimization is worth its cost. At a later review
checkpoint, stop or other uncertified outcome, CAT offers `keep improvement` again whenever any attempt
qualifies. `keep improvement` delivers the candidate the user chose from those offers and ends optimization.

**Report.** Each cell shows its difference interval. A cell that is not proven better might still be slightly
worse within that interval, and the report says so. Keeping an improvement is always an explicit user choice. It never
happens automatically and never produces a `certified` label. Delivery follows [§4.7](#47-delivery) for the frozen
snapshot of the chosen attempt.

## 3. Execution

### 3.1 Nested sessions

CAT runs five kinds of work in nested sessions, each started through the current harness's existing runner
(`ClaudeRunner` or `CodexRunner`) in a fresh isolated run directory:

| Role | Input | Output |
|---|---|---|
| Contract author | Read-only prompt system, the prompt tests already in it, user feedback and the latest guard pilot results; after a failed attempt, each failed prompt test with its development test cases only, its formal failures' cells, failed assertions and behaviors, and the reproduction counts of the test cases it tried | Complete contract proposal in a strict schema, including every prompt test it creates or edits and every test case's values and expected outcome; after a failed attempt, new development test cases for each failed test ([§6.3](#63-prompt-tests-and-test-cases)) |
| Trial agent | Staged candidate project or control source with the test case's workspace files, the assigned turn sequence and its cell's trial profile | Transcript, the reply to each turn, project effects |
| Judge | Frozen rubric, the test case, that trial's evidence section and the assertions it decides | `pass` or `fail` with rationale per assertion, in a strict schema |
| Triage | A failed development trial's test case, reply, evidence section, built-in tool results and failure record | `blocking` or `self-recovering` with rationale, in a strict schema ([§2.7](#27-development-commit-gate-and-edit-or-continue)) |
| Prompt author | Copy of the working candidate and the failure evidence, including each failing trial's evidence section | Edited prompt files in its copy, and the evidence manifest ([§2.7](#27-development-commit-gate-and-edit-or-continue)) |

Each trial session runs under its cell's trial profile ([§6.6](#66-trial-profiles)). The contract author, judge, triage
and prompt author sessions run under the harness's **role profile**: the model and effort that the agent definition of
`optimize-xhigh`, the most capable of CAT's implementation agents, binds in that harness. The contract records the role
profile as part of the frozen judge. Changing it is a judge change ([§5](#5-measurement-identity-and-applicability)).

Trial sessions run as the prompt system's users run it: a trial installs the trial plugins that the campaign
configuration lists for the campaign's harness, and no other plugin, together with the project's own files
([§4.4](#44-trial-staging)). A trial runs with CAT only when the configuration lists CAT. The contract author,
judge, triage and prompt author sessions run without CAT's plugin, so no CAT session rules, hooks or path triggers reach
them, and neither do the user's or the project's rules. Each one's brief carries the complete text of the rules its role
applies instead, read from the installed CAT artifact: the prompt author receives the clear-writing, failure-prevention,
contract-design, rule-organization, prompt-file-dependencies and prompt-file-references rules; the contract author
receives the contract-design rule; the judge and the triage receive none, because their prompts and schemas define their
decisions. A delivered rule's lazy-load directives name the installed artifact's files. Delivering the rules in the
brief removes the role's lazy reads, which Codex truncates and the model then repeats (H33).

A trial needs a judge session only for the assertions that no built-in tool decides. When a built-in tool decides
every assertion, or one whose `on_tool_failure` is `fail` has already failed, CAT records the outcome without starting
a judge ([§6.4](#64-judge)). Every
trial runs as one invocation of the harness for its whole turn sequence.

A judge or contract author output that does not match its schema is an infrastructure failure, after the contract
author's one correction turn ([§6.1](#61-derivation-and-confirmation)). A contract author, judge, triage or prompt author
session that reaches its time limit is stopped and is an infrastructure failure whose message names the session and
the limit; a trial's time limit is behaviour unless the provider produced no output ([§3.2](#32-logical-trials)).
A triage output that does not match its schema reruns the triage, and a third failure makes the failure blocking
([§2.7](#27-development-commit-gate-and-edit-or-continue)). The output is the final response when that response is
exactly one JSON object, either bare or alone inside one Markdown code fence; any other response is invalid (Claude
fences its JSON, H23). Candidate and trial content cannot change the judge's rubric, schema or
authority. Every nested session is counted against the campaign ceiling and the shared harness capacity
([§3.6](#36-shared-harness-capacity)).

Prompt authors may see development test cases and outcomes, and the formal outcomes of attempts that have ended. They
never see the test cases of a certification attempt in progress.

The host agent that runs the `prompt-optimizer` skill does not prompt author, judge or derive. It presents proposals,
choices and progress, and relays the user's answers. Deterministic decisions stay in the Java driver.

### 3.2 Logical trials

A **logical trial** is one assigned test case for one snapshot: a cell, a prompt test, a test case and a complete
prewritten turn sequence. It has one sequence number per cell, purpose and snapshot. Its record moves
through `assigned → running → judged`, and adopted or reconciled states, as follows:

- The assignment record is persisted before any session starts. It holds:
	- the cell, the prompt test and the test case: its values, with each generated value's generator, version, seed and
		parameters, and its expected outcome;
	- the draw stream and position;
	- the complete turn sequence;
	- the snapshot and its purpose (development, targeted, baseline, pilot, certification attempt `i`, or control trial
		of attempt `i` with the candidate formal trial it pairs with);
	- the allocation decision with the evidence revision it used (the number of the campaign's adopted outcomes when CAT
		decided), and the assignment time.
	The judged outcome, the judge identity and every infrastructure attempt are added later. The record establishes
	ordering and guards against selecting favourable test cases; it does not claim the execution is reproducible.
- Each logical trial starts one fresh root session, and every turn of a multi-turn test runs in that session. A turn
	ends when the root session has replied and none of its background child tasks is still running. In Claude, the reply
	to a turn can arrive before background children finish; their completion notices and the follow-up reply belong to
	the same turn (H17). A child can also finish during the root's last model call: the task list is then empty when the
	root replies, yet the CLI still starts a follow-up. Claude reports when its main loop has nothing left to do, and that
	report already accounts for completion notices the root has not read (H25). So a Claude turn ends when Claude reports
	itself idle while its latest task list is empty. Neither a root reply nor a task-list change ends a turn by itself:
	Claude can be idle while a child's own background command runs, and a finished child can restart to handle it. The
	runner sets no time limit of its own on that wait; the test's time limit or an interrupt bounds it. A Claude that
	does not report its state fails the turn at its first `init`, naming its version, because the stream alone cannot
	tell when a completion notice reaches the root (H24). While a turn produces no output, the runner tells its caller
	every 30 seconds that the turn is still running.
- A run that ends without a pass or fail outcome (provider error, rate-limit rejection, interruption, crash) is an
	infrastructure attempt. The same logical trial runs again with the same test case. After 3 infrastructure failures
	of the same logical trial, the campaign pauses with a precise infrastructure failure. Rate-limit rejections and
	provider refusals do not count toward that limit; they follow the hold in [§3.5](#35-volume-safeguards).
- A turn that completes after the harness reconnected to the provider has a pass or fail outcome and is judged
	like any other turn.
- A trial agent may use the harness's own subagents, recursive delegation and child Git worktrees. All of it belongs to
	one logical trial and yields one judged outcome. Child disposition and merges are reconciled before judging. If they
	cannot be determined, the trial records an observation limit and receives no invented label. Codex trials cannot
	create child worktrees under the runner's sandbox (H18); the judge sees that failure as part of the evidence.
- Exceeding the prompt test's time limit is behaviour: CAT judges the trial on its evidence and does not retry it.
	The exception is a turn that reaches the limit with no model output (no reasoning, reply or tool call, and no
	reported token usage) while none of its hooks is still running. The provider never answered, so the run is an
	infrastructure attempt (user ruling, 2026-10-06). A hook still running at the limit belongs to the prompt system,
	so that turn stays behaviour.

### 3.3 Allocation and concurrency

**Allocation (which cell gets the next trial).** CAT decides each admission from the persisted state, highest
priority first:

1. pending targeted work, which is reruns, promotion runs and regression runs;
2. development only: representative assignments go only to development cells ([§6.6](#66-trial-profiles)). Among a
	prompt test's development cells the **coverage floor** applies: no cell may be more than 50 representative
	assignments ahead of the least-sampled one;
3. development: the lowest exploratory Wilson 95% lower bound for the current candidate. An empty cell counts as 0.
	Ties are broken by fewer outcomes, then the contract's risk rank, then prompt test order;
4. formal: candidate trials go only to **open cells** (user ruling, 2026-10-04). Of the cells of one prompt test, its
	development cells are open, and each other scope cell opens once every development cell and every scope cell below
	it in that prompt test are certified. A cell is below another when its model ranks no higher by the capability
	ranking and its effort is no higher, and the two differ. Each prompt test offers its open cell with the fewest
	formal outcomes. Among the offered cells: one trial per cell first, then the cell furthest from certifying, the
	lowest current lower bound at `α_i`; ties are broken by fewer outcomes, then prompt test order. A failure is
	likeliest in the weakest cells, so an attempt that ends not certified most likely ends there, with a cell clearly
	below or at its cap, and a stronger cell spends no trial on a candidate that a weaker cell has not yet certified;
5. formal control trials and incumbent trials, then the further candidate trials that a control or incumbent
	comparison needs ([§2.4](#24-caps)), only in cells whose minimum-pass-rate part has certified and only when `p_min`
	is below 0.95 ([§2.1](#21-claims));
6. baseline trials for the incumbent. The concurrency window below limits only the active candidate's trials. A
	failure never wastes a baseline trial, so these trials may use any capacity left under the campaign ceiling.

Rule 3 sends the first, low-concurrency trials to the riskiest prompt tests, so their failures surface and are fixed
before parallelism rises. Rule 4 does the same across cells within an attempt: with the latest model versions of
`gpt-6-luna`, `gpt-6.1-sol` and `gpt-6-astra` at 5 efforts each, `gpt-6-luna` at `low` certifies first, then
`gpt-6-luna` at `medium` and `gpt-6.1-sol` at `low` together, and so on, in 7 waves rather than 15 steps. Baseline
trials are not ordered: they measure the incumbent, whose trials a candidate's failure never wastes.

**Cheap work first (formal phase).** A certification attempt orders its work so that a failing attempt ends before it
spends the expensive work:

1. CAT runs the built-in tools on each finished trial at once, and the failure of a built-in tool whose
	`on_tool_failure` is `fail` is a definitive trial failure, adopted without a judge;
2. a judge decides the remaining assertions of every trial while the attempt is alive;
3. control and incumbent trials run only in cells that steps 1 and 2 certified (rule 5), because a cell below the
	minimum pass rate ends the attempt whatever those comparisons would show, and not at all when `p_min ≥ 0.95`;
4. the whole-trial comparison with the incumbent reads the baseline, which is already complete at commit, and runs no
	trial.

**Concurrency (how many trials run at once).** For the active candidate, let `n` and `f` be its adopted outcomes and
failures. In the formal phase these include the same snapshot's development outcomes, which is safe because concurrency
changes only timing. Let `û` be the one-sided 95% Clopper–Pearson upper bound on the failure rate from `f` of `n`, with
`û = 1` when `n = 0`. The window `w` is the largest value allowed by every limit below:

- **Risk ceiling:** `floor(1 / (2·û))`, at least 1. Under the pessimistic failure rate, the expected number of failures
	among the trials in flight is at most ½. With no failures it first permits 2 trials at `n = 11`, 4 at `n = 23` and
	8 at `n = 47`. With 1 failure in 100 it permits 10.
- **Slow start:** after any adopted failure of this candidate, `w` returns to 1, and it doubles after each window whose
	trials all pass.
- **Cell limit (formal):** in-flight candidate trials in cell `k` never exceed the passes still required to certify it
	if all of them pass, and are at least 1; in-flight control trials never exceed the pairs still required for its
	slowest undecided control comparison if every pair favours the candidate, and in-flight incumbent trials likewise for
	its incumbent comparisons. This bounds the trials wasted after a decision.
- **Campaign ceiling:** default 8, configurable from 1 to 16.
- **Throttle limit and capacity:** see [§3.5](#35-volume-safeguards) and [§3.6](#36-shared-harness-capacity).

Concurrency is a deterministic function of persisted counts, the clock and the reported throttle state. It never needs
a forecast model.

### 3.4 Adoption order and draining

Within a cell and purpose, CAT adopts an outcome into `(S, F)` only after every earlier-assigned outcome is adopted. A
control or incumbent comparison adopts pair `j` only after pairs 1 to `j − 1` and after both trials of pair `j` have
outcomes. Every statistical decision uses that contiguous prefix only. When a cell reaches a decision that stops it, or
the attempt ends, CAT stops admitting new trials for it. Certified, clearly below, below control and below incumbent
stop a cell. Trials already in flight finish and are recorded as post-decision observations that never change the
decision. A cancelled post-decision trial records no outcome.

### 3.5 Volume safeguards

CAT cannot know the threshold at which a provider flags automated volume, so it keeps volume conservative and honest:

- **Authentication warning:** if the harness authenticates through a consumer subscription login rather than an API
	key, the contract proposal warns about it, states the minimum session count ([§3.9](#39-progress-and-remaining-work)),
	and recommends an API key for campaign-scale use.
- **Ceilings:** the campaign ceiling ([§3.3](#33-allocation-and-concurrency)) and the per-harness capacity shared by
	every CAT process ([§3.6](#36-shared-harness-capacity)). Each counts every nested session, including judges and
	prompt authors.
- **Gradual ramp:** concurrency only grows through the slow-start and risk ceilings.
- **Rate-limit response:** when a harness reports utilization at 80% or more of any window, `w` stops growing. It does
	not drop or hold admissions, even at 100%: a harness can draw on several accounts, such as through a load balancer,
	and report the exhausted window of one while another still serves sessions. Only a rejection shows that the harness
	cannot start one (D84).
- **Rejection hold:** a rate-limit rejection or a provider refusal, an HTTP 403 from the provider or its network edge,
	sets the harness's hold ([§3.6](#36-shared-harness-capacity)) and drops `w` to 1. While the hold lasts, no CAT
	process starts a session of that harness. The hold ends at the reported reset time. Without one, it lasts 1 minute,
	and each rejection that follows the end of a hold doubles the next one, up to 30 minutes; a turn that completes
	resets it to 1 minute. The rejected session runs again after the hold, a trial as the same logical trial. A refusal
	that follows a 30-minute hold pauses the campaign with an infrastructure failure: a refusal that outlasts the longest
	hold is an account or access fault that waiting does not fix (D57).
- **No evasion:** CAT presents its real client identity and never adds randomized human-like pacing, rotates accounts,
	credentials or network addresses, or works around a harness's own limits.
- **Records:** every trial records its concurrency, the campaign's throttle state, the rate-limit windows that its
	session reported, its start and end times, its reported token usage and whether the harness authenticates through a
	subscription login or an API key.

### 3.6 Shared harness capacity

`HarnessCapacity` bounds the total number of nested sessions per harness across independent CAT processes. The
optimizer starts many nested sessions, and this bound is the only limit on their total across processes
([§13](#13-decision-records), D7):

- `main` creates the capacity instance from `HOME` and passes it to whatever needs it. Each harness has its own
	file-lock slots under `$HOME/.cat/locks/harness-capacity/<harness>/`, so Claude and Codex sessions do not compete.
- Both `ClaudeRunnerCli` and `CodexRunnerCli` acquire it. The optimizer acquires one permit per nested session from the
	same instance type.
- Acquisition waits through an injected pause, not `Thread.sleep`. A non-blocking `tryAcquire` lets the driver keep
	scheduling while it waits.
- Each harness's directory also holds its **hold**: the time it ends and the length of the next one. Acquisition by
	the optimizer and by both runner CLIs waits while a hold lasts, so the hold paces every CAT process and every nested
	runner of that harness. A process that records a rejection can extend the hold but never shortens it; a completed
	turn resets the next length to 1 minute ([§3.5](#35-volume-safeguards)).
- Tests construct an unlimited instance or one under a test-owned directory, so no test contends with another test or
	with real sessions.

### 3.7 Process model

The probes in [§11](#11-harness-evidence) rule out a detached background worker. The sandboxed Claude host kills every
descendant of a tool command when the command ends or the user presses ESC, including `setsid` and `nohup` children.
The driver therefore runs as a foreground command in time-bounded **slices**:

- Each `prompt-optimizer run` reconciles state, advances the campaign, and returns a status within its slice. The
	default slice budget is 8 minutes, and the skill invokes it with a tool timeout longer than the slice (10 minutes in
	Claude; a Codex host command ran 662 seconds with no limit, premise P10 and H22).
- Admission stops when the current time plus the 90th-percentile trial duration in this campaign would pass the slice
	deadline. Before 5 trials have completed, CAT uses the prompt test's time limit instead.
- In-flight trials may finish within a 1-minute grace period. CAT then cancels the remainder, which rerun later as the
	same logical trials, and exits.
- Statuses are `running` (call again), `awaiting-user` (a decision with concrete choices), `busy` (another driver holds
	the campaign) or a terminal outcome. The skill loops on `running` and shows progress after each slice.
- One driver writes to a campaign at a time. A second `run` returns `busy` immediately instead of waiting.

### 3.8 Interruption and crash consistency

- **Claude:** ESC kills the command and every descendant (observed in the sandbox). The next `run` reconciles. As a
	backstop for hosts that let the command survive (premise P2), the driver tails the host session transcript for a
	user-interrupt record written after it started.
- **Codex:** ESC leaves the command running (observed). The driver tails
	`$CODEX_HOME/sessions/**/rollout-*-$CODEX_THREAD_ID.jsonl` and, after a `turn_aborted` event later than its start,
	stops admitting, interrupts in-flight sessions through the runner, persists, and exits within 15 seconds. The Codex
	runner interrupts a turn with the app-server's `turn/interrupt` request (H19).
- **Claude runner:** interrupts a turn by writing a stream-json `control_request` with subtype `interrupt` to
	Claude's standard input, not by a signal, so the interrupt works the same on every operating system (H29).
- **Write-ahead order:** CAT persists each assignment before starting its session and each outcome before adopting it.
	The campaign snapshot is replaced atomically after adoption. A crash at any point leaves either a logical trial to
	rerun or an outcome to adopt; it never leaves a duplicate outcome or a lost failure.
- **Reconciliation** runs first in every `run`. It adopts recorded outcomes that are not yet in the snapshot, marks
	sessions that started without an outcome for rerun, and verifies the optimization worktree inventory
	([§4.6](#46-inventory-validation)). It also terminates the processes recorded in the run manifest (`ownerPid`,
	`harnessPid`) of every run left by an earlier driver, since Codex runner processes outlive a killed driver (H20). The
	runner's stale-run maintenance deletes orphaned run directories.

### 3.9 Progress and remaining work

Each status shows the phase, the current candidate, the per-cell four outputs, concurrency and throttle state, and
the **minimum remaining trials**: the number still required if every remaining trial passes.

- **Development:** the sum over development cells of the fewest further all-pass trials after which the cell meets the
	commit gate's count conditions ([§2.7](#27-development-commit-gate-and-edit-or-continue)), where `p_min` is the
	minimum pass rate: at least 100 trials, a failure rate of at most `(1 − p_min)/5`, and the latest two completed
	batches within `2·(1 − p_min)/5`. At `p_min = 0.95` this is at least `max(0, max(100, 100·f_k) − n_k)`, and more when
	a recent batch holds 2 or more failures. Add the incumbent's remaining baseline, and the next attempt's all-pass
	minimum for every cell of the certification scope.
- **Formal:** for each unresolved cell, the smallest `s` such that `E↑(S + s, F)` exceeds `1/α_i`, plus, for each
	undecided control or incumbent comparison, the fewest further pairs after which its `E_ctl↑` or `E_inc↑` exceeds
	`1/α_i` if every pair gives `Z = ½`, the result when both trials pass.

The status also shows each nested-session kind's observed token usage per session (median and 90th percentile) when
the harness reports it. CAT does not show a token total, a time estimate or a completion probability. Any of those
would be a forecast with no calibrated predictor, and a minimum observed token count is not a bound on future trials.
The minimum trial count is exact.

The status compares the working candidate with the incumbent on two observed sizes: the size in bytes and in words of
the target prompt file alone, and the median input tokens per trial session in each cell where both have outcomes. The
size does not count derived sources, project rules or any other member of the prompt system (user ruling, 2026-10-21).
The measurement checks the staged file's bytes against the snapshot. These are measurements of the snapshots and of
completed trials, not forecasts. A minimality pass reports the hunks it removed.

## 4. Workspaces and files

### 4.1 Storage

Each host session has a CAT session directory, `<base>/.cat/sessions/<session-id>/`, named by the harness's session ID.
One component creates it and writes its marker, which records the harness and that harness's configuration directory;
every part of CAT that writes into the directory obtains it from that component (D79). A campaign belongs to the host
session that starts it, and its data lives under `<base>/.cat/sessions/<session-id>/optimizer/<campaign-id>/`:

- the held-out directory `held-out/` ([§6.3](#63-prompt-tests-and-test-cases)), holding:
	- `campaign.json`: a compact snapshot holding identities, the frozen contract and procedure, `(S, F)` per candidate
		and cell, each control and incumbent comparison's pair counts, allocation positions, and pending decisions;
	- the latest proposal, including its derived prompt sources, its draft directory with its prompt tests and every
		test case's values and expected outcome, and the salt of the split;
	- the guard pilot's trial records;
	- one record file per logical trial;
- the base copy of each trial plugin ([§4.4](#44-trial-staging));
- candidate overlays, and staged sources, which are caches: CAT deletes a staged source once its snapshot is no longer
	scheduled and rebuilds it from the overlay when needed;
- the optimization worktree;
- the delivery journal.

The runs of formal and baseline trials and of judges go under a held-out run root beside the runner's own run root, so
that sessions whose output reaches development can be denied them as one directory. Both run roots are in the owning
session's CAT session directory, under `optimizer-runs/`.

Only the owning session runs, changes or delivers a campaign. Resuming that session keeps its ID and continues the
campaign; a Claude `/clear` starts a new session, so the campaign continues only once the earlier session is resumed.
Any other session, in either harness, can show the campaign and the session that owns it, and refuses every other
command for it and every start of a second campaign for its target, naming the session to resume; the refusal changes
no state ([§7](#7-campaign-lifecycle), D17). Once the harness has deleted the owning session, the next session start in
the project removes its CAT session directory (D79), first removing each of its campaigns' optimization worktrees
through Git, so the repository keeps no stale worktree entry. A campaign that does not clean up after itself therefore
keeps its files as long as its session exists.

Earlier versions of CAT kept these directories under `<base>/.cat/work/`. At each session start, before removing stale
directories, CAT moves every directory there that has a session ID as its name and holds a marker to
`<base>/.cat/sessions/`, whichever session wrote it, with one rename per directory. When the new path already exists
or the rename fails, CAT leaves the old directory in place and reports both paths; the session still starts. CAT
leaves every other entry of `.cat/work/`, including its former shared `temp/`, untouched (D81).

Codex enforces those denials through a sandbox helper that refuses to start when it cannot use the directory
`/tmp/codex-daemon-<uid>`, and a Codex sandbox hides that directory from the commands it runs. A campaign started from
a sandboxed Codex session could therefore start no session that is denied reads. When `bwrap` is on the `PATH`, the
Codex runner starts the app-server under bubblewrap with a private, empty `/tmp`, keeping a run directory below `/tmp`
visible.

Loading a campaign reads only the snapshot, so its cost does not grow with the number of trials. Trial records are read
for diagnosis, reports and reconciliation of the in-flight tail. CAT never rebuilds state by replaying all trials.

Before creating anything, CAT checks that `.cat/sessions/` is ignored by Git. If it is not, CAT appends `/.cat/sessions/` to the
repository's `info/exclude` and tells the user, so campaign files never make the base worktree dirty. This file is
local to the clone and never appears in a diff.

A campaign whose certification attempt ends `certified` cleans up after itself once it no longer needs its files: when
the delivery of the certified candidate completes and is verified ([§4.7](#47-delivery)), or when the user keeps the
incumbent instead of accepting a regression ([§2.8](#28-decision-table)). CAT then removes the optimization worktree,
deletes the campaign's runs from both run roots and deletes the campaign directory last, so nothing of the campaign
stays under `.cat/sessions/`. Removal is idempotent: if a fault interrupts it, the next command for the target completes
it. Every other campaign keeps its files for resuming, diagnosis and the best candidate so far (D51).

### 4.2 Fork and optimization worktree

A new campaign requires a base worktree with no staged, unstaged or untracked changes. Otherwise CAT stops before
creating anything and tells the user to commit, stash or remove the changes. CAT records the prompt system's paths,
bytes, modes and symbolic links. It copies participating files that are ignored by Git, or that lie outside the
project, into campaign storage. It then adds a detached optimization worktree at the base `HEAD`, which always holds
the current working candidate for the user to inspect.

When CAT presents a proposal, it records and copies the proposal's prompt tests, with their scenarios, `workspace/`
directories and the shared files that their links reach, and the derived prompt sources that the fork does not yet hold
in the same way, so neither the guard pilot nor the trials read a live workspace file, test or source. Before
confirmation, presenting a later proposal leaves out the files that only earlier proposals needed, together with the
files outside the project that only their links reach. Their copies stay in campaign storage, and a proposal that needs
such a file again gets the recorded copy back rather than the live file. A file that appears after the presentation is
refused at confirmation until CAT presents the proposal again. After confirmation the fork only grows: a file that a
revision no longer needs stays. Presenting a revision records the files it needs that the fork lacks, and its guard
pilot reads them, but the campaign stays on its current fork. Confirming the revision moves the campaign onto the larger
fork: the incumbent and the working candidate keep their edits under new snapshot identities, and the new lineage
measures them ([§7](#7-campaign-lifecycle)). Candidates of earlier lineages stay on the fork they ran on (user ruling,
2026-10-10).

Base-worktree edits after the fork do not invalidate the campaign; delivery handles them ([§4.7](#47-delivery)).

### 4.3 Candidates and edit authority

A candidate is an exact snapshot: the fork's prompt system plus an **overlay** of content edits and ordinary-file
creates, renames and deletes. Its identity is a hash of the overlay and the fork identity. Authority comes from the
confirmed contract:

- In-project prompt files, which are the target prompt file and every in-project file that the prompt system loads:
	content edits and ordinary-file create, rename and delete. An in-project file that a link at or below a prompt
	source resolves to is a prompt file too, editable through the link or at its own path. When CAT is a trial plugin
	of the campaign's harness ([§4.4](#44-trial-staging), D93), the project rules directory `.cat/rules/` is an in-project prompt source, since CAT's
	rule loader loads it in trials as for the prompt's users, so a candidate may create a rule in it (D30).
- Existing symbolic-link entries, their destinations and existing file modes stay fixed.
- Loader configuration, meaning whatever decides which prompt files load, is fixed for the campaign. A needed loader
	change is prepared by the user before a new campaign.
- External files, whether reached through links or read directly from outside the project, are read-only. They may
	belong to the prompt family: CAT reads them, stages copies of them for trials and measures the candidate with them,
	but no candidate edits, creates, renames, deletes, re-modes or retargets one.
- An edit made at a link path applies to its resolved target. If an attempt makes different edits to the same target
	through two paths, CAT rejects it as ambiguous.

When diagnosis finds that a requirement needs an edit outside this authority, CAT warns the user. The warning names the
requirement, the path, the resolved target, the limit and the viable choices: continue within this authority, for
example through an in-project prompt file, or stop or revise the campaign. No choice makes an external file editable.

The warning is an `awaiting-user` decision. The prompt author reports each edit it needed but did not make, and CAT
checks each one against this authority. The warnings that the prompt authors of one round report form one decision, with
each distinct warning listed once. When the user chooses to continue within this authority, CAT records the answered
warnings for the rest of the campaign and does not raise an answered warning again. Every later prompt author is told
that the user declined those edits and must meet their requirements through the in-project prompt files. The prompt
author's list of editable paths names only in-project prompt files: a link to an external file appears only among the
links, as read-only.

### 4.4 Trial staging

The runner refuses a project that contains symbolic links (`HarnessRunManager.copyProject`), and it copies everything
else in the project. CAT therefore materializes **staged sources**, directories that the runner copies for each trial.

**Trial workspace (user ruling, 2026-10-17).** A trial's workspace holds the prompt system and the project files that
its prompt test asks for, not a copy of the whole project. Each candidate, including the incumbent, has a **base
source** that contains:

- the prompt system's files in the project: every file at or below an in-project prompt source, which is the target
	prompt file or a derived prompt source; every file that the harness reads from the project itself, which is an
	instruction file in any directory (`CLAUDE.md` and `CLAUDE.local.md` for Claude Code, `AGENTS.md` and
	`AGENTS.override.md` for Codex) or a file below the harness's project directories (`.claude/` for Claude Code,
	`.codex/` and `.agents/` for Codex); the loader configuration; the project rules `.cat/rules/`; every file of a
	project directory or file that a plugin mapping covers; and every in-project dependent of the target;
- the recorded participating ignored and external prompt files;
- the candidate overlay;
- **every symbolic link replaced by a copy of its resolved target**, so trial writes through a link reach only the trial
	copy and never a live target. A link cycle, or a link to a missing target, is reported in the proposal and blocks
	confirmation until it is resolved.

A trial's workspace is its candidate's base source plus its test case's workspace files: the prompt test's `workspace/`
directory, whose symbolic links that lead outside it reach the trial as copies of their targets and whose other links
stay links ([§6.3](#63-prompt-tests-and-test-cases)). Neither ever holds a prompt test directory of the confirmed
contract, with its scenario and values file, a shared file that a link reaches inside a test root, or the prompt test
configuration. A prompt test whose agent must search, read or build the project's own files links them from its
`workspace/` at the paths where the agent should find them, so a search test links the files among which the agent
searches. CAT reads every link's target when it checks the proposal and records it with the prompt test, so every trial
of the prompt test receives the same copy. Every trial already receives its candidate's version of each prompt file, so
confirmation refuses a link that would copy one in its place. The proposal lists each prompt test's workspace files and
links for the user to review. Control trials and candidate trials of one prompt test receive the same workspace files. A
trial that needs a file its prompt test does not provide fails like any other trial, so the guard pilot shows the
missing file before confirmation. The plugin copies and the prompt measurement read the base source, since no workspace
file reaches a plugin or the prompt. The contract author and a test revision still work in a copy of the whole fork,
because writing a prompt test means reading the project.

**Files that trials receive (user rulings, 2026-10-19).** The user knows, before any trial runs, every file that trials
receive, and no project file reaches a trial unless it is on that list or in a prompt test's `workspace/`. The derived
prompt sources ([§6.1](#61-derivation-and-confirmation)) hold the instruction files, the files that they import, and
every in-project file that a hook or MCP server of the prompt system runs, such as a script that its command names.
The proposal lists every file of the incumbent's base source. CAT refuses, naming each, what a trial cannot receive:

- `start` refuses an import whose file is missing, or lies outside the project, such as `@~/notes.md`, so the user
	moves the file into the project or removes the import;
- `start` refuses a hook that runs before the model acts, at session start or prompt submission, and that fails in a
	trial's workspace although it succeeds in the project. CAT cannot work out from a command which files the command
	reads, so before the contract author starts, it runs each such hook of the prompt system and of the trial plugins
	without a model: once in the staged base source with a new, empty harness home, as a trial runs it, and once in a
	copy of the fork with the user's home, as the user's sessions run it, each with the input that the harness documents
	for the event and the same prompt. A hook whose output differs between the two runs is listed in the proposal with
	the difference, since a time or Git state can differ legitimately. A hook that reads other files under another input
	can still fail in trials, so the proposal states that limit, and a later hook that fails in trials shows in the
	guard pilot's hook status;
- a candidate whose prompt system loads or runs a project file that its trials do not receive, or makes a reference
	that `start` would refuse, contributes no candidate, and the prompt author's failure names the file. A file that the
	prompt author writes lies inside the prompt sources, so trials receive it.

Run directories lie inside the user's project, and a harness can load instruction files from the directories above its
working directory, as Claude Code does. A nested session therefore loads no instruction file from above its run
directory, the boundary above which Git discovery already stops; otherwise every trial would also receive the
project's incumbent instructions (end-to-end finding, 2026-10-20).

Loading instructions is not the only way into the project: a session's file tools and commands can read it too. In a
Claude E1 campaign, 104 of 160 trial sessions used paths in the user's project, and 63 read the project's copy of a
document instead of their workspace's copy, whose content the test case had set. Every nested session therefore runs with its harness sandbox denying reads of the user's
project, except its own run directory and the evidence it reads. A Claude session also receives only the host's deny and
ask rules, not its grants: the host's additional directories and allow rules would let it read and write the host's
directories again. CAT itself allows the `Skill` tool, which Claude Code asks before using and a nested session has no
user to approve (end-to-end finding, 2026-10-20).

Nor does a nested session receive the paths that the host may write, which include the user's Claude and CAT
directories. It writes only its run directory, its own home and the user's npm and Maven caches, which trials share so
that each one does not download the same packages again. Maven finds its repository from the user's account rather than
from the home directory that CAT isolates, or from the user's `MAVEN_OPTS`, so the session keeps the user's
`MAVEN_OPTS` (user ruling, 2026-10-20).

**Prompt test configuration (user rulings, 2026-10-15).** Which plugins a trial installs is the user's decision, so CAT
reads it from the prompt test configuration instead of inferring it from file contents. The configuration belongs to the
target prompt file rather than to one campaign, since every campaign of the target reads it. It is the file
`prompt.json` in the target's test directory ([§8](#8-skill-and-command-interface)), committed with the target's prompt
tests; for the Claude rule `cat-env.md`, it is `client/claude/rules/src/test/resources/claude/cat-env/prompt.json`. It
holds one key per harness, `claude` or `codex`, and each of those holds `plugins`, the list of trial plugins that a
campaign started in that harness installs. For example:

```json
{
	"claude": {
		"plugins": [
			{
				"name": "cat",
				"build": {
					"command": ["client/mvnw", "-f", "client/pom.xml", "-B", "package", "-DskipTests"],
					"output": "client/common/distribution/target/runtime/claude"
				}
			},
			{"name": "formatter@acme-tools"}
		]
	}
}
```

- `name` is the plugin's name. For a plugin that the project does not build, it is `<plugin>@<marketplace>`, as the
	harness's install records name it.
- `build` describes a plugin that the project builds: `output` is the project directory, relative to the project root,
	where the build writes the plugin, and `command` is the build command as a list of arguments, run from the project
	root. A plugin whose project directory is the plugin itself, with no build step, names that directory as its
	`output` and gives no `command`.

`start` refuses, before creating anything, a target whose test directory holds no prompt test configuration or whose
configuration has no key for the harness that runs `start`, and names the file to write. The refusal lists every
plugin installed in the user's harness home, by name and version, and every plugin manifest tracked in the project, so
that the skill can write the configuration from the user's answer (user ruling, 2026-10-21). The skill asks the user
which of them the prompt's users have installed, saying that a certification holds only for users with the chosen
plugins: a plugin that trials leave out, CAT included, can change what the model receives, and one that trials add can
supply guidance that the users lack. For a plugin that the project builds, the skill asks the user for the build command
and output directory. It writes `prompt.json` from the answers, shows it to the user and runs `start` again; the user
commits it with the prompt tests. Every trial installs every
plugin that the configuration lists for the campaign's harness, and no other plugin, including CAT and the plugins
installed in the user's harness home. Without CAT, neither the project's nor the user's CAT rules load in trials, as for
the users of a prompt system who do not install CAT.

**Plugins that the project builds.** A project-built plugin's installed copy can predate its sources, and neither
harness records which sources an installed plugin came from, so CAT installs such a plugin in trials from a build that
it runs itself, never from the user's installed copy. When `start` checks the configuration, it refuses a listed plugin
without `build` whose name matches a plugin manifest (`.claude-plugin/plugin.json` or `.codex-plugin/plugin.json`)
tracked in the project, because the project builds that plugin. After adding the optimization worktree
([§4.2](#42-fork-and-optimization-worktree)), CAT runs each `build.command` there. The base worktree has no uncommitted
changes when a campaign starts, so the build output reflects the user's current sources exactly, and the user's own
build output is never read or changed. Running the project's own build command makes this independent of the project's
language and build tool. `start` stops, showing the command's exit status and the end of its output, when a command
fails, or when an output directory then lacks the harness's plugin manifest or its manifest's `name` differs from the
listed name. CAT then copies each output directory, the plugin's **base**, into campaign storage. A campaign builds
once: its fork commit never changes, and a revision adds only recorded files to the fork
([§4.2](#42-fork-and-optimization-worktree)).

A plugin that the project does not build comes from the user's harness home, where the harness's install records
locate it: Claude Code's `installed_plugins.json`, or Codex's `config.toml` and plugin cache. `start` refuses when the
records do not identify exactly one installed copy, and copies that copy into campaign storage as the plugin's base, so
that a later update of the user's installation does not change the campaign's trials.

**Which trial plugin is CAT (user ruling, 2026-10-21).** A trial plugin is CAT when its manifest's `repository` is
CAT's repository URL, whatever the plugin's name or marketplace: a plugin named `cat` from another repository is not
CAT, and CAT installed under another name or from another marketplace is. URLs compare after ignoring the letter case of
the scheme and host, a trailing `/` and a trailing `.git`; the path keeps its case, and a value that is not an absolute
URL with a host never matches. `start` reads the repository from the manifest of the build output or the installed copy
and records it with the trial plugin in the campaign; a plugin recorded without one, because its manifest states none or
the campaign started before CAT recorded it, is not CAT (D93). Only `https://github.com/holdmyspot-com/cat` is CAT's
repository (user ruling, 2026-10-22): a manifest that states the former `https://github.com/cowwoc/cat` is not CAT.

**Mappings.** A trial must load a candidate's version of a prompt file that a project-built plugin ships, as the
plugin's users do, not the copy in the base that no candidate changes. Where the build puts each file follows from
comparing files, so CAT works it out for each project-built plugin before the contract author starts, and the author
never reads the build ([§6.1](#61-derivation-and-confirmation)):

1. CAT maps a project directory to the base directory that holds a file at every one of its files' relative paths,
	choosing the one where the most of them are identical. When no base directory holds all of them, as when the build
	places a directory's files in different plugin directories, CAT maps each of its files on its own to the base path
	that holds an identical copy.
2. A participating in-project prompt file that does not load from the project itself and that no mapping covers, such
	as one that the build transforms, is listed in the proposal, because a candidate's edit to it would not reach
	trials.

The plugin copy of a base source is the plugin's base copy with every mapped file of the base source written over its
mapped path. A prompt file may map into more than one plugin, and each receives it. A mapped file whose content
differs from the base's is a warning, because the build may transform the file; trials load the source's content.
Mappings copy files unchanged, so a prompt file that the build generates or compiles cannot be a target. CAT refuses a
campaign whose target would not load in trials, such as a rule that only an unlisted plugin loads, and the proposal
lists every other prompt file that loads in production but not in trials.

Each trial installs every trial plugin into the trial's harness home under its own name: a project-built plugin from the
plugin copy of its candidate's base source, any other from its base copy. The trial's project copy keeps the project's
own files that its staged source holds. The runner reuses the user's existing approvals of each plugin's hooks, as it
does for CAT's. A Codex hook that the user has not approved does not run in trials, and the proposal lists it as a
limitation. Claude Code runs every hook of an installed plugin without an approval, so its proposals list none.

Staging excludes `.cat/sessions/`. This keeps a trial agent from reading other trials, judge results or formal
assignments. Excluding the prompt test directories keeps a trial agent from reading a test's assertions, expected
outcomes or other test cases. User-level rules reach the nested harness home through the runner's existing user-rule
handling.

**Test case workspace files.** Each trial's workspace receives its test case's workspace files on top of the base
source: CAT copies the prompt test's `workspace/` directory into the workspace root, keeping each file's path below it,
and replaces every declared variable's `{{name}}` in file names and in the contents of files that are valid UTF-8 and
come from a test root: the prompt test's own files and the shared files that its links reach. A linked project file and
every other file are copied unchanged. A generated value is expanded only into file contents
([§6.3](#63-prompt-tests-and-test-cases)). CAT refuses a test case whose workspace path collides with another workspace
file or with a path of the base source, unless the workspace file is a link to that same project path and so the same
file, so a workspace file never silently replaces project content.

**Initial project (user ruling, 2026-10-12).** Before the first turn, the runner copies each trial's workspace, with its
workspace files, to the run's **initial project**. A built-in tool that reads a file as it was when the trial started,
and the trial record's file changes, compare with that copy rather than with the staged source, so a workspace file is
part of the workspace that the trial started with, not a file that the trial created.

**Passing trials' project copies (user ruling, 2026-10-17).** Once a trial's passing verdict is recorded, CAT deletes
the trial's effects and its initial project; a guard pilot's control trial loses them once it passes every assertion.
A failed trial keeps both, because a prompt author's evidence section and a later diagnosis read them. The trial's
transcript and run record stay until the campaign's files are removed (D51).

**Control source (user rulings, 2026-10-13).** Control trials ([§2.3](#23-sequential-tests)) and the guard pilot use one
more base source, with each trial's project files added as for any candidate: the incumbent with the target prompt file
emptied to zero bytes, so the file still exists but gives no instruction, and with every dependent of the target
removed. A dependent is a part of the prompt system, or of a project-built trial plugin, that exists only to deliver the
target or to carry out, enforce or repair what the target instructs, so that it has no purpose once the target is gone.
For example, a hook that repairs the output of a command that only the target tells the agent to run is a dependent, and
so is a route whose only effect is to load the target. The control source removes nothing else: a file, hook or sentence
that still has a purpose without the target stays as the incumbent has it, even when it mentions the target, so control
trials measure the agent that the user would have without the target, not an agent without any project rules.
Each harness's prompt files are its own instruction files, `CLAUDE.md` for Claude Code and `AGENTS.md` for Codex (D90).
Claude Code treats an empty `CLAUDE.md` as missing and, by default, loads the project's `AGENTS.md` in its place, so a
Claude control trial loads only `CLAUDE.md` files; otherwise a `CLAUDE.md` that only imports `AGENTS.md` would leave the
target's instructions in force.

CAT works out the control source itself; the user does not write it. The contract author lists each dependent with the
part it removes (a whole file, which is deleted; a hook registration, which is unregistered; or an exact span of a file,
which is deleted) and the target instruction it serves. A dependent at a symbolic link removes the copy that trials see
at the link's path and leaves the link's target unchanged, so an external file or a file that other paths share keeps
its content. CAT refuses an entry that names the target, a path outside the
prompt system and the project-built trial plugins, or a span that does not occur exactly once, and the user confirms the
list with the proposal ([§6.1](#61-derivation-and-confirmation)). The control source is the same for every candidate.
Its plugin copies are built from it in the same way, so a target or dependent that a project-built trial plugin ships is
removed there too. When a file that stays still references the target lazily, the reference stays, and the agent may
still try to read it; the proposal records this as a limitation.

### 4.5 Symbolic-link warning

Before confirmation, the proposal lists every symbolic link that it finds in the prompt system. For each link it
shows:

- the link path and its resolved target;
- whether the target is inside or outside the project, and its edit authority;
- the staging consequence: trials see a regular copy at the link path, so a prompt or tool that inspects link metadata
	or resolved paths may behave differently in trials than in production.

This consequence is also recorded as a limitation of every result.

### 4.6 Inventory validation

CAT compares complete inventories, read without following links. An inventory covers tracked, untracked and ignored
paths, symlink metadata, modes, loader configuration and staged targets. CAT compares them:

- **Before admitting a prompt author attempt:** against the attempt's starting snapshot. Any forbidden operation or
	final change rejects the whole attempt as an infrastructure failure. The attempt contributes no candidate and no
	outcome, and its evidence is retained.
- **At every `run`, before promotion and before delivery:** the optimization worktree is checked against CAT's recorded
	operations. An unowned change stops scheduling and asks the user how to proceed, without incorporating the change.
	An edit that is made and reverted before an observation can escape this check; CAT does not claim to detect every
	write.

The authority, conflict and merge decisions are pure functions over in-memory inventories
([§10](#10-testability-contract)).

### 4.7 Delivery

Delivery applies the selected candidate to the base worktree and writes no file outside the project. The candidate is a
certified one, a kept improvement, or an explicitly accepted uncertified one:

1. Verify that the optimization worktree holds only content CAT recorded ([§4.6](#46-inventory-validation)). If the
	selected candidate is not the working candidate, as when the user keeps an earlier improvement, materialize it there
	from its overlay first, so the user can inspect exactly what will be delivered.
2. Preflight the complete combined change set against the fork baseline and the current base. Changes to the same file
	on both sides, structural collisions, changed link destinations and target identity mismatches stop delivery before
	any write and ask the user for direction.
3. Journal the delivery, then apply each change after validating its live preimage. A concurrent edit stops further
	writes without overwriting it. Restart resumes the same journaled delivery and applies each change at most once.
4. Verify the combined result, then report the Git diff and certification applicability.

When the candidate is certified, the change set also appends its certification record
([§5](#5-measurement-identity-and-applicability)) to the target's certification history, `certifications.json` beside
`prompt.json` in the target's test directory, keeping every earlier record, and the delivery's cleanup follows step 4
([§4.1](#41-storage)).

The change set also writes each confirmed prompt test's values file beside its scenario, holding every test case of the
campaign, development, held-out and added ones alike, and writes the scenario and `workspace/` of every prompt test that
the contract author created or edited. A later campaign treats these test cases as development test cases
([§6.3](#63-prompt-tests-and-test-cases)). When the user keeps the incumbent instead of accepting a regression
([§2.8](#28-decision-table)), CAT delivers these test files alone, through the same steps. A campaign that stops without
a delivery writes none of them; its test cases stay in its campaign directory.

Base-only edits survive. If a base-only edit changed another participating prompt source, the merged system is a
different snapshot, so CAT merges it but reports the certification as historical. A partial delivery is never reported
as complete or current.

## 5. Measurement identity and applicability

Every result is a point-in-time observation. CAT records the conditions needed to interpret it:

- the candidate snapshot and the fork identity;
- the contract identity, which covers its prompt tests and every test case, including each generated value's generator,
	version, seed and parameters, and the judge identity;
- for each cell, its configured trial profile and the model and reasoning configuration **observed in harness events**.
	The observed values decide comparability, never the configured alias, because the observed model can differ
	([§11](#11-harness-evidence));
- the role profile that the contract author, judge and prompt author sessions use;
- the harness version;
- the session environment version, which CAT declares and changes only in a release that changes what a session's
	model receives or how its actions take effect: injected content, hooks, the settings and limits the runner applies,
	and the sandbox. A test keeps the runner's generated session settings tied to it;
- the runner and CAT versions, recorded for information;
- the trial plugins: each one's name, the version in its manifest and the identity of its base copy, with the build
	command and mappings of each one that the project builds;
- the loader configuration and the prompt sources observed loading at session start, including links, resolved
	targets and staged identities. A hook that runs on a later event, such as a tool call or a child session's start,
	runs only when the trial takes that action, so it is not a measurement condition; the loader configuration and the
	session environment version already identify its source. The prompt sources include only the skills, agents and
	plugins that come from files: the project's, the trial plugins' and the user configuration's. They leave out those
	that come with the harness or the account, which the harness version identifies. Within one harness version a
	session may list such a source or not, depending on what the harness fetched before it reported its sources (an
	end-to-end finding, 2026-10-20); that variation falls on control and candidate trials alike.

CAT does not claim deterministic replay and does not build closures of binary dependencies. Unobservable conditions are
reported as limitations.

**Host machine (user ruling, 2026-10-22).** Trials run with the host machine's programs, such as the `git` or compiler
that an agent runs, and with outside sources, such as MCP servers, websites and the harness's web search. CAT neither
records nor controls them: they are not measurement conditions, so a change to them is never material, and neither the
measurement identity nor a certification's environment names them. Every certification's environment block and every
`status` report state this as a limitation and recommend running the campaign, and the sessions that later use the
prompt, in the same container image to limit changes to them (D95). A `status` report that shows the certified
environment block states the limitation only there, not again among its limitations (user ruling, 2026-10-22).

A certification shows that the prompt makes the model behave as the contract requires. It needs to be repeated only
when the model's behavior has changed, for example because the model changed, or when the desired behavior has changed.
A change that does not affect the model's behavior keeps it current. A certification is **current** only while CAT
knows of no material change. CAT makes it **historical**, without deleting or rewriting it, when it observes a change
to any of these:

- behavioural prompt content;
- the contract, including its prompt tests and test cases, or the judge version, which define the desired behavior;
- a certification parameter whose new value the certification's claim does not imply (see below);
- the trial model or reasoning profile;
- the trial plugins, their bases, build commands or mappings, which change what the model receives;
- the harness version, the session environment version, the loader configuration or the prompt sources, each of which
	changes what the model receives. The loader configuration covers the MCP servers that trials can reach.

A runner or CAT version change by itself is not material: it reaches the model only through the conditions above, and
a release that changes what a session's model receives changes the session environment version (D21). A change to a
command that the prompt tells the model to run, such as a script that ships with the prompt, is not material either
while the prompt stays unchanged: it fixes the command, and the model still invokes it and acts on its output as
before. A command change that needs the model to act differently comes with a prompt change, which is material (D48).
The host machine's programs and outside sources are not material, since CAT does not record them (D95).

The certification parameters also define the desired behavior: the campaign's minimum pass rate `p_min` and the control
comparison's 5-point margin in claim (a), and the error level `α_i` of the attempt ([§2.1](#21-claims),
[§2.2](#22-joint-error-budget)). CAT records them with each certification. A certification proves its claim and every
weaker claim, so when a CAT release or a later contract changes them it stays current only while its claim implies the
new one: its minimum pass rate is at least the new one, its margin is at most the new one, and its `α_i` is at most the
new level for that attempt. Certifying a pass probability above 95% implies one above 94%, but not one
above 96%, so raising the minimum pass rate to 0.96 makes it historical and lowering it to 0.94 does not (D49).

**Certified environment (user rulings, 2026-10-21).** Every report of a certification, whether the terminal outcome,
the delivery report or `status`, shows the environment it was measured in as one block, drawn from the measurement
identity above and worded for a user who knows none of CAT's terms:

```text
Certified for the following environment:

Codex 0.160.0+41
Plugins: cat 1.0 (built from the project), formatter@acme-tools 2.3.1
Model: gpt-6-luna, reasoning low
Prompt files loaded: AGENTS.md, .cat/rules/common/support.md
Harness settings: the project's .codex/config.toml, with your connection settings and tool restrictions carried over
Trial setup: as introduced in CAT 1.0

Trials ran with this machine's programs and outside sources, such as MCP servers and websites, which CAT neither
records nor controls. To limit changes to them, run the campaign, and the sessions that later use the prompt, in the
same container image.
```

The first line names the harness and its version. `Plugins` lists each trial plugin with the version in its manifest,
marking one that the project builds, or says `none`. `Model` repeats for each cell of the certification scope and gives
the observed model and reasoning, and names no provider (user ruling, 2026-10-22, D94). `Prompt files loaded` lists
the prompt sources that trials observed loading, by project path. `Harness settings` describes the harness
configuration that trials ran with, at both of its levels. The project level is the loader configuration: the project's settings files that existed in trials, from the harness's fixed
list (`.claude/settings.json`, `.claude/settings.local.json` and `.mcp.json` for Claude Code, `.codex/config.toml` for
Codex), named after `the project's`. A settings file implies the harness's defaults for whatever it leaves unset, so the
line names the defaults only when the project has none of the files, reading `defaults` in its place. A change to the
content of a listed file, or a file added or removed, makes the certification historical. The user level is the user's
own harness configuration, which trials do not use beyond what the runner copies, since each trial runs in an isolated
harness home: the user's hooks, MCP servers, enabled plugins and other customizations play no part. For Codex the runner
copies the connection settings of the user's `config.toml`, such as the login method, model and provider; for Claude
Code, the connection variables and the `deny` and `ask` permission rules of the user's `settings.json`. The line ends
`with your connection settings and tool restrictions carried over`, so a project without settings files reads `Harness
settings: defaults, with your connection settings and tool restrictions carried over`. `Trial setup` reports the session
environment version as the CAT release that introduced it, so a later CAT release that left it unchanged does not read
as a different environment. A condition that the reporting session could not check says so, as for
applicability (D51). After the lines, the block states the host machine limitation and its container
recommendation (D95).

**Versions (user ruling, 2026-10-22).** CAT's own version, in `Plugins` and in `Trial setup`, has two parts, such as
`1.0`; CAT's releases are numbered that way, and CAT records the trial setup's release the same way. A third-party
plugin's version is whatever its manifest states, such as `2.3.1`.

**A finished campaign not yet removed (user ruling, 2026-10-22).** `status` of a finished campaign whose certified
candidate was delivered shows the same block while cleanup has not removed the campaign, drawn from the delivered
attempt's record in the target's certification history and compared with the conditions CAT knows now, as for a
target without a campaign. A campaign whose history holds no record of the delivered attempt shows no block.

A certified delivery appends a **certification record** to the target's certification history, `certifications.json`
beside `prompt.json` in the target's test directory, which the user commits with the prompt. Staging leaves the history
out, so no trial reads an earlier result. Each record is a JSON object of a few kilobytes holding the claim and its
measurement identity: the certification index, the scope, each
cell's counts with each control and incumbent comparison's pair counts, the minimum pass rate, the margin and `α_i`, and
every condition listed above, with the prompt sources and the contract by hash. It holds no trial results. Once the
campaign's files are removed ([§4.1](#41-storage)), the record is what remains of the certification: `status` for a
target without a resumable campaign, and the proposal of a new campaign for the target, compare the latest record of
each harness with the conditions CAT knows at that point and report it current or historical, naming each changed
condition, and list the earlier records as history. A finished campaign stays reachable by its identifier from the
session that ran it, until that session is deleted. A condition that only a session observes, such as the trial model,
is reported as unchecked until a later session observes it; so is every harness-specific condition (the provider, the
harness, session environment and judge versions, and the trial model, reasoning and tool-output limit) when the
reporting harness is not the one that certified (D51).

If a material condition changes between trials, CAT pauses as `MEASUREMENT_CHANGED` before the next model call. To
continue, the user restores the condition, starts a new measurement identity, explicitly keeps the existing identity,
or cancels. A change to the loader
configuration cannot start a new measurement identity within the campaign: the user either restores it or keeps it and
starts a new campaign.

**Keep identity (user ruling, 2026-10-24).** For any measurement change except a loader-configuration change,
`keep_identity` explicitly accepts the observed values as equivalent to the recorded ones within this identity.
The original conditions, development and baseline evidence, certification attempts, their indices and allowances,
and pending trial test cases remain. The identity records the accepted observations; a later value not recorded or
accepted still pauses the campaign. Acceptances apply campaign-wide or to the cells where the changed values were
observed. Status and certification reports state that the user accepted changes and retained earlier measurements,
so the result does not silently claim one unchanged environment. A new identity carries no earlier acceptances.
This is an explicit exception to exact comparability and the prohibition on pooling changed conditions below;
it asserts the user's equivalence judgment rather than establishing that the environments behave identically.
An excluded run is still rerun; accepting a change does not recover its discarded verdict (D102).

A run whose observed conditions differ from its trial's measurement identity and have not been explicitly accepted
yields no outcome under that identity. CAT
keeps its record as an observation under the changed conditions. It is neither a pass or fail outcome nor an
infrastructure failure, so it does not count toward the infrastructure-failure limit ([§3.2](#32-logical-trials)). If
the user restores the condition, the same logical trial runs again with the same test case. Whether a run is excluded
depends only on its conditions, never on its outcome, so the exclusion cannot favour the candidate.

Starting a new measurement identity during a certification attempt ends that attempt
`not certified: testing environment changed`. The attempt can never resume, its `α_i` and `β_i` stay spent, and
the next attempt takes the next certification index. The working candidate returns to development under the new
identity, and the incumbent is measured again as a new baseline ([§2.5](#25-incumbent-baseline)). Outcomes under the
old and new identities cannot be combined, and counting only the new outcomes under the same `α_i` would let the
attempt test the same claim twice. Restoring the condition instead lets the attempt continue.

Candidate and incumbent observations are **comparable** only when every material condition that either records is
exactly equal, or explicitly accepted as equivalent within that identity, campaign-wide and in each cell. A condition
that only one of them records
is missing data: it makes comparability `INDETERMINATE` and the comparison unavailable. A condition that neither records
was not observed: it does not prevent comparison and is reported as a limitation (D38). The host machine's programs
and outside sources are not conditions, so they never take part in comparison (D95). CAT names each differing or
missing condition in words, with its cell, instead of reducing the check to one opaque hash. The median trial input
tokens per cell is a size measurement, shown whenever both have outcomes in that cell, whatever their comparability.

## 6. Compliance contract

### 6.1 Derivation and confirmation

A contract author session receives the target prompt file and, when the user stated one, the user's goal. It
inspects the prompt system and proposes the complete contract. Loading follows trial behaviour: the prompt system is
what a trial loads, the project's files and the trial plugins that the prompt test configuration lists
([§4.4](#44-trial-staging)). CAT observes loading where the harness exposes it (premise P9), and otherwise derives it
from the harness's documented loading rules and, when a trial plugin is CAT (D93), CAT's rule loader, recording that limit.
Both harnesses expose hook runs, and Claude exposes its skill and command inventory; no harness names the instruction
files it loaded, or the files that a hook or MCP server runs, so those are derived (H21, [§4.4](#44-trial-staging)). CAT lists the derived files itself when the contract author finishes, from
the trial plugins; the contract author does not list them. The contract author's request already lists the files that
load in trials with the rule that loads each, so the author neither works out the loading rules nor reads CAT's
implementation; CAT's validation reports what the proposal must change.

**Requirements come from what trials load (user ruling, 2026-10-19).** A file that loads only in the user's own
sessions, such as a rule of the CAT that the user installed or the user's own CAT rules when the configuration does not
list CAT, is not part of the prompt system that trials measure, so the contract author's request does not list it and
no requirement comes from it. A prompt system whose behaviour depends on a plugin lists that plugin in its prompt test
configuration; the proposal still names the files that load in the user's sessions but not in trials, as information
(D85).

**Prompt tests as files (user ruling, 2026-10-15).** Each part of a contract is written once, in the form that outlives
the campaign. A prompt test outlives it as its directory ([§6.3](#63-prompt-tests-and-test-cases)), so the contract
author writes prompt tests as files rather than restating them in the proposal. Before the session starts, CAT creates a
new draft directory and copies into it, at their test paths, the directory of every recorded prompt test of the target:
its scenario, `workspace/` and values file, and every shared file that their links reach. The author edits those copies,
converting a scenario in an earlier format in place, and creates each new prompt test as a new directory. It adds each
new test case to the prompt test's values file without a seed; CAT gives it one when it validates the proposal. The
proposal file holds only what belongs to this contract and to no prompt test:

- the requirements, each with its source, trigger and behaviors;
- for each prompt test, its directory in the draft directory, its risk rank, the requirements it proves, the
	requirements whose trigger it leaves absent, its applicability, the requirement that each assertion checks, and any
	interaction it cannot cover;
- the minimum pass rate, the judge's rubric and decision rule, the dependents, the conflicts and the limitations.

A recorded prompt test that the author leaves unchanged therefore costs it nothing to carry, and the user reviews the
same files that delivery writes ([§4.7](#47-delivery)). The draft directory lies in the contract author's run
directory. When the session ends, CAT moves it into the held-out directory with the proposal ([§4.1](#41-storage)),
because its values files hold expected outcomes of both parts. A revision's draft directory starts as a copy of the
latest proposal's.

The request names the draft directory and the proposal file, so the author writes each file once it settles it, in the
turn that inspects the prompt system, instead of ending an inspection turn and writing everything in a second one. A
session that reaches its time limit leaves the files it finished for diagnosis.

**Contract tools (user ruling, 2026-10-15).** Work that needs no judgment is CAT's, not the contract author's. CAT does
the work that every proposal needs before the session starts, and puts the result in the request:

- the files that load in trials, with the rule that loads each (above);
- the trial plugins, with the mappings of each one that the project builds ([§4.4](#44-trial-staging));
- every hook registration of the prompt system and of the trial plugins, with its event, matcher and command, from
	which the author chooses the dependents;
- the draft directory with the recorded prompt tests (above).

Work on what the author is drafting is a contract tool, a command that the request names by its absolute path:

- `contract check` applies to the proposal file and the draft directory, as they stand, every validation that CAT
	applies when the session ends, and prints every problem at once with its file and line or its JSON path. It also
	prints each prompt test's number of distinct expected outcomes. It does not split them into parts, so that the author
	cannot tell which of its test cases are held out; CAT checks the split when the session ends
	([§6.3](#63-prompt-tests-and-test-cases)).
- `contract workspace` writes one test case's workspace files into a directory that the author names: the prompt test's
	`workspace/` with each link replaced by a copy of its target, the case's values in place of the placeholders and its
	generator calls expanded. It prints each file's path, size in bytes and line count, and how its size compares with the
	tool-output limit of each trial profile of the prompt test, so the author can size a workspace file just over a limit
	without reading the harness's or CAT's implementation.

The author still decides everything that needs judgment: the requirements, the prompt tests and their content, the
test cases' values and expected outcomes, the dependents, the conflicts, the limitations, and the judge's rubric and
decision rule.

Aggregate counts never stand in for the items themselves. The proposal that CAT presents lists:

- every requirement and its source, including files reached through links and readable files outside the project. With a
	goal, the contract author first derives the requirements that the goal asks for, each an observable outcome rather
	than a mechanism that a prompt file prescribes for it, together with the operations that the goal implies the prompt
	must not harm. It then proposes, as an extra requirement, each obligation of the prompt system that none of them
	covers, and the user keeps or drops each one (user rulings, 2026-10-13). Without a goal, it derives every requirement
	from the prompt system, and the user confirms them with the contract. Each requirement lists the behaviors through
	which it can fail ([§6.3](#63-prompt-tests-and-test-cases)) and the assertions that check it. This mapping lives in
	the proposal, not in the scenarios, so one scenario can serve contracts that name its requirements differently;
- the minimum pass rate `p_min` ([§2.1](#21-claims));
- the trial profiles: the harness's profile inventory and its source, each prompt file that binds a model or effort,
	each prompt test's profile set and cell count, and the models ranked by advertised capability, with the ranking's
	source ([§6.6](#66-trial-profiles));
- the certification scope choices: for each, the cells it covers, each prompt test's weakest cell, what a claim covers
	and the minimum session count ([§6.6](#66-trial-profiles));
- every prompt test with its risk rank, which allocation uses: the requirements it proves; its scenario, marked as
	recorded, created by the contract author, or recorded and edited, with an edit shown as a difference from the recorded
	file; its applicability; its test cases with their values and expected outcomes, and the number of distinct expected
	outcomes in the development part and in the held-out part ([§6.3](#63-prompt-tests-and-test-cases)); its time limit;
	and any material interaction it cannot cover;
- for every requirement that applies only under a trigger, that trigger and at least one test where it is absent, whose
	expected behaviour is that the obligated action does not occur (D40);
- the judge's rubric and decision rule;
- the target prompt file that the control source empties, and each dependent it removes with the target instruction
	that the dependent serves ([§4.4](#44-trial-staging));
- every file that trials receive from the incumbent's base source, and each hook whose output in a trial's workspace
	differs from its output in the project, with the difference ([§4.4](#44-trial-staging));
- the trial plugins as the prompt test configuration lists them, each with the source of its base: the build output with
	its build command, or the user's installed copy; each project-built plugin's mappings, with each participating
	project file that no mapping covers; the prompt files, hooks, plugins and MCP servers that load or run in the
	user's sessions but not in trials; and the hooks that
	the user has not approved ([§4.4](#44-trial-staging));
- the guard pilot's results (below);
- the statistical procedure: the joint budget, bets, caps for the first attempts, the commit gate, the review
	checkpoint and the baseline targets, at the proposed `p_min`;
- every conflict: requirements that no reply can satisfy together, with the source text that shows it, at least two
	ways to resolve it and the recommended one with its reason;
- the symbolic-link report ([§4.5](#45-symbolic-link-warning)), the authentication and volume warning
	([§3.5](#35-volume-safeguards)), the minimum session count, and known limitations.

CAT validates the proposal before presenting it and reports every problem it finds at once, including a missing
regression scenario when the proposal cannot be parsed. When CAT refuses it, CAT sends the problems back to the same
contract author session once, so that the author corrects the proposal with its inspection still in context; that turn
has a time limit of its own. A corrected proposal that CAT also refuses ends the derivation with its problems. The user
then runs `start` again, or `revise` with feedback when an earlier proposal is still stored.

The user gives feedback, and CAT regenerates and presents the complete proposal again. Only a confirmed proposal governs
trials, and nothing but the guard pilot runs before confirmation. CAT refuses to confirm a proposal that lists a
conflict (D31). The contract author does not decide which requirement prevails; it offers resolutions, such as
exempting one requirement from another or dropping one, and recommends one. The agent asks the user to choose a
resolution for each conflict, or to describe another, and passes the answers to `revise`.

The certification scope is the weakest cell only unless the user chooses another (user ruling, 2026-10-21, which
replaces the rule of 2026-10-04 that CAT had no default scope). `confirm` without a scope confirms the weakest cell
only. The agent presents every choice with the tradeoffs of [§6.6](#66-trial-profiles), says that the weakest cell
only applies unless the user chooses another, and passes a choice to `confirm` only when the user made one. The scope is part of the confirmed contract, so changing it later is a revision
([§7](#7-campaign-lifecycle)).

**Guard pilot (user ruling, 2026-10-10).** A guard assertion states that the agent passes it almost always even without
the target prompt. If that is false, the control comparison on it needs many pairs or ends the attempt, so the user
should learn it before confirming. After a proposal validates and before CAT presents it, CAT runs about 20 control
trials per prompt test, in the prompt test's weakest cell, drawing only development test cases, and decides them with
the proposed decision procedure. The proposal shows each assertion's pass rate in those trials, and why each trial that
ended without a decision ended, such as a built-in tool that the trial's record cannot decide, so that the user can
correct the proposal. A guard assertion whose rate is below the minimum pass rate `p_min` is a warning, not a refusal:
the contract author may have misclassified it, its test cases may be too hard, or the agent may really fail it without
the prompt, which only the user can judge. A revision reruns the pilot for each prompt test whose scenario or test cases
changed, and the contract author's revision brief includes the latest pilot results. Pilot trials are targeted runs:
they never enter representative, formal or baseline counts and spend no allowance.

CAT, not the contract author, does these things:

- adds the expected-outcome assertion to every prompt test ([§6.3](#63-prompt-tests-and-test-cases));
- computes the profile sets;
- copies the recorded regression scenarios of the target prompt file, and only those, into the draft directory, and
	refuses a proposal that lists no prompt test for one of them ([§8](#8-skill-and-command-interface)). The contract
	author keeps each one, converting a scenario in an earlier format to the current one in place
	([§6.3](#63-prompt-tests-and-test-cases));
- builds the trial plugins that the project builds, derives their mappings, the files that load and the hook
	registrations, and provides the contract tools (above);
- refuses a proposal in which a requirement has no prompt test that proves it;
- refuses a proposal in which a requirement that names a trigger has no test where that trigger is absent;
- refuses a proposal in which a Codex prompt file binds a model below version 6 ([§6.6](#66-trial-profiles));
- refuses a target that would not load in trials ([§4.4](#44-trial-staging));
- validates every scenario and test case, gives each generated value its seed and version, splits each prompt test's
	test cases by expected outcome, and refuses a prompt test with fewer than 2 distinct expected outcomes in either part
	([§6.3](#63-prompt-tests-and-test-cases));
- runs the guard pilot.

### 6.2 Test distribution

Every prompt test must pass on its own: the claim is per cell, and a cell is one prompt test under one profile, so no
prompt test can hide behind others. Within a part, CAT draws a prompt test's test cases uniformly. This is an
explicitly labelled balanced benchmark, not usage frequencies, and the report says that actual future usage
frequencies are unknown. Confirmation freezes the test cases of each part, and CAT never changes the draw after seeing
outcomes; only development test cases added after a failed attempt change development's draw
([§6.3](#63-prompt-tests-and-test-cases)). Allocation changes testing effort only.

### 6.3 Prompt tests and test cases

**Prompt tests (user ruling, 2026-10-04).** Every prompt test varies. Its variables, such as a file's content or name,
a value the turn asks about or the wording of a turn, yield distinct test cases that share the prompt test's expected
behaviour. A prompt test that always presents the same test case cannot be certified fairly: development tunes the
candidate on that test case, so certifying on it measures the candidate on its own training data.

**Test design rules (user rulings, 2026-10-13).** The contract author writes, and a test revision rewrites, every prompt
test by three rules:

1. **It proves its requirements.** It lists the requirements it proves, and its assertions check those requirements'
	outcomes, not a mechanism that a prompt file prescribes unless a requirement names that mechanism.
2. **It needs the least work that can still fail them.** Its inputs, turns and actions are the smallest under which a
	requirement can still fail, such as a document just over one part rather than one many parts long. The rule sizes
	each trial; it never reduces the number of trials.
3. **It is organic.** No turn, workspace file or file name tells the agent the expected outcome or that it is being
	tested.

**Scenario format (user rulings, 2026-10-10).** A prompt test is a directory named by its case in snake_case, under
`<root>/.cat/tests/<path>/` or `<module>/src/test/resources/<path>/` ([§8](#8-skill-and-command-interface)). It holds
`scenario.md`, which is required, an optional `workspace/` directory, and the values file once a campaign has delivered
([§4.7](#47-delivery)). Every prompt test that a campaign delivers lives under its target's test directory, the
directory that [§8](#8-skill-and-command-interface) derives from the campaign's target, even a prompt test whose guard
assertions check the target's effect on behavior that other guidance governs (user ruling, 2026-10-10). The scenario
has these sections:

- `# <Title>` and `## Design Goals`, which say what the test proves;
- `## Variables`, one key-value pair per variable: the key is its snake_case name and the value says what the variable's
	value represents. A prompt test with no variable has no test cases to split, so CAT refuses it;
- `## Scenario`, the turn sequence. A single turn is the section's `description`. Several turns are `### Turn <n>`
	subsections, numbered from 1, each with its own `description`;
- `## Expected Behavior`, the keys `description`, what a compliant agent does across the turns, or that an obligated
	action does not occur when the test leaves its trigger absent, and `time_limit_seconds`, how long a compliant agent
	needs, with margin. A recorded scenario that lacks the section is still valid; the contract author adds it, and the
	proposal shows the addition as an edit. These belong to the test, not to one contract, so they live in the scenario
	(user ruling, 2026-10-15);
- `## Assertions`, one `### <id>` subsection per assertion, with a snake_case id. Its keys are `kind`, which is `effect`
	or `guard`; optionally `tool`, a code span holding `<name> <arguments>` ([§6.4](#64-judge)); optionally
	`on_tool_failure`, which is `fail`, the default, or `judge` and requires a `tool`; and `description`, which states
	the assertion.

**Section content (user rulings, 2026-10-10).** `# <Title>` and `## Design Goals` are prose for readers. Every other
section's own content, the text before its first subsection, is key-value pairs: a list item whose bold key is followed
by a colon and the value, `- **key**: value`. A key with several values lists them as nested items or as code spans
separated by commas, and a value continues on lines indented under its item, where it may hold several paragraphs.
`## Scenario` and each of its `### Turn <n>` subsections alone may instead hold prose, which is the value of their
`description` key; when they hold key-value pairs, `description` must have a value. A section that holds only
subsections, such as `## Assertions`, has no content of its own, and the rules apply to each subsection. CAT refuses a
key that the section does not define, prose in a section of key-value pairs, and a section that mixes the two.
Line-oriented `key: value` text could not be told apart from prose that begins with a word and a colon, so the format
never uses it.

An example, `long_document_read_whole/scenario.md`:

```markdown
## Variables

- **notes**: The release-notes file name, without its directory
- **release**: The release whose publication date the turn asks for
- **before**: Log text placed before the release's entry

## Scenario

When was release {{release}} published? The notes are in `docs/{{notes}}`.

## Assertions

### whole_file_read

- **kind**: effect
- **tool**: `read-in-full docs/{{notes}}`
- **description**: The agent reads all of `docs/{{notes}}` before it answers.
```

`{{name}}` is a placeholder for a declared variable. CAT replaces only declared names, so other text in double braces,
such as `${{ github.token }}` in a workflow fixture, stays as it is. `{{expected_outcome}}` is reserved: it stands for
the test case's expected outcome, and no variable may take that name. A scenario never states its test cases: the
contract author writes them to the values file beside it ([§6.1](#61-derivation-and-confirmation)). An assertion whose
id is `expected_outcome` is reserved too.

**Declared kind.** An assertion's `kind` states how the contract author expects it to fare without the target
prompt, and CAT checks that expectation instead of deriving it. The guard pilot
([§6.1](#61-derivation-and-confirmation)) measures each assertion's pass rate without the prompt and warns on a guard
below `p_min`. A low rate is what an effect assertion predicts. On a guard it means that the control comparison on that
assertion may need many pairs or end the attempt, or that the agent fails the operation the guard protects even without
the prompt. A kind derived from the pilot's rates would always agree with them, so the warning could never tell the user
that a protected operation fails without the prompt. The kinds also decide the kind of `expected_outcome`. Certification
still compares every assertion, whatever its kind, with its control trials ([§2.3](#23-sequential-tests)).

**Workspace directory (user ruling, 2026-10-17).** A prompt test's `workspace/` directory is the root of each trial's
workspace: a file at `workspace/<path>` reaches each trial at `<path>`, with placeholders replaced in file names and in
the contents of UTF-8 files from a test root ([§4.4](#44-trial-staging)). A value used in a file name must be a single
name: not empty, not `.` or `..`, without `/`, `\`, control characters, or any of `< > : " | ? *`, which Windows
forbids. CAT refuses a test case that breaks these rules or whose file names collide.

A symbolic link in `workspace/` whose target is another entry of the same `workspace/` reaches each trial as the same
link, so the trial sees those paths related as the prompt test relates them. Every other link reaches each trial as a
copy of its resolved target, a file or a whole directory, so a trial's workspace holds no link that leads outside it.
When `workspace/a` links to `workspace/b` and `workspace/b` links to a file outside `workspace/`, the trial receives
`a` as a link to `b` and `b` as a copy of that file. A link inside a directory that an outside link copies is copied too.
A link to a file or directory of the project outside every test root gives the trial that content as the project holds
it, so a prompt test places project files, such as the sources an agent searches, at the paths where the agent should
find them. A link is relative to its own directory. CAT refuses a link that is absolute, whose target is missing or
lies outside the project, that forms a cycle, whose target directory holds the link, or that reaches a prompt test
directory, and confirmation refuses a link that copies a prompt file, because every trial receives the candidate's
version of each prompt file.

Git records a link as its target's path, and a checkout recreates it only where the platform lets Git create links. Git
for Windows creates them only when `core.symlinks` is true and the process may create links, which Developer Mode
allows; otherwise it writes a small file that holds the target's path, keeps recording a link, and reports nothing. CAT
therefore refuses a prompt test whose Git index records a link at a path that holds another kind of file, or none, and
names `core.symlinks` and Developer Mode as the remedy, so a trial never receives a path in place of the content it
names. The proposal lists every file that a linked directory gives each trial.

**Shared files (user rulings, 2026-10-10 and 2026-10-17).** Prompt tests share files through a directory outside any
prompt test, such as `<path>/shared/` beside the prompt tests of one guidance: each prompt test that uses a shared file
or directory links it from its own `workspace/` at the path where its trials should find it. Shared files follow the
same placeholder rules as the prompt test's own: a placeholder for a variable that the prompt test does not declare
stays as written. Staging leaves out every file or directory inside a test root that a link of a cited or delivered
prompt test reaches, as it does the prompt test directories. In a draft directory, CAT resolves a link as it would at
the prompt test's path in the project: a target that the draft holds comes from the draft, and any other from the fork.
Delivery writes a shared file that the draft creates at its path and leaves one that the campaign did not change where
it is. For a shared file that the campaign changed, it replaces the link of each prompt test that the change is for with
a copy that holds the change, a link to a directory becoming a directory of copies, so delivery never edits a file that
other prompt tests read. Directories never pass files to their descendants implicitly: a guidance's prompt tests that
need different shared files would otherwise each receive every file their ancestors hold.

**Test cases (user rulings, 2026-10-09 and 2026-10-10).** A test case is a prompt test with one value for each of its
variables, together with its **expected outcome**: the result a compliant reply gives, such as the date that the turn
asks for. The contract author writes every test case's values and expected outcome when it proposes the contract. Two
test cases with the same values are the same test case, so CAT records a repeated draw as another trial of that test
case, and every outcome on a test case counts toward its record. "Expected outcome" never means a trial's pass or fail
result.

**Values.** A value is text or a call to a value generator built into CAT. The contract author writes a call as
`{"generator": "log_lines", "count": 18000}`; when it creates the test case, CAT adds a random `seed` and the
generator's current `version`, and never changes them afterwards. Two test cases that the contract author writes with
the same parameters receive different seeds, so they are different test cases. A generator expands its seed and
parameters deterministically, with `java.util.Random`, whose sequence for a seed the Java specification fixes, and
with templates and a small word list built into CAT. Its output is UTF-8 with `\n` line endings. Each generator's
name keeps its versions: a later CAT that changes `log_lines` adds version 2 and still expands version 1 as before, so
an old values file rebuilds the same files. CAT starts with `log_lines`, whose lines look like real application logs:

```
2025-02-11T08:14:03.512Z INFO  billing.InvoiceService - processed batch 4821 in 37 ms
```

Each line must look real by itself, in format, words and token density, because a file of gibberish tells the agent it
is being tested and costs a different number of tokens per line than real text; the file as a whole need not mean
anything. Generated text never decides a test case's expected outcome: only the values the contract author writes do.
The contract author's instructions list each generator's words and date range, so it can write facts that the filler
never contains. A generated value may appear only in the contents of workspace files; CAT refuses one in a turn, an
assertion, a `tool` value or a file name.

**Development and held-out parts (user ruling, 2026-10-10).** CAT draws a random salt when it validates a campaign's
first proposal and keeps it for the campaign. It assigns each test case to the **development part** or the **held-out
part** by a hash of its normalized expected outcome under the salt, each with probability ½, before any trial and
whatever the outcomes. Normalizing applies Unicode NFC, removes leading and trailing whitespace, replaces each run of
whitespace with one space and converts to lower case, so test cases whose outcomes differ only in spelling land in the
same part. Splitting on the expected outcome keeps every held-out answer out of development: a split on all values
together would let a held-out test case repeat a development test case's answer in a different workspace, so tuning
could fit it. CAT refuses a prompt test with fewer than 2 distinct expected outcomes in either part, so that each part
varies; the contract author then adds test cases.

Test cases from a committed values file belong to the development part in every later campaign, because an earlier
campaign's development may have tuned on them and its report may have shown the held-out ones. The held-out part of a
later campaign holds only its new test cases whose normalized expected outcome appears in no committed test case.
CAT adds a recorded scenario's committed test cases to the prompt test that cites the scenario; the contract author
sees them with the scenario and writes new test cases that differ from them.

- Development trials, targeted runs, the guard pilot, triage, diagnosis and prompt author sessions draw or receive
	development test cases only.
- Formal trials, their control trials and the incumbent's baseline trials draw held-out test cases only.
- A held-out test case, its reply and its judge rationale never reach a session that shapes a candidate. From a formal
	failure, development receives only its cell, its prompt test, the assertions it failed and their behaviors. The
	user's report may show held-out test cases.

**Held-out content on disk.** Both harnesses' sandboxes limit what a session writes, not what it reads, so withholding
held-out content from a brief does not keep a session from reading it. CAT keeps every file that holds held-out content
in one of two directories: the campaign's **held-out directory**, which holds the snapshot, the proposal and the trial
records ([§4.1](#41-storage)), and the **held-out run root**, which holds the run directories of formal, control and
baseline trials and of judges. Every session whose output reaches development (development trials, targeted runs,
triage, diagnosis, prompt authors and the contract author) runs with its harness sandbox denying reads of both
directories, for its commands and its file tools alike. CAT denies whole directories, because in Codex a file denied by
name becomes readable again once it is replaced. Formal, control and baseline trials and judges are not denied: they
read held-out content by design, and only a failure summary of theirs reaches development. CAT refuses to start a
campaign when its harness cannot apply the denial, for example when the host's settings turn the sandbox off.

**Development test cases after a failed attempt (user ruling, 2026-10-04).** The development part may hold no test
case that triggers a formal failure, so development could find nothing to edit. When a certification attempt ends with
formal failures, a contract author session receives, for each prompt test with a formal failure, that prompt test with
only its development test cases, and each failure's cell, failed assertions and behaviors. It writes new development
test cases for that test, at least 4, that exercise the failed assertions through that behavior. CAT refuses a new test
case whose normalized expected outcome equals a held-out one, and validates it like any other. The new test cases
belong to the development part only, and the held-out part never changes. They join development only once they
reproduce the failure (below). Development draws of that test then give the added test cases, together, half of the
test's draws, the other half going to its confirmed development test cases. A failure on an added test case is an
ordinary development failure, which triage and prompt authors receive in full. The development distribution has
changed, so development counts restart for every candidate, and the commit gate needs new trials that include the
added test cases. The added test cases are campaign state, not part of the confirmed contract: they change neither the
claim nor the measurement identity. The user's report lists them, and delivery writes them to the values file.

**Failure behavior (user ruling, 2026-10-04).** A failed assertion can fail in several ways, and development aims at
the wrong one when it knows only the assertion. The contract lists, per requirement, the behaviors through which it can
fail, such as no tool call, a required file not read in full, a search where a read was required, a read where a
search was required, or an unneeded call. A failed built-in tool names the behavior it detects, and the judge names
one of the requirement's listed behaviors, or `other`, for each assertion it fails. A behavior names no content of a
test case, so a formal failure's summary carries it to development along with the cell, the test and the failed
assertions.

**Reproduction before tuning (user ruling, 2026-10-04).** A formal failure can be too rare in development to edit
against, and an edit made without the failure in view tends to trade one rare failure for another. So after a failed
attempt, before any prompt author session works on it, CAT reproduces each **failure group**: the formal failures that
share a cell, a test, a failed assertion and a behavior.

1. The contract author writes the group's new development test cases, as above.
2. CAT runs reproduction trials of those test cases in the group's cell, up to 100 per round, and stops the round once
	the assertion has failed twice with the group's behavior. Reproduction trials are development trials of the
	working candidate, targeted at the group's test cases, so they never enter representative counts; development
	counts restart when the test cases join.
3. Two such failures reproduce the group: its test cases join development, and development tunes against them.
	Otherwise the contract author receives the test cases that it tried and their reproduction counts, without replies,
	and writes different ones, which replace them.
4. A round that does not reproduce the group may be repeated under a **stress setting** that makes the behavior
	likelier: the same model at a lower effort, or a tighter harness limit such as Codex's tool-output limit. A
	reproduction there makes that stressed profile a development cell, so development tunes in it and the commit
	gate covers it.
5. After 4 rounds without reproduction, CAT adds no test cases for the group, records it as not reproduced with its
	formal and reproduction counts, and pauses. The user chooses more rounds, a new attempt with the unchanged
	candidate once it passes the commit gate, or stopping.

A candidate is therefore certified on test cases that played no part in selecting or editing it, and every claim
covers the held-out part of the confirmed test cases ([§2.1](#21-claims)).

Draws are independent of candidate identity and outcomes, from separate durable streams for development, formal
trials and baseline trials, each confined to its part; a control trial runs its paired trial's test case and draws
nothing. Formal draws for an attempt begin only after its candidate and procedure are frozen. Targeted diagnostics,
meaning reruns, promotion runs and regression runs, are marked before execution and never enter representative or
formal counts. Repeated draws of the same test case within a part are legitimate and are never rejected; each is
another trial of that test case. An obligation that needs response-contingent follow-up turns is reported as a
coverage gap and leads to `certification unavailable: unsupported test interaction`. A test case or judge validation
failure is an infrastructure failure, not evidence.

**Earlier scenarios.** A scenario written before this format, with its context in prose and its values fixed in the
text, is input that the contract author converts in the draft directory: it writes the variables, the turns with
placeholders, the workspace files and the test cases, and the proposal shows the conversion as an edit of the recorded
file.

### 6.4 Judge

A trial's pass label comes from one frozen decision procedure in two parts. A trial passes when every assertion of its
prompt test passes, including the expected-outcome assertion.

**Expected-outcome assertion (user ruling, 2026-10-10).** CAT adds to every prompt test the assertion
`expected_outcome`: "the reply's result matches this test case's expected outcome". The judge decides it. It is a
guard when every other assertion of the test is a guard, and an effect otherwise, because a result that needs the
prompt's behaviour, such as an answer found only by reading a long file whole, depends on the prompt. It replaces the
general requirement that each reply correctly and completely does what the turn asked: a judge disagrees less often on a
specific expected answer than on "complete and correct", and every judge error counts against the minimum pass rate.

**Built-in tools (user rulings, 2026-10-10).** A mechanical assertion names a tool built into CAT in its `tool` key. CAT
runs the tool on the trial record with no model, and its verdict is final unless the assertion leaves a failure to the
judge (below): the judge neither repeats nor overrides it. The built-in tools are a closed set that CAT implements and
its own unit tests verify; no user or model writes their code. The `tool` key names CAT's built-in tool, not a tool of
the trial agent; an assertion's prose may still describe the agent's tools, such as "the agent used the Read tool".
Arguments are separated by spaces; an argument that contains a space or a double quote is written in double quotes, with
`\"` and `\\` escapes. CAT replaces placeholders in each argument after splitting, so a value with spaces stays one
argument. A built-in tool that examines replies examines every message of every turn's reply unless its first argument
is `turn=<n>`.

| Built-in tool | Passes when |
|---|---|
| `exact-text <text>` | every examined reply equals the text, ignoring leading and trailing whitespace |
| `forbidden-text <text>` | no examined reply contains the text |
| `pattern <regex>` | every examined reply contains a match of the Java regular expression |
| `count <regex> <minimum> <maximum>` | the number of matches in the examined replies lies within the bounds |
| `file-exists <path>`, `file-absent <path>` | the path exists, or does not exist, in the workspace after the trial |
| `file-content <path> <text>` | the file after the trial equals the text, with CRLF line endings read as `\n` |
| `exit-status <regex> <status>` | the last recorded command that matches the expression exited with the status |
| `tool-used <tool>`, `tool-unused <tool>` | the trial called, or never called, the tool |
| `no-tool-call` | the trial made no tool call |
| `read-in-full <path>` | every non-blank line of the file in the initial project appeared in the trial's tool output, as a whole output line or after a line-number prefix |
| `read-at-most <path> <percent>` | at most that percentage of the file's non-blank lines appeared in the trial's tool output |

**Judge on tool failure (user ruling, 2026-10-11).** An assertion's `on_tool_failure` says what a failure of its
built-in tool means. With `fail`, the default, the failure fails the trial. With `judge`, the tool only accepts the
common replies: when it passes, the assertion passes without the judge, and when it fails, the judge decides the
assertion from its `description`, with the tool's observed value as evidence rather than a verdict. The description,
not the tool, then defines the assertion, so a contract author gives `judge` only to a tool that passes no reply that
the description rejects; a tool that could pass a wrong reply would let that reply skip the judge. A reply that names
the changed component in another wording, such as "session-store" for the session store, fails a word match but
satisfies an assertion that the reply names the component (H34). `fail` stays the default because most tools observe
everything that their assertion requires, such as `read-in-full`, and no judge should excuse a file that was not read.
A trial needs the judge for the expected outcome anyway, so `judge` adds no judge session; it lets the common replies
pass whatever the judge would say, while an unusual wording still gets the judge's reading.

A built-in tool decides only what it can observe. Whether a reply is correct, relevant, complete or appropriate stays
with the judge. A built-in tool that examines reply text reads the whole reply: every assistant message of the turn, in
order, ending with the final response. A built-in tool whose evidence the trial record lacks, such as an exit status
that Claude does not report (H30), is an infrastructure failure of the decision procedure, never a pass or fail outcome;
CAT refuses a proposal that names such a built-in tool for a harness that cannot supply its evidence.

**Trial record.** CAT renders one normalized **trial record** from the trial's harness events and its file changes, so
neither a built-in tool nor the judge reads a log. It holds, per turn and in order: every tool call with its input, its
exit status when the harness reports one (H30) and its complete output; each child agent's activity in the same form;
and each hook's outcome by status only. After the turns, it lists every file the trial changed with its difference from
the initial project. The record is complete, so a built-in tool or the judge can establish that something did not happen,
and it is de-duplicated: streaming fragments, telemetry and the prompt text injected at session start are left out,
because the judge decides the trial's behaviour, not the prompt system that the candidate snapshot already identifies
(H32).

**Judge.** The contract names one frozen judge profile: the role profile ([§3.1](#31-nested-sessions)), prompt, rubric,
decision rule and result schema. The judge decides every assertion without a `tool` key, and every assertion whose
built-in tool failed under `on_tool_failure: judge`, from the trial record, which it receives as its **evidence
section**, with the test case's values and expected outcome. It receives each turn's reply as that turn's messages in
order, so an interim message is judged like the final response. It sees the built-in tool results as evidence but cannot
override a result that decided its assertion. It returns `pass` or `fail` with a rationale for each assertion it
decides.

The judge is identified by its **judge version**, a number that CAT declares per harness and increments in every
release that changes a property that can change a verdict: the role profile, the prompt, the rubric, the decision rule,
the result schema, or what the evidence section holds for the same harness events. A test keeps the version tied to
those properties, so a change to one of them without a new version fails the build. A new judge version is a judge
change, and it makes a certification historical unless its identity explicitly accepts that version
([§5](#5-measurement-identity-and-applicability)). Repeating a judge
session on the same evidence is not a change. A change to a built-in tool's behaviour is a CAT release that changes
how assertions are decided, so it gets a new tool name or version in the same way as a value generator.

CAT runs the built-in tools first. When one fails under `on_tool_failure: fail`, or when they decide every assertion,
CAT records the outcome without a judge session. Every prompt test has the expected-outcome assertion, which the judge
decides, so a trial whose built-in tools all pass still needs a judge.

A trial that fails a built-in tool under `on_tool_failure: fail` records which tool failed, its observed value and
its behavior. The prompt author
receives that record in place of a judge rationale.

### 6.5 Certifiable scope

- **Certifiable outputs:** text, plus observable file and tool effects. A requirement for image, audio or video
	output makes certification unavailable.
- **Remote and MCP responses:** allowed as task data. When they supply governing instructions, for the agent or for a
	child, certification is unavailable. The source's role decides this, not its wording.
- **Trial writes:** allowed only in the trial copy or in disposable external fixtures. External services need confirmed
	read-only credentials or disposable fixtures before admission. A known persistent write is refused before dispatch
	and produces no outcome.
- **Host executables:** allowed under the starting harness's permissions. They are not contained; CAT records the
	effects it can observe and reports the rest as limitations.

Any of these conditions blocks a commit while it is known. When CAT discovers one after a commit, it stops
assignments, reconciles in-flight work, keeps the outcomes and the spent allowance, and ends the attempt with the
matching `certification unavailable` outcome. Restart preserves that disposition.

### 6.6 Trial profiles

Compliance can differ between models and reasoning efforts, and a larger model or more reasoning does not reliably
comply better. A claim therefore names the profiles it was measured under. Each cell is tested only under the profiles
that can actually run its prompt files.

**Inventory.** The harness's profile inventory is every model it offers the user, each with every reasoning effort that
model accepts:

- **Codex:** each model of version 6 or later that `codex debug models` lists with visibility `list`, with its
	supported reasoning levels except `ultra`. The version is the number after `gpt-` in the model's slug: `gpt-6-luna`
	and `gpt-6.1-sol` qualify, while `gpt-5.5` and `gpt-5.6-sol` do not, and a slug without that form is left out
	(D41). `ultra` is `max` reasoning plus automatic task delegation, which changes what the agent does rather
	than how deeply it reasons, so CAT never tests it, and a model's highest effort is the one below it (user ruling,
	2026-10-03).
- **Claude:** the CLI has no model catalog. CAT ships the inventory as a plugin resource: the documented model aliases
	and the efforts each one accepts, with the documentation it came from (premise P12).

The proposal names the inventory's source. Confirmation freezes the profile set, so a later model release does not
change a running campaign.

**Bindings.** A prompt file binds a profile only through a field that its harness applies whenever it runs the file:

- `model` and `effort` in Claude skill and subagent frontmatter (`inherit` binds nothing);
- `model` and `model_reasoning_effort` in a Codex custom agent's TOML.

`CLAUDE.md`, `AGENTS.md`, rules and Codex skills cannot bind a profile. A file that binds only its model or only its
effort fixes that dimension and leaves the other open. A binding to a value outside the inventory is used as written,
and the proposal says so, except that CAT refuses a proposal in which a Codex prompt file binds a model below version 6,
naming the file and the model, and refuses a Codex role profile that binds such a model (D41).

**Profile set.** CAT computes each prompt test's profile set from the requirements that the proposal maps to its
assertions, excluding the expected-outcome assertion, which comes from CAT and not from a file:

- When every file that supplies those requirements binds the same complete profile, the prompt test has one cell under
	that profile.
- Otherwise, the prompt test has one cell for each inventory profile that matches every dimension those files all bind
	to the same value.

A trial session starts under its cell's profile. The harness then applies any binding of the files it runs.

**Development cells (user ruling, 2026-10-04).** Development runs only in each prompt test's development cells: its
weakest cell, plus every cell where a certification attempt of the lineage has adopted a failure. Representative
development trials, prompt author rounds and the commit gate cover those cells only; promotion runs repeat test cases
where they failed. The baseline and certification cover the certification scope.

- **Weakest cell.** A prompt test with one cell develops in that cell. Otherwise its weakest cell is, among its cells in
	the certification scope (the latest versions, when the scope is the weakest cell only), the lowest effort of the least
	capable model, because lower effort failed more often in H27 and H28. Capability is the harness's advertised
	capability, not a measurement: no trial runs to find the weakest cell (user ruling, 2026-10-04). CAT ships each
	harness's capability ranking of model families, taken from the vendor's published model documentation and recording
	its address and the date it was read. OpenAI's model pages rank Astra ("Our most capable model for the most demanding
	work") above Sol ("Near-Astra performance for complex work at a lower cost") above Luna ("Our most efficient model for
	focused, high-volume tasks"); Anthropic's models overview ranks Fable ("For demanding reasoning and long-horizon
	agentic work") above Opus above Sonnet above Haiku. Models rank by family first and, within a family, an older version
	below a newer one. The ranking is part of the proposal, so the user can correct it in revision feedback, and CAT
	derives each scope's weakest cell from it. CAT refuses to confirm while an inventory model's family is missing from
	the ranking, and the proposal names it so that the user can rank it.
- **Cells where certification failed.** A formal failure in a cell that is not a development cell shows that a
	candidate that passed the commit gate in the weakest cell can still fail there. That cell becomes a development cell
	for the rest of the lineage, so later commits need the commit gate there too.

**Certification scope (user rulings, 2026-10-04 and 2026-10-21).** Certification tests the weakest cell only unless
the user, before confirming, chooses other cells. A model family is a model's name without its version (Codex `luna`, `sol`, `astra`; each Claude alias), and its
latest version is the highest version the inventory lists. The inventory already leaves out Codex's `ultra` effort. The
agent presents these three choices, each with the proposal's minimum session count, and these tradeoffs (user ruling,
2026-10-04: no choice certifies every model, older versions included):

- **Weakest cell only.** Formal trials, their control trials and the baseline run only in each prompt test's weakest
	cell, the least capable model's lowest effort among the latest versions. This costs the fewest trials. The claim
	covers only that profile for each prompt test, and the report and the delivery name it and say that no other profile
	was certified. A stronger model or a higher effort can fail in ways the weakest profile does not (H28: high effort
	added the motto for a discontinued product), and the premise that passing the commit gate in the weakest cell carries
	over to the other cells (P15) has been shown for one prompt file only.
- **Latest model versions.** Formal trials, their control trials and the baseline run in every cell of each model
	family's latest version. The claim covers the models a user is offered by default, and a failure that only a stronger
	profile makes is found before delivery. It claims nothing about an older version still in the inventory. Formal,
	control and baseline trials grow with the number of cells, and a cell clearly below or at its cap in any of them ends
	the attempt, spending its allowance, while a formal failure in any of them makes that cell a development cell.
- **Other.** The user describes the scope in their own words. The agent maps it to a set of inventory cells and
	passes them to `revise`; CAT refuses a cell outside the inventory, and the next proposal shows the cells, each
	prompt test's weakest cell in them and the minimum session count, for the user to confirm.

A prompt test whose prompt files bind one profile has one cell; it is that test's development cell and its scope under
every choice. Claude's aliases always name the latest version, so for Claude the latest-versions choice covers every
inventory cell.

**User control.** The proposal shows each prompt test's cells, the capability ranking and the minimum session count of
each certification scope. In revision feedback, the user may correct or extend the ranking or restrict the profile
set to a subset of the inventory. The claim then names only the certified profiles.

**Cost.** The Codex inventory of 2026-10-04 has 4 models of version 6 or later (`gpt-6-luna`, `gpt-6-sol`,
`gpt-6.1-sol`, `gpt-6-astra`) with 5 efforts each, 20 profiles. For a prompt test whose files bind nothing, every choice
needs at least 100 development trials for the commit gate in the weakest cell, `gpt-6-luna` at `low`, and attempt 1 then
needs at least 93 all-pass formal trials and 50 baseline trials in each scope cell, at the default minimum pass rate
`p_min = 0.95`. At that rate the minimum pass rate implies the control and incumbent comparisons, so no control or
incumbent trial runs ([§2.1](#21-claims)):

| Scope | Cells | Formal trials | Baseline trials |
|---|---|---|---|
| Weakest cell only | 1 | 93 | 50 |
| Latest model versions | 15 | 1,395 | 750 |

These counts are per prompt test. A prompt test whose file binds one profile needs 50 baseline, 100 development and 93
formal trials under every choice. Below `p_min = 0.95`, each scope cell also runs a control trial and an incumbent trial
for each pair its comparisons need, and a formal trial for each pair beyond its formal trials. In attempt 1, a guard
that the candidate and both references pass in every trial needs 105 pairs ([§2.4](#24-caps)).

## 7. Campaign lifecycle

- **Campaign:** one explicitly started effort, with its own joint budget and a starting harness that is fixed for its
	lifetime, including across restarts and target renames. A canonical project root and target have at most one
	resumable campaign, which belongs to the host session that started it ([§4.1](#41-storage)). Any other session, in
	either harness, can show that campaign, its starting harness and its owning session, but cannot advance, dispatch,
	allocate allowance or start a concurrent campaign, and its refusal changes no state.
- **Lineage:** the evidence history under one confirmed contract. A confirmed material revision starts a new lineage,
	and so does a test revision, which the confirmed requirements authorize
	([§2.7](#27-development-commit-gate-and-edit-or-continue)).
	Earlier evidence stays historical, the certification index continues, and the incumbent is measured
	again under the new contract as a new baseline ([§2.5](#25-incumbent-baseline)).
- **Revision during formal testing:** a revision starts only at a safe checkpoint. Either the attempt has ended, or the
	user stopped it, in which case it ends `not certified: stopped for contract revision` and can never resume. A test
	revision ends a running attempt `not certified: stopped for test revision`. Every
	in-flight session, assignment, promotion and delivery must also be reconciled. An unknown disposition delays the
	revision.
- **Measurement change during formal testing:** starting a new measurement identity ends the running attempt
	`not certified: testing environment changed` ([§5](#5-measurement-identity-and-applicability)). In-flight work is
	reconciled as for a contract revision.
- **Cancel and resume:** cancelling schedules no further calls and keeps all history and the spent allowance. Resuming
	continues from the retained state. Only an explicit new campaign gets a fresh budget.
- **State distinctions:** the persisted state always distinguishes:
	- confirmed from unconfirmed contracts;
	- development from formal evidence;
	- assigned, running, judged, adopted and post-decision trials;
	- infrastructure failures from pass or fail outcomes;
	- campaign, lineage, candidate, attempt and measurement identities, the certification index and the incumbent
		baseline number;
	- the starting harness;
	- pending user decisions and recorded overrides;
	- unowned-change and measurement-change pauses;
	- current from historical results.

## 8. Skill and command interface

The `prompt-optimizer` skill exists in both harnesses as thin wrappers around one shared body. It applies when the user
wants a prompt file improved until it is certified. The host agent:

1. starts or resumes the campaign for the named target prompt file, passing to `start` the user's goal for the prompt
	in the user's own words when the user stated one, and, when `start` reports no prompt test configuration, asks the
	user which plugins the prompt's users have installed and writes it ([§4.4](#44-trial-staging));
2. presents the proposal, warnings and minimum session count, asks the user to keep or drop each extra requirement,
	asks the user to resolve each conflict with its resolutions and the recommended one, explains the three
	certification scope choices and their tradeoffs ([§6.6](#66-trial-profiles)), saying that the weakest cell only
	applies unless the user chooses another, and relays feedback until the user confirms;
3. calls `run` while the status is `running`, showing the progress summary after each slice;
4. presents each `awaiting-user` decision's concrete choices and passes the selected choice back;
5. reports the terminal outcome with its four outputs, the certified environment
	([§5](#5-measurement-identity-and-applicability)), applicability and limitations.

The harness-specific `prompt-optimizer` launcher provides `start`, `run`, `status`, `confirm`, `revise`, `choose` and
`cancel`. `start` takes the target prompt file and, optionally, the user's goal; it refuses a directory, naming it.
It reads the prompt test configuration from the target's test directory (below) and refuses a target without one, naming
the file to write ([§4.4](#44-trial-staging)).
Each prints one JSON status object to standard output, holding the status, campaign identity, a summary for the user
and any choices. The skill never interprets statistics itself. The launcher also provides the contract tools,
`contract check` and `contract workspace`, for contract author sessions ([§6.1](#61-derivation-and-confirmation)); the
skill does not use them.

Agents locate CAT's installed files without shell variables that the harness does not define. The session hooks
render the plugin root into the injected CAT environment rule, and CAT derives the harness home from `CODEX_HOME` or
`CLAUDE_CONFIG_DIR` when set, otherwise from `HOME`.

**Runner skills (user rulings, 2026-10-22):** each harness's plugin has a runner skill for the other harness, Codex's
`claude-runner` and Claude's `codex-runner`, which runs a task in that harness with CAT for that harness installed,
through that harness's runner launcher. A plugin ships only its own harness's classes and files. The skill uses the
other harness's CAT artifact of the same CAT version and platform: that harness's installed CAT plugin when its version
matches, otherwise a copy in `${HOME}/.cat/bundles/cat-<harness>-v<version>-<platform>/`. When neither exists, it
downloads that harness's release archive and the release's `SHA256SUMS.txt`, verifies the archive's checksum, and
extracts it into that directory before the run; a failed download, checksum or extraction leaves no copy and reports
the failure (D96). A download waits at most 30 seconds to connect, 60 seconds for the server's response and 15 minutes
for the whole file. After a download, the skill deletes the copies of every other CAT version in that directory, and
uninstalling CAT for either harness removes the directory (user rulings, 2026-10-22). A copy's age counts from its last
use: each check for a copy deletes the copies, of any version, harness and platform, that no run has used for more than
three calendar months (user ruling, 2026-10-23).

**Session transcripts (user rulings, 2026-10-23):** a run leaves no harness transcript on disk unless its caller passes
`--session-transcripts`, which requires `--keep-run`. Without it, Claude runs with `--no-session-persistence` and Codex
starts ephemeral threads. With it, the harness writes its native JSONL transcripts, the runner reports each session's
transcript path in a `session_transcript` record once the session starts, so a caller can read it while the run lasts,
and at the end of the run copies every native transcript of the run, subagents' included, into the kept run's
`artifacts/session-transcripts/` and lists them in the receipt. The optimizer passes the option for its trials, whose
records read what a model received from those copies (D97).

**Trial limits (user rulings, 2026-10-23):** the commands that a session runs have the same limits in both harnesses,
and one harness-neutral definition that both runners apply sets them, so a limit cannot hold in one harness and not
the other. A session's commands run with the run's isolated home as their home directory, receive no harness
credential in their environment, and cannot read any harness's login or configuration files, in the run's home or the
user's, or any run under the run root except their own. The user's harness directories stay readable otherwise,
because a session's installed plugins link to files there. On Windows, where Claude Code runs commands without its
sandbox, the read limits bind only Claude's file tools. A harness that a command starts directly therefore cannot
reach the model provider; a session that needs another session requests one instead (D98).

**Requested sessions (user rulings, 2026-10-23):** a caller that passes `--session-requests <count>` lets the session
request up to that many sessions over the run; the default, `0`, allows none, and the optimizer allows none in its
trials. The session requests one by running the `request-session` launcher that the runner names in its environment,
with the harness, a project directory, one or more prompt files and, optionally, plugins, a model, an effort and
`--session-transcripts`. Every input and result passes as a file path, never as content: the launcher, which runs as
one of the session's own commands and therefore inside its sandbox, packs the request and its inputs into one archive
file in the run's `requests` directory, the one directory outside its project and home that the session may write. The runner answers in the run's `responses` directory, which the session may read but not write, so a link
that the session plants cannot redirect the runner's writes: a response file that names copies, beside it, of the
requested run's transcript, receipt and native transcripts, since the requesting session cannot read other runs. The
runner, which runs outside the session's sandbox with the user's permissions, opens no path that the session chose:
it opens only the archive, without following a link and refusing a file with more than one hard link, and unpacks it
into a directory of its own, refusing links, absolute and parent paths and anything beyond its size bounds. An archive
therefore holds only what the session's sandbox let it read, on every platform. The runner validates the request and
starts the session through that
harness's runner launcher, resolved as the runner skills resolve it, with the requesting session's run root and denied
reads, and without requested sessions of its own. A requested session therefore has at most the permissions of the
session that requested it, and no credential ever reaches a command. It takes its own slot of its harness's shared
capacity and honours its hold, but waits at most 10 minutes for a slot, because the requesting session keeps its own
slot while it waits; a request that times out fails with the reason `capacity`. Ending the run or interrupting its turn
stops the requested sessions that still run. The receipt lists each requested session, its run and its evidence. When
`--session-requests` allows any, the runner tells the session in its instructions how to request one (D99).

**Source-only content in prompt files (user rulings, 2026-10-23):** a prompt file's `## Design Goals` section is the
reference that its authors update the guidance from, and its HTML comments remind the plugin developers why a Design
Goal was added; no model reads either. The build removes them from every Markdown file of both harnesses' plugin
artifacts, and from a prompt-only repackaging, before it expands includes, so no path ships them: not an injected rule,
a lazily loaded or path-scoped rule, a skill's body or an agent's shared body. The optimizer removes them from each role
brief that it loads, and the rule loader from each project rule that it injects, since neither passes through the
build. Each keeps `cat:` directives, which later steps replace, and leaves fenced code blocks and code spans as
written, because those show the reader examples of the format. The guidance must therefore reach every outcome its
Design Goals promise on its own: a model that needs a Design Goal or a comment exposes a defect in the guidance, which
is fixed in the guidance. The plugin's rules on writing prompt files say so (D100).

**Retirement of the existing skill:** this skill's body (`skills/prompt-optimizer/first-use.md`) and wrapper text
replace the observe-diagnose-retest procedure. `failure-regression-promotion.md` applies its own correction and
regression procedure instead of invoking `cat:prompt-optimizer` for every qualifying failure, and it may recommend a
campaign when the user wants a certification ([§13](#13-decision-records), D13). No `default-rules.md` route or
test resource refers to the old procedure.

**Regression scenarios:** the correction records the reported case, a meaningful analogous case and a dissimilar case
as regression scenarios. Each is a prompt test directory in the format of [§6.3](#63-prompt-tests-and-test-cases): its
`scenario.md` holds the variables, the turns and the assertions, and its `workspace/` holds the workspace files. A
scenario for guidance under a rules directory, `<root>/.cat/rules/<path>.md` with `<root>` the project or `${HOME}`,
lives in `<root>/.cat/tests/<path>/<case>/scenario.md`. One for guidance packaged as a module resource,
`<module>/src/main/resources/<top>/<path>.md`, such as CAT's plugin rules, skills and optimizer prompts, lives with
the module's tests in `<module>/src/test/resources/<path>/<case>/scenario.md`. One for other guidance in the project
lives in `<project>/.cat/tests/<path relative to the project, without .md>/<case>/scenario.md` (user rulings, 2026-10-02
and 2026-10-10). The directory that holds a target's prompt tests, such as `<module>/src/test/resources/<path>/`, is
the target's **test directory**; it also holds the target's prompt test configuration ([§4.4](#44-trial-staging)).
Rule loading never reads them. Nothing runs
a scenario outside a campaign, so a correction is recorded, not tested, until the user starts a campaign whose target
prompt files include that guidance; its contract then holds every recorded scenario of those files
([§6.1](#61-derivation-and-confirmation)).

## 9. Architecture

The harness-neutral core lives in `client/common/cli` under `com.holdmyspot.cat.common.optimizer`. Harness adapters
and launchers live in `client/claude/cli` and `client/codex/cli`.

| Package | Responsibility | Kind |
|---|---|---|
| `optimizer.statistics` | Betting tests and exact decisions, cap calibration, confidence bounds, Wilson and Clopper–Pearson bounds | pure |
| `optimizer.contract` | Contract model, proposal schema, scenario loader, test cases and the expected-outcome split, value generators, the values file, seeded draw streams, judge profile, built-in tools, the trial record, trial profiles, bindings and profile sets | pure |
| `optimizer.campaign` | Immutable campaign model and its transitions. Commit gate, edit-or-continue, prompt-author-manifest validation, promotion rule, the choice among concurrent attempts, minimality reduction steps, decision table, and the `Scheduler` that makes allocation and concurrency decisions | pure |
| `optimizer.inventory` | Inventory model, edit authority, delivery conflict planning; one scanner reads the file system without following links | pure, plus one I/O adapter |
| `optimizer.store` | Campaign snapshot and trial records, written atomically | I/O |
| `optimizer.workspace` | Fork and optimization worktree, local exclude, staged sources with link dereferencing and exclusions, authoring copies and delta capture, the symbolic-link report | I/O |
| `optimizer.execution` | The slice driver: reconciliation, admission, in-flight tracking, ordered adoption, interrupts, throttling | orchestration over ports |
| `optimizer.delivery` | Journaled delivery | I/O |
| `optimizer.report` | Status object, four outputs, minimum remaining trials | pure |
| `optimizer.cli` | Command parsing and wiring from `main` | edge |

The core owns these ports; adapters implement them at the edges:

- **`SessionPort`** starts a nested session for a role ([§3.1](#31-nested-sessions)) under a trial or role profile, from
	a source directory and its inputs. The returned handle can await completion, delivering the transcript, project
	effects, observed model and token usage, and can interrupt the session. It also reports rate-limit observations. The
	Claude and Codex implementations wrap the existing runners.
- **`WorkspacePort`** stages sources, scans inventories and captures prompt author deltas, so the driver can be tested
	without a file system.
- **`CampaignStore`** is the file-system boundary for campaign persistence. Production has one implementation. Driver
	tests use an in-memory implementation from test sources, and both implementations pass the same contract tests.
- **`CandidateOverlays`** saves each candidate's overlay ([§4.3](#43-candidates-and-edit-authority)) under its snapshot
	and returns it, so the driver can stage trial sources, prepare authoring copies, and materialize and deliver a
	candidate. Its file implementation and its in-memory test implementation pass the same contract tests.
- **`HostInterruptSignal`** reports whether the host turn was interrupted after a given instant (Codex rollout, Claude
	transcript).
- **`HarnessCapacity`** is the permit pool described in [§3.6](#36-shared-harness-capacity).
- **`ProfileInventory`** lists the harness's trial profiles with their source, and reads a prompt file's binding
	([§6.6](#66-trial-profiles)). Each harness adapter implements it.
- **Environment ports:** `Clock`, a pause operation, and a seeded `RandomGenerator` for each draw stream, with the seed
	persisted.

Test builders and fakes live in test sources and are shared with the harness modules through the common module's
test JAR. Production code never depends on them.

`plan.md` declares each cross-component handoff (producer, consumer, readiness point, identity, failure and resumption
behaviour, and proof) once.

## 10. Testability contract

Every implementation change must keep these properties; reviewers reject changes that break them.

- **T1 — Functional core:**
	- Statistics, scheduling, gate and decision policies, state transitions, authority, and conflict planning are pure
		functions over immutable values.
	- They perform no I/O, read no clock, and draw no randomness except through an injected generator.
- **T2 — Direct state construction:**
	- Tests build any campaign state in memory through public builders. Cell state is `(S, F)` plus the control pair
		counts.
	- A test that needs "a certification attempt with 250 passes and 2 failures" constructs it directly. It never runs or
		replays 252 trials and never parses a trial log.
- **T3 — Expensive operations verified once:**
	- Unit tests are the default (user ruling, 2026-10-24): identify the product requirement, then use controlled
		inputs and fakes when they prove it. Assert outcomes, without asserting internal calls, ordering or counts.
		Remove checks that only demonstrate documented JDK or OS behavior. If replacing the dependency would hide a
		defect the requirement must catch, retain the smallest integration check of that boundary, including packaged
		scripts, launchers, installers and harness interaction.
	- Cap calibration is checked once against the [§2.4](#24-caps) fixtures. Other tests receive a precomputed decision
		table through injection.
	- Real Git operations are tested once per operation, in test-owned repositories. The other workspace tests use
		in-memory inventories.
	- Java tests are unit tests: none starts a runner, a harness process or a model. Session adapters are tested
		against recorded harness events and fakes, through contract tests shared with the fakes.
	- A fake has only behaviour its shared contract checks, and the same contract runs against the real adapter. Each
		recording names the harness version that produced it. The end-to-end runs save their harness event streams as new
		recordings, so a harness change that the adapter cannot handle fails the contract tests instead of passing
		silently on old recordings.
	- Only prompt tests use a model. Real nested sessions run only in the end-to-end checks, which run prompt-test
		fixtures through the installed `prompt-optimizer` in each harness and exercise every port operation there.
- **T4 — No shared resources:**
	- No static mutable state. Caches are instance-scoped and injected. Test classes have no mutable fields.
	- Every path is under a directory the test creates and deletes.
	- Libraries never read `HOME`, `user.home` or other process environment. `main` or a harness's production scope
		resolves them and passes them in; test scopes supply their own values.
	- Capacity pools and campaign locks are constructed per test, either unlimited or under the test's own directory.
	- Tests use no fixed network ports.
- **T5 — Time:**
	- Production code reachable from unit tests never calls `Thread.sleep`.
	- Deadlines, backoff, drain and slow start are tested with a manually advanced clock.
	- Driver concurrency is tested with a fake `SessionPort` whose completion order the test controls.
	- Real threads are exercised by one focused test of the blocking wrapper.
- **T6 — Crash consistency by fault injection:** the store accepts an injected failure after any write step, and
	recovery is tested from each step. Real process kills appear only in the end-to-end checks, once per harness.
- **T7 — Runtime:** a unit test normally finishes in under one second. A slower test records why its claim needs the
	extra work.

## 11. Harness evidence

These facts are starting evidence. An implementer revalidates a fact when its determining inputs change, such as a
harness version, sandbox mode or runner change. The 2026-09-30 probes ran a script that starts a plain background
child, a `setsid` child and a `nohup` child, then records heartbeats and signals.

| ID | Fact | Source and limits |
|---|---|---|
| H1 | Claude's PreToolUse input provides the session, prompt, tool-use and transcript-path identities, but no model or effort. | CAT probe; https://code.claude.com/docs/en/hooks#common-input-fields |
| H2 | In one persisted sample, the transcript's assistant record with the matching tool use appeared about 0.085 seconds after PreToolUse. | One print-mode sample; no latency guarantee. |
| H3 | A nested Claude launched with the `haiku` alias reported `gpt-5.6-luna` in its harness assistant events, and `perTurnEffort` was null. | The observed model must come from harness events, not configuration. |
| H4 | The runner copies the project into its run directory, skips `.git`, creates a fresh Git baseline, and rejects any project containing a symbolic link. | `HarnessRunManager.copyProject`. |
| H5 | Nested processes cannot reach the outer Codex daemon socket. | Earlier CAT probe. |
| H6 | In the sandboxed Claude Bash tool, the command ran in its own PID namespace. When the command ended normally, all three children stopped within one second. On ESC, the command and every child stopped at once, with no catchable signal recorded. | 2026-09-30 probe, sandboxed host only (premise P2). |
| H7 | A Claude tool command that exceeded its timeout was moved to the background instead of being killed. | 2026-09-30 probe. |
| H8 | After a Codex turn interrupt, the command and every child kept running until they finished on their own (73 seconds). The thread's rollout file recorded `turn_aborted`, and the command's environment exposed `CODEX_THREAD_ID` and `CODEX_HOME`. | 2026-09-30 probe, nested Codex 0.159.1 (premise P1 covers the host case). |
| H9 | Claude tool commands receive `CLAUDE_CODE_SESSION_ID` and `CLAUDE_CONFIG_DIR`. After an interrupt, the nested transcript contained `[Request interrupted by user for tool use]`. | 2026-09-30 probe (premise P3 covers the host case). |
| H10 | Interrupting a nested Claude through the runner ended the harness process with exit status 1. The bridge then reported "Native harness ended before completing the requested turn" rather than `interrupted`. | 2026-09-30 probe. The Claude adapter must classify an interrupt it requested as an interruption, never as a pass or fail outcome. |
| H11 | In an interactive Codex host, ESC wrote `turn_aborted` to the host thread's rollout 19 ms after the key. A tool command polling that rollout read it 330 ms after the key. The command and its plain, `setsid` and `nohup` children received no signal and ran to completion 172 seconds later. The command's environment had `CODEX_THREAD_ID` and `CODEX_HOME`. | 2026-10-01 probe, interactive Codex 0.159.1+23 in `workspace-write`, ESC sent as a key through `tmux`. Premise P1 passes. |
| H12 | In an interactive Claude host with the sandbox disabled, ESC sent SIGTERM to the command and to all three children, including the `setsid` and `nohup` ones, 84 ms after the key. All of them stopped. | 2026-10-01 probe, interactive Claude Code 2.1.286, sandbox disabled (the command shared the host's PID namespace), ESC sent as a key through `tmux`. Premise P2 passes. |
| H13 | The host Claude transcript recorded a rejected tool result and `[Request interrupted by user]` about 100 ms after ESC. The command had `CLAUDE_CODE_SESSION_ID` but not `CLAUDE_CONFIG_DIR`; the transcript was `$HOME/.claude/projects/<project>/<session>.jsonl`. A watcher must default to `$HOME/.claude` when `CLAUDE_CONFIG_DIR` is unset and match the `[Request interrupted by user` prefix, since the text differs from H9's. | Same probe as H12. Premise P3 passes. |
| H14 | Both runners' harness events carry rate-limit state. Claude emits `rate_limit_event` with `unifiedWindows.five_hour` and `seven_day`, each with `utilization` (0–1) and `resetsAt` (epoch seconds). Codex emits `account/rateLimits/updated` with `primary` and `secondary` windows, each with `usedPercent`, `windowDurationMins` and `resetsAt`. | 2026-10-01 probe through the installed runners (Claude Code 2.1.286 with `haiku`, medium effort; codex-cli 0.159.1+23 with `gpt-6-luna`). Premise P4 passes. |
| H15 | Both runners' harness events carry token usage. Claude's `result` record holds the session's `usage` (input, output, cache-creation and cache-read tokens) and `total_cost_usd`. Codex's `thread/tokenUsage/updated` holds cumulative `total` and per-turn `last` counts (input, cached input, output and reasoning output). Claude's harness events named the model `claude-haiku-4-5-20251001` for the `haiku` alias; Codex's `thread/started` named `model` and `reasoningEffort`. | Same probe. Premise P5 passes; H3's alias mapping did not recur. |
| H16 | Claude's credential file `.credentials.json` holds a `claudeAiOauth` object with `subscriptionType` for a subscription login, and `claude auth status --json` reports `authMethod` and `apiProvider`. `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN` in the environment selects an API key. Codex's `auth.json` holds `auth_mode` (`apikey` with an `OPENAI_API_KEY` string, or a ChatGPT mode with a `tokens` object), and `config.toml` may force a method with `forced_login_method`. Both runners copy the credential file byte for byte into the isolated home. | Same probe. Observed logins: Claude subscription, Codex API key; the other kind in each harness was not available. Premise P6 passes on the documented fields. |
| H17 | Claude trial agents start child agents with `run_in_background`. The harness CLI then emits `task_started` (`is_backgrounded`, `spawn_depth`), and the root turn's `result` arrives before the children finish. The CLI keeps the children running, emits `task_notification` with the child's status when one finishes, and starts a follow-up turn with its own `result`. The runner ends the turn at the first `result`, so the children's work and the follow-up were lost. Codex children appear as separate threads, and parallel children have overlapping activity. | Same probe, plus a direct stream-json run of the Claude CLI. Premise P7 partly passes: completion and parallelism are observable, but the runner must wait for them (§3.2). No grandchild was observed in Claude. |
| H18 | Under the Codex runner's `workspace-write` sandbox, a trial's `.git` is read-only, so `git worktree add` and commits fail. Codex trials therefore cannot use child worktrees. | Same probe. Child worktree merges were not observable in Codex. |
| H19 | A Claude runner interrupt during a turn produced an `interrupted` bridge record and then `turn-completed` with exit code 0 and the response `[Request interrupted by user]`; no harness process remained. An interrupt that arrives after the turn has ended makes the runner fail with "Bridge has no active turn to interrupt". The Codex runner refuses an interrupt with "This harness does not support mid-turn interruption", and the turn ran to completion. Codex's `turn-completed` record carries an empty `assistant_response`; the reply is the last completed `agentMessage` item. | Same probe. H19 supersedes H10's exit status for this runner version. |
| H20 | When the driver was killed with SIGKILL, every Claude runner process was gone within 2 seconds. Every Codex runner process survived the whole 60-second observation window: the runner JVM, `codex app-server` and the sandboxed command. The Codex run manifest records `ownerPid` and `harnessPid`. | Same probe. Premise P8 passes for Claude and fails for Codex. |
| H21 | Hook runs are observable in both harnesses: Claude emits `hook_started` and `hook_response`, and Codex emits `hook/started` and `hook/completed` with the hook's source path. Claude's `init` and `commands_changed` records list the loaded skills and commands. No harness event in either harness names a loaded instruction file (`CLAUDE.md` or `AGENTS.md`), although the model saw each harness's file. | Same probe. Premise P9 fails for instruction files. |
| H22 | A Codex host command that ran for 662 seconds completed with exit status 0. No timeout, yield or background transition was reported. | 2026-10-01 probe, `codex exec` 0.159.1+23. Premise P10 passes for the 8-minute slice. |
| H23 | Over 20 judge sessions per harness on fixed evidence, every verdict was the expected `fail` and quoted the evidence's session ID, and the evidence was unchanged afterwards. Codex returned the bare JSON object 20 of 20 times. Claude (`haiku`) wrapped it in a Markdown code fence 20 of 20 times and never returned it bare. Claude's read-only evidence is advisory (an added directory under `acceptEdits`); the Codex runner has no read-only option, so only file permissions protected the evidence. | Same probe as H14. Premise P11 passes with the extraction rule in §3.1. |
| H24 | A child that finished during the root's last model call emitted `task_notification` and an empty `background_tasks_changed` before the root's `result`; the CLI then started a follow-up (`init`, then `result`) within 0.1 seconds (2 of 3 runs, children of 3 and 6 seconds). Each follow-up `result` carries `origin.kind` `task-notification`; a reply to a user message has no `origin`. Every root notice not yet followed by a root `message_start` led to a follow-up, and none followed a notice that preceded one. No event marks when a request is sent; `message_start` marks the start of its response. Two children produced one follow-up per notice. A grandchild's `task_started` lacks `owned_by_subagent` and its notice reaches the root; a subagent's own command carries `owned_by_subagent: true`, and its notice restarted that subagent (a second `task_started` for the same `task_id`) instead of the root. `subagent_stats` lagged the notices and cannot decide the end of a turn. A user message sent while a child ran was answered at once, with no `origin`, and the child's follow-up came later. | 2026-10-01 probe, Claude 2.1.286 (`haiku`), runner flags with `--include-partial-messages`; six scenarios. Refines H17: the turn-end condition in §3.2. |
| H25 | With the environment variable `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1`, Claude emits `system/session_state_changed` with `state` `running` (before a turn's first `init`) and `idle` (about 0.01 seconds after the root's final `result`). In three runs where a child finished during the root's last model call, no `idle` followed the first `result`; the follow-up started at once and `idle` followed its `result`. No `idle` arrived while a child agent ran. `idle` did arrive while a child's own background command was still listed; when that command finished, the child restarted, the root ran a follow-up, and `idle` arrived again with an empty task list. The variable is undocumented; the CLI reads it in its session-state module. | 2026-10-01 probe, Claude 2.1.286 (`haiku`), six scenarios with the runner's flags. Resolves the H24 window in which a notice arrives after a request was sent but before its response starts. |
| H26 | On `gpt-6.1-sol` through `codex exec` with CAT installed, 20 trials per effort (low, medium, high, xhigh) of each fixture: the deficient fixture closed 0 of 80 replies with the motto and the fixed variant 80 of 80; all 160 answers were correct. Output tokens per 20 trials grew with effort: deficient 1,986, 2,439, 4,074 and 13,886; fixed 2,146, 2,470, 5,337 and 16,372. Input was about 44,000 tokens per trial at every effort. | 2026-10-01 probe, Codex 0.159.1. Premise P14 is inconclusive: both fixtures sit at a floor or a ceiling, where no effort difference can appear. |
| H27 | On `gpt-6.1-sol` through `codex exec` with CAT installed, a fixture with an intermediate pass rate (the deficient fixture with its two rules in reverse order: "no slogans or sign-offs" first, "close every reply with the team motto" second; chosen by a 5-trial pilot at medium among three variants, the only one below 5/5) ran 20 fresh trials per effort. Replies that closed with the motto and answered correctly: low 5/20, medium 8/20, high 16/20, xhigh 19/20; all 80 answers were correct. The 2×4 Fisher–Freeman–Halton exact test fixed before the run rejects equal rates, p ≈ 2.2 × 10^-6. Output tokens per 20 trials: 2,744, 3,611, 9,200 and 26,146. | 2026-10-01 probe, Codex 0.159.1. Premise P14 holds for this model and this kind of rule: the pass rate rose with effort. One fixture and one model; the direction for other models and rules is not established. |
| H28 | A rule without a conflict: "when a question is about a product that Acme currently sells, close with the motto; otherwise add none", among 30 other support guidelines, with product status in a change log that the handbook links to (products discontinued and reintroduced). gpt-6.1-sol passed 40 of 40 pilot trials at low across four such fixtures, a ceiling. On `gpt-6-luna`, 20 fresh trials per effort, pass (correct motto decision and correct answer), fixed before the run: low 4, medium 6, high 13, xhigh 15; the 2×4 exact test rejects equal rates, p ≈ 0.0006. Most failures at low and medium were answers given without reading the handbook. Scored afterwards, as exploration only: the motto decision alone was right 14, 14, 18 and 18 times (exact p ≈ 0.17). Adding the motto to a discontinued product occurred 0, 1, 1 and 1 times. Output tokens per 20 trials: 303, 1,869, 9,710 and 12,418. Fixture limits: "What is Lumen?" also reads as the unit, and the prompt never says to answer from the handbook. | 2026-10-01 probe, Codex 0.159.1. P14 holds on a second model with a rule that has no conflict, through the amount of reading the model does; the rule decision alone showed no significant effect. |
| H29 | Claude Code 2.1.287 in CAT's stream-json mode with `CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS=1` answered a `control_request` with subtype `interrupt` by a `control_response` with subtype `success` within 0.01 seconds. During a foreground tool call it rejected the call, printed `[Request interrupted by user for tool use]`, a `result` with subtype `error_during_execution`, then `idle`. While idle after the root reply with a background command still listed, it stopped the command, printed an empty `background_tasks_changed`, then `idle`. In both cases the process stayed alive and answered the next user message. | 2026-10-01 probe. The request replaces SIGINT for the Claude runner. |
| H30 | Claude Code 2.1.287 exposes no structured exit status for a Bash call anywhere a plugin or host can read it: not in stream-json `tool_use_result`, the `PostToolUse` or `PostToolUseFailure` hook input, the mod `tool.call` result, or the saved session transcript. A result Claude counts as a failure sets `is_error` and gives `Exit code N` only as text. Claude counts exit status 1 from `grep`, `rg`, `diff`, `find`, `test` and `git diff` (judged by the last command of a pipeline; its code applies the same rule to `[` and `git grep`) as success: `is_error` is false, no exit code appears, and only a `returnCodeInterpretation` such as `No matches found` is added. A background command's exit status appears only in the text of its `task_notification` summary. Codex app-server items report `exitCode`. Every status from 2 to 255 that was recorded, including 127 and 143, gave `is_error` with `Error: Exit code N` at the start of the result. A command that Claude denied gave `is_error` with no exit code. | 2026-10-01 recordings for WI-17a and a follow-up probe of hooks, a mod and the transcript. `is_error` alone does not give the status. The Claude adapter reads 0 from a result that is not an error and has no `returnCodeInterpretation`, 1 from one that has it, and N from an error result that starts with `Error: Exit code N`; any other result, a background command and an interruption leave the status absent, so a check that needs it is undecidable rather than guessed. A new Claude version needs a new recording before these forms are trusted. |
| H31 | In the first Codex E1 certification campaign (`gpt-6.1-sol/low`, one category), every prompt author candidate made the trial agent read the handbook for the motto; Codex then posted "I'll check the team handbook for the required closing motto." before the tool call. All six promotion trials ended with the correct final response and passed the motto check, yet the judge failed each one for that note under "answer only the question". The judge read the note from the transcript; the checks and the judge's `responses` evidence held only final responses. | 2026-10-02 E1 run, Codex 0.159.1. Interim messages are visible to the user and belong to the reply (D29); the checks and the judge's evidence must carry them. |
| H32 | Across 231 Codex judge sessions of the E1 to E7 campaigns (45.6M input tokens), reading the trial's log and artifacts took about 32% of judge input tokens and tool output about 61%. One 77 KB trial log held 58 KB of rules injected into the trial, 12.5 KB of streaming fragments repeating a 589-byte reply, and telemetry, with no tool call; the judge already had the reply inline. Codex replaces the middle of a tool result over about 10,000 tokens with `Warning: truncated output`, so the judge's read of a long log was never complete. | 2026-10-02 measurement of the stored judge sessions. A rendered, complete and de-duplicated evidence section replaces the log (§6.4). |
| H33 | Replaying recorded Codex prompt author and judge sessions: prompt authors read a lazily loaded rule twice in 2 of 2 runs, each time after its first read was truncated, sometimes in one script result that joined several files. A rule requiring each whole-file read to be its tool result's only content reduced judge repeats to 0 of 3, but prompt authors still joined four rules in one result in 2 of 3 runs, and one prompt author never recovered the missing text. | 2026-10-02 replay probe, Codex 0.159.1, `gpt-6.1-sol`. The runner delivers role rules in the brief (§3.1). |
| H34 | In P15's read-file commit-gate runs under `gpt-6.1-sol` at low effort, the lazy-load task's answer check, a word-boundary match of a component that the asked release changed, passed 91 of 100 replies with the candidate prompt and 99 of 100 without it. Seven of the candidate's failures named the component with a hyphen, such as "session-store"; matching a hyphen as a space passed 98 and 100. | 2026-10-11 probe, Codex. A mechanical check of a semantic assertion fails replies that satisfy it, so `on_tool_failure: judge` lets the judge decide a failed check (D68). |
| H35 | Codex writes a session's denied reads into the model's instructions under "Denied filesystem reads". A trial denied 402 paths, 399 of them directories under its run root, was shown the first 212, then "Additional permission paths/globs are omitted". In the read-file guard pilot, 11 of 20 such control trials answered that a file in their own project was outside the permitted paths, without a tool call; the file was readable. On `gpt-6-luna` at low effort, 3 of 30 trials refused with 300 named run directories denied and 0 of 30 with only the run root denied. With a session-like parent denied, 20 of 20 trials read their writable project, 3 of 3 could not read a denied file, and 0 of 10 could read a read-only allowance inside the denied parent. The run-root deny did not stop the pilot's refusals: after it, 11 of 20 control trials still answered that their project lay under a denied path. A probe reproduced this only with the control trials' plugin, whose read-file guidance is empty: with the run root denied, 9 of 20 such trials gave that answer without a tool call; with no deny, 0 of 10; with the run root hidden by an empty bubblewrap mount and the session's own run bound back, 0 of 21. With CAT's read-file guidance, which starts the session with a command, 0 of 20 did. Without the deny, control trials still answered 8 of 31 times that the file was unavailable, without a tool call. A developer instruction saying that the project and home stay readable inside the denied run root did not help: 10 of 21 control trials still refused without a tool call. With no deny and the run root made traversable but not listable (mode 0311), 16 of 21 named the component; none of the 5 refusals cited a denied path. On 2026-10-17, with the run root denied, eight developer-note wordings were tried on control trials: seven gave 10 to 12 of 20 named components; the note that the denial protects other sessions' projects and does not apply to the project or home, plus that project files are read with a shell command, gave 64 of 80, the rate of control trials with no deny. Reaching the project and home through links outside the run root, with no note, gave 29 of 41, and trials still cited the denied path. Codex 0.160's Seatbelt and Windows ACL code keep a more specific readable or writable entry readable inside a denied directory. | 2026-10-17 pilot and probes, Codex 0.160.0 on Linux. The refusal's cause is inferred from the replies and the absent tool calls; 3 of 30 against 0 of 30 is not significant alone. macOS and Windows were not probed. |

## 12. Premise gates

Each gate must pass before code that depends on it is accepted. A failed gate selects its fallback, which `plan.md`
records with the evidence.

| ID | Premise to establish | Fallback if it fails | Result |
|---|---|---|---|
| P1 | When the driver runs as a Codex host command, ESC writes `turn_aborted` to the host thread's rollout within seconds, and the driver can read it. | Codex slices shrink to 2 minutes, so ESC stops admissions within one slice. The status documents the latency. | Passed 2026-10-01 (H11). |
| P2 | Without the sandbox, Claude ESC still stops the command and its descendants. | Make P3's transcript watcher mandatory. | Passed 2026-10-01 (H12). |
| P3 | The host Claude transcript receives an interrupt record promptly, and the driver can find it from `CLAUDE_CODE_SESSION_ID` and `CLAUDE_CONFIG_DIR`. | If P2 also fails, use 2-minute slices and document the latency. | Passed 2026-10-01 (H13). |
| P4 | Both harnesses expose rate-limit utilization and reset times through the runner's harness events. | React only to rate-limit rejections, and say so in the volume warning. | Passed (H14). |
| P5 | Both harnesses expose per-session token usage. | Omit token statistics. | Passed (H15). |
| P6 | CAT can tell a subscription login from an API key in each harness's credentials. | Show the authentication warning unconditionally. | Passed on the documented fields; one login kind per harness observed (H16). |
| P7 | In trials, child completion, recursive and parallel children, child worktree merges and cancellation are observable enough to reconcile. | Record observation limits; an affected trial gets no invented label. | Partly passed (H17–H19). Fallback applied to Codex child worktrees in §3.2 and §3.8. |
| P8 | Runner harness processes are descendants of the driver, so killing the driver leaves no live harness session. | Reconciliation terminates orphans recorded through `HarnessRunManager.recordHarnessProcess`. | Passed for Claude, failed for Codex (H20). Fallback applied in §3.8. |
| P9 | CAT can observe which prompt sources a nested session loaded. | Derive loading from documented rules, and record the limit. | Failed for instruction files (H21). Fallback applied in §6.1. |
| P10 | Codex lets a command run for the slice budget, and reports what happens when a command exceeds its limit. | Shorten Codex slices to fit the observed limit. | Passed for the 8-minute slice; no limit was reached in 662 seconds, so the over-limit behaviour is unobserved (H22). |
| P11 | Judge sessions read the evidence read-only and return schema-valid results reliably in both harnesses. | Measure the invalid-output rate. Retries of the same logical trial cover the rate if it is low; otherwise the judge profile cannot be confirmed. | Passed with the §3.1 extraction rule (H23). |
| P12 | Claude Code's documentation names the model aliases a user can select and the reasoning efforts each one accepts. | Ship the aliases with every documented effort level, and record as a limitation that an unsupported combination fails its first trial as an infrastructure failure. | Open until E1 runs a Claude trial per alias. Claude Code 2.1.287: `claude --help` names `opus`, `sonnet` and `fable` and the efforts low to max; `haiku` is named only in the changelog, and the local model-catalog cache (`~/.claude/cache/model-catalog/`, undocumented) gave it no effort options. Claude Code 2.1.305 (2026-10-21): the catalog maps `haiku` to `claude-haiku-5-5` with the efforts low to max. CAT ships these as its inventory resource and does not read the cache at run time. |
| P13 | Each runner can start a session under a requested model and effort, and the harness events report the model and effort that ran. | A runner that cannot select a profile tests only its default profile, and the proposal says so. | Open. Codex `codex debug models` lists each model's supported reasoning levels (0.159.1, 2026-10-01). |
| P14 | Reasoning effort changes compliance with the same prompt on the same model, so effort must stay a dimension of the profile set (§6.6). | If no difference is detected, effort stays in the profile set: a test that fails to reject equal rates does not show that they are equal. Only an equivalence test with a stated margin could support testing fewer efforts, and only for the kind of rule tested. | Holds (H27, H28). H27: a fixture with conflicting rules on `gpt-6.1-sol`, passing 25% of trials at low and 95% at xhigh (p ≈ 2.2 × 10^-6). H28: a rule without a conflict on `gpt-6-luna`, 20% at low and 75% at xhigh (p ≈ 0.0006), mostly through whether the model read the docs. H26, at a floor and a ceiling, was inconclusive. Effort stays in the profile set. |
| P15 | A candidate developed only in one cell, the weakest model at its lowest effort, once it passes the commit gate there (§2.7), also has a high rate under stronger models and higher efforts. Development samples only that cell (§6.6, user ruling, 2026-10-04); the user chooses whether certification covers only that cell or more cells. The weakest model is the least capable by advertised capability. | Certifying every cell (§6.6) finds a cell where it fails, which then becomes a development cell. | Holds for the read-file rule (plan §4): P15 chose gpt-6-luna by the fewest incumbent baseline passes, and Codex advertises it as its model for easier tasks; a candidate that passed the commit gate under gpt-6-luna at low effort then passed 148 of 148 trials in each of the 14 other Codex cells. One prompt file does not show it for every prompt file, which is why weakest-only certification claims nothing about the other cells. |

## 13. Decision records

These decisions go beyond what the product requirements fix. Each was chosen for its long-term cost and benefit.

| ID | Decision | Alternatives and why not | Cost of the chosen option |
|---|---|---|---|
| D1 | Split the joint 5% into 0.04 for certification, 0.005 for improvement claims and 0.005 for the incumbent's bounds. Divide the improvement and incumbent parts by the cell count `K`. | Giving certification only 0.025 raises the first cap from 262 to 311 and the all-pass minimum from 93 to 103. The incumbent's part buys little: halving it from 0.01 to 0.005 adds at most one failure to a deficiency proof. Letting each cell's proven-better decision use the full `α_i` would make the joint error grow with the cell count. An unsplit 5% gives no joint guarantee. | Compared with an unsplit 5%, the first certification attempt's all-pass minimum rises from 88 to 93 and its cap from 255 to 262. Proving improvement over `UI = 0.90` with 3 cells takes 79 all-pass trials, against 45 if each cell used the full `α_1 = 2%`. |
| D2 | Bet on a mixture weighted toward 99% (`0.1/0.2/0.5/0.2` on 97/98/99/100%). | At `α = 2%`: a single 99% bet has cap 255, an all-pass minimum of 95, and probability 0.775 of certifying a 98% candidate within 600 trials. An equal-weight mixture has cap 301, minimum 95 and probability 0.868. | Cap 262, all-pass minimum 93, and probability 0.854. That is close to the single bet's cap with most of the equal mixture's robustness. |
| D3 | Keep the commit gate of 100 trials with, for the minimum pass rate `p_min`, a failure rate of at most `(1 − p_min)/5` and two batches of 50 at most `2(1 − p_min)/5`: 99% and 98% at `p_min = 0.95` (user ruling, 2026-10-10, generalizing the 99% ruling to any minimum pass rate). | A 10-consecutive-pass gate commits a true 97% candidate about 74% of the time, which then certifies only about 17% of the time (§2.7). A 30-trial screen fares little better: P15 committed two candidates after clean screens, and both certifications failed on a failure kind that development had already shown at about 1 in 40 trials. Every failed commit permanently spends allowance and raises later caps. | At least 100 development trials per development cell, which also feed concurrency and diagnosis. |
| D4 | Edit-or-continue with 3 reproduction reruns. | Editing on every failure resets counts on noise: a 99% candidate fails at least once in 100 trials 63% of the time. Never editing mid-batch wastes trials on reproducible defects. | Up to 3 targeted trials per failure. A defect that fails half the time is caught with probability 0.875, and a random 1% failure is treated as a defect only about 3% of the time. |
| D5 | Foreground driver in time-bounded slices. | A detached worker is killed by the Claude sandbox (H6). One long command hits the tool timeout and hides progress. | A snapshot reload per slice, and trials cut off at slice ends, limited by the admission cutoff. |
| D6 | Compact snapshot plus one record file per trial. | Event-log replay costs more as trials accumulate. SQLite adds a native dependency to the runtime image and is not needed beyond atomic renames. | CAT keeps the snapshot consistent with the records through write-ahead ordering and reconciliation. |
| D7 | Keep the cross-process capacity bound, injected and per harness. | Removing it leaves sessions unbounded across processes. A static global causes test contention and couples the harnesses. | Constructor changes in both runner CLIs and in the optimizer wiring. |
| D8 | Concurrency from a risk ceiling (at most ½ expected failure in flight) plus slow start. | A fixed level wastes trials after failures or runs slowly. Slow start alone ignores accumulated evidence. A predictive cost model has no calibrated predictor. | With no failures, reaching 8 in flight takes 47 adopted outcomes. |
| D9 | Adopt outcomes in assignment order. | Completion order biases decisions when duration correlates with the outcome. | A slow trial delays later decisions by up to its duration. |
| D10 | Replace links with copies of their targets in staged sources. | Teaching the runner to copy links breaks relative external links, and absolute links would let trials write live targets. | Trials cannot observe link metadata. This is warned before confirmation and recorded as a limitation. |
| D11 | Report the exact minimum remaining trials, plus observed tokens per session and the size of the candidate's target prompt file and median trial input tokens against the incumbent's; the size counts the target prompt file alone, not derived sources, project rules or the rest of the prompt system (user ruling, 2026-10-21). | Token totals, time estimates and completion probabilities would be forecasts with no calibrated predictor. The target file is the text the prompt author edits and the user reads, and a family total would move with files that the optimization did not choose. | Users estimate cost from the reported facts themselves. The size does not show growth in a derived source or a project rule. |
| D12 | Nested sessions for the contract author, prompt authors and judges. | The host agent cannot work while a slice runs, differs per harness, and cannot be tested with fakes. | Extra sessions, which count toward capacity and the volume warning. |
| D13 | When an agent misapplies user-owned guidance, `failure-regression-promotion` corrects the guidance and records its regression scenarios itself instead of invoking the optimizer, and may suggest a campaign. The scenarios become prompt tests of the next campaign that covers the guidance (user ruling, 2026-10-02). | Invoking the optimizer for every such failure would halt the user's task: a campaign needs a clean worktree, a confirmed contract and hundreds of trials. Asking the user each time interrupts every incident with the same question. The scenario runner of the retired prompt-evaluation workflow is gone, so a campaign is the only executor. | A correction made this way is recorded but neither tested nor certified until the user starts a campaign. |
| D14 | The incumbent's baseline is 50 trials per cell, plus 50 more where it is below the minimum pass rate but unproven. | Always running 100 costs about 50 extra trials per cell. Using only the incumbent's development trials gives no deficiency proof when development replaces it early. | Up to 100 trials per cell, run on spare capacity. |
| D15 | At most one fresh judge session per trial. | Batched judging couples outcomes through judge memory (§2.6). | One extra session per trial that passed its built-in tools; every trial needs one, because the judge decides the expected-outcome assertion. |
| D16 | Rerun infrastructure failures as the same logical trial, up to 3 times, then pause. | Skipping a trial could hide failures that cause infrastructure errors. Unbounded retries hide outages. | An outage pauses the campaign until the user resumes it. |
| D17 | Store each campaign in the CAT session directory of the host session that starts it, under `.cat/sessions/<session-id>/optimizer/`, let only that session run it, and add a local exclude when needed (user ruling, 2026-10-16, replacing project-level storage under `.cat/work/optimizer/`). | Storage under `HOME` loses project-local discovery and complicates worktree handling. One session runs a campaign, so its files live, and are removed, with that session. | One line in the clone's `info/exclude`, which CAT reports. Another session cannot advance the campaign or start one for its target; after a Claude `/clear`, the campaign continues only once the earlier session is resumed; deleting the session deletes an uncertified campaign's files. |
| D18 | Let the user keep the best candidate so far that is proven better than the incumbent in at least one cell, proven worse in none, and checked for regression in every other cell, using one certification attempt's evidence. | Offering only the latest proven candidate would discard an earlier improvement whenever later candidates fail. Testing development results after selecting the candidate overstates it. A separate improvement attempt, committed without the commit gate, could prove a candidate that never passes the commit gate, but it spends allowance outside certification and at least 100 new trials per cell. Giving every promoted candidate an improvement share at promotion would let its development trials count, but proofs grow costlier as promotions accumulate: with 3 cells and an incumbent at 90%, a 97% candidate needs 469 trials for a 90% chance of proof at the 10th promotion and 631 at the 100th. Requiring improvement in every deficient cell would discard real gains in other cells. Accepting a cell with only a few outcomes would call a candidate improved without checking it for regression. Certifying on improvement alone would weaken what `certified` means. | No extra trials, and no allowance spent outside certification. A candidate that never passes the commit gate cannot be proven better; the user can still accept it as `not certified`. A cell that is not proven better may hide a small regression within its interval, and the report says so. |
| D19 | Spend each budget part by the series `x / (n·(n + 1))`, so early attempts receive most of it. | A flatter series, `0.4 / ((i + 9)·(i + 10))`, lowers attempt 100's cap from 712 to 619, but raises attempt 1's cap from 262 to 382 and its all-pass minimum from 93 to 130. An equal split over a fixed maximum of 100 attempts gives every attempt a cap of 478 and an all-pass minimum of 177, and leaves nothing for attempt 101. The commit gate admits only candidates with strong development results, so a campaign is expected to commit few certification attempts; this expectation is unmeasured. | Late attempts cost more: attempt 100 has an all-pass minimum of 271 and a cap of 712. |
| D20 | A new measurement identity ends a running certification attempt, and qualifying attempts are ranked only within their own identity. | Counting the old and new outcomes together supports only the claim that some condition exceeds 95%, which nobody needs. Counting only the new outcomes under the same `α_i` tests the same claim twice and can double its error. Pausing until the condition is restored blocks a user who cannot restore it, so it remains one choice rather than the rule. Dropping earlier-identity attempts would discard valid claims whose allowance is already spent. Re-certifying an earlier candidate under the new identity needs a way to make it the working candidate again, plus a commit gate and a full attempt; the user can still optimize further. | The stopped attempt's allowance is lost. The user may have to choose between candidates whose results cannot be compared. |
| D21 | Record the runner and CAT versions for information, and treat a change to the session environment version that CAT declares as material (user ruling, 2026-10-04: a certification is repeated only when the model's behavior or the desired behavior has changed). | Treating every runner or CAT version as material repeats certifications after releases that change nothing the model receives. A runner or CAT release affects the model only through what a session receives and how its actions take effect, which CAT's developers know when they release and which a test of the generated session settings guards. | Certifications stay current across CAT upgrades that leave the session environment unchanged. A release that fixes how CAT observes or judges a trial, such as H17's early turn end, does not by itself make earlier results historical; its release notes say which results it affected. An undeclared environment change that the settings test does not cover would leave a certification current when it should not be. |
| D22 | Give every prompt test one cell per trial profile that can run its prompt files: the profile a file binds, otherwise every profile in the harness's inventory (user ruling, 2026-10-01). | Certifying one default profile says nothing about the others, and compliance does not grow reliably with model size or reasoning. Certifying a mixture of profiles costs about one profile's trials, but a weak profile can hide behind the average. Borrowing strength across profiles with item-response models estimates averages and is not anytime-valid. | Trial counts multiply by the number of profiles for every prompt test whose files bind none. The proposal shows this cost, and the user can restrict the profile set. |
| D23 | Revised by D63: decide mechanical requirements with checks, and use the judge only for the semantic remainder (user ruling, 2026-10-01). | A judge that decides everything adds its own errors to mechanical requirements. Checks written as code by the contract author could not be validated or contained. | A closed set of check kinds to implement and maintain. A requirement that no kind fits stays semantic. |
| D24 | Superseded by D62: add the core-task requirement to every contract (user ruling, 2026-10-01). | Contracts derived only from the prompt's text can certify a candidate that obeys every rule but does the user's task worse. | The judge also decides task correctness. That can expose failures that the prompt's own rules would not. |
| D25 | Require prompt authors to classify failures, make the smallest owning-boundary correction, generalize against analogous and dissimilar cases, and cite the failing test cases for each hunk. CAT checks the citations (user ruling, 2026-10-01). | Unconstrained prompt authors add emphasis and special cases that pass the triggering test cases and grow the prompt. Judging the quality of each edit with a model would add an unvalidated decision. | Prompt authors write a manifest. CAT can check only that the citations exist, not that they are apt. |
| D26 | Reduce each promoted attempt by delta debugging before it becomes the working candidate (user ruling, 2026-10-01). | Keeping every added hunk lets prompts grow with unneeded text. Removing hunks after representative sampling would discard counts. | Extra targeted runs per promotion, growing at most with the square of the hunk count. A needed hunk can be removed by chance; representative trials then catch the weaker candidate. |
| D27 | Develop each prompt test only in its weakest cell, plus any cell where certification found a failure (user ruling, 2026-10-04). | Developing in each model's lowest and highest effort, then widening to every cell, multiplied development trials by the number of active cells. P15's candidate, which passed the commit gate only under gpt-6-luna at low effort, passed every other Codex cell. Ranking models by advertised capability finds the weakest cell without the baseline trials that measuring each model's lowest effort would cost. | A failure that only a stronger profile makes is found only by certifying that profile, at the cost of that attempt's allowance; weakest-only certification makes no claim about it. An advertised ranking can be wrong for a given prompt; the user can correct it in the proposal, and a certification failure in another cell adds that cell to development. |
| D28 | Make 3 concurrent prompt author attempts while the working candidate is weak, and promote the best (user ruling, 2026-10-01). | One attempt at a time is cheaper per round, but early rounds then spend representative trials discovering that a weak edit is weak, and a failed promotion costs a whole round. Three attempts in every round roughly break even: late rounds rarely gain from a second attempt. | Two extra prompt author sessions and their promotion runs per weak round; all are targeted and consume no allowance. The benefit is estimated, not measured; the recorded per-attempt promotion results measure it. |
| D29 | A turn's reply is every assistant message of the turn, including interim messages, not only its final response (user ruling, 2026-10-02). | The user sees the interim messages. Judging only the final response would certify a prompt whose agent says prohibited things before it answers, and the checks and the judge would disagree about what a reply is (E1, H31). | Narration that a harness adds by itself, such as Codex's note before a tool call, counts against requirements on reply content. Whether a prompt can suppress it is not yet known, but a prompt can shape its wording, so a failure there is a prompt defect (user ruling, 2026-10-02). |
| D30 | Prompt authors correct an instruction in the file that states it, `AGENTS.md` and `CLAUDE.md` included. A target that ships with CAT is corrected at its source location. Other new guidance goes into a project rule under `.cat/rules/` when CAT is a trial plugin, scoped with `paths` frontmatter when it applies only to some files; without CAT, it goes into a prompt file other than `AGENTS.md` and `CLAUDE.md`, and into those only when it cannot otherwise reach the sessions that need it (user rulings, 2026-10-02 and 2026-10-21). | Both files load into every session, so guidance added there costs context in every task and applies to uses it was not written for. A file that loads only where it applies keeps the always-loaded files small. But guidance added elsewhere leaves a faulty always-loaded instruction in force: in E1 under `gpt-6-luna`/low, every attempt of a round added handbook text while the `AGENTS.md` sentence limit that the motto closing overran stayed unchanged, and the campaign reached a plateau. A project rule loads through CAT's rule loader without a reference from another file, and `paths` frontmatter keeps it out of sessions that do not touch its files; in E5 under `gpt-6-luna`/low, with no trial plugin, candidate 7 had grown `AGENTS.md` from 452 bytes to about 7,300, which every model call of every trial resent. | A correction in a separate file may need a reference from `AGENTS.md` or `CLAUDE.md` to load. Reading a referenced file can cost a tool call, and with it an interim message whose wording the prompt must keep compliant (D29). A project rule reaches only users who install CAT, which is why the preference applies only when CAT is a trial plugin: the certification then holds only for users with the certified plugins, as the certified environment says. |
| D31 | Refuse to confirm a proposal that lists a conflict between requirements (user ruling, 2026-10-02). | No reply can satisfy a contract with a conflict, so its campaign spends its trials until its review checkpoint. Which requirement prevails is the user's decision, not the contract author's. | The contract author reports each conflict with resolutions and a recommended one instead of settling it; the agent asks the user to choose; `confirm` fails and names the conflicts until feedback resolves them. |
| D32 | A conflict found during a campaign is the prompt author's to resolve if a campaign edit introduced it, and pauses the campaign for the user's resolution, with options and a recommendation, if the confirmed prompt system already held it (user ruling, 2026-10-02). | The user decides which of their own requirements prevails; a conflict that the campaign created is a candidate defect. Checking the quoted text against the confirmed snapshot tells the two apart without trusting the prompt author's account. | A *conflict* classification in the prompt author's manifest, a pause whose choices are the resolutions, and a revision built from the chosen one. |
| D33 | Promote an attempt that fixes at least one triggering test case without a regression, rather than only one that fixes them all (user ruling, 2026-10-02). | Requiring every triggering run to pass rejected improvements whose remaining failures were left for later: in E1 under `gpt-6-luna`/low, the best attempt passed 12 of 14 promotion runs, failed one test case twice, and the campaign reached a plateau after one round. Promotion only sets the next working candidate; the commit gate still decides certification. | Minimality must keep every fix, so a reduction must fix every test case that its attempt fixed. A lucky streak on an intermittent test case could promote an attempt that is no better; D35 limits that. |
| D34 | Give the attempts of one round different approaches: correct the stated instruction, rewrite existing text, then add missing guidance (user ruling, 2026-10-02). | Identical briefs produced three variations of one edit in one file, so a round tested one idea three times. | An approach can be a poor fit for a failure; another attempt of the round covers it. Later rounds continue the list (D59). |
| D35 | Revised by D75 and D89: count a test case as fixed only when the attempt's streak of passes there would be unlikely (below 10%) for the working candidate, extending to up to 4 runs, and revert a promoted candidate whose development upper bound in an addressed cell falls below its predecessor's observed pass rate (user ruling, 2026-10-02). | Two passes prove little on a test case the working candidate already passes half the time: they occur by chance 25% of the time. A fixed run count high enough for such test cases wastes runs on test cases that fail reliably. The working candidate's representative failure and diagnostic reruns already estimate how often it passes each test case. | Up to 2 extra targeted runs per intermittent test case. A test case that the working candidate passes 3 of 4 times can never count as fixed. A revert discards the withdrawn candidate's representative trials and ends the round without a promotion. A revert goes back only one promotion, so the stored state holds one predecessor rather than a chain. |
| D36 | Give the judge a rendered evidence section in place of the trial's log and transcript path (user ruling, 2026-10-02). | The log was mostly injected rules, streaming duplicates and telemetry, and its reads cost about a third of judge input tokens while Codex truncated them (H32). | CAT owns one renderer per harness's events. A frozen judge prompt from an earlier contract keeps its placeholders and still receives what they name. |
| D37 | Run the contract author, judge and prompt author without CAT's plugin and deliver each role's rules in its brief (user ruling, 2026-10-02). | The runner already knows each session's role, so no hook has to detect it; the session-start rules and path triggers served trials, and the prompt author's lazy reads were truncated and repeated (H33). | The user's and the project's rules do not reach these roles. A rule a role needs must be added to its list. |
| D38 | Treat a measurement condition that neither the working candidate nor the incumbent records as unobserved, not missing (status finding, 2026-10-02). | No runner observed host tools or external sources then, and campaigns stored before the judge, model and reasoning were recorded lack them on both sides, so every comparison was `INDETERMINATE` although both sides ran under the same driver. | Two unrecorded values are not proven equal. Since D95 the host tools and external sources are no longer conditions at all, so a campaign stored with them reads without them; the status and the certified environment state the host machine as a limitation. |
| D39 | Classify each failed development trial as blocking or self-recovering in a triage session, and fail fast on a blocking failure: discard the working candidate's unadopted development trials and edit without diagnostic reruns (user ruling, 2026-10-03). | Diagnostic reruns and in-flight trials delayed every round, although a failure that leaves the user without the result needs an edit whether or not it reproduces. A failure the agent corrected within the turn is cheaper and is still worth sampling around. The frozen judge decides only the pass label and does not run after a failed check, so it cannot classify every failure. | One triage session per failed development trial. Development outcomes never support a claim, so the triage changes no claim. A wrongly blocking label edits early; a wrongly self-recovering one costs up to 3 reruns. |
| D40 | Give every requirement that applies only under a trigger a test where the trigger is absent, and make each authored hunk's dissimilar case one of the contract's tests (user ruling, 2026-10-03). | A prompt-tuning run accepted "start every turn with an `exec` call" to stop agents asking before reading, because all of its tasks needed files; the over-broad wording made a needless call in 9 of 10 runs of a question that needs none. A dissimilar case that is only named is never measured. | One more test per conditional requirement, and the contract author must state each requirement's trigger. A prompt author cannot cite a dissimilar case the contract lacks; the user adds it through `revise`. |
| D41 | In Codex, the optimizer supports only models of version 6 or later: the inventory leaves out earlier models, and CAT refuses a proposal whose prompt files bind one (user ruling, 2026-10-04). | The user limited support to these models. Earlier models also differ in their tools: `gpt-5.5` gets a direct `exec_command` tool, while `gpt-6` models run commands inside an `exec` script, so a prompt that names one form need not hold on the other. | No contract certifies a `gpt-5.x` profile. A user whose Codex prompt files bind an earlier model changes that binding before CAT accepts the proposal. |
| D42 | Certify the weakest cell only unless the user chooses, at confirmation, every cell of each model family's latest version or a scope the user describes (user rulings, 2026-10-04; the default, user ruling 2026-10-21). | Always certifying every cell multiplies formal and baseline trials by the number of cells. Always certifying the weakest cell only would claim nothing about the profiles the user may actually run. Certifying older model versions was dropped as a choice; a user who needs one describes it as their own scope. The weakest cell is the default because it costs the fewest trials, and a user who runs other profiles can still choose them. | The proposal must compute and explain every choice, and confirmation without a choice certifies the weakest cell only. A described scope is mapped to inventory cells and shown for confirmation. A weakest-only claim names one profile per prompt test. |
| D43 | Revised by D61: require every prompt test to vary, split its test cases into a development part and a held-out part by a salted hash of their outcome-changing values at confirmation, and certify only on held-out test cases (user rulings, 2026-10-04 and 2026-10-09). | P15 tuned and certified on the same four fixed tasks, so its certification measured the rule on its own training data. Its first split hashed all values together and put the same general-knowledge question in both parts, so the split uses only the variables that change the expected outcome, which each prompt test marks; a default that treated every variable as outcome-changing when none was marked put one expected outcome in both parts. Excluding test cases after development has seen them would make the formal distribution depend on which test cases development happened to draw, and on outcomes when only failures are excluded. A fixed split depends on neither. | Each prompt test needs at least one outcome-changing variable. The claim covers the held-out half of the distribution, baseline trials can no longer reuse the incumbent's development trials, and prompt authors never see the evidence of a formal failure. |
| D44 | Revised by D61: after an attempt ends with formal failures, have a contract author session add development-only outcome-changing values that exercise each failed test's failed requirements (user ruling, 2026-10-04). | Showing development the failing held-out test case would tune the candidate on the content that certifies it. Moving shown test cases out of the held-out part would bias later certifications toward content the candidate passes. Rerunning existing development test cases of the failed test finds nothing when no development value triggers the failure, and then an unchanged candidate that still meets the commit gate is committed again. A description of the failure written from the held-out test case could leak its content. | A contract author session and new development trials after each failed attempt. The development distribution no longer matches the held-out distribution, so development counts restart and the commit gate measures the harder distribution. |
| D45 | Within an attempt, certify a prompt test's development cells first and open each other scope cell only after every development cell and every cell below it are certified (user ruling, 2026-10-04). | Running every scope cell at once spent about 500 formal runs of P15 in stronger cells, twice, on a candidate that then failed in the weakest cell. Certifying cells one at a time in a total order would waste nothing but serialize up to 15 cells; models and efforts are only partially ordered, so cells that cannot be compared run together. | A certification takes longer in wall time: with 15 cells it runs in 7 waves. A failure in a stronger cell is found later. |
| D46 | Keep held-out content in a held-out directory and a held-out run root, and have each harness sandbox deny every session whose output reaches development reads of both. | Withholding held-out content from a session's brief does not stop the session from reading it: the prompt author's copy and the contract author's directory sit inside the campaign directory, and both harnesses' sandboxes allow reads anywhere. Denying files by name fails in Codex once a file is replaced, and leaves running formal trials' run directories readable. | Each harness's session settings carry a deny-read list; the campaign storage layout changes; a host without a working sandbox cannot run the optimizer. |
| D47 | Reproduce each formal failure group in development, by behavior, before tuning against it; replace test cases that do not reproduce, up to 4 rounds, using stress settings when needed (user ruling, 2026-10-04). | P15 tuned four times after a formal failure that development had not reproduced; each edit traded the failure for another rare one, and the development values added for it found a different behavior of the same requirement. Sending every formal failure straight to development tunes without a target. | Up to 400 reproduction trials per failure group, a behavior list per requirement in the contract, and a pause when a group does not reproduce. |
| D48 | A change to a command that a prompt tells the model to run keeps the certification current while the prompt stays unchanged (user ruling, 2026-10-04). | A certification measures the model's behavior under the prompt. Fixing a defect in a command the prompt names, such as a bundled script that misquotes a path, needs no change in what the model does, so repeating the certification would spend trials to measure behavior that did not change. A command change that does need different behavior also changes the prompt, and a prompt change is material. | A command fix can change trial outcomes, for example by turning a failure caused by the defect into a pass, without a new certification. The prompt's author is responsible for changing the prompt when a command change needs different model behavior. |
| D49 | Record the certification parameters with each certification, and keep it current after they change only while its claim implies the new one (user ruling, 2026-10-04). | A certification of a pass probability above 95% at error level `α` also proves every weaker claim: a lower minimum pass rate, or a higher error level. It proves nothing about a stronger claim, such as a minimum pass rate of 96%. | A release that raises the minimum pass rate or lowers the error budget makes every certification under the old values historical; one that relaxes them leaves certifications current. |
| D50 | Identify the judge by a version number that CAT increments whenever a property that can change a verdict changes, and make a certification historical when the judge version changes (user ruling, 2026-10-04). | The judge's role profile, prompt, rubric, decision rule, result schema and evidence renderer each can change verdicts, and the code recorded only the judge's model. One declared number covers them all and needs no per-property comparison. Re-judging retained trials could keep a certification current across a judge change, but it needs retained evidence and a new attempt per change. | A judge change makes every certification under the old judge historical, even when the model's compliance is unchanged. A judge property changed without a version increment fails the build. |
| D51 | Remove a certified campaign's files from `.cat/sessions/` once its candidate is delivered or the user keeps the incumbent, and keep a certification record of a few kilobytes in the project (user rulings, 2026-10-04); records accumulate in `certifications.json` beside `prompt.json` (user ruling, 2026-10-16). Delete a trial's two project copies, its effects and its initial project, once its passing verdict is recorded, and keep a failed trial's (user ruling, 2026-10-17). | A campaign's run directories take about 1.7 MB per trial when the project is small, and a finished certification needs none of them. Each project copy holds the whole staged project: in the 2026-10-17 read-file campaign the two copies took about 230 MB per trial, and nothing reads a passing trial's copies. Without a record, deleting the snapshot would leave nothing from which to report whether the delivered prompt's certification is current. A record under `.cat/sessions/` would be local to one clone, and committing trial results would put megabytes into the repository. A separate history file keeps CAT from rewriting `prompt.json`, which the user writes and CAT only reads, and keeps the records of both harnesses and of earlier certifications. | The history is a committed project file that grows by one record with each certified delivery. After cleanup, a certified campaign cannot be resumed or diagnosed, and its evidence cannot be re-examined. A campaign that ends any other way keeps its files until the user removes them. A passing trial's file changes cannot be re-examined. |
| D52 | Treat every external file as a read-only member of the prompt family: CAT reads, stages and measures it but never edits it (user ruling, 2026-10-05). | Letting the user authorize edits to an external target meant a delivery could change files outside the project that other projects or users share, with a separate preflight and change record for them. | A requirement that needs an external edit must be met through an in-project prompt file, or the campaign stops or is revised. |
| D53 | Keep sampling the working candidate after a round that promoted no attempt, and edit again on its next failure; development has no plateau outcome (user rulings, 2026-10-05 and 2026-10-06). | Pausing at every round without a promotion ended development whenever attempts could not be proven better, although the working candidate might already meet the commit gate or be close to it. A plateau limited to a candidate with more than 2 failures in a category still ended E1 after 15 rounds and E7 after 5, on failures that later rounds could target with new evidence. | A candidate below the gate keeps spending development trials and prompt author rounds until a category reaches its 200-trial review checkpoint or the user stops. |
| D54 | Treat the in-project target of a prompt-source link as a prompt file (user ruling, 2026-10-05). | A prompt source that is a link has its content in the target, so forbidding the target left the prompt source uneditable while the prompt author was told it could edit it; every attempt that edited it was rejected. | CAT may edit an in-project file that the loader reaches only through a link, which other in-project readers of that file also see. |
| D55 | Treat a trial turn that reaches its time limit with no model output, and no hook still running, as an infrastructure attempt (user ruling, 2026-10-06). | A provider overload left a Codex trial with no event for 60 seconds: the load balancer logged `server_is_overloaded`, the harness reported no error, and the time limit judged the provider's failure as "no reply". That failure counted toward a plateau, although a turn the provider never answered says nothing about the prompt. | A harness-reported provider error is not required, because Codex reported none. A model that thinks past the limit without emitting any item or token usage is indistinguishable from a provider that never answered, so it is also an infrastructure attempt; if it repeats, the third infrastructure failure of that logical trial pauses the campaign instead of hiding it. |
| D56 | Give each prompt author the evidence section of every failing trial it receives, with its tool calls, exit statuses and outputs (user ruling, 2026-10-06). | Prompt authors received replies and check results but not commands. In E1 under `gpt-6-luna`/low, prompt authors wrote that the records "do not establish whether handbook access was attempted", so they could not tell a mistyped `read-file` path (exit 127) from a skipped read, and rounds kept targeting the wrong cause. | The prompt author's input grows by one evidence section per failing trial. |
| D57 | Treat a provider refusal like a rate-limit rejection, through one hold per harness that every CAT process and nested runner honours (user rulings, 2026-10-06). | In E8 at concurrency 16, bursts of HTTP 403s from OpenAI's edge failed up to 451 of 474 requests a minute. A 403 was an ordinary infrastructure failure, so each failed session freed its slot at once and its trial reran within a minute, while Codex retried each failing turn about 10 times in 12 seconds, so requests rose to about 500 a minute. 403s tracked new connections: a minute after one with 10 or more new connections had a 403 52% of the time, against 6% below 10. A per-process backoff leaves other campaigns and runners connecting. Codex's own retries stay at its defaults: it accepts no retry limits for its built-in `openai` provider, and a provider under another id changes the requests Codex sends, such as its `version` header. | Every CAT process of a harness waits while one of them is refused. A refusal that persists past a 30-minute hold pauses the campaign instead of retrying forever. Sessions already running when a hold starts still make Codex's own retries, at most about 11 requests each. |
| D58 | Have each prompt author attempt correct every kind of failure it receives, other than a test or contract defect, an infrastructure fault or a conflict; when a kind fails again after an earlier attempt targeted it, explain why its instruction must be followed instead of restating it (user request, 2026-10-07). | In P15's development of the Codex read rule under `gpt-6-luna`/low with a 5,000-token output limit, 8 of 13 failures in 1,200 lazy-load runs began with a mistyped or invented command path, and they went on to give up, skip the rules or list a whole directory; no edit had targeted them because they looked like model limitations. An edit that restated the instruction behind a recurring failure, finishing every part of a file, did not change its rate over 500 runs. | The prompt author's manifest groups the failures into kinds and names the hunks for each, and CAT rejects a manifest that leaves a correctable kind without a hunk. The prompt author also receives the lineage's earlier manifests. An attempt grows with the number of kinds; the minimality pass still removes hunks that the promotion runs do not need, so a kind whose hunk is removed returns through a later round. |
| D59 | Add six approaches learned from tuning a prompt by hand, and let each round continue a prompt test's approach list where its previous round stopped (user ruling, 2026-10-07). | Hand tuning of a Codex file-reading prompt found edits that changed results noticeably and that the first three approaches did not ask for. Removing examples that replies copied moved correct search answers from 10 points below the rate without the prompt to 11 points above it. Stating the consequence of `max_output_tokens` beside the instruction cut its use from 4 in 100 runs to none. Prescribing the quoted command form, which replies could not misapply, instead of the piped form cut failures from 21 in 250 runs to 5. Putting the text to copy last on its line cut miscopies from 12–15 in 550 runs to 4. Defining a placeholder before its use cut wrong paths from 4 in 250 runs to 1. Stating the finished command instead of a line range to build cut wrong re-reads from 4 in 59 runs to none in 52, and writing the plugin directory in place of its placeholder cut failures from 4 in 250 runs to 1. Restarting the list each round would never reach approaches after the third. | A round still makes 3 or 2 attempts, so a prompt test reaches all nine approaches only after three weak or five sequential rounds. The evidence comes from one prompt and one harness, and the order of the new approaches is a guess; the recorded per-attempt promotion results measure both. |
| D60 | Make a prompt test a directory holding `scenario.md` with variables, turns with `{{name}}` placeholders and assertions, plus `fixtures/` copied into each trial's workspace with placeholders replaced, and write its test cases to a values file at delivery (user rulings, 2026-10-10). | Prose context let the contract author describe a workspace that trials staged differently, and fixed text gave one test case per scenario. Model-written fixture programs would put unvalidated code between the contract and the trial. Keeping test cases only in campaign state would lose them, so a later campaign could not reuse them. | Every recorded scenario must be converted, and a later campaign's held-out part holds only new test cases whose expected outcomes differ from committed ones. |
| D61 | Split test cases into the development and held-out parts by a salted hash of their normalized expected outcome, keep committed test cases in development, and refuse a new development test case whose outcome equals a held-out one (user ruling, 2026-10-10). | Hashing the variables that change the outcome, which each test marked, depended on the contract author marking them; a missed mark put one answer in both parts. Hashing all values put the same answer in both parts in different workspaces. | A prompt test needs at least 2 distinct expected outcomes in each part, and test cases that differ only in filler share a part. |
| D62 | Add to every prompt test an assertion that the reply's result matches the test case's expected outcome, a guard when every other assertion is a guard and an effect otherwise (user ruling, 2026-10-10). | The core-task requirement asked the judge whether a reply was correct and complete, which judges decide less consistently than a named answer, and every judge error counts against the minimum pass rate. | Every trial needs a judge session, and every test case needs an expected outcome. |
| D63 | Decide a mechanical assertion with a tool built into CAT, named in its `tool` key and verified by CAT's unit tests, and use the judge for every other assertion (user ruling, 2026-10-10). | Checks written by the contract author needed known-good and known-bad controls, which the contract author also wrote, so they validated nothing the author did not already believe. | A closed set of built-in tools to implement and maintain; an assertion that no built-in tool fits stays with the judge. |
| D64 | Generate filler with value generators built into CAT, recorded in the values file as a name, version, seed and parameters (user ruling, 2026-10-10). | Storing every generated line would make values files megabytes long. A model writing filler costs tokens on every test case and can place answers in it. A program that the contract author writes would run unreviewed code. | Each generator keeps every version, so old values files rebuild the same files; filler only looks real line by line. |
| D65 | Revised by D74: certify each cell at a minimum pass rate `p_min` that the user confirms per campaign, between 0.80 and 0.99, with a default of 0.95, and require each assertion's pass probability to be no more than 0.05 below its rate without the target prompt (user ruling, 2026-10-10; the range and the control mixture are internal design). | A fixed 95% cannot express a prompt test whose agent fails often for reasons the prompt cannot fix, and certifying only against the minimum lets a prompt that degrades a guard below its unprompted rate pass. A minimum below 0.80 tells the user little; above 0.99 needs 474 all-pass trials per cell. | Control trials pair with formal trials in every certified cell, the commit gate and caps depend on `p_min`, and an attempt ends `not certified: below control` when the control comparison proves a regression. |
| D66 | Before confirmation, run about 20 control trials per prompt test in its weakest cell on development test cases, and show each assertion's pass rate in the proposal, warning on a guard below the minimum pass rate `p_min` (user ruling, 2026-10-10). | Waiting for the formal phase to learn that a guard fails without the prompt spends an attempt's allowance on a contract error. Refusing a proposal on pilot rates would decide a question that only the user can: whether the agent really fails without the prompt. | About 20 trials per prompt test and revision before confirmation, which spend no allowance. |
| D67 | Partly superseded by D82, which replaces fixture `patterns` with links in `workspace/`: write the data sections of `scenario.md`, `## Variables`, `## Fixtures` and each assertion, as `- **key**: value` items; let only `## Scenario` and its turns hold prose, which is their `description`; and name each prompt test's fixture files with glob `patterns` that are copied in list order, the last copy of a path winning, so prompt tests share files from a pooled `fixtures/` directory (user rulings, 2026-10-10). | Bare `kind:` and `tool:` lines look like prose that starts with a word and a colon, so a reader cannot tell structure from statement without knowing the parser's rule, and a table needs `\|` escapes and one-line cells. Prompt tests of one guidance kept identical copies of their files, and implicit inheritance from ancestor directories gave every prompt test each file its ancestors hold, even the ones it must not see. Copying in list order gives what a person copying the list by hand would get. | Every recorded scenario is converted again, a shared pattern for a name with braces must escape them, and a pattern placed early can be silently replaced by a later one. |
| D68 | Let an assertion leave a failure of its built-in tool to the judge with `on_tool_failure: judge`; a passing tool still decides it, and `fail` stays the default (user ruling, 2026-10-11). | A word match accepts the common wording of a semantic assertion but fails replies that satisfy it in another wording, such as a hyphenated name (H34); a judge alone would put every common reply at the risk of a judge error. | The judge prompt changes, so the judge version increments and certifications under the earlier judge become historical. A tool given `judge` must pass no reply that its description rejects, which CAT cannot check. |
| D69 | Install the plugin under test in each trial from a copy that holds the snapshot's prompt files, and install CAT in its trials only when the plugin depends on CAT (user ruling, 2026-10-11). | Trials installed one fixed CAT artifact, so a candidate's edit to a prompt file that the plugin ships, and a control's emptied copy, never reached the trial agent, and a campaign could not measure its own target. Installing CAT beside another plugin would measure a combination that the plugin's users without CAT never run. A mapping from source to a built base, rather than a rebuild per candidate, keeps a candidate's trials free of the project's build. | A build step that transforms a prompt file is outside the mapping, so such a file cannot be a target. A hook that the user has not approved does not run in trials. The contract author must judge the dependency on CAT, which the user confirms. |
| D70 | Derive requirements from the user's goal before the prompt system, and let a test revision rewrite a failing prompt test without confirmation while it keeps the confirmed requirements and the test design rules (user rulings, 2026-10-13). | Deriving requirements from the prompt system alone, as the first read-file certification start did without the target or the goal, turned every obligation of a 608 KB prompt system into a requirement and produced tests of how the bundled scripts work rather than whether a file reaches the agent whole. Classifying a faulty test as *not guidance* stopped progress until the user revised the contract, and confirming every test revision would ask the user to decide how to test, which the user delegated. | A prompt obligation outside the goal reaches the contract only when the user keeps it. Each test revision costs a new lineage, a new incumbent baseline and new test cases, and ends a running attempt without refunding its allowance. |
| D71 | Optimize exactly one target prompt file, refusing a directory, and let control trials empty only that file (user rulings, 2026-10-13). | One prompt file is the focus of an optimization; the rest of the prompt system is the context it runs in. Emptying every in-project prompt file measured an agent with no project rules at all, so the guard pilot and the control comparison credited or blamed the target for the effect of the other rules. | A prompt system with several files needs one campaign per file it optimizes. The prompt author may still edit every in-project file that the prompt system loads. Control trials load those files as the incumbent has them, so the control comparison attributes a candidate's edits to them to the candidate along with its edits to the target. |
| D72 | Let control trials also remove every dependent of the target, which the contract author lists and the user confirms with the proposal, and nothing else (user ruling, 2026-10-13). | Emptying the target alone left in place the hooks and routes that exist only to serve it, such as the Codex PostToolUse hook that repairs `read-part` output, so control trials measured an agent with part of the target's mechanism still active. Removing more than the dependents would again credit or blame the target for the effect of other rules (D71). | Whether a part exists only to serve the target is a judgment, so the contract author makes it and the user confirms it; a wrong list biases the control comparison. A file that still has another purpose keeps any mention of the target. |
| D73 | Revised by D74: compare each assertion of a certified cell with the incumbent as well as with control, on incumbent trials paired with the candidate formal trials, with the same 5-point margin, decisions and cap (user ruling, 2026-10-13). The reference is the incumbent recorded at the fork, not the best candidate so far, which only guides development. No assertion needs to be proven better than the incumbent's: a candidate must be better overall only in a cell whose incumbent is below the minimum pass rate, which claim (b) already requires, and where the incumbent already meets that rate, no worse suffices (user rulings, 2026-10-13). | The minimum pass rate, the control comparison and the whole-trial comparisons with the incumbent can all pass while a candidate passes one assertion less often than the incumbent did, trading a guard for an effect. The baseline trials are unpaired and measure whole trials, so they cannot decide a per-assertion comparison. | Each certified cell runs up to one incumbent trial per pair, as many as its control trials, so formal cost rises by up to the number of pairs. Requiring every part of claim (a) adds no error budget, but a guard that both pass almost always needs many pairs in both comparisons. |
| D74 | Run the control and incumbent comparisons only when the minimum pass rate `p_min` is below 0.95; at 0.95 or above, certify a cell on its minimum-pass-rate part alone and say in the certification record that the comparisons follow from it (user ruling, 2026-10-14). | A trial passes only when every assertion passes, so `pC(k, a) ≥ pC(k) > p_min ≥ 0.95 ≥ pR(k, a) − 0.05` for either reference `R`: a true minimum-pass-rate claim makes both comparisons true. Running them anyway added no assurance, yet a comparison on a guard that the candidate and its reference both pass 99% of the time was undecided at the cap of attempt 1 6% of the time, and 17% of the time against a reference that always passes, and even a perfect candidate needs 105 pairs against the 93 formal trials of the minimum-pass-rate part. | At `p_min ≥ 0.95` no control or incumbent trial runs in certification, so a certified claim rests on the minimum pass rate alone. Below 0.95 the comparisons and their cost are unchanged. |
| D75 | Revert a promoted candidate only when its development 95% Wilson upper bound in an addressed cell is below its predecessor's development 95% Wilson lower bound there, so the two intervals do not overlap (user ruling, 2026-10-14). | The predecessor's observed rate carries its own sampling error, so comparing with it withdrew a candidate as good as its predecessor 25–36% of the time. With non-overlapping intervals and a predecessor of 50 trials, that falls to 2–6%. Refusing at commit a candidate clearly worse than the best earlier committed one was considered and dropped: a candidate that meets the commit gate has an upper bound of at least 99.57%, above any earlier candidate's lower bound of at most 98.1% at 200 trials, so the rule could never fire. | A clearly worse candidate takes longer to withdraw: one at 70% against a predecessor at 90% is withdrawn within 50 trials 58% of the time rather than 99%. Each revert record holds the predecessor's lower bound instead of its observed rate. |
| D76 | Call the gate that a development cell must meet before a commit the commit gate rather than the readiness gate (user ruling, 2026-10-14). | Passing the gate permits a commit, which starts a certification attempt; "readiness" did not say ready for what. "Certification gate" would suggest that passing it certifies the candidate, which only the formal trials do. | Reports, the remaining-work kind and the status JSON use the new name; stored campaigns are unaffected because the gate's state is derived from trial counts. |
| D77 | Have the contract author write prompt tests as files in a draft directory that CAT seeds with the recorded ones, keep in the proposal only what belongs to the contract, and move work that needs no judgment into CAT: the plugin under test, the hook registrations and the recorded prompt tests before the session, and the `contract check` and `contract fixtures` commands during it (user rulings, 2026-10-15). | Restating every prompt test in the proposal JSON made the author re-type each cited scenario's turns, variables and assertions, about 5,000 characters for the four scenarios of `read-file.md`, and let the user review a form that delivery later converted. Leaving the plugin under test to the author made it read the distribution build to find where each file lands, which a comparison of file contents settles. Validating only after the session made the author write its own checks. Ending an inspection turn before writing anything made a session that reached its time limit leave nothing. | The scenario format gains a section, CAT needs a plugin-matching step and two commands, and a draft directory must move into the held-out directory when the session ends. |
| D78 | Read the plugins that trials install from the prompt test configuration, a committed `prompt.json` in the target's test directory that lists them for each harness, and install each plugin that the project builds from a build that `start` runs on the fork commit instead of from the user's installed copy (user rulings, 2026-10-15). This replaces deriving the plugin under test and its dependency on CAT from file contents (D69, D77). | Deriving the plugins guessed: a plugin that ships none of the target's files was never installed, and a dependency on CAT rested on a search for CAT's names. An installed copy can predate its sources, as CAT's did on 2026-10-15, when it lacked a fix that the build output held, and neither harness records which sources an installed plugin came from. Running the project's own build command works for any language and build tool, and a list in the configuration lets one campaign install several plugins. | A target needs a configuration before its first campaign. Every campaign start runs the project's build. A prompt file that the build transforms still cannot be a target. |
| D79 | Give each host session a CAT session directory, `.cat/sessions/<session-id>/`, that one component creates with a marker naming the harness and its configuration directory, and remove a session's directory at a later session start once the marker is more than 24 hours old and the harness no longer has the session's transcript (user request and rulings, 2026-10-16). | A shared temporary directory ties no file to the session that wrote it, so nothing can remove a session's files once the session is gone. Neither harness tells plugins when it deletes a session, and a transcript is what the harness keeps to resume one. The marker locates the transcript, because the configuration directory differs between sessions; the 24 hours cover a Claude session whose transcript appears only with its first turn. | A session without a transcript, such as an ephemeral Codex run, loses its directory to another session's start after 24 hours. A directory without a marker is never removed. When a harness reports deleted sessions, that report replaces the sweep for its sessions. |
| D80 | On Linux, deny a Codex session its whole run root as one path, which leaves its writable project and home readable, unless read-only evidence lies under that root (user ruling, 2026-10-17). | One path per other run made Codex shorten the list and trial agents refuse their own files (H35), and missed runs that start later. Denying the CAT session directory would also hide the development trials' transcripts from judges, which run under the held-out run root, because a read-only allowance cannot escape a denied parent (H35). | macOS, Windows and sessions with evidence under the run root keep one path per denied file and directory until a probe shows the root deny works there. Codex session environment version 5 makes earlier Codex results historical. |
| D81 | Name the per-session directory the CAT session directory and keep it under `.cat/sessions/<session-id>/`, and at each session start move every directory with a marker from the former `.cat/work/<session-id>/` there (user request and ruling, 2026-10-17). | The directory belongs to one session, and `.cat/work/` named neither that nor its contents. Moving existing directories keeps a resumed session's campaigns and lets the sweep remove the directories of deleted sessions, which it no longer finds under `.cat/work/`. The move is an exception to the rule that only plugin installation migrates plugin data, because installation does not know the user's projects. | A directory whose new path already exists stays under `.cat/work/` until the user removes it, as does the former shared `temp/`, which CAT never moves or deletes. |
| D82 | Give each trial a workspace that holds the prompt system and its prompt test's `workspace/` directory, the root of the trial's workspace, in which each symbolic link that leads outside `workspace/` reaches the trial as a copy of its target and each other link stays a link, instead of a copy of the whole fork with `fixtures/` files that glob `patterns` select (user rulings, 2026-10-17). | Each trial of the read-file campaign copied the whole project twice, about 230 MB per trial, for tests whose agent read only its fixtures; the project's other files were also material that an agent could search or read by accident and that the test never chose. Patterns needed a rule for where each matched file lands, and that rule differed for fixtures and for project files; a directory that mirrors the trial's workspace shows the layout as the trial sees it, and a link places a shared or project file at any path. The base source keeps every file that the harness, the loader configuration or a trial plugin reads from the project, so the prompt system that loads is unchanged. | A trial that needs a file its prompt test does not link fails, and the guard pilot shows it; every stored guard pilot reruns once; every recorded `fixtures/` directory and `## Fixtures` section is converted; a checkout that cannot create symbolic links, such as Git for Windows without `core.symlinks`, cannot run a prompt test that links. |
| D83 | Retire the contract's ignored files, the proposal's `ignored_files` and its "Ignored files" section, without compatibility: a trial receives a file outside the prompt system only through a symbolic link in its prompt test's `workspace/` (user ruling, 2026-10-18). | Since D82 the base source holds no ignored file, and a link in `workspace/` reads its target from the draft or the live project, so a listed ignored file reached no trial; keeping the list would have the contract author name files that nothing uses and refuse a confirmation for a missing one. | A campaign stored with `ignored_files` no longer loads, and a contract author that still writes the key has its proposal refused. |
| D84 | Hold admissions only on a rate-limit rejection or a provider refusal; high reported utilization, even 100%, only stops `w` from growing (user ruling, 2026-10-18). | A read-file campaign's trials ran through a load balancer with two Codex accounts. One account reported 95–97% of its 5-hour window used, so the 95% rule held every admission for over four hours while the other account still had allowance. A harness reports only the windows of the account that served the session, so utilization cannot show that no account can serve the next one; a rejection can. |
| D85 | Derive the contract from the files that load in trials, never from those that load only in the user's own sessions; a prompt system that depends on a plugin lists it in its prompt test configuration (user ruling, 2026-10-19). | The fifth E1 and sixth E7 Codex runs listed no trial plugin, yet the contract author's request listed the CAT rules and user rules that the user's harness loads, so the proposals carried 11 and 17 requirements from files that no trial receives. Tests of those requirements would measure prompts that the campaign cannot edit or load, and a prompt system's users who do not install CAT never receive them. |
| D86 | List every file that trials receive before any trial runs, derive the files that hooks and MCP servers run, and refuse what a trial cannot receive: a missing or external import, a hook that fails only in a trial's workspace, and a candidate that loads a file its trials do not receive (user rulings, 2026-10-19). | In the first Claude E1 run, the target `CLAUDE.md` held only `@AGENTS.md`, CAT did not derive `AGENTS.md`, and 84 trial sessions received `CLAUDE.md` without it, so the incumbent measured an empty prompt and nothing showed it. Copying the imported files into each prompt test's `workspace/` instead would keep a candidate's edits out of trials, and the tests would need an update whenever the prompt system changed. Hooks and MCP servers also inject context, from files that no instruction file names. | A command's files are derived only when the command names them by path; what a hook reads is checked only by running it with one input, so a hook that reads other files under another input can still fail in trials. Running the hooks at `start` runs project code, as the project's build already does. |
| D87 | Deny every nested session reads of the user's project, except its own run directory and the evidence it reads, and give a Claude session only the host's deny and ask rules, not its grants (end-to-end finding, 2026-10-20). | A trial that reads the project measures the incumbent prompt system instead of its candidate, and a judge that reads it can judge against the incumbent. Copying the host's grants gave trials the host's additional directories, which held every sibling worktree. | A grant that a trial needs and the host's settings supplied must now come from CAT: it allows the `Skill` tool in every nested Claude session, and a trial that needs another host grant, such as web search, is denied it. |
| D88 | Let every nested session write only its run directory, its home and the user's npm and Maven caches, never the other paths that the host may write (user ruling, 2026-10-20). | The host may write the user's Claude and CAT directories, which a trial must neither change nor use to reach the user's state. Trials still share the dependency caches, so that each one does not download the same packages again. | A tool that a trial runs and that writes elsewhere in the user's home, such as a Gradle cache, fails until CAT grants its cache. The user's `MAVEN_OPTS` reaches every nested session. |
| D89 | Extend a triggering test case's passing streak until it is unlikely for the working candidate, with no upper limit, rather than stopping at 4 runs (user ruling, 2026-10-20). | With 4 runs, a fix can be shown only where the working candidate passes less than about 56% of the time, since `p^4 < 0.10` needs `p < 0.56`. In the Codex E7 campaign under `gpt-6-luna`/low, the working candidate passed its failing `concise_explanations` test cases 33 of 35 to 40 of 41 times, so attempts 10 to 19 each passed every promotion run and were all rejected (end-to-end finding, 2026-10-20). | A fix on a test case that the working candidate passes most of the time costs many targeted runs, about `2.3 / (1 − p)`, run one at a time: 32 at 39 of 41. A test case that the working candidate passes 3 of 4 times can now count as fixed. |
| D90 | Treat `CLAUDE.md` as Claude Code's prompt file and `AGENTS.md` as Codex's, so that a Claude control trial loads only `CLAUDE.md` files (user ruling, 2026-10-20). | Claude Code 2.1.304 loads `AGENTS.md` in place of a `CLAUDE.md` that is missing or empty. A control trial of a `CLAUDE.md` that only imports `AGENTS.md` therefore still followed the target: 59 of 60 Claude guard-pilot control trials closed with the motto that the emptied file required (end-to-end finding, 2026-10-20). | A Claude control trial loads different instruction files from the user's sessions when those load `AGENTS.md` beside or in place of `CLAUDE.md`. |
| D91 | End-to-end checks prove only what a real harness must show: each harness's runners work and prompt tests run through them, a trial uses a host tool, and a real ESC is recorded. Behavior that depends only on harness events is proven by unit tests that replay events recorded once from a real run, and behavior that depends on Git by tests on a real repository (user ruling, 2026-10-20). | A live campaign costs hundreds of nested sessions per claim, while a recording replays in milliseconds and still fails when a new harness version changes its events. | A recording proves the behavior of the harness version that produced it; a harness change that alters the events needs a new recording. |
| D92 | Show a certification's environment as one block, `Certified for the following environment:` followed by the harness and its version, the trial plugins with their versions, and every other recorded condition in words that need none of CAT's terms, and have the skill write a missing prompt test configuration from the user's choice among the installed plugins (user rulings, 2026-10-21); CAT's own version has two parts, such as `1.0`, and `status` of a finished campaign that cleanup has not removed shows the block too (user rulings, 2026-10-22). | A certification holds only for the environment it measured, and the plugins that trials install are the user's choice; a claim that does not name them reads as covering users with other plugins, CAT included. Asking from the list of installed plugins keeps the user from writing `prompt.json` by hand. | Plugin versions join the measurement identity, so updating a trial plugin makes a certification historical, as a change of its base copy already did. |
| D93 | Identify CAT among the trial plugins by its manifest's `repository` URL, compared after ignoring the letter case of the scheme and host, a trailing `/` and a trailing `.git`, never by the plugin's name or marketplace, and record each trial plugin's repository in the campaign, a plugin recorded without one counting as not CAT (user ruling, 2026-10-21); only `https://github.com/holdmyspot-com/cat` is CAT's repository, and the former `https://github.com/cowwoc/cat` is not (user ruling, 2026-10-22). | A name is chosen by whoever publishes the plugin: another plugin may be named `cat`, and CAT may be installed under another name or from another marketplace, so a name match both admits a stranger and misses CAT. Exact URL comparison would miss CAT over spellings that name the same repository. Deriving the repository of an older campaign's plugin from its base copy would make loading a campaign read campaign storage. | A CAT release whose manifest states another repository URL, such as the former `https://github.com/cowwoc/cat` that CAT 1.0.x and 1.1 manifests state, is not CAT in trials, so its rules and `.cat/rules/` load in no trial. A campaign started before CAT recorded repositories treats every trial plugin as not CAT until it is started again. |
| D94 | Name no model provider in a certification's environment or measurement identity (user ruling, 2026-10-22). | The provider name that Codex reports is only the name configured in its `config.toml`; behind a proxy such as codex-lb it does not say who serves the model, and Claude Code reports no provider at all. A fact that one harness can state wrongly and the other cannot state is not a condition to certify against. | The `Model` line reads `Model: <model>, reasoning <level>`. Stored campaigns and certification histories that carry the retired condition load without it. |
| D95 | Neither record nor control the host machine's programs and outside sources, such as MCP servers, websites and web search: they are not measurement conditions, never material and never compared. Every certification's environment block and every `status` report state this as a limitation and recommend running the campaign, and the sessions that later use the prompt, in the same container image to limit changes to them. A stored campaign or certification history that records the retired host tools or external sources conditions loads with them dropped, and a pending measurement change that names only them is dropped (user ruling, 2026-10-22, superseding the earlier ruling of the same day to record them). | CAT cannot snapshot the host environment; short of a container, nothing it records would let a user reproduce it. Which tools a trial runs varies from trial to trial, so recording them gives noise and making them material gives false alarms that pause campaigns. The candidate, control and incumbent trials paired within one attempt run on the same machine at the same time, so they already share the environment that the comparison depends on. A container is the user's means to limit changes to it. | The tools that trials invoke usually do not affect prompt optimization, but sometimes do; a certification stays current when they change, and only the limitation and the container recommendation warn the user. |
| D96 | Give each harness's plugin a runner skill for the other harness, and have it download the other harness's CAT release archive, verified against the release's checksums, when neither that harness's installed CAT plugin of the same version nor an earlier download exists (user rulings, 2026-10-22). | A plugin that bundled the other harness's classes and plugin files would grow for every user to serve a skill few run, and one that required the other harness's plugin installed would fail for a user who never installed it. Downloading the published, checksummed archive of the same version reuses the installer's trust model and costs nothing until the skill first runs. |
| D97 | Write harness transcripts only when the caller passes `--session-transcripts` to a kept run, copy them into the run's artifacts and list them in the receipt (user rulings, 2026-10-23: "1a, 2a"). | Transcripts on disk by default would keep every trial's conversation, which callers rarely read; a caller that tests what a harness records, such as a plugin's effect on the transcript, had no way to get it. | Optimizer trials pass the option, so a campaign keeps the transcripts that its records read. |
| D98 | Give a session's commands one set of limits in both harnesses from one harness-neutral definition: the run's home as their home, no harness credential in their environment, and no reads of any harness's login or configuration or of other runs (user ruling, 2026-10-23). | A Codex session's commands kept the user's home, and a Claude session's commands received the provider token, so a harness started from a command reached the provider in one harness and not the other, and an agent could not predict which. | A command that needs the user's harness configuration, such as a harness CLI started directly, no longer works in a session; it requests a session instead (D99). |
| D99 | Let a caller allow a session to request sessions, which the runner starts outside the session's sandbox through the requested harness's runner with no more than the requesting session's permissions, passing every input and result as a file path (user ruling, 2026-10-23: option C). | Starting a harness inside a session would put credentials in reach of every command and escape the shared capacity, interrupts and evidence; making the caller start every session prevents a session from checking its own work, such as a plugin it just wrote. A broker keeps credentials, capacity and evidence with the runner. | A requested session waits at most 10 minutes for a capacity slot and fails with `capacity` after that; requested sessions request none of their own and are unavailable in optimizer trials. |
| D100 | Remove each prompt file's design goals and comments before any model reads it (plugin build, role briefs, project rules), and make the guidance alone carry every goal (user rulings, 2026-10-23). | Design goals guide prompt authors and comments remind plugin developers why a goal was added. Agents read lazily loaded rules, skill bodies and agent bodies straight from the installed plugin, so removal at injection alone left that text in front of models; a model that needed it showed a gap in the guidance. |
| D101 | Pack a requested session's inputs into one archive inside the requesting session's sandbox, and let the runner open only that archive (user ruling, 2026-10-23: option 4). | The runner copies the inputs with the user's permissions while the requesting session keeps running. Walking a directory that the session controls lets it swap a link or junction into a directory on the walk's path at the moment the runner passes through, so the runner would copy a file the session may not read, such as a harness login; Java refuses a link in such a position only through `SecureDirectoryStream`, which Windows lacks, and checking the copy afterwards cannot tell where a regular file came from. Reading the inputs inside the sandbox makes the sandbox refuse such a read, and a single file in `requests` has no session-controlled directory on its path. | A request may name any file the session can read, not only files in its project; a session whose harness has no command sandbox, such as Claude Code on Windows, can pack whatever its user can read, as its commands already can. |
| D102 | Offer `keep_identity` for every measurement change except loader changes, retaining the identity and accumulated results while recording accepted values (user ruling, 2026-10-24). | The user wants to continue the existing campaign after a plugin-cache change without discarding its measurements. | Reports identify the accepted changes and retained earlier measurements; equivalence is the user's judgment. Loader changes still require restoration or a new campaign. |
| D103 | Codex trials inherit the parent's existing `project_doc_*` instruction-loading settings from `config.toml` (user ruling, 2026-10-24). | Filename fallbacks and instruction-size limits affect which prompt text Codex reads. | Preserve configured values and account for them in the loader configuration identity; implementation is tracked with the instruction-loading follow-up. |
| D104 | Supply `executableFiles()` through `JvmScope`; production scopes retain retry pauses and test scopes do not sleep (user ruling, 2026-10-24). | Consumers need one scope-owned service that tests can control, rather than an inline production instance or a separate constructor argument. | The optimizer, requested runner and both harness runners obtain the service from their scope. |
| D105 | Retain refusal of symlinks anywhere in a requested-session input path (user ruling, 2026-10-24). | The user chose to preserve the existing restriction. | macOS callers must supply resolved paths when an ancestor such as `/var` is a symlink. |

## 14. Acceptance evidence

The feature is complete only when every gate below passes on the final tree, together with the unrestricted project
build and the complete requirement inventory in `plan.md`. An end-to-end check proves what only a real harness can
show: that each harness's runners start their sessions and that prompt tests run through them. Behavior that depends
only on a harness's events is proven by unit tests that replay events recorded once from a real run (T3), and a
behavior that depends on a third-party tool, such as Git, by a unit test that runs the real tool.

| Goal | Required evidence |
|---|---|
| G1 | **End-to-end in both harnesses:** a deliberately deficient prompt system is improved in the optimization worktree; unselected attempts never touch the base; a certified, non-regressing candidate is delivered as a Git diff that includes its certification record, after which nothing of the campaign remains under `.cat/sessions/`. **Real Git:** a base with staged, unstaged or untracked changes is refused before any fork. **Removal:** keeping the incumbent instead of a regressing certified candidate also removes the campaign's files, an uncertified campaign keeps its files, and an interrupted removal completes on the next command (driver and delivery tests, because a regressing certified candidate costs hundreds of trials end to end). **Delivery:** disjoint post-fork base edits survive; same-file changes stop for direction; a fault between writes resumes without duplicating or overwriting. **Uncertified outcomes:** they leave the base unchanged unless the user explicitly chooses. An improvement proven by an inconclusive attempt is offered and, when kept, delivered as `improved, not certified`; when several attempts qualify, the best candidate so far is offered, even after later candidates fail. Driver and delivery tests prove these, because an uncertified certification attempt costs hundreds of trials end to end. An accepted uncertified candidate is labelled `not certified`. **Authoring:** an attempt whose manifest omits a changed hunk or cites an unknown test case is rejected; the minimality pass removes a hunk that the promotion runs do not need and keeps one they do; a weak round's 3 attempts promote only the best accepted one; an attempt that fixes some triggering test cases but not all is promoted; a triggering test case gets extra runs until its streak is unlikely for the working candidate; a promoted candidate that falls clearly below its predecessor is reverted; each attempt of a round receives its own approach (driver tests with a fake `SessionPort`). |
| G2 | **Proposal content:** every file that trials receive from the base source, every hook whose output differs in a trial's workspace, every workspace file and its source, including linked and external files, the symbolic-link report, and the volume warning. **Confirmation:** nothing but the guard pilot runs before confirmation; feedback produces a complete new proposal. **Guard pilot:** it runs about 20 control trials per prompt test in the weakest cell on development test cases only, the proposal shows each assertion's rate and warns on a guard below the minimum pass rate `p_min`, a revision reruns it only for changed prompt tests, and its trials enter no count and spend no allowance. **Loading:** conditional and later-loaded prompt files are covered, with the loader configuration fixed from the fork through delivery. **Refusals:** forbidden edits are rejected (any edit to an external file, link and mode changes, loader configuration), and a proposal that lists a conflict cannot be confirmed; a target that trials would not load is refused, a participating prompt file that does not load from the project and has no mapping is listed, and a mapped file that differs from the base is a warning. A prompt file may map into several plugins, and each receives it. `start` refuses a missing or external import and a hook that fails only in a trial's workspace, and a candidate that loads or runs a project file its trials do not receive contributes no candidate. **Unavailable claims:** remote governing instructions, image/audio/video outputs and response-contingent obligations make the claim unavailable instead of being omitted. **Contract completeness:** every prompt test gets the expected-outcome assertion, a guard exactly when every other assertion is one; a scenario that declares that id, a missing section, prose in a section of key-value pairs, a `## Scenario` or turn of key-value pairs without a `description`, an unknown key or section, a workspace link that is absolute, is missing, leaves the project, forms a cycle, holds itself or reaches a prompt test directory, a link that copies a prompt file, a link that Git records where the checkout holds another kind of file, a malformed variable, an undeclared or generated placeholder outside workspace-file contents, an unknown built-in tool, an `on_tool_failure` other than `fail` or `judge` or without a `tool`, or a check whose evidence the harness cannot supply is refused. **Test cases:** workspace files are copied with placeholders replaced in names and UTF-8 contents and other files copied unchanged; an invalid file-name value or a name collision is refused; a generator call gets a seed and version once, two identical calls get different seeds, and each version expands to the same bytes on every run; the values file round-trips and rejects unknown fields. **Profiles:** a file binding a complete profile yields one cell; an unbound file yields one cell per inventory profile; a partial binding fixes only its dimension; a user restriction narrows the set. **Scope and templates:** confirmation defaults to the weakest cell unless the user chooses another certification scope, the proposal shows the cells, weakest cells and minimum session count of each choice, and a described scope outside the inventory is refused; a prompt test with fewer than 2 distinct expected outcomes in either part is refused; the minimum pass rate outside 0.80–0.99 or with more than two decimals is refused. |
| G3 | **Statistical fixtures:** the joint budget sequence; exact decisions at the boundary, including equality not certifying; the [§2.4](#24-caps) caps and all-pass minimums; deficiency thresholds; improvement following from certification plus deficiency; and the proven-better and proven-worse decisions at `β_i / K` against frozen incumbent bounds; caps, all-pass minimums, bets, the commit gate and the baseline at minimum pass rates other than 0.95; the control and incumbent e-values on fixed pair paths, including `not certified: below control`, `not certified: below incumbent` and certification held back until both comparisons are decided. **Budget persistence:** restarts, revisions and resumed cancellations never reset the budget, while a new campaign does. **Report wording:** the claim names the frozen judge, the benchmark, the held-out part and the conditions, and a weakest-only claim names its one profile per prompt test and says that no other profile was certified. |
| G4 | **Assignment and order:** formal draws only after freeze; adoption in assignment order regardless of completion order; post-decision outcomes never change a decision. **Retries:** they cannot discard outcomes. **Evidence separation:** targeted runs and development outcomes never enter formal counts. **Staging:** it excludes optimizer state. **Dependence:** detected dependence yields `invalid statistical assumptions`. **Held-out test cases:** the split by normalized expected outcome is fixed at the first validated proposal, and outcomes that differ only in case or whitespace share a part; committed test cases go to development and a new test case sharing a committed outcome does too; development trials, targeted runs and prompt author sessions receive only development test cases; formal and baseline trials draw only held-out ones; a formal failure reaches development only as its cell, test, failed assertions and behaviors, and the development test cases added after it never share a held-out expected outcome and join development only after two reproduction failures with the failure's behavior in its cell or a stressed profile, the contract author replacing test cases that do not reproduce, with a pause after 4 rounds; in both harnesses, no session whose output reaches development can read the held-out directory or the held-out run root. |
| G5 | **Outputs:** four outputs in both phases with unavailable values explained. **Applicability:** each unaccepted material change makes a result historical; a changed command that an unchanged prompt tells the model to run leaves it current; a runner or CAT version change with an unchanged session environment version leaves it current; a raised threshold or a lowered error level makes it historical, and a lowered threshold or a raised error level does not; an unaccepted judge version makes it historical; accepting a non-loader change retains evidence, attempts and allowances across restart and discloses the acceptance in reports; after cleanup, `status` reports from the certification record, naming each changed condition and each unchecked one; a comparability field that only one side records makes the comparison `INDETERMINATE`; a new measurement identity ends a running attempt as `not certified: testing environment changed`, and a qualifying attempt under an earlier identity is offered separately, never ranked against current ones. **Remaining work:** minimum remaining trials match hand-computed values; no token total or time estimate is shown. **Size:** the status shows the size of the candidate's target prompt file, and no other file of the prompt system, and median trial input tokens against the incumbent's; a change to a derived source or a project rule alone leaves the size unchanged. |
| G6 | **End-to-end in both harnesses, recorded once:** a real ESC during a slice is recorded with its harness events, and Codex ESC stops admissions within 15 seconds. **Replay:** unit tests replay each harness's recorded interrupted turn and show that `run` then retains completed outcomes and assignments, reruns in-flight logical trials, and keeps the certification index. **Unit fault injection:** recovery from every persistence step. **Campaign binding:** the other harness cannot advance the campaign. |
| G7 | **End-to-end in both harnesses:** trials, judges, prompt authors and the contract author run through the existing runners, and a prompt test runs through them; a trial uses a host tool. **Replay:** a text-output case and a file-effect case, each recorded once from a real trial with its judge session, are judged from the recording. **Unit:** a prompt test's fixtures, with a generated value, appear in the trial workspace; a failed built-in tool decides its trial without a judge session unless its `on_tool_failure` is `judge`, when the judge decides its assertion; control trials run on the control source, where the target prompt file is empty and every other prompt file is unchanged, each with its paired formal trial's test case. **Delivery:** a certified delivery and keeping the incumbent both write each confirmed prompt test's values file, and its scenario and fixtures when the contract author created or edited them. Trials run under the profile of their cell, as the harness events show. A trial with a read-only service credential and one with a disposable fixture are both eligible, and a known persistent write is refused before dispatch. **Capacity:** the shared capacity bounds sessions across two concurrent CAT processes. **Trial plugins:** a trial installs exactly the plugins that the prompt test configuration lists for its harness, and a campaign whose configuration lists two plugins installs both; a project-built plugin comes from the build that `start` ran on the fork commit, not from the user's installed copy; a candidate's edit to a target that a project-built plugin ships, and a control's emptied copy, reach the plugin that the trial loads; a configuration without CAT runs trials without CAT; and `start` refuses a target without a configuration. |
| G8 | **Unit:** representative development trials go only to development cells; the weakest cell is the lowest effort of the least capable model in the scope by the proposal's capability ranking, and no trial runs to find it; a cell with a formal failure becomes a development cell; formal trials go to a prompt test's development cells first and to another scope cell only after every development cell and every cell below it are certified; allocation picks the riskiest prompt test first; control trials run only in cells certified by their built-in tools and judge, and extra formal trials run only to complete pairs; concurrency follows [§3.3](#33-allocation-and-concurrency) exactly, including the reset after a failure, the risk ceiling, the cell limit and the ceilings. **Throttling:** it responds to utilization, rejections and provider refusals, and a hold set by one process delays every other process's acquisition. **Volume warning:** the authentication warning appears for a subscription login. |
| G9 | The optimizer and capacity unit tests pass under parallel methods with `HOME` and `user.home` pointing at a nonexistent directory. A source audit finds no static mutable state, no `Thread.sleep` on tested paths, and no mutable test-class fields. Calibration and each Git operation have exactly one owning test, and no Java test starts a runner, a harness process or a model. |
| G10 | Both harness artifacts ship the new skill and the `prompt-optimizer` launcher. No reference to the old procedure remains, `failure-regression-promotion.md` follows D13, every recorded regression scenario is a prompt test directory in the format and location that [§6.3](#63-prompt-tests-and-test-cases) and §8 name, and a proposal that omits one is refused. |
