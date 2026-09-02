# CAT Frontend Plan

## Status and scope

This document defines CAT's local client, client-owned prompt tuning and certification, and release-distribution
behavior. It does not claim that these capabilities are implemented. Prompt tuning, evaluation, certification,
evidence, and project history run inside the customer's harness. `backend.md` owns the public website, authenticated
account experience, authentication, commercial-license state, Paddle billing, payment history, renewal notifications,
explicit feedback submission, Usage analytics ingestion, and access to CAT's commercial release repository.

## Implementation time tracking

The implementer maintains this ledger while executing the detailed implementation plan. Before starting a component, it
records the estimate, recorded-at time, and status. When the component's named terminal verification passes, it records
the completion time, elapsed time, verification evidence, and `actual − estimate`. It does not invent start times or
actual durations for work completed before this ledger existed; those entries remain `unmeasured`.

After every component transition, the implementer updates the remaining-goal estimate from the unfinished component
estimates and records the update time. A component is not complete merely because its source compiles: its row may say
`complete` only after the terminal acceptance evidence named for that component passes.

| Component | Status | Estimate | Recorded at | Actual duration | Difference | Completion evidence |
| --- | --- | --- | --- | --- | --- | --- |
| Wave 0 — shared contract | complete; prior work is unmeasured | 1–2 engineering days | 2026-09-02 | unmeasured | unmeasured | 2026-09-02 Maven gate passed: 73 common, 8 Claude runner, and 9 distribution tests; the optimizer and formal-certification terminology audit found no remaining `scenario` terms |
| Wave 1 — Codex vertical slice | complete; prior work is unmeasured | 2–4 engineering days | 2026-09-02 | unmeasured | unmeasured | 2026-09-02 Wave 1 Maven gate passed 81 tests; the packaged Bats runner suite passed 4 tests and skipped its explicitly opt-in live-Codex test; `CertificationProcessExecutorTest` passes 8 focused tests, including the complete typed six-operation executor workflow, real sequential certification, a retained static formal trial, recovery, and complete formal-state before/after evidence |
| Wave 2 — extraction and evaluation | complete; prior work is unmeasured | 3–5 engineering days | 2026-09-02 | unmeasured | unmeasured | 2026-09-02 combined extraction, prompt-test, and evaluator Maven gate passed 84 tests; Wave 1's 81-test typed-fixture gate and 4-test packaged Bats boundary gate also passed |
| Wave 3 — selection-aware optimization | complete; prior work is unmeasured | 3–5 engineering days | 2026-09-02 | unmeasured | unmeasured | 2026-09-02 combined evaluation, optimization, development, certification, simulation, and prompt-system Maven gate passed 316 tests |
| Wave 4 — cost, history, and stale results | complete; prior work is unmeasured | 3–5 engineering days | 2026-09-02 | unmeasured | unmeasured | 2026-09-02 combined cost, price, provider-usage, certificate, history, stale-result, and environment Maven gate passed 30 tests |
| Wave 5 — Work client behavior and Claude parity | complete; prior work is unmeasured | 3–5 engineering days | 2026-09-02 | unmeasured | unmeasured | 2026-09-02 commercial/common Maven gate passed 23 tests; Claude Maven gate passed 33 tests; the packaged Codex and Claude terminal outcomes passed the 4-test Bats suite |
| Wave 6 — releases and integrated launch gate | local implementation verified; production launch unrun | 2–3 engineering days | 2026-09-02 | in progress | not applicable | 2026-09-02 clean Maven verification passed after terminal-certification Bats enrollment and again after the first-release publisher repair. The release publisher now creates `main` for an empty first-release repository and refuses a non-empty repository without `main`; its focused Maven and ShellCheck checks passed. The live Codex terminal fixture and both Codex work-execute fixtures passed. Claude retried its complete work-execute fixture ten times and two later terminal-certification retries received HTTP 503 after Codex passed. This provider outage leaves the live Claude terminal and work-execute evidence unrun. The work-execute gate recognizes HTTP 429/5xx, retains an outage marker, and records the gate as unrun with its Bats log. The source repository currently has no Actions secrets, so `CAT_RELEASE_TOKEN` must be configured before its Release workflow can publish either edition. A local launch-gate run passed entitlement, privacy, and prompt-system gates; lifecycle, platform, commercial-repository, Claude terminal, and live work-execute evidence remain unrun. Each launch-gate invocation creates a separate immutable attempt directory. |

Current remaining estimate: 1–2 calendar days; recorded 2026-09-02. The `1.0.2` cross-platform release workflow is
running with the configured `CAT_RELEASE_TOKEN`. A live Claude provider must still complete the required terminal
evidence. This is a planning estimate, not evidence that any listed component has passed its terminal gate.

## Target audience and product entry points

CAT serves three overlapping problem profiles. A person or team may fit more than one:

1. **Instruction-reliability users** maintain persistent coding-agent instructions and incur expensive failures when
   an agent overlooks, misinterprets, or inconsistently applies them.
2. **Style-convergence developers** spend substantial review and revision time making generated code match their
   design, architecture, testing, documentation, maintainability, and workflow preferences.
3. **Cognitive-debt developers** can no longer easily explain why generated code and accumulated agent-made decisions
   behave as they do. They want code, decisions, and explanations to remain understandable and traceable.

The broad problem audience is anyone who experiences one of these costs. The initial paying audience is professional
developers, consultants, active solo builders, and teams for whom the recurring cost justifies a commercial license.
Organizations that need shared policies, audit history, reproducibility, and certification are a later higher-value
audience.

CAT presents one product through three entry points:

- **Instruction Reliability** begins with a persistent instruction that an agent fails to follow.
- **Review Convergence** begins with an explicit preference, an explained review correction, or an accepted example.
- **Explainable Agent Code** begins with a code decision or agent explanation that is difficult to understand or
  verify.

These entry points use the same contract-extraction, optimization, evaluation, and certification pipeline. They do not
create separate products.

## Licensing and offers

CAT is proprietary software. Personal use is free. Use for an employer, client, business, product, service, or another
organization requires a paid commercial license. The root license is the legal authority; package metadata, installed
documentation, installation instructions, and client messages must not contradict it. `backend.md` applies the same
authority to the public website, checkout, and account experience.

CAT has exactly two launch offers:

- **Personal — free:** locally delivered CAT rules, skills, review workflows, and Git workflows for non-commercial use.
  Personal does not include prompt tuning or commercial-use rights.
- **Work — USD $15 per licensed person per month:** everything in Personal, prompt tuning, updates, and the right
  to use CAT for commercial work. There is no annual billing option, company minimum, or repository or project limit.

Work is the edition name. “Commercial license” and “commercial work” remain the legal terms that define
when the Work license is required.

Paddle sells the Work offer as `CAT commercial license`. One purchased license authorizes one named person. A
company may purchase and administer any number of licenses under one billing account. An automated service identity
that installs, enables, or uses CAT for commercial work consumes one license and has one accountable named owner. A
person's license covers up to three devices they control. Licenses are not concurrent pools: they may not be shared or
rotated among simultaneous users. Keeping CAT installed or enabled for commercial work requires a license even if the
person does not invoke a CAT command during that billing month. Repository count and command activity do not affect the
license quantity.

The maintained commercial-license wording is:

> A CAT commercial license authorizes one named person, or one named automated service identity, to install, enable, or
> use CAT for commercial work. Each person must have a current assigned CAT commercial license. One licensed user may
> use CAT on up to three devices they control. Licenses may not be shared or rotated among simultaneous users.

Prompt tuning, evaluation, and certification run locally under the Work license. CAT does not sell service
credits or charge separately for those operations. The customer remains responsible for any model-provider,
subscription, or API costs incurred by the local harness.

The launch offer includes account and billing support, AI-first technical support, and private `$cat:feedback`. It does
not promise bespoke prompt consulting, a human technical-response time, or a service-level agreement unless the selected
plan states otherwise.

## Release repositories

CAT publishes two separately entitled distributions:

- Personal releases: `https://github.com/catsforbots/cat/`
- Work releases: `https://github.com/catsforbots/cat-work/`

The personal repository is the supported source for personal releases. The private Work repository is the
supported source for Work releases. A customer may install a Work release only while their GitHub account
has access to the Work repository. Removing repository access prevents future fetches and updates; it cannot
recall copies that the customer obtained while access was valid.

### Release history and version selection

Each repository uses `main` as its current release channel. Publishing a release creates a new commit on `main` and an
immutable tag containing the exact release version. A user who selects `main` receives the newest published release. A
user who selects a version tag receives that exact release. Release branches and moving version tags are not supported.

The same version identifier denotes the same product release across the Personal and Work repositories, even
when their entitled capabilities differ. A release is not published until every supported target for that edition has
passed its clean-install checks.

### Release tree

Every release commit contains one directory for each supported target platform. A target platform is the closed tuple
of harness type, operating system, and hardware architecture. The first release supports the harness identifiers
`codex` for Codex and `claude` for Claude Code on Linux and macOS, each on `x86_64` and `arm64`. Native Windows is not
supported. A Windows user runs CAT inside WSL, which selects the matching Linux target for the WSL distribution and
architecture.

The release manifest defines the canonical directory name for every supported tuple. The installer detects the harness,
operating system, architecture, and WSL environment, then validates that exact tuple against the manifest instead of
constructing an unchecked path or selecting the nearest target. A detected native Windows environment is rejected with
instructions to install and run CAT inside WSL. The publisher must not place two incompatible packages in one target
directory and rely on runtime guessing.

The root may contain only the end-user files needed to identify, verify, license, and install the target directories.
Each target directory must be self-contained for its documented installation flow. A release may contain:

- plugin and marketplace manifests required by a supported harness;
- end-user skills, rules, prompts, launchers, and configuration schemas;
- compiled programs and runtime dependencies needed by the installed product;
- end-user documentation, license and third-party notices; and
- checksums, version metadata, and update metadata.

A release must not contain source material used to build the distribution, tests or fixtures, build or assembly tools,
continuous-integration configuration, build caches, logs, reports, credentials, or another file that an end user does
not need to install or run that release.

### Release workflow

The publisher performs these stages in order:

1. Build the Personal and Work distributions outside their release repositories.
2. Materialize each target directory from an explicit edition-and-target allowlist.
3. Reject an unlisted file, a broken link, a link that escapes its target directory, an embedded credential, a version
   mismatch, or a missing license or third-party notice.
4. Install every target from a clean environment using the staged tree and run its supported smoke test.
5. Create the release commit on `main`, tag that commit with the exact version, and verify that the tag resolves to the
   intended commit.
6. Repeat the installation check by fetching the published tag through the documented end-user path.
7. Hand the verified release coordinates, checksums, target manifest, and installation contract to the backend-owned
   website. The website publishes those instructions only after its own presentation and access checks pass.

`EditionArtifactMaterializer` rejects credential patterns in the staged end-user tree, preventing a distribution from
including an API key, access token, password, or private key. The workflow then builds the four native tuples on their
matching Linux or macOS runners. A Windows hosted runner runs the Linux x86_64 artifact inside `Ubuntu-24.04` WSL and
records a separate WSL receipt. The publishing job must collect all four native receipts and that WSL receipt before it
assembles the two edition trees or runs the frontend launch gate. A missing WSL distribution or a failed WSL artifact
check fails the workflow; it cannot be represented as native evidence.

A failed stage leaves the preceding release and installation instructions current. The publisher must not move a tag
or describe a failed release as available.

Release acceptance evidence consists of the allowlist report, tree manifest and checksums, clean-install results for
every declared target, the resolved commit and tag identifiers, and the post-publication installation results.

## Local-client behavior

The local client determines the current harness, operating system, hardware architecture, and WSL state when those
values can be observed reliably, validates the exact tuple against the release manifest, and asks the user only when a
required value cannot be determined. It never substitutes the closest target after a mismatch.

Local project configuration, prompt tests, certification contracts, optimization history, evaluation evidence, and
certificates remain on the customer's machine so they can be versioned with the source revision that uses them. Prompt
tuning, evaluation, and certification execute locally through the customer's harness and model-provider account. The
client sends no prompt, test, trace, or optimization content to CAT unless the user explicitly reviews and submits a
`$cat:feedback` report.

### License connectivity

The Work client's signed license lease has a 24-hour normal lifetime. The client attempts renewal at startup and
at least once every 24 hours while running. A successful renewal records the latest confirmed entitlement. If CAT is
unreachable, Work-only entry points remain available for at most seven calendar days after that confirmation.
After seven days without confirmation, the client rejects those entry points and explains how to reconnect or sign in.
A temporary CAT outage does not shorten an unexpired lease or grace period.

When an assignment is revoked or its paid term ends, the backend refuses another lease and returns the effective end
time. The client rejects Work-only entry points after the entitlement has ended and seven calendar days have
elapsed since its last confirmed entitlement; receiving the end state does not extend that deadline.

### Feedback and Usage analytics

`$cat:feedback` lets a user report a compliance failure privately. The user selects the failed response. The local
collector obtains the retained context snapshot used for that response, the triggering request, tool events through the
response's terminal event, and the response itself. It never scans project files to replace unavailable history.

Before submission, CAT shows the exact payload: report identifier; harness and client version; selected response;
triggering request; retained context snapshot; tool events; and prompt files that the user explicitly added. Missing
history is the literal `unavailable`. The user may remove an explicitly added prompt file or cancel. The confirmation
states:

> Your content will only be visible to CAT staff.

CAT binds the confirmed payload to `cat-feedback-consent-v1`, using its canonical SHA-256 fingerprint and confirmation
time, then submits only that immutable request. A successful submission validates and displays the `feedback-receipt-v2`
receipt. A failed submission remains local and offers retry or deletion; it is not silently queued.

Usage analytics ship in the first release. The local collector emits only the fields in the active published allowlist
and rejects an event containing another field. The initial allowlist contains file count, line count, sentence count,
model identifier, harness identifier, family count, trial count, token count, rounded duration, estimated cost, outcome,
and prediction error. It excludes prompt text, test text, conversation content, file names, raw traces, and stable
customer identifiers. `backend.md` owns the account-facing retention statement, the feedback submission schema,
payload-bound confirmation, separate optional-reuse and Usage analytics consents, and legally required privacy
controls. The local client sends no feedback until it can bind the reviewed payload to `cat-feedback-consent-v1` and
validate the returned receipt.

For certification progress, the client uses this compact terminology:

```text
Current lower bound: 92.8%
Target: above 95%
```

The product documentation explains that `Current lower bound` is the proof-covered weakest-family simultaneous lower
bound under the active certification contract. After the contract or recorded environment changes, the client displays
that the current lower bound is unavailable until new formal evidence exists. A previous value remains visible only in
historical evidence.

### Mechanical implementation agent

CAT packages one `work-execute` subagent for Codex and Claude Code. Its parent supplies a detailed implementation plan
and retains every design, product, architecture, file-selection, security, migration, sequencing, and acceptance
decision.

Before modifying the workspace, `work-execute` performs a read-only preflight of the complete plan, its named
authorities, and the current project. Every step must identify exact files or one deterministic derivation rule, the
required behavior and integration point, applicable contract decisions, tests and expected results, verification
commands, ordering, and mechanically verifiable acceptance evidence. One unambiguous project convention may determine
syntax or formatting; the agent may not choose among multiple compliant designs.

The parent also supplies an exact write allowlist and one workspace mode. `exclusive_shared` means that no other writer
may run in the shared worktree until the operation ends. `isolated` identifies a dedicated worktree created from an
immutable shared snapshot. Concurrent write-capable agents must use separate isolated worktrees; read-only agents may
share a workspace. Verification may inspect or compile files outside the write allowlist but never authorizes editing
them.

The parent supplies a versioned `cat-work-execute-plan-v1` manifest beside the detailed plan. It binds the plan path
and SHA-256 fingerprint to workspace mode and every allowed write's step, project-relative path, and required final
SHA-256. The runner rejects an unknown field, plan-fingerprint mismatch, path outside the project, unsupported mode,
or two steps requiring different bytes for one path before it starts either harness. After execution, it verifies the
declared output bytes in the isolated project and writes the runner-owned terminal result. Model prose cannot turn an
unverified output into `SUCCESS`.

If any step requires a decision, the agent leaves the workspace unchanged and returns
`BLOCKED_PLAN_NOT_MECHANICAL`. The result identifies every established ambiguity, its plan location, the evidence, and
the exact decision the parent must add. If a hidden ambiguity appears only after execution begins, the agent stops
before making that decision, preserves the workspace, and reports every file it already changed.

After preflight passes, the agent executes the plan in order, applies only prescribed changes, runs every required
verification, and reports each plan step and check as passed, failed, or unrun. It does not delegate work, select
workarounds, create commits, publish, or update issue state unless the plan mechanically specifies that action and all
required inputs. A diagnostic in an unowned file is reported as an out-of-scope dependency, not repaired. An unexplained
post-preflight file change is reported as a concurrent workspace change. A failed or unrun required check prevents
success.

An isolated agent returns its changed paths, verification evidence, and commit or patch identity without integrating
the result. The parent rejects paths outside the allowlist and integrates accepted results sequentially. A clean
integration worktree may use the repository's approved merge operation. A dirty parent worktree receives only an
authoritatively validated patch replay that preserves existing changes; otherwise the result remains isolated. Every
dependent delegation starts from the resulting integrated snapshot.

## Local optimizer and certification design

The Work client performs prompt tuning, evaluation, certification, evidence management, local history, cost
control, and prompt-system repair inside the customer's harness. It invokes the customer's model-provider account.
CAT's backend neither directs nor executes these operations and receives no prompt, test, trace, evaluator, customer
repository, or certification content through ordinary use.

### Initial model profiles

The first implementation starts with one Codex vertical slice using model `gpt-5.6-luna` and reasoning effort
`medium`. The Codex adapter must pass both values through the typed runner request and retain the resolved model and
effort in every operation receipt and certification environment snapshot.

The requested Claude launch profile is `haiku` with `medium` effort. Before that profile is enabled, the Claude adapter
must prove through a harness-boundary conformance test that the selected Claude release accepts and applies both values.
If the harness or selected Haiku model does not support an effort setting, CAT must report the unsupported combination
and the release remains blocked until the product owner selects a supported mapping. CAT must not silently omit,
downgrade, or relabel the requested effort.

Adding or changing a supported model profile requires a versioned profile record, a harness-boundary conformance test,
evaluator-protocol calibration for that profile, and a stale-certificate comparison for prior history. A friendly
alias may select a profile, but the retained evidence records the exact provider model identifier and applied reasoning
configuration.

### Cross-harness runner skills

Codex can run every other supported harness through a dedicated CAT skill. The first such skill is
`$cat:claude-runner`. It runs a requested Claude Code turn through CAT's packaged `claude-runner`, using the selected
Claude artifact, model profile, reasoning effort, project, and JSON Lines turn protocol. It returns the retained
transcript and receipt produced by that runner; it does not reimplement Claude's protocol or prompt-optimization
logic in skill instructions.

The Codex package must resolve the matching installed Claude artifact and runner through one typed cross-harness
resolver. It must not search arbitrary cache directories, silently download a harness, or substitute a different
artifact version. If the compatible Claude artifact, executable, authentication, or requested model profile is absent,
the skill reports the exact missing prerequisite and performs no run. A run remains locally isolated and follows the
same artifact validation, credential isolation, receipt, usage, and evidence rules as a direct `claude-runner`
invocation.

Every later supported harness receives the same pattern: one explicitly named Codex skill backed by its typed runner
and a version-compatible packaged artifact. Adding a skill does not imply that Codex may invoke an uninstalled
harness, access its credentials, or make a model call without the authorization already required for that call.

### Certificate trust boundary

A CAT certificate is local integrity and reproducibility evidence for its owner. Its fingerprints, immutable receipts,
and local signatures can detect accidental corruption, identify the exact inputs used, and support reproduction while
the retained evidence remains available. They do not constitute independent CAT-server attestation and do not prove
that a hostile owner has not modified the client, substituted evidence, or forged locally controlled history. The
client must not present a locally produced certificate as independent verification by CAT.

The certificate claim remains useful for selecting and maintaining a prompt in the recorded local environment. A
future independently attested product would require a separate trust boundary, keys and execution authority that the
client cannot control, and a distinct product claim; it is not part of this plan.

The first Work release includes the formal result named **certified**. Development scores, diagnostics,
comparisons, and readiness estimates help CAT reach that result, but they are not alternate certificates.

### Terminal goal

CAT must return one of two terminal product outcomes using a versioned policy selected to reduce expected local
model-provider cost and elapsed time:

- `certified`: CAT produces a prompt-file version that meets the claim below.
- `not certified`: CAT retains the best validated version and reports its current lower bound, limiting prompt-test
  families, completed work, amount spent, and automatic terminal reason without implying that further spending would
  necessarily reach the target.

CAT cannot guarantee that an arbitrary prompt, requirement set, or model permits `certified`. A certification process
starts only after CAT records its initial prompt-system snapshot, contract, edit boundary, and model profiles. Once
started, it exposes no pause, resume, or user-stop operation and runs until it records one of the two outcomes above. An
unsuccessful formal batch is intermediate; `plateau`, `uncertifiable`, `edit boundary exceeded`, `environment changed`,
exhausted lineage allowance, and unrecoverable evidence or
infrastructure failure produce `not certified`. The `certified` outcome makes this claim:

> **Certified:** With the recorded setup, this prompt will pass all applicable checks more than 95% of the time for
> every defined kind of use. CAT's testing process has at most a 5% chance of granting this certification when that
> standard has not been met.

A future trial for one prompt-test family randomly draws one prompt test from that family's recorded distribution and
runs it once. The trial passes only when every assertion applicable to that prompt test passes. CAT does not assign
frequency weights to prompt-test families or average strong families against weak ones. Every prompt-test family must
independently cross the 95% boundary, and every prompt-test family must cross it before CAT grants certification.

`Passes all applicable checks` means that the frozen evaluator protocol accepts every assertion applicable to the
prompt test. The running agent receives the prompt test's request and setup, while the evaluator receives its assertions
and retained execution evidence. The protocol records its model or models, reasoning settings, evaluator prompt, rubric,
evidence inputs, aggregation rule, and handling of infrastructure failures. The probability in the claim includes
variation from both the running agent and that evaluator protocol. This is not a guarantee for an unspecified future
environment, inputs outside the recorded distributions, or another interpretation of a semantic requirement. CAT does
not promise that its policy finds the globally or actually cheapest path; cost predictions are uncertain decisions
governed by the cost policy defined below.

#### Terminal acceptance evidence

CAT may label a prompt version `certified` only when one retained record establishes all of these conditions:

- The selected candidate's selection-adjusted weakest-family lower bound at its candidate allowance is above 95%.
- Every prompt-test family's certification bound at that allowance is above 95%; their minimum is the reported
  `Current lower bound`.
- The prompt-file version, certification-contract version, candidate identity, prompt tests, prompt-test generators,
  evaluator protocols,
  statistical method, completed trials, promotion-allowance ledger, and formal candidate-allowance ledger are immutable
  and verifiable.
- The recorded execution environment matches the environment used for every contributing trial.
- Every deterministic check and semantic evaluator protocol has passed its required known-good, known-bad, and
  ambiguous-evidence controls under the recorded protocol version.
- No required deterministic check, semantic evaluation, coverage-proof gate, simulation gate, or evidence-integrity
  gate is failed or unrun.

CAT may return `not certified` only when one retained record establishes all of these conditions:

- The best validated candidate and every candidate considered for that choice are identified under the current
  contract, or the record states that no candidate received valid formal evidence.
- The current lower bound is reported when valid formal evidence exists; otherwise it is explicitly `unavailable`.
- The terminal reason is one of `plateau`, `uncertifiable`, `edit boundary exceeded`, `environment changed`, `lineage
  allowance exhausted`, `invalid evidence`, `infrastructure failure`,
  `edit boundary exceeded`, or `no candidate certified`, with the exact failed or exhausted condition.
- Every completed operation has a terminal result, observed provider usage when available, and evidence receipt; no
  model-consuming operation remains pending or running.
- The record identifies the retained local evidence and its reproducibility status. A later workflow cannot reinterpret
  this terminal result or reuse evidence under a changed contract.

### Core terms

- A **certification contract** is the versioned set of prompt requirements, applicability rules, prompt tests,
  prompt-test groups, prompt-test families, prompt-test distributions, prompt-test generators, evaluator protocols, and
  recorded execution-environment values that define the claim.
- A **prompt test** is one executable case containing the running agent's request and setup plus the assertions used to
  evaluate the retained result. The running agent never receives those assertions.
- A **prompt-test group** organizes related prompt-test families for navigation and traceability. It receives no
  compliance bound and cannot average or replace its prompt-test families' results.
- A **prompt-test family** is a lowest-level group of related future-use prompt tests that CAT tests and scores
  separately. It represents one substantive kind of use and receives its own guarantee; CAT does not hide a difficult
  prompt-test family inside a weighted prompt-test-group average.
- An **interaction prompt-test family** is a prompt-test family whose tests exercise two or more requirements or loaded
  prompt files together. It receives the same separate compliance guarantee as every other prompt-test family.
- A **prompt-test distribution** defines how CAT randomly varies prompt tests inside one prompt-test family. It may
  randomize superficial details such as names, wording, file paths, and harmless ordering. A substantive behavior
  difference creates a separate prompt-test family instead of receiving an assumed frequency.
- A **prompt-test generator** draws from one prompt-test distribution and produces a prompt test whose request, setup,
  family assignment, and applicable assertions satisfy the frozen certification contract.
- A **prompt-test run** executes one frozen candidate against one prompt test and records one evaluator-protocol outcome
  for every prompt-test family that the contract assigned before execution.
- A **candidate** is one frozen prompt-system snapshot tested without editing during its formal evidence collection.
- A **certification process** is the complete automatic sequence of candidate generation, development comparison, and
  formal testing performed under one certification contract until CAT returns `certified` or `not certified`. New
  agents, sessions, or attempts do not reset it.
- An **optimization lineage** contains every certification process adaptively selected toward one deployment objective.
  Its shared lineage error budget protects the result when optimization moves among linked certification contracts.
- The **lineage error budget** is the 5% maximum probability that CAT's formal procedure may falsely certify any
  candidate or overstate the reported `Current lower bound` anywhere in one optimization lineage. Its append-only
  candidate-allowance ledger records every assigned share and rejects an assignment that would exceed the budget.
- A candidate's **weakest-family lower bound** is the smallest selection-adjusted lower confidence bound among its
  prompt-test families. **Current lower bound** is the greatest valid weakest-family lower bound retained among formally
  tested candidates under the active contract; the **best validated candidate** is the candidate that owns that value.
  The bound is one-sided and anytime-valid for that candidate's true weakest-family future pass probability under the
  recorded evaluator protocol and setup. CAT does not report a formal numeric lower bound for each individual prompt
  test family.
- An **evaluator protocol** is the frozen procedure that converts one complete retained execution into `pass` or `fail`.
  It identifies the evaluator model or models, reasoning settings, prompt, atomic assertions, rubric, evidence inputs,
  isolation rules, aggregation rule, and infrastructure-failure handling. An infrastructure failure produces no formal
  outcome.
- A **development policy** is a versioned machine-readable policy produced by the approved simulation suite. It contains
  the exact paired-comparison, material-regression, promotion, readiness, plateau, and fallback decisions used during
  development.
- The **false-promotion allowance** is the 5% maximum probability that any champion promoted within one optimization
  lineage fails the development policy's true weakest-family improvement rule under the frozen reference
  distributions. An append-only allocation ledger assigns a summable share before each adaptive comparison.
- A **cost policy** is a versioned machine-readable forecasting contract. It names the available actions, input
  features, calibrated reference population, uncertainty and applicability limits, decision loss, and fallback when no
  reliable forecast is available.
- An **extraction policy** is a versioned machine-readable operation graph that fixes the contract-extraction actors,
  profiles, inputs, output schemas, merge and disagreement rules, maximum resolution passes, terminal results, and
  validation checks.
- An **observation inventory** is the versioned record of instructions and environment inputs known to affect the
  covered harness invocations. It records each artifact's activation conditions, precedence, fingerprint, source, and
  whether CAT can edit it. Discovery may add an artifact to a later candidate's inventory, but doing so changes that
  candidate's prompt-system snapshot and requires the applicable contract checks.
