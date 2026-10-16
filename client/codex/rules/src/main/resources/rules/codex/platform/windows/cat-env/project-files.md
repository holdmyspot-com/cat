Write files only inside `${CAT_PROJECT_ROOT}` unless the user names a location outside it. Put a scratch file, probe
input, or saved command output in a temporary directory under `${CAT_TEMP}`, not in the system's temporary directory,
the home directory, or another directory outside the project. When you know that a command you run writes its own
temporary files to the system's temporary directory, point it at that directory through its `TEMP` and `TMP` variables
or equivalent setting.
