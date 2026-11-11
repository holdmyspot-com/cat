# Testing and Build Rule Routing

## Design Goals

- Route each test, build, and CI decision to its focused rule before the reader changes, runs, or reports on it.

## Guidance

When the project's build, tests or scripts fail on a machine other than the local one, such as in CI, a hosted workflow
or a remote build or test host, whatever the task is called, such as fixing failing tests, a red build or a release step
that waits for a required check, lazy load `../ci-debugging.md` before retrying the run, changing code to fix it or
delegating its fix. Do
not load it for a production incident, or for an infrastructure failure, which the project's commands did not cause: no
runner assigned or the runner lost, a service or network outage, a quota or rate-limit refusal, or the platform
cancelling the job or timing it out before the project's commands ran. A flaky test or a timeout inside the project's
own steps is a project failure. When the cause is unclear, rerun the job once without loading it, and lazy load it if
the same failure occurs again. When a job's download from an upstream server failed because the server could not be
reached, lazy load `../upstream-fallbacks.md` instead.

When running a CI or hosted workflow on a remote runner to test changed commands that it runs there, lazy load
`../remote-runner-testing.md` before dispatching the run.

Before changing testable production behavior, lazy load `../tdd.md`.

When designing, changing, running to verify a change, or reviewing tests, lazy load `../testing.md`.

Before starting, dispatching or requesting a build, test suite, workflow run or probe that takes many minutes or draws
on capacity that others share, such as the runners of a hosted workflow, and when a job of such a run that you started
has failed while other jobs of it are queued or running, lazy load `../expensive-verification-runs.md`.

When a test's outcome varies between runs of unchanged code and inputs, a test that should reproduce a problem
triggers it on only some runs, or before adding a randomized or property-based test, lazy load `../flaky-tests.md` before changing, rerunning, or removing that test.

When a test creates or shares a fixture, lazy load `../temporary-resources.md`.

When running a build, when a revision's tests are about to run again (a rerun, a filtered subset, a repeated stress run
or a run on another machine), or when selecting, configuring, or reusing a build or workflow cache, lazy load
`../build-caching.md`.

When writing or changing a build or job step that downloads dependencies, packages or tools from one upstream server,
lazy load `../upstream-fallbacks.md`.

When creating, updating, or debugging a build script, pipeline, or multi-step build, or introducing or repairing an
expensive many-unit workflow, lazy load `../build-workflow.md`.

Before selecting or reporting a command as evidence that a project's build passes, lazy load `../build-verification.md`.
