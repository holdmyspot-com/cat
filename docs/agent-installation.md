# Install CAT: instructions for AI agents

If you are a person, follow the [getting-started guide](installation.md) instead.

If CAT's `update` skill is installed, use it. Otherwise leave the installation to the user. For a standard
installation, point them to the command for their harness and shell in the [getting-started guide](installation.md).

When the target session uses a different home or harness configuration directory, have code select the command from
that guide, read the required session paths, and write a runnable command file with values quoted for the receiving
shell. On Linux or macOS, compare the session home with the account's home and set `HOME` only when they differ or
the account's home cannot be determined. On Windows, use the harness configuration directory override when needed.
Ask the user to run the generated file from a terminal that can read it. If the file cannot be delivered to that
terminal, direct the user to the guide's custom-directory instructions and tell them which setting to check.
