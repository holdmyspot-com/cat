package com.holdmyspot.cat.codex.runner.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.codex.runner.CodexProcess;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.runner.HarnessProcessResult;
import com.holdmyspot.cat.common.runner.HarnessSessionTranscripts;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.fakes.test.TestHarnessRunnerScope;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how {@link CodexProcess} constructs requests and interprets supplied app-server events.
 * {@link FakeHarnessCli} supplies controlled protocol input;
 * these tests do not establish how a live app-server responds.
 */
public final class CodexProcessTest
{
	private static final String ROOT_THREAD = "thread-root";
	private static final String CHILD_THREAD = "thread-child";
	private static final String ROOT_TURN = "turn-root";
	private static final Path PROJECT = Path.of("/project").toAbsolutePath();
	/**
	 * Request lines that the tests play no part in.
	 */
	private static final LongFunction<List<String>> NO_ANSWER = _ -> List.of();
	/**
	 * Creates the test fixture.
	 */
	public CodexProcessTest()
	{
	}

	/**
	 * Verifies that closing a process that keeps session transcripts copies every rollout below the Codex home's
	 * {@code sessions} directory into the rollouts directory, without the other files there.
	 *
	 * @throws IOException if the files cannot be written or read
	 */
	@Test(timeOut = 10_000)
	public void closeKeepsRollouts() throws IOException
	{
		// Requirement: R-I7
		try (TemporaryDirectories directories = new TemporaryDirectories())
		{
			Path directory = directories.add(Files.createTempDirectory("codex-process-rollouts-"));
			Path day = Files.createDirectories(directory.resolve("home/sessions/2026/10/15"));
			Files.writeString(day.resolve("rollout-2026-10-15T10-00-00-root.jsonl"), "{\"root\":true}\n", UTF_8);
			Files.writeString(day.resolve("notes.txt"), "not a rollout\n", UTF_8);
			Files.writeString(directory.resolve("home/sessions/rollout-child.jsonl"), "{\"child\":true}\n", UTF_8);

			new AppServer(NO_ANSWER, NO_ANSWER).start(directory).close();

			Path rollouts = directory.resolve("rollouts");
			try (Stream<Path> files = Files.list(rollouts))
			{
				requireThat(files.map(file -> file.getFileName().toString()).sorted().toList(), "rollouts").
					isEqualTo(List.of("rollout-2026-10-15T10-00-00-root.jsonl", "rollout-child.jsonl"));
			}
			requireThat(Files.readString(rollouts.resolve("rollout-2026-10-15T10-00-00-root.jsonl"), UTF_8), "root").
				isEqualTo("{\"root\":true}\n");
			requireThat(Files.readString(rollouts.resolve("rollout-child.jsonl"), UTF_8), "child").
				isEqualTo("{\"child\":true}\n");
		}
	}

