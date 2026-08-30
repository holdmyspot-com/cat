# Commit Git History One Topic at a Time

## Terms

- A **semantic unit** is one changed code, configuration, test, documentation, or other artifact fragment considered
  for its contribution to an observable behavior or bug fix.
- A **candidate topic** is a possible replacement commit that delivers one independently useful behavior or bug fix.
- A **fixed point** is reached only after a complete review finds no additional candidate topic.
- A **manifest** is the workflow-owned record that identifies the authorized Git range and retains the review evidence
  for its replacement commits.

## Design Goals

- Require an explicit, evidence-backed decision for every semantic unit before changing Git history: retain whether it
  becomes one commit, joins another, or is excluded.
- Ensure a commit-per-topic completion claim covers the exact user-authorized branch range, or an explicitly verified
  fixed prefix completed under the same governing rule, rather than an apparently tidy log or current worktree
  increment.
- Ensure a commit-per-topic invocation records the current workflow and launcher revision, the independent topic
  review based on original inputs, and the planner's challenges rather than relying on a cached prompt or a claim of
  reviewer independence.
- Reconstruct an authorized Git-history range into the smallest buildable feature or bug-fix commits that each make an
  observable user or developer change. Discover candidate topics from observable user and developer results and affected
  responsibilities across the entire range, rather than from source commits, paths, components, or product labels, so
  equivalent content reaches the same boundaries regardless of input commit partition.
- Make the rewritten history read as if a developer introduced one working feature or bug fix at a time. Every
  replacement commit must build, make its claimed user or developer-visible change, pass its focused acceptance check,
  and contain only the implementation, tests, documentation, configuration, and registration needed for that change.
- Prevent a command, API, installer, user interface, background job, rule, or other claimed capability from appearing as
  a feature before its complete supported activation path reaches its observable result.
- Establish candidate-topic boundaries by enumerating the complete universe of independently observable behaviors and
  bug fixes, then falsifying each candidate-topic combination with a removal or split counterfactual. Retain a
  combination only when an evidence-backed, specific dependency prevents its behaviors from being separately buildable
  and passing their focused acceptance checks.
- Derive every composite delivery's candidate universe from a behavior-and-acceptance matrix, not an umbrella product
  outcome. Enumerate each independently observable user or developer change—including a command, install or uninstall
  flow, event-hook rule injection, exposed agent family, resolvable skill or help command, reader-facing documentation
  or legal material, and a build or development-tool workflow—and retain each as a candidate unless a recorded pairwise
  blocker proves it cannot work in any split-enabling snapshot. Treat several members as one contract only when users or
  developers select them through one release surface for one stated purpose and one acceptance check proves the set is
  complete. Sharing a directory, package, or installer does not make a family.
- Require candidate discovery to reach a recorded fixed point: after every split, re-examine every resulting candidate
  for newly exposed behaviors or contracts and do not declare outputs until a complete pass finds no new candidate.
- Prevent a preliminary behavior inventory from being represented to a user as a final topic list. A topic list is final
  only after iterative decomposition has reached its recorded fixed point and the independent inventory is reconciled.
- Presume a candidate spanning more than one independently testable behavior or contract is composite. Retain it only
  after the inventory and adversarial review record a removal test and smallest split-enabling artifact set for every
  behavior, together with the exact missing final-state prerequisite that prevents each split; a shared build, plugin,
  distribution, installer, path set, or product label is never that prerequisite.
- Prevent source commits, proposed output names, source-review classifications, and broad component labels from priming
  candidate discovery. Freeze an independently derived behavior inventory before any reviewer sees a proposed topic plan
  or classifies a source as whole, split, or absorbed.
- Ensure one source-neutral topic reviewer discovers the candidate-topic universe from the immutable inventory and raw
  diffs before the planner assigns units, names outputs, or captures patches. The planner may challenge a missing or
  composite candidate topic, but may not replace the reviewed universe with a second independently worded inventory.
- Prevent any artifact category—especially prior planning, replay, test, generated-input, or workflow-evidence
  material—from being silently excluded from a whole-range behavior inventory; every immutable inventory item must
  receive a concrete semantic-unit decision or an evidence-backed explicit exclusion.
- Make every retained combination auditable at the behavior level: a source-wide conclusion is valid only after every
  independently observable outcome in that source has been enumerated, challenged as a possible split, and either
  retained separately or linked to a specific missing prerequisite.
- Treat mechanical validity, structural ledger coverage, source or adversarial review completion, and a preserved final
  tree as confirmation that the declared replay executed correctly, never as proof that candidate discovery is complete,
  semantic placement is correct, or topic decomposition has converged.
- Preserve a recoverable backup, the intended final tree, Git identity, per-commit build and behavioral checks, and
  final quality gates.
- When verification fails, revisit only the earliest placement decision that the failure proves incomplete; do not
  broaden a repair for replay convenience.
- Reject an incomplete, ambiguous, generic, or unauditable semantic plan before it can create replacement commits.
- Make candidate-topic boundary evidence monotonic: downstream challenge resolution, ledger, output planning, repair,
  and replay may split a reviewed candidate topic but may not collapse, omit, or reclassify a `separate` decision. A
  combination is valid only when both reviewer matrices explicitly record it as `combined` with its missing
  prerequisite.
- Make candidate discovery produce the smallest independently selectable and accepted behavior boundaries before
  reconciliation. Reconciliation compares and refines those boundaries; it never chooses a broader product, release,
  package, or source-shaped candidate in their place.
- Prevent a non-empty root commit from becoming a catch-all bootstrap topic. A root build setup is valid only when it
  establishes an otherwise empty reactor and normal build command; planning material, future-consumed helpers, shared
  assets, tests, rules, and implementation must be placed with their first working behavior unless a concrete,
  failure-capable split test proves an exact missing prerequisite.

## Prepare the replay

Use this skill only when the user explicitly requests history organized by topic, feature, vertical slice, origin, or
self-contained change—for example, “organize commits by topic,” “squash by topic,” “fold fixes into the feature,” or
“split this composite commit into features.” An ordinary request to “squash” belongs to the Git Squash skill; do not
infer topic organization.

Before invoking `git-topic-replay`, resolve its launcher instead of assuming it is on `PATH`. From a CAT source-checkout
root, build the distribution with `client/mvnw -f client/pom.xml -pl distribution -am package` and run
`client/common/distribution/target/jlink/<harness>/bin/git-topic-replay`. Otherwise, use the selected harness wrapper's
installed `git-topic-replay` launcher. Record the
workflow revision and launcher artifact used for the invocation. When the workflow source changed, rebuild the
distribution before review; do not combine a cached installed skill with a launcher built from newer source, or use an
older launcher to validate newer workflow requirements.

Before any rewrite, determine the user-authorized range, identity policy, and required quality gates. For a wholly new
review, run `git-topic-replay prepare REBASE_BASE TIP`. The launcher creates the invocation-local directory and returns
`replay_directory` and `manifest`; record the returned `manifest` value for later commands. Never create or select an
invocation directory in the prompt, or use `.cat`, the repository, or another durable workspace for replay artifacts.
Before assigning a source, path, or hunk to an output,
inventory the complete candidate universe: every independently observable behavior and bug fix discovered from the
range's affected responsibilities. Record each candidate's behavior-first discovery basis, then map its semantic units
back to all contributing sources. Do not derive this inventory from source commits, source reviews, paths, components,
or a proposed output count. Then complete the iterative decomposition and semantic-placement ledger described below.
Keep these decisions in separate checkpoints. First, discover only candidate topics with an externally reachable result
and their supported entry point, observable result, and split counterfactual; do not assign inventory units in that
pass. Reject a candidate topic that cannot name all four facts. Second, validate that the discovered candidate topics
are behavior-level candidates, then assign every immutable inventory unit to one accepted candidate topic; do not create
a new candidate topic merely to satisfy coverage. Third, record tier families and pair decisions from that accepted
universe. Generate bulk JSON, hashes, and pair rows mechanically only after the semantic boundary is fixed. This
prevents a reviewer from turning each changed file or unit into a topic while trying to satisfy inventory coverage.

Before independent discovery begins, publish one immutable entry-point vocabulary derived by the launcher: exact command
and subcommand arrays, installed-host selection names, hook names, lifecycle script paths, and reader-document paths.
Every reviewer must cite those canonical forms and state the concrete effect observed from that form. A logical alias
such as `skill:name`, `developer:build`, or an agent responsibility is evidence only when the same record also names its
canonical selector and explicitly states the alias relationship. Do not reconcile records by similar prose or matching
counts; reject them until their supported entry points and observable results can be compared directly. Only then write
the ordered topic outputs as JSON objects with `id` and commit `subject`, and run `git-topic-replay
declare-topic-outputs MANIFEST OUTPUTS_FILE`. The output list is the reviewer’s topic plan; it may have fewer, the same
number of, or more entries than the source commits. Only after declaration run `git-topic-replay
bootstrap-preserved-output-batches MANIFEST` once to capture immutable raw source patches and source reconstructions.
The command deliberately creates no output batch: a raw source patch proves reconstruction, but cannot decide a topic.
Write every output batch from the sealed behavior plan. For a safe fixed-prefix extension, run `git-topic-replay
prepare-fixed-prefix REBASE_BASE FIXED_TIP TIP`, then use its returned `manifest` value to bootstrap the manifest.
The command records the fixed source hashes; the semantic placement ledger and its mechanical source/origin
projections then cover only later sources.

If an earlier review artifact cannot be read by the current launcher, stop output planning. Preserve its behavioral
findings as evidence, then migrate each field with a documented mapping or re-author the affected review; never restart
from a source-shaped manifest and silently discard the earlier inventory. A successful final tree or a newly generated
manifest does not repair missing review evidence.

