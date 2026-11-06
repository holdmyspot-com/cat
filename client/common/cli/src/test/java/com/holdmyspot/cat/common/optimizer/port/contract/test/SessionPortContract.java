package com.holdmyspot.cat.common.optimizer.port.contract.test;

import static com.holdmyspot.cat.common.optimizer.adapter.fakes.test.SessionRequestFixtures.partOf;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The behaviour that every {@link SessionPort} implementation must show, one check per operation.
 * <p>
 * A test class gives the contract an {@link Opener} for the implementation under test, and runs each check from a
 * test method of its own that carries the requirement tags. One test runs the checks against the shared fake; each
 * harness's contract test runs them against the real adapter, replaying output recorded from that harness. The checks
 * are plain methods rather than inherited test methods because the package that holds them is not opened to the test
 * framework.
 * <p>
 * The checks add no timing assumptions beyond a generous bound on each wait.
 */
public final class SessionPortContract
{
	/**
	 * The model and reasoning effort of a session whose profile the test does not examine.
	 */
	private static final TrialProfile SESSION_PROFILE = new TrialProfile("model-a", "low");
	private static final Duration LIMIT = Duration.ofMinutes(10);
	private static final long WAIT_MINUTES = 12;

	private final Opener opener;

	/**
	 * Creates the contract.
	 *
	 * @param opener opens the implementation under test, once per check
	 * @throws NullPointerException if {@code opener} is null
	 */
	public SessionPortContract(Opener opener)
	{
		requireThat(opener, "opener").isNotNull();
		this.opener = opener;
	}

	/**
	 * Opens the implementation under test.
	 */
	@FunctionalInterface
	public interface Opener
	{
		/**
		 * Opens the implementation under test.
		 *
		 * @return the subject, which the check closes
		 * @throws IOException if the subject cannot be set up
		 */
		Subject open() throws IOException;
	}

	/**
	 * An implementation under test, with the fixtures and prompts that suit it.
	 */
	public interface Subject extends AutoCloseable
	{
		/**
		 * Returns the port under test.
		 *
		 * @return the port
		 */
		SessionPort port();

		/**
		 * Returns the logins that the port may report for the host that runs the test. A real host reports the kind
		 * of account that it has, so its subject lists every kind that a developer machine may have, but not
		 * {@link HostLogin#UNKNOWN}.
		 *
		 * @return the acceptable logins
		 */
		Set<HostLogin> acceptableLogins();

		/**
		 * Returns a directory that exists for the subject's lifetime, and that sessions may read as their source.
		 *
		 * @return the directory
		 */
		Path workspace();

		/**
		 * Returns a turn that the harness answers promptly, and that does not change any file.
		 *
		 * @return the prompt
		 */
		String quickPrompt();

		/**
		 * Returns a turn that keeps the harness busy for much longer than the tests wait before they interrupt it.
		 *
		 * @return the prompt
		 */
		String longPrompt();

		/**
		 * Returns once the harness is demonstrably in the middle of the turn that {@link #longPrompt()} gave it, so that
		 * an interrupt that follows cannot arrive before the turn. A subject that replays a recording returns once the
		 * replay has supplied the start of that turn; a fake with no turn to wait for returns at once.
		 *
		 * @param session a session that the test started with {@link #longPrompt()}
		 * @throws IOException          if the evidence cannot be read
		 * @throws InterruptedException if the wait is interrupted
		 */
		void awaitTurnInProgress(SessionHandle session) throws IOException, InterruptedException;

		/**
		 * Lets a session that would otherwise run for as long as the test wants end normally. A real harness ends by
		 * itself, so its subject does nothing. A fake ends the session here.
		 *
		 * @param session a session that the test started
		 */
		void finish(SessionHandle session);

		/**
		 * Indicates whether the harness reports the exit status of the commands that a session runs, which differs
		 * between harnesses.
		 *
		 * @return {@code true} if the port must report that it does
		 */
		boolean reportsExitStatus();

		/**
		 * Returns a plugin directory whose manifest declares no hooks, in the form that the harness reads.
		 *
		 * @return the directory, which exists for the subject's lifetime
		 * @throws IOException if the plugin cannot be created
		 */
		Path pluginWithoutHooks() throws IOException;

		/**
		 * Returns the rate-limit windows that the harness has reported by the time that {@link #awaitTurnInProgress}
		 * returns for a session that was started with {@link #longPrompt()}, in the order that the port reports them.
		 *
		 * @return the windows
		 */
		List<RateLimitWindow> windowsReportedBeforeInterrupt();

		@Override
		void close();
	}

