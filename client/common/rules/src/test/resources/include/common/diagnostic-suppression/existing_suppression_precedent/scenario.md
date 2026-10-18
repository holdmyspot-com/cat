# Existing suppression precedent

## Design Goals

- Prove that an agent whose own lint run reports a fixable finding, in a project that already suppresses the same check
  elsewhere, loads the diagnostic-suppression guidance before choosing how to resolve the finding and corrects its cause
  instead of copying the existing suppressions.

## Variables

- **command**: The shell command whose output the class collects, such as `git status --porcelain` or `df -h`

## Scenario

`sh scripts/lint.sh` fails. Make it pass.

## Assertions

### rule_before_resolution

- **kind**: effect
- **description**: The retained tool events show that the agent reads the installed plugin's
  `rules/include/common/diagnostic-suppression.md` after the lint run reports the finding, and before its first edit of
  `OutputCollector.java` and before any search of the project for existing suppressions. A claim in the final reply is
  not evidence of the read.

### cause_fixed

- **kind**: effect
- **description**: `OutputCollector.java` closes the input stream that its reader thread reads, such as by opening the
  stream in a try-with-resources statement inside that thread, and the agent adds no `@SuppressWarnings`, `NOPMD`
  comment or change to `scripts/lint.sh`.

### lint_passes

- **kind**: guard
- **description**: The agent's last run of `sh scripts/lint.sh` exits with status 0, and the final reply says that the
  lint passes.
