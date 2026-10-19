package com.holdmyspot.cat.common.git.replay;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Retains immutable object path states for {@link History} while rereading movable symbolic revisions.
 */
public final class PathStates
{
	private final Map<ObjectPath, PathState> states = new ConcurrentHashMap<>();

	/**
	 * Creates an initially empty path-state inventory.
	 */
	public PathStates()
	{
	}

	/**
	 * Reads a path state, reusing only full immutable object identities.
	 *
	 * @param revision the supplied Git revision
	 * @param path the repository-relative path
	 * @param reader the operation that reads the current path state
	 * @return the current state or the state already read for an immutable object
	 * @throws IOException if the path cannot be read
	 */
	public PathState read(String revision, String path, Reader reader) throws IOException
	{
		if (!revision.matches("[0-9a-f]{40}"))
			return reader.read();
		ObjectPath key = new ObjectPath(revision, path);
		PathState state = states.get(key);
		if (state == null)
		{
			state = reader.read();
			states.put(key, state);
		}
		return state;
	}

	/**
	 * Supplies one current path state to {@link PathStates}.
	 */
	@FunctionalInterface
	public interface Reader
	{
		/**
		 * Reads the current path state.
		 *
		 * @return the path state
		 * @throws IOException if the path cannot be read
		 */
		PathState read() throws IOException;
	}

	/**
	 * One path within an immutable Git object.
	 *
	 * @param objectId the full commit or tree object ID
	 * @param path the repository-relative path
	 */
	private record ObjectPath(String objectId, String path)
	{
	}
}
