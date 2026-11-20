package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;


/**
 * Tracks and closes the persistent harness processes started by one request bridge.
 */
public final class LiveNestedSessions implements AutoCloseable
{
	private final Map<String, LiveNestedSession> sessions = new HashMap<>();

	/**
	 * Creates an empty live-session registry.
	 */
	public LiveNestedSessions()
	{
	}

	/**
	 * Registers a newly started live session.
	 *
	 * @param session the live session
	 *
	 * @throws NullPointerException if {@code session} is null
	 *
	 * @throws IllegalArgumentException if its run ID is already registered
	 */
	public void add(LiveNestedSession session)
	{
		requireThat(session, "session").isNotNull();
		synchronized (sessions)
		{
			String id = session.nestedSession().id();
			if (sessions.putIfAbsent(id, session) != null)
				throw new IllegalArgumentException("Run is already registered: " + id);
		}
	}

	/**
	 * Sends a turn to a registered live session.
	 *
	 * @param slotId the non-blank run ID
	 *
	 * @param request the non-blank framed request
	 *
	 * @return the terminal turn result
	 *
	 * @throws IOException if the run is unknown or cannot accept the turn
	 */
	public HarnessProcessResult sendTurn(String slotId, String request) throws IOException
	{
		requireThat(slotId, "slotId").isNotBlank();
		requireThat(request, "request").isNotBlank();
		return session(slotId).sendTurn(request);
	}

	/**
	 * Completes and unregisters a live session.
	 *
	 * @param slotId the non-blank run ID
	 *
	 * @throws IOException if the run is unknown or shutdown fails
	 */
	public void complete(String slotId) throws IOException
	{
		requireThat(slotId, "slotId").isNotBlank();
		try (LiveNestedSession session = remove(slotId))
		{
			if (session == null)
				throw new IOException("Unknown live run: " + slotId);
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
		List<LiveNestedSession> openSessions;
		synchronized (sessions)
		{
			openSessions = List.copyOf(sessions.values());
			sessions.clear();
		}
		IOException failure = null;
		for (LiveNestedSession session : openSessions)
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
	 * @param slotId the non-blank run ID
	 *
	 * @return the live registered session
	 *
	 * @throws IOException if the run is unknown
	 */
	private LiveNestedSession session(String slotId) throws IOException
	{
		assert slotId != null;
		synchronized (sessions)
		{
			LiveNestedSession result = sessions.get(slotId);
			if (result == null)
				throw new IOException("Unknown live run: " + slotId);
			return result;
		}
	}

	/**
	 * Removes one registered session and transfers ownership to the caller.
	 *
	 * @param slotId the non-blank run ID
	 *
	 * @return the removed session, or {@code null} if it was unknown
	 */
	private LiveNestedSession remove(String slotId)
	{
		assert slotId != null;
		synchronized (sessions)
		{
			return sessions.remove(slotId);
		}
	}
}
