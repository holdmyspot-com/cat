package com.holdmyspot.cat.claude.optimizer.test;

import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.claude.optimizer.ClaudeSessions;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.contract.BuiltInTool;
import com.holdmyspot.cat.common.optimizer.contract.TrialRecord;
import com.holdmyspot.cat.common.optimizer.contract.UndecidableToolException;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.runner.HarnessCapacity;

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
 * Tests that the trial record read from recorded Claude events supplies what the built-in tools decide from.
 */
public final class ClaudeTrialRecordTest
{
	/**
	 * The recorded events of a session that ran {@code true} and {@code false} and read {@code sample.txt}.
	 */
	private static final Path EVENTS = Path.of("src/test/resources/optimizer/claude-tool-calls.jsonl");

	/**
	 * Creates the test.
	 */
	public ClaudeTrialRecordTest()
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
	 * Verifies that the record marks shell commands, keeps each tool's output, and lets the tool and reading checks
	 * decide, while the port declares that Claude does not report exit statuses.
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
			Path root = cleanup.add(Files.createTempDirectory("cat-claude-trial-record-"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(root, root, List::of, "", false, Map.of(),
				root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
			ClaudeSessions sessions = new ClaudeSessions(settings);
			Recorded record = new Recorded(sessions.readEvidence(EVENTS), Map.of("sample.txt", "sample\n"));

			requireThat(sessions.reportsExitStatus(), "reportsExitStatus").isFalse();
			requireThat(record.tools().stream().map(SessionEvidence.Tool::command).toList(), "command").
				isEqualTo(List.of(true, true, false));
			requireThat(decide("tool-used Read", record), "toolUsed").isTrue();
			requireThat(decide("tool-unused Bash", record), "toolUnused").isFalse();
			requireThat(decide("no-tool-call", record), "noToolCall").isFalse();
			requireThat(decide("read-in-full sample.txt", record), "readInFull").isTrue();
			requireThat(decide("read-at-most sample.txt 0", record), "readAtMost").isFalse();
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
