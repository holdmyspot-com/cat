# Update CAT

## Design Goals

- Install another CAT release only when it differs from the active one, so that an update with nothing to do changes
	nothing.
- Update routinely only through CAT's trusted updater, which accepts only signed releases that the user's policy
	approves.
- Leave setting up, upgrading and rolling back the trusted updater to the user, because those operations change what
	the updater trusts.
- Leave a change that the agent's sandbox blocks to the user, instead of working around the sandbox.
- Give the user the exact command or setting that a blocked change needs, so that they can apply it without asking for
	it.

## Guidance

1. Find the trusted updater's launcher, and write its absolute path in place of `<launcher>` below:
	- In a POSIX shell, including Git Bash on Windows, it is `cat-updater/bin/cat-updater` in the directory that the
		environment variable `XDG_DATA_HOME` names when that variable holds an absolute path, and otherwise in
		`.local/share` in the user's home directory.
	- In PowerShell, where Codex runs commands on Windows, it is `cat-updater\bin\cat-updater.cmd` in the directory
		that the environment variable `LOCALAPPDATA` names.

	Read the variable's value first, then write the launcher's absolute path literally in every command that runs it,
	because the user's sandbox exemption matches that exact text. Write each such command as the launcher followed by
	its arguments only: no variable, `cd`, environment assignment, redirection, pipe or chain of commands.
2. When the launcher does not exist, the trusted updater is not set up. End your turn with a reply that says so and
	gives the user, each in its own code block, two commands to run in a POSIX shell outside the sandbox. Each command
	downloads the release's entry script with `curl -fsSL` and pipes it to `sh -s --` with the arguments `--harness`
	and `<h>`:
	- The setup command, which installs that release and sets up the trusted updater.
	- The same command with the extra argument `--updater-only`, which sets up the trusted updater and leaves the
		existing CAT installation unchanged. Say that this form is the one for an existing CAT installation.

	The entry script's address is `https://github.com/holdmyspot-com/cat/releases/download/<tag>/cat.sh` for a release
	that the user names, with its tag such as `v1.1`, and otherwise
	`https://github.com/holdmyspot-com/cat/releases/latest/download/cat.sh`. Say that the user asks for the update
	again once setup is done. Do not run an installer, the entry script or the updater's setup yourself instead.
3. Run the launcher:
	- When the user names a release, run `<launcher> update --harness <h> <tag>`, with the release's tag such as
		`v1.1`.
	- When the user asks only whether a newer release exists, run `<launcher> status --harness <h>`, report what it
		prints, and stop.
	- Otherwise run `<launcher> update --harness <h>`.
4. The launcher prints one line of JSON. Act on its `result` field:
	- `updated`: tell the user that the release that `version` names replaced the one that `previous_version` names,
		and that it takes effect once they restart or resume the session. In Codex, also say that Codex asks them to
		review a new or changed CAT hook before it runs.
	- `up_to_date`: tell the user that the active CAT release, which `version` names, is already the release they asked
		for, so nothing changed.
	- `refused`: CAT's release policy rejected the release. Report its `message`, and do not try to install the release
		in any other way.
	- `download_failed`: report its `message`.
	- `restored`: report that the update failed and that the previous release is still active, with its `message`.
	- `not_restored`: report that the update failed and that the previous release could not be restored, show each of
		its `paths`, and give the user the command `<launcher> rollback --harness <h>` to run themselves.
	- `locked`: report that another update of CAT is running, and that the user can ask again once it finishes.
	- `usage`: report its `message` as a defect in CAT.

	When the launcher prints anything else, report its output.

Never run the launcher's `rollback`, `setup` or `upgrade-self` operation yourself. These are the user's operations:
when one is needed, give the user its command.

When the launcher exits with status 77, or its `result` is `not_writable`, the user's sandbox exemption for the trusted
updater is missing, and the launcher could not write a path that the result's `paths` names. Then end your turn with a
reply that names that path, shows the result's `exemption` text in a code block character for character as printed,
never as a summary or a description of it, and asks the user to add it to their harness's settings, or else to run the
command you ran in a terminal outside the sandbox or, in Claude Code only, to type it at the prompt prefixed with `! `.
Do no other work in that turn, including work the user asked for earlier. Do not run it again, with other permissions,
or in any other way to get around the sandbox.
