package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import com.holdmyspot.cat.distribution.bundle.Launcher;
import com.holdmyspot.cat.distribution.bundle.PluginLaunchers;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
			Path bin = cleanup.add(Files.createTempDirectory("plugin-launchers-")).resolve("bin");
			PluginLaunchers.write(List.of(READ_PART, SESSION_START), Platform.LINUX_X64, true, bin);

			requireThat(names(bin), "launchers").isEqualTo(Set.of("java", "read-part",
				"session-start"));
			requireThat(Files.readString(bin.resolve("read-part")), "launcher").
				startsWith("#!/bin/sh\n").
				contains("exec \"$java\" ${cache:+\"$cache\"} " + VM_OPTIONS + " --module-path \"$bin/modules\" " +
					"--add-modules org.slf4j.nop -m app/example.Main \"$@\"\n").
				contains("java=$runtime/bin/java\n").
				doesNotContain("bwrap");
			requireThat(Files.readString(bin.resolve("java")), "wrapper").
				contains("exec \"$java\" ${cache:+\"$cache\"} " + VM_OPTIONS +
					" --module-path \"$bin/modules\" \"$@\"\n");
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
				contains("\r\n\"%JAVA%\" %CACHE% " + VM_OPTIONS + " --module-path \"%BIN%\\modules\" " +
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
	 * Verifies that a POSIX launcher runs the bound {@code java} with the plugin's module path and the caller's
	 * arguments, discarding the JVM options that the environment supplies.
	 */
	@Test
	public void launcherRunsBoundJava() throws IOException, InterruptedException
	{
		if (Platform.current().isWindows())
			throw new SkipException("The POSIX launcher runs in a POSIX shell");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("plugin launchers ")).toRealPath();
			Path bin = plugin.resolve("bin");
			PluginLaunchers.write(List.of(READ_PART), Platform.current(), true, bin);
			Path runtime = Files.createDirectories(plugin.resolve("installed runtime/bin")).getParent();
			Path java = Files.writeString(runtime.resolve("bin/java"), """
				#!/bin/sh
				printf '%s|' "JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS-unset}" "$@"
				""");
			requireThat(java.toFile().setExecutable(true, false), "executable").isTrue();
			Files.writeString(bin.resolve("runtime-binding"), runtime + "\n");

			ProcessBuilder builder = new ProcessBuilder(bin.resolve("read-part").toString(), "first arg",
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
				String.join("|", VM_OPTIONS.replace("\"", "").split(" ")) + "|--module-path|" + bin +
				"/modules|--add-modules|org.slf4j.nop|-m|app/example.Main|first arg|second|");
		}
	}

	/**
	 * Verifies that the plugin's {@code java} wrapper on this platform, {@code java.cmd} on Windows, trains a startup
	 * cache that the wrapper then passes to Java, which accepts it, while the wrapper passes no cache before the
	 * training writes one. The plugin's path holds spaces, as a user's home directory can.
	 *
	 * @throws IOException          if the fixture cannot be created or a wrapper cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void wrapperTrainsAndPassesStartupCache() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("plugin with spaces ")).toRealPath();
			Path bin = plugin.resolve("bin");
			// A Windows plugin whose harness runs commands in PowerShell holds only the batch launchers
			PluginLaunchers.write(List.of(), Platform.current(), !Platform.current().isWindows(), bin);
			// Java uses a startup cache that it trained on a module path only for the same module path, which an empty
			// folder is not; any jar stands in for the plugin's modules
			Path modules = Files.createDirectories(bin.resolve("modules"));
			Files.copy(Path.of(URI.create(Test.class.getProtectionDomain().getCodeSource().getLocation().toString())),
				modules.resolve("module.jar"));
			Path runtime = Path.of(System.getProperty("java.home")).toRealPath();
			Files.writeString(bin.resolve("runtime-binding"), runtime + "\n", UTF_8);
			Files.writeString(plugin.resolve(StartupCache.TRAINING), """
				{"format":1,"arguments":["-m","jdk.jartool/sun.tools.jar.Main","--version"],\
				"plugin_root_variable":"CAT_PLUGIN_ROOT","input":""}""", UTF_8);

			String untrainedFlags = runWrapper(bin, "-XX:+PrintCommandLineFlags", "-version");
			boolean written = new StartupCacheTrainer(plugin.resolve("scratch"), Duration.ofMinutes(1), () -> false).
				train(plugin);
			String trainedFlags = runWrapper(bin, "-XX:+PrintCommandLineFlags", "-version");
			// With AOTMode=on, Java fails instead of starting without a cache that it cannot use
			String trainedRun = runWrapper(bin, "-XX:AOTMode=on", "-m", "jdk.jartool/sun.tools.jar.Main", "--version");

			requireThat(untrainedFlags, "untrainedFlags").doesNotContain("-XX:AOTCache=");
			requireThat(written, "written").isTrue();
			Matcher cache = Pattern.compile("-XX:AOTCache=(.+?)(?: -XX:|\\R)").matcher(trainedFlags);
			requireThat(cache.find(), "cachePassed").withContext(trainedFlags, "trainedFlags").isTrue();
			requireThat(Files.isSameFile(Path.of(cache.group(1)), plugin.resolve(StartupCache.FILE)), "passedCache").
				withContext(cache.group(1), "passed").isTrue();
			requireThat(trainedRun.strip(), "trainedRun").startsWith("jar ");
		}
	}

	/**
	 * Runs a plugin's {@code java} wrapper on this platform, {@code java.cmd} on Windows, requiring it to succeed.
	 *
	 * @param bin       the plugin's {@code bin} folder
	 * @param arguments the wrapper's arguments
	 * @return the wrapper's standard output and standard error
	 * @throws IOException          if the wrapper cannot be run or ends with a failure status
	 * @throws InterruptedException if the test is interrupted
	 */
	private static String runWrapper(Path bin, String... arguments) throws IOException, InterruptedException
	{
		String wrapper = PluginLaunchers.JAVA_WRAPPER;
		if (Platform.current().isWindows())
			wrapper += ".cmd";
		List<String> command = new ArrayList<>();
		command.add(bin.resolve(wrapper).toString());
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			if (process.waitFor() != 0)
				throw new IOException(command + " failed with status " + process.exitValue() + ": " + output);
			return output;
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
