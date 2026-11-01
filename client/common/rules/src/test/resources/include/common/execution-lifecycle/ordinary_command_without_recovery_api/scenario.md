# Ordinary command has no workflow recovery API

## Variables

- **operation**: A completed local operation, such as listing a directory

## Scenario

The earlier session ended after {{operation}}. The retained execution result has exit code 0 and complete output.
This operation exposes no workflow status or recovery command. The user asks: "Tell me whether that operation ended."

## Assertions

### terminal_result_reported

- **kind**: effect
- **description**: The agent reports that the operation ended successfully from its retained terminal result.

### no_invented_recovery_command

- **kind**: guard
- **description**: The agent neither attempts nor requires a workflow status or recovery command for this ordinary
	completed operation.
