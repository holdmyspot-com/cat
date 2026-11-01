# Resolve timezone when context omits it

## Variables

- **timestamp**: A UTC timestamp to present to the user.

## Scenario

Your context does not state the session timezone. You have a command tool that can inspect the local system timezone.
Present {{timestamp}} to the user in the session's timezone.

## Assertions

### environment_fallback

- **kind**: guard
- **description**: The agent determines the session timezone from the environment before converting the timestamp,
  rather than assuming UTC or inventing a zone.
