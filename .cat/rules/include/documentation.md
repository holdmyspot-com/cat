# Documentation Rule Routing

## Design Goals

- Route documentation-specific guidance from the writing task's observable trigger so readers load only the companion
	rule that governs their documentation decision.

When documenting Bash functions, Bats tests, or Bash source code while maintaining CAT, lazy load
`../../../client/common/rules/src/main/resources/rules/include/bash/documentation.md`.

When an explanation or instructional workflow must make an operation, relationship, term, option, or audience flow
precise, lazy load `./documentation/precision.md`.
