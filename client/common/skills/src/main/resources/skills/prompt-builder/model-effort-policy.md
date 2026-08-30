# Evaluation Model and Effort Policy

## Design Goals

- Measure a prompt with the model and reasoning effort declared for the reader that is expected to follow it in normal
  use, rather than an arbitrary profile chosen by the person running the evaluation.
- Make the effective executor and validator profiles explicit, reproducible evaluation evidence so prompt revisions are
  compared only under the same conditions.
- Keep a profile experiment separate from evidence that certifies the delivered prompt configuration.
- Keep Sol-high review as a recorded resolution path for evidence-defined uncertainty rather than silently changing the
  ordinary reviewer for every evaluation.

## Guidance

Before creating a formal-evaluation plan, identify whether the prompt's authoritative delivery route names a dedicated
CAT agent. Invoke the selected harness runner's `--resolve-profile` command with no agent for an unowned route, or with
its artifact and that declared agent name for an owned route. Use the command's `owner`, `model`, and `effort` result
fields unchanged in the executor plan and runner arguments. The Codex runner's unowned default is `gpt-5.6-luna` at
`medium` effort; the Claude runner's is `haiku-4.5` at `medium` effort. Those values are runner policy, not a user
configuration fallback.

Do not substitute the profile of the person running Prompt Builder, infer a profile from the prompt's filename or
importance, manually copy agent metadata, or silently choose a stronger profile. If the delivery route does not identify
whether it has an owner, stop before claiming a formal result and report the missing ownership information.

Resolve the validator profile through the same runner command. Its plan entry must name the independent validator model
and effort that the evaluation contract requires; it does not become the executor's profile merely because it reviews
the executor's work.

Persist the resolved executor and validator profile fields with the suite fingerprint, rendered prompt fingerprint,
execution context, runner artifact, and sandbox policy before the first trial. A formal series uses one fixed effective
executor profile and one fixed validator profile. Changing an agent's model or effort, selecting a different default,
or changing either profile field starts a new series: retain earlier evidence for diagnosis, but do not combine it with
the new result or compare their compliance rates as the same condition.

If a completed suite indicates that the declared profile may be insufficient, first classify the failure using the
normal failure-revision workflow. Do not raise a profile for a missing rule, stale artifact, unreadable route, failed
preflight, runner, validator, sandbox, dependency, or other delivery and infrastructure failure: the stronger executor
would receive the same broken input. Fix that boundary and retain its successful evidence first.

Use the official profile-capability diagnostic only after all of the following are true: routing audit and deterministic
preflight passed; retained native events prove the executor received the current selected rule before task work; no
trial has an infrastructure or validator-evidence failure; the same case, assertion, and first wrong decision failed in
three fresh target-profile trials with no pass; and two prompt revisions aimed at that decision, each followed by a
target-profile smoke run, did not change that pattern. Generate the direct-application or routing-only diagnostic that
isolates the question, then run
`prompt-evaluation capability-diagnostic <source-run.json> <case-id> <trial> <diagnostic-suite-directory>
<prompt-artifact> <harness> <execution-context> <runner> <build-plan.json> <executor-model> <executor-effort>
<retained-directory-under-cat-work-temp>`. It verifies the retained application or routing failure, its three matching
target-profile checks, and the earlier prompt revisions before it starts. It then runs exactly three matched trials
with only the executor model and effort changed; the independent validator remains on the resolved target profile. Keep
the prompt, task, fixture, route, runner artifact, sandbox, network, cache, and validator profile unchanged. Its result
is diagnostic evidence, not a compliance rate, certification, or comparison input.

If all three higher-profile trials pass and all three target-profile trials made the same completed failure, record
profile sensitivity. If the higher profile repeats the failure, repair the prompt, route, test, or execution boundary.
If either profile has mixed outcomes, the experiment is inconclusive. A profile-sensitive result requires an explicit
product decision: reduce the decision burden with deterministic support or change the authoritative delivered profile,
then start a new formal series. Do not describe finite trials as proof of a universal minimum model capability.

The normal independent reviewer remains the fixed reviewer for a formal series. Use Sol-high (`gpt-5.6-terra` at high
effort) only after the normal review has an identified evidence problem: it conflicts with a deterministic probe, leaves
an assertion without required evidence, cannot resolve a material contradiction, or concerns a recurring or high-impact
failure. Record the triggering evidence and both profiles. Sol-high diagnoses or resolves that bounded disagreement; it
does not replace the ordinary reviewer's profile for certification unless the user changes the authoritative evaluation
policy and starts a new series.
