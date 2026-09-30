package io.github.cowwoc.cat.common.optimizer.api;

import java.io.IOException;

/**
 * Reads immutable evidence addressed by its content identity.
 */
@FunctionalInterface
public interface EvidenceReader
{
	/**
	 * Reads the exact bytes of one content-addressed evidence item.
	 *
	 * @param reference the evidence address to read
	 * @return the evidence bytes
	 * @throws IOException if the evidence cannot be read or fails content verification
	 */
	byte[] read(EvidenceRef reference) throws IOException;
}
