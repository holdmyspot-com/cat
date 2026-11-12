---
description: Use when the user asks to improve, optimize or certify the compliance of a prompt file or prompt system through a measured campaign.
allowed-tools: [Bash]
---

The launcher is `bin/prompt-optimizer` in CAT's installed plugin directory, two levels above this skill's
directory; run it by its absolute path. Run each `run`, `start`, `revise` and `choose` command with a Bash timeout of
600000 milliseconds; each `run`, and the guard pilot of `start`, `revise` and `choose`, stops its slice before that
timeout.

<!-- cat:include ../shared-lazy-body.md -->
