# Publishing Workflows

## Design Goals

<!-- Tests that failed only on Windows, a release workflow's tag check and its cleanup job each failed first in a real
     release run, so each fix cost another build on every platform and another release. -->
- A run of a workflow that publishes, such as by creating a release, uploading a package or deploying, starts only
	after every step it runs, other than publishing itself, has passed on the same revision in runs that cannot publish,
	including a rerun of failed jobs when the workflow recovers that way, so that it fails only where publishing itself
	fails.
- A published artifact is the revision whose acceptance checks passed, with no pending change that alters it left
	undecided.

## Guidance

This rule applies before you start a run of a workflow that publishes, such as by pushing a release tag or dispatching a
deployment. Before that run, show in runs that cannot publish that every step it runs passes on the revision it will
publish:

- The build and tests: run the project's non-publishing CI on that revision, on every platform that the publishing run
	builds on, and wait for it to pass.
- The steps that only the publishing workflow runs, when the workflow or a script it runs changed since its last
	successful run: run a copy of the workflow on a branch, triggered by a push to that branch, with each step that
	publishes or deletes published output replaced by one that prints what it would do. Give the copy the inputs that
	the real trigger supplies, such as the tag name.
- When a failed run is recovered by rerunning its failed jobs and the change can affect that path, make one job of the
	copy fail once, rerun the failed jobs, and check that the rerun completes.

Before the publishing run, land or explicitly defer every pending change that alters the published artifact, and run
the final acceptance checks once, on the revision that it publishes, instead of after each fix.

Start the publishing run only after these pass.
