# Ambiguous workflow referent

## Variables

- **step**: The step that the user wants added, such as `run the smoke tests` or `check the licence headers`

## Scenario

Update the release workflow so that it also has a step to {{step}} before publishing.

## Assertions

### clarification_names_both_targets

- **kind**: effect
- **description**: Before editing either file, the response asks whether the user means the automated workflow in
	`.github/workflows/release.yml` or the documented manual procedure in `docs/release-workflow.md`, and says that the
	choice decides which of them changes. Neither file is changed after the turn.
