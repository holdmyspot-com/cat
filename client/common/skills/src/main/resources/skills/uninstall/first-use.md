# Uninstall CAT

## Design Goals

- Remove this harness's whole CAT installation with the trusted updater that is installed with it, so that removal
	downloads nothing, leaves the other harness's installation untouched, and leaves nothing behind that the user did
	not choose to keep.
- Keep CAT's plugin data unless the user asks to delete it, so that a later installation can migrate it, and ask the
	user before removing anything.
- Leave every file that CAT does not own, including the user's rules in `.cat/rules`, untouched.
- Leave a change that the agent's sandbox blocks to the user, instead of working around the sandbox.
- Give the user the exact command that a blocked change needs, so that they can run it without asking for it.

## Guidance

1. Find the trusted updater's launcher, and write its absolute path in place of `<launcher>` below. This harness has
	its own installation, so the path contains the harness identifier `<h>`:
	- On Linux and macOS, it is `cat-updater/<h>/bin/cat-updater` in the directory that the environment variable
		`XDG_DATA_HOME` names when that variable holds an absolute path, and otherwise in `.local/share` in the user's
		home directory. `<launcher>` stands for that path.
	- On Windows, in PowerShell or Git Bash, it is `cat-updater\<h>\bin\cat-updater.ps1` in the directory that the
		environment variable `LOCALAPPDATA` names. `<launcher>` stands for the command `powershell.exe -NoProfile
		-NonInteractive -ExecutionPolicy Bypass -File` followed by that path in double quotes.

	Read the variable's value first, then write the launcher's absolute path literally in the command that runs it,
	because the user's permission entries match that exact text. Write the command as the launcher followed by its
	arguments only: no variable, `cd`, environment assignment, redirection, pipe or chain of commands.

	When the launcher does not exist, there is no trusted updater to remove CAT with for this harness; the other
	harness's installation does not count. End your turn with a reply that says so, and that the user can install CAT's
	updater again with the entry command from the `update` skill and then ask you to uninstall. Run nothing else.
2. Before you run anything, ask the user whether to also delete CAT's plugin data, and state that the default is to
	keep it. Say that uninstalling removes this harness's whole CAT installation: its plugin and registration, the
	updater, the Java runtime and previous release that CAT kept for it, and the permission entries that let you run
	the updater, and the runner artifacts in `~/.cat/bundles`, which the other harness's installation downloads again
	when it next needs them, but not the other harness's CAT installation, and not the user's own files or rules. End
	your turn with the question, and continue only when the user answers. Skip the question when the user's request
	already says whether to delete the data.
3. Run one command: `<launcher> uninstall` to keep CAT's plugin data, or `<launcher> uninstall --delete-data` only when
	the user asked to delete it. The harness may ask the user to approve this command before it runs; that approval is
	separate from the user's answer in step 2. When the user declines it, stop and report that nothing was
	removed.
4. The launcher prints one line of JSON. Act on its `result` field:
	- `uninstalled`: tell the user that CAT is removed from this harness and that sessions they start from now on run
		without it. Say whether CAT's plugin data was kept or deleted. Also tell them that installing CAT again is a new
		installation, which needs the one manual entry command again, and that you cannot run it for them. Run nothing
		further with the launcher: it no longer exists. When the result's `unregistered` field is false, the harness
		program that CAT recorded when it was installed no longer existed, so CAT could not remove its registration
		and the harness may still list the plugin and its marketplace. Tell the user to remove those entries with the
		harness's own commands once the harness program works again: for `claude`, `claude plugin uninstall cat@cat`
		and then `claude plugin marketplace remove cat`; for `codex`, `codex plugin remove cat@cat` and then
		`codex plugin marketplace remove cat`. Run them yourself only when the user asks.
	- `not_removed`: tell the user that CAT's files remain, name the directory that the launcher printed to standard
		error, and ask them to delete that directory themselves, outside the sandbox, after they close every session
		that uses CAT.
	- `locked`: report that another operation on CAT is running, and that the user can ask again once it finishes.
	- `usage`: report its `message` as a defect in CAT.
	- Any other result: report its `message` and tell the user that running the same command again continues the
		removal.

	When the launcher prints anything else, report its output.

When the launcher exits with status 77, or its `result` is `not_writable`, or the harness refuses to run the launcher
command, the user's sandbox or permission settings block the operation, at a path that the result's `paths` may name.
Then end your turn with a reply that names that path, if the result names one, shows the command you ran in a code
block character for character as printed, never as a summary or a description of it, and asks the user to run it in a
terminal outside the sandbox or, in Claude Code only, to type it at the prompt prefixed with `! `. Do no other work in
that turn, including work the user asked for earlier. Do not run it again, with other permissions, or in any other way
to get around the sandbox.
