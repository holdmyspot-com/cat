package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.CodexPostToolUseHook;
import io.github.cowwoc.cat.codex.CommandShell;
import io.github.cowwoc.cat.common.SessionDirectory.Removal;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that the Codex PostToolUse hook replaces a {@code run-part} or {@code read-part} result that Codex cut with
 * the command that prints the same part again, deletes a saved output after its last part is shown uncut, and leaves
 * every result that it does not replace unchanged.
 */
public final class CodexPostToolUseHookTest
{
	private static final String BIN = "/plugin/bin/";
	private static final String INSTRUCTION = "[The output was cut off because you set max_output_tokens. Run the " +
		"following command without max_output_tokens to see it: ";
	private static final JsonMapper JSON = JsonMapper.builder().build();
	/**
	 * A Codex thread ID in canonical form.
	 */
	private static final String SESSION_ID = "01a147d8-bf56-7283-92b4-12dcaeb53738";

	/**
	 * Creates a test instance.
	 */
	public CodexPostToolUseHookTest()
	{
	}

	/**
	 * Verifies that a cut part that more parts follow is replaced by an instruction to print the same part again from
	 * the position at which the agent's {@code read-part} command started it, without the part's lines or the command
	 * that prints the next part.
	 *
	 * @throws IOException if the hook response is not JSON
	 */
	@Test
	public void cutPartRepeatsItsStart() throws IOException
	{
		String result = """
			Warning: truncated output (original token count: 10108)
			line 40
			…120 tokens truncated…
			line 90
			[More output follows. Before any other command or answer, run: %sread-part /tmp/out\\ 1.txt 91]
			""".formatted(BIN);
		HookResult hook = invoke(CommandShell.POSIX, BIN + "read-part /tmp/out\\ 1.txt 40", result);

		requireThat(reason(hook), "reason").isEqualTo(INSTRUCTION + BIN + "read-part /tmp/out\\ 1.txt 40]\n");
	}

	/**
	 * Verifies that a cut part that splits a long line keeps the agent's position, including its byte offset.
	 *
	 * @throws IOException if the hook response is not JSON
	 */
	@Test
	public void cutSplitLineKeepsByteOffset() throws IOException
	{
		String result = """
			…3 tokens truncated…
			end of a long line
			[The last line continues at the start of the next part. More output follows. Before any other command \
			or answer, run: %sread-part /tmp/out.txt 7:80001]
			""".formatted(BIN);
		HookResult hook = invoke(CommandShell.POSIX, BIN + "read-part /tmp/out.txt 7:40001; echo done", result);

		requireThat(reason(hook), "reason").isEqualTo(INSTRUCTION + BIN + "read-part /tmp/out.txt 7:40001]\n");
	}

	/**
	 * Verifies that a cut last part of a {@code run-part} command is replaced by an instruction to print the saved output
	 * from its first line, quoting the saved file's path for the shell.
	 *
	 * @throws IOException if the hook response is not JSON
	 */
	@Test
	public void cutLastPartReadsSavedFile() throws IOException
	{
		String result = """
			Warning: truncated output (original token count: 9000)
			first
			…40 tokens truncated…
			last
			[End of /tmp/it's here.txt. The file was deleted]
			""";
		HookResult hook = invoke(CommandShell.POSIX, BIN + "run-part <<'EOF'\ncat notes.md\nEOF", result);

		requireThat(reason(hook), "reason").isEqualTo(INSTRUCTION + BIN + "read-part '/tmp/it'\"'\"'s here.txt' 1]\n");
	}

	/**
	 * Verifies that in PowerShell, a cut part of a {@code read-part} command whose quoted path holds spaces is replaced
	 * by an instruction to print the same part again from the position at which the agent's command started it.
	 *
	 * @throws IOException if the hook response is not JSON
	 */
	@Test
	public void powerShellCutPartRepeatsItsStart() throws IOException
	{
		String readPart = "& 'C:\\Program Files\\cat\\bin\\read-part.cmd' '\"C:\\work\\out 1.txt\"'";
		String result = """
			Warning: truncated output (original token count: 10108)
			line 40
			…120 tokens truncated…
			line 90
			[More output follows. Before any other command or answer, run: %s 91]
			""".formatted(readPart);
		HookResult hook = invoke(CommandShell.POWERSHELL, readPart + " 40", result);

		requireThat(reason(hook), "reason").isEqualTo(INSTRUCTION + readPart + " 40]\n");
	}

