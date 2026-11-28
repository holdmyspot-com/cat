package com.holdmyspot.cat.common.optimizer.port.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract.Subject;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.testng.annotations.Test;

/**
 * Runs the {@link SessionPortContract} against {@link FakeSessionPort}, so that the fake that the rest of the suite
 * relies on shows the behaviour that the real adapters must show.
 */
public final class FakeSessionPortContractTest
{
	private static final Instant RESET = Instant.parse("2026-10-01T00:00:00Z");
	private static final List<RateLimitWindow> WINDOWS = List.of(new RateLimitWindow(0.1, RESET));

	/**
	 * Creates the test.
	 */
	public FakeSessionPortContractTest()
	{
	}

	/**
	 * Creates a contract over a fresh fake.
	 *
	 * @return the contract
	 */
	private static SessionPortContract contract()
	{
		return contract(true);
	}

	/**
	 * Creates a contract over a fresh fake that stands for a harness that does or does not report exit statuses.
	 *
	 * @param reportsExitStatus whether the harness reports the exit status of the commands that a session runs
	 * @return the contract
	 */
	private static SessionPortContract contract(boolean reportsExitStatus)
	{
		return new SessionPortContract(() -> open(reportsExitStatus));
	}

	/**
	 * Verifies that the fake runs the shared completion check: it starts a session, ends it with a result built from
	 * the request's turns, and delivers that result through the session's completion. The result's contents come from
	 * the test's own {@code finish} step, so this checks the fake's delivery and not how a real port gathers evidence.
	 *
	 * @throws IOException          if the fixtures cannot be created or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test
	public void completesWithEvidenceOfEveryTurn()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		contract().completesWithEvidenceOfEveryTurn();
	}

	/**
	 * Runs the shared check that a scripted judge session completes and leaves
	 * the local evidence fixture bytes unchanged.
	 *
	 * @throws IOException          if the fixtures cannot be created or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test
	public void judgeKeepsLocalEvidence()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		contract().judgeKeepsLocalEvidence();
	}

	/**
	 * Verifies that the fake ends an interrupted session as interrupted.
	 *
	 * @throws IOException          if the fixtures cannot be created or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test
	public void interruptEndsARunningTurnAsInterrupted()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E15
		contract().interruptEndsARunningTurnAsInterrupted();
	}

	/**
	 * Verifies that an interrupt after the end changes nothing in the fake.
	 *
	 * @throws IOException          if the fixtures cannot be created or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test
	public void interruptAfterTheEndChangesNothing()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E15
		contract().interruptAfterTheEndChangesNothing();
	}

	/**
	 * Verifies that the fake classifies the host login without a session.
	 *
	 * @throws IOException if the fixtures cannot be created or read
	 */
	@Test
	public void reportsTheHostLoginWithoutASession() throws IOException
	{
		// Requirement: R-E7
		contract().reportsTheHostLoginWithoutASession();
	}

	/**
	 * Verifies that the fake reports the exit status answer that the test chose.
	 *
	 * @throws IOException if the fixtures cannot be created or read
	 */
	@Test
	public void reportsExitStatusAsTheHarnessDoes() throws IOException
	{
		// Requirement: R-E7
		contract(true).reportsExitStatusAsTheHarnessDoes();
		contract(false).reportsExitStatusAsTheHarnessDoes();
	}

	/**
	 * Verifies that the fake identifies its versions and results folders.
	 *
	 * @throws IOException if the fixtures cannot be created or read
	 */
	@Test
	public void identifiesItsVersionsAndResultsFolders() throws IOException
	{
		// Requirement: R-E7
		contract().identifiesItsVersionsAndResultsFolders();
	}

	/**
	 * Verifies that the fake reports nothing for an empty host.
	 *
	 * @throws IOException if the fixtures cannot be created or read
	 */
	@Test
	public void reportsNothingForAnEmptyHost() throws IOException
	{
		// Requirement: R-E7
		contract().reportsNothingForAnEmptyHost();
	}

