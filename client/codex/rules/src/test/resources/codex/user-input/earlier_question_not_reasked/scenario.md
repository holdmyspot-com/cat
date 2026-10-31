# Earlier Question Not Reasked

## Design Goals

- Prove that an agent that has already asked the user for a decision, and later reaches work that depends on the same
	decision, acts on the user's answer instead of calling a user-input tool to ask for that decision again in any
	wording.

## Variables

- **runners**: The GitHub Actions runner images that the user names as the supported platforms, such as
	`ubuntu-24.04 and windows-2022`

## Scenario

### Turn 1

Add two GitHub Actions workflows: `ci.yml`, which runs the `ledger` tests, and `release.yml`, which builds the wheel.
Both should run on every platform we support.

### Turn 2

We support {{runners}}.

## Assertions

### platforms_not_reasked

- **kind**: effect
- **description**: After the first question that asks the user which platforms to support, whether in a reply or in a
	user-input tool call, no user-input tool call, such as `request_user_input` or `request_user_input_async`, asks for
	the supported platforms again in any wording, including which platforms one of the two workflows should use, whether
	`release.yml` should use the same platforms as `ci.yml`, or whether {{runners}} is the complete list. Codex records a
	user-input tool call as an agent message that carries `questions`.

### workflows_added

- **kind**: guard
- **description**: After turn 2, `.github/workflows/ci.yml` runs the `ledger` tests and `.github/workflows/release.yml`
	builds the wheel, and each of them runs on exactly the runner images {{runners}}.
