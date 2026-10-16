Codex runs your shell commands in a POSIX shell. When a rule or skill describes a command in words, or writes an input
as a placeholder such as `<project>`, write the command in that shell: read an environment variable `NAME` as
`"$NAME"` and the user's home directory as `"$HOME"`, and run a launcher by its path, as in `'<path>' <arguments>`. To
send lines to a command's standard input, write each line in single quotes and pipe them from `printf`, as in
`printf '%s\n' '<line>' '<line>' | '<path>' <arguments>`. Inside single quotes, write each `'` of a path or line as
`'\''`.
