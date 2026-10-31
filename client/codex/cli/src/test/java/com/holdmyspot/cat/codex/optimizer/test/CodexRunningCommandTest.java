package com.holdmyspot.cat.codex.optimizer.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.optimizer.CodexSessions;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.evidence.CommandSummary;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.HarnessRun;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * Tests that the record of a Codex trial shows what an agent received from a command that was still running, and
 * then the command's end.
 */
public final class CodexRunningCommandTest
{
	/**
	 * The recorded run artifacts of a trial whose child received an empty result from {@code cat docs/facts.md} after
	 * Codex's yield time, while the command later ended with the file's content.
	 */
	private static final Path TRANSCRIPT = Path.of("src/test/resources/optimizer/running-command/transcript.jsonl");
	/**
	 * The thread of the child that received the empty result.
	 */
	private static final String SHIPPING = "01a1882f-73ef-7ad1-bb1c-17227c5bb14e";
	/**
	 * The thread of the child whose command ended before it received the result.
	 */
	private static final String CAPITAL = "01a1882f-c39f-7670-917e-44dff59f05af";
	/**
	 * The rollout of {@link #SHIPPING}.
	 */
	private static final String SHIPPING_ROLLOUT = "rollout-2026-10-29T11-02-53-" + SHIPPING + ".jsonl";
	/**
	 * The command that every agent of the trial ran.
	 */
	private static final String COMMAND = "Input:\n/bin/bash -lc 'cat docs/facts.md'\n";
	/**
	 * How the record shows a command that ended before the agent received its result.
	 */
	private static final String ENDED_COMMAND = "Tool: commandExecution\n" + COMMAND + "Exit status: 0\n" +
		"Output: 44 characters; the agent received all of it\n";
	/**
	 * How the record introduces what an agent received from a command that was still running.
	 */
	private static final String STILL_RUNNING = "Received while the command was still running:\n";

	/**
	 * Creates the test.
	 */
	public CodexRunningCommandTest()
	{
	}

