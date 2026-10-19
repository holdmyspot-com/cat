---
paths:
  - "client/**/skills/**"
  - "client/distribution/**"
---
# Skill Assembly

## Design Goals

- Ship each harness a complete skill whose frontmatter it supports while maintaining shared skill instructions once.
- Keep a repeatedly selected skill's `SKILL.md` limited to routing, so its full `first-use.md` instructions are read on
	first use in the current conversation context and read again only after that context resets or is compacted.
- Word each Codex skill once for every platform, although Codex runs commands in PowerShell on Windows and in a POSIX
	shell elsewhere.

## Guidance

The common skill layer must not contain a file named `SKILL.md`: it is harness-neutral source, not an installable skill.
Keep its shared body and instructions in source-only files. When a shared skill needs harness-specific frontmatter, give
each harness its own `SKILL.md` header in that harness's skill layer. Each header must include the shared body with
`<!-- cat:include PATH -->`. When several skills have identical body text, store it once directly under `skills/` and
have their headers include it with a bounded relative path such as `../shared-first-use-route.md`. The distribution
builder expands every include only after it applies harness layers, then removes every referenced source-only body file
after all headers have been expanded. Include expansion is assembly-time work: the assembled `SKILL.md` contains the
complete body before an agent selects the skill. A shared body that tells the agent to read sibling `first-use.md` only
when it has not already read it in the current conversation context therefore defers that later read; it does not make
the include lazy. After a context reset or compaction, the agent reads `first-use.md` again. Verify the assembled
artifact for each harness: it must contain the complete body and only frontmatter that harness supports. Do not ship the
include directive or its source-only body file to a harness. Use this indirection only when the same body is paired with
different harness headers; otherwise keep the body inline in its single `SKILL.md`.

Codex runs a skill's commands in PowerShell on Windows and in a POSIX shell elsewhere, and one skill file serves both.
Do not write shell syntax in a Codex skill, such as a variable, a default value, a pipe, or a call operator; a
single-quoted argument is the same in both shells. Describe each command as the launcher or program to run, its argument
list, and the lines it reads on standard input, with each value the agent supplies written as a placeholder such as
`<project>`, and say in words where a value comes from, such as an environment variable. Each platform's Codex
`cat-env.md`, which every Codex session receives, states how its shell reads an environment variable, runs a launcher
and pipes lines to standard input; add a convention there when a skill needs one that it lacks, in both versions. Claude
Code runs commands in Git Bash on Windows, so a Claude skill may show bash commands.