When the user states that an earlier organized-history run completed the same rule revision and asks to extend that
exact normalized history with later commits, treat the earlier commits as a fixed prefix. Verify that the named prefix
is an unchanged ancestor of the requested tip and that the new range begins immediately after it. Preserve each fixed
commit in place; do not re-review or move content out of it. Review only the new commits, then either retain each new
responsibility or fold its selected hunks into the fixed commit that it completes.

This shortcut is safe only when all of these conditions hold: the prior result is known complete, its placement rule has
not changed, its commit IDs still form the unchanged prefix, and the user has not expanded the request to include work
before that prefix. A fixed prefix is not proof for a different rule, altered history, an unknown prior result, or a
newly widened range; in those cases, prepare and review the entire authorized range.

### Scope and Completion Gate

A request to “commit and organize the branch” authorizes an organization review of the requested branch, not a direct
commit of only its current worktree changes. Before staging, rewriting history, or naming the branch organized, record
the branch, captured `REBASE_BASE`, `TIP`, resolved source range, governing rule revision, and one of these scope
decisions in `MANIFEST.scope-attestation.json`; preserve the user-authorization handoff in the invocation-local review
packet as well:

1. **Whole range** — the manifest covers every commit in the user-authorized branch range. For a full branch, use
   `--root` and the captured tip.
2. **Fixed prefix** — the user explicitly named the exact earlier normalized prefix and stated that it was completed
   under the same rule revision; record the committed artifact and verification that its unchanged commit IDs form the
   prefix. Review every later source.

Commit subjects, a tidy-looking log, an earlier agent statement, a successful build, or a coherent uncommitted change
are not fixed-prefix evidence. If the fixed-prefix record is absent or any condition cannot be verified, select the
whole range. Do not replace this decision with a narrower manifest for convenience.

Immediately before the final replacement commit or an “organized” completion claim, compare the recorded source range
with the sealed manifest, output witnesses, and required quality gates. Every source must be represented by a retained,
absorbed, or explicitly user-excluded semantic decision. Do not make a direct Git commit or report success while any
authorized source lacks that evidence; report the remaining scope instead. `declare-topic-outputs` and `preflight`
reject a missing, mismatched, or ambiguous scope attestation. The CLI can bind the recorded scope to immutable Git
facts, but cannot infer the user's authority; the review-packet handoff remains the external evidence for that decision.

Validate fixed commits mechanically from immutable Git objects: commit hashes, parent-to-tree transitions, patch
digests, tree hashes, and output witnesses when available. This validation proves that the preserved prefix did not
change; it does not reopen its completed placement decisions. Do not import or copy an old manifest's source, origin, or
path-transition reviews as evidence for the new commits.

The authorized range is a scope contract. When the user requests the full or entire branch, use `--root` and set `TIP`
to the captured branch tip, so the manifest includes the orphan root and every later commit. The root's additions are
eligible to be split across initial topic outputs, combined with later changes, or completed by backported corrections.
Do not use a commit subject, type, age, path set, or source grouping to assign a topic. Derive every topic assignment
from the affected file content and changes, their responsibility, prerequisites, and first meaningful consumer. When the
user names a range, use that exact range. Once preparation begins, the resulting manifest is the only authority for that
request: do not create or replay a narrower manifest because commit subjects, paths, or an initial review make part of
the range appear already normalized. Evidence may require expanding the range to include an earlier origin, but only
explicit user direction may narrow or replace it. If the user changes scope, stop the current plan and prepare one new
manifest for the replacement scope.

Record `subject branch`, `REBASE_BASE`, `TIP`, and the resolved commit range before creating a backup or invoking a
helper. For `--root`, verify that the selected tip is an orphan root or descends from the selected orphan root;
otherwise verify that the base is an ancestor of the tip. Do not substitute `main`, the current default branch, or a
workflow example's branch for a branch's root or user-specified base. Another branch is a base only when the user
explicitly asks to squash **against**, **onto**, or **into** it.

Finish every convention, workflow, and implementation change that must be included before capturing `TIP`. Freeze that
scope for the review: do not add ordinary commits while the manifest is under review. A changed tip requires a new
manifest and fresh evidence; do not silently extend, splice, or reuse an incomplete ledger. Record the frozen root, tip,
manifest format, and governing rule revision in the invocation-local review packet.

## Freeze and Reuse Evidence

Treat the captured base, tip, source snapshots, rule revision, semantic placement ledger, output plan, patch digests,
source reconstructions, output witnesses, and challenge ledger as one evidence set. Before reusing any part of it in the
same invocation, verify the captured base/tip and source trees still match, every patch digest and reconstruction still
verifies, and every reused output witness is sealed. Reuse only the verified prefix; a repair records the affected
output, invalidation reason, and replacement path, then rebuilds that suffix. Preserve unaffected sealed evidence.

The manifest workspace and review packet are invocation-local. They may coordinate a current retry, but are not a
later-run handoff. A later invocation starts from a clean checkout and fresh manifest unless a named, committed
repository artifact supplies the required rule revision, review evidence, and retrieval procedure; matching paths, cache
contents, or a remembered prior decision are not durable evidence.

The launcher interface is not the workflow boundary. Its single-message command supports one mechanical operation; this
skill selects the workflow needed for responsibility-first history. Follow this skill's prepared manifest, evidence, and
replay procedure even when a helper exposes only a single-commit operation. Do not infer that the helper's argument
shape limits the supported history shape.

Use one planned replay from the earliest affected target output. Do not squash everything into one commit and
reconstruct the desired history afterward. Preserve original authors unless the user-approved identity policy requires
normalization; configure that policy before the CLI creates any retained output.

Within one active invocation, reuse immutable Git facts for an unchanged prefix: captured source hashes, parent and
result trees, source inventory, raw source patches, exact-path predecessors, origin candidates, and path-transition
facts. These facts are a cache for navigation and verification, not placement evidence. When a repaired batch or new
source work affects an output, identify the earliest affected target output and run `git-topic-replay affected-suffix
MANIFEST OUTPUT`. Recompute and re-review the affected new work only; do not repeat Git reads or placement review for a
validated fixed prefix. A new hunk may still need a target-adapted patch because its fixed destination has an older
version of the file. Read that destination tree and adapt the new hunk to it; do not reconsider or relocate the
destination's existing content.


## Topic boundaries

Produce a readable history in which every commit after an intentional empty build bootstrap owns one smallest
dependency-complete, end-to-end feature addition or bug fix. A reader can build, install, and use that behavior without
a later commit supplying missing implementation context, tests, harness integration, plugin assets, distribution,
installer support, documentation, configuration, formatting, or corrections. Do not retain a broad component or
infrastructure label such as “shared runtime,” “packaging,” or “guidance” as a topic: it describes implementation scope,
not one observable feature or bug fix.

The only setup-only exception is the first commit, which may establish an otherwise empty reactor and its normal build
command. It must build successfully. Every later output must introduce one real behavior. Put a helper, abstraction, or
configuration change with its first end-to-end consumer; do not retain it as a standalone shared-infrastructure topic
merely because more than one later feature uses it.

For a full-range replay whose source begins at an orphan root, audit the root before naming any output. Separate the
empty-reactor build setup from each planning artifact, shared asset, helper, test, rule, and implementation unit. For
every non-setup root unit, name its first working consumer and trace that consumer's supported activation path and
focused acceptance check. A reviewer may retain root units together only after recording the pairwise removal result,
the smallest split-enabling artifact set, and the exact absent prerequisite. Labels such as “baseline,” “foundational
configuration,” or “bootstrap” do not supply that evidence. Test the rule with both a meaningful analogous root that
contains a build plus one complete feature and a dissimilar empty reactor: the former must keep the feature out of the
setup output, while the latter may remain one setup-only commit.

A supporting artifact is not a user- or developer-visible behavior merely because it exists, builds, or a unit test can
call it. A claimed capability begins only when a fresh user or developer can start at its supported entry point and
reach its stated observable result. Put that complete activation path in the same earliest output: the entry point,
registration or trigger, transport, implementation and assets, and a focused end-to-end acceptance check. A later commit
that first supplies any required activation link proves the earlier output incomplete; fold the earlier support forward
unless it has its own independently usable supported entry point and acceptance check.

Before naming an output, apply this rejection test to every candidate that is a type, helper, abstraction, or
configuration artifact: identify the supported external command or product entry point that invokes it, the usable
result that command or entry point produces, and a focused acceptance check that starts there. A developer command
counts only when it actually invokes the candidate and produces a usable project build or release artifact; compiling
the candidate, a unit test, a Maven phase that never reaches it, or a later command that merely could use it does not
count. If any fact is absent, reject the candidate as a standalone topic and place it with the earliest complete
consumer command. Include that command's integration, every required helper, and the end-to-end artifact check in the
same output. Record the consumer command and the failed standalone test in the candidate's split counterfactual. For
example, an artifact-layer value type and a plugin-artifact builder belong with the supported distribution command that
invokes them to produce an installable plugin or release artifact; a separately invokable documented release command
with its own usable output and acceptance check may be a topic. Re-run this test after every discovery refinement and
immediately before declaring outputs.

For every semantic candidate, record `acceptanceCommand` as the exact non-empty command array that a clean checkout of
that candidate's output can run to prove its claimed result. Also record the expected observable result in the
candidate's evidence. A prose instruction, a generic Maven test, a source-file assertion, or a command that does not
exercise the claimed entry point is not an acceptance command. Choose the command before declaring outputs. The CLI
rejects a semantic ledger or plan that omits it. Before sealing the output plan, run `git-topic-replay
validate-draft-declared-output-acceptance MANIFEST PLAN_FILE`; it materializes each proposed output tree and runs its
declared command there. After replay run `git-topic-replay run-declared-output-acceptance MANIFEST` so the CLI executes
each output's own declared command in its isolated output worktree. This contract is repository- and technology-neutral:
it may be a CLI invocation, HTTP request, consumer program, migration, build command, or another supported external
operation.

