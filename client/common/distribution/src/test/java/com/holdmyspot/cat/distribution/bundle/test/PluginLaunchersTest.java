package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.distribution.bundle.Launcher;
import com.holdmyspot.cat.distribution.bundle.PluginLaunchers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests {@link PluginLaunchers}.
 */
public final class PluginLaunchersTest
{
	/**
	 * The options of every launcher's JVM: warnings on standard error only, except reports of a startup cache that
	 * Java cannot use, performance data kept in the process, UTF-8 output on every platform, Basic proxy logins allowed
	 * for HTTPS tunnels, and native access for the common module.
	 */
	private static final String VM_OPTIONS = "-Xlog:all=off:stdout -Xlog:all=warning:stderr " +
		"\"-Xlog:aot*=off:stderr\" \"-Xlog:cds*=off:stderr\" -XX:+PerfDisableSharedMem " +
		"-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 " +
		"-Djdk.http.auth.tunneling.disabledSchemes= --enable-native-access=com.holdmyspot.cat.common.cli";
	private static final Launcher READ_PART = new Launcher("read-part", "app", "example.Main");
	private static final Launcher SESSION_START = new Launcher("session-start", "app", "example.Hook");

	/** Creates a test instance. */
	public PluginLaunchersTest()
	{
	}

	/**
	 * Verifies that a plugin on Linux holds a POSIX launcher per program and the {@code java} wrapper, and no batch
	 * launchers.
	 */
	@Test
	public void linuxPluginHoldsPosixLaunchers() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("bin");
			PluginLaunchers.write(List.of(READ_PART, SESSION_START), Platform.LINUX_X64, true, bin);

			requireThat(names(bin), "launchers").isEqualTo(Set.of("java", "read-part",
				"session-start"));
			requireThat(Files.readString(bin.resolve("read-part")), "launcher").
				startsWith("#!/bin/sh\n").
				contains("exec \"$java\" ${cache:+\"$cache\"} ${tmpdir:+\"$tmpdir\"} " + VM_OPTIONS +
					" --module-path \"$bin/runtime/modules\" --add-modules org.slf4j.nop -m app/example.Main \"$@\"\n").
				contains("java=$runtime/bin/java\n").
				doesNotContain("bwrap");
			requireThat(Files.readString(bin.resolve("java")), "wrapper").
				contains("exec \"$java\" ${cache:+\"$cache\"} ${tmpdir:+\"$tmpdir\"} " + VM_OPTIONS +
					" --module-path \"$bin/runtime/modules\" \"$@\"\n");
		}
	}

	/**
	 * Verifies that a plugin on Windows whose harness runs commands in Git Bash holds both forms of each launcher, that
	 * the POSIX form runs {@code java.exe}, and that the batch form gets the same JVM options with Windows line
	 * endings.
	 */
	@Test
	public void windowsPluginHoldsBothForms() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("bin");
			PluginLaunchers.write(List.of(READ_PART), Platform.WINDOWS_X64, true, bin);

			requireThat(names(bin), "launchers").isEqualTo(Set.of("java", "java.cmd", "read-part",
				"read-part.cmd"));
			requireThat(Files.readString(bin.resolve("read-part")), "posixLauncher").
				contains("java=$runtime/bin/java.exe\n");
			String batch = Files.readString(bin.resolve("read-part.cmd"));
			requireThat(batch, "batchLauncher").
				contains("\r\n\"%JAVA%\" %CACHE% " + VM_OPTIONS + " --module-path \"%BIN%\\runtime\\modules\" " +
					"--add-modules org.slf4j.nop -m app/example.Main %*\r\n").
				contains("\r\nexit /b 3\r\n");
			requireThat(batch.replace("\r\n", ""), "lineEndings").doesNotContain("\n");
		}
	}

	/**
	 * Verifies that a plugin on Windows whose harness runs commands in PowerShell holds only the batch launchers, so
	 * that PowerShell never resolves a launcher's extensionless path to a POSIX launcher.
	 */
	@Test
	public void powerShellPluginHoldsOnlyBatchLaunchers() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("bin");
			PluginLaunchers.write(List.of(READ_PART), Platform.WINDOWS_X64, false, bin);

			requireThat(names(bin), "launchers").isEqualTo(Set.of("java.cmd", "read-part.cmd"));
		}
	}

	/**
	 * Verifies that a plugin on Linux cannot leave out its POSIX launchers.
	 */
	@Test(expectedExceptions = IllegalArgumentException.class)
	public void linuxPluginRequiresPosixLaunchers() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("bin");
			PluginLaunchers.write(List.of(READ_PART), Platform.LINUX_X64, false, bin);
		}
	}

	/**
	 * Verifies that a launcher, in both forms, finds the plugin's {@code bin/} folder as its own folder.
	 */
	@Test
	public void launchersFindTheirOwnFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("bin");
			PluginLaunchers.write(List.of(READ_PART, SESSION_START), Platform.WINDOWS_X64, true, bin);

			requireThat(Files.readString(bin.resolve("read-part")), "posixLauncher").
				contains("bin=$(CDPATH= cd -P -- \"$(dirname -- \"$self\")\" && pwd -P)").
				contains("binding=$bin/runtime-binding\n");
			requireThat(Files.readString(bin.resolve("read-part.cmd")), "batchLauncher").
				contains("for %%I in (\"%~dp0.\") do set \"BIN=%%~fI\"\r\n").
				contains("set \"BINDING=%BIN%\\runtime-binding\"\r\n");
			requireThat(names(bin), "launchers").isEqualTo(Set.of("java", "java.cmd", "read-part", "read-part.cmd",
				"session-start", "session-start.cmd"));
		}
	}

	/**
	 * Verifies that the POSIX launchers of a Windows plugin pass no {@code java.io.tmpdir}, while those of a Linux plugin
	 * pass {@code TMPDIR} as {@code java.io.tmpdir}. The plugin's Java on Windows follows {@code TEMP} and {@code TMP},
	 * and cannot use the {@code /c/...} path that Git Bash gives {@code TMPDIR}. {@code jlink-launchers.bats} runs the
	 * Linux launchers with {@code TMPDIR} set, empty and unset.
	 *
	 * @throws IOException if the launchers cannot be written or read
	 */
	@Test
	public void onlyWindowsLaunchersIgnoreTmpdir() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("plugin-launchers-"));
			Path windows = directory.resolve("windows");
			Path linux = directory.resolve("linux");
			PluginLaunchers.write(List.of(READ_PART), Platform.WINDOWS_X64, true, windows);
			PluginLaunchers.write(List.of(READ_PART), Platform.LINUX_X64, true, linux);

			for (String name : List.of("read-part", PluginLaunchers.JAVA_WRAPPER))
			{
				requireThat(Files.readString(windows.resolve(name)), name).doesNotContain("-Djava.io.tmpdir");
				requireThat(Files.readString(linux.resolve(name)), name).contains("tmpdir=-Djava.io.tmpdir=$TMPDIR\n");
			}
		}
	}

	/**
	 * Returns the names of a folder's entries.
	 *
	 * @param directory the folder
	 * @return the names
	 * @throws IOException if the folder cannot be read
	 */
	private static Set<String> names(Path directory) throws IOException
	{
		try (Stream<Path> entries = Files.list(directory))
		{
			return new TreeSet<>(entries.map(path -> path.getFileName().toString()).toList());
		}
	}
}
