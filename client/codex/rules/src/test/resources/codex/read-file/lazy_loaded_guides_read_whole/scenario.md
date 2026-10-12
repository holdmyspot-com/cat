# Lazy-Loaded Guides Read Whole

## Design Goals

- Prove that each file that the project's instructions tell the agent to lazy load reaches the agent whole, even when
  the file exceeds Codex's output limit.
- Prove that reading the guides whole leaves the reply correct: it names what the asked release changed, which the
  release notes that the turn names state in a few lines.

## Variables

- **release**: The release the customer asks about, between 4.1 and 4.320; release 4.173 alone also requires callers to
  change their code
- **improved**: The component that the asked release improved, as `docs/release-notes.md` names it
- **corrected**: The component whose message the asked release corrected, as `docs/release-notes.md` names it
- **sign_off**: The team name that `docs/style-guide.md` requires as every reply's last line after `-- `, such as
  `Release Desk`

## Fixtures

- **patterns**: `../fixtures/AGENTS.md`, `../fixtures/docs/glossary.md`, `../fixtures/docs/release-notes.md`,
  `../fixtures/docs/style-guide.md`

## Scenario

A customer asks: "Does release {{release}} change anything for me?" Draft the reply from `docs/release-notes.md`. Do
not edit any file.

## Assertions

### style_guide_read_whole

- **kind**: effect
- **tool**: `read-in-full docs/style-guide.md`
- **description**: Every line of `docs/style-guide.md` reaches the agent through its tool output.

### glossary_read_whole

- **kind**: effect
- **tool**: `read-in-full docs/glossary.md`
- **description**: Every line of `docs/glossary.md` reaches the agent through its tool output.

### changes_named

- **kind**: guard
- **tool**: `pattern "(?is)(?=.*\b{{improved}}\b)(?=.*\b{{corrected}}\b)"`
- **on_tool_failure**: judge
- **description**: The drafted reply tells the customer that release {{release}} improved the {{improved}} and corrected
  a message in the {{corrected}}.
