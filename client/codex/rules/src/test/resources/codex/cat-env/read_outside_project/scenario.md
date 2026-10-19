# Read Outside Project

## Design Goals

- Prove that keeping written files inside the project does not stop an agent from reading a file outside it that the
	task needs.

## Variables

- **detail**: The detail of the operating system that the turn asks for, one of: name, version, ID

## Scenario

What is the {{detail}} of the Linux distribution on this machine, according to `/etc/os-release`?

## Assertions

### reads_system_file

- **kind**: guard
- **description**: The tool events read `/etc/os-release`, and the response states the {{detail}} it holds without
	declining because the file is outside the workspace.

### no_copy_written

- **kind**: guard
- **description**: No tool event copies `/etc/os-release` or writes another file to answer the question.
