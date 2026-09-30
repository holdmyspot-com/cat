package io.github.cowwoc.cat.claude;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies native hook authority binding without launching Claude or an inspection process.
 */
public final class ClaudeOptimizerPreToolHookTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String COMMAND = "\"/opt/client/bin/optimizer-inspect\" --json";
	private static final String EVENT = "{\"hook_event_name\":\"PreToolUse\",\"tool_name\":\"Bash\"," +
		"\"session_id\":\"session\",\"prompt_id\":\"prompt\",\"tool_use_id\":\"tool\"," +
		"\"transcript_path\":\"/tmp/session.jsonl\",\"tool_input\":{\"command\":\"" +
		COMMAND.replace("\"", "\\\"") +
		"\",\"timeout\":5}}";

	/**
	 * Creates the test fixture.
	 */
	public ClaudeOptimizerPreToolHookTest()
	{
	}

	/**
	 * Lookup receives exact identities; success preserves input and binds prompt_id.
	 */
	@Test
	public void bindsExactHostIdentity() throws IOException
	{
		AtomicInteger calls = new AtomicInteger();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ClaudeOptimizerPreToolHook.run(input(EVENT), output, (path, session, toolUse) ->
		{
			calls.incrementAndGet();
			requireThat(path, "transcriptPath").isEqualTo(Path.of("/tmp/session.jsonl"));
			requireThat(session, "session").isEqualTo("session");
			requireThat(toolUse, "toolUse").isEqualTo("tool");
			return "actual-model";
		});
		JsonNode response = JSON.readTree(output.toByteArray());
		JsonNode hook = response.path("hookSpecificOutput");
		requireThat(calls.get(), "lookupCalls").isEqualTo(1);
		requireThat(hook.path("hookEventName").asString(), "eventName").isEqualTo("PreToolUse");
		requireThat(hook.path("permissionDecision").isMissingNode(), "noPermissionDecision").isTrue();
		JsonNode updated = hook.path("updatedInput");
		requireThat(updated.path("timeout").intValue(), "preservedTimeout").isEqualTo(5);
		requireThat(updated.path("command").asString(), "boundPrompt").contains("--host-turn-id 'prompt'");
		requireThat(updated.path("command").asString(), "boundModel").contains("--host-model-id 'actual-model'");
	}

	/**
	 * Valid unrelated commands never consult the transcript model lookup.
	 */
	@Test
	public void skipsUnrelatedCommand() throws IOException
	{
		AtomicInteger calls = new AtomicInteger();
		String unrelated = EVENT.replace(COMMAND.replace("\"", "\\\""), "echo hello").replace(
			",\"session_id\":\"session\"", "");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ClaudeOptimizerPreToolHook.run(input(unrelated), output, (path, session, toolUse) ->
		{
			calls.incrementAndGet();
			throw new IOException("must not run");
		});
		requireThat(calls.get(), "noLookup").isEqualTo(0);
		requireThat(output.size(), "noRewrite").isEqualTo(0);
	}

	/**
	 * Missing authority and transcript failures both return a blocking denial with a reason.
	 */
	@Test
	public void deniesMissingAuthorityAndLookupFailure() throws IOException
	{
		AtomicInteger calls = new AtomicInteger();
		for (String event: new String[]{EVENT.replace(",\"prompt_id\":\"prompt\"", ""), EVENT})
		{
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			ClaudeOptimizerPreToolHook.run(input(event), output, (path, session, toolUse) ->
			{
				calls.incrementAndGet();
				if (event.equals(EVENT))
					throw new IOException("transcript unavailable");
				return "unexpected-model";
			});
			JsonNode hook = JSON.readTree(output.toByteArray()).path("hookSpecificOutput");
			requireThat(hook.path("permissionDecision").asString(), "deny").isEqualTo("deny");
			requireThat(hook.path("permissionDecisionReason").asString(), "actionableReason").isNotBlank();
		}
		requireThat(calls.get(), "lookupOnlyForAuthorizedEvent").isEqualTo(1);
	}

	/**
	 * Other native event types are ignored before consulting host identity lookup.
	 */
	@Test
	public void skipsUnrelatedNativeEvent() throws IOException
	{
		AtomicInteger calls = new AtomicInteger();
		String event = EVENT.replace("PreToolUse", "PostToolUse");
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ClaudeOptimizerPreToolHook.run(input(event), output, (path, session, toolUse) ->
		{
			calls.incrementAndGet();
			throw new IOException("must not run");
		});
		requireThat(calls.get(), "noLookup").isEqualTo(0);
		requireThat(output.size(), "noRewrite").isEqualTo(0);
	}

	/**
	 * Malformed event JSON, nonobjects and invalid tool input fail as I/O errors.
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMalformedJson() throws IOException
	{
		runEvent("{");
	}

	/**
	 * A JSON array cannot serve as the Claude event object.
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsNonObjectEvent() throws IOException
	{
		runEvent("[]");
	}

	/**
	 * A pre-tool event without tool_input is malformed.
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMissingToolInput() throws IOException
	{
		String prefix = EVENT.substring(0, EVENT.indexOf("\"tool_input\":"));
		runEvent(prefix.substring(0, prefix.length() - 1) + "}");
	}

	/**
	 * A pre-tool event with null tool_input is malformed.
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsNullToolInput() throws IOException
	{
		String prefix = EVENT.substring(0, EVENT.indexOf("\"tool_input\":"));
		runEvent(prefix + "\"tool_input\":null}");
	}

	/**
	 * A Bash tool input without command is malformed.
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsMissingCommand() throws IOException
	{
		String prefix = EVENT.substring(0, EVENT.indexOf("\"tool_input\":"));
		runEvent(prefix + "\"tool_input\":{\"timeout\":5}}");
	}

	/**
	 * A nontext Bash command is malformed.
	 */
	@Test(expectedExceptions = IOException.class)
	public void rejectsNontextCommand() throws IOException
	{
		String prefix = EVENT.substring(0, EVENT.indexOf("\"tool_input\":"));
		runEvent(prefix + "\"tool_input\":{\"command\":7}}");
	}

	/**
	 * Response output failure propagates instead of being converted to an authority denial.
	 */
	@Test(expectedExceptions = IOException.class)
	public void propagatesOutputFailure() throws IOException
	{
		IOException failure = new IOException("output failed");
		try (OutputStream output = new OutputStream()
		{
			@Override
			public void write(int value) throws IOException
			{
				throw failure;
			}
		})
		{
			ClaudeOptimizerPreToolHook.run(input(EVENT), output, (path, session, toolUse) -> "model");
		}
	}

	/**
	 * Executes a malformed hook event with a lookup that has no external behavior.
	 *
	 * @param event serialized native event
	 * @throws IOException when event validation rejects the input
	 */
	private static void runEvent(String event) throws IOException
	{
		ClaudeOptimizerPreToolHook.run(input(event), new ByteArrayOutputStream(),
			(path, session, toolUse) -> "model");
	}

	/**
	 * Creates UTF-8 hook input for a small event fixture.
	 *
	 * @param value serialized event
	 * @return input stream
	 */
	private static ByteArrayInputStream input(String value)
	{
		return new ByteArrayInputStream(value.getBytes(UTF_8));
	}
}