- The **optimization target** is the one prompt file whose future compliance CAT is optimizing and certifying. It
  remains the target entry file for the entire workflow. Its prompt tests and their family results define the compliance
  claim, even when CAT moves instructions into related files.
- A **related file** is a customer-controlled prompt file whose content, activation, precedence, dependency, or
  interaction can affect the target's measured behavior. CAT may edit, create, delete, rename, or split related files
  inside the authorized project boundary. Each proposed structural change must keep the target entry file loadable and
  record how the resulting files participate in the target's prompt-system snapshot.
- The **edit boundary** is the recorded project root and the prompt-file artifact classes that CAT may change while
  optimizing the target. It does not enumerate files. A file becomes editable only when evidence
  establishes that it is related to the target and the proposed change remains inside this boundary.
- A **Stale certificate** is a historical certificate whose recorded prompt system or environment no longer matches the
  user's current configuration.

### Certification-contract extraction

#### Prompt-system discovery and edit authority

CAT discovers the initial prompt system before it extracts a certification contract or spends model inference on
optimization. For every covered invocation class, the harness adapter combines its versioned resolution rules with an
effective-context capture from the execution boundary. The resulting observation inventory contains:

- the target prompt file and its includes or declared dependencies;
- every customer-controlled rule, skill, agent instruction, plugin instruction, and configuration artifact that the
  harness can load for a covered prompt test;
- activation conditions, precedence, and dependency edges among those artifacts; and
- a stable version or opaque identity for an uneditable system prompt or harness input whose contents CAT cannot read.

The discovery operation returns `blocked` rather than claiming a complete initial inventory when a supported harness
cannot account for an input that may change the covered result. A file that cannot be loaded in any covered invocation
does not enter the inventory. An artifact whose content is uneditable may still enter as observed environment.

Two independent prompt-system-analysis agents inspect the same immutable initial inventory. They propose
semantic-overlap, precedence, dependency, and interaction edges. A deterministic reducer retains the union, preserves
disagreements, and identifies the single optimization target. An unresolved target identity or dependency edge returns
`blocked`; it cannot be silently treated as irrelevant.

Starting optimization authorizes CAT to change customer-controlled prompt files inside the recorded project boundary
when the change is evidence-backed as necessary to improve or preserve the target's behavior. This authority includes
creating companion files and splitting, renaming, or deleting existing prompt files. It does not authorize source-code,
third-party, system-prompt, harness, or out-of-project changes.

The optimization target remains distinct from related files in every request, receipt, candidate, certificate, and
user-visible report. Related files contribute applicable preservation and interaction checks to the contract, but the
certificate's compliance claim remains about the target. An observation-only artifact contributes environment identity
and interactions that affect the target claim; CAT does not claim to certify that artifact's unrelated behavior.

The change guard accepts a related-file claim only from one of three versioned evidence forms:

1. the harness resolver records a load, include, activation, or precedence edge connecting the file to a covered target
   invocation;
2. the development policy's controlled ablation test records an interaction with the target at its approved boundary;
   or
3. a creation or split manifest maps source clauses to the new file and the resolver proves that the target reaches the
   new file under every original activation condition.

A semantic-overlap label by itself is diagnostic evidence, not write permission.

Every challenger records a complete prompt-system snapshot and a change manifest. Before execution, a deterministic
guard verifies that each changed or newly created artifact is a prompt file inside the authorized project boundary,
that the target entry file still exists and resolves, and that every changed artifact has an evidence-backed relation
to the target. A split must also prove that the harness loads the extracted content in every invocation in which the
original content applied. A violation rejects the challenger before a model call.

Discovering or creating another loaded prompt file does not mutate an existing candidate. CAT records a new candidate
snapshot and validates it against the frozen certification contract, including every preservation and interaction
assertion. An optimization-produced structural change does not create a new contract or make the retained champion's
certificate stale because it remains isolated until a stronger formally validated candidate replaces that champion.
The new candidate remains in the same optimization lineage, so structural change cannot reset selection-aware error
accounting.

#### Automatic extraction

CAT derives the certification contract automatically before optimization. A signed extraction-policy manifest owns the
operation. It contains:

- the complete source-inventory rule, dependency resolver, canonical representation, and source-span format;
- one primary requirement-extractor profile, one independent requirement-challenger profile, one family-and-generator
  designer profile, one designer profile for interaction prompt-test families, one evaluator-protocol designer profile,
  and one final
  monotonic-review profile, including each model, reasoning setting, instruction fingerprint, permitted inputs, and
  output schema;
- the dependency graph and invocation order for those actors;
- stable identifier formats for requirements, source spans, applicability rules, families, generators, assertions,
  evaluator protocols, disagreements, and review actions;
- deterministic merge rules, the resolver profile for semantic disagreements, a maximum of two resolution passes, and
  the terminal result when disagreement remains;
- every schema, traceability, coverage, distribution, and evaluator-protocol validation run before freezing; and
- the exact `ready` and `blocked` result schemas and their permitted successors.

The manifest executes this operation graph:

1. The local inventory operation consumes the versioned observation inventory, optimization target, and edit boundary,
   canonicalizes every
   contract input, records its content fingerprint and source spans, and returns `inventoried` or `blocked`. It rejects
   an unclassified artifact, an unresolved dependency edge, or a mismatch with the effective-context capture.
2. The primary extractor and independent challenger each receive the same immutable inventory without seeing the
   other's output. Each returns a complete requirement set in the manifest schema.
3. A deterministic union operation retains every source-traced obligation from either set. It may collapse two records
   only when their normalized proposition and complete source-span set are identical. Every other overlap becomes a
   named disagreement.
4. The family, interaction, and evaluator designers consume that union and return applicability rules, prompt-test
   groups, prompt-test families, interaction prompt-test families, prompt-test distributions, prompt-test generators,
   atomic assertions, evidence requirements, and proposed evaluator protocols. Each output must trace to one or more
   retained requirements.
5. The monotonic reviewer compares the complete proposed contract with the immutable source inventory. It may add a
   missed obligation, split an overbroad family, add an interaction, or tighten traceability. It may not delete a
   source-traced obligation or merge substantive behaviors.
6. The deterministic reducer applies additions and exact duplicate collapses. A semantic conflict goes to the manifest's
   resolver, which returns `choose with trace`, `merge while preserving every source span`, or `unresolved`. The reducer
   runs at most two resolver passes; `unresolved` after the second pass produces `blocked`.
7. Deterministic validation rejects an untraced requirement, duplicated identifier, uncovered applicability branch,
   missing family distribution, generator that can emit outside its family, unassigned assertion, missing evaluator
   protocol, unresolved review action, or output that fails its schema.
8. CAT freezes and versions only a validated result. The user can inspect every non-proprietary requirement, family,
   distribution, assertion, evidence requirement, evaluator-protocol identifier, source trace, and derivation. The
   Work distribution may keep evaluator prompts and withheld adversarial cases outside the optimizer's editable
   inputs; the user receives their versioned identifiers, fingerprints, validation metrics, and externally observable
   decision rules.

The extraction operation returns either `ready` with an immutable contract identifier or `blocked` with every missing
requirement trace, distribution, evaluator protocol, or unresolved disagreement. Optimization cannot start from
`blocked`.
Rerunning extraction creates a new proposed version; it never edits or silently completes a frozen contract.

The extraction schemas use these required fields. Every identifier is an opaque, stable identifier scoped to the
proposed contract; every list is ordered canonically before fingerprinting:

- `source span`: artifact identifier, content fingerprint, start and end positions, and quoted normalized source;
- `requirement`: requirement identifier, atomic proposition, source-span identifiers, applicability-rule identifiers,
  and dependency identifiers;
- `applicability rule`: rule identifier, executable predicate schema, required evidence fields, and complete outcomes
  `applicable`, `not applicable`, or `invalid evidence`;
- `prompt test`: test identifier, family identifiers, running-agent request, setup and fixture references,
  applicability-evidence fields, assertion identifiers, generator provenance when generated, and content fingerprint;
- `prompt-test group`: group identifier, parent-group identifier when present, ordered child-group and child-family
  identifiers, and a descriptive label;
- `prompt-test family`: family identifier, parent-group identifier when present, requirement identifiers,
  applicability-rule identifiers, substantive behavior definition, distribution identifier, generator identifier, and
  assertion identifiers;
- `prompt-test distribution`: distribution identifier, versioned sampler identifier, superficial variation
  dimensions and their finite or generative sources, exclusion predicates, seed namespace, and support-validation
  receipt;
- `assertion`: assertion identifier, requirement identifier, classification `deterministic` or `semantic`, atomic pass
  proposition, required evidence-field identifiers, evaluator-protocol identifier, and infrastructure-failure rule;
- `disagreement`: disagreement identifier, competing record identifiers, source-span union, conflict category,
  resolution-pass count, resolver result, and retained rationale; and
- `extraction result`: result `ready` with the complete identifiers and validation receipts, or `blocked` with every
  disagreement and missing-field identifier plus the permitted recovery operations.

The schema validator, not an agent, enforces required fields, identifier references, canonical ordering, allowed enum
values, and fingerprint reproducibility. Agents supply only the semantic contents assigned to their profiles.

Multiple-agent agreement reduces missed extractions but cannot prove that an inferred distribution represents uses the
prompt never describes. The certificate therefore covers the frozen extracted contract, not every imaginable use.

The extraction policy is replaceable only by publishing and validating a new version. A runtime agent may not change
actor count, profiles, merge behavior, resolution passes, or acceptance checks. The workflow-conformance gate must run
the original case, a case where the challenger finds a missed requirement, a substantive prompt-test family split, an
exact duplicate, a resolvable disagreement, and a disagreement that remains blocked after two passes.

#### Prompt-test changes

Adding or removing a prompt test creates a new certification-contract version. Materially editing its request, setup,
fixture, assertions, expected results, applicability rules, family assignment, or generation settings also creates a
new version. Changing a prompt-test group, family, distribution, generator, or evaluator protocol creates a new version.
A deterministic
canonicalizer may ignore only comments and formatting bytes that the test loader proves do not change its parsed
representation. The
preceding certificate remains valid as a historical statement about its old contract, but it does not describe the
changed test configuration.

All prompt-test content remains on the client. Before an operation, the local harness creates an immutable snapshot of
the applicable tests and certification-contract artifacts and retains that snapshot for later audit or reproduction.
The local project history stores contract lineage, project-relative paths, content and environment fingerprints,
statistical settings, trial summaries, locally estimated costs, outcomes, and certificate state beside the versioned
project configuration. It does not send prompt tests, raw traces, prompt text, or optimization history to CAT.

Local fingerprints prove whether an available artifact matches the artifact used by a historical operation; they
cannot reconstruct a deleted file. The customer therefore owns retention and recovery of every snapshot needed to
reproduce a certificate. Deleting a retained prompt, test, trace, or snapshot does not rewrite the historical result,
but its local certificate view changes to `Reproduction: unavailable` and identifies the missing evidence. The result
cannot contribute evidence to a new operation. Reproducing or recertifying requires a new snapshot and contract
operation; a matching hash alone does not reconstruct missing evidence.

#### Evaluator protocols

CAT classifies every assertion before development begins:

- A deterministic assertion is decided from retained evidence by a versioned executable check. The check is approved
  only after its known-good and known-bad controls pass at the same execution boundary used by formal trials.
- A semantic assertion is decided by an approved model-evaluator protocol. The assertion must state one atomic
  proposition, the evidence the evaluator may use, and the exact pass rule. The evaluator must cite the retained
  evidence supporting its decision. A prompt test passes only when every applicable assertion passes.

The initial semantic protocol uses one high-end evaluator with high reasoning effort. It receives the frozen atomic
assertions, rubric, and bounded retained evidence, but not the candidate's optimization history, prior scores, other
evaluators' decisions, or the expected outcome of a test-corpus item. A valid formal evaluation must return `pass` or
`fail` for every applicable assertion. Missing or corrupt evidence and evaluator-service failures are infrastructure
failures and contribute no formal outcome; evaluator uncertainty about complete evidence is a `fail`, not a discarded
trial.

CAT maintains a versioned evaluator test corpus for each assertion family. Separate high-end corpus-authoring and
challenge agents derive expected decisions from the frozen atomic assertion and rubric before the evaluator under test
sees an item. They must cite the clause and evidence that determine each expected decision. An unresolved example is
recorded as ambiguous and forces rubric clarification; it cannot become a passing validation control by majority vote.
After clarification, each corpus contains known-passing, known-failing, boundary, ambiguous-evidence, and adversarial
examples with a frozen expected `pass` or `fail`; complete evidence that does not establish an assertion expects `fail`.

The evaluator-validation operation runs repeated blinded evaluations and records decision accuracy against those
expected decisions, reproducibility, false-pass and false-fail rates, prompt-injection resistance, cost, and latency. A
versioned evaluator-policy manifest defines every minimum accuracy and reproducibility threshold, maximum false-pass and
false-fail rate, mandatory adversarial gate, repeat count, cost comparison, and fallback. The operation uses an
untouched validation partition to approve or reject a protocol; an agent cannot waive a failed threshold or tune the
protocol against that partition.

For every assertion family, the same operation compares the one-evaluator protocol with this model-adjudication
protocol:

1. Two high-end evaluators independently decide each atomic assertion from the same bounded evidence.
2. If they agree, their decision is final.
3. If they disagree, a third high-end evaluator reviews the assertion without seeing the earlier decisions. The
   majority decision is final.

The evaluator-policy manifest defines the minimum validated improvement that justifies model adjudication and how cost
and latency are compared. CAT selects adjudication only for assertion families where the untouched validation results
meet that threshold. A certification contract may therefore assign the one-evaluator protocol to one assertion family
and the model-adjudication protocol to another. The selected protocol is frozen before formal evidence is collected.
Production certification requires no human reference adjudication and no second evaluator-validity lower bound: the
selected protocol's final verdict is the Bernoulli `pass` or `fail` outcome used by the statistical procedure.

Before model-consuming development or formal trials, an evaluator-protocol preflight returns one of these results:

- `eligible`: every assertion has a deterministic check or an approved semantic evaluator protocol; or
- `uncertifiable`: at least one assertion has no approved protocol; CAT names it and rejects optimization setup. The
  user may clarify the assertion, create a deterministic check, or validate a semantic protocol before submitting a new
  start request.

This preflight owns evaluator validity only. Cost forecasting cannot reject a statistically valid contract merely
because no calibrated forecast exists.

##### Evaluator gaming and untrusted evidence

