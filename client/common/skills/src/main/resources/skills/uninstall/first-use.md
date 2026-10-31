# Uninstall CAT

## Design Goals

- Remove CAT with the uninstaller that its installed plugin already holds, so that removal downloads nothing.
- Keep CAT's data unless the user asks to delete it, so that a later installation can migrate it.
- Leave a change that the agent's sandbox blocks to the user, instead of working around the sandbox.

## Guidance

CAT's installed plugin directory is two levels above this skill's directory, and its `install/` directory holds CAT's
uninstaller. The uninstaller removes CAT's plugin and its marketplace registration, and `.cat/bundles` in the user's
home directory, where the runner skills keep the CAT artifacts that they downloaded. In Codex it first removes the
CAT-owned custom agents recorded in CAT's ownership manifest, and it keeps CAT's data directory unless the user asks to
delete CAT's data as well.

Run one command, writing the installed plugin directory's absolute path in place of `<plugin>`:

- In a POSIX shell, including Git Bash on Windows, run `sh` with the arguments `<plugin>/install/cat.sh`, the
	`--harness` arguments that this skill's `SKILL.md` names, and `--uninstall`. In Codex, add the argument
	`--delete-data` only when the user asks to delete CAT's data.
- In PowerShell, where Codex runs commands on Windows, read the text of `<plugin>\install\install-codex.ps1`, create a
	script block from it, and run the script block with the argument `-Uninstall`, followed by `-DeleteData` only when
	the user asks to delete CAT's data. Do not run the file itself, which PowerShell's execution policy can block.

When the command exits with status 77, or its output says that CAT cannot write a path and gives a command to run
outside the sandbox, show the user that path and that command exactly as printed, tell them that they can run the
command in a terminal or, in Claude Code, type it at the prompt prefixed with `! `, and stop. Do not run it again, with
other permissions, or in any other way to get around the sandbox. Report any other failure with the command's output.

After the command succeeds, tell the user that CAT is uninstalled and that sessions they start from now on run without
it.
