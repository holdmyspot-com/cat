package com.holdmyspot.cat.common.optimizer.adapter.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.HostTools;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.testng.annotations.Test;

/**
 * Tests {@link HostTools} with programs that a test's own {@code PATH} directory holds. A fake launcher stands for
 * each program's {@code --version} process, except where a test runs the JVM's own {@code java}.
 */
public final class HostToolsTest
{
	private static final Path TEMP = Path.of(System.getProperty("cat.session.temp"));

	/**
	 * Creates the test.
	 */
	public HostToolsTest()
	{
	}

	/**
	 * Writes a program file that {@link Platform#findProgram(String, Map)} finds by its name in a directory.
	 *
	 * @param directory the directory
	 * @param name      the program's name
	 * @return the file
	 * @throws IOException if the file cannot be written
	 */
	private static Path program(Path directory, String name) throws IOException
	{
		if (Platform.current().isWindows())
			return Files.writeString(directory.resolve(name + ".exe"), "");
		Path file = Files.writeString(directory.resolve(name), "");
		Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
		return file;
	}

	/**
	 * Returns a trial's activity in which agents ran shell commands.
	 *
	 * @param commands the commands, each run by the agent that precedes it: {@code root} or a child's name
	 * @return the activity
	 */
	private static SessionEvidence commands(String... commands)
	{
		List<SessionEvidence.Entry> entries = new ArrayList<>();
		for (int i = 0; i < commands.length; i += 2)
			entries.add(new SessionEvidence.Tool(1, commands[i], "Bash", commands[i + 1], true, 0, ""));
		return new SessionEvidence(entries);
	}

	/**
	 * Returns the environment whose {@code PATH} is one directory.
	 *
	 * @param directory the directory
	 * @return the environment
	 */
	private static Map<String, String> pathOf(Path directory)
	{
		return Map.of("PATH", directory.toString());
	}

