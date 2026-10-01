# CAT Skill Environment

## Design Goals

- Let an ordinary Codex agent use a file shipped in CAT's installed plugin, such as a launcher, rule, or skill, from the
  plugin directory that its session context states, because Codex tool shells define no variable that names that
  directory.
- Let the agent locate CAT's project-owned temporary directory through the CAT command that finds the project root, in
  the same shell command that uses it, rather than reconstructing the path or relying on an earlier command's state.

## Guidance

In this session, CAT's installed plugin directory is `<!-- cat:plugin-root -->`. Codex tool shells do not define
`CAT_PLUGIN_ROOT`. Wherever a skill or rule writes `${CAT_PLUGIN_ROOT}`, write that directory in its place in the
shell command; never expand `${CAT_PLUGIN_ROOT}` from the environment.

`CAT_TEMP` means the absolute `.cat/work/temp` directory below the current CAT project root. In the same shell
command that needs it, derive it with `<plugin directory>/client/bin/workflow-temp root`, where `<plugin directory>`
is the directory above. The command finds the project root, creates the directory if it is missing, and prints its
absolute path on one line with no prefix; do not reconstruct that path from the current directory.
