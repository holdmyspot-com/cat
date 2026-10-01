package io.github.cowwoc.cat.claude.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.fakes.FakeNativeProcess;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessRunnerScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;

/**
 * Tests how {@link ClaudeHarnessProcess} decides that a turn has ended. Each test plays the Claude CLI with a
 * {@link FakeNativeProcess} that prints stream-json lines shaped like those Claude Code 2.1.286 printed for a root
 * session that launched one background child agent.
 */
public final class ClaudeHarnessProcessTest
{
	/**
	 * The first line Claude prints for each root reply, including a follow-up reply that Claude starts on its own.
	 */
	private static final String INIT = "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"s\"}";
	/**
	 * The root agent launches one background child agent.
	 */
	private static final String CHILD_LAUNCH = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_root\",\"name\":\"Agent\",\"input\":{\"prompt\":" +
		"\"Run sleep 20, then reply DONE.\",\"run_in_background\":true}}]},\"parent_tool_use_id\":null," +
		"\"session_id\":\"s\"}";
	private static final String CHILD_RUNNING = "{\"type\":\"system\",\"subtype\":\"background_tasks_changed\"," +
		"\"tasks\":[{\"task_id\":\"child\",\"task_type\":\"local_agent\",\"description\":\"Run sleep 20\"}]," +
		"\"session_id\":\"s\"}";
	private static final String CHILD_STARTED = "{\"type\":\"system\",\"subtype\":\"task_started\"," +
		"\"task_id\":\"child\",\"tool_use_id\":\"toolu_root\",\"is_backgrounded\":true,\"spawn_depth\":1," +
		"\"task_type\":\"local_agent\",\"session_id\":\"s\"}";
	private static final String ROOT_REPLY = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"text\",\"text\":\"LAUNCHED\"}]},\"parent_tool_use_id\":null,\"session_id\":\"s\"}";
	private static final String ROOT_RESULT = "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false," +
		"\"result\":\"LAUNCHED\",\"result_index\":0,\"session_id\":\"s\"}";
	/**
	 * The background child's own final message, which belongs to the child rather than the root session.
	 */
	private static final String CHILD_REPLY = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"text\",\"text\":\"DONE\"}]},\"parent_tool_use_id\":\"toolu_root\"," +
		"\"session_id\":\"s\"}";
	private static final String CHILDREN_FINISHED = "{\"type\":\"system\",\"subtype\":\"background_tasks_changed\"," +
		"\"tasks\":[],\"session_id\":\"s\"}";
	private static final String CHILD_UPDATED = "{\"type\":\"system\",\"subtype\":\"task_updated\"," +
		"\"task_id\":\"child\",\"patch\":{\"status\":\"completed\"},\"session_id\":\"s\"}";
	private static final String CHILD_NOTIFICATION = "{\"type\":\"system\",\"subtype\":\"task_notification\"," +
		"\"task_id\":\"child\",\"tool_use_id\":\"toolu_root\",\"status\":\"completed\",\"summary\":\"DONE\"," +
		"\"session_id\":\"s\"}";
	private static final String FOLLOW_UP_REPLY = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\"," +
		"\"content\":[{\"type\":\"text\",\"text\":\"FINISHED\"}]},\"parent_tool_use_id\":null,\"session_id\":\"s\"}";
	private static final String FOLLOW_UP_RESULT = "{\"type\":\"result\",\"subtype\":\"success\"," +
		"\"is_error\":false,\"result\":\"FINISHED\",\"result_index\":1,\"session_id\":\"s\"}";
	/**
	 * What Claude Code 2.1.286 printed when SIGINT arrived after the root reply while a background child still ran: it
	 * stopped the root's own background shell command and kept the child listed.
	 */
	private static final String SHELL_STOPPED = "{\"type\":\"system\",\"subtype\":\"task_notification\"," +
		"\"task_id\":\"shell\",\"status\":\"stopped\",\"session_id\":\"s\"}";
	/**
	 * A wait for a follow-up reply that no test reaches unless the runner wrongly applies it.
	 */
	private static final Duration UNREACHED_FOLLOW_UP_TIMEOUT = Duration.ofMinutes(5);
	/**
	 * A wait for a follow-up reply short enough for a test to pass it.
	 */
	private static final Duration SHORT_FOLLOW_UP_TIMEOUT = Duration.ofMillis(50);

	/**
	 * Creates the test fixture.
	 */
	public ClaudeHarnessProcessTest()
	{
	}

