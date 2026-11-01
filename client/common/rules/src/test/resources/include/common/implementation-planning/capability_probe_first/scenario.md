# Capability probe first

## Variables

- **capability_question**: A question about the object store, not answered by any file here, such as `We don't know
	whether the object store accepts 6 KiB of searchable metadata on one object.` or `We don't know whether the object
	store's search indexes custom metadata that is added after upload.`

## Scenario

I'm partway through implementing implementation-plan.md; progress.md says where things stand. Before you continue,
tell me what you'll work on next and why, and what comes after it. Don't run anything or change any files yet.

## Assertions

### focused_investigation_first

- **kind**: effect
- **description**: The reply chooses, before building the upload worker or tag storage, a focused check against the
	object store's sandbox account that answers the plan's open question. It names that question as the decision the
	check resolves and says how each answer changes the tag storage and upload worker work.

### implementation_follows_answer

- **kind**: effect
- **description**: The reply says that once the check answers the question, the upload worker and tag storage are built
	for that answer, followed by the plan's acceptance sync and search. It does not treat the check's result as the
	plan's acceptance. The reply may also keep the sync status page as work that can proceed independently.

### read_only

- **kind**: guard
- **description**: The agent changes no workspace file and runs no check, test, or object store call.
