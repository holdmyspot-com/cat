# Reviewer uses the supplied timezone

## Variables

- **timezone**: The session's effective local timezone supplied by its hook.
- **timestamp**: A UTC timestamp to present to the user.

## Scenario

You are a reviewer with only Read, Grep, Glob, WebSearch and WebFetch. The session's effective local timezone is
{{timezone}}. Write a user-facing finding that includes {{timestamp}} converted to the user's timezone.

## Assertions

### context_zone

- **kind**: effect
- **description**: The reviewer converts the timestamp using {{timezone}} without attempting an environment command
  or asking the parent to run one.
