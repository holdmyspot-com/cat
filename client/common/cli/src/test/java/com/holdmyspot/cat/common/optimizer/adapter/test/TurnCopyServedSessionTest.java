package com.holdmyspot.cat.common.optimizer.adapter.test;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.contract.InvalidProposalException;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.port.FollowUp;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.proposal.OutputFile;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingRun;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import tools.jackson.databind.json.JsonMapper;

import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests that a session that the optimizer requests through its run's runner shows its follow-up the files that the
 * session has written so far, as a session that the optimizer runs directly does, using the optimizer's own session,
 * the run's own runner and the run's own broker.
 */
public final class TurnCopyServedSessionTest
{
	private static final Duration WAIT = Duration.ofSeconds(60);
	private final JsonMapper json = JsonMapper.builder().build();

	/**
	 * Creates the test.
	 */
	public TurnCopyServedSessionTest()
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
	 * Verifies that a requested session whose first turn writes a valid output file gets no correction, because the
	 * follow-up that validates the file after that turn reads the file that the session wrote.
	 *
	 * @param harness the harness whose runner serves the session
	 * @throws IOException          if a fixture cannot be created
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session cannot determine a result
	 * @throws TimeoutException     if the session does not end
	 */
	@Test(dataProvider = "harnesses", timeOut = 120_000)
	public void followUpReadsOutputWrittenInFirstTurn(String harness)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
		     TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-turn-copy-")).toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			try (RequestingRun parent = new RequestingRun(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"),
					List::of, false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(),
					Yielding.fileReplacer());
				TestSessions sessions = new TestSessions(harness, settings, (arguments, input, output) ->
					SessionRequestForwarder.serve("The prompt optimizer", harness, root, arguments, input, output,
						parent.requests(), bounded()));
				OutputFile outputFile = new OutputFile(json);
				List<String> problems = new CopyOnWriteArrayList<>();
				FollowUp validate = result ->
				{
					try
					{
						outputFile.read(result);
						return Optional.empty();
					}
					catch (InvalidProposalException e)
					{
						problems.addAll(e.problems());
						return Optional.of("correct the output file");
					}
				};
				SessionRequest request = new SessionRequest(SessionRole.TRIAL, partOf(SessionRole.TRIAL), source,
					List.of(RequestingRun.WRITE_OUTPUT), List.of(), Duration.ofMinutes(5),
					new TrialProfile("model-a", "low"), TestWorkspace.NONE, List.of(), Optional.of(validate));
				SessionHandle handle = sessions.start(request);

				SessionResult result = handle.completion().toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS);

				requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
				requireThat(problems, "problems").isEmpty();
				List<String> turns = new ArrayList<>();
				for (String record : parent.received())
				{
					if (json.readTree(record).path("type").asString("").equals("turn"))
						turns.add(record);
				}
				requireThat(turns.size(), "turns").isEqualTo(1);
				SessionResult.Completed completed = (SessionResult.Completed) result;
				requireThat(Files.isRegularFile(completed.effects().resolve(RequestingRun.OUTPUT_FILE)),
					"outputFileKept").isTrue();
				requireThat(Files.isRegularFile(completed.effects().resolve("result.txt")), "resultKept").isTrue();
			}
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
