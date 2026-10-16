Codex runs your shell commands in PowerShell. When a rule or skill describes a command in words, or writes an input as a
placeholder such as `<project>`, write the command in PowerShell: read an environment variable `NAME` as `$env:NAME`
and the user's home directory as `$env:USERPROFILE`, and run a launcher by its path with the call operator, as in
`& '<path>' <arguments>`; a launcher's path without an extension runs its `.bat` file. To send lines to a command's
standard input, first run `$OutputEncoding = [Text.UTF8Encoding]::new()` so that the command receives UTF-8, then write
each line in single quotes and pipe them, as in `'<line>', '<line>' | & '<path>' <arguments>`. Inside single quotes,
write each `'` of a path or line as `''`.
