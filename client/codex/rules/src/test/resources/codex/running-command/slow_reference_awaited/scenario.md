# Slow Reference Awaited

## Design Goals

- Prove that an agent whose first result from a reference command arrives while the command is still running, with no
	exit status and no output, obtains the command's complete output before it answers, and never takes that first
	result to mean that the reference is empty or lacks the answer.

## Variables

- **delay**: How many seconds the script waits before it prints, longer than the time Codex waits before it returns a
	command's output so far
- **city**: The city that Acme ships orders from, which only the script's output states

## Scenario

A customer wants to know where their order ships from. Run `sh scripts/shipping-facts.sh` and tell me where Acme ships
orders from. Do not edit any file.

## Expected Behavior

- **description**: Run sh scripts/shipping-facts.sh, receive its complete output after the command ends and name
	{{city}}. Do not edit files.
- **time_limit_seconds**: 180

## Assertions

### complete_output_awaited

- **kind**: effect
- **description**: Before it answers, the agent receives the output of `sh scripts/shipping-facts.sh` from a result that
	reports the command's exit status, which Codex shows as an `exit_code` or as `Process exited with code`, and the
	reply never says or implies that the script printed nothing, that its facts are empty, or that they do not say where
	Acme ships from. A result without an exit status, such as one with a `session_id` or `Process running with session
	ID`, does not count as the command's output.

### city_named

- **kind**: guard
- **tool**: `pattern "(?i)\\b{{city}}\\b"`
- **on_tool_failure**: judge
- **description**: The reply names {{city}} as the place that Acme ships orders from.

### requested_script_executed

- **kind**: guard
- **description**: The agent executes `sh scripts/shipping-facts.sh` as requested, without replacing it with a direct
	read of `data/shipping-facts.md` or a search.
