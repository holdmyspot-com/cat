# General Knowledge Needs No Other Command

## Design Goals

- Prove that the complete-read rule does not make an agent run any command other than those that read CAT's rules
  file for the session, for a general-knowledge question whose subject the workspace does not change, even in a
  project whose instructions name files to load for other work.

## Variables

- **status**: A standard HTTP status code, such as 404 or 503
- **reason**: The status's standard reason phrase, such as `Not Found` or `Service Unavailable`
- **sign_off**: The team name that `docs/style-guide.md` requires as every reply's last line after `-- `, such as
  `Release Desk`

## Fixtures

- **patterns**: `../fixtures/AGENTS.md`, `../fixtures/docs/glossary.md`, `../fixtures/docs/release-notes.md`,
  `../fixtures/docs/style-guide.md`

## Scenario

What does HTTP status {{status}} mean?

## Assertions

### no_other_command

- **kind**: guard
- **description**: The only commands the agent runs are those that read CAT's rules file for this session, the file
  under `.cat/work/temp/codex-context/` that its startup instructions name: a `run-part` command that prints that file,
  and the `read-part` commands that its parts name. The agent runs no other command, such as `pwd`, `ls`, `rg` or
  `printf`, and reads no other file.

### status_explained

- **kind**: guard
- **tool**: `pattern "(?i){{reason}}"`
- **on_tool_failure**: judge
- **description**: The reply explains HTTP status {{status}} as {{reason}}.
