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
