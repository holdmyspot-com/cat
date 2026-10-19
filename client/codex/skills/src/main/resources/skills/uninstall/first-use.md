# Uninstall CAT

## Design Goals

- Remove CAT-owned user agents before the Codex plugin is removed.
- Keep CAT's data unless the user asks to delete it, so a later install can migrate it.

## Guidance

Use CAT's uninstaller. It removes only the files recorded in CAT's ownership manifest, then removes the plugin and its
marketplace registration. It keeps CAT's data directory unless the user asks to delete CAT's data as well.

The uninstaller for each shell is in
`https://raw.githubusercontent.com/holdmyspot-com/cat/HEAD/client/codex/lifecycle/src/main/resources/install/`:

- In PowerShell, download `bootstrap-codex.ps1` from there with `Invoke-RestMethod`, create a script block from its
	text, and run the script block with the argument `-Uninstall`, followed by `-DeleteData` when the user asks to delete
	CAT's data. Do not save it as a file and run the file, which PowerShell's execution policy can block.
- In a POSIX shell, download `bootstrap-uninstall-codex.sh` from there with `curl -fsSL` and pipe it to `sh`, giving
	`sh` the arguments `-s -- --delete-data` when the user asks to delete CAT's data.
