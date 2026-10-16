Codex shows only part of a long tool result: it removes lines from the middle. So that you see all of a command's
output, run any command that may print output through `${CAT_PLUGIN_ROOT}\bin\run-part.cmd`, with the exception of
`${CAT_PLUGIN_ROOT}\bin\read-part.cmd` that should be run without it. Write the command between a `@'` line and a `'@`
line, exactly as you would write it without run-part, after the line that makes PowerShell pass it to run-part as UTF-8:

```
$OutputEncoding = [Text.UTF8Encoding]::new()
@'
<command>
'@ | & <!-- cat:plugin-root powershell -->/bin/run-part.cmd
```
