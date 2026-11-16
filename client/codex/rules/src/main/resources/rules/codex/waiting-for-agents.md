# Waiting for Agents

## Design Goals

- When a needed subagent is running and the agent can use a blocking wait tool, keep independent in-scope work
	progressing and avoid repeated timeout wakeups once only the subagent result remains.

## Guidance

When a needed subagent is running and `wait_agent` is available, lazy load
`${CAT_PLUGIN_ROOT}/rules/include/common/external-waits.md`.

While independent in-scope work remains, do that work instead of blocking on `wait_agent`. Once no such work remains,
call `wait_agent` with `timeout_ms` explicitly set to the maximum supported by its current tool contract, subject to
higher-priority limits. Do not omit `timeout_ms` and rely on its default.
