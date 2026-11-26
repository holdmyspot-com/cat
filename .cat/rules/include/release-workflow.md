# CAT Release Workflow

## Design Goals

- A CAT release tag starts the release workflow only after the release commit's build and tests, and every release-only
	step that changed since the last successful release, have passed in runs that create no GitHub release.
- A release publishes the packages that the release commit's own passing Build run built, and never builds them again.

## Guidance

Apply this before pushing a CAT release tag, which starts `.github/workflows/release.yml` and publishes a GitHub
release.

1. Push the release commit on a branch and wait until `.github/workflows/build.yml` passes on every platform. It runs
	the release's build and tests on the same platforms, publishes nothing, and uploads the release's packages as the
	artifacts `cat-<platform>`, which it keeps for 3 days. The release publishes those packages: it fails, without
	building anything, when the tag's commit has no passing Build run on a push or when that run's artifacts have
	expired. When they expired, re-run that Build run, wait for it to pass, then push the tag or re-run the failed
	release run.
2. When `release.yml` or a script it runs changed since the last successful release, use code to copy the workflow
	file from the release commit into a workflow on a test branch that runs on a push to that branch. In the copy,
	replace every `${GITHUB_REF_NAME}` with the release tag,
	because GitHub sets that variable to the branch name and does not let a workflow override it, and replace the
	`gh release create` command with one that lists the files it would publish. Before pushing the test branch,
	compare the rehearsal workflow with the release commit's workflow and verify that the diff contains only the
	rehearsal trigger and these substitutions. The push also starts the Build on the test branch's head, and the
	rehearsal publishes that Build's packages, so its first run fails until that Build passes: re-run the failed job
	then.
3. When the change can affect recovering a failed release by re-running its failed job, make the rehearsal's publish
	job fail once after it downloads the packages, re-run it, and check that it then succeeds.
4. Delete the test branch and its workflow copy, then push the tag.
