package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.io.IOException;

/**
 * Reads bytes already retained by a native runner.
 */
@FunctionalInterface
public interface NativeEventReader
{
	/**
	 * Reads the referenced immutable native event.
	 *
	 * @param reference the retained event reference
	 * @return the native event bytes
	 * @throws IOException if the referenced event cannot be read
	 */
	byte[] read(EvidenceRef reference) throws IOException;
}
