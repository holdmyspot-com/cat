---
description: Use when the user asks to install, update, upgrade or downgrade CAT, to install a CAT version or release, or whether a newer CAT release is available.
---

This skill belongs to CAT's Codex plugin, whose harness identifier is `codex`, as in `--harness codex`. Wherever its
instructions write `<h>`, write `codex` in its place.

For a direct `installer install` command, request approval to run that exact command outside the sandbox before the
first attempt. Codex registration can write to a protected marketplace metadata directory even when the marketplace
root is writable. Run a complete `update_command` or `consent_command` from the installer as its instructions direct.

<!-- cat:include ../shared-lazy-body.md -->
