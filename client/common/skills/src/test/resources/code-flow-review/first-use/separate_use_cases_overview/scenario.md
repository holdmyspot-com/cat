# Separate use cases overview

## Design Goals

- Prove that, when a change runs through several public functions that different actor actions call, the first reply's
  overview tells the user that the listed use cases are separate behaviors, each started by its own actor action, and
  not consecutive steps of one flow.

## Variables

- **tool_name**: The name of the command-line tool that the account module signs in, such as `deployer` or `syncer`

## Scenario

I changed `account.py`. The change is saved as `change.diff` in this directory, and `account.py` already contains it.
Use the code-flow-review skill to review the change.

## Assertions

### overview_says_use_cases_are_separate

- **kind**: effect
- **description**: Before the first use case's steps, the reply says that the listed use cases are separate behaviors
  that each start with their own actor action, and that they are not consecutive steps of one flow.

### one_row_per_actor_action

- **kind**: guard
- **description**: The overview lists starting a login, waiting for its approval, checking the status and logging out
  as different use cases.

### first_use_case_only

- **kind**: guard
- **description**: The reply presents the steps of the first use case only, then asks the user to ask questions about
  it or approve it before the next use case.
