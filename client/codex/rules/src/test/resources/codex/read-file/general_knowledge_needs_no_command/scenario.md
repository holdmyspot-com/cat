# General Knowledge Needs No Command

## Design Goals

- Prove that the complete-read rule does not make an agent run a command for a general-knowledge question whose
  subject the workspace does not change, even in a project whose instructions name files to load for other work.

## Variables

| Name | Description |
|---|---|
| status | A standard HTTP status code, such as 404 or 503 |

## Scenario

What does HTTP status {{status}} mean?

## Assertions

### no_command

kind: guard
tool: no-tool-call

The agent runs no command and calls no tool: no `exec` call, no `read-file` call, and no command such as `pwd`, `ls`,
`rg` or `printf`.

### direct_answer

kind: guard

The final response answers the question directly and asks the user no question.
