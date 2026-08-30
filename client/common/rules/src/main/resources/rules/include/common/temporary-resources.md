# Temporary Resources

## Design Goals

- Ensure temporary files and directories have one bounded owner and are removed when their final owner is done.
- Keep CAT-controlled workflow state isolated under CAT_WORK instead of a shared system temporary location.
- Keep every test method self-contained and safe to run alongside other test methods.

## Guidance

Any code, test, script, or workflow that creates a temporary file or directory owns its cleanup. Create the resource
directly at its point of use and make its cleanup owner visible beside that creation. Register cleanup immediately so it
runs when later setup, normal work, validation, or error handling fails. Use the language's automatic resource
management when available; otherwise use a bounded cleanup block or equivalent reliable cleanup path. Recursively remove
only the root created by that operation.

For CAT-controlled workflow state, derive CAT_WORK through the active harness scope. Create invocation-local temporary
files beneath `${CAT_WORK}/temp`; retain session data that outlives one invocation in a named directory directly beneath
`${CAT_WORK}`. This includes runner homes, workspaces, staged inputs or outputs, diagnostic records, and native scratch
space configured for a nested harness. Do not use `/tmp`, the operating system's default temporary directory, or another
shared location for that state: those locations break CAT's isolation, cleanup, and retained-evidence ownership. Pass a
fresh `${CAT_WORK}/temp` child to a nested native process through its supported temporary-directory setting before it
starts, and grant write authority only to that child. A test-only fixture that is not CAT workflow state may use the
test framework's isolated temporary directory, provided its test owns and cleans it up. Classify by the resource's
consumer, not by the source file that creates it: a unit-test fixture may use the test framework's directory, while a
test that starts a CAT runner, evaluator, nested harness, or other CAT workflow must give that workflow a fresh
`${CAT_WORK}/temp` child.

Each test must create, use, and clean up its own fixtures within that test. Tests may run in parallel, so they may
access only test-local state or state that is safe for concurrent use; they must not share mutable fixtures.

Do not rely on process exit, operating-system cleanup, a successful run, or an unspecified later operation to remove
temporary state. A temporary resource may outlive its creator only through an explicit handoff that identifies the next
owner and the point at which that owner removes it. Retain a resumable session's CAT work data until it no longer needs
the data or the session is deleted and cannot resume. Until that handoff completes, the creator remains responsible.
