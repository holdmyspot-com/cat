package com.holdmyspot.cat.common.runner.launcher.client.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.testng.Assert.expectThrows;

import com.holdmyspot.cat.common.ExecutableFiles.ProcessLauncher;
import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.InvalidArgumentsException;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel;
import com.holdmyspot.cat.common.runner.SessionLauncherChannel.Status;
import com.holdmyspot.cat.common.runner.launcher.ParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.SessionLauncher;
import com.holdmyspot.cat.common.runner.launcher.client.GitIdentity;
import com.holdmyspot.cat.common.runner.launcher.client.LaunchRefusedException;
import com.holdmyspot.cat.common.runner.launcher.client.SessionLauncherClient;
import com.holdmyspot.cat.common.runner.launcher.test.FakeProcessTable;
import com.holdmyspot.cat.common.runner.launcher.test.FakeRunner;
import com.holdmyspot.cat.common.runner.launcher.test.FakeStarter;
import com.holdmyspot.cat.common.runner.launcher.test.TrackedExecutor;
import com.holdmyspot.cat.common.runner.policy.ChildNeeds;
import com.holdmyspot.cat.common.runner.policy.ExtraPermissions;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.secrets.ProcessHolderStarterSecrets;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tests that a runner launch inside a sandboxed session becomes a request to the session launcher of its harness
 * process, through {@link SessionLauncherClient}.
 * <p>
 * Each test runs the real {@link SessionLauncher} in this process over a temporary channel folder. A
 * {@link FakeProcessTable} holds the harness process, its sandbox and the holders that the client's real holder starter
 * starts below the sandbox, where processes that play the holder shell stand in for the operating system's, and a
 * {@link FakeStarter} starts a {@link FakeRunner} in place of the child's runner. Each pause of the
 * client advances the shared clock by the inbox poll and lets the launcher poll once.
 */
public final class SessionLauncherClientTest
{
	/**
	 * The instant at which each test's clock starts.
	 */
	private static final Instant START = Instant.parse("2026-11-05T12:00:00Z");
	/**
	 * Why a test that models a refusal with POSIX permissions does not run on native Windows.
	 */
	private static final String NO_POSIX_PERMISSIONS = "POSIX permissions model the refusal, and native Windows " +
		"has neither them nor a session launcher";
	/**
	 * The product name of the harness that runs the tests' sessions.
	 */
	private static final String PRODUCT = "Claude Code";
	/**
	 * The host of the child's model, which the parent's policy lets it reach.
	 */
	private static final String MODEL_HOST = "model.example";
	/**
	 * The host of a package registry, which the parent's policy lets it reach.
	 */
	private static final String REGISTRY_HOST = "registry.example";
	/**
	 * How many pauses a launch may take before the test gives up on it.
	 */
	private static final int MAXIMUM_STEPS = 100_000;

	/**
	 * Creates the test.
	 */
	public SessionLauncherClientTest()
	{
	}

	/**
	 * Returns a path as the JSON text that a file of extra permissions holds, which escapes the backslashes of a
	 * Windows path.
	 *
	 * @param path the path
	 * @return the path as a quoted JSON string
	 */
	private static String jsonText(Path path)
	{
		return JsonMapper.builder().build().writeValueAsString(path.toString());
	}

	/**
	 * Runs before or after each poll of the launcher, on the thread that sends the request.
	 */
	@FunctionalInterface
	private interface PollHook
	{
		/**
		 * Acts on the fixture before or after a poll.
		 *
		 * @param fixture the fixture
		 * @throws IOException          if the fixture's files cannot be read
		 * @throws InterruptedException if the thread is interrupted while it waits for the launcher
		 */
		void act(Fixture fixture) throws IOException, InterruptedException;
	}

