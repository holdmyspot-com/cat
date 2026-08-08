# Bash Documentation and Source Code

## Design Goals

- Make Bash functions and Bats tests understandable to their intended readers without inspecting their implementation.
- Keep Bash behavior shared by several entry scripts in one documented library while each entry retains only its own
  adaptation at the execution boundary.

## Bash Function Documentation

Document every Bash function, including internal helpers, with a comment block immediately above its definition. Use one
concise responsibility sentence, then only the applicable structured sections — `Arguments:`, `Environment:`, `Output:`,
`Side effects:`, `Errors:`, and `Exit status:`. Describe positional arguments and environment values by semantic role,
constraint, operational effect, and user-visible purpose, not merely `$1`, `$2`, or a shell variable name. `Output:`
describes data written to standard output; `Exit status:` describes the numeric result. Use ordinary `# ` comment lines.
A blank `#` line may separate the description and distinct structured sections, but do not add empty `#` wrapper lines
before or after the block.

## Bats Test Names and Documentation

Treat each `@test "..."` declaration as a function name for human readers. Write its title as a plain-language
description of the actor, relevant condition, and observable outcome; do not rely on assertion syntax, test-framework
terminology, variable names, or project shorthand to explain what behavior is covered. Add a comment block immediately
before the test only when the title alone cannot make its setup, risk, boundary, or distinct expectation clear. The
title and any preceding comment must read as one non-redundant explanation for a reader unfamiliar with the test body:
the comment supplies the missing context, while the title states the behavior under test.

At the top of every Bats file, after its shebang and before imports or setup, add one concise comment that states the
kinds of behavior the file tests. Treat this as the Bats equivalent of a Java test class's class-level description: it
lets a reader decide whether the file is relevant before reading individual test cases. Name the actor or boundary and
the covered behavior categories, rather than repeating a filename or a particular test title.

## Bash Source Code

Treat Bash scripts as maintained source code: follow the project's `.editorconfig` when it provides one, document their
functions, and test their observable boundaries. When identical behavior serves more than one entry script, centralize
it in a documented Bash library. An entry script owns only its invocation-specific commands, configuration, paths,
environment variables, argument adaptation, and calls to that library. Pass entry-specific values to library functions;
do not make shared library behavior depend on an entry script's layout or invocation contract.

Derive a sourced library's path from the entry script's own directory, not the caller's working directory or ambient
shell search paths, and fail clearly when it is unavailable. The small bootstrap that locates and sources that library
may remain in each entry script. Do not duplicate a library function in an entry script: extend the library when its
contract is shared, and keep code local only when its contract is genuinely entry-specific. Update all callers and
boundary tests when a shared library's contract changes.

Before completing Bash changes, verify that duplicated behavior is centralized or justified as entry-specific, every
function is documented, and the relevant entry scripts exercise both successful sourcing and a missing-library failure.
