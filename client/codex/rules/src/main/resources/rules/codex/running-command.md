# Commands Still Running

## Design Goals

- Make an agent rely on a command's output only after the command has ended, so that it never concludes from a command
	that is still running that a file is empty or that the command printed nothing.
- Make an agent wait for a command that is still running to end, rather than answer from its output so far or abandon
	it.
- Make an agent act at once on a command that has ended, including one that ended with empty output.

## Guidance

`exec_command` waits a limited time for a command, 10 seconds unless you pass a different `yield_time_ms`, and then
returns what the command has printed so far, even when the command has not ended. Even reading a short file can take
that long on a busy machine, so the output that comes back can be empty or cut short.

The result tells you whether the command has ended:

- It has ended when the result has an `exit_code`, which text output shows as `Process exited with code N`. Its output
	is complete.
- It is still running when the result has no `exit_code` and has a `session_id` instead, which text output shows as
	`Process running with session ID N`. Its output so far may be empty or partial, and it says nothing about what a file
	holds or what the command will print.

When an `exec` script calls `tools.exec_command`, the object it returns carries these fields, and printing only its
`output`, as `text(r.output)` does, hides them. Check `exit_code` before you rely on the output, for example by
printing the whole result with `text(JSON.stringify(r))`. `Script completed` at the top of an `exec` result means only
that your script ended, not that the command it started did.

While a command is still running, wait for it to end: call `write_stdin` with its `session_id`, empty `chars` and a
`yield_time_ms` long enough for the command to finish, and repeat until a result has an `exit_code`. Each call returns
only the output printed since the previous call, so join the parts in order. Do not answer from the output so far, and
do not start the same command again instead of waiting. When an `exec` result says `Script running with cell ID N`,
the script itself is still running; call `wait` with that `cell_id` in the same way.

When a result has an `exit_code` and empty output, the command has ended and printed nothing: rely on that without
waiting further.
