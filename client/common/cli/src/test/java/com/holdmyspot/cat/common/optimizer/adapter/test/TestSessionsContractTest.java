package com.holdmyspot.cat.common.optimizer.adapter.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.optimizer.adapter.Bridge;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract;
import com.holdmyspot.cat.common.optimizer.port.contract.test.SessionPortContract.Subject;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness;
import com.holdmyspot.cat.common.runner.fakes.test.ScriptedHarness.Turn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;

/**
 * Runs the session port contract on {@link TestSessions}, the generic harness's subclass of {@link RunnerSessions},
 * over a scripted harness behind the real runner bridge, so that the tests that use it rely only on behaviour that
 * the contract checks.
 */
public final class TestSessionsContractTest
{
	/**
	 * The reply of every quick turn.
	 */
	private static final String REPLY = "ok";

	/**
	 * Creates a test.
	 */
	public TestSessionsContractTest()
	{
	}

	/**
	 * Returns a turn that replies at once and reports the model, its usage and its output.
	 *
	 * @return the turn
	 */
	private static Turn quickTurn()
	{
		return Turn.replies(REPLY, TestSessions.MODEL_EVENT, TestSessions.USAGE_EVENT, TestSessions.OUTPUT_EVENT);
	}

	/**
	 * Returns the contract over ports whose harness follows a script.
	 *
	 * @param jvm    the JVM scope
	 * @param script what the harness does for each turn, in turn order
	 * @return the contract
	 */
	private static SessionPortContract contract(JvmScope jvm, List<Turn> script)
	{
		return contract(jvm, script, new CountDownLatch(0));
	}

	/**
	 * Returns the contract over ports whose harness follows a script.
	 *
	 * @param jvm     the JVM scope
	 * @param script  what the harness does for each turn, in turn order
	 * @param entered counted down once a long turn is in progress
	 * @return the contract
	 */
	private static SessionPortContract contract(JvmScope jvm, List<Turn> script, CountDownLatch entered)
	{
		return new SessionPortContract(() -> ScriptedSubject.open(jvm, (options, input, output) ->
			ScriptedHarness.serve(Path.of(ScriptedHarness.optionValue(options, "--run-root")), TestSessions.HARNESS,
				script, input, output), entered));
	}

	/**
	 * Returns the contract over ports whose checks must not start a session.
	 *
	 * @param jvm the JVM scope
	 * @return the contract
	 */
	private static SessionPortContract contractWithoutSessions(JvmScope jvm)
	{
		return new SessionPortContract(() -> ScriptedSubject.open(jvm, (_, _, _) ->
		{
			throw new AssertionError("The check must not start a session");
		}, new CountDownLatch(0)));
	}

	/**
	 * Returns the contract over ports whose harness blocks the turn until it is interrupted.
	 *
	 * @param jvm the JVM scope
	 * @return the contract
	 */
	private static SessionPortContract interruptedContract(JvmScope jvm)
	{
		CountDownLatch entered = new CountDownLatch(1);
		return contract(jvm, List.of(Turn.blocksUntilInterrupted(TestSessions.WINDOW_EVENT).announcing(entered)),
			entered);
	}

