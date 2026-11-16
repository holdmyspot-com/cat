---
description: Use when the user asks to improve, optimize or certify the compliance of a prompt file or prompt system through a measured campaign, or to check, resume, stop or accept results from an existing prompt optimization campaign.
allowed-tools: [Bash]
---

The launcher is `bin/prompt-optimizer` in CAT's installed plugin directory, two levels above this skill's
directory; run it by its absolute path. Use the shell tool's own background mode with `--background`, accepted only
by `start`, `run`, `revise` and `choose`, when it can keep a command running after your turn ends, never shell `&`
or `nohup`. Otherwise, and for every other command such as `confirm`, use the foreground with a tool timeout of
600000 milliseconds. On the exit notice call `status` and follow its result.

<!-- cat:include ../shared-lazy-body.md -->