For an agent or skill candidate whose claimed result is that a user can select it, a host-native query that names the
agent or skill and reports it available from the installed plugin is a focused acceptance command. It must ask the host
about the assembled plugin; reading a source file, plugin manifest, or unpacked resource proves packaging only. Run a
model task only when the candidate claims the agent's or skill's generated result, not merely that the host can access
it. When the host has no named availability query, record that limitation and use its documented selection preflight; do
not invent a source-level substitute.

Apply this test to a proposed infrastructure candidate even when it contains a Maven module, module descriptor, shared
CLI code, API, test fixture, wrapper, or installer fragment. Its record must name the exact supported command, hook,
installer, or product action; the invocation edge that reaches the implementation; and the usable result at that
boundary. An exported package, a compiling module, a unit test, or a hypothetical later consumer is not an invocation
edge. If the record cannot name that path, the artifact is support for another candidate and must move with the earliest
supported command that actually calls it. Before patch capture, construct every proposed output snapshot in order and
run its stated build and focused acceptance check. A snapshot with an empty package, missing implementation, or a
later-required activation link invalidates the placement: reopen the candidate boundary and fold the support forward; do
not repair the snapshot by retaining an infrastructure topic.

Rule injection is one application of this test. A bundled rule or rule loader becomes user-visible only when a fresh
session started through a supported harness entry point receives the rule in its context. Its output therefore includes
the rule assets, loader, registration or transport, SessionStart hook, and session-level acceptance check. A later hook
that first invokes an earlier loader proves that the earlier output was incomplete.

When the same feature applies to more than one supported harness, one output implements, tests, packages, and installs
that feature for every supported harness together. A feature is not complete merely because a library-level test or one
harness can use it. Include each harness's real command, hook, or other user-facing entry point; its corresponding
tests; the plugin artifacts; distribution packaging; and installer or upgrade support needed for a fresh user to install
and use the behavior. Keep only a harness-specific follow-up when the behavior itself is genuinely unavailable or
meaningfully different for the other harness.

Organizing history by topic means responsibility-first placement: fold each hunk into the earliest commit for the
feature, bug fix, or other coherent theme it affects. Do not preserve a later rule-governance, convention, cleanup,
documentation, test, configuration, or refactor topic merely because it is useful by itself, broad in scope, or has a
coherent commit message. A standalone topic is valid only when a hunk introduces a genuinely new responsibility that
affects no earlier responsibility in the range.

### Construct commits as working development steps

Design the final history backward from each proposed commit's user or developer-visible change and its acceptance check.
A commit is valid only when a clean checkout of that commit builds and its new feature or bug fix works through a real
user-facing or developer-facing boundary. Include only the code, tests, documentation, configuration, registration, and
prerequisites that make that one change work; defer every independently working user or developer-visible behavior to
its own later commit.

Split within files and classes as readily as across files. If one class gains separate behaviors, place the fields,
methods, callers, tests, and documentation for each behavior in the commit that first makes that behavior work. A shared
helper belongs with its first working consumer. Do not preserve a whole class, file, or source commit merely because
splitting it requires constructing earlier versions of the class. A method, API, helper, refactor, or test has no topic
of its own unless it is needed to make that commit's user or developer-visible change work.

For every claimed capability, trace the complete activation path from a fresh supported entry point through its trigger,
registration or transport, implementation and required assets to its stated observable result. A developer capability
must trace from a supported command that invokes the implementation and leaves a usable build or release artifact; an
internal package phase, classpath call, compilation, or unit test is not that boundary. Treat every missing link as a
prerequisite of the same candidate. A build, unit test, or direct internal call is not the focused acceptance check
unless it is itself the supported entry point and produces the claimed usable result. For rule injection, start the
supported session and assert that the intended rule reaches its context.

When a proposed commit fails to build or its acceptance check fails, identify the exact missing prerequisite and add
only that prerequisite to the affected behavior's commit, or order the prerequisite behavior first. Re-run the build and
acceptance check. Do not merge the failed behavior with unrelated features, commands, harnesses, installers, or release
work merely to obtain a passing snapshot.

## Derive Topic Boundaries

Work backward from the desired history. For each candidate topic and its observable result:

1. Start with the smallest real behavior a fresh user can install and use, or one bug fix a user can observe.
2. Trace backward through implementation, callers, necessary helpers, tests, documentation, configuration, packaging,
   planning, performance work, style, refactoring, and corrections.
3. Include every hunk that corrects, explains, tests, configures, packages, formats, plans, or otherwise completes that
   responsibility. Keep a helper with its first meaningful in-commit consumer.
4. Create a standalone topic only when a hunk introduces one genuinely new end-to-end feature or bug fix with its own
   user-visible behavior, or one documented supported developer command that invokes it and produces a usable project
   build or release artifact. A user feature must have its real harness entry point, tests, plugin artifacts,
   distribution, and installer support. A developer feature must include its command integration and end-to-end artifact
   check. Removing either kind must leave no earlier feature incomplete. A new rule, convention, helper, or abstraction
   alone is not enough: fold it into the first feature or command it changes or enables. A capability is not present
   until a fresh user or developer can traverse its supported activation path to the claimed result. For a rule, that
   result is a fresh supported session receiving the rule in context.
5. Repeat until every hunk has an earliest dependency-complete destination.

Use iterative generalization: start with the smallest observable result, expand only while a prerequisite is required,
and stop at the most general boundary that still distinguishes the topic from independent work.

### Partition Composite Capabilities by Behavior and Acceptance

Before naming a candidate for a composite delivery, write a **behavior-and-acceptance matrix** in the CLI's
action-result-matrix artifact. A behavior is one user or developer-visible result, such as a command result, install or
uninstall flow, session rule injection, exposed agent family, resolvable skill or help command, reader-facing
documentation or legal material, or a build or development-tool workflow. List every behavior the final tree exposes,
its result, focused acceptance check, and smallest required artifact contract. Do not replace this matrix with a broad
outcome such as “install and use the product” or “provide plugin support.”

For every row, the required artifact contract must name the supported entry point, every activation link, and the
observed result. Its focused acceptance check must start at that entry point; a unit-level check is corroborating
evidence only. For a session rule-injection row, name the loader and selected rule assets and observe the rule in the
resulting context. Do not record support artifacts as a separate observable row unless users can reach them through
another supported entry point.

Treat several members as one behavior only when all three facts are recorded in the matrix: the one release surface
through which users select a member, the shared purpose stated for the set, and an acceptance check that proves a user
can select an appropriate member from the complete set. In that case, keep the member definitions, wrappers, and shared
selection metadata in one family candidate; removing an individual member tests whether the stated set is complete, not
whether a separate topic works. Do not infer a family from a shared directory, package, or installer. Give a member its
own candidate when users install it separately, invoke it through a separately stated entry point, or rely on a distinct
acceptance check. For example, custom agents or Git workflow skills form one family when users select from their stated
set through one release surface; a separately installable command does not.

Start with one candidate per matrix row. For every proposed combination, remove one behavior while retaining the other
behaviors' minimal contracts. Keep the combination only when the review records the exact final-state prerequisite that
makes the removed behavior's earlier contract impossible, the artifacts deliberately deferred, and the resulting failed
focused acceptance check. Shared packaging, a common installer, a common distribution, a source-commit boundary, or a
common product name does not prove that prerequisite. If every row remains buildable and passes its focused acceptance
check after another is removed, retain separate outputs even when they share a release artifact.

Treat a whole-source review as an exceptional conclusion: it is valid only after the matrix covers every terminal
behavior in the source and every pair has a recorded blocker. A raw source patch, an empty placement list, a source-wide
counterfactual, or a passing source-level build cannot establish that a source is whole. Write the planner's matrix to
`MANIFEST.planner-action-result-matrix.json` and the independent reviewer's matrix to
`MANIFEST.independent-action-result-matrix.json`; each has `rows` with `candidateId`, `observableBehavior`,
`observableResult`, `acceptanceCheck`, `requiredArtifacts`, and `deferredArtifacts`, plus `pairs` with both candidate
IDs, a `separate` or `combined` disposition, removal result, split-enabling artifacts, and a missing prerequisite only
for `combined`. The independent attestation must bind both its candidate-universe file and action-result matrix by
SHA-256. The CLI rejects missing or incomplete matrices and rejects a shared output unless both matrices explicitly mark
its behavior pair `combined`. Preserve the independent reviewer's artifacts unchanged, and require the adversarial
challenge ledger to challenge every retained combination before declaring outputs.

### Derive Split-Enabling Snapshots

Do not treat the current composite implementation, artifact layout, module membership, package builder, installer, or
source commit as proof that its user-visible capabilities must remain together. A capability can be independently usable
even when the current source first delivers it alongside other capabilities. For every candidate that a composite
boundary appears to prevent from building, installing, or running alone, derive the earliest **split-enabling
snapshot**: the smallest earlier version of the existing final-state artifacts in which that candidate has its own entry
point, required configuration, packaged resources, tests, and required user documentation.

Work backward from each candidate's focused acceptance check. Name the user or developer-visible behavior and result,
then identify only the artifact contract it needs: for example, the class methods, callers, and tests behind a command;
a minimal valid plugin that an installer can install and remove; session-start hooks, a rule loader, and rule assets
that inject rules; or a build configuration and command that a developer can run. For session rule injection, include
the supported session entry point and a fresh-session assertion that the rule reaches context; the loader and assets
alone are not a contract. Separate contracts remain separate candidates when removing one leaves the other candidates'
stated behaviors valid. A shared build, artifact, or installer component belongs with the first candidate that needs it,
unless it itself has a separately usable user- or developer-facing contract.