	/**
	 * Runs the contract check that every turn's evidence is reported.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void completesWithEvidenceOfEveryTurn() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(quickTurn(), quickTurn())).completesWithEvidenceOfEveryTurn();
		}
	}

	/**
	 * Runs the contract check that a judge leaves its read-only evidence unchanged.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void judgeLeavesReadOnlyEvidenceUnchanged() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(quickTurn())).judgeLeavesReadOnlyEvidenceUnchanged();
		}
	}

	/**
	 * Runs the contract check that an interrupt ends a running turn as interrupted.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void interruptEndsARunningTurnAsInterrupted() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			interruptedContract(jvm).interruptEndsARunningTurnAsInterrupted();
		}
	}

	/**
	 * Runs the contract check that an interrupt after the session ended changes nothing.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void interruptAfterTheEndChangesNothing() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(quickTurn())).interruptAfterTheEndChangesNothing();
		}
	}

	/**
	 * Runs the contract check that the host login is reported without a session.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void reportsTheHostLoginWithoutASession() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contractWithoutSessions(jvm).reportsTheHostLoginWithoutASession();
		}
	}

	/**
	 * Runs the contract check that the port reports exit status as its harness does.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void reportsExitStatusAsTheHarnessDoes() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contractWithoutSessions(jvm).reportsExitStatusAsTheHarnessDoes();
		}
	}

	/**
	 * Runs the contract check that the port identifies its versions and run roots.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void identifiesItsVersionsAndRunRoots() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contractWithoutSessions(jvm).identifiesItsVersionsAndRunRoots();
		}
	}

	/**
	 * Runs the contract check that an empty host reports nothing.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void reportsNothingForAnEmptyHost() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contractWithoutSessions(jvm).reportsNothingForAnEmptyHost();
		}
	}

	/**
	 * Runs the contract check that a completed transcript's evidence reads the same each time.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void readsTheEvidenceOfACompletedTranscript() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			contract(jvm, List.of(quickTurn())).readsTheEvidenceOfACompletedTranscript();
		}
	}

	/**
	 * Runs the contract check that an interrupted session keeps the rate-limit windows reported before the interrupt.
	 *
	 * @throws Exception if the check fails
	 */
	@Test(timeOut = 60_000)
	public void interruptKeepsTheWindowsReportedBeforeIt() throws Exception
	{
		try (TestJvmScope jvm = new TestJvmScope())
		{
			interruptedContract(jvm).interruptKeepsTheWindowsReportedBeforeIt();
		}
	}

	/**
	 * A port of the generic harness in a temporary directory.
	 *
	 * @param cleanup   deletes the directory
	 * @param port      the port
	 * @param workspace the directory that holds the sessions' project
	 * @param entered   counted down once a long turn is in progress
	 */
	private record ScriptedSubject(TemporaryDirectories cleanup, SessionPort port, Path workspace,
		CountDownLatch entered) implements Subject
	{
		/**
		 * Creates a subject.
		 *
		 * @param cleanup   deletes the directory
		 * @param port      the port
		 * @param workspace the directory that holds the sessions' project
		 * @param entered   counted down once a long turn is in progress
		 * @throws NullPointerException if any argument is null
		 */
		private ScriptedSubject
		{
			requireThat(cleanup, "cleanup").isNotNull();
			requireThat(port, "port").isNotNull();
			requireThat(workspace, "workspace").isNotNull();
			requireThat(entered, "entered").isNotNull();
		}

		/**
		 * Creates a subject in a new temporary directory.
		 *
		 * @param jvm     the JVM scope
		 * @param bridge  runs the runner's bridge
		 * @param entered counted down once a long turn is in progress
		 * @return the subject
		 * @throws IOException if the directory cannot be created
		 */
		static ScriptedSubject open(JvmScope jvm, Bridge bridge, CountDownLatch entered) throws IOException
		{
			TemporaryDirectories cleanup = new TemporaryDirectories();
			Path root = cleanup.add(Files.createTempDirectory(jvm.temporaryDirectory(), "cat-sessions-contract-"));
			Path workspace = Files.createDirectories(root.resolve("workspace"));
			RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, root, root.resolve("runs"), List::of,
				false, Map.of(), Files.createDirectories(root.resolve("configuration")), HarnessCapacity.unlimited(),
				Clock.systemUTC(), Yielding.fileReplacer());
			return new ScriptedSubject(cleanup, new TestSessions(settings, bridge), workspace, entered);
		}

		@Override
		public Set<HostLogin> acceptableLogins()
		{
			return Set.of(HostLogin.UNKNOWN);
		}

		@Override
		public String quickPrompt()
		{
			return "Reply with the single word ok.";
		}

		@Override
		public String longPrompt()
		{
			return "Keep working until interrupted.";
		}

		@Override
		public void awaitTurnInProgress(SessionHandle session) throws InterruptedException
		{
			requireThat(entered.await(60, TimeUnit.SECONDS), "entered").isTrue();
			requireThat(session.completion().toCompletableFuture().isDone(), "endedEarly").isFalse();
		}

		@Override
		public void finish(SessionHandle session)
		{
			// The scripted harness ends each quick turn in response to its request
		}

		@Override
		public boolean reportsExitStatus()
		{
			return true;
		}

		@Override
		public Path pluginWithoutHooks() throws IOException
		{
			return Files.createDirectories(workspace.resolveSibling("plugin"));
		}

		@Override
		public List<RateLimitWindow> windowsReportedBeforeInterrupt()
		{
			return List.of(TestSessions.WINDOW);
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
				throw new AssertionError(e);
			}
		}
	}
}
