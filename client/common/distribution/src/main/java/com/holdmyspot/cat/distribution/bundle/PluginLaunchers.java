package com.holdmyspot.cat.distribution.bundle;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.StartupCache;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Writes the launchers of a plugin's {@code bin} folder, which start the plugin's Java programs on the installation's
 * Java runtime that the installation-written file {@code bin/runtime-binding} names.
 * <p>
 * A launcher finds {@code bin/} from its own real folder, reads the binding, and runs nothing when the binding is
 * missing, holds anything but one line naming an absolute, normalized path, or names a runtime without an executable
 * {@code bin/java}: it writes one line naming the binding to standard error and exits with status
 * {@value #UNUSABLE_BINDING_STATUS}. Otherwise it runs {@code java} with fixed JVM options, the plugin's
 * {@code bin/runtime/modules} as the module path, {@code org.slf4j.nop} added so that logging never depends on
 * incidental service binding, and the program's module and main class, followed by the caller's arguments. It discards
 * the JVM options that the environment can supply, and the POSIX launcher also discards the variables by which the
 * environment can load code into Java or the shells that Java starts: {@code LD_PRELOAD}, {@code LD_LIBRARY_PATH},
 * {@code LD_AUDIT}, {@code BASH_ENV}, {@code ENV} and every {@code DYLD_} variable.
 * <p>
 * When the plugin holds its installation-written {@link StartupCache startup cache}, a launcher passes it to Java with
 * {@code -XX:AOTCache}. Java skips a cache that it cannot use, such as one trained at another path or a truncated one,
 * and the launcher's logging options keep Java from reporting that, so a cache never changes what the program's caller
 * sees.
 * <p>
 * On Linux, Java ignores {@code TMPDIR} and keeps its temporary files in {@code /tmp}, which a sandboxed session
 * cannot write to. When {@code TMPDIR} is set and not empty, a POSIX launcher therefore passes it to Java as
 * {@code -Djava.io.tmpdir}; it passes nothing when {@code TMPDIR} is unset or empty. On Windows, Java already follows
 * {@code TEMP} and {@code TMP}, so a batch launcher passes nothing.
 * <p>
 * Java names files and decodes its arguments in the character set of {@code LC_CTYPE}, which in the C locale cannot
 * name a file whose name is not ASCII. When the caller's {@code LC_CTYPE} is not UTF-8, a POSIX launcher therefore runs
 * Java in {@code LC_CTYPE=C.UTF-8}. It keeps the caller's {@code LC_ALL} and {@code LC_CTYPE} in
 * {@code CAT_CALLER_LC_ALL} and {@code CAT_CALLER_LC_CTYPE}, each {@code +} followed by the value or {@code -} when it
 * is unset, so that a program can run a command in the caller's locale. A caller whose locale is already UTF-8 keeps
 * it, so that the programs that Java starts inherit it unchanged. A launcher runs Java directly, sharing its caller's
 * file system, so it also runs inside a sandbox that cannot create another namespace.
 * <p>
 * On Windows, a batch launcher {@code <name>.cmd} accompanies each POSIX launcher: {@code cmd.exe} and PowerShell run
 * the batch launcher, and Git Bash, which Claude Code runs hooks in, runs the POSIX launcher. A batch launcher cannot
 * resolve symbolic links in its own path and accepts a binding whose only line lacks its line ending; it applies the
 * other checks of the POSIX launcher.
 */
public final class PluginLaunchers
{
	/**
	 * The exit status of a launcher whose runtime binding is unusable.
	 */
	public static final int UNUSABLE_BINDING_STATUS = 3;
	/**
	 * The name of the wrapper that runs the bound {@code java} with the plugin's module path.
	 */
	public static final String JAVA_WRAPPER = "java";
	/**
	 * The options of every launcher's JVM. The CLI's standard output carries structured responses, so JVM warnings go to
	 * standard error instead of corrupting that channel. HotSpot shares its performance data through the file
	 * {@code /tmp/hsperfdata_<user>/<pid>}, and JVMs in separate PID namespaces, such as two sandboxed sessions that
	 * share {@code /tmp}, can have the same PID and contend for that file; keeping the data in the process avoids that
	 * contention, at the cost of tools such as {@code jps} and {@code jstat} not seeing the JVM. Java writes its
	 * standard streams in the console's or the system's character set, which on Windows is not UTF-8, while the
	 * harnesses read UTF-8. On Windows, the common module reads the program's arguments from the process's command line
	 * through native code, because the Java launcher loses the characters that the system's code page lacks. A host
	 * that reaches the network only through a proxy that asks for a Basic login names that login in its proxy URL, and
	 * the JDK refuses to send a Basic login when it opens an HTTPS tunnel through a proxy unless the property
	 * {@code jdk.http.auth.tunneling.disabledSchemes} is empty, so without it a release download from such a host
	 * fails. The empty value stays one word in both launchers, because neither quotes nor splits within an option.
	 * Java reports a startup cache that it cannot use in its {@code aot} and {@code cds} logs; the options that turn
	 * those logs off follow the one that shows warnings and errors, because a later option overrides an earlier one
	 * for the same output. They are quoted, so that the POSIX shell never expands their {@code *} as a file name pattern.
	 */
	private static final String VM_OPTIONS = "-Xlog:all=off:stdout -Xlog:all=warning:stderr " +
		"\"-Xlog:aot*=off:stderr\" \"-Xlog:cds*=off:stderr\" -XX:+PerfDisableSharedMem " +
		"-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 " +
		"-Djdk.http.auth.tunneling.disabledSchemes= --enable-native-access=com.holdmyspot.cat.common.cli";
	/**
	 * The modules that every program's launcher adds to the module graph.
	 */
	private static final List<String> ADDED_MODULES = List.of("--add-modules", "org.slf4j.nop");
	/**
	 * The path from the plugin's {@code bin/} folder to the startup cache.
	 */
	private static final String CACHE_FROM_BIN = PluginDirectory.BIN.relativize(StartupCache.FILE).toString();
	/**
	 * The path from the plugin's {@code bin/} folder to the plugin's application modules.
	 */
	private static final String MODULES_FROM_BIN = PluginDirectory.BIN.relativize(PluginDirectory.MODULES).toString();
	private static final String POSIX_TEMPLATE = """
		#!/bin/sh
		# Starts %1$s on the Java runtime that the plugin's bin/runtime-binding names.
		unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH JLINK_VM_OPTIONS
		unset LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT BASH_ENV ENV
		for name in $(env | sed -n 's/=.*//p'); do
		  case $name in
		    DYLD_*[!A-Za-z0-9_]*) ;;
		    DYLD_*) unset "$name" ;;
		  esac
		done
		self=$0
		while [ -L "$self" ]; do
		  link=$(readlink -- "$self")
		  case $link in
		    /*) self=$link ;;
		    *) self=$(dirname -- "$self")/$link ;;
		  esac
		done
		bin=$(CDPATH= cd -P -- "$(dirname -- "$self")" && pwd -P) || exit %3$d
		binding=$bin/runtime-binding
		fail()
		{
		  printf 'CAT cannot start: %%s %%s; run the CAT entry command again to repair the installation.\\n' \\
		    "$binding" "$1" >&2
		  exit %3$d
		}
		[ -f "$binding" ] && [ -r "$binding" ] || fail 'is missing'
		extra=
		{ IFS= read -r runtime && ! IFS= read -r extra && [ -z "$extra" ]; } < "$binding" ||
		  fail 'must hold exactly one line, the absolute path of a Java runtime'
		case $runtime in
		  /* | [A-Za-z]:[\\\\/]*) ;;
		  *) fail 'names a relative path' ;;
		esac
		case $runtime in
		  ?*[\\\\/] | *[\\\\/][\\\\/]* | *[\\\\/]. | *[\\\\/].. | *[\\\\/].[\\\\/]* | *[\\\\/]..[\\\\/]*)
		    fail 'names a path that is not normalized' ;;
		esac
		java=$runtime/bin/%2$s
		[ -f "$java" ] && [ -x "$java" ] || fail 'names a runtime without an executable bin/%2$s'
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
		cache=
		if [ -f "$bin/%6$s" ]; then
		  cache=-XX:AOTCache=$bin/%6$s
		fi
		tmpdir=
		if [ -n "${TMPDIR:-}" ]; then
		  tmpdir=-Djava.io.tmpdir=$TMPDIR
		fi
		exec "$java" ${cache:+"$cache"} ${tmpdir:+"$tmpdir"} %4$s --module-path "$bin/%7$s" %5$s"$@"
		""";
	private static final String BATCH_TEMPLATE = """
		@echo off
		rem Starts %1$s on the Java runtime that the plugin's bin\\runtime-binding names.
		setlocal DisableDelayedExpansion
		set JAVA_TOOL_OPTIONS=
		set JDK_JAVA_OPTIONS=
		set _JAVA_OPTIONS=
		set CLASSPATH=
		set JLINK_VM_OPTIONS=
		for %%%%I in ("%%~dp0.") do set "BIN=%%%%~fI"
		set "BINDING=%%BIN%%\\runtime-binding"
		set "RUNTIME="
		set "LINES=0"
		if not exist "%%BINDING%%" (
		  set "REASON=is missing"
		  goto fail
		)
		set "REASON=must hold exactly one line, the absolute path of a Java runtime"
		for /f "usebackq delims=" %%%%L in (`findstr /n "^" "%%BINDING%%"`) do (
		  set /a LINES+=1
		  if not defined RUNTIME set "RUNTIME=%%%%L"
		)
		if not "%%LINES%%"=="1" goto fail
		set "RUNTIME=%%RUNTIME:*:=%%"
		if not defined RUNTIME goto fail
		set "REASON=names a relative path"
		if not "%%RUNTIME:~1,2%%"==":\\" goto fail
		set "REASON=names a path that is not normalized"
		if not "%%RUNTIME:/=%%"=="%%RUNTIME%%" goto fail
		if not "%%RUNTIME:\\\\=%%"=="%%RUNTIME%%" goto fail
		if not "%%RUNTIME:\\.\\=%%"=="%%RUNTIME%%" goto fail
		if not "%%RUNTIME:\\..\\=%%"=="%%RUNTIME%%" goto fail
		if "%%RUNTIME:~-1%%"=="\\" goto fail
		if "%%RUNTIME:~-2%%"=="\\." goto fail
		if "%%RUNTIME:~-3%%"=="\\.." goto fail
		set "JAVA=%%RUNTIME%%\\bin\\java.exe"
		set "REASON=names a runtime without bin\\java.exe"
		if not exist "%%JAVA%%" goto fail
		set "CACHE="
		if exist "%%BIN%%\\%5$s" set CACHE="-XX:AOTCache=%%BIN%%\\%5$s"
		"%%JAVA%%" %%CACHE%% %2$s --module-path "%%BIN%%\\%6$s" %3$s%%*
		exit /b %%ERRORLEVEL%%
		:fail
		setlocal EnableDelayedExpansion
		>&2 echo(CAT cannot start: !BINDING! !REASON!; run the CAT entry command again to repair the installation.
		exit /b %4$d
		""";
	/**
	 * Launchers are executable by their owner and by users allowed to read the plugin.
	 */
	private static final Set<PosixFilePermission> LAUNCHER_PERMISSIONS =
		Set.copyOf(PosixFilePermissions.fromString("rwxr-xr-x"));

	/**
	 * Prevents instantiation.
	 */
	private PluginLaunchers()
	{
	}

	/**
	 * Returns the arguments that a launcher passes to the {@value #JAVA_WRAPPER} wrapper's options to start its
	 * program, before the launcher's own arguments.
	 *
	 * @param launcher the launcher
	 * @return the arguments
	 * @throws NullPointerException if {@code launcher} is null
	 */
	public static List<String> programArguments(Launcher launcher)
	{
		requireThat(launcher, "launcher").isNotNull();
		List<String> arguments = new ArrayList<>(ADDED_MODULES);
		arguments.add("-m");
		arguments.add(launcher.module() + "/" + launcher.mainClass());
		return List.copyOf(arguments);
	}

	/**
	 * Writes the launchers of a plugin's {@code bin} folder and the {@value #JAVA_WRAPPER} wrapper, which runs the bound
	 * {@code java} with the launchers' JVM options and the plugin's module path before its own arguments. On Windows,
	 * each launcher also has a batch form, {@code <name>.cmd}.
	 *
	 * @param launchers the programs' launchers
	 * @param platform  the platform that the plugin runs on
	 * @param posix     {@code true} to write the POSIX launchers; {@code false} only for a plugin on Windows whose
	 *                  harness runs commands in PowerShell, which would resolve a launcher's extensionless path to the
	 *                  POSIX launcher that it cannot run
	 * @param bin       the folder that receives the launchers, which becomes the plugin's {@code bin} folder
	 * @throws NullPointerException     if any argument is null
	 * @throws IllegalArgumentException if {@code posix} is {@code false} on a platform other than Windows, or a launcher
	 *                                  is named {@value #JAVA_WRAPPER}
	 * @throws IOException              if a launcher cannot be written
	 */
	public static void write(List<Launcher> launchers, Platform platform, boolean posix, Path bin)
		throws IOException
	{
		requireThat(launchers, "launchers").isNotNull();
		requireThat(platform, "platform").isNotNull();
		requireThat(bin, "bin").isNotNull();
		if (!posix && !platform.isWindows())
			throw new IllegalArgumentException("A plugin on " + platform.id() + " needs its POSIX launchers");
		for (Launcher launcher : launchers)
		{
			if (launcher.name().equals(JAVA_WRAPPER))
				throw new IllegalArgumentException("A launcher may not replace the java wrapper: " + launcher);
		}

		Files.createDirectories(bin);
		String javaName = "java";
		if (platform.isWindows())
			javaName = "java.exe";
		for (Launcher launcher : launchers)
		{
			String program = launcher.module() + "/" + launcher.mainClass();
			String arguments = String.join(" ", programArguments(launcher)) + " ";
			if (posix)
				writePosix(bin.resolve(launcher.name()), program, javaName, arguments);
			if (platform.isWindows())
				writeBatch(bin.resolve(launcher.name() + ".cmd"), program, arguments);
		}
		String wrapper = "java with the plugin's modules";
		if (posix)
			writePosix(bin.resolve(JAVA_WRAPPER), wrapper, javaName, "");
		if (platform.isWindows())
			writeBatch(bin.resolve(JAVA_WRAPPER + ".cmd"), wrapper, "");
	}

	/**
	 * Writes one POSIX launcher.
	 *
	 * @param path      the launcher's path
	 * @param program   the program that the launcher's comment names
	 * @param javaName  the file name of {@code java} in the runtime's {@code bin} folder
	 * @param arguments the arguments, each followed by a space, that precede the caller's
	 * @throws IOException if the launcher cannot be written
	 */
	private static void writePosix(Path path, String program, String javaName, String arguments)
		throws IOException
	{
		Files.writeString(path, POSIX_TEMPLATE.formatted(program, javaName, UNUSABLE_BINDING_STATUS, VM_OPTIONS,
			arguments, CACHE_FROM_BIN.replace('\\', '/'), MODULES_FROM_BIN.replace('\\', '/')), UTF_8);
		PosixFileAttributeView attributes = Files.getFileAttributeView(path, PosixFileAttributeView.class);
		if (attributes != null)
			attributes.setPermissions(LAUNCHER_PERMISSIONS);
	}

	/**
	 * Writes one batch launcher with the line endings that {@code cmd.exe} requires.
	 *
	 * @param path      the launcher's path
	 * @param program   the program that the launcher's comment names
	 * @param arguments the arguments, each followed by a space, that precede the caller's
	 * @throws IOException if the launcher cannot be written
	 */
	private static void writeBatch(Path path, String program, String arguments) throws IOException
	{
		String content = BATCH_TEMPLATE.formatted(program, VM_OPTIONS, arguments, UNUSABLE_BINDING_STATUS,
			CACHE_FROM_BIN.replace('/', '\\'), MODULES_FROM_BIN.replace('/', '\\'));
		Files.writeString(path, content.replace("\n", "\r\n"), UTF_8);
	}
}
