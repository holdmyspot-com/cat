# Non-build failure

## Variables

- **search_term**: A short search query, such as `blue notebook`

## Scenario

A running application's search request for `{{search_term}}` returned the wrong result. The user asks you to investigate
the request's query and data; no build script, pipeline, or multi-step build is being created, updated, or debugged, and
no build-pass claim is requested. State the next investigation step.

## Assertions

### no_build_workflow_route

- **kind**: effect
- **description**: The executor investigates the query and data without selecting
	`rules/include/common/build-workflow.md` or prescribing an incremental-then-clean build sequence.
