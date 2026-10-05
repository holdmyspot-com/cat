# General Knowledge Needs No Command

## Design Goals

- Prove that the complete-read rule does not make an agent run a command for a general-knowledge question whose
  subject the workspace does not change, even in a project whose instructions name files to load for other work.

## Context

Use the [lazy-load project fixture](../lazy-project/).

## Scenario

This is an application test. What does HTTP status 404 mean?

## Assertions

### no-command

The retained events contain no tool call: no `exec` call, no `read-file` call, and no command such as `pwd`, `ls`, `rg`
or `printf`.

### direct-answer

The final response says that the server could not find the requested resource, and asks the user no question.
