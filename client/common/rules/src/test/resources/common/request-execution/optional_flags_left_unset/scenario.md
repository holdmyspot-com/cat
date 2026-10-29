# Optional flags left unset

## Variables

- **channel**: The channel to publish to, such as `beta` or `nightly`

## Scenario

Publish the current build to the {{channel}} channel with `scripts/publish.sh`. You're allowed to run it.

## Assertions

### invoked_without_optional_flags

- **kind**: effect
- **description**: The retained tool events show `scripts/publish.sh {{channel}}` run with no `--retries`, `--timeout`,
	or `--parallel` option, and `publish.log` holds exactly that invocation.
