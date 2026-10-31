---
agents: ["main", "subagents"]
---
# Project Rule Routing

## Design Goals

- Route each project-specific reader decision directly to its authoritative rule before the reader takes the governed
	action.
- Keep project routes narrow enough that unrelated work does not receive policy it cannot apply.

## Guidance

When changing an API, configuration entry, file format, data structure, command, or documented procedure, or writing
documentation or a code comment that describes an earlier contract, lazy load `../include/contract-maintenance.md`.

When writing or editing documentation for a function or method, command, configuration entry, or user-facing workflow,
or writing or changing Bash source code, including a function, a Bats test, or a Bats file, lazy load
`../include/documentation.md`.

Before constructing or running a Maven command in this project, lazy load `../include/build.md`, then
`../include/maven.md`.

When writing or changing a file under `client/common/` or `.cat/rules/common/`, adding or changing functionality for
one harness, designing common or harness-specific modules, or deciding whether code should be shared, lazy load
`../include/architecture.md`.

When applying a rule, convention, policy, migration, or systematic correction across existing artifacts, lazy load
`../include/rule-coverage.md`.

When a proposed prevention, rule revision, or prompt-file change itself creates or preserves the wrong prevention for a
reported failure, lazy load `../include/mistake-learning.md`. Analyze the reported operational failure first through the
bundled Failure Prevention rule.

When deciding whether guidance that a confirmed failure involves is user-owned, treat CAT plugin source in this
repository as user-owned guidance, because its user is that plugin's developer.

When creating or revising a Git commit message, lazy load `../include/commit-messages.md`.

When a CAT workflow creates, retains, hands off, or cleans up uncommitted state or evidence, lazy load
`../include/workflow-temporary-resources.md`.

When defining or changing a skill's frontmatter `description`, lazy load `../include/skill-routing.md`.

When defining, writing, parsing, reading, or processing JSON, JSONL, or YAML that this project owns, including Markdown
frontmatter, writing a key-value pair in project-owned Markdown, naming a regression scenario or its assertions, or
handling independently handleable unbounded record or binary input, lazy load `../include/data.md`.

When writing, parsing, or displaying a date or time, including in a name or identifier, lazy load
`../include/dates-and-times.md`.

When changing a plugin installer that migrates persisted plugin data or a runtime assumption about that data, lazy load
`../include/plugin-data-migration.md`.

Before rebuilding, reinstalling, refreshing, or validating a plugin distribution or cache, or when a task needs a CAT
helper CLI, lazy load `plugin-artifacts.md`.

When writing code, a command, a hook, or a skill or rule instruction that passes an argument to a CAT launcher, lazy
load `../include/jlink-launchers/launcher-arguments.md`.

When evaluating or adopting a newly available Codex model, or changing CAT's Codex supported-model configuration or
Codex-facing prompts or rules, lazy load `../codex/model-guidance-review.md`.
