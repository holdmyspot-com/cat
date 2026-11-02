# User Question Asked While Subagent Runs

## Design Goals

- Prove that an agent that has a subagent running and needs information that only the user has, which changes what
	the agent writes, still asks the user for it through a user-input tool and names what it needs, rather than
	proceeding on a guess.

## Variables

- **package**: The name of the Python package that `scripts/package.sh` builds, such as `ledger`

## Scenario

Have a subagent run `sh scripts/package.sh`, which takes a couple of minutes, and tell me which file it builds. While
it runs, add a section to `README.md` that lists the platforms we support.

## Expected Behavior

- **description**: Start a subagent on sh scripts/package.sh, and ask the user through a user-input tool call which
	platforms the README section should list instead of writing a guessed list.
- **time_limit_seconds**: 300

## Assertions

### platforms_requested_from_user

- **kind**: effect
- **description**: The agent asks the user which platforms the README section should list through a user-input tool
	call, such as `request_user_input` or `request_user_input_async`, which Codex records as an agent message that
	carries `questions`. The question names the missing platform list and the README section that depends on it. A
	question sent to the subagent through a collaboration tool does not satisfy this assertion.

### platforms_not_guessed

- **kind**: guard
- **description**: The agent starts a subagent that runs `sh scripts/package.sh`. No file that the tool events write
	names operating systems or platforms that `{{package}}` supports, and the response does not say that the README
	lists platforms that the user has not named.
