# CAT Environment

## Design Goals

- Let an ordinary Codex agent use a file shipped in CAT's installed plugin, such as a launcher, rule, or skill, from the
	plugin directory that its session context states, because Codex tool shells define no variable that names that
	directory.
- After an install changes the active CAT plugin, resume development only after Codex delivers session rules from the
	installed release.
- Let the agent name the project directory and its session's temporary directory inside the project from the paths
	that its session context states, not from its working directory, which can change during the session, because
	Codex tool shells define no variable that names them, and create, remove, and move temporary files there with
	standard commands.
- Let the agent find a file that a user rule names from the user rule directory that its session context states, which
	can differ from the directory below the home directory of its tool shells.
- Let a rule or skill describe a command once for every platform, by stating here how the shell that Codex runs
	commands in reads environment variables, runs launchers, and passes lines to a command's standard input.
- Keep every file the agent writes inside the project directory unless the user names another location, so its scratch
	files, probe inputs, and saved command output stay where the user expects them and can remove them.

## Guidance

In this session, CAT's installed plugin directory is `<!-- cat:plugin-root -->`. Codex tool shells do not define
`CAT_PLUGIN_ROOT`. Wherever a rule or skill writes `${CAT_PLUGIN_ROOT}`, including a rule path to lazy load, write that
directory in its place in the shell command or file path you use; never expand `${CAT_PLUGIN_ROOT}` from the
environment.

When CAT's installer reports `installed` or `updated` for the Codex installation used by this conversation, give the
user the installed `version` and stop development. If a goal is active, pause it when the user has authorized that
change and the harness exposes the operation. Otherwise ask the user to pause it. End the turn with these instructions
for the user:

1. In the current Codex conversation, open `/hooks` and review and approve any changed CAT hooks you trust.
2. Once those hooks are approved, run `/compact` in the same conversation. When it finishes, ask Codex to verify the
	CAT update. The next turn runs the CAT `SessionStart` hook when
	Codex has loaded it and the user has approved it.
3. If Codex blocks a changed CAT hook that `/hooks` still shows as trusted, or the client cannot complete compaction,
	exit and relaunch Codex in the same project, review the CAT hooks in the startup prompt, and resume the conversation.
	Then ask Codex to verify the update.

On the verification turn, use the installer's status to identify the active release. Compare it with the retained
installer result when the earlier tool call captured one. Check that CAT delivered fresh session guidance naming the
active release's plugin directory and read the session rules it points to before continuing development or resuming
a paused goal. A successful
install, hook approval, or compaction alone does not establish that the session rules were refreshed. If the fresh
guidance is missing or still names the previous release, keep development paused even on automatic goal continuation.
Offer the restart procedure if the user tried compaction. If they already restarted, report the missing or stale
guidance and stop. A status check or an `up_to_date` result does not trigger this handoff.

`CAT_PROJECT_ROOT` is `<!-- cat:project-root -->`, this session's project directory. Wherever a rule or skill writes
`${CAT_PROJECT_ROOT}`, write that directory in its place; never expand `${CAT_PROJECT_ROOT}` from the environment.

`CAT_USER_RULES` is `<!-- cat:user-rules -->`, this session's user rule directory. Wherever a rule writes
`${CAT_USER_RULES}`, write that directory in its place; never expand `${CAT_USER_RULES}` from the environment.

<!-- cat:include platform/{platform}/cat-env/shell.md -->

<!-- cat:include platform/{platform}/cat-env/temporary-directory.md -->

<!-- cat:include platform/{platform}/cat-env/project-files.md -->
