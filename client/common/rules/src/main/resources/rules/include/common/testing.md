# Testing Focus and Isolation

## Design Goals

- Test observable behavior with isolated, controlled inputs and state, ensuring each claim, exercised boundary, and
  assertion describe that same outcome and remain distinct from structural artifact validation.
- Ensure an artifact-wide audit finds every executable verification artifact whose claimed outcome is not directly
  exercised and observed.
- Make application behavior directly and safely testable in the same process by passing environmental dependencies
  explicitly when shared process state would otherwise prevent isolation.
- Keep test-support behavior at the narrowest boundary that can establish the claimed outcome. Prefer a direct
  primary-language operation with test-owned inputs and collaborators over a fixture process; reserve a script or
  native-process fixture for a claim about that boundary's own behavior.
- Verify meaningful changes efficiently with the narrowest relevant test and the complete suite before handoff.

## Guidance

Test observable behavior with controlled inputs rather than machine-specific configuration or implementation details.
Each test owns its fixtures and mutable state. Avoid tests that primarily validate third-party tools; test the
application's interpretation, command construction, error handling, and integration behavior instead.

Choose the test language from the behavior being claimed, not from the fact that it uses a command, files, or another
tool. Test behavior in the same process when its dependencies can be injected. Use the project's shell-test framework
when the claimed behavior depends on POSIX-shell parsing, expansion, exit handling, or a packaged shell-facing
interface. Do not use a shell test merely because application code invokes another command; do not use a facade that
skips shell behavior a shell contract must prove.

Before creating a fixture process, name the observable property that requires it. If the assertion concerns only
application-owned request construction, rule expansion, ordering, protocol interpretation, or result handling, invoke
the focused application operation directly with fixture files or in-memory inputs and an in-memory collaborator. Reuse
an existing operation when it already owns that behavior; otherwise have the production entry point delegate to a
focused operation that accepts the needed inputs and collaborator. That operation must remain a real production
responsibility, not a test-only alternate path.

Do not write a shell script merely to emit protocol data, create files, return an exit status, or stand in for a
runner. A fixture process is justified only when the asserted outcome depends on process-only behavior, such as native
command construction and launch, operating-system stream closure, exit status, signal handling, or the target shell's
parsing, expansion, or packaged-launcher contract. When that boundary is required, use the smallest fixture in the
project's primary language unless the assertion is specifically about the shell. A command invocation alone does not
make shell or process behavior part of the claim.

Do not fork a process merely to isolate the current directory, environment variables, standard streams, clocks, random
sources, or other shared process state. Refactor the owned behavior to accept that dependency explicitly, and have the
existing production entry point obtain the real value and delegate to the new operation. Tests must invoke that
operation with an isolated value they own, such as a temporary directory for the current directory. This preserves
production behavior while making parallel tests deterministic and self-contained.

Apply the temporary-resource ownership convention to every fixture a test creates. A test must clean up its fixture even
when setup, an assertion, or the behavior under test fails. When the fixture is consumed by a CAT runner, evaluator,
nested harness, or other CAT workflow, allocate its fresh root under the active harness's `${CAT_WORK}/temp` rather than the
system temporary directory. A pure in-process unit-test fixture may use the test framework's temporary directory; the
fixture's consumer, rather than the test class that creates it, determines which location applies.

Do not rerun an unchanged test command merely for reassurance. Run the narrowest relevant test after a meaningful
change, then the complete verification suite before handoff.

Before accepting any test, state in one plain sentence what it says happens and identify the operation that makes it
happen. For example: "Installing the extension makes the host run its startup hook." If the test does not run the
operation that makes its claimed behavior happen, rename it as a structure test or move it to the supported consumer
entry point.

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

Structural artifact tests are allowed when their claim is limited to packaged layout or validity. Build or obtain the
assembled artifact, parse its structured metadata, and assert required files, references, and formats using the artifact
as the input. Name these tests as packaging, structure, or validation tests, not as runtime registration or execution
tests. Keep structural coverage separate from behavior coverage; neither substitutes for the other.

Use the complete artifact produced by the production packaging operation. Do not extract one resource and add a fake
launcher, script, or other missing component before calling it an assembled artifact. A test that supplies such a
fixture may claim only the behavior of the component that consumes it; it may not claim packaging, registration,
installation, lifecycle, or execution behavior. A manifest, hook, or configuration file is a valid structural input only
when its exact format is the documented external artifact contract being validated.