	/**
	 * Verifies that the record of a child that received an empty result while its command was still running shows,
	 * in order, the command with exactly what the child received and that the command was still running, then the
	 * command's end with its exit status and the size of its complete output, then the child's answer.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void showsResultReceivedWhileRunning() throws IOException
	{
		// Requirement: R-E21
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-running-command-"));
			String rendered = sessions(jvm, root).readEvidence(TRANSCRIPT).render();

			String received = "Actor: child " + SHIPPING + "\nTool: commandExecution\n" + COMMAND + STILL_RUNNING +
				"Script completed\nWall time 10.2 seconds\nOutput:\n\n";
			String ended = "Actor: child " + SHIPPING + "\nCommand ended\n" + COMMAND + "Exit status: 0\n" +
				"Output: 44 characters\n";
			String answer = "Actor: child " + SHIPPING + "\nAssistant response position: 1\nAssigned subquestion: " +
				"Where does Acme ship orders from? `docs/facts.md` returned zero stdout";
			String remainder = rendered;
			for (String expected : List.of(received, ended, answer))
			{
				requireThat(remainder, "remainder").contains(expected);
				remainder = remainder.substring(remainder.indexOf(expected) + expected.length());
			}
		}
	}

	/**
	 * Verifies that the commands that ended before their agents received their results are shown as before, with
	 * their exit status and output on the command itself, and that no other command is shown as still running.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void commandsThatEndedFirstRenderAsBefore() throws IOException
	{
		// Requirement: R-E21
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-running-command-"));
			String rendered = sessions(jvm, root).readEvidence(TRANSCRIPT).render();

			requireThat(rendered, "rendered").contains("Actor: root\n" + ENDED_COMMAND).
				contains("Actor: child " + CAPITAL + "\n" + ENDED_COMMAND);
			requireThat(rendered.split(STILL_RUNNING, -1).length, "stillRunningCount").isEqualTo(2);
		}
	}

	/**
	 * Verifies that when the rollout does not report when the child's command started and ended, so that its result
	 * cannot be matched with the command, the command is shown as before rather than as still running.
	 *
	 * @throws IOException if the trial cannot be written or read
	 */
	@Test
	public void unmatchedCommandRendersAsBefore() throws IOException
	{
		// Requirement: R-E21
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-running-command-"));
			Path artifacts = Files.createDirectories(root.resolve("artifacts"));
			Path transcript = Files.copy(TRANSCRIPT, artifacts.resolve("transcript.jsonl"));
			Path rollouts = Files.createDirectories(artifacts.resolve(HarnessRun.SESSION_TRANSCRIPTS));
			Path recorded = TRANSCRIPT.resolveSibling(HarnessRun.SESSION_TRANSCRIPTS);
			try (Stream<Path> files = Files.list(recorded))
			{
				for (Path rollout : files.toList())
				{
					List<String> lines = Files.readAllLines(rollout, UTF_8);
					StringBuilder text = new StringBuilder();
					for (String line : lines)
					{
						// The child's rollout loses the event that reports its command's start and end times
						boolean commandEvent = line.contains("\"type\":\"CommandExecution\"");
						if (rollout.getFileName().toString().equals(SHIPPING_ROLLOUT) && commandEvent)
							continue;
						text.append(line).append('\n');
					}
					Files.writeString(rollouts.resolve(rollout.getFileName()), text, UTF_8);
				}
			}

			String rendered = sessions(jvm, root).readEvidence(transcript).render();
			requireThat(rendered, "rendered").contains("Actor: child " + SHIPPING + "\n" + ENDED_COMMAND).
				doesNotContain(STILL_RUNNING).doesNotContain("Command ended");
		}
	}

	/**
	 * Verifies that the record of the trial reports its longest command, the child's {@code cat docs/facts.md} that
	 * the app-server reports as running for 17,181 ms, and the one empty result that the child received from it while
	 * it was still running.
	 *
	 * @throws IOException if the recording cannot be read
	 */
	@Test
	public void summarizesCommands() throws IOException
	{
		// Requirement: R-E6
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-running-command-"));
			CommandSummary commands = sessions(jvm, root).readEvidence(TRANSCRIPT).commands();

			requireThat(commands, "commands").isEqualTo(new CommandSummary(Duration.ofMillis(17_181), 1));
		}
	}

	/**
	 * Verifies that a result that the child received while its command was still running and that held some of the
	 * command's output is not counted as an empty result.
	 *
	 * @throws IOException if the trial cannot be written or read
	 */
	@Test
	public void runningResultWithOutputIsNotEmpty() throws IOException
	{
		// Requirement: R-E6
		try (TestJvmScope jvm = new TestJvmScope();
			TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-running-command-"));
			Path artifacts = Files.createDirectories(root.resolve("artifacts"));
			Path transcript = Files.copy(TRANSCRIPT, artifacts.resolve("transcript.jsonl"));
			Path rollouts = Files.createDirectories(artifacts.resolve(HarnessRun.SESSION_TRANSCRIPTS));
			Path recorded = TRANSCRIPT.resolveSibling(HarnessRun.SESSION_TRANSCRIPTS);
			try (Stream<Path> files = Files.list(recorded))
			{
				for (Path rollout : files.toList())
				{
					String text = Files.readString(rollout, UTF_8);
					if (rollout.getFileName().toString().equals(SHIPPING_ROLLOUT))
					{
						// The child receives the first line of the file before the command ends
						text = text.replace("Wall time 10.2 seconds\\nOutput:\\n\"},{\"type\":\"input_text\"," +
							"\"text\":\"\"}", "Wall time 10.2 seconds\\nOutput:\\n\"},{\"type\":" +
							"\"input_text\",\"text\":\"# Acme facts\"}");
					}
					Files.writeString(rollouts.resolve(rollout.getFileName()), text, UTF_8);
				}
			}

			SessionEvidence evidence = sessions(jvm, root).readEvidence(transcript);
			requireThat(evidence.render(), "rendered").contains(STILL_RUNNING + "Script completed\nWall time " +
				"10.2 seconds\nOutput:\n# Acme facts\n");
			requireThat(evidence.commands().emptyRunningResults(), "emptyRunningResults").isEqualTo(0);
		}
	}

	/**
	 * Returns a port whose run directories are below a directory.
	 *
	 * @param jvm  the borrowed process scope
	 * @param root the directory
	 * @return the port
	 */
	private static CodexSessions sessions(JvmScope jvm, Path root)
	{
		return new CodexSessions(new RunnerSessions.Settings(jvm, root, root, List::of, "", false, Map.of(), root,
			HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer()));
	}
}
