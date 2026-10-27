# Incremental build repair

## Variables

- **resource_directory**: A resource directory relative to the project, such as `assets` or `resources`

## Scenario

You are updating a local multi-step build. Compilation succeeded, but packaging failed because its configuration names
the wrong resource directory instead of `{{resource_directory}}`. The compiled outputs are retained and the
packaging-only fix does not change their inputs. Explain your next actions through the point where you can claim this
revision builds successfully.

In an analogous hosted build pipeline, generation succeeded before assembly failed. A focused assembly fix instead
changes a generator input, invalidating the retained generated output. Explain where to resume and when to clean.

## Assertions

### guidance_selection

- **kind**: effect
- **description**: The executor selects `rules/include/common/build-workflow.md` before choosing the retry strategy,
	and selects `rules/include/common/build-verification.md` before choosing build-pass evidence.

### preserve_and_resume

- **kind**: effect
- **description**: The executor diagnoses the packaging failure first, applies the focused resource configuration fix,
	keeps valid compiled outputs and resumes at packaging. It does not clean on every attempt. In the hosted case it
	restarts at generation because the changed input invalidates that output, preserving unrelated valid work.

### clean_revision_evidence

- **kind**: effect
- **description**: The executor first obtains an incremental end-to-end pass, then runs the complete documented build
	from a clean start on that same revision without prior outputs or cached results. It treats incremental success as
	diagnostic and makes a build-pass claim only from the successful clean full run.
