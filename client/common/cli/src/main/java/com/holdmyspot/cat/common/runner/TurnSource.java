package com.holdmyspot.cat.common.runner;

import java.io.IOException;
import java.util.Optional;

/**
 * The runner records that a launch streams to its child, one at a time as they arrive, taken without waiting.
 */
public interface TurnSource extends AutoCloseable
{
	/**
	 * Takes the next record that has arrived.
	 *
	 * @return the record, without its line terminator, or empty if none has arrived
	 * @throws IOException if the records cannot be read
	 */
	Optional<String> poll() throws IOException;

	/**
	 * Indicates whether every record was taken and no record follows.
	 *
	 * @return {@code true} if no record follows
	 * @throws IOException if the records cannot be read
	 */
	boolean ended() throws IOException;

	/**
	 * Stops reading records.
	 */
	@Override
	void close();
}
