package io.github.cowwoc.cat.codex.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import io.github.cowwoc.cat.common.PluginInfo;
import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.runner.fakes.FakeHarnessCli;
import io.github.cowwoc.cat.common.runner.fakes.TestHarnessRunnerScope;
import io.github.cowwoc.cat.common.runner.HarnessProcessResult;
import io.github.cowwoc.cat.common.runner.HarnessRunManager;
import io.github.cowwoc.cat.common.runner.HarnessRunSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests how {@link CodexProcess} runs, interrupts and reports a turn, and how {@link CodexRunner} starts it. Each
 * test plays the Codex app-server with a {@link FakeHarnessCli} that answers JSON-RPC requests with notifications
 * shaped like those codex-cli 0.159.1 printed for a root thread with child threads.
 */
public final class CodexProcessTest
{
	private static final String ROOT_THREAD = "thread-root";
	private static final String CHILD_THREAD = "thread-child";
	private static final String ROOT_TURN = "turn-root";
	private static final Path PROJECT = Path.of("/project");
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
	 * Verifies the turn's assistant response is the text of the root thread's last completed {@code agentMessage},
	 * not a child thread's message that completed later.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void repliesWithLastRootAgentMessage() throws IOException
	{
		// Requirement: R-E13
		AppServer server = new AppServer(id -> List.of(turnStarted(id),
			agentMessage(ROOT_THREAD, "I will read the evidence."),
			agentMessage(CHILD_THREAD, "Child finding."),
			agentMessage(ROOT_THREAD, "{\"verdict\":\"fail\"}"),
			agentMessage(CHILD_THREAD, "Child afterthought."),
			turnCompleted(CHILD_THREAD, "turn-child", "completed"),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (CodexProcess process = server.start())
		{
			HarnessProcessResult result = process.sendTurn("Judge the evidence.");

			requireThat(result.completed(), "completed").isTrue();
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("{\"verdict\":\"fail\"}");
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
		try (CodexProcess process = server.start("gpt-5.5", "xhigh"))
		{
			process.sendTurn("Run the trial.");

			requireThat(server.requests("thread/start").getFirst().path("model").asString(""), "model").
				isEqualTo("gpt-5.5");
			requireThat(server.requests("turn/start").getFirst().path("effort").asString(""), "effort").
				isEqualTo("xhigh");
		}
	}

	/**
	 * Verifies that the runner starts the app-server under the model and effort it was asked for: the model reaches
	 * {@code thread/start} and the effort reaches each {@code turn/start}.
	 *
	 * @throws IOException if the run fails
	 */
	@Test(timeOut = 10_000)
	public void runnerSelectsTheRequestedProfile() throws IOException
	{
		// Requirement: R-E17
		AppServer server = new AppServer(id -> List.of(turnStarted(id),
			agentMessage(ROOT_THREAD, "done"),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("cat-codex-runner-"));
			Path artifact = createArtifact(root.resolve("artifact"));
			Path project = Files.createDirectories(root.resolve("project"));
			HarnessRunManager manager = new HarnessRunManager(root.resolve("runs"), Clock.systemUTC());
			List<List<String>> commands = new CopyOnWriteArrayList<>();
			CodexRunner runner = new CodexRunner((command, _, _) ->
			{
				commands.add(command);
				return new StartedProcess(server.process);
			});
			try (HarnessRunSession session = runner.start(artifact, project, manager, "codex",
				Map.of("HOME", root.resolve("home").toString()), "gpt-6.1-sol", "high", false, null))
			{
				session.sendTurn("Run the trial.");
			}

			requireThat(commands, "commands").isEqualTo(List.of(List.of("codex", "app-server", "--stdio",
				"--disable", "plugins")));
			requireThat(server.requests("thread/start").getFirst().path("model").asString(""), "model").
				isEqualTo("gpt-6.1-sol");
			requireThat(server.requests("turn/start").getFirst().path("effort").asString(""), "effort").
				isEqualTo("high");
		}
	}

	/**
	 * Creates the smallest directory that the runner accepts as a Codex artifact.
	 *
	 * @param artifact the directory to create
	 * @return {@code artifact}
	 * @throws IOException if the filesystem operation fails
	 */
	private static Path createArtifact(Path artifact) throws IOException
	{
		Files.createDirectories(artifact.resolve(".codex-plugin"));
		Files.createDirectories(artifact.resolve("client/bin"));
		Files.writeString(artifact.resolve(".codex-plugin/plugin.json"), "{}\n", UTF_8);
		Files.writeString(artifact.resolve("client/VERSION"), PluginInfo.CAT_VERSION + "\n", UTF_8);
		for (String launcher : List.of("plugin-info", "session-start"))
		{
			Path file = artifact.resolve("client/bin").resolve(launcher);
			Files.writeString(file, "#!/bin/sh\nexit 0\n", UTF_8);
			Files.setPosixFilePermissions(file, Set.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE));
		}
		return artifact;
	}

	/**
	 * Verifies an interrupt during a turn sends {@code turn/interrupt} for the active root turn, and the interrupted
	 * turn ends as a completed bridge turn with the root's last message.
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
		AppServer server = new AppServer(id -> List.of(turnStarted(id), agentMessage(ROOT_THREAD, "Working on it.")),
			id -> List.of(response(id), turnCompleted(ROOT_THREAD, ROOT_TURN, "interrupted")));
		CountDownLatch working = new CountDownLatch(1);
		try (ExecutorService turnThread = Executors.newSingleThreadExecutor();
			CodexProcess process = server.start())
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
			requireThat(result.assistantResponse(), "assistantResponse").isEqualTo("Working on it.");
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
		try (ExecutorService turnThread = Executors.newSingleThreadExecutor();
			CodexProcess process = server.start())
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
	 * Verifies an interrupt that arrives after the turn has ended sends nothing to Codex, and the process still serves
	 * the next turn.
	 *
	 * @throws IOException if a turn or the interrupt fails
	 */
	@Test(timeOut = 10_000)
	public void ignoresInterruptAfterTurnEnded() throws IOException
	{
		// Requirement: R-E10
		AppServer server = new AppServer(id -> List.of(turnStarted(id), agentMessage(ROOT_THREAD, "Done."),
			turnCompleted(ROOT_THREAD, ROOT_TURN, "completed")), NO_ANSWER);
		try (CodexProcess process = server.start())
		{
			process.sendTurn("First task.");

			process.interrupt();

			HarnessProcessResult next = process.sendTurn("Second task.");
			requireThat(server.interruptRequests(), "interruptRequests").isEmpty();
			requireThat(next.assistantResponse(), "assistantResponse").isEqualTo("Done.");
		}
	}

	/**
	 * Verifies that a turn which ends because Codex closed its output waits for the operating system to report the
	 * exit, instead of reading an exit status that does not exist yet.
	 *
	 * @throws IOException if the turn fails
	 */
	@Test(timeOut = 10_000)
	public void waitsForExitAfterOutputEnds() throws IOException
	{
		// Requirement: R-E13
		AppServer server = new AppServer(id -> List.of(turnStarted(id)), NO_ANSWER).closingOutputAfterTurnStart();
		try (CodexProcess process = server.start())
		{
			HarnessProcessResult result = process.sendTurn("Judge the evidence.");

			requireThat(result.completed(), "completed").isFalse();
			requireThat(result.standardError(), "standardError").contains("ended before turn completion");
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
			"\"text\":" + JsonMapper.builder().build().writeValueAsString(text) + ",\"phase\":\"final_answer\"}," +
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
	 * An in-memory app-server as the runner sees a started operating-system process. The runner records the
	 * process's identifier and start time, which an in-memory child lacks, so this process reports the test JVM's;
	 * everything else is the in-memory child's.
	 */
	private static final class StartedProcess extends Process
	{
		private final FakeHarnessCli child;

		/**
		 * Wraps an in-memory child.
		 *
		 * @param child the child
		 */
		private StartedProcess(FakeHarnessCli child)
		{
			this.child = child;
		}

		@Override
		public OutputStream getOutputStream()
		{
			return child.getOutputStream();
		}

		@Override
		public InputStream getInputStream()
		{
			return child.getInputStream();
		}

		@Override
		public InputStream getErrorStream()
		{
			return child.getErrorStream();
		}

		@Override
		public int waitFor() throws InterruptedException
		{
			return child.waitFor();
		}

		@Override
		public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException
		{
			return child.waitFor(timeout, unit);
		}

		@Override
		public int exitValue()
		{
			return child.exitValue();
		}

		@Override
		public boolean isAlive()
		{
			return child.isAlive();
		}

		@Override
		public void destroy()
		{
			child.destroy();
		}

		@Override
		public long pid()
		{
			return ProcessHandle.current().pid();
		}

		@Override
		public ProcessHandle toHandle()
		{
			return new Handle(child.toHandle());
		}

		/**
		 * The in-memory child's handle, with the test JVM's identifier and start time.
		 */
		private static final class Handle implements ProcessHandle
		{
			private final ProcessHandle child;

			/**
			 * Wraps the in-memory child's handle.
			 *
			 * @param child the child's handle
			 */
			private Handle(ProcessHandle child)
			{
				this.child = child;
			}

			@Override
			public long pid()
			{
				return ProcessHandle.current().pid();
			}

			@Override
			public Optional<ProcessHandle> parent()
			{
				return child.parent();
			}

			@Override
			public Stream<ProcessHandle> children()
			{
				return child.children();
			}

			@Override
			public Stream<ProcessHandle> descendants()
			{
				return child.descendants();
			}

			@Override
			public Info info()
			{
				return ProcessHandle.current().info();
			}

			@Override
			public CompletableFuture<ProcessHandle> onExit()
			{
				return child.onExit();
			}

			@Override
			public boolean supportsNormalTermination()
			{
				return child.supportsNormalTermination();
			}

			@Override
			public boolean destroy()
			{
				return child.destroy();
			}

			@Override
			public boolean destroyForcibly()
			{
				return child.destroyForcibly();
			}

			@Override
			public boolean isAlive()
			{
				return child.isAlive();
			}

			@Override
			public int compareTo(ProcessHandle other)
			{
				return child.compareTo(other);
			}

			@Override
			public boolean equals(Object value)
			{
				return value instanceof Handle other && other.child.equals(child);
			}

			@Override
			public int hashCode()
			{
				return child.hashCode();
			}
		}
	}

	/**
	 * Plays the Codex app-server: answers the start-up requests, then answers each {@code turn/start} and
	 * {@code turn/interrupt} request with the lines the test supplies.
	 */
	private static final class AppServer
	{
		private final JsonMapper json = JsonMapper.builder().build();
		private final LongFunction<List<String>> onTurnStart;
		private final LongFunction<List<String>> onTurnInterrupt;
		private final FakeHarnessCli process = new FakeHarnessCli(this::answer);
		private final CountDownLatch turnRequested = new CountDownLatch(1);
		private volatile long turnRequestId;
		private volatile boolean closesOutputAfterTurnStart;

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
		 * Starts a runner process over this app-server and creates its root thread.
		 *
		 * @return the initialized runner process
		 * @throws IOException if initialization fails
		 */
		private CodexProcess start() throws IOException
		{
			return start("", "");
		}

		/**
		 * Starts a runner process over this app-server under a profile and creates its root thread.
		 *
		 * @param model the model to select, or empty for Codex's default
		 * @param effort the reasoning effort to select, or empty for Codex's default
		 * @return the initialized runner process
		 * @throws IOException if initialization fails
		 */
		private CodexProcess start(String model, String effort) throws IOException
		{
			CodexProcess result = new CodexProcess(process, new TestHarnessRunnerScope(), false);
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
				case "initialize" -> process.emit(response(id));
				case "thread/start" -> process.emit("{\"id\":" + id + ",\"result\":{\"thread\":{\"id\":\"" +
					ROOT_THREAD + "\"}}}");
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
