package io.github.cowwoc.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks and closes the persistent harness processes started by one request bridge.
 */
public final class HarnessRunSessions implements AutoCloseable
{
	private final Map<String, HarnessRunSession> sessions = new HashMap<>();

	/**
	 * Creates an empty live-session registry.
	 */
	public HarnessRunSessions()
	{
	}

	/**
	 * Registers a newly started live session.
	 *
	 * @param session the live session
	 * @throws NullPointerException if {@code session} is null
	 * @throws IllegalArgumentException if its run ID is already registered
	 */
	public void add(HarnessRunSession session)
	{
		requireThat(session, "session").isNotNull();
		synchronized (sessions)
		{
			String id = session.run().id();
			if (sessions.putIfAbsent(id, session) != null)
				throw new IllegalArgumentException("Run is already registered: " + id);
		}
	}

	/**
	 * Sends a turn to a registered live session.
	 *
	 * @param runId the non-blank run ID
	 * @param request the non-blank framed request
	 * @return the terminal turn result
	 * @throws IOException if the run is unknown or cannot accept the turn
	 */
	public HarnessProcessResult sendTurn(String runId, String request) throws IOException
	{
		requireThat(runId, "runId").isNotBlank();
		requireThat(request, "request").isNotBlank();
		return session(runId).sendTurn(request);
	}

	/**
	 * Completes and unregisters a live session.
	 *
	 * @param runId the non-blank run ID
	 * @throws IOException if the run is unknown or shutdown fails
	 */
	public void complete(String runId) throws IOException
	{
		requireThat(runId, "runId").isNotBlank();
		try (HarnessRunSession session = remove(runId))
		{
			if (session == null)
				throw new IOException("Unknown live run: " + runId);
		}
	}

	/**
	 * Closes every live session when the bridge process terminates.
	 *
	 * @throws IOException if one or more session shutdowns fail
	 */
	@Override
	public void close() throws IOException
	{
		List<HarnessRunSession> openSessions;
		synchronized (sessions)
		{
			openSessions = List.copyOf(sessions.values());
			sessions.clear();
		}
		IOException failure = null;
		for (HarnessRunSession session : openSessions)
		{
			try (session)
			{
			}
			catch (IOException e)
			{
				if (failure == null)
					failure = e;
				else
					failure.addSuppressed(e);
			}
		}
		if (failure != null)
			throw failure;
	}

	/**
	 * Returns one registered session without transferring its ownership.
	 *
	 * @param runId the non-blank run ID
	 * @return the live registered session
	 * @throws IOException if the run is unknown
	 */
	private HarnessRunSession session(String runId) throws IOException
	{
		assert runId != null;
		synchronized (sessions)
		{
			HarnessRunSession result = sessions.get(runId);
			if (result == null)
				throw new IOException("Unknown live run: " + runId);
			return result;
		}
	}

	/**
	 * Removes one registered session and transfers ownership to the caller.
	 *
	 * @param runId the non-blank run ID
	 * @return the removed session, or {@code null} if it was unknown
	 */
	private HarnessRunSession remove(String runId)
	{
		assert runId != null;
		synchronized (sessions)
		{
			return sessions.remove(runId);
		}
	}
}
