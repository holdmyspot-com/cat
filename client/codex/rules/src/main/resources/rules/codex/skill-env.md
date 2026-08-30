# CAT Skill Environment

## Design Goals

- Let an ordinary Codex prompt locate CAT's installed files and its project-owned work root without relying on
  hook-only environment variables or state left by an earlier shell command.
- Keep both values derived from the native Codex environment and the CAT CLI that owns project-root discovery.

## Guidance

For an ordinary Codex agent tool shell, `CAT_PLUGIN_ROOT` means
`${CODEX_HOME}/plugins/cat-marketplace/plugins/cat`. Derive that value from native `CODEX_HOME` whenever a prompt needs
a file shipped in CAT's installed plugin, including a launcher, rule, skill, or configuration file. Use paths below it
as `${CAT_PLUGIN_ROOT}/…`.

For an ordinary Codex agent tool shell, `CAT_WORK` means the absolute `.cat/work` directory below the current CAT
project root. In the same shell action that needs it, derive it with `${CAT_PLUGIN_ROOT}/client/bin/workflow-temp root`.
The command finds the project root and prints the resulting absolute path; do not reconstruct that path from the current
directory. Create invocation-local temporary resources below `${CAT_WORK}/temp`; retain resumable session data directly
below `${CAT_WORK}` until its session no longer needs it or is deleted permanently.

Do not rely on a prior command having exported either value. In the shell command that expands `CAT_PLUGIN_ROOT` or
`CAT_WORK`, first derive `CAT_PLUGIN_ROOT` from `CODEX_HOME`, then derive `CAT_WORK` with `workflow-temp root`, and then
invoke the required launcher.

Do not let a locally defined `CAT_WORK` override that derived value. If it is defined and differs, stop and report both
values; if it matches, continue using the derived value. The launcher computes the work root itself, so exporting
`CAT_WORK` cannot change where it stores data.

Use this check in the same shell action that needs the value:

```sh
derived_cat_plugin_root="${CODEX_HOME}/plugins/cat-marketplace/plugins/cat"
if [ -n "${CAT_PLUGIN_ROOT+x}" ] && [ "$CAT_PLUGIN_ROOT" != "$derived_cat_plugin_root" ]; then
  printf '%s\n' \
    "CAT_PLUGIN_ROOT conflicts with the Codex-derived plugin root: $CAT_PLUGIN_ROOT != $derived_cat_plugin_root" >&2
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

Use a separate shell statement to assign the derived plugin root before a later statement expands it. For example, this
action reads the installed copy of this rule; replace only the final literal relative path with the rule, skill, or
launcher already selected by the active route:

```sh
# Read an installed CAT file in an ordinary Codex tool shell
derived_cat_plugin_root="${CODEX_HOME}/plugins/cat-marketplace/plugins/cat"
if [ -n "${CAT_PLUGIN_ROOT+x}" ] && [ "$CAT_PLUGIN_ROOT" != "$derived_cat_plugin_root" ]; then
  exit 1
fi
CAT_PLUGIN_ROOT="$derived_cat_plugin_root"
cat "$CAT_PLUGIN_ROOT/rules/codex/skill-env.md"
```

Do not combine the last two statements as `CAT_PLUGIN_ROOT="$derived_cat_plugin_root" cat "$CAT_PLUGIN_ROOT/..."`.
The shell expands the `cat` argument before applying that command's temporary environment assignment, so the command
reads `/rules/...` instead of the installed file.

Do not use `PLUGIN_ROOT`: Codex defines it for hook invocations, not ordinary agent tool shells. Do not let a locally
defined `CAT_PLUGIN_ROOT` override the derived value. If it is defined and differs, stop and report both values; if it
matches, continue using the derived value.
