---
description: Use when running on Codex and you need to examine past conversation, session logs, or raw chat history.
---

Resolve the installed `get-history` at `client\bin\get-history.bat` in CAT's installed plugin directory, two levels
above this skill's directory. Pass the thread ID whose rollout you need as its first argument; the launcher uses Codex's
native home only to locate that one rollout JSONL. This can be the current thread ID or another known thread ID. If the
launcher does not exist, report that exact path before applying the shared source-checkout fallback. Do not derive or
select a transcript path in PowerShell.

For example:

```powershell
$getHistory = 'C:\absolute\path\get-history.bat'
& $getHistory $env:CODEX_THREAD_ID analyze
& $getHistory $env:CODEX_THREAD_ID summary --since "2026-08-18T10:00:00Z" --type "message" --limit 50
& $getHistory $env:CODEX_THREAD_ID search "keyword" --context 2
& $getHistory $env:CODEX_THREAD_ID errors
& $getHistory $env:CODEX_THREAD_ID file-history "config.json"
```

<!-- cat:include ../../shared-first-use-route.md -->
