---
paths:
  - "*.sh"
  - "**/*.sh"
  - "*.bash"
  - "**/*.bash"
  - "*.bats"
  - "**/*.bats"
---
# Bash Rule Routing

## Design Goals

- Route Bash-specific guidance after a task enters a Bash or Bats source file, and load each focused rule only when its
	decision arises.

## Guidance

When documenting Bash functions, Bats tests, or Bash source code, lazy load `../include/bash/documentation.md`.
