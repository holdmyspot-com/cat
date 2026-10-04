package io.github.cowwoc.cat.common.optimizer.port;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.evidence.SessionEvidence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link SessionPort} whose sessions end only when the test ends them, in any order.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 */
public final class FakeSessionPort implements SessionPort
{
	private final List<FakeSession> sessions = new ArrayList<>();
	private final HostLogin login;
	private final Optional<String> heldOutExposure;
	private final Map<Path, String> evidence = new HashMap<>();
	private final Map<Path, Path> evidenceSources = new HashMap<>();

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
		this(login, Optional.empty());
	}

	/**
	 * Creates a port that has started no sessions.
	 *
	 * @param login the login that {@link #hostLogin()} reports
	 * @param heldOutExposure the value that {@link #findHeldOutExposure(Path)} reports
	 * @throws NullPointerException if any argument is null
	 */
	public FakeSessionPort(HostLogin login, Optional<String> heldOutExposure)
	{
		requireThat(login, "login").isNotNull();
		requireThat(heldOutExposure, "heldOutExposure").isNotNull();
		this.login = login;
		this.heldOutExposure = heldOutExposure;
	}

	@Override
	public HostLogin hostLogin()
	{
		return login;
	}

	@Override
	public Optional<String> findHeldOutExposure(Path project)
	{
		requireThat(project, "project").isNotNull();
		return heldOutExposure;
	}

	@Override
	public String renderEvidence(SessionResult.Completed result, Path source)
	{
		requireThat(result, "result").isNotNull();
		requireThat(source, "source").isNotNull();
		synchronized (sessions)
		{
			evidenceSources.put(result.transcript(), source);
			return evidence.getOrDefault(result.transcript(), new SessionEvidence(List.of()).render() +
				"\nFile changes\nNo file changes.\n");
		}
	}

	/**
	 * Supplies a rendered section for a retained trial transcript.
	 *
	 * @param transcript the trial transcript
	 * @param section the complete section returned by the evidence renderer
	 */
	public void supplyEvidence(Path transcript, String section)
	{
		requireThat(transcript, "transcript").isNotNull();
		requireThat(section, "section").isNotNull();
		synchronized (sessions)
		{
			evidence.put(transcript, section);
		}
	}

	/**
	 * Returns the staged sources supplied to evidence rendering.
	 *
	 * @return each transcript's original source
	 */
	public Map<Path, Path> evidenceSources()
	{
		synchronized (sessions)
		{
			return Map.copyOf(evidenceSources);
		}
	}

	@Override
	public SessionHandle start(SessionRequest request)
	{
		requireThat(request, "request").isNotNull();
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
			result.complete(new SessionResult.Interrupted(List.of()));
		}
	}
}
