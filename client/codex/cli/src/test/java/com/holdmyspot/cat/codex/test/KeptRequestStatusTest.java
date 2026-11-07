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
	private static final String GRANDPARENT_ID = "019a2b3c-0000-7000-8000-000000000001";
	private static final String PARENT_ID = "019a2b3c-0000-7000-8000-000000000002";
	private static final String FORK_ID = "019a2b3c-0000-7000-8000-000000000003";
	private static final String REVERTED_THREAD_ID = "019a2b3c-0000-7000-8000-000000000004";
	private static final String PARENT_REQUEST = "Make format_currency print negative amounts as -$75.25";
	private static final String PARENT_ANSWER = "format_currency now prints -$75.25";
	private static final String FORK_REQUEST = "Add a --customer option to the CLI";
	private static final String FORK_ANSWER = "The CLI accepts --customer";

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

			requireThat(context, "context").contains("- \"Run the campaign\" (sent 2026-10-31T17:00:00Z): no answer in its " +
				"own turn, turn aborted at 2026-10-31T17:20:18Z\n");
		}
	}

	/**
	 * Verifies a kept message whose turn the user interrupted is reported aborted when Codex recorded the interruption
	 * only as its {@code <turn_aborted>} developer message, with no {@code turn_aborted} event, and that a later turn
	 * that completed does not change this. The hook and the {@code get-history request-status} command report the same
	 * end.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsAbortedKeptMessageFromAbortNotice() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.add(Rollout.taskStarted("2026-10-31T17:00:00Z", "t1"));
			rollout.add(Rollout.user("2026-10-31T17:00:00Z", "t1", "Run the campaign"));
			rollout.add(Rollout.commentary("2026-10-31T17:01:00Z", "t1", "Starting the campaign"));
			rollout.add(Rollout.abortNotice("2026-10-31T17:20:18Z", "t1"));
			rollout.answeredTurn("t2", "2026-10-31T17:30:00Z", "Summarize the campaign", "2026-10-31T17:31:00Z",
				"The campaign is running");
			rollout.compacted("2026-10-31T18:00:00Z", Rollout.typed("Run the campaign"));
			Path codexHome = project.resolve("codex-home");
			Path transcript = rollout.write(Files.createDirectories(codexHome.resolve("sessions/2026/10/31")).
				resolve("rollout-2026-10-31T17-00-00-" + SESSION_ID + ".jsonl"));

			String context = context(invokeHook(project, transcript, "compact"));
			String status = CodexGetHistorySecrets.getCodexGetHistoryAccess().execute(scope,
				Map.of("CODEX_HOME", codexHome.toString()), new String[]{SESSION_ID, "request-status"},
				new ByteArrayInputStream("Run the campaign".getBytes(UTF_8)));

			requireThat(context, "context").contains("- \"Run the campaign\" (sent 2026-10-31T17:00:00Z): no answer in its " +
				"own turn, turn aborted at 2026-10-31T17:20:18Z; later turns completed at 2026-10-31T17:31:00Z\n");
			requireThat(status, "status").contains("before_compaction: \"Run the campaign\" " +
				"(sent 2026-10-31T17:00:00Z): no answer in its own turn, turn aborted at 2026-10-31T17:20:18Z; later " +
				"turns completed at 2026-10-31T17:31:00Z\n");
		}
	}

	/**
	 * Verifies a kept message whose turn left no end record, and with no abort notice, is reported as ended without
	 * completing once a later turn started.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void reportsKeptMessageEndedByLaterTurn() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.add(Rollout.taskStarted("2026-10-31T17:00:00Z", "t1"));
			rollout.add(Rollout.user("2026-10-31T17:00:00Z", "t1", "Run the campaign"));
			rollout.add(Rollout.commentary("2026-10-31T17:01:00Z", "t1", "Starting the campaign"));
			rollout.answeredTurn("t2", "2026-10-31T17:30:00Z", "Summarize the campaign", "2026-10-31T17:31:00Z",
				"The campaign is running");
			rollout.compacted("2026-10-31T18:00:00Z", Rollout.typed("Run the campaign"),
				Rollout.typed("Summarize the campaign"));

			String context = context(invokeHook(project, rollout.write(project), "compact"));

			requireThat(context, "context").contains("- \"Run the campaign\" (sent 2026-10-31T17:00:00Z): no answer in its " +
				"own turn, turn ended without completing; later turns completed at 2026-10-31T17:31:00Z\n").
				contains("- \"Summarize the campaign\" (sent 2026-10-31T17:30:00Z): answered at 2026-10-31T17:31:00Z, " +
					"turn completed: \"The campaign is running\"");
		}
	}

	/**
	 * Verifies a kept message that its own turn did not answer is reported with every later turn that completed, oldest
	 * first, so that the reader can check whether one of them finished the work, and that a later turn that did not
	 * complete is not listed.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void listsLaterCompletedTurns() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.add(Rollout.taskStarted("2026-10-31T17:00:00Z", "t1"));
			rollout.add(Rollout.user("2026-10-31T17:00:00Z", "t1", "Run the campaign"));
			rollout.add(Rollout.turnAborted("2026-10-31T17:20:18Z", "t1"));
			rollout.answeredTurn("t2", "2026-10-31T17:30:00Z", "Summarize the campaign", "2026-10-31T17:31:00Z",
				"The campaign is running");
			rollout.add(Rollout.taskStarted("2026-10-31T17:40:00Z", "t3"));
			rollout.add(Rollout.user("2026-10-31T17:40:00Z", "t3", "Stop the campaign"));
			rollout.add(Rollout.turnAborted("2026-10-31T17:41:00Z", "t3"));
			rollout.answeredTurn("t4", "2026-10-31T17:50:00Z", "Restart the campaign", "2026-10-31T17:50:30Z",
				"The campaign restarted");
			rollout.compacted("2026-10-31T18:00:00Z", Rollout.typed("Run the campaign"),
				Rollout.typed("Summarize the campaign"));

			String context = context(invokeHook(project, rollout.write(project), "compact"));

			requireThat(context, "context").contains("- \"Run the campaign\" (sent 2026-10-31T17:00:00Z): no answer in " +
				"its own turn, turn aborted at 2026-10-31T17:20:18Z; later turns completed at 2026-10-31T17:31:00Z, " +
				"2026-10-31T17:50:30Z\n").
				contains("(sent 2026-10-31T17:30:00Z): answered at 2026-10-31T17:31:00Z, turn completed: " +
					"\"The campaign is running\"\n");
		}
	}

	/**
	 * Verifies a kept message whose own turn completed without an answer is reported without any later turn, because a
	 * turn's own completion is not a later turn's.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void ownCompletedTurnIsNotListedAsLater() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Rollout rollout = new Rollout();
			rollout.add(Rollout.taskStarted("2026-10-31T17:00:00Z", "t1"));
			rollout.add(Rollout.user("2026-10-31T17:00:00Z", "t1", "Run the campaign"));
			rollout.add(Rollout.record("2026-10-31T17:05:00Z", "event_msg", Map.of("type", "task_complete", "turn_id",
				"t1")));
			rollout.compacted("2026-10-31T18:00:00Z", Rollout.typed("Run the campaign"));

			String context = context(invokeHook(project, rollout.write(project), "compact"));

			requireThat(context, "context").contains("- \"Run the campaign\" (sent 2026-10-31T17:00:00Z): no answer in " +
				"its own turn, turn completed\n");
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

			requireThat(context, "context").contains("- \"Run the campaign\" (sent 2026-10-31T17:00:00Z): no answer in its " +
				"own turn, turn not finished\n");
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
				contains("after_compaction: \"" + UPDATE_REQUEST + "\" (sent 2026-10-31T09:00:00Z): no answer in its " +
					"own turn, turn not finished\n");
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
	 * Verifies that a thread forked at a past turn reports a kept message from the parent's session log, as the parent
	 * recorded it before the fork point. The parent sent the same text again after the fork point, and the fork never
	 * saw that turn, so the report does not use its answer. The hook and the {@code get-history request-status} command
	 * report the same outcome, and a kept message that the fork itself recorded is reported too.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsParentLogUpToForkPoint() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			TestJvmScope scope = new TestJvmScope())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Path codexHome = project.resolve("codex-home");
			Path sessions = Files.createDirectories(codexHome.resolve("sessions/2026/11/06"));
			Rollout parent = new Rollout().numberedFrom(0);
			parent.add(Rollout.sessionMeta("2026-11-06T10:00:00Z", PARENT_ID, null, 0));
			parent.answeredTurn("t1", "2026-11-06T10:01:00Z", PARENT_REQUEST, "2026-11-06T10:02:00Z", PARENT_ANSWER);
			int forkPoint = parent.nextOrdinal();
			parent.answeredTurn("t2", "2026-11-06T10:03:00Z", PARENT_REQUEST, "2026-11-06T10:04:00Z",
				"Answered after the fork point");
			parent.write(sessions.resolve(logName(PARENT_ID)));
			Path transcript = writeFork(sessions.resolve(logName(FORK_ID)), PARENT_ID, forkPoint, PARENT_REQUEST);

			String context = context(invokeHook(project, transcript, "compact"));
			String status = CodexGetHistorySecrets.getCodexGetHistoryAccess().execute(scope,
				Map.of("CODEX_HOME", codexHome.toString()), new String[]{FORK_ID, "request-status"},
				new ByteArrayInputStream(PARENT_REQUEST.getBytes(UTF_8)));

			requireThat(context, "context").contains("- \"" + PARENT_REQUEST + "\" (sent 2026-11-06T10:01:00Z): " +
				"answered at 2026-11-06T10:02:00Z, turn completed: \"" + PARENT_ANSWER + "\"\n").
				contains("- \"" + FORK_REQUEST + "\" (sent 2026-11-06T11:01:00Z): answered at 2026-11-06T11:02:00Z, " +
					"turn completed: \"" + FORK_ANSWER + "\"").
				doesNotContain("not found in the session log").doesNotContain("Answered after the fork point");
			requireThat(status, "status").contains("before_compaction: \"" + PARENT_REQUEST + "\" (sent " +
				"2026-11-06T10:01:00Z): answered at 2026-11-06T10:02:00Z, turn completed: \"" + PARENT_ANSWER + "\"\n").
				doesNotContain("not found in the session log").doesNotContain("Answered after the fork point");
		}
	}

	/**
	 * Verifies that a thread forked from a fork reports kept messages from every ancestor's session log: the
	 * grandparent's, which the parent inherited, the parent's own, and the fork's own.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsEveryAncestorLogOfForkOfFork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Path sessions = Files.createDirectories(project.resolve("codex-home/sessions/2026/11/06"));
			Rollout grandparent = new Rollout().numberedFrom(0);
			grandparent.add(Rollout.sessionMeta("2026-11-06T09:00:00Z", GRANDPARENT_ID, null, 0));
			grandparent.answeredTurn("t1", "2026-11-06T09:01:00Z", "Format negative amounts", "2026-11-06T09:02:00Z",
				"Negative amounts print as -$75.25");
			int grandparentEnd = grandparent.nextOrdinal();
			grandparent.write(sessions.resolve(logName(GRANDPARENT_ID)));
			Rollout parent = new Rollout().numberedFrom(grandparentEnd);
			parent.add(Rollout.sessionMeta("2026-11-06T10:00:00Z", PARENT_ID, GRANDPARENT_ID, grandparentEnd));
			parent.answeredTurn("t2", "2026-11-06T10:01:00Z", PARENT_REQUEST, "2026-11-06T10:02:00Z", PARENT_ANSWER);
			int parentEnd = parent.nextOrdinal();
			parent.write(sessions.resolve(logName(PARENT_ID)));
			Path transcript = writeFork(sessions.resolve(logName(FORK_ID)), PARENT_ID, parentEnd, "Format negative amounts",
				PARENT_REQUEST);

			String context = context(invokeHook(project, transcript, "compact"));

			requireThat(context, "context").contains("- \"Format negative amounts\" (sent 2026-11-06T09:01:00Z): " +
				"answered at 2026-11-06T09:02:00Z, turn completed: \"Negative amounts print as -$75.25\"\n").
				contains("- \"" + PARENT_REQUEST + "\" (sent 2026-11-06T10:01:00Z): answered at 2026-11-06T10:02:00Z, " +
					"turn completed: \"" + PARENT_ANSWER + "\"\n").
				contains("- \"" + FORK_REQUEST + "\" (sent 2026-11-06T11:01:00Z)").
				doesNotContain("not found in the session log");
		}
	}

	/**
	 * Verifies that a fork whose parent's session log does not exist still reports the kept messages that the fork
	 * recorded, reports the parent's messages as not found, and does not stop the session.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void missingParentLogKeepsForkMessages() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Path sessions = Files.createDirectories(project.resolve("codex-home/sessions/2026/11/06"));
			Path transcript = writeFork(sessions.resolve(logName(FORK_ID)), PARENT_ID, 6, PARENT_REQUEST);

			HookResult result = invokeHook(project, transcript, "compact");
			String context = context(result);

			requireThat(context, "context").contains("- \"" + PARENT_REQUEST + "\": not found in the session log\n").
				contains("- \"" + FORK_REQUEST + "\" (sent 2026-11-06T11:01:00Z): answered at 2026-11-06T11:02:00Z, " +
					"turn completed: \"" + FORK_ANSWER + "\"");
			requireThat(readJson(result.output()).has("continue"), "stops").isFalse();
		}
	}

	/**
	 * Verifies that a parent's session log that ends before the fork point that the fork declares is read as far as it
	 * goes, and that its messages are reported with the outcomes it recorded.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void shortParentLogIsReadAsFarAsItGoes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Path sessions = Files.createDirectories(project.resolve("codex-home/sessions/2026/11/06"));
			Rollout parent = new Rollout().numberedFrom(0);
			parent.add(Rollout.sessionMeta("2026-11-06T10:00:00Z", PARENT_ID, null, 0));
			parent.answeredTurn("t1", "2026-11-06T10:01:00Z", PARENT_REQUEST, "2026-11-06T10:02:00Z", PARENT_ANSWER);
			parent.write(sessions.resolve(logName(PARENT_ID)));
			Path transcript = writeFork(sessions.resolve(logName(FORK_ID)), PARENT_ID, parent.nextOrdinal() + 100,
				PARENT_REQUEST);

			String context = context(invokeHook(project, transcript, "compact"));

			requireThat(context, "context").contains("- \"" + PARENT_REQUEST + "\" (sent 2026-11-06T10:01:00Z): " +
				"answered at 2026-11-06T10:02:00Z, turn completed: \"" + PARENT_ANSWER + "\"\n").
				doesNotContain("not found in the session log");
		}
	}

	/**
	 * Verifies that a fork reports kept messages from a parent whose thread was archived after the fork, which Codex
	 * moves out of {@code sessions/} into {@code archived_sessions/}.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsArchivedParentLog() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Path codexHome = project.resolve("codex-home");
			Path sessions = Files.createDirectories(codexHome.resolve("sessions/2026/11/06"));
			Path archived = Files.createDirectories(codexHome.resolve("archived_sessions"));
			int forkPoint = writeParent(archived.resolve(logName(PARENT_ID)), PARENT_ID);
			Path transcript = writeFork(sessions.resolve(logName(FORK_ID)), PARENT_ID, forkPoint, PARENT_REQUEST);

			String context = context(invokeHook(project, transcript, "compact"));

			requireThat(context, "context").contains("- \"" + PARENT_REQUEST + "\" (sent 2026-11-06T10:01:00Z): " +
				"answered at 2026-11-06T10:02:00Z, turn completed: \"" + PARENT_ANSWER + "\"\n").
				doesNotContain("not found in the session log");
		}
	}

	/**
	 * Verifies that a fork reports kept messages from the rollout that its {@code history_base} names when that
	 * rollout's thread was reverted: Codex then names the log after the thread and the rollout, and the fork refers to
	 * the rollout.
	 *
	 * @throws IOException if the test filesystem cannot be used
	 */
	@Test
	public void readsParentLogOfRevertedThread() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path project = cleanup.add(Files.createTempDirectory("cat-kept-status-"));
			Path sessions = Files.createDirectories(project.resolve("codex-home/sessions/2026/11/06"));
			int forkPoint = writeParent(sessions.resolve(logName(REVERTED_THREAD_ID + "_" + PARENT_ID)), PARENT_ID);
			Path transcript = writeFork(sessions.resolve(logName(FORK_ID)), PARENT_ID, forkPoint, PARENT_REQUEST);

			String context = context(invokeHook(project, transcript, "compact"));

			requireThat(context, "context").contains("- \"" + PARENT_REQUEST + "\" (sent 2026-11-06T10:01:00Z): " +
				"answered at 2026-11-06T10:02:00Z, turn completed: \"" + PARENT_ANSWER + "\"\n").
				doesNotContain("not found in the session log");
		}
	}

	/**
	 * Writes a parent session log that holds one answered turn, which {@link #PARENT_REQUEST} sent.
	 *
	 * @param log the file to write
	 * @param rolloutId the identifier that the log's {@code session_meta} gives the rollout
	 * @return the ordinal that follows the turn, where a fork of this log can begin
	 * @throws IOException if the file cannot be written
	 */
	private static int writeParent(Path log, String rolloutId) throws IOException
	{
		Rollout parent = new Rollout().numberedFrom(0);
		parent.add(Rollout.sessionMeta("2026-11-06T10:00:00Z", rolloutId, null, 0));
		parent.answeredTurn("t1", "2026-11-06T10:01:00Z", PARENT_REQUEST, "2026-11-06T10:02:00Z", PARENT_ANSWER);
		parent.write(log);
		return parent.nextOrdinal();
	}

	/**
	 * Writes a fork's session log that inherits the first turns of a parent's, runs one answered turn of its own, and
	 * compacts keeping the given inherited messages and {@link #FORK_REQUEST}.
	 *
	 * @param log the file to write
	 * @param baseRolloutId the rollout that the fork's {@code history_base} names
	 * @param baseEnd the {@code history_base}'s {@code end_ordinal_exclusive}
	 * @param keptInherited the texts of the inherited messages that the compaction keeps, oldest first
	 * @return the log
	 * @throws IOException if the file cannot be written
	 */
	private static Path writeFork(Path log, String baseRolloutId, int baseEnd, String... keptInherited)
		throws IOException
	{
		Rollout fork = new Rollout().numberedFrom(baseEnd);
		fork.add(Rollout.sessionMeta("2026-11-06T11:00:00Z", FORK_ID, baseRolloutId, baseEnd));
		fork.answeredTurn("t9", "2026-11-06T11:01:00Z", FORK_REQUEST, "2026-11-06T11:02:00Z", FORK_ANSWER);
		Object[] kept = new Object[keptInherited.length + 1];
		for (int i = 0; i < keptInherited.length; ++i)
			kept[i] = Rollout.typed(keptInherited[i]);
		kept[keptInherited.length] = Rollout.typed(FORK_REQUEST);
		fork.compacted("2026-11-06T11:10:00Z", kept);
		return fork.write(log);
	}

	/**
	 * Returns the name that Codex gives a session log.
	 *
	 * @param id the identifier that ends the name: a thread's, or a thread's followed by an underscore and its rollout's
	 * @return the file name
	 */
	private static String logName(String id)
	{
		return "rollout-2026-11-06T10-00-00-" + id + ".jsonl";
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
		environment.put(Platform.current().homeVariable(),
			project.resolveSibling(project.getFileName() + "-home").toString());
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
		private int firstOrdinal = -1;

		/**
		 * Numbers the rollout's records as a paginated Codex rollout does: each record carries an {@code ordinal}, and
		 * the first one's is the given ordinal.
		 *
		 * @param firstOrdinal the first record's ordinal: zero for a thread's own rollout, or the {@code history_base}'s
		 *   {@code end_ordinal_exclusive} for a fork's
		 * @return this rollout
		 */
		Rollout numberedFrom(int firstOrdinal)
		{
			this.firstOrdinal = firstOrdinal;
			return this;
		}

		/**
		 * Returns the ordinal that the next appended record gets.
		 *
		 * @return the ordinal
		 */
		int nextOrdinal()
		{
			return firstOrdinal + records.size();
		}

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
			for (int i = 0; i < records.size(); ++i)
			{
				Map<String, Object> record = records.get(i);
				if (firstOrdinal >= 0)
				{
					Map<String, Object> numbered = new LinkedHashMap<>();
					numbered.put("timestamp", record.get("timestamp"));
					numbered.put("ordinal", firstOrdinal + i);
					numbered.putAll(record);
					record = numbered;
				}
				text.append(writeJson(record)).append('\n');
			}
			Files.writeString(file, text, UTF_8);
			return file;
		}

		/**
		 * Returns the {@code session_meta} record that begins a paginated rollout.
		 *
		 * @param time the record's time
		 * @param rolloutId the rollout's identifier
		 * @param baseRolloutId the rollout that this one's {@code history_base} names, or {@code null} for a thread that
		 *   is not a fork
		 * @param baseEnd the {@code history_base}'s {@code end_ordinal_exclusive}, which is ignored when
		 *   {@code baseRolloutId} is {@code null}
		 * @return the record
		 */
		static Map<String, Object> sessionMeta(String time, String rolloutId, String baseRolloutId, int baseEnd)
		{
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("id", rolloutId);
			payload.put("history_mode", "paginated");
			if (baseRolloutId != null)
				payload.put("history_base", Map.of("thread_id", baseRolloutId, "end_ordinal_exclusive", baseEnd));
			return record(time, "session_meta", payload);
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
		 * Returns the developer message that Codex writes when the user interrupts a turn. Codex sometimes writes it
		 * without a {@code turn_aborted} event.
		 *
		 * @param time the message's time
		 * @param turnId the interrupted turn's ID
		 * @return the record
		 */
		static Map<String, Object> abortNotice(String time, String turnId)
		{
			return message(time, turnId, "developer", "<turn_aborted>\nThe previous turn was interrupted on purpose.",
				"generic.turn_aborted");
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
