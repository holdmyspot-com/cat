## Scenario

An implementation plan defines `H2` as the handoff from inspected prompt sources to the confirmed source contract and
`W4` as candidate capture. A new Java test has a method named `recoversBeforeH2`, Javadoc saying "H2 publishes after
restart," and an assertion failure saying "H2 must reject missing adoption." A status update says only "W4 failed."
Readers of the test and update need to know which operation ran and what failed; they do not use plan handoff codes as
API values. Another diagnostic says only "R3 failed," where `R3` is an internal marker for the retained-adoption
validation step. The plan's handoff table uses `H2` to cross-reference its component owner. Separately, a public
request field has the literal supported value `H2`, and its API documentation must tell callers that exact value.

Revise the test language, diagnostic, and status update. State which uses of `H2` should remain and how their readers
learn its meaning.

## Assertions

### source-language-describes-behavior

The test name, Javadoc, and failure message identify adoption retrieval, source review, or the actual rejecting
operation and result without retaining `H2`. The test artifact has no interface use or explicit cross-reference that
needs the exact plan code. They do not retain the unsupported restart claim.

### update-names-operation-and-result

The status update names the candidate-capture operation and the observed failure rather than reporting only `W4`. The
diagnostic names the retained-adoption validation failure rather than reporting only `R3`.

### necessary-identifiers-retained

The plan may retain `H2` as a defined cross-reference, and the API documentation retains the literal `H2` value with
its caller-facing meaning. The correction does not ban decision-bearing identifiers.
