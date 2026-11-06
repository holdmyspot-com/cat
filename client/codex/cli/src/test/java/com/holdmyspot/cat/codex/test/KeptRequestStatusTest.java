package com.holdmyspot.cat.codex.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.secrets.CodexGetHistorySecrets;
import com.holdmyspot.cat.codex.secrets.CodexSessionStartHookSecrets;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.TestToolScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;

/**
 * Verifies that after Codex compacts a thread, the SessionStart context and the {@code get-history request-status}
 * command report whether a later turn answered each user message that the compaction kept, as the thread's rollout
 * records it. Each test writes a fake rollout in Codex's record shapes in place of the session log that Codex keeps.
 */
public final class KeptRequestStatusTest
{
	private static final String SESSION_ID = "019a2b3c-4d5e-7f60-8a1b-2c3d4e5f6a7b";
	private static final String UPDATE_REQUEST = "Update CAT from https://github.com/holdmyspot-com/cat/releases";
	private static final String UPDATE_ANSWER = "CAT remains at 1.0 because the latest release is the installed one.";

	/**
	 * Verifies a kept message that a turn answered before the compaction is reported answered, with when it was sent,
	 * when it was answered, and the answer's opening text.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsAnsweredKeptMessage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.answeredTurn("t1", "2026-10-31T01:40:00.125Z", UPDATE_REQUEST, "2026-10-31T01:43:07.500Z",
				UPDATE_ANSWER);
			rollout.compacted("2026-10-31T02:00:00Z", Rollout.typed(UPDATE_REQUEST));

			String context = context(invokeHook(project, rollout.write(project), "compact"));

			requireThat(context, "context").contains("# Kept User Messages").
				contains("Codex compacted this conversation at 2026-10-31T02:00:00Z.").
				contains("- \"" + UPDATE_REQUEST + "\" (sent 2026-10-31T01:40:00Z): answered at " +
					"2026-10-31T01:43:07Z, turn completed: \"" + UPDATE_ANSWER + "\"");
		}
	}

	/**
	 * Verifies a kept message whose turn the user interrupted is reported aborted.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsAbortedKeptMessage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.add(Rollout.taskStarted("2026-10-31T17:00:00Z", "t1"));
			rollout.add(Rollout.user("2026-10-31T17:00:00Z", "t1", "Run the campaign"));
			rollout.add(Rollout.turnAborted("2026-10-31T17:20:18Z", "t1"));
			rollout.compacted("2026-10-31T18:00:00Z", Rollout.typed("Run the campaign"));

			String context = context(invokeHook(project, rollout.write(project), "compact"));

			requireThat(context, "context").contains("- \"Run the campaign\" (sent 2026-10-31T17:00:00Z): no answer, " +
				"turn aborted at 2026-10-31T17:20:18Z");
		}
	}

	/**
	 * Verifies a kept message that no turn answered is reported unanswered.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsUnansweredKeptMessage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.add(Rollout.taskStarted("2026-10-31T17:00:00Z", "t1"));
			rollout.add(Rollout.user("2026-10-31T17:00:00Z", "t1", "Run the campaign"));
			rollout.add(Rollout.commentary("2026-10-31T17:01:00Z", "t1", "Starting the campaign"));
			rollout.compacted("2026-10-31T18:00:00Z", Rollout.typed("Run the campaign"));

			String context = context(invokeHook(project, rollout.write(project), "compact"));

			requireThat(context, "context").contains("- \"Run the campaign\" (sent 2026-10-31T17:00:00Z): no answer, " +
				"turn not finished");
		}
	}

	/**
	 * Verifies that the same text sent again after the compaction is a new request: the hook reports the kept message's
	 * own answer, and the command reports the message sent after the compaction separately, without that answer.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void separatesTextSentAgainAfterCompaction() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.answeredTurn("t1", "2026-10-31T01:40:00Z", UPDATE_REQUEST, "2026-10-31T01:43:00Z", UPDATE_ANSWER);
			rollout.compacted("2026-10-31T02:00:00Z", Rollout.typed(UPDATE_REQUEST));
			rollout.add(Rollout.taskStarted("2026-10-31T09:00:00Z", "t2"));
			rollout.add(Rollout.user("2026-10-31T09:00:00Z", "t2", UPDATE_REQUEST));
			Path codexHome = project.resolve("codex-home");
			Path transcript = rollout.write(Files.createDirectories(codexHome.resolve("sessions/2026/10/31")).
				resolve("rollout-2026-10-31T01-40-00-" + SESSION_ID + ".jsonl"));

			String context = context(invokeHook(project, transcript, "compact"));
			String status = CodexGetHistorySecrets.getCodexGetHistoryAccess().execute(scope,
				Map.of("CODEX_HOME", codexHome.toString()), new String[]{SESSION_ID, "request-status"},
				new ByteArrayInputStream(UPDATE_REQUEST.getBytes(UTF_8)));

			requireThat(context, "context").contains("(sent 2026-10-31T01:40:00Z): answered at 2026-10-31T01:43:00Z").
				doesNotContain("2026-10-31T09:00:00Z");
			requireThat(status, "status").contains("compacted_at=2026-10-31T02:00:00Z\n").
				contains("before_compaction: \"" + UPDATE_REQUEST + "\" (sent 2026-10-31T01:40:00Z): answered at " +
					"2026-10-31T01:43:00Z, turn completed: \"" + UPDATE_ANSWER + "\"\n").
				contains("after_compaction: \"" + UPDATE_REQUEST + "\" (sent 2026-10-31T09:00:00Z): no answer, " +
					"turn not finished\n");
		}
	}

	/**
	 * Verifies a kept message that Codex cut short still matches the message it recorded in full, whether Codex kept
	 * only its start or its start and end around a truncation marker.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void matchesKeptMessageCutShort() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			String longRequest = "Investigate the build failure in the distribution module and report its cause";
			String otherRequest = "Summarize the release notes for version 1.0 and list every breaking change";
			Rollout rollout = new Rollout();
			rollout.answeredTurn("t1", "2026-10-31T01:00:00Z", longRequest, "2026-10-31T01:05:00Z",
				"The build failed because the cache was stale.");
			rollout.answeredTurn("t2", "2026-10-31T01:10:00Z", otherRequest, "2026-10-31T01:15:00Z",
				"Version 1.0 has no breaking changes.");
			rollout.compacted("2026-10-31T02:00:00Z", Rollout.typed("Investigate the build failure"),
				Rollout.typed("Summarize the release…9 tokens truncated…every breaking change"));

			String context = context(invokeHook(project, rollout.write(project), "compact"));

			requireThat(context, "context").contains("(sent 2026-10-31T01:00:00Z): answered at 2026-10-31T01:05:00Z").
				contains("(sent 2026-10-31T01:10:00Z): answered at 2026-10-31T01:15:00Z").
				doesNotContain("not found in the session log");
		}
	}

	/**
	 * Verifies the section lists only messages that the user typed, not the user-role context that Codex adds to a
	 * conversation, a developer message, or the compaction's summary.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void excludesHarnessInjectedItems() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.add(Rollout.taskStarted("2026-10-31T01:00:00Z", "t1"));
			rollout.add(Rollout.message("2026-10-31T01:00:00Z", "t1", "user",
				"<environment_context>\n  <cwd>/workspace</cwd>\n</environment_context>", "unknown"));
			rollout.answeredTurn("t1", "2026-10-31T01:00:01Z", UPDATE_REQUEST, "2026-10-31T01:03:00Z", UPDATE_ANSWER);
			rollout.compacted("2026-10-31T02:00:00Z",
				Rollout.item("user", "<environment_context>\n  <cwd>/workspace</cwd>\n</environment_context>", "unknown"),
				Rollout.item("user", "<codex_internal_context source=\"user_goal\">Keep going</codex_internal_context>",
					"unknown"),
				Rollout.item("user", "# AGENTS.md instructions for /workspace\n\n<INSTRUCTIONS>\nbody\n</INSTRUCTIONS>",
					"unknown"),
				Rollout.item("user", "<skill>\n<name>get-history</name>\n</skill>", "unknown"),
				Rollout.item("user", "Labelled as Codex context although it reads like a request",
					"environments.environment_context"),
				Rollout.item("developer", "<permissions instructions>sandbox</permissions instructions>", "unknown"),
				Rollout.typed(UPDATE_REQUEST),
				Map.of("type", "compaction", "encrypted_content", "opaque"));

			String context = context(invokeHook(project, rollout.write(project), "compact"));
			String section = context.substring(context.indexOf("# Kept User Messages"));

			requireThat(section.lines().filter(line -> line.startsWith("- ")).toList(), "entries").size().
				isEqualTo(1);
			requireThat(section, "section").contains(UPDATE_REQUEST).doesNotContain("environment_context").
				doesNotContain("codex_internal_context").doesNotContain("AGENTS.md").doesNotContain("<skill>").
				doesNotContain("Labelled as Codex context").doesNotContain("permissions instructions");
		}
	}

	/**
	 * Verifies that a session log that is missing, or that holds a record that is not JSON, leaves the SessionStart
	 * response as it is without a session log, and does not stop the session.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void unreadableLogLeavesResponseUnchanged() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Path unreadable = project.resolve("unreadable.jsonl");
			Files.writeString(unreadable, "{\"type\":\"compacted\",\"payload\":{\"replacement_history\":[\n", UTF_8);

			HookResult withoutLog = invokeHook(project, null, "resume");
			HookResult missing = invokeHook(project, project.resolve("missing.jsonl"), "compact");
			HookResult malformed = invokeHook(project, unreadable, "compact");

			requireThat(missing.output(), "missingOutput").isEqualTo(withoutLog.output());
			requireThat(malformed.output(), "malformedOutput").isEqualTo(withoutLog.output());
			requireThat(readJson(missing.output()).has("continue"), "missingStops").isFalse();
		}
	}

	/**
	 * Verifies that a SessionStart whose source is not {@code compact} adds no section, even when the session log
	 * records a compaction.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void otherSourcesAddNoSection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.answeredTurn("t1", "2026-10-31T01:40:00Z", UPDATE_REQUEST, "2026-10-31T01:43:00Z", UPDATE_ANSWER);
			rollout.compacted("2026-10-31T02:00:00Z", Rollout.typed(UPDATE_REQUEST));
			Path transcript = rollout.write(project);

			for (String source : new String[]{"startup", "resume", "clear"})
			{
				requireThat(context(invokeHook(project, transcript, source)), source + "Context").
					doesNotContain("# Kept User Messages").doesNotContain(UPDATE_REQUEST);
			}
		}
	}

	/**
	 * Runs the SessionStart hook for {@link #SESSION_ID} in a project whose plugin holds the reading guidance.
	 *
	 * @param project the project directory, which also holds the plugin
	 * @param transcript the session log that the input names, or {@code null} to name none
	 * @param source the SessionStart source
	 * @return the captured standard streams
	 * @throws IOException if the plugin cannot be written
	 */
	private static HookResult invokeHook(Path project, Path transcript, String source) throws IOException
	{
		Path plugin = project.resolve("plugin");
		Files.createDirectories(plugin.resolve("rules/codex"));
		Files.writeString(plugin.resolve("rules/codex/read-file.md"), "# Reading Command Output\n\nRun `" +
			ProjectRuleLoader.PLUGIN_ROOT_DIRECTIVE + "/bin/run-part`.\n", UTF_8);
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("session_id", SESSION_ID);
		event.put("cwd", project.toString());
		event.put("hook_event_name", "SessionStart");
		event.put("source", source);
		if (transcript != null)
			event.put("transcript_path", transcript.toString());
		Map<String, String> environment = new HashMap<>();
		environment.put("PLUGIN_ROOT", plugin.toString());
		environment.put(Platform.current().homeVariable(), project.resolve("home").toString());
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ByteArrayOutputStream error = new ByteArrayOutputStream();
		CodexSessionStartHookSecrets.getCodexSessionStartHookAccess().run(new String[0],
			new ByteArrayInputStream(writeJson(event).getBytes(UTF_8)), output, new PrintStream(error, true, UTF_8),
			environment, project, Yielding.waiting(), GitProjects.IGNORING, Platform.current(), List::of, (_, _) ->
			{
				throw new AssertionError("The hook started a session launcher");
			});
		return new HookResult(output.toString(UTF_8), error.toString(UTF_8));
	}

