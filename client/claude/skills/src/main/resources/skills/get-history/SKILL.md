---
description: Use when running on Claude Code and you need to examine past conversation, session logs, or raw chat history.
allowed-tools: [Bash]
---

Resolve the installed `get-history` at `client/bin/get-history` in CAT's installed plugin directory, two levels above
this skill's directory. Pass the session ID whose history you need as its first argument. Without `--agent`, the
launcher selects that session's main transcript. With `--agent <agent-id>`, it selects that subagent's transcript within
the requested session. If the launcher does not exist, report that exact path before applying the shared source-checkout
fallback. Do not derive or select a transcript path in Bash.

For example:

```bash
/absolute/path/get-history "$CLAUDE_CODE_SESSION_ID" analyze
/absolute/path/get-history "$CLAUDE_CODE_SESSION_ID" summary --since "2026-08-18T10:00:00Z" --type "message" --limit 50
/absolute/path/get-history "$CLAUDE_CODE_SESSION_ID" --agent "agent-123" search "keyword" --context 2
/absolute/path/get-history "$CLAUDE_CODE_SESSION_ID" errors
/absolute/path/get-history "$CLAUDE_CODE_SESSION_ID" file-history "config.json"
```

<!-- cat:include ../shared-first-use-route.md -->
