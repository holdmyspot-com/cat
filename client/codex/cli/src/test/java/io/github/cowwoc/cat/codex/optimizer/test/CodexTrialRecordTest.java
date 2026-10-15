package io.github.cowwoc.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.codex.optimizer.CodexSessions;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.contract.BuiltInTool;
import io.github.cowwoc.cat.common.optimizer.contract.TrialRecord;
import io.github.cowwoc.cat.common.optimizer.contract.UndecidableToolException;
import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.testng.annotations.Test;

/**
 * Tests that the trial record read from recorded Codex events supplies what the built-in tools decide from.
 */
public final class CodexTrialRecordTest
{
	/**
	 * The recorded events of a session whose commands {@code true} and {@code false} failed to start in the sandbox.
	 */
	private static final Path EVENTS = Path.of("src/test/resources/optimizer/codex-tool-calls.jsonl");
	/**
	 * The output of each recorded command.
	 */
	private static final String SANDBOX_ERROR = "error building bubblewrap command: app-server socket directory must " +
		"be a user-owned directory with mode 0700\n";

	/**
	 * Creates the test.
	 */
	public CodexTrialRecordTest()
	{
	}

	/**
	 * A trial record of recorded activity and of files as they were when the trial started.
	 *
	 * @param activity the activity read from the events
	 * @param initial the text of each file when the trial started, by path
	 */
	private record Recorded(SessionEvidence activity, Map<String, String> initial) implements TrialRecord
	{
		@Override
		public List<List<String>> replies()
		{
			return List.of();
		}

		@Override
		public List<SessionEvidence.Tool> tools()
		{
			List<SessionEvidence.Tool> tools = new ArrayList<>();
			for (SessionEvidence.Entry entry : activity.entries())
			{
				if (entry instanceof SessionEvidence.Tool tool)
					tools.add(tool);
			}
			return tools;
		}

		@Override
		public List<String> received()
		{
			return activity.received();
		}

		@Override
		public boolean exists(String path)
		{
			return false;
		}

		@Override
		public Optional<String> content(String path)
		{
			return Optional.empty();
		}

		@Override
		public Optional<String> initial(String path)
		{
			return Optional.ofNullable(initial.get(path));
		}
	}

	/**
	 * Verifies that the record marks command executions, and only them, as commands with their reported exit statuses,
	 * so that the exit status and tool checks decide, and that the port declares that Codex reports exit statuses.
	 *
	 * @throws IOException if the events cannot be read
	 * @throws UndecidableToolException if a check lacks evidence
	 */
	@Test
	public void checksDecideFromRecordedEvents() throws IOException, UndecidableToolException
	{
		// Requirement: R-C9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-trial-record-"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root, List::of, "", false, Map.of(),
				root, HarnessCapacity.unlimited(), Clock.systemUTC());
			CodexSessions sessions = new CodexSessions(settings);
			Path transcript = RecordedTrial.write(root, EVENTS, List.of(RecordedTrial.commandResult(1, SANDBOX_ERROR)));
			Recorded record = new Recorded(sessions.readEvidence(transcript), Map.of());

			requireThat(sessions.reportsExitStatus(), "reportsExitStatus").isTrue();
			requireThat(record.tools(), "tools").isNotEmpty();
			for (SessionEvidence.Tool tool : record.tools())
			{
				requireThat(tool.command(), tool.tool() + ".command").
					isEqualTo(tool.tool().equals("commandExecution"));
			}
			requireThat(decide("exit-status \"-lc true$\" 1", record), "failedToStart").isTrue();
			requireThat(decide("exit-status \"-lc true$\" 0", record), "succeeded").isFalse();
			requireThat(decide("tool-used commandExecution", record), "toolUsed").isTrue();
			requireThat(decide("no-tool-call", record), "noToolCall").isFalse();
		}
	}

	/**
	 * Verifies that when Codex gave the agent only part of a command's output, the evidence says so and reading counts
	 * only the part that the agent received, while a whole result counts as reading the whole file.
	 *
	 * @throws IOException if the trial cannot be written or read
	 * @throws UndecidableToolException if a check lacks evidence
	 */
	@Test
	public void readingCountsOnlyReceivedOutput() throws IOException, UndecidableToolException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-trial-record-"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root, List::of, "", false, Map.of(),
				root, HarnessCapacity.unlimited(), Clock.systemUTC());
			CodexSessions sessions = new CodexSessions(settings);
			Map<String, String> initial = Map.of("error.log", SANDBOX_ERROR);
			Path whole = RecordedTrial.write(Files.createDirectories(root.resolve("whole")), EVENTS,
				List.of(RecordedTrial.commandResult(1, SANDBOX_ERROR)));
			Path cut = RecordedTrial.write(Files.createDirectories(root.resolve("cut")), EVENTS,
				List.of(RecordedTrial.commandResult(1, "error building bubblewrap command: app-ser\n…12 tokens truncated…")));

			SessionEvidence wholeEvidence = sessions.readEvidence(whole);
			SessionEvidence cutEvidence = sessions.readEvidence(cut);

			requireThat(wholeEvidence.render(), "whole.render()").
				contains("Output: " + SANDBOX_ERROR.length() + " characters; the agent received all of it").
				doesNotContain("only part");
			requireThat(cutEvidence.render(), "cut.render()").
				contains("Output: " + SANDBOX_ERROR.length() + " characters; the agent received only part of it").
				doesNotContain("all of it");
			requireThat(decide("read-in-full error.log", new Recorded(wholeEvidence, initial)), "wholeRead").isTrue();
			requireThat(decide("read-in-full error.log", new Recorded(cutEvidence, initial)), "cutRead").isFalse();
		}
	}

	/**
	 * Decides a check.
	 *
	 * @param line the check's {@code tool:} line
	 * @param record the trial record
	 * @return {@code true} if the record passes the check
	 * @throws IOException if the record cannot be read
	 * @throws UndecidableToolException if the record lacks what the check observes
	 */
	private static boolean decide(String line, TrialRecord record) throws IOException, UndecidableToolException
	{
		return BuiltInTool.parse(line).decide(record, Map.of()).passed();
	}
}