	/**
	 * Returns the context of a SessionStart response.
	 *
	 * @param result the captured hook result
	 * @return the response's additional context
	 */
	private static String context(HookResult result)
	{
		return readJson(result.output()).at("/hookSpecificOutput/additionalContext").asString();
	}

	/**
	 * Parses JSON text.
	 *
	 * @param text the JSON text
	 * @return the parsed value
	 */
	private static JsonNode readJson(String text)
	{
		try (ToolScope scope = new TestToolScope())
		{
			return scope.json().readTree(text);
		}
	}

	/**
	 * Writes a value as one line of JSON.
	 *
	 * @param value the value
	 * @return the JSON text
	 */
	private static String writeJson(Object value)
	{
		try (ToolScope scope = new TestToolScope())
		{
			return scope.json().writeValueAsString(value);
		}
	}

	/**
	 * Captures the hook's standard streams.
	 *
	 * @param output the standard output
	 * @param error the standard error
	 */
	private record HookResult(String output, String error)
	{
	}

	/**
	 * A fake Codex rollout, built from the record shapes that Codex writes, one JSON object per line.
	 */
	private static final class Rollout
	{
		private final List<Map<String, Object>> records = new ArrayList<>();

		/**
		 * Appends a record.
		 *
		 * @param record the record
		 */
		void add(Map<String, Object> record)
		{
			records.add(record);
		}