	/**
	 * Verifies that the host tools are the programs that any of the trial's agents ran by name, each with the first
	 * version number that its {@code --version} printed, in name order.
	 *
	 * @throws IOException          if a fixture cannot be written
	 * @throws InterruptedException if a version is read with the thread interrupted
	 */
	@Test
	public void namesEachProgramWithItsVersion() throws IOException, InterruptedException
	{
		// Requirement: R-M8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "host-tools-"));
			Map<String, String> versions = Map.of(
				program(bin, "git").toString(), "git version 2.51.0\n",
				program(bin, "rg").toString(), "ripgrep 14.1.0\n\nfeatures:+pcre2\n",
				program(bin, "node").toString(), "v22.11.0\n",
				program(bin, "java").toString(), "openjdk 27 2026-09-15\nOpenJDK Runtime Environment (build 27+35)\n");
			HostTools tools = new HostTools(pathOf(bin), file -> new HostTools.Exited(0,
				versions.get(file.toString())));

			List<String> entries = tools.describe(commands("root", "git status --short && rg -n main src",
				"child-1", "node --test && java -jar app.jar", "root", "git log --oneline -5"));

			requireThat(entries, "entries").isEqualTo(List.of("git 2.51.0", "java 27", "node 22.11.0", "rg 14.1.0"));
		}
	}

	/**
	 * Verifies that a program whose {@code --version} fails, does not finish, or prints no version number is named
	 * with the reason that its version is unknown.
	 *
	 * @throws IOException          if a fixture cannot be written
	 * @throws InterruptedException if a version is read with the thread interrupted
	 */
	@Test
	public void explainsAnUnreadableVersion() throws IOException, InterruptedException
	{
		// Requirement: R-M8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "host-tools-"));
			Map<String, HostTools.Outcome> outcomes = Map.of(
				program(bin, "busybox").toString(), new HostTools.Exited(1, "unrecognized option: --version\n"),
				program(bin, "watch").toString(), new HostTools.Unfinished(
					"--version did not finish within 5 seconds"),
				program(bin, "tree").toString(), new HostTools.Exited(0, "usage: tree [options]\n"));
			HostTools tools = new HostTools(pathOf(bin), file -> outcomes.get(file.toString()));

			List<String> entries = tools.describe(commands("root", "busybox ls; watch -n1 date; tree -L 2"));

			requireThat(entries, "entries").isEqualTo(List.of(
				"busybox (no version: --version exited with status 1)",
				"tree (no version: --version printed no version number)",
				"watch (no version: --version did not finish within 5 seconds)"));
		}
	}

	/**
	 * Verifies that a name that the host's {@code PATH} does not find, a builtin, a program named by its path and a
	 * tool call that ran no shell command add no host tool.
	 *
	 * @throws IOException          if a fixture cannot be written
	 * @throws InterruptedException if a version is read with the thread interrupted
	 */
	@Test
	public void ignoresOtherCommandWords() throws IOException, InterruptedException
	{
		// Requirement: R-M8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "host-tools-"));
			Path project = program(bin, "gradlew");
			List<Path> launched = new CopyOnWriteArrayList<>();
			HostTools hostTools = new HostTools(pathOf(bin), file ->
			{
				launched.add(file);
				return new HostTools.Exited(0, "1.0\n");
			});
			List<SessionEvidence.Entry> entries = new ArrayList<>(commands("root",
				"cd src && echo done; missing-tool --help; " + project + " build").entries());
			entries.add(new SessionEvidence.Tool(1, "root", "Read", "rg is not run", false, null, ""));

			List<String> tools = hostTools.describe(new SessionEvidence(entries));

			requireThat(tools, "tools").isEmpty();
			requireThat(launched, "launched").isEmpty();
		}
	}

	/**
	 * Verifies that each program's version is read once, however many trials run it.
	 *
	 * @throws IOException          if a fixture cannot be written
	 * @throws InterruptedException if a version is read with the thread interrupted
	 */
	@Test
	public void readsEachVersionOnce() throws IOException, InterruptedException
	{
		// Requirement: R-M8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path bin = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "host-tools-"));
			Path git = program(bin, "git");
			List<Path> launched = new CopyOnWriteArrayList<>();
			HostTools tools = new HostTools(pathOf(bin), file ->
			{
				launched.add(file);
				return new HostTools.Exited(0, "git version 2.51.0\n");
			});

			tools.describe(commands("root", "git status && git diff"));
			List<String> entries = tools.describe(commands("root", "git log"));

			requireThat(entries, "entries").isEqualTo(List.of("git 2.51.0"));
			requireThat(launched, "launched").isEqualTo(List.of(git));
		}
	}

	/**
	 * Verifies that the process launcher runs a program's {@code --version} and returns what it printed, using the
	 * JVM that runs the test as the program.
	 *
	 * @throws IOException          if a fixture cannot be written
	 * @throws InterruptedException if the process is awaited with the thread interrupted
	 */
	@Test
	public void runsTheVersionProcess() throws IOException, InterruptedException
	{
		// Requirement: R-M8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path scratch = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "host-tools-"));
			Path bin = Path.of(System.getProperty("java.home"), "bin");
			HostTools tools = new HostTools(pathOf(bin), HostTools.processes(pathOf(bin), scratch));

			List<String> entries = tools.describe(commands("root", "java -jar app.jar"));

			String version = Runtime.version().version().stream().map(String::valueOf).
				collect(Collectors.joining("."));
			requireThat(entries, "entries").isEqualTo(List.of("java " + version));
			try (Stream<Path> files = Files.list(scratch))
			{
				requireThat(files.toList(), "scratch").isEmpty();
			}
		}
	}

	/**
	 * Verifies that the process launcher reports a program that cannot start.
	 *
	 * @throws IOException          if a fixture cannot be written
	 * @throws InterruptedException if the process is awaited with the thread interrupted
	 */
	@Test
	public void reportsAProgramThatCannotStart() throws IOException, InterruptedException
	{
		// Requirement: R-M8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path scratch = cleanup.add(Files.createTempDirectory(Files.createDirectories(TEMP), "host-tools-"));
			Path broken = Files.writeString(scratch.resolve("broken"), "not a program");
			HostTools.Launcher launcher = HostTools.processes(Map.of(), scratch);

			HostTools.Outcome outcome = launcher.run(broken);

			requireThat(outcome, "outcome").isEqualTo(new HostTools.Unfinished("--version could not start"));
		}
	}
}
