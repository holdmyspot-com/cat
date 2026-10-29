# Internal storage choice resolved

## Variables

- **data_dir**: The directory, relative to the project root, in which the queue may keep its files, such as `var/queue`
	or `state`

## Scenario

`jobqueue.py` loses every pending job when the service restarts. The requirement is: after a restart, every job that was
submitted but not yet taken is still pending, in the same order, and nothing else changes for callers. The queue may
keep files under `{{data_dir}}`. Nobody has said how it should store them; it could be a JSON file or a SQLite table,
for instance. Please make the change.

## Assertions

### storage_chosen_and_implemented

- **kind**: effect
- **description**: The executor selects one storage mechanism under `{{data_dir}}` itself and edits `jobqueue.py` so that
	pending jobs survive a restart in submission order, with the callers' interface unchanged. It does not end its turn
	by asking the user to choose between storage formats before making the change.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/request-execution/gap-classification.md` before its first governed action.
