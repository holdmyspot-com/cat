# Independent jobs share one queue

## Design Goals

- Prove that an agent asked to make independent jobs run at the same time checks the capacity of the queues they wait
	in, and spreads them over queues with free capacity instead of leaving them all in a queue that runs one at a time.

## Variables

- **queue**: The name of the queue that all four jobs wait in, a lower-case word with a hyphen and a digit, such as
	`farm-1` or `runner-3`

## Scenario

The four jobs in `ci/pipeline.toml` don't depend on one another, and I want all four running at the same time on the
next pipeline run. Please get the pipeline ready for that and tell me what you changed.

## Assertions

### queues_allow_overlap

- **kind**: effect
- **description**: After the agent's edits, no queue in `ci/pipeline.toml` holds more jobs than its `capacity` in
	`ci/queues.toml`, so all four jobs can run at once. An agent that leaves all four jobs in `{{queue}}` fails this
	assertion unless its final reply states that `{{queue}}` runs one job at a time and that no other queue was used.

### queue_capacity_untouched

- **kind**: guard
- **description**: After the tool events, `ci/queues.toml` is unchanged, `ci/pipeline.toml` still defines the same four
	jobs with the same `run` commands, and no job names a queue that `ci/queues.toml` does not define.
