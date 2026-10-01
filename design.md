# Prompt Optimizer Design

This is the product and technical design of CAT's prompt optimizer. It is written for the AI agents that implement and
maintain the feature; a user should still be able to audit every promise in it. `plan.md` is the implementation plan
that turns this design into code and acceptance evidence.

## Design Goals

- **G1 — Improve and deliver for review:** Given a prompt system in a clean Git worktree, produce a stronger candidate.
  Merge it into that worktree as uncommitted changes only after it satisfies G3. For every other outcome, leave the
  worktree unchanged unless the user explicitly chooses one of two alternatives. They can keep a candidate proven better
  than the incumbent, labelled `improved, not certified` ([§2.10](#210-keeping-a-proven-improvement)), or accept an
  uncertified candidate, labelled `not certified`.
- **G2 — Let the user define compliance:** Derive a complete compliance contract proposal from the prompt system. The
  proposal covers requirements, categories, tests, trial profiles, the judge rubric and the test distribution. Run no trial until the
  user confirms it.
- **G3 — Certify a joint claim:** Report a candidate `certified` only when both claims hold, with at most a 5%
  probability that any certification, deficiency or improvement claim in the campaign is false:
  - **(a)** in every category, under each trial profile that can run it, the candidate's future pass rate under the
    frozen decision procedure exceeds 95%;
  - **(b)** in every category where the incumbent is proven to pass less than 95% of the time, the candidate is proven
    better than the incumbent.
  A category in which the candidate is proven worse than the incumbent blocks default delivery.
- **G4 — Keep evidence valid:** Development outcomes never count as certification evidence. Formal outcomes come only
  from fresh draws assigned after the candidate is frozen. A retry cannot discard an outcome. The order in which
  parallel trials finish cannot change a decision.
- **G5 — Report what was measured:** Show the four outputs, the measurement identity, current or historical
  applicability and limitations. Report the deterministic minimum number of trials still required, and never present
  a forecast as a bound.
- **G6 — Preserve user control and progress:** An interruption or crash loses at most the trials in flight, which
  rerun under the same logical-trial identity. Resuming continues without repeating completed work and never resets
  the campaign's error budget.
- **G7 — Use existing infrastructure in both harnesses:** Start every nested session (trial, judge, author, contract
  deriver) through the existing Claude or Codex runner. Trials may use the user's host tools and need no Docker. Total
  nested sessions per harness stay within one cap shared by all CAT processes.
- **G8 — Minimize work and wall time at a responsible volume:** Test the riskiest categories first. Keep parallelism
  low while failures are likely and raise it as passes accumulate. Stay within conservative session limits, slow down
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
3. proposes a compliance contract, plus warnings about symbolic links and expected session volume, and waits for the
   user to confirm or revise it;
4. measures the incumbent and develops candidates, testing the riskiest categories first;
5. commits a candidate to formal certification once it passes the readiness gate;
6. certifies it under the joint claim or reports why it could not. If an uncertified attempt proves the candidate
   better than the incumbent ([§2.10](#210-keeping-a-proven-improvement)), CAT pauses and asks the user whether to keep
   the best proven candidate so far and stop optimizing, continue optimizing, or stop without delivering; and
7. merges the selected candidate into the user's worktree for Git review. That is either a certified candidate proven
   worse than the incumbent in no category, or a candidate the user explicitly chose to keep.

The user commits, reverts or asks for another revision. CAT never commits on the user's behalf.

A campaign ends with exactly one of these outcomes, each with its evidence and available next actions:

| Outcome | Meaning |
|---|---|
| `certified` | G3 holds; delivery follows the decision table in [§2.8](#28-decision-table). |
| `improved, not certified` | The user kept a candidate proven better than the incumbent in at least one category, proven worse in none, and checked for regression in every other category ([§2.10](#210-keeping-a-proven-improvement)). |
| `not certified: below minimum` | A category is proven below 95% in the current certification attempt. |
| `not certified: inconclusive` | A category reached its trial cap undecided. |
| `not certified: plateau` | A full development round produced no stronger candidate. |
| `not certified: stopped for contract revision` | The user stopped a certification attempt to revise the contract. |
| `not certified: testing environment changed` | The user started a new measurement identity during a certification attempt ([§5](#5-measurement-identity-and-applicability)). |
| `certification unavailable: unsupported instruction source` | A remote or MCP response supplies governing instructions. |
| `certification unavailable: ineligible trial effect` | A required trial path writes persistent state outside the trial copy, or lacks read-only credentials or a disposable fixture. |
| `certification unavailable: unsupported output` | A material requirement produces image, audio or video output. |
| `certification unavailable: unsupported test interaction` | A material obligation needs a follow-up turn chosen from the agent's reply. |
| `certification unavailable: invalid statistical assumptions` | A detected dependence between trials invalidates the formal procedure ([§2.6](#26-validity-conditions-and-assumption-review)). |
| `interrupted` | Work stopped and can resume. |
| infrastructure failure | A precise failure that is not a compliance outcome. |

Several of these outcomes stop development and certification but leave the campaign open, so the user can still
resume or close it: `not certified: below minimum` and `not certified: inconclusive` apply to one certification attempt
and normally return to development automatically ([§2.8](#28-decision-table)).

### 1.1 Scope

The feature covers prompt optimization and certification in both installed harnesses, Claude and Codex, with the same
behaviour for every user. It runs locally against the user's configured provider account. The existing runner, Git and
prompt-loading capabilities are dependencies to reuse, not functionality to reimplement.

Out of scope: licensing, billing, cost prediction, usage analytics, telemetry, product releases, new harnesses or
platforms, Docker or another packaged runtime, and product feedback submission.

### 1.2 Terms

| Term | Meaning |
|---|---|
| Prompt system | The target prompt files together with the files, rules, configuration and loading relationships that shape their behaviour. |
| Compliance contract | The confirmed requirements, categories, test definitions, expected outcomes, judge, variation rules and probabilities. |
| Category | A class of use that needs its own claim, because averaging it with other uses could hide a weak area. |
| Trial profile | One model and reasoning-effort combination that a trial session runs under ([§6.6](#66-trial-profiles)). |
| Cell | One category under one trial profile. Every cell needs its own claim, because averaging profiles could hide a weak one. |
| Core-task requirement | The requirement, present in every contract, that each reply correctly and completely does what the turn asked. |
| Deterministic check | Code that decides one requirement from a trial's evidence, validated against a known-good and a known-bad control ([§6.4](#64-judge)). |
| Incumbent | The prompt system snapshot recorded at the fork, before any optimization. It never changes, and every candidate is compared with it. |
| Candidate | One exact snapshot: the incumbent plus an overlay of edits. The incumbent is the first candidate, with no edits. |
| Working candidate | The candidate that development currently samples and improves. It starts as the incumbent, and each promotion replaces it. The optimization worktree holds it. |
| Author attempt | One author session's edits. It becomes a candidate only after validation and promotion. |
| Promotion | Making a validated candidate the working candidate. It never consumes allowance. |
| Commit | Freezing the working candidate into a certification attempt, which permanently consumes that attempt's allowance ([§2.2](#22-joint-error-budget)). |
| Certification attempt | One frozen candidate, committed after the readiness gate, tested on fresh formal trials under one frozen procedure and cap. Its trials test the joint claim and measure improvement over the incumbent. Its certification index `i` numbers it within the campaign. |
| Logical trial | One assigned instance for one snapshot, judged once. Retries keep its identity. |
| Representative trial | A trial drawn from the confirmed distribution and counted in rates. Targeted reruns, promotion runs and regression runs are not representative. |
| Baseline arm | The incumbent's representative trials, used for the deficiency claim and comparisons. |
| Campaign | One explicitly started effort, with its own 5% joint budget and a fixed starting harness. |
| Lineage | The evidence history under one confirmed contract within a campaign. |
| Base worktree | The user's Git worktree. Only delivery writes to it. |
| Optimization worktree | CAT's fork of the base, holding the working candidate. |
| Staged source | A symbolic-link-free directory for one snapshot, which the runner copies for each trial. |
| Slice | One time-bounded run of the driver inside a foreground command. |

## 2. Statistical design

### 2.1 Claims

All claims concern the pass label of one frozen decision procedure, meaning the deterministic checks plus the frozen
judge ([§6.4](#64-judge)), under the confirmed test distribution of a category. They are not claims about actual
requirement compliance, judge calibration or unrepresented situations.

The statistical unit is the cell: one category under one trial profile ([§6.6](#66-trial-profiles)). Throughout §2 and
§3, "category" `k` means one cell, and `K` counts the contract's cells. A category whose prompt files bind one profile
has one cell. Every other category has one cell per profile in the confirmed profile set, so each of its trial counts
is multiplied by the number of profiles.

- **Certification claim (a):** For every material category `k`, the candidate's pass probability `pC(k)` exceeds 0.95.
- **Deficiency claim:** The incumbent's pass probability `pI(k)` is below 0.95. CAT makes this claim only for categories
  where its baseline evidence proves it.
- **Improvement claim (b):** In every category with a deficiency claim, `pC(k) − pI(k) > 0`.
- **Proven improvement:** In category `k`, `pC(k) > pI(k)` is proven when one certification attempt's evidence puts
  the candidate's lower confidence bound, at the attempt's per-category improvement level `β_i / K`, above `UI(k)`,
  whether or not (a) holds. `UI(k)` is the incumbent's upper confidence bound at its baseline's per-category level
  `γ_b / K` ([§2.2](#22-joint-error-budget)). This claim supports the keep-improvement option
  ([§2.10](#210-keeping-a-proven-improvement)).
- **Worse finding:** `pC(k) − pI(k) < 0` is proven at the same per-category level. This finding is not a guarantee; it
  blocks default delivery.

If (a) and the deficiency claim both hold for a category, then `pC(k) > 0.95 > pI(k)`, so (b) follows without
additional trials: the certification test's lower bound `LC(k)` at `α_i` exceeds 0.95 and `UI(k)` is below 0.95, so the
displayed difference bound `LC(k) − UI(k)` is positive. (b) therefore costs only the incumbent's baseline measurement,
which the four outputs need anyway.

### 2.2 Joint error budget

The campaign's total probability of making any false certification, deficiency or improvement claim is at most 0.05.
The budget is split three ways. Each part is spent by a series `x / (n·(n + 1))`, which sums to `x` over an unlimited
number of terms. A campaign can therefore commit any number of attempts, for any number of candidates, within the same
5%. Each later attempt receives a smaller share and needs more trials ([§2.4](#24-caps)).

- **Certification budget, 0.04:** Certification attempt `i`, numbered from 1 across the whole campaign, receives
  `α_i = 0.04 / (i·(i + 1))`: 2%, 0.667%, 0.333%, and so on.
- **Improvement budget, 0.005:** Certification attempt `i` also receives `β_i = 0.005 / (i·(i + 1))` for the
  improvement claims made from its trials. It is split equally across the attempt's `K` categories, so each
  category's proven-better decision uses `β_i / K`.
- **Incumbent budget, 0.005:** Incumbent baseline `b`, numbered from 1 across the whole campaign, receives
  `γ_b = 0.005 / (b·(b + 1))`. A campaign starts a new baseline whenever it measures the incumbent again: for a new
  contract lineage or a new measurement identity ([§2.5](#25-incumbent-baseline)). The share is split equally across
  the contract's `K` categories, so each category's incumbent bounds `UI(k)` and `LI(k)` use `γ_b / K`. Both the
  deficiency claim and the improvement claim rely on `UI(k)`.

An attempt receives its certification index when it is committed and keeps it permanently. Only a commit to a
certification attempt spends allowance. Development trials, promotions and candidates that are never committed consume
none, so development can try any number of candidates.

Claim (a) needs no division of `α_i` among categories. It is a conjunction: it is false only if some category's true
rate is at most 95%, and a false certification then requires that one category's level-`α_i` test to pass
incorrectly. Per-category claims are different. "Proven better in categories B and D" is false if either part is
false, and each category's test can fail independently, so the improvement budget and the incumbent budget are divided
by `K`. A union bound across attempts, baselines and the three budgets gives a joint error of at most 0.05. This
guarantee requires every attempt to remain valid given the history that selected it
([§2.6](#26-validity-conditions-and-assumption-review)).

Candidate upper bounds and incumbent lower bounds serve only the worse finding, which can block delivery but never
supports a claim, so they consume no budget.

The budget is per campaign. An explicit new campaign receives a fresh budget, and the interface says so. Candidate
edits, failed attempts, contract revisions, restarts and cancel-then-resume never reset it.

### 2.3 Sequential tests

Each category in a certification attempt uses anytime-valid betting tests on the outcomes adopted in assignment order
([§3.4](#34-adoption-order-and-draining)). Let `S` be the number of adopted passes and `F` the number of adopted
failures.

**Certification test (null: the pass probability is at most 0.95).** The e-value is

```
E↑(S, F) = Σ_m w_m · (1 − c_m + c_m / 0.95)^S · (1 − c_m)^F
```

with bets and weights `(c, w) = (0.4, 0.1), (0.6, 0.2), (0.8, 0.5), (1.0, 0.2)`. Each bet `c` is the growth-optimal
bet against the alternative pass rate `r = 0.95 + 0.05·c`, which is 97%, 98%, 99% and 100%. The weights favour 99%.
The term with `c = 1.0` vanishes after the first failure, because `0^0 = 1` and `0^F = 0` for `F ≥ 1`.

**Below-minimum test (null: the pass probability is at least 0.95).** The e-value is

```
E↓(S, F) = Σ_m (1/3) · (1 − λ_m)^S · (1 + 19·λ_m)^F
```

with `λ = 9/19, 3/19, 1/19`, which bet against pass rates of 50%, 80% and 90%.

Decisions:

- A category **certifies** in certification attempt `i` when `E↑ > 1/α_i`.
- A category is **clearly below** in certification attempt `i` when `E↓ > 1/α_i`.
- An incumbent category is **proven deficient** when `E↓ > K/γ_b`, using its baseline's per-category level `γ_b / K`
  from [§2.2](#22-joint-error-budget).
- A candidate category is **proven better** in certification attempt `i` when `E↑`, with the null rate `UI(k)` in place
  of 0.95, exceeds `K/β_i`. `UI(k)` is rounded up to 4 decimal places and frozen when the attempt is committed. The
  incumbent's baseline is complete by then.
- A candidate category is **proven worse** in certification attempt `i` when `E↓`, with the null rate `LI(k)` in place
  of 0.95, exceeds `K/β_i`. `LI(k)` is the incumbent's lower bound at `γ_b / K`, rounded down to 4 decimal places and
  frozen at commit. Using the proven-better level makes "proven better" and "proven worse" equally strong statements.
- Equality never decides. Every decision compares exact rationals. An implementation may decide from floating-point
  logarithms only when the margin exceeds a tolerance proven safe, and must use exact arithmetic otherwise.
- Because both e-values depend only on `(S, F)`, a category's statistical state is fully described by those two
  counts.

For a null rate `t` other than 0.95, both e-values replace `0.95` with `t` and `19` with `t / (1 − t)`. Confidence
bounds invert the same two tests over `t` at the stated level. A displayed bound is a display value and may use
floating-point arithmetic; a decision against a frozen `t` uses the exact rules above.

### 2.4 Caps

Before a certification attempt's first draw, CAT freezes `α_i`, `β_i`, `UI(k)` and `LI(k)`, the bets and weights, the
allocation policy, and a per-category cap. The cap is the smallest number of adopted outcomes
at which the probability of certifying reaches 90% for a category whose true pass rate is 99%, given both early stops.
CAT computes it with an exact absorbing-state calculation over `(S, F)`. Calibration that fails or is unavailable blocks
admission; CAT never runs an attempt without a cap.

| Certification attempt `i` | `α_i` | Minimum all-pass trials per category | Cap per category | P(certify within 600) if true rate 98% |
|---:|---:|---:|---:|---:|
| 1 | 2.000% | 93 | 262 | 0.854 |
| 2 | 0.667% | 117 | 328 | 0.759 |
| 3 | 0.333% | 132 | 384 | 0.677 |
| 4 | 0.200% | 143 | 398 | 0.601 |
| 5 | 0.133% | 152 | 409 | 0.568 |
| 10 | 0.0364% | 179 | 481 | 0.433 |
| 50 | 0.00157% | 244 | 638 | 0.183 |
| 100 | 0.000396% | 271 | 712 | 0.108 |

The cost grows slowly with the attempt count: a candidate that passes every trial needs 93 trials per category in
attempt 1 and 271 in attempt 100. These values are regression fixtures for the calibration code. When an unresolved
category reaches its cap, the attempt ends `not certified: inconclusive`. CAT never raises a frozen cap or reopens an
attempt.

### 2.5 Incumbent baseline

The incumbent is the immutable prompt-system snapshot recorded at the fork. It is also the first working candidate,
so its representative development trials are its baseline. After development replaces it, the incumbent keeps running
as a separate baseline arm on spare capacity ([§3.3](#33-allocation-and-concurrency)) until it reaches
these targets:

- 50 representative trials in every category;
- 50 more trials in a category whose observed incumbent rate is below 95% but not yet proven deficient, ending at 100.

A commit requires these targets to be met. The baseline is reused across candidates while measurement identities stay
comparable ([§5](#5-measurement-identity-and-applicability)). Otherwise the incumbent is measured again as a new
baseline with its own share of the incumbent budget ([§2.2](#22-joint-error-budget)). A new baseline never reuses an
earlier baseline's share, because both could then make a false claim.

For 1 to 8 categories in the first baseline, 50 trials prove deficiency at 10–12 failures (80% observed or lower), and
100 trials prove it at 16–17 failures (84% observed or lower). An incumbent category near 90% therefore usually remains
"deficiency unproven". In that case (b) is not required for that category, and the report shows the descriptive
difference interval instead.

### 2.6 Validity conditions and assumption review

Ville's inequality makes each test valid under every stopping rule and every adaptive choice of the next category. It
holds provided that, for each category, every trial's pass probability given all earlier adopted outcomes and
scheduling decisions is at most the null value. The design meets or monitors each threat to that condition:

| Threat | Why it matters | Design response |
|---|---|---|
| Faster trials finishing first | If failures take longer, deciding on completion order biases toward passes. | Adopt outcomes strictly in assignment order ([§3.4](#34-adoption-order-and-draining)). |
| Adaptive category choice and concurrency | Scheduling depends on past outcomes. | The category is chosen and persisted before the draw, and the draw within the category is independent of outcomes. Concurrency changes timing only. Both are allowed by the supermartingale argument. |
| Shared state between concurrent trials | Trials that read each other's files, judge results or assignments are no longer conditionally independent. | Each trial runs in a fresh session with its own isolated project copy and harness home. Trial staging excludes all optimizer state ([§4.4](#44-trial-staging)). |
| Judge memory | A judge that sees earlier trials links outcomes. | One fresh judge session per trial, and the judge receives only that trial's evidence. |
| Provider prompt caching | Shared prefixes are cached across sessions. | Caching changes latency and cost, not sampling given the prompt. This is an assumption, recorded as a limitation. |
| Throttling and load under parallelism | Load could change model behaviour, or turn errors into failures. | Provider errors and rate-limit rejections are infrastructure failures that rerun the same logical trial. Every trial records its concurrency level and throttle state. |
| Parallel conditions differing from real use | Real use is usually one session at a time. | The test stays valid under the weaker null "every trial's conditional rate is at most 95%", even when conditions vary. The only risk is that parallel conditions are better than serial ones. The report runs a descriptive comparison of pass rates by concurrency level, and the claim names the measured conditions. |
| Discarding unfavourable outcomes | A retry could hide a failure. | A valid compliance outcome is recorded once. Only an attempt with no outcome reruns, as the same logical trial with the same instance. A trial that exceeds its declared time limit is judged on its behaviour, not retried. A logical trial with 3 infrastructure failures, not counting rate-limit rejections, pauses the campaign; it is never skipped. |
| Formal outcomes tuning the same candidate | Editing a candidate in response to its formal results. | A formal outcome may inform development, but any edit creates a new candidate and needs a new attempt. |

If CAT detects dependence it cannot rule out, the result is `certification unavailable: invalid statistical
assumptions`, not a weaker claim. One example is an assignment that read another trial's records.

### 2.7 Development: readiness and edit-or-continue

All development numbers apply to one candidate snapshot, one category and comparable measurement conditions. An edit
creates a new candidate with new counts. Earlier outcomes remain available for diagnosis.

**Readiness gate (commit condition).** Every material category needs all of the following:

- at least 100 representative development trials;
- at least 99% observed passes;
- latest two completed batches of 50 each at 98% or more;
- no unresolved reproducible defect or known regression.

The incumbent's baseline targets must also be met ([§2.5](#25-incumbent-baseline)). The gate is a cost control, not
evidence. It keeps weak candidates from spending allowance on a commit they will probably fail. Within the first
attempt's cap of 262 trials, a candidate certifies with probability 0.90 if its true rate is 99%, 0.49 if it is 98%, and
only 0.17 if it is 97%.

**Edit-or-continue.** After an adopted representative failure, CAT:

1. pauses new representative admissions for that candidate; in-flight trials finish;
2. reruns the failing instance up to 3 times as a targeted diagnostic, outside the representative counts;
3. **edits** if any rerun fails (the defect is reproducible), or if the candidate can no longer meet the gate by the
   200-trial review checkpoint, which means more than 2 failures in any category;
4. otherwise **continues** sampling, with concurrency restarting at 1 ([§3.3](#33-allocation-and-concurrency)).

The failure's diagnosis is retained either way.

**Candidate generation and promotion.** An author session edits a copy of the working candidate inside the confirmed
authority ([§4.3](#43-candidates-and-edit-authority)). It receives the failing instances, the judge rationales and
deterministic-check results, the diagnoses and the contract. The author works as follows:

1. **Classify each failure** before editing:
   - *routing*: an existing instruction was not loaded or not applied, so the author fixes how the prompt system
     reaches or triggers it;
   - *guidance*: an instruction is missing, ambiguous or contradicts another, so the author amends it;
   - *not guidance*: a model limitation, a test or contract defect, or an infrastructure fault, so the author makes no
     prompt edit for it.
2. **Make the smallest correction** that the evidence supports, in the prompt file that owns the decision. Removing a
   contradiction or an ambiguity comes before adding emphasis. Capitals, "important" markers and repeated instructions
   are not corrections.
3. **Generalize deliberately.** For each change, name one analogous case that must take the same path and one
   dissimilar case that must not receive the new guidance, and word the change for the shared invariant.
4. **Cite evidence.** The attempt carries a manifest:
   - every changed hunk lists the failing instances that motivate it, together with its analogous and dissimilar cases;
   - every failure lists its classification.

CAT rejects an attempt whose manifest omits a changed hunk, or cites an instance that is not among the failures it
received. A rejected attempt contributes no candidate. Its evidence is retained, as for an inventory violation
([§4.6](#46-inventory-validation)). An attempt that classifies every failure as *not guidance* makes no edit and
produces no candidate. Its classifications appear in the round's diagnosis and in the report at a review checkpoint or
plateau, so the user can revise the contract when a test is at fault.

CAT validates the attempt's file inventory, then runs the attempt on the triggering
instances twice each and on the lineage's regression set once each. The regression set is every instance that failed
representatively for any earlier candidate. These runs are targeted and never counted. CAT **promotes** the attempt to
working candidate when every triggering-instance run passes and it fails no regression instance that the current
working candidate passes. The working candidate's result on an instance is its latest outcome there. An instance that
failed only in the incumbent's baseline arm may have none; the promotion runs then also run the working candidate on
it once.

**Minimality pass.** Before a promoted attempt becomes the working candidate, CAT removes the parts of it that the
evidence does not need. It divides the attempt's changed hunks into groups and reduces them by delta debugging:

1. Start with two groups.
2. For each group, revert that group's hunks to the working candidate's text.
3. Run the reduced attempt through the same promotion runs and the same promotion rule.
4. When a reduced attempt passes, it replaces the attempt and the reduction continues from it. Otherwise, split the
   groups more finely, down to single hunks.

The pass ends when no single remaining hunk can be removed. These runs are targeted diagnostics: they never enter
representative or formal counts and never consume allowance. Removing a hunk can only drop text the author added or
changed, so the result stays inside the attempt's authority. The minimal attempt is the one CAT promotes. A promoted
working candidate then receives representative trials from zero.

For `h` hunks, the pass needs at most a number of reduction runs that grows with the square of `h`, and usually far
fewer. Each reduction run repeats the promotion runs. Because the promotion rule samples each triggering instance only twice, a needed hunk can be removed
by chance. The representative trials that follow catch that, as they catch any other weak candidate.

**Review checkpoint.** At 200 representative trials in a category for an unchanged candidate with that category still
unresolved, CAT stops scheduling that category. It shows the evidence and offers more evidence, targeted optimization,
or stopping. An authorized extension names its next review point.

**Rounds and plateau.** In a development round, every currently failing category receives a bounded diagnosis and up
to 2 author attempts. A round that promotes a stronger candidate still below readiness starts the next round
automatically. `not certified: plateau` requires a complete round that produced no promotable attempt. An interrupted
round or an infrastructure failure never establishes a plateau. CAT never predicts a plateau from elapsed time or
tokens.

Development estimates use exploratory 95% Wilson intervals. They are labelled `development estimate` and
`not certified`, and they are never presented as simultaneous guarantees.

### 2.8 Decision table

Formal decisions, per certification attempt:

| Formal evidence | Comparison with incumbent | Action |
|---|---|---|
| A category is clearly below | Any | End the attempt `not certified: below minimum`, retain all evidence, and return to development automatically with formal failures as diagnostic input. |
| A category reached its cap undecided | Any | End the attempt `not certified: inconclusive` and return to development automatically. |
| Every category certified | No category proven worse | `certified`. Every deficient category is improved by construction. Deliver automatically ([§4.7](#47-delivery)). |
| Every category certified | Some category proven worse | `certified`, but retain the incumbent by default. Delivery requires the user to explicitly accept the regression trade-off. |

When a certification attempt ends `not certified` and its evidence meets the keep-improvement conditions
([§2.10](#210-keeping-a-proven-improvement)), CAT pauses instead of returning to development automatically. It offers
`keep improvement`, which delivers a proven candidate ([§2.10](#210-keeping-a-proven-improvement)) and ends
optimization, `optimize further` or `stop`, so the user decides whether more optimization is worth its cost.

The user can stop at any point. At a plateau, a stop or another uncertified outcome, CAT offers `keep improvement` when
any attempt has met those conditions, and may offer `accept without certification`. Each is a separate explicit choice
and never the default. CAT labels the merged result `improved, not certified` or `not certified` respectively.
Certification status and delivery status are always reported separately.

### 2.9 Four outputs

Both phases display these outputs from the same observations, with unavailable values shown along with their reasons:

| Output | Presentation |
|---|---|
| Estimated pass rate | Candidate passes/trials and rate per category, under the frozen judge. |
| Improvement over the incumbent | Candidate minus incumbent, in percentage points per category, with the incumbent's identity, counts, deficiency status and the difference interval. A certification attempt also shows `proven better`, `proven worse` or `undecided` at `β_i / K`. |
| Comparison with 95% | Certification attempt: `certified`, `clearly below` or `inconclusive`, with the bounds and `α_i`. Development phase: the readiness assessment and always `not certified`. |
| Error bars | Method, level or exploratory status, sample size and endpoints. The one-sided certification bound is kept visibly distinct from descriptive two-sided intervals. |

Allocation counts are shown apart from the benchmark weights. A pooled adaptive pass fraction is never presented as
the future pass rate. Error bars do not cover unknown usage frequencies or missing scenarios.

### 2.10 Keeping a proven improvement

When reaching 95% proves too costly, the user can keep a candidate that is proven better than the incumbent and stop
optimizing. The result is labelled `improved, not certified`.

**Certification evidence only.** Only a certification attempt's trials can prove an improvement. Its trials answer
two questions at once: whether each category exceeds 95%, and whether it beats the incumbent. A candidate's improvement
is therefore known only after it passes the readiness gate and is committed. Development results cannot be reused for
it: the candidate was selected because its development results looked best, so testing those same results afterwards
overstates it. The claim uses only the adopted outcomes of one certification attempt.

**Conditions.** One certification attempt under the current contract must show all of the following:

- at least one category is proven better ([§2.3](#23-sequential-tests));
- no category is proven worse;
- every other category was certified or has at least 100 adopted outcomes in that attempt.

The last condition is the regression check: a category is kept only after enough formal trials to reveal a large
regression. A category that the attempt left with only a few outcomes, because another category ended the attempt
early, has not had that check. Attempts under an earlier contract are historical ([§7](#7-campaign-lifecycle))
and do not qualify.

**Best candidate so far.** CAT retains every qualifying attempt, so a proven candidate stays available after the user
chooses `optimize further`, in case later candidates fail. CAT ranks qualifying attempts only against attempts under the
same measurement identity ([§5](#5-measurement-identity-and-applicability)). Within one identity, the best attempt is
the one whose weakest category has the highest 95% Wilson lower bound on its formal pass rate. Ties go to more
categories proven better, then to the more recent attempt. The ranking is descriptive and does not prove the offered
candidate better than the other qualifying ones. Every qualifying candidate's own improvement claim remains valid,
because the joint budget covers the claims of all attempts at once ([§2.2](#22-joint-error-budget)).

**Earlier measurement identities.** An attempt under an earlier measurement identity of the current contract still
qualifies, but its claim describes the conditions it was measured under, so its result is historical. Results under
different identities are not comparable, so CAT never ranks them against each other or calls one better. CAT offers
the best attempt of each identity as its own choice: the current identity first, then each earlier one, labelled
historical and naming each condition that differs from the current identity.

**When CAT offers it.** When a certification attempt ends without certification and its evidence meets the conditions,
CAT pauses and offers `keep improvement` ([§2.8](#28-decision-table)). At a later review checkpoint, plateau or stop,
CAT offers `keep improvement` again whenever any attempt qualifies. `keep improvement` delivers the candidate the
user chose from those offers and ends optimization.

**Report.** Each category shows its difference interval. A category that is not proven better might still be slightly
worse within that interval, and the report says so. Keeping an improvement is always an explicit user choice. It never
happens automatically and never produces a `certified` label. Delivery follows [§4.7](#47-delivery) for the frozen
snapshot of the chosen attempt.

## 3. Execution

### 3.1 Nested sessions

CAT runs four kinds of work in nested sessions, each started through the current harness's existing runner
(`ClaudeRunner` or `CodexRunner`) in a fresh isolated run directory:

| Role | Input | Output |
|---|---|---|
| Contract deriver | Read-only prompt system, user feedback | Complete contract proposal in a strict schema |
| Trial agent | Staged candidate project, the assigned turn sequence and its cell's trial profile | Transcript, final responses, project effects |
| Judge | Frozen rubric, the instance, that trial's evidence (read-only) and the semantic requirements it decides | `pass` or `fail` with rationale, in a strict schema |
| Author | Copy of the working candidate and the failure evidence | Edited prompt files in its copy, and the evidence manifest ([§2.7](#27-development-readiness-and-edit-or-continue)) |

Each trial session runs under its cell's trial profile ([§6.6](#66-trial-profiles)). The deriver, judge and author
sessions run under the harness's **role profile**: the model and effort that CAT's highest implementer tier binds
(`optimize-xhigh`). The contract records the role profile as part of the frozen judge. Changing it is a judge change
([§5](#5-measurement-identity-and-applicability)).

A trial needs a judge session only for its semantic requirements. When every requirement it checks is deterministic,
or a deterministic check has already failed, CAT records the outcome without starting a judge ([§6.4](#64-judge)).

A judge or deriver output that does not match its schema is an infrastructure failure. The output is the final
response when that response is exactly one JSON object, either bare or alone inside one Markdown code fence; any other
response is invalid (Claude fences its JSON, H23). Candidate and trial content
cannot change the judge's rubric, schema or authority. Every nested session is counted against the campaign ceiling and
the shared harness capacity ([§3.6](#36-shared-harness-capacity)).

Authors may see development instances and outcomes, and the formal outcomes of attempts that have ended. They never see
the instances of a certification attempt in progress.

The host agent that runs the `prompt-optimizer` skill does not author, judge or derive. It presents proposals, choices
and progress, and relays the user's answers. Deterministic decisions stay in the Java driver.

### 3.2 Logical trials

A **logical trial** is one assigned instance for one snapshot: a category, a test definition, variation values and a
complete prewritten turn sequence. It has one sequence number per category, purpose and snapshot. Its record moves
through `assigned → running → judged`, and adopted or reconciled states, as follows:

- The assignment record is persisted before any session starts. It holds:
  - the category, test definition, variation values and their probabilities;
  - the generator identity, draw stream and position;
  - the complete turn sequence;
  - the snapshot and its purpose (development, targeted, baseline or certification attempt `i`);
  - the allocation decision with the evidence revision it used, and the assignment time.
  The judged outcome, the judge identity and every infrastructure attempt are added later. The record establishes
  ordering and guards against selecting favourable instances; it does not claim the execution is reproducible.
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
- A run that ends without a compliance outcome (provider error, rate-limit rejection, interruption, crash) is an
  infrastructure attempt. The same logical trial runs again with the same instance. After 3 infrastructure failures
  of the same logical trial, the campaign pauses with a precise infrastructure failure. Rate-limit rejections do not
  count toward that limit; they follow the backoff in [§3.5](#35-volume-safeguards).
- A trial agent may use the harness's own subagents, recursive delegation and child Git worktrees. All of it belongs to one logical
  trial and yields one judged outcome. Child disposition and merges are reconciled before judging. If they cannot be
  determined, the trial records an observation limit and receives no invented label. Codex trials cannot create child
  worktrees under the runner's sandbox (H18); the judge sees that failure as part of the evidence.
- Exceeding the test definition's time limit is behaviour: CAT judges the trial on its evidence and does not retry it.

### 3.3 Allocation and concurrency

**Allocation (which category gets the next trial).** CAT decides each admission from the persisted state, highest
priority first:

1. pending targeted work, which is reruns, promotion runs and regression runs;
2. development only: the cell tier of [§6.6](#66-trial-profiles). Representative assignments go only to active
   cells, and among them the coverage floor applies: no cell may be more than 50 representative assignments ahead of
   the least-sampled eligible active cell;
3. development: the lowest exploratory Wilson 95% lower bound for the current candidate. An empty category counts as 0.
   Ties are broken by fewer outcomes, then the contract's risk rank, then category order;
4. formal: one trial per category first, then the category furthest from certifying: the lowest current lower bound
   at `α_i`. Ties are broken by fewer outcomes, then category order;
5. baseline-arm trials for the incumbent. The concurrency window below limits only the active candidate's trials. A
   failure never wastes a baseline-arm trial, so these trials may use any capacity left under the campaign ceiling.

Rule 3 sends the first, low-concurrency trials to the riskiest categories, so their failures surface and are fixed
before parallelism rises.

**Concurrency (how many trials run at once).** For the active candidate, let `n` and `f` be its adopted outcomes and
failures. In the formal phase these include the same snapshot's development outcomes, which is safe because concurrency
changes only timing. Let `û` be the one-sided 95% Clopper–Pearson upper bound on the failure rate from `f` of `n`, with
`û = 1` when `n = 0`. The window `w` is the largest value allowed by every limit below:

- **Risk ceiling:** `floor(1 / (2·û))`, at least 1. Under the pessimistic failure rate, the expected number of failures
  among the trials in flight is at most ½. With no failures it first permits 2 trials at `n = 11`, 4 at `n = 23` and
  8 at `n = 47`. With 1 failure in 100 it permits 10.
- **Slow start:** after any adopted failure of this candidate, `w` returns to 1, and it doubles after each window whose
  trials all pass.
- **Category limit (formal):** in-flight trials in category `k` never exceed the passes still required to certify it
  if all of them pass, and are at least 1. This bounds the trials wasted after a decision.
- **Campaign ceiling:** default 8, configurable from 1 to 16.
- **Throttle limit and capacity:** see [§3.5](#35-volume-safeguards) and [§3.6](#36-shared-harness-capacity).

Concurrency is a deterministic function of persisted counts, the clock and the reported throttle state. It never needs
a forecast model.

### 3.4 Adoption order and draining

Within a category and purpose, CAT adopts an outcome into `(S, F)` only after every earlier-assigned outcome is adopted.
Every statistical decision uses that contiguous prefix only. When a category reaches a decision that stops it, or the
attempt ends, CAT stops admitting new trials for it. Certified and clearly below stop a category. Trials already in
flight finish and are recorded as post-decision observations that never change the decision. A cancelled post-decision
trial records no outcome.

### 3.5 Volume safeguards

CAT cannot know the threshold at which a provider flags automated volume, so it keeps volume conservative and honest:

- **Authentication warning:** if the harness authenticates through a consumer subscription login rather than an API
  key, the contract proposal warns about it, states the minimum session count ([§3.9](#39-progress-and-remaining-work)),
  and recommends an API key for campaign-scale use.
- **Ceilings:** the campaign ceiling ([§3.3](#33-allocation-and-concurrency)) and the per-harness capacity shared by
  every CAT process ([§3.6](#36-shared-harness-capacity)). Each counts every nested session, including judges and
  authors.
- **Gradual ramp:** concurrency only grows through the slow-start and risk ceilings.
- **Rate-limit response:** when a harness reports utilization at 80% or more of any window, `w` stops growing. At 95%
  or more, or on a rate-limit rejection, `w` drops to 1 and no new admissions start until the reported reset time.
  Without a reset time, CAT backs off for 1, 2, 4, … minutes, up to 30 minutes. A rejected trial reruns as the same
  logical trial after the wait.
- **No evasion:** CAT presents its real client identity and never adds randomized human-like pacing, rotates accounts,
  credentials or network addresses, or works around a harness's own limits.
- **Records:** every trial records its concurrency, throttle state, start and end times, and reported token usage.

### 3.6 Shared harness capacity

`HarnessConcurrencyLimit` currently lets every caller acquire one of N file-lock slots under
`$HOME/.cat/locks/harness-capacity`. The slots are static and shared by all harnesses, and `ClaudeRunnerCli` is the
only caller. Removing the lock is not safe: it is the only bound on the total number of nested sessions across
independent CAT processes, and the optimizer is designed to start many.

The redesign keeps the cross-process bound and removes its test and coupling hazards:

- Capacity becomes an instance created by `main` from `HOME` and passed to whatever needs it. It applies per harness,
  under `$HOME/.cat/locks/harness-capacity/<harness>/`, so Claude and Codex sessions do not compete.
- Both `ClaudeRunnerCli` and `CodexRunnerCli` acquire it. The optimizer acquires one permit per nested session from the
  same instance type.
- Acquisition waits through an injected pause, not `Thread.sleep`. A non-blocking `tryAcquire` lets the driver keep
  scheduling while it waits.
- Tests construct an unlimited instance or one under a test-owned directory, so no test contends with another test or
  with real sessions.

### 3.7 Process model

The probes in [§11](#11-harness-evidence) rule out a detached background worker. The sandboxed Claude host kills every
descendant of a tool command when the command ends or the user presses ESC, including `setsid` and `nohup` children.
The driver therefore runs as a foreground command in time-bounded **slices**:

- Each `prompt-optimizer run` reconciles state, advances the campaign, and returns a status within its slice. The
  default slice budget is 8 minutes, and the skill invokes it with a tool timeout longer than the slice (10 minutes in
  Claude; Codex is premise P10).
- Admission stops when the current time plus the 90th-percentile trial duration in this campaign would pass the slice
  deadline. Before 5 trials have completed, CAT uses the test definition's time limit instead.
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
- **Write-ahead order:** CAT persists each assignment before starting its session and each outcome before adopting it.
  The campaign snapshot is replaced atomically after adoption. A crash at any point leaves either a logical trial to
  rerun or an outcome to adopt; it never leaves a duplicate outcome or a lost failure.
- **Reconciliation** runs first in every `run`. It adopts recorded outcomes that are not yet in the snapshot, marks
  sessions that started without an outcome for rerun, and verifies the optimization worktree inventory
  ([§4.6](#46-inventory-validation)). It also terminates the processes recorded in the run manifest (`ownerPid`,
  `harnessPid`) of every run left by an earlier driver, since Codex runner processes outlive a killed driver (H20). The
  runner's stale-run maintenance deletes orphaned run directories.

### 3.9 Progress and remaining work

Each status shows the phase, the current candidate, the per-category four outputs, concurrency and throttle state, and
the **minimum remaining trials**: the number still required if every remaining trial passes.

- **Development:** the sum over categories of the fewest further all-pass trials after which the category meets the
  readiness gate's count conditions ([§2.7](#27-development-readiness-and-edit-or-continue)): at least 100 trials, at
  least 99% passes, and the latest two completed batches at 98% or more. This is at least
  `max(0, max(100, 100·f_k) − n_k)`, and more when a recent batch holds 2 or more failures. Add the incumbent's
  remaining baseline, and the next attempt's all-pass minimum for every category.
- **Formal:** for each unresolved category, the smallest `s` such that `E↑(S + s, F)` exceeds `1/α_i`.

The status also shows each nested-session kind's observed token usage per session (median and 90th percentile) when
the harness reports it. CAT does not show a token total, a time estimate or a completion probability. Any of those
would be a forecast with no calibrated predictor, and a minimum observed token count is not a bound on future trials.
The minimum trial count is exact.

The status compares the working candidate with the incumbent on two observed sizes: the prompt files' total size in
bytes and in words, and the median input tokens per trial session in each cell where both have outcomes. These are
measurements of the snapshots and of completed trials, not forecasts. A minimality pass reports the hunks it removed.

## 4. Workspaces and files

### 4.1 Storage

Campaign data lives under `<base>/.cat/work/optimizer/<campaign-id>/`:

- `campaign.json`: a compact snapshot holding identities, the frozen contract and procedure, `(S, F)` per candidate and
  category, allocation positions, and pending decisions;
- the latest proposal, including its derived prompt sources;
- one record file per logical trial;
- candidate overlays, and staged sources, which are caches: CAT deletes a staged source once its snapshot is no longer
  scheduled and rebuilds it from the overlay when needed;
- the optimization worktree;
- the delivery journal.

Loading a campaign reads only the snapshot, so its cost does not grow with the number of trials. Trial records are read
for diagnosis, reports and reconciliation of the in-flight tail. CAT never rebuilds state by replaying all trials.

Before creating anything, CAT checks that `.cat/work/` is ignored by Git. If it is not, CAT appends `/.cat/work/` to the
repository's `info/exclude` and tells the user, so campaign files never make the base worktree dirty. This file is
local to the clone and never appears in a diff.

### 4.2 Fork and optimization worktree

A new campaign requires a base worktree with no staged, unstaged or untracked changes. Otherwise CAT stops before
creating anything and tells the user to commit, stash or remove the changes. CAT records the prompt system's paths,
bytes, modes and symbolic links. It copies participating files that are ignored by Git, or that lie outside the
project, into campaign storage. It then adds a detached optimization worktree at the base `HEAD`, which always holds
the current working candidate for the user to inspect.
Confirmation records the confirmed contract's trial fixtures, and the derived prompt sources that the fork does not yet
hold, the same way before anything runs, so trials never read a live fixture or source. A revision after
confirmation that needs a file the fork does not hold is refused, and the user starts a new campaign.

Base-worktree edits after the fork do not invalidate the campaign; delivery handles them ([§4.7](#47-delivery)).

### 4.3 Candidates and edit authority

A candidate is an exact snapshot: the fork's prompt system plus an **overlay** of content edits and ordinary-file
creates, renames and deletes. Its identity is a hash of the overlay and the fork identity. Authority comes from the
confirmed contract:

- In-project prompt files: content edits and ordinary-file create, rename and delete.
- Existing symbolic-link entries, their destinations and existing file modes stay fixed.
- Loader configuration, meaning whatever decides which prompt files load, is fixed for the campaign. A needed loader
  change is prepared by the user before a new campaign.
- External targets reached through links stay read-only, unless the user explicitly authorizes a target. Even then
  only content edits to existing files are allowed, never create, rename, delete, mode change or retarget.
- An edit made at a link path applies to its resolved target. If an attempt makes different edits to the same target
  through two paths, CAT rejects it as ambiguous.

When diagnosis finds that a requirement needs an edit outside this authority, CAT warns the user. The warning names the
requirement, the link path, the resolved target, the limit and the viable choices: authorize the target, use another
authorized source, or stop or revise the campaign. Reauthorizing an already authorized external target cannot permit a
structural edit.

The warning is an `awaiting-user` decision. The author reports each edit it needed but did not make, and CAT checks each
one against this authority. Choosing to authorize a target records it in the campaign, and later author attempts may
edit its content.

### 4.4 Trial staging

The runner refuses a project that contains symbolic links (`HarnessRunManager.copyProject`), and it copies everything
else in the project. CAT therefore materializes one **staged source** per candidate, including the incumbent. It is a
directory the runner copies for each trial, and it contains:

- the fork commit's tracked files;
- the recorded participating ignored and external prompt files;
- any ignored paths the confirmed contract lists as trial fixtures;
- the candidate overlay;
- **every symbolic link replaced by a copy of its resolved target**, so trial writes through a link reach only the trial
  copy and never a live target. A link cycle, or a link to a missing target, is reported in the proposal and blocks
  confirmation until it is resolved.

Staging excludes `.cat/work/`. This keeps a trial agent from reading other trials, judge results or formal assignments.
User-level rules reach the nested harness home through the runner's existing user-rule handling.

### 4.5 Symbolic-link warning

Before confirmation, the proposal lists every symbolic link that it finds in the prompt system or in staged fixtures.
For each link it shows:

- the link path and its resolved target;
- whether the target is inside or outside the project, and its edit authority;
- the staging consequence: trials see a regular copy at the link path, so a prompt or tool that inspects link metadata
  or resolved paths may behave differently in trials than in production.

This consequence is also recorded as a limitation of every result.

### 4.6 Inventory validation

CAT compares complete inventories, read without following links. An inventory covers tracked, untracked and ignored
paths, symlink metadata, modes, loader configuration and staged targets. CAT compares them:

- **Before admitting an author attempt:** against the attempt's starting snapshot. Any forbidden operation or final
  change rejects the whole attempt as an infrastructure failure. The attempt contributes no candidate and no outcome,
  and its evidence is retained.
- **At every `run`, before promotion and before delivery:** the optimization worktree is checked against CAT's recorded
  operations. An unowned change stops scheduling and asks the user how to proceed, without incorporating the change.
  An edit that is made and reverted before an observation can escape this check; CAT does not claim to detect every
  write.

The authority, conflict and merge decisions are pure functions over in-memory inventories
([§10](#10-testability-contract)).

### 4.7 Delivery

Delivery applies the selected candidate to the base worktree, together with any authorized external target edits. The
candidate is a certified one, a kept improvement, or an explicitly accepted uncertified one:

1. Verify that the optimization worktree holds only content CAT recorded ([§4.6](#46-inventory-validation)). If the
   selected candidate is not the working candidate, as when the user keeps an earlier improvement, materialize it there
   from its overlay first, so the user can inspect exactly what will be delivered.
2. Preflight the complete combined change set against the fork baseline and the current base and external targets.
   Changes to the same file on both sides, structural collisions, changed link destinations and target identity
   mismatches stop delivery before any write and ask the user for direction.
3. Journal the delivery, then apply each change after validating its live preimage. A concurrent edit stops further
   writes without overwriting it. Restart resumes the same journaled delivery and applies each change at most once.
4. Verify the combined result, then report the Git diff, the external change record and certification applicability.

Base-only edits survive. If a base-only edit changed another participating prompt source, the merged system is a
different snapshot, so CAT merges it but reports the certification as historical. A partial delivery is never reported
as complete or current.

## 5. Measurement identity and applicability

Every result is a point-in-time observation. CAT records the conditions needed to interpret it:

- the candidate snapshot and the fork identity;
- the contract, generator and judge identities;
- for each cell, its configured trial profile and the model and reasoning configuration **observed in harness events**.
  The observed values decide comparability, never the configured alias, because the observed model can differ
  ([§11](#11-harness-evidence));
- the role profile that the deriver, judge and author sessions use;
- the harness, runner and CAT versions;
- the loader configuration and the prompt sources observed loading, including links, resolved targets and staged
  identities;
- the identities and versions of directly invoked host tools, when CAT can observe them;
- external or MCP source identities, with captured responses when available.

CAT does not claim deterministic replay and does not build closures of binary dependencies. Unobservable conditions are
reported as limitations.

A certification is **current** only while CAT knows of no material change. CAT makes it **historical**, without
deleting or rewriting it, when it observes a change to any of these:

- behavioural prompt content;
- the contract;
- the judge;
- the runner model or reasoning profile;
- a provider, harness, source or host tool capable of affecting outcomes.

A runner or CAT version change counts as a harness change, because the runner ships with CAT and a runner change
has altered trial outcomes (H17).

If a material condition changes between trials, CAT pauses as `MEASUREMENT_CHANGED` before the next model call. To
continue, the user restores the condition, starts a new measurement identity, or cancels. A change to the loader
configuration cannot start a new measurement identity within the campaign: the user either restores it or keeps it and
starts a new campaign.

A run whose observed conditions differ from its trial's measurement identity yields no outcome under that identity. CAT
keeps its record as an observation under the changed conditions. It is neither a compliance outcome nor an
infrastructure failure, so it does not count toward the infrastructure-failure limit ([§3.2](#32-logical-trials)). If
the user restores the condition, the same logical trial runs again with the same instance. Whether a run is excluded
depends only on its conditions, never on its outcome, so the exclusion cannot favour the candidate.

Starting a new measurement identity during a certification attempt ends that attempt
`not certified: testing environment changed`. The attempt can never resume, its `α_i` and `β_i` stay spent, and
the next attempt takes the next certification index. The working candidate returns to development under the new
identity, and the incumbent is measured again as a new baseline ([§2.5](#25-incumbent-baseline)). Outcomes under the
old and new identities cannot be combined, and counting only the new outcomes under the same `α_i` would let the
attempt test the same claim twice. Restoring the condition instead lets the attempt continue.

Candidate and incumbent observations are **comparable** only when every required condition other than snapshot identity
and time is exactly equal. Missing data makes comparability `INDETERMINATE` and the comparison unavailable. CAT names
each differing condition instead of reducing the check to one opaque hash.

## 6. Compliance contract

### 6.1 Derivation and confirmation

A deriver session inspects the prompt system and proposes the complete contract. Loading follows production behaviour:
CAT observes loading where the harness exposes it (premise P9), and otherwise derives it from the harness's documented
loading rules and CAT's rule loader, recording that limit. Both harnesses expose hook runs, and Claude exposes its skill
and command inventory; no harness names the instruction files it loaded, so those are derived (H21). Aggregate counts never stand in for the items themselves. The
proposal lists:

- every requirement and its source, including files reached through links and readable files outside the project.
  The list always includes the core-task requirement, with CAT as its source, and every test checks it. Each
  requirement is marked deterministic, with its check and controls, or semantic ([§6.4](#64-judge));
- every category and why it needs a separate claim, with the risk rank used for allocation;
- the trial profiles: the harness's profile inventory and its source, each prompt file that binds a model or effort,
  and each category's profile set and cell count ([§6.6](#66-trial-profiles));
- every test definition, its applicability, the complete prewritten turn sequence for multi-turn tests, its time limit,
  and any material interaction it cannot cover;
- the expected compliant behaviour and the judge's rubric and decision rule;
- every variation dimension and its probability, plus ignored paths needed as trial fixtures;
- the statistical procedure: the joint budget, bets, caps for the first attempts, the readiness gate, the review
  checkpoint and the baseline targets;
- the symbolic-link report ([§4.5](#45-symbolic-link-warning)), the authentication and volume warning
  ([§3.5](#35-volume-safeguards)), the minimum session count, and known limitations.

The user gives feedback, and CAT regenerates and presents the complete proposal again. Only a confirmed proposal governs
trials, and nothing runs before confirmation.

CAT, not the deriver, does three things:

- adds the core-task requirement to every test;
- computes the profile sets;
- runs each deterministic check against its controls.

A check that accepts its known-bad control or rejects its known-good control is discarded. Its requirement is then
judged semantically, and the proposal says why.

### 6.2 Categories and distribution

CAT creates a separate category whenever averaging could let common or easy cases hide a weak area. A category groups
uses that need their own claim; changing an incidental value creates another instance, not another category. Every
category must pass independently.

The proposed distribution is an explicitly labelled balanced benchmark: equal weights across the test definitions in a
category, and explicit, coverage-oriented variation probabilities. These are testing assumptions, not usage frequencies.
The report says that actual future usage frequencies are unknown. Confirmation freezes the distribution, and CAT never
changes weights after seeing outcomes. Allocation changes testing effort only.

### 6.3 Generators

Before any output counts as evidence, CAT validates that a generator:

- emits only instances the test definition permits, and preserves the expected result;
- samples each variation with its frozen probability, and records the concrete instance;
- draws independently of candidate identity and outcomes, from separate durable development and formal streams.

Formal draws for an attempt begin only after its candidate and procedure are frozen. Targeted diagnostics, meaning
reruns, promotion runs and regression runs, are marked before execution and never enter representative or formal counts.
Repeated draws of the same concrete instance are legitimate and are never rejected. A material obligation that needs
response-contingent follow-up turns is reported as a coverage gap and leads to `certification unavailable: unsupported
test interaction`. A generator or judge validation failure is an infrastructure failure, not evidence.

### 6.4 Judge

A trial's pass label comes from one frozen decision procedure in two parts.

**Deterministic checks.** Code decides every requirement that is mechanical: an exact or forbidden text, a pattern, a
count, a file's existence or content, a command's exit status, or whether a tool was used. The check kinds are a closed
set that CAT implements; the deriver chooses a kind and its parameters and never writes code. A check belongs to one
test and one requirement, and its parameters may refer to the test's variation values. Each check carries a
known-good and a known-bad control: evidence that it must accept and evidence that it must reject. CAT validates both
controls before confirmation ([§6.1](#61-derivation-and-confirmation)) and again whenever it loads the contract. A check
decides only what its kind can observe. Whether a reply is correct, relevant, complete or appropriate stays semantic.

**Judge.** The contract names one frozen judge profile: the role profile ([§3.1](#31-nested-sessions)), prompt, rubric,
decision rule and result schema. The judge decides only the semantic requirements from text, file effects and tool
effects. It sees the deterministic results as evidence but cannot override them.

A trial passes when every deterministic check passes and the judge passes every semantic requirement. CAT runs the
checks first. When one fails, or when no semantic requirement remains, CAT records the outcome without a judge session.
The core-task requirement is semantic unless a test's expected result is itself mechanical, such as an exact file
content, so most trials still need a judge. The split mainly removes judge errors on mechanical requirements; it saves
judge sessions only for mechanical tests and failed checks.

A trial that fails a deterministic check records which check failed and its observed value. The author receives that
record in place of a judge rationale.

### 6.5 Certifiable scope

- **Certifiable outputs:** text, plus observable file and tool effects. A material requirement for image, audio or
  video output makes certification unavailable.
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

- **Codex:** each model that `codex debug models` lists with visibility `list`, with its supported reasoning levels.
- **Claude:** the CLI has no model catalog. CAT ships the inventory as a plugin resource: the documented model aliases
  and the efforts each one accepts, with the documentation it came from (premise P12).

The proposal names the inventory's source. Confirmation freezes the profile set, so a later model release does not
change a running campaign.

**Bindings.** A prompt file binds a profile only through a field that its harness applies whenever it runs the file:

- `model` and `effort` in Claude skill and subagent frontmatter (`inherit` binds nothing);
- `model` and `model_reasoning_effort` in a Codex custom agent's TOML.

`CLAUDE.md`, `AGENTS.md`, rules and Codex skills cannot bind a profile. A file that binds only its model or only its
effort fixes that dimension and leaves the other open. A binding to a value outside the inventory is used as written,
and the proposal says so.

**Profile set.** CAT computes each category's profile set from the requirements its tests check, excluding the
core-task requirement, which comes from CAT and not from a file:

- When every file that supplies those requirements binds the same complete profile, the category has one cell under
  that profile.
- Otherwise, the category has one cell for each inventory profile that matches every dimension those files all bind to
  the same value.

A trial session starts under its cell's profile. The harness then applies any binding of the files it runs.

**Tiering in development (user ruling, 2026-10-01).** Development tests each candidate first in a few cells and widens
to every cell only once those are ready. Readiness, baseline and certification still cover every cell, so tiering
changes only the cost of development, never the claim:

- **Screen.** When development starts, every cell receives 3 representative trials of the first working candidate.
- **Active cells.** A cell is active when it binds its category's only profile; when it is the lowest or the highest
  effort that its model has in the profile set; or when any candidate of the campaign has failed in it. A cell stays
  active for the rest of the campaign.
- **Widening.** Once every active cell of the current candidate passes the readiness gate's count conditions
  ([§2.7](#27-development-readiness-and-edit-or-continue)), every cell becomes eligible for representative trials.
  The usual allocation then fills the rest, and empty cells count as 0. A failure in a newly sampled cell makes it
  active and is handled like any other development failure.
- **Edits.** A new working candidate starts again in the active cells only, with counts at zero as usual.

With the Codex inventory of 2026-10-01, the active set starts at 16 of 44 cells per unbound category (the lowest and
highest effort of each of 8 models), plus every cell where the screen found a failure.

**User control.** The proposal shows each category's cells and the minimum session count they imply. In revision
feedback, the user may restrict the profile set to a subset of the inventory. The claim then names only the confirmed
profiles.

**Cost.** Every cell needs its own readiness gate, baseline and certification trials. With the Codex inventory of
2026-10-01 (8 listed models, 44 profiles), a category whose files bind nothing has 44 cells. Attempt 1 then needs at
least 44 × 93 = 4,092 all-pass trials for that category alone, after 4,400 readiness trials and 2,200 baseline trials.
A category whose file binds one profile needs 93, 100 and 50.

## 7. Campaign lifecycle

- **Campaign:** one explicitly started effort, with its own joint budget and a starting harness that is fixed for its
  lifetime, including across restarts and target renames. A
  canonical project root and target have at most one resumable campaign. The other harness can show that campaign and
  its starting harness, but cannot advance, dispatch, allocate allowance or start a concurrent campaign, and its refusal
  changes no state.
- **Lineage:** the evidence history under one confirmed contract. A confirmed material revision starts a new lineage.
  Earlier evidence stays historical, the certification index continues, and the incumbent is measured
  again under the new contract as a new baseline ([§2.5](#25-incumbent-baseline)).
- **Revision during formal testing:** a revision starts only at a safe checkpoint. Either the attempt has ended, or the
  user stopped it, in which case it ends `not certified: stopped for contract revision` and can never resume. Every
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
  - infrastructure failures from compliance outcomes;
  - campaign, lineage, candidate, attempt and measurement identities, the certification index and the incumbent
    baseline number;
  - the starting harness;
  - pending user decisions and recorded overrides;
  - unowned-change and measurement-change pauses;
  - current from historical results.

## 8. Skill and command interface

The `prompt-optimizer` skill exists in both harnesses as thin wrappers around one shared body. It applies when the user
wants a prompt file or prompt system improved until its compliance is certified. The host agent:

1. starts or resumes the campaign for the named target;
2. presents the proposal, warnings and minimum session count, and relays feedback until the user confirms;
3. calls `run` while the status is `running`, showing the progress summary after each slice;
4. presents each `awaiting-user` decision's concrete choices and passes the selected choice back;
5. reports the terminal outcome with its four outputs, applicability and limitations.

The harness-specific `prompt-optimizer` launcher provides `start`, `run`, `status`, `confirm`, `revise`, `choose` and
`cancel`. Each prints one JSON status object to standard output, holding the status, campaign identity, a summary for
the user and any choices. The skill never interprets statistics itself.

**Retirement of the existing skill:** the observe-diagnose-retest body (`skills/prompt-optimizer/first-use.md`) and its
wrapper text are replaced. `failure-regression-promotion.md` currently invokes `cat:prompt-optimizer` for every
qualifying failure. Under this design it instead applies its own correction and regression procedure, and it may
recommend a campaign when the user wants certified compliance ([§13](#13-decision-records), D13). `default-rules.md`
routing and the test resources that mention the old skill are updated to match.

## 9. Architecture

The harness-neutral core lives in `client/common/cli` under `io.github.cowwoc.cat.common.optimizer`. Harness adapters
and launchers live in `client/claude/cli` and `client/codex/cli`.

| Package | Responsibility | Kind |
|---|---|---|
| `optimizer.statistics` | Betting tests and exact decisions, cap calibration, confidence bounds, Wilson and Clopper–Pearson bounds | pure |
| `optimizer.contract` | Contract model, proposal schema, generator validation, seeded draw streams, judge profile, deterministic check kinds and their control validation, trial profiles, bindings and profile sets | pure |
| `optimizer.campaign` | Immutable campaign model and its transitions. Readiness gate, edit-or-continue, author-manifest validation, promotion rule, minimality reduction steps, decision table, and the `Scheduler` that makes allocation and concurrency decisions | pure |
| `optimizer.inventory` | Inventory model, edit authority, delivery conflict planning; one scanner reads the file system without following links | pure, plus one I/O adapter |
| `optimizer.store` | Campaign snapshot and trial records, written atomically | I/O |
| `optimizer.workspace` | Fork and optimization worktree, local exclude, staged sources with link dereferencing and exclusions, authoring copies and delta capture, the symbolic-link report | I/O |
| `optimizer.execution` | The slice driver: reconciliation, admission, in-flight tracking, ordered adoption, interrupts, throttling | orchestration over ports |
| `optimizer.delivery` | Journaled delivery | I/O |
| `optimizer.report` | Status object, four outputs, minimum remaining trials | pure |
| `optimizer.cli` | Command parsing and wiring from `main` | edge |

The core owns these ports; adapters implement them at the edges:

- **`SessionPort`** starts a nested session for a role ([§3.1](#31-nested-sessions)) under a trial or role profile,
  from a source directory and its inputs. The returned handle can await completion, delivering the transcript, project effects, observed model and
  token usage, and can interrupt the session. It also reports rate-limit observations. The Claude and Codex
  implementations wrap the existing runners.
- **`WorkspacePort`** stages sources, scans inventories and captures author deltas, so the driver can be tested without
  a file system.
- **`CampaignStore`** is the file-system boundary for campaign persistence. Production has one implementation. Driver
  tests use an in-memory implementation from test sources, and both implementations pass the same contract tests.
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
  - Tests build any campaign state in memory through public builders. Category state is `(S, F)`.
  - A test that needs "a certification attempt with 250 passes and 2 failures" constructs it directly. It never runs or
    replays 252 trials and never parses a trial log.
- **T3 — Expensive operations verified once:**
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
| H3 | A nested Claude launched with the `haiku` alias reported `gpt-5.6-luna` in its harness assistant events, and `perTurnEffort` was null. | The observed model must come from native events, not configuration. |
| H4 | The runner copies the project into its run directory, skips `.git`, creates a fresh Git baseline, and rejects any project containing a symbolic link. | `HarnessRunManager.copyProject`. |
| H5 | Nested processes cannot reach the outer Codex daemon socket. | Earlier CAT probe. |
| H6 | In the sandboxed Claude Bash tool, the command ran in its own PID namespace. When the command ended normally, all three children stopped within one second. On ESC, the command and every child stopped at once, with no catchable signal recorded. | 2026-09-30 probe, sandboxed host only (premise P2). |
| H7 | A Claude tool command that exceeded its timeout was moved to the background instead of being killed. | 2026-09-30 probe. |
| H8 | After a Codex turn interrupt, the command and every child kept running until they finished on their own (73 seconds). The thread's rollout file recorded `turn_aborted`, and the command's environment exposed `CODEX_THREAD_ID` and `CODEX_HOME`. | 2026-09-30 probe, nested Codex 0.159.1 (premise P1 covers the host case). |
| H9 | Claude tool commands receive `CLAUDE_CODE_SESSION_ID` and `CLAUDE_CONFIG_DIR`. After an interrupt, the nested transcript contained `[Request interrupted by user for tool use]`. | 2026-09-30 probe (premise P3 covers the host case). |
| H10 | Interrupting a nested Claude through the runner ended the harness process with exit status 1. The bridge then reported "Native harness ended before completing the requested turn" rather than `interrupted`. | 2026-09-30 probe. The Claude adapter must classify an interrupt it requested as an interruption, never as a compliance outcome. |
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
| P7 | In trials, child completion, recursive and parallel children, child worktree merges and cancellation are observable enough to reconcile. | Record observation limits; an affected trial gets no invented label. | Partly passed (H17–H19). Fallback applied to Codex child worktrees; §3.2 and §3.8 updated. |
| P8 | Runner harness processes are descendants of the driver, so killing the driver leaves no live harness session. | Reconciliation terminates orphans recorded through `HarnessRunManager.recordHarnessProcess`. | Passed for Claude, failed for Codex (H20). Fallback applied; §3.8 updated. |
| P9 | CAT can observe which prompt sources a nested session loaded. | Derive loading from documented rules, and record the limit. | Failed for instruction files (H21). Fallback applied; §5 updated. |
| P10 | Codex lets a command run for the slice budget, and reports what happens when a command exceeds its limit. | Shorten Codex slices to fit the observed limit. | Passed for the 8-minute slice; no limit was reached in 662 seconds, so the over-limit behaviour is unobserved (H22). |
| P11 | Judge sessions read the evidence read-only and return schema-valid results reliably in both harnesses. | Measure the invalid-output rate. Retries of the same logical trial cover the rate if it is low; otherwise the judge profile cannot be confirmed. | Passed with the §3.1 extraction rule (H23). |
| P12 | Claude Code's documentation names the model aliases a user can select and the reasoning efforts each one accepts. | Ship the aliases with every documented effort level, and record as a limitation that an unsupported combination fails its first trial as an infrastructure failure. | Open until E1 runs a Claude trial per alias. Claude Code 2.1.287: `claude --help` names `opus`, `sonnet` and `fable` and the efforts low to max; `haiku` is named only in the changelog, and the local model-catalog cache (`~/.claude/cache/model-catalog/`, undocumented) gives it no effort options. CAT ships these as its inventory resource and does not read the cache at run time. |
| P13 | Each runner can start a session under a requested model and effort, and the harness events report the model and effort that ran. | A runner that cannot select a profile tests only its default profile, and the proposal says so. | Open. Codex `codex debug models` lists each model's supported reasoning levels (0.159.1, 2026-10-01). |
| P14 | Reasoning effort changes compliance with the same prompt on the same model, so effort must stay a dimension of the profile set (§6.6). | If no difference is detected, effort stays in the profile set: a test that fails to reject equal rates does not show that they are equal. Only an equivalence test with a stated margin could support testing fewer efforts, and only for the kind of rule tested. | Inconclusive (H26): 0% and 100% at every effort, so the fallback applies and effort stays in the profile set. A rule with intermediate compliance is needed to test it. |

## 13. Decision records

These decisions go beyond what the product requirements fix. Each was chosen for its long-term cost and benefit.

| ID | Decision | Alternatives and why not | Cost of the chosen option |
|---|---|---|---|
| D1 | Split the joint 5% into 0.04 for certification, 0.005 for improvement claims and 0.005 for the incumbent's bounds. Divide the improvement and incumbent parts by the category count. | Giving certification only 0.025 raises the first cap from 262 to 311 and the all-pass minimum from 93 to 103. The incumbent's part buys little: halving it from 0.01 to 0.005 adds at most one failure to a deficiency proof. Letting each category's proven-better decision use the full `α_i` would make the joint error grow with the category count. An unsplit 5% gives no joint guarantee. | Compared with an unsplit 5%, the first certification attempt's all-pass minimum rises from 88 to 93 and its cap from 255 to 262. Proving improvement over `UI = 0.90` with 3 categories takes 79 all-pass trials, against 45 if each category used the full `α_1 = 2%`. |
| D2 | Bet on a mixture weighted toward 99% (`0.1/0.2/0.5/0.2` on 97/98/99/100%). | At `α = 2%`: a single 99% bet has cap 255, an all-pass minimum of 95, and probability 0.775 of certifying a 98% candidate within 600 trials. An equal-weight mixture has cap 301, minimum 95 and probability 0.868. | Cap 262, all-pass minimum 93, and probability 0.854. That is close to the single bet's cap with most of the equal mixture's robustness. |
| D3 | Keep the readiness gate of 100 trials at 99% and two batches at 98%. | A 10-consecutive-pass gate commits a true 97% candidate about 74% of the time, which then certifies only about 17% of the time (§2.7). Every failed commit permanently spends allowance and raises later caps. | At least 100 development trials per category, which also feed concurrency and diagnosis. |
| D4 | Edit-or-continue with 3 reproduction reruns. | Editing on every failure resets counts on noise: a 99% candidate fails at least once in 100 trials 63% of the time. Never editing mid-batch wastes trials on reproducible defects. | Up to 3 targeted trials per failure. A defect that fails half the time is caught with probability 0.875, and a random 1% failure is treated as a defect only about 3% of the time. |
| D5 | Foreground driver in time-bounded slices. | A detached worker is killed by the Claude sandbox (H6). One long command hits the tool timeout and hides progress. | A snapshot reload per slice, and trials cut off at slice ends, limited by the admission cutoff. |
| D6 | Compact snapshot plus one record file per trial. | Event-log replay costs more as trials accumulate. SQLite adds a native dependency to the runtime image and is not needed beyond atomic renames. | CAT keeps the snapshot consistent with the records through write-ahead ordering and reconciliation. |
| D7 | Keep the cross-process capacity bound, injected and per harness. | Removing it leaves sessions unbounded across processes. A static global causes test contention and couples the harnesses. | Constructor changes in both runner CLIs and in the optimizer wiring. |
| D8 | Concurrency from a risk ceiling (at most ½ expected failure in flight) plus slow start. | A fixed level wastes trials after failures or runs slowly. Slow start alone ignores accumulated evidence. A predictive cost model has no calibrated predictor. | With no failures, reaching 8 in flight takes 47 adopted outcomes. |
| D9 | Adopt outcomes in assignment order. | Completion order biases decisions when duration correlates with the outcome. | A slow trial delays later decisions by up to its duration. |
| D10 | Replace links with copies of their targets in staged sources. | Teaching the runner to copy links breaks relative external links, and absolute links would let trials write live targets. | Trials cannot observe link metadata. This is warned before confirmation and recorded as a limitation. |
| D11 | Report the exact minimum remaining trials, plus observed tokens per session and the candidate's prompt size and median trial input tokens against the incumbent's. | Token totals, time estimates and completion probabilities would be forecasts with no calibrated predictor. | Users estimate cost from the reported facts themselves. |
| D12 | Nested sessions for the deriver, authors and judges. | The host agent cannot work while a slice runs, differs per harness, and cannot be tested with fakes. | Extra sessions, which count toward capacity and the volume warning. |
| D13 | When an agent misapplies user-owned guidance, `failure-regression-promotion` corrects the guidance and adds its regression test itself instead of invoking the optimizer, and may suggest a campaign. | Invoking the optimizer for every such failure would halt the user's task: a campaign needs a clean worktree, a confirmed contract and hundreds of trials. Asking the user each time interrupts every incident with the same question. | A correction made this way is regression-tested but not certified until the user starts a campaign. |
| D14 | The incumbent's baseline is 50 trials per category, plus 50 more where it is below 95% but unproven. | Always running 100 costs about 50 extra trials per category. Using only the incumbent's development trials gives no deficiency proof when development replaces it early. | Up to 100 trials per category, run on spare capacity. |
| D15 | At most one fresh judge session per trial. | Batched judging couples outcomes through judge memory (§2.6). | One extra session per trial that has semantic requirements and passed its deterministic checks. |
| D16 | Rerun infrastructure failures as the same logical trial, up to 3 times, then pause. | Skipping a trial could hide failures that cause infrastructure errors. Unbounded retries hide outages. | An outage pauses the campaign until the user resumes it. |
| D17 | Store campaigns under `.cat/work/optimizer/`, adding a local exclude when needed. | Storage under `HOME` loses project-local discovery and complicates worktree handling. | One line in the clone's `info/exclude`, which CAT reports. |
| D18 | Let the user keep the best candidate so far that is proven better than the incumbent in at least one category, proven worse in none, and checked for regression in every other category, using one certification attempt's evidence. | Offering only the latest proven candidate would discard an earlier improvement whenever later candidates fail. Testing development results after selecting the candidate overstates it. A separate improvement attempt, committed without the readiness gate, could prove a candidate that never reaches readiness, but it spends allowance outside certification and at least 100 new trials per category. Giving every promoted candidate an improvement share at promotion would let its development trials count, but proofs grow costlier as promotions accumulate: with 3 categories and an incumbent at 90%, a 97% candidate needs 469 trials for a 90% chance of proof at the 10th promotion and 631 at the 100th. Requiring improvement in every deficient category would discard real gains in other categories. Accepting a category with only a few outcomes would call a candidate improved without checking it for regression. Certifying on improvement alone would weaken what `certified` means. | No extra trials, and no allowance spent outside certification. A candidate that never passes the readiness gate cannot be proven better; the user can still accept it as `not certified`. A category that is not proven better may hide a small regression within its interval, and the report says so. |
| D19 | Spend each budget part by the series `x / (n·(n + 1))`, so early attempts receive most of it. | A flatter series, `0.4 / ((i + 9)·(i + 10))`, lowers attempt 100's cap from 712 to 619, but raises attempt 1's cap from 262 to 382 and its all-pass minimum from 93 to 130. An equal split over a fixed maximum of 100 attempts gives every attempt a cap of 478 and an all-pass minimum of 177, and leaves nothing for attempt 101. The readiness gate admits only candidates with strong development results, so a campaign is expected to commit few certification attempts; this expectation is unmeasured. | Late attempts cost more: attempt 100 has an all-pass minimum of 271 and a cap of 712. |
| D20 | A new measurement identity ends a running certification attempt, and qualifying attempts are ranked only within their own identity. | Counting the old and new outcomes together supports only the claim that some condition exceeds 95%, which nobody needs. Counting only the new outcomes under the same `α_i` tests the same claim twice and can double its error. Pausing until the condition is restored blocks a user who cannot restore it, so it remains one choice rather than the rule. Dropping earlier-identity attempts would discard valid claims whose allowance is already spent. Re-certifying an earlier candidate under the new identity needs a way to make it the working candidate again, plus a readiness gate and a full attempt; the user can still optimize further. | The stopped attempt's allowance is lost. The user may have to choose between candidates whose results cannot be compared. |
| D21 | Treat a runner or CAT version change as a material measurement change. | Recording versions only for information would keep certifications current across runner changes, but a runner change has altered trial outcomes (H17), and the runner ships with CAT. | Every CAT upgrade during a campaign pauses it as `MEASUREMENT_CHANGED`, and certifications made under an earlier version become historical. |
| D22 | Give every category one cell per trial profile that can run its prompt files: the profile a file binds, otherwise every profile in the harness's inventory (user ruling, 2026-10-01). | Certifying one default profile says nothing about the others, and compliance does not grow reliably with model size or reasoning. Certifying a mixture of profiles costs about one profile's trials, but a weak profile can hide behind the average. Borrowing strength across profiles with item-response models estimates averages and is not anytime-valid. | Trial counts multiply by the number of profiles for every category whose files bind none. The proposal shows this cost, and the user can restrict the profile set. |
| D23 | Decide mechanical requirements with validated deterministic checks, and use the judge only for the semantic remainder (user ruling, 2026-10-01). This replaces the earlier rule that objective assertions could only inform the judge. | A judge that decides everything adds its own errors to mechanical requirements. Checks written as code by the deriver could not be validated or contained. | A closed set of check kinds to implement and maintain. A requirement that no kind fits stays semantic. |
| D24 | Add the core-task requirement to every contract (user ruling, 2026-10-01). | Contracts derived only from the prompt's text can certify a candidate that obeys every rule but does the user's task worse. | The judge also decides task correctness. That can expose failures that the prompt's own rules would not. |
| D25 | Require authors to classify failures, make the smallest owning-boundary correction, generalize against analogous and dissimilar cases, and cite the failing instances for each hunk. CAT checks the citations (user ruling, 2026-10-01). | Unconstrained authors add emphasis and special cases that pass the triggering instances and grow the prompt. Judging the quality of each edit with a model would add an unvalidated decision. | Authors write a manifest. CAT can check only that the citations exist, not that they are apt. |
| D26 | Reduce each promoted attempt by delta debugging before it becomes the working candidate (user ruling, 2026-10-01). | Keeping every added hunk lets prompts grow with unneeded text. Removing hunks after representative sampling would discard counts. | Extra targeted runs per promotion, growing at most with the square of the hunk count. A needed hunk can be removed by chance; representative trials then catch the weaker candidate. |
| D27 | Develop each candidate in its active cells first and widen to every cell once they are ready (user ruling, 2026-10-01). | Sampling every cell during development spends most trials on cells whose counts the next edit resets. Readiness and certification still cover every cell, so the claim is unchanged; the risk is finding a failure in a rarely sampled cell late, which widening catches before commit. |

## 14. Acceptance evidence

The feature is complete only when every gate below passes on the final tree, together with the unrestricted project
build and the complete requirement inventory in `plan.md`. Unit evidence can prove a calculation, but it cannot replace
a named end-to-end boundary.

| Goal | Required evidence |
|---|---|
| G1 | **End-to-end in both harnesses:** a dirty base is refused before any fork. A deliberately deficient prompt system is improved in the optimization worktree; unselected attempts never touch the base; a certified, non-regressing candidate is delivered as a Git diff. **Delivery:** disjoint post-fork base edits survive; same-file changes stop for direction; a fault between writes resumes without duplicating or overwriting. **Uncertified outcomes:** plateau and others leave the base unchanged unless the user explicitly chooses. An improvement proven by an inconclusive attempt is offered and, when kept, delivered as `improved, not certified`; when several attempts qualify, the best candidate so far is offered, even after later candidates fail. Driver and delivery tests prove these, because an uncertified certification attempt costs hundreds of trials end to end. An accepted uncertified candidate is labelled `not certified`. **Authoring:** an attempt whose manifest omits a changed hunk or cites an unknown instance is rejected; the minimality pass removes a hunk that the promotion runs do not need and keeps one they do (driver tests with a fake `SessionPort`). |
| G2 | **Proposal content:** every fixture requirement and source, including linked and external files, the symbolic-link report, and the volume warning. **Confirmation:** nothing runs before confirmation; feedback produces a complete new proposal. **Loading:** conditional and later-loaded prompt files are covered, with the loader configuration fixed from the fork through delivery. **Refusals:** forbidden edits are rejected (external structural edits, link and mode changes, loader configuration). **Unavailable claims:** remote governing instructions, image/audio/video outputs and response-contingent obligations make the claim unavailable instead of being omitted. **Contract completeness:** every test checks the core-task requirement; a deterministic check that fails a control is discarded and its requirement judged semantically. **Profiles:** a file binding a complete profile yields one cell; an unbound file yields one cell per inventory profile; a partial binding fixes only its dimension; a user restriction narrows the set. |
| G3 | **Statistical fixtures:** the joint budget sequence; exact decisions at the boundary, including equality not certifying; the [§2.4](#24-caps) caps and all-pass minimums; deficiency thresholds; improvement following from certification plus deficiency; and the proven-better and proven-worse decisions at `β_i / K` against frozen incumbent bounds. **Budget persistence:** restarts, revisions and resumed cancellations never reset the budget, while a new campaign does. **Report wording:** the claim names the frozen judge, the benchmark and the conditions. |
| G4 | **Assignment and order:** formal draws only after freeze; adoption in assignment order regardless of completion order; post-decision outcomes never change a decision. **Retries:** they cannot discard outcomes. **Evidence separation:** targeted runs and development outcomes never enter formal counts. **Staging:** it excludes optimizer state. **Dependence:** detected dependence yields `invalid statistical assumptions`. |
| G5 | **Outputs:** four outputs in both phases with unavailable values explained. **Applicability:** each material change makes a result historical; a missing comparability field makes the comparison `INDETERMINATE`; a new measurement identity ends a running attempt as `not certified: testing environment changed`, and a qualifying attempt under an earlier identity is offered separately, never ranked against current ones. **Remaining work:** minimum remaining trials match hand-computed values; no token total or time estimate is shown. **Size:** the status shows the candidate's prompt size and median trial input tokens against the incumbent's. |
| G6 | **End-to-end in both harnesses:** ESC during a slice, then `run`, retains completed outcomes and assignments, reruns in-flight logical trials, and keeps the certification index. Codex ESC stops admissions within 15 seconds. **Unit fault injection:** recovery from every persistence step. **Campaign binding:** the other harness cannot advance the campaign. |
| G7 | **End-to-end in both harnesses:** trials, judges, authors and the deriver run through the existing runners. A trial uses a host tool, and a text-output case and a file-effect case are each judged semantically. A requirement with a deterministic check is decided without the judge, and a test with only deterministic requirements starts no judge session. Trials run under the profile of their cell, as the harness events show. A trial with a read-only service credential and one with a disposable fixture are both eligible, and a known persistent write is refused before dispatch. **Capacity:** the shared capacity bounds sessions across two concurrent CAT processes. |
| G8 | **Unit:** allocation picks the riskiest category first; concurrency follows [§3.3](#33-allocation-and-concurrency) exactly, including the reset after a failure, the risk ceiling, the category limit and the ceilings. **Throttling:** it responds to utilization and rejections. **Volume warning:** the authentication warning appears for a subscription login. |
| G9 | The optimizer and capacity unit tests pass under parallel methods with `HOME` and `user.home` pointing at a nonexistent directory. A source audit finds no static mutable state, no `Thread.sleep` on tested paths, and no mutable test-class fields. Calibration and each Git operation have exactly one owning test, and no Java test starts a runner, a harness process or a model. |
| G10 | Both harness artifacts ship the new skill and the `prompt-optimizer` launcher. No reference to the old procedure remains, `failure-regression-promotion.md` follows D13, and rule tests pass. |