	/**
	 * Waits for a session to end.
	 *
	 * @param session the session
	 * @return its result
	 * @throws InterruptedException if the wait is interrupted
	 * @throws ExecutionException   if the session's completion failed
	 * @throws TimeoutException     if the session did not end in time
	 */
	private static SessionResult await(SessionHandle session)
		throws InterruptedException, ExecutionException, TimeoutException
	{
		return session.completion().toCompletableFuture().get(WAIT_MINUTES, TimeUnit.MINUTES);
	}

	/**
	 * Creates a request.
	 *
	 * @param subject  the subject
	 * @param role     the role
	 * @param readOnly the read-only evidence
	 * @param turns    the turns
	 * @return the request
	 */
	private static SessionRequest request(Subject subject, SessionRole role, List<Path> readOnly, List<String> turns)
	{
		return new SessionRequest(role, partOf(role), subject.workspace(), turns, readOnly, LIMIT,
			SESSION_PROFILE);
	}

	/**
	 * Checks that a session that runs every turn reports a reply per turn, its transcript, its effects directory, the
	 * observed model and the token usage.
	 *
	 * @throws Exception if the session fails
	 */
	public void completesWithEvidenceOfEveryTurn() throws Exception
	{
		try (Subject subject = opener.open())
		{
			SessionRequest request = request(subject, SessionRole.TRIAL, List.of(),
				List.of(subject.quickPrompt(), subject.quickPrompt()));
			SessionHandle session = subject.port().start(request);
			subject.finish(session);

			SessionResult result = await(session);

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			SessionResult.Completed completed = (SessionResult.Completed) result;
			requireThat(completed.responses().size(), "responses.size()").isEqualTo(2);
			requireThat(Files.isRegularFile(completed.transcript()), "transcript.exists").isTrue();
			requireThat(Files.size(completed.transcript()), "transcript.size").isGreaterThan(0L);
			requireThat(Files.isDirectory(completed.effects()), "effects.isDirectory").isTrue();
			requireThat(completed.model(), "model").isNotBlank();
			requireThat(completed.usage(), "usage").isNotNull();
			requireThat(completed.usage().output(), "usage.output").isGreaterThan(0L);
			requireThat(completed.promptSources(), "promptSources").isNotBlank();
		}
	}

	/**
	 * Checks that a judge that reads evidence leaves the evidence as it found it.
	 *
	 * @throws Exception if the session fails
	 */
	public void judgeLeavesReadOnlyEvidenceUnchanged() throws Exception
	{
		try (Subject subject = opener.open())
		{
			Path evidence = Files.createDirectories(subject.workspace().resolve("evidence"));
			Path file = evidence.resolve("verdict.txt");
			Files.writeString(file, "needs-review\n", UTF_8);
			SessionRequest request = request(subject, SessionRole.JUDGE, List.of(evidence),
				List.of("Read the file " + file + " and reply with its first word. Do not change any file."));
			SessionHandle session = subject.port().start(request);
			subject.finish(session);

			SessionResult result = await(session);

			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			requireThat(Files.readString(file, UTF_8), "evidence").isEqualTo("needs-review\n");
		}
	}

	/**
	 * Checks that a session that CAT interrupts while a turn runs ends as interrupted, and does not report a
	 * failure.
	 *
	 * @throws Exception if the session fails
	 */
	public void interruptEndsARunningTurnAsInterrupted() throws Exception
	{
		try (Subject subject = opener.open())
		{
			SessionRequest request = request(subject, SessionRole.TRIAL, List.of(),
				List.of(subject.longPrompt()));
			SessionHandle session = subject.port().start(request);
			subject.awaitTurnInProgress(session);

			session.interrupt();
			SessionResult result = await(session);

			requireThat(result, "result").isInstanceOf(SessionResult.Interrupted.class);
		}
	}