	/**
	 * Verifies that in PowerShell, a cut last part of a {@code run-part} command is replaced by an instruction that runs
	 * the {@code read-part} launcher of the same folder on the saved output from its first line, quoting the saved
	 * file's path for PowerShell and for the {@code cmd.exe} that runs the launcher.
	 *
	 * @throws IOException if the hook response is not JSON
	 */
	@Test
	public void powerShellCutLastPartReadsSavedFile() throws IOException
	{
		String result = """
			Warning: truncated output (original token count: 9000)
			first
			…40 tokens truncated…
			last
			[End of C:\\work\\it's here.txt. The file was deleted]
			""";
		HookResult hook = invoke(CommandShell.POWERSHELL, "$OutputEncoding = [Text.UTF8Encoding]::new()\n" +
			"@'\nGet-Content notes.md\n'@ | & C:\\cat\\bin\\run-part.cmd", result);

		requireThat(reason(hook), "reason").isEqualTo(INSTRUCTION +
			"& C:\\cat\\bin\\read-part.cmd '\"C:\\work\\it''s here.txt\"' 1]\n");
	}

	/**
	 * Verifies that a cut result without the closing line of a part is replaced by an instruction to run the agent's
	 * command again.
	 *
	 * @throws IOException if the hook response is not JSON
	 */
	@Test
	public void cutResultWithoutPartEndingRerunsCommand() throws IOException
	{
		String command = BIN + "read-part /tmp/out.txt 400";
		HookResult hook = invoke(CommandShell.POSIX, command,
			"…2 tokens truncated…\n[/tmp/out.txt ends before position 400]\n");

		requireThat(reason(hook), "reason").isEqualTo(INSTRUCTION + command + "]\n");
	}

	/**
	 * Verifies that a result that Codex did not cut passes unchanged.
	 */
	@Test
	public void uncutResultPassesUnchanged()
	{
		HookResult hook = invoke(CommandShell.POSIX, BIN + "read-part /tmp/out.txt",
			"line 1\n[End of /tmp/out.txt. The file was deleted]\n");

		requireThat(hook.output(), "output").isEmpty();
		requireThat(hook.error(), "error").isEmpty();
	}

	/**
	 * Verifies that a cut result of a command that runs neither script passes unchanged.
	 */
	@Test
	public void otherCommandPassesUnchanged()
	{
		HookResult hook = invoke(CommandShell.POSIX, "cat notes.md",
			"Warning: truncated output (original token count: 20000)\n[x]\n");

		requireThat(hook.output(), "output").isEmpty();
		requireThat(hook.error(), "error").isEmpty();
	}

	/**
	 * Verifies that an event that is not JSON leaves the result unchanged and reports the failure on standard error.
	 */
	@Test
	public void malformedEventPassesUnchanged()
	{
		HookResult hook = invoke(CommandShell.POSIX, new ByteArrayInputStream("{".getBytes(UTF_8)));

		requireThat(hook.output(), "output").isEmpty();
		requireThat(hook.error(), "error").startsWith("CAT PostToolUse: ");
	}

