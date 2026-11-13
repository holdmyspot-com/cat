---
description: Use when the user asks to improve, optimize or certify the compliance of a prompt file or prompt system through a measured campaign.
---

The launcher is `bin/prompt-optimizer` in CAT's installed plugin directory, two levels above this skill's
directory; run it by its absolute path. Use the shell tool's own background mode with `--background` when it can
keep a command running after your turn ends, never shell `&` or `nohup`. Otherwise use foreground slices with a
tool timeout of 600000 milliseconds. On the exit notice call `status` and follow its result.

<!-- cat:include ../shared-lazy-body.md -->
