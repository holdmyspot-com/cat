# Prompt Validation

## Design Goals

- Prove every prompt obligation with task-realistic tests that can expose both its required behavior and a relevant
  absence or boundary.
- Keep source, assembled-artifact, and runtime claims separate so each receives evidence at the interface it promises.
- Separate deterministic contract checks from variable executor behavior, and retain enough independent run evidence to
  distinguish a repeatable result from one inconclusive attempt.
- Make each model-review conclusion traceable to the individual obligation and observable evidence that supports it, so
  a mistaken explanation cannot be treated as a verified failure cause.
- Preserve a stable test suite and execution identity so results from before and after a prompt revision are
  comparable.
- Keep fixture-only details out of reader guidance, so a passing result measures the prompt's general behavior rather
  than its ability to repeat a visible testcase.

For every durable prompt obligation, write an ordinary scenario that needs it and a non-applying or edge scenario that
would expose its absence. Compare them with an analogous and a dissimilar scenario where that obligation does not
apply. A scenario supplies only the task-local information an ordinary reader would have; it
must not leak the expected conclusion, intended change, or a prior diagnosis. When the test claims organic selection,
lazy load `rules/include/common/organic-prompt-tests.md` before designing or evaluating it. A certification test never
declares a route. When selection is missing or unclear, derive a separate routing-only diagnostic; it keeps the ordinary
task and fixture, stops after selection, and measures organic selection only. When selection is present but application
is unclear, derive the direct application-only diagnostic; it measures delivery and application, not organic selection.
Generate both only under `${CAT_WORK}/temp`; neither is certification or comparison evidence. Before a conditionally
routed evaluation, run
`prompt-evaluation audit-routing <rules-root> <router-rule> <certification-suite-directory>` to check that the asserted
routes have the named router as an owner and the durable suite measures the complete organic product.

When a scenario suite declares `generalization.fixture_distinctives`, run
`prompt-evaluation audit-generalization <scenario-suite-directory> <prompt-artifact>` before a build or model turn. Declare a term
only when it distinguishes a test fixture from the prompt's reader-facing contract; do not list general domain language
merely because a testcase uses it. The audit rejects a prompt that repeats such a term. If an external contract makes
the term necessary in reader guidance, cite that contract with the distinctive's `external_authority.path`; the audit
reads that suite-owned file and accepts the overlap only when the contract itself contains the term. Use a dissimilar
example to teach the same decision whenever no such authority exists.

Verify the source artifact and each promised delivered form separately. A source check can establish content; an
assembled-plugin, command, hook, or model execution is needed when the promise concerns that interface. Record each
required check as passed, failed, unrun, or explicitly excluded. A related successful check does not pass an unrun one.

Keep the test definitions and their initial project fixtures as version-controlled durable files outside the
delivered prompt source. Keep each trial's harness workspace as a fresh temporary copy of its persistent fixture, but
retain its result record and validator evidence in the evaluation output. Use fresh workspaces for a retest so a
previous result cannot supply the answer.

Run each deterministic checker at the first point where all of its inputs exist. A checker that decides only from the
suite and fixture runs before a harness turn. A checker that decides from retained executor events or output first runs
its known-good and known-bad controls before the turn, then runs against the retained result after the executor finishes
and before a model reviewer receives that result. When an executor's result can vary, hold the task, rendered prompt,
profile, available context, and acceptance test fixed; run independent fresh trials and preserve each result. Before
any variable trial, lazy load [formal-sprt.md](formal-sprt.md) and use its suite validation, immutable fingerprint, and
formal decision procedure. Do not present a single pass, a retry after a failure, or an unsupported statistical label as
evidence that variable behavior is reliable.

For a formal evaluator assertion, write a direct proposition about retained evidence in the scenario's `## Assertions`
section. The evaluator decides every prose assertion. Runtime validators own mechanically enforceable requirements and
their receipts; the Runner does not receive assertions or decide them. A scenario passes only when its evaluator
assertions, conventionally discovered Java checks, and runtime validators pass. Do not ask the reviewer to copy,
reinterpret, or choose a runtime-validator result: that would make one observable fact depend on two different decision
makers.

When assertions in different scenarios exercise the same prompt requirement, declare their stable assertion IDs in the
same `assertion_obligation_families` value in `suite.json`. The evaluator retains each scenario assertion separately, but its recurrence
review includes every active failure in that family. Omit the field when the assertions protect different requirements:
similar wording or a shared scenario does not establish one shared repair decision. The evaluator keeps an
undeclared assertion separate from every other assertion, so it never infers a family from labels or prose.

Before a changed candidate runs after a retained obligation exists, complete the workflow-owned revision review bound
to that candidate's identity. The review must address every active and recently covered obligation, identifying the
actor, direction, affected scope, correction target, and verification outcome each revised clause preserves. This review
checks a proposed change before a new model result can conceal an obligation that an earlier candidate already covered.
The workflow runs the review through a harness runner with its own writable project copy and records the runner receipt.
The receipt identifies the retained project that contains `review-result.json`; do not expect the runner to write into
the prepared source bundle, which remains read-only evidence. Use the public `prompt-evaluation revision-review` command
with the candidate-state ledger, proposed run, and a new review-bundle directory under CAT work temp to complete this
review. Do not invoke the workflow's preparation or recording steps as CLI commands.

For every formal batch model-reviewed acceptance assertion, retain a separate status and the concrete source, event,
command result, or produced value on which the reviewer relied. When the assertion names an input/output behavior, run
the smallest deterministic production-boundary probe that can show that behavior and give its output to the reviewer; a
source inspection or a test claim is not a substitute. Treat a reviewer verdict that conflicts with such a probe, omits
an assertion's evidence, or gives a different reason from the observed sequence as an inconclusive review result.
Escalate that bounded evidence to an independent stronger reviewer before diagnosing the executor or changing the
prompt.

Run `prompt-file-check` with every explicitly selected substantive prompt-file directory to reject a missing
`## Design Goals` section in any delivered form. When a substantive source file sits beside assembly-only wrappers,
pass that file itself instead of its directory. It recognizes the standard metadata-only `SKILL.md` wrapper; classify
every other wrapper before invoking it rather than treating unrelated Markdown or harness metadata as substantive
guidance.

When a prompt change requires an audit of source-code comments or Javadoc, run
`source-documentation-inventory <output.md> <source-root>...`. The resulting location inventory proves that the
reviewer considered the complete selected source scope; it does not replace the reader's prose review.
