package com.holdmyspot.cat.common.optimizer.adapter.test;

import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.contract.TestWorkspace;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.SessionRequestForwarder;
import com.holdmyspot.cat.common.runner.fakes.test.RequestingNestedSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * Tests which sessions that the optimizer requests through its nested session's runner return the link declarations of
 * a draft directory, using the optimizer's own session, the nested session's own runner and the nested session's own
 * broker.
 */
public final class DraftLinksServedSessionTest
{
	private static final Duration WAIT = Duration.ofSeconds(60);
	private static final String RESULT_LINKS_OPTION = "--result-project-links";
	private static final String REFUSAL = "The selected result link directory is missing or is not a directory";

	/**
	 * Creates the test.
	 */
	public DraftLinksServedSessionTest()
	{
	}

	/**
	 * Supplies the harnesses that a nested session's runner serves.
	 *
	 * @return the harness of each case
	 */
	@DataProvider
	public Object[][] harnesses()
	{
		return new Object[][]{{"claude"}, {"codex"}};
	}

	/**
	 * Verifies that a contract author session that receives no draft directory, such as the session that writes
	 * reproduction values and answers in its final response, completes without being asked for the link declarations of
	 * a draft directory that its project does not hold.
	 *
	 * @param harness the harness whose runner serves the session
	 * @throws IOException          if a fixture cannot be created
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session cannot determine a result
	 * @throws TimeoutException     if the session does not end
	 */
	@Test(dataProvider = "harnesses", timeOut = 120_000)
	public void sessionWithoutDraftCompletes(String harness)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
		     TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-no-draft-")).toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				TestSessions sessions = sessions(harness, jvm, root, parent);
				SessionRequest request = new SessionRequest(SessionRole.CONTRACT_AUTHOR,
					partOf(SessionRole.CONTRACT_AUTHOR), source, List.of("reply with the values"), List.of(),
					Duration.ofMinutes(5), new TrialProfile("model-a", "low"), TestWorkspace.NONE, List.of(),
					Optional.empty());

				SessionResult result = sessions.start(request).completion().toCompletableFuture().
					get(WAIT.toSeconds(), TimeUnit.SECONDS);

				requireThat(result, "result").withContext(result, "outcome").isInstanceOf(SessionResult.Completed.class);
				requireThat(parent.launches().getFirst(), "launch").doesNotContain(RESULT_LINKS_OPTION);
			}
		}
	}

	/**
	 * Verifies that a contract author session that receives a draft directory is asked for the draft directory's link
	 * declarations, and completes once its project holds the draft directory.
	 *
	 * @param harness the harness whose runner serves the session
	 * @throws IOException          if a fixture cannot be created
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session cannot determine a result
	 * @throws TimeoutException     if the session does not end
	 */
	@Test(dataProvider = "harnesses", timeOut = 120_000)
	public void sessionWithDraftReturnsItsLinks(String harness)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
		     TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-draft-links-")).toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				TestSessions sessions = sessions(harness, jvm, root, parent);
				String seed = RequestingNestedSession.DRAFT + "/seed.md";
				TestWorkspace draft = new TestWorkspace(Map.of(seed, new WorkspaceFile(seed, "seed")), Map.of(),
					"expected");
				SessionRequest request = new SessionRequest(SessionRole.CONTRACT_AUTHOR,
					partOf(SessionRole.CONTRACT_AUTHOR), source, List.of(RequestingNestedSession.WRITE_DRAFT), List.of(),
					Duration.ofMinutes(5), new TrialProfile("model-a", "low"), draft, List.of(), Optional.empty());

				SessionResult result = sessions.start(request).completion().toCompletableFuture().
					get(WAIT.toSeconds(), TimeUnit.SECONDS);

				requireThat(result, "result").withContext(result, "outcome").isInstanceOf(SessionResult.Completed.class);
				List<String> launch = parent.launches().getFirst();
				requireThat(launch.get(launch.indexOf(RESULT_LINKS_OPTION) + 1), "linksDirectory").
					isEqualTo(RequestingNestedSession.DRAFT);
			}
		}
	}

	/**
	 * Verifies that a session that receives a draft directory and ends a turn without it fails with the refusal that
	 * names the missing directory, so that a missing draft directory is not mistaken for a session that needs none.
	 *
	 * @param harness the harness whose runner serves the session
	 * @throws IOException          if a fixture cannot be created
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session cannot determine a result
	 * @throws TimeoutException     if the session does not end
	 */
	@Test(dataProvider = "harnesses", timeOut = 120_000)
	public void sessionThatLosesItsDraftFails(String harness)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TestJvmScope jvm = new TestJvmScope();
		     TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-lost-draft-")).toRealPath();
			Path source = Files.createDirectories(root.resolve("source"));
			try (RequestingNestedSession parent = new RequestingNestedSession(root.resolve("parent"), jvm.fileLocks(), 1))
			{
				TestSessions sessions = sessions(harness, jvm, root, parent);
				String seed = RequestingNestedSession.DRAFT + "/seed.md";
				TestWorkspace draft = new TestWorkspace(Map.of(seed, new WorkspaceFile(seed, "seed")), Map.of(),
					"expected");
				SessionRequest request = new SessionRequest(SessionRole.CONTRACT_AUTHOR,
					partOf(SessionRole.CONTRACT_AUTHOR), source, List.of("reply without writing"), List.of(),
					Duration.ofMinutes(5), new TrialProfile("model-a", "low"), draft, List.of(), Optional.empty());

				SessionResult result = sessions.start(request).completion().toCompletableFuture().
					get(WAIT.toSeconds(), TimeUnit.SECONDS);

				requireThat(result, "result").isInstanceOf(SessionResult.Failed.class);
				requireThat(((SessionResult.Failed) result).reason(), "reason").contains(REFUSAL);
			}
		}
	}

	/**
	 * Creates the optimizer's sessions for a harness whose runner a nested session serves.
	 *
	 * @param harness the harness
	 * @param jvm     the borrowed process scope
	 * @param root    the directory that holds the test's files
	 * @param parent  the nested session that serves the requests
	 * @return the sessions
	 * @throws IOException if the artifact's rule cannot be written
	 */
	private static TestSessions sessions(String harness, TestJvmScope jvm, Path root,
		RequestingNestedSession parent) throws IOException
	{
		// The contract author's role brief carries this rule from the installed artifact
		Files.writeString(Files.createDirectories(root.resolve("rules/include/common")).resolve("contract-design.md"),
			"Complete guidance for contract-design");
		RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("results"),
			List::of, false, Map.of(), root, HarnessCapacity.unlimited(), Clock.systemUTC(), Yielding.fileReplacer());
		return new TestSessions(harness, settings, (arguments, input, output) ->
			SessionRequestForwarder.serve("The prompt optimizer", harness, root, arguments, input, output,
				parent.requests(), bounded()));
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
