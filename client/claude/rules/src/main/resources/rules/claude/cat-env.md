# CAT Environment

## Design Goals

- Let an ordinary Claude Code agent use a file shipped in CAT's installed plugin, such as a launcher, rule, or skill,
  from the plugin directory that its session context states, because Claude Code tool shells define no variable that
  names that directory.
- Let the agent name the project directory and its session's temporary directory inside the project from what its
  session context states, because Claude Code tool shells define no variable that names them, and create, remove, and
  move temporary files there with standard commands.
- Keep every file the agent writes inside the project directory unless the user names another location, so its scratch
  files, probe inputs, and saved command output stay where the user expects them and can remove them.

## Guidance

In this session, CAT's installed plugin directory is `<!-- cat:plugin-root -->`. Claude Code tool shells do not define
`CAT_PLUGIN_ROOT`. Wherever a rule or skill writes `${CAT_PLUGIN_ROOT}`, including a rule path to lazy load, write that
directory in its place in the shell command or file path you use; never expand `${CAT_PLUGIN_ROOT}` from the
environment.

`CAT_PROJECT_ROOT` is the working directory that your environment information states. Wherever a rule or skill writes
`${CAT_PROJECT_ROOT}`, write that directory in its place; never expand `${CAT_PROJECT_ROOT}` from the environment.

`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/sessions/<!-- cat:session-id -->/temp`, this session's temporary directory, and
the directory exists. Wherever a rule or skill writes `${CAT_TEMP}`, write that directory in its place; never expand
`${CAT_TEMP}` from the environment. Create a temporary directory there with
`mktemp -d -p ${CAT_TEMP} <workflow-name>-XXXXXX`, which prints the new directory's absolute path; use that output as
the directory. Remove it with `rm -rf -- <directory>` once nothing needs it, or move it with
`mv -- <directory> <new-path>` to hand it to a later owner.

Write files only inside `${CAT_PROJECT_ROOT}` unless the user names a location outside it. Put a scratch file, probe
input, or saved command output in a temporary directory under `${CAT_TEMP}`, not in `/tmp`, the home directory, or
another directory outside the project. When you know that a command you run writes its own temporary files to the
system's temporary directory, point it at that directory through its `TMPDIR` or equivalent setting.
