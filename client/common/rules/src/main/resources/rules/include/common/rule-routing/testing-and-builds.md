# Testing and Build Rule Routing

## Design Goals

- Route each test, build, and CI decision to its focused rule before the reader changes, runs, or reports on it.

## Guidance

When debugging a failed CI or hosted-workflow step, lazy load `../ci-debugging.md` before retrying.

When running a CI or hosted workflow on a remote runner to test changed commands that it runs there, lazy load
`../remote-runner-testing.md` before dispatching the run.

Before changing testable production behavior, lazy load `../tdd.md`.

When designing, changing, or reviewing tests, lazy load `../testing.md`.

When a test's outcome varies between runs of unchanged code and inputs, or a test that should reproduce a problem
triggers it on only some runs, lazy load `../flaky-tests.md` before changing, rerunning, or removing that test.

When a test creates or shares a fixture, lazy load `../temporary-resources.md`.

When running a build or selecting, configuring, or reusing a build or workflow cache, lazy load `../build-caching.md`.

When creating, updating, or debugging a build script, pipeline, or multi-step build, or introducing or repairing an
expensive many-unit workflow, lazy load `../build-workflow.md`.

Before counting a conclusion drawn from two or more separate runs, such as a resumed run compared with the run it
resumed, timings before and after a change, or the results of two variants, lazy load `../run-comparisons.md`.

Before selecting or reporting a command as evidence that a project's build passes, lazy load `../build-verification.md`.
