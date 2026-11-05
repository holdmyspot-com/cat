package com.holdmyspot.cat.common.runner.launcher.client;

import java.io.IOException;
import java.util.List;

/**
 * Starts the holder process that proves to a session launcher that a message comes from a process of the session
 * that the launcher serves.
 * <p>
 * <b>Thread Safety:</b> Implementations must be thread-safe.
 */
@FunctionalInterface
public interface HolderStarter
{
	/**
	 * A started holder, which lives until it is closed or its starter ends.
	 * <p>
	 * <b>Thread Safety:</b> Implementations must be thread-safe.
	 */
	@FunctionalInterface
	interface Holder extends AutoCloseable
	{
		/**
		 * Ends the holder. Ending it again has no effect.
		 */
		@Override
		void close();
	}

	/**
	 * Starts a holder.
	 *
	 * @param arguments the arguments that follow the holder shell on its command line
	 * @return the holder
	 * @throws NullPointerException if {@code arguments} is null
	 * @throws IOException          if the holder cannot be started
	 */
	Holder start(List<String> arguments) throws IOException;

	/**
	 * Returns the starter of real holder processes, which run the holder shell with a standard input that stays open
	 * until the holder is closed.
	 *
	 * @return the starter
	 */
	static HolderStarter processes()
	{
		return new ProcessHolderStarter();
	}
}
