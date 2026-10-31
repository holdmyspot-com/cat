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
documentation or a code comment that describes an earlier contract, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/contract-maintenance.md`.

When writing or editing documentation for a function or method, command, configuration entry, or user-facing workflow,
or writing or changing Bash source code, including a function, a Bats test, or a Bats file, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/documentation.md`.

Before constructing or running a Maven command in this project, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/build.md`, then `${CAT_PROJECT_ROOT}/.cat/rules/include/maven.md`.

When writing or changing a file under `client/common/` or `.cat/rules/common/`, adding or changing functionality for
one harness, designing common or harness-specific modules, or deciding whether code should be shared, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/architecture.md`.

When applying a rule, convention, policy, migration, or systematic correction across existing artifacts, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/rule-coverage.md`.

When a proposed prevention, rule revision, or prompt-file change itself creates or preserves the wrong prevention for a
reported failure, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/mistake-learning.md`. Analyze the reported
operational failure first through the bundled Failure Prevention rule.

When deciding whether guidance that a confirmed failure involves is user-owned, treat CAT plugin source in this
repository as user-owned guidance, because its user is that plugin's developer.

When creating or revising a Git commit message, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/commit-messages.md`.

When a CAT workflow creates, retains, hands off, or cleans up uncommitted state or evidence, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/workflow-temporary-resources.md`.

When defining or changing a skill's frontmatter `description`, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/skill-routing.md`.

When defining, writing, parsing, reading, or processing JSON, JSONL, or YAML that this project owns, including Markdown
frontmatter, writing a key-value pair in project-owned Markdown, naming a regression scenario or its assertions, or
handling independently handleable unbounded record or binary input, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/data.md`.

When writing, parsing, or displaying a date or time, including in a name or identifier, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/dates-and-times.md`.

When changing a plugin installer that migrates persisted plugin data or a runtime assumption about that data, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/plugin-data-migration.md`.

Before rebuilding, reinstalling, refreshing, publishing, or validating a plugin distribution or cache; before editing a
file in an installed plugin cache or using a changed shipped command, hook, prompt, or configuration; or when a task
needs a CAT helper CLI, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/plugin-artifacts.md`.

When writing a path reference into a file that ships in a plugin artifact, such as plugin code, a skill, a hook, a
launcher, or a packaged rule, or changing which files distribution assembly packages, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/plugin-artifacts/packaged-content.md`.

When changing a plugin-facing skill's description, or what a hook, launcher, or command returns to its harness,
including its output, response, or exit code, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/plugin-artifacts/harness-contracts.md`.

When writing code, a command, a hook, or a skill or rule instruction that passes an argument to a CAT launcher, lazy
load `${CAT_PROJECT_ROOT}/.cat/rules/include/jlink-launchers/launcher-arguments.md`.

When evaluating or adopting a newly available Codex model, or changing CAT's Codex supported-model configuration or
Codex-facing prompts or rules, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/codex/model-guidance-review.md`.
