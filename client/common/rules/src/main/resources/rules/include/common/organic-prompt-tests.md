# Organic Prompt Tests

## Design Goals

- Certify conditionally routed prompt behavior through ordinary work: the task activates the route, the selected prompt
  governs the work, and the observable result meets the contract without test-only steering.
- Separate a missed lazy route from a misapplied delivered prompt with bounded, generated diagnostics, without treating
  either diagnostic as certification or comparison evidence.
- Keep a certification fixture's concrete values from becoming undocumented requirements of the prompt it measures, so a
  passing result shows application of the ordinary contract rather than recall of one test case.

## Guidance

For every conditionally routed prompt, design durable certification cases from the reader's real task and initial project
state. Make the target behavior necessary to complete that task, but do not add a target rule or skill name, procedure,
answer, or acceptance criterion solely to steer that behavior. Provide only context an ordinary reader would receive;
an actual user contract, including required output language, remains part of the task. Keep evaluator-only assertions,
route expectations, and evidence instructions out of the executor's input.

For a positive certification case, use a realistic task whose observable need matches the target's documented trigger.
Verify that native events show the target selected before the first task action and that the resulting behavior would
fail if the prompt's required procedure were skipped. For a negative or boundary case, use a realistic nearby task that
lacks that need, verify that the target was not selected, and verify the task's observable result. A certification case
therefore measures `organic_selection` and `application` together.

State in the task every ordinary contract fact that makes its lazy route applicable. When an assertion requires different
results for two concrete inputs, the task must name those inputs and outcomes too; the verifier cannot supply a hidden
trigger. For example, a TOML reader task that must preserve integer versus decimal syntax says that it accepts `8080`
and rejects `8080.0`, rather than leaving the distinction only in a later assertion. This states the user's required
behavior; it does not name the target prompt, tell the executor which component to choose, or prescribe its procedure.

When a failed certification case motivates a prompt revision, separate the task's general decision from that case's
concrete inputs. Put the general decision in the prompt—for example, preserve a required difference between input forms
even when ordinary processing could produce the same value—and keep the particular input pair in the task and fixture.
Do not promote a fixture value into prompt guidance merely because a trial used it or failed on it. Retain an exact
value in prompt guidance only when an authoritative non-test contract requires that value and ordinary readers need it
to make the prompt's decision; a protocol's required literal marker is one such case. Check a same-decision case with
different concrete values and a dissimilar case whose exact value changes the decision before making that promotion.

When an organic certification case fails, use its retained native events and task result first. Run `prompt-evaluation
classify-failure <source-run.json> <case-id> <trial>` before deciding whether another model turn is useful. If the
result identifies routing/delivery, application, a deterministic tool, an external prerequisite, or infrastructure,
repair that owner instead. Only an `unresolved` result may derive one temporary application-only diagnostic with
`prompt-evaluation create-direct-diagnostic <source-run.json> <case-id> <trial> <diagnostic-suite-directory>` when
application remains unclear. It recreates the case from the failed run's retained certification-suite snapshot,
delivers the expected rules before the unchanged task, then measures only delivery and application. An unresolved
selection question may derive one temporary routing-only diagnostic with `prompt-evaluation
create-routing-diagnostic <source-run.json> <case-id> <trial> <diagnostic-suite-directory>`. It recreates every organically
routed ordinary task and fixture from the failed run's retained certification-suite snapshot but
asks the executor to select and load applicable rules, then stop before project inspection, candidate search,
implementation, or a task answer. It measures only organic selection. Generate either manifest below `${CAT_WORK}/temp` from
the retained certification suite; do not maintain a second model-test suite, report a compliance rate, compare it with
a certification run, or run it before the organic failure identifies its question. A deterministic CLI or source test
with no model-selected prompt is outside this rule; test its observable contract directly.

When deriving either diagnostic, retain only the case fields that establish its stated question. Remove an application
assertion, deterministic check, validator setup, or other prerequisite whose owned product the diagnostic does not
measure; otherwise the derived suite can fail on work it intentionally did not ask the executor to perform. Validate the
generated manifest before starting a harness. For example, a routing-only diagnostic keeps route expectations and its
routing assertion, but removes a dependency-declaration check that belongs to the later implementation.

For each conditionally routed suite, name the router that owns its expected paths and run `prompt-evaluation
audit-routing <rules-root> <router-rule> <certification-suite-directory>` before a variable evaluation. The audit rejects a
route expectation that the named router cannot reach and rejects a suite that does not measure ordinary selection and
application together. It establishes source ownership only; retain native certification events to establish that the
executor selected the route.

Before changing a durable prompt suite, state its purpose and which products each case measures. Keep the task, fixture,
route expectation, and assertions stable while comparing certification versions. An eager prompt that is present in an
ordinary session has no selection decision: verify its native startup delivery and application, but do not manufacture
an organic-selection case.
