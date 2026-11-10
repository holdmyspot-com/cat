package com.holdmyspot.cat.common.runner.fakes.test;

import com.holdmyspot.cat.common.FileLocks;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.runner.HarnessRun;
import com.holdmyspot.cat.common.runner.HarnessRunManager;
import com.holdmyspot.cat.common.runner.HarnessRunReceipt;
import com.holdmyspot.cat.common.runner.HarnessRunnerOptions;
import com.holdmyspot.cat.common.runner.HarnessTemporaryRoot;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.SessionRequestBroker;
import com.holdmyspot.cat.common.secrets.MainHarnessRunnerScopeSecrets;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A run whose session requests sessions, served by the run's real {@link SessionRequestBroker}, which discovers
 * requests on the production heartbeat ticker's thread. Each requested session is a {@link FakeHarnessCli} that plays
 * a harness runner's bridge: it reports its start, answers each turn with the reply {@code reply <n>}, and completes
 * with a retained-evidence receipt. A turn whose prompt is {@value #HOLD} gets no reply until an {@code interrupt}
 * record arrives, which the session answers with an {@code interrupted} record and the turn's completion, as a Claude
 * runner does. Its run holds {@code project/result.txt} and a login in its home.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class RequestingRun implements AutoCloseable
{
	/**
	 * The prompt of a turn that the requested session answers only once it is interrupted.
	 */
	public static final String HOLD = "hold";
	/**
	 * How often the broker discovers requests.
	 */
	private static final Duration SCAN_PERIOD = Duration.ofMillis(5);
	private final JsonMapper json = JsonMapper.builder().build();
	private final FileLocks fileLocks;
	private final HarnessRun run;
	private final SessionRequestBroker broker;
	private final List<List<String>> launches = new CopyOnWriteArrayList<>();
	private final List<String> received = new CopyOnWriteArrayList<>();
	private final CountDownLatch holding = new CountDownLatch(1);

	/**
	 * Creates the run and starts its broker.
	 *
	 * @param root            the folder that holds the run and its artifact
	 * @param fileLocks       the file locks of the runs
	 * @param sessionRequests how many sessions the run allows
	 * @throws IOException if the run cannot be created or its broker cannot start
	 */
	public RequestingRun(Path root, FileLocks fileLocks, int sessionRequests) throws IOException
	{
		this.fileLocks = fileLocks;
		Files.createDirectories(root);
		this.run = new HarnessRunManager(fileLocks, root.resolve("requesting-runs"), Clock.systemUTC(),
			Yielding.fileReplacer()).create("claude");
		SessionRequestAccess.allowing(run, root, sessionRequests, Platform.current(), SessionRequestAccess.Shell.POSIX);
		HarnessRunnerOptions options = HarnessRunnerOptions.parse(new String[]{"--artifact", root.toString(),
			"--project", run.directory().resolve("project").toString(), "--model", "model-a", "--effort", "effort-a",
			"--keep-run", "--session-requests", String.valueOf(sessionRequests)}, "harness",
			HarnessTemporaryRoot.of(root));
		this.broker = new SessionRequestBroker(run, options, (harness, arguments, _) -> child(harness, arguments));
		broker.start(MainHarnessRunnerScopeSecrets.getMainHarnessRunnerScopeAccess().heartbeatEvery(SCAN_PERIOD));
	}

	/**
	 * Returns the run's requests directory, which a session's environment names.
	 *
	 * @return the directory
	 */
	public Path requests()
	{
		return SessionRequestAccess.requestsDirectory(run);
	}

	/**
	 * Returns the run's responses directory.
	 *
	 * @return the directory
	 */
	public Path responses()
	{
		return SessionRequestAccess.responsesDirectory(run);
	}

	/**
	 * Returns the runner options of each requested session, in the order the broker started them.
	 *
	 * @return the options
	 */
	public List<List<String>> launches()
	{
		return List.copyOf(launches);
	}

	/**
	 * Returns the records that the requested sessions received, in order.
	 *
	 * @return the records
	 */
	public List<String> received()
	{
		return List.copyOf(received);
	}

	/**
	 * Waits until a requested session holds a turn that awaits an interrupt.
	 *
	 * @param timeout how long to wait
	 * @return {@code true} if a session holds such a turn
	 * @throws InterruptedException if the wait is interrupted
	 */
	public boolean awaitHeldTurn(Duration timeout) throws InterruptedException
	{
		return holding.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
	}

	/**
	 * Starts a requested session.
	 *
	 * @param harness   the requested harness
	 * @param arguments the requested runner's options
	 * @return the session
	 * @throws IOException if its run cannot be created
	 */
	private FakeHarnessCli child(String harness, List<String> arguments) throws IOException
	{
		launches.add(arguments);
		HarnessRun child = new HarnessRunManager(fileLocks, Path.of(option(arguments, "--run-root")),
			Clock.systemUTC(), Yielding.fileReplacer()).create(harness);
		Files.writeString(child.directory().resolve("project").resolve("result.txt"), "result");
		Files.writeString(Files.createDirectories(child.directory().resolve("home")).resolve("login.json"), "login");
		Path output = Path.of(option(arguments, "--output"));
		Files.writeString(output, "");
		append(output, json.createObjectNode().put("type", "started").put("run_id", child.id()).toString());
		AtomicInteger turns = new AtomicInteger();
		AtomicReference<FakeHarnessCli> process = new AtomicReference<>();
		process.set(new FakeHarnessCli(line ->
		{
			try
			{
				received.add(line);
				JsonNode record = json.readTree(line);
				switch (record.path("type").asString())
				{
					case "turn" ->
					{
						if (record.path("prompt").asString().equals(HOLD))
						{
							holding.countDown();
							return;
						}
						String reply = "reply " + turns.incrementAndGet();
						ObjectNode completed = json.createObjectNode().put("type", "turn_completed").
							put("run_id", child.id()).put("exit_code", 0).put("error", "").put("assistant_response", reply);
						completed.putArray("assistant_messages").add(reply);
						append(output, completed.toString());
					}
					case "interrupt" ->
					{
						append(output, json.createObjectNode().put("type", "interrupted").put("run_id", child.id()).
							toString());
						String reply = "[Request interrupted by user]";
						ObjectNode completed = json.createObjectNode().put("type", "turn_completed").
							put("run_id", child.id()).put("exit_code", 0).put("error", "").put("assistant_response", reply);
						completed.putArray("assistant_messages").add(reply);
						append(output, completed.toString());
					}
					case "complete" ->
					{
						append(output, json.createObjectNode().put("type", "completed").put("run_id", child.id()).
							toString());
						HarnessRunReceipt.write(json, Yielding.fileReplacer(), Path.of(option(arguments, "--receipt")),
							child, output, List.of());
						process.get().exit();
					}
					default -> throw new IOException("Unexpected record: " + line);
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}));
		return process.get();
	}

	/**
	 * Returns the value of an option.
	 *
	 * @param arguments the options
	 * @param name      the option's name
	 * @return its value
	 * @throws IllegalArgumentException if the options do not name it
	 */
	private static String option(List<String> arguments, String name)
	{
		int index = arguments.indexOf(name);
		if (index < 0 || index + 1 == arguments.size())
			throw new IllegalArgumentException("Missing " + name + " in " + arguments);
		return arguments.get(index + 1);
	}

	/**
	 * Appends a line to a file.
	 *
	 * @param file the file
	 * @param line the line, without its terminator
	 * @throws IOException if the file cannot be written
	 */
	private static void append(Path file, String line) throws IOException
	{
		Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
	}

	@Override
	public void close() throws IOException
	{
		broker.close();
	}
}
