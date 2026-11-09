package com.holdmyspot.cat.common.runner.test;

import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_A;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.fakes.test.FakeHarnessCli;
import com.holdmyspot.cat.common.runner.launcher.ProcessIdentity;
import com.holdmyspot.cat.common.runner.launcher.test.FakeParentPolicySource;
import com.holdmyspot.cat.common.runner.launcher.test.FakeProcessTable;
import com.holdmyspot.cat.common.runner.policy.ChildNeedsReport;
import com.holdmyspot.cat.common.runner.policy.ChildPolicy;
import com.holdmyspot.cat.common.runner.policy.HostAccess;
import com.holdmyspot.cat.common.runner.policy.ParentPolicy;
import com.holdmyspot.cat.common.runner.policy.PolicyBoundFile;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests the request server that a harness session's agent starts outside the session's sandbox.
 */
public final class RequestServerTest
{
	private static final String SESSION_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
	private static final String MODEL_HOST = "model.example";
	private static final Duration WAIT = Duration.ofSeconds(20);

	/**
	 * Verifies that the server refuses any argument, because its sandbox exception matches only its bare path.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesArguments() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			StringWriter errors = new StringWriter();
			int status = RequestServer.serve(new String[]{"--project", "elsewhere"}, fixture.host(),
				new PrintWriter(errors, true));
			requireThat(status, "status").isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			requireThat(errors.toString(), "errors").contains("takes no arguments").
				contains(RequestServer.program(fixture.plugin).toString());
		}
	}

	/**
	 * Verifies that the server refuses on a platform that runs no sandboxed session, such as native Windows.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesPlatform() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.hostRefusal = Optional.of("CAT does not start sessions on native Windows");
			requireThat(fixture.refusal(), "refusal").isEqualTo("CAT does not start sessions on native Windows\n");
		}
	}

	/**
	 * Verifies that a server that the other harness's session runs names the plugin to use.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesOtherHarness() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.running = Set.of("codex");
			requireThat(fixture.refusal(), "refusal").contains("Run the request server of CAT's Codex plugin instead");
		}
	}

	/**
	 * Verifies that a server that no harness session runs is refused, because it has no session to serve.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWithoutHarness() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.running = Set.of();
			requireThat(fixture.refusal(), "refusal").contains("no harness session runs it");
		}
	}

	/**
	 * Verifies that a server that runs inside the session's sandbox, where it cannot write its plugin's folder,
	 * refuses with the cause that the plugin gives.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesInsideSandbox() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			TemporaryDirectories.deleteRecursively(fixture.plugin);
			requireThat(fixture.refusal(), "refusal").isEqualTo("the session runs the server inside its sandbox\n");
		}
	}

	/**
	 * Verifies that a server refuses a session that its sources do not identify.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesUnidentifiedSession() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.sessionRefusal = Optional.of(new PolicyRefusal("no transcript has the session's identifier",
				"Start a new session"));
			requireThat(fixture.refusal(), "refusal").contains("no transcript has the session's identifier");
		}
	}

	/**
	 * Verifies that a server refuses a session whose commands could write its transcript, from which the server
	 * takes the session's project folder.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWritableTranscript() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.policy.allow(fixture.policy(List.of(fixture.project, fixture.transcript.getParent())));
			requireThat(fixture.refusal(), "refusal").contains(fixture.transcript.toString());
		}
	}

	/**
	 * Verifies that a server refuses a session whose commands could write the plugin that holds it, from which the
	 * server's own code and the runners that it starts are read.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesWritablePlugin() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.policy.allow(fixture.policy(List.of(fixture.project, fixture.plugin)));
			requireThat(fixture.refusal(), "refusal").contains(fixture.plugin.toString());
		}
	}

	/**
	 * Verifies that a server refuses a session whose policy it cannot read.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesUnreadablePolicy() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.policy.failReading(new IOException("the settings are malformed"));
			requireThat(fixture.refusal(), "refusal").contains("sandbox policy").contains("the settings are malformed");
		}
	}

	/**
	 * Verifies that a second server for a session that a server already serves ends without serving it.
	 *
	 * @throws IOException if the fixture cannot be created
	 */
	@Test
	public void refusesSecondServer() throws IOException
	{
		try (Fixture fixture = new Fixture())
		{
			Path directory = fixture.sessionDirectory();
			Files.createDirectories(directory);
			try (FileChannel channel = FileChannel.open(directory.resolve(RequestServer.LOCK_FILE),
				StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			     FileLock _ = channel.lock())
			{
				fixture.pause = () ->
				{
				};
				requireThat(fixture.refusal(), "refusal").contains("Another request server already serves");
			}
		}
	}

	/**
	 * Verifies that the server serves a launch that a client packs into the session's requests: the requested session
	 * starts in a run of its own, bounded by the intersection of the session's policy and what the new session needs,
	 * and the server removes that run once it has answered, leaving the client the response.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servesWithinBound() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<List<String>> started = new AtomicReference<>();
			AtomicReference<List<String>> copied = new AtomicReference<>();
			AtomicReference<ChildPolicy> bounds = new AtomicReference<>();
			fixture.child = arguments ->
			{
				started.set(arguments);
				bounds.set(PolicyBoundFile.read(fixture.json, Path.of(option(arguments, PolicyBoundFile.OPTION))));
				Path copy = Path.of(option(arguments, "--project"));
				try (Stream<Path> paths = Files.walk(copy))
				{
					copied.set(paths.map(path -> copy.relativize(path).toString().replace('\\', '/')).sorted().
						toList());
				}
				Files.writeString(Path.of(option(arguments, "--output")), "{\"type\":\"turn_completed\"}\n");
				Files.writeString(Path.of(option(arguments, "--receipt")), "{}");
				FakeHarnessCli process = new FakeHarnessCli(_ ->
				{
				});
				process.closeOutputThenExitWhenAwaited();
				return process;
			};
			try (Fixture.Running server = fixture.start())
			{
				ByteArrayOutputStream output = new ByteArrayOutputStream();
				StringWriter errors = new StringWriter();
				int status = SessionRequestForwarder.forward("claude", new String[]{"--project",
						fixture.project.toString(), "--model", "haiku", "--effort", "low"},
					new StringReader("{\"type\":\"turn\",\"prompt\":\"hello\"}\n{\"type\":\"complete\"}\n"),
					server.requests(), Thread::yield, output, new PrintWriter(errors, true));
				requireThat(status, "status").withContext(errors, "errors").isEqualTo(0);

				List<String> arguments = started.get();
				Path runRoot = Path.of(option(arguments, "--run-root"));
				requireThat(runRoot.startsWith(fixture.sessionDirectory()), "inSessionDirectory").isTrue();
				ChildPolicy bound = bounds.get();
				requireThat(bound.writeRoots(), "writeRoots").isEqualTo(List.of(runRoot));
				requireThat(bound.hosts(), "hosts").isEqualTo(Set.of(MODEL_HOST));
				requireThat(awaitAbsent(runRoot), "runRootRemains").isFalse();
				requireThat(copied.get(), "copied").contains("README.md").contains(".cat/sessions/" + SESSION_ID).
					doesNotContain(".cat/sessions/" + SESSION_ID + "/" + RequestServer.REQUESTS).
					doesNotContain(".cat/sessions/" + SESSION_ID + "/responses").
					doesNotContain(".cat/sessions/" + SESSION_ID + "/" + RequestServer.LOCK_FILE);
				fixture.processes.end(fixture.harness);
				requireThat(server.end(), "exit").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that a server that a thread of the session runs bounds each request by that thread's policy, not the
	 * session's: a thread that may not reach the model's host has its request refused, naming the entry to add.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void boundsByAskingThread() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.askingThread = Optional.of("thread-1");
			fixture.policy.allowThread("thread-1", new ParentPolicy(true, List.of(fixture.project), List.of(),
				List.of(), List.of(), HostAccess.listed(Set.of()), false, List.of()));
			fixture.child = _ ->
			{
				throw new AssertionError("The server started a session that the thread's policy refuses");
			};
			try (Fixture.Running server = fixture.start())
			{
				JsonNode response = server.request(fixture.manifest());
				requireThat(response.path("status").asString(), "status").withContext(response, "response").
					isEqualTo("refused");
				requireThat(response.path("reason").asString(), "reason").contains(MODEL_HOST);
			}
		}
	}

	/**
	 * Verifies that a launch with {@code --conversation} holds a conversation through the server: the client sends
	 * the asking session's turn once the server has started the session, writes the session's reply and the turn's
	 * end to the conversation, and ends the session when the asking session ends the conversation.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void conversesThroughServer() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			fixture.child = arguments ->
			{
				Path output = Path.of(option(arguments, "--output"));
				Path receipt = Path.of(option(arguments, "--receipt"));
				Files.writeString(output, "");
				FakeHarnessCli process = new FakeHarnessCli(line ->
				{
					try
					{
						String type = fixture.json.readTree(line).path("type").asString();
						if (type.equals("turn"))
						{
							Files.writeString(output, "{\"type\":\"turn_completed\",\"assistant_messages\":[\"reply\"]}\n",
								StandardOpenOption.APPEND);
						}
						else if (type.equals("complete"))
						{
							Files.writeString(output, "{\"type\":\"completed\"}\n", StandardOpenOption.APPEND);
							Files.writeString(receipt, "{}");
							child.get().exit();
						}
					}
					catch (IOException e)
					{
						throw new UncheckedIOException(e);
					}
				});
				child.set(process);
				return process;
			};
			Path folder = fixture.root.resolve("conversation");
			Path toChild = folder.resolve("to-child.jsonl");
			Path fromChild = folder.resolve("from-child.jsonl");
			AtomicInteger sent = new AtomicInteger();
			try (Fixture.Running server = fixture.start())
			{
				StringWriter errors = new StringWriter();
				long deadline = System.nanoTime() + WAIT.toNanos();
				int status = SessionRequestForwarder.forward(SessionRequestForwarder.Route.SERVER, "claude",
					new String[]{"--project", fixture.project.toString(), "--model", "haiku", "--effort", "low",
						"--conversation", folder.toString()}, new StringReader(""), server.requests(),
					fixture.root.resolve("runs"), () ->
					{
						if (System.nanoTime() > deadline)
							throw new IOException("The conversation did not end");
						// The asking session sends its turn at once, and ends the conversation once the turn has ended
						if (sent.get() == 0)
						{
							Files.writeString(toChild, "{\"turn\": \"hello\"}\n", StandardOpenOption.CREATE,
								StandardOpenOption.APPEND);
							sent.incrementAndGet();
						}
						else if (sent.get() == 1 && Files.readString(fromChild).contains("\"turn_end\""))
						{
							Files.writeString(toChild, "{\"end\": true}\n", StandardOpenOption.APPEND);
							sent.incrementAndGet();
						}
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(errors, true));

				requireThat(status, "status").withContext(errors, "errors").isEqualTo(0);
				List<String> lines = Files.readAllLines(fromChild);
				requireThat(lines, "fromChild").contains("{\"turn_end\":1,\"status\":\"completed\"}");
				requireThat(lines.getLast(), "lastLine").isEqualTo("{\"ended\":\"completed\"}");
				requireThat(child.get().requests(), "records").isEqualTo(List.of(
					"{\"type\":\"turn\",\"prompt\":\"hello\"}", "{\"type\":\"complete\"}"));
			}
		}
	}

	/**
	 * Verifies that a CAT command that starts sessions, such as the prompt optimizer, has its session served by the
	 * server outside a run: the command receives the session's records, finds the session's kept run in its own run
	 * root, and removes the response once it has copied the run, while the server keeps neither the request nor a run
	 * of its own, so the request is served once.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servesCommandSessionAndCopiesKeptRun() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			FileLocks fileLocks = new FileLocks();
			fixture.child = arguments ->
			{
				HarnessRun run = new HarnessRunManager(fileLocks, Path.of(option(arguments, "--run-root")),
					Clock.systemUTC(), Yielding.fileReplacer()).create("claude");
				Files.writeString(run.directory().resolve("result.txt"), "result");
				Path output = Path.of(option(arguments, "--output"));
				Files.writeString(output, fixture.json.createObjectNode().put("type", "started").
					put("run_id", run.id()) + "\n");
				AtomicReference<FakeHarnessCli> process = new AtomicReference<>();
				process.set(new FakeHarnessCli(line ->
				{
					try
					{
						if (fixture.json.readTree(line).path("type").asString().equals("complete"))
						{
							Files.writeString(output, fixture.json.createObjectNode().put("type", "completed").
								put("run_id", run.id()) + "\n", StandardOpenOption.APPEND);
							HarnessRunReceipt.write(fixture.json, Yielding.fileReplacer(),
								Path.of(option(arguments, "--receipt")), run, output, List.of());
							process.get().exit();
						}
					}
					catch (IOException e)
					{
						throw new UncheckedIOException(e);
					}
				}));
				return process.get();
			};
			Path runRoot = fixture.root.resolve("optimizer-runs");
			Path ownArtifact = Files.createDirectories(fixture.root.resolve("artifact"));
			try (Fixture.Running server = fixture.start())
			{
				StringWriter output = new StringWriter();
				long deadline = System.nanoTime() + WAIT.toNanos();
				SessionRequestForwarder.serve(SessionRequestForwarder.Route.SERVER, "The prompt optimizer", "claude",
					ownArtifact, List.of("--project", fixture.project.toString(), "--run-root", runRoot.toString(),
						"--keep-run", "--model", "haiku", "--effort", "low"),
					new StringReader("{\"type\":\"turn\",\"prompt\":\"hello\"}\n{\"type\":\"complete\"}\n"), output,
					server.requests(), () ->
					{
						if (System.nanoTime() > deadline)
							throw new IOException("The served session did not end");
						Thread.yield();
					});

				String runId = fixture.json.readTree(output.toString().lines().findFirst().orElseThrow()).
					path("run_id").asString();
				Path kept = runRoot.resolve("claude").resolve(runId);
				requireThat(Files.readString(kept.resolve("result.txt")), "result").isEqualTo("result");
				try (Stream<Path> responses = Files.list(fixture.sessionDirectory().resolve("responses")))
				{
					requireThat(responses.toList(), "responses").isEmpty();
				}
				try (Stream<Path> requests = Files.list(server.requests()))
				{
					requireThat(requests.filter(path -> path.toString().endsWith(".tar")).toList(),
						"requests").isEmpty();
				}
				requireThat(awaitAbsent(fixture.sessionDirectory().resolve("request-runs").resolve(runId)),
					"serverRunRemains").isFalse();
			}
		}
	}

	/**
	 * Verifies that the server applies a request's own session requests and depth to the session that it starts, so
	 * that the top-level session's requests have no caller limit.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void appliesRequestedSessionRequests() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<List<String>> started = new AtomicReference<>();
			fixture.child = arguments ->
			{
				started.set(arguments);
				Files.writeString(Path.of(option(arguments, "--receipt")), "{}");
				FakeHarnessCli process = new FakeHarnessCli(_ ->
				{
				});
				process.closeOutputThenExitWhenAwaited();
				return process;
			};
			try (Fixture.Running server = fixture.start())
			{
				ObjectNode manifest = fixture.manifest().put("session_requests", 3).put("request_depth", 2).
					put("max_concurrency", 4);
				JsonNode response = server.request(manifest);
				requireThat(response.path("status").asString(), "status").withContext(response, "response").
					isEqualTo("completed");
				requireThat(option(started.get(), "--session-requests"), "sessionRequests").isEqualTo("3");
				requireThat(option(started.get(), "--request-depth"), "requestDepth").isEqualTo("2");
				requireThat(option(started.get(), "--max-concurrency"), "maxConcurrency").isEqualTo("4");
			}
		}
	}

	/**
	 * Verifies that the server refuses a request that carries a bound or another option that only CAT's own starters
	 * pass, without starting a session.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void refusesBoundInRequest() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			fixture.child = _ ->
			{
				throw new AssertionError("The server started a session for a refused request");
			};
			try (Fixture.Running server = fixture.start())
			{
				JsonNode response = server.request(fixture.manifest().put("policy_bound", "bound.json"));
				requireThat(response.path("status").asString(), "status").isEqualTo("refused");
				requireThat(response.path("reason").asString(), "reason").contains("policy_bound");
			}
		}
	}

	/**
	 * Verifies that the server ends once the session's harness process ends, stops the session that it started with
	 * its process tree, and answers its request with the reason {@code ended}.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void endsWithSession() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			fixture.child = _ ->
			{
				FakeHarnessCli process = new FakeHarnessCli(_ ->
				{
				});
				child.set(process);
				fixture.processes.end(fixture.harness);
				return process;
			};
			try (Fixture.Running server = fixture.start())
			{
				JsonNode response = server.request(fixture.manifest());
				requireThat(response.path("status").asString(), "status").isEqualTo("failed");
				requireThat(response.path("reason").asString(), "reason").isEqualTo("ended");
				requireThat(server.end(), "exit").isEqualTo(0);
				requireThat(child.get().isAlive(), "childAlive").isFalse();
			}
		}
	}

	/**
	 * Waits, for a bounded time, until the server removes a path once it has answered the request that the path
	 * belongs to.
	 *
	 * @param path the path
	 * @return {@code true} if the path still exists when the wait ends
	 */
	private static boolean awaitAbsent(Path path)
	{
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (Files.exists(path) && System.nanoTime() < deadline)
			Thread.yield();
		return Files.exists(path);
	}

	/**
	 * Returns the value of an option.
	 *
	 * @param arguments the options
	 * @param name      the option's name
	 * @return its value
	 */
	private static String option(List<String> arguments, String name)
	{
		int index = arguments.indexOf(name);
		requireThat(index, "index").withContext(arguments, "arguments").isNotNegative();
		return arguments.get(index + 1);
	}

	/**
	 * Starts a requested session's runner in a test.
	 */
	@FunctionalInterface
	private interface Child
	{
		/**
		 * Starts the runner.
		 *
		 * @param arguments the runner's options
		 * @return the runner's process
		 * @throws IOException if the runner cannot start
		 */
		Process start(List<String> arguments) throws IOException;
	}

	/**
	 * A session that runs a request server, with its project, transcript, plugin and harness process.
	 */
	private static final class Fixture implements AutoCloseable
	{
		private final TemporaryDirectories cleanup = new TemporaryDirectories();
		private final JsonMapper json = JsonMapper.builder().build();
		private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		private final Path root;
		private final Path project;
		private final Path home;
		private final Path plugin;
		private final Path transcript;
		private final FakeProcessTable processes = new FakeProcessTable();
		private final ProcessIdentity harness;
		private final FakeParentPolicySource policy;
		private Optional<String> hostRefusal = Optional.empty();
		private Set<String> running = Set.of("claude");
		private Optional<PolicyRefusal> sessionRefusal = Optional.empty();
		private Optional<String> askingThread = Optional.empty();
		private Pause pause = Thread::yield;
		private Child child = _ ->
		{
			throw new AssertionError("No session was expected");
		};

		/**
		 * Creates the session.
		 *
		 * @throws IOException if its files cannot be created
		 */
		private Fixture() throws IOException
		{
			root = cleanup.add(Files.createTempDirectory("request-server-")).toRealPath();
			project = Files.createDirectories(root.resolve("project/.cat")).getParent();
			Files.writeString(project.resolve("README.md"), "project\n");
			home = Files.createDirectories(root.resolve("home"));
			plugin = Files.createDirectories(root.resolve("plugin"));
			transcript = Files.writeString(Files.createDirectories(root.resolve("transcripts")).
				resolve(SESSION_ID + ".jsonl"), "{}\n");
			harness = processes.start(4242);
			policy = new FakeParentPolicySource("Claude Code", policy(List.of(project)));
		}

		/**
		 * Returns a sandboxed policy that may reach the model's host.
		 *
		 * @param writeRoots the folders that the session may write
		 * @return the policy
		 */
		private ParentPolicy policy(List<Path> writeRoots)
		{
			return new ParentPolicy(true, writeRoots, List.of(), List.of(), List.of(),
				HostAccess.listed(Set.of(MODEL_HOST)), false, List.of());
		}

		/**
		 * Returns the session's CAT session directory.
		 *
		 * @return the directory
		 */
		private Path sessionDirectory()
		{
			return project.resolve(".cat/sessions").resolve(SESSION_ID);
		}

		/**
		 * Returns what the plugin gives the server.
		 *
		 * @return the host
		 */
		private RequestServer.Host host()
		{
			Map<String, String> environment = Map.of(HARNESS_A.sessionVariable(), SESSION_ID, "HOME", home.toString(),
				"USERPROFILE", home.toString());
			RequestedSessionStarter starter = (_, arguments, _) ->
			{
				if (arguments.getFirst().equals(ChildNeedsReport.OPTION))
				{
					new ChildNeedsReport(List.of(), Set.of(MODEL_HOST), List.of()).write(json, Path.of(arguments.get(1)));
					FakeHarnessCli process = new FakeHarnessCli(_ ->
					{
					});
					process.closeOutputThenExitWhenAwaited();
					return process;
				}
				return child.start(arguments);
			};
			return new RequestServer.Host("claude", running, hostRefusal, plugin,
				() -> "the session runs the server inside its sandbox", () ->
			{
				if (sessionRefusal.isPresent())
					throw sessionRefusal.get();
				return new RequestServer.Session(policy, transcript, project, askingThread, harness);
			}, directory -> ToolScope.forSession(directory, environment, List.of(HARNESS_A), Yielding.waiting(),
				GitProjects.IGNORING), starter, processes, pause);
		}

		/**
		 * Runs the server, expecting it to refuse.
		 *
		 * @return what it wrote to its errors
		 * @throws IOException if the server fails
		 */
		private String refusal() throws IOException
		{
			StringWriter errors = new StringWriter();
			int status = RequestServer.serve(new String[0], host(), new PrintWriter(errors, true));
			requireThat(status, "status").withContext(errors, "errors").
				isEqualTo(HarnessRunnerOptions.REFUSED_LAUNCH_STATUS);
			return errors.toString().replace(System.lineSeparator(), "\n");
		}

		/**
		 * Returns a valid request manifest, which names the entries that {@link Running#request(ObjectNode)} packs.
		 *
		 * @return the manifest
		 */
		private ObjectNode manifest()
		{
			ObjectNode request = json.createObjectNode().put("format_version", 3).put("harness", "claude").
				put("project", "project").put("model", "haiku").put("effort", "low");
			request.putArray("prompts").add("prompts/0");
			request.putArray("plugins");
			return request;
		}

		/**
		 * Starts the server on a thread of its own, once its session's directories exist.
		 *
		 * @return the running server
		 * @throws IOException if the server does not serve in time
		 */
		private Running start() throws IOException
		{
			Future<Integer> exit = executor.submit(() -> RequestServer.serve(new String[0], host(),
				new PrintWriter(new StringWriter(), true)));
			Path requests = sessionDirectory().resolve(RequestServer.REQUESTS);
			long deadline = System.nanoTime() + WAIT.toNanos();
			while (!Files.isDirectory(sessionDirectory().resolve("responses")))
			{
				if (exit.isDone())
					throw new AssertionError("The server ended: " + result(exit));
				if (System.nanoTime() > deadline)
					throw new AssertionError("The server created no request directories");
				Thread.onSpinWait();
			}
			return new Running(exit, requests);
		}

		/**
		 * Returns a finished server's exit status.
		 *
		 * @param exit the server's result
		 * @return its exit status, or its failure
		 */
		private static String result(Future<Integer> exit)
		{
			try
			{
				return String.valueOf(exit.get());
			}
			catch (InterruptedException | ExecutionException e)
			{
				return e.toString();
			}
		}

		@Override
		public void close() throws IOException
		{
			processes.end(harness);
			executor.close();
			cleanup.close();
		}

		/**
		 * A server that runs on a thread of the fixture.
		 *
		 * @param exit     the server's exit status
		 * @param requests the directory into which the session's commands write their requests
		 */
		private record Running(Future<Integer> exit, Path requests) implements AutoCloseable
		{
			/**
			 * Writes a request archive that holds a manifest, a project and one prompt, and waits for its response.
			 *
			 * @param manifest the manifest
			 * @return the response
			 * @throws IOException if the request cannot be written or the response does not arrive in time
			 */
			private JsonNode request(ObjectNode manifest) throws IOException
			{
				JsonMapper json = JsonMapper.builder().build();
				String id = "0b7c8f3e-1d2a-4c5b-9e6f-7a8b9c0d1e2f";
				Path staging = requests.resolveSibling("staging.tar");
				try (OutputStream bytes = Files.newOutputStream(staging);
				     TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
				{
					entry(archive, "request.json", json.writeValueAsBytes(manifest));
					entry(archive, "project/", new byte[0]);
					entry(archive, "project/marker", "original".getBytes(StandardCharsets.UTF_8));
					entry(archive, "prompts/0", "hello".getBytes(StandardCharsets.UTF_8));
				}
				Files.move(staging, requests.resolve(id + ".tar"));
				Path response = requests.resolveSibling("responses").resolve(id).resolve("response.json");
				long deadline = System.nanoTime() + WAIT.toNanos();
				while (!Files.isRegularFile(response))
				{
					if (System.nanoTime() > deadline)
						throw new AssertionError("The server did not answer " + id);
					Thread.onSpinWait();
				}
				return json.readTree(Files.readString(response));
			}

			/**
			 * Adds a file or empty directory to a test archive.
			 *
			 * @param archive the archive
			 * @param name    the entry name
			 * @param content its bytes
			 * @throws IOException if writing fails
			 */
			private static void entry(TarArchiveOutputStream archive, String name, byte[] content) throws IOException
			{
				TarArchiveEntry entry = new TarArchiveEntry(name, true);
				entry.setSize(content.length);
				archive.putArchiveEntry(entry);
				archive.write(content);
				archive.closeArchiveEntry();
			}

			/**
			 * Waits for the server to end, after its session's harness process has ended.
			 *
			 * @return the server's exit status
			 * @throws InterruptedException if the test is interrupted
			 */
			private int end() throws InterruptedException
			{
				try
				{
					return exit.get(WAIT.toSeconds(), TimeUnit.SECONDS);
				}
				catch (ExecutionException | TimeoutException e)
				{
					throw new AssertionError(e);
				}
			}

			@Override
			public void close()
			{
				exit.cancel(true);
			}
		}
	}
}
