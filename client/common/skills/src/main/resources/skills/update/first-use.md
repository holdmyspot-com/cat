# Update CAT

## Design Goals

- Install another CAT release only when it differs from the active one, so that an install of the active release
	replaces no installed release and only repairs CAT's permission entries and finishes its pending data migrations.
- Install, update and inspect CAT only through CAT's trusted installer, which accepts only signed releases that the
	user's policy approves.
- Treat CAT's Java runtime, installer and plugin as one bundle that moves between releases together.
- Leave setting up the trusted installer to the user, because that operation changes what the installer trusts.
- Leave a change that the agent's sandbox blocks to the user, instead of working around the sandbox.
- Give the user the exact command that a blocked change needs, so that they can apply it without asking for it.

## Guidance

1. Find the trusted installer's launcher, and write its absolute path in place of `<launcher>` below. The launcher is
	in the version folder of CAT's plugin that holds this skill: the folder that holds the `skills` folder in which this
	skill's folder lies.
	- On Linux and macOS, it is `bin/installer` in that version folder. `<launcher>` stands for that path.
	- On Windows, in PowerShell or Git Bash, it is `bin\installer.ps1` in that version folder. `<launcher>` stands for
		the command `powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File` followed by that path in
		double quotes.

	Resolve the version folder to its absolute path first, then write the launcher's absolute path literally in every
	command that runs it, because the user's permission entries match that exact text. Write each such command as the
	launcher followed by its arguments only: no variable, `cd`, environment assignment, redirection, pipe or chain of
	commands.
2. When the launcher does not exist, CAT's trusted installer is not installed for this harness: the plugin's version
	folder is incomplete. The other harness's installation does not count. End your turn with a reply that says so and
	gives the user, in its own code block, the one command that installs CAT for this harness. The user runs it in a
	terminal outside the sandbox. The command downloads the release's entry script `cat.sh` with `curl -fsSL` and
	pipes it to `sh -s --` with the arguments `--harness` and `<h>`. In PowerShell on Windows, the user instead
	downloads `cat.ps1` from the same address with `irm`, creates a script block from it and runs the script block
	with the argument `-Harness <h>`.

	The entry script's address is `https://github.com/holdmyspot-com/cat/releases/download/<tag>/cat.sh` for a release
	that the user names, with its tag such as `v1.1`, and otherwise
	`https://github.com/holdmyspot-com/cat/releases/latest/download/cat.sh`. Say that the command installs the
	installer, a Java runtime for it, CAT and the permissions that let you install, update and uninstall CAT afterwards,
	so that the user runs it only this once for this harness, and that they ask you again once it is done. Do not run
	another installer or the entry script yourself instead.
3. Run the launcher:
	- When the user asks to install or update CAT to a release they name, run `<launcher> install --harness <h> <tag>`,
		with the release's tag such as `v1.1`. This is also how to move to a release other than the one that the
		installation runs. An install of another release moves the Java runtime, the installer and the plugin together.
	- When the user asks to go back to an earlier release, run `<launcher> install --harness <h> <tag>` with the tag of
		the release they name, such as `v1.1`. When they do not name one, ask the user which release to go back to. An
		install without a release never goes back to an earlier one.
	- When the user asks only whether a newer release exists, or which release is active, run
		`<launcher> status --harness <h>`, report what it prints, and stop. Its `version` names the active release; it
		does not look for a newer one, so when the user asked about a newer release, say so and offer to run the
		install below, which installs the latest approved release when it is newer. When its `installed` field is
		false, CAT's plugin is absent: say so, and offer to install it.
	- Otherwise, including when the user asks to install or update CAT without naming a release, or when CAT's plugin
		was removed by other means, run `<launcher> install --harness <h>`. It installs the latest release that the
		user's policy approves, or repairs the active one when that is the latest.
4. The launcher prints one line of JSON. Act on its `result` field:
	- `installed`: tell the user that the release that `version` names is installed and registered, and that it takes
		effect once they restart or resume the session. In Codex, also say that Codex asks them to review a new or
		changed CAT hook before it runs.
	- `updated`: tell the user that the release that `version` names replaced the one that `previous_version` names,
		and that it takes effect once they restart or resume the session. In Codex, also say that Codex asks them to
		review a new or changed CAT hook before it runs.
	- `up_to_date`: tell the user that the active CAT release, which `version` names, is already the release they asked
		for, so no other release was installed. Also report the result's `message`, which names the permission entries
		that the install repaired, if any.
	- `refused` with a `consent_command` field: the operation needs a change to the harness's settings that only the
		user can allow, and it changed nothing. End your turn with a reply that explains its `message` to the user in
		plain words and asks whether they allow the change. Only when the user answers yes, run the command that
		`consent_command` holds, character for character as printed. The harness asks the user to approve that command
		before it runs, because it gives the user's permission; in Codex, ask to run it outside the sandbox. When they
		answer no, or decline the harness's approval, do not run it again, and tell them what the message says does not
		work without the change.
	- `refused`: CAT's release policy or its data structures rejected the operation, and the active installation is
		unchanged. Report its `message`, and do not try to install the release in any other way. When the message tells
		the user to run the entry command again, give the user that command as step 2 describes. When the message says
		that the latest release is older than the active one, run `<launcher> install --harness <h> <tag>` for an older
		release only when the user asked for that release. When the message says that CAT's data structures changed
		between the releases and are not backward compatible, the earlier release would lose the user's data: report the
		message to the user as it is, and do not retry the install or go back in any other way.
	- `download_failed`: report its `message`.
	- `failed`: an unexpected error stopped the operation after it may have changed the installation. Report its
		`message`, and that running the same command again continues the operation; run it again only when the user
		asks.
	- `locked`: report that another operation on CAT is running, and that the user can ask again once it finishes.
	- `usage`: report its `message` as a defect in CAT.

	Any result may carry an `aot_cache` field, which says whether CAT prepared a start-up cache for the plugin. A false
	value is never a failure. Do not mention `aot_cache` to the user unless they ask about it; if they do, say that
	true means the cache was prepared and false means sessions start a little slower.

	When the launcher prints anything else, report its output.

When the launcher exits with status 77, or its `result` is `not_writable`, or the harness refuses to run the launcher
command, the user's sandbox or permission settings block the operation, at a path that the result's `paths` may name.
Say that running the entry command from step 2 again restores the permission entries that let you run it. Then end your turn with a reply that names that path, if the result names one, shows the command you ran in a code
block character for character as printed, never as a summary or a description of it, and asks the user to run it in a
terminal outside the sandbox or, in Claude Code only, to type it at the prompt prefixed with `! `. Do no other work in
that turn, including work the user asked for earlier. Do not run it again, with other permissions, or in any other way
to get around the sandbox.