For each proposed split-enabling snapshot, record the candidate's focused acceptance check, its earliest required
artifacts, the artifacts deliberately deferred to later candidates, and why the earlier snapshot remains buildable and
usable without them. Adapt the selected existing hunks to that earlier tree with the topic-replay target-adapted patch
workflow; do not add temporary work that is absent from the captured final tree. A later output supplies only a new
candidate's contract or an artifact whose stated prerequisite is not present earlier. If no arrangement of existing
final-state artifacts can produce a valid earlier contract, record the exact missing final-state prerequisite as the
combination blocker.

Test this decision against a meaningful analogous composite, such as a release artifact containing separately
installable commands, and a dissimilar helper with no direct user action. Keep separately usable commands or plugin
capabilities split even when they share one distribution or installer. Keep a helper with its first consumer when it has
no independently usable contract. A successful build of the current composite is not evidence that no split-enabling
snapshot exists.

### Iteratively Decompose Candidate Topics

Before declaring outputs, treat every candidate topic as provisional. First freeze `MANIFEST.candidate-universe.json`,
written before creating output names, source-review classifications, or a semantic-placement ledger. For each discovered
behavior, record its observable user or reader outcome, affected responsibility, acceptance evidence, and the units that
may contribute to it. Discover these behaviors from the raw range and affected responsibilities; do not use a source
commit, its subject, a path grouping, a component label, or a proposed topic as a candidate or as its discovery basis. A
phrase such as “the source's contribution”, “native sessions”, or “rule changes” is not a behavior inventory entry.

Before writing either candidate universe, run `git-topic-replay exposure-inventory MANIFEST`. Map every returned
selectable entry point to exactly one candidate's `exposedEntryPoints` list in both reviewer universes. A candidate that
owns more than one returned entry point must name an `entryPointFamilies` record with its complete membership, selection
surface, shared purpose, and acceptance check. The command normalizes equivalent supported-harness agent declarations
into one entry point; a shared package, path, or installer never combines distinct entries. The CLI rejects an omitted,
duplicated, or unsupported entry-point mapping before topic outputs can be declared.

An entry-point family is valid only when every member is one selectable behavior with variants, rather than several
actions that happen to ship together. In the current exposure inventory, high, medium, and low tiers of the same agent
role may form one family; different agent roles, a command, each install or uninstall action, and each skill remain
separate candidates. A shared release, distribution, main program, or installer does not create a family. Before
freezing the universe, run the CLI's negative check with a complete-looking family record that combines two different
selectable actions; it must be rejected. For example, architecture and security agents cannot become one topic merely
because both use the plugin's agent picker. This check prevents a first commit from treating an installable release as
one feature when users or developers can independently invoke its command, installer flows, skills, or agent
responsibilities.

Start discovery with one candidate seed for every independently selectable boundary: a user can choose a harness,
installer, command, skill, agent family, or documented flow while another boundary is absent. A shared release,
distribution, package, product name, or umbrella installer is not one selection surface. Before an author records an
entry-point family, it must try the smallest snapshot containing each member's own entry point, required artifacts, and
focused acceptance check. Combine members only when no member can be selected and accepted without every other member,
and record the exact missing final-state prerequisite for each attempted split. In particular, separate harness-specific
installation or removal flows seed separate candidates unless that test proves an exact prerequisite. Do this before
either reviewer freezes its candidate universe or writes a reconciliation.

Give one source-neutral topic reviewer the complete immutable inventory and raw diffs, but not a proposed output plan,
source-review dispositions, planner topic names, or an expected number of candidate topics. Do not exclude an artifact
class from that handoff. The reviewer writes `MANIFEST.topic-candidate-universe.json`, names its reviewer, and records
each candidate topic's responsibility, acceptance evidence, discovery evidence, split counterfactual, and contributing
semantic units. Before accepting it, validate complete inventory-ID coverage and the launcher's exact schema.

The planner does not write a competing candidate universe. It writes a challenge ledger against the frozen reviewed
universe. Each challenge must prove either a missing candidate topic or a composite reviewed candidate topic with a
supported entry point, observable result, focused acceptance check, and split counterfactual. An accepted challenge adds
or splits candidate topics; it may never merge, rename, or delete a reviewed topic. The topic reviewer approves or
rejects each challenge with evidence. Only the reviewed universe plus approved monotonic changes advances to semantic
placement. `declare-topic-outputs` and `preflight` reject a missing reviewer attestation, unresolved challenge, or
planner attempt to alter a topic outside an approved challenge.

Treat reviewed candidate boundaries as monotonic evidence. A later reconciliation, semantic ledger, output plan, repair,
or replay may split a candidate, but it may not merge, omit, or reclassify a pair that either reviewer marked
`separate`. A many-to-one reconciliation or final semantic candidate is valid only when every merged pair is marked
`combined` in both action-result matrices with its exact missing prerequisite. Otherwise preserve separate outputs or
invalidate the affected evidence and repeat focused review; do not let a planner's broad candidate override an
independent split decision.

Then reconcile the candidate-universe inventory against the whole immutable artifact inventory: each changed unit maps
to one or more behavior candidates, and every behavior candidate names its contributing units. Enumerate each
candidate's independently observable behaviors, then try to retain each behavior as its own dependency-complete output.
For each proposed split, trace the required entry points, implementation, tests, plugin artifacts, distribution,
installer support, documentation, and governing corrections; ask whether removing that behavior leaves every other
behavior buildable and able to pass its focused acceptance check.

Retain the split when that removal test succeeds. When it cannot succeed, record the candidate's split counterfactual,
the other named behavior it remains with, and the exact prerequisite or shared contract that is absent from the earlier
output and prevents the behavior from being retained separately. One broad candidate cannot stand in for that pairwise
decision. After every retained split, start another complete discovery pass: re-examine every resulting candidate's
observable behaviors, behavior-and-acceptance rows, and pairwise removal tests, including behaviors made possible by the
newly isolated artifact contract. Record the pass, its inputs, and every newly found candidate. Do not declare outputs
until one complete pass finds no new candidate and every remaining combination has a specific, auditable blocker. A
passing build, applicable patch, successful dry run, structural ledger check, completed review, or preserved final tree
verifies an already chosen output; none proves that discovery is complete or ends this decomposition loop.

For every final semantic-placement-ledger candidate and semantic-plan candidate, cite the planner and independent
candidate-universe IDs that were reconciled into it. Cite every discovery record at least once, and cite planner and
independent records together only when their reconciliation compares that pair. This traceability permits a final
candidate to split or combine discovery candidates, but prevents the final plan from silently dropping an independently
identified behavior. `declare-topic-outputs` and `preflight` enforce these citations and reconciliation links.

Do not collapse a branch into one output merely because one commit is easier to build, install, replay, or verify. A
passing per-output build gate proves that an output is complete; it does not prove that it is the smallest topic. Before
declaring any output plan, inventory every independently observable user behavior and bug fix in the range. For every
candidate, record its prerequisites, the earliest output that can contain it, and why every earlier output cannot.
Retain a separate output for each behavior whose removal leaves the others usable and correctly documented. A plan may
combine candidates only when the ledger records the specific prerequisite that prevents a separate installable, usable,
and verifiable output. The review packet must repeat that justification for every combined candidate before
`declare-topic-outputs`; a broad product name, shared packaging, or a passing build is not justification.

### Guard Topic-List Communication

Do not give the user a final, planned, or intended commit-topic list until iterative decomposition has completed: both
candidate universes are reconciled, every retained split has been re-examined, and the terminal discovery pass records
no new candidate. Until then, describe any list only as a provisional inventory and state the unfinished decision that
can change it. This prevents an early inventory from becoming an implicit output plan before its split counterfactuals
are tested.

After the sealed plan passes `git-topic-replay preflight MANIFEST`, use `git-topic-replay report-topics MANIFEST` to
write the final topic list. Do not construct or present that list manually.

Apply the same gate when a user asks which topics will be generated as when the reviewer is about to declare outputs:
the answer is a final list only with the fixed-point evidence. In contrast, a request to report the existing commit log
does not require decomposition because it does not propose replacement topics.

## Build the Hunk Ledger

Before replay, inspect every commit in the range and record every semantic hunk. A hunk is one coherent behavior,
contract, test, documentation block, configuration unit, rule paragraph, generated input, or formatting change. For a
layout-only change, record the complete enclosing construct; a brace change includes its condition, braces, and body.

For each hunk, record it in one reviewer-authored **semantic placement ledger** at
`MANIFEST.semantic-placement-ledger.json`: source commit and immutable inventory reference; affected unit (type, method,
rule, configuration element, test, or documentation section); observable change and topic; semantic predecessors and
equivalent cross-path consumers; prerequisites and first meaningful consumer; selected earliest dependency-complete
topic output or independent-topic exception; rejected earlier candidates and their missing prerequisites; direct
evidence; batch identifier; and historical snapshot-verification result. The ledger covers additions as well as changed
existing paths. A source review, exact-path origin review, path-transition decision, output plan, and patch
reconstruction are derived evidence or mechanical checks; none may replace or contradict the ledger's placement
decision.