Evaluator gaming is a real validity risk. Repeated optimization can discover wording that receives a favorable verdict
because of a stable evaluator bias rather than because it better satisfies the intended assertion. Selection-aware
statistics control false certification caused by repeated candidate testing, but they do not repair a semantic
evaluator whose pass rule is a poor proxy for the intended interpretation. Published evaluator studies have demonstrated
[prompt-injection susceptibility](https://arxiv.org/abs/2505.13348),
[position bias](https://arxiv.org/abs/2406.07791), and
[self-preference bias](https://arxiv.org/abs/2410.21819).

CAT therefore applies all of these controls:

- Separate formal evaluator prompts, withheld corpus items, model-variant details, and adversarial cases from the
  optimizer's editable inputs. Give candidate-generation agents only the assertion, cited failure evidence, and bounded
  failure category needed to make a repair. Do not claim that local packaging makes those artifacts impossible to
  inspect.
- Use separate evaluator sessions from candidate generation and execution. Blind evaluators to candidate identity,
  development history, prior verdicts, cost, and certification state.
- Validate atomic assertions against adversarial cases for verbosity, position and ordering bias, evaluator-style
  imitation, unsupported evidence, self-referential requests for a pass, rubric manipulation, and prompt injection.
- Permit randomized evaluator-prompt or model variants only when they encode the same frozen interpretation and pass
  the evaluator-validation gate. Record their selection distribution as part of the evaluator protocol.
- Run the evaluator test corpus before every new protocol version. A newly discovered material false-pass pattern
  blocks that protocol from new certification and makes certificates that used the affected interpretation stale.
- Add model adjudication only where the validation partition shows that it materially improves decisions relative to
  its added cost. Evaluator count alone is not evidence of validity.

Executor output, tool output, retrieved text, and files are untrusted evidence. The evaluation request places the
evaluator-owned assertions and rubric in its instruction fields and places each evidence item in a separately typed,
length-bounded field marked as untrusted data; it never concatenates evidence into evaluator instructions. The
evaluator has no tools or authority to alter its rubric. A deterministic validator rejects an assertion identifier,
verdict, or evidence citation outside the frozen output schema. Missing or oversized required evidence blocks the
evaluation instead of being silently truncated. Instructions inside evidence cannot change the
assertions, rubric, evaluator protocol, or output schema. The adversarial corpus must include direct and indirect prompt
injections; a protocol that follows one fails validation.

Changing an evaluator model, reasoning profile, evaluator prompt, rubric, atomic assertion, evidence schema,
aggregation rule, security-isolation rule, or approved model-variant distribution changes the recorded interpretation
of compliance. CAT creates a new certification contract, labels the preceding result `Stale certificate`, and makes the
new contract's `Current lower bound` unavailable until it has formal evidence.

#### Pre-execution cost forecast

After contract extraction and before model inference, a deterministic preflight supplies the number of prompt-test
families, the number that exercise interactions, selected evaluator protocols, model-adjudication workload, remaining
lineage error budget, candidate-batch policy, and available model and harness prices to the approved simulation and
cost-policy artifacts. It returns:

- `forecast available`: a calibrated range for development, formal trials, and evaluator execution, with the policy
  version and uncertainty interval; or
- `cost unavailable`: the contract is outside the cost model's calibrated range or no validated simulation covers its
  number of prompt-test families, interaction structure, evaluator-protocol mix, dependence, or remaining lineage error
  budget.

`Cost unavailable` means that CAT cannot quote a reliable local model-cost range; it does not make the statistical
operation invalid or prevent it from starting. CAT reports `No reliable certification cost forecast is available`,
names the unsupported dimensions, and continues under the simulation-selected fallback policy. It reports completed
work and estimated actual provider cost after each optimization round. CAT may not merge, remove, or reweight a
prompt-test family merely to lower cost. Every later material contract or environment change reruns this forecast.

### Certification-engine replacement and migration

The local certification design replaces CAT's current complete-suite SPRT evaluator for this product. The current
engine treats one complete suite as one Bernoulli trial, uses SPRT only against fixed strong and weak rates, and stops
after its configured maximum trial count. It cannot produce the plan's adaptive evidence for each prompt-test family,
candidate-allowance ledger, weakest-family lower bound, or lineage-wide repeated-selection guarantee.

The replacement engine, initially identified as `prompt-test-family-sequential-v1`, has six authoritative operations:

1. `compile-contract` validates a frozen certification contract and produces the statistical family manifest.
2. `assign-allowance` records the candidate's lineage allowance before that candidate produces formal data.
3. `schedule-trial` selects one immutable candidate and prompt-test draw from the permitted formal pool.
4. `record-outcome` accepts one schema-valid evaluator verdict or one infrastructure failure and appends it exactly
   once to the immutable trial ledger.
5. `decide` returns the single permitted next sampling, certification, futility, invalid-evidence, or terminal action
   from the proof-covered state.
6. `report` produces `Current lower bound`, diagnostics for limiting prompt-test families, terminal status, and a signed
   evidence manifest from the verified ledgers.

Before implementation, perform a reuse audit of every old prompt-builder and prompt-tester optimizer component. Classify
each component as `retain` only when the new optimizer calls it through its current focused contract, `replace` when a
new optimizer owner supplies its required behavior, or `retire` when no supported behavior needs it. The audit records
the component, callers, replacement owner or retained contract, tests, documentation, packaged artifacts, and removal
order. The new optimizer may reuse prompt-test content, deterministic checks, runner adapters, and immutable artifact
fingerprints only after an importer validates them against the new schemas. It may not import a legacy compliance rate,
SPRT likelihood ratio, pass/fail decision, completed-trial count, or stopping state as formal evidence. Legacy results
remain historical records and cannot be combined with `prompt-test-family-sequential-v1` evidence.

Before the Work client can return `certified`, the new engine must pass deterministic numerical controls, property
tests, the statistical simulations in this plan, the complete workflow-conformance gate, and end-to-end runs through
each supported harness. The local certification entry point rejects a legacy engine identifier. The old optimizer and
its prompt-builder/prompt-tester entry points are retired after the replacement passes its acceptance gates; retained
generic components remain only under their focused reused contracts.

### End-to-end workflow

#### 1. Discover the prompt system and establish edit authority

The prompt-system discovery operation records the initial observation inventory for the covered harness invocations.
The prompt-system-analysis operation identifies the single optimization target and records the evidence that connects
each initially related file to it. Starting the operation authorizes CAT to change related prompt files within the
recorded edit boundary, including creating companion files or splitting existing files. The target entry file remains
the object whose tests run and whose compliance CAT measures.

#### 2. Enforce deterministic requirements mechanically

Before spending model inference on prompt behavior, the contract extractor classifies every assertion as
`deterministic` or `semantic` and records the evidence each evaluator protocol consumes. For each deterministic
assertion, the owning local tool produces an executable check and at least one known-good and one known-bad control. The
evaluator-protocol preflight runs those controls at the production evaluation boundary and records their receipts. A
missing check, missing control, or unexpected control result returns `blocked`; development cannot begin. Formal model
trials measure only assertions whose outcome still depends on model behavior, although deterministic checks may inspect
their retained evidence.

#### 3. Run development optimization

Development optimization may generate and revise any number of candidates without consuming the lineage error budget.
It uses targeted, adversarial, and randomly varied prompt tests from the development pool to:

- diagnose failure causes;
- repair the target prompt;
- detect conflicts among loaded prompt files;
- maximize the lowest family score instead of the average score; and
- supply the retained outcomes and costs that the development and cost policies use to rank candidates.

These adaptive development results are empirical scores and cost predictors. They are not confidence-qualified future
compliance guarantees because CAT selected and edited candidates after observing them.

Exploratory diagnosis and candidate generation do not need selection-aware controls. Replacing the retained champion
does: CAT makes a confidence-qualified claim that the accepted prompt system did not move backward, so every promotion
must use the selection-aware comparison controls below. Formal certification keeps a separate lineage allowance and
uses only formal evidence.

Before any candidate is optimized, the certification contract freezes one reference distribution and generator for
each prompt-test family. It derives independent, domain-separated random streams for `development`, `readiness`, and
`formal`; all three streams draw from that same family distribution:

- `development`: visible, adaptive draws whose results may diagnose and change later candidates;
- `readiness`: fresh draws hidden until the compared candidate freezes and used only by the development policy; and
- `formal`: fresh draws hidden until a candidate freezes and counted only by the certification engine.

The generator validator proves that every stream uses the same support and probabilities and that its random source is
independent of candidate content and prior outcomes. A prompt test may coincidentally render the same content in two
independent draws; CAT never reuses one retained execution or verdict as another draw. A deterministic leakage check
rejects a readiness or formal request whose seed or execution identity was previously disclosed or used. A formal
failure may inform later development only after its candidate's formal result is recorded; that outcome remains formal
evidence and is never moved into another stream or recounted.

##### Candidate creation and racing

`FailureDiagnosis` is the only input to candidate creation. It groups retained development failures by their limiting
prompt-test family, affected prompt-test family, and evidence-backed cause. A cause is eligible only when it cites the
failed prompt tests and evaluator evidence, identifies the target or a related editable prompt file, and does not
depend on an uneditable artifact or an environment change. The diagnosis operation orders eligible causes by: limiting
family first; then the number of distinct failed prompt tests; then interaction-evidence strength; then cause
identifier. It records that order before a candidate is proposed.

The candidate generator receives the retained champion, the ordered diagnosis, the frozen contract, and the edit
boundary. It returns zero to five distinct `CandidateProposal` values. Each proposal must name one eligible cause,
cited evidence identifiers, an expected affected-family set, a complete change manifest, preserved requirements, and
an estimated local cost when available. The generator may propose alternatives for one cause, but it may not propose a
sixth candidate, an edit without a cited cause, or a candidate outside the edit boundary.

`CandidateSelection` validates every proposal and orders valid challengers by the cause order, then by fewer changed
files, then by lower estimated local cost when both costs are available, then by proposal identifier. It does not treat
the generator's self-reported likelihood of success as evidence. Each challenger first receives deterministic checks
and the policy's smoke prompt tests for its limiting and affected families. A deterministic failure, invalid manifest,
or smoke-test material regression rejects it before paired comparison. The configured survivor limit determines which
remaining challengers receive the broader paired comparison. Only a challenger that the paired comparison promotes can
replace the retained champion; only the readiness gate can send that champion to formal testing.

##### Paired A/B development comparisons

CAT uses paired A/B comparisons as its primary development candidate-selection method. For each comparison, it freezes
one development prompt-test batch before either version runs, then executes the retained best version and one challenger
against the same prompt tests under the same recorded environment. It randomizes version order, uses fresh harness
sessions, and evaluates every requirement applicable to each prompt test.

CAT first compares the limiting prompt-test families and the prompt-test families affected by the edit. A surviving
challenger then receives a broader regression comparison across every prompt-test family before it may replace the
retained champion. CAT records paired wins, ties, and losses; empirical scores for each prompt-test family; regressions;
provider usage and estimated cost; latency; prompt size; and every added, removed, renamed, split, or edited file.

The development policy allocates one 5% false-promotion allowance across every adaptive comparison in the optimization
lineage. It uses proof-covered simultaneous paired confidence sequences and promotes a challenger only when:

- every deterministic, preservation, loading, and interaction check passes;
- the simultaneous lower bound for the change in the weakest-family development objective is above zero; and
- no prompt-test family's paired result crosses the simulation-defined material-regression boundary.

This controls the probability that any promoted champion failed the stated improvement rule; it does not prove that
the unknowable true rate improved with certainty. An inconclusive comparison collects more pairs up to the frozen
maximum and then rejects the challenger. A rejection restores the unchanged champion snapshot. The policy's allowance
schedule, minimum and maximum pair counts, tie handling, material-regression definition, and promotion boundary come
from the approved simulation and statistical coverage argument; no agent may replace them with an intuitive judgment.

These are development comparisons. The development policy may stop them early only when its recorded outcome boundary
returns a terminal decision; an agent cannot apply another qualitative early-stop rule. Comparisons use regenerated
development prompt tests over time so repeated editing does not target one fixed prompt-test batch. Their results may be
labeled `Development comparison` or `Development estimate`; they do not use the `Current lower bound` label and do not
contribute formal certification evidence.

The retained `Current lower bound` is monotonic: CAT never replaces the candidate and evidence record that established
it with a lower formally supported value. A development round may leave that value unchanged or `unavailable`; only a
stronger selection-adjusted formal result can raise it. Round reports identify both the retained champion and the
candidate that owns the displayed formal bound when they differ.

##### Monotonic progress contract

Within one unchanged certification contract, CAT enforces all of these invariants:

- the optimization target identity never changes;
- candidate generation occurs in an isolated snapshot and cannot overwrite the retained champion;
- a challenger replaces the development champion only through the false-promotion-controlled paired test above;
- a challenger replaces the best formally validated candidate only when its selection-adjusted `Current lower bound`
  exceeds the retained value;
- the report projection retains the greatest valid anytime lower bound established for its owning candidate and the
  greatest valid candidate bound established in the lineage; and
- the evidence, comparison-allocation, and formal candidate-allowance ledgers are append-only.

These invariants keep the chance that any promoted champion violates the stated improvement rule at or below 5% and
make the reported formal lower bound nondecreasing under one contract. They do not guarantee that true compliance
improves with certainty, that
file count decreases, or that the remaining diagnosis workload shrinks. A stochastic run can reveal another failure,
and a valid repair can add or split files. The simulation-defined plateau boundary guarantees a terminal result when
the controlled evidence cannot establish another promotion. A material contract change starts with `Current lower
bound: unavailable`; the earlier bound remains historical because the new contract asks a different question.

#### 4. Apply a readiness gate

After each development round, the local readiness operation—not a candidate-generation agent—applies the frozen
development and cost policies. Its inputs are the retained paired outcomes, limiting and regressing families,
unresolved conflicts, round cost and latency, contract and evaluator-protocol mix, remaining lineage error budget, and
current prices. It returns `continue development`, `repair prompt system`, `start formal batch`, `edit boundary
exceeded`, or `plateau`, plus the input snapshot and reason code. A repair may change the prompt-system topology inside
the edit boundary; `edit boundary exceeded`
cannot authorize a change outside it.

When the cost forecast is applicable, the operation compares the distributions for `certify now` and `one more
development round, then certify` using the cost policy's exact loss and uncertainty threshold. It continues development
only when that policy selects the latter. When the forecast is unavailable after development has begun, it applies the
simulation-selected fallback readiness rule from the development policy and labels the cost forecast unavailable; it
does not invent a rate or cost. A number of prompt-test families or interaction pattern outside the validated
feasibility envelope stops before the next model-consuming operation and returns to the pre-execution gate.

The readiness decision never uses an asserted true compliance rate because CAT cannot observe that value. The
development policy instead defines an exact gate over fresh development outcomes, retained failures, regressions, and
plateau state. CAT does not impose a fixed 99% development target. Simulated true rates such as 97% or 99% may be used
to select and validate the policy offline, but they are not runtime inputs or reported measurements.

##### Run-count behavior

One run means executing one candidate on one generated prompt test. A paired A/B prompt test therefore costs two runs.
Development has no compliance-certification run count. It starts with the development policy's exact initial pair count
and adds the policy's specified increments until the policy returns `promote`, `reject`, `repair regression`, or
`plateau`.

Formal certification also has no universal run count. It depends on the number of prompt-test families and candidates,
the true pass rates for the prompt-test families, the remaining lineage error budget, observed failures, dependence
among results, and the proof-covered statistical method. For scale, a one-sided exact fixed-sample test for one frozen
prompt-test family and one unselected candidate can first put its 95% lower bound above 95% after 59/59 successes. If
the true rate is 99%,
approximately 124 trials
give at least an 80% chance of demonstrating a rate above 95% at a 5% one-sided error level; if the true rate is 97%,
the analogous count is approximately 601.

Certification requires every prompt-test family to pass, but CAT does not split one candidate's allowance across its
prompt-test families. If a candidate is below the target, at least one prompt-test family is at or below 95%, and CAT
can falsely certify that candidate only by wrongly passing that weak prompt-test family. Giving every prompt-test family
the candidate's full allowance therefore still bounds the candidate's false-certification chance and its scalar
weakest-family lower bound. CAT does not spend a separate error allocation to report simultaneous numeric bounds for
every prompt-test family. For illustration, 59/59 successes is the first possible crossing for a fixed-sample exact test
at a full 5% level; the production candidate allowance is smaller and uses an anytime-valid method, so its required
evidence is determined by the production manifest rather than that illustration. Ten prompt-test families increase
total execution cost because every prompt-test family needs evidence.

The chance and cost of getting all prompt-test families across the boundary still worsen as their number grows,
and candidate selection reduces the allowance available to later candidates. The adaptive method is intended to spend
less than a uniform fixed design by stopping weak candidates and resolved prompt-test families early; the simulation
must measure whether it does. The UI displays only the cost-policy operation's range for the actual contract. If that
operation returns `cost unavailable`, the UI displays that result instead of quoting these illustrations as a price or
completion promise.

#### Cost-minimizing execution policy

The development and cost policies minimize combined local execution, model-provider inference, evaluator, and
elapsed-time cost through this ordered funnel:

1. Prompt-system discovery, target and edit-boundary validation, deterministic parsing, traceability, static checks,
   and known-control validation run before model work.
2. One candidate-generation operation may propose several challengers from the same retained diagnosis. Each challenger
   has its own immutable identity and revision review.
3. Candidate racing runs deterministic checks and the cheapest readiness-policy smoke prompt tests for the limiting and
   edited families. A challenger with a deterministic failure, named material regression, or terminal rejection leaves
   the race without receiving broader trials.
4. Surviving challengers enter successive paired comparisons against the retained best candidate. The development
   policy allocates additional pairs only while their decision can still change. At most the policy's configured
   survivor count receives the broad regression comparison across all prompt-test families.
5. The readiness pool evaluates the final survivor using fresh hidden prompt tests. Only a readiness terminal
   result may send that candidate to formal testing.
6. Formal testing starts with one champion candidate. The simulation must compare champion-first batch sizes 1, 2, 3,
   and 5. A production policy may allocate formal allowance to more than one candidate only when untouched validation
   shows that doing so lowers expected combined cost relative to the one-candidate baseline within the same error and
   cost objective.

The formal scheduler ranks permitted prompt-test draws by expected decision information per combined executor and
evaluator cost, using only prior evidence and the frozen scheduling policy. A prompt test may contribute outcomes to
multiple predeclared families when the contract defines all applicable scores before execution and the statistical
method preserves their dependence. The scheduler prefers such joint evidence when the cost policy predicts a lower
total cost; it may not create an interaction or add a family after seeing the result.

One semantic-evaluator request may decide multiple applicable atomic assertions from the same retained execution. The
response must preserve one schema-valid verdict and evidence citation per assertion. Evaluator-protocol validation must
compare batched and isolated decisions for cross-assertion interference; batching is permitted only for assertion
families and batch sizes that pass the manifest's accuracy, false-pass, reproducibility, injection, cost, and latency
gates. A disagreement protocol reruns only the assertion decisions whose approved protocol requires adjudication.

Content-addressed receipts may reuse contract parsing, immutable source inventory, deterministic-control validation,
evaluator-protocol validation, build artifacts, runner verification, environment setup, and generated prompt test
instances only while every determining fingerprint and policy version is unchanged. Stochastic executor or evaluator
outcomes never transfer to a changed candidate, contract, generated prompt test, evaluator protocol, or environment.

CAT reports client work as run count, token count when available, elapsed time, and capacity consumption. It reports a
client-side dollar estimate only when a metered provider price or a customer-supplied valuation rule makes that amount
determinable. A flat model subscription price is not divided into an invented per-run charge.

#### 5. Freeze a candidate batch

The development policy identifies eligible candidates from retained development evidence and supplies them to the
batch-freeze operation. That operation selects the exact count and ordering required by the simulation-approved batch
policy, verifies that no candidate has prior formal results in the batch, fingerprints each complete prompt system and
contract, and returns an immutable batch identifier. If too few candidates are eligible, it returns `resume
development`; if an input changes during freezing, it returns `stale input` and freezes nothing. One is the
pre-simulation safe default. The production policy may select 2, 3, or 5 only after untouched simulation validation
establishes a lower expected combined cost than the champion-first baseline.

Before a candidate receives any formal outcome, the statistical engine assigns it the next candidate allowance from the
versioned schedule. The append-only ledger rejects an assignment that would make candidate allowances across every
certification process and linked contract in the optimization lineage sum to more than 5%. Every certification test for
a prompt-test family uses that full candidate allowance because certification and `Current lower bound` concern the
scalar weakest-family rate.
The simulation-approved manifest fixes the allowance schedule; a runtime agent cannot choose it. Starting a new
session, changing agents, restoring the original prompt, or creating an adaptively selected contract does not create a
new allowance for the same deployment objective.

#### 6. Allocate prompt-test runs adaptively across candidates and families

The proof-covered statistical engine is the sole owner of formal allocation and stopping. Given the immutable batch,
completed-trial ledger, evaluator-protocol validation record, and candidate-allowance ledger, it returns one next
action: sample one named candidate and prompt-test family, certify a named candidate, reject a named candidate for
futility, complete the batch without certification, or report invalid evidence.
In particular:

- It stops spending on a candidate only when the approved futility boundary rejects it.
- It stops sampling a family only when the approved bound says additional evidence from that family cannot affect the
  current batch decision, or the approved certification boundary for that prompt-test family is crossed.
- It concentrates trials on uncertain or limiting families.
- It may use SPRT or another proof-covered futility test to reject a weak candidate early. A futility
  result cannot certify a candidate.

Different families and candidates may therefore receive different numbers of trials. Certification does not require
one execution to exercise every family or equal trial counts. A formal trial draws from one prompt-test family's
recorded distribution and checks every requirement applicable to that prompt test. One execution may contribute to
more than one family only when the contract defined that joint scoring before the result was observed and the
statistical method preserves the resulting dependence.

After every completed formal batch, the statistical engine verifies the append-only trial ledger and
candidate-allowance ledger, calculates each candidate's selection-adjusted family bounds and weakest-family lower bound,
and records the terminal batch result. A candidate becomes `certified` only when every family bound, and therefore
`Current lower bound`, is above 95%.

#### 7. Continue after an unsuccessful batch

If no candidate certifies, CAT retains the best validated candidate and resumes development optimization. The best
validated candidate is the one with the highest valid weakest-family lower bound under the current contract.
Ties prefer, in order, fewer family regressions, lower measured execution cost, and the smaller prompt-system change.

A later formal batch uses part of the lineage error budget that remains. Because later batches must keep the total
chance of a false certification within 5%, they will usually need stronger evidence and may cost more or remain
inconclusive longer. CAT must express that consequence as an estimated trial range, money range, and probability of
reaching the target—not as a request to choose an error-allocation mechanism.

There is no arbitrary limit on development rounds or formal batches. At every round boundary, the readiness operation
applies the cost policy or its recorded fallback to select the next automatic development, repair, formal, or terminal
action. `Plateau` finishes the certification process as `not certified`. An unavailable cost forecast is reported but
does not block the locally run workflow. The user may interrupt the enclosing harness session at any time.

#### 8. Recover after formal failure

A one-shot product could certify without candidate-selection controls: freeze one candidate and contract before formal
data is observed, predeclare fixed trial counts, construct a weakest-family lower bound, and permit exactly one formal
attempt. It would still need that valid bound, but it would not need to account for selecting
among candidates or retrying after observed failures. If that attempt failed, certification of the same objective would
end. CAT does not select this design because automatic repair and continued optimization are core capabilities.

After a candidate or batch fails, CAT may return to unrestricted development, repair the target or surrounding system,
and then test new frozen candidates using the same optimization lineage's remaining statistical protection. Fresh
prompt-test draws, a new agent, a new random seed, a restored prompt, a rewritten prompt, a new holdout set, or a
material repair selected because of the failure does not create a fresh 5% lineage error budget. Those actions can
improve the candidate and lower expected testing cost, but they remain adaptive retries toward the same objective.

CAT may start another certification operation for the same prompt file at any time under that existing lineage. The
restriction concerns resetting statistical protection, not whether an existing file may be tested again. A prompt edit
made during optimization creates a new candidate in the same lineage; an unchanged prompt may also become a new
candidate when its recorded surrounding environment changes.

A fresh 5% lineage error budget requires a new optimization lineage. CAT creates one only when all of these conditions
hold:

- An externally required change creates a materially different deployment objective, population, or requirement set.
- That change was not proposed, selected, or shaped using the earlier formal outcomes.
- The preceding objective is closed; CAT will not later choose between the old and new environments based on which one
  produced a certificate.
- The new contract, environment, reason for the change, and lineage boundary are recorded before new formal evidence is
  observed.

The lineage-boundary operation defaults every changed contract to the existing lineage. It returns `fresh lineage`
only after it verifies a user-supplied external-change record, its time and source, the mandatory new deployment
objective, closure of the old objective, and the versioned lineage policy's evidence that earlier formal results did not
select or shape the change. A missing field or ambiguous causal relationship returns `linked lineage`; an agent cannot
override that result.

Examples include user-authored product requirements that arise independently of certification and an upstream model,
harness, or system-prompt release that the deployment must adopt. A change CAT recommends because certification failed
does not meet this gate. If no external change meets it, CAT either continues under the existing lineage, retains and
reports the best current lower bound, or stops certification. Elapsed time and a new billing period do not reset the
lineage.

#### Workflow state and handoff contract

Every local operation receives immutable input identifiers and an idempotency key. Operations in the certification
process also receive the frozen contract and lineage identifiers. Each operation writes
`pending`, `running`, and exactly one terminal result with input and output fingerprints, evidence locations, observed
model usage, estimated provider cost, and its mechanically selected successor. A later step may consume only a verified
terminal result. Retrying the same idempotency key returns that result and cannot repeat a model call; changed inputs
require a new operation and key.

Preparation identifies the target entry file and edit boundary. An invalid preparation returns `not started`.
`start-certification-process` then freezes the determining inputs needed for execution. CAT exposes no pause, resume,
or stop operation. The user may interrupt the enclosing harness session; CAT then makes no further model calls until a
later invocation recovers the durable certification process.

The local operation registry gives each transition one authoritative owner. The prompt-system service owns
`discover-prompt-system`, `analyze-prompt-system`, and change-manifest validation; the contract engine owns
`extract-contract` and `validate-candidate-contract`; the evaluator registry
owns `validate-evaluator-protocols`; the validation controller owns `validate-deterministic-controls`; the cost
estimator owns `forecast-cost`; the optimizer owns `generate-candidates`; the development-policy engine owns
`compare-development-candidates` and `decide-readiness`; the
repair engine owns `repair-prompt-system`; the batch service owns `freeze-candidate-batch`; the statistical engine owns
`assign-allowance`, `schedule-trial`, `record-outcome`, `decide`, and `report`; the progress reporter owns
`report-optimization-round`; the lifecycle controller owns `retry-operation`; and the service that controls the
certification process owns `start-certification-process`, `recover-certification-process`, and
`finish-certification-process`.

Their permitted result and successor pairs are:

1. `discover-prompt-system` returns `discovered` with the initial observation inventory to `analyze-prompt-system`, or
   `blocked` to the preparation result `not started`.
2. `analyze-prompt-system` returns the immutable target identity, edit boundary, initially related files, and evidence
   edges to `extract-contract`, or `blocked` to the preparation result `not started`.
3. `extract-contract` returns `ready` to `validate-evaluator-protocols`. `Blocked` returns `not started` during
   preparation or `not certified` with reason `invalid evidence` during an active certification process.
4. `validate-evaluator-protocols` returns `eligible` to `validate-deterministic-controls`. It returns
   `uncertifiable` as `not started` during preparation or `not certified` during an active certification process.
5. `validate-deterministic-controls` returns `passed` to `forecast-cost`. `Blocked` returns `not started` during
   preparation or `not certified` with reason `invalid evidence` during an active certification process.
6. `forecast-cost` records `forecast available` or `cost unavailable`. During preparation either result proceeds to
   `start-certification-process`; during an active certification process either returns to `generate-candidates`. The
   unavailable result is visible in the next report but does not block local execution.
7. `start-certification-process` verifies that every preparation receipt and determining fingerprint is current, then
   returns `started` to `generate-candidates`; otherwise it returns `stale preparation` as `not started`. `Started` is
   the boundary after which no user decision controls the certification process's progression.
8. `generate-candidates` returns an immutable challenger set to `compare-development-candidates` or `no eligible
   challenger` to `decide-readiness`.
9. `compare-development-candidates` returns `promote`, `reject`, `more pairs`, or `repair regression` to the next
   `compare-development-candidates`, `generate-candidates`, or `decide-readiness` selected by the complete decision
   table in the frozen development-policy manifest, or `plateau` to `decide-readiness`.
10. `decide-readiness` selects exactly one result: `continue development`, `repair prompt system`, `start formal batch`,
    `edit boundary exceeded`, or `plateau`. Every result first goes to `report-optimization-round` with its mechanically
    selected successor.
11. `report-optimization-round` persists and emits the round cost, cumulative cost, `Current lower bound` or
   `unavailable`, best validated candidate, limiting evidence, and next automatic action. Delivery failure cannot block
   progression: the report remains local for the next connected client. `Reported` routes `continue development` to
   `generate-candidates`, `repair prompt system` to `repair-prompt-system`, and `start formal batch` to
   `freeze-candidate-batch`. The remaining readiness results route to `finish-certification-process` as `not certified`
   with their exact reason.
12. `repair-prompt-system` produces an isolated challenger snapshot and change manifest. It may add, split, rename,
   delete, or edit related prompt files inside the edit boundary. The change guard returns `valid` to
   `validate-candidate-contract`; `edit boundary exceeded` to `finish-certification-process` as `not certified`; or
   `invalid change` to `generate-candidates` after restoring the champion.
13. `validate-candidate-contract` verifies the challenger against every frozen requirement, family, interaction,
   applicability rule, and load condition. `Preserved` routes to `compare-development-candidates`; `contract changed`
   or `invalid evidence` rejects the challenger and returns to `generate-candidates` without changing the champion.
14. `freeze-candidate-batch` returns an immutable batch to `assign-allowance`; `return to development` to
   `generate-candidates`; or `stale input` to `finish-certification-process` as `not certified` with reason
   `environment changed`.
15. `assign-allowance` returns `assigned` to `schedule-trial`, or `lineage allowance exhausted` to
   `finish-certification-process` as `not certified`. `Schedule-trial` returns `scheduled` to `record-outcome` or `no
   valid schedule` to `decide`.
   `Record-outcome` returns `recorded` to `decide`, `infrastructure failure` to `retry-operation`, or `invalid evidence`
   to `finish-certification-process` as `not certified`. `Decide` returns `sample` to `schedule-trial`, or `certified`,
   `futility`, or `batch not certified` to `report`. `Report` returns `reported certified` to
   `finish-certification-process`, or `reported unsuccessful batch` to `decide-readiness`.
16. `retry-operation` applies the frozen retry count and backoff without changing inputs. It
   returns `retry` to the failed operation or `retry exhausted` to `finish-certification-process` as `not certified`
   with reason `infrastructure failure`.
17. `recover-certification-process` runs automatically when a client starts and finds a nonterminal certification
   process without a live owner. It verifies every retained terminal receipt, finishes or retries only the active atomic
   operation, and invokes the next registered successor. It never waits for a user resume request.
18. `finish-certification-process` returns exactly `certified` or `not certified`, with the best valid candidate,
   current lower-bound state, diagnostics for limiting prompt-test families, terminal reason, observed usage, estimated
   provider cost, and evidence status.
   It returns `certified` only from a verified `report` result that crossed every certification boundary. Every other
   registered terminal predecessor produces `not certified`; no user-facing operation may invoke it directly.

Before any operation reads or writes prompt-system content, a common change guard verifies the current prompt-system
snapshot and the proposed change manifest. A changed existing file must be related to the target; a created file must
become reachable from the target's recorded prompt-system graph; every write must stay inside the edit boundary; and
the target entry file must remain stable and loadable. An unrecorded external change returns `environment changed`; an
out-of-bound write returns `edit boundary exceeded`; and an invalid relation or load path rejects the challenger and
restores the champion. None of these results performs the planned model call.

The operation schema for each registry entry declares its operation identifier, owner component, required immutable
input-schema identifier, constrained result enum, result-specific successor set, model-cost behavior, evidence-schema
identifier, idempotency behavior, and recovery boundary. The state-machine validator rejects
an omitted input, unknown result, unregistered successor, successor whose required evidence is absent, unauthorized
choice, and an unbounded cycle that lacks a simulation-defined plateau transition. It also rejects any transition from
an active certification process to a user-choice, pause, resume, or stop operation, a write without a valid change
manifest, or a transition that replaces the target identity.

Every operation manifest and receipt uses this canonical envelope:

```text
Operation definition
- operation identifier: closed OperationId value
- version: immutable policy version
- owner component: closed ComponentId value
- request schema identifier and result schema identifier
- result enum: finite values for this operation
- transition table: result value -> finite permitted successor OperationId set
- model-cost rule: no model call or measured local provider usage
- idempotency rule and recovery boundary

Operation request
- operation instance identifier and idempotency key
- operation, policy, contract, certification-process, and lineage identifiers
- predecessor receipt identifiers
- immutable input snapshot identifier and determining-input fingerprints
- observation-inventory, optimization-target, edit-boundary, and candidate-snapshot identifiers

Operation receipt
- operation instance identifier, request fingerprint, owner component, and policy versions
- lifecycle state: pending, running, or terminal
- terminal result value when terminal
- immutable output fingerprint and evidence-receipt identifiers
- observed model usage and exact estimated provider cost carrying one matching currency when a price is available
- finite permitted successor set and required successor-input identifiers
- reproducibility state: available or unavailable, with missing evidence identifiers
- started and completed timestamps and signed receipt fingerprint
```

`Money` is a domain value, not a floating-point primitive. The local cost contract fixes its accepted exact-decimal
form, currency, scale, operation order, and rounding at estimation, persistence, and display boundaries. A
currency mismatch is invalid unless a separately authorized conversion contract supplies its exact rate and rounding
policy.

The build produces one versioned operation-registry manifest containing this envelope and every result/successor pair
above. Runtime code deserializes raw values once into the closed operation, component, result, lifecycle,
reproducibility, and money domain types. It does not accept an arbitrary string at a domain boundary. Registry tests
cover at least one accepted and one rejected value for every closed enum and every operation-specific result enum.

Each non-success result names the failed condition and a finite permitted recovery set. The agent explains these
structured results; it does not reconstruct state, thresholds, prices, statistics, or recovery steps from prose.

### User-visible progress and decisions

#### Compliance reporting

After every completed optimization or certification round, CAT reports the best formally validated candidate directly:

```text
Current lower bound: 92.8%
Target: above 95%
Optimization target: write.md
Related files changed: review.md
Best validated version: v14
Amount spent: $23.10

Limiting prompt-test families:
- Cross-file interaction: 12 formal trials, 2 failures, not yet above target
- Direct instruction: 18 formal trials, 1 failure, not yet above target
```

`Current lower bound` changes only when the statistical engine records new valid formal evidence for a candidate. A
development-only round reports the retained value unchanged and labels the round's new results as development evidence.
If no candidate has valid formal evidence under the current contract, it reports `Current lower bound: unavailable`.

The response uses only the short labels above. Product documentation supplies these footnotes; CAT does not append the
technical definitions to each response:

- **Current lower bound:** the greatest retained selection-adjusted weakest-family lower bound under the active
  contract. The best validated candidate owns that value. Its bound is the minimum of that candidate's valid
  certification bounds for its prompt-test families and applies to its true weakest-family future pass probability. CAT
  uses a 95% confidence level across the optimization lineage, meaning its procedure has at most a 5% chance of ever
  displaying this scalar lower bound above the selected candidate's true weakest-family pass probability under the
  recorded contract. The displayed percentage is a one-sided lower-bound endpoint, not a model confidence score or a
  two-sided confidence interval.
- **Target:** every prompt-test family's selection-adjusted certification bound, and therefore `Current lower bound`,
  must be above 95% before CAT may report `certified`.
- **Optimization target:** the one prompt file whose future compliance the workflow optimizes and certifies.
- **Related files changed:** the prompt files added, split, renamed, deleted, or edited in the reported candidate. The
  list describes that candidate; it is not an optimization scope or a compliance target.

CAT identifies limiting prompt-test families using their formal trial counts, failures, boundary status, cost, and
projected work when available. It does not display a formal numeric lower bound for an individual prompt-test family
because the product does not spend a separate simultaneous-reporting allowance. If the candidate has only development
evidence, CAT labels its numbers as a development estimate and does not use the `Current lower bound` label.

When a prompt, requirement, test, evaluator protocol, prompt-test distribution, or recorded environment change
creates a new certification contract, CAT immediately clears the active lower bound and displays:

```text
Current lower bound: unavailable
Target: above 95%
Reason: no formal evidence exists for contract <new-contract-id>
```

The preceding bound remains immutable in the prior contract's history and may appear only under `Historical lower
bound`. Development estimates for the new contract may appear under `Development estimate`; neither value may populate
`Current lower bound`. That label becomes numeric again only after the statistical engine records valid formal evidence
for the new contract.

The `certified` result appears only after `Current lower bound` crosses the target. Until then, the displayed percentage
and target show exactly how far the best candidate has progressed without suggesting that adaptive development evidence
is a guarantee.

#### Automatic round reporting and interruption recovery

After every completed optimization round, CAT retains the best validated candidate, writes one immutable round report,
emits it to an attached client, and immediately invokes the mechanically selected successor. The report is
informational; it contains no continue, pause, resume, or stop control. CAT retains an undelivered report locally and
displays it on the next invocation.

The controller for the certification process owns execution while the harness session remains active. The user may
interrupt or close that session at any time. CAT then starts no further model call, retains every completed atomic
result, and records the active operation as interrupted unless its independently owned child already reached a
verifiable terminal result. The next invocation verifies those receipts, reuses completed work, and retries only the
uncompleted operation before continuing from the registered successor.

Each report identifies the completed round, cost, lower-bound state, selected next action, and the evidence-based
reason.
For example:

```text
Optimization round: 6 complete
Current lower bound: 92.8%
Target: above 95%
Optimization target: write.md
Related files changed: review.md; added write-examples.md by splitting write.md
Round cost: $3.42
Total cost: $23.10
Next automatic action: repair the prompt-system conflict in write.md and review.md
Reason: seven of the last nine failures occurred only when those two files were loaded together; the frozen
cost policy estimates repair and recertification at $14–$24, below the $28–$46 estimate for target-only optimization.
```

When the selected action changes the prompt system, the report states that CAT is editing an isolated challenger and
that the retained champion and its `Current lower bound` remain unchanged until stronger formal evidence promotes the
challenger. When the cost policy returns `no preference` or conflict diagnosis returns `not localized`, the automatic
policy continues with the target because that action changes fewer artifacts.

### Automatic prompt-system conflict repair

CAT can automatically diagnose prompt-system conflicts and repair related prompt files inside the edit boundary.
Repair, contract-preservation validation, and formal testing of the repaired candidate are automatic after the readiness
and cost policies select that path. CAT may diagnose observation-only artifacts, third-party system prompts, or harness
behavior, but it cannot edit them.

Before offering this path, CAT must:

1. reproduce each remaining failure by its retained stable signature under the recorded environment;
2. run paired controlled ablations and interaction trials under the development policy, returning `localized` only when
   that policy's exact interaction boundary attributes the failures to another loaded artifact or conflict;
3. name the artifacts and clauses involved;
4. ask the cost-policy operation for the cost of continuing target-only optimization;
5. ask the same operation for the combined cost of conflict repair, regression validation, development retuning, and
   certification in the changed environment; and
6. recommend conflict repair only when the ablation evidence supports that cause, the cost policy selects the repair
   path, and the change guard accepts every proposed write. If either forecast is unavailable, state that fact and apply
   the development policy's recorded fallback.

CAT applies a selected repair only in an isolated challenger snapshot. It validates the change manifest, every affected
prompt, the resulting load graph, and preservation of the frozen certification contract. If the repair changes a
requirement, applicability rule, family, distribution, evaluator protocol, or required interaction, CAT rejects the
challenger; optimization cannot redefine the standard it is trying to satisfy.

If an evidence-backed repair requires a change outside the edit boundary, CAT returns `edit boundary exceeded` and
automatically finishes `not certified`. The terminal report names the artifact and evidence. A later start request does
not reset selection-aware statistical history when it was chosen from the old results.

The repaired snapshot is a new candidate under the same contract and optimization lineage. It receives no fresh 5%
allowance. Formal evidence from the retained champion remains attached to that champion; the repaired candidate must
establish a stronger formal lower bound before CAT may replace it. Only an external requirement, prompt-test, model, or
harness change that meets the lineage-boundary rules may create a new contract or lineage.

System repair can still reduce expected total cost when it improves the future family outcomes that drive formal trial
counts. The cost-policy operation compares another batch in the old environment with repair, validation, development
checks, and the repaired candidate's first formal batch. It recommends repair only when its calibrated decision rule
selects that path; it does not treat an unobserved true compliance rate as an input. It may not make a cosmetic or
irrelevant system change to obtain another allowance.

### Certificate scope and environment history

Every certificate record identifies the exact values and locally retained content artifacts that can affect its
result. The local certificate history stores:

- opaque identifiers, necessary project-relative paths, and content fingerprints for the target prompt and every loaded
  prompt file;
- opaque identifiers, necessary project-relative paths, and content fingerprints for client-retained prompt tests and
  certification-contract artifacts;
- version identifiers and fingerprints for prompt-test families, prompt-test generators, prompt-test
  distributions, deterministic checks, and semantic evaluator protocols, including model, reasoning profile, prompt,
  rubric, atomic assertions, evidence schema, aggregation, isolation rules, and approved model-variant distribution;
- the certification-contract version and lineage;
- model and reasoning settings;
- harness and runner versions;
- system prompt and available context policy;
- cache, sandbox, network, and tool policies;
- statistical method, confidence level, lineage candidate-allowance ledger, and candidate-selection history; and
- all other execution inputs required by the versioned statistical coverage proof.

CAT compares the active system with that snapshot. When a recorded value changes, CAT names the old and new value,
shows the affected scope, labels the certificate `Stale certificate`, and clears the active `Current lower bound`. The
old numeric bound remains only in historical evidence. The user decides whether the difference justifies retuning or
recertification.

### Local prompt optimization and intellectual property

The Work distribution contains candidate-generation strategy, failure diagnosis, scheduling of the certification
process, prompt-test generation logic, cost prediction, evaluator protocols, and certification-history behavior. All of
those operations run on the customer's machine. The local client invokes the customer's selected harness and
model-provider account; it does not send prompt, test, trace, evaluator, or certification content to CAT's backend.

A current Work license unlocks prompt tuning, evaluation, and certification. The customer is responsible for
model subscription limits and API charges. CAT measures provider usage when the provider exposes it and uses exact
monetary arithmetic for estimates. It publishes the provider price source, currency, units, rounding, and
unavailable-price behavior, but does not authorize or limit the customer's local model spending.

The local cost-policy operation provides estimated ranges for initial optimization, later prompt changes, model
changes, and certification only inside its calibrated applicability envelope. These ranges are planning estimates, not
fixed prices or guaranteed completion times. Outside that envelope, the UI displays `No reliable certification cost
forecast is available` and the unsupported inputs. During an operation, the UI shows estimated model-provider cost,
elapsed time, current lower-bound state, and projected remaining cost when available.

CAT protects the Work implementation through the Work distribution, license checks, release access, and
ordinary intellectual-property law rather than by moving prompt optimization to a service. The plan does not promise
that client-side execution makes the implementation impossible to inspect or copy.

Semantic certification sends the bounded retained evidence named in the evaluator-protocol preflight from the local
harness to the customer's selected model provider. Before the model call, CAT previews the data categories, model
provider, estimated evaluator volume, and the provider's applicable retention setting. No CAT account permission can
override the customer's model-provider configuration. A customer who does not authorize that local model call may
continue with deterministic checks and non-certifying development but cannot receive `certified` for semantic
assertions.

Certification paths, fingerprints, traces, and history remain local. They can still be confidential, so CAT minimizes
the stored fields, avoids unnecessary absolute paths, uses project-scoped storage, and documents local deletion and
export. CAT receives none of this history through ordinary optimization.

Changes to the global failure taxonomy require CAT-staff approval in reviewed batches. Agents may propose taxonomy
changes, but they may not publish them automatically.

### Defensible assets

CAT should build and protect these product assets:

- a human-governed failure taxonomy;
- prompt-test generation data and generator revisions;
- explicitly submitted `$cat:feedback` examples and privacy-filtered opted-in Usage analytics;
- an evaluation corpus;
- cost and completion-time prediction models; and
- local certification and environment-history formats and tooling.

The commercial license earns revenue from CAT-authored prompt tuning, evaluation, certification, compatibility updates,
and commercial-use rights. CAT does not depend on metered backend work for revenue.

### Statistical coverage proof and simulation

CAT owns the written coverage proof below and a versioned machine-readable statement of its assumptions. Production may
not launch until the statistical implementation names the exact anytime-valid Bernoulli construction it uses, passes
deterministic numerical controls and property tests for that construction, and passes the simulation gates in this
section. An independent statistician is not a product or launch requirement. Simulation can expose a broken
implementation or impractical policy, but it does not replace the proof.

#### Coverage construction and proof

For candidate `c` and prompt-test family `f`, let `p(c,f)` be the probability that one fresh formal trial drawn from the
family's frozen distribution receives a final `pass` from the frozen evaluator protocol. The proof requires all of
these conditions:

1. The candidate, contract, family distribution, evaluator protocol, and recorded environment are frozen before that
   candidate's formal outcomes are observed.
2. Every counted outcome is a fresh draw from the recorded trial process. Unrecorded shared state may not make the
   result depend on earlier outcomes. A detected change makes the contract stale; an infrastructure failure contributes
   no `pass` or `fail` and cannot be retried selectively based on semantic content.
3. Before candidate `c` produces formal data, CAT assigns it an allowance `alpha(c)`. The assignment may depend on past
   certification processes, but the ledger must keep the sum of all `alpha(c)` values in the optimization lineage at or
   below 0.05 on every possible execution path.
4. Every family for candidate `c` uses a one-sided anytime-valid Bernoulli certification test at level
   `alpha(c)`. Under the null claim `p(c,f) ≤ 0.95`, the chance that the family ever crosses its certification boundary
   is at most `alpha(c)`, even when CAT chooses when to inspect or stop that family from prior results.

One directly checkable way to obtain condition 4 is an e-process. For outcomes `X_i` equal to 1 for pass and 0 for
fail, choose each betting value `lambda_i(p0)` from prior data only, with `0 ≤ lambda_i(p0) ≤ 1 / p0`, and calculate:

```text
E(n, p0) = product over i=1..n of [1 + lambda_i(p0) × (X_i − p0)]
```

If the conditional pass probability is at most `p0`, the next factor has conditional expectation at most 1 and is
nonnegative. `E(n, p0)` is therefore a nonnegative supermartingale starting at 1. Ville's inequality then limits the
chance that it ever reaches `1 / q` to `q` for a predeclared level `q`. Setting `q` to `alpha(c)` and `p0` to 0.95 gives
the certification test. Inverting the same predeclared monotone family of tests produces each internal family bound;
their minimum is `Current lower bound`. The production manifest must pin the betting or mixture rule and numerical
inversion; a runtime agent cannot choose them. The construction follows the time-uniform guarantee formalized for
[confidence sequences](https://arxiv.org/abs/1810.08240).

Now consider any candidate that does not meet CAT's target. At least one prompt-test family, call it the weak family,
has `p(c,f) ≤ 0.95`. CAT certifies the candidate only if every family crosses its boundary. A false certification is
therefore a subset of the event that this weak family's test crosses incorrectly. Its probability is at most
`alpha(c)`. This is why every family may use the full candidate allowance: no independence assumption and no
division of that allocation by the family count are required for the certification decision.

For candidate `c`, let each family's internal certification bound be `L(c,f)`, let `B(c)` be the smallest `L(c,f)`, and
let `M(c)` be the candidate's true weakest-family pass probability. If `B(c)` exceeds `M(c)`, it must exceed the true
rate of a family that attains `M(c)`. That family's anytime-valid coverage failed, an event with probability at most
`alpha(c)`. A false certificate is a subset of the same event because CAT certifies only when `B(c)` exceeds 95% while
`M(c)` does not.

An overstated `Current lower bound` anywhere in the optimization lineage must therefore occur for at least one tested
candidate. By the union bound, its probability is at most the sum of the candidate allowances, which the ledger keeps
at or below 0.05. The same argument permits adaptively generated candidates, unequal family trial counts, optional
stopping, and an unlimited number of retries when the allowance schedule is summable. Adaptive sampling of a family is
allowed only when its next sampling decision uses prior information and its anytime-valid test remains valid at those
sampling times. Correlation among family results does not change the proof. This candidate-allocation step is an
alpha-spending construction of the same kind used for
[online familywise error control](https://arxiv.org/abs/1910.04900).

This proof covers the evaluator-defined outcome in the recorded contract. Evaluator-protocol validation, drift rules,
and adversarial controls determine whether that outcome remains a useful interpretation of the prompt requirement; CAT
does not multiply a statistical lower bound by a separate evaluator-validity bound.

The simulation suite must challenge candidate selection, adaptive family sampling, repeated attempts within one
certification process, dependence when one execution scores multiple families, sequential stopping, `Current lower
bound`, stale-environment handling, and evidence reuse. It must include:

- family compliance below, at, and above 95%, especially values close to the boundary;
- unequal family difficulty and trial counts;
- many prompt-test families, including many interaction prompt-test families;
- champion-first and 2-, 3-, and 5-candidate batches in which no candidate certifies;
- repeated certification processes and batches under one persistent lineage candidate-allowance ledger;
- correlated family outcomes;
- early futility rejection and long inconclusive runs;
- prompt and environment changes between certification processes;
- prompt-system candidates that edit existing related files, add companion files, split files, and attempt an
  unrelated or out-of-bound change; and
- realistic local model-provider usage, cost, and elapsed-time distributions, including unpriced subscription capacity.

It must also simulate evaluator behavior separately from executor behavior: semantic-evaluator false passes and false
failures, correlated evaluator errors, reproducibility failures, position and self-preference bias, prompt injection,
one-evaluator protocols, model-adjudication protocols, and contracts that mix deterministic checks and semantic
protocols. It must verify that the final protocol verdict becomes exactly one Bernoulli formal outcome and that an
infrastructure failure cannot be selected or discarded according to semantic content. Known-control success alone is
not valid simulation evidence for these claims.

The simulation must also cover development/readiness/formal stream dependence and seed reuse, scoring one prompt test
against multiple prompt-test families,
batched semantic assertions with correlated evaluator errors, content-addressed reuse of unchanged deterministic work,
rejection of stochastic evidence reuse, unforecast operations, deletion of client evidence after a signed historical
result, and candidate topology changes. It must reject an unrelated or out-of-bound write while accepting a valid
addition or split that preserves loading and contract coverage.

#### Simulation-owned development policy

The simulation runner evaluates a finite, versioned set of candidate development policies. Each candidate fully defines
all of these values; a prompt or runtime agent supplies none of them:

- `material regression`: the exact paired-outcome decrease, affected prompt-test families, minimum exposure, and
  uncertainty rule that prevents promotion;
- `development promotion`: initial and incremental pair counts, early terminal boundaries, tie order, regression-repair
  result, and the complete decision table for `promote`, `reject`, `more pairs`, or `repair regression`;
- `readiness batch`: the fresh prompt-test count and permitted failures for each family and interaction stratum, plus
  the rule that maps those observations and retained failures to `continue development` or `start formal batch`; and
- `plateau`: the exact number of consecutive completed rounds, minimum improvement measure, requirements for comparable
  prompt-test families and environments, and reset conditions that produce `plateau`.

Simulation data is split by random seed and generated optimization trajectory into policy-selection and untouched
policy-validation partitions. The runner selects the policy with the lowest mean combined development, certification,
evaluator-execution, and failed-attempt cost on the selection partition. A policy is ineligible if it bypasses a
deterministic or evaluator-protocol gate, exceeds the 5% false-promotion allowance, or causes the formal procedure to
exceed its false-certification constraint. Ties select, in order, the lower 95th-percentile total cost, fewer promoted
candidates
whose simulated weakest-family rate is below the retained candidate, fewer formal attempts, and fewer development runs.

The untouched validation partition must satisfy the formal error gate and report mean and 95th-percentile cost,
certification probability, false-promotion frequency, time to readiness, and
plateau frequency for each combination of the total number of prompt-test families and the number of interaction
prompt-test families. If the selected policy fails a required
statistical gate or does not outperform the conservative no-early-promotion baseline on mean total cost, the launch
gate fails; CAT does not tune thresholds against the validation partition. The simulation input contains that baseline
as a complete versioned policy manifest; “conservative baseline” is not a runtime judgment.

The accepted output is a signed, machine-readable development-policy manifest containing every selected value, the
simulation and cost-model versions, validated applicability envelope, metrics, and fallback decisions. Runtime
operations validate and apply that manifest directly. A later simulation may publish a new policy version for future
operations but may not reinterpret an earlier decision or certificate.

#### Cost-model and feasibility simulation

The simulation enumerates family counts, interaction counts, evaluator-protocol mixes, dependence structures,
remaining lineage error budgets, candidate counts, and prices. For every validated input band it
records calibrated run, evaluator-execution, money, and elapsed-time ranges plus prediction error. The cost policy's
applicability test accepts
only those bands. An input outside them deterministically produces `cost unavailable`; it may not be extrapolated by an
agent. This table is the source used by the pre-execution certification-feasibility gate to detect commercially
infeasible or unsupported contracts before paid inference.

The initial cost policy is bootstrapped from a CAT-funded reference corpus executed through every initially supported
harness, model profile, evaluator protocol, total number of prompt-test families, number of interaction prompt-test
families, candidate count, and
error-budget band that the first release intends to accept. CAT splits complete optimization trajectories—not individual
runs—into policy-selection, calibration, and untouched validation partitions. A band becomes forecastable only when the
validation partition meets the manifest's prediction-error and interval-coverage thresholds. Synthetic simulations may
expand stress coverage but cannot by themselves make a real-money band forecastable.

After launch, completed operations may improve a future cost-policy version only through fields permitted by the Usage
analytics policy and applicable customer authorization. Policy training never changes an estimate already reported for
a completed operation. Until a band becomes calibrated, `cost unavailable` lets CAT collect useful execution evidence
without inventing a price range.

#### Workflow-conformance gate

Before launch, an executable state-machine test starts from a clean local project and exercises every terminal result
listed in the workflow handoff contract, including missing inputs, stale fingerprints, evaluator disagreement,
unsupported family counts, interruption, retry, failed formal batches, conflict repair, related-file creation and
splitting, rejected unrelated and out-of-bound writes, environment changes, contract changes,
and a successful certificate. For every transition it verifies the owning operation, required inputs, immutable output,
provider-usage effect, permitted successors, and user-visible message. The gate fails if an agent
must invent a threshold, forecast, statistical setting, state reconstruction, or recovery step; if a result has no
bounded successor; or if a later step accepts nonterminal or mismatched evidence.

#### Statistical acceptance gate

Across false candidates and every permitted adaptive path, the one-sided 95% upper confidence bound on the simulated
frequency of an overstated `Current lower bound`, including any false certification, must be at or below 5%. The gate
reports false certifications separately as a diagnostic subset. Passing this empirical gate does not replace CAT's
coverage proof. The same simulations select the production batch size, summable candidate-allowance schedule, stopping
rules, assertion-family evaluator protocols, development policy, and cost-prediction safety margins. CAT
publishes the externally relevant guarantee and method revision with each certificate; it does not ask customers to
choose among statistical implementations.

#### Development-promotion acceptance gate

The implementation must include a written coverage argument showing that each adaptive challenger receives its
comparison allowance before fresh paired outcomes are observed, every family comparison is simultaneous under that
allowance, and the append-only promotion ledger keeps all comparison allowances in one optimization lineage at or
below 5%. A false promotion is any accepted challenger whose true weakest-family future pass probability does not
exceed the champion's under the frozen reference distributions, or whose true family change crosses the
material-regression boundary under those distributions.

Across simulated false challengers and every permitted adaptive comparison path, the one-sided 95% upper confidence
bound on false-promotion frequency must be at or below 5%. The suite must include equal candidates, improvements in one
limiting family, regressions hidden in a non-limiting family, candidate topology changes, repeated inconclusive
comparisons, and adversarial candidate selection from prior results. Failure blocks launch; empirical success does not
replace the written coverage argument.

### Implementation sequence

#### Stage 1: Freeze schemas and ownership

Implement and version the observation-inventory, optimization-target, edit-boundary, related-file evidence,
certification-contract, extraction-policy, evaluator-policy, development-policy, cost-policy, operation-manifest,
evidence-receipt, trial-ledger, promotion-allowance-ledger, candidate-allowance-ledger, and certificate schemas.
Implement the state-machine validator that rejects an unknown result, missing successor, changed immutable input, or
operation without one owner. It must also reject target replacement, an unrelated or out-of-bound write, and a split
whose extracted content is not loaded wherever the original content applied. Acceptance evidence is a schema
and state-machine suite that exercises every registered result-and-successor edge, every terminal outcome of the
certification process, and every failure path in this plan, including interruption and idempotent retry.

#### Stage 2: Build the client execution and evidence boundary

Implement signed operation-manifest verification, bounded content requests, local harness execution, immutable client
snapshots, structured evidence return, transient-content deletion receipts, and reproduction-status reporting for one
supported harness and model profile. Acceptance evidence is an end-to-end run showing that an allowed request executes,
an undeclared input request is rejected, disconnect finishes only the active atomic operation, and deleting client
evidence leaves a signed historical result marked `Reproduction: unavailable`.

#### Stage 3: Replace the statistical engine

Implement `prompt-test-family-sequential-v1`, its exact e-process or mixture, numerical inversion, candidate allowance
schedule, adaptive scheduling, futility boundary, immutable ledgers, and reports. Acceptance evidence is
the deterministic numerical suite, property tests, proof-assumption checks, adversarial simulations, legacy-evidence
rejection, and an end-to-end certificate using a version-controlled fixture contract through the Stage 2 harness
boundary.

#### Stage 4: Implement contract extraction and evaluator protocols

Implement effective-context discovery, independent prompt-system analysis, related-file evidence, edit-boundary and
change-manifest validation, the extraction-policy operation graph, deterministic reducers, blocked disagreement
path, reference-distribution and independent-stream checks, deterministic assertion controls, semantic evaluator
validation,
optional assertion batching, and injection isolation. Acceptance evidence is the prompt-system discovery and structural-
change matrix, extraction conformance matrix, evaluator corpus gates, known-good and known-bad controls, and a complete
frozen
contract accepted by Stage 3 without a runtime-invented value.

#### Stage 5: Implement development optimization and efficiency policy

Implement failure diagnosis, multi-challenger generation, candidate racing, selection-aware paired comparisons,
readiness pools, champion-first formal promotion, prompt-system repair, file creation and splitting, invariant-work
caching, and best-candidate retention. Acceptance evidence is the untouched simulation comparison against the
conservative and one-candidate baselines plus end-to-end outcomes for statistically valid promotion, rejected
regression, valid structural repair, unrelated and out-of-bound edit rejection, plateau, uninterrupted round
progression, unsuccessful formal testing, and `not certified`.

#### Stage 6: Add local cost measurement, calibration, and production controls

Implement provider-usage accounting when the provider exposes it, the CAT-funded calibration corpus, forecast
applicability, unforecast operation reporting, local retention and deletion controls, signed local
certificate history, and privacy-preserving Usage analytics. Acceptance evidence is a calibrated supported band, an
unsupported band that returns `cost unavailable`, round-by-round observed-cost reporting, and production-like local
`certified` and `not certified` flows that
send no prompt, test, trace, evaluator, or certification content to CAT.

### Planned capabilities

1. Prompt-system discovery, a distinct immutable optimization target, related-file and structural-change validation,
   versioned certification contracts, automatic multi-agent extraction, traceability checks, prompt-test groups, and
   prompt-test generation for every prompt-test family.
2. Deterministic-check controls, validated semantic evaluator protocols, assertion-family-specific model adjudication,
   evaluator-security controls, and pre-execution certifiability checks.
3. Client-directed local execution with provider-usage accounting when available, automatic round-to-round progression,
   immutable round reports, and interruption recovery.
4. Development optimization that applies a simulation-produced policy to paired A/B candidate comparisons, targets the
   lowest-scoring family, and diagnoses prompt-system interactions.
5. Optimization-lineage-protected formal testing with champion-first candidate batches, adaptive
   allocation across candidate and prompt-test family pairs, proof-covered weakest-family `Current lower bound`,
   diagnostics for limiting prompt-test families, and one `certified`
   result.
6. Actionable cost and progress reporting, unsupported-contract preflight, best-candidate retention, and automatic
   repair of related prompt files within the edit boundary, including file creation and splitting.
7. Environment snapshots, `Stale certificate` detection, linked contract history, prompt-test editing, and maintenance
   workflows.
8. `$cat:feedback`, privacy controls, Usage analytics, human-governed failure taxonomy, and cost-model improvement.
9. A written statistical coverage proof, adversarial simulation, evidence-integrity checks, and a launch gate that
   rejects a procedure or implementation outside that proof.
10. Judgment Capture that turns explicit preferences, explained review corrections, accepted examples, and selected
    decision records into reviewable style and explainability contracts grounded in `write-clearly.md` and
    `contract-design.md`.
11. A low-cost `work-execute` subagent that mechanically applies a complete implementation plan and returns
    `BLOCKED_PLAN_NOT_MECHANICAL` before mutation when the plan leaves a decision to the implementation agent.

### Known concerns and required mitigations

- **Distribution validity:** automatic extraction cannot discover future uses absent from the prompt and tests. Preserve
  the exact family definitions and distributions so the user can audit what the certificate covers.
- **Evaluator validity:** certification measures the frozen evaluator protocol's interpretation of each assertion.
  Require atomic assertions, strict cited evidence, a validated evaluator test corpus, and an approved protocol for each
  assertion family; reject an uncertifiable contract before model-consuming trials.
- **Evaluator gaming:** repeated optimization can exploit a stable model judge's biases or blind spots. Keep formal
  evaluator details and adversarial cases withheld, separate evaluator sessions from optimization, test bias and
  manipulation cases, and use model adjudication only where validation shows a material cost-adjusted improvement.
- **Prompt injection:** executor and retrieved content can try to replace the evaluator rubric. Treat all retained
  evidence as data, remove evaluator tool authority, enforce the frozen assertion schema, and reject any protocol that
  follows injected instructions in the adversarial corpus.
- **Family explosion:** many leaves, interactions, expensive evaluator protocols, or a depleted lineage error budget can
  put a contract outside the validated cost envelope. Run the deterministic feasibility gate before model inference and
  report `cost unavailable` instead of extrapolating.
- **Calibration bootstrap:** a new product lacks enough customer history to forecast every contract. Fund a reference
  corpus, validate only supported cost bands, and label unforecast operations without inventing a range.
- **Near-threshold cost:** a true family rate only slightly above 95% can require many trials. Optimize for margin
  before formal testing using the development policy, focus formal trials adaptively, and apply the cost policy or its
  recorded fallback before spending more.
- **Repeated selection:** repeatedly testing new candidates can inflate false certification. Preserve one lineage
  candidate-allowance ledger across every attempt for the same deployment objective.
- **Evaluator drift:** changing the model, reasoning profile, rubric, atomic assertions, evidence schema, aggregation,
  isolation rules, or approved model variants changes the recorded interpretation of compliance. Create a new contract,
  label the old result `Stale certificate`, and require new formal evidence.
- **Environment drift:** surrounding instructions and harness changes can alter compliance. Record them and label the
  prior result `Stale certificate`, clear the active lower bound, and keep its numeric value only in history instead of
  implying transfer.
- **Local evidence loss:** fingerprints cannot reproduce deleted local content. Keep the historical signed
  result, label reproduction unavailable, prevent evidence reuse, and require a new snapshot for another operation.
- **Incorrect related-file classification:** semantic analysis can propose an unrelated edit. Require explicit
  dependency or interaction evidence, enforce the edit boundary, validate the complete resulting load graph, and reject
  the challenger before execution when that evidence is absent.
- **Automatic broad edits:** conflict repair may change behavior outside the target prompt. Keep the target separate,
  isolate each challenger, validate every changed artifact and affected interaction, compare it with the retained
  champion, and retain a rollback path.
- **Statistical implementation risk:** an attractive adaptive algorithm may not support adaptively generated candidates
  or the advertised weakest-family bound. The written coverage proof, deterministic numerical checks, and simulation are
  launch blockers, not post-launch tasks.


## Frontend acceptance gates

The frontend and release work is complete only when retained evidence proves all of these results:

- personal installation, upgrade, and uninstall work from the personal repository for every supported target;
- Work installation and upgrade work from the private repository for an entitled test account;
- clean installation succeeds for Codex and Claude Code on Linux and macOS for `x86_64` and `arm64`, WSL selects and
  installs its matching Linux target, and native Windows is rejected with the WSL instruction;
- every release tag resolves to the tested commit and contains only allowlisted end-user files;
- a 24-hour license lease renews, a CAT outage permits no more than seven days from the last confirmed entitlement, and
  an expired grace rejects Work-only entry points with a recovery action;
- `$cat:feedback` previews and sends only the confirmed payload, failed delivery remains local, accepted delivery
  returns a receipt, and Usage analytics reject a field outside the published allowlist;
- prompt-system discovery records one immutable optimization target and edit boundary; valid related-file edits,
  additions, and splits pass the change guard, while unrelated, out-of-bound, or target-replacing changes are rejected
  before any model call;
- the first Work release completes the local certification acceptance gates and can return both `certified` and
  `not certified` through Codex and Claude Code;
- the legacy optimizer, its prompt-builder/prompt-tester entry points, and every unretained supporting artifact are
  absent from the assembled distributions; and
- both distributions package `work-execute`; a complete-plan fixture produces its specified change and evidence, while
  an ambiguous-plan fixture returns `BLOCKED_PLAN_NOT_MECHANICAL` without changing the fixture; and
- package metadata, installed license text, and client messages agree with the root license and the backend-owned
  versioned offer catalog.

## Detailed implementation plan

The work packages below are dependency ordered. A package is complete only when its named terminal outcome and
acceptance evidence exist. A compile, an intermediate schema, or a started harness process does not complete a package.
New prompt-optimizer code belongs in focused common packages and typed harness adapters; it must not add another
responsibility to a legacy whole-suite evaluation entry point. The reuse audit retires that entry point and retains
only the generic helpers under their current focused owners.

### Mechanical execution contract

This section resolves the implementation choices needed by `work-execute`. It is authoritative when a descriptive
section elsewhere in this document leaves a choice open. The executor must preserve existing working behavior and the
current uncommitted worktree. For each numbered wave, it must add the named tests first, run them to observe the required
failure, implement the named production behavior, and rerun the same tests. It must not commit, publish, push, change an
issue, or mark an external gate passed.

All JSON documents introduced below use schema version `1`, reject unknown fields, accept only the named enum values,
use lowercase SHA-256 fingerprints, and sort identifier-keyed collections before fingerprinting. Text is UTF-8.
Project paths are normalized project-relative paths without `..`; evidence paths are normalized absolute paths. A
single JSON document is limited to 1 MiB, an identifier to 128 characters, a project path to 4,096 characters, open
text to 64 KiB, a list to 4,096 entries, and a map to 4,096 entries. An append-only JSON Lines ledger is unbounded as a
file, but each line is limited to 64 KiB and readers must stream it. Reuse `RecordStore` and `BoundedJsonLines` for these
rules instead of adding another JSON or atomic-file owner.

#### Wave 0 — close the shared contract

1. Add these strict common owners under
   `client/common/cli/src/main/java/io/github/cowwoc/cat/common/optimization/promptsystem/`, with one matching `*Test`
   under the corresponding test package:

   - `ObservationInventoryDocument`: `schema_version`, `inventory_id`, `project_root_sha256`, `invocations`, and
     `artifacts`. An invocation contains `invocation_id`, harness `codex` or `claude`, `target_path`, and ordered
     `loaded_artifact_ids`. An artifact contains `artifact_id`, `path`, `sha256`, load kind `always`, `conditional`, or
     `observation_only`, editable boolean, and ordered `dependency_artifact_ids` and `condition_evidence_ids`.
   - `OptimizationTargetDocument`: `schema_version`, `target_id`, `target_path`, `target_sha256`, and ordered
     `prompt_test_ids`. The target path cannot change within one certification process.
   - `EditBoundaryDocument`: `schema_version`, `boundary_id`, ordered `editable_artifact_ids`, ordered
     `editable_roots`, and ordered `observation_only_artifact_ids`. A new prompt file is permitted only below an
     editable root. The target may be edited but may not be deleted, renamed, or replaced by another entry file.
   - `RelatedPromptEvidenceDocument`: `schema_version`, `evidence_id`, `artifact_id`, `target_id`, relation
     `loads_with`, `affects_requirement`, or `interacts_with`, ordered `source_artifact_ids`, ordered
     `requirement_ids`, and `evidence_sha256`.
   - `PromptSystemChangeManifest`: `schema_version`, `candidate_id`, `target_id`, `boundary_id`, and ordered `changes`.
     A change contains action `add`, `modify`, `delete`, or `rename`, `before_path` when applicable, `after_path` when
     applicable, before and after fingerprints when applicable, and ordered related-evidence identifiers. A split is
     represented by one `modify` of the source plus one or more `add` entries and must include a load-graph validation
     receipt. Validation runs before candidate inference and rejects a missing relation, a path outside the boundary,
     an observation-only write, or target deletion/rename.

2. Extend `CertificationContract` and `CertificationContractDocument` with the extraction fields already specified in
   “Automatic extraction”: requirements, source spans, applicability rules, prompt-test groups, prompt-test families,
   prompt-test distributions, prompt tests, assertions, evaluator-protocol assignments, and disagreements. A prompt
   test is the running-agent request plus its assertions and fixtures; it is not a synonym for a family. Add one fixture
   for every reference failure named in that section. Keep prompt-test bodies and fixtures at client paths; the frozen
   contract records their hashes.

3. Replace only the prompt-optimizer's public and persisted `scenario` terminology using this complete mapping:
   `ScenarioGenerationPolicy` → `PromptTestGenerationPolicy`, `ScenarioGenerationPolicyDocument` →
   `PromptTestGenerationPolicyDocument`, `ScenarioPoolPartition` → `PromptTestPoolPartition`,
   `ScenarioIdentityLedger` → `PromptTestIdentityLedger`, `DevelopmentScenarioScheduler` →
   `DevelopmentPromptTestScheduler`, `FormalScenarioScheduler` → `FormalPromptTestScheduler`, and
   `ScenarioContractCatalog` → `PromptTestContractCatalog`. Rename their matching tests and persisted fields
   `scenario_id` → `prompt_test_id`, `scenario_generator` → `prompt_test_generator`, and `scenario` → `prompt_test`.
   Do not rename the unrelated Java source-check package `io.github.cowwoc.cat.evaluation.scenarios`.

4. Add closed `HarnessKind`, `OperationKind`, `OperationState`, `OperationResultKind`, `ReproductionState`, and
   `ReleaseTarget` types where existing code still accepts a free-form value from one of those finite domains. Convert
   raw JSON and CLI text once at ingress. `HarnessModelProfile` remains open to future model names, but add
   `SupportedModelProfiles` containing exactly Codex `gpt-5.6-luna`/`medium` and Claude `haiku`/`medium`; optimizer and
   certification operations accept only a profile from this registry. Change the Claude runner's current default
   `haiku-4.5` to the selected `haiku` alias and assert both command arguments in `ClaudeRunnerTest`.

5. Extend `OptimizationOperationRegistryTest` so every registry schema identifier resolves to one strict document
   owner and every result has a successor or is terminal. Generate the state-machine matrix from the registry and test
   every edge, both process terminal outcomes, and rejection of pause, resume, stop, target replacement, and an
   unvalidated write.

Wave 0 is complete when its focused tests pass and this command passes with at least one executed test from every named
test class:

```text
client/mvnw -Dmaven.build.cache.enabled=false -f client/pom.xml \
  -pl :client-common-cli,:client-claude-cli,:client-distribution -am \
  -Dtest='*DocumentTest,*RegistryTest,*StateMachineTest,*PromptTest*Test,*SupportedModelProfilesTest,\
*ClaudeRunnerTest,*ReleaseManifestTest,*EditionArtifactMaterializerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

#### Wave 1 — finish the durable Codex vertical slice

1. Add `OperationDirectory` in the evaluation package. It owns `manifest.json`, `state.json`, `receipt.json`,
   `transcript.jsonl`, and `claim.json` below one caller-supplied operation directory. Creation writes `pending`; input
   validation must finish before compare-and-set changes it to `running`.
2. Add `OperationClaim` and `OperationClaimDocument`. A claim contains operation identifier, random UUID owner
   identifier, process identifier, acquired time, renewed time, and expiry. Its lease is 30 seconds, its owner renews
   every 10 seconds, and recovery may take it over only after the recorded expiry. A living owner retains authority to
   finish its current atomic harness or evaluator call. A dead or expired owner can retry only a unit with no verified
   terminal receipt.
3. Add `ProviderUsage` with nonnegative input, cached-input, and output token counts, plus `unavailable`. Extend
   `HarnessEvaluationExecution`, `HarnessRunReceipt`, and `HarnessEvaluationOperation` with harness version, runner
   version, usage, reproduction state, and evidence fingerprints. Extend `FormalTrialOperation` so its receipt contains
   the evaluator verdict and evaluator-evidence fingerprint. A retry verifies every fingerprint and returns the
   retained result; a changed fingerprint fails before runner invocation.
4. Add `CertificationProcessExecutor`. It reads the registry-selected successor from a verified terminal receipt,
   claims that operation, executes its registered typed handler, records exactly one terminal result, reports an
   optimization round after the report operation, and continues until `certified` or `not_certified`. Recovery starts
   at the first registered operation lacking verified terminal evidence. There is no pause, resume, or stop method.
   Wave 1 adds the typed handler interfaces and `OptimizationHandlers`, and tests the executor with complete
   fixture handlers for the vertical-slice operation set. Each later wave adds its concrete adapters when their named
   owners exist. `DefaultOptimizationOperationHandlers` is assembled only after the last concrete owner is available;
   the integrated launch gate rejects a missing, duplicate, or mismatched handler for any registered operation kind.
5. Keep a Bats case in `client/common/distribution/src/test/bats/artifact-runners.bats` that uses a fake Codex
   executable at the packaged launcher boundary. It asserts the exact `gpt-5.6-luna` and `medium` arguments and the
   exchanged protocol events. Java tests use typed static evidence for the remaining vertical-slice outcomes: one
   family, one prompt test, one deterministic assertion, retained transcript, evaluator verdict, verified receipt,
   successor invocation, idempotent retry, changed-input rejection, undeclared-input rejection, and recovery after
   each durable state.
6. Add concurrent-claim, stale-takeover, crash-point, deletion-receipt, missing-evidence, and bounded-reader tests.
   Deletion receipts are append-only entries at `.cat/history/deletions.jsonl` with artifact fingerprint, deletion time,
   reason, and affected operation identifiers. They never delete or rewrite historical result records.

Wave 1 verification is:

```text
client/mvnw -Dmaven.build.cache.enabled=false -f client/pom.xml \
  -pl :client-common-cli,:client-codex-cli -am \
  -Dtest='*Operation*Test,*CertificationProcessExecutorTest,*Harness*ReceiptTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Then package the distribution and run
`bats client/common/distribution/src/test/bats/artifact-runners.bats` against that produced runtime.

#### Wave 2 — make extraction and evaluation executable

1. Add `PromptSystemDiscovery` in the prompt-system package. It consumes an explicit effective-context capture rather
   than scanning arbitrary repository files. For each captured artifact it records load kind and dependency evidence,
   freezes one target, derives editable roots from the parents of editable loaded prompt artifacts, and rejects any
   loaded artifact that is not classified. Add direct-load, conditional-load, observation-only, and unloaded-file
   fixtures.
2. Add `PromptSystemChangeGuard`; it validates `PromptSystemChangeManifest` against the inventory, target, boundary,
   related evidence, and a caller-supplied post-change effective-context capture. It accepts target edits, related edits,
   additions, and splits; it rejects unrelated writes, writes outside editable roots, observation-only writes, target
   deletion/rename, and a split whose extracted content is not loaded in every original invocation.
3. Wire `ContractExtractionWorkflow` into the certification-process executor with the actor order and two-pass disagreement limit
   already specified in “Automatic extraction.” Deterministic reducers own identifiers, ordering, exact duplicate
   collapse, traceability, reference validation, stream separation, and final fingerprint. Add production adapters for
   the primary extractor, independent challenger, family/generator designer, interaction designer, evaluator designer,
   monotonic reviewer, and resolver. Each adapter accepts and returns only its named schema. A remaining disagreement
   returns `blocked`; no adapter may choose a fallback.
4. Add `DeterministicAssertionEvaluator`. It receives a registered check identifier and bounded typed evidence and
   returns pass or fail plus cited evidence identifiers. It rejects an unknown check, missing evidence, oversized
   evidence, and citations outside the request.
5. Add `EvaluatorProtocolRegistry`. The first semantic profile is Codex `gpt-5.6-luna` with `high` effort. The registry
   binds each assertion family to either one evaluator or two evaluators plus a blinded tiebreaker. The evaluator policy
   selects adjudication only when untouched-corpus accuracy improves by at least 2 percentage points, false-pass rate
   does not increase, and added estimated cost is no more than 2.5 times the single-evaluator cost. The initial
   calibration gate requires at least 100 untouched items per assertion family, at least 95% accuracy, at most 2%
   false passes, at most 10% false failures, at least 95% repeated-decision agreement, and a pass on every injection
   control. A missing production corpus leaves the protocol `unapproved` and blocks formal semantic evidence.
6. Keep trusted rubric/assertion fields separate from untrusted evidence in `SemanticEvaluatorOperation`; enforce the
   1 MiB document limit and 64 KiB per evidence field before model invocation. Add end-to-end tests proving that an
   approved deterministic or semantic protocol contributes exactly one formal Bernoulli outcome and that every
   protocol-defining environment change makes the preceding certificate stale.

Wave 2 verification is the explicit extraction and evaluator test list plus the Wave 1 typed-fixture and packaged
Codex Bats checks.

`PromptSystemDiscovery.discover(Capture)` is the only initial-discovery entry point. `Capture` contains the complete
`ObservationInventoryDocument`, one target artifact identifier, one boundary identifier, and the target prompt-test
identifiers; it has no repository path or scan option. Its `Result` contains the unchanged inventory plus derived
`OptimizationTargetDocument` and `EditBoundaryDocument`. `PromptSystemChangeGuard.validate(...)` accepts exactly the
pre-change inventory, target, boundary, candidate change manifest, related-evidence documents, and caller-supplied
post-change inventory. It returns normally only after validating the target, edit authority, fingerprints, relation
evidence, safe paths, and post-change effective-context capture. Neither owner invokes a model or reads an arbitrary
file from disk.

#### Wave 3 — complete selection-aware certification and optimization

1. Keep `Statistics.sequentialLowerConfidenceBound` as the only production certification bound and pin its existing
   beta-mixture e-process and numerical inversion as statistical revision `prompt-test-family-sequential-v1`. Add
   numerical fixtures and property tests for zero trials, all passes, all failures, monotonicity in passes and error
   allowance, non-monotonicity protection across time, and inversion tolerance.
2. Keep the candidate schedule `alpha(c) = 0.05 / 2^c` for one-based candidate ordinal `c`. Before assigning another
   candidate, calculate the bound for a perfect stream at that allowance and the policy's per-family trial cap. If it
   cannot exceed the target, return terminal `not_certified` with reason `remaining_allowance_unusable`; because every
   later allowance is smaller, do not assign another candidate. Persist intermediate, certified, futile, and
   remaining-allowance-unusable decisions and their weakest-family bound in the certification-process evidence.
3. Extend `CertificationCoverageSimulation` to the complete adaptive-path matrix listed in “Coverage construction and
   proof.” Use deterministic seeds checked into the policy manifest. The simulation fails unless the one-sided exact
   95% Clopper–Pearson upper bound on false certification and on any overstated displayed lower bound is at most 5%.
   Add explicit legacy statistical-revision rejection and allowance-unusable tests.
4. Add `PromotionAllowanceLedger` with the same hash-chain and `0.05 / 2^c` one-based challenger schedule as
   `CandidateAllowanceLedger`. Assign before paired outcomes. Add `PairedPromptTestFamilyEngine`. For each family, ignore
   ties and treat challenger-only passes as successes and champion-only passes as failures. Apply the same anytime-valid
   Bernoulli lower bound at the challenger's full allowance. Promote only when every family has a lower bound above
   0.5. Return `repair_regression` when any family has the corresponding loss-direction lower bound above 0.5. Otherwise
   request the next predeclared pair increment or reject at the maximum. This intersection rule uses no family weights.
5. Replace `DevelopmentPolicy`'s aggregate-net-win promotion with that engine. Check in three simulation candidates:
   `(initial, increment, maximum, survivors, readiness trials, readiness failures, plateau rounds, plateau improvement)`
   equal to `(8, 8, 64, 3, 20, 0, 3, 0.01)`, `(16, 16, 128, 3, 30, 0, 4, 0.01)`, and
   `(24, 24, 192, 2, 40, 1, 5, 0.005)`. The existing untouched-partition selection and tie rules choose one complete
   manifest. Runtime refuses a missing or failed manifest; it never chooses thresholds itself.
6. Extend candidates from one target file to a `PromptSystemCandidate` containing the complete prompt-system snapshot,
   target identity, and validated change manifest. Materialization uses a new isolated directory, applies exactly the
   manifest changes, runs `PromptSystemChangeGuard`, and only then permits a model call. Add a
   `PromptSystemRepairOperation` that receives named failing interactions and may change only the same frozen boundary.
   If a calibrated total-cost comparison is available, repair when its upper estimated cost is lower than another
   candidate's lower estimated certification cost; when unavailable, try one isolated repair challenger and let the
   ordinary paired comparison retain or reject it.
7. `CertificationProcessExecutor` generates at most five challengers per round, retains at most the policy's survivor
   count, restores the best validated champion after an unsuccessful round, and automatically continues. A plateau or
   unusable allowance returns `not_certified`; it does not enlarge the boundary or reset either lineage ledger. Derive
   `OptimizationRoundReportDocument` from retained evidence after every completed optimization round; callers may not
   submit their own report values.

Wave 3 verification is every evaluation, optimization-development, certification-process, simulation, and prompt-system
test. It must include complete `certified`, plateau, failed-batch, conflict-repair, addition, split, unrelated-write,
out-of-bound-write, and best-candidate-restoration paths.

#### Wave 4 — cost, history, and stale results

1. Add strict `ProviderPriceCatalogDocument`, `CostPolicyDocument`, and `OperationCostReceipt`. The catalog contains
   schema version, catalog identifier, source URI, source revision, effective time, ISO-4217 currency, input,
   cached-input, and output token unit prices, tokens per price unit, and currency display scale. Calculations retain
   exact `BigDecimal` values and display rounds once with `HALF_EVEN`; the receipt retains both the exact value and
   displayed value. A flat subscription records token usage and elapsed time but reports provider cost `unavailable`.
2. Parse Codex usage only from the typed final-turn usage object already delivered by the app-server protocol:
   `input_tokens`, `cached_input_tokens`, and `output_tokens`. A missing object produces `ProviderUsage.unavailable`;
   an unknown field is ignored only inside that explicitly extensible provider object. Propagate usage through runner,
   development, formal, operation-evidence, and round-report records. Never estimate missing token counts.
3. Persist calibration in `cost-policy-v1.json`. A forecast band keys model, harness, evaluator protocol, family-count
   band, interaction-family-count band, candidate-count band, and lineage-allowance band. It requires at least 30
   complete trajectories, at least 90% validation interval coverage, and median absolute percentage error at most 30%.
   No first-release band is assumed calibrated; absent reference data produces `cost unavailable` without blocking
   execution. Reconcile exact operation receipts into each round total.
4. Add `CertificationEnvironmentDocument` and embed its complete bounded canonical value—not only a reference—in the
   signed result. It records every environment dimension already named by `CertificateEnvironment`. Add
   `CertificationResultOperation` for both `certified` and `not_certified`; only `certified` receives the certificate
   claim, while both receive signed local history records.
5. Add `CertificateHistoryIndex` at `.cat/history/certificates/index.jsonl`. Each hash-chained entry contains result
   identifier, result path, issued time, target fingerprint, contract fingerprint, environment fingerprint, terminal
   result, and previous/entry fingerprints. Preserve append order and present results newest-first by issued time then
   identifier. Extend `CertificateHistoryCommand` with `list`, `verify`, and `compare --environment <file>`. A changed
   dimension prints its old value, new value, and affected scope, labels the result `Stale certificate`, and reports
   `Current lower bound: unavailable`; the historical numeric value remains in the signed result. Missing evidence
   leaves signature verification intact but reports `Reproduction: unavailable` and forbids evidence reuse.

Wave 4 verification is every cost and history test plus golden current, stale, corrupt-index, every-environment-field,
missing-evidence, and cumulative-reconciliation fixtures.

#### Wave 5 — commercial client behavior and Claude parity

1. Guard these entry points and no others: prompt optimization, semantic evaluation for optimization, formal
   certification, certificate issuance, and feedback submission. No retired command remains installed or callable.
   Put the table in `CommercialOperation` and make each guarded command call
   `CommercialEntitlementGuard` before its first atomic operation.
2. Add `CommercialAccountConfiguration` at `$HOME/.cat/account.json` with schema version, HTTPS account endpoint, and
   access-token file path. Require both files to be owner-readable and owner-writable only. Add a random UUID device
   identifier at `$HOME/.cat/device-id`. Add `CommercialAccountClient`, which sends a bearer-authenticated GET to
   `/api/v1/license/lease` with `X-CAT-Device-Id` and accepts only a signed lease document. The verification key is an
   edition resource named `commercial-entitlement-public-key.pem`; tests inject a fixture public key.
3. A confirmed lease lasts 24 hours. Renew one hour before expiry and retry a failed renewal after 1, 2, 4, 8, 16, 32,
   then 60 minutes. Offline grace ends at the earlier of seven days after the last confirmed entitlement and the signed
   subscription end. A denial records its signed effective end. An atomic operation started while authorized may
   finish; no successor starts after the effective end. Diagnostics name account sign-in and renewal as recovery.
4. `FeedbackCommand` reads authenticated account configuration rather than endpoint/token CLI arguments. The feedback
   skill may use harness history only. It may include a file only when the user explicitly adds that path before
   preview; it never discovers or reads files implicitly. Preview lists every field and file, says “Your content will
   only be visible to CAT staff,” and requires explicit confirmation. Failed delivery retains the preview locally;
   success stores the backend receipt.
5. Add `UsageAnalyticsSettings` at `$HOME/.cat/usage-analytics.json`, default disabled, plus
   `UsageAnalyticsClient`. Keep the existing field allowlist and exclusion rules. Queue accepted events in bounded
   JSON Lines, send them to `/api/v1/usage-analytics`, retain receipts, and retry transport failures without blocking
   optimization. Reject an unknown field before queuing. Integrate round cost, duration, family-count band, harness,
   model profile identifier, result kind, and policy versions; send no path, prompt, test, conversation, trace, or
   stable customer identifier.
6. Add the Claude typed-boundary and packaged terminal fixtures named in F9. Use `haiku` and `medium` exactly. Share
   common optimizer behavior through static runner evidence, and keep Claude command/protocol assertions in the Claude
   module. No Claude capability exclusion is approved for the first release.
7. Add `client/codex/skills/src/main/resources/skills/claude-runner/SKILL.md`. The skill invokes the typed
   cross-harness resolver rather than discovering paths itself. Add the resolver and its strict tests in the Codex CLI
   module. It accepts only a compatible packaged Claude artifact, resolves `claude-runner` and the artifact from the
   installed CAT distribution, validates the requested `haiku`/`medium` profile, and delegates the JSON Lines request
   unchanged to the Claude runner. Its tests cover a successful retained receipt, missing Claude artifact, incompatible
   CAT versions, missing Claude executable, missing authentication, and rejection of arbitrary cache-path discovery.
   Add a Codex packaged-artifact test proving the skill and its required Claude runner assets are present. Future
   harness integrations add one equivalent named Codex skill and resolver entry; they may not add free-form runner or
   artifact paths to the skill interface.

Wave 5 verification is every Work common test plus every Claude runner test and both packaged terminal outcomes.

#### Wave 6 — releases and the integrated launch gate

1. Keep the release repository tree as `<harness>/<os>-<arch>/...`, where harness is `codex` or `claude`, OS is
   `linux` or `macos`, and architecture is `x86_64` or `arm64`. Materialize all eight tuples for each edition. WSL uses
   the matching Linux tuple; native Windows exits with the WSL instruction. A release version tag is the Maven version
   without a leading `v`. Remove the obsolete GitHub-Release archive publication path; a production release is one
   release-tree commit on `main` plus the immutable version tag.
2. Extend `EditionArtifactMaterializer` to emit `release-manifest.json`. The manifest lists every file path, SHA-256,
   size, edition, target, and version and rejects an unlisted file, source file, build input, credential pattern, or
   symlink. This package inspection rejects a credential embedded in a staged end-user file. Installers verify the
   checked-out immutable Git tag, the manifest, and every listed file.
3. Add `client/release/publish-release.sh`. It accepts edition, staged tree, repository URL, version, and an already
   authenticated Git executable. It verifies a clean staged tree and manifest, clones the destination, replaces only the
   release tree, commits on `main`, runs the staged install/update/uninstall suite, creates the version tag, and publishes
   branch and tag together with `git push --atomic`. It never deletes or moves an existing tag and leaves a failed
   unpublished staging clone for diagnosis. Personal uses `https://github.com/catsforbots/cat/`; Work uses
   `https://github.com/catsforbots/cat-work/`.
4. Update Codex and Claude bootstrap/install/update scripts to resolve the requested tag, fetch that tag with
   `--depth 1`, select the exact tuple, verify the manifest and every file before installation, and invoke the harness's
   existing local-plugin installation boundary. Do not use `curl | sh`. Add staged-repository Bats fixtures for clean
   install, update, uninstall, wrong architecture, native Windows, WSL, tag failure, manifest failure,
   Personal-commercial exclusion, and an authenticated local bare-repository stand-in for the private repository.
5. Add `FrontendLaunchGate` and `FrontendLaunchGateTest`. Its registry contains every bullet in “Frontend acceptance
   gates” with status `passed`, `failed`, `unrun`, or `excluded`, command, evidence paths, start/end times, and
   diagnostic. Reports live at `target/frontend-launch-gates/<version>/gate-report.json`. Only the product owner may
   supply an `excluded` result and reason; ordinary execution records unavailable macOS, arm64, WSL, live-harness,
   private-repository, or publishing evidence as `unrun`, never passed.
6. Add `client/release/run-frontend-launch-gate.sh` as the one terminal command. It runs the uncached Maven reactor,
   common simulations, external Bats suites, package inspection, and every available target fixture. Java tests prove
   deterministic release-builder and gate logic; Bats tests prove end-user shell installation behavior. It then invokes the
   Java gate aggregator. It writes `backend-handoff.json` beside the report only when every required gate passed. The
   handoff contains schema version, release version, repository URLs, commit and tag, manifest fingerprint, supported
   tuples, install/update commands, license version, offer-catalog fingerprint, and gate-report fingerprint. Update
   `.github/workflows/release.yml` to call this script before `publish-release.sh`; remove archive upload steps. Do not
   change `.github/workflows/build.yml` except to run deterministic source-owned tests that do not require credentials or
   unavailable platforms.

Wave 6 source implementation is complete when the local fixture gate passes. Production launch remains incomplete
while any required real-platform, live-harness, private-repository, or publication gate is `unrun`.

#### Mechanical clarifications

The following details are part of the wave contracts above and remove any need for the executor to choose a migration,
fixture, adapter, persistence location, protocol, or gate policy.

##### Contract fixtures and schema migration

Every `*Document` introduced by Wave 0 is a public immutable Java `record`. Its record components appear in the same
order as the fields listed by the wave contract. The canonical record constructor performs all validation and defensively
copies collections; the generated component accessors are its only public data-access API. Do not add builders,
overloaded constructors, `fromJson`, `toJson`, or map conversion methods. `RecordStore` is the sole JSON read/write
owner for these records and supplies the configured mapper, size limit, atomic replacement, unknown-field rejection,
and UTF-8 handling. Tests serialize and deserialize through `RecordStore`; fingerprint tests use the canonical bytes
that `RecordStore` produces after identifier-keyed collections have been sorted as required by this contract.

Do not add prose-named resource fixtures for Wave 0. Add one Java owner
`ContractExtractionFixtures` under the contract test package. Its `validContract()` method returns the smallest complete
contract: one source span `span-1`, one requirement `requirement-1`, one always-applicable rule `applicability-1`, group
`group-1`, family `family-1`, distribution `distribution-1`, generator `generator-1`, prompt test `prompt-test-1`,
deterministic assertion `assertion-1`, evaluator protocol `deterministic-v1`, and ready extraction result. Add these
methods; each makes only the named mutation to a fresh valid value:

| Method | Mutation | Expected diagnostic owner and substring |
|---|---|---|
| `untracedRequirement()` | remove `span-1` from `requirement-1` | `CertificationContractDocumentTest`: `untraced requirement` |
| `duplicateIdentifier()` | add a second family named `family-1` | `CertificationContractDocumentTest`: `duplicate identifier` |
| `uncoveredApplicabilityBranch()` | remove the `not_applicable` outcome | `CertificationContractDocumentTest`: `incomplete applicability outcomes` |
| `missingDistribution()` | point `family-1` at `missing-distribution` | `CertificationContractDocumentTest`: `unknown distribution` |
| `generatorOutsideFamily()` | generator emits family `family-2` | `PromptTestGenerationPolicyDocumentTest`: `outside prompt-test family` |
| `unassignedAssertion()` | remove `assertion-1` from `prompt-test-1` | `CertificationContractDocumentTest`: `unassigned assertion` |
| `missingEvaluatorProtocol()` | point `assertion-1` at `missing-protocol` | `CertificationContractDocumentTest`: `unknown evaluator protocol` |
| `unresolvedReviewAction()` | add disagreement `disagreement-1` with result `unresolved` and pass count `2` | `ContractExtractionWorkflowTest`: `unresolved after two passes` |
| `unsupportedField()` | add root field `future` | `CertificationContractDocumentTest`: `unsupported fields` |

`validContract()` must round-trip to the same fingerprint. Every invalid method must be rejected before workflow
execution. These programmatic fixtures are the “one fixture for every reference failure” required by Wave 0.

The optimizer and certification artifacts in this worktree have not shipped. Apply a clean schema break instead of a
compatibility layer: documents whose persisted vocabulary changes from `scenario` to `prompt_test` use schema version
`2`; readers reject schema version `1` with `Unsupported <document name> schema version 1; regenerate the certification
contract`. Do not migrate, rewrite, or accept aliases. Delete the optimizer's old scenario-named Java production types
and matching tests after their prompt-test-named replacements compile. Do not retain deprecated wrappers, subclasses,
forwarding types, source aliases, or duplicate JSON field aliases. Update every in-repository Java caller and import to
the replacement type in the same wave.

##### Closed-domain conversion list

Apply only these conversions; do not search for additional candidates:

| New type and external values | Replace these existing domains and ingresses |
|---|---|
| `HarnessKind`: `codex`, `claude` | `HarnessModelProfile.harness`, `HarnessEvaluationRequest.harness`, `CertificateEnvironment` harness field, and their JSON/CLI parsers |
| existing `OptimizationOperationRegistry.OperationId` (rename the type to `OperationKind`, preserving values) | `OptimizationOperationEvidenceDocument`, `CertificationProcessLedger.Entry`, `OptimizationStateMachine.Transition`, and `CertificationProcessCommand` operation parsing |
| `OperationState`: `pending`, `running`, `completed`, `failed` | `HarnessEvaluationOperation.State`, `OperationDirectory`, and `OperationClaimDocument` state parsing |
| existing `OptimizationOperationRegistry.ResultId` (rename the type to `OperationResultKind`, preserving values) | registry transitions, certification-process entries, state-machine inputs, and command result parsing |
| `EvaluatorVerdict`: `pass`, `fail` | `FormalTrialOperation.Evaluation`, `SemanticEvaluatorOperation`, `DeterministicAssertionEvaluator`, evaluator evidence documents, and their JSON parsers; infrastructure failure remains an operation result, not an evaluator verdict |
| `CertificationProcessState`: `not_started`, `active`, `certified`, `not_certified` | `OptimizationStateMachine.Phase`, certification-process status projection, `CertificationProcessCommand`, and certification-result history parsing |
| `ReproductionState`: `available`, `unavailable` | `LocalCertificate`, `LocalCertificateDocument`, `LocalCertificateStore`, and `CertificateHistoryCommand` |
| existing `ReleaseManifest.Target` (rename the type to `ReleaseTarget`, preserving its 8 tuple values) | `ReleaseManifest.Entry`, `EditionArtifactMaterializer`, `DistributionMain`, and their CLI/JSON parsers |

Apply a clean pre-release terminology migration at the same boundary; do not retain aliases or deprecated wrappers.
The hash-chained process ledger is `CertificationProcessLedger`; its command and CLI route are
`CertificationProcessCommand` and `certification-process`; and its executor is `CertificationProcessExecutor`.
`OptimizationStateMachine` exposes exact public method
`static OptimizationStateMachine.State beginCertificationProcess()`. It takes no arguments and returns
`activeAt(OperationKind.GENERATE_CANDIDATES)` for an already prepared process. Replace the existing no-argument method
that owns that behavior; do not retain a forwarding alias. Its start, recovery, and finish operation
kinds are `START_CERTIFICATION_PROCESS`, `RECOVER_CERTIFICATION_PROCESS`, and `FINISH_CERTIFICATION_PROCESS`. Their
owner and terminal recovery boundary are `CERTIFICATION_PROCESS_SERVICE` and `TERMINAL_CERTIFICATION_PROCESS`.

In `OperationKind`, replace exactly three existing entries while preserving their ordinal positions: the start entry
between `FORECAST_COST` and `GENERATE_CANDIDATES` becomes
`START_CERTIFICATION_PROCESS("start-certification-process")`; the recovery entry between `RETRY_OPERATION` and
`FINISH_PREPARATION` becomes `RECOVER_CERTIFICATION_PROCESS("recover-certification-process")`; and the terminal
finish entry immediately after `FINISH_PREPARATION` becomes
`FINISH_CERTIFICATION_PROCESS("finish-certification-process")`. Update every registry and state-machine reference to
those constants. Preserve every other operation constant and external value.

`OperationKind.fromExternalValue` accepts only the resulting exact values. Any replaced or otherwise unsupported
value throws `IllegalArgumentException` with exact message `Unsupported optimization operation: <value>`. A persisted
schema-2 reader catches that exception and throws `IOException` with exact message
`Unsupported optimization operation in <absolute-path>: <value>`, retaining the parser exception as its cause. This is
an invalid operation value, not a schema-version error. It creates no files, transitions, or recovery state. Add parser
and persisted-reader tests covering each of the three replaced values and each replacement.

Rename matching source and test files, variables, methods, persisted fields, diagnostics, documentation, and fixtures
in the affected optimization workflow. Process identity uses only `certification_process_id`,
`certificationProcessIdentifier`, and `certification-process.json`. Operation evidence uses schema identifier
`optimization-operation-evidence-v2`, and the CLI prints `certification_process=<identifier>`. Schema version 1 is
rejected rather than migrated. Do not apply this rename to an unrelated workflow merely because it uses similar words.

The persisted-field migration is closed. Exactly two documents carry process identity:

- `CertificationProcessLedger` manifest: `schema_version`, `certification_process_id`, `contract_sha256`,
  `environment_sha256`, `initial_operation`, `transition_ledger`.
- `OptimizationOperationEvidenceDocument` version 2: `schema_version`, `certification_process_id`,
  `predecessor_sequence`, `contract_sha256`, `environment_sha256`, `operation`, `evidence_schema_id`, `result`,
  `successor`, `payload_path`, `payload_sha256`, `completed_at`.

No transition entry or command-owned persisted document contains another process-identity field. The `status` and
`next` command outputs replace only their first key with `certification_process`; every other output key remains
unchanged. Usage text uses route `certification-process` and placeholder `<certification-process-id>`. Every
human-readable diagnostic and Javadoc in the renamed workflow uses the noun phrase “certification process”; no such
text may contain the obsolete term, except when a diagnostic safely echoes an invalid caller-supplied value. Tests must
assert the two exact document field sets, both command projections, the usage route and placeholder, and absence of the
obsolete term from static diagnostics.

The exact process-bound Java APIs are:

```text
public static OptimizationOperationEvidenceDocument.VerifiedEvidence
OptimizationOperationEvidenceDocument.readAndVerify(JsonMapper json, Path source,
    CertificationProcessLedger.Snapshot certificationProcess) throws IOException
public static void CertificationProcessLedger.initialize(JsonMapper json, Path manifestPath,
    String certificationProcessIdentifier, String contractFingerprint,
    String environmentFingerprint) throws IOException
public record CertificationProcessLedger.Snapshot(String certificationProcessIdentifier, String contractFingerprint,
    String environmentFingerprint, OptimizationStateMachine.State state, String ledgerFingerprint)
```

The evidence document's private identity helper is `verifyCertificationProcessBinding`; every parameter or local of
type `CertificationProcessLedger.Snapshot` is named `certificationProcess`. Its fingerprint helper keeps its existing
method name and receives the same renamed parameter. `CertificationProcessCommand` retains method names `main`,
`status`, `next`, `reportRound`, and `recordEvidence`; it changes only its owning type, process terminology, CLI route,
and use of `CertificationProcessLedger`. There are no forwarding methods or process-terminology aliases.

Parse exact lowercase external values at JSON and CLI ingress. Java callers accept the enum. Keep model names open.
`SupportedModelProfiles` separates two closed uses through `ProfileRole`: `OPTIMIZER_EXECUTOR` accepts Codex
`gpt-5.6-luna`/`medium` and Claude `haiku`/`medium`; `SEMANTIC_EVALUATOR` accepts Codex
`gpt-5.6-luna`/`high`. `requireSupported(ProfileRole, HarnessModelProfile)` rejects every other combination. Wave 0's
two-profile statement refers to `OPTIMIZER_EXECUTOR`; Wave 2's evaluator uses the separate
`SEMANTIC_EVALUATOR` entry. Claude formal trials execute through Claude but their initial semantic evaluator runs
through the Codex evaluator profile; no Claude semantic-evaluator profile is approved in the first release.
Call `requireSupported(OPTIMIZER_EXECUTOR, profile)` in the canonical constructors of
`HarnessContractProposalAgent`, `HarnessContractResolverAgent`, `HarnessCandidateGenerator`, and
`HarnessDevelopmentTrialExecutor`. Call `requireSupported(SEMANTIC_EVALUATOR, profile)` when constructing an
`EvaluatorProtocol` and when decoding one in `EvaluatorCalibrationDocument`; `SemanticEvaluatorOperation` rechecks the
protocol profile before creating a harness request. Store the returned validated profile. Tests must show that each
boundary rejects one unsupported model or effort, so a registry with no production callers cannot satisfy Wave 0.

`OperationDirectory/state.json` always contains `schema_version`, `operation_id`, `state`, `input_fingerprint`, and
`updated_at`. `pending` and `running` have no domain result. A terminal receipt always contains the same identity plus
`state` and terminal evidence:

- `completed` means the handler produced any registered domain result. This includes ordinary success, validation
  rejection, `blocked`, `uncertifiable`, `futility`, `infrastructure_failure` when the registry explicitly defines it
  as a retained result, and the certification-process results `certified` and `not_certified`. The receipt requires
  `result_kind`, evidence path and fingerprint, and `successor_kind` unless the registry declares that result terminal.
- `failed` means the operation could not produce a registered domain result because its input, protocol, persistence,
  or infrastructure boundary failed before a result receipt was retained. The receipt omits `result_kind` and
  `successor_kind` and requires `failure_kind` (`invalid_input`, `protocol_failure`, `persistence_failure`, or
  `infrastructure_failure`), `diagnostic`, and `retryable`. Recovery may retry it only when `retryable` is true and the
  registered recovery boundary permits retry.

A rejected candidate is therefore `completed` with result `reject`; it is not an operation failure. A formal harness
or evaluator outage that `record-outcome` successfully retains is `completed` with result `infrastructure_failure`.
`finish-certification-process` is `completed` with result `certified` or `not_certified`. Compare-and-set writes `state.json` first
for `pending` and `running`, then writes exactly one immutable `receipt.json`, then atomically changes `state.json` to
the matching terminal state. A terminal retry verifies both files and never rewrites either.

##### Operation handler contract and complete mapping

Add these types to the optimization package:

```text
OptimizationInput                input interface with operationKind() and inputFingerprint()
OptimizationResult               record(operationKind, resultKind, evidencePath, evidenceFingerprint, successorKind)
OptimizationContext              record(json, clock, certificationProcessDirectory, operationDirectory, contractFingerprint,
                               environmentFingerprint)
OptimizationHandler<I extends OptimizationInput>
                               operationKind(), inputType(), execute(context, input)
OptimizationHandlers             immutable EnumMap<OperationKind, OptimizationHandler<?>>
```

Their complete interface declarations are:

```java
public interface OptimizationInput
{
    OperationKind operationKind();
    String inputFingerprint();
}

public interface OptimizationHandler<I extends OptimizationInput>
{
    OperationKind operationKind();
    Class<I> inputType();
    OptimizationResult execute(OptimizationContext context, I input) throws IOException;
}
```

Both interfaces are public. Implementations must return non-null kinds and values. `inputFingerprint()` is a lowercase
SHA-256 over the complete canonical operation input. `OptimizationHandlers` validates those requirements and
wraps no `IOException`; a handler failure propagates to the executor, which records the specified failed receipt when
persistence remains available.

`OptimizationHandlers` supports a constructor-scoped required-operation set. It requires exactly one handler
for every member of that set, rejects a missing, extra, or duplicate handler, checks `inputType().isInstance(input)`
before dispatch, and returns a typed mismatch error before calling the handler. Wave 1's vertical slice passes exactly
its six-operation set. The production factory passes `EnumSet.allOf(OperationKind.class)`, so it cannot omit an operation.
Both constructor collections are non-null, non-empty, contain no null elements, and are defensively copied. A duplicate
means two handlers return the same operation kind, regardless of object identity. Each handler must return a non-null
operation kind and input type during construction. Null required values throw `NullPointerException`; an empty set or
collection, a duplicate operation kind, a missing required kind, or a handler outside the required set throws
`IllegalArgumentException`.
`CertificationProcessExecutor` depends on this registry through its constructor; it does not instantiate model agents
or network clients. After the concrete owners named below exist, add `DefaultOptimizationOperationHandlers.create(...)`,
whose arguments are those collaborators and whose mapping is exact:

| Operation kind | Handler owner |
|---|---|
| `discover-prompt-system`, `analyze-prompt-system` | `PromptSystemDiscovery` |
| `extract-contract` | `ContractExtractionWorkflow` adapter |
| `validate-evaluator-protocols` | `EvaluatorProtocolRegistry` |
| `validate-deterministic-controls` | `DeterministicAssertionEvaluator` preflight |
| `forecast-cost` | `CostForecastCatalog` adapter |
| `start-certification-process`, `recover-certification-process`, `finish-preparation`, `finish-certification-process` | new `CertificationProcessLifecycleOperations` |
| `generate-candidates` | `HarnessCandidateGenerator` adapter |
| `compare-development-candidates` | `DevelopmentComparisonOperation` adapter |
| `decide-readiness` | `DevelopmentReadinessOperation` adapter |
| `report-optimization-round` | new `OptimizationRoundReportOperation` |
| `repair-prompt-system` | `PromptSystemRepairOperation` |
| `validate-candidate-contract` | new `CandidateContractValidationOperation`, which calls `PromptSystemChangeGuard` then compares the frozen contract fingerprint |
| `freeze-candidate-batch` | `FormalCertificationRound` adapter |
| `assign-allowance` | `CandidateAllowanceLedger` adapter |
| `schedule-trial` | `FormalPromptTestScheduler` adapter |
| `record-outcome` | `FormalTrialOperation` adapter |
| `decide` | `FamilySequentialEngine` adapter |
| `report` | `CertificationResultOperation` |
| `retry-operation` | new `RetryOperation`, which returns the same operation kind whose claimed unit lacks terminal evidence |

Each adapter is a package-private class beside its existing owner and translates only between that owner's typed value
and `OptimizationResult`; it contains no fallback policy. `OptimizationResult.successorKind` must be
one member of the registry successor set for its result. Finish operations have no successor.

##### Wave 1 durable-operation API and schemas

Use these exact public contracts; do not derive another lifecycle API.

`ProviderUsage` is
`record ProviderUsage(boolean available, long inputTokens, long cachedInputTokens, long outputTokens)`.
`ProviderUsage.of(input, cachedInput, output)` requires nonnegative values and `cachedInput <= input`.
`ProviderUsage.unavailable()` sets `available` to false and all counts to zero; an unavailable instance with a nonzero
count is invalid.

Extend `HarnessEvaluationExecution` with `harnessVersion`, `runnerVersion`, `ProviderUsage usage`,
`ReproductionState reproductionState`, and immutable `Map<String, String> evidenceFingerprints`. Version values are
nonblank open text. Fingerprint keys are `retained_project`, `raw_result`, and `transcript`; values are lowercase
SHA-256. Keep the existing three- and four-argument constructors as legacy test helpers. They use version `unknown`,
unavailable usage, unavailable reproduction, and an empty fingerprint map; a production receipt may not use those
defaults.

Its exact component order is
`HarnessEvaluationExecution(HarnessEvaluationResult result, long startedAtNanos, long completedAtNanos,
boolean interrupted, String harnessVersion, String runnerVersion, ProviderUsage usage,
ReproductionState reproductionState, Map<String, String> evidenceFingerprints)`.

Add nested
`HarnessRunReceipt.Receipt(HarnessEvaluationResult result, Path transcript, String harnessVersion,
String runnerVersion, ProviderUsage usage, ReproductionState reproductionState,
Map<String, String> evidenceFingerprints)`. It normalizes the absolute transcript path and defensively copies the map.
Add `readComplete(JsonMapper, Path)` and `fromCompleteReceipt(JsonNode, Path)` for the strict complete schema.
Keep `read` and `fromReceipt` as compatibility projections of `.result()`. The complete receipt has exactly:

```text
schema_version: 1
retained_project: absolute path
retained_project_sha256: lowercase SHA-256
raw_result: absolute path
raw_result_sha256: lowercase SHA-256
transcript: absolute path
transcript_sha256: lowercase SHA-256
harness_version: nonblank text
runner_version: nonblank text
usage: {available, input_tokens, cached_input_tokens, output_tokens}
reproduction_state: available | unavailable
```

The complete writer accepts the transcript and metadata explicitly, computes all three fingerprints, and atomically
publishes the document. The existing writer overload remains only for legacy callers and writes the old two-path
schema. `NativeHarnessEvaluationRunner` prefers `readComplete` and returns every value in
`HarnessEvaluationExecution`. A legacy receipt remains executable with unknown versions, unavailable usage and
reproduction, and computed fingerprints for its two retained artifacts; it is not eligible for certification.
`HarnessEvaluationOperation` adds those metadata and fingerprints to its completed manifest and verifies every
available fingerprint on retry. Its input identity includes the exact
launcher, artifact, project, profile, bridge input, transcript and receipt destinations, installation flag, and sorted
read-only evidence paths. A changed value fails before runner invocation.

The exact complete-writer signature is:

```text
static HarnessRunReceipt.Receipt writeComplete(JsonMapper json, Path destination, HarnessRun run,
                                                Path rawResult, Path transcript, String harnessVersion,
                                                String runnerVersion, ProviderUsage usage,
                                                ReproductionState reproductionState) throws IOException
```

It returns the same validated value that `readComplete(json, destination)` returns after publication.

`FormalTrialLedger.RetainedOutcome` is the durable formal-trial receipt; do not create a second receipt. Extend each
outcome with `executor_evidence_path` and nullable `evaluator_evidence_path`, in addition to their fingerprints. Convert
the public evaluator result to `Evaluation(EvaluatorVerdict verdict, Path evidencePath, String evidenceFingerprint)`;
translate the verdict once to the ledger's statistical pass/fail representation. On retry, `FormalTrialOperation`
verifies family and prompt-test identifiers, both retained evidence paths, and every available fingerprint before
returning the outcome. Infrastructure failure has a null evaluator path and fingerprint. Do not retain a pathless
compatibility constructor: migrate existing fixtures and `SemanticEvaluatorOperation` to write evaluator evidence and
return its path. A pass/fail result without that path is rejected before the formal ledger append.

Use these exact formal records:

```text
FormalTrialLedger.Outcome(String operationId, String familyId, String promptTestId, Verdict verdict,
                          Path executorEvidencePath, String executorEvidenceFingerprint,
                          Optional<Path> evaluatorEvidencePath,
                          Optional<String> evaluatorEvidenceFingerprint, Instant completedAt)
FormalTrialLedger.RetainedOutcome(int ordinal, Outcome outcome, String previousFingerprint,
                                  String entryFingerprint)
FormalTrialOperation.Evaluation(EvaluatorVerdict verdict, Path evidencePath, String evidenceFingerprint)
```

Normalize all evidence paths to absolute paths. `FormalTrialLedger.record` requires each non-null evidence path to be a
regular file whose current fingerprint matches its supplied fingerprint before append. Ledger replay validates path
syntax and the hash chain without requiring historical external evidence to remain present. `FormalTrialOperation`
retry requires the executor evidence and, for pass/fail, evaluator evidence to remain present and fingerprint-valid;
otherwise it fails as missing or changed evidence without invoking the runner or evaluator.

The outcome-ledger JSON field order is exactly:

```text
operation_id, family_id, prompt_test_id, verdict, executor_evidence_path, executor_evidence_sha256,
evaluator_evidence_path, evaluator_evidence_sha256, completed_at, ordinal, previous_sha256, entry_sha256
```

Both evaluator fields are explicit JSON null only for `infrastructure-failure`; both are required strings for `pass`
and `fail`. The executor fields are always required. Keep `FormalTrialLedger.Verdict` as the persisted statistical
outcome, but expose only `EvaluatorVerdict` at the evaluator boundary and translate `PASS` to `PASS` and `FAIL` to
`FAIL` inside `FormalTrialOperation`.

`SemanticEvaluatorOperation` writes `<operation-root>/evaluator-evidence.json` before returning. Its strict document
has `schema_version`, `protocol_sha256`, `policy_sha256`, `corpus_sha256`, `calibration_report_sha256`,
`executor_result_sha256`, ordered `evaluator_transcript_sha256`, and `verdict`; it returns that absolute path and its
file fingerprint in `FormalTrialOperation.Evaluation`.

`HarnessEvaluationOperation` writes schema version 2 for every new manifest and keeps schema version 1 read-only for
retained pre-release fixtures. Version 2 field order is:

```text
schema_version, state, input_sha256, model, effort
```

For `completed`, append exactly:

```text
started_at_nanos, completed_at_nanos, owner_interrupted,
retained_project, retained_project_sha256, raw_result, raw_result_sha256,
transcript, transcript_sha256, harness_version, runner_version,
usage, reproduction_state
```

`usage` has the complete-receipt field order `available`, `input_tokens`, `cached_input_tokens`, `output_tokens`.
For `failed`, append only `failure`. Pending and running contain only the five base fields. A version-2 retry requires
the exact field set for its state and verifies the project, raw result, and transcript fingerprints. A version-1 retry
uses the existing schema and reconstructs unknown versions, unavailable usage and reproduction, and fingerprints for
the project and raw result; it remains non-certifiable and is never rewritten as version 2. Any other schema version,
mixed field set, missing metadata, or invalid usage fails. New request identity uses version 2 and the complete sorted
input identity already specified.

`OperationDirectory` exposes these methods:

```text
static Path path(Path certificationProcessDirectory, long transitionSequence, OperationKind kind, int attempt)
static OperationDirectory create(JsonMapper, Clock, OptimizationContext, long transitionSequence,
                                 int attempt, OptimizationInput)
static OperationDirectory open(JsonMapper, Clock, Path operationDirectory)
Manifest manifest()
StateSnapshot state()
Path root(), manifestPath(), statePath(), receiptPath(), transcriptPath(), claimPath()
void start(String expectedInputFingerprint)
Receipt complete(OptimizationResult)
Receipt fail(FailureKind, String diagnostic, boolean retryable)
Optional<Receipt> verifiedTerminalReceipt()
```

`path` requires a non-null certification-process directory and operation kind, a positive transition sequence, and a positive
attempt. It normalizes the certification-process directory to an absolute path and returns the matching child of its `operations`
directory. `create` requires non-null JSON mapper, clock, context, and input plus the same positive sequence and
attempt. It requires the context operation directory to equal `path(context.certificationProcessDirectory(),
transitionSequence, context.operationKind(), attempt)` and the input kind to equal the context kind before creating
files. `open` requires non-null arguments and an existing regular operation manifest below the normalized operation
directory. Null required arguments throw `NullPointerException`; a nonpositive sequence or attempt, a context/input
mismatch, or a path mismatch throws `IllegalArgumentException`; malformed or missing persisted state throws
`IOException`.

`Manifest`, `StateSnapshot`, `Receipt`, and `FailureKind` are public nested types of `OperationDirectory`; do not add
top-level files for them:

```text
record Manifest(int schemaVersion, String operationId, long transitionSequence, int attempt,
                OperationKind operationKind, String inputFingerprint, String contractFingerprint,
                String environmentFingerprint, String stateFile, String receiptFile,
                String transcriptFile, String claimFile)
record StateSnapshot(int schemaVersion, String operationId, OperationState state, String inputFingerprint,
                     Instant updatedAt, Optional<String> receiptFingerprint)
record Receipt(int schemaVersion, String operationId, OperationKind operationKind, long transitionSequence,
               int attempt, String inputFingerprint, String contractFingerprint, String environmentFingerprint,
               OperationState state, Instant startedAt, Instant completedAt,
               Optional<OperationResultKind> resultKind, Optional<Path> evidencePath,
               Optional<String> evidenceFingerprint, Optional<OperationKind> successorKind,
               Optional<FailureKind> failureKind, Optional<String> diagnostic, Optional<Boolean> retryable)
enum FailureKind { INVALID_INPUT, PROTOCOL_FAILURE, PERSISTENCE_FAILURE, INFRASTRUCTURE_FAILURE }
```

`FailureKind.externalValue()` and `fromExternalValue(String)` use the exact lowercase underscore values named in the
failed-receipt schema. `Manifest` preserves the four relative filenames exactly. `StateSnapshot.receiptFingerprint` is
empty only for pending/running. A completed `Receipt` requires the result and evidence optionals, forbids all failure
optionals, and permits an empty successor only when the registry says the result is terminal. A failed `Receipt`
requires all three failure optionals, forbids every result/evidence/successor optional, and requires `retryable` to
contain a boolean. Both receipt states require `completedAt >= startedAt`.

`transitionSequence` is the one-based certification-process transition that the operation will produce. `attempt` starts at one and
increments only after a verified retryable `failed` receipt. The directory name and `operation_id` are
`%08d-<operation-kind>-a%03d`, using the transition sequence and attempt. A crash that leaves no receipt reuses the
same attempt; a verified failed receipt is never rewritten. `manifest.json` is immutable and has exactly:

```text
schema_version, operation_id, transition_sequence, attempt, operation_kind, input_sha256,
contract_sha256, environment_sha256, state_path, receipt_path, transcript_path, claim_path
```

The four paths are the relative names `state.json`, `receipt.json`, `transcript.jsonl`, and `claim.json`.
`transcript.jsonl` is a bounded native-harness transcript, not a CAT schema: each line is at most 64 KiB and the
terminal receipt fingerprints the whole file. `OperationDirectory` never interprets or rewrites its lines.

`state.json` has exactly `schema_version`, `operation_id`, `state`, `input_sha256`, `updated_at`, and
`receipt_sha256`. `receipt_sha256` is null for `pending` and `running`, and required for `completed` and `failed`.
Creation atomically writes the immutable manifest and `pending` state. `start` locks `state.json`, verifies the
manifest and input fingerprint, and changes only `pending` to `running`. `complete` or `fail` locks the state, requires
`running`, writes `receipt.json` once without replacement, fingerprints it, and then changes the state to the matching
terminal value. A retry verifies both documents and returns the existing receipt without rewriting either.

A completed receipt contains exactly the common fields
`schema_version`, `operation_id`, `operation_kind`, `transition_sequence`, `attempt`, `input_sha256`,
`contract_sha256`, `environment_sha256`, `state`, `started_at`, and `completed_at`, plus `result_kind`,
`evidence_path`, `evidence_sha256`, and `successor_kind`. `successor_kind` is JSON null only for a registry-terminal
result. A failed receipt contains the common fields plus `failure_kind`, `diagnostic`, and `retryable`; its allowed
failure kinds are `invalid_input`, `protocol_failure`, `persistence_failure`, and `infrastructure_failure`.

Build every JSON object by inserting fields in the order shown above and publish it with `RecordStore`; do not
fingerprint a reserialized Java value. A document fingerprint is `Fingerprints.ofFile(path)` over the exact
published UTF-8 bytes. `receipt_sha256` and `successor_kind` are explicit JSON null when their typed optionals are empty;
no other optional field is serialized into the opposite receipt variant. Readers require the exact field set for the
selected state, exact integer schema version, exact lowercase enum values, canonical absolute evidence paths, canonical
ISO-8601 instants, and lowercase SHA-256 values. Unknown, missing, duplicated, trailing, or variant-incompatible fields
fail before state transition or handler invocation.

`OperationClaim` is the immutable value
`record OperationClaim(String operationId, UUID ownerId, long processId, Instant acquiredAt, Instant renewedAt,
Instant expiresAt)`. Times use canonical UTC ISO-8601 text. `expiresAt` must equal `renewedAt + 30 seconds`.
`OperationClaimDocument` exposes:

```text
Optional<OperationClaim> tryAcquire(JsonMapper, Clock, OperationDirectory, UUID ownerId, long processId)
OperationClaim renew(JsonMapper, Clock, OperationDirectory, OperationClaim expected)
Optional<OperationClaim> read(JsonMapper, OperationDirectory)
```

All methods lock `claim.json`. An absent claim is acquired. The same owner and process may renew it. Another owner gets
empty while `expires_at` is after `Clock.instant()` and may replace it when `expires_at <= Clock.instant()`. Acquisition
sets all three times from the clock; renewal preserves `acquired_at`. A claim document has exactly
`schema_version`, `operation_id`, `owner_id`, `process_id`, `acquired_at`, `renewed_at`, and `expires_at`.
`OperationClaim.Lease.acquire(...)` wraps `tryAcquire`, starts one daemon renewal task at a fixed ten-second interval,
and returns empty when another live lease owns the operation. `Lease.close()` stops renewal but does not delete the
document. A renewal failure is retained and `verifyOwned()` fails before a terminal receipt is written. An interruption
of the owning thread does not stop the renewal task or an already-started harness/evaluator call.

`OperationClaim.Lease` implements `AutoCloseable` with this exact API:

```text
static Optional<OperationClaim.Lease> acquire(JsonMapper json, Clock clock, OperationDirectory directory,
                                              UUID ownerId, long processId) throws IOException
OperationClaim claim()
void verifyOwned() throws IOException
void close()
```

`acquire` returns empty only when a nonexpired different owner holds the claim; malformed state, invalid arguments,
terminal operation state, scheduler creation failure, and document I/O throw `IOException` or the existing argument
exception before returning. The daemon renewer calls `OperationClaimDocument.renew` every ten seconds. It retains the
first renewal `IOException`, stops further renewal, and causes `verifyOwned` to throw that same failure. `close` is
idempotent, stops only the renewal executor, and does not throw. Package-private `renewNow()` performs one synchronous
renewal for deterministic clock-controlled tests.

Add `OptimizationInputResolver`:

```java
@FunctionalInterface
public interface OptimizationInputResolver
{
    OptimizationInput resolve(OptimizationContext context, long transitionSequence,
        Optional<OptimizationResult> predecessor) throws IOException;
}
```

The interface is public. Its caller supplies non-null context and predecessor plus a positive transition sequence; an
implementation rejects invalid arguments before reading evidence and uses `IOException` for missing, malformed, or
incompatible predecessor evidence. The requested operation kind is `context.operationKind()`. The resolver must return that kind; the executor rejects a
mismatch before creating or invoking a handler. `OptimizationContext` therefore adds `operationKind` while
retaining the already specified JSON mapper, clock, certification-process directory, predicted operation directory, contract
fingerprint, and environment fingerprint.

Use these exact constructors and dispatch surface:

```java
public record OptimizationResult(OperationKind operationKind, OperationResultKind resultKind, Path evidencePath,
    String evidenceFingerprint, Optional<OperationKind> successorKind)
{
}

public record OptimizationContext(JsonMapper json, Clock clock, Path certificationProcessDirectory, Path operationDirectory,
    OperationKind operationKind, String contractFingerprint, String environmentFingerprint)
{
}

// Dispatch surface
OptimizationHandlers(Set<OperationKind> requiredKinds, Collection<OptimizationHandler<?>> handlers)
OptimizationResult OptimizationHandlers.execute(OptimizationContext context, OptimizationInput input)
```

Both records are public. Their canonical constructors reject null components. `OptimizationResult` normalizes its
evidence path to an absolute path, requires a lowercase SHA-256 fingerprint, and defensively retains a non-null
successor optional; it does not read the file in its constructor. `OptimizationContext` normalizes both directories,
requires the operation directory to be below `<certificationProcessDirectory>/operations`, and validates both lowercase SHA-256
values. These constructor failures are `NullPointerException` or `IllegalArgumentException`, not `IOException`.

The handler registry
builds an `EnumMap`, so the input collection exposes duplicates instead of silently replacing them. It rejects a
handler outside `requiredKinds`, a duplicate, a missing required kind, a context/input/handler kind mismatch, and an
input for which `handler.inputType().isInstance(input)` is false before invoking the handler. It validates the
handler result and successor against `OptimizationOperationRegistry`, then requires its evidence path to be a regular
file whose current fingerprint matches the result before returning it. Dispatch and evidence failures use
`IOException`; constructor/configuration failures use argument exceptions. `execute` requires non-null context and
input; either null throws `NullPointerException` before dispatch.

`CertificationProcessExecutor` has constructor
`(OptimizationHandlers handlers, OptimizationInputResolver inputResolver, UUID ownerId,
long processId)` and method
`ExecutionResult run(JsonMapper, Clock, Path certificationProcessDirectory, String contractFingerprint,
String environmentFingerprint) throws IOException`. It creates one `OptimizationContext` per operation using the predicted
operation path. The certification-process manifest records
`initial_operation`; the existing initializer overload defaults it to `discover-prompt-system`, while the vertical
slice explicitly initializes `assign-allowance`. `OptimizationStateMachine` replays from that recorded initial kind.
Production initialization accepts only `discover-prompt-system`; the package-private fixture initializer accepts any
registered kind.

The executor constructor requires non-null handlers, input resolver, and owner identifier plus a positive process
identifier. `run` requires a non-null JSON mapper, clock, and certification-process directory plus lowercase SHA-256 contract and
environment fingerprints. It normalizes the certification-process directory to an absolute path. Null required arguments throw
`NullPointerException`; a nonpositive process identifier or malformed fingerprint throws `IllegalArgumentException`.
Missing, malformed, or incompatible persisted certification-process state throws `IOException`.

`run` never initializes a certification process. It requires a preinitialized regular
`<normalized-certification-process-directory>/certification-process.json`, reads its certification-process identifier and initial operation, and requires its
contract and environment fingerprints to equal the method arguments before creating an operation directory. A missing
manifest or mismatch throws `IOException` without creating or changing certification-process state. Production callers initialize
that path through the public initializer before `run`; the vertical-slice test initializes it with certification-process identifier
`codex-certification-vertical-slice` and initial operation `assign-allowance` through `initializeForFixture`.

Add package-private
`CertificationProcessLedger.initializeForFixture(JsonMapper json, Path manifestPath, String certificationProcessIdentifier,
String contractFingerprint, String environmentFingerprint, OperationKind initialOperation) throws IOException` and
package-private `OptimizationStateMachine.State OptimizationStateMachine.initial(OperationKind initialOperation)`.
Both return types are exact: the initializer returns `void`, while the state-machine method returns its existing
`State`. The public certification-process initializer continues to select
`discover-prompt-system`. Add `initial_operation` to the strict certification-process manifest, include it in the manifest identity,
and replay from it. Existing schema-1 manifests without that field are rejected rather than guessed.

The certification-process manifest becomes schema version 2 with exact field order `schema_version`, `certification_process_id`,
`contract_sha256`, `environment_sha256`, `initial_operation`, `transition_ledger`. Its transition entries have exact
field order `sequence`, `attempt`, `operation`, `result`, `successor`, `evidence_sha256`, `completed_at`,
`previous_sha256`, `entry_sha256`. Add `attempt` after `sequence` to the public `CertificationProcessLedger.Entry`
record; it must be positive. For an automatically executed operation, `evidence_sha256` is the completed operation
receipt fingerprint.

`transition_ledger` is a JSON string containing exactly the adjacent filename
`<certification-process-manifest-filename>.transitions.jsonl`; for `certification-process.json`, its value is
`certification-process.json.transitions.jsonl`. Entries are external, one canonical UTF-8 JSON object per line, append-only,
hash-chained, forced after each append, and limited by the existing 64 KiB per-line bound. The field is never an inline
array or an absolute path. Reading rejects another filename, a missing ledger, an invalid line, or a broken chain.

Add this exact API:

```text
static CertificationProcessLedger.Entry recordReceipt(JsonMapper json, Path manifestPath, long expectedSequence,
                                                       OperationDirectory operationDirectory) throws IOException
```

It requires non-null arguments and a nonnegative expected sequence. It obtains the directory's verified terminal
receipt, requires a completed receipt, fingerprints the receipt file, and requires the receipt transition sequence to
equal `expectedSequence + 1`. Under the certification-process-manifest lock, it verifies that the receipt operation is the current
ledger operation and appends its attempt, result, successor, receipt fingerprint, and completion time. An identical
retry returns the retained entry; a mismatch throws `IOException`. Null arguments throw `NullPointerException`; a
negative expected sequence throws `IllegalArgumentException`; a missing, failed, malformed, stale, or conflicting
receipt throws `IOException`.

Retain the existing `record(...)` API for the existing manual certification-process command and make it write attempt 1. The
automatic executor never calls it. Recovery accepts a certification-process transition as automatic-operation evidence only when
its sequence, attempt, operation, result, successor, and `evidence_sha256` resolve to and match the verified completed
receipt at `OperationDirectory.path(...)`; a manual record without that matching receipt is not recoverable by the
automatic executor and cannot contribute to its terminal result.

For each iteration the executor performs this exact sequence:

1. Verify and replay the certification-process ledger. Let `transitionSequence` be its sequence plus one and let its current
   operation be the requested kind.
2. Find attempts for that transition and kind. A verified completed receipt missing from the ledger is replayed into
   the ledger after registry validation. A verified failed receipt starts the next attempt only when `retryable` is
   true and the registry recovery boundary permits retry; otherwise throw `CertificationProcessExecutionException`. A pending or
   running attempt with no receipt is reused.
3. Build the predicted operation path, resolve the typed input, and verify its operation kind and fingerprint against
   any retained manifest before a claim or handler call.
4. Create or open the operation directory, acquire its lease, validate input completely, change `pending` to
   `running` when it is pending, or verify that a recovered attempt is already `running`, and dispatch through
   `OptimizationHandlers`.
5. Verify the returned operation kind, result, evidence path and fingerprint, and selected successor against the
   operation registry. Retain the completed receipt, call `Lease.verifyOwned()`, and append the receipt fingerprint—not
   the underlying evidence fingerprint—to `CertificationProcessLedger`.
6. If interruption was observed during the atomic handler call, restore the thread's interrupted status and throw
   `CertificationProcessInterruptedException` only after the receipt and ledger transition are durable. Recovery resumes from the
   first transition without both verified records.
7. Continue until `finish-certification-process` records `certified` or `not-certified`. The report handlers themselves persist and
   render round output; the executor does not create a second report or user prompt.

`ExecutionResult` contains the terminal certification-process state, final certification-process sequence, and ordered verified
operation receipts. There is no pause, resume, stop, or user-confirmation API.

Define it as public nested record
`CertificationProcessExecutor.ExecutionResult(CertificationProcessState terminalState, long finalSequence,
List<OperationDirectory.Receipt> receipts)`. It accepts only `CERTIFIED` or `NOT_CERTIFIED`, requires a nonnegative
sequence, and defensively copies receipts ordered by transition sequence and then attempt. The final
`finish-certification-process` result `OperationResultKind.CERTIFIED` maps only to `CertificationProcessState.CERTIFIED`; result
`OperationResultKind.NOT_CERTIFIED` maps only to `CertificationProcessState.NOT_CERTIFIED`. The receipt list includes
every verified completed or failed attempt observed during this run or recovery so the caller can audit retries.

Define these public static nested exceptions in `CertificationProcessExecutor`; all extend `IOException`:

```text
CertificationProcessExecutionException(String message, OperationDirectory.Receipt failedReceipt,
                                       List<OperationDirectory.Receipt> receipts, Throwable cause)
OperationDirectory.Receipt failedReceipt()
List<OperationDirectory.Receipt> receipts()
CertificationProcessClaimUnavailableException(String message, OperationClaim activeClaim)
OperationClaim activeClaim()
CertificationProcessInterruptedException(String message, long durableSequence,
                                         List<OperationDirectory.Receipt> receipts)
long durableSequence()
List<OperationDirectory.Receipt> receipts()
```

`CertificationProcessExecutionException` is thrown for a verified non-retryable failed receipt or a retryable receipt whose
registry recovery boundary forbids another attempt. It defensively copies the ordered receipts and never maps the
failure to `NOT_CERTIFIED`. `CertificationProcessClaimUnavailableException` is thrown when lease acquisition returns empty; it
retains the current nonexpired claim and creates no receipt. `CertificationProcessInterruptedException` is thrown only after the
current atomic operation receipt and certification-process transition are durable; it restores the thread's interrupted status and
retains the durable sequence and ordered receipts. These exceptions do not create pause, stop, or terminal certification-process
states.

All three constructors require a non-null, non-blank message. Receipt lists are non-null, contain no null elements,
preserve encounter order, and are defensively copied. `CertificationProcessExecutionException` also requires a non-null failed
receipt that occurs in its receipt list. `CertificationProcessClaimUnavailableException` requires a non-null active claim.
`CertificationProcessInterruptedException` requires a nonnegative durable sequence. A `CertificationProcessExecutionException` cause may be
null because recovery can reconstruct the exception from a retained failed receipt without the original Java
exception. Null required values throw `NullPointerException`; blank messages, a negative sequence, or a missing failed
receipt throw `IllegalArgumentException`.

Add public `OperationFailureException extends IOException` in the optimization package with constructor
`OperationFailureException(OperationDirectory.FailureKind failureKind, boolean retryable, String message,
Throwable cause)` and exact accessors `OperationDirectory.FailureKind failureKind()` and `boolean retryable()`. A
handler uses it only when it cannot produce a registered domain result. The executor maps failures exactly:

Its failure kind and message are non-null, its message is non-blank, and its cause may be null. A null required value
throws `NullPointerException`; a blank message throws `IllegalArgumentException`.

- pre-dispatch input, type, fingerprint, or successor validation failure: `INVALID_INPUT`, not retryable;
- handler `OperationFailureException`: its declared kind and retryable value;
- another handler `IOException`: `INFRASTRUCTURE_FAILURE`, retryable;
- another handler runtime exception: `PROTOCOL_FAILURE`, not retryable; and
- operation-directory or certification-process-ledger publication failure: `PERSISTENCE_FAILURE`, not retryable when a failed
  receipt can still be published; otherwise propagate the original `IOException` with the failed publication attached
  as a suppressed exception.

After retaining a failed receipt, throw `CertificationProcessExecutionException` immediately when it is non-retryable. When it is
retryable, the same `run` invocation proceeds to the next attempt only if the registry recovery boundary permits it;
otherwise it throws. A failed receipt is never a `not certified` result.

`DeletionReceiptLedger` writes only `.cat/history/deletions.jsonl`. Each strict, append-only, hash-chained entry has
`schema_version`, `artifact_sha256`, `deleted_at`, `reason`, ordered `affected_operation_ids`, `previous_sha256`, and
`entry_sha256`. The affected values are operation-instance identifiers such as
`00000003-record-outcome-a001`, not operation kinds. A retry with the same artifact fingerprint and affected identifiers
returns the retained entry; a conflicting retry fails. Reading streams lines through `BoundedJsonLines` with the
global 64 KiB line limit. Deletion never removes or rewrites an operation, certification-process transition, formal outcome, or
prior deletion receipt.

`DeletionReceiptLedger` is a static utility owner with nested
`record Entry(String artifactFingerprint, Instant deletedAt, String reason, List<String> affectedOperationIds,
String previousFingerprint, String entryFingerprint)`. It exposes:

```text
static Entry record(JsonMapper json, Path projectRoot, String artifactFingerprint, Instant deletedAt,
                    String reason, List<String> affectedOperationIds) throws IOException
static Optional<Entry> find(JsonMapper json, Path projectRoot, String artifactFingerprint,
                            List<String> affectedOperationIds) throws IOException
static void read(JsonMapper json, Path projectRoot, Consumer<Entry> consumer) throws IOException
```

All methods resolve only `<normalized-project-root>/.cat/history/deletions.jsonl`. `record` locks that path, streams and
validates the existing chain, returns an identical retained entry for the same artifact and ordered affected-operation
list, rejects a conflicting retry, appends one forced UTF-8 line, and returns it. `find` and `read` stream and validate
the complete chain without accumulating it; `find` retains at most one match. The schema version is persisted but is
not a record component because only version 1 can produce an `Entry`. The consumer is
`java.util.function.Consumer<Entry>`; an exception thrown by it propagates unchanged.

Keep `client/common/cli/src/main/java/module-info.java` aligned with the current public boundaries:
`io.github.cowwoc.cat.common`, `io.github.cowwoc.cat.common.runner`,
`io.github.cowwoc.cat.common.optimization`, and
`io.github.cowwoc.cat.common.optimization.certification`. The Codex vertical slice consumes the public
certification-process API without exposing retired optimizer types. No other new package export is part of Wave 1.

##### Exact vertical-slice fixture

`CertificationProcessExecutorTest` creates this temporary tree:

```text
project/AGENTS.md                 "Always answer with OK.\n"
artifact/.codex-plugin/plugin.json
evidence/assertions.json          {"assertion-1":"response equals OK"}
operation/
runner-runs/
```

The contract uses target `AGENTS.md`, family `family-1`, prompt test `prompt-test-1`, request `Reply now`, and assertion
`assertion-1`. The Java fixture supplies typed static runner evidence: retained project, raw result, transcript,
versions, usage, reproduction state, and every fingerprint. It never starts a native runner. The packaged Bats fixture
owns the fake Codex executable and verifies that the real launcher sends `gpt-5.6-luna`, `medium`, and the expected
protocol events.

The test evaluator checks the raw response equals `OK`, returns pass with the SHA-256 of `assertions.json`, and records
that fingerprint. To make the `certified` result statistically real rather than fixture-forced, the assign-allowance
fixture handler uses the allowance actually returned by the production schedule. It records the fixed-sample reference
count from `Statistics.minimumPassingTrials(1_000, assignment.allowance().doubleValue(), 0.95)`, then calculates
`requiredTrialCount` as the first all-pass trial count whose production sequential lower-bound formula is strictly above
`0.95`. It creates a formal ledger containing `requiredTrialCount - 1` verified, distinct prior pass outcomes for
`family-1`; their prompt-test identifiers, executor evidence files, and evaluator evidence files are all distinct and
fingerprint-valid. Typed static evidence supplies the `requiredTrialCount`th distinct prompt test and pass. The
production family-sequential engine must produce the certification decision; no fixture handler may hard-code
`certified` or bypass its lower-bound calculation. The expected operation order for the new work is `assign-allowance`,
`schedule-trial`, `record-outcome`, `decide`, `report`, `finish-certification-process`; the expected result is
`certified`. Typed tests prove that changed or undeclared input cannot reach a runner or evaluator and that recovery
reuses a verified receipt or retries only unrecorded work.

The test defines one nested
`record VerticalSliceInput(OperationKind operationKind, long transitionSequence, String contractFingerprint,
String environmentFingerprint, Optional<OperationKind> predecessorOperationKind,
Optional<OperationResultKind> predecessorResultKind, Optional<String> predecessorEvidenceFingerprint,
String inputFingerprint) implements OptimizationInput`. Its constructor requires a positive sequence, matching
all-or-none predecessor optionals, lowercase SHA-256 values, and a non-null operation kind.

The fixture `OptimizationInputResolver` builds canonical JSON in this exact order and fingerprints its exact UTF-8
encoding:

```text
schema_version, operation_kind, transition_sequence, contract_sha256, environment_sha256,
predecessor_operation_kind, predecessor_result_kind, predecessor_evidence_sha256
```

The three predecessor fields are explicit JSON null for the first operation and exact lowercase external values or a
SHA-256 thereafter. The resolver returns these exact mappings and rejects any other kind, sequence, or predecessor
before a handler runs:

| Sequence | Operation | Required predecessor result | Handler result | Successor |
|---:|---|---|---|---|
| 1 | `assign-allowance` | none | `assigned` | `schedule-trial` |
| 2 | `schedule-trial` | `assigned` | `scheduled` | `record-outcome` |
| 3 | `record-outcome` | `scheduled` | `recorded` | `decide` |
| 4 | `decide` | `recorded` | `certified` | `report` |
| 5 | `report` | `certified` | `reported-certified` | `finish-certification-process` |
| 6 | `finish-certification-process` | `reported-certified` | `certified` | none |

All six fixture handlers declare `VerticalSliceInput.class`. Each writes one strict operation-owned evidence JSON file
before returning `OptimizationResult`. The record-outcome handler must call the real `FormalTrialOperation`; the decide
handler must call the real family-sequential engine. Use the exact collaborators and behavior below; no handler may
substitute a fake result.

The six handlers are private test-local implementations inside `CertificationProcessExecutorTest`. Their Java form
(private nested classes, lambdas, or anonymous implementations) is intentionally not part of the product contract; the
implementer may choose any of those forms without seeking a design decision. They may share private test-local helper
methods but may not add production types or reusable test-helper files.

- `assign-allowance` calls `CandidateAllowanceLedger.initialize`, `CandidateAllowanceLedger.assign`, and
  `FormalTrialLedger.initialize`, computes `requiredTrialCount` as specified above, then records the
  `requiredTrialCount - 1` distinct prior outcomes through `FormalTrialLedger.record`.
- `schedule-trial` first calls `FamilySequentialEngine.decide` and requires `SAMPLE_FAMILY`, then calls
  `FormalPromptTestScheduler.schedule` with seed `requiredTrialCount`, execution identifier
  `formal-run-<requiredTrialCount>`, the frozen `PromptTestContractCatalog`, and the retained prior identities. It writes
  `scheduled-prompt-test.json` and requires `SCHEDULED`.
- `record-outcome` constructs the frozen `FormalTrialOperation.Request` from that retained schedule, executes it through
  a typed static `HarnessEvaluationRunner` and the test evaluator, and requires a retained pass for prompt test
  `requiredTrialCount`.
- `decide` calls `FamilySequentialEngine.decide`, requires `CERTIFIED`, and writes `decision.json` with exact fields
  `schema_version`, `action`, `current_lower_bound`, `target`, and `selected_family`; the selected family is JSON null.
- `report` calls `CertificationCommand.run` with `decide <allowance-manifest> <trial-manifest>
  <statistical-policy>` and writes its exact output to `report.txt`. It parses the scalar lines and requires them to
  equal `decision.json`, including a current lower bound strictly above the target.
- `finish-certification-process` reads the verified `decision.json` and `report.txt`, requires their fingerprints to match the
  predecessor evidence, writes `terminal-summary.json` with `schema_version`, `terminal_state`,
  `decision_sha256`, and `report_sha256`, and only then returns `certified` with no successor.

The fixture creates these independently fingerprinted inputs before certification-process execution:

```text
fixture/contract.json
fixture/environment.json
fixture/evaluator-policy.json
fixture/statistical-policy.json
fixture/prompt-test-catalog.json
fixture/project/AGENTS.md
fixture/artifact/.codex-plugin/plugin.json
fixture/evidence/assertions.json
```

It stores allowance state under `fixture/state/allowance/`, formal state and the prior executor/evaluator evidence
files under `fixture/state/formal/`, and the retained schedule under `fixture/state/schedule/`.

Every handler writes `<operation-directory>/handler-evidence.json` with exact field order
`schema_version`, `operation_kind`, `result_kind`, `inputs`, `outputs`. Each array item has exact field order `id`,
`path`, `sha256`; paths are absolute, identifiers are unique and sorted, and fingerprints use
`Fingerprints.ofArtifact` so a path may identify one file or a complete state directory. Use these exact
artifact sets:

| Handler | Input artifact identifiers | Output artifact identifiers |
|---|---|---|
| `assign-allowance` | `contract`, `environment`, `evaluator_policy`, `statistical_policy` | `allowance_state`, `formal_state` |
| `schedule-trial` | `allowance_state`, `formal_state`, `prompt_test_catalog` | `scheduled_prompt_test` |
| `record-outcome` | `allowance_state`, `artifact`, `assertions`, `formal_state_before`, `project`, `scheduled_prompt_test` | `evaluator_evidence`, `formal_state_after`, `harness_operation`, `runner_receipt`, `transcript` |
| `decide` | `allowance_state`, `formal_state`, `statistical_policy` | `decision` |
| `report` | `allowance_state`, `decision`, `formal_state`, `statistical_policy` | `report` |
| `finish-certification-process` | `decision`, `report` | `terminal_summary` |

Capture `formal_state_before` before invoking `FormalTrialOperation` and `formal_state_after` after its durable append.
For every other state artifact, fingerprint it immediately before the handler reads it or immediately after the handler
finishes writing it. The handler returns the path and fingerprint of `handler-evidence.json`; that document is not
listed as one of its own outputs.

##### Agent adapter API

Add `ContractAgentAdapter<I, O>` in the contract package:

```text
roleIdentifier()
inputType()
outputType()
O invoke(ContractAgentContext context, I input)
```

`ContractAgentContext` contains `HarnessEvaluationRunner`, the exact supported model profile, operation directory, and
read-only evidence paths. Add these concrete adapters and no generic runtime-selected role:

`PrimaryRequirementExtractorAdapter`, `IndependentRequirementChallengerAdapter`,
`PromptTestFamilyDesignerAdapter`, `InteractionPromptTestFamilyDesignerAdapter`,
`EvaluatorProtocolDesignerAdapter`, `MonotonicContractReviewerAdapter`, and `ContractDisagreementResolverAdapter`.
Each writes canonical input to `<operation>/agent-input.json`, calls the supplied harness runner once, reads only
`<operation>/agent-output.json`, validates it through its existing strict output document, and returns the typed value.
The adapter never repairs output. `ContractExtractionWorkflow` receives all seven adapters through its constructor and
invokes them in the order defined in “Automatic extraction.” Static fake runners in unit tests write the complete
output JSON. Native Codex and Claude integration tests use the same `ContractAgentContext` boundary.

Add `CertificationContractDraftDocument`, which strictly reads and writes `CertificationContract.Draft`, and
`ContractDisagreementResolutionDocument`, which strictly reads and writes
`ContractDisagreementResolutionRequest(disagreements, mergedDraft, resolutionPass)` and
`ContractDisagreementResolution(orderedDecisions)`. Use this exact adapter table:

| Adapter class | `roleIdentifier()` | Input Java/document type | Output Java/document type | Operation kind |
|---|---|---|---|---|
| `PrimaryRequirementExtractorAdapter` | `requirement-extraction-primary` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `extract-contract` |
| `IndependentRequirementChallengerAdapter` | `requirement-extraction-challenger` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `extract-contract` |
| `PromptTestFamilyDesignerAdapter` | `prompt-test-family-design` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `extract-contract` |
| `InteractionPromptTestFamilyDesignerAdapter` | `interaction-prompt-test-family-design` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `extract-contract` |
| `EvaluatorProtocolDesignerAdapter` | `evaluator-protocol-design` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `extract-contract` |
| `MonotonicContractReviewerAdapter` | `monotonic-contract-review` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `CertificationContract.Draft` / `CertificationContractDraftDocument` | `extract-contract` |
| `ContractDisagreementResolverAdapter` | `contract-disagreement-resolution` | `ContractDisagreementResolutionRequest` / `ContractDisagreementResolutionDocument` | `ContractDisagreementResolution` / `ContractDisagreementResolutionDocument` | `extract-contract` |

For the first six adapters, `inputType()` and `outputType()` both return `CertificationContract.Draft.class`. The
resolver returns `ContractDisagreementResolutionRequest.class` and `ContractDisagreementResolution.class`; its adapter
validates every returned decision against a named input disagreement before returning it.

##### `extract-contract` operation boundary

`ContractExtractionInput` is the only typed input accepted by the `extract-contract` handler. It contains the
immutable `CertificationContract.Draft` inventory and an `inventory_sha256`. The input resolver writes the inventory
once with `CertificationContractDocument.writeDraft` to
`<operation-directory>/input/inventory.json`, hashes those exact bytes with `Fingerprints.ofFile`, and constructs
the input only when that hash equals `inventory_sha256`. `inputFingerprint()` returns `inventory_sha256`; no agent
identity, model response, or mutable path contributes to it.

`ContractExtractionHandler` receives its seven fixed adapters at construction. It rejects any operation other than
`extract-contract`, reruns the same byte-hash check before invoking an adapter, and calls
`ContractExtractionWorkflow.extract` exactly once. It writes one strict result payload at
`<operation-directory>/contract-extraction-result.json`:

```text
schema_version
status
completed_stages
blocked_stage             # JSON null only for ready
contract_sha256           # JSON null only for blocked
```

For `ready`, it writes the frozen contract to `<operation-directory>/contract.json`, sets `contract_sha256` to the
hash of that file, and returns result `ready` with successor `validate-evaluator-protocols`. For `blocked`, it writes
no contract, records the named blocked stage, and returns result `blocked` with successor `finish-preparation`. The
handler never retries a resolver decision, repairs an adapter response, or substitutes an actor. The operation result
references the result payload and its exact SHA-256 fingerprint.

##### Development- and cost-policy artifacts

Store the three Wave 3 development candidates as one strict classpath resource:

`client/common/cli/src/main/resources/io/github/cowwoc/cat/common/optimization/development/development-policy-candidates-v1.json`.

Its root is `{"schema_version":1,"policies":[...]}`. Each policy uses the existing
`DevelopmentPolicyDocument` fields after replacing aggregate win/loss fields with `comparison_revision` equal to
`paired-prompt-test-family-v1` and the eight numeric values listed in Wave 3. `DevelopmentPolicySimulationCommand`
accepts `<trajectory-jsonl> <output-json>`, partitions by SHA-256 of trajectory identifier modulo 10 (`0`–`5`
selection, `6`–`7` calibration, `8`–`9` untouched validation), applies the published selection/tie gates, and writes
the chosen complete policy to the supplied output. Runtime loads exactly
`.cat/policies/development-policy-v1.json` through `DevelopmentPolicyDocument.read`; absence or failed validation is a
preflight failure. Tests use `src/test/resources/optimization/development/policy-trajectories-v1.jsonl` with one
trajectory for every policy/outcome branch and assert the exact selected identifier `development-balanced-v1`.

Store the packaged empty cost bootstrap at
`client/common/cli/src/main/resources/io/github/cowwoc/cat/common/optimization/cost/cost-policy-bootstrap-v1.json` with
exact content `{"schema_version":1,"policy_id":"cost-policy-bootstrap-v1","bands":[]}`. The customer-local corpus
is `.cat/calibration/cost-trajectories.jsonl`; each line contains trajectory identifier, the seven band keys named in
Wave 4, actual input/cached/output tokens, exact cost or `null`, and elapsed milliseconds. `CostPolicyCalibrationCommand`
accepts `<trajectory-jsonl> <output-json>` and uses the same hash-modulo partition. It writes
`.cat/policies/cost-policy-v1.json` only when that explicit output path is supplied. With no eligible band it writes a
valid empty policy, and runtime deterministically reports `cost unavailable`. Tests use
`src/test/resources/optimization/cost/calibration-trajectories-v1.jsonl`, containing 30 entries in one passing band and
one 29-entry ineligible band.

##### Signed lease protocol

The lease uses Ed25519 with an X.509 DER public key encoded as PEM and a base64 signature. The response document fields
are exactly:

```text
schema_version             1
lease_id                   opaque identifier
account_id                 opaque identifier
device_id                  UUID matching the request
status                     entitled or denied
issued_at                  UTC ISO-8601 instant
lease_expires_at           UTC ISO-8601 instant
subscription_ends_at       UTC ISO-8601 instant
denial_reason              absent for entitled; present for denied
signature_algorithm        Ed25519
signature                  base64
```

Canonical signed bytes are Jackson's UTF-8 serialization of a newly built object containing every field except
`signature` in the order shown above, omitting `denial_reason` only when absent; no whitespace is added. Verification
rebuilds those bytes and never signs received raw bytes. Reject an unknown field, wrong device, invalid Base64,
algorithm other than Ed25519, invalid signature, `issued_at` more than five minutes in the future, expiry not after
issue, an entitled lease longer than 24 hours plus five minutes, or subscription end before issue. The effective end is
the minimum of subscription end and seven days after the most recent verified entitled lease. A denied lease has
`lease_expires_at` equal to `issued_at` and takes effect at the signed subscription end or immediately when that end is
not later than issue.

HTTP mapping is exact: `200` parses a lease; `401` deletes no local lease and reports `Sign in to CAT again`; `403`
must contain a valid signed denied lease; `429` and `500`–`599` are retryable renewal failures; every other status is a
nonretryable protocol failure. A transport failure is retryable. No failure extends the seven-day maximum.

##### Release publisher contract

`publish-release.sh` accepts only named arguments:

```text
--edition personal|commercial
--staged-tree ABSOLUTE_PATH
--repository HTTPS_OR_SSH_GIT_URL
--version MAVEN_VERSION_WITHOUT_V_PREFIX
--git ABSOLUTE_GIT_EXECUTABLE
```

The staged tree contains exactly root `LICENSE.md`, `README.md`, `release-manifest.json`, and directories `codex/` and
`claude/`; each harness directory contains exactly
`linux-x86_64`, `linux-arm64`, `macos-x86_64`, and `macos-arm64`. The script rejects anything else before cloning. It
creates a directory with `mktemp -d`, clones `main`, fails if the version tag exists, removes only the six allowed
release-tree entries from the checkout, copies the staged entries without following symlinks, and runs:

```text
client/release/test-staged-release.sh CHECKOUT EDITION VERSION
```

Authentication is owned entirely by the supplied Git executable's existing credential configuration; the script reads
no token and constructs no authenticated URL. It uses the checkout's configured Git identity, creates one commit with
message `Release CAT VERSION (EDITION)`, creates an annotated tag named exactly `VERSION`, then runs
`GIT push --atomic origin main refs/tags/VERSION`. On success it deletes the temporary clone. On any failure it leaves
the clone unchanged at the failure point, prints `Retained failed release checkout: PATH` to standard error, and exits
nonzero. It never deletes a remote ref or retries a non-fast-forward push.

##### Launch-gate registry and terminal commands

The stable gate identifiers, one for each frontend acceptance bullet, are:

```text
personal-lifecycle
commercial-lifecycle
platform-matrix
release-tag-integrity
commercial-lease-continuity
feedback-and-analytics-privacy
prompt-system-boundary
certification-terminal-matrix
work-execute-contract
license-offer-consistency
```

`FrontendLaunchGate` accepts a version, report destination, and one receipt path for each identifier. The release
script creates a new attempt directory below `target/frontend-launch-gates/VERSION/` before every invocation; each
attempt contains its own immutable receipts, logs, report, and handoff. A later attempt never replaces an earlier
attempt's evidence. Each receipt is a
strict document with `schema_version`, `gate_id`, `status`, `command`, `started_at`, `completed_at`, ordered
`evidence_paths`, ordered `evidence_sha256`, and `diagnostic`. Status is immutable once written. `passed` requires a
zero command exit, at least one regular evidence path, and matching fingerprints. `failed` requires a nonzero exit and
diagnostic. `unrun` requires no command execution plus the missing executable, credential, harness, or platform in the
diagnostic. `excluded` additionally requires `authorized_by` and `reason`; only the aggregator option
`--exclude GATE --authorized-by PRODUCT_OWNER --reason TEXT` can create it. The aggregate report repeats the ten gates
in the order above and fingerprints each receipt. A missing receipt becomes `unrun`, never `passed`. Any failed or
unrun required gate makes the aggregator exit `1` and suppresses `backend-handoff.json`.

`run-frontend-launch-gate.sh VERSION STAGED_PERSONAL STAGED_COMMERCIAL` creates one fresh attempt directory, then runs
commands through one helper. Exit `0`
writes `passed`, exit `77` writes `unrun`, and every other exit writes `failed`; the script continues to aggregate all
receipts. The commands are exact:

| Gate | Command |
|---|---|
| `personal-lifecycle` | `client/release/test-staged-release.sh STAGED_PERSONAL personal VERSION` |
| `commercial-lifecycle` | `client/release/test-private-install.sh STAGED_COMMERCIAL commercial VERSION`; this exits `77` when `CAT_COMMERCIAL_TEST_REPOSITORY` or configured Git credentials are absent |
| `platform-matrix` | `client/release/test-platform-matrix.sh STAGED_PERSONAL STAGED_COMMERCIAL VERSION`; it runs the current tuple and consumes sibling CI receipts for the other native platforms plus WSL, returning `77` while any required receipt is absent. A receipt has exactly `schema_version: cat-platform-receipt-v1`, release version, platform, `execution_environment: native` or `wsl`, and creation time. `--record` accepts only a native platform; `--record-wsl` also proves the active Linux kernel is WSL. |
| `release-tag-integrity` | `client/release/test-release-tag.sh STAGED_PERSONAL STAGED_COMMERCIAL VERSION`; local bare repositories stand in for both remotes |
| `commercial-lease-continuity` | Maven `-Dtest='*CommercialEntitlement*,*EntitlementLease*,*CommercialAccountClient*'` in `:client-common-cli` |
| `feedback-and-analytics-privacy` | Maven `-Dtest='*Feedback*,*UsageAnalytics*'` in `:client-common-cli` |
| `prompt-system-boundary` | Maven `-Dtest='*PromptSystem*,*ContractExtraction*,*PromptTest*'` in `:client-common-cli` |
| `certification-terminal-matrix` | `client/release/test-terminal-certification.sh STAGED_PERSONAL STAGED_COMMERCIAL VERSION`; actual Codex or Claude absence returns `77` |
| `work-execute-contract` | `client/common/agents/src/test/bats/work-execute-agent.bats`; it returns `77` unless `CAT_RUN_ACTUAL_AGENT_TESTS=1` and both harness credentials are available |
| `license-offer-consistency` | `client/release/test-license-offer-consistency.sh STAGED_PERSONAL STAGED_COMMERCIAL docs/PRICING.md LICENSE.md VERSION` |

Every Maven command begins `client/mvnw -Dmaven.build.cache.enabled=false -f client/pom.xml -pl MODULE -am` and ends
`-Dsurefire.failIfNoSpecifiedTests=false test`. Before the ten gate commands, the script runs
`client/mvnw -Dmaven.build.cache.enabled=false -f client/pom.xml verify`; failure is recorded as a `failed` diagnostic
on every gate whose command did not subsequently run. The actual-agent Bats suite owns three directories:
`src/test/resources/work-execute/complete`, `ambiguous`, and `failing`. Their `plan.md` files respectively require
creating `result.txt` with exact contents `implemented\n` and running `test -f result.txt`; require two incompatible
exact contents for the same result file; and require running `false`. Assert statuses `SUCCESS`,
`BLOCKED_PLAN_NOT_MECHANICAL` without workspace mutation, and `FAILED`.

### F0 — Freeze public and internal contracts

**Owners:** root product documents, common domain package, and versioned JSON schemas.

1. Assign stable identifiers and closed result types to model profiles, harnesses, operation kinds, operation states,
   evaluator verdicts, reproduction states, certification-process states, terminal outcomes, and release targets.
2. Define strict schemas for the observation inventory, optimization target, edit boundary, related-file evidence,
   certification contract, operation manifest, evidence receipt, environment snapshot, trial ledger,
   promotion-allowance ledger, candidate-allowance ledger, evaluator policy, development policy, cost policy,
   certificate, and release manifest.
   Reject unknown required-domain values and unknown fields unless a schema explicitly owns an extension field.
3. Register every operation with one owner, input schema, result-specific successor set, retry identity, interruption
   behavior, and terminal evidence. Generate the executable state-machine matrix from that registry and reject any
   pause, resume, or user-stop transition from an active certification process, target replacement, and unvalidated
   prompt-system write.
4. Record `gpt-5.6-luna` with `medium` effort as the initial Codex profile and the `haiku` alias with `medium` effort
   as the initial Claude profile. The harness-boundary tests must prove that CAT sends those exact values; they may not
   silently substitute a versioned model name or another reasoning setting.

**Terminal outcome:** every later package can consume a versioned contract without inventing a state, threshold,
successor, identifier, or recovery policy.

**Acceptance evidence:** schema round-trip and rejection tests, one accepted and one excluded value at every untyped
ingress, registry completeness tests, change-manifest tests that accept related additions and splits and reject
unrelated or out-of-bound writes, and an executable
state-machine test that visits every registered edge and both terminal outcomes of the certification process.

### F0A — Add the mechanical implementation subagent

**Owners:** common agent resources, Codex and Claude Code agent wrappers, and distribution assembly.

1. Add one harness-neutral `work-execute` body and `cat-work-execute-plan-v1` manifest. Require read-only preflight before mutation and define the exact
   `BLOCKED_PLAN_NOT_MECHANICAL` ambiguity payload.
2. Treat a plan as mechanical only when each step determines its files, behavior, integration, contract decisions,
   tests and expected results, verification, order, acceptance evidence, exact write allowlist, and workspace mode.
   Return every established ambiguity to the parent instead of selecting an implementation.
3. After preflight passes, execute only prescribed steps. Do not delegate, broaden scope, select a workaround, commit,
   publish, or update issue state unless the plan specifies the exact action and inputs. Treat wider verification as
   read-only; report out-of-scope dependencies and concurrent workspace changes instead of repairing or absorbing them.
4. Add a Codex wrapper using `gpt-5.6-luna` with `medium` effort and workspace-write isolation. Add a Claude Code
   wrapper using `haiku` with `medium` effort and only read, search, edit, write, and shell tools.
5. Assemble both wrappers with the common body into their release artifacts.
6. Route every write-capable parent delegation through the common delegation rule. Require separate worktrees for
   concurrent writers, parent-owned sequential integration, changed-path allowlist validation, and a new integrated
   snapshot before dependent work starts. Permit one `exclusive_shared` writer when no authoritative worktree and
   integration owner exists.

**Terminal outcome:** a parent can delegate a complete implementation plan without transferring implementation-design
authority, and receives a precise repair request instead of an invented decision when the plan is incomplete.

**Acceptance evidence:** structural packaging checks that both harness artifacts contain valid metadata and resolve only
shipped paths; one actual-agent fixture whose complete plan produces the exact file change and passing verification;
one ambiguous-plan manifest that returns the structured status before a harness starts and leaves the workspace
unchanged; one ambiguous-plan fixture that returns the structured status with the plan location, problem, needed decision, and
evidence while leaving the workspace unchanged; and one fully specified failing operation that returns `FAILED` rather
than misclassifying the result as plan ambiguity. Add
fixtures proving that a verification failure in an unowned file does not change that file, an unexplained concurrent
change stops execution, concurrent writers receive distinct worktrees from the same snapshot, changed paths outside an
allowlist are rejected, and accepted results are integrated one at a time before their combined checks run.

### F1 — Complete one Codex execution-to-evidence vertical slice

**Owners:** common operation orchestration, common runner contract, and Codex runner adapter.

1. Accept one frozen fixture contract containing one prompt-test family, one prompt test, one deterministic atomic
   assertion, one Codex executor profile, and one evaluator verdict supplied through the evaluator boundary.
2. Materialize an immutable operation directory and write `pending` before any harness action. Change it to `running`
   only after all inputs are valid.
3. Submit a typed harness request carrying `gpt-5.6-luna`, `medium`, the frozen prompt input, declared read-only
   evidence, and operation-owned transcript and receipt destinations. Reuse the existing `HarnessEvaluationRunner`;
   the Codex
   module remains the sole owner of Codex process and protocol details.
4. Convert the retained runner result into a typed assertion verdict. Record one terminal operation receipt containing
   input and output fingerprints, exact model profile, harness and runner versions, timestamps, evidence locations,
   usage when available, and reproduction state.
5. On retry with the same idempotency key and fingerprints, return the retained terminal result without invoking the
   runner. Reject the key when a fingerprint differs. On interruption, finish the already-started atomic harness
   operation when its owner remains live, record its terminal result, and invoke the registered successor. If the owner
   was lost, automatic recovery retries only the unrecorded operation under the same idempotency contract.

**Terminal outcome:** one Codex operation proceeds from a frozen contract through local harness execution and evaluator
verdict to retained, reproducible evidence without reading a customer repository or contacting CAT's backend.

**Acceptance evidence:** Java tests with typed static runner and evaluator evidence for orchestration, the packaged
Codex Bats boundary test that proves the real launcher receives the selected model and effort, an undeclared-input
rejection test, idempotent-retry and changed-input tests, interruption recovery evidence, and an end-to-end fixture
whose receipt verifies after process completion.

### F2 — Durable operation and evidence lifecycle

**Owners:** common operation store, evidence store, fingerprinting, and recovery command.

1. Implement atomic writes, compare-and-set transitions, operation claims, renewable ownership, and exactly one terminal
   receipt per operation.
2. Stream unbounded ledgers and transcripts; bound every whole-document read by schema limits. Separate immutable
   evidence from transient content and record deletion receipts.
3. Implement read-only status and automatic recovery from the durable transition record. Reuse verified units, rerun
   only a claimed unit that lacks terminal evidence, and expose no pause, resume, or user-stop command.
4. Verify content fingerprints on every reuse. If client-retained evidence is deleted, preserve the historical result,
   set `Reproduction: unavailable`, and reject it as input to new formal evidence.

**Terminal outcome:** disconnect, retry, deletion, and stale work cannot duplicate model spend, overwrite verified
progress, or make missing evidence appear reproducible.

**Acceptance evidence:** crash-point tests for every durable transition, concurrent-claim tests, duplicate and stale
writer rejection, bounded-memory ledger tests, deletion and reproduction-state tests, and recovery tests from each
nonterminal state.

### F3 — Contract extraction and prompt-test generation

**Owners:** prompt-system discovery and analysis, change-manifest validation, extraction-policy engine,
deterministic reducers, prompt-test generator, and review agents.

1. Resolve the effective prompt system for every covered invocation, identify the one optimization target and edit
   boundary, and record the evidence that connects each related file to the target. Reject an unaccounted loaded input.
2. Convert target requirements, related-file preservation requirements, relevant interactions, and client-side tests
   into atomic assertions, applicability rules, prompt-test groups, prompt-test families, prompt-test generators, and
   interaction prompt-test families through the frozen monotonic extraction workflow.
3. Make deterministic reducers own normalization, deduplication, traceability, coverage checks, reference-distribution
   validation, domain-separated development, readiness, and formal random streams, and seed and execution-identity
   leakage detection. Agents
   supply semantic proposals only; they cannot waive a deterministic gate.
4. Return `blocked` with named disagreements or missing coverage rather than inventing a prompt-test family,
   distribution, or evaluator. A prompt-test addition, removal, or material edit creates a new certification-contract
   version.
5. Keep prompt tests and their contents client-side. History may identify their project-relative paths and content
   hashes, but a hash does not remove privacy obligations if it can identify or be linked to customer data.

**Terminal outcome:** a complete frozen contract reaches evaluation with every requirement assigned and no runtime
component required to invent testing policy.

**Acceptance evidence:** effective-context fixtures for direct dependencies, conditionally loaded related prompts,
and an unrelated unloaded file; accepted edits, additions, and splits; rejection of unrelated and out-of-bound writes;
extraction conformance matrix; missed-requirement and false-split fixtures; reviewer-disagreement
paths; generator distribution and independent-stream property tests; interaction coverage tests; and deterministic
replay to the same contract fingerprint.

### F4 — Evaluator protocols and security isolation

**Owners:** deterministic evaluator, semantic evaluator boundary, protocol registry, and calibration corpus.

1. Implement deterministic assertions first. For semantic assertions, start with one approved high-end evaluator using
   atomic assertions, strict cited evidence, a frozen rubric, and typed verdicts.
2. Compare each assertion family against the approved two-evaluator-plus-tiebreaker protocol. Enable adjudication only
   where the evaluator-policy manifest shows a material validated improvement relative to cost.
3. Keep evaluator instructions structurally separate from length-bounded executor evidence. Treat all executor, tool,
   retrieved, and file content as untrusted data that cannot alter the evaluator rubric.
4. Run known-good, known-bad, ambiguous, prompt-injection, self-preference, and adversarial-gaming controls before the
   protocol can contribute formal evidence. Any model, reasoning, rubric, prompt, schema, aggregation, or isolation
   change makes prior certificates stale.

**Terminal outcome:** every assertion family has one approved, frozen evaluator protocol whose verdict is the Bernoulli
outcome consumed by certification.

**Acceptance evidence:** calibrated corpus reports, reproducibility and error-rate gates, injection-isolation tests,
single-versus-adjudicated cost/benefit results, and protocol-drift stale-certificate tests.

### F5 — Selection-aware statistical certification

**Owners:** `prompt-test-family-sequential-v1`, immutable allowance ledgers, adaptive scheduler, and report projection.

1. Implement and numerically verify the sequential one-sided bound for each prompt-test family and its inversion.
   Allocate one summable 5% lineage false-certification allowance across adaptively selected candidates without changing
   the certified object: the certificate still names one frozen prompt-system snapshot.
2. Schedule trials independently by prompt-test family according to information value. A prompt-test run may cover
   several assertions, but a prompt-test family receives only outcomes drawn from its frozen prompt-test distribution.
3. Compute the scalar weakest-family simultaneous bound used for both the decision and `Current lower bound`. Do not
   display separately adjusted percentages for individual prompt-test families or unadjusted diagnostic bounds as
   simultaneous guarantees.
4. Return `certified` only when every required prompt-test family crosses the target under the shared controls.
   Otherwise
   return an intermediate batch result or the automatic `not certified` terminal record selected by the state machine.

**Terminal outcome:** the retained certificate supports the documented false-certification claim across every permitted
adaptive selection path.

**Acceptance evidence:** deterministic numerical fixtures, property tests, the written coverage argument, adversarial
simulation with its one-sided upper bound at or below 5%, allowance-exhaustion tests, and legacy-evidence rejection.

### F6 — Efficient optimization and readiness decisions

**Owners:** candidate generator, failure diagnosis, development scorer, comparison policy, and readiness policy.

1. Cache certification-contract, prompt-test, and deterministic-check work by fingerprint. Generate multiple bounded
   challengers from the ordered, evidence-backed diagnosis and preserve a champion that can be restored independently
   of later attempts. Validate and race challengers using the Candidate creation and racing contract before spending on
   broader paired comparisons.
2. Use selection-aware paired A/B comparisons on shared development prompt tests. Keep development evidence separate
   from formal evidence and never present development comparisons as certification bounds.
3. Allocate one 5% false-promotion allowance across the optimization lineage. Promote a challenger only when the
   simultaneous paired test shows a positive weakest-family change, no material family regression, and every
   deterministic and interaction check passes. Reject an inconclusive challenger at the policy's maximum pair count.
4. Allow a challenger to add, split, rename, delete, or edit related prompt files inside the edit boundary. Validate
   its complete load graph and change manifest before a model call, and keep the target entry file unchanged as the
   measured object.
5. After each optimization round, show the best validated candidate, current lower bound or `unavailable`, observed
   cost, forecast when calibrated, limiting evidence, and next automatic action. Then continue automatically while the
   harness session remains active. If the user interrupts the harness, retain completed atomic evidence for the next
   invocation.
6. When interaction evidence identifies a related prompt as a likely cause and repair has lower estimated total cost,
   repair it automatically in an isolated challenger, validate the frozen contract, and start formal testing when the
   readiness policy selects it. An out-of-bound requirement returns `edit boundary exceeded`. CAT may diagnose but
   cannot
   edit observation-only artifacts, third-party system prompts, or harness behavior.

**Terminal outcome:** optimization reaches `certified` or a truthful `not certified` result while minimizing expected
local model cost under the validated policy, without spending formal error allowance on ordinary development search.

**Acceptance evidence:** untouched simulation comparison with conservative and one-candidate baselines,
false-promotion controls across one certification process, paired-comparison tests, scheduling for limiting prompt-test
families, deterministic cause ordering, invalid-proposal rejection, five-candidate limit, candidate-race ordering,
tests, best-candidate restoration, plateau and futility paths, valid add/split repair tests, and unrelated and
out-of-bound write rejection.

### F7 — Cost measurement and forecasting

**Owners:** provider price catalog, exact-money types, usage meter, and forecast calibration.

1. Record exact currency, pricing units, source revision, rounding, subscription-versus-API interpretation, and
   unavailable-price behavior. Do not invent a marginal price for flat subscription capacity.
2. Calibrate forecasts only within recorded model, harness, evaluator, total number of prompt-test families, number of
   interaction prompt-test families, and candidate-count bands. Detect infeasible numbers of prompt-test families or an
   uncalibrated envelope before expensive execution and report that no reliable total forecast is available.
3. Retain forecast, actual provider usage when available, estimated provider cost, error, and elapsed time per operation
   without sending those records to CAT unless the user enables the documented Usage analytics event.

**Terminal outcome:** every completed round reports measured or estimated local provider cost, and every forecast is
either calibrated and qualified or explicitly unavailable; neither result controls whether local execution may proceed.

**Acceptance evidence:** exact-money and rounding tests, provider-catalog fixtures, forecast applicability tests,
unpriced-subscription behavior, prediction-error simulation, and cumulative-cost reconciliation.

### F8 — Certificate, history, and stale-environment experience

**Owners:** local certificate writer, history index, environment comparator, exporter, and CLI projection.

1. Write the scalar `Current lower bound`, target, terminal result, exact contract and environment, lineage ledger,
   evaluator protocols, statistical revision, evidence fingerprints, and local trust-boundary statement.
2. Compare every recorded environment value with the active environment. Name old and new values and affected scope,
   mark the result `Stale certificate`, and make the active current lower bound unavailable until new formal evidence
   exists.
3. Export and verify history without requiring CAT's backend. Preserve a historical signed result after evidence
   deletion while stating that it cannot be reproduced.

**Terminal outcome:** a user can identify what was proved, under which setup, whether it is reproducible, and exactly
why it became stale without interpreting internal statistical terminology.

**Acceptance evidence:** golden CLI and export fixtures, every environment-change comparison, corrupt-history
rejection, missing-evidence behavior, and a local-forgery limitation shown consistently in documentation and output.

### F9 — Claude parity

**Owners:** Claude runner adapter and Claude-owned external tests.

1. Use the selected Claude `haiku` model alias with `medium` effort and prove those exact values at the harness
   boundary.
2. Implement the same typed runner result, retained evidence, interruption, idempotency, evaluator, cost, and history
   contracts as Codex without putting Claude details in common code.
3. Run the complete common optimizer and certification suite through static evidence once, then run only Claude-specific
   launcher, installer, protocol, and lifecycle tests in the Claude module.

**Terminal outcome:** Claude Code can return both `certified` and `not certified` with behavior equivalent to Codex
where the harness supports it.

**Acceptance evidence:** Claude profile conformance, external runner lifecycle, clean-install, interruption, and
end-to-end retained-evidence tests plus documented exclusions for any capability the harness cannot emulate.

### F10 — Work entitlement, feedback, and Usage analytics client

**Owners:** commercial entry-point guard, lease cache, account client, feedback client, and analytics allowlist.

1. Gate prompt tuning, evaluation, and certification with the signed 24-hour commercial lease and seven-day maximum
   offline grace. A backend outage cannot erase local evidence or interrupt an already-started atomic operation.
2. Implement `$cat:feedback` as an explicit preview-and-confirm workflow. Use harness history to capture relevant
   context. Include a file only when the user explicitly adds its path before preview; do not discover or inspect files
   implicitly. The preview states exactly what will be sent and that the content will only be visible to CAT staff.
3. Implement Usage analytics as an opt-in, versioned allowlist that excludes prompts, tests, conversations, filenames,
   raw traces, and stable customer identifiers. Reject unknown fields locally before transmission.

**Terminal outcome:** Work-only functionality follows the entitlement contract, feedback never sends an unconfirmed
payload, and Usage analytics cannot expand silently.

**Acceptance evidence:** lease renewal/outage/expiry tests, already-started operation behavior, feedback preview,
cancel, delivery failure and receipt tests, history-only and optional-file payload tests, and analytics allowlist tests.

### F11 — Retire the old optimizer

**Owners:** reuse-audit record, retirement implementation, and packaged-distribution verifier.

1. Before deleting an old component, complete the reuse audit described in Certification-engine replacement and
   migration. Retain only a component whose new optimizer caller and focused contract are recorded. A component with no
   retained caller is `retire`; a component with a replacement owner is removed only after that owner passes its named
   acceptance evidence.
2. Replace the old optimizer entry point with the new Work `prompt-optimizer` skill. Remove the old optimizer's
   prompt-tester entry points, whole-suite certification policy, SPRT decision path, commands, agents, rules, tests,
   fixtures, documentation, build registrations, and packaged files unless the audit classifies a focused generic
   component as `retain`. Do not leave a hidden compatibility route, alias, or legacy result that a supported consumer
   can invoke.
3. Remove or replace every caller of a retired component. Preserve historical evidence only as non-executable history;
   it cannot start, influence, or validate a new optimization or certification process.
4. Run an artifact-wide identifier search after removal. Every remaining old-system reference must either name retained
   historical evidence or identify a retained generic component in the reuse audit. Build both distributions and prove
   that neither discovers, packages, nor invokes the retired path.

**Terminal outcome:** CAT has one optimizer and certification workflow: the new local prompt optimizer. No supported
consumer can invoke or receive the old optimizer.

**Acceptance evidence:** the committed reuse-audit record, removal and retained-entry-point behavior tests,
artifact-wide reference audit, clean Personal and Work package inspections, and Codex and Claude end-to-end
tests of the new optimizer after retirement.

### F12 — Release construction and installation

**Owners:** existing Java 26 jlink distribution builder, edition manifest, platform packager, and harness installers.

1. Reuse the existing `JlinkImageBuilder` and bundled JDK 26 runtime; do not introduce another runtime assembly path.
2. Define eight first-release tuples per edition, sixteen across both editions: Codex and Claude Code on Linux and
   macOS, each on x86-64 and ARM64, for Personal and Work. WSL installs and runs the matching Linux tuple and
   receives its own installation check.
3. Produce clean release trees containing only end-user runtime files selected by the edition allowlist. Verify package
   checksums, version metadata, target identifiers, Work-only entry points, and absence of source and build
   inputs.
4. Publish each tested tree to `main` in its selected release repository and create the immutable version tag. Test a
   non-production version through the exact supported plugin installation and update paths before publishing production.

**Terminal outcome:** each advertised tuple installs from its supported repository into a clean harness and exposes
exactly the entitled functionality using the bundled Java 26 runtime.

**Acceptance evidence:** manifest allowlist tests, clean-room package inspection, all eight clean-install tests, WSL
installation check, plugin discovery and update tests, tag-to-commit verification, and Personal/Work negative
entitlement tests.

### F13 — Integrated frontend launch gate

1. Run the complete Maven quality gate against the current uncached tree and every non-Java external harness boundary
   suite.
2. Run the workflow-conformance, statistical, evaluator, cost, privacy, local-entitlement, installation, and packaged-
   artifact gates and record each as passed, failed, unrun, or explicitly excluded.
3. Exercise Personal and entitled Work clean installation, Codex and Claude terminal optimizer outcomes,
   related-file edits, additions and splits, rejected unrelated and out-of-bound writes, lease renewal and offline-grace
   behavior, feedback, Usage analytics validation, upgrade, and uninstall.
4. Hand the tested release coordinates and installation contract to the backend-owned website launch gate. Publish the
   first production release only when every required frontend gate has terminal evidence. A failed or unrun gate is
   not replaced by a successful build or adjacent test.

**Terminal outcome:** the Personal and Work local clients, optimizer, certification claim, entitlement boundary,
and distributions work together under the documented client contract and provide verified inputs to the website and
account launch gate.

## Known concerns

- GitHub access controls future repository access but cannot revoke a commercial copy already downloaded by a former
  subscriber. Commercial terms remain the enforcement boundary for continued use of that copy.
- Supporting every harness, operating-system, and architecture tuple can multiply release testing. CAT must publish
  only the eight first-release tuples covered by clean-install evidence; adding a tuple expands the release matrix and
  its required gate. WSL reuses a tested Linux tuple and requires a separate WSL installation check.
- A distribution repository may require root-level catalog metadata for a harness to discover target plugins. Such a
  file is allowed only when an end-user installation path consumes it and the release test proves that dependency.
- Client license and entitlement language can diverge from the backend-owned offer catalog. Release verification must
  compare every installed client value with that catalog and reject a contradiction.