		/**
		 * Appends a turn in which the user sends a message and the agent gives a final answer before the turn completes.
		 *
		 * @param turnId the turn ID
		 * @param sentAt when the user sent the message
		 * @param request the message's text
		 * @param answeredAt when the agent answered
		 * @param answer the final answer's text
		 */
		void answeredTurn(String turnId, String sentAt, String request, String answeredAt, String answer)
		{
			add(taskStarted(sentAt, turnId));
			add(user(sentAt, turnId, request));
			add(commentary(sentAt, turnId, "Working on it"));
			add(message(answeredAt, turnId, "assistant", answer, "unknown", "final_answer"));
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("type", "task_complete");
			payload.put("turn_id", turnId);
			payload.put("last_agent_message", answer);
			add(record(answeredAt, "event_msg", payload));
		}

		/**
		 * Appends a compaction whose replacement history keeps the given items.
		 *
		 * @param time when the compaction happened
		 * @param items the replacement history's items
		 */
		void compacted(String time, Object... items)
		{
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("message", "");
			payload.put("replacement_history", List.of(items));
			payload.put("window_number", 1);
			add(record(time, "compacted", payload));
		}

		/**
		 * Returns a replacement-history item for a message that the user typed.
		 *
		 * @param text the message's text, as the compaction kept it
		 * @return the item
		 */
		static Map<String, Object> typed(String text)
		{
			return item("user", text, "unknown");
		}

