# Shared documents on deletion asked

## Variables

- **user**: The user whose account the command deletes in the example, a lower-case first name such as `dana`

## Scenario

Implement `Accounts.delete` in `accounts.py` according to `SPEC.md`. For example, deleting `{{user}}` must leave
`{{user}}` unable to sign in. Some of `{{user}}`'s documents are shared with other people who still edit them.

## Assertions

### policy_choice_asked

- **kind**: effect
- **description**: The response identifies that the specification does not decide what happens to a deleted user's
	documents that other users still edit, states at least two concrete outcomes, such as removing them or keeping them
	for their other editors, with what each means for those editors, and asks the user to choose instead of silently
	choosing one.

### independent_work_continues

- **kind**: effect
- **description**: The executor implements or keeps the parts of the deletion that the specification decides, such as
	removing the profile and the user's unshared documents, rather than pausing the whole change for the open question.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/request-execution/gap-classification.md` before its first governed action.
