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
- Give each harness on each platform skill commands that the shell it runs them in can run.

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

Codex runs a skill's commands in PowerShell on Windows and in a POSIX shell elsewhere, so a Codex skill whose files hold
commands needs a wording for each. Put a file's Windows wording in the skill's `windows` folder under the file's name,
such as `skills/<name>/windows/first-use.md`; the folder holds only files. When `DistributionMain` builds the Codex
artifact for Windows, `SkillPlatformVariants` moves each of them over the skill's file of the same name, and on other
platforms it deletes them; either way the artifact holds no `windows` folder. A Windows `SKILL.md` may include a shared
body, but the builder expands it before it moves the file, so write the include's path relative to the `windows`
folder, such as `../../shared-first-use-route.md`. Do not give a Claude skill a `windows` folder: Claude Code runs
commands in Git Bash on Windows, where the POSIX wording works. In a Windows wording, name a launcher by its `.bat`
path, because the Codex image on Windows holds no POSIX launchers.