	/**
	 * Verifies that a {@code run-part} result whose only part Codex did not cut deletes the folder of the saved output
	 * in the session's temporary directory and leaves the result unchanged.
	 *
	 * @throws IOException if the test folders cannot be used
	 */
	@Test
	public void deletesAfterLastPartOfRunPart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("post-tool-use-test-"));
			Path saved = savedOutput(project.resolve(".cat/sessions").resolve(SESSION_ID).resolve("temp"), true);

			HookResult hook = invoke(CommandShell.POSIX, BIN + "run-part <<'EOF'\nls\nEOF", lastPart("a", saved),
				Map.of());

			requireThat(hook.output(), "output").isEmpty();
			requireThat(hook.error(), "error").isEmpty();
			requireThat(Files.exists(saved.getParent()), "folderExists").isFalse();
		}
	}

	/**
	 * Verifies that a {@code read-part} result that prints the last part uncut deletes the folder of the saved output
	 * in the temporary folder that {@code run-part} falls back to outside a session and leaves the result unchanged.
	 *
	 * @throws IOException if the test folders cannot be used
	 */
	@Test
	public void deletesAfterLastPartOfReadPart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("post-tool-use-test-"));
			Path saved = savedOutput(temporary, true);

			HookResult hook = invoke(CommandShell.POSIX, readPart(saved, "40"), lastPart("z", saved),
				Map.of("TMPDIR", temporary.toString()));

			requireThat(hook.output(), "output").isEmpty();
			requireThat(hook.error(), "error").isEmpty();
			requireThat(Files.exists(saved.getParent()), "folderExists").isFalse();
		}
	}

	/**
	 * Verifies that the result of a command that wrote no output, whose only line is the line that ends the last part,
	 * deletes the folder of the saved output.
	 *
	 * @throws IOException if the test folders cannot be used
	 */
	@Test
	public void deletesAfterEmptyOutput() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("post-tool-use-test-"));
			Path saved = savedOutput(temporary, true);

			HookResult hook = invoke(CommandShell.POSIX, BIN + "run-part <<'EOF'\ntrue\nEOF",
				"[End of " + saved + ". The file was deleted]\n", Map.of("TMPDIR", temporary.toString()));

			requireThat(hook.output(), "output").isEmpty();
			requireThat(hook.error(), "error").isEmpty();
			requireThat(Files.exists(saved.getParent()), "folderExists").isFalse();
		}
	}

	/**
	 * Verifies that a last part that Codex cut keeps the saved output, which its repaired result prints again.
	 *
	 * @throws IOException if the test folders cannot be used
	 */
	@Test
	public void keepsAfterCutLastPart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("post-tool-use-test-"));
			Path saved = savedOutput(temporary, true);

			HookResult hook = invoke(CommandShell.POSIX, readPart(saved, "40"),
				"…9 tokens truncated…\n" + lastPart("z", saved), Map.of("TMPDIR", temporary.toString()));

			requireThat(reason(hook), "reason").isEqualTo(INSTRUCTION + readPart(saved, "40") + "]\n");
			requireThat(saved, "saved").isRegularFile();
		}
	}

	/**
	 * Verifies that an uncut last part of a file that is not directly inside a {@code run-part-*} folder keeps the
	 * file.
	 *
	 * @throws IOException if the test folders cannot be used
	 */
	@Test
	public void keepsFileOutsideRunPartFolder() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("post-tool-use-test-"));
			Path folder = Files.createDirectories(temporary.resolve("notes"));
			Path saved = Files.writeString(folder.resolve("output.txt"), "z\n");
			Files.writeString(folder.resolve("output.txt.exit-status"), "0\n");

			HookResult hook = invoke(CommandShell.POSIX, readPart(saved, "1"), lastPart("z", saved),
				Map.of("TMPDIR", temporary.toString()));

			requireThat(hook.output(), "output").isEmpty();
			requireThat(saved, "saved").isRegularFile();
		}
	}

	/**
	 * Verifies that an uncut last part of an {@code output.txt} without {@code output.txt.exit-status} beside it keeps
	 * the file, which {@code run-part} did not save.
	 *
	 * @throws IOException if the test folders cannot be used
	 */
	@Test
	public void keepsOutputWithoutExitStatus() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("post-tool-use-test-"));
			Path saved = savedOutput(temporary, false);

			HookResult hook = invoke(CommandShell.POSIX, readPart(saved, "1"), lastPart("z", saved),
				Map.of("TMPDIR", temporary.toString()));

			requireThat(hook.output(), "output").isEmpty();
			requireThat(saved, "saved").isRegularFile();
		}
	}

	/**
	 * Verifies that an uncut part that more parts follow keeps the saved output.
	 *
	 * @throws IOException if the test folders cannot be used
	 */
	@Test
	public void keepsAfterPartThatMoreFollow() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("post-tool-use-test-"));
			Path saved = savedOutput(temporary, true);

			HookResult hook = invoke(CommandShell.POSIX, BIN + "run-part <<'EOF'\nls\nEOF",
				"a\n[More output follows. Before any other command or answer, run: " + readPart(saved, "2") + "]\n",
				Map.of("TMPDIR", temporary.toString()));

			requireThat(hook.output(), "output").isEmpty();
			requireThat(saved, "saved").isRegularFile();
		}
	}

	/**
	 * Verifies that a deletion that fails is reported on standard error and leaves the result unchanged.
	 *
	 * @throws IOException if the test folders cannot be used
	 */
	@Test
	public void reportsFailedDeletion() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path temporary = cleanup.add(Files.createTempDirectory("post-tool-use-test-"));
			Path saved = savedOutput(temporary, true);
			byte[] event = event(readPart(saved, "1"), lastPart("z", saved));

			HookResult hook = invoke(CommandShell.POSIX, new ByteArrayInputStream(event),
				Map.of("TMPDIR", temporary.toString()), directory ->
				{
					throw new IOException("Cannot delete " + directory);
				});

			requireThat(hook.output(), "output").isEmpty();
			requireThat(hook.error(), "error").startsWith("CAT PostToolUse: ").contains("Cannot delete");
			requireThat(saved, "saved").isRegularFile();
		}
	}

	/**
	 * Creates the folder of a saved output the way {@code run-part} does.
	 *
	 * @param temporary the temporary directory that holds the folder
	 * @param exitStatus {@code true} to save the command's exit status beside the output
	 * @return the saved output
	 * @throws IOException if the folder cannot be created
	 */
	private static Path savedOutput(Path temporary, boolean exitStatus) throws IOException
	{
		assert temporary != null;
		Path folder = Files.createTempDirectory(Files.createDirectories(temporary), "run-part-");
		Path saved = Files.writeString(folder.resolve("output.txt"), "a\nz\n");
		if (exitStatus)
			Files.writeString(folder.resolve("output.txt.exit-status"), "0\n");
		return saved.toAbsolutePath().normalize();
	}

	/**
	 * Returns the {@code read-part} command that prints a part of a saved output.
	 *
	 * @param saved the saved output
	 * @param position the part's position
	 * @return the command
	 */
	private static String readPart(Path saved, String position)
	{
		assert saved != null;
		assert position != null;
		return CommandShell.POSIX.command(BIN + "read-part", saved.toString(), position);
	}

	/**
	 * Returns the last part of a saved output as {@code read-part} prints it.
	 *
	 * @param line the part's only line
	 * @param saved the saved output
	 * @return the part
	 */
	private static String lastPart(String line, Path saved)
	{
		assert line != null;
		assert saved != null;
		return line + "\n[End of " + saved + ". The file was deleted]\n";
	}

	/**
	 * Returns a Bash PostToolUse event.
	 *
	 * @param command the shell command that the agent ran
	 * @param result the command's result as Codex showed it
	 * @return the event
	 */
	private static byte[] event(String command, String result)
	{
		assert command != null;
		assert result != null;
		return JSON.writeValueAsBytes(Map.of("hook_event_name", "PostToolUse", "tool_name", "Bash",
			"tool_input", Map.of("command", command), "tool_response", result));
	}

	/**
	 * Runs the hook on a Bash PostToolUse event in an environment without a temporary folder variable.
	 *
	 * @param shell the shell that ran the command
	 * @param command the shell command that the agent ran
	 * @param result the command's result as Codex showed it
	 * @return the captured streams
	 */
	private static HookResult invoke(CommandShell shell, String command, String result)
	{
		return invoke(shell, command, result, Map.of());
	}

	/**
	 * Runs the hook on a Bash PostToolUse event.
	 *
	 * @param shell the shell that ran the command
	 * @param command the shell command that the agent ran
	 * @param result the command's result as Codex showed it
	 * @param environment the hook's environment
	 * @return the captured streams
	 */
	private static HookResult invoke(CommandShell shell, String command, String result,
		Map<String, String> environment)
	{
		return invoke(shell, new ByteArrayInputStream(event(command, result)), environment,
			TemporaryDirectories::deleteRecursively);
	}

	/**
	 * Runs the hook on an event in an environment without a temporary folder variable.
	 *
	 * @param shell the shell that ran the event's command
	 * @param input the event
	 * @return the captured streams
	 */
	private static HookResult invoke(CommandShell shell, ByteArrayInputStream input)
	{
		return invoke(shell, input, Map.of(), TemporaryDirectories::deleteRecursively);
	}

	/**
	 * Runs the hook on an event.
	 *
	 * @param shell the shell that ran the event's command
	 * @param input the event
	 * @param environment the hook's environment
	 * @param removal deletes the folder of a saved output
	 * @return the captured streams
	 */
	private static HookResult invoke(CommandShell shell, ByteArrayInputStream input, Map<String, String> environment,
		Removal removal)
	{
		assert input != null;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		CodexPostToolUseHook.run(new String[0], shell, environment, removal, input, output,
			new PrintStream(error, true, UTF_8));
		return new HookResult(output.toString(UTF_8), error.toString(UTF_8));
	}

	/**
	 * Returns the result that a blocking hook response shows the model in place of the command's result.
	 *
	 * @param hook the captured streams
	 * @return the response's reason
	 * @throws IOException if the response is not JSON
	 */
	private static String reason(HookResult hook) throws IOException
	{
		assert hook != null;
		requireThat(hook.error(), "error").isEmpty();
		JsonNode response = JSON.readTree(hook.output());
		requireThat(response.path("decision").stringValue(), "decision").isEqualTo("block");
		return response.path("reason").stringValue();
	}

	/**
	 * The streams that one hook invocation wrote.
	 *
	 * @param output the hook's standard output
	 * @param error the hook's standard error
	 */
	private record HookResult(String output, String error)
	{
		/**
		 * Creates a result.
		 *
		 * @param output the hook's standard output
		 * @param error the hook's standard error
		 */
		private HookResult
		{
			assert output != null;
			assert error != null;
		}
	}
}