Source commits are immutable evidence and reconstruction boundaries, not topic-output boundaries. Do not declare,
bootstrap, seal, or record an output until the candidate inventory and semantic placement ledger are complete: every
candidate must have an evidence-backed earliest destination or a cited exception. Declare topic outputs before patch
bootstrap. A topic output may contain hunks from any number of sources, and a composite source may contribute hunks to
any number of topic outputs. Determine output count and order from dependency-complete themes, not from source count,
source order, path, or commit message. Reject a plan whose candidate, responsibility, prerequisite, or evidence is
generic, ambiguous, duplicated stock rationale, or otherwise not auditable by a reviewer who did not create it.

Before `declare-topic-outputs`, write `MANIFEST.semantic-placement-ledger.json` as a JSON array of behavior candidates.
Every candidate must provide a stable `id`, an `observableOutcome`, a behavior-first `candidateDiscovery` basis that
names the affected responsibility and observable evidence rather than its source grouping, a `splitCounterfactual`, its
earliest proposed `output`, every reviewer-named `units` entry (`inventoryId` and concrete `affectedUnit`) that
implements it, its `prerequisites`, `rejectedEarlierOutputs` with every earlier output and the specific missing
condition for each rejection, `combinedCandidateBlockers`, and behavior-specific `evidence`. Assign every declared
semantic unit exactly once, ensure every immutable inventory item has one or more declared units, and ensure every
declared output has at least one candidate. A path may supply units to separate candidates only when their distinct
`affectedUnit` values identify the separate semantic behaviors. For each pair of candidates assigned to one output,
record exactly one `combinedCandidateBlockers` entry naming the other candidate and the specific missing condition that
prevents the split. `declare-topic-outputs` rejects a missing, incomplete, duplicated, or unauditable ledger. These
structural checks ensure every declared candidate is reviewable; they cannot prove that the reviewer discovered every
behavior, so the frozen candidate-universe inventory and its counterfactuals remain mandatory review evidence.

Before sealing the output plan, copy that validated candidate inventory exactly into `MANIFEST.semantic-plan.json`.
`preflight` and `replay` reject a missing, incomplete, duplicated, source-review-incompatible, or ledger-divergent
semantic plan before replacement commits are created. This validates traceability and coverage; it does not replace the
reviewer’s responsibility to judge whether the stated outcome, blocker, and evidence are actually specific enough.

Do not write a candidate whose units are an entire source, every path in a component, or a generic “contribution” unless
the frozen candidate-universe reconciliation lists no second observable behavior for those units. A ledger that covers
every inventory ID but omits that behavior inventory is structurally complete and semantically insufficient. Do not
create source reviews, output subjects, batches, or a replay plan until this gate is satisfied.

For every deleted or replaced unit, also record whether it is a deletion or replacement, the responsibility that first
introduced the removed unit, and whether that responsibility's reconstructed snapshot still meets its governing
conventions after the planned placement.

## Apply the responsibility-first default

For every hunk in a later rule, convention, migration, cleanup, refactor, test, documentation, configuration, or
formatting source, first ask: **which earlier responsibility does this hunk change or complete?** Fold it into that
responsibility's earliest dependency-complete output. Do this separately for every hunk in a mixed source.

Do not use a generic source review, shared file path, source-commit message, broad scope, or replay convenience as
placement evidence. Identify the affected unit and its owning responsibility. If the hunk moves or removes an existing
unit, review both halves: the destination or replacement and the deletion. Place both with the responsibility whose
final history needs that move or removal, unless the deletion itself retires a distinct responsibility.

The only reason to retain a standalone output is that the ledger proves the hunk creates a new responsibility with no
earlier affected responsibility. Record the new responsibility, its first consumer or contract, and why every earlier
candidate is unrelated. Never retain a standalone “rule-governance” topic solely because the rule is independently
useful.

A later source that deletes or replaces a test from an earlier responsibility has a separate placement obligation.
Review the test's actual claim, exercised boundary, and governing testing convention. If the removal prevents the
introducing responsibility from retaining a misleading, unsupported, or noncompliant test, fold that removal into the
introducing responsibility's earliest dependency-complete output—even when the same source adds independent behavior.
Retain it later only when it retires a distinct tested contract; record that contract, its consumer, and why the earlier
responsibility remains complete and convention-compliant. A source that both adds a new responsibility and deletes or
replaces an earlier responsibility's test is presumptively **split**; a whole-source review must prove the test
retirement is independently owned.

## Remove net-zero work

Do not retain history for work that a later change completely undoes. Treat a possible revert as a net-effect question,
not as a commit-message convention: inspect the original and later semantic units, including changes whose subjects do
not say `revert`. For each proposed cancellation, prove all of the following:

1. The later units remove every observable effect of the earlier units in the reconstructed final tree.
2. No retained responsibility requires an earlier unit, an intermediate state, or a side effect of that work.
3. Removing both units leaves the affected destination snapshots dependency-complete and preserves the captured final
   tree.

Record the original and undoing units, their before-and-after snapshots, the retained responsibilities checked, and the
final-tree comparison in the hunk ledger. If the proof succeeds and each logical output contains only net-zero work,
record both sources as `absorbed` and discard both outputs in chronological order with `git-topic-replay
discard-output`. Do not create empty replacement commits. If either source also has surviving work, split it: omit only
the cancelling units and retain the independently required units in their earliest destinations. If the evidence does
not prove complete cancellation, treat the later change as an ordinary correction, retirement, or new responsibility; do
not discard an output merely because its commit subject says `revert`.

## Classify Cross-Cutting Refactors by Hunk

When a later refactor touches code from an earlier feature, classify each hunk separately before preserving the
refactor. Ask plainly: **if this hunk is removed, does the earlier feature become incomplete or violate the convention
that its own final snapshot must meet?** If yes, fold that hunk into the earlier feature, including its feature-specific
test or manifest correction. Do not leave it late merely because the same source also introduces shared infrastructure.

Keep a refactor hunk separate only when it creates independently useful shared infrastructure with a consumer beyond the
corrected feature. Record that consumer and show that the earlier feature remains complete without the shared hunk. For
a mixed refactor, split the source: fold feature repairs into their respective earliest feature commits and retain only
the shared boundary. Before replay, verify each destination snapshot: the earlier feature contains its repair, and the
retained refactor contains no hunk required solely to complete that feature.

Also build a correction-placement ledger for every later change that corrects previously introduced code, tests,
documentation, configuration, rules, generated inputs, formatting, contracts, names, readability, safety, or convention
compliance. Map each correction to its earliest dependency-complete destination, including moved, renamed, or equivalent
earlier content.

Before recording batches, run `git-topic-replay origin-review-scaffold MANIFEST`. The CLI supplies immutable inventory
items, paths, exact-path predecessors, and a predecessor output when a completed whole-source review identifies one
unambiguously. Do not copy, regenerate, or template those facts into the ledger. Review the scaffold in bounded semantic
batches, then record each fact as a candidate or corroborating evidence in the semantic placement ledger. The reviewer
decides equivalence, prerequisites, output, and whether a cross-path consumer or an added artifact is relevant; an
exact-path predecessor is a mandatory candidate, not proof of equivalence or the complete candidate set. Record
compatible CLI origin-review projections immediately when the command can represent the ledger decision. `preflight` is
a structural coverage gate, not evidence that a semantic placement was reviewed.

Do not generate review decisions from a source-to-output mapping, default output order, a template, or generic evidence.
Those are deterministic facts or semantic claims without review, not placement evidence. The CLI provides the former;
the reviewer must supply the latter.

For every ledger entry, name the affected responsibility, exact unit, concrete predecessor or cross-path consumer, and
missing prerequisite that proves its destination. Do not create, complete, or “repair” it with a loop, generated
mapping, repeated stock rationale, or a passing preflight result. A mechanically accepted projection is not evidence
that its placements were reviewed. If the evidence cannot explain why a unit belongs with this responsibility rather
than each earlier candidate, leave the entry open and investigate before replay.

Close one bounded review batch before beginning another. A progress update, a source-level classification, a generated
worklist, or an empty retry does not close a batch. If the next batch is too broad to explain every unit and candidate
plainly, split it by responsibility or affected unit before continuing. Do not make the workflow “safe” by requiring an
impractically large all-at-once review file: use the CLI's incremental recording and reserve the full ledger check for
preflight.

Also run `git-topic-replay path-transition-facts MANIFEST`. It reports raw additions, deletions, and Git-reported
renames without deciding whether they are related. For each deletion or reported rename, record either an independent
removal or a link to the related inventory item with `record-path-transition-decisions`. Do not infer a semantic rename
or replacement from Git's heuristic status. When independent packets produce disjoint decision files, the coordinator
may merge them with `record-path-transition-decision-batches` instead.

Treat a move, extraction, rename, or replacement as one two-sided obligation: the destination/replacement and the old
artifact's deletion must both be present in the responsibility's reconstructed snapshot. Before recording the output
that owns either side, inspect its before-and-after tree for both paths. Before replay, compare the final sealed output
tree with the captured tip tree. The comparison must be empty. If it reports an old path that remains or a new path that
is missing, reopen the responsible hunk placement; do not rely on a later preflight failure to discover the omission.

When a source changes one existing file for more than one destination, partition it by semantic hunk, not by source or
path. Choose every hunk's earliest dependency-complete destination independently. Fold each hunk whose prerequisites
already exist in an earlier output, and retain only a hunk whose identified prerequisite first exists in a later output.
One later prerequisite-bound hunk must not leave unrelated corrections in the same file or source late.

Distinguish a replay prerequisite from a final topic. A dependency, configuration entry, or module declaration that is
needed only to compile an immediately following helper or scope folds into that helper's first consumer in final
history. Keep it standalone only when it has independently useful behavior or contract, or an identified consumer
outside that topic; record that evidence. After finding an earliest dependency-complete destination, ask plainly: "Would
this prerequisite be useful and revertible by itself?" If not, place it with its first consumer instead of preserving a
setup-only commit.

