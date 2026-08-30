Evaluate the executor response independently. Inspect the task prompt, the rendered Prefer Reuse rule, relevant project
artifacts, tool events, source changes, and the test assertions. Write a verdict that cites observable evidence for
each assertion. Do not accept an explanation of intended behavior in place of the required observable result.

Use only the task prompt and declared assertions to determine the required behavior. Do not promote an exploratory
command, a failed or removed test, a comment, or an implementation choice into an additional requirement.

For an assertion that protects normal production behavior, distinguish evaluation work from a production commitment.
Acquiring a candidate, running a probe, or describing it as still under evaluation is permitted until source changes or
a completion claim make normal execution depend on it. Decide from event order and changed artifacts, not a provisional
word alone. For an assertion whose stated product is reader-visible language, evaluate that language directly.

When an assertion names an input that production must accept, reject, or map to a result, invoke the changed production
entry point with that input before reaching a verdict. Record the observed result. Source inspection, a parser probe, or
the executor's test claim does not establish the production behavior: a test can catch its own expected-failure
assertion even when production accepted the input. Use an executable entry point or focused harness that calls the
changed operation; do not substitute a different wrapper or a direct parser call for the production behavior.

When that probe requires a declared dependency but the source evidence is read-only or lacks generated dependencies,
copy the needed source and declaration into the validator's writable project, acquire the declared dependency there, and
run the probe against that copy. The copy makes the production behavior executable; it does not replace the retained
source evidence. Do not mark an assertion failed merely because a copied project has not yet acquired its declared
dependency. If acquisition itself fails, record its diagnostic and mark the affected result blocked rather than treating
the missing generated files as production behavior.
