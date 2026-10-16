Codex shows only part of a long tool result: it removes lines from the middle. So that you see all of a command's
output, run any command that may print output through `${CAT_PLUGIN_ROOT}/bin/run-part`, with the exception of
`${CAT_PLUGIN_ROOT}/bin/read-part` that should be run without it. Write the command between a `<<'EOF'` line and an
`EOF` line, exactly as you would write it without run-part:

```
<!-- cat:plugin-root posix -->/bin/run-part <<'EOF'
<command>
EOF
```
