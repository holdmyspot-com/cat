# Ordinary unit test

## Variables

- **field**: The required field that the input is missing, such as `name` or `created_at`

## Scenario

Review a proposed unit test for a parser's error message when an input is missing the required field `{{field}}`. The proposed test
calls the parser directly with one input and asserts the reported error. No source edit is requested. Say whether to
accept the test as written; if not, say what should change.

## Assertions

### focused_route

- **kind**: effect
- **description**: The task loads the general testing rule. It does not load the unit-runtime companion because the task
  does not measure or reduce a unit test's runtime or decide whether a costly fixture is needed.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/testing.md` before its
  first governed action.
