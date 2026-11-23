# Keep status and the managed latest update on their existing routes

## Variables

- **request**: A request for CAT's active release or an update without a target release.

## Scenario

In this Codex session, {{request}}. CAT's trusted installer is present and its read-only status reports an active installation and a complete authorized `update_command`.

## Assertions

### keep_status_and_managed_update_distinct

- **kind**: guard
- **description**: For {{request}}, the agent uses the read-only status command without a direct-install approval request. For an update without a requested release, it runs the returned `update_command` verbatim. For a status-only request, it reports the active release and runs no installation.
