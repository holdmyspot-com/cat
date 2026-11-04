# Update CAT

## Design Goals

- Install another CAT release only when it differs from the active one, so that an update with nothing to do changes
	no installed file and only registers the plugin again when the harness lost it.
- Install, update and inspect CAT only through CAT's trusted installer, which accepts only signed releases that the
	user's policy approves.
- Treat CAT's Java runtime, installer and plugin as one bundle that moves between releases together.
- Leave setting up the trusted installer to the user, because that operation changes what the installer trusts.
- Leave a change that the agent's sandbox blocks to the user, instead of working around the sandbox.
- Give the user the exact command that a blocked change needs, so that they can apply it without asking for it.

## Guidance

1. Find the trusted installer's launcher, and write its absolute path in place of `<launcher>` below. This harness has
	its own installation, so the path contains the harness identifier `<h>`:
	- On Linux and macOS, it is `cat-updater/<h>/bin/cat-updater` in the directory that the environment variable
		`XDG_DATA_HOME` names when that variable holds an absolute path, and otherwise in `.local/share` in the user's
		home directory. `<launcher>` stands for that path.
	- On Windows, in PowerShell or Git Bash, it is `cat-updater\<h>\bin\cat-updater.ps1` in the directory that the
		environment variable `LOCALAPPDATA` names. `<launcher>` stands for the command `powershell.exe -NoProfile
		-NonInteractive -ExecutionPolicy Bypass -File` followed by that path in double quotes.

	Read the variable's value first, then write the launcher's absolute path literally in every command that runs it,
	because the user's permission entries match that exact text. Write each such command as the launcher followed by
	its arguments only: no variable, `cd`, environment assignment, redirection, pipe or chain of commands.
2. When the launcher does not exist, CAT's trusted installer is not installed for this harness: CAT was never installed
	for it or was uninstalled from it. The other harness's installation does not count. End your turn with a reply that
	says so and gives the user, in its own code block, the one command that installs CAT for this harness. The user
	runs it in a terminal outside the sandbox. The command downloads the release's entry script `cat.sh` with
	`curl -fsSL` and pipes it to `sh -s --` with the arguments `--harness` and `<h>`. In PowerShell on Windows, the
	user instead downloads `cat.ps1` from the same address with `irm`, creates a script block from it and runs the
	script block with the argument `-Harness <h>`.

	The entry script's address is `https://github.com/holdmyspot-com/cat/releases/download/<tag>/cat.sh` for a release
	that the user names, with its tag such as `v1.1`, and otherwise
	`https://github.com/holdmyspot-com/cat/releases/latest/download/cat.sh`. Say that the command installs the
	installer, a Java runtime for it, CAT and the permissions that let you install, update and uninstall CAT afterwards,
	so that the user runs it only this once for this harness, and that they ask you again once it is done. Do not run
	an installer, the entry script or the installer's setup yourself instead.
3. Run the launcher:
	- When the user asks to install CAT, including when CAT's plugin was removed by other means, run
		`<launcher> install --harness <h>` with no release argument. It installs only the release that this
		installation already runs, which the result's `version` names; the launcher refuses a tag or `latest` that names
		another release, with exit status 3. When the user named a release, run `<launcher> update --harness <h> <tag>`
		after `install` reports `installed`, with the release's tag such as `v1.1`.
	- When the user asks to update CAT to a release they name, run `<launcher> update --harness <h> <tag>`, with the
		release's tag such as `v1.1`. This is also how to move to a release other than the one that the installation
		runs. An update moves the Java runtime, the installer and the plugin together.
	- When the user asks to go back to an earlier release, run `<launcher> update --harness <h> <tag>` with the tag of
		the release they name, such as `v1.1`. When they do not name one, run `<launcher> status --harness <h>` first,
		and use the release that its `previous_version` field names; when that field is absent, ask the user which
		release to go back to. An update without a release never goes back to an earlier one.
	- When the user asks only whether a newer release exists, or which release is active, run
		`<launcher> status --harness <h>`, report what it prints, and stop. When its `installed` field is false, CAT's
		plugin is absent: say so, and offer to install it. When its `pending_swap` field is present, the operation that
		its `operation` names, from the release that `from_version` names to the one that `to_version` names, has not
		finished: say so, and that the next operation on CAT finishes or undoes it first.
	- Otherwise run `<launcher> update --harness <h>`.
4. The launcher prints one line of JSON. Act on its `result` field:
	- `installed`: tell the user that the release that `version` names is installed and registered, and that it takes
		effect once they restart or resume the session. In Codex, also say that Codex asks them to review a new or
		changed CAT hook before it runs.
	- `updated`: tell the user that the release that `version` names replaced the one that `previous_version` names,
		and that it takes effect once they restart or resume the session. In Codex, also say that Codex asks them to
		review a new or changed CAT hook before it runs.
	- `up_to_date`: tell the user that the active CAT release, which `version` names, is already the release they asked
		for, so no installed file changed.
	- `refused`: CAT's release policy, the installed Java runtime or a pending data migration rejected the operation,
		and the active installation is unchanged. Report its `message`, and do not try to install the release in any
		other way. When the message tells the user to rerun the entry command of a newer release, the installed Java
		runtime cannot run the release: give the user that command as step 2 describes, for the tag that the message
		names or else for the latest release. When the message says that `install` adds only the release that the
		installation runs, run `<launcher> update --harness <h> <tag>` for the release that the message names only when
		the user asked for that release. When the message says that CAT's data structures changed between the releases
		and are not backward compatible, the earlier release would lose the user's data: report the message to the
		user as it is, and do not retry the update or go back in any other way.
	- `download_failed`: report its `message`.
	- `restored`: report that the operation failed and that the previous state is still active, with its `message`.
	- `not_restored`: report that the operation failed and that the previous state could not be restored, show each of
		its `paths`, and give the user the entry command from step 2, which repairs the installation.
	- `failed`: an unexpected error stopped the operation after it may have changed the installation. Report its
		`message`, and that running the same command again continues the operation; run it again only when the user
		asks.
	- `not_swapped`: the Java runtime could not be put in place, so the operation did not finish. Run
		`<launcher> install --harness <h>` at once: it undoes the failed operation and puts back the previous release,
		so that CAT's hooks work again. Then tell the user that the operation could not complete, quoting the
		`not_swapped` result's `message`; when that install also fails, quote its `message` too.
	- `locked`: report that another operation on CAT is running, and that the user can ask again once it finishes.
	- `usage`: report its `message` as a defect in CAT.

	Any result may carry an `aot_cache` field, which says whether CAT prepared a start-up cache for the plugin. A false
	value is never a failure. Do not mention `aot_cache` to the user unless they ask about it; if they do, say that
	true means the cache was prepared and false means sessions start a little slower.

	When the launcher prints anything else, report its output.

Never run the launcher's `setup` operation yourself: it is the user's operation, and the entry command runs it. When
the user needs it, give them the entry command as step 2 describes.

When the launcher exits with status 77, or its `result` is `not_writable`, or the harness refuses to run the launcher
command, the user's sandbox or permission settings block the operation, at a path that the result's `paths` may name.
Say that running the entry command from step 2 again restores the permission entries that let you run it. Then end your turn with a reply that names that path, if the result names one, shows the command you ran in a code
block character for character as printed, never as a summary or a description of it, and asks the user to run it in a
terminal outside the sandbox or, in Claude Code only, to type it at the prompt prefixed with `! `. Do no other work in
that turn, including work the user asked for earlier. Do not run it again, with other permissions, or in any other way
to get around the sandbox.
