# CAT Environment

## Design Goals

- Let an ordinary Codex agent use a file shipped in CAT's installed plugin, such as a launcher, rule, or skill, from the
  plugin directory that its session context states, because Codex tool shells define no variable that names that
  directory.
- Let the agent name the project directory and CAT's project-owned temporary directory from what its session context
  states, because Codex tool shells define no variable that names them, and create, remove, and move temporary files
  there with standard commands.
- Let a rule or skill describe a command once for every platform, by stating here how the shell that Codex runs
  commands in reads environment variables, runs launchers, and passes lines to a command's standard input.
- Keep every file the agent writes inside the project directory unless the user names another location, so its scratch
  files, probe inputs, and saved command output stay where the user expects them and can remove them.

## Guidance

In this session, CAT's installed plugin directory is `<!-- cat:plugin-root -->`. Codex tool shells do not define
`CAT_PLUGIN_ROOT`. Wherever a rule or skill writes `${CAT_PLUGIN_ROOT}`, including a rule path to lazy load, write that
directory in its place in the shell command or file path you use; never expand `${CAT_PLUGIN_ROOT}` from the
environment.

`CAT_PROJECT_ROOT` is the directory in the `<cwd>` element of your environment context. Wherever a rule or skill writes
`${CAT_PROJECT_ROOT}`, write that directory in its place; never expand `${CAT_PROJECT_ROOT}` from the environment.

Codex runs your shell commands in a POSIX shell. When a rule or skill describes a command in words, or writes an input
as a placeholder such as `<project>`, write the command in that shell: read an environment variable `NAME` as
`"$NAME"` and the user's home directory as `"$HOME"`, and run a launcher by its path, as in `'<path>' <arguments>`. To
send lines to a command's standard input, write each line in single quotes and pipe them from `printf`, as in
`printf '%s\n' '<line>' '<line>' | '<path>' <arguments>`. Inside single quotes, write each `'` of a path or line as
`'\''`.

`CAT_TEMP` is `${CAT_PROJECT_ROOT}/.cat/work/temp`, and the directory exists. Wherever a rule or skill writes
`${CAT_TEMP}`, write that directory in its place; never expand `${CAT_TEMP}` from the environment. Create a temporary
directory there with `mktemp -d -p ${CAT_TEMP} <workflow-name>-XXXXXX`, which prints the new directory's absolute path;
use that output as the directory. Remove it with `rm -rf -- <directory>` once nothing needs it, or move it with
`mv -- <directory> <new-path>` to hand it to a later owner.

Write files only inside `${CAT_PROJECT_ROOT}` unless the user names a location outside it. Put a scratch file, probe
input, or saved command output in a temporary directory under `${CAT_TEMP}`, not in `/tmp`, the home directory, or
another directory outside the project. When you know that a command you run writes its own temporary files to the
system's temporary directory, point it at that directory through its `TMPDIR` or equivalent setting.
