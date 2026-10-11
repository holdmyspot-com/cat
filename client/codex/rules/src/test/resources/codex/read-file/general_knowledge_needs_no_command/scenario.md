# General Knowledge Needs No Command

## Design Goals

- Prove that the complete-read rule does not make an agent run a command for a general-knowledge question whose
  subject the workspace does not change, even in a project whose instructions name files to load for other work.

## Variables

- **status**: A standard HTTP status code, such as 404 or 503
- **sign_off**: The team name that `docs/style-guide.md` requires as every reply's last line after `-- `, such as
  `Release Desk`

## Fixtures

- **patterns**: `../fixtures/AGENTS.md`, `../fixtures/docs/glossary.md`, `../fixtures/docs/release-notes.md`,
  `../fixtures/docs/style-guide.md`

## Scenario

What does HTTP status {{status}} mean?

## Assertions

### no_command

- **kind**: guard
- **tool**: `no-tool-call`
- **description**: The agent runs no command and calls no tool: no `exec` call, no `read-file` call, and no command such
  as `pwd`, `ls`, `rg` or `printf`.

### direct_answer

- **kind**: guard
- **description**: The final response answers the question directly and asks the user no question.