	/**
	 * Verifies that closing a process whose Codex home recorded no session still creates the rollouts directory, so
	 * that a session without threads reads as having received no tool results.
	 *
	 * @throws IOException if the directory cannot be created or listed
	 */
	@Test(timeOut = 10_000)
	public void closeWithoutSessionsCreatesRollouts() throws IOException
	{
		// Requirement: R-I7
		try (TemporaryDirectories directories = new TemporaryDirectories())
		{
			Path directory = directories.add(Files.createTempDirectory("codex-process-rollouts-"));

			new AppServer(NO_ANSWER, NO_ANSWER).start(directory).close();

			try (Stream<Path> files = Files.list(directory.resolve("rollouts")))
			{
				requireThat(files.toList(), "rollouts").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a process that keeps no session transcripts starts ephemeral threads, copies nothing when it
	 * closes and names no transcript, while one that keeps them starts persistent threads and names the rollout that
	 * Codex reports for the thread.
	 *
	 * @throws IOException if the process cannot be started or closed
	 */
	@Test(timeOut = 10_000)
	public void keepsNoRolloutUnlessAsked() throws IOException
	{
		// Requirement: R-I7
		try (TemporaryDirectories directories = new TemporaryDirectories())
		{
			Path directory = directories.add(Files.createTempDirectory("codex-process-ephemeral-"));
			Files.createDirectories(directory.resolve("home/sessions"));
			Files.writeString(directory.resolve("home/sessions/rollout-root.jsonl"), "{}\n", UTF_8);
			AppServer ephemeral = new AppServer(NO_ANSWER, NO_ANSWER);

			try (CodexProcess process = ephemeral.start(directory, "", "", false))
			{
				requireThat(process.sessionTranscript().isPresent(), "named").isFalse();
			}

			requireThat(ephemeral.requests("thread/start").getFirst().path("ephemeral").asBoolean(false), "ephemeral").
				isTrue();
			requireThat(Files.exists(directory.resolve("rollouts")), "copied").isFalse();
			AppServer persistent = new AppServer(NO_ANSWER, NO_ANSWER);

			try (CodexProcess process = persistent.start(directory, "", "", true))
			{
				requireThat(process.sessionTranscript().orElseThrow(), "rollout").
					isEqualTo(Path.of("/codex-home/sessions/rollout-root.jsonl"));
			}

			requireThat(persistent.requests("thread/start").getFirst().has("ephemeral"), "ephemeral").isFalse();
			requireThat(Files.exists(directory.resolve("rollouts/rollout-root.jsonl")), "copied").isTrue();
		}
	}

	/**
	 * Verifies the turn's assistant response is the text of the root thread's last completed {@code agentMessage},
	 * while its reply contains every root message in order and excludes child messages that complete later.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void repliesWithLastRootAgentMessage() throws IOException
	{
		// Requirement: R-E13
		// Requirement: R-C12
		AppServer server = new AppServer(id -> List.of(turnStarted(id),
			agentMessage(ROOT_THREAD, "I will read the evidence."),
			agentMessage(CHILD_THREAD, "Child finding."),
			agentMessage(ROOT_THREAD, "{\"verdict\":\"fail\"}"),
			agentMessage(CHILD_THREAD, "Child afterthought."),
			turnCompleted(CHILD_THREAD, "turn-child", "completed"),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (TemporaryDirectories directories = new TemporaryDirectories();
			CodexProcess process = server.start(directories))
		{
			HarnessProcessResult result = process.sendTurn("Judge the evidence.");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("{\"verdict\":\"fail\"}");
			requireThat(result.assistantMessages(), "assistantMessages").isEqualTo(
				List.of("I will read the evidence.", "{\"verdict\":\"fail\"}"));
		}
	}

	/**
	 * Verifies empty and whitespace-only root messages are skipped while non-blank messages keep their exact text
	 * and order. Event envelopes follow the recorded app-server evidence documented by {@link AppServer}; text
	 * values are controlled inputs.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 1000)
	public void skipsBlankRootAgentMessages() throws IOException
	{
		AppServer server = new AppServer(id -> List.of(turnStarted(id),
			agentMessage(ROOT_THREAD, " interim "),
			agentMessage(ROOT_THREAD, ""),
			agentMessage(ROOT_THREAD, " \t\n"),
			agentMessage(ROOT_THREAD, "final"),
			agentMessage(ROOT_THREAD, " \t\n"),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (TemporaryDirectories directories = new TemporaryDirectories();
			CodexProcess process = server.start(directories))
		{
			HarnessProcessResult result = process.sendTurn("Run the blank-message task.");

			requireThat(result.assistantMessages(), "assistantMessages").isEqualTo(List.of(" interim ", "final"));
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("final");
		}
	}

	/**
	 * Verifies that recorded root progress messages before a command and the final response are collected in order.
	 * The recorded thread and turn identifiers are replaced with this fixture's controlled identifiers.
	 *
	 * @throws IOException if the recording cannot be read or the turn fails
	 */
	@Test(timeOut = 1000)
	public void collectsRecordedReplyMessages() throws IOException
	{
		// Requirement: R-C12
		String recording;
		try (InputStream input = CodexProcessTest.class.getResourceAsStream("/optimizer/codex-reply-messages.jsonl"))
		{
			recording = new String(input.readAllBytes(), UTF_8).
				replace("019fe761-2935-72b2-b815-52183463aec8", ROOT_THREAD).
				replace("019fe761-29ef-7180-895a-1c731e998e6d", ROOT_TURN);
		}
		AppServer server = new AppServer(id -> List.of(turnStarted(id), recording), NO_ANSWER);
		try (TemporaryDirectories directories = new TemporaryDirectories();
			CodexProcess process = server.start(directories))
		{
			HarnessProcessResult result = process.sendTurn("Implement the calculator.");

			requireThat(result.assistantMessages(), "assistantMessages").isEqualTo(List.of(
				"I’ll inspect the project’s language and conventions, then add the checkout calculator and run the " +
					"relevant tests.",
				"The repository is a minimal Java 17 project with an existing `CheckoutPriceCalculator` stub. I’ll " +
					"implement a single calculation method using decimal rates (for example, `0.10` for 10%); the " +
					"shipping fee is added after the item discount and before tax.", result.assistantResponse()));
			requireThat(result.assistantResponse(), "assistantResponse").startsWith(
				"Implemented `CheckoutPriceCalculator.calculateTotal()`");
		}
	}

	/**
	 * Verifies that the runner selects its profile through the app-server protocol: the model as
	 * {@code thread/start}'s {@code model} and the reasoning effort as each {@code turn/start}'s {@code effort}.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void selectsTheProfileThroughTheAppServer() throws IOException
	{
		// Requirement: R-E17
		AppServer server = new AppServer(id -> List.of(turnStarted(id),
			agentMessage(ROOT_THREAD, "done"),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (TemporaryDirectories directories = new TemporaryDirectories();
			CodexProcess process = server.start(directories, "gpt-5.5", "xhigh"))
		{
			process.sendTurn("Run the trial.");

			requireThat(server.requests("thread/start").getFirst().path("model").asString(""), "model").
				isEqualTo("gpt-5.5");
			requireThat(server.requests("turn/start").getFirst().path("effort").asString(""), "effort").
				isEqualTo("xhigh");
		}
	}

	/**
	 * Verifies that the effort {@code none} reaches Codex as each {@code turn/start}'s explicit {@code effort}
	 * {@code none}, which codex-cli reads as no reasoning, rather than as a turn without an effort, which Codex would
	 * run at its configured default.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void sendsNoneEffortExplicitly() throws IOException
	{
		// Requirement: R-I11
		AppServer server = new AppServer(id -> List.of(turnStarted(id),
			agentMessage(ROOT_THREAD, "done"),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (TemporaryDirectories directories = new TemporaryDirectories();
			CodexProcess process = server.start(directories, "gpt-6-plain", "none"))
		{
			process.sendTurn("Run the trial.");

			requireThat(server.requests("turn/start").getFirst().path("effort").asString(""), "effort").
				isEqualTo("none");
		}
	}

	/**
	 * Verifies that when Codex refuses to start the thread, the failure reports Codex's own error message.
	 *
	 * @throws IOException if the app-server's process cannot be closed
	 */
	@Test(timeOut = 10_000, expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Codex app-server did not start the thread: error creating thread: " +
			"fs sandbox helper failed")
	public void reportsThreadStartError() throws IOException
	{
		AppServer server = new AppServer(NO_ANSWER, NO_ANSWER).
			refusingThreadStart("error creating thread: fs sandbox helper failed");
		try (TemporaryDirectories directories = new TemporaryDirectories())
		{
			server.start(directories).close();
		}
	}

	/**
	 * Verifies that when the app-server ends before it answers {@code initialize}, the failure reports what it printed
	 * on its standard error, which names the cause, such as a launcher that cannot find {@code node}.
	 *
	 * @throws IOException if the app-server's process cannot be closed
	 */
	@Test(timeOut = 10_000, expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "Codex app-server ended before responding to request 1: " +
			"'node' is not recognized as an internal or external command\\.")
	public void reportsStandardErrorWhenStartupEnds() throws IOException
	{
		AppServer server = new AppServer(NO_ANSWER, NO_ANSWER).
			endingAtStartup("'node' is not recognized as an internal or external command.\r\n");
		try (TemporaryDirectories directories = new TemporaryDirectories())
		{
			server.start(directories).close();
		}
	}

	/**
	 * Verifies that notifications Codex prints during start-up, such as {@code thread/started}, reach the first
	 * turn's listener ahead of that turn's own lines, that the first turn's archived events are those same lines,
	 * and that a later turn's listener does not receive the start-up notifications again.
	 *
	 * @throws IOException if a turn fails
	 */
	@Test(timeOut = 10_000)
	public void deliversStartupEventsToFirstTurn() throws IOException
	{
		List<Long> turnRequests = new CopyOnWriteArrayList<>();
		LongFunction<List<String>> turnLines = id -> List.of(turnStarted(id), agentMessage(ROOT_THREAD, "done"),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed"));
		AppServer server = new AppServer(id ->
		{
			turnRequests.add(id);
			return turnLines.apply(id);
		}, NO_ANSWER).notifyingThreadStarted();
		try (TemporaryDirectories directories = new TemporaryDirectories();
			CodexProcess process = server.start(directories, "gpt-5.5", ""))
		{
			List<String> firstEvents = new ArrayList<>();
			HarnessProcessResult first = process.sendTurn("First task.", firstEvents::add);
			List<String> secondEvents = new ArrayList<>();
			HarnessProcessResult second = process.sendTurn("Second task.", secondEvents::add);

			List<String> expectedFirst = new ArrayList<>();
			expectedFirst.add(threadStarted("gpt-5.5"));
			expectedFirst.addAll(lines(turnLines.apply(turnRequests.get(0))));
			List<String> expectedSecond = lines(turnLines.apply(turnRequests.get(1)));
			requireThat(firstEvents, "firstEvents").isEqualTo(expectedFirst);
			requireThat(first.standardOutput(), "first.standardOutput()").isEqualTo(String.join("\n", expectedFirst) + "\n");
			requireThat(secondEvents, "secondEvents").isEqualTo(expectedSecond);
			requireThat(second.standardOutput(), "second.standardOutput()").
				isEqualTo(String.join("\n", expectedSecond) + "\n");
		}
	}

	/**
	 * Splits printed output into the lines a reader receives.
	 *
	 * @param output output chunks, each holding one or more newline-separated lines
	 * @return the lines in order
	 */
	private static List<String> lines(List<String> output)
	{
		return output.stream().flatMap(chunk -> Stream.of(chunk.split("\n"))).toList();
	}

	/**
	 * Verifies an interrupt during a turn targets the active root turn. Given the supplied {@code interrupted}
	 * completion notification, the adapter classifies the caller-interrupted turn as successful and retains its
	 * progress message.
	 *
	 * @throws IOException          if the turn fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 * @throws ExecutionException   if the turn fails on its own thread
	 * @throws TimeoutException     if the turn does not end after the interruption
	 */
	@Test(timeOut = 10_000)
	public void interruptsActiveTurn() throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E10
		// Requirement: R-C12
		AppServer server = new AppServer(id -> List.of(turnStarted(id), agentMessage(ROOT_THREAD, "Working on it.")),
			id -> List.of(response(id), turnCompleted(ROOT_THREAD, ROOT_TURN, "interrupted")));
		CountDownLatch working = new CountDownLatch(1);
		try (TemporaryDirectories directories = new TemporaryDirectories();
			ExecutorService turnThread = Executors.newSingleThreadExecutor();
			CodexProcess process = server.start(directories))
		{
			Future<HarnessProcessResult> turn = turnThread.submit(() -> process.sendTurn("Do the task.", line ->
			{
				if (line.contains("Working on it."))
					working.countDown();
			}));
			working.await();

			process.interrupt();
			HarnessProcessResult result = turn.get(5, TimeUnit.SECONDS);

			requireThat(server.interruptRequests(), "interruptRequests").isEqualTo(List.of(ROOT_THREAD + "/" +
				ROOT_TURN));
			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.exitCode(), "exitCode").isEqualTo(0);
			requireThat(result.assistantMessages(), "assistantMessages").isEqualTo(List.of("Working on it."));
		}
	}

	/**
	 * Verifies an interrupt that arrives after the turn was requested but before Codex identified the turn is sent
	 * once Codex identifies it.
	 *
	 * @throws IOException          if the turn fails
	 * @throws InterruptedException if the test is interrupted while it waits
	 * @throws ExecutionException   if the turn fails on its own thread
	 * @throws TimeoutException     if the turn does not end after the interruption
	 */
	@Test(timeOut = 10_000)
	public void interruptsTurnBeforeItIsIdentified()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E10
		AppServer server = new AppServer(NO_ANSWER,
			id -> List.of(response(id), turnCompleted(ROOT_THREAD, ROOT_TURN, "interrupted")));
		try (TemporaryDirectories directories = new TemporaryDirectories();
			ExecutorService turnThread = Executors.newSingleThreadExecutor();
			CodexProcess process = server.start(directories))
		{
			Future<HarnessProcessResult> turn = turnThread.submit(() -> process.sendTurn("Do the task."));
			long turnRequest = server.awaitTurnRequest();

			process.interrupt();
			requireThat(server.interruptRequests(), "interruptRequestsBeforeIdentification").isEmpty();
			server.process.emit(turnStarted(turnRequest));
			HarnessProcessResult result = turn.get(5, TimeUnit.SECONDS);

			requireThat(server.interruptRequests(), "interruptRequests").isEqualTo(List.of(ROOT_THREAD + "/" +
				ROOT_TURN));
			requireThat(result.completed(), "completed").isTrue();
		}
	}

	/**
	 * Verifies an interrupt after a supplied completion notification sends no interrupt request. A subsequent
	 * supplied completion notification is still classified as a completed turn with only that turn's messages.
	 *
	 * @throws IOException if a turn or the interrupt fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptAfterTurnEnded() throws IOException
	{
		// Requirement: R-E10
		// Requirement: R-C12
		List<String> replies = List.of("First answer.", "Second answer.");
		AtomicInteger nextReply = new AtomicInteger();
		AppServer server = new AppServer(id -> List.of(turnStarted(id),
			agentMessage(ROOT_THREAD, replies.get(nextReply.getAndIncrement())),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (TemporaryDirectories directories = new TemporaryDirectories();
			CodexProcess process = server.start(directories))
		{
			process.sendTurn("First task.");

			process.interrupt();

			HarnessProcessResult next = process.sendTurn("Second task.");
			requireThat(server.interruptRequests(), "interruptRequests").isEmpty();
			requireThat(next.completed(), "completed").isTrue();
			requireThat(next.assistantMessages(), "assistantMessages").isEqualTo(List.of("Second answer."));
		}
	}

	/**
	 * Verifies the bridge reports an incomplete turn when its input reaches EOF without a completion notification,
	 * and waits for the supplied process to exit before reading its exit status.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void waitsForExitAfterOutputEnds() throws IOException
	{
		// Requirement: R-E13
		AppServer server = new AppServer(id -> List.of(turnStarted(id)), NO_ANSWER).closingOutputAfterTurnStart();
		try (TemporaryDirectories directories = new TemporaryDirectories();
			CodexProcess process = server.start(directories))
		{
			HarnessProcessResult result = process.sendTurn("Judge the evidence.");

			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.standardError(), "standardError").contains("ended before turn completion");
		}
	}

	/**
	 * Verifies that when Codex refuses to start a turn, the turn fails at once with Codex's own error message rather
	 * than waiting for a completion that Codex never sends. The error's shape follows the response that codex-cli
	 * 0.160.0 printed for a prompt longer than its input limit.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void reportsTurnStartError() throws IOException
	{
		AppServer server = new AppServer(id -> List.of("{\"error\":{\"code\":-32602,\"data\":{\"input_error_code\":" +
			"\"input_too_large\",\"max_chars\":1048576,\"actual_chars\":1098863},\"message\":\"Input exceeds the " +
			"maximum length of 1048576 characters.\"},\"id\":" + id + "}"), NO_ANSWER);
		try (TemporaryDirectories directories = new TemporaryDirectories();
			CodexProcess process = server.start(directories))
		{
			HarnessProcessResult result = process.sendTurn("Judge the evidence.");

			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.standardError(), "standardError").isEqualTo(
				"Codex app-server did not start the turn: Input exceeds the maximum length of 1048576 characters.");
		}
	}

	/**
	 * Returns Codex's answer to a {@code turn/start} request followed by the root turn's start notification.
	 *
	 * @param requestId the {@code turn/start} request's identifier
	 * @return the lines Codex prints
	 */
	private static String turnStarted(long requestId)
	{
		return "{\"id\":" + requestId + ",\"result\":{\"turn\":{\"id\":\"" + ROOT_TURN + "\",\"items\":[]," +
			"\"status\":\"inProgress\",\"error\":null}}}\n{\"method\":\"turn/started\",\"params\":{\"threadId\":\"" +
			ROOT_THREAD + "\",\"turn\":{\"id\":\"" + ROOT_TURN + "\",\"status\":\"inProgress\"}}}";
	}

	/**
	 * Returns the notification that Codex started the root thread. Its fields follow the {@code thread/started}
	 * notification recorded in {@code optimizer/codex-children.jsonl} (codex-cli 0.159.1).
	 *
	 * @param model the model the thread runs
	 * @return the notification line
	 */
	private static String threadStarted(String model)
	{
		return "{\"method\":\"thread/started\",\"params\":{\"thread\":{\"id\":\"" + ROOT_THREAD + "\"," +
			"\"parentThreadId\":null,\"modelProvider\":\"openai\",\"model\":\"" + model + "\"}}}";
	}

	/**
	 * Returns Codex's empty answer to a request.
	 *
	 * @param requestId the request's identifier
	 * @return the response line
	 */
	private static String response(long requestId)
	{
		return "{\"id\":" + requestId + ",\"result\":{}}";
	}

	/**
	 * Returns the notification that a thread completed an assistant message.
	 *
	 * @param threadId the thread that wrote the message
	 * @param text the message
	 * @return the notification line
	 */
	private static String agentMessage(String threadId, String text)
	{
		return "{\"method\":\"item/completed\",\"params\":{\"item\":{\"type\":\"agentMessage\",\"id\":\"msg\"," +
			"\"text\":" + JsonMapper.builder().build().writeValueAsString(text) + "}," +
			"\"threadId\":\"" + threadId + "\",\"turnId\":\"turn\"}}";
	}

	/**
	 * Returns the notification that a thread's turn ended.
	 *
	 * @param threadId the thread
	 * @param turnId the turn
	 * @param status how the turn ended
	 * @return the notification line
	 */
	private static String turnCompleted(String threadId, String turnId, String status)
	{
		return "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + threadId + "\",\"turn\":{\"id\":\"" +
			turnId + "\",\"items\":[],\"status\":\"" + status + "\",\"error\":null}}}";
	}

	/**
	 * Supplies protocol responses to {@link CodexProcess}. Startup, turn-start and interrupt response shapes follow
	 * OpenAI's codex-cli 0.147.0
	 * <a href="https://github.com/openai/codex/blob/rust-v0.147.0/codex-rs/app-server/README.md">app-server README</a>
	 * (Initialization, Lifecycle Overview, and Interrupt an active turn). Notifications retain the fields consumed
	 * by the bridge. Root/child completion fields appear in {@code optimizer/codex-children.jsonl}
	 * (codex-cli 0.159.1); agent-message fields follow that README's Events and Items sections.
	 * Identifiers and message text are controlled test inputs. Withholding a response lets the caller choose when to
	 * deliver it; it makes no claim about harness timing. EOF-before-exit uses {@link FakeHarnessCli}'s generic process
	 * lifecycle model.
	 */
	private final class AppServer
	{
		private final JsonMapper json = JsonMapper.builder().build();
		private final LongFunction<List<String>> onTurnStart;
		private final LongFunction<List<String>> onTurnInterrupt;
		private final FakeHarnessCli process = new FakeHarnessCli(this::answer);
		private final CountDownLatch turnRequested = new CountDownLatch(1);
		private volatile long turnRequestId;
		private volatile boolean closesOutputAfterTurnStart;
		private volatile boolean notifiesThreadStarted;
		private volatile String threadStartError = "";
		private volatile boolean endsAtStartup;

		/**
		 * Creates an app-server that has not started.
		 *
		 * @param onTurnStart returns the lines to print for a {@code turn/start} request with the given identifier
		 * @param onTurnInterrupt returns the lines to print for a {@code turn/interrupt} request with the given
		 *   identifier
		 */
		private AppServer(LongFunction<List<String>> onTurnStart, LongFunction<List<String>> onTurnInterrupt)
		{
			this.onTurnStart = onTurnStart;
			this.onTurnInterrupt = onTurnInterrupt;
		}

		/**
		 * Makes the app-server close its output right after it answers {@code turn/start}, without reporting that it
		 * exited until a caller waits for it.
		 *
		 * @return this app-server
		 */
		private AppServer closingOutputAfterTurnStart()
		{
			closesOutputAfterTurnStart = true;
			return this;
		}

		/**
		 * Makes the app-server print a {@code thread/started} notification naming the requested model before it
		 * answers {@code thread/start}.
		 *
		 * @return this app-server
		 */
		private AppServer notifyingThreadStarted()
		{
			notifiesThreadStarted = true;
			return this;
		}

		/**
		 * Makes the app-server answer {@code thread/start} with a JSON-RPC error instead of a thread. The error's shape
		 * follows the response that codex-cli 0.160.0 printed when its sandbox helper could not start.
		 *
		 * @param message the error's message
		 * @return this app-server
		 */
		private AppServer refusingThreadStart(String message)
		{
			threadStartError = message;
			return this;
		}

		/**
		 * Makes the app-server print text on its standard error and end without answering {@code initialize}.
		 *
		 * @param error the text
		 * @return this app-server
		 */
		private AppServer endingAtStartup(String error)
		{
			process.printError(error);
			endsAtStartup = true;
			return this;
		}

		/**
		 * Starts a runner process over this app-server, whose Codex home and rollouts directory lie in a new temporary
		 * directory, and creates its root thread.
		 *
		 * @param directories removes the new directory when it closes
		 * @return the initialized runner process
		 * @throws IOException if initialization fails
		 */
		private CodexProcess start(TemporaryDirectories directories) throws IOException
		{
			return start(directories, "", "");
		}

		/**
		 * Starts a runner process over this app-server under a profile, whose Codex home and rollouts directory lie in a
		 * new temporary directory, and creates its root thread.
		 *
		 * @param directories removes the new directory when it closes
		 * @param model the model to select, or empty for Codex's default
		 * @param effort the reasoning effort to select, or empty for Codex's default
		 * @return the initialized runner process
		 * @throws IOException if initialization fails
		 */
		private CodexProcess start(TemporaryDirectories directories, String model, String effort) throws IOException
		{
			return start(directories.add(Files.createTempDirectory("codex-process-")), model, effort);
		}

		/**
		 * Starts a runner process over this app-server, whose Codex home and rollouts directory are the {@code home}
		 * and {@code rollouts} directories of a directory, and creates its root thread.
		 *
		 * @param directory the directory
		 * @return the initialized runner process
		 * @throws IOException if initialization fails
		 */
		private CodexProcess start(Path directory) throws IOException
		{
			return start(directory, "", "");
		}

		/**
		 * Starts a runner process over this app-server under a profile, whose Codex home and rollouts directory are
		 * the {@code home} and {@code rollouts} directories of a directory, and creates its root thread.
		 *
		 * @param directory the directory
		 * @param model the model to select, or empty for Codex's default
		 * @param effort the reasoning effort to select, or empty for Codex's default
		 * @return the initialized runner process
		 * @throws IOException if initialization fails
		 */
		private CodexProcess start(Path directory, String model, String effort) throws IOException
		{
			return start(directory, model, effort, true);
		}

		/**
		 * Starts a runner process over this app-server, whose Codex home and rollouts directory are the {@code home}
		 * and {@code rollouts} directories of a directory, and creates its root thread.
		 *
		 * @param directory the directory
		 * @param model the model to select, or empty for Codex's default
		 * @param effort the reasoning effort to select, or empty for Codex's default
		 * @param sessionTranscripts whether the process keeps the rollouts of its threads
		 * @return the initialized runner process
		 * @throws IOException if initialization fails
		 */
		private CodexProcess start(Path directory, String model, String effort, boolean sessionTranscripts)
			throws IOException
		{
			HarnessSessionTranscripts transcripts = null;
			if (sessionTranscripts)
				transcripts = CodexProcess.rollouts(directory.resolve("home"), directory.resolve("rollouts"));
			CodexProcess result = new CodexProcess(process, new TestHarnessRunnerScope(), false, transcripts);
			result.initialize(PROJECT, model, "", effort);
			return result;
		}

		/**
		 * Returns the requests received so far that call a method.
		 *
		 * @param method the method
		 * @return each request's parameters
		 */
		private List<JsonNode> requests(String method)
		{
			return process.requests().stream().map(json::readTree).
				filter(request -> request.path("method").asString("").equals(method)).
				map(request -> request.path("params")).
				toList();
		}

		/**
		 * Waits for the first {@code turn/start} request.
		 *
		 * @return the request's identifier
		 * @throws InterruptedException if the wait is interrupted
		 */
		private long awaitTurnRequest() throws InterruptedException
		{
			turnRequested.await();
			return turnRequestId;
		}

		/**
		 * Returns the {@code turn/interrupt} requests received so far.
		 *
		 * @return each request's thread and turn, as {@code thread/turn}
		 */
		private List<String> interruptRequests()
		{
			return process.requests().stream().map(json::readTree).
				filter(request -> request.path("method").asString("").equals("turn/interrupt")).
				map(request -> request.path("params").path("threadId").asString() + "/" +
					request.path("params").path("turnId").asString()).
				toList();
		}

		/**
		 * Answers one request that the runner wrote.
		 *
		 * @param line the request line
		 */
		private void answer(String line)
		{
			JsonNode request = json.readTree(line);
			long id = request.path("id").asLong(0);
			switch (request.path("method").asString(""))
			{
				case "initialize" ->
				{
					if (endsAtStartup)
					{
						process.exit();
						return;
					}
					process.emit("{\"id\":" + id + ",\"result\":{\"userAgent\":\"codex/0.147.0\"," +
						"\"codexHome\":\"/codex-home\",\"platformFamily\":\"unix\",\"platformOs\":\"linux\"}}");
				}
				case "thread/start" ->
				{
					if (threadStartError.isEmpty())
					{
						if (notifiesThreadStarted)
							process.emit(threadStarted(request.path("params").path("model").asString("")));
						process.emit("{\"id\":" + id + ",\"result\":{\"thread\":{\"id\":\"" + ROOT_THREAD +
							"\",\"path\":\"/codex-home/sessions/rollout-root.jsonl\"}}}");
					}
					else
					{
						process.emit("{\"error\":{\"code\":-32603,\"message\":" + json.writeValueAsString(threadStartError) +
							"},\"id\":" + id + "}");
					}
				}
				case "turn/start" ->
				{
					turnRequestId = id;
					turnRequested.countDown();
					process.emit(onTurnStart.apply(id).toArray(String[]::new));
					if (closesOutputAfterTurnStart)
						process.closeOutputThenExitWhenAwaited();
				}
				case "turn/interrupt" -> process.emit(onTurnInterrupt.apply(id).toArray(String[]::new));
				default ->
				{
				}
			}
		}
	}
}
