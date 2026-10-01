# CAT Skill Environment

## Design Goals

- Let an ordinary Claude Code prompt locate CAT's installed files and its project-owned temporary root without relying
  on hook-only environment variables or state left by an earlier shell command.
- Keep both values derived from the native Claude Code environment and the CAT CLI that owns project-root discovery.

## Guidance

For an ordinary Claude Code agent tool shell, `CAT_PLUGIN_ROOT` means
`${CLAUDE_CONFIG_DIR}/plugins/cat-marketplace/plugins/cat`. Derive that value from native `CLAUDE_CONFIG_DIR` whenever a
prompt needs a file shipped in CAT's installed plugin, including a launcher, rule, skill, or configuration file. Use
paths below it as `${CAT_PLUGIN_ROOT}/…`.

For an ordinary Claude Code agent tool shell, `CAT_TEMP` means the absolute `.cat/work/temp` directory below the current
CAT project root. In the same shell action that needs it, derive it with
`${CAT_PLUGIN_ROOT}/client/bin/workflow-temp root`. The command finds the project root, creates the directory if it is
missing, and prints its absolute path on one line with no prefix; do not reconstruct that path from the current
directory.

Do not rely on a prior command having exported either value. In the shell command that expands `CAT_PLUGIN_ROOT` or
`CAT_TEMP`, first derive `CAT_PLUGIN_ROOT` from `CLAUDE_CONFIG_DIR`, then derive `CAT_TEMP` with `workflow-temp root`,
and then invoke the required launcher.

Do not use `CLAUDE_PLUGIN_ROOT`: Claude Code defines it for hook invocations, not ordinary agent tool shells. Do not let
a locally defined `CAT_PLUGIN_ROOT` override the derived value. If it is defined and differs, stop and report both
values; if it matches, continue using the derived value.
