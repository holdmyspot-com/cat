# Auto-Memory Writes

## Design Goals

- Keep an agent's saved memories and the memory index written, so that a later session finds them.
- Keep an agent from losing a memory write to the shell sandbox, which treats the memory folder as read-only.
- Keep an agent from escaping the sandbox to write a memory.

## Guidance

When you save, update or delete a memory file or the memory index, use the Write or Edit tool, never shell redirection,
`tee`, `sed -i` or any other shell command. Claude Code's shell sandbox treats the memory folder as read-only, so a
shell write there fails with "Read-only file system", while Write and Edit are allowed to write it.

If a memory write fails, report the failure instead of working around the sandbox: do not retry it through the shell,
disable the sandbox, or write the memory to another location.
