# Testing Focus and Isolation

## Design Goals

- Test observable behavior with isolated, controlled inputs and state, ensuring each claim, exercised boundary, and
	assertion describe that same outcome rather than an implementation or artifact shape.
- Ensure an artifact-wide audit finds every executable verification artifact whose claimed outcome is not directly
	exercised and observed.
- Make application behavior directly and safely testable in the same process by passing environmental dependencies
	explicitly when shared process state would otherwise prevent isolation.
- Prove each claim that application code in the test's own process decides with a unit test of the production method
	that makes the decision, not with a test that runs or fakes an executable, network service, or operating-system
	facility. Keep integration tests only for claims about the boundary's own behavior and one end-to-end check per
	boundary, and lose no claim when converting an existing test.
- Verify meaningful changes efficiently with the narrowest relevant test and the complete suite before handoff.

## Guidance

When measuring or reducing a unit test's runtime, or deciding whether a costly test fixture is needed, lazy load
`testing/unit-runtime.md` before choosing which test work to keep.

When a test replaces a collaborator, external program, or process with a fake, stub, mock, or scripted fixture, lazy
load `testing/test-doubles.md` before writing or accepting its assertions.

Test observable behavior with controlled inputs rather than machine-specific configuration or implementation details.
Each test owns its fixtures and mutable state. Avoid tests that primarily validate third-party tools; test the
application's interpretation, command construction, error handling, and integration behavior instead.

Choose the test language from the behavior being claimed, not from the fact that it uses a command, files, or another
tool. Test behavior in the same process when its dependencies can be injected. Use the project's shell-test framework
when the claimed behavior depends on POSIX-shell parsing, expansion, exit handling, or a packaged shell-facing
interface. Do not use a shell test merely because application code invokes another command; do not use a facade that
skips shell behavior a shell contract must prove.

Before writing or keeping an integration test, list each claim it makes. An integration test is a test that crosses
an external boundary: an executable, network service, or operating-system facility that production code calls, such as
a version-control binary, a search tool, an HTTP service, a file lock, or a process launcher. A unit test runs in the
test's own process and calls production code without crossing such a boundary. A temporary file or directory that a
test creates and passes in as input is test data, not a boundary. Classify each claim:

- A decided claim is one that application code in the test's process decides: the command, arguments, or request it
	sends, how it interprets an output, response, or status it receives, and what it reports or does next. Prove every
	decided claim with a unit test instead of an integration test, and do not also assert it in an integration test. Do
	not prove it by putting a fake executable on the search path, running a fake server, using a real operating-system
	facility only to deliver a result, or waiting in real time for a deadline to pass.
- A boundary claim is about the boundary's own behavior: how a launcher or shell parses and expands arguments, the exit
	status or signal that a real process delivers, stream closure, a lock held across processes, or how the real
	program, service, or facility reacts to a request. Keep each boundary claim in an integration test. A double cannot
	establish how the real dependency reacts: prove that reaction against the real dependency, and unit-test only what
	the code does with it. When a boundary claim needs a stand-in process, write the smallest one in the project's
	primary language unless the claim is about the shell.

To make a decided claim unit-testable, split the decision from the interaction. The production code calls a method that
returns the decision as a value: the command and arguments to run, the request to send, or its interpretation of a given
output, response, or status. The production entry point performs the interaction with that value and passes the result
back for interpretation. Reuse an existing operation when it already owns the decision. Pass the current time, the
environment, and other shared process state to the method as parameters, so that a deadline decision is tested with a
given time instead of a real wait. Give the method an in-memory collaborator instead of having it return a value only
when the code exchanges several messages with the boundary and a later message depends on an earlier reply. The method
is a real production responsibility that the entry point uses, not a test-only path.

A unit test of that method asserts the outcome that the requirement names, as the method's result shows it. For
example, the method behind a search that must skip hidden files returns a command that excludes them, and the method
that reads an HTTP 503 response reports a retryable failure. The test does not assert that the method was called, how
often, or how it assembles its result.

Keep one end-to-end check per boundary: an integration test that runs the production entry point across the real
boundary. A retained boundary-claim test that does so already is that check. Its assertion shows only that the
interaction ran and its result reached the caller; it does not repeat the decided claims. When the real boundary is
unavailable where the tests run, report that instead of replacing it with a fake.

When the task does not permit changing the production code that makes a decision, a unit test cannot prove that claim.
Keep the integration test that proves it, and report the claim and the split that would make it unit-testable. This is
the only case in which an integration test asserts a decided claim.

When converting an existing integration test, classify each of its assertions as above. For each decided claim, add or
reuse the production method and write its unit test. Narrow the integration test to its boundary claims and the
end-to-end check. Remove each decided claim's assertion from the integration test only after you name the unit test
that now proves that claim and that test passes; until then, keep the assertion.

Do not fork a process merely to isolate the current directory, environment variables, standard streams, clocks, random
sources, or other shared process state. Refactor the owned behavior to accept that dependency explicitly, and have the
existing production entry point obtain the real value and delegate to the new operation. Tests must invoke that
operation with an isolated value they own, such as a temporary directory for the current directory. This preserves
production behavior while making parallel tests deterministic and self-contained.

Apply the temporary-resource ownership convention to every fixture a test creates. A test must clean up its fixture even
when setup, an assertion, or the behavior under test fails. When the fixture is consumed by a CAT runner, evaluator,
nested harness, or other CAT workflow, allocate its fresh root as a new child directory of CAT_TEMP rather than the
system temporary directory. A pure in-process unit-test fixture may use the test framework's temporary directory; the
fixture's consumer, rather than the test class that creates it, determines which location applies.

Do not rerun an unchanged test command merely for reassurance. Run the narrowest relevant test after a meaningful
change, then the complete verification suite before handoff.

Before accepting any test, state in one plain sentence what it says happens and identify the operation that makes it
happen. For example: "Installing the extension makes the host run its startup hook." If the test does not run the
operation that makes its claimed behavior happen, remove it or move it to the supported consumer entry point where it
can exercise and observe that behavior.

When a test uses a local value to simulate an external actor, resource, or boundary, make the mapping explicit in the
test's name, documentation, fixture names, and comments at the simulated transition. Identify the real actor and
resource, the local representation, the operation that changes its lifecycle, the consumer that observes the result,
and the asserted outcome. For a parent process consuming a forked child process's standard-error output through a pipe,
close the local output endpoint that represents the child, then wait for the parent-side reader and assert it completes;
do not close the input endpoint as a substitute for the child's close.

Name modeled actors at the narrowest level that changes the behavior. Do not call a simulated parent or child process
by a product or framework name when the test's contract depends only on ordinary process and stream semantics.

Derive each test from the observable claim it makes. A test must run the part of the system it says it tests: its name,
documented claim, controlled input, exercised boundary, and assertion must all describe the same observable outcome. Any
test that claims an externally observable outcome must run the boundary that produces it and assert the result.
Consumer, integration, lifecycle, installation, registration, and execution claims are non-exhaustive examples. Do not
prove such a claim by reading source files, generated configuration text, or asserting literal fragments.

When auditing executable verification artifacts across an existing scope, enumerate every test, scenario,
fixture-backed check, and executable example. For each one, apply the claim, exercised-boundary, and
observable-assertion requirements in this rule. Correct every unsupported claim by narrowing it, adding the missing
direct exercise and assertion, or splitting independently observable outcomes.

Do not add or retain a structural test. A test must not establish a claim by inspecting source code, type shape,
constructors, modifiers, record components, reflection metadata, generated configuration, package layout, or assembled
artifact contents. Exercise the behavior's supported consumer boundary and assert its observable effect instead.
