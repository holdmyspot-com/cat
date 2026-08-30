# CAT Skill Environment

## Design Goals

- Let an ordinary Claude Code prompt locate CAT's installed files and its project-owned work root without relying
  on hook-only environment variables or state left by an earlier shell command.
- Keep both values derived from the native Claude Code environment and the CAT CLI that owns project-root discovery.

## Guidance

For an ordinary Claude Code agent tool shell, `CAT_PLUGIN_ROOT` means
`${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/plugins/cat`. Derive that value from native `CLAUDE_CONFIG_DIR` whenever a
prompt needs a file shipped in CAT's installed plugin, including a launcher, rule, skill, or configuration file. Use
paths below it as `${CAT_PLUGIN_ROOT}/…`.

For an ordinary Claude Code agent tool shell, `CAT_WORK` means the absolute `.cat/work` directory below the current CAT
project root. In the same shell action that needs it, derive it with `${CAT_PLUGIN_ROOT}/client/bin/workflow-temp root`.
The command finds the project root and prints the resulting absolute path; do not reconstruct that path from the current
directory. Create invocation-local temporary resources below `${CAT_WORK}/temp`; retain resumable session data directly
below `${CAT_WORK}` until its session no longer needs it or is deleted permanently.

Do not rely on a prior command having exported either value. In the shell command that expands `CAT_PLUGIN_ROOT` or
`CAT_WORK`, first derive `CAT_PLUGIN_ROOT` from `CLAUDE_CONFIG_DIR`, then derive `CAT_WORK` with `workflow-temp root`,
and then invoke the required launcher.

Do not let a locally defined `CAT_WORK` override that derived value. If it is defined and differs, stop and report both
values; if it matches, continue using the derived value. The launcher computes the work root itself, so exporting
`CAT_WORK` cannot change where it stores data.

Use this check in the same shell action that needs the value:

```sh
derived_cat_plugin_root="${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/plugins/cat"
if [ -n "${CAT_PLUGIN_ROOT+x}" ] && [ "$CAT_PLUGIN_ROOT" != "$derived_cat_plugin_root" ]; then
  printf '%s\n' \
    "CAT_PLUGIN_ROOT conflicts with the Claude-derived plugin root: $CAT_PLUGIN_ROOT != $derived_cat_plugin_root" >&2
  exit 1
fi
CAT_PLUGIN_ROOT="$derived_cat_plugin_root"
derived_cat_work="$("$CAT_PLUGIN_ROOT/client/bin/workflow-temp" root)"
if [ -n "${CAT_WORK+x}" ] && [ "$CAT_WORK" != "$derived_cat_work" ]; then
  printf '%s\n' "CAT_WORK conflicts with the harness-derived work root: $CAT_WORK != $derived_cat_work" >&2
  exit 1
fi
CAT_WORK="$derived_cat_work"
```

Do not use `CLAUDE_PLUGIN_ROOT`: Claude Code defines it for hook invocations, not ordinary agent tool shells. Do not let
a locally defined `CAT_PLUGIN_ROOT` override the derived value. If it is defined and differs, stop and report both
values; if it matches, continue using the derived value.