For documentation, distinguish a refinement of an existing reader-facing outcome from documentation of a later outcome.
A hunk that makes an existing section more correct, complete, or usable belongs in the earliest output that introduced
that section's outcome. Retain a hunk later only when it explains, links to, or relies on a capability, artifact,
workflow, or document first introduced by that later output; record that exact unavailable prerequisite. Split a mixed
documentation source accordingly. For example, fold a rewritten README introduction into the commit that first
introduced the README's product documentation, but retain a link whose target documentation page is first created by the
later output. The presence of newly created documentation files in the same source is not evidence that every README
hunk belongs later.

Later edits to an existing documentation artifact normally fold into the artifact's original documentation topic. Keep
one with an implementation topic only when removing it leaves that implementation unusable or undocumented for its
required audience. Record the audience, the missing install, upgrade, or uninstall action, and the specific
implementation prerequisite as evidence. Before replay, ask: "Who is this for? Is this the one supported flow for that
audience? Can that audience find install, upgrade, and uninstall here or through clearly labeled links?" Move
local-build and developer alternatives to contributor documentation unless end users are meant to perform them.

Before classifying documentation hunks, inventory documentation artifacts across the entire repository. Include the root
`README.md` and `LICENSE.md`, every file under `docs/`, and every user-facing command, skill, help, installer, or
generated-help document; do not limit the audit to `docs/`. For each later hunk in an existing artifact, apply the
removal counterfactual and record its disposition. For example, a README install or uninstall instruction added with a
later bootstrap implementation normally folds into the README's original product-documentation topic. Keep it with the
bootstrap only when removing it leaves the bootstrap unusable or undocumented for its required audience. Completion
evidence must list every reviewed documentation artifact and its disposition: folded destination, retained later topic
with the required-audience evidence, or no later hunk.

Generalize this inventory to every pre-existing artifact modified by later commits: source, tests, documentation,
configuration, build files, manifests, scripts, installers, and generated user-facing or help artifacts. For every
semantic hunk, identify the artifact's owning responsibility, apply the removal counterfactual, and place it in that
responsibility's earliest dependency-complete topic unless a named shared boundary or prerequisite proves it belongs
later. Documentation audience and lifecycle checks are category-specific examples, not a limit on the inventory. A mixed
commit requires explicit disposition evidence for every artifact and hunk: folded destination, retained topic with its
consumer or missing prerequisite, or independent project-wide convention.

Choose a correction's destination from the affected unit's history, not from later commits that happen to touch the same
file. Starting with the commit that introduced the unit or its equivalent predecessor, examine candidate snapshots in
chronological order. Select the first snapshot in which the correction, its required prerequisites, and its first
meaningful consumer form a dependency-complete vertical slice.

A later candidate is not justified merely because it modifies the same file, makes the patch apply more cleanly, or
reduces replay conflicts. Reject an earlier candidate only for a specific missing prerequisite, and record that
prerequisite and the commit that introduces it in the ledger. Apply this test independently to every hunk in a mixed
commit. During verification, confirm both that the selected destination contains the correction and that each rejected
earlier candidate lacks its recorded prerequisite.

A corpus-wide migration, convention application, formatter pass, or cleanup is a source grouping, not a standalone
topic. Split it by affected unit and path. For every changed existing unit, map the correction to the commit that first
introduced it or to the earliest later commit whose corrected form is dependency-complete. It may remain separate only
when the ledger proves it introduces a genuinely new responsibility with no earlier affected responsibility.

Treat later `bugfix`, `style`, `test`, `refactor`, `docs`, `config`, planning, and performance work as presumptively
related to an earlier in-range feature when it modifies, explains, tests, configures, styles, plans, supports, or
corrects that feature. Commit type, source-commit scope, message, broad path set, or the fact that one change applies a
new convention do not prove independence.

Do not classify a later rename, split, merge, relocation, or other reorganization of existing feature-owned artifacts as
an independent topic merely because its resulting layout is clearer. Trace each reorganized unit to the feature it first
supports and fold that unit, including the structural change needed to place it, into that feature's earliest
dependency-complete output. Split a mixed reorganization by unit: retain only artifacts first introduced for a later
feature with that later feature. A reorganization may remain independent only when it introduces an independently usable
mechanism whose value does not depend on the artifacts it reorganizes; record that mechanism and its first consumer as
the exception evidence.

A hunk may remain separate only when the ledger proves it introduces a genuinely new responsibility with no earlier
affected responsibility. The evidence must name its new user, consumer, or contract and show why each earlier candidate
is unrelated. A later prerequisite may determine which output receives a hunk, but does not create a standalone
governance topic; fold the hunk into the first responsibility for which that prerequisite yields a dependency-complete
slice.

"Independent topic," "coherent by itself," "independently useful rule," "different commit type," "corpus-wide change,"
"non-adjacent," and "inconvenient to replay" are not evidence. Split every mixed-origin or mixed-disposition commit. Do
not begin replay while any hunk or correction has an unknown affected responsibility, destination, prerequisite, or
unsupported standalone claim.

## Normalize repeated replays deterministically

Treat commit-per-topic as a normalizing transformation, not as a preference for the current commit partition. With the
same authorized content, semantic change order, and placement criteria, two inputs that split or combine that content
differently must produce the same responsibility boundaries and final tree. Git object IDs, authorship metadata, and
commit messages may differ after reconstruction; source grouping, current topic labels, and the number of input commits
are never placement evidence.

For every hunk, derive a placement normal form from its affected responsibility, observable outcome, prerequisites, and
first meaningful consumer. Use the earliest dependency-complete destination in that form. Record why every earlier
candidate lacks a prerequisite and why every retained standalone responsibility has its own consumer or contract. Do not
preserve or expand a topic because an earlier replay happened to retain it, and do not merge topics merely because a
broader label, shared convention, common file, or later interpretation can describe both.

Give every ledger unit and topic output a stable identifier. Order topic outputs by recorded dependency edges; when
independent outputs need an order, record one deterministic tie-break in the frozen ledger and reuse it unchanged. Do
not derive output order from a source commit's path, message, or default output slot. The current CLI can execute that
order only when its source-indexed manifest has enough logical outputs; otherwise stop before replay and report the
required CLI extension.

On a repeated invocation with no new commits and unchanged placement criteria, rebuild the same normal form and require
the same hunk-to-responsibility assignments. A differing assignment is a review or implementation error to investigate,
not permission to broaden a topic incrementally. Do not use a fold from an earlier iteration as evidence for another
fold; each placement must be proved directly from the hunk's own responsibility, prerequisite, and consumer.

When new commits extend the authorized range, classify their hunks against every earlier dependency-complete
responsibility. Fold a new correction, test, documentation, configuration, rule, refactor, or cleanup hunk into that
earlier responsibility when its prerequisites exist there; retain a new output only when it introduces a distinct
responsibility with its own first consumer or contract. Recheck an older placement only when the new hunk proves that
the older output itself was not dependency-complete, and record that direct contradiction.

Reclassify existing content under new placement criteria only when the commit-per-topic rule has a committed normative
change that affects placement, scope, or required evidence. Record the exact rule change, every reconsidered hunk, its
old and new normal-form destinations, and the new direct evidence for the changed destination. A wording edit, example,
formatting change, broader label, or desire for fewer commits is not a criteria change. The criteria change authorizes a
full comparison against the normal form; it does not authorize a blanket merge of existing topics.

## Prove Whole-Source Preservation

Treat preserving an entire source commit as a claim that every semantic unit in it has the same earliest
dependency-complete destination. A commit message, type, path scope, apparent coherence, or replay convenience does not
prove that claim. For each whole source, enumerate its behavior, contracts, helpers, tests, documentation,
configuration, generated inputs, refactors, corrections, and migrations. Trace each unit's predecessor, successor,
caller, consumer, and earlier feature where applicable. Apply the removal counterfactual: if the unit were absent from
the preserved output, would an earlier retained feature lack part of its intended final implementation, explanation,
verification, configuration, or another prerequisite? Split the source whenever the answer is yes for any unit.

A whole-source or whole-file review is invalid when its hunks have different earliest dependency-complete outputs. Mark
it **split**, even if all hunks modify one path. Shared source or path ownership is not evidence of shared placement.

Do not record `whole`, `split`, or `absorbed` until the two candidate-universe inventories have been reconciled and
every outcome that source can affect has a pairwise split decision. A reviewer may not infer `whole` from the absence of
a proposed split or from a source-level build passing. If one source implements multiple outcomes, split it even when
all output commits happen to use the same broad component or package.

Record source-level `whole`, `split`, or `absorbed` status only as a projection of the semantic placement ledger: it may
summarize how a source's units were placed but must not select their outputs. Use `absorbed` only when every unit has a
ledger entry placing it in an earlier output and the historical source output is absent from the reconstructed history.
The counterfactual and evidence must cite the reviewed predecessor, consumer, and destination snapshot; do not
substitute path similarity or a generic statement that the source is independent.

For every `split` source, copy its complete manifest inventory into the source review's `placements` array before
running `record-source-reviews`. Each placement names one immutable ID for a changed path, the changed unit, the
responsibility that needs it, its selected output, and concrete evidence. The placement outputs must cover every output
declared by the split review. The CLI rejects a split review that omits a changed path or a declared output. This is a
coverage gate, not semantic proof: when one artifact contains several independently placeable hunks, record each in the
semantic placement ledger and create separate batches as needed. Never define a topic as “the remaining artifacts” or as
a complement of another batch; name the responsibility that unifies every placement in it.

## Challenge the Placement Ledger