	/**
	 * One test's files, clock, processes, launcher and client: a harness process with a sandbox below it, the real
	 * launcher that serves the harness, and a client whose holders start below the sandbox.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final Path root;
		private final SessionLauncherChannel channel;
		private final ManualClock clock = new ManualClock(START);
		private final FakeProcessTable processes = new FakeProcessTable();
		private final ProcessIdentity harness = processes.start(100, Optional.empty(), List.of("claude"));
		private final ProcessIdentity sandbox = processes.start(150, Optional.of(harness), List.of("sandbox"));
		private final FakeStarter starter = new FakeStarter();
		private final TrackedExecutor executor = new TrackedExecutor();
		private final Policy policy;
		private final SessionLauncher launcher;
		private final HolderShells holders;
		private final SessionLauncherClient client;
		private final Path project;
		private final Path cache;
		private boolean serving;
		private int pauses = 1;
		private PollHook beforePoll = _ ->
		{
		};
		private PollHook afterPoll = _ ->
		{
		};
		private int steps;

		/**
		 * Creates the fixture.
		 *
		 * @throws IOException if the files cannot be written
		 */
		Fixture() throws IOException
		{
			try (TestJvmScope jvm = new TestJvmScope())
			{
				root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "client-")).toRealPath();
			}
			channel = new SessionLauncherChannel(Files.createDirectory(root.resolve("home")));
			Path work = Files.createDirectory(root.resolve("work"));
			cache = Files.createDirectory(root.resolve("registry cache"));
			policy = new Policy(new ParentPolicy(true, List.of(work, cache), List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of(MODEL_HOST, REGISTRY_HOST)), false, List.of()));
			launcher = new SessionLauncher(channel, "claude", harness, processes, policy,
				(_, runFolder, deniedReads, _) -> new ChildNeeds(runFolder, List.of(), deniedReads, Set.of(MODEL_HOST)),
				starter, new FileLocks(), clock, executor);
			holders = new HolderShells(channel, processes, sandbox);
			GitIdentity git = new GitIdentity()
			{
				@Override
				public Optional<String> name(Path project)
				{
					return Optional.of("Ada");
				}

				@Override
				public Optional<String> email(Path project)
				{
					return Optional.of("ada@example.com");
				}
			};
			client = new SessionLauncherClient(channel, PRODUCT,
				ProcessHolderStarterSecrets.getProcessHolderStarterAccess().create(holders), git, clock, this::step,
				Map.of("claude", "thread-a", "codex", "thread-b"));
			project = Files.createDirectory(root.resolve("project"));
			Files.writeString(project.resolve("README.md"), "project");
		}

		/**
		 * Starts the launcher, which registers it and makes each later pause poll it.
		 *
		 * @throws IOException if the launcher's state cannot be written
		 */
		void serve() throws IOException
		{
			requireThat(launcher.start(), "start").isTrue();
			serving = true;
		}

		/**
		 * Makes later pauses leave the launcher unpolled, as a launcher that stopped without ending its state.
		 */
		void stopServing()
		{
			serving = false;
		}

		/**
		 * Makes the launcher poll only once every so many pauses of the client, as a launcher that falls behind.
		 *
		 * @param pauses the number of pauses between polls
		 */
		void pollEvery(int pauses)
		{
			this.pauses = pauses;
		}

		/**
		 * Sets what runs before each later poll.
		 *
		 * @param beforePoll what runs
		 */
		void beforePoll(PollHook beforePoll)
		{
			this.beforePoll = beforePoll;
		}

		/**
		 * Sets what runs after each later poll.
		 *
		 * @param afterPoll what runs
		 */
		void afterPoll(PollHook afterPoll)
		{
			this.afterPoll = afterPoll;
		}

		/**
		 * Polls the launcher once.
		 *
		 * @throws IOException if the launcher's state cannot be read or written
		 */
		void poll() throws IOException
		{
			launcher.poll();
		}

		/**
		 * Waits until the launcher's requests have finished their work.
		 *
		 * @throws InterruptedException if the thread is interrupted while waiting
		 */
		void awaitIdle() throws InterruptedException
		{
			executor.awaitIdle();
		}

		/**
		 * Pauses the client: advances the clock by the inbox poll and lets the launcher poll once, or once every
		 * {@link #pollEvery(int) so many pauses}.
		 *
		 * @throws IOException if the launcher's state cannot be read or written
		 */
		private void step() throws IOException
		{
			steps += 1;
			if (steps > MAXIMUM_STEPS)
				throw new AssertionError("The client never stopped waiting");
			clock.advance(SessionLauncherChannel.INBOX_POLL);
			if (!serving || steps % pauses != 0)
				return;
			try
			{
				beforePoll.act(this);
				launcher.poll();
				afterPoll.act(this);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new InterruptedIOException(e.getMessage());
			}
			// The launcher serves each request on its own thread
			Thread.yield();
		}

		/**
		 * Writes a records file with one turn and {@code complete}.
		 *
		 * @return the file
		 * @throws IOException if it cannot be written
		 */
		Path records() throws IOException
		{
			return Files.writeString(root.resolve("turns.jsonl"), "{\"type\":\"turn\",\"prompt\":\"task\"}\n" +
				"{\"type\":\"complete\"}\n");
		}

		/**
		 * Runs a launch with empty standard input.
		 *
		 * @param errors receives the reason when the launch does not complete
		 * @param args   the launch's options
		 * @return the exit status
		 * @throws IOException if the launch fails
		 */
		int launch(StringWriter errors, String... args) throws IOException
		{
			return launch(new StringReader(""), errors, args);
		}

		/**
		 * Runs a launch.
		 *
		 * @param input  the launch's standard input
		 * @param errors receives the reason when the launch does not complete
		 * @param args   the launch's options
		 * @return the exit status
		 * @throws IOException if the launch fails
		 */
		int launch(Reader input, StringWriter errors, String... args) throws IOException
		{
			return client.launch("claude", args, root, HarnessTemporaryRoot.of(root.resolve("session-temp")), input,
				new ByteArrayOutputStream(), new PrintWriter(errors, true));
		}

		/**
		 * Lists the inbox's entries.
		 *
		 * @return their names
		 * @throws IOException if the inbox cannot be listed
		 */
		List<String> inbox() throws IOException
		{
			if (!Files.isDirectory(channel.inbox()))
				return List.of();
			try (Stream<Path> entries = Files.list(channel.inbox()))
			{
				return entries.map(path -> path.getFileName().toString()).toList();
			}
		}

		/**
		 * Returns the folders of the requests that the launcher accepted and still keeps.
		 *
		 * @return the folders
		 * @throws IOException if the requests' folder cannot be listed
		 */
		List<Path> requests() throws IOException
		{
			if (!Files.isDirectory(channel.requests()))
				return List.of();
			try (Stream<Path> entries = Files.list(channel.requests()))
			{
				return entries.filter(entry -> Files.isRegularFile(entry.resolve(SessionLauncherChannel.ACCEPTED_FILE))).
					toList();
			}
		}

		/**
		 * Returns how many streamed turns the launcher has taken from the inbox.
		 *
		 * @return the number of turns, over every request that it keeps
		 * @throws IOException if a request's files cannot be read
		 */
		int turnsConsumed() throws IOException
		{
			int count = 0;
			for (Path request : requests())
			{
				Path turns = request.resolve(SessionLauncherChannel.TURNS_FILE);
				if (Files.isRegularFile(turns))
					count += (int) Files.readAllLines(turns, UTF_8).stream().filter(line -> !line.isBlank()).count();
			}
			return count;
		}

		/**
		 * Returns the runner of the fixture's only request.
		 *
		 * @return the runner
		 */
		FakeRunner runner()
		{
			List<FakeRunner> runners = starter.runners();
			requireThat(runners.size(), "runners.size()").isEqualTo(1);
			return runners.getFirst();
		}

		@Override
		public void close() throws IOException
		{
			try (cleanup)
			{
				executor.release();
				launcher.close();
				executor.awaitIdle();
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				throw new InterruptedIOException(e.getMessage());
			}
		}
	}

	/**
	 * Plays the holder shell that the real holder starter runs, as processes of the fake table below a parent that the
	 * test chooses: the session's sandbox, or a process outside the served harness's tree. A holder starts before its
	 * message is published, and like the shell it reads its standard input and ends when that input closes.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class HolderShells implements ProcessLauncher
	{
		private final SessionLauncherChannel channel;
		private final FakeProcessTable processes;
		private final List<HolderShell> started = new CopyOnWriteArrayList<>();
		private final AtomicInteger nextPid = new AtomicInteger(1000);
		private volatile ProcessIdentity parent;

		/**
		 * Creates the shells.
		 *
		 * @param channel   the channel whose inbox receives the messages
		 * @param processes the table that holds the holders
		 * @param parent    the holders' parent
		 */
		HolderShells(SessionLauncherChannel channel, FakeProcessTable processes, ProcessIdentity parent)
		{
			this.channel = channel;
			this.processes = processes;
			this.parent = parent;
		}

		/**
		 * Starts later holders outside the served harness's process tree.
		 */
		void startOutsideHarness()
		{
			parent = processes.start(200, Optional.empty(), List.of("another program"));
		}

		@Override
		public Process start(ProcessBuilder builder)
		{
			List<String> command = builder.command();
			requireThat(command.getFirst(), "shell").isEqualTo(SessionLauncherChannel.HOLDER_SHELL.toString());
			List<String> arguments = List.copyOf(command.subList(1, command.size()));
			String name;
			if (arguments.get(4).equals("0"))
				name = SessionLauncherChannel.requestFileName(arguments.get(3));
			else
				name = SessionLauncherChannel.turnFileName(arguments.get(3), Integer.parseInt(arguments.get(4)));
			if (Files.exists(channel.inbox().resolve(name)))
				throw new AssertionError("The message was published before its holder started: " + name);
			ProcessIdentity process = processes.start(nextPid.incrementAndGet(), Optional.of(parent), arguments);
			HolderShell holder = new HolderShell(processes, process, arguments);
			started.add(holder);
			return holder;
		}

		/**
		 * Returns the holders started so far.
		 *
		 * @return the holders, in start order
		 */
		List<HolderShell> started()
		{
			return List.copyOf(started);
		}

		/**
		 * Returns the holder of the request message.
		 *
		 * @return the holder
		 */
		HolderShell request()
		{
			return started.stream().filter(holder -> holder.arguments.get(4).equals("0")).findFirst().orElseThrow();
		}
	}

	/**
	 * A holder shell's process in the fake table, which ends when its standard input closes.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class HolderShell extends Process
	{
		private final FakeProcessTable processes;
		private final ProcessIdentity process;
		private final CountDownLatch ended = new CountDownLatch(1);
		final List<String> arguments;
		volatile boolean closed;

		/**
		 * Creates a holder.
		 *
		 * @param processes the table that holds its process
		 * @param process   its process
		 * @param arguments the arguments that follow the shell on its command line
		 */
		HolderShell(FakeProcessTable processes, ProcessIdentity process, List<String> arguments)
		{
			this.processes = processes;
			this.process = process;
			this.arguments = arguments;
		}

		/**
		 * Ends the shell's process.
		 */
		private void end()
		{
			processes.end(process);
			closed = true;
			ended.countDown();
		}

		@Override
		public OutputStream getOutputStream()
		{
			return new OutputStream()
			{
				@Override
				public void write(int value)
				{
				}

				@Override
				public void close()
				{
					end();
				}
			};
		}

		@Override
		public InputStream getInputStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public InputStream getErrorStream()
		{
			return InputStream.nullInputStream();
		}

		@Override
		public int waitFor() throws InterruptedException
		{
			ended.await();
			return 0;
		}

		@Override
		public int exitValue()
		{
			if (ended.getCount() != 0)
				throw new IllegalThreadStateException("The holder has not ended");
			return 0;
		}

		@Override
		public void destroy()
		{
			end();
		}
	}

	/**
	 * A sandbox policy source whose answer the test sets, for the session and for its threads.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class Policy implements ParentPolicySource
	{
		private final ParentPolicy session;
		private final Map<String, ParentPolicy> threads = new ConcurrentHashMap<>();

		/**
		 * Creates a source.
		 *
		 * @param session the policy of the session's threads that have none of their own
		 */
		Policy(ParentPolicy session)
		{
			this.session = session;
		}

		/**
		 * Gives one thread a policy of its own.
		 *
		 * @param thread the thread's identifier
		 * @param policy the thread's policy
		 */
		void allowThread(String thread, ParentPolicy policy)
		{
			threads.put(thread, policy);
		}

		@Override
		public String harnessName()
		{
			return PRODUCT;
		}

		@Override
		public String permissionEntries(List<Path> writes, Set<String> hosts)
		{
			return "add " + writes + " and " + hosts + " to the settings of " + PRODUCT;
		}

		@Override
		public ParentPolicy read(Optional<String> askingThread)
		{
			return threads.getOrDefault(askingThread.orElse(""), session);
		}
	}

	/**
	 * Verifies that with no launcher registered, the launch exits at once with status 2 and the no-launcher message,
	 * publishing nothing and starting no holder.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void exitsAtOnceWithoutLauncher() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			Files.createDirectories(fixture.channel.launchers());
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(), "errors").contains(SessionLauncherChannel.noLauncherMessage(PRODUCT));
			requireThat(fixture.inbox(), "inbox").isEmpty();
			requireThat(fixture.holders.started(), "holders").isEmpty();
			requireThat(fixture.clock.instant(), "clock").isEqualTo(START);
		}
	}

	/**
	 * Verifies that when the launchers' state folder cannot be read, the launch exits with status 2 and a message
	 * that names the folder.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void exitsWhenStateIsUnreadable() throws IOException
	{
		// Requirement: R-I11
		if (Platform.current().isWindows())
			throw new SkipException(NO_POSIX_PERMISSIONS);
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			Path state = fixture.channel.stateRoot();
			Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("---------"));
			StringWriter errors = new StringWriter();
			int status;
			try
			{
				status = fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m",
					"--effort", "e", "--input", fixture.records().toString());
			}
			finally
			{
				Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
			}

			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(), "errors").contains(state.toString());
			requireThat(fixture.inbox(), "inbox").isEmpty();
		}
	}

	/**
	 * Verifies that a request that no launcher claims, because its holder runs outside the served harness's process
	 * tree, ends after the acceptance wait with status 2 and the no-launcher message, leaving no inbox file and ending
	 * its holder.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void exitsWhenRequestIsUnclaimed() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			fixture.holders.startOutsideHarness();
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(), "errors").contains(SessionLauncherChannel.noLauncherMessage(PRODUCT));
			Duration waited = Duration.between(START, fixture.clock.instant());
			requireThat(waited, "waited").isGreaterThanOrEqualTo(SessionLauncherChannel.ACCEPTANCE_WAIT).
				isLessThan(SessionLauncherChannel.ACCEPTANCE_WAIT.plusSeconds(1));
			requireThat(fixture.inbox(), "inbox").isEmpty();
			requireThat(fixture.holders.started().size(), "holders").isEqualTo(1);
			requireThat(fixture.holders.request().closed, "closed").isTrue();
			requireThat(fixture.starter.runners(), "runners").isEmpty();
		}
	}

	/**
	 * Verifies that a launch names, for each harness, the thread that the harness's session variable identifies,
	 * and no thread for a harness whose variable is unset or blank.
	 */
	@Test
	public void asksForThreadOfSessionVariable()
	{
		SessionHarness first = new SessionHarness("harness-a", "HARNESS_A_THREAD", "HARNESS_A_HOME", Path.of(".a"),
			List.of("<session-id>.jsonl"));
		SessionHarness blank = new SessionHarness("harness-b", "HARNESS_B_THREAD", "HARNESS_B_HOME", Path.of(".b"),
			List.of("<session-id>.jsonl"));
		SessionHarness unset = new SessionHarness("harness-c", "HARNESS_C_THREAD", "HARNESS_C_HOME", Path.of(".c"),
			List.of("<session-id>.jsonl"));
		Map<String, String> environment = Map.of("HARNESS_A_THREAD", "thread-a", "HARNESS_B_THREAD", " ");

		requireThat(SessionLauncherClient.askingThreads(environment, List.of(first, blank, unset)), "askingThreads").
			isEqualTo(Map.of("harness-a", "thread-a"));
	}

	/**
	 * Verifies that a completed request writes the child's records to {@code --output} and its receipt to
	 * {@code --receipt}, copies the kept run into {@code --run-root} without links or the child's home, names the
	 * copy in the records and the receipt, and exits with status 0. The child's runner receives the launch's choices,
	 * the project's Git identity and the launch's records, under a holder that ends with the launch.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void completedRequestCopiesResults() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			Path output = fixture.root.resolve("out/transcript.jsonl");
			Path receipt = fixture.root.resolve("out/receipt.json");
			Path runRoot = fixture.root.resolve("runs");
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString(), "--output", output.toString(), "--receipt",
				receipt.toString(), "--keep-run", "--run-root", runRoot.toString());

			requireThat(errors.toString(), "errors").isEmpty();
			requireThat(status, "status").isEqualTo(0);
			Path run = runRoot.resolve("claude").resolve(FakeRunner.RUN_ID);
			requireThat(Files.readString(output, UTF_8), "output").
				contains("\"type\":\"started\"").
				contains("reply 1").
				contains("\"type\":\"completed\"");
			requireThat(recordPaths(output), "recordPaths").
				isEqualTo(List.of(run.resolve(FakeRunner.SESSION_TRANSCRIPT).toString()));
			JsonNode receiptDocument = JsonMapper.builder().build().readTree(Files.readString(receipt, UTF_8));
			requireThat(receiptDocument.path("retained_project").asString(), "retainedProject").
				isEqualTo(run.resolve("project").toString());
			requireThat(Files.readString(run.resolve("artifacts/result.txt"), UTF_8), "result").isEqualTo("result");
			requireThat(Files.exists(run.resolve("run.json")), "runManifest").isTrue();
			requireThat(Files.exists(run.resolve("home")), "home").isFalse();
			requireThat(Files.exists(run.resolve("link"), LinkOption.NOFOLLOW_LINKS), "link").isFalse();
			FakeRunner runner = fixture.runner();
			requireThat(runner.option("--model"), "model").isEqualTo(Optional.of("m"));
			requireThat(runner.arguments(), "arguments").contains("--keep-run").doesNotContain("--capacity-held");
			requireThat(runner.option("--git-name"), "gitName").isEqualTo(Optional.of("Ada"));
			requireThat(runner.records(), "records").isEqualTo(List.of("{\"type\":\"turn\",\"prompt\":\"task\"}",
				"{\"type\":\"complete\"}"));
			requireThat(fixture.holders.request().closed, "requestHolderClosed").isTrue();
		}
	}

	/**
	 * Verifies that a launch asks as the thread that the launch's harness names: the launcher bounds the child by that
	 * thread's policy, which here refuses a write that the session's policy allows.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void asksAsThreadOfLaunchHarness() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			fixture.policy.allowThread("thread-a", new ParentPolicy(true, List.of(fixture.root.resolve("work")),
				List.of(), List.of(), List.of(), HostAccess.listed(Set.of(MODEL_HOST)), false, List.of()));
			Files.writeString(fixture.root.resolve("needs.json"), """
				{"format_version": 1, "writes": [%s], "hosts": []}
				""".formatted(jsonText(fixture.cache)));
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m", "--effort",
				"e", "--input", fixture.records().toString(), "--extra-permissions", "needs.json");

			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(), "errors").contains(fixture.cache.toString());
			requireThat(fixture.starter.runners(), "runners").isEmpty();
		}
	}

	/**
	 * The one launch that the copy-failure tests share: a request whose kept run cannot be copied in full, because
	 * the sender already has a file where the copy of the child's {@code artifacts/result.txt} belongs. The existing
	 * file stands for a protected file that the sender's sandbox refuses to write, and copying onto it fails.
	 *
	 * @param fixture    the test's fixture
	 * @param exitStatus the status with which the child's runner exits
	 * @param errors     receives why the launch did not complete
	 * @param output     the file that receives the child's records
	 * @param runRoot    the run root that receives the kept run
	 * @return the launch's exit status
	 * @throws IOException if the fixture cannot be written
	 */
	private static int launchWithBlockedCopy(Fixture fixture, int exitStatus, StringWriter errors, Path output,
		Path runRoot) throws IOException
	{
		fixture.serve();
		fixture.starter.exitWith(exitStatus);
		Path blocked = blockedFile(runRoot);
		Files.createDirectories(blocked.getParent());
		Files.writeString(blocked, "protected");
		return fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m", "--effort", "e",
			"--input", fixture.records().toString(), "--output", output.toString(), "--keep-run", "--run-root",
			runRoot.toString());
	}

	/**
	 * Returns the file of the sender's copy of the kept run that the copy-failure tests block.
	 *
	 * @param runRoot the run root that receives the kept run
	 * @return the file
	 */
	private static Path blockedFile(Path runRoot)
	{
		return runRoot.resolve("claude").resolve(FakeRunner.RUN_ID).resolve("artifacts/result.txt");
	}

	/**
	 * Verifies that when the sender cannot write one file of a completed session's kept run, the child's records and
	 * its receipt still reach the caller, and the launch then reports that the kept run is incomplete, naming the
	 * file and the kept run, and exits with status 1.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void completedRequestReportsFailedCopy() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path output = fixture.root.resolve("out/transcript.jsonl");
			Path runRoot = fixture.root.resolve("runs");
			StringWriter errors = new StringWriter();

			int status = launchWithBlockedCopy(fixture, 0, errors, output, runRoot);

			requireThat(status, "status").isEqualTo(1);
			requireThat(Files.readString(output, UTF_8), "output").
				contains("reply 1").
				contains("\"type\":\"completed\"");
			requireThat(errors.toString(), "errors").
				contains(blockedFile(runRoot).toString()).
				contains("The kept run at " + runRoot.resolve("claude").resolve(FakeRunner.RUN_ID) +
					" is incomplete");
		}
	}

	/**
	 * The files of a kept run's project that the child's sandbox kept it from changing, and the files beside them.
	 */
	private static final List<String> PROJECT_FILES = List.of("project/.git/config", "project/.git/hooks/pre-commit",
		"project/.git/HEAD", "project/.claude/settings.json", "project/.claude/rules.md", "project/.gitconfig",
		"project/README.md");

	/**
	 * Verifies that a kept run's copy leaves out the project's Git configuration and hooks and its harness settings
	 * files, which the child could not change, and copies every other file, including the rest of {@code .git}. The
	 * launch prints nothing and a completed session exits with status 0, and the receipt lists no skipped path.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void keptRunOmitsUnchangedFiles() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.starter.runFiles(PROJECT_FILES);
			Path receipt = fixture.root.resolve("out/receipt.json");
			Path runRoot = fixture.root.resolve("runs");
			StringWriter errors = new StringWriter();

			int status = launchKept(fixture, errors, receipt, runRoot);

			requireThat(errors.toString(), "errors").isEmpty();
			requireThat(status, "status").isEqualTo(0);
			Path run = runRoot.resolve("claude").resolve(FakeRunner.RUN_ID);
			for (String name : List.of("project/.git/config", "project/.git/hooks/pre-commit",
				"project/.claude/settings.json", "project/.gitconfig"))
				requireThat(Files.exists(run.resolve(name)), name).isFalse();
			requireThat(Files.exists(run.resolve("project/.git/hooks")), "hooks").isFalse();
			for (String name : List.of("project/.git/HEAD", "project/.claude/rules.md", "project/README.md",
				"artifacts/result.txt"))
				requireThat(Files.isRegularFile(run.resolve(name)), name).isTrue();
			requireThat(Files.readString(receipt, UTF_8), "receipt").doesNotContain("skipped");
		}
	}

	/**
	 * Verifies that when the sender's sandbox refuses to write one file of a kept run, the copy skips that file and
	 * copies every other file, the launch prints nothing, a completed session exits with status 0, and the receipt
	 * lists the skipped path relative to the run.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusedFileIsSkippedSilently() throws IOException
	{
		if (Platform.current().isWindows())
			throw new SkipException(NO_POSIX_PERMISSIONS);
		try (Fixture fixture = new Fixture())
		{
			fixture.starter.runFiles(List.of("project/README.md", "project/locked/a.txt"));
			Path receipt = fixture.root.resolve("out/receipt.json");
			Path runRoot = fixture.root.resolve("runs");
			Path run = runRoot.resolve("claude").resolve(FakeRunner.RUN_ID);
			Path locked = Files.createDirectories(run.resolve("project/locked"));
			Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
			StringWriter errors = new StringWriter();
			int status;
			try
			{
				status = launchKept(fixture, errors, receipt, runRoot);
			}
			finally
			{
				Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
			}

			requireThat(errors.toString(), "errors").isEmpty();
			requireThat(status, "status").isEqualTo(0);
			requireThat(Files.readString(run.resolve("project/README.md"), UTF_8), "readme").isEqualTo(
				"project/README.md");
			requireThat(Files.readString(run.resolve("artifacts/result.txt"), UTF_8), "result").isEqualTo("result");
			requireThat(Files.exists(run.resolve("project/locked/a.txt")), "a").isFalse();
			requireThat(skippedPaths(receipt), "skipped").isEqualTo(List.of("project/locked/a.txt"));
		}
	}

	/**
	 * Verifies that when the sender's sandbox refuses to create one folder of a kept run, the copy skips the folder's
	 * subtree, copies its siblings, and lists only the folder in the receipt.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusedFolderIsSkippedWithItsSubtree() throws IOException
	{
		if (Platform.current().isWindows())
			throw new SkipException(NO_POSIX_PERMISSIONS);
		try (Fixture fixture = new Fixture())
		{
			fixture.starter.runFiles(List.of("project/data/sub/a.txt", "project/data/sub/deep/b.txt",
				"project/open/c.txt"));
			Path receipt = fixture.root.resolve("out/receipt.json");
			Path runRoot = fixture.root.resolve("runs");
			Path run = runRoot.resolve("claude").resolve(FakeRunner.RUN_ID);
			Path data = Files.createDirectories(run.resolve("project/data"));
			Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("r-x------"));
			StringWriter errors = new StringWriter();
			int status;
			try
			{
				status = launchKept(fixture, errors, receipt, runRoot);
			}
			finally
			{
				Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rwx------"));
			}

			requireThat(errors.toString(), "errors").isEmpty();
			requireThat(status, "status").isEqualTo(0);
			requireThat(Files.exists(run.resolve("project/data/sub")), "sub").isFalse();
			requireThat(Files.isRegularFile(run.resolve("project/open/c.txt")), "sibling").isTrue();
			requireThat(Files.isRegularFile(run.resolve("artifacts/result.txt")), "result").isTrue();
			requireThat(skippedPaths(receipt), "skipped").isEqualTo(List.of("project/data/sub"));
		}
	}

	/**
	 * Verifies that a failure that is not a permission refusal still ends the copy and yields the incomplete-copy
	 * message and exit status 1, even when the copy also skipped a refused file.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void otherFailureReportsIncompleteRun() throws IOException
	{
		if (Platform.current().isWindows())
			throw new SkipException(NO_POSIX_PERMISSIONS);
		try (Fixture fixture = new Fixture())
		{
			fixture.starter.runFiles(List.of("project/locked/a.txt"));
			Path output = fixture.root.resolve("out/transcript.jsonl");
			Path runRoot = fixture.root.resolve("runs");
			Path locked = Files.createDirectories(runRoot.resolve("claude").resolve(FakeRunner.RUN_ID).
				resolve("project/locked"));
			Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
			StringWriter errors = new StringWriter();
			int status;
			try
			{
				status = launchWithBlockedCopy(fixture, 0, errors, output, runRoot);
			}
			finally
			{
				Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
			}

			requireThat(status, "status").isEqualTo(1);
			requireThat(errors.toString(), "errors").
				contains(blockedFile(runRoot).toString()).
				contains("is incomplete").
				doesNotContain("locked");
		}
	}

	/**
	 * Runs a completed launch that keeps the run and writes its receipt.
	 *
	 * @param fixture the test's fixture
	 * @param errors  receives why the launch did not complete
	 * @param receipt the file that receives the child's receipt
	 * @param runRoot the run root that receives the kept run
	 * @return the launch's exit status
	 * @throws IOException if the fixture cannot be written
	 */
	private static int launchKept(Fixture fixture, StringWriter errors, Path receipt, Path runRoot)
		throws IOException
	{
		fixture.serve();
		return fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m", "--effort", "e",
			"--input", fixture.records().toString(), "--output", fixture.root.resolve("out/transcript.jsonl").
			toString(), "--receipt", receipt.toString(), "--keep-run", "--run-root", runRoot.toString());
	}

	/**
	 * Returns the {@code path} fields of the records that a launch wrote to {@code --output}.
	 *
	 * @param output the records
	 * @return the paths, in the records' order
	 * @throws IOException if the records cannot be read
	 */
	private static List<String> recordPaths(Path output) throws IOException
	{
		JsonMapper json = JsonMapper.builder().build();
		List<String> paths = new ArrayList<>();
		for (String line : Files.readAllLines(output, UTF_8))
		{
			JsonNode path = json.readTree(line).path("path");
			if (path.isString())
				paths.add(path.asString());
		}
		return paths;
	}

	/**
	 * Returns the paths that a receipt lists as skipped by the copy of the kept run.
	 *
	 * @param receipt the receipt
	 * @return the paths, empty if the receipt lists none
	 * @throws IOException if the receipt cannot be read
	 */
	private static List<String> skippedPaths(Path receipt) throws IOException
	{
		JsonNode document = JsonMapper.builder().build().readTree(Files.readString(receipt, UTF_8));
		List<String> paths = new ArrayList<>();
		for (JsonNode path : document.path("skipped_copy_paths"))
			paths.add(path.asString());
		return paths;
	}

	/**
	 * Verifies that when the sender cannot write one file of a refused session's kept run, the launch exits with
	 * status 2 and prints the refusal's message before the message that the kept run is incomplete.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusalComesBeforeFailedCopyMessage() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path output = fixture.root.resolve("out/transcript.jsonl");
			Path runRoot = fixture.root.resolve("runs");
			StringWriter errors = new StringWriter();

			int status = launchWithBlockedCopy(fixture, Status.REFUSED.exitStatus(), errors, output, runRoot);

			requireThat(status, "status").isEqualTo(2);
			String message = errors.toString();
			int refusal = message.indexOf(FakeRunner.reason(Status.REFUSED.exitStatus()));
			int incomplete = message.indexOf("is incomplete");
			requireThat(refusal, "refusal").isNotNegative();
			requireThat(incomplete, "incomplete").isGreaterThan(refusal);
			requireThat(message, "errors").contains(blockedFile(runRoot).toString());
		}
	}

	/**
	 * Returns each status that does not complete, with the exit status that the launch returns for it.
	 *
	 * @return the status and the exit status
	 */
	@DataProvider
	public Object[][] incomplete()
	{
		return new Object[][]{{Status.CAPACITY, 75}, {Status.REFUSED, 2}, {Status.FAILED, 1}};
	}

	/**
	 * Verifies that a request that does not complete, because the child's runner exits with that status's exit
	 * status, exits with the same status and prints the reason that the runner gives.
	 *
	 * @param answer     how the request ends
	 * @param exitStatus the expected exit status
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "incomplete")
	public void incompleteRequestReportsReason(Status answer, int exitStatus) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			fixture.starter.exitWith(answer.exitStatus());
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(status, "status").isEqualTo(exitStatus);
			requireThat(errors.toString(), "errors").contains(answer.value()).
				contains(FakeRunner.reason(answer.exitStatus()));
		}
	}

	/**
	 * Verifies that a response that the sender finds before it can read it does not end the launch: the sender keeps
	 * waiting, reads the launcher's response once that replaces it, and reports how the request ended.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void unreadableResponseIsAwaited() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			fixture.starter.exitWith(Status.REFUSED.exitStatus());
			// The launcher accepts the request but does not run it until the test lets it
			fixture.executor.hold();
			fixture.afterPoll(polled ->
			{
				List<Path> requests = polled.requests();
				if (requests.isEmpty())
					return;
				Path response = requests.getFirst().resolve(SessionLauncherChannel.RESPONSE_FILE);
				if (!Files.exists(response))
				{
					// After the poll that accepts the request, the test puts the start of a response document in its
					// place. It stands for a response that the sender finds but cannot read yet: on Windows, one that
					// is still in use when the sender opens it.
					Files.writeString(response, "{\"format_version\":1,\"id\":\"");
					return;
				}
				// The sender has failed to read that document once. The launcher now runs the request, whose records
				// it already holds, and its response replaces the document. Waiting here for that answer keeps the
				// sender's clock from running past its read wait while the launcher's thread works.
				polled.executor.release();
				polled.awaitIdle();
			});
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(status, "status").isEqualTo(Status.REFUSED.exitStatus());
			requireThat(errors.toString(), "errors").contains(FakeRunner.reason(Status.REFUSED.exitStatus()));
		}
	}

	/**
	 * Verifies that a response that stays unreadable ends the launch with the read's failure once the sender has tried
	 * to read it for {@link SessionLauncherChannel#RESPONSE_READ_WAIT}, rather than at the first failed read or never.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void responseUnreadableTooLongFails() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			// The launcher accepts the request but never runs it, so its own response never replaces the one below
			fixture.executor.hold();
			fixture.afterPoll(polled ->
			{
				List<Path> requests = polled.requests();
				if (requests.isEmpty())
					return;
				Path response = requests.getFirst().resolve(SessionLauncherChannel.RESPONSE_FILE);
				if (!Files.exists(response))
					Files.writeString(response, "{\"format_version\":1,\"id\":\"");
			});

			IOException failure = expectThrows(IOException.class, () -> fixture.launch(new StringWriter(),
				"--project", fixture.project.toString(), "--model", "m", "--effort", "e", "--input",
				fixture.records().toString()));

			requireThat(failure.getMessage(), "message").contains("Malformed response").
				contains(SessionLauncherChannel.RESPONSE_FILE);
			requireThat(Duration.between(START, fixture.clock.instant()), "waited").
				isGreaterThanOrEqualTo(SessionLauncherChannel.RESPONSE_READ_WAIT);
		}
	}

	/**
	 * Verifies that an acceptance that the sender finds before it can read it does not end the launch: the sender
	 * keeps waiting, reads the acceptance once it can, and the request completes.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void unreadableAcceptanceIsAwaited() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			AtomicInteger pollsSinceAcceptance = new AtomicInteger();
			AtomicReference<Closeable> refusal = new AtomicReference<>();
			fixture.afterPoll(polled ->
			{
				List<Path> requests = polled.requests();
				if (requests.isEmpty())
					return;
				Path accepted = requests.getFirst().resolve(SessionLauncherChannel.ACCEPTED_FILE);
				int poll = pollsSinceAcceptance.getAndIncrement();
				// After the poll that accepts the request, the test refuses reads of the acceptance until the next
				// poll. It stands for an acceptance that the sender finds but cannot open yet: on Windows, one that is
				// still in use when the sender opens it.
				if (poll == 0)
					refusal.set(refuseReads(accepted));
				else if (poll == 1)
					refusal.get().close();
			});

			int status = fixture.launch(new StringWriter(), "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(status, "status").isEqualTo(0);
			requireThat(pollsSinceAcceptance.get(), "pollsSinceAcceptance").isGreaterThanOrEqualTo(2);
		}
	}

	/**
	 * Refuses every other read of a file until the returned value is closed. On native Windows the file stays locked,
	 * so a read through another handle fails as it does while another process holds a lock on the file; elsewhere the
	 * file loses every permission, because a lock there does not stop another reader.
	 *
	 * @param file the file
	 * @return what lets the file be read again when it is closed
	 * @throws IOException if the file cannot be locked or its permissions cannot be read or changed
	 */
	private static Closeable refuseReads(Path file) throws IOException
	{
		if (Platform.current().isWindows())
		{
			FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
			try
			{
				channel.lock();
			}
			catch (IOException e)
			{
				channel.close();
				throw e;
			}
			// Closing the channel releases its lock
			return channel;
		}
		Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
		Files.setPosixFilePermissions(file, Set.of());
		return () -> Files.setPosixFilePermissions(file, permissions);
	}

	/**
	 * Verifies that a request file that the launcher finds before it can read it is accepted at a later poll that can
	 * read it, and the request completes.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void unreadableRequestIsAcceptedLater() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			AtomicReference<Path> refused = new AtomicReference<>();
			AtomicReference<Closeable> refusal = new AtomicReference<>();
			// During the first poll that finds the request file, the test refuses reads of it. It stands for a request
			// file that the launcher finds but cannot open yet: on Windows, one that is still in use when the launcher
			// opens it. The refusal changes neither the file's content nor its size or modification time, so the next
			// poll finds the same file, which it can read.
			fixture.beforePoll(polled ->
			{
				if (refused.get() != null)
					return;
				for (String name : polled.inbox())
				{
					if (name.startsWith(SessionLauncherChannel.REQUEST_PREFIX))
					{
						Path request = polled.channel.inbox().resolve(name);
						refusal.set(refuseReads(request));
						refused.set(request);
						return;
					}
				}
			});
			fixture.afterPoll(_ ->
			{
				Closeable pending = refusal.getAndSet(null);
				if (pending != null)
					pending.close();
			});

			int status = fixture.launch(new StringWriter(), "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(status, "status").isEqualTo(0);
			requireThat(refused.get(), "refused").isNotNull();
		}
	}

	/**
	 * Verifies that when the accepting launcher stops renewing its heartbeat, the launch gives up after the heartbeat
	 * timeout with status 1 and the stopped-launcher message, ending its holder.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void staleHeartbeatEndsRequest() throws IOException
	{
		try (Fixture fixture = new Fixture();
			FeedReader input = new FeedReader())
		{
			fixture.serve();
			// The launcher accepts the request, then stops before it answers
			fixture.afterPoll(polled ->
			{
				if (!polled.requests().isEmpty())
					polled.stopServing();
			});
			input.send("{\"type\":\"turn\",\"prompt\":\"task\"}");
			StringWriter errors = new StringWriter();

			int status = fixture.launch(input, errors, "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e");

			requireThat(status, "status").isEqualTo(1);
			requireThat(errors.toString(), "errors").contains(SessionLauncherChannel.stoppedLauncherMessage());
			requireThat(Duration.between(START, fixture.clock.instant()), "waited").
				isGreaterThan(SessionLauncherChannel.HEARTBEAT_TIMEOUT);
			requireThat(fixture.holders.request().closed, "closed").isTrue();
		}
	}

	/**
	 * Verifies that a launch that names a file of extra permissions passes the writes and hosts that it lists to the
	 * child's runner, and that a launch without the option passes none.
	 *
	 * @throws IOException          if the fixture cannot be written
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void requestCarriesExtraPermissions() throws IOException, InterruptedException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			Files.writeString(fixture.root.resolve("needs.json"), """
				{"format_version": 1, "writes": [%s], "hosts": ["Registry.Example"]}
				""".formatted(jsonText(fixture.cache)));

			int asking = fixture.launch(new StringWriter(), "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString(), "--extra-permissions", "needs.json");
			fixture.awaitIdle();
			Optional<String> askingFile = fixture.runner().option(ExtraPermissions.OPTION);
			ExtraPermissions passed = ExtraPermissions.read(JsonMapper.builder().build(),
				Path.of(askingFile.orElseThrow()));
			int silent = fixture.launch(new StringWriter(), "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--input", fixture.records().toString());

			requireThat(asking, "asking").isEqualTo(0);
			requireThat(silent, "silent").isEqualTo(0);
			requireThat(passed, "extraPermissions").
				isEqualTo(new ExtraPermissions(List.of(fixture.cache), Set.of(REGISTRY_HOST)));
			requireThat(fixture.starter.runners().getLast().option(ExtraPermissions.OPTION), "withoutOption").
				isEmpty();
		}
	}

	/**
	 * Verifies that a launch whose file of extra permissions is missing or invalid is refused before anything is
	 * published, with a reason that names the file and the problem.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesInvalidExtraPermissions() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			Path invalid = Files.writeString(fixture.root.resolve("invalid.json"),
				"{\"format_version\": 1, \"writes\": [\"relative/cache\"]}");
			Path missing = fixture.root.resolve("missing.json");
			for (Path file : List.of(invalid, missing))
			{
				InvalidArgumentsException refusal = expectThrows(InvalidArgumentsException.class, () ->
					fixture.launch(new StringWriter(), "--project", fixture.project.toString(), "--model", "m",
						"--effort", "e", "--extra-permissions", file.toString()));

				requireThat(refusal.getMessage(), "message").contains(file.toString());
			}
			requireThat(fixture.inbox(), "inbox").isEmpty();
			requireThat(fixture.holders.started(), "holders").isEmpty();
		}
	}

	/**
	 * Verifies that a launch that names {@code --artifact} is refused with status 2 before anything is published.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void refusesArtifact() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			StringWriter errors = new StringWriter();

			int status = fixture.launch(errors, "--project", fixture.project.toString(), "--model", "m",
				"--effort", "e", "--artifact", fixture.root.toString());

			requireThat(status, "status").isEqualTo(2);
			requireThat(errors.toString(), "errors").contains("--artifact");
			requireThat(fixture.inbox(), "inbox").isEmpty();
			requireThat(fixture.holders.started(), "holders").isEmpty();
		}
	}

	/**
	 * A writer that answers each reply that reaches the optimizer: a follow-up turn after the first reply, and the end of
	 * the session after the second.
	 */
	private static final class ReplyWriter extends Writer
	{
		/**
		 * The input that receives the answers.
		 */
		private final FeedReader input;
		/**
		 * The replies received, in order.
		 */
		final List<String> replies = new ArrayList<>();

		/**
		 * Creates a writer.
		 *
		 * @param input the input that receives the answers
		 */
		ReplyWriter(FeedReader input)
		{
			this.input = input;
		}

		@Override
		public void write(char[] buffer, int offset, int length)
		{
			String text = new String(buffer, offset, length);
			if (!text.contains("turn_completed"))
				return;
			replies.add(text);
			if (replies.size() == 1)
				input.send("{\"type\":\"turn\",\"prompt\":\"follow-up\"}");
			else
			{
				input.send("{\"type\":\"complete\"}");
				input.end();
			}
		}

		@Override
		public void flush()
		{
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * A reader whose lines a test supplies while the reader is read, as the optimizer supplies its turns.
	 */
	private static final class FeedReader extends Reader
	{
		/**
		 * The supplied text, one entry per line, with an empty entry at the end.
		 */
		private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
		/**
		 * The rest of the line being read.
		 */
		private String current = "";
		/**
		 * Indicates whether the input ended.
		 */
		private boolean ended;

		/**
		 * Supplies a line.
		 *
		 * @param line the line, without its terminator
		 */
		void send(String line)
		{
			lines.add(line + "\n");
		}

		/**
		 * Ends the input.
		 */
		void end()
		{
			lines.add("");
		}

		@Override
		public int read(char[] buffer, int offset, int length) throws IOException
		{
			if (ended)
				return -1;
			try
			{
				while (current.isEmpty())
				{
					current = lines.take();
					if (current.isEmpty())
					{
						ended = true;
						return -1;
					}
				}
			}
			catch (InterruptedException _)
			{
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while waiting for the next line");
			}
			int count = Math.min(length, current.length());
			current.getChars(0, count, buffer, offset);
			current = current.substring(count);
			return count;
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * Verifies that the optimizer's bridge streams its turns one at a time, each under its own holder that ends once
	 * the launcher consumed the turn, sends its follow-up turn only after the reply to the one before, holds the
	 * child's capacity slot itself, and carries the candidate CAT artifact as the first plugin to install as CAT.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void bridgeStreamsTurnsAfterReplies() throws IOException
	{
		try (Fixture fixture = new Fixture();
			FeedReader input = new FeedReader();
			ReplyWriter output = new ReplyWriter(input))
		{
			fixture.serve();
			Path candidate = Files.createDirectories(fixture.root.resolve("candidate/.claude-plugin")).getParent();
			Files.writeString(candidate.resolve(".claude-plugin/plugin.json"), "{}");
			Path own = Files.createDirectory(fixture.root.resolve("own"));
			Path runRoot = fixture.root.resolve("runs");
			List<Integer> repliesAtConsume = new ArrayList<>();
			// The launcher falls behind the client, so that a turn's holder could end before the launcher consumes it
			fixture.pollEvery(3);
			fixture.afterPoll(polled ->
			{
				int consumed = polled.turnsConsumed();
				for (int turn = repliesAtConsume.size(); turn < consumed; ++turn)
					repliesAtConsume.add(output.replies.size());
				for (HolderShell holder : polled.holders.started())
				{
					int turn = Integer.parseInt(holder.arguments.get(4));
					if (turn > 0 && holder.closed && turn > consumed)
						throw new AssertionError("The holder of turn " + turn + " ended before the launcher consumed it");
				}
			});
			input.send("{\"type\":\"turn\",\"prompt\":\"first\"}");
			Bridge bridge = fixture.client.bridge("claude", own);

			bridge.serve(List.of("--artifact", candidate.toString(), "--project", fixture.project.toString(),
				"--run-root", runRoot.toString(), "--keep-run", "--session-transcripts", "--model", "m", "--effort", "e",
				"--install-cat"), input, output);

			FakeRunner runner = fixture.runner();
			requireThat(runner.records(), "records").isEqualTo(List.of("{\"type\":\"turn\",\"prompt\":\"first\"}",
				"{\"type\":\"turn\",\"prompt\":\"follow-up\"}", "{\"type\":\"complete\"}"));
			// The follow-up turn reached the launcher only after the first reply reached the optimizer
			requireThat(repliesAtConsume, "repliesAtConsume").isEqualTo(List.of(0, 1, 2));
			List<HolderShell> turnHolders = fixture.holders.started().stream().
				filter(holder -> !holder.arguments.get(4).equals("0")).toList();
			requireThat(turnHolders.stream().map(holder -> holder.arguments.get(4)).toList(), "turnHolders").
				isEqualTo(List.of("1", "2", "3"));
			requireThat(turnHolders.stream().allMatch(holder -> holder.closed), "turnHoldersClosed").isTrue();
			requireThat(runner.arguments(), "arguments").contains("--capacity-held").contains("--install-cat");
			List<String> plugins = new ArrayList<>();
			for (int index = runner.arguments().indexOf("--plugin"); index >= 0;
				index = indexOf(runner.arguments(), "--plugin", index + 1))
				plugins.add(runner.arguments().get(index + 1));
			requireThat(plugins.size(), "plugins").isEqualTo(1);
			requireThat(Files.exists(Path.of(plugins.getFirst()).resolve(".claude-plugin/plugin.json")),
				"candidatePlugin").isTrue();
			requireThat(Files.isDirectory(runRoot.resolve("claude").resolve(FakeRunner.RUN_ID).resolve("artifacts")),
				"artifacts").isTrue();
		}
	}

	/**
	 * Returns the index of an element of a list at or after a position.
	 *
	 * @param list    the list
	 * @param element the element
	 * @param from    the first index to look at
	 * @return the index, or {@code -1} if the element does not occur there
	 */
	private static int indexOf(List<String> list, String element, int from)
	{
		for (int index = from; index < list.size(); ++index)
		{
			if (list.get(index).equals(element))
				return index;
		}
		return -1;
	}

	/**
	 * Verifies that an interrupt of the thread that serves the optimizer's bridge, after the launcher started the
	 * child's runner, ends the request's holder, which makes the launcher stop the child's runner and its harness.
	 *
	 * @throws IOException          if the fixture cannot be written
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void bridgeInterruptEndsHolder() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			FeedReader input = new FeedReader())
		{
			fixture.serve();
			// The optimizer interrupts the session once the launcher has consumed its first turn and started the runner
			fixture.afterPoll(polled ->
			{
				if (polled.turnsConsumed() > 0)
				{
					polled.starter.awaitRunner();
					Thread.currentThread().interrupt();
				}
			});
			boolean interrupted = serveUntilInterrupted(fixture, input);
			fixture.poll();
			fixture.awaitIdle();

			requireThat(interrupted, "interrupted").isTrue();
			requireThat(fixture.holders.request().closed, "closed").isTrue();
			requireThat(fixture.holders.started().stream().allMatch(holder -> holder.closed), "allClosed").isTrue();
			requireThat(fixture.runner().treeStopped(), "treeStopped").isTrue();
		}
	}

	/**
	 * Verifies that an interrupt of the thread that serves the optimizer's bridge before the launcher started the
	 * child's runner ends the request's holder, and that the launcher answers that it stopped the session without
	 * ever starting a runner.
	 *
	 * @throws IOException          if the fixture cannot be written
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test
	public void bridgeInterruptBeforeRunnerStarts() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture();
			FeedReader input = new FeedReader())
		{
			fixture.serve();
			// The launcher accepts the request and its first turn, but does not run the request until the test lets it
			fixture.executor.hold();
			fixture.afterPoll(polled ->
			{
				if (polled.turnsConsumed() > 0)
					Thread.currentThread().interrupt();
			});
			boolean interrupted = serveUntilInterrupted(fixture, input);
			fixture.poll();
			fixture.executor.release();
			fixture.awaitIdle();

			requireThat(interrupted, "interrupted").isTrue();
			requireThat(fixture.holders.request().closed, "closed").isTrue();
			requireThat(fixture.starter.runners(), "runners").isEmpty();
			Path log = fixture.channel.logFile("claude", fixture.harness.pid(), fixture.harness.start());
			requireThat(Files.readString(log), "log").contains(": stopped");
		}
	}

	/**
	 * Serves a bridge whose first turn is ready, until the thread is interrupted.
	 *
	 * @param fixture the fixture, whose later polls may interrupt the thread
	 * @param input   the bridge's input
	 * @return {@code true} if the bridge ended because the thread was interrupted
	 * @throws IOException if the fixture cannot be written
	 */
	private static boolean serveUntilInterrupted(Fixture fixture, FeedReader input) throws IOException
	{
		input.send("{\"type\":\"turn\",\"prompt\":\"first\"}");
		Bridge bridge = fixture.client.bridge("claude", fixture.root);
		boolean interrupted;
		try
		{
			bridge.serve(List.of("--project", fixture.project.toString(), "--run-root",
				fixture.root.resolve("runs").toString(), "--keep-run", "--model", "m", "--effort", "e"), input,
				new StringWriter());
			interrupted = false;
		}
		catch (InterruptedIOException _)
		{
			interrupted = Thread.interrupted();
		}
		fixture.afterPoll(_ ->
		{
		});
		return interrupted;
	}

	/**
	 * Verifies that the optimizer's bridge signals a refusal when no launcher serves the session, so that the optimizer
	 * does not retry a session that is refused again until the user acts, and that the refusal carries the launcher's
	 * message.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void bridgeWithoutLauncherIsRefused() throws IOException
	{
		// Requirement: R-I11
		try (Fixture fixture = new Fixture())
		{
			Files.createDirectories(fixture.channel.launchers());

			String refusal = bridgeFailure(fixture, LaunchRefusedException.class);

			requireThat(refusal, "refusal").contains(SessionLauncherChannel.noLauncherMessage(PRODUCT));
			requireThat(fixture.inbox(), "inbox").isEmpty();
			requireThat(fixture.client.refusal(), "noSpendRefusal").
				isEqualTo(Optional.of(SessionLauncherChannel.noLauncherMessage(PRODUCT)));
		}
	}

	/**
	 * Verifies that the optimizer's bridge signals a refusal when the child's runner refuses the session, carrying the
	 * runner's reason, while the no-spend check finds the launcher that serves the session.
	 *
	 * @throws IOException if the fixture cannot be written
	 */
	@Test
	public void bridgeRefusedByLauncherIsRefused() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			fixture.starter.exitWith(Status.REFUSED.exitStatus());

			String refusal = bridgeFailure(fixture, LaunchRefusedException.class);

			requireThat(refusal, "refusal").contains(FakeRunner.reason(Status.REFUSED.exitStatus()));
			requireThat(fixture.client.refusal(), "noSpendRefusal").isEmpty();
		}
	}

	/**
	 * Returns each status that ends a session for a reason that a later attempt may not meet.
	 *
	 * @return the status
	 */
	@DataProvider
	public Object[][] retryable()
	{
		return new Object[][]{{Status.CAPACITY}, {Status.FAILED}};
	}

	/**
	 * Verifies that a session that the child fails, or that finds no capacity, is not signalled as a refusal, so the
	 * optimizer handles it as before.
	 *
	 * @param answer how the request ends
	 * @throws IOException if the fixture cannot be written
	 */
	@Test(dataProvider = "retryable")
	public void bridgeFailureIsNotRefusal(Status answer) throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.serve();
			fixture.starter.exitWith(answer.exitStatus());

			String failure = bridgeFailure(fixture, IOException.class);

			requireThat(failure, "failure").contains(answer.value()).contains(FakeRunner.reason(answer.exitStatus()));
		}
	}

	/**
	 * Serves one turn through the fixture's bridge and returns the message of the exception that ends it.
	 *
	 * @param fixture  the fixture
	 * @param expected the exact type of exception that the bridge must throw
	 * @return the exception's message
	 * @throws IOException if the fixture cannot be written
	 */
	private static String bridgeFailure(Fixture fixture, Class<? extends IOException> expected) throws IOException
	{
		Bridge bridge = fixture.client.bridge("claude", fixture.root);
		try (FeedReader input = new FeedReader())
		{
			input.send("{\"type\":\"turn\",\"prompt\":\"first\"}");
			input.send("{\"type\":\"complete\"}");
			bridge.serve(List.of("--project", fixture.project.toString(), "--run-root",
				fixture.root.resolve("runs").toString(), "--keep-run", "--model", "m", "--effort", "e"), input,
				new StringWriter());
		}
		catch (IOException e)
		{
			requireThat(e.getClass().getName(), "exception").isEqualTo(expected.getName());
			return e.getMessage();
		}
		throw new AssertionError("The bridge completed");
	}
}
