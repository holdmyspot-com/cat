package com.holdmyspot.cat.distribution.bundle.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.PortableFiles;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.StartupCache;
import com.holdmyspot.cat.common.installer.engine.StartupCacheTrainer;
import com.holdmyspot.cat.distribution.bundle.Launcher;
import com.holdmyspot.cat.distribution.bundle.PluginLaunchers;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
	 * Verifies that a POSIX launcher runs the bound {@code java} with the plugin's module path and the caller's
	 * arguments, discarding the JVM options that the environment supplies. On Windows, the launcher runs in Git Bash, as
	 * Claude Code runs it, and starts the runtime's {@code java.exe}.
	 */
	@Test
	public void launcherRunsBoundJava() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path plugin = cleanup.add(Files.createTempDirectory("plugin launchers ")).toRealPath();
			Path bin = plugin.resolve("bin");
			PluginLaunchers.write(List.of(READ_PART), Platform.current(), true, bin);
			Path runtime = Files.createDirectories(plugin.resolve("installed runtime/bin")).getParent();
			String javaName = "java";
			if (Platform.current().isWindows())
				javaName = "java.exe";
			// The launcher runs java.exe on Windows, so the fake script takes that name; Git Bash starts it by its #! line
			Path java = Files.writeString(runtime.resolve("bin").resolve(javaName), """
				#!/bin/sh
				printf '%s|' "JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS-unset}" "$@"
				""");
			if (PortableFiles.hasModes(java))
				Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
			Files.writeString(bin.resolve("runtime-binding"), runtime + "\n");

			List<String> command = new ArrayList<>();
			String modulePath = bin + "/runtime/modules";
			if (Platform.current().isWindows())
			{
				// Git Bash takes the launcher's own folder from its path, which needs / separators, and prints the
				// folder as /<drive>/<path>
				command.add("sh");
				command.add(bin.resolve("read-part").toString().replace('\\', '/'));
				String windowsBin = bin.toString().replace('\\', '/');
				modulePath = "/" + Character.toLowerCase(windowsBin.charAt(0)) + windowsBin.substring(2) +
					"/runtime/modules";
			}
			else
				command.add(bin.resolve("read-part").toString());
			command.addAll(List.of("first arg", "second"));
			ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
			builder.environment().put("JAVA_TOOL_OPTIONS", "-Dinjected=true");
			builder.environment().remove("TMPDIR");
			builder.environment().put("LC_ALL", "C.UTF-8");
			String output;
			try (Process process = builder.start())
			{
				process.getOutputStream().close();
				output = new String(process.getInputStream().readAllBytes(), UTF_8);
				requireThat(process.waitFor(), "status").withContext(output, "output").isEqualTo(0);
			}

			requireThat(output, "output").isEqualTo("JAVA_TOOL_OPTIONS=unset|" +
				String.join("|", VM_OPTIONS.replace("\"", "").split(" ")) + "|--module-path|" + modulePath +
				"|--add-modules|org.slf4j.nop|-m|app/example.Main|first arg|second|");
		}
	}

	/**
	 * Verifies that a POSIX launcher passes the session's {@code TMPDIR} to Java as {@code java.io.tmpdir}, including a
	 * folder whose name holds a space, because Java on Linux ignores {@code TMPDIR} and would use a {@code /tmp} that a
	 * sandboxed session cannot write to. A launcher of a Windows plugin passes nothing, because Java on Windows follows
	 * {@code TEMP} and {@code TMP}; Git Bash gives such a launcher a {@code /c/...} {@code TMPDIR}, which that Java does
	 * not understand.
	 */
	@Test
	public void launcherPassesTmpdirToJava() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("session temp ")).toRealPath();

			List<String> arguments = launcherJavaArguments(Platform.current(), temporary, temporary.toString());

			List<String> tmpdirOptions = arguments.stream().filter(argument -> argument.contains("java.io.tmpdir")).
				toList();
			if (Platform.current().isWindows())
				requireThat(tmpdirOptions, "tmpdirOptions").isEmpty();
			else
				requireThat(tmpdirOptions, "tmpdirOptions").isEqualTo(List.of("-Djava.io.tmpdir=" + temporary));
		}
	}

	/**
	 * Verifies that a POSIX launcher of a Windows plugin passes no {@code java.io.tmpdir} when {@code TMPDIR} is set,
	 * whatever the host that runs it, because the plugin's Java on Windows follows {@code TEMP} and {@code TMP} and
	 * cannot use the {@code /c/...} path that Git Bash gives {@code TMPDIR}.
	 */
	@Test
	public void windowsLauncherPassesNoTmpdirToJava() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("session temp ")).toRealPath();

			List<String> arguments = launcherJavaArguments(Platform.WINDOWS_X64, temporary, "/c/session temp");

			requireThat(arguments.stream().filter(argument -> argument.contains("java.io.tmpdir")).toList(),
				"tmpdirOptions").isEmpty();
		}
	}

	/**
	 * Verifies that a POSIX launcher passes no {@code java.io.tmpdir} when {@code TMPDIR} is unset or empty, on every
	 * platform.
	 */
	@Test
	public void launcherPassesNoTmpdirWithoutTmpdir() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("session temp ")).toRealPath();

			for (String tmpdir : new String[]{null, ""})
			{
				List<String> arguments = launcherJavaArguments(Platform.current(), temporary, tmpdir);

				requireThat(arguments.stream().filter(argument -> argument.contains("java.io.tmpdir")).toList(),
					"tmpdirOptions").withContext(tmpdir, "TMPDIR").isEmpty();
			}
		}
	}

	/**
	 * Runs a generated POSIX launcher over a stand-in {@code java} that prints its arguments.
	 *
	 * @param platform the platform that the plugin runs on, which decides the launcher's content and the stand-in's
	 *                 name
	 * @param work     an existing folder that receives the plugin
	 * @param tmpdir   the launcher's {@code TMPDIR}, or {@code null} to leave it unset
	 * @return the arguments that the launcher passed to {@code java}
	 * @throws IOException          if the fixture cannot be created or the launcher cannot be run
	 * @throws InterruptedException if the test is interrupted
	 */
	private static List<String> launcherJavaArguments(Platform platform, Path work, String tmpdir)
		throws IOException, InterruptedException
	{
		Path plugin = Files.createTempDirectory(work, "plugin launchers ").toRealPath();
		Path bin = plugin.resolve("bin");
		PluginLaunchers.write(List.of(READ_PART), platform, true, bin);
		Path runtime = Files.createDirectories(plugin.resolve("installed runtime/bin")).getParent();
		String javaName = "java";
		if (platform.isWindows())
			javaName = "java.exe";
		Path java = Files.writeString(runtime.resolve("bin").resolve(javaName), """
			#!/bin/sh
			printf '%s\\n' "$@"
			""");
		if (PortableFiles.hasModes(java))
			Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
		Files.writeString(bin.resolve("runtime-binding"), runtime + "\n");

		List<String> command = new ArrayList<>();
		if (Platform.current().isWindows())
		{
			// Git Bash takes the launcher's own folder from its path, which needs / separators
			command.add("sh");
			command.add(bin.resolve("read-part").toString().replace('\\', '/'));
		}
		else
			command.add(bin.resolve("read-part").toString());
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
		builder.environment().put("LC_ALL", "C.UTF-8");
		if (tmpdir == null)
			builder.environment().remove("TMPDIR");
		else
			builder.environment().put("TMPDIR", tmpdir);
		try (Process process = builder.start())
		{
			process.getOutputStream().close();
			String output = new String(process.getInputStream().readAllBytes(), UTF_8);
			requireThat(process.waitFor(), "status").withContext(output, "output").isEqualTo(0);
			return output.lines().toList();
		}
	}

	/**
	 * Verifies that the plugin's {@code java} wrapper on this platform, {@code java.cmd} on Windows, trains a startup
	 * cache that the wrapper then passes to Java, which accepts it, while the wrapper passes no cache before the
	 * training writes one. The runtime is the plugin's own, in its {@code bin/runtime} folder, as an install places it,
	 * so the cache is trained and used by the runtime at that location. The training leaves the cache file alone in
	 * the plugin's cache folder and removes its scratch folder. The plugin's path holds spaces, as a user's home
	 * directory can.
	 * <p>
	 * This is the one check of how real Java handles a cache that a training wrote through a plugin's wrapper. The
	 * test takes seconds: Java starts several times, for the training and for each of three wrapper runs,
	 * and the claim is how the real wrapper and real Java handle the cache, so a stand-in for either cannot prove it.
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
			Path runtime = linkedRuntime(bin.resolve("runtime"));
			Path modules = Files.createDirectories(runtime.resolve("modules"));
			Files.copy(Path.of(URI.create(Test.class.getProtectionDomain().getCodeSource().getLocation().toString())),
				modules.resolve("module.jar"));
			Files.writeString(bin.resolve("runtime-binding"), runtime + "\n", UTF_8);
			Files.writeString(plugin.resolve(StartupCache.TRAINING), """
				{"format":1,"arguments":["-m","jdk.jartool/sun.tools.jar.Main","--version"],\
				"plugin_root_variable":"CAT_PLUGIN_ROOT","input":""}""", UTF_8);

			String untrainedFlags = runWrapper(bin, "-XX:+PrintCommandLineFlags", "-version");
			Path scratch = plugin.resolve("scratch");
			boolean written = new StartupCacheTrainer(scratch, Duration.ofMinutes(1), () -> false).train(plugin);
			String trainedFlags = runWrapper(bin, "-XX:+PrintCommandLineFlags", "-version");
			// With AOTMode=on, Java fails instead of starting without a cache that it cannot use
			String trainedRun = runWrapper(bin, "-XX:AOTMode=on", "-m", "jdk.jartool/sun.tools.jar.Main", "--version");

			requireThat(untrainedFlags, "untrainedFlags").doesNotContain("-XX:AOTCache=");
			requireThat(written, "written").isTrue();
			requireThat(names(plugin.resolve(StartupCache.DIRECTORY)), "cacheFolder").
				isEqualTo(Set.of(StartupCache.FILE.getFileName().toString()));
			requireThat(names(scratch), "scratchEntries").isEmpty();
			Matcher cache = Pattern.compile("-XX:AOTCache=(.+?)(?: -XX:|\\R)").matcher(trainedFlags);
			requireThat(cache.find(), "cachePassed").withContext(trainedFlags, "trainedFlags").isTrue();
			requireThat(Files.isSameFile(Path.of(cache.group(1)), plugin.resolve(StartupCache.FILE)), "passedCache").
				withContext(cache.group(1), "passed").isTrue();
			requireThat(trainedRun.strip(), "trainedRun").startsWith("jar ");
		}
	}

	/**
	 * Places a Java runtime at a new location by linking the files of the testing JVM's runtime, so that Java started
	 * from it reads its runtime image at that location. A file that cannot be linked, such as one on another file
	 * system, is copied.
	 *
	 * @param target the runtime directory, which must not exist
	 * @return the runtime directory
	 * @throws IOException if the runtime cannot be placed
	 */
	private static Path linkedRuntime(Path target) throws IOException
	{
		Path source = Path.of(System.getProperty("java.home")).toRealPath();
		for (String part : List.of("bin", "conf", "lib", "release"))
		{
			try (Stream<Path> paths = Files.walk(source.resolve(part)))
			{
				for (Path path : paths.toList())
				{
					Path copy = target.resolve(source.relativize(path).toString());
					if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
						Files.createDirectories(copy);
					else
						linkOrCopy(path, copy);
				}
			}
		}
		return target;
	}

	/**
	 * Links a file of the testing JVM's runtime at another location, or copies it when it cannot be linked.
	 *
	 * @param file the file, which is never changed
	 * @param copy the new location
	 * @throws IOException if the file can be neither linked nor copied
	 */
	private static void linkOrCopy(Path file, Path copy) throws IOException
	{
		Files.createDirectories(copy.getParent());
		if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
		{
			try
			{
				Files.createLink(copy, file);
				return;
			}
			catch (IOException | UnsupportedOperationException _)
			{
				// Copied below
			}
		}
		Files.copy(file, copy, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
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
