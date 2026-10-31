---
paths:
  - ".cat/rules/**"
  - "client/**"
---
# Prompt-versus-CLI Loading Index

## Design Goals

- Load the focused conventions needed when assigning work between a prompt and Java CLI code.

## Guidance

When creating or updating a prompt file or a Java CLI that participates in the prompt's workflow, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/prompt-vs-cli.md`.
