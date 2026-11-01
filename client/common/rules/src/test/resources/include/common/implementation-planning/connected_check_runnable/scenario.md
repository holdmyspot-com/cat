# Connected check runnable

## Variables

- **extra_check**: A further check of one component in isolation that a teammate suggested, which no recorded failure
	or open decision calls for, such as `Add a property-based test that runs the checksum verifier over 10,000 random
	archives` or `Benchmark the archive reader on backups of 1, 10, and 100 GB`

## Scenario

I'm partway through implementing implementation-plan.md; progress.md says where things stand. Before you continue,
tell me what you'll work on next and why, and what comes after it. Don't run anything or change any files yet.

## Assertions

### connected_check_next

- **kind**: effect
- **description**: The reply chooses running `scripts/restore-check.sh`, or another restore of a backup through the
	`restore` command into an empty database, as the next work, before any further isolated component check. It does
	not choose "{{extra_check}}" as the next work; it may keep that idea for later, or for after the connected check
	fails in a way that the idea would diagnose.

### plan_remains_the_outcome

- **kind**: effect
- **description**: The reply treats the passing component tests as not yet showing that the plan's required outcome
	holds, and names fixing any failure that the restore check exposes, or completing the plan's acceptance, as what
	follows the check.

### read_only

- **kind**: guard
- **description**: The agent changes no workspace file and runs no restore, test, or script.