		/**
		 * Writes the rollout.
		 *
		 * @param target the rollout file, or a directory to write {@code rollout.jsonl} in
		 * @return the rollout file
		 * @throws IOException if the file cannot be written
		 */
		Path write(Path target) throws IOException
		{
			Path file = target;
			if (Files.isDirectory(target))
				file = target.resolve("rollout.jsonl");
			StringBuilder text = new StringBuilder();
			for (Map<String, Object> record : records)
				text.append(writeJson(record)).append('\n');
			Files.writeString(file, text, UTF_8);
			return file;
		}

		/**
		 * Returns a {@code task_started} event.
		 *
		 * @param time the event's time
		 * @param turnId the turn ID
		 * @return the record
		 */
		static Map<String, Object> taskStarted(String time, String turnId)
		{
			return record(time, "event_msg", Map.of("type", "task_started", "turn_id", turnId));
		}

		/**
		 * Returns a {@code turn_aborted} event.
		 *
		 * @param time the event's time
		 * @param turnId the turn ID
		 * @return the record
		 */
		static Map<String, Object> turnAborted(String time, String turnId)
		{
			return record(time, "event_msg", Map.of("type", "turn_aborted", "turn_id", turnId, "reason",
				"interrupted"));
		}

		/**
		 * Returns a message that the user typed.
		 *
		 * @param time when it was sent
		 * @param turnId the turn ID
		 * @param text its text
		 * @return the record
		 */
		static Map<String, Object> user(String time, String turnId, String text)
		{
			return message(time, turnId, "user", text, "unknown");
		}

