---
description: Use when running on Codex and you need to examine past conversation, session logs, or raw chat history.
---

Resolve the installed `get-history` launcher at `bin/get-history` in CAT's installed plugin directory, two
levels above this skill's directory. Pass the thread ID whose rollout you need as its first argument; the launcher uses
Codex's native home only to locate that one rollout JSONL. This can be the current thread ID, which the
`CODEX_THREAD_ID` environment variable holds, or another known thread ID.

Give the launcher one of these argument lists, where `<thread-id>` is the thread ID:

```text
<thread-id> analyze
<thread-id> summary --since 2026-08-18T10:00:00Z --type message --limit 50
<thread-id> search keyword --context 2
<thread-id> errors
<thread-id> file-history config.json
<thread-id> request-status
```

`request-status` reads a user message's text, or its start, on standard input, such as from a file that holds it, and
reports whether a later turn answered it: `compacted_at=` gives the time of the thread's latest compaction, the
`before_compaction:` line describes the last matching message sent before it, which the compaction may have kept, and
each `after_compaction:` line describes a matching message sent after it, which is a new request. Each description
gives when the message was sent and either when it was answered, with the answer's opening text, or `no answer in its
own turn`, and whether its turn completed, was aborted, ended without completing when a later turn started, or has not
finished. A message with no answer in its own turn also lists, by completion time, the later turns that completed, so
that you can check whether one of them finished the work.

<!-- cat:include ../shared-first-use-route.md -->
