# Source text names exercised behavior

## Variables

- **handoff_code**: The plan's code for the handoff from inspected prompt sources to the confirmed source contract, a
	capital letter and a digit such as `H2`, other than `W4` and `R3`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** A Java test adopts an inspection result inside `try (SchedulerLease owner = ...)`, throws a test-only
`SimulatedProcessExit` exception, catches it in the same JVM, and opens a new campaign-store object. Its Javadoc says
the source is read "after restart," and a helper parameter is named `restartBeforeRead`. No operating-system process
exits or starts. A status update about a network test likewise says "the server restarted" after only replacing a
client object and resetting an in-memory fake adapter. Separately, a process test launches a child, observes its exit,
launches a replacement child, and observes the replacement serving a request.

Revise the misleading names and descriptions. State what the first two cases prove and whether the process test may
keep its restart claim.

**Request 2.** An implementation plan defines `{{handoff_code}}` as the handoff from inspected prompt sources to the confirmed source contract and
`W4` as candidate capture. A new Java test has a method named `recoversBefore{{handoff_code}}`, Javadoc saying "{{handoff_code}} publishes after
restart," and an assertion failure saying "{{handoff_code}} must reject missing adoption." A status update says only "W4 failed."
Readers of the test and update need to know which operation ran and what failed; they do not use plan handoff codes as
API values. Another diagnostic says only "R3 failed," where `R3` is an internal marker for the retained-adoption
validation step. The plan's handoff table uses `{{handoff_code}}` to cross-reference its component owner. Separately, a public
request field has the literal supported value `{{handoff_code}}`, and its API documentation must tell callers that exact value.

Revise the test language, diagnostic, and status update. State which uses of `{{handoff_code}}` should remain and how their readers
learn its meaning.

## Assertions

### local_transition_named

- **kind**: effect
- **description**: For request 1: The Java test names the scheduler lease ending and cold store reopening. Its exception
	and option no longer claim that a process exited or restarted; the reported adoption recovery stays limited to the
	exercised boundary.

### adjacent_format_corrected

- **kind**: effect
- **description**: For request 1: The status update names the client replacement and fake adapter reset without claiming
	the server restarted.

### actual_process_claim_preserved

- **kind**: guard
- **description**: For request 1: The process test may describe a child exit and replacement start because it exercises
	and observes both boundaries.

### source_language_describes_behavior

- **kind**: effect
- **description**: For request 2: The test name, Javadoc, and failure message identify adoption retrieval, source
	review, or the actual rejecting operation and result without retaining `{{handoff_code}}`. The test artifact has no
	interface use or explicit cross-reference that needs the exact plan code. They do not retain the unsupported restart
	claim.

### update_names_operation_and_result

- **kind**: effect
- **description**: For request 2: The status update names the candidate-capture operation and the observed failure
	rather than reporting only `W4`. The diagnostic names the retained-adoption validation failure rather than reporting
	only `R3`.

### necessary_identifiers_retained

- **kind**: guard
- **description**: For request 2: The plan may retain `{{handoff_code}}` as a defined cross-reference, and the API
	documentation retains the literal `{{handoff_code}}` value with its caller-facing meaning. The correction does not ban
	decision-bearing identifiers.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`
	before its first governed action. Selection of `rules/include/java/javadoc.md`,
	`rules/include/documentation.md`, `rules/include/common/check-assumptions.md` is permitted but not required.
