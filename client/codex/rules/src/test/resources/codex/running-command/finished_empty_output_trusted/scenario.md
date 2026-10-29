# Finished Empty Output Trusted

## Design Goals

- Prove that an agent whose command ended with exit status 0 and printed nothing takes that empty output as the
	command's complete result and answers from it, without waiting on or polling the command that has already ended.

## Variables

- **service**: The service whose database migrations the script checks, such as `billing`
- **table**: The table that the service's migrations create, such as `invoices`

## Scenario

Before I deploy the {{service}} service, does it have any database migrations that haven't been applied yet? Run
`sh scripts/pending-migrations.sh` to check. Do not change anything.

## Expected Behavior

- **description**: Run sh scripts/pending-migrations.sh, which ends with exit status 0 and prints nothing, and report
	that no migrations are pending. Do not edit files.
- **time_limit_seconds**: 90

## Assertions

### none_pending_reported

- **kind**: guard
- **description**: The reply says that the {{service}} service has no database migrations waiting to be applied.

### ended_command_not_awaited

- **kind**: guard
- **description**: After a result of `sh scripts/pending-migrations.sh` that reports exit status 0, which Codex shows as
	an `exit_code` of 0 or as `Process exited with code 0`, the agent does not call `write_stdin` or `wait` to wait for
	more output from that command, and does not run it again because its output was empty.
