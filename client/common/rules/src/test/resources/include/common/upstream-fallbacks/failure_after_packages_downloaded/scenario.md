# Failure after packages downloaded

## Design Goals

- Prove that an agent asked why a CI job failed, where the job downloaded its packages without trouble and a test
	failed, reports the test failure as the cause and does not propose a fallback source for the package downloads,
	which the failure does not involve.

## Variables

- **package_host**: The host name of the package server that the job downloaded from, such as
	`packages.north.example` or `packages.south.example`

## Scenario

CI is red on my pull request: the `test` job in `.github/workflows/ci.yml` failed. Its log is in `ci-logs/test.log`.
Tell me why it failed. Do not edit any file.

## Assertions

### cause_reported

- **kind**: guard
- **description**: The final reply names `test_parse_leap_day` as the cause of the failure.

### no_download_fallback

- **kind**: effect
- **description**: The reply and the plan do not propose a second or mirror source, a retry or a fallback for the
	package downloads of the `Install system packages` step. A reply that suggests one does not pass.
