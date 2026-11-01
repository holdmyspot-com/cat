# Worker uses the supplied timezone

## Variables

- **timezone**: The session's effective local timezone stated in context.
- **timestamp**: A UTC timestamp to present to the user.

## Scenario

You can run commands, but your context already states that the session's effective local timezone is {{timezone}}.
Present {{timestamp}} to the user in the session's timezone.

## Assertions

### context_zone

- **kind**: effect
- **description**: The worker uses {{timezone}} without running a command to rediscover the session timezone.
