# Update CAT

## Design Goals

- Install another CAT release only when it differs from the installed one or the user names it, so that an update with
	nothing to do changes nothing.
- Install with the installer that CAT's installed plugin already holds, which detects the harness and checks that it
	can write every path it changes before it changes any.
- Leave a change that the agent's sandbox blocks to the user, instead of working around the sandbox.
- Give the user the exact command that a blocked change needs, so that they can run it without asking for it.

## Guidance

CAT's installed plugin directory is two levels above this skill's directory. Write its absolute path in place of
`<plugin>` below.

1. Find the installed version: run `bin/plugin-info` in the installed plugin directory by its absolute path. Its
	`version=` line names the installed version, such as `1.0`, whose release tag is `v1.0`.
2. Choose the release. When the user names a version, use its release tag, such as `v1.1`, and go to step 3 even when
	it is the installed version. Otherwise read the latest release's tag from the `tag_name` field of the JSON at
	`https://api.github.com/repos/holdmyspot-com/cat/releases/latest`, which `curl -fsSL` with that URL writes in a
	POSIX shell and `Invoke-RestMethod` with that URL returns in PowerShell. When that tag names the installed version,
	tell the user that the installed CAT is the latest release, and stop. When the tag cannot be read, report why and
	stop.
3. Install the release:
	- In a POSIX shell, including Git Bash on Windows, run `sh` with the arguments `<plugin>/install/cat.sh`, the
		`--harness` arguments that this skill's `SKILL.md` names, and the release tag.
	- In PowerShell, where Codex runs commands on Windows, read the text of `<plugin>\install\bootstrap-codex.ps1`,
		create a script block from it, and run the script block with the arguments `-Version` and the release tag. Do not
		run the file itself, which PowerShell's execution policy can block.

When the command exits with status 77, or its output says that CAT cannot write a path and gives a command to run
outside the sandbox, end your turn with a reply that names that path, shows the command in a code block character for
character as printed, never as a summary or a description of it, and asks the user to run it in a terminal outside the
sandbox or, in Claude Code only, to type it at the prompt prefixed with `! `. Do no other work in that turn, including
work the user asked for earlier. Do not run it again, with other permissions, or in any other way to get around the
sandbox. Report any other failure with the command's output.

After the command succeeds, tell the user which release is installed and that it takes effect once they restart or
resume the session.
