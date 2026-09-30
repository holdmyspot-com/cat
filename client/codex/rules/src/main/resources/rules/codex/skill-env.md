# CAT Skill Environment

## Design Goals

- Let an ordinary Codex prompt locate CAT's installed files and its project-owned temporary root without relying on
  hook-only environment variables or state left by an earlier shell command.
- Keep both values derived from the native Codex environment and the CAT CLI that owns project-root discovery.
- Preserve a supplied absolute file path as the selected target; resolve the installed plugin root only when the
  reference requires it.

## Guidance

When a prompt supplies an absolute file path, use it verbatim, including when it points into a plugin cache. Reading or
invoking that target does not require `CAT_PLUGIN_ROOT` derivation.

For an ordinary Codex agent tool shell, `CAT_PLUGIN_ROOT` means
`${CODEX_HOME}/plugins/cat-marketplace/plugins/cat`. Derive that value from native `CODEX_HOME` only when the selected
reference uses `CAT_PLUGIN_ROOT` or requires the installed plugin root to resolve its relative path. Use such references
as `${CAT_PLUGIN_ROOT}/…`.

For an ordinary Codex agent tool shell, `CAT_TEMP` means the absolute `.cat/temp` directory below the current CAT
project root. In the same shell action that needs it, derive it with `${CAT_PLUGIN_ROOT}/client/bin/workflow-temp root`.
The command finds the project root and prints the resulting absolute path; do not reconstruct that path from the current
directory.

Do not rely on a prior command having exported either value. In the shell command that expands `CAT_PLUGIN_ROOT` or
`CAT_TEMP`, first derive `CAT_PLUGIN_ROOT` from `CODEX_HOME`. When the command needs `CAT_TEMP`, then derive it with
`workflow-temp root` before invoking the required launcher.

Use a separate shell statement to assign the derived plugin root before a later statement expands it. For example, this
action reads the installed copy of this rule; replace only the final literal relative path with the rule, skill, or
launcher already selected by the active route:

```sh
# Read an installed CAT file in an ordinary Codex tool shell
derived_cat_plugin_root="${CODEX_HOME}/plugins/cat-marketplace/plugins/cat"
if [ -n "${CAT_PLUGIN_ROOT+x}" ] && [ "$CAT_PLUGIN_ROOT" != "$derived_cat_plugin_root" ]; then
  printf 'CAT_PLUGIN_ROOT=%s conflicts with the root derived from CODEX_HOME: %s\n' \
    "$CAT_PLUGIN_ROOT" "$derived_cat_plugin_root" >&2
  exit 1
fi
CAT_PLUGIN_ROOT="$derived_cat_plugin_root"
cat "$CAT_PLUGIN_ROOT/rules/codex/skill-env.md"
```

Do not combine the last two statements as `CAT_PLUGIN_ROOT="$derived_cat_plugin_root" cat "$CAT_PLUGIN_ROOT/..."`.
The shell expands the `cat` argument before applying that command's temporary environment assignment, so the command
reads `/rules/...` instead of the installed file.

Do not use `PLUGIN_ROOT`: Codex defines it for hook invocations, not ordinary agent tool shells. When deriving
`CAT_PLUGIN_ROOT`, do not let a locally defined value override the derived value. If it is defined and differs, stop and
report both values; if it matches, continue using the derived value.
