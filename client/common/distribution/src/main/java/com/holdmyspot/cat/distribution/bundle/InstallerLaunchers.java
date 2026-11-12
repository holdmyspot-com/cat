package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes the launchers in a plugin's {@code bin} folder that run the plugin's installer on the Java runtime in the
 * plugin's {@code bin/runtime} folder, with the plugin's modules and fixed JVM options. The launchers discard the JVM
 * options that the environment supplies, and exit with status 3, writing one line that names the missing file, when
 * the plugin's runtime has no {@code java}. The POSIX launcher of a plugin on a platform other than Windows passes a
 * set, non-empty {@code TMPDIR} to Java as {@code -Djava.io.tmpdir}, because Java on Linux ignores {@code TMPDIR} and a
 * sandboxed session cannot write to {@code /tmp}. Java on Windows follows {@code TEMP} and {@code TMP} and does not
 * understand the {@code /c/Users/...} {@code TMPDIR} that Git Bash gives the POSIX launcher, so a Windows plugin's
 * launcher passes nothing. The installer receives the caller's PATH and working directory.
 */
final class InstallerLaunchers
{
	/**
	 * The path from the plugin's {@code bin/} folder to its Java runtime, with {@code /} separators.
	 */
	private static final String RUNTIME_FROM_BIN = PluginDirectory.BIN.relativize(PluginDirectory.RUNTIME).
		toString().replace('\\', '/');
	/**
	 * The path from the plugin's {@code bin/} folder to its modules, with {@code /} separators.
	 */
	private static final String MODULES_FROM_BIN = PluginDirectory.BIN.relativize(PluginDirectory.MODULES).
		toString().replace('\\', '/');
	/**
	 * The lines of the POSIX launcher that pass {@code TMPDIR} to Java, in the launcher of a plugin on a platform other
	 * than Windows.
	 */
	private static final String PASS_TMPDIR = """
		if [ -n "${TMPDIR:-}" ]; then
			tmpdir=-Djava.io.tmpdir=$TMPDIR
		fi""";
	/**
	 * The line that stands in the POSIX launcher of a Windows plugin for the lines that pass {@code TMPDIR}.
	 */
	private static final String IGNORE_TMPDIR =
		"# Java on Windows follows TEMP and TMP, and does not read Git Bash's TMPDIR.";
	/**
	 * The POSIX launcher, in which {@code @RUNTIME@} and {@code @MODULES@} stand for the paths from {@code bin/} to the
	 * runtime and to the modules, and {@code @TMPDIR@} for the lines that pass {@code TMPDIR} to Java.
	 */
	private static final String POSIX_TEMPLATE = """
		#!/bin/sh
		# Runs the plugin's installer on the Java runtime in the plugin's bin/@RUNTIME@ folder, with fixed JVM options;
		# environment-provided JVM options are discarded.
		# Arguments: the installer operation and its arguments.
		# Output: the installer's result as one JSON line; a person at a terminal reads a plain-text report instead, unless
		# the installer runs in a harness's agent session or receives --json.
		# Exit status: the installer operation's status, or 3 when the plugin's runtime has no executable java.
		set -eu
		unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH JLINK_VM_OPTIONS
		# The bin folder is found from this file's real location, so a symbolic link to the launcher elsewhere still runs
		# the runtime and modules that belong to it. The links are read with a fixed search path, so the caller's PATH
		# cannot choose that program; the installer still receives the caller's PATH and working directory, because it
		# finds the harness program on that PATH.
		caller_path=${PATH+"+$PATH"}
		PATH=/usr/bin:/bin
		launcher=$0
		case $launcher in
			*/*) ;;
			*) launcher=./$launcher ;;
		esac
		while [ -L "$launcher" ]; do
			target=$(readlink -- "$launcher")
			case $target in
				/*) launcher=$target ;;
				*) launcher=${launcher%/*}/$target ;;
			esac
		done
		case $caller_path in
			+*) PATH=${caller_path#+} ;;
			*) unset PATH ;;
		esac
		bin=$(CDPATH='' cd -P -- "${launcher%/*}" && pwd -P)
		runtime=$bin/@RUNTIME@
		java=$runtime/bin/java
		if [ ! -f "$java" ] || [ ! -x "$java" ]; then
			printf 'CAT installer runtime is missing: %s is not an executable file; reinstall CAT.\\n' "$java" >&2
			exit 3
		fi
		case ${LC_ALL:-${LC_CTYPE:-${LANG:-}}} in
			*[Uu][Tt][Ff]-8* | *[Uu][Tt][Ff]8*) ;;
			*)
				CAT_CALLER_LC_ALL=${LC_ALL+"+$LC_ALL"}
				CAT_CALLER_LC_CTYPE=${LC_CTYPE+"+$LC_CTYPE"}
				export CAT_CALLER_LC_ALL="${CAT_CALLER_LC_ALL:--}"
				export CAT_CALLER_LC_CTYPE="${CAT_CALLER_LC_CTYPE:--}"
				unset LC_ALL
				export LC_CTYPE=C.UTF-8
				;;
		esac
		tmpdir=
		@TMPDIR@
		exec "$java" ${tmpdir:+"$tmpdir"} -Xshare:auto -Xlog:all=off:stdout -Xlog:all=warning:stderr \\
			-XX:+PerfDisableSharedMem -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \\
			-Djdk.http.auth.tunneling.disabledSchemes= \\
			--enable-native-access=com.holdmyspot.cat.common.cli \\
			-Djava.util.logging.config.file="$runtime/conf/logging.properties" \\
			--module-path "$bin/@MODULES@" --add-modules org.slf4j.nop \\
			-m com.holdmyspot.cat.common.installer/com.holdmyspot.cat.common.installer.cli.InstallerMain "$@"
		""";
	/**
	 * The PowerShell launcher, in which {@code @RUNTIME@} and {@code @MODULES@} stand for the paths from {@code bin\} to
	 * the runtime and to the modules.
	 */
	private static final String POWERSHELL_TEMPLATE = """
		# Runs the plugin's installer on Windows on the Java runtime in the plugin's bin\\@RUNTIME@ folder, with fixed JVM
		# options; environment-provided JVM options are discarded. The installer receives the caller's PATH and working
		# directory, because it finds the harness program on that PATH.
		# Arguments: the installer operation and its arguments, forwarded to Java without interpretation.
		# Output: the installer's result as one JSON line; a person at a terminal reads a plain-text report instead, unless
		# the installer runs in a harness's agent session or receives --json.
		# Exit status: the installer operation's status, or 3 when the plugin's runtime has no java.exe.
		Set-StrictMode -Version 3.0
		$ErrorActionPreference = 'Stop'

		# Quotes one argument so that a program built with the Microsoft C runtime, such as java.exe, reads it back
		# unchanged.
		# Arguments: the argument.
		# Output: the quoted argument.
		function ConvertTo-CommandLineArgument([string] $argument)
		{
			if ($argument.Length -gt 0 -and $argument -notmatch '[\\s"]')
			{
				return $argument
			}
			# Backslashes are plain characters unless they precede a quotation mark, where each one must be doubled.
			$escaped = [regex]::Replace($argument, '(\\\\*)"', { param($match) $match.Groups[1].Value * 2 + '\\"' })
			$escaped = [regex]::Replace($escaped, '(\\\\+)$', { param($match) $match.Groups[1].Value * 2 })
			return '"' + $escaped + '"'
		}

		$runtime = Join-Path $PSScriptRoot '@RUNTIME@'
		$java = Join-Path $runtime 'bin\\java.exe'
		if (-not (Test-Path -LiteralPath $java -PathType Leaf))
		{
			$message = "CAT installer runtime is missing: $java is not a file; reinstall CAT.`n"
			$bytes = (New-Object Text.UTF8Encoding $false).GetBytes($message)
			$stream = [Console]::OpenStandardError()
			$stream.Write($bytes, 0, $bytes.Length)
			$stream.Flush()
			exit 3
		}

		$javaArguments = @('-Xshare:auto', '-Xlog:all=off:stdout', '-Xlog:all=warning:stderr',
			'-XX:+PerfDisableSharedMem', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
			'-Djdk.http.auth.tunneling.disabledSchemes=', '--enable-native-access=com.holdmyspot.cat.common.cli',
			"-Djava.util.logging.config.file=$(Join-Path $runtime 'conf\\logging.properties')",
			'--module-path', (Join-Path $PSScriptRoot '@MODULES@'), '--add-modules', 'org.slf4j.nop',
			'-m', 'com.holdmyspot.cat.common.installer/com.holdmyspot.cat.common.installer.cli.InstallerMain') + $args
		$start = New-Object Diagnostics.ProcessStartInfo
		$start.FileName = $java
		$start.Arguments = ($javaArguments | ForEach-Object { ConvertTo-CommandLineArgument $_ }) -join ' '
		$start.UseShellExecute = $false
		# PowerShell's location can differ from the process's working directory, so the caller's location is passed on.
		$start.WorkingDirectory = (Get-Location -PSProvider FileSystem).ProviderPath
		# Java inherits this launcher's standard streams, so its output reaches the caller unchanged.
		foreach ($name in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH', 'JLINK_VM_OPTIONS'))
		{
			$start.EnvironmentVariables.Remove($name)
		}
		$process = [Diagnostics.Process]::Start($start)
		$process.WaitForExit()
		exit $process.ExitCode
		""";
	/**
	 * Launchers are executable by their owner and by users allowed to read the plugin.
	 */
	private static final Set<PosixFilePermission> LAUNCHER_PERMISSIONS =
		Set.copyOf(PosixFilePermissions.fromString("rwxr-xr-x"));

