package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.runner.CodexKeyFileCommand;
import com.holdmyspot.cat.codex.runner.CodexSourceHomeCommand;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.TemporaryDirectories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the launcher that runs a source provider's own token command with the source home as its home folder, in
 * place of a Unix {@code env} program that native Windows lacks.
 */
public final class CodexSourceHomeCommandTest
{
	/**
	 * Returns each platform with the name of the runtime's Java program and the variables that name the home folder.
	 *
	 * @return the platform, the program's file name and the names of the home variables
	 */
	@DataProvider
	public static Object[][] platforms()
	{
		return new Object[][]
		{
			{Platform.LINUX_X64, "java", List.of("HOME")},
			{Platform.MACOS_ARM64, "java", List.of("HOME")},
			{Platform.WINDOWS_X64, "java.exe", List.of("HOME", "USERPROFILE")}
		};
	}

	/**
	 * Verifies that the launcher runs the runtime's Java program by an absolute path, and sets the variables that name
	 * the home folder on each platform, so that the command needs no program that a {@code PATH} finds.
	 *
	 * @param platform  the platform
	 * @param name      the file name of the runtime's Java program
	 * @param variables the names of the variables that name the home folder
	 */
	@Test(dataProvider = "platforms")
	public void launcherRunsBundledRuntime(Platform platform, String name, List<String> variables)
	{
		// Requirement: R-I15
		Path javaHome = Path.of(System.getProperty("java.home"));
		Path home = javaHome.resolve("source-home");

		CodexKeyFileCommand.Invocation invocation = CodexSourceHomeCommand.invocation(home, "sh",
			List.of("-c", "cat ~/key"), platform, javaHome, "modules");

		requireThat(invocation.program(), "program").isEqualTo(javaHome.resolve("bin").resolve(name).toString());
		requireThat(invocation.arguments(), "arguments").contains("--module-path").contains("modules").contains("-m");
		List<String> arguments = invocation.arguments();
		List<String> tail = arguments.subList(arguments.size() - 4, arguments.size());
		requireThat(tail, "tail").isEqualTo(List.of(home.toString(), "sh", "-c", "cat ~/key"));
		requireThat(CodexSourceHomeCommand.homeVariables(home, platform).keySet(), "variables").
			isEqualTo(new java.util.TreeSet<>(variables));
		for (String value : CodexSourceHomeCommand.homeVariables(home, platform).values())
			requireThat(value, "value").isEqualTo(home.toString());
	}

	/**
	 * Verifies that the source command sees the source home as its home folder, whatever the launcher's own home is.
	 *
	 * @throws IOException          if the fixture or the process fails
	 * @throws InterruptedException if the wait for the process is interrupted
	 */
	@Test
	public void sourceCommandSeesSourceHome() throws IOException, InterruptedException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-source-home-")).toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			Path other = Files.createDirectories(root.resolve("other"));
			Files.writeString(source.resolve("key"), "the-secret");

			Result result = run(source, other, root, "sh", "-c", "cat ~/key; printf '|%s' \"$HOME\"");

			requireThat(result.status(), "status").isEqualTo(0);
			requireThat(result.output(), "output").isEqualTo("the-secret|" + shellPath(source));
		}
	}

	/**
	 * Verifies that the source command's arguments, standard output, standard error and exit status pass through the
	 * launcher unchanged, and that the command runs in the folder that the launcher's own process has.
	 *
	 * @throws IOException          if the fixture or the process fails
	 * @throws InterruptedException if the wait for the process is interrupted
	 */
	@Test
	public void streamsAndStatusPassThrough() throws IOException, InterruptedException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-source-home-")).toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			Path folder = Files.createDirectories(root.resolve("folder"));

			Result result = run(source, folder, folder, "sh", "-c",
				"pwd; printf '<%s>' \"$@\"; echo problem >&2; exit 7", "name", "a b", "", "c'd");

			requireThat(result.status(), "status").isEqualTo(7);
			requireThat(result.output(), "output").isEqualTo(shellPath(folder) + "\n<a b><><c'd>problem\n");
		}
	}

	/**
	 * Verifies that a source command that does not exist ends the launcher with a nonzero status and a message that
	 * names the program, as a shell does.
	 *
	 * @throws IOException          if the fixture or the process fails
	 * @throws InterruptedException if the wait for the process is interrupted
	 */
	@Test
	public void missingProgramFails() throws IOException, InterruptedException
	{
		// Requirement: R-I15
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-source-home-")).toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));

			Result result = run(source, source, root, "no-such-token-program");

			requireThat(result.status(), "status").isEqualTo(127);
			requireThat(result.output(), "output").contains("no-such-token-program");
		}
	}

	/**
	 * Runs the launcher with the bundled runtime's Java program in the way that Codex runs a token command: by its
	 * absolute path, in an environment of its own.
	 *
	 * @param source   the source home
	 * @param home     the home folder that the launcher's own environment names
	 * @param folder   the folder that the process starts in
	 * @param program  the source command
	 * @param argument the source command's arguments
	 * @return the status and the output, with standard error after standard output
	 * @throws IOException          if the process cannot start
	 * @throws InterruptedException if the wait for the process is interrupted
	 */
	// The process ends before the method returns, so there is nothing left to close
	@SuppressWarnings("PMD.CloseResource")
	private static Result run(Path source, Path home, Path folder, String program, String... argument)
		throws IOException, InterruptedException
	{
		CodexKeyFileCommand.Invocation invocation = CodexSourceHomeCommand.invocation(source, program,
			List.of(argument));
		List<String> command = new ArrayList<>();
		command.add(invocation.program());
		for (String launcherArgument : invocation.arguments())
		{
			// Codex, which starts a token command, quotes its arguments as Windows programs read them, and Java does not
			if (Platform.current().isWindows())
				command.add(CommandLineArguments.quote(launcherArgument));
			else
				command.add(launcherArgument);
		}
		ProcessBuilder builder = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true);
		Map<String, String> environment = builder.environment();
		environment.clear();
		environment.put("PATH", System.getenv("PATH"));
		environment.put("HOME", home.toString());
		Process process = builder.start();
		String output = new String(process.getInputStream().readAllBytes(), UTF_8);
		return new Result(process.waitFor(), output);
	}

	/**
	 * Returns a path as the shell that runs the source command prints it. On Windows that is the shell of Git for
	 * Windows, which names {@code C:\Users\me} as {@code /c/Users/me}.
	 *
	 * @param path an absolute path
	 * @return the path as the shell prints it
	 */
	private static String shellPath(Path path)
	{
		String text = path.toString();
		if (!Platform.current().isWindows())
			return text;
		return "/" + Character.toLowerCase(text.charAt(0)) + text.substring(2).replace('\\', '/');
	}

	/**
	 * The outcome of a launcher run.
	 *
	 * @param status the exit status
	 * @param output the standard output followed by the standard error
	 */
	private record Result(int status, String output)
	{
	}
}