Before comparing conclusions with the placement ledger, assign an independent adversarial reviewer the Git-generated
inventory and raw source diffs. Give it the goal, acceptance evidence, failure history, governing artifacts, and range,
but not the placement ledger or the proposing agent's private reasoning. It must build a finite challenge ledger that
tries to falsify every candidate placement, especially delete/add replacements, moved successors, extracted helpers,
mixed-file changes, tests, documentation, configuration, and changes outside the initial range.

Each challenge item records the acceptance condition, source inventory item, evidence, and status: **open**,
**resolved**, or **out of scope**. Compare the two ledgers only after both are frozen. Resolve every disagreement with
specific evidence; a reviewer finding may add or reopen work only for an unguarded acceptance condition or relevant
counterexample. Preference, a speculative alternative, file overlap, patch convenience, and rename detection are not
evidence.

Generate immutable facts once per manifest, then organize the frozen artifact inventory, exact source/path origins, and
path-transition facts into responsibility packets in `MANIFEST.review-packet.md`. A packet identifies candidate units,
relevant snapshots, path transitions, and its proposed responsibility only as reviewer navigation; it is not a placement
decision. Create the challenge ledger as `MANIFEST.adversarial-challenges.json`; every item must be `resolved` or `out
of scope`, with cited evidence. These are invocation-local review artifacts: the planner must read them immediately
before `preflight`. `preflight` validates CLI-managed replay evidence; it does not attest to the semantic challenge
ledger. A later invocation must regenerate these artifacts from a fresh manifest.

Independent responsibility packets may be reviewed in parallel only after assigning disjoint ledger units and their
immutable inventory facts. Each reviewer writes one distinct manifest-local evidence file and never changes the
manifest, output batches, or another reviewer's file. A coordinator merges only non-overlapping ledger decisions;
existing `record-origin-review-batches`, `record-source-review-batches`, and path-transition commands may record
compatible mechanical projections, but are not placement authorities. Resolve an overlapping unit or transition before
recording either result. Do not parallelize a packet whose placement evidence depends on another packet's unresolved
decision. Output batches, suffix invalidation, replay, and finalization remain sequential because each depends on the
preceding tree; do not seal or replay an output until all ledger decisions that can affect it are complete. `preflight`
must reject missing ledger coverage and projection disagreement.

For each bounded review packet, wait up to 60 seconds for completion. If no completion notice arrives, inspect the
expected manifest-local artifact before treating the review as incomplete: a delayed notice alone is not failure. If the
artifact is present and JSON-valid, validate and merge it; if it is missing, assign the defined fallback reviewer or
report the unavailable review as the blocker. Do not wait indefinitely or silently discard completed evidence.

The adversarial reviewer must challenge every whole-source review for units that complete, move, replace, explain, test,
configure, correct, or refactor an earlier feature. It must also challenge every CLI-reported origin candidate,
delete/add pair, and raw deletion with this counterfactual: if the later source were absent, would an earlier output
retain an artifact that final history removes or corrects? Challenge every `different-unit` classification and every
unavailable-prerequisite claim. Resolve every such challenge with cited evidence before freezing the ledger. For each
mixed source that changes an existing file, it must also test the partial-backport counterfactual: which hunks can move
to each earlier candidate while another hunk remains later? Require cited missing prerequisites for every hunk retained
later; shared source or path is not evidence that their destinations are the same.

For every deletion or replacement of a pre-existing test, reconstruct the introducing responsibility with and without
the planned removal. Ask: “Would the earlier responsibility otherwise retain a test whose claim, exercised boundary, or
fixture contradicts the governing testing convention?” If yes, resolve the challenge by folding the removal into that
responsibility, or record the distinct retired contract, its consumer, and proof that the earlier responsibility remains
complete.

## Replay

### Seal the Complete Output Plan

Before recording or discarding any output, freeze the semantic placement ledger and compile one immutable output plan.
The plan must name every ledger-selected logical output in final-history order as either retained or discarded. A
retained output declares its ordered reviewer-authored batches; a discarded historical output has proof that no ledger
unit selects it. Validate the complete plan before it writes any output witness: every semantic unit has exactly one
placement or a cited cancellation; every selected unit maps to one batch and output; every planned patch exists, has a
recorded digest, and belongs to its declared source; every retained output has one or more batches; no discarded output
is selected by a ledger unit; and no batch is duplicated or omitted.

Declare every ledger-selected topic output before bootstrap with `git-topic-replay declare-topic-outputs MANIFEST
OUTPUTS_FILE`. The reviewer-authored output list is independent of source count: it may combine sources, split a
composite source across outputs, or contain more outputs than sources. Do not force the ledger back into source
boundaries or pretend that a composite source is one topic.

When the output list has one retained entry, stop before bootstrap and recheck the single-output justification against
the completed candidate-behavior inventory. If any candidate has its own usable boundary after its stated prerequisites,
add that output and assign its supporting hunks. Do not let a per-output build result, a successful dry run, or
final-tree preservation override this boundary review.

Write the plan as one JSON array. Each entry contains `outputId`, `disposition` (`retained` or `discarded`), and
`batches`; retained entries have ordered `{id, source, patch}` batches and discarded entries have an empty batch list.
Before declaring an output retained, compare its proposed tree with its parent. If the trees are identical, discard that
output: any source whose units are all already placed in earlier outputs is `absorbed`. Do not preserve an empty commit
to retain a source commit's subject or position. A nonempty batch list is not proof that the output changes its parent.
After replay, compare every retained commit with its parent and fail the replay if any pair has identical trees.
Run `git-topic-replay validate-draft-declared-output-acceptance MANIFEST PLAN_FILE` before sealing. It runs each
candidate's recorded command in the exact tree that the draft plan would produce, so a command that needs a later
artifact fails before the plan becomes immutable. Keep its gate logs with the plan evidence. Then Run `git-topic-replay
seal-output-plan MANIFEST PLAN_FILE` only when the current manifest can represent every frozen ledger output. It
captures every patch digest and dry-runs the complete plan in chronological order through an isolated Git index. Before
it writes the plan, it checks the recorded mechanical origin-review projections against that simulated output state and
reports conflicts together. A conflict means the selected output neither contains a batch from the reviewed source that
changes the exact path nor reaches that source's resulting path state. Correct the ledger and plan when the semantic
decision no longer applies. The dry run validates patch applicability, output-tree transitions, mechanical projections,
and final tip-tree identity without changing the manifest's witnesses or Git history. After it succeeds, `record-output`
and `discard-output` may execute only the next decision already declared by the sealed plan. A sealed plan is immutable:
if placement changes, prepare a replacement manifest, import only still-valid review evidence, and seal a newly dry-run
plan before recording a replacement witness.

1. Expand the range when a ledger origin lies earlier than the initially selected range. Group all hunks by recorded
   destination, not source commit, path, or commit type.
2. Before creating any output or replaying, compare the prepared manifest's source inventory with the semantic placement
   ledger. Every source transition, including additions, deletions, and cross-path equivalents, must be covered by
   ledger units; do not infer a placement from a message, type, age, path set, or previous rewrite. Resolve every source
   through the same authorized manifest. Run `origin-review-scaffold` and path-transition facts as mechanical candidate
   discovery after a ledger or manifest change, and do not replay until the ledger is complete, compatible projections
   are recorded, and preflight proves their structural coverage.
3. Verify each destination batch is a dependency-complete vertical slice: helpers have meaningful in-commit use, tests
   do not precede what they verify, and public boundaries have understandable contracts. For every claimed capability,
   start at its supported entry point from that destination snapshot and verify its stated result; a build or internal
   unit test cannot replace this check. For a rule-injection batch, verify the intended rule reaches session context.
   For every earlier responsibility changed by a later test deletion or replacement, inspect its reconstructed snapshot:
   it must not retain a test removed solely because it violates the final testing convention. Every such removal must
   either be folded into the responsibility that introduced the test or have a recorded distinct-contract exception.
4. For every source, declare ordered reviewer-authored **hunk batches**. A batch is one opaque patch artifact assigned
   to one output. It declares its source, destination, proof, immutable patch path and SHA-256, and source
   reconstruction. A mixed source has one batch per destination. The same path may occur in batches for several outputs;
   do not coalesce them because they share a source or file. Each batch retained later must cite the earliest candidate
   that lacks its prerequisite. The CLI must not identify, classify, rename-detect, fuzzy-match, or regenerate hunks.
5. A source reconstruction proves each source's original parent-to-source transition. When destination batches apply
   unchanged to the source parent, list those batches in authored order. When an earlier destination needs a patch for
   an earlier version of a file, first preserve the source's original complete patch with
   `record-source-patch-reconstruction`; do not use the earlier-file patch to claim original-source reconstruction. Then
   prepare a manifest-local candidate file containing the intended version of the reviewed path and run
   `git-topic-replay capture-target-adapted-path-patch MANIFEST SOURCE PATCH TARGET_TREE PATH CANDIDATE_FILE`.
   `TARGET_TREE` is the earlier commit or tree the patch must update. The command creates the patch relative to that
   tree and proves it applies there. The reviewer remains responsible for selecting the change and intended path
   contents; do not write a patch directly or manually construct a batch. Path inventory is an immutable cross-check,
   not the placement authority.
6. Derive `whole` only when one source's ledger units use one destination batch and `split` when they use two or more
   destination batches. Derive `absorbed` when every unit belongs in earlier outputs and the source's historical output
   is discarded. An absorbed source may have no batch when an earlier-file batch already contains its state; retain its
   original source patch reconstruction as deterministic proof. Do not use path-level placement, correction, `outputs`,
   or `absorbedOutputs` fields as placement authorities.