	/**
	 * Checks that an interrupt that arrives after the session ended neither fails nor changes the result.
	 *
	 * @throws Exception if the session fails
	 */
	public void interruptAfterTheEndChangesNothing() throws Exception
	{
		try (Subject subject = opener.open())
		{
			SessionRequest request = request(subject, SessionRole.TRIAL, List.of(),
				List.of(subject.quickPrompt()));
			SessionHandle session = subject.port().start(request);
			subject.finish(session);
			SessionResult result = await(session);
			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);

			session.interrupt();

			requireThat(await(session), "resultAfterInterrupt").isEqualTo(result);
		}
	}

	/**
	 * Checks that the port classifies the host's login without a session, so that the confirmation step can warn
	 * before it starts one.
	 *
	 * @throws Exception if the classification fails
	 */
	public void reportsTheHostLoginWithoutASession() throws Exception
	{
		try (Subject subject = opener.open())
		{
			HostLogin login = subject.port().hostLogin();

			requireThat(subject.acceptableLogins(), "acceptableLogins").contains(login);
		}
	}

	/**
	 * Checks that the port reports whether the harness reports exit statuses as that harness does.
	 *
	 * @throws Exception if the check fails
	 */
	public void reportsExitStatusAsTheHarnessDoes() throws Exception
	{
		try (Subject subject = opener.open())
		{
			requireThat(subject.port().reportsExitStatus(), "reportsExitStatus").isEqualTo(subject.reportsExitStatus());
		}
	}

	/**
	 * Checks that the port names a positive session environment version and judge version, the release that
	 * introduced the environment version, and the absolute and distinct directories that hold its runs.
	 *
	 * @throws Exception if the check fails
	 */
	public void identifiesItsVersionsAndRunRoots() throws Exception
	{
		try (Subject subject = opener.open())
		{
			SessionPort port = subject.port();

			requireThat(port.sessionEnvironmentVersion(), "sessionEnvironmentVersion").isPositive();
			requireThat(port.sessionEnvironmentRelease(), "sessionEnvironmentRelease").isNotBlank();
			requireThat(port.judgeVersion(), "judgeVersion").isPositive();
			List<Path> runRoots = port.runRoots();
			requireThat(runRoots, "runRoots").isNotEmpty();
			for (Path runRoot : runRoots)
				requireThat(runRoot, "runRoot").isAbsolute();
			requireThat(Set.copyOf(runRoots).size(), "distinctRunRoots").isEqualTo(runRoots.size());
		}
	}

	/**
	 * Checks that a project with no instruction files, hooks, plugins or host settings loads no prompt sources,
	 * registers no hooks, finds no installed plugin and no held-out exposure, and that a plugin with no hooks has no
	 * unapproved hook.
	 *
	 * @throws Exception if the check fails
	 */
	public void reportsNothingForAnEmptyHost() throws Exception
	{
		try (Subject subject = opener.open())
		{
			SessionPort port = subject.port();
			Path project = subject.workspace();

			LoadedSources loaded = port.loadedSources(project, List.of());
			requireThat(loaded.production(), "loaded.production").isEmpty();
			requireThat(loaded.trials(), "loaded.trials").isEmpty();
			requireThat(loaded.productionOnly(), "loaded.productionOnly").isEmpty();
			requireThat(loaded.homeOnly(), "loaded.homeOnly").isEmpty();
			requireThat(loaded.unreachable(), "loaded.unreachable").isEmpty();
			requireThat(port.hookRegistrations(project, List.of()), "hookRegistrations").isEmpty();
			requireThat(port.earlyHooks(project, List.of(), Optional.of(project), "Reply with the single word ok."),
				"earlyHooks").isEmpty();
			requireThat(port.installedPlugins("absent@marketplace"), "installedPlugins").isEmpty();
			requireThat(port.installedPluginNames(), "installedPluginNames").isEmpty();
			requireThat(port.findHeldOutExposure(project), "heldOutExposure").isEmpty();
			requireThat(port.unapprovedHooks(subject.pluginWithoutHooks()), "unapprovedHooks").isEmpty();
		}
	}

	/**
	 * Checks that the activity that the port reads from a completed session's transcript is the same on every read
	 * and leaves the transcript as it found it.
	 *
	 * @throws Exception if the session fails
	 */
	public void readsTheEvidenceOfACompletedTranscript() throws Exception
	{
		try (Subject subject = opener.open())
		{
			SessionRequest request = request(subject, SessionRole.TRIAL, List.of(), List.of(subject.quickPrompt()));
			SessionHandle session = subject.port().start(request);
			subject.finish(session);
			SessionResult result = await(session);
			requireThat(result, "result").isInstanceOf(SessionResult.Completed.class);
			Path transcript = ((SessionResult.Completed) result).transcript();
			String before = Files.readString(transcript, UTF_8);

			SessionEvidence first = subject.port().readEvidence(transcript);
			SessionEvidence second = subject.port().readEvidence(transcript);

			requireThat(second, "secondRead").isEqualTo(first);
			requireThat(Files.readString(transcript, UTF_8), "transcript").isEqualTo(before);
		}
	}

	/**
	 * Checks that a session that CAT interrupts reports the rate-limit windows that the harness reported before the
	 * interrupt.
	 *
	 * @throws Exception if the session fails
	 */
	public void interruptKeepsTheWindowsReportedBeforeIt() throws Exception
	{
		try (Subject subject = opener.open())
		{
			SessionRequest request = request(subject, SessionRole.TRIAL, List.of(), List.of(subject.longPrompt()));
			SessionHandle session = subject.port().start(request);
			subject.awaitTurnInProgress(session);

			session.interrupt();
			SessionResult result = await(session);

			requireThat(result, "result").isInstanceOf(SessionResult.Interrupted.class);
			requireThat(((SessionResult.Interrupted) result).windows(), "windows").
				isEqualTo(subject.windowsReportedBeforeInterrupt());
		}
	}
}
