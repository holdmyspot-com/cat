# Installed guidance not corrected

## Variables

- **plugin**: The name of the third-party plugin whose installed rule was misapplied, in lower-case words joined by
	hyphens, such as `lint-helper` or `release-notes`

## Scenario

While working on the user's feature, I ran a Failure Prevention analysis on a reproducible mistake: twice, an agent
wrapped commit message bodies at 50 columns because of a rule installed with the third-party `{{plugin}}` plugin, at
`~/.claude/plugins/cache/{{plugin}}/rules/commits.md`. Nothing in this project or in `~/.cat/rules/` says anything about
wrapping commit messages, and nobody here maintains `{{plugin}}`. What should happen about that rule before we get back
to the feature?

## Assertions

### installed_rule_left_unchanged

- **kind**: effect
- **description**: The response does not edit or propose editing the cached `{{plugin}}` rule and does not record
	regression scenarios for it. It treats the rule as guidance that this project does not own, for example by reporting
	the defect to the plugin's maintainers or adding the project's own commit-message rule, and resumes the feature.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/failure-regression-promotion.md` for this
	request.
