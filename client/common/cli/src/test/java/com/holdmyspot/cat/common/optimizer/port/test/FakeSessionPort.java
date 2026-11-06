package com.holdmyspot.cat.common.optimizer.port.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A {@link SessionPort} whose sessions end only when the test ends them, in any order.
 * <p>
 * The answers that a real port gives without a session are checked by {@code SessionPortContract}, which runs against
 * this fake and the real adapters: the versions, run roots, empty answers about the host, evidence reading, the
 * exit-status answer and the windows of an interrupted session. {@link #loading(Function)},
 * {@link #unstartable(Predicate)} and {@link #supplyEvidence(Path, SessionEvidence)} are controls that make the fake
 * answer what no contract case can induce in a real port, such as a project with prompt files or a session that
 * cannot start.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeSessionPort implements SessionPort
{
	/**
	 * The session environment version that every fake port reports.
	 */
	public static final int SESSION_ENVIRONMENT_VERSION = 7;
	/**
	 * The CAT release that every fake port reports as having introduced its session environment version.
	 */
	public static final String SESSION_ENVIRONMENT_RELEASE = "0.7";
	/**
	 * The judge version that every fake port reports.
	 */
	public static final int JUDGE_VERSION = 5;
	/**
	 * The directories that a port reports as holding its runs unless the test supplies its own: a run root and a
	 * held-out run root, as a real port has, which do not exist.
	 */
	private static final List<Path> DEFAULT_RUN_ROOTS = List.of(
		FileSystems.getDefault().getRootDirectories().iterator().next().resolve("fake-runs/development"),
		FileSystems.getDefault().getRootDirectories().iterator().next().resolve("fake-runs/held-out"));
	private final List<FakeSession> sessions = new ArrayList<>();
	private final HostLogin login;
	private final String heldOutExposure;
	private final List<Path> runRoots;
	private final Map<Path, SessionEvidence> evidence = new HashMap<>();
	private final Set<Path> readTranscripts = new HashSet<>();
	/**
	 * Returns the files that load in a project, which {@link #loadedSources(Path, List)} reports.
	 */
	private volatile Function<Path, LoadedSources> loading = _ -> new LoadedSources(List.of(), List.of(), List.of(),
		List.of(), List.of());
	/**
	 * Selects the requests whose sessions cannot start, which {@link #start(SessionRequest)} refuses.
	 */
	private volatile Predicate<SessionRequest> unstartable = _ -> false;
	/**
	 * Whether {@link #reportsExitStatus()} answers {@code true}, which the harness that the test stands for decides.
	 */
	private volatile boolean reportsExitStatus = true;

	/**
	 * Creates a port that has started no sessions and whose host login is not identified.
	 */
	public FakeSessionPort()
	{
		this(HostLogin.UNKNOWN);
	}

	/**
	 * Creates a port that has started no sessions.
	 *
	 * @param login the login that {@link #hostLogin()} reports
	 * @throws NullPointerException if {@code login} is null
	 */
	public FakeSessionPort(HostLogin login)
	{
		this(login, null);
	}

	/**
	 * Creates a port that has started no sessions.
	 *
	 * @param login the login that {@link #hostLogin()} reports
	 * @param heldOutExposure the value that {@link #findHeldOutExposure(Path)} reports, or null if it reports none
	 * @throws NullPointerException if {@code login} is null
	 */
	public FakeSessionPort(HostLogin login, String heldOutExposure)
	{
		this(login, heldOutExposure, DEFAULT_RUN_ROOTS);
	}

	/**
	 * Creates a port that has started no sessions.
	 *
	 * @param login the login that {@link #hostLogin()} reports
	 * @param heldOutExposure the value that {@link #findHeldOutExposure(Path)} reports, or null if it reports none
	 * @param runRoots the directories that {@link #runRoots()} reports
	 * @throws NullPointerException if {@code login} or {@code runRoots} are null
	 */
	public FakeSessionPort(HostLogin login, String heldOutExposure, List<Path> runRoots)
	{
		requireThat(login, "login").isNotNull();
		requireThat(runRoots, "runRoots").isNotNull();
		this.login = login;
		this.heldOutExposure = heldOutExposure;
		this.runRoots = List.copyOf(runRoots);
	}

	@Override
	public int sessionEnvironmentVersion()
	{
		return SESSION_ENVIRONMENT_VERSION;
	}

	@Override
	public String sessionEnvironmentRelease()
	{
		return SESSION_ENVIRONMENT_RELEASE;
	}

	@Override
	public int judgeVersion()
	{
		return JUDGE_VERSION;
	}

	@Override
	public List<Path> runRoots()
	{
		return runRoots;
	}

	@Override
	public LoadedSources loadedSources(Path project, List<PluginBase> plugins)
	{
		return loading.apply(project);
	}

	/**
	 * Sets the files that load in a project.
	 *
	 * @param loading returns the files that load in the project that it receives; by default, none load
	 * @throws NullPointerException if {@code loading} is null
	 */
	public void loading(Function<Path, LoadedSources> loading)
	{
		requireThat(loading, "loading").isNotNull();
		this.loading = loading;
	}

	@Override
	public List<HookRegistration> hookRegistrations(Path project, List<PluginBase> plugins)
	{
		return List.of();
	}

	@Override
	public List<HookInvocation> earlyHooks(Path project, List<PluginBase> plugins, Optional<Path> home, String prompt)
	{
		return List.of();
	}

	@Override
	public List<Path> installedPlugins(String name)
	{
		return List.of();
	}

	@Override
	public List<String> installedPluginNames()
	{
		return List.of();
	}

	@Override
	public HostLogin hostLogin()
	{
		return login;
	}

	@Override
	public List<String> unapprovedHooks(Path plugin)
	{
		requireThat(plugin, "plugin").isNotNull();
		return List.of();
	}

	@Override
	public Optional<String> findHeldOutExposure(Path project)
	{
		requireThat(project, "project").isNotNull();
		return Optional.ofNullable(heldOutExposure);
	}

	@Override
	public SessionEvidence readEvidence(Path transcript)
	{
		requireThat(transcript, "transcript").isNotNull();
		synchronized (sessions)
		{
			readTranscripts.add(transcript);
			return evidence.getOrDefault(transcript, new SessionEvidence(List.of()));
		}
	}

	@Override
	public boolean reportsExitStatus()
	{
		return reportsExitStatus;
	}

	/**
	 * Sets whether the harness that the test stands for reports the exit status of the commands that a session runs.
	 * Claude Code does not and Codex does.
	 *
	 * @param reportsExitStatus the answer that {@link #reportsExitStatus()} gives; by default, {@code true}
	 */
	public void reportsExitStatus(boolean reportsExitStatus)
	{
		this.reportsExitStatus = reportsExitStatus;
	}

	/**
	 * Supplies the activity of a retained trial transcript.
	 *
	 * @param transcript the trial transcript
	 * @param activity the activity that {@link #readEvidence(Path)} returns for it
	 */
	public void supplyEvidence(Path transcript, SessionEvidence activity)
	{
		requireThat(transcript, "transcript").isNotNull();
		requireThat(activity, "activity").isNotNull();
		synchronized (sessions)
		{
			evidence.put(transcript, activity);
		}
	}

	/**
	 * Returns the transcripts whose activity was read.
	 *
	 * @return the transcripts
	 */
	public Set<Path> readTranscripts()
	{
		synchronized (sessions)
		{
			return Set.copyOf(readTranscripts);
		}
	}

	/**
	 * Sets the requests whose sessions cannot start. A real port reports such a session by throwing
	 * {@code IOException} from {@link SessionPort#start(SessionRequest)}, as its contract documents.
	 *
	 * @param unstartable selects each request whose session cannot start; by default, every session starts
	 * @throws NullPointerException if {@code unstartable} is null
	 */
	public void unstartable(Predicate<SessionRequest> unstartable)
	{
		requireThat(unstartable, "unstartable").isNotNull();
		this.unstartable = unstartable;
	}

	@Override
	public SessionHandle start(SessionRequest request) throws IOException
	{
		requireThat(request, "request").isNotNull();
		if (unstartable.test(request))
			throw new IOException("The fake " + request.role() + " session cannot start");
		FakeSession session = new FakeSession(request);
		synchronized (sessions)
		{
			sessions.add(session);
		}
		return session;
	}

	/**
	 * Returns the sessions started so far.
	 *
	 * @return the sessions, in start order
	 */
	public List<FakeSession> sessions()
	{
		synchronized (sessions)
		{
			return List.copyOf(sessions);
		}
	}

	/**
	 * A session that the test ends.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	public static final class FakeSession implements SessionHandle
	{
		private final SessionRequest request;
		private final CompletableFuture<SessionResult> result = new CompletableFuture<>();
		private final AtomicBoolean interruptRequested = new AtomicBoolean();
		private volatile List<RateLimitWindow> windows = List.of();

		/**
		 * Creates a running session.
		 *
		 * @param request the request that started the session
		 */
		private FakeSession(SessionRequest request)
		{
			this.request = request;
		}

		/**
		 * Returns the request that started the session.
		 *
		 * @return the request
		 */
		public SessionRequest request()
		{
			return request;
		}

		@Override
		public CompletionStage<SessionResult> completion()
		{
			return result.minimalCompletionStage();
		}

		/**
		 * Ends the session with a result. Ending a session that has ended does nothing.
		 *
		 * @param sessionResult the result
		 * @throws NullPointerException if {@code sessionResult} is null
		 */
		public void end(SessionResult sessionResult)
		{
			requireThat(sessionResult, "sessionResult").isNotNull();
			result.complete(sessionResult);
		}

		/**
		 * Indicates whether the session has ended.
		 *
		 * @return {@code true} if the session has a result
		 */
		public boolean hasEnded()
		{
			return result.isDone();
		}

		/**
		 * Sets the rate-limit windows that the harness has reported so far, which an interrupted session carries.
		 *
		 * @param windows the windows
		 * @throws NullPointerException if {@code windows} is null
		 */
		public void reportWindows(List<RateLimitWindow> windows)
		{
			requireThat(windows, "windows").isNotNull();
			this.windows = List.copyOf(windows);
		}

		/**
		 * Indicates whether CAT asked the session to stop.
		 *
		 * @return {@code true} if {@link #interrupt()} was called
		 */
		public boolean isInterruptRequested()
		{
			return interruptRequested.get();
		}

		@Override
		public void interrupt()
		{
			interruptRequested.set(true);
			result.complete(new SessionResult.Interrupted(windows));
		}
	}
}
