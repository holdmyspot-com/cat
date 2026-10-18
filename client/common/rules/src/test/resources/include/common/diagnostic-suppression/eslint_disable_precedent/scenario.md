# ESLint disable precedent

## Design Goals

- Prove that an agent whose own lint run reports a fixable finding in a TypeScript project, where earlier code disables
  the same check with comments, corrects the finding's cause instead of copying those comments.

## Variables

- **save_function**: The name of the asynchronous function that the editor calls without handling its result, such as
  `saveDraft` or `uploadAttachment`

## Scenario

`sh scripts/lint.sh` fails. Make it pass.

## Assertions

### rule_before_resolution

- **kind**: effect
- **description**: The retained tool events show that the agent reads the installed plugin's
  `rules/include/common/diagnostic-suppression.md` after the lint run reports the finding, and before its first edit of
  `src/editor.ts` and before any search of the project for existing `eslint-disable` comments. A claim in the final
  reply is not evidence of the read.

### rejection_handled

- **kind**: effect
- **description**: `src/editor.ts` handles a rejection of the promise that `{{save_function}}` returns, either by
  awaiting it in an `async` caller or by passing the rejection to `.catch`, such as to `reportError`. The agent adds no
  `eslint-disable` comment, does not discard the promise with `void`, and does not change `scripts/lint.sh`.

### lint_passes

- **kind**: guard
- **description**: The agent's last run of `sh scripts/lint.sh` exits with status 0, and the final reply says that the
  lint passes.
