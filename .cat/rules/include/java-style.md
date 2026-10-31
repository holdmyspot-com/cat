## Java Style

## Design Goals

- Keep Java code readable, consistent, and stable under routine maintenance.

## Guidance

Let Checkstyle and PMD enforce mechanical formatting. Apply these conventions when their intent requires judgment.

### Layout and Names

- Follow `.editorconfig` for shared whitespace settings. When manually wrapping Java, follow the project's existing
	surrounding style.
- Source and configuration that CAT fully generates must follow the same applicable conventions as checked-in artifacts
	of that type, including `.editorconfig` whitespace settings. Verify generated text whose formatting is semantically or
	operationally relevant with an exact-output test. This does not require reformatting an artifact owned by an external
	generator that CAT only patches.
- Use smart tabs; one tab represents two spaces. Do not align code with spaces.
- Use Allman braces. Put `else`, `catch`, `finally`, and `do`/`while` continuations on a new line after the closing
	brace.
- Separate distinct method phases with blank lines: validation, normalization/setup, primary branching, and final
	failure/return handling. Do not add blank lines within one cohesive phase.
- Treat `stdout` and `stderr` as standard terminology for a process's standard output and standard error. Prefer those
	terms in identifiers, comments, and documentation over expanded names such as `standardErrorOutput`; retain an
	expanded spelling only when an external API or established public type requires it.

### Size and Simplicity

Keep methods under about 80 lines and classes under about 2,000 lines when practical. Extract a coherent responsibility,
not a mechanical fragment. Prefer simple, direct code over abstractions created only for hypothetical reuse.
