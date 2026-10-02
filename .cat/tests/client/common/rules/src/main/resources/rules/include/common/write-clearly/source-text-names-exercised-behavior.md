## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

### Request 1

A Java test adopts an inspection result inside `try (SchedulerLease owner = ...)`, throws a test-only
`SimulatedProcessExit` exception, catches it in the same JVM, and opens a new campaign-store object. Its Javadoc says
the source is read "after restart," and a helper parameter is named `restartBeforeRead`. No operating-system process
exits or starts. A status update about a network test likewise says "the server restarted" after only replacing a
client object and resetting an in-memory fake adapter. Separately, a process test launches a child, observes its exit,
launches a replacement child, and observes the replacement serving a request.

Revise the misleading names and descriptions. State what the first two cases prove and whether the process test may
keep its restart claim.

### Request 2

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

### local-transition-named

For request 1: The Java test names the scheduler lease ending and cold store reopening. Its exception and option no
longer claim that a process exited or restarted; the reported adoption recovery stays limited to the exercised boundary.

### adjacent-format-corrected

For request 1: The status update names the client replacement and fake adapter reset without claiming the server
restarted.

### actual-process-claim-preserved

For request 1: The process test may describe a child exit and replacement start because it exercises and observes both
boundaries.

### source-language-describes-behavior

For request 2: The test name, Javadoc, and failure message identify adoption retrieval, source review, or the actual
rejecting operation and result without retaining `H2`. The test artifact has no interface use or explicit
cross-reference that needs the exact plan code. They do not retain the unsupported restart claim.

### update-names-operation-and-result

For request 2: The status update names the candidate-capture operation and the observed failure rather than reporting
only `W4`. The diagnostic names the retained-adoption validation failure rather than reporting only `R3`.

### necessary-identifiers-retained

For request 2: The plan may retain `H2` as a defined cross-reference, and the API documentation retains the literal `H2`
value with its caller-facing meaning. The correction does not ban decision-bearing identifiers.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/write-clearly.md` before its first
governed action.
Selection of `rules/include/java/javadoc.md`, `rules/include/documentation/general.md`,
`rules/include/common/check-assumptions.md` is permitted but not required.
