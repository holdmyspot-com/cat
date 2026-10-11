package io.github.cowwoc.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.codex.CodexPostToolUseHook;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies that the Codex PostToolUse hook replaces the closing instruction of a {@code run-part} or {@code read-part}
 * result that Codex cut, and leaves every other result unchanged.
 */
public final class CodexPostToolUseHookTest
{
	private static final String BIN = "/plugin/client/bin/";
	private static final String INSTRUCTION = "[The output was cut off because you set max_output_tokens. Before " +
		"doing anything else, run the following command (without setting max_output_tokens) in order to get the " +
		"correct output: ";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates a test instance.
	 */
	public CodexPostToolUseHookTest()
	{
	}

	/**
	 * Verifies that a cut part that more parts follow ends with an instruction to print the same part again from the
	 * position at which the agent's {@code read-part} command started it.
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
		HookResult hook = invoke(BIN + "read-part /tmp/out\\ 1.txt 40", result);

		requireThat(reason(hook), "reason").isEqualTo("""
			Warning: truncated output (original token count: 10108)
			line 40
			…120 tokens truncated…
			line 90
			%s%sread-part /tmp/out\\ 1.txt 40]
			""".formatted(INSTRUCTION, BIN));
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
		HookResult hook = invoke(BIN + "read-part /tmp/out.txt 7:40001; echo done", result);

		requireThat(reason(hook), "reason").endsWith(INSTRUCTION + BIN + "read-part /tmp/out.txt 7:40001]\n");
	}

	/**
	 * Verifies that a cut last part of a {@code run-part} command ends with an instruction to print the saved output
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
			[End of /tmp/it's here.txt]
			""";
		HookResult hook = invoke(BIN + "run-part <<'EOF'\ncat notes.md\nEOF", result);

		requireThat(reason(hook), "reason").endsWith("last\n" + INSTRUCTION + BIN +
			"read-part '/tmp/it'\"'\"'s here.txt' 1]\n");
	}

	/**
	 * Verifies that a cut result without the closing line of a part ends with an instruction to run the agent's command
	 * again.
	 *
	 * @throws IOException if the hook response is not JSON
	 */
	@Test
	public void cutResultWithoutPartEndingRerunsCommand() throws IOException
	{
		String command = BIN + "read-part /tmp/out.txt 400";
		HookResult hook = invoke(command, "…2 tokens truncated…\n[/tmp/out.txt ends before position 400]\n");

		requireThat(reason(hook), "reason").isEqualTo("…2 tokens truncated…\n" + INSTRUCTION + command + "]\n");
	}

	/**
	 * Verifies that a result that Codex did not cut passes unchanged.
	 */
	@Test
	public void uncutResultPassesUnchanged()
	{
		HookResult hook = invoke(BIN + "read-part /tmp/out.txt", "line 1\n[End of /tmp/out.txt]\n");

		requireThat(hook.output(), "output").isEmpty();
		requireThat(hook.error(), "error").isEmpty();
	}

	/**
	 * Verifies that a cut result of a command that runs neither script passes unchanged.
	 */
	@Test
	public void otherCommandPassesUnchanged()
	{
		HookResult hook = invoke("cat notes.md", "Warning: truncated output (original token count: 20000)\n[x]\n");

		requireThat(hook.output(), "output").isEmpty();
		requireThat(hook.error(), "error").isEmpty();
	}

	/**
	 * Verifies that an event that is not JSON leaves the result unchanged and reports the failure on standard error.
	 */
	@Test
	public void malformedEventPassesUnchanged()
	{
		HookResult hook = invoke(new ByteArrayInputStream("{".getBytes(UTF_8)));

		requireThat(hook.output(), "output").isEmpty();
		requireThat(hook.error(), "error").startsWith("CAT PostToolUse: ");
	}

	/**
	 * Runs the hook on a Bash PostToolUse event.
	 *
	 * @param command the shell command that the agent ran
	 * @param result the command's result as Codex showed it
	 * @return the captured streams
	 */
	private static HookResult invoke(String command, String result)
	{
		assert command != null;
		assert result != null;
		byte[] event = JSON.writeValueAsBytes(Map.of("hook_event_name", "PostToolUse", "tool_name", "Bash",
			"tool_input", Map.of("command", command), "tool_response", result));
		return invoke(new ByteArrayInputStream(event));
	}

	/**
	 * Runs the hook on an event.
	 *
	 * @param input the event
	 * @return the captured streams
	 */
	private static HookResult invoke(ByteArrayInputStream input)
	{
		assert input != null;
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		CodexPostToolUseHook.run(new String[0], input, output, new PrintStream(error, true, UTF_8));
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
