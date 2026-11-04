package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

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
import org.testng.SkipException;
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
			Path client = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("client");
			PluginLaunchers.write(List.of(READ_PART, SESSION_START), Platform.LINUX_X64, true, client);

			requireThat(names(client.resolve("bin")), "launchers").isEqualTo(Set.of("java", "read-part",
				"session-start"));
			requireThat(Files.readString(client.resolve("bin/read-part")), "launcher").
				startsWith("#!/bin/sh\n").
				contains("exec \"$java\" ${cache:+\"$cache\"} " + VM_OPTIONS + " --module-path \"$client/modules\" " +
					"--add-modules org.slf4j.nop -m app/example.Main \"$@\"\n").
				contains("java=$runtime/bin/java\n").
				doesNotContain("bwrap");
			requireThat(Files.readString(client.resolve("bin/java")), "wrapper").
				contains("exec \"$java\" ${cache:+\"$cache\"} " + VM_OPTIONS +
					" --module-path \"$client/modules\" \"$@\"\n");
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
			Path client = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("client");
			PluginLaunchers.write(List.of(READ_PART), Platform.WINDOWS_X64, true, client);

			requireThat(names(client.resolve("bin")), "launchers").isEqualTo(Set.of("java", "java.cmd", "read-part",
				"read-part.bat"));
			requireThat(Files.readString(client.resolve("bin/read-part")), "posixLauncher").
				contains("java=$runtime/bin/java.exe\n");
			String batch = Files.readString(client.resolve("bin/read-part.bat"));
			requireThat(batch, "batchLauncher").
				contains("\r\n\"%JAVA%\" %CACHE% " + VM_OPTIONS + " --module-path \"%CLIENT%\\modules\" " +
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
			Path client = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("client");
			PluginLaunchers.write(List.of(READ_PART), Platform.WINDOWS_X64, false, client);

			requireThat(names(client.resolve("bin")), "launchers").isEqualTo(Set.of("java.cmd", "read-part.bat"));
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
			Path client = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("client");
			PluginLaunchers.write(List.of(READ_PART), Platform.LINUX_X64, false, client);
		}
	}

	/**
	 * Verifies that a moved launcher, in both forms, finds {@code client/} from its new folder, that its batch form
	 * takes the extension {@code .cmd}, and that the launchers that do not move stay in {@code client/bin}.
	 */
	@Test
	public void movedLaunchersFindClient() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("plugin-launchers-"));
			Path client = plugin.resolve("client");
			PluginLaunchers.write(List.of(READ_PART, SESSION_START), Platform.WINDOWS_X64, true, client);

			PluginLaunchers.move(Set.of("read-part"), client, plugin.resolve("bin"));

			requireThat(Files.readString(plugin.resolve("bin/read-part")), "posixLauncher").
				contains("client=$(CDPATH= cd -P -- \"$(dirname -- \"$self\")/../client\" && pwd -P)");
			requireThat(Files.readString(plugin.resolve("bin/read-part.cmd")), "batchLauncher").
				contains("for %%I in (\"%~dp0..\\client\") do set \"CLIENT=%%~fI\"\r\n");
			requireThat(names(plugin.resolve("bin")), "movedLaunchers").isEqualTo(Set.of("read-part", "read-part.cmd"));
			requireThat(names(client.resolve("bin")), "keptLaunchers").isEqualTo(Set.of("java", "java.cmd",
				"session-start", "session-start.bat"));
		}
	}

	/**
	 * Verifies that a moved POSIX launcher runs the bound {@code java} with the plugin's module path and the caller's
	 * arguments, discarding the JVM options that the environment supplies.
	 */
	@Test
	public void movedLauncherRunsBoundJava() throws IOException, InterruptedException
	{
		if (Platform.current().isWindows())
			throw new SkipException("The POSIX launcher runs in a POSIX shell");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("plugin launchers ")).toRealPath();
			Path client = plugin.resolve("client");
			PluginLaunchers.write(List.of(READ_PART), Platform.current(), true, client);
			PluginLaunchers.move(Set.of("read-part"), client, plugin.resolve("bin"));
			Path runtime = Files.createDirectories(plugin.resolve("shared runtime/bin")).getParent();
			Path java = Files.writeString(runtime.resolve("bin/java"), """
				#!/bin/sh
				printf '%s|' "JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS-unset}" "$@"
				""");
			requireThat(java.toFile().setExecutable(true, false), "executable").isTrue();
			Files.writeString(client.resolve("runtime-binding"), runtime + "\n");

			ProcessBuilder builder = new ProcessBuilder(plugin.resolve("bin/read-part").toString(), "first arg",
				"second").redirectErrorStream(true);
			builder.environment().put("JAVA_TOOL_OPTIONS", "-Dinjected=true");
			builder.environment().put("LC_ALL", "C.UTF-8");
			String output;
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				output = new String(process.getInputStream().readAllBytes(), UTF_8);
				requireThat(process.waitFor(), "status").withContext(output, "output").isEqualTo(0);
			}

			requireThat(output, "output").isEqualTo("JAVA_TOOL_OPTIONS=unset|" +
				String.join("|", VM_OPTIONS.replace("\"", "").split(" ")) + "|--module-path|" + client +
				"/modules|--add-modules|org.slf4j.nop|-m|app/example.Main|first arg|second|");
		}
	}

	/**
	 * Verifies that moving a launcher that the plugin lacks fails.
	 */
	@Test
	public void moveRequiresLauncher() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("plugin-launchers-"));
			Path client = plugin.resolve("client");
			PluginLaunchers.write(List.of(READ_PART), Platform.LINUX_X64, true, client);
			try
			{
				PluginLaunchers.move(Set.of("run-part"), client, plugin.resolve("bin"));
				throw new AssertionError("Expected a failure for the missing launcher");
			}
			catch (IOException failure)
			{
				requireThat(failure.getMessage(), "failureMessage").contains("no launcher named run-part");
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
