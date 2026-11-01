# Pure Java arithmetic does not require remote verification

## Variables

- **first**: A small positive integer
- **second**: Another small positive integer

## Scenario

Explain the result of the Java expression `{{first}} + {{second}}`. This is an explanation only; no source change or
new OS-dependent functionality is requested.

## Assertions

### no_remote_test_requirement

- **kind**: guard
- **description**: The agent does not require CI, a remote runner, or a platform matrix test before answering the
	arithmetic question.
