# Process Command Lines

## Design Goals

- Keep a password that a process's command line holds out of the transcript, the results and every file that an agent
	writes, because a harness can start its sandbox or a helper with a proxy URL that carries one.
- Let an agent identify and stop processes without printing any command line.

## Guidance

This rule applies when you run a command that prints another process's command line, such as `ps -ef`, `ps aux`,
`ps -o args`, `pgrep -a`, or a read of `/proc/<pid>/cmdline`, or the equivalent on another platform. It does not apply
to a command that prints only process IDs and command names.

To find or identify a process, prefer `ps -o pid,comm`, which prints no arguments. When you must read the arguments,
for example to tell two processes with the same command name apart, pass the output through a filter that replaces
`user:password@` in every URL with `***@`, and read only the filtered text:

```sh
ps -eo pid,args | sed -E 's#://[^/@[:space:]]+@#://***@#g'
tr '\0' ' ' < /proc/1234/cmdline | sed -E 's#://[^/@[:space:]]+@#://***@#g'
```

Run the filter in the same command as the listing, so that the unfiltered text never reaches the transcript.
