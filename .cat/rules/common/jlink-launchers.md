---
paths:
  - "client/common/distribution/src/main/java/**/PluginLaunchers.java"
  - "client/common/distribution/src/main/java/**/DistributionMain.java"
  - "client/common/distribution/src/main/java/**/PluginArtifactBuilder.java"
  - "client/**/*.bats"
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
that `PluginLaunchers` writes for every launcher, not in one launcher or program. When the caller's `LC_CTYPE` is
already UTF-8, the launcher keeps the caller's locale. Otherwise it saves the caller's `LC_ALL` and `LC_CTYPE` in
`CAT_CALLER_LC_ALL` and `CAT_CALLER_LC_CTYPE`, unsets `LC_ALL` and sets `LC_CTYPE=C.UTF-8`.

When changing how the launcher template saves or sets the caller's locale, or how a program receives its arguments on
Windows, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/jlink-launchers/programs.md`.

Every jlink launcher runs Java directly, sharing the caller's file system, including `/tmp`. Do not start Java through
Bubblewrap or another sandbox: inside a sandboxed agent session, such as Claude Code's, Bubblewrap cannot create another
namespace, so the launcher would fail. HotSpot shares a JVM's performance data through the file
`/tmp/hsperfdata_<user>/<pid>`, and JVMs in separate PID namespaces that share `/tmp` can have the same PID and would
write the same file, so the launchers' JVM options include `-XX:+PerfDisableSharedMem`, which keeps that data inside the
process. Do not hide part of the file system from a launcher or give one launcher a different template.

A launcher's program downloads a release of CAT through the proxy that `HTTPS_PROXY` names, and a proxy that asks for a
Basic login names it in the proxy URL. The JDK refuses to send a Basic login when it opens an HTTPS tunnel through a
proxy unless the property `jdk.http.auth.tunneling.disabledSchemes` is empty, and without the login such a proxy
answers 407, so the launchers' JVM options include `-Djdk.http.auth.tunneling.disabledSchemes=`, which clears the
property. Do not remove it from the options or add `Basic` back to it. The download sends the login only to the proxy
whose URL holds it.

One Bats test, `jlink-launchers.bats`, verifies the launcher template for all launchers against a built image. Do not
add a Bats test for a launcher or a harness merely to show that its launcher starts its program, runs from a path with
spaces, or works in another locale; extend that test instead when the template changes. Test a program's own behavior,
such as its arguments, output and exit status, with Java tests that call it directly.

## Launchers That Agents Run

A plugin's top-level `bin/` folder holds only the launchers that agents run, such as `plugin-info`, `get-history` and
`run-part`; hook launchers and the `java` wrapper stay in the plugin's `client/bin/`. `PluginArtifactBuilder` moves
each launcher that `DistributionMain` lists as agent-run out of `client/bin/` with `PluginLaunchers.move`, which
rewrites its path to `client/` relative to `bin/`, so `bin/<name>` starts the Java that `client/runtime-binding` names
directly. Do not put a symbolic link or a forwarding script in `bin/` instead: Codex drops symbolic links when it
installs a plugin, and a forwarding script adds a second launcher to keep in step. A moved batch launcher takes the
extension `.cmd`, the name under which CAT's Windows rules tell agents to run it.

## Windows Launchers

On Windows, `PluginLaunchers` writes the batch launcher `client/bin/<name>.bat` beside the POSIX launcher
`client/bin/<name>`. `cmd.exe` and PowerShell run the batch launcher, and Git Bash, which Claude Code runs hooks in,
runs the POSIX launcher, so both must work there. Each batch launcher has the JVM options of the POSIX launchers, and
quotes every assignment of a folder, as in `set "CLIENT=%%~fI"`, because an unquoted `&` in a folder name, such as a
user's name, ends the command there. Keep both launchers' JVM options in one constant.

On Windows, the Codex plugin holds only the batch launchers. Codex runs commands in PowerShell, and CAT's skills name a
launcher by its absolute path without an extension; PowerShell resolves that path to the POSIX launcher whenever it
exists, cannot run it, and hands it to the program that Windows associates with the file.
`DistributionMain.writeCodexLaunchers` therefore writes no POSIX launchers there, and `CodexImageLaunchersTest` runs
each launcher by that path from the shell that Codex uses on the platform that runs the test. The Claude plugin keeps
both launchers.

The JVM options include `-Dstdout.encoding=UTF-8` and `-Dstderr.encoding=UTF-8`, so a program writes UTF-8 whatever
the console's code page, and `--enable-native-access` for the module that holds `CommandLineArguments`.