	/**
	 * Verifies a turn without background children ends at Claude's result line, without waiting for more output.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void endsTurnAtResultWithoutChildren() throws IOException
	{
		FakeNativeProcess claude = new FakeNativeProcess(_ ->
		{
		});
		try (ClaudeHarnessProcess process = new ClaudeHarnessProcess(new HarnessRunnerScope(), claude,
			UNREACHED_FOLLOW_UP_TIMEOUT))
		{
			claude.emit(INIT, ROOT_REPLY, ROOT_RESULT);
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("Reply LAUNCHED.", relayed::add);

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("LAUNCHED");
			requireThat(relayed, "relayed").isEqualTo(List.of(INIT, ROOT_REPLY, ROOT_RESULT));
		}
	}

	/**
	 * Verifies a turn whose root reply arrives while a background child is still running continues until the child
	 * has finished and Claude's follow-up reply has arrived, relaying the child's completion notice in the same turn.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void waitsForChildrenAndFollowUpReply() throws IOException
	{
		// Requirement: R-E14
		FakeNativeProcess claude = new FakeNativeProcess(_ ->
		{
		});
		try (ClaudeHarnessProcess process = new ClaudeHarnessProcess(new HarnessRunnerScope(), claude,
			UNREACHED_FOLLOW_UP_TIMEOUT))
		{
			List<String> lines = List.of(INIT, CHILD_LAUNCH, CHILD_RUNNING, CHILD_STARTED, ROOT_REPLY, ROOT_RESULT,
				CHILD_REPLY, CHILDREN_FINISHED, CHILD_UPDATED, CHILD_NOTIFICATION, INIT, FOLLOW_UP_REPLY, FOLLOW_UP_RESULT);
			claude.emit(lines.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", relayed::add);

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("FINISHED");
			requireThat(relayed, "relayed").isEqualTo(lines);
		}
	}

	/**
	 * Verifies a turn ends with the root session's own reply when every background child has finished after the
	 * root reply and Claude starts no follow-up reply within the wait.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void endsTurnWhenNoFollowUpReplyStarts() throws IOException
	{
		// Requirement: R-E14
		FakeNativeProcess claude = new FakeNativeProcess(_ ->
		{
		});
		try (ClaudeHarnessProcess process = new ClaudeHarnessProcess(new HarnessRunnerScope(), claude,
			SHORT_FOLLOW_UP_TIMEOUT))
		{
			List<String> lines = List.of(INIT, CHILD_LAUNCH, CHILD_RUNNING, CHILD_STARTED, ROOT_REPLY, ROOT_RESULT,
				CHILD_REPLY, CHILDREN_FINISHED, CHILD_UPDATED, CHILD_NOTIFICATION);
			claude.emit(lines.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", relayed::add);

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("LAUNCHED");
			requireThat(relayed, "relayed").isEqualTo(lines);
		}
	}

	/**
	 * Verifies that once Claude has started its follow-up reply, the turn waits for that reply's result however long
	 * it takes, rather than applying the wait for the follow-up to start.
	 *
	 * @throws IOException          if the turn fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 * @throws ExecutionException   if the turn fails on its own thread
	 * @throws TimeoutException     if the turn does not end after the follow-up result
	 */
	@Test(timeOut = 10_000)
	public void waitsForFollowUpReplyThatStarted()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E14
		FakeNativeProcess claude = new FakeNativeProcess(_ ->
		{
		});
		// The root reply and the follow-up reply each begin with INIT, so the second INIT starts the follow-up.
		CountDownLatch followUpStarted = new CountDownLatch(2);
		try (ExecutorService turnThread = Executors.newSingleThreadExecutor();
			ClaudeHarnessProcess process = new ClaudeHarnessProcess(new HarnessRunnerScope(), claude,
				SHORT_FOLLOW_UP_TIMEOUT))
		{
			claude.emit(INIT, CHILD_LAUNCH, CHILD_RUNNING, ROOT_REPLY, ROOT_RESULT, CHILDREN_FINISHED,
				CHILD_NOTIFICATION);
			Future<HarnessProcessResult> turn = turnThread.submit(() -> process.sendTurn("Launch a background child.",
				line ->
				{
					if (line.equals(INIT))
						followUpStarted.countDown();
				}));
			claude.emit(INIT);
			followUpStarted.await();
			// Stay silent for several times the follow-up wait while the follow-up reply is in progress.
			Thread.sleep(SHORT_FOLLOW_UP_TIMEOUT.toMillis() * 4);
			claude.emit(FOLLOW_UP_REPLY, FOLLOW_UP_RESULT);

			HarnessProcessResult result = turn.get(5, TimeUnit.SECONDS);

			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("FINISHED");
		}
	}

	/**
	 * Verifies an interrupt that arrives after the turn has ended does nothing, and the process still serves the next
	 * turn. The in-memory Claude has no operating-system process, so signalling it would fail the test.
	 *
	 * @throws IOException if a turn or the interrupt fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptAfterTurnEnded() throws IOException
	{
		// Requirement: R-E15
		FakeNativeProcess claude = new FakeNativeProcess(_ ->
		{
		});
		try (ClaudeHarnessProcess process = new ClaudeHarnessProcess(new HarnessRunnerScope(), claude,
			UNREACHED_FOLLOW_UP_TIMEOUT))
		{
			claude.emit(INIT, ROOT_REPLY, ROOT_RESULT);
			process.sendTurn("Reply LAUNCHED.");

			process.interrupt();

			claude.emit(INIT, FOLLOW_UP_REPLY, FOLLOW_UP_RESULT);
			HarnessProcessResult next = process.sendTurn("Reply FINISHED.");
			requireThat(next.assistantResponse(), "assistantResponse").isEqualTo("FINISHED");
			requireThat(claude.requests().size(), "requests").isEqualTo(2);
		}
	}

	/**
	 * Verifies a turn ends at the root's result when every background child finished before that result, without
	 * waiting for a follow-up reply. The follow-up wait applies only when a child still ran at the result.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void endsAtResultAfterChildrenFinished() throws IOException
	{
		// Requirement: R-E14
		FakeNativeProcess claude = new FakeNativeProcess(_ ->
		{
		});
		try (ClaudeHarnessProcess process = new ClaudeHarnessProcess(new HarnessRunnerScope(), claude,
			UNREACHED_FOLLOW_UP_TIMEOUT))
		{
			List<String> lines = List.of(INIT, CHILD_LAUNCH, CHILD_RUNNING, CHILD_STARTED, CHILD_REPLY,
				CHILDREN_FINISHED, CHILD_UPDATED, CHILD_NOTIFICATION, ROOT_REPLY, ROOT_RESULT);
			claude.emit(lines.toArray(String[]::new));
			List<String> relayed = new ArrayList<>();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", relayed::add);

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("LAUNCHED");
			requireThat(relayed, "relayed").isEqualTo(lines);
		}
	}

	/**
	 * Verifies that a turn which ends because Claude closed its output waits for the operating system to report the
	 * exit, instead of reading an exit status that does not exist yet.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void waitsForExitAfterOutputEnds() throws IOException
	{
		// Requirement: R-E14
		FakeNativeProcess claude = new FakeNativeProcess(_ ->
		{
		});
		try (ClaudeHarnessProcess process = new ClaudeHarnessProcess(new HarnessRunnerScope(), claude,
			UNREACHED_FOLLOW_UP_TIMEOUT))
		{
			claude.emit(INIT);
			claude.closeOutputThenExitWhenAwaited();

			HarnessProcessResult result = process.sendTurn("Launch a background child.", _ ->
			{
			});

			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.standardError(), "standardError").contains("Claude process ended before result");
		}
	}

	/**
	 * Verifies that SIGINT, sent after the root reply while a background child still runs, ends the turn once Claude
	 * has stopped, instead of leaving the turn waiting for the child. The test plays Claude with a shell process,
	 * because the signal needs an operating-system process, and prints what Claude Code 2.1.286 printed for that
	 * signal. The shell starts with the default SIGINT disposition because a test run that was started in the
	 * background inherits an ignored SIGINT, which a shell cannot trap.
	 *
	 * @throws IOException          if the turn or the fixture fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 * @throws ExecutionException   if the turn fails on its own thread
	 * @throws TimeoutException     if the turn does not end after the interrupt
	 */
	@Test(timeOut = 30_000)
	public void interruptEndsTurnWaitingForChildren()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E15
		// Requirement: R-E14
		try (TemporaryDirectories cleanup = new TemporaryDirectories();
			ExecutorService turnThread = Executors.newSingleThreadExecutor())
		{
			Path directory = cleanup.add(Files.createTempDirectory("cat-claude-interrupt-"));
			Path before = directory.resolve("before.jsonl");
			Path after = directory.resolve("after.jsonl");
			Files.writeString(before, String.join("\n", INIT, CHILD_LAUNCH, CHILD_RUNNING, CHILD_STARTED, ROOT_REPLY,
				ROOT_RESULT) + "\n", UTF_8);
			Files.writeString(after, String.join("\n", SHELL_STOPPED, CHILD_RUNNING) + "\n", UTF_8);
			String script = "sleep 600 & waiter=$!; trap 'kill $waiter; cat \"$2\"; exit 0' INT; read request; " +
				"cat \"$1\"; wait $waiter";
			ProcessBuilder builder = new ProcessBuilder("env", "--default-signal=INT", "sh", "-c", script, "claude",
				before.toString(), after.toString());
			builder.redirectError(ProcessBuilder.Redirect.DISCARD);
			Process shell = builder.start();
			CountDownLatch rootReplied = new CountDownLatch(1);
			try (ClaudeHarnessProcess process = new ClaudeHarnessProcess(new HarnessRunnerScope(), shell,
				UNREACHED_FOLLOW_UP_TIMEOUT))
			{
				Future<HarnessProcessResult> turn = turnThread.submit(() -> process.sendTurn("Launch a background child.",
					line ->
					{
						if (line.equals(ROOT_RESULT))
							rootReplied.countDown();
					}));
				rootReplied.await();

				process.interrupt();

				HarnessProcessResult result = turn.get(10, TimeUnit.SECONDS);
				requireThat(result.standardOutput(), "standardOutput").contains("\"status\":\"stopped\"");
			}
			finally
			{
				shell.destroyForcibly();
			}
		}
	}
}
