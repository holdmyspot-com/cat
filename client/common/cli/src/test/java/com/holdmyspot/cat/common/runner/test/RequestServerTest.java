package com.holdmyspot.cat.common.runner.test;

import static com.holdmyspot.cat.common.test.SessionHarnesses.HARNESS_A;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.ToolScope;
import com.holdmyspot.cat.common.fakes.test.GitProjects;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.test.ExitedProcess;
import com.holdmyspot.cat.common.git.Git;
import com.holdmyspot.cat.common.optimizer.workspace.GitWorktreeRegistrar;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRefusedException;
import com.holdmyspot.cat.common.optimizer.workspace.WorktreeRegistrar;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.RequestServer;
import com.holdmyspot.cat.common.runner.RequestedSessionStarter;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.WorktreeRequest;
import com.holdmyspot.cat.common.runner.WorktreeRequestClient;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
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
				FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child));
				child.set(process);
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
	 * Verifies that a conversation of a launch that keeps its run ends only once the run is copied into the launch's
	 * run root: the asking session that reads the {@code ended} line finds the kept run, including its last file,
	 * already in place.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException   if the watcher of the conversation fails
	 * @throws TimeoutException     if the conversation does not end
	 */
	@Test(timeOut = 60_000)
	public void conversationEndsAfterKeptRunIsCopied() throws IOException, InterruptedException,
		ExecutionException, TimeoutException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedRun> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path folder = fixture.root.resolve("conversation");
			Path toChild = folder.resolve("to-child.jsonl");
			Path fromChild = folder.resolve("from-child.jsonl");
			Path runs = fixture.root.resolve("runs");
			String lastFile = "artifacts/many/file-399";
			AtomicBoolean endSent = new AtomicBoolean();
			try (Fixture.Running server = fixture.start())
			{
				// The asking session reads the run root as soon as it reads the line that ends the conversation
				Future<Boolean> keptWhenEnded = fixture.executor.submit(() ->
				{
					long deadline = System.nanoTime() + WAIT.toNanos();
					while (!(Files.isRegularFile(fromChild) && Files.readString(fromChild).contains("\"ended\"")))
					{
						if (System.nanoTime() > deadline)
							throw new IOException("The conversation did not end");
						Thread.onSpinWait();
					}
					Path claude = runs.resolve("claude");
					if (!Files.isDirectory(claude))
						return false;
					try (Stream<Path> kept = Files.list(claude))
					{
						return kept.anyMatch(run -> Files.isRegularFile(run.resolve("run.json")) &&
							Files.isRegularFile(run.resolve(lastFile)));
					}
				});
				StringWriter errors = new StringWriter();
				long deadline = System.nanoTime() + WAIT.toNanos();
				int status = SessionRequestForwarder.forward(SessionRequestForwarder.Route.SERVER, "claude",
					new String[]{"--project", fixture.project.toString(), "--run-root", runs.toString(),
						"--keep-run", "--model", "haiku", "--effort", "low", "--conversation", folder.toString()},
					new StringReader(""), server.requests(), runs, () ->
					{
						if (System.nanoTime() > deadline)
							throw new IOException("The conversation did not end");
						// Once the session runs, its run holds many files, so that copying it takes a while
						if (!served.isEmpty() && !endSent.get())
						{
							Path many = Files.createDirectories(served.getFirst().run().directory().
								resolve("artifacts/many"));
							for (int i = 0; i < 400; ++i)
								Files.writeString(many.resolve("file-" + i), "content");
							Files.writeString(toChild, "{\"end\": true}\n", StandardOpenOption.CREATE,
								StandardOpenOption.APPEND);
							endSent.set(true);
						}
						Thread.yield();
					}, OutputStream.nullOutputStream(), new PrintWriter(errors, true));

				requireThat(status, "status").withContext(errors, "errors").isEqualTo(0);
				requireThat(keptWhenEnded.get(WAIT.toSeconds(), TimeUnit.SECONDS), "keptRunInPlaceWhenEnded").isTrue();
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
			List<ServedRun> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path runRoot = fixture.root.resolve("optimizer-runs");
			try (Fixture.Running server = fixture.start())
			{
				String runId = serve(fixture, server, runRoot);
				Path kept = runRoot.resolve("claude").resolve(runId);
				requireThat(Files.readString(kept.resolve("result.txt")), "result").isEqualTo("1");
				try (Stream<Path> responses = Files.list(fixture.sessionDirectory().resolve("responses")))
				{
					requireThat(responses.toList(), "responses").isEmpty();
				}
				try (Stream<Path> requests = Files.list(server.requests()))
				{
					requireThat(requests.filter(path -> path.toString().endsWith(".tar")).toList(),
						"requests").isEmpty();
				}
				requireThat(awaitAbsent(served.getFirst().runRoot()), "serverRunRemains").isFalse();
			}
		}
	}

	/**
	 * Verifies that two sessions that the server serves one after the other build their run at the same paths, in
	 * the one slot of the session's server, so that the paths that the harness puts into its instructions repeat from
	 * one session to the next.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servedSessionsShareRunPaths() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedRun> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path runRoot = fixture.root.resolve("optimizer-runs");
			try (Fixture.Running server = fixture.start())
			{
				serve(fixture, server, runRoot);
				serve(fixture, server, runRoot);
			}
			requireThat(served, "served").size().isEqualTo(2);
			ServedRun first = served.getFirst();
			ServedRun second = served.getLast();
			requireThat(second.runRoot(), "runRoot").isEqualTo(first.runRoot());
			requireThat(second.run().directory(), "directory").isEqualTo(first.run().directory());
			requireThat(second.run().directory().resolve("home"), "home").
				isEqualTo(first.run().directory().resolve("home"));
			requireThat(second.run().directory().resolve("project"), "project").
				isEqualTo(first.run().directory().resolve("project"));
			requireThat(first.runRoot(), "runRoot").isEqualTo(fixture.sessionDirectory().resolve("slots").
				resolve("d1"));
		}
	}

	/**
	 * Verifies that a session that the server serves finds nothing of the previous session's files in its slot.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servedSessionFindsEmptySlot() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedRun> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path runRoot = fixture.root.resolve("optimizer-runs");
			try (Fixture.Running server = fixture.start())
			{
				serve(fixture, server, runRoot);
				serve(fixture, server, runRoot);
			}
			requireThat(served, "served").size().isEqualTo(2);
			requireThat(served.getLast().entriesAtStart(), "entriesAtStart").isEmpty();
		}
	}

	/**
	 * Verifies that a request that names the held-out part runs in a slot of the held-out part, and one that does not
	 * runs in a slot of the development part.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servedHeldOutSessionTakesHeldOutSlot() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedRun> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path runRoot = fixture.root.resolve("optimizer-runs");
			try (Fixture.Running server = fixture.start())
			{
				serve(fixture, server, runRoot, "--held-out");
				serve(fixture, server, runRoot);
			}
			Path slots = fixture.sessionDirectory().resolve("slots");
			requireThat(served, "served").size().isEqualTo(2);
			requireThat(served.getFirst().runRoot(), "heldOut").isEqualTo(slots.resolve("h1"));
			requireThat(served.getLast().runRoot(), "development").isEqualTo(slots.resolve("d1"));
		}
	}

	/**
	 * Verifies that the kept run that the command receives survives the next session's use of the slot: each session's
	 * kept run is in the command's run root under an identifier of its own, with the files of its own session.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void keptRunsOfServedSessionsSurviveSlotReuse() throws IOException, InterruptedException
	{
		try (Fixture fixture = new Fixture())
		{
			List<ServedRun> served = new CopyOnWriteArrayList<>();
			fixture.child = slotChild(fixture, served);
			Path runRoot = fixture.root.resolve("optimizer-runs");
			try (Fixture.Running server = fixture.start())
			{
				String first = serve(fixture, server, runRoot);
				String second = serve(fixture, server, runRoot);
				requireThat(second, "secondRunId").isNotEqualTo(first);
				requireThat(Files.readString(runRoot.resolve("claude").resolve(first).resolve("result.txt")),
					"first").isEqualTo("1");
				requireThat(Files.readString(runRoot.resolve("claude").resolve(second).resolve("result.txt")),
					"second").isEqualTo("2");
				requireThat(Files.readString(runRoot.resolve("claude").resolve(first).resolve("run.json")),
					"manifest").contains("\"id\": \"" + first + "\"");
			}
		}
	}

	/**
	 * Returns a request handler that ends the captured runner when it receives a complete record.
	 *
	 * @param json reads the runner's input record
	 * @param process receives the fake runner before the handler is called
	 * @return the request handler
	 */
	private static Consumer<String> exitOnCompleteRecord(JsonMapper json, AtomicReference<FakeHarnessCli> process)
	{
		return line ->
		{
			if (json.readTree(line).path("type").asString().equals("complete"))
				process.get().exit();
		};
	}

	/**
	 * Serves one session of a command through the server, as the prompt optimizer does.
	 *
	 * @param fixture the session that runs the server
	 * @param server  the running server
	 * @param runRoot the command's run root
	 * @param extra   further options of the launch
	 * @return the identifier of the session's kept run, as the command's records name it
	 * @throws IOException if the session does not complete
	 */
	private static String serve(Fixture fixture, Fixture.Running server, Path runRoot, String... extra)
		throws IOException
	{
		Path ownArtifact = Files.createDirectories(fixture.root.resolve("artifact"));
		StringWriter output = new StringWriter();
		long deadline = System.nanoTime() + WAIT.toNanos();
		List<String> arguments = new ArrayList<>(List.of("--project", fixture.project.toString(), "--run-root",
			runRoot.toString(), "--keep-run", "--model", "haiku", "--effort", "low"));
		arguments.addAll(List.of(extra));
		SessionRequestForwarder.serve(SessionRequestForwarder.Route.SERVER, "The prompt optimizer", "claude",
			ownArtifact, arguments,
			new StringReader("{\"type\":\"turn\",\"prompt\":\"hello\"}\n{\"type\":\"complete\"}\n"), output,
			server.requests(), () ->
			{
				if (System.nanoTime() > deadline)
					throw new IOException("The served session did not end");
				Thread.yield();
			});
		return fixture.json.readTree(output.toString().lines().findFirst().orElseThrow()).path("run_id").asString();
	}

	/**
	 * Returns a double of a requested runner that builds its run in the run root and under the run identifier that the
	 * server names, as the real runner does, and completes the session when its records end.
	 *
	 * @param fixture the session that runs the server
	 * @param served  receives what each session finds in its slot and builds there
	 * @return the double
	 */
	private static Child slotChild(Fixture fixture, List<ServedRun> served)
	{
		FileLocks fileLocks = new FileLocks();
		return arguments ->
		{
			Path runRoot = Path.of(option(arguments, "--run-root"));
			List<String> entries;
			try (Stream<Path> paths = Files.list(runRoot))
			{
				// The server prepares the session's policy in the slot, in entries whose names start with a dot
				entries = paths.map(path -> path.getFileName().toString()).filter(name -> !name.startsWith(".")).
					toList();
			}
			HarnessRun run = new HarnessRunManager(fileLocks, runRoot, Clock.systemUTC(), Yielding.fileReplacer()).
				withFixedRunId(option(arguments, "--run-id")).create("claude");
			served.add(new ServedRun(runRoot, run, entries));
			Files.writeString(run.directory().resolve("result.txt"), String.valueOf(served.size()));
			Files.writeString(run.directory().resolve("left-behind"), "left behind");
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
	}

	/**
	 * What a served session finds in its slot, and the run that it builds there.
	 *
	 * @param runRoot        the run root that the session received
	 * @param run            the run that it built
	 * @param entriesAtStart the names of the entries of the run root when the session started
	 */
	private record ServedRun(Path runRoot, HarnessRun run, List<String> entriesAtStart)
	{
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
			AtomicReference<FakeHarnessCli> child = new AtomicReference<>();
			fixture.child = arguments ->
			{
				started.set(arguments);
				Files.writeString(Path.of(option(arguments, "--receipt")), "{}");
				FakeHarnessCli process = new FakeHarnessCli(exitOnCompleteRecord(fixture.json, child));
				child.set(process);
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
	 * Verifies that the server carries out each worktree operation of a campaign of its session in the session's
	 * project, outside the session's sandbox: add, recreate after sandboxed code deleted the worktree, repair after the
	 * registration came to name another location of the project, and remove of an existing and of a missing worktree.
	 * The client carries out none of them itself, sends no request for links that are intact, and removes each
	 * response that it read.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void servesWorktreeOperations() throws IOException, InterruptedException
	{
		// Requirement: R-W17
		try (Fixture fixture = new Fixture())
		{
			Git git = fixture.repository();
			fixture.policy.allow(fixture.protectingGit());
			Path campaign = Files.createDirectories(fixture.sessionDirectory().resolve("optimizer/c1"));
			Path worktree = campaign.resolve("worktree");
			try (Fixture.Running server = fixture.start())
			{
				WorktreeRegistrar client = server.client();

				client.add(fixture.project, campaign);
				requireThat(worktree.resolve("README.md"), "added").isRegularFile();
				requireThat(TestRepositories.worktreeCount(git), "addedCount").isEqualTo(2L);

				TemporaryDirectories.deleteRecursively(worktree);
				client.recreate(fixture.project, campaign);
				requireThat(worktree.resolve("README.md"), "recreated").isRegularFile();
				requireThat(TestRepositories.worktreeCount(git), "recreatedCount").isEqualTo(2L);

				requireThat(client.repair(fixture.project, campaign), "intact").isTrue();
				Path backlink = fixture.project.resolve(".git/worktrees/worktree/gitdir");
				Files.writeString(backlink, "/moved/project/.cat/sessions/" + SESSION_ID +
					"/optimizer/c1/worktree/.git\n", StandardCharsets.UTF_8);
				requireThat(GitWorktreeRegistrar.isLinked(fixture.project, campaign), "brokenLinked").isFalse();
				requireThat(client.repair(fixture.project, campaign), "repaired").isTrue();
				requireThat(GitWorktreeRegistrar.isLinked(fixture.project, campaign), "repairedLinked").isTrue();

				client.remove(fixture.project, campaign);
				requireThat(Files.exists(worktree, LinkOption.NOFOLLOW_LINKS), "removed").isFalse();
				requireThat(TestRepositories.worktreeCount(git), "removedCount").isEqualTo(1L);
				client.remove(fixture.project, campaign);
				requireThat(TestRepositories.worktreeCount(git), "missingCount").isEqualTo(1L);

				try (Stream<Path> responses = Files.list(fixture.sessionDirectory().resolve("responses")))
				{
					requireThat(responses.toList(), "responses").isEmpty();
				}
			}
		}
	}

	/**
	 * Verifies that the server refuses, with the reason and having changed nothing, a worktree request for a campaign
	 * that is not its session's, one whose campaign directory or worktree is a link, an add over an existing worktree,
	 * and any request of a session whose sandbox lets its commands write the repository's configuration.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void refusesWorktreeRequests() throws IOException, InterruptedException
	{
		// Requirement: R-W17
		try (Fixture fixture = new Fixture())
		{
			Git git = fixture.repository();
			fixture.policy.allow(fixture.protectingGit());
			Path optimizer = Files.createDirectories(fixture.sessionDirectory().resolve("optimizer"));
			Path other = Files.createDirectories(fixture.project.resolve(".cat/sessions/" +
				"1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed/optimizer/c2"));
			Path target = Files.createDirectories(fixture.root.resolve("elsewhere"));
			Path linked = Files.createSymbolicLink(optimizer.resolve("c3"), target);
			Path campaign = Files.createDirectories(optimizer.resolve("c4"));
			Files.createSymbolicLink(Files.createDirectories(optimizer.resolve("c5")).resolve("worktree"), target);
			try (Fixture.Running server = fixture.start())
			{
				WorktreeRegistrar client = server.client();
				for (Path refused : List.of(optimizer.resolve("unknown"), other, linked, optimizer.resolve("c5")))
				{
					String message = refusal(() -> client.remove(fixture.project, refused));
					requireThat(message, "message").contains("is not this session's");
				}
				client.add(fixture.project, campaign);
				String existing = refusal(() -> client.add(fixture.project, campaign));
				requireThat(existing, "existing").contains("already exists");
				requireThat(TestRepositories.worktreeCount(git), "count").isEqualTo(2L);
				try (Stream<Path> entries = Files.list(target))
				{
					requireThat(entries.toList(), "target").isEmpty();
				}
			}
		}
		try (Fixture fixture = new Fixture())
		{
			Git git = fixture.repository();
			Path campaign = Files.createDirectories(fixture.sessionDirectory().resolve("optimizer/c1"));
			try (Fixture.Running server = fixture.start())
			{
				String message = refusal(() -> server.client().add(fixture.project, campaign));
				requireThat(message, "message").contains("lets its commands write " +
					fixture.project.resolve(".git/config"));
				requireThat(Files.exists(campaign.resolve("worktree"), LinkOption.NOFOLLOW_LINKS), "worktree").isFalse();
				requireThat(TestRepositories.worktreeCount(git), "count").isEqualTo(1L);
			}
		}
	}

	/**
	 * Verifies that the server refuses a worktree request whose document holds another field, names another
	 * operation or an identifier that is not shaped like a campaign identifier, or whose archive holds another entry.
	 *
	 * @throws IOException          if the fixture cannot be created or a request cannot be served
	 * @throws InterruptedException if the test is interrupted
	 */
	@Test(timeOut = 60_000)
	public void refusesMalformedWorktreeRequests() throws IOException, InterruptedException
	{
		// Requirement: R-W17
		try (Fixture fixture = new Fixture())
		{
			fixture.repository();
			fixture.policy.allow(fixture.protectingGit());
			Files.createDirectories(fixture.sessionDirectory().resolve("optimizer/c1"));
			try (Fixture.Running server = fixture.start())
			{
				ObjectNode extra = fixture.json.createObjectNode().put("operation", "add").put("campaign", "c1").
					put("path", "/elsewhere");
				ObjectNode operation = fixture.json.createObjectNode().put("operation", "prune").put("campaign", "c1");
				ObjectNode identifier = fixture.json.createObjectNode().put("operation", "add").
					put("campaign", "../c1");
				List<String> reasons = new ArrayList<>();
				for (ObjectNode manifest : List.of(extra, operation, identifier))
				{
					JsonNode response = server.worktreeRequest(manifest, false);
					requireThat(response.path("status").asString(), "status").isEqualTo("refused");
					reasons.add(response.path("reason").asString());
				}
				JsonNode response = server.worktreeRequest(fixture.json.createObjectNode().put("operation", "add").
					put("campaign", "c1"), true);
				requireThat(response.path("status").asString(), "entryStatus").isEqualTo("refused");
				requireThat(reasons.get(0), "extra").contains("may not choose path");
				requireThat(reasons.get(1), "operation").contains("not prune");
				requireThat(reasons.get(2), "identifier").contains("campaign identifier");
				requireThat(response.path("reason").asString(), "entry").contains("holds only request.json");
				requireThat(Files.exists(fixture.sessionDirectory().resolve("optimizer/c1/worktree")), "worktree").
					isFalse();
			}
		}
	}

	/**
	 * Runs a worktree operation that must be refused.
	 *
	 * @param operation the operation
	 * @return the refusal's message
	 * @throws IOException if the operation fails otherwise
	 */
	private static String refusal(WorktreeOperation operation) throws IOException
	{
		try
		{
			operation.run();
		}
		catch (WorktreeRefusedException e)
		{
			return e.getMessage();
		}
		throw new AssertionError("The worktree operation was not refused");
	}

	/**
	 * A worktree operation of a test.
	 */
	@FunctionalInterface
	private interface WorktreeOperation
	{
		/**
		 * Runs the operation.
		 *
		 * @throws IOException if the operation fails or is refused
		 */
		void run() throws IOException;
	}

	/**
	 * The channel of a client to a server that runs on a thread of the test.
	 *
	 * @param requests the server's requests directory
	 * @param exit     the server's exit status
	 */
	private record ServerChannel(Path requests, Future<Integer> exit) implements WorktreeRequestClient.Channel
	{
		@Override
		public boolean isServed()
		{
			return !exit.isDone();
		}

		@Override
		public boolean removesResponses()
		{
			return true;
		}

		@Override
		public void close()
		{
		}
	}

	/**
	 * The registrar that a client uses in place, which a client that sends requests never calls.
	 */
	private static final class NoInPlace implements WorktreeRegistrar
	{
		@Override
		public void add(Path project, Path campaign)
		{
			throw new AssertionError("The client ran add itself");
		}

		@Override
		public void recreate(Path project, Path campaign)
		{
			throw new AssertionError("The client ran recreate itself");
		}

		@Override
		public void remove(Path project, Path campaign)
		{
			throw new AssertionError("The client ran remove itself");
		}

		@Override
		public boolean repair(Path project, Path campaign)
		{
			throw new AssertionError("The client ran repair itself");
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
		 * Makes the session's project a repository with one commit.
		 *
		 * @return the repository's Git
		 * @throws IOException if Git fails
		 */
		private Git repository() throws IOException
		{
			return TestRepositories.initialize(project, root.resolve("no-excludes"));
		}

		/**
		 * Returns a sandboxed policy that lets the session write its project but not the repository's configuration
		 * or hooks, as a harness's sandbox protects them.
		 *
		 * @return the policy
		 */
		private ParentPolicy protectingGit()
		{
			return new ParentPolicy(true, List.of(project), List.of(project.resolve(".git/config"),
				project.resolve(".git/hooks")), List.of(), List.of(), HostAccess.listed(Set.of(MODEL_HOST)), false,
				List.of());
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
					return new ExitedProcess(new ProcessBuilder(), 0, "", "");
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
			 * Returns a client that sends its worktree requests to this server.
			 *
			 * @return the client
			 */
			private WorktreeRegistrar client()
			{
				return new WorktreeRequestClient(() -> Optional.of(new ServerChannel(requests, exit)), new NoInPlace(),
					JsonMapper.builder().build(), Thread::yield);
			}

			/**
			 * Writes a worktree request archive that holds a document, and waits for its response.
			 *
			 * @param manifest the document
			 * @param extra    {@code true} to add an entry besides the document
			 * @return the response
			 * @throws IOException if the request cannot be written or the response does not arrive in time
			 */
			private JsonNode worktreeRequest(ObjectNode manifest, boolean extra) throws IOException
			{
				JsonMapper json = JsonMapper.builder().build();
				String id = WorktreeRequest.PREFIX + UUID.randomUUID();
				Path staging = requests.resolveSibling("staging.tar");
				try (OutputStream bytes = Files.newOutputStream(staging);
				     TarArchiveOutputStream archive = new TarArchiveOutputStream(bytes))
				{
					entry(archive, "request.json", json.writeValueAsBytes(manifest));
					if (extra)
						entry(archive, "project/marker", "extra".getBytes(StandardCharsets.UTF_8));
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
