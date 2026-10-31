# CAT Environment

## Design Goals

- Let an ordinary Claude Code agent use a file shipped in CAT's installed plugin, such as a launcher, rule, or skill,
	from the plugin directory that its session context states, because Claude Code tool shells define no variable that
	names that directory.
- Let the agent name the project directory and its session's temporary directory inside the project from the paths
	that its session context states, not from its working directory, which can change during the session, because
	Claude Code tool shells define no variable that names them, and create, remove, and move temporary files there with
	standard commands.
- Let the agent find a file that a user rule names from the user rule directory that its session context states, which
	can differ from the directory below the home directory of its tool shells.
- Keep every file the agent writes inside the project directory unless the user names another location, so its scratch
	files, probe inputs, and saved command output stay where the user expects them and can remove them.

## Guidance

In this session, CAT's installed plugin directory is `<!-- cat:plugin-root -->`. Claude Code tool shells do not define
`CAT_PLUGIN_ROOT`. Wherever a rule or skill writes `${CAT_PLUGIN_ROOT}`, including a rule path to lazy load, write that
directory in its place in the shell command or file path you use; never expand `${CAT_PLUGIN_ROOT}` from the
environment.

`CAT_PROJECT_ROOT` is `<!-- cat:project-root -->`, this session's project directory. Wherever a rule or skill writes
`${CAT_PROJECT_ROOT}`, write that directory in its place; never expand `${CAT_PROJECT_ROOT}` from the environment.

`CAT_USER_RULES` is `<!-- cat:user-rules -->`, this session's user rule directory. Wherever a rule writes
`${CAT_USER_RULES}`, write that directory in its place; never expand `${CAT_USER_RULES}` from the environment.

<!-- cat:include platform/{platform}/cat-env/temporary-directory.md -->

Write files only inside `${CAT_PROJECT_ROOT}` unless the user names a location outside it. Put a scratch file, probe
input, or saved command output in a temporary directory under `${CAT_TEMP}`, not in `/tmp`, the home directory, or
another directory outside the project. When you know that a command you run writes its own temporary files to the
system's temporary directory, point it at that directory through its `TMPDIR` or equivalent setting.