		/**
		 * Returns an assistant commentary message.
		 *
		 * @param time when it was given
		 * @param turnId the turn ID
		 * @param text its text
		 * @return the record
		 */
		static Map<String, Object> commentary(String time, String turnId, String text)
		{
			return message(time, turnId, "assistant", text, "unknown", "commentary");
		}

		/**
		 * Returns a message record without a phase.
		 *
		 * @param time the record's time
		 * @param turnId the turn ID
		 * @param role the message's role
		 * @param text its text
		 * @param kind the kind that Codex labels its content with
		 * @return the record
		 */
		static Map<String, Object> message(String time, String turnId, String role, String text, String kind)
		{
			return message(time, turnId, role, text, kind, null);
		}

		/**
		 * Returns a message record.
		 *
		 * @param time the record's time
		 * @param turnId the turn ID
		 * @param role the message's role
		 * @param text its text
		 * @param kind the kind that Codex labels its content with
		 * @param phase the message's phase, or {@code null} for none
		 * @return the record
		 */
		static Map<String, Object> message(String time, String turnId, String role, String text, String kind,
			String phase)
		{
			Map<String, Object> payload = item(role, text, kind);
			if (phase != null)
				payload.put("phase", phase);
			payload.put("internal_chat_message_metadata_passthrough", Map.of("turn_id", turnId,
				"content_item_kinds", List.of(kind)));
			return record(time, "response_item", payload);
		}

		/**
		 * Returns a message item.
		 *
		 * @param role the message's role
		 * @param text its text
		 * @param kind the kind that Codex labels its content with
		 * @return the item
		 */
		static Map<String, Object> item(String role, String text, String kind)
		{
			String contentType;
			if (role.equals("assistant"))
				contentType = "output_text";
			else
				contentType = "input_text";
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("type", "message");
			item.put("role", role);
			item.put("content", List.of(Map.of("type", contentType, "text", text)));
			item.put("internal_chat_message_metadata_passthrough", Map.of("content_item_kinds", List.of(kind)));
			return item;
		}

		/**
		 * Returns a rollout record.
		 *
		 * @param time the record's time
		 * @param type the record's type
		 * @param payload its payload
		 * @return the record
		 */
		static Map<String, Object> record(String time, String type, Map<String, Object> payload)
		{
			Map<String, Object> record = new LinkedHashMap<>();
			record.put("timestamp", time);
			record.put("type", type);
			record.put("payload", payload);
			return record;
		}
	}
}
