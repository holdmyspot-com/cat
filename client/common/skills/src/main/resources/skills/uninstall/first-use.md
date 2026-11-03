# Uninstall CAT

## Design Goals

- Remove CAT with the uninstaller that its installed plugin already holds, so that removal downloads nothing.
- Keep CAT's data unless the user asks to delete it, so that a later installation can migrate it.
- Leave CAT's trusted updater to the user, who set it up, and tell them how to remove it.
- Leave a change that the agent's sandbox blocks to the user, instead of working around the sandbox.
- Give the user the exact command that a blocked change needs, so that they can run it without asking for it.

## Guidance

CAT's installed plugin directory is two levels above this skill's directory, and its `install/` directory holds CAT's
uninstaller. The uninstaller removes CAT's plugin and its marketplace registration, and `.cat/bundles` in the user's
home directory, where CAT's runner launchers keep the CAT artifacts that they downloaded. In Codex it first removes the
CAT-owned custom agents recorded in CAT's ownership manifest, and it keeps CAT's data directory unless the user asks to
delete CAT's data as well.

Run one command, writing the installed plugin directory's absolute path in place of `<plugin>`:

- In a POSIX shell, including Git Bash on Windows, run `sh` with the argument `<plugin>/install/uninstall-<h>.sh`. In
	Codex, add the argument `--delete-data` only when the user asks to delete CAT's data.
- In PowerShell, where Codex runs commands on Windows, read the text of `<plugin>\install\install-codex.ps1`, create a
	script block from it, and run the script block with the argument `-Uninstall`, followed by `-DeleteData` only when
	the user asks to delete CAT's data. Do not run the file itself, which PowerShell's execution policy can block.

When the command exits with status 77, or its output says that CAT cannot write a path and gives a command to run
outside the sandbox, end your turn with a reply that names that path, shows the command in a code block character for
character as printed, never as a summary or a description of it, and asks the user to run it in a terminal outside the
sandbox or, in Claude Code only, to type it at the prompt prefixed with `! `. Do no other work in that turn, including
work the user asked for earlier. Do not run it again, with other permissions, or in any other way to get around the
sandbox. Report any other failure with the command's output.

After the command succeeds, tell the user that CAT is uninstalled and that sessions they start from now on run without
it. Also tell them that CAT's trusted updater, if they set it up, stays installed, and that they can remove it
themselves outside the sandbox. To do so, they delete the updater's data and configuration directories, and remove
the entries for the updater's launcher that they added to their harness's sandbox and permission settings. The
launcher is `cat-updater/bin/cat-updater`, or `cat-updater\bin\cat-updater.cmd` on Windows, in the directory that holds
the data directory. The directories are:

- On Linux, macOS and Git Bash, the data directory `cat-updater` in the directory that the environment variable
	`XDG_DATA_HOME` names, or in `.local/share` in their home directory when that variable does not hold an absolute
	path, and the configuration directory `cat-updater` in the directory that `XDG_CONFIG_HOME` names, or in `.config`
	in their home directory when that variable does not hold an absolute path.
- On Windows in PowerShell, `cat-updater` in the directory that the environment variable `LOCALAPPDATA` names, which
	is both the data and the configuration directory.

Do not delete them yourself.