7. Assemble retained outputs in final-history order. For each output, write one reviewer-authored JSON batch file whose
   entries contain only the reviewed batch identifier, source hash, and manifest-relative patch path. Run
   `git-topic-replay record-output MANIFEST OUTPUT BATCH_FILE`. If a placement changes an earlier output, replace its
   reviewed batch file and rebuild every later output witness from that point.

   After `seal-output-plan` succeeds, prefer `git-topic-replay record-planned-output MANIFEST OUTPUT` for a retained
   output. It records exactly the next sealed batch declarations without creating a reviewer-maintained duplicate batch
   file. Use `discard-output` for the next sealed discarded output.
8. Run `git-topic-replay replay MANIFEST` and use its returned values and failure diagnostic as authoritative. For a
   non-root replay, require `git merge-base --is-ancestor REBASE_BASE HEAD` to succeed and require the first replacement
   output's direct parent to equal `REBASE_BASE`. For a `--root` replay, require the first replacement output to have no
   parent. These checks prevent a correct final tree from concealing disconnected replacement history. The CLI creates
   all replacement commits from an isolated index and advances the branch only after every output witness matches, so an
   interrupted invocation leaves the checked-out source branch unchanged. Rerun the same command only after its
   diagnostic confirms that the manifest and recorded backup remain valid. Do not set `GIT_SEQUENCE_EDITOR`, transform a
   todo with shell, AWK, sed, or an editor, pick a source commit, or manually recreate a batch.
9. Run `git-topic-replay origin-review-audit MANIFEST` after replay. Review every reported later exact-path change
   against its recorded same-unit or different-unit decision before accepting the rewritten history.
10. On failure, stop and follow the CLI diagnostic before correcting the reviewed artifact. Run focused checks for the
    repaired output and downstream outputs whose trees, prerequisites, tests, configuration, or documentation can
    change. After the final successful replay and `origin-review-audit`, run each required full quality gate once as
    `git-topic-replay run-gate MANIFEST GATE_NAME COMMAND...`. Then run `git-topic-replay verify-identity REBASE_BASE
    HEAD NAME EMAIL` using the user-approved policy, followed by `git-topic-replay finalize MANIFEST` and
    `git-topic-replay verify-finalization MANIFEST REBASE_BASE HEAD`. `HEAD` is resolved to its commit ID by the
    launcher, so the documented symbolic-ref form is valid. Finalization writes an atomic receipt before it removes the
    recorded backup; verification must bind the current `HEAD`, ordered replacement chain, manifest, and retained review
    and gate artifacts to that receipt. A reword-only rebase, a copied history, a matching final tree, or an absent
    receipt means the history is unreviewed: prepare a fresh whole-range replay rather than claiming it is organized by
    topic. Retain the named manifest directory and its receipt with the review packet; a receipt is not cross-invocation
    evidence if those artifacts are discarded. If cleanup fails after receipt publication, restore the recorded backup
    and rerun `finalize`; it revalidates the existing receipt before retrying only backup cleanup. Consume each CLI
    result or diagnostic; do not duplicate its verification, recovery, or cleanup procedure in this skill.

Within the same active invocation, a failed replay may reuse its sealed prefix only after `git-topic-replay
validate-sealed-prefix MANIFEST` succeeds. A sealed prefix is the contiguous sequence of earlier outputs whose batches
and witnesses were recorded before the first unresolved or failed output. If a repair changes an earlier new-work
output, identify it with `git-topic-replay affected-suffix MANIFEST OUTPUT`, invalidate and rebuild that suffix, record
the reason and its replacement plan in the ledger, and re-review only the affected new placements. A validated fixed
prefix remains fixed: adapt the repaired new hunk to its target tree rather than moving the prefix's content. If the
fixed-prefix safety conditions no longer hold, the tip or rule revision changed, or validation fails, prepare and review
the full authorized range. Do not import semantic reviews, classifications, challenge decisions, or output-placement
authority from a prior invocation merely because its Git facts match.

## Make Progress Monotonic

Before replay, finish and freeze both ledgers: no hunk or challenge item may lack a classification, destination, or
cited exception. Derive a finite replay worklist from them: unresolved placement batches, ledger disagreements,
mixed-source reconstructions, pending output creation, and failed verification gates. Each iteration must close one or
more existing worklist items or replace one with a strictly narrower evidence-backed item, and strictly reduce
unresolved work; a failed gate remains open until it is resolved or the replay restarts from its backup. Record a
transition only after its evidence is verified; never mark planning, a dry run, or an output witness as final history.

Do not add a subjective historical refinement after the ledger is frozen. Reopen a resolved entry only when concrete
evidence proves that its recorded placement, prerequisite, or verification is wrong; invalidate only the affected
entries and record the evidence. Treat every other new improvement idea as a new commit outside the rewrite range.

## Recover a Failed Topic Replay

After a planning or replay command fails, stop immediately. Do not continue a loop, run a later manifest command, or
edit Git's todo file. Within the same invocation, resume only from a sealed checkpoint that the CLI revalidates without
changing Git: the immutable base, tip, and ordered sources match; every recorded patch exists and matches its digest;
every source reconstruction and sealed output witness recreates its recorded tree; and there is at most one verified
open output. Use a fail-fast driver that exits on the first nonzero command and records each successful checkpoint.

Restart from a new manifest when validation fails, the failure point is unknown, Git's branch, index, or worktree state
differs from the saved pre-replay state, or the invocation has ended. Preserve a failed run for diagnosis, but do not
use its uncommitted manifest, ledgers, or checkpoints as input to a later invocation and do not repair it into apparent
validity. The CLI-owned replay does not leave an interactive rebase to resume: inspect its diagnostic, then either rerun
its unchanged manifest within the current invocation or restart from newly verified immutable source evidence. Do not
manually delete or restore a backup branch; the manifest-local CLI inventory is the authority for recovery and
successful cleanup.

## Verification

1. Reinspect every placement and challenge item. Verify the destination is the first chronological candidate with all
   recorded prerequisites, each rejected earlier candidate lacks its specifically recorded prerequisite, and every
   adversarial disagreement has evidence-backed resolution.
2. For every moved, renamed, delete/add-replaced, or extracted unit, inspect the destination historical snapshot. Verify
   it contains the unit and its first meaningful consumer, and that no later commit exists solely to supply its helper,
   correction, test, documentation, configuration, or other implementation context. For a claimed capability, the first
   meaningful consumer is its supported activation path; verify that a fresh user or developer can reach the stated
   result from the destination snapshot and that no later commit first supplies that path. For rule assets or a loader,
   verify that a fresh session receives the rule in context.
3. Reinspect every remaining commit hunk by hunk. Verify it is an independent coherent topic or has an explicit
   placement-proof exception; do not rely on commit type, source scope, file overlap, rename detection, or final-tree
   equivalence.
4. Re-run `origin-candidates` and verify that every reported candidate has one recorded review, selected output, and
   evidence for every rejected earlier output. A source-level atomicity review cannot provide this evidence.
5. For every mixed source that changes an existing file, verify that each earlier output contains every batch whose
   prerequisites it has, and each later output contains only batches with a cited unavailable earlier prerequisite.
   Verify the batches reconstruct the source transition exactly once without relying on shared-path placement.
6. Before finalizing, repeat the post-replay ancestry check: a non-root replay must descend from `REBASE_BASE` and have
   it as the first replacement output's direct parent; a `--root` replay's first replacement output must have no parent.
   Invoke `git-topic-replay finalize MANIFEST` only after the required quality gates pass, then require
   `git-topic-replay verify-finalization MANIFEST REBASE_BASE HEAD` to succeed. Treat its result or diagnostic as
   authoritative; correct a failed reviewed artifact with evidence and restart. Do not accept changed commit messages as
   evidence of semantic decomposition.
7. Verify every destination is understandable, independently buildable, and able to demonstrate its claimed user or
   developer-visible behavior without a later corrective commit. After replay, run the normal build through
   `git-topic-replay run-output-gate MANIFEST per-output-build COMMAND...` and its focused acceptance check through
   `git-topic-replay run-declared-output-acceptance MANIFEST` for every retained output. The declared commands must
   exercise the named build or focused behavior; a no-op or mere log-producing command is not gate evidence. Stop at the
   first failure, preserve its diagnostic worktree, and reopen the earliest incomplete feature. A failure proves only
   the missing condition named by its diagnostic; it does not justify combining unrelated candidates. Recheck the
   candidate inventory and move only the hunk whose recorded earliest destination requires that condition, then rebuild
   and re-review the affected suffix.
8. Verify the rewritten tip preserves the recorded tree exactly unless an explicit, approved difference is recorded.
9. Run `git diff --check`, the full build, and any additional required final quality gates through `git-topic-replay
   run-gate MANIFEST GATE_NAME COMMAND...`, so the CLI preserves gate output and diagnoses failures for either a human
   or agent caller. Verify the approved identity through `git-topic-replay verify-identity REBASE_BASE HEAD NAME EMAIL`.

## Per-Commit Build Gate

Run this gate for every retained output. Use `git-topic-replay run-output-gate MANIFEST per-output-build COMMAND...`
after replay and before finalization. The CLI creates an isolated detached worktree for each output commit, runs the
normal command there, removes successful worktrees, and preserves the first failing worktree and its output log. Do not
finalize, or describe the history as organized, until every output passes.

## Fixed Point

Stop rewriting when the completed ledger proves every hunk is at its earliest dependency-complete destination or has its
cited exception, every remaining commit is an independent coherent topic, and all verification passes. Under unchanged
content and criteria, a second normalization must produce the same hunk-to-responsibility assignments and final tree.
New commits may extend this result through their own reviewed hunks; a committed criteria change may reconsider existing
assignments only through the recorded normal-form comparison. Record all other new ideas as new commits.