	/**
	 * Writes the installer's launchers: {@code installer} when the plugin has POSIX launchers, and
	 * {@code installer.ps1} on Windows.
	 *
	 * @param bin      the folder that becomes the plugin's {@code bin} folder
	 * @param platform the platform that the plugin runs on
	 * @param posix    {@code true} to write the POSIX launcher; {@code false} only for a plugin on Windows whose harness
	 *                 runs commands in PowerShell, which would resolve the launcher's extensionless path to the POSIX
	 *                 launcher that it cannot run
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code posix} is {@code false} on a platform other than Windows
	 * @throws IOException              if a launcher cannot be written
	 */
	static void write(Path bin, Platform platform, boolean posix) throws IOException
	{
		requireThat(bin, "bin").isNotNull();
		requireThat(platform, "platform").isNotNull();
		if (!posix && !platform.isWindows())
			throw new IllegalArgumentException("A plugin on " + platform.id() + " needs its POSIX installer launcher");
		Path launcher = bin.resolve(PluginDirectory.BIN.relativize(PluginDirectory.INSTALLER));
		Files.createDirectories(bin);
		if (posix)
		{
			String tmpdirLines = PASS_TMPDIR;
			if (platform.isWindows())
				tmpdirLines = IGNORE_TMPDIR;
			Files.writeString(launcher, substitute(POSIX_TEMPLATE, '/').replace("@TMPDIR@", tmpdirLines), UTF_8);
			PosixFileAttributeView attributes = Files.getFileAttributeView(launcher, PosixFileAttributeView.class);
			if (attributes != null)
				attributes.setPermissions(LAUNCHER_PERMISSIONS);
		}
		if (platform.isWindows())
		{
			Path powerShell = launcher.resolveSibling(launcher.getFileName() + ".ps1");
			Files.writeString(powerShell, substitute(POWERSHELL_TEMPLATE, '\\'), UTF_8);
		}
	}

	/**
	 * Fills in a template's paths.
	 *
	 * @param template  the template
	 * @param separator the separator of the launcher's paths
	 * @return the launcher
	 */
	private static String substitute(String template, char separator)
	{
		return template.replace("@RUNTIME@", RUNTIME_FROM_BIN.replace('/', separator)).
			replace("@MODULES@", MODULES_FROM_BIN.replace('/', separator));
	}

	/**
	 * Prevents instantiation.
	 */
	private InstallerLaunchers()
	{
	}
}
