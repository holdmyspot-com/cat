package io.github.cowwoc.cat.common.optimizer.port.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.port.contract.test.SessionPortContract.Subject;
import io.github.cowwoc.cat.common.optimizer.port.contract.test.SessionPortContract;
import io.github.cowwoc.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Runs the {@link SessionPortContract} against {@link FakeSessionPort}, so that the fake that the rest of the suite
 * relies on shows the behaviour that the real adapters must show.
 */
public final class FakeSessionPortContractTest
{
	private static final Instant RESET = Instant.parse("2026-10-01T00:00:00Z");

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
		return new SessionPortContract(FakeSessionPortContractTest::open);
	}

	/**
	 * Verifies that the fake completes a session with evidence of every turn.
	 *
	 * @throws Exception if the check fails
	 */
	@Test
	public void completesWithEvidenceOfEveryTurn() throws Exception
	{
		// Requirement: R-E13
		// Requirement: R-E16
		// Requirement: R-E6
		contract().completesWithEvidenceOfEveryTurn();
	}

	/**
	 * Verifies that the fake leaves read-only evidence unchanged.
	 *
	 * @throws Exception if the check fails
	 */
	@Test
	public void judgeLeavesReadOnlyEvidenceUnchanged() throws Exception
	{
		// Requirement: R-E13
		contract().judgeLeavesReadOnlyEvidenceUnchanged();
	}

	/**
	 * Verifies that the fake ends an interrupted session as interrupted.
	 *
	 * @throws Exception if the check fails
	 */
	@Test
	public void interruptEndsARunningTurnAsInterrupted() throws Exception
	{
		// Requirement: R-E15
		contract().interruptEndsARunningTurnAsInterrupted();
	}

	/**
	 * Verifies that an interrupt after the end changes nothing in the fake.
	 *
	 * @throws Exception if the check fails
	 */
	@Test
	public void interruptAfterTheEndChangesNothing() throws Exception
	{
		// Requirement: R-E15
		contract().interruptAfterTheEndChangesNothing();
	}

	/**
	 * Verifies that the fake classifies the host login without a session.
	 *
	 * @throws Exception if the check fails
	 */
	@Test
	public void reportsTheHostLoginWithoutASession() throws Exception
	{
		// Requirement: R-E7
		contract().reportsTheHostLoginWithoutASession();
	}

	/**
	 * Opens a fake with its fixtures.
	 *
	 * @return the subject
	 * @throws IOException if the fixtures cannot be created
	 */
	private static Subject open() throws IOException
	{
		TemporaryDirectories cleanup = new TemporaryDirectories();
		Path workspace = cleanup.add(Files.createTempDirectory("cat-fake-session-"));
		return new FakeSubject(cleanup, workspace);
	}

	/**
	 * The fake port with fixtures in a temporary directory.
	 */
	private static final class FakeSubject implements Subject
	{
		private final TemporaryDirectories cleanup;
		private final Path workspace;
		private final FakeSessionPort port = new FakeSessionPort(HostLogin.SUBSCRIPTION);

		/**
		 * Creates a subject.
		 *
		 * @param cleanup   deletes the workspace
		 * @param workspace the workspace
		 */
		private FakeSubject(TemporaryDirectories cleanup, Path workspace)
		{
			this.cleanup = cleanup;
			this.workspace = workspace;
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
			// A fake session has no turn to wait for.
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
				fake.end(new SessionResult.Completed(transcript, responses, workspace, "fake-model", "medium",
					new TokenUsage(10, 0, 5, 0), List.of(new RateLimitWindow(0.1, RESET)), "none", List.of()));
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
