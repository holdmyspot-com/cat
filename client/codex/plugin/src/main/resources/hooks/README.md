# Codex Hooks

Before usage reporting, run the installed plugin's `client/bin/login` command.
With the default Codex home, its path is
`~/.codex/plugins/cat-marketplace/plugins/cat/client/bin/login`; when
`CODEX_HOME` is set, use that directory in place of `~/.codex`. Login prints a
WorkOS verification link and code, then saves the authorization. If the login
has one linked HoldMySpot account, it selects that account. If it has several,
run the neighboring `client/bin/account-select <account-id>` command with one
of the listed IDs.

CAT runs `session-start` for each configured Codex `SessionStart` event.
After it checks the selected account, the hook supplies eligible rules and
records each rule it delivers. When a rule refers to a packaged lazy rule,
Codex should use the neighboring `client/bin/rule-load include/<rule>.md`
command. That command checks account access before supplying the rule, records
its delivery, and queues the event if the server cannot be reached within the
offline allowance. With no authorized account, it supplies no rule content.
It runs `stop` for each `Stop` event after a turn finishes. The Stop hook reads
the rollout supplied by Codex, queues any nonzero measured output tokens for
the selected HoldMySpot account, and attempts to deliver pending reports. If
the host does not confirm delivery, the report remains in the plugin data
directory for a later Stop event. The hook returns a JSON response to Codex;
delivery failures appear as a `systemMessage`.

For this hook, Codex supplies `PLUGIN_ROOT`, the directory of the installed CAT plugin, to locate the shared
`${PLUGIN_ROOT}/hooks/session-start.sh` launcher. The launcher derives CAT's installation directory from its own
location before it runs the native session-start command.
The Stop launcher uses the same plugin root and Codex's `PLUGIN_DATA` directory
for the saved login and pending reports.

The command hook sets `additionalContextLimit` to `0`, disabling additional-context spilling for this hook. Codex
otherwise applies a default limit. For the meaning and introduction of this Codex hook property, see
[openai/codex#34393](https://github.com/openai/codex/pull/34393).
