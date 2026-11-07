# Commands for the User to Run

## Design Goals

- A command that the user runs in a shell other than this session's tool shell acts on the same harness home, project
	and user directories as this session, and sets only the environment values it needs to do so.
- The user can tell which parts of the command are required and which are a precaution.
- The session relies on the command's effect only after confirming it at the session's own paths.

## Guidance

Apply this when you tell the user to run a command in their own terminal or in any shell outside this session's tool
shell, whether the command appears in a reply, a document, a skill, or a rule. The user's terminal can have a different
`HOME` and different harness variables than this session. Without them the command acts on the account's global
harness home (for example `~/.claude`) instead of this session's.

1. Trace what the command reads. Read the command and every script it runs (for a downloaded installer, read the
	script's source). List each environment variable that chooses a path, account, harness or other target, with the
	line that reads it and the default it falls back to. For the CAT release installer `cat.sh`, the harness folder comes
	from `CLAUDE_CONFIG_DIR`, else `$HOME/.claude`, for Claude Code, and from `CODEX_HOME`, else `$HOME/.codex`, for Codex.
2. Read the session's value of each listed variable from the session, for example with `printenv NAME` in the tool
	shell; "unset" is a value. Do not use a remembered or assumed value.
3. Write into the command only the variables it needs:
	- Set a variable, with the session's value, when the user's terminal may hold a different value and the command
	  would then act elsewhere. Put the assignments on the command that runs the script (`curl … | HOME=… sh -s -- …`),
	  not on an earlier stage of a pipeline that never reads them.
	- Omit a variable whose value the script derives from a variable that the command already sets, when the session's
	  value equals that derived value. This includes a session value that is unset, when the script's default from the set
	  variable gives the session's path.
	- When the user's terminal could plausibly hold an overriding value for an omitted variable, such as an exported
	  `CLAUDE_CONFIG_DIR`, say so in one sentence beside the command ("if your terminal exports `CLAUDE_CONFIG_DIR`,
	  unset it first"). Do not add the variable to the command: that presents a precaution as a requirement.
	- A command whose every target is named by an explicit path or argument, and that reads no variable to choose a
	  target, needs no assignments.
4. After the user reports that the command finished, and before relying on its effect, check the effect at the
	session's paths: inspect the artifact the command was to create or change under the session's harness home or project
	directory (for an installer, the installed plugin and its version). If it is absent or unchanged, the command acted on
	another environment: ask the user for the command's printed output instead of continuing as if it had succeeded.

For example, when a session runs with `HOME=/home/gili/cat/home`, `CLAUDE_CONFIG_DIR` unset and
`CODEX_HOME=/home/gili/cat/home/.codex` (read the current values first), `HOME=/home/gili/cat/home` alone is the right
setup command for both harnesses: `curl -fsSL <installer URL> | HOME=/home/gili/cat/home sh -s -- --harness claude`, and
the same with `--harness codex`. `CODEX_HOME` and `CLAUDE_CONFIG_DIR` are omitted because `cat.sh` derives them from
`HOME` and the session's values equal what it derives.
