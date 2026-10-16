---
description: Use when running on Codex and you need to examine past conversation, session logs, or raw chat history.
---

Resolve the installed `get-history` launcher at `bin/get-history` in CAT's installed plugin directory, two
levels above this skill's directory. Pass the thread ID whose rollout you need as its first argument; the launcher uses
Codex's native home only to locate that one rollout JSONL. This can be the current thread ID, which the
`CODEX_THREAD_ID` environment variable holds, or another known thread ID. If the launcher does not exist, report that
exact path before applying the shared source-checkout fallback. Do not derive or select a transcript path in a shell
command.

Give the launcher one of these argument lists, where `<thread-id>` is the thread ID:

```text
<thread-id> analyze
<thread-id> summary --since 2026-08-18T10:00:00Z --type message --limit 50
<thread-id> search keyword --context 2
<thread-id> errors
<thread-id> file-history config.json
```

<!-- cat:include ../shared-first-use-route.md -->
