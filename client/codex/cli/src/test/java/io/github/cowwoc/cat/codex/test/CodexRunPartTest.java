package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.CodexRunPart;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Verifies that {@code run-part} saves a command's output and exit status, then prints the output's first part the way
 * {@code read-part} prints a file.
 */
public final class CodexRunPartTest
{
	private static final Path READ_PART = Path.of("/plugin/bin/read-part");
	private static final Pattern LAST_PART = Pattern.compile("\\[End of (?<path>[^\n]+)]\n\\z");
	/**
	 * A folder that no CAT project contains, so that {@code run-part} saves the output of a command that runs there
	 * under {@code TMPDIR}.
	 */
	private static final Path OUTSIDE_PROJECT = Path.of("/");

	/**
	 * Creates a test instance.
	 */
	public CodexRunPartTest()
	{
	}

	/**
	 * Verifies that the command's standard output and standard error are saved in one file under {@code TMPDIR} when no
	 * CAT project contains the folder that the command runs in, that the last part exits with the command's exit status,
	 * and that the status is saved next to the output for {@code read-part}.
	 *
	 * @throws IOException if the temporary folder cannot be created or read
	 */
	@Test
	public void savesOutputAndStatus() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));

			Call call = runPart("printf 'out\\n'\necho err >&2\nexit 3\n", false, OUTSIDE_PROJECT,
				Map.of("TMPDIR", temporary.toString()));

			Matcher lastPart = LAST_PART.matcher(call.output());
			requireThat(lastPart.find(), "lastPart").isTrue();
			Path saved = Path.of(lastPart.group("path"));
			requireThat(saved.getParent().getParent(), "folder").isEqualTo(temporary);
			requireThat(call.output(), "output").isEqualTo("out\nerr\n[End of " + saved + "]\n");
			requireThat(call.status(), "status").isEqualTo(3);
			requireThat(Files.readString(Path.of(saved + ".exit-status")), "exitStatus").isEqualTo("3\n");
		}
	}

	/**
	 * Verifies that a command runs in its folder and that its output is saved in the CAT temporary folder of the nearest
	 * project that contains that folder, so that it stays inside the project.
	 *
	 * @throws IOException if the temporary folders cannot be created
	 */
	@Test
	public void savesOutputInProjectTemp() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Path catTemp = Files.createDirectories(project.resolve(".cat/work/temp"));
			Path folder = Files.createDirectories(project.resolve("src/main"));
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));

			Call call = runPart("pwd", false, folder, Map.of("TMPDIR", temporary.toString()));

			Matcher lastPart = LAST_PART.matcher(call.output());
			requireThat(lastPart.find(), "lastPart").isTrue();
			Path saved = Path.of(lastPart.group("path"));
			requireThat(saved.getParent().getParent(), "folder").isEqualTo(catTemp);
			// pwd prints the folder's real path, which differs where the temporary folder is a symbolic link, as on macOS
			requireThat(call.output(), "output").isEqualTo(folder.toRealPath() + "\n[End of " + saved + "]\n");
		}
	}

	/**
	 * Verifies that in a Codex thread, output too long for one part ends with the {@code read-part} command that prints
	 * the next part, and that the first part exits with status 0 whatever the command's status.
	 *
	 * @throws IOException if the temporary folder cannot be created
	 */
	@Test
	public void longOutputNamesNextPart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Map<String, String> environment = Map.of("TMPDIR", temporary.toString(), "CODEX_THREAD_ID", "thread",
				"CODEX_HOME", temporary.toString());

			Call call = runPart("seq 1 20000; exit 4", false, OUTSIDE_PROJECT, environment);

			requireThat(call.output(), "output").startsWith("1\n2\n3\n").
				matches("(?s).*\\[More output follows\\. Before any other command or answer, run: " +
					Pattern.quote(READ_PART.toString()) + " \\S+/output\\.txt [1-9][0-9]*]\n");
			requireThat(call.status(), "status").isEqualTo(0);
		}
	}

	/**
	 * Verifies that the command reads no input, so that it cannot wait for input that never comes.
	 *
	 * @throws IOException if the temporary folder cannot be created
	 */
	@Test
	public void commandReadsNoInput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));

			Call call = runPart("cat; echo done", false, OUTSIDE_PROJECT, Map.of("TMPDIR", temporary.toString()));

			requireThat(call.output(), "output").startsWith("done\n[End of ");
			requireThat(call.status(), "status").isEqualTo(0);
		}
	}

	/**
	 * Verifies that the command runs with the caller's locale, which the launcher saved before it changed the locale of
	 * the JVM.
	 *
	 * @throws IOException if the temporary folder cannot be created
	 */
	@Test
	public void commandRunsInCallerLocale() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Map<String, String> environment = Map.of("TMPDIR", temporary.toString(), "LC_CTYPE", "C.UTF-8",
				"CAT_CALLER_LC_ALL", "+C", "CAT_CALLER_LC_CTYPE", "-");

			Call call = runPart("printf '%s|%s|%s\\n' \"${LC_ALL-unset}\" \"${LC_CTYPE-unset}\" " +
				"\"${CAT_CALLER_LC_ALL-unset}\"", false, OUTSIDE_PROJECT, environment);

			requireThat(call.output(), "output").startsWith("C|unset|unset\n");
		}
	}

	/**
	 * Verifies that the command finds the plugin's {@code run-part} overrides before the caller's {@code PATH} and is
	 * told which file saves its output, so that an override can tell whether it writes straight into that file.
	 *
	 * @throws IOException if the temporary folder cannot be created
	 */
	@Test
	public void commandFindsOverridesFirst() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			Map<String, String> environment = Map.of("TMPDIR", temporary.toString(), "PATH", "/usr/bin:/bin");

			Call call = runPart("printf '%s|%s\\n' \"$PATH\" \"${CAT_RUN_PART_OUTPUT-unset}\"", false, OUTSIDE_PROJECT,
				environment);

			Matcher lastPart = LAST_PART.matcher(call.output());
			requireThat(lastPart.find(), "lastPart").isTrue();
			requireThat(call.output(), "output").isEqualTo("/plugin/bin/run-part-overrides:/usr/bin:/bin|" +
				lastPart.group("path") + "\n[End of " + lastPart.group("path") + "]\n");
		}
	}

	/**
	 * Verifies that a call without a command prints how to use {@code run-part} and saves nothing.
	 *
	 * @throws IOException if the temporary folder cannot be created or listed
	 */
	@Test
	public void blankCommandIsUsageError() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("run-part-test-"));
			for (Call call : List.of(runPart(" \n\t\n", false, OUTSIDE_PROJECT, Map.of("TMPDIR", temporary.toString())),
				runPart("echo typed", true, OUTSIDE_PROJECT, Map.of("TMPDIR", temporary.toString()))))
			{
				requireThat(call.status(), "status").isEqualTo(2);
				requireThat(call.output(), "output").isEmpty();
				requireThat(call.error(), "error").isEqualTo("usage: run-part <<'EOF', then the command on the lines " +
					"that follow, then EOF\n");
			}
			try (Stream<Path> files = Files.list(temporary))
			{
				requireThat(files.toList(), "files").isEmpty();
			}
		}
	}

	/**
	 * Runs {@code run-part}.
	 *
	 * @param command the command on standard input
	 * @param terminal {@code true} if standard input is a terminal
	 * @param workingDirectory the folder that the command runs in
	 * @param environment the environment
	 * @return the call's streams and exit status
	 */
	private static Call runPart(String command, boolean terminal, Path workingDirectory,
		Map<String, String> environment)
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		int status = CodexRunPart.run(new String[0], new ByteArrayInputStream(command.getBytes(UTF_8)), terminal,
			workingDirectory, READ_PART, environment, output, new PrintStream(error, true, UTF_8));
		return new Call(output.toString(UTF_8), error.toString(UTF_8), status);
	}

	/**
	 * The streams and exit status of one call.
	 *
	 * @param output the standard output
	 * @param error the standard error
	 * @param status the exit status
	 */
	private record Call(String output, String error, int status)
	{
		/**
		 * Creates a call.
		 *
		 * @param output the standard output
		 * @param error the standard error
		 * @param status the exit status
		 */
		private Call
		{
			assert output != null;
			assert error != null;
		}
	}
}
