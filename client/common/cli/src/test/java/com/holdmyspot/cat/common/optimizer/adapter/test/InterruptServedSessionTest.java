package com.holdmyspot.cat.common.optimizer.adapter.test;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a session that the optimizer requests through its run's runner ends when CAT interrupts it, using the
 * optimizer's own session and the run's own runner to carry the interrupt.
 */
public final class InterruptServedSessionTest
{
	private static final Duration WAIT = Duration.ofSeconds(60);
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public InterruptServedSessionTest()
	{
	}

	/**
	 * Supplies the harnesses that a run's runner serves.
	 *
	 * @return the harness of each case
	 */
	@DataProvider
	public Object[][] harnesses()
	{
		return new Object[][]{{"claude"}, {"codex"}};
	}

	/**
	 * Verifies that the interrupt that CAT sends to a requested session that is working on a turn reaches that
	 * session as an interrupt and ends the turn, instead of the run's runner refusing it as an invalid record and
	 * stopping the session.
	 *
	 * @param harness the harness whose runner serves the session
	 * @throws IOException          if a fixture cannot be created or read
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session cannot determine a result
	 * @throws TimeoutException     if the session does not end
	 */
	@Test(dataProvider = "harnesses", timeOut = 120_000)
	public void interruptReachesRequestedSession(String harness)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
		     TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-interrupt-")).toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			try (RequestingRun parent = new RequestingRun(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"),
					List::of, false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
					Yielding.fileReplacer());
				TestSessions sessions = new TestSessions(harness, settings, (arguments, input, output) ->
					SessionRequestForwarder.serve("The prompt optimizer", harness, root, arguments, input, output,
						parent.requests(), bounded()));
				SessionRequest request = new SessionRequest(SessionRole.TRIAL, partOf(SessionRole.TRIAL), source,
					List.of(RequestingRun.HOLD), List.of(), Duration.ofMinutes(5), new TrialProfile("model-a", "low"));
				SessionHandle handle = sessions.start(request);
				awaitHeldTurn(parent, handle);

				handle.interrupt();

				SessionResult result = handle.completion().toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);
				requireThat(result, "result").isInstanceOf(SessionResult.Interrupted.class);
				requireThat(parent.received(), "received").contains("{\"type\":\"interrupt\"}");
				try (Stream<Path> responses = Files.list(parent.responses()))
				{
					List<Path> directories = responses.filter(Files::isDirectory).toList();
					requireThat(directories.size(), "responses").isEqualTo(1);
					JsonNode response = json.readTree(directories.getFirst().resolve("response.json").toFile());
					requireThat(response.path("reason").asString(""), "reason").doesNotContain("invalid record");
					requireThat(response.path("status").asString(), "status").isEqualTo("completed");
				}
			}
		}
	}

	/**
	 * Waits until the requested session holds a turn.
	 *
	 * @param parent the run that serves the request
	 * @param handle the session
	 * @throws InterruptedException if the wait is interrupted
	 * @throws AssertionError       if the session ends or the turn does not arrive within the time limit
	 */
	private static void awaitHeldTurn(RequestingRun parent, SessionHandle handle) throws InterruptedException
	{
		Instant deadline = Instant.now().plus(WAIT);
		while (!parent.awaitHeldTurn(Duration.ofMillis(50)))
		{
			// A session that ends first reports its own result, which says why no turn arrived
			Object ended = handle.completion().toCompletableFuture().getNow(null);
			if (ended != null)
				throw new AssertionError("The session ended before its turn was held: " + ended);
			if (Instant.now().isAfter(deadline))
				throw new AssertionError("The requested session did not hold a turn within " + WAIT);
		}
	}

	/**
	 * Returns a pause that yields to the other threads and fails once the wait for the session's end
	 * has lasted too long.
	 *
	 * @return the pause
	 */
	private static Pause bounded()
	{
		Instant deadline = Instant.now().plus(WAIT);
		return () ->
		{
			if (Instant.now().isAfter(deadline))
				throw new IOException("The served session did not end");
			Thread.yield();
		};
	}
}
