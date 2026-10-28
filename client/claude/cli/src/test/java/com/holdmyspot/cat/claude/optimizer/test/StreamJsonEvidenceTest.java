package com.holdmyspot.cat.claude.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.claude.optimizer.StreamJsonEvents;
import com.holdmyspot.cat.claude.secrets.StreamJsonEventsSecrets;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the evidence mapped from recorded Claude stream-JSON events.
 */
public final class StreamJsonEvidenceTest
{
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public StreamJsonEvidenceTest()
	{
	}

	/**
	 * Reads a recorded event stream.
	 *
	 * @param name the resource name
	 * @return the event lines
	 * @throws IOException if the resource cannot be read
	 */
	private static List<String> recorded(String name) throws IOException
	{
		try (InputStream stream = StreamJsonEvidenceTest.class.getResourceAsStream("/optimizer/" + name))
		{
			requireThat(stream, "resource").isNotNull();
			return new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
	}

	/**
	 * Maps event lines to evidence.
	 *
	 * @param lines the event lines
	 * @return the evidence
	 */
	private static SessionEvidence parse(List<String> lines)
	{
		StreamJsonEvents events = StreamJsonEventsSecrets.getStreamJsonEventsAccess().newStreamJsonEvents(JSON);
		for (String line : lines)
			events.accept(line);
		return events.evidence();
	}

	/**
	 * Verifies that shell commands, other arguments and complete results survive parsing recorded tool calls, that each
	 * result is received as the content shown to the agent, and that rendering gives each result's size.
	 *
	 * @throws IOException if the recordings cannot be read
	 */
	@Test
	public void rendersRecordedToolCalls() throws IOException
	{
		// Requirement: R-E21
		SessionEvidence evidence = parse(recorded("claude-tool-calls.jsonl"));
		requireThat(evidence.entries(), "entries").isEqualTo(List.of(
			new SessionEvidence.Tool(1, "root", "Bash", "true", true, 0, ""),
			new SessionEvidence.Tool(1, "root", "Bash", "false", true, 1, "Exit code 1"),
			new SessionEvidence.Tool(1, "root", "Read", "{\"file_path\":\"/recorded-project/sample.txt\"}", false,
				null, "1\tsample\n2\t")));
		String rendered = evidence.render();
		requireThat(rendered.contains("true"), "command").isTrue();
		requireThat(rendered.contains("Exit status: 0"), "exitStatus").isTrue();
		requireThat(evidence.received(), "received").
			isEqualTo(List.of("(Bash completed with no output)", "Exit code 1", "1\tsample\n2\t"));
		requireThat(rendered, "rendered").contains("Output: none\n").
			contains("Output: 11 characters; the agent received all of it\n").
			contains("Output: 11 characters\n").
			doesNotContain("1\tsample");
		requireThat(rendered.contains("input_tokens"), "telemetry").isFalse();
	}

	/**
	 * Verifies that the recorded command results preserve all exit-status forms and unknown statuses.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void preservesRecordedExitStatuses() throws IOException
	{
		// Requirement: R-E21
		SessionEvidence evidence = parse(recorded("claude-exit-statuses.jsonl"));
		List<Integer> statuses = evidence.entries().stream().map(entry -> ((SessionEvidence.Tool) entry).exitStatus()).
			toList();
		requireThat(statuses, "statuses").isEqualTo(Arrays.asList(0, 1, 42, 1, null, null));
		requireThat(evidence.received().contains("Exit code 42"), "errorOutput").isTrue();
		requireThat(evidence.received().stream().anyMatch(result ->
			result.startsWith("Command running in background with ID: bty01sqtm.")), "backgroundOutput").isTrue();
		requireThat(evidence.render(), "rendered").doesNotContain("only part");
	}

	/**
	 * Verifies that a child's recorded calls are attributed to that child and that its report excludes metadata.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void rendersRecordedChildCalls() throws IOException
	{
		// Requirement: R-E21
		SessionEvidence evidence = parse(recorded("claude-child-tool-calls.jsonl"));
		List<SessionEvidence.Tool> tools = evidence.entries().stream().filter(SessionEvidence.Tool.class::isInstance).
			map(SessionEvidence.Tool.class::cast).toList();
		requireThat(tools.size(), "tools.size()").isEqualTo(2);
		SessionEvidence.Tool root = tools.getFirst();
		requireThat(root.agent(), "root.agent").isEqualTo("root");
		requireThat(root.output(), "root.output").isEqualTo("child done");
		requireThat(tools.get(1), "child").isEqualTo(new SessionEvidence.Tool(1,
			"toolu_01PLv6dPTkvN3FJipupbgSzv", "Bash", "true", true, null, "(Bash completed with no output)"));
		String rendered = evidence.render();
		requireThat(rendered.contains("toolu_01PLv6dPTkvN3FJipupbgSzv"), "childAttribution").isTrue();
		requireThat(rendered.contains("subagent_tokens"), "childTelemetry").isFalse();
		requireThat(rendered.contains("totalDurationMs"), "durationTelemetry").isFalse();
	}

	/**
	 * Verifies that the record of a root that starts a child and waits for it holds the start with the child's
	 * identity and assignment, followed by the report that the root received from that child.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void recordsChildStartAndReceivedReport() throws IOException
	{
		// Requirement: R-E21
		String rendered = parse(recorded("claude-child-tool-calls.jsonl")).render();
		String started = "Actor: root\nStarted child toolu_01PLv6dPTkvN3FJipupbgSzv (Run true via Bash)\n" +
			"Assignment:\nRun exactly one Bash tool call with the command: true. Do nothing else. Then reply with " +
			"exactly: child done\n";
		String received = "Actor: root\nReceived from child toolu_01PLv6dPTkvN3FJipupbgSzv (Run true via Bash):\n" +
			"child done\n";
		requireThat(rendered, "rendered").contains(started);
		requireThat(rendered.substring(rendered.indexOf(started)), "afterStart").contains(received);
	}

	/**
	 * Verifies that the record of a root that starts two background children holds what Claude reported when each
	 * child finished, attributed to that child, and nothing for a background command that is not a child.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void recordsBackgroundChildNotifications() throws IOException
	{
		// Requirement: R-E21
		List<String> lines;
		try (InputStream stream = StreamJsonEvidenceTest.class.getResourceAsStream(
			"/runner/turn-boundary/two-children.jsonl"))
		{
			requireThat(stream, "resource").isNotNull();
			lines = new String(stream.readAllBytes(), UTF_8).lines().toList();
		}
		String rendered = parse(lines).render();
		requireThat(rendered, "rendered").
			contains("Actor: root\nStarted child toolu_01CujbSRcNLWYUfPRb15WtEs\n").
			contains("Actor: root\nStarted child toolu_015ScoiHLTKwce3esAMpcgZU\n").
			contains("Actor: root\nChild toolu_01CujbSRcNLWYUfPRb15WtEs finished\nStatus: completed\nSummary: DONE-A\n").
			contains("Actor: root\nChild toolu_015ScoiHLTKwce3esAMpcgZU finished\nStatus: completed\nSummary: DONE-B\n").
			doesNotContain("Sleep for 5 seconds");
	}

	/**
	 * Verifies that a hook is represented by its outcome and that session-start text and telemetry are absent.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void recordsHooksByStatus() throws IOException
	{
		// Requirement: R-E21
		List<String> lines = new ArrayList<>(recorded("claude-single-turn.jsonl"));
		lines.add("{\"type\":\"system\",\"subtype\":\"hook_response\",\"hook_id\":\"other\"," +
			"\"hook_name\":\"PreToolUse:check\",\"outcome\":\"error\",\"stdout\":\"INJECTED_HOOK_PROMPT\"," +
			"\"stderr\":\"PRIVATE_HOOK_LOG\"}");
		lines.add("{\"type\":\"system\",\"subtype\":\"init\",\"prompt\":\"INJECTED_SESSION_PROMPT\"}");
		lines.add("{\"type\":\"stream_event\",\"event\":{\"type\":\"content_block_delta\"," +
			"\"delta\":{\"type\":\"text_delta\",\"text\":\"DELTA_FRAGMENT\"}}}");
		SessionEvidence evidence = parse(lines);
		requireThat(evidence.entries(), "entries").isEqualTo(List.of(
			new SessionEvidence.Hook(1, "root", "SessionStart:startup", "success"),
			new SessionEvidence.Hook(1, "root", "PreToolUse:check", "error")));
		String rendered = evidence.render();
		for (String excluded : List.of("INJECTED_HOOK_PROMPT", "PRIVATE_HOOK_LOG", "INJECTED_SESSION_PROMPT",
			"DELTA_FRAGMENT", "modelUsage", "rate_limit_info", "input_tokens"))
		{
			requireThat(rendered.contains(excluded), excluded).isFalse();
		}
	}

	/**
	 * Verifies that session-start hooks preceding the next init event belong to the next turn.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void attributesSessionStartHooksToTheirTurn() throws IOException
	{
		// Requirement: R-E21
		List<String> recording = recorded("claude-single-turn.jsonl");
		List<String> lines = new ArrayList<>(recording);
		lines.addAll(recording);
		SessionEvidence evidence = parse(lines);
		requireThat(evidence.entries(), "entries").isEqualTo(List.of(
			new SessionEvidence.Hook(1, "root", "SessionStart:startup", "success"),
			new SessionEvidence.Hook(2, "root", "SessionStart:startup", "success")));
	}

	/**
	 * Verifies that messages between calls preserve ordering without repeating root replies, and that a repeated
	 * complete message or result does not duplicate its entry.
	 */
	@Test
	public void ordersAndDeduplicatesMessagesAcrossTurns()
	{
		// Requirement: R-E21
		String first = "{\"type\":\"assistant\",\"message\":{\"id\":\"m1\",\"content\":[" +
			"{\"type\":\"text\",\"text\":\"ROOT_REPLY\"},{\"type\":\"tool_use\",\"id\":\"t1\"," +
			"\"name\":\"Bash\",\"input\":{\"command\":\"printf first\"}}]}}";
		String result = "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\"," +
			"\"tool_use_id\":\"t1\",\"content\":\"first\"}]},\"tool_use_result\":{\"stdout\":\"first\"," +
			"\"stderr\":\"\",\"interrupted\":false}}";
		SessionEvidence evidence = parse(List.of(first, first, result, result,
			"{\"type\":\"result\",\"result\":\"ROOT_REPLY\",\"is_error\":false}",
			"{\"type\":\"system\",\"subtype\":\"init\"}",
			"{\"type\":\"assistant\",\"message\":{\"id\":\"m2\",\"content\":[{\"type\":\"tool_use\"," +
				"\"id\":\"t2\",\"name\":\"Read\",\"input\":{\"file_path\":\"file.txt\"}}]}}",
			"{\"type\":\"assistant\",\"message\":{\"id\":\"m3\",\"content\":[{\"type\":\"text\"," +
				"\"text\":\"ROOT_FINAL_REPLY\"}]}}"));
		requireThat(evidence.entries(), "entries").isEqualTo(List.of(
			new SessionEvidence.Assistant(1, "root", 1, ""),
			new SessionEvidence.Tool(1, "root", "Bash", "printf first", true, 0, "first"),
			new SessionEvidence.Tool(2, "root", "Read", "{\"file_path\":\"file.txt\"}", false, null, ""),
			new SessionEvidence.Assistant(2, "root", 1, "")));
		requireThat(evidence.render().contains("ROOT_REPLY"), "rootReply").isFalse();
		requireThat(evidence.render().contains("ROOT_FINAL_REPLY"), "rootFinalReply").isFalse();
	}

	/**
	 * Verifies that the full stdout and stderr survive even when their size exceeds 200,000 characters and the
	 * tool-result display contains a shortened value, and that the evidence reports that the agent received only part
	 * of the output.
	 */
	@Test
	public void preservesLargeCompleteToolOutput()
	{
		// Requirement: R-E21
		String stdout = "output\n".repeat(30_000) + "FINAL_OUTPUT_BYTE";
		String stderr = "diagnostic\n";
		SessionEvidence evidence = parse(List.of(
			"{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"large\"," +
				"\"name\":\"Bash\",\"input\":{\"command\":\"cat large.txt\"}}]}}",
			JSON.writeValueAsString(Map.of("type", "user", "message", Map.of("content", List.of(
				Map.of("type", "tool_result", "tool_use_id", "large", "content", "shortened"))),
				"tool_use_result", Map.of("stdout", stdout, "stderr", stderr, "interrupted", false)))));
		SessionEvidence.Tool tool = (SessionEvidence.Tool) evidence.entries().getFirst();
		requireThat(tool.output(), "output").isEqualTo(stdout + stderr);
		requireThat(evidence.received(), "received").isEqualTo(List.of("shortened"));
		requireThat(evidence.render(), "rendered").
			contains("Output: " + (stdout + stderr).length() + " characters; the agent received only part of it");
	}

	/**
	 * Verifies that interrupted commands and errors without the reported exit-code form preserve output without
	 * inventing an exit status, and that an error reported as a string is the content shown to the agent, since Claude
	 * Code's string is its own wording of the error rather than fuller output.
	 */
	@Test
	public void preservesOutputWithUnreportedExitStatus()
	{
		// Requirement: R-E21
		SessionEvidence evidence = parse(List.of(
			"{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"i\"," +
				"\"name\":\"Bash\",\"input\":{\"command\":\"make\"}}]}}",
			"{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"i\"," +
				"\"content\":\"interrupted\"}]},\"tool_use_result\":{\"stdout\":\"partial output\"," +
				"\"stderr\":\"\",\"interrupted\":true}}",
			"{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"u\"," +
				"\"name\":\"Bash\",\"input\":{\"command\":\"rm forbidden\"}}]}}",
			"{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"u\"," +
				"\"is_error\":true,\"content\":\"rejected\"}]},\"tool_use_result\":\"Error: permission denied\"}",
			"{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"b\"," +
				"\"name\":\"Bash\",\"input\":{\"command\":\"sleep 60\"}}]}}",
			"{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"b\"," +
				"\"is_error\":true,\"content\":\"<tool_use_error>Blocked: sleep 60</tool_use_error>\"}]}," +
				"\"tool_use_result\":\"Error: Blocked: sleep 60\"}"));
		requireThat(evidence.entries(), "entries").isEqualTo(List.of(
			new SessionEvidence.Tool(1, "root", "Bash", "make", true, null, "partial output"),
			new SessionEvidence.Tool(1, "root", "Bash", "rm forbidden", true, null, "rejected"),
			new SessionEvidence.Tool(1, "root", "Bash", "sleep 60", true, null,
				"<tool_use_error>Blocked: sleep 60</tool_use_error>")));
		requireThat(evidence.render(), "rendered").
			contains("Output: 8 characters; the agent received all of it\n").
			contains("Output: 50 characters; the agent received all of it\n");
	}

	/**
	 * Verifies that background child calls, statuses and messages retain the turn that spawned the child after the
	 * root begins its next turn.
	 */
	@Test
	public void attributesLateChildActivityToItsTurn()
	{
		// Requirement: R-E21
		SessionEvidence evidence = parse(List.of(
			"{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"id\":\"agent-call\"," +
				"\"name\":\"Agent\",\"input\":{\"prompt\":\"Run true\"}}]}}",
			"{\"type\":\"result\",\"is_error\":false}",
			"{\"type\":\"system\",\"subtype\":\"init\"}",
			"{\"type\":\"assistant\",\"parent_tool_use_id\":\"agent-call\",\"message\":{\"content\":[" +
				"{\"type\":\"tool_use\",\"id\":\"child-call\",\"name\":\"Bash\",\"input\":{\"command\":\"true\"}}]}}",
			"{\"type\":\"user\",\"parent_tool_use_id\":\"agent-call\",\"message\":{\"content\":[" +
				"{\"type\":\"tool_result\",\"tool_use_id\":\"child-call\",\"content\":\"child stdout\"}]}," +
				"\"tool_use_result\":{\"stdout\":\"child stdout\",\"stderr\":\"\",\"interrupted\":false}}",
			"{\"type\":\"assistant\",\"parent_tool_use_id\":\"agent-call\",\"message\":{\"id\":\"child-text\"," +
				"\"content\":[{\"type\":\"text\",\"text\":\"child done\"}]}}",
			"{\"type\":\"assistant\",\"message\":{\"id\":\"root-next\",\"content\":[" +
				"{\"type\":\"text\",\"text\":\"ROOT_NEXT\"}]}}"));
		requireThat(evidence.entries(), "entries").isEqualTo(List.of(
			new SessionEvidence.Tool(1, "root", "Agent", "{\"prompt\":\"Run true\"}", false, null, ""),
			new SessionEvidence.ChildEvent(1, "root", "agent-call", "", SessionEvidence.ChildEvent.Kind.STARTED,
				"Run true"),
			new SessionEvidence.Tool(1, "agent-call", "Bash", "true", true, 0, "child stdout"),
			new SessionEvidence.Assistant(1, "agent-call", 1, "child done"),
			new SessionEvidence.Assistant(2, "root", 1, "")));
	}
}
