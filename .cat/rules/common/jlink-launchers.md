---
paths:
  - "client/**"
---
# Jlink Launchers

## Design Goals

- Let every program that a generated jlink launcher starts name any file, whatever the caller's locale.
- Give every program the same view of the file system as its caller, so files it writes for a later call or reads on the
  caller's behalf are where the caller expects them.
- Test the launcher template that all jlink launchers share once, rather than once for each program or harness.
- Start every program on Windows with the same JVM options and the same arguments as on Linux and macOS.

## Guidance

Every jlink launcher runs Java in a UTF-8 `LC_CTYPE`, because Java names files and decodes its arguments in the
character set of `LC_CTYPE`, and the C locale cannot name a file whose name is not ASCII. Generate this in the template
that `JlinkImageBuilder` writes for every launcher, not in one launcher or program. When the caller's `LC_CTYPE` is
already UTF-8, the launcher keeps the caller's locale. Otherwise it saves the caller's `LC_ALL` and `LC_CTYPE` in
`CAT_CALLER_LC_ALL` and `CAT_CALLER_LC_CTYPE`, unsets `LC_ALL` and sets `LC_CTYPE=C.UTF-8`. A program that runs a
command on the caller's behalf, such as `run-part`, restores the caller's `LC_ALL` and `LC_CTYPE` from those variables
in the command's environment and removes them.

Every jlink launcher shares the caller's `/tmp`. When Bubblewrap is available, the template mounts an empty temporary
filesystem over only the JVM's performance-data directory, `/tmp/hsperfdata_<user>`, because JVMs in separate PID
namespaces can have the same PID and would otherwise write the same file there. Do not hide more of the file system
from a launcher, give one launcher a different template, or disable the JVM's performance data instead.

One Bats test, `jlink-launchers.bats`, verifies the launcher template for all launchers against a built image. Do not
add a Bats test for a launcher or a harness merely to show that its launcher starts its program, runs from a path with
spaces, or works in another locale; extend that test instead when the template changes. Test a program's own behavior,
such as its arguments, output and exit status, with Java tests that call it directly.

## Launchers That Agents Run

A plugin's top-level `bin/` folder holds only the launchers that agents run, such as `plugin-info`, `get-history` and
`run-part`; hook launchers, `java` and `keytool` stay in the runtime image's `client/bin/`. `PluginArtifactBuilder`
moves each launcher that `DistributionMain` lists as agent-run out of the image with `JlinkImageBuilder.moveLaunchers`,
which rewrites its path to `java` relative to `bin/`, so `bin/<name>` starts `client/bin/java` directly. Do not put a
symbolic link or a forwarding script in `bin/` instead: Codex drops symbolic links when it installs a plugin, and a
forwarding script adds a second launcher to keep in step. A moved batch launcher takes the extension `.cmd`, the name
under which CAT's Windows rules tell agents to run it.

## Windows Launchers

On Windows, jlink writes the batch launcher `bin/<name>.bat` beside the POSIX launcher `bin/<name>`. `cmd.exe` and
PowerShell run the batch launcher, and Git Bash, which Claude Code runs hooks in, runs the POSIX launcher, so both must
work there. `JlinkImageBuilder` rewrites each batch launcher too: it gives it the JVM options of the POSIX launchers,
and quotes the folder's assignment as `set "DIR=%~dp0"`, because an unquoted `&` in a folder name, such as a user's
name, ends the command there. Keep both launchers' JVM options in one constant.

On Windows, the Codex image holds only the batch launchers. Codex runs commands in PowerShell, and CAT's skills name a
launcher by its absolute path without an extension; PowerShell resolves that path to the POSIX launcher whenever it
exists, cannot run it, and hands it to the program that Windows associates with the file. `DistributionMain.codexImage`
therefore removes the POSIX launchers, and `CodexImageLaunchersTest` runs each launcher by that path from the shell
that Codex uses on the platform that runs the test. The Claude image keeps both launchers.

The JVM options include `-Dstdout.encoding=UTF-8` and `-Dstderr.encoding=UTF-8`, so a program writes UTF-8 whatever
the console's code page, and `--enable-native-access` for the module that holds `CommandLineArguments`. The `java`
launcher reads its command line in the system's ANSI code page and replaces each character that the code page lacks;
every `main` that reads file names or text from its arguments passes them through `CommandLineArguments.recover`,
which reads them again from the process's UTF-16 command line.

A batch file expands `%NAME%` in its own arguments when `NAME` is a defined variable, and an unquoted `&` in an
argument ends the command, so a caller must not pass untrusted text to a batch launcher as an argument; pass it on
standard input or in a file instead.

Codex runs a hook's `commandWindows` in PowerShell, not `cmd.exe`, so the command starts a `.cmd` launcher with the
call operator, `& "$env:PLUGIN_ROOT\hooks\<name>.cmd"`. A quoted path without `&` is only a string, which PowerShell
prints. Codex also writes the plugin's directory into the command in place of `${PLUGIN_ROOT}`, where a `$` or a quote
in the directory's name would be read as syntax, so a Codex hook's command names the directory through the environment
variable `PLUGIN_ROOT` instead, on every platform. `CodexPluginHooksTest` runs each hook's command as Codex does on the
platform that runs the test.

Claude Code runs a hook's `command` in a POSIX shell, Git Bash on Windows, and the shell expands
`${CLAUDE_PLUGIN_ROOT}`, so the command double-quotes it, as in `"${CLAUDE_PLUGIN_ROOT}/hooks/<name>.sh"`; a space in
the plugin's path, such as one in a user's name, otherwise splits the command. `ClaudePluginHooksTest` runs each hook's
command from a plugin path that holds a space.