	/**
	 * Verifies that the fake reads the evidence of a completed transcript repeatably.
	 *
	 * @throws IOException          if the fixtures cannot be created or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test
	public void readsTheEvidenceOfACompletedTranscript()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E13
		contract().readsTheEvidenceOfACompletedTranscript();
	}

	/**
	 * Verifies that the fake's interrupted session carries the windows reported before the interrupt.
	 *
	 * @throws IOException          if the fixtures cannot be created or read
	 * @throws InterruptedException if waiting for completion is interrupted
	 * @throws ExecutionException   if the session fails
	 * @throws TimeoutException     if the session does not complete
	 */
	@Test
	public void interruptKeepsTheWindowsReportedBeforeIt()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E15
		contract().interruptKeepsTheWindowsReportedBeforeIt();
	}

	/**
	 * Opens a fake with its fixtures.
	 *
	 * @param reportsExitStatus whether the harness reports the exit status of the commands that a session runs
	 * @return the subject
	 * @throws IOException if the fixtures cannot be created
	 */
	private static Subject open(boolean reportsExitStatus) throws IOException
	{
		TemporaryDirectories cleanup = new TemporaryDirectories();
		Path workspace = cleanup.add(Files.createTempDirectory("cat-fake-session-"));
		return new FakeSubject(cleanup, workspace, reportsExitStatus);
	}

	/**
	 * The fake port with fixtures in a temporary directory.
	 */
	private static final class FakeSubject implements Subject
	{
		private final TemporaryDirectories cleanup;
		private final Path workspace;
		private final FakeSessionPort port = new FakeSessionPort(HostLogin.SUBSCRIPTION);
		private final boolean reportsExitStatus;

		/**
		 * Creates a subject.
		 *
		 * @param cleanup   deletes the workspace
		 * @param workspace the workspace
		 * @param reportsExitStatus whether the harness that the fake stands for reports exit statuses
		 */
		private FakeSubject(TemporaryDirectories cleanup, Path workspace, boolean reportsExitStatus)
		{
			this.cleanup = cleanup;
			this.workspace = workspace;
			this.reportsExitStatus = reportsExitStatus;
			port.reportsExitStatus(reportsExitStatus);
		}

		@Override
		public SessionPort port()
		{
			return port;
		}

		@Override
		public Set<HostLogin> acceptableLogins()
		{
			return Set.of(HostLogin.SUBSCRIPTION);
		}

		@Override
		public Path workspace()
		{
			return workspace;
		}

		@Override
		public String quickPrompt()
		{
			return "Reply with the single word ok.";
		}

		@Override
		public String longPrompt()
		{
			return "Keep working until you are told to stop.";
		}

		@Override
		public void awaitTurnInProgress(SessionHandle session)
		{
			// A fake session has no turn to wait for, so the test reports the windows that the harness would have
			((FakeSession) session).reportWindows(WINDOWS);
		}

		@Override
		public boolean reportsExitStatus()
		{
			return reportsExitStatus;
		}

		@Override
		public Path pluginWithoutHooks() throws IOException
		{
			return Files.createDirectories(workspace.resolve("plugin"));
		}

		@Override
		public List<RateLimitWindow> windowsReportedBeforeInterrupt()
		{
			return WINDOWS;
		}

		@Override
		public void finish(SessionHandle session)
		{
			FakeSession fake = (FakeSession) session;
			try
			{
				Path transcript = workspace.resolve("transcript-" + port.sessions().indexOf(fake) + ".jsonl");
				Files.writeString(transcript, "{\"type\":\"result\"}\n", UTF_8);
				List<List<String>> responses = Collections.nCopies(fake.request().turns().size(), List.of("ok"));
				fake.end(new SessionResult.Completed(transcript, responses, workspace,
					workspace.resolveSibling("initial-project"), "fake-model", "medium",
					new TokenUsage(10, 0, 5, 0), WINDOWS, "none", List.of()));
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public void close()
		{
			try
			{
				cleanup.close();
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}
	}
}
