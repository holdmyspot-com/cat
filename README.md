# Reliable agentic coding for professional cat herders

> [!WARNING]
> **CAT v1.0 is under internal development and is not ready for public use.** Its features, rules, and installation
> may change without notice.

A coding agent can follow a rule in one file and miss it in the next. It can complete only part of a refactor, or apply
a project convention in one area and overlook it somewhere else.

Your rules will change as the team learns. When a review uncovers a gap, add the lesson to the project rules and CAT
brings it into later sessions. CAT also guides agents to explain their work clearly and avoid unnecessary jargon, making
their changes easier to understand in review.

<p align="center">
	<img src="docs/problem.png" alt="A request for a small change becoming an unrelated AI rewrite" width="600"/>
</p>

## Why CAT

- **Proven compliance.** CAT’s rules are certified to achieve at least 95% compliance, with 95% confidence.
- **Apply rules more consistently across a task.** Give the agent the same guidance as it moves between files and
	sessions, so it is less likely to handle a convention in one place and miss it in another.
- **Use rules tested for reliability.** CAT certifies an optimized rule only after repeated tests support, with 95%
	confidence, that agents follow it at least 95% of the time in the cases tested.
- **Keep rules with the codebase.** Store shared guidance in the repository, where the team can change it alongside the
	code it affects.
- **Make agent changes easier to review.** Clear, plain-language explanations help reviewers understand what changed
	and spot gaps in the current rules.
- **Improve the rules as you work.** Reviews uncover new cases. Add the lesson as a project rule, and CAT can supply it
	in later sessions.
- **Use the coding harness you prefer.** CAT supports both Claude Code and Codex while keeping project guidance in one
	place.
- **Install with confidence.** CAT provides native distributions for supported Linux and macOS platforms, including
	checksum-verified installation and an uninstall path.

## How CAT checks its rules

CAT tests and certifies its optimized rules against agent tasks. A rule is certified only when repeated tests support,
with 95% confidence, that agents follow it at least 95% of the time in the cases tested.

Your team’s own rules stay in the repository, where you can improve them through normal review.

## Try it on one project

**CAT is free for non-commercial use.** Try it on the project you work on today. When the same workflow needs to support
a team, see Pricing (docs/PRICING.md) for commercial plans and features.

## Get Started

### 1. Install or update CAT

Ask your agent in Claude Code or Codex: "Install CAT from https://github.com/holdmyspot-com/cat/releases". Each
release's notes give the agent the command for that release, and CAT's `update` skill updates CAT later.

Or run the single command yourself. Inside a Claude Code or Codex session it detects which harness runs it; in Claude
Code, type it at the prompt prefixed with `! `. It installs the latest release, or updates an existing installation to
it:

```bash
curl -fsSL https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/common/lifecycle/src/main/resources/install/cat.sh | sh
```

- To install a particular release, end the command with `| sh -s -- v1.0` instead of `| sh`.
- In a terminal, outside a session, name the harness: end the command with `| sh -s -- --harness claude` or
	`| sh -s -- --harness codex`. Without it, the command exits without changing anything and asks for it.
- When the command reports that it cannot write a path, it has changed nothing. An agent's sandbox usually causes this:
	run the command that it prints in a terminal, or in Claude Code at the prompt prefixed with `! `.
- Codex on Windows installs from PowerShell; see [Install CAT](docs/installation.md).

The per-harness commands below do the same for one harness, and also accept a release tag after `| sh -s`:

<details>
<summary>Claude Code</summary>

```bash
curl -fsSL \
	https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/claude/lifecycle/src/main/resources/install/bootstrap-claude.sh | sh
```

To install into a custom Claude Code configuration directory, set `CLAUDE_CONFIG_DIR`:

```bash
curl -fsSL \
	https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/claude/lifecycle/src/main/resources/install/bootstrap-claude.sh \
	| CLAUDE_CONFIG_DIR=/path/to/claude-home sh
```

</details>

<details>
<summary>Codex</summary>

```bash
curl -fsSL \
	https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.sh | sh
```

To install into a custom Codex home directory, set `CODEX_HOME`:

```bash
curl -fsSL \
	https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/resources/install/bootstrap-codex.sh \
	| CODEX_HOME=/path/to/codex-home sh
```

</details>

### 2. Start or resume a session

Start or resume a session. CAT then injects its default rules and your project's applicable rules.

### Uninstall

Ask your agent to uninstall CAT, which runs CAT's `uninstall` skill, or run the single command with `--uninstall`:

```bash
curl -fsSL https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/common/lifecycle/src/main/resources/install/cat.sh | sh -s -- --uninstall
```

In Codex, CAT keeps its data, so that a later installation can migrate it; end the command with
`| sh -s -- --uninstall --delete-data` to delete it too.

## Learn More

- [Install, supported platforms, and uninstall](docs/installation.md)
- [Add project rules and separate common from harness-specific guidance](docs/project-rules.md)
- [CAT's bundled default rules](docs/default-rules.md)
- [Build and test CAT](docs/contributing.md)
- [Pricing](docs/PRICING.md)
- [License](LICENSE.md)
