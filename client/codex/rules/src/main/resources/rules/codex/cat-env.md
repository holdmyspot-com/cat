# CAT Environment

## Design Goals

- Let an ordinary Codex agent use a file shipped in CAT's installed plugin, such as a launcher, rule, or skill, from the
	plugin directory that its session context states, because Codex tool shells define no variable that names that
	directory.
- Let the agent name the project directory and its session's temporary directory inside the project from what its
	session context states, because Codex tool shells define no variable that names them, and create, remove, and move
	temporary files there with standard commands.
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

<!-- cat:include platform/{platform}/cat-env/shell.md -->

<!-- cat:include platform/{platform}/cat-env/temporary-directory.md -->

<!-- cat:include platform/{platform}/cat-env/project-files.md -->
